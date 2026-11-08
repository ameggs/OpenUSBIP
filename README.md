# OpenUSBIP

**USB over IP.** Use a USB device that is plugged into another machine as though
it were plugged into yours — a free, cross-platform alternative to VirtualHere.

Three pieces, which can be mixed freely because they all speak the standard
USB/IP protocol:

| Piece | What it is | Runs on |
|---|---|---|
| **Desktop client** | Qt6 GUI: find servers, attach and detach devices | Linux, Windows |
| **Hub** | Web UI client for machines with no desktop | Proxmox, Debian, any headless Linux |
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

### Windows

```powershell
.\packaging\windows\build.ps1 -Clean
```

Produces `dist\OpenUSBIP\OpenUSBIP.exe` (the GUI client). It contains no
USB/IP driver — that has to be signed, and this project ships none — so
install [usbip-win2](https://github.com/vadimgrn/usbip-win2/releases) as well.

Sharing devices FROM a Windows machine is done with
[usbipd-win](https://github.com/dorssel/usbipd-win/releases) directly, which is
not part of this project; OpenUSBIP does not ship a Windows server. See
[`packaging/windows/README.md`](packaging/windows/README.md).

### Use devices from a headless server (Proxmox, a NAS, any Debian box)

```bash
git clone https://github.com/ameggs/OpenUSBIP.git
cd OpenUSBIP
sudo ./install-hub.sh
```

Then open the link it prints. The hub is a client with a web interface rather
than a Qt window, because the machines that most want one — a hypervisor, a
NAS — are administered through a browser and have no desktop to run a GUI on.

Attach a device shared by another machine, and it is mounted and browsable:
tick the files you want and give a destination on local storage. The typical
use is pulling photos off a phone running the Android app straight onto the
server's array.

The installer only adds packages, `/opt/openusbip`, a systemd unit and
`/etc/openusbip`. It touches no firewall, no network and no storage, restarts
nothing already running, and **leaves the service disabled** so a reboot does
not silently start a network service you have not decided to keep. Undo it all
with `./install-hub.sh --uninstall`.

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

### Hub (`openusbip_hub.py`, `src/web/`, `install-hub.sh`)

A USB/IP client driven from a browser, for machines with no desktop.

- **Attach, and it is ready to use.** `usbip attach` reports success without
  saying what appeared, so the hub reads sysfs to work out whether you got a
  mountable disk or an MTP phone and which `/dev/sd*` it is, waits for it to
  settle, and mounts it.
- **Browse and copy** with progress, resumable — a second run skips files
  already at the destination, so pulling a phone's camera roll repeatedly is
  cheap.
- **Standard library only.** No pip. Debian marks its interpreter externally
  managed and Unraid rebuilds its root filesystem from RAM on every boot, so a
  dependency tree is a liability on exactly the machines this targets.
  Everything comes from apt.

It runs as root — attaching talks to the vhci driver and mounting talks to the
kernel — often on a box with live guest storage, so the destructive directions
are fenced structurally rather than by care:

- unmount refuses any path outside `/run/openusbip`, judged on *resolved*
  paths, so neither a symlink nor `..` nor a lookalike sibling directory gets
  past it
- browsing and copying resolve every path the client sends inside the mount and
  reject anything that escapes
- detach refuses while a copy to that device is running
- mounts are `nosuid,nodev` — the contents arrive from another machine over an
  unauthenticated protocol

Access is a token generated on first run, sent as a request header and never as
a cookie. That is the whole CSRF defence: a page on another origin cannot set a
custom header without a preflight, so nothing else open in your browser can
drive a service that attaches devices and writes files as root.

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

- **Python** 3.10+ and **PySide6** for the desktop client (the server and the
  hub are headless and need no Qt)
- **For the hub**: `usbip`, plus `jmtpfs` to browse phones over MTP and
  `python3-zeroconf` to find servers automatically. All from apt; the hub
  itself uses only the standard library
- **USB/IP tools**
  - Debian/Raspberry Pi OS: `sudo apt install usbip`
  - Ubuntu/Zorin: `sudo apt install linux-tools-common` (there is no `usbip`
    package there; the binary ships with linux-tools)
  - Windows client: [usbip-win2](https://github.com/vadimgrn/usbip-win2)
    (ships the vhci driver Windows needs to attach anything)
  - usbip-win2 does not add itself to `PATH`; OpenUSBIP searches Program Files
    for it
  - Windows is client-only; sharing from Windows is
    [usbipd-win](https://github.com/dorssel/usbipd-win)'s job, not this
    project's
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

- **The hub is a root-privileged web service.** It can attach USB devices and
  write files anywhere on the machine. It is protected by a single generated
  token, stored `0600` in `/etc/openusbip/hub.token`, and it binds every
  interface by default so you can reach it from another machine. Bind it to
  `127.0.0.1` and use an SSH tunnel if that is not what you want, and never
  port-forward 8765.

**Use it on a trusted LAN or across a VPN (WireGuard, Tailscale). Do not
port-forward 3240 or 8765.**

---

## Known issues

- **OTG passthrough is experimental and currently parked.** The protocol side
  is proven — a real SanDisk drive completed full enumeration, `INQUIRY`,
  `READ CAPACITY` and 512-byte sector reads through the phone — but it is not
  dependable. Bulk transfers fail at Android's own API (`bulkTransfer` returns
  -1) depending on the hub and power arrangement, and behaviour changed between
  a powered and unpowered hub with the same drive. The untested variable is the
  drive connected straight to the phone with no hub in between.

  **Use MTP for phone files.** It is reliable, needs no adapters or hubs, and
  is what a phone speaks over a cable anyway. Passthrough is only worth
  pursuing for non-storage devices.
- **No isochronous transfers on Android.** `UsbDeviceConnection` offers control,
  bulk and interrupt only, so USB audio and webcams cannot be shared from a
  phone. Storage, HID, serial and printers are fine.
- **One client per device.** This is USB, not a limitation here: a USB device
  has exactly one host. A device in use disappears from other clients' lists
  rather than showing as busy, which matches `usbipd`.
- **Tailscale needs an ACL change.** Tailnet policies are port allowlists and
  3240 is not on one by default, so remote access is refused until it is added.
- **The hub's MTP path is not yet hardware-tested.** Attaching, mounting a
  block device, browsing and copying are all verified on real hardware, and the
  MTP code has unit tests, but the end-to-end "browse a phone over MTP from a
  hypervisor" path has not been run against a phone yet. The block-device path
  it shares is proven.
- **Unraid is not yet packaged or tested.** On Unraid 6.12.98-Unraid the
  kernel ships `vhci_hcd` and `usbip_host` (both loaded), and `/usr/sbin/usbip`
  and `/usr/sbin/usbipd` exist, so a USB/IP client can run there. OpenUSBIP
  itself has not been packaged or tested on Unraid.
- **The Windows executable has not yet been built on Windows.** The spec and
  build script are written and were validated by building with the same spec
  on Linux, but
  the Windows-only parts (icon and version resources, the UAC manifest, the
  Program Files search against a real usbip-win2 install) are so far covered
  only by unit tests and the `windows` CI job. Treat them as untested on
  hardware until that job has run.

---

## Development

```bash
# Python
pip install -r requirements-dev.txt
QT_QPA_PLATFORM=offscreen pytest        # 255 tests
ruff check .

# Android
cd android
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:testDebugUnitTest        # 184 tests, no device needed
./gradlew :app:assembleDebug
```

CI runs the Python suite on 3.10 and 3.12, lints, byte-compiles the tree and
syntax-checks the installers. A separate Windows job runs the same suite on a
`windows-latest` runner — where the Program Files search and the elevation
handling actually execute — parse-checks every PowerShell script, builds both
executables with PyInstaller and smoke-tests the server one.

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
it — exFAT in particular has no journal to recover from. The hub does this for
you and refuses to detach while a copy is running.

**Lost the hub's link** — `python3 /opt/openusbip/openusbip_hub.py --print-token`
prints it again. The token does not change unless the file is deleted or found
to be readable by others, in which case a new one is generated on purpose.

**The hub says a device attached but reported no media** — that is a card
reader or drive bay with nothing in it. The device is genuinely attached and
visible as a `/dev/sd*` node; there is just no medium behind it.

**The hub finds no servers** — mDNS may not cross your network segment, which
is common between VLANs and over a VPN. Add the server by address; nothing else
depends on discovery. On a machine without `python3-zeroconf` the hub logs a
warning at startup and carries on with manual servers only.

---

## License

GPL-3.0 — see [LICENSE](LICENSE) for the full text and [NOTICE](NOTICE) for the
project's copyright notice.
