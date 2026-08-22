"""
Main Window for OpenUSBIP
Cross-platform GUI for USB/IP client and server
"""

import sys
import os
from typing import Optional

# Ensure the repository root is importable so `src.*` resolves consistently,
# whether this module is imported by an entry point or run directly.
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from PySide6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QPushButton, QLabel, QStatusBar, QMessageBox, QSplitter
)
from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QAction, QIcon

from src.core.usbip_wrapper import USBIPWrapper
from src.core.server_discovery import ServerDiscovery, USBIPServer
from src.core.config_manager import ConfigManager
from src.utils.logger import setup_logger
from src.utils.platform_utils import get_platform, check_usbip_installed, is_admin

from src.gui.server_widget import ServerListWidget
from src.gui.device_widget import DeviceListWidget
from src.gui.system_tray import SystemTray
from src.gui.workers import run_async, wait_for_workers

logger = setup_logger(__name__)


class MainWindow(QMainWindow):
    """Main application window"""

    def __init__(self, mode: str = "client", enable_tray: bool = True):
        super().__init__()
        
        self.mode = mode  # "client" or "server"
        self.current_server: Optional[USBIPServer] = None
        self.enable_tray = enable_tray

        # Set when the user explicitly chooses Quit, so closeEvent knows to
        # actually exit instead of minimising back to the tray.
        self._really_quit = False
        
        # Core components
        self.config = ConfigManager()
        self.usbip = USBIPWrapper()
        self.discovery = ServerDiscovery()

        # Re-add servers the user saved in a previous session. Without this
        # every manually added server was lost on exit.
        self._restore_saved_servers()
        
        # System tray
        self.tray = None
        if self.enable_tray:
            icon_path = os.path.join(os.path.dirname(__file__), '../../resources/icons/openusbip.svg')
            self.tray = SystemTray(icon_path)
            self.setup_tray_connections()
        
        # Check if USB/IP is available
        if not self.usbip.is_available():
            installed, msg = check_usbip_installed()
            QMessageBox.critical(
                self,
                "USB/IP Not Found",
                f"{msg}\n\nPlease install USB/IP tools and restart the application."
            )
        
        # Setup UI
        self.init_ui()
        self._restore_geometry()
        
        # Start discovery if in client mode
        if self.mode == "client":
            self.start_discovery()
        
        # Setup refresh timer
        self.refresh_timer = QTimer()
        self.refresh_timer.timeout.connect(self.refresh_data)
        self.refresh_timer.start(5000)  # Refresh every 5 seconds
        
        # Show tray icon
        if self.tray:
            self.tray.show()
    
    def _restore_saved_servers(self):
        """Load manually added servers from the config file into discovery."""
        try:
            saved = self.config.get_manual_servers()
        except Exception as exc:  # noqa: BLE001 - a bad config must not block startup
            logger.error(f"Could not read saved servers: {exc}")
            return

        for server in saved:
            self.discovery.add_manual_server(
                server.ip_address, server.port, server.name
            )
        if saved:
            logger.info(f"Restored {len(saved)} saved server(s) from config")

    def _restore_geometry(self):
        """Restore the window position and size from the previous session."""
        x, y, width, height = self.config.get_window_geometry()
        if width and height:
            self.resize(width, height)
        if x is not None and y is not None:
            self.move(x, y)

    def _persist_state(self):
        """Write window geometry and server list back to disk."""
        try:
            geo = self.geometry()
            self.config.set_window_geometry(
                geo.x(), geo.y(), geo.width(), geo.height()
            )
            self.config.save()
        except Exception as exc:  # noqa: BLE001 - never block shutdown on config
            logger.error(f"Failed to save configuration: {exc}")

    def setup_tray_connections(self):
        """Setup system tray signal connections"""
        if not self.tray:
            return
        
        # Connect tray signals
        self.tray.show_window.connect(self.show_from_tray)
        self.tray.quit_app.connect(self.quit_application)
        self.tray.attach_device.connect(self.attach_device_from_tray)
        self.tray.detach_device.connect(self.detach_device_from_tray)
    
    def init_ui(self):
        """Initialize the user interface"""
        self.setWindowTitle(f"OpenUSBIP - {self.mode.capitalize()} Mode")
        self.setMinimumSize(900, 600)
        
        # Create central widget
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        
        # Main layout
        main_layout = QVBoxLayout(central_widget)
        
        # Header
        header = self.create_header()
        main_layout.addWidget(header)
        
        # Content area (splitter for server list and device list)
        splitter = QSplitter(Qt.Orientation.Horizontal)
        
        # Server list (left side)
        self.server_widget = ServerListWidget(self.discovery, self.usbip, self.config)
        self.server_widget.server_selected.connect(self.on_server_selected)
        splitter.addWidget(self.server_widget)
        
        # Device list (right side)
        self.device_widget = DeviceListWidget(self.usbip)
        # Reuse the widget's fetches for the tray rather than issuing a second
        # identical usbip call for every refresh.
        self.device_widget.devices_loaded.connect(self._on_devices_loaded)
        self.device_widget.attached_loaded.connect(self._on_attached_loaded)
        splitter.addWidget(self.device_widget)
        
        # Create menu bar AFTER widgets are created
        self.create_menu_bar()
        
        # Set splitter sizes (30% servers, 70% devices)
        splitter.setSizes([300, 600])
        
        main_layout.addWidget(splitter)
        
        # Status bar
        self.status_bar = QStatusBar()
        self.setStatusBar(self.status_bar)
        self.update_status("Ready")
        
        # Apply styling
        self.apply_styles()
    
    def create_menu_bar(self):
        """Create application menu bar"""
        menubar = self.menuBar()
        
        # File menu
        file_menu = menubar.addMenu("&File")
        
        refresh_action = QAction("&Refresh", self)
        refresh_action.setShortcut("F5")
        refresh_action.triggered.connect(self.refresh_data)
        file_menu.addAction(refresh_action)
        
        file_menu.addSeparator()
        
        exit_action = QAction("E&xit", self)
        exit_action.setShortcut("Ctrl+Q")
        # Exit must actually exit, not bounce to the tray like closing the window does.
        exit_action.triggered.connect(self.quit_application)
        file_menu.addAction(exit_action)
        
        # Servers menu
        servers_menu = menubar.addMenu("&Servers")
        
        add_server_action = QAction("&Add Server...", self)
        add_server_action.setShortcut("Ctrl+A")
        add_server_action.triggered.connect(self.server_widget.add_manual_server)
        servers_menu.addAction(add_server_action)
        
        remove_server_action = QAction("&Remove Server", self)
        remove_server_action.setShortcut("Delete")
        remove_server_action.triggered.connect(self.server_widget.remove_selected_server)
        servers_menu.addAction(remove_server_action)
        
        servers_menu.addSeparator()
        
        toggle_discovery_action = QAction("Toggle Auto-&Discovery", self)
        toggle_discovery_action.setCheckable(True)
        toggle_discovery_action.setChecked(True)
        toggle_discovery_action.triggered.connect(self.toggle_discovery)
        servers_menu.addAction(toggle_discovery_action)
        
        # Help menu
        help_menu = menubar.addMenu("&Help")
        
        about_action = QAction("&About", self)
        about_action.triggered.connect(self.show_about)
        help_menu.addAction(about_action)
        
        check_status_action = QAction("Check &Status", self)
        check_status_action.triggered.connect(self.check_system_status)
        help_menu.addAction(check_status_action)
    
    def create_header(self) -> QWidget:
        """Create header widget with title and controls"""
        header = QWidget()
        header_layout = QHBoxLayout(header)
        
        # Title
        title = QLabel(f"<h2>OpenUSBIP - {self.mode.capitalize()} Mode</h2>")
        header_layout.addWidget(title)
        
        header_layout.addStretch()
        
        # Refresh button
        refresh_btn = QPushButton("🔄 Refresh")
        refresh_btn.clicked.connect(self.refresh_data)
        header_layout.addWidget(refresh_btn)
        
        # Admin status
        if is_admin():
            admin_label = QLabel("🔑 Admin")
            admin_label.setStyleSheet("color: green; font-weight: bold;")
        else:
            admin_label = QLabel("⚠️ Not Admin")
            admin_label.setStyleSheet("color: orange; font-weight: bold;")
            admin_label.setToolTip("Some operations require administrator privileges")
        header_layout.addWidget(admin_label)
        
        return header
    
    def start_discovery(self):
        """Start server discovery"""
        if self.discovery.start(self.on_server_discovered):
            logger.info("Server discovery started")
            self.update_status("Discovering servers...")
        else:
            logger.warning("Server discovery not available (zeroconf not installed)")
            self.update_status("Auto-discovery disabled - add servers manually")
    
    def on_server_discovered(self, server: USBIPServer):
        """Called when a server is discovered"""
        logger.info(f"Server discovered: {server.display_name}")
        self.server_widget.refresh_servers()
        
        # Update tray menu
        if self.tray:
            self.tray.update_servers(self.discovery.get_servers())
            self.tray.notify_server_found(server)
    
    def on_server_selected(self, server: USBIPServer):
        """Called when a server is selected"""
        self.current_server = server
        logger.info(f"Server selected: {server.display_name}")
        self.update_status(f"Connected to: {server.display_name}")
        
        # Load devices from this server. The tray is updated from the
        # devices_loaded signal, so no second lookup is needed here.
        self.device_widget.load_devices(server)
    
    def refresh_data(self):
        """Refresh all data"""
        logger.debug("Refreshing data...")
        
        # Refresh server list
        self.server_widget.refresh_servers()
        
        # Refresh device list if a server is selected
        if self.current_server:
            self.device_widget.load_devices(self.current_server)
        
        # Refresh the attached list; the tray follows via attached_loaded.
        self.device_widget.refresh_attached_devices()

        if self.tray:
            self.tray.update_servers(self.discovery.get_servers())

        self.update_status("Refreshed")

    def _on_devices_loaded(self, server_ip: str, devices: list):
        """Mirror a completed remote-device fetch into the tray menu."""
        if self.tray and devices:
            self.tray.update_devices(server_ip, devices)

    def _on_attached_loaded(self, attached: list):
        """Mirror a completed attached-device fetch into the tray menu."""
        if self.tray:
            self.tray.update_attached_devices(attached)
    
    def toggle_discovery(self, enabled: bool):
        """Toggle auto-discovery on/off"""
        if enabled:
            self.start_discovery()
        else:
            self.discovery.stop()
            logger.info("Server discovery stopped")
            self.update_status("Auto-discovery disabled")
    
    def update_status(self, message: str):
        """Update status bar message"""
        self.status_bar.showMessage(message)
    
    def check_system_status(self):
        """Check and display system status"""
        platform = get_platform()
        installed, msg = check_usbip_installed()
        admin = is_admin()
        
        status_text = f"""
<h3>System Status</h3>
<p><b>Platform:</b> {platform.value}</p>
<p><b>USB/IP Installed:</b> {'✅ Yes' if installed else '❌ No'}</p>
<p><b>Message:</b> {msg}</p>
<p><b>Admin/Root:</b> {'✅ Yes' if admin else '❌ No'}</p>
<p><b>Discovery Active:</b> {'✅ Yes' if self.discovery.is_running else '❌ No'}</p>
<p><b>Servers Found:</b> {len(self.discovery.get_servers())}</p>
        """
        
        QMessageBox.information(self, "System Status", status_text)
    
    def show_about(self):
        """Show about dialog"""
        about_text = """
<h2>OpenUSBIP</h2>
<p><b>Version:</b> 0.1.0</p>
<p><b>Description:</b> Open Source USB over IP</p>
<p>A free alternative to VirtualHere for personal use.</p>
<br>
<p><b>Features:</b></p>
<ul>
<li>Cross-platform (Windows, Linux, Raspberry Pi)</li>
<li>Auto-discovery of servers</li>
<li>Easy device sharing</li>
<li>Free and open source (GPL-3.0)</li>
</ul>
<br>
<p><b>Project:</b> <a href="https://github.com/ameggs/OpenUSBIP">GitHub</a></p>
<p><b>License:</b> GPL-3.0</p>
        """
        
        QMessageBox.about(self, "About OpenUSBIP", about_text)
    
    def apply_styles(self):
        """Apply custom styles to the application"""
        self.setStyleSheet("""
            QMainWindow {
                background-color: #f5f5f5;
            }
            
            QPushButton {
                background-color: #4CAF50;
                color: white;
                border: none;
                padding: 8px 16px;
                border-radius: 4px;
                font-weight: bold;
            }
            
            QPushButton:hover {
                background-color: #45a049;
            }
            
            QPushButton:pressed {
                background-color: #3d8b40;
            }
            
            QPushButton:disabled {
                background-color: #cccccc;
                color: #666666;
            }
            
            QStatusBar {
                background-color: #e0e0e0;
                color: #333333;
            }
            
            QLabel {
                color: #333333;
            }
        """)
    
    def show_from_tray(self):
        """Show window from system tray"""
        self.show()
        self.activateWindow()
        self.raise_()
        logger.debug("Window shown from tray")
    
    def quit_application(self):
        """Quit the application completely"""
        logger.info("Quitting application from tray")

        self._really_quit = True

        # Tear down background work before the event loop stops, otherwise
        # the zeroconf thread can keep the process alive.
        if self.discovery.is_running:
            self.discovery.stop()
        self.refresh_timer.stop()

        # Hide the tray icon so closeEvent cannot bounce us back into it.
        if self.tray:
            self.tray.hide()

        self._persist_state()

        # Let in-flight background work finish before Qt tears down, otherwise
        # pool threads emit into destroyed objects.
        wait_for_workers()

        self.close()
        QApplication.quit()
    
    def attach_device_from_tray(self, server_ip: str, busid: str):
        """Attach a device from the system tray menu"""
        logger.info(f"Attaching device {busid} from {server_ip} via tray")
        
        # Find the server
        server = None
        for s in self.discovery.get_servers():
            if s.ip_address == server_ip:
                server = s
                break
        
        if not server:
            logger.error(f"Server {server_ip} not found")
            if self.tray:
                self.tray.notify_error("Attach Failed", f"Server {server_ip} not found")
            return
        
        # Attach in the background: this shells out to usbip and can block.
        run_async(
            self.usbip.attach_device,
            server_ip,
            busid,
            server.port,
            on_result=lambda outcome, b=busid, srv=server: self._on_tray_attach(outcome, b, srv),
            on_error=lambda msg, b=busid, srv=server: self._on_tray_attach((False, msg), b, srv),
        )

    def _on_tray_attach(self, outcome, busid: str, server: USBIPServer):
        """Report the result of a tray-initiated attach."""
        success, message = outcome
        if success:
            logger.info(f"Device {busid} attached successfully")
            if self.tray:
                self.tray.show_message("Device Attached", f"{busid} from {server.name}")
            self.refresh_data()
        else:
            logger.error(f"Failed to attach device {busid}: {message}")
            if self.tray:
                self.tray.notify_error("Attach Failed", message)
    
    def detach_device_from_tray(self, port: str):
        """Detach a device from the system tray menu"""
        logger.info(f"Detaching device on port {port} via tray")
        
        run_async(
            self.usbip.detach_device,
            port,
            on_result=lambda outcome, pt=port: self._on_tray_detach(outcome, pt),
            on_error=lambda msg, pt=port: self._on_tray_detach((False, msg), pt),
        )

    def _on_tray_detach(self, outcome, port: str):
        """Report the result of a tray-initiated detach."""
        success, message = outcome
        if success:
            logger.info(f"Device on port {port} detached successfully")
            if self.tray:
                self.tray.show_message("Device Detached", f"Port {port} disconnected")
            self.refresh_data()
        else:
            logger.error(f"Failed to detach device on port {port}: {message}")
            if self.tray:
                self.tray.notify_error("Detach Failed", message)
    
    def closeEvent(self, event):
        """Handle window close event"""
        # If tray is enabled, minimize to tray instead of closing -- unless the
        # user picked Quit, in which case fall through and actually exit.
        if not self._really_quit and self.tray and self.tray.tray_icon.isVisible():
            event.ignore()
            self.hide()
            if self.tray:
                self.tray.show_message(
                    "OpenUSBIP",
                    "Application minimized to tray. Right-click the tray icon to quit.",
                    duration=2000
                )
            logger.info("Application minimized to tray")
        else:
            # Actually close the application
            # Stop discovery
            if self.discovery.is_running:
                self.discovery.stop()
            
            # Stop refresh timer
            self.refresh_timer.stop()

            self._persist_state()

            logger.info("Application closing")
            event.accept()


if __name__ == "__main__":
    app = QApplication(sys.argv)
    app.setApplicationName("OpenUSBIP")
    
    window = MainWindow(mode="client")
    window.show()
    
    sys.exit(app.exec())
