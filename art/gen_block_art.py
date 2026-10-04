"""Procedural, original Cyberblock block art: the Night City replica blocks and the neutral figure skin (megaplan 3-A1).

Run from anywhere:  uv run --with pillow python art/gen_block_art.py

Deterministic: every texture is drawn from a fixed seed (the block id), so two runs give byte-identical files. Nothing is read
from the game or from Minecraft: the textures are drawn from scratch with pixel rules (noise, seams, bricks, tubes).

Structure: BLOCKS is a table of BlockSpec rows (id, English name, painter, model kind). Phase 4 (4A) appends its device blocks to
that table (and, when a block needs its own model shape, adds a model kind to MODELS); nothing else changes. For every row the
script writes under mc/src/main/resources/assets/cyberblock/:

    textures/block/<id>.png        16x16 texture (glass and water carry alpha: Minecraft draws a texture with partly transparent
                                   pixels in its translucent layer by itself, no Java render-layer call is needed)
    models/block/<id>.json         "cube" (cube_all) or "emissive" (a full cube with light_emission 15: drawn at full brightness)
    blockstates/<id>.json          one variant
    items/<id>.json                the inventory model definition (the block model)

and the neutral figure skin textures/entity/figure/neutral.png (64x64, classic arm layout) used by the visible figures.
A preview sheet is written to art/block_preview.png. The English names of the blocks live in lang/en_us.json (a test checks them).
"""
from __future__ import annotations

import io
import itertools
import json
import math
import random
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

ART = Path(__file__).resolve().parent
ASSETS = ART.parent / "mc" / "src" / "main" / "resources" / "assets" / "cyberblock"
NAMESPACE = "cyberblock"
SIZE = 16

Color = tuple[int, int, int]
RGBA = tuple[int, int, int, int]


def rgb(h: str) -> Color:
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def clamp(v: float) -> int:
    return max(0, min(255, round(v)))


def scale(c: Color, f: float) -> Color:
    return clamp(c[0] * f), clamp(c[1] * f), clamp(c[2] * f)


def mix(a: Color, b: Color, t: float) -> Color:
    return clamp(a[0] + (b[0] - a[0]) * t), clamp(a[1] + (b[1] - a[1]) * t), clamp(a[2] + (b[2] - a[2]) * t)


def jitter(c: Color, rnd: random.Random, amount: float) -> Color:
    return scale(c, 1 + rnd.uniform(-amount, amount))


def canvas() -> Image.Image:
    return Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 255))


def put(im: Image.Image, x: int, y: int, c: Color, a: int = 255) -> None:
    im.putpixel((x % SIZE, y % SIZE), (c[0], c[1], c[2], a))


def fill_noise(im: Image.Image, rnd: random.Random, base: Color, amount: float, alpha: int = 255) -> None:
    for y in range(SIZE):
        for x in range(SIZE):
            put(im, x, y, jitter(base, rnd, amount), alpha)


def scatter(im: Image.Image, rnd: random.Random, count: int, colour: Color, strength: float, alpha: int = 255) -> None:
    """Blend `count` random pixels towards `colour`."""
    for _ in range(count):
        x, y = rnd.randrange(SIZE), rnd.randrange(SIZE)
        r, g, b, _a = im.getpixel((x, y))
        put(im, x, y, mix((r, g, b), colour, strength), alpha)


# --- painters: one per material, each returns a 16x16 RGBA tile -----------------------------------------------------------
def concrete(base: str, stains: bool) -> Callable[[random.Random], Image.Image]:
    """Formwork concrete: grain, panel seams on the top row and left column, four tie holes, pits (and rain streaks when stained)."""
    colour = rgb(base)

    def paint(rnd: random.Random) -> Image.Image:
        im = canvas()
        fill_noise(im, rnd, colour, 0.05)
        scatter(im, rnd, 10, scale(colour, 0.7), 0.6)
        scatter(im, rnd, 6, scale(colour, 1.3), 0.5)
        if stains:
            for _ in range(3):
                x, top, length = rnd.randrange(2, SIZE), rnd.randrange(0, 6), rnd.randrange(5, 11)
                for y in range(top, min(SIZE, top + length)):
                    r, g, b, _a = im.getpixel((x, y))
                    put(im, x, y, scale((r, g, b), 0.84))
        for i in range(SIZE):
            put(im, i, 0, scale(colour, 0.62))
            put(im, 0, i, scale(colour, 0.68))
            put(im, i, 1, scale(colour, 1.08))
            put(im, 1, i, scale(colour, 1.05))
        for hx, hy in ((4, 5), (11, 5), (4, 11), (11, 11)):
            put(im, hx, hy, scale(colour, 0.5))
            put(im, hx + 1, hy + 1, scale(colour, 1.16))
        return im

    return paint


def asphalt(rnd: random.Random) -> Image.Image:
    base = rgb("#2c2e35")
    im = canvas()
    fill_noise(im, rnd, base, 0.10)
    scatter(im, rnd, 16, rgb("#6d717c"), 0.55)
    scatter(im, rnd, 10, rgb("#15161b"), 0.7)
    x, y = 0, rnd.randrange(4, 12)
    for _ in range(11):  # one hairline crack walking right
        put(im, x, y, rgb("#121318"))
        x += 1
        y += rnd.choice((-1, 0, 0, 1))
    return im


def sidewalk(rnd: random.Random) -> Image.Image:
    base, grout = rgb("#9b9ca3"), rgb("#5d5f68")
    im = canvas()
    for sy in (0, 8):
        for sx in (0, 8):
            slab = jitter(base, rnd, 0.045)
            for j in range(8):
                for i in range(8):
                    c = jitter(slab, rnd, 0.025)
                    if i == 0 or j == 0:
                        c = jitter(grout, rnd, 0.04)
                    elif i == 1 or j == 1:
                        c = scale(c, 1.08)
                    put(im, sx + i, sy + j, c)
    scatter(im, rnd, 8, scale(base, 0.75), 0.6)
    return im


def glass(rnd: random.Random) -> Image.Image:
    pane, frame, glint = rgb("#8edcf0"), rgb("#d6f4ff"), rgb("#ffffff")
    im = canvas()
    for y in range(SIZE):
        for x in range(SIZE):
            edge = x in (0, SIZE - 1) or y in (0, SIZE - 1)
            put(im, x, y, frame if edge else jitter(pane, rnd, 0.03), 215 if edge else 96)
    for i in range(5):  # two diagonal reflections
        put(im, 3 + i, 8 - i, glint, 175)
        put(im, 8 + i, 12 - i, glint, 130)
    return im


def metal(rnd: random.Random) -> Image.Image:
    base = rgb("#7d8798")
    im = canvas()
    for y in range(SIZE):
        row = 1 + rnd.uniform(-0.05, 0.05)  # brushed: the tone is per row, with a little per-pixel grain
        for x in range(SIZE):
            put(im, x, y, scale(base, row * (1 + rnd.uniform(-0.02, 0.02))))
    for i in range(SIZE):
        put(im, i, 0, scale(base, 1.22))
        put(im, 0, i, scale(base, 1.15))
        put(im, i, SIZE - 1, scale(base, 0.68))
        put(im, SIZE - 1, i, scale(base, 0.72))
    for rx, ry in ((2, 2), (13, 2), (2, 13), (13, 13)):  # rivets
        put(im, rx, ry, scale(base, 0.55))
        put(im, rx - 1, ry - 1, scale(base, 1.25))
    for i in range(4, 12):  # a recessed groove
        put(im, i, 7, scale(base, 0.62))
        put(im, i, 8, scale(base, 1.18))
    return im


def brick(rnd: random.Random) -> Image.Image:
    base, mortar = rgb("#8e473a"), rgb("#574b4b")
    tints = {(r, c): 1 + rnd.uniform(-0.12, 0.12) for r in range(4) for c in range(3)}
    im = canvas()
    for y in range(SIZE):
        r = y // 4
        for x in range(SIZE):
            shifted = x + 4 * (r % 2)
            if y % 4 == 3 or shifted % 8 == 7:
                put(im, x, y, jitter(mortar, rnd, 0.05))
            else:
                c = scale(base, tints[(r, shifted // 8)])
                put(im, x, y, scale(jitter(c, rnd, 0.05), 1.1 if y % 4 == 0 else 1.0))
    return im


def tile(rnd: random.Random) -> Image.Image:
    base, grout = rgb("#b6d2d8"), rgb("#6e838b")
    im = canvas()
    for ty in (0, 8):
        for tx in (0, 8):
            slab = jitter(base, rnd, 0.035)
            for j in range(8):
                for i in range(8):
                    if i == 7 or j == 7:
                        c = jitter(grout, rnd, 0.03)
                    elif i == 0 or j == 0:
                        c = scale(slab, 1.1)
                    else:
                        c = jitter(slab, rnd, 0.015)
                    put(im, tx + i, ty + j, c)
            put(im, tx + 2, ty + 2, rgb("#ffffff"))
    return im


def ground(rnd: random.Random) -> Image.Image:
    base = rgb("#7f684c")
    im = canvas()
    fill_noise(im, rnd, base, 0.10)
    scatter(im, rnd, 12, scale(base, 0.7), 0.7)
    scatter(im, rnd, 9, rgb("#c2a878"), 0.5)
    for _ in range(2):  # a pebble: two light pixels side by side
        x, y = rnd.randrange(SIZE - 1), rnd.randrange(SIZE)
        put(im, x, y, rgb("#a89a86"))
        put(im, x + 1, y, rgb("#8f8270"))
    return im


def water(rnd: random.Random) -> Image.Image:
    base, crest = rgb("#246ab4"), rgb("#6fb4e8")
    im = canvas()
    for y in range(SIZE):
        for x in range(SIZE):
            ripple = (x + 2 * y) % 8 in (0, 1) and (x + y) % 5 != 0
            put(im, x, y, jitter(crest if ripple else base, rnd, 0.035), 185 if ripple else 168)
    return im


# vanilla dye order; each colour is (tube core, housing) in an own neon-saturated tuning, not a copy of any game palette
NEON_DYES: dict[str, str] = {
    "white": "#f4fbff", "orange": "#ff8a1f", "magenta": "#ff2fb3", "light_blue": "#4cc3ff",
    "yellow": "#f7ff3d", "lime": "#8dff2f", "pink": "#ff7fb8", "gray": "#9aa3b8",
    "light_gray": "#d3d8e6", "cyan": "#3df5ff", "purple": "#a45cff", "blue": "#3b63ff",
    "brown": "#d08a4a", "green": "#2cff6b", "red": "#ff3b3b", "black": "#6a52c9",
}


def neon(core_hex: str) -> Callable[[random.Random], Image.Image]:
    """A lit panel: dark housing, dim backlight, three glass tubes (bright core, white-hot centre line, halo above and below)."""
    core = rgb(core_hex)

    def paint(rnd: random.Random) -> Image.Image:
        im = canvas()
        for y in range(SIZE):
            for x in range(SIZE):
                if x in (0, SIZE - 1) or y in (0, SIZE - 1):
                    c = scale(core, 0.28)
                else:
                    c = jitter(scale(core, 0.42), rnd, 0.04)
                put(im, x, y, c)
        for ty in (3, 7, 11):
            for x in range(1, SIZE - 1):
                put(im, x, ty - 1, scale(core, 0.66))
                put(im, x, ty, core)
                put(im, x, ty + 1, mix(core, rgb("#ffffff"), 0.55))
                put(im, x, ty + 2, scale(core, 0.66))
        return im

    return paint


# --- the table of blocks ------------------------------------------------------------------------------------------------
@dataclass(frozen=True)
class BlockSpec:
    id: str
    name: str  # English display name (kept in lang/en_us.json by hand; tests compare)
    paint: Callable[[random.Random], Image.Image]
    model: str = "cube"  # a key of MODELS


def neon_name(dye: str) -> str:
    return "Night City Neon (" + dye.replace("_", " ").title() + ")"


# Order matters: the material_palette codes follow it (0 unknown, 1.. in this order, neon in dye order).
BLOCKS: list[BlockSpec] = [
    BlockSpec("nc_concrete_light", "Night City Concrete (Light)", concrete("#aeb3bd", stains=False)),
    BlockSpec("nc_concrete_dark", "Night City Concrete (Dark)", concrete("#5c616d", stains=True)),
    BlockSpec("nc_asphalt", "Night City Asphalt", asphalt),
    BlockSpec("nc_sidewalk", "Night City Sidewalk", sidewalk),
    BlockSpec("nc_glass", "Night City Glass", glass),
    BlockSpec("nc_metal", "Night City Metal", metal),
    BlockSpec("nc_brick", "Night City Brick", brick),
    BlockSpec("nc_tile", "Night City Tile", tile),
    BlockSpec("nc_ground", "Night City Ground", ground),
    BlockSpec("nc_water", "Night City Water", water),
    *(BlockSpec(f"nc_neon_{dye}", neon_name(dye), neon(hexcol), model="emissive") for dye, hexcol in NEON_DYES.items()),
]

FACES = ("down", "up", "north", "south", "west", "east")


def model_cube(block: BlockSpec) -> dict:
    return {"parent": "minecraft:block/cube_all", "textures": {"all": f"{NAMESPACE}:block/{block.id}"}}


def model_emissive(block: BlockSpec) -> dict:
    """A full cube whose element has light_emission 15, so the faces are drawn at full brightness (the lit tubes glow)."""
    return {
        "ambientocclusion": False,
        "textures": {"all": f"{NAMESPACE}:block/{block.id}", "particle": "#all"},
        "elements": [{
            "from": [0, 0, 0], "to": [16, 16, 16], "light_emission": 15,
            "faces": {f: {"texture": "#all", "cullface": f} for f in FACES},
        }],
    }


MODELS: dict[str, Callable[[BlockSpec], dict]] = {"cube": model_cube, "emissive": model_emissive}


# --- the neutral figure skin (64x64, classic layout) ------------------------------------------------------------------
SKIN = {
    "skin": rgb("#b3a9a6"), "hair": rgb("#2d3142"), "shirt": rgb("#4b5772"), "stripe": rgb("#3df5ff"),
    "pants": rgb("#2b3040"), "shoes": rgb("#171a22"), "eye": rgb("#1b1e29"), "white": rgb("#eef2f7"),
}


def cuboid_faces(u: int, v: int, w: int, h: int, d: int) -> dict[str, tuple[int, int, int, int]]:
    """The six rectangles (x, y, width, height) of a skin cuboid of w x h x d pixels whose texture origin is (u, v)."""
    return {
        "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h), "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h), "back": (u + 2 * d + w, v + d, w, h),
    }


def paint_cuboid(im: Image.Image, rnd: random.Random, origin: tuple[int, int], dims: tuple[int, int, int],
                 shader: Callable[[str, int, int, int, int], Color]) -> None:
    """Paint every face pixel with shader(face, i, j, face width, face height), plus a faint grain."""
    for face, (x0, y0, fw, fh) in cuboid_faces(*origin, *dims).items():
        for j in range(fh):
            for i in range(fw):
                im.putpixel((x0 + i, y0 + j), (*jitter(shader(face, i, j, fw, fh), rnd, 0.025), 255))


def head_shader(face: str, i: int, j: int, fw: int, fh: int) -> Color:
    if face == "top" or face == "back":
        return SKIN["hair"]
    if face == "bottom":
        return scale(SKIN["skin"], 0.8)
    if face in ("left", "right"):
        return SKIN["hair"] if j < 3 or (face == "left" and i >= 5) or (face == "right" and i <= 2) else SKIN["skin"]
    if j < 2 or (j == 2 and i in (0, 7)):
        return SKIN["hair"]  # a plain cap of hair with a short fringe
    if j == 3 and i in (1, 2, 5, 6):
        return scale(SKIN["hair"], 1.4)  # brows
    if j == 4 and i in (1, 6):
        return SKIN["white"]
    if j == 4 and i in (2, 5):
        return SKIN["eye"]
    if j == 6 and i in (3, 4):
        return scale(SKIN["skin"], 0.72)  # mouth
    return SKIN["skin"]


def body_shader(face: str, i: int, j: int, fw: int, fh: int) -> Color:
    if face == "bottom":
        return SKIN["pants"]
    if face == "top":
        return SKIN["shirt"]
    if face == "front" and j == 0 and 2 <= i <= 5:
        return SKIN["stripe"]  # a thin collar line
    if face == "front" and i in (3, 4) and j >= 1:
        return scale(SKIN["shirt"], 0.82)  # the zip
    return SKIN["shirt"] if j < 9 else SKIN["pants"]


def arm_shader(face: str, i: int, j: int, fw: int, fh: int) -> Color:
    if face == "bottom":
        return SKIN["skin"]
    return SKIN["shirt"] if face == "top" or j < 9 else SKIN["skin"]  # a sleeve with a hand


def leg_shader(face: str, i: int, j: int, fw: int, fh: int) -> Color:
    if face == "bottom":
        return SKIN["shoes"]
    return SKIN["pants"] if face == "top" or j < 10 else SKIN["shoes"]


def figure_skin() -> Image.Image:
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    rnd = random.Random("neutral_figure_skin")
    paint_cuboid(im, rnd, (0, 0), (8, 8, 8), head_shader)
    paint_cuboid(im, rnd, (16, 16), (8, 12, 4), body_shader)
    paint_cuboid(im, rnd, (40, 16), (4, 12, 4), arm_shader)  # right arm
    paint_cuboid(im, rnd, (0, 16), (4, 12, 4), leg_shader)
    paint_cuboid(im, rnd, (32, 48), (4, 12, 4), arm_shader)  # left arm (its own slot in the 64x64 layout)
    paint_cuboid(im, rnd, (16, 48), (4, 12, 4), leg_shader)
    return im


# --- Phase 4: the device blocks and the eddies items (megaplan 4-A1) -------------------------------------------------------
# The device blocks are a second table, DEVICES, so BLOCKS (and with it the material_palette codes) stays exactly as 3A left it.
# Block ids, properties and model stems below are the contract between this art (4A), the block classes (4B) and the device
# mapping (4M); shared/content-schemas/device_map.schema.json repeats the id table. Every device block also has the property
# quest_locked (false/true: true shows the greyed texture, section 1.11), except the crate. All ids are in the cyberblock: namespace.
#
#   lamp_<dye>        lit                    16 dyes of NEON_DYES; lit is a full-bright cube, unlit a dull one
#   eye_camera        facing, mode           mode: alarm, friendly, off, on (the vision cone is a client particle effect)
#   net_port          breached, facing
#   vending_machine   broken, facing
#   traffic_lamp      signal                 signal: amber, green, off, red (a pole-thin box, light 12 while lit)
#   lift_platform     moving                 a 4 px slab; the lift floor is the block's position
#   lift_call_button  facing, lit            a wall plate on the back of its cell; facing is the side the plate looks at
#   turret            facing, mode           mode: active, friendly, off
#   sliding_gate      facing, step           step 0 closed .. 4 open: the panel retracts towards the west end in 4 px steps
#   screen_frame      facing, on             the backing cube of a glow item frame that shows the map image
#   crate             (none)                 the closed crate for containers whose loot is unreadable
HOUSING = rgb("#2f3441")
FACING_Y = {"north": 0, "east": 90, "south": 180, "west": 270}  # facing=north is the model as drawn (its front looks north)
HAZARD, HAZARD_DARK = rgb("#f5c518"), rgb("#1b1d24")


def clear() -> Image.Image:
    return Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))


def box(im: Image.Image, x0: int, y0: int, x1: int, y1: int, c: Color, a: int = 255) -> None:
    """Fill the pixels x0 <= x < x1, y0 <= y < y1."""
    for y in range(y0, y1):
        for x in range(x0, x1):
            put(im, x, y, c, a)


def disc(im: Image.Image, cx: float, cy: float, r: float, colour: Callable[[float], Color]) -> None:
    """Fill the pixels whose centre is within r of (cx, cy); colour(d) gets the distance (square roots only: reproducible)."""
    for y in range(SIZE):
        for x in range(SIZE):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            if dx * dx + dy * dy <= r * r:
                put(im, x, y, colour(math.sqrt(dx * dx + dy * dy)))


def housing(rnd: random.Random, base: Color = HOUSING) -> Image.Image:
    """A bevelled dark-metal panel: grain, lit top-left edges, shadowed bottom-right edges, four screws."""
    im = canvas()
    fill_noise(im, rnd, base, 0.04)
    for i in range(SIZE):
        put(im, i, 0, scale(base, 1.35))
        put(im, 0, i, scale(base, 1.2))
        put(im, i, SIZE - 1, scale(base, 0.6))
        put(im, SIZE - 1, i, scale(base, 0.65))
    for x, y in ((1, 1), (SIZE - 2, 1), (1, SIZE - 2), (SIZE - 2, SIZE - 2)):
        put(im, x, y, scale(base, 0.5))
    return im


def hazard(im: Image.Image, x0: int, y0: int, x1: int, y1: int) -> None:
    """Diagonal yellow and black warning stripes."""
    for y in range(y0, y1):
        for x in range(x0, x1):
            put(im, x, y, HAZARD if ((x + y) // 2) % 2 else HAZARD_DARK)


def greyed(src: Image.Image) -> Image.Image:
    """The quest-locked look: luminance only, darkened, with a diagonal hatch (alpha kept)."""
    out = Image.new("RGBA", src.size)
    for y in range(src.height):
        for x in range(src.width):
            r, g, b, a = src.getpixel((x, y))
            v = 22 + 0.55 * (0.30 * r + 0.59 * g + 0.11 * b)
            v *= 0.72 if (x + y) % 4 == 0 else 1.0
            out.putpixel((x, y), (clamp(v), clamp(v), clamp(v + 4), a))
    return out


def lamp(core_hex: str, lit: bool) -> Callable[[random.Random], Image.Image]:
    """A square wall or ceiling lamp: housing frame and a 12 x 12 lens (lit: white-hot centre fading to the dye; unlit: dull tinted glass)."""
    core = rgb(core_hex)

    def paint(rnd: random.Random) -> Image.Image:
        im = housing(rnd)
        for y in range(2, 14):
            for x in range(2, 14):
                d = max(abs(x + 0.5 - 8), abs(y + 0.5 - 8))  # 0.5 .. 5.5, square rings
                if lit:
                    c = mix(mix(core, rgb("#ffffff"), 0.7), core, d / 5.5)
                    c = scale(c, 0.78) if d > 5 else c
                else:
                    c = scale(mix(core, rgb("#59606e"), 0.65), 0.34 + 0.05 * (5.5 - d) / 5.5)
                put(im, x, y, jitter(c, rnd, 0.02))
        if not lit:
            put(im, 4, 4, scale(core, 0.7))  # a faint glint
            put(im, 5, 5, scale(core, 0.6))
        return im

    return paint


def lens_front(iris_hex: str) -> Callable[[random.Random], Image.Image]:
    """The eye camera's front: bezel ring, black glass, a coloured iris, a pupil and a glint."""
    iris = rgb(iris_hex)

    def paint(rnd: random.Random) -> Image.Image:
        im = housing(rnd, rgb("#262b36"))
        disc(im, 8, 8, 6.4, lambda d: scale(rgb("#8a93a6"), 1.1 - 0.06 * d))
        disc(im, 8, 8, 5.3, lambda d: rgb("#07090e"))
        disc(im, 8, 8, 3.8, lambda d: scale(iris, 1.15 - 0.2 * d))
        disc(im, 8, 8, 1.6, lambda d: rgb("#050609"))
        put(im, 5, 5, rgb("#ffffff"))
        put(im, 6, 5, mix(iris, rgb("#ffffff"), 0.6))
        return im

    return paint


def vent_side(rnd: random.Random) -> Image.Image:
    """A housing side with three vent slots."""
    im = housing(rnd)
    for y in (5, 8, 11):
        for x in range(3, 13):
            put(im, x, y, scale(HOUSING, 0.35))
            put(im, x, y + 1, scale(HOUSING, 1.3))
    return im


def cap_top(rnd: random.Random) -> Image.Image:
    """A housing top with a small round cap in the middle."""
    im = housing(rnd)
    disc(im, 8, 8, 3.2, lambda d: scale(rgb("#7b8598"), 1.2 - 0.12 * d))
    return im


def plain_housing(rnd: random.Random) -> Image.Image:
    return housing(rnd)


def net_port_front(state_hex: str) -> Callable[[random.Random], Image.Image]:
    """The Net Port: a small screen with data lines, a three-pin socket and a status LED (amber locked, green breached)."""
    state = rgb(state_hex)

    def paint(rnd: random.Random) -> Image.Image:
        im = housing(rnd, rgb("#272d39"))
        box(im, 2, 2, 14, 9, rgb("#06141a"))
        for length, y in ((9, 3), (6, 5), (7, 7)):
            for x in range(3, 3 + length):
                if rnd.random() < 0.88:
                    put(im, x, y, scale(state, 0.55 + 0.45 * rnd.random()))
        box(im, 5, 10, 11, 14, rgb("#0b0d12"))
        for x in (6, 8, 9):
            put(im, x, 11, rgb("#d9b84a"))
            put(im, x, 12, rgb("#d9b84a"))
        for x in range(5, 11):
            put(im, x, 10, scale(HOUSING, 1.5))
        put(im, 12, 11, state)
        put(im, 12, 12, scale(state, 0.55))
        return im

    return paint


def vending_front(broken: bool) -> Callable[[random.Random], Image.Image]:
    """The vending machine face: a sign bar, a window with three shelves of products, a keypad, a coin slot and a flap."""
    stock = [rgb(h) for h in ("#ff5d5d", "#ffd25d", "#5dd1ff", "#7dff8a", "#ff7fe0", "#ffffff")]

    def paint(rnd: random.Random) -> Image.Image:
        im = housing(rnd, rgb("#252a35"))
        sign = rgb("#ff2fb3")
        box(im, 2, 2, 14, 4, scale(sign, 0.3) if broken else sign)
        box(im, 2, 5, 10, 13, rgb("#0b1626"))
        for row, y in enumerate((5, 8, 11)):
            for x in range(2, 10):
                put(im, x, y + 2, scale(rgb("#7a8499"), 0.8))  # the shelf
            for x in range(3, 9, 2):
                c = stock[(row * 3 + x // 2) % len(stock)]
                box(im, x, y, x + 1, y + 2, scale(c, 0.45) if broken else c)
        if broken:
            for i in range(9):  # a crack across the window
                put(im, 2 + i, 5 + (i * 7) // 9, rgb("#9fb3c9"))
            put(im, 11, 3, rgb("#ffe27a"))  # a spark
        for ky in (5, 7, 9):
            for kx in (11, 13):
                put(im, kx, ky, rgb("#8d97aa"))
        box(im, 11, 11, 14, 12, rgb("#07080b"))  # the coin slot
        box(im, 2, 13, 10, 15, rgb("#07080b"))  # the flap
        return im

    return paint


def traffic_side(signal: str) -> Callable[[random.Random], Image.Image]:
    """A traffic lamp's side (the box is 8 px wide: pixels 4..11): housing with red, amber and green lenses, one of them lit."""
    lenses = {"red": ((8.0, 3.5), "#ff3b3b"), "amber": ((8.0, 8.0), "#ffb21f"), "green": ((8.0, 12.5), "#2cff6b")}

    def paint(rnd: random.Random) -> Image.Image:
        im = clear()
        base = rgb("#232832")
        for y in range(SIZE):
            for x in range(4, 12):
                edge = x in (4, 11) or y in (0, SIZE - 1)
                put(im, x, y, scale(base, 1.3) if edge else jitter(base, rnd, 0.04))
        for name, ((cx, cy), colour) in lenses.items():
            core = rgb(colour)
            on = name == signal
            disc(im, cx, cy, 2.6, lambda d, core=core, on=on: mix(rgb("#ffffff"), core, d / 2.6) if on else scale(core, 0.3))
        return im

    return paint


def traffic_top(rnd: random.Random) -> Image.Image:
    im = canvas()
    fill_noise(im, rnd, rgb("#232832"), 0.04)
    return im


def lift_top(moving: bool) -> Callable[[random.Random], Image.Image]:
    """The lift platform's walking surface: a steel plate, a hazard border and three arrow chevrons (amber lit while it moves)."""

    def paint(rnd: random.Random) -> Image.Image:
        im = canvas()
        plate = rgb("#4a5260")
        fill_noise(im, rnd, plate, 0.05)
        hazard(im, 0, 0, SIZE, 2)
        hazard(im, 0, SIZE - 2, SIZE, SIZE)
        chevron = rgb("#ffb21f") if moving else rgb("#8a6a2a")
        for cy in (4, 7, 10):
            for i in range(5):
                put(im, 8 - i, cy + i // 2, chevron)
                put(im, 7 + i, cy + i // 2, chevron)
        for i in range(2, SIZE - 2):
            put(im, 0, i, scale(plate, 1.3))
            put(im, SIZE - 1, i, scale(plate, 0.6))
        return im

    return paint


def lift_edge(rnd: random.Random) -> Image.Image:
    im = canvas()
    hazard(im, 0, 0, SIZE, SIZE)
    return im


def call_plate(lit: bool) -> Callable[[random.Random], Image.Image]:
    """The lift call button (the plate fills pixels 4..11 across and 3..12 down): up and down arrows over a round button."""

    def paint(rnd: random.Random) -> Image.Image:
        im = clear()
        plate = rgb("#323846")
        for y in range(3, 13):
            for x in range(4, 12):
                edge = x in (4, 11) or y in (3, 12)
                put(im, x, y, scale(plate, 1.35) if edge else jitter(plate, rnd, 0.04))
        arrow = rgb("#aab4c8")
        for dx, dy in ((0, 0), (-1, 1), (1, 1)):  # an up arrow
            put(im, 8 + dx, 4 + dy, arrow)
        for dx, dy in ((0, 1), (-1, 0), (1, 0)):  # a down arrow
            put(im, 8 + dx, 6 + dy, arrow)
        core = rgb("#ffb21f")
        disc(im, 8, 9.6, 1.9, lambda d: mix(rgb("#ffffff"), core, d / 1.9) if lit else scale(core, 0.35))
        return im

    return paint


def turret_front(led_hex: str) -> Callable[[random.Random], Image.Image]:
    """The turret face: a dark barrel ring in the middle (like a dispenser, but armed) and a mode LED above it."""
    led = rgb(led_hex)

    def paint(rnd: random.Random) -> Image.Image:
        im = housing(rnd, rgb("#2a2e38"))
        disc(im, 8, 9, 5.2, lambda d: scale(rgb("#8a93a6"), 1.1 - 0.07 * d))
        disc(im, 8, 9, 3.8, lambda d: scale(rgb("#0a0b10"), 1 + 0.05 * d))
        disc(im, 8, 9, 1.4, lambda d: rgb("#000000"))
        put(im, 7, 8, rgb("#3a3f4c"))
        box(im, 6, 2, 10, 4, scale(led, 0.35))
        box(im, 7, 2, 9, 3, led)
        return im

    return paint


def turret_side(rnd: random.Random) -> Image.Image:
    im = housing(rnd, rgb("#2a2e38"))
    hazard(im, 2, 12, SIZE - 2, 14)
    for x in range(3, 13, 3):
        put(im, x, 4, scale(HOUSING, 1.5))
        put(im, x, 5, scale(HOUSING, 0.4))
    return im


def turret_top(rnd: random.Random) -> Image.Image:
    im = housing(rnd, rgb("#2a2e38"))
    for y in range(3, 13, 3):
        for x in range(3, 13):
            put(im, x, y, scale(HOUSING, 0.4))
    return im


def gate_panel(rnd: random.Random) -> Image.Image:
    """A sliding-gate panel: dark steel, vertical bars with a highlight each, hazard bands at the top and bottom."""
    im = canvas()
    steel = rgb("#6b7587")
    fill_noise(im, rnd, scale(steel, 0.55), 0.04)
    for x in range(1, SIZE, 3):
        for y in range(2, SIZE - 2):
            put(im, x, y, scale(steel, 1.15))
            put(im, x + 1, y, scale(steel, 0.8))
    hazard(im, 0, 0, SIZE, 2)
    hazard(im, 0, SIZE - 2, SIZE, SIZE)
    return im


def gate_rail(rnd: random.Random) -> Image.Image:
    im = canvas()
    fill_noise(im, rnd, rgb("#4f5766"), 0.05)
    for y, f in ((5, 0.5), (6, 1.35)):
        for x in range(SIZE):
            put(im, x, y, scale(rgb("#4f5766"), f))
    return im


def gate_end(rnd: random.Random) -> Image.Image:
    im = canvas()
    fill_noise(im, rnd, rgb("#394050"), 0.05)
    return im


def crate_side(rnd: random.Random) -> Image.Image:
    """A closed crate: horizontal boards inside a dark metal frame with a rivet at each corner."""
    im = canvas()
    wood = rgb("#7a6a4a")
    for y in range(SIZE):
        tone = 0.9 if y % 4 == 3 else 1 + 0.04 * ((y // 4) % 2)
        for x in range(SIZE):
            put(im, x, y, jitter(scale(wood, tone), rnd, 0.04))
    frame = rgb("#2d323d")
    for i in range(SIZE):
        for t in (0, 1, SIZE - 2, SIZE - 1):
            put(im, i, t, frame)
            put(im, t, i, frame)
    for x in (2, SIZE - 3):
        for y in (2, SIZE - 3):
            put(im, x, y, rgb("#9aa3b5"))
    return im


def crate_top(rnd: random.Random) -> Image.Image:
    im = crate_side(rnd)
    for i in range(2, SIZE - 2):
        put(im, i, i, rgb("#2d323d"))
        put(im, SIZE - 1 - i, i, rgb("#2d323d"))
    return im


def screen_front(on: bool) -> Callable[[random.Random], Image.Image]:
    """The screen frame's face: a bezel round a glass the map image hangs in front of (on: a soft blue static with scan lines)."""

    def paint(rnd: random.Random) -> Image.Image:
        im = housing(rnd, rgb("#20242e"))
        for y in range(2, 14):
            for x in range(2, 14):
                if on:
                    c = scale(rgb("#3fa6d9"), 0.55 + 0.25 * rnd.random())
                    c = scale(c, 0.8) if y % 2 else c
                else:
                    c = jitter(rgb("#0a0d14"), rnd, 0.1)
                put(im, x, y, c)
        return im

    return paint


# --- device blocks: textures, models and blockstates ------------------------------------------------------------------------
@dataclass
class DeviceBlock:
    id: str
    name: str  # English display name (kept in lang/en_us.json by hand; tests compare)
    props: dict[str, tuple[str, ...]]  # every property but quest_locked (the crate has none and is not lockable)
    textures: dict[str, Image.Image]  # texture stem -> image
    models: dict[str, dict]  # model stem -> model json
    model_of: Callable[[dict[str, str]], str]  # a state (quest_locked included unless not lockable) -> its model stem
    item_model: str  # the model stem the inventory item shows
    lockable: bool = True

    def all_props(self) -> dict[str, tuple[str, ...]]:
        props = dict(self.props)
        if self.lockable:
            props["quest_locked"] = ("false", "true")
        return dict(sorted(props.items()))


FACINGS = ("east", "north", "south", "west")
FULL = ([0, 0, 0], [16, 16, 16])


def cull(face: str, frm: list[int], to: list[int]) -> bool:
    """A face is culled against its neighbour only when it lies on the cell boundary and covers the whole cell face."""
    axis, edge = {"down": (1, 0), "up": (1, 16), "north": (2, 0), "south": (2, 16), "west": (0, 0), "east": (0, 16)}[face]
    others = [i for i in range(3) if i != axis]
    return (frm[axis] if edge == 0 else to[axis]) == edge and all(frm[i] == 0 and to[i] == 16 for i in others)


def element(frm: list[int], to: list[int], faces: dict[str, str | tuple[str, list[int]]], emission: int = 0) -> dict:
    """One model element; a face is a texture key or (texture key, [u0, v0, u1, v1])."""
    out: dict[str, dict] = {}
    for face, spec in faces.items():
        tex, uv = (spec, None) if isinstance(spec, str) else spec
        f: dict = {"texture": f"#{tex}"}
        if uv:
            f["uv"] = uv
        if cull(face, frm, to):
            f["cullface"] = face
        out[face] = f
    e: dict = {"from": frm, "to": to, "faces": out}
    if emission:
        e["light_emission"] = emission
    return e


def model(textures: dict[str, str], elements: list[dict], particle: str, ao: bool = True) -> dict:
    """A block model; texture values are stems under textures/block/."""
    out: dict = {} if ao else {"ambientocclusion": False}
    out["textures"] = {**{k: f"{NAMESPACE}:block/{v}" for k, v in textures.items()}, "particle": f"#{particle}"}
    out["elements"] = elements
    return out


def cube(front: str, side: str, top: str, emission: int = 0) -> dict:
    """A full cube with a front (north) face, like the vanilla orientable model; the arguments are texture stems."""
    faces = {"down": "top", "up": "top", "north": "front", "south": "side", "west": "side", "east": "side"}
    return model({"front": front, "side": side, "top": top}, [element(*FULL, faces, emission)], "side", ao=not emission)


def one_texture_cube(stem: str) -> dict:
    return cube(stem, stem, stem)


def mode_pick(prefix: str, key: str) -> Callable[[dict[str, str]], str]:
    """model_of for blocks whose model depends on one property and on quest_locked."""
    return lambda s: f"{prefix}_quest_locked" if s["quest_locked"] == "true" else f"{prefix}_{s[key]}"


def lamp_block(dye: str, hexcol: str) -> DeviceBlock:
    bid = f"lamp_{dye}"
    textures = {f"{bid}_lit": lamp(hexcol, True)(random.Random(f"{bid}_lit")), f"{bid}_off": lamp(hexcol, False)(random.Random(f"{bid}_off"))}
    models = {f"{bid}_lit": model({"all": f"{bid}_lit"}, [element(*FULL, {f: "all" for f in FACES}, 15)], "all", ao=False),
              f"{bid}_off": model({"all": f"{bid}_off"}, [element(*FULL, {f: "all" for f in FACES})], "all")}

    def pick(s: dict[str, str]) -> str:
        return "lamp_quest_locked" if s["quest_locked"] == "true" else f"{bid}_{'lit' if s['lit'] == 'true' else 'off'}"

    return DeviceBlock(bid, "Night City Lamp (" + dye.replace("_", " ").title() + ")", {"lit": ("false", "true")}, textures, models, pick,
                       f"{bid}_lit")


def shared_lamp_locked() -> tuple[dict[str, Image.Image], dict[str, dict]]:
    """The one greyed lamp every colour shares."""
    texture = greyed(lamp("#9aa3b8", False)(random.Random("lamp_quest_locked")))
    return {"lamp_quest_locked": texture}, {"lamp_quest_locked": one_texture_cube("lamp_quest_locked")}


def eye_camera() -> DeviceBlock:
    modes = {"off": "#3a4254", "on": "#2fe0ff", "alarm": "#ff2d2d", "friendly": "#35ff7a"}
    textures = {f"eye_camera_front_{m}": lens_front(h)(random.Random(f"eye_camera_front_{m}")) for m, h in modes.items()}
    textures["eye_camera_side"] = vent_side(random.Random("eye_camera_side"))
    textures["eye_camera_top"] = cap_top(random.Random("eye_camera_top"))
    models = {f"eye_camera_{m}": cube(f"eye_camera_front_{m}", "eye_camera_side", "eye_camera_top") for m in modes}
    textures["eye_camera_quest_locked"] = greyed(textures["eye_camera_front_on"])
    models["eye_camera_quest_locked"] = one_texture_cube("eye_camera_quest_locked")
    return DeviceBlock("eye_camera", "Eye Camera", {"facing": FACINGS, "mode": tuple(sorted(modes))}, textures, models,
                       mode_pick("eye_camera", "mode"), "eye_camera_on")


def net_port() -> DeviceBlock:
    states = {"locked": "#ffb21f", "breached": "#35ff7a"}
    textures = {f"net_port_front_{k}": net_port_front(h)(random.Random(f"net_port_front_{k}")) for k, h in states.items()}
    textures["net_port_side"] = vent_side(random.Random("net_port_side"))
    textures["net_port_top"] = plain_housing(random.Random("net_port_top"))
    models = {f"net_port_{k}": cube(f"net_port_front_{k}", "net_port_side", "net_port_top") for k in states}
    textures["net_port_quest_locked"] = greyed(textures["net_port_front_locked"])
    models["net_port_quest_locked"] = one_texture_cube("net_port_quest_locked")

    def pick(s: dict[str, str]) -> str:
        return "net_port_quest_locked" if s["quest_locked"] == "true" else f"net_port_{'breached' if s['breached'] == 'true' else 'locked'}"

    return DeviceBlock("net_port", "Net Port", {"breached": ("false", "true"), "facing": FACINGS}, textures, models, pick, "net_port_locked")


def vending_machine() -> DeviceBlock:
    textures = {f"vending_machine_front{k}": vending_front(b)(random.Random(f"vending_machine_front{k}")) for k, b in (("", False), ("_broken", True))}
    side = housing(random.Random("vending_machine_side"), rgb("#252a35"))
    box(side, 4, 3, 12, 5, rgb("#ff2fb3"))
    textures["vending_machine_side"] = side
    textures["vending_machine_top"] = plain_housing(random.Random("vending_machine_top"))
    models = {f"vending_machine{k}": cube(f"vending_machine_front{k}", "vending_machine_side", "vending_machine_top") for k in ("", "_broken")}
    textures["vending_machine_quest_locked"] = greyed(textures["vending_machine_front"])
    models["vending_machine_quest_locked"] = one_texture_cube("vending_machine_quest_locked")

    def pick(s: dict[str, str]) -> str:
        return "vending_machine_quest_locked" if s["quest_locked"] == "true" else "vending_machine" + ("_broken" if s["broken"] == "true" else "")

    return DeviceBlock("vending_machine", "Vending Machine", {"broken": ("false", "true"), "facing": FACINGS}, textures, models, pick,
                       "vending_machine")


def pole(side: str, emission: int = 0) -> dict:
    """The traffic lamp: an 8 x 16 x 8 box in the middle of its cell, the four sides one texture, plain caps."""
    caps = ("top", [4, 4, 12, 12])
    faces = {"north": "side", "south": "side", "west": "side", "east": "side", "up": caps, "down": caps}
    return model({"side": side, "top": "traffic_lamp_top"}, [element([4, 0, 4], [12, 16, 12], faces, emission)], "side", ao=not emission)


def traffic_lamp() -> DeviceBlock:
    signals = ("off", "red", "amber", "green")
    textures = {f"traffic_lamp_{s}": traffic_side(s)(random.Random(f"traffic_lamp_{s}")) for s in signals}
    textures["traffic_lamp_top"] = traffic_top(random.Random("traffic_lamp_top"))
    models = {f"traffic_lamp_{s}": pole(f"traffic_lamp_{s}", 0 if s == "off" else 12) for s in signals}
    textures["traffic_lamp_quest_locked"] = greyed(textures["traffic_lamp_red"])
    models["traffic_lamp_quest_locked"] = pole("traffic_lamp_quest_locked")
    return DeviceBlock("traffic_lamp", "Traffic Lamp", {"signal": tuple(sorted(signals))}, textures, models, mode_pick("traffic_lamp", "signal"),
                       "traffic_lamp_green")


def lift_platform() -> DeviceBlock:
    textures = {"lift_platform_top": lift_top(False)(random.Random("lift_platform_top")),
                "lift_platform_top_moving": lift_top(True)(random.Random("lift_platform_top_moving")),
                "lift_platform_edge": lift_edge(random.Random("lift_platform_edge"))}
    textures["lift_platform_quest_locked"] = greyed(textures["lift_platform_top"])

    def slab(top: str, edge: str, emission: int = 0) -> dict:
        side = ("edge", [0, 0, 16, 4])
        el = element([0, 0, 0], [16, 4, 16], {"down": "edge", "up": "top", "north": side, "south": side, "west": side, "east": side}, emission)
        return model({"top": top, "edge": edge}, [el], "top")

    models = {"lift_platform": slab("lift_platform_top", "lift_platform_edge"),
              "lift_platform_moving": slab("lift_platform_top_moving", "lift_platform_edge", 6),
              "lift_platform_quest_locked": slab("lift_platform_quest_locked", "lift_platform_quest_locked")}

    def pick(s: dict[str, str]) -> str:
        return "lift_platform_quest_locked" if s["quest_locked"] == "true" else "lift_platform" + ("_moving" if s["moving"] == "true" else "")

    return DeviceBlock("lift_platform", "Lift Platform", {"moving": ("false", "true")}, textures, models, pick, "lift_platform")


def lift_call_button() -> DeviceBlock:
    textures = {"lift_call_button": call_plate(False)(random.Random("lift_call_button")),
                "lift_call_button_lit": call_plate(True)(random.Random("lift_call_button_lit")),
                "lift_call_button_edge": plain_housing(random.Random("lift_call_button_edge"))}
    textures["lift_call_button_quest_locked"] = greyed(textures["lift_call_button"])

    def plate(front: str, emission: int = 0, edge: str = "lift_call_button_edge") -> dict:
        frm, to = [4, 3, 14], [12, 13, 16]  # on the south edge of its cell; the plate looks north (facing=north)
        faces = {"north": "front", "south": ("edge", [0, 0, 8, 10]), "west": ("edge", [0, 0, 2, 10]), "east": ("edge", [0, 0, 2, 10]),
                 "up": ("edge", [0, 0, 8, 2]), "down": ("edge", [0, 0, 8, 2])}
        return model({"front": front, "edge": edge}, [element(frm, to, faces, emission)], "edge")

    models = {"lift_call_button": plate("lift_call_button"), "lift_call_button_lit": plate("lift_call_button_lit", 8),
              "lift_call_button_quest_locked": plate("lift_call_button_quest_locked", 0, "lift_call_button_quest_locked")}

    def pick(s: dict[str, str]) -> str:
        return "lift_call_button_quest_locked" if s["quest_locked"] == "true" else "lift_call_button" + ("_lit" if s["lit"] == "true" else "")

    return DeviceBlock("lift_call_button", "Lift Call Button", {"facing": FACINGS, "lit": ("false", "true")}, textures, models, pick,
                       "lift_call_button")


def turret() -> DeviceBlock:
    modes = {"off": "#4a5262", "active": "#ff2d2d", "friendly": "#35ff7a"}
    textures = {f"turret_front_{m}": turret_front(h)(random.Random(f"turret_front_{m}")) for m, h in modes.items()}
    textures["turret_side"] = turret_side(random.Random("turret_side"))
    textures["turret_top"] = turret_top(random.Random("turret_top"))
    models = {f"turret_{m}": cube(f"turret_front_{m}", "turret_side", "turret_top") for m in modes}
    textures["turret_quest_locked"] = greyed(textures["turret_front_active"])
    models["turret_quest_locked"] = one_texture_cube("turret_quest_locked")
    return DeviceBlock("turret", "Security Turret", {"facing": FACINGS, "mode": tuple(sorted(modes))}, textures, models,
                       mode_pick("turret", "mode"), "turret_off")


def sliding_gate() -> DeviceBlock:
    textures = {"sliding_gate_panel": gate_panel(random.Random("sliding_gate_panel")),
                "sliding_gate_rail": gate_rail(random.Random("sliding_gate_rail")),
                "sliding_gate_end": gate_end(random.Random("sliding_gate_end"))}
    textures["sliding_gate_quest_locked"] = greyed(textures["sliding_gate_panel"])

    def gate(step: int, panel: str, rail: str, end: str) -> dict:
        elements = [element([0, 0, 5], [16, 1, 11], {"down": "rail", "up": "rail", "north": "rail", "south": "rail", "west": "end", "east": "end"})]
        width = 16 - 4 * step  # the panel retracts towards the west end (x = 0); step 4 leaves only the rail
        if width:
            faces = {"north": "panel", "south": "panel", "up": ("end", [0, 0, width, 4]), "down": ("end", [0, 0, width, 4]),
                     "east": ("end", [0, 0, 4, 16])}
            if step == 0:
                faces["west"] = ("end", [0, 0, 4, 16])
            elements.append(element([0, 1, 6], [width, 16, 10], faces))
        return model({"panel": panel, "rail": rail, "end": end}, elements, "end")

    models = {}
    for step in range(5):
        models[f"sliding_gate_s{step}"] = gate(step, "sliding_gate_panel", "sliding_gate_rail", "sliding_gate_end")
        models[f"sliding_gate_quest_locked_s{step}"] = gate(step, *("sliding_gate_quest_locked",) * 3)

    def pick(s: dict[str, str]) -> str:
        return f"sliding_gate{'_quest_locked' if s['quest_locked'] == 'true' else ''}_s{s['step']}"

    return DeviceBlock("sliding_gate", "Sliding Gate", {"facing": FACINGS, "step": tuple(str(i) for i in range(5))}, textures, models, pick,
                       "sliding_gate_s0")


def screen_frame() -> DeviceBlock:
    textures = {f"screen_frame_front_{k}": screen_front(k == "on")(random.Random(f"screen_frame_front_{k}")) for k in ("off", "on")}
    textures["screen_frame_side"] = plain_housing(random.Random("screen_frame_side"))
    textures["screen_frame_quest_locked"] = greyed(textures["screen_frame_front_on"])
    models = {"screen_frame_off": cube("screen_frame_front_off", "screen_frame_side", "screen_frame_side"),
              "screen_frame_on": cube("screen_frame_front_on", "screen_frame_side", "screen_frame_side", 8),
              "screen_frame_quest_locked": one_texture_cube("screen_frame_quest_locked")}

    def pick(s: dict[str, str]) -> str:
        return "screen_frame_quest_locked" if s["quest_locked"] == "true" else f"screen_frame_{'on' if s['on'] == 'true' else 'off'}"

    return DeviceBlock("screen_frame", "Screen Frame", {"facing": FACINGS, "on": ("false", "true")}, textures, models, pick, "screen_frame_off")


def crate() -> DeviceBlock:
    textures = {"crate_side": crate_side(random.Random("crate_side")), "crate_top": crate_top(random.Random("crate_top"))}
    return DeviceBlock("crate", "Crate", {}, textures, {"crate": cube("crate_side", "crate_side", "crate_top")}, lambda s: "crate", "crate",
                       lockable=False)


DEVICES: list[DeviceBlock] = [*(lamp_block(dye, hexcol) for dye, hexcol in NEON_DYES.items()), eye_camera(), net_port(), vending_machine(),
                              traffic_lamp(), lift_platform(), lift_call_button(), turret(), sliding_gate(), screen_frame(), crate()]


def blockstate(block: DeviceBlock) -> dict:
    """Every combination of the block's properties (alphabetical in the key, as the vanilla files are), rotated by facing."""
    props = block.all_props()
    names = list(props)
    variants: dict[str, dict] = {}
    for combo in itertools.product(*(props[n] for n in names)):
        state = dict(zip(names, combo))
        entry: dict = {"model": f"{NAMESPACE}:block/{block.model_of(state)}"}
        if FACING_Y.get(state.get("facing"), 0):
            entry["y"] = FACING_Y[state["facing"]]
        variants[",".join(f"{n}={state[n]}" for n in names)] = entry
    return {"variants": variants}


# --- the eddies items (megaplan 1.16): a coin and a credchip worth 100, original 16 x 16 art -------------------------------
def eddies_coin() -> Image.Image:
    im = clear()
    gold = rgb("#e8b82e")
    disc(im, 8, 8, 7.0, lambda d: rgb("#8a6410"))
    disc(im, 8, 8, 6.1, lambda d: scale(gold, 1.12 - 0.1 * d / 6))
    disc(im, 8, 8, 4.9, lambda d: scale(gold, 0.82))
    disc(im, 8, 8, 4.3, lambda d: scale(gold, 0.98 - 0.05 * d / 4))
    glyph = rgb("#12c9de")  # an E with a double bar through its stem, in a cyan inlay
    for y in range(5, 11):
        put(im, 6, y, glyph)
    for y in (5, 7, 10):
        for x in range(6, 10):
            put(im, x, y, glyph)
    for x in (4, 5):
        put(im, x, 6, scale(glyph, 0.75))
        put(im, x, 8, scale(glyph, 0.75))
    for x, y in ((4, 3), (5, 3), (4, 4)):  # the glint
        put(im, x, y, rgb("#fff3b0"))
    return im


def eddie_chip() -> Image.Image:
    im = clear()
    body = rgb("#1b2232")
    for y in range(3, 13):
        for x in range(2, 14):
            put(im, x, y, scale(body, 1.5 if x in (2, 13) or y in (3, 12) else 1.0))
    for x, y in ((12, 3), (13, 3), (13, 4)):  # a cut corner
        put(im, x, y, (0, 0, 0), 0)
    gold = rgb("#e8b82e")
    box(im, 3, 5, 7, 10, gold)
    for y in (6, 8):
        for x in range(3, 7):
            put(im, x, y, scale(gold, 0.7))
    put(im, 5, 5, scale(gold, 0.7))
    put(im, 5, 9, scale(gold, 0.7))
    box(im, 9, 5, 13, 6, rgb("#12c9de"))
    for x, y in ((9, 8), (11, 8), (12, 8), (9, 10), (11, 10)):  # value marks
        put(im, x, y, rgb("#eef2f7"))
    return im


ITEMS: dict[str, tuple[str, Callable[[], Image.Image]]] = {"eddies": ("Eddies", eddies_coin), "eddie_chip": ("Eddie Chip", eddie_chip)}


def png_bytes(im: Image.Image) -> bytes:
    buf = io.BytesIO()
    im.save(buf, format="PNG", optimize=False, compress_level=9)
    return buf.getvalue()


def json_bytes(data: dict) -> bytes:
    return (json.dumps(data, indent="\t") + "\n").encode("utf-8")


def device_files() -> dict[str, bytes]:
    """Every file of the device blocks and the eddies items as {path under assets/cyberblock: bytes}."""
    files: dict[str, bytes] = {}

    def add(path: str, data: bytes) -> None:
        assert files.setdefault(path, data) == data, f"{path} would be written twice with different content"

    shared_tex, shared_models = shared_lamp_locked()
    for stem, im in shared_tex.items():
        add(f"textures/block/{stem}.png", png_bytes(im))
    for stem, data in shared_models.items():
        add(f"models/block/{stem}.json", json_bytes(data))
    for block in DEVICES:
        for stem, im in block.textures.items():
            add(f"textures/block/{stem}.png", png_bytes(im))
        for stem, data in block.models.items():
            add(f"models/block/{stem}.json", json_bytes(data))
        add(f"blockstates/{block.id}.json", json_bytes(blockstate(block)))
        add(f"items/{block.id}.json", json_bytes({"model": {"type": "minecraft:model", "model": f"{NAMESPACE}:block/{block.item_model}"}}))
    for item, (_name, paint) in ITEMS.items():
        add(f"textures/item/{item}.png", png_bytes(paint()))
        add(f"models/item/{item}.json", json_bytes({"parent": "minecraft:item/generated", "textures": {"layer0": f"{NAMESPACE}:item/{item}"}}))
        add(f"items/{item}.json", json_bytes({"model": {"type": "minecraft:model", "model": f"{NAMESPACE}:item/{item}"}}))
    return files


def device_preview(files: dict[str, bytes]) -> Image.Image:
    """A contact sheet of every device texture (name order) at 4x on a checkerboard, then the two eddies items at 8x."""
    paths = sorted(p for p in files if p.startswith("textures/block/"))
    zoom, cols = 4, 12
    rows = -(-len(paths) // cols)
    sheet = Image.new("RGBA", (cols * SIZE * zoom, rows * SIZE * zoom + SIZE * 8 + 8), (0, 0, 0, 255))
    for y in range(sheet.height):
        for x in range(sheet.width):
            sheet.putpixel((x, y), (46, 48, 58, 255) if (x // 8 + y // 8) % 2 else (62, 64, 76, 255))
    for n, path in enumerate(paths):
        tex = Image.open(io.BytesIO(files[path])).convert("RGBA").resize((SIZE * zoom, SIZE * zoom), Image.NEAREST)
        sheet.alpha_composite(tex, ((n % cols) * SIZE * zoom, (n // cols) * SIZE * zoom))
    for n, item in enumerate(ITEMS):
        tex = Image.open(io.BytesIO(files[f"textures/item/{item}.png"])).convert("RGBA").resize((SIZE * 8, SIZE * 8), Image.NEAREST)
        sheet.alpha_composite(tex, (n * (SIZE * 8 + 8), rows * SIZE * zoom + 8))
    return sheet


# --- output ---------------------------------------------------------------------------------------------------------------
def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(data, indent="\t") + "\n").encode("utf-8"))


def write_png(path: Path, im: Image.Image) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, format="PNG", optimize=False, compress_level=9)


def write_block(block: BlockSpec) -> Image.Image:
    texture = block.paint(random.Random(block.id))
    write_png(ASSETS / "textures" / "block" / f"{block.id}.png", texture)
    write_json(ASSETS / "models" / "block" / f"{block.id}.json", MODELS[block.model](block))
    write_json(ASSETS / "blockstates" / f"{block.id}.json", {"variants": {"": {"model": f"{NAMESPACE}:block/{block.id}"}}})
    write_json(ASSETS / "items" / f"{block.id}.json", {"model": {"type": "minecraft:model", "model": f"{NAMESPACE}:block/{block.id}"}})
    return texture


def preview(tiles: list[Image.Image], skin: Image.Image) -> Image.Image:
    """A contact sheet on a checkerboard (so the alpha of glass and water shows): the blocks at 4x, the skin at 3x."""
    zoom, cols = 4, 7
    rows = -(-len(tiles) // cols)
    sheet = Image.new("RGBA", (cols * SIZE * zoom + 8 + 64 * 3, max(rows * SIZE * zoom, 64 * 3)), (0, 0, 0, 255))
    for y in range(sheet.height):
        for x in range(sheet.width):
            sheet.putpixel((x, y), (46, 48, 58, 255) if (x // 8 + y // 8) % 2 else (62, 64, 76, 255))
    for n, tex in enumerate(tiles):
        big = tex.resize((SIZE * zoom, SIZE * zoom), Image.NEAREST)
        sheet.alpha_composite(big, ((n % cols) * SIZE * zoom, (n // cols) * SIZE * zoom))
    sheet.alpha_composite(skin.resize((64 * 3, 64 * 3), Image.NEAREST), (cols * SIZE * zoom + 8, 0))
    return sheet


def main() -> None:
    tiles = [write_block(block) for block in BLOCKS]
    skin = figure_skin()
    write_png(ASSETS / "textures" / "entity" / "figure" / "neutral.png", skin)
    write_png(ART / "block_preview.png", preview(tiles, skin))
    files = device_files()
    for rel, data in files.items():
        (ASSETS / rel).parent.mkdir(parents=True, exist_ok=True)
        (ASSETS / rel).write_bytes(data)
    write_png(ART / "device_preview.png", device_preview(files))
    print(f"wrote {len(BLOCKS)} blocks, the neutral figure skin, {len(DEVICES)} device blocks and {len(ITEMS)} eddies items ({len(files)} files)")


if __name__ == "__main__":
    main()
