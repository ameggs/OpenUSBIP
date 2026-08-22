#!/usr/bin/env python3
"""
OpenUSBIP Server
Main entry point for the USB/IP server application (for Raspberry Pi/SBC)
"""

import sys
import os
import socket

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


def main(argv=None):
    """Main entry point"""
    args = parse_args(argv)
    interactive = not args.daemon

    print("OpenUSBIP Server v0.1.0")
    print("=" * 50)
    
    # Import after path is set
    from src.utils.platform_utils import (
        check_usbip_installed, get_platform, check_kernel_modules,
        load_kernel_module, get_local_ip
    )
    from src.utils.logger import setup_logger
    from src.core.server_discovery import advertise_server
    
    logger = setup_logger("OpenUSBIP-Server", level=10)  # DEBUG level
    
    # Check platform
    platform = get_platform()
    logger.info(f"Running on: {platform.value}")
    
    if platform.value == "windows":
        logger.info("Windows detected - using usbipd-win")
        print("\nWindows Server Mode:")
        print("  Please use 'usbipd' command-line tool or install usbipd-win GUI")
        print("  https://github.com/dorssel/usbipd-win")
        print("\nTo share a device:")
        print("  1. usbipd list")
        print("  2. usbipd bind --busid <BUSID>")
        print("  3. Device will be available to clients")
        return
    
    # Check USB/IP installation
    installed, msg = check_usbip_installed()
    if not installed:
        logger.error(msg)
        print(f"\nERROR: {msg}")
        sys.exit(1)
    
    logger.info(msg)
    
    # Check kernel modules (Linux only)
    if platform.value == "linux":
        loaded, missing = check_kernel_modules()
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
    print(f"Server Information:")
    print(f"  Hostname: {hostname}")
    print(f"  IP Address: {local_ip}")
    print(f"  Port: {args.port}")
    print(f"{'=' * 50}\n")
    
    # Check if usbipd is running
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
        # Detect server type
        server_type = "linux"
        if "raspberry" in hostname.lower() or "rpi" in hostname.lower():
            server_type = "raspberry-pi"
        
        zeroconf = advertise_server(
            name=hostname,
            port=args.port,
            server_type=server_type
        )
        
        if zeroconf:
            print(f"✅ Server advertised as: {hostname}")
            print(f"   Type: {server_type}")
            print(f"   Clients can auto-discover this server")
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
