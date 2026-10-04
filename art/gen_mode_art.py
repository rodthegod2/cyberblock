"""Procedural, original Phase 8 art: the Overlord Console, the Trauma Beacon, the Hacker Van, the drone, the Trauma Team flyer and the Netrunner implant
(megaplan 8-A1).

Run from anywhere:  uv run --with pillow python art/gen_mode_art.py

Deterministic (every texture is drawn from a seed made of its own name), original (nothing is read from the game or from Minecraft) and built on the
helpers of gen_block_art.py (housing, discs, the model and blockstate writers), gen_item_art.py (the Sprite painter) and gen_vehicle_art.py (the
vehicle Painter, whose van and aerodyne shaders the van and the flyer reuse). The script owns exactly these ids, which the Phase 8 packages use by name
(8O the console, 8T the beacon and the flyer, 8V the van and the drone, 8X the implant):

  blocks
    overlord_console  facing east|north|south|west   the Overlord Console (8O): a desk with a top-down map screen; full block item
    trauma_beacon     lit false|true                 the Trauma Beacon (8T): a flare post on a plate; lit while the Trauma Team flyer is on its way
  items
    hacker_van        the placeable Van (8V)         hacker_drone  the drone the netrunner flies (8V)         netrunner  the implant (8X, megaplan 1.17)
  entity textures (under textures/entity/mode/, no model json: the Java renderer builds the boxes of the tables below)
    hacker_van.png (+ _detail)         the Van: the Phase 5 `van` body and wheels plus a roof rack, dish and two masts. Paint (tintable greyscale) and
                                       detail (drawn untinted over it), exactly the layers of gen_vehicle_art.py. Default colour #2b3340. 128 x 128
    hacker_drone.png (+ _glow)         the drone: opaque paint, and a glow layer (the camera lens and the arm lights) drawn full bright. 64 x 64
    trauma_flyer.png (+ _detail, _lights_a, _lights_b)  the Trauma Team flyer: the Phase 5 `trauma_aerodyne` hull and light bar plus a roof cross pad and
                                       a winch; the two light layers alternate while it flies in. Default colour #e8ecef. 128 x 128
and the contact sheet art/mode_preview.png. The English names are kept in lang/en_us.json by hand (a test compares them).

UV LAYOUT (the contract with the Java renderers of 8V and 8T). Every part is a box given exactly as Java builds it:
`CubeListBuilder.create().texOffs(u, v).addBox(x, y, z, w, h, d)` in a model with the texture size of its row, all parts in one root at PartPose.ZERO, no
mirror, no inflation. Model pixels with the vanilla ModelPart axes (y points DOWN). The van and the flyer follow gen_vehicle_art.py to the letter (y = 0 is
the ground, the nose points to -z, one pixel is 1/8 block at 2x); the drone's origin is its centre and one pixel is 1/16 block. A part listed with several
positions is one UV region shared by several boxes (the wheels, the rotors). The faces unwrap the vanilla way (art/skin_layout.py `cuboid_faces`).

    model            part        u    v     w    h    d   at (x, y, z) of each box
    hacker_van       body        0    0    16   13   34   -8,-16,-17
    hacker_van       wheel       0   47     3    6    6   -9,-6,-13  6,-6,-13  -9,-6,8  6,-6,8
    hacker_van       dish       20   47     8    2    8   -4,-19,-8
    hacker_van       mast       54   47     2    8    2   -6,-24,9  4,-24,9
    hacker_van       rack        0   64    12    1   16   -6,-17,-12
    hacker_drone     body        0    0     6    3    6   -3,-2,-3
    hacker_drone     eye         0   10     4    3    2   -2,-1,-5
    hacker_drone     arm_x      26    0    14    1    2   -7,-1,-1
    hacker_drone     arm_z      26    4     2    1   14   -1,-1,-7
    hacker_drone     rotor       0   20     4    1    4   -9,-2,-2  5,-2,-2  -2,-2,-9  -2,-2,5
    hacker_drone     skid        0   28     1    1    8   -3,1,-4  2,1,-4
    hacker_drone     antenna    20   22     1    4    1   1,-6,1
    trauma_flyer     fuselage    0    0    16    8   34   -8,-14,-17
    trauma_flyer     cockpit     0   42    12    4   10   -6,-18,-15
    trauma_flyer     pod        44   42     6    4    8   -14,-10,-14  8,-10,-14  -14,-10,6  8,-10,6
    trauma_flyer     fin        72   42     2    4    8   -1,-18,9
    trauma_flyer     light_bar  92   42     8    1    2   -4,-15,0
    trauma_flyer     pad         0   64    10    1    6   -5,-15,3
    trauma_flyer     winch      40   64     4    3    4   -2,-6,-4

The first five trauma_flyer rows are the `trauma_aerodyne` rows of gen_vehicle_art.py (the same geometry, so one model class draws both). A test checks
that this table and PARTS below are the same, that every region fits its texture and that no two regions of a texture overlap.
"""
from __future__ import annotations

import io
import random
import re
import sys
from collections.abc import Callable
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import gen_block_art as gb  # noqa: E402
import gen_item_art as gi  # noqa: E402
import gen_vehicle_art as gv  # noqa: E402
from skin_layout import cuboid_faces  # noqa: E402

ROOT = HERE.parent
ASSETS = gb.ASSETS
NAMESPACE = gb.NAMESPACE
SIZE = gb.SIZE
rgb, scale, mix, put, box, disc, housing, canvas, fill_noise = gb.rgb, gb.scale, gb.mix, gb.put, gb.box, gb.disc, gb.housing, gb.canvas, gb.fill_noise
CYAN, MAGENTA, AMBER, RED, GREEN = rgb("#2fe0ff"), rgb("#ff2fb3"), rgb("#ffb21f"), rgb("#ff3b3b"), rgb("#35ff7a")
WHITE = rgb("#f2fbff")


# --- block textures ----------------------------------------------------------------------------------------------------------
def console_screen(rnd: random.Random) -> Image.Image:
    """The Overlord Console's screen: a Threat Point gauge, a top-down map (a grid, two streets, lit blocks, hostile dots, players and a
    magenta event ring) and the event timeline underneath with a cursor."""
    im = canvas()
    fill_noise(im, rnd, rgb("#050b16"), 0.04)
    for i in range(SIZE):  # the bezel
        for edge in (0, SIZE - 1):
            put(im, i, edge, rgb("#2a303d"))
            put(im, edge, i, rgb("#2a303d"))
    for x in range(2, 14):  # the Threat Point gauge, two thirds full
        put(im, x, 1, scale(AMBER, 0.95 if x < 10 else 0.28))
    for i in range(3, 12, 4):  # the grid
        for j in range(2, 14):
            put(im, i, j, scale(CYAN, 0.2))
            put(im, j, i, scale(CYAN, 0.2))
    for x in range(2, 14):  # the streets
        put(im, x, 7, scale(CYAN, 0.5))
    for y in range(2, 12):
        put(im, 7, y, scale(CYAN, 0.5))
    for _ in range(9):  # lit city blocks
        x, y = rnd.randrange(2, 13), rnd.randrange(3, 11)
        if (x, y) != (7, 7) and x != 7 and y != 7:
            put(im, x, y, scale(CYAN if rnd.random() < 0.7 else MAGENTA, 0.45 + 0.3 * rnd.random()))
    for x, y in ((4, 4), (11, 9), (9, 4)):  # hostile dots
        put(im, x, y, RED)
    for x, y in ((5, 9), (12, 5)):  # players
        put(im, x, y, WHITE)
    for y in range(2, 12):  # the event ring
        for x in range(2, 14):
            d2 = (x + 0.5 - 4.5) ** 2 + (y + 0.5 - 9.0) ** 2
            if 5.0 <= d2 <= 8.5:
                put(im, x, y, MAGENTA)
    for x in range(2, 14):  # the timeline
        put(im, x, 13, scale(CYAN, 0.45))
    for x in (3, 6, 9, 12):
        put(im, x, 12, scale(CYAN, 0.7))
        put(im, x, 14, scale(CYAN, 0.7))
    for x in (4, 10):  # queued events
        put(im, x, 13, AMBER)
    for y in (11, 12, 13, 14):  # the cursor
        put(im, 7, y, WHITE)
    return im


def console_front(rnd: random.Random) -> Image.Image:
    """The desk's front panel: a vent grille, three status lights and a magenta band."""
    im = housing(rnd, rgb("#262b36"))
    for y in (3, 5, 7):
        for x in range(3, 13):
            put(im, x, y, scale(gb.HOUSING, 0.35))
            put(im, x, y + 1, scale(gb.HOUSING, 1.3))
    for x, c in ((3, MAGENTA), (7, CYAN), (11, AMBER)):
        box(im, x, 11, x + 2, 13, c)
    box(im, 2, 14, 14, 15, scale(MAGENTA, 0.7))
    return im


def console_top(rnd: random.Random) -> Image.Image:
    im = housing(rnd, rgb("#262b36"))
    gb.hazard(im, 2, 14, SIZE - 2, 15)
    return im


def console_keys(rnd: random.Random) -> Image.Image:
    """The key slab seen from above: three rows of keys, three coloured ones and a trackball."""
    im = housing(rnd, rgb("#1d212c"))
    for row, y in enumerate((2, 5, 8)):
        for col in range(5):
            x = 2 + col * 2 + (row % 2)
            if x + 1 < SIZE - 1:
                hot = (row, col) in ((0, 1), (1, 3), (2, 0))
                key = MAGENTA if hot else rgb("#4a5266")
                box(im, x, y, x + 2, y + 2, scale(key, 0.85))
                put(im, x, y, scale(key, 1.25))
    disc(im, 12, 11.5, 2.2, lambda d: scale(rgb("#8a93a6"), 1.15 - 0.15 * d))
    put(im, 11, 10, WHITE)
    return im


def beacon_plate(rnd: random.Random) -> Image.Image:
    """The beacon's plate seen from above: white with a red cross and a hazard rim."""
    im = housing(rnd, rgb("#cfd6df"))
    gb.hazard(im, 0, 0, SIZE, 1)
    gb.hazard(im, 0, SIZE - 1, SIZE, SIZE)
    box(im, 6, 3, 10, 13, rgb("#d6283c"))
    box(im, 3, 6, 13, 10, rgb("#d6283c"))
    return im


def beacon_post(rnd: random.Random) -> Image.Image:
    """The post: a white pole with teal bands."""
    im = housing(rnd, rgb("#dde3ea"))
    for y in range(SIZE):
        if y % 6 in (2, 3):
            box(im, 0, y, SIZE, y + 1, scale(rgb("#25b39e"), 0.9))
    return im


def beacon_head(lit: bool) -> Callable[[random.Random], Image.Image]:
    """The beacon lamp: lit, a white-hot red lens with a bright rim; off, dull red glass."""
    def paint(rnd: random.Random) -> Image.Image:
        im = canvas()
        for y in range(SIZE):
            for x in range(SIZE):
                d = max(abs(x + 0.5 - 8), abs(y + 0.5 - 8))
                if lit:
                    c = mix(mix(RED, WHITE, 0.75), RED, d / 8.0)
                    c = scale(c, 0.8) if d > 7 else c
                else:
                    c = scale(mix(RED, rgb("#59606e"), 0.6), 0.32 + 0.08 * (8 - d) / 8)
                put(im, x, y, gb.jitter(c, rnd, 0.02))
        return im
    return paint


# --- the blocks ----------------------------------------------------------------------------------------------------------------
FOUR = ("north", "south", "west", "east")


def overlord_console() -> gb.DeviceBlock:
    textures = {f"overlord_console_{k}": paint(random.Random(f"overlord_console_{k}"))
                for k, paint in (("screen", console_screen), ("front", console_front), ("top", console_top), ("keys", console_keys))}
    textures["overlord_console_side"] = gb.vent_side(random.Random("overlord_console_side"))
    # a desk, a key slab on its front half and the screen standing on its back half; facing=north looks north (-z), so the screen faces north too
    elements = [
        gb.element([0, 0, 0], [16, 9, 16], {"north": "front", "south": "side", "west": "side", "east": "side", "up": "top", "down": "top"}),
        gb.element([2, 9, 1], [14, 10, 7], {**{f: "side" for f in FOUR}, "up": "keys", "down": "top"}),
        gb.element([1, 9, 9], [15, 16, 12], {"north": "screen", "south": "side", "west": "side", "east": "side", "up": "top", "down": "top"}, 8),
    ]
    mdl = gb.model({k: f"overlord_console_{k}" for k in ("screen", "front", "top", "keys", "side")}, elements, "side", ao=False)
    return gb.DeviceBlock("overlord_console", "Overlord Console", {"facing": gb.FACINGS}, textures, {"overlord_console": mdl},
                          lambda s: "overlord_console", "overlord_console", lockable=False)


def trauma_beacon() -> gb.DeviceBlock:
    textures = {"trauma_beacon_plate": beacon_plate(random.Random("trauma_beacon_plate")), "trauma_beacon_post": beacon_post(random.Random("trauma_beacon_post")),
                "trauma_beacon_lit": beacon_head(True)(random.Random("trauma_beacon_lit")), "trauma_beacon_off": beacon_head(False)(random.Random("trauma_beacon_off"))}
    models = {}
    for state, emission in (("off", 0), ("lit", 15)):
        elements = [
            gb.element([2, 0, 2], [14, 2, 14], {**{f: "plate" for f in FOUR}, "up": "plate", "down": "plate"}),
            gb.element([7, 2, 7], [9, 10, 9], {**{f: "post" for f in FOUR}, "up": "post", "down": "post"}),
            gb.element([5, 10, 5], [11, 14, 11], {**{f: "head" for f in FOUR}, "up": "head", "down": "head"}, emission),
        ]
        models[f"trauma_beacon_{state}"] = gb.model({"plate": "trauma_beacon_plate", "post": "trauma_beacon_post", "head": f"trauma_beacon_{state}"},
                                                    elements, "plate", ao=False)
    return gb.DeviceBlock("trauma_beacon", "Trauma Beacon", {"lit": ("false", "true")}, textures, models,
                          lambda s: "trauma_beacon_lit" if s["lit"] == "true" else "trauma_beacon_off", "trauma_beacon_off", lockable=False)


BLOCKS: list[gb.DeviceBlock] = [overlord_console(), trauma_beacon()]


# --- the items -------------------------------------------------------------------------------------------------------------------
def hacker_van_item() -> Image.Image:
    """A dark panel van in side view with a roof dish, a mast and a cyan stripe."""
    s = gi.Sprite()
    s.part(gi.disc(4.5, 12.3, 1.9) | gi.disc(11.5, 12.3, 1.9), "void", sep=True)
    s.part(gi.rect(1, 6, 14, 11) - {(1, 6), (14, 6), (1, 11), (14, 11)}, "gunmetal", sep=True)
    s.part(gi.rect(3, 4, 11, 5), "gunmetal", shade=False)
    s.dots([(2, 7), (3, 7), (4, 7), (2, 8), (3, 8)], gi.CYAN)
    s.dots([(10, 7), (11, 7), (12, 7), (13, 7)], gi.RAMPS["void"][1])
    s.dots([(x, 9) for x in range(5, 14)], gi.CYAN)
    s.dots([(4, 12), (11, 12)], gi.RAMPS["steel"][0])
    s.part(gi.poly([(5.0, 1.5), (9.0, 1.5), (8.0, 3.2), (6.0, 3.2)]), "steel", sep=True)
    s.dots([(7, 3), (7, 4)], gi.RAMPS["steel"][2])
    s.dots(gi.line((12, 4), (12, 1)), gi.RAMPS["steel"][1])
    s.dots([(12, 0)], gi.MAGENTA, top=True)
    s.dots([(1, 8)], gi.YELLOW)
    return s.render()


def hacker_drone_item() -> Image.Image:
    """A quadcopter seen from above and a little to the front: four rotors, two arms, a body and a cyan lens."""
    s = gi.Sprite()
    s.part(gi.bar((3.5, 3.5), (12.5, 12.5), 1.4) | gi.bar((12.5, 3.5), (3.5, 12.5), 1.4), "ash", sep=True)
    for cx, cy in ((3.2, 3.2), (12.8, 3.2), (3.2, 12.8), (12.8, 12.8)):
        s.part(gi.disc(cx, cy, 2.3), "steel", sep=True)
        s.dots([(int(cx - .5), int(cy - .5))], gi.CYAN)
    s.part(gi.disc(8, 8, 2.9), "gunmetal", sep=True)
    s.dots([(7, 9), (8, 9)], gi.CYAN)
    s.dots([(7, 7)], gi.RAMPS["gunmetal"][0])
    s.dots([(8, 5)], gi.RED, top=True)
    return s.render()


def netrunner_item() -> Image.Image:
    """The implant chip with a small net: three nodes round a white one, joined by purple links, so it reads as a runner's own corner of the net."""
    s = gi.Sprite()
    gi.chip_frame(s)
    s.dots(gi.polyline([(5, 5), (8, 8), (10, 5)]) | gi.polyline([(8, 8), (6, 11)]) | gi.polyline([(8, 8), (11, 10)]), gi.RAMPS["purple"][1])
    s.dots([(5, 5), (10, 5), (6, 11), (11, 10)], gi.CYAN)
    s.dots([(8, 8)], gi.WHITE)
    s.dots([(8, 7), (9, 8)], gi.MAGENTA)
    s.dots([(12, 3), (13, 4)], gi.MAGENTA, top=True)
    return s.render()


ITEMS: dict[str, tuple[str, Callable[[], Image.Image]]] = {"hacker_van": ("Hacker Van", hacker_van_item), "hacker_drone": ("Hacker Drone", hacker_drone_item),
                                                           "netrunner": ("Netrunner", netrunner_item)}


# --- entity textures -------------------------------------------------------------------------------------------------------------
Part = gv.Part
PARTS: dict[str, tuple[int, tuple[Part, ...]]] = {  # model -> (texture size, parts)
    "hacker_van": (128, (*gv.MODELS["van"],
                         *gv.parts(("dish", 20, 47, 8, 2, 8, [(-4, -19, -8)]), ("mast", 54, 47, 2, 8, 2, [(-6, -24, 9), (4, -24, 9)]),
                                   ("rack", 0, 64, 12, 1, 16, [(-6, -17, -12)])))),
    "hacker_drone": (64, gv.parts(("body", 0, 0, 6, 3, 6, [(-3, -2, -3)]), ("eye", 0, 10, 4, 3, 2, [(-2, -1, -5)]),
                                  ("arm_x", 26, 0, 14, 1, 2, [(-7, -1, -1)]), ("arm_z", 26, 4, 2, 1, 14, [(-1, -1, -7)]),
                                  ("rotor", 0, 20, 4, 1, 4, [(-9, -2, -2), (5, -2, -2), (-2, -2, -9), (-2, -2, 5)]),
                                  ("skid", 0, 28, 1, 1, 8, [(-3, 1, -4), (2, 1, -4)]), ("antenna", 20, 22, 1, 4, 1, [(1, -6, 1)]))),
    "trauma_flyer": (128, (*gv.MODELS["trauma_aerodyne"],
                           *gv.parts(("pad", 0, 64, 10, 1, 6, [(-5, -15, 3)]), ("winch", 40, 64, 4, 3, 4, [(-2, -6, -4)])))),
}
DEFAULT_COLOUR = {"hacker_van": "#2b3340", "trauma_flyer": "#e8ecef"}
BLACK_GLASS = (8, 10, 14)
CROSS_RED = (214, 40, 58)
DARK, TRIM = gv.DARK, gv.TRIM


def region(part: Part) -> tuple[int, int, int, int]:
    """The rectangle of the texture a part owns: 2 (d + w) wide and d + h high from its origin."""
    return part.u, part.v, 2 * (part.d + part.w), part.d + part.h


class VanPainter(gv.Painter):
    """The van paint and detail of gen_vehicle_art.py, with the windows at the back blacked out, a cyan side stripe and the roof gear."""

    def __init__(self) -> None:
        super().__init__("van")

    def d_body(self, part, face, i, j, fw, fh):
        found = super().d_body(part, face, i, j, fw, fh)
        if face in ("right", "left"):
            a = gv.along(face, i, fw)
            if found in (gv.GLASS, gv.GLASS_HI) and a >= fw - 9:
                return BLACK_GLASS  # privacy glass behind the cab
            if j == 8:
                return (47, 224, 255)  # the stripe
            if j == 9:
                return DARK
        if face == "back" and j == 6 and found is None and 4 <= i <= fw - 5:
            return (47, 224, 255)
        return found

    def d_rack(self, part, face, i, j, fw, fh):
        if face == "top":
            return DARK if (i + j) % 4 else (70, 74, 82)  # slotted rails
        return TRIM

    def d_dish(self, part, face, i, j, fw, fh):
        if face == "top":
            r = max(abs(i - (fw - 1) / 2), abs(j - (fh - 1) / 2))
            return (47, 224, 255) if r > fw / 2 - 1.5 else (232, 238, 244) if r > 1 else (255, 47, 179)  # rim, bowl, feed
        return (150, 156, 166)

    def d_mast(self, part, face, i, j, fw, fh):
        if face == "top":
            return (255, 59, 59)
        return (30, 32, 36) if j % 3 else (150, 156, 166)


class FlyerPainter(gv.Painter):
    """The trauma_aerodyne paint and detail with a red cross on each side, a cross pad on the roof and a winch."""

    def __init__(self) -> None:
        super().__init__("trauma_aerodyne")

    def d_fuselage(self, part, face, i, j, fw, fh):
        if face in ("right", "left"):
            a = gv.along(face, i, fw)
            if (j == 3 and 21 <= a <= 25) or (a == 23 and 1 <= j <= 5):
                return CROSS_RED
        return super().d_fuselage(part, face, i, j, fw, fh)

    def d_pad(self, part, face, i, j, fw, fh):
        if face == "top":
            cross = (fw // 2 - 1 <= i <= fw // 2) or (fh // 2 - 1 <= j <= fh // 2 and 2 <= i <= fw - 3)
            return CROSS_RED if cross else (236, 240, 244)
        return TRIM

    def d_winch(self, part, face, i, j, fw, fh):
        if face == "bottom":
            return (255, 196, 70)
        return DARK if j != fh - 1 else (255, 196, 70)


def layers(painter: gv.Painter, parts: tuple[Part, ...], size: int, lights: tuple | None = None) -> list[Image.Image]:
    """[paint, detail, lights a, lights b] of a painter over a parts table (gen_vehicle_art.Painter.textures for any table)."""
    out = [Image.new("RGBA", (size, size), (0, 0, 0, 0)) for _ in range(4)]
    for part in parts:
        for face, (x0, y0, fw, fh) in cuboid_faces(part.u, part.v, part.w, part.h, part.d).items():
            for j in range(fh):
                for i in range(fw):
                    g = painter.grey(part, face, i, j, fw, fh)
                    out[0].putpixel((x0 + i, y0 + j), (g, g, g, 255))
                    c = painter.detail(part, face, i, j, fw, fh)
                    if c is not None:
                        out[1].putpixel((x0 + i, y0 + j), (*c, 255))
                    if lights and part.name == "light_bar" and face not in ("top", "bottom"):
                        half = painter.half(face, i, fw)
                        out[2 + half].putpixel((x0 + i, y0 + j), (*lights[half], 255))
    return out


# the drone: a gunmetal hull, a lens that glows cyan and navigation lights on the arm tips (glow layer)
HULL, HULL_HI, HULL_LO = (58, 64, 80), (84, 92, 112), (36, 40, 52)
NAV = {"front": (47, 224, 255), "back": (255, 255, 255), "right": (53, 255, 122), "left": (255, 59, 59)}


def drone_pixel(part: Part, face: str, i: int, j: int, fw: int, fh: int) -> tuple[tuple[int, int, int], tuple[int, int, int] | None]:
    """(paint, glow) of one pixel of the drone; glow None where nothing glows."""
    wob = (gv.noise(i, j, len(part.name) + 5 * len(face)) - 0.5) * 8
    base = HULL_HI if face == "top" else HULL_LO if face == "bottom" else HULL
    paint, glow = tuple(max(0, min(255, round(c + wob))) for c in base), None
    if part.name == "body":
        if face == "top" and (i, j) in ((2, 2), (3, 2), (2, 3), (3, 3)):
            paint = (110, 120, 140)  # the hatch
        elif face in ("right", "left", "front", "back") and j == 1:
            paint = HULL_LO
    elif part.name == "eye":
        if face == "front":
            if not (i in (0, fw - 1) and j in (0, fh - 1)):
                paint, glow = (6, 18, 26), (47, 224, 255)
            if (i, j) in ((fw // 2 - 1, 1), (fw // 2, 1)):
                paint, glow = (255, 255, 255), (255, 255, 255)
        else:
            paint = (30, 34, 44)
    elif part.name == "arm_x" and face in ("right", "left"):
        paint, glow = (30, 34, 44), NAV[face]
    elif part.name == "arm_z" and face in ("front", "back"):
        paint, glow = (30, 34, 44), NAV[face]
    elif part.name in ("arm_x", "arm_z"):
        paint = HULL_LO if face != "top" else HULL
    elif part.name == "rotor":
        blade = face == "top" and (i == j or i + j == fw - 1)
        paint = (150, 158, 172) if blade else (24, 26, 32)
    elif part.name == "skid":
        paint = (24, 26, 32)
    elif part.name == "antenna":
        paint = (30, 32, 36)
        if face == "top":
            paint, glow = (255, 59, 59), (255, 59, 59)
    return paint, glow


def drone_layers() -> list[Image.Image]:
    """[paint, glow] of the drone."""
    size, parts = PARTS["hacker_drone"]
    paint, glow = (Image.new("RGBA", (size, size), (0, 0, 0, 0)) for _ in range(2))
    for part in parts:
        for face, (x0, y0, fw, fh) in cuboid_faces(part.u, part.v, part.w, part.h, part.d).items():
            for j in range(fh):
                for i in range(fw):
                    colour, light = drone_pixel(part, face, i, j, fw, fh)
                    paint.putpixel((x0 + i, y0 + j), (*colour, 255))
                    if light:
                        glow.putpixel((x0 + i, y0 + j), (*light, 255))
    return [paint, glow]


def textures() -> dict[str, Image.Image]:
    """Every entity texture as {file stem: image}."""
    out: dict[str, Image.Image] = {}
    out["hacker_van"], out["hacker_van_detail"] = layers(VanPainter(), PARTS["hacker_van"][1], 128)[:2]
    out["hacker_drone"], out["hacker_drone_glow"] = drone_layers()
    flyer = layers(FlyerPainter(), PARTS["trauma_flyer"][1], 128, gv.FLASHERS["trauma_aerodyne"])
    out["trauma_flyer"], out["trauma_flyer_detail"], out["trauma_flyer_lights_a"], out["trauma_flyer_lights_b"] = flyer
    return out


def documented_layout() -> dict[str, tuple[Part, ...]]:
    """The UV table of this file's docstring, parsed (a test keeps it equal to PARTS)."""
    out: dict[str, list[Part]] = {}
    for line in (__doc__ or "").splitlines():
        m = re.fullmatch(r"\s{4}([a-z_]+)\s+([a-z_]+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(.+)", line)
        if m:
            at = tuple(tuple(int(n) for n in triple.split(",")) for triple in m.group(8).split())
            out.setdefault(m.group(1), []).append(Part(m.group(2), *(int(m.group(k)) for k in range(3, 8)), at))
    return {k: tuple(v) for k, v in out.items()}


# --- output ---------------------------------------------------------------------------------------------------------------------
def files() -> dict[str, bytes]:
    """Every file this script owns as {path under assets/cyberblock: bytes}."""
    out: dict[str, bytes] = {}

    def add(path: str, data: bytes) -> None:
        assert out.setdefault(path, data) == data, f"{path} would be written twice with different content"

    for block in BLOCKS:
        for stem, im in block.textures.items():
            add(f"textures/block/{stem}.png", gb.png_bytes(im))
        for stem, data in block.models.items():
            add(f"models/block/{stem}.json", gb.json_bytes(data))
        add(f"blockstates/{block.id}.json", gb.json_bytes(gb.blockstate(block)))
        add(f"items/{block.id}.json", gb.json_bytes({"model": {"type": "minecraft:model", "model": f"{NAMESPACE}:block/{block.item_model}"}}))
    for item, (_name, paint) in ITEMS.items():
        add(f"textures/item/{item}.png", gb.png_bytes(paint()))
        add(f"models/item/{item}.json", gb.json_bytes({"parent": "minecraft:item/generated", "textures": {"layer0": f"{NAMESPACE}:item/{item}"}}))
        add(f"items/{item}.json", gb.json_bytes({"model": {"type": "minecraft:model", "model": f"{NAMESPACE}:item/{item}"}}))
    for stem, im in textures().items():
        add(f"textures/entity/mode/{stem}.png", gb.png_bytes(im))
    return out


# --- the preview ------------------------------------------------------------------------------------------------------------------
def tint(paint: Image.Image, colour: str) -> Image.Image:
    r, g, b = (int(colour[k:k + 2], 16) for k in (1, 3, 5))
    out = Image.new("RGBA", paint.size, (0, 0, 0, 0))
    src, dst = paint.load(), out.load()
    for y in range(paint.height):
        for x in range(paint.width):
            v, _g, _b, a = src[x, y]
            if a:
                dst[x, y] = (r * v // 255, g * v // 255, b * v // 255, 255)
    return out


def view(parts: tuple[Part, ...], tex: Image.Image, which: str, zoom: int) -> Image.Image:
    """An orthographic view (front, left side or top) of a parts table painted with a texture, parts drawn far to near."""
    boxes = [(p, x, y, z) for p in parts for x, y, z in p.at]
    xs = [x for _p, x, _y, _z in boxes] + [x + p.w for p, x, _y, _z in boxes]
    ys = [y for _p, _x, y, _z in boxes] + [y + p.h for p, _x, y, _z in boxes]
    zs = [z for _p, _x, _y, z in boxes] + [z + p.d for p, _x, _y, z in boxes]
    if which == "front":
        size, order = (max(xs) - min(xs), max(ys) - min(ys)), sorted(boxes, key=lambda b: -b[3])
    elif which == "left":
        size, order = (max(zs) - min(zs), max(ys) - min(ys)), sorted(boxes, key=lambda b: b[1] + b[0].w)
    else:
        size, order = (max(xs) - min(xs), max(zs) - min(zs)), sorted(boxes, key=lambda b: -b[2])
    out = Image.new("RGBA", (size[0] * zoom, size[1] * zoom), (0, 0, 0, 0))
    for p, x, y, z in order:
        fx, fy, fw, fh = cuboid_faces(p.u, p.v, p.w, p.h, p.d)[which]
        img = tex.crop((fx, fy, fx + fw, fy + fh)).resize((fw * zoom, fh * zoom), Image.NEAREST)
        pos = {"front": (x - min(xs), y - min(ys)), "left": (z - min(zs), y - min(ys)), "top": (x - min(xs), z - min(zs))}[which]
        out.alpha_composite(img, (pos[0] * zoom, pos[1] * zoom))
    return out


def preview() -> Image.Image:
    """A contact sheet: the block textures and the items at 4x, then each entity model in front, left and top view with its texture sheets."""
    tex, zoom, pad = textures(), 4, 8
    tiles = [im for b in BLOCKS for im in b.textures.values()] + [paint() for _n, paint in ITEMS.values()]
    strip = Image.new("RGBA", (len(tiles) * (SIZE * zoom + pad), SIZE * zoom), (0, 0, 0, 0))
    for n, tile in enumerate(tiles):
        strip.alpha_composite(tile.convert("RGBA").resize((SIZE * zoom, SIZE * zoom), Image.NEAREST), (n * (SIZE * zoom + pad), 0))
    rows = [strip]
    composed = {"hacker_van": tint(tex["hacker_van"], DEFAULT_COLOUR["hacker_van"]), "hacker_drone": tex["hacker_drone"].copy(),
                "trauma_flyer": tint(tex["trauma_flyer"], DEFAULT_COLOUR["trauma_flyer"])}
    composed["hacker_van"].alpha_composite(tex["hacker_van_detail"])
    composed["hacker_drone"].alpha_composite(tex["hacker_drone_glow"])
    composed["trauma_flyer"].alpha_composite(tex["trauma_flyer_detail"])
    composed["trauma_flyer"].alpha_composite(tex["trauma_flyer_lights_a"])
    for model, (_size, parts) in PARTS.items():
        z = 8 if model == "hacker_drone" else zoom
        shots = [view(parts, composed[model], w, z) for w in ("front", "left", "top")]
        sheets = [im.resize((im.width * 2, im.height * 2), Image.NEAREST) for name, im in tex.items() if name.startswith(model)]
        row = Image.new("RGBA", (sum(s.width + pad for s in shots + sheets), max(s.height for s in shots + sheets)), (0, 0, 0, 0))
        x = 0
        for s in shots + sheets:
            row.alpha_composite(s, (x, 0))
            x += s.width + pad
        rows.append(row)
    sheet = Image.new("RGBA", (max(r.width for r in rows) + 2 * pad, sum(r.height + pad for r in rows) + pad), (29, 32, 44, 255))
    y = pad
    for r in rows:
        sheet.alpha_composite(r, (pad, y))
        y += r.height + pad
    return sheet.convert("RGB")


def main() -> None:
    out = files()
    for rel, data in out.items():
        (ASSETS / rel).parent.mkdir(parents=True, exist_ok=True)
        (ASSETS / rel).write_bytes(data)
    gb.write_png(HERE / "mode_preview.png", preview())
    print(f"wrote {len(BLOCKS)} blocks, {len(ITEMS)} items and {len(textures())} entity textures ({len(out)} files)")


if __name__ == "__main__":
    main()






