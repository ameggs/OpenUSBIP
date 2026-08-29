"""
Tests for browsing a mounted device and copying off it.

Path containment gets the same adversarial treatment as the unmount fence:
this code is reachable over HTTP, runs as root, and the paths come from the
client. Everything else here is about the copy being *correct* -- structure
preserved, no truncated files left behind, resumable without re-copying.
"""

import os
import sys
import threading
import time

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from src.core import transfer  # noqa: E402
from src.core.transfer import PathEscape, TransferManager  # noqa: E402


@pytest.fixture
def phone(tmp_path):
    """A mounted device with a plausible photo layout."""
    root = tmp_path / "mount"
    camera = root / "DCIM" / "Camera"
    camera.mkdir(parents=True)
    (camera / "IMG_0001.jpg").write_bytes(b"a" * 2048)
    (camera / "IMG_0002.jpg").write_bytes(b"b" * 4096)
    (root / "DCIM" / "thumb.db").write_bytes(b"c" * 16)
    (root / "Download").mkdir()
    (root / "Download" / "manual.pdf").write_bytes(b"d" * 1024)
    return root


def wait_for(job, timeout=10.0):
    deadline = time.time() + timeout
    while not job.is_finished and time.time() < deadline:
        time.sleep(0.01)
    return job


class TestResolveWithin:
    def test_resolves_a_normal_relative_path(self, phone):
        resolved = transfer.resolve_within(str(phone), "DCIM/Camera")
        assert resolved == os.path.realpath(str(phone / "DCIM" / "Camera"))

    def test_the_root_itself_is_allowed(self, phone):
        assert transfer.resolve_within(str(phone), "") == os.path.realpath(str(phone))

    @pytest.mark.parametrize(
        "attempt",
        [
            "../../../etc/passwd",
            "DCIM/../../../../etc/pve",
            "/etc/shadow",
            "//etc/shadow",
            "DCIM/./../../..",
        ],
    )
    def test_rejects_escapes(self, phone, attempt):
        with pytest.raises(PathEscape):
            transfer.resolve_within(str(phone), attempt)

    def test_an_absolute_path_cannot_hijack_the_join(self, phone):
        # os.path.join("/mount", "/etc/pve") returns "/etc/pve" outright, which
        # is why the leading separator is stripped before joining.
        with pytest.raises(PathEscape):
            transfer.resolve_within(str(phone), "/etc/pve")

    def test_rejects_a_symlink_pointing_out_of_the_tree(self, phone, tmp_path):
        # The lexical path stays inside; only resolving it reveals the escape.
        secret = tmp_path / "secret"
        secret.mkdir()
        (secret / "key").write_text("private")
        os.symlink(secret, phone / "escape")

        with pytest.raises(PathEscape):
            transfer.resolve_within(str(phone), "escape/key")


class TestListDirectory:
    def test_lists_directories_before_files(self, phone):
        names = [e.name for e in transfer.list_directory(str(phone))]
        assert names == ["DCIM", "Download"]

    def test_reports_sizes_for_files(self, phone):
        entries = transfer.list_directory(str(phone), "DCIM/Camera")
        by_name = {e.name: e for e in entries}
        assert by_name["IMG_0002.jpg"].size == 4096
        assert not by_name["IMG_0002.jpg"].is_dir

    def test_paths_are_relative_to_the_root(self, phone):
        # They go back to the client and come again as the next request, so
        # they must not leak the server's absolute layout.
        entries = transfer.list_directory(str(phone), "DCIM")
        assert {e.path for e in entries} == {"DCIM/Camera", "DCIM/thumb.db"}

    def test_cannot_list_outside_the_root(self, phone):
        with pytest.raises(PathEscape):
            transfer.list_directory(str(phone), "../..")


class TestCopy:
    def test_copies_a_folder_preserving_structure(self, phone, tmp_path):
        dest = tmp_path / "storage"
        job = wait_for(
            TransferManager().start(str(phone), ["DCIM/Camera"], str(dest))
        )

        assert job.state == "done", job.error
        assert (dest / "Camera" / "IMG_0001.jpg").read_bytes() == b"a" * 2048
        assert (dest / "Camera" / "IMG_0002.jpg").exists()

    def test_copies_a_single_file_to_the_top_level(self, phone, tmp_path):
        dest = tmp_path / "storage"
        job = wait_for(
            TransferManager().start(str(phone), ["Download/manual.pdf"], str(dest))
        )
        assert job.state == "done"
        assert (dest / "manual.pdf").read_bytes() == b"d" * 1024

    def test_copies_several_selections_at_once(self, phone, tmp_path):
        dest = tmp_path / "storage"
        job = wait_for(
            TransferManager().start(
                str(phone), ["DCIM/Camera", "Download/manual.pdf"], str(dest)
            )
        )
        assert job.state == "done"
        assert job.files_total == 3
        assert (dest / "Camera" / "IMG_0001.jpg").exists()
        assert (dest / "manual.pdf").exists()

    def test_counts_bytes_not_just_files(self, phone, tmp_path):
        # Progress by file count reads 99% done with the big video still to go.
        job = wait_for(
            TransferManager().start(str(phone), ["DCIM"], str(tmp_path / "s"))
        )
        assert job.bytes_total == 2048 + 4096 + 16
        assert job.bytes_done == job.bytes_total
        assert job.percent == 100.0

    def test_creates_the_destination_if_it_does_not_exist(self, phone, tmp_path):
        dest = tmp_path / "a" / "b" / "c"
        job = wait_for(TransferManager().start(str(phone), ["Download"], str(dest)))
        assert job.state == "done"
        assert (dest / "Download" / "manual.pdf").exists()

    def test_preserves_modification_times(self, phone, tmp_path):
        dest = tmp_path / "storage"
        wait_for(TransferManager().start(str(phone), ["Download"], str(dest)))
        source_mtime = (phone / "Download" / "manual.pdf").stat().st_mtime
        copied_mtime = (dest / "Download" / "manual.pdf").stat().st_mtime
        assert abs(source_mtime - copied_mtime) < 1


class TestSkipExisting:
    def test_a_second_run_skips_what_is_already_there(self, phone, tmp_path):
        # Pulling photos off a phone repeatedly is the normal case; re-copying
        # everything each time makes the feature useless.
        dest = tmp_path / "storage"
        manager = TransferManager()
        wait_for(manager.start(str(phone), ["DCIM/Camera"], str(dest)))

        second = wait_for(manager.start(str(phone), ["DCIM/Camera"], str(dest)))
        assert second.state == "done"
        assert second.files_skipped == 2
        assert second.files_done == 2

    def test_a_changed_file_is_re_copied(self, phone, tmp_path):
        dest = tmp_path / "storage"
        manager = TransferManager()
        wait_for(manager.start(str(phone), ["Download"], str(dest)))

        (phone / "Download" / "manual.pdf").write_bytes(b"e" * 9999)
        second = wait_for(manager.start(str(phone), ["Download"], str(dest)))

        assert second.files_skipped == 0
        assert (dest / "Download" / "manual.pdf").read_bytes() == b"e" * 9999

    def test_skipping_can_be_turned_off(self, phone, tmp_path):
        dest = tmp_path / "storage"
        manager = TransferManager()
        wait_for(manager.start(str(phone), ["Download"], str(dest)))
        second = wait_for(
            manager.start(str(phone), ["Download"], str(dest), skip_existing=False)
        )
        assert second.files_skipped == 0


class TestPartialFiles:
    def test_no_part_file_survives_a_successful_copy(self, phone, tmp_path):
        dest = tmp_path / "storage"
        wait_for(TransferManager().start(str(phone), ["DCIM"], str(dest)))
        leftovers = [
            os.path.join(root, name)
            for root, _dirs, files in os.walk(dest)
            for name in files
            if name.endswith(".openusbip-part")
        ]
        assert leftovers == []

    def test_a_failed_read_leaves_no_truncated_file(self, phone, tmp_path, monkeypatch):
        # The reason for the .part file: a truncated destination would be
        # accepted by the next run's size check and silently kept forever.
        dest = tmp_path / "storage"

        real_open = open
        state = {"count": 0}

        def flaky_open(path, mode="r", *args, **kwargs):
            if "b" in mode and "r" in mode:
                state["count"] += 1
                if state["count"] == 1:
                    raise OSError(5, "Input/output error")
            return real_open(path, mode, *args, **kwargs)

        monkeypatch.setattr("builtins.open", flaky_open)
        job = wait_for(
            TransferManager().start(str(phone), ["Download/manual.pdf"], str(dest))
        )

        assert job.state == "failed"
        assert "Input/output error" in job.error
        assert not (dest / "manual.pdf").exists()
        assert not (dest / "manual.pdf.openusbip-part").exists()


class TestCancellation:
    def test_a_cancelled_job_reports_cancelled_not_failed(self, tmp_path):
        source = tmp_path / "mount"
        source.mkdir()
        # Big enough that cancellation lands mid-file rather than between them.
        (source / "big.bin").write_bytes(b"x" * (8 * 1024 * 1024))

        manager = TransferManager()
        job = manager.start(str(source), ["big.bin"], str(tmp_path / "dest"))

        # Cancel as soon as the copy is actually under way.
        deadline = time.time() + 5
        while job.bytes_done == 0 and not job.is_finished and time.time() < deadline:
            time.sleep(0.001)
        job.cancel()
        wait_for(job)

        assert job.state == "cancelled"
        assert job.error == ""

    def test_cancelling_leaves_no_partial_file_behind(self, tmp_path):
        source = tmp_path / "mount"
        source.mkdir()
        (source / "big.bin").write_bytes(b"x" * (8 * 1024 * 1024))
        dest = tmp_path / "dest"

        manager = TransferManager()
        job = manager.start(str(source), ["big.bin"], str(dest))
        deadline = time.time() + 5
        while job.bytes_done == 0 and not job.is_finished and time.time() < deadline:
            time.sleep(0.001)
        job.cancel()
        wait_for(job)

        assert not (dest / "big.bin.openusbip-part").exists()


class TestValidation:
    def test_rejects_an_empty_selection(self, phone, tmp_path):
        with pytest.raises(ValueError, match="nothing selected"):
            TransferManager().start(str(phone), [], str(tmp_path))

    def test_rejects_a_relative_destination(self, phone):
        # Otherwise the files land wherever the server's cwd happens to be.
        with pytest.raises(ValueError, match="absolute"):
            TransferManager().start(str(phone), ["Download"], "storage/photos")

    def test_rejects_a_source_outside_the_mount_before_starting(self, phone, tmp_path):
        # Failing the request beats failing a background job the user then has
        # to go looking for.
        with pytest.raises(PathEscape):
            TransferManager().start(str(phone), ["../../etc"], str(tmp_path / "d"))


class TestManagerBookkeeping:
    def test_jobs_can_be_looked_up_by_id(self, phone, tmp_path):
        manager = TransferManager()
        job = wait_for(manager.start(str(phone), ["Download"], str(tmp_path / "d")))
        assert manager.get(job.id) is job

    def test_finished_jobs_are_evicted_but_running_ones_are_not(self, phone, tmp_path):
        manager = TransferManager(max_history=3)
        for index in range(5):
            wait_for(
                manager.start(str(phone), ["Download"], str(tmp_path / f"d{index}"))
            )
        assert len(manager.all()) <= 3

    def test_active_lists_only_unfinished_jobs(self, phone, tmp_path):
        manager = TransferManager()
        wait_for(manager.start(str(phone), ["Download"], str(tmp_path / "d")))
        assert manager.active() == []

    def test_the_registry_is_safe_to_touch_from_another_thread(self, phone, tmp_path):
        # The web server polls status from request threads while workers write.
        manager = TransferManager()
        errors = []

        def poll():
            try:
                for _ in range(200):
                    [job.as_dict() for job in manager.all()]
            except Exception as exc:  # pragma: no cover - only on a real race
                errors.append(exc)

        watcher = threading.Thread(target=poll)
        watcher.start()
        for index in range(5):
            manager.start(str(phone), ["DCIM"], str(tmp_path / f"d{index}"))
        watcher.join()
        assert errors == []
