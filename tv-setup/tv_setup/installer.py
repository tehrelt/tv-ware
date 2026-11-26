"""Download GitHub release APKs, manage local APKs, and install via ADB."""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import tempfile
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PureWindowsPath

from .adb_client import AdbClient, AdbError
from .presets import HAPP_PACKAGES, LAUNCHER_PACKAGE

logger = logging.getLogger(__name__)

_USER_AGENT = "tv-setup"
_HTTP_TIMEOUT = 15.0
_DOWNLOAD_CHUNK_SIZE = 64 * 1024
_VERSIONS_FILENAME = ".versions.json"
_FAILURE_LINE = re.compile(r"(?:^|\n)\s*Failure\b")
_UNINSTALL_FAILURE = re.compile(r"(?:^|\n)\s*Failure\b|DELETE_FAILED_")


class SignatureConflictError(RuntimeError):
    """APK installation failed due to a signing certificate mismatch."""


@dataclass(frozen=True)
class RepoSource:
    key: str
    label: str
    release_url: str
    asset_name: str
    local_filename: str
    package_name: str


REPO_SOURCES: tuple[RepoSource, ...] = (
    RepoSource(
        key="tv-launcher",
        label="tv-launcher",
        release_url="https://api.github.com/repos/tehrelt/tv-ware/releases/tags/launcher-latest",
        asset_name="tv-launcher.apk",
        local_filename="tv-launcher.apk",
        package_name=LAUNCHER_PACKAGE,
    ),
    RepoSource(
        key="happ",
        label="Happ",
        release_url="https://api.github.com/repos/Happ-proxy/happ-android/releases/latest",
        asset_name="Happ.apk",
        local_filename="Happ.apk",
        package_name=HAPP_PACKAGES[0],
    ),
)

_SOURCES_BY_FILENAME = {source.local_filename.casefold(): source for source in REPO_SOURCES}
_PRIORITY_BY_FILENAME = {source.local_filename.casefold(): idx for idx, source in enumerate(REPO_SOURCES)}


@dataclass(frozen=True)
class LocalApk:
    filename: str
    path: Path
    package_hint: str | None
    version_tag: str | None
    updated_at: str | None


def _validate_https_url(url: object) -> str:
    if not isinstance(url, str) or not url.startswith("https://"):
        raise ValueError(f"Expected https:// URL, got {url!r}")
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.netloc or any(ch.isspace() for ch in url):
        raise ValueError(f"Invalid https:// URL: {url!r}")
    return url


def select_release_asset(release_json: dict, asset_name: str) -> tuple[str, int, str, str]:
    if not isinstance(release_json, dict):
        raise ValueError("Release payload must be a JSON object")
    tag_name = release_json.get("tag_name")
    if not isinstance(tag_name, str) or not tag_name.strip():
        raise ValueError("Release payload is missing tag_name")
    assets = release_json.get("assets")
    if not isinstance(assets, list):
        raise ValueError("Release payload is missing assets list")

    for asset in assets:
        if isinstance(asset, dict) and asset.get("name") == asset_name:
            asset_id = asset.get("id")
            if isinstance(asset_id, bool) or not isinstance(asset_id, int):
                raise ValueError(f"Invalid asset id for {asset_name!r}: {asset_id!r}")
            updated_at = asset.get("updated_at")
            if not isinstance(updated_at, str) or not updated_at.strip():
                raise ValueError(f"Invalid updated_at for {asset_name!r}: {updated_at!r}")
            url = _validate_https_url(asset.get("browser_download_url"))
            return tag_name, asset_id, updated_at, url

    raise ValueError(f"Release asset {asset_name!r} not found")


def needs_download(
    versions_meta: dict,
    key: str,
    tag_name: str,
    asset_id: int,
    updated_at: str,
    target_path: Path,
) -> bool:
    if not target_path.is_file() or target_path.stat().st_size == 0:
        return True
    if not isinstance(versions_meta, dict):
        return True
    entry = versions_meta.get(key)
    if not isinstance(entry, dict):
        return True
    stored_id = entry.get("asset_id")
    if isinstance(stored_id, bool) or not isinstance(stored_id, int):
        return True
    return (
        entry.get("tag_name") != tag_name
        or stored_id != asset_id
        or entry.get("updated_at") != updated_at
    )


def _load_versions(apks_dir: Path) -> dict:
    versions_path = apks_dir / _VERSIONS_FILENAME
    try:
        raw = versions_path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return {}
    except (OSError, UnicodeDecodeError) as exc:
        logger.warning("Could not read %s: %s", versions_path, exc)
        return {}
    try:
        data = json.loads(raw)
    except ValueError as exc:
        logger.warning("Corrupt %s: %s", versions_path, exc)
        return {}
    if not isinstance(data, dict):
        logger.warning("Invalid %s structure: expected object", versions_path)
        return {}
    return data


def _save_versions(apks_dir: Path, versions_meta: dict) -> None:
    apks_dir.mkdir(parents=True, exist_ok=True)
    versions_path = apks_dir / _VERSIONS_FILENAME
    temp = tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", dir=apks_dir, prefix=".versions-", suffix=".tmp", delete=False,
    )
    temp_path = Path(temp.name)
    try:
        with temp:
            json.dump(versions_meta, temp, indent=2)
            temp.flush()
            os.fsync(temp.fileno())
        temp_path.replace(versions_path)
    finally:
        temp_path.unlink(missing_ok=True)


def scan_local_apks(apks_dir: Path) -> list[LocalApk]:
    if not apks_dir.is_dir():
        return []
    versions_meta = _load_versions(apks_dir)
    apk_paths = [
        entry for entry in apks_dir.iterdir()
        if entry.is_file() and entry.suffix.lower() == ".apk"
    ]
    apk_paths.sort(
        key=lambda path: (
            _PRIORITY_BY_FILENAME.get(path.name.casefold(), len(REPO_SOURCES)),
            path.name.casefold(),
            path.name,
        )
    )
    items: list[LocalApk] = []
    for path in apk_paths:
        source = _SOURCES_BY_FILENAME.get(path.name.casefold())
        package_hint = source.package_name if source is not None else None
        meta = versions_meta.get(source.key) if source is not None else None
        if not isinstance(meta, dict):
            meta = versions_meta.get(path.name)
        version_tag = meta.get("tag_name") if isinstance(meta, dict) and isinstance(meta.get("tag_name"), str) else None
        updated_at = meta.get("updated_at") if isinstance(meta, dict) and isinstance(meta.get("updated_at"), str) else None
        items.append(LocalApk(path.name, path, package_hint, version_tag, updated_at))
    return items


def _log(log_cb: Callable[[str], None] | None, message: str) -> None:
    if log_cb is not None:
        log_cb(message)


def _open_https(url: str, accept: str | None = None):
    _validate_https_url(url)
    headers = {"User-Agent": _USER_AGENT}
    if accept is not None:
        headers["Accept"] = accept
    request = urllib.request.Request(url, headers=headers)
    response = urllib.request.urlopen(request, timeout=_HTTP_TIMEOUT)
    geturl = getattr(response, "geturl", None)
    if callable(geturl):
        final_url = geturl()
        if isinstance(final_url, str) and final_url:
            try:
                _validate_https_url(final_url)
            except ValueError:
                close = getattr(response, "close", None)
                if callable(close):
                    close()
                raise
    return response


def _fetch_release_json(url: str) -> dict:
    with _open_https(url, accept="application/vnd.github+json") as response:
        payload = json.loads(response.read().decode("utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("GitHub release response must be a JSON object")
    return payload


def _download_file(
    url: str,
    target_path: Path,
    key: str,
    progress_cb: Callable[[str, int, int], None] | None,
) -> None:
    with tempfile.NamedTemporaryFile(
        dir=target_path.parent, prefix=f".{target_path.name}.", suffix=".part", delete=False,
    ) as tmp:
        part_path = Path(tmp.name)
    try:
        with _open_https(url) as response, part_path.open("wb") as output:
            headers = getattr(response, "headers", None)
            raw_length = headers.get("Content-Length") if hasattr(headers, "get") else None
            try:
                total = int(raw_length or 0)
            except (TypeError, ValueError):
                total = 0
            downloaded = 0
            while chunk := response.read(_DOWNLOAD_CHUNK_SIZE):
                output.write(chunk)
                downloaded += len(chunk)
                if progress_cb is not None:
                    progress_cb(key, downloaded, total)
            output.flush()
            os.fsync(output.fileno())
        if downloaded == 0:
            raise ValueError(f"Downloaded empty APK for {target_path.name}")
        part_path.replace(target_path)
    finally:
        part_path.unlink(missing_ok=True)


def download_repo_apks(
    apks_dir: Path,
    force: bool = False,
    progress_cb: Callable[[str, int, int], None] | None = None,
    log_cb: Callable[[str], None] | None = None,
) -> list[LocalApk]:
    apks_dir.mkdir(parents=True, exist_ok=True)
    versions_meta = _load_versions(apks_dir)

    for source in REPO_SOURCES:
        _log(log_cb, f"Checking {source.label} release...")
        release_json = _fetch_release_json(source.release_url)
        tag_name, asset_id, updated_at, download_url = select_release_asset(release_json, source.asset_name)
        target_path = apks_dir / source.local_filename
        if not force and not needs_download(versions_meta, source.key, tag_name, asset_id, updated_at, target_path):
            _log(log_cb, f"{source.local_filename} is up to date ({tag_name})")
            continue

        _log(log_cb, f"Downloading {source.local_filename} ({tag_name})...")
        _download_file(download_url, target_path, source.key, progress_cb)
        versions_meta = {
            **versions_meta,
            source.key: {
                "tag_name": tag_name,
                "asset_id": asset_id,
                "updated_at": updated_at,
                "downloaded_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            },
        }
        _save_versions(apks_dir, versions_meta)
        _log(log_cb, f"Downloaded {source.local_filename} ({tag_name})")

    return scan_local_apks(apks_dir)


def _validate_apk_filename(name: str) -> str:
    win_name = PureWindowsPath(name)
    if (
        not name
        or name in {".", ".."}
        or len(win_name.parts) != 1
        or win_name.anchor
        or win_name.is_reserved()
        or name.endswith((".", " "))
        or any(ch in '<>:"/\\|?*' or ord(ch) < 32 for ch in name)
    ):
        raise ValueError(f"Unsafe APK filename: {name!r}")
    if win_name.suffix.lower() != ".apk":
        raise ValueError(f"Expected an .apk file, got {name!r}")
    return name


def import_local_apk(src_path: Path, apks_dir: Path) -> Path:
    src = Path(src_path) if not isinstance(src_path, Path) else src_path
    name = _validate_apk_filename(src.name)
    if not src.exists():
        raise FileNotFoundError(f"APK file not found: {src}")
    if not src.is_file():
        raise ValueError(f"APK path is not a file: {src}")
    if src.stat().st_size == 0:
        raise ValueError(f"APK file is empty: {src}")

    apks_dir.mkdir(parents=True, exist_ok=True)
    dest_dir = apks_dir.resolve()
    target = apks_dir / name
    resolved_target = target.resolve()
    if resolved_target.parent != dest_dir or not resolved_target.is_relative_to(dest_dir):
        raise ValueError(f"Unsafe destination path for {name!r}")

    if src.resolve() != resolved_target:
        with tempfile.NamedTemporaryFile(
            dir=apks_dir, prefix=f".{name}.", suffix=".part", delete=False,
        ) as tmp:
            part_path = Path(tmp.name)
        try:
            with src.open("rb") as src_file, part_path.open("wb") as dst_file:
                shutil.copyfileobj(src_file, dst_file)
                dst_file.flush()
                os.fsync(dst_file.fileno())
            part_path.replace(target)
        finally:
            part_path.unlink(missing_ok=True)

        source = _SOURCES_BY_FILENAME.get(name.casefold())
        if source is not None and (apks_dir / _VERSIONS_FILENAME).is_file():
            versions_meta = _load_versions(apks_dir)
            if source.key in versions_meta:
                updated_meta = {k: v for k, v in versions_meta.items() if k != source.key}
                _save_versions(apks_dir, updated_meta)

    return target


def _combined_output(stdout: str, stderr: str) -> str:
    return "\n".join(part for part in (stdout.strip(), stderr.strip()) if part)


def install_apk(adb: AdbClient, serial: str, apk_path: Path) -> None:
    result = adb.install(serial, apk_path, downgrade=True)
    output = _combined_output(result.stdout, result.stderr)
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in output:
        raise SignatureConflictError(output or f"Signature conflict installing {apk_path.name}")
    if result.returncode != 0 or "Failure [" in output or "INSTALL_FAILED_" in output or _FAILURE_LINE.search(output):
        raise AdbError(output or f"adb install failed with exit code {result.returncode}")


def reinstall_after_uninstall(adb: AdbClient, serial: str, package_name: str, apk_path: Path) -> None:
    if not package_name or not package_name.strip():
        raise ValueError("package_name is required")
    result = adb.uninstall(serial, package_name)
    output = _combined_output(result.stdout, result.stderr)
    if result.returncode != 0 or _UNINSTALL_FAILURE.search(output):
        raise AdbError(output or f"adb uninstall {package_name} failed with exit code {result.returncode}")
    install_apk(adb, serial, apk_path)
