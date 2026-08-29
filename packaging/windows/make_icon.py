#!/usr/bin/env python3
"""
Render resources/icons/openusbip.svg to a multi-resolution Windows .ico.

Windows takes the application icon from the executable's resources, and it
picks whichever embedded size fits the context -- 16px in the taskbar's small
mode, 32px on the desktop, 256px in the large-icon view. A single-size .ico
gets rescaled by the shell and looks noticeably rough, so every size is
rendered from the vector rather than downsampled from one bitmap.

Run from the repository root:

    python3 packaging/windows/make_icon.py

Committed rather than generated at build time so the Windows build does not
need Qt or Pillow just to produce an icon.
"""

import os
import sys

# The sizes the Windows shell actually asks for.
SIZES = (16, 24, 32, 48, 64, 128, 256)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SVG = os.path.join(REPO_ROOT, "resources", "icons", "openusbip.svg")
ICO = os.path.join(REPO_ROOT, "resources", "icons", "openusbip.ico")


def render(size: int) -> bytes:
    """Rasterise the SVG at `size`x`size` and return PNG bytes."""
    from PySide6.QtCore import QBuffer, QByteArray, Qt
    from PySide6.QtGui import QImage, QPainter
    from PySide6.QtSvg import QSvgRenderer

    renderer = QSvgRenderer(SVG)
    if not renderer.isValid():
        raise SystemExit(f"could not parse {SVG}")

    image = QImage(size, size, QImage.Format_ARGB32)
    image.fill(Qt.transparent)

    painter = QPainter(image)
    painter.setRenderHint(QPainter.Antialiasing)
    renderer.render(painter)
    painter.end()

    # The QByteArray must outlive the QBuffer: passing a temporary here leaves
    # the buffer pointing at freed memory and Qt segfaults inside save().
    data = QByteArray()
    buffer = QBuffer(data)
    buffer.open(QBuffer.WriteOnly)
    image.save(buffer, "PNG")
    buffer.close()
    return bytes(data)


def main() -> None:
    # Qt needs a platform plugin even to rasterise off-screen.
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

    import io

    from PIL import Image
    from PySide6.QtGui import QGuiApplication

    # QImage and QPainter need a live application object; without one Qt
    # segfaults rather than raising.
    _app = QGuiApplication.instance() or QGuiApplication([])

    frames = [Image.open(io.BytesIO(render(size))) for size in SIZES]

    # Pillow skips any requested size larger than the base image and falls back
    # to downsampling it for the rest, so the base has to be the largest frame
    # and the others are supplied through append_images by exact size match.
    # Getting this backwards silently produces a .ico holding only a 16px icon.
    largest = frames[-1]
    largest.save(
        ICO,
        format="ICO",
        sizes=[(s, s) for s in SIZES],
        append_images=frames[:-1],
    )

    embedded = sorted(Image.open(ICO).info["sizes"])
    if embedded != sorted((s, s) for s in SIZES):
        raise SystemExit(f"expected {SIZES}, got {embedded}")
    print(f"wrote {ICO} ({os.path.getsize(ICO)} bytes, sizes: {list(SIZES)})")


if __name__ == "__main__":
    sys.exit(main())
