import json, math
from pathlib import Path
from tools import cbmath

def test_position_mapping():
    assert cbmath.cp_to_mc_pos(1.0, 2.0, 3.0) == (1.0, 3.0, -2.0)

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
