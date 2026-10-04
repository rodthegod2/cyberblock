"""Phase 4 docs (megaplan task 4-P1): the player guide's Devices section, Re-test: Wave B, Phase 4, the changelog entry and the descopes note
say what the plan says, the guide's table is the plan's table of section 1.11 and the protocol's kinds, every link resolves, and nothing
private is in them."""
import re
from pathlib import Path

from test_protocol_md import DEVICE_KINDS, GUIDE, README, anchors, section_of

ROOT = Path(__file__).resolve().parent.parent
TESTING = (ROOT / "docs" / "TESTING-PLAN.md").read_text(encoding="utf-8")
CHANGELOG = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
DESCOPES = (ROOT / "docs" / "notes" / "phase4-descopes.md").read_text(encoding="utf-8")
GUIDE_HEADING = "### Devices (Phase 4)"
RETEST_HEADING = "## Re-test: Wave B, Phase 4 (the device layer)"
PHASE4_TESTING = "## Re-test: Wave B, Phase 3 (the Night City replica)"


def flat(text: str) -> str:
    return " ".join(text.split())


def test_guide_has_the_devices_section_with_every_topic_of_the_plan():
    section = flat(section_of(GUIDE, GUIDE_HEADING))
    for phrase in ("a **block you can use**", "a **guest opens a door**", "a **pedestrian opens a door**", "**What each device is in Minecraft, and what you do with it.**",
                   "**Waiting for Night City.**", "never shows a state that Night City refused", "**Greyed devices.**", "**Quest devices.**", "belongs to a mission",
                   "**call a lift**", "Night City cannot do that with this device yet", "a Minecraft shop with its own stock", "**Breach**", "**Vending machines and eddies.**",
                   "`cyberblock:eddies`", "*Eddie Chip* worth 100", "datapack", "**Who may use what.**", "`/cyberblock guests devices <type> on|off`",
                   "`/cyberblock guests devices on|off`", "**Which devices you see.**", "does not see these blocks on their own screen", "**only while their host is playing**",
                   "10 seconds", "doors are not walls", "**What is not carried over.**", "a lift's floor, a traffic light's colour or a camera's alarm",
                   "a device that moves is then a new device", "**Which way is it working?", "device **ids**", "device-box filter", "within 48 metres", "8 blocks"):
        assert phrase in section, f"HOW-TO-PLAY.md Devices does not say {phrase!r}"


def test_guide_table_is_the_plan_table_of_section_1_11_in_player_words():
    section = section_of(GUIDE, GUIDE_HEADING)
    rows = re.findall(r"^\| ([^|]+) \| ([^|]+) \| ([^|]+) \| `(\w+)` \|$", section, re.M)
    assert [r[3] for r in rows] == DEVICE_KINDS, "one row per device kind, in the order of the protocol's bits"
    text = flat(section)
    for phrase in ("a wooden door, an **iron door** for a secure one, a **red lamp** above it while it is locked", "a wide sliding gate", "a lamp in the light's colour",
                   "an eye block with a vision cone", "a **Net Port** block", "a machine block (snack, drinks or gear machine)", "a three-colour lamp block",
                   "a lift platform with a call button", "a turret block", "**sneak** and right-click makes it friendly", "a screen in an item frame", "a closed **crate**"):
        assert phrase in text, f"the Devices table does not say {phrase!r}"
    names = flat(re.search(r"\*\*Who may use what\.\*\*(.*?)\n\n", section, re.S).group(1))
    assert all(f"`{kind}`" in names for kind in DEVICE_KINDS), "the toggle text names every type"


def test_guide_older_sections_point_at_the_new_one():
    text = flat(GUIDE)
    assert "(#devices-phase-4)" in text and "devices-phase-4" in anchors(GUIDE)
    commands = flat(section_of(GUIDE, "### Commands"))
    assert "`/cyberblock guests devices <type> on|off`" in commands
    assert "docs/HOW-TO-PLAY.md#devices-phase-4" in README and "`/cyberblock guests devices <type> off`" in README


def test_re_test_wave_b_phase_4_sits_next_to_phase_3_and_has_the_plan_checks():
    assert TESTING.index(PHASE4_TESTING) < TESTING.index(RETEST_HEADING) < TESTING.index("## Re-test: Wave A") < TESTING.index("## Re-test Q (do this first)")
    retest = section_of(TESTING, RETEST_HEADING)
    one_screen = retest.split("**What to try (one screen):**", 1)[1].split("| Item |", 1)[0].strip().splitlines()
    assert len(one_screen) <= 15
    for n in range(1, 11):
        assert f"### P4-{n}." in retest, f"P4-{n}"
    text = flat(retest)
    for fact in ("same session", "straight after", "same test server", "C1 to C5", "`/give <guest> cyberblock:eddies 50`", "opens **in Night City**", "pedestrian",
                 "opens in Minecraft", "light goes **off in Night City**", "eye block", "**red**", "no alarm field", "shop screen", "nothing happens in Night City",
                 "**greyed**", "Night City cannot do that with this device yet", "`/cyberblock guests devices door off`", "A **light** still works",
                 "`/cyberblock guests devices door on`", "This device belongs to a mission", "Cyberblock.log", "go within about 10 seconds", "doors are not walls",
                 "descopes"):
        assert fact in text, f"Re-test: Wave B, Phase 4 does not say {fact!r}"


def test_re_test_wave_b_phase_4_has_one_line_per_runtime_path_of_the_script_and_dll_packages():
    table = flat(section_of(TESTING, "### P4-9. Which way is it working? (the runtime paths)"))
    for feature, word in (("Device ids", "devices: ids from"), ("Changes", "devices: changes via"), ("Device-box filter", "probes: device hits"),
                          ("Device list", "devices: sent"), ("Actions", "devact"), ("Server", "devices:")):
        assert f"| {feature} | `{word}" in table, feature
    for fact in ("`entity hash`", "`position+class fallback`", "`events`", "`2 Hz poll`", "`recast`", "device-box filter only", "search for the word", "`unsupported`"):
        assert fact in table, fact


def test_the_testing_plan_keeps_the_phase_3_block_and_the_older_ones_in_order():
    assert TESTING.index("## Re-test: Wave B, Phase 3") < TESTING.index("## Re-test: Wave A") < TESTING.index("## Re-test Q (do this first)")
    assert "Re-test: Wave B, Phase 3 first" in flat(TESTING[:1200])


def test_the_changelog_has_a_phase_4_entry_above_the_phase_3_one():
    assert CHANGELOG.index("## [1.0.0]") < CHANGELOG.index("### Unreleased additions: Phase 4") < CHANGELOG.index("### Unreleased additions: Phase 3")
    text = flat(section_of(CHANGELOG, "### Unreleased additions: Phase 4 (multiplayer plan, package 4P)"))
    for phrase in ("`devices`", "`devstate`", "`devresult`", "`devact`", "`cyberblock:act` `t:\"devact\"`", "`tools.fakehost --devices`", "`tools.mpcheck devices`", "`unsupported`",
                   "`/cyberblock test device <x> <y> <z> <act>`", "HOW-TO-PLAY", "Re-test: Wave B, Phase 4", "`docs/notes/phase4-descopes.md`", "`--shift`"):
        assert phrase in text, f"CHANGELOG does not say {phrase!r}"


def test_the_descopes_note_lists_the_risks_of_the_plan_and_the_contract_limits():
    text = flat(DESCOPES)
    for phrase in ("Lift call and vending purchase are greyed", "No size", "Position-based ids", "Polling fallback", "Containers", "Only `st` and `fl` travel",
                   "`disable` reads as quest-locked", "`by:guest`", "Reach and rate", "The first real run is the test", "`devact` payload", "Devtools command",
                   "Contract additions made by 4P", "no full or partial mark"):
        assert phrase in text, f"phase4-descopes.md does not say {phrase!r}"


def test_the_phase_4_docs_hold_no_private_values():
    for name, text in (("HOW-TO-PLAY section", section_of(GUIDE, GUIDE_HEADING)), ("descopes", DESCOPES), ("Re-test", section_of(TESTING, RETEST_HEADING)),
                       ("CHANGELOG entry", section_of(CHANGELOG, "### Unreleased additions: Phase 4 (multiplayer plan, package 4P)"))):
        assert not re.search(r"(?<![A-Za-z0-9])[A-Za-z]:[\\/]", text), f"{name}: a drive-letter path"
        assert not re.search(r"APEX_FTP_PASS\s*=|ftp://|\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}", text), f"{name}: a secret or an address"
        assert not re.search(r"_PASS\s*=|joinPassword\"?\s*:\s*\"[^\"]", text), f"{name}: a password"
