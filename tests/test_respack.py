import hashlib
import json
import zipfile
from pathlib import Path

import pytest

from tools import package, respack

REPO = Path(__file__).resolve().parents[1]


def make_pack(root: Path, extra: dict[str, bytes] | None = None) -> Path:
    src = root / "respack"
    files = {"pack.mcmeta": b'{"pack": {"description": "x", "min_format": 97, "max_format": 97}}\r\n', "pack.png": b"\x89PNG"}
    for name, data in {**files, **(extra or {})}.items():
        (src / name).parent.mkdir(parents=True, exist_ok=True)
        (src / name).write_bytes(data)
    return src


def test_builds_a_zip_with_the_pack_files(tmp_path):
    src = make_pack(tmp_path, {"assets/cyberblock/textures/block/x.png": b"png", ".hidden": b"no"})
    target = respack.build(src, tmp_path / "out", "9.8.7")
    assert target.name == "cyberblock-pack-9.8.7.zip"
    with zipfile.ZipFile(target) as z:
        assert z.namelist() == ["assets/cyberblock/textures/block/x.png", "pack.mcmeta", "pack.png"]
        assert b"\r" not in z.read("pack.mcmeta")  # LF whatever the checkout used, so the SHA-1 is stable
        assert z.testzip() is None


def test_deterministic_across_runs_and_checkouts(tmp_path):
    a = respack.build(make_pack(tmp_path / "a"), tmp_path / "out_a", "1")
    b = respack.build(make_pack(tmp_path / "b"), tmp_path / "out_b", "1")
    assert a.read_bytes() == b.read_bytes()
    assert respack.sha1(a) == hashlib.sha1(a.read_bytes()).hexdigest()


@pytest.mark.parametrize("name", ["assets/minecraft/textures/block/stone.png", "Cyber.archive", "a/b.reds", "x.jar"])
def test_refuses_game_assets_and_code(tmp_path, name):
    src = make_pack(tmp_path, {name: b"x"})
    with pytest.raises(package.PackageError):
        respack.build(src, tmp_path / "out", "1")
    assert not (tmp_path / "out").exists()


@pytest.mark.parametrize("mcmeta", [b"", b"{", b'{"pack": {}}', b'{"pack": {"description": "x", "min_format": "97"}}'])
def test_refuses_a_bad_pack_mcmeta(tmp_path, mcmeta):
    src = make_pack(tmp_path, {"pack.mcmeta": mcmeta})
    with pytest.raises(package.PackageError, match="pack.mcmeta"):
        respack.collect(src)


def test_cli_prints_the_sha1(tmp_path, capsys):
    src = make_pack(tmp_path)
    assert respack.main(["--src", str(src), "--out", str(tmp_path / "out"), "--version", "2"]) == 0
    out = capsys.readouterr().out
    sha = respack.sha1(tmp_path / "out" / "cyberblock-pack-2.zip")
    assert f"resource-pack-sha1={sha}" in out and "require-resource-pack stays false" in out


def test_the_repository_pack_builds_and_is_valid(tmp_path):
    target = respack.build(REPO / "respack", tmp_path, "1")
    with zipfile.ZipFile(target) as z:
        assert {"pack.mcmeta", "pack.png"} <= set(z.namelist())
        meta = json.loads(z.read("pack.mcmeta"))["pack"]
    assert meta["min_format"] == 97  # resource_major of Minecraft 26.3's version.json
    assert respack.build(REPO / "respack", tmp_path / "again", "1").read_bytes() == target.read_bytes()
