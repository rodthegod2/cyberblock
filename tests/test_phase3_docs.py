"""Phase 3 docs (megaplan task 3-P1): the player guide section, the web map page, Re-test: Wave B, Phase 3, the changelog entry and the
descopes note say what the plan says, every link resolves, and nothing private is in them."""
import re
from pathlib import Path

from test_protocol_md import GUIDE, README, anchors, section_of

from tools import cbmath

ROOT = Path(__file__).resolve().parent.parent
TESTING = (ROOT / "docs" / "TESTING-PLAN.md").read_text(encoding="utf-8")
CHANGELOG = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
WEBMAP = (ROOT / "docs" / "WEBMAP.md").read_text(encoding="utf-8")
DESCOPES = (ROOT / "docs" / "notes" / "phase3-descopes.md").read_text(encoding="utf-8")
GUIDE_HEADING = "### Night City as Minecraft (Phase 3)"
RETEST_HEADING = "## Re-test: Wave B, Phase 3 (the Night City replica)"


def flat(text: str) -> str:
    return " ".join(text.split())


def test_guide_has_the_night_city_as_minecraft_section_with_every_topic_of_the_plan():
    section = flat(section_of(GUIDE, GUIDE_HEADING))
    for phrase in ("**The replica.**", "solid material blocks", "16 glowing neon colours", "Doors, gates and other devices are left out",
                   "saved in the world", "grows as anyone explores", "stays", "never sent back to Cyberpunk", "explosions never remove replica blocks",
                   "**Breaking replica blocks (the Q5 rule).**", "cannot be broken", "comes back in about 10 seconds", "Creative can erase the replica permanently",
                   "**People and cars.**", "real speed", "dark wreck for 5 seconds", "lies down for 3 seconds", "**Time of day and weather.**",
                   "Cyberpunk's night is Minecraft's night", "**first host is the primary**", "**Offline Builds.**", "stops V at once",
                   "While you were away: N blocks placed, M removed (by A, B)", "`/cyberblock changes`", "30 days", "**Meet Up.**",
                   "`/cyberblock meetup <player>`", "`/cyberblock meetup accept`", "within 60 seconds", "30 seconds between your requests", "teleported next to the host's Steve",
                   "**More than one host.**", "4 hosts at once", "**The Apex server's world.**", "void world", "`cyberblock:nightcity`", "spawn platform",
                   "`worldcheck`", "**A web map.**", "**Which way is it working?", "one line saying which way is active"):
        assert phrase in section, f"HOW-TO-PLAY.md Night City as Minecraft does not say {phrase!r}"


def test_guide_materials_and_neon_in_the_text_are_the_code_table():
    section = flat(section_of(GUIDE, GUIDE_HEADING))
    for word in ("concrete", "asphalt", "sidewalk", "glass", "metal", "brick", "tile", "ground", "water"):
        assert word in section
    assert len(cbmath.DYES) == 16 and "16 glowing neon colours" in section and "light level 15" in section


def test_guide_older_sections_point_at_the_new_one_and_no_longer_say_guests_see_no_figures():
    text = flat(GUIDE)
    assert "(#night-city-as-minecraft-phase-3)" in text and "Not yet: the guest does not see Night City's people" not in text
    assert "Only one Cyberpunk host per server for now" not in text and "up to `maxHosts` (4 from Phase 3)" in text
    commands = flat(section_of(GUIDE, "### Commands"))
    assert "`/cyberblock meetup <player>`" in commands and "`/cyberblock meetup accept`" in commands and "`/cyberblock changes`" in commands
    assert "night-city-as-minecraft-phase-3" in {a for a in anchors(GUIDE)}
    assert "docs/HOW-TO-PLAY.md#night-city-as-minecraft-phase-3" in README and "docs/WEBMAP.md" in README


def test_the_web_map_page_names_the_mod_the_port_the_config_and_the_blocks_and_stays_private():
    text = flat(WEBMAP)
    for phrase in ("BlueMap", "Fabric on Minecraft 26.3", "Dynmap", "**One extra TCP port**", "Apex fact", "Never put the server address or the port in this repository",
                   "`mods` folder", "`config/bluemap/`", "`accept-download`", "`webserver.conf`", "`/bluemap`", "Java 25", "`config/bluemap/packs/`",
                   "`nc_*` replica blocks", "`cyberblock:nc_concrete_light`", "`nc_neon_<dye>`", "`/bluemap purge <map>`", '`"webMap": false`', "`true` yourself",
                   "void world", "no server was run here"):
        assert phrase in text, f"WEBMAP.md does not say {phrase!r}"
    assert not re.search(r"\d+\.\d+\.\d+\.\d+|:\d{4,5}\b", WEBMAP), "no address or port number belongs in the web map page"
    assert "(HOW-TO-PLAY.md#night-city-as-minecraft-phase-3)" in WEBMAP


def test_the_web_map_page_lists_every_replica_block_the_materials_table_makes():
    for code in range(1, 11):
        assert f"nc_{cbmath.SURFACE_MATERIALS[code]}" in WEBMAP, cbmath.SURFACE_MATERIALS[code]


def test_re_test_wave_b_phase_3_sits_at_the_top_and_has_the_plan_checks():
    assert TESTING.index(RETEST_HEADING) < TESTING.index("## Re-test: Wave A") < TESTING.index("## Re-test Q (do this first)")
    retest = section_of(TESTING, RETEST_HEADING)
    one_screen = retest.split("**What to try (one screen):**", 1)[1].split("| Item |", 1)[0].strip().splitlines()
    assert len(one_screen) <= 15
    for n in range(1, 11):
        assert f"### P3-{n}." in retest, f"P3-{n}"
    text = flat(retest)
    for fact in ("two streets", "neon glowing", "the replica **stays**", "builds a wall", "stops V", "figures", "wreck", "Cyberpunk night",
                 "Meet Up", "`/cyberblock meetup <friend>`", "comes back in about 10 s", "Night City (the host's game) is unchanged",
                 "A second host", "four hosts", "Atlas", "BlueMap", "B1 to B5, ENV, G1 to G3, G5 and G6", "CBP0", "descopes"):
        assert fact in text, f"Re-test: Wave B, Phase 3 does not say {fact!r}"


def test_re_test_wave_b_phase_3_has_one_line_per_runtime_path_of_the_script_and_dll_packages():
    table = flat(section_of(TESTING, "### P3-9. Which way is it working? (the runtime paths)"))
    for feature, word in (("Materials of the probe hits", "material"), ("Doors and devices", "device"), ("Weather", "weather"), ("Teleport (Meet Up)", "teleport"),
                          ("Park", "park"), ("Server", "replica")):
        assert f"| {feature} | `{word}" in table, feature
    for fact in ("`park: on (<reason>)`", "`park: off (<reason>)`", "`no ground`", "`timeout`", "`blocked (reason)`", "mesh's appearance name",
                 "search for the word"):
        assert fact in table or fact.lower() in table.lower(), fact


def test_the_testing_plan_intro_and_the_old_sections_are_still_in_their_order():
    assert TESTING.index("## Re-test Q (do this first)") < TESTING.index("## In-game fixes 3") < TESTING.index("## Re-test 3 (do this first)")
    assert "Re-test: Wave B, Phase 3 first" in flat(TESTING[:1200])


def test_the_changelog_has_a_phase_3_entry_above_the_join_password_one():
    assert CHANGELOG.index("## [1.0.0]") < CHANGELOG.index("### Unreleased additions: Phase 3") < CHANGELOG.index("### Unreleased additions: join password")
    text = flat(section_of(CHANGELOG, "### Unreleased additions: Phase 3 (multiplayer plan, package 3P)"))
    for phrase in ("`ground.m`", "`unground.seen`", "`env`", "`vteleport`/`vteleported`", "`park`/`parked`", "`maptile`", "`materials`", "`tools.fakehost --replica`",
                   "`--shift`", "`--env-hours`", "`tools.mpcheck replica twohosts2 meetup`", "HOW-TO-PLAY", "`docs/WEBMAP.md`", "Re-test: Wave B, Phase 3"):
        assert phrase in text, f"CHANGELOG does not say {phrase!r}"


def test_the_descopes_note_says_the_web_map_exists_and_lists_the_risks_of_the_plan():
    text = flat(DESCOPES)
    for phrase in ("Web map: not skipped", "BlueMap build for Fabric on Minecraft 26.3 exists", "docs/WEBMAP.md", "Material accuracy", "Weather", "Replica size",
                   "Render suppression", "re-mesh", "Phase 0 check (b)", "working** table", "Contract additions made by 3P", "`maptile`"):
        assert phrase in text, f"phase3-descopes.md does not say {phrase!r}"


def test_the_phase_3_docs_hold_no_private_values():
    for name, text in (("HOW-TO-PLAY section", section_of(GUIDE, GUIDE_HEADING)), ("WEBMAP", WEBMAP), ("descopes", DESCOPES), ("Re-test", section_of(TESTING, RETEST_HEADING))):
        assert not re.search(r"(?<![A-Za-z0-9])[A-Za-z]:[\\/]", text), f"{name}: a drive-letter path"
        assert not re.search(r"APEX_FTP_PASS\s*=|ftp://|\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}", text), f"{name}: a secret or an address"
