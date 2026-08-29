#!/usr/bin/env python3
"""
OpenUSBIP Hub — a USB/IP client with a web interface, for headless machines.

Built for boxes you administer through a browser rather than a desktop:
Proxmox, Unraid, a NAS. Attach a USB device shared by another machine (a
phone running the OpenUSBIP Android app, a Raspberry Pi, a Windows PC), and
copy files off it — without a GUI toolkit, an X server, or a display.

    openusbip_hub.py                 # listen on all interfaces, port 8765
    openusbip_hub.py --bind 127.0.0.1
    openusbip_hub.py --port 9000 --print-token

It prints a URL containing an access token on first start. That token is the
only thing standing between this service and anyone who can reach the port,
because attaching devices and writing files needs root.
"""

import argparse
import os
import signal
import sys
import threading

_ROOT = os.path.dirname(os.path.abspath(__file__))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        prog="openusbip_hub",
        description="USB/IP client with a web interface, for headless servers.",
    )
    parser.add_argument(
        "--bind",
        default="0.0.0.0",
        help="Address to listen on (default: 0.0.0.0, every interface).",
    )
    parser.add_argument(
        "--port", type=int, default=8765, help="TCP port to listen on (default: 8765)."
    )
    parser.add_argument(
        "--token-file",
        default=None,
        help="Where the access token is stored (default: /etc/openusbip/hub.token).",
    )
    parser.add_argument(
        "--print-token",
        action="store_true",
        help="Print the access URL and exit, for recovering a lost link.",
    )
    parser.add_argument(
        "--allow-non-root",
        action="store_true",
        help="Start without root. Attaching and mounting will fail; browsing "
             "an already-mounted device still works.",
    )
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)

    from src.utils.logger import setup_logger
    from src.web import auth
    from src.web.hub import Hub
    from src.web.server import access_url, serve

    logger = setup_logger("OpenUSBIP-Hub")

    token, created = auth.load_or_create_token(args.token_file)
    url = access_url(args.bind, args.port, token)

    if args.print_token:
        print(url)
        return 0

    # Attaching talks to the vhci driver and mounting talks to the kernel;
    # neither is possible unprivileged. Saying so now beats every button in the
    # UI failing with a permission error later.
    if os.geteuid() != 0 and not args.allow_non_root:
        print(
            "ERROR: the hub needs root to attach USB devices and mount them.\n"
            "       Run it with sudo, or pass --allow-non-root to start anyway\n"
            "       (browsing works; attaching and mounting will not).",
            file=sys.stderr,
        )
        return 1

    hub = Hub()
    hub.start()
    httpd = serve(hub, token, args.bind, args.port)

    stopping = threading.Event()

    def shutdown(signum, _frame):
        # Idempotent: systemd sends SIGTERM and an impatient user sends
        # SIGINT, and doing this twice concurrently deadlocks server_close.
        if stopping.is_set():
            return
        stopping.set()
        logger.info(f"signal {signum}; shutting down")
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    print("OpenUSBIP Hub")
    print("=" * 60)
    print(f"  Listening on : {args.bind}:{args.port}")
    print("  Mounts under : /run/openusbip")
    if created:
        print("\n  A new access token was generated.")
    print(f"\n  Open this link:\n\n    {url}\n")
    if args.bind in ("0.0.0.0", "", "::"):
        print("  Reachable from anywhere on your network. The token is the only")
        print("  thing protecting it, so do not forward this port to the internet.")
    print("=" * 60, flush=True)

    try:
        httpd.serve_forever()
    finally:
        # Releasing mounts matters more than a tidy exit: one left behind keeps
        # the vhci device busy and the next start cannot reuse it.
        hub.stop()
        httpd.server_close()
        logger.info("stopped")

    return 0


if __name__ == "__main__":
    sys.exit(main())
