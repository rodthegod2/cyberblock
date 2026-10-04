"""Megaplan 7-A1: the Phase 7 content defaults (npc_role, dialogue, contract, ripperdoc, landmark, shop, hub) agree with each other, with the
code's id tables and with the lang file; the schemas refuse malformed files."""
import json
import re
from pathlib import Path

import pytest

from tools import content_check

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "mc" / "src" / "main" / "resources"
ASSETS = RES / "assets" / "cyberblock"
DATA = RES / "data" / "cyberblock" / "cyberblock"
ROLES = ["bartender", "fixer", "mechanic", "medic", "ripperdoc", "vendor"]
TOKENS = {"player", "item", "price", "title", "reward", "implant", "drink"}
NEEDS = {"shop": ["purchase", "sale", "no_funds"], "contracts": ["gig_offer", "gig_done"], "bartender": ["rumour", "drink", "no_funds"],
         "ripperdoc": ["installed", "surgery_ok", "surgery_fail", "no_funds"], "medic": ["healed", "no_funds"], "mechanic": ["repaired", "no_funds"]}
GAME_SLANG = ("choom", "preem", "gonk", "nova", "wakako", "silverhand", "samurai", "braindance")  # words of the game's own text: ours is original


def read(path: Path) -> dict:
    return json.loads(path.read_text("utf-8"))


def entries(kind: str) -> dict[str, dict]:
    return {p.stem: read(p) for p in sorted((DATA / kind).glob("*.json"))}


@pytest.fixture(scope="module")
def lang() -> dict:
    return read(ASSETS / "lang" / "en_us.json")


@pytest.fixture(scope="module")
def layout() -> dict:
    return read(DATA / "hub" / "afterlife.json")


def known_item(item: str) -> bool:
    ns, name = item.split(":")
    return ns == "minecraft" or (ASSETS / "items" / f"{name}.json").is_file()


def test_one_file_per_role_in_every_role_kind():
    assert sorted(entries("npc_role")) == ROLES == sorted(entries("dialogue"))
    for kind in ("npc_role", "dialogue"):
        for name, doc in entries(kind).items():
            assert doc.get("id", f"cyberblock:{name}") == f"cyberblock:{name}"
    assert all(d["role"] == f"cyberblock:{n}" for n, d in entries("dialogue").items())
    assert all(d["dialogue"] == f"cyberblock:{n}" for n, d in entries("npc_role").items())


def test_role_tags_are_the_phase_5_role_names_and_no_tag_belongs_to_two_roles():
    skin_roles: set[str] = set()
    for row in entries("skin_map").values():
        role = row.get("role", [])
        skin_roles |= set([role] if isinstance(role, str) else role)
    tags = [t for r in entries("npc_role").values() for t in r["tags"]]
    assert len(tags) == len(set(tags))
    assert set(tags) <= skin_roles, set(tags) - skin_roles
    assert skin_roles - set(tags) <= {"cyberpsycho"}, skin_roles - set(tags)


def test_each_role_has_what_its_screen_needs(lang):
    for name, role in entries("npc_role").items():
        assert lang[role["title_key"]], name
        screen = role["screen"]
        if screen == "ripperdoc":
            assert role["catalogue"] and "install_implant" in role["services"]
        if screen == "shop":
            assert role["shop"] and set(role["shop_by_tag"]) <= set(role["tags"])
            assert set(role["tags"]) <= set(role["shop_by_tag"]), "every vendor tag has a shop"
        if screen == "bartender":
            assert len(role["drinks"]) >= 4
            for drink in role["drinks"]:
                assert lang[f"cyberblock.drink.{drink['key']}"]
        if screen == "medic":
            assert role["prices"]["heal_per_heart"] >= 1
        if screen == "mechanic":
            assert {"repair_vehicle", "repair_gear"} <= set(role["prices"])
        assert (screen == "contracts") == ("gigs" in role["services"]), name


def test_dialogue_has_every_context_its_screen_uses_and_only_known_placeholders():
    roles = entries("npc_role")
    for name, doc in entries("dialogue").items():
        lines = doc["lines"]
        for ctx in NEEDS[roles[name]["screen"]]:
            assert ctx in lines, (name, ctx)
        for ctx, texts in lines.items():
            assert len(texts) == len(set(texts)), (name, ctx)
            if ctx in ("greeting", "farewell", "bark"):
                assert len(texts) >= 4, (name, ctx)
            for line in texts:
                assert set(re.findall(r"\{(\w+)\}", line)) <= TOKENS, (name, line)
                assert "%" not in line and "{" not in re.sub(r"\{\w+\}", "", line), (name, line)
                assert not any(word in line.lower() for word in GAME_SLANG), (name, line)
    for ctx in ("rumour",):
        assert len(entries("dialogue")["bartender"]["lines"][ctx]) >= 10


def test_contracts_cover_every_type_and_refer_to_what_exists(lang):
    contracts, landmarks = entries("contract"), entries("landmark")
    factions = set()
    for row in entries("skin_map").values():
        f = row.get("faction", [])
        factions |= set([f] if isinstance(f, str) else f)
    types = {}
    for name, c in contracts.items():
        types.setdefault(c["type"], []).append(name)
        t = c["target"]
        if c["type"] == "reach_landmark":
            assert t["landmark"].removeprefix("cyberblock:") in landmarks, name
        if c["type"] == "defeat_hostiles":
            assert t["faction"] in factions, (name, t["faction"])
        if c["type"] == "deliver_item":
            assert known_item(t["item"]), name
        for row in c["reward"].get("items", []):
            assert known_item(row["item"]), name
        assert c["reward"]["eddies"] >= 100 * c["difficulty"] // 2, name  # harder gigs pay at least their rough floor
        assert c["difficulty"] in (1, 2, 3)
    assert set(types) == {"deliver_item", "defeat_hostiles", "reach_landmark", "breach_device"}
    assert all(len(v) >= 3 for v in types.values()), types
    for key in ("deliver_item", "defeat_hostiles", "reach_landmark", "breach_device"):
        assert lang[f"cyberblock.gig.type.{key}"]
    for d in (1, 2, 3):
        assert lang[f"cyberblock.gig.difficulty.{d}"]
    titles = [c["title"] for c in contracts.values()]
    assert len(titles) == len(set(titles))


def test_the_catalogue_lists_every_cyberware_id_once_with_riskier_surgery_for_dearer_implants():
    source = (ROOT / "mc" / "src" / "main" / "java" / "dev" / "cyberblock" / "cyber" / "Cyberware.java").read_text("utf-8")
    ids = [m.lower() for m in re.findall(r"^\t([A-Z_]+)\(CyberSlot\.", source, re.M)]
    assert len(ids) == 17
    catalogue = entries("ripperdoc")["afterlife"]
    listed = [e["implant"] for e in catalogue["implants"]]
    assert sorted(listed) == sorted(ids) and len(listed) == len(set(listed))
    with_surgery = [e for e in catalogue["implants"] if "surgery" in e]
    without = [e for e in catalogue["implants"] if "surgery" not in e]
    assert with_surgery and without
    assert min(e["price"] for e in with_surgery) >= max(e["price"] for e in without)  # only the dear ones need the minigame
    by_price = sorted(with_surgery, key=lambda e: e["price"])
    windows = [e["surgery"]["window"] for e in by_price]
    assert windows == sorted(windows, reverse=True), "the dearer the implant, the narrower the window"
    assert entries("npc_role")["ripperdoc"]["catalogue"] == "cyberblock:afterlife"


def test_shops_sell_known_items_and_never_buy_dearer_than_they_sell(lang):
    shops = entries("shop")
    assert {"afterlife_arms", "afterlife_gear", "afterlife_tech", "afterlife_scrap", "afterlife_kitchen"} <= set(shops)
    buy, sell = {}, {}
    for name, shop in shops.items():
        assert lang[shop["title_key"]], name
        assert len({r["item"] for r in shop["stock"]}) == len(shop["stock"]), name
        for row in shop["stock"]:
            assert known_item(row["item"]), (name, row)
            buy.setdefault(row["item"], []).append(row["price"] / row["count"])
        for row in shop.get("sell", []):
            assert known_item(row["item"]), (name, row)
            sell.setdefault(row["item"], []).append(row["price"] / row["count"])
    for item, prices in sell.items():
        if item in buy:
            assert max(prices) < min(buy[item]), f"{item}: sells for {max(prices)} per piece but is bought for {min(buy[item])}"


def test_landmarks_are_unique_named_places_in_known_districts_and_marked_approximate(lang):
    marks = entries("landmark")
    assert len(marks) >= 12
    assert len({m["name"] for m in marks.values()}) == len(marks)
    assert {m["district"] for m in marks.values()} >= {"City Center", "Watson", "Westbrook", "Heywood", "Pacifica", "Santo Domingo", "Badlands"}
    for name, m in marks.items():
        assert lang[f"cyberblock.landmark.kind.{m['kind']}"]
        assert m["approx"] is True, f"{name}: a measured position would say approx false only after an in-game check"
        x, y, z = m["pos"]
        assert -64 <= y <= 400 and abs(x) < 6000 and abs(z) < 6000, name
        assert name == re.sub(r"[^a-z0-9]+", "_", m["name"].lower()).strip("_"), name


def test_hub_roles_agree_with_the_layout(layout):
    roles = entries("npc_role")
    areas = layout["areas"]
    for fig in layout["figures"]:
        role = roles[fig["role"].removeprefix("cyberblock:")]
        assert role["hub"]["area"] == fig["area"], fig["id"]
        if role["screen"] == "shop":
            assert fig["shop"].removeprefix("cyberblock:") in entries("shop")
        else:
            assert "shop" not in fig
    for name, role in roles.items():
        assert role["hub"]["area"] in areas, name
        assert any(f["role"] == f"cyberblock:{name}" for f in layout["figures"]), f"{name} has no figure in the hub"
    assert len({f["id"] for f in layout["figures"]}) == len(layout["figures"])


def test_the_hub_layout_titles_and_boards_have_english_text(layout, lang):
    assert lang[layout["title_key"]]
    for board in layout["boards"]:
        assert lang[board["title_key"]]
    assert lang["cyberblock.afterlife.terminal.soon"]
    assert lang["cyberblock.afterlife.clinic"] and lang[entries("ripperdoc")["afterlife"]["title_key"]]


# --- the schemas refuse malformed files ------------------------------------------------------------------------------------
def check_files(tmp_path: Path, files: dict[str, dict]) -> list[str]:
    pack = tmp_path / "data" / "friend" / "cyberblock"
    for rel, doc in files.items():
        path = pack / f"{rel}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(doc), encoding="utf-8")
    return content_check.check([tmp_path / "data"], reference_roots=[content_check.DEFAULT_DATA]).problems


def test_the_schemas_refuse_bad_files_and_accept_good_ones(tmp_path):
    gig = {"schema": 1, "title": "T", "text": "x", "type": "reach_landmark", "target": {"landmark": "cyberblock:corpo_plaza"}, "reward": {"eddies": 5}}
    line = {"schema": 1, "role": "cyberblock:bartender", "lines": {"greeting": ["a"], "farewell": ["b"], "bark": ["c"]}}
    good = {
        "contract/good": gig, "dialogue/good": line,
        "landmark/good": {"schema": 1, "name": "N", "district": "D", "kind": "plaza", "pos": [1, 2, 3]},
        "npc_role/good": {"schema": 1, "title_key": "x.y", "screen": "medic", "services": ["heal"], "tags": ["Medic"], "dialogue": "cyberblock:medic"},
        "ripperdoc/good": {"schema": 1, "title_key": "x.y", "implants": [{"implant": "lynx_paws", "price": 5}], "failure": {"damage": 2, "debuff_s": 5}},
    }
    bad = {
        "contract/wrong_target": {**gig, "type": "deliver_item"},
        "contract/extra_key": {**gig, "target": {"landmark": "cyberblock:corpo_plaza", "count": 2}},
        "contract/missing_landmark": {**gig, "target": {"landmark": "friend:nowhere"}},
        "contract/free": {**gig, "reward": {"eddies": 0}},
        "contract/long_text": {**gig, "text": "x" * 281},
        "dialogue/no_role": {**line, "role": "friend:nobody"},
        "dialogue/no_bark": {"schema": 1, "role": "cyberblock:bartender", "lines": {"greeting": ["a"], "farewell": ["b"]}},
        "dialogue/empty": {**line, "lines": {**line["lines"], "bark": []}},
        "dialogue/unknown_context": {**line, "lines": {**line["lines"], "shout": ["!"]}},
        "landmark/no_pos": {"schema": 1, "name": "N", "district": "D", "kind": "plaza"},
        "landmark/bad_kind": {"schema": 1, "name": "N", "district": "D", "kind": "tower", "pos": [1, 2, 3]},
        "npc_role/bad_screen": {"schema": 1, "title_key": "x.y", "screen": "pilot", "services": ["heal"], "tags": ["T"], "dialogue": "cyberblock:medic"},
        "npc_role/no_dialogue": {"schema": 1, "title_key": "x.y", "screen": "medic", "services": ["heal"], "tags": ["T"], "dialogue": "friend:none"},
        "ripperdoc/bad_implant": {"schema": 1, "title_key": "x.y", "implants": [{"implant": "wings", "price": 5}], "failure": {"damage": 2, "debuff_s": 5}},
        "ripperdoc/tiny_window": {"schema": 1, "title_key": "x.y", "implants": [{"implant": "lynx_paws", "price": 5, "surgery": {"window": 1, "period": 40}}],
                                  "failure": {"damage": 2, "debuff_s": 5}},
        "hub/no_steps": {"schema": 1, "title_key": "x.y", "origin": [0, 0, 0], "size": [1, 1, 1], "spawn": {"pos": [0, 1, 0], "yaw": 0}, "areas": {},
                         "stations": {"match_terminal": [0, 0, 0], "holo_table": [0, 0, 0], "exit_portal": [0, 0, 0]}, "boards": [], "figures": []},
    }
    problems = check_files(tmp_path, {**good, **bad})
    for rel in bad:
        assert any(f"{rel}.json:" in p for p in problems), (rel, problems)
    assert not any("/good.json:" in p for p in problems), problems
