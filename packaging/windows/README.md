# Windows packaging

Builds one standalone executable: the client. Windows is client-only; sharing
devices FROM Windows is done with
[usbipd-win](https://github.com/dorssel/usbipd-win/releases) directly, and
OpenUSBIP does not ship a Windows server. The executable contains no USB/IP
driver, and that is a deliberate limit rather than an oversight: moving USB
traffic on Windows needs kernel-mode code, kernel-mode code must be signed by a
certificate this project does not have, and shipping an unsigned driver would
mean asking users to disable driver signature enforcement. So OpenUSBIP drives
the project that already solves that properly.

| Executable | What it does | Needs installed |
|---|---|---|
| `OpenUSBIP.exe` | GUI client: discover servers, attach and detach devices | [usbip-win2](https://github.com/vadimgrn/usbip-win2/releases) |

## Build

On Windows, from anywhere:

```powershell
.\packaging\windows\build.ps1 -Clean
```

Output lands in `dist\OpenUSBIP\`. The script makes its own virtualenv in
`.venv-build\` rather than using the system interpreter, because PyInstaller
bundles whatever it finds installed and a cluttered global environment quietly
inflates the result.

## Files

| File | Purpose |
|---|---|
| `build.ps1` | Driver script: checks Python, makes the venv, runs the spec |
| `common.py` | Settings shared by the spec — hidden imports, excludes, pruning |
| `openusbip-client.spec` | GUI client, windowed, requests Administrator |
| `version_info.txt` | The version resource shown in the .exe's Properties tab |
| `make_icon.py` | Regenerates `resources/icons/openusbip.ico` from the SVG |

## Decisions worth knowing about

**One-folder, not one-file.** A one-file build unpacks itself to `%TEMP%` on
every launch. For a Qt app this size that is several seconds of startup, and it
is also the behaviour antivirus heuristics flag hardest on an unsigned binary.

**The client requests Administrator.** Attaching a device talks to the vhci
kernel driver, which needs elevation, and Windows cannot elevate a process that
is already running — so the client asks once at launch via its manifest.
Without that, every attach fails with an opaque access error.

**No UPX.** Compressed executables are among the strongest signals commodity
antivirus uses. Saving a few MB is not worth the false positives.

**The executable is unsigned**, so SmartScreen shows "Windows protected your PC" on
first run. Signing needs a code-signing certificate.

**Qt module excludes are not enough on their own.** Listing `PySide6.QtQml` in
`excludes` stops the Python module being imported but does not stop
`Qt6Qml.dll` arriving as a dependency of some other plugin. `common.prune()`
removes those from the collected file list after analysis; without it the
build carries the entire QML and Quick stack for an app made of Qt Widgets.

## What has and has not been verified

Verified by building on Linux with the same spec: the spec executes, the
hidden-import collection finds `src.*` and all of `zeroconf`, and the pruning
removes the QML stack without breaking startup. The icon is a genuine
multi-resolution `.ico` (16 through 256 px, each rendered from the vector).

**Not yet verified: a build produced on Windows, and the executable running
against a real usbip-win2.** The Windows-only parts are the icon and version
resources, the UAC manifest, and the tool discovery under `Program Files`. The
`windows` job in `.github/workflows/ci.yml` builds the client on a
`windows-latest` runner and checks that it starts, so that gap closes as soon
as CI runs against this branch.
