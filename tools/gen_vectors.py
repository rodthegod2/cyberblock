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
mc_yaws = [0.0, 90.0, -90.0, 180.0, -180.0, 359.5]
mc_vels = [(1.0, 2.0, 3.0), (0.0, -9.8, 0.5), (-30.5, 12.25, 7.0), (0.5, 0.0, 0.25), (200.0, -200.0, 0.5), (-0.01, 0.01, -4.5)]
# The keys Minecraft can name in `binds` (letters, digits, F1-F12, as Windows virtual-key codes) and which of them the
# DLL takes (plan 2026-10-03 D5: not W A S D C, not the forwarded E Q T 1-9 F5, not F9). The DLL's keys::bindable and
# Minecraft's VirtualKeys.of are both checked against this set, so the two lists cannot drift apart.
bind_keys = list(range(0x41, 0x5B)) + list(range(0x30, 0x3A)) + list(range(0x70, 0x7C))
bind_refused = {ord(c) for c in "WASDCEQT123456789"} | {0x74, 0x78}
out = {
    "quat": [{"q": list(q), "yaw": y, "pitch": p} for q in qs for y, p in [cbmath.cp_quat_to_mc_yaw_pitch(*q)]],
    "down": [{"hit": list(h), "range": list(cbmath.down_hit_to_range(*h))} for h in hits],
    "wall": [{"hit": list(h), "range": list(cbmath.wall_hit_to_range(*h))} for h in hits],
    "mc_pos": [{"mc": list(p), "cp": list(cbmath.mc_to_cp_pos(*p))} for p in mc_points],
    "mc_yaw": [{"mc": y, "cp": cbmath.mc_yaw_to_cp_yaw(y)} for y in mc_yaws],
    "mc_vel": [{"mc": list(v), "cp": list(cbmath.mc_vel_to_cp(*v))} for v in mc_vels],
    "mc_block": [{"block": list(b), "min": list(lo), "max": list(hi)} for b in mc_blocks for lo, hi in [cbmath.mc_block_to_cp_aabb(*b)]],
    "binds": [{"vk": vk, "bindable": vk not in bind_refused} for vk in bind_keys],
}
Path("shared/vectors.json").write_text(json.dumps(out, indent=1), newline="\n")
print(f"wrote {len(out['quat'])} quat, {len(out['down'])} down, {len(out['wall'])} wall, "
      f"{len(out['mc_pos'])} mc_pos, {len(out['mc_block'])} mc_block, "
      f"{len(out['mc_yaw'])} mc_yaw, {len(out['mc_vel'])} mc_vel, {len(out['binds'])} binds cases")
