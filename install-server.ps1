# OpenUSBIP Windows Server - One-Click Installer
# Save this as install-server.ps1 and double-click to run

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
    Write-Host "Right-click the file and select 'Run as Administrator'" -ForegroundColor Yellow
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "✅ Running as Administrator" -ForegroundColor Green
Write-Host ""

# Get script directory
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Write-Host "📁 Script directory: $scriptDir" -ForegroundColor Cyan
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
    Write-Host "Installing Python..." -ForegroundColor Yellow

    # Download and install Python
    $pythonInstaller = "$env:TEMP\python-installer.exe"
    Invoke-WebRequest -Uri "https://www.python.org/ftp/python/3.11.8/python-3.11.8-amd64.exe" -OutFile $pythonInstaller

    Write-Host "Running Python installer..." -ForegroundColor Yellow
    Start-Process -FilePath $pythonInstaller -ArgumentList "/quiet InstallAllUsers=1 PrependPath=1" -Wait

    # Refresh environment
    $env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

    $pythonPath = (Get-Command python -ErrorAction SilentlyContinue).Source
    if (-not $pythonPath) {
        Write-Host "❌ Python installation failed!" -ForegroundColor Red
        Read-Host "Press Enter to exit"
        exit 1
    }

    Write-Host "✅ Python installed!" -ForegroundColor Green
} else {
    Write-Host "✅ Python found at: $pythonPath" -ForegroundColor Green
}

Write-Host ""

# Check if usbipd-win is installed
Write-Host "🔍 Checking for usbipd-win..." -ForegroundColor Cyan
$usbipd = Get-Command usbipd -ErrorAction SilentlyContinue

if (-not $usbipd) {
    Write-Host "❌ usbipd-win not found!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Installing usbipd-win..." -ForegroundColor Yellow

    # Install usbipd-win via winget
    & winget install --id=dorssel.usbipd-win -e --silent

    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ usbipd-win installed!" -ForegroundColor Green
    } else {
        Write-Host "❌ usbipd-win installation failed!" -ForegroundColor Red
        Write-Host ""
        Write-Host "Please install manually from:" -ForegroundColor Yellow
        Write-Host "  https://github.com/dorssel/usbipd-win/releases" -ForegroundColor Yellow
        Write-Host ""
        Read-Host "Press Enter to exit"
        exit 1
    }
} else {
    Write-Host "✅ usbipd-win found!" -ForegroundColor Green
}

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

# List USB devices
Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║                    Available USB Devices                     ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

& usbipd list

Write-Host ""

# Installation complete
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