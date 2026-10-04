"""Phase 6 docs (megaplan task 6-P1): the player guide's Redstone hacking, Breach Protocol and interaction map sections, Re-test 6, the changelog entry and the
descopes note say what the plan says, the guide's numbers are the protocol's, every link resolves, and nothing private is in them."""
import re
from pathlib import Path

from test_phase6_protocol import AREA_HACKS, BREACH_CODES, REMOTE_HACKS
from test_protocol_md import GUIDE, README, anchors, section_of

ROOT = Path(__file__).resolve().parent.parent
TESTING = (ROOT / "docs" / "TESTING-PLAN.md").read_text(encoding="utf-8")
CHANGELOG = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
DESCOPES = (ROOT / "docs" / "notes" / "phase6-descopes.md").read_text(encoding="utf-8")
REDSTONE, BREACH, MAP = "### Redstone hacking (Phase 6)", "### Breach Protocol (Phase 6)", "### The interaction map (Phase 6)"
RETEST_HEADING = "## Re-test 6 (Phase 6: hacking and the interaction map)"
CHANGELOG_HEADING = "### Unreleased additions: Phase 6 (multiplayer plan, package 6P)"


def flat(text: str) -> str:
    return " ".join(text.split())


def test_guide_has_the_redstone_hacking_section_with_the_plan_blocks_and_limits():
    section = section_of(GUIDE, REDSTONE)
    rows = re.findall(r"^\| \*\*([^*|]+)\*\* \| ([^|]+) \| ([^|]+) \|$", section, re.M)
    assert [r[0] for r in rows] == ["Device Relay", "Proximity Sensor", "Heat Sensor", "Breach Port", "Charge", "Cyberdeck block"]
    text = flat(section)
    for phrase in ("**The Cyberdeck Link.**", "scan the device with the hack key (G)", "right-click the relay or sensor block with the Cyberdeck Link item", "unresolved",
                   "rising edge", "1 to 16 blocks", "0 to 15", "times 3", "costs RAM", "Cyberdeck block", "refills slowly", "a real Cyberpunk explosion",
                   "at most twice a second", "more than 8 times in 4 seconds", "frozen for 30 seconds", "`cyberblock.redstone.loop`", "A redstone trap for NPCs",
                   "`/cyberblock guests hacks on|off`", "`/cyberblock guests explosions on|off`", "the host's own are never blocked", "blocked"):
        assert phrase in text, f"HOW-TO-PLAY.md Redstone hacking does not say {phrase!r}"


def test_guide_breach_section_gives_the_protocols_codes_sizes_and_rules():
    section = section_of(GUIDE, BREACH)
    text = flat(section)
    assert all(f"`{code}`" in text for code in BREACH_CODES)
    for phrase in ("Right-click a Net Port", "5 x 5", "7 x 7", "`breach_tier`", "buffer** (4 to 8", "first pick is in the top row", "column", "row", "cannot be picked twice",
                   "One to three sequences", "strength 1 to 3", "real action in the host's Night City", "disable the cameras", "reveal enemies", "overload", "datapack",
                   "raises an alarm in the host's game", "Heat", "wanted level goes up by one **only if the game allows that**", "Firewall", "18th", "played on the server",
                   "`/cyberblock guests hacks on|off`", "Re-test 6"):
        assert phrase in text, f"HOW-TO-PLAY.md Breach Protocol does not say {phrase!r}"


def test_guide_interaction_map_lists_every_entry_of_the_plan_and_the_fallbacks():
    section = section_of(GUIDE, MAP)
    rows = re.findall(r"^\| ([^|]+) \| ([^|]+) \| ([^|]+) \| ([^|]*) \|$", section, re.M)
    tools = " ".join(r[1] for r in rows)
    for tool in ("Hack Wheel", "Vehicle Panel", "Traffic Tool", "Net Map", "Comms Panel", "Net Port", "Overlord Console", "TNT"):
        assert tool in tools, tool
    text = flat(section)
    for phrase in ("**lock the doors** (2 minutes), **kill the engine** (30 seconds), **force a brake** (a few seconds), **jam it** (3 seconds)",
                   "**start it remotely** (that one stays)", "it is a screen, not an item", "**switch the lights** of the intersection nearest V", "gridlock a road",
                   "black out the lights around V for 1, 2 or 5 minutes", "camera's feed", "outline", "ping", "photo feed", "toast and a locator-bar waypoint",
                   "up to 64 characters", "Overlord Console", "Phase 8", "everything the host's game is showing the server", "**50 metres of V**", "check (h)",
                   "out of range", "fall back to the device layer", "within 150 metres of V", "not** Cyberpunk's own camera view", "within your view distance",
                   "128 x 128 map colours", "once a second", "only while someone is watching it", "low-resolution by design", "more than half a millisecond",
                   "the server draws a **top-down replica view** around V", "no Cyberpunk change at all", "`/cyberblock guests hacks on|off`", "Which way is it working?"):
        assert phrase in text, f"HOW-TO-PLAY.md The interaction map does not say {phrase!r}"


def test_guide_and_retest_cover_the_review_fixes_abort_timed_effects_reach_from_v_and_the_feed_toggle():
    breach, tools = flat(section_of(GUIDE, BREACH)), flat(section_of(GUIDE, MAP))
    for phrase in ("**Closing the screen after your first pick is a failure**", "The timer starts when the screen opens", "same timer still running",
                   "you must wait **10 seconds**", "walking more than 8 blocks away from the Net Port counts as closing it", "at most one alarm a minute",
                   "A refused Breach does not open"):
        assert phrase in breach, phrase
    assert "locked for 30 seconds" not in breach and "starts running with your **first pick**" not in breach
    for phrase in ("**from V, not from you**", "work from anywhere in the world", "**Nothing a hack does lasts.**", "a remote start simply stays",
                   "puts everything back **at once** before **every save**", "the host's `hacks` toggle (also for the photo feed)",
                   "every guest watching the feed loses it at once"):
        assert phrase in tools, phrase
    for stale in ("each for **30 seconds**", "cannot **save** while a hack is still running", "there is **no picture**", "switch a district's traffic lights"):
        assert stale not in tools, stale
    retest = flat(TESTING.split(RETEST_HEADING, 1)[1].split("## Re-test: Wave B", 1)[0])
    for phrase in ("**No free re-roll:**", "**same grid** with the timer still running", "says to wait (10 s)", "`alarm merged`",
                   "**Every effect wears off, each after its own time:**", "the doors unlock after **120 s**", "**Remote start is not undone**",
                   "the save happens at once and the car is **unlocked first**", "`rhack: cleared`", "There is **no Vehicle Panel item**",
                   "**more than 50 metres from V** but still shown as a figure", "`hack range: fallback active: ...`", "the reach is measured from V",
                   "flips **one intersection**", "guest's picture **stops at once**", "`feed: off (Minecraft stopped asking)` (no viewer left)",
                   "a **top-down replica view** around V", "`feed: <host> feedreq on`"):
        assert phrase in retest, phrase
    for stale in ("the save waits until the effect is over", "`cyberblock.breach.locked_out`", "**Minecraft draws no stand-in picture**", "`feedreq off`",
                  "**Vehicle Panel**, **Traffic Tool**, **Net Map** (an item)", "about **30 seconds** after each hack", "timer bar that **starts with your first pick**"):
        assert stale not in retest, stale
    p610 = flat(TESTING.split("### P6-10.", 1)[1].split("### ", 1)[0])
    assert "`/cyberblock guests hacks off`: **expect** the guest's picture **stops at once**" in p610


def test_guide_range_and_names_agree_with_the_protocol():
    from test_protocol_md import TEXT
    assert "every streamed target" in flat(TEXT) and "more than 50 m from V" in flat(TEXT) and len(REMOTE_HACKS) == 11 and len(AREA_HACKS) == 5
    text = flat(section_of(GUIDE, MAP))
    for word in ("lock", "engine", "brake", "start", "jam"):  # the five vehicle hacks of the protocol, in player words
        assert word in text, word


def test_guide_older_sections_and_readme_point_at_the_new_ones():
    flat_guide = flat(GUIDE)
    for anchor in ("redstone-hacking-phase-6", "breach-protocol-phase-6", "the-interaction-map-phase-6"):
        assert f"(#{anchor})" in flat_guide and anchor in anchors(GUIDE), anchor
        assert f"docs/HOW-TO-PLAY.md#{anchor}" in README, anchor
    devices = flat(section_of(GUIDE, "### Devices (Phase 4)"))
    assert "opens the **Breach Protocol** ([Phase 6](#breach-protocol-phase-6))" in devices and "the **Traffic Tool** of [Phase 6](#the-interaction-map-phase-6)" in devices
    assert "later update" not in devices.split("**What each device is in Minecraft", 1)[1].split("**Waiting for Night City.**", 1)[0]  # no placeholder row is left
    assert "`/cyberblock guests hacks on|off`" in flat(section_of(GUIDE, "### Commands"))


def test_re_test_6_leads_the_testing_plan_and_has_the_plan_checks():
    assert TESTING.index(RETEST_HEADING) < TESTING.index("## Re-test: Wave B (Phases 3, 4, 5)") < TESTING.index("## Re-test: Wave A") < TESTING.index("## Re-test Q (do this first)")
    assert "Re-test: Wave B (Phases 3, 4, 5) first" in flat(TESTING[:1200])
    retest = section_of(TESTING, RETEST_HEADING)
    one_screen = retest.split("**What to try (one screen):**", 1)[1].split("| Item |", 1)[0].strip().splitlines()
    assert len(one_screen) <= 15
    for n in range(1, 11):
        assert f"### P6-{n}." in retest, f"P6-{n}"
    text = flat(retest)
    for fact in ("one test session for Phases 6 to 9", "Cyberdeck Link", "Device Relay", "Proximity Sensor", "redstone lamp", "**lever**", "rising edge", "Charge", "**button**",
                 "real explosion", "5 x 5 grid", "`1C 55 BD E9 7A FF`", "top row", "disable cameras", "alarm in the host's game", "Vehicle Panel", "Kill engine",
                 "out of range", "headline check", "Net Map", "Blackout", "devices within 150 m of V", "Traffic Tool", "gridlock", "Comms Panel", "toast", "locator-bar waypoint",
                 "128 x 128", "once a second", "top-down replica view", "0.5 ms a frame", "`/cyberblock guests hacks off`", "50 metres", "Phase 0's check (h)",
                 "Cyberblock.log", "descopes", "`tools.mpcheck breach`", "`tools.fakehost --hacks2`"):
        assert fact in text, f"Re-test 6 does not say {fact!r}"


def test_re_test_6_has_one_line_per_runtime_path_of_the_script_and_dll_packages():
    table = flat(section_of(TESTING, "### P6-9. Which way is it working? (the runtime paths)"))
    for feature, word in (("Traffic", "traffic"), ("Blackout", "blackout"), ("Vehicle hacks", "rhack"), ("Range", "hack range"), ("Breach", "breach"), ("Photo feed", "feed"),
                          ("Redstone", "redstone")):
        assert f"| {feature} | `{word}` |" in table, feature
    for fact in ("search for the word", "`cyberblock.hack.out_of_range`", "`feed: on (path: ...)`", "`feed: <host> feedreq on`", "`cyberblock.redstone.loop`",
                 "the compositor copy or the server's top-down replica stand-in (the fallback)", "(fallback)", "`rhack: traffic path: ...`", "`hack range: every streamed target (...)`"):
        assert fact in table, fact


def test_the_testing_plan_keeps_the_wave_b_blocks_and_the_older_ones_in_order():
    for older in ("## Re-test: Wave B, Phase 3", "## Re-test: Wave B, Phase 4", "## Re-test: Wave B, Phase 5"):
        assert TESTING.index(RETEST_HEADING) < TESTING.index(older) < TESTING.index("## Re-test: Wave A")


def test_the_changelog_has_a_phase_6_entry_above_the_wave_b_integration_one():
    assert CHANGELOG.index("## [2.0.0]") < CHANGELOG.index(CHANGELOG_HEADING) < CHANGELOG.index("### Unreleased additions: Wave B integration")
    text = flat(section_of(CHANGELOG, CHANGELOG_HEADING))
    for phrase in ("`rhack`", "`daemon`", "`feedreq`", "`feed`", "`hackresult`", "`cyberblock:act`", "`cyberblock:ui`", "`firewall`", "`tools.fakehost --hacks2`", "`tools.mpcheck breach`",
                   "`/cyberblock test breach <x> <y> <z> solve|fail`", "HOW-TO-PLAY", "Re-test 6", "`docs/notes/phase6-descopes.md`", "`cyberblock.hack.out_of_range`", "50 m", "bit 8",
                   "`shared/phase6-messages.json`", "`shared/rhack-vectors.json`", "`/cyberblock test act <t> <json>`", "`target:null`", "one alarm a minute"):
        assert phrase in text, f"CHANGELOG does not say {phrase!r}"


def test_the_descopes_note_lists_the_risks_of_the_plan_and_the_contract_limits():
    text = flat(DESCOPES)
    for phrase in ("Remote range (check (h))", "**every target the host's game streams**", "50 m from V", "`cyberblock.hack.out_of_range`",
                   "Traffic and blackout fall back to the device layer", "within 150 m of V", "No true Cyberpunk camera view", "replica", "low-resolution by design",
                   "0.5 ms of frame time", "a top-down replica render around V", "One contract, checked from four sides", "`shared/phase6-messages.json`",
                   "there is no save lock", "an owner gets one alarm a minute", "`alarm`", "Daemon ids are content",
                   "Heat", "One answer per `daemon`", "The `feed` is BOTH", "The first real run is the test", "Devtools command", "`/cyberblock test breach <x> <y> <z> solve|fail`",
                   "Loops", "merge order", "6D, 6S, 6A, 6B, 6R, 6I, 6P"):
        assert phrase in text or phrase.lower() in text.lower(), f"phase6-descopes.md does not say {phrase!r}"


def test_the_phase_6_docs_hold_no_private_values():
    for name, text in (("HOW-TO-PLAY sections", "\n".join(section_of(GUIDE, h) for h in (REDSTONE, BREACH, MAP))), ("descopes", DESCOPES),
                       ("Re-test", section_of(TESTING, RETEST_HEADING)), ("CHANGELOG entry", section_of(CHANGELOG, CHANGELOG_HEADING))):
        assert not re.search(r"(?<![A-Za-z0-9])[A-Za-z]:[\\/]", text), f"{name}: a drive-letter path"
        assert not re.search(r"APEX_FTP_PASS\s*=|ftp://|\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}", text), f"{name}: a secret or an address"
        assert not re.search(r"_PASS\s*=|joinPassword\"?\s*:\s*\"[^\"]", text), f"{name}: a password"


def test_the_lang_keys_the_phase_6_docs_promise_exist():
    """Re-test 6 and the protocol name action bar keys; each must be a key of en_us.json."""
    import json
    from test_protocol_md import TEXT
    lang = json.loads((ROOT / "mc/src/main/resources/assets/cyberblock/lang/en_us.json").read_text(encoding="utf-8"))
    named = set(re.findall(r"`(cyberblock\.(?:breach|guest|hack|rhack)\.[a-z_.]+)`", section_of(TESTING, RETEST_HEADING) + TEXT.split("## Phase 6", 1)[1].split("## Phase 7", 1)[0]))
    assert {"cyberblock.breach.cooldown", "cyberblock.guest.blocked", "cyberblock.hack.out_of_range"} <= named
    assert not named - set(lang), sorted(named - set(lang))
