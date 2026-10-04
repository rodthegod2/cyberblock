import hashlib
import json
import struct
import subprocess
import zipfile
from pathlib import Path

import pytest

from tools import modpack, package, respack

REPO = Path(__file__).resolve().parents[1]
NEEDLE = "sentinel" + "-address"  # split, so this file does not contain it
ADDRESS = f"play.{NEEDLE}.invalid:25599"
FABRIC = "fabric-api-0.161.0+26.3.jar"
VOICE = "voicechat-fabric-26.3-2.6.0.jar"


def url(name: str) -> str:
    return f"https://cdn.modrinth.com/data/PROJECT/versions/VERSION/{name.replace('+', '%2B')}"


class Inputs:
    """Synthetic jars, urls file and server file under tmp_path (never inside the repo)."""

    def __init__(self, root: Path, ver="9.8.7"):
        self.ver = ver
        self.mods = root / "mods"
        self.mods.mkdir()
        for name in (FABRIC, VOICE):
            with zipfile.ZipFile(self.mods / name, "w") as z:
                z.writestr("fabric.mod.json", name)
        self.urls = root / "urls.json"
        self.urls.write_text(json.dumps({FABRIC: url(FABRIC), VOICE: url(VOICE)}))
        self.server = root / "server.json"
        self.server.write_text(json.dumps({"name": "Cyberblock", "address": ADDRESS}))
        self.jar = root / "cyberblock.jar"
        with zipfile.ZipFile(self.jar, "w") as z:
            z.writestr("fabric.mod.json", "{}")
        src = root / "respack"
        src.mkdir()
        (src / "pack.mcmeta").write_text('{"pack": {"description": "x", "min_format": 97}}')
        self.pack = respack.build(src, root / "packout", ver)
        self.out = root / "out"

    def build(self):
        return modpack.build(self.out, self.jar, self.pack, self.mods, self.urls, self.server, self.ver)


@pytest.fixture
def inputs(tmp_path):
    return Inputs(tmp_path)


def test_index_schema_and_hashes(inputs):
    with zipfile.ZipFile(inputs.build()) as z:
        index = json.loads(z.read("modrinth.index.json"))
    assert index["formatVersion"] == 1 and index["game"] == "minecraft" and index["versionId"] == "9.8.7"
    assert index["dependencies"] == {"minecraft": "26.3", "fabric-loader": "0.19.5"}
    assert [f["path"] for f in index["files"]] == [f"mods/{FABRIC}", f"mods/{VOICE}"]
    for f in index["files"]:
        data = (inputs.mods / Path(f["path"]).name).read_bytes()
        assert f["hashes"] == {"sha1": hashlib.sha1(data).hexdigest(), "sha512": hashlib.sha512(data).hexdigest()}
        assert f["fileSize"] == len(data) and f["env"] == {"client": "required", "server": "required"}
        assert len(f["downloads"]) == 1 and f["downloads"][0].startswith("https://cdn.modrinth.com/data/")


def test_overrides(inputs):
    with zipfile.ZipFile(inputs.build()) as z:
        names = set(z.namelist())
        assert names == {"modrinth.index.json", "overrides/mods/cyberblock-9.8.7.jar", "overrides/resourcepacks/cyberblock-pack-9.8.7.zip",
                         "overrides/options.txt", "overrides/config/cyberblock/join.json", "overrides/servers.dat"}
        assert z.read("overrides/mods/cyberblock-9.8.7.jar") == inputs.jar.read_bytes()
        assert z.read("overrides/resourcepacks/cyberblock-pack-9.8.7.zip") == inputs.pack.read_bytes()
        assert "file/cyberblock-pack-9.8.7.zip" in z.read("overrides/options.txt").decode()
        assert json.loads(z.read("overrides/config/cyberblock/join.json")) == {"schema": 1, "name": "Cyberblock", "address": ADDRESS}
        assert z.testzip() is None


def test_options_txt_is_current_and_names_only_real_packs(inputs):
    # 5023 = 26.3's world_version (version.json): a current `version:` line means Options.load runs no datafixer, and
    # "fabric" is not a pack id in 26.3 with Fabric API (the client logs and drops it).
    with zipfile.ZipFile(inputs.build()) as z:
        lines = z.read("overrides/options.txt").decode().splitlines()
    assert lines == ["version:5023", 'resourcePacks:["vanilla","file/cyberblock-pack-9.8.7.zip"]', "incompatibleResourcePacks:[]"]


def parse_servers_dat(data: bytes):
    """A tiny reader for exactly the NBT shape servers_dat writes, so the test does not trust the writer's own layout."""
    assert data[0] == 10 and data[1:3] == b"\x00\x00"  # unnamed root compound
    pos = 3
    assert data[pos] == 9  # list "servers"
    (n,) = struct.unpack(">H", data[pos + 1:pos + 3])
    assert data[pos + 3:pos + 3 + n] == b"servers"
    pos += 3 + n
    assert data[pos] == 10  # of compounds
    (count,) = struct.unpack(">i", data[pos + 1:pos + 5])
    pos += 5
    servers = []
    for _ in range(count):
        entry = {}
        while data[pos] != 0:
            assert data[pos] == 8  # string tags
            (n,) = struct.unpack(">H", data[pos + 1:pos + 3])
            key = data[pos + 3:pos + 3 + n].decode()
            pos += 3 + n
            (n,) = struct.unpack(">H", data[pos:pos + 2])
            entry[key] = data[pos + 2:pos + 2 + n].decode()
            pos += 2 + n
        pos += 1
        servers.append(entry)
    assert data[pos:] == b"\x00"
    return servers


def test_servers_dat_has_the_entry(inputs):
    with zipfile.ZipFile(inputs.build()) as z:
        assert parse_servers_dat(z.read("overrides/servers.dat")) == [{"ip": ADDRESS, "name": "Cyberblock"}]


def test_deterministic(inputs):
    first = inputs.build().read_bytes()
    assert inputs.build().read_bytes() == first


def test_no_address_in_any_tracked_file():
    tracked = subprocess.run(["git", "ls-files"], cwd=REPO, capture_output=True, text=True, check=True).stdout.split("\n")
    for rel in filter(None, tracked):
        path = REPO / rel
        if path.is_file() and path.stat().st_size < 5_000_000:
            assert NEEDLE.encode() not in path.read_bytes(), rel


def test_private_inputs_inside_the_repo_are_refused(inputs):
    inside = REPO / "dist" / "should_not_exist.json"
    for override in ({"urls_file": inside}, {"server_file": inside}, {"mods_from": REPO / "dist"}):
        args = dict(out=inputs.out, jar=inputs.jar, pack=inputs.pack, mods_from=inputs.mods, urls_file=inputs.urls, server_file=inputs.server, ver="1")
        with pytest.raises(package.PackageError, match="outside the repository"):
            modpack.build(**{**args, **override})
    assert not inputs.out.exists()


@pytest.mark.parametrize("bad", ["", "host name", "host:99999", "host:", "ho/st", "play.example.com:0", "ü.example"])
def test_bad_server_address(inputs, bad):
    inputs.server.write_text(json.dumps({"name": "Cyberblock", "address": bad}))
    with pytest.raises(package.PackageError, match="server file"):
        inputs.build()


@pytest.mark.parametrize("bad_url", [
    "http://cdn.modrinth.com/data/P/versions/V/" + FABRIC,
    "https://evil.example/data/P/versions/V/" + FABRIC,
    "https://cdn.modrinth.com/data/P/versions/V/other.jar",
])
def test_urls_must_be_official_modrinth_urls(inputs, bad_url):
    inputs.urls.write_text(json.dumps({FABRIC: bad_url, VOICE: url(VOICE)}))
    with pytest.raises(package.PackageError, match="Modrinth"):
        inputs.build()
    assert not inputs.out.exists()


def test_fabric_api_is_required(inputs):
    (inputs.mods / FABRIC).unlink()
    with pytest.raises(package.PackageError, match="Fabric API"):
        inputs.build()


def test_the_mod_jar_must_not_come_from_the_mods_folder(inputs):
    (inputs.mods / "cyberblock-1.0.0.jar").write_bytes(inputs.jar.read_bytes())
    with pytest.raises(package.PackageError, match="from --jar"):
        inputs.build()


def test_a_jar_with_game_code_is_refused(inputs):
    with zipfile.ZipFile(inputs.jar, "w") as z:
        z.writestr("net/minecraft/Foo.class", "x")
    with pytest.raises(package.PackageError, match="game code"):
        inputs.build()


def test_cli(inputs, capsys):
    out = inputs.out
    code = modpack.main(["--mods-from", str(inputs.mods), "--urls", str(inputs.urls), "--server-file", str(inputs.server), "--jar", str(inputs.jar),
                         "--pack", str(inputs.pack), "--out", str(out), "--version", "9.8.7"])
    assert code == 0 and (out / "cyberblock-9.8.7.mrpack").is_file()
    assert ADDRESS not in capsys.readouterr().out  # the address is never printed
