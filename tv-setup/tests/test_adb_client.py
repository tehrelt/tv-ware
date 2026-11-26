import io
import os
import stat
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch

from tv_setup.adb_client import (
    DEFAULT_TOOLS_DIR,
    PLATFORM_TOOLS_URL,
    AdbClient,
    AdbError,
    AdbResult,
    UsbOrTcpDevice,
    download_platform_tools,
    find_adb_executable,
    parse_adb_devices,
    safe_extract_zip,
)


def make_zip(*entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, data in entries:
            archive.writestr(name, data)
    return buffer.getvalue()


def make_bad_crc_zip():
    data = make_zip(
        ("platform-tools/adb.exe", b"adb"),
        ("platform-tools/AdbWinApi.dll", b"original DLL data"),
    )
    return data.replace(b"original DLL data", b"modified DLL data")


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.tools = self.root / "tools"

    def test_find_adb_prefers_local_tools_then_path_then_sdk(self):
        local_adb = self.tools / "platform-tools" / "adb.exe"
        sdk_adb = self.root / "Android" / "Sdk" / "platform-tools" / "adb.exe"
        for path in (local_adb, sdk_adb):
            path.parent.mkdir(parents=True)
            path.touch()
        path_adb = self.root / "other" / "adb.exe"
        which = Mock(return_value=str(path_adb))
        self.assertEqual(
            find_adb_executable(self.tools, which, str(self.root)), local_adb
        )
        which.assert_not_called()
        local_adb.unlink()
        self.assertEqual(
            find_adb_executable(self.tools, which, str(self.root)), path_adb
        )
        which.assert_called_once_with("adb")
        with patch.dict(os.environ, {"LOCALAPPDATA": str(self.root)}):
            self.assertEqual(find_adb_executable(self.tools, lambda _: None), sdk_adb)
        sdk_adb.unlink()
        self.assertIsNone(
            find_adb_executable(self.tools, lambda _: None, str(self.root))
        )

    def test_safe_extract_zip_rejects_unsafe_members_before_extracting(self):
        symlink = zipfile.ZipInfo("link")
        symlink.create_system = 3
        symlink.external_attr = (stat.S_IFLNK | 0o777) << 16
        archive = self.root / "bad.zip"
        dest = self.root / "out"
        for member in (
            "../evil.txt",
            "/evil.txt",
            r"C:\evil.txt",
            r"..\evil.txt",
            symlink,
        ):
            with self.subTest(member=member):
                archive.write_bytes(
                    make_zip(("first.txt", "ok"), (member, "../evil.txt"))
                )
                with self.assertRaises(ValueError):
                    safe_extract_zip(archive, dest)
                self.assertFalse((self.root / "evil.txt").exists())
                self.assertFalse((dest / "first.txt").exists())

    def test_safe_extract_zip_reports_corruption_as_value_error(self):
        archive = self.root / "corrupt.zip"
        for data in (b"not a ZIP", make_bad_crc_zip()):
            with self.subTest(data=data[:10]):
                archive.write_bytes(data)
                with self.assertRaisesRegex(ValueError, "Corrupt ZIP"):
                    safe_extract_zip(archive, self.root / "out")

    def test_failed_extraction_does_not_publish_adb_and_allows_retry(self):
        corrupt = io.BytesIO(make_bad_crc_zip())
        valid = io.BytesIO(make_zip(("platform-tools/adb.exe", b"adb")))
        corrupt.headers = valid.headers = {}
        with patch("urllib.request.urlopen", side_effect=[corrupt, valid]):
            with self.assertRaises(ValueError):
                download_platform_tools(self.tools)
            self.assertFalse((self.tools / "platform-tools" / "adb.exe").exists())
            self.assertEqual(list(self.tools.iterdir()), [])
            self.assertEqual(download_platform_tools(self.tools).read_bytes(), b"adb")

    def test_download_replaces_existing_incomplete_platform_tools(self):
        installed = self.tools / "platform-tools"
        installed.mkdir(parents=True)
        (installed / "leftover.dll").write_bytes(b"old")
        response = io.BytesIO(make_zip(("platform-tools/adb.exe", b"new")))
        response.headers = {}
        with patch("urllib.request.urlopen", return_value=response):
            self.assertEqual(download_platform_tools(self.tools), installed / "adb.exe")
        self.assertEqual((installed / "adb.exe").read_bytes(), b"new")
        self.assertFalse((installed / "leftover.dll").exists())
        self.assertEqual(list(self.tools.iterdir()), [installed])

    def test_failed_publication_restores_existing_platform_tools(self):
        installed = self.tools / "platform-tools"
        installed.mkdir(parents=True)
        leftover = installed / "leftover.dll"
        leftover.write_bytes(b"old")
        response = io.BytesIO(make_zip(("platform-tools/adb.exe", b"new")))
        response.headers = {}
        replace = Path.replace

        def fail_staging_replace(source, target):
            if target == installed and (source / "adb.exe").is_file():
                self.assertFalse(installed.exists())
                raise PermissionError("publish denied")
            return replace(source, target)

        with (
            patch("urllib.request.urlopen", return_value=response),
            patch.object(
                Path,
                "replace",
                fail_staging_replace,
            ),
        ):
            with self.assertRaisesRegex(PermissionError, "publish denied"):
                download_platform_tools(self.tools)
        self.assertEqual(leftover.read_bytes(), b"old")
        self.assertEqual(list(self.tools.iterdir()), [installed])

    def test_cleanup_failure_does_not_fail_successful_publication(self):
        installed = self.tools / "platform-tools"
        installed.mkdir(parents=True)
        (installed / "leftover.dll").write_bytes(b"old")
        response = io.BytesIO(make_zip(("platform-tools/adb.exe", b"new")))
        response.headers = {}

        with (
            patch("urllib.request.urlopen", return_value=response),
            patch(
                "shutil.rmtree",
                side_effect=PermissionError("cleanup denied"),
            ) as remove,
            self.assertLogs("tv_setup.adb_client", level="WARNING") as logs,
        ):
            self.assertEqual(download_platform_tools(self.tools), installed / "adb.exe")

        self.assertEqual((installed / "adb.exe").read_bytes(), b"new")
        self.assertEqual(len(remove.call_args_list), 2)
        for call in remove.call_args_list:
            self.assertIn(str(call.args[0]), "\n".join(logs.output))
        self.assertFalse(list(self.tools.glob("*.zip.part")))

    def test_download_reports_progress_and_removes_partial_archive(self):
        data = make_zip(("platform-tools/", b""), ("platform-tools/adb.exe", b"adb"))
        response = io.BytesIO(data)
        response.headers = {"Content-Length": str(len(data))}
        progress = Mock()
        with patch("urllib.request.urlopen", return_value=response) as urlopen:
            adb = download_platform_tools(self.tools, progress)
        self.assertEqual(adb, self.tools / "platform-tools" / "adb.exe")
        self.assertEqual(adb.read_bytes(), b"adb")
        progress.assert_called_with(len(data), len(data))
        self.assertEqual(urlopen.call_args.args[0], PLATFORM_TOOLS_URL)
        self.assertFalse(list(self.tools.glob("*.zip.part")))

    def test_failed_download_cleans_partial_archive(self):
        response = Mock()
        response.headers = {}
        response.read.side_effect = [b"partial", OSError("interrupted")]
        with patch("urllib.request.urlopen") as urlopen:
            urlopen.return_value.__enter__.return_value = response
            with self.assertRaises(OSError):
                download_platform_tools(self.tools)
        self.assertFalse(list(self.tools.glob("*.zip.part")))

    def test_archive_without_adb_raises_and_cleans_up(self):
        response = io.BytesIO(make_zip(("platform-tools/NOTICE.txt", b"notice")))
        response.headers = {}
        with patch("urllib.request.urlopen", return_value=response):
            with self.assertRaisesRegex(AdbError, r"adb\.exe"):
                download_platform_tools(self.tools)
        self.assertFalse(list(self.tools.glob("*.zip.part")))

    def test_archive_unlink_failure_logs_warning_and_returns_adb(self):
        response = io.BytesIO(make_zip(("platform-tools/adb.exe", b"new")))
        response.headers = {}
        orig_unlink = Path.unlink

        def fail_part_unlink(path, missing_ok=False):
            if path.name.endswith(".zip.part"):
                raise PermissionError("archive locked")
            return orig_unlink(path, missing_ok=missing_ok)

        with (
            patch("urllib.request.urlopen", return_value=response),
            patch.object(Path, "unlink", fail_part_unlink),
            self.assertLogs("tv_setup.adb_client", level="WARNING") as logs,
        ):
            adb = download_platform_tools(self.tools)

        self.assertEqual(adb, self.tools / "platform-tools" / "adb.exe")
        self.assertEqual(adb.read_bytes(), b"new")
        self.assertIn("archive locked", "\n".join(logs.output))


class AdbClientTests(unittest.TestCase):
    def test_parse_devices_includes_tcp_usb_and_optional_metadata(self):
        raw = (
            "* daemon started successfully\r\n"
            "List of devices attached\r\n"
            "192.168.31.42:5555 device product:jaws model:MiTV_AFKR0 device:jaws transport_id:2\r\n"
            "ABC12345 unauthorized usb:1-2 transport_id:3\r\n"
            "malformed\r\n\r\n"
        )
        self.assertEqual(
            parse_adb_devices(raw),
            [
                UsbOrTcpDevice(
                    "192.168.31.42:5555", "device", "MiTV_AFKR0", "jaws", "2"
                ),
                UsbOrTcpDevice("ABC12345", "unauthorized", "", "", "3"),
            ],
        )

    def test_ensure_adb_honors_explicit_path(self):
        with patch("tv_setup.adb_client.find_adb_executable") as find:
            self.assertEqual(
                AdbClient(Path("chosen.exe")).ensure_adb(), Path("chosen.exe")
            )
        find.assert_not_called()

    @patch("tv_setup.adb_client.download_platform_tools")
    @patch("tv_setup.adb_client.find_adb_executable")
    def test_ensure_adb_finds_or_downloads_and_caches(self, find, download):
        for found in (None, Path("found.exe")):
            with self.subTest(found=found):
                find.reset_mock(return_value=True)
                download.reset_mock(return_value=True)
                find.return_value = found
                download.return_value = Path("downloaded.exe")
                client = AdbClient()
                expected = found or download.return_value
                self.assertEqual(client.ensure_adb(), expected)
                self.assertEqual(client.ensure_adb(), expected)
                find.assert_called_once_with(DEFAULT_TOOLS_DIR)
                if found:
                    download.assert_not_called()
                else:
                    download.assert_called_once_with(DEFAULT_TOOLS_DIR)

    @patch("subprocess.run")
    def test_shell_passes_serial_command_timeout_and_flags(self, run):
        run.return_value = subprocess.CompletedProcess([], 1, "out\n", "err\n")
        client = AdbClient(Path("adb.exe"))
        self.assertEqual(
            client.shell("ABC", "echo hello", timeout=2.5),
            AdbResult(1, "out\n", "err\n"),
        )
        run.assert_called_once_with(
            ["adb.exe", "-s", "ABC", "shell", "echo hello"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=2.5,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )

    @patch("subprocess.run", side_effect=subprocess.TimeoutExpired(["adb"], 15))
    def test_run_wraps_timeout(self, _run):
        with self.assertRaises(AdbError):
            AdbClient(Path("adb.exe")).run(["version"])

    def test_connect_returns_success_only_without_failure_text(self):
        client = AdbClient(Path("adb.exe"))
        target = "192.168.31.42:5555"
        for stdout, stderr, expected in (
            (f"connected to {target}", "", True),
            ("", f"Already connected to {target}", True),
            (f"cannot connect to {target}: Connection refused", "", False),
            (f"connected to {target}", "failed to connect", False),
        ):
            with self.subTest(stdout=stdout, stderr=stderr):
                with patch.object(
                    client, "run", return_value=AdbResult(0, stdout, stderr)
                ) as run:
                    ok, message = client.connect(target)
                self.assertIs(ok, expected)
                self.assertIn(stdout or stderr, message)
                run.assert_called_once_with(["connect", target])

    def test_pair_checks_code_and_reports_result(self):
        client = AdbClient(Path("adb.exe"))
        target = "192.168.31.42:12345"
        with patch.object(client, "run") as run:
            self.assertFalse(client.pair(target, " \t")[0])
            run.assert_not_called()
            result = f"Successfully paired to {target}"
            run.return_value = AdbResult(0, "", result)
            self.assertEqual(client.pair(target, "123456"), (True, result))
            run.assert_called_once_with(["pair", target, "123456"])
            run.return_value = AdbResult(1, "", "Failed to pair")
            self.assertEqual(client.pair(target, "123456"), (False, "Failed to pair"))

    def test_install_and_uninstall_forward_results_and_arguments(self):
        client = AdbClient(Path("adb.exe"))
        apk = Path("some app.apk")
        result = AdbResult(1, "", "Failure")
        with patch.object(client, "run", return_value=result) as run:
            self.assertIs(client.install("ABC", apk), result)
            self.assertEqual(
                run.call_args.args[0], ["install", "-r", "-d", "-g", str(apk)]
            )
            self.assertEqual(run.call_args.kwargs["serial"], "ABC")
            self.assertIs(client.install("ABC", apk, downgrade=False), result)
            self.assertEqual(run.call_args.args[0], ["install", "-r", "-g", str(apk)])
            self.assertIs(client.uninstall("ABC", "com.example.app"), result)
            run.assert_called_with(["uninstall", "com.example.app"], serial="ABC")

    def test_devices_runs_long_listing_and_parses_output(self):
        client = AdbClient(Path("adb.exe"))
        with patch.object(
            client, "run", return_value=AdbResult(0, "ABC device\n", "")
        ) as run:
            self.assertEqual(
                client.devices(), [UsbOrTcpDevice("ABC", "device", "", "", "")]
            )
            run.assert_called_once_with(["devices", "-l"])
            run.return_value = AdbResult(1, "", "daemon error")
            with self.assertRaisesRegex(AdbError, "daemon error"):
                client.devices()


if __name__ == "__main__":
    unittest.main()
