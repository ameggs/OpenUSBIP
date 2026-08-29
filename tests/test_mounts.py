"""
Tests for mounting what an attach produced.

The unmount fence gets the most attention here. This code runs as root on a
hypervisor, and the failure it is designed to prevent -- unmounting live guest
storage -- cannot be undone by retrying. So the fence is tested against the
ways a path can pretend to be somewhere it is not, not just the happy case.
"""

import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from src.core import mounts  # noqa: E402
from src.core.mounts import MountError  # noqa: E402

# `lsblk -J` for a partitioned flash drive, an unpartitioned one, and a disk
# whose partition holds no filesystem.
LSBLK_PARTITIONED = """
{"blockdevices":[
  {"name":"sdc","path":"/dev/sdc","size":"29.8G","fstype":null,"label":null,
   "mountpoint":null,"type":"disk","children":[
     {"name":"sdc1","path":"/dev/sdc1","size":"29.8G","fstype":"exfat",
      "label":"PHOTOS","mountpoint":null,"type":"part"}]}]}
"""

LSBLK_WHOLE_DISK = """
{"blockdevices":[
  {"name":"sdd","path":"/dev/sdd","size":"2G","fstype":"vfat","label":"PHONEDISK",
   "mountpoint":null,"type":"disk","children":[]}]}
"""

LSBLK_NO_FILESYSTEM = """
{"blockdevices":[
  {"name":"sde","path":"/dev/sde","size":"1G","fstype":null,"label":null,
   "mountpoint":null,"type":"disk","children":[
     {"name":"sde1","path":"/dev/sde1","size":"1G","fstype":null,"label":null,
      "mountpoint":null,"type":"part"}]}]}
"""

LSBLK_MULTI_PARTITION = """
{"blockdevices":[
  {"name":"sdc","path":"/dev/sdc","size":"64G","fstype":null,"label":null,
   "mountpoint":null,"type":"disk","children":[
     {"name":"sdc1","path":"/dev/sdc1","size":"512M","fstype":"vfat",
      "label":"BOOT","mountpoint":null,"type":"part"},
     {"name":"sdc2","path":"/dev/sdc2","size":"63.5G","fstype":"ext4",
      "label":"data","mountpoint":null,"type":"part"},
     {"name":"sdc3","path":"/dev/sdc3","size":"8M","fstype":"swap",
      "label":null,"mountpoint":null,"type":"part"}]}]}
"""


class TestParseLsblk:
    def test_reads_a_partition_tree(self):
        disks = mounts.parse_lsblk(LSBLK_PARTITIONED)
        assert disks[0].name == "sdc"
        assert disks[0].children[0].label == "PHOTOS"

    @pytest.mark.parametrize("text", ["", "not json", "{}", '{"blockdevices":null}'])
    def test_malformed_output_is_empty_not_an_error(self, text):
        assert mounts.parse_lsblk(text) == []


class TestChooseMountTargets:
    def test_prefers_the_partition_over_the_whole_disk(self):
        # Mounting /dev/sdc when the filesystem is on /dev/sdc1 just fails.
        chosen = mounts.choose_mount_targets(mounts.parse_lsblk(LSBLK_PARTITIONED))
        assert [t.name for t in chosen] == ["sdc1"]

    def test_falls_back_to_the_whole_disk_when_unpartitioned(self):
        # What `mkfs` straight onto a drive gives you, and what the Android
        # app's virtual disk looks like after the client formats it.
        chosen = mounts.choose_mount_targets(mounts.parse_lsblk(LSBLK_WHOLE_DISK))
        assert [t.name for t in chosen] == ["sdd"]

    def test_skips_partitions_with_no_filesystem(self):
        assert mounts.choose_mount_targets(mounts.parse_lsblk(LSBLK_NO_FILESYSTEM)) == []

    def test_skips_swap_but_keeps_real_filesystems(self):
        chosen = mounts.choose_mount_targets(mounts.parse_lsblk(LSBLK_MULTI_PARTITION))
        assert [t.name for t in chosen] == ["sdc1", "sdc2"]

    def test_display_name_is_useful_in_a_list(self):
        chosen = mounts.choose_mount_targets(mounts.parse_lsblk(LSBLK_PARTITIONED))
        assert chosen[0].display_name == "PHOTOS · 29.8G · exfat"


class TestMountRootFence:
    """
    The fence that stops this program unmounting something it did not mount.
    """

    def test_accepts_a_path_it_owns(self, tmp_path):
        root = tmp_path / "run" / "openusbip"
        (root / "phone").mkdir(parents=True)
        assert mounts.is_within_mount_root(str(root / "phone"), str(root))

    def test_rejects_the_mount_root_itself(self, tmp_path):
        root = tmp_path / "run" / "openusbip"
        root.mkdir(parents=True)
        assert not mounts.is_within_mount_root(str(root), str(root))

    def test_rejects_a_sibling_with_a_shared_prefix(self, tmp_path):
        # "/run/openusbip-evil".startswith("/run/openusbip") is True, which is
        # exactly why this is not a startswith check.
        base = tmp_path / "run"
        root = base / "openusbip"
        evil = base / "openusbip-evil"
        root.mkdir(parents=True)
        evil.mkdir()
        assert not mounts.is_within_mount_root(str(evil), str(root))

    def test_rejects_a_traversal_back_out(self, tmp_path):
        root = tmp_path / "run" / "openusbip"
        root.mkdir(parents=True)
        escape = str(root / ".." / ".." / "Storage2")
        assert not mounts.is_within_mount_root(escape, str(root))

    def test_rejects_a_symlink_pointing_outside(self, tmp_path):
        # The dangerous shape: a mountpoint name under our root that is really
        # a link to somewhere that matters.
        root = tmp_path / "run" / "openusbip"
        root.mkdir(parents=True)
        outside = tmp_path / "Storage2"
        outside.mkdir()
        link = root / "phone"
        os.symlink(outside, link)
        assert not mounts.is_within_mount_root(str(link), str(root))

    @pytest.mark.parametrize(
        "path", ["/", "/mnt/pve/backup", "/Storage2", "/etc/pve", "relative/path", ""]
    )
    def test_rejects_paths_that_matter(self, path):
        assert not mounts.is_within_mount_root(path)

    def test_unmount_refuses_a_path_it_does_not_own(self, monkeypatch):
        # The single most important assertion in this file.
        def explode(*args, **kwargs):
            raise AssertionError("umount must never be reached for a foreign path")

        monkeypatch.setattr(mounts, "run_command", explode)

        with pytest.raises(MountError, match="refusing"):
            mounts.unmount("/Storage2")

    def test_mount_refuses_a_target_it_does_not_own(self, monkeypatch):
        def explode(*args, **kwargs):
            raise AssertionError("mount must never be reached for a foreign path")

        monkeypatch.setattr(mounts, "run_command", explode)

        with pytest.raises(MountError, match="refusing"):
            mounts.mount_block("/dev/sdc1", "/mnt/pve/backup")


class TestMountpointFor:
    def test_builds_a_path_under_the_mount_root(self):
        assert mounts.mountpoint_for("moto-g").startswith(mounts.MOUNT_ROOT + "/")

    @pytest.mark.parametrize(
        "name,expected",
        [
            ("moto g", "moto-g"),
            ("../../etc", "etc"),
            ("a/b/c", "a-b-c"),
            ("", "device"),
            ("...", "device"),
            ("SanDisk Ultra Fit", "SanDisk-Ultra-Fit"),
        ],
    )
    def test_reduces_a_device_supplied_name_to_a_safe_slug(self, name, expected):
        # The name comes from the USB device's own product string, which is
        # chosen by whoever plugged the device in.
        assert mounts.mountpoint_for(name) == os.path.join(mounts.MOUNT_ROOT, expected)

    def test_every_slug_stays_inside_the_mount_root(self):
        for name in ("../../../etc/shadow", "..", "/", "....//....//x"):
            assert mounts.is_within_mount_root(mounts.mountpoint_for(name))


class TestUnmountBehaviour:
    def test_unmounting_something_not_mounted_is_a_no_op(self, tmp_path, monkeypatch):
        monkeypatch.setattr(mounts, "MOUNT_ROOT", str(tmp_path))
        target = tmp_path / "phone"
        target.mkdir()

        def explode(*args, **kwargs):
            raise AssertionError("must not shell out when nothing is mounted")

        monkeypatch.setattr(mounts, "run_command", explode)
        mounts.unmount(str(target))  # must not raise

    def test_block_unmount_syncs_before_unmounting(self, tmp_path, monkeypatch):
        # A removable device pulled with dirty pages still in cache is how
        # people lose the files they just copied.
        monkeypatch.setattr(mounts, "MOUNT_ROOT", str(tmp_path))
        target = tmp_path / "drive"
        target.mkdir()
        monkeypatch.setattr(mounts, "is_mounted", lambda p: True)

        calls = []

        def record(cmd, **kwargs):
            calls.append(cmd[0])
            return 0, "", ""

        monkeypatch.setattr(mounts, "run_command", record)
        mounts.unmount(str(target))
        assert calls == ["sync", "umount"]

    def test_fuse_unmount_uses_fusermount(self, tmp_path, monkeypatch):
        monkeypatch.setattr(mounts, "MOUNT_ROOT", str(tmp_path))
        target = tmp_path / "phone"
        target.mkdir()
        monkeypatch.setattr(mounts, "is_mounted", lambda p: True)
        monkeypatch.setattr(mounts, "find_command", lambda n: f"/bin/{n}")

        calls = []

        def record(cmd, **kwargs):
            calls.append(cmd)
            return 0, "", ""

        monkeypatch.setattr(mounts, "run_command", record)
        mounts.unmount(str(target), fuse=True)
        assert calls[0][0].endswith("fusermount3")
        assert calls[0][1] == "-u"

    def test_a_failed_unmount_is_raised_not_swallowed(self, tmp_path, monkeypatch):
        # Reporting success on a failed unmount would let the caller detach a
        # device with a filesystem still mounted on it.
        monkeypatch.setattr(mounts, "MOUNT_ROOT", str(tmp_path))
        target = tmp_path / "drive"
        target.mkdir()
        monkeypatch.setattr(mounts, "is_mounted", lambda p: True)
        monkeypatch.setattr(
            mounts, "run_command", lambda cmd, **k: (32, "", "target is busy")
        )
        with pytest.raises(MountError, match="busy"):
            mounts.unmount(str(target))


class TestMountOptions:
    def test_block_mounts_are_nosuid_and_nodev(self, tmp_path, monkeypatch):
        # The contents arrive from another machine over an unauthenticated
        # protocol; a setuid binary on it must not become one here.
        monkeypatch.setattr(mounts, "MOUNT_ROOT", str(tmp_path))
        captured = {}

        def record(cmd, **kwargs):
            captured["cmd"] = cmd
            return 0, "", ""

        monkeypatch.setattr(mounts, "run_command", record)
        mounts.mount_block("/dev/sdc1", str(tmp_path / "drive"))

        options = captured["cmd"][captured["cmd"].index("-o") + 1].split(",")
        assert "nosuid" in options and "nodev" in options

    def test_read_only_is_honoured(self, tmp_path, monkeypatch):
        monkeypatch.setattr(mounts, "MOUNT_ROOT", str(tmp_path))
        captured = {}
        monkeypatch.setattr(
            mounts,
            "run_command",
            lambda cmd, **k: (captured.update(cmd=cmd), (0, "", ""))[1],
        )
        mounts.mount_block("/dev/sdc1", str(tmp_path / "drive"), read_only=True)
        options = captured["cmd"][captured["cmd"].index("-o") + 1].split(",")
        assert "ro" in options and "rw" not in options

    def test_mtp_without_jmtpfs_says_how_to_install_it(self, tmp_path, monkeypatch):
        monkeypatch.setattr(mounts, "MOUNT_ROOT", str(tmp_path))
        monkeypatch.setattr(mounts, "find_command", lambda n: None)
        with pytest.raises(MountError, match="apt install jmtpfs"):
            mounts.mount_mtp(str(tmp_path / "phone"))

    def test_a_failed_mount_reports_the_reason(self, tmp_path, monkeypatch):
        monkeypatch.setattr(mounts, "MOUNT_ROOT", str(tmp_path))
        monkeypatch.setattr(
            mounts,
            "run_command",
            lambda cmd, **k: (32, "", "wrong fs type, bad superblock"),
        )
        with pytest.raises(MountError, match="bad superblock"):
            mounts.mount_block("/dev/sdc1", str(tmp_path / "drive"))
