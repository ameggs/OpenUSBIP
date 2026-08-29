#!/usr/bin/env python3
"""
OpenUSBIP Client
Main entry point for the USB/IP client application
"""

import os
import sys

# Ensure the repository root is importable so `src.*` resolves consistently.
_ROOT = os.path.dirname(os.path.abspath(__file__))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

def _fatal(title: str, message: str) -> None:
    """
    Report an unrecoverable startup problem and exit.

    Prints to stderr and, when Qt is usable, also shows a dialog. Both, not
    one or the other: the console build has no dialog to look at and the
    windowed build has no console.
    """
    print(f"\nERROR: {message}", file=sys.stderr)
    try:
        from PySide6.QtWidgets import QApplication, QMessageBox

        if QApplication.instance() is not None:
            QMessageBox.critical(None, f"OpenUSBIP - {title}", message)
    except Exception:
        pass  # already reported on stderr; never fail inside the failure path
    sys.exit(1)


def main():
    """Main entry point"""
    print("OpenUSBIP Client v0.1.0")
    print("=" * 50)

    # Check dependencies
    try:
        from PySide6.QtWidgets import QApplication
    except ImportError:
        print("ERROR: PySide6 not installed")
        print("Install with: pip install PySide6")
        sys.exit(1)

    # Import after path is set
    from src.utils.logger import setup_logger
    from src.utils.platform_utils import (
        check_kernel_modules,
        check_usbip_installed,
        get_platform,
        load_kernel_module,
    )

    logger = setup_logger("OpenUSBIP", level=10)  # DEBUG level

    # Check platform
    platform = get_platform()
    logger.info(f"Running on: {platform.value}")

    # The application object is created before the preflight checks so those
    # can report failures in a dialog. In the packaged Windows build there is
    # no console attached, so a printed error goes nowhere and the app simply
    # disappears -- the single most confusing way for a program to fail.
    app = QApplication(sys.argv)
    app.setApplicationName("OpenUSBIP Client")
    app.setOrganizationName("OpenUSBIP")

    # Check USB/IP installation
    installed, msg = check_usbip_installed()
    if not installed:
        logger.error(msg)
        _fatal("USB/IP tools not found", msg)

    # Check kernel modules (Linux only)
    if platform.value == "linux":
        loaded, missing = check_kernel_modules(role="client")
        if not loaded:
            logger.warning(f"Missing kernel modules: {', '.join(missing)}")
            print(f"\nWARNING: Missing kernel modules: {', '.join(missing)}")
            print("Attempting to load modules...")

            for module in missing:
                if load_kernel_module(module):
                    logger.info(f"Loaded module: {module}")
                else:
                    logger.error(f"Failed to load module: {module}")
                    print(f"ERROR: Failed to load {module}. Run: sudo modprobe {module}")

    # Start GUI
    logger.info("Starting GUI...")

    # Import and create main window
    from src.gui.main_window import MainWindow

    window = MainWindow(mode="client")
    window.show()

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
