import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from tv_setup.adb_client import AdbError, AdbResult
from tv_setup.installer import (
    REPO_SOURCES,
    LocalApk,
    RepoSource,
    SignatureConflictError,
    download_launcher_apk,
    download_repo_apks,
    import_local_apk,
    install_apk,
    needs_download,
    reinstall_after_uninstall,
    scan_local_apks,
    select_release_asset,
)


def _json_response(
    payload: dict | list,
    url: str = "https://api.github.com/repos/example/repo/releases/latest",
):
    raw = json.dumps(payload).encode("utf-8")
    resp = io.BytesIO(raw)
    resp.headers = {"Content-Length": str(len(raw))}
    resp.geturl = lambda: url
    return resp


def _bytes_response(
    data: bytes, url: str = "https://objects.githubusercontent.com/asset.apk"
):
    resp = io.BytesIO(data)
    resp.headers = {"Content-Length": str(len(data))}
    resp.geturl = lambda: url
    return resp


class InstallerTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.apks_dir = self.root / "apks"

    def test_repo_sources_match_expected_public_repositories(self):
        by_key = {src.key: src for src in REPO_SOURCES}
        self.assertEqual(set(by_key), {"tv-launcher", "happ"})
        self.assertEqual(
            by_key["tv-launcher"],
            RepoSource(
                key="tv-launcher",
                label="tv-launcher",
                release_url="https://api.github.com/repos/tehrelt/tv-ware/releases?per_page=20",
                asset_name="tv-launcher.apk",
                local_filename="tv-launcher.apk",
                package_name="com.tvware.launcher",
                tag_prefix="launcher-v",
            ),
        )
        self.assertEqual(
            by_key["happ"],
            RepoSource(
                key="happ",
                label="Happ",
                release_url="https://api.github.com/repos/Happ-proxy/happ-android/releases/latest",
                asset_name="Happ.apk",
                local_filename="Happ.apk",
                package_name="su.happ.proxyutility",
            ),
        )

    def test_select_release_asset_validates_https_and_extracts_metadata(self):
        payload = {
            "tag_name": "launcher-latest",
            "assets": [
                {
                    "id": 900,
                    "name": "tv-launcher.apk.sha256",
                    "updated_at": "2026-11-26T09:00:00Z",
                    "browser_download_url": "https://github.com/tehrelt/tv-ware/releases/download/launcher-latest/tv-launcher.apk.sha256",
                },
                {
                    "id": 901,
                    "name": "tv-launcher.apk",
                    "updated_at": "2026-11-26T10:00:00Z",
                    "browser_download_url": "https://github.com/tehrelt/tv-ware/releases/download/launcher-latest/tv-launcher.apk",
                },
            ],
        }
        tag, asset_id, updated_at, url = select_release_asset(
            payload, "tv-launcher.apk"
        )
        self.assertEqual(
            (tag, asset_id, updated_at),
            ("launcher-latest", 901, "2026-11-26T10:00:00Z"),
        )
        self.assertTrue(url.startswith("https://"))

        payload["assets"][1]["browser_download_url"] = "http://insecure.example/a.apk"
        with self.assertRaises(ValueError):
            select_release_asset(payload, "tv-launcher.apk")

    def test_select_release_asset_rejects_missing_or_malformed_fields(self):
        valid_asset = {
            "id": 10,
            "name": "Happ.apk",
            "updated_at": "2026-11-26T10:00:00Z",
            "browser_download_url": "https://github.com/Happ-proxy/happ-android/releases/download/v1/Happ.apk",
        }
        for bad_payload in (
            {},
            {"tag_name": "", "assets": [valid_asset]},
            {"tag_name": "v1", "assets": []},
            {"tag_name": "v1", "assets": [{**valid_asset, "name": "other.apk"}]},
            {"tag_name": "v1", "assets": [{**valid_asset, "id": True}]},
            {"tag_name": "v1", "assets": [{**valid_asset, "updated_at": ""}]},
            {
                "tag_name": "v1",
                "assets": [{**valid_asset, "browser_download_url": "https://"}],
            },
        ):
            with self.subTest(bad_payload=bad_payload):
                with self.assertRaises(ValueError):
                    select_release_asset(bad_payload, "Happ.apk")

    def test_needs_download_detects_rolling_release_update(self):
        apk = self.root / "tv-launcher.apk"
        apk.write_bytes(b"apk")
        meta = {
            "tv-launcher": {
                "tag_name": "launcher-latest",
                "asset_id": 901,
                "updated_at": "2026-11-26T10:00:00Z",
            }
        }
        self.assertFalse(
            needs_download(
                meta, "tv-launcher", "launcher-latest", 901, "2026-11-26T10:00:00Z", apk
            )
        )
        self.assertTrue(
            needs_download(
                meta, "tv-launcher", "launcher-latest", 902, "2026-11-26T11:00:00Z", apk
            )
        )
        self.assertTrue(
            needs_download(
                meta, "tv-launcher", "launcher-latest", 901, "2026-11-26T11:00:00Z", apk
            )
        )
        self.assertTrue(
            needs_download(meta, "tv-launcher", "v2", 901, "2026-11-26T10:00:00Z", apk)
        )
        self.assertTrue(
            needs_download(
                {}, "tv-launcher", "launcher-latest", 901, "2026-11-26T10:00:00Z", apk
            )
        )
        self.assertTrue(
            needs_download(
                {"tv-launcher": "bad"},
                "tv-launcher",
                "launcher-latest",
                901,
                "2026-11-26T10:00:00Z",
                apk,
            )
        )

        apk.write_bytes(b"")
        self.assertTrue(
            needs_download(
                meta, "tv-launcher", "launcher-latest", 901, "2026-11-26T10:00:00Z", apk
            )
        )
        apk.unlink()
        self.assertTrue(
            needs_download(
                meta, "tv-launcher", "launcher-latest", 901, "2026-11-26T10:00:00Z", apk
            )
        )

    def test_scan_local_apks_reads_versions_metadata(self):
        self.apks_dir.mkdir()
        (self.apks_dir / "tv-launcher.apk").write_bytes(b"1")
        (self.apks_dir / ".versions.json").write_text(
            '{"tv-launcher": {"tag_name": "launcher-latest", "updated_at": "2026-11-26T10:00:00Z"}}',
            encoding="utf-8",
        )
        items = scan_local_apks(self.apks_dir)
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0].package_hint, "com.tvware.launcher")
        self.assertEqual(items[0].version_tag, "launcher-latest")
        self.assertEqual(items[0].updated_at, "2026-11-26T10:00:00Z")

    def test_scan_local_apks_sorts_known_first_and_handles_corrupt_metadata(self):
        self.assertEqual(scan_local_apks(self.apks_dir), [])
        self.apks_dir.mkdir()
        (self.apks_dir / "Zeta.apk").write_bytes(b"z")
        (self.apks_dir / "Happ.apk").write_bytes(b"h")
        (self.apks_dir / "Alpha.apk").write_bytes(b"a")
        (self.apks_dir / "tv-launcher.apk").write_bytes(b"l")
        (self.apks_dir / "notes.txt").write_text("ignore", encoding="utf-8")
        (self.apks_dir / "dir.apk").mkdir()
        (self.apks_dir / ".versions.json").write_text("{corrupt json", encoding="utf-8")

        with self.assertLogs("tv_setup.installer", level="WARNING"):
            items = scan_local_apks(self.apks_dir)
        self.assertEqual(
            [(item.filename, item.package_hint, item.version_tag) for item in items],
            [
                ("tv-launcher.apk", "com.tvware.launcher", None),
                ("Happ.apk", "su.happ.proxyutility", None),
                ("Alpha.apk", None, None),
                ("Zeta.apk", None, None),
            ],
        )

    def test_download_repo_apks_downloads_updates_metadata_and_skips_unchanged(self):
        launcher_release = {
            "tag_name": "launcher-latest",
            "assets": [
                {
                    "id": 901,
                    "name": "tv-launcher.apk",
                    "updated_at": "2026-11-26T10:00:00Z",
                    "browser_download_url": "https://github.com/tehrelt/tv-ware/releases/download/launcher-latest/tv-launcher.apk",
                }
            ],
        }
        happ_release = {
            "tag_name": "v2.5.0",
            "assets": [
                {
                    "id": 42,
                    "name": "Happ.apk",
                    "updated_at": "2026-11-25T08:00:00Z",
                    "browser_download_url": "https://github.com/Happ-proxy/happ-android/releases/download/v2.5.0/Happ.apk",
                }
            ],
        }
        progress_calls = []
        logs = []

        def fake_urlopen(req, timeout=None):
            self.assertEqual(timeout, 15.0)
            self.assertEqual(req.get_header("User-agent"), "tv-setup")
            url = req.full_url
            if url == REPO_SOURCES[0].release_url:
                return _json_response(launcher_release, url)
            if url == REPO_SOURCES[1].release_url:
                return _json_response(happ_release, url)
            if url.endswith("tv-launcher.apk"):
                return _bytes_response(b"LAUNCHER_APK", url)
            if url.endswith("Happ.apk"):
                return _bytes_response(b"HAPP_APK", url)
            raise AssertionError(f"Unexpected URL: {url}")

        with patch("urllib.request.urlopen", side_effect=fake_urlopen) as urlopen:
            items = download_repo_apks(
                self.apks_dir,
                progress_cb=lambda key, done, total: progress_calls.append(
                    (key, done, total)
                ),
                log_cb=logs.append,
            )

        self.assertEqual(urlopen.call_count, 4)
        self.assertEqual(
            (self.apks_dir / "tv-launcher.apk").read_bytes(), b"LAUNCHER_APK"
        )
        self.assertEqual((self.apks_dir / "Happ.apk").read_bytes(), b"HAPP_APK")
        self.assertFalse(list(self.apks_dir.glob("*.part")))
        self.assertIn(("tv-launcher", 12, 12), progress_calls)
        self.assertIn(("happ", 8, 8), progress_calls)
        self.assertTrue(logs)

        meta = json.loads(
            (self.apks_dir / ".versions.json").read_text(encoding="utf-8")
        )
        self.assertEqual(meta["tv-launcher"]["asset_id"], 901)
        self.assertEqual(meta["happ"]["tag_name"], "v2.5.0")
        self.assertIn("downloaded_at", meta["tv-launcher"])
        self.assertEqual(
            items,
            [
                LocalApk(
                    "tv-launcher.apk",
                    self.apks_dir / "tv-launcher.apk",
                    "com.tvware.launcher",
                    "launcher-latest",
                    "2026-11-26T10:00:00Z",
                ),
                LocalApk(
                    "Happ.apk",
                    self.apks_dir / "Happ.apk",
                    "su.happ.proxyutility",
                    "v2.5.0",
                    "2026-11-25T08:00:00Z",
                ),
            ],
        )

        # Second run without changes skips asset downloads.
        with patch("urllib.request.urlopen", side_effect=fake_urlopen) as urlopen_skip:
            download_repo_apks(self.apks_dir)
        self.assertEqual(urlopen_skip.call_count, 2)

        # force=True re-downloads both assets.
        with patch("urllib.request.urlopen", side_effect=fake_urlopen) as urlopen_force:
            download_repo_apks(self.apks_dir, force=True)
        self.assertEqual(urlopen_force.call_count, 4)

    def test_download_repo_apks_cleans_partial_file_and_preserves_existing_on_failure(
        self,
    ):
        self.apks_dir.mkdir()
        existing = self.apks_dir / "tv-launcher.apk"
        existing.write_bytes(b"OLD_APK")
        (self.apks_dir / ".versions.json").write_text("{bad json", encoding="utf-8")

        launcher_release = {
            "tag_name": "launcher-latest",
            "assets": [
                {
                    "id": 901,
                    "name": "tv-launcher.apk",
                    "updated_at": "2026-11-26T10:00:00Z",
                    "browser_download_url": "https://github.com/tehrelt/tv-ware/releases/download/launcher-latest/tv-launcher.apk",
                }
            ],
        }
        broken_stream = MagicMock()
        broken_stream.headers = {}
        broken_stream.geturl.return_value = (
            "https://objects.githubusercontent.com/tv-launcher.apk"
        )
        broken_stream.read.side_effect = [b"partial", OSError("connection reset")]
        broken_cm = MagicMock()
        broken_cm.__enter__.return_value = broken_stream

        def fake_urlopen(req, timeout=None):
            if req.full_url == REPO_SOURCES[0].release_url:
                return _json_response(launcher_release, req.full_url)
            return broken_cm

        with (
            patch("urllib.request.urlopen", side_effect=fake_urlopen),
            self.assertLogs("tv_setup.installer", level="WARNING"),
        ):
            with self.assertRaises(OSError):
                download_repo_apks(self.apks_dir)

        self.assertEqual(existing.read_bytes(), b"OLD_APK")
        self.assertFalse(list(self.apks_dir.glob("*.part")))

    def test_download_repo_apks_rejects_insecure_redirect_and_cleans_part(self):
        launcher_release = {
            "tag_name": "launcher-latest",
            "assets": [
                {
                    "id": 901,
                    "name": "tv-launcher.apk",
                    "updated_at": "2026-11-26T10:00:00Z",
                    "browser_download_url": "https://github.com/tehrelt/tv-ware/releases/download/launcher-latest/tv-launcher.apk",
                }
            ],
        }

        def fake_urlopen(req, timeout=None):
            if req.full_url == REPO_SOURCES[0].release_url:
                return _json_response(launcher_release, req.full_url)
            return _bytes_response(
                b"APK", url="http://downgraded.example/tv-launcher.apk"
            )

        with patch("urllib.request.urlopen", side_effect=fake_urlopen):
            with self.assertRaises(ValueError):
                download_repo_apks(self.apks_dir)

        self.assertFalse((self.apks_dir / "tv-launcher.apk").exists())
        self.assertFalse(list(self.apks_dir.glob("*.part")))

    def test_download_repo_apks_rejects_empty_download(self):
        launcher_release = {
            "tag_name": "launcher-latest",
            "assets": [
                {
                    "id": 901,
                    "name": "tv-launcher.apk",
                    "updated_at": "2026-11-26T10:00:00Z",
                    "browser_download_url": "https://github.com/tehrelt/tv-ware/releases/download/launcher-latest/tv-launcher.apk",
                }
            ],
        }

        def fake_urlopen(req, timeout=None):
            if req.full_url == REPO_SOURCES[0].release_url:
                return _json_response(launcher_release, req.full_url)
            return _bytes_response(b"")

        with patch("urllib.request.urlopen", side_effect=fake_urlopen):
            with self.assertRaises(ValueError):
                download_repo_apks(self.apks_dir)

        self.assertFalse((self.apks_dir / "tv-launcher.apk").exists())
        self.assertFalse(list(self.apks_dir.glob("*.part")))

    def test_download_repo_apks_rejects_truncated_download_and_preserves_state(self):
        self.apks_dir.mkdir()
        existing = self.apks_dir / "tv-launcher.apk"
        existing.write_bytes(b"OLD_APK")
        old_meta = {
            "tv-launcher": {
                "tag_name": "launcher-latest",
                "asset_id": 900,
                "updated_at": "2026-11-25T10:00:00Z",
            }
        }
        (self.apks_dir / ".versions.json").write_text(
            json.dumps(old_meta), encoding="utf-8"
        )

        launcher_release = {
            "tag_name": "launcher-latest",
            "assets": [
                {
                    "id": 901,
                    "name": "tv-launcher.apk",
                    "updated_at": "2026-11-26T10:00:00Z",
                    "browser_download_url": "https://github.com/tehrelt/tv-ware/releases/download/launcher-latest/tv-launcher.apk",
                }
            ],
        }
        truncated = _bytes_response(b"partial")
        truncated.headers = {"Content-Length": "100"}

        def fake_urlopen(req, timeout=None):
            if req.full_url == REPO_SOURCES[0].release_url:
                return _json_response(launcher_release, req.full_url)
            if req.full_url == launcher_release["assets"][0]["browser_download_url"]:
                return truncated
            raise AssertionError(f"Unexpected URL: {req.full_url}")

        with patch("urllib.request.urlopen", side_effect=fake_urlopen):
            with self.assertRaisesRegex(ValueError, "Incomplete download"):
                download_repo_apks(self.apks_dir)

        self.assertEqual(existing.read_bytes(), b"OLD_APK")
        self.assertEqual(
            json.loads((self.apks_dir / ".versions.json").read_text(encoding="utf-8")),
            old_meta,
        )
        self.assertFalse(list(self.apks_dir.glob("*.part")))

    def test_import_local_apk_validates_and_copies_atomically(self):
        src = self.root / "SmartTube.apk"
        src.write_bytes(b"SMARTTUBE_BYTES")
        dest = import_local_apk(src, self.apks_dir)
        self.assertEqual(dest, self.apks_dir / "SmartTube.apk")
        self.assertEqual(dest.read_bytes(), b"SMARTTUBE_BYTES")
        self.assertFalse(list(self.apks_dir.glob("*.part")))

        # Importing a file already in apks_dir succeeds cleanly.
        self.assertEqual(import_local_apk(dest, self.apks_dir), dest)

        # Replacing a known repo APK clears its stale .versions.json entry.
        (self.apks_dir / ".versions.json").write_text(
            '{"tv-launcher": {"tag_name": "launcher-latest", "asset_id": 901, "updated_at": "2026-11-26T10:00:00Z"}}',
            encoding="utf-8",
        )
        local_launcher = self.root / "tv-launcher.apk"
        local_launcher.write_bytes(b"LOCAL_BUILD")
        import_local_apk(local_launcher, self.apks_dir)
        self.assertEqual(
            json.loads((self.apks_dir / ".versions.json").read_text(encoding="utf-8")),
            {},
        )

    def test_import_local_apk_cleans_part_on_copy_failure(self):
        src = self.root / "SmartTube.apk"
        src.write_bytes(b"SMARTTUBE_BYTES")
        with patch("shutil.copyfileobj", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                import_local_apk(src, self.apks_dir)
        self.assertFalse((self.apks_dir / "SmartTube.apk").exists())
        self.assertFalse(list(self.apks_dir.glob("*.part")))

    def test_import_local_apk_rejects_invalid_inputs(self):
        txt = self.root / "readme.txt"
        txt.write_bytes(b"hello")
        empty = self.root / "empty.apk"
        empty.write_bytes(b"")

        with self.assertRaises(ValueError):
            import_local_apk(txt, self.apks_dir)
        with self.assertRaises(ValueError):
            import_local_apk(empty, self.apks_dir)
        with self.assertRaises(FileNotFoundError):
            import_local_apk(self.root / "missing.apk", self.apks_dir)

        for bad_name in (
            "CON.apk",
            "bad:name.apk",
            "../evil.apk",
            "..\\evil.apk",
            "foo.apk.",
        ):
            fake_path = MagicMock(spec=Path)
            fake_path.name = bad_name
            with self.subTest(bad_name=bad_name):
                with self.assertRaises(ValueError):
                    import_local_apk(fake_path, self.apks_dir)

    def test_install_apk_raises_signature_conflict_error(self):
        adb = MagicMock()
        adb.install.return_value = AdbResult(
            1,
            "",
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]",
        )
        with self.assertRaises(SignatureConflictError):
            install_apk(adb, "192.168.31.42:5555", Path("tv-launcher.apk"))
        adb.install.assert_called_once_with(
            "192.168.31.42:5555", Path("tv-launcher.apk"), downgrade=True
        )

    def test_install_apk_handles_success_and_other_failures(self):
        adb = MagicMock()
        adb.install.return_value = AdbResult(
            0, "Performing Streamed Install\nSuccess\n", ""
        )
        install_apk(adb, "SER1", Path("app.apk"))

        for result in (
            AdbResult(0, "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]", ""),
            AdbResult(0, "Failure", ""),
            AdbResult(0, "", "INSTALL_FAILED_VERSION_DOWNGRADE"),
            AdbResult(1, "", "adb: failed to install app.apk"),
        ):
            with self.subTest(result=result):
                adb.install.return_value = result
                with self.assertRaises(AdbError):
                    install_apk(adb, "SER1", Path("app.apk"))

    def test_reinstall_after_uninstall_checks_uninstall_before_installing(self):
        adb = MagicMock()
        adb.uninstall.return_value = AdbResult(0, "Success\n", "")
        adb.install.return_value = AdbResult(0, "Success\n", "")
        reinstall_after_uninstall(
            adb, "SER1", "com.tvware.launcher", Path("tv-launcher.apk")
        )
        adb.uninstall.assert_called_once_with("SER1", "com.tvware.launcher")
        adb.install.assert_called_once_with(
            "SER1", Path("tv-launcher.apk"), downgrade=True
        )

        for bad_uninstall in (
            AdbResult(1, "", "Failure [DELETE_FAILED_INTERNAL_ERROR]"),
            AdbResult(0, "Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]", ""),
            AdbResult(1, "", ""),
        ):
            with self.subTest(bad_uninstall=bad_uninstall):
                adb.reset_mock()
                adb.uninstall.return_value = bad_uninstall
                with self.assertRaises(AdbError):
                    reinstall_after_uninstall(
                        adb, "SER1", "com.tvware.launcher", Path("tv-launcher.apk")
                    )
                adb.install.assert_not_called()

    def test_download_launcher_apk_fetches_only_launcher(self):
        launcher_release = {
            "tag_name": "launcher-latest",
            "assets": [
                {
                    "id": 905,
                    "name": "tv-launcher.apk",
                    "updated_at": "2026-11-26T12:00:00Z",
                    "browser_download_url": "https://github.com/tehrelt/tv-ware/releases/download/launcher-latest/tv-launcher.apk",
                }
            ],
        }

        def fake_urlopen(req, timeout=None):
            if req.full_url == REPO_SOURCES[0].release_url:
                return _json_response(launcher_release, req.full_url)
            if req.full_url == launcher_release["assets"][0]["browser_download_url"]:
                return _bytes_response(b"LAUNCHER_NEW")
            raise AssertionError(f"Unexpected URL: {req.full_url}")

        with patch("urllib.request.urlopen", side_effect=fake_urlopen):
            apk_path = download_launcher_apk(self.apks_dir)

        self.assertEqual(apk_path, self.apks_dir / "tv-launcher.apk")
        self.assertEqual(apk_path.read_bytes(), b"LAUNCHER_NEW")
        self.assertFalse((self.apks_dir / "Happ.apk").exists())

    def test_download_launcher_apk_selects_versioned_release_from_list(self):
        releases = [
            {
                "tag_name": "launcher-latest",
                "draft": False,
                "prerelease": False,
                "assets": [
                    {
                        "id": 900,
                        "name": "tv-launcher.apk",
                        "updated_at": "2026-11-26T12:00:00Z",
                        "browser_download_url": "https://github.com/tehrelt/tv-ware/releases/download/launcher-latest/tv-launcher.apk",
                    }
                ],
            },
            {
                "tag_name": "launcher-v1.0.13",
                "draft": True,
                "prerelease": False,
                "assets": [
                    {
                        "id": 906,
                        "name": "tv-launcher.apk",
                        "updated_at": "2026-11-26T12:05:00Z",
                        "browser_download_url": "https://github.com/tehrelt/tv-ware/releases/download/launcher-v1.0.13/tv-launcher.apk",
                    }
                ],
            },
            {
                "tag_name": "launcher-v1.0.12",
                "draft": False,
                "prerelease": False,
                "assets": [
                    {
                        "id": 905,
                        "name": "tv-launcher.apk",
                        "updated_at": "2026-11-26T12:00:00Z",
                        "browser_download_url": "https://github.com/tehrelt/tv-ware/releases/download/launcher-v1.0.12/tv-launcher.apk",
                    }
                ],
            },
        ]

        def fake_urlopen(req, timeout=None):
            if req.full_url == REPO_SOURCES[0].release_url:
                return _json_response(releases, req.full_url)
            if req.full_url == releases[2]["assets"][0]["browser_download_url"]:
                return _bytes_response(b"LAUNCHER_V12")
            raise AssertionError(f"Unexpected URL: {req.full_url}")

        with patch("urllib.request.urlopen", side_effect=fake_urlopen):
            download_launcher_apk(self.apks_dir)

        items = scan_local_apks(self.apks_dir)
        self.assertEqual(items[0].version_tag, "launcher-v1.0.12")


if __name__ == "__main__":
    unittest.main()
