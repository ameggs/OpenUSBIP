#!/bin/bash
#
# OpenUSBIP Client - One-Command Installer
# For Linux Desktop/Laptop
#
# Usage: sudo ./install-client.sh
# Must be run from a checkout (it launches the client from the repo directory):
#   git clone https://github.com/ameggs/OpenUSBIP.git && cd OpenUSBIP && ./install-client.sh
#

set -e

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║           OpenUSBIP Client - Easy Installer                 ║"
echo "║                                                              ║"
echo "║     Connect to remote USB devices over your network!        ║"
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
DISTRO="Unknown"
if [ -f /etc/os-release ]; then
    . /etc/os-release
    DISTRO="$NAME"
    echo "   Detected: $DISTRO"
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
if command -v apt-get &> /dev/null; then
    sudo apt-get update -qq
elif command -v dnf &> /dev/null; then
    sudo dnf check-update -q || true
elif command -v pacman &> /dev/null; then
    sudo pacman -Sy --noconfirm
fi

# Install required packages.
#
# There is no "usbip" package on Ubuntu/Zorin -- the binary ships in
# linux-tools-common, with the kernel-specific implementation in
# linux-tools-<version>. Requiring a package named "usbip" made apt fail with
# "no installation candidate", and because the output was redirected to
# /dev/null the script died under `set -e` with nothing on screen.
#
# Install only packages that exist, and verify the binary afterwards rather
# than trusting package names across distros.
echo "   Installing USB/IP tools..."
APT_LOG="$(mktemp)"

install_apt_packages() {
    local pkgs=()
    local candidate

    # The libxcb-*/libxkbcommon-x11 set is not optional: from Qt 6.5 the xcb
    # platform plugin refuses to load without libxcb-cursor0, and PySide6 then
    # aborts with "no Qt platform plugin could be initialized" even though the
    # plugin file is present. The client is a GUI, so these are hard deps.
    for candidate in linux-tools-common "linux-tools-$(uname -r)" linux-tools-generic \
                     avahi-utils python3 python3-pip \
                     libxcb-cursor0 libxcb-icccm4 libxcb-keysyms1 libxcb-shape0 \
                     libxcb-xinerama0 libxkbcommon-x11-0 libegl1 libgl1 libdbus-1-3; do
        if apt-cache policy "$candidate" 2>/dev/null | grep -q 'Candidate: [^(]'; then
            pkgs+=("$candidate")
        else
            echo "   note: skipping unavailable package '$candidate'"
        fi
    done

    if [ ${#pkgs[@]} -eq 0 ]; then
        echo "   ⚠️  No installable packages found; relying on what is already present."
        return 0
    fi

    if ! sudo apt-get install -y "${pkgs[@]}" > "$APT_LOG" 2>&1; then
        echo "❌ ERROR: apt failed to install: ${pkgs[*]}"
        echo "   ---- apt output ----"
        tail -20 "$APT_LOG" | sed 's/^/   /'
        rm -f "$APT_LOG"
        exit 1
    fi
}

if command -v apt-get &> /dev/null; then
    install_apt_packages
elif command -v dnf &> /dev/null; then
    if ! sudo dnf install -y usbip-utils avahi-tools python3 python3-pip > "$APT_LOG" 2>&1; then
        echo "❌ ERROR: dnf install failed:"; tail -20 "$APT_LOG" | sed 's/^/   /'; exit 1
    fi
elif command -v pacman &> /dev/null; then
    if ! sudo pacman -S --noconfirm usbip avahi python python-pip > "$APT_LOG" 2>&1; then
        echo "❌ ERROR: pacman install failed:"; tail -20 "$APT_LOG" | sed 's/^/   /'; exit 1
    fi
fi
rm -f "$APT_LOG"

echo "   Installing Python packages..."
PIP_LOG="$(mktemp)"
if ! pip3 install --user --break-system-packages PySide6 zeroconf pyyaml colorlog > "$PIP_LOG" 2>&1; then
    if ! pip3 install --user PySide6 zeroconf pyyaml colorlog >> "$PIP_LOG" 2>&1; then
        # Already satisfied system-wide is fine; a real failure is not.
        if ! python3 -c "import PySide6, zeroconf" 2>/dev/null; then
            echo "❌ ERROR: could not install the Python dependencies:"
            tail -20 "$PIP_LOG" | sed 's/^/   /'
            rm -f "$PIP_LOG"
            exit 1
        fi
        echo "   note: pip reported an error but the imports resolve; continuing."
    fi
fi
rm -f "$PIP_LOG"

echo ""
echo "✅ Dependencies installed!"
echo ""

# Detect usbip path (CRITICAL for different distros)
echo "🔍 Detecting usbip installation path..."
USBIP_PATH=""

# Try common locations
if [ -x "/usr/bin/usbip" ]; then
    USBIP_PATH="/usr/bin/usbip"
elif [ -x "/usr/sbin/usbip" ]; then
    USBIP_PATH="/usr/sbin/usbip"
elif [ -x "/sbin/usbip" ]; then
    USBIP_PATH="/sbin/usbip"
else
    # Try to find it
    USBIP_PATH=$(which usbip 2>/dev/null || true)
fi

if [ -z "$USBIP_PATH" ]; then
    echo "❌ ERROR: Could not find usbip command!"
    echo "   Please install usbip tools for your distribution."
    exit 1
fi

echo "   Found usbip at: $USBIP_PATH"
echo ""

# Configure passwordless sudo for usbip (CRITICAL FIX)
echo "🔧 Configuring passwordless sudo for usbip..."

SUDOERS_FILE="/etc/sudoers.d/usbip"
sudo tee "$SUDOERS_FILE" > /dev/null << SUDOERS
# OpenUSBIP - Allow passwordless usbip commands
# This is required for the GUI to attach/detach devices
$USER ALL=(ALL) NOPASSWD: $USBIP_PATH
SUDOERS

sudo chmod 440 "$SUDOERS_FILE"

# Verify sudoers syntax
if sudo visudo -c -f "$SUDOERS_FILE" > /dev/null 2>&1; then
    echo "✅ Passwordless sudo configured!"
else
    echo "❌ ERROR: Invalid sudoers configuration!"
    sudo rm -f "$SUDOERS_FILE"
    exit 1
fi

echo ""

# Load kernel modules
echo "🔧 Configuring kernel modules..."
sudo modprobe vhci_hcd 2>/dev/null || true

# Add to /etc/modules for auto-load on boot
if [ -f /etc/modules ]; then
    if ! grep -q "vhci_hcd" /etc/modules 2>/dev/null; then
        echo "vhci_hcd" | sudo tee -a /etc/modules > /dev/null
    fi
fi

# For systemd-based systems, create module load config
sudo mkdir -p /etc/modules-load.d
echo "vhci_hcd" | sudo tee /etc/modules-load.d/usbip.conf > /dev/null

echo "✅ Kernel modules configured!"
echo ""

# Get current directory (where the repo is). This installer wires the desktop
# launcher to the checkout, so it cannot run standalone via curl | bash.
if [ -z "${BASH_SOURCE[0]:-}" ] || [ ! -f "${BASH_SOURCE[0]}" ]; then
    echo "❌ ERROR: this installer must be run from a checkout, not piped from curl."
    echo "   git clone https://github.com/ameggs/OpenUSBIP.git"
    echo "   cd OpenUSBIP && ./install-client.sh"
    exit 1
fi

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -f "$REPO_DIR/openusbip_client.py" ]; then
    echo "❌ ERROR: openusbip_client.py not found in $REPO_DIR"
    echo "   Run this script from inside the OpenUSBIP checkout."
    exit 1
fi

echo "📁 Repository location: $REPO_DIR"
echo ""

# Create desktop launcher
echo "🖥️  Creating desktop launcher..."

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
Categories=Network;RemoteAccess;
Keywords=usb;network;remote;
StartupNotify=true
DESKTOP

chmod +x "$DESKTOP_FILE"

# Also create a desktop shortcut
if [ -d "$HOME/Desktop" ]; then
    cp "$DESKTOP_FILE" "$HOME/Desktop/openusbip-client.desktop"
    chmod +x "$HOME/Desktop/openusbip-client.desktop"
    echo "✅ Desktop shortcut created!"
fi

# Update desktop database
if command -v update-desktop-database &> /dev/null; then
    update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
fi

echo "✅ Desktop launcher created!"
echo ""

# Test usbip access
echo "🧪 Testing usbip access..."
if sudo -n "$USBIP_PATH" version > /dev/null 2>&1; then
    echo "✅ Passwordless sudo working!"
else
    echo "⚠️  Warning: Passwordless sudo test failed"
    echo "   You may need to log out and back in"
fi
echo ""

# Verify the GUI toolkit can actually initialise. Catching this here is the
# difference between a clear message now and "no Qt platform plugin could be
# initialized" the first time the user double-clicks the launcher.
echo "🧪 Testing GUI toolkit..."
QT_SMOKE="$(cd "$REPO_DIR" && QT_QPA_PLATFORM=offscreen python3 -c '
import sys
from PySide6.QtWidgets import QApplication
app = QApplication(sys.argv)
print("ok")
' 2>&1)"

if [ "${QT_SMOKE##*$'\n'}" = "ok" ]; then
    echo "✅ Qt/PySide6 initialises correctly!"
else
    echo "❌ ERROR: PySide6 could not start."
    echo "$QT_SMOKE" | tail -6 | sed 's/^/   /'
    echo ""
    echo "   Most often a missing X11 library. Try:"
    echo "     sudo apt-get install -y libxcb-cursor0 libxkbcommon-x11-0 libegl1 libgl1"
    exit 1
fi
echo ""

# Show installation summary
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║                  ✅ INSTALLATION COMPLETE! ✅                 ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    Configuration Summary                     ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  🖥️  Distribution: $DISTRO"
echo "  🔌 usbip path: $USBIP_PATH"
echo "  ✅ Passwordless sudo: Configured"
echo "  🧩 Kernel module: vhci_hcd loaded"
echo "  🚀 Desktop launcher: Created"
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    How to Launch                             ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  Option 1: Click 'OpenUSBIP Client' in your application menu"
echo ""
echo "  Option 2: Double-click the desktop shortcut"
echo ""
echo "  Option 3: Run from terminal:"
echo "    cd \"$REPO_DIR\""
echo "    python3 openusbip_client.py"
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    What's Next?                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  1. Launch the OpenUSBIP Client"
echo "  2. Your Raspberry Pi servers will appear automatically!"
echo "  3. Click 'Attach' to connect to any USB device"
echo "  4. The app minimizes to system tray for easy access"
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                  Useful Commands                             ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  List attached devices:"
echo "    sudo $USBIP_PATH port"
echo ""
echo "  Manually attach a device:"
echo "    sudo $USBIP_PATH attach -r SERVER_IP -b BUS_ID"
echo ""
echo "  Manually detach a device:"
echo "    sudo $USBIP_PATH detach -p PORT"
echo ""
echo "  Find servers on network:"
echo "    avahi-browse -rt _usbip._tcp"
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║         🎉 Your USB/IP Client is Ready! 🎉                  ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
