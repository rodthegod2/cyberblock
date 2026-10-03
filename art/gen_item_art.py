"""Procedural, original Cyberblock item and GUI art (Minecraft-style 16x16).

Run from anywhere:  uv run --with pillow python art/gen_item_art.py

Everything is drawn from scratch with primitives (rects, discs, polygons, curves):
no game textures are read, copied or traced. Style rules shared by every sprite:
transparent background, a 1px darker outline derived from the neighbouring colour,
top-left light with a 4-tone ramp (highlight / base / shadow / deep), a small shared
palette and four neon accents (cyan, magenta, yellow, red).

Outputs (next to this file): items/*.png, gui/*.png and preview.png.
"""
from __future__ import annotations

import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ART = Path(__file__).resolve().parent
ITEMS = ART / "items"
GUI = ART / "gui"
SIZE = 16


def rgb(h: str) -> tuple[int, int, int]:
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


# --- palette: 4-tone ramps (highlight, base, shadow, deep) ---------------------------
RAMPS = {
    name: tuple(rgb(h) for h in hs)
    for name, hs in {
        "steel": ("#c9d6e4", "#8c9bb0", "#5b6a82", "#3b465c"),
        "gunmetal": ("#6b7790", "#454e66", "#2e3548", "#1f2433"),
        "void": ("#2a3050", "#0d1020", "#090b17", "#05060d"),
        "ink": ("#4a5a9a", "#232d5c", "#161c3e", "#0b0f26"),
        "ash": ("#7a7f98", "#4a4e66", "#2f3245", "#1c1e2c"),
        "red": ("#ff8a8a", "#ff3b3b", "#c21f3a", "#7d1030"),
        "crimson": ("#ff6f7c", "#d3243e", "#9a1634", "#5e0e28"),
        "ember": ("#7a4a35", "#4e2e26", "#331c1c", "#200f12"),
        "orange": ("#ffc060", "#ff8a1f", "#d1520f", "#8a2c0a"),
        "yellow": ("#fffbb0", "#f7ff3d", "#c9cf1f", "#7c8010"),
        "gold": ("#fff0a0", "#ffd23d", "#d6921f", "#8a5412"),
        "cyan": ("#b8fcff", "#3df5ff", "#1aa9c4", "#0d6585"),
        "magenta": ("#ff9ad8", "#ff2fb3", "#bd1a8a", "#741060"),
        "purple": ("#c79bff", "#8f4dff", "#5d2cc2", "#35187a"),
        "lavender": ("#f6efff", "#d6c4ff", "#a58ae6", "#6e54b4"),
        "green": ("#9dff8a", "#3fd04a", "#229a3a", "#126028"),
        "teal": ("#2f8f86", "#1f6b6e", "#164c58", "#0e3342"),
        "glass": ("#eefcff", "#b4dfeb", "#7aaabf", "#4a7690"),
        "bone": ("#f6f3ea", "#d2cec2", "#9d9aa6", "#6a687a"),
        "cork": ("#d9a066", "#a8703c", "#7a4c28", "#4a2c18"),
    }.items()
}
CYAN, MAGENTA, YELLOW, RED = rgb("#3df5ff"), rgb("#ff2fb3"), rgb("#f7ff3d"), rgb("#ff3b3b")
WHITE = rgb("#f2fbff")

Color = tuple[int, int, int]
Mask = set[tuple[int, int]]
GRID = [(x, y) for y in range(SIZE) for x in range(SIZE)]


def dark(c: Color, f: float = 0.3) -> Color:
    """Darker, slightly blue-tinted version of a colour (used for outlines)."""
    return (min(255, int(c[0] * f) + 4), min(255, int(c[1] * f) + 5), min(255, int(c[2] * f) + 12))


def lum(c: Color) -> float:
    return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]


# --- masks (continuous coordinates: pixel (x, y) has its centre at x+.5, y+.5) --------
def rect(x0: int, y0: int, x1: int, y1: int) -> Mask:
    return {(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)}


def ellipse(cx: float, cy: float, rx: float, ry: float) -> Mask:
    return {(x, y) for x, y in GRID if ((x + .5 - cx) / rx) ** 2 + ((y + .5 - cy) / ry) ** 2 <= 1}


def disc(cx: float, cy: float, r: float) -> Mask:
    return ellipse(cx, cy, r, r)


def poly(pts: list[tuple[float, float]]) -> Mask:
    def inside(px: float, py: float) -> bool:
        hit = False
        for (x0, y0), (x1, y1) in zip(pts, pts[1:] + pts[:1]):
            if (y0 > py) != (y1 > py) and px < x0 + (py - y0) * (x1 - x0) / (y1 - y0):
                hit = not hit
        return hit
    return {(x, y) for x, y in GRID if inside(x + .5, y + .5)}


def bar(p0: tuple[float, float], p1: tuple[float, float], w: float) -> Mask:
    """Rectangle of width w along the segment p0-p1."""
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    n = math.hypot(dx, dy)
    nx, ny = -dy / n * w / 2, dx / n * w / 2
    return poly([(p0[0] + nx, p0[1] + ny), (p1[0] + nx, p1[1] + ny),
                 (p1[0] - nx, p1[1] - ny), (p0[0] - nx, p0[1] - ny)])


def sweep(p0, p1, p2, w0: float, w1: float, steps: int = 48) -> Mask:
    """Tapered stroke along a quadratic bezier (used for blades)."""
    out: Mask = set()
    for i in range(steps + 1):
        t = i / steps
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]
        out |= disc(x, y, (w0 + (w1 - w0) * t) / 2)
    return out


def line(a: tuple[int, int], b: tuple[int, int]) -> Mask:
    (x0, y0), (x1, y1) = a, b
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err, out = dx + dy, set()
    while True:
        out.add((x0, y0))
        if (x0, y0) == (x1, y1):
            return out
        e2 = 2 * err
        if e2 >= dy:
            err, x0 = err + dy, x0 + sx
        if e2 <= dx:
            err, y0 = err + dx, y0 + sy


def polyline(pts: list[tuple[int, int]]) -> Mask:
    out: Mask = set()
    for a, b in zip(pts, pts[1:]):
        out |= line(a, b)
    return out


def arc(cx: float, cy: float, r: float, a0: float, a1: float, n: int = 6) -> Mask:
    """1px arc: sample the circle (angles in degrees, y up) and join the samples."""
    pts = []
    for i in range(n):
        a = math.radians(a0 + (a1 - a0) * i / (n - 1))
        pts.append((round(cx + r * math.cos(a) - .5), round(cy - r * math.sin(a) - .5)))
    return polyline(pts)


def mirror(m: Mask, axis: float = 8.0) -> Mask:
    """Mirror horizontally around x = axis (8.0 is the sprite centre)."""
    return {(int(2 * axis - 1 - x), y) for x, y in m}


def dilate(m: Mask) -> Mask:
    return m | {(x + dx, y + dy) for x, y in m for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))}


def glyph(rows: list[str], ox: int, oy: int, legend: dict[str, Color]) -> dict[tuple[int, int], Color]:
    return {(ox + x, oy + y): legend[ch]
            for y, row in enumerate(rows) for x, ch in enumerate(row) if ch in legend}


# --- sprite ------------------------------------------------------------------------
class Sprite:
    """Layered parts with automatic top-left shading and a final 1px outline."""

    def __init__(self) -> None:
        self.pix: dict[tuple[int, int], Color] = {}
        self.top: dict[tuple[int, int], Color] = {}  # drawn after the outline (thin neon)

    def part(self, mask: Mask, ramp: str | tuple, shade: bool = True, sep: bool = False,
             flip: bool = False) -> "Sprite":
        hi, base, lo, deep = RAMPS[ramp] if isinstance(ramp, str) else ramp
        if flip:  # recessed: light comes from the bottom-right
            hi, lo, deep = lo, hi, hi
        if sep:  # dark separation line drawn over what is already underneath
            for p in dilate(mask) - mask:
                if p in self.pix:
                    self.pix[p] = dark(self.pix[p], 0.45)
        for x, y in mask:
            if not shade:
                self.pix[(x, y)] = base
                continue
            lit = (x, y - 1) not in mask or (x - 1, y) not in mask
            shd = (x, y + 1) not in mask or (x + 1, y) not in mask
            if lit and shd:
                c = base
            elif lit:
                c = hi
            elif shd:
                c = deep if ((x, y + 1) not in mask and (x + 1, y) not in mask) else lo
            else:
                c = base
            self.pix[(x, y)] = c
        return self

    def dots(self, cells, color: Color, top: bool = False) -> "Sprite":
        (self.top if top else self.pix).update({p: color for p in cells})
        return self

    def paint(self, pixels: dict, top: bool = False) -> "Sprite":
        (self.top if top else self.pix).update(pixels)
        return self

    def glitch(self, row: int, dx: int, fringe: Color) -> "Sprite":
        """Slide one pixel row sideways; the gap it leaves is filled with a neon fringe."""
        cells = {p: c for p, c in self.pix.items() if p[1] == row}
        for p in cells:
            del self.pix[p]
        self.pix.update({(x + dx, row): c for (x, _), c in cells.items()})
        left, right = min(x for x, _ in cells), max(x for x, _ in cells)
        for x in (range(left, left + dx) if dx > 0 else range(right + dx + 1, right + 1)):
            self.pix[(x, row)] = fringe
        return self

    def erase(self, cells) -> "Sprite":
        for p in cells:
            self.pix.pop(p, None)
        return self

    def render(self, outline: bool = True) -> Image.Image:
        img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        out = dict(self.pix)
        if outline:
            for x, y in dilate(set(self.pix)) - set(self.pix):
                near = [self.pix[q] for q in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)) if q in self.pix]
                out[(x, y)] = dark(min(near, key=lum))
        out.update(self.top)
        for (x, y), c in out.items():
            if 0 <= x < SIZE and 0 <= y < SIZE:
                img.putpixel((x, y), (*c, 255))
        return img


# --- reusable shapes ----------------------------------------------------------------
BOLT = [(0.62, 0.0), (0.08, 0.58), (0.44, 0.58), (0.27, 1.0), (0.94, 0.38), (0.56, 0.38), (0.82, 0.0)]


def bolt(x: float, y: float, w: float, h: float) -> Mask:
    return poly([(x + px * w, y + py * h) for px, py in BOLT])


def brain_front() -> Mask:
    half = ellipse(5.4, 7.0, 4.4, 4.9) | rect(5, 11, 6, 13)
    return half | mirror(half)


def fist(s: Sprite, fingers: str, palm: str, cuff: str, studs: bool) -> None:
    for x0, top in ((2, 3), (5, 2), (8, 2), (11, 3)):
        s.part(rect(x0, top, x0 + 2, 7) - {(x0, top), (x0 + 2, top)}, fingers, sep=True)
    s.part(rect(2, 7, 13, 11), palm, sep=True)
    s.part(rect(2, 8, 8, 11) - {(2, 8), (2, 11)}, fingers, sep=True)  # thumb
    s.part(rect(4, 12, 12, 14), cuff, sep=True)
    if studs:
        s.dots([(3, 3), (6, 2), (9, 2), (12, 3)], YELLOW)


# =====================================================================================
# ITEMS
# =====================================================================================
def cyberdeck() -> Sprite:
    s = Sprite()
    s.part(rect(2, 2, 13, 8), "gunmetal", sep=True)
    s.part(rect(3, 3, 12, 7), "void", shade=False)
    s.dots([(4, 4), (5, 4), (6, 4), (7, 4), (8, 4), (10, 4), (11, 4)], CYAN)
    s.dots([(4, 5), (5, 5)], MAGENTA).dots([(7, 5), (8, 5), (9, 5), (10, 5)], CYAN)
    s.dots([(4, 6)], YELLOW).dots([(6, 6), (7, 6), (8, 6)], CYAN).dots([(11, 6)], YELLOW)
    s.part(rect(1, 9, 14, 12), "steel", sep=True)
    keys = RAMPS["steel"][2]
    s.dots([(3, 10), (5, 10), (7, 10), (9, 10), (11, 10)], keys)
    s.dots([(x, 11) for x in range(4, 10)], RAMPS["steel"][3]).dots([(12, 11)], MAGENTA)
    return s


def ram_shard() -> Sprite:
    s = Sprite()
    pcb = rect(1, 3, 14, 10) - {(14, 3), (13, 3), (14, 4), (14, 6), (13, 7), (14, 7), (14, 8), (14, 9), (13, 10), (14, 10), (12, 10)}
    s.part(pcb, "teal", sep=True)
    for x in (2, 4, 6, 8, 10, 12):
        s.part(rect(x, 11, x, 12), "gold", shade=False)
    for x0 in (2, 6, 10):
        chip = rect(x0, 5, x0 + 2, 8)
        if x0 == 10:
            chip -= {(12, 5), (12, 8), (12, 7)}
        s.part(chip, "void", sep=True)
        s.dots([(x0, 5)], CYAN)
    s.dots([(x, 9) for x in range(2, 12)], RAMPS["teal"][0])
    s.dots([(13, 5), (12, 6)], MAGENTA)
    return s


def redstone_charge() -> Sprite:
    s = Sprite()
    s.part(rect(6, 1, 9, 2), "steel", sep=True)
    s.part(rect(4, 3, 11, 13), "red", sep=True)
    s.part(rect(4, 3, 11, 4), "steel", sep=True)
    s.part(rect(4, 12, 11, 13), "gunmetal", sep=True)
    s.dots([(5, y) for y in range(5, 12)], RAMPS["red"][0])
    s.part(bolt(5.5, 5, 5.2, 7), "yellow", shade=False)
    return s


def blaze_chip() -> Sprite:
    s = Sprite()
    for p in (4, 6, 9, 11):
        s.part({(p, 2), (p, 13), (2, p), (13, p)}, "gold", shade=False)
    s.part(rect(3, 3, 12, 12), "ember", sep=True)
    flame = glyph([
        "..O...",
        "..OO..",
        ".OOOO.",
        ".OYYOO",
        "OOYYYO",
        "OOYYYO",
        ".OOOO.",
    ], 5, 4, {"O": RAMPS["orange"][1], "Y": RAMPS["yellow"][1]})
    s.paint(flame)
    s.dots([(5, 8), (5, 9), (10, 8)], RAMPS["red"][1])
    return s


def ink_sac_daemon() -> Sprite:
    s = Sprite()
    s.part(disc(8, 9.5, 5.3) | rect(7, 3, 8, 5) | rect(6, 2, 9, 3), "ink", sep=True)
    s.part(ellipse(8, 9.6, 4.2, 2.5), "cyan")
    s.part(rect(7, 9, 8, 10), "void", shade=False)
    s.dots([(6, 9)], WHITE)
    return s


def cobweb_daemon() -> Sprite:
    s = Sprite()
    tile = rect(1, 1, 13, 13) - {(1, 1), (2, 1), (1, 2), (13, 1), (12, 1), (13, 2),
                                  (1, 13), (2, 13), (1, 12), (13, 13), (12, 13), (13, 12)}
    s.part(tile, "void", sep=True)
    c = (7, 7)
    web = RAMPS["bone"]
    tips = [(7, 1), (13, 7), (7, 13), (1, 7), (12, 2), (12, 12), (2, 12), (2, 2)]
    for ring in (2, 4, 6):
        d = round(ring * 0.75)
        pts = [(7, 7 - ring), (7 + d, 7 - d), (7 + ring, 7), (7 + d, 7 + d),
               (7, 7 + ring), (7 - d, 7 + d), (7 - ring, 7), (7 - d, 7 - d), (7, 7 - ring)]
        s.dots(polyline(pts), web[2] if ring == 6 else web[1])
    for t in tips:
        s.dots(line(c, t), web[0])
    s.dots([(6, 6), (7, 6), (8, 6), (6, 7), (8, 7), (6, 8), (7, 8), (8, 8)], web[0])
    s.dots([(7, 7)], MAGENTA)
    return s


def spider_eye_virus() -> Sprite:
    s = Sprite()
    spikes = (rect(7, 2, 8, 3) | rect(7, 12, 8, 13) | rect(2, 7, 3, 8) | rect(12, 7, 13, 8)
              | {(3, 3), (4, 4), (12, 3), (11, 4), (3, 12), (4, 11), (12, 12), (11, 11)})
    s.part(spikes, "crimson", sep=True)
    s.part(disc(8, 8, 4.4), "crimson", sep=True)
    s.dots([(7, 2), (8, 2), (2, 7), (2, 8), (13, 7), (13, 8), (7, 13), (8, 13),
            (3, 3), (12, 3), (3, 12), (12, 12)], MAGENTA)
    s.part(ellipse(8, 8, 3.1, 2.1), "yellow")
    s.dots([(8, 7), (8, 8), (8, 9)], RAMPS["void"][3])
    return s


def wither_skull_daemon() -> Sprite:
    s = Sprite()
    s.part(ellipse(8, 7.2, 5.3, 5.1) | rect(5, 10, 10, 13), "ash", sep=True)
    for sock in (rect(4, 6, 6, 8), rect(9, 6, 11, 8)):
        s.part(sock, "void", shade=False)
    s.dots([(5, 7), (6, 7), (5, 8), (6, 8), (9, 7), (10, 7), (9, 8), (10, 8)], RED)
    s.dots([(5, 7), (10, 7)], rgb("#ff9a9a"))
    s.dots([(7, 10), (8, 10)], RAMPS["void"][1])
    s.dots([(6, 12), (9, 12), (6, 13), (9, 13)], RAMPS["void"][1])
    s.dots(polyline([(10, 2), (9, 3), (10, 4), (9, 5)]), CYAN)
    return s


def compass_ping() -> Sprite:
    s = Sprite()
    cx, cy = 6.3, 9.8
    s.part(disc(cx, cy, 5.1), "steel", sep=True)
    s.part(disc(cx, cy, 3.7), "void", flip=True)
    s.part(poly([(cx + 2.5, cy - 2.5), (cx - 0.9, cy - 0.9 + 1.8), (cx + 0.9, cy + 0.9)]), "red", shade=False)
    s.part(poly([(cx - 2.5, cy + 2.5), (cx - 0.9, cy - 0.9 + 1.8), (cx + 0.9, cy + 0.9)]), "bone", shade=False)
    s.dots([(int(cx), int(cy))], WHITE)
    s.dots(arc(cx, cy, 7.3, 6, 84, 8) | arc(cx, cy, 9.2, 10, 80, 9), CYAN, top=True)
    return s


def creeper_brain() -> Sprite:
    s = Sprite()
    lobes = [(4.6, 7.4, 3.2), (7.4, 5.4, 3.4), (10.8, 6.0, 3.4), (12.0, 9.2, 2.6),
             (8.6, 9.2, 3.6), (5.0, 10.6, 2.6)]
    mask: Mask = rect(7, 11, 8, 13)
    for cx, cy, r in lobes:
        mask |= disc(cx, cy, r)
    s.part(mask, "green", sep=True)
    g = RAMPS["green"]
    gyri = [[(7, 3), (8, 4), (7, 5), (8, 6), (7, 7), (8, 8)], [(11, 4), (12, 5), (11, 6), (12, 7)],
            [(3, 8), (4, 9), (5, 8), (6, 9)], [(10, 9), (11, 10), (12, 9)], [(5, 11), (6, 12)]]
    for z in gyri:
        s.dots(polyline(z), g[3])
    s.dots([(5, 4), (9, 5), (4, 7), (10, 7)], g[0])
    s.part(rect(2, 2, 5, 4), "steel", sep=True).dots([(3, 3)], RED).dots([(4, 3)], MAGENTA)
    s.dots([(2, 5), (3, 5), (5, 5)], g[3])
    return s


def bottle_forgetting() -> Sprite:
    s = Sprite()
    body = disc(8, 10, 5.2) | rect(6, 4, 9, 7)
    s.part(body, "glass", sep=True)
    liquid = {p for p in disc(8, 10, 4.2) if p[1] >= 9}
    s.part(liquid, "magenta")
    s.dots([(5, 8), (5, 7), (6, 6), (6, 5)], RAMPS["glass"][0])
    s.dots([(9, 11), (7, 12)], MAGENTA)
    s.dots([(9, 10)], RAMPS["magenta"][0])
    s.part(rect(6, 1, 9, 3), "cork", sep=True)
    s.dots([(12, 4), (13, 5), (12, 6)], CYAN, top=True).dots([(13, 3)], WHITE, top=True)
    return s


def redstone_lever() -> Sprite:
    s = Sprite()
    s.part(rect(2, 10, 13, 14), "steel", sep=True)
    s.dots([(3, 11), (12, 11), (3, 13), (12, 13)], RAMPS["steel"][3])
    s.dots([(5, 12), (6, 12)], CYAN).dots([(9, 12), (10, 12)], RAMPS["cyan"][3])
    s.part(rect(6, 8, 9, 10), "gunmetal", sep=True)
    s.part(bar((7.5, 9), (11.5, 3.5), 2.0), "steel", sep=True)
    s.part(disc(11.8, 3.0, 1.9), "red", sep=True)
    return s


def mantis_blade() -> Sprite:
    s = Sprite()
    s.part(sweep((4.6, 11.4), (9.8, 8.6), (14.6, 1.0), 4.8, 0.6), "steel", sep=True)
    s.dots(line((6, 10), (11, 6)), RAMPS["steel"][0])
    s.part(bar((1.0, 14.8), (5.4, 10.6), 4.4), "gunmetal", sep=True)
    s.dots([(3, 12), (2, 13)], RED).dots([(4, 11)], YELLOW)
    s.dots([(14, 1)], WHITE)
    return s


def gorilla_gauntlet() -> Sprite:
    s = Sprite()
    fist(s, "steel", "gunmetal", "gunmetal", studs=True)
    s.dots([(5, 13), (6, 13), (9, 13), (10, 13)], YELLOW).dots([(7, 13), (8, 13)], MAGENTA)
    s.dots([(x, 9) for x in range(9, 13)], MAGENTA)
    return s


def perk_token() -> Sprite:
    s = Sprite()
    s.part(disc(8, 8, 6.6), "gold", sep=True)
    s.part(disc(8, 8, 4.7), "void", flip=True)
    star = glyph([
        "..#..",
        "..#..",
        "#####",
        ".###.",
        ".#.#.",
    ], 5, 5, {"#": CYAN})
    s.paint(star)
    s.dots([(7, 7), (8, 7)], WHITE)
    return s


# --- skill branch icons ---------------------------------------------------------------
def skill_body() -> Sprite:
    s = Sprite()
    fist(s, "red", "red", "crimson", studs=False)
    return s


def skill_reflexes() -> Sprite:
    s = Sprite()
    s.part(poly([(10.5, 1), (3.2, 8.8), (7.4, 8.8), (5.2, 15), (12.8, 6.4), (8.6, 6.4), (11.8, 1)]), "yellow")
    return s


def gear_mask(cx: float, cy: float, r_in: float, r_out: float, teeth: int, hole: float) -> Mask:
    m: Mask = set()
    for x, y in GRID:
        dx, dy = x + .5 - cx, y + .5 - cy
        r, a = math.hypot(dx, dy), math.atan2(dy, dx)
        tooth = math.sin(teeth * a + math.pi / 2) > -0.2
        if hole < r <= (r_out if tooth else r_in):
            m.add((x, y))
    return m


def skill_technical() -> Sprite:
    s = Sprite()
    s.part(gear_mask(8, 8, 5.1, 7.0, 8, 2.3), "orange", sep=True)
    return s


def skill_intelligence() -> Sprite:
    s = Sprite()
    s.part(brain_front(), "cyan", sep=True)
    trace = RAMPS["cyan"][3]
    half = polyline([(3, 5), (5, 5), (6, 6), (6, 9)]) | polyline([(2, 8), (4, 8), (5, 9), (5, 11)]) | {(4, 11)}
    s.dots(half | mirror(half), trace)
    s.dots(polyline([(7, 3), (8, 4), (7, 5), (8, 6)]), trace).dots([(7, 8), (8, 8), (7, 11), (8, 11)], trace)
    s.dots([(3, 5), (2, 8), (4, 11), (12, 5), (13, 8), (11, 11)], WHITE)
    return s


def skill_cool() -> Sprite:
    s = Sprite()
    lens = disc(8, 8 + 3.03, 7.63) & disc(8, 8 - 3.03, 7.63)
    s.part(lens, "lavender", sep=True)
    s.part(disc(8, 8, 3.6) & lens, "purple", sep=True)
    s.part(disc(8, 8, 1.6), "void", shade=False)
    s.dots([(7, 7)], WHITE).dots([(9, 9)], MAGENTA)
    return s


SPRITES = {
    "cyberdeck": cyberdeck, "ram_shard": ram_shard, "redstone_charge": redstone_charge,
    "blaze_chip": blaze_chip, "ink_sac_daemon": ink_sac_daemon, "cobweb_daemon": cobweb_daemon,
    "spider_eye_virus": spider_eye_virus, "wither_skull_daemon": wither_skull_daemon,
    "compass_ping": compass_ping, "creeper_brain": creeper_brain, "bottle_forgetting": bottle_forgetting,
    "redstone_lever": redstone_lever, "mantis_blade": mantis_blade, "gorilla_gauntlet": gorilla_gauntlet,
    "perk_token": perk_token,
    "skill_body": skill_body, "skill_reflexes": skill_reflexes, "skill_technical": skill_technical,
    "skill_intelligence": skill_intelligence, "skill_cool": skill_cool,
}


# =====================================================================================
# GUI
# =====================================================================================
def ram_bar() -> Image.Image:
    """182x10 like the XP bar sheet: rows 0-4 empty frame, rows 5-9 filled cyan."""
    w = 182
    img = Image.new("RGBA", (w, 10), (0, 0, 0, 0))
    frame = [rgb("#070b14"), rgb("#17233b"), rgb("#0e1829"), rgb("#0e1829"), rgb("#070b14")]
    fill = [rgb("#19b8cc"), rgb("#9ffcff"), RAMPS["cyan"][1], rgb("#27cfe6"), RAMPS["cyan"][3]]
    ticks = {round(i * w / 10) for i in range(1, 10)}
    for x in range(w):
        for y in range(5):
            if x in (0, w - 1) and y in (0, 4):
                continue  # clipped corners
            e = frame[y] if x not in (0, w - 1) else frame[0]
            f = fill[y] if x not in (0, w - 1) else dark(fill[y], 0.55)
            if x in ticks and 0 < y < 4:
                e, f = rgb("#1f2f4d"), dark(fill[y], 0.78)
            img.putpixel((x, y), (*e, 255))
            img.putpixel((x, y + 5), (*f, 255))
    return img


def skill_tree_bg() -> Image.Image:
    """256x256 beveled dark panel with a faint circuit pattern."""
    n = 256
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    px = img.load()
    base, trace, node = rgb("#1b1f30"), rgb("#262d48"), rgb("#323c60")
    lit1, lit2, shd1, shd2, edge = rgb("#68729a"), rgb("#4a5478"), rgb("#10131f"), rgb("#0a0c15"), rgb("#05070d")
    for y in range(n):
        for x in range(n):
            px[x, y] = (*base, 255)

    rng = random.Random(2077)
    used: set[tuple[int, int]] = set()
    dirs = [(1, 0), (0, 1), (-1, 0), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)]
    cell, lo, hi = 8, 3, n // 8 - 3
    drawn = 0
    for _ in range(900):
        if drawn >= 120:
            break
        gx, gy = rng.randint(lo, hi), rng.randint(lo, hi)
        if (gx, gy) in used:
            continue
        d, path = rng.choice(dirs), [(gx, gy)]
        for _ in range(rng.randint(3, 12)):
            if rng.random() < 0.35:
                d = rng.choice([dirs[(dirs.index(d) + k) % 8] for k in (-1, 1)] if rng.random() < .6 else dirs)
            nx, ny = path[-1][0] + d[0], path[-1][1] + d[1]
            if not (lo <= nx <= hi and lo <= ny <= hi) or (nx, ny) in used or (nx, ny) in path:
                break
            path.append((nx, ny))
        if len(path) < 3:
            continue
        used.update(path)
        drawn += 1
        pts = [(gx * cell, gy * cell) for gx, gy in path]
        for a, b in zip(pts, pts[1:]):
            for x, y in line(a, b):
                px[x, y] = (*trace, 255)
        ex, ey = pts[-1]
        for x, y in ((ex - 1, ey), (ex + 1, ey), (ex, ey - 1), (ex, ey + 1)):
            px[x, y] = (*node, 255)
        px[ex, ey] = (*base, 255)
        sx, sy = pts[0]
        for x, y in ((sx, sy), (sx + 1, sy), (sx, sy + 1), (sx + 1, sy + 1)):
            px[x, y] = (*node, 255)

    for y in range(n):
        for x in range(n):
            k = min(x, y, n - 1 - x, n - 1 - y)
            if k == 0:
                px[x, y] = (*edge, 255)
            elif k <= 2:
                lit = min(x, y) <= min(n - 1 - x, n - 1 - y)  # nearer the top/left edge
                px[x, y] = (*((lit1, lit2) if lit else (shd1, shd2))[k - 1], 255)
    for y in range(n):  # notched corners like a Minecraft panel
        for x in range(n):
            s = min(x, n - 1 - x) + min(y, n - 1 - y)
            if s <= 1:
                px[x, y] = (0, 0, 0, 0)
            elif s == 2 and min(x, n - 1 - x) <= 2 and min(y, n - 1 - y) <= 2:
                px[x, y] = (*edge, 255)
    return img


# =====================================================================================
# PREVIEW
# =====================================================================================
def preview(images: dict[str, Image.Image], gui: dict[str, Image.Image]) -> Image.Image:
    scale, cols, pad, label_h = 8, 5, 16, 26
    cw, ch = SIZE * scale + pad, SIZE * scale + label_h + pad
    rows = math.ceil(len(images) / cols)
    gui_h = 256 + 40
    sheet = Image.new("RGBA", (cols * cw + pad, rows * ch + pad + gui_h), rgb("#2a2d38") + (255,))
    d = ImageDraw.Draw(sheet)
    font = ImageFont.load_default(size=14)
    for i, (name, im) in enumerate(images.items()):
        x0, y0 = pad + (i % cols) * cw, pad + (i // cols) * ch
        big = im.resize((SIZE * scale, SIZE * scale), Image.NEAREST)
        tile = Image.new("RGBA", big.size)
        td = ImageDraw.Draw(tile)
        for ty in range(0, big.height, 16):  # checkerboard shows the transparency
            for tx in range(0, big.width, 16):
                td.rectangle([tx, ty, tx + 15, ty + 15], fill=rgb("#8b8b8b") if (tx + ty) // 16 % 2 else rgb("#7a7a7a"))
        tile.alpha_composite(big)
        sheet.paste(tile, (x0, y0))
        d.text((x0, y0 + SIZE * scale + 4), name, fill=(235, 240, 255), font=font)
    gy = rows * ch + pad + 8
    for name, im, gx in (("skill_tree_bg", gui["skill_tree_bg"], pad), ("ram_bar", gui["ram_bar"], pad + 256 + pad)):
        sheet.alpha_composite(im, (gx, gy))
        d.text((gx, gy + im.height + 2), f"{name} {im.width}x{im.height}", fill=(235, 240, 255), font=font)
    return sheet


def main() -> None:
    ITEMS.mkdir(parents=True, exist_ok=True)
    GUI.mkdir(parents=True, exist_ok=True)
    images = {}
    for name, fn in SPRITES.items():
        im = fn().render()
        im.save(ITEMS / f"{name}.png")
        images[name] = im
    gui = {"skill_tree_bg": skill_tree_bg(), "ram_bar": ram_bar()}
    for name, im in gui.items():
        im.save(GUI / f"{name}.png")
    preview(images, gui).convert("RGB").save(ART / "preview.png")
    print(f"wrote {len(images)} items, {len(gui)} gui images, preview.png")


if __name__ == "__main__":
    main()
