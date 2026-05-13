#!/bin/bash

# OpenUSBIP Client - Portable Launcher
# Copy this script to any Linux machine and run it!

set -e

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                              ║"
echo "║           OpenUSBIP Client - Portable Launcher              ║"
echo "║                                                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Create temporary directory for the application
TEMP_DIR=$(mktemp -d)
echo "📁 Using temporary directory: $TEMP_DIR"

# Function to cleanup on exit
cleanup() {
    echo ""
    echo "🧹 Cleaning up..."
    rm -rf "$TEMP_DIR"
    echo "✅ Cleanup complete"
}
trap cleanup EXIT

# Copy application files to temp directory
echo "📋 Copying application files..."
cp -r src resources openusbip_client.py requirements.txt "$TEMP_DIR/"

cd "$TEMP_DIR"

# Check system requirements
echo "🔍 Checking system requirements..."

# Check for usbip tools
if ! command -v usbip &> /dev/null; then
    echo "❌ ERROR: usbip tools not found!"
    echo "   Please install usbip tools:"
    echo "   Ubuntu/Debian: sudo apt install linux-tools-generic"
    echo "   Fedora: sudo dnf install usbip-utils"
    exit 1
fi

# Check for kernel modules
if ! lsmod | grep -q vhci_hcd; then
    echo "⚠️  WARNING: vhci_hcd kernel module not loaded"
    echo "   Trying to load it..."
    sudo modprobe vhci_hcd 2>/dev/null || echo "   Please run: sudo modprobe vhci_hcd"
fi

# Check for Python
if ! command -v python3 &> /dev/null; then
    echo "❌ ERROR: Python 3 not found!"
    exit 1
fi

# Check for PySide6
if ! python3 -c "import PySide6" 2>/dev/null; then
    echo "❌ ERROR: PySide6 not found!"
    echo "   Please install: pip3 install PySide6"
    exit 1
fi

# Check for zeroconf
if ! python3 -c "import zeroconf" 2>/dev/null; then
    echo "❌ ERROR: zeroconf not found!"
    echo "   Please install: pip3 install zeroconf"
    exit 1
fi

echo "✅ System requirements met!"

# Launch the application
echo "🚀 Launching OpenUSBIP Client..."
echo ""
echo "The application will start in a moment..."
echo "If it doesn't appear, check the terminal for error messages."
echo ""
echo "Press Ctrl+C to stop the application."
echo ""

exec python3 openusbip_client.py