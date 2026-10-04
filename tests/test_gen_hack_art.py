"""Megaplan 6-A1: the Phase 6 block and item art, the daemon and breach_tier defaults, the recipes and the lang keys agree with each other."""
import importlib.util
import itertools
import json
import re
import sys
from pathlib import Path

import pytest
from PIL import Image

from tools import content_check

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / "art"
ASSETS = ROOT / "mc" / "src" / "main" / "resources" / "assets" / "cyberblock"
DATA = ROOT / "mc" / "src" / "main" / "resources" / "data" / "cyberblock"
BLOCK_IDS = ["device_relay", "proximity_sensor", "heat_sensor", "breach_port", "charge", "cyberdeck_block"]
ITEM_IDS = ["cyberdeck_link", "traffic_tool", "net_map", "comms_panel", "firewall"]
PROPS = {"device_relay": {"linked": 2, "powered": 2}, "proximity_sensor": {"powered": 2}, "heat_sensor": {"power": 16},
         "breach_port": {"facing": 4, "powered": 2}, "charge": {"powered": 2}, "cyberdeck_block": {"facing": 4, "ram": 5}}
CODES = {"1C", "55", "BD", "E9", "7A", "FF"}
KINDS = ["door", "gate", "light", "camera", "terminal", "vending", "traffic", "elevator", "turret", "screen", "container"]
ACTIONS = ["open_door", "disable_cameras", "reveal_enemies", "overload", "disable_turrets", "lights_out", "distract", "traffic_switch", "make_friendly"]


@pytest.fixture(scope="module")
def art():
    sys.path.insert(0, str(ART))
    spec = importlib.util.spec_from_file_location("gen_hack_art", ART / "gen_hack_art.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def read(path: Path) -> dict:
    return json.loads(path.read_text("utf-8"))


@pytest.fixture(scope="module")
def lang() -> dict:
    return read(ASSETS / "lang" / "en_us.json")


def entries(kind: str) -> dict[str, dict]:
    return {p.stem: read(p) for p in sorted((DATA / "cyberblock" / kind).glob("*.json"))}


# --- art ---------------------------------------------------------------------------------------------------------------------
def test_the_tables_hold_the_agreed_ids(art):
    assert [b.id for b in art.BLOCKS] == BLOCK_IDS
    assert list(art.ITEMS) == ITEM_IDS


def test_two_runs_are_byte_identical_and_match_the_committed_files(art):
    first, second = art.files(), art.files()
    assert first == second
    for rel, data in first.items():
        assert (ASSETS / rel).read_bytes() == data, f"{rel} differs from the committed file: run art/gen_hack_art.py and commit"
    assert (ART / "hack_preview.png").read_bytes() == art.gb.png_bytes(art.preview(first))


def test_blockstates_cover_every_combination_without_quest_locked_and_point_at_models_that_exist(art):
    for block in art.BLOCKS:
        variants = read(ASSETS / "blockstates" / f"{block.id}.json")["variants"]
        props = block.all_props()
        assert {n: len(v) for n, v in props.items()} == PROPS[block.id], block.id
        want = {",".join(f"{n}={v}" for n, v in zip(props, combo)) for combo in itertools.product(*props.values())}
        assert set(variants) == want, block.id
        for key, entry in variants.items():
            assert (ASSETS / "models" / "block" / f"{entry['model'].removeprefix('cyberblock:block/')}.json").is_file(), (block.id, key)
            facing = dict(p.split("=") for p in key.split(",")).get("facing")
            assert entry.get("y", 0) == {"north": 0, "east": 90, "south": 180, "west": 270}.get(facing, 0), (block.id, key)
        assert read(ASSETS / "items" / f"{block.id}.json")["model"]["model"] == f"cyberblock:block/{block.item_model}"


def test_models_use_textures_that_exist_and_stay_inside_the_cell(art):
    for block in art.BLOCKS:
        for stem in block.models:
            model = read(ASSETS / "models" / "block" / f"{stem}.json")
            textures = model["textures"]
            for key, ref in textures.items():
                if ref.startswith("#"):
                    assert ref[1:] in textures, (stem, key)
                    continue
                assert Image.open(ASSETS / "textures" / "block" / f"{ref.removeprefix('cyberblock:block/')}.png").size == (16, 16), (stem, key)
            for el in model["elements"]:
                assert all(0 <= a <= b <= 16 for a, b in zip(el["from"], el["to"])), stem
                assert all(spec["texture"][1:] in textures for spec in el["faces"].values()), stem


def test_the_heat_sensor_shows_heat_zero_to_five_from_signal_strength_zero_to_fifteen(art):
    variants = read(ASSETS / "blockstates" / "heat_sensor.json")["variants"]
    assert {k.removeprefix("power="): v["model"].rsplit("_", 1)[1] for k, v in variants.items()} == {str(p): str((p + 2) // 3) for p in range(16)}
    lit = [bool(read(ASSETS / "models" / "block" / f"heat_sensor_{t}.json")["elements"][0].get("light_emission")) for t in range(6)]
    assert lit == [False] + [True] * 5


def test_the_cyberdeck_block_lights_one_more_bar_per_ram_step(art):
    def lit_bars(n: int) -> int:
        im = Image.open(ASSETS / "textures" / "block" / f"cyberdeck_block_front_{n}.png").convert("RGB")
        return sum(1 for i in range(4) if im.getpixel((4 + i * 3, 5))[2] > 150)  # the bright cyan of a lit bar, not the dim well

    assert [lit_bars(n) for n in range(5)] == [0, 1, 2, 3, 4]


def test_powered_blocks_glow_and_unpowered_ones_do_not(art):
    for stem in ("device_relay_linked_on", "device_relay_unlinked_on", "proximity_sensor_on", "breach_port_on", "charge_on"):
        assert read(ASSETS / "models" / "block" / f"{stem}.json")["elements"][0]["light_emission"] > 0, stem
    for stem in ("device_relay_linked_off", "device_relay_unlinked_off", "proximity_sensor_off", "breach_port_off", "charge_off", "cyberdeck_block_0"):
        assert "light_emission" not in read(ASSETS / "models" / "block" / f"{stem}.json")["elements"][0], stem


def test_the_items_have_models_definitions_and_16x16_art(art):
    for item in ITEM_IDS:
        im = Image.open(ASSETS / "textures" / "item" / f"{item}.png").convert("RGBA")
        assert im.size == (16, 16)
        assert sum(1 for x in range(16) for y in range(16) if im.getpixel((x, y))[3]) > 40, item  # not an empty sprite
        assert read(ASSETS / "models" / "item" / f"{item}.json")["textures"]["layer0"] == f"cyberblock:item/{item}"
        assert read(ASSETS / "items" / f"{item}.json")["model"]["model"] == f"cyberblock:item/{item}"


def test_no_file_collides_with_a_phase_4_device_file(art):
    older = set(art.gb.device_files())
    assert not older & set(art.files())


# --- daemon and breach_tier ----------------------------------------------------------------------------------------------------
def test_every_daemon_is_a_valid_file_with_original_codes_and_three_rising_tiers(lang):
    daemons = entries("daemon")
    assert sorted(daemons) == sorted(ACTIONS)
    # The `daemon` message `list` carries each daemon's `action` (the protocol allows [a-z0-9_]{1,32}), never its content id or file
    # name; the shipped defaults merely name each file after its action, a datapack daemon may use any name.
    schema_actions = read(ROOT / "shared" / "content-schemas" / "daemon.schema.json")["properties"]["action"]["enum"]
    assert schema_actions == ACTIONS and all(re.fullmatch(r"[a-z0-9_]{1,32}", a) for a in ACTIONS)
    for name, d in daemons.items():
        assert d["action"] == name and set(d["sequence"]) <= CODES and 2 <= len(d["sequence"]) <= 4, name
        assert len({tuple(x["sequence"]) for x in daemons.values()}) == len(daemons), "two daemons share a sequence"
        seconds = [t["seconds"] for t in d["tiers"]]
        radius = [t["radius"] for t in d["tiers"]]
        assert seconds == sorted(seconds) and radius == sorted(radius) and len(set(radius)) == 3, name
        assert lang[d["title_key"]].strip() and lang[d["text_key"]].strip(), name
        assert set(d["kinds"]) <= set(KINDS)


def test_every_device_kind_but_the_unproven_lift_offers_a_daemon_at_the_lowest_security():
    daemons = entries("daemon")
    for kind in KINDS:
        offered = [n for n, d in daemons.items() if kind in d["kinds"] and d.get("min_security", 1) == 1]
        assert bool(offered) == (kind != "elevator"), (kind, offered)


def test_breach_tiers_get_harder_with_security_and_with_firewall_rank():
    tiers = {t["security"]: t for t in entries("breach_tier").values()}
    assert sorted(tiers) == [1, 2, 3] and sorted(entries("breach_tier")) == ["security_1", "security_2", "security_3"]
    assert [tiers[s]["grid"] for s in (1, 2, 3)] == [5, 6, 7]
    assert [tiers[s]["seconds"] for s in (1, 2, 3)] == sorted((tiers[s]["seconds"] for s in (1, 2, 3)), reverse=True)
    daemons = entries("daemon")
    for s, t in tiers.items():
        assert 4 <= t["buffer"] <= 8 and t["daemons_min"] <= t["daemons_max"], s
        offered = [d for d in daemons.values() if d.get("min_security", 1) <= s]
        assert t["buffer"] >= max(len(d["sequence"]) for d in offered), s  # every offered daemon fits the buffer on its own
        steps = t["firewall"]
        for field in ("grid_add", "daemons_add"):
            assert [x[field] for x in steps] == sorted(x[field] for x in steps), (s, field)
        for field in ("buffer_add", "seconds_add"):
            assert [x[field] for x in steps] == sorted((x[field] for x in steps), reverse=True), (s, field)
        assert t["buffer"] + steps[-1]["buffer_add"] >= 4 and t["seconds"] + steps[-1]["seconds_add"] >= 10, s


def test_content_check_passes_and_refuses_bad_daemon_and_tier_files(tmp_path):
    assert content_check.check([content_check.DEFAULT_DATA]).ok
    pack = tmp_path / "data" / "friend" / "cyberblock"
    daemon = entries("daemon")["open_door"]
    tier = entries("breach_tier")["security_1"]
    bad = {
        "daemon/short": {**daemon, "sequence": ["1C"]},
        "daemon/long": {**daemon, "sequence": ["1C", "55", "BD", "E9", "7A"]},
        "daemon/code": {**daemon, "sequence": ["1C", "ZZ"]},
        "daemon/action": {**daemon, "action": "launch_missile"},
        "daemon/kind": {**daemon, "kinds": ["door", "garage"]},
        "daemon/tiers": {**daemon, "tiers": daemon["tiers"][:2]},
        "daemon/radius": {**daemon, "tiers": [{"seconds": 1, "radius": 0}] * 3},
        "daemon/dupe_kinds": {**daemon, "kinds": ["door", "door"]},
        "breach_tier/grid": {**tier, "grid": 8},
        "breach_tier/buffer": {**tier, "buffer": 3},
        "breach_tier/security": {**tier, "security": 4},
        "breach_tier/steps": {**tier, "firewall": tier["firewall"][:2]},
        "breach_tier/grow": {**tier, "firewall": [{**tier["firewall"][0], "buffer_add": 1}] * 3},
    }
    for rel, data in {**bad, "daemon/good": daemon, "breach_tier/good": tier}.items():
        path = pack / f"{rel}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data), encoding="utf-8")
    problems = content_check.check([tmp_path / "data"], reference_roots=[content_check.DEFAULT_DATA]).problems
    for rel in bad:
        assert any(f"{rel}.json:" in p for p in problems), (rel, problems)
    assert not any("/good.json:" in p for p in problems), problems


# --- recipes -------------------------------------------------------------------------------------------------------------------
def test_every_phase_6_thing_has_a_shapeless_recipe_that_makes_it():
    own = {p.stem for p in (ASSETS / "items").glob("*.json")}
    for name in BLOCK_IDS + ITEM_IDS:
        recipe = read(DATA / "recipe" / f"{name}.json")
        assert recipe["type"] == "minecraft:crafting_shapeless" and recipe["result"] == {"id": f"cyberblock:{name}", "count": 1}, name
        assert 3 <= len(recipe["ingredients"]) <= 6, name
        for ingredient in recipe["ingredients"]:
            namespace, item = ingredient.split(":")
            assert namespace != "cyberblock" or item in own, (name, ingredient)


# --- lang ----------------------------------------------------------------------------------------------------------------------
def test_every_block_item_and_implant_has_an_english_name_and_a_tooltip(art, lang):
    for block in art.BLOCKS:
        assert lang[f"block.cyberblock.{block.id}"] == block.name
        assert lang[f"item.cyberblock.{block.id}.tooltip"].strip()
    for item, (name, _paint) in art.ITEMS.items():
        assert lang[f"item.cyberblock.{item}"] == name
        assert lang[f"item.cyberblock.{item}.tooltip"].strip()
    assert lang["cyberware.cyberblock.firewall.mc"].strip() and lang["cyberware.cyberblock.firewall.cp"].strip()


def test_the_phase_6_lang_keys_exist_and_sit_in_one_contiguous_block(lang):
    keys = list(lang)
    for key in ("cyberblock.redstone.loop", "cyberblock.hack.out_of_range", "cyberblock.breach.title", "cyberblock.hack.vehicle.title",
                "cyberblock.hack.traffic.title", "cyberblock.hack.netmap.title", "cyberblock.hack.comms.title", "cyberblock.redstone.link.unresolved"):
        assert lang[key].strip(), key
    block = keys[keys.index("block.cyberblock.device_relay"):keys.index("cyberblock.hack.comms.no_host") + 1]
    prefixes = ("cyberblock.redstone.", "cyberblock.breach.", "cyberblock.daemon.", "cyberblock.hack.vehicle.", "cyberblock.hack.traffic.",
                "cyberblock.hack.netmap.", "cyberblock.hack.comms.", "block.cyberblock.", "item.cyberblock.", "cyberware.cyberblock.firewall")
    assert all(k.startswith(prefixes) or k in ("cyberblock.hack.out_of_range", "cyberblock.hack.denied", "cyberblock.hack.sent",
                                               "cyberblock.hack.wheel.no_figure") for k in block)
    assert [k for k in keys if k.startswith(prefixes[:7])] == [k for k in block if k.startswith(prefixes[:7])]  # none of them lives outside the block


def test_format_arguments_are_what_the_screens_say_they_take(lang):
    for key, args in {"cyberblock.redstone.cyberdeck.ram": 2, "cyberblock.breach.complete": 2, "cyberblock.breach.partial": 2,
                      "cyberblock.hack.comms.from": 2, "cyberblock.breach.time": 1, "cyberblock.breach.heat": 1,
                      "cyberblock.redstone.proximity.range": 1, "cyberblock.redstone.link.done": 1, "cyberblock.hack.denied": 1}.items():
        assert lang[key].count("%s") == args, key
