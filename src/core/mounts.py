"""
Mount and unmount what a USB/IP attach produced.

This runs as root on a machine whose other filesystems matter -- a hypervisor
with live guest storage. So the destructive direction is fenced off: nothing
here will unmount a path that is not inside MOUNT_ROOT, and MOUNT_ROOT is a
directory this program owns. A bug that unmounts an array is not recoverable
by retrying, so it is prevented structurally rather than by being careful.

Two kinds of thing turn up after an attach:

* a **block device** -- a flash drive, or the virtual disk the Android app
  synthesises. Mounted with plain mount(8).
* an **MTP device** -- what a phone actually speaks over a cable. Mounted with
  jmtpfs, a FUSE filesystem, because the kernel has no MTP driver.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field

from src.utils.logger import setup_logger
from src.utils.platform_utils import find_command, run_command

logger = setup_logger(__name__)

#: Everything is mounted under here. On a systemd machine /run is a tmpfs, so
#: stale mountpoints cannot survive a reboot and accumulate.
MOUNT_ROOT = "/run/openusbip"

#: Filesystems worth offering to mount. Anything else on a USB device is
#: either not a filesystem (swap, crypto) or needs setup we do not do (LVM).
MOUNTABLE_FSTYPES = frozenset({
    "vfat", "exfat", "ntfs", "ntfs3", "ext2", "ext3", "ext4",
    "btrfs", "xfs", "f2fs", "hfsplus", "udf", "iso9660",
})


class MountError(RuntimeError):
    """A mount or unmount could not be completed."""


@dataclass
class BlockTarget:
    """A block device or partition that could be mounted."""

    name: str
    path: str
    size: str = ""
    fstype: str = ""
    label: str = ""
    mountpoint: str = ""
    type: str = ""
    children: list[BlockTarget] = field(default_factory=list)

    @property
    def is_mountable(self) -> bool:
        return self.fstype in MOUNTABLE_FSTYPES

    @property
    def display_name(self) -> str:
        parts = [self.label or self.name]
        if self.size:
            parts.append(self.size)
        if self.fstype:
            parts.append(self.fstype)
        return " · ".join(parts)


def parse_lsblk(text: str) -> list[BlockTarget]:
    """
    Parse `lsblk -J` output into a tree.

    lsblk is used rather than reading sysfs directly because it already knows
    how to identify filesystem types and labels, and getting that wrong means
    either refusing to mount a good filesystem or trying to mount something
    that is not one.
    """
    try:
        data = json.loads(text)
        devices = data["blockdevices"]
    except Exception:
        return []
    if not isinstance(devices, list):
        return []

    def build(entry: dict) -> BlockTarget:
        return BlockTarget(
            name=entry.get("name") or "",
            path=entry.get("path") or f"/dev/{entry.get('name', '')}",
            size=entry.get("size") or "",
            fstype=entry.get("fstype") or "",
            label=entry.get("label") or "",
            mountpoint=entry.get("mountpoint") or "",
            type=entry.get("type") or "",
            children=[build(child) for child in entry.get("children") or []],
        )

    return [build(entry) for entry in devices if isinstance(entry, dict)]


def choose_mount_targets(targets: list[BlockTarget]) -> list[BlockTarget]:
    """
    Pick what to actually mount from an lsblk tree.

    A partitioned disk exposes both the whole disk and its partitions; only the
    partitions carry filesystems. An unpartitioned one -- which is what
    `mkfs` straight onto a flash drive gives you, and what the Android app's
    virtual disk starts as -- carries the filesystem itself. So: prefer
    mountable children, and fall back to the parent only when it has none.
    """
    chosen: list[BlockTarget] = []
    for target in targets:
        children = choose_mount_targets(target.children)
        if children:
            chosen.extend(children)
        elif target.is_mountable:
            chosen.append(target)
    return chosen


def list_block_targets(device_names: list[str]) -> list[BlockTarget]:
    """Run lsblk against the named block devices and pick what to mount."""
    if not device_names:
        return []

    paths = [f"/dev/{name}" for name in device_names]
    code, stdout, stderr = run_command(
        ["lsblk", "-J", "-o", "NAME,PATH,SIZE,FSTYPE,LABEL,MOUNTPOINT,TYPE", *paths]
    )
    if code != 0:
        logger.error(f"lsblk failed: {stderr.strip()}")
        return []
    return choose_mount_targets(parse_lsblk(stdout))


# --- the safety fence ---------------------------------------------------------


def is_within_mount_root(path: str, mount_root: str | None = None) -> bool:
    """
    Whether `path` is inside the directory this program owns.

    Uses commonpath on *resolved* paths rather than a startswith check, which
    would accept "/run/openusbip-evil" as being inside "/run/openusbip", and
    would be fooled by a ".." component or by a symlink pointing out of the
    tree.

    `mount_root` defaults to MOUNT_ROOT at call time rather than as a default
    argument value, so the fence can be repointed -- a default argument binds
    at import and would silently ignore any override.
    """
    mount_root = mount_root if mount_root is not None else MOUNT_ROOT
    try:
        root = os.path.realpath(mount_root)
        candidate = os.path.realpath(path)
    except OSError:
        return False
    if candidate == root:
        return False  # the root itself is not a mountpoint we manage
    try:
        return os.path.commonpath([root, candidate]) == root
    except ValueError:
        return False  # different drives, or a relative path slipped through


def _require_owned(mountpoint: str) -> None:
    if not is_within_mount_root(mountpoint):
        # Refusing loudly, because the alternative on this machine is
        # unmounting somebody's array.
        raise MountError(
            f"refusing to act on {mountpoint!r}: not inside {MOUNT_ROOT}"
        )


def mountpoint_for(name: str, mount_root: str | None = None) -> str:
    """
    A mountpoint under MOUNT_ROOT for a device.

    The name is reduced to a safe slug: it reaches here from a USB device's own
    product string, which is attacker-controlled in the sense that anyone who
    can plug in a device chooses it. Anything that is not alphanumeric, dash or
    underscore becomes a dash, which leaves no path separators and no "..".
    """
    mount_root = mount_root if mount_root is not None else MOUNT_ROOT
    slug = "".join(c if c.isalnum() or c in "-_" else "-" for c in name).strip("-")
    return os.path.join(mount_root, slug or "device")


# --- mounting -----------------------------------------------------------------


def _ensure_mountpoint(mountpoint: str) -> None:
    _require_owned(mountpoint)
    os.makedirs(mountpoint, exist_ok=True)


def is_mounted(mountpoint: str) -> bool:
    """Whether something is mounted at this exact path."""
    return os.path.ismount(mountpoint)


def mount_block(device_path: str, mountpoint: str, read_only: bool = False) -> str:
    """
    Mount a block device.

    Returns the mountpoint. `nosuid,nodev` are always set: the contents come
    from another machine over an unauthenticated protocol, so a setuid binary
    on it should not become a setuid binary here.
    """
    _ensure_mountpoint(mountpoint)

    options = ["nosuid", "nodev"]
    options.append("ro" if read_only else "rw")

    code, _out, err = run_command(
        ["mount", "-o", ",".join(options), device_path, mountpoint],
        timeout=30,
        require_admin=True,
    )
    if code != 0:
        raise MountError(f"could not mount {device_path}: {err.strip() or 'unknown error'}")

    logger.info(f"mounted {device_path} at {mountpoint} ({'ro' if read_only else 'rw'})")
    return mountpoint


def mount_mtp(mountpoint: str) -> str:
    """
    Mount the attached MTP device with jmtpfs.

    jmtpfs takes no device argument -- libmtp scans the USB bus and takes the
    first MTP device it finds. That is fine here because the attach that
    preceded this is what put one there, but it does mean a phone plugged into
    this machine physically would win. Worth knowing if it ever mounts the
    wrong thing.
    """
    if not find_command("jmtpfs"):
        raise MountError(
            "jmtpfs is not installed, so MTP devices cannot be browsed.\n"
            "Install it with: apt install jmtpfs"
        )

    _ensure_mountpoint(mountpoint)

    # -o allow_other so the web server can read the tree even if FUSE was
    # mounted by a different uid; ro because this is for pulling files off a
    # phone and MTP writes are where jmtpfs is least reliable.
    code, _out, err = run_command(
        ["jmtpfs", "-o", "allow_other", mountpoint], timeout=60, require_admin=True
    )
    if code != 0:
        raise MountError(f"jmtpfs failed: {err.strip() or 'unknown error'}")

    logger.info(f"mounted MTP device at {mountpoint}")
    return mountpoint


def unmount(mountpoint: str, fuse: bool = False) -> None:
    """
    Unmount a path this program mounted.

    Refuses anything outside MOUNT_ROOT. FUSE mounts go through fusermount3,
    which does not need root and is the only thing that cleans up jmtpfs
    properly.
    """
    _require_owned(mountpoint)

    if not is_mounted(mountpoint):
        logger.info(f"{mountpoint} is not mounted; nothing to do")
        return

    if fuse:
        tool = find_command("fusermount3") or find_command("fusermount")
        if not tool:
            raise MountError("neither fusermount3 nor fusermount is available")
        cmd = [tool, "-u", mountpoint]
    else:
        # sync first: on a removable device the alternative is data still in
        # the page cache when the user pulls the plug.
        run_command(["sync"], timeout=60, require_admin=True)
        cmd = ["umount", mountpoint]

    code, _out, err = run_command(cmd, timeout=60, require_admin=True)
    if code != 0:
        raise MountError(f"could not unmount {mountpoint}: {err.strip() or 'unknown error'}")

    logger.info(f"unmounted {mountpoint}")

    # Leave no empty directories behind; harmless if it is not empty.
    try:
        os.rmdir(mountpoint)
    except OSError:
        pass


def unmount_all() -> list[str]:
    """
    Unmount everything under MOUNT_ROOT.

    Used on shutdown so a restart does not inherit stale mounts. Errors are
    collected rather than raised: one stuck mount must not stop the others
    being released.
    """
    released: list[str] = []
    try:
        entries = sorted(os.listdir(MOUNT_ROOT))
    except OSError:
        return released

    for entry in entries:
        path = os.path.join(MOUNT_ROOT, entry)
        if not is_mounted(path):
            continue
        for fuse in (True, False):
            try:
                unmount(path, fuse=fuse)
                released.append(path)
                break
            except MountError:
                continue
    return released
