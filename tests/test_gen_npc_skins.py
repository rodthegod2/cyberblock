"""Megaplan 5-A2: the NPC skins are deterministic, well formed, one per look and gender, and the skin_map defaults resolve the
way the plan says (faction, role and archetype rows; female on slim; unknown gender on classic)."""
import json
import shutil
import sys
import zlib
from pathlib import Path

import pytest
from PIL import Image

from tools import content_check

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "art"))

import convert_slim  # noqa: E402
import gen_npc_skins as gen  # noqa: E402
from skin_layout import PARTS, faces  # noqa: E402

RESOURCES = ROOT / "mc" / "src" / "main" / "resources"
NPC = RESOURCES / "assets" / "cyberblock" / "textures" / "entity" / "npc"
SKIN_MAP = RESOURCES / "data" / "cyberblock" / "cyberblock" / "skin_map"

# 2.31 script enums (the installed scripts; NativeDB): gamedataAffiliation, gamedataVendorType, gamedataArchetypeType
AFFILIATIONS = {"AfterlifeMercs", "Aldecaldos", "Animals", "Arasaka", "Barghest", "Biotechnica", "CityCouncil", "Civilian",
                "Classified", "KangTao", "Maelstrom", "MaelstromAndroid", "Militech", "NCPD", "NUSA", "NetWatch", "News54", "OA",
                "RecordingAgency", "SSI", "Scavengers", "ScavengersAndroid", "SixthStreet", "SixthStreetAndroid",
                "SouthCalifornia", "TheMox", "TraumaTeam", "TygerClaws", "Unaffiliated", "UnaffiliatedCorpo", "Unknown",
                "Valentinos", "VoodooBoys", "Wraiths", "WraithsAndroid", "Zetatech"}
VENDOR_TYPES = {"Armorsmith", "Clothes", "Clothing", "Cyberware", "Drinks", "DropPoint", "Food", "GrilledFood", "Gunsmith", "Junk",
                "Kiosk", "Market", "Medical", "PackedFood", "RipperDoc", "SkillTrainer", "Tech", "TechJunk", "VendingMachine"}
CONTENT_ROLES = {"fixer", "bartender", "medic", "mechanic", "cyberpsycho"}
ARCHETYPES = {"AndroidMeleeT1", "AndroidMeleeT2", "AndroidRangedT2", "FastMeleeT2", "FastMeleeT3", "FastRangedT2", "FastRangedT3",
              "FastShotgunnerT2", "FastShotgunnerT3", "FastSniperT3", "FriendlyGenericRangedT3", "GenericMeleeT1", "GenericMeleeT2",
              "GenericRangedT1", "GenericRangedT2", "GenericRangedT3", "HeavyMeleeT2", "HeavyMeleeT3", "HeavyRangedT2",
              "HeavyRangedT3", "NetrunnerT1", "NetrunnerT2", "NetrunnerT3", "ShotgunnerT2", "ShotgunnerT3", "SniperT2", "TechieT2",
              "TechieT3"}
BRIEF = ["civ_corpo", "civ_street", "civ_worker", "civ_tourist", "civ_homeless", "tyger_claws", "valentinos", "maelstrom",
         "sixth_street", "animals", "scavengers", "voodoo_boys", "mox", "aldecaldos", "arasaka", "militech", "kang_tao", "ncpd_patrol",
         "ncpd_swat", "maxtac", "trauma_team", "netwatch", "ripperdoc", "vendor", "fixer", "bartender", "cyberpsycho"]


@pytest.fixture(scope="module")
def rows() -> dict[str, dict]:
    return {p.stem: json.loads(p.read_text("utf-8")) for p in sorted(SKIN_MAP.glob("*.json"))}


def generate(monkeypatch, tmp: Path) -> dict[str, bytes]:
    monkeypatch.setattr(gen, "ASSETS", tmp / "assets")
    monkeypatch.setattr(gen, "SKIN_MAP", tmp / "skin_map")
    monkeypatch.setattr(gen, "ART", tmp / "art")
    gen.main()
    return {p.relative_to(tmp).as_posix(): p.read_bytes() for p in sorted(tmp.rglob("*")) if p.is_file()}


def test_two_runs_are_byte_identical_and_match_the_committed_files(monkeypatch, tmp_path):
    first, second = generate(monkeypatch, tmp_path / "a"), generate(monkeypatch, tmp_path / "b")
    assert first == second
    for rel, data in first.items():
        top, rest = rel.split("/", 1)
        committed = {"assets": RESOURCES / "assets" / "cyberblock", "skin_map": SKIN_MAP, "art": ROOT / "art"}[top] / rest
        assert committed.read_bytes() == data, f"{rel} differs from the committed file: run art/gen_npc_skins.py and commit"
    shipped = {p.name for p in NPC.glob("*.png")} | {p.name for p in SKIN_MAP.glob("*.json")}
    assert shipped == {Path(rel).name for rel in first if not rel.startswith("art/")}, "stale files in npc/ or skin_map/"


def test_every_look_of_the_brief_exists_in_both_genders():
    assert [g.id for g in gen.GROUPS] == BRIEF
    for gid in BRIEF:
        assert (NPC / f"{gid}_m.png").is_file() and (NPC / f"{gid}_f.png").is_file(), gid


@pytest.mark.parametrize("gid", BRIEF)
def test_skins_are_well_formed_and_on_the_right_model(gid):
    for suffix, slim in (("m", False), ("f", True)):
        im = Image.open(NPC / f"{gid}_{suffix}.png")
        assert im.size == (64, 64) and im.mode == "RGBA"
        assert convert_slim.layout(im) == ("slim" if slim else "classic")
        used = set()
        for part in PARTS:
            for layer in ("base", "overlay"):
                for x0, y0, w, h in faces(part, layer, slim).values():
                    cells = {(x0 + i, y0 + j) for i in range(w) for j in range(h)}
                    used |= cells
                    if layer == "base":
                        assert all(im.getpixel(c)[3] == 255 for c in cells), (gid, suffix, part)
        assert all(im.getpixel((x, y))[3] == 0 for x in range(64) for y in range(64) if (x, y) not in used), (gid, suffix)
        assert {im.getpixel((x, y))[3] for x in range(64) for y in range(64)} <= {0, 255}  # no half-transparent pixels


def test_every_skin_is_different():
    digests = {zlib.crc32(Image.open(p).tobytes()): p.name for p in sorted(NPC.glob("*.png"))}
    assert len(digests) == len(list(NPC.glob("*.png")))


# --- skin_map ----------------------------------------------------------------------------------------------------------------
def values(row: dict, key: str) -> set[str]:
    v = row.get(key)
    return set() if v is None else {v} if isinstance(v, str) else set(v)


def test_rows_point_at_shipped_skins_on_the_matching_model(rows):
    for name, row in rows.items():
        ns, path = row["texture"].split(":", 1)
        file = RESOURCES / "assets" / ns / path
        assert file.is_file(), (name, row["texture"])
        assert convert_slim.layout(convert_slim.load(file)) == row["model"], name
        if "gender" not in row and "cyber" not in row:
            assert row["model"] == "classic", f"{name}: unknown gender is drawn on the classic model"
        if row.get("gender") == "female":
            assert row["model"] == "slim", name


def test_match_values_are_2_31_names_or_content_roles(rows):
    for name, row in rows.items():
        assert values(row, "faction") <= AFFILIATIONS, name
        assert values(row, "role") <= VENDOR_TYPES | CONTENT_ROLES, name
        assert values(row, "archetype") <= ARCHETYPES, name


def test_the_cyber_skins_and_the_fallbacks_are_there(rows):
    assert rows["cyber_male"] == {"schema": 1, "texture": "cyberblock:textures/entity/cyber_skin/v_male.png", "model": "classic", "cyber": "male"}
    assert rows["cyber_female"] == {"schema": 1, "texture": "cyberblock:textures/entity/cyber_skin/v_female.png", "model": "slim", "cyber": "female"}
    assert set(rows["default"]) == {"schema", "texture", "model"}
    for g in gen.GROUPS:  # every look has a row without gender (unknown gender) besides its female row
        assert "gender" not in rows[g.id] and rows[f"{g.id}_female"]["gender"] == "female", g.id


def resolve(rows: dict[str, dict], npc: dict, npc_id: int = 0) -> str:
    """The reference matching rule of the schema description: priority, then match keys, then a stable pick by NPC id."""
    keys = ("role", "faction", "gender", "archetype")
    fits = []
    for name, row in sorted(rows.items()):
        if "cyber" in row:
            continue
        if all(npc.get(k) in values(row, k) for k in keys if k in row):
            fits.append((row.get("priority", 0), sum(k in row for k in keys), name))
    best = max(f[:2] for f in fits)
    tied = [f[2] for f in fits if f[:2] == best]
    return Path(rows[tied[npc_id % len(tied)]]["texture"]).stem


@pytest.mark.parametrize("npc, skin", [
    ({"faction": "TygerClaws", "gender": "male"}, "tyger_claws_m"),
    ({"faction": "TygerClaws", "gender": "female"}, "tyger_claws_f"),
    ({"faction": "TygerClaws"}, "tyger_claws_m"),                     # unknown gender: the classic row
    ({"faction": "MaelstromAndroid", "gender": "female"}, "maelstrom_f"),
    ({"faction": "Wraiths"}, "scavengers_m"),
    ({"faction": "NCPD", "gender": "male", "archetype": "GenericRangedT1"}, "ncpd_patrol_m"),
    ({"faction": "NCPD", "gender": "female", "archetype": "HeavyRangedT2"}, "ncpd_swat_f"),
    ({"faction": "NCPD", "archetype": "HeavyRangedT3"}, "maxtac_m"),
    ({"faction": "Civilian", "gender": "female", "role": "RipperDoc"}, "ripperdoc_f"),
    ({"faction": "Valentinos", "role": "Drinks"}, "bartender_m"),
    ({"faction": "Civilian", "role": "Clothes", "gender": "male"}, "vendor_m"),
    ({"role": "fixer", "gender": "female"}, "fixer_f"),
    ({"role": "mechanic"}, "civ_worker_m"),
    ({"faction": "Maelstrom", "role": "cyberpsycho"}, "cyberpsycho_m"),
    ({"faction": "Unknown"}, "civ_street_m"),
    ({"faction": "SouthCalifornia", "gender": "female"}, "civ_street_f"),
    ({}, "civ_street_m"),
])
def test_resolution(rows, npc, skin):
    assert resolve(rows, npc) == skin


def test_civilians_get_a_mix_of_skins(rows):
    picks = {resolve(rows, {"faction": "Civilian", "gender": "male"}, n) for n in range(20)}
    assert picks == {"civ_corpo_m", "civ_street_m", "civ_worker_m", "civ_tourist_m", "civ_homeless_m"}


def test_content_check_passes_the_defaults_and_refuses_bad_rows(tmp_path):
    result = content_check.check([content_check.DEFAULT_DATA])
    assert result.ok and "skin_map" in result.kinds, result.problems
    pack = tmp_path / "data" / "friend" / "cyberblock" / "skin_map"
    pack.mkdir(parents=True)
    tex = "friend:textures/entity/npc/x.png"
    bad = {
        "nomodel": {"schema": 1, "texture": tex},
        "model": {"schema": 1, "texture": tex, "model": "wide"},
        "texture": {"schema": 1, "texture": "friend:npc/x", "model": "classic"},
        "gender": {"schema": 1, "texture": tex, "model": "classic", "gender": "unknown"},
        "cybermatch": {"schema": 1, "texture": tex, "model": "slim", "cyber": "female", "faction": "NCPD"},
        "emptylist": {"schema": 1, "texture": tex, "model": "classic", "faction": []},
        "badname": {"schema": 1, "texture": tex, "model": "classic", "role": "Ripper Doc"},
        "extra": {"schema": 1, "texture": tex, "model": "classic", "colour": "#ffffff"},
    }
    for name, data in bad.items():
        (pack / f"{name}.json").write_text(json.dumps(data))
    (pack / "good.json").write_text(json.dumps({"schema": 1, "texture": tex, "model": "slim", "faction": ["TheMox", "Valentinos"],
                                                "gender": "female", "priority": 5}))
    problems = content_check.check([content_check.data_root(tmp_path)]).problems
    for name in bad:
        assert any(f"/{name}.json:" in p for p in problems), (name, problems)
    assert not any("/good.json:" in p for p in problems), problems
    shutil.rmtree(tmp_path)
