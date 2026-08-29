#!/usr/bin/env python3
"""
OpenUSBIP Server
Main entry point for the USB/IP server application (for Raspberry Pi/SBC)
"""

import os
import socket
import sys

# Ensure the repository root is importable so `src.*` resolves consistently.
_ROOT = os.path.dirname(os.path.abspath(__file__))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

def parse_args(argv=None):
    """Parse command-line arguments"""
    import argparse

    parser = argparse.ArgumentParser(
        prog="openusbip_server",
        description="OpenUSBIP server: share local USB devices over the network.",
    )
    parser.add_argument(
        "--daemon",
        action="store_true",
        help="Run non-interactively (for systemd). Never prompts.",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=3240,
        help="USB/IP port to advertise (default: 3240).",
    )
    return parser.parse_args(argv)



def _detect_server_type(platform_value: str, hostname: str) -> str:
    """
    The `type` property advertised over mDNS, which the client turns into an
    icon. A Pi is called out separately from generic Linux because that is the
    distinction a user actually cares about when picking a server.
    """
    if platform_value == "windows":
        return "windows"
    lowered = hostname.lower()
    if "raspberry" in lowered or "rpi" in lowered:
        return "raspberry-pi"
    return platform_value


def _preflight_windows(port, logger):
    """
    Report whether usbipd-win is installed and actually serving.

    Nothing is started or installed here: usbipd-win runs as a Windows service
    under its own installer, and silently shelling out to `sc start` would be
    both surprising and likely to fail without elevation. The useful thing this
    can do is say precisely what is wrong.
    """
    from src.utils import windows_support
    from src.utils.platform_utils import find_command, port_is_listening

    usbipd = find_command("usbipd")
    if not usbipd:
        print(f"\nERROR: {windows_support.missing_server_message()}")
        sys.exit(1)

    logger.info(f"Found usbipd at {usbipd}")
    print(f"✅ usbipd-win found: {usbipd}")

    if port_is_listening(port):
        print(f"✅ usbipd service is accepting connections on port {port}")
        return

    # The service being installed but not listening is the common case after a
    # fresh install, and it is invisible until a client fails to connect.
    print(f"❌ Nothing is listening on port {port}")
    print("\n   The usbipd-win service is installed but not running. Start it with")
    print("   an elevated PowerShell:")
    print("\n       Start-Service usbipd")
    print("\n   Devices also have to be shared explicitly before clients see them:")
    print("\n       usbipd list")
    print("       usbipd bind --busid <BUSID>")
    print("\n   Advertising anyway, so clients can find this machine once it is up.")


def main(argv=None):
    """Main entry point"""
    args = parse_args(argv)
    interactive = not args.daemon

    print("OpenUSBIP Server v0.1.0")
    print("=" * 50)

    # Import after path is set
    from src.core.server_discovery import advertise_server
    from src.utils.logger import setup_logger
    from src.utils.platform_utils import (
        check_kernel_modules,
        check_usbip_installed,
        get_local_ip,
        get_platform,
        load_kernel_module,
    )

    logger = setup_logger("OpenUSBIP-Server", level=10)  # DEBUG level

    # Check platform
    platform = get_platform()
    logger.info(f"Running on: {platform.value}")

    if platform.value == "windows":
        # Windows already has a perfectly good USB/IP daemon in usbipd-win, so
        # this does not reimplement one. What it adds is the part usbipd-win
        # has no interest in: announcing the machine over mDNS so OpenUSBIP
        # clients discover it the same way they discover a Pi.
        _preflight_windows(args.port, logger)
    else:
        installed, msg = check_usbip_installed()
        if not installed:
            logger.error(msg)
            print(f"\nERROR: {msg}")
            sys.exit(1)

        logger.info(msg)

        if platform.value == "linux":
            loaded, missing = check_kernel_modules(role="server")
            if not loaded:
                logger.warning(f"Missing kernel modules: {', '.join(missing)}")
                print(f"\nWARNING: Missing kernel modules: {', '.join(missing)}")
                print("Attempting to load modules...")

                for module in missing:
                    if load_kernel_module(module):
                        logger.info(f"Loaded module: {module}")
                    else:
                        logger.error(f"Failed to load module: {module}")
                        print(f"ERROR: Failed to load {module}. Run: sudo modprobe {module}")

    # Get local IP
    local_ip = get_local_ip()
    hostname = socket.gethostname()

    print(f"\n{'=' * 50}")
    print("Server Information:")
    print(f"  Hostname: {hostname}")
    print(f"  IP Address: {local_ip}")
    print(f"  Port: {args.port}")
    print(f"{'=' * 50}\n")

    # Check if usbipd is running. Linux only: the Windows daemon is a service
    # and was already checked in the preflight above.
    if platform.value != "windows":
        import subprocess
        try:
            result = subprocess.run(
                ["pgrep", "-x", "usbipd"],
                capture_output=True,
                timeout=2
            )

            if result.returncode == 0:
                print("✅ usbipd service is running")
            else:
                print("❌ usbipd service is NOT running")
                print("\nTo start usbipd:")
                print("  sudo usbipd -D")
                print("\nOr create a systemd service for auto-start on boot")

                # Only prompt when a human is actually attached. Under systemd
                # (or any redirected stdin) input() raises EOFError immediately.
                if interactive and sys.stdin.isatty():
                    try:
                        response = input("\nStart usbipd now? (y/n): ")
                        if response.lower() == 'y':
                            print("Starting usbipd...")
                            subprocess.run(["sudo", "-n", "usbipd", "-D"], check=True)
                            print("✅ usbipd started")
                    except KeyboardInterrupt:
                        print("\nCancelled")
                    except Exception as e:
                        print(f"❌ Failed to start usbipd: {e}")
                else:
                    print("\nRunning non-interactively; not starting usbipd automatically.")
                    print("Enable it with: sudo systemctl enable --now usbipd")

        except Exception as e:
            logger.warning(f"Could not check usbipd status: {e}")

    # List available USB devices
    print(f"\n{'=' * 50}")
    print("Available USB Devices:")
    print(f"{'=' * 50}\n")

    from src.core.usbip_wrapper import USBIPWrapper

    usbip = USBIPWrapper()
    devices = usbip.list_local_devices()

    if devices:
        for i, device in enumerate(devices, 1):
            print(f"{i}. {device}")
    else:
        print("No USB devices found or usbipd not running")

    # Advertise server via mDNS
    print(f"\n{'=' * 50}")
    print("mDNS Advertisement:")
    print(f"{'=' * 50}\n")

    try:
        server_type = _detect_server_type(platform.value, hostname)

        zeroconf = advertise_server(
            name=hostname,
            port=args.port,
            server_type=server_type
        )

        if zeroconf:
            print(f"✅ Server advertised as: {hostname}")
            print(f"   Type: {server_type}")
            print("   Clients can auto-discover this server")
            print("\nPress Ctrl+C to stop advertising...")

            try:
                # Keep running
                import time
                while True:
                    time.sleep(1)
            except KeyboardInterrupt:
                print("\n\nStopping server advertisement...")
                zeroconf.close()
                print("✅ Server stopped")
        else:
            print("❌ Failed to advertise server (zeroconf not available)")
            print("   Clients will need to add this server manually")
            print(f"   IP: {local_ip}")
            print(f"   Port: {args.port}")

    except KeyboardInterrupt:
        print("\n\nExiting...")
    except Exception as e:
        logger.error(f"Error: {e}")
        print(f"\n❌ Error: {e}")


if __name__ == "__main__":
    main()
