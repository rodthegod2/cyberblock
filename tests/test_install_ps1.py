"""release/install.ps1 (Windows PowerShell 5.1, no Python) against a fake game folder, and against tools/install.py.

The two installers must be interchangeable: same payload, same manifest (same files, same sha256, same dirs), either one
removes what the other installed. Every scenario runs the real script through powershell.exe, so these tests are skipped
where there is no Windows PowerShell.
"""
import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

import pytest

from test_install import EXPECTED, HEADER, MANIFEST, World, sha
from tools import install, package

POWERSHELL = shutil.which("powershell.exe") if sys.platform == "win32" else None
PS1 = Path(__file__).resolve().parents[1] / "release" / "install.ps1"
pytestmark = pytest.mark.skipif(POWERSHELL is None, reason="needs Windows PowerShell")
NO_GAME_PROCESS = "NoSuchCyberpunkProcess"


class PsWorld(World):
    """World plus the release layout: a payload folder (the zip's game\\ folder) built from the same inputs."""

    def __init__(self, root):
        super().__init__(root)
        self.payload = root / "payload"
        self.stage()

    def stage(self):
        shutil.rmtree(self.payload, ignore_errors=True)
        for rel, src in install.payload(self.build, self.source).items():
            (self.payload / rel).parent.mkdir(parents=True, exist_ok=True)
            (self.payload / rel).write_bytes(src.read_bytes())

    def ps(self, *extra, game=True, process=NO_GAME_PROCESS, env=None):
        cmd = [POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(PS1)]
        cmd += ["-Game", str(self.game)] if game else []
        cmd += ["-Payload", str(self.payload), "-Saves", str(self.saves), "-Backups", str(self.backups), "-ProcessName", process, *extra]
        return subprocess.run(cmd, capture_output=True, text=True, timeout=180, env=env)

    def snapshot(self):
        return sorted(p.relative_to(self.game).as_posix() for p in self.game.rglob("*"))


@pytest.fixture
def pw(tmp_path):
    return PsWorld(tmp_path / "ps")


def manifest_of(world):
    return json.loads((world.game / MANIFEST).read_text(encoding="utf-8"))  # strict utf-8, so a BOM would fail here too


def test_the_script_is_plain_ascii_and_parses_without_errors():
    data = PS1.read_bytes()
    assert all(b < 0x80 for b in data), "Windows PowerShell 5.1 reads a BOM-less file as ANSI: keep the script ASCII"
    check = ("$e=$null;$t=$null;[void][System.Management.Automation.Language.Parser]::ParseFile('" + str(PS1) +
             "',[ref]$t,[ref]$e);if($e.Count){$e|ForEach-Object{$_.Message};exit 1}")
    r = subprocess.run([POWERSHELL, "-NoProfile", "-Command", check], capture_output=True, text=True, timeout=60)
    assert r.returncode == 0, r.stdout + r.stderr


def test_fresh_install_matches_install_py_file_for_file_and_manifest_for_manifest(pw, tmp_path):
    r = pw.ps()
    assert r.returncode == 0, r.stderr
    assert f"installed {len(EXPECTED) - 1} files" in r.stdout  # the ReShade preset is written too but is not counted, as in install.py
    py = PsWorld(tmp_path / "py")  # the same fake game folder, installed by install.py instead
    assert py.run() == 0
    mine, theirs = manifest_of(pw), py.manifest()
    assert mine["version"] == theirs["version"] == 1
    assert mine["files"] == theirs["files"]  # the same files with the same lower-case sha256
    assert sorted(set(mine["dirs"])) == sorted(set(theirs["dirs"]))
    assert pw.snapshot() == py.snapshot()
    assert all(mine["files"][rel] == sha((pw.game / rel).read_bytes()) for rel in EXPECTED)
    assert (pw.game / "bin/x64/ReShadePreset.ini").read_bytes() == b"Techniques=Cyberblock@Cyberblock.fx\n"
    assert (pw.game / "r6/scripts/OtherMod/other.reds").read_text() == "other"
    assert not (pw.game / MANIFEST).read_bytes().startswith(b"\xef\xbb\xbf")  # no BOM: Python could not read it


def test_reinstall_is_idempotent_and_updates_changed_files(pw):
    assert pw.ps().returncode == 0
    first = manifest_of(pw)
    assert pw.ps().returncode == 0 and manifest_of(pw) == first
    (pw.build / "Cyberblock.dll").write_bytes(b"dll v2")
    pw.stage()
    assert pw.ps().returncode == 0
    assert (pw.game / EXPECTED[0]).read_bytes() == b"dll v2" and manifest_of(pw)["files"][EXPECTED[0]] == sha(b"dll v2")


def test_files_an_older_version_installed_and_this_one_no_longer_ships_are_removed(pw):
    pw.ps()
    (pw.source / "cp/scripts/Cyberblock/Natives.reds").unlink()
    pw.stage()
    assert pw.ps().returncode == 0
    assert not (pw.game / "r6/scripts/Cyberblock/Natives.reds").exists()
    assert "r6/scripts/Cyberblock/Natives.reds" not in manifest_of(pw)["files"]


def test_stale_file_that_the_user_changed_is_left_with_a_message(pw):
    pw.ps()
    (pw.game / "r6/scripts/Cyberblock/Natives.reds").write_text("edited by the user")
    (pw.source / "cp/scripts/Cyberblock/Natives.reds").unlink()
    pw.stage()
    r = pw.ps()
    assert r.returncode == 0 and "left r6/scripts/Cyberblock/Natives.reds" in r.stdout
    assert (pw.game / "r6/scripts/Cyberblock/Natives.reds").read_text() == "edited by the user"


def test_a_foreign_file_is_refused_with_exit_2_and_nothing_changes(pw):
    foreign = pw.game / "r6/scripts/Cyberblock/Natives.reds"
    foreign.parent.mkdir(parents=True)
    foreign.write_text("someone else's file")
    before = pw.snapshot()
    r = pw.ps()
    assert r.returncode == 2 and "Natives.reds" in r.stderr and "refusing" in r.stderr
    assert foreign.read_text() == "someone else's file" and pw.snapshot() == before
    assert not pw.backups.exists()  # nothing was installed, so nothing was backed up


def test_an_existing_reshade_header_is_accepted_skipped_and_never_recorded(pw):
    header = pw.game / HEADER
    header.parent.mkdir(parents=True)
    header.write_text("the user's own ReShade.fxh")
    r = pw.ps()
    assert r.returncode == 0 and "ReShade.fxh" in r.stdout
    assert header.read_text() == "the user's own ReShade.fxh" and HEADER not in manifest_of(pw)["files"]
    assert pw.ps("-Remove").returncode == 0 and header.read_text() == "the user's own ReShade.fxh"


def test_the_reshade_header_we_installed_is_updated_like_any_owned_file(pw):
    pw.ps()
    (pw.source / "cp/shaders/vendor/ReShade.fxh").write_text("reshade header v2")
    pw.stage()
    assert pw.ps().returncode == 0 and (pw.game / HEADER).read_text() == "reshade header v2"


def test_an_existing_preset_is_never_touched_or_recorded_and_ours_survives_edits(pw):
    preset = pw.game / "bin/x64/ReShadePreset.ini"
    preset.write_text("Techniques=SomethingElse@Other.fx\n")
    assert pw.ps().returncode == 0
    assert preset.read_text() == "Techniques=SomethingElse@Other.fx\n" and "bin/x64/ReShadePreset.ini" not in manifest_of(pw)["files"]
    preset.unlink()
    pw.ps("-Remove")
    assert pw.ps().returncode == 0 and "bin/x64/ReShadePreset.ini" in manifest_of(pw)["files"]
    preset.write_text("Techniques=Cyberblock@Cyberblock.fx,CyberblockDepthDebug@Cyberblock.fx\n")
    assert pw.ps().returncode == 0 and "CyberblockDepthDebug" in preset.read_text()  # an update keeps the user's edit
    assert pw.ps("-Remove").returncode == 0 and preset.exists()  # changed since install, so it is left


def test_remove_deletes_only_unchanged_manifest_files_and_leaves_the_game_as_it_was(pw):
    before = pw.snapshot()
    pw.ps()
    assert pw.ps("-Remove").returncode == 0
    assert pw.snapshot() == before  # files and the directories the install created are gone, nothing else moved


def test_remove_leaves_changed_files_and_foreign_neighbours(pw):
    pw.ps()
    edited = pw.game / "r6/scripts/Cyberblock/Cyberblock.reds"
    edited.write_text("edited by the user")
    (pw.game / "r6/scripts/Cyberblock/mine.reds").write_text("not ours")
    r = pw.ps("-Remove")
    assert r.returncode == 0 and "Cyberblock.reds" in r.stdout and edited.read_text() == "edited by the user"
    assert (pw.game / "r6/scripts/Cyberblock/mine.reds").read_text() == "not ours"
    assert not (pw.game / MANIFEST).exists()
    for rel in EXPECTED:
        if rel != "r6/scripts/Cyberblock/Cyberblock.reds":
            assert not (pw.game / rel).exists(), rel
    assert (pw.game / "r6/scripts/OtherMod/other.reds").read_text() == "other"


def test_remove_without_a_manifest_does_nothing(pw):
    r = pw.ps("-Remove")
    assert r.returncode == 0 and "nothing to remove" in r.stdout.lower() and (pw.game / "r6/scripts/OtherMod/other.reds").exists()


def test_manifest_paths_cannot_escape_the_game_folder(pw):
    pw.ps()
    outside = pw.game.parent / "precious.txt"
    outside.write_text("keep me")
    m = manifest_of(pw)
    m["files"]["../precious.txt"] = sha(b"keep me")
    (pw.game / MANIFEST).write_text(json.dumps(m), encoding="utf-8")
    r = pw.ps("-Remove")
    assert r.returncode == 1 and "outside" in r.stderr and outside.read_text() == "keep me"


def test_an_unreadable_manifest_is_an_error_not_a_crash(pw):
    pw.ps()
    (pw.game / MANIFEST).write_text("{not json", encoding="utf-8")
    r = pw.ps()
    assert r.returncode == 1 and "unreadable" in r.stderr


def test_saves_are_backed_up_once_before_the_first_install_as_a_python_readable_zip(pw):
    assert not pw.backups.exists()
    assert pw.ps().returncode == 0
    (zip_path,) = pw.zips()
    with zipfile.ZipFile(zip_path) as z:
        assert z.read("manualSave-1/sav.dat") == b"save data"  # forward slashes, as install.py's backup
    pw.ps()
    assert pw.zips() == [zip_path]


def test_missing_saves_folder_skips_the_backup(pw):
    shutil.rmtree(pw.saves)
    r = pw.ps()
    assert r.returncode == 0 and "skipping the backup" in r.stdout and pw.zips() == []


def test_it_refuses_while_cyberpunk_runs(pw):
    r = pw.ps(process="powershell")  # this very test run has a powershell process
    assert r.returncode == 1 and "running" in r.stderr
    assert not (pw.game / "red4ext").exists() and not pw.backups.exists()


def test_not_a_cyberpunk_folder_and_a_missing_payload_are_errors_that_write_nothing(pw):
    (pw.game / "bin/x64/Cyberpunk2077.exe").unlink()
    r = pw.ps()
    assert r.returncode == 1 and "Cyberpunk2077.exe" in r.stderr
    (pw.game / "bin/x64/Cyberpunk2077.exe").write_bytes(b"MZ")
    shutil.rmtree(pw.payload)
    r = pw.ps()
    assert r.returncode == 1 and "unzip the whole release" in r.stderr
    assert not (pw.game / "red4ext").exists() and pw.zips() == []


def test_the_game_is_found_through_steams_libraries_like_install_py(pw, tmp_path):
    steam, library = tmp_path / "Steam", tmp_path / "Library"
    (steam / "steamapps").mkdir(parents=True)
    shutil.move(str(pw.game), str(library / "steamapps/common/Cyberpunk 2077"))  # the fake game now lives in a second library
    escaped = lambda p: str(p).replace("\\", "\\\\")
    (steam / "steamapps/libraryfolders.vdf").write_text(
        f'"libraryfolders"\n{{\n\t"0"\n\t{{\n\t\t"path"\t\t"{escaped(steam)}"\n\t}}\n\t"1"\n\t{{\n\t\t"path"\t\t"{escaped(library)}"\n\t}}\n}}\n')
    pw.game = library / "steamapps/common/Cyberpunk 2077"
    assert install.find_game(steam) == pw.game
    r = pw.ps("-SteamPath", str(steam), game=False)
    assert r.returncode == 0, r.stderr
    assert (pw.game / EXPECTED[0]).exists()
    r = pw.ps("-SteamPath", str(tmp_path / "NoSteam"), game=False)
    assert r.returncode == 1 and "Steam" in r.stderr


def test_missing_frameworks_are_warned_about_and_present_ones_are_not(pw):
    r = pw.ps()
    assert r.returncode == 0
    for name in ("RED4ext", "redscript", "Codeware", "ReShade"):
        assert f"could not find {name}" in r.stdout
    pw.ps("-Remove")
    for rel in ("red4ext/RED4ext.dll", "engine/tools/scc.exe", "red4ext/plugins/Codeware/Codeware.dll", "bin/x64/dxgi.dll"):
        (pw.game / rel).parent.mkdir(parents=True, exist_ok=True)
        (pw.game / rel).write_bytes(b"x")
    r = pw.ps()
    assert r.returncode == 0 and "could not find" not in r.stdout


def test_the_two_installers_remove_each_others_installs(pw, tmp_path):
    before = pw.snapshot()
    assert pw.ps().returncode == 0
    assert pw.run("--remove") == 0 and pw.snapshot() == before  # installed by powershell, removed by install.py
    assert pw.run() == 0
    assert pw.ps("-Remove").returncode == 0 and pw.snapshot() == before  # installed by install.py, removed by powershell
    assert pw.ps().returncode == 0 and pw.run() == 0  # and an install over the other's install is an update, not a refusal
    assert pw.ps().returncode == 0


def test_the_release_zip_installs_and_uninstalls_through_the_bat_files(pw, tmp_path):
    if subprocess.run(["tasklist", "/FI", "IMAGENAME eq Cyberpunk2077.exe", "/NH"], capture_output=True, text=True).stdout.count("Cyberpunk2077.exe"):
        pytest.skip("Cyberpunk is running: the .bat files do not take a process name")
    (pw.source / "docs").mkdir()
    for rel in ("docs/HOW-TO-PLAY.md",):
        (pw.source / rel).write_text("guide")
    root = Path(__file__).resolve().parents[1]
    for name in ("README.md", "CHANGELOG.md", "LICENSE", "NOTICE.md", "THIRD-PARTY-NOTICES.md", "pyproject.toml"):
        shutil.copy(root / name, pw.source / name)
    shutil.copytree(root / "release", pw.source / "release")
    jar = tmp_path / "cyberblock.jar"
    with zipfile.ZipFile(jar, "w") as z:
        z.writestr("fabric.mod.json", "{}")
    zip_path = package.build_zip(tmp_path / "dist", pw.build, jar, pw.source)
    extracted = tmp_path / "unzipped"
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(extracted)
    release = next(extracted.iterdir())
    env = {**os.environ, "USERPROFILE": str(tmp_path / "home"), "LOCALAPPDATA": str(tmp_path / "local")}  # the backup goes nowhere real
    before = pw.snapshot()
    drop = lambda name, *arg: subprocess.run(["cmd", "/c", str(release / name), *arg], capture_output=True, text=True, timeout=180, env=env,
                                             stdin=subprocess.DEVNULL, cwd=str(release))
    r = drop("install.bat", str(pw.game) + "\\")  # a dropped folder may carry a trailing backslash
    assert r.returncode == 0, r.stdout + r.stderr
    assert all((pw.game / rel).exists() for rel in EXPECTED)
    assert (pw.game / EXPECTED[0]).read_bytes() == b"dll v1"
    r = drop("uninstall.bat", str(pw.game))
    assert r.returncode == 0, r.stdout + r.stderr
    assert pw.snapshot() == before
