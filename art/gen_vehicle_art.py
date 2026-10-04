"""Procedural, original textures for the 12 Night City vehicle figures and the `vehicle_map` defaults (megaplan 5-A2, 1.7, 1.12).

Run from anywhere:  uv run python art/gen_vehicle_art.py

Writes, under mc/src/main/resources/assets/cyberblock/textures/entity/vehicle/ (every texture 128 x 128):
    <model>.png           the paint layer: greyscale, opaque on every face pixel the model uses. TINTABLE: the renderer multiplies
                          it by the figure's colour (`col` of the npcs message, else the vehicle_map default colour).
    <model>_detail.png    drawn untinted over the paint with the same geometry (cutout): glass, tyres, lights, grilles, bumpers,
                          trims, livery bands. Transparent where only paint shows.
    <model>_lights_a.png  emergency vehicles only (police_cruiser, ncpd_aerodyne, trauma_aerodyne): the light bar lenses of one
    <model>_lights_b.png  half each, to be drawn emissive (full bright) and alternated while the vehicle flashes (a chase).
and mc/src/main/resources/data/cyberblock/cyberblock/vehicle_map/<row>.json, and art/preview_vehicles.png (front, left side and
top views of every model in its default colour, painted through the layout below, plus each model's paint and detail sheets).

UV LAYOUT (the contract with the figure renderer, package 5F). Every part is a box given exactly as Java builds it:
`CubeListBuilder.create().texOffs(u, v).addBox(x, y, z, w, h, d)` in a model with `texWidth = texHeight = 128`, all parts in one
root at PartPose.ZERO, no mirror, no inflation. Model pixels, with the vanilla ModelPart axes: y points DOWN and y = 0 is the
ground (so every box has y + h <= 0); the vehicle's nose points to -z; -x is the vehicle's right side. The geometry is half scale:
draw it at 2x (one model pixel = 1/8 block, a sedan is 2 x 4.5 blocks) or scale it to the proxy's size. The faces unwrap the vanilla
way (art/skin_layout.py `cuboid_faces`): top (u+d, v), bottom (u+d+w, v), right (u, v+d), front (u+d, v+d), left (u+d+w, v+d),
back (u+2d+w, v+d). A part listed with several positions is one UV region shared by several boxes (the wheels).

    model            part        u    v     w    h    d   at (x, y, z) of each box
    sedan            body        0    0    16    6   36   -8,-8,-18
    sedan            cabin       0   42    14    5   16   -7,-13,-6
    sedan            wheel      60   42     3    6    6   -9,-6,-14  6,-6,-14  -9,-6,8  6,-6,8
    sports_car       body        0    0    16    5   34   -8,-7,-17
    sports_car       cabin       0   39    14    4   13   -7,-11,-3
    sports_car       spoiler    54   39    14    1    3   -7,-8,13
    sports_car       wheel      88   39     3    6    6   -9,-6,-13  6,-6,-13  -9,-6,8  6,-6,8
    truck            chassis     0    0    18    3   44   -9,-7,-22
    truck            cab         0   47    18   12   12   -9,-19,-22
    truck            cargo       0   71    18   16   30   -9,-23,-8
    truck            wheel      60   47     4    7    7   -10,-7,-20  6,-7,-20  -10,-7,7  6,-7,7  -10,-7,14  6,-7,14
    van              body        0    0    16   13   34   -8,-16,-17
    van              wheel       0   47     3    6    6   -9,-6,-13  6,-6,-13  -9,-6,8  6,-6,8
    bike             frame       0    0     4    5   18   -2,-10,-9
    bike             seat       44    0     4    1    6   -2,-11,0
    bike             handlebar  64    0    10    1    1   -5,-12,-8
    bike             wheel       0   23     2    8    8   -1,-8,-13  -1,-8,5
    taxi             body        0    0    16    6   36   -8,-8,-18
    taxi             cabin       0   42    14    5   16   -7,-13,-6
    taxi             wheel      60   42     3    6    6   -9,-6,-14  6,-6,-14  -9,-6,8  6,-6,8
    taxi             sign       78   42     6    2    3   -3,-15,0
    police_cruiser   body        0    0    16    6   36   -8,-8,-18
    police_cruiser   cabin       0   42    14    5   16   -7,-13,-6
    police_cruiser   wheel      60   42     3    6    6   -9,-6,-14  6,-6,-14  -9,-6,8  6,-6,8
    police_cruiser   light_bar  78   47    12    1    3   -6,-14,0
    armoured_van     body        0    0    18   14   36   -9,-17,-18
    armoured_van     ram         0   50    18    3    2   -9,-6,-20
    armoured_van     wheel      40   50     4    7    7   -10,-7,-14  6,-7,-14  -10,-7,7  6,-7,7
    armoured_van     hatch      62   50     8    2    8   -4,-19,-2
    trauma_aerodyne  fuselage    0    0    16    8   34   -8,-14,-17
    trauma_aerodyne  cockpit     0   42    12    4   10   -6,-18,-15
    trauma_aerodyne  pod        44   42     6    4    8   -14,-10,-14  8,-10,-14  -14,-10,6  8,-10,6
    trauma_aerodyne  fin        72   42     2    4    8   -1,-18,9
    trauma_aerodyne  light_bar  92   42     8    1    2   -4,-15,0
    ncpd_aerodyne    fuselage    0    0    16    8   34   -8,-14,-17
    ncpd_aerodyne    cockpit     0   42    12    4   10   -6,-18,-15
    ncpd_aerodyne    pod        44   42     6    4    8   -14,-10,-14  8,-10,-14  -14,-10,6  8,-10,6
    ncpd_aerodyne    fin        72   42     2    4    8   -1,-18,9
    ncpd_aerodyne    light_bar  92   42     8    1    2   -4,-15,0
    delivery_truck   cargo       0    0    16   15   24   -8,-18,-10
    delivery_truck   cab         0   39    16   11   10   -8,-14,-20
    delivery_truck   wheel      52   39     3    6    6   -9,-6,-17  6,-6,-17  -9,-6,7  6,-6,7
    pickup           body        0    0    16    6   36   -8,-8,-18
    pickup           cabin       0   42    14    6   12   -7,-14,-8
    pickup           bed_side   52   42     1    3   14   -8,-11,4  7,-11,4
    pickup           tailgate   82   42    14    3    1   -7,-11,17
    pickup           wheel       0   60     3    6    6   -9,-6,-14  6,-6,-14  -9,-6,8  6,-6,8

taxi and police_cruiser are the sedan plus one part; the two aerodynes share one geometry. A test checks that this table and the
MODELS table below are the same and that no two regions of a texture overlap. Deterministic: two runs give byte-identical files.

vehicle_map rows (one JSON object per file, schema in shared/content-schemas/vehicle_map.schema.json): a vehicle matches a row when
each of the row's `vc` (the npcs `vc`, "<type>/<model>" of 2.31, e.g. "Car/Turbo"), `type` (the part of `vc` before the slash:
Car, Bike, Panzer) and `faction` (the vehicle's Affiliation().EnumName(), when the figure has one) keys holds (a key's value is
one name or a list, any of them); the highest `priority` wins (default 0), then the row with more keys, then the lowest id. Shipped priorities:
faction liveries 20, model rows 10, class rows 0. The row gives the `model` and the default `colour`; `col` from the host replaces
the colour unless the row has `keepColour` (liveries: police, Trauma Team, Militech). The `default` row (no keys) is the unknown
vehicle: a sedan in the Car class colour.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

from convert_slim import write_png
from skin_layout import cuboid_faces

ART = Path(__file__).resolve().parent
RESOURCES = ART.parent / "mc" / "src" / "main" / "resources"
ASSETS = RESOURCES / "assets" / "cyberblock"
VEHICLE_MAP = RESOURCES / "data" / "cyberblock" / "cyberblock" / "vehicle_map"
NAMESPACE = "cyberblock"
SIZE = 128

RGBA = tuple[int, int, int, int]


@dataclass(frozen=True)
class Part:
    name: str
    u: int
    v: int
    w: int
    h: int
    d: int
    at: tuple[tuple[int, int, int], ...]


def parts(*rows: tuple) -> tuple[Part, ...]:
    return tuple(Part(name, u, v, w, h, d, tuple(at)) for name, u, v, w, h, d, at in rows)


WHEELS_SEDAN = ((-9, -6, -14), (6, -6, -14), (-9, -6, 8), (6, -6, 8))
WHEELS_13 = ((-9, -6, -13), (6, -6, -13), (-9, -6, 8), (6, -6, 8))
SEDAN = (("body", 0, 0, 16, 6, 36, [(-8, -8, -18)]), ("cabin", 0, 42, 14, 5, 16, [(-7, -13, -6)]),
         ("wheel", 60, 42, 3, 6, 6, WHEELS_SEDAN))
AERODYNE = (("fuselage", 0, 0, 16, 8, 34, [(-8, -14, -17)]), ("cockpit", 0, 42, 12, 4, 10, [(-6, -18, -15)]),
            ("pod", 44, 42, 6, 4, 8, [(-14, -10, -14), (8, -10, -14), (-14, -10, 6), (8, -10, 6)]),
            ("fin", 72, 42, 2, 4, 8, [(-1, -18, 9)]), ("light_bar", 92, 42, 8, 1, 2, [(-4, -15, 0)]))
MODELS: dict[str, tuple[Part, ...]] = {
    "sedan": parts(*SEDAN),
    "sports_car": parts(("body", 0, 0, 16, 5, 34, [(-8, -7, -17)]), ("cabin", 0, 39, 14, 4, 13, [(-7, -11, -3)]),
                        ("spoiler", 54, 39, 14, 1, 3, [(-7, -8, 13)]), ("wheel", 88, 39, 3, 6, 6, WHEELS_13)),
    "truck": parts(("chassis", 0, 0, 18, 3, 44, [(-9, -7, -22)]), ("cab", 0, 47, 18, 12, 12, [(-9, -19, -22)]),
                   ("cargo", 0, 71, 18, 16, 30, [(-9, -23, -8)]),
                   ("wheel", 60, 47, 4, 7, 7, [(-10, -7, -20), (6, -7, -20), (-10, -7, 7), (6, -7, 7), (-10, -7, 14), (6, -7, 14)])),
    "van": parts(("body", 0, 0, 16, 13, 34, [(-8, -16, -17)]), ("wheel", 0, 47, 3, 6, 6, WHEELS_13)),
    "bike": parts(("frame", 0, 0, 4, 5, 18, [(-2, -10, -9)]), ("seat", 44, 0, 4, 1, 6, [(-2, -11, 0)]),
                  ("handlebar", 64, 0, 10, 1, 1, [(-5, -12, -8)]), ("wheel", 0, 23, 2, 8, 8, [(-1, -8, -13), (-1, -8, 5)])),
    "taxi": parts(*SEDAN, ("sign", 78, 42, 6, 2, 3, [(-3, -15, 0)])),
    "police_cruiser": parts(*SEDAN, ("light_bar", 78, 47, 12, 1, 3, [(-6, -14, 0)])),
    "armoured_van": parts(("body", 0, 0, 18, 14, 36, [(-9, -17, -18)]), ("ram", 0, 50, 18, 3, 2, [(-9, -6, -20)]),
                          ("wheel", 40, 50, 4, 7, 7, [(-10, -7, -14), (6, -7, -14), (-10, -7, 7), (6, -7, 7)]),
                          ("hatch", 62, 50, 8, 2, 8, [(-4, -19, -2)])),
    "trauma_aerodyne": parts(*AERODYNE),
    "ncpd_aerodyne": parts(*AERODYNE),
    "delivery_truck": parts(("cargo", 0, 0, 16, 15, 24, [(-8, -18, -10)]), ("cab", 0, 39, 16, 11, 10, [(-8, -14, -20)]),
                            ("wheel", 52, 39, 3, 6, 6, [(-9, -6, -17), (6, -6, -17), (-9, -6, 7), (6, -6, 7)])),
    "pickup": parts(("body", 0, 0, 16, 6, 36, [(-8, -8, -18)]), ("cabin", 0, 42, 14, 6, 12, [(-7, -14, -8)]),
                    ("bed_side", 52, 42, 1, 3, 14, [(-8, -11, 4), (7, -11, 4)]), ("tailgate", 82, 42, 14, 3, 1, [(-7, -11, 17)]),
                    ("wheel", 0, 60, 3, 6, 6, WHEELS_SEDAN)),
}
DEFAULT_COLOUR = {"sedan": "#8a8f99", "sports_car": "#c23b3b", "truck": "#b88a3a", "van": "#d9d9d4", "bike": "#3a3a44",
                  "taxi": "#e3b13a", "police_cruiser": "#1f2c4a", "armoured_van": "#3b4136", "trauma_aerodyne": "#e8ecef",
                  "ncpd_aerodyne": "#1f2c4a", "delivery_truck": "#d8d2c4", "pickup": "#6d4f3a"}
FLASHERS = {"police_cruiser": ((255, 48, 48), (58, 107, 255)), "ncpd_aerodyne": ((255, 48, 48), (58, 107, 255)),
            "trauma_aerodyne": ((255, 255, 255), (64, 255, 216))}
LIVERY = {"police_cruiser": (232, 236, 240), "ncpd_aerodyne": (232, 236, 240), "trauma_aerodyne": (37, 179, 158),
          "delivery_truck": (44, 111, 184), "taxi": (24, 24, 28)}
FACE_GREY = {"top": 236, "front": 212, "back": 204, "right": 222, "left": 222, "bottom": 110}
HULLS = ("body", "cabin", "cab", "cargo", "fuselage", "frame", "chassis")
GLASS, GLASS_HI, TYRE, RIM, DARK, TRIM = (28, 40, 56), (70, 96, 120), (24, 24, 27), (150, 156, 166), (38, 40, 44), (52, 54, 60)
HEAD, TAIL, AMBER = (246, 240, 214), (200, 32, 42), (255, 196, 70)


def noise(i: int, j: int, salt: int) -> float:
    n = (i * 73856093) ^ (j * 19349663) ^ (salt * 83492791)
    n = (n ^ (n >> 13)) * 1274126177
    return ((n ^ (n >> 16)) & 0xFFFF) / 0x10000


def along(face: str, i: int, w: int) -> int:
    """On a side face: 0 at the front edge (the nose), growing towards the back."""
    return w - 1 - i if face == "right" else i


def across(face: str, i: int, w: int) -> int | None:
    """Columns of top, bottom, front and back faces counted from the vehicle's right edge (model -x); None on side faces."""
    if face in ("right", "left"):
        return None
    return w - 1 - i if face == "back" else i


class Painter:
    def __init__(self, model: str):
        self.model = model
        self.livery = LIVERY.get(model)

    def grey(self, part: Part, face: str, i: int, j: int, fw: int, fh: int) -> int:
        g = FACE_GREY[face]
        if part.name in HULLS and face in ("right", "left"):
            a = along(face, i, fw)
            if j == 0:
                g += 10
            if j == fh - 1:
                g -= 28
            if part.name == "body" and a in (fw * 3 // 10, fw * 6 // 10):
                g -= 46  # door gaps
            if part.name == "cargo" and j % 4 == 3:
                g -= 30  # ribs
        if face == "top" and part.name in ("body", "fuselage") and j == fh // 4:
            g -= 18  # bonnet seam
        if face == "top" and part.name in ("cabin", "cab", "cockpit", "cargo") and (i in (0, fw - 1) or j in (0, fh - 1)):
            g -= 24  # roof edge
        return max(0, min(255, round(g + (noise(i, j, len(part.name) + 7 * len(face)) - 0.5) * 6)))

    # --- details ----------------------------------------------------------------------------------------------------------------
    def detail(self, part: Part, face: str, i: int, j: int, fw: int, fh: int) -> tuple[int, int, int] | None:
        handler = getattr(self, f"d_{part.name}", None)
        return handler(part, face, i, j, fw, fh) if handler else None

    def d_wheel(self, part, face, i, j, fw, fh):
        if face in ("right", "left"):
            c = (fw - 1) / 2
            r = max(abs(i - c), abs(j - c))
            return RIM if r < fw / 4 else (90, 94, 100) if r < fw / 4 + 1 else TYRE
        return (36, 36, 40) if j % 2 == 0 else TYRE  # tread

    def d_body(self, part, face, i, j, fw, fh):
        m, x = self.model, across(face, i, fw)
        if face == "front":
            if j == fh - 1:
                return TRIM  # bumper
            if j in (1, 2) and (x <= 2 or x >= fw - 3):
                return HEAD
            if m == "van" and 2 <= j <= 5 and 1 <= x <= fw - 2:
                return GLASS_HI if j == 2 else GLASS
            if m == "armoured_van":
                if j in (3, 4) and 3 <= x <= fw - 4:
                    return (12, 14, 16)  # vision slit
                if j in (fh - 4, fh - 3) and (x <= 2 or x >= fw - 3):
                    return HEAD
                return None
            if m == "van" and j in (fh - 4, fh - 3) and (x <= 2 or x >= fw - 3):
                return HEAD
            if j in (2, 3) and 5 <= x <= fw - 6 and m != "van":
                return DARK if x % 2 else (64, 66, 72)  # grille
            return None
        if face == "back":
            if j == fh - 1:
                return TRIM
            if j in (1, 2) and (x <= 2 or x >= fw - 3) and m not in ("van", "armoured_van"):
                return TAIL
            if m == "van":
                if 2 <= j <= 5 and 1 <= x <= fw - 2 and x not in (fw // 2 - 1, fw // 2):
                    return GLASS
                if j in (8, 9) and (x <= 1 or x >= fw - 2):
                    return TAIL
            if m == "armoured_van":
                if x in (fw // 2 - 1, fw // 2) and j < fh - 1:
                    return DARK  # door seam
                if j in (9, 10) and (x <= 1 or x >= fw - 2):
                    return TAIL
            if j == 3 and fw // 2 - 2 <= x <= fw // 2 + 1:
                return (214, 214, 206)  # a blank plate
            return None
        if face in ("right", "left"):
            a = along(face, i, fw)
            if j == fh - 1:
                return TRIM  # sill
            if m == "van":
                if 2 <= j <= 5 and 2 <= a <= 8:
                    return GLASS_HI if j == 2 else GLASS
                if 2 <= j <= 4 and fw - 9 <= a <= fw - 3:
                    return GLASS
                return None
            if m == "armoured_van":
                if (j in (1, fh - 3)) and a % 4 == 1:
                    return (70, 74, 78)  # rivets
                if j in (3, 4) and 3 <= a <= 8:
                    return (12, 14, 16)
                return None
            if self.livery and m == "police_cruiser" and j in (2, 3):
                return self.livery
            if m == "taxi" and j == 2:
                return (24, 24, 28) if a % 2 else (236, 236, 228)  # chequer band
            if m == "sports_car" and j in (1, 2) and fw * 6 // 10 + 2 <= a <= fw * 6 // 10 + 4:
                return DARK  # side intake
            if j == 1 and a in (fw * 3 // 10 + 2, fw * 6 // 10 + 2):
                return DARK  # door handles
            return None
        return None

    def glass_box(self, face, i, j, fw, fh):
        """A cabin: windows on the sides, the windscreen and the rear window; pillars stay paint."""
        if face == "top" or face == "bottom":
            return None
        if face in ("right", "left"):
            a = along(face, i, fw)
            if j >= 1 and 1 <= a <= fw - 2 and a != fw // 2:
                return GLASS_HI if j == 1 else GLASS
            return None
        if j >= 0 and 1 <= i <= fw - 2:
            return GLASS_HI if j == 0 else GLASS
        return None

    def d_cabin(self, part, face, i, j, fw, fh):
        return self.glass_box(face, i, j, fw, fh)

    def d_cockpit(self, part, face, i, j, fw, fh):
        if face == "back":
            return None
        if face == "top":
            return GLASS_HI if i in (1, fw - 2) or j >= fh - 3 else GLASS
        return self.glass_box(face, i, j, fw, fh)

    def d_cab(self, part, face, i, j, fw, fh):
        x = across(face, i, fw)
        if face == "front":
            if 1 <= j <= 5 and 1 <= x <= fw - 2:
                return GLASS_HI if j == 1 else GLASS
            if j in (fh - 3, fh - 2) and (x <= 2 or x >= fw - 3):
                return HEAD
            if j >= fh - 4 and 5 <= x <= fw - 6:
                return DARK if x % 2 else (64, 66, 72)
            return TRIM if j == fh - 1 else None
        if face in ("right", "left"):
            a = along(face, i, fw)
            if 1 <= j <= 4 and 1 <= a <= fw // 2:
                return GLASS
            return TRIM if j == fh - 1 else None
        return None

    def d_cargo(self, part, face, i, j, fw, fh):
        if face == "back":
            x = across(face, i, fw)
            if x in (fw // 2 - 1, fw // 2):
                return DARK
            if j in (fh // 2,) and x in (fw // 2 - 3, fw // 2 + 2):
                return (180, 184, 190)  # handles
            if j == fh - 2 and (x <= 1 or x >= fw - 2):
                return TAIL
            return None
        if face in ("right", "left") and self.livery and self.model == "delivery_truck" and j in (5, 6, 7):
            return self.livery if j != 6 else tuple(min(255, c + 40) for c in self.livery)
        return None

    def d_chassis(self, part, face, i, j, fw, fh):
        return DARK if face != "top" else (48, 50, 54)

    def d_frame(self, part, face, i, j, fw, fh):
        if face in ("right", "left"):
            a = along(face, i, fw)
            if 2 <= j <= 4 and 6 <= a <= 12:
                return (64, 66, 72) if (a + j) % 2 else DARK  # the engine
            return None
        if face == "front" and j in (1, 2) and i in (1, 2):
            return HEAD
        if face == "back" and j == 1 and i in (1, 2):
            return TAIL
        return None

    def d_seat(self, part, face, i, j, fw, fh):
        return (26, 24, 24) if face != "bottom" else DARK

    def d_handlebar(self, part, face, i, j, fw, fh):
        x = across(face, i, fw)
        return (20, 20, 22) if x is not None and (x <= 1 or x >= fw - 2) else (110, 114, 120)

    def d_spoiler(self, part, face, i, j, fw, fh):
        return (30, 30, 34)

    def d_sign(self, part, face, i, j, fw, fh):
        if face in ("front", "back"):
            return AMBER
        return (210, 160, 50) if face in ("right", "left") else (40, 40, 44)

    def d_light_bar(self, part, face, i, j, fw, fh):
        if face in ("top", "bottom"):
            return (32, 34, 40)
        dim = [tuple(c // 2 for c in lit) for lit in FLASHERS[self.model]]
        return dim[self.half(face, i, fw)]

    @staticmethod
    def half(face: str, i: int, fw: int) -> int:
        """0 for the vehicle's right half (model -x) of a light bar, 1 for its left half."""
        if face == "right":
            return 0
        if face == "left":
            return 1
        return 0 if across(face, i, fw) < fw / 2 else 1

    def d_fuselage(self, part, face, i, j, fw, fh):
        x = across(face, i, fw)
        if face in ("right", "left"):
            a = along(face, i, fw)
            if j in (2, 3) and 5 <= a <= 15 and a % 3 != 0:
                return GLASS_HI if j == 2 else GLASS
            if j in (5, 6) and self.livery:
                return self.livery
            return TRIM if j == fh - 1 else None
        if face == "front":
            if j in (5, 6) and (x <= 1 or x >= fw - 2):
                return HEAD
            return (40, 42, 48) if j == fh - 1 else None
        if face == "back":
            return (90, 220, 255) if j in (3, 4) and 5 <= x <= fw - 6 else None  # exhaust glow
        if face == "bottom":
            return DARK if (i + j) % 3 == 0 else (60, 62, 68)
        return None

    def d_pod(self, part, face, i, j, fw, fh):
        if face == "bottom":
            return (127, 232, 255) if 1 <= i <= fw - 2 and 1 <= j <= fh - 2 else DARK  # the thrust ring
        if face == "front":
            return DARK if 1 <= i <= fw - 2 and 1 <= j <= fh - 2 else None
        if face in ("right", "left") and j == fh - 1:
            return TRIM
        return None

    def d_fin(self, part, face, i, j, fw, fh):
        return self.livery if face in ("right", "left") and j == 1 and self.livery else None

    def d_ram(self, part, face, i, j, fw, fh):
        return (58, 60, 64) if (i + j) % 3 else (30, 31, 34)

    def d_hatch(self, part, face, i, j, fw, fh):
        if face == "top":
            return DARK if i in (0, fw - 1) or j in (0, fh - 1) else None
        return TRIM

    def d_bed_side(self, part, face, i, j, fw, fh):
        return TRIM if face == "top" else None

    def d_tailgate(self, part, face, i, j, fw, fh):
        if face == "back" and j == 1 and fw // 2 - 2 <= i <= fw // 2 + 1:
            return (214, 214, 206)
        return TRIM if face == "top" else None

    # --- the textures -----------------------------------------------------------------------------------------------------------
    def textures(self) -> dict[str, Image.Image]:
        paint = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        detail = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        lights = [Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0)) for _ in range(2)]
        for part in MODELS[self.model]:
            for face, (x0, y0, fw, fh) in cuboid_faces(part.u, part.v, part.w, part.h, part.d).items():
                for j in range(fh):
                    for i in range(fw):
                        g = self.grey(part, face, i, j, fw, fh)
                        paint.putpixel((x0 + i, y0 + j), (g, g, g, 255))
                        c = self.detail(part, face, i, j, fw, fh)
                        if c is not None:
                            detail.putpixel((x0 + i, y0 + j), (*c, 255))
                        if part.name == "light_bar" and face not in ("top", "bottom"):
                            half = self.half(face, i, fw)
                            lights[half].putpixel((x0 + i, y0 + j), (*FLASHERS[self.model][half], 255))
        out = {self.model: paint, f"{self.model}_detail": detail}
        if self.model in FLASHERS:
            out[f"{self.model}_lights_a"], out[f"{self.model}_lights_b"] = lights
        return out


# --- vehicle_map ---------------------------------------------------------------------------------------------------------------
# 2.31 gamedataVehicleModel names by figure model; any other model falls to its type row (Car, Bike, Panzer).
CAR_MODELS = {
    "sports_car": ("Aerondight", "Alvarado", "Deleon", "Riptide", "Shion", "Turbo", "Type66"),
    "sedan": ("Cortes", "Emperor", "Galena", "GalenaNomad", "Hozuki", "Maimai", "Merrimac", "Octant", "Thrax"),
    "van": ("Columbus", "Hearse", "Supron"),
    "pickup": ("Colby", "Mackinaw"),
    "truck": ("Bratsk",),
    "delivery_truck": ("Zeya",),
}


def map_rows() -> dict[str, dict]:
    def row(model: str, priority: int = 0, **keys) -> dict:
        return {"schema": 1, "model": model, "colour": DEFAULT_COLOUR[model], **keys, **({"priority": priority} if priority else {})}

    rows = {
        "default": row("sedan"),
        "type_car": row("sedan", type="Car"),
        "type_bike": row("bike", type="Bike"),
        "type_panzer": row("armoured_van", type="Panzer"),
        "ncpd_car": row("police_cruiser", 20, type="Car", faction="NCPD", keepColour=True),
        "trauma_team": row("trauma_aerodyne", 20, faction="TraumaTeam", keepColour=True),
        "militech_panzer": row("armoured_van", 20, type="Panzer", faction="Militech", keepColour=True),
    }
    for model, names in CAR_MODELS.items():
        rows[f"cars_{model}"] = row(model, 10, vc=[f"Car/{name}" for name in names] if len(names) > 1 else f"Car/{names[0]}")
    return rows


# --- preview -------------------------------------------------------------------------------------------------------------------
def tinted(tex: dict[str, Image.Image], model: str, colour: str) -> Image.Image:
    rgb = tuple(int(colour[k:k + 2], 16) for k in (1, 3, 5))
    paint = tex[model]
    out = Image.new("RGBA", paint.size, (0, 0, 0, 0))
    px, po = paint.load(), out.load()
    for y in range(paint.height):
        for x in range(paint.width):
            g, _g, _b, a = px[x, y]
            if a:
                po[x, y] = tuple(c * g // 255 for c in rgb) + (255,)
    out.alpha_composite(tex[f"{model}_detail"])
    return out


def view(model: str, tex: Image.Image, which: str, zoom: int) -> Image.Image:
    """An orthographic view of the model (front, left side or top), parts drawn far to near."""
    boxes = [(p, x, y, z) for p in MODELS[model] for x, y, z in p.at]
    xs = [x for _p, x, _y, _z in boxes] + [x + p.w for p, x, _y, _z in boxes]
    ys = [y for _p, _x, y, _z in boxes]
    zs = [z for _p, _x, _y, z in boxes] + [z + p.d for p, _x, _y, z in boxes]
    if which == "front":
        size, order = (max(xs) - min(xs), -min(ys)), sorted(boxes, key=lambda b: -b[3])
    elif which == "left":
        size, order = (max(zs) - min(zs), -min(ys)), sorted(boxes, key=lambda b: b[1] + b[0].w)
    else:
        size, order = (max(xs) - min(xs), max(zs) - min(zs)), sorted(boxes, key=lambda b: -b[2])
    out = Image.new("RGBA", (size[0] * zoom, size[1] * zoom), (0, 0, 0, 0))
    for p, x, y, z in order:
        fx, fy, fw, fh = cuboid_faces(p.u, p.v, p.w, p.h, p.d)[{"front": "front", "left": "left", "top": "top"}[which]]
        img = tex.crop((fx, fy, fx + fw, fy + fh))
        if which == "front":
            pos = (x - min(xs), y - min(ys))
        elif which == "left":
            pos = (z - min(zs), y - min(ys))
        else:
            pos = (x - min(xs), z - min(zs))
        out.alpha_composite(img.resize((fw * zoom, fh * zoom), Image.NEAREST), (pos[0] * zoom, pos[1] * zoom))
    return out


def preview(all_tex: dict[str, Image.Image]) -> Image.Image:
    zoom, pad = 4, 8
    rows = []
    for model in MODELS:
        tex = tinted(all_tex, model, DEFAULT_COLOUR[model])
        shots = [view(model, tex, w, zoom) for w in ("front", "left", "top")]
        sheets = [all_tex[model], all_tex[f"{model}_detail"]]
        width = sum(s.width + pad for s in shots) + 2 * (SIZE + pad)
        height = max(max(s.height for s in shots), SIZE)
        row = Image.new("RGBA", (width, height), (0, 0, 0, 0))
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
    return sheet


# --- output ----------------------------------------------------------------------------------------------------------------------
def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(data, indent="\t") + "\n").encode("utf-8"))


def documented_layout() -> dict[str, tuple[Part, ...]]:
    """The UV table of this file's docstring, parsed (a test keeps it equal to MODELS)."""
    out: dict[str, list[Part]] = {}
    for line in __doc__.splitlines():
        m = re.fullmatch(r"\s{4}([a-z_]+)\s+([a-z_]+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(.+)", line)
        if not m:
            continue
        at = tuple(tuple(int(n) for n in triple.split(",")) for triple in m.group(8).split())
        out.setdefault(m.group(1), []).append(Part(m.group(2), *(int(m.group(k)) for k in range(3, 8)), at))
    return {k: tuple(v) for k, v in out.items()}


def textures() -> dict[str, Image.Image]:
    out: dict[str, Image.Image] = {}
    for model in MODELS:
        out.update(Painter(model).textures())
    return out


def main() -> None:
    tex = textures()
    for name, im in tex.items():
        write_png(ASSETS / "textures" / "entity" / "vehicle" / f"{name}.png", im)
    rows = map_rows()
    for name, row in rows.items():
        write_json(VEHICLE_MAP / f"{name}.json", row)
    write_png(ART / "preview_vehicles.png", preview(tex))
    print(f"wrote {len(tex)} vehicle textures for {len(MODELS)} models, {len(rows)} vehicle_map rows")


if __name__ == "__main__":
    main()
