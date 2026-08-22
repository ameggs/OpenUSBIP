#!/bin/bash
#
# OpenUSBIP Server - One-Command Installer
# For Raspberry Pi and Linux SBCs
#
# Usage: curl -sSL https://raw.githubusercontent.com/ameggs/OpenUSBIP/main/install-server.sh | bash
# Or: wget -qO- https://raw.githubusercontent.com/ameggs/OpenUSBIP/main/install-server.sh | bash
#

set -e

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║           OpenUSBIP Server - Easy Installer                 ║"
echo "║                                                              ║"
echo "║     Turn your Raspberry Pi into a USB/IP server!            ║"
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
    MODEL=$(cat /proc/device-tree/model)
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
echo ""

# Update package list (quietly)
echo "   Updating package list..."
sudo apt-get update -qq

# Install required packages
echo "   Installing USB/IP tools..."
sudo apt-get install -y usbip avahi-daemon python3 python3-pip > /dev/null 2>&1

echo "   Installing Python packages..."
pip3 install --user --break-system-packages zeroconf > /dev/null 2>&1 || pip3 install --user zeroconf > /dev/null 2>&1

echo ""
echo "✅ Dependencies installed!"
echo ""

# Create installation directory
INSTALL_DIR="$HOME/OpenUSBIP"
echo "📁 Installing to: $INSTALL_DIR"

mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"

# Install the real application.
#
# This previously wrote a simplified inline copy of the server via a heredoc,
# which then drifted from the actual openusbip_server.py in the repository --
# the systemd unit ran the inline copy, so fixes to the real one never took
# effect. Install the repository itself instead.
echo "⬇️  Installing OpenUSBIP..."

# Work out whether we are running from a checkout or via curl | bash.
SCRIPT_SRC=""
if [ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ]; then
    CANDIDATE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    if [ -f "$CANDIDATE/openusbip_server.py" ] && [ -d "$CANDIDATE/src" ]; then
        SCRIPT_SRC="$CANDIDATE"
    fi
fi

if [ -n "$SCRIPT_SRC" ]; then
    echo "   Installing from local checkout: $SCRIPT_SRC"
    cp -r "$SCRIPT_SRC/openusbip_server.py" "$SCRIPT_SRC/src" "$INSTALL_DIR/"
    [ -f "$SCRIPT_SRC/requirements.txt" ] && cp "$SCRIPT_SRC/requirements.txt" "$INSTALL_DIR/"
else
    echo "   Fetching from GitHub..."
    if ! command -v git > /dev/null 2>&1; then
        sudo apt-get install -y git > /dev/null 2>&1
    fi
    TMP_CLONE="$(mktemp -d)"
    git clone --depth 1 https://github.com/ameggs/OpenUSBIP.git "$TMP_CLONE/repo" > /dev/null 2>&1
    cp -r "$TMP_CLONE/repo/openusbip_server.py" "$TMP_CLONE/repo/src" "$INSTALL_DIR/"
    [ -f "$TMP_CLONE/repo/requirements.txt" ] && cp "$TMP_CLONE/repo/requirements.txt" "$INSTALL_DIR/"
    rm -rf "$TMP_CLONE"
fi

if [ ! -f "$INSTALL_DIR/openusbip_server.py" ] || [ ! -d "$INSTALL_DIR/src" ]; then
    echo "❌ ERROR: installation failed - openusbip_server.py or src/ is missing."
    exit 1
fi

chmod +x "$INSTALL_DIR/openusbip_server.py"

echo "✅ Server installed!"
echo ""

# Load kernel modules
echo "🔧 Configuring kernel modules..."
sudo modprobe usbip_host 2>/dev/null || true
sudo modprobe vhci_hcd 2>/dev/null || true

# Add to /etc/modules for auto-load on boot
if ! grep -q "usbip_host" /etc/modules 2>/dev/null; then
    echo "usbip_host" | sudo tee -a /etc/modules > /dev/null
fi
if ! grep -q "vhci_hcd" /etc/modules 2>/dev/null; then
    echo "vhci_hcd" | sudo tee -a /etc/modules > /dev/null
fi

echo "✅ Kernel modules configured!"
echo ""

# Create systemd service for usbipd
echo "⚙️  Creating systemd services..."

# Locate usbipd. Its path varies by distro: /usr/sbin on some Debian builds,
# /usr/bin on Ubuntu/Zorin, and under /usr/lib/linux-tools/<ver>/ when it
# comes from linux-tools-generic. Never hardcode it.
USBIPD_BIN="$(command -v usbipd 2>/dev/null || true)"
if [ -z "$USBIPD_BIN" ]; then
    for candidate in /usr/sbin/usbipd /usr/bin/usbipd /usr/lib/linux-tools/*/usbipd; do
        if [ -x "$candidate" ]; then
            USBIPD_BIN="$candidate"
            break
        fi
    done
fi

if [ -z "$USBIPD_BIN" ]; then
    echo "❌ ERROR: could not locate the 'usbipd' binary."
    echo "   Install it first (Debian/Ubuntu: sudo apt install linux-tools-generic)."
    exit 1
fi
echo "   Found usbipd at: $USBIPD_BIN"

sudo tee /etc/systemd/system/usbipd.service > /dev/null << USBIPD_SERVICE
[Unit]
Description=USB/IP Daemon
After=network.target

[Service]
Type=forking
ExecStart=$USBIPD_BIN -D
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
USBIPD_SERVICE

# Create systemd service for OpenUSBIP server
sudo tee /etc/systemd/system/openusbip-server.service > /dev/null << OPENUSBIP_SERVICE
[Unit]
Description=OpenUSBIP Server - USB over IP Server
After=network.target usbipd.service
Wants=usbipd.service

[Service]
Type=simple
User=$USER
WorkingDirectory=$HOME
ExecStart=/usr/bin/python3 $INSTALL_DIR/openusbip_server.py --daemon
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
OPENUSBIP_SERVICE

# Create auto-bind script
cat > "$INSTALL_DIR/bind-usb-devices.sh" << 'BINDSCRIPT'
#!/bin/bash
# Auto-bind USB devices for sharing

sleep 5

# Bind all non-hub USB devices
for device in /sys/bus/usb/drivers/usb/*; do
    if [ -e "$device/idVendor" ]; then
        vendor=$(cat "$device/idVendor")
        # Skip hubs and built-in devices
        if [ "$vendor" != "1d6b" ] && [ "$vendor" != "0424" ]; then
            busid=$(basename "$device")
            /usr/sbin/usbip bind -b "$busid" 2>/dev/null || true
        fi
    fi
done

logger "OpenUSBIP: USB devices bound"
BINDSCRIPT

chmod +x "$INSTALL_DIR/bind-usb-devices.sh"

# Create auto-bind service
sudo tee /etc/systemd/system/usb-auto-bind.service > /dev/null << AUTOBIND_SERVICE
[Unit]
Description=Auto-bind USB devices for USB/IP
After=usbipd.service
Requires=usbipd.service

[Service]
Type=oneshot
ExecStart=$INSTALL_DIR/bind-usb-devices.sh
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
AUTOBIND_SERVICE

# Create udev rule for hotplug
sudo tee /etc/udev/rules.d/99-usbip-auto-bind.rules > /dev/null << 'UDEV_RULE'
# Auto-bind USB devices when plugged in
SUBSYSTEM=="usb", ACTION=="add", ATTRS{idVendor}!="0424", ATTRS{idVendor}!="1d6b", ATTRS{bDeviceClass}!="09", RUN+="/bin/bash -c 'sleep 3 && /usr/sbin/usbip bind -b $(basename %p) 2>&1 | /usr/bin/logger -t usbip-hotplug'"
UDEV_RULE

# Reload systemd and udev
sudo systemctl daemon-reload
sudo udevadm control --reload-rules

echo "✅ Services created!"
echo ""

# Enable and start services
echo "🚀 Starting services..."
sudo systemctl enable usbipd.service > /dev/null 2>&1
sudo systemctl enable openusbip-server.service > /dev/null 2>&1
sudo systemctl enable usb-auto-bind.service > /dev/null 2>&1

# Stop any existing instances
sudo pkill usbipd 2>/dev/null || true
sudo pkill -f openusbip_server 2>/dev/null || true

# Start services
sudo systemctl start usbipd.service
sudo systemctl start openusbip-server.service
sudo systemctl start usb-auto-bind.service

sleep 2

echo "✅ Services started!"
echo ""

# Show status
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║                  ✅ INSTALLATION COMPLETE! ✅                 ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "📊 Server Status:"
echo ""
sudo systemctl status usbipd.service --no-pager -l | head -5
echo ""
sudo systemctl status openusbip-server.service --no-pager -l | head -5
echo ""

# Get IP
IP=$(get_local_ip)
HOSTNAME=$(hostname)

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    Server Information                        ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  🥧 Hostname: $HOSTNAME"
echo "  🌐 IP Address: $IP"
echo "  🔌 Port: 3240"
echo "  📡 mDNS: $HOSTNAME.local"
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    What's Next?                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  1. Your server is running and advertising via mDNS!"
echo "  2. Plug in USB devices - they'll auto-bind for sharing"
echo "  3. On your desktop, run the OpenUSBIP client"
echo "  4. Your Raspberry Pi will appear automatically!"
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                  Useful Commands                             ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  View available devices:"
echo "    sudo usbip list -l"
echo ""
echo "  Check server status:"
echo "    sudo systemctl status openusbip-server"
echo ""
echo "  View logs:"
echo "    sudo journalctl -u openusbip-server -f"
echo ""
echo "  Restart server:"
echo "    sudo systemctl restart openusbip-server"
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║         🎉 Your USB/IP Server is Ready! 🎉                  ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
