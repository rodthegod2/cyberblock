"""Original Phase 8 content defaults: the `event`, `spawn_palette`, `heat_tier` and `match_mode` kinds (megaplan 8-A1, 1.7).

Run from anywhere:  uv run python art/gen_mode_data.py

Writes one JSON file per entry under mc/src/main/resources/data/cyberblock/cyberblock/<kind>/<name>.json (tab indented, the way the other
defaults are) and the netrunner implant's recipe under mc/src/main/resources/data/cyberblock/recipe/, and nothing else. The formats are the schemas in shared/content-schemas/{event,spawn_palette,heat_tier,match_mode}.schema.json,
which say what each field means; tools/content_check.py validates the result and tests/test_phase8_data.py keeps these tables, the files and
the schemas in step. Deterministic: two runs give byte-identical files. All names and texts are original; the Night City classes, factions and
the Minecraft stand-ins follow docs/notes/phase0-feasibility.md (d) and (e): turrets are Minecraft-side only.

The tables below are the whole default content:
  SPAWNS   23 palette entries: 8 Minecraft (monsters and a shulker turret) and 15 Night City (eight gangs, the Barghest, a cyberpsycho,
           NCPD patrol and enforcers, a Max-Tac squad, two kinds of drone), each Night City one with its Minecraft squad stand-in.
  EVENTS   the seven Overlord events of megaplan 8-O1.
  HEAT     the five tiers of megaplan 8-T1 (1 more patrols, 2 chase the loudest, 3 heavy units, 4 Max-Tac hunts the top chaos player, 5 all out).
  MODES    four match modes: team deathmatch, free for all, Gang Wars, last team standing.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "mc" / "src" / "main" / "resources" / "data" / "cyberblock" / "cyberblock"
NS = "cyberblock"


def squad(mob: str, count: int) -> dict:
    return {"mob": f"minecraft:{mob}", "count": count}


def mc(name: str, group: str, cost: int, mob: str, count: int, text: str, title: str, **extra) -> tuple[str, dict]:
    """A Minecraft palette entry."""
    return name, {"name": title, "description": text, "group": group, "kind": "minecraft", "cost": cost, "count": count, "hostile": True,
                  "mob": f"minecraft:{mob}", **extra}


def nc(name: str, title: str, group: str, cost: int, rec: str, faction: str, count: int, stand_in: dict, text: str, **extra) -> tuple[str, dict]:
    """A Night City palette entry with its Minecraft squad."""
    return name, {"name": title, "description": text, "group": group, "kind": "night_city", "cost": cost, "count": count, "hostile": True,
                  "rec": rec, "faction": faction, **extra, "squad": stand_in}


# Every mob here works in the replica at any hour: the overworld of the cyberblock:nightcity world type (time of day follows the host,
# open sky) zombifies piglins and hoglins and burns the daylight undead, so none of those is used (wither skeletons are fire immune); every
# one attacks players unprovoked in any light (no enderman, no spider); none explodes or breaks blocks (no creeper, ghast, breeze or blaze:
# an Overlord's spawn must never grief a base or send an explosion to a host). tests/test_phase8_data.py holds these rules.
SPAWNS: list[tuple[str, dict]] = [
    mc("zombie_pack", "monster", 6, "husk", 3, "Three dry, stubborn walkers that shrug off the sun. Cheap, and they never stop coming.", title="Walker Pack",
       cooldown_s=10),
    mc("magma_pair", "monster", 7, "magma_cube", 2, "Two molten blobs that bounce in and split in two when you hit them.", title="Molten Blobs", cooldown_s=15),
    mc("crawler_swarm", "monster", 7, "endermite", 4, "Four quick little crawlers that go for the ankles.", title="Crawler Swarm", cooldown_s=15),
    mc("pillager_patrol", "monster", 12, "pillager", 3, "A crossbow squad that shoots first and never asks.", title="Crossbow Squad", cooldown_s=20),
    mc("vindicator_brute", "monster", 5, "vindicator", 1, "One angry fellow with an axe and nothing to lose.", title="Axe Brute", cooldown_s=10),
    mc("ravager", "monster", 14, "ravager", 1, "A horned battering ram on four legs. Clear the street.", title="Street Ram", cooldown_s=30),
    mc("zoglin_charger", "monster", 10, "zoglin", 1, "A rotten tusked brute that charges anything that moves, the Overlord's own included.",
       title="Rot Tusker", cooldown_s=25),
    mc("sentry_turret", "machine", 10, "shulker", 1, "A fixed gun nest that tracks and fires. It cannot walk and it never blinks.", title="Sentry Nest", cooldown_s=20),
    nc("gang_tyger_claws", "Tyger Claws Crew", "gang", 9, "ganger", "tygerClaws", 3, squad("pillager", 3),
       "Three blade-and-gun gangers from the Japantown side of the bridge.", cooldown_s=15),
    nc("gang_maelstrom", "Maelstrom Crew", "gang", 9, "ganger", "maelstrom", 3, squad("vindicator", 2),
       "Three chrome-heavy gangers who treat their implants as a dare.", cooldown_s=15),
    nc("gang_valentinos", "Valentinos Crew", "gang", 9, "ganger", "valentinos", 3, squad("pillager", 3),
       "Three gangers in gold and red, loud about loyalty and quick on the draw.", cooldown_s=15),
    nc("gang_sixth_street", "6th Street Crew", "gang", 9, "ganger", "6thStreet", 3, squad("pillager", 3),
       "Three flag-wearing locals who defend their blocks with real guns.", cooldown_s=15),
    nc("gang_animals", "Animals Crew", "gang", 10, "ganger", "animals", 2, squad("vindicator", 2),
       "Two huge, muscled bruisers who prefer to get close.", cooldown_s=15),
    nc("gang_voodoo_boys", "Voodoo Boys Crew", "gang", 11, "ganger", "voodooBoys", 3, squad("witch", 3),
       "Three net-bound mystics who hit you in ways the guns do not explain.", cooldown_s=20),
    nc("gang_scavengers", "Scavenger Pack", "gang", 8, "ganger", "scavenger", 3, squad("husk", 3),
       "Three desperate salvagers who swing whatever they find.", cooldown_s=15),
    nc("gang_wraiths", "Wraith Raiders", "gang", 9, "ganger", "wraiths", 3, squad("pillager", 3),
       "Three desert raiders who come in fast cars and leave in the same.", cooldown_s=15),
    nc("merc_barghest", "Barghest Soldiers", "gang", 14, "ganger", "kurtz_army", 2, squad("vindicator", 2),
       "Two disciplined mercenaries with real kit and no sense of humour.", cooldown_s=25),
    nc("cyberpsycho", "Cyberpsycho", "gang", 15, "ganger", "scavenger", 1, squad("vindicator", 1),
       "A lone chrome casualty with a very short temper and a very long reach.", cooldown_s=45),
    nc("ncpd_patrol", "NCPD Patrol", "law", 8, "ncpd", "police", 2, squad("pillager", 2),
       "Two patrol officers who open with a warning and close with a volley.", cooldown_s=15),
    nc("ncpd_enforcers", "NCPD Enforcers", "law", 14, "ncpd", "police", 2, squad("vindicator", 2),
       "Two armoured enforcers in heavy vests, here to end the argument.", cooldown_s=25),
    nc("maxtac_squad", "Max-Tac Squad", "law", 30, "maxtac", "police", 2, squad("wither_skeleton", 2),
       "Two elite response troopers. If they have come, the night went badly.", cooldown_s=60),
    nc("wyvern_drones", "Patrol Drones", "machine", 12, "drone", "police", 2, squad("vex", 2),
       "Two police drones that hover, scan and shoot from above.", cooldown_s=20),
    nc("octant_drone", "Corpo Drone", "machine", 14, "drone", "arasaka", 1, squad("vex", 1),
       "A corporate security drone with a sharper eye than a patrol one.", cooldown_s=25),
]

# the events of megaplan 8-O1; every number is a default a datapack can change
EVENTS: list[tuple[str, dict]] = [
    ("blackout", {"name": "Blackout", "description": "The lights of the area go dark. Cameras and streetlamps fail, and hunters see the dark better than prey.",
                  "cost": 25, "warning_s": 5, "cooldown_s": 90, "radius": 48, "scales_with_intensity": True, "night_city": True,
                  "effect": {"type": "blackout", "seconds": 40}}),
    ("emp", {"name": "EMP Pulse", "description": "A silent pulse. Devices stop answering and active implants go quiet for a while.",
             "cost": 30, "warning_s": 5, "cooldown_s": 120, "radius": 24, "scales_with_intensity": True, "night_city": True,
             "effect": {"type": "emp", "seconds": 12, "cyberware_off": True}}),
    ("airstrike", {"name": "Airstrike", "description": "A marked patch of ground is hit from above. The marker burns for a few seconds first.",
                   "cost": 40, "warning_s": 6, "cooldown_s": 150, "radius": 20, "scales_with_intensity": True, "night_city": True,
                   "effect": {"type": "airstrike", "strikes": 8, "spread": 12, "power": 2.5, "interval_ticks": 8}}),
    ("traffic_jam", {"name": "Traffic Jam", "description": "Every car in the area stops dead. Nobody is going anywhere, in a car or around one.",
                     "cost": 20, "warning_s": 4, "cooldown_s": 90, "radius": 64, "scales_with_intensity": False, "night_city": True,
                     "effect": {"type": "traffic_jam", "seconds": 60}}),
    # a token prize (25 eddies, arrows and bread: about 50 at the hub's prices), far below the cheapest gig: no Threat Point eddie farm
    ("supply_drop", {"name": "Supply Drop", "description": "A crate drops from the sky with a few eddies and supplies. The first fighter to reach it keeps it.",
                     "cost": 15, "warning_s": 8, "cooldown_s": 120, "radius": 8, "scales_with_intensity": False, "night_city": False,
                     "effect": {"type": "supply_drop", "eddies": 25,
                                "items": [{"item": "minecraft:arrow", "count": 16}, {"item": "minecraft:bread", "count": 2}]}}),
    ("weather", {"name": "Storm Front", "description": "A thunderstorm rolls over the match area. It is loud and wet, and nobody hears you coming.",
                 "cost": 10, "warning_s": 10, "cooldown_s": 180, "radius": 128, "scales_with_intensity": False, "night_city": False,
                 "effect": {"type": "weather", "weather": "thunder", "seconds": 180}}),
    ("cyberpsychosis_outbreak", {"name": "Cyberpsychosis Outbreak", "description": "Chrome casualties break out of the crowd and rush the nearest people.",
                                 "cost": 35, "warning_s": 6, "cooldown_s": 180, "radius": 32, "scales_with_intensity": True, "night_city": True,
                                 "effect": {"type": "outbreak", "spawn": f"{NS}:cyberpsycho", "count": 2}}),
]


def tier(n: int, title: str, text: str, enter: int, leave: int, decay: float, grace: int, wanted: int, target: str, alive: int,
         waves: list[tuple[str, int]], top: int | None = None) -> tuple[str, dict]:
    doc = {"tier": n, "name": title, "description": text, "enter": enter, "exit": leave, "decay_per_s": decay, "grace_s": grace, "host_wanted": wanted}
    if top is not None:
        doc["top_chaos_wanted"] = top
    doc["response"] = {"target": target, "max_alive": alive, "waves": [{"entry": f"{NS}:{e}", "every_s": s} for e, s in waves]}
    return f"tier_{n}", doc


HEAT: list[tuple[str, dict]] = [
    tier(1, "Noticed", "The city has noticed the noise. More patrols walk the streets near you.", 15, 10, 0.40, 10, 1, "area", 4,
         [("ncpd_patrol", 90)]),
    tier(2, "Pursuit", "The patrols stop strolling. They chase the loudest people in the area.", 35, 30, 0.35, 15, 2, "loudest", 6,
         [("ncpd_patrol", 45)]),
    tier(3, "Heavy Response", "Armoured enforcers and drones join the chase. Cover is a good idea.", 55, 50, 0.30, 20, 3, "loudest", 8,
         [("ncpd_enforcers", 60), ("wyvern_drones", 90)]),
    tier(4, "Max-Tac Hunt", "The elite squad is sent after the one who caused the most chaos.", 75, 70, 0.25, 25, 4, "top_chaos", 8,
         [("maxtac_squad", 120), ("ncpd_enforcers", 60)], top=5),
    tier(5, "Citywide Lockdown", "Everything the city has comes for everyone. Run, hide or burn it all down.", 92, 88, 0.20, 30, 5, "everyone", 12,
         [("maxtac_squad", 90), ("wyvern_drones", 60), ("ncpd_enforcers", 45)]),
]

STREET_TEAMS = [("Red", "#e5384f"), ("Blue", "#3a7bff"), ("Green", "#35d07f"), ("Yellow", "#f2c230"), ("Cyan", "#2fe0ff"), ("Magenta", "#ff2fb3"),
                ("Orange", "#ff8a1f"), ("White", "#e8eef5")]


def palette(rows: list[tuple]) -> list[dict]:
    """(name, colour) or (name, colour, faction) rows as team_palette entries."""
    return [{"name": name, "colour": colour, **({"faction": faction[0]} if faction else {})} for name, colour, *faction in rows]


MODES: list[tuple[str, dict]] = [
    ("team_deathmatch", {
        "name": "Team Deathmatch", "description": "Two or three teams, one area, one score: every kill counts. The first to the limit, or the best at the bell, wins.",
        "teams": {"min": 2, "max": 4}, "team_size": {"min": 1, "max": 8}, "time_limit_s": 900, "score_limit": 30,
        "scoring": {"kill": 1, "capture": 0}, "respawn_s": 8, "friendly_fire": False, "pvp": True,
        "zone": {"min_radius": 32, "max_radius": 160}, "districts": False, "reputation": False, "heat_scale": 0.5,
        "roles": ["teammate", "hacker", "spectator", "overlord"], "team_palette": palette(STREET_TEAMS[:4])}),
    ("free_for_all", {
        "name": "Free For All", "description": "Everyone alone, everyone a target. The first to the limit wins; no teams, no friends, no mercy.",
        "teams": {"min": 2, "max": 8}, "team_size": {"min": 1, "max": 1}, "time_limit_s": 600, "score_limit": 20,
        "scoring": {"kill": 1, "capture": 0}, "respawn_s": 6, "friendly_fire": True, "pvp": True,
        "zone": {"min_radius": 24, "max_radius": 128}, "districts": False, "reputation": False, "heat_scale": 0.5,
        "roles": ["teammate", "hacker", "spectator", "overlord"], "team_palette": palette(STREET_TEAMS)}),
    ("gang_wars", {
        "name": "Gang Wars", "description": "Teams stand for gangs and fight over the area's capture points. Hold a point to score; the district behind it is yours to spawn in.",
        "teams": {"min": 2, "max": 4}, "team_size": {"min": 2, "max": 8}, "time_limit_s": 1800, "score_limit": 300,
        "scoring": {"kill": 2, "capture": 1}, "respawn_s": 12, "friendly_fire": False, "pvp": True,
        "zone": {"min_radius": 96, "max_radius": 320}, "capture_points": {"count": 3, "radius": 8, "seconds": 20},
        "districts": True, "reputation": True, "heat_scale": 1.0,
        "roles": ["teammate", "hacker", "spectator", "overlord"],
        "team_palette": palette([("Tyger Claws", "#d4372c", "tygerClaws"), ("Valentinos", "#e3b02a", "valentinos"),
                                 ("Maelstrom", "#c2c7d4", "maelstrom"), ("6th Street", "#3a9d5d", "6thStreet")])}),
    ("last_team_standing", {
        "name": "Last Team Standing", "description": "One life each. A downed player can still be revived; a defeated one is out. The last team on its feet wins.",
        "teams": {"min": 2, "max": 4}, "team_size": {"min": 1, "max": 6}, "time_limit_s": 1200, "score_limit": 1,
        "scoring": {"kill": 0, "capture": 0}, "respawn_s": 0, "friendly_fire": False, "pvp": True,
        "zone": {"min_radius": 32, "max_radius": 192}, "districts": False, "reputation": False, "heat_scale": 0.75,
        "roles": ["teammate", "hacker", "spectator", "overlord"], "team_palette": palette(STREET_TEAMS[:4])}),
]

KINDS: dict[str, list[tuple[str, dict]]] = {"spawn_palette": SPAWNS, "event": EVENTS, "heat_tier": HEAT, "match_mode": MODES}

# Survival recipes for the Phase 8 things 8A owns. The netrunner implant follows the cyberware rule (shapeless, equipment, redstone and
# a copper ingot, CyberAssetsTest). The other Phase 8 things are not made here: the Overlord Console's recipe ships with its block (8O),
# the Van is 8V's item, its drones are launched from it, and the Trauma Beacon is placed by the Downed flow (8T).
RECIPES: dict[str, dict] = {
    "netrunner": {"type": "minecraft:crafting_shapeless", "category": "equipment",
                  "ingredients": ["minecraft:redstone", "minecraft:copper_ingot", "minecraft:lapis_lazuli", "minecraft:amethyst_shard",
                                  "minecraft:ender_pearl"],
                  "result": {"id": f"{NS}:netrunner", "count": 1}},
}


def file_bytes(doc: dict) -> bytes:
    return json_bytes({"schema": 1, **doc})


def json_bytes(doc: dict) -> bytes:
    return (json.dumps(doc, indent="\t", ensure_ascii=False) + "\n").encode("utf-8")


def files() -> dict[str, bytes]:
    """Every default as {path under data/cyberblock: bytes}: the content kinds under cyberblock/, the recipes under recipe/."""
    out: dict[str, bytes] = {}
    for kind, rows in KINDS.items():
        for name, doc in rows:
            path = f"{NS}/{kind}/{name}.json"
            assert path not in out, f"{path} twice"
            out[path] = file_bytes(doc)
    for name, recipe in RECIPES.items():
        out[f"recipe/{name}.json"] = json_bytes(recipe)
    return out


def main() -> None:
    out = files()
    for rel, data in out.items():
        (DATA.parent / rel).parent.mkdir(parents=True, exist_ok=True)
        (DATA.parent / rel).write_bytes(data)
    print(f"wrote {len(out)} files: " + ", ".join(f"{len(rows)} {kind}" for kind, rows in KINDS.items()) + f", {len(RECIPES)} recipe")


if __name__ == "__main__":
    main()
