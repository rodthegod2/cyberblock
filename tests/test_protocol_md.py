import copy, json, math, re
from pathlib import Path

import pytest

TEXT = Path("shared/protocol.md").read_text(encoding="utf-8")


def section(heading: str) -> str:
    return re.search(rf"^### {re.escape(heading)}\n(.*?)(?=^#{{2,3}} |\Z)", TEXT, re.S | re.M).group(1)


def documented(heading: str) -> set[str]:
    return set(re.findall(r"^\| `(\w+)` \|", section(heading), re.M)) - {"t"}  # "t" is the header row


def examples(first_line: str) -> list[dict]:
    block = re.search(rf"{re.escape(first_line)}\n```jsonl\n(.*?)\n```", TEXT, re.S).group(1)
    return [json.loads(line) for line in block.splitlines()]


def test_every_cp_to_mc_message_has_one_example():
    types = [m["t"] for m in examples("Cyberpunk → Minecraft:")]
    assert len(types) == len(set(types)) and set(types) == documented("Cyberpunk → Minecraft (all values already Minecraft convention)")


def test_every_mc_to_cp_message_has_one_example():
    types = [m["t"] for m in examples("Minecraft → Cyberpunk:")]
    assert len(types) == len(set(types)) and set(types) == documented("Minecraft → Cyberpunk")


def test_hellos_carry_protocol_1():
    assert all(m["proto"] == 1 for m in examples("Cyberpunk → Minecraft:") + examples("Minecraft → Cyberpunk:") if m["t"] == "hello")


# ---- layer 2 (world interaction): new message types, their fields and the limits both receivers validate against ----
CP_TO_MC, MC_TO_CP = "Cyberpunk → Minecraft (all values already Minecraft convention)", "Minecraft → Cyberpunk"
DAMAGE_KINDS = {"bullet", "explosion", "melee", "fall", "vehicle", "fire", "other"}
HIT_KINDS = {"melee", "arrow", "trident", "mob", "explosion", "fire"}


def example(first_line: str, t: str) -> dict:
    return next(m for m in examples(first_line) if m["t"] == t)


def vec3(v) -> bool:
    return isinstance(v, list) and len(v) == 3 and all(isinstance(c, (int, float)) and math.isfinite(c) and abs(c) < 3e7 for c in v)


def uint64_string(s) -> bool:
    return isinstance(s, str) and s.isascii() and s.isdigit() and int(s) < 2**64


def test_layer2_message_types_are_documented():
    assert {"dmg", "npcs"} <= documented(CP_TO_MC)
    assert {"block", "explosion", "hit", "ignite"} <= documented(MC_TO_CP)


def test_every_example_field_is_named_in_its_table_row():
    for heading, first_line in ((CP_TO_MC, "Cyberpunk → Minecraft:"), (MC_TO_CP, "Minecraft → Cyberpunk:")):
        rows = {m.group(1): m.group(0) for m in re.finditer(r"^\| `(\w+)` \|.*$", section(heading), re.M)}
        for m in examples(first_line):
            for key in set(m) - {"t"}:
                assert re.search(rf"\b{key}\b", rows[m["t"]]), f"{m['t']}.{key} is not described in its table row"


def test_dmg_example():
    m = example("Cyberpunk → Minecraft:", "dmg")
    assert 0 <= m["frac"] <= 4 and m["kind"] in DAMAGE_KINDS and (m["from"] is None or vec3(m["from"]))


def test_npcs_example_lists_humans_and_a_vehicle_with_unique_decimal_string_ids():
    m = example("Cyberpunk → Minecraft:", "npcs")
    assert {e["kind"] for e in m["list"]} == {"human", "vehicle"} and len(m["list"]) <= 48
    assert len({e["id"] for e in m["list"]}) == len(m["list"])
    for e in m["list"]:
        assert set(e) == {"id", "p", "yaw", "kind", "hostile"}
        assert uint64_string(e["id"]) and vec3(e["p"]) and math.isfinite(e["yaw"]) and isinstance(e["hostile"], bool)
    assert any(int(e["id"]) > 2**53 for e in m["list"])  # shows why ids are strings: they do not fit a JSON double


def test_block_example():
    m = example("Minecraft → Cyberpunk:", "block")
    assert m["op"] in {"add", "remove"} and len(m["pos"]) == 3 and all(isinstance(c, int) and abs(c) < 3e7 for c in m["pos"])


def test_explosion_example():
    m = example("Minecraft → Cyberpunk:", "explosion")
    assert vec3(m["pos"]) and 0 <= m["power"] <= 20 and isinstance(m["fire"], bool)


def test_hit_example():
    m = example("Minecraft → Cyberpunk:", "hit")
    assert uint64_string(m["target"]) and 0 <= m["dmg"] <= 1000 and m["kind"] in HIT_KINDS and vec3(m["impulse"])


def test_ignite_example():
    m = example("Minecraft → Cyberpunk:", "ignite")
    assert uint64_string(m["target"]) and 0 <= m["seconds"] <= 120


# ---- layers 3-6: new message types, the three extended ones, the limits and the name lists (plan section 1.2) ----
L36_CP_TO_MC, L36_MC_TO_CP = "Layers 3–6: Cyberpunk → Minecraft", "Layers 3–6: Minecraft → Cyberpunk"
L36_CP_EXAMPLES, L36_MC_EXAMPLES = "Layers 3–6 examples, Cyberpunk to Minecraft:", "Layers 3–6 examples, Minecraft to Cyberpunk:"
L36_NEW_CP_TO_MC = {"mobdmg", "pcells", "unlead", "scaninfo", "hackresult", "killed", "look", "char", "skey"}
L36_NEW_MC_TO_CP = {"blocks", "mobs", "flight", "teleport", "proj", "lead", "pull", "knock", "effect", "scan", "hack", "perks", "perkfx", "binds",
                    "align", "cyberware", "cyberfx"}  # the last three: re-test 2 (plan 2026-10-03-retest2 section 1.2)
L36_EXTENDED = {"cam", "key", "click", "hit"}
CAM_FLAGS = {"cr", "air", "held", "menu", "fly"}
CAM_ADDED = CAM_FLAGS | {"lv"}  # what the layers 3-6 `cam` row adds to the layer 1 message (`lv`: re-test 2)
THREATS = {"none", "low", "medium", "high", "extreme"}
NEW_KEYS = {"jump", "sprint", "perspective", "hack", "skills", "cyberware", "activate"}
BIND_NAMES = {"hack", "skills", "cyberware", "activate"}
FLIGHT_KINDS = {"glide", "launch"}
CYBERWARE_IDS = ["mantis_blades", "gorilla_arms", "monowire", "projectile_launch_system", "reinforced_tendons", "fortified_ankles",
                 "lynx_paws", "sandevistan", "berserk", "cyberdeck", "kerenzikov", "optical_camo", "subdermal_armor",
                 "kiroshi_optics", "biomonitor", "second_heart", "ram_upgrade"]  # plan section 1.7, in this fixed order
CYBERFX_IDS = ["sandevistan", "kerenzikov", "berserk"]
KIND_RE, EFFECT_RE = re.compile(r"[a-z0-9_]{1,32}"), re.compile(r"[a-z_]{1,32}")


def names(label: str) -> list[str]:
    line = re.search(rf"^- \*\*{label}\*\*.*$", TEXT, re.M).group(0)
    return re.findall(r"`([a-z_]+)`", line.split("—", 1)[1])


HACKS, PERKS, CYBERWARE, CYBERFX = names("Hack names"), names("Perk ids"), names("Cyberware ids"), names("Cyberfx ids")


def rows_by_type(heading: str) -> dict[str, str]:
    """All table rows of a section by `t`; a type with two forms (lead) has several rows, joined."""
    rows: dict[str, str] = {}
    for m in re.finditer(r"^\| `(\w+)` \|.*$", section(heading), re.M):
        rows[m.group(1)] = rows.get(m.group(1), "") + m.group(0)
    rows.pop("t")
    return rows


def num(x, lo, hi) -> bool:
    return isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x) and lo <= x <= hi


def whole(x, lo=0, hi=2**31 - 1) -> bool:
    return isinstance(x, int) and not isinstance(x, bool) and lo <= x <= hi


def speed_ok(v) -> bool:
    return vec3(v) and math.hypot(*v) <= 200


def cells_ok(c) -> bool:
    return isinstance(c, list) and len(c) % 3 == 0 and len(c) <= 3 * 64 and all(whole(i, -3e7 + 1, 3e7 - 1) for i in c)


def text_ok(s) -> bool:
    return isinstance(s, str) and len(s) <= 64


def lead_ok(m) -> bool:
    if not uint64_string(m["target"]):
        return False
    if m.get("release") is True:
        return set(m) == {"t", "target", "release"}
    return set(m) == {"t", "target", "anchor", "length"} and (m["anchor"] is None or vec3(m["anchor"])) and num(m["length"], 1, 32)


def list_ok(m, key, limit, entry) -> bool:
    return isinstance(m[key], list) and len(m[key]) <= limit and all(entry(e) for e in m[key])


def mob_ok(e) -> bool:
    return (set(e) == {"id", "kind", "p", "yaw", "w", "h", "hp", "maxHp"} and whole(e["id"]) and KIND_RE.fullmatch(e["kind"])
            and vec3(e["p"]) and num(e["yaw"], -1e4, 1e4) and num(e["w"], 0, 16) and num(e["h"], 0, 16)
            and num(e["hp"], 0, 2048) and num(e["maxHp"], 0, 2048))


def proj_ok(e) -> bool:
    return set(e) == {"id", "p", "v", "g"} and whole(e["id"]) and vec3(e["p"]) and speed_ok(e["v"]) and num(e["g"], 0, 40)


def perks_ok(m) -> bool:
    ids = [e["id"] for e in m["list"]]
    return sorted(ids) == sorted(PERKS) and all(set(e) == {"id", "rank"} and e["rank"] in (0, 1) and not isinstance(e["rank"], bool)
                                                  for e in m["list"])


def binds_ok(m) -> bool:
    """Minecraft always sends all four names; a receiver also tolerates a missing one (an older Minecraft build)."""
    return set(m["keys"]) == BIND_NAMES and all(whole(vk, 1, 254) for vk in m["keys"].values())


def cyberware_ok(m) -> bool:
    return list_ok(m, "list", 32, lambda e: set(e) == {"id", "on"} and e["id"] in CYBERWARE and e["on"] in (0, 1) and not isinstance(e["on"], bool))


def flight_ok(m) -> bool:
    """`kind` and `ts` are optional; the receiver treats a bad one as absent, a sender stays inside the limits."""
    return (isinstance(m["active"], bool) and vec3(m["p"]) and speed_ok(m["v"]) and num(m["yaw"], -1e4, 1e4) and num(m["pitch"], -90, 90)
            and m.get("kind", "glide") in FLIGHT_KINDS and ("ts" not in m or num(m["ts"], 0, 1e13)))


def mods_ok(m) -> bool:
    """`mods` (SDL keymod bits) is optional: an older host or the fake host sends none."""
    return "mods" not in m or whole(m["mods"], 0, 0xFFFF)


def cam_ok(m) -> bool:
    """`cr` always; `air`, `held`, `menu` and `fly` are booleans when present (the fake host sends only `cr`, and `fly` in layer 4);
    `lv` is an integer 0..63 when present (the fake host and older hosts send none)."""
    return (isinstance(m["cr"], bool) and {"f", "p", "r", "fov", "fp", "pl", "h"} <= set(m)
            and all(isinstance(m[f], bool) for f in CAM_FLAGS & set(m)) and ("lv" not in m or whole(m["lv"], 0, 63)))


# Each validator applies the plan section 1.2 limits to a whole message (everything but `t` is checked).
VALIDATORS = {
    "cam": cam_ok,
    "key": lambda m: m["k"] in NEW_KEYS and isinstance(m["down"], bool) and mods_ok(m),
    "click": lambda m: m["b"] in (0, 1, 2) and not isinstance(m["b"], bool) and isinstance(m["down"], bool) and mods_ok(m),
    "char": lambda m: whole(m["c"], 0x20, 0x10FFFF) and m["c"] != 0x7F and not 0xD800 <= m["c"] <= 0xDFFF,
    "skey": lambda m: whole(m["sc"], 1, 511) and mods_ok(m),
    "mobdmg": lambda m: whole(m["id"]) and num(m["frac"], 0, 4) and (m["by"] is None or uint64_string(m["by"])),
    "pcells": lambda m: cells_ok(m["c"]),
    "unlead": lambda m: uint64_string(m["target"]),
    "scaninfo": lambda m: (whole(m["id"]) and isinstance(m["ok"], bool) and uint64_string(m["target"]) and text_ok(m["name"])
                           and text_ok(m["faction"]) and m["threat"] in THREATS and num(m["hp"], 0, 1)
                           and isinstance(m["device"], bool) and vec3(m["p"])
                           and isinstance(m["size"], list) and len(m["size"]) == 2 and all(num(s, 0, 3e7) for s in m["size"])),
    "hackresult": lambda m: whole(m["id"]) and isinstance(m["ok"], bool) and text_ok(m["reason"]) and (not m["ok"] or m["reason"] == ""),
    "killed": lambda m: uint64_string(m["target"]) and m["kind"] in {"human", "vehicle"} and isinstance(m["hostile"], bool),
    "look": lambda m: isinstance(m["d"], list) and len(m["d"]) == 2 and all(num(d, -3e7, 3e7) for d in m["d"]),
    "hit": lambda m: uint64_string(m["target"]) and m["kind"] in HIT_KINDS and vec3(m["impulse"]) and num(m["dmg"], 0, 1000) and whole(m["mob"]),
    "blocks": lambda m: isinstance(m["reset"], bool) and cells_ok(m["c"]),
    "mobs": lambda m: list_ok(m, "list", 12, mob_ok),
    "flight": flight_ok,
    "teleport": lambda m: vec3(m["p"]) and num(m["yaw"], -1e4, 1e4),
    "proj": lambda m: list_ok(m, "list", 16, proj_ok),
    "lead": lead_ok,
    "pull": lambda m: uint64_string(m["target"]) and vec3(m["toward"]) and num(m["strength"], 0, 50),
    "knock": lambda m: vec3(m["pos"]) and num(m["radius"], 0, 16) and num(m["strength"], 0, 50),
    "effect": lambda m: EFFECT_RE.fullmatch(m["name"]) and num(m["seconds"], 0, 86400) and whole(m["amp"], 0, 255),
    "scan": lambda m: whole(m["id"]) and (m["target"] is None or uint64_string(m["target"])),
    "hack": lambda m: whole(m["id"]) and uint64_string(m["target"]) and m["hack"] in HACKS and isinstance(m["chain"], bool),
    "perks": perks_ok,
    "perkfx": lambda m: m["id"] == "sandevistan" and num(m["seconds"], 0, 30),
    "binds": binds_ok,
    "align": lambda m: whole(m["k"], 0, 63),
    "cyberware": cyberware_ok,
    "cyberfx": lambda m: m["id"] in CYBERFX and num(m["seconds"], 0, 30),
}
L36_EXAMPLES = [(m["t"], m) for first in (L36_CP_EXAMPLES, L36_MC_EXAMPLES) for m in examples(first)]


def test_layer36_name_lists_have_the_plan_counts():
    assert len(HACKS) == 10 and len(set(HACKS)) == 10 and len(PERKS) == 20 and len(set(PERKS)) == 20
    assert CYBERWARE == CYBERWARE_IDS and CYBERFX == CYBERFX_IDS  # re-test 2: 17 implants in the fixed order, 3 timed effects
    assert {"short_circuit", "device", "cyberpsychosis"} <= set(HACKS) and {"iron_skin", "sandevistan", "silent_steps"} <= set(PERKS)


def test_layer36_every_new_type_and_every_extended_type_is_in_its_table():
    assert documented(L36_CP_TO_MC) == L36_NEW_CP_TO_MC | {"cam", "key", "click"}
    assert documented(L36_MC_TO_CP) == L36_NEW_MC_TO_CP | {"hit"}
    for t, heading in (("cam", L36_CP_TO_MC), ("key", L36_CP_TO_MC), ("click", L36_CP_TO_MC), ("hit", L36_MC_TO_CP)):
        assert "**extends layer" in rows_by_type(heading)[t], f"{t} is not marked as an extension"
    assert len(re.findall(r"^\| `lead` \|", section(L36_MC_TO_CP), re.M)) == 2  # the leash and the release form


def test_layer36_every_type_of_both_tables_has_an_example_and_every_example_has_a_row():
    for heading, first in ((L36_CP_TO_MC, L36_CP_EXAMPLES), (L36_MC_TO_CP, L36_MC_EXAMPLES)):
        assert {m["t"] for m in examples(first)} == documented(heading)
    assert {t for t, _ in L36_EXAMPLES} == set(VALIDATORS)


def test_layer36_lead_and_scan_show_both_forms():
    leads = [m for t, m in L36_EXAMPLES if t == "lead"]
    assert [("release" in m) for m in leads].count(True) == 1 and len(leads) == 2
    assert any(m["anchor"] is None for m in leads if "anchor" in m)
    scans = [m["target"] for t, m in L36_EXAMPLES if t == "scan"]
    assert None in scans and any(s is not None for s in scans)


def test_layer36_every_example_field_is_named_in_its_table_rows():
    for heading, first, old in ((L36_CP_TO_MC, L36_CP_EXAMPLES, "Cyberpunk → Minecraft:"), (L36_MC_TO_CP, L36_MC_EXAMPLES, "Minecraft → Cyberpunk:")):
        rows = rows_by_type(heading)
        for m in examples(first):
            inherited = set(example(old, m["t"])) if m["t"] in L36_EXTENDED else set()  # an extension row names only what it adds
            for key in set(m) - {"t"} - inherited:
                assert re.search(rf"\b{key}\b", rows[m["t"]]), f"{m['t']}.{key} is not described in its table rows"


@pytest.mark.parametrize("t, m", L36_EXAMPLES, ids=[f"{t}-{i}" for i, (t, _) in enumerate(L36_EXAMPLES)])
def test_layer36_example_obeys_the_documented_limits(t, m):
    assert VALIDATORS[t](m)


def test_layer36_examples_carry_the_exact_field_sets():
    fields = {t: {tuple(sorted(set(m) - {"t"})) for u, m in L36_EXAMPLES if u == t} for t in VALIDATORS}
    assert fields["mobdmg"] == {("by", "frac", "id")} and fields["pcells"] == {("c",)}
    assert fields["scaninfo"] == {("device", "faction", "hp", "id", "name", "ok", "p", "size", "target", "threat")}
    assert fields["hackresult"] == {("id", "ok", "reason")} and fields["killed"] == {("hostile", "kind", "target")}
    assert fields["flight"] == {("active", "kind", "p", "pitch", "ts", "v", "yaw")} and fields["teleport"] == {("p", "yaw")}
    assert fields["pull"] == {("strength", "target", "toward")} and fields["knock"] == {("pos", "radius", "strength")}
    assert fields["effect"] == {("amp", "name", "seconds")} and fields["hack"] == {("chain", "hack", "id", "target")}
    assert fields["perkfx"] == {("id", "seconds")} and fields["binds"] == {("keys",)} and fields["blocks"] == {("c", "reset")}
    assert fields["align"] == {("k",)} and fields["cyberware"] == {("list",)} and fields["cyberfx"] == {("id", "seconds")}


def test_layer36_extended_examples_keep_their_layer1_and_layer2_fields():
    cam = next(m for t, m in L36_EXAMPLES if t == "cam")
    assert set(cam) == set(example("Cyberpunk → Minecraft:", "cam")) | CAM_ADDED
    click = next(m for t, m in L36_EXAMPLES if t == "click")
    assert set(click) == set(example("Cyberpunk → Minecraft:", "click")) | {"mods"}
    hit = next(m for t, m in L36_EXAMPLES if t == "hit")
    assert set(hit) == set(example("Minecraft → Cyberpunk:", "hit")) | {"mob"} and hit["kind"] in {"mob", "arrow"}


def with_(t, **changes):
    m = copy.deepcopy(next(m for u, m in L36_EXAMPLES if u == t))
    m.update(changes)
    return m


def entry(t, key, **changes):
    m = copy.deepcopy(next(m for u, m in L36_EXAMPLES if u == t))
    m[key] = [dict(e, **changes) for e in m[key]]
    return m


def many(t, key, n):
    m = copy.deepcopy(next(m for u, m in L36_EXAMPLES if u == t))
    m[key] = [m[key][0]] * n
    return m


OUT_OF_LIMITS = [
    ("mobdmg", with_("mobdmg", frac=4.5)), ("mobdmg", with_("mobdmg", frac=-0.1)), ("mobdmg", with_("mobdmg", id=-1)),
    ("pcells", with_("pcells", c=[0, 0, 0] * 65)), ("pcells", with_("pcells", c=[1, 2, 3, 4])), ("pcells", with_("pcells", c=[0.5, 1, 2])),
    ("scaninfo", with_("scaninfo", threat="bogus")), ("scaninfo", with_("scaninfo", hp=1.5)), ("scaninfo", with_("scaninfo", name="n" * 65)),
    ("scaninfo", with_("scaninfo", faction="f" * 65)), ("scaninfo", with_("scaninfo", target=7)),
    ("hackresult", with_("hackresult", reason="r" * 65)), ("hackresult", with_("hackresult", reason="denied")),
    ("killed", with_("killed", kind="robot")), ("look", with_("look", d=[1.0, 2.0, 3.0])), ("look", with_("look", d=[1.0, float("inf")])),
    ("hit", with_("hit", mob=-1)), ("blocks", with_("blocks", c=[0, 0, 0] * 65)), ("blocks", with_("blocks", reset="yes")),
    ("mobs", many("mobs", "list", 13)), ("mobs", entry("mobs", "list", hp=2049)), ("mobs", entry("mobs", "list", w=17)),
    ("mobs", entry("mobs", "list", kind="Zombie")), ("mobs", entry("mobs", "list", id=-3)),
    ("flight", with_("flight", v=[0.0, -150.0, 150.0])), ("flight", with_("flight", p=[3e7, 0.0, 0.0])),
    ("teleport", with_("teleport", p=[0.0, float("nan"), 0.0])),
    ("proj", many("proj", "list", 17)), ("proj", entry("proj", "list", g=41)), ("proj", entry("proj", "list", v=[0.0, 0.0, 201.0])),
    ("lead", with_("lead", length=0.5)), ("lead", with_("lead", length=33)), ("lead", with_("lead", target="not-a-number")),
    ("pull", with_("pull", strength=51)), ("knock", with_("knock", radius=17)), ("knock", with_("knock", strength=-1)),
    ("effect", with_("effect", seconds=86401)), ("effect", with_("effect", name="Speed")), ("effect", with_("effect", amp=256)),
    ("scan", with_("scan", id=-1)), ("hack", with_("hack", hack="nuke")), ("hack", with_("hack", chain=1)),
    ("perks", many("perks", "list", 19)), ("perks", entry("perks", "list", rank=2)), ("perks", entry("perks", "list", id="iron_skin")),
    ("perkfx", with_("perkfx", seconds=31)), ("binds", with_("binds", keys={"hack": 0, "skills": 75, "cyberware": 85, "activate": 89})),
    ("binds", with_("binds", keys={"hack": 71, "skills": 255, "cyberware": 85, "activate": 89})), ("binds", with_("binds", keys={"hack": 71})),
    ("binds", with_("binds", keys={"hack": 71, "skills": 75})), ("binds", with_("binds", keys={"hack": 71, "skills": 75, "cyberware": 85, "activate": 0})),
    ("align", with_("align", k=64)), ("align", with_("align", k=-1)), ("align", with_("align", k=1.5)), ("align", with_("align", k="57")),
    ("align", with_("align", k=True)),
    ("cyberware", entry("cyberware", "list", on=2)), ("cyberware", entry("cyberware", "list", on=True)),
    ("cyberware", entry("cyberware", "list", id="mantis_blade")), ("cyberware", many("cyberware", "list", 33)),
    ("cyberfx", with_("cyberfx", seconds=31)), ("cyberfx", with_("cyberfx", seconds=-1)), ("cyberfx", with_("cyberfx", id="optical_camo")),
    ("cyberfx", with_("cyberfx", seconds=float("nan"))),
    ("flight", with_("flight", kind="fly")), ("flight", with_("flight", ts=-1)), ("flight", with_("flight", ts=1e14)),
    ("flight", with_("flight", ts=float("nan"))), ("flight", with_("flight", ts="81234.5")),
    ("cam", with_("cam", lv=64)), ("cam", with_("cam", lv=-1)), ("cam", with_("cam", lv=1.5)), ("cam", with_("cam", lv="57")),
    ("key", with_("key", k="tab")),
    ("cam", with_("cam", fly=1)), ("key", with_("key", mods=-1)), ("click", with_("click", b=3)), ("click", with_("click", mods="shift")),
    ("char", with_("char", c=0x0D)), ("char", with_("char", c=0xD800)), ("char", with_("char", c=0x110000)),
    ("skey", with_("skey", sc=0)), ("skey", with_("skey", sc=512)),
]


@pytest.mark.parametrize("t, m", OUT_OF_LIMITS, ids=[f"{t}-{i}" for i, (t, _) in enumerate(OUT_OF_LIMITS)])
def test_layer36_validators_reject_out_of_limit_messages(t, m):
    try:
        ok = VALIDATORS[t](m)
    except (KeyError, TypeError):  # a malformed shape is also a rejection
        ok = False
    assert not ok


def test_layer36_limits_are_stated_in_the_document():
    text = section("Layers 3–6 semantics and limits") + section(L36_CP_TO_MC) + section(L36_MC_TO_CP)
    for phrase in ("0..40", "0..2048", "0..16", "0..1", "0..4", "1..32", "0..50", "0..86400", "0..255", "0..30", "1..254",
                   "≤ 12", "≤ 16", "≤ 64", "≤ 5", "≤ 120 Hz", "3e7", "200 m/s", "[a-z0-9_]{1,32}", "[a-z_]{1,32}",
                   "0..63", "1e13", "≤ 32"):  # the last three: re-test 2 (`align.k` and `cam.lv`, `flight.ts`, `cyberware.list`)
        assert phrase in text, f"limit {phrase!r} is not stated"


# ---- re-test 2 (plan 2026-10-03-retest2 sections 1.1, 1.2, 1.7, 1.8): ground alignment, flight kind/ts, cyberware, four binds ----
ALIGN_CASES = [(6.907, 57), (6.001, 63), (0.0, 63), (-3.25, 47), (312.999, 63), (-0.0005, 63), (64.5, 31)]  # plan section 1.1, in every suite


def align_k(z: float) -> int:
    """The plan's rule, written out here so that this test needs nothing but the document: floorMod(floor(frac(z) * 64 - 0.125), 64)."""
    return math.floor((z - math.floor(z)) * 64 - 0.125) % 64


def test_alignment_cases_in_the_document_match_the_rule():
    rows = re.findall(r"^\| `(-?[\d.]+)` \| (\d+) \|$", section("Ground alignment (re-test 2)"), re.M)
    assert [(float(z), int(k)) for z, k in rows] == ALIGN_CASES
    assert all(align_k(z) == k for z, k in ALIGN_CASES)


def test_alignment_rule_leaves_the_floor_a_few_millimetres_above_a_block_boundary():
    """The document claims e in [1.95 mm, 17.6 mm): the aligned floor maps to N + e, so Steve's feet are never inside the barrier."""
    import random
    rng = random.Random(7)
    for _ in range(5000):
        z = rng.uniform(-2000, 2000)
        mapped = z - align_k(z) / 64
        e = mapped - math.floor(mapped)
        assert 0.00195 <= e < 0.0176, (z, e)


def test_alignment_agrees_with_cbmath_when_it_has_the_rule():
    from tools import cbmath
    if not hasattr(cbmath, "align_k"):
        pytest.skip("tools/cbmath.py has no align_k yet (package D)")
    assert all(cbmath.align_k(z) == k for z, k in ALIGN_CASES)


def test_header_and_block_cells_state_the_offset():
    head = TEXT.split("## Wire protocol v1")[0]
    assert "(x, z − off, −y)" in head and "(x, −z, y + off)" in head and "k/64" in head
    assert "by + k/64" in section("Layer 2 semantics and limits")


def test_alignment_section_lists_who_gets_the_offset():
    text = section("Ground alignment (re-test 2)")
    for field in ("cam.p", "cam.pl", "dmg.from", "npcs[].p", "scaninfo.p", "explosion.pos", "teleport.p", "lead.anchor", "pull.toward",
                  "knock.pos", "mobs[].p", "proj[].p", "flight.p", "hit.impulse", "flight.v", "proj[].v"):
        assert field in text, f"{field} is missing from the offset table"
    assert "Minecraft y = Cyberpunk z − off" in text and "`k = 0`" in text


def test_cyberware_example_lists_the_17_ids_in_the_fixed_order_with_flags():
    m = next(m for t, m in L36_EXAMPLES if t == "cyberware")
    assert [e["id"] for e in m["list"]] == CYBERWARE_IDS and {e["on"] for e in m["list"]} == {0, 1}


def test_binds_example_carries_the_four_default_keys():
    m = next(m for t, m in L36_EXAMPLES if t == "binds")
    assert m["keys"] == {"hack": 0x47, "skills": 0x4B, "cyberware": 0x55, "activate": 0x59}


def test_key_examples_show_the_cyberware_and_activate_keys():
    assert {m["k"] for t, m in L36_EXAMPLES if t == "key"} >= {"hack", "cyberware", "activate"}


def test_cam_example_carries_the_alignment_it_was_converted_with():
    cam = next(m for t, m in L36_EXAMPLES if t == "cam")
    assert whole(cam["lv"], 0, 63)


def test_flight_example_is_a_launch_with_a_sample_clock():
    flight = next(m for t, m in L36_EXAMPLES if t == "flight")
    assert flight["kind"] == "launch" and num(flight["ts"], 0, 1e13) and flight["active"] is True


def test_rebinding_rule_covers_four_keys_and_the_all_reset_clash():
    text = " ".join(section("Layers 3–6 semantics and limits").split())  # one line: the document wraps its bullets
    for phrase in ("`cyberware`", "`activate`", "U (85)", "Y (89)", "all four", "a missing name keeps the current key"):
        assert phrase in text, f"{phrase!r} is not in the binds limits"


def test_validation_rules_of_re_test_2_are_written_down():
    text = section("Layers 3–6 semantics and limits")
    for phrase in ("`align.k`", "`cyberware[].id`", "`cyberfx.seconds`", "`flight.ts`", "ignored, not rejected"):
        assert phrase in text, f"{phrase!r} is not in the limits"


def test_flight_row_documents_kind_ts_and_the_new_fly_meaning():
    flight, cam = rows_by_type(L36_MC_TO_CP)["flight"], rows_by_type(L36_CP_TO_MC)["cam"]
    assert "`kind`" in flight and "launch" in flight and "`ts`" in flight and "ignored" in flight
    assert "launch" in cam and "`lv:int`" in cam and "0..63" in cam and "0.5 s" in cam and "Cyberblock_SetFlightFollowing" not in cam


def test_delivery_rules_keep_cyberware_and_drop_cyberfx_while_not_active():
    text = section("Layers 3–6 semantics and limits")
    delivery = re.search(r"\*\*Delivery in the DLL:\*\*.*?(?=\n- \*\*)", text, re.S).group(0)
    assert "`cyberware`" in delivery and "`cyberfx`" in delivery


# ---- re-test 3 (plan 2026-10-03-retest3-polish section 1.1): Minecraft-drawn UI, hack kinds and spreads, seat, six binds ----
R3_CP_TO_MC, R3_MC_TO_CP = "Re-test 3: Cyberpunk → Minecraft", "Re-test 3: Minecraft → Cyberpunk"
R3_CP_EXAMPLES, R3_MC_EXAMPLES = "Re-test 3 examples, Cyberpunk to Minecraft:", "Re-test 3 examples, Minecraft to Cyberpunk:"
R3_KEYS_SECTION = "Re-test 3: keys (six binds)"
R3_NEW_CP = {"hackspread", "stats", "quest", "pins", "prompt", "choices", "sub", "call", "notify", "screen", "reply"}
R3_EXT_CP = {"hackresult", "cam", "hud", "key"}
R3_NEW_MC, R3_EXT_MC = {"uiact", "persp"}, {"binds", "hello", "status"}
R3_EXTENDED = R3_EXT_CP | R3_EXT_MC
R3_OLD_EXAMPLE_OF = {"hackresult": L36_CP_EXAMPLES, "cam": L36_CP_EXAMPLES, "hud": "Cyberpunk → Minecraft:", "key": L36_CP_EXAMPLES,
                     "binds": L36_MC_EXAMPLES, "hello": "Minecraft → Cyberpunk:", "status": "Minecraft → Cyberpunk:"}  # where the old fields are shown

HACK_KINDS, SPREAD_WHY = names("Hack kinds"), names("Spread reasons")
QUEST_KINDS, PIN_KINDS = names("Quest kinds"), names("Pin kinds")
CHOICE_FLAGS, CALL_PHASES, NOTIFY_KINDS = names("Choice flags"), names("Call phases"), names("Notify kinds")
SCREEN_OPEN, PASS_NAMES, REPLY_KINDS = names("Screen open names"), names("Pass-through names"), names("Reply kinds")
UI_ACTIONS, PERSP_MODES = names("UI actions"), names("Perspective modes")
SIX_BINDS = {"hack": 0x47, "skills": 0x4B, "cyberware": 0x55, "activate": 0x59, "arms": 0x4C, "camo": 0x4F}
R3_KEYS = NEW_KEYS | {"arms", "camo"}
REPLY_BYTES = 256 * 1024


def ctext(s, limit) -> bool:
    """A wire string: UTF-8 bytes within the limit, no control characters (the DLL strips them)."""
    return isinstance(s, str) and len(s.encode("utf-8")) <= limit and not any(ord(c) < 0x20 or ord(c) == 0x7F for c in s)


def uint32(x) -> bool:
    return whole(x, 0, 2**32 - 1)


def fields_are(m, fields) -> bool:
    return isinstance(m, dict) and set(m) == fields


def hackresult3_ok(m) -> bool:
    base = whole(m["id"]) and isinstance(m["ok"], bool) and text_ok(m["reason"]) and (not m["ok"] or m["reason"] == "")
    if not m["ok"]:
        return base and not ({"upload", "kind"} & set(m))  # both only matter (and are only sent) when ok
    return base and ("upload" not in m or num(m["upload"], 0, 30)) and ("kind" not in m or m["kind"] in HACK_KINDS)


def stats_ok(m) -> bool:
    return (whole(m["money"], 0) and whole(m["wanted"], 0, 5) and ctext(m["district"], 64)
            and isinstance(m["time"], list) and len(m["time"]) == 2 and whole(m["time"][0], 0, 23) and whole(m["time"][1], 0, 59)
            and (m["speed"] == -1 or num(m["speed"], 0, 1e4)))


def objective_ok(e) -> bool:
    return (fields_are(e, {"text", "cur", "tot", "opt", "on"}) and ctext(e["text"], 256) and whole(e["cur"], 0) and whole(e["tot"], 0)
            and isinstance(e["opt"], bool) and isinstance(e["on"], bool))


def quest_ok(m) -> bool:
    return ctext(m["title"], 128) and m["kind"] in QUEST_KINDS and list_ok(m, "obj", 12, objective_ok) and (m["wp"] is None or vec3(m["wp"]))


def pin_ok(e) -> bool:
    return fields_are(e, {"p", "kind", "tr"}) and vec3(e["p"]) and e["kind"] in PIN_KINDS and isinstance(e["tr"], bool)


def prompt_entry_ok(e) -> bool:
    return (fields_are(e, {"text", "key", "hold", "inactive"}) and ctext(e["text"], 128) and isinstance(e["key"], str) and len(e["key"]) <= 3
            and ctext(e["key"], 12) and isinstance(e["hold"], bool) and isinstance(e["inactive"], bool))


def choice_entry_ok(e) -> bool:
    return fields_are(e, {"text", "f"}) and ctext(e["text"], 256) and isinstance(e["f"], list) and all(f in CHOICE_FLAGS for f in e["f"])


def hub_ok(h) -> bool:
    return (fields_are(h, {"id", "title", "list"}) and whole(h["id"]) and ctext(h["title"], 128)
            and isinstance(h["list"], list) and all(choice_entry_ok(e) for e in h["list"]))


def choices_ok(m) -> bool:
    return (list_ok(m, "hubs", 4, hub_ok) and sum(len(h["list"]) for h in m["hubs"]) <= 12 and whole(m["active"], -1)
            and whole(m["sel"], -1) and (m["left"] == -1 or num(m["left"], 0, 3600)))


def reply_line_ok(e) -> bool:
    return fields_are(e, {"hash", "text", "state"}) and uint32(e["hash"]) and ctext(e["text"], 256) and e["state"] in (0, 1, 2) and not isinstance(e["state"], bool)


def reply_group_ok(g) -> bool:
    return (fields_are(g, {"hash", "title", "kind", "tracked", "done", "lines"}) and uint32(g["hash"]) and ctext(g["title"], 128)
            and g["kind"] in QUEST_KINDS + ["sms"] and isinstance(g["tracked"], bool) and isinstance(g["done"], bool)
            and isinstance(g["lines"], list) and all(reply_line_ok(e) for e in g["lines"]))


def reply_pin_ok(e) -> bool:
    return fields_are(e, {"p", "kind", "name", "i"}) and vec3(e["p"]) and e["kind"] in PIN_KINDS and ctext(e["name"], 64) and whole(e["i"], -1)


def reply_ok(m) -> bool:
    return (m["kind"] in REPLY_KINDS and whole(m["id"]) and list_ok(m, "groups", 128, reply_group_ok) and list_ok(m, "pins", 256, reply_pin_ok)
            and sum(len(g["lines"]) for g in m["groups"]) <= 512 and len(json.dumps(m).encode("utf-8")) <= REPLY_BYTES)


def screen_ok(m) -> bool:
    forms = {"open", "pass"} & set(m)
    return len(forms) == 1 and set(m) == {"t", *forms} and (m["open"] in SCREEN_OPEN if "open" in m else m["pass"] in PASS_NAMES + [""])


def binds6_ok(m) -> bool:
    """Minecraft always sends all six names; a receiver also tolerates a missing one (an older Minecraft build)."""
    return isinstance(m["keys"], dict) and set(m["keys"]) == set(SIX_BINDS) and all(whole(vk, 1, 254) for vk in m["keys"].values())


R3_VALIDATORS = {
    "hackresult": hackresult3_ok,
    "hackspread": lambda m: whole(m["id"]) and uint64_string(m["target"]) and m["why"] in SPREAD_WHY,
    "cam": lambda m: cam_ok(m) and ("seat" not in m or isinstance(m["seat"], bool)),
    "hud": lambda m: m["hidden"] is False,  # the DLL always sends hidden:false now
    "key": lambda m: m["k"] in R3_KEYS and isinstance(m["down"], bool) and mods_ok(m),
    "stats": stats_ok,
    "quest": quest_ok,
    "pins": lambda m: list_ok(m, "list", 32, pin_ok),
    "prompt": lambda m: ctext(m["title"], 128) and list_ok(m, "list", 6, prompt_entry_ok),
    "choices": choices_ok,
    "sub": lambda m: isinstance(m["show"], bool) and ctext(m["key"], 64) and ctext(m["who"], 64) and ctext(m["text"], 256) and num(m["secs"], 0, 30),
    "call": lambda m: m["phase"] in CALL_PHASES and ctext(m["who"], 64) and isinstance(m["audio"], bool) and isinstance(m["reject"], bool),
    "notify": lambda m: m["kind"] in NOTIFY_KINDS and ctext(m["title"], 128) and ctext(m["text"], 256) and num(m["secs"], 0, 30),
    "screen": screen_ok,
    "reply": reply_ok,
    "binds": binds6_ok,
    "hello": lambda m: m["proto"] == 1 and ctext(m["ver"], 32) and 0 < len(m["ver"]),
    "status": lambda m: isinstance(m["pause"], bool) and isinstance(m["screen"], bool),
    "uiact": lambda m: m["a"] in UI_ACTIONS and ctext(m.get("arg", ""), 64) and uint32(m.get("hash", 0)) and whole(m.get("id", 0)),
    "persp": lambda m: m["mode"] in PERSP_MODES,
}
R3_EXAMPLES = [(m["t"], m) for first in (R3_CP_EXAMPLES, R3_MC_EXAMPLES) for m in examples(first)]


def r3_example(t, **changes):
    m = copy.deepcopy(next(m for u, m in R3_EXAMPLES if u == t))
    m.update(changes)
    return m


def r3_nested(t, field, index, **changes):
    m = copy.deepcopy(next(m for u, m in R3_EXAMPLES if u == t))
    m[field][index] = {**m[field][index], **changes}
    return m


def r3_wide(t, key, n):
    m = copy.deepcopy(next(m for u, m in R3_EXAMPLES if u == t))
    m[key] = [m[key][0]] * n
    return m


def test_r3_name_lists_have_the_plan_counts():
    assert HACK_KINDS == ["squad", "crowd", "driver", "camera", "traffic", "distract", "device"]
    assert SPREAD_WHY == ["chain", "spread", "ping", "frenzy"] and QUEST_KINDS == ["main", "side", "gig", "other"]
    assert len(PIN_KINDS) == 9 and len(CHOICE_FLAGS) == 6 and len(CALL_PHASES) == 4 and len(NOTIFY_KINDS) == 12
    assert len(SCREEN_OPEN) == 7 and len(PASS_NAMES) == 14 and len(set(PASS_NAMES)) == 14
    assert len(UI_ACTIONS) == 10 and REPLY_KINDS == ["journal", "phone", "atlas"] and PERSP_MODES == ["first", "back"]
    assert set(SCREEN_OPEN) == {"pause", "map", "journal", "phone", "inventory", "skills", "cyberware"}
    assert set(PASS_NAMES) == {"vendor", "ripperdoc", "stash", "wardrobe", "crafting", "breach", "arcade", "braindance", "tutorial",
                               "settings", "saves", "death", "loading", "other"}


def test_r3_every_type_of_both_tables_has_an_example_and_a_validator():
    for heading, first, new, ext in ((R3_CP_TO_MC, R3_CP_EXAMPLES, R3_NEW_CP, R3_EXT_CP), (R3_MC_TO_CP, R3_MC_EXAMPLES, R3_NEW_MC, R3_EXT_MC)):
        assert documented(heading) == new | ext
        assert {m["t"] for m in examples(first)} == new | ext
        for t in ext:
            assert "**extends layer" in rows_by_type(heading)[t], f"{t} is not marked as an extension"
    assert {t for t, _ in R3_EXAMPLES} == set(R3_VALIDATORS)


def test_r3_every_example_field_is_named_in_its_table_row():
    for heading, first in ((R3_CP_TO_MC, R3_CP_EXAMPLES), (R3_MC_TO_CP, R3_MC_EXAMPLES)):
        rows = rows_by_type(heading)
        for m in examples(first):
            inherited = set(example(R3_OLD_EXAMPLE_OF[m["t"]], m["t"])) if m["t"] in R3_EXTENDED else set()  # an extension row names what it adds
            for key in set(m) - {"t"} - inherited:
                assert re.search(rf"\b{key}\b", rows[m["t"]]), f"{m['t']}.{key} is not described in its re-test 3 row"


@pytest.mark.parametrize("t, m", R3_EXAMPLES, ids=[f"{t}-{i}" for i, (t, _) in enumerate(R3_EXAMPLES)])
def test_r3_example_obeys_the_documented_limits(t, m):
    assert R3_VALIDATORS[t](m)


def test_r3_screen_key_and_reply_show_all_their_forms():
    screens = [m for t, m in R3_EXAMPLES if t == "screen"]
    assert [("open" in m, "pass" in m) for m in screens] == [(True, False), (False, True)]
    assert {m["k"] for t, m in R3_EXAMPLES if t == "key"} == {"arms", "camo"}
    assert {m["kind"] for t, m in R3_EXAMPLES if t == "reply"} == set(REPLY_KINDS)


def test_r3_extended_examples_keep_their_old_fields():
    cam = next(m for t, m in R3_EXAMPLES if t == "cam")
    assert set(cam) == set(example("Cyberpunk → Minecraft:", "cam")) | CAM_ADDED | {"seat"} and cam["fp"] is False and cam["seat"] is True
    result = next(m for t, m in R3_EXAMPLES if t == "hackresult")
    assert set(result) == set(next(m for t, m in L36_EXAMPLES if t == "hackresult")) | {"upload", "kind"}
    hello = next(m for t, m in R3_EXAMPLES if t == "hello")
    assert set(hello) == set(example("Minecraft → Cyberpunk:", "hello")) | {"ver"}
    status = next(m for t, m in R3_EXAMPLES if t == "status")
    assert set(status) == set(example("Minecraft → Cyberpunk:", "status")) | {"pause"}


def test_r3_binds_example_carries_the_six_default_keys():
    assert next(m for t, m in R3_EXAMPLES if t == "binds")["keys"] == SIX_BINDS


def test_r3_choices_example_has_a_timed_entry_and_a_listed_active_hub():
    m = next(m for t, m in R3_EXAMPLES if t == "choices")
    flags = {f for h in m["hubs"] for e in h["list"] for f in e["f"]}
    assert "timed" in flags and m["active"] in {h["id"] for h in m["hubs"]} and 0 <= m["sel"] < sum(len(h["list"]) for h in m["hubs"])


def test_r3_journal_example_shows_a_hash_above_the_signed_32_bit_range():
    """Hashes are uint32 JSON numbers: a value above 2**31 is legal and must not be read as a signed int."""
    journal = next(m for t, m in R3_EXAMPLES if t == "reply" and m["kind"] == "journal")
    assert journal["groups"][0]["hash"] > 2**31


R3_OUT_OF_LIMITS = [
    ("hackresult", r3_example("hackresult", upload=31)), ("hackresult", r3_example("hackresult", upload=-1)),
    ("hackresult", r3_example("hackresult", upload=float("nan"))), ("hackresult", r3_example("hackresult", kind="swarm")),
    ("hackresult", r3_example("hackresult", ok=False, reason="no driver")), ("hackresult", r3_example("hackresult", ok=False, reason="")),
    ("hackspread", r3_example("hackspread", why="explode")), ("hackspread", r3_example("hackspread", target=5)),
    ("hackspread", r3_example("hackspread", id=-1)),
    ("cam", r3_example("cam", seat="yes")), ("cam", r3_example("cam", lv=64)),
    ("hud", r3_example("hud", hidden=True)),
    ("key", r3_example("key", k="tab")),
    ("stats", r3_example("stats", money=-1)), ("stats", r3_example("stats", wanted=6)), ("stats", r3_example("stats", district="d" * 65)),
    ("stats", r3_example("stats", time=[24, 0])), ("stats", r3_example("stats", time=[12, 60])), ("stats", r3_example("stats", time=[12])),
    ("stats", r3_example("stats", speed=-2)), ("stats", r3_example("stats", district="bad\x07")),
    ("quest", r3_example("quest", title="t" * 129)), ("quest", r3_example("quest", kind="epic")), ("quest", r3_example("quest", wp=[1, 2])),
    ("quest", r3_example("quest", obj=[r3_nested("quest", "obj", 0)["obj"][0]] * 13)),
    ("quest", r3_nested("quest", "obj", 0, text="x" * 257)), ("quest", r3_nested("quest", "obj", 0, cur=-1)),
    ("quest", r3_nested("quest", "obj", 0, on="yes")),
    ("pins", r3_wide("pins", "list", 33)), ("pins", r3_nested("pins", "list", 0, kind="shop")), ("pins", r3_nested("pins", "list", 0, p=[0, 0])),
    ("prompt", r3_example("prompt", title="t" * 129)), ("prompt", r3_wide("prompt", "list", 7)),
    ("prompt", r3_nested("prompt", "list", 0, key="Enter")), ("prompt", r3_nested("prompt", "list", 0, text="t" * 129)),
    ("choices", r3_example("choices", sel=-2)), ("choices", r3_example("choices", left=-2)),
    ("choices", r3_example("choices", hubs=[r3_example("choices")["hubs"][0]] * 5)),
    ("choices", r3_example("choices", hubs=[{"id": 1, "title": "t", "list": [{"text": "x", "f": []}] * 13}])),
    ("choices", r3_example("choices", hubs=[{"id": 1, "title": "t", "list": [{"text": "x", "f": ["secret"]}]}])),
    ("choices", r3_example("choices", hubs=[{"id": 1, "title": "t", "list": [{"text": "x" * 257, "f": []}]}])),
    ("sub", r3_example("sub", secs=31)), ("sub", r3_example("sub", key="k" * 65)), ("sub", r3_example("sub", text="t" * 257)),
    ("sub", r3_example("sub", who="w" * 65)), ("sub", r3_example("sub", show=1)),
    ("call", r3_example("call", phase="ring")), ("call", r3_example("call", who="w" * 65)), ("call", r3_example("call", reject=0)),
    ("notify", r3_example("notify", kind="badge")), ("notify", r3_example("notify", secs=31)), ("notify", r3_example("notify", title="t" * 129)),
    ("notify", r3_example("notify", text="é" * 129)),  # 258 bytes: the limits are UTF-8 bytes, not characters
    ("screen", {"t": "screen", "open": "settings"}), ("screen", {"t": "screen", "pass": "shop"}), ("screen", {"t": "screen"}),
    ("screen", {"t": "screen", "open": "pause", "pass": "vendor"}),
    ("reply", r3_example("reply", kind="mail")), ("reply", r3_example("reply", id=-1)),
    ("reply", r3_example("reply", kind="atlas", groups=[{"hash": 1, "title": "g", "kind": "main", "tracked": False, "done": False, "lines": []}] * 129)),
    ("reply", r3_nested("reply", "groups", 0, kind="epic")), ("reply", r3_nested("reply", "groups", 0, hash=2**32)),
    ("reply", r3_nested("reply", "groups", 0, lines=[{"hash": 1, "text": "x", "state": 3}])),
    ("reply", r3_nested("reply", "groups", 0, lines=[{"hash": 1, "text": "x", "state": 0}] * 513)),
    ("reply", r3_nested("reply", "groups", 0, lines=[{"hash": 1, "text": "x" * 257, "state": 0}])),
    ("reply", r3_example("reply", pins=[{"p": [0, 0, 0], "kind": "quest", "name": "n", "i": -1}] * 257)),
    ("reply", r3_example("reply", pins=[{"p": [0, 0, 0], "kind": "quest", "name": "n" * 65, "i": -1}])),
    ("reply", r3_example("reply", pins=[{"p": [0, 0, 0], "kind": "shop", "name": "n", "i": -1}])),
    ("reply", r3_example("reply", groups=[{"hash": 1, "title": "g", "kind": "main", "tracked": False, "done": False,
                                            "lines": [{"hash": 1, "text": "x" * 256, "state": 0}] * 300}] * 2)),  # 600 lines, over 512
    ("binds", r3_example("binds", keys={**SIX_BINDS, "arms": 0})), ("binds", r3_example("binds", keys={**SIX_BINDS, "camo": 255})),
    ("binds", r3_example("binds", keys={"hack": 71, "skills": 75, "cyberware": 85, "activate": 89})),
    ("binds", r3_example("binds", keys={**SIX_BINDS, "wheel": 80})),
    ("hello", r3_example("hello", ver="")), ("hello", r3_example("hello", ver="v" * 33)), ("hello", r3_example("hello", proto=2)),
    ("status", r3_example("status", pause="yes")),
    ("uiact", r3_example("uiact", a="quit")), ("uiact", r3_example("uiact", arg="a" * 65)), ("uiact", r3_example("uiact", hash=-1)),
    ("uiact", r3_example("uiact", hash=2**32)), ("uiact", r3_example("uiact", id=-1)),
    ("persp", r3_example("persp", mode="front")), ("persp", r3_example("persp", mode=None)),
]


@pytest.mark.parametrize("t, m", R3_OUT_OF_LIMITS, ids=[f"{t}-{i}" for i, (t, _) in enumerate(R3_OUT_OF_LIMITS)])
def test_r3_validators_reject_out_of_limit_messages(t, m):
    try:
        ok = R3_VALIDATORS[t](m)
    except (KeyError, TypeError):
        ok = False
    assert not ok


def test_r3_validators_accept_the_limits_themselves():
    assert R3_VALIDATORS["hackresult"](r3_example("hackresult", upload=0)) and R3_VALIDATORS["hackresult"](r3_example("hackresult", upload=30))
    assert R3_VALIDATORS["hackresult"]({"t": "hackresult", "id": 1, "ok": True, "reason": ""})  # upload and kind are optional
    assert R3_VALIDATORS["hackresult"]({"t": "hackresult", "id": 1, "ok": False, "reason": "target gone"})
    assert R3_VALIDATORS["stats"](r3_example("stats", wanted=5, time=[23, 59], speed=0, money=0, district="é" * 32))
    assert R3_VALIDATORS["prompt"](r3_example("prompt", title="", list=[]))  # an empty list clears
    assert R3_VALIDATORS["choices"](r3_example("choices", hubs=[], active=-1, sel=-1, left=-1))
    assert R3_VALIDATORS["choices"](r3_example("choices", hubs=[{"id": 1, "title": "t", "list": [{"text": "x", "f": []}] * 12}]))
    assert R3_VALIDATORS["sub"](r3_example("sub", secs=0, text="é" * 128)) and R3_VALIDATORS["sub"](r3_example("sub", secs=30))
    assert R3_VALIDATORS["screen"]({"t": "screen", "pass": ""})  # the pass-through ended
    assert R3_VALIDATORS["uiact"]({"t": "uiact", "a": "save"}) and R3_VALIDATORS["uiact"](r3_example("uiact", hash=2**32 - 1))
    assert R3_VALIDATORS["quest"](r3_example("quest", wp=None))


def test_r3_limits_and_rules_are_stated_in_the_document():
    text = " ".join((section("Re-test 3 semantics and limits") + section(R3_CP_TO_MC) + section(R3_MC_TO_CP) + section(R3_KEYS_SECTION)).split())
    for phrase in ("0..30", "0..5", "[0..23, 0..59]", "≤ 12", "≤ 16", "≤ 32", "≤ 128", "≤ 256 KB", "≤ 512", "≤ 6", "≤ 4", "≤ 64", "150 m",
                   "UTF-8 bytes", "uint32", "level-triggered", "status.pause", "hidden:false", "cam.seat", "all six", "last 32 request ids",
                   "unknown id is dropped", "`other`", "invalid for"):
        assert phrase in text, f"{phrase!r} is not stated"


def test_r3_keys_section_lists_six_binds_with_vk_codes_and_the_clash_rule():
    body = section(R3_KEYS_SECTION)
    rows = {name: (key, int(vk)) for key, vk, name in re.findall(r"^\| [A-Za-z ]+ \| (\w) \| (\d+) \| `(\w+)` \|$", body, re.M)}
    assert rows == {name: (chr(vk), vk) for name, vk in SIX_BINDS.items()}
    text = " ".join(body.replace("**", "").split())
    for phrase in ("all six go back to G K U Y L O", "its own default", "binds_apply", "key arms", "key camo", "unbound once", "no tap and hold cycling"):
        assert phrase in text, f"{phrase!r} is missing"


def test_r3_knockback_tiers_are_written_down():
    text = " ".join(section("Re-test 3 semantics and limits").split())
    for phrase in ("(0.4 + extra) × 5", "plain hit is 2 m/s", "4.5", "9.5", "12", "s < 4.0", "6.5", "10.0", "knockdown"):
        assert phrase in text, f"{phrase!r} is missing from the knockback rule"


def test_r3_older_sections_point_at_the_new_keys_and_hud_rule():
    assert "Re-test 3 supersedes the tap and hold cycling" in section("Cyberware and keys (re-test 2)")
    assert "always sends `hidden:false`" in section(CP_TO_MC)


# ---- the player documents agree with the protocol (re-test 3): keys, name lists and links ----
GUIDE = Path("docs/HOW-TO-PLAY.md").read_text(encoding="utf-8")
README = Path("README.md").read_text(encoding="utf-8")


def slug(heading: str) -> str:
    """GitHub's heading anchor: lower case, punctuation dropped, spaces to hyphens."""
    return re.sub(r"\s", "-", re.sub(r"[^\w\s-]", "", heading.strip().lower()))


def anchors(doc: str) -> set[str]:
    return {slug(m.group(2)) for m in re.finditer(r"^(#{1,6}) (.+)$", doc, re.M)}


def test_guide_anchor_links_resolve():
    found = anchors(GUIDE)
    for target in re.findall(r"\]\(#([^)]+)\)", GUIDE):
        assert target in found, f"HOW-TO-PLAY.md links to a missing heading #{target}"


def test_readme_links_into_the_guide_resolve():
    found = anchors(GUIDE)
    for target in re.findall(r"\]\(docs/HOW-TO-PLAY\.md#([^)]+)\)", README):
        assert target in found, f"README.md links to a missing guide heading #{target}"
    for rel in re.findall(r"\]\(((?:docs|shared)/[^)#]+)", README):
        assert Path(rel).exists(), f"README.md links to a missing file {rel}"


def test_guide_documents_the_six_keys_and_the_allowed_set_of_the_protocol():
    text = " ".join(GUIDE.split())
    for key in ("**Y**", "**L**", "**O**", "**F5**", "**U**", "**G**", "**K**"):
        assert key in text
    assert "G K L O U Y 0 F1-F4 F6-F8 F10-F12" in text and "all six go back to G, K, U, Y, L and O" in text
    protocol = " ".join(TEXT.split())
    assert "G K L O U Y 0 F1–F4 F6–F8 F10–F12" in protocol and "all six" in protocol  # the same set, spelled with en dashes there
    assert "binds: hack G, skills K, cyberware U, activate Y, arms L, camo O" in GUIDE


def test_guide_names_every_pass_through_menu_and_the_ui_actions_it_documents():
    text = GUIDE.lower()
    for menu in ("shops and vendors", "ripperdoc", "stash", "wardrobe", "crafting benches", "breach protocol", "arcade", "braindance editor",
                 "tutorial and shard popups", "settings", "save and load lists", "death screen", "loading screens"):
        assert menu in text, f"HOW-TO-PLAY.md does not mention the pass-through menu {menu!r}"
    assert "font" in text and "wolvenkit" in text  # the documented limitation
    for button in ("Back to Night City", "Save Game", "Load Last Save", "Night City Menu...", "Quit to Cyberpunk Main Menu"):
        assert button in GUIDE


def test_guide_quickhack_table_covers_all_ten_chips_with_their_extras():
    table = GUIDE.split("#### What each chip does", 1)[1].split("**Other cyber items:**", 1)[0]
    for chip in ("Redstone Charge", "Blaze Chip", "Ink Sac Daemon", "Cobweb Daemon", "Spider Eye Virus", "Wither Skull Daemon", "Compass Ping",
                 "Bottle of Forgetting", "Creeper Brain", "Redstone Lever"):
        assert f"**{chip}**" in table, chip
    for fact in ("real cobwebs", "6 s", "5 s", "4 s", "2 nearest", "35 %", "32 blocks", "30 m", "2 s", "6 s", "creeper face"):
        assert fact in table, fact


# ---- Atlas (explored map and survey tour): `terrain`, `surveystate`, the atlas `reply` extension and `survey` ----------
AT_CP_TO_MC, AT_MC_TO_CP = "Atlas: Cyberpunk → Minecraft", "Atlas: Minecraft → Cyberpunk"
AT_CP_EXAMPLES, AT_MC_EXAMPLES = "Atlas examples, Cyberpunk to Minecraft:", "Atlas examples, Minecraft to Cyberpunk:"
SURVEY_STATES, SURVEY_OPS = names("Survey states"), names("Survey ops")


def terrain_ok(m) -> bool:
    c = m["c"]
    if not (isinstance(c, list) and len(c) % 4 == 0 and 0 < len(c) <= 4 * 64 and all(whole(v, -2**31, 2**31 - 1) for v in c)):
        return False
    return all(abs(c[i]) < 1.5e7 and abs(c[i + 1]) < 1.5e7 and abs(c[i + 2]) <= 20000 and c[i + 3] in (0, 1) for i in range(0, len(c), 4))


def surveystate_ok(m) -> bool:
    return (m["state"] in SURVEY_STATES and whole(m["done"]) and whole(m["total"]) and m["done"] <= m["total"]
            and (m["eta"] == -1 or num(m["eta"], 0, 1e7)) and ctext(m["reason"], 64)
            and ("stop" not in m or (isinstance(m["stop"], list) and len(m["stop"]) == 2 and all(whole(v, -100000, 100000) for v in m["stop"]))))


def atlas_pin_ok(e) -> bool:
    base = {"p", "kind", "name", "i"}
    return (set(e) in (base, base | {"d"}) and vec3(e["p"]) and e["kind"] in PIN_KINDS and ctext(e["name"], 64) and whole(e["i"], -1)
            and ("d" not in e or ctext(e["d"], 64)))


def atlas_reply_ok(m) -> bool:
    return (m["kind"] == "atlas" and whole(m["id"]) and m["groups"] == [] and list_ok(m, "pins", 1024, atlas_pin_ok)
            and len(json.dumps(m).encode("utf-8")) <= REPLY_BYTES)


def survey_ok(m) -> bool:
    if m["op"] not in SURVEY_OPS or ("done" in m and m["op"] != "start"):
        return False
    done = m.get("done", [])
    return isinstance(done, list) and len(done) % 2 == 0 and len(done) <= 2 * 4096 and all(whole(v, -100000, 100000) for v in done)


AT_VALIDATORS = {"terrain": terrain_ok, "surveystate": surveystate_ok, "reply": atlas_reply_ok, "survey": survey_ok}
AT_EXAMPLES = [(m["t"], m) for first in (AT_CP_EXAMPLES, AT_MC_EXAMPLES) for m in examples(first)]


def at_example(t, **changes):
    m = copy.deepcopy(next(m for u, m in AT_EXAMPLES if u == t))
    m.update(changes)
    return m


def test_atlas_name_lists():
    assert SURVEY_STATES == ["running", "paused", "done", "stopped", "refused", "idle"] and SURVEY_OPS == ["start", "stop", "status"]


def test_atlas_every_type_has_a_row_an_example_and_a_validator():
    assert documented(AT_CP_TO_MC) == {"terrain", "surveystate", "reply"} and documented(AT_MC_TO_CP) == {"survey"}
    assert {m["t"] for m in examples(AT_CP_EXAMPLES)} == documented(AT_CP_TO_MC)
    assert {m["t"] for m in examples(AT_MC_EXAMPLES)} == documented(AT_MC_TO_CP)
    assert "**extends re-test 3:**" in rows_by_type(AT_CP_TO_MC)["reply"]
    assert {t for t, _ in AT_EXAMPLES} == set(AT_VALIDATORS)


def test_atlas_every_example_field_is_named_in_its_row():
    for heading, first in ((AT_CP_TO_MC, AT_CP_EXAMPLES), (AT_MC_TO_CP, AT_MC_EXAMPLES)):
        rows = rows_by_type(heading)
        for m in examples(first):
            inherited = set(example(R3_CP_EXAMPLES, "reply")) if m["t"] == "reply" else set()
            for key in set(m) - {"t"} - inherited:
                assert re.search(rf"\b{key}\b", rows[m["t"]]), f"{m['t']}.{key} is not described in its Atlas row"
    reply = next(m for t, m in AT_EXAMPLES if t == "reply")
    assert any("d" in p for p in reply["pins"]) and any("d" not in p for p in reply["pins"])  # d is optional


@pytest.mark.parametrize("t, m", AT_EXAMPLES, ids=[f"{t}-{i}" for i, (t, _) in enumerate(AT_EXAMPLES)])
def test_atlas_example_obeys_the_documented_limits(t, m):
    assert AT_VALIDATORS[t](m)


def test_atlas_terrain_example_matches_cbmath():
    """The example's first cell is the survey cell of a real hit (tools/cbmath.py survey_cell at k 0)."""
    from tools import cbmath
    m = next(m for t, m in AT_EXAMPLES if t == "terrain")
    assert list(cbmath.survey_cell(-1245.5, 812.25, 66.62)) == m["c"][:3] and m["c"][3] == 0 and m["c"][-1] == 1


AT_OUT_OF_LIMITS = [
    ("terrain", at_example("terrain", c=[1, 2, 3])), ("terrain", at_example("terrain", c=[1, 2, 3, 2])),
    ("terrain", at_example("terrain", c=[1, 2, 20001, 0])), ("terrain", at_example("terrain", c=[15_000_000, 2, 3, 0])),
    ("terrain", at_example("terrain", c=[1.5, 2, 3, 0])), ("terrain", at_example("terrain", c=[1, 2, 3, 0] * 65)),
    ("terrain", at_example("terrain", c=[])),
    ("surveystate", at_example("surveystate", state="flying")), ("surveystate", at_example("surveystate", done=981)),
    ("surveystate", at_example("surveystate", done=-1)), ("surveystate", at_example("surveystate", reason="r" * 65)),
    ("surveystate", at_example("surveystate", stop=[1])), ("surveystate", at_example("surveystate", eta=-2)),
    ("reply", at_example("reply", pins=[{"p": [0, 0, 0], "kind": "quest", "name": "n", "i": -1, "d": "d" * 65}])),
    ("reply", at_example("reply", pins=[{"p": [0, 0, 0], "kind": "quest", "name": "n", "i": -1}] * 1025)),
    ("reply", at_example("reply", pins=[{"p": [0, 0, 0], "kind": "quest", "name": "n", "i": -1, "x": 1}])),
    ("survey", at_example("survey", op="pause")), ("survey", at_example("survey", done=[1, 2, 3])),
    ("survey", at_example("survey", done=[1, 200000])), ("survey", at_example("survey", done=[0, 0] * 4097)),
    ("survey", {"t": "survey", "op": "stop", "done": [1, 2]}),
]


@pytest.mark.parametrize("t, m", AT_OUT_OF_LIMITS, ids=[f"{t}-{i}" for i, (t, _) in enumerate(AT_OUT_OF_LIMITS)])
def test_atlas_validators_reject_out_of_limit_messages(t, m):
    try:
        ok = AT_VALIDATORS[t](m)
    except (KeyError, TypeError):
        ok = False
    assert not ok


def test_atlas_validators_accept_the_limits_themselves():
    assert terrain_ok(at_example("terrain", c=[-14_999_999, 14_999_999, -20000, 1] * 64))
    assert surveystate_ok(at_example("surveystate", done=980, eta=-1)) and surveystate_ok({**at_example("surveystate"), "state": "idle"})
    assert surveystate_ok({k: v for k, v in at_example("surveystate").items() if k != "stop"})  # stop is optional
    assert survey_ok({"t": "survey", "op": "status"}) and survey_ok({"t": "survey", "op": "start"})
    assert atlas_reply_ok(at_example("reply", pins=[{"p": [0, 0, 0], "kind": "fasttravel", "name": "n", "i": -1, "d": "Westbrook"}] * 1024))


def test_atlas_rules_are_stated_in_the_document():
    text = " ".join((section("Atlas semantics and limits") + section(AT_CP_TO_MC) + section(AT_MC_TO_CP)).split())
    for phrase in ("2-m cell", "survey_cell(x, y, z, off)", "survey_column(cx, cz)", "`survey` and `survey_column` sets", "at most 64 cells",
                   "48 more rays", "300 s", "20 s after a miss", "Water-only", "66 to 96 m", "time budget", "12 minutes", "stops after 15",
                   "200 m apart", "8 pins", "convex hull", "outside the Badlands", "Open sea beyond them is never visited", "150 m",
                   "2, 4, 8 or 10 m", "8, 10 or 20 m", "3 × 3", "0.5 s", "2 s at most", "150 to 600 m", "flies up to", "one retry",
                   "invisible", "invulnerable", "saving is locked", "put back exactly", "menu pauses", "90 %", "never superseded", "1024",
                   "4096 pairs"):
        assert phrase in text, f"{phrase!r} is not stated"


def test_atlas_survey_constants_agree_with_the_dll():
    hpp = Path("cp/plugin/src/survey.hpp").read_text(encoding="utf-8")
    for name, value in (("kRadius", "96.0"), ("kRefreshSeconds", "300.0"), ("kMissRetrySeconds", "20.0"), ("kCellsPerMessage", "64"),
                        ("kTourSpacing", "200.0"), ("kTourHalf", "100.0"), ("kTourHoldZ", "150.0"), ("kTourHoldAbove", "25.0"),
                        ("kProbeStep", "60.0"), ("kSettleStable", "0.5"), ("kSettleTimeout", "2.0"), ("kMinPins", "8"), ("kMinCityPoints", "6"),
                        ("kPinJoinRadius", "800.0"), ("kCityMargin", "300.0"), ("kOutskirtsMargin", "300.0"), ("kWorldLimit", "8000.0"),
                        ("kMaxDone", "4096"), ("kPlanSeconds", "720.0"), ("kBudgetSeconds", "900.0"), ("kSquareCovered", "0.9"),
                        ("kFrameTargetMs", "40.0"), ("kMinBudgetMs", "2.0"), ("kMaxBudgetMs", "25.0"), ("kVehicleBudgetMs", "1.5"),
                        ("kVehicleMinRadius", "66.0"), ("kMaxDrift", "150.0"), ("kReachHits", "10"), ("kReachRays", "12"),
                        ("kRefineShare", "0.85")):
        assert re.search(rf"\b{name} = {re.escape(value)};", hpp), f"survey.hpp {name} is not {value}"


def test_guide_documents_the_atlas_and_the_one_time_survey():
    text = " ".join(GUIDE.split())
    for phrase in ("#### The Atlas (map)", "Survey once, and every world and save uses it.", "`/cyberblock survey`", "`/cyberblock survey redo`",
                   "/cyberblock survey stop", "/cyberblock survey status", "put back exactly where V stood", "nightcity-map",
                   "Quests, Travel, Shops, Gigs, Places", "**Track**"):
        assert phrase in text, f"HOW-TO-PLAY.md does not say {phrase!r}"
    assert "Nothing of Cyberpunk's own map art is used" in text


# ---- Phase Q (third-person view and step-up assist): `prefs` and `cam.vdbg` (plan 2026-10-04 megaplan section 1.9) ----------
PQ_CP_TO_MC, PQ_MC_TO_CP = "Phase Q: Cyberpunk → Minecraft", "Phase Q: Minecraft → Cyberpunk"
PQ_CP_EXAMPLES, PQ_MC_EXAMPLES = "Phase Q examples, Cyberpunk to Minecraft:", "Phase Q examples, Minecraft to Cyberpunk:"
PREFS_FIELDS = {"dist", "shoulder", "fov", "recenter", "vdist", "stepup", "debug"}
PREFS_DEFAULTS = {"dist": 3.2, "shoulder": 0.45, "fov": 0, "recenter": True, "vdist": 0, "stepup": True, "debug": False}  # section 1.9 / Q-D1 (+ debug)


def zero_or(x, lo, hi) -> bool:
    return x == 0 or num(x, lo, hi)


def prefs_ok(m) -> bool:
    """What Minecraft may send: all seven fields, each inside its range (the DLL clamps instead of dropping)."""
    return (set(m) - {"t"} == PREFS_FIELDS and num(m["dist"], 1.5, 8) and num(m["shoulder"], -1, 1) and zero_or(m["fov"], 50, 110)
            and isinstance(m["recenter"], bool) and zero_or(m["vdist"], 3, 14) and isinstance(m["stepup"], bool) and isinstance(m["debug"], bool))


def vdbg_ok(v) -> bool:
    return isinstance(v, list) and len(v) == 3 and num(v[0], 0, 100) and num(v[1], 0, 100) and num(v[2], 0, 60000)


def cam_vdbg_ok(m) -> bool:
    """A `cam` is the re-test 3 `cam` plus an optional `vdbg` (a bad `vdbg` is ignored by the receiver, so the validator of the
    whole message only judges the part that is there: a `cam` with a good or no `vdbg`)."""
    return R3_VALIDATORS["cam"](m) and ("vdbg" not in m or vdbg_ok(m["vdbg"]))


PQ_VALIDATORS = {"cam": cam_vdbg_ok, "prefs": prefs_ok}
PQ_EXAMPLES = [(m["t"], m) for first in (PQ_CP_EXAMPLES, PQ_MC_EXAMPLES) for m in examples(first)]


def pq_example(t, **changes):
    m = copy.deepcopy(next(m for u, m in PQ_EXAMPLES if u == t))
    m.update(changes)
    return m


# ---- Multiplayer (Phase 1): the class tables, the new messages, the payloads and the rate limits ----------------------
# Plan 2026-10-04-multiplayer-megaplan sections 1.2 (classes and new messages), 1.3 (payloads), 1.5 (handshake), 1.6 (limits).
MP_CP, MP_MC = "Multiplayer: Cyberpunk → Minecraft classes", "Multiplayer: Minecraft → Cyberpunk classes"
MP_NEW, MP_PAYLOADS = "Multiplayer: new messages", "Multiplayer: payloads (client ↔ server)"
MP_SECURITY, MP_SESSIONS = "Multiplayer: security, rate limits and guest toggles", "Multiplayer: handshake and sessions"
MP_CP_EXAMPLES, MP_MC_EXAMPLES = "Multiplayer examples, Cyberpunk to Minecraft:", "Multiplayer examples, Minecraft to Cyberpunk:"
MP_UP_EXAMPLES = "Multiplayer examples, client to server (`cyberblock:up`):"
MP_DOWN_EXAMPLES = "Multiplayer examples, server to client only (`cyberblock:down`):"
SESSION_STATES, ROLES, SURVEY_CMD_OPS = names("Session states"), names("Roles"), names("Survey commands")
PAYLOAD_IDS, GUEST_TOGGLES = names("Payload ids"), names("Guest toggles")
HSTATE_FLAGS = ("active", "vehicle", "air", "cr", "held", "fly", "launch", "seat")
SESSION_REASON_BYTES = 128

MP_CP_CLASSES = {
    **dict.fromkeys(("hello", "state", "aim", "npcs", "stats"), "BOTH"),
    **dict.fromkeys(("ground", "unground", "clear", "dmg", "mobdmg", "pcells", "unlead", "killed", "scaninfo", "hackresult", "hackspread"), "UP"),
    **dict.fromkeys(("cam", "view", "hud", "key", "slot", "scroll", "cursor", "click", "char", "skey", "look", "quest", "pins", "prompt",
                     "choices", "sub", "call", "notify", "screen", "reply", "terrain", "surveystate", "cmd"), "LOCAL"),
}
MP_MC_CLASSES = {
    **dict.fromkeys(("hello", "status", "binds", "uiact", "persp", "flight", "survey", "prefs"), "LOCAL"),
    **dict.fromkeys(("block", "blocks", "align", "explosion", "knock", "hit", "ignite", "lead", "pull", "mobs", "proj", "teleport", "effect",
                     "scan", "hack", "perks", "cyberware", "cyberfx", "pteleport"), "DOWN"),
}
MP_RATES = {  # plan section 1.6: type -> (tokens per second, burst)
    **dict.fromkeys(("ground", "unground", "clear"), (40, 120)), "npcs": (15, 30), "dmg": (20, 40), "aim": (15, 30),
    **dict.fromkeys(("hstate", "state"), (10, 20)), **dict.fromkeys(("mobdmg", "pcells"), (30, 60)),
    **dict.fromkeys(("scaninfo", "hackresult", "hackspread"), (40, 80)), **dict.fromkeys(("killed", "unlead"), (10, 20)),
    "stats": (2, 4), "terrain": (30, 200), "hello": (1, 3), **dict.fromkeys(("devices", "devstate", "devresult"), (10, 40)),
}
MP_PAYLOADS_EXPECTED = {  # plan section 1.3: id -> direction
    "hs": "C2S", "hs_ack": "S2C", "up": "C2S", "down": "S2C", "roster": "S2C", "perms": "C2S", "content": "S2C", "act": "C2S", "ui": "S2C",
}


def class_rows(heading: str) -> list[tuple[str, str]]:
    return re.findall(r"^\| `(\w+)` \| (LOCAL|UP|DOWN|BOTH) \|", section(heading), re.M)


def rate_rows() -> dict[str, tuple[int, int]]:
    rows = {}
    for m in re.finditer(r"^\| ((?:`\w+`(?:, )?)+) \| (\d+) \| (\d+) \|", section(MP_SECURITY), re.M):
        rows.update({t: (int(m.group(2)), int(m.group(3))) for t in re.findall(r"`(\w+)`", m.group(1))})
    return rows


def payload_rows() -> dict[str, str]:
    return {m.group(1): m.group(2) for m in re.finditer(r"^\| `cyberblock:(\w+)` \| (C2S|S2C) \|", section(MP_PAYLOADS), re.M)}


def mp_hello_ok(m) -> bool:
    """The DLL's `hello` gains `ver` (the DLL version, 1..32 chars)."""
    return (set(m) == {"t", "proto", "game", "build", "ver"} and m["proto"] == 1 and m["game"] == "cp2077" and isinstance(m["build"], str)
            and isinstance(m["ver"], str) and 1 <= len(m["ver"]) <= 32)


def hstate_ok(m) -> bool:
    return (set(m) - {"feetCpZ"} == {"t", *HSTATE_FLAGS, "lv"} and all(isinstance(m[f], bool) for f in HSTATE_FLAGS) and whole(m["lv"], 0, 63)
            and ("feetCpZ" not in m or (num(m["feetCpZ"], -3e7, 3e7) and abs(m["feetCpZ"]) < 3e7)))


def session_ok(m) -> bool:
    perms = m["perms"]
    return (set(m) == {"t", "state", "reason", "perms"} and m["state"] in SESSION_STATES and isinstance(m["reason"], str)
            and len(m["reason"].encode("utf-8")) <= SESSION_REASON_BYTES and isinstance(perms, dict) and list(perms) == GUEST_TOGGLES
            and all(isinstance(v, bool) for v in perms.values()))


MP_VALIDATORS = {
    "hello": mp_hello_ok,
    "hstate": hstate_ok,
    "session": session_ok,
    "pteleport": lambda m: set(m) == {"t", "pos"} and vec3(m["pos"]),
    "survey_cmd": lambda m: set(m) == {"t", "op"} and m["op"] in SURVEY_CMD_OPS,
}
MP_EXAMPLES = [(m["t"], m) for first in (MP_CP_EXAMPLES, MP_MC_EXAMPLES, MP_UP_EXAMPLES, MP_DOWN_EXAMPLES) for m in examples(first)]


def mp_example(t, **changes):
    m = copy.deepcopy(next(m for u, m in MP_EXAMPLES if u == t))
    m.update(changes)
    return m


def test_phase_q_every_type_has_a_row_an_example_and_a_validator():
    assert documented(PQ_CP_TO_MC) == {"cam"} and documented(PQ_MC_TO_CP) == {"prefs"}
    assert {m["t"] for m in examples(PQ_CP_EXAMPLES)} == {"cam"} and {m["t"] for m in examples(PQ_MC_EXAMPLES)} == {"prefs"}
    assert {t for t, _ in PQ_EXAMPLES} == set(PQ_VALIDATORS)
    assert "**extends layer 1, layers 3–6 and re-test 3:**" in rows_by_type(PQ_CP_TO_MC)["cam"]


def test_phase_q_every_example_field_is_named_in_its_row():
    cp_row, mc_row = rows_by_type(PQ_CP_TO_MC)["cam"], rows_by_type(PQ_MC_TO_CP)["prefs"]
    cam = next(m for t, m in PQ_EXAMPLES if t == "cam")
    inherited = set(next(m for t, m in R3_EXAMPLES if t == "cam")) - {"seat"}  # what the older `cam` rows already name
    assert set(cam) - {"t"} - inherited == {"vdbg"} and re.search(r"\bvdbg\b", cp_row)
    assert set(cam) >= set(example(R3_OLD_EXAMPLE_OF["cam"], "cam"))  # the example keeps every layer 1 and layers 3-6 field
    for key in set(next(m for t, m in PQ_EXAMPLES if t == "prefs")) - {"t"}:
        assert re.search(rf"\b{key}\b", mc_row), f"prefs.{key} is not described in its row"


@pytest.mark.parametrize("t, m", PQ_EXAMPLES, ids=[t for t, _ in PQ_EXAMPLES])
def test_phase_q_example_obeys_the_documented_limits(t, m):
    assert PQ_VALIDATORS[t](m)


def test_phase_q_cam_example_is_third_person_and_prefs_example_is_the_defaults():
    cam = next(m for t, m in PQ_EXAMPLES if t == "cam")
    assert cam["fp"] is False and len(cam["vdbg"]) == 3
    assert next(m for t, m in PQ_EXAMPLES if t == "prefs") == {"t": "prefs", **PREFS_DEFAULTS}


PQ_OUT_OF_LIMITS = [
    ("prefs", pq_example("prefs", dist=1.4)), ("prefs", pq_example("prefs", dist=8.01)), ("prefs", pq_example("prefs", shoulder=-1.01)),
    ("prefs", pq_example("prefs", shoulder=1.01)), ("prefs", pq_example("prefs", fov=49)), ("prefs", pq_example("prefs", fov=110.5)),
    ("prefs", pq_example("prefs", fov=-1)), ("prefs", pq_example("prefs", vdist=2.9)), ("prefs", pq_example("prefs", vdist=14.1)),
    ("prefs", pq_example("prefs", recenter=1)), ("prefs", pq_example("prefs", stepup="yes")), ("prefs", pq_example("prefs", dist=float("nan"))),
    ("prefs", pq_example("prefs", dist=True)), ("prefs", {k: v for k, v in pq_example("prefs").items() if k != "dist"}),
    ("prefs", pq_example("prefs", scale=0.8)),  # an eighth field is a later phase's: not part of this contract
    ("prefs", pq_example("prefs", debug=1)), ("prefs", {k: v for k, v in pq_example("prefs").items() if k != "debug"}),  # Minecraft sends all seven
    ("cam", pq_example("cam", vdbg=[3.2, 2.8])), ("cam", pq_example("cam", vdbg=[3.2, 2.8, 14, 0])), ("cam", pq_example("cam", vdbg=[-0.1, 2.8, 14])),
    ("cam", pq_example("cam", vdbg=[100.1, 2.8, 14])), ("cam", pq_example("cam", vdbg=[3.2, 100.1, 14])), ("cam", pq_example("cam", vdbg=[3.2, 2.8, 60001])),
    ("cam", pq_example("cam", vdbg=[3.2, 2.8, -1])), ("cam", pq_example("cam", vdbg="3.2")), ("cam", pq_example("cam", vdbg=[3.2, None, 14])),
    ("cam", pq_example("cam", vdbg=[True, 2.8, 14])),
]


@pytest.mark.parametrize("t, m", PQ_OUT_OF_LIMITS, ids=[f"{t}-{i}" for i, (t, _) in enumerate(PQ_OUT_OF_LIMITS)])
def test_phase_q_validators_reject_out_of_limit_messages(t, m):
    try:
        ok = PQ_VALIDATORS[t](m)
    except (KeyError, TypeError):
        ok = False
    assert not ok


def test_mp_names_have_the_plan_values():
    assert PAYLOAD_IDS == list(MP_PAYLOADS_EXPECTED) and SESSION_STATES == ["none", "host", "guest", "refused"] and ROLES == ["host", "guest"]
    assert SURVEY_CMD_OPS == ["start", "redo", "stop", "status"] and GUEST_TOGGLES == ["explosions", "damage", "hacks", "devices", "spawns"]


def test_mp_class_tables_classify_every_wire_message_once_with_the_plan_class():
    for heading, expected in ((MP_CP, MP_CP_CLASSES), (MP_MC, MP_MC_CLASSES)):
        rows = class_rows(heading)
        assert len(rows) == len({t for t, _ in rows}), f"{heading}: a type is listed twice"
        assert dict(rows) == expected, heading


def test_mp_class_tables_cover_every_documented_type_of_the_wire():
    cp = documented(CP_TO_MC) | documented(L36_CP_TO_MC) | documented(R3_CP_TO_MC) | documented(AT_CP_TO_MC)
    mc = documented(MC_TO_CP) | documented(L36_MC_TO_CP) | documented(R3_MC_TO_CP) | documented(AT_MC_TO_CP) | documented(PQ_MC_TO_CP)
    assert set(MP_CP_CLASSES) == cp | {"cmd"}  # `cmd` is the test tools' command, documented only here
    assert set(MP_MC_CLASSES) == (mc - {"perkfx"}) | {"pteleport"}  # `perkfx` is superseded and never sent; `pteleport` was undocumented
    assert {"hello"} <= set(MP_CP_CLASSES) & set(MP_MC_CLASSES)


def test_mp_up_types_fit_the_envelope_and_all_have_a_rate_limit():
    up = {t for t, c in MP_CP_CLASSES.items() if c in ("UP", "BOTH")} | {"hstate"}
    assert all(len(t) <= 16 for t in up)  # `cyberblock:up` t is at most 16 characters
    assert up <= set(rate_rows())


def test_mp_rate_limits_are_the_plan_table():
    assert rate_rows() == MP_RATES
    text = " ".join(section(MP_SECURITY).split())
    for phrase in ("at most 20000 cells per second", "the burst is 200 during a survey", "`rate` multiplier", "five times its rate for 10 s",
                   "Cyberblock: too many messages from your Cyberpunk link", "160 blocks horizontally", "`terrain` 400 blocks",
                   "50 of them within a minute kick the player", "counters are logged every 5 s", "`cyberblock.guest.blocked`",
                   "never relayed and a dedicated server refuses it"):
        assert phrase in text, f"{phrase!r} is not stated"


def test_mp_payload_table_lists_the_nine_payloads_with_their_directions_and_caps():
    assert payload_rows() == MP_PAYLOADS_EXPECTED
    text = section(MP_PAYLOADS)
    rows = {m.group(1): m.group(0) for m in re.finditer(r"^\| `cyberblock:(\w+)` \|.*$", text, re.M)}
    for pid, cap in (("hs", "`mod` ≤ 32"), ("hs", "`dll` ≤ 32"), ("hs_ack", "`reason` ≤ 256"), ("up", "`t` ≤ 16"), ("up", "30000 bytes"),
                     ("down", "1 000 000 bytes"), ("roster", "≤ 200"), ("content", "`kind` ≤ 32"), ("act", "8000"), ("ui", "1 000 000")):
        assert cap in rows[pid], f"cyberblock:{pid} does not state {cap!r}"
    flat = " ".join(text.split())
    for phrase in ("1 `explosions`, 2 `damage`, 4 `hacks`, 8 `devices`, 16 `spawns`",
                   "1 replica, 2 devices, 4 figures, 8 hacking, 16 hub, 32 modes", "bounded at 2048 messages", "in `seq` order",
                   "whole quadruples", "`npcs` lists"):
        assert phrase in flat, f"{phrase!r} is not stated"


def test_mp_handshake_and_session_rules_are_written_down():
    text = " ".join(section(MP_SESSIONS).split())
    for phrase in ("Cyberblock version mismatch: server X, your mod Y. Update from the modpack.", "differ in major.minor", "within 10 s",
                   "`requireMod:true`", "5, 10, 20, 40 and then every 60 s for 10 minutes", "`NONE`", "`LINKED`", "`ACTIVE`",
                   "`align` immediately followed by `blocks reset:true`", "`maxHosts`", "handshake: <name> mod X dll Y -> host|guest",
                   "session: <name> started|active|ended (<reason>)", "relay: <name> dropped <t> x N (<why>)", "session: cleaned (<reason>)"):
        assert phrase in text, f"{phrase!r} is not stated"


def test_mp_new_messages_have_a_row_an_example_and_a_validator():
    rows = rows_by_type(MP_NEW)
    assert set(rows) == set(MP_VALIDATORS) == {t for t, _ in MP_EXAMPLES}
    assert "**extends layer 1**" in rows["hello"] and "`ver`" in rows["hello"]
    for t in ("hstate", "session", "pteleport", "survey_cmd"):
        assert "**extends" not in rows[t]


def test_mp_every_example_field_is_named_in_its_row():
    rows = rows_by_type(MP_NEW)
    for t, m in MP_EXAMPLES:
        for key in set(m) - {"t"} - ({"proto", "game", "build"} if t == "hello" else set()):  # the old hello fields live in layer 1
            assert re.search(rf"\b{key}\b", rows[t]), f"{t}.{key} is not described in its Multiplayer row"


@pytest.mark.parametrize("t, m", MP_EXAMPLES, ids=[f"{t}-{i}" for i, (t, _) in enumerate(MP_EXAMPLES)])
def test_mp_example_obeys_the_documented_limits(t, m):
    assert MP_VALIDATORS[t](m)


def test_mp_examples_show_every_session_state_and_both_hstate_forms():
    sessions = [m for t, m in MP_EXAMPLES if t == "session"]
    assert sorted(m["state"] for m in sessions) == sorted(SESSION_STATES)
    assert any(m["perms"]["explosions"] for m in sessions) and all(m["reason"] for m in sessions if m["state"] == "refused")
    hstates = [m for t, m in MP_EXAMPLES if t == "hstate"]
    assert any("feetCpZ" in m for m in hstates) and any("feetCpZ" not in m for m in hstates)  # feetCpZ is optional
    assert mp_example("hello")["ver"] and mp_example("hello")["proto"] == 1


MP_OUT_OF_LIMITS = [
    ("hello", mp_example("hello", ver="")), ("hello", mp_example("hello", ver="v" * 33)), ("hello", mp_example("hello", ver=1)),
    ("hello", {k: v for k, v in mp_example("hello").items() if k != "ver"}),
    ("hstate", mp_example("hstate", lv=64)), ("hstate", mp_example("hstate", lv=-1)), ("hstate", mp_example("hstate", lv=1.5)),
    ("hstate", mp_example("hstate", active=1)), ("hstate", mp_example("hstate", seat="yes")), ("hstate", mp_example("hstate", feetCpZ=float("inf"))),
    ("hstate", mp_example("hstate", feetCpZ=3e7)), ("hstate", mp_example("hstate", feetCpZ="64")),
    ("hstate", {k: v for k, v in mp_example("hstate").items() if k != "launch"}), ("hstate", {**mp_example("hstate"), "extra": True}),
    ("session", mp_example("session", state="spectator")), ("session", mp_example("session", reason="r" * 129)),
    ("session", mp_example("session", reason="é" * 65)), ("session", mp_example("session", reason=None)),
    ("session", mp_example("session", perms={"explosions": True})),
    ("session", mp_example("session", perms={**mp_example("session")["perms"], "damage": 1})),
    ("session", mp_example("session", perms={**mp_example("session")["perms"], "extra": True})),
    ("pteleport", mp_example("pteleport", pos=[1, 2])), ("pteleport", mp_example("pteleport", pos=[0, float("nan"), 0])),
    ("pteleport", mp_example("pteleport", pos=[3e7, 0, 0])), ("pteleport", mp_example("pteleport", pos=None)),
    ("survey_cmd", mp_example("survey_cmd", op="pause")), ("survey_cmd", mp_example("survey_cmd", op=None)),
    ("survey_cmd", {"t": "survey_cmd"}),
]


@pytest.mark.parametrize("t, m", MP_OUT_OF_LIMITS, ids=[f"{t}-{i}" for i, (t, _) in enumerate(MP_OUT_OF_LIMITS)])
def test_mp_validators_reject_out_of_limit_messages(t, m):
    try:
        ok = MP_VALIDATORS[t](m)
    except (KeyError, TypeError):
        ok = False
    assert not ok


def test_phase_q_validators_accept_the_limits_themselves():
    for dist, shoulder, fov, vdist in ((1.5, -1, 50, 3), (8, 1, 110, 14), (3.2, 0, 0, 0)):
        assert prefs_ok(pq_example("prefs", dist=dist, shoulder=shoulder, fov=fov, vdist=vdist))
    assert prefs_ok(pq_example("prefs", recenter=False, stepup=False, debug=True))
    assert cam_vdbg_ok(pq_example("cam", vdbg=[0, 0, 0])) and cam_vdbg_ok(pq_example("cam", vdbg=[100, 100, 60000]))
    assert cam_vdbg_ok({k: v for k, v in pq_example("cam").items() if k != "vdbg"})  # vdbg is optional: absent unless the debug flag is on


def test_phase_q_rules_are_stated_in_the_document():
    text = " ".join((section("Phase Q semantics and limits") + section(PQ_CP_TO_MC) + section(PQ_MC_TO_CP)).split())
    for phrase in ("dist 3.2, shoulder 0.45, fov 0, recenter true, vdist 0, stepup true, debug false", "on each `hello`", "all seven fields",
                   "a `prefs` without it keeps the current flag", "the last `prefs` had `debug:true`", "clamps", "a negative `fov` or `vdist` becomes 0",
                   "1.5..8", "−1..1", "50..110", "3..14", "event pump", "1.05 m", "2 m of free space", "never stepped on", "only the `vdbg` is ignored",
                   "`/cyberblock debug view`", "only while", "nearest hit minus 0.25 m", "fp:false",
                   "how long ago the DLL's view last computed the camera pose, its last tick, when this `cam` is sent"):
        assert phrase in text, f"{phrase!r} is not stated"


def test_phase_q_documented_defaults_match_the_checked_ones():
    text = section("Phase Q semantics and limits")
    for key, value in PREFS_DEFAULTS.items():
        assert re.search(rf"\b{key} {str(value).lower()}\b", text), f"default {key} {value} is not in the Defaults bullet"


# ---- Phase Q player docs and the Re-test Q list (plan 2026-10-04 megaplan Q-P3) ----------------------------------------
TESTING = Path("docs/TESTING-PLAN.md").read_text(encoding="utf-8")
CHANGELOG = Path("CHANGELOG.md").read_text(encoding="utf-8")


def test_guide_documents_phase_q():
    text = " ".join(GUIDE.split())
    for phrase in ("### Mining placed blocks, stairs and the step-up assist", "#### Third-person camera options and the debug overlay",
                   "### Creative mode and the outline blocks", "#### The Cyberblock settings screen", "Step-up assist",
                   "vault", "climb", "`/cyberblock debug view on`", "`/cyberblock debug aim on`", "`/cyberblock settings`",
                   "`/cyberblock outline erase on|off`", "Creative can erase the replica permanently", "comes back in about 10 seconds",
                   "Caught you: back to safe ground", "Only the host or an operator can change this", "1.5 to 8 m", "-1 to 1 m", "50 to 110", "3 to 14 m",
                   "view: frame <ms> pose age <ms> interp <f> dist <m> collide <m>", "aim: target <block|entity|none> d=<m> limit=<m>",
                   "locomotion: vault refused on Cyberblock box", "outline: restored N"):
        assert phrase in text, f"HOW-TO-PLAY.md does not say {phrase!r}"
    outline = section_of(GUIDE, "### Creative mode and the outline blocks")
    for fact in ("Survival and adventure", "cannot be broken", "Creative:", "Permanent erase", "walk away and back"):
        assert fact in outline, fact


def section_of(doc: str, heading: str) -> str:
    """The text of a markdown section from its heading to the next heading of the same or a higher level."""
    level = len(heading.split()[0])
    start = doc.index(heading + "\n")
    rest = doc[start + len(heading):]
    end = re.search(rf"^#{{1,{level}}} ", rest, re.M)
    return rest[: end.start()] if end else rest


def test_testing_plan_starts_with_re_test_q_and_leaves_room_for_the_next_phases():
    assert TESTING.index("## Re-test Q (do this first)") < TESTING.index("## In-game fixes 3") < TESTING.index("## Re-test 3 (do this first)")
    q = section_of(TESTING, "## Re-test Q (do this first)")
    assert "<!-- end of Re-test Q." in q and "Re-test 1" in q.split("<!-- end of Re-test Q.", 1)[1]  # the marker says where Re-test 1 goes
    assert q.rstrip().endswith("-->")  # nothing follows the marker inside the section, so appending below it is safe
    for item in ("### Q1. Survival mining", "### Q2. A staircase of placed blocks", "### Q3. Third person", "### Q4. UI coverage and the escape hatch",
                 "### Q5. Outline blocks and the settings screen", "### Q6. Gorilla Arms charged punch", "### Q7. Phase 0 CET checks"):
        assert item in q, item
    assert len(q.split("**What to try (one screen):**", 1)[1].split("| Item |", 1)[0].strip().splitlines()) <= 15  # one screen


def test_re_test_q_has_the_five_phase_q_checks_the_gorilla_check_and_phase_0_rows():
    q = " ".join(section_of(TESTING, "## Re-test Q (do this first)").split())
    for fact in ("iron pickaxe", "eye height", "your **feet**", "five** placed blocks", "no vault or climb prompt", "kerb", "ladder",
                 "F5", "glide", "launch with a wind charge", "shield", "along a wall", "corridor", "crosshair", "frame time and pose age",
                 "comes back in about 10 seconds", "Survival:", "stays broken until you walk away", "docs/notes/ui-coverage.md", "Night City Menu...",
                 "`locomotion: vault refused on Cyberblock box`", "`aim: target block d=... limit=...`", "`outline: restored N`",
                 "fully charged", "uncharged", "knocks the pedestrian down", "only stagger them"):
        assert fact in q, f"Re-test Q does not say {fact!r}"
    # Q6 cites the scripts' real log line (Effects.reds AfterHit writes `knock: <id> s=<s> tier=<tier> impulse=<i>` to
    # Cyberblock.log), never Minecraft's latest.log, which has no `knock:` line
    gorilla = " ".join(section_of(TESTING, "### Q6. Gorilla Arms charged punch (package QG)").split())
    for fact in ("**Log (Cyberblock.log):**", "`knock: <id> s=<speed> tier=<tier> impulse=<i>`", "`tier=knockdown`", "`tier=stagger`"):
        assert fact in gorilla, f"Re-test Q6 does not say {fact!r}"
    assert "latest.log" not in gorilla
    assert 'Cyberblock_Log(s"knock: \\(target) s=\\(s) tier=\\(tierName)' in Path("cp/scripts/Cyberblock/Effects.reds").read_text(encoding="utf-8")
    for check in ("(b)", "(c)", "(d)", "(e)", "(f)", "(g)", "(h)"):  # Phase 0's CET checks, placeholders until 0R's notes land
        assert f"| {check} |" in q, check
    assert q.count("| to be filled | to be filled |") == 7
    assert "placeholders until 0R's notes land" in q


def test_changelog_has_an_unreleased_entry_for_phase_q_at_the_top_of_the_1_0_0_section():
    """It is a `###` entry inside the top `## [1.0.0]` section, not a `## [Unreleased]` heading: tests/test_version.py wants the
    first `##` heading of the changelog to be the current version (the integrator may change that when a release is cut)."""
    assert CHANGELOG.index("## [1.0.0]") < CHANGELOG.index("### Unreleased additions: Phase Q") < re.search(r"^### Added$", CHANGELOG, re.M).start()
    text = " ".join(section_of(CHANGELOG, "### Unreleased additions: Phase Q (multiplayer plan)").split())
    for phrase in ("Survival mining", "Stairs", "step-up assist", "third-person camera", "Outline blocks in creative", "settings screen",
                   "`prefs`", "`cam.vdbg`", "--view", "--stairs", "tests/test_hygiene.py"):
        assert phrase in text, f"CHANGELOG Unreleased does not say {phrase!r}"


def test_mp_validators_accept_the_limits_themselves():
    assert mp_hello_ok(mp_example("hello", ver="v" * 32)) and mp_hello_ok(mp_example("hello", ver="1"))
    assert hstate_ok(mp_example("hstate", lv=0)) and hstate_ok(mp_example("hstate", lv=63, feetCpZ=-29_999_999.5))
    assert session_ok(mp_example("session", reason="r" * 128)) and session_ok(mp_example("session", reason="é" * 64))
    assert session_ok(mp_example("session", state="none", reason=""))
    assert MP_VALIDATORS["pteleport"](mp_example("pteleport", pos=[-29_999_999, 0.0, 29_999_999]))
    assert all(MP_VALIDATORS["survey_cmd"](mp_example("survey_cmd", op=op)) for op in SURVEY_CMD_OPS)


def test_mp_refused_reason_of_the_plan_fits_the_cap_for_five_character_versions():
    """Section 1.5's mismatch text with `X.Y.Z` versions is exactly 128 bytes: longer version strings are cut by the sender."""
    msg = "Cyberblock: your Cyberpunk plugin is 1.0.0 but your mod is 1.1.0; you can play as a guest. Reinstall both from the same release."
    assert len(msg.encode("utf-8")) == SESSION_REASON_BYTES
    assert msg == next(m for t, m in MP_EXAMPLES if t == "session" and m["state"] == "refused")["reason"]
    assert "cuts `reason` at 128 bytes" in " ".join(section(MP_NEW).split())


def test_mp_session_and_survey_cmd_rules_are_written_down():
    text = " ".join(section(MP_NEW).split())
    for phrase in ("clean Cyberpunk state exactly as on a link drop", "collision boxes reset", "puppets despawned", "tethers released",
                   "Minecraft pause released", "survey stopped", "parked V released", "keeping the WebSocket", "forgets its probe state and sends `clear`",
                   "never forwards it to the DLL", "never on the DLL wire", "`Bridge.hostAirborne`", "`Cyberblock.launching`", "Undocumented until Phase 1",
                   "the DLL ignores it today", "(≤ 128 UTF-8 bytes", "`hs_ack.perms`"):
        assert phrase in text, f"{phrase!r} is not stated"


def test_mp_section_sits_before_the_shared_memory_section_and_points_at_the_plan():
    assert TEXT.index("## Multiplayer (Phase 1)") < TEXT.index("## Shared memory")
    assert "2026-10-04-multiplayer-megaplan.md" in TEXT.split("## Multiplayer (Phase 1)", 1)[1].split("###", 1)[0]
