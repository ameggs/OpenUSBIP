"""
Browsing a mounted device and copying files off it.

Two concerns, both driven by the same constraint: this is reachable over HTTP
from a browser, running as root. So every path the client supplies is treated
as hostile.

* **Browsing** is confined to the mount root. A request for "../../etc/pve"
  does not fail at read time, it fails at resolution -- the caller never gets a
  path outside the tree it was given.
* **Copying** runs on a worker thread with progress, because pulling a few
  gigabytes of photos over MTP-on-FUSE-over-the-network takes minutes and a
  request that blocks that long is indistinguishable from a hang.
"""

from __future__ import annotations

import os
import shutil
import threading
import time
import uuid
from dataclasses import dataclass, field

from src.utils.logger import setup_logger

logger = setup_logger(__name__)

#: Copy buffer. Larger than shutil's default because the source is often a
#: FUSE filesystem, where each read is a round trip and small reads dominate.
CHUNK_SIZE = 1024 * 1024


class PathEscape(ValueError):
    """A supplied path resolved outside the tree it was confined to."""


def resolve_within(root: str, relative: str) -> str:
    """
    Join `relative` onto `root`, guaranteeing the result stays inside `root`.

    Guards three separate tricks, which is why this is not a join plus a
    startswith check:

    * ".." components walking out of the tree
    * an absolute path, which os.path.join would let win outright
    * a symlink inside the tree pointing outside it -- the reason the check is
      on the fully resolved path rather than the lexical one
    """
    # An absolute path is rejected rather than quietly reinterpreted as
    # relative. Both are safe -- os.path.join would let it win outright, so the
    # separator is stripped below either way -- but a client has no legitimate
    # reason to send one, and turning it into a puzzling "no such file" hides
    # whatever bug or probe produced it.
    if os.path.isabs(relative):
        raise PathEscape(f"{relative!r} is absolute; paths must be relative to {root}")

    root_real = os.path.realpath(root)
    # Defence in depth: strip any separator that survived the check above, so
    # join cannot be hijacked even if isabs disagrees on some platform.
    candidate = os.path.realpath(os.path.join(root_real, relative.lstrip("/\\")))

    if candidate != root_real:
        try:
            if os.path.commonpath([root_real, candidate]) != root_real:
                raise PathEscape(f"{relative!r} resolves outside {root}")
        except ValueError as exc:  # different drives / malformed
            raise PathEscape(f"{relative!r} is not a valid path under {root}") from exc

    return candidate


@dataclass
class Entry:
    """One item in a directory listing."""

    name: str
    path: str          # relative to the browse root, for the client to send back
    is_dir: bool
    size: int = 0
    mtime: float = 0.0


def list_directory(root: str, relative: str = "") -> list[Entry]:
    """
    List a directory inside `root`.

    Directories first then files, both case-insensitively by name -- a phone's
    DCIM folder is otherwise an unordered wall of IMG_ files. Entries that
    cannot be stat'ed are listed with zero size rather than dropped: on MTP a
    stat failure is common and hiding the file would be worse than showing it.
    """
    base = resolve_within(root, relative)
    entries: list[Entry] = []

    with os.scandir(base) as scan:
        for item in scan:
            try:
                is_dir = item.is_dir()
                stat = item.stat()
                size, mtime = (0 if is_dir else stat.st_size), stat.st_mtime
            except OSError:
                is_dir, size, mtime = False, 0, 0.0

            entries.append(Entry(
                name=item.name,
                path=os.path.relpath(os.path.join(base, item.name), root),
                is_dir=is_dir,
                size=size,
                mtime=mtime,
            ))

    entries.sort(key=lambda e: (not e.is_dir, e.name.lower()))
    return entries


# --- copying ------------------------------------------------------------------


@dataclass
class CopyJob:
    """A running or finished copy."""

    id: str
    sources: list[str]
    destination: str
    skip_existing: bool = True

    state: str = "pending"   # pending | scanning | copying | done | failed | cancelled
    files_total: int = 0
    files_done: int = 0
    files_skipped: int = 0
    bytes_total: int = 0
    bytes_done: int = 0
    current: str = ""
    error: str = ""
    started_at: float = field(default_factory=time.time)
    finished_at: float = 0.0

    _cancel: threading.Event = field(default_factory=threading.Event, repr=False)

    def cancel(self) -> None:
        self._cancel.set()

    @property
    def cancelled(self) -> bool:
        return self._cancel.is_set()

    @property
    def is_finished(self) -> bool:
        return self.state in ("done", "failed", "cancelled")

    @property
    def percent(self) -> float:
        # By bytes, not files: a folder of one video and three hundred
        # thumbnails is 99% done by file count while barely started.
        if not self.bytes_total:
            return 100.0 if self.is_finished else 0.0
        return min(100.0, self.bytes_done * 100.0 / self.bytes_total)

    @property
    def rate_bytes_per_second(self) -> float:
        elapsed = (self.finished_at or time.time()) - self.started_at
        return self.bytes_done / elapsed if elapsed > 0.1 else 0.0

    def as_dict(self) -> dict:
        return {
            "id": self.id,
            "state": self.state,
            "destination": self.destination,
            "files_total": self.files_total,
            "files_done": self.files_done,
            "files_skipped": self.files_skipped,
            "bytes_total": self.bytes_total,
            "bytes_done": self.bytes_done,
            "percent": round(self.percent, 1),
            "rate": round(self.rate_bytes_per_second),
            "current": self.current,
            "error": self.error,
            "elapsed": round((self.finished_at or time.time()) - self.started_at, 1),
        }


def _walk_files(root: str, source: str) -> list[tuple[str, str]]:
    """
    Every file under `source`, as (absolute source, path relative to its parent).

    The relative half is what reproduces the directory structure at the
    destination: copying DCIM/Camera must produce Camera/IMG_1.jpg, not a flat
    heap of files.
    """
    absolute = resolve_within(root, source)

    if os.path.isfile(absolute):
        return [(absolute, os.path.basename(absolute))]

    parent = os.path.dirname(absolute.rstrip("/"))
    collected: list[tuple[str, str]] = []
    for dirpath, _dirnames, filenames in os.walk(absolute):
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            collected.append((full, os.path.relpath(full, parent)))
    return collected


def _should_skip(source: str, target: str) -> bool:
    """
    Whether an existing destination file already matches.

    Compares size and mtime rather than hashing: the source is usually a phone
    over FUSE, where re-reading every file to hash it costs as much as copying
    it. mtime is compared with a two-second tolerance because FAT stores it at
    that resolution, so an exact comparison re-copies everything on every run.
    """
    try:
        src = os.stat(source)
        dst = os.stat(target)
    except OSError:
        return False
    return src.st_size == dst.st_size and abs(src.st_mtime - dst.st_mtime) <= 2


class TransferManager:
    """Runs copy jobs on worker threads and keeps their state for polling."""

    def __init__(self, max_history: int = 20):
        self._jobs: dict[str, CopyJob] = {}
        self._order: list[str] = []
        self._lock = threading.Lock()
        self._max_history = max_history

    def get(self, job_id: str) -> CopyJob | None:
        with self._lock:
            return self._jobs.get(job_id)

    def all(self) -> list[CopyJob]:
        with self._lock:
            return [self._jobs[i] for i in self._order if i in self._jobs]

    def active(self) -> list[CopyJob]:
        return [job for job in self.all() if not job.is_finished]

    def _remember(self, job: CopyJob) -> None:
        with self._lock:
            self._jobs[job.id] = job
            self._order.append(job.id)
            # Drop the oldest finished jobs; a running one is never evicted.
            while len(self._order) > self._max_history:
                for index, job_id in enumerate(self._order):
                    if self._jobs[job_id].is_finished:
                        del self._jobs[job_id]
                        del self._order[index]
                        break
                else:
                    break

    def start(
        self,
        root: str,
        sources: list[str],
        destination: str,
        skip_existing: bool = True,
    ) -> CopyJob:
        """
        Begin copying `sources` (relative to `root`) into `destination`.

        The destination is deliberately *not* fenced to the mount root -- the
        whole point is to land files on this machine's storage. It must be
        absolute, so a relative path cannot end up somewhere surprising
        depending on the server's working directory.
        """
        if not sources:
            raise ValueError("nothing selected to copy")
        if not os.path.isabs(destination):
            raise ValueError("destination must be an absolute path")

        # Resolve every source before starting, so a bad path fails the request
        # rather than a background job the user has to go looking for.
        for source in sources:
            resolve_within(root, source)

        job = CopyJob(
            id=uuid.uuid4().hex[:12],
            sources=list(sources),
            destination=destination,
            skip_existing=skip_existing,
        )
        self._remember(job)

        thread = threading.Thread(
            target=self._run, args=(job, root), name=f"copy-{job.id}", daemon=True
        )
        thread.start()
        return job

    def _run(self, job: CopyJob, root: str) -> None:
        try:
            job.state = "scanning"
            plan: list[tuple[str, str]] = []
            for source in job.sources:
                plan.extend(_walk_files(root, source))

            job.files_total = len(plan)
            # Totalling up front is a second pass over the metadata, but
            # without it there is no denominator and so no progress bar.
            job.bytes_total = sum(
                os.path.getsize(src) for src, _ in plan if os.path.exists(src)
            )

            os.makedirs(job.destination, exist_ok=True)
            job.state = "copying"

            for absolute, relative in plan:
                if job.cancelled:
                    job.state = "cancelled"
                    return

                target = os.path.join(job.destination, relative)
                job.current = relative

                if job.skip_existing and _should_skip(absolute, target):
                    job.files_skipped += 1
                    job.files_done += 1
                    try:
                        job.bytes_done += os.path.getsize(absolute)
                    except OSError:
                        pass
                    continue

                os.makedirs(os.path.dirname(target) or job.destination, exist_ok=True)
                self._copy_one(job, absolute, target)
                job.files_done += 1

            job.state = "cancelled" if job.cancelled else "done"

        except _Cancelled:
            # Must be caught ahead of the general handler: cancelling mid-file
            # raises out of _copy_one, and treating that as a failure would
            # report an error for something the user asked for.
            job.state = "cancelled"

        except Exception as exc:
            logger.exception(f"copy job {job.id} failed")
            job.state = "failed"
            job.error = str(exc)
        finally:
            job.current = ""
            job.finished_at = time.time()
            logger.info(
                f"copy job {job.id} {job.state}: {job.files_done}/{job.files_total} "
                f"files, {job.bytes_done} bytes"
            )

    def _copy_one(self, job: CopyJob, source: str, target: str) -> None:
        """
        Copy one file, updating progress as it goes.

        Written to a .part file and renamed on completion, so an interrupted
        copy cannot leave a truncated file that the next run's size check would
        happily accept as already done.
        """
        partial = target + ".openusbip-part"
        try:
            with open(source, "rb") as src, open(partial, "wb") as dst:
                while True:
                    if job.cancelled:
                        raise _Cancelled()
                    chunk = src.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    dst.write(chunk)
                    job.bytes_done += len(chunk)

            shutil.copystat(source, partial, follow_symlinks=True)
            os.replace(partial, target)

        except _Cancelled:
            _remove_quietly(partial)
            job.state = "cancelled"
            raise
        except Exception:
            _remove_quietly(partial)
            raise


class _Cancelled(Exception):
    """Internal: the user cancelled mid-file."""


def _remove_quietly(path: str) -> None:
    try:
        os.unlink(path)
    except OSError:
        pass
