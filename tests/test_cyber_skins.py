"""Megaplan 5-A1: the slim conversion of the female V (Lucy) cyber skin, the shipped cyber skins and their preview."""
import sys
from pathlib import Path

import pytest
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "art"))  # the art scripts import their shared skin_layout module by name

import convert_slim  # noqa: E402
import preview_skins  # noqa: E402
from skin_layout import cuboid_faces  # noqa: E402

CYBER = ROOT / "mc" / "src" / "main" / "resources" / "assets" / "cyberblock" / "textures" / "entity" / "cyber_skin"
ARM_BLOCKS = [(40, 16), (40, 32), (32, 48), (48, 48)]  # right arm, right sleeve, left arm, left sleeve


def coded_skin() -> Image.Image:
    """A classic skin whose every pixel names its own position (R = x, G = y), fully opaque."""
    im = Image.new("RGBA", (64, 64))
    for y in range(64):
        for x in range(64):
            im.putpixel((x, y), (x, y, 7, 255))
    return im


def source_of(im: Image.Image, x: int, y: int) -> tuple[int, int] | None:
    r, g, _b, a = im.getpixel((x, y))
    return (r, g) if a else None


def run(monkeypatch, tmp: Path) -> dict[str, bytes]:
    monkeypatch.setattr(convert_slim, "SLIM", tmp / "art" / "skins" / "v_female_lucy_slim.png")
    monkeypatch.setattr(convert_slim, "ASSETS", tmp / "assets")
    monkeypatch.setattr(preview_skins, "ART", tmp / "art")
    convert_slim.main()
    preview_skins.main()
    return {p.relative_to(tmp).as_posix(): p.read_bytes() for p in sorted(tmp.rglob("*.png"))}


def test_two_runs_are_byte_identical_and_match_the_committed_files(monkeypatch, tmp_path):
    first, second = run(monkeypatch, tmp_path / "a"), run(monkeypatch, tmp_path / "b")
    assert first == second and len(first) == 4
    committed = {"art/skins/v_female_lucy_slim.png": ROOT / "art" / "skins" / "v_female_lucy_slim.png",
                 "art/preview_skins.png": ROOT / "art" / "preview_skins.png",
                 "assets/textures/entity/cyber_skin/v_female.png": CYBER / "v_female.png",
                 "assets/textures/entity/cyber_skin/v_male.png": CYBER / "v_male.png"}
    for rel, path in committed.items():
        assert path.read_bytes() == first[rel], f"{rel} differs from the committed file: run art/convert_slim.py and art/preview_skins.py"


def test_the_layouts_of_the_committed_skins():
    assert convert_slim.layout(convert_slim.load(convert_slim.MALE)) == "classic"
    assert convert_slim.layout(convert_slim.load(convert_slim.SOURCE)) == "slim"  # the Lucy template already has 3 px arms
    assert convert_slim.layout(convert_slim.load(CYBER / "v_female.png")) == "slim"
    assert convert_slim.layout(convert_slim.load(CYBER / "v_male.png")) == "classic"


def test_narrowing_drops_the_outer_column_and_moves_the_side_faces():
    out, found = convert_slim.to_slim(coded_skin())
    assert found == "classic"
    for (u, v), outer_min_x in zip(ARM_BLOCKS, (True, True, False, False)):
        classic, slim = cuboid_faces(u, v, 4, 12, 4), cuboid_faces(u, v, 3, 12, 4)
        for face, (sx, sy, sw, sh) in slim.items():
            cx, cy, cw, _ch = classic[face]
            if face in ("right", "left"):
                kept = list(range(cw))  # the side faces are 4 deep on both models
            else:
                runs_with_x = face != "back"
                drop = 0 if outer_min_x == runs_with_x else cw - 1
                kept = [i for i in range(cw) if i != drop]
            for j in range(sh):
                for n in range(sw):
                    assert source_of(out, sx + n, sy + j) == (cx + kept[n], cy + j), (u, v, face, n, j)
        used = {(sx + i, sy + j) for sx, sy, sw, sh in slim.values() for i in range(sw) for j in range(sh)}
        for y in range(v, v + 16):
            for x in range(u, u + 16):
                if (x, y) not in used:
                    assert out.getpixel((x, y))[3] == 0, (x, y)


def test_the_right_arm_keeps_its_inner_column_against_the_body():
    """Spot values: front loses x=44 (outer, model -x), back loses x=55; the left arm loses front x=39 and back x=44."""
    out, _ = convert_slim.to_slim(coded_skin())
    assert [source_of(out, x, 20) for x in (44, 45, 46)] == [(45, 20), (46, 20), (47, 20)]
    assert [source_of(out, x, 20) for x in (51, 52, 53)] == [(52, 20), (53, 20), (54, 20)]
    assert [source_of(out, x, 52) for x in (36, 37, 38)] == [(36, 52), (37, 52), (38, 52)]
    assert [source_of(out, x, 52) for x in (43, 44, 45)] == [(45, 52), (46, 52), (47, 52)]
    assert [source_of(out, x, 16) for x in (44, 45, 46, 47, 48, 49)] == [(45, 16), (46, 16), (47, 16), (49, 16), (50, 16), (51, 16)]


def test_everything_outside_the_arms_is_unchanged():
    source = coded_skin()
    out, _ = convert_slim.to_slim(source)
    arms = {(x, y) for u, v in ARM_BLOCKS for x in range(u, u + 16) for y in range(v, v + 16)}
    for y in range(64):
        for x in range(64):
            if (x, y) not in arms:
                assert out.getpixel((x, y)) == source.getpixel((x, y)), (x, y)


def test_the_shipped_lucy_keeps_every_pixel_of_the_template_the_slim_model_uses():
    source, shipped = convert_slim.load(convert_slim.SOURCE), convert_slim.load(CYBER / "v_female.png")
    arms = {(x, y) for u, v in ARM_BLOCKS for x in range(u, u + 16) for y in range(v, v + 16)}
    used = {(sx + i, sy + j) for u, v in ARM_BLOCKS for sx, sy, sw, sh in cuboid_faces(u, v, 3, 12, 4).values()
            for i in range(sw) for j in range(sh)}
    for y in range(64):
        for x in range(64):
            want = source.getpixel((x, y)) if (x, y) not in arms or (x, y) in used else (0, 0, 0, 0)
            if want[3] == 0:
                assert shipped.getpixel((x, y))[3] == 0, (x, y)
            else:
                assert shipped.getpixel((x, y)) == want, (x, y)


def test_a_skin_with_holes_in_a_base_arm_is_refused():
    broken = coded_skin()
    for x in range(40, 56):
        for y in range(16, 32):
            broken.putpixel((x, y), (0, 0, 0, 0))
    with pytest.raises(ValueError, match="holes"):
        convert_slim.layout(broken)


def test_the_preview_has_three_rows_of_four_views_and_the_texture():
    sheet = Image.open(ROOT / "art" / "preview_skins.png")
    row_w = 4 * (preview_skins.CELL_W + preview_skins.PAD) + 64 * 3
    assert sheet.size == (row_w + 2 * preview_skins.PAD, 3 * (preview_skins.CELL_H + preview_skins.PAD) + preview_skins.PAD)
