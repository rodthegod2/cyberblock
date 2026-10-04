"""Procedural, original Afterlife hub data and block art (megaplan 7-A1).

Run from anywhere:  uv run --with pillow python art/gen_hub.py

Deterministic: two runs give byte-identical files. Nothing is read from the game or from Minecraft; the layout is arithmetic and the
textures are drawn from scratch with the pixel helpers of gen_block_art.py (integer rules and square roots only, so every platform
agrees).

Writes
  mc/src/main/resources/data/cyberblock/cyberblock/hub/afterlife.json   the layout of the hub (schema shared/content-schemas/hub.schema.json),
                                                                       built once into the dimension cyberblock:afterlife by the hub package (7H)
  mc/src/main/resources/assets/cyberblock/{textures/block,models/block,blockstates,items}/...   the five Phase 7 blocks
  art/hub_preview.png                                                  the floor plan of the layout and a contact sheet of the block textures

The block ids, properties and model stems are the contract between this art and the Java block classes (7H, 7T, 8M):

  holo_table         (no properties)  the Holo-Table (7T): a pedestal, a lit plate and a floating translucent skyline
  spawn_beacon       (no properties)  the placeable Spawn Beacon (7T): a base plate, a slotted pillar and a glowing core
  hub_portal         (no properties)  the hub portal (7H): a full-brightness cube; placed in the replica it opens the hub, in the hub it leaves it
  match_terminal     facing           the Match Terminal (7H, filled by 8M): a console whose front shows a VS screen (full cube, light 7)
  leaderboard_board  facing           a wall board (7H): a 2 px plate on the back of its cell, facing is the side the board looks at

facing is north, east, south or west; facing=north is the model as drawn (the front looks north), like the device blocks of 4A.
"""
from __future__ import annotations

import io
import json
import random
import sys
from pathlib import Path

from PIL import Image

ART = Path(__file__).resolve().parent
if str(ART) not in sys.path:
    sys.path.insert(0, str(ART))
import gen_block_art as base  # noqa: E402  (the shared pixel helpers and the device-block machinery)

ROOT = ART.parent
RES = ROOT / "mc" / "src" / "main" / "resources"
ASSETS = RES / "assets" / "cyberblock"
HUB_FILE = RES / "data" / "cyberblock" / "cyberblock" / "hub" / "afterlife.json"
SIZE = base.SIZE
rgb, scale, mix, jitter, put, box, disc, housing = base.rgb, base.scale, base.mix, base.jitter, base.put, base.box, base.disc, base.housing
canvas, clear, fill_noise, scatter = base.canvas, base.clear, base.fill_noise, base.scatter

CYAN, MAGENTA, AMBER = rgb("#3df5ff"), rgb("#ff2fb3"), rgb("#ffb21f")


# --- block textures ---------------------------------------------------------------------------------------------------------
def holo_top(rnd: random.Random) -> Image.Image:
    """The Holo-Table's plate seen from above: dark glass, a faint grid, a bright ring, lit city blocks and a radar line."""
    im = canvas()
    fill_noise(im, rnd, rgb("#04121b"), 0.05)
    for i in range(0, SIZE, 4):
        for j in range(SIZE):
            put(im, i, j, scale(CYAN, 0.22))
            put(im, j, i, scale(CYAN, 0.22))
    disc(im, 8, 8, 7.4, lambda d: scale(CYAN, 0.8) if d > 6.4 else rgb("#06202c"))
    for _ in range(14):  # the city: small lit blocks inside the ring
        x, y = rnd.randrange(3, 12), rnd.randrange(3, 12)
        if (x + 0.5 - 8) ** 2 + (y + 0.5 - 8) ** 2 < 24:
            c = scale(CYAN if rnd.random() < 0.7 else MAGENTA, 0.5 + 0.5 * rnd.random())
            put(im, x, y, c)
            if rnd.random() < 0.5:
                put(im, x + 1, y, scale(c, 0.7))
    for i in range(1, 7):  # a radar sweep from the centre to the upper right
        put(im, 8 + i, 8 - i, mix(CYAN, rgb("#ffffff"), 0.5))
    put(im, 8, 8, rgb("#ffffff"))
    return im


def holo_projection(rnd: random.Random) -> Image.Image:
    """The floating skyline: translucent cyan columns with scan lines (alpha, so Minecraft draws it translucent)."""
    im = clear()
    for x in range(SIZE):
        for y in range(SIZE):
            if x % 4 == 3:
                continue
            alpha = 150 if y % 3 else 80
            if y == 0:
                alpha = 220
            put(im, x, y, mix(CYAN, rgb("#ffffff"), 0.6 if y == 0 else 0.1 + 0.05 * (y % 3)), alpha)
    return im


def holo_side(rnd: random.Random) -> Image.Image:
    im = housing(rnd, rgb("#262c39"))
    for x in range(1, SIZE - 1):
        put(im, x, 6, scale(CYAN, 0.55))
        put(im, x, 7, CYAN)
        put(im, x, 8, scale(CYAN, 0.55))
    return im


def beacon_base(rnd: random.Random) -> Image.Image:
    im = housing(rnd, rgb("#2b303c"))
    base.hazard(im, 0, 0, SIZE, 2)
    base.hazard(im, 0, SIZE - 2, SIZE, SIZE)
    return im


def beacon_pillar(rnd: random.Random) -> Image.Image:
    im = housing(rnd, rgb("#242934"))
    for x in (4, 8, 11):
        for y in range(2, 14):
            put(im, x, y, scale(AMBER, 0.55 + 0.4 * ((y + x) % 3) / 2))
    return im


def match_front(rnd: random.Random) -> Image.Image:
    """The Match Terminal's face: a screen with a magenta V and a cyan S between two thin bars, three buttons and a card slot."""
    im = housing(rnd, rgb("#262b36"))
    box(im, 2, 2, 14, 10, rgb("#05080e"))
    v = ["X...X", "X...X", ".X.X.", ".X.X.", "..X.."]
    s = [".XXX.", "X....", ".XX..", "...X.", "XXX.."]
    for glyph, x0, colour in ((v, 3, MAGENTA), (s, 9, CYAN)):
        for gy, row in enumerate(glyph):
            for gx, ch in enumerate(row):
                if ch == "X":
                    put(im, x0 + gx, 3 + gy, colour)
    for x in range(3, 13):
        put(im, x, 9, scale(CYAN, 0.35))
    for x, c in ((3, MAGENTA), (6, CYAN), (9, AMBER)):
        box(im, x, 11, x + 2, 12, c)
    box(im, 4, 13, 12, 14, rgb("#07080b"))
    return im


def match_side(rnd: random.Random) -> Image.Image:
    return base.vent_side(rnd)


def match_top(rnd: random.Random) -> Image.Image:
    im = housing(rnd, rgb("#262b36"))
    base.hazard(im, 2, 2, SIZE - 2, 4)
    return im


def board_front(rnd: random.Random) -> Image.Image:
    """A leaderboard: a gold header, five ranked rows (a pip and a bar that shortens) on a dark panel."""
    im = canvas()
    fill_noise(im, rnd, rgb("#10141d"), 0.04)
    for i in range(SIZE):
        put(im, i, 0, rgb("#7d8798"))
        put(im, i, SIZE - 1, rgb("#3a4254"))
        put(im, 0, i, rgb("#7d8798"))
        put(im, SIZE - 1, i, rgb("#3a4254"))
    box(im, 2, 2, 14, 4, rgb("#e8b82e"))
    for x in (4, 5, 8, 9, 11):  # a few dark marks: the title
        put(im, x, 2, rgb("#8a6410"))
    pips = [rgb("#e8b82e"), rgb("#c3cad8"), rgb("#c47a3a"), rgb("#6a738a"), rgb("#6a738a")]
    for row, (y, length) in enumerate(zip((5, 7, 9, 11, 13), (10, 8, 7, 5, 3))):
        put(im, 2, y, pips[row])
        put(im, 2, y + 1, scale(pips[row], 0.6))
        for x in range(4, 4 + length):
            put(im, x, y, scale(CYAN, 0.9 - 0.12 * row))
            put(im, x, y + 1, scale(CYAN, 0.45 - 0.05 * row))
    return im


def portal(rnd: random.Random) -> Image.Image:
    """The hub portal: a dark frame round square rings that alternate magenta and cyan towards a white centre."""
    im = canvas()
    for y in range(SIZE):
        for x in range(SIZE):
            d = max(abs(2 * x - 15), abs(2 * y - 15)) // 2  # 0..7, the square ring the pixel lies on
            if d >= 7:
                c = rgb("#14161d")
            else:
                ring = mix(MAGENTA, CYAN, (d % 3) / 2)
                c = mix(ring, rgb("#ffffff"), (6 - d) / 9)
                if (x + y) % 5 == 0 and d < 6:
                    c = scale(c, 0.8)
            put(im, x, y, jitter(c, rnd, 0.02))
    return im


# --- the blocks: a table of DeviceBlock rows (the machinery of gen_block_art) ---------------------------------------------------
def holo_table() -> base.DeviceBlock:
    textures = {"holo_table_top": holo_top(random.Random("holo_table_top")), "holo_table_side": holo_side(random.Random("holo_table_side")),
                "holo_table_base": housing(random.Random("holo_table_base"), rgb("#1d212c")),
                "holo_table_proj": holo_projection(random.Random("holo_table_proj"))}
    sides = {f: "side" for f in ("north", "south", "west", "east")}
    elements = [
        base.element([3, 0, 3], [13, 9, 13], {**sides, "up": "base", "down": "base"}),
        base.element([0, 9, 0], [16, 11, 16], {**sides, "up": "base", "down": "base"}),
        base.element([1, 11, 1], [15, 12, 15], {**{f: "top" for f in ("north", "south", "west", "east")}, "up": "top"}, 15),
        base.element([5, 12, 5], [11, 15, 11], {**{f: "proj" for f in ("north", "south", "west", "east")}, "up": "proj"}, 15),
    ]
    mdl = base.model({"side": "holo_table_side", "base": "holo_table_base", "top": "holo_table_top", "proj": "holo_table_proj"}, elements,
                     "side", ao=False)
    return base.DeviceBlock("holo_table", "Holo-Table", {}, textures, {"holo_table": mdl}, lambda s: "holo_table", "holo_table", lockable=False)


def spawn_beacon() -> base.DeviceBlock:
    textures = {"spawn_beacon_base": beacon_base(random.Random("spawn_beacon_base")),
                "spawn_beacon_pillar": beacon_pillar(random.Random("spawn_beacon_pillar")),
                "spawn_beacon_core": base.lamp("#3df5ff", True)(random.Random("spawn_beacon_core"))}
    four = ("north", "south", "west", "east")
    elements = [
        base.element([2, 0, 2], [14, 2, 14], {**{f: "base" for f in four}, "up": "base", "down": "base"}),
        base.element([6, 2, 6], [10, 11, 10], {**{f: "pillar" for f in four}, "up": "pillar", "down": "pillar"}),
        base.element([5, 11, 5], [11, 16, 11], {**{f: "core" for f in four}, "up": "core", "down": "core"}, 15),
    ]
    mdl = base.model({"base": "spawn_beacon_base", "pillar": "spawn_beacon_pillar", "core": "spawn_beacon_core"}, elements, "base", ao=False)
    return base.DeviceBlock("spawn_beacon", "Spawn Beacon", {}, textures, {"spawn_beacon": mdl}, lambda s: "spawn_beacon", "spawn_beacon",
                            lockable=False)


def hub_portal() -> base.DeviceBlock:
    textures = {"hub_portal": portal(random.Random("hub_portal"))}
    mdl = base.model({"all": "hub_portal"}, [base.element(*base.FULL, {f: "all" for f in base.FACES}, 15)], "all", ao=False)
    return base.DeviceBlock("hub_portal", "Hub Portal", {}, textures, {"hub_portal": mdl}, lambda s: "hub_portal", "hub_portal", lockable=False)


def match_terminal() -> base.DeviceBlock:
    textures = {"match_terminal_front": match_front(random.Random("match_terminal_front")),
                "match_terminal_side": match_side(random.Random("match_terminal_side")),
                "match_terminal_top": match_top(random.Random("match_terminal_top"))}
    mdl = base.cube("match_terminal_front", "match_terminal_side", "match_terminal_top", 7)
    return base.DeviceBlock("match_terminal", "Match Terminal", {"facing": base.FACINGS}, textures, {"match_terminal": mdl},
                            lambda s: "match_terminal", "match_terminal", lockable=False)


def leaderboard_board() -> base.DeviceBlock:
    textures = {"leaderboard_board_front": board_front(random.Random("leaderboard_board_front")),
                "leaderboard_board_edge": base.plain_housing(random.Random("leaderboard_board_edge"))}
    faces = {"north": ("front", [0, 0, 16, 16]), "south": ("edge", [0, 0, 16, 16]), "west": ("edge", [0, 0, 2, 16]),
             "east": ("edge", [0, 0, 2, 16]), "up": ("edge", [0, 0, 16, 2]), "down": ("edge", [0, 0, 16, 2])}
    # the plate is a 2 px slab on the south edge of its cell and looks north, so the whole texture shows on its front
    mdl = base.model({"front": "leaderboard_board_front", "edge": "leaderboard_board_edge"}, [base.element([0, 0, 14], [16, 16, 16], faces, 6)],
                     "edge", ao=False)
    return base.DeviceBlock("leaderboard_board", "Leaderboard Board", {"facing": base.FACINGS}, textures, {"leaderboard_board": mdl},
                            lambda s: "leaderboard_board", "leaderboard_board", lockable=False)


BLOCKS: list[base.DeviceBlock] = [holo_table(), spawn_beacon(), hub_portal(), match_terminal(), leaderboard_board()]


def block_files() -> dict[str, bytes]:
    """Every file of the five blocks as {path under assets/cyberblock: bytes}."""
    files: dict[str, bytes] = {}
    for block in BLOCKS:
        for stem, im in block.textures.items():
            files[f"textures/block/{stem}.png"] = base.png_bytes(im)
        for stem, data in block.models.items():
            files[f"models/block/{stem}.json"] = base.json_bytes(data)
        files[f"blockstates/{block.id}.json"] = base.json_bytes(base.blockstate(block))
        files[f"items/{block.id}.json"] = base.json_bytes({"model": {"type": "minecraft:model", "model": f"{base.NAMESPACE}:block/{block.item_model}"}})
    return files


# --- the layout -------------------------------------------------------------------------------------------------------------
Cell = tuple[int, int, int]
CD, MT, TL, AS = ("cyberblock:nc_concrete_dark", "cyberblock:nc_metal", "cyberblock:nc_tile", "cyberblock:nc_asphalt")
AIR, LIGHT = "minecraft:air", "minecraft:light[level=15]"
SIZE_CELLS = (43, 9, 35)  # x 0..42, y 0..8, z 0..34; the floor layer is y 0, the ceiling y 8, players stand at y 1


def neon(dye: str) -> str:
    return f"cyberblock:nc_neon_{dye}"


class Layout:
    """Collects the ordered fill steps and replays them into a voxel dictionary (to check figures and stations stand where they say)."""

    def __init__(self) -> None:
        self.steps: list[dict] = []
        self.cells: dict[Cell, str] = {}

    def fill(self, a: Cell, b: Cell, block: str, mode: str = "fill") -> None:
        lo = (min(a[0], b[0]), min(a[1], b[1]), min(a[2], b[2]))
        hi = (max(a[0], b[0]), max(a[1], b[1]), max(a[2], b[2]))
        step: dict = {"from": list(lo), "to": list(hi), "block": block}
        if mode != "fill":
            step["mode"] = mode
        self.steps.append(step)
        for x in range(lo[0], hi[0] + 1):
            for y in range(lo[1], hi[1] + 1):
                for z in range(lo[2], hi[2] + 1):
                    if mode == "hollow" and not (x in (lo[0], hi[0]) or y in (lo[1], hi[1]) or z in (lo[2], hi[2])):
                        continue
                    self.cells[(x, y, z)] = block

    def put(self, at: Cell, block: str) -> None:
        self.fill(at, at, block)

    def block(self, at: Cell) -> str:
        return self.cells.get(at, AIR)


def passable(block: str) -> bool:
    return block in (AIR, LIGHT)


STALLS = [("arms", 13, 15, "orange", "Dax"), ("gear", 16, 18, "lime", "Pell"), ("tech", 24, 26, "light_blue", "Ono"),
          ("scrap", 27, 29, "yellow", "Tully"), ("kitchen", 30, 32, "pink", "Bao")]
BOARDS = [((33, 3, 10), "cb_wins", "cyberblock.afterlife.board.wins"), ((33, 3, 13), "cb_gigs", "cyberblock.afterlife.board.gigs"),
          ((33, 3, 16), "cb_chaos", "cyberblock.afterlife.board.chaos")]
AREAS: dict[str, tuple[Cell, Cell]] = {
    "lobby": ((14, 1, 29), (28, 7, 33)), "hall": ((9, 1, 1), (33, 7, 27)), "bar": ((12, 1, 1), (30, 7, 8)),
    "booths": ((9, 1, 17), (12, 7, 27)), "shops": ((13, 1, 25), (32, 7, 27)), "board_wall": ((33, 1, 9), (33, 5, 17)),
    "match_floor": ((18, 1, 11), (24, 7, 17)), "holo_room": ((1, 1, 8), (7, 7, 20)), "clinic": ((35, 1, 1), (41, 7, 15)),
    "garage": ((35, 1, 17), (41, 7, 27)),
}
SPAWN = ((21.5, 1.0, 31.5), 180.0)
STATIONS = {"match_terminal": (21, 2, 14), "holo_table": (4, 1, 14), "exit_portal": (21, 2, 33)}


def figures() -> list[dict]:
    out = [
        {"id": "bartender", "role": "cyberblock:bartender", "area": "bar", "pos": [21.5, 1.0, 4.5], "yaw": 0.0, "gender": "female", "name": "Juno"},
        {"id": "fixer", "role": "cyberblock:fixer", "area": "booths", "pos": [10.5, 1.0, 20.5], "yaw": 270.0, "gender": "male", "name": "Venn"},
        {"id": "ripperdoc", "role": "cyberblock:ripperdoc", "area": "clinic", "pos": [38.5, 1.0, 5.5], "yaw": 270.0, "gender": "female", "name": "Dr. Vale"},
        {"id": "medic", "role": "cyberblock:medic", "area": "clinic", "pos": [37.5, 1.0, 12.5], "yaw": 270.0, "gender": "male", "name": "Sorrel"},
        {"id": "mechanic", "role": "cyberblock:mechanic", "area": "garage", "pos": [38.5, 1.0, 21.5], "yaw": 90.0, "gender": "female", "name": "Rook"},
    ]
    for i, (shop, x0, _x1, _dye, name) in enumerate(STALLS):
        out.append({"id": f"vendor_{shop}", "role": "cyberblock:vendor", "area": "shops", "pos": [x0 + 1.5, 1.0, 26.5], "yaw": 180.0,
                    "gender": "female" if i % 2 else "male", "name": name, "shop": f"cyberblock:afterlife_{shop}"})
    return out


def build() -> Layout:
    L = Layout()
    # the shell and the rooms
    L.fill((0, 0, 0), (42, 8, 34), CD, "hollow")
    L.fill((1, 1, 1), (41, 7, 33), AIR)
    L.fill((14, 0, 29), (28, 0, 33), TL)  # lobby floor
    L.fill((35, 0, 1), (41, 0, 15), TL)  # clinic floor
    L.fill((35, 0, 17), (41, 0, 27), AS)  # garage floor
    L.fill((1, 0, 8), (7, 0, 20), MT)  # Holo-Table room floor
    for a, b in (((1, 1, 1), (7, 7, 7)), ((1, 1, 21), (7, 7, 27)), ((1, 1, 28), (13, 7, 33)), ((29, 1, 28), (41, 7, 33))):
        L.fill(a, b, CD)  # the unused corners are solid
    L.fill((8, 1, 1), (8, 7, 27), MT)  # the hall's west wall
    L.fill((34, 1, 1), (34, 7, 27), MT)  # the hall's east wall
    L.fill((8, 1, 28), (34, 7, 28), MT)  # the hall's south wall
    L.fill((35, 1, 16), (41, 7, 16), MT)  # between clinic and garage
    for a, b in (((8, 1, 13), (8, 4, 15)), ((34, 1, 4), (34, 4, 6)), ((34, 1, 20), (34, 4, 22)), ((19, 1, 28), (23, 5, 28))):
        L.fill(a, b, AIR)  # the four doors: Holo-Table room, clinic, garage, lobby
    for a, b in (((8, 5, 13), (8, 5, 15)), ((34, 5, 4), (34, 5, 6)), ((34, 5, 20), (34, 5, 22))):
        L.fill(a, b, neon("light_blue"))  # lintels
    # the hall: bar, stools, booths, floor ring and the Match Terminal dais
    L.fill((12, 1, 1), (30, 2, 1), CD)  # back bar
    L.fill((12, 3, 1), (30, 3, 1), MT)  # shelf
    for i, x in enumerate(range(13, 30, 2)):
        L.put((x, 4, 1), neon(("pink", "cyan", "lime", "orange")[i % 4]))  # bottles
    L.fill((14, 1, 7), (28, 2, 8), CD)  # counter
    L.fill((14, 2, 7), (28, 2, 8), MT)
    L.fill((14, 1, 8), (28, 1, 8), neon("magenta"))
    for x in range(15, 28, 2):
        L.put((x, 1, 10), "minecraft:polished_blackstone_slab[type=bottom]")  # stools
    for zc in (18, 22, 26):
        L.fill((9, 1, zc - 1), (9, 3, zc + 1), CD)  # booth back panel
        L.fill((9, 4, zc - 1), (9, 4, zc + 1), neon("pink"))
        L.put((10, 1, zc), "minecraft:polished_blackstone_stairs[facing=east,half=bottom]")
        L.put((11, 1, zc), MT)  # table
        L.put((12, 1, zc), "minecraft:polished_blackstone_stairs[facing=west,half=bottom]")
    L.fill((18, 0, 11), (24, 0, 17), neon("cyan"))  # the dais
    L.fill((19, 0, 12), (23, 0, 16), CD)
    L.fill((19, 1, 12), (23, 1, 16), MT)
    for x, z in ((19, 12), (23, 12), (19, 16), (23, 16)):
        L.fill((x, 2, z), (x, 3, z), neon("magenta"))  # corner posts
    L.put(STATIONS["match_terminal"], "cyberblock:match_terminal[facing=south]")
    for pos, _objective, _title in BOARDS:
        L.put(pos, "cyberblock:leaderboard_board[facing=west]")
    for name, x0, x2, dye, _vendor in STALLS:
        L.fill((x0, 1, 25), (x2, 1, 25), CD)  # stall counter
        L.fill((x0, 2, 25), (x2, 2, 25), MT)
        L.fill((x0, 4, 27), (x2, 5, 27), neon(dye))  # sign
    L.fill((9, 5, 1), (9, 5, 27), neon("magenta"))  # wall accents
    L.fill((33, 5, 1), (33, 5, 27), neon("cyan"))
    for i, z in enumerate((2, 10, 18, 26)):
        L.fill((10, 7, z), (32, 7, z), neon("cyan" if i % 2 == 0 else "magenta"))  # ceiling strips
    for x in (12, 18, 24, 30):
        for z in (4, 12, 20, 25):
            L.put((x, 6, z), LIGHT)
    # the Holo-Table room
    L.fill((2, 0, 12), (6, 0, 16), neon("cyan"))
    L.fill((3, 0, 13), (5, 0, 15), MT)
    L.put(STATIONS["holo_table"], "cyberblock:holo_table")
    for a, b in (((1, 3, 8), (1, 3, 20)), ((7, 3, 8), (7, 3, 20)), ((1, 3, 8), (7, 3, 8)), ((1, 3, 20), (7, 3, 20))):
        L.fill(a, b, neon("light_blue"))
    L.put((4, 6, 10), LIGHT)
    L.put((4, 6, 18), LIGHT)
    # the clinic (ripperdoc and medic) and the garage (mechanic)
    L.put((40, 1, 5), "minecraft:quartz_stairs[facing=west,half=bottom]")  # the surgery chair
    L.fill((39, 1, 11), (40, 1, 12), "minecraft:quartz_slab[type=bottom]")  # the bed
    L.fill((38, 3, 15), (38, 5, 15), neon("red"))  # a cross on the dividing wall
    L.fill((37, 4, 15), (39, 4, 15), neon("red"))
    for z in (3, 9, 14):
        L.put((38, 6, z), LIGHT)
    L.fill((37, 0, 23), (39, 0, 25), MT)  # a lift pad
    L.fill((35, 0, 18), (41, 0, 18), neon("yellow"))
    L.fill((41, 1, 18), (41, 2, 22), MT)  # a tool bench
    L.fill((41, 3, 18), (41, 3, 22), neon("orange"))
    for z in (19, 25):
        L.put((38, 6, z), LIGHT)
    # the lobby and the exit portal
    L.fill((19, 1, 33), (19, 4, 33), neon("magenta"))
    L.fill((23, 1, 33), (23, 4, 33), neon("magenta"))
    L.fill((19, 4, 33), (23, 4, 33), neon("magenta"))
    L.fill((20, 1, 33), (22, 3, 33), "cyberblock:hub_portal")
    L.fill((14, 7, 31), (28, 7, 31), neon("cyan"))
    L.put((21, 6, 31), LIGHT)
    return L


def layout_document() -> dict:
    L = build()
    return {
        "schema": 1, "title_key": "cyberblock.afterlife.title", "origin": [0, 64, 0], "size": list(SIZE_CELLS),
        "spawn": {"pos": list(SPAWN[0]), "yaw": SPAWN[1]},
        "areas": {name: {"from": list(a), "to": list(b)} for name, (a, b) in AREAS.items()},
        "steps": L.steps,
        "stations": {k: list(v) for k, v in STATIONS.items()},
        "boards": [{"pos": list(pos), "facing": "west", "objective": obj, "title_key": key} for pos, obj, key in BOARDS],
        "figures": figures(),
    }


def layout_problems(doc: dict) -> list[str]:
    """Everything wrong with a layout document: cells outside the size, figures that do not stand on floor with air above, stations that are
    not the block they claim, areas that leave the size, a spawn inside a block. An empty list is a good layout."""
    L = Layout()
    problems: list[str] = []
    w, h, d = doc["size"]
    for step in doc["steps"]:
        L.fill(tuple(step["from"]), tuple(step["to"]), step["block"], step.get("mode", "fill"))
        if not all(0 <= c < lim for c, lim in zip(step["from"] + step["to"], (w, h, d) * 2)):
            problems.append(f"step {step} leaves the size {doc['size']}")
    for name, area in doc["areas"].items():
        if not all(0 <= c < lim for c, lim in zip(area["from"] + area["to"], (w, h, d) * 2)):
            problems.append(f"area {name} leaves the size")

    def stands(pos: list[float], what: str) -> None:
        x, y, z = (int(v // 1) for v in pos)
        if passable(L.block((x, y - 1, z))):
            problems.append(f"{what} at {pos} has no floor")
        if not (passable(L.block((x, y, z))) and passable(L.block((x, y + 1, z)))):
            problems.append(f"{what} at {pos} is inside a block")

    stands(doc["spawn"]["pos"], "the spawn")
    for fig in doc["figures"]:
        stands(fig["pos"], f"figure {fig['id']}")
        area = doc["areas"].get(fig["area"])
        if area is None or not all(area["from"][i] <= int(fig["pos"][i] // 1) <= area["to"][i] for i in range(3)):
            problems.append(f"figure {fig['id']} is not inside its area {fig['area']}")
    for key, block in (("match_terminal", "cyberblock:match_terminal"), ("holo_table", "cyberblock:holo_table"), ("exit_portal", "cyberblock:hub_portal")):
        if not L.block(tuple(doc["stations"][key])).startswith(block):
            problems.append(f"station {key} is {L.block(tuple(doc['stations'][key]))}, not {block}")
    for board in doc["boards"]:
        if not L.block(tuple(board["pos"])).startswith("cyberblock:leaderboard_board"):
            problems.append(f"board at {board['pos']} is not a leaderboard board")
    return problems


def layout_bytes(doc: dict) -> bytes:
    """The document as JSON, one step, area, board and figure per line (a tab-indented dump would run to thousands of lines)."""
    def one(item: object) -> str:
        return json.dumps(item, ensure_ascii=False)

    parts = [f'\t"schema": 1', f'\t"title_key": {one(doc["title_key"])}', f'\t"origin": {one(doc["origin"])}', f'\t"size": {one(doc["size"])}',
             f'\t"spawn": {one(doc["spawn"])}']
    parts.append('\t"areas": {\n' + ",\n".join(f"\t\t{one(name)}: {one(area)}" for name, area in doc["areas"].items()) + "\n\t}")
    parts.append('\t"steps": [\n' + ",\n".join(f"\t\t{one(s)}" for s in doc["steps"]) + "\n\t]")
    parts.append(f'\t"stations": {one(doc["stations"])}')
    parts.append('\t"boards": [\n' + ",\n".join(f"\t\t{one(b)}" for b in doc["boards"]) + "\n\t]")
    parts.append('\t"figures": [\n' + ",\n".join(f"\t\t{one(f)}" for f in doc["figures"]) + "\n\t]")
    return ("{\n" + ",\n".join(parts) + "\n}\n").encode("utf-8")


# --- the preview ------------------------------------------------------------------------------------------------------------
def block_colour(block: str, cache: dict[str, tuple[int, int, int]]) -> tuple[int, int, int]:
    name = block.split("[")[0]
    if name == AIR:
        return (24, 26, 34)
    if name not in cache:
        stem = name.removeprefix("cyberblock:")
        spec = next((b for b in base.BLOCKS if b.id == stem), None)
        if spec is not None:
            tile = spec.paint(random.Random(spec.id)).convert("RGB")
            px = [tile.getpixel((x, y)) for x in range(SIZE) for y in range(SIZE)]
            cache[name] = tuple(sum(p[i] for p in px) // len(px) for i in range(3))
        else:
            cache[name] = {"cyberblock:hub_portal": (255, 47, 179), "cyberblock:holo_table": (61, 245, 255), "cyberblock:match_terminal": (200, 60, 220),
                           "cyberblock:leaderboard_board": (232, 184, 46), "minecraft:light": (255, 255, 120)}.get(name, (150, 150, 160))
    return cache[name]


def preview(doc: dict, files: dict[str, bytes]) -> Image.Image:
    """The floor plan (one pixel block of 12 per cell, the highest block of each column up to y 7) with the figures as white dots, then the
    textures of the five blocks at 4x on a checkerboard."""
    L = Layout()
    for s in doc["steps"]:
        L.fill(tuple(s["from"]), tuple(s["to"]), s["block"], s.get("mode", "fill"))
    w, h, d = doc["size"]
    cell, cache = 12, {}
    plan = Image.new("RGB", (w * cell, d * cell), (0, 0, 0))
    for x in range(w):
        for z in range(d):
            top = max((y for y in range(h - 1) if not passable(L.block((x, y, z)))), default=0)
            colour = scale(block_colour(L.block((x, top, z)), cache), 0.65 + 0.35 * min(top, 4) / 4)
            for px in range(cell):
                for pz in range(cell):
                    plan.putpixel((x * cell + px, z * cell + pz), colour)
    for fig in doc["figures"]:
        cx, cz = int(fig["pos"][0] * cell), int(fig["pos"][2] * cell)
        for px in range(-3, 4):
            for pz in range(-3, 4):
                if px * px + pz * pz <= 9:
                    plan.putpixel((cx + px, cz + pz), (255, 255, 255))
    stems = sorted(p for p in files if p.startswith("textures/block/"))
    zoom, cols = 4, 6
    rows = -(-len(stems) // cols)
    sheet = Image.new("RGBA", (cols * SIZE * zoom, max(rows * SIZE * zoom, 1)), (0, 0, 0, 255))
    for y in range(sheet.height):
        for x in range(sheet.width):
            sheet.putpixel((x, y), (46, 48, 58, 255) if (x // 8 + y // 8) % 2 else (62, 64, 76, 255))
    for n, path in enumerate(stems):
        tex = Image.open(io.BytesIO(files[path])).convert("RGBA").resize((SIZE * zoom, SIZE * zoom), Image.NEAREST)
        sheet.alpha_composite(tex, ((n % cols) * SIZE * zoom, (n // cols) * SIZE * zoom))
    out = Image.new("RGB", (plan.width + 8 + sheet.width, max(plan.height, sheet.height)), (24, 26, 34))
    out.paste(plan, (0, 0))
    out.paste(sheet.convert("RGB"), (plan.width + 8, 0))
    return out


# --- output -----------------------------------------------------------------------------------------------------------------
def main() -> None:
    doc = layout_document()
    problems = layout_problems(doc)
    if problems:
        raise SystemExit("the hub layout is wrong:\n" + "\n".join(problems))
    HUB_FILE.parent.mkdir(parents=True, exist_ok=True)
    HUB_FILE.write_bytes(layout_bytes(doc))
    files = block_files()
    for rel, data in files.items():
        (ASSETS / rel).parent.mkdir(parents=True, exist_ok=True)
        (ASSETS / rel).write_bytes(data)
    base.write_png(ART / "hub_preview.png", preview(doc, files))
    print(f"wrote the hub layout ({len(doc['steps'])} steps, {len(doc['figures'])} figures) and {len(BLOCKS)} blocks ({len(files)} files)")


if __name__ == "__main__":
    main()
