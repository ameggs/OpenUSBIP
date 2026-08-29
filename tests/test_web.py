"""
Tests for the hub's web layer.

These start a real server on a real port and talk to it over HTTP. The auth
surface is the reason: this listens on the LAN, runs as root, and can attach
devices and write files, so "is the token actually enforced on every endpoint"
is a question worth answering against the running thing rather than against a
handler called directly.
"""

import json
import os
import stat
import sys
import threading
import urllib.error
import urllib.request

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from src.core.transfer import TransferManager  # noqa: E402
from src.web import auth  # noqa: E402
from src.web.hub import Hub, HubError  # noqa: E402
from src.web.server import serve  # noqa: E402


class FakeWrapper:
    """A USBIPWrapper that never shells out."""

    def __init__(self):
        self.attached = []

    def is_available(self):
        return True

    def list_remote_devices(self, host, port=3240):
        from src.core.usbip_wrapper import DeviceState, USBDevice

        return [USBDevice("1-1", "046d", "c52b", "Test Device", DeviceState.AVAILABLE)]

    def list_attached_devices(self):
        return []

    def attach_device(self, host, busid, port=3240):
        self.attached.append((host, busid, port))
        return True, "ok"

    def detach_device(self, port_num):
        return True, "ok"


class FakeDiscovery:
    def __init__(self):
        self.servers = []

    def start(self, callback=None):
        return True

    def stop(self):
        pass

    def get_servers(self):
        return self.servers

    def add_manual_server(self, ip_address, port=3240, name=None):
        from src.core.server_discovery import USBIPServer

        server = USBIPServer(name or ip_address, ip_address, ip_address, port,
                             server_type="manual")
        self.servers.append(server)
        return server

    def remove_server(self, ip_address):
        before = len(self.servers)
        self.servers = [s for s in self.servers if s.ip_address != ip_address]
        return len(self.servers) < before


@pytest.fixture
def hub():
    return Hub(usbip=FakeWrapper(), discovery=FakeDiscovery())


@pytest.fixture
def server(hub):
    """A live hub server on an ephemeral port."""
    httpd = serve(hub, token="secret-token", bind="127.0.0.1", port=0)
    # A short poll interval only so teardown is quick: shutdown() waits for
    # serve_forever to notice, and the default 0.5s adds half a second to
    # every one of these tests. Production runs until killed and never cares.
    thread = threading.Thread(
        target=lambda: httpd.serve_forever(poll_interval=0.02), daemon=True
    )
    thread.start()
    httpd.base = f"http://127.0.0.1:{httpd.server_address[1]}"
    yield httpd
    httpd.shutdown()
    httpd.server_close()


def call(server, path, token="secret-token", method="GET", body=None):
    """Returns (status, parsed json)."""
    request = urllib.request.Request(server.base + path, method=method)
    if token is not None:
        request.add_header("X-Auth-Token", token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, data, timeout=10) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        try:
            return exc.code, json.loads(raw or b"{}")
        except ValueError:
            return exc.code, {"raw": raw.decode(errors="replace")}


# --- token storage ----------------------------------------------------------


class TestTokenFile:
    def test_creates_a_token_on_first_run(self, tmp_path):
        path = tmp_path / "sub" / "hub.token"
        token, created = auth.load_or_create_token(str(path))
        assert created and len(token) > 20

    def test_reuses_the_token_on_the_next_run(self, tmp_path):
        path = str(tmp_path / "hub.token")
        first, _ = auth.load_or_create_token(path)
        second, created = auth.load_or_create_token(path)
        assert (second, created) == (first, False)

    def test_the_token_file_is_not_readable_by_others(self, tmp_path):
        path = str(tmp_path / "hub.token")
        auth.load_or_create_token(path)
        assert stat.S_IMODE(os.stat(path).st_mode) == 0o600

    def test_a_world_readable_token_is_replaced_not_trusted(self, tmp_path):
        # Carrying on with an exposed secret would leave the user believing
        # the service is protected when it is not.
        path = tmp_path / "hub.token"
        path.write_text("leaked-token\n")
        path.chmod(0o644)

        token, created = auth.load_or_create_token(str(path))
        assert created
        assert token != "leaked-token"
        assert stat.S_IMODE(os.stat(path).st_mode) == 0o600


class TestTokenComparison:
    def test_accepts_the_right_token(self):
        assert auth.token_matches("abc", "abc")

    @pytest.mark.parametrize("supplied", ["wrong", "", None, "ab", "abcd"])
    def test_rejects_anything_else(self, supplied):
        assert not auth.token_matches(supplied, "abc")

    def test_an_empty_expected_token_never_matches(self):
        # Otherwise a failed token load would leave the service wide open.
        assert not auth.token_matches("anything", "")


# --- authorisation ----------------------------------------------------------


class TestAuthorisation:
    ENDPOINTS = [
        ("GET", "/api/state", None),
        ("GET", "/api/devices?host=1.2.3.4", None),
        ("GET", "/api/browse?id=x", None),
        ("GET", "/api/jobs", None),
        ("POST", "/api/servers", {"host": "1.2.3.4"}),
        ("POST", "/api/servers/remove", {"host": "1.2.3.4"}),
        ("POST", "/api/attach", {"host": "1.2.3.4", "busid": "1-1"}),
        ("POST", "/api/detach", {"id": "x"}),
        ("POST", "/api/copy", {"id": "x", "sources": ["a"], "destination": "/tmp"}),
        ("POST", "/api/jobs/cancel", {"job": "x"}),
    ]

    @pytest.mark.parametrize("method,path,body", ENDPOINTS)
    def test_every_endpoint_rejects_a_missing_token(self, server, method, path, body):
        status, _ = call(server, path, token=None, method=method, body=body)
        assert status == 401

    @pytest.mark.parametrize("method,path,body", ENDPOINTS)
    def test_every_endpoint_rejects_a_wrong_token(self, server, method, path, body):
        status, _ = call(server, path, token="nope", method=method, body=body)
        assert status == 401

    def test_the_page_itself_needs_no_token(self, server):
        # It holds no data -- only the code that then has to authenticate.
        with urllib.request.urlopen(server.base + "/", timeout=10) as response:
            assert response.status == 200
            assert b"OpenUSBIP" in response.read()

    def test_an_unknown_path_is_not_found(self, server):
        assert call(server, "/../etc/passwd")[0] in (400, 404)


# --- endpoints --------------------------------------------------------------


class TestEndpoints:
    def test_state_reports_the_empty_case(self, server):
        status, payload = call(server, "/api/state")
        assert status == 200
        assert payload["attachments"] == []
        assert payload["usbip_available"] is True

    def test_a_server_can_be_added_and_removed(self, server):
        status, payload = call(
            server, "/api/servers", method="POST", body={"host": "192.168.0.146"}
        )
        assert status == 200
        assert payload["server"]["ip_address"] == "192.168.0.146"

        _, state = call(server, "/api/state")
        assert len(state["servers"]) == 1

        call(server, "/api/servers/remove", method="POST",
             body={"host": "192.168.0.146"})
        _, state = call(server, "/api/state")
        assert state["servers"] == []

    def test_adding_a_blank_server_is_a_bad_request(self, server):
        status, payload = call(server, "/api/servers", method="POST", body={"host": " "})
        assert status == 400
        assert "required" in payload["error"]

    def test_remote_devices_are_listed(self, server):
        status, payload = call(server, "/api/devices?host=192.168.0.146")
        assert status == 200
        assert payload["devices"][0]["busid"] == "1-1"

    def test_listing_devices_needs_a_host(self, server):
        assert call(server, "/api/devices")[0] == 400

    def test_browsing_an_unknown_attachment_is_a_bad_request(self, server):
        status, payload = call(server, "/api/browse?id=nope")
        assert status == 400
        assert "no longer attached" in payload["error"]

    def test_malformed_json_is_reported_clearly(self, server):
        request = urllib.request.Request(
            server.base + "/api/servers", method="POST", data=b"{not json"
        )
        request.add_header("X-Auth-Token", "secret-token")
        try:
            urllib.request.urlopen(request, timeout=10)
            raise AssertionError("should have failed")
        except urllib.error.HTTPError as exc:
            assert exc.code == 400
            assert "valid JSON" in json.loads(exc.read())["error"]

    def test_an_oversized_body_is_refused(self, server):
        # The cap exists so a single request cannot exhaust memory.
        request = urllib.request.Request(
            server.base + "/api/servers", method="POST", data=b"x" * (2 * 1024 * 1024)
        )
        request.add_header("X-Auth-Token", "secret-token")
        request.add_header("Content-Type", "application/json")
        try:
            urllib.request.urlopen(request, timeout=10)
            raise AssertionError("should have failed")
        except urllib.error.HTTPError as exc:
            # 413, and delivered reliably: the body is drained before the
            # refusal, so closing the socket cannot reset the connection and
            # lose the response.
            assert exc.code == 413
            assert "too large" in json.loads(exc.read())["error"]


class TestResponseHeaders:
    def test_the_python_version_is_not_advertised(self, server):
        # The default Server header names the exact Python build, which is
        # free reconnaissance for anyone scanning the port.
        with urllib.request.urlopen(server.base + "/", timeout=10) as response:
            assert "Python" not in response.headers.get("Server", "")

    def test_responses_are_not_framable_or_sniffable(self, server):
        with urllib.request.urlopen(server.base + "/", timeout=10) as response:
            assert response.headers.get("X-Frame-Options") == "DENY"
            assert response.headers.get("X-Content-Type-Options") == "nosniff"


# --- hub behaviour ----------------------------------------------------------


class TestHubCopyGuards:
    def test_refuses_to_copy_into_the_mount_root(self, hub, tmp_path, monkeypatch):
        # Copying from a mount back into the mount tree either recurses or
        # writes to the phone; neither is ever what was meant.
        from src.web import hub as hub_module

        mountpoint = tmp_path / "run" / "openusbip" / "phone"
        mountpoint.mkdir(parents=True)
        monkeypatch.setattr(
            hub_module.mounts, "MOUNT_ROOT", str(tmp_path / "run" / "openusbip")
        )

        attachment = hub_module.Attachment(
            id="a1", server_host="h", server_port=3240, busid="1-1",
            description="Phone", kind="mtp", mountpoint=str(mountpoint),
        )
        hub._attachments["a1"] = attachment
        monkeypatch.setattr(hub_module.mounts, "is_mounted", lambda p: True)

        with pytest.raises(HubError, match="not inside"):
            hub.copy("a1", ["DCIM"], str(mountpoint / "copy"))

    def test_refuses_to_copy_from_an_unmounted_device(self, hub):
        from src.web.hub import Attachment

        hub._attachments["a1"] = Attachment(
            id="a1", server_host="h", server_port=3240, busid="1-1",
            description="Phone", kind="mtp",
        )
        with pytest.raises(HubError, match="not mounted"):
            hub.copy("a1", ["DCIM"], "/tmp/dest")

    def test_detach_refuses_while_a_copy_is_running(self, hub, tmp_path, monkeypatch):
        # Pulling the device out mid-copy corrupts what was being written.
        from src.web import hub as hub_module

        mountpoint = tmp_path / "mnt"
        mountpoint.mkdir()
        hub._attachments["a1"] = hub_module.Attachment(
            id="a1", server_host="h", server_port=3240, busid="1-1",
            description="Drive", kind="storage", mountpoint=str(mountpoint),
        )

        class Busy:
            destination = str(mountpoint / "out")
            is_finished = False

        monkeypatch.setattr(hub.transfers, "active", lambda: [Busy()])
        with pytest.raises(HubError, match="copy is still running"):
            hub.detach("a1")


class TestHubAttachFailures:
    def test_a_failed_attach_says_why(self, hub, monkeypatch):
        monkeypatch.setattr(
            hub.usbip, "attach_device", lambda h, b, p: (False, "device is busy")
        )
        with pytest.raises(HubError, match="device is busy"):
            hub.attach("192.168.0.146", "1-1")

    def test_a_device_that_never_appears_is_reported_not_hidden(self, hub, monkeypatch):
        from src.web import hub as hub_module

        monkeypatch.setattr(hub_module.vhci, "list_attached", lambda *a, **k: [])
        monkeypatch.setattr(
            hub_module.vhci, "wait_for_new_device", lambda *a, **k: None
        )
        with pytest.raises(HubError, match="never appeared"):
            hub.attach("192.168.0.146", "1-1")


class TestManagerIntegration:
    def test_the_hub_exposes_job_state(self, hub, tmp_path):
        source = tmp_path / "mnt"
        source.mkdir()
        (source / "a.txt").write_text("hello")

        hub.transfers = TransferManager()
        job = hub.transfers.start(str(source), ["a.txt"], str(tmp_path / "dest"))
        assert job.as_dict()["destination"] == str(tmp_path / "dest")
