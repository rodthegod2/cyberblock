"""Megaplan 8-A1: the Phase 8 content defaults (event, spawn_palette, heat_tier, match_mode) agree with their schemas, with each other and with the Phase 0
verdicts; the generator matches what is committed; the schemas refuse malformed files."""
import importlib.util
import json
import sys
from pathlib import Path

import pytest

from tools import content_check

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / "art"
DATA = ROOT / "mc" / "src" / "main" / "resources" / "data" / "cyberblock" / "cyberblock"
SCHEMAS = ROOT / "shared" / "content-schemas"
KINDS = ["event", "spawn_palette", "heat_tier", "match_mode"]
EVENT_TYPES = {"blackout", "emp", "airstrike", "traffic_jam", "supply_drop", "weather", "outbreak"}
# the attitude groups of docs/notes/phase0-feasibility.md (e) that a spawn or a team can stand in
FACTIONS = {"tygerClaws", "maelstrom", "valentinos", "6thStreet", "animals", "voodooBoys", "scavenger", "wraiths", "aldecaldos", "kangtao", "kurtz_army",
            "arasaka", "militech", "police", "traumateam"}
# vanilla entity ids and items the defaults use (all long-standing ids)
MOBS = {"zombie", "skeleton", "creeper", "spider", "pillager", "vindicator", "ravager", "enderman", "shulker", "witch", "piglin_brute", "vex"}
ITEMS = {"golden_apple", "arrow"}
GAME_SLANG = ("choom", "preem", "gonk", "nova", "wakako", "silverhand", "samurai", "braindance")  # words of the game's own text: ours is original


def read(path: Path) -> dict:
    return json.loads(path.read_text("utf-8"))


def entries(kind: str) -> dict[str, dict]:
    return {p.stem: read(p) for p in sorted((DATA / kind).glob("*.json"))}


@pytest.fixture(scope="module")
def gen():
    sys.path.insert(0, str(ART))
    spec = importlib.util.spec_from_file_location("gen_mode_data", ART / "gen_mode_data.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def test_the_generator_matches_the_committed_files_and_is_deterministic(gen):
    first = gen.files()
    assert first == gen.files()
    for rel, data in first.items():
        assert (DATA / rel).read_bytes() == data, f"{rel} differs from the committed file: run art/gen_mode_data.py and commit"
    on_disk = {f"{kind}/{p.name}" for kind in KINDS for p in (DATA / kind).glob("*.json")}
    assert on_disk == set(first), "a stray file is in a Phase 8 content folder"


def test_content_check_passes_on_the_shipped_defaults():
    result = content_check.check([content_check.DEFAULT_DATA])
    assert result.ok, result.problems
    assert set(KINDS) <= result.kinds


def test_every_schema_is_present():
    for kind in KINDS:
        assert (SCHEMAS / f"{kind}.schema.json").is_file(), kind


def test_the_texts_are_original_and_short():
    for kind in KINDS:
        for name, doc in entries(kind).items():
            text = json.dumps(doc).lower()
            assert not any(word in text for word in GAME_SLANG), (kind, name)
            assert len(doc["description"]) <= 160 and doc["name"].strip(), (kind, name)


# --- spawn_palette --------------------------------------------------------------------------------------------------------------------
def test_the_palette_has_minecraft_mobs_and_night_city_enemies_of_every_class():
    palette = entries("spawn_palette")
    assert len(palette) >= 20
    by_kind = {k: [d for d in palette.values() if d["kind"] == k] for k in ("minecraft", "night_city")}
    assert len(by_kind["minecraft"]) >= 8 and len(by_kind["night_city"]) >= 12
    assert {d["rec"] for d in by_kind["night_city"]} == {"ganger", "ncpd", "maxtac", "drone"}
    assert {d["group"] for d in palette.values()} == {"gang", "law", "machine", "monster"}
    assert any(d["mob"] == "minecraft:shulker" for d in by_kind["minecraft"]), "turrets are Minecraft-side only (Phase 0 d)"
    gangs = {d["faction"] for d in by_kind["night_city"] if d["rec"] == "ganger"}
    assert {"tygerClaws", "maelstrom", "valentinos", "6thStreet", "animals", "voodooBoys", "scavenger", "wraiths"} <= gangs


def test_every_entry_names_a_known_mob_or_faction_and_a_squad_stand_in():
    for name, d in entries("spawn_palette").items():
        assert 1 <= d["count"] <= 8 and 1 <= d["cost"] <= 60, name
        if d["kind"] == "minecraft":
            assert d["mob"].removeprefix("minecraft:") in MOBS, name
            assert not {"rec", "faction", "squad"} & set(d), name
        else:
            assert d["faction"] in FACTIONS, name
            assert d["squad"]["mob"].removeprefix("minecraft:") in MOBS, name
            assert not {"mob"} & set(d), name
            assert d["rec"] != "turret"


def test_dearer_spawns_cost_more_per_figure_up_the_ladder():
    palette = entries("spawn_palette")
    per = {n: d["cost"] / d["count"] for n, d in palette.items()}
    assert per["maxtac_squad"] > per["ncpd_enforcers"] > per["ncpd_patrol"]
    assert per["maxtac_squad"] == max(per.values())
    assert per["cyberpsycho"] > per["gang_tyger_claws"]
    # a bigger spawn of the same kind of thing is never cheaper
    assert palette["pillager_patrol"]["cost"] > palette["vindicator_brute"]["cost"]


# --- event ------------------------------------------------------------------------------------------------------------------------------
def test_the_seven_events_of_the_plan_each_have_one_effect():
    events = entries("event")
    assert {e["effect"]["type"] for e in events.values()} == EVENT_TYPES and len(events) == 7
    for name, e in events.items():
        assert e["warning_s"] >= 3, name  # megaplan 8-O1: never less than the 3 s warning
        assert 1 <= e["cost"] <= 100 and e["radius"] >= 4, name


def test_events_refer_to_things_that_exist():
    palette = entries("spawn_palette")
    for name, e in entries("event").items():
        fx = e["effect"]
        if fx["type"] == "outbreak":
            assert fx["spawn"].removeprefix("cyberblock:") in palette, name
            assert palette[fx["spawn"].removeprefix("cyberblock:")]["hostile"], name
        if fx["type"] == "supply_drop":
            assert {i["item"].removeprefix("minecraft:") for i in fx["items"]} <= ITEMS, name
        if e["night_city"]:
            assert fx["type"] in ("blackout", "emp", "traffic_jam", "outbreak"), name  # the ones a host's game can really play


def test_the_airstrike_is_the_dearest_event_and_the_weather_the_cheapest():
    cost = {e["effect"]["type"]: e["cost"] for e in entries("event").values()}
    assert max(cost, key=cost.get) == "airstrike" and min(cost, key=cost.get) == "weather"


# --- heat_tier --------------------------------------------------------------------------------------------------------------------------
def test_five_heat_tiers_climb_in_every_number():
    tiers = [entries("heat_tier")[f"tier_{n}"] for n in range(1, 6)]
    assert [t["tier"] for t in tiers] == [1, 2, 3, 4, 5]
    assert sorted(entries("heat_tier")) == [f"tier_{n}" for n in range(1, 6)]
    enter = [t["enter"] for t in tiers]
    assert enter == sorted(set(enter)) and enter[-1] <= 100
    for n, t in enumerate(tiers):
        assert t["exit"] < t["enter"], t["name"]
        if n:
            assert t["exit"] >= tiers[n - 1]["enter"], t["name"]  # leaving a tier never skips down past the one below
    assert [t["response"]["max_alive"] for t in tiers] == sorted(t["response"]["max_alive"] for t in tiers)
    assert [t["decay_per_s"] for t in tiers] == sorted((t["decay_per_s"] for t in tiers), reverse=True)  # high heat lingers
    assert [t["host_wanted"] for t in tiers] == [1, 2, 3, 4, 5]


def test_each_tier_sends_what_the_plan_says():
    t = {n: entries("heat_tier")[f"tier_{n}"] for n in range(1, 6)}
    waves = {n: {w["entry"].removeprefix("cyberblock:") for w in t[n]["response"]["waves"]} for n in t}
    assert waves[1] == {"ncpd_patrol"} and t[1]["response"]["target"] == "area"
    assert t[2]["response"]["target"] == "loudest"
    assert "ncpd_enforcers" in waves[3] and "wyvern_drones" in waves[3]  # armoured NCPD and air units
    assert "maxtac_squad" in waves[4] and t[4]["response"]["target"] == "top_chaos" and t[4]["top_chaos_wanted"] == 5
    assert t[5]["response"]["target"] == "everyone"
    assert not any("maxtac_squad" in w for n, w in waves.items() if n < 4)
    assert all("top_chaos_wanted" not in t[n] for n in (1, 2, 3, 5))
    palette = entries("spawn_palette")
    assert all(w.removeprefix("cyberblock:") in palette for n in t for w in waves[n])


# --- match_mode --------------------------------------------------------------------------------------------------------------------------
def test_four_modes_with_consistent_bounds():
    modes = entries("match_mode")
    assert sorted(modes) == ["free_for_all", "gang_wars", "last_team_standing", "team_deathmatch"]
    for name, m in modes.items():
        assert m["teams"]["min"] <= m["teams"]["max"] <= len(m["team_palette"]), name  # a colour for every possible team
        assert m["team_size"]["min"] <= m["team_size"]["max"], name
        assert m["zone"]["min_radius"] <= m["zone"]["max_radius"], name
        assert len({p["colour"].lower() for p in m["team_palette"]}) == len(m["team_palette"]), name
        assert "spectator" in m["roles"] and m["pvp"], name
        assert m["time_limit_s"] >= 60 and m["score_limit"] >= 1, name


def test_the_modes_say_what_the_plan_says():
    m = entries("match_mode")
    gw = m["gang_wars"]
    assert gw["districts"] and gw["reputation"] and gw["capture_points"]["count"] >= 2 and gw["scoring"]["capture"] > 0
    assert {p["faction"] for p in gw["team_palette"]} <= FACTIONS and all("faction" in p for p in gw["team_palette"])
    assert "hacker" in gw["roles"] and "overlord" in gw["roles"]
    assert m["free_for_all"]["team_size"] == {"min": 1, "max": 1} and m["free_for_all"]["friendly_fire"]
    assert m["last_team_standing"]["respawn_s"] == 0 and m["last_team_standing"]["scoring"]["kill"] == 0
    assert m["team_deathmatch"]["scoring"]["kill"] >= 1 and not m["team_deathmatch"]["friendly_fire"]
    assert not any(d["reputation"] for n, d in m.items() if n != "gang_wars")  # only a war moves the factions
    assert not any("capture_points" in d for n, d in m.items() if n != "gang_wars")


# --- the schemas refuse bad files -----------------------------------------------------------------------------------------------------------
def test_the_schemas_refuse_malformed_files(tmp_path):
    spawn, event = entries("spawn_palette"), entries("event")
    tier, mode = entries("heat_tier")["tier_1"], entries("match_mode")["gang_wars"]
    ganger, zombie, outbreak = spawn["gang_maelstrom"], spawn["zombie_pack"], event["cyberpsychosis_outbreak"]
    bad = {
        "spawn_palette/no_squad": {k: v for k, v in ganger.items() if k != "squad"},
        "spawn_palette/mc_with_rec": {**zombie, "rec": "ganger"},
        "spawn_palette/nc_with_mob": {**ganger, "mob": "minecraft:zombie"},
        "spawn_palette/turret": {**ganger, "rec": "turret"},
        "spawn_palette/count": {**zombie, "count": 9},
        "spawn_palette/cost": {**zombie, "cost": 0},
        "spawn_palette/kind": {**zombie, "kind": "space"},
        "spawn_palette/faction": {**ganger, "faction": "no way"},
        "spawn_palette/mob": {**zombie, "mob": "Zombie"},
        "event/warning": {**event["blackout"], "warning_s": 2},
        "event/type": {**event["blackout"], "effect": {"type": "meteor", "seconds": 5}},
        "event/mixed": {**event["blackout"], "effect": {"type": "blackout", "seconds": 5, "power": 2}},
        "event/weather": {**event["weather"], "effect": {"type": "weather", "weather": "snow", "seconds": 60}},
        "event/unknown_spawn": {**outbreak, "effect": {"type": "outbreak", "spawn": "cyberblock:nobody", "count": 2}},
        "event/extra": {**event["emp"], "colour": "red"},
        "heat_tier/tier": {**tier, "tier": 6},
        "heat_tier/wanted": {**tier, "host_wanted": 6},
        "heat_tier/no_waves": {**tier, "response": {**tier["response"], "waves": []}},
        "heat_tier/target": {**tier, "response": {**tier["response"], "target": "nobody"}},
        "heat_tier/unknown_entry": {**tier, "response": {**tier["response"], "waves": [{"entry": "cyberblock:nobody", "every_s": 30}]}},
        "match_mode/colour": {**mode, "team_palette": [{"name": "A", "colour": "red"}, {"name": "B", "colour": "#000000"}]},
        "match_mode/role": {**mode, "roles": ["teammate", "referee"]},
        "match_mode/teams": {**mode, "teams": {"min": 1, "max": 4}},
        "match_mode/one_colour": {**mode, "team_palette": [{"name": "A", "colour": "#ffffff"}]},
        "match_mode/dupe_roles": {**mode, "roles": ["teammate", "teammate"]},
    }
    good = {"spawn_palette/good": ganger, "event/good": event["emp"], "heat_tier/good": tier, "match_mode/good": mode}
    pack = tmp_path / "data" / "friend" / "cyberblock"
    for rel, data in {**bad, **good}.items():
        path = pack / f"{rel}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data), encoding="utf-8")
    problems = content_check.check([tmp_path / "data"], reference_roots=[content_check.DEFAULT_DATA]).problems
    for rel in bad:
        assert any(f"{rel}.json:" in p for p in problems), (rel, problems)
    assert not any("/good.json:" in p for p in problems), problems


def test_the_ripperdoc_schema_accepts_the_netrunner_implant_megaplan_1_17_appends():
    schema = read(SCHEMAS / "ripperdoc.schema.json")
    allowed = schema["properties"]["implants"]["items"]["properties"]["implant"]["enum"]
    assert allowed[-2:] == ["firewall", "netrunner"]
