"""
Tests for the USB/IP command-line wrapper.

The output parsers are the most fragile part of the project: they consume the
free-form text of whatever `usbip` build a distro happens to ship. They are
also pure functions over strings, so they are cheap to pin down here.
"""

import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from src.core.usbip_wrapper import (  # noqa: E402
    DEFAULT_USBIP_PORT,
    DeviceState,
    USBDevice,
    USBIPWrapper,
)

# --- fixtures: real `usbip` output, captured verbatim -----------------------

LIST_LOCAL_OUTPUT = """\
 - busid 1-1 (046d:c52b)
   Logitech, Inc. : Unifying Receiver (046d:c52b)

 - busid 3-2 (0781:5583)
   SanDisk Corp. : Ultra Fit (0781:5583)
"""

LIST_REMOTE_OUTPUT = """\
Exportable USB devices
======================
 - 192.168.0.34
      1-1: Logitech, Inc. : Unifying Receiver (046d:c52b)
         : /sys/devices/pci0000:00/0000:00:14.0/usb1/1-1
         : (Defined at Interface level) (00/00/00)

      3-2: SanDisk Corp. : Ultra Fit (0781:5583)
         : /sys/devices/pci0000:00/0000:00:14.0/usb3/3-2
         : (Defined at Interface level) (00/00/00)
"""

PORT_OUTPUT = """\
Imported USB devices
====================
Port 00: <Port in Use> at High Speed(480Mbps)
       Logitech, Inc. : Unifying Receiver (046d:c52b)
       1-1 -> usbip://192.168.0.34:3240/1-1
           -> remote bus/dev 001/002
"""


@pytest.fixture
def wrapper(monkeypatch):
    """A wrapper with binary discovery stubbed out."""
    monkeypatch.setattr(
        "src.core.usbip_wrapper.find_command", lambda name: f"/usr/bin/{name}"
    )
    return USBIPWrapper()


def _stub_run(monkeypatch, stdout, recorder=None, returncode=0, admin_recorder=None):
    def fake_run(cmd, timeout=10, require_admin=False):
        if recorder is not None:
            recorder.append(cmd)
        if admin_recorder is not None:
            admin_recorder.append(require_admin)
        return returncode, stdout, ""

    monkeypatch.setattr("src.core.usbip_wrapper.run_command", fake_run)


# --- local device parsing ---------------------------------------------------


def test_list_local_parses_busid_and_ids(wrapper, monkeypatch):
    _stub_run(monkeypatch, LIST_LOCAL_OUTPUT)
    devices = wrapper.list_local_devices()

    assert [d.busid for d in devices] == ["1-1", "3-2"]
    assert devices[0].vendor_id == "046d"
    assert devices[0].product_id == "c52b"


def test_local_description_does_not_repeat_the_vid_pid(wrapper, monkeypatch):
    """Regression: description kept the trailing "(vid:pid)" and __str__ added
    a second copy, rendering "Unifying Receiver (046d:c52b) (046d:c52b)"."""
    _stub_run(monkeypatch, LIST_LOCAL_OUTPUT)
    device = wrapper.list_local_devices()[0]

    assert device.description == "Logitech, Inc. : Unifying Receiver"
    assert str(device).count("046d:c52b") == 1


def test_list_local_handles_empty_output(wrapper, monkeypatch):
    _stub_run(monkeypatch, "")
    assert wrapper.list_local_devices() == []


def test_list_local_uses_usbip_not_usbipd(monkeypatch):
    """Regression: the Linux path gated on usbipd being present but then ran
    usbip, so clients with usbip and no usbipd silently got an empty list."""
    monkeypatch.setattr(
        "src.core.usbip_wrapper.find_command",
        lambda name: "/usr/bin/usbip" if name == "usbip" else None,
    )
    w = USBIPWrapper()
    assert w._usbipd_cmd is None

    calls = []
    _stub_run(monkeypatch, LIST_LOCAL_OUTPUT, recorder=calls)
    assert len(w.list_local_devices()) == 2


# --- remote device parsing --------------------------------------------------


def test_list_remote_parses_devices(wrapper, monkeypatch):
    _stub_run(monkeypatch, LIST_REMOTE_OUTPUT)
    devices = wrapper.list_remote_devices("192.168.0.34")

    assert [d.busid for d in devices] == ["1-1", "3-2"]
    assert devices[1].description == "SanDisk Corp. : Ultra Fit"
    assert devices[1].vendor_id == "0781"


def test_list_remote_ignores_the_sysfs_continuation_lines(wrapper, monkeypatch):
    """The ": /sys/devices/..." lines must not be mistaken for devices."""
    _stub_run(monkeypatch, LIST_REMOTE_OUTPUT)
    devices = wrapper.list_remote_devices("192.168.0.34")

    assert len(devices) == 2
    assert not any("/sys/devices" in d.description for d in devices)


# --- the --tcp-port regression ----------------------------------------------


def test_default_port_omits_the_tcp_port_flag(wrapper, monkeypatch):
    calls = []
    _stub_run(monkeypatch, LIST_REMOTE_OUTPUT, recorder=calls)
    wrapper.list_remote_devices("10.0.0.5", DEFAULT_USBIP_PORT)

    assert "--tcp-port" not in calls[0]


def test_custom_port_is_passed_as_a_global_flag_before_the_subcommand(
    wrapper, monkeypatch
):
    """Regression: `port` was accepted everywhere and silently dropped, so any
    server not on 3240 failed while the UI reported it reachable. usbip takes
    --tcp-port as a global option, so it must precede the subcommand."""
    calls = []
    _stub_run(monkeypatch, LIST_REMOTE_OUTPUT, recorder=calls)
    wrapper.list_remote_devices("10.0.0.5", 9999)

    cmd = calls[0]
    assert "--tcp-port" in cmd and "9999" in cmd
    assert cmd.index("--tcp-port") < cmd.index("list")


def test_attach_passes_the_custom_port_too(wrapper, monkeypatch):
    calls = []
    _stub_run(monkeypatch, "", recorder=calls)
    ok, _ = wrapper.attach_device("10.0.0.5", "1-1", 9999)

    cmd = calls[0]
    assert ok
    assert cmd.index("--tcp-port") < cmd.index("attach")
    assert cmd[cmd.index("--tcp-port") + 1] == "9999"


# --- attached device parsing ------------------------------------------------


def test_list_attached_parses_port_and_remote(wrapper, monkeypatch):
    _stub_run(monkeypatch, PORT_OUTPUT)
    attached = wrapper.list_attached_devices()

    assert len(attached) == 1
    assert attached[0]["port"] == "00"
    assert attached[0]["remote_host"] == "192.168.0.34"
    assert attached[0]["busid"] == "1-1"


def test_list_attached_handles_no_devices(wrapper, monkeypatch):
    _stub_run(monkeypatch, "Imported USB devices\n====================\n")
    assert wrapper.list_attached_devices() == []


# --- failure propagation ----------------------------------------------------


def test_attach_surfaces_the_error_message(wrapper, monkeypatch):
    def fake_run(cmd, timeout=10, require_admin=False):
        return 1, "", "usbip: error: could not connect to 10.0.0.5:3240"

    monkeypatch.setattr("src.core.usbip_wrapper.run_command", fake_run)
    ok, message = wrapper.attach_device("10.0.0.5", "1-1")

    assert not ok
    assert "could not connect" in message


def test_missing_binary_is_reported_not_crashed(monkeypatch):
    monkeypatch.setattr("src.core.usbip_wrapper.find_command", lambda name: None)
    w = USBIPWrapper()

    assert not w.is_available()
    ok, message = w.attach_device("10.0.0.5", "1-1")
    assert not ok and "not found" in message


# --- USBDevice ---------------------------------------------------------------


def test_device_display_name():
    d = USBDevice("1-1", "046d", "c52b", "Unifying Receiver", DeviceState.AVAILABLE)
    assert d.display_name == "Unifying Receiver [1-1]"


# --- privilege requirements ------------------------------------------------


def test_list_attached_asks_for_root(wrapper, monkeypatch):
    """Regression: `usbip port` reads the vhci state and needs root. Without
    require_admin the tool emitted "libusbip: error: fopen" and empty output,
    so this returned [] and the GUI's attached table was permanently empty --
    no device could be detached from the UI."""
    admin = []
    _stub_run(monkeypatch, PORT_OUTPUT, admin_recorder=admin)
    wrapper.list_attached_devices()

    assert admin == [True]


def test_attach_and_detach_ask_for_root(wrapper, monkeypatch):
    admin = []
    _stub_run(monkeypatch, "", admin_recorder=admin)
    wrapper.attach_device("10.0.0.5", "1-1")
    wrapper.detach_device("00")

    assert admin == [True, True]


def test_listing_remote_devices_does_not_need_root(wrapper, monkeypatch):
    """`usbip list -r` is a plain network query; escalating would prompt for
    nothing."""
    admin = []
    _stub_run(monkeypatch, LIST_REMOTE_OUTPUT, admin_recorder=admin)
    wrapper.list_remote_devices("10.0.0.5")

    assert admin == [False]


# --- usbip-win2 output ------------------------------------------------------
#
# The Windows client is a separate implementation, not a port of the kernel
# tool, and it formats two of these listings differently. Both differences are
# silent: the commands succeed, the parser matches nothing, and the GUI simply
# shows an empty list. These fixtures are transcribed from the format strings
# in usbip-win2's own source (userspace/usbip/list.cpp and port.cpp).

WIN_LIST_REMOTE_OUTPUT = """\
Exportable USB devices
======================
 - 192.168.0.34
    1-1    : Logitech, Inc. : Unifying Receiver (046d:c52b)
           : /sys/devices/pci0000:00/0000:00:14.0/usb1/1-1
           : (Defined at Interface level) (00/00/00)

    3-2    : SanDisk Corp. : Ultra Fit (0781:5583)
           : /sys/devices/pci0000:00/0000:00:14.0/usb3/3-2
           :  0 - Mass Storage / SCSI / Bulk-Only (08/06/50)
"""

WIN_PORT_OUTPUT = """\
Imported USB devices
====================
Port 01: device in use at High Speed(480Mbps)
         SanDisk Corp. : Ultra Fit (0781:5583)
           -> usbip://192.168.0.34:3240/3-2
           -> remote bus/dev: 003/004
           -> serial: 4C530001234567890
           -> mode: zero-copy
"""


def test_list_remote_handles_the_centred_busid_of_usbip_win2(wrapper, monkeypatch):
    # Linux right-aligns the busid ("        1-1: ..."), usbip-win2 centres it
    # ("    1-1    : ..."), so the colon is not adjacent to the busid.
    _stub_run(monkeypatch, WIN_LIST_REMOTE_OUTPUT)
    devices = wrapper.list_remote_devices("192.168.0.34")

    assert [d.busid for d in devices] == ["1-1", "3-2"]
    assert devices[1].vendor_id == "0781"
    assert devices[1].description == "SanDisk Corp. : Ultra Fit"


def test_list_remote_still_ignores_continuation_lines_on_windows(wrapper, monkeypatch):
    # Loosening the busid pattern must not start matching the indented sysfs
    # path or the interface-class lines that follow each device.
    _stub_run(monkeypatch, WIN_LIST_REMOTE_OUTPUT)
    assert len(wrapper.list_remote_devices("192.168.0.34")) == 2


def test_list_attached_parses_windows_output_without_a_local_busid(
    wrapper, monkeypatch
):
    # usbip-win2 prints "  -> usbip://host:port/busid" with nothing before the
    # arrow. Requiring a local busid there left the attached table empty, so
    # nothing could be detached from the GUI.
    _stub_run(monkeypatch, WIN_PORT_OUTPUT)
    attached = wrapper.list_attached_devices()

    assert len(attached) == 1
    assert attached[0]["port"] == "01"
    assert attached[0]["busid"] == "3-2"
    assert attached[0]["remote_host"] == "192.168.0.34"
    assert attached[0]["remote_port"] == "3240"


def test_windows_attached_entry_still_has_a_local_busid_value(wrapper, monkeypatch):
    # The GUI renders this column unconditionally; falling back to the remote
    # busid keeps it from showing "None".
    _stub_run(monkeypatch, WIN_PORT_OUTPUT)
    assert wrapper.list_attached_devices()[0]["local_busid"] == "3-2"


def test_linux_local_busid_is_still_preferred_when_present(wrapper, monkeypatch):
    # The optional group must not swallow the Linux form, where the local and
    # remote busids are genuinely different values worth telling apart.
    _stub_run(monkeypatch, PORT_OUTPUT)
    entry = wrapper.list_attached_devices()[0]
    assert entry["local_busid"] == "1-1"
    assert entry["busid"] == "1-1"
