# OpenUSBIP

**Open Source USB over IP** — A free, cross-platform alternative to VirtualHere for sharing USB devices over your network.

Share USB devices between machines with an easy-to-use GUI. Works on Windows, Linux, and Raspberry Pi.

## Features

- **Cross-Platform** — Windows, Linux, Raspberry Pi support
- **Modern GUI** — Clean Qt6 interface built with PySide6
- **Auto-Discovery** — Automatically find USB/IP servers on your network via mDNS
- **One-Click Operations** — Attach/detach remote USB devices with a single click
- **System Tray** — Minimize to tray with quick-access menu
- **Real-Time Updates** — Instant detection of device plug/unplug events
- **Server Mode** — Share locally connected USB devices over the network
- **Client Mode** — Connect to and use remote USB devices
- **100% Free** — Open source (GPL-3.0), no licensing fees

## Quick Start

### Server (Share USB Devices)

**Raspberry Pi / Linux:**
```bash
git clone https://github.com/ameggs/OpenUSBIP.git
cd OpenUSBIP
sudo ./install-server.sh
```

**Manual start:**
```bash
sudo modprobe usbip_host
python3 openusbip_server.py
```

### Client (Use Remote USB Devices)

```bash
pip install -r requirements.txt
python3 openusbip_client.py
```

## Requirements

- **Python** 3.10+
- **PySide6** (Qt6 bindings)
- **USB/IP tools** — `usbip` / `usbipd` (platform-specific)

## Project Structure

```
OpenUSBIP/
├── src/
│   ├── core/           # USB/IP wrapper, server discovery, config
│   ├── gui/            # Main window, widgets, system tray
│   └── utils/          # Platform helpers, logging
├── resources/          # Icons, styles
├── openusbip_client.py # Client entry point
├── openusbip_server.py # Server entry point
└── requirements.txt    # Python dependencies
```

## License

GPL-3.0 — see [LICENSE](LICENSE)
