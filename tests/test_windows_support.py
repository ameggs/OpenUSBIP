"""
Tests for the Windows tool-discovery helpers.

These run on Linux. That is the whole point of the module's design: the
filesystem check and the environment are both injectable, so the search logic
can be exercised without a Windows machine -- and this logic is exactly the
kind that is otherwise only discovered to be wrong by a user whose working
installation reports itself missing.
"""

import os

from src.utils import windows_support as ws

PF = r"C:\Program Files"
PF86 = r"C:\Program Files (x86)"


def env(**kwargs) -> dict[str, str]:
    """An environment with only the keys a test cares about."""
    return {k.replace("_x86_", "(x86)"): v for k, v in kwargs.items()}


class TestProgramFilesRoots:
    def test_prefers_w6432_then_native_then_x86(self):
        roots = ws.program_files_roots(
            env(ProgramW6432=PF, ProgramFiles=PF86, ProgramFiles_x86_=PF86)
        )
        assert roots[0] == PF

    def test_deduplicates_identical_roots(self):
        # The common case on 64-bit Python: ProgramW6432 and ProgramFiles are
        # the same string, and searching it twice doubles the stat calls.
        roots = ws.program_files_roots(env(ProgramW6432=PF, ProgramFiles=PF))
        assert roots == [PF]

    def test_includes_x86_for_32bit_builds(self):
        roots = ws.program_files_roots(env(ProgramFiles=PF, ProgramFiles_x86_=PF86))
        assert PF86 in roots

    def test_empty_environment_yields_no_roots(self):
        assert ws.program_files_roots({}) == []

    def test_ignores_empty_values(self):
        assert ws.program_files_roots(env(ProgramFiles="", ProgramW6432=PF)) == [PF]


class TestCandidatePaths:
    def test_appends_exe_suffix(self):
        paths = ws.candidate_paths("usbip", env(ProgramFiles=PF))
        assert all(p.endswith(".exe") for p in paths)

    def test_does_not_double_the_suffix(self):
        paths = ws.candidate_paths("usbip.exe", env(ProgramFiles=PF))
        assert all(not p.endswith(".exe.exe") for p in paths)

    def test_suffix_check_is_case_insensitive(self):
        # Windows filenames are case-insensitive, and a caller passing
        # "USBIP.EXE" should not end up searching for "USBIP.EXE.exe".
        paths = ws.candidate_paths("USBIP.EXE", env(ProgramFiles=PF))
        assert all(not p.lower().endswith(".exe.exe") for p in paths)

    def test_covers_every_known_install_dir(self):
        paths = ws.candidate_paths("usbip", env(ProgramFiles=PF))
        assert len(paths) == len(ws.INSTALL_SUBDIRS)

    def test_searches_usbip_win2_first(self):
        # usbip-win2 is the maintained client, so a machine with both an old
        # usbip-win and a current usbip-win2 should get the current one.
        paths = ws.candidate_paths("usbip", env(ProgramFiles=PF))
        assert "usbip-win2" in paths[0]

    def test_no_roots_means_no_candidates(self):
        assert ws.candidate_paths("usbip", {}) == []


class TestFindTool:
    def test_returns_first_existing_candidate(self):
        installed = os.path.join(PF, "usbip-win2", "usbip.exe")
        found = ws.find_tool(
            "usbip", env(ProgramFiles=PF), exists=lambda p: p == installed
        )
        assert found == installed

    def test_returns_none_when_nothing_installed(self):
        assert ws.find_tool("usbip", env(ProgramFiles=PF), exists=lambda p: False) is None

    def test_finds_usbipd_in_its_bin_subdir(self):
        # usbipd-win puts its executable one level deeper than the others.
        installed = os.path.join(PF, "usbipd-win", "bin", "usbipd.exe")
        found = ws.find_tool(
            "usbipd", env(ProgramFiles=PF), exists=lambda p: p == installed
        )
        assert found == installed

    def test_search_order_wins_over_filesystem_order(self):
        # With both installed, the earlier subdir must win regardless of which
        # the injected predicate would happen to enumerate first.
        found = ws.find_tool("usbip", env(ProgramFiles=PF), exists=lambda p: True)
        assert found == os.path.join(PF, ws.INSTALL_SUBDIRS[0], "usbip.exe")


class TestMessages:
    def test_points_at_the_right_download(self):
        # A "not found" message that does not say where to get it is a dead
        # end for the user, which is the failure this guards against.
        assert ws.USBIP_WIN2_URL in ws.missing_client_message()

    def test_elevation_message_names_the_action(self):
        assert "attach a device" in ws.needs_elevation_message("attach a device")

    def test_elevation_message_says_how_to_elevate(self):
        assert "administrator" in ws.needs_elevation_message().lower()


class TestIsElevated:
    def test_returns_false_off_windows_rather_than_raising(self):
        # ctypes.windll does not exist on Linux; the helper must degrade to
        # "not elevated" instead of taking the app down.
        assert ws.is_elevated() is False
