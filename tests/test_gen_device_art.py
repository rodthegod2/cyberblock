"""Megaplan 4-A1: the device block art, the eddies items, the device_map and shop defaults and their lang keys agree with each other."""
import importlib.util
import itertools
import json
import sys
from pathlib import Path

import pytest
from PIL import Image

from tools import content_check

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "mc" / "src" / "main" / "resources" / "assets" / "cyberblock"
DATA = ROOT / "mc" / "src" / "main" / "resources" / "data" / "cyberblock" / "cyberblock"
DYES = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue",
        "brown", "green", "red", "black"]
IDS = [f"lamp_{d}" for d in DYES] + ["eye_camera", "net_port", "vending_machine", "traffic_lamp", "lift_platform", "lift_call_button",
                                     "turret", "sliding_gate", "screen_frame", "crate"]
KINDS = ["door", "gate", "light", "camera", "terminal", "vending", "traffic", "elevator", "turret", "screen", "container"]


@pytest.fixture(scope="module")
def art():
    spec = importlib.util.spec_from_file_location("gen_block_art", ROOT / "art" / "gen_block_art.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def pixels_of(path: Path) -> list[tuple[int, int, int, int]]:
    im = Image.open(path).convert("RGBA")
    return [im.getpixel((x, y)) for y in range(im.height) for x in range(im.width)]


def read(path: Path) -> dict:
    return json.loads(path.read_text("utf-8"))


@pytest.fixture(scope="module")
def lang() -> dict:
    return read(ASSETS / "lang" / "en_us.json")


def test_the_device_table_holds_the_agreed_ids(art):
    assert [b.id for b in art.DEVICES] == IDS


def test_two_runs_are_byte_identical_and_match_the_committed_files(art):
    first, second = art.device_files(), art.device_files()
    assert first == second
    for rel, data in first.items():
        assert (ASSETS / rel).read_bytes() == data, f"{rel} differs from the committed file: run art/gen_block_art.py and commit"
    sheet = art.device_preview(first)
    assert (ROOT / "art" / "device_preview.png").read_bytes() == art.png_bytes(sheet)


def test_blockstates_cover_every_combination_and_point_at_models_that_exist(art):
    for block in art.DEVICES:
        variants = read(ASSETS / "blockstates" / f"{block.id}.json")["variants"]
        props = block.all_props()
        want = {",".join(f"{n}={v}" for n, v in zip(props, combo)) for combo in itertools.product(*props.values())}
        assert set(variants) == want, block.id
        for key, entry in variants.items():
            stem = entry["model"].removeprefix("cyberblock:block/")
            assert (ASSETS / "models" / "block" / f"{stem}.json").is_file(), (block.id, key)
            assert ("quest_locked=true" in key) == ("quest_locked" in stem), (block.id, key)
            facing = dict(p.split("=") for p in key.split(",") if p).get("facing")
            assert entry.get("y", 0) == {"north": 0, "east": 90, "south": 180, "west": 270}.get(facing, 0), (block.id, key)
        assert read(ASSETS / "items" / f"{block.id}.json")["model"]["model"] == f"cyberblock:block/{block.item_model}"


def test_models_use_textures_that_exist_and_stay_inside_the_cell(art):
    for block in art.DEVICES:
        for stem in {e["model"].removeprefix("cyberblock:block/") for e in read(ASSETS / "blockstates" / f"{block.id}.json")["variants"].values()}:
            model = read(ASSETS / "models" / "block" / f"{stem}.json")
            textures = model["textures"]
            for key, ref in textures.items():
                if ref.startswith("#"):
                    assert ref[1:] in textures, (stem, key)
                    continue
                tex = ASSETS / "textures" / "block" / f"{ref.removeprefix('cyberblock:block/')}.png"
                assert Image.open(tex).size == (16, 16), (stem, tex)
            for el in model["elements"]:
                assert all(0 <= a <= b <= 16 for a, b in zip(el["from"], el["to"])), (stem, el["from"], el["to"])
                for face, spec in el["faces"].items():
                    assert spec["texture"][1:] in textures, (stem, face)
                    assert all(0 <= u <= 16 for u in spec.get("uv", [])), (stem, face)
                    assert spec.get("cullface", face) == face, (stem, face)
            assert model["textures"]["particle"][1:] in textures


def test_lit_things_glow_and_the_rest_do_not(art):
    for dye in DYES:
        assert read(ASSETS / "models" / "block" / f"lamp_{dye}_lit.json")["elements"][0]["light_emission"] == 15
        assert "light_emission" not in read(ASSETS / "models" / "block" / f"lamp_{dye}_off.json")["elements"][0]
    assert read(ASSETS / "models" / "block" / "traffic_lamp_off.json")["elements"][0].get("light_emission", 0) == 0
    assert read(ASSETS / "models" / "block" / "traffic_lamp_red.json")["elements"][0]["light_emission"] == 12


def test_quest_locked_textures_are_grey_and_everything_else_is_not_all_grey(art):
    for path in (ASSETS / "textures" / "block").glob("*_quest_locked.png"):
        pixels = pixels_of(path)
        assert all(abs(r - g) <= 1 and abs(b - g) <= 5 for r, g, b, a in pixels if a), path.name
    assert max(abs(r - b) for r, g, b, a in pixels_of(ASSETS / "textures" / "block" / "lamp_cyan_lit.png")) > 60


def test_the_sliding_gate_retracts_in_four_pixel_steps(art):
    widths = []
    for step in range(5):
        elements = read(ASSETS / "models" / "block" / f"sliding_gate_s{step}.json")["elements"]
        panel = [e for e in elements if e["to"][1] == 16]
        widths.append(panel[0]["to"][0] - panel[0]["from"][0] if panel else 0)
    assert widths == [16, 12, 8, 4, 0]


def test_the_eddies_items_have_models_definitions_and_16x16_art(art):
    for item in ("eddies", "eddie_chip"):
        assert Image.open(ASSETS / "textures" / "item" / f"{item}.png").size == (16, 16)
        assert read(ASSETS / "models" / "item" / f"{item}.json")["textures"]["layer0"] == f"cyberblock:item/{item}"
        assert read(ASSETS / "items" / f"{item}.json")["model"]["model"] == f"cyberblock:item/{item}"
    coin = Image.open(ASSETS / "textures" / "item" / "eddies.png").convert("RGBA")
    assert coin.getpixel((0, 0))[3] == 0 and coin.getpixel((8, 8))[3] == 255


def test_the_block_table_and_the_palette_are_untouched(art):
    assert len(art.BLOCKS) == 26 and art.BLOCKS[0].id == "nc_concrete_light" and art.BLOCKS[-1].id == "nc_neon_black"


# --- device_map and shop -------------------------------------------------------------------------------------------------------
def entries(kind: str) -> dict[str, dict]:
    return {p.stem: read(p) for p in sorted((DATA / kind).glob("*.json"))}


FILES = {"Door": "door", "SecurityGate": "security_gate", "RoadBlock": "road_block", "ElectricLight": "electric_light",
         "InteractiveSign": "interactive_sign", "BillboardDevice": "billboard_device", "SurveillanceCamera": "surveillance_camera",
         "Terminal": "terminal", "Computer": "computer", "VendingMachine": "vending_machine", "TrafficLight": "traffic_light",
         "LiftDevice": "lift_device", "SecurityTurret": "security_turret", "TV": "tv", "gameContainerObjectBase": "game_container_object_base"}


def test_one_device_map_file_per_confirmed_class_named_in_snake_case():
    maps = entries("device_map")
    assert {m["class"]: name for name, m in maps.items()} == FILES
    assert {m["kind"] for m in maps.values()} == set(KINDS)
    assert "garage_door" not in maps  # there is no GarageDoor class (the Phase 0 amendment)


def test_device_map_blocks_exist_and_sizes_fit():
    for name, m in entries("device_map").items():
        for ref in [m["block"], m.get("alt_block"), m.get("entity"), *(a["block"] for a in m.get("attachments", []))]:
            if ref and ref.startswith("cyberblock:"):
                assert ref.removeprefix("cyberblock:") in IDS, (name, ref)
        assert m["kind"] != "vending" or m["shops"], name
        assert ("alt_block" in m) == ("alt_when" in m), name
    maps = entries("device_map")
    assert maps["door"]["alt_block"] == "minecraft:iron_door" and maps["door"]["attachments"][0]["when"] == "locked"
    assert maps["game_container_object_base"]["alt_when"] == "readable"
    assert maps["tv"]["entity"] == "minecraft:glow_item_frame"


def test_shops_sell_known_items_for_whole_positive_eddies(lang):
    shops = entries("shop")
    assert sorted(shops) == ["vending_drinks", "vending_gear", "vending_snacks"]
    own_items = {p.stem for p in (ASSETS / "items").glob("*.json")}
    for name, shop in shops.items():
        assert shop["title_key"] in lang, name
        for row in shop["stock"] + shop.get("sell", []):
            ns, item = row["item"].split(":")
            assert ns != "cyberblock" or item in own_items, (name, row)
            assert row["price"] >= 1 and 1 <= row["count"] <= 64
    for m in entries("device_map").values():
        for shop_id in m.get("shops", []):
            assert shop_id.removeprefix("cyberblock:") in shops


def test_content_check_passes_and_refuses_bad_device_files(tmp_path):
    assert content_check.check([content_check.DEFAULT_DATA]).ok
    pack = tmp_path / "data" / "friend" / "cyberblock"
    good = {"schema": 1, "class": "Door", "kind": "door", "block": "minecraft:oak_door", "size": [1, 2, 1]}
    bad = {
        "device_map/no_size": {k: v for k, v in good.items() if k != "size"},
        "device_map/bad_kind": {**good, "kind": "garage"},
        "device_map/alt_alone": {**good, "alt_block": "minecraft:iron_door"},
        "device_map/size_zero": {**good, "size": [0, 2, 1]},
        "device_map/bad_shop": {**good, "kind": "vending", "shops": ["friend:missing"]},
        "device_map/long_class": {**good, "class": "A" * 49},
        "shop/free": {"schema": 1, "title_key": "x.y", "stock": [{"item": "minecraft:bread", "count": 1, "price": 0}]},
        "shop/empty": {"schema": 1, "title_key": "x.y", "stock": []},
        "shop/loose": {"schema": 1, "title_key": "x.y", "stock": [{"item": "bread", "count": 1, "price": 1}]},
    }
    for rel, data in {**bad, "device_map/good": good,
                      "shop/good": {"schema": 1, "title_key": "x.y", "stock": [{"item": "minecraft:bread", "count": 2, "price": 3}]}}.items():
        path = pack / f"{rel}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data), encoding="utf-8")
    problems = content_check.check([tmp_path / "data"], reference_roots=[content_check.DEFAULT_DATA]).problems
    for rel in bad:
        assert any(f"{rel}.json:" in p for p in problems), (rel, problems)
    assert not any("/good.json:" in p for p in problems), problems


# --- lang ----------------------------------------------------------------------------------------------------------------------
def test_every_block_item_and_kind_has_an_english_name(art, lang):
    for block in art.DEVICES:
        assert lang[f"block.cyberblock.{block.id}"] == block.name
    for item, (name, _paint) in art.ITEMS.items():
        assert lang[f"item.cyberblock.{item}"] == name
    for kind in KINDS:
        assert lang[f"cyberblock.device.kind.{kind}"]
    for key in ("cyberblock.device.quest_locked", "cyberblock.device.unsupported", "cyberblock.device.denied", "cyberblock.shop.title",
                "cyberblock.shop.no_funds", "cyberblock.device.toggle.on", "cyberblock.device.toggle.off", "cyberblock.eddies.balance"):
        assert lang[key].strip(), key


def test_the_phase_4_lang_keys_are_one_contiguous_block(art, lang):
    keys = list(lang)
    ours = {f"block.cyberblock.{b.id}" for b in art.DEVICES} | {f"item.cyberblock.{i}" for i in art.ITEMS}
    prefixes = ("cyberblock.device.", "cyberblock.shop.", "cyberblock.eddies.", "item.cyberblock.eddie_chip.")
    block = keys[keys.index("block.cyberblock.lamp_white"):keys.index("cyberblock.shop.empty") + 1]
    assert all(k in ours or k.startswith(prefixes) for k in block), [k for k in block if k not in ours and not k.startswith(prefixes)]
    assert [k for k in keys if k in ours or k.startswith(prefixes)] == block
