"""Inspect and optimize an Android TV, retaining its original settings for rollback."""

import json
import os
import re
import shlex
import tempfile
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from .adb_client import AdbClient, AdbError
from .presets import (
    ANIMATION_KEYS,
    HAPP_PACKAGES,
    LAUNCHER_ACTIVITY,
    LAUNCHER_PACKAGE,
    STOCK_LAUNCHER_PACKAGES,
)

_PACKAGE = r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*"
_COMPONENT = rf"{_PACKAGE}/\.?[A-Za-z_$][A-Za-z0-9_.$]*"
_SCALE = r"(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?|null"
_FAILURE = re.compile(r"\b(?:SecurityException|Failure|Error|Exception)\b", re.IGNORECASE)
_PROTECTED = {LAUNCHER_PACKAGE, "com.android.settings"}
_SET_HOME = "cmd package set-home-activity --user current"
_WHITELIST = "dumpsys deviceidle whitelist"


@dataclass(frozen=True)
class DeviceInspection:
    device_id: str
    model: str
    android_version: str
    sdk: int
    home_component: str | None
    animation_scales: dict[str, str]
    happ_whitelisted: bool
    installed_packages: set[str]
    disabled_packages: set[str]


@dataclass(frozen=True)
class ApplyPlan:
    enable_fast_animations: bool
    whitelist_happ: bool
    set_home_launcher: bool
    disable_stock_launcher: bool
    packages_to_disable: list[str]


@dataclass(frozen=True)
class ApplyReport:
    actions: list[str]
    errors: list[str]


def _read(adb: AdbClient, serial: str, command: str) -> str:
    result = adb.shell(serial, command)
    if result.returncode:
        raise AdbError(f"{command}: {result.stderr.strip() or result.stdout.strip() or result.returncode}")
    return result.stdout.strip()


def _safe_id(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]|\.{2,}", "_", value).strip(".") or "device"


def _device_id(adb: AdbClient, serial: str) -> str:
    value = _read(adb, serial, "getprop ro.serialno")
    return _safe_id(serial if value.lower() in {"", "unknown"} else value)


def _packages(text: str) -> set[str]:
    return {match[1] for line in text.splitlines()
            if (match := re.fullmatch(rf"package:({_PACKAGE})", line.strip()))}


def inspect_device(adb: AdbClient, serial: str) -> DeviceInspection:
    model = _read(adb, serial, "getprop ro.product.model")
    version = _read(adb, serial, "getprop ro.build.version.release")
    sdk = _read(adb, serial, "getprop ro.build.version.sdk")
    device_id = _device_id(adb, serial)
    home = _read(adb, serial, "cmd package resolve-activity --brief --user current "
                 "-a android.intent.action.MAIN -c android.intent.category.HOME")
    component = next((line.strip() for line in reversed(home.splitlines())
                      if re.fullmatch(_COMPONENT, line.strip())), None)
    scales = {key: _read(adb, serial, f"settings get global {key}") or "null" for key in ANIMATION_KEYS}
    whitelist = re.split(r"[,\s]+", _read(adb, serial, _WHITELIST))
    disabled = _packages(_read(adb, serial, "pm list packages -d"))
    installed = _packages(_read(adb, serial, "pm list packages"))
    return DeviceInspection(device_id, model, version, int(sdk) if sdk.isascii() and sdk.isdecimal() else 0,
                            component, scales, any(pkg in whitelist for pkg in HAPP_PACKAGES), installed, disabled)


def _matches(pattern: str, value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def _validate_snapshot(snapshot: dict) -> dict:
    if not isinstance(snapshot, dict) or not {"home_component", "animation_scales", "disabled_packages"} <= snapshot.keys():
        raise ValueError("invalid snapshot structure")
    home, scales, packages = (snapshot[key] for key in ("home_component", "animation_scales", "disabled_packages"))
    if home is not None and not _matches(_COMPONENT, home):
        raise ValueError("invalid snapshot HOME")
    if not isinstance(scales, dict) or any(key not in ANIMATION_KEYS or not _matches(_SCALE, value)
                                          for key, value in scales.items()):
        raise ValueError("invalid snapshot animation scales")
    if not isinstance(packages, dict) or any(not _matches(_PACKAGE, pkg) or method not in ("disable", "uninstall")
                                            for pkg, method in packages.items()):
        raise ValueError("invalid snapshot package record")
    return snapshot


def _load_snapshot(path: Path) -> dict:
    return _validate_snapshot(json.loads(path.read_text(encoding="utf-8")))


def _save_snapshot(path: Path, snapshot: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False)
    temp_path = Path(temp.name)
    try:
        with temp:
            json.dump(snapshot, temp, indent=2)
            temp.flush()
            os.fsync(temp.fileno())
        temp_path.replace(path)
    finally:
        temp_path.unlink(missing_ok=True)


def _command(adb: AdbClient, serial: str, command: str) -> None:
    result = adb.shell(serial, command)
    output = f"{result.stdout}\n{result.stderr}".strip()
    if result.returncode or _FAILURE.search(output):
        raise AdbError(f"{command}: {output or result.returncode}")


def _restore_package(package: str, method: str) -> str:
    return f"pm enable {package}" if method == "disable" else f"cmd package install-existing {package}"


def apply_optimizations(
    adb: AdbClient, serial: str, inspection: DeviceInspection, plan: ApplyPlan,
    state_dir: Path, log_cb: Callable[[str], None] | None = None,
) -> ApplyReport:
    report = ApplyReport([], [])
    path = state_dir / f"{_safe_id(inspection.device_id)}.json"
    try:
        try:
            snapshot = _load_snapshot(path)
        except FileNotFoundError:
            snapshot = _validate_snapshot(dict(home_component=inspection.home_component,
                                               animation_scales=inspection.animation_scales, disabled_packages={}))
            _save_snapshot(path, snapshot)
    except (OSError, ValueError) as exc:
        _record(report.errors, f"Snapshot {path}: {exc}", log_cb)
        return report

    def run(command: str) -> bool:
        return _run(adb, serial, command, report, log_cb)

    def disable(package: str) -> None:
        nonlocal snapshot
        if package in _PROTECTED or package in inspection.disabled_packages:
            return
        snapshot = _disable_package(adb, serial, package, path, snapshot, report, log_cb)

    if plan.enable_fast_animations:
        for key in ANIMATION_KEYS:
            run(f"settings put global {key} 0.25")
    if plan.whitelist_happ:
        happ = [pkg for pkg in HAPP_PACKAGES if pkg in inspection.installed_packages]
        for pkg in happ:
            run(f"{_WHITELIST} +{pkg}")
        if not happ:
            _record(report.actions, "Skipped Happ whitelist: Happ is not installed", log_cb)

    previous_home = (inspection.home_component or "").partition("/")[0]
    if plan.set_home_launcher or plan.disable_stock_launcher:
        if LAUNCHER_PACKAGE not in inspection.installed_packages:
            _record(report.errors, f"Cannot switch HOME: {LAUNCHER_PACKAGE} is not installed", log_cb)
        elif run(f"{_SET_HOME} {LAUNCHER_ACTIVITY}"):
            if plan.disable_stock_launcher and _matches(_PACKAGE, previous_home):
                disable(previous_home)
            run("input keyevent KEYCODE_HOME")
    for pkg in plan.packages_to_disable:
        if not _matches(_PACKAGE, pkg):
            _record(report.errors, f"Invalid package name: {pkg!r}", log_cb)
        elif pkg not in STOCK_LAUNCHER_PACKAGES and pkg != previous_home and pkg in inspection.installed_packages:
            disable(pkg)
    return report


def _record(messages: list[str], message: str, log_cb: Callable[[str], None] | None) -> None:
    messages.append(message)
    if log_cb is not None:
        log_cb(message)


def _run(adb: AdbClient, serial: str, command: str, report: ApplyReport, log_cb: Callable[[str], None] | None) -> bool:
    try:
        _command(adb, serial, command)
    except AdbError as exc:
        _record(report.errors, str(exc), log_cb)
        return False
    _record(report.actions, command, log_cb)
    return True


def _disable_package(
    adb: AdbClient, serial: str, pkg: str, path: Path, snapshot: dict,
    report: ApplyReport, log_cb: Callable[[str], None] | None,
) -> dict:
    command = f"pm disable-user --user 0 {pkg}"
    method = "disable"
    try:
        _command(adb, serial, command)
    except AdbError as disable_error:
        command = f"pm uninstall -k --user 0 {pkg}"
        method = "uninstall"
        try:
            _command(adb, serial, command)
        except AdbError as uninstall_error:
            _record(report.errors, f"{disable_error}; fallback failed: {uninstall_error}", log_cb)
            return snapshot

    updated = {**snapshot, "disabled_packages": {**snapshot["disabled_packages"], pkg: method}}
    try:
        _save_snapshot(path, updated)
    except OSError as exc:
        _record(report.errors, f"Cannot record {method} of {pkg}: {exc}", log_cb)
        _run(adb, serial, _restore_package(pkg, method), report, log_cb)
        return snapshot
    _record(report.actions, command, log_cb)
    return updated


def rollback_device(
    adb: AdbClient, serial: str, state_dir: Path, log_cb: Callable[[str], None] | None = None,
) -> ApplyReport:
    report = ApplyReport([], [])
    try:
        path = state_dir / f"{_device_id(adb, serial)}.json"
        snapshot = _load_snapshot(path)
    except (AdbError, OSError, ValueError) as exc:
        _record(report.errors, f"Cannot load rollback snapshot: {exc}", log_cb)
        return report

    for pkg, method in snapshot["disabled_packages"].items():
        _run(adb, serial, _restore_package(pkg, method), report, log_cb)
    if home := snapshot["home_component"]:
        _run(adb, serial, f"{_SET_HOME} {shlex.quote(home)}", report, log_cb)
    for key, value in snapshot["animation_scales"].items():
        command = f"settings delete global {key}" if value == "null" else f"settings put global {key} {value}"
        _run(adb, serial, command, report, log_cb)
    return report
