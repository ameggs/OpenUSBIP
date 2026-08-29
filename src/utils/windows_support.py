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

import json
import os
import re
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
USBIPD_WIN_URL = "https://github.com/dorssel/usbipd-win/releases"


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


def missing_server_message() -> str:
    """Explain that the server tool is absent and how to fix it."""
    return (
        "usbipd-win was not found.\n\n"
        "Sharing this PC's USB devices needs usbipd-win:\n\n"
        f"  {USBIPD_WIN_URL}\n\n"
        "Install the .msi, then restart OpenUSBIP."
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


# --- usbipd-win output --------------------------------------------------------
#
# usbipd-win offers two ways to enumerate devices. `usbipd list` prints a
# fixed-width table with two sections and a trailing status report; `usbipd
# state` prints JSON that the project maintains as a documented, machine-
# readable interface. Prefer the JSON: the table truncates the device
# description at 60 columns and pads it with the same spaces that separate the
# columns, so any whitespace-splitting parse of it silently mangles both the
# description and the state.

#: Device states as reported to the rest of the app. Deliberately plain strings
#: so this module stays a leaf and does not import from src.core.
STATE_ATTACHED = "attached"
STATE_SHARED = "shared"
STATE_NOT_SHARED = "not-shared"


def parse_hardware_id(instance_id: str) -> tuple[str, str]:
    """
    Pull the vendor and product ids out of a Windows device instance id.

    Instance ids look like ``USB\\VID_046D&PID_C52B\\5&1234ABCD&0&1``. Returns
    ("0000", "0000") when the id is not of that shape, which happens for
    non-USB or composite entries rather than being an error.
    """
    match = re.search(r"VID_([0-9A-F]{4})&PID_([0-9A-F]{4})", instance_id or "", re.I)
    if not match:
        return "0000", "0000"
    return match.group(1).lower(), match.group(2).lower()


def parse_state_json(text: str) -> list[dict[str, str]]:
    """
    Parse the output of ``usbipd state`` into plain device dicts.

    Devices with no BusId are skipped: usbipd-win lists devices that are
    *persisted* for sharing but not currently plugged in, and those cannot be
    attached by a client.

    Returns [] rather than raising on malformed input -- a device list is a
    thing the GUI polls, so a bad parse should show nothing, not crash a
    refresh.
    """
    try:
        devices = json.loads(text)["Devices"]
    except Exception:
        return []
    if not isinstance(devices, list):
        return []

    out: list[dict[str, str]] = []
    for dev in devices:
        if not isinstance(dev, dict):
            continue
        busid = dev.get("BusId")
        if not busid:
            continue  # persisted but not connected

        # usbipd-win derives these the same way: a client address means it is
        # attached right now; a persisted guid means it is merely offered.
        if dev.get("ClientIPAddress"):
            state = STATE_ATTACHED
        elif dev.get("PersistedGuid"):
            state = STATE_SHARED
        else:
            state = STATE_NOT_SHARED

        vendor_id, product_id = parse_hardware_id(dev.get("InstanceId", ""))
        out.append({
            "busid": str(busid),
            "vendor_id": vendor_id,
            "product_id": product_id,
            "description": dev.get("Description") or "Unknown Device",
            "state": state,
            "client": dev.get("ClientIPAddress") or "",
        })
    return out


def parse_list_output(text: str) -> list[dict[str, str]]:
    """
    Parse ``usbipd list``, for installations too old to have ``state``.

    The table is fixed-width -- BUSID(5) + 2, VID:PID(9) + 2, DEVICE(60) + 2,
    then STATE -- and both the device name and the state contain spaces, so it
    is sliced by column rather than split on whitespace. Only the "Connected:"
    section is read; the "Persisted:" section that follows lists devices that
    are not plugged in, and after it comes free-form server status text.
    """
    BUSID_END = 5
    VIDPID_START, VIDPID_END = 7, 16
    DESC_START, DESC_END = 18, 78

    out: list[dict[str, str]] = []
    in_connected = False

    for line in text.splitlines():
        stripped = line.strip()

        if not in_connected:
            in_connected = stripped == "Connected:"
            continue
        if not stripped or stripped == "Persisted:":
            break  # end of the section we care about
        if line.startswith("BUSID"):
            continue  # column header

        busid = line[:BUSID_END].strip()
        vid_pid = line[VIDPID_START:VIDPID_END].strip()
        description = line[DESC_START:DESC_END].strip()
        state_str = line[DESC_END:].strip().lower()

        # An incompatible hub is listed with a blank busid and cannot be shared.
        if not busid:
            continue

        if ":" in vid_pid:
            vendor_id, product_id = vid_pid.split(":", 1)
        else:
            vendor_id, product_id = "0000", "0000"

        # Order matters: the "Not shared" state contains the word "shared",
        # so a plain substring test reports unshared devices as available.
        if "attached" in state_str:
            state = STATE_ATTACHED
        elif "not shared" in state_str:
            state = STATE_NOT_SHARED
        elif "shared" in state_str:
            state = STATE_SHARED
        else:
            # "Allowed" and "Incompatible hub" both land here.
            state = STATE_NOT_SHARED

        out.append({
            "busid": busid,
            "vendor_id": vendor_id.lower(),
            "product_id": product_id.lower(),
            "description": description or "Unknown Device",
            "state": state,
            "client": "",
        })

    return out
