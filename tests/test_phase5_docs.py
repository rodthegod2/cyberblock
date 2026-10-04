"""Phase 5 docs (megaplan task 5-P1): the player guide sections, Re-test: Wave B, Phase 5 and the changelog entry say what the plan says, every
link resolves, and nothing private is in them."""
import re
from pathlib import Path

from test_protocol_md import GUIDE, README, anchors, section_of

ROOT = Path(__file__).resolve().parent.parent
TESTING = (ROOT / "docs" / "TESTING-PLAN.md").read_text(encoding="utf-8")
CHANGELOG = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
FIGURES = "### Figures and skins (Phase 5)"
SKIN = "### Your cyber skin (Phase 5)"
RETEST = "## Re-test: Wave B, Phase 5 (figures and skins)"


def flat(text: str) -> str:
    return " ".join(text.split())


def test_guide_figures_and_skins_says_what_the_plan_promises():
    text = flat(section_of(GUIDE, FIGURES))
    for phrase in ("faction, role, gender and type", "Tyger Claws", "Valentinos", "Maelstrom", "6th Street", "Animals", "Scavengers", "Voodoo Boys", "Mox",
                   "Aldecaldos", "Arasaka", "Militech", "Kang Tao", "NCPD patrol and SWAT", "Max-Tac", "Trauma Team", "Netwatch", "a ripperdoc",
                   "a fixer", "a bartender", "cyberpsychos", "About 30 original skins", "no logos and no copied art", "Women are drawn on the slim model",
                   "walks", "runs", "aims", "fires", "crouches", "lies down when it dies", "red outline you can see through walls", "threat colour",
                   "heart particles", "About twelve vehicle models", "flashes its lights", "dark wreck for 5 seconds", "written fallback",
                   "`npcs: tags full", "`npcs: tags faction only", "`npc_role`", "`skin_map`", "walking above 0.6 m/s", "running above 3.2 m/s",
                   "`npcs: colour format hsb (unconfirmed)`", "default colour", "procedural pixel art"):
        assert phrase in text, f"HOW-TO-PLAY.md Figures and skins does not say {phrase!r}"


def test_guide_your_cyber_skin_says_what_the_plan_promises():
    text = flat(section_of(GUIDE, SKIN))
    for phrase in ("always looks like V", "male V", "Lucy", "slim model", "`/cyberblock body female`", "`/cyberblock body male`", "saved on the server",
                   "no option to turn it off", "inside the mod", "works offline", "`[V]`", "player list", "Allies", "nobody is an ally",
                   "keeps the normal Minecraft skin", "NPC figures follow their own gender"):
        assert phrase in text, f"HOW-TO-PLAY.md Your cyber skin does not say {phrase!r}"


def test_guide_links_the_new_sections_from_the_phase_3_section_the_commands_and_the_readme():
    assert {"figures-and-skins-phase-5", "your-cyber-skin-phase-5"} <= anchors(GUIDE)
    assert "(#figures-and-skins-phase-5)" in GUIDE.split("### Night City as Minecraft (Phase 3)", 1)[1].split("### Figures and skins", 1)[0]
    assert "`/cyberblock body male|female`" in flat(section_of(GUIDE, "### Commands")) and "(#your-cyber-skin-phase-5)" in flat(section_of(GUIDE, "### Commands"))
    assert "docs/HOW-TO-PLAY.md#figures-and-skins-phase-5" in README
    assert "a plain human figure for people" in flat(GUIDE)  # the Phase 3 description stays true for a build without Phase 5 tags


def test_re_test_phase_5_sits_next_to_phase_3_and_has_the_plan_checks():
    assert TESTING.index("## Re-test: Wave B, Phase 3") < TESTING.index(RETEST) < TESTING.index("## Re-test: Wave A")
    retest = section_of(TESTING, RETEST)
    one_screen = retest.split("**What to try (one screen):**", 1)[1].split("| Item |", 1)[0].strip().splitlines()
    assert len(one_screen) <= 15
    for n in range(1, 8):
        assert f"### P5-{n}." in retest, f"P5-{n}"
    text = flat(retest)
    for fact in ("after the Phase 3 section above and the Phase 4 section", "gangers in their gang's colours", "women on slim models", "aiming, firing", "ragdoll",
                 "a body lying down", "police car flashing", "red outline through it", "`[V]`", "`/cyberblock body female`", "Lucy on the slim model",
                 "`/cyberblock body male`", "tags faction only", "npcs: colour format hsb (unconfirmed)", "F1", "F2", "`CBP0`", "fixer, bartender, medic and mechanic",
                 "walk and run from the movement", "model's default", "`tools.fakehost --figures`", "`tools.mpcheck figures`", "descopes"):
        assert fact in text, f"Re-test: Wave B, Phase 5 does not say {fact!r}"


def test_re_test_phase_5_names_the_runtime_path_lines_the_dll_prints():
    table = flat(section_of(TESTING, "### P5-6. Which way is it working? (the runtime paths)"))
    world = (ROOT / "cp" / "plugin" / "src" / "natives.cpp").read_text(encoding="utf-8")
    for needle in ("Cyberblock_SubmitNpcs2: arrays differ in length", "npcs: %d people"):
        assert needle in world, needle  # the lines the table talks about exist in the DLL
    assert "npcs: tags %s" not in world  # the tags path line has one source, the scripts (Npcs.reds)
    for fact in ("`npcs: tags full`", "`npcs: tags faction only`", "both gender and archetype", "`npcs: colour format hsb (unconfirmed)`", "unknown",
                 "paste the F1 line", "one-line change"):
        assert fact in table, fact


def test_the_tags_path_rule_is_gender_and_archetype_everywhere():
    protocol = flat((ROOT / "shared" / "protocol.md").read_text(encoding="utf-8"))
    assert "carries both a gender and an archetype" in protocol and "gender or an archetype" not in protocol
    assert "gender or archetype" not in flat(TESTING) and "both gender and archetype" in flat(section_of(GUIDE, FIGURES))


def test_the_changelog_has_a_phase_5_entry_above_the_join_password_one():
    assert CHANGELOG.index("### Unreleased additions: Phase 5") < CHANGELOG.index("### Unreleased additions: join password")
    text = flat(section_of(CHANGELOG, "### Unreleased additions: Phase 5 (multiplayer plan, packages 5D and 5P)"))
    for phrase in ("`Cyberblock_SubmitNpcs2`", "`role`", "`fac`", "`arch`", "`anim`", "`vc`", "`col`", "`hp`", "walk and run", "hue, saturation and brightness",
                   "`npcs: colour format hsb (unconfirmed)`", "`npcs: tags full|faction only`", "`colour`", "`tools.cbmath.colour_hex`", "`P5_VALIDATORS`",
                   "`tools.fakehost --figures`", "`tools.mpcheck figures`", "`/cyberblock body male|female`", "Re-test: Wave B, Phase 5"):
        assert phrase in text, f"CHANGELOG does not say {phrase!r}"


def test_the_phase_5_docs_hold_no_private_values():
    for name, text in (("guide figures", section_of(GUIDE, FIGURES)), ("guide skin", section_of(GUIDE, SKIN)), ("Re-test", section_of(TESTING, RETEST)),
                       ("changelog", section_of(CHANGELOG, "### Unreleased additions: Phase 5 (multiplayer plan, packages 5D and 5P)"))):
        assert not re.search(r"(?<![A-Za-z0-9])[A-Za-z]:[\\/]", text), f"{name}: a drive-letter path"
        assert not re.search(r"APEX_FTP_PASS\s*=|ftp://|\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|:\d{4,5}\b", text), f"{name}: a secret, an address or a port"
