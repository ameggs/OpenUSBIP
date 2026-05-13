#!/bin/bash
#
# OpenUSBIP Client - Manual Setup (Run this in a terminal)
# Usage: ./setup-client-manual.sh
#

set -e

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║           OpenUSBIP Client - Manual Setup                   ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Get current directory
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "📍 Repository: $REPO_DIR"
echo ""

# Check dependencies
echo "🔍 Checking dependencies..."
echo ""

# Check Python packages
if python3 -c "import PySide6" 2>/dev/null; then
    echo "  ✅ PySide6 installed"
else
    echo "  ❌ PySide6 NOT installed"
    echo "     Install: pip3 install --user PySide6"
    exit 1
fi

if python3 -c "import zeroconf" 2>/dev/null; then
    echo "  ✅ zeroconf installed"
else
    echo "  ❌ zeroconf NOT installed"
    echo "     Install: pip3 install --user zeroconf"
    exit 1
fi

# Check usbip
USBIP_PATH=$(which usbip 2>/dev/null || echo "")
if [ -z "$USBIP_PATH" ]; then
    echo "  ❌ usbip NOT installed"
    echo "     Install: sudo apt install linux-tools-generic"
    exit 1
else
    echo "  ✅ usbip found at: $USBIP_PATH"
fi

echo ""
echo "🔧 Configuring passwordless sudo..."
echo ""

# Create sudoers file
SUDOERS_FILE="/etc/sudoers.d/usbip"
TEMP_FILE="/tmp/usbip-sudoers-$$"

cat > "$TEMP_FILE" << SUDOERS
# OpenUSBIP - Allow passwordless usbip commands
# This is required for the GUI to attach/detach devices
$USER ALL=(ALL) NOPASSWD: $USBIP_PATH
SUDOERS

echo "  Creating sudoers file: $SUDOERS_FILE"
sudo cp "$TEMP_FILE" "$SUDOERS_FILE"
sudo chmod 440 "$SUDOERS_FILE"
sudo chown root:root "$SUDOERS_FILE"

# Verify syntax
if sudo visudo -c -f "$SUDOERS_FILE" > /dev/null 2>&1; then
    echo "  ✅ Sudoers file created successfully"
else
    echo "  ❌ ERROR: Invalid sudoers syntax!"
    sudo rm -f "$SUDOERS_FILE"
    rm -f "$TEMP_FILE"
    exit 1
fi

rm -f "$TEMP_FILE"

# Test passwordless sudo
echo ""
echo "🧪 Testing passwordless sudo..."
if sudo -n "$USBIP_PATH" version > /dev/null 2>&1; then
    echo "  ✅ Passwordless sudo working!"
else
    echo "  ⚠️  Test failed (may need to log out/in)"
fi

echo ""
echo "🔧 Loading kernel module..."
sudo modprobe vhci_hcd 2>/dev/null || true

# Make it persistent
if ! grep -q "vhci_hcd" /etc/modules 2>/dev/null; then
    echo "vhci_hcd" | sudo tee -a /etc/modules > /dev/null
    echo "  ✅ Module will load on boot"
fi

# Also create systemd module load
sudo mkdir -p /etc/modules-load.d
echo "vhci_hcd" | sudo tee /etc/modules-load.d/usbip.conf > /dev/null

if lsmod | grep -q vhci_hcd; then
    echo "  ✅ vhci_hcd module loaded"
else
    echo "  ⚠️  Module not loaded (may need reboot)"
fi

echo ""
echo "🖥️  Creating desktop launcher..."

# Create .desktop file
DESKTOP_FILE="$HOME/.local/share/applications/openusbip-client.desktop"
mkdir -p "$HOME/.local/share/applications"

cat > "$DESKTOP_FILE" << DESKTOP
[Desktop Entry]
Version=1.0
Type=Application
Name=OpenUSBIP Client
Comment=Connect to remote USB devices over network
Exec=python3 "$REPO_DIR/openusbip_client.py"
Icon=$REPO_DIR/resources/icons/openusbip.svg
Terminal=false
Categories=Network;Utility;
Keywords=usb;network;remote;
StartupNotify=true
DESKTOP

chmod +x "$DESKTOP_FILE"

echo "  ✅ Application menu launcher created"

# Create desktop shortcut
if [ -d "$HOME/Desktop" ]; then
    cp "$DESKTOP_FILE" "$HOME/Desktop/openusbip-client.desktop"
    chmod +x "$HOME/Desktop/openusbip-client.desktop"
    
    # Try to mark as trusted (GNOME/Pop!_OS)
    gio set "$HOME/Desktop/openusbip-client.desktop" metadata::trusted true 2>/dev/null || true
    
    echo "  ✅ Desktop shortcut created"
fi

# Update desktop database
if command -v update-desktop-database &> /dev/null; then
    update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
fi

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║                  ✅ SETUP COMPLETE! ✅                        ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    How to Launch                             ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  Option 1: Search for 'OpenUSBIP' in your application menu"
echo ""
echo "  Option 2: Double-click the desktop shortcut"
echo ""
echo "  Option 3: Run from terminal:"
echo "    cd \"$REPO_DIR\""
echo "    python3 openusbip_client.py"
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    Your Servers                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  The client will auto-discover these servers:"
echo "    • raspberrypi (192.168.0.34)"
echo "    • USB (192.168.0.27)"
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║         🎉 Ready to Connect to USB Devices! 🎉              ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
