# OpenUSBIP

**USB over IP.** Use a USB device that is plugged into another machine as though
it were plugged into yours — a free, cross-platform alternative to VirtualHere.

Three pieces, which can be mixed freely because they all speak the standard
USB/IP protocol:

| Piece | What it is | Runs on |
|---|---|---|
| **Desktop client** | Qt6 GUI: find servers, attach and detach devices | Linux, Windows |
| **Server** | Shares locally-plugged USB devices | Linux, Raspberry Pi |
| **Android app** | Shares the phone's *files* and its OTG devices | Android 7+ |

Any of them interoperates with stock `usbip` from the Linux kernel tools, so a
plain `usbip attach` works against all three and the GUI client works against
any USB/IP server.

> ⚠️ **Read [Security](#security) before putting this on a network.** USB/IP has
> no authentication and no encryption.

---

## Quick start

### Share devices from a Linux box or Raspberry Pi

```bash
git clone https://github.com/ameggs/OpenUSBIP.git
cd OpenUSBIP
./install-server.sh
```

Installs `usbipd`, loads the kernel modules, and sets up two systemd units
(`usbipd.service`, `openusbip-server.service`). The server advertises itself
over mDNS, so clients find it without being told an address.

### Use devices from a desktop

```bash
git clone https://github.com/ameggs/OpenUSBIP.git
cd OpenUSBIP
./install-client.sh
```

Then launch **OpenUSBIP Client** from your application menu. Servers appear
automatically; click one to see its devices, and click Attach.

The installer must run from a checkout — it wires the launcher to the
repository directory — and it configures a narrowly scoped passwordless sudo
rule for the `usbip` binary, which is what lets the GUI attach without
prompting.

### Share from an Android phone

See [`android/README.md`](android/README.md). Build with
`./gradlew :app:assembleDebug` and sideload; the app then offers its files over
MTP and its OTG devices over USB/IP.

---

## What each part does

### Desktop client (`src/`)

Python and PySide6.

- **Auto-discovery** of servers over mDNS, plus manual entry by address for
  networks where multicast does not carry (a VPN, for instance).
- **Attach and detach** with the device list refreshing as things change.
- **System tray** with quick attach/detach, so it can sit in the background.
- **Persistent settings** — manually added servers and window geometry survive
  a restart.
- All blocking work runs on a thread pool, so an unreachable server never
  freezes the UI.

### Server (`openusbip_server.py`, `install-server.sh`)

- Wraps `usbipd`, advertises over mDNS, and reports what is shared.
- `--daemon` for systemd, `--port` for a non-default port.
- The installer binds attached devices for sharing and sets the services to
  start at boot.

### Android app (`android/`)

Three things in one, all reachable over standard USB/IP:

1. **MTP** — browse the phone's real internal storage from a file manager,
   exactly as a USB cable would, but over the network. This is the one to
   reach for: it needs no extra hardware and it is what a phone actually
   speaks over a cable.
2. **A virtual USB disk** — a file on the phone exported as a genuine block
   device, which the client can format and mount. Useful when something needs
   a *disk* rather than files.
3. **OTG passthrough** — a device plugged into the phone, forwarded on. See
   the [known issues](#known-issues).

Written in Kotlin with the protocol layers kept free of Android imports so
they run as plain JVM tests. 184 tests, none needing a phone.

---

## Requirements

- **Python** 3.10+ and **PySide6** for the desktop client (server is headless)
- **USB/IP tools**
  - Debian/Raspberry Pi OS: `sudo apt install usbip`
  - Ubuntu/Zorin: `sudo apt install linux-tools-common` (there is no `usbip`
    package there; the binary ships with linux-tools)
  - Windows: [usbipd-win](https://github.com/dorssel/usbipd-win)
- **Android**: 7.0+, USB host mode for passthrough, JDK 17 and SDK 34 to build

---

## Security

USB/IP is a plaintext, unauthenticated protocol. OpenUSBIP does not change
that, so plan around it:

- **No authentication.** Anyone who can reach port 3240 can enumerate and
  attach the shared devices. No password, no allowlist, no pairing.
- **No encryption.** Everything crosses the network in the clear — keystrokes,
  file contents, all of it.
- **Servers advertise themselves.** mDNS announces presence to the whole local
  network by design.
- **Attaching is a trust decision.** An attached device can claim to be any USB
  class, including a keyboard, so a hostile server can inject input. Only
  attach from servers you control.
- **The sudo rule is scoped but real.** The installer grants passwordless sudo
  for the `usbip` binary alone, not blanket `NOPASSWD: ALL`. Combined with the
  point above, any local process that can run `usbip` can attach a remote
  device.
- **The Android app shares your files.** With All files access granted, the MTP
  device exposes your whole internal storage to anyone who can reach the phone
  on port 3240.

**Use it on a trusted LAN or across a VPN (WireGuard, Tailscale). Do not
port-forward 3240.**

---

## Known issues

- **OTG passthrough is unfinished.** The protocol side is proven — a real
  SanDisk drive completed full enumeration, `INQUIRY`, `READ CAPACITY` and
  512-byte sector reads through the phone. But bulk transfers fail
  intermittently at Android's own API (`bulkTransfer` returns -1) depending on
  the hub and power arrangement, and the drive can drop off the phone's USB bus
  entirely when powered from the battery. Under investigation; MTP is the
  reliable route for files.
- **No isochronous transfers on Android.** `UsbDeviceConnection` offers control,
  bulk and interrupt only, so USB audio and webcams cannot be shared from a
  phone. Storage, HID, serial and printers are fine.
- **One client per device.** This is USB, not a limitation here: a USB device
  has exactly one host. A device in use disappears from other clients' lists
  rather than showing as busy, which matches `usbipd`.
- **Tailscale needs an ACL change.** Tailnet policies are port allowlists and
  3240 is not on one by default, so remote access is refused until it is added.

---

## Development

```bash
# Python
pip install -r requirements-dev.txt
QT_QPA_PLATFORM=offscreen pytest        # 40 tests
ruff check .

# Android
cd android
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:testDebugUnitTest        # 184 tests, no device needed
./gradlew :app:assembleDebug
```

CI runs the Python suite on 3.10 and 3.12, lints, byte-compiles the tree and
syntax-checks the installers.

**A note on how this codebase is tested.** The protocol layers — USB/IP wire
format, SCSI, Bulk-Only Transport, PTP/MTP — are deliberately free of platform
imports so they run as plain unit tests on a desktop. That matters because a
wrong byte offset does not throw; it silently corrupts transfers and surfaces
as unexplained USB errors on someone else's machine. Several real bugs were
caught exactly that way, and the ones that were not are noted in the commit
history with what it took to find them.

---

## Troubleshooting

**`usbip: error: open vhci_driver`** — the client module is missing:
`sudo modprobe vhci_hcd`.

**A server does not appear** — mDNS may not cross your network segment. Add it
manually by IP; non-default ports are supported.

**"Root privileges are required"** — the passwordless sudo rule is missing. Run
`./install-client.sh`, or add it by hand and check it with
`sudo visudo -c -f /etc/sudoers.d/usbip`.

**A device attaches but shows 0 bytes / "no medium"** — almost always power,
not software. A drive that browns out enumerates and then stops answering. Use
a powered hub, or a better supply for a Raspberry Pi; `dmesg` on the server
will show undervoltage events.

**Unmount before detaching.** Detaching a mounted filesystem is how you corrupt
it — exFAT in particular has no journal to recover from.

---

## License

GPL-3.0 — see [LICENSE](LICENSE) for the full text and [NOTICE](NOTICE) for the
project's copyright notice.
