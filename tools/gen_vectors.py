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
# re-test 2 (plan section 1.1): every down/wall hit at these alignments, plus a floor hit at 6.907 with float noise, which
# must give one range at k 57 (the k that z 6.907 chooses)
hit_ks = [0, 32, 57, 63]
noise_hits = [(3.5, 2.5, 6.907 - 1e-6), (3.5, 2.5, 6.907), (3.5, 2.5, 6.907 + 1e-6)]
noise_k = 57
mc_points = [(1.0, 3.0, -2.0), (0.5, 64.0, 0.25), (-1245.5, 66.62, -812.25), (10.4, 70.0, 20.6), (-0.01, -60.5, -4.5), (1234.5, 312.0, 987.6)]
mc_blocks = [(10, 4, -21), (0, 0, 0), (-1, -64, -1), (123456, 319, -98765), (-30000000, 100, 29999999), (5, 70, 3)]
pos_ks = [0, 57]
# the fixed cases of plan section 1.1, used by every suite (z = V's feet, Cyberpunk z)
align_zs = [6.907, 6.001, 0.0, -3.25, 312.999, -0.0005, 64.5]
mc_yaws = [0.0, 90.0, -90.0, 180.0, -180.0, 359.5]
mc_vels = [(1.0, 2.0, 3.0), (0.0, -9.8, 0.5), (-30.5, 12.25, 7.0), (0.5, 0.0, 0.25), (200.0, -200.0, 0.5), (-0.01, 0.01, -4.5)]
# The keys Minecraft can name in `binds` (letters, digits, F1-F12, as Windows virtual-key codes) and which of them the
# DLL takes: not the forwarded E Q T X 1-9 F5, not F9, and not Cyberpunk's own on-foot letters W A S D C F I M J H N B P V
# Z R, which leaves G K L O U Y 0 F1-F4 F6-F8 F10-F12. The DLL's keys::bindable and Minecraft's VirtualKeys.of are both
# checked against this set, so the two lists cannot drift apart.
bind_keys = list(range(0x41, 0x5B)) + list(range(0x30, 0x3A)) + list(range(0x70, 0x7C))
bind_refused = {ord(c) for c in "WASDCEQTXFIMJHNBPVZR123456789"} | {0x74, 0x78}


def off(k):
    return cbmath.level_offset(k)


# the Atlas survey: cell edges (x = 2.0 and y = -2.0 sit on one), negative cells, a surface half a metre off a whole y
survey_hits = [(2.0, -2.0, 40.5), (1.999, -1.999, 40.49), (-0.5, 0.5, -3.5), (-1245.5, 812.25, 66.62), (3999.9, -3999.9, 455.0)]
survey_cells = [(0, 0), (-1, -1), (5, -3), (-623, -407), (1999, 1999)]


# re-test 3 (plan section 1.7): `binds` messages applied to a current set of six keys, with the clash rule. Every given
# key is a whole number (the C++ tests cover non-numbers). F1..F6 = 0x70..0x75 (F5 is refused), F10..F12 = 0x79..0x7B.
F = {n: 0x6F + n for n in range(1, 13)}
DEFAULT_BINDS = dict(cbmath.BIND_DEFAULTS)
CUSTOM_BINDS = {"hack": F[1], "skills": F[2], "cyberware": F[3], "activate": F[4], "arms": F[6], "camo": F[7]}
binds_given = [
    ({}, DEFAULT_BINDS),                                   # nothing given: nothing changes
    (dict(DEFAULT_BINDS), CUSTOM_BINDS),                   # all six back to the defaults
    (dict(CUSTOM_BINDS), DEFAULT_BINDS),                   # six custom keys
    ({"hack": F[8]}, CUSTOM_BINDS),                        # a missing name keeps its current key
    ({"arms": F[10], "camo": F[11]}, CUSTOM_BINDS),
    ({"hack": F[1], "skills": F[1]}, DEFAULT_BINDS),       # two-key clash: all six go back to G K U Y L O
    ({"activate": F[2]}, CUSTOM_BINDS),                    # clash with a key that was not given (skills is F2)
    ({"hack": ord("L")}, DEFAULT_BINDS),                   # L as hack now clashes with arms
    ({"skills": ord("O")}, DEFAULT_BINDS),                 # O as skills now clashes with camo
    ({"hack": ord("L"), "arms": F[10]}, DEFAULT_BINDS),    # L as hack is fine once arms moves away
    ({"hack": ord("W")}, {**CUSTOM_BINDS, "skills": 0x47}),  # a refusal falls back to G, which skills holds: clash
    ({"hack": 0x30, "camo": F[12]}, DEFAULT_BINDS),        # 0 and F12 are bindable
] + [
    # every single refusal: each name given each refused kind of key falls back to its own default
    ({name: vk}, CUSTOM_BINDS) for name in cbmath.BIND_NAMES for vk in (ord("W"), ord("E"), ord("1"), 0x74, 0x78, 0, 255)
]


# Phase 3 (megaplan 1.8 item 1, 3-P1): the material codes of `ground.m` and the name rules every copy of the table must obey. The
# names are what Phase 0's CET lines B1-B5 will show: until then the table and these cases are the working contract.
material_names = [
    "concrete_light_02", "Concrete_White_Wall", "plaster_dirty", "concrete_dark_01", "Concrete_Wet_Floor", "rubble_pile",
    "asphalt_road_a", "Road_Marking_Yellow", "sidewalk_slab_a", "pavement_concrete", "curb_stone", "glass_window_01",
    "shopfront_glass_blue", "metal_solid_01", "scaffold_pipe", "Steel_Plate_Rust", "brick_wall_red", "masonry_old",
    "tile_bathroom_white", "marble_floor", "dirt_path", "terrain_rock_cliff", "gravel_a", "water_still", "puddle_street",
    "neon_red_sign", "neon_light_blue_tube", "neon_blue_tube", "emissive_light_gray_panel", "led_pink_strip", "neon_gray_bar",
    "neon_generic_sign", "hologram_ad_01", "environment_probe", "", "None", "unknown_thing", "billboard_frame",
]
materials = {
    "codes": [{"code": c, "id": cbmath.SURFACE_MATERIALS[c] if c < cbmath.NEON_FIRST else f"neon_{cbmath.DYES[c - cbmath.NEON_FIRST]}",
               "block": cbmath.material_block(c), "match": list(cbmath.MATERIAL_MATCH.get(c, ()))}
              for c in range(cbmath.MATERIAL_MAX_CODE + 1)],
    "order": list(cbmath.MATERIAL_ORDER),
    "neon_any": list(cbmath.NEON_ANY),
    "neon_any_code": cbmath.NEON_FIRST,
    "cases": [{"name": n, "code": cbmath.material_code(n)} for n in material_names],
    "clock": [{"time": [h, m], "ticks": cbmath.env_ticks(h, m)} for h, m in ((6, 0), (12, 0), (18, 0), (0, 0), (22, 30), (5, 45), (23, 59), (0, 1))],
}


out = {
    "quat": [{"q": list(q), "yaw": y, "pitch": p} for q in qs for y, p in [cbmath.cp_quat_to_mc_yaw_pitch(*q)]],
    "down": [{"hit": list(h), "k": k, "range": list(cbmath.down_hit_to_range(*h, off(k)))} for k in hit_ks for h in hits]
            + [{"hit": list(h), "k": noise_k, "range": list(cbmath.down_hit_to_range(*h, off(noise_k)))} for h in noise_hits],
    "wall": [{"hit": list(h), "k": k, "range": list(cbmath.wall_hit_to_range(*h, off(k)))} for k in hit_ks for h in hits],
    "mc_pos": [{"mc": list(p), "k": k, "cp": list(cbmath.mc_to_cp_pos(*p, off(k)))} for k in pos_ks for p in mc_points],
    "mc_yaw": [{"mc": y, "cp": cbmath.mc_yaw_to_cp_yaw(y)} for y in mc_yaws],
    "mc_vel": [{"mc": list(v), "cp": list(cbmath.mc_vel_to_cp(*v))} for v in mc_vels],
    "mc_block": [{"block": list(b), "k": k, "min": list(lo), "max": list(hi)}
                 for k in pos_ks for b in mc_blocks for lo, hi in [cbmath.mc_block_to_cp_aabb(*b, off(k))]],
    "align": [{"z": z, "k": cbmath.align_k(z)} for z in align_zs],
    "binds": [{"vk": vk, "bindable": vk not in bind_refused} for vk in bind_keys],
    "binds_apply": [{"given": given, "current": current, "expect": expect, "clash": clash}
                    for given, current in binds_given for expect, clash in [cbmath.apply_binds(given, current)]],
    # the Atlas survey: hits -> (cx, cz, h) at k 0 and 57, and cells -> their Cyberpunk column centre
    "survey": [{"hit": list(h), "k": k, "cell": list(cbmath.survey_cell(*h, off(k)))} for k in pos_ks for h in hits + survey_hits],
    "survey_column": [{"cell": list(c), "cp": list(cbmath.survey_column(*c))} for c in survey_cells],
    "materials": materials,
}
Path("shared/vectors.json").write_text(json.dumps(out, indent=1), newline="\n")
print(f"wrote {len(out['quat'])} quat, {len(out['down'])} down, {len(out['wall'])} wall, "
      f"{len(out['mc_pos'])} mc_pos, {len(out['mc_block'])} mc_block, {len(out['align'])} align, "
      f"{len(out['mc_yaw'])} mc_yaw, {len(out['mc_vel'])} mc_vel, {len(out['binds'])} binds, "
      f"{len(out['binds_apply'])} binds_apply, {len(out['survey'])} survey, {len(out['survey_column'])} survey_column, "
      f"{len(materials['codes'])} material codes, {len(materials['cases'])} material cases")
