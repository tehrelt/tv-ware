"""Discover Android TV devices over USB and the local network."""

from __future__ import annotations

import ctypes
import ipaddress
import json
import logging
import socket
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor
from ctypes import wintypes
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


class _SocketAddress(ctypes.Structure):
    _fields_ = [("address", ctypes.c_void_p), ("length", ctypes.c_int)]


class _UnicastAddress(ctypes.Structure):
    pass


_UnicastAddress._fields_ = [
    ("alignment", ctypes.c_uint64),
    ("next", ctypes.POINTER(_UnicastAddress)),
    ("socket_address", _SocketAddress),
    ("prefix_origin", ctypes.c_int),
    ("suffix_origin", ctypes.c_int),
    ("dad_state", ctypes.c_int),
    ("valid_lifetime", wintypes.ULONG),
    ("preferred_lifetime", wintypes.ULONG),
    ("lease_lifetime", wintypes.ULONG),
    ("prefix_length", ctypes.c_ubyte),
]


class _AdapterAddress(ctypes.Structure):
    pass


_AdapterAddress._fields_ = [
    ("alignment", ctypes.c_uint64),
    ("next", ctypes.POINTER(_AdapterAddress)),
    ("adapter_name", ctypes.c_char_p),
    ("first_unicast", ctypes.POINTER(_UnicastAddress)),
    ("first_anycast", ctypes.c_void_p),
    ("first_multicast", ctypes.c_void_p),
    ("first_dns_server", ctypes.c_void_p),
    ("dns_suffix", wintypes.LPWSTR),
    ("description", wintypes.LPWSTR),
    ("friendly_name", wintypes.LPWSTR),
]


def _windows_ipv4_interfaces() -> list[tuple[str, str, int]]:
    if not hasattr(ctypes, "WinDLL"):
        return []
    get_adapters = ctypes.WinDLL("iphlpapi").GetAdaptersAddresses
    get_adapters.argtypes = (wintypes.ULONG, wintypes.ULONG, ctypes.c_void_p,
                            ctypes.POINTER(_AdapterAddress), ctypes.POINTER(wintypes.ULONG))
    get_adapters.restype = wintypes.ULONG
    size = wintypes.ULONG(15_000)
    for _ in range(3):
        buffer = ctypes.create_string_buffer(size.value)
        adapter = ctypes.cast(buffer, ctypes.POINTER(_AdapterAddress))
        status = get_adapters(socket.AF_INET, 0, None, adapter, ctypes.byref(size))
        if status != 111:  # ERROR_BUFFER_OVERFLOW: Windows supplied the new size.
            break
    if status:
        raise ctypes.WinError(status)

    interfaces = []
    while adapter:
        entry = adapter.contents
        alias = entry.friendly_name
        address = entry.first_unicast
        while alias and address:
            unicast = address.contents
            sockaddr = unicast.socket_address
            if sockaddr.address and sockaddr.length >= 16:
                family = ctypes.c_ushort.from_address(sockaddr.address).value
                if family == socket.AF_INET:
                    # sockaddr_in stores four IPv4 octets after family and port.
                    ip = socket.inet_ntoa(ctypes.string_at(sockaddr.address + 4, 4))
                    interfaces.append((alias, ip, unicast.prefix_length))
            address = unicast.next
        adapter = entry.next
    return interfaces


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
        return _windows_ipv4_interfaces()
    except OSError as exc:
        logger.debug("Windows adapter enumeration failed: %s", exc)
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

    adb_devices = [_adb_device(device) for device in usb_devices]
    unauthorized_endpoints = {
        (device.ip, device.port) for device in adb_devices
        if device.status == DeviceStatus.UNAUTHORIZED and device.ip
    }

    def add(device: DiscoveredDevice) -> None:
        key = (bool(device.ip), device.id)
        previous = merged.get(key)
        if previous is None:
            merged[key] = device
            return
        winner, other = (device, previous) if priority[device.status] < priority[previous.status] else (previous, device)
        merged[key] = replace(winner, pairing_port=winner.pairing_port or other.pairing_port)

    for device in adb_devices:
        add(device)
    for record in mdns_records:
        device = _mdns_device(record)
        if device is not None and (device.ip, device.port) not in unauthorized_endpoints:
            add(device)
    for ip in open_5555_ips:
        if (ip, _ADB_PORT) not in unauthorized_endpoints:
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
