#!/usr/bin/env python3
# Copyright 2026 MyDog Contributors — Apache-2.0
"""
Regenerate the MyDog installer / application icons.

Pure-stdlib renderer (no Pillow): draws a white dog silhouette on a rounded
blue-square background into a 1024x1024 master PNG, then produces the
per-platform artifacts next to this script:

    mydog.png        1024x1024 master (also the reference for every size)
    mydog.icns       macOS icon bundle        (jpackage --icon, dmg/pkg)
    mydog.ico        Windows icon resource    (jpackage --icon, msi)
    mydog_512.png    512x512 Linux icon       (jpackage --icon, deb shortcut)

Scaling / .iconset / .icns use macOS-provided `sips` and `iconutil`, so this
script must be run on macOS (which is also the only machine that authors the
dmg locally; the .icns/.ico/.png are committed and CI never regenerates them).

Usage:  python3 src/main/packaging/make_icons.py
"""

import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
SIZE = 1024            # master resolution
SS = 2                 # supersample factor for antialiasing

# Background: rounded square, vertical gradient (sRGB, top -> bottom).
BG_MARGIN = 48
BG_RADIUS = 176
TOP_COLOR = (76, 141, 255)     # #4C8DFF
BOTTOM_COLOR = (29, 78, 216)   # #1D4ED8

# Dog silhouette: shapes are (kind, params, color).  Coordinates are in the
# 1024 grid, y down, dog facing left.  TRANSLATE recenters the composition.
TRANSLATE = (-13, 45)

def _t(x, y):
    return (x + TRANSLATE[0], y + TRANSLATE[1])

# kind "e": ellipse cx cy rx ry | "c": circle cx cy r | "p": polygon points
# | "r": rounded rect x0 y0 x1 y1 radius | punch shapes use the background
# gradient color at their own position ("bg").
DOG_SHAPES = [
    ("e", _t(585, 540) + (205, 118), "dog"),   # body
    ("c", _t(745, 560) + (105,), "dog"),        # haunch
    ("p", [_t(755, 505), _t(820, 420), _t(880, 350),
           _t(915, 372), _t(860, 455), _t(795, 545)], "dog"),   # tail
    ("r", _t(430, 600) + (482, 745) + (26,), "dog"),   # front near leg
    ("r", _t(520, 600) + (572, 745) + (26,), "dog"),   # front far leg
    ("r", _t(690, 600) + (742, 745) + (26,), "dog"),   # hind near leg
    ("r", _t(770, 600) + (822, 745) + (26,), "dog"),   # hind far leg
    ("c", _t(350, 400) + (150,), "dog"),        # head
    ("e", _t(235, 465) + (100, 65), "dog"),     # muzzle
    ("p", [_t(295, 315), _t(350, 165), _t(445, 310)], "dog"),   # ear
]
PUNCH_SHAPES = [
    ("c", _t(340, 385) + (22,), "bg"),   # eye
    ("c", _t(160, 455) + (18,), "bg"),   # nose
]


def bg_color(y):
    """Gradient color (pre-multiplied ignores alpha) at canvas row y."""
    t = min(max(y, 0.0), SIZE - 1.0) / (SIZE - 1)
    return tuple(int(round(a + (b - a) * t)) for a, b in zip(TOP_COLOR, BOTTOM_COLOR))


def rounded_rect_alpha(x, y):
    """0/1 with a 1px soft edge (in supersample units, edge width 2 -> ~1px)."""
    x0, y0, x1, y1 = BG_MARGIN, BG_MARGIN, SIZE - BG_MARGIN, SIZE - BG_MARGIN
    r = BG_RADIUS
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 if (x < x0 + r or x > x1 - r or
                                                   y < y0 + r or y > y1 - r) else -1
    if 0 <= d < r - 1.5:
        return 1.0
    if r - 1.5 <= d < r + 1.5:
        return (r + 1.5 - d) / 3.0
    # straight edges
    pad = 1.5
    if x < x0 - pad or x > x1 + pad or y < y0 - pad or y > y1 + pad:
        return 0.0
    if x0 <= x <= x1 and y0 <= y <= y1:
        # inside the straight sides; corner region handled above via d
        corner_zone = (x < x0 + r or x > x1 - r) and (y < y0 + r or y > y1 - r)
        if not corner_zone:
            return 1.0
        cx = min(max(x, x0 + r), x1 - r)
        cy = min(max(y, y0 + r), y1 - r)
        d2 = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
        return 1.0 if d2 < r - 1.5 else ((r + 1.5 - d2) / 3.0 if d2 < r + 1.5 else 0.0)
    return 0.0


def in_shape(kind, params, x, y):
    if kind == "c":
        cx, cy, r = params
        return (x - cx) ** 2 + (y - cy) ** 2 <= r * r
    if kind == "e":
        cx, cy, rx, ry = params
        return ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1.0
    if kind == "r":
        x0, y0, x1, y1, rad = params
        if not (x0 <= x <= x1 and y0 <= y <= y1):
            return False
        # exact rounded-rect test: distance to the clamped inner core
        cx = min(max(x, x0 + rad), x1 - rad)
        cy = min(max(y, y0 + rad), y1 - rad)
        return (x - cx) ** 2 + (y - cy) ** 2 <= rad * rad
    if kind == "p":
        pts = params
        inside = False
        n = len(pts)
        j = n - 1
        for i in range(n):
            xi, yi = pts[i]
            xj, yj = pts[j]
            if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
                inside = not inside
            j = i
        return inside
    raise ValueError(kind)


def dog_alpha(x, y):
    """1.0 where any dog shape covers, minus punched holes."""
    for kind, params, _ in DOG_SHAPES:
        if in_shape(kind, params, x, y):
            for k2, p2, _ in PUNCH_SHAPES:
                if in_shape(k2, p2, x, y):
                    return 0.0
            return 1.0
    return 0.0


def render_master(path):
    w = SIZE * SS
    rows = []
    for py in range(w):
        row = bytearray(w * 4)
        sy = py / SS + 0.5 / SS - 0.5  # canvas coord of subpixel center
        for px in range(w):
            sx = px / SS + 0.5 / SS - 0.5
            a_bg = rounded_rect_alpha(sx, sy)
            a_dog = dog_alpha(sx, sy)
            # blend: dog white over gradient bg over transparent
            br, bg_, bb = bg_color(sy)
            r = int(br + (255 - br) * a_dog)
            g = int(bg_ + (255 - bg_) * a_dog)
            b = int(bb + (255 - bb) * a_dog)
            a = int(255 * a_bg)
            o = px * 4
            row[o:o + 4] = bytes((r, g, b, a))
        rows.append(row)
    # box-downsample SSxSS -> 1024
    out = bytearray()
    for py in range(0, w, SS):
        row_a = rows[py]
        row_b = rows[py + 1] if py + 1 < w else None
        line = bytearray()
        for px in range(0, w, SS):
            for c in range(4):
                i = px * 4 + c
                s = row_a[i] + (row_b[i] if row_b else 0)
                d = 2 if row_b else 1
                line.append(s // d)
        out += bytes(line)
    _write_png(path, SIZE, SIZE, out)


def _write_png(path, width, height, raw_rgba):
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))
    stride = width * 4
    scanlines = b"".join(b"\x00" + raw_rgba[i * stride:(i + 1) * stride]
                         for i in range(height))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(scanlines, 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def sips_scale(src, dst, px):
    subprocess.run(["sips", "-z", str(px), str(px), src, "--out", dst],
                   check=True, stdout=subprocess.DEVNULL)


def build_icns(master):
    tmp = tempfile.mkdtemp(prefix="mydog.iconset.")
    iconset = os.path.join(tmp, "mydog.iconset")
    os.mkdir(iconset)
    spec = {
        "icon_16x16.png": 16, "icon_16x16@2x.png": 32,
        "icon_32x32.png": 32, "icon_32x32@2x.png": 64,
        "icon_128x128.png": 128, "icon_128x128@2x.png": 256,
        "icon_256x256.png": 256, "icon_256x256@2x.png": 512,
        "icon_512x512.png": 512,
    }
    for name, px in spec.items():
        sips_scale(master, os.path.join(iconset, name), px)
    shutil.copyfile(master, os.path.join(iconset, "icon_512x512@2x.png"))
    out = os.path.join(HERE, "mydog.icns")
    subprocess.run(["iconutil", "-c", "icns", iconset, "-o", out], check=True)
    shutil.rmtree(tmp)
    return out


def build_ico(master):
    sizes = [16, 24, 32, 48, 256]
    tmp = tempfile.mkdtemp(prefix="mydog.ico.")
    blobs = []
    try:
        for px in sizes:
            p = os.path.join(tmp, f"{px}.png")
            sips_scale(master, p, px)
            with open(p, "rb") as f:
                blobs.append((px, f.read()))
    finally:
        shutil.rmtree(tmp)
    count = len(blobs)
    header = struct.pack("<HHH", 0, 1, count)
    offset = 6 + 16 * count
    entries, data = b"", b""
    for px, blob in blobs:
        wh = 0 if px >= 256 else px
        entries += struct.pack("<BBBBHHII", wh, wh, 0, 0, 1, 32, len(blob), offset)
        data += blob
        offset += len(blob)
    out = os.path.join(HERE, "mydog.ico")
    with open(out, "wb") as f:
        f.write(header + entries + data)
    return out


def main():
    if sys.platform != "darwin":
        sys.exit("this generator uses sips/iconutil and must run on macOS")
    for tool in ("sips", "iconutil"):
        if shutil.which(tool) is None:
            sys.exit(f"required tool not found: {tool}")
    master = os.path.join(HERE, "mydog.png")
    if "--reuse" in sys.argv and os.path.exists(master):
        print(f"reusing existing master {master}")
    else:
        print(f"rendering {SIZE}x{SIZE} master (2x supersampled)...")
        render_master(master)
    print("building mydog.icns ...")
    build_icns(master)
    print("building mydog.ico ...")
    build_ico(master)
    print("building mydog_512.png ...")
    sips_scale(master, os.path.join(HERE, "mydog_512.png"), 512)
    print("done. artifacts in", HERE)


if __name__ == "__main__":
    main()
