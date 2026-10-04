"""Front, back and side previews of the cyber skins on their player models (megaplan 5-A1, section 1.12).

Run from anywhere:  uv run python art/preview_skins.py      (after art/convert_slim.py)

Writes art/preview_skins.png, one row per skin, each row: front, back, right side, left side (an orthographic view of every
part's base layer with its overlay drawn over it), then the flat 64x64 texture on a checkerboard.

    row 1  male V (art/skins/v_male_classic.png) on the classic model
    row 2  female V = Lucy (art/skins/v_female_lucy_slim.png) on the slim model
    row 3  the male skin narrowed by art/convert_slim.py, on the slim model: shows the narrowing itself (the Lucy source is
           already slim, so row 2 does not exercise it)

What to look for: arms that keep their pattern edge to edge, no shifted columns, nothing missing where the arm meets the
shoulder, the slim arms half a pixel lower (as vanilla draws them). Deterministic: two runs give byte-identical files.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image

import convert_slim
from skin_layout import PARTS, box, faces

ART = Path(__file__).resolve().parent
ZOOM = 6
PAD = 12
BACKGROUND = (29, 32, 44, 255)
VIEWS = ("front", "back", "right", "left")
CELL_W, CELL_H = 16 * ZOOM, 33 * ZOOM  # 16 px of model width (8 for the side views), 32 px of height plus the slim drop


def face_image(skin: Image.Image, part: str, layer: str, face: str, slim: bool) -> Image.Image:
    x, y, w, h = faces(part, layer, slim)[face]
    return skin.crop((x, y, x + w, y + h))


def placement(part: str, view: str, slim: bool) -> tuple[int, int]:
    """Top-left corner of a part's face in a view, in model pixels: x across the view, y down from the top of the head."""
    x0, y0, z0, w, _h, d = box(part, slim)
    across = {"front": x0 + 8, "back": 8 - (x0 + w), "right": 4 - (z0 + d), "left": z0 + 4}[view]
    return across, y0


def depth_order(view: str, slim: bool) -> list[str]:
    """Parts from the farthest to the nearest (painter's order): side views see the near arm over the body."""
    if view == "right":
        return sorted(PARTS, key=lambda p: -box(p, slim)[0])
    if view == "left":
        return sorted(PARTS, key=lambda p: box(p, slim)[0] + box(p, slim)[3])
    return list(PARTS)


def render_view(skin: Image.Image, view: str, slim: bool) -> Image.Image:
    cell = Image.new("RGBA", (CELL_W, CELL_H), BACKGROUND)
    width = 8 if view in ("right", "left") else 16
    left = (16 - width) * ZOOM // 2
    for part in depth_order(view, slim):
        across, down = placement(part, view, slim)
        drop = ZOOM // 2 if slim and part in ("right_arm", "left_arm") else 0  # vanilla: slim arms sit half a pixel lower
        for layer in ("base", "overlay"):
            img = face_image(skin, part, layer, view, slim)
            big = img.resize((img.width * ZOOM, img.height * ZOOM), Image.NEAREST)
            cell.alpha_composite(big, (left + across * ZOOM, down * ZOOM + drop))
    return cell


def flat(skin: Image.Image) -> Image.Image:
    size = 64 * 3
    sheet = Image.new("RGBA", (size, size))
    for y in range(size):
        for x in range(size):
            sheet.putpixel((x, y), (70, 72, 86, 255) if (x // 6 + y // 6) % 2 else (54, 56, 68, 255))
    sheet.alpha_composite(skin.resize((size, size), Image.NEAREST))
    return sheet


def row(skin: Image.Image, slim: bool) -> Image.Image:
    cells = [render_view(skin, view, slim) for view in VIEWS]
    texture = flat(skin)
    out = Image.new("RGBA", (len(cells) * (CELL_W + PAD) + texture.width, max(CELL_H, texture.height)), BACKGROUND)
    for n, cell in enumerate(cells):
        out.alpha_composite(cell, (n * (CELL_W + PAD), 0))
    out.alpha_composite(texture, (len(cells) * (CELL_W + PAD), 0))
    return out


def sheet() -> Image.Image:
    male = convert_slim.load(convert_slim.MALE)
    rows = [row(male, False), row(convert_slim.load(convert_slim.SLIM), True), row(convert_slim.to_slim(male)[0], True)]
    out = Image.new("RGBA", (rows[0].width + 2 * PAD, sum(r.height + PAD for r in rows) + PAD), BACKGROUND)
    y = PAD
    for r in rows:
        out.alpha_composite(r, (PAD, y))
        y += r.height + PAD
    return out


def main() -> None:
    convert_slim.write_png(ART / "preview_skins.png", sheet())
    print("wrote art/preview_skins.png")


if __name__ == "__main__":
    main()
