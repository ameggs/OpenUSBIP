"""
Access control for the hub's web interface.

The threat this addresses is specific. The hub runs as root on a hypervisor and
can attach USB devices and write files, and it listens on the LAN because the
whole point is to reach it from a browser on another machine. So it needs a
secret, and the secret needs to survive a restart without the user having to
manage it.

The token is sent as a **request header**, never a cookie. That is the entire
CSRF defence: a browser attaches cookies to cross-site requests automatically,
but a page on another origin cannot set a custom header on a request to this
one without a CORS preflight, which is refused. There is therefore no way for
a random page the user happens to have open to drive this service.
"""

from __future__ import annotations

import hmac
import os
import secrets
import stat

from src.utils.logger import setup_logger

logger = setup_logger(__name__)

#: Where the token lives. Under /etc when running as root (the normal case,
#: since attaching needs root), otherwise in the user's config directory.
SYSTEM_CONFIG_DIR = "/etc/openusbip"
TOKEN_FILENAME = "hub.token"

HEADER_NAME = "X-Auth-Token"


def default_token_path() -> str:
    if os.geteuid() == 0:
        return os.path.join(SYSTEM_CONFIG_DIR, TOKEN_FILENAME)
    base = os.environ.get(
        "XDG_CONFIG_HOME", os.path.join(os.path.expanduser("~"), ".config")
    )
    return os.path.join(base, "openusbip", TOKEN_FILENAME)


def load_or_create_token(path: str | None = None) -> tuple[str, bool]:
    """
    Read the token, generating one on first run.

    Returns (token, was_created). A token file with loose permissions is
    replaced rather than trusted: on a shared machine, a world-readable secret
    is no secret, and silently carrying on would leave the user believing the
    service is protected.
    """
    path = path or default_token_path()

    if os.path.exists(path):
        mode = stat.S_IMODE(os.stat(path).st_mode)
        if mode & 0o077:
            logger.warning(
                f"{path} is readable by others (mode {mode:o}); generating a new token"
            )
        else:
            token = _read(path)
            if token:
                return token, False

    token = secrets.token_urlsafe(32)
    _write(path, token)
    logger.info(f"generated a new access token at {path}")
    return token, True


def _read(path: str) -> str:
    try:
        with open(path, encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError as exc:
        logger.error(f"could not read {path}: {exc}")
        return ""


def _write(path: str, token: str) -> None:
    """
    Write the token, never exposing it even briefly.

    Via a fresh temp file rather than by opening `path` directly, for two
    reasons that both bite:

    * O_CREAT's mode argument applies only when the file is *created*. Opening
      an existing world-readable token file with mode 0o600 leaves it
      world-readable -- so replacing an exposed secret would produce another
      exposed secret.
    * os.replace is atomic, so a reader never sees a half-written token.
    """
    directory = os.path.dirname(path)
    os.makedirs(directory, exist_ok=True)
    os.chmod(directory, 0o700)

    temporary = f"{path}.new.{os.getpid()}"
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(token + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def token_matches(supplied: str | None, expected: str) -> bool:
    """
    Constant-time comparison.

    A plain `==` on a secret leaks its length and, in principle, its prefix
    through timing. The cost of avoiding that is one function call.
    """
    if not supplied or not expected:
        return False
    return hmac.compare_digest(supplied, expected)
