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
