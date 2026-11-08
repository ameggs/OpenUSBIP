#Requires -Version 5.1
<#
.SYNOPSIS
    Builds the OpenUSBIP Windows client with PyInstaller.

.DESCRIPTION
    Windows is client-only. Produces one one-folder build under dist\:

        dist\OpenUSBIP\OpenUSBIP.exe              the GUI client

    The executable contains no USB/IP driver. The client drives usbip-win2,
    which is a separate install, because the kernel-mode parts have to be
    signed and this project ships no driver.

    Run from anywhere; the script locates the repository itself.

.PARAMETER Clean
    Delete build\ and dist\ first. Worth using after changing a .spec file:
    PyInstaller caches analysis results and will otherwise reuse stale ones.

.EXAMPLE
    .\packaging\windows\build.ps1 -Clean
#>
[CmdletBinding()]
param(
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'

function Write-Step  ($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-Ok    ($m) { Write-Host "    $m" -ForegroundColor Green }
function Write-Warn2 ($m) { Write-Host "    $m" -ForegroundColor Yellow }

# Resolve the repository root from this script, never from the current
# directory: building from the wrong tree is silent and produces a working
# executable full of the wrong code.
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = Split-Path -Parent (Split-Path -Parent $ScriptDir)

Write-Step "Repository: $RepoRoot"
if (-not (Test-Path (Join-Path $RepoRoot 'openusbip_client.py'))) {
    throw "openusbip_client.py not found under $RepoRoot - is this a full checkout?"
}

# --- Python -----------------------------------------------------------------

Write-Step 'Checking Python'
$python = $null
foreach ($candidate in @('py -3', 'python', 'python3')) {
    $parts = $candidate.Split(' ')
    $exe = Get-Command $parts[0] -ErrorAction SilentlyContinue
    if ($exe) { $python = $candidate; break }
}
if (-not $python) {
    throw 'Python was not found. Install Python 3.10 or newer from https://python.org and tick "Add python.exe to PATH".'
}

$versionText = & ([scriptblock]::Create("$python -c `"import sys; print('%d.%d' % sys.version_info[:2])`""))
$major, $minor = $versionText.Trim().Split('.')
if ([int]$major -lt 3 -or ([int]$major -eq 3 -and [int]$minor -lt 10)) {
    throw "Python $versionText found, but 3.10 or newer is required."
}
Write-Ok "Python $versionText ($python)"

# --- build environment ------------------------------------------------------
#
# A dedicated venv, not the system interpreter. PyInstaller bundles whatever it
# finds installed, so building against a cluttered global environment quietly
# inflates the output and can pull in packages that fail to import on a clean
# machine.

$VenvDir = Join-Path $RepoRoot '.venv-build'
$VenvPy  = Join-Path $VenvDir 'Scripts\python.exe'

if (-not (Test-Path $VenvPy)) {
    Write-Step "Creating build virtualenv at $VenvDir"
    & ([scriptblock]::Create("$python -m venv `"$VenvDir`""))
    if ($LASTEXITCODE -ne 0) { throw 'Failed to create the virtualenv.' }
}
Write-Ok 'Build virtualenv ready'

Write-Step 'Installing build dependencies'
& $VenvPy -m pip install --upgrade pip --quiet
& $VenvPy -m pip install --quiet -r (Join-Path $RepoRoot 'requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'Failed to install runtime requirements.' }
& $VenvPy -m pip install --quiet 'pyinstaller>=6.3'
if ($LASTEXITCODE -ne 0) { throw 'Failed to install PyInstaller.' }
Write-Ok 'Dependencies installed'

# --- build ------------------------------------------------------------------

Push-Location $RepoRoot
try {
    if ($Clean) {
        Write-Step 'Cleaning previous build output'
        foreach ($dir in @('build', 'dist')) {
            if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
        }
        Write-Ok 'build\ and dist\ removed'
    }

    foreach ($spec in @('openusbip-client.spec')) {
        Write-Step "Building $spec"
        & $VenvPy -m PyInstaller --noconfirm (Join-Path $ScriptDir $spec)
        if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed on $spec" }
    }

    # --- report -------------------------------------------------------------

    Write-Step 'Result'
    $built = @()
    foreach ($pair in @(
        @{ Name = 'Client'; Path = 'dist\OpenUSBIP\OpenUSBIP.exe' }
    )) {
        if (Test-Path $pair.Path) {
            $folder = Split-Path -Parent $pair.Path
            $size = (Get-ChildItem -Recurse $folder | Measure-Object -Property Length -Sum).Sum / 1MB
            Write-Ok ("{0,-7} {1}  ({2:N0} MB total)" -f $pair.Name, $pair.Path, $size)
            $built += $folder
        }
    }
    if (-not $built) { throw 'PyInstaller reported success but produced no executable.' }

    Write-Host ''
    Write-Warn2 'This executable contains no USB/IP driver. Before use, install:'
    Write-Warn2 '  client -> usbip-win2   https://github.com/vadimgrn/usbip-win2/releases'
    Write-Host ''
    Write-Warn2 'It is also unsigned, so SmartScreen will warn on first run.'
}
finally {
    Pop-Location
}
