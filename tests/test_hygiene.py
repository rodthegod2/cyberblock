"""Public-repo hygiene (megaplan rule 0.5, task Q-P2): the repo is public, so no tracked file may carry the user's Windows
account name, an Apex FTP password, the hosting provider's FTP node host names, an `ftp://` URL with credentials or, in the
files the multiplayer plan creates, a drive-letter path (use `%USERPROFILE%`, `<repo>` and the other placeholders instead).

The private values (account name, FTP host and user name) are read from the machine at run time and never written here; a
failure names the file, the line and the rule, never the matched text. Each later phase's P package adds the files it
creates to `DRIVE_LETTER_FILES`."""
import fnmatch
import getpass
import os
import re
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent

# Files (git paths, fnmatch globs) created by the multiplayer plan, checked for drive-letter paths. Older plans, older docs and
# the code from before the plan are exempt. Phase Q and Phase 0 are listed; each later P package appends its own files.
DRIVE_LETTER_FILES = (
    "tests/test_hygiene.py",
    "docs/notes/ui-coverage.md", "docs/notes/phase0-*.md",                                    # Q-U1, 0R, 0M
    "mc/src/main/java/dev/cyberblock/Outline*.java", "mc/src/main/java/dev/cyberblock/SafeSpots.java",  # Q-B4, Q-B6
    "mc/src/client/java/dev/cyberblock/client/settings/*", "mc/src/client/java/dev/cyberblock/client/view/*",  # Q-B5, Q-V
    "mc/src/test/java/dev/cyberblock/Outline*Test.java", "mc/src/test/java/dev/cyberblock/SafeSpotsTest.java",
    "cp/scripts/Cyberblock/Locomotion.reds",                                                  # Q-S2
    "docs/WEBMAP.md", "docs/notes/phase3-*.md", "tests/test_phase3_docs.py",                  # 3-P1
    "docs/SERVER.md", "docs/JOIN.md", "docs/CONTENT.md", "tests/test_phase9_docs.py",         # 9-P1
)

GENERIC_NAMES = {"runner", "runneradmin", "user", "admin", "administrator", "root", "ubuntu", "default", "vsts", "system", "public"}
DRIVE_LETTER = re.compile(r"(?<![A-Za-z0-9])[A-Za-z]:[\\/]")  # one letter, a colon and a slash (not the s: of an https URL)
# APEX_FTP_PASS, an equals sign and a quoted value with at least one letter or digit ('...' and '' are placeholders)
PASSWORD_ASSIGNMENT = re.compile(r"APEX_FTP_PASS['\"]?\s*=\s*(['\"])[^'\"\n]*[A-Za-z0-9][^'\"\n]*\1")
PROVIDER_FTP_HOST = re.compile(r"(?<![A-Za-z0-9.-])[A-Za-z0-9-]*ftp[A-Za-z0-9.-]*\.(?:apexmc\.co|apexminecrafthosting\.com|apexhosting\.com)\b", re.I)
FTP_URL_WITH_CREDENTIALS = re.compile(r"ftps?://[^/\s:@'\"<>]+:[^/\s@'\"<>]+@", re.I)


def tracked_files() -> list[str]:
    try:
        out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError):
        pytest.skip("not a git checkout")
    return [p for p in out.decode("utf-8").split("\0") if p and (ROOT / p).is_file()]


def read_text(rel: str) -> str | None:
    """The file's text, None for a binary file."""
    data = (ROOT / rel).read_bytes()
    return None if b"\0" in data[:8192] else data.decode("utf-8", errors="replace")


def private_names() -> list[str]:
    """The user's Windows account name (and the home folder's), taken from this machine now; generic names are skipped."""
    names = {getpass.getuser(), os.environ.get("USERNAME", ""), Path.home().name}
    return sorted(n for n in names if len(n) >= 4 and n.lower() not in GENERIC_NAMES)


def private_hosts() -> list[str]:
    """The FTP host and user name of the user's server, only when this machine's environment has them."""
    return [v for v in (os.environ.get("APEX_FTP_HOST", ""), os.environ.get("APEX_FTP_USER", "")) if len(v) >= 4]


def word_pattern(values: list[str]) -> re.Pattern | None:
    return re.compile(r"(?<![A-Za-z0-9])(?:" + "|".join(re.escape(v) for v in values) + r")(?![A-Za-z0-9])", re.I) if values else None


def scan(rel: str, text: str, names: re.Pattern | None, hosts: re.Pattern | None, drive_letters: bool) -> list[str]:
    """`<path>:<line>: <rule>` for every violation; never the matched text."""
    rules = [("the user's Windows account name", names), ("the user's FTP host or user name", hosts),
             ("an APEX_FTP_PASS assignment with a value", PASSWORD_ASSIGNMENT), ("a hosting provider FTP node host name", PROVIDER_FTP_HOST),
             ("an ftp:// URL with credentials", FTP_URL_WITH_CREDENTIALS), ("a drive-letter path", DRIVE_LETTER if drive_letters else None)]
    return [f"{rel}:{n}: {why}" for n, line in enumerate(text.splitlines(), 1) for why, pattern in rules if pattern and pattern.search(line)]


def test_no_tracked_file_leaks_a_private_value_or_a_secret():
    names, hosts = word_pattern(private_names()), word_pattern(private_hosts())
    found = []
    for rel in tracked_files():
        if (text := read_text(rel)) is not None:
            found += scan(rel, text, names, hosts, drive_letters=False)
    assert not found, "public-repo hygiene (megaplan rule 0.5):\n" + "\n".join(found)


def test_files_the_plan_creates_have_no_drive_letter_paths():
    found = []
    for rel in tracked_files():
        if any(fnmatch.fnmatchcase(rel, g) for g in DRIVE_LETTER_FILES) and (text := read_text(rel)) is not None:
            found += scan(rel, text, None, None, drive_letters=True)
    assert not found, "use %USERPROFILE%, <repo> and the other placeholders:\n" + "\n".join(found)


def test_the_hygiene_test_scans_itself():
    assert "tests/test_hygiene.py" in DRIVE_LETTER_FILES and "tests/test_hygiene.py" in tracked_files()


# ---- the scanners themselves (the sample values are assembled here so that this file does not trip them) ----
PASS_VAR = "APEX_FTP_" + "PASS"


@pytest.mark.parametrize("line", [
    PASS_VAR + " = 'hunter2'", '$env:' + PASS_VAR + ' = "hunter2"', PASS_VAR + "='x'", PASS_VAR + '="a b3"', "set " + PASS_VAR + "='p@ss'",
])
def test_a_password_assignment_with_a_value_is_found(line):
    assert PASSWORD_ASSIGNMENT.search(line)


@pytest.mark.parametrize("line", [
    "**Never use `$env:" + PASS_VAR + " = '...'`**", PASS_VAR + " = ''", "if ($env:" + PASS_VAR + ") {'set'} else {'not set'}",
    "Tools read " + PASS_VAR + " from the environment", PASS_VAR + " = os.environ[...]", "APEX_FTP_HOST = 'placeholder'",
])
def test_placeholders_and_mentions_of_the_password_variable_are_fine(line):
    assert not PASSWORD_ASSIGNMENT.search(line)


@pytest.mark.parametrize("host", ["ftp-12." + "apexmc.co", "ftpnode3.us." + "apexminecrafthosting.com", "FTP." + "APEXHOSTING.com"])
def test_provider_ftp_node_host_names_are_found(host):
    assert PROVIDER_FTP_HOST.search(f"connect to {host} on port 21")


@pytest.mark.parametrize("text", ["https://" + "apexminecrafthosting.com/guides/minecraft/control-panel/how-to-connect-via-ftp/", "ftp.example.org", "an FTP client"])
def test_the_providers_public_guides_and_other_hosts_are_fine(text):
    assert not PROVIDER_FTP_HOST.search(text)


@pytest.mark.parametrize("url, bad", [("ftp://" + "user:secret@host.example/x", True), ("ftps://" + "u:p@h", True), ("ftp://" + "host.example/file", False),
                                      ("ftp://" + "<user>:<pass>@host", False), ("https://" + "user:pw@host", False)])
def test_ftp_urls_with_credentials_are_found(url, bad):
    assert bool(FTP_URL_WITH_CREDENTIALS.search(url)) == bad


@pytest.mark.parametrize("line, bad", [("C:" + "\\Users\\x", True), ("see d:" + "/games", True), ("https://example.org", False), ("Local\\CyberblockFrame", False),
                                      ("%USERPROFILE%\\x", False), ("a dict {k: /v/}", False), ("ratio 16:9/1", False)])
def test_drive_letter_paths_are_found(line, bad):
    assert bool(DRIVE_LETTER.search(line)) == bad


def test_the_account_name_is_found_by_word_and_generic_names_are_skipped(monkeypatch):
    monkeypatch.setattr(getpass, "getuser", lambda: "sampleuser9")
    monkeypatch.setenv("USERNAME", "Runner")
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: Path("/home/administrator")))
    assert private_names() == ["sampleuser9"]
    pattern = word_pattern(private_names())
    assert scan("a.md", "x\nsee SampleUser9 here\ny", pattern, None, False) == ["a.md:2: the user's Windows account name"]
    assert scan("a.md", "sampleuser99 and xsampleuser9", pattern, None, False) == []  # whole words only


def test_the_ftp_host_and_user_name_come_from_the_environment_and_are_not_printed(monkeypatch):
    monkeypatch.setenv("APEX_FTP_HOST", "node7.example.invalid")
    monkeypatch.setenv("APEX_FTP_USER", "ab")  # too short to be meaningful
    assert private_hosts() == ["node7.example.invalid"]
    found = scan("a.md", "host node7.example.invalid", None, word_pattern(private_hosts()), False)
    assert found == ["a.md:1: the user's FTP host or user name"] and "node7" not in found[0]
    monkeypatch.delenv("APEX_FTP_HOST")
    assert private_hosts() == [] and word_pattern([]) is None


def test_a_finding_names_the_rule_and_never_the_secret():
    value = "hunter2" + "-sample"
    (found,) = scan("x.ps1", "ok\n$env:" + PASS_VAR + " = '" + value + "'", None, None, False)
    assert found == "x.ps1:2: an APEX_FTP_PASS assignment with a value" and value not in found
