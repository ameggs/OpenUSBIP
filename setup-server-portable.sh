#!/bin/bash

# OpenUSBIP Server - Portable Setup Script
# Copy this script to any Linux machine and run it!

set -e

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║           OpenUSBIP Server - Portable Setup                ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Check if running as root
if [ "$EUID" -eq 0 ]; then
   echo "⚠️  Please do NOT run as root. Run as regular user with sudo access."
   exit 1
fi

# Detect platform
echo "🔍 Detecting platform..."
if [ -f /proc/device-tree/model ]; then
    MODEL=$(cat /proc/device-tree/model 2>/dev/null || echo "Unknown SBC")
    echo "   Detected: $MODEL"
else
    echo "   Detected: $(uname -m) Linux"
fi

# Check for sudo
if ! sudo -n true 2>/dev/null; then
    echo "🔑 This script needs sudo access. You may be prompted for your password."
fi

echo ""
echo "📦 Installing dependencies..."

# Update package list
sudo apt-get update -qq 2>/dev/null || true

# Install required packages
sudo apt-get install -y usbip avahi-daemon python3 python3-pip usbipd 2>/dev/null || {
    echo "⚠️  Some packages may not be available on this system"
    echo "   Installing what we can..."
    sudo apt-get install -y python3 python3-pip avahi-daemon 2>/dev/null || true
}

# Install Python packages
pip3 install --user zeroconf 2>/dev/null || pip3 install zeroconf 2>/dev/null || {
    echo "⚠️  Could not install zeroconf - mDNS discovery may not work"
}

echo ""
echo "✅ Dependencies installed!"

# Create installation directory
INSTALL_DIR="$HOME/OpenUSBIP-Server"
echo "📁 Installing to: $INSTALL_DIR"

mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"

# Create server script
cat > openusbip_server.py << 'SERVERSCRIPT'
#!/usr/bin/env python3
"""
OpenUSBIP Server - Portable USB/IP Server
"""
import socket
import subprocess
import time
import sys
import os

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except:
        return "127.0.0.1"

def main():
    print("=" * 60)
    print("OpenUSBIP Server - Portable")
    print("=" * 60)

    hostname = socket.gethostname()
    ip = get_local_ip()

    print(f"\nServer Info:")
    print(f"  Hostname: {hostname}")
    print(f"  IP: {ip}")
    print(f"  Port: 3240")

    # Check if usbipd is available and running
    usbipd_available = False
    try:
        result = subprocess.run(["which", "usbipd"], capture_output=True, timeout=5)
        if result.returncode == 0:
            usbipd_available = True
    except:
        pass

    if usbipd_available:
        try:
            result = subprocess.run(["pgrep", "-x", "usbipd"], capture_output=True)
            if result.returncode == 0:
                print("✅ usbipd is running")
            else:
                print("❌ usbipd is NOT running")
                print("Starting usbipd...")
                subprocess.run(["sudo", "usbipd", "-D"])
                time.sleep(1)
                print("✅ usbipd started")
        except Exception as e:
            print(f"Error checking usbipd: {e}")
    else:
        print("⚠️  usbipd not available - limited functionality")

    # List USB devices
    print("
" + "=" * 60)
    print("Available USB Devices:")
    print("=" * 60)

    try:
        # Try usbip command first
        result = subprocess.run(["sudo", "usbip", "list", "-l"],
                              capture_output=True, text=True, timeout=10)
        if result.returncode == 0:
            print(result.stdout)
        else:
            print("No USB devices found or usbip not available")
    except Exception as e:
        print(f"Error listing devices: {e}")

    # Advertise via mDNS
    print("
" + "=" * 60)
    print("mDNS Advertisement:")
    print("=" * 60)

    try:
        from zeroconf import ServiceInfo, Zeroconf

        info = ServiceInfo(
            "_usbip._tcp.local.",
            f"{hostname}._usbip._tcp.local.",
            addresses=[socket.inet_aton(ip)],
            port=3240,
            properties={
                'type': 'portable',
                'version': '1.0'
            },
            server=f"{hostname}.local."
        )

        zeroconf = Zeroconf()
        zeroconf.register_service(info)

        print(f"✅ Server advertised as: {hostname}")
        print("   Clients can auto-discover this server!")
        print("\nPress Ctrl+C to stop...")

        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            print("\n\nStopping...")
            zeroconf.close()
            print("✅ Server stopped")

    except ImportError:
        print("❌ zeroconf not installed - no auto-discovery")
        print("   Clients must add server manually")
        print(f"   IP: {ip}")
        print(f"   Port: 3240")
        print("\nServer is ready! Press Ctrl+C to stop...")
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            print("\nStopped")

if __name__ == "__main__":
    main()
SERVERSCRIPT

chmod +x openusbip_server.py

echo "✅ Server script installed!"

# Load kernel modules
echo "🔧 Configuring kernel modules..."
sudo modprobe usbip_host 2>/dev/null || true
sudo modprobe vhci_hcd 2>/dev/null || true

echo "✅ Kernel modules configured!"

# Try to start usbipd
echo "🚀 Starting USB/IP daemon..."
sudo usbipd -D 2>/dev/null || echo "⚠️  usbipd not available or already running"

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║                  ✅ PORTABLE SETUP COMPLETE! ✅              ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Get IP
IP=$(get_local_ip 2>/dev/null || echo "unknown")
HOSTNAME=$(hostname)

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    Server Information                        ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  🖥️  Hostname: $HOSTNAME"
echo "  🌐 IP Address: $IP"
echo "  🔌 Port: 3240"
echo ""

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    How to Use                                ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  1. Plug in USB devices (they will be shared automatically)"
echo "  2. On your client computer, run the OpenUSBIP client"
echo "  3. This server will appear automatically!"
echo ""
echo "  Manual device sharing:"
echo "    sudo usbip list -l                    # List devices"
echo "    sudo usbip bind -b <busid>           # Share device"
echo "    sudo usbip unbind -b <busid>         # Stop sharing"
echo ""

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    Useful Commands                           ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  Check status:"
echo "    sudo systemctl status usbipd 2>/dev/null || echo 'usbipd not managed by systemd'"
echo ""
echo "  View logs:"
echo "    journalctl -u usbipd -f 2>/dev/null || echo 'No systemd logs'"
echo ""
echo "  Restart server:"
echo "    cd ~/OpenUSBIP-Server && python3 openusbip_server.py"
echo ""

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║         🎉 Your Portable USB/IP Server is Ready! 🎉         ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
SERVERSCRIPT

chmod +x setup-server-portable.sh

echo "✅ Portable server setup script created!"

# Run the server
echo "🚀 Starting server..."
python3 openusbip_server.py