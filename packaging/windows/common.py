"""
Shared PyInstaller settings for the two Windows executables.

Imported by both .spec files, which PyInstaller executes as Python with the
spec's own directory on sys.path.
"""

import os
import sys

from PyInstaller.utils.hooks import collect_submodules

SPEC_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(SPEC_DIR))

# collect_submodules("src") imports the package to walk it, and `src` is only
# importable with the repository root on the path -- the same thing the entry
# points do at runtime.
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

ICON = os.path.join(REPO_ROOT, "resources", "icons", "openusbip.ico")
VERSION_FILE = os.path.join(SPEC_DIR, "version_info.txt")

# Every `src.*` import in this project happens inside a function, so that the
# entry points can adjust sys.path before importing anything. PyInstaller's
# analysis does follow function-level imports, but `src` is only reachable once
# the repository root is on the path -- which never happens at build time. So
# collect it explicitly instead of relying on discovery.
HIDDEN_IMPORTS = collect_submodules("src") + [
    # zeroconf's internals are split across Cython-compiled modules that are
    # loaded dynamically; missing one produces an ImportError at first
    # discovery rather than at startup, which is a miserable thing to debug in
    # a frozen build.
    *collect_submodules("zeroconf"),
]

# PySide6 pulls in a great deal that a Qt Widgets app never touches. Excluding
# the modules is only half of it -- see PRUNE_PATTERNS below for the shared
# libraries, which the excludes do not remove on their own.
EXCLUDES = [
    "PySide6.Qt3DAnimation",
    "PySide6.Qt3DCore",
    "PySide6.Qt3DExtras",
    "PySide6.Qt3DInput",
    "PySide6.Qt3DLogic",
    "PySide6.Qt3DRender",
    "PySide6.QtBluetooth",
    "PySide6.QtCharts",
    "PySide6.QtDataVisualization",
    "PySide6.QtDesigner",
    "PySide6.QtHelp",
    "PySide6.QtMultimedia",
    "PySide6.QtMultimediaWidgets",
    "PySide6.QtOpenGL",
    "PySide6.QtOpenGLWidgets",
    "PySide6.QtPdf",
    "PySide6.QtPdfWidgets",
    "PySide6.QtPositioning",
    "PySide6.QtQml",
    "PySide6.QtQuick",
    "PySide6.QtQuick3D",
    "PySide6.QtQuickWidgets",
    "PySide6.QtRemoteObjects",
    "PySide6.QtScxml",
    "PySide6.QtSensors",
    "PySide6.QtSerialPort",
    "PySide6.QtSpatialAudio",
    "PySide6.QtSql",
    "PySide6.QtTest",
    "PySide6.QtTextToSpeech",
    "PySide6.QtWebChannel",
    "PySide6.QtWebEngineCore",
    "PySide6.QtWebEngineQuick",
    "PySide6.QtWebEngineWidgets",
    "PySide6.QtWebSockets",
    # Not used by this project, and each drags in a large dependency tree.
    "matplotlib",
    "numpy",
    "PIL",
    "tkinter",
    "pytest",
]

# The SVG is what the system tray and window icons load at runtime; the .ico is
# only the executable's own resource, so it does not need to be bundled.
DATAS = [
    (os.path.join(REPO_ROOT, "resources", "icons", "openusbip.svg"), "resources/icons"),
]


# --- pruning ------------------------------------------------------------------
#
# Listing a module in `excludes` stops PyInstaller importing it, but does not
# stop the corresponding Qt shared library being pulled in as a dependency of
# some other plugin. A stock build therefore still ships the whole QML and
# Quick stack -- around 70 MB of it -- for an app built entirely from Qt
# Widgets. These are dropped from the collected file lists after analysis.

#: Substrings matched against the *destination* path of each collected file.
#: Deliberately specific: a loose pattern here removes something Qt loads
#: lazily and the failure appears only at runtime, as a blank window.
PRUNE_PATTERNS = (
    # The QML/Quick stack and everything built on it.
    "Qt6Qml",
    "Qt6Quick",
    "Qt63D",
    "Qt6ShaderTools",
    "Qt6Pdf",
    "Qt6Charts",
    "Qt6DataVisualization",
    "Qt6Designer",
    "Qt6Multimedia",
    "Qt6Sql",
    "Qt6Test",
    "Qt6Bluetooth",
    "Qt6Nfc",
    "Qt6Positioning",
    "Qt6Sensors",
    "Qt6SerialPort",
    "Qt6SpatialAudio",
    "Qt6TextToSpeech",
    "Qt6WebChannel",
    "Qt6WebEngine",
    "Qt6WebSockets",
    "Qt6RemoteObjects",
    "Qt6Scxml",
    "Qt6VirtualKeyboard",
    # The QML module tree, which is useless without the libraries above.
    os.path.join("PySide6", "Qt", "qml"),
    os.path.join("PySide6", "Qt", "qsci"),
)


def _wanted(entry) -> bool:
    dest = entry[0].replace("\\", "/")
    return not any(p.replace("\\", "/") in dest for p in PRUNE_PATTERNS)


def prune(analysis) -> None:
    """
    Drop the collected files matching PRUNE_PATTERNS, in place.

    Called from each .spec after Analysis and before EXE.
    """
    for attr in ("binaries", "datas"):
        table = getattr(analysis, attr)
        kept = [entry for entry in table if _wanted(entry)]
        removed = len(table) - len(kept)
        if removed:
            print(f"[openusbip] pruned {removed} unused {attr}")
        table[:] = kept
