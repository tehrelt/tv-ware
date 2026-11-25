"""Discover Android TV devices over USB and the local network."""

from __future__ import annotations

import ipaddress
import json
import logging
import socket
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from enum import Enum

from .adb_client import AdbClient, AdbError, UsbOrTcpDevice

logger = logging.getLogger(__name__)

VIRTUAL_KEYWORDS = (
    "vethernet", "wsl", "hyper-v", "docker", "vmware", "virtualbox", "vbox",
    "meta", "wireguard", "tailscale", "zerotier", "tap", "tun", "bluetooth",
    "loopback", "npcap",
)
_ADB_PORT = 5555
_TLS_CONNECT = "_adb-tls-connect._tcp.local."
_TLS_PAIRING = "_adb-tls-pairing._tcp.local."
_LEGACY_ADB = "_adb._tcp.local."
_TV_REMOTE = "_androidtvremote2._tcp.local."
_MDNS_TYPES = (_TLS_CONNECT, _TLS_PAIRING, _LEGACY_ADB, _TV_REMOTE)
_IPV4_COMMAND = (
    "Get-NetIPAddress -AddressFamily IPv4 | Select-Object InterfaceAlias,IPAddress,PrefixLength | ConvertTo-Json"
)


class DeviceStatus(str, Enum):
    CONNECTED = "connected"
    READY = "ready"
    NEEDS_PAIRING = "needs_pairing"
    UNAUTHORIZED = "unauthorized"
    DEBUG_OFF = "debug_off"


@dataclass(frozen=True)
class MdnsRecord:
    service_type: str
    name: str
    ip: str
    port: int


@dataclass(frozen=True)
class DiscoveredDevice:
    id: str
    name: str
    ip: str
    port: int | None
    pairing_port: int | None
    status: DeviceStatus
    source: str
    serial: str | None


def is_virtual_interface(alias: str) -> bool:
    alias = alias.casefold()
    return any(keyword in alias for keyword in VIRTUAL_KEYWORDS)


def extract_scan_subnets(interfaces: list[tuple[str, str, int]]) -> list[ipaddress.IPv4Network]:
    subnets = []
    for alias, address, _ in interfaces:
        if is_virtual_interface(alias):
            continue
        try:
            ip = ipaddress.IPv4Address(address)
        except ipaddress.AddressValueError:
            continue
        if ip.is_private and not (ip.is_loopback or ip.is_link_local):
            subnets.append(ipaddress.IPv4Network(f"{ip}/24", strict=False))
    return list(dict.fromkeys(subnets))


def get_local_ipv4_interfaces() -> list[tuple[str, str, int]]:
    try:
        result = subprocess.run(
            ["powershell", "-NoProfile", "-Command", _IPV4_COMMAND],
            capture_output=True, text=True, timeout=5,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        result.check_returncode()
        data = json.loads(result.stdout)
        rows = [data] if isinstance(data, dict) else data
        if not isinstance(rows, list):
            raise ValueError("Expected interface rows from PowerShell")
        return [(row["InterfaceAlias"], row["IPAddress"], row["PrefixLength"]) for row in rows
                if isinstance(row, dict) and isinstance(row.get("InterfaceAlias"), str)
                and isinstance(row.get("IPAddress"), str) and isinstance(row.get("PrefixLength"), int)]
    except (OSError, subprocess.SubprocessError, ValueError) as exc:
        logger.debug("PowerShell IPv4 discovery failed: %s", exc)
    try:
        return [("hostname", ip, 24) for ip in socket.gethostbyname_ex(socket.gethostname())[2]]
    except OSError as exc:
        logger.debug("Hostname IPv4 discovery failed: %s", exc)
        return []


def scan_port_5555(
    subnets: list[ipaddress.IPv4Network], timeout: float = 0.35, max_workers: int = 64,
) -> list[str]:
    def probe(host: ipaddress.IPv4Address) -> str | None:
        ip = str(host)
        try:
            socket.create_connection((ip, _ADB_PORT), timeout=timeout).close()
        except OSError:
            return None
        return ip

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        hosts = (host for subnet in subnets for host in subnet.hosts())
        return [ip for ip in executor.map(probe, hosts) if ip is not None]


class _MdnsListener:
    def __init__(self) -> None:
        self.records: dict[tuple[str, str], list[MdnsRecord]] = {}
        self.error: OSError | None = None

    def add_service(self, zeroconf, service_type: str, name: str) -> None:
        try:
            info = zeroconf.get_service_info(service_type, name, timeout=250)
        except OSError as exc:
            self.error = exc
            return
        if info is not None:
            label = name.removesuffix(f".{service_type}")
            self.records[service_type, name] = [
                MdnsRecord(service_type, label, ip, info.port)
                for ip in info.parsed_addresses() if ":" not in ip
            ]

    update_service = add_service

    def remove_service(self, zeroconf, service_type: str, name: str) -> None:
        self.records.pop((service_type, name), None)


def browse_mdns(timeout: float = 2.5) -> list[MdnsRecord]:
    try:
        from zeroconf import ServiceBrowser, Zeroconf
    except ImportError as exc:
        logger.debug("mDNS dependency unavailable: %s", exc)
        return []

    listener = _MdnsListener()
    try:
        zeroconf = Zeroconf()
        try:
            browser = ServiceBrowser(zeroconf, list(_MDNS_TYPES), listener=listener)
            try:
                time.sleep(timeout)
            finally:
                browser.cancel()
        finally:
            zeroconf.close()
        if listener.error is not None:
            raise listener.error
    except OSError as exc:
        logger.debug("mDNS discovery failed: %s", exc)
        return []
    return [record for records in listener.records.values() for record in records]


def _adb_device(device: UsbOrTcpDevice) -> DiscoveredDevice:
    serial = device.serial
    address, _, raw_port = serial.rpartition(":")
    ip, port = "", None
    if raw_port.isascii() and raw_port.isdecimal() and len(raw_port) <= 5:
        try:
            parsed_ip = ipaddress.ip_address(address.strip("[]"))
        except ValueError:
            pass  # USB serials are not necessarily colon-free.
        else:
            if 0 < int(raw_port) <= 65535:
                ip, port = str(parsed_ip), int(raw_port)
    status = {
        "device": DeviceStatus.CONNECTED,
        "unauthorized": DeviceStatus.UNAUTHORIZED,
    }.get(device.state, DeviceStatus.DEBUG_OFF)
    return DiscoveredDevice(
        ip or serial, device.model or serial, ip, port, None, status, "tcp" if ip else "usb", serial,
    )


def _mdns_device(record: MdnsRecord) -> DiscoveredDevice | None:
    if record.service_type in (_TLS_CONNECT, _LEGACY_ADB):
        port, pairing_port, status = record.port, None, DeviceStatus.READY
    elif record.service_type == _TLS_PAIRING:
        port, pairing_port, status = None, record.port, DeviceStatus.NEEDS_PAIRING
    elif record.service_type == _TV_REMOTE:
        port, pairing_port, status = None, None, DeviceStatus.DEBUG_OFF
    else:
        return None
    return DiscoveredDevice(record.ip, record.name, record.ip, port, pairing_port, status, "mdns", None)


def merge_discovered_devices(
    usb_devices: list[UsbOrTcpDevice], mdns_records: list[MdnsRecord], open_5555_ips: list[str],
) -> list[DiscoveredDevice]:
    merged: dict[tuple[bool, str], DiscoveredDevice] = {}
    priority = {status: index for index, status in enumerate(DeviceStatus)}

    def merge_rank(device: DiscoveredDevice) -> int:
        # ADB authorization results outrank a successful TCP handshake.
        if device.status == DeviceStatus.CONNECTED:
            return -1
        return 0 if device.status == DeviceStatus.UNAUTHORIZED else priority[device.status]

    def add(device: DiscoveredDevice) -> None:
        key = (bool(device.ip), device.id)
        previous = merged.get(key)
        if previous is None:
            merged[key] = device
            return
        winner, other = (device, previous) if merge_rank(device) < merge_rank(previous) else (previous, device)
        merged[key] = replace(winner, pairing_port=winner.pairing_port or other.pairing_port)

    for device in usb_devices:
        add(_adb_device(device))
    for record in mdns_records:
        device = _mdns_device(record)
        if device is not None:
            add(device)
    for ip in open_5555_ips:
        add(DiscoveredDevice(ip, ip, ip, _ADB_PORT, None, DeviceStatus.READY, "scan", None))
    return sorted(merged.values(), key=lambda device: priority[device.status])


def discover_devices(
    adb: AdbClient | None, mdns_timeout: float = 2.5, scan_timeout: float = 0.35,
) -> list[DiscoveredDevice]:
    try:
        devices = adb.devices() if adb is not None else []
    except AdbError as exc:
        logger.debug("ADB discovery failed: %s", exc)
        devices = []
    mdns = browse_mdns(timeout=mdns_timeout)
    subnets = extract_scan_subnets(get_local_ipv4_interfaces())
    return merge_discovered_devices(devices, mdns, scan_port_5555(subnets, timeout=scan_timeout))
