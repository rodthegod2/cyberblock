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
L36_NEW_CP_TO_MC = {"mobdmg", "pcells", "unlead", "scaninfo", "hackresult", "killed", "look"}
L36_NEW_MC_TO_CP = {"blocks", "mobs", "flight", "teleport", "proj", "lead", "pull", "knock", "effect", "scan", "hack", "perks", "perkfx", "binds"}
L36_EXTENDED = {"cam", "key", "hit"}
THREATS = {"none", "low", "medium", "high", "extreme"}
NEW_KEYS = {"jump", "sprint", "perspective", "hack", "skills"}
KIND_RE, EFFECT_RE = re.compile(r"[a-z0-9_]{1,32}"), re.compile(r"[a-z_]{1,32}")


def names(label: str) -> list[str]:
    line = re.search(rf"^- \*\*{label}\*\*.*$", TEXT, re.M).group(0)
    return re.findall(r"`([a-z_]+)`", line.split("—", 1)[1])


HACKS, PERKS = names("Hack names"), names("Perk ids")


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
    return set(m["keys"]) == {"hack", "skills"} and all(whole(vk, 1, 254) for vk in m["keys"].values())


# Each validator applies the plan section 1.2 limits to a whole message (everything but `t` is checked).
VALIDATORS = {
    "cam": lambda m: isinstance(m["cr"], bool) and {"f", "p", "r", "fov", "fp", "pl", "h"} <= set(m),
    "key": lambda m: m["k"] in NEW_KEYS and isinstance(m["down"], bool),
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
    "flight": lambda m: isinstance(m["active"], bool) and vec3(m["p"]) and speed_ok(m["v"]) and num(m["yaw"], -1e4, 1e4) and num(m["pitch"], -90, 90),
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
}
L36_EXAMPLES = [(m["t"], m) for first in (L36_CP_EXAMPLES, L36_MC_EXAMPLES) for m in examples(first)]


def test_layer36_name_lists_have_the_plan_counts():
    assert len(HACKS) == 10 and len(set(HACKS)) == 10 and len(PERKS) == 20 and len(set(PERKS)) == 20
    assert {"short_circuit", "device", "cyberpsychosis"} <= set(HACKS) and {"iron_skin", "sandevistan", "silent_steps"} <= set(PERKS)


def test_layer36_every_new_type_and_every_extended_type_is_in_its_table():
    assert documented(L36_CP_TO_MC) == L36_NEW_CP_TO_MC | {"cam", "key"}
    assert documented(L36_MC_TO_CP) == L36_NEW_MC_TO_CP | {"hit"}
    for t, heading in (("cam", L36_CP_TO_MC), ("key", L36_CP_TO_MC), ("hit", L36_MC_TO_CP)):
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
    assert fields["flight"] == {("active", "p", "pitch", "v", "yaw")} and fields["teleport"] == {("p", "yaw")}
    assert fields["pull"] == {("strength", "target", "toward")} and fields["knock"] == {("pos", "radius", "strength")}
    assert fields["effect"] == {("amp", "name", "seconds")} and fields["hack"] == {("chain", "hack", "id", "target")}
    assert fields["perkfx"] == {("id", "seconds")} and fields["binds"] == {("keys",)} and fields["blocks"] == {("c", "reset")}


def test_layer36_extended_examples_keep_their_layer1_and_layer2_fields():
    cam = next(m for t, m in L36_EXAMPLES if t == "cam")
    assert set(cam) == set(example("Cyberpunk → Minecraft:", "cam")) | {"cr"}
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
    ("perkfx", with_("perkfx", seconds=31)), ("binds", with_("binds", keys={"hack": 0, "skills": 75})),
    ("binds", with_("binds", keys={"hack": 71, "skills": 255})), ("binds", with_("binds", keys={"hack": 71})),
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
                   "≤ 12", "≤ 16", "≤ 64", "≤ 5", "≤ 120 Hz", "3e7", "200 m/s", "[a-z0-9_]{1,32}", "[a-z_]{1,32}"):
        assert phrase in text, f"limit {phrase!r} is not stated"
