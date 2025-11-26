# System Tray Guide

## Overview

OpenUSBIP includes a system tray integration that allows you to:
- Run the application in the background
- Quickly attach/detach devices without opening the main window
- Receive desktop notifications for device events
- Access servers and devices from a convenient menu

## Features

### 🔔 Minimize to Tray
When you close the main window, OpenUSBIP minimizes to the system tray instead of quitting. This allows the application to continue running in the background and monitor for device changes.

**How it works**:
1. Click the X button on the main window
2. Window disappears and a notification appears
3. Application continues running in the background
4. Tray icon remains visible in the system tray

### 👆 Tray Icon Actions

#### Single Click
- **Action**: Show/restore the main window
- **Use case**: Quick access to the full GUI

#### Double Click
- **Action**: Show/restore the main window
- **Use case**: Alternative to single click

#### Right Click
- **Action**: Open the context menu
- **Use case**: Access quick actions and device management

## Tray Menu Structure

```
OpenUSBIP Tray Menu
├── Show Window
├── ──────────────
├── Servers ▶
│   ├── 🥧 raspberrypi
│   │   ├── IP: 192.168.0.34:3240
│   │   ├── ──────────────
│   │   ├── 📎 Logitech Unifying Receiver
│   │   ├── 📎 USB Flash Drive
│   │   └── 📎 Arduino Uno
│   └── 🥧 raspberrypi2
│       └── ...
├── Attached Devices ▶
│   ├── 🔗 1-1.4 from raspberrypi
│   └── 🔗 1-1.2 from raspberrypi
├── ──────────────
├── 🔄 Refresh
├── ──────────────
└── Quit
```

## Menu Items Explained

### Show Window
- **Action**: Restores the main window
- **Shortcut**: Single/double-click tray icon
- **Use case**: When you need full GUI access

### Servers Submenu
- **Shows**: All discovered and manually added servers
- **For each server**:
  - Server name (e.g., "raspberrypi")
  - IP address and port
  - List of available USB devices
- **Click a device**: Attaches it to your computer
- **Notification**: Shows when device is attached

### Attached Devices Submenu
- **Shows**: All currently attached USB devices
- **Format**: `busid from server_name`
- **Click a device**: Detaches it from your computer
- **Notification**: Shows when device is detached

### Refresh
- **Action**: Refreshes server list and device list
- **Use case**: Force update if auto-discovery is slow
- **Shortcut**: F5 in main window

### Quit
- **Action**: Completely closes the application
- **Note**: This is the only way to fully quit when tray is enabled
- **Shortcut**: Ctrl+Q in main window

## Notifications

OpenUSBIP shows desktop notifications for important events:

### Server Discovered
```
Title: Server Discovered
Message: raspberrypi (192.168.0.34)
Icon: Information
```

### Device Attached
```
Title: Device Attached
Message: Logitech Unifying Receiver
         from raspberrypi
Icon: Information
```

### Device Detached
```
Title: Device Detached
Message: Port 00 disconnected
Icon: Information
```

### Minimized to Tray
```
Title: OpenUSBIP
Message: Application minimized to tray.
         Right-click the tray icon to quit.
Icon: Information
```

### Error Notifications
```
Title: Attach Failed / Detach Failed
Message: [Error message]
Icon: Critical
```

## Usage Examples

### Example 1: Quick Device Attach
**Scenario**: You want to attach a USB device without opening the main window

**Steps**:
1. Right-click the tray icon
2. Hover over "Servers"
3. Hover over your server (e.g., "raspberrypi")
4. Click the device you want to attach
5. Wait for the notification confirming attachment

**Result**: Device is attached and ready to use

### Example 2: Quick Device Detach
**Scenario**: You're done using a USB device and want to detach it

**Steps**:
1. Right-click the tray icon
2. Hover over "Attached Devices"
3. Click the device you want to detach
4. Wait for the notification confirming detachment

**Result**: Device is detached and available on the server again

### Example 3: Background Operation
**Scenario**: You want OpenUSBIP to run in the background while you work

**Steps**:
1. Launch OpenUSBIP
2. Close the main window (click X)
3. Application minimizes to tray
4. Continue working - tray icon remains visible
5. When needed, click tray icon to restore window

**Result**: Application runs in background, ready when you need it

### Example 4: Monitoring Device Changes
**Scenario**: You want to be notified when new devices are available

**Steps**:
1. Launch OpenUSBIP and minimize to tray
2. Plug a USB device into your Raspberry Pi
3. Wait a few seconds
4. Right-click tray icon → Servers → Your server
5. New device appears in the list

**Result**: Real-time device detection without opening the main window

## Configuration

### Enable/Disable Tray
By default, the system tray is **enabled**. To disable it:

**Option 1: Command Line**
```bash
python3 openusbip_client.py --no-tray
```

**Option 2: Code**
```python
window = MainWindow(mode="client", enable_tray=False)
```

### Tray Icon
The tray icon is loaded from:
```
resources/icons/openusbip.svg
```

If the icon file is not found, OpenUSBIP uses the system's default USB icon.

## Troubleshooting

### Tray Icon Not Appearing
**Possible causes**:
- System tray not supported by your desktop environment
- Icon file missing

**Solutions**:
1. Check if your desktop environment supports system tray
2. Verify icon file exists: `ls resources/icons/openusbip.svg`
3. Check logs for errors: `grep -i tray ~/.openusbip/logs/openusbip.log`

### Notifications Not Showing
**Possible causes**:
- Desktop notifications disabled
- Notification daemon not running

**Solutions**:
1. Check system notification settings
2. Test with: `notify-send "Test" "Testing notifications"`
3. Install notification daemon if needed

### Tray Menu Empty
**Possible causes**:
- No servers discovered
- Server discovery disabled

**Solutions**:
1. Check if Raspberry Pi is running: `ping 192.168.0.34`
2. Check mDNS: `avahi-browse -t _usbip._tcp`
3. Add server manually from main window
4. Click "Refresh" in tray menu

### Can't Quit Application
**Solution**:
- Right-click tray icon → Quit
- Or use Ctrl+Q in main window
- Or kill process: `pkill -f openusbip_client`

## Tips & Tricks

### Tip 1: Keyboard Shortcuts
- **F5**: Refresh all data
- **Ctrl+Q**: Quit application
- **Ctrl+A**: Add server manually
- **Delete**: Remove selected server

### Tip 2: Quick Access
Set up a keyboard shortcut in your desktop environment to show/hide the OpenUSBIP window for even faster access.

### Tip 3: Auto-Start
Add OpenUSBIP to your startup applications to have it running in the tray when you log in:

```bash
# Add to startup applications
cp ~/.local/share/applications/openusbip-client.desktop ~/.config/autostart/
```

### Tip 4: Multiple Servers
The tray menu supports multiple servers. Each server gets its own submenu with its devices.

### Tip 5: Notification Duration
Notifications disappear after 3 seconds by default. This is configurable in the code:

```python
self.tray.show_message("Title", "Message", duration=5000)  # 5 seconds
```

## Advanced Usage

### Custom Tray Icon
Replace the default icon with your own:

```bash
cp /path/to/your/icon.svg ~/OpenUSBIP/resources/icons/openusbip.svg
```

### Disable Minimize to Tray
If you want closing the window to quit the application instead of minimizing:

Edit `src/gui/main_window.py` and change:
```python
def __init__(self, mode: str = "client", enable_tray: bool = True):
```
to:
```python
def __init__(self, mode: str = "client", enable_tray: bool = False):
```

### Custom Notifications
You can customize notification behavior in `src/gui/system_tray.py`:

```python
def show_message(self, title: str, message: str, 
                 icon=QSystemTrayIcon.MessageIcon.Information, 
                 duration: int = 3000):
    self.tray_icon.showMessage(title, message, icon, duration)
```

## Platform Support

### Linux
✅ **Fully Supported**
- GNOME Shell (with extension)
- KDE Plasma
- XFCE
- MATE
- Cinnamon

### Windows
🔨 **In Progress**
- System tray supported
- Notifications supported
- Testing in progress

### macOS
📋 **Planned**
- System tray supported by Qt
- Not yet tested

## See Also

- [Main README](../README.md) - Project overview
- [Testing Guide](../TESTING.md) - Testing checklist
- [Changelog](../CHANGELOG.md) - Version history
- [Installation Guide](../README.md#installation) - Setup instructions

## Feedback

If you have suggestions for improving the system tray integration, please open an issue on GitHub!
