"""The Minecraft 64x64 player skin layout, shared by the skin scripts in art/ (convert_slim, preview_skins, gen_npc_skins).

Every body part is a cuboid of w x h x d pixels whose texture origin is (u, v); its six faces sit in the texture like this
(the vanilla ModelPart cube unwrap, checked against the 26.3 `ModelPart.Cube` UV code):

    (u+d, v) top w x d        (u+d+w, v) bottom w x d
    (u, v+d) right d x h  |  (u+d, v+d) front w x h  |  (u+d+w, v+d) left d x h  |  (u+2d+w, v+d) back w x h

`right` is the face on the player's right side (model -x), `left` the one on the player's left (model +x). Pixel columns run
with model x on top, bottom and front (column 0 = the player's right edge) and against it on back; on `right` column 0 is the
back edge, on `left` column 0 is the front edge. Rows of top and bottom start at the back edge.

Classic arms are 4 x 12 x 4, slim arms 3 x 12 x 4: only the width shrinks, so front, back, top and bottom lose one column and the
two side faces (4 deep) keep their size and move left. The slim arm keeps its inner edge against the body (vanilla PlayerModel:
classic right arm x -8..-4, slim -7..-4; left arm 4..8 and 4..7), so the column that goes is the outer one.
"""
from __future__ import annotations

Rect = tuple[int, int, int, int]  # x, y, width, height

FACES = ("top", "bottom", "right", "front", "left", "back")


def cuboid_faces(u: int, v: int, w: int, h: int, d: int) -> dict[str, Rect]:
    """The six face rectangles of a cuboid of w x h x d pixels whose texture origin is (u, v)."""
    return {
        "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h), "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h), "back": (u + 2 * d + w, v + d, w, h),
    }


# part -> (base layer origin, overlay layer origin, model box (x0, y0, z0) with y down from the top of the head)
PARTS: dict[str, tuple[tuple[int, int], tuple[int, int], tuple[int, int, int]]] = {
    "head": ((0, 0), (32, 0), (-4, 0, -4)),
    "body": ((16, 16), (16, 32), (-4, 8, -2)),
    "right_arm": ((40, 16), (40, 32), (-8, 8, -2)),
    "left_arm": ((32, 48), (48, 48), (4, 8, -2)),
    "right_leg": ((0, 16), (0, 32), (-4, 20, -2)),
    "left_leg": ((16, 48), (0, 48), (0, 20, -2)),
}
ARMS = ("right_arm", "left_arm")


def dims(part: str, slim: bool = False) -> tuple[int, int, int]:
    """w, h, d of a part on the classic or slim model."""
    if part == "head":
        return 8, 8, 8
    if part == "body":
        return 8, 12, 4
    return (3 if slim and part in ARMS else 4), 12, 4


def faces(part: str, layer: str = "base", slim: bool = False) -> dict[str, Rect]:
    """The face rectangles of one part on one layer ("base" or "overlay")."""
    base, overlay, _box = PARTS[part]
    u, v = base if layer == "base" else overlay
    return cuboid_faces(u, v, *dims(part, slim))


def box(part: str, slim: bool = False) -> tuple[int, int, int, int, int, int]:
    """The part's model box (x0, y0, z0, w, h, d), y down from the top of the head; a slim right arm starts one pixel further in."""
    x0, y0, z0 = PARTS[part][2]
    w, h, d = dims(part, slim)
    if slim and part == "right_arm":
        x0 += 1
    return x0, y0, z0, w, h, d


def outer_column(part: str, face: str, width: int) -> int:
    """The column of a top, bottom, front or back face that lies on the arm's outer edge (the one the slim model drops)."""
    outer_is_min_x = part == "right_arm"
    runs_with_x = face != "back"
    return 0 if outer_is_min_x == runs_with_x else width - 1


def region(part: str, layer: str) -> Rect:
    """The 16 x 16 block of the texture an arm or leg cuboid owns (head: 32 x 16, body: 24 x 16)."""
    base, overlay, _box = PARTS[part]
    u, v = base if layer == "base" else overlay
    w, h, d = dims(part)
    return u, v, 2 * (w + d), d + h
