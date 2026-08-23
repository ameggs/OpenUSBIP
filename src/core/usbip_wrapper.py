"""
USB/IP command-line wrapper
Handles communication with usbip/usbipd tools
"""

import os
import re
import sys
from dataclasses import dataclass
from enum import Enum

# Ensure the repository root is importable so `src.*` resolves consistently,
# whether this module is imported by an entry point or run directly.
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from src.utils.logger import setup_logger
from src.utils.platform_utils import Platform, find_command, get_platform, run_command

logger = setup_logger(__name__)

# The USB/IP IANA-registered default port.
DEFAULT_USBIP_PORT = 3240


class DeviceState(Enum):
    """USB device state"""
    AVAILABLE = "available"
    ATTACHED = "attached"
    BUSY = "busy"
    ERROR = "error"


@dataclass
class USBDevice:
    """USB device information"""
    busid: str
    vendor_id: str
    product_id: str
    description: str
    state: DeviceState
    speed: str = "unknown"

    def __str__(self) -> str:
        return f"{self.busid}: {self.description} ({self.vendor_id}:{self.product_id})"

    @property
    def display_name(self) -> str:
        """Human-readable device name"""
        return f"{self.description} [{self.busid}]"


class USBIPWrapper:
    """Wrapper for USB/IP command-line tools"""

    def __init__(self):
        self.platform = get_platform()
        self._usbip_cmd = self._find_usbip_command()
        self._usbipd_cmd = self._find_usbipd_command()

    def _find_usbip_command(self) -> str | None:
        """Find usbip command"""
        if self.platform == Platform.WINDOWS:
            # Windows may have usbip.exe in different locations
            return find_command("usbip") or find_command("usbip.exe")
        else:
            return find_command("usbip")

    def _find_usbipd_command(self) -> str | None:
        """Find usbipd command"""
        if self.platform == Platform.WINDOWS:
            return find_command("usbipd") or find_command("usbipd.exe")
        else:
            return find_command("usbipd")

    def _base_cmd(self, port: int = DEFAULT_USBIP_PORT) -> list[str]:
        """
        Build the usbip invocation prefix, including the global --tcp-port
        option when a non-default port is requested.
        """
        cmd = [self._usbip_cmd]  # type: ignore[list-item]
        if port and port != DEFAULT_USBIP_PORT:
            cmd += ["--tcp-port", str(port)]
        return cmd

    def is_available(self) -> bool:
        """Check if USB/IP tools are available"""
        return self._usbip_cmd is not None

    def list_local_devices(self) -> list[USBDevice]:
        """
        List USB devices on local machine (server mode)
        """
        if self.platform == Platform.WINDOWS:
            if not self._usbipd_cmd:
                logger.error("usbipd command not found")
                return []
            return self._list_local_devices_windows()

        # Linux lists local devices with `usbip list -l`; usbipd is the daemon
        # and is not required just to enumerate.
        if not self._usbip_cmd:
            logger.error("usbip command not found")
            return []
        return self._list_local_devices_linux()

    def _list_local_devices_windows(self) -> list[USBDevice]:
        """List local devices on Windows using usbipd"""
        devices = []

        # Run: usbipd list
        returncode, stdout, stderr = run_command([self._usbipd_cmd, "list"])  # type: ignore

        if returncode != 0:
            logger.error(f"Failed to list devices: {stderr}")
            return devices

        # Parse output
        # Example format:
        # BUSID  VID:PID    DEVICE                                            STATE
        # 1-1    046d:c52b  Logitech USB Input Device                         Not shared
        # 1-2    0781:5583  SanDisk USB Device                                Shared

        for line in stdout.split('\n')[1:]:  # Skip header
            line = line.strip()
            if not line:
                continue

            parts = line.split(maxsplit=3)
            if len(parts) < 4:
                continue

            busid = parts[0]
            vid_pid = parts[1]
            description = parts[2]
            state_str = parts[3] if len(parts) > 3 else "unknown"

            # Parse VID:PID
            if ':' in vid_pid:
                vendor_id, product_id = vid_pid.split(':', 1)
            else:
                vendor_id, product_id = "0000", "0000"

            # Determine state
            if "shared" in state_str.lower():
                state = DeviceState.AVAILABLE
            elif "attached" in state_str.lower():
                state = DeviceState.ATTACHED
            else:
                state = DeviceState.BUSY

            devices.append(USBDevice(
                busid=busid,
                vendor_id=vendor_id,
                product_id=product_id,
                description=description,
                state=state
            ))

        return devices

    def _list_local_devices_linux(self) -> list[USBDevice]:
        """List local devices on Linux using usbip"""
        devices = []

        # Run: usbip list -l
        returncode, stdout, stderr = run_command([self._usbip_cmd, "list", "-l"])  # type: ignore

        if returncode != 0:
            logger.error(f"Failed to list devices: {stderr}")
            return devices

        # Parse output
        # Example format:
        #  - busid 1-1 (046d:c52b)
        #    Logitech, Inc. : Unifying Receiver (046d:c52b)

        current_device = None
        for line in stdout.split('\n'):
            line = line.strip()

            # Match busid line
            busid_match = re.match(r'- busid (\S+) \(([0-9a-f]{4}):([0-9a-f]{4})\)', line, re.IGNORECASE)
            if busid_match:
                if current_device:
                    devices.append(current_device)

                busid = busid_match.group(1)
                vendor_id = busid_match.group(2)
                product_id = busid_match.group(3)

                current_device = USBDevice(
                    busid=busid,
                    vendor_id=vendor_id,
                    product_id=product_id,
                    description="Unknown Device",
                    state=DeviceState.AVAILABLE
                )

            # The line after a busid carries the human-readable name. Only take
            # the first one, and strip the trailing "(vid:pid)" so it is not
            # repeated by __str__, which appends the ids itself.
            elif (
                current_device
                and ':' in line
                and current_device.description == "Unknown Device"
            ):
                current_device.description = re.sub(
                    r'\s*\([0-9a-f]{4}:[0-9a-f]{4}\)\s*$', '', line, flags=re.IGNORECASE
                ).strip()

        if current_device:
            devices.append(current_device)

        return devices

    def list_remote_devices(self, host: str, port: int = DEFAULT_USBIP_PORT) -> list[USBDevice]:
        """
        List USB devices on remote server

        Args:
            host: Remote server IP or hostname
            port: USB/IP port (default 3240)
        """
        if not self._usbip_cmd:
            logger.error("usbip command not found")
            return []

        devices = []

        # Run: usbip [--tcp-port <port>] list -r <host>
        # --tcp-port is a global option and must precede the subcommand.
        cmd = self._base_cmd(port) + ["list", "-r", host]
        returncode, stdout, stderr = run_command(cmd, timeout=15)

        if returncode != 0:
            logger.error(f"Failed to list remote devices on {host}: {stderr}")
            return devices

        # Parse output (similar format on all platforms)
        # Exportable USB devices
        # ======================
        #  - 1-1: Logitech, Inc. : Unifying Receiver (046d:c52b)
        #         : /sys/devices/pci0000:00/0000:00:14.0/usb1/1-1
        #         : (Defined at Interface level) (00/00/00)

        current_device = None
        for line in stdout.split('\n'):
            # Match device line: "      busid: description (vid:pid)"
            # Can have leading whitespace
            device_match = re.match(r'\s+(\S+):\s+(.+?)\s+\(([0-9a-f]{4}):([0-9a-f]{4})\)', line, re.IGNORECASE)
            if device_match:
                if current_device:
                    devices.append(current_device)

                busid = device_match.group(1)
                description = device_match.group(2)
                vendor_id = device_match.group(3)
                product_id = device_match.group(4)

                current_device = USBDevice(
                    busid=busid,
                    vendor_id=vendor_id,
                    product_id=product_id,
                    description=description,
                    state=DeviceState.AVAILABLE
                )

        if current_device:
            devices.append(current_device)

        logger.info(f"Found {len(devices)} devices on {host}")
        return devices

    def attach_device(self, host: str, busid: str, port: int = DEFAULT_USBIP_PORT) -> tuple[bool, str]:
        """
        Attach remote USB device

        Args:
            host: Remote server IP or hostname
            busid: Device bus ID
            port: USB/IP port (default 3240)

        Returns:
            (success, message)
        """
        if not self._usbip_cmd:
            return False, "usbip command not found"

        # Run: usbip [--tcp-port <port>] attach -r <host> -b <busid>
        cmd = self._base_cmd(port) + ["attach", "-r", host, "-b", busid]
        returncode, stdout, stderr = run_command(cmd, timeout=10, require_admin=True)

        if returncode == 0:
            logger.info(f"Attached device {busid} from {host}")
            return True, f"Successfully attached {busid}"
        else:
            error_msg = stderr or stdout or "Unknown error"
            logger.error(f"Failed to attach {busid}: {error_msg}")
            return False, error_msg

    def detach_device(self, port_num: str) -> tuple[bool, str]:
        """
        Detach USB device

        Args:
            port_num: Port number (from list_attached_devices)

        Returns:
            (success, message)
        """
        if not self._usbip_cmd:
            return False, "usbip command not found"

        # Run: usbip detach -p <port>
        cmd = [self._usbip_cmd, "detach", "-p", port_num]  # local; no --tcp-port
        returncode, stdout, stderr = run_command(cmd, timeout=10, require_admin=True)

        if returncode == 0:
            logger.info(f"Detached device on port {port_num}")
            return True, f"Successfully detached port {port_num}"
        else:
            error_msg = stderr or stdout or "Unknown error"
            logger.error(f"Failed to detach port {port_num}: {error_msg}")
            return False, error_msg

    def list_attached_devices(self) -> list[dict[str, str]]:
        """
        List currently attached devices (client mode)

        Returns:
            List of dicts with keys: port, busid, remote_host, device
        """
        if not self._usbip_cmd:
            logger.error("usbip command not found")
            return []

        devices = []

        # Run: usbip port
        # `usbip port` reads the vhci driver state and needs root: without it
        # the tool prints "libusbip: error: fopen" to stderr and produces empty
        # output, so this returned [] and the GUI's attached-device table was
        # permanently empty -- leaving no way to detach anything from the UI.
        cmd = [self._usbip_cmd, "port"]  # type: ignore
        returncode, stdout, stderr = run_command(cmd, timeout=10, require_admin=True)

        if returncode != 0:
            logger.error(f"Failed to list attached devices: {stderr}")
            return devices

        # Parse output
        # Example:
        # Port 00: <Port in Use> at High Speed(480Mbps)
        #        Logitech, Inc. : Unifying Receiver (046d:c52b)
        #        1-1 -> usbip://192.168.1.100:3240/1-1
        #            -> remote bus/dev 001/002

        current_port = None
        for line in stdout.split('\n'):
            # Match port line
            port_match = re.match(r'Port (\d+):', line)
            if port_match:
                current_port = port_match.group(1)
                continue

            # Match remote connection line
            if current_port and '->' in line and 'usbip://' in line:
                # Extract busid and host
                remote_match = re.search(r'(\S+)\s+->\s+usbip://([^:]+):(\d+)/(\S+)', line)
                if remote_match:
                    local_busid = remote_match.group(1)
                    remote_host = remote_match.group(2)
                    remote_port = remote_match.group(3)
                    remote_busid = remote_match.group(4)

                    devices.append({
                        'port': current_port,
                        'busid': remote_busid,
                        'local_busid': local_busid,
                        'remote_host': remote_host,
                        'remote_port': remote_port,
                        'device': f"Device on port {current_port}"
                    })

        return devices

    def bind_device(self, busid: str) -> tuple[bool, str]:
        """
        Bind device for sharing (server mode, Windows only)

        Args:
            busid: Device bus ID

        Returns:
            (success, message)
        """
        if self.platform != Platform.WINDOWS or not self._usbipd_cmd:
            return False, "bind command only available on Windows with usbipd-win"

        # Run: usbipd bind --busid <busid>
        cmd = [self._usbipd_cmd, "bind", "--busid", busid]  # type: ignore
        returncode, stdout, stderr = run_command(cmd, timeout=10, require_admin=True)

        if returncode == 0:
            logger.info(f"Bound device {busid}")
            return True, f"Successfully bound {busid}"
        else:
            error_msg = stderr or stdout or "Unknown error"
            logger.error(f"Failed to bind {busid}: {error_msg}")
            return False, error_msg

    def unbind_device(self, busid: str) -> tuple[bool, str]:
        """
        Unbind device (server mode, Windows only)

        Args:
            busid: Device bus ID

        Returns:
            (success, message)
        """
        if self.platform != Platform.WINDOWS or not self._usbipd_cmd:
            return False, "unbind command only available on Windows with usbipd-win"

        # Run: usbipd unbind --busid <busid>
        cmd = [self._usbipd_cmd, "unbind", "--busid", busid]  # type: ignore
        returncode, stdout, stderr = run_command(cmd, timeout=10, require_admin=True)

        if returncode == 0:
            logger.info(f"Unbound device {busid}")
            return True, f"Successfully unbound {busid}"
        else:
            error_msg = stderr or stdout or "Unknown error"
            logger.error(f"Failed to unbind {busid}: {error_msg}")
            return False, error_msg


if __name__ == "__main__":
    # Test the wrapper
    wrapper = USBIPWrapper()
    print(f"USB/IP available: {wrapper.is_available()}")

    if wrapper.is_available():
        print("\nLocal devices:")
        for device in wrapper.list_local_devices():
            print(f"  {device}")

        print("\nAttached devices:")
        for device in wrapper.list_attached_devices():
            print(f"  {device}")
