"""Cyberpunk <-> Minecraft conversions. The single source of truth; cp/plugin/src/cbmath.hpp mirrors it.

Re-test 2 (plan 2026-10-03-retest2 section 1.1): a world has a ground alignment k (0..63) and positions shift by
off = k / 64 m: Minecraft y = Cyberpunk z - off. Positions take `off`; directions, velocities and impulses never do.
`off` defaults to 0.0 here only (the C++ mirror makes it a required parameter)."""
import math

LEVEL_STEPS = 64
"""The alignment k is a whole number of 1/64 m steps, 0..63: exact in float32, double and JSON."""


def level_offset(k: int) -> float:
    return k / LEVEL_STEPS


def align_k(z: float) -> int:
    """The k that puts a floor at Cyberpunk z (V's feet) just above a whole Minecraft y: the aligned floor maps to
    N + e with e in [1.95 mm, 17.6 mm), so the nearest-rounded barrier top is exactly N and Steve stands on it, never in it.
    k = floorMod(floor((z - floor z) * 64 - 0.125), 64). Minecraft chooses k (GroundAlign.kOf); the DLL never does."""
    return math.floor((z - math.floor(z)) * LEVEL_STEPS - 0.125) % LEVEL_STEPS


def cp_to_mc_pos(x: float, y: float, z: float, off: float = 0.0) -> tuple[float, float, float]:
    """Cyberpunk (x, y, z) -> Minecraft (x, z - off, -y)."""
    return (x, z - off, -y)


def mc_to_cp_pos(x: float, y: float, z: float, off: float = 0.0) -> tuple[float, float, float]:
    """Inverse of cp_to_mc_pos: Minecraft (x, y, z) -> Cyberpunk (x, -z, y + off)."""
    return (x, -z, y + off)


def cp_dir_to_mc(x: float, y: float, z: float) -> tuple[float, float, float]:
    """A Cyberpunk direction (camera forward, facing) -> Minecraft (x, z, -y): never offset."""
    return (x, z, -y)


def mc_vel_to_cp(vx: float, vy: float, vz: float) -> tuple[float, float, float]:
    """Minecraft velocity or impulse (m/s) -> Cyberpunk (vx, -vz, vy): never offset."""
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


def mc_block_to_cp_aabb(bx: int, by: int, bz: int, off: float = 0.0) -> tuple[tuple[float, float, float],
                                                                               tuple[float, float, float]]:
    """Cyberpunk AABB (min, max) of the Minecraft block (bx, by, bz): min (bx, -(bz+1), by + off), max
    (bx+1, -bz, by+1 + off)."""
    return (bx, -(bz + 1), by + off), (bx + 1, -bz, by + 1 + off)


def cp_quat_forward(i: float, j: float, k: float, r: float) -> tuple[float, float, float]:
    """Cyberpunk camera forward: the quaternion (i, j, k, r) applied to local +Y."""
    return (2 * (i * j - r * k), 1 - 2 * (i * i + k * k), 2 * (j * k + r * i))


def mc_yaw_pitch(dx: float, dy: float, dz: float) -> tuple[float, float]:
    n = math.sqrt(dx * dx + dy * dy + dz * dz)
    dx, dy, dz = dx / n, dy / n, dz / n
    return math.degrees(math.atan2(-dx, dz)), math.degrees(math.asin(max(-1.0, min(1.0, -dy))))


def cp_quat_to_mc_yaw_pitch(i: float, j: float, k: float, r: float) -> tuple[float, float]:
    return mc_yaw_pitch(*cp_dir_to_mc(*cp_quat_forward(i, j, k, r)))


def vfov_from_hfov(hfov_deg: float, aspect: float) -> float:
    return math.degrees(2 * math.atan(math.tan(math.radians(hfov_deg) / 2) / aspect))


def down_hit_to_range(x: float, y: float, z: float, off: float = 0.0) -> tuple[int, int, int, int]:
    """A floor hit at Cyberpunk z -> the two solid cells under it. The barrier's top face is the nearest whole Minecraft
    y to z - off (ties go up), so after alignment it is exactly the floor Steve stands on."""
    top = math.floor(z - off + 0.5) - 1
    return (math.floor(x), math.floor(-y), top - 1, top)


def wall_hit_to_range(x: float, y: float, z: float, off: float = 0.0) -> tuple[int, int, int, int]:
    b = math.floor(z - off)
    return (math.floor(x), math.floor(-y), b, b)
