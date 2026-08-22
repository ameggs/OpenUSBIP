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

    # Check USB/IP installation
    installed, msg = check_usbip_installed()
    if not installed:
        logger.error(msg)
        print(f"\nERROR: {msg}")
        sys.exit(1)

    logger.info(msg)

    # Check kernel modules (Linux only)
    if platform.value == "linux":
        loaded, missing = check_kernel_modules()
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

    app = QApplication(sys.argv)
    app.setApplicationName("OpenUSBIP Client")
    app.setOrganizationName("OpenUSBIP")

    # Import and create main window
    from src.gui.main_window import MainWindow

    window = MainWindow(mode="client")
    window.show()

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
