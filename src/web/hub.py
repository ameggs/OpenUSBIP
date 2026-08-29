"""
The hub: what the web UI actually drives.

Holds the state that a headless client needs and that no single usbip command
expresses -- which servers exist, what is attached, where each attachment is
mounted, and which copies are running. Deliberately free of HTTP: everything
here is callable from a test, and the server module is a thin translation
layer over it.

The sequence a user thinks of as "get my photos" is five steps that each fail
differently, so it lives in one place (`attach`) rather than being spread
across request handlers:

    usbip attach -> wait for the device -> work out what it is
                 -> mount it -> hand back something browsable
"""

from __future__ import annotations

import threading
import uuid
from dataclasses import dataclass, field

from src.core import mounts, vhci
from src.core.server_discovery import ServerDiscovery, USBIPServer
from src.core.transfer import TransferManager
from src.core.usbip_wrapper import DEFAULT_USBIP_PORT, USBIPWrapper
from src.utils.logger import setup_logger
from src.utils.platform_utils import check_kernel_modules, load_kernel_module

logger = setup_logger(__name__)


class HubError(RuntimeError):
    """Something the user should be told about, in words they can act on."""


@dataclass
class Attachment:
    """One attached device, and where it ended up."""

    id: str
    server_host: str
    server_port: int
    busid: str
    description: str
    kind: str                      # storage | mtp | other
    vhci_port: str = ""
    mountpoint: str = ""
    mount_source: str = ""
    is_fuse: bool = False
    read_only: bool = False
    note: str = ""
    block_devices: list[str] = field(default_factory=list)

    @property
    def is_mounted(self) -> bool:
        return bool(self.mountpoint) and mounts.is_mounted(self.mountpoint)

    def as_dict(self) -> dict:
        return {
            "id": self.id,
            "server": f"{self.server_host}:{self.server_port}",
            "busid": self.busid,
            "description": self.description,
            "kind": self.kind,
            "vhci_port": self.vhci_port,
            "mounted": self.is_mounted,
            "mountpoint": self.mountpoint,
            "mount_source": self.mount_source,
            "read_only": self.read_only,
            "browsable": self.is_mounted,
            "note": self.note,
            "block_devices": self.block_devices,
        }


class Hub:
    """
    All the client-side state, with no HTTP in sight.

    Every public method is safe to call from a request thread; the lock covers
    the attachment table, which attach and detach both mutate while the UI
    polls it.
    """

    def __init__(self, usbip: USBIPWrapper | None = None, discovery=None):
        self.usbip = usbip or USBIPWrapper()
        self.discovery = discovery if discovery is not None else ServerDiscovery()
        self.transfers = TransferManager()
        self._attachments: dict[str, Attachment] = {}
        self._lock = threading.Lock()
        self._discovery_started = False

    # --- lifecycle ------------------------------------------------------------

    def start(self) -> None:
        """Load the client kernel module and begin discovering servers."""
        loaded, missing = check_kernel_modules(role="client")
        if not loaded:
            for module in missing:
                if load_kernel_module(module):
                    logger.info(f"loaded kernel module {module}")
                else:
                    # Not fatal: the user may still want to browse an already
                    # mounted device, and the error at attach time is clearer.
                    logger.warning(f"could not load {module}; attaching will fail")

        if not self._discovery_started:
            self._discovery_started = self.discovery.start()
            if not self._discovery_started:
                logger.warning("mDNS discovery unavailable; add servers by address")

        self._adopt_existing()

    def stop(self) -> None:
        """
        Release everything this process is holding.

        Unmounting on the way out matters more here than usual: a mount left
        behind keeps the vhci device busy, and the next start cannot reuse it.
        """
        for path in mounts.unmount_all():
            logger.info(f"released {path} on shutdown")
        try:
            self.discovery.stop()
        except Exception:
            logger.debug("discovery did not stop cleanly", exc_info=True)

    def _adopt_existing(self) -> None:
        """
        Pick up devices already attached when the hub started.

        A restart of this service must not orphan them: they would still be
        attached, still consuming a vhci port, and invisible in the UI.
        """
        for device in vhci.list_attached():
            attachment = Attachment(
                id=uuid.uuid4().hex[:8],
                server_host="(already attached)",
                server_port=0,
                busid=device.busid,
                description=device.description,
                kind=device.kind,
                block_devices=device.block_devices,
                note="Attached before this service started.",
            )
            with self._lock:
                self._attachments[attachment.id] = attachment
            logger.info(f"adopted existing attachment {device.busid}")

    # --- servers --------------------------------------------------------------

    def servers(self) -> list[USBIPServer]:
        return self.discovery.get_servers()

    def add_server(self, host: str, port: int = DEFAULT_USBIP_PORT) -> USBIPServer:
        if not host.strip():
            raise HubError("a hostname or IP address is required")
        return self.discovery.add_manual_server(host.strip(), port)

    def remove_server(self, host: str) -> bool:
        return self.discovery.remove_server(host)

    def remote_devices(self, host: str, port: int = DEFAULT_USBIP_PORT) -> list[dict]:
        devices = self.usbip.list_remote_devices(host, port)
        attached = {a.busid for a in self.attachments()}
        return [
            {
                "busid": d.busid,
                "description": d.description,
                "vendor_id": d.vendor_id,
                "product_id": d.product_id,
                "attached": d.busid in attached,
            }
            for d in devices
        ]

    # --- attach / detach ------------------------------------------------------

    def attachments(self) -> list[Attachment]:
        with self._lock:
            return list(self._attachments.values())

    def attachment(self, attachment_id: str) -> Attachment:
        with self._lock:
            found = self._attachments.get(attachment_id)
        if not found:
            raise HubError("that device is no longer attached")
        return found

    def attach(
        self,
        host: str,
        busid: str,
        port: int = DEFAULT_USBIP_PORT,
        read_only: bool = False,
        auto_mount: bool = True,
    ) -> Attachment:
        """
        Attach a remote device and make it browsable.

        Returns as soon as there is something to show. A device that attaches
        but cannot be mounted is still returned, with `note` explaining why --
        that is far more useful than an error, because the attach genuinely
        worked and detaching is now the user's decision.
        """
        before = {d.busid for d in vhci.list_attached()}

        ok, message = self.usbip.attach_device(host, busid, port)
        if not ok:
            raise HubError(f"attach failed: {message}")

        device = vhci.wait_for_new_device(before, timeout=8.0)
        if device is None:
            # The attach reported success, so leave it attached rather than
            # silently detaching something that may yet enumerate.
            raise HubError(
                "the device attached but never appeared on the virtual bus. "
                "It may still be enumerating -- refresh in a moment."
            )

        attachment = Attachment(
            id=uuid.uuid4().hex[:8],
            server_host=host,
            server_port=port,
            busid=busid,
            description=device.description,
            kind=device.kind,
            read_only=read_only,
            block_devices=device.block_devices,
        )

        if auto_mount:
            self._mount_attachment(attachment, device)

        with self._lock:
            self._attachments[attachment.id] = attachment
        return attachment

    def _mount_attachment(self, attachment: Attachment, device: vhci.AttachedDevice) -> None:
        """
        Mount whatever the device turned out to be, recording why if it cannot.

        Mount failures are captured on the attachment rather than raised: the
        attach succeeded, and a user looking at "SanDisk Ultra Fit — attached,
        not mounted: no filesystem found" is better served than by an error
        that loses the fact it is attached at all.
        """
        try:
            if device.is_mass_storage:
                # Always wait: usb-storage creates the block devices after the
                # USB device enumerates, so at this instant there are usually
                # none even for a drive that is about to work perfectly.
                names = device.block_devices or vhci.wait_for_block_devices(
                    device, timeout=8.0
                )
                attachment.block_devices = names

                if not names:
                    attachment.note = (
                        "Attached, but the device reported no media. If this is "
                        "a card reader or a drive bay, check something is "
                        "actually inserted."
                    )
                    return

                targets = mounts.list_block_targets(names)
                if not targets:
                    attachment.note = (
                        f"Attached as {', '.join('/dev/' + n for n in names)}, but "
                        "no mountable filesystem was found. The device may be "
                        "unformatted."
                    )
                    return

                target = targets[0]
                mountpoint = mounts.mountpoint_for(target.label or target.name)
                mounts.mount_block(target.path, mountpoint, attachment.read_only)
                attachment.mountpoint = mountpoint
                attachment.mount_source = target.path
                if len(targets) > 1:
                    others = ", ".join(t.display_name for t in targets[1:])
                    attachment.note = f"Mounted {target.display_name}. Also present: {others}."

            elif device.kind == "mtp":
                mountpoint = mounts.mountpoint_for(device.product or device.busid)
                mounts.mount_mtp(mountpoint)
                attachment.mountpoint = mountpoint
                attachment.mount_source = "MTP"
                attachment.is_fuse = True
                attachment.read_only = True

            else:
                attachment.note = (
                    "Attached. This is not a storage or MTP device, so there is "
                    "nothing to browse -- it is available to the system as an "
                    "ordinary USB device."
                )

        except mounts.MountError as exc:
            logger.warning(f"mount failed for {attachment.busid}: {exc}")
            attachment.note = f"Attached, but could not be mounted: {exc}"

    def detach(self, attachment_id: str) -> None:
        """
        Unmount and detach.

        The unmount is not optional and its failure is fatal to the detach:
        pulling a device out from under a mounted filesystem is how people
        corrupt the thing they just copied.
        """
        attachment = self.attachment(attachment_id)

        running = [
            job for job in self.transfers.active()
            if attachment.mountpoint and job.destination.startswith(attachment.mountpoint)
        ]
        if running:
            raise HubError("a copy is still running to this device")

        if attachment.mountpoint and mounts.is_mounted(attachment.mountpoint):
            mounts.unmount(attachment.mountpoint, fuse=attachment.is_fuse)

        port = attachment.vhci_port or self._find_vhci_port(attachment.busid)
        if port:
            ok, message = self.usbip.detach_device(port)
            if not ok:
                raise HubError(f"detach failed: {message}")
        else:
            logger.warning(
                f"no vhci port found for {attachment.busid}; removing from the list only"
            )

        with self._lock:
            self._attachments.pop(attachment_id, None)

    def _find_vhci_port(self, busid: str) -> str:
        """The vhci port number serving `busid`, via `usbip port`."""
        for entry in self.usbip.list_attached_devices():
            if entry.get("busid") == busid:
                return entry.get("port", "")
        return ""

    # --- browsing and copying -------------------------------------------------

    def browse(self, attachment_id: str, path: str = "") -> dict:
        attachment = self.attachment(attachment_id)
        if not attachment.is_mounted:
            raise HubError("that device is not mounted, so there is nothing to browse")

        from src.core.transfer import list_directory

        entries = list_directory(attachment.mountpoint, path)
        return {
            "path": path,
            "parent": path.rsplit("/", 1)[0] if "/" in path else ("" if path else None),
            "entries": [
                {
                    "name": e.name,
                    "path": e.path,
                    "is_dir": e.is_dir,
                    "size": e.size,
                    "mtime": e.mtime,
                }
                for e in entries
            ],
        }

    def copy(
        self,
        attachment_id: str,
        sources: list[str],
        destination: str,
        skip_existing: bool = True,
    ):
        attachment = self.attachment(attachment_id)
        if not attachment.is_mounted:
            raise HubError("that device is not mounted")

        if mounts.is_within_mount_root(destination):
            # Copying from a mount back into the mount tree would either
            # recurse or write to the phone, neither of which is ever intended.
            raise HubError(
                f"the destination must be somewhere on this machine, not inside "
                f"{mounts.MOUNT_ROOT}"
            )

        return self.transfers.start(
            attachment.mountpoint, sources, destination, skip_existing
        )

    # --- one call for the whole UI --------------------------------------------

    def state(self) -> dict:
        """Everything the page needs, so a refresh is a single request."""
        return {
            "servers": [
                {**server.to_dict(), "is_online": server.is_online}
                for server in self.servers()
            ],
            "attachments": [a.as_dict() for a in self.attachments()],
            "jobs": [job.as_dict() for job in self.transfers.all()],
            "usbip_available": self.usbip.is_available(),
            "mount_root": mounts.MOUNT_ROOT,
        }
