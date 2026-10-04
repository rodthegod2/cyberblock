"""Convert the female V (Lucy) cyber skin to the slim (3 px arm) layout and ship both cyber skins (megaplan 5-A1, section 1.12).

Run from anywhere:  uv run python art/convert_slim.py

Reads   art/skins/v_female_lucy_classic_template.png and art/skins/v_male_classic.png
Writes  art/skins/v_female_lucy_slim.png
        mc/src/main/resources/assets/cyberblock/textures/entity/cyber_skin/v_female.png   (slim model)
        mc/src/main/resources/assets/cyberblock/textures/entity/cyber_skin/v_male.png     (classic model)

The conversion narrows each arm cuboid (both arms, base layer and overlay) from 4 to 3 px wide, following the vanilla UV unwrap
(art/skin_layout.py): front, back, top and bottom faces lose their outer column (the slim arm keeps its inner edge against the
body); the two side faces are 4 px deep on both models, so they keep every pixel and only move one column left. Pixels the slim
layout does not use are made transparent. Everything outside the arms is copied unchanged.

The layout of the source is detected from its base arm layers (a base layer is fully opaque): if the columns only a classic arm
uses are transparent and the slim columns are filled, the source is already slim and its arms are copied as they are. The Lucy
template committed in art/skins/ is in that state (its arms are 3 px wide), so the conversion reports "already slim" for it.

Deterministic: no randomness, PNGs written with fixed settings, so two runs give byte-identical files.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image

from skin_layout import ARMS, cuboid_faces, outer_column, region

ART = Path(__file__).resolve().parent
ASSETS = ART.parent / "mc" / "src" / "main" / "resources" / "assets" / "cyberblock"
SOURCE = ART / "skins" / "v_female_lucy_classic_template.png"
SLIM = ART / "skins" / "v_female_lucy_slim.png"
MALE = ART / "skins" / "v_male_classic.png"
LAYERS = ("base", "overlay")


def load(path: Path) -> Image.Image:
    im = Image.open(path)
    if im.size != (64, 64):
        raise ValueError(f"{path.name}: a skin must be 64x64, got {im.size[0]}x{im.size[1]}")
    return im.convert("RGBA")


def arm_faces(part: str, layer: str, slim: bool) -> dict[str, tuple[int, int, int, int]]:
    u, v, _w, _h = region(part, layer)
    return cuboid_faces(u, v, 3 if slim else 4, 12, 4)


def opaque(im: Image.Image, cells: set[tuple[int, int]]) -> int:
    return sum(1 for cell in cells if im.getpixel(cell)[3] > 0)


def cells(rects) -> set[tuple[int, int]]:
    return {(x0 + i, y0 + j) for x0, y0, w, h in rects for j in range(h) for i in range(w)}


def layout(im: Image.Image) -> str:
    """'classic' or 'slim', from the base arm layers; anything else (a hole in a base arm) is an error."""
    verdicts = set()
    for part in ARMS:
        slim_cells = cells(arm_faces(part, "base", True).values())
        classic_only = cells(arm_faces(part, "base", False).values()) - slim_cells
        if opaque(im, classic_only):
            verdicts.add("classic")
        elif opaque(im, slim_cells) == len(slim_cells):
            verdicts.add("slim")
        else:
            raise ValueError(f"{part}: the base layer has holes, so its layout cannot be told")
    if len(verdicts) != 1:
        raise ValueError(f"the two arms disagree about the layout: {sorted(verdicts)}")
    return verdicts.pop()


def narrow_arm(src: Image.Image, dst: Image.Image, part: str, layer: str) -> None:
    """Rebuild one arm cuboid of dst in the slim layout from the classic arm of src."""
    x, y, w, h = region(part, layer)
    dst.paste((0, 0, 0, 0), (x, y, x + w, y + h))
    classic, slim = arm_faces(part, layer, False), arm_faces(part, layer, True)
    for face, (cx, cy, cw, ch) in classic.items():
        piece = src.crop((cx, cy, cx + cw, cy + ch))
        sx, sy, sw, _sh = slim[face]
        if sw < cw:  # top, bottom, front, back: drop the outer column
            drop = outer_column(part, face, cw)
            keep = [i for i in range(cw) if i != drop]
            narrowed = Image.new("RGBA", (sw, ch))
            for n, i in enumerate(keep):
                narrowed.paste(piece.crop((i, 0, i + 1, ch)), (n, 0))
            piece = narrowed
        dst.paste(piece, (sx, sy))


def copy_slim_arm(src: Image.Image, dst: Image.Image, part: str, layer: str) -> None:
    """An arm already in the slim layout: keep its slim faces, clear the pixels the slim layout does not use."""
    x, y, w, h = region(part, layer)
    dst.paste((0, 0, 0, 0), (x, y, x + w, y + h))
    for fx, fy, fw, fh in arm_faces(part, layer, True).values():
        dst.paste(src.crop((fx, fy, fx + fw, fy + fh)), (fx, fy))


def to_slim(src: Image.Image) -> tuple[Image.Image, str]:
    """The slim version of a 64x64 skin and the layout the source was in."""
    found = layout(src)
    out = src.copy()
    for part in ARMS:
        for layer in LAYERS:
            (narrow_arm if found == "classic" else copy_slim_arm)(src, out, part, layer)
    return out, found


def write_png(path: Path, im: Image.Image) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, format="PNG", optimize=False, compress_level=9)


def main() -> None:
    slim, found = to_slim(load(SOURCE))
    write_png(SLIM, slim)
    write_png(ASSETS / "textures" / "entity" / "cyber_skin" / "v_female.png", slim)
    male = load(MALE)
    if layout(male) != "classic":
        raise ValueError(f"{MALE.name} must be a classic skin")
    write_png(ASSETS / "textures" / "entity" / "cyber_skin" / "v_male.png", male)
    action = "arms narrowed from 4 to 3 px" if found == "classic" else "already slim: arms copied, unused pixels cleared"
    print(f"{SOURCE.name}: {found} layout, {action} -> {SLIM.name}; shipped cyber_skin/v_female.png and cyber_skin/v_male.png")


if __name__ == "__main__":
    main()
