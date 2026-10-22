"""Generate Photoshop-compatible .acv tone-curve files for the app's filters.

The .acv format (big-endian 16-bit integers):
    version, curve_count,
    then for each curve: point_count, then (output, input) pairs.
Curve order: 0 = RGB composite, 1 = red, 2 = green, 3 = blue.

You can also design a curve in Photoshop (Image > Adjustments > Curves > Save Preset)
and drop the .acv into app/src/main/assets/filters/ directly.

Usage:  python3 tools/make_acv.py app/src/main/assets/filters
"""
import os
import struct
import sys

IDENTITY = [(0, 0), (255, 255)]

# Each filter: [composite, red, green, blue]; points are (input, output).
FILTERS = {
    "warm":  [IDENTITY, [(0, 0), (128, 146), (255, 255)], [(0, 0), (128, 132), (255, 255)], [(0, 0), (128, 108), (255, 235)]],
    "cool":  [IDENTITY, [(0, 0), (128, 112), (255, 240)], [(0, 0), (255, 255)], [(0, 10), (128, 148), (255, 255)]],
    "fade":  [[(0, 38), (128, 132), (255, 232)], IDENTITY, IDENTITY, IDENTITY],
    "punch": [[(0, 0), (64, 44), (192, 214), (255, 255)], IDENTITY, IDENTITY, IDENTITY],
    "retro": [[(0, 0), (70, 58), (190, 202), (255, 255)], [(0, 32), (255, 245)], [(0, 18), (128, 124), (255, 232)], [(0, 58), (255, 198)]],
    "rose":  [[(0, 12), (255, 250)], [(0, 10), (128, 150), (255, 255)], [(0, 0), (128, 120), (255, 245)], [(0, 8), (128, 136), (255, 248)]],
    "noir":  [[(0, 0), (80, 40), (170, 185), (255, 245)], IDENTITY, IDENTITY, IDENTITY],
}


def write_acv(path, curves):
    data = struct.pack(">hh", 1, len(curves))
    for points in curves:
        data += struct.pack(">h", len(points))
        for x, y in points:
            data += struct.pack(">hh", y, x)  # stored as (output, input)
    with open(path, "wb") as f:
        f.write(data)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    for name, curves in FILTERS.items():
        write_acv(os.path.join(out, f"{name}.acv"), curves)
        print("wrote", name)
