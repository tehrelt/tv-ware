import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import tv_setup.__main__ as entry_main
from tv_setup.adb_client import AdbClient
from tv_setup.discovery import DeviceStatus, DiscoveredDevice
from tv_setup.gui import (
    PALETTE,
    TvSetupApp,
    _ManualIpDialog,
    _PairingDialog,
    format_device_label,
    main,
    parse_manual_target,
)
from tv_setup.installer import LocalApk, SignatureConflictError
from tv_setup.optimizer import ApplyPlan, ApplyReport, DeviceInspection
from tv_setup.presets import ANIMATION_KEYS, BLOAT_CATALOG, LAUNCHER_PACKAGE


class GuiSmokeTests(unittest.TestCase):
    def test_palette_contains_graphite_theme_colors(self):
        expected = {"#0A0A0B", "#1C1C1E", "#2C2C2E", "#F5F5F7", "#A1A1A6", "#0A84FF", "#4CC38A"}
        self.assertTrue(expected <= set(PALETTE.values()))

    def test_format_device_label_covers_all_statuses(self):
        cases = [
            (
                DiscoveredDevice("192.168.31.42", "MiTV-AFKR0", "192.168.31.42", 5555, None, DeviceStatus.CONNECTED, "tcp", "192.168.31.42:5555"),
                ("192.168.31.42:5555", "MiTV-AFKR0"),
            ),
            (
                DiscoveredDevice("usb123", "MiBox", "", None, None, DeviceStatus.CONNECTED, "usb", "usb123"),
                ("usb123", "MiBox"),
            ),
            (
                DiscoveredDevice("192.168.31.50", "LivingRoom", "192.168.31.50", 41234, 37123, DeviceStatus.READY, "mdns", None),
                ("192.168.31.50:41234", "LivingRoom"),
            ),
            (
                DiscoveredDevice("192.168.31.77", "192.168.31.77", "192.168.31.77", 5555, None, DeviceStatus.READY, "scan", None),
                ("192.168.31.77:5555",),
            ),
            (
                DiscoveredDevice("192.168.31.88", "MiBox", "192.168.31.88", None, 39999, DeviceStatus.NEEDS_PAIRING, "mdns", None),
                ("192.168.31.88:39999", "MiBox"),
            ),
            (
                DiscoveredDevice("192.168.31.12", "192.168.31.12:5555", "192.168.31.12", 5555, None, DeviceStatus.UNAUTHORIZED, "tcp", "192.168.31.12:5555"),
                ("192.168.31.12:5555",),
            ),
            (
                DiscoveredDevice("192.168.31.60", "BedroomTV", "192.168.31.60", None, None, DeviceStatus.DEBUG_OFF, "mdns", None),
                ("192.168.31.60", "BedroomTV"),
            ),
        ]
        labels = []
        for device, expected_parts in cases:
            with self.subTest(status=device.status, id=device.id):
                label = format_device_label(device)
                self.assertIsInstance(label, str)
                self.assertTrue(label.strip())
                for part in expected_parts:
                    self.assertIn(part, label)
                labels.append((device.status, label))

        status_suffixes = {status: label.rsplit("·", 1)[-1].strip() for status, label in labels}
        self.assertEqual(len(set(status_suffixes.values())), len(DeviceStatus))

    def test_parse_manual_target_defaults_port_and_validates(self):
        self.assertEqual(parse_manual_target("192.168.31.42"), ("192.168.31.42", 5555, False))
        self.assertEqual(parse_manual_target("192.168.31.42:41234"), ("192.168.31.42", 41234, True))
        for bad in ("", "   ", "192.168.31.42:0", "192.168.31.42:70000", "192.168.31.42:abc", "bad host:5555"):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    parse_manual_target(bad)

    def test_app_smoke_creation_and_main_entry(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        apks_dir = root / "apks"
        apks_dir.mkdir()
        (apks_dir / "tv-launcher.apk").write_bytes(b"apk")
        (apks_dir / "SmartTube.apk").write_bytes(b"apk")

        with patch("tv_setup.gui.find_adb_executable", return_value=None):
            app = TvSetupApp(
                tools_dir=root / "tools",
                apks_dir=apks_dir,
                state_dir=root / "state",
                auto_discover=False,
            )
            app.withdraw()
            self.addCleanup(app.destroy)
            app.update_idletasks()

            self.assertEqual(set(app._apk_vars), {"tv-launcher.apk", "SmartTube.apk"})
            self.assertTrue(app._apk_vars["tv-launcher.apk"].get())
            self.assertFalse(app._apk_vars["SmartTube.apk"].get())
            self.assertTrue(app._download_adb_btn.winfo_manager())

        with patch("tv_setup.gui.TvSetupApp") as app_cls:
            main()
            app_cls.assert_called_once_with()
            app_cls.return_value.mainloop.assert_called_once_with()

    def test_apply_worker_re_inspects_after_install_and_handles_signature_conflict(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        apks_dir = root / "apks"
        apks_dir.mkdir()
        launcher_apk = apks_dir / "tv-launcher.apk"
        custom_apk = apks_dir / "Custom.apk"
        launcher_apk.write_bytes(b"launcher")
        custom_apk.write_bytes(b"custom")

        initial_info = DeviceInspection(
            "SER1", "MiTV", "14", 34, "com.google.android.apps.tv.launcherx/.MainActivity",
            dict.fromkeys(ANIMATION_KEYS, "1.0"), False,
            {"com.google.android.apps.tv.launcherx", BLOAT_CATALOG[0].package}, set(),
        )
        post_install_info = DeviceInspection(
            "SER1", "MiTV", "14", 34, "com.google.android.apps.tv.launcherx/.MainActivity",
            dict.fromkeys(ANIMATION_KEYS, "1.0"), False,
            {"com.google.android.apps.tv.launcherx", LAUNCHER_PACKAGE, BLOAT_CATALOG[0].package}, set(),
        )
        post_apply_info = DeviceInspection(
            "SER1", "MiTV", "14", 34, f"{LAUNCHER_PACKAGE}/.MainActivity",
            dict.fromkeys(ANIMATION_KEYS, "0.25"), False,
            {"com.google.android.apps.tv.launcherx", LAUNCHER_PACKAGE, BLOAT_CATALOG[0].package},
            {BLOAT_CATALOG[0].package},
        )

        with patch("tv_setup.gui.find_adb_executable", return_value=Path("adb.exe")):
            app = TvSetupApp(
                tools_dir=root / "tools",
                apks_dir=apks_dir,
                state_dir=root / "state",
                auto_discover=False,
            )
            app.withdraw()
            self.addCleanup(app.destroy)
            app._connected_serial = "192.168.31.42:5555"
            app._inspection = initial_info
            app._refresh_inspection_ui(initial_info)

            selected = [
                LocalApk("tv-launcher.apk", launcher_apk, LAUNCHER_PACKAGE, "launcher-latest", None),
                LocalApk("Custom.apk", custom_apk, None, None, None),
            ]
            plan = ApplyPlan(True, False, True, False, [BLOAT_CATALOG[0].package])

            with (
                patch("tv_setup.gui.install_apk", side_effect=[SignatureConflictError("mismatch"), SignatureConflictError("mismatch")]) as install_mock,
                patch("tv_setup.gui.reinstall_after_uninstall") as reinstall_mock,
                patch("tv_setup.gui.inspect_device", side_effect=[post_install_info, post_apply_info]) as inspect_mock,
                patch("tv_setup.gui.apply_optimizations", return_value=ApplyReport(["ok"], [])) as apply_mock,
                patch.object(app, "_ask_yes_no_sync", return_value=True) as ask_mock,
                patch.object(app, "_ui_call", side_effect=lambda fn: fn()),
            ):
                app._run_apply_worker("192.168.31.42:5555", initial_info, selected, plan)

            self.assertEqual(install_mock.call_count, 2)
            ask_mock.assert_called_once()
            reinstall_mock.assert_called_once_with(app.adb, "192.168.31.42:5555", LAUNCHER_PACKAGE, launcher_apk)
            self.assertEqual(inspect_mock.call_count, 2)
            self.assertEqual(apply_mock.call_args.args[2], post_install_info)
            self.assertEqual(app._inspection, post_apply_info)
            self.assertIn("Отключён", app._bloat_widgets[BLOAT_CATALOG[0].package].cget("text"))

            # Declining signature conflict prompt skips reinstall_after_uninstall.
            with (
                patch("tv_setup.gui.install_apk", side_effect=SignatureConflictError("mismatch")),
                patch("tv_setup.gui.reinstall_after_uninstall") as declined_reinstall,
                patch("tv_setup.gui.inspect_device", side_effect=[post_install_info, post_apply_info]),
                patch("tv_setup.gui.apply_optimizations", return_value=ApplyReport([], [])),
                patch.object(app, "_ask_yes_no_sync", return_value=False),
                patch.object(app, "_ui_call", side_effect=lambda fn: fn()),
            ):
                app._run_apply_worker("192.168.31.42:5555", initial_info, [selected[0]], plan)
            declined_reinstall.assert_not_called()

    def test_rollback_refreshes_inspection_and_main_module_delegates(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        disabled_info = DeviceInspection(
            "SER1", "MiTV", "14", 34, f"{LAUNCHER_PACKAGE}/.MainActivity",
            dict.fromkeys(ANIMATION_KEYS, "0.25"), True,
            {LAUNCHER_PACKAGE, BLOAT_CATALOG[0].package}, {BLOAT_CATALOG[0].package},
        )
        restored_info = DeviceInspection(
            "SER1", "MiTV", "14", 34, "com.google.android.apps.tv.launcherx/.MainActivity",
            dict.fromkeys(ANIMATION_KEYS, "1.0"), False,
            {LAUNCHER_PACKAGE, BLOAT_CATALOG[0].package}, set(),
        )

        with patch("tv_setup.gui.find_adb_executable", return_value=Path("adb.exe")):
            app = TvSetupApp(
                tools_dir=root / "tools",
                apks_dir=root / "apks",
                state_dir=root / "state",
                auto_discover=False,
            )
            app.withdraw()
            self.addCleanup(app.destroy)
            app._connected_serial = "192.168.31.42:5555"
            app._refresh_inspection_ui(disabled_info)
            self.assertIn("Отключён", app._bloat_widgets[BLOAT_CATALOG[0].package].cget("text"))

            def run_sync(target):
                target()
                return True

            with (
                patch.object(app, "_start_worker", side_effect=run_sync),
                patch.object(app, "_ui_call", side_effect=lambda fn: fn()),
                patch("tv_setup.gui.rollback_device", return_value=ApplyReport(["pm enable"], [])) as rb_mock,
                patch("tv_setup.gui.inspect_device", return_value=restored_info),
            ):
                app.start_rollback()

            rb_mock.assert_called_once()
            self.assertEqual(app._inspection, restored_info)
            self.assertIn("Активен", app._bloat_widgets[BLOAT_CATALOG[0].package].cget("text"))

            # Verify worker-thread messagebox marshaling uses main thread via after(0, ...).
            caller_threads = []
            answer = []

            def fake_askyesno(title, message, parent=None):
                caller_threads.append(threading.current_thread())
                return True

            with patch("tv_setup.gui.messagebox.askyesno", side_effect=fake_askyesno):
                worker = threading.Thread(
                    target=lambda: answer.append(app._ask_yes_no_sync("Title", "Msg")),
                    daemon=True,
                )
                worker.start()
                deadline = time.monotonic() + 5.0
                while worker.is_alive() and time.monotonic() < deadline:
                    app.update()
                    worker.join(timeout=0.01)
                self.assertFalse(worker.is_alive())

            self.assertEqual(answer, [True])
            self.assertEqual(caller_threads, [threading.main_thread()])

        with patch("tv_setup.gui.main") as gui_main:
            entry_main.main()
            gui_main.assert_called_once_with()

    def test_handle_manual_connect_resolves_connect_port_and_direct_connect(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)

        with patch("tv_setup.gui.find_adb_executable", return_value=Path("adb.exe")):
            app = TvSetupApp(
                tools_dir=root / "tools",
                apks_dir=root / "apks",
                state_dir=root / "state",
                auto_discover=False,
            )
            app.withdraw()
            self.addCleanup(app.destroy)

            with (
                patch.object(app, "_start_pair_and_connect") as pair_mock,
                patch.object(app, "_start_connect") as connect_mock,
            ):
                with self.subTest("ip_without_explicit_port_and_pair_port_uses_mdns"):
                    app._handle_manual_connect("192.168.31.42", 5555, False, 37123, "123456")
                    pair_mock.assert_called_once_with("192.168.31.42", 37123, "123456", None)
                    connect_mock.assert_not_called()

                pair_mock.reset_mock()
                connect_mock.reset_mock()
                with self.subTest("ip_with_explicit_port_and_pair_port_uses_explicit_port"):
                    app._handle_manual_connect("192.168.31.42", 41234, True, 37123, "123456")
                    pair_mock.assert_called_once_with("192.168.31.42", 37123, "123456", 41234)
                    connect_mock.assert_not_called()

                pair_mock.reset_mock()
                connect_mock.reset_mock()
                with self.subTest("ip_with_explicit_pairing_port_only_uses_mdns"):
                    app._handle_manual_connect("192.168.31.42", 37123, True, None, "123456")
                    pair_mock.assert_called_once_with("192.168.31.42", 37123, "123456", None)
                    connect_mock.assert_not_called()

                pair_mock.reset_mock()
                connect_mock.reset_mock()
                with self.subTest("ip_without_code_connects_directly"):
                    app._handle_manual_connect("192.168.31.42", 5555, False, None, "")
                    connect_mock.assert_called_once_with("192.168.31.42:5555")
                    pair_mock.assert_not_called()

    def test_modal_dialogs_fit_requested_size_and_refit_on_error(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)

        with patch("tv_setup.gui.find_adb_executable", return_value=Path("adb.exe")):
            app = TvSetupApp(
                tools_dir=root / "tools",
                apks_dir=root / "apks",
                state_dir=root / "state",
                auto_discover=False,
            )
            app.withdraw()
            self.addCleanup(app.destroy)

            device = DiscoveredDevice(
                "192.168.31.88", "MiBox", "192.168.31.88", None, 39999, DeviceStatus.NEEDS_PAIRING, "mdns", None,
            )
            pairing = _PairingDialog(app, device, lambda _code, _port: None)
            pairing.withdraw()
            self.addCleanup(lambda: pairing.winfo_exists() and pairing.destroy())
            pairing.update_idletasks()
            self.assertGreaterEqual(pairing.winfo_width(), pairing.winfo_reqwidth())
            self.assertGreaterEqual(pairing.winfo_height(), pairing.winfo_reqheight())
            pairing._show_error("Строка 1\nСтрока 2\nСтрока 3\nСтрока 4\nСтрока 5")
            pairing.update_idletasks()
            self.assertGreaterEqual(pairing.winfo_width(), pairing.winfo_reqwidth())
            self.assertGreaterEqual(pairing.winfo_height(), pairing.winfo_reqheight())
            pairing.destroy()

            manual = _ManualIpDialog(app, lambda *_args: None)
            manual.withdraw()
            self.addCleanup(lambda: manual.winfo_exists() and manual.destroy())
            manual.update_idletasks()
            self.assertGreaterEqual(manual.winfo_width(), manual.winfo_reqwidth())
            self.assertGreaterEqual(manual.winfo_height(), manual.winfo_reqheight())
            manual._show_error("Строка 1\nСтрока 2\nСтрока 3\nСтрока 4\nСтрока 5")
            manual.update_idletasks()
            self.assertGreaterEqual(manual.winfo_width(), manual.winfo_reqwidth())
            self.assertGreaterEqual(manual.winfo_height(), manual.winfo_reqheight())
            manual.destroy()

    def test_start_download_apks_refreshes_list_on_partial_failure(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        apks_dir = root / "apks"
        apks_dir.mkdir()

        with patch("tv_setup.gui.find_adb_executable", return_value=Path("adb.exe")):
            app = TvSetupApp(
                tools_dir=root / "tools",
                apks_dir=apks_dir,
                state_dir=root / "state",
                auto_discover=False,
            )
            app.withdraw()
            self.addCleanup(app.destroy)

            def partial_download(target_dir, progress_cb=None, log_cb=None):
                (target_dir / "tv-launcher.apk").write_bytes(b"apk")
                raise RuntimeError("Happ download failed")

            def run_sync(target):
                with self.assertRaisesRegex(RuntimeError, "Happ download failed"):
                    target()
                return True

            with (
                patch.object(app, "_start_worker", side_effect=run_sync),
                patch.object(app, "_ui_call", side_effect=lambda fn: fn()),
                patch("tv_setup.gui.download_repo_apks", side_effect=partial_download),
            ):
                app.start_download_apks()

            self.assertIn("tv-launcher.apk", app._apk_vars)

    def test_refresh_adb_status_clears_stale_adb_path(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        stale_adb = root / "deleted" / "adb.exe"

        with patch("tv_setup.gui.find_adb_executable", return_value=None):
            app = TvSetupApp(
                tools_dir=root / "tools",
                apks_dir=root / "apks",
                state_dir=root / "state",
                adb_client=AdbClient(adb_path=stale_adb, tools_dir=root / "tools"),
                auto_discover=False,
            )
            app.withdraw()
            self.addCleanup(app.destroy)

            self.assertIsNone(app.adb.adb_path)
            self.assertTrue(app._download_adb_btn.winfo_manager())

            app.adb.adb_path = stale_adb
            app._refresh_adb_status()
            self.assertIsNone(app.adb.adb_path)
            self.assertTrue(app._download_adb_btn.winfo_manager())

    def test_update_launcher_downloads_installs_and_reopens_home(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        apks_dir = root / "apks"
        apks_dir.mkdir()
        launcher_apk = apks_dir / "tv-launcher.apk"
        info = DeviceInspection(
            "SER1", "MiTV", "14", 34, f"{LAUNCHER_PACKAGE}/.MainActivity",
            {"window_animation_scale": "0.25"}, True, {LAUNCHER_PACKAGE}, set(),
        )

        with patch("tv_setup.gui.find_adb_executable", return_value=Path("adb.exe")):
            app = TvSetupApp(
                tools_dir=root / "tools",
                apks_dir=apks_dir,
                state_dir=root / "state",
                auto_discover=False,
            )
            app.withdraw()
            self.addCleanup(app.destroy)
            app._connected_serial = "192.168.31.42:5555"
            app._inspection = info

            def fake_download(target_dir, progress_cb=None, log_cb=None):
                launcher_apk.write_bytes(b"apk")
                return launcher_apk

            with (
                patch.object(app.adb, "ensure_adb", return_value=Path("adb.exe")),
                patch.object(app.adb, "shell") as shell_mock,
                patch("tv_setup.gui.download_launcher_apk", side_effect=fake_download) as dl_mock,
                patch("tv_setup.gui.install_apk") as install_mock,
                patch("tv_setup.gui.inspect_device", return_value=info) as inspect_mock,
                patch.object(app, "_ui_call", side_effect=lambda fn: fn()),
            ):
                app._run_update_launcher_worker("192.168.31.42:5555")

            dl_mock.assert_called_once()
            install_mock.assert_called_once_with(app.adb, "192.168.31.42:5555", launcher_apk)
            inspect_mock.assert_called_once_with(app.adb, "192.168.31.42:5555")
            shell_mock.assert_called_once_with("192.168.31.42:5555", "input keyevent KEYCODE_HOME")


if __name__ == "__main__":
    unittest.main()
