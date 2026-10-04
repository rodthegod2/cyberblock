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

import json
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
    print(f"wrote {len(BLOCKS)} blocks and the neutral figure skin")


if __name__ == "__main__":
    main()
