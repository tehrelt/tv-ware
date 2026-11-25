import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from tv_setup.adb_client import AdbError, AdbResult
from tv_setup.optimizer import ApplyPlan, DeviceInspection, apply_optimizations, inspect_device, rollback_device
from tv_setup.presets import ANIMATION_KEYS, BLOAT_CATALOG, HAPP_PACKAGES, LAUNCHER_ACTIVITY, LAUNCHER_PACKAGE, STOCK_LAUNCHER_PACKAGES

STOCK = "com.google.android.apps.tv.launcherx"
HOME = f"{STOCK}/.MainActivity"
GAMES = "com.google.android.play.games"
ADS = "com.xiaomi.mitv.advertise"
SERIAL = "192.168.31.42:5555"
SET_HOME = "cmd package set-home-activity --user current "
DISABLE = "pm disable-user --user 0 "
UNINSTALL = "pm uninstall -k --user 0 "
WHITELIST = "dumpsys deviceidle whitelist"
BOOT = "cat /proc/sys/kernel/random/boot_id"
BOOT_A = "00000000-0000-4000-8000-000000000001"
BOOT_B = "00000000-0000-4000-8000-000000000002"
RESOLVE = "cmd package resolve-activity"


def output(text):
    return AdbResult(0, text, "")


class FakeAdb:
    def __init__(self, responses=None):
        self.commands = []
        self.responses = responses or {}

    def shell(self, serial, command, timeout=15.0):
        self.commands.append(command)
        if command == BOOT and command not in self.responses:
            return output(BOOT_A)
        if command == "pm list packages -d" and command not in self.responses:
            return output("")
        for prefix, result in self.responses.items():
            if command.startswith(prefix):
                if isinstance(result, AdbError):
                    raise result
                return result
        if command.startswith("pm list packages"):
            return output("")
        return output("Success")


class OptimizerTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.state = Path(temp.name) / "state"
        self.info = DeviceInspection(
            "SER123", "MiTV-AFKR0", "14", 34, HOME,
            dict.fromkeys(ANIMATION_KEYS, "1.0"), False,
            {LAUNCHER_PACKAGE, STOCK, GAMES, ADS, *HAPP_PACKAGES}, set(),
        )

    def apply(self, adb, plan, info=None, log_cb=None):
        return apply_optimizations(adb, SERIAL, info or self.info, plan, self.state, log_cb)

    @property
    def snapshot(self):
        return next(self.state.glob("SER123*.json"), self.state / "missing.json")

    def read_snapshot(self):
        return json.loads(self.snapshot.read_text(encoding="utf-8"))

    def test_catalog_defaults_and_stock_separation(self):
        self.assertEqual(LAUNCHER_PACKAGE, "com.tvware.launcher")
        self.assertEqual(LAUNCHER_ACTIVITY, "com.tvware.launcher/.MainActivity")
        self.assertEqual(HAPP_PACKAGES, ("su.happ.proxyutility", "com.happproxy"))
        self.assertEqual(set(STOCK_LAUNCHER_PACKAGES), {
            STOCK, "com.google.android.tvlauncher", "com.mitv.tvhome", "org.lineageos.tv.launcher",
        })
        self.assertEqual(len(BLOAT_CATALOG), 21)
        self.assertEqual(len({entry.package for entry in BLOAT_CATALOG}), 21)
        self.assertEqual({entry.package for entry in BLOAT_CATALOG if not entry.default_checked}, {
            "com.google.android.katniss", "com.google.android.youtube.tvmusic", "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus",
        })
        self.assertFalse(set(STOCK_LAUNCHER_PACKAGES) & {entry.package for entry in BLOAT_CATALOG})

    def test_inspect_device_parses_home_scales_and_packages(self):
        adb = FakeAdb({
            "getprop ro.product.model": output("MiTV-AFKR0\n"),
            "getprop ro.build.version.release": output("14\n"),
            "getprop ro.build.version.sdk": output("34\n"),
            "getprop ro.serialno": output("SER123\n"),
            RESOLVE: output(f"priority=0\n{HOME}\n"),
            **{f"settings get global {key}": output("1.0\n") for key in ANIMATION_KEYS},
            WHITELIST: output("system,su.happ.proxyutility,10123\n"),
            "pm list packages -d": output(f"package:{ADS}\n"),
            "pm list packages": output(f"package:{GAMES}\npackage:{ADS}\nnoise\n"),
        })
        self.assertEqual(inspect_device(adb, SERIAL), replace(
            self.info, happ_whitelisted=True, installed_packages={GAMES, ADS}, disabled_packages={ADS},
        ))

    def test_missing_home_scales_and_serial_fallback(self):
        for prop, home in (("", "No activity found"), ("unknown\n", "")):
            with self.subTest(prop=prop):
                adb = FakeAdb({
                    "getprop ro.serialno": output(prop), RESOLVE: output(home),
                    "settings get global window_animation_scale": output("null\n"),
                    "settings get global": output(""), WHITELIST: output("user,com.happproxy.fake,123"),
                })
                info = inspect_device(adb, SERIAL)
                self.assertEqual(info.device_id, SERIAL)
                self.assertIsNone(info.home_component)
                self.assertEqual(info.animation_scales, dict.fromkeys(ANIMATION_KEYS, "null"))
                self.assertFalse(info.happ_whitelisted)

    def test_snapshot_paths_cannot_traverse_state_dir(self):
        adb = FakeAdb({"getprop ro.serialno": output("../other\\SER123")})
        info = inspect_device(adb, SERIAL)
        self.assertEqual(info.device_id, "../other\\SER123")
        info = replace(self.info, device_id=info.device_id)
        self.assertFalse(self.apply(adb, ApplyPlan(True, False, False, False, []), info).errors)
        self.assertEqual(len(list(self.state.glob("*.json"))), 1)
        self.assertFalse(rollback_device(adb, SERIAL, self.state).errors)

    def test_refuses_to_disable_stock_launcher_without_tv_launcher_installed(self):
        adb = FakeAdb()
        info = replace(self.info, installed_packages={STOCK, GAMES})
        report = self.apply(adb, ApplyPlan(True, False, True, True, [GAMES, STOCK]), info)
        self.assertTrue(any(LAUNCHER_PACKAGE in e for e in report.errors))
        self.assertFalse(any(STOCK in cmd for cmd in adb.commands))
        self.assertIn(DISABLE + GAMES, adb.commands)

    def test_switches_home_before_disabling_stock_then_presses_home(self):
        class SnapshotCheckingAdb(FakeAdb):
            def shell(inner, serial, command, timeout=15.0):
                self.assertEqual(self.read_snapshot()["home_component"], HOME)
                return super().shell(serial, command, timeout)

        adb = SnapshotCheckingAdb()
        report = self.apply(adb, ApplyPlan(False, False, True, True, [LAUNCHER_PACKAGE, "com.android.settings"]))
        self.assertEqual(adb.commands, [SET_HOME + LAUNCHER_ACTIVITY, DISABLE + STOCK, "input keyevent KEYCODE_HOME"])
        self.assertEqual(self.read_snapshot()["disabled_packages"], {STOCK: "disable"})
        self.assertTrue(report.actions)

    def test_does_not_disable_home_after_failed_switch(self):
        adb = FakeAdb({SET_HOME: output("Error: cannot set HOME")})
        report = self.apply(adb, ApplyPlan(False, False, True, True, []))
        self.assertTrue(report.errors)
        self.assertEqual(adb.commands, [SET_HOME + LAUNCHER_ACTIVITY])

    def test_whitelists_only_installed_happ_and_logs_skip(self):
        for packages in (set(HAPP_PACKAGES), {HAPP_PACKAGES[1]}, set()):
            with self.subTest(packages=packages):
                adb, logs = FakeAdb(), []
                info = replace(self.info, installed_packages=packages)
                report = self.apply(adb, ApplyPlan(False, True, False, False, []), info, logs.append)
                self.assertFalse(report.errors)
                self.assertEqual(adb.commands, [f"{WHITELIST} +{pkg}" for pkg in HAPP_PACKAGES if pkg in packages])
                self.assertTrue(logs)

    def test_preserves_original_snapshot_and_rolls_back_disable_and_uninstall(self):
        adb = FakeAdb({
            "getprop ro.serialno": output("SER123\n"),
            DISABLE + ADS: AdbResult(1, "", "SecurityException"),
        })
        self.assertFalse(self.apply(adb, ApplyPlan(True, True, True, True, [GAMES])).errors)
        self.assertIn("settings put global window_animation_scale 0.25", adb.commands)
        info = replace(self.info, home_component=LAUNCHER_ACTIVITY, happ_whitelisted=True,
                       animation_scales=dict.fromkeys(ANIMATION_KEYS, "0.25"), disabled_packages={GAMES})
        self.assertFalse(self.apply(adb, ApplyPlan(False, False, False, False, [ADS, GAMES]), info).errors)
        snap = self.read_snapshot()
        self.assertEqual(snap["home_component"], HOME)
        self.assertEqual(snap["animation_scales"], self.info.animation_scales)
        self.assertEqual(snap["disabled_packages"], {GAMES: "disable", ADS: "uninstall", STOCK: "disable"})
        self.assertEqual(adb.commands.count(DISABLE + GAMES), 1)
        adb.commands.clear()
        self.assertFalse(rollback_device(adb, SERIAL, self.state).errors)
        self.assertIn("pm enable " + GAMES, adb.commands)
        self.assertIn("cmd package install-existing " + ADS, adb.commands)
        self.assertLess(adb.commands.index("pm enable " + STOCK), adb.commands.index(SET_HOME + HOME))
        self.assertIn("settings put global window_animation_scale 1.0", adb.commands)

    def test_zero_exit_failure_markers_trigger_fallback_and_no_false_success(self):
        for marker in ("SecurityException", "Failure", "Unknown package:", "DELETE_FAILED_INTERNAL_ERROR"):
            with self.subTest(marker=marker):
                adb = FakeAdb({DISABLE + ADS: output(marker), UNINSTALL + ADS: AdbResult(0, "", marker),
                               "pm list packages -d": output(""), "pm list packages": output(f"package:{ADS}")})
                report = self.apply(adb, ApplyPlan(False, False, False, False, [ADS]))
                self.assertEqual(adb.commands, [DISABLE + ADS, "pm list packages -d", UNINSTALL + ADS, "pm list packages"])
                self.assertTrue(report.errors)
                self.assertEqual(self.read_snapshot()["disabled_packages"], {})

    def test_adb_error_is_reported_and_other_changes_continue(self):
        adb = FakeAdb({DISABLE + ADS: output("Failure"), UNINSTALL + ADS: output("Failure [DELETE_FAILED_INTERNAL_ERROR: offline]"),
                       "pm list packages -d": output(""), "pm list packages": output(f"package:{ADS}")})
        report = self.apply(adb, ApplyPlan(False, False, False, False, [ADS, GAMES]))
        self.assertTrue(any("offline" in error for error in report.errors))
        self.assertEqual(self.read_snapshot()["disabled_packages"], {GAMES: "disable"})
        adb = FakeAdb({"getprop ro.serialno": AdbError("offline")})
        self.assertTrue(rollback_device(adb, SERIAL, self.state).errors)
        with self.assertRaises(AdbError):
            inspect_device(adb, SERIAL)

    def test_rollback_deletes_null_scales(self):
        adb = FakeAdb({"getprop ro.serialno": output("unknown\n")})
        info = replace(self.info, device_id=SERIAL, animation_scales=dict.fromkeys(ANIMATION_KEYS, "null"))
        self.assertFalse(self.apply(adb, ApplyPlan(True, False, False, False, []), info).errors)
        adb.commands.clear()
        self.assertFalse(rollback_device(adb, SERIAL, self.state).errors)
        self.assertEqual(adb.commands[-3:], [f"settings delete global {key}" for key in ANIMATION_KEYS])

    def test_rollback_rejects_missing_and_corrupt_snapshots(self):
        adb = FakeAdb({"getprop ro.serialno": output("SER123")})
        self.assertTrue(rollback_device(adb, SERIAL, self.state).errors)
        self.apply(FakeAdb(), ApplyPlan(False, False, False, False, []))
        for corruption in ("{", "[]", '{"home_component":null,"animation_scales":{},"disabled_packages":{"pkg; id":"disable"}}'):
            with self.subTest(corruption=corruption):
                self.snapshot.write_text(corruption, encoding="utf-8")
                adb.commands.clear()
                self.assertTrue(rollback_device(adb, SERIAL, self.state).errors)
                self.assertEqual(adb.commands, ["getprop ro.serialno"])
                adb.commands.clear()
                self.assertTrue(self.apply(adb, ApplyPlan(True, False, False, False, [])).errors)
                self.assertFalse(adb.commands)

    def test_stock_disable_alone_does_not_switch_home(self):
        adb = FakeAdb()
        self.apply(adb, ApplyPlan(False, False, False, True, []))
        self.assertEqual(adb.commands, [])

    def test_leaves_preexisting_disabled_packages_alone(self):
        adb = FakeAdb()
        info = replace(self.info, disabled_packages={GAMES})
        report = self.apply(adb, ApplyPlan(False, False, False, False, [GAMES]), info)
        self.assertFalse(report.errors)
        self.assertFalse(adb.commands)
        self.assertEqual(self.read_snapshot()["disabled_packages"], {})

    def test_snapshot_write_failure_prevents_changes(self):
        self.state.touch()
        adb = FakeAdb()
        report = self.apply(adb, ApplyPlan(True, False, False, False, [GAMES]))
        self.assertTrue(report.errors)
        self.assertFalse(adb.commands)

    def test_rollback_preserves_nested_activity_class(self):
        adb = FakeAdb({"getprop ro.serialno": output("SER123")})
        nested_home = f"{STOCK}/.Outer$Inner"
        info = replace(self.info, home_component=nested_home)
        self.assertFalse(self.apply(adb, ApplyPlan(True, False, False, False, []), info).errors)
        self.assertFalse(rollback_device(adb, SERIAL, self.state).errors)
        self.assertIn(f"{SET_HOME}'{nested_home}'", adb.commands)

    def test_can_disable_a_package_again_after_rollback(self):
        adb = FakeAdb({"getprop ro.serialno": output("SER123")})
        plan = ApplyPlan(False, False, False, False, [GAMES])
        self.assertFalse(self.apply(adb, plan).errors)
        self.assertFalse(rollback_device(adb, SERIAL, self.state).errors)
        adb.commands.clear()
        self.assertFalse(self.apply(adb, plan).errors)
        self.assertEqual(adb.commands, [DISABLE + GAMES])

    def test_stock_option_never_disables_third_party_home(self):
        pkg = "com.example.home"
        adb = FakeAdb()
        info = replace(self.info, home_component=f"{pkg}/.Home", installed_packages={LAUNCHER_PACKAGE, pkg})
        self.assertFalse(self.apply(adb, ApplyPlan(False, False, True, True, []), info).errors)
        self.assertEqual(adb.commands, [SET_HOME + LAUNCHER_ACTIVITY, "input keyevent KEYCODE_HOME"])

    def test_stock_option_uses_preserved_home_after_switch(self):
        adb = FakeAdb()
        self.assertFalse(self.apply(adb, ApplyPlan(False, False, True, False, [])).errors)
        adb.commands.clear()
        info = replace(self.info, home_component=LAUNCHER_ACTIVITY,
                       installed_packages=self.info.installed_packages | {"com.mitv.tvhome"})
        self.assertFalse(self.apply(adb, ApplyPlan(False, False, False, True, []), info).errors)
        self.assertEqual(adb.commands, [DISABLE + STOCK])

    def test_refuses_home_switch_and_stock_disable_without_original_home(self):
        adb = FakeAdb()
        info = replace(self.info, home_component=None)
        self.assertTrue(self.apply(adb, ApplyPlan(False, False, True, True, []), info).errors)
        self.assertEqual(adb.commands, [])
        info = replace(info, home_component=LAUNCHER_ACTIVITY)
        self.assertTrue(self.apply(adb, ApplyPlan(False, False, False, True, []), info).errors)
        self.assertEqual(adb.commands, [])

    def test_rollback_reports_unrestorable_older_home_switch(self):
        adb = FakeAdb({"getprop ro.serialno": output("SER123")})
        self.apply(adb, ApplyPlan(False, False, False, False, []), replace(self.info, home_component=None))
        data = {**self.read_snapshot(), "home_switched": True}
        self.snapshot.write_text(json.dumps(data), encoding="utf-8")
        report = rollback_device(adb, SERIAL, self.state)
        self.assertTrue(any("original HOME" in error for error in report.errors))
        self.assertFalse(any("preferred-activities" in cmd or cmd.startswith(SET_HOME) for cmd in adb.commands))

    def test_interrupted_package_mutation_is_recoverable(self):
        for method, mutation, restore in (
            ("disable", DISABLE, f"pm enable {ADS}"),
            ("uninstall", UNINSTALL, f"cmd package install-existing {ADS}"),
        ):
            with self.subTest(method=method):
                class InterruptedAdb(FakeAdb):
                    def shell(inner, serial, command, timeout=15.0):
                        if command == mutation + ADS:
                            self.assertEqual(self.read_snapshot().get("pending_package"), {"package": ADS, "method": method})
                            raise KeyboardInterrupt
                        return super().shell(serial, command, timeout)

                adb = InterruptedAdb({DISABLE + ADS: output("Failure")})
                with self.assertRaises(KeyboardInterrupt):
                    self.apply(adb, ApplyPlan(False, False, False, False, [ADS]))
                adb = FakeAdb({"getprop ro.serialno": output("SER123"), "pm list packages": output(f"package:{ADS}")})
                self.assertTrue(rollback_device(adb, SERIAL, self.state).errors)
                adb.responses[BOOT] = output(BOOT_B)
                self.assertFalse(rollback_device(adb, SERIAL, self.state).errors)
                self.assertIn(restore, adb.commands)
                self.assertNotIn("pending_package", self.read_snapshot())

    def test_ambiguous_disable_checks_package_state_before_uninstall(self):
        adb = FakeAdb({DISABLE + ADS: AdbError("timeout"), "pm list packages -d": output(f"package:{ADS}")})
        self.assertFalse(self.apply(adb, ApplyPlan(False, False, False, False, [ADS])).errors)
        self.assertNotIn(UNINSTALL + ADS, adb.commands)
        self.assertEqual(self.read_snapshot()["disabled_packages"], {ADS: "disable"})

    def test_successful_disable_with_error_in_package_name(self):
        pkg = "com.example.error"
        adb = FakeAdb({DISABLE + pkg: output(f"Package {pkg} new state: disabled-user")})
        info = replace(self.info, installed_packages={pkg})
        self.assertFalse(self.apply(adb, ApplyPlan(False, False, False, False, [pkg]), info).errors)
        self.assertEqual(adb.commands, [DISABLE + pkg])
        self.assertEqual(self.read_snapshot()["disabled_packages"], {pkg: "disable"})

    def test_snapshot_identity_distinguishes_serial_case_and_rejects_mismatch(self):
        for device, home in (("ABC", HOME), ("abc", LAUNCHER_ACTIVITY)):
            self.assertFalse(self.apply(FakeAdb(), ApplyPlan(False, False, False, False, []),
                                        replace(self.info, device_id=device, home_component=home)).errors)
        paths = {data["device_id"]: path for path in self.state.glob("*.json")
                 if (data := json.loads(path.read_text(encoding="utf-8")))}
        self.assertNotEqual(paths["ABC"].name.casefold(), paths["abc"].name.casefold())
        adb = FakeAdb({"getprop ro.serialno": output("ABC")})
        self.assertFalse(rollback_device(adb, SERIAL, self.state).errors)
        self.assertIn(SET_HOME + HOME, adb.commands)
        paths["ABC"].write_bytes(paths["abc"].read_bytes())
        adb.commands.clear()
        self.assertTrue(rollback_device(adb, SERIAL, self.state).errors)
        self.assertEqual(adb.commands, ["getprop ro.serialno"])

    def test_ambiguous_uninstall_checks_current_package_inventory(self):
        adb = FakeAdb({DISABLE + ADS: output("Failure"), UNINSTALL + ADS: AdbError("timeout")})
        self.assertFalse(self.apply(adb, ApplyPlan(False, False, False, False, [ADS])).errors)
        self.assertEqual(adb.commands[-1], "pm list packages")
        self.assertEqual(self.read_snapshot()["disabled_packages"], {ADS: "uninstall"})
        self.assertNotIn("pending_package", self.read_snapshot())

    def test_inventory_failure_preserves_pending_and_prevents_uninstall(self):
        adb = FakeAdb({DISABLE + ADS: AdbError("timeout"),
                       "pm list packages -d": AdbResult(0, "", "Error: cannot list packages")})
        self.assertTrue(self.apply(adb, ApplyPlan(False, False, False, False, [ADS])).errors)
        self.assertEqual(adb.commands, [DISABLE + ADS, "pm list packages -d"])
        self.assertEqual(self.read_snapshot()["pending_package"], {"package": ADS, "method": "disable"})

    def test_ambiguous_disable_without_visible_change_keeps_pending(self):
        for failure in (AdbError("timeout"), AdbResult(1, "", ""), AdbResult(1, "", "error: device offline")):
            with self.subTest(failure=failure):
                adb = FakeAdb({DISABLE + ADS: failure})
                self.assertTrue(self.apply(adb, ApplyPlan(False, False, False, False, [ADS])).errors)
                self.assertEqual(adb.commands, [DISABLE + ADS, "pm list packages -d"])
                self.assertEqual(self.read_snapshot()["pending_package"], {"package": ADS, "method": "disable"})
                retry = FakeAdb()
                self.assertTrue(self.apply(retry, ApplyPlan(True, False, False, False, [ADS])).errors)
                self.assertEqual(retry.commands, [])
                recovery = FakeAdb({"getprop ro.serialno": output("SER123"), "pm list packages": output(f"package:{ADS}")})
                self.assertTrue(rollback_device(recovery, SERIAL, self.state).errors)
                recovery.responses[BOOT] = output(BOOT_B)
                self.assertFalse(rollback_device(recovery, SERIAL, self.state).errors)

    def test_rollback_restores_pending_even_before_change_is_visible(self):
        enable = f"pm enable {ADS}"
        install = f"cmd package install-existing {ADS}"
        self.apply(FakeAdb(), ApplyPlan(False, False, False, False, []))
        for method, inventory, expected in (("disable", {}, [enable]),
                ("uninstall", {"pm list packages": output(f"package:{ADS}")}, [install, enable])):
            with self.subTest(method=method):
                data = {**self.read_snapshot(), "pending_package": {"package": ADS, "method": method}}
                self.snapshot.write_text(json.dumps(data), encoding="utf-8")
                responses = {"getprop ro.serialno": output("SER123"), "pm list packages": output(f"package:{ADS}"), **inventory}
                adb = FakeAdb({**responses, enable: output("Error: cannot enable")})
                self.assertTrue(rollback_device(adb, SERIAL, self.state).errors)
                self.assertIn("pending_package", self.read_snapshot())
                adb = FakeAdb(responses)
                # Both inventory checks pass before the original mutation takes effect.
                self.assertTrue(rollback_device(adb, SERIAL, self.state).errors)
                self.assertIn("pending_package", self.read_snapshot())
                self.assertEqual([cmd for cmd in adb.commands if cmd in (install, enable)], expected)
                # The queued command lands after rollback returned, before reboot.
                inventory_command = "pm list packages -d" if method == "disable" else "pm list packages"
                adb.responses = {inventory_command: output(f"package:{ADS}" if method == "disable" else ""),
                                 **{k: v for k, v in responses.items() if k != inventory_command}}
                adb.responses[BOOT] = output(BOOT_B)
                self.assertTrue(rollback_device(adb, SERIAL, self.state).errors)
                adb.responses[BOOT] = output("unknown")
                adb.responses[inventory_command] = output("" if method == "disable" else f"package:{ADS}")
                self.assertTrue(rollback_device(adb, SERIAL, self.state).errors)
                adb.responses[BOOT] = output(BOOT_B)
                self.assertFalse(rollback_device(adb, SERIAL, self.state).errors)
                self.assertNotIn("pending_package", self.read_snapshot())

    def test_legacy_snapshot_blocks_apply_and_rollback(self):
        self.state.mkdir()
        self.assert_legacy_snapshot_blocks_apply_and_rollback()

    def assert_legacy_snapshot_blocks_apply_and_rollback(self):
        existing = set(self.state.glob("*.json"))
        legacy = self.state / "SER123.json"
        legacy.write_text("{}", encoding="utf-8")
        adb = FakeAdb({"getprop ro.serialno": output("SER123")})
        for report in (self.apply(adb, ApplyPlan(True, False, False, False, [ADS])),
                       rollback_device(adb, SERIAL, self.state)):
            self.assertTrue(any(str(legacy) in error and "legacy" in error.lower() for error in report.errors))
        self.assertEqual(adb.commands, ["getprop ro.serialno"])
        self.assertEqual(set(self.state.glob("*.json")), existing | {legacy})

    def test_chooser_home_cannot_be_switched_or_restored(self):
        chooser = "android/com.android.internal.app.ResolverActivity"
        adb = FakeAdb({"getprop ro.serialno": output("SER123")})
        info = replace(self.info, home_component=chooser)
        self.assertTrue(self.apply(adb, ApplyPlan(False, False, True, True, []), info).errors)
        self.assertEqual(adb.commands, [])
        self.assertTrue(rollback_device(adb, SERIAL, self.state).errors)
        self.assertFalse(any(cmd.startswith(SET_HOME) for cmd in adb.commands))

    def test_coexisting_legacy_snapshot_blocks_apply_and_rollback(self):
        self.assertFalse(self.apply(FakeAdb(), ApplyPlan(False, False, False, False, [])).errors)
        self.assert_legacy_snapshot_blocks_apply_and_rollback()

    def test_generic_errors_reconcile_before_any_uninstall(self):
        for message in ("Error: command interrupted", "java.lang.RuntimeException: interrupted", "Failure"):
            for visible in (True, False):
                if not visible and message == "Failure":
                    continue
                with self.subTest(message=message, visible=visible):
                    self.snapshot.unlink(missing_ok=True)
                    adb = FakeAdb({DISABLE + ADS: output(message),
                                   "pm list packages -d": output(f"package:{ADS}" if visible else "")})
                    report = self.apply(adb, ApplyPlan(False, False, False, False, [ADS]))
                    self.assertEqual(adb.commands, [DISABLE + ADS, "pm list packages -d"])
                    self.assertEqual(bool(report.errors), not visible)
                    data = self.read_snapshot()
                    if visible:
                        self.assertEqual(data["disabled_packages"], {ADS: "disable"})
                    else:
                        self.assertEqual(data["pending_package"], {"package": ADS, "method": "disable"})

    def test_pending_rollback_restores_and_checks_inventory_without_boot_id(self):
        self.apply(FakeAdb(), ApplyPlan(False, False, False, False, []))
        original = self.read_snapshot()
        enable = f"pm enable {ADS}"
        install = f"cmd package install-existing {ADS}"
        for method, boot, fence, restore in (
            ("uninstall", AdbResult(1, "", "Permission denied"), {}, [install, enable]),
            ("disable", output("malformed"), {"recovery_boot_id": BOOT_A}, [enable]),
        ):
            with self.subTest(method=method):
                data = {**original, **fence, "pending_package": {"package": ADS, "method": method}}
                self.snapshot.write_text(json.dumps(data), encoding="utf-8")
                adb = FakeAdb({BOOT: boot, "getprop ro.serialno": output("SER123"),
                               "pm list packages": output(f"package:{ADS}")})
                report = rollback_device(adb, SERIAL, self.state)
                self.assertTrue(any("Cannot establish package recovery boot" in e for e in report.errors))
                checks = ["pm list packages", "pm list packages -d"]
                self.assertEqual([c for c in adb.commands if c in [install, enable, *checks]], restore + checks)
                self.assertEqual(self.read_snapshot(), data)

    def test_pending_rollback_restores_when_recovery_boot_save_fails(self):
        self.apply(FakeAdb(), ApplyPlan(False, False, False, False, []))
        data = {**self.read_snapshot(), "pending_package": {"package": ADS, "method": "disable"}}
        self.snapshot.write_text(json.dumps(data), encoding="utf-8")
        adb = FakeAdb({"getprop ro.serialno": output("SER123"), "pm list packages": output(f"package:{ADS}")})
        with patch("tv_setup.optimizer._save_snapshot", side_effect=OSError("disk full")):
            report = rollback_device(adb, SERIAL, self.state)
        self.assertTrue(any("Cannot establish package recovery boot" in e for e in report.errors))
        expected = [f"pm enable {ADS}", "pm list packages", "pm list packages -d"]
        self.assertEqual([c for c in adb.commands if c in expected], expected)
        self.assertEqual(self.read_snapshot(), data)

    def test_pending_restore_verifies_inventory_and_retains_retry_record(self):
        for method in ("disable", "uninstall"):
            with self.subTest(method=method):
                self.snapshot.unlink(missing_ok=True)
                self.apply(FakeAdb(), ApplyPlan(False, False, False, False, []))
                data = {**self.read_snapshot(), "pending_package": {"package": ADS, "method": method}}
                self.snapshot.write_text(json.dumps(data), encoding="utf-8")
                responses = {"getprop ro.serialno": output("SER123")}
                if method == "disable":
                    responses.update({"pm list packages -d": output(f"package:{ADS}"),
                                      "pm list packages": output(f"package:{ADS}")})
                self.assertTrue(rollback_device(FakeAdb(responses), SERIAL, self.state).errors)
                self.assertIn("pending_package", self.read_snapshot())
                active = FakeAdb({"getprop ro.serialno": output("SER123"),
                                  "pm list packages": output(f"package:{ADS}")})
                active.responses[BOOT] = output(BOOT_B)
                self.assertFalse(rollback_device(active, SERIAL, self.state).errors)
                self.assertNotIn("pending_package", self.read_snapshot())
                self.assertEqual(self.read_snapshot()["disabled_packages"], {ADS: method})
                late = FakeAdb(responses)
                self.assertFalse(rollback_device(late, SERIAL, self.state).errors)
                restore = "pm enable " if method == "disable" else "cmd package install-existing "
                self.assertIn(restore + ADS, late.commands)
