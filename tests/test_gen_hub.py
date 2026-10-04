"""Megaplan 7-A1: the hub generator is deterministic and matches what is committed; the layout is valid and its figures stand where they say;
the five Phase 7 blocks have complete art and English names."""
import importlib.util
import itertools
import json
import sys
from pathlib import Path

import pytest
from PIL import Image

from tools import content_check

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "mc" / "src" / "main" / "resources"
ASSETS = RES / "assets" / "cyberblock"
HUB = RES / "data" / "cyberblock" / "cyberblock" / "hub" / "afterlife.json"
IDS = ["holo_table", "spawn_beacon", "hub_portal", "match_terminal", "leaderboard_board"]
FACING_BLOCKS = {"match_terminal", "leaderboard_board"}


@pytest.fixture(scope="module")
def hub():
    spec = importlib.util.spec_from_file_location("gen_hub", ROOT / "art" / "gen_hub.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module  # dataclasses look the module up by name
    spec.loader.exec_module(module)
    return module


def read(path: Path) -> dict:
    return json.loads(path.read_text("utf-8"))


@pytest.fixture(scope="module")
def layout() -> dict:
    return read(HUB)


def test_the_table_holds_the_five_agreed_blocks(hub):
    assert [b.id for b in hub.BLOCKS] == IDS


def test_two_runs_are_byte_identical_and_match_the_committed_files(hub):
    first, second = hub.block_files(), hub.block_files()
    assert first == second
    for rel, data in first.items():
        assert (ASSETS / rel).read_bytes() == data, f"{rel} differs from the committed file: run art/gen_hub.py and commit"
    doc = hub.layout_document()
    assert hub.layout_bytes(doc) == hub.layout_bytes(hub.layout_document())
    assert HUB.read_bytes() == hub.layout_bytes(doc), "the committed hub layout differs: run art/gen_hub.py and commit"
    sheet = hub.preview(doc, first)
    committed = Image.open(ROOT / "art" / "hub_preview.png").convert("RGB")
    assert list(committed.getdata()) == list(sheet.getdata()) and committed.size == sheet.size


def test_the_generator_never_writes_outside_its_own_files(hub):
    assert hub.HUB_FILE.relative_to(RES).as_posix() == "data/cyberblock/cyberblock/hub/afterlife.json"
    assert all(rel.split("/")[0] in ("textures", "models", "blockstates", "items") for rel in hub.block_files())
    assert not set(hub.block_files()) & {f"blockstates/{i}.json" for i in ("crate", "lamp_white", "nc_brick")}


def test_blockstates_cover_every_combination_and_point_at_models_that_exist(hub):
    for block in hub.BLOCKS:
        variants = read(ASSETS / "blockstates" / f"{block.id}.json")["variants"]
        props = block.all_props()
        assert set(props) == ({"facing"} if block.id in FACING_BLOCKS else set()), block.id
        want = {",".join(f"{n}={v}" for n, v in zip(props, combo)) for combo in itertools.product(*props.values())}
        assert set(variants) == want, block.id
        for key, entry in variants.items():
            assert (ASSETS / "models" / "block" / f"{entry['model'].removeprefix('cyberblock:block/')}.json").is_file(), (block.id, key)
            facing = dict(p.split("=") for p in key.split(",") if p).get("facing")
            assert entry.get("y", 0) == {"north": 0, "east": 90, "south": 180, "west": 270}.get(facing, 0), (block.id, key)
        assert read(ASSETS / "items" / f"{block.id}.json")["model"]["model"] == f"cyberblock:block/{block.item_model}"


def test_models_use_textures_that_exist_and_stay_inside_the_cell(hub):
    for block in hub.BLOCKS:
        for stem in block.models:
            model = read(ASSETS / "models" / "block" / f"{stem}.json")
            textures = model["textures"]
            for key, ref in textures.items():
                if ref.startswith("#"):
                    assert ref[1:] in textures, (stem, key)
                    continue
                assert Image.open(ASSETS / "textures" / "block" / f"{ref.removeprefix('cyberblock:block/')}.png").size == (16, 16), (stem, key)
            for el in model["elements"]:
                assert all(0 <= a <= b <= 16 for a, b in zip(el["from"], el["to"])), (stem, el["from"], el["to"])
                for face, spec in el["faces"].items():
                    assert spec["texture"][1:] in textures, (stem, face)
                    assert all(0 <= u <= 16 for u in spec.get("uv", [])), (stem, face)
            assert textures["particle"][1:] in textures


def test_the_glowing_blocks_glow_and_the_translucent_skyline_has_alpha(hub):
    assert read(ASSETS / "models" / "block" / "hub_portal.json")["elements"][0]["light_emission"] == 15
    assert read(ASSETS / "models" / "block" / "match_terminal.json")["elements"][0]["light_emission"] == 7
    assert [e.get("light_emission", 0) for e in read(ASSETS / "models" / "block" / "holo_table.json")["elements"]] == [0, 0, 15, 15]
    alpha = Image.open(ASSETS / "textures" / "block" / "holo_table_proj.png").convert("RGBA").getchannel("A").getextrema()
    assert alpha[0] == 0 and 0 < alpha[1] < 256
    assert Image.open(ASSETS / "textures" / "block" / "hub_portal.png").convert("RGBA").getchannel("A").getextrema() == (255, 255)


def test_every_block_has_an_english_name():
    lang = read(ASSETS / "lang" / "en_us.json")
    names = {"holo_table": "Holo-Table", "spawn_beacon": "Spawn Beacon", "hub_portal": "Hub Portal", "match_terminal": "Match Terminal",
             "leaderboard_board": "Leaderboard Board"}
    for bid, name in names.items():
        assert lang[f"block.cyberblock.{bid}"] == name


def test_the_phase_7_lang_keys_follow_the_phase_4_block_and_stay_out_of_its_prefixes():
    keys = list(read(ASSETS / "lang" / "en_us.json"))
    ours = [k for k in keys if k.startswith(("cyberblock.afterlife.", "cyberblock.role.", "cyberblock.drink.", "cyberblock.gig.", "cyberblock.landmark."))]
    ours += [f"block.cyberblock.{i}" for i in IDS]
    first, last = keys.index("block.cyberblock.holo_table"), keys.index("cyberblock.landmark.kind.wilds")
    assert sorted(keys[first:last + 1]) == sorted(ours)
    assert first == keys.index("cyberblock.shop.empty") + 1
    assert not any(k.startswith(("cyberblock.shop.afterlife", "cyberblock.device.afterlife")) for k in keys)


# --- the layout -------------------------------------------------------------------------------------------------------------
def test_the_layout_passes_content_check_and_the_generators_own_checks(hub, layout):
    assert content_check.check([content_check.DEFAULT_DATA]).ok
    assert hub.layout_problems(layout) == []
    assert layout["size"] == [43, 9, 35] and len(layout["steps"]) <= 4000
    assert HUB.stat().st_size < 100_000


def test_the_layout_has_the_rooms_the_plan_names(layout):
    assert {"lobby", "hall", "bar", "booths", "shops", "board_wall", "match_floor", "holo_room", "clinic", "garage"} <= set(layout["areas"])
    assert {b["objective"] for b in layout["boards"]} == {"cb_wins", "cb_gigs", "cb_chaos"}
    assert all(len(b["objective"]) <= 16 for b in layout["boards"])
    roles = sorted(f["role"].removeprefix("cyberblock:") for f in layout["figures"])
    assert roles == sorted(["bartender", "fixer", "ripperdoc", "medic", "mechanic"] + ["vendor"] * 5)


def test_the_layout_uses_only_known_blocks(layout):
    items = {p.stem for p in (ASSETS / "items").glob("*.json")}
    for step in layout["steps"]:
        ns, rest = step["block"].split(":", 1)
        name = rest.split("[")[0]
        if ns == "cyberblock":
            assert name in items, step
        else:
            assert ns == "minecraft", step


def test_figures_and_the_spawn_stand_on_floor_with_head_room(hub, layout):
    assert hub.layout_problems(layout) == []
    broken = json.loads(json.dumps(layout))
    broken["figures"][0]["pos"] = [21.5, 1.0, 7.5]  # inside the counter
    broken["figures"][1]["pos"] = [100.5, 1.0, 20.5]  # outside the shell
    problems = hub.layout_problems(broken)
    assert any("figure bartender" in p for p in problems) and any("figure fixer" in p for p in problems)


def test_the_layout_checker_catches_a_station_that_is_not_its_block(hub, layout):
    broken = json.loads(json.dumps(layout))
    broken["stations"]["holo_table"] = [4, 1, 15]
    broken["steps"][0]["to"] = [60, 8, 34]
    problems = hub.layout_problems(broken)
    assert any("station holo_table" in p for p in problems) and any("leaves the size" in p for p in problems)


def test_the_spawn_faces_the_hall_and_the_exit_portal_is_behind_it(layout):
    spawn = layout["spawn"]
    assert spawn["yaw"] == 180 and spawn["pos"][2] > layout["areas"]["hall"]["to"][2]  # in the lobby, facing north into the hall
    assert layout["stations"]["exit_portal"][2] > spawn["pos"][2]
