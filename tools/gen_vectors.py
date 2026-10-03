"""Regenerate shared/vectors.json from tools/cbmath.py (run after any cbmath change)."""
import json, math
from pathlib import Path
from tools import cbmath


def quat_axis(ax, ay, az, deg):
    h = math.radians(deg) / 2
    return (ax * math.sin(h), ay * math.sin(h), az * math.sin(h), math.cos(h))


def mul(a, b):
    ai, aj, ak, ar = a; bi, bj, bk, br = b
    return (ar * bi + ai * br + aj * bk - ak * bj, ar * bj - ai * bk + aj * br + ak * bi,
            ar * bk + ai * bj - aj * bi + ak * br, ar * br - ai * bi - aj * bj - ak * bk)


qs = [quat_axis(0, 0, 1, d) for d in range(0, 360, 30)] + [quat_axis(1, 0, 0, d) for d in (-89, -45, 45, 89)]
qs.append(mul(quat_axis(0, 0, 1, 135), quat_axis(1, 0, 0, -30)))
hits = [(10.4, 20.6, 5.0), (-3.2, -7.9, 12.25), (0.0, 0.0, 0.0), (1234.5, -987.6, 312.0), (-0.01, 0.01, -4.5), (7.0, 7.0, 7.999)]
mc_points = [(1.0, 3.0, -2.0), (0.5, 64.0, 0.25), (-1245.5, 66.62, -812.25), (10.4, 70.0, 20.6), (-0.01, -60.5, -4.5), (1234.5, 312.0, 987.6)]
mc_blocks = [(10, 4, -21), (0, 0, 0), (-1, -64, -1), (123456, 319, -98765), (-30000000, 100, 29999999), (5, 70, 3)]
out = {
    "quat": [{"q": list(q), "yaw": y, "pitch": p} for q in qs for y, p in [cbmath.cp_quat_to_mc_yaw_pitch(*q)]],
    "down": [{"hit": list(h), "range": list(cbmath.down_hit_to_range(*h))} for h in hits],
    "wall": [{"hit": list(h), "range": list(cbmath.wall_hit_to_range(*h))} for h in hits],
    "mc_pos": [{"mc": list(p), "cp": list(cbmath.mc_to_cp_pos(*p))} for p in mc_points],
    "mc_block": [{"block": list(b), "min": list(lo), "max": list(hi)} for b in mc_blocks for lo, hi in [cbmath.mc_block_to_cp_aabb(*b)]],
}
Path("shared/vectors.json").write_text(json.dumps(out, indent=1), newline="\n")
print(f"wrote {len(out['quat'])} quat, {len(out['down'])} down, {len(out['wall'])} wall, "
      f"{len(out['mc_pos'])} mc_pos, {len(out['mc_block'])} mc_block cases")
