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


# ---- the Atlas survey (explored map): 2-m cells of the highest Night City surface -----------------------------------

SURVEY_CELL = 2
"""Metres (Minecraft blocks) per side of an Atlas survey cell."""


def survey_cell(x: float, y: float, z: float, off: float = 0.0) -> tuple[int, int, int]:
    """A survey hit (the highest Night City surface of a column, Cyberpunk space) -> (cx, cz, h): its 2-m Minecraft cell
    cx = floor(x / 2), cz = floor(-y / 2), and the surface's Minecraft y h = floor(z - off + 0.5), the nearest whole y
    (the top-face rule of down_hit_to_range)."""
    mx, my, mz = cp_to_mc_pos(x, y, z, off)
    return (math.floor(mx / SURVEY_CELL), math.floor(mz / SURVEY_CELL), math.floor(my + 0.5))


def survey_column(cx: int, cz: int) -> tuple[float, float]:
    """The Cyberpunk column (x, y) at the centre of survey cell (cx, cz): Minecraft (2cx + 1, 2cz + 1) -> (2cx + 1, -(2cz + 1))."""
    x, y, _ = mc_to_cp_pos(SURVEY_CELL * cx + SURVEY_CELL / 2, 0.0, SURVEY_CELL * cz + SURVEY_CELL / 2)
    return (x, y)


# ---- the six rebindable keys (plan 2026-10-03-retest3-polish section 1.7) -------------------------------------------
# Not a conversion, but a rule the DLL (keys::bindable, keys::apply_binds) and Minecraft (VirtualKeys) must share: this is
# its reference, and shared/vectors.json `binds` and `binds_apply` pin both mirrors to it.

BIND_NAMES = ("hack", "skills", "cyberware", "activate", "arms", "camo")
"""The `binds.keys` names, in the wire order."""
BIND_DEFAULTS = {"hack": 0x47, "skills": 0x4B, "cyberware": 0x55, "activate": 0x59, "arms": 0x4C, "camo": 0x4F}
"""G, K, U, Y, L, O as Windows virtual-key codes."""
_BIND_REFUSED = ({ord(c) for c in "EQTX123456789"} | {0xBF, 0x74, 0x78}  # forwarded E Q T X 1-9 / F5, and F9
                 | {0x1B, 0x20, 0x10, 0xA0, 0xA1, 0x11, 0xA2, 0xA3}         # Esc, Space, Shift, Ctrl
                 | {ord(c) for c in "WASDCFIMJHNBPVZR"})                     # Cyberpunk's own on-foot letters


def bindable(vk: int) -> bool:
    """May `vk` be bound to an action: 1..254 and not a refused key (keys::bindable)."""
    return 1 <= vk <= 254 and vk not in _BIND_REFUSED


def apply_binds(given: dict, current: dict) -> tuple[dict, bool]:
    """A `binds` message's keys applied to `current` (keys::apply_binds, VirtualKeys): a missing name keeps its current
    key; a given key that is not a whole number bindable() takes falls back to its own default; then, if any two of the
    six resolved keys are equal, all six go back to the defaults. Returns (binds, clash)."""
    out = dict(current)
    for name in BIND_NAMES:
        if name not in given:
            continue
        v = given[name]
        whole = isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) and v == math.floor(v)
        out[name] = int(v) if whole and bindable(int(v)) else BIND_DEFAULTS[name]
    keys = [out[n] for n in BIND_NAMES]
    clash = len(set(keys)) != len(keys)
    return (dict(BIND_DEFAULTS) if clash else out), clash


# ---- `prefs`, the third-person camera preferences (megaplan 2026-10-04 section 1.9, Phase Q) --------------------------
# The DLL's world::parse_prefs is the receiver; shared/vectors.json `prefs_apply` pins it to this reference.

PREFS_DEFAULTS = {"dist": 3.2, "shoulder": 0.45, "fov": 0.0, "recenter": True, "vdist": 0.0, "stepup": True, "debug": False}
"""The DLL's defaults (Q-D1), held before any `prefs`, after every `hello` and after a link drop. `debug` is Minecraft's
`/cyberblock debug view` flag: the DLL adds `cam.vdbg` while it is true."""
_PREFS_NUMBERS = {"dist": (1.5, 8.0, False), "shoulder": (-1.0, 1.0, False), "fov": (50.0, 110.0, True), "vdist": (3.0, 14.0, True)}


def apply_prefs(given: dict, current: dict) -> tuple[dict, int]:
    """A `prefs` message applied to `current` (world::parse_prefs): a missing field keeps its value; a number is clamped
    into its range (`fov` and `vdist`: 0 or below is 0, the game's own, and above 0 is clamped); a field of the wrong type
    keeps its value and is counted; other fields are ignored. Returns (prefs, bad)."""
    out, bad = dict(current), 0
    for key, (lo, hi, zero_off) in _PREFS_NUMBERS.items():
        if key in given:
            v = given[key]
            if isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v):
                out[key] = 0.0 if zero_off and v <= 0 else min(max(float(v), lo), hi)
            else:
                bad += 1
    for key in ("recenter", "stepup", "debug"):
        if key in given:
            if isinstance(given[key], bool):
                out[key] = given[key]
            else:
                bad += 1
    return out, bad


# ---- Phase 3 (megaplan sections 1.9 and 7): replica materials and the clock -----------------------------------------------
# `ground.m` carries one material code per range. The names are `TraceResult.material` or, when that is None, the hit mesh's
# `meshAppearance` (Phase 0 amendment, section 1.8 item 1). The real names are only known from the CET lines B1-B5, so the
# substrings below are a WORKING table: 3S's scripts send raw names, 3D's DLL table and 3A's `material_palette` heuristics map
# them with exactly this rule, and `shared/vectors.json` `materials` pins every copy. A change goes through the integrator.
DYES = ("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown",
        "green", "red", "black")
"""The vanilla dye order; `nc_neon_<dye>` is code 11 + its index."""
NEON_FIRST = 11
SURFACE_MATERIALS = ("unknown", "concrete_light", "concrete_dark", "asphalt", "sidewalk", "glass", "metal", "brick", "tile", "ground", "water")
"""Codes 0..10 in order; codes 11..26 are the sixteen neon blocks."""
MATERIAL_MAX_CODE = NEON_FIRST + len(DYES) - 1
MATERIAL_MATCH = {  # code -> lower-case substrings of a material or mesh appearance name that select it
    1: ("concrete_light", "concrete_white", "plaster", "stucco", "limestone"),
    2: ("concrete_dark", "concrete_black", "concrete_wet", "rubble", "stone_dark"),
    3: ("asphalt", "road_", "tarmac", "street_surface"),
    4: ("sidewalk", "pavement", "curb", "kerb", "paving"),
    5: ("glass", "window", "windshield", "shopfront"),
    6: ("metal", "steel", "iron_", "aluminium", "aluminum", "chrome", "scaffold", "grate"),
    7: ("brick", "masonry"),
    8: ("tile", "ceramic", "marble"),
    9: ("terrain", "dirt", "sand", "gravel", "grass", "soil", "rock_", "mud", "badlands", "ground"),
    10: ("water", "puddle", "pool", "sewage"),
    **{NEON_FIRST + i: (f"neon_{d}", f"emissive_{d}", f"led_{d}") for i, d in enumerate(DYES)},
}
NEON_ANY = ("neon", "emissive", "hologram", "led_", "light_strip")
"""A neon-like name that names no dye is the white neon block (code 11)."""
MATERIAL_ORDER = (*range(NEON_FIRST, MATERIAL_MAX_CODE + 1), "neon", 5, 10, 6, 7, 8, 3, 4, 2, 1, 9)
"""The order the rules are tried in, first match wins: a dye neon, any other neon, glass, water, metal, brick, tile, asphalt,
sidewalk, dark concrete, light concrete, ground. A name that matches nothing is 0 (the scripts log it once)."""


def material_block(code: int) -> str | None:
    """The block of a code: `cyberblock:nc_<material>` or `cyberblock:nc_neon_<dye>`; None for 0 (unknown: the palette decides)."""
    if code == 0 or not 0 <= code <= MATERIAL_MAX_CODE:
        return None
    return f"cyberblock:nc_{SURFACE_MATERIALS[code]}" if code < NEON_FIRST else f"cyberblock:nc_neon_{DYES[code - NEON_FIRST]}"


def material_code(name: str) -> int:
    """The code of a material or mesh appearance name (case-insensitive substring rules, see MATERIAL_ORDER)."""
    low = name.lower()
    for rule in MATERIAL_ORDER:
        needles = NEON_ANY if rule == "neon" else MATERIAL_MATCH[rule]
        if any(n in low for n in needles):
            return NEON_FIRST if rule == "neon" else rule
    return 0


def env_ticks(hour: int, minute: int) -> int:
    """Minecraft day time of Cyberpunk's clock (megaplan 3-E1): ((h*60 + m) / 1440 * 24000 + 18000) mod 24000, so 06:00 is
    tick 0 (sunrise), 12:00 tick 6000 (noon), 18:00 tick 12000 (sunset) and 00:00 tick 18000 (midnight)."""
    return round(((hour * 60 + minute) / 1440 * 24000 + 18000) % 24000) % 24000
