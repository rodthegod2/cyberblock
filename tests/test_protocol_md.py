import json, re
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
