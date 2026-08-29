# -*- mode: python ; coding: utf-8 -*-
"""
PyInstaller spec for the OpenUSBIP client executable.

Build from the repository root on Windows:

    pyinstaller packaging/windows/openusbip-client.spec

One-folder rather than one-file on purpose. A one-file build unpacks itself to
%TEMP% on every launch, which costs several seconds of startup for a Qt app
this size and, more to the point, is exactly the behaviour that antivirus
heuristics flag on an unsigned binary.
"""

import os
import sys

sys.path.insert(0, os.path.abspath(SPECPATH))
import common  # noqa: E402

ENTRY = os.path.join(common.REPO_ROOT, "openusbip_client.py")

analysis = Analysis(
    [ENTRY],
    pathex=[common.REPO_ROOT],
    binaries=[],
    datas=common.DATAS,
    hiddenimports=common.HIDDEN_IMPORTS,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=common.EXCLUDES,
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
    name="OpenUSBIP",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,  # UPX-packed binaries are a reliable way to get flagged
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=common.ICON,
    version=common.VERSION_FILE,
    # Attaching a device talks to the vhci kernel driver, which needs
    # Administrator, and Windows offers no way to elevate a running process.
    # Requesting it in the manifest means one UAC prompt at launch instead of
    # every attach failing with an opaque access error.
    uac_admin=True,
)

coll = COLLECT(
    exe,
    analysis.binaries,
    analysis.datas,
    strip=False,
    upx=False,
    name="OpenUSBIP",
)
