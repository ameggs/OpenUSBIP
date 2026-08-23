# Changelog

All notable changes to OpenUSBIP will be documented in this file.

## [Unreleased]

### Fixed

- **Server entry point crashed on every run.** `openusbip_server.py` put `src/`
  on `sys.path`, making `core` a top-level package, while `usbip_wrapper.py`
  used `from ..utils` to reach above it — every invocation died with
  "attempted relative import beyond top-level package".
- **`install-server.sh` installed a divergent inline copy of the server.** It
  wrote a simplified `openusbip_server.py` via heredoc and pointed the systemd
  unit at that, so fixes to the real server never took effect. It now installs
  the repository.
- **`usbipd` path was hardcoded to `/usr/sbin/usbipd`**, which does not exist on
  Ubuntu/Zorin (`/usr/bin/usbipd`). Located at install time instead.
- **The server prompted for input under systemd**, raising `EOFError`
  immediately. Prompting is now gated on `stdin.isatty()`, with `--daemon`.
- **Tray Quit and File → Exit could never exit.** Both called `self.close()`,
  which `closeEvent` turned into minimise-to-tray.
- **Custom ports were silently ignored.** `port` was accepted by
  `list_remote_devices()` and `attach_device()` and dropped; `usbip` takes
  `--tcp-port` as a global option, so it now precedes the subcommand.
- **The GUI froze for up to 15 seconds** on an unreachable server. All usbip
  calls and socket probes now run on a `QThreadPool`.
- **The 5-second refresh wiped the user's selection** by rebuilding both lists
  wholesale. Selection is preserved across rebuilds.
- **Linux `list_local_devices()` returned empty** when `usbipd` was absent, even
  though it runs `usbip`.
- **`sudo` could deadlock**: bare `sudo` with piped stdin blocked on the password
  prompt until timeout. Uses `sudo -n` and explains the failure.
- Local device descriptions repeated their `(vid:pid)` suffix.
- Selecting a server issued two identical `usbip list -r` calls.
- `LICENSE` contained only the GPL-3.0 notice, not the license text required for
  distribution. Full text added; the project notice moved to `NOTICE`.

### Added

- `ConfigManager` is now actually wired in — manually added servers, auto-attach
  entries, and window geometry persist across restarts. It was 255 lines of
  working but entirely unimported code.
- `tests/` with 24 tests covering the output parsers, the `--tcp-port`
  regression, and import health.
- GitHub Actions CI: pytest on Python 3.10/3.12, ruff, byte-compile, installer
  syntax checks.
- `pyproject.toml`; `requirements-dev.txt` split out from runtime deps.
- A Security section in the README documenting that USB/IP is unauthenticated
  and unencrypted.
- Authenticode verification of the Python installer downloaded by the Windows
  installers.

### Changed

- Consolidated 12 overlapping installer scripts into 4. The removed
  `install-linux-client.sh` never installed the application at all — its
  download line was commented out — and several contained unreplaced
  `yourusername` / `your-url` placeholders that 404'd.
- Removed dead code: `MainWindow.server_selected` (never emitted),
  `ServerDiscovery.scan_network()` (a stub), and the `psutil` dependency
  (never imported).

## [0.1.0] - 2025-11-26

### 🎉 Initial Release - Fully Functional USB/IP Solution

#### ✅ Core Features
- **USB/IP Wrapper** (`src/core/usbip_wrapper.py`)
  - Device enumeration and listing
  - Attach/detach functionality
  - Remote device discovery
  - Attached device management
  - Cross-platform support (Linux, Windows, Raspberry Pi)

- **Server Discovery** (`src/core/server_discovery.py`)
  - mDNS/Zeroconf auto-discovery
  - Manual server addition
  - Server management and persistence
  - Real-time server detection

- **Configuration Management** (`src/core/config_manager.py`)
  - Persistent server storage
  - User preferences
  - Auto-connect rules (planned)

#### 🎨 GUI Components
- **Main Window** (`src/gui/main_window.py`)
  - Clean, modern Qt6 interface
  - Server list with auto-discovery
  - Device list with real-time updates
  - Status bar with connection info
  - Menu bar with keyboard shortcuts

- **Server Widget** (`src/gui/server_widget.py`)
  - Server list with icons
  - Manual server addition
  - Server removal
  - Connection status indicators

- **Device Widget** (`src/gui/device_widget.py`)
  - Device list with details (VID/PID, description)
  - One-click attach/detach buttons
  - Real-time device updates
  - Attached device indicators

- **System Tray** (`src/gui/system_tray.py`) ⭐ NEW
  - Minimize to system tray
  - Quick access menu
  - Server and device submenus
  - Desktop notifications
  - Background operation

#### 🔧 Utilities
- **Platform Utils** (`src/utils/platform_utils.py`)
  - Platform detection (Linux, Windows, Raspberry Pi)
  - USB/IP installation check
  - Admin/root privilege detection
  - Path resolution for usbip binaries

- **Logger** (`src/utils/logger.py`)
  - Colored console output
  - File logging
  - Debug levels
  - Module-specific loggers

#### 🥧 Raspberry Pi Server
- **One-Command Installer** (`install-server.sh`)
  - Automated setup script
  - Installs all dependencies
  - Configures systemd services
  - Sets up USB hotplug detection
  - Configures auto-start on boot
  - Creates udev rules for auto-binding

- **Services**
  - `usbipd.service` - USB/IP daemon
  - `openusbip-server.service` - mDNS advertisement
  - `usb-auto-bind.service` - Automatic USB device binding

- **USB Hotplug**
  - Udev rules for automatic device detection
  - Auto-binding of new USB devices
  - Real-time updates to clients

#### 💻 Desktop Client
- **GUI Launcher**
  - Desktop icon (`~/.local/share/applications/openusbip-client.desktop`)
  - Application menu integration
  - Custom icon (SVG)

- **Passwordless Sudo**
  - Configured for usbip commands
  - Secure sudoers.d configuration
  - No password prompts for attach/detach

#### 🔔 System Tray Integration
- **Features**
  - Minimize to tray instead of closing
  - Right-click menu with quick actions
  - Server submenu with available devices
  - Attached devices submenu with detach option
  - Desktop notifications for events
  - Single/double-click to show window

- **Notifications**
  - Server discovered
  - Device attached
  - Device detached
  - Error notifications
  - Minimize to tray notification

#### ⚡ Real-Time Updates
- **Device Detection**
  - Instant detection of USB plug/unplug
  - Automatic GUI refresh
  - Tray menu updates
  - No manual refresh needed

- **Server Discovery**
  - Continuous mDNS monitoring
  - Automatic server addition/removal
  - Connection status updates

#### 📦 Installation & Setup
- **Raspberry Pi**
  - One-command automated installer
  - Complete service configuration
  - Auto-start on boot
  - USB hotplug support

- **Desktop Client**
  - Desktop launcher creation
  - Icon installation
  - Passwordless sudo setup
  - Application menu integration

#### 🧪 Testing
- **Verified Working**
  - Raspberry Pi server (192.168.0.34)
  - Pop!_OS client (192.168.0.225)
  - Logitech Unifying Receiver
  - USB Flash Drive
  - Real-time device updates
  - System tray integration

#### 📚 Documentation
- **README.md**
  - Feature list
  - Quick start guide
  - Installation instructions
  - Architecture overview
  - Supported devices

- **TESTING.md**
  - Comprehensive test checklist
  - Manual testing procedures
  - Known issues
  - Test commands

- **CHANGELOG.md** (this file)
  - Version history
  - Feature documentation
  - Release notes

### 🔨 Known Limitations
- Windows client/server not yet tested
- Single server selection in GUI (multi-server support in tray)
- No auto-attach rules yet
- No bandwidth monitoring

### 📋 Planned Features
- [ ] Windows client/server support
- [ ] Auto-attach rules
- [ ] Device filtering and search
- [ ] Connection history
- [ ] Bandwidth monitoring
- [ ] AppImage/DEB packaging
- [ ] Multiple server selection in main GUI
- [ ] Device categories and grouping

### 🐛 Bug Fixes
- Fixed Qt.Horizontal import (changed to Qt.Orientation.Horizontal)
- Fixed usbip path detection (added /usr/sbin check)
- Fixed tray icon loading (fallback to system icon)

### 🎯 Statistics
- **Total Lines of Code**: ~2,571
- **Python Modules**: 14
- **GUI Components**: 4
- **Core Components**: 3
- **Utility Modules**: 2
- **Services**: 3
- **Completion**: ~85%

### 🙏 Acknowledgments
- Linux USB/IP kernel developers
- usbipd-win by @dorssel
- PySide6/Qt6 team
- Raspberry Pi Foundation

---

## [Unreleased]

### 🔨 In Progress
- Windows client/server testing
- AppImage packaging
- Auto-attach rules UI

### 📋 Planned
- Multiple server support in main GUI
- Device filtering
- Connection history
- Bandwidth monitoring
- Video demo and screenshots

---

**Note**: This is version 0.1.0 - the first fully functional release. While the core features are complete and tested, this is still early-stage software. Please report any issues on GitHub!
