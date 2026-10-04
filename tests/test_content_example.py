"""examples/content-datapack: the datapack docs/CONTENT.md walks through must stay valid, complete and self-consistent."""
import json
import struct
import zlib
from pathlib import Path

import pytest

from tools import content_check as cc

REPO = Path(__file__).resolve().parents[1]
PACK = REPO / "examples" / "content-datapack"
NAMESPACE = "neon_crew"
KINDS = {"dialogue", "skin_map", "shop", "device_map", "landmark", "daemon", "event"}


def files(kind: str) -> list[Path]:
    """The example's own files of a kind (not the one shipped default it replaces)."""
    return sorted((PACK / "data" / NAMESPACE / "cyberblock" / kind).glob("*.json"))


def test_the_example_passes_content_check_with_no_warnings(capsys):
    assert cc.main(["--dir", str(PACK)]) == 0
    out = capsys.readouterr().out
    assert "warning" not in out and "FAIL" not in out and "namespaces: cyberblock, neon_crew" in out


def test_the_example_shows_every_kind_the_guide_names():
    result = cc.check([cc.data_root(PACK)], reference_roots=[cc.DEFAULT_DATA])
    assert result.kinds == KINDS and result.problems == [] and result.warnings == []


def test_the_example_uses_its_own_namespace_and_replaces_one_shipped_file_by_its_id():
    assert {p.name for p in (PACK / "data").iterdir()} == {NAMESPACE, "cyberblock"}
    replaced = sorted(p.relative_to(PACK / "data" / "cyberblock").as_posix() for p in (PACK / "data" / "cyberblock").rglob("*.json"))
    assert replaced == ["cyberblock/device_map/vending_machine.json"]
    assert (cc.DEFAULT_DATA / "cyberblock" / replaced[0]).is_file(), "the replaced id is a shipped default"


def test_the_example_has_a_pack_mcmeta_for_this_game_version():
    assert cc.check_pack_meta(PACK) == []


def test_the_skin_texture_the_example_names_ships_as_a_64x64_png():
    for path in files("skin_map"):
        ref = json.loads(path.read_text(encoding="utf-8"))["texture"]
        namespace, rel = ref.split(":", 1)
        png = PACK / "assets" / namespace / rel
        assert png.is_file(), ref
        data = png.read_bytes()
        assert data[:8] == b"\x89PNG\r\n\x1a\n"
        width, height = struct.unpack(">II", data[16:24])
        assert (width, height) == (64, 64)
        assert zlib.decompress(b"".join(chunk_data(data, b"IDAT")))  # a real image stream


def chunk_data(data: bytes, name: bytes) -> list[bytes]:
    out, i = [], 8
    while i < len(data):
        (length,) = struct.unpack(">I", data[i:i + 4])
        if data[i + 4:i + 8] == name:
            out.append(data[i + 8:i + 8 + length])
        i += 12 + length
    return out


def lang() -> dict[str, str]:
    return json.loads((PACK / "assets" / NAMESPACE / "lang" / "en_us.json").read_text(encoding="utf-8"))


def test_every_lang_key_the_example_uses_is_in_its_lang_file():
    keys = lang()
    used = set()
    for kind in ("shop", "daemon"):
        for path in files(kind):
            doc = json.loads(path.read_text(encoding="utf-8"))
            used |= {v for k, v in doc.items() if k.endswith("_key")}
    assert used and used <= set(keys), used - set(keys)
    assert all(v.strip() for v in keys.values())


def test_the_example_dialogue_adds_to_a_shipped_role_and_the_vending_override_keeps_the_shipped_shops():
    dialogue = json.loads(files("dialogue")[0].read_text(encoding="utf-8"))
    assert dialogue["role"] == "cyberblock:bartender"
    vending = json.loads((PACK / "data" / "cyberblock" / "cyberblock" / "device_map" / "vending_machine.json").read_text(encoding="utf-8"))
    shipped = json.loads((cc.DEFAULT_DATA / "cyberblock" / "cyberblock" / "device_map" / "vending_machine.json").read_text(encoding="utf-8"))
    assert shipped["class"] == vending["class"] and set(shipped["shops"]) <= set(vending["shops"]) and f"{NAMESPACE}:neon_stall" in vending["shops"]


def test_the_example_files_are_original_text_in_plain_utf8():
    for path in PACK.rglob("*.json"):
        raw = path.read_bytes()
        assert not raw.startswith(b"\xef\xbb\xbf") and b"\r" not in raw, path
