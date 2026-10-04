import json
import msvcrt
import zipfile
from pathlib import Path

import pytest

from tools import package, server_bundle

REPO = Path(__file__).resolve().parents[1]
FABRIC = "fabric-api-0.161.0+26.3.jar"
VOICE = "voicechat-fabric-26.3-2.6.0.jar"
LAUNCHER = "fabric-server-mc.26.3-loader.0.19.5-launcher.1.1.0.jar"
# a paper-style file: CRLF, comments, odd spacing, the keys we change, and a key we must leave alone
PROPERTIES = (b"#Minecraft server properties\r\n#Sat Oct 03 12:00:00 UTC 2026\r\nlevel-name=Rods Ores\r\nmotd=A Minecraft Server\r\n"
              b"white-list=false\r\nallow-flight=false\r\nonline-mode=true\r\nenforce-whitelist = false\r\nmax-players=20\r\n"
              b"resource-pack=\r\nrequire-resource-pack=false\r\n")


def make_zip(path: Path, **members) -> Path:
    with zipfile.ZipFile(path, "w") as z:
        for name, data in (members or {"META-INF/MANIFEST.MF": "Manifest-Version: 1.0\n"}).items():
            z.writestr(name.replace("__", "/"), data)
    return path


class Fixture:
    """A fake server backup, a fake world, jars and an example server.json, all under tmp_path."""

    def __init__(self, root: Path):
        self.root = root
        self.backup = root / "backup"
        self.backup.mkdir()
        (self.backup / "server.properties").write_bytes(PROPERTIES)
        (self.backup / "paper.jar").write_bytes(b"paper")
        self.world = root / "saves" / "my local world"  # any folder name: the zip always says cyberblock/
        (self.world / "datapacks").mkdir(parents=True)
        (self.world / "region").mkdir()
        (self.world / "level.dat").write_bytes(b"level")
        (self.world / "datapacks" / "pack.zip").write_bytes(b"dp")
        (self.world / "region" / "r.0.0.mca").write_bytes(b"x" * 1000)
        (self.world / "session.lock").write_bytes("☃".encode())
        self.mods = root / "mods"
        self.mods.mkdir()
        for name in (FABRIC, VOICE):
            make_zip(self.mods / name)
        self.launcher = make_zip(root / LAUNCHER)
        self.jar = make_zip(root / "cyberblock.jar", **{"fabric.mod.json": "{}"})
        self.example = root / "server.json.example"
        self.example.write_text(json.dumps({"schema": 1, "hosts": [], "maxHosts": 1, "joinHelp": ""}))
        self.staging = root / "stage" / "server-staging"

    def plan(self, hosts=(), pack=None):
        return server_bundle.plan(self.backup, self.world, self.mods, self.launcher, self.jar, self.example, list(hosts), pack, "9.8.7")

    def args(self, *extra):
        return ["--backup", str(self.backup), "--world", str(self.world), "--mods-from", str(self.mods), "--launcher", str(self.launcher),
                "--jar", str(self.jar), "--example", str(self.example), "--staging", str(self.staging), *extra]


@pytest.fixture
def fx(tmp_path):
    return Fixture(tmp_path)


def test_plan_lists_the_staged_files(fx):
    assert sorted(fx.plan()) == sorted(["cyberblock.zip", "config/cyberblock/server.json", LAUNCHER, f"mods/{FABRIC}", f"mods/{VOICE}",
                                        "mods/cyberblock-9.8.7.jar", "server.properties", "server.properties.rodsores"])


def test_server_properties_changes_exactly_the_five_keys(fx):
    staged = fx.plan()["server.properties"].decode()
    assert staged.splitlines() == [
        "#Minecraft server properties", "#Sat Oct 03 12:00:00 UTC 2026", "level-name=cyberblock", "motd=Cyberblock", "white-list=true",
        "allow-flight=true", "online-mode=true", "enforce-whitelist=true", "max-players=20", "resource-pack=", "require-resource-pack=false"]
    assert "\r\n" in staged and "\n" not in staged.replace("\r\n", "")  # the file's own line endings are kept


def test_missing_keys_are_appended(fx):
    (fx.backup / "server.properties").write_bytes(b"online-mode=true")
    lines = fx.plan()["server.properties"].decode().splitlines()
    assert lines[0] == "online-mode=true" and set(lines[1:]) == {"level-name=cyberblock", "allow-flight=true", "white-list=true",
                                                                 "enforce-whitelist=true", "motd=Cyberblock"}


def test_resource_pack_keys_only_with_a_url(fx):
    assert b"resource-pack-sha1" not in fx.plan()["server.properties"]
    url, sha = "https://example.invalid/pack.zip", "a" * 40
    lines = fx.plan(pack=(url, sha))["server.properties"].decode().splitlines()
    assert f"resource-pack={url}" in lines and f"resource-pack-sha1={sha}" in lines and "require-resource-pack=false" in lines
    assert "resource-pack=" not in lines


def test_rodsores_copy_is_byte_identical(fx):
    assert fx.plan()["server.properties.rodsores"] == PROPERTIES


def test_server_json_gets_the_hosts(fx):
    data = json.loads(fx.plan(hosts=["Alice", "0123abcd-0123-4abc-8def-0123456789ab"])["config/cyberblock/server.json"])
    assert data == {"schema": 1, "hosts": ["Alice", "0123abcd-0123-4abc-8def-0123456789ab"], "maxHosts": 1, "joinHelp": ""}
    assert json.loads(fx.plan()["config/cyberblock/server.json"])["hosts"] == []


@pytest.mark.parametrize("bad", ["not a name", "x" * 17, "a;b", ""])
def test_bad_host_names_are_refused(fx, bad):
    with pytest.raises(package.PackageError, match="player names"):
        fx.plan(hosts=[bad])


def test_stage_writes_the_files_and_the_world_zip(fx):
    sizes = server_bundle.stage(fx.staging, fx.plan())
    assert (fx.staging / "server.properties.rodsores").read_bytes() == PROPERTIES
    assert (fx.staging / LAUNCHER).read_bytes() == fx.launcher.read_bytes()
    with zipfile.ZipFile(fx.staging / "cyberblock.zip") as z:
        assert sorted(z.namelist()) == ["cyberblock/datapacks/pack.zip", "cyberblock/level.dat", "cyberblock/region/r.0.0.mca"]  # no session.lock
        assert z.read("cyberblock/level.dat") == b"level"
    assert sizes["cyberblock.zip"] == (fx.staging / "cyberblock.zip").stat().st_size
    assert set(sizes) == set(fx.plan())


def test_refuses_while_session_lock_is_held(fx):
    with (fx.world / "session.lock").open("r+b") as holder:  # what Minecraft's FileChannel.tryLock does
        msvcrt.locking(holder.fileno(), msvcrt.LK_NBLCK, 1)
        try:
            assert server_bundle.session_lock_held(fx.world)
            with pytest.raises(package.PackageError, match="session.lock"):
                fx.plan()
        finally:
            holder.seek(0)
            msvcrt.locking(holder.fileno(), msvcrt.LK_UNLCK, 1)
    assert not server_bundle.session_lock_held(fx.world)
    fx.plan()  # and works again once it is released


@pytest.mark.parametrize("what", ["level.dat", "server.properties", "fabric-api", "launcher"])
def test_refuses_incomplete_inputs(fx, what):
    if what == "level.dat":
        (fx.world / "level.dat").unlink()
    elif what == "server.properties":
        (fx.backup / "server.properties").unlink()
    elif what == "fabric-api":
        (fx.mods / FABRIC).unlink()
    else:
        fx.launcher.write_bytes(b"not a jar")
    with pytest.raises(package.PackageError):
        fx.plan()


def test_the_mod_jar_must_not_come_from_the_mods_folder(fx):
    make_zip(fx.mods / "cyberblock-1.0.0.jar")
    with pytest.raises(package.PackageError, match="--jar"):
        fx.plan()


def test_dry_run_prints_the_list_and_writes_nothing(fx, capsys):
    assert server_bundle.main(fx.args("--dry-run")) == 0
    out = capsys.readouterr().out
    for name in ("cyberblock.zip", "config/cyberblock/server.json", "server.properties", "server.properties.rodsores", f"mods/{FABRIC}",
                 f"mods/{VOICE}", f"mods/cyberblock-{package.version(REPO)}.jar", LAUNCHER):
        assert name in out
    assert f"Custom Jar field: {LAUNCHER}" in out and "Java version: 25" in out and "1,007 bytes" in out  # the world's size before zipping
    assert not fx.staging.exists()


def test_cli_stages_and_prints_the_jar_name(fx, capsys):
    assert server_bundle.main(fx.args("--hosts", "Alice")) == 0
    out = capsys.readouterr().out
    assert f"Custom Jar field: {LAUNCHER}" in out and str(fx.staging) in out
    assert (fx.staging / "cyberblock.zip").is_file() and (fx.staging / "mods" / f"cyberblock-{package.version(REPO)}.jar").is_file()
    assert (fx.backup / "server.properties").read_bytes() == PROPERTIES  # the backup is never touched


def test_staging_must_be_empty_or_cleaned(fx, capsys):
    assert server_bundle.main(fx.args()) == 0
    (fx.staging / "stale.txt").write_text("stale")
    assert server_bundle.main(fx.args()) == 1
    assert "not empty" in capsys.readouterr().err
    assert server_bundle.main(fx.args("--clean")) == 0
    assert not (fx.staging / "stale.txt").exists()


def test_clean_only_for_a_folder_named_server_staging(fx, capsys):
    other = fx.root / "stage" / "precious"
    other.mkdir(parents=True)
    (other / "keep.txt").write_text("keep")
    assert server_bundle.main(fx.args("--clean", "--staging", str(other))) == 1
    assert (other / "keep.txt").exists()


def test_staging_inside_the_repo_is_refused(fx, capsys):
    assert server_bundle.main(fx.args("--staging", str(REPO / "dist" / "server-staging"))) == 1
    assert "outside the repository" in capsys.readouterr().err


def test_warns_about_a_resource_pack_inherited_from_the_backup(fx, capsys):
    (fx.backup / "server.properties").write_bytes(PROPERTIES.replace(b"resource-pack=\r\n", b"resource-pack=https://x.invalid/p.zip\r\n"))
    assert server_bundle.main(fx.args("--dry-run")) == 0
    assert "warning: the backup's server.properties pushes a resource pack" in capsys.readouterr().out


@pytest.mark.parametrize("args", [["--pack-url", "https://x.invalid/p.zip"], ["--pack-url", "http://x.invalid/p.zip", "--pack-sha1", "a" * 40],
                                  ["--pack-url", "https://x.invalid/p.zip", "--pack-sha1", "ABC"]])
def test_bad_pack_arguments(fx, args, capsys):
    assert server_bundle.main(fx.args(*args)) == 1
    assert not fx.staging.exists()
