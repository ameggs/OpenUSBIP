# OpenUSBIP Testing

## Automated tests

```bash
pip install -r requirements-dev.txt
QT_QPA_PLATFORM=offscreen pytest
```

CI runs this on Python 3.10 and 3.12, plus `ruff check .`, a byte-compile of
the whole tree, and a syntax check of the installers.

### What is covered

`tests/test_usbip_wrapper.py` pins the output parsers against captured `usbip`
output. These are the most fragile part of the project — they consume the
free-form text of whatever `usbip` build a distro ships — and they are pure
functions over strings, so they are cheap to test.

| Area | Covered |
|---|---|
| `usbip list -l` parsing | busid, vendor/product IDs, description, empty output |
| `usbip list -r` parsing | device rows, ignoring `/sys/devices` continuation lines |
| `usbip port` parsing | port number, remote host, busid, no-devices case |
| `--tcp-port` handling | omitted at 3240, passed as a global flag before the subcommand |
| Failure paths | error text propagated, missing binary reported not crashed |

`tests/test_entrypoints.py` covers import health:

| Area | Covered |
|---|---|
| Every core module imports | regression guard |
| Both entry points import | regression guard |
| `openusbip_server.py --help` runs as a subprocess | the original crash |
| Only one canonical module identity | `src.core.X` vs `core.X` duplication |
| No imports reach above the package | the `from ..utils` style that caused it |

### Not covered by automated tests

The GUI widgets are exercised only by construction, not by simulated
interaction. `pytest-qt` is available in the dev requirements if you want to
add widget-level tests.

Nothing here talks to a real USB device or a real remote server — every usbip
invocation is stubbed. End-to-end behaviour still needs the manual pass below.

## Manual test checklist

Unchecked means unverified. Do not tick an item because the code looks right;
the tray Quit bug survived precisely because "the method exists" was treated as
equivalent to "the feature works".

### System tray

- [ ] Tray icon appears, with the custom icon and correct tooltip
- [ ] Single and double click both restore the window
- [ ] Closing the window minimises to tray and shows a notification
- [ ] Tray menu lists discovered servers, each with IP/port and devices
- [ ] Clicking a device in the tray attaches it, with a notification
- [ ] Attached devices appear under "Attached Devices" and can be detached
- [ ] Error notifications appear on attach/detach failure
- [ ] **Quit from the tray actually exits the process** (was broken; fixed and
      covered by an automated check, but confirm on a real desktop)
- [ ] File → Exit also actually exits

### Servers and devices

- [ ] Servers are discovered over mDNS
- [ ] A manually added server persists across a restart
- [ ] A server on a non-default port can be listed and attached from
- [ ] Window size and position are restored on next launch
- [ ] Removing a server removes it permanently

### Responsiveness

- [ ] Selecting an unreachable server does not freeze the UI
      (previously a 15 second hard freeze)
- [ ] The UI stays interactive while devices load
- [ ] Selection is not lost when the 5 second refresh fires

### Edge cases

- [ ] Works when no servers are found
- [ ] Works when a server has no devices
- [ ] Handles a server disconnecting mid-session
- [ ] Handles a device being unplugged while attached
- [ ] Sensible error when `vhci_hcd` is not loaded
- [ ] Sensible error when passwordless sudo is not configured

### Server host

- [ ] `openusbip_server.py` runs from a terminal
- [ ] `openusbip_server.py --daemon` runs without prompting
- [ ] `systemctl status openusbip-server` is active, not restart-looping
- [ ] `systemctl status usbipd` is active
- [ ] The server survives a reboot
