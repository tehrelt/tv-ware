import ipaddress
import json
import subprocess
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from tv_setup.adb_client import AdbError, UsbOrTcpDevice
import tv_setup.discovery as discovery
from tv_setup.discovery import DeviceStatus, MdnsRecord


class DiscoveryTests(unittest.TestCase):
    def test_filters_virtual_and_link_local_interfaces(self):
        interfaces = [
            ("vEthernet (Default Switch)", "172.29.128.1", 20),
            ("Meta", "198.18.0.1", 30),
            ("Подключение по локальной сети* 10", "169.254.9.69", 16),
            ("Беспроводная сеть", "192.168.31.73", 24),
            ("Loopback Pseudo-Interface 1", "127.0.0.1", 8),
            ("Ethernet", "192.168.31.90", 16),
            ("Ethernet", "10.0.5.1", 8),
            ("Ethernet", "8.8.8.8", 24),
            ("Ethernet", "::1", 128),
            ("Ethernet", "invalid", 24),
        ]
        self.assertTrue(discovery.is_virtual_interface("vEthernet (Default Switch)"))
        self.assertTrue(discovery.is_virtual_interface("Meta"))
        self.assertFalse(discovery.is_virtual_interface("Беспроводная сеть"))
        self.assertEqual(discovery.extract_scan_subnets(interfaces), [
            ipaddress.IPv4Network("192.168.31.0/24"), ipaddress.IPv4Network("10.0.5.0/24"),
        ])

    def test_merges_mdns_and_port_5555_with_priority_and_pairing_port(self):
        mdns = [
            MdnsRecord("_androidtvremote2._tcp.local.", "MiTV-AFKR0", "192.168.31.42", 6466),
            MdnsRecord("_adb-tls-pairing._tcp.local.", "adb-pair", "192.168.31.50", 37123),
            MdnsRecord("_adb-tls-connect._tcp.local.", "adb-tls", "192.168.31.50", 41234),
            MdnsRecord("_androidtvremote2._tcp.local.", "LivingRoomTV", "192.168.31.60", 6466),
            MdnsRecord("_adb-tls-pairing._tcp.local.", "adb-pair", "192.168.31.42", 37124),
        ]
        adb_devices = [UsbOrTcpDevice("192.168.31.42:5555", "device", "MiTV_AFKR0", "jaws", "1")]
        merged = discovery.merge_discovered_devices(
            adb_devices, mdns, open_5555_ips=["192.168.31.42", "192.168.31.77"],
        )
        self.assertEqual([device.status for device in merged], [
            DeviceStatus.CONNECTED, DeviceStatus.READY, DeviceStatus.READY, DeviceStatus.DEBUG_OFF,
        ])
        by_ip = {d.ip: d for d in merged}
        self.assertEqual(len(by_ip), len(merged))
        self.assertEqual(by_ip["192.168.31.42"].port, 5555)
        self.assertEqual(by_ip["192.168.31.42"].pairing_port, 37124)
        self.assertEqual(by_ip["192.168.31.50"].port, 41234)
        self.assertEqual(by_ip["192.168.31.50"].pairing_port, 37123)
        self.assertEqual(by_ip["192.168.31.77"].port, 5555)

    def test_pairing_only_device_gets_needs_pairing_status(self):
        mdns = [MdnsRecord("_adb-tls-pairing._tcp.local.", "MiBox", "192.168.31.88", 39999)]
        device, = discovery.merge_discovered_devices([], mdns, open_5555_ips=[])
        self.assertEqual(device.status, DeviceStatus.NEEDS_PAIRING)
        self.assertEqual(device.pairing_port, 39999)
        self.assertIsNone(device.port)

    def test_usb_tcp_unauthorized_and_legacy_mdns(self):
        merged = discovery.merge_discovered_devices([
            UsbOrTcpDevice("usb:serial", "unauthorized", "", "", "1"),
            UsbOrTcpDevice("usb123", "device", "Model", "", "2"),
            UsbOrTcpDevice("192.168.31.12:5555", "unauthorized", "", "", "3"),
        ], [
            MdnsRecord("_adb._tcp.local.", "Unauthorized legacy", "192.168.31.12", 5555),
            MdnsRecord("_adb._tcp.local.", "Legacy", "192.168.31.13", 5556),
        ], ["192.168.31.12"])
        self.assertEqual([device.status for device in merged], [
            DeviceStatus.CONNECTED, DeviceStatus.READY, DeviceStatus.UNAUTHORIZED, DeviceStatus.UNAUTHORIZED,
        ])
        by_serial = {device.serial: device for device in merged}
        self.assertEqual(by_serial["usb:serial"].ip, "")
        self.assertEqual(by_serial["usb123"].name, "Model")
        self.assertEqual(by_serial["192.168.31.12:5555"].port, 5555)
        self.assertEqual(by_serial[None].port, 5556)

    def test_connected_adb_wins_over_stale_unauthorized_endpoint(self):
        adb_devices = [
            UsbOrTcpDevice("192.168.31.42:5555", "unauthorized", "", "", "1"),
            UsbOrTcpDevice("192.168.31.42:41234", "device", "TV", "", "2"),
        ]
        device, = discovery.merge_discovered_devices(adb_devices, [], [])
        self.assertEqual(device.status, DeviceStatus.CONNECTED)
        self.assertEqual(device.port, 41234)

    @patch.object(discovery.subprocess, "run")
    def test_reads_powershell_json_object_or_array(self, run):
        row = {"InterfaceAlias": "Беспроводная сеть", "IPAddress": "192.168.31.73", "PrefixLength": 24}
        for value in (row, [row]):
            with self.subTest(value=value):
                run.return_value = subprocess.CompletedProcess([], 0, json.dumps(value))
                self.assertEqual(discovery.get_local_ipv4_interfaces(), [
                    ("Беспроводная сеть", "192.168.31.73", 24),
                ])
        cmd = run.call_args.args[0]
        self.assertEqual(cmd[:3], ["powershell", "-NoProfile", "-Command"])
        self.assertEqual(cmd[3], "Get-NetIPAddress -AddressFamily IPv4 | Select-Object InterfaceAlias,IPAddress,PrefixLength | ConvertTo-Json")
        self.assertEqual(run.call_args.kwargs["timeout"], 5)
        self.assertEqual(run.call_args.kwargs["creationflags"], getattr(subprocess, "CREATE_NO_WINDOW", 0))

    def test_tls_ready_at_another_port_wins_over_unauthorized_legacy(self):
        device, = discovery.merge_discovered_devices(
            [UsbOrTcpDevice("192.168.31.42:5555", "unauthorized", "", "", "1")],
            [MdnsRecord("_adb-tls-connect._tcp.local.", "TV", "192.168.31.42", 41234),
             MdnsRecord("_adb-tls-pairing._tcp.local.", "Pair", "192.168.31.42", 37123)],
            ["192.168.31.42"],
        )
        self.assertEqual(device.status, DeviceStatus.READY)
        self.assertEqual((device.port, device.pairing_port), (41234, 37123))

    @patch.object(discovery.subprocess, "run")
    def test_fallback_retains_adapter_names_for_subnet_filtering(self, run):
        adapters = [
            ("vEthernet (Default Switch)", "172.29.128.1", 20),
            ("Meta", "198.18.0.1", 30),
            ("Беспроводная сеть", "192.168.31.73", 24),
        ]
        run.side_effect = [subprocess.TimeoutExpired("powershell", 5), FileNotFoundError(),
                           subprocess.CompletedProcess([], 1, "[]"), subprocess.CompletedProcess([], 0, "bad json")]
        with patch.object(discovery, "_windows_ipv4_interfaces", return_value=adapters):
            for _ in range(4):
                interfaces = discovery.get_local_ipv4_interfaces()
                self.assertEqual(interfaces, adapters)
                self.assertEqual(discovery.extract_scan_subnets(interfaces), [
                    ipaddress.IPv4Network("192.168.31.0/24"),
                ])

    @patch.object(discovery.socket, "gethostbyname_ex", return_value=("host", [], ["172.29.128.1"]))
    @patch.object(discovery.subprocess, "run", side_effect=FileNotFoundError())
    def test_fallback_without_adapter_metadata_returns_no_candidates(self, run, resolve):
        with patch.object(discovery, "_windows_ipv4_interfaces", side_effect=OSError()):
            self.assertEqual(discovery.get_local_ipv4_interfaces(), [])
        resolve.assert_not_called()

    @patch.object(discovery.socket, "create_connection")
    def test_scan_reports_open_ports_and_closes_connections(self, connect):
        connection = Mock()
        connect.side_effect = [OSError(), connection]
        networks = [ipaddress.IPv4Network("192.168.31.0/30")]
        self.assertEqual(discovery.scan_port_5555(networks, max_workers=1), ["192.168.31.2"])
        self.assertEqual([call.args[0] for call in connect.call_args_list], [
            ("192.168.31.1", 5555), ("192.168.31.2", 5555),
        ])
        self.assertTrue(all(call.kwargs["timeout"] == 0.35 for call in connect.call_args_list))
        connection.close.assert_called_once_with()

    def test_browse_resolves_ipv4_records_and_closes_resources(self):
        zeroconf = Mock()
        browser = Mock()
        zeroconf.get_service_info.return_value = SimpleNamespace(
            parsed_addresses=lambda: ["192.168.31.42", "::1"], port=6466,
        )

        def browse(zc, types, listener):
            self.assertEqual(set(types), {
                "_adb-tls-connect._tcp.local.", "_adb-tls-pairing._tcp.local.",
                "_adb._tcp.local.", "_androidtvremote2._tcp.local.",
            })
            service = "_androidtvremote2._tcp.local."
            name = f"MiTV-AFKR0.{service}"
            listener.add_service(zc, service, name)
            listener.update_service(zc, service, name)
            listener.add_service(zc, service, f"Gone.{service}")
            listener.remove_service(zc, service, f"Gone.{service}")
            return browser

        module = SimpleNamespace(Zeroconf=lambda: zeroconf, ServiceBrowser=browse)
        with patch.dict(sys.modules, {"zeroconf": module}):
            self.assertEqual(discovery.browse_mdns(0), [
                MdnsRecord("_androidtvremote2._tcp.local.", "MiTV-AFKR0", "192.168.31.42", 6466),
            ])
        browser.cancel.assert_called_once_with()
        zeroconf.close.assert_called_once_with()

    def test_browse_degrades_when_dependency_missing_or_sockets_fail(self):
        zc = Mock()
        module = SimpleNamespace(Zeroconf=lambda: zc, ServiceBrowser=Mock(side_effect=OSError()))
        with patch.dict(sys.modules, {"zeroconf": None}):
            self.assertEqual(discovery.browse_mdns(0), [])
        with patch.dict(sys.modules, {"zeroconf": module}):
            self.assertEqual(discovery.browse_mdns(0), [])
        zc.close.assert_called_once_with()

    @patch.object(discovery, "get_local_ipv4_interfaces", return_value=[("Wi-Fi", "192.168.31.73", 24)])
    @patch.object(discovery, "scan_port_5555", return_value=["192.168.31.42"])
    @patch.object(discovery, "browse_mdns", return_value=[
        MdnsRecord("_adb-tls-pairing._tcp.local.", "TV", "192.168.31.42", 37123),
    ])
    def test_discover_combines_sources_even_without_working_adb(self, browse, scan, interfaces):
        adb = Mock()
        adb.devices.side_effect = AdbError("ADB unavailable")
        for client in (None, adb):
            with self.subTest(client=client):
                device, = discovery.discover_devices(client, mdns_timeout=1.25, scan_timeout=0.1)
                self.assertEqual((device.status, device.port, device.pairing_port), (DeviceStatus.READY, 5555, 37123))
        adb.devices.assert_called_once_with()
        browse.assert_called_with(timeout=1.25)
        scan.assert_called_with([ipaddress.IPv4Network("192.168.31.0/24")], timeout=0.1)
        adb.devices.side_effect = None
        adb.devices.return_value = [UsbOrTcpDevice("usb123", "device", "Model", "", "1")]
        self.assertEqual(discovery.discover_devices(adb)[0].serial, "usb123")
