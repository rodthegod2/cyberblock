"""Megaplan 3-A1: the block art script is deterministic and matches what is committed, and the material_palette defaults agree with it."""
import importlib.util
import json
import shutil
import sys
from pathlib import Path

import pytest
from PIL import Image, ImageStat

from tools import content_check

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "mc" / "src" / "main" / "resources" / "assets" / "cyberblock"
PALETTE = ROOT / "mc" / "src" / "main" / "resources" / "data" / "cyberblock" / "cyberblock" / "material_palette" / "default.json"
DYES = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue",
        "brown", "green", "red", "black"]
MATERIALS = ["concrete_light", "concrete_dark", "asphalt", "sidewalk", "glass", "metal", "brick", "tile", "ground", "water"]
IDS = [f"nc_{m}" for m in MATERIALS] + [f"nc_neon_{d}" for d in DYES]


@pytest.fixture(scope="module")
def art():
    spec = importlib.util.spec_from_file_location("gen_block_art", ROOT / "art" / "gen_block_art.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module  # dataclasses look the module up by name
    spec.loader.exec_module(module)
    return module


def generate(art, monkeypatch, tmp: Path) -> dict[str, bytes]:
    """Run the script into tmp (a copy of the assets layout) and return every file it wrote as {relative path: bytes}."""
    monkeypatch.setattr(art, "ASSETS", tmp / "assets" / "cyberblock")
    monkeypatch.setattr(art, "ART", tmp / "art")
    art.main()
    return {p.relative_to(tmp).as_posix(): p.read_bytes() for p in sorted(tmp.rglob("*")) if p.is_file()}


def test_the_table_holds_exactly_the_ids_3r_registers_in_palette_order(art):
    assert [b.id for b in art.BLOCKS] == IDS
    assert list(art.NEON_DYES) == DYES


def test_two_runs_are_byte_identical_and_match_the_committed_files(art, monkeypatch, tmp_path):
    first = generate(art, monkeypatch, tmp_path / "a")
    second = generate(art, monkeypatch, tmp_path / "b")
    assert first.keys() == second.keys() and first == second
    for rel, data in first.items():
        committed = ROOT / "art" / Path(rel).relative_to("art") if rel.startswith("art/") else \
            ROOT / "mc" / "src" / "main" / "resources" / rel
        assert committed.read_bytes() == data, f"{rel} differs from the committed file: run art/gen_block_art.py and commit"


def test_every_block_has_state_model_item_and_a_16x16_texture(art):
    for block in art.BLOCKS:
        state = json.loads((ASSETS / "blockstates" / f"{block.id}.json").read_text("utf-8"))
        assert state == {"variants": {"": {"model": f"cyberblock:block/{block.id}"}}}
        model = json.loads((ASSETS / "models" / "block" / f"{block.id}.json").read_text("utf-8"))
        item = json.loads((ASSETS / "items" / f"{block.id}.json").read_text("utf-8"))
        assert item["model"]["model"] == f"cyberblock:block/{block.id}"
        assert Image.open(ASSETS / "textures" / "block" / f"{block.id}.png").size == (16, 16)
        if block.id.startswith("nc_neon_"):
            assert model["elements"][0]["light_emission"] == 15 and model["ambientocclusion"] is False
        else:
            assert model["parent"] == "minecraft:block/cube_all"


def test_glass_and_water_have_translucent_pixels_and_the_rest_are_opaque(art):
    for block in art.BLOCKS:
        lowest = Image.open(ASSETS / "textures" / "block" / f"{block.id}.png").convert("RGBA").getchannel("A").getextrema()[0]
        assert (0 < lowest < 255) == (block.id in ("nc_glass", "nc_water")), block.id


def test_the_neutral_skin_is_64x64_with_the_classic_body_filled():
    skin = Image.open(ASSETS / "textures" / "entity" / "figure" / "neutral.png")
    assert skin.size == (64, 64) and skin.mode == "RGBA"
    for x, y in ((12, 12), (24, 24), (44, 24), (52, 24), (4, 24), (36, 56), (20, 56)):  # head front, torso, arms, legs
        assert skin.getpixel((x, y))[3] == 255, (x, y)
    assert skin.getpixel((63, 63))[3] == 0  # the unused corner stays transparent


# --- the material_palette defaults -----------------------------------------------------------------------------------------
@pytest.fixture(scope="module")
def palette() -> dict:
    return json.loads(PALETTE.read_text("utf-8"))


def lookup(palette: dict, name: str) -> str | None:
    """What the loader does for a probe material or mesh appearance name."""
    lowered = name.lower()
    return next((m["block"] for m in palette["match"] if any(n in lowered for n in m["names"])), None)


def test_codes_run_0_to_26_in_art_order(palette):
    assert [c["code"] for c in palette["codes"]] == list(range(27))
    assert [c["block"] for c in palette["codes"][1:]] == [f"cyberblock:{i}" for i in IDS]
    assert palette["codes"][0]["block"] == "cyberblock:nc_concrete_light"


def test_neon_colours_are_the_tube_colours(art, palette):
    assert [c["colour"] for c in palette["codes"][11:]] == [art.NEON_DYES[d] for d in DYES]


def test_colours_are_close_to_the_textures(palette):
    for entry in palette["codes"][1:11]:
        texture = Image.open(ASSETS / "textures" / "block" / f"{entry['block'].split(':')[1]}.png").convert("RGB")
        mean = ImageStat.Stat(texture).mean
        want = [int(entry["colour"][1 + 2 * i:3 + 2 * i], 16) for i in range(3)]
        assert sum(abs(a - b) for a, b in zip(mean, want)) < 90, entry


@pytest.mark.parametrize("name,block", [
    ("concrete", "nc_concrete_light"), ("Concrete_Wet", "nc_concrete_dark"), ("road_asphalt_01", "nc_asphalt"),
    ("pavement_tiles", "nc_sidewalk"), ("shop_window", "nc_glass"), ("steel_plate", "nc_metal"), ("brick_red", "nc_brick"),
    ("ceramic_tile", "nc_tile"), ("dirt_dry", "nc_ground"), ("water_still", "nc_water"), ("neon_pink_sign", "nc_neon_pink"),
    ("light_blue_neon", "nc_neon_light_blue"), ("neon_sign", "nc_neon_cyan"), ("metal_panel", "nc_metal"),
])
def test_names_map_to_blocks(palette, name, block):
    assert lookup(palette, name) == f"cyberblock:{block}"


def test_unknown_names_match_nothing(palette):
    assert lookup(palette, "default") is None and lookup(palette, "environment") is None


def test_every_referenced_block_has_art_and_a_code(palette):
    coded = {c["block"] for c in palette["codes"]}
    heur = palette["heuristic"]
    refs = [m["block"] for m in palette["match"]] + [heur["water"]] + list(heur["default"].values())
    refs += [b for s in heur["districts"].values() for b in s.values()]
    for ref in refs:
        assert ref in coded, ref
        assert (ASSETS / "blockstates" / f"{ref.split(':')[1]}.json").is_file()
    assert set(heur["default"]) == {"street", "raised", "wall", "tower"}
    assert all(k == k.lower() for k in heur["districts"])


def test_content_check_passes_the_defaults_and_refuses_a_bad_palette(tmp_path):
    assert content_check.check([content_check.DEFAULT_DATA]).ok
    pack = tmp_path / "data" / "friend" / "cyberblock" / "material_palette"
    pack.mkdir(parents=True)
    (pack / "bad.json").write_text(json.dumps({"schema": 1, "match": [{"names": ["Concrete"], "block": "no block"}], "extra": 1}))
    problems = content_check.check([content_check.data_root(tmp_path)]).problems
    assert any("names" in p or "pattern" in p or "does not match" in p for p in problems)
    assert any("block" in p for p in problems) and any("extra" in p for p in problems)
    shutil.rmtree(tmp_path)
