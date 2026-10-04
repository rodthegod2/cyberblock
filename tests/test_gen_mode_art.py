"""Megaplan 8-A1: the Phase 8 art (Overlord Console, Trauma Beacon, Hacker Van, drone, Trauma Team flyer, Netrunner implant) is deterministic and matches what
is committed; its blocks, items and entity layouts are complete; the English names are in the lang file."""
import importlib.util
import io
import itertools
import json
import sys
from pathlib import Path

import pytest
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / "art"
ASSETS = ROOT / "mc" / "src" / "main" / "resources" / "assets" / "cyberblock"
BLOCK_IDS = ["overlord_console", "trauma_beacon"]
ITEM_IDS = ["hacker_van", "hacker_drone", "netrunner"]
ENTITY = ["hacker_van", "hacker_van_detail", "hacker_drone", "hacker_drone_glow", "trauma_flyer", "trauma_flyer_detail", "trauma_flyer_lights_a",
          "trauma_flyer_lights_b"]


@pytest.fixture(scope="module")
def art():
    sys.path.insert(0, str(ART))
    spec = importlib.util.spec_from_file_location("gen_mode_art", ART / "gen_mode_art.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module  # dataclasses look the module up by name
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def lang() -> dict:
    return json.loads((ASSETS / "lang" / "en_us.json").read_text("utf-8"))


def read(path: Path) -> dict:
    return json.loads(path.read_text("utf-8"))


def image(path: Path) -> Image.Image:
    return Image.open(path).convert("RGBA")


def test_the_tables_hold_the_agreed_ids(art):
    assert [b.id for b in art.BLOCKS] == BLOCK_IDS
    assert list(art.ITEMS) == ITEM_IDS
    assert list(art.textures()) == ENTITY


def test_two_runs_are_byte_identical_and_match_the_committed_files(art):
    first, second = art.files(), art.files()
    assert first == second
    for rel, data in first.items():
        assert (ASSETS / rel).read_bytes() == data, f"{rel} differs from the committed file: run art/gen_mode_art.py and commit"
    sheet = art.preview()
    committed = Image.open(ART / "mode_preview.png").convert("RGB")
    assert committed.size == sheet.size and committed.tobytes() == sheet.tobytes()


def test_the_generator_writes_only_its_own_files(art):
    names = set(BLOCK_IDS) | set(ITEM_IDS)
    for rel in art.files():
        top = rel.split("/")[0]
        assert top in ("textures", "models", "blockstates", "items"), rel
        stem = Path(rel).stem
        assert stem in names or stem.startswith(tuple(BLOCK_IDS)) or stem in ENTITY, rel
    assert not set(art.files()) & {"blockstates/crate.json", "blockstates/holo_table.json", "items/firewall.json"}


def test_blockstates_cover_every_combination_and_point_at_models_that_exist(art):
    for block in art.BLOCKS:
        variants = read(ASSETS / "blockstates" / f"{block.id}.json")["variants"]
        props = block.all_props()
        assert set(props) == {"overlord_console": {"facing"}, "trauma_beacon": {"lit"}}[block.id]
        want = {",".join(f"{n}={v}" for n, v in zip(props, combo)) for combo in itertools.product(*props.values())}
        assert set(variants) == want, block.id
        for key, entry in variants.items():
            assert (ASSETS / "models" / "block" / f"{entry['model'].removeprefix('cyberblock:block/')}.json").is_file(), (block.id, key)
            facing = dict(p.split("=") for p in key.split(",") if p).get("facing")
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
                else:
                    assert image(ASSETS / "textures" / "block" / f"{ref.removeprefix('cyberblock:block/')}.png").size == (16, 16), (stem, key)
            for el in model["elements"]:
                assert all(0 <= a <= b <= 16 for a, b in zip(el["from"], el["to"])), (stem, el["from"], el["to"])
                for face, spec in el["faces"].items():
                    assert spec["texture"][1:] in textures, (stem, face)
            assert textures["particle"][1:] in textures


def test_the_screen_and_the_lit_beacon_glow_and_the_unlit_one_does_not():
    console = read(ASSETS / "models" / "block" / "overlord_console.json")["elements"]
    assert [e.get("light_emission", 0) for e in console] == [0, 0, 8]
    assert [e.get("light_emission", 0) for e in read(ASSETS / "models" / "block" / "trauma_beacon_lit.json")["elements"]] == [0, 0, 15]
    assert [e.get("light_emission", 0) for e in read(ASSETS / "models" / "block" / "trauma_beacon_off.json")["elements"]] == [0, 0, 0]
    lit, off = (image(ASSETS / "textures" / "block" / f"trauma_beacon_{s}.png") for s in ("lit", "off"))
    assert sum(lit.convert("RGB").tobytes()) > 2 * sum(off.convert("RGB").tobytes())


def test_every_item_has_a_16_pixel_texture_and_models(art):
    for item in ITEM_IDS:
        tex = image(ASSETS / "textures" / "item" / f"{item}.png")
        assert tex.size == (16, 16) and tex.getchannel("A").getextrema() == (0, 255), item  # a cut-out sprite
        assert read(ASSETS / "models" / "item" / f"{item}.json")["textures"]["layer0"] == f"cyberblock:item/{item}"
        assert read(ASSETS / "items" / f"{item}.json")["model"]["model"] == f"cyberblock:item/{item}"


# --- the entity layouts -------------------------------------------------------------------------------------------------------------
def test_the_documented_uv_table_is_the_table_the_painters_use(art):
    documented = art.documented_layout()
    assert set(documented) == set(art.PARTS)
    for model, (_size, parts) in art.PARTS.items():
        assert documented[model] == parts, model


def test_the_van_and_the_flyer_extend_the_phase_5_models_exactly(art):
    gv = sys.modules["gen_vehicle_art"]
    assert art.PARTS["hacker_van"][1][:len(gv.MODELS["van"])] == gv.MODELS["van"]
    assert art.PARTS["trauma_flyer"][1][:len(gv.MODELS["trauma_aerodyne"])] == gv.MODELS["trauma_aerodyne"]


def test_every_region_fits_its_texture_and_no_two_overlap(art):
    for model, (size, parts) in art.PARTS.items():
        taken: dict[tuple[int, int], str] = {}
        for part in parts:
            u, v, w, h = art.region(part)
            assert u >= 0 and v >= 0 and u + w <= size and v + h <= size, (model, part.name)
            for x in range(u, u + w):
                for y in range(v, v + h):
                    assert (x, y) not in taken, f"{model}: {part.name} overlaps {taken[(x, y)]} at {x},{y}"
                    taken[(x, y)] = part.name


def faces(art, model):
    from skin_layout import cuboid_faces
    for part in art.PARTS[model][1]:
        for rect in cuboid_faces(part.u, part.v, part.w, part.h, part.d).values():
            yield part, rect


def test_paint_is_opaque_on_every_face_pixel_and_nowhere_else(art):
    tex = art.textures()
    for model, name in (("hacker_van", "hacker_van"), ("hacker_drone", "hacker_drone"), ("trauma_flyer", "trauma_flyer")):
        size = art.PARTS[model][0]
        assert tex[name].size == (size, size), name
        used = set()
        for _part, (x, y, w, h) in faces(art, model):
            used |= {(i, j) for i in range(x, x + w) for j in range(y, y + h)}
        alpha = tex[name].getchannel("A")
        assert {(i, j) for i in range(size) for j in range(size) if alpha.getpixel((i, j)) == 255} == used, name
        assert alpha.getextrema() == (0, 255), name


def test_van_and_flyer_paint_is_greyscale_so_the_colour_tint_works(art):
    tex = art.textures()
    for name in ("hacker_van", "trauma_flyer"):
        pixels = tex[name].tobytes()
        assert all(pixels[i] == pixels[i + 1] == pixels[i + 2] for i in range(0, len(pixels), 4) if pixels[i + 3]), name


def test_detail_glow_and_light_layers_stay_on_the_painted_pixels(art):
    tex = art.textures()
    for paint, layers in (("hacker_van", ["hacker_van_detail"]), ("hacker_drone", ["hacker_drone_glow"]),
                          ("trauma_flyer", ["trauma_flyer_detail", "trauma_flyer_lights_a", "trauma_flyer_lights_b"])):
        base = tex[paint].getchannel("A")
        for name in layers:
            layer = tex[name].getchannel("A")
            assert layer.getextrema()[1] == 255, f"{name} is empty"
            assert all(layer.getpixel((x, y)) == 0 or base.getpixel((x, y)) == 255 for x in range(layer.width) for y in range(layer.height)), name


def test_the_flyer_lights_are_two_disjoint_halves_of_the_light_bar(art):
    tex = art.textures()
    a, b = (tex[f"trauma_flyer_lights_{s}"].getchannel("A") for s in "ab")
    pa = {(x, y) for x in range(128) for y in range(128) if a.getpixel((x, y))}
    pb = {(x, y) for x in range(128) for y in range(128) if b.getpixel((x, y))}
    assert pa and pb and not pa & pb
    bar = next(p for p in art.PARTS["trauma_flyer"][1] if p.name == "light_bar")
    u, v, w, h = art.region(bar)
    assert all(u <= x < u + w and v <= y < v + h for x, y in pa | pb)


def test_the_drone_has_a_glowing_lens_and_four_navigation_lights(art):
    glow = art.textures()["hacker_drone_glow"]
    raw = glow.tobytes()
    colours = {tuple(raw[i:i + 3]) for i in range(0, len(raw), 4) if raw[i + 3]}
    assert {(47, 224, 255), (255, 255, 255), (53, 255, 122), (255, 59, 59)} <= colours


def test_the_committed_entity_files_decode(art):
    for stem in ENTITY:
        data = (ASSETS / "textures" / "entity" / "mode" / f"{stem}.png").read_bytes()
        assert Image.open(io.BytesIO(data)).mode == "RGBA", stem


# --- lang --------------------------------------------------------------------------------------------------------------------------
def test_every_block_item_and_the_implant_has_an_english_name_and_a_tooltip(art, lang):
    for block in art.BLOCKS:
        assert lang[f"block.cyberblock.{block.id}"] == block.name
        assert lang[f"item.cyberblock.{block.id}.tooltip"].strip()
    for item, (name, _paint) in art.ITEMS.items():
        assert lang[f"item.cyberblock.{item}"] == name
        assert lang[f"item.cyberblock.{item}.tooltip"].strip()
    assert lang["cyberware.cyberblock.netrunner.mc"].strip() and lang["cyberware.cyberblock.netrunner.cp"].strip()


def test_the_phase_8_art_lang_keys_sit_together_after_every_older_key(lang):
    keys = list(lang)
    ours = ([f"block.cyberblock.{i}" for i in BLOCK_IDS] + [f"item.cyberblock.{i}" for i in ITEM_IDS]
            + [f"item.cyberblock.{i}.tooltip" for i in BLOCK_IDS + ITEM_IDS] + ["cyberware.cyberblock.netrunner.mc", "cyberware.cyberblock.netrunner.cp"])
    first = keys.index("block.cyberblock.overlord_console")
    assert set(ours) <= set(keys[first:]) and first > keys.index("cyberblock.survey.asked")
