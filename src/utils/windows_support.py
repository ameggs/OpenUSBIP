"""
Windows-specific helpers for locating and running the USB/IP tools.

Windows differs from Linux in two ways that matter here, and both are handled
as pure functions so they can be unit-tested on any platform:

1. **The tools are not on PATH.** usbip-win2 and usbipd-win install under
   Program Files, and neither adds itself to the system PATH, so `shutil.which`
   finds nothing on a perfectly working installation.

2. **There is no `sudo`.** Attaching a device needs Administrator, and a
   process either has it or does not -- it cannot acquire it mid-run. So rather
   than escalating per command as the Linux path does, the app has to detect
   the situation and tell the user to restart elevated.
"""

from __future__ import annotations

import os
from collections.abc import Callable

# --- where the tools actually live -------------------------------------------

#: Directory names, relative to a Program Files root, that the tools install to.
#: usbip-win2 is the client (it ships the vhci driver); usbipd-win is the
#: server. A machine may have either, both, or neither.
INSTALL_SUBDIRS = (
    "usbip-win2",
    "usbip-win",
    "usbipd-win",
    os.path.join("usbipd-win", "bin"),
    "USBIP",
)


def program_files_roots(environ: dict[str, str] | None = None) -> list[str]:
    """
    The Program Files directories to search, most likely first.

    A 64-bit tool on a 64-bit host lands in ProgramFiles; a 32-bit build lands
    in ProgramFiles(x86). ProgramW6432 is included because a 32-bit Python
    process sees a redirected ProgramFiles and would otherwise miss the 64-bit
    installation entirely.
    """
    env = environ if environ is not None else os.environ
    roots = [
        env.get("ProgramW6432"),
        env.get("ProgramFiles"),
        env.get("ProgramFiles(x86)"),
    ]
    seen: set[str] = set()
    out: list[str] = []
    for root in roots:
        if root and root not in seen:
            seen.add(root)
            out.append(root)
    return out


def candidate_paths(command: str, environ: dict[str, str] | None = None) -> list[str]:
    """
    Every place [command] might reasonably be, in search order.

    Returns paths whether or not they exist; the caller checks. Keeping the
    generation separate from the filesystem check is what makes this testable
    without a Windows machine.
    """
    exe = command if command.lower().endswith(".exe") else f"{command}.exe"
    paths: list[str] = []
    for root in program_files_roots(environ):
        for subdir in INSTALL_SUBDIRS:
            paths.append(os.path.join(root, subdir, exe))
    return paths


def find_tool(
    command: str,
    environ: dict[str, str] | None = None,
    exists: Callable[[str], bool] | None = None,
) -> str | None:
    """
    First existing candidate for [command], or None.

    [exists] is injectable so tests can describe a filesystem without one.
    """
    check = exists or os.path.isfile
    for path in candidate_paths(command, environ):
        if check(path):
            return path
    return None


# --- privilege ----------------------------------------------------------------


def is_elevated() -> bool:
    """
    Whether this process is running as Administrator.

    Returns False on any error rather than raising: a wrong answer here should
    degrade to "tell the user to elevate", never crash the app.
    """
    try:
        import ctypes

        return bool(ctypes.windll.shell32.IsUserAnAdmin())  # type: ignore[attr-defined]
    except Exception:
        return False


# --- user-facing guidance -----------------------------------------------------

USBIP_WIN2_URL = "https://github.com/vadimgrn/usbip-win2/releases"


def missing_client_message() -> str:
    """
    Explain that the client driver is absent and how to fix it.

    Worth being specific: Windows cannot attach a remote USB device without a
    kernel-mode virtual host controller, and that has to come from a signed
    driver package. This app deliberately does not ship one.
    """
    return (
        "USB/IP client tools were not found.\n\n"
        "Attaching a remote device on Windows needs a kernel driver (a virtual "
        "USB host controller). OpenUSBIP does not ship one, so install "
        "usbip-win2, which provides a signed driver:\n\n"
        f"  {USBIP_WIN2_URL}\n\n"
        "Install the .msi, reboot if prompted, then restart OpenUSBIP."
    )


def needs_elevation_message(action: str = "attach or detach devices") -> str:
    """
    Explain the elevation requirement.

    Unlike Linux there is no per-command escalation to offer, so the only
    honest advice is to restart the whole application elevated.
    """
    return (
        f"Administrator rights are required to {action}.\n\n"
        "Windows has no per-command elevation, so close OpenUSBIP and start it "
        'again with "Run as administrator".'
    )
