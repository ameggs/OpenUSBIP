"""
Tests for the Windows tool-discovery helpers.

These run on Linux. That is the whole point of the module's design: the
filesystem check and the environment are both injectable, so the search logic
can be exercised without a Windows machine -- and this logic is exactly the
kind that is otherwise only discovered to be wrong by a user whose working
installation reports itself missing.
"""

import os

import pytest

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
    @pytest.mark.parametrize(
        "message,url",
        [
            (ws.missing_client_message(), ws.USBIP_WIN2_URL),
            (ws.missing_server_message(), ws.USBIPD_WIN_URL),
        ],
    )
    def test_points_at_the_right_download(self, message, url):
        # A "not found" message that does not say where to get it is a dead
        # end for the user, which is the failure this guards against.
        assert url in message

    def test_client_and_server_messages_are_distinct(self):
        assert ws.missing_client_message() != ws.missing_server_message()

    def test_elevation_message_names_the_action(self):
        assert "attach a device" in ws.needs_elevation_message("attach a device")

    def test_elevation_message_says_how_to_elevate(self):
        assert "administrator" in ws.needs_elevation_message().lower()


class TestIsElevated:
    def test_returns_false_off_windows_rather_than_raising(self):
        # ctypes.windll does not exist on Linux; the helper must degrade to
        # "not elevated" instead of taking the app down.
        assert ws.is_elevated() is False


# --- usbipd-win output parsing -----------------------------------------------

STATE_JSON = """\
{
  "Devices": [
    {
      "BusId": "1-1",
      "ClientIPAddress": null,
      "Description": "USB Input Device",
      "InstanceId": "USB\\\\VID_046D&PID_C52B\\\\5&1234ABCD&0&1",
      "IsForced": false,
      "PersistedGuid": null,
      "StubInstanceId": null
    },
    {
      "BusId": "3-2",
      "ClientIPAddress": null,
      "Description": "USB Mass Storage Device",
      "InstanceId": "USB\\\\VID_0781&PID_5583\\\\4C530001",
      "IsForced": false,
      "PersistedGuid": "b1b2c3d4-0000-0000-0000-000000000001",
      "StubInstanceId": null
    },
    {
      "BusId": "3-4",
      "ClientIPAddress": "192.168.0.20",
      "Description": "USB Serial Converter",
      "InstanceId": "USB\\\\VID_0403&PID_6001\\\\A50285BI",
      "IsForced": false,
      "PersistedGuid": "b1b2c3d4-0000-0000-0000-000000000002",
      "StubInstanceId": "USB\\\\VID_80EE&PID_CAFE\\\\1"
    },
    {
      "BusId": null,
      "ClientIPAddress": null,
      "Description": "Persisted But Unplugged",
      "InstanceId": "USB\\\\VID_1234&PID_5678\\\\X",
      "IsForced": false,
      "PersistedGuid": "b1b2c3d4-0000-0000-0000-000000000003",
      "StubInstanceId": null
    }
  ]
}
"""

# Fixed-width, per usbipd-win's own format string:
#   {BUSID,-5}  {VID:PID,-9}  {DEVICE,-60}  STATE
LIST_OUTPUT = (
    "Connected:\n"
    f"{'BUSID':<5}  {'VID:PID':<9}  {'DEVICE':<60}  STATE\n"
    f"{'1-1':<5}  {'046d:c52b':<9}  {'USB Input Device':<60}  Not shared\n"
    f"{'3-2':<5}  {'0781:5583':<9}  {'USB Mass Storage Device':<60}  Shared\n"
    f"{'3-4':<5}  {'0403:6001':<9}  {'USB Serial Converter':<60}  Attached\n"
    "\n"
    "Persisted:\n"
    f"{'GUID':<36}  DEVICE\n"
    f"{'b1b2c3d4-0000-0000-0000-000000000003':<36}  Persisted But Unplugged\n"
    "\n"
    "WARNING: A newer version of usbipd-win is available.\n"
)


class TestParseHardwareId:
    def test_extracts_vid_and_pid_lowercased(self):
        assert ws.parse_hardware_id(r"USB\VID_046D&PID_C52B\5&1&1") == ("046d", "c52b")

    def test_unparseable_id_yields_zeroes_not_an_error(self):
        # Composite and non-USB entries appear in the same list; they are not
        # a failure, they just have no hardware id to show.
        assert ws.parse_hardware_id("ROOT\\SYSTEM\\0000") == ("0000", "0000")

    def test_empty_id_is_safe(self):
        assert ws.parse_hardware_id("") == ("0000", "0000")


class TestParseStateJson:
    def test_skips_persisted_but_unplugged_devices(self):
        # A device with no BusId is not plugged in and cannot be attached, so
        # offering it to a client would be a dead end.
        devices = ws.parse_state_json(STATE_JSON)
        assert [d["busid"] for d in devices] == ["1-1", "3-2", "3-4"]

    def test_client_address_means_attached(self):
        devices = {d["busid"]: d for d in ws.parse_state_json(STATE_JSON)}
        assert devices["3-4"]["state"] == ws.STATE_ATTACHED
        assert devices["3-4"]["client"] == "192.168.0.20"

    def test_persisted_guid_without_client_means_shared(self):
        devices = {d["busid"]: d for d in ws.parse_state_json(STATE_JSON)}
        assert devices["3-2"]["state"] == ws.STATE_SHARED

    def test_neither_means_not_shared(self):
        devices = {d["busid"]: d for d in ws.parse_state_json(STATE_JSON)}
        assert devices["1-1"]["state"] == ws.STATE_NOT_SHARED

    def test_keeps_the_full_description(self):
        devices = {d["busid"]: d for d in ws.parse_state_json(STATE_JSON)}
        assert devices["3-2"]["description"] == "USB Mass Storage Device"

    def test_derives_vid_pid_from_the_instance_id(self):
        devices = {d["busid"]: d for d in ws.parse_state_json(STATE_JSON)}
        assert (devices["3-2"]["vendor_id"], devices["3-2"]["product_id"]) == (
            "0781",
            "5583",
        )

    @pytest.mark.parametrize(
        "text", ["", "not json at all", "{}", '{"Devices": null}', "[]"]
    )
    def test_malformed_output_yields_no_devices_rather_than_raising(self, text):
        # This is polled on a timer; a bad parse must show an empty list, not
        # take down a refresh.
        assert ws.parse_state_json(text) == []


class TestParseListOutput:
    def test_reads_only_the_connected_section(self):
        # The Persisted section lists unpluggable devices and the trailing
        # lines are free-form status text; neither is a shareable device.
        devices = ws.parse_list_output(LIST_OUTPUT)
        assert [d["busid"] for d in devices] == ["1-1", "3-2", "3-4"]

    def test_description_is_not_truncated_at_the_first_space(self):
        # The bug this replaces: splitting on whitespace made the description
        # "USB" and folded the rest of the name into the state column.
        devices = {d["busid"]: d for d in ws.parse_list_output(LIST_OUTPUT)}
        assert devices["3-2"]["description"] == "USB Mass Storage Device"

    def test_multiword_state_is_read_correctly(self):
        devices = {d["busid"]: d for d in ws.parse_list_output(LIST_OUTPUT)}
        assert devices["1-1"]["state"] == ws.STATE_NOT_SHARED
        assert devices["3-2"]["state"] == ws.STATE_SHARED
        assert devices["3-4"]["state"] == ws.STATE_ATTACHED

    def test_splits_vid_and_pid(self):
        devices = {d["busid"]: d for d in ws.parse_list_output(LIST_OUTPUT)}
        assert devices["1-1"]["vendor_id"] == "046d"
        assert devices["1-1"]["product_id"] == "c52b"

    def test_skips_rows_with_a_blank_busid(self):
        # usbipd-win prints an incompatible hub with an empty busid; it cannot
        # be bound, so it must not appear as a shareable device.
        text = (
            "Connected:\n"
            f"{'BUSID':<5}  {'VID:PID':<9}  {'DEVICE':<60}  STATE\n"
            f"{'':<5}  {'8087:0aaa':<9}  {'Generic USB Hub':<60}  Incompatible hub\n"
            f"{'1-1':<5}  {'046d:c52b':<9}  {'USB Input Device':<60}  Not shared\n"
        )
        assert [d["busid"] for d in ws.parse_list_output(text)] == ["1-1"]

    def test_empty_output_yields_no_devices(self):
        assert ws.parse_list_output("") == []

    def test_output_without_a_connected_header_yields_nothing(self):
        assert ws.parse_list_output("some unrelated error text\n") == []
