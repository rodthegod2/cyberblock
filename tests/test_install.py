import hashlib
import json
import zipfile

import pytest

from tools import install

MANIFEST = "red4ext/plugins/Cyberblock/install-manifest.json"
EXPECTED = [
    "red4ext/plugins/Cyberblock/Cyberblock.dll",
    "r6/scripts/Cyberblock/Cyberblock.reds",
    "r6/scripts/Cyberblock/Natives.reds",
    "bin/x64/reshade-shaders/Shaders/Cyberblock.fx",
    "bin/x64/reshade-shaders/Shaders/ReShade.fxh",
    "bin/x64/ReShadePreset.ini",
]


class World:
    """A fake game folder, build output, repository, saves and backups folder, all under tmp_path."""

    def __init__(self, root):
        self.game, self.build, self.source = root / "game", root / "build", root / "repo"
        self.saves, self.backups = root / "saves", root / "backups"
        (self.game / "bin/x64").mkdir(parents=True)
        (self.game / "bin/x64/Cyberpunk2077.exe").write_bytes(b"MZ")
        (self.game / "r6/scripts/OtherMod").mkdir(parents=True)  # exists before us and must survive
        (self.game / "r6/scripts/OtherMod/other.reds").write_text("other")
        self.build.mkdir()
        (self.build / "Cyberblock.dll").write_bytes(b"dll v1")
        (self.source / "cp/scripts/Cyberblock").mkdir(parents=True)
        (self.source / "cp/scripts/Cyberblock/Cyberblock.reds").write_text("state machine")
        (self.source / "cp/scripts/Cyberblock/Natives.reds").write_text("natives")
        (self.source / "cp/shaders").mkdir(parents=True)
        (self.source / "cp/shaders/Cyberblock.fx").write_text("shader")
        (self.source / "cp/shaders/vendor").mkdir()
        (self.source / "cp/shaders/vendor/ReShade.fxh").write_text("reshade header")
        (self.saves / "manualSave-1").mkdir(parents=True)
        (self.saves / "manualSave-1/sav.dat").write_bytes(b"save data")

    def args(self, *extra):
        return ["--game", str(self.game), "--build", str(self.build), "--source", str(self.source),
                "--saves", str(self.saves), "--backups", str(self.backups), *extra]

    def run(self, *extra):
        return install.main(self.args(*extra))

    def manifest(self):
        return json.loads((self.game / MANIFEST).read_text())

    def zips(self):
        return sorted(self.backups.glob("saves-*.zip")) if self.backups.exists() else []


@pytest.fixture
def world(tmp_path):
    return World(tmp_path)


def sha(text: bytes) -> str:
    return hashlib.sha256(text).hexdigest()


def test_fresh_install_writes_files_and_manifest(world):
    assert world.run() == 0
    assert (world.game / "red4ext/plugins/Cyberblock/Cyberblock.dll").read_bytes() == b"dll v1"
    assert (world.game / "r6/scripts/Cyberblock/Natives.reds").read_text() == "natives"
    assert (world.game / "bin/x64/reshade-shaders/Shaders/Cyberblock.fx").read_text() == "shader"
    assert (world.game / "bin/x64/reshade-shaders/Shaders/ReShade.fxh").read_text() == "reshade header"
    assert (world.game / "bin/x64/ReShadePreset.ini").read_text().strip() == "Techniques=Cyberblock@Cyberblock.fx"
    m = world.manifest()
    assert sorted(m["files"]) == sorted(EXPECTED)
    assert all(m["files"][rel] == sha((world.game / rel).read_bytes()) for rel in EXPECTED)
    assert (world.game / "r6/scripts/OtherMod/other.reds").read_text() == "other"


def test_reinstall_is_idempotent_and_updates_changed_files(world):
    world.run()
    first = world.manifest()
    assert world.run() == 0
    assert world.manifest() == first
    (world.build / "Cyberblock.dll").write_bytes(b"dll v2")
    assert world.run() == 0
    assert (world.game / EXPECTED[0]).read_bytes() == b"dll v2"
    assert world.manifest()["files"][EXPECTED[0]] == sha(b"dll v2")


def test_reinstall_removes_scripts_that_are_no_longer_shipped(world):
    world.run()
    (world.source / "cp/scripts/Cyberblock/Natives.reds").unlink()
    assert world.run() == 0
    assert not (world.game / "r6/scripts/Cyberblock/Natives.reds").exists()
    assert "r6/scripts/Cyberblock/Natives.reds" not in world.manifest()["files"]


def test_foreign_file_is_refused_with_exit_2_and_nothing_changes(world, capsys):
    foreign = world.game / "r6/scripts/Cyberblock/Natives.reds"
    foreign.parent.mkdir(parents=True)
    foreign.write_text("someone else's file")
    assert world.run() == 2
    assert "Natives.reds" in capsys.readouterr().err
    assert foreign.read_text() == "someone else's file"
    assert not (world.game / "red4ext").exists() and not (world.game / MANIFEST).exists()
    assert world.zips() == []  # nothing was installed, so nothing was backed up


def test_existing_reshade_preset_is_never_touched_or_recorded(world):
    preset = world.game / "bin/x64/ReShadePreset.ini"
    preset.write_text("Techniques=SomethingElse@Other.fx\n")
    assert world.run() == 0
    assert preset.read_text() == "Techniques=SomethingElse@Other.fx\n"
    assert "bin/x64/ReShadePreset.ini" not in world.manifest()["files"]


def test_user_changes_to_our_preset_survive_reinstall(world):
    world.run()
    preset = world.game / "bin/x64/ReShadePreset.ini"
    preset.write_text("Techniques=Cyberblock@Cyberblock.fx,CyberblockDepthDebug@Cyberblock.fx\n")
    assert world.run() == 0
    assert "CyberblockDepthDebug" in preset.read_text()
    assert world.run("--remove") == 0
    assert preset.exists()  # changed since install, so it is left


def test_remove_deletes_only_unchanged_manifest_files(world, capsys):
    world.run()
    edited = world.game / "r6/scripts/Cyberblock/Cyberblock.reds"
    edited.write_text("edited by the user")
    (world.game / "r6/scripts/Cyberblock/mine.reds").write_text("not ours")
    capsys.readouterr()
    assert world.run("--remove") == 0
    assert "Cyberblock.reds" in capsys.readouterr().out  # reported as left
    assert edited.read_text() == "edited by the user"
    assert (world.game / "r6/scripts/Cyberblock/mine.reds").read_text() == "not ours"
    assert not (world.game / MANIFEST).exists()
    for rel in EXPECTED:
        if rel != "r6/scripts/Cyberblock/Cyberblock.reds":
            assert not (world.game / rel).exists(), rel
    assert (world.game / "r6/scripts/OtherMod/other.reds").read_text() == "other"


def test_remove_after_a_clean_install_leaves_the_game_folder_as_it_was(world):
    def snapshot():
        return sorted(p.relative_to(world.game).as_posix() for p in world.game.rglob("*"))

    before = snapshot()
    world.run()
    assert world.run("--remove") == 0
    assert snapshot() == before  # files and the directories we created are gone, nothing else moved


def test_remove_without_a_manifest_does_nothing(world, capsys):
    assert world.run("--remove") == 0
    assert "nothing to remove" in capsys.readouterr().out.lower()
    assert (world.game / "r6/scripts/OtherMod/other.reds").exists()


def test_manifest_paths_cannot_escape_the_game_folder(world, capsys):
    world.run()
    outside = world.game.parent / "precious.txt"
    outside.write_text("keep me")
    m = world.manifest()
    m["files"]["../precious.txt"] = sha(b"keep me")
    (world.game / MANIFEST).write_text(json.dumps(m))
    assert world.run("--remove") == 1
    assert outside.read_text() == "keep me"
    assert "outside" in capsys.readouterr().err


def test_saves_are_backed_up_once_before_the_first_install(world):
    assert world.zips() == []
    world.run()
    (zip_path,) = world.zips()
    with zipfile.ZipFile(zip_path) as z:
        assert z.read("manualSave-1/sav.dat") == b"save data"
    world.run()
    assert world.zips() == [zip_path]


def test_missing_saves_folder_skips_the_backup(world):
    (world.saves / "manualSave-1/sav.dat").unlink()
    (world.saves / "manualSave-1").rmdir()
    world.saves.rmdir()
    assert world.run() == 0
    assert world.zips() == []


def test_missing_dll_is_an_error_and_nothing_is_written(world, capsys):
    (world.build / "Cyberblock.dll").unlink()
    assert world.run() == 1
    assert "Cyberblock.dll" in capsys.readouterr().err
    assert not (world.game / "red4ext").exists() and world.zips() == []


def test_not_a_cyberpunk_folder_is_an_error(world, capsys):
    (world.game / "bin/x64/Cyberpunk2077.exe").unlink()
    assert world.run() == 1
    assert "Cyberpunk2077.exe" in capsys.readouterr().err


def test_find_game_reads_steam_libraries(tmp_path):
    steam, library = tmp_path / "Steam", tmp_path / "Library"
    (steam / "steamapps").mkdir(parents=True)
    exe = library / "steamapps/common/Cyberpunk 2077/bin/x64/Cyberpunk2077.exe"
    exe.parent.mkdir(parents=True)
    exe.write_bytes(b"MZ")
    escaped = str(library).replace("\\", "\\\\")
    (steam / "steamapps/libraryfolders.vdf").write_text(
        f'"libraryfolders"\n{{\n\t"0"\n\t{{\n\t\t"path"\t\t"{str(steam).replace(chr(92), chr(92) * 2)}"\n\t}}\n'
        f'\t"1"\n\t{{\n\t\t"path"\t\t"{escaped}"\n\t}}\n}}\n')
    assert install.find_game(steam) == library / "steamapps/common/Cyberpunk 2077"
    with pytest.raises(install.InstallError):
        install.find_game(tmp_path / "NoSteam")


# ---- release 1.0.0 (plan 2026-10-03-retest3-polish section 1.13): an existing ReShade.fxh is accepted and skipped ----
HEADER = "bin/x64/reshade-shaders/Shaders/ReShade.fxh"


def test_existing_reshade_header_is_accepted_skipped_and_never_recorded(world, capsys):
    header = world.game / HEADER
    header.parent.mkdir(parents=True)
    header.write_text("the user's own ReShade.fxh")
    assert world.run() == 0
    assert header.read_text() == "the user's own ReShade.fxh"  # not overwritten
    m = world.manifest()
    assert HEADER not in m["files"] and sorted(m["files"]) == sorted(e for e in EXPECTED if e != HEADER)
    assert "ReShade.fxh" in capsys.readouterr().out  # said so
    assert (world.game / "bin/x64/reshade-shaders/Shaders/Cyberblock.fx").read_text() == "shader"  # the rest was installed
    assert world.run("--remove") == 0
    assert header.read_text() == "the user's own ReShade.fxh"  # and removal leaves it too
    assert not (world.game / "red4ext/plugins/Cyberblock/Cyberblock.dll").exists()


def test_a_reshade_header_we_installed_is_updated_like_any_owned_file(world):
    world.run()
    (world.source / "cp/shaders/vendor/ReShade.fxh").write_text("reshade header v2")
    assert world.run() == 0
    assert (world.game / HEADER).read_text() == "reshade header v2" and world.manifest()["files"][HEADER] == sha(b"reshade header v2")


def test_other_foreign_files_are_still_refused_when_the_reshade_header_exists(world, capsys):
    header = world.game / HEADER
    header.parent.mkdir(parents=True)
    header.write_text("user header")
    foreign = world.game / "r6/scripts/Cyberblock/Natives.reds"
    foreign.parent.mkdir(parents=True)
    foreign.write_text("someone else's file")
    assert world.run() == 2
    err = capsys.readouterr().err
    assert "Natives.reds" in err and "ReShade.fxh" not in err
    assert not (world.game / MANIFEST).exists() and header.read_text() == "user header"


def test_a_second_backup_in_the_same_second_never_overwrites_the_first(world, monkeypatch):
    class FrozenNow(install.datetime.datetime):
        @classmethod
        def now(cls, tz=None):
            return cls(2026, 10, 3, 12, 0, 0, tzinfo=tz)

    monkeypatch.setattr(install.datetime, "datetime", FrozenNow)
    world.run()
    world.run("--remove")
    world.run()
    names = [p.name for p in world.zips()]
    assert names == ["saves-20261003T120000Z-2.zip", "saves-20261003T120000Z.zip"]
