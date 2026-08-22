"""
Server List Widget
Displays discovered and manual USB/IP servers
"""

import os
import sys

# Ensure the repository root is importable so `src.*` resolves consistently,
# whether this module is imported by an entry point or run directly.
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QPushButton,
    QListWidget, QListWidgetItem, QLabel, QInputDialog,
    QMessageBox, QGroupBox
)
from PySide6.QtCore import Qt, Signal, QTimer
from PySide6.QtGui import QColor

from src.core.server_discovery import ServerDiscovery, USBIPServer
from src.core.usbip_wrapper import USBIPWrapper
from src.gui.workers import run_async
from src.utils.logger import setup_logger

logger = setup_logger(__name__)


class ServerListWidget(QWidget):
    """Widget for displaying and managing USB/IP servers"""
    
    # Signals
    server_selected = Signal(USBIPServer)
    
    def __init__(self, discovery: ServerDiscovery, usbip: USBIPWrapper, config=None):
        super().__init__()

        self.discovery = discovery
        self.usbip = usbip
        # Optional so the module's __main__ self-test can still construct one.
        self.config = config
        self.servers = {}  # ip_address -> USBIPServer
        
        self.init_ui()
        
        # Initial load
        self.refresh_servers()
    
    def init_ui(self):
        """Initialize UI"""
        layout = QVBoxLayout(self)
        
        # Group box
        group = QGroupBox("USB/IP Servers")
        group_layout = QVBoxLayout(group)
        
        # Header with count
        self.header_label = QLabel("Discovered: 0")
        self.header_label.setStyleSheet("font-weight: bold;")
        group_layout.addWidget(self.header_label)
        
        # Server list
        self.server_list = QListWidget()
        self.server_list.itemClicked.connect(self.on_server_clicked)
        self.server_list.itemDoubleClicked.connect(self.on_server_double_clicked)
        group_layout.addWidget(self.server_list)
        
        # Buttons
        button_layout = QHBoxLayout()
        
        self.add_btn = QPushButton("➕ Add Server")
        self.add_btn.clicked.connect(self.add_manual_server)
        button_layout.addWidget(self.add_btn)
        
        self.remove_btn = QPushButton("➖ Remove")
        self.remove_btn.clicked.connect(self.remove_selected_server)
        self.remove_btn.setEnabled(False)
        button_layout.addWidget(self.remove_btn)
        
        self.refresh_btn = QPushButton("🔄 Refresh")
        self.refresh_btn.clicked.connect(self.refresh_servers)
        button_layout.addWidget(self.refresh_btn)
        
        group_layout.addLayout(button_layout)
        
        layout.addWidget(group)
        
        # Status label
        self.status_label = QLabel("Auto-discovery active")
        self.status_label.setStyleSheet("color: #666; font-size: 10px;")
        layout.addWidget(self.status_label)
    
    def refresh_servers(self):
        """Refresh server list"""
        logger.debug("Refreshing server list")
        
        # Get servers from discovery
        discovered_servers = self.discovery.get_servers()
        
        # Update internal dict
        self.servers.clear()
        for server in discovered_servers:
            self.servers[server.ip_address] = server
        
        # Update UI
        self.update_server_list()
        
        # Update header
        self.header_label.setText(f"Servers: {len(self.servers)}")
    
    def _selected_ip(self):
        """Remember which server was selected across a rebuild."""
        item = self.server_list.currentItem()
        if not item:
            return None
        server = item.data(Qt.UserRole)
        return server.ip_address if server else None

    def update_server_list(self):
        """
        Update the server list widget.

        The refresh timer calls this every 5 seconds, so it must not silently
        drop the user's selection -- doing so previously made "Remove Server"
        unusable if you did not click it fast enough.
        """
        previously_selected = self._selected_ip()
        self.server_list.clear()

        for ip, server in sorted(self.servers.items()):
            # Create list item
            item = QListWidgetItem()
            
            # Format display text
            status_icon = "🟢" if server.is_online else "🔴"
            type_icon = self.get_server_type_icon(server.server_type)
            
            display_text = f"{status_icon} {type_icon} {server.name}\n    {server.ip_address}:{server.port}"
            
            if server.device_count > 0:
                display_text += f" ({server.device_count} devices)"
            
            item.setText(display_text)
            item.setData(Qt.UserRole, server)  # Store server object
            
            # Color based on status
            if not server.is_online:
                item.setForeground(QColor("#999999"))
            
            self.server_list.addItem(item)

            if server.ip_address == previously_selected:
                self.server_list.setCurrentItem(item)
                self.remove_btn.setEnabled(True)

    def get_server_type_icon(self, server_type: str) -> str:
        """Get icon for server type"""
        icons = {
            "raspberry-pi": "🥧",
            "linux": "🐧",
            "windows": "🪟",
            "manual": "📌",
            "unknown": "❓"
        }
        return icons.get(server_type, "❓")
    
    def on_server_clicked(self, item: QListWidgetItem):
        """Handle server click"""
        server = item.data(Qt.UserRole)
        if server:
            self.remove_btn.setEnabled(True)
            logger.debug(f"Server clicked: {server.name}")
    
    def on_server_double_clicked(self, item: QListWidgetItem):
        """Handle server double-click (select and load devices)"""
        server = item.data(Qt.UserRole)
        if server:
            logger.info(f"Server selected: {server.name}")
            
            # Already known good: select immediately.
            if server.is_online:
                self.server_selected.emit(server)
                return

            # Otherwise probe in the background; a dead host costs the full
            # socket timeout and must not freeze the UI.
            self.status_label.setText(f"Checking {server.name}...")
            self.setEnabled(False)
            run_async(
                self.discovery.check_server_online,
                server,
                on_result=lambda online, srv=server: self._on_online_checked(srv, online),
                on_error=lambda msg, srv=server: self._on_online_checked(srv, False),
            )

    def _on_online_checked(self, server: USBIPServer, online: bool):
        """Handle the result of a background reachability probe."""
        self.setEnabled(True)
        self.status_label.setText("Auto-discovery active")

        if online:
            server.is_online = True
            self.update_server_list()
            self.server_selected.emit(server)
            return

        QMessageBox.warning(
            self,
            "Server Offline",
            f"Server {server.name} ({server.ip_address}) is not responding.\n\n"
            "Please check:\n"
            "• Server is powered on\n"
            "• Network connection is working\n"
            "• USB/IP service is running on server"
        )
    
    def add_manual_server(self):
        """Add a server manually"""
        # Get IP address
        ip, ok = QInputDialog.getText(
            self,
            "Add Server",
            "Enter server IP address or hostname:"
        )
        
        if not ok or not ip:
            return
        
        ip = ip.strip()
        
        # Get port (optional)
        port, ok = QInputDialog.getInt(
            self,
            "Add Server",
            "Enter port number:",
            value=3240,
            min=1,
            max=65535
        )
        
        if not ok:
            port = 3240
        
        # Get name (optional)
        name, ok = QInputDialog.getText(
            self,
            "Add Server",
            "Enter server name (optional):",
            text=f"Server {ip}"
        )
        
        if not ok or not name:
            name = f"Server {ip}"
        
        # Add server
        try:
            server = self.discovery.add_manual_server(ip, port, name)
            self.servers[server.ip_address] = server
            self.update_server_list()
            
            logger.info(f"Manually added server: {server.name}")

            # Persist immediately so the server survives a restart even if the
            # app is killed rather than closed cleanly.
            if self.config:
                self.config.add_manual_server(server)
                self.config.save()

            # Probe reachability in the background so a wrong IP does not
            # freeze the dialog for the full socket timeout.
            run_async(
                self.discovery.check_server_online,
                server,
                on_result=lambda online, srv=server: self._on_manual_server_checked(srv, online),
                on_error=lambda msg, srv=server: self._on_manual_server_checked(srv, False),
            )

        except Exception as e:
            logger.error(f"Failed to add server: {e}")
            QMessageBox.critical(
                self,
                "Error",
                f"Failed to add server: {e}"
            )
    
    def _on_manual_server_checked(self, server: USBIPServer, online: bool):
        """Report whether a newly added manual server answered."""
        server.is_online = online
        self.update_server_list()

        if online:
            QMessageBox.information(
                self,
                "Server Added",
                f"Server {server.name} added successfully and is online!"
            )
        else:
            QMessageBox.warning(
                self,
                "Server Added",
                f"Server {server.name} added but is not responding.\n\n"
                "The server may be offline or USB/IP service is not running."
            )

    def remove_selected_server(self):
        """Remove selected server"""
        current_item = self.server_list.currentItem()
        if not current_item:
            return
        
        server = current_item.data(Qt.UserRole)
        if not server:
            return
        
        # Confirm removal
        reply = QMessageBox.question(
            self,
            "Remove Server",
            f"Remove server {server.name} ({server.ip_address})?",
            QMessageBox.Yes | QMessageBox.No
        )
        
        if reply == QMessageBox.Yes:
            # Remove from discovery
            self.discovery.remove_server(server.ip_address)
            
            # Remove from local dict
            if server.ip_address in self.servers:
                del self.servers[server.ip_address]

            if self.config:
                self.config.remove_manual_server(server.ip_address)
                self.config.save()
            
            # Update UI
            self.update_server_list()
            self.remove_btn.setEnabled(False)
            
            logger.info(f"Removed server: {server.name}")


if __name__ == "__main__":
    from PySide6.QtWidgets import QApplication
    
    app = QApplication(sys.argv)
    
    # Create test widget
    discovery = ServerDiscovery()
    usbip = USBIPWrapper()
    
    widget = ServerListWidget(discovery, usbip, config=None)
    widget.setWindowTitle("Server List Test")
    widget.resize(400, 500)
    widget.show()
    
    # Add some test servers
    discovery.add_manual_server("192.168.1.100", name="Test Pi 1")
    discovery.add_manual_server("192.168.1.101", name="Test Pi 2")
    widget.refresh_servers()
    
    sys.exit(app.exec())
