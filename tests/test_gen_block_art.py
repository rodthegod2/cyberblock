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
PALETTE = ROOT / "mc" / "src" / "main" / "resources" / "data" / "cyberblock" / "cyberblock" / "material_palette"
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


# --- the material_palette defaults (one entry per file, the shape the replica's Palette loader reads) ---------------------------
HEURISTICS = {"street": "asphalt", "sidewalk": "sidewalk", "roof": "concrete_light", "offroad": "ground", "wall_base": "concrete_dark",
              "wall_brick": "brick", "wall_trim": "metal", "wall_window": "glass", "wall": "concrete_light", "water": "water"}
"""The heuristic classes and the materials 3R's loader has built in: the shipped entries must not change behaviour."""


@pytest.fixture(scope="module")
def palette() -> dict[str, dict]:
    return {p.stem: json.loads(p.read_text("utf-8")) for p in sorted(PALETTE.glob("*.json"))}


def coded(palette: dict) -> dict[int, dict]:
    return {e["code"]: e for e in palette.values() if "code" in e}


def test_one_file_per_code_1_to_26_named_after_its_block_in_art_order(palette):
    codes = coded(palette)
    assert sorted(codes) == list(range(1, 27))
    assert [codes[c]["block"] for c in range(1, 27)] == [f"cyberblock:{i}" for i in IDS]
    assert all(name == e["block"].split(":")[1] for name, e in palette.items() if "code" in e)


def test_one_file_per_heuristic_class_with_the_built_in_material(palette):
    heur = {e["heuristic"]: e["block"] for e in palette.values() if "heuristic" in e}
    assert heur == {h: f"cyberblock:nc_{m}" for h, m in HEURISTICS.items()}
    assert all(name == f"heuristic_{e['heuristic']}" for name, e in palette.items() if "heuristic" in e)
    assert len(palette) == 26 + len(HEURISTICS)


def test_no_entry_carries_material_names(palette):
    """The server only receives codes: the names-to-codes table is the shared one in shared/ (tools/cbmath.py)."""
    assert all(set(e) <= {"schema", "block", "code", "heuristic", "colour"} for e in palette.values())


def test_codes_agree_with_the_shared_table(palette):
    """shared/vectors.json `materials` (3P) pins the codes: every case name resolves to the same block through the palette."""
    shared = json.loads((ROOT / "shared" / "vectors.json").read_text("utf-8")).get("materials")
    if shared is None:
        pytest.skip("shared/vectors.json has no `materials` yet (3P merges it)")
    codes = coded(palette)
    table = {c["code"]: c["block"] for c in shared["codes"]}
    assert {c: e["block"] for c, e in codes.items()} == {c: b for c, b in table.items() if c != 0}
    for case in shared["cases"]:
        want = table[case["code"]]
        assert (codes[case["code"]]["block"] if case["code"] else None) == want, case


def test_neon_colours_are_the_tube_colours(art, palette):
    codes = coded(palette)
    assert [codes[c]["colour"] for c in range(11, 27)] == [art.NEON_DYES[d] for d in DYES]


def test_colours_are_close_to_the_textures(palette):
    for code, entry in coded(palette).items():
        if code > 10:
            continue
        texture = Image.open(ASSETS / "textures" / "block" / f"{entry['block'].split(':')[1]}.png").convert("RGB")
        mean = ImageStat.Stat(texture).mean
        want = [int(entry["colour"][1 + 2 * i:3 + 2 * i], 16) for i in range(3)]
        assert sum(abs(a - b) for a, b in zip(mean, want)) < 90, entry


def test_every_block_has_art(palette):
    for entry in palette.values():
        assert (ASSETS / "blockstates" / f"{entry['block'].split(':')[1]}.json").is_file(), entry


def test_content_check_passes_the_defaults_and_refuses_bad_entries(tmp_path):
    assert content_check.check([content_check.DEFAULT_DATA]).ok
    pack = tmp_path / "data" / "friend" / "cyberblock" / "material_palette"
    pack.mkdir(parents=True)
    bad = {
        "both": {"schema": 1, "block": "cyberblock:nc_brick", "code": 7, "heuristic": "wall"},
        "names": {"schema": 1, "block": "cyberblock:nc_brick", "match": [{"names": ["brick"]}]},
        "code0": {"schema": 1, "block": "cyberblock:nc_brick", "code": 0},
        "class": {"schema": 1, "block": "cyberblock:nc_brick", "heuristic": "tower"},
        "vanilla": {"schema": 1, "block": "minecraft:stone"},
        "noblock": {"schema": 1, "code": 3},
    }
    for name, data in bad.items():
        (pack / f"{name}.json").write_text(json.dumps(data))
    (pack / "good.json").write_text(json.dumps({"schema": 1, "block": "cyberblock:nc_brick", "heuristic": "street", "colour": "#884422"}))
    problems = content_check.check([content_check.data_root(tmp_path)]).problems
    for name in bad:
        assert any(f"/{name}.json:" in p for p in problems), (name, problems)
    assert not any("/good.json:" in p for p in problems), problems
    shutil.rmtree(tmp_path)
