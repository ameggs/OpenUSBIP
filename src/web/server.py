"""
HTTP front end for the hub.

Built on the standard library rather than a framework, deliberately. This runs
on a Proxmox host and an Unraid box, where Python packages are awkward: Debian
marks its interpreter externally managed, and Unraid rebuilds its root
filesystem from RAM on every boot. `apt install usbip jmtpfs python3-zeroconf`
plus a directory of files is an install anyone can reason about; adding a pip
dependency tree to a hypervisor is not.

The surface is small enough that a framework would not save much: ten JSON
endpoints and one static page.
"""

from __future__ import annotations

import json
import os
import socket
import traceback
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from src.core.transfer import PathEscape
from src.core.usbip_wrapper import DEFAULT_USBIP_PORT
from src.utils.logger import setup_logger
from src.web import auth
from src.web.hub import Hub, HubError

logger = setup_logger(__name__)

DEFAULT_PORT = 8765

#: Cap on request bodies. Every body here is a small JSON object; anything
#: larger is a mistake or an attempt to exhaust memory.
MAX_BODY_BYTES = 1024 * 1024

#: How much of an over-sized body to read and throw away before answering.
#: Closing a socket that still has unread data sends RST, which can discard the
#: response the client was meant to read -- so refusing without draining turns
#: a clear "body too large" into a connection reset, intermittently. Draining
#: in fixed chunks keeps memory bounded, which is what the cap protects.
DRAIN_LIMIT = 8 * 1024 * 1024
DRAIN_CHUNK = 64 * 1024

UI_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ui.html")


class _TooLarge(HubError):
    """The request body exceeded MAX_BODY_BYTES."""


class HubRequestHandler(BaseHTTPRequestHandler):
    """One request. The hub and token are attached to the server object."""

    server_version = "OpenUSBIP-Hub"
    sys_version = ""  # do not advertise the Python version

    # --- plumbing -------------------------------------------------------------

    @property
    def hub(self) -> Hub:
        return self.server.hub  # type: ignore[attr-defined]

    @property
    def token(self) -> str:
        return self.server.token  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args) -> None:
        # BaseHTTPRequestHandler writes to stderr by default, which under
        # systemd means every poll lands in the journal.
        logger.debug("%s - %s", self.address_string(), fmt % args)

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        # This page talks only to itself; nothing here should ever be framed
        # or sniffed into another content type.
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, payload, status: int = HTTPStatus.OK) -> None:
        self._send(status, json.dumps(payload).encode("utf-8"), "application/json")

    def _error(self, status: int, message: str) -> None:
        self._json({"error": message}, status)

    def _body(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            raise HubError("malformed Content-Length") from None
        if length <= 0:
            return {}
        if length > MAX_BODY_BYTES:
            self._drain(length)
            raise _TooLarge(
                f"request body too large ({length} bytes; the limit is {MAX_BODY_BYTES})"
            )

        raw = self.rfile.read(length)
        try:
            parsed = json.loads(raw)
        except ValueError:
            raise HubError("request body was not valid JSON") from None
        if not isinstance(parsed, dict):
            raise HubError("request body must be a JSON object")
        return parsed

    def _drain(self, length: int) -> None:
        """Read and discard a body we are about to refuse. See DRAIN_LIMIT."""
        remaining = min(length, DRAIN_LIMIT)
        try:
            while remaining > 0:
                chunk = self.rfile.read(min(DRAIN_CHUNK, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
        except OSError:
            pass  # the client gave up; nothing left to be polite about

    def _authorised(self) -> bool:
        return auth.token_matches(self.headers.get(auth.HEADER_NAME), self.token)

    # --- entry points ---------------------------------------------------------

    def do_GET(self) -> None:
        self._dispatch("GET")

    def do_POST(self) -> None:
        self._dispatch("POST")

    def do_HEAD(self) -> None:
        self._dispatch("GET")

    def _dispatch(self, method: str) -> None:
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        query = parse_qs(parsed.query)

        try:
            if path == "/" and method == "GET":
                return self._serve_ui()

            if not path.startswith("/api/"):
                return self._error(HTTPStatus.NOT_FOUND, "not found")

            # Everything under /api requires the token. The UI page itself does
            # not, because it contains no data -- only the code that then asks
            # for some.
            if not self._authorised():
                return self._error(HTTPStatus.UNAUTHORIZED, "invalid or missing token")

            return self._route(method, path, query)

        except _TooLarge as exc:
            self._error(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, str(exc))
        except HubError as exc:
            self._error(HTTPStatus.BAD_REQUEST, str(exc))
        except PathEscape as exc:
            logger.warning(f"path escape attempt from {self.address_string()}: {exc}")
            self._error(HTTPStatus.FORBIDDEN, "that path is not inside the device")
        except ValueError as exc:
            self._error(HTTPStatus.BAD_REQUEST, str(exc))
        except BrokenPipeError:
            pass  # the browser navigated away mid-response
        except Exception:
            # The traceback goes to the log, not to the client: it would
            # otherwise disclose paths and versions to anyone who can reach the
            # port.
            logger.error(f"unhandled error on {method} {path}\n{traceback.format_exc()}")
            self._error(HTTPStatus.INTERNAL_SERVER_ERROR, "internal error; see the log")

    def _serve_ui(self) -> None:
        try:
            with open(UI_PATH, "rb") as handle:
                body = handle.read()
        except OSError:
            return self._error(HTTPStatus.INTERNAL_SERVER_ERROR, "UI file missing")
        self._send(HTTPStatus.OK, body, "text/html; charset=utf-8")

    # --- routes ---------------------------------------------------------------

    def _route(self, method: str, path: str, query: dict) -> None:
        hub = self.hub

        if method == "GET":
            if path == "/api/state":
                return self._json(hub.state())

            if path == "/api/devices":
                host = _one(query, "host")
                if not host:
                    raise HubError("a server address is required")
                port = _int(_one(query, "port"), DEFAULT_USBIP_PORT)
                return self._json({"devices": hub.remote_devices(host, port)})

            if path == "/api/browse":
                attachment = _one(query, "id")
                if not attachment:
                    raise HubError("which device?")
                return self._json(hub.browse(attachment, _one(query, "path") or ""))

            if path == "/api/jobs":
                return self._json({"jobs": [j.as_dict() for j in hub.transfers.all()]})

            return self._error(HTTPStatus.NOT_FOUND, "no such endpoint")

        # POST
        body = self._body()

        if path == "/api/servers":
            server = hub.add_server(
                str(body.get("host", "")), _int(body.get("port"), DEFAULT_USBIP_PORT)
            )
            return self._json({"server": server.to_dict()})

        if path == "/api/servers/remove":
            return self._json({"removed": hub.remove_server(str(body.get("host", "")))})

        if path == "/api/attach":
            attachment = hub.attach(
                host=str(body.get("host", "")),
                busid=str(body.get("busid", "")),
                port=_int(body.get("port"), DEFAULT_USBIP_PORT),
                read_only=bool(body.get("read_only", False)),
            )
            return self._json({"attachment": attachment.as_dict()})

        if path == "/api/detach":
            hub.detach(str(body.get("id", "")))
            return self._json({"ok": True})

        if path == "/api/copy":
            job = hub.copy(
                attachment_id=str(body.get("id", "")),
                sources=list(body.get("sources") or []),
                destination=str(body.get("destination", "")),
                skip_existing=bool(body.get("skip_existing", True)),
            )
            return self._json({"job": job.as_dict()})

        if path == "/api/jobs/cancel":
            job = hub.transfers.get(str(body.get("job", "")))
            if not job:
                raise HubError("no such job")
            job.cancel()
            return self._json({"ok": True})

        return self._error(HTTPStatus.NOT_FOUND, "no such endpoint")


def _one(query: dict, key: str) -> str:
    values = query.get(key) or []
    return values[0] if values else ""


def _int(value, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


class HubServer(ThreadingHTTPServer):
    """
    Threaded so a long attach does not block the page polling for progress.

    daemon_threads means a request in flight never keeps the process alive at
    shutdown, which matters because shutdown is when mounts get released.
    """

    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, hub: Hub, token: str):
        self.hub = hub
        self.token = token
        super().__init__(address, HubRequestHandler)


def serve(
    hub: Hub,
    token: str,
    bind: str = "0.0.0.0",
    port: int = DEFAULT_PORT,
) -> HubServer:
    """Create the server. The caller runs it, so it can also stop it."""
    return HubServer((bind, port), hub, token)


def access_url(bind: str, port: int, token: str) -> str:
    """
    The URL to hand the user, token included.

    Printed once at startup. The token is in the query string only for this
    first visit; the page moves it into sessionStorage and strips it from the
    address bar, so it does not linger in history or get copied into a chat
    window along with the link.
    """
    host = bind
    if bind in ("0.0.0.0", "", "::"):
        try:
            probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            probe.connect(("8.8.8.8", 80))
            host = probe.getsockname()[0]
            probe.close()
        except OSError:
            host = "127.0.0.1"
    return f"http://{host}:{port}/?token={token}"
