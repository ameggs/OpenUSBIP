# OpenUSBIP

**Open Source USB over IP** — A free, cross-platform alternative to VirtualHere for sharing USB devices over your network.

Share USB devices between machines with an easy-to-use GUI. Works on Windows, Linux, and Raspberry Pi.

> ⚠️ **Read [Security](#security) before exposing this to a network.** USB/IP has no
> authentication and no encryption. Run it on a trusted LAN or over a VPN only.

## Features

- **Cross-Platform** — Windows, Linux, Raspberry Pi support
- **Modern GUI** — Clean Qt6 interface built with PySide6
- **Auto-Discovery** — Automatically find USB/IP servers on your network via mDNS
- **One-Click Operations** — Attach/detach remote USB devices with a single click
- **System Tray** — Minimize to tray with quick-access menu
- **Server Mode** — Share locally connected USB devices over the network
- **Client Mode** — Connect to and use remote USB devices
- **Persistent Settings** — Manually added servers and window layout survive restarts
- **100% Free** — Open source (GPL-3.0), no licensing fees

## Quick Start

### Server (Share USB Devices)

**Raspberry Pi / Linux:**

```bash
git clone https://github.com/ameggs/OpenUSBIP.git
cd OpenUSBIP
./install-server.sh
```

The installer sets up `usbipd`, loads the kernel modules, and installs two
systemd units (`usbipd.service` and `openusbip-server.service`).

**Manual start:**

```bash
sudo modprobe usbip_host
python3 openusbip_server.py
```

Useful flags:

| Flag | Effect |
|---|---|
| `--daemon` | Never prompt. Use this when running under systemd or any non-TTY context. |
| `--port N` | Advertise a port other than the 3240 default. |

### Client (Use Remote USB Devices)

```bash
git clone https://github.com/ameggs/OpenUSBIP.git
cd OpenUSBIP
./install-client.sh
```

The client installer must run from a checkout — it wires a desktop launcher to
the repository directory.

**Manual start:**

```bash
pip install -r requirements.txt
python3 openusbip_client.py
```

Attaching and detaching devices requires root. The installer configures a
narrowly scoped passwordless sudo rule for the `usbip` binary; without it the
GUI will report that it could not obtain privileges.

## Requirements

- **Python** 3.10+
- **PySide6** (Qt6 bindings) — client only; the server runs headless
- **USB/IP tools** — `usbip` / `usbipd`
  - Debian/Ubuntu: `sudo apt install linux-tools-generic usbip`
  - Fedora: `sudo dnf install usbip`
  - Windows: [usbipd-win](https://github.com/dorssel/usbipd-win)

## Security

USB/IP is a plaintext, unauthenticated protocol. OpenUSBIP does not change
that, and you should plan your deployment around it:

- **No authentication.** Anyone who can reach port 3240 on the server can
  enumerate its shared devices and attach them. There is no password, no
  allowlist, and no pairing step.
- **No encryption.** All USB traffic — keystrokes, storage contents,
  everything — crosses the network in the clear and is readable by anyone who
  can see the packets.
- **The server advertises itself.** mDNS broadcasts the server's presence to
  the whole local network by design, so it is discoverable, not hidden.
- **Attaching a remote device is a trust decision.** An attached device can
  present itself as any USB class, including a keyboard. A malicious or
  compromised server can therefore inject input into your machine. Only attach
  devices from servers you control.
- **The sudo rule is scoped but real.** The installer grants passwordless sudo
  for the `usbip` binary only (not blanket `NOPASSWD: ALL`), which is what lets
  the GUI attach devices without prompting. Combined with the point above, it
  means any local process that can invoke `usbip` can attach a remote device.

**Recommended deployment:** a trusted home or lab LAN, or across a VPN
(WireGuard/Tailscale). Do not port-forward 3240 to the internet.

## Project Structure

```
OpenUSBIP/
├── src/
│   ├── core/           # USB/IP wrapper, server discovery, config
│   ├── gui/            # Main window, widgets, system tray, workers
│   └── utils/          # Platform helpers, logging
├── tests/              # pytest suite (parsers, entry points)
├── resources/          # Icons
├── openusbip_client.py # Client entry point
├── openusbip_server.py # Server entry point
├── install-client.sh   # Linux client installer
├── install-server.sh   # Linux server installer
├── install-client.ps1  # Windows client installer
└── install-server.ps1  # Windows server installer
```

## Development

```bash
pip install -r requirements-dev.txt
pytest                      # run the suite
QT_QPA_PLATFORM=offscreen pytest   # headless
ruff check .                # lint
```

CI runs the suite on Python 3.10 and 3.12, byte-compiles the tree, and syntax
checks the installers.

## Troubleshooting

**`usbip: error: open vhci_driver`** — the client kernel module is not loaded:
`sudo modprobe vhci_hcd`.

**Server not appearing in the client** — mDNS may be blocked on your network.
Add the server manually with its IP; the client supports non-default ports.

**"Root privileges are required"** — the passwordless sudo rule is missing. Run
`./install-client.sh`, or add it yourself and validate with
`sudo visudo -c -f /etc/sudoers.d/usbip`.

## License

GPL-3.0 — see [LICENSE](LICENSE) for the full text and [NOTICE](NOTICE) for the
project's copyright notice.
