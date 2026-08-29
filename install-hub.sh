#!/usr/bin/env bash
#
# Install the OpenUSBIP Hub -- the web-based USB/IP client -- on a headless
# Debian-family machine such as a Proxmox host.
#
# Scope, deliberately narrow, because this often runs on a hypervisor:
#
#   * installs packages only from a fixed list, and only those apt actually has
#   * writes only to /opt/openusbip, /etc/systemd/system/openusbip-hub.service,
#     /etc/modules-load.d/openusbip.conf and /etc/openusbip
#   * touches no firewall, no network configuration, no storage
#   * restarts nothing that was already running
#   * leaves the service DISABLED, so a reboot does not silently start a
#     network service you have not decided to keep
#
# Undo with: ./install-hub.sh --uninstall

set -euo pipefail

PREFIX=/opt/openusbip
UNIT=/etc/systemd/system/openusbip-hub.service
MODULES_CONF=/etc/modules-load.d/openusbip.conf
CONFIG_DIR=/etc/openusbip
SERVICE=openusbip-hub

# Resolve before any cd: a later cd would change what BASH_SOURCE resolves to
# and the script would install from the wrong directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BOLD=$'\033[1m'; RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; OFF=$'\033[0m'
say()  { printf '%s\n' "$*"; }
step() { printf '\n%s==> %s%s\n' "$BOLD" "$*" "$OFF"; }
ok()   { printf '    %s✓%s %s\n' "$GREEN" "$OFF" "$*"; }
warn() { printf '    %s!%s %s\n' "$YELLOW" "$OFF" "$*"; }
die()  { printf '\n%sERROR:%s %s\n' "$RED" "$OFF" "$*" >&2; exit 1; }

[[ ${EUID} -eq 0 ]] || die "run as root (attaching USB devices and mounting need it)"

# --- uninstall ---------------------------------------------------------------

if [[ "${1:-}" == "--uninstall" ]]; then
    step "Removing the OpenUSBIP Hub"

    if systemctl is-active --quiet "$SERVICE" 2>/dev/null; then
        systemctl stop "$SERVICE"; ok "stopped $SERVICE"
    fi
    if systemctl is-enabled --quiet "$SERVICE" 2>/dev/null; then
        systemctl disable "$SERVICE" >/dev/null 2>&1; ok "disabled $SERVICE"
    fi

    # Release any mounts before deleting the code that knows how to.
    if [[ -x "$PREFIX/openusbip_hub.py" ]]; then
        for mount in /run/openusbip/*; do
            [[ -d "$mount" ]] || continue
            mountpoint -q "$mount" || continue
            fusermount3 -u "$mount" 2>/dev/null || umount "$mount" 2>/dev/null || \
                warn "could not unmount $mount"
        done
    fi

    rm -f "$UNIT" "$MODULES_CONF"
    systemctl daemon-reload
    rm -rf "$PREFIX"
    ok "removed $PREFIX, $UNIT, $MODULES_CONF"
    warn "left $CONFIG_DIR alone; it holds your access token. Delete it by hand if you want."
    say ""
    say "Packages installed by this script (usbip, jmtpfs, ...) were left in place."
    exit 0
fi

# --- checks ------------------------------------------------------------------

step "Checking the machine"

command -v apt-get >/dev/null || die "this installer expects a Debian-family system (apt-get)"
ok "$(. /etc/os-release && echo "$PRETTY_NAME")"

if [[ ! -f "$SCRIPT_DIR/openusbip_hub.py" ]]; then
    die "run this from a checkout: openusbip_hub.py is not next to the script"
fi

python3 - <<'PY' || die "Python 3.10 or newer is required"
import sys
sys.exit(0 if sys.version_info >= (3, 10) else 1)
PY
ok "python3 $(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')"

# The virtual host controller is what makes a client possible at all. Its
# absence is not something the installer can fix, so say so plainly.
if ! modinfo vhci-hcd >/dev/null 2>&1; then
    die "this kernel has no vhci-hcd module, so it cannot attach remote USB devices.
       A USB/IP client needs it; there is no userspace substitute."
fi
ok "vhci-hcd is available in $(uname -r)"

# --- packages ----------------------------------------------------------------

step "Installing packages"

WANTED=(usbip jmtpfs mtp-tools python3-zeroconf)
MISSING=()
for package in "${WANTED[@]}"; do
    if dpkg -s "$package" >/dev/null 2>&1; then
        ok "$package (already installed)"
        continue
    fi
    # Only ask apt for packages it actually has; a name that does not exist on
    # this release would otherwise abort the whole transaction.
    #
    # The output is captured before matching rather than piped into grep -q.
    # Under `set -o pipefail` that pipeline reports failure even on a match:
    # grep -q exits at the first hit, apt-cache gets SIGPIPE, and pipefail
    # takes the pipeline's status from the failed left-hand side. The effect
    # was that every package looked unavailable on a machine that had them all.
    policy="$(apt-cache policy "$package" 2>/dev/null || true)"
    if printf '%s' "$policy" | grep -q 'Candidate: [^(]'; then
        MISSING+=("$package")
    else
        warn "$package is not available on this release"
    fi
done

if (( ${#MISSING[@]} )); then
    say "    installing: ${MISSING[*]}"
    DEBIAN_FRONTEND=noninteractive apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${MISSING[@]}"
    for package in "${MISSING[@]}"; do ok "$package"; done
fi

command -v usbip >/dev/null || die "usbip is still not on PATH after installing"

# --- kernel module -----------------------------------------------------------

step "Loading the virtual host controller"

# Captured for the same pipefail/SIGPIPE reason as the apt check above.
loaded_modules="$(lsmod || true)"
if printf '%s' "$loaded_modules" | grep -q '^vhci_hcd'; then
    ok "vhci_hcd already loaded"
else
    modprobe vhci-hcd || die "could not load vhci-hcd"
    ok "vhci_hcd loaded"
fi

# Load at boot. Its own file, so uninstalling removes exactly what was added
# rather than editing a shared /etc/modules.
printf '# Added by OpenUSBIP. Remove this file to undo.\nvhci-hcd\n' > "$MODULES_CONF"
ok "will load at boot ($MODULES_CONF)"

# --- files -------------------------------------------------------------------

step "Installing to $PREFIX"

mkdir -p "$PREFIX"
# Copy only what the hub needs. Notably not the Qt client, the Android app or
# the tests -- there is no reason for them to sit on a hypervisor.
for item in openusbip_hub.py openusbip_server.py src resources; do
    [[ -e "$SCRIPT_DIR/$item" ]] || continue
    rm -rf "${PREFIX:?}/$item"
    cp -a "$SCRIPT_DIR/$item" "$PREFIX/"
done
find "$PREFIX" -name '__pycache__' -type d -prune -exec rm -rf {} + 2>/dev/null || true
chmod 755 "$PREFIX/openusbip_hub.py"
ok "copied $(find "$PREFIX" -name '*.py' | wc -l) Python files"

mkdir -p "$CONFIG_DIR"; chmod 700 "$CONFIG_DIR"
ok "$CONFIG_DIR ready (the access token lives here)"

# --- service -----------------------------------------------------------------

step "Creating the systemd unit"

BIND="${OPENUSBIP_BIND:-0.0.0.0}"
PORT="${OPENUSBIP_PORT:-8765}"

cat > "$UNIT" <<UNITEOF
[Unit]
Description=OpenUSBIP Hub (USB/IP client with a web interface)
Documentation=https://github.com/ameggs/OpenUSBIP
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 $PREFIX/openusbip_hub.py --bind $BIND --port $PORT
Restart=on-failure
RestartSec=5

# Root is required: attaching talks to the vhci driver and mounting talks to
# the kernel. The restrictions below are what can still be given up.
NoNewPrivileges=yes
ProtectHostname=yes
ProtectKernelLogs=yes
ProtectControlGroups=yes
RestrictRealtime=yes
RestrictSUIDSGID=yes
LockPersonality=yes

# Mounts must be visible to the rest of the system, so the namespace is shared
# rather than private -- a private one would hide every mount inside the unit.
MountFlags=shared

[Install]
WantedBy=multi-user.target
UNITEOF

systemctl daemon-reload
ok "wrote $UNIT"
warn "left DISABLED on purpose -- nothing starts at boot until you say so"

# --- done --------------------------------------------------------------------

step "Installed"

cat <<DONE

    Start it now, in the foreground, to see what it does:

        python3 $PREFIX/openusbip_hub.py

    Or run it as a service:

        systemctl start $SERVICE
        systemctl status $SERVICE
        journalctl -u $SERVICE -f

    Either way it prints a URL containing an access token. That token is the
    only thing protecting a service that can attach USB devices and write
    files as root, so do not expose port $PORT to the internet.

    To recover the URL later:

        python3 $PREFIX/openusbip_hub.py --print-token

    Once you are happy with it, survive reboots with:

        systemctl enable $SERVICE

    To remove everything:

        $SCRIPT_DIR/install-hub.sh --uninstall

DONE
