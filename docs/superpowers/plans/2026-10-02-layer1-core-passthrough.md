# Cyberblock Layer 1 (Core Passthrough) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Walk around Night City as Steve: real Minecraft 26.3 drawn into Cyberpunk 2077 2.31 with correct
camera, depth and Night City collision; F9 and a watchdog restore normal V; clean install/remove.

**Architecture:** A Fabric mod (forked from universal-modder's Minecraft × GTA V example) renders from a
host camera and exports frames to shared memory. `Cyberblock.dll` (a RED4ext plugin that also registers as
a ReShade add-on) sends Cyberpunk's camera/probes over a local WebSocket and composites Minecraft's frames
against Cyberpunk's depth. redscript handles V's state, probes and safety. Spec:
`docs/superpowers/specs/2026-10-02-minecraft-cyberpunk-passthrough-design.md`.

**Tech Stack:** Java 25 + Fabric (Loom 1.18, Minecraft 26.3, Java-WebSocket 1.6.0) · C++20 MSVC + CMake +
RED4ext.SDK + ReShade 6.8.0 add-on API + nlohmann/json · ReShade FX · redscript (+ Codeware) · Python 3.12
via uv (pytest, websockets, numpy, pillow).

---

## 0. Ground rules for every track

- **Reference code** lives at `third_party/universal-modder/examples/minecraft-gta5-passthrough/` (pinned at
  commit `15d6f9d`). "Port X" means: copy X, rename `passthrough`→`cyberblock`, `dev.rehan.passthrough`→
  `dev.cyberblock`, `MCPassthrough`→`Cyberblock`, `Local\MCPassthroughFrame`→`Local\CyberblockFrame`,
  magic `0x5450434D` ("MCPT") → `0x52464243` ("CBFR"), port `25599`→`25600`, then make the stated changes.
  Keep the MIT header credit: `// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough`.
- Each track works in **its own git worktree/branch** and only touches its own directories (table below).
  The integrator (main session) merges.
- Never copy game files (Minecraft or Cyberpunk assets) into the repo.
- Game install: `D:\SteamLibrary\steamapps\common\Cyberpunk 2077` (referred to as `<CP>`).
- Commit after every task with a `feat:`/`test:`/`chore:` message ending in
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

| Track | Owns | Needs toolchain |
|---|---|---|
| A tools & shared | `shared/`, `tools/`, `tests/`, root files, `third_party/` | uv/Python |
| B Minecraft mod | `mc/` | JDK 25 |
| C DLL core | `cp/plugin/` except `src/compositor.*` | VS 2022 Build Tools, CMake |
| D compositor | `cp/plugin/src/compositor.*`, `cp/shaders/` | VS 2022 Build Tools, CMake |
| E redscript | `cp/scripts/` | none (compile-check with `<CP>\engine\tools\scc.exe`) |
| F integration (main session) | — | everything + the game |

Order: A1 first (repo skeleton + submodules, ~15 min). Then B, C, E start. D starts after C1. F after all.

---

## 1. Shared contracts (all tracks code against these; change only via the integrator)

### 1.1 Coordinates (truth = `tools/cbmath.py`; C++ mirrors it; vectors in `shared/vectors.json`)
- Position: Cyberpunk `(x, y, z)` (Z-up, metres) → Minecraft `(x, z, -y)`.
- Direction: same mapping. Minecraft look vector for yaw/pitch (degrees):
  `(-sin(yaw)·cos(pitch), -sin(pitch), cos(yaw)·cos(pitch))` ⇒ `yaw = atan2(-dx, dz)`, `pitch = asin(-dy)`.
- Cyberpunk camera forward = quaternion `(i, j, k, r)` applied to local `+Y`:
  `f = (2(ij − rk), 1 − 2(i² + k²), 2(jk + ri))`.
- Roll: 0 in layer 1.
- Vertical fov from horizontal: `vfov = 2·atan(tan(hfov/2) / aspect)`. Whether Cyberpunk reports horizontal
  or vertical fov is decided in task F2; the DLL exposes the choice as `fovIsHorizontal` (default true).
- Block cell of a point: `floor` on each Minecraft axis.
- Down-ray hit at Cyberpunk `(x, y, z)` → ground range for column `(floor(x), floor(-y))`:
  `yTop = ceil(z) − 1`, `yBottom = yTop − 1`.
- Wall-ray hit at Cyberpunk `(x, y, z)` → one-block range at `(floor(x), floor(-y))`, `yBottom = yTop = floor(z)`.

### 1.2 Wire protocol v1 — `shared/protocol.md` (WebSocket on `127.0.0.1:25600`, Minecraft is the server)
Cyberpunk → Minecraft (all values already Minecraft convention):
| `t` | fields | when |
|---|---|---|
| `hello` | `proto:1, game:"cp2077", build:"2.31"` | on connect |
| `cam` | `f` frame (long), `p:[x,y,z]` camera, `r:[yaw,pitch,roll]` deg, `fov` vertical deg, `fp:true`, `pl:[x,y,z]` feet, `h` body yaw deg | every Cyberpunk frame while active |
| `ground` | `c:[x,z,yBottom,yTop, …]` inclusive | probe deltas (new solid ranges) |
| `unground` | `c:[x,z,yBottom,yTop, …]` | probe deltas (ranges no longer solid / out of radius) |
| `clear` | — | on activate, deactivate, teleport > 64 m |
| `state` | `active:bool, vehicle:bool` | on change |
| `view` | `w, h` | on connect and when Cyberpunk's backbuffer size changes |
| `hud` | `hidden:bool` | on change (hidden in vehicles) |
| `key` | `k` ∈ `use, attack, pick, inventory, drop, swap, chat, command, escape`, `down:bool` | input |
| `slot` | `n` 0–8 | number keys |
| `scroll` | `d` int (wheel notches, +1 = up) | wheel |
| `cursor` | `x, y` in 0..1 of the window | only while `status.screen` |
| `click` | `b` 0 left, 1 right, 2 middle, `down:bool` | only while `status.screen` |

Minecraft → Cyberpunk:
| `t` | fields | when |
|---|---|---|
| `hello` | `proto:1, mod:"cyberblock", mc:"26.3"` | on connect |
| `status` | `hp, maxHp, food, gm` (`survival`/`creative`/`adventure`/`spectator`), `screen:bool` | 10 Hz and on change |

Unknown `t` values are ignored by both sides (forward compatibility for layers 2–6).

### 1.3 Shared memory `Local\CyberblockFrame`
Exactly the reference `FrameExporter` layout (see its Javadoc) with magic `0x52464243` ("CBFR"). The
reference flags (zero-to-one depth, bottom-up rows, reversed-Z) are kept.

### 1.4 DLL ↔ redscript natives (`cp/scripts/Cyberblock/Natives.reds` ↔ `cp/plugin/src/natives.cpp`)
```swift
// Natives.reds — declarations only; implemented by Cyberblock.dll
public native func Cyberblock_Version() -> String;              // "0.1.0"
public native func Cyberblock_IsLinked() -> Bool;              // WebSocket open and hello exchanged
public native func Cyberblock_SecondsSinceFrame() -> Float;    // since the last shared-memory frame or status; 999 if never
public native func Cyberblock_ScreenOpen() -> Bool;            // last status.screen
public native func Cyberblock_ConsumeToggle() -> Bool;         // true once per F9 press (caught by the DLL's window hook)
public native func Cyberblock_SetActive(active: Bool, inVehicle: Bool) -> Void;  // sends state/hud/clear, gates cam + compositor + input
public native func Cyberblock_SubmitProbes(center: Vector4, downHits: array<Vector4>, wallHits: array<Vector4>, radius: Float) -> Void;
                                                               // Cyberpunk-space hits; DLL converts, diffs, sends ground/unground
public native func Cyberblock_Log(msg: String) -> Void;        // to red4ext/logs/Cyberblock.log
```
The DLL publishes `cam` itself every frame (RED4ext Running-state `OnUpdate`), so redscript never sends
per-frame data.

---

## Track A — tools & shared

### Task A1: Repository skeleton and pinned dependencies
**Files:** Create `.gitignore`, `README.md`, `LICENSE` (MIT, 2026, rodthegod2), `NOTICE.md`, `.gitattributes`;
submodules under `third_party/`.

- [ ] **Step 1: Create root files**

`.gitignore`:
```
/out/
/build/
/cp/plugin/build/
/mc/.gradle/
/mc/build/
/mc/run/
/.venv/
__pycache__/
*.log
```
`.gitattributes`:
```
* text=auto eol=lf
*.bat text eol=crlf
*.ps1 text eol=crlf
*.png binary
*.jar binary
```
`NOTICE.md`:
```markdown
# Notices
- Minecraft mod, compositor, WebSocket client and shader are based on universal-modder (MIT) by Rehan,
  examples/minecraft-gta5-passthrough — https://github.com/rehan-remade/universal-modder (commit 15d6f9d).
- RED4ext.SDK (MIT), ReShade add-on headers (BSD-3-Clause), nlohmann/json (MIT).
- No Minecraft or Cyberpunk 2077 game files are included or redistributed.
```
`README.md`: one paragraph on what Cyberblock is, prerequisites (spec §2), and the commands
`uv run tools/install.py`, `uv run tools/play.py`, `uv run tools/install.py --remove`.

- [ ] **Step 2: Add submodules (shallow, pinned)**
```bash
git -c core.longpaths=true submodule add https://github.com/rehan-remade/universal-modder third_party/universal-modder
git -C third_party/universal-modder checkout 15d6f9d5fbd32de9b1884f29ddec3be9133bd912
git submodule add https://github.com/WopsS/RED4ext.SDK third_party/RED4ext.SDK
git submodule add https://github.com/crosire/reshade third_party/reshade
git -C third_party/reshade checkout v6.8.0
git config -f .gitmodules submodule.third_party/reshade.shallow true
```
Expected: three populated folders; `git submodule status` lists the pinned SHAs.

- [ ] **Step 3: Python project**

`pyproject.toml`:
```toml
[project]
name = "cyberblock-tools"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = ["websockets>=13", "numpy>=1.26", "pillow>=10"]

[dependency-groups]
dev = ["pytest>=8"]

[tool.pytest.ini_options]
testpaths = ["tests"]
```
Run: `uv sync` → Expected: `.venv` created, no errors.

- [ ] **Step 4: Commit** `chore: repo skeleton, notices, pinned third-party submodules`

### Task A2: Coordinate truth + test vectors
**Files:** Create `tools/cbmath.py`, `tools/gen_vectors.py`, `shared/vectors.json`, `tests/test_cbmath.py`.

- [ ] **Step 1: Write the failing test** `tests/test_cbmath.py`
```python
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
```
- [ ] **Step 2: Run** `uv run pytest tests/test_cbmath.py -v` → Expected: FAIL (`No module named tools.cbmath`).
- [ ] **Step 3: Implement** `tools/__init__.py` (empty) and `tools/cbmath.py`:
```python
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
```
- [ ] **Step 4: Generator** `tools/gen_vectors.py` writes `shared/vectors.json` with 12 `quat` cases (identity;
  yaw steps of 30° about +Z; pitch ±45°, ±89° about +X; one combined), 6 `down` and 6 `wall` cases including
  negative coordinates and exact-integer heights:
```python
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
out = {
    "quat": [{"q": list(q), "yaw": y, "pitch": p} for q in qs for y, p in [cbmath.cp_quat_to_mc_yaw_pitch(*q)]],
    "down": [{"hit": list(h), "range": list(cbmath.down_hit_to_range(*h))} for h in hits],
    "wall": [{"hit": list(h), "range": list(cbmath.wall_hit_to_range(*h))} for h in hits],
}
Path("shared/vectors.json").write_text(json.dumps(out, indent=1))
print(f"wrote {len(out['quat'])} quat, {len(out['down'])} down, {len(out['wall'])} wall cases")
```
Run: `uv run python -m tools.gen_vectors` then `uv run pytest -v` → Expected: all PASS.
- [ ] **Step 5: Commit** `feat(shared): coordinate truth and test vectors`

### Task A3: Protocol document
**Files:** Create `shared/protocol.md` — copy §1.2 and §1.3 of this plan verbatim plus one JSON example per
message type. Commit `docs(shared): wire protocol v1`.

### Task A4: Fake host oracle
**Files:** Create `tools/fakehost.py` (port `host/fakehost.py` from the reference), `tools/shm.py`, `tests/test_shm.py`.
- [ ] `tools/shm.py`: `read_latest(name="Local\\CyberblockFrame") -> dict | None` opening the mapping read-only
  via `mmap.mmap(-1, size, tagname=name.split("\\")[-1], access=mmap.ACCESS_READ)` (Windows `Local\` names
  map to the session namespace), parsing the header/slot descriptor per §1.3 and returning
  `{"seq", "width", "height", "pose", "color": np.ndarray(h,w,4), "depth": np.ndarray(h,w), "overlay": ...}`;
  returns `None` if the magic differs or the slot's seq is odd or changes during the copy.
- [ ] `tests/test_shm.py`: builds a fake mapping with a different name via `mmap.mmap(-1, size, tagname="CyberblockTest")`,
  writes a 4×2 slot with known pixels using the same layout code path (`shm.write_test_frame`), and asserts
  `read_latest("Local\\CyberblockTest")` returns those pixels, magic check, and odd-seq rejection.
- [ ] `tools/fakehost.py`: connects to `ws://127.0.0.1:25600`, sends `hello`, `view {w:1280,h:720}`, a known
  ground slab (`ground` covering x,z ∈ [−8, 8], y 63..63) and a scripted 120-frame camera orbit around
  (0, 65, 0) as `cam` messages at 60 Hz; prints received `hello`/`status`; every 30 frames saves
  `out/fakehost/frame_XXX_{color,depth,overlay}.png` from `shm.read_latest()`.
  CLI: `uv run python -m tools.fakehost --frames 120 --out out/fakehost`.
- [ ] Commit `feat(tools): shared-memory reader and fake host oracle`

### Task A5: Installer
**Files:** Create `tools/install.py`, `tests/test_install.py`.
Behaviour (all paths overridable by `--game`, `--build` for tests):
- Installs: `cp/plugin/build/Release/Cyberblock.dll` → `<CP>\red4ext\plugins\Cyberblock\Cyberblock.dll`;
  `cp/scripts/Cyberblock/*.reds` → `<CP>\r6\scripts\Cyberblock\`; `cp/shaders/Cyberblock.fx` →
  `<CP>\bin\x64\reshade-shaders\Shaders\Cyberblock.fx`; appends `Techniques=Cyberblock@Cyberblock.fx` to a
  new `<CP>\bin\x64\ReShadePreset.ini` only if that file does not exist.
- Writes `<CP>\red4ext\plugins\Cyberblock\install-manifest.json` listing every file it wrote with sha256.
- Refuses (exit 2, message) to overwrite any existing file not in the manifest.
- `--remove`: deletes exactly the manifest's files whose sha256 still matches, then the manifest; reports
  files left because they changed.
- Before the first install (no manifest): zips `%USERPROFILE%\Saved Games\CD Projekt Red\Cyberpunk 2077` to
  `%LOCALAPPDATA%\Cyberblock\save-backups\saves-<UTC timestamp>.zip`.
- `tests/test_install.py` uses `tmp_path` fake game/build/saves dirs and covers: fresh install writes manifest;
  re-install is idempotent; foreign file → exit 2; remove deletes only unchanged manifest files; backup zip created once.
- Commit `feat(tools): installer with manifest, refusal and save backup`

### Task A6: Launcher
**Files:** Create `tools/play.py`.
- Starts the Minecraft dev client: `mc\gradlew.bat runClient --args="--quickPlaySingleplayer cyberblock"`
  with `JAVA_HOME` pointing at the Temurin 25 install (found via `where java` / `HKLM\SOFTWARE\Eclipse Adoptium`),
  waits up to 120 s until a TCP connect to `127.0.0.1:25600` succeeds, then runs `steam://rungameid/1091500`
  via `os.startfile`. Prints what it is waiting for. Ctrl+C stops Minecraft.
- Commit `feat(tools): play launcher`

---

## Track B — Minecraft Fabric mod (`mc/`)

### Task B1: Fork the reference mod and trim it to layer 1
**Files:** Create `mc/**` from the reference `mc/` (gradle wrapper, `build.gradle`, `settings.gradle`,
`gradle.properties`, sources, resources).
- [ ] Port per §0. `rootProject.name = 'cyberblock'`, `group=dev.cyberblock`, mod id `cyberblock`, mixin
  configs `cyberblock.mixins.json` / `cyberblock.client.mixins.json`, `HostLink` default port 25600 (system
  property `cyberblock.port`), `FrameExporter.NAME = "Local\\CyberblockFrame"`, `MAGIC = 0x52464243`.
- [ ] Remove layer 2+ features: delete `MobWar.java`, `Nether.java`, `ProxyTargetGoal.java`, `MobAccessor`,
  `MobMixin`, `PortalMixin`, `ProjectileMixin`, `ProxyMixin`, `ServerExplosionMixin`; remove their message
  cases from `HostLink` and their calls elsewhere. Keep `WorldBridge.solid/clearSolid` and the block-change
  reporting code but stop sending block events (layer 2 re-enables them).
- [ ] Verify the wrapper jar matches Gradle's published checksum:
  `curl -s https://services.gradle.org/distributions/gradle-9.7.1-wrapper.jar.sha256` vs
  `certutil -hashfile mc/gradle/wrapper/gradle-wrapper.jar SHA256` (they must be equal; stop and report if not).
- [ ] Build: `cd mc && gradlew.bat build` (JAVA_HOME = JDK 25) → Expected: `BUILD SUCCESSFUL`,
  `mc/build/libs/cyberblock-0.1.0.jar`.
- [ ] Commit `feat(mc): fork reference passthrough mod as cyberblock, trimmed to layer 1`

### Task B2: Ground ranges with removal
**Files:** Create `mc/src/main/java/dev/cyberblock/Ranges.java`; modify `WorldBridge.java`, `HostLink.java`;
test `mc/src/test/java/dev/cyberblock/RangesTest.java` (add `testImplementation 'org.junit.jupiter:junit-jupiter:5.11.0'`
and `test { useJUnitPlatform() }` to `build.gradle`).
- [ ] **Failing test** `RangesTest`:
```java
package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class RangesTest {
	@Test
	void decodesQuadruples() {
		List<Ranges.Range> r = Ranges.decode(new int[] {10, -21, 3, 4, -4, 7, 12, 12});
		assertEquals(List.of(new Ranges.Range(10, -21, 3, 4), new Ranges.Range(-4, 7, 12, 12)), r);
	}

	@Test
	void rejectsBadLength() {
		assertThrows(IllegalArgumentException.class, () -> Ranges.decode(new int[] {1, 2, 3}));
	}

	@Test
	void rejectsInvertedRange() {
		assertThrows(IllegalArgumentException.class, () -> Ranges.decode(new int[] {0, 0, 5, 4}));
	}

	@Test
	void countsBlocks() {
		assertEquals(2, new Ranges.Range(0, 0, 3, 4).height());
	}
}
```
- [ ] Run `gradlew.bat test` → FAIL (class missing).
- [ ] **Implement** `Ranges.java`:
```java
package dev.cyberblock;

import java.util.ArrayList;
import java.util.List;

/** Solid column ranges from the host: {x, z, yBottom, yTop} quadruples, inclusive, Minecraft block coordinates. */
public final class Ranges {
	public record Range(int x, int z, int yBottom, int yTop) {
		public int height() {
			return this.yTop - this.yBottom + 1;
		}
	}

	private Ranges() {
	}

	public static List<Range> decode(final int[] c) {
		if (c.length % 4 != 0) {
			throw new IllegalArgumentException("ranges need quadruples, got " + c.length + " ints");
		}

		List<Range> out = new ArrayList<>(c.length / 4);
		for (int i = 0; i < c.length; i += 4) {
			if (c[i + 2] > c[i + 3]) {
				throw new IllegalArgumentException("inverted range at " + i / 4);
			}

			out.add(new Range(c[i], c[i + 1], c[i + 2], c[i + 3]));
		}

		return out;
	}
}
```
- [ ] Rework `WorldBridge.solid` to take `List<Ranges.Range>` (place barrier only into air, track in `barriers`),
  add `WorldBridge.unsolid(List<Ranges.Range>)` removing only blocks that are still `BARRIER` and tracked;
  `HostLink`: `case "ground" -> WorldBridge.solid(Ranges.decode(ints(...)))`, `case "unground" -> WorldBridge.unsolid(...)`.
  Bad input is logged and ignored (never crashes the link thread).
- [ ] `gradlew.bat test build` → PASS. Commit `feat(mc): ground and unground ranges`

### Task B3: Night City world
**Files:** Create `mc/src/main/resources/data/cyberblock/dimension_type/nightcity.json`,
`.../worldgen/world_preset/nightcity.json`, `mc/src/client/java/dev/cyberblock/client/AutoWorld.java`; modify
`CyberblockClient.java`.
- [ ] `dimension_type/nightcity.json`: copy vanilla 26.3 `overworld` dimension type (from `gradlew genSources`
  output / the vanilla data in the Loom cache) and set `"min_y": -512, "height": 2048, "logical_height": 2048`.
- [ ] `world_preset/nightcity.json`: overworld = `cyberblock:nightcity` type with a `minecraft:flat` generator,
  `layers: []`, biome `minecraft:the_void`, no structures; nether/end vanilla.
- [ ] `AutoWorld`: when the client reaches the title screen and the launch args contain
  `--quickPlaySingleplayer cyberblock` but `saves/cyberblock` does not exist, create it with the
  `cyberblock:nightcity` preset, game mode survival, cheats on (so `/gamemode` works), then load it.
- [ ] Verify: `gradlew.bat runClient --args="--quickPlaySingleplayer cyberblock"` → world loads; in chat
  `/setblock 0 1500 0 stone` succeeds and `/setblock 0 -500 0 stone` succeeds. Close.
- [ ] Commit `feat(mc): nightcity void world with 2048-block height`

### Task B4: Hello, status, state, hud and view
**Files:** Modify `HostLink.java`, `CyberblockClient.java`, `ClientInput.java`; create `mc/src/client/java/dev/cyberblock/client/StatusReporter.java`.
- [ ] On open: send `{"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"}`. On receiving `hello` with
  `proto != 1`: log and close the connection.
- [ ] `StatusReporter`: every 2 client ticks (10 Hz) and immediately when any field changes, send
  `{"t":"status","hp":…,"maxHp":…,"food":…,"gm":"survival","screen":bool}` (`screen` = a GUI screen is open,
  excluding the in-game HUD).
- [ ] `state {vehicle:true}`: hide the hand and the HUD and make the local player model invisible; `false`
  restores. `hud` and `view` handling are kept from the reference.
- [ ] Commit `feat(mc): hello, status reporting and vehicle state`

### Task B5: Screen input (cursor and clicks)
**Files:** Modify `ClientInput.java`; create `mc/src/client/java/dev/cyberblock/client/HostCursor.java`,
`mc/src/client/java/dev/cyberblock/client/mixin/GuiCursorMixin.java`.
- [ ] `cursor {x,y}` → store; convert to GUI-scaled coordinates (`x * window.getGuiScaledWidth()`), call the
  open screen's mouse-moved handler. `click {b,down}` → the screen's mouse-clicked/released handler at the
  stored cursor (look up the exact 26.3 method names in the generated sources).
- [ ] `GuiCursorMixin`: after the screen renders, draw an 8×8 white crosshair-arrow quad at the host cursor
  so the cursor appears in the exported overlay (the OS cursor is in Cyberpunk's window, not Minecraft's).
- [ ] Add `chat`, `command` (opens chat with `/`) to the `key` mapping.
- [ ] Commit `feat(mc): host-driven cursor and clicks for screens`

### Task B6: Oracle run
- [ ] With `gradlew.bat runClient --args="--quickPlaySingleplayer cyberblock"` running, run
  `uv run python -m tools.fakehost --frames 120 --out out/fakehost`.
  Expected: Minecraft logs the host connection; `out/fakehost/` contains 4 colour PNGs showing the stone slab
  from an orbiting camera, depth PNGs with a gradient, overlay PNGs with the hand/hotbar; fakehost prints
  `hello` and `status` lines. Attach the PNG paths to the task report.
- [ ] Commit any fixes `fix(mc): …`

---

## Track C — DLL core (`cp/plugin/`)

### Task C1: CMake project and RED4ext plugin skeleton
**Files:** Create `cp/plugin/CMakeLists.txt`, `cp/plugin/src/main.cpp`, `cp/plugin/src/log.hpp`,
`cp/plugin/src/log.cpp`; copy reference `gta/src/compositor.h` and `compositor.cpp` to `cp/plugin/src/`
with only the §0 renames (Track D takes them over).
- [ ] `CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.28)
project(Cyberblock VERSION 0.1.0 LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
add_compile_definitions(WIN32_LEAN_AND_MEAN NOMINMAX UNICODE _UNICODE)

include(FetchContent)
FetchContent_Declare(json URL https://github.com/nlohmann/json/releases/download/v3.11.3/json.tar.xz)
FetchContent_MakeAvailable(json)

set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../..)
add_subdirectory(${ROOT}/third_party/RED4ext.SDK ${CMAKE_BINARY_DIR}/red4ext.sdk EXCLUDE_FROM_ALL)

file(GLOB CB_SOURCES CONFIGURE_DEPENDS src/*.cpp)
add_library(Cyberblock SHARED ${CB_SOURCES})
target_include_directories(Cyberblock PRIVATE src ${ROOT}/third_party/reshade/include)
target_link_libraries(Cyberblock PRIVATE RED4ext::SDK nlohmann_json::nlohmann_json ws2_32 user32)

enable_testing()
add_executable(cb_tests tests/test_main.cpp tests/test_cbmath.cpp tests/test_probes.cpp src/cbmath.cpp src/probes.cpp)
target_include_directories(cb_tests PRIVATE src)
target_link_libraries(cb_tests PRIVATE nlohmann_json::nlohmann_json)
target_compile_definitions(cb_tests PRIVATE CB_VECTORS="${ROOT}/shared/vectors.json")
add_test(NAME cb_tests COMMAND cb_tests)
```
  (If the RED4ext.SDK target name differs, use the one its `CMakeLists.txt` exports and note it.)
- [ ] `main.cpp`: RED4ext entry points — `Supports()` returns the RED4ext API version from the SDK;
  `Query()` fills name `Cyberblock`, author `rodthegod2`, version 0.1.0, runtime `RED4EXT_RUNTIME_INDEPENDENT`,
  SDK version; `Main(handle, reason, sdk)` on `Load`: open the log, check the game version via
  `sdk->runtime->GetProductVersion()` == 2.31 (if not: log and return true without registering anything),
  register game states and natives (C4/C5), call `compositor::try_register` once ReShade is loaded (retry each
  frame from the Running-state update until it returns true); on `Unload`: unregister and stop the link.
- [ ] Build: `cmake -S cp/plugin -B cp/plugin/build -G "Visual Studio 17 2022" -A x64` then
  `cmake --build cp/plugin/build --config Release` → Expected: `cp/plugin/build/Release/Cyberblock.dll`
  (tests target may fail until C2; build only `--target Cyberblock` here).
- [ ] Commit `feat(cp): RED4ext plugin skeleton with version gate`

### Task C2: cbmath mirror + tests
**Files:** Create `cp/plugin/src/cbmath.hpp`, `cp/plugin/src/cbmath.cpp`, `cp/plugin/tests/test_main.cpp`,
`cp/plugin/tests/test_cbmath.cpp`.
- [ ] `test_main.cpp`: a 30-line micro test runner (`CHECK(cond)` macro counting failures, `main` runs
  registered tests and returns the failure count).
- [ ] `test_cbmath.cpp`: loads `CB_VECTORS` with nlohmann/json and, for every case, compares
  `cb::cp_quat_to_mc_yaw_pitch`, `cb::down_hit_to_range`, `cb::wall_hit_to_range` with tolerance 1e-6
  (yaw compared modulo 360, so −180 and 180 are equal).
- [ ] Run `cmake --build cp/plugin/build --config Release --target cb_tests && ctest --test-dir cp/plugin/build -C Release`
  → FAIL first (symbols missing), then implement `cbmath.*` as a line-for-line mirror of `tools/cbmath.py`
  (same function names in `namespace cb`, `double` math, `std::floor/std::ceil`), re-run → PASS.
- [ ] Commit `feat(cp): coordinate conversion mirrored from cbmath.py, vector tests`

### Task C3: Probe diffing
**Files:** Create `cp/plugin/src/probes.hpp`, `cp/plugin/src/probes.cpp`, `cp/plugin/tests/test_probes.cpp`.
- [ ] `class ProbeSet { Delta submit(Vec3 cpCenter, span<const Vec3> down, span<const Vec3> wall, double radius); void clear(); }`
  where `Delta { std::vector<int> add, remove; }` holds quadruples. A range stays solid while its column is
  within `radius` blocks (horizontal distance from the Minecraft-converted center) and has been confirmed by a
  hit within the last 3 s; columns re-probed without a hit at that height are removed.
- [ ] Tests: submitting the same hits twice → second delta empty; moving the center 30 m with radius 24 →
  far ranges appear in `remove`; a range not re-hit for 3 s after its column is probed again → removed;
  `clear()` then submit → everything re-added.
- [ ] Commit `feat(cp): probe set with add/remove deltas`

### Task C4: Link (WebSocket client) + natives
**Files:** Create `cp/plugin/src/link.hpp`, `link.cpp` (port reference `ws.h`/`ws.cpp` into `ws.*` unchanged
except renames), `natives.cpp`; modify `main.cpp`.
- [ ] `Link`: background thread; connects to `127.0.0.1:25600`, reconnects every 1 s when closed; on open
  sends `hello` and `view`; parses incoming JSON with nlohmann; `hello` with `proto==1` sets `linked`;
  `status` updates atomics (`screenOpen`, `lastStatusTime`); `send(json)` enqueues (drops oldest `cam` if the
  queue exceeds 8 messages).
- [ ] Natives per §1.4, registered as global functions in RED4ext RTTI during `RTTIRegistrator` post-register
  (`CGlobalFunction::Create("Cyberblock_IsLinked", "Cyberblock_IsLinked", &fn)` with params/return types).
  `SubmitProbes` converts `Vector4` arrays to `Vec3`, calls `ProbeSet::submit`, and sends non-empty
  `ground`/`unground`. `SetActive` sends `state`, `hud`, `clear`, toggles `compositor::set_active`, and gates
  `cam` publishing and input forwarding.
- [ ] Manual check: with Minecraft dev client running and no game, run `cb_tests` + a tiny `cp/plugin/tests/link_smoke.cpp`
  executable (built only on demand) that connects, prints the received `hello` and three `status` lines.
- [ ] Commit `feat(cp): WebSocket link and redscript natives`

### Task C5: Per-frame camera publish
**Files:** Create `cp/plugin/src/camera.cpp`, `camera.hpp`; modify `main.cpp`.
- [ ] Register a Running game state via `sdk->gameStates->Add(handle, RED4ext::EGameStateType::Running, &state)`
  with `OnUpdate` that (when active and linked) reads the active camera's world transform and fov by calling
  the scripted functions through RTTI (`GameInstance.GetCameraSystem(game)` →
  `GetActiveCameraWorldTransform` and `GetActiveCameraFOV`; confirm the names first in the CET console:
  `print(Game.GetCameraSystem():GetActiveCameraWorldTransform())`, `print(Game.GetCameraSystem():GetActiveCameraFOV())`),
  reads the player's world position and orientation (`GetPlayer(game)` → `GetWorldPosition`, `GetWorldYaw`),
  converts with `cb::` functions, sends `cam` with an incrementing frame number, and calls
  `compositor::set_host_pose(...)` and `compositor::set_host_planes(near, far)` (planes from the camera
  component; if unavailable, constants 0.1 / 10000 marked for F2 calibration).
- [ ] Log the first 5 converted poses at info level for F2.
- [ ] Commit `feat(cp): per-frame camera publish`

### Task C6: Input forwarding
**Files:** Create `cp/plugin/src/input.cpp`, `input.hpp`; modify `main.cpp`.
- [ ] Find Cyberpunk's main window (`EnumWindows` for this process, class/title containing "Cyberpunk 2077"),
  subclass it (`SetWindowLongPtrW(GWLP_WNDPROC)`), register raw mouse input (`RegisterRawInputDevices`,
  `RIDEV_INPUTSINK` not set). While active, not in a vehicle and the window is foreground:
  - `WM_LBUTTON*`→`key attack`, `WM_RBUTTON*`→`key use`, `WM_MBUTTON*`→`key pick`, `WM_MOUSEWHEEL`→`scroll`,
    `1`–`9`→`slot`, `E`→`key inventory`, `Q`→`key drop`, `T`→`key chat`, `/`→`key command`, `Esc` while
    `screenOpen` → `key escape` (and swallow so Cyberpunk's pause menu does not open).
  - While `screenOpen`: raw mouse deltas move a virtual cursor (clamped 0..1, starting centered, sensitivity
    1/800 per count) sent as `cursor` at ≤ 120 Hz; buttons send `click` instead of `key`.
- [ ] `F9` (`WM_KEYDOWN`, not auto-repeat) at any time sets an atomic flag read-and-cleared by
  `Cyberblock_ConsumeToggle`.
- [ ] Always call the original WndProc except for the swallowed Esc.
- [ ] Commit `feat(cp): input forwarding and screen cursor`

---

## Track D — compositor (`cp/plugin/src/compositor.*`, `cp/shaders/`)

### Task D1: DX12-safe compositor port
- [ ] Starting from the C1 copy, keep the API in `compositor.h` (functions listed in the reference header).
  Replace any D3D11-specific assumptions: use only `reshade::api` calls; create textures with
  `resource_usage::shader_resource | copy_dest`; upload via `device->update_texture_region`; add the
  `command_list->barrier` transitions ReShade requires on D3D12 around updates; bind with
  `runtime->update_texture_bindings("CB_WORLD", …)`, `"CB_DEPTH"`, `"CB_OVERLAY"`.
- [ ] Uniforms set each frame through `runtime->find_uniform_variable("Cyberblock.fx", name)`:
  `cb_active`, `cb_host_near`, `cb_host_far`, `cb_host_pose[7]` (yaw, pitch, roll, fov, x, y, z),
  `cb_mc_pose[7]`, `cb_mc_near`, `cb_mc_far`, `cb_mc_flags`, `cb_age_ms`.
- [ ] Frames older than 250 ms (`cb_age_ms`) set `cb_active = false`.
- [ ] Build `--target Cyberblock` → success. Commit `feat(cp): DX12-safe compositor`

### Task D2: Shader port
**Files:** Create `cp/shaders/Cyberblock.fx` from reference `gta/shaders/MCPassthrough.fx`.
- [ ] Rename textures/uniforms to the D1 names. Host depth: `ReShade::GetLinearizedDepth` is **not** used;
  instead read `ReShade::DepthBuffer` raw and linearise with `cb_host_near/cb_host_far` assuming reversed-Z
  (`linear = near / max(raw, 1e-7)` for infinite reversed; keep a `#define CB_HOST_DEPTH_MODE 0|1|2`
  — 0 reversed-infinite, 1 reversed-finite, 2 standard — default 0, chosen in F2).
- [ ] Keep reprojection, relight (rename "Match GTA lighting" → "Match Night City lighting"), haze, edge
  softening and overlay passes.
- [ ] Add a second technique `CyberblockDepthDebug` that outputs host linear depth as a grey ramp
  (0 m black → 50 m white) with a 5-m isoline every 5 m, for F2.
- [ ] Commit `feat(cp): Cyberblock.fx with Night City depth modes and depth debug`

### Task D3: Compositor smoke test without the game
**Files:** Create `cp/plugin/tests/replay_frame.py` (in `tools/` style, run with uv).
- [ ] Script writes a synthetic frame (checkerboard colour, constant 5 m depth, overlay with a red square)
  into `Local\CyberblockFrame` using `tools/shm.py`'s writer and keeps refreshing it, so the compositor can be
  checked in-game (F1) before Minecraft is involved.
- [ ] Commit `test(cp): synthetic frame replayer`

---

## Track E — redscript gameplay (`cp/scripts/Cyberblock/`)

Compile check for every task: run `"<CP>\engine\tools\scc.exe" -help` once to learn the flags, then compile
the game's scripts plus ours into a temporary cache directory (never into `<CP>`) and expect zero errors.
Every Cyberpunk API used must first be confirmed to exist in 2.31 via the CET console (`print(...)`) or the
redscript/Codeware references; record the confirmation in the commit message.

### Task E1: Natives and the mashup state machine
**Files:** Create `Natives.reds` (exactly §1.4), `Cyberblock.reds`.
- [ ] `public class CyberblockSystem extends ScriptableSystem` with states `Off`, `Active`, `Vehicle`,
  `Lost`. F9 comes from the DLL (`Cyberblock_ConsumeToggle()`), so no input-mapping mod is needed.
- [ ] A 10 Hz tick (`DelaySystem.DelayCallback` re-armed) drives: `Off`→`Active` only on F9 when
  `Cyberblock_IsLinked()` (F9 while not linked → notification "Start Minecraft first"); any state → `Off` on F9; `Active`/`Vehicle`→`Lost` when `Cyberblock_SecondsSinceFrame() > 2.0`; `Lost`→previous
  state automatically when frames return; mounting/dismounting a vehicle switches `Active`↔`Vehicle`.
  Every transition calls `Cyberblock_SetActive(...)` and shows a short notification
  ("Cyberblock on", "Minecraft lost — V restored", …).
- [ ] Commit `feat(scripts): Cyberblock state machine and F9 toggle`

### Task E2: V's state while active
**Files:** Create `PlayerState.reds`.
- [ ] Apply on `Active`, remove on any other state: unequip and holster weapons; apply gameplay restriction
  status effects for no combat, no phone and no quickhacks (look up exact TweakDB IDs, e.g.
  `GameplayRestriction.NoCombat`, `GameplayRestriction.NoPhone`, `GameplayRestriction.NoQuickHacks`, with
  `TweakDB:GetRecord` in CET); god mode via `GodModeSystem.AddGodMode(player.GetEntityID(),
  gameGodModeType.Invulnerable, n"Cyberblock")` / `ClearGodMode`; hide the Cyberpunk HUD on foot (find the
  2.31 mechanism — HUD manager visibility or the "hud" ink layer — and restore it exactly).
- [ ] While `Cyberblock_ScreenOpen()`: apply a camera-control restriction (confirm the ID, e.g.
  `GameplayRestriction.NoCameraControl`) and remove it when the screen closes.
- [ ] Save safety: register Codeware callbacks for the session's before-save and after-load events and
  remove every effect above before the save; re-apply after the save if still `Active`. On load, always
  remove them and set state `Off`.
- [ ] Commit `feat(scripts): hide V, block combat, god mode, HUD swap, save safety`

### Task E3: Collision probes
**Files:** Create `Probes.reds`.
- [ ] Each tick while `Active`/`Vehicle`: budget 192 rays per 100 ms, round-robin over
  (a) down rays: columns on a 1-m grid within 24 m of V, from `feet.z + 3` to `feet.z − 10`, and
  (b) wall rays: 32 directions × heights 0.5/1.5/2.5 m, 24 m long, from V's feet.
  Use `GameInstance.GetSpatialQueriesSystem(game).SyncRaycastByCollisionGroup(start, end, n"Static", out result)`
  (confirm signature and collision group names in 2.31; also include `n"Terrain"` if it exists).
  Collect hit positions into `downHits`/`wallHits` and call `Cyberblock_SubmitProbes(feet, downHits, wallHits, 24.0)`.
- [ ] Commit `feat(scripts): raycast probes for Night City collision`

---

## Track F — integration and in-game verification (main session)

### Task F1: First light (no Minecraft)
- [ ] Merge A–E. `uv run python -m tools.install`. Launch Cyberpunk, load a save outside in Watson.
- [ ] ReShade overlay (Home): Add-ons tab lists Cyberblock; enable `CyberblockDepthDebug` → the grey ramp must
  follow scene geometry (near dark, far bright) with DLSS on and frame generation off. Screenshot.
- [ ] Run `uv run python cp/plugin/tests/replay_frame.py`; enable `Cyberblock` → checkerboard visible where its
  constant 5 m depth is nearer than the scene. Screenshot. Pick `CB_HOST_DEPTH_MODE` so a wall 5 m away
  cuts the checkerboard exactly at the wall.

### Task F2: Cube test and calibration
- [ ] Start Minecraft (`uv run python -m tools.play` without auto-starting the game, or run both).
  F9 → Steve's hand and hotbar appear.
- [ ] In Minecraft chat `/setblock <x> <y> <z> gold_block` at V's feet + 3 m forward (coordinates from the
  F3 screen). The block must appear 3 m in front of V on the ground, stay put while turning, and be hidden
  behind a pillar when walking behind it.
- [ ] If mirrored or rotated: fix the formula in `tools/cbmath.py`, regenerate vectors, update `cbmath.cpp`,
  all tests pass, commit `fix(shared): coordinate convention from cube test`. Same for fov
  (`fovIsHorizontal`) and near/far.

### Task F3: Lag test
- [ ] Gold wall 4×4 in front of a landmark; flick the mouse 90° quickly; record with
  `um win record --exe Cyberpunk2077.exe --seconds 10` and step frames. Acceptance: wall edge drifts ≤ 3 px
  at 2560×1440 after reprojection.

### Task F4: Layer 1 acceptance
- [ ] Walk 200 m including stairs and an overpass: Steve stands on Night City floors; placed blocks rest on floors.
- [ ] Enter and leave a car: Cyberpunk HUD and Steve swap correctly.
- [ ] Close Minecraft mid-walk: within 2 s V is restored with a notification; restart Minecraft: resumes.
- [ ] Save while active, load that save without the mod (`uv run python -m tools.install --remove`): loads
  as a normal save, V has weapons, HUD, no god mode.
- [ ] Re-install, F9 off/on 10 times: no leftover restrictions.
- [ ] Tag `layer1` and report with screenshots.
