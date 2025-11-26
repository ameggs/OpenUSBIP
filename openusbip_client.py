#!/usr/bin/env python3
"""
OpenUSBIP Client
Main entry point for the USB/IP client application
"""

import sys
import os

# Add src directory to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'src'))

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
    from utils.platform_utils import check_usbip_installed, get_platform, check_kernel_modules, load_kernel_module
    from utils.logger import setup_logger
    
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
    from gui.main_window import MainWindow
    
    window = MainWindow(mode="client")
    window.show()
    
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
