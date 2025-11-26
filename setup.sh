#!/bin/bash
# OpenUSBIP Setup Script
# Installs dependencies and sets up the environment

set -e

echo "========================================="
echo "OpenUSBIP Setup"
echo "========================================="
echo ""

# Detect platform
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    PLATFORM="linux"
elif [[ "$OSTYPE" == "msys" ]] || [[ "$OSTYPE" == "win32" ]]; then
    PLATFORM="windows"
else
    PLATFORM="unknown"
fi

echo "Platform detected: $PLATFORM"
echo ""

# Check Python version
PYTHON_CMD="python3"
if ! command -v $PYTHON_CMD &> /dev/null; then
    PYTHON_CMD="python"
fi

if ! command -v $PYTHON_CMD &> /dev/null; then
    echo "ERROR: Python 3 not found"
    exit 1
fi

PYTHON_VERSION=$($PYTHON_CMD --version 2>&1 | awk '{print $2}')
echo "Python version: $PYTHON_VERSION"

# Check if Python >= 3.10
PYTHON_MAJOR=$($PYTHON_CMD -c 'import sys; print(sys.version_info.major)')
PYTHON_MINOR=$($PYTHON_CMD -c 'import sys; print(sys.version_info.minor)')

if [ "$PYTHON_MAJOR" -lt 3 ] || ([ "$PYTHON_MAJOR" -eq 3 ] && [ "$PYTHON_MINOR" -lt 10 ]); then
    echo "ERROR: Python 3.10 or newer required"
    exit 1
fi

echo ""
echo "Installing Python dependencies..."
$PYTHON_CMD -m pip install --user -r requirements.txt

echo ""
echo "========================================="
echo "Platform-specific setup"
echo "========================================="

if [ "$PLATFORM" == "linux" ]; then
    echo ""
    echo "Linux detected - checking USB/IP tools..."
    
    if command -v usbip &> /dev/null; then
        echo "✓ usbip command found"
    else
        echo "✗ usbip not found"
        echo ""
        echo "Install USB/IP tools:"
        echo "  Ubuntu/Debian: sudo apt install linux-tools-generic hwdata"
        echo "  Fedora: sudo dnf install usbip"
        echo "  Arch: sudo pacman -S usbip"
    fi
    
    echo ""
    echo "Checking kernel modules..."
    if lsmod | grep -q vhci_hcd; then
        echo "✓ vhci_hcd module loaded"
    else
        echo "✗ vhci_hcd not loaded"
        echo "  Load with: sudo modprobe vhci_hcd"
    fi
    
    if lsmod | grep -q usbip_host; then
        echo "✓ usbip_host module loaded"
    else
        echo "✗ usbip_host not loaded (only needed for server mode)"
        echo "  Load with: sudo modprobe usbip_host"
    fi
    
elif [ "$PLATFORM" == "windows" ]; then
    echo ""
    echo "Windows detected"
    echo ""
    echo "Please install:"
    echo "  1. usbipd-win (server): https://github.com/dorssel/usbipd-win"
    echo "  2. usbip-win2 (client): https://github.com/vadimgrn/usbip-win2"
fi

echo ""
echo "========================================="
echo "Setup complete!"
echo "========================================="
echo ""
echo "Run the client with:"
echo "  $PYTHON_CMD openusbip_client.py"
echo ""
echo "Run the server with:"
echo "  $PYTHON_CMD openusbip_server.py"
echo ""
