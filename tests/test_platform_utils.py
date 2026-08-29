"""Tests for platform helpers."""

import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from src.utils import platform_utils  # noqa: E402
from src.utils.platform_utils import (  # noqa: E402
    ROLE_MODULES,
    Platform,
    check_kernel_modules,
)

LOADED_BOTH = "vhci_hcd 61440 0 - Live 0x0\nusbip_core 45056 1 - Live 0x0\nusbip_host 28672 0 - Live 0x0\n"
LOADED_CLIENT_ONLY = "vhci_hcd 61440 0 - Live 0x0\nusbip_core 45056 1 - Live 0x0\n"


@pytest.fixture(autouse=True)
def force_linux(monkeypatch):
    monkeypatch.setattr(platform_utils, "get_platform", lambda: Platform.LINUX)


def _fake_modules(monkeypatch, tmp_path, contents):
    modules = tmp_path / "modules"
    modules.write_text(contents)
    real_open = open

    def fake_open(path, *args, **kwargs):
        if path == "/proc/modules":
            return real_open(modules, *args, **kwargs)
        return real_open(path, *args, **kwargs)

    monkeypatch.setattr("builtins.open", fake_open)


def test_role_modules_are_distinct():
    """A client needs the virtual host controller; a server needs the exporter."""
    assert ROLE_MODULES["client"] == ["vhci_hcd"]
    assert ROLE_MODULES["server"] == ["usbip_host"]
    assert set(ROLE_MODULES["both"]) == {"vhci_hcd", "usbip_host"}


def test_client_role_ignores_the_server_module(monkeypatch, tmp_path):
    """Regression: the client required usbip_host, a server-side module it never
    uses, so it warned and then modprobe'd something unnecessary."""
    _fake_modules(monkeypatch, tmp_path, LOADED_CLIENT_ONLY)

    loaded, missing = check_kernel_modules(role="client")
    assert loaded and missing == []


def test_server_role_reports_its_own_module_missing(monkeypatch, tmp_path):
    _fake_modules(monkeypatch, tmp_path, LOADED_CLIENT_ONLY)

    loaded, missing = check_kernel_modules(role="server")
    assert not loaded and missing == ["usbip_host"]


def test_both_is_the_default_and_requires_everything(monkeypatch, tmp_path):
    _fake_modules(monkeypatch, tmp_path, LOADED_CLIENT_ONLY)

    assert check_kernel_modules() == check_kernel_modules(role="both")
    loaded, missing = check_kernel_modules()
    assert not loaded and missing == ["usbip_host"]


def test_all_loaded(monkeypatch, tmp_path):
    _fake_modules(monkeypatch, tmp_path, LOADED_BOTH)

    for role in ("client", "server", "both"):
        loaded, missing = check_kernel_modules(role=role)
        assert loaded and missing == [], role


def test_unknown_role_falls_back_to_both(monkeypatch, tmp_path):
    _fake_modules(monkeypatch, tmp_path, LOADED_CLIENT_ONLY)

    assert check_kernel_modules(role="nonsense") == check_kernel_modules(role="both")


def test_non_linux_is_a_no_op(monkeypatch):
    monkeypatch.setattr(platform_utils, "get_platform", lambda: Platform.WINDOWS)
    assert check_kernel_modules(role="client") == (True, [])


# --- Windows wiring ---------------------------------------------------------
#
# These exercise the Windows branches from Linux by patching the platform
# probe. They are about the wiring -- that the branch is reached and returns
# the right shape -- while the search logic itself is pinned down in
# test_windows_support.py.


def test_find_command_searches_program_files_on_windows(monkeypatch):
    """
    A working usbip-win2 install is invisible to PATH, so PATH alone reported
    the tools missing on machines that had them.
    """
    from src.utils import platform_utils

    monkeypatch.setattr(platform_utils, "get_platform", lambda: Platform.WINDOWS)
    monkeypatch.setattr(platform_utils.shutil, "which", lambda name: None)
    monkeypatch.setattr(
        "src.utils.windows_support.find_tool",
        lambda command, *a, **k: r"C:\Program Files\usbip-win2\usbip.exe",
    )

    assert platform_utils.find_command("usbip").endswith("usbip.exe")


def test_find_command_still_prefers_path_when_it_has_the_tool(monkeypatch):
    from src.utils import platform_utils

    monkeypatch.setattr(platform_utils, "get_platform", lambda: Platform.WINDOWS)
    monkeypatch.setattr(
        platform_utils.shutil, "which", lambda name: r"C:\tools\usbip.exe"
    )

    assert platform_utils.find_command("usbip") == r"C:\tools\usbip.exe"


def test_windows_admin_requirement_explains_itself_instead_of_failing(monkeypatch):
    """
    Windows cannot elevate a running process, so there is no equivalent of
    prepending sudo. Running the command regardless produces an opaque access
    error; this returns something a user can act on.
    """
    from src.utils import platform_utils

    monkeypatch.setattr(platform_utils, "get_platform", lambda: Platform.WINDOWS)
    monkeypatch.setattr(platform_utils, "is_admin", lambda: False)

    def explode(*args, **kwargs):
        raise AssertionError("the command must not run without elevation")

    monkeypatch.setattr(platform_utils.subprocess, "run", explode)

    code, out, err = platform_utils.run_command(["usbip", "attach"], require_admin=True)

    assert code != 0
    assert "administrator" in err.lower()


def test_windows_commands_not_needing_admin_still_run(monkeypatch):
    """The elevation guard must only apply to require_admin=True."""
    from src.utils import platform_utils

    monkeypatch.setattr(platform_utils, "get_platform", lambda: Platform.WINDOWS)
    monkeypatch.setattr(platform_utils, "is_admin", lambda: False)

    class Result:
        returncode = 0
        stdout = "devices"
        stderr = ""

    monkeypatch.setattr(platform_utils.subprocess, "run", lambda *a, **k: Result())

    assert platform_utils.run_command(["usbipd", "state"])[1] == "devices"


def test_port_is_listening_detects_a_live_socket():
    import socket

    from src.utils.platform_utils import port_is_listening

    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    port = server.getsockname()[1]
    try:
        assert port_is_listening(port) is True
    finally:
        server.close()

    # Closed again: the same port must now read as down, which is what
    # distinguishes this from merely checking that a service is registered.
    assert port_is_listening(port, timeout=0.3) is False
