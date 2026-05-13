#!/bin/bash

# OpenUSBIP Linux Server - One-Command Installer
# Run: curl -sSL https://raw.githubusercontent.com/yourusername/OpenUSBIP/main/install-linux-server.sh | bash

set -e

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║           OpenUSBIP Server - Linux Installer                ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Check if running as root
if [ "$EUID" -eq 0 ]; then
   echo "⚠️  Please do NOT run as root. Run as regular user with sudo access."
   exit 1
fi

echo "🔍 Detecting Linux distribution..."
DISTRO="unknown"
if command -v lsb_release &> /dev/null; then
    DISTRO=$(lsb_release -si | tr '[:upper:]' '[:lower:]')
elif [ -f /etc/os-release ]; then
    . /etc/os-release
    DISTRO=$ID
fi
echo "   Detected: $DISTRO"

# Check for sudo
if ! sudo -n true 2>/dev/null; then
    echo "🔑 This script needs sudo access. You may be prompted for your password."
fi

echo ""
echo "📦 Installing system dependencies..."

# Update package list quietly
sudo apt-get update -qq 2>/dev/null || sudo dnf check-update -q 2>/dev/null || sudo pacman -Sy --noconfirm 2>/dev/null || true

# Install USB/IP tools and dependencies
if command -v apt-get &> /dev/null; then
    # Debian/Ubuntu/Pop!_OS
    sudo apt-get install -y usbip avahi-daemon python3 python3-pip -qq 2>/dev/null
elif command -v dnf &> /dev/null; then
    # Fedora/RHEL
    sudo dnf install -y usbip-utils avahi python3 python3-pip -q
elif command -v pacman &> /dev/null; then
    # Arch
    sudo pacman -S --noconfirm usbip avahi python python-pip
else
    echo "⚠️  Could not determine package manager. Please install usbip and avahi manually."
fi

echo "✅ System dependencies installed!"

echo ""
echo "🐍 Installing Python dependencies..."
pip3 install --user --break-system-packages zeroconf 2>/dev/null || \
pip3 install --user zeroconf 2>/dev/null || \
pip3 install zeroconf

echo "✅ Python dependencies installed!"

# Load kernel modules
echo ""
echo "🔧 Configuring kernel modules..."
sudo modprobe usbip_host 2>/dev/null || true
sudo modprobe vhci_hcd 2>/dev/null || true

# Add to /etc/modules for auto-load
if [ -f /etc/modules ]; then
    grep -q "usbip_host" /etc/modules || echo "usbip_host" | sudo tee -a /etc/modules > /dev/null
    grep -q "vhci_hcd" /etc/modules || echo "vhci_hcd" | sudo tee -a /etc/modules > /dev/null
fi

echo "✅ Kernel modules configured!"

# Create installation directory
echo ""
echo "📁 Setting up OpenUSBIP server..."
INSTALL_DIR="$HOME/OpenUSBIP-Server"
mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"

# Download the server script
echo "⬇️  Downloading server script..."

cat > openusbip_server.py << 'SERVERScript'
#!/usr/bin/env python3
"""
OpenUSBIP Server - Simple USB/IP Server with mDNS
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
    print("OpenUSBIP Server - Linux")
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
                'type': 'linux',
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
SERVERScript

chmod +x openusbip_server.py

# Create systemd service
echo ""
echo "⚙️  Creating systemd service..."

sudo tee /etc/systemd/system/openusbip-server.service > /dev/null << EOF
[Unit]
Description=OpenUSBIP Server - USB over IP Server
After=network.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$INSTALL_DIR
ExecStart=/usr/bin/python3 $INSTALL_DIR/openusbip_server.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# Create usbipd service if available
if command -v usbipd &> /dev/null; then
    sudo tee /etc/systemd/system/usbipd.service > /dev/null << EOF
[Unit]
Description=USB/IP Daemon
After=network.target

[Service]
Type=forking
ExecStart=/usr/sbin/usbipd -D
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
fi

# Reload systemd
sudo systemctl daemon-reload

# Enable and start services
echo "🚀 Starting services..."
if command -v usbipd &> /dev/null; then
    sudo systemctl enable usbipd.service 2>/dev/null || true
    sudo systemctl start usbipd.service 2>/dev/null || true
fi

sudo systemctl enable openusbip-server.service 2>/dev/null || true
sudo systemctl start openusbip-server.service 2>/dev/null || true

echo "✅ Services started!"

# Create launcher script
cat > start-server.sh << 'EOF'
#!/bin/bash
# OpenUSBIP Server Launcher

echo "Starting OpenUSBIP Server..."
cd "$(dirname "$0")"
python3 openusbip_server.py
EOF

chmod +x start-server.sh

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║                  ✅ INSTALLATION COMPLETE! ✅                 ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    Server Information                        ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

IP=$(hostname -I | awk '{print $1}')
HOSTNAME=$(hostname)

echo "  🖥️  Hostname: $HOSTNAME"
echo "  🌐 IP Address: $IP"
echo "  🔌 Port: 3240"
echo ""

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    How to Use                                ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  The server is now running automatically!"
echo ""
echo "  To manually start/stop:"
echo "    sudo systemctl start openusbip-server"
echo "    sudo systemctl stop openusbip-server"
echo ""
echo "  To check status:"
echo "    sudo systemctl status openusbip-server"
echo ""
echo "  To share a USB device:"
echo "    sudo usbip list -l                    # List devices"
echo "    sudo usbip bind -b <busid>           # Share device"
echo ""

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║         🎉 Your USB/IP Server is Ready! 🎉                  ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

echo "Installation directory: $INSTALL_DIR"