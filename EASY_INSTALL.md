# 🚀 OpenUSBIP - Super Easy Installation

## For Raspberry Pi / Linux SBC Servers

### One-Command Install:

```bash
bash <(curl -sSL https://raw.githubusercontent.com/yourusername/openusbip/main/install-server.sh)
```

Or if you don't have curl:

```bash
bash <(wget -qO- https://raw.githubusercontent.com/yourusername/openusbip/main/install-server.sh)
```

### Or Manual Install:

```bash
# Download the installer
wget https://raw.githubusercontent.com/yourusername/openusbip/main/install-server.sh

# Make it executable
chmod +x install-server.sh

# Run it
./install-server.sh
```

### What It Does:

✅ Installs all dependencies (usbip, avahi, python3)  
✅ Downloads and configures the server  
✅ Creates systemd services (auto-start on boot)  
✅ Sets up USB hotplug detection  
✅ Configures mDNS advertisement  
✅ Starts everything automatically  

### After Installation:

1. **Plug in USB devices** - They auto-bind for sharing!
2. **On your desktop** - Run OpenUSBIP client
3. **Your Pi appears automatically** - No configuration needed!

---

## For Desktop Clients (Linux)

### Requirements:
- Ubuntu/Debian/Pop!_OS Linux
- Python 3.10+

### Install:

```bash
# Clone or download the project
cd ~
git clone https://github.com/yourusername/openusbip.git OpenUSBIP
cd OpenUSBIP

# Run setup
./setup.sh

# Install USB/IP client tools
sudo apt install linux-tools-generic

# Load kernel module
sudo modprobe vhci_hcd

# Configure passwordless sudo for usbip
sudo visudo
# Add this line:
# yourusername ALL=(ALL) NOPASSWD: /usr/sbin/usbip

# Launch the client
python3 openusbip_client.py
```

### Desktop Launcher:

A desktop icon is automatically created! Look for "OpenUSBIP Client" in your application menu.

---

## Quick Start Guide

### On Raspberry Pi:
```bash
# One command - that's it!
bash <(curl -sSL https://your-url/install-server.sh)
```

### On Desktop:
```bash
# Install and run
cd ~/OpenUSBIP
./setup.sh
python3 openusbip_client.py
```

### Use It:
1. Raspberry Pi appears in client automatically
2. Double-click to see USB devices
3. Click "Attach" to use them!

---

## Supported Platforms

### Servers:
- ✅ Raspberry Pi (all models)
- ✅ Raspberry Pi Zero
- ✅ Orange Pi
- ✅ Rock Pi
- ✅ Any Debian/Ubuntu Linux SBC

### Clients:
- ✅ Ubuntu Linux
- ✅ Debian Linux
- ✅ Pop!_OS
- ✅ Linux Mint
- ⏳ Windows (coming soon)

---

## Features

🥧 **Auto-Discovery** - Raspberry Pis appear automatically  
🔌 **Hotplug Support** - Plug in devices, they're instantly shared  
🚀 **Auto-Start** - Everything starts on boot  
🎨 **Beautiful GUI** - Professional Qt6 interface  
💾 **System Tray** - Runs in background  
🔔 **Notifications** - Alerts when devices connect  
🆓 **100% Free** - No licensing fees ever  

---

## Troubleshooting

### Server not appearing?
```bash
# Check if services are running
sudo systemctl status usbipd openusbip-server

# Check firewall
sudo ufw allow 3240/tcp

# Restart services
sudo systemctl restart usbipd openusbip-server
```

### Device won't attach?
```bash
# Check if device is bound
sudo usbip list -l

# Manually bind
sudo usbip bind -b <busid>
```

### Need help?
Check the full documentation in the project folder!

---

## Uninstall

### On Raspberry Pi:
```bash
# Stop and disable services
sudo systemctl stop usbipd openusbip-server usb-auto-bind
sudo systemctl disable usbipd openusbip-server usb-auto-bind

# Remove service files
sudo rm /etc/systemd/system/usbipd.service
sudo rm /etc/systemd/system/openusbip-server.service
sudo rm /etc/systemd/system/usb-auto-bind.service
sudo rm /etc/udev/rules.d/99-usbip-auto-bind.rules

# Remove installation
rm -rf ~/OpenUSBIP

# Reload systemd
sudo systemctl daemon-reload
```

---

## 🎉 That's It!

**Easiest USB/IP setup ever!**

One command on your Raspberry Pi, and you're sharing USB devices across your network!

