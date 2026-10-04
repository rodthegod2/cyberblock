"""Phase 7 docs (megaplan task 7-P1): the player guide sections, Re-test: Wave C, Phase 7 and the changelog entry say what the plan says, every link
resolves, and nothing private is in them."""
import re
from pathlib import Path

from test_protocol_md import GUIDE, README, anchors, section_of

ROOT = Path(__file__).resolve().parent.parent
TESTING = (ROOT / "docs" / "TESTING-PLAN.md").read_text(encoding="utf-8")
CHANGELOG = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
TALKING = "### Talking to Night City (Phase 7)"
HUB = "### The Afterlife hub (Phase 7)"
TABLE = "### The Holo-Table (Phase 7)"
RETEST = "## Re-test: Wave C, Phase 7 (NPC interaction, the Afterlife hub and the Holo-Table)"
ENTRY = "### Unreleased additions: Phase 7 (multiplayer plan, package 7P)"


def flat(text: str) -> str:
    return " ".join(text.split())


def plain(text: str) -> str:
    """Lower case, one line, no bold marks: the guide's emphasis does not matter to what it says."""
    return flat(text).replace("**", "").lower()


def test_guide_talking_to_night_city_says_what_the_plan_promises():
    text = plain(section_of(GUIDE, TALKING))
    for phrase in ("right-click a figure that has a job", "original text", "/dialogue/`", "ripperdoc", "vendor", "fixer", "bartender", "medic", "mechanic",
                   "surgery minigame", "timing bar", "30 second debuff", "never kills you", "`ripperdoc` content", "`shop` content", "buy and sell", "`contract` content",
                   "deliver an item", "defeat a number of hostiles of one faction", "reach a landmark", "breach a device", "rumours", "drinks", "heals Steve for eddies",
                   "elytra and armour", "vendor type", "`npc_role`", "`skin_map`", "always there, whoever is online", "true Minecraft shop screen", "real Cyberpunk money",
                   "One unit per click", "up to ten with Shift+click", "Night City vendor...", "ripperdoc of the prologue", "quest-critical", "pass-through",
                   "the game's own way", "says \"Not now\"", "Phase 0 check (f)", "written fallback", "Re-test: Wave C, Phase 7"):
        assert plain(phrase) in text, f"HOW-TO-PLAY.md Talking to Night City does not say {phrase!r}"


def test_guide_the_afterlife_hub_says_what_the_plan_promises():
    text = plain(section_of(GUIDE, HUB))
    for phrase in ("`cyberblock:afterlife`", "always day and nothing hostile spawns", "even when no host is online", "`/cyberblock hub`", "hub portal", "pause screen",
                   "back to where you came from", "full-screen Minecraft", "V is parked", "invisible, invulnerable, unseen", "ignores V", "saving switched off",
                   "releases V by itself", "nobody stays parked", "you simply walk in", "ripperdoc corner", "row of shops", "Match Terminal", "coming in Phase 8",
                   "leaderboards", "match wins", "gigs done", "chaos", "Holo-Table", "Phase 0 check (g)", "fallback", "`park: on ...`",
                   "The cover only ever goes up over a parked V", "taken straight back out with the reason", "the hub picks up again", "`/login`"):
        assert plain(phrase) in text, f"HOW-TO-PLAY.md The Afterlife hub does not say {phrase!r}"


def test_guide_the_holo_table_says_what_the_plan_promises():
    text = plain(section_of(GUIDE, TABLE))
    for phrase in ("shared live 3D model", "**Drag** to rotate", "**scroll** to zoom", "tilt", "2D map", "Atlas", "head and name", "team colour", "`[V]`", "in a car",
                   "in combat", "downed", "NPC density", "enemy hot spots", "Server Heat", "Gang Wars", "Van drones", "`landmark` content", "team's base", "Spawn Beacon",
                   "join a friend", "Minecraft-only player", "V is teleported", "snaps V to the ground", "you leave the hub first", "5 seconds of spawn protection",
                   "60 second cooldown",
                   "locked match zone", "only your team's spawns", "fast-travel points", "Privacy", "hide my pin", "scan", "spectators and the Overlord see all",
                   "falls back to a **2D map**", "\"no ground\" or \"timeout\""):
        assert plain(phrase) in text, f"HOW-TO-PLAY.md The Holo-Table does not say {phrase!r}"


def test_guide_links_the_new_sections_from_each_other_the_commands_and_the_readme():
    assert {"talking-to-night-city-phase-7", "the-afterlife-hub-phase-7", "the-holo-table-phase-7"} <= anchors(GUIDE)
    assert GUIDE.index("### Your cyber skin (Phase 5)") < GUIDE.index(TALKING) < GUIDE.index(HUB) < GUIDE.index(TABLE) < GUIDE.index("## 6. Game modes and the starter kit")
    assert "(#the-holo-table-phase-7)" in section_of(GUIDE, TALKING) and "(#talking-to-night-city-phase-7)" in section_of(GUIDE, HUB)
    assert "`/cyberblock hub`" in flat(section_of(GUIDE, "### Commands")) and "(#the-afterlife-hub-phase-7)" in flat(section_of(GUIDE, "### Commands"))
    for anchor in ("talking-to-night-city-phase-7", "the-afterlife-hub-phase-7", "the-holo-table-phase-7"):
        assert f"docs/HOW-TO-PLAY.md#{anchor}" in README


def test_re_test_wave_c_phase_7_is_the_first_section_and_has_the_plan_checks():
    assert TESTING.index(RETEST) < TESTING.index("## Re-test: Wave B (Phases 3, 4, 5)") < TESTING.index("## Re-test: Wave A")
    retest = section_of(TESTING, RETEST)
    one_screen = retest.split("**What to try (one screen):**", 1)[1].split("| Item |", 1)[0].strip().splitlines()
    assert len(one_screen) <= 15
    for n in range(1, 11):
        assert f"### P7-{n}." in retest, f"P7-{n}"
    text = flat(retest)
    for fact in ("talk to Night City's people", "open even with no host online", "`/give <guest> cyberblock:eddies 64`", "Phase 0 is still not run", "**(f)**", "**(g)**",
                 "a vendor figure", "a fixer", "ripperdoc", "surgery minigame", "timing bar", "30 second debuff", "never killed", "`/cyberblock hub`", "full-screen Minecraft",
                 "**parked**", "walks right past V", "ignores", "Leave with `/cyberblock hub` again", "close Cyberpunk", "V must be released", "coming in Phase 8",
                 "Holo-Table", "3D model", "2D", "`[V]`", "district landmark", "V is **teleported**", "Steve stands where V is", "5 seconds of spawn protection", "60 second cooldown",
                 "Spawn Beacon", "join a friend", "hide my pin", "gun shop", "real Cyberpunk money", "ripperdoc of the prologue", "pass-through", "fast-travel", "Not now",
                 "Never over a free V", "taken straight back out", "Start Cyberpunk again", "Before `/login`", "not an operator", "you leave the hub **first**",
                 "**not** put back where it was parked", "Shift+click", "Night City vendor...", "`tools.fakehost --vendor`", "`tools.mpcheck hub`", "join password",
                 "`--park-refuse`", "descopes"):
        assert fact in text, f"Re-test: Wave C, Phase 7 does not say {fact!r}"


def test_re_test_phase_7_names_the_runtime_paths_and_the_descopes():
    table = flat(section_of(TESTING, "### P7-9. Which way is it working? (the runtime paths)"))
    for fact in ("`park:`", "full park", "fallback", "nearest fast-travel point", "unavailable: unseen", "`vendor`", "`vendors: path: ...`", "pass-through", "`fast travel`",
                 "`Holo-Table path active`", "server log", "who and how", "`V not parked`"):
        assert fact in table, fact
    risks = flat(section_of(TESTING, "### Phase 7 descopes and risks (short)"))
    for fact in ("**(f)**", "stock", "2D Atlas screen", "**(g)**", "Cyberpunk car", "elytra and armour", "Fast travel", "runtime check", "Match Terminal", "Phase 8"):
        assert fact in risks, fact


def hub_button_labels() -> tuple[str, str]:
    """The pause-screen button 7H ships (HubClient.label): its English fallbacks for cyberblock.hub.button.enter and .leave."""
    java = (ROOT / "mc/src/client/java/dev/cyberblock/client/hub/HubClient.java").read_text(encoding="utf-8")
    enter = re.search(r'"cyberblock\.hub\.button\.enter", "([^"]+)"', java).group(1)
    leave = re.search(r'"cyberblock\.hub\.button\.leave", "([^"]+)"', java).group(1)
    return enter, leave


def test_the_docs_name_the_real_hub_button_of_the_pause_screen():
    enter, leave = hub_button_labels()
    assert (enter, leave) == ("Afterlife hub", "Leave the Afterlife")
    guide = flat(section_of(GUIDE, HUB)).split("**Getting in and out.**", 1)[1].split("**If you are a host", 1)[0]
    step = flat(section_of(TESTING, "### P7-5. The hub, with V parked"))
    for name, text in (("HOW-TO-PLAY Getting in and out", guide), ("TESTING-PLAN P7-5", step)):
        assert f"**{enter}**" in text and f"**{leave}**" in text and "top right of the pause screen" in text, name
        assert "Cyberblock..." not in text, f"{name} sends the tester to the settings button"
    assert step.count(f"**{enter}** button") >= 2  # the locked guest's way in (step 8) and the guest's (step 9) name it too


def test_re_test_phase_7_drops_the_link_in_the_hub_and_checks_the_7v_amendment_lines():
    step5 = flat(section_of(TESTING, "### P7-5. The hub, with V parked"))
    for fact in ("Drop the link while in the hub", "close the Minecraft client", "like a crash", "V visible, moving, the camera free and saving allowed",
                 "`park: off (Minecraft lost)`", "`hub: forgotten (Minecraft lost)`", "`hub: forgotten (the link dropped)`", "rejoin"):
        assert fact in step5, f"P7-5 does not say {fact!r}"
    step7 = flat(section_of(TESTING, "### P7-7. A Cyberpunk shop as a Minecraft screen (host)"))
    for fact in ("A ripperdoc as the host", "`vendors: <name> -> minecraft screen open`", "for **both**", "**no** `vendors: <name> pass-through`"):
        assert fact in step7, f"P7-7 does not say {fact!r}"
    step8 = flat(section_of(TESTING, "### P7-8. Fast travel from the Holo-Table (host)"))
    for fact in ("Picked in the hub", "you leave the hub first", "Steve follows", "V **stays** at the destination", "when you leave the hub",
                 "`fast travel: to <name> (Holo-Table)` comes **after** `park: off (hub exit)`", "`fast travel: to <name> when V leaves the hub (V is parked)`",
                 "leaving the hub first"):
        assert fact in step8, f"P7-8 does not say {fact!r}"
    # the lines the steps grep are the ones the scripts print
    scripts = ROOT / "cp/scripts/Cyberblock"
    hub, menus, vendors, main = ((scripts / f).read_text(encoding="utf-8") for f in ("Hub.reds", "Menus.reds", "Vendors.reds", "Cyberblock.reds"))
    assert 'hub: forgotten (\\(reason))' in hub and 'CyberblockHub.Forget("Minecraft lost")' in main and 'Forget("the link dropped")' in hub
    assert '"hub exit"' in hub and 'fast travel: to \\(pointName) (Holo-Table)' in menus and 'when V leaves the hub (V is parked)' in menus
    assert '-> minecraft screen open' in vendors and 'pass-through (\\(why))' in vendors


def test_the_changelog_has_a_phase_7_entry_above_the_wave_b_integration_one():
    assert CHANGELOG.index(ENTRY) < CHANGELOG.index("### Unreleased additions: Wave B integration")
    text = flat(section_of(CHANGELOG, ENTRY))
    for phrase in ("`hub`", "`park`", "on entering `hub in:true` then `park on:true`", "on leaving `park on:false` then `hub in:false`", "`parked ok:false` takes the player out",
                   "hears the entering pair again", "before the `vteleport`", "no way in before `/login`", "`reply` with `kind:\"vendor\"`", "sent unrequested",
                   "`uiact` `buy` and `sell`", "`fasttravel`", "`parked` relay row", "differ from megaplan section 1", "`tools.fakehost --vendor`", "`--park-refuse`",
                   "`tools.mpcheck hub`", "join password", "`cyberblock:afterlife`", "HOW-TO-PLAY", "Re-test: Wave C, Phase 7", "(f) and (g)"):
        assert phrase in text, f"CHANGELOG does not say {phrase!r}"


def test_the_phase_7_docs_hold_no_private_values():
    for name, text in (("guide talking", section_of(GUIDE, TALKING)), ("guide hub", section_of(GUIDE, HUB)), ("guide table", section_of(GUIDE, TABLE)),
                       ("Re-test", section_of(TESTING, RETEST)), ("changelog", section_of(CHANGELOG, ENTRY))):
        assert not re.search(r"(?<![A-Za-z0-9])[A-Za-z]:[\\/]", text), f"{name}: a drive-letter path"
        assert not re.search(r"APEX_FTP_PASS\s*=|ftp://|\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|:\d{4,5}\b", text), f"{name}: a secret, an address or a port"
