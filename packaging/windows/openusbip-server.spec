# -*- mode: python ; coding: utf-8 -*-
"""
PyInstaller spec for the OpenUSBIP server executable.

Build from the repository root on Windows:

    pyinstaller packaging/windows/openusbip-server.spec

This does not implement a USB/IP daemon -- usbipd-win already does that well.
What it adds is mDNS advertisement, so an OpenUSBIP client discovers a Windows
machine the same way it discovers a Raspberry Pi, plus a readable report of
whether usbipd-win is installed and actually serving.
"""

import os
import sys

sys.path.insert(0, os.path.abspath(SPECPATH))
import common  # noqa: E402

ENTRY = os.path.join(common.REPO_ROOT, "openusbip_server.py")

# The server is headless, so Qt is dead weight in this build.
SERVER_EXCLUDES = common.EXCLUDES + ["PySide6", "shiboken6"]

analysis = Analysis(
    [ENTRY],
    pathex=[common.REPO_ROOT],
    binaries=[],
    datas=[],
    hiddenimports=common.HIDDEN_IMPORTS,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=SERVER_EXCLUDES,
    noarchive=False,
    optimize=0,
)

common.prune(analysis)

pyz = PYZ(analysis.pure)

exe = EXE(
    pyz,
    analysis.scripts,
    [],
    exclude_binaries=True,
    name="OpenUSBIPServer",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,  # it reports status and is meant to be watched
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=common.ICON,
    version=common.VERSION_FILE,
    # Deliberately not uac_admin: this only reads state and advertises. Sharing
    # a device needs `usbipd bind`, which the user runs elevated themselves --
    # so there is no reason to demand Administrator just to see the list.
    uac_admin=False,
)

coll = COLLECT(
    exe,
    analysis.binaries,
    analysis.datas,
    strip=False,
    upx=False,
    name="OpenUSBIPServer",
)
