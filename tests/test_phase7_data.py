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
APPENDED_1_17 = {"firewall", "netrunner"}  # megaplan 1.17: cyberware ids appended by Phases 6 and 8
# the placeholders 7N fills in each context ({player} always): a line may only use those of the screen that says it
FILLED = {"gig_offer": {"title", "reward"}, "gig_done": {"title", "reward"}, "purchase": {"item", "price"}, "sale": {"item", "price"},
          "repaired": {"item", "price"}, "drink": {"drink", "price"}, "installed": {"implant", "price"}, "surgery_ok": {"implant"},
          "surgery_fail": {"implant"}, "healed": {"price"}}
NO_FUNDS = {"shop": {"item", "price"}, "mechanic": {"item", "price"}, "bartender": {"drink", "price"}, "ripperdoc": {"implant", "price"},
            "medic": {"price"}}
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


def test_every_placeholder_is_one_its_context_fills():
    roles = entries("npc_role")
    for name, doc in entries("dialogue").items():
        screen = roles[name]["screen"]
        for ctx, texts in doc["lines"].items():
            filled = {"player"} | (NO_FUNDS[screen] if ctx == "no_funds" else FILLED.get(ctx, set()))
            for line in texts:
                assert set(re.findall(r"\{(\w+)\}", line)) <= filled, (name, ctx, line, filled)


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


# what the live world can supply for a delivery: the server's world (cyberblock:nightcity) is a void world with no ores and no reachable
# Nether, so a deliver_item gig asks for a drop of the hostile mobs that spawn on the replica's dark floors, never an ore, a Nether item
# or something only a shop sells
MOB_DROPS = {"minecraft:" + i for i in ("bone", "string", "gunpowder", "spider_eye", "rotten_flesh")}


def test_deliveries_are_mob_drops_no_shop_stocks_and_pay_more_than_selling_them():
    shops = entries("shop").values()
    stocked = {row["item"] for shop in shops for row in shop["stock"]}
    sell = {}
    for shop in shops:
        for row in shop.get("sell", []):
            sell[row["item"]] = max(sell.get(row["item"], 0), row["price"] / row["count"])
    deliveries = {n: c for n, c in entries("contract").items() if c["type"] == "deliver_item"}
    assert deliveries
    for name, c in deliveries.items():
        item, count = c["target"]["item"], c["target"]["count"]
        assert item in MOB_DROPS, f"{name}: {item} is not a mob drop the void world supplies"
        assert item not in stocked, f"{name}: {item} can be bought, so the gig is a buy-and-deliver eddie loop"
        assert c["reward"]["eddies"] > 2 * sell.get(item, 0) * count, f"{name}: selling the {count} {item} pays nearly as much"


def test_a_solved_device_breach_steps_the_players_breach_gig():
    source = (ROOT / "mc" / "src" / "main" / "java" / "dev" / "cyberblock" / "hack" / "breach" / "Breach.java").read_text("utf-8")
    solved = source[source.index("private static void deviceOutcome("):source.index("private static void onHackResult(")]
    solved = solved[solved.rindex("} else {"):]
    assert "Gigs.breached(player, device.type().id())" in solved, "the solved branch of a device breach must advance breach_device gigs"
    kinds = {c["target"]["kind"] for c in entries("contract").values() if c["type"] == "breach_device"}
    permissions = (ROOT / "mc" / "src" / "main" / "java" / "dev" / "cyberblock" / "net" / "Permissions.java").read_text("utf-8")
    types = re.search(r"enum DeviceType \{\s*([^;]+);", permissions).group(1)
    ids = {m.lower() for m in re.findall(r"([A-Z_]+)\(\d+\)", types)}
    assert kinds and kinds <= ids, f"breach gigs name kinds no device type has: {kinds - ids}"


def test_the_catalogue_lists_every_cyberware_id_once_with_riskier_surgery_for_dearer_implants():
    source = (ROOT / "mc" / "src" / "main" / "java" / "dev" / "cyberblock" / "cyber" / "Cyberware.java").read_text("utf-8")
    ids = [m.lower() for m in re.findall(r"^\t([A-Z_]+)\(CyberSlot\.", source, re.M)]
    assert 17 <= len(ids) <= 32  # the 17 of retest2, then the ids megaplan 1.17 appends
    schema = read(ROOT / "shared" / "content-schemas" / "ripperdoc.schema.json")
    allowed = schema["properties"]["implants"]["items"]["properties"]["implant"]["enum"]
    assert set(ids) <= set(allowed), set(ids) - set(allowed)
    appended = set(allowed) - set(ids)
    assert appended <= APPENDED_1_17, appended  # only a 1.17 id may be in the schema before its phase's Cyberware lands
    catalogue = entries("ripperdoc")["afterlife"]
    listed = [e["implant"] for e in catalogue["implants"]]
    assert len(listed) == len(set(listed))
    assert set(ids) <= set(listed) <= set(allowed), (set(ids) - set(listed), set(listed) - set(allowed))
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


# One crafting step from shop stock to a sell row (the Minecraft 26.3 recipes, read from its jar; redstone torches counted as their redstone
# and stick): {sold item: ({ingredient: count}, items made)}. FREE are the renewable materials that cost no eddies (wood, stone, coal,
# feathers): a recipe with a bought ingredient and only free ones besides is an eddie loop unless the sell price is below the bought part.
RECIPES = {
    "minecraft:iron_sword": ({"minecraft:iron_ingot": 2, "minecraft:stick": 1}, 1),
    "minecraft:bow": ({"minecraft:string": 3, "minecraft:stick": 3}, 1),
    "minecraft:shield": ({"minecraft:iron_ingot": 1, "minecraft:oak_planks": 6}, 1),
    "minecraft:arrow": ({"minecraft:flint": 1, "minecraft:stick": 1, "minecraft:feather": 1}, 4),
    "minecraft:compass": ({"minecraft:iron_ingot": 4, "minecraft:redstone": 1}, 1),
    "minecraft:clock": ({"minecraft:gold_ingot": 4, "minecraft:redstone": 1}, 1),
    "minecraft:torch": ({"minecraft:coal": 1, "minecraft:stick": 1}, 4),
    "minecraft:repeater": ({"minecraft:redstone": 3, "minecraft:stick": 2, "minecraft:stone": 3}, 1),
    "minecraft:comparator": ({"minecraft:redstone": 3, "minecraft:stick": 3, "minecraft:quartz": 1, "minecraft:stone": 3}, 1),
}
FREE = {"minecraft:stick", "minecraft:oak_planks", "minecraft:feather", "minecraft:stone", "minecraft:coal"}
# sell rows no recipe makes from shop stock: drops and raw materials (a new sell row must be listed here or in RECIPES)
UNCRAFTED = {"minecraft:" + i for i in ("rotten_flesh", "bone", "string", "gunpowder", "spider_eye", "ink_sac", "iron_ingot", "gold_ingot",
                                        "redstone", "emerald", "diamond", "ender_pearl", "wheat", "potato", "carrot")}


def mod_recipe(item: str) -> tuple[dict[str, int], int] | None:
    """A cyberblock item's own crafting recipe as ({ingredient: count}, items made); None when it has none."""
    path = RES / "data" / "cyberblock" / "recipe" / f"{item.removeprefix('cyberblock:')}.json"
    if not path.is_file():
        return None
    doc, need = read(path), {}
    if "ingredients" in doc:
        names = doc["ingredients"]
    else:
        names = [doc["key"][ch] for row in doc["pattern"] for ch in row if ch != " "]
    for name in names:
        name = name if isinstance(name, str) else name[0]
        need[name] = need.get(name, 0) + 1
    return need, doc["result"].get("count", 1)


def test_no_sell_row_pays_more_than_the_bought_inputs_of_its_recipe():
    buy, sells = {}, []
    for name, shop in entries("shop").items():
        for row in shop["stock"]:
            buy[row["item"]] = min(buy.get(row["item"], 1e9), row["price"] / row["count"])
        sells += [(name, row) for row in shop.get("sell", [])]
    assert sells
    for shop, row in sells:
        item = row["item"]
        recipe = RECIPES.get(item) or (mod_recipe(item) if item.startswith("cyberblock:") else None)
        if recipe is None:
            assert item in UNCRAFTED or item.startswith("cyberblock:"), f"{shop} buys {item}: add its recipe to RECIPES or it to UNCRAFTED"
            continue
        need, made = recipe
        bought = {i: n for i, n in need.items() if i in buy}
        if not bought or set(need) - set(bought) - FREE:
            continue  # nothing in it is bought, or it needs a gathered material the shops do not sell: not an eddie loop
        cost = sum(buy[i] * n for i, n in bought.items()) / made
        each = row["price"] / row["count"]
        assert each < cost, f"{shop} buys {item} for {each:.2f} each, but its bought inputs cost only {cost:.2f}"


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
