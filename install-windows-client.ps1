# OpenUSBIP Windows Client Installer
# Run as Administrator: powershell -ExecutionPolicy Bypass -File install-windows-client.ps1

Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║                                                              ║" -ForegroundColor Cyan
Write-Host "║           OpenUSBIP Client - Windows Installer              ║" -ForegroundColor Cyan
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

# Check if usbip-win2 is installed
Write-Host "🔍 Checking for usbip-win2..." -ForegroundColor Cyan
$usbipPath = "C:\Program Files\usbip-win2\usbip.exe"
if (-not (Test-Path $usbipPath)) {
    $usbipPath = "C:\Program Files (x86)\usbip-win2\usbip.exe"
}

if (-not (Test-Path $usbipPath)) {
    Write-Host "❌ usbip-win2 not found!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Please install usbip-win2 from:" -ForegroundColor Yellow
    Write-Host "  https://github.com/vadimgrn/usbip-win2/releases" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Download and install the latest release, then run this script again." -ForegroundColor Yellow
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "✅ usbip-win2 found at: $usbipPath" -ForegroundColor Green
Write-Host ""

# Install Python dependencies
Write-Host "📦 Installing Python dependencies..." -ForegroundColor Cyan
Write-Host ""

& python -m pip install --upgrade pip --quiet
& python -m pip install PySide6 zeroconf --quiet

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

# Create desktop shortcut
Write-Host "🖥️  Creating desktop shortcut..." -ForegroundColor Cyan

$desktopPath = [Environment]::GetFolderPath("Desktop")
$shortcutPath = Join-Path $desktopPath "OpenUSBIP Client.lnk"

$WScriptShell = New-Object -ComObject WScript.Shell
$shortcut = $WScriptShell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = "pythonw.exe"
$shortcut.Arguments = "`"$installDir\openusbip_client.py`""
$shortcut.WorkingDirectory = $installDir
$shortcut.IconLocation = "$installDir\resources\icons\openusbip.ico"
$shortcut.Description = "OpenUSBIP Client - Connect to remote USB devices"
$shortcut.Save()

Write-Host "✅ Desktop shortcut created!" -ForegroundColor Green
Write-Host ""

# Create Start Menu shortcut
Write-Host "📌 Creating Start Menu shortcut..." -ForegroundColor Cyan

$startMenuPath = [Environment]::GetFolderPath("StartMenu")
$programsPath = Join-Path $startMenuPath "Programs"
$shortcutPath = Join-Path $programsPath "OpenUSBIP Client.lnk"

$shortcut = $WScriptShell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = "pythonw.exe"
$shortcut.Arguments = "`"$installDir\openusbip_client.py`""
$shortcut.WorkingDirectory = $installDir
$shortcut.IconLocation = "$installDir\resources\icons\openusbip.ico"
$shortcut.Description = "OpenUSBIP Client - Connect to remote USB devices"
$shortcut.Save()

Write-Host "✅ Start Menu shortcut created!" -ForegroundColor Green
Write-Host ""

# Test the application
Write-Host "🧪 Testing application..." -ForegroundColor Cyan
$testResult = & python -c "import sys; sys.path.insert(0, '$installDir/src'); from utils.platform_utils import check_usbip_installed; print(check_usbip_installed()[0])" 2>&1

if ($testResult -eq "True") {
    Write-Host "✅ Application test passed!" -ForegroundColor Green
} else {
    Write-Host "⚠️  Warning: Application test failed" -ForegroundColor Yellow
    Write-Host "   The application may still work, but please verify." -ForegroundColor Yellow
}
Write-Host ""

# Installation complete
Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Green
Write-Host "║                                                              ║" -ForegroundColor Green
Write-Host "║                  ✅ INSTALLATION COMPLETE! ✅                 ║" -ForegroundColor Green
Write-Host "║                                                              ║" -ForegroundColor Green
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Green
Write-Host ""

Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║                    How to Launch                             ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Option 1: Double-click 'OpenUSBIP Client' on your desktop" -ForegroundColor White
Write-Host ""
Write-Host "  Option 2: Search for 'OpenUSBIP' in Start Menu" -ForegroundColor White
Write-Host ""
Write-Host "  Option 3: Run from PowerShell:" -ForegroundColor White
Write-Host "    cd `"$installDir`"" -ForegroundColor Gray
Write-Host "    python openusbip_client.py" -ForegroundColor Gray
Write-Host ""

Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║                    What's Next?                              ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""
Write-Host "  1. Launch the OpenUSBIP Client" -ForegroundColor White
Write-Host "  2. Your USB/IP servers will appear automatically!" -ForegroundColor White
Write-Host "  3. Click 'Attach' to connect to any USB device" -ForegroundColor White
Write-Host "  4. The app minimizes to system tray for easy access" -ForegroundColor White
Write-Host ""

Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Green
Write-Host "║                                                              ║" -ForegroundColor Green
Write-Host "║         🎉 Your USB/IP Client is Ready! 🎉                  ║" -ForegroundColor Green
Write-Host "║                                                              ║" -ForegroundColor Green
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Green
Write-Host ""

Read-Host "Press Enter to exit"
