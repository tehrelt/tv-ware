"""Locate Windows platform-tools and run ADB commands."""

import os
import shutil
import stat
import subprocess
import tempfile
import urllib.request
import zipfile
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path, PureWindowsPath

PLATFORM_TOOLS_URL = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
DEFAULT_TOOLS_DIR = Path(__file__).resolve().parents[1] / "tools"
_ADB_RELATIVE_PATH = Path("platform-tools", "adb.exe")
_DOWNLOAD_CHUNK_SIZE = 64 * 1024
_DOWNLOAD_TIMEOUT = 30.0
_INSTALL_TIMEOUT = 120.0


class AdbError(RuntimeError):
    """ADB could not be started or did not provide the requested result."""


@dataclass(frozen=True)
class AdbResult:
    returncode: int
    stdout: str
    stderr: str


@dataclass(frozen=True)
class UsbOrTcpDevice:
    serial: str
    state: str
    model: str
    product: str
    transport_id: str


def find_adb_executable(
    tools_dir: Path,
    which_fn: Callable[[str], str | None] = shutil.which,
    local_appdata: str | None = None,
) -> Path | None:
    local_adb = tools_dir / _ADB_RELATIVE_PATH
    if local_adb.is_file():
        return local_adb
    if path_adb := which_fn("adb"):
        return Path(path_adb)
    if local_appdata is None:
        local_appdata = os.environ.get("LOCALAPPDATA")
    if local_appdata:
        sdk_adb = Path(local_appdata) / "Android" / "Sdk" / _ADB_RELATIVE_PATH
        if sdk_adb.is_file():
            return sdk_adb
    return None


def safe_extract_zip(zip_path: Path, dest_dir: Path) -> None:
    root = dest_dir.resolve()
    with zipfile.ZipFile(zip_path) as archive:
        for member in archive.infolist():
            name = member.orig_filename
            path = PureWindowsPath(name)
            if (
                not path.parts
                or path.anchor
                or ".." in path.parts
                or any(
                    PureWindowsPath(part).is_reserved()
                    or part.endswith((".", " "))
                    or any(char in ':<>|"?*' or ord(char) < 32 for char in part)
                    for part in path.parts
                )
                or stat.S_ISLNK(member.external_attr >> 16)
                or not (root.joinpath(*path.parts)).resolve().is_relative_to(root)
            ):
                raise ValueError(f"Unsafe ZIP member: {name!r}")
        archive.extractall(root)


def download_platform_tools(
    tools_dir: Path,
    progress_cb: Callable[[int, int], None] | None = None,
    url: str = PLATFORM_TOOLS_URL,
) -> Path:
    tools_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=tools_dir, suffix=".zip.part", delete=False) as archive:
        archive_path = Path(archive.name)
    try:
        with urllib.request.urlopen(url, timeout=_DOWNLOAD_TIMEOUT) as response, archive_path.open("wb") as output:
            total = int(response.headers.get("Content-Length") or 0)
            downloaded = 0
            while chunk := response.read(_DOWNLOAD_CHUNK_SIZE):
                output.write(chunk)
                downloaded += len(chunk)
                if progress_cb is not None:
                    progress_cb(downloaded, total)
        safe_extract_zip(archive_path, tools_dir)
        adb_path = tools_dir / _ADB_RELATIVE_PATH
        if not adb_path.is_file():
            raise AdbError("platform-tools archive did not provide adb.exe")
        return adb_path
    finally:
        archive_path.unlink(missing_ok=True)


def parse_adb_devices(output: str) -> list[UsbOrTcpDevice]:
    devices = []
    for line in output.splitlines():
        fields = line.split()
        if len(fields) < 2 or fields[0] == "*" or line.strip() == "List of devices attached":
            continue
        serial, state, *details = fields
        metadata = dict(field.split(":", 1) for field in details if ":" in field)
        devices.append(UsbOrTcpDevice(
            serial, state, metadata.get("model", ""), metadata.get("product", ""),
            metadata.get("transport_id", ""),
        ))
    return devices


def _message(result: AdbResult) -> str:
    return "\n".join(text for text in (result.stdout.strip(), result.stderr.strip()) if text)


class AdbClient:
    def __init__(self, adb_path: Path | None = None, tools_dir: Path = DEFAULT_TOOLS_DIR):
        self.adb_path = adb_path
        self.tools_dir = tools_dir

    def ensure_adb(self) -> Path:
        if self.adb_path is None:
            self.adb_path = find_adb_executable(self.tools_dir) or download_platform_tools(self.tools_dir)
        return self.adb_path

    def run(self, args: list[str], serial: str | None = None, timeout: float = 15.0) -> AdbResult:
        command = [str(self.ensure_adb()), *(["-s", serial] if serial is not None else []), *args]
        try:
            result = subprocess.run(
                command, capture_output=True, text=True, encoding="utf-8", errors="replace",
                timeout=timeout, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        except subprocess.TimeoutExpired:
            raise AdbError(f"ADB command timed out after {timeout:g} seconds") from None
        except OSError as exc:
            raise AdbError(f"Could not start ADB: {exc}") from exc
        return AdbResult(result.returncode, result.stdout, result.stderr)

    def shell(self, serial: str, command: str, timeout: float = 15.0) -> AdbResult:
        return self.run(["shell", command], serial=serial, timeout=timeout)

    def devices(self) -> list[UsbOrTcpDevice]:
        result = self.run(["devices", "-l"])
        if result.returncode:
            raise AdbError(_message(result) or "Could not list ADB devices")
        return parse_adb_devices(result.stdout)

    def connect(self, target: str) -> tuple[bool, str]:
        message = _message(self.run(["connect", target]))
        text = message.lower()
        return "connected to" in text and "cannot connect" not in text and "failed to connect" not in text, message

    def pair(self, target: str, code: str) -> tuple[bool, str]:
        if not code.strip():
            return False, "Pairing code is required"
        message = _message(self.run(["pair", target, code]))
        return "successfully paired" in message.lower(), message

    def install(self, serial: str, apk_path: Path, downgrade: bool = True) -> AdbResult:
        flags = ["-r", "-d"] if downgrade else ["-r"]
        return self.run(["install", *flags, str(apk_path)], serial=serial, timeout=_INSTALL_TIMEOUT)

    def uninstall(self, serial: str, package: str) -> AdbResult:
        return self.run(["uninstall", package], serial=serial)
