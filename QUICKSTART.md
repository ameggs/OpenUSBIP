# OpenUSBIP - Quick Start Guide

## What Is This?

**OpenUSBIP** is a free, open-source alternative to VirtualHere for sharing USB devices over your network.

Perfect for:
- Sharing USB devices from Raspberry Pi to your desktop
- Accessing USB devices remotely over LAN
- Multiple USB servers (Raspberry Pi, Linux SBCs) feeding to any client

## Installation

### 1. Install Dependencies

```bash
cd ~/OpenUSBIP
./install-client.sh
```

This installs:
- PySide6 (Qt6 GUI framework)
- zeroconf (mDNS auto-discovery)
- Other Python dependencies

### 2. Platform-Specific Setup

**Raspberry Pi / Linux Server:**
```bash
# Install USB/IP tools
sudo apt install linux-tools-generic hwdata  # Ubuntu/Debian
# or
sudo dnf install usbip  # Fedora

# Load kernel modules
sudo modprobe usbip_host
sudo modprobe vhci_hcd

# Make permanent (load on boot)
echo "usbip_host" | sudo tee -a /etc/modules
echo "vhci_hcd" | sudo tee -a /etc/modules

# Start usbipd service
sudo usbipd -D
```

**Windows Client:**
```powershell
# Install usbipd-win (for server mode)
winget install usbipd

# Install usbip-win2 (for client mode)
# Download from: https://github.com/vadimgrn/usbip-win2/releases
```

**Linux Client:**
```bash
# Install USB/IP tools (same as server)
sudo apt install linux-tools-generic

# Load kernel module
sudo modprobe vhci_hcd
```

## Usage

### Running the Client

```bash
python3 openusbip_client.py
```

The client will:
1. Auto-discover Raspberry Pi servers on your network
2. Show available USB devices
3. Let you attach/detach with one click

### Running the Server (Raspberry Pi)

```bash
python3 openusbip_server.py
```

The server will:
1. Advertise itself via mDNS
2. Share USB devices
3. Show which devices are connected

## Typical Setup

### Scenario: Raspberry Pi with USB devices → Windows/Linux Desktop

**On Raspberry Pi:**
```bash
# One-time setup
sudo apt update
sudo apt install linux-tools-generic avahi-daemon
sudo modprobe usbip_host
sudo usbipd -D

# Run OpenUSBIP server
cd ~/OpenUSBIP
python3 openusbip_server.py
```

**On Desktop (Windows or Linux):**
```bash
# Run OpenUSBIP client
cd ~/OpenUSBIP
python3 openusbip_client.py

# The Raspberry Pi should appear automatically!
# Click on a device and click "Attach"
```

## Network Requirements

- **Port 3240** must be open (USB/IP default port)
- **mDNS/Avahi** for auto-discovery (optional, can add servers manually)
- **Same subnet** recommended (works across subnets with routing)

## Troubleshooting

### Server not discovered?

1. **Check firewall:**
   ```bash
   # Raspberry Pi
   sudo ufw allow 3240/tcp
   ```

2. **Add server manually:**
   - In client GUI, click "Add Server"
   - Enter Raspberry Pi IP address
   - Port: 3240

3. **Check usbipd is running:**
   ```bash
   # On Raspberry Pi
   sudo systemctl status usbipd
   # or
   ps aux | grep usbipd
   ```

### Device won't attach?

1. **Check device is shared:**
   ```bash
   # On Raspberry Pi
   usbip list -l
   ```

2. **Try with sudo:**
   ```bash
   # On client
   sudo usbip attach -r <raspberry-pi-ip> -b <busid>
   ```

3. **Check kernel modules:**
   ```bash
   lsmod | grep usbip
   lsmod | grep vhci
   ```

### Permission errors?

**Linux:**
```bash
# Add udev rule for non-root access
echo 'SUBSYSTEM=="usbip", MODE="0666"' | sudo tee /etc/udev/rules.d/99-usbip.rules
sudo udevadm control --reload-rules
```

**Or use sudo:**
```bash
# Edit sudoers (CAREFUL!)
sudo visudo

# Add this line (replace <username>):
<username> ALL=(ALL) NOPASSWD: /usr/sbin/usbip
```

## What's Working Now

✅ Core USB/IP wrapper (list, attach, detach devices)
✅ Server auto-discovery (mDNS)
✅ Platform detection (Windows, Linux, Raspberry Pi)
✅ Logging and error handling

## What's Next

🔨 GUI components (in progress)
🔨 Configuration management
🔨 System tray integration
🔨 Installers/packages

## Current Status

The **core functionality is complete** but the **GUI is not yet built**.

You can test the core functionality from Python:

```python
import sys
sys.path.insert(0, 'src')

# Test USB/IP wrapper
from core.usbip_wrapper import USBIPWrapper

wrapper = USBIPWrapper()
print(f"USB/IP available: {wrapper.is_available()}")

# List local devices (server mode)
devices = wrapper.list_local_devices()
for device in devices:
    print(device)

# List remote devices (client mode)
devices = wrapper.list_remote_devices("192.168.1.100")
for device in devices:
    print(device)

# Attach a device
success, msg = wrapper.attach_device("192.168.1.100", "1-1")
print(msg)
```

## Need Help?

Check `PROJECT_STATUS.md` for detailed project status and next steps.

## License

GPL-3.0 - Free for personal use
