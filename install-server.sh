#!/bin/bash
#
# OpenUSBIP Server - One-Command Installer
# For Raspberry Pi and Linux SBCs
#
# Usage: curl -sSL https://raw.githubusercontent.com/ameggs/OpenUSBIP/main/install-server.sh | bash
# Or: wget -qO- https://raw.githubusercontent.com/ameggs/OpenUSBIP/main/install-server.sh | bash
#

set -e

# Resolve where this script lives BEFORE anything changes directory. Doing it
# later is a trap: BASH_SOURCE[0] is often relative ("./install-server.sh"), so
# after `cd "$INSTALL_DIR"` it resolves to the install directory instead of the
# source checkout -- which silently made the installer clone from GitHub and
# install stale code over the local tree.
SCRIPT_SRC=""
if [ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ]; then
    _candidate="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    if [ -f "$_candidate/openusbip_server.py" ] && [ -d "$_candidate/src" ]; then
        SCRIPT_SRC="$_candidate"
    fi
fi

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
    MODEL=$(tr -d "\0" < /proc/device-tree/model)
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

# Install required packages.
#
# Package names differ by distro and requesting one that does not exist aborts
# the whole apt run: Debian/Raspberry Pi OS ship `usbip`, while Ubuntu/Zorin
# have no such package and put the binary in linux-tools-common. Offer both and
# install whichever actually exists, then verify the binary rather than
# trusting package names.
#
# Prefer distro Python packages over pip: Debian 12+ marks the environment
# externally managed (PEP 668), so `pip install` needs --break-system-packages
# and fights the package manager. python3-zeroconf is the same library.
echo "   Installing USB/IP tools..."
APT_LOG="$(mktemp)"

install_apt_packages() {
    local pkgs=()
    local candidate

    for candidate in usbip linux-tools-common "linux-tools-$(uname -r)" \
                     linux-tools-generic avahi-daemon avahi-utils python3 \
                     python3-zeroconf python3-colorlog; do
        if apt-cache policy "$candidate" 2>/dev/null | grep -q 'Candidate: [^(]'; then
            pkgs+=("$candidate")
        else
            echo "   note: skipping unavailable package '$candidate'"
        fi
    done

    if [ ${#pkgs[@]} -eq 0 ]; then
        echo "   ⚠️  No installable packages found; relying on what is present."
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

install_apt_packages
rm -f "$APT_LOG"

# Fall back to pip only if the distro package did not supply zeroconf.
if ! python3 -c "import zeroconf" 2>/dev/null; then
    echo "   Installing zeroconf via pip (no distro package available)..."
    PIP_LOG="$(mktemp)"
    if ! pip3 install --user --break-system-packages zeroconf > "$PIP_LOG" 2>&1; then
        pip3 install --user zeroconf >> "$PIP_LOG" 2>&1 || true
    fi
    if ! python3 -c "import zeroconf" 2>/dev/null; then
        echo "   ⚠️  zeroconf unavailable - mDNS auto-discovery will be disabled."
        echo "      Clients can still add this server manually by IP."
        tail -5 "$PIP_LOG" | sed 's/^/      /'
    fi
    rm -f "$PIP_LOG"
fi

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
# Auto-bind USB devices for sharing.
#
# Hubs are excluded by USB device class (09 = hub), not by a vendor blocklist.
# The old filter only skipped vendors 1d6b and 0424, so it attempted to bind
# any third-party hub (VIA Labs 2109, Realtek 0bda, ...). usbip refuses those,
# but binding a hub would detach everything downstream of it.
#
# The usbip binary lives in /usr/sbin on Debian and /usr/bin on Ubuntu, so
# resolve it at runtime instead of hardcoding either.

sleep 5

USBIP_BIN="$(command -v usbip 2>/dev/null || true)"
if [ -z "$USBIP_BIN" ]; then
    for candidate in /usr/sbin/usbip /usr/bin/usbip /usr/lib/linux-tools/*/usbip; do
        [ -x "$candidate" ] && USBIP_BIN="$candidate" && break
    done
fi

if [ -z "$USBIP_BIN" ]; then
    logger "OpenUSBIP: usbip binary not found; nothing bound"
    exit 0
fi

bound=0
for device in /sys/bus/usb/devices/*/; do
    [ -f "$device/idVendor" ] || continue

    # Only real interfaces-level devices have a bDeviceClass file.
    class="$(cat "$device/bDeviceClass" 2>/dev/null || echo '')"
    [ "$class" = "09" ] && continue          # hub
    [ -z "$class" ] && continue              # interface node, not a device

    busid="$(basename "$device")"
    case "$busid" in
        usb*) continue ;;                    # root hub controller
    esac

    if "$USBIP_BIN" bind -b "$busid" 2>/dev/null; then
        logger "OpenUSBIP: bound $busid"
        bound=$((bound + 1))
    fi
done

logger "OpenUSBIP: auto-bind complete, $bound device(s) bound"
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

# Get IP. `hostname -I` lists every address, so take the first non-loopback
# one; fall back to the route lookup. (This previously called get_local_ip,
# a Python function that only ever existed inside the removed heredoc, so the
# script errored out here with "command not found".)
IP="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -vE '^(127\.|::1|$)' | head -1)"
if [ -z "$IP" ]; then
    IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}' | head -1)"
fi
[ -z "$IP" ] && IP="(could not determine)"
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
