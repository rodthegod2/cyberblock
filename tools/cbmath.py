"""Cyberpunk -> Minecraft conversions. The single source of truth; cp/plugin/src/cbmath.hpp mirrors it."""
import math


def cp_to_mc_pos(x: float, y: float, z: float) -> tuple[float, float, float]:
    return (x, z, -y)


def mc_to_cp_pos(x: float, y: float, z: float) -> tuple[float, float, float]:
    """Inverse of cp_to_mc_pos: Minecraft (x, y, z) -> Cyberpunk (x, -z, y)."""
    return (x, -z, y)


def mc_vel_to_cp(vx: float, vy: float, vz: float) -> tuple[float, float, float]:
    """Minecraft velocity or impulse (m/s) -> Cyberpunk (x, -z, y): the position mapping with no offset, under its own
    name so that callers say what they mean."""
    return (vx, -vz, vy)


def mc_yaw_to_cp_yaw(mc_yaw: float) -> float:
    """Minecraft yaw (0 = facing +Z, degrees) -> Cyberpunk yaw (0 = facing +Y, counter-clockwise from above):
    a = 180 - m, normalised to (-180, 180]. Its own inverse, as is cp_yaw_to_mc_yaw in the DLL."""
    a = math.fmod(180.0 - mc_yaw, 360.0)
    if a > 180.0:
        a -= 360.0
    elif a <= -180.0:
        a += 360.0
    return a


def mc_block_to_cp_aabb(bx: int, by: int, bz: int) -> tuple[tuple[int, int, int], tuple[int, int, int]]:
    """Cyberpunk AABB (min, max) of the Minecraft block (bx, by, bz): min (bx, -(bz+1), by), max (bx+1, -bz, by+1)."""
    return (bx, -(bz + 1), by), (bx + 1, -bz, by + 1)


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
