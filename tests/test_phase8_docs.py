"""Phase 8 docs (megaplan task 8-P1): the player guide section, Re-test: Wave C, Phase 8, the descopes note and the changelog entry say what the plan says, every link
resolves, and nothing private is in them."""
import re
from pathlib import Path

from test_protocol_md import GUIDE, README, anchors, section_of

ROOT = Path(__file__).resolve().parent.parent
TESTING = (ROOT / "docs" / "TESTING-PLAN.md").read_text(encoding="utf-8")
CHANGELOG = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
DESCOPES = (ROOT / "docs" / "notes" / "phase8-descopes.md").read_text(encoding="utf-8")
MODES = "### Modes: the Van, the Overlord, matches and more (Phase 8)"
ANCHOR = "modes-the-van-the-overlord-matches-and-more-phase-8"
RETEST = "## Re-test: Wave C, Phase 8 (roles and modes)"
ENTRY = "### Unreleased additions: Phase 8 (multiplayer plan, package 8P)"


def flat(text: str) -> str:
    return " ".join(text.split())


def plain(text: str) -> str:
    """Lower case, one line, no bold marks: the guide's emphasis does not matter to what it says."""
    return flat(text).replace("**", "").lower()


def test_guide_modes_says_what_the_plan_promises():
    text = plain(section_of(GUIDE, MODES))
    for phrase in ("nothing in a mode touches a player who did not opt in", "`/cyberblock guests spawns on|off`", "off by default", "Minecraft squads", "always gets the Minecraft squads",
                   # the Van
                   "The Hacker in the Van", "netrunner seat", "3D miniature of the host's area", "live photo feed", "drones", "limited range from the Van and a battery",
                   "full Hack Wheel", "runs in the host's real game", "`hacks` switch",
                   # the Overlord
                   "Overlord Console", "spawn palette", "gangers, NCPD, Max-Tac, drones", "blackout, an EMP, an airstrike, a traffic jam, a supply drop, weather, a cyberpsychosis outbreak",
                   "timeline", "Threat Points", "regenerate", "bigger spawn costs more", "3 second warning", "minimum distance", "cap on simultaneous spawns", "Chill, Normal or Brutal",
                   "only to players who are in an active match and opted in", "Auto-Director",
                   # matches and PvP
                   "Match Terminal", "only Cyberpunk-linked players create them", "`match_mode` content", "teammate, a hacker (in a Van), a spectator or the Overlord",
                   "not saved", "off for everyone until a match turns it on", "only if both have PvP on", "red name tag", "15 seconds", "through Steve's hearts",
                   "Gang Wars", "team colours", "districts", "spawn points", "scoreboard", "capture points", "reputation",
                   # player hacks
                   "Short Circuit", "Reboot Optics", "glitch overlay", "ReShade glitch", "Cripple Movement", "Ping", "Overheat", "Memory Wipe", "drops at the victim's feet", "never deleted",
                   "A Minecraft-only guest who has not opted in can never be hacked", "war zone", "Intrusion detected", "4 x 4 grid, 5 seconds", "Firewall", "Netrunner", "19th piece",
                   "resists", "RAM", "cooldown",
                   # Heat, down, spectator
                   "Server Heat", "0 to 100", "explosions, kills, destroyed cars, failed Breaches and PvP kills", "decays", "`heat_tier` content", "Max-Tac hunts the player who caused the most chaos",
                   "full response on everyone", "real wanted level", "goes back to what it was", "60 second bleed-out", "holding right-click on you for 4 seconds", "Trauma Team", "30 second countdown",
                   "Minecraft's death screen", "`/cyberblock spectate`", "vanilla spectator movement", "every pin", "caster overlay", "kill feed",
                   # limits
                   "Phase 0 check (d)", "Turrets are always Minecraft-side", "`spawn: path: ...`", "check (e)", "does not change how Night City treats a team", "The biggest phase",
                   "`tools.fakehost --modes`", "`tools.mpcheck match heat overlord`", "Re-test: Wave C, Phase 8"):
        assert plain(phrase) in text, f"HOW-TO-PLAY.md Modes does not say {phrase!r}"


def test_guide_links_the_modes_from_the_commands_the_interaction_map_and_the_readme():
    assert ANCHOR in anchors(GUIDE)
    assert GUIDE.index("### The Holo-Table (Phase 7)") < GUIDE.index(MODES) < GUIDE.index("## 6. Game modes and the starter kit")
    commands = flat(section_of(GUIDE, "### Commands"))
    assert "`/cyberblock spectate`" in commands and "`/cyberblock guests spawns on|off`" in commands and f"(#{ANCHOR})" in commands
    interaction = flat(section_of(GUIDE, "### The interaction map (Phase 6)"))
    assert "Overlord Console" in interaction and f"(#{ANCHOR})" in interaction and "a later phase (Phase 8)" not in interaction
    assert f"docs/HOW-TO-PLAY.md#{ANCHOR}" in README and "Phase 8" in README
    for target in re.findall(r"\]\(#([^)]+)\)", section_of(GUIDE, MODES)):
        assert target in anchors(GUIDE), target


def test_re_test_wave_c_phase_8_follows_phase_7_and_has_the_plan_checks():
    assert TESTING.index("## Re-test: Wave C, Phase 7") < TESTING.index(RETEST) < TESTING.index("## Re-test: Wave B (Phases 3, 4, 5)") < TESTING.index("## Re-test: Wave A")
    retest = section_of(TESTING, RETEST)
    one_screen = retest.split("**What to try (one screen):**", 1)[1].split("| Item |", 1)[0].strip().splitlines()
    assert len(one_screen) <= 15
    for n in range(1, 12):
        assert f"### P8-{n}." in retest, f"P8-{n}"
    text = plain(retest)
    for fact in ("Phase 0 is still not run.", "**(d)**", "**(e)**", "`/cyberblock guests spawns on`", "off by default",
                 "Van", "netrunner seat", "3D miniature", "photo feed", "drone", "range and a battery", "Short Circuit", "`/cyberblock guests hacks off`", "blocked",
                 "Overlord Console", "spawn palette", "Threat Points", "3 second warning", "sound and a marker", "minimum distance", "cap on simultaneous spawns", "Chill, Normal or Brutal",
                 "Auto-Director", "nothing happens near the host", "opted in", "Match Terminal", "only a Cyberpunk-linked player may", "team deathmatch", "red name tag",
                 "PvP is off for everyone by default", "through Steve's hearts", "wait 15 seconds", "Gang Wars", "capture point", "`attitude`",
                 "Intrusion detected", "short Breach", "4 x 4, 5 seconds", "Reboot Optics", "ReShade glitch", "`cb_glitch`", "Cripple Movement", "Memory Wipe", "Firewall", "Netrunner",
                 "**shorter**", "Never a bystander", "war zone", "Heat meter on the HUD", "tier 4 Max-Tac hunts the player who caused the most chaos", "real wanted level", "`stats.wanted`",
                 "no write", "Minecraft squads", "Push it to **5**", "60 second bleed-out", "holds right-click on you for 4 seconds", "Minecraft's death screen", "Trauma Team",
                 "30 second countdown", "flyer", "not enough eddies", "`/cyberblock spectate`", "vanilla spectator movement", "every pin", "caster overlay", "scoreboard", "kill feed",
                 "`/cyberblock guests spawns off`", "The join password still locks players", "Nothing without opt-in", "Cleanup",
                 "tools.fakehost --modes", "tools.mpcheck match heat overlord", "`PHASE8_COMMANDS`", "descopes"):
        assert plain(fact) in text, f"Re-test: Wave C, Phase 8 does not say {fact!r}"


def test_re_test_phase_8_names_the_runtime_paths_and_the_descopes():
    table = plain(section_of(TESTING, "### P8-9. Which way is it working? (the runtime paths)"))
    for fact in ("`spawn: path`", "real Night City NPCs", "Codeware's entity system", "fallback", "Minecraft squads", "`heat: path`", "wanted level is written", "`PreventionSystem`",
                 "`attitude: path`", "no Gang Wars reputation", "`glitch`", "`cb_glitch`", "server log", "`<old> -> <new>`", "`match`", "PvP refusal", "player hack"):
        assert plain(fact) in table, fact
    risks = plain(section_of(TESTING, "### Phase 8 descopes and risks (short)"))
    for fact in ("**(d)**", "CET D2 and D3", "turrets are Minecraft-side", "**(e)**", "no write", "Gang Wars reputation does not change", "The biggest phase", "8a", "8b",
                 "`PHASE8_COMMANDS`", "simulated server only"):
        assert plain(fact) in risks, fact


def test_the_descopes_note_names_every_fallback_and_the_assumptions():
    text = plain(DESCOPES)
    for fact in ("**(d) Spawning Night City enemies.**", "**Minecraft squad**", "`spawn: path: ...`", "Turrets are always Minecraft-side", "`turret` is not a `spawn.rec`", "**(e) The wanted level",
                 "Gang Wars team's reputation does **not** change", "`heat: path: ...`", "`attitude: path: ...`", "off by default", "8D, 8S, 8A, 8T, 8X, 8M, 8O, 8V, 8P",
                 "`PHASE8_COMMANDS`", "SKIP", "a SKIP is not a pass", "`/damage", "8a", "8b", "`tests/test_phase8_protocol.py`", "does not reply to `attitude`, `heat` or `glitch`"):
        assert plain(fact) in text, f"phase8-descopes.md does not say {fact!r}"


def test_the_changelog_has_a_phase_8_entry_above_the_wave_b_integration_one():
    assert CHANGELOG.index("### Unreleased additions: Phase 7 (multiplayer plan, package 7P)") < CHANGELOG.index(ENTRY) < CHANGELOG.index("### Unreleased additions: Wave B integration")
    text = plain(section_of(CHANGELOG, ENTRY))
    for phrase in ("`spawn`", "`spawnresult`", "rate row 10 per second, burst 40", "`heat`", "real wanted level", "again after every host `hello`", "`attitude`", "`glitch`",
                   "Every effect ends with the session", "spawns` toggle (off by default)", "Minecraft squads", "Phase 0 check (d)", "`turret` is not a `rec`", "`hs_ack.features` bit 32 (modes)",
                   "19th cyberware id `netrunner`", "`firewall`", "differences from megaplan section 1", "`tools.fakehost --modes`", "`tools.mpcheck match`", "`PHASE8_COMMANDS`",
                   "SKIP", "never as a pass", "HOW-TO-PLAY", "Re-test: Wave C, Phase 8", "(d) and (e)", "`docs/notes/phase8-descopes.md`"):
        assert plain(phrase) in text, f"CHANGELOG does not say {phrase!r}"


def test_the_phase_8_docs_hold_no_private_values():
    for name, text in (("guide modes", section_of(GUIDE, MODES)), ("Re-test", section_of(TESTING, RETEST)), ("changelog", section_of(CHANGELOG, ENTRY)), ("descopes", DESCOPES)):
        assert not re.search(r"(?<![A-Za-z0-9])[A-Za-z]:[\\/]", text), f"{name}: a drive-letter path"
        assert not re.search(r"APEX_FTP_PASS\s*=|ftp://|\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|:\d{4,5}\b", text), f"{name}: a secret, an address or a port"
