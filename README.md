# OpenUSBIP

**Open Source USB over IP - A Free Alternative to VirtualHere**

Share USB devices over your network with an easy-to-use GUI. Works on Windows, Linux, and Raspberry Pi.

## Features

- 🖥️ **Cross-Platform**: Windows, Linux, Raspberry Pi support
- 🎨 **Modern GUI**: Clean, intuitive Qt6 interface with PySide6
- 🔍 **Auto-Discovery**: Automatically find USB servers via mDNS
- 🔌 **Easy Setup**: One-click device attach/detach
- 💾 **Persistent Config**: Remember your servers and settings
- 🔔 **System Tray**: Minimize to tray with quick access menu
- ⚡ **Real-Time Updates**: Instant detection of device plug/unplug events
- 🔧 **One-Command Install**: Automated Raspberry Pi server setup
- 🆓 **100% Free**: Open source, no licensing fees

## Screenshots

### Main Application
- **Server Discovery**: Auto-discovers Raspberry Pi servers on your network
- **Device Management**: View and attach/detach USB devices with one click
- **Real-Time Updates**: See devices appear/disappear instantly

### System Tray Integration
- **Background Operation**: Minimize to system tray
- **Quick Access**: Right-click tray icon for quick attach/detach
- **Notifications**: Get notified when devices are attached/detached

## Quick Start

### Server Setup (Device Host)

**Raspberry Pi (Recommended - One Command Install):**
```bash
# Clone the repository
git clone https://github.com/yourusername/OpenUSBIP.git
cd OpenUSBIP

# Run the automated installer (sets up everything + auto-start)
sudo ./install-server.sh

# That's it! Server is now running and will auto-start on boot
```

**Linux (Manual Setup):**
```bash
# Install USB/IP kernel modules (usually pre-installed)
sudo modprobe usbip_host
sudo modprobe vhci_hcd

# Start the server
python3 openusbip_server.py
```

**Windows:**
```bash
# Install usbipd-win first: https://github.com/dorssel/usbipd-win
# Then run the GUI
python openusbip_server.py
```

### Client Setup (Device User)

**Linux/Raspberry Pi:**
```bash
# Install USB/IP kernel modules (usually pre-installed)
sudo modprobe vhci_hcd

# Run the client GUI
python3 openusbip_client.py
```

**Windows:**
```bash
# Install usbip-win2 drivers first
# Then run the GUI
python openusbip_client.py
```

## Installation

### Prerequisites

**All Platforms:**
- Python 3.10 or newer
- PySide6 (Qt6)

**Linux/Raspberry Pi:**
- USB/IP kernel modules (built into most modern kernels)
- `usbip` command-line tools

**Windows:**
- [usbipd-win](https://github.com/dorssel/usbipd-win) (for server)
- [usbip-win2](https://github.com/vadimgrn/usbip-win2) (for client)

### Install Dependencies

```bash
pip install -r requirements.txt
```

## Architecture

```
┌─────────────────────────────────────────┐
│         OpenUSBIP GUI Application       │
├─────────────────────────────────────────┤
│  Server Mode          │  Client Mode    │
│  - Share USB devices  │  - Connect to   │
│  - Auto-discovery     │    remote USB   │
│  - Device management  │  - Auto-connect │
└─────────────────────────────────────────┘
           │                    │
           ▼                    ▼
┌──────────────────┐  ┌──────────────────┐
│  USB/IP Server   │  │  USB/IP Client   │
│  (usbipd)        │  │  (usbip)         │
└──────────────────┘  └──────────────────┘
```

## Project Structure

```
OpenUSBIP/
├── src/
│   ├── core/
│   │   ├── usbip_wrapper.py      # USB/IP command wrapper
│   │   ├── device_manager.py     # Device enumeration & management
│   │   ├── server_discovery.py   # mDNS auto-discovery
│   │   └── config_manager.py     # Persistent configuration
│   ├── gui/
│   │   ├── main_window.py        # Main application window
│   │   ├── server_widget.py      # Server list widget
│   │   ├── device_widget.py      # Device list widget
│   │   ├── settings_dialog.py    # Settings dialog
│   │   └── system_tray.py        # System tray integration
│   └── utils/
│       ├── platform_utils.py     # Platform-specific helpers
│       └── logger.py             # Logging configuration
├── resources/
│   ├── icons/                    # Application icons
│   └── styles/                   # Qt stylesheets
├── tests/
│   └── test_*.py                 # Unit tests
├── openusbip_client.py           # Client entry point
├── openusbip_server.py           # Server entry point
├── requirements.txt              # Python dependencies
├── setup.py                      # Installation script
└── README.md                     # This file
```

## Current Status

**✅ Fully Working (v0.1.0):**
- ✅ Raspberry Pi server with auto-start services
- ✅ Desktop client with GUI launcher
- ✅ mDNS auto-discovery of servers
- ✅ Real-time device detection (hotplug support)
- ✅ One-click attach/detach from GUI
- ✅ System tray integration with quick access menu
- ✅ Desktop notifications for device events
- ✅ Passwordless sudo configuration for usbip
- ✅ USB auto-binding on Raspberry Pi
- ✅ One-command automated installer

**🔨 In Progress:**
- 🔨 Windows client/server support
- 🔨 Auto-attach rules
- 🔨 AppImage/DEB packaging

**📋 Planned:**
- 📋 Multiple server support in GUI
- 📋 Device filtering and search
- 📋 Connection history
- 📋 Bandwidth monitoring

## Supported Devices

✅ **Well Supported:**
- USB Storage (flash drives, external HDDs)
- HID devices (keyboards, mice, game controllers)
- Serial devices (Arduino, USB-to-serial adapters)
- Printers

⚠️ **Limited Support:**
- Webcams (may have latency issues)
- Audio devices (timing-sensitive)
- High-bandwidth devices

❌ **Not Supported:**
- Video capture cards
- USB 3.0+ devices requiring high bandwidth
- Devices with strict timing requirements

## License

GPL-3.0 License - See LICENSE file for details

This project uses:
- USB/IP kernel module (GPL-2.0)
- usbipd-win (GPL-3.0)
- PySide6 (LGPL-3.0)

## Contributing

Contributions welcome! This is a personal project made open source for the community.

## Acknowledgments

- Linux USB/IP kernel developers
- [usbipd-win](https://github.com/dorssel/usbipd-win) by @dorssel
- [usbip-win2](https://github.com/vadimgrn/usbip-win2) by @vadimgrn
- [usb-audio-ip-client](https://github.com/seastwood/usb-audio-ip-client) for GUI inspiration

## Disclaimer

This is a personal project for educational and personal use. Not affiliated with VirtualHere or any commercial USB/IP solutions.
