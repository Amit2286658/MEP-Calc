#!/usr/bin/env python3
"""Renders store/icon-512.png from the app's launcher-vector artwork.

The Android adaptive icon (drawable/ic_launcher_background.xml +
ic_launcher_foreground.xml) is a 108x108 vector. This reproduces it at 512x512
with 4x supersampling so the store listing gets a crisp, high-DPI icon instead
of an upscaled 192px mipmap (Uptodown rejects low-resolution icons).
"""
from pathlib import Path

from PIL import Image, ImageDraw

S = 512
SS = 4          # supersample factor
V = 108.0       # vector viewport
K = (S * SS) / V


def p(x, y):
    return (x * K, y * K)


def w(v):
    return max(1, int(round(v * K)))


img = Image.new("RGBA", (S * SS, S * SS), (248, 248, 248, 255))
d = ImageDraw.Draw(img)

# Card: white rounded rectangle with a soft dark outline.
d.rounded_rectangle(
    [p(29, 29), p(79, 79)],
    radius=8 * K,
    fill=(255, 255, 255, 255),
    outline=(0x33, 0x33, 0x33, 255),
    width=w(2.4),
)


def line(x1, y1, x2, y2, color, width):
    d.line([p(x1, y1), p(x2, y2)], fill=color, width=w(width), joint="curve")
    # round caps (PIL has no cap style; draw discs at both ends)
    for (cx, cy) in ((x1, y1), (x2, y2)):
        r = width / 2.0
        d.ellipse([p(cx - r, cy - r), p(cx + r, cy + r)], fill=color)


INK = (0x33, 0x33, 0x33, 255)
ACCENT = (0xE5, 0x8A, 0x7B, 255)

# +  −  ×  =
line(40.5, 44.5, 49.5, 44.5, INK, 2.6)
line(45.0, 40.0, 45.0, 49.0, INK, 2.6)
line(58.5, 44.5, 67.5, 44.5, INK, 2.6)
line(41.5, 59.5, 48.5, 66.5, INK, 2.6)
line(48.5, 59.5, 41.5, 66.5, INK, 2.6)
line(58.5, 60.5, 67.5, 60.5, ACCENT, 2.8)
line(58.5, 65.5, 67.5, 65.5, ACCENT, 2.8)

out = img.resize((S, S), Image.LANCZOS)
dest = Path(__file__).resolve().parent / "icon-512.png"
out.save(dest, "PNG", optimize=True)
print(f"wrote {dest} ({dest.stat().st_size} bytes)")
