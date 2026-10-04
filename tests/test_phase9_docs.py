"""Phase 9 docs (megaplan task 9-P1): docs/SERVER.md, docs/JOIN.md and docs/CONTENT.md, the player guide's Phase 9 sections, the test plan's "Full multiplayer pass" and
"Re-test 9", the README's links and the release notes say what the plan says, every link resolves, the content recipes are valid content, and nothing private is in them."""
import json
import re
import subprocess
import sys
from pathlib import Path

import pytest

from test_protocol_md import GUIDE, README, anchors, section_of

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
SERVER = (DOCS / "SERVER.md").read_text(encoding="utf-8")
JOIN = (DOCS / "JOIN.md").read_text(encoding="utf-8")
CONTENT = (DOCS / "CONTENT.md").read_text(encoding="utf-8")
TESTING = (DOCS / "TESTING-PLAN.md").read_text(encoding="utf-8")
CHANGELOG = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
FULL = "## Full multiplayer pass (one session: do this first, then Re-test 9 inside it)"
RETEST = "## Re-test 9 (Phase 9: the finish: content, settings, performance, backups)"
ENTRY = "### Unreleased additions: Phase 9 (multiplayer plan, package 9P)"
NOTES = "### Release notes: 2.0.0, the multiplayer version"
NEW_GUIDES = {"SERVER.md": SERVER, "JOIN.md": JOIN, "CONTENT.md": CONTENT}


def plain(text: str) -> str:
    """Lower case, one line, no bold marks: the emphasis of a document does not matter to what it says."""
    return " ".join(text.split()).replace("**", "").lower()


def has(text: str, *phrases: str) -> None:
    body = plain(text)
    for phrase in phrases:
        assert plain(phrase) in body, f"missing {phrase!r}"


# ---- links ------------------------------------------------------------------------------------------------------------------------


@pytest.mark.parametrize("name", sorted(NEW_GUIDES))
def test_every_link_of_a_new_guide_resolves(name):
    text = NEW_GUIDES[name]
    for target in re.findall(r"\]\(([^)\s]+)\)", text):
        if target.startswith(("http://", "https://")):
            continue
        path, _, fragment = target.partition("#")
        doc = (DOCS / path) if path else (DOCS / name)
        assert doc.is_file(), f"{name} links to a missing file {target}"
        if fragment:
            assert fragment in anchors(doc.read_text(encoding="utf-8")), f"{name} links to a missing heading {target}"


def test_the_readme_links_the_three_guides_and_the_release_notes():
    for target in ("docs/SERVER.md", "docs/JOIN.md", "docs/CONTENT.md", "docs/HOW-TO-PLAY.md#performance-mode", "docs/HOW-TO-PLAY.md#the-cyberblock-settings-screen"):
        assert f"]({target})" in README, target
    for target in re.findall(r"\]\((docs/[^)#\s]+)(?:#([^)\s]+))?\)", README):
        path, fragment = target
        assert (ROOT / path).is_file(), f"README links to a missing file {path}"
        if fragment and path.endswith(".md"):
            assert fragment in anchors((ROOT / path).read_text(encoding="utf-8")), f"README links to a missing heading {path}#{fragment}"
    has(README, "version 2.0.0", "Play together", "Full multiplayer pass", "Re-test 9", "CHANGELOG.md")
    assert "Online servers are the next goal" not in README


def test_the_guide_and_the_test_plan_phase_9_anchors_resolve():
    for doc, name in ((GUIDE, "HOW-TO-PLAY"), (TESTING, "TESTING-PLAN")):
        found = anchors(doc)
        for target in re.findall(r"\]\(#([^)]+)\)", doc):
            assert target in found, f"{name}.md links to a missing heading #{target}"
    for target in ("SERVER.md", "JOIN.md", "CONTENT.md"):
        assert f"]({target})" in GUIDE, target
    guide_links = re.findall(r"\]\(([A-Z-]+\.md)(?:#([^)]+))?\)", GUIDE + TESTING)
    for path, fragment in guide_links:
        assert (DOCS / path).is_file(), path
        if fragment:
            assert fragment in anchors((DOCS / path).read_text(encoding="utf-8")), f"{path}#{fragment}"


# ---- docs/CONTENT.md ----------------------------------------------------------------------------------------------------------------


def recipes() -> list[tuple[str, str]]:
    """(path inside the pack, file content) of every recipe: a backticked `data/...json` line followed by a json block."""
    return re.findall(r"^`(data/[^`]+\.json)`\n```json\n(.*?)\n```", CONTENT, re.S | re.M)


def test_the_content_guide_has_one_recipe_for_each_of_the_six_things_a_friend_adds():
    kinds = sorted(path.split("/")[3] for path, _ in recipes())
    assert kinds == ["daemon", "dialogue", "event", "landmark", "shop", "skin_map"]
    has(CONTENT, "dialogue line", "A skin", "A shop", "A landmark", "A Breach daemon", "An Overlord event", "same id as a default replaces it", "`/reload`", "cyberblock:content",
        "tools.content_check --dir", "content: <kind> <id> skipped: <why>", "data/<namespace>/cyberblock/<kind>/<name>.json", "datapacks", "pack.mcmeta", "Original text and art only",
        "resource pack", "`approx`", "closed list", "`warning_s` can never be below 3")


def test_every_content_kind_is_in_the_guides_table():
    kinds = sorted(p.name.removesuffix(".schema.json") for p in (ROOT / "shared" / "content-schemas").glob("*.schema.json") if p.name != "test.schema.json")
    table = section_of(CONTENT, "## The kinds")
    for kind in kinds:
        assert f"| `{kind}` |" in table, f"CONTENT.md does not list the kind {kind}"


def test_the_content_recipes_are_valid_content(tmp_path):
    found = recipes()
    assert len(found) == 6
    for rel, body in found:
        target = tmp_path / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body + "\n", encoding="utf-8")
        assert json.loads(body)["schema"] == 1, rel
    done = subprocess.run([sys.executable, "-m", "tools.content_check", "--dir", str(tmp_path)], cwd=ROOT, capture_output=True, text=True)
    assert done.returncode == 0, done.stdout + done.stderr
    assert "6 file(s) ok" in done.stdout


# ---- docs/SERVER.md -----------------------------------------------------------------------------------------------------------------


def test_the_server_guide_documents_every_key_of_the_config_example():
    example = json.loads((ROOT / "server" / "server.json.example").read_text(encoding="utf-8"))
    table = section_of(SERVER, "## 4. The configuration file")
    for key, value in example.items():
        if key == "schema":
            continue
        for sub in ([f"backups.{k}" for k in value] if isinstance(value, dict) else [key]):
            assert f"| `{sub}` |" in table, f"SERVER.md does not document `{sub}`"


def test_the_server_guide_says_what_the_plan_promises():
    has(SERVER, "Fabric 26.3", "Java 25", "void world", "`cyberblock:nightcity`", "guest", "host", "tools.server_sync backup", "tools.server_sync verify", "tools.server_bundle",
        "tools.server_sync upload", "--go", "never delete anything on the server", "Custom Jar", "cyberblock server: ready", "worldcheck", "whitelist", "join password", "`/login <password>`",
        "Five wrong tries", "120 seconds", "`hosts`", "`maxHosts`", "An older file keeps its old values", "`/cyberblock status`", "`/cyberblock backup now`", "`backups.hour`",
        "`backups.keep`", "twice the world size", "backup: <file> <size>", "tools.server_sync pull-backups --to", "`/reload`", "tools.modpack", "tools.respack", "the way back",
        "server.properties.rodsores", "Never put the server address", "major and minor", "Performance mode", "[CONTENT.md](CONTENT.md)", "[JOIN.md](JOIN.md)", "[WEBMAP.md](WEBMAP.md)")
    for command in ("hosts add|remove", "password reset", "worldcheck", "outline erase", "guests <toggle>"):
        assert f"`/cyberblock {command}" in SERVER, command


def test_the_join_guide_says_what_the_plan_promises():
    has(JOIN, "Minecraft: Java Edition", "26.3", "Modrinth App", "Prism Launcher", "`.mrpack`", "Join Cyberblock", "two clicks", "resource pack", "`/login <password>`", "once",
        "guest", "Minecraft only", "the city appears as the hosts explore it", "blocked", "`/cyberblock meetup <player>`", "`/cyberblock hub`", "Hacker in the Van", "Overlord",
        "proximity", "Performance mode", "Cyberblock version mismatch", "This server needs a password", "[CONTENT.md](CONTENT.md)", "[SERVER.md](SERVER.md)")


# ---- the player guide ---------------------------------------------------------------------------------------------------------------


def test_the_guide_has_the_settings_tabs_performance_mode_and_the_short_creative_note():
    settings = section_of(GUIDE, "#### The Cyberblock settings screen")
    for tab in ("Guests", "Look", "PvP", "Difficulty", "Performance", "World"):
        assert f"| **{tab}** |" in settings, tab
    has(settings, "`/cyberblock settings`", "the server decides", "Only the host or an operator can change this", "skin mode", "Overlord intensity", "Server Heat",
        "Creative can erase the replica permanently", "`explosions`", "`damage`", "`hacks`", "`spawns`", "`devices`")
    perf = section_of(GUIDE, "#### Performance mode")
    has(perf, "`prefs.scale`", "50 to 100 percent", "scales it back up", "Figure cap", "16 to 48", "stop animating", "Server budgets", "off by default", "Numbers", "R9-5", "Stutter, swimming or lag")
    creative = section_of(GUIDE, "### Creative mode and the outline blocks")
    assert len(creative.splitlines()) <= 16 and "Creative can erase the replica permanently" in creative and "**World** tab" in creative
    has(section_of(GUIDE, "### Playing together (LAN)"), "[SERVER.md](SERVER.md)", "[JOIN.md](JOIN.md)", "`backups.hour`", "`/cyberblock backup now`", "backup: <file> <size>", "pull-backups",
        "Adding your own content (datapacks)", "[CONTENT.md](CONTENT.md)")
    has(section_of(GUIDE, "#### Menus Cyberblock cannot replace"), "Save and load lists", "Night City saves", "docs/notes/ui-coverage.md", "R9-6", "escape hatch", "works at all times")
    commands = section_of(GUIDE, "### Commands")
    for command in ("`/cyberblock login <password>`", "`/cyberblock backup now`", "`/reload`", "`/cyberblock settings`"):
        assert command in commands, command
    assert "2.0.0" in section_of(GUIDE, "### Release install (players)") and "1.0.0" not in GUIDE


def test_the_guide_says_the_multiplayer_version_in_its_banner():
    banner = GUIDE.split("---", 1)[0]
    has(banner, "version 2.0.0, proposed", "playing together", "SERVER.md", "JOIN.md", "CONTENT.md", "Full multiplayer pass", "Re-test 9", "Performance mode", "settings screen")


# ---- the test plan ------------------------------------------------------------------------------------------------------------------


def test_the_test_plan_starts_with_the_full_pass_then_re_test_9_then_the_older_sections():
    assert TESTING.index(FULL) < TESTING.index(RETEST) < TESTING.index("## Re-test 6 (Phase 6: hacking and the interaction map)") < TESTING.index("## Re-test: Wave C, Phase 7")
    assert TESTING.index("## Re-test: Wave C, Phase 8 (roles and modes)") < TESTING.index("## Re-test: Wave B (Phases 3, 4, 5)")
    assert "Full multiplayer pass" in TESTING.split("## ", 2)[1] or "Full multiplayer pass" in TESTING.split("\n## ", 1)[0]


def test_the_full_multiplayer_pass_has_ten_steps_and_a_one_screen_list():
    full = section_of(TESTING, FULL)
    one_screen = full.split("**What to try (one screen):**", 1)[1].split("| Phase |", 1)[0].strip().splitlines()
    assert len(one_screen) <= 15
    for n in range(1, 11):
        assert f"### M-{n}." in full, f"M-{n}"
    has(full, "Phase 0 is still not run", "the join password on", "Four people", "`/login <password>`", "Join Cyberblock", "`/cyberblock guests explosions off`", "blocked", "While you were away",
        "Reconnecting to", "`/cyberblock hub`", "`/cyberblock overlord optin on`", "`/cyberblock spectate`", "R9-5", "`backup:`", "`worldcheck:`", "`handshake:`", "`content:`", "which way",
        "Re-test: Wave B", "Re-test 6", "Re-test: Wave C, Phase 7", "Re-test: Wave C, Phase 8", "Re-test 9")


def test_re_test_9_has_the_plan_checks():
    retest = section_of(TESTING, RETEST)
    one_screen = retest.split("**What to try (one screen):**", 1)[1].split("| Item |", 1)[0].strip().splitlines()
    assert len(one_screen) <= 15
    for n in range(1, 9):
        assert f"### R9-{n}." in retest, f"R9-{n}"
    has(retest, "Phase 0 is still not run", "operator", "dialogue", "skin", "`/reload`", "content: loaded", "content: <kind> <id> skipped: <why>", "tools.content_check --dir", "examples/content-datapack",
        "Guests", "Look", "PvP", "Difficulty", "Performance", "World", "Only the host or an operator can change this", "`backups.hour`", "`/cyberblock backup now`", "backup:", "`backups.keep`",
        "twice the world size", "pull-backups", "`APEX_FTP_PASS`", "never", "Performance mode", "four guests", "75 percent", "50 percent", "figure cap", "Night City saves", "docs/notes/ui-coverage.md",
        "escape hatch", "Night City Menu...", "which way", "a SKIP is not a pass", "2.0.0", "no tag, no release, no push", "descopes")
    assert "### Phase 9 descopes and risks (short)" in retest


# ---- the release notes ---------------------------------------------------------------------------------------------------------------


def test_the_changelog_has_release_notes_and_a_phase_9_entry_at_the_top_of_2_0_0():
    entries = re.findall(r"^## (.+)$", CHANGELOG, re.M)
    assert entries[0] == "[2.0.0] - unreleased" and entries[-1] == "Planned"
    assert CHANGELOG.index(NOTES) < CHANGELOG.index(ENTRY) < CHANGELOG.index("### Unreleased additions: Phase 6 (multiplayer plan, package 6P)")
    notes = section_of(CHANGELOG, NOTES)
    has(notes, "Cyberblock is now a multiplayer game", "A server", "Night City as Minecraft", "Roles and modes", "The finish (Phase 9)", "settings screen", "Performance mode", "daily server backups",
        "pull-backups", "Upgrading", "same version on both sides", "`\"maxHosts\": 4`", "Known limits", "Phase 0 in-game checks have not been run", "docs/SERVER.md", "docs/JOIN.md", "docs/CONTENT.md")
    top = CHANGELOG.split("### Release notes", 1)[0]
    has(top, "proposed", "owner decides", "never-released 1.0.0 draft", "\"push\"")
    entry = section_of(CHANGELOG, ENTRY)
    has(entry, "docs/SERVER.md", "docs/JOIN.md", "docs/CONTENT.md", "Full multiplayer pass", "Re-test 9", "R9-1", "Version 2.0.0 (proposed)", "`uv.lock`", "`CB_VERSION`", "`mc/gradle.properties`",
        "`Natives.reds`", "tests/test_version.py", "No tag, no release and no push", "tests/test_phase9_docs.py")


# ---- hygiene -------------------------------------------------------------------------------------------------------------------------


def test_the_phase_9_docs_hold_no_private_values():
    parts = {"SERVER.md": SERVER, "JOIN.md": JOIN, "CONTENT.md": CONTENT, "full pass": section_of(TESTING, FULL), "Re-test 9": section_of(TESTING, RETEST),
             "release notes": section_of(CHANGELOG, NOTES), "changelog entry": section_of(CHANGELOG, ENTRY), "guide settings": section_of(GUIDE, "#### The Cyberblock settings screen"),
             "guide performance": section_of(GUIDE, "#### Performance mode")}
    for name, text in parts.items():
        assert not re.search(r"(?<![A-Za-z0-9])[A-Za-z]:[\\/]", text), f"{name}: a drive-letter path"
        assert not re.search(r"APEX_FTP_PASS\s*=|ftp://|\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|:\d{4,5}\b", text), f"{name}: a secret, an address or a port"
