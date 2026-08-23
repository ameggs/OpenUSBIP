"""
Import smoke tests for the entry points.

The server entry point shipped broken for the whole life of the project: it
put src/ on sys.path, making `core` a top-level package, while
usbip_wrapper.py used `from ..utils` to reach above it. Every run died with
"attempted relative import beyond top-level package".

A test this cheap would have caught it, so it exists now.
"""

import importlib
import os
import subprocess
import sys

import pytest

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, REPO_ROOT)


CORE_MODULES = [
    "src.core.usbip_wrapper",
    "src.core.server_discovery",
    "src.core.config_manager",
    "src.utils.platform_utils",
    "src.utils.logger",
]


@pytest.mark.parametrize("module", CORE_MODULES)
def test_core_module_imports(module):
    assert importlib.import_module(module) is not None


@pytest.mark.parametrize("module", ["openusbip_client", "openusbip_server"])
def test_entry_point_imports(module):
    assert importlib.import_module(module) is not None


def test_server_entry_point_runs_as_a_subprocess():
    """
    Regression: `python3 openusbip_server.py` raised ImportError immediately.

    --help exits before any USB/IP work, so this is safe on a machine with no
    usbip installed, but it still executes the module-level imports that used
    to fail.
    """
    result = subprocess.run(
        [sys.executable, os.path.join(REPO_ROOT, "openusbip_server.py"), "--help"],
        capture_output=True,
        text=True,
        timeout=60,
        cwd=REPO_ROOT,
    )
    assert result.returncode == 0, result.stderr
    assert "attempted relative import" not in result.stderr
    assert "--daemon" in result.stdout


def test_there_is_only_one_canonical_module_identity():
    """
    Regression: `src.core.server_discovery` and `core.server_discovery` loaded
    as two distinct modules with non-identical classes, so isinstance checks
    across them failed -- a latent trap for Signal(USBIPServer).
    """
    from src.core.server_discovery import USBIPServer

    loaded = [m for m in sys.modules if m.endswith("core.server_discovery")]
    assert loaded == ["src.core.server_discovery"], loaded

    server = USBIPServer("n", "h", "1.2.3.4")
    assert isinstance(server, USBIPServer)


def test_no_module_uses_imports_that_reach_above_the_package():
    """The relative-import style that caused the original crash must not return."""
    offenders = []
    for dirpath, _dirnames, filenames in os.walk(os.path.join(REPO_ROOT, "src")):
        for name in filenames:
            if not name.endswith(".py"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                for lineno, line in enumerate(handle, 1):
                    if line.startswith("from .."):
                        offenders.append(f"{path}:{lineno}")
    assert not offenders, f"relative imports reaching above the package: {offenders}"
