import json, math, random
from pathlib import Path
from tools import cbmath

def test_position_mapping():
    assert cbmath.cp_to_mc_pos(1.0, 2.0, 3.0) == (1.0, 3.0, -2.0)

def test_mc_to_cp_position_mapping():
    assert cbmath.mc_to_cp_pos(1.0, 3.0, -2.0) == (1.0, 2.0, 3.0)

def test_mc_cp_positions_round_trip_both_ways():
    for p in [(1.0, 2.0, 3.0), (-1245.5, 812.25, 66.62), (0.5, -0.25, 1e6)]:
        assert cbmath.mc_to_cp_pos(*cbmath.cp_to_mc_pos(*p)) == p
        assert cbmath.cp_to_mc_pos(*cbmath.mc_to_cp_pos(*p)) == p

def test_mc_block_to_cp_aabb():
    assert cbmath.mc_block_to_cp_aabb(10, 4, -21) == ((10, 20, 4), (11, 21, 5))
    assert cbmath.mc_block_to_cp_aabb(0, 0, 0) == ((0, -1, 0), (1, 0, 1))  # z = 0 spans Cyberpunk y -1..0

def test_block_aabb_is_the_cp_image_of_the_block_corners():
    for b in [(10, 4, -21), (-1, -64, -1), (123456, 319, -98765), (3, 70, 4)]:
        lo, hi = cbmath.mc_block_to_cp_aabb(*b)
        corners = [cbmath.mc_to_cp_pos(b[0] + dx, b[1] + dy, b[2] + dz) for dx in (0, 1) for dy in (0, 1) for dz in (0, 1)]
        assert lo == tuple(min(c[i] for c in corners) for i in range(3))
        assert hi == tuple(max(c[i] for c in corners) for i in range(3))

def test_block_aabb_agrees_with_the_probe_cell_of_a_point_inside_it():
    # a Cyberpunk point inside the box falls in the same Minecraft column cell the layer-1 probes use
    lo, hi = cbmath.mc_block_to_cp_aabb(10, 4, -21)
    inside = tuple((a + b) / 2 for a, b in zip(lo, hi))
    assert cbmath.wall_hit_to_range(*inside) == (10, -21, 4, 4)

def test_identity_quaternion_looks_down_cp_plus_y():
    # CP +Y maps to MC -Z, which is yaw 180
    yaw, pitch = cbmath.cp_quat_to_mc_yaw_pitch(0.0, 0.0, 0.0, 1.0)
    assert math.isclose(abs(yaw), 180.0, abs_tol=1e-9) and math.isclose(pitch, 0.0, abs_tol=1e-9)

def test_looking_down_is_positive_pitch():
    # rotate +Y toward -Z by 90 deg about +X: q = (sin(-45), 0, 0, cos(-45))
    s = math.sin(math.radians(-45)); c = math.cos(math.radians(-45))
    _, pitch = cbmath.cp_quat_to_mc_yaw_pitch(s, 0.0, 0.0, c)
    assert math.isclose(pitch, 90.0, abs_tol=1e-6)

def test_vfov():
    assert math.isclose(cbmath.vfov_from_hfov(90.0, 16 / 9), 58.7155, abs_tol=1e-3)

def test_probe_ranges():
    assert cbmath.down_hit_to_range(10.4, 20.6, 5.0) == (10, -21, 3, 4)
    assert cbmath.wall_hit_to_range(10.4, 20.6, 5.7) == (10, -21, 5, 5)

def _cp_yaw_to_mc_yaw(a):
    """The layer 2 formula (cp/plugin/src/world.cpp cp_yaw_to_mc_yaw): m = 180 - a, normalised to (-180, 180]."""
    m = math.fmod(180.0 - a, 360.0)
    return m - 360.0 if m > 180.0 else m + 360.0 if m <= -180.0 else m

def test_mc_yaw_to_cp_yaw_known_values():
    assert cbmath.mc_yaw_to_cp_yaw(0) == 180
    assert cbmath.mc_yaw_to_cp_yaw(90) == 90
    assert cbmath.mc_yaw_to_cp_yaw(-90) == -90      # 180 - -90 = 270 -> normalised to -90
    assert cbmath.mc_yaw_to_cp_yaw(180) == 0
    assert cbmath.mc_yaw_to_cp_yaw(-180) == 0       # 360 -> 0
    assert cbmath.mc_yaw_to_cp_yaw(359.5) == -179.5

def test_mc_yaw_to_cp_yaw_is_normalised_to_half_open_range():
    for m in range(-720, 721, 15):
        a = cbmath.mc_yaw_to_cp_yaw(m)
        assert -180 < a <= 180

def test_mc_yaw_cp_yaw_round_trip_with_the_layer2_formula():
    for m in [-179.5, -90, -1, 0, 0.25, 45, 90, 135.5, 180]:
        assert math.isclose(_cp_yaw_to_mc_yaw(cbmath.mc_yaw_to_cp_yaw(m)), m, abs_tol=1e-9)
    for a in [-179.5, -90, 0, 90, 179.9, 180]:
        assert math.isclose(cbmath.mc_yaw_to_cp_yaw(_cp_yaw_to_mc_yaw(a)), a, abs_tol=1e-9)

def test_mc_yaw_to_cp_yaw_agrees_with_the_quaternion_conversion():
    # a camera yawed by a (counter-clockwise from above, 0 = +Y) looks along Minecraft yaw 180 - a
    for a in range(-170, 180, 30):
        h = math.radians(a) / 2
        yaw, _ = cbmath.cp_quat_to_mc_yaw_pitch(0, 0, math.sin(h), math.cos(h))
        assert math.isclose(cbmath.mc_yaw_to_cp_yaw(yaw), a, abs_tol=1e-6)

def test_mc_vel_to_cp_is_the_position_mapping_without_offset():
    assert cbmath.mc_vel_to_cp(1, 2, 3) == (1, -3, 2)
    for v in [(0.0, -9.8, 0.0), (-30.5, 12.25, 7.0)]:
        assert cbmath.mc_vel_to_cp(*v) == cbmath.mc_to_cp_pos(*v)

# ---- re-test 2 (plan 2026-10-03-retest2 section 1.1): the ground alignment ----------------------------------------------

OFFSETS = [cbmath.level_offset(k) for k in (0, 1, 32, 57, 63)]

def test_level_offset_is_k_sixty_fourths_and_exact_in_float32():
    import struct
    for k in range(cbmath.LEVEL_STEPS):
        off = cbmath.level_offset(k)
        assert off == k / 64
        assert struct.unpack("f", struct.pack("f", off))[0] == off

def test_offset_moves_positions_by_off_both_ways():
    assert cbmath.cp_to_mc_pos(1.0, 2.0, 3.0, 0.5) == (1.0, 2.5, -2.0)
    assert cbmath.mc_to_cp_pos(1.0, 2.5, -2.0, 0.5) == (1.0, 2.0, 3.0)
    assert cbmath.mc_to_cp_pos(0.0, 70.0, 0.0, 57 / 64) == (0.0, -0.0, 70.890625)

def test_offset_round_trips():
    for off in OFFSETS:
        for p in [(1.0, 2.0, 3.0), (-1245.5, 812.25, 66.62), (0.5, -0.25, 1e6), (7.25, -3.0, 6.907)]:
            back = cbmath.mc_to_cp_pos(*cbmath.cp_to_mc_pos(*p, off), off)
            assert all(math.isclose(a, b, rel_tol=1e-12, abs_tol=1e-9) for a, b in zip(back, p))
            back = cbmath.cp_to_mc_pos(*cbmath.mc_to_cp_pos(*p, off), off)
            assert all(math.isclose(a, b, rel_tol=1e-12, abs_tol=1e-9) for a, b in zip(back, p))

def test_block_aabb_with_offset_is_the_cp_image_of_the_block_corners():
    for off in OFFSETS:
        for b in [(10, 4, -21), (-1, -64, -1), (3, 70, 4)]:
            lo, hi = cbmath.mc_block_to_cp_aabb(*b, off)
            assert lo == (b[0], -(b[2] + 1), b[1] + off) and hi == (b[0] + 1, -b[2], b[1] + 1 + off)
            corners = [cbmath.mc_to_cp_pos(b[0] + dx, b[1] + dy, b[2] + dz, off) for dx in (0, 1) for dy in (0, 1) for dz in (0, 1)]
            assert lo == tuple(min(c[i] for c in corners) for i in range(3))
            assert hi == tuple(max(c[i] for c in corners) for i in range(3))
            # a point inside the block falls in its cell
            inside = tuple((a + c) / 2 for a, c in zip(lo, hi))
            assert cbmath.wall_hit_to_range(*inside, off) == (b[0], b[2], b[1], b[1])

def test_down_hits_round_to_the_nearest_whole_y():
    # the barrier's top face (range top + 1) is the nearest whole Minecraft y to z - off; ties go up
    assert cbmath.down_hit_to_range(0.5, 0.5, 12.25) == (0, -1, 10, 11)     # was 12 with ceil: a block above the floor
    assert cbmath.down_hit_to_range(0.5, 0.5, 12.5) == (0, -1, 11, 12)      # tie goes up
    assert cbmath.down_hit_to_range(0.5, 0.5, 12.49) == (0, -1, 10, 11)
    assert cbmath.down_hit_to_range(0.5, 0.5, 12.0) == (0, -1, 10, 11)
    assert cbmath.down_hit_to_range(0.5, 0.5, -0.75) == (0, -1, -3, -2)
    assert cbmath.down_hit_to_range(0.5, 0.5, 6.907, 57 / 64) == (0, -1, 4, 5)  # 6.016 -> top face 6
    assert cbmath.wall_hit_to_range(0.5, 0.5, 6.907, 57 / 64) == (0, -1, 6, 6)

def test_down_range_is_stable_against_float_noise_once_aligned():
    off = cbmath.level_offset(57)
    ranges = {cbmath.down_hit_to_range(3.5, 2.5, 6.907 + d, off) for d in (-1e-6, 0.0, 1e-6, -1e-4, 1e-4)}
    assert ranges == {(3, -3, 4, 5)}

def test_align_k_fixed_cases():
    # plan section 1.1, used by every suite
    cases = {6.907: 57, 6.001: 63, 0.0: 63, -3.25: 47, 312.999: 63, -0.0005: 63, 64.5: 31}
    for z, k in cases.items():
        assert cbmath.align_k(z) == k, z

def test_align_k_puts_the_floor_just_above_a_whole_y():
    rng = random.Random(20261003)
    for _ in range(10_000):
        z = rng.uniform(-5000.0, 5000.0)
        k = cbmath.align_k(z)
        assert 0 <= k < cbmath.LEVEL_STEPS and isinstance(k, int)
        y = z - cbmath.level_offset(k)
        e = y - math.floor(y)
        assert 0.00195 <= e < 0.0176, (z, k, e)
        # the nearest-rounded barrier under that floor has its top face exactly at floor(y): Steve stands on it
        assert cbmath.down_hit_to_range(0.0, 0.0, z, cbmath.level_offset(k))[3] + 1 == math.floor(y)

def test_directions_and_quaternions_never_take_the_offset():
    assert cbmath.cp_dir_to_mc(1.0, 2.0, 3.0) == (1.0, 3.0, -2.0)
    for p in [(1.0, 2.0, 3.0), (-0.3, 0.9, -0.1)]:
        assert cbmath.cp_dir_to_mc(*p) == cbmath.cp_to_mc_pos(*p, 0.0)
        assert cbmath.cp_dir_to_mc(*p) != cbmath.cp_to_mc_pos(*p, 0.5)
    assert cbmath.mc_vel_to_cp(1.0, 2.0, 3.0) == (1.0, -3.0, 2.0)
    # yaw/pitch come from cp_dir_to_mc, so they are the same whatever k is: compare with a hand-made mapping
    for q in [(0.0, 0.0, 0.0, 1.0), (0.1, -0.2, 0.3, 0.927), (-0.5, 0.5, 0.5, 0.5)]:
        f = cbmath.cp_quat_forward(*q)
        assert cbmath.cp_quat_to_mc_yaw_pitch(*q) == cbmath.mc_yaw_pitch(f[0], f[2], -f[1])

# ---- re-test 3 (plan 2026-10-03-retest3-polish section 1.7): six binds ---------------------------------------------------

G, K, U, Y, L, O = (ord(c) for c in "GKUYLO")
DEFAULTS = {"hack": G, "skills": K, "cyberware": U, "activate": Y, "arms": L, "camo": O}

def test_bind_defaults_are_g_k_u_y_l_o_and_bindable():
    assert cbmath.BIND_DEFAULTS == DEFAULTS
    assert cbmath.BIND_NAMES == ("hack", "skills", "cyberware", "activate", "arms", "camo")
    assert all(cbmath.bindable(vk) for vk in DEFAULTS.values())

def test_bindable_refuses_cyberpunks_and_forwarded_keys():
    for c in "WASDCFIMJHNBPVZREQTX123456789":
        assert not cbmath.bindable(ord(c)), c
    for vk in (0, 255, 0x1B, 0x20, 0x10, 0x11, 0xA0, 0xA3, 0x74, 0x78, 0xBF):
        assert not cbmath.bindable(vk), hex(vk)
    for vk in (G, K, L, O, U, Y, 0x30, 0x70, 0x73, 0x75, 0x77, 0x79, 0x7B):
        assert cbmath.bindable(vk), hex(vk)

def test_apply_binds_single_refusal_falls_back_to_its_own_default():
    current = {**DEFAULTS, "hack": 0x70}
    assert cbmath.apply_binds({"hack": ord("W")}, current) == (DEFAULTS, False)
    assert cbmath.apply_binds({"hack": 71.5}, current) == (DEFAULTS, False)  # not a whole number
    assert cbmath.apply_binds({"hack": "G"}, current) == (DEFAULTS, False)   # not a number
    assert cbmath.apply_binds({"hack": True}, current) == (DEFAULTS, False)  # a bool is not a key

def test_apply_binds_missing_names_keep_their_key_and_clashes_reset_all_six():
    current = {**DEFAULTS, "arms": 0x79}
    assert cbmath.apply_binds({"camo": 0x7A}, current) == ({**current, "camo": 0x7A}, False)
    assert cbmath.apply_binds({"camo": 0x79}, current) == (DEFAULTS, True)
    assert cbmath.apply_binds({"hack": L}, DEFAULTS) == (DEFAULTS, True)
    assert cbmath.apply_binds({"skills": O}, DEFAULTS) == (DEFAULTS, True)

def test_vectors_file_matches_truth():
    data = json.loads(Path("shared/vectors.json").read_text())
    for case in data["quat"]:
        yaw, pitch = cbmath.cp_quat_to_mc_yaw_pitch(*case["q"])
        assert math.isclose(yaw, case["yaw"], abs_tol=1e-6) and math.isclose(pitch, case["pitch"], abs_tol=1e-6)

    # re-test 2: down and wall at k 0, 32, 57 and 63, plus the noise cases at k 57
    assert {c["k"] for c in data["down"]} == {0, 32, 57, 63} and {c["k"] for c in data["wall"]} == {0, 32, 57, 63}
    for case in data["down"]:
        assert list(cbmath.down_hit_to_range(*case["hit"], cbmath.level_offset(case["k"]))) == case["range"]
    for case in data["wall"]:
        assert list(cbmath.wall_hit_to_range(*case["hit"], cbmath.level_offset(case["k"]))) == case["range"]
    noise = [c for c in data["down"] if c["k"] == 57 and c["hit"][:2] == [3.5, 2.5]]
    assert len(noise) == 3 and len({tuple(c["range"]) for c in noise}) == 1
    assert any(c["hit"] == [-3.2, -7.9, 12.25] and c["k"] == 0 and c["range"][3] == 11 for c in data["down"])

    assert len(data["mc_pos"]) >= 12 and len(data["mc_block"]) >= 12
    assert {c["k"] for c in data["mc_pos"]} == {0, 57} and {c["k"] for c in data["mc_block"]} == {0, 57}
    for case in data["mc_pos"]:
        off = cbmath.level_offset(case["k"])
        assert list(cbmath.mc_to_cp_pos(*case["mc"], off)) == case["cp"]
        assert all(math.isclose(a, b, abs_tol=1e-9) for a, b in zip(cbmath.cp_to_mc_pos(*case["cp"], off), case["mc"]))
    for case in data["mc_block"]:
        lo, hi = cbmath.mc_block_to_cp_aabb(*case["block"], cbmath.level_offset(case["k"]))
        assert [list(lo), list(hi)] == [case["min"], case["max"]]

    assert len(data["align"]) == 7
    for case in data["align"]:
        assert cbmath.align_k(case["z"]) == case["k"]

    # re-test 3 (plan section 1.7): the bindable set and the six-key clash rule
    for case in data["binds"]:
        assert cbmath.bindable(case["vk"]) == case["bindable"], case
    assert len(data["binds_apply"]) >= 12 + 6 * 7
    for case in data["binds_apply"]:
        assert cbmath.apply_binds(case["given"], case["current"]) == (case["expect"], case["clash"]), case
        assert set(case["expect"]) == set(cbmath.BIND_NAMES) and set(case["current"]) == set(cbmath.BIND_NAMES)

    assert len(data["mc_yaw"]) >= 6 and len(data["mc_vel"]) >= 6
    for case in data["mc_yaw"]:
        assert cbmath.mc_yaw_to_cp_yaw(case["mc"]) == case["cp"]
    for case in data["mc_vel"]:
        assert list(cbmath.mc_vel_to_cp(*case["mc"])) == case["cp"]


# ---- the Atlas survey (explored map): 2-m cells -------------------------------------------------------------------
def test_survey_cell_uses_the_minecraft_axes_and_the_top_face_rule():
    assert cbmath.survey_cell(10.4, 20.6, 5.0) == (5, -11, 5)       # Minecraft x 10.4, z -20.6
    assert cbmath.survey_cell(2.0, -2.0, 40.5) == (1, 1, 41)        # a cell edge belongs to the cell above it; ties go up
    assert cbmath.survey_cell(1.999, -1.999, 40.49) == (0, 0, 40)
    assert cbmath.survey_cell(-0.5, 0.5, -3.5) == (-1, -1, -3)
    # the surface height is the same nearest whole y as the floor range of a down hit
    for x, y, z in [(10.4, 20.6, 5.0), (-3.2, -7.9, 12.25), (7.0, 7.0, 7.999)]:
        for k in (0, 57):
            off = cbmath.level_offset(k)
            assert cbmath.survey_cell(x, y, z, off)[2] == cbmath.down_hit_to_range(x, y, z, off)[3] + 1


def test_survey_column_is_the_centre_of_its_cell():
    for cx, cz in [(0, 0), (-1, -1), (5, -3), (-623, -407), (1999, 1999)]:
        x, y = cbmath.survey_column(cx, cz)
        assert cbmath.survey_cell(x, y, 0.0)[:2] == (cx, cz)
        assert cbmath.survey_cell(x - 0.99, y + 0.99, 0.0)[:2] == (cx, cz) and cbmath.survey_cell(x + 0.99, y - 0.99, 0.0)[:2] == (cx, cz)


def test_survey_vectors_match_truth():
    data = json.loads(Path("shared/vectors.json").read_text())
    assert {c["k"] for c in data["survey"]} == {0, 57} and len(data["survey"]) >= 20 and len(data["survey_column"]) >= 5
    for case in data["survey"]:
        assert list(cbmath.survey_cell(*case["hit"], cbmath.level_offset(case["k"]))) == case["cell"]
    for case in data["survey_column"]:
        assert list(cbmath.survey_column(*case["cell"])) == case["cp"]


# ---- Phase 3: material codes and the clock (megaplan 1.9 `ground.m`, 3-E1) -------------------------------------------------
def test_material_codes_are_the_plan_table():
    assert cbmath.SURFACE_MATERIALS == ("unknown", "concrete_light", "concrete_dark", "asphalt", "sidewalk", "glass", "metal", "brick", "tile",
                                        "ground", "water")
    assert cbmath.DYES == ("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue",
                           "brown", "green", "red", "black")
    assert cbmath.NEON_FIRST == 11 and cbmath.MATERIAL_MAX_CODE == 26
    assert cbmath.material_block(0) is None and cbmath.material_block(27) is None and cbmath.material_block(-1) is None
    assert cbmath.material_block(1) == "cyberblock:nc_concrete_light" and cbmath.material_block(10) == "cyberblock:nc_water"
    assert cbmath.material_block(11) == "cyberblock:nc_neon_white" and cbmath.material_block(26) == "cyberblock:nc_neon_black"
    assert cbmath.material_block(14) == "cyberblock:nc_neon_light_blue" and cbmath.material_block(19) == "cyberblock:nc_neon_light_gray"


def test_every_code_has_a_matching_rule_and_the_order_covers_each_rule_once():
    assert set(cbmath.MATERIAL_MATCH) == set(range(1, 27))
    rules = [r for r in cbmath.MATERIAL_ORDER if r != "neon"]
    assert sorted(rules) == list(range(1, 27)) and cbmath.MATERIAL_ORDER.count("neon") == 1


def test_material_names_map_by_substring_first_rule_wins():
    for name, code in (("Glass_Window_01", 5), ("neon_light_blue_tube", 14), ("neon_blue_tube", 22), ("neon_sign", 11), ("brick_wall", 7),
                       ("asphalt", 3), ("sidewalk_a", 4), ("concrete_dark_01", 2), ("terrain", 9), ("water", 10), ("environment_probe", 0), ("", 0)):
        assert cbmath.material_code(name) == code, name


def test_materials_vectors_match_truth():
    m = json.loads(Path("shared/vectors.json").read_text())["materials"]
    assert [c["code"] for c in m["codes"]] == list(range(27))
    assert all(c["block"] == cbmath.material_block(c["code"]) for c in m["codes"])
    assert m["order"] == list(cbmath.MATERIAL_ORDER) and m["neon_any"] == list(cbmath.NEON_ANY) and m["neon_any_code"] == 11
    assert len(m["cases"]) >= 30 and all(cbmath.material_code(c["name"]) == c["code"] for c in m["cases"])
    assert {c["code"] for c in m["cases"]} >= set(range(1, 11)) | {0, 11, 14, 19, 22, 25}  # every surface code and several neon dyes
    assert set(m) == {"codes", "order", "neon_any", "neon_any_code", "cases"}  # only the name table: 3D's cb_tests reads it key by key


def test_clock_vectors_match_truth_and_live_outside_materials():
    clock = json.loads(Path("shared/vectors.json").read_text())["clock"]
    assert len(clock) >= 8 and all(c["ticks"] == cbmath.env_ticks(*c["time"]) for c in clock)


def test_env_ticks_follow_the_plan_formula():
    assert [cbmath.env_ticks(h, 0) for h in (6, 12, 18, 0)] == [0, 6000, 12000, 18000]
    assert cbmath.env_ticks(22, 30) == 16500 and cbmath.env_ticks(23, 59) == 17983 and cbmath.env_ticks(5, 59) == 23983
    assert all(0 <= cbmath.env_ticks(h, m) < 24000 for h in range(24) for m in range(60))
