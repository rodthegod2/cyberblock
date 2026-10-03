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
