"""
Platform-specific utilities for OpenUSBIP
Handles differences between Windows, Linux, and other platforms
"""

import os
import platform
import shutil
import subprocess
from enum import Enum


class Platform(Enum):
    """Supported platforms"""
    WINDOWS = "windows"
    LINUX = "linux"
    MACOS = "macos"
    UNKNOWN = "unknown"


def get_platform() -> Platform:
    """Detect current platform"""
    system = platform.system().lower()
    if system == "windows":
        return Platform.WINDOWS
    elif system == "linux":
        return Platform.LINUX
    elif system == "darwin":
        return Platform.MACOS
    return Platform.UNKNOWN


def is_admin() -> bool:
    """Check if running with administrator/root privileges"""
    current_platform = get_platform()

    if current_platform == Platform.WINDOWS:
        try:
            import ctypes  # type: ignore
            return ctypes.windll.shell32.IsUserAnAdmin() != 0  # type: ignore
        except Exception:
            return False
    else:  # Linux/Unix
        import os
        return os.geteuid() == 0


def find_command(command: str) -> str | None:
    """Find full path to a command"""
    # Try standard PATH first
    result = shutil.which(command)
    if result:
        return result

    # Also check /usr/sbin (common on Debian/Raspberry Pi)
    sbin_path = f"/usr/sbin/{command}"
    if os.path.exists(sbin_path):
        return sbin_path

    return None


def check_usbip_installed() -> tuple[bool, str]:
    """
    Check if USB/IP tools are installed
    Returns: (is_installed, message)
    """
    current_platform = get_platform()

    if current_platform == Platform.WINDOWS:
        # Check for usbipd-win (server) or usbip.exe (client)
        usbipd = find_command("usbipd")
        usbip = find_command("usbip")

        if usbipd or usbip:
            return True, "USB/IP tools found"
        return False, "Please install usbipd-win from https://github.com/dorssel/usbipd-win"

    elif current_platform == Platform.LINUX:
        # Check for usbip command
        usbip = find_command("usbip")
        if usbip:
            return True, "USB/IP tools found"
        return False, "Please install usbip tools: sudo apt install linux-tools-generic (Ubuntu/Debian) or sudo dnf install usbip (Fedora)"

    return False, f"Platform {current_platform.value} not fully supported yet"


def check_kernel_modules() -> tuple[bool, list[str]]:
    """
    Check if required kernel modules are loaded (Linux only)
    Returns: (all_loaded, missing_modules)
    """
    if get_platform() != Platform.LINUX:
        return True, []

    required_modules = ["vhci_hcd", "usbip_host"]
    missing = []

    try:
        with open("/proc/modules") as f:
            loaded_modules = f.read()

        for module in required_modules:
            if module not in loaded_modules:
                missing.append(module)
    except Exception:
        return False, required_modules

    return len(missing) == 0, missing


def load_kernel_module(module: str) -> bool:
    """Load a kernel module (requires root)"""
    if get_platform() != Platform.LINUX:
        return False

    cmd = ["modprobe", module]
    if not is_admin():
        cmd = ["sudo", "-n"] + cmd

    try:
        subprocess.run(
            cmd,
            check=True,
            capture_output=True,
            timeout=5,
            stdin=subprocess.DEVNULL,
        )
        return True
    except Exception:
        return False


def run_command(cmd: list[str], timeout: int = 10, require_admin: bool = False) -> tuple[int, str, str]:
    """
    Run a system command
    Returns: (return_code, stdout, stderr)
    """
    used_sudo = False
    if require_admin and not is_admin():
        # Prepend sudo on Linux. -n is essential: stdin is not a terminal here
        # (capture_output pipes it), so an interactive password prompt would
        # block until the timeout rather than ever being answerable.
        if get_platform() == Platform.LINUX:
            cmd = ["sudo", "-n"] + cmd
            used_sudo = True

    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
            stdin=subprocess.DEVNULL,
        )
    except subprocess.TimeoutExpired:
        return -1, "", "Command timed out"
    except FileNotFoundError:
        return -1, "", f"Command not found: {cmd[0]}"
    except Exception as e:
        return -1, "", str(e)

    # Turn sudo's own refusal into something a user can act on, rather than
    # surfacing the raw "a password is required" in a dialog.
    if used_sudo and result.returncode != 0 and "password is required" in result.stderr:
        return result.returncode, result.stdout, (
            "Root privileges are required for this operation, but sudo asked for "
            "a password and none could be supplied.\n\n"
            "Run the installer to configure passwordless sudo for usbip, or add:\n"
            f"  {os.getenv('USER', 'youruser')} ALL=(ALL) NOPASSWD: {find_command('usbip')}\n"
            "to /etc/sudoers.d/usbip (validate with: sudo visudo -c -f /etc/sudoers.d/usbip)"
        )

    return result.returncode, result.stdout, result.stderr


def get_local_ip() -> str:
    """Get local IP address"""
    import socket
    try:
        # Create a socket to determine local IP
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


if __name__ == "__main__":
    # Self-test
    print(f"Platform: {get_platform().value}")
    print(f"Admin: {is_admin()}")
    print(f"Local IP: {get_local_ip()}")

    installed, msg = check_usbip_installed()
    print(f"USB/IP installed: {installed} - {msg}")

    if get_platform() == Platform.LINUX:
        loaded, missing = check_kernel_modules()
        print(f"Kernel modules loaded: {loaded}")
        if missing:
            print(f"Missing modules: {', '.join(missing)}")
