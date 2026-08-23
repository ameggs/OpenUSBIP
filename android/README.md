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

Working: protocol encode/decode, USB enumeration and mapping, main screen
reporting host-mode support, listen address and attached devices.

Not written yet: the TCP server and URB pump (`net/`), per-device share
toggles, one-client-per-device exclusivity, and the foreground service that
keeps the export alive. The service is deliberately absent from the manifest
until it exists.
