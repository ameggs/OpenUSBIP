"""
Tests for working out what an attach produced.

These build a synthetic /sys tree in a temp directory. That is the only way to
test this at all: the real thing requires a USB/IP server, a device attached to
it, and root -- so without a fake tree, the code that decides whether to offer
"mount" or "browse over MTP" would ship unexercised.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from src.core import vhci  # noqa: E402


def build_sysfs(tmp_path, devices):
    """
    Create a /sys tree.

    `devices` maps busid -> spec dict:
        controller       "vhci_hcd.0" (default) or e.g. "0000:00:14.0"
        attrs            sysfs attribute files
        interfaces       list of {"class": "08", ...} per interface
        block            block device names to hang off the SCSI chain
    """
    sysfs = tmp_path / "sys"
    bus_dir = sysfs / "bus" / "usb" / "devices"
    bus_dir.mkdir(parents=True)

    for busid, spec in devices.items():
        controller = spec.get("controller", "vhci_hcd.0")
        real = sysfs / "devices" / "platform" / controller / "usb1" / busid
        real.mkdir(parents=True)

        for name, value in spec.get("attrs", {}).items():
            (real / name).write_text(value + "\n")

        for index, iface in enumerate(spec.get("interfaces", [])):
            iface_dir = real / f"{busid}:1.{index}"
            iface_dir.mkdir()
            (iface_dir / "bInterfaceClass").write_text(iface["class"] + "\n")

        for index, block_name in enumerate(spec.get("block", [])):
            # The chain usb-storage builds: interface / host / target / lun.
            lun = real / f"{busid}:1.0" / "host4" / "target4:0:0" / f"4:0:{index}:0"
            (lun / "block" / block_name).mkdir(parents=True)

        os.symlink(real, bus_dir / busid)

    # A root hub and an interface entry, both of which must be ignored.
    (bus_dir / "usb1").mkdir()
    (bus_dir / "1-1:1.0").mkdir(exist_ok=True)
    return str(sysfs)


PHONE = {
    "attrs": {
        "idVendor": "18d1",
        "idProduct": "4ee1",
        "manufacturer": "motorola",
        "product": "moto g",
        "serial": "ZT42224MCC",
    },
    "interfaces": [{"class": "06"}],
}

FLASH_DRIVE = {
    "attrs": {
        "idVendor": "0781",
        "idProduct": "5583",
        "manufacturer": "SanDisk",
        "product": "Ultra Fit",
    },
    "interfaces": [{"class": "08"}],
    "block": ["sdc"],
}


class TestListAttached:
    def test_finds_a_device_on_the_virtual_controller(self, tmp_path):
        sysfs = build_sysfs(tmp_path, {"7-1": PHONE})
        devices = vhci.list_attached(sysfs)
        assert [d.busid for d in devices] == ["7-1"]

    def test_ignores_devices_on_a_real_controller(self, tmp_path):
        # This is the whole reason for matching on the controller rather than
        # diffing before and after: a real device plugged in at the same
        # moment must not be mistaken for the one that was attached.
        sysfs = build_sysfs(
            tmp_path,
            {
                "7-1": PHONE,
                "1-2": {**FLASH_DRIVE, "controller": "0000:00:14.0"},
            },
        )
        assert [d.busid for d in vhci.list_attached(sysfs)] == ["7-1"]

    def test_ignores_root_hubs_and_interface_entries(self, tmp_path):
        sysfs = build_sysfs(tmp_path, {"7-1": PHONE})
        busids = [d.busid for d in vhci.list_attached(sysfs)]
        assert "usb1" not in busids
        assert "1-1:1.0" not in busids

    def test_missing_sysfs_is_empty_not_an_error(self, tmp_path):
        # vhci_hcd not loaded is a normal state, and the UI asks for this list
        # on every refresh.
        assert vhci.list_attached(str(tmp_path / "nope")) == []


class TestDeviceProperties:
    def test_reads_the_identifying_attributes(self, tmp_path):
        sysfs = build_sysfs(tmp_path, {"7-1": PHONE})
        device = vhci.list_attached(sysfs)[0]
        assert device.vendor_id == "18d1"
        assert device.product_id == "4ee1"
        assert device.serial == "ZT42224MCC"
        assert device.description == "motorola moto g"

    def test_falls_back_to_ids_when_there_are_no_strings(self, tmp_path):
        bare = {"attrs": {"idVendor": "1234", "idProduct": "5678"}}
        sysfs = build_sysfs(tmp_path, {"7-1": bare})
        assert vhci.list_attached(sysfs)[0].description == "USB device 1234:5678"

    def test_still_image_class_is_reported_as_mtp(self, tmp_path):
        sysfs = build_sysfs(tmp_path, {"7-1": PHONE})
        device = vhci.list_attached(sysfs)[0]
        assert device.is_mtp
        assert device.kind == "mtp"

    def test_finds_the_block_device_of_a_flash_drive(self, tmp_path):
        sysfs = build_sysfs(tmp_path, {"7-1": FLASH_DRIVE})
        device = vhci.list_attached(sysfs)[0]
        assert device.block_devices == ["sdc"]
        assert device.kind == "storage"

    def test_finds_every_lun_of_a_card_reader(self, tmp_path):
        reader = {**FLASH_DRIVE, "block": ["sdc", "sdd", "sde"]}
        sysfs = build_sysfs(tmp_path, {"7-1": reader})
        assert vhci.list_attached(sysfs)[0].block_devices == ["sdc", "sdd", "sde"]

    def test_storage_is_recognised_before_its_block_devices_appear(self, tmp_path):
        # The regression this pins: usb-storage creates block devices a moment
        # after the USB device enumerates, so a real card reader inspected the
        # instant it attaches has none. Deciding "not storage" from that is
        # circular -- it is precisely why the caller must wait for them.
        fresh = {**FLASH_DRIVE, "block": []}
        sysfs = build_sysfs(tmp_path, {"7-1": fresh})
        device = vhci.list_attached(sysfs)[0]
        assert device.is_mass_storage      # declared by the interface class
        assert not device.is_mountable     # but nothing to mount yet
        assert device.kind == "storage"

    def test_a_mounted_drive_is_both_storage_and_mountable(self, tmp_path):
        sysfs = build_sysfs(tmp_path, {"7-1": FLASH_DRIVE})
        device = vhci.list_attached(sysfs)[0]
        assert device.is_mass_storage and device.is_mountable

    def test_a_phone_is_mtp_and_not_storage(self, tmp_path):
        sysfs = build_sysfs(tmp_path, {"7-1": PHONE})
        device = vhci.list_attached(sysfs)[0]
        assert not device.is_mass_storage
        assert device.is_mtp

    def test_a_device_can_be_both_storage_and_mtp(self, tmp_path):
        # Some phones expose MTP and a mass-storage LUN at once; storage wins
        # because mounting it is the more direct route to the files.
        both = {
            "attrs": {"idVendor": "18d1", "idProduct": "4ee1"},
            "interfaces": [{"class": "06"}, {"class": "08"}],
            "block": ["sdc"],
        }
        sysfs = build_sysfs(tmp_path, {"7-1": both})
        device = vhci.list_attached(sysfs)[0]
        assert device.is_mtp and device.is_mass_storage
        assert device.kind == "storage"
        assert device.is_mountable


class TestIsVhciPath:
    def test_matches_the_platform_device(self):
        assert vhci.is_vhci_path("/sys/devices/platform/vhci_hcd.0/usb7/7-1")

    def test_does_not_match_a_real_controller(self):
        assert not vhci.is_vhci_path("/sys/devices/pci0000:00/0000:00:14.0/usb1/1-2")

    def test_does_not_match_a_name_that_merely_contains_the_text(self):
        # Substring matching would accept this; component matching does not.
        assert not vhci.is_vhci_path("/sys/devices/platform/not_vhci_hcd_thing/usb1")


class TestWaiting:
    def test_returns_the_device_that_was_not_there_before(self, tmp_path):
        sysfs = build_sysfs(tmp_path, {"7-1": PHONE, "7-2": FLASH_DRIVE})
        found = vhci.wait_for_new_device({"7-1"}, sysfs, sleep=lambda s: None)
        assert found.busid == "7-2"

    def test_gives_up_and_returns_none(self, tmp_path):
        sysfs = build_sysfs(tmp_path, {"7-1": PHONE})
        ticks = iter([0.0, 5.0, 20.0])
        found = vhci.wait_for_new_device(
            {"7-1"}, sysfs, timeout=12.0, sleep=lambda s: None, now=lambda: next(ticks)
        )
        assert found is None

    def test_waits_for_a_block_device_that_arrives_late(self, tmp_path):
        # usb-storage probes the medium after the device enumerates, so a
        # freshly attached drive has no block device for a second or more.
        sysfs = build_sysfs(tmp_path, {"7-1": {**FLASH_DRIVE, "block": []}})
        device = vhci.list_attached(sysfs)[0]
        assert device.block_devices == []

        def appear(_seconds):
            lun = os.path.join(device.sysfs_path, "7-1:1.0", "host4", "target4:0:0")
            os.makedirs(os.path.join(lun, "4:0:0:0", "block", "sdc"), exist_ok=True)

        assert vhci.wait_for_block_devices(device, sleep=appear) == ["sdc"]
        assert device.block_devices == ["sdc"]

    def test_block_wait_gives_up_for_a_device_that_never_grows_one(self, tmp_path):
        sysfs = build_sysfs(tmp_path, {"7-1": PHONE})
        device = vhci.list_attached(sysfs)[0]
        ticks = iter([0.0, 3.0, 99.0])
        assert vhci.wait_for_block_devices(
            device, sleep=lambda s: None, now=lambda: next(ticks)
        ) == []
