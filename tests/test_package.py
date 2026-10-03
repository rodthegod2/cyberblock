import hashlib
import re
import zipfile
from pathlib import Path

import pytest

from tools import install, package

REPO = Path(__file__).resolve().parents[1]
DOC_FILES = ["README.md", "HOW-TO-PLAY.md", "CHANGELOG.md", "LICENSE", "NOTICE.md", "THIRD-PARTY-NOTICES.md"]
SCRIPTS = ["install.bat", "uninstall.bat", "install.ps1"]


class Repo:
    """A fake repository root, the build output and a fake mod jar, all under tmp_path."""

    def __init__(self, root, version="9.8.7"):
        self.root, self.build, self.jar = root / "repo", root / "build", root / "cyberblock.jar"
        for rel, text in {
            "pyproject.toml": f'[project]\nname = "x"\nversion = "{version}"\n',
            "README.md": "readme, see [the guide](docs/HOW-TO-PLAY.md#1-setup) and [the plan](docs/TESTING-PLAN.md)\n",
            "docs/HOW-TO-PLAY.md": "guide\n", "CHANGELOG.md": "log\n", "LICENSE": "mit\n", "NOTICE.md": "notice\n",
            "THIRD-PARTY-NOTICES.md": "third party\n",
            "release/install.bat": "@echo off\nrem install\n", "release/uninstall.bat": "@echo off\r\nrem uninstall\r\n",
            "release/install.ps1": "param()\n",
            "cp/scripts/Cyberblock/Cyberblock.reds": "state machine", "cp/scripts/Cyberblock/Natives.reds": "natives",
            "cp/shaders/Cyberblock.fx": "shader", "cp/shaders/vendor/ReShade.fxh": "reshade header",
        }.items():
            (self.root / rel).parent.mkdir(parents=True, exist_ok=True)
            (self.root / rel).write_bytes(text.encode())
        self.build.mkdir()
        (self.build / "Cyberblock.dll").write_bytes(b"dll")
        with zipfile.ZipFile(self.jar, "w") as z:
            z.writestr("fabric.mod.json", "{}")
            z.writestr("assets/cyberblock/lang/en_us.json", "{}")

    def zip(self, out, **kwargs):
        return package.build_zip(out, self.build, self.jar, self.root, **kwargs)


@pytest.fixture
def repo(tmp_path):
    return Repo(tmp_path)


def names(path):
    with zipfile.ZipFile(path) as z:
        return z.namelist()


def test_zip_layout_is_one_top_folder_with_scripts_docs_game_and_the_jar(repo, tmp_path):
    out = repo.zip(tmp_path / "dist")
    assert out == tmp_path / "dist" / "Cyberblock-9.8.7.zip"
    top = "Cyberblock-9.8.7/"
    expected = sorted(
        [top + n for n in SCRIPTS + DOC_FILES]
        + [top + "game/" + rel for rel in install.payload(repo.build, repo.root)]
        + [top + "minecraft/mods/cyberblock-9.8.7.jar"])
    assert names(out) == expected  # exactly the payload list of the installers, plus docs, scripts and the jar
    assert top + "game/red4ext/plugins/Cyberblock/Cyberblock.dll" in expected
    assert top + "game/bin/x64/reshade-shaders/Shaders/ReShade.fxh" in expected and top + "game/r6/scripts/Cyberblock/Natives.reds" in expected


def test_game_folder_equals_what_install_py_installs(repo, tmp_path):
    out = repo.zip(tmp_path / "dist")
    with zipfile.ZipFile(out) as z:
        zipped = {n.split("/game/", 1)[1] if "/game/" in n else None: z.read(n) for n in z.namelist() if "/game/" in n}
    assert zipped == {rel: src.read_bytes() for rel, src in install.payload(repo.build, repo.root).items()}


def test_the_zip_is_reproducible_with_fixed_timestamps_and_modes(repo, tmp_path):
    first, second = repo.zip(tmp_path / "a"), repo.zip(tmp_path / "b")
    assert first.read_bytes() == second.read_bytes()
    with zipfile.ZipFile(first) as z:
        assert {i.date_time for i in z.infolist()} == {package.ZIP_TIME} and {i.external_attr >> 16 for i in z.infolist()} == {0o644}
        assert z.testzip() is None


def test_windows_scripts_get_crlf_and_everything_else_is_untouched(repo, tmp_path):
    out = repo.zip(tmp_path / "dist")
    with zipfile.ZipFile(out) as z:
        bat, bat2, ps1 = (z.read(f"Cyberblock-9.8.7/{n}") for n in SCRIPTS)
        assert bat == b"@echo off\r\nrem install\r\n" and bat2 == b"@echo off\r\nrem uninstall\r\n" and ps1 == b"param()\r\n"  # no doubled CR
        assert z.read("Cyberblock-9.8.7/NOTICE.md") == b"notice\n" and z.read("Cyberblock-9.8.7/minecraft/mods/cyberblock-9.8.7.jar") == repo.jar.read_bytes()


def test_readme_in_the_zip_points_at_the_guide_next_to_it(repo, tmp_path):
    with zipfile.ZipFile(repo.zip(tmp_path / "dist")) as z:
        readme = z.read("Cyberblock-9.8.7/README.md").decode()
    assert "](HOW-TO-PLAY.md#1-setup)" in readme and "docs/HOW-TO-PLAY.md" not in readme
    assert "docs/TESTING-PLAN.md" in readme  # only the guide is next to it


def test_the_version_comes_from_pyproject(tmp_path):
    other = Repo(tmp_path / "other", version="2.0.1")
    assert package.version(other.root) == "2.0.1"
    assert other.zip(tmp_path / "dist").name == "Cyberblock-2.0.1.zip"
    assert "Cyberblock-2.0.1/minecraft/mods/cyberblock-2.0.1.jar" in names(tmp_path / "dist" / "Cyberblock-2.0.1.zip")


def test_a_new_script_is_packaged_without_touching_the_packager(repo, tmp_path):
    (repo.root / "cp/scripts/Cyberblock/Hud.reds").write_text("hud")
    assert "Cyberblock-9.8.7/game/r6/scripts/Cyberblock/Hud.reds" in names(repo.zip(tmp_path / "dist"))


@pytest.mark.parametrize("missing", ["jar", "dll", "script", "doc", "release"])
def test_a_missing_input_is_an_error_and_writes_no_zip(repo, tmp_path, capsys, missing):
    {"jar": lambda: repo.jar.unlink(), "dll": lambda: (repo.build / "Cyberblock.dll").unlink(),
     "script": lambda: [p.unlink() for p in (repo.root / "cp/scripts/Cyberblock").glob("*.reds")],
     "doc": lambda: (repo.root / "NOTICE.md").unlink(), "release": lambda: (repo.root / "release/install.ps1").unlink()}[missing]()
    out = tmp_path / "dist"
    code = package.main(["--build", str(repo.build), "--jar", str(repo.jar), "--source", str(repo.root), "--out", str(out)])
    assert code == 1 and capsys.readouterr().err
    assert not out.exists() or not list(out.iterdir())


def test_main_prints_the_sha256_of_the_zip(repo, tmp_path, capsys):
    code = package.main(["--build", str(repo.build), "--jar", str(repo.jar), "--source", str(repo.root), "--out", str(tmp_path / "dist")])
    zip_path = tmp_path / "dist" / "Cyberblock-9.8.7.zip"
    assert code == 0 and f"sha256 {hashlib.sha256(zip_path.read_bytes()).hexdigest()}  Cyberblock-9.8.7.zip" in capsys.readouterr().out


def test_default_jar_is_the_gradle_output_of_the_same_version(repo, tmp_path):
    libs = repo.root / "mc/build/libs"
    libs.mkdir(parents=True)
    (libs / "cyberblock-9.8.7.jar").write_bytes(repo.jar.read_bytes())
    code = package.main(["--build", str(repo.build), "--source", str(repo.root), "--out", str(tmp_path / "dist")])
    assert code == 0 and (tmp_path / "dist" / "Cyberblock-9.8.7.zip").exists()


def test_a_jar_with_minecraft_code_is_refused_and_vanilla_assets_are_reported(repo, tmp_path, capsys):
    with zipfile.ZipFile(repo.jar, "a") as z:
        z.writestr("assets/minecraft/lang/en_us.json", "{}")
    out = repo.zip(tmp_path / "dist")
    assert out.exists() and "assets/minecraft/" in capsys.readouterr().err  # reported, not fatal
    for forbidden in ("net/minecraft/client/Minecraft.class", "com/mojang/blaze3d/Foo.class"):
        with zipfile.ZipFile(repo.jar, "a") as z:
            z.writestr(forbidden, b"\xca\xfe")
        with pytest.raises(package.PackageError, match="game code"):
            repo.zip(tmp_path / "dist2")
        repo.jar.unlink()
        with zipfile.ZipFile(repo.jar, "w") as z:
            z.writestr("fabric.mod.json", "{}")
    assert not (tmp_path / "dist2").exists()


def test_a_broken_jar_is_an_error(repo, tmp_path):
    repo.jar.write_bytes(b"not a zip")
    with pytest.raises(package.PackageError, match="readable jar"):
        repo.zip(tmp_path / "dist")


def test_no_zip_entry_carries_game_assets_or_a_source_tree(repo, tmp_path):
    for n in names(repo.zip(tmp_path / "dist")):
        assert not re.search(r"/(net|com/mojang|assets/minecraft)/", n) and not n.endswith((".pdb", ".obj")), n


# ---- the real repository: documents, notices, release files (skipped parts need the submodules) ----
def test_the_real_repo_has_every_file_the_package_needs():
    for rel in ("README.md", "docs/HOW-TO-PLAY.md", "CHANGELOG.md", "LICENSE", "NOTICE.md", "THIRD-PARTY-NOTICES.md",
                "release/install.bat", "release/uninstall.bat", "release/install.ps1", "docs/media/.gitkeep"):
        assert (REPO / rel).is_file(), rel


LICENSE_FILES = ["third_party/universal-modder/LICENSE", "third_party/RED4ext.SDK/LICENSE.md",
                 "third_party/RED4ext.SDK/THIRD_PARTY_LICENSES.md", "third_party/reshade/LICENSE.md"]


@pytest.mark.parametrize("rel", LICENSE_FILES)
def test_third_party_notices_hold_the_licence_files_verbatim(rel):
    source = REPO / rel
    if not source.is_file():
        pytest.skip(f"{rel} is not checked out (git submodule update --init)")
    text = source.read_text(encoding="utf-8").replace("\r\n", "\n").strip("\n")
    assert text in (REPO / "THIRD-PARTY-NOTICES.md").read_text(encoding="utf-8")


def test_third_party_notices_name_every_redistributed_component():
    notices = (REPO / "THIRD-PARTY-NOTICES.md").read_text(encoding="utf-8")
    for heading in ("universal-modder (MIT)", "RED4ext.SDK (MIT)", "ReShade add-on headers (BSD-3-Clause)", "ReShade.fxh (CC0-1.0)",
                    "nlohmann/json 3.11.3 (MIT)", "Java-WebSocket 1.6.0 (MIT)"):
        assert f"## {heading}" in notices, heading
    assert "Niels Lohmann" in notices and "Patrick Mours" in notices and "Octavian Dima" in notices and "Rehan" in notices


def test_notice_and_readme_carry_the_disclaimer_and_the_placeholders():
    notice, readme = (REPO / "NOTICE.md").read_text(encoding="utf-8"), (REPO / "README.md").read_text(encoding="utf-8")
    for text in (notice, readme):
        assert "not affiliated" in text.lower() and "CD PROJEKT" in text and "Mojang" in text
    assert "<!-- screenshot:" in readme and "Screenshots pending" in readme
    assert "No Minecraft or Cyberpunk 2077 game files" in notice


def test_readme_lists_the_requirements_with_links_and_the_launcher_install():
    readme = (REPO / "README.md").read_text(encoding="utf-8")
    for needle in ("github.com/WopsS/RED4ext", "github.com/jac3km4/redscript", "github.com/psiberx/cp2077-codeware", "reshade.me",
                   "fabricmc.net/use/installer", "modrinth.com/mod/fabric-api", "install.bat", ".minecraft-cyberblock",
                   "-Xmx2G -XX:+UseZGC --enable-native-access=ALL-UNNAMED -Dcyberblock.autostart=true", "Play Cyberblock", "MIT License"):
        assert needle in readme, needle


def test_no_personal_paths_or_decompiled_wording_in_the_docs():
    for path in [REPO / "README.md", REPO / "NOTICE.md", REPO / "CHANGELOG.md", *(REPO / "docs").rglob("*.md")]:
        if "superpowers/plans" in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8")
        for bad in ("<user>", "SteamLibrary", "adamsmasher/cyberpunk", "%TEMP%\\cbs2", "decompiled 2.31", "Decompiled 2.31"):
            assert bad not in text, f"{bad!r} in {path.relative_to(REPO)}"
        assert not re.search(r"[A-Z]:\\(Users|Cyberblock)", text), f"a personal path in {path.relative_to(REPO)}"


def test_the_installer_manifest_format_is_the_one_the_script_documents():
    """install.ps1 writes {"version":1,"files":{rel:sha256},"dirs":[...]}: the doc string of install.py and the script agree."""
    script = (REPO / "release/install.ps1").read_text(encoding="ascii")
    assert "version = 1" in script and "red4ext/plugins/Cyberblock/install-manifest.json" in script
    assert install.MANIFEST == "red4ext/plugins/Cyberblock/install-manifest.json"
    for constant in (install.GAME_EXE, install.SHADER_HEADER, install.PRESET):  # the paths the script spells out itself
        assert constant in script
    assert install.PRESET_TEXT.strip() in script
