"""
Checks on the served UI page.

A syntax error in the inline script produces a blank page with an error only
in the browser console -- nothing on the server side fails, so no other test
here would notice. Since the page cannot be clicked through from CI, this at
least proves it parses and that the pieces the server depends on are present.
"""

import os
import re
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from src.web.server import UI_PATH  # noqa: E402


@pytest.fixture(scope="module")
def page():
    with open(UI_PATH, encoding="utf-8") as handle:
        return handle.read()


@pytest.fixture(scope="module")
def script(page):
    match = re.search(r"<script>(.*?)</script>", page, re.S)
    assert match, "the page has no inline script"
    return match.group(1)


def test_the_script_parses(script):
    """
    Parsed with esprima, not Qt's QJSEngine: QJSEngine is ES7 and rejects
    `async function`, which every browser has supported since 2017. It reports
    a syntax error on perfectly good code, so it is the wrong tool here.
    """
    esprima = pytest.importorskip("esprima", reason="esprima not installed")
    esprima.parseScript(script)


def test_the_token_is_sent_as_a_header_not_a_cookie(script):
    # The entire CSRF defence. A cookie would be attached automatically to
    # cross-site requests; a custom header cannot be set cross-origin without
    # a preflight, which this server never grants.
    assert "X-Auth-Token" in script
    assert "document.cookie" not in script


def test_the_token_is_stripped_from_the_address_bar(script):
    # So it does not linger in history or get copied out along with the link.
    assert "replaceState" in script
    assert "sessionStorage" in script


def test_no_external_resources_are_loaded(page):
    """
    Everything is inline. A hypervisor's management interface should not need
    the internet to render, and a CDN would be a third party in a position to
    inject script into a page that drives a root service.
    """
    for pattern in ("src=\"http", "href=\"http", "@import", "//cdn"):
        assert pattern not in page, f"page pulls in an external resource: {pattern}"


@pytest.mark.parametrize(
    "endpoint",
    ["/api/state", "/api/devices", "/api/browse", "/api/attach",
     "/api/detach", "/api/copy", "/api/servers", "/api/jobs/cancel"],
)
def test_every_endpoint_the_page_calls_exists(endpoint, script):
    # Guards against a route being renamed on the server without the page
    # being updated -- which fails only when a user clicks the button.
    assert endpoint in script


def test_the_page_renders_in_both_colour_schemes(page):
    # It is opened next to the Proxmox UI, which follows the system theme.
    assert "prefers-color-scheme: dark" in page
    assert "color-scheme: light dark" in page
