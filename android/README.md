# OpenUSBIP Server for Android

Shares USB devices plugged into an Android phone (via OTG) with computers on
the network, speaking the standard USB/IP protocol. A Linux client attaches
them with plain `usbip attach` -- no special client software.

## Why a server and not a client

An Android app **cannot** be a USB/IP client. Attaching a remote device
requires the kernel to present a virtual USB device, which needs the
`vhci_hcd` module. Stock Android kernels do not build it and loading one needs
root plus a matching custom kernel.

The server direction has no such problem: Android's `UsbManager` grants a
normal app direct access to USB peripherals, so the whole thing runs in
userspace with no root.

## Known limitations

- **No isochronous transfers.** Android's USB API has no isochronous support,
  so USB audio interfaces and webcams cannot be shared. Mass storage, HID,
  serial adapters and printers are fine. Isochronous URBs are detected and
  refused rather than silently mishandled.
- **Needs USB host (OTG) support**, which the app reports on its main screen.
- **Speed is reported as high-speed.** Android exposes no device speed before
  API 35; the value is advisory and clients use it only for display.
- **busid is synthesised** as `busnum-devnum` parsed out of the kernel path in
  `getDeviceName()`, because Android exposes no USB topology.

## Layout

| Path | Contents |
|---|---|
| `app/src/main/kotlin/.../protocol/` | USB/IP wire format. **No Android imports** -- runs as plain JVM tests. |
| `app/src/main/kotlin/.../usb/` | Android USB enumeration and the framework-free mapping to wire structs. |
| `app/src/test/kotlin/` | Unit tests, runnable on a desktop with no device attached. |

Keeping the protocol free of Android types is deliberate: a wrong byte offset
does not throw, it silently corrupts transfers and surfaces as unexplained USB
errors on the client. It is far cheaper to catch that in a JVM test.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk

./gradlew :app:testDebugUnitTest     # protocol tests, no device needed
./gradlew :app:assembleDebug         # -> app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17, Android SDK platform 34 and build-tools 34.0.0.
`local.properties` (holding `sdk.dir`) is generated locally and not committed.

## Status

**Verified end to end on real hardware (2026-08-27.)** A Linux desktop attached
the phone's synthesised disk, formatted it, wrote to it and read it back:

```
$ sudo usbip attach -r 192.168.0.146 -b 99-1
$ usbip port
Port 00: <Port in Use> at High Speed(480Mbps)
       7-1 -> usbip://192.168.0.146:3240/99-1

$ lsblk
sdc   2G  disk  Virtual Disk  usb          <- does not physically exist

$ sudo mkfs.vfat -F 32 -n PHONEDISK /dev/sdc
$ udisksctl mount -b /dev/sdc               -> /media/andy/PHONEDISK
```

An 8 MB random file survived write, unmount, remount and re-read with a
matching md5, at roughly 2.3 MB/s. Capacity is exact: 4194304 sectors of 512
bytes, no off-by-one. Afterwards the phone's image file contained the FAT32
label `PHONEDISK`, using 12 MB of blocks for a nominally 2 GB sparse file. 854
URBs served, zero errors. The project's own Python GUI client lists the volume
too, so both clients work against it.

Working: protocol codec, TCP server and URB pump, device registry with
one-client-per-device exclusivity, SCSI target, Bulk-Only Transport,
synthesised mass-storage device, foreground service, control-panel UI.

### Not written yet

- **Passthrough backend for real OTG devices.** `usb/UsbHost.kt` enumerates
  them and `DeviceMapping` converts them to wire structs, but nothing forwards
  URBs to `UsbDeviceConnection` yet, so the UI lists them as "passthrough not
  implemented". This is the main remaining feature.
- Per-device share toggles; the virtual disk is currently offered
  automatically whenever sharing starts.
- Choosing the image size, or backing the export with a different file.
- Nothing formats the image, so a fresh one arrives unpartitioned and the
  client has to `mkfs` it. Worth doing on the phone side eventually.

### Notes for picking this up

- `MassStorageBackend.DEBUG` is `true`, logging every URB with endpoint,
  direction, length, setup packet and result. That trace is what located the
  `actual_length` bug, so it stays for now -- but turn it off before real use;
  it is noisy and logs transfer metadata.
  Watch it with `adb logcat -s UsbipMsc:V`.
- `MainActivity` honours `--ez autostart true` / `--ez autostop true` so the
  export can be driven from adb without unlocking the phone. Gated on
  `BuildConfig.DEBUG` on purpose: the activity is exported, so in a release
  build that extra would let any installed app start sharing storage over the
  network.
- The server binds all interfaces, and USB/IP has no authentication or
  encryption. Restricting it to one interface is a constructor argument on
  `UsbipServer`, not a rewrite.
- Reaching the phone over Tailscale does not work yet: the tailnet ACL is a
  port allowlist and 3240 is not on it. Same blocker as the Raspberry Pi
  server.
- `android/local.properties` holds `sdk.dir` and is gitignored. Recreate it on
  a fresh clone or the build fails.
