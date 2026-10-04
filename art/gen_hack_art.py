"""Procedural, original Cyberblock Phase 6 art: the redstone hacking blocks and the interaction-map items (megaplan 6-A1).

Run from anywhere:  uv run --with pillow python art/gen_hack_art.py

Deterministic (every texture is drawn from a seed made of its own name), original (nothing is read from the game or from
Minecraft), and built on the helpers of gen_block_art.py (the housing, discs, hazard stripes, the model and blockstate writers)
and gen_item_art.py (the Sprite painter). The script owns exactly these ids, which package 6R registers in Java and 6I and the
screens use by name:

  blocks (no quest_locked: they are the player's own blocks, not Night City devices)
    device_relay      linked false|true, powered false|true         redstone edge toggles the linked device
    proximity_sensor  powered false|true                            signal while a person, car or V is in range
    heat_sensor       power 0..15 (the model follows the NCPD heat  signal = heat 0-5 x 3: models for heat 0..5)
    breach_port       facing east|north|south|west, powered         fires the chosen quickhack on a signal
    charge            powered false|true                            a real Cyberpunk explosion on a signal
    cyberdeck_block   facing east|north|south|west, ram 0..4        stores RAM next to a Breach Port (bars lit)
  items
    cyberdeck_link, traffic_tool, net_map, comms_panel, firewall (the implant, shown in the ripperdoc slots like kerenzikov)
  every block also has its block item (items/<id>.json shows the block model).

For every row it writes under mc/src/main/resources/assets/cyberblock/ (the same layout as gen_block_art.py):
    textures/block/<stem>.png, models/block/<stem>.json, blockstates/<id>.json, items/<id>.json
    textures/item/<id>.png, models/item/<id>.json, items/<id>.json                         (the five items)
and the contact sheet art/hack_preview.png. The English names are kept in lang/en_us.json by hand (a test compares them).
"""
from __future__ import annotations

import io
import random
import sys
from collections.abc import Callable
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import gen_block_art as gb  # noqa: E402
import gen_item_art as gi  # noqa: E402

ART = HERE
ASSETS = gb.ASSETS
NAMESPACE = gb.NAMESPACE
SIZE = gb.SIZE
rgb = gb.rgb
FULL = gb.FULL
HOUSING = gb.HOUSING
CYAN, MAGENTA, GREEN, AMBER, RED = rgb("#2fe0ff"), rgb("#ff2fb3"), rgb("#35ff7a"), rgb("#ffb21f"), rgb("#ff2d2d")
GOLD = rgb("#d9b84a")


# --- textures --------------------------------------------------------------------------------------------------------------
def led(im: Image.Image, x: int, y: int, colour: tuple[int, int, int], lit: bool = True) -> None:
    gb.put(im, x, y, colour if lit else gb.scale(colour, 0.35))
    gb.put(im, x, y + 1, gb.scale(colour, 0.6 if lit else 0.2))


def relay_face(linked: bool, powered: bool) -> Callable[[random.Random], Image.Image]:
    """Two gold contacts and a bridge: lifted while unpowered, closed (and glowing) while powered; the LED is amber unlinked, cyan linked."""
    def paint(rnd: random.Random) -> Image.Image:
        im = gb.housing(rnd, rgb("#2a303d"))
        gb.box(im, 2, 4, 14, 13, rgb("#14181f"))
        for cx in (4.5, 11.5):
            gb.disc(im, cx, 10.5, 1.7, lambda d: gb.scale(GOLD, 1.15 - 0.2 * d))
        if powered:
            gb.box(im, 5, 10, 12, 12, gb.mix(CYAN, rgb("#ffffff"), 0.55))
            gb.box(im, 5, 9, 12, 10, gb.scale(CYAN, 0.8))
        else:
            for i in range(5):
                gb.put(im, 5 + i, 10 - i, rgb("#8a93a6"))
                gb.put(im, 5 + i, 11 - i, gb.scale(rgb("#8a93a6"), 0.6))
        gb.box(im, 3, 6, 5, 7, rgb("#7b8598"))  # a coil mark
        led(im, 12, 2, CYAN if linked else AMBER, lit=linked or powered)
        return im

    return paint


def sensor_face(powered: bool) -> Callable[[random.Random], Image.Image]:
    """A round lens with two sensing rings: dull teal, or green and bright while the sensor outputs a signal."""
    def paint(rnd: random.Random) -> Image.Image:
        im = gb.housing(rnd, rgb("#262d36"))
        ring = GREEN if powered else rgb("#2d6f73")
        gb.disc(im, 8, 8, 6.3, lambda d: gb.scale(rgb("#8a93a6"), 1.1 - 0.06 * d))
        gb.disc(im, 8, 8, 5.4, lambda d: rgb("#07090e"))
        gb.disc(im, 8, 8, 4.4, lambda d: gb.scale(ring, 0.5))
        gb.disc(im, 8, 8, 3.4, lambda d: rgb("#07090e"))
        gb.disc(im, 8, 8, 2.4, lambda d: gb.scale(ring, 1.0 if powered else 0.7))
        gb.disc(im, 8, 8, 1.0, lambda d: gb.mix(ring, rgb("#ffffff"), 0.7 if powered else 0.3))
        return im

    return paint


HEAT_COLOURS = ["#2fb4ff", "#35ff7a", "#f7ff3d", "#ff8a1f", "#ff2d2d"]


def heat_face(tier: int) -> Callable[[random.Random], Image.Image]:
    """A five-segment thermometer: the tier (NCPD heat 0..5) says how many segments are lit, cold blue at the bottom to red at the top."""
    def paint(rnd: random.Random) -> Image.Image:
        im = gb.housing(rnd, rgb("#2b2f3a"))
        gb.box(im, 5, 2, 11, 14, rgb("#07090e"))
        for i in range(5):
            y1 = 13 - 2 * i  # segment i occupies rows y1-2 .. y1-1 (bottom up)
            colour = rgb(HEAT_COLOURS[i])
            gb.box(im, 6, y1 - 2, 10, y1 - 1, colour if i < tier else gb.scale(colour, 0.22))
            if i < tier:
                gb.put(im, 6, y1 - 2, gb.mix(colour, rgb("#ffffff"), 0.6))
        for y in (3, 7, 11):  # scale marks on both sides
            gb.put(im, 3, y, rgb("#7b8598"))
            gb.put(im, 12, y, rgb("#7b8598"))
        return im

    return paint


def heat_top(rnd: random.Random) -> Image.Image:
    im = gb.housing(rnd, rgb("#2b2f3a"))
    for y in range(4, 13, 2):
        gb.box(im, 3, y, 13, y + 1, gb.scale(HOUSING, 0.4))
    return im


def port_front(powered: bool) -> Callable[[random.Random], Image.Image]:
    """The Breach Port: a small screen with hex code cells, a three-pin socket and an LED (magenta while a signal arrives)."""
    accent = MAGENTA if powered else rgb("#7c3a6a")

    def paint(rnd: random.Random) -> Image.Image:
        im = gb.housing(rnd, rgb("#272d39"))
        gb.box(im, 2, 2, 14, 9, rgb("#120a17"))
        for row, y in enumerate((3, 5, 7)):
            for col in range(4):
                if (row + col) % 3 != 2:
                    gb.box(im, 3 + col * 3, y, 5 + col * 3, y + 1, accent if (row * 4 + col) % 2 else gb.scale(CYAN, 1.0 if powered else 0.55))
        gb.box(im, 5, 10, 11, 14, rgb("#0b0d12"))
        for x in (6, 8, 9):
            gb.put(im, x, 11, GOLD)
            gb.put(im, x, 12, GOLD)
        for x in range(5, 11):
            gb.put(im, x, 10, gb.scale(HOUSING, 1.5))
        led(im, 12, 11, MAGENTA, lit=powered)
        return im

    return paint


def charge_side(powered: bool) -> Callable[[random.Random], Image.Image]:
    """A squat red explosive pack: hazard stripes top and bottom, a yellow bolt label, a blinking LED."""
    def paint(rnd: random.Random) -> Image.Image:
        im = gb.canvas()
        gb.fill_noise(im, rnd, rgb("#6b1f26"), 0.05)
        gb.hazard(im, 0, 0, 16, 3)
        gb.hazard(im, 0, 13, 16, 16)
        for i in range(gb.SIZE):
            gb.put(im, i, 3, gb.scale(rgb("#6b1f26"), 0.5))
            gb.put(im, i, 12, gb.scale(rgb("#6b1f26"), 0.5))
        # the bolt: a zig-zag in yellow
        bolt = [(7, 4), (8, 4), (6, 5), (7, 5), (5, 6), (6, 6), (7, 6), (8, 6), (7, 7), (8, 7), (6, 8), (7, 8), (5, 9), (6, 9), (6, 10), (5, 11)]
        for x, y in bolt:
            gb.put(im, x, y, rgb("#f7ff3d"))
        gb.box(im, 10, 5, 14, 11, rgb("#1b1d24"))
        for y in (6, 8, 10):
            gb.box(im, 11, y, 13, y + 1, RED if powered else gb.scale(RED, 0.3))
        return im

    return paint


def charge_top(powered: bool) -> Callable[[random.Random], Image.Image]:
    def paint(rnd: random.Random) -> Image.Image:
        im = gb.housing(rnd, rgb("#2a1c20"))
        gb.disc(im, 8, 8, 4.8, lambda d: gb.scale(rgb("#7b8598"), 1.15 - 0.1 * d))
        gb.disc(im, 8, 8, 3.4, lambda d: rgb("#15080a"))
        gb.disc(im, 8, 8, 1.8, lambda d: gb.mix(RED, rgb("#ffffff"), 0.5) if powered else gb.scale(RED, 0.4))
        return im

    return paint


def deck_front(bars: int) -> Callable[[random.Random], Image.Image]:
    """The Cyberdeck block: a screen with four RAM bars (the property `ram` says how many are lit) over a key strip."""
    def paint(rnd: random.Random) -> Image.Image:
        im = gb.housing(rnd, rgb("#222a33"))
        gb.box(im, 2, 2, 14, 10, rgb("#04141a"))
        for i in range(4):
            x0 = 3 + i * 3
            if i < bars:
                gb.box(im, x0, 3, x0 + 2, 9, gb.scale(CYAN, 0.85))
                gb.box(im, x0, 3, x0 + 1, 9, CYAN)
                gb.put(im, x0, 3, rgb("#e6fdff"))
            else:
                gb.box(im, x0, 3, x0 + 2, 9, rgb("#0b3440"))
        for x in range(2, 14, 2):
            gb.box(im, x, 11, x + 1, 12, gb.scale(HOUSING, 1.7))
            gb.box(im, x, 13, x + 1, 14, gb.scale(HOUSING, 1.4))
        gb.put(im, 13, 12, MAGENTA)
        return im

    return paint


def seam_top(rnd: random.Random) -> Image.Image:
    im = gb.housing(rnd, rgb("#222a33"))
    gb.box(im, 3, 7, 13, 9, gb.scale(HOUSING, 0.35))
    return im


# --- blocks ----------------------------------------------------------------------------------------------------------------
def all_sides(side: str, top: str, emission: int = 0) -> dict:
    """A full cube whose four sides share one texture and whose top and bottom share another."""
    faces = {"down": "top", "up": "top", "north": "side", "south": "side", "west": "side", "east": "side"}
    return gb.model({"side": side, "top": top}, [gb.element(*FULL, faces, emission)], "side", ao=not emission)


def device_relay() -> gb.DeviceBlock:
    textures, models = {}, {}
    for linked in (False, True):
        for powered in (False, True):
            stem = f"device_relay_{'linked' if linked else 'unlinked'}_{'on' if powered else 'off'}"
            textures[stem] = relay_face(linked, powered)(random.Random(stem))
            models[stem] = all_sides(stem, "device_relay_top", 7 if powered else 0)
    textures["device_relay_top"] = gb.plain_housing(random.Random("device_relay_top"))

    def pick(s: dict[str, str]) -> str:
        return f"device_relay_{'linked' if s['linked'] == 'true' else 'unlinked'}_{'on' if s['powered'] == 'true' else 'off'}"

    return gb.DeviceBlock("device_relay", "Device Relay", {"linked": ("false", "true"), "powered": ("false", "true")}, textures, models, pick,
                          "device_relay_linked_off", lockable=False)


def proximity_sensor() -> gb.DeviceBlock:
    textures = {f"proximity_sensor_{k}": sensor_face(k == "on")(random.Random(f"proximity_sensor_{k}")) for k in ("off", "on")}
    textures["proximity_sensor_top"] = gb.cap_top(random.Random("proximity_sensor_top"))
    models = {f"proximity_sensor_{k}": all_sides(f"proximity_sensor_{k}", "proximity_sensor_top", 8 if k == "on" else 0) for k in ("off", "on")}
    return gb.DeviceBlock("proximity_sensor", "Proximity Sensor", {"powered": ("false", "true")}, textures, models,
                          lambda s: f"proximity_sensor_{'on' if s['powered'] == 'true' else 'off'}", "proximity_sensor_off", lockable=False)


def heat_sensor() -> gb.DeviceBlock:
    textures = {f"heat_sensor_{t}": heat_face(t)(random.Random(f"heat_sensor_{t}")) for t in range(6)}
    textures["heat_sensor_top"] = heat_top(random.Random("heat_sensor_top"))
    models = {f"heat_sensor_{t}": all_sides(f"heat_sensor_{t}", "heat_sensor_top", 0 if t == 0 else 4 + t) for t in range(6)}
    return gb.DeviceBlock("heat_sensor", "Heat Sensor", {"power": tuple(str(i) for i in range(16))}, textures, models,
                          lambda s: f"heat_sensor_{(int(s['power']) + 2) // 3}", "heat_sensor_3", lockable=False)  # signal = heat x 3: 1..3 is heat 1


def breach_port() -> gb.DeviceBlock:
    textures = {f"breach_port_front_{k}": port_front(k == "on")(random.Random(f"breach_port_front_{k}")) for k in ("off", "on")}
    textures["breach_port_side"] = gb.vent_side(random.Random("breach_port_side"))
    textures["breach_port_top"] = gb.plain_housing(random.Random("breach_port_top"))
    models = {f"breach_port_{k}": gb.cube(f"breach_port_front_{k}", "breach_port_side", "breach_port_top", 6 if k == "on" else 0) for k in ("off", "on")}
    return gb.DeviceBlock("breach_port", "Breach Port", {"facing": gb.FACINGS, "powered": ("false", "true")}, textures, models,
                          lambda s: f"breach_port_{'on' if s['powered'] == 'true' else 'off'}", "breach_port_off", lockable=False)


def charge() -> gb.DeviceBlock:
    textures = {}
    models = {}
    for k in ("off", "on"):
        textures[f"charge_side_{k}"] = charge_side(k == "on")(random.Random(f"charge_side_{k}"))
        textures[f"charge_top_{k}"] = charge_top(k == "on")(random.Random(f"charge_top_{k}"))
        models[f"charge_{k}"] = all_sides(f"charge_side_{k}", f"charge_top_{k}", 9 if k == "on" else 0)
    return gb.DeviceBlock("charge", "Charge", {"powered": ("false", "true")}, textures, models,
                          lambda s: f"charge_{'on' if s['powered'] == 'true' else 'off'}", "charge_off", lockable=False)


def cyberdeck_block() -> gb.DeviceBlock:
    textures = {f"cyberdeck_block_front_{n}": deck_front(n)(random.Random(f"cyberdeck_block_front_{n}")) for n in range(5)}
    textures["cyberdeck_block_side"] = gb.vent_side(random.Random("cyberdeck_block_side"))
    textures["cyberdeck_block_top"] = seam_top(random.Random("cyberdeck_block_top"))
    models = {f"cyberdeck_block_{n}": gb.cube(f"cyberdeck_block_front_{n}", "cyberdeck_block_side", "cyberdeck_block_top", 3 if n else 0)
              for n in range(5)}
    return gb.DeviceBlock("cyberdeck_block", "Cyberdeck Block", {"facing": gb.FACINGS, "ram": tuple(str(i) for i in range(5))}, textures, models,
                          lambda s: f"cyberdeck_block_{s['ram']}", "cyberdeck_block_4", lockable=False)


BLOCKS: list[gb.DeviceBlock] = [device_relay(), proximity_sensor(), heat_sensor(), breach_port(), charge(), cyberdeck_block()]


# --- items -----------------------------------------------------------------------------------------------------------------
Sprite, RAMPS = gi.Sprite, gi.RAMPS


def cyberdeck_link() -> Image.Image:
    """A datajack on a coiled cable: a steel plug with gold pins, the cable, a magenta clip and a cyan spark."""
    s = Sprite()
    s.part(gi.rect(1, 2, 6, 6), "steel", sep=True)
    s.part(gi.rect(2, 6, 5, 7), "gunmetal", sep=True)
    s.dots([(2, 3), (4, 3)], RAMPS["steel"][3])
    for x in (2, 4):
        s.dots([(x, 1)], rgb("#ffd23d"))
    s.part(gi.bar((3.5, 7), (5.5, 11), 1.8), "gunmetal", sep=True)
    s.part(gi.bar((5.5, 11), (11, 11.5), 1.8), "gunmetal", sep=True)
    s.part(gi.bar((11, 11.5), (12.5, 7.5), 1.8), "gunmetal", sep=True)
    s.part(gi.rect(10, 3, 14, 7), "magenta", sep=True)
    s.dots([(11, 4), (12, 4)], RAMPS["magenta"][0])
    s.dots([(14, 2), (15, 1), (13, 1)], gi.CYAN, top=True)
    s.dots([(1, 12), (2, 13)], gi.CYAN, top=True)
    return s.render()


def traffic_tool() -> Image.Image:
    """A handheld with a three-lamp head (red, amber, green) on a grip, an antenna on top."""
    s = Sprite()
    s.part(gi.rect(5, 0, 5, 2), "steel", shade=False)
    s.part(gi.rect(4, 3, 11, 12) - {(4, 3), (11, 3)}, "gunmetal", sep=True)
    s.part(gi.rect(5, 4, 10, 11), "void", shade=False)
    for y, ramp in ((5, "red"), (7, "orange"), (9, "green")):
        s.part(gi.disc(7.5, y + 0.5, 1.3), ramp, shade=False)
        s.dots([(7, y)], gi.WHITE)
    s.part(gi.rect(6, 13, 9, 15), "steel", sep=True)
    s.dots([(6, 14), (9, 14)], RAMPS["steel"][3])
    s.dots([(12, 6), (13, 5), (13, 7)], gi.CYAN, top=True)
    return s.render()


def net_map() -> Image.Image:
    """A folded sheet with a node graph: cyan links and nodes, a magenta ping ring."""
    s = Sprite()
    s.part(gi.rect(1, 2, 14, 13), "bone", sep=True)
    s.part(gi.poly([(10, 13), (14, 13), (14, 9)]), "ash", shade=False)
    s.dots([(x, 2) for x in range(1, 15)], RAMPS["bone"][2])
    nodes = [(3, 5), (7, 4), (11, 6), (5, 9), (9, 10)]
    links = [((3, 5), (7, 4)), ((7, 4), (11, 6)), ((3, 5), (5, 9)), ((5, 9), (9, 10)), ((7, 4), (9, 10))]
    for a, b in links:
        s.dots(gi.line(a, b), RAMPS["cyan"][2])
    s.dots(nodes, gi.CYAN)
    s.dots(gi.arc(11.5, 6.5, 2.6, 0, 360, 16), gi.MAGENTA, top=True)
    s.dots([(11, 6)], gi.WHITE, top=True)
    return s.render()


def comms_panel() -> Image.Image:
    """A handheld transmitter: a screen showing a message bubble, signal arcs over an antenna."""
    s = Sprite()
    s.part(gi.rect(3, 5, 12, 14), "gunmetal", sep=True)
    s.part(gi.rect(4, 6, 11, 10), "void", shade=False)
    s.part(gi.rect(5, 7, 9, 9) - {(5, 9)}, "cyan", shade=False)
    s.dots([(6, 8), (8, 8)], RAMPS["void"][1])
    s.dots([(5, 10)], gi.CYAN)
    s.dots([(5, 12), (7, 12), (9, 12)], RAMPS["steel"][2])
    s.dots([(11, 12)], gi.MAGENTA)
    s.part(gi.rect(11, 1, 11, 4), "steel", shade=False)
    s.dots([(12, 1), (13, 0), (14, 1), (14, 3), (15, 0), (15, 4)], gi.CYAN, top=True)
    s.dots([(10, 3)], gi.YELLOW, top=True)
    return s.render()


def firewall() -> Image.Image:
    """The implant chip with a shield that holds a brick wall, so it reads as a wall of fire between you and the net."""
    s = Sprite()
    gi.chip_frame(s)
    shield = gi.poly([(8, 3.5), (11.6, 4.8), (11.2, 8.2), (8, 11.6), (4.8, 8.2), (4.4, 4.8)])
    s.part(shield, "orange", sep=True)
    for y in (6, 8):
        s.dots([(x, y) for x in range(5, 12) if (x, y) in shield], RAMPS["orange"][3])
    s.dots([(7, 5), (9, 7), (6, 9)], RAMPS["orange"][3])
    s.dots([(8, 4), (7, 4)], RAMPS["yellow"][0])
    s.dots([(8, 10)], gi.RED)
    s.dots([(12, 3), (13, 4)], gi.CYAN, top=True)
    return s.render()


ITEMS: dict[str, tuple[str, Callable[[], Image.Image]]] = {
    "cyberdeck_link": ("Cyberdeck Link", cyberdeck_link), "traffic_tool": ("Traffic Tool", traffic_tool), "net_map": ("Net Map", net_map),
    "comms_panel": ("Comms Panel", comms_panel), "firewall": ("Firewall", firewall)}


# --- output ----------------------------------------------------------------------------------------------------------------
def files() -> dict[str, bytes]:
    """Every file of the Phase 6 blocks and items as {path under assets/cyberblock: bytes}."""
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
    return out


def preview(data: dict[str, bytes]) -> Image.Image:
    """A contact sheet: every block texture at 4x (name order) on a checkerboard, then the five items at 8x."""
    paths = sorted(p for p in data if p.startswith("textures/block/"))
    zoom, cols = 4, 12
    rows = -(-len(paths) // cols)
    sheet = Image.new("RGBA", (cols * SIZE * zoom, rows * SIZE * zoom + SIZE * 8 + 8), (0, 0, 0, 255))
    for y in range(sheet.height):
        for x in range(sheet.width):
            sheet.putpixel((x, y), (46, 48, 58, 255) if (x // 8 + y // 8) % 2 else (62, 64, 76, 255))
    for n, path in enumerate(paths):
        tex = Image.open(io.BytesIO(data[path])).convert("RGBA").resize((SIZE * zoom, SIZE * zoom), Image.NEAREST)
        sheet.alpha_composite(tex, ((n % cols) * SIZE * zoom, (n // cols) * SIZE * zoom))
    for n, item in enumerate(ITEMS):
        tex = Image.open(io.BytesIO(data[f"textures/item/{item}.png"])).convert("RGBA").resize((SIZE * 8, SIZE * 8), Image.NEAREST)
        sheet.alpha_composite(tex, (n * (SIZE * 8 + 8), rows * SIZE * zoom + 8))
    return sheet


def main() -> None:
    data = files()
    for rel, content in data.items():
        (ASSETS / rel).parent.mkdir(parents=True, exist_ok=True)
        (ASSETS / rel).write_bytes(content)
    (ART / "hack_preview.png").write_bytes(gb.png_bytes(preview(data)))
    print(f"wrote {len(BLOCKS)} blocks and {len(ITEMS)} items ({len(data)} files) and art/hack_preview.png")


if __name__ == "__main__":
    main()
