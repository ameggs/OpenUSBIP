"""
Device List Widget
Displays USB devices from selected server and allows attach/detach
"""

import os
import sys

# Ensure the repository root is importable so `src.*` resolves consistently,
# whether this module is imported by an entry point or run directly.
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)


from PySide6.QtCore import Signal
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (
    QAbstractItemView,
    QGroupBox,
    QHeaderView,
    QLabel,
    QMessageBox,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from src.core.server_discovery import USBIPServer
from src.core.usbip_wrapper import DeviceState, USBDevice, USBIPWrapper
from src.gui.workers import run_async
from src.utils.logger import setup_logger

logger = setup_logger(__name__)


class DeviceListWidget(QWidget):
    """Widget for displaying and managing USB devices"""

    # Emitted whenever a remote device list arrives, so MainWindow can update
    # the tray without issuing a second identical usbip call.
    devices_loaded = Signal(str, list)  # server_ip, devices
    attached_loaded = Signal(list)

    def __init__(self, usbip: USBIPWrapper):
        super().__init__()

        self.usbip = usbip
        self.current_server: USBIPServer | None = None
        self.devices: list[USBDevice] = []
        self.attached_devices = []

        # Guards against the 5s refresh timer stacking up requests when a
        # server is slow: a call can outlive the interval that triggered it.
        self._loading_devices = False
        self._loading_attached = False

        self.init_ui()

    def init_ui(self):
        """Initialize UI"""
        layout = QVBoxLayout(self)

        # Available devices section
        available_group = QGroupBox("Available USB Devices")
        available_layout = QVBoxLayout(available_group)

        # Header
        self.available_header = QLabel("Select a server to view devices")
        self.available_header.setStyleSheet("font-weight: bold; color: #666;")
        available_layout.addWidget(self.available_header)

        # Device table
        self.device_table = QTableWidget()
        self.device_table.setColumnCount(5)
        self.device_table.setHorizontalHeaderLabels([
            "Bus ID", "Vendor:Product", "Description", "Status", "Action"
        ])
        self.device_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.device_table.setSelectionMode(QAbstractItemView.SingleSelection)
        self.device_table.setEditTriggers(QAbstractItemView.NoEditTriggers)

        # Resize columns
        header = self.device_table.horizontalHeader()
        header.setSectionResizeMode(0, QHeaderView.ResizeToContents)
        header.setSectionResizeMode(1, QHeaderView.ResizeToContents)
        header.setSectionResizeMode(2, QHeaderView.Stretch)
        header.setSectionResizeMode(3, QHeaderView.ResizeToContents)
        header.setSectionResizeMode(4, QHeaderView.ResizeToContents)

        available_layout.addWidget(self.device_table)

        layout.addWidget(available_group)

        # Attached devices section
        attached_group = QGroupBox("Attached Devices (This Computer)")
        attached_layout = QVBoxLayout(attached_group)

        # Attached devices table
        self.attached_table = QTableWidget()
        self.attached_table.setColumnCount(4)
        self.attached_table.setHorizontalHeaderLabels([
            "Port", "Bus ID", "Remote Server", "Action"
        ])
        self.attached_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.attached_table.setSelectionMode(QAbstractItemView.SingleSelection)
        self.attached_table.setEditTriggers(QAbstractItemView.NoEditTriggers)

        # Resize columns
        header2 = self.attached_table.horizontalHeader()
        header2.setSectionResizeMode(0, QHeaderView.ResizeToContents)
        header2.setSectionResizeMode(1, QHeaderView.ResizeToContents)
        header2.setSectionResizeMode(2, QHeaderView.Stretch)
        header2.setSectionResizeMode(3, QHeaderView.ResizeToContents)

        attached_layout.addWidget(self.attached_table)

        # Refresh attached button
        refresh_attached_btn = QPushButton("🔄 Refresh Attached Devices")
        refresh_attached_btn.clicked.connect(self.refresh_attached_devices)
        attached_layout.addWidget(refresh_attached_btn)

        layout.addWidget(attached_group)

        # Initial load of attached devices
        self.refresh_attached_devices()

    def load_devices(self, server: USBIPServer):
        """
        Load devices from a server.

        Runs off the GUI thread: `usbip list -r` against an unreachable host
        takes the full command timeout to fail.
        """
        self.current_server = server

        if self._loading_devices:
            logger.debug("Device load already in flight; skipping")
            return

        self._loading_devices = True
        self.available_header.setText(f"Loading devices from {server.display_name}...")
        logger.info(f"Loading devices from {server.ip_address}")

        run_async(
            self.usbip.list_remote_devices,
            server.ip_address,
            server.port,
            on_result=lambda devices, s=server: self._on_devices_loaded(s, devices),
            on_error=lambda msg, s=server: self._on_devices_error(s, msg),
            on_finished=lambda: setattr(self, "_loading_devices", False),
        )

    def _on_devices_loaded(self, server: USBIPServer, devices: list[USBDevice]):
        """Populate the table once the background fetch returns."""
        # A different server may have been selected while this was in flight.
        if self.current_server is None or server.ip_address != self.current_server.ip_address:
            return

        self.devices = devices
        server.device_count = len(devices)
        self.devices_loaded.emit(server.ip_address, devices)

        selected = self._selected_busid()
        self.device_table.setRowCount(0)

        if not devices:
            self.available_header.setText(f"No devices found on {server.display_name}")
            return

        for device in devices:
            self.add_device_row(device)

        self._restore_selection(selected)
        self.available_header.setText(
            f"Devices on {server.display_name}: {len(devices)} found"
        )

    def _on_devices_error(self, server: USBIPServer, message: str):
        logger.error(f"Failed to load devices from {server.ip_address}: {message}")
        self.available_header.setText(f"Error loading devices: {message}")

    def _selected_busid(self) -> str | None:
        """Remember which row was selected across a rebuild."""
        row = self.device_table.currentRow()
        if row < 0:
            return None
        item = self.device_table.item(row, 0)
        return item.text() if item else None

    def _restore_selection(self, busid: str | None):
        """Reselect a row by busid after the table was rebuilt."""
        if not busid:
            return
        for row in range(self.device_table.rowCount()):
            item = self.device_table.item(row, 0)
            if item and item.text() == busid:
                self.device_table.selectRow(row)
                return

    def add_device_row(self, device: USBDevice):
        """Add a device row to the table"""
        row = self.device_table.rowCount()
        self.device_table.insertRow(row)

        # Bus ID
        busid_item = QTableWidgetItem(device.busid)
        self.device_table.setItem(row, 0, busid_item)

        # Vendor:Product
        vid_pid_item = QTableWidgetItem(f"{device.vendor_id}:{device.product_id}")
        self.device_table.setItem(row, 1, vid_pid_item)

        # Description
        desc_item = QTableWidgetItem(device.description)
        self.device_table.setItem(row, 2, desc_item)

        # Status
        status_icon = self.get_status_icon(device.state)
        status_item = QTableWidgetItem(f"{status_icon} {device.state.value}")
        if device.state == DeviceState.ATTACHED:
            status_item.setForeground(QColor("#4CAF50"))
        elif device.state == DeviceState.BUSY:
            status_item.setForeground(QColor("#FF9800"))
        self.device_table.setItem(row, 3, status_item)

        # Action button
        attach_btn = QPushButton("📎 Attach")
        attach_btn.clicked.connect(lambda: self.attach_device(device))

        if device.state != DeviceState.AVAILABLE:
            attach_btn.setEnabled(False)

        self.device_table.setCellWidget(row, 4, attach_btn)

    def get_status_icon(self, state: DeviceState) -> str:
        """Get icon for device state"""
        icons = {
            DeviceState.AVAILABLE: "✅",
            DeviceState.ATTACHED: "🔗",
            DeviceState.BUSY: "⚠️",
            DeviceState.ERROR: "❌"
        }
        return icons.get(state, "❓")

    def attach_device(self, device: USBDevice):
        """Attach a device"""
        if not self.current_server:
            QMessageBox.warning(self, "Error", "No server selected")
            return

        logger.info(f"Attaching device {device.busid} from {self.current_server.ip_address}")

        # Confirm
        reply = QMessageBox.question(
            self,
            "Attach Device",
            f"Attach device:\n\n{device.description}\n\n"
            f"Bus ID: {device.busid}\n"
            f"From: {self.current_server.name}\n\n"
            "This will make the device available on this computer.",
            QMessageBox.Yes | QMessageBox.No
        )

        if reply != QMessageBox.Yes:
            return

        self.setEnabled(False)
        self.available_header.setText(f"Attaching {device.busid}...")

        run_async(
            self.usbip.attach_device,
            self.current_server.ip_address,
            device.busid,
            self.current_server.port,
            on_result=self._on_attach_finished,
            on_error=lambda msg: self._on_attach_finished((False, msg)),
        )

    def _on_attach_finished(self, outcome):
        """Report the result of a background attach."""
        self.setEnabled(True)
        success, message = outcome

        if success:
            QMessageBox.information(
                self, "Success", f"Device attached successfully!\n\n{message}"
            )
            if self.current_server:
                self.load_devices(self.current_server)
            self.refresh_attached_devices()
        else:
            QMessageBox.critical(
                self,
                "Error",
                f"Failed to attach device:\n\n{message}\n\n"
                "Make sure you have administrator/root privileges.",
            )

    def refresh_attached_devices(self):
        """Refresh list of attached devices (off the GUI thread)"""
        if self._loading_attached:
            return

        self._loading_attached = True
        logger.debug("Refreshing attached devices")

        run_async(
            self.usbip.list_attached_devices,
            on_result=self._on_attached_loaded,
            on_error=lambda msg: logger.error(f"Failed to refresh attached devices: {msg}"),
            on_finished=lambda: setattr(self, "_loading_attached", False),
        )

    def _on_attached_loaded(self, attached: list):
        """Rebuild the attached table from a background result."""
        self.attached_devices = attached
        self.attached_loaded.emit(attached)

        self.attached_table.setRowCount(0)
        for device_info in attached:
            self.add_attached_row(device_info)

    def add_attached_row(self, device_info: dict):
        """Add an attached device row"""
        row = self.attached_table.rowCount()
        self.attached_table.insertRow(row)

        # Port
        port_item = QTableWidgetItem(device_info.get('port', '?'))
        self.attached_table.setItem(row, 0, port_item)

        # Bus ID
        busid_item = QTableWidgetItem(device_info.get('busid', '?'))
        self.attached_table.setItem(row, 1, busid_item)

        # Remote server
        remote_host = device_info.get('remote_host', '?')
        remote_port = device_info.get('remote_port', '?')
        server_item = QTableWidgetItem(f"{remote_host}:{remote_port}")
        self.attached_table.setItem(row, 2, server_item)

        # Detach button
        detach_btn = QPushButton("🔓 Detach")
        detach_btn.clicked.connect(
            lambda: self.detach_device(device_info.get('port', ''))
        )
        self.attached_table.setCellWidget(row, 3, detach_btn)

    def detach_device(self, port: str):
        """Detach a device"""
        if not port:
            return

        logger.info(f"Detaching device on port {port}")

        # Confirm
        reply = QMessageBox.question(
            self,
            "Detach Device",
            f"Detach device on port {port}?",
            QMessageBox.Yes | QMessageBox.No
        )

        if reply != QMessageBox.Yes:
            return

        self.setEnabled(False)

        run_async(
            self.usbip.detach_device,
            port,
            on_result=self._on_detach_finished,
            on_error=lambda msg: self._on_detach_finished((False, msg)),
        )

    def _on_detach_finished(self, outcome):
        """Report the result of a background detach."""
        self.setEnabled(True)
        success, message = outcome

        if success:
            QMessageBox.information(
                self, "Success", f"Device detached successfully!\n\n{message}"
            )
            self.refresh_attached_devices()
            if self.current_server:
                self.load_devices(self.current_server)
        else:
            QMessageBox.critical(
                self,
                "Error",
                f"Failed to detach device:\n\n{message}\n\n"
                "Make sure you have administrator/root privileges.",
            )


if __name__ == "__main__":
    from PySide6.QtWidgets import QApplication

    app = QApplication(sys.argv)

    # Create test widget
    usbip = USBIPWrapper()

    widget = DeviceListWidget(usbip)
    widget.setWindowTitle("Device List Test")
    widget.resize(800, 600)
    widget.show()

    sys.exit(app.exec())
