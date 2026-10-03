"""Cyberpunk -> Minecraft conversions. The single source of truth; cp/plugin/src/cbmath.hpp mirrors it."""
import math


def cp_to_mc_pos(x: float, y: float, z: float) -> tuple[float, float, float]:
    return (x, z, -y)


def cp_quat_forward(i: float, j: float, k: float, r: float) -> tuple[float, float, float]:
    """Cyberpunk camera forward: the quaternion (i, j, k, r) applied to local +Y."""
    return (2 * (i * j - r * k), 1 - 2 * (i * i + k * k), 2 * (j * k + r * i))


def mc_yaw_pitch(dx: float, dy: float, dz: float) -> tuple[float, float]:
    n = math.sqrt(dx * dx + dy * dy + dz * dz)
    dx, dy, dz = dx / n, dy / n, dz / n
    return math.degrees(math.atan2(-dx, dz)), math.degrees(math.asin(max(-1.0, min(1.0, -dy))))


def cp_quat_to_mc_yaw_pitch(i: float, j: float, k: float, r: float) -> tuple[float, float]:
    return mc_yaw_pitch(*cp_to_mc_pos(*cp_quat_forward(i, j, k, r)))


def vfov_from_hfov(hfov_deg: float, aspect: float) -> float:
    return math.degrees(2 * math.atan(math.tan(math.radians(hfov_deg) / 2) / aspect))


def down_hit_to_range(x: float, y: float, z: float) -> tuple[int, int, int, int]:
    top = math.ceil(z) - 1
    return (math.floor(x), math.floor(-y), top - 1, top)


def wall_hit_to_range(x: float, y: float, z: float) -> tuple[int, int, int, int]:
    b = math.floor(z)
    return (math.floor(x), math.floor(-y), b, b)
