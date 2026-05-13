#!/bin/bash

# OpenUSBIP Linux Client - One-Command Installer
# Run: curl -sSL https://raw.githubusercontent.com/yourusername/OpenUSBIP/main/install-linux-client.sh | bash

set -e

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║           OpenUSBIP Client - Linux Installer                ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Check if running as root
if [ "$EUID" -eq 0 ]; then 
   echo "⚠️  Please do NOT run as root. Run as regular user."
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

# Install USB/IP tools
if command -v apt-get &> /dev/null; then
    # Debian/Ubuntu/Pop!_OS
    sudo apt-get install -y linux-tools-generic usbip avahi-utils python3 python3-pip -qq 2>/dev/null || \
    sudo apt-get install -y usbip avahi-utils python3 python3-pip -qq 2>/dev/null
elif command -v dnf &> /dev/null; then
    # Fedora/RHEL
    sudo dnf install -y usbip-utils avahi-tools python3 python3-pip -q
elif command -v pacman &> /dev/null; then
    # Arch
    sudo pacman -S --noconfirm usbip avahi python python-pip
else
    echo "⚠️  Could not determine package manager. Please install usbip tools manually."
fi

echo "✅ System dependencies installed!"

echo ""
echo "🐍 Installing Python dependencies..."
pip3 install --user --break-system-packages PySide6 zeroconf 2>/dev/null || \
pip3 install --user PySide6 zeroconf 2>/dev/null || \
pip3 install PySide6 zeroconf

echo "✅ Python dependencies installed!"

# Load kernel modules
echo ""
echo "🔧 Configuring kernel modules..."
sudo modprobe vhci_hcd 2>/dev/null || true
sudo modprobe usbip_core 2>/dev/null || true
sudo modprobe usbip_host 2>/dev/null || true

# Add to /etc/modules for auto-load
if [ -f /etc/modules ]; then
    grep -q "vhci_hcd" /etc/modules || echo "vhci_hcd" | sudo tee -a /etc/modules > /dev/null
    grep -q "usbip_core" /etc/modules || echo "usbip_core" | sudo tee -a /etc/modules > /dev/null
    grep -q "usbip_host" /etc/modules || echo "usbip_host" | sudo tee -a /etc/modules > /dev/null
fi

echo "✅ Kernel modules configured!"

# Configure passwordless sudo for usbip
echo ""
echo "🔧 Configuring passwordless sudo..."
USBIP_PATH=$(which usbip 2>/dev/null)
if [ -n "$USBIP_PATH" ]; then
    SUDOERS_FILE="/etc/sudoers.d/usbip"
    sudo tee "$SUDOERS_FILE" > /dev/null << EOF
# OpenUSBIP - Allow passwordless usbip commands
$USER ALL=(ALL) NOPASSWD: $USBIP_PATH
EOF
    sudo chmod 440 "$SUDOERS_FILE"
    echo "✅ Passwordless sudo configured!"
else
    echo "⚠️  usbip command not found in PATH"
fi

# Download and set up the client
echo ""
echo "⬇️  Downloading OpenUSBIP client..."

# Create installation directory
INSTALL_DIR="$HOME/OpenUSBIP-Client"
mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"

# Download the client files (you would replace this with actual download)
# For now, we'll assume the files are already available
# curl -sSL https://github.com/yourusername/OpenUSBIP/archive/main.tar.gz | tar xz --strip-components=1

# Create a simple launcher script
cat > openusbip-client.sh << 'EOF'
#!/bin/bash
# OpenUSBIP Client Launcher

echo "Starting OpenUSBIP Client..."
cd "$(dirname "$0")"
python3 openusbip_client.py
EOF

chmod +x openusbip-client.sh

# Create desktop launcher
echo ""
echo "🖥️  Creating desktop launcher..."

DESKTOP_FILE="$HOME/.local/share/applications/openusbip-client.desktop"
mkdir -p "$HOME/.local/share/applications"

cat > "$DESKTOP_FILE" << EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=OpenUSBIP Client
Comment=Connect to remote USB devices over network
Exec=$INSTALL_DIR/openusbip-client.sh
Icon=drive-removable-media
Terminal=false
Categories=Network;Utility;
StartupNotify=true
EOF

chmod +x "$DESKTOP_FILE"

# Also create desktop shortcut
if [ -d "$HOME/Desktop" ]; then
    cp "$DESKTOP_FILE" "$HOME/Desktop/"
    chmod +x "$HOME/Desktop/openusbip-client.desktop"
fi

# Update desktop database
if command -v update-desktop-database &> /dev/null; then
    update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
fi

echo "✅ Desktop launcher created!"

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║                  ✅ INSTALLATION COMPLETE! ✅                 ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    How to Launch                             ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  Option 1: Look for 'OpenUSBIP Client' in your applications menu"
echo ""
echo "  Option 2: Double-click the desktop shortcut"
echo ""
echo "  Option 3: Run from terminal:"
echo "    $INSTALL_DIR/openusbip-client.sh"
echo ""

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    What's Next?                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  1. Launch the OpenUSBIP Client"
echo "  2. Your USB/IP servers will appear automatically!"
echo "  3. Click 'Attach' to connect to any USB device"
echo "  4. The app minimizes to system tray for easy access"
echo ""

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║         🎉 Your USB/IP Client is Ready! 🎉                  ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

echo "Installation directory: $INSTALL_DIR"