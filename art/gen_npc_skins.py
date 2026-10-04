"""Procedural, original Night City NPC skins and the `skin_map` defaults (megaplan 5-A2, sections 1.7 and 1.12).

Run from anywhere:  uv run python art/gen_npc_skins.py      (after art/convert_slim.py, whose cyber skins skin_map points at)

Writes  mc/src/main/resources/assets/cyberblock/textures/entity/npc/<group>_m.png   male, classic model (4 px arms)
        mc/src/main/resources/assets/cyberblock/textures/entity/npc/<group>_f.png   female, slim model (3 px arms)
        mc/src/main/resources/data/cyberblock/cyberblock/skin_map/<row>.json       the skin_map defaults
        art/preview_npc_skins.png                                                    front and back of every skin

Every skin is painted pixel by pixel from a LOOK row (garment kinds, colours, hair, headwear, eyewear, face marks, cyberware)
through the vanilla 64x64 layout of art/skin_layout.py: base layers fully opaque, overlays (jackets, helmets, visors, hair
volume, coat tails, skirts) where the look has them, unused pixels transparent. The colour schemes and motifs are our own: plain
stripes, slashes, dots, studs, circuits, camouflage blotches; no logos, no lettering, nothing traced from any game or skin.
Deterministic: each skin draws from random.Random(<skin id>), so two runs give byte-identical files.

skin_map rows (one JSON object per file, schema in shared/content-schemas/skin_map.schema.json): an NPC matches a row when
each of the row's `role`, `faction`, `gender` and `archetype` keys (a value or a list of values) holds for it. Of the matching
rows the highest `priority` wins, then the row with more match keys, then, among rows still tied, one picked by the NPC id
(stable, so a crowd of civilians wears a mix of skins). Rows with `cyber` are the linked players' cyber skins and never match
an NPC. Values: `faction` = Affiliation().EnumName() names of 2.31 (`gamedataAffiliation`), `role` = vendor type names
(`gamedataVendorType`: RipperDoc, Clothes, ...) or the content roles fixer, bartender, medic, mechanic, cyberpsycho,
`archetype` = `gamedataArchetypeType` names, `gender` = male | female (the npcs `g` 1 | 2; 0 matches no gender row, so an NPC
of unknown gender falls to a row without `gender`, which is always the classic model).
"""
from __future__ import annotations

import json
import random
from dataclasses import dataclass, replace
from pathlib import Path

from PIL import Image

import preview_skins
from convert_slim import write_png
from skin_layout import ARMS, PARTS, faces

ART = Path(__file__).resolve().parent
RESOURCES = ART.parent / "mc" / "src" / "main" / "resources"
ASSETS = RESOURCES / "assets" / "cyberblock"
SKIN_MAP = RESOURCES / "data" / "cyberblock" / "cyberblock" / "skin_map"
NAMESPACE = "cyberblock"

Color = tuple[int, int, int]


class Glow(tuple):
    """A colour drawn as it is: no face shading, no grain (lit eyes, visors, glowing trims)."""


def rgb(h: str) -> Color:
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def clamp(v: float) -> int:
    return max(0, min(255, round(v)))


def scale(c: Color, f: float) -> Color:
    return clamp(c[0] * f), clamp(c[1] * f), clamp(c[2] * f)


def mix(a: Color, b: Color, t: float) -> Color:
    return clamp(a[0] + (b[0] - a[0]) * t), clamp(a[1] + (b[1] - a[1]) * t), clamp(a[2] + (b[2] - a[2]) * t)


def noise(i: int, j: int, salt: int) -> float:
    """A fixed hash of a pixel position in [0, 1): stable blotches and specks without consuming the random stream."""
    n = (i * 73856093) ^ (j * 19349663) ^ (salt * 83492791)
    n = (n ^ (n >> 13)) * 1274126177
    return ((n ^ (n >> 16)) & 0xFFFF) / 0x10000


TONES = ("#f2d3b8", "#e6b993", "#d29c74", "#b47c55", "#8d5b3a", "#6a4229", "#4b2f1e", "#c48a65")
METAL, METAL_DARK, METAL_LIGHT = rgb("#8e949c"), rgb("#4a4f57"), rgb("#c3c8cf")
BELT, BUCKLE, WHITE, LENS = rgb("#1d1815"), rgb("#b7bcc4"), rgb("#eef0f2"), rgb("#121418")
FACE_SHADE = {"top": 1.07, "front": 1.0, "right": 0.87, "left": 0.87, "back": 0.93, "bottom": 0.74}
LONG_HAIR = ("long", "bob", "dreads")
FACE_SALT = {"top": 1, "bottom": 2, "right": 3, "front": 4, "left": 5, "back": 6}


@dataclass(frozen=True)
class Look:
    """One skin's design. Colours are #RRGGBB; kinds are the painters below."""
    top: str                     # tee tank crop shirt hoodie jacket suit coat vest apron armour labcoat coverall rags poncho
    top_col: str
    under_col: str               # the shirt under a jacket, suit, vest, apron or coat; the undersuit under armour
    trim: str                    # accent colour: motif, tie, stripes, buttons, glowing lines
    legs: str = "pants"          # pants jeans cargo shorts skirt armour
    legs_col: str = "#26282e"
    shoes: str = "#16171b"
    boots: bool = False
    hair: str = "short"          # bald buzz short slick mohawk undercut ponytail long bob dreads
    hair_col: str = "#231a14"
    hat: str = "none"            # none cap beanie hard_hat sunhat helmet full_helmet hood headband face_bandana mask headset
    hat_col: str = "#2a2c30"
    eyes: str = "plain"          # plain glow shades visor goggles optics loupe
    eye_col: str = "#3b4f66"
    face: tuple[str, ...] = ()   # beard stubble tattoo dots scars plate jaw makeup
    face_col: str = "#1f2a2a"
    motif: str = "none"          # stripes slashes chevron dots circuit chain studs camo flowers band lines hi_vis badge bowtie check
    cyber: tuple[str, ...] = ()  # right_arm left_arm legs
    gloves: str | None = None
    sleeves: str | None = None   # long short none; None = the garment's own
    skirt_col: str = "#1c1c22"
    arm_tattoo: str | None = None
    tones: tuple[str, ...] = TONES
    glow_trim: bool = False      # the trim is a lit line (circuits, cyberware seams)


@dataclass(frozen=True)
class Group:
    id: str
    male: Look
    female: Look
    factions: tuple[str, ...] = ()
    roles: tuple[str, ...] = ()
    archetypes: tuple[str, ...] = ()
    priority: int = 0


def group(id: str, male: Look, female: dict, **match) -> Group:
    return Group(id, male, replace(male, **female), **match)


SLEEVES = {"tee": "short", "tank": "none", "crop": "none", "rags": "short", "apron": "short"}
JACKETS = ("jacket", "coat", "labcoat")


class Painter:
    """Paints one 64x64 skin from a Look; female skins use the slim arms and a few softer features."""

    def __init__(self, look: Look, female: bool, seed: str):
        self.l, self.female, self.slim = look, female, female
        self.rnd = random.Random(seed)
        self.skin = rgb(self.rnd.choice(look.tones))
        self.c = {k: rgb(getattr(look, k)) for k in ("top_col", "under_col", "trim", "legs_col", "shoes", "hair_col", "hat_col",
                                                     "eye_col", "face_col", "skirt_col")}
        self.sleeves = look.sleeves or SLEEVES.get(look.top, "long")

    # --- the whole skin -----------------------------------------------------------------------------------------------------
    def paint(self) -> Image.Image:
        im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        for part in PARTS:
            for layer in ("base", "overlay"):
                for face, (x0, y0, w, h) in faces(part, layer, self.slim).items():
                    for j in range(h):
                        for i in range(w):
                            c = self.pixel(part, layer, face, i, j, w)
                            if c is None:
                                if layer == "base":
                                    raise ValueError(f"{part} {face} ({i}, {j}): the base layer must be opaque")
                                continue
                            im.putpixel((x0 + i, y0 + j), (*self.finish(c, part, face, i, w), 255))
        return im

    def finish(self, c: Color, part: str, face: str, i: int, w: int) -> Color:
        if isinstance(c, Glow):
            return tuple(c)
        f = FACE_SHADE[face]
        if part != "head" and face in ("front", "back") and i in (0, w - 1):
            f *= 0.95  # rounded edges on limbs and torso
        return scale(c, f * (1 + self.rnd.uniform(-0.025, 0.025)))

    def pixel(self, part: str, layer: str, face: str, i: int, j: int, w: int) -> Color | None:
        base = layer == "base"
        if part == "head":
            return self.head_base(face, i, j) if base else self.head_over(face, i, j)
        if part == "body":
            return self.torso_base(face, i, j, w) if base else self.torso_over(face, i, j, w)
        if part in ARMS:
            return self.arm_base(part, face, i, j, w) if base else self.arm_over(part, face, i, j, w)
        return self.leg_base(part, face, i, j, w) if base else self.leg_over(part, face, i, j, w)

    # --- head -------------------------------------------------------------------------------------------------------------------
    @staticmethod
    def fwd(face: str, i: int, w: int) -> int:
        """On a side face: 0 for the column at the front edge, growing towards the back."""
        return w - 1 - i if face == "right" else i

    def hair_mask(self, face: str, i: int, j: int) -> bool:
        st = self.l.hair
        if st == "bald":
            return False
        fz = self.fwd(face, i, 8) if face in ("right", "left") else 0
        if st == "undercut" and face == "left":
            st = "long"  # the long side
        if face == "top":
            return True
        if face == "bottom":
            return False
        if face == "front":
            return {"buzz": j == 0, "mohawk": j == 0 and i in (3, 4),
                    "undercut": (j <= 1 and i >= 3) or (j == 2 and i >= 5),
                    "long": j <= 1 or i in (0, 7) or (j == 2 and i in (1, 6)), "dreads": j <= 1 or i in (0, 7),
                    "bob": j <= 1 or (i in (0, 7) and j <= 6)}.get(st, j <= 1 or (j == 2 and i in (0, 7)))
        if face == "back":
            return {"buzz": j <= 3, "mohawk": j <= 2 or i in (3, 4), "ponytail": j <= 4 or i in (3, 4), "slick": j <= 5,
                    "undercut": j <= 5, "bob": j <= 6}.get(st, True if st in ("long", "dreads") else j <= 4)
        # side faces
        if st in ("long", "dreads"):
            return not (fz <= 1 and j >= 3)
        if st == "bob":
            return not (fz <= 1 and j >= 3) and j <= 6
        return {"buzz": j <= 1 and fz >= 1, "mohawk": j <= 1, "undercut": j <= 1}.get(st, j <= 2 or (fz >= 3 and j <= 3))

    def hair_colour(self, face: str, i: int, j: int) -> Color:
        hc, st = self.c["hair_col"], self.l.hair
        shaved = mix(self.skin, hc, 0.35)
        if st == "mohawk" and not ((face in ("top", "back") and i in (3, 4)) or (face == "front" and i in (3, 4))):
            return shaved
        if st == "undercut" and (face == "right" or (face == "top" and i < 3)):
            return shaved
        if st == "buzz":
            return mix(hc, self.skin, 0.25)
        if st == "dreads" and (i + j // 2) % 2 == 0:
            return scale(hc, 0.72)
        if st == "slick" and face == "top" and i % 3 == 1:
            return scale(hc, 1.3)
        return scale(hc, 0.9) if j % 3 == 2 else hc

    def head_base(self, face: str, i: int, j: int) -> Color:
        if face == "bottom":
            return scale(self.skin, 0.85)
        if self.hair_mask(face, i, j):
            return self.hair_colour(face, i, j)
        if face == "front":
            return self.face_front(i, j)
        if face in ("right", "left"):
            return self.face_side(face, i, j)
        return self.skin if face != "top" else scale(self.skin, 0.95)

    def face_side(self, face: str, i: int, j: int) -> Color:
        fz, marks = self.fwd(face, i, 8), self.l.face
        if "jaw" in marks and j >= 6 and fz <= 3:
            return METAL_DARK if j == 6 else METAL
        if "plate" in marks and face == "right" and 2 <= j <= 7 and fz <= 4:
            return METAL_DARK if j in (2, 7) or fz == 4 else METAL
        if "beard" in marks and j >= 5 and fz <= 3:
            return self.c["hair_col"]
        if "stubble" in marks and j >= 6 and fz <= 3:
            return mix(self.skin, self.c["hair_col"], 0.3)
        if fz in (4, 5) and j in (4, 5):
            return scale(self.skin, 0.86)  # the ear
        return self.skin

    def face_front(self, i: int, j: int) -> Color:
        s, l, marks, ec = self.skin, self.l, self.l.face, self.c["eye_col"]
        if "plate" in marks and i <= 3 and 2 <= j <= 7:
            if j == 4 and i in (1, 2):
                return Glow(ec)
            return METAL_DARK if j in (2, 7) or i == 3 else METAL
        if "jaw" in marks and j >= 6:
            if j == 6 and 2 <= i <= 5:
                return rgb("#d9d7cc") if i % 2 else rgb("#9a9a92")  # chrome teeth
            return METAL
        if j == 4:
            if l.eyes == "optics":
                return Glow(ec) if i in (1, 2, 5, 6) else (METAL if i in (0, 7) else s)
            if l.eyes == "glow" and i in (1, 2, 5, 6):
                return Glow(ec if i in (2, 5) else mix(ec, WHITE, 0.45))
            if i in (1, 6):
                return WHITE
            if i in (2, 5):
                return ec
            if self.female and i in (0, 7):
                return rgb("#1a1416")  # eyeliner
        if l.eyes == "optics" and (j, i) in ((3, 2), (5, 6), (3, 6)):
            return Glow(scale(ec, 0.8))
        if j == 3 and i in (1, 2, 5, 6):
            return scale(self.c["hair_col"], 0.85) if l.hair != "bald" else scale(s, 0.78)
        if "beard" in marks and (j == 7 or (j == 6 and i not in (3, 4)) or (j == 5 and i in (0, 1, 2, 5, 6, 7))):
            return self.c["hair_col"]
        if j == 6 and i in (3, 4):
            if "makeup" in marks:
                return self.c["face_col"]
            if "beard" in marks:
                return scale(self.c["hair_col"], 0.6)
            return mix(s, rgb("#b0404c"), 0.35) if self.female else scale(s, 0.72)
        if "stubble" in marks and j >= 6:
            return mix(s, self.c["hair_col"], 0.3)
        if "tattoo" in marks and (i, j) in ((1, 5), (1, 6), (6, 5), (6, 6), (0, 5), (7, 5)):
            return self.c["face_col"]
        if "dots" in marks and (i, j) in ((2, 1), (5, 1), (0, 6), (7, 6), (3, 7), (4, 7), (3, 2), (4, 2)):
            return self.c["face_col"]
        if "scars" in marks and (i, j) in ((5, 2), (5, 3), (6, 5), (6, 6), (5, 7)):
            return mix(s, rgb("#8a4a4a"), 0.45)
        if "makeup" in marks and j == 3 and i in (0, 7):
            return self.c["face_col"]
        if j == 5 and i in (3, 4):
            return scale(s, 0.9)  # the nose
        return s

    def head_over(self, face: str, i: int, j: int) -> Color | None:
        if face == "bottom":
            return None
        for layer in (self.eyewear, self.hat, self.hair_volume):
            c = layer(face, i, j)
            if c is not None:
                return c
        return None

    def eyewear(self, face: str, i: int, j: int) -> Color | None:
        e, ec = self.l.eyes, self.c["eye_col"]
        if self.l.hat in ("helmet", "full_helmet"):
            return None  # the helmet draws its own visor
        side = face in ("right", "left")
        fz = self.fwd(face, i, 8) if side else 0
        if e == "shades":
            if face == "front" and j == 4:
                return (rgb("#5d6474") if i in (2, 6) else LENS) if i in (1, 2, 5, 6) else rgb("#2b2c33")
            if side and j == 4 and fz <= 3:
                return rgb("#2b2c33")
        if e == "visor":
            if face == "front" and j in (3, 4):
                return Glow(mix(ec, WHITE, 0.3) if j == 3 else ec)
            if side and j in (3, 4) and fz <= 1:
                return scale(ec, 0.7)
        if e == "goggles":
            if face == "front" and j in (3, 4):
                return Glow(mix(ec, WHITE, 0.3) if j == 3 else ec) if i in (1, 2, 5, 6) else rgb("#3a3026")
            if face in ("right", "left", "back") and j == 4:
                return rgb("#3a3026")
        if e == "loupe":
            if face == "front" and i in (5, 6) and 3 <= j <= 5:
                return Glow(ec) if j == 4 else rgb("#b08a3a")
            if face == "left" and j == 4 and fz <= 1:
                return rgb("#b08a3a")
        return None

    def hat(self, face: str, i: int, j: int) -> Color | None:
        h, hc = self.l.hat, self.c["hat_col"]
        if h == "none":
            return None
        side = face in ("right", "left")
        fz = self.fwd(face, i, 8) if side else 0
        if h == "cap":
            if face == "top":
                return hc
            if j <= 1:
                return scale(hc, 0.78) if face == "front" and j == 1 else hc
            return scale(hc, 0.8) if face == "back" and j == 2 and i in (3, 4) else None
        if h == "beanie":
            return hc if face == "top" else (scale(hc, 0.8) if j == 2 else hc) if j <= 2 else None
        if h == "hard_hat":
            if face == "top":
                return scale(hc, 1.15) if i in (3, 4) else hc
            return (scale(hc, 0.85) if j == 1 and face == "front" else hc) if j <= 1 else None
        if h == "sunhat":
            return hc if face == "top" else (self.c["trim"] if j == 1 else hc) if j <= 1 else None
        if h == "helmet":
            if face in ("top", "back"):
                return hc if face == "top" or j != 5 else scale(hc, 0.8)
            if side:
                return hc if fz >= 1 or j <= 2 or j >= 6 else None
            if j <= 2:
                return scale(hc, 0.85) if j == 2 else hc
            if 3 <= j <= 5:
                return Glow(mix(self.c["eye_col"], WHITE, 0.3) if j == 3 else self.c["eye_col"]) if self.l.eyes == "visor" else None
            return hc if i in (0, 1, 6, 7) else None
        if h == "full_helmet":
            if face == "front":
                if j in (3, 4) and 1 <= i <= 6:
                    return Glow(self.c["eye_col"] if j == 4 else mix(self.c["eye_col"], WHITE, 0.35))
                if j == 6 and i in (2, 5):
                    return scale(hc, 0.6)  # vents
                return scale(hc, 1.12) if j == 0 else hc
            if face == "top":
                return scale(hc, 1.1) if i in (3, 4) else hc
            return scale(hc, 0.82) if j == 5 else hc
        if h == "hood":
            if face in ("top", "back"):
                return hc
            if side:
                return hc if fz >= 1 or j <= 2 else None
            return (scale(hc, 0.7) if j == 1 else hc) if j <= 1 or (i in (0, 7) and j <= 6) else None
        if h == "headband":
            if face in ("front", "right", "left", "back") and j in (1, 2):
                return self.c["trim"] if noise(i, j, FACE_SALT[face]) < 0.2 else hc
            return hc if face == "back" and j in (3, 4) and i in (3, 4) else None
        if h == "face_bandana":
            if face == "front" and j >= 5:
                return self.c["trim"] if noise(i, j, 9) < 0.22 else hc
            return hc if side and j >= 5 and fz <= 4 else None
        if h == "mask":
            if face == "front" and j >= 5 and 1 <= i <= 6:
                return rgb("#5a5e66") if j == 6 and i in (2, 5) else rgb("#2b2d31")
            return rgb("#2b2d31") if side and j == 5 and fz <= 3 else None
        if h == "headset":
            if side and j in (3, 4, 5) and fz in (3, 4):
                return Glow(self.c["trim"]) if j == 4 and fz == 3 else rgb("#202226")
            return rgb("#202226") if face == "top" and j == 4 else None
        raise ValueError(f"unknown hat {h!r}")

    def hair_volume(self, face: str, i: int, j: int) -> Color | None:
        st = self.l.hair
        if self.l.hat != "none":
            return None
        if st in LONG_HAIR and self.hair_mask(face, i, j):
            return scale(self.hair_colour(face, i, j), 1.08)
        if st == "mohawk" and ((face in ("top", "back") and i in (3, 4) and (face == "top" or j <= 5)) or (face == "front" and j == 0 and i in (3, 4))):
            return scale(self.c["hair_col"], 1.15)
        if st == "undercut" and face == "left" and self.hair_mask(face, i, j):
            return scale(self.c["hair_col"], 1.08)
        return None

    # --- torso ------------------------------------------------------------------------------------------------------------------
    def garment(self, face: str, i: int, j: int) -> tuple[Color, str]:
        """The torso's base pixel and its kind: main (takes the motif), inner, skin or detail."""
        t, top, under, s = self.l.top, self.c["top_col"], self.c["under_col"], self.skin
        front = face == "front"
        if t in ("tank", "crop"):
            if (front or face == "back") and ((j == 0 and 2 <= i <= 5) or (front and j == 1 and i in (3, 4))):
                return s, "skin"
            if t == "crop" and j >= 7:
                return s, "skin"
            return top, "main"
        if t in ("tee", "apron", "poncho"):
            col = under if t != "tee" else top
            if front and j == 0 and i in (3, 4):
                return s, "skin"
            return col, "main" if t == "tee" else "inner"
        if t in ("shirt", "vest"):
            col = top if t == "shirt" else under
            if front and j == 0:
                return (s, "skin") if i in (3, 4) else (scale(col, 1.15), "detail")
            if front and i == 4 and j % 2 == 1:
                return scale(col, 0.85), "detail"
            return col, "main" if t == "shirt" else "inner"
        if t == "hoodie":
            if front and 6 <= j <= 8 and 1 <= i <= 6:
                return scale(top, 0.72 if j == 6 else 0.86), "detail"
            if front and 1 <= j <= 3 and i in (2, 5):
                return under, "detail"
            if front and j == 0 and i in (3, 4):
                return scale(top, 0.62), "detail"
            if face == "back" and j <= 1:
                return scale(top, 0.84), "detail"
            return top, "main"
        if t in ("jacket", "coat", "suit", "labcoat"):
            inner = t == "labcoat" or (front and 2 <= i <= 5)
            if not inner:
                return top, "main"
            if t == "suit" and i in (3, 4) and j <= 7 and not (j == 7 and i == 4):
                return (scale(self.c["trim"], 0.8) if j == 0 else self.c["trim"]), "detail"
            if front and j == 0 and i in (3, 4):
                return s, "skin"
            return under, "inner"
        if t == "armour":
            if j in (3, 7):
                return scale(top, 0.78), "detail"
            return top, "main"
        if t == "coverall":
            if front and i == 3 and j <= 9:
                return scale(top, 0.8), "detail"
            if front and j in (2, 3) and i in (1, 2, 5, 6):
                return scale(top, 0.86), "detail"
            return (scale(top, 1.15), "detail") if front and j == 0 else (top, "main")
        if t == "rags":
            if noise(i // 2, j // 3, FACE_SALT[face]) < 0.3:
                return under, "detail"
            return (s, "skin") if noise(i, j, 77 + FACE_SALT[face]) < 0.05 else (top, "main")
        raise ValueError(f"unknown top {t!r}")

    def motif(self, face: str, i: int, j: int, c: Color) -> Color:
        m, tr = self.l.motif, self.c["trim"]
        lit = Glow(tr) if self.l.glow_trim else tr
        front, back = face == "front", face == "back"
        if m == "stripes" and j in (3, 6):
            return tr
        if m == "slashes" and back and 1 <= j <= 8 and i + j in (5, 8, 11):
            return tr  # three claw slashes across the back
        if m == "slashes" and front and i >= 4 and 1 <= j <= 6 and i + j in (8, 11):
            return tr
        if m == "chevron" and (front or back) and 2 <= j <= 5 and j - 2 == min(i, 7 - i):
            return tr
        if m == "dots" and (i * 3 + j * 2) % 7 == 0:
            return tr
        if m == "circuit":
            if front and ((j == 3 and i <= 4) or (i == 4 and 3 <= j <= 7) or (j == 7 and i >= 4)):
                return lit
            if back and ((i == 2 and 1 <= j <= 8) or (j == 8 and i >= 2) or (j == 4 and 2 <= i <= 6)):
                return lit
            if face in ("right", "left") and j == 5:
                return lit
        if m == "chain" and front and (i, j) in ((1, 1), (6, 1), (2, 2), (5, 2), (3, 3), (4, 3)):
            return tr
        if m == "chain" and front and (i, j) in ((3, 4), (4, 4)):
            return scale(tr, 1.2)
        if m == "studs" and ((front and i in (0, 7) and j % 2 == 0 and j <= 8) or (back and j in (1, 3) and i % 2 == 0)):
            return METAL_LIGHT
        if m == "camo":
            n = noise(i // 2 + FACE_SALT[face] * 5, j // 2, 31)
            return scale(c, 0.74) if n < 0.3 else mix(c, self.c["under_col"], 0.55) if n < 0.5 else c
        if m == "flowers":
            n = noise(i, j, 40 + FACE_SALT[face])
            return tr if n < 0.13 else self.c["under_col"] if n < 0.22 else c
        if m == "band" and j in (4, 5):
            return tr
        if m == "lines" and (front or back) and i in (1, 6) and 1 <= j <= 9:
            return lit
        if m == "hi_vis" and j in (6, 7):
            return Glow(tr) if j == 6 else tr
        if m == "badge" and front and i in (5, 6) and j in (2, 3):
            return rgb("#d6bf6e") if (i + j) % 2 else rgb("#a8914a")
        if m == "bowtie" and front and j == 0 and 2 <= i <= 5:
            return scale(tr, 0.75) if i in (3, 4) else tr
        if m == "check":
            if i % 4 == 0 or j % 4 == 0:
                return tr
            return scale(c, 0.86) if (i // 2 + j // 2) % 2 == 0 else c
        return c

    def chest(self, face: str, i: int, j: int, c: Color) -> Color:
        """A hint of shape on female torsos (one softly shaded row)."""
        return scale(c, 0.88) if self.female and face == "front" and j == 4 and i in (1, 2, 5, 6) else c

    def torso_base(self, face: str, i: int, j: int, w: int) -> Color:
        t = self.l.top
        if face == "top":
            if t in ("tank", "crop"):
                return self.c["top_col"] if i in (1, 2, 5, 6) else self.skin
            return self.c["top_col"] if t not in ("vest", "apron", "poncho", "labcoat") else self.c["under_col"]
        if face == "bottom":
            return self.c["legs_col"]
        if j >= 10:
            if t == "coverall":
                return self.c["top_col"]
            if j == 10:
                return BUCKLE if face == "front" and i in (3, 4) else BELT
            return self.c["legs_col"]
        c, kind = self.garment(face, i, j)
        if kind == "main":
            c = self.motif(face, i, j, c)
        return self.chest(face, i, j, c) if kind in ("main", "inner") else c

    def torso_over(self, face: str, i: int, j: int, w: int) -> Color | None:
        t, top, front = self.l.top, self.c["top_col"], face == "front"
        if face == "bottom":
            return None
        hair = self.l.hair if self.l.hat not in ("helmet", "full_helmet", "hood") else "none"
        if face == "back" and ((hair in ("long", "dreads") and j <= 3) or (hair == "ponytail" and j <= 4 and i in (3, 4))):
            return scale(self.hair_colour("back", i, j), 1.05)
        if face == "top":
            return top if t in ("jacket", "coat", "labcoat", "vest", "poncho") else None
        if t in JACKETS:
            if j >= 10 and t == "jacket":
                return None
            if front and 2 <= i <= 5:
                return scale(top, 1.12) if j == 0 and i in (2, 5) else None
            c = top
            if t == "labcoat" and noise(i, j, FACE_SALT[face]) < 0.06:
                return self.c["trim"]
            return self.chest(face, i, j, self.motif(face, i, j, c) if t != "labcoat" else c)
        if t == "vest":
            if j >= 10 or (front and 3 <= i <= 4) or (front and j <= 2 and i in (2, 5)):
                return None
            if front and i in (2, 5) and j in (4, 7):
                return self.c["trim"]
            return self.chest(face, i, j, self.motif(face, i, j, top))
        if t == "apron":
            if front and 1 <= i <= 6 and j >= 2:
                return top
            if front and j <= 1 and i in (1, 6):
                return scale(top, 0.8)
            return scale(top, 0.8) if face in ("right", "left", "back") and j == 6 else None
        if t == "armour":
            if front and 1 <= j <= 6 and 1 <= i <= 6:
                edge = j in (1, 6) or i in (1, 6)
                return scale(top, 0.9) if edge else self.motif(face, i, j, scale(top, 1.14))
            if face == "back" and 1 <= j <= 7 and 1 <= i <= 6:
                return scale(top, 1.08)
            return None
        if t == "poncho":
            if front and j == 0 and i in (3, 4):
                return None
            if j > 10:
                return None
            return self.c["trim"] if j % 4 == 1 else self.c["under_col"] if j % 4 == 2 else top
        if t == "rags":
            return scale(top, 0.8) if j >= 9 and noise(i, j, 5 + FACE_SALT[face]) < 0.5 else None
        if t == "hoodie" and face == "back" and j <= 2 and 1 <= i <= 6:
            return scale(top, 0.92)
        return None

    # --- arms -------------------------------------------------------------------------------------------------------------------
    def metal(self, face: str, i: int, j: int) -> Color:
        if face == "top":
            return METAL_LIGHT
        if face == "bottom" or j == 9:
            return METAL_DARK
        if j in (3, 7):
            return scale(METAL, 0.72)
        if j >= 10:
            return METAL_LIGHT if (i + j) % 2 else METAL
        if j == 5 and face in ("right", "left") and self.l.glow_trim:
            return Glow(self.c["trim"])
        return METAL

    def sleeve(self, face: str, i: int, j: int) -> Color:
        t = self.l.top
        col = self.c["under_col"] if t in ("vest", "apron", "poncho", "armour") else self.c["top_col"]
        if j in (9, 3) and j == {"long": 9, "short": 3}.get(self.sleeves):
            return scale(col, 0.82)  # the cuff
        if self.l.motif in ("hi_vis", "stripes") and j == 6 and t in ("coverall", "jacket", "hoodie"):
            return self.c["trim"]
        return col

    def arm_base(self, part: str, face: str, i: int, j: int, w: int) -> Color:
        if face == "top":
            j = 0
        elif face == "bottom":
            j = 11
        sleeve_rows = {"long": 10, "short": 4, "none": 0}[self.sleeves]
        if j < sleeve_rows:
            return self.sleeve(face, i, j)
        if j >= 10 and self.l.gloves:
            g = rgb(self.l.gloves)
            return scale(g, 0.85) if j == 11 and i % 2 else g
        if part in self.l.cyber:
            return self.metal(face, i, j)
        if self.l.arm_tattoo and face in ("front", "right", "left") and 2 <= j <= 8 and (i + j) % 3 == 0:
            return rgb(self.l.arm_tattoo)
        return scale(self.skin, 0.94) if j == 11 else self.skin

    def arm_over(self, part: str, face: str, i: int, j: int, w: int) -> Color | None:
        t, top = self.l.top, self.c["top_col"]
        if face == "bottom":
            return None
        if face == "top":
            j = 0
        if t in JACKETS and j < min(9, {"long": 10, "short": 4, "none": 0}[self.sleeves]):
            return top
        if t == "armour":
            if j <= 3:
                return self.c["trim"] if j == 3 else scale(top, 1.1)
            if 6 <= j <= 8 and face in ("front", "right", "left"):
                return top
            return None
        if t == "poncho" and j <= 4:
            return self.c["trim"] if j % 4 == 1 else top
        return None

    # --- legs -------------------------------------------------------------------------------------------------------------------
    def leg_base(self, part: str, face: str, i: int, j: int, w: int) -> Color:
        legs, lc = self.l.legs, self.c["legs_col"]
        if self.l.top == "coverall":
            lc = self.c["top_col"]
        if face == "top":
            return lc
        if face == "bottom":
            return scale(self.c["shoes"], 0.55)
        shoe_row = 8 if self.l.boots else 10
        if j >= shoe_row:
            sh = self.c["shoes"]
            if j == shoe_row and face == "front":
                return scale(sh, 1.25)
            return scale(sh, 0.8) if j == 11 else sh
        outer = (part == "right_leg" and face == "right") or (part == "left_leg" and face == "left")
        if legs == "shorts" and j >= 5:
            if "legs" in self.l.cyber:
                return self.metal(face, i, j)
            return WHITE if j == shoe_row - 1 else self.skin
        if "legs" in self.l.cyber and j >= 4:
            return self.metal(face, i, j)
        if legs == "jeans":
            if j == shoe_row - 1:
                return scale(lc, 1.18)
            if outer and i == 1:
                return scale(lc, 1.12)
        if legs == "cargo" and outer and 4 <= j <= 6 and 1 <= i <= 2:
            return scale(lc, 0.7 if j == 4 else 0.84)
        if legs == "armour" and face == "front" and j in (5, 6):
            return scale(lc, 1.3 if j == 5 else 1.15)
        if self.l.top == "coverall" and self.l.motif == "hi_vis" and j == 7:
            return self.c["trim"]
        if self.l.top == "rags" and noise(i, j, 60 + FACE_SALT[face] + (part == "left_leg") * 9) < 0.1:
            return self.c["under_col"]
        return lc

    def leg_over(self, part: str, face: str, i: int, j: int, w: int) -> Color | None:
        t, top = self.l.top, self.c["top_col"]
        if face in ("top", "bottom"):
            return None
        inner = (part == "right_leg" and face == "left") or (part == "left_leg" and face == "right")
        if self.l.legs == "skirt" and j <= 4:
            return scale(self.c["skirt_col"], 0.85) if j == 4 else self.c["skirt_col"]
        if t in ("coat", "labcoat") and j <= 7 and not inner:
            if face == "front" and ((part == "right_leg" and i == w - 1) or (part == "left_leg" and i == 0)):
                return None
            if t == "labcoat" and noise(i, j, 50 + FACE_SALT[face]) < 0.05:
                return self.c["trim"]
            return top
        if t == "apron" and face == "front" and j <= 4 and ((part == "right_leg" and i >= 1) or (part == "left_leg" and i <= 2)):
            return top
        if self.l.legs == "armour" and face == "front" and j in (5, 6):
            return scale(self.c["legs_col"], 1.3)
        return None


# --- the looks -----------------------------------------------------------------------------------------------------------------
LIGHT, MID, DARK = TONES[:3], TONES[2:6], TONES[4:7]
GROUPS: list[Group] = [
    # civilians
    group("civ_corpo", Look("suit", "#3a3f4b", "#dfe3e8", "#8a2f45", legs_col="#30343c", shoes="#111317", hair="slick",
                            hair_col="#2a201a", eye_col="#3b5d7a"),
          dict(hair="bob", hair_col="#4a2c1d", top_col="#2f3540", trim="#2e6f8e", legs="skirt", skirt_col="#2f3540",
               legs_col="#1c1d22", face=("makeup",), face_col="#8a3346"),
          factions=("Civilian", "UnaffiliatedCorpo", "Biotechnica", "CityCouncil", "News54", "OA", "RecordingAgency", "Zetatech")),
    group("civ_street", Look("hoodie", "#5b6b4a", "#e8e4d6", "#e8c547", legs="jeans", legs_col="#3b4f72", shoes="#d8d8d0",
                             hair="short", hair_col="#1d1712"),
          dict(top_col="#7a3b5e", hair="ponytail", hair_col="#6b3a22"),
          factions=("Civilian", "Unaffiliated")),
    group("civ_worker", Look("coverall", "#4c6a8a", "#2d3a48", "#f2a33a", motif="hi_vis", shoes="#3a2c22", boots=True,
                             hair="buzz", hair_col="#2a2018", hat="hard_hat", hat_col="#e8b923", gloves="#4a3a2a",
                             face=("stubble",)),
          dict(hair="ponytail", hair_col="#2b1d14", face=()),
          factions=("Civilian", "Unaffiliated")),
    group("civ_tourist", Look("shirt", "#3fb8a6", "#f4e3a1", "#e9567b", motif="flowers", sleeves="short", legs="shorts",
                              legs_col="#d9c7a0", shoes="#f0f0ea", hair="short", hair_col="#b8955a", hat="sunhat",
                              hat_col="#d8c38a", eyes="shades", tones=LIGHT),
          dict(top_col="#f08a5d", under_col="#fff1c8", trim="#3a9ad9", hair="long", hair_col="#c9a46a", hat="none"),
          factions=("Civilian",)),
    group("civ_homeless", Look("rags", "#5a5246", "#6e5f4a", "#8a7a5a", legs_col="#3e3a33", shoes="#2a2622", hair="short",
                               hair_col="#4a4038", hat="beanie", hat_col="#4a3f3a", face=("beard",)),
          dict(hair="long", hair_col="#4a3a2e", hat="hood", hat_col="#5a5246", face=()),
          factions=("Civilian", "Unaffiliated")),
    # gangs
    group("tyger_claws", Look("jacket", "#0f5e5a", "#141618", "#e3c25a", motif="slashes", legs_col="#17191d", shoes="#0e0f12",
                              hair="slick", hair_col="#0d0d10", face=("tattoo",), face_col="#11615a", arm_tattoo="#1c6f66"),
          dict(top="crop", hair="long", hair_col="#16141c", face=("tattoo", "makeup"), face_col="#0f5e5a",
               legs="skirt", skirt_col="#0f5e5a", legs_col="#141518"),
          factions=("TygerClaws",)),
    group("valentinos", Look("tank", "#f0ece2", "#1b1b1f", "#d9a93a", motif="chain", legs_col="#1b1b1f", shoes="#5a3a22",
                             hair="slick", hair_col="#120e0c", arm_tattoo="#2a2a30", tones=MID),
          dict(top="crop", top_col="#1a1a1e", hair="long", face=("makeup",), face_col="#a8243a", legs_col="#4a1220",
               arm_tattoo=None),
          factions=("Valentinos",)),
    group("maelstrom", Look("tank", "#151517", "#3a0d0d", "#d22a2a", motif="studs", legs_col="#1d1d20", shoes="#0f0f11", boots=True,
                            hair="mohawk", hair_col="#c21f1f", eyes="optics", eye_col="#ff2b2b", face=("plate",),
                            cyber=("right_arm", "left_arm"), glow_trim=True),
          dict(hair="undercut", hair_col="#1a1a1a", cyber=("right_arm",), face=("plate", "makeup"), face_col="#5a0f14"),
          factions=("Maelstrom", "MaelstromAndroid")),
    group("sixth_street", Look("jacket", "#4a5233", "#2c2f2a", "#c8b26a", motif="camo", legs="cargo", legs_col="#3d4430",
                               shoes="#2a2418", boots=True, hair="buzz", hair_col="#3a2a1c", hat="cap", hat_col="#2c3a5a"),
          dict(hair="ponytail", hair_col="#5a3a1e", hat="none"),
          factions=("SixthStreet", "SixthStreetAndroid")),
    group("animals", Look("tank", "#3a2f28", "#2a2420", "#c9a24a", motif="stripes", legs_col="#2a2420", shoes="#1a1614",
                          boots=True, hair="bald", face=("scars",), gloves="#3a2a20", tones=MID + DARK),
          dict(hair="bob", hair_col="#1a1410", face=("scars",)),
          factions=("Animals",)),
    group("scavengers", Look("rags", "#4b4a3e", "#6b3a2a", "#a83a2a", legs="cargo", legs_col="#3a3a32", shoes="#241f1a",
                             boots=True, hair="short", hair_col="#2a2420", hat="face_bandana", hat_col="#7a2a22",
                             eyes="goggles", eye_col="#d0a848", cyber=("left_arm",)),
          dict(hair="undercut", hair_col="#2a2420"),
          factions=("Scavengers", "ScavengersAndroid", "Wraiths", "WraithsAndroid")),
    group("voodoo_boys", Look("tank", "#22202a", "#14121a", "#9a6bff", motif="circuit", glow_trim=True, legs_col="#25222b",
                              shoes="#141218", hair="dreads", hair_col="#1a1612", eyes="glow", eye_col="#a98bff",
                              face=("dots",), face_col="#e8e4d8", tones=DARK),
          dict(top="crop"),
          factions=("VoodooBoys",)),
    group("mox", Look("tank", "#18181c", "#2a1220", "#ff4fa3", motif="studs", legs_col="#1c1820", shoes="#141216", boots=True,
                      hair="mohawk", hair_col="#ff5ab4", arm_tattoo="#b03a7a"),
          dict(top="crop", top_col="#e0408a", hair="undercut", legs="skirt", skirt_col="#18181c", legs_col="#3a2a34",
               face=("makeup",), face_col="#c42a78", arm_tattoo=None),
          factions=("TheMox",)),
    group("aldecaldos", Look("jacket", "#6b4a2e", "#a8c0d0", "#c96a2a", legs="jeans", legs_col="#3d5a7a", shoes="#4a3020",
                             boots=True, hair="short", hair_col="#5a3a22", hat="headband", hat_col="#b2452a", face=("beard",)),
          dict(top="poncho", top_col="#c4934d", under_col="#e6d6b4", trim="#7a2e2a", hair="long", hair_col="#3a2418",
               hat="none", face=()),
          factions=("Aldecaldos",)),
    # corporations and law
    group("arasaka", Look("armour", "#17181c", "#24262c", "#c4161c", motif="lines", legs="armour", legs_col="#141518",
                          shoes="#0c0d0f", boots=True, hair="short", hat="helmet", hat_col="#121316", eyes="visor",
                          eye_col="#ff3b3b", gloves="#121316"),
          dict(),
          factions=("Arasaka",)),
    group("militech", Look("armour", "#4f5548", "#2e322b", "#e08a1e", motif="stripes", legs="armour", legs_col="#3f453a",
                           shoes="#1e1f1b", boots=True, hair="buzz", hat="helmet", hat_col="#3c4138", eyes="plain",
                           gloves="#2a2c28", face=("stubble",)),
          dict(face=()),
          factions=("Militech", "Barghest", "NUSA")),
    group("kang_tao", Look("jacket", "#1e2126", "#2c3036", "#e8c21a", motif="lines", legs_col="#1a1c20", shoes="#101114",
                           hair="slick", hair_col="#0b0b0d", eyes="visor", eye_col="#ffd84a", gloves="#15171a"),
          dict(hair="bob"),
          factions=("KangTao",)),
    group("ncpd_patrol", Look("vest", "#2b2f36", "#2a3d66", "#9ec3ff", motif="badge", legs_col="#1b2744", shoes="#0f1116",
                              hair="short", hat="cap", hat_col="#1b2744", eye_col="#4a5a6a"),
          dict(hair="ponytail", hair_col="#3a2a1e"),
          factions=("NCPD",)),
    group("ncpd_swat", Look("armour", "#2a3140", "#1c2029", "#5d7bb8", motif="stripes", legs="armour", legs_col="#232a36",
                            shoes="#0f1116", boots=True, hat="helmet", hat_col="#20262f", eyes="visor", eye_col="#7fb0ff",
                            gloves="#161a20"),
          dict(),
          factions=("NCPD",), archetypes=("HeavyRangedT2", "HeavyMeleeT2", "ShotgunnerT2", "SniperT2"), priority=10),
    group("maxtac", Look("armour", "#101114", "#08090a", "#7dff5a", motif="lines", glow_trim=True, legs="armour",
                         legs_col="#0d0e10", shoes="#060607", boots=True, hat="full_helmet", hat_col="#0b0c0e",
                         eye_col="#7dff5a", gloves="#0b0c0e", cyber=("legs",)),
          dict(),
          factions=("NCPD",), archetypes=("HeavyRangedT3", "HeavyMeleeT3", "FastRangedT3", "FastMeleeT3", "FastShotgunnerT3",
                                          "FastSniperT3", "ShotgunnerT3", "NetrunnerT3", "TechieT3", "GenericRangedT3"),
          priority=10),
    group("trauma_team", Look("armour", "#e9eef0", "#3a3f44", "#2fbfa8", motif="band", legs="armour", legs_col="#d8dde0",
                              shoes="#2a2e33", boots=True, hat="helmet", hat_col="#dfe5e8", eyes="visor", eye_col="#4fe0c8",
                              gloves="#2a2e33"),
          dict(),
          factions=("TraumaTeam",)),
    group("netwatch", Look("suit", "#1a2236", "#0e1220", "#38b6ff", motif="circuit", glow_trim=True, legs_col="#141a2a",
                           shoes="#0a0c12", hair="slick", hair_col="#101216", eyes="shades", hat="headset", hat_col="#202226"),
          dict(hair="bob"),
          factions=("NetWatch",)),
    # roles (from the vendor type or from content)
    group("ripperdoc", Look("labcoat", "#dcdcd2", "#4a5a5a", "#7a2a2a", legs_col="#2d3138", shoes="#1a1c20", hair="short",
                            hair_col="#8a8a88", eyes="loupe", eye_col="#7af0ff", gloves="#7fb8b0", face=("stubble",), sleeves="short",
                            cyber=("left_arm",)),
          dict(hair="ponytail", hair_col="#6a4a3a", face=()),
          roles=("RipperDoc", "Cyberware", "Medical", "medic"), priority=20),
    group("vendor", Look("apron", "#c8a26a", "#3a6b5a", "#7a5a3a", legs_col="#33302c", shoes="#2a2420", hair="short",
                         hair_col="#2b2118", face=("stubble",)),
          dict(hair="ponytail", hair_col="#1f1712", face=()),
          roles=("Armorsmith", "Clothes", "Clothing", "Food", "GrilledFood", "PackedFood", "Gunsmith", "Junk", "Kiosk",
                 "Market", "Tech", "TechJunk", "SkillTrainer"), priority=20),
    group("fixer", Look("suit", "#2b1f3a", "#121014", "#d9b25a", legs_col="#211a2c", shoes="#3a2416", hair="slick",
                        hair_col="#1a1410", eyes="shades", face=("stubble",)),
          dict(hair="long", hair_col="#2a1a12", face=("makeup",), face_col="#7a2a4a"),
          roles=("fixer",), priority=20),
    group("bartender", Look("vest", "#2a2a30", "#e8e2d6", "#8a1f2a", motif="bowtie", sleeves="short", legs_col="#1e1e22",
                            shoes="#141416", hair="slick", hair_col="#3a2618", arm_tattoo="#3a4a5a"),
          dict(hair="bob", hair_col="#7a3a22", arm_tattoo=None),
          roles=("Drinks", "bartender"), priority=20),
    group("cyberpsycho", Look("rags", "#2a2526", "#5a1a1a", "#ff2a3a", glow_trim=True, legs_col="#211d1e", shoes="#141112",
                              boots=True, hair="undercut", hair_col="#2a2a2a", eyes="glow", eye_col="#ff2a3a", face=("jaw",),
                              cyber=("right_arm", "left_arm", "legs")),
          dict(hair="long", hair_col="#3a1a1a"),
          roles=("cyberpsycho",), priority=30),
]
MECHANIC = Group("mechanic", GROUPS[2].male, GROUPS[2].female, roles=("mechanic",), priority=20)  # wears the worker skins
DEFAULT = "civ_street"


# --- output --------------------------------------------------------------------------------------------------------------------
def texture_id(path: str) -> str:
    return f"{NAMESPACE}:textures/entity/{path}.png"


def match_value(values: tuple[str, ...]) -> str | list[str]:
    return values[0] if len(values) == 1 else list(values)


def map_rows() -> dict[str, dict]:
    """Every skin_map default as {file name: JSON object}."""
    rows: dict[str, dict] = {
        "cyber_male": {"schema": 1, "texture": texture_id("cyber_skin/v_male"), "model": "classic", "cyber": "male"},
        "cyber_female": {"schema": 1, "texture": texture_id("cyber_skin/v_female"), "model": "slim", "cyber": "female"},
        "default": {"schema": 1, "texture": texture_id(f"npc/{DEFAULT}_m"), "model": "classic"},
        "default_female": {"schema": 1, "texture": texture_id(f"npc/{DEFAULT}_f"), "model": "slim", "gender": "female"},
    }
    for g in GROUPS + [MECHANIC]:
        skin = "civ_worker" if g is MECHANIC else g.id
        keys = {}
        for key, values in (("role", g.roles), ("faction", g.factions), ("archetype", g.archetypes)):
            if values:
                keys[key] = match_value(values)
        extra = {"priority": g.priority} if g.priority else {}
        rows[g.id] = {"schema": 1, "texture": texture_id(f"npc/{skin}_m"), "model": "classic", **keys, **extra}
        rows[f"{g.id}_female"] = {"schema": 1, "texture": texture_id(f"npc/{skin}_f"), "model": "slim", **keys,
                                  "gender": "female", **extra}
    return rows


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(data, indent="\t") + "\n").encode("utf-8"))


def skins() -> dict[str, Image.Image]:
    out = {}
    for g in GROUPS:
        out[f"{g.id}_m"] = Painter(g.male, False, f"{g.id}_m").paint()
        out[f"{g.id}_f"] = Painter(g.female, True, f"{g.id}_f").paint()
    return out


def preview(images: dict[str, Image.Image]) -> Image.Image:
    """Front and back of each skin, male and female side by side, four skins (two groups) per row."""
    cells = []
    for name, im in images.items():
        slim = name.endswith("_f")
        cells += [preview_skins.render_view(im, "front", slim), preview_skins.render_view(im, "back", slim)]
    cols, pad = 8, 6
    cw, ch = preview_skins.CELL_W + pad, preview_skins.CELL_H + pad
    rows = -(-len(cells) // cols)
    sheet = Image.new("RGBA", (cols * cw + pad, rows * ch + pad), preview_skins.BACKGROUND)
    for n, cell in enumerate(cells):
        sheet.alpha_composite(cell, (pad + (n % cols) * cw, pad + (n // cols) * ch))
    return sheet.resize((sheet.width // 2, sheet.height // 2), Image.NEAREST)


def main() -> None:
    images = skins()
    for name, im in images.items():
        write_png(ASSETS / "textures" / "entity" / "npc" / f"{name}.png", im)
    rows = map_rows()
    for name, row in rows.items():
        write_json(SKIN_MAP / f"{name}.json", row)
    write_png(ART / "preview_npc_skins.png", preview(images))
    print(f"wrote {len(images)} NPC skins ({len(GROUPS)} looks, male and female), {len(rows)} skin_map rows")


if __name__ == "__main__":
    main()
