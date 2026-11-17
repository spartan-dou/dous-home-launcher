"""Draws the Dou's Home Launcher icon as PNG, no dependency: a house that is itself a photo.

python3 -I icon.py <out.png> <size> [full|fg|bg]
Everything is on a 0..1 square, from signed distances, sampled 3x3 per pixel.
"""
import math
import struct
import sys
import zlib

# House outline (its corners are rounded by R), white border, soft shadow under it
CORE = [(0.265, 0.475), (0.50, 0.265), (0.735, 0.475), (0.735, 0.76), (0.265, 0.76)]
R = 0.095
BORDER = 0.034
SHADOW_DY = 0.03
SHADOW_SOFT = 0.07
FG_SCALE = 72 / 108

HORIZON = 0.665
SUN_C, SUN_R = (0.575, 0.605), 0.06


def sd_polygon(poly, x, y):
    """Signed distance to a polygon: negative inside."""
    d = float("inf")
    s = 1.0
    j = len(poly) - 1
    for i in range(len(poly)):
        (xi, yi), (xj, yj) = poly[i], poly[j]
        ex, ey = xj - xi, yj - yi
        wx, wy = x - xi, y - yi
        t = max(0.0, min(1.0, (wx * ex + wy * ey) / (ex * ex + ey * ey)))
        bx, by = wx - ex * t, wy - ey * t
        d = min(d, bx * bx + by * by)
        c1, c2, c3 = y >= yi, y < yj, ex * wy > ey * wx
        if (c1 and c2 and c3) or (not c1 and not c2 and not c3):
            s = -s
        j = i
    return s * math.sqrt(d)


def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def over(base, top, a):
    return tuple(base[i] * (1 - a) + top[i] * a for i in range(3))


def hex_(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


BG_TL, BG_BR, GLOW = hex_("3A2470"), hex_("0E2244"), hex_("8E5CC8")
SKY = [(0.24, hex_("6A4C9C")), (0.47, hex_("E77E9B")), (0.60, hex_("FFB27A")), (HORIZON, hex_("FFD98E"))]
SUN, SUN_GLOW = hex_("FFF2C4"), hex_("FFD27A")
FAR, MID, NEAR = hex_("B07BB8"), hex_("7A5AA6"), hex_("3B2E6E")
LAKE_TOP, LAKE_BOTTOM = hex_("C07FA8"), hex_("3A3A7A")


def ridge(x, base, amps):
    """Height of a mountain line at x: a sum of soft peaks."""
    return base - sum(a * math.exp(-((x - c) / w) ** 2) for c, w, a in amps)


def sky(y):
    for (y0, c0), (y1, c1) in zip(SKY, SKY[1:]):
        if y <= y1:
            return lerp(c0, c1, (y - y0) / (y1 - y0))
    return SKY[-1][1]


def photo(x, y):
    """The sunset inside the house."""
    if y > HORIZON:
        # The lake: the sky's warmth fading down, the sun's streak on it
        c = lerp(LAKE_TOP, LAKE_BOTTOM, (y - HORIZON) / 0.13)
        streak = math.exp(-((x - SUN_C[0]) / 0.03) ** 2) * math.exp(-(y - HORIZON) / 0.05)
        return over(c, SUN_GLOW, 0.75 * streak)
    c = sky(y)
    dist = math.hypot(x - SUN_C[0], y - SUN_C[1])
    c = over(c, SUN_GLOW, 0.55 * math.exp(-max(0.0, dist - SUN_R) / 0.05))
    if dist <= SUN_R:
        c = SUN
    if y > ridge(x, 0.655, [(0.33, 0.06, 0.08), (0.46, 0.07, 0.055), (0.69, 0.06, 0.09)]):
        c = FAR
    if y > ridge(x, 0.67, [(0.26, 0.08, 0.055), (0.42, 0.05, 0.095), (0.59, 0.07, 0.045)]):
        c = MID
    if y > ridge(x, 0.678, [(0.64, 0.07, 0.06), (0.78, 0.06, 0.035)]):
        c = NEAR
    return c


def sample(x, y, mode):
    if mode in ("fg", "bg"):
        # Adaptive icon layers: the launcher shows their middle two thirds through its mask, so the
        # whole icon is drawn there, exactly as the full one (Home Assistant's) looks
        x, y = 0.5 + (x - 0.5) / FG_SCALE, 0.5 + (y - 0.5) / FG_SCALE
    if mode != "bg":
        d = sd_polygon(CORE, x, y) - R
    else:
        d = 1.0
    if d <= -BORDER:
        return photo(x, y), 1.0
    if d <= 0:
        return (255, 255, 255), 1.0
    # Background: a rounded square, diagonal gradient, a glow up top, the house's shadow
    if mode == "fg":
        ds = sd_polygon(CORE, x, y - SHADOW_DY) - R
        return (8, 4, 24), 0.45 * math.exp(-max(0.0, ds) / SHADOW_SOFT)
    q = 0.22
    cx, cy = min(max(x, q), 1 - q), min(max(y, q), 1 - q)
    if mode == "full" and (x - cx) ** 2 + (y - cy) ** 2 > q * q:
        return (0, 0, 0), 0.0
    c = lerp(BG_TL, BG_BR, (x + y) / 2)
    c = over(c, GLOW, 0.55 * math.exp(-((x - 0.5) ** 2 + (y - 0.28) ** 2) / 0.06))
    if mode == "full":
        ds = sd_polygon(CORE, x, y - SHADOW_DY) - R
        c = over(c, (8, 4, 24), 0.45 * math.exp(-max(0.0, ds) / SHADOW_SOFT))
    return c, 1.0


def draw(size, mode):
    n = 3
    rows = []
    for py in range(size):
        row = bytearray([0])
        for px in range(size):
            acc = [0.0, 0.0, 0.0]
            alpha = 0.0
            for sy in range(n):
                for sx in range(n):
                    c, a = sample((px + (sx + 0.5) / n) / size, (py + (sy + 0.5) / n) / size, mode)
                    alpha += a
                    for i in range(3):
                        acc[i] += c[i] * a
            row += bytes(min(255, int(acc[i] / alpha + 0.5)) if alpha else 0 for i in range(3))
            row.append(int(255 * alpha / (n * n) + 0.5))
        rows.append(bytes(row))

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b""))


if __name__ == "__main__":
    out, size = sys.argv[1], int(sys.argv[2])
    with open(out, "wb") as f:
        f.write(draw(size, sys.argv[3] if len(sys.argv) > 3 else "full"))
