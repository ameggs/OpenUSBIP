# OpenUSBIP Windows Server Installer
# Run as Administrator: powershell -ExecutionPolicy Bypass -File install-windows-server.ps1

Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║                                                              ║" -ForegroundColor Cyan
Write-Host "║           OpenUSBIP Server - Windows Installer              ║" -ForegroundColor Cyan
Write-Host "║                                                              ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

# Check if running as Administrator
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "❌ ERROR: This script must be run as Administrator!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Right-click PowerShell and select 'Run as Administrator'" -ForegroundColor Yellow
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "✅ Running as Administrator" -ForegroundColor Green
Write-Host ""

# Check if Python is installed
Write-Host "🔍 Checking for Python..." -ForegroundColor Cyan
$pythonPath = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $pythonPath) {
    $pythonPath = (Get-Command python3 -ErrorAction SilentlyContinue).Source
}

if (-not $pythonPath) {
    Write-Host "❌ Python not found!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Please install Python 3.10 or newer from:" -ForegroundColor Yellow
    Write-Host "  https://www.python.org/downloads/" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Make sure to check 'Add Python to PATH' during installation!" -ForegroundColor Yellow
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "✅ Python found at: $pythonPath" -ForegroundColor Green
Write-Host ""

# Check if usbipd-win is installed
Write-Host "🔍 Checking for usbipd-win..." -ForegroundColor Cyan
$usbipd = Get-Command usbipd -ErrorAction SilentlyContinue

if (-not $usbipd) {
    Write-Host "❌ usbipd-win not found!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Please install usbipd-win from:" -ForegroundColor Yellow
    Write-Host "  https://github.com/dorssel/usbipd-win/releases" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Or install via winget:" -ForegroundColor Yellow
    Write-Host "  winget install --id=dorssel.usbipd-win -e" -ForegroundColor Gray
    Write-Host ""
    Write-Host "After installation, run this script again." -ForegroundColor Yellow
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "✅ usbipd-win found!" -ForegroundColor Green
Write-Host ""

# Install Python dependencies
Write-Host "📦 Installing Python dependencies..." -ForegroundColor Cyan
Write-Host ""

& python -m pip install --upgrade pip --quiet
& python -m pip install zeroconf --quiet

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ Python dependencies installed!" -ForegroundColor Green
} else {
    Write-Host "❌ Failed to install Python dependencies!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}
Write-Host ""

# Get installation directory
$installDir = $PSScriptRoot
if (-not $installDir) {
    $installDir = Get-Location
}

Write-Host "📁 Installation directory: $installDir" -ForegroundColor Cyan
Write-Host ""

# List USB devices
Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║                    Available USB Devices                     ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

& usbipd list

Write-Host ""
Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Green
Write-Host "║                                                              ║" -ForegroundColor Green
Write-Host "║                  ✅ INSTALLATION COMPLETE! ✅                 ║" -ForegroundColor Green
Write-Host "║                                                              ║" -ForegroundColor Green
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Green
Write-Host ""

Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║                    How to Share Devices                      ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""
Write-Host "  1. List devices:" -ForegroundColor White
Write-Host "     usbipd list" -ForegroundColor Gray
Write-Host ""
Write-Host "  2. Bind a device (one-time setup):" -ForegroundColor White
Write-Host "     usbipd bind --busid <BUSID>" -ForegroundColor Gray
Write-Host ""
Write-Host "  3. Share the device:" -ForegroundColor White
Write-Host "     usbipd attach --wsl --busid <BUSID>" -ForegroundColor Gray
Write-Host "     OR for network sharing:" -ForegroundColor Gray
Write-Host "     usbipd bind --busid <BUSID>" -ForegroundColor Gray
Write-Host ""

Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║                    Server Information                        ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

$hostname = $env:COMPUTERNAME
$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object {$_.InterfaceAlias -notlike "*Loopback*" -and $_.IPAddress -notlike "169.254.*"} | Select-Object -First 1).IPAddress

Write-Host "  💻 Hostname: $hostname" -ForegroundColor White
Write-Host "  🌐 IP Address: $ip" -ForegroundColor White
Write-Host "  🔌 Port: 3240" -ForegroundColor White
Write-Host ""

Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Green
Write-Host "║                                                              ║" -ForegroundColor Green
Write-Host "║         🎉 Your USB/IP Server is Ready! 🎉                  ║" -ForegroundColor Green
Write-Host "║                                                              ║" -ForegroundColor Green
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Green
Write-Host ""

Read-Host "Press Enter to exit"
