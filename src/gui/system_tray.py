"""
System Tray Integration for OpenUSBIP
Provides background operation and quick access
"""

import os
import sys

# Ensure the repository root is importable so `src.*` resolves consistently,
# whether this module is imported by an entry point or run directly.
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)


from PySide6.QtCore import QObject, Signal
from PySide6.QtGui import QAction, QIcon
from PySide6.QtWidgets import QMenu, QSystemTrayIcon

from src.core.server_discovery import USBIPServer
from src.core.usbip_wrapper import USBDevice
from src.utils.logger import setup_logger

logger = setup_logger(__name__)


class SystemTray(QObject):
    """System tray icon with quick access menu"""

    # Signals
    show_window = Signal()
    quit_app = Signal()
    attach_device = Signal(str, str)  # server_ip, busid
    detach_device = Signal(str)  # port

    def __init__(self, icon_path: str | None = None):
        super().__init__()

        # Create tray icon
        self.tray_icon = QSystemTrayIcon()

        # Set icon
        if icon_path and os.path.exists(icon_path):
            self.tray_icon.setIcon(QIcon(icon_path))
        else:
            # Use system USB icon as fallback
            self.tray_icon.setIcon(QIcon.fromTheme("drive-removable-media"))

        self.tray_icon.setToolTip("OpenUSBIP - USB over IP Client")

        # Create menu
        self.menu = QMenu()
        self.create_menu()

        self.tray_icon.setContextMenu(self.menu)

        # Connect signals
        self.tray_icon.activated.connect(self.on_tray_activated)

        # Storage for dynamic menu items
        self.servers: list[USBIPServer] = []
        self.devices: dict = {}  # server_ip -> [devices]

        logger.info("System tray initialized")

    def create_menu(self):
        """Create the tray menu"""
        # Show/Hide window
        self.show_action = QAction("Show Window", self)
        self.show_action.triggered.connect(self.show_window.emit)
        self.menu.addAction(self.show_action)

        self.menu.addSeparator()

        # Servers section (will be populated dynamically)
        self.servers_menu = QMenu("Servers", self.menu)
        self.menu.addMenu(self.servers_menu)

        # Attached devices section
        self.attached_menu = QMenu("Attached Devices", self.menu)
        self.menu.addMenu(self.attached_menu)

        self.menu.addSeparator()

        # Refresh
        refresh_action = QAction("🔄 Refresh", self)
        refresh_action.triggered.connect(lambda: self.update_servers(self.servers))
        self.menu.addAction(refresh_action)

        self.menu.addSeparator()

        # Quit
        quit_action = QAction("Quit", self)
        quit_action.triggered.connect(self.quit_app.emit)
        self.menu.addAction(quit_action)

    def on_tray_activated(self, reason):
        """Handle tray icon activation"""
        if reason == QSystemTrayIcon.ActivationReason.Trigger:
            # Single click - show window
            self.show_window.emit()
        elif reason == QSystemTrayIcon.ActivationReason.DoubleClick:
            # Double click - show window
            self.show_window.emit()

    def show(self):
        """Show the tray icon"""
        self.tray_icon.show()
        logger.info("System tray icon shown")

    def hide(self):
        """Hide the tray icon"""
        self.tray_icon.hide()

    def show_message(self, title: str, message: str, icon=QSystemTrayIcon.MessageIcon.Information, duration: int = 3000):
        """Show a notification message"""
        self.tray_icon.showMessage(title, message, icon, duration)
        logger.debug(f"Notification: {title} - {message}")

    def update_servers(self, servers: list[USBIPServer]):
        """Update the servers menu"""
        self.servers = servers
        self.servers_menu.clear()

        if not servers:
            no_servers = QAction("No servers found", self)
            no_servers.setEnabled(False)
            self.servers_menu.addAction(no_servers)
            return

        for server in servers:
            # Create submenu for each server
            server_menu = QMenu(f"🥧 {server.name}", self.servers_menu)

            # Server info
            info_action = QAction(f"IP: {server.ip_address}:{server.port}", server_menu)
            info_action.setEnabled(False)
            server_menu.addAction(info_action)

            server_menu.addSeparator()

            # Devices for this server
            if server.ip_address in self.devices and self.devices[server.ip_address]:
                for device in self.devices[server.ip_address]:
                    device_action = QAction(f"📎 {device.description[:30]}...", server_menu)
                    device_action.triggered.connect(
                        lambda checked, s=server.ip_address, d=device.busid:
                        self.attach_device.emit(s, d)
                    )
                    server_menu.addAction(device_action)
            else:
                no_devices = QAction("No devices available", server_menu)
                no_devices.setEnabled(False)
                server_menu.addAction(no_devices)

            self.servers_menu.addMenu(server_menu)

    def update_devices(self, server_ip: str, devices: list[USBDevice]):
        """Update devices for a specific server"""
        self.devices[server_ip] = devices
        self.update_servers(self.servers)

    def update_attached_devices(self, attached_devices: list[dict]):
        """Update the attached devices menu"""
        self.attached_menu.clear()

        if not attached_devices:
            no_attached = QAction("No devices attached", self)
            no_attached.setEnabled(False)
            self.attached_menu.addAction(no_attached)
            return

        for device_info in attached_devices:
            port = device_info.get('port', '?')
            busid = device_info.get('busid', '?')
            remote_host = device_info.get('remote_host', '?')

            device_action = QAction(f"🔗 {busid} from {remote_host}", self.attached_menu)
            device_action.triggered.connect(
                lambda checked, p=port: self.detach_device.emit(p)
            )
            self.attached_menu.addAction(device_action)

    def notify_device_attached(self, device: USBDevice, server: USBIPServer):
        """Show notification when device is attached"""
        self.show_message(
            "Device Attached",
            f"{device.description}\nfrom {server.name}",
            QSystemTrayIcon.MessageIcon.Information
        )

    def notify_device_detached(self, port: str):
        """Show notification when device is detached"""
        self.show_message(
            "Device Detached",
            f"Port {port} disconnected",
            QSystemTrayIcon.MessageIcon.Information
        )

    def notify_server_found(self, server: USBIPServer):
        """Show notification when new server is discovered"""
        self.show_message(
            "Server Discovered",
            f"{server.name} ({server.ip_address})",
            QSystemTrayIcon.MessageIcon.Information
        )

    def notify_error(self, title: str, message: str):
        """Show error notification"""
        self.show_message(
            title,
            message,
            QSystemTrayIcon.MessageIcon.Critical
        )


if __name__ == "__main__":
    from PySide6.QtWidgets import QApplication

    app = QApplication(sys.argv)

    # Test tray
    tray = SystemTray()
    tray.show()

    # Test notification
    tray.show_message("Test", "System tray is working!")

    # Test with fake servers
    from src.core.server_discovery import USBIPServer
    test_servers = [
        USBIPServer("TestPi1", "testpi1.local", "192.168.0.34", 3240, server_type="raspberry-pi"),
        USBIPServer("TestPi2", "testpi2.local", "192.168.0.35", 3240, server_type="raspberry-pi"),
    ]
    tray.update_servers(test_servers)

    sys.exit(app.exec())
