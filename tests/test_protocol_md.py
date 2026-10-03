import json, math, re
from pathlib import Path

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
