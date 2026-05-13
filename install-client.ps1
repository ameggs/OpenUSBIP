# OpenUSBIP Windows Client - One-Click Installer
# Save this as install-client.ps1 and double-click to run

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

# Create desktop shortcut
Write-Host "🖥️  Creating desktop shortcut..." -ForegroundColor Cyan

$desktopPath = [Environment]::GetFolderPath("Desktop")
$shortcutPath = Join-Path $desktopPath "OpenUSBIP Client.lnk"

$WScriptShell = New-Object -ComObject WScript.Shell
$shortcut = $WScriptShell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $pythonPath
$shortcut.Arguments = "`"$scriptDir\openusbip_client.py`""
$shortcut.WorkingDirectory = $scriptDir
$shortcut.IconLocation = "$scriptDir\resources\icons\openusbip.ico"
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
$shortcut.TargetPath = $pythonPath
$shortcut.Arguments = "`"$scriptDir\openusbip_client.py`""
$shortcut.WorkingDirectory = $scriptDir
$shortcut.IconLocation = "$scriptDir\resources\icons\openusbip.ico"
$shortcut.Description = "OpenUSBIP Client - Connect to remote USB devices"
$shortcut.Save()

Write-Host "✅ Start Menu shortcut created!" -ForegroundColor Green
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
Write-Host "  Double-click 'OpenUSBIP Client' on your desktop" -ForegroundColor White
Write-Host "  OR" -ForegroundColor White
Write-Host "  Search for 'OpenUSBIP' in Start Menu" -ForegroundColor White
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