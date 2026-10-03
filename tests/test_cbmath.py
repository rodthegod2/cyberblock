import json, math
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

def test_vectors_file_matches_truth():
    data = json.loads(Path("shared/vectors.json").read_text())
    for case in data["quat"]:
        yaw, pitch = cbmath.cp_quat_to_mc_yaw_pitch(*case["q"])
        assert math.isclose(yaw, case["yaw"], abs_tol=1e-6) and math.isclose(pitch, case["pitch"], abs_tol=1e-6)
    for case in data["down"]:
        assert list(cbmath.down_hit_to_range(*case["hit"])) == case["range"]
    for case in data["wall"]:
        assert list(cbmath.wall_hit_to_range(*case["hit"])) == case["range"]

    assert len(data["mc_pos"]) >= 6 and len(data["mc_block"]) >= 6
    for case in data["mc_pos"]:
        assert list(cbmath.mc_to_cp_pos(*case["mc"])) == case["cp"]
        assert list(cbmath.cp_to_mc_pos(*case["cp"])) == case["mc"]
    for case in data["mc_block"]:
        lo, hi = cbmath.mc_block_to_cp_aabb(*case["block"])
        assert [list(lo), list(hi)] == [case["min"], case["max"]]
