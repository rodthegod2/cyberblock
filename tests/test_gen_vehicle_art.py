"""Megaplan 5-A2: the vehicle textures follow the documented UV layout, are deterministic and tintable, and the vehicle_map
defaults resolve records and classes to the 12 models (unknown vehicle: a sedan in the class colour)."""
import json
import shutil
import sys
from pathlib import Path

import pytest
from PIL import Image

from tools import content_check

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "art"))

import gen_vehicle_art as gen  # noqa: E402
from skin_layout import cuboid_faces  # noqa: E402

RESOURCES = ROOT / "mc" / "src" / "main" / "resources"
VEHICLE = RESOURCES / "assets" / "cyberblock" / "textures" / "entity" / "vehicle"
VEHICLE_MAP = RESOURCES / "data" / "cyberblock" / "cyberblock" / "vehicle_map"
MODELS = ["sedan", "sports_car", "truck", "van", "bike", "taxi", "police_cruiser", "armoured_van", "trauma_aerodyne",
          "ncpd_aerodyne", "delivery_truck", "pickup"]
# 2.31 script enums (the installed scripts; NativeDB): gamedataVehicleType, gamedataVehicleModel, gamedataAffiliation (subset used)
VEHICLE_TYPES = {"Bike", "Car", "Panzer"}
VEHICLE_MODELS = {"Aerondight", "Alvarado", "Basilisk", "Bratsk", "Colby", "Columbus", "Cortes", "Deleon", "Emperor", "Galena",
                  "GalenaNomad", "Hearse", "Hozuki", "Kusanagi", "Mackinaw", "Maimai", "Merrimac", "Octant", "Riptide", "Shion",
                  "Supron", "Thrax", "Turbo", "Type66", "Zeya"}


def used_cells(model: str) -> set[tuple[int, int]]:
    cells = set()
    for p in gen.MODELS[model]:
        for x0, y0, w, h in cuboid_faces(p.u, p.v, p.w, p.h, p.d).values():
            cells |= {(x0 + i, y0 + j) for i in range(w) for j in range(h)}
    return cells


def generate(monkeypatch, tmp: Path) -> dict[str, bytes]:
    monkeypatch.setattr(gen, "ASSETS", tmp / "assets")
    monkeypatch.setattr(gen, "VEHICLE_MAP", tmp / "vehicle_map")
    monkeypatch.setattr(gen, "ART", tmp / "art")
    gen.main()
    return {p.relative_to(tmp).as_posix(): p.read_bytes() for p in sorted(tmp.rglob("*")) if p.is_file()}


def test_two_runs_are_byte_identical_and_match_the_committed_files(monkeypatch, tmp_path):
    first, second = generate(monkeypatch, tmp_path / "a"), generate(monkeypatch, tmp_path / "b")
    assert first == second
    for rel, data in first.items():
        top, rest = rel.split("/", 1)
        committed = {"assets": RESOURCES / "assets" / "cyberblock", "vehicle_map": VEHICLE_MAP, "art": ROOT / "art"}[top] / rest
        assert committed.read_bytes() == data, f"{rel} differs from the committed file: run art/gen_vehicle_art.py and commit"
    shipped = {p.name for p in VEHICLE.glob("*")} | {p.name for p in VEHICLE_MAP.glob("*")}
    assert shipped == {Path(rel).name for rel in first if not rel.startswith("art/")}, "stale files in vehicle/ or vehicle_map/"


def test_the_twelve_models_of_the_plan():
    assert list(gen.MODELS) == MODELS and set(gen.DEFAULT_COLOUR) == set(MODELS)


def test_the_documented_uv_table_is_the_one_the_script_paints():
    assert gen.documented_layout() == gen.MODELS


@pytest.mark.parametrize("model", MODELS)
def test_regions_fit_the_texture_and_never_overlap(model):
    seen: dict[tuple[int, int], str] = {}
    for p in gen.MODELS[model]:
        u_end, v_end = p.u + 2 * (p.w + p.d), p.v + p.d + p.h
        assert u_end <= 128 and v_end <= 128, (model, p.name)
        for x0, y0, w, h in cuboid_faces(p.u, p.v, p.w, p.h, p.d).values():
            for cell in ((x0 + i, y0 + j) for i in range(w) for j in range(h)):
                assert cell not in seen, (model, p.name, seen.get(cell))
                seen[cell] = p.name
        for x, y, z in p.at:
            assert y + p.h <= 0, (model, p.name, "a box below the ground")


@pytest.mark.parametrize("model", MODELS)
def test_paint_is_opaque_greyscale_and_details_stay_on_the_model(model):
    cells = used_cells(model)
    paint = Image.open(VEHICLE / f"{model}.png")
    detail = Image.open(VEHICLE / f"{model}_detail.png")
    assert paint.size == detail.size == (128, 128) and paint.mode == detail.mode == "RGBA"
    for y in range(128):
        for x in range(128):
            r, g, b, a = paint.getpixel((x, y))
            if (x, y) in cells:
                assert a == 255 and r == g == b, (model, x, y)
            else:
                assert a == 0 and detail.getpixel((x, y))[3] == 0, (model, x, y)
    assert any(detail.getpixel(c)[3] for c in cells), model


@pytest.mark.parametrize("model", MODELS)
def test_only_the_emergency_vehicles_flash_and_only_their_light_bar(model):
    a, b = VEHICLE / f"{model}_lights_a.png", VEHICLE / f"{model}_lights_b.png"
    flashes = model in ("police_cruiser", "ncpd_aerodyne", "trauma_aerodyne")
    assert a.is_file() == b.is_file() == flashes
    if not flashes:
        return
    bar = next(p for p in gen.MODELS[model] if p.name == "light_bar")
    bar_cells = {(x0 + i, y0 + j) for x0, y0, w, h in cuboid_faces(bar.u, bar.v, bar.w, bar.h, bar.d).values()
                 for i in range(w) for j in range(h)}
    lit_a = {(x, y) for x in range(128) for y in range(128) if Image.open(a).getpixel((x, y))[3]}
    lit_b = {(x, y) for x in range(128) for y in range(128) if Image.open(b).getpixel((x, y))[3]}
    assert lit_a and lit_b and not lit_a & lit_b and (lit_a | lit_b) <= bar_cells


# --- vehicle_map -------------------------------------------------------------------------------------------------------------
@pytest.fixture(scope="module")
def rows() -> dict[str, dict]:
    return {p.stem: json.loads(p.read_text("utf-8")) for p in sorted(VEHICLE_MAP.glob("*.json"))}


def values(row: dict, key: str) -> set[str]:
    v = row.get(key)
    return set() if v is None else {v} if isinstance(v, str) else set(v)


def resolve(rows: dict[str, dict], vc: str | None, faction: str | None = None, col: str | None = None) -> tuple[str, str]:
    """The reference rule of the schema description: priority, then match keys, then the lowest id; `col` unless keepColour."""
    tags = {"vc": vc, "type": vc.split("/")[0] if vc else None, "faction": faction}
    fits = [(-row.get("priority", 0), -sum(k in row for k in tags), name) for name, row in sorted(rows.items())
            if all(tags[k] in values(row, k) for k in tags if k in row)]
    row = rows[min(fits)[2]]
    return row["model"], row["colour"] if row.get("keepColour") or col is None else col


def test_rows_use_2_31_names_and_shipped_models(rows):
    for name, row in rows.items():
        assert (VEHICLE / f"{row['model']}.png").is_file(), name
        assert values(row, "type") <= VEHICLE_TYPES, name
        for vc in values(row, "vc"):
            t, m = vc.split("/")
            assert t in VEHICLE_TYPES and m in VEHICLE_MODELS, (name, vc)


@pytest.mark.parametrize("vc, faction, col, want", [
    ("Car/Turbo", None, None, ("sports_car", "#c23b3b")),
    ("Car/Turbo", None, "#102030", ("sports_car", "#102030")),        # the host's colour template wins
    ("Car/Cortes", None, None, ("sedan", "#8a8f99")),
    ("Car/Cortes", "NCPD", "#aabbcc", ("police_cruiser", "#1f2c4a")),  # a livery keeps its colour
    ("Car/Colby", None, None, ("pickup", "#6d4f3a")),
    ("Car/Supron", None, None, ("van", "#d9d9d4")),
    ("Car/Bratsk", None, None, ("truck", "#b88a3a")),
    ("Car/Zeya", None, None, ("delivery_truck", "#d8d2c4")),
    ("Car/SomethingNew", None, None, ("sedan", "#8a8f99")),          # unknown model: the Car class row
    ("Bike/Kusanagi", None, None, ("bike", "#3a3a44")),
    ("Panzer/Basilisk", None, None, ("armoured_van", "#3b4136")),
    ("Panzer/Basilisk", "Militech", "#ffffff", ("armoured_van", "#3b4136")),
    ("Car/Emperor", "TraumaTeam", None, ("trauma_aerodyne", "#e8ecef")),
    ("Hover/Thing", None, None, ("sedan", "#8a8f99")),               # unknown class: the default row
    (None, None, None, ("sedan", "#8a8f99")),
])
def test_resolution(rows, vc, faction, col, want):
    assert resolve(rows, vc, faction, col) == want


def test_every_car_model_of_2_31_has_a_row(rows):
    mapped = {m for row in rows.values() for vc in values(row, "vc") for t, m in [vc.split("/")] if t == "Car"}
    assert mapped == VEHICLE_MODELS - {"Basilisk", "Kusanagi"}  # the tank (Panzer) and the bike (Bike) go by their type rows


def test_content_check_passes_the_defaults_and_refuses_bad_rows(tmp_path):
    result = content_check.check([content_check.DEFAULT_DATA])
    assert result.ok and "vehicle_map" in result.kinds, result.problems
    pack = tmp_path / "data" / "friend" / "cyberblock" / "vehicle_map"
    pack.mkdir(parents=True)
    bad = {
        "model": {"schema": 1, "model": "tank", "colour": "#112233"},
        "colour": {"schema": 1, "model": "sedan", "colour": "red"},
        "nocolour": {"schema": 1, "model": "sedan"},
        "vc": {"schema": 1, "model": "sedan", "colour": "#112233", "vc": "Turbo"},
        "keep": {"schema": 1, "model": "sedan", "colour": "#112233", "keepColour": "yes"},
        "extra": {"schema": 1, "model": "sedan", "colour": "#112233", "record": "Vehicle.v_sport"},
    }
    for name, data in bad.items():
        (pack / f"{name}.json").write_text(json.dumps(data))
    (pack / "good.json").write_text(json.dumps({"schema": 1, "model": "taxi", "colour": "#e3b13a", "vc": ["Car/Thrax", "Car/Emperor"],
                                                "faction": "Unaffiliated", "priority": 15}))
    problems = content_check.check([content_check.data_root(tmp_path)]).problems
    for name in bad:
        assert any(f"/{name}.json:" in p for p in problems), (name, problems)
    assert not any("/good.json:" in p for p in problems), problems
    shutil.rmtree(tmp_path)
