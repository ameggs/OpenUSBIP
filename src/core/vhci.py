"""
Work out what a USB/IP attach actually produced on this machine.

`usbip attach` succeeds without telling you what appeared. Something is now
plugged into the virtual host controller, but turning that into "the phone is
at /dev/sdc" or "this is an MTP device" means reading sysfs.

Correlation is done by filtering to devices whose real sysfs path runs through
`vhci_hcd`, rather than by diffing the device list before and after. A diff
looks simpler until someone plugs in a real USB stick at the same moment, at
which point it attributes the wrong device to the attach.

Every function takes `sysfs_root` so the whole module can be exercised against
a synthetic tree in a temp directory. That matters more than usual here: the
alternative is testing only on a machine that happens to have a device
attached, which means in practice not testing it.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field

#: USB class 6 is "Still Image"; MTP and PTP both live there. Android's MTP
#: interface uses subclass 1 / protocol 1, as does a PTP-only camera -- the
#: distinction is made by the Microsoft OS descriptor, which is not exposed in
#: sysfs. Treating both as MTP is right: libmtp drives PTP cameras too.
USB_CLASS_STILL_IMAGE = 0x06

#: Mass storage. Its block devices are found through sysfs rather than by
#: class, so this is only used to label the device for the UI.
USB_CLASS_MASS_STORAGE = 0x08


@dataclass
class AttachedDevice:
    """One USB device currently attached through the virtual host controller."""

    busid: str
    sysfs_path: str
    vendor_id: str = "0000"
    product_id: str = "0000"
    manufacturer: str = ""
    product: str = ""
    serial: str = ""
    interface_classes: list[int] = field(default_factory=list)
    block_devices: list[str] = field(default_factory=list)

    @property
    def is_mtp(self) -> bool:
        return USB_CLASS_STILL_IMAGE in self.interface_classes

    @property
    def is_mass_storage(self) -> bool:
        """
        Whether the device *declares* itself as mass storage.

        Decided by the interface class, not by whether block devices exist
        yet. Those are created by usb-storage milliseconds after the device
        enumerates, so a device inspected the instant it appears has none --
        and deciding "not storage" from that is circular: it is the reason to
        wait for them in the first place.
        """
        return USB_CLASS_MASS_STORAGE in self.interface_classes

    @property
    def is_mountable(self) -> bool:
        """Whether there is actually something to mount right now."""
        return bool(self.block_devices)

    @property
    def description(self) -> str:
        name = " ".join(p for p in (self.manufacturer, self.product) if p).strip()
        return name or f"USB device {self.vendor_id}:{self.product_id}"

    @property
    def kind(self) -> str:
        """
        What the UI should offer to do with it.

        Storage wins over MTP for a device that claims both -- some phones do
        -- because mounting a filesystem is the more direct route to the files
        than going through MTP.
        """
        if self.is_mass_storage:
            return "storage"
        if self.is_mtp:
            return "mtp"
        return "other"


def _read(path: str) -> str:
    """Read a sysfs attribute, returning "" for anything unreadable."""
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read().strip()
    except OSError:
        # sysfs attributes disappear mid-read when a device is unplugged, and
        # some are root-only. Neither is worth an exception here.
        return ""


def _is_usb_device_dir(entry: str) -> bool:
    """
    Whether a /sys/bus/usb/devices entry is a device rather than an interface.

    Interfaces are named like "1-1:1.0"; devices are "1-1" or "usb1". Root hubs
    ("usb1") are devices too but are never what was attached.
    """
    return ":" not in entry and not entry.startswith("usb")


def is_vhci_path(real_path: str) -> bool:
    """
    Whether a resolved sysfs path belongs to the virtual host controller.

    The platform device is named `vhci_hcd.0`, so the check is on a path
    component rather than a substring -- a real device on a bus whose name
    merely contained the text would otherwise match.
    """
    return any(part.startswith("vhci_hcd") for part in real_path.split(os.sep))


def find_block_devices(device_path: str) -> list[str]:
    """
    The block device names (`sdc`, ...) that a USB device produced.

    Walks the SCSI chain the usb-storage driver builds beneath the interface:
    `<dev>:1.0/host4/target4:0:0/4:0:0:0/block/sdc`. Walking is used rather
    than a fixed glob because the host, target and lun numbers all vary, and a
    multi-LUN device (a card reader with several slots) yields several.
    """
    found: list[str] = []
    for root, dirnames, _files in os.walk(device_path):
        if os.path.basename(root) == "block":
            found.extend(sorted(dirnames))
            dirnames[:] = []  # nothing useful below block/sdX
    return sorted(set(found))


def read_interface_classes(device_path: str) -> list[int]:
    """The bInterfaceClass of each interface of this device."""
    busid = os.path.basename(device_path)
    classes: list[int] = []
    try:
        entries = sorted(os.listdir(device_path))
    except OSError:
        return []

    for entry in entries:
        # Interfaces of this device are named "<busid>:<config>.<num>".
        if not entry.startswith(f"{busid}:"):
            continue
        raw = _read(os.path.join(device_path, entry, "bInterfaceClass"))
        if raw:
            try:
                classes.append(int(raw, 16))
            except ValueError:
                continue
    return classes


def describe_device(device_path: str) -> AttachedDevice:
    """Build an AttachedDevice from a sysfs USB device directory."""
    return AttachedDevice(
        busid=os.path.basename(device_path),
        sysfs_path=device_path,
        vendor_id=_read(os.path.join(device_path, "idVendor")) or "0000",
        product_id=_read(os.path.join(device_path, "idProduct")) or "0000",
        manufacturer=_read(os.path.join(device_path, "manufacturer")),
        product=_read(os.path.join(device_path, "product")),
        serial=_read(os.path.join(device_path, "serial")),
        interface_classes=read_interface_classes(device_path),
        block_devices=find_block_devices(device_path),
    )


def list_attached(sysfs_root: str = "/sys") -> list[AttachedDevice]:
    """
    Every USB device currently reachable through the virtual host controller.

    Returns [] when vhci_hcd is not loaded or nothing is attached, which are
    both normal states rather than errors.
    """
    usb_devices = os.path.join(sysfs_root, "bus", "usb", "devices")
    try:
        entries = sorted(os.listdir(usb_devices))
    except OSError:
        return []

    devices: list[AttachedDevice] = []
    for entry in entries:
        if not _is_usb_device_dir(entry):
            continue
        path = os.path.join(usb_devices, entry)
        if not is_vhci_path(os.path.realpath(path)):
            continue
        devices.append(describe_device(path))
    return devices


def wait_for_new_device(
    known_busids: set[str],
    sysfs_root: str = "/sys",
    timeout: float = 12.0,
    interval: float = 0.25,
    sleep=None,
    now=None,
) -> AttachedDevice | None:
    """
    Poll until a vhci device appears that was not in `known_busids`.

    An attach returns as soon as the connection is made, but the device is
    still enumerating: block devices in particular show up a second or more
    later, once usb-storage has probed the medium. Returning immediately gives
    a device with no partitions and nothing to mount, so this waits for the
    device and then for it to settle.

    `sleep` and `now` are injectable so tests do not spend twelve seconds
    proving the timeout works.
    """
    import time as _time

    sleep = sleep or _time.sleep
    now = now or _time.monotonic

    deadline = now() + timeout
    while True:
        for device in list_attached(sysfs_root):
            if device.busid not in known_busids:
                return device
        if now() >= deadline:
            return None
        sleep(interval)


def wait_for_block_devices(
    device: AttachedDevice,
    timeout: float = 10.0,
    interval: float = 0.25,
    sleep=None,
    now=None,
) -> list[str]:
    """
    Re-poll a device until its block devices appear, or give up.

    Separate from wait_for_new_device because an MTP device legitimately never
    grows one, so this is only worth calling once the device is known to claim
    mass storage.
    """
    import time as _time

    sleep = sleep or _time.sleep
    now = now or _time.monotonic

    deadline = now() + timeout
    while True:
        found = find_block_devices(device.sysfs_path)
        if found:
            device.block_devices = found
            return found
        if now() >= deadline:
            return []
        sleep(interval)
