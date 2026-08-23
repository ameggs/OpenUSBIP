"""
Server discovery using mDNS/Avahi
Optimized for discovering Raspberry Pi and SBC USB/IP servers on the network
"""

import socket
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime

try:
    from zeroconf import ServiceBrowser, ServiceInfo, ServiceListener, Zeroconf  # type: ignore
    HAS_ZEROCONF = True
except ImportError:
    HAS_ZEROCONF = False
    ServiceListener = object  # type: ignore
    ServiceBrowser = None  # type: ignore
    Zeroconf = None  # type: ignore
    ServiceInfo = None  # type: ignore

import os
import sys

# Ensure the repository root is importable so `src.*` resolves consistently,
# whether this module is imported by an entry point or run directly.
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from src.utils.logger import setup_logger
from src.utils.platform_utils import get_local_ip

logger = setup_logger(__name__)


@dataclass
class USBIPServer:
    """USB/IP server information"""
    name: str
    hostname: str
    ip_address: str
    port: int = 3240
    device_count: int = 0
    last_seen: datetime = field(default_factory=datetime.now)
    is_online: bool = True
    server_type: str = "unknown"  # raspberry-pi, linux, windows, etc.

    def __str__(self) -> str:
        return f"{self.name} ({self.ip_address}:{self.port})"

    @property
    def display_name(self) -> str:
        """Human-readable server name"""
        type_str = f" [{self.server_type}]" if self.server_type != "unknown" else ""
        return f"{self.name}{type_str} - {self.ip_address}"

    def to_dict(self) -> dict:
        """Convert to dictionary for serialization"""
        return {
            'name': self.name,
            'hostname': self.hostname,
            'ip_address': self.ip_address,
            'port': self.port,
            'device_count': self.device_count,
            'server_type': self.server_type
        }

    @classmethod
    def from_dict(cls, data: dict) -> 'USBIPServer':
        """Create from dictionary"""
        return cls(
            name=data.get('name', 'Unknown'),
            hostname=data.get('hostname', ''),
            ip_address=data['ip_address'],
            port=data.get('port', 3240),
            device_count=data.get('device_count', 0),
            server_type=data.get('server_type', 'unknown')
        )


class USBIPServiceListener(ServiceListener):
    """Listener for USB/IP mDNS services"""

    def __init__(self, callback: Callable[[USBIPServer], None]):
        self.callback = callback
        self.servers: dict[str, USBIPServer] = {}

    def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        """Called when a new service is discovered"""
        info = zc.get_service_info(type_, name)
        if info:
            self._process_service(info, is_new=True)

    def update_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        """Called when a service is updated"""
        info = zc.get_service_info(type_, name)
        if info:
            self._process_service(info, is_new=False)

    def remove_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        """Called when a service is removed"""
        if name in self.servers:
            server = self.servers[name]
            server.is_online = False
            logger.info(f"Server removed: {server.name}")
            self.callback(server)

    def _process_service(self, info: ServiceInfo, is_new: bool) -> None:
        """Process discovered service information"""
        if not info.addresses:
            return

        # Get IP address
        ip_address = socket.inet_ntoa(info.addresses[0])

        # Get port
        port = info.port or 3240

        # Get server name
        server_name = info.server.rstrip('.') if info.server else info.name

        # Detect server type from properties
        server_type = "unknown"
        if info.properties:
            props = {k.decode('utf-8'): v.decode('utf-8') for k, v in info.properties.items()}
            server_type = props.get('type', 'unknown')

            # Try to detect Raspberry Pi
            if 'raspberry' in server_name.lower() or 'rpi' in server_name.lower():
                server_type = "raspberry-pi"
            elif 'linux' in props.get('os', '').lower():
                server_type = "linux"
            elif 'windows' in props.get('os', '').lower():
                server_type = "windows"

        # Create or update server
        server = USBIPServer(
            name=server_name,
            hostname=info.server.rstrip('.') if info.server else '',
            ip_address=ip_address,
            port=port,
            server_type=server_type,
            is_online=True
        )

        self.servers[info.name] = server

        if is_new:
            logger.info(f"New server discovered: {server}")
        else:
            logger.debug(f"Server updated: {server}")

        self.callback(server)


class ServerDiscovery:
    """
    USB/IP server discovery service
    Discovers Raspberry Pi and other SBC servers on the network
    """

    SERVICE_TYPE = "_usbip._tcp.local."

    def __init__(self):
        self.zeroconf: Zeroconf | None = None
        self.browser: ServiceBrowser | None = None
        self.listener: USBIPServiceListener | None = None
        self.is_running = False
        self.servers: dict[str, USBIPServer] = {}
        self.callbacks: list[Callable[[USBIPServer], None]] = []
        self._lock = threading.Lock()

    def start(self, callback: Callable[[USBIPServer], None] | None = None) -> bool:
        """
        Start server discovery

        Args:
            callback: Function to call when servers are discovered/updated

        Returns:
            True if started successfully
        """
        if not HAS_ZEROCONF:
            logger.warning("zeroconf library not available, auto-discovery disabled")
            return False

        if self.is_running:
            logger.warning("Discovery already running")
            return True

        if callback:
            self.callbacks.append(callback)

        try:
            self.zeroconf = Zeroconf()
            self.listener = USBIPServiceListener(self._on_server_update)
            self.browser = ServiceBrowser(self.zeroconf, self.SERVICE_TYPE, self.listener)
            self.is_running = True
            logger.info("Server discovery started")
            return True
        except Exception as e:
            logger.error(f"Failed to start discovery: {e}")
            return False

    def stop(self) -> None:
        """Stop server discovery"""
        if not self.is_running:
            return

        try:
            if self.browser:
                self.browser.cancel()
            if self.zeroconf:
                self.zeroconf.close()
            self.is_running = False
            logger.info("Server discovery stopped")
        except Exception as e:
            logger.error(f"Error stopping discovery: {e}")

    def _on_server_update(self, server: USBIPServer) -> None:
        """Internal callback when server is discovered/updated"""
        with self._lock:
            self.servers[server.ip_address] = server

        # Notify all registered callbacks
        for callback in self.callbacks:
            try:
                callback(server)
            except Exception as e:
                logger.error(f"Error in discovery callback: {e}")

    def get_servers(self) -> list[USBIPServer]:
        """Get list of discovered servers"""
        with self._lock:
            return list(self.servers.values())

    def add_manual_server(self, ip_address: str, port: int = 3240, name: str | None = None) -> USBIPServer:
        """
        Manually add a server (for servers without mDNS)

        Args:
            ip_address: Server IP address
            port: Server port (default 3240)
            name: Optional server name

        Returns:
            Created server object
        """
        if not name:
            name = f"Server {ip_address}"

        server = USBIPServer(
            name=name,
            hostname=ip_address,
            ip_address=ip_address,
            port=port,
            server_type="manual"
        )

        with self._lock:
            self.servers[ip_address] = server

        logger.info(f"Manually added server: {server}")

        # Notify callbacks
        self._on_server_update(server)

        return server

    def remove_server(self, ip_address: str) -> bool:
        """
        Remove a server from the list

        Args:
            ip_address: Server IP address

        Returns:
            True if removed
        """
        with self._lock:
            if ip_address in self.servers:
                del self.servers[ip_address]
                logger.info(f"Removed server: {ip_address}")
                return True
        return False

    def check_server_online(self, server: USBIPServer, timeout: float = 2.0) -> bool:
        """
        Check if a server is online by attempting to connect

        Args:
            server: Server to check
            timeout: Connection timeout in seconds

        Returns:
            True if server is reachable
        """
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(timeout)
            result = sock.connect_ex((server.ip_address, server.port))
            sock.close()

            is_online = (result == 0)
            server.is_online = is_online
            server.last_seen = datetime.now()

            return is_online
        except Exception as e:
            logger.debug(f"Error checking server {server.ip_address}: {e}")
            server.is_online = False
            return False


def advertise_server(name: str, port: int = 3240, server_type: str = "linux") -> Zeroconf | None:
    """
    Advertise this machine as a USB/IP server using mDNS
    For use on Raspberry Pi / SBC servers

    Args:
        name: Server name to advertise
        port: USB/IP port (default 3240)
        server_type: Server type (raspberry-pi, linux, windows)

    Returns:
        Zeroconf instance (keep alive) or None if failed
    """
    if not HAS_ZEROCONF:
        logger.error("zeroconf library not available")
        return None

    try:
        local_ip = get_local_ip()

        # Create service info
        info = ServiceInfo(
            ServerDiscovery.SERVICE_TYPE,
            f"{name}.{ServerDiscovery.SERVICE_TYPE}",
            addresses=[socket.inet_aton(local_ip)],
            port=port,
            properties={
                'type': server_type.encode('utf-8'),
                'version': b'1.0'
            },
            server=f"{name}.local."
        )

        # Register service
        zeroconf = Zeroconf()
        zeroconf.register_service(info)

        logger.info(f"Advertising USB/IP server: {name} at {local_ip}:{port}")
        return zeroconf

    except Exception as e:
        logger.error(f"Failed to advertise server: {e}")
        return None


if __name__ == "__main__":
    # Test discovery
    print("Starting USB/IP server discovery...")

    def on_server_found(server: USBIPServer):
        print(f"Found: {server.display_name}")

    discovery = ServerDiscovery()
    if discovery.start(on_server_found):
        print("Discovery started. Waiting for servers...")
        print("Press Ctrl+C to stop")

        try:
            while True:
                time.sleep(1)
                servers = discovery.get_servers()
                print(f"\rServers found: {len(servers)}", end='', flush=True)
        except KeyboardInterrupt:
            print("\nStopping...")
            discovery.stop()
    else:
        print("Failed to start discovery")
        print("\nTrying manual server addition...")
        server = discovery.add_manual_server("192.168.1.100", name="Test Server")
        print(f"Added: {server}")
