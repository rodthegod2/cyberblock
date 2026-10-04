# Cyberblock Re-test 2 Fixes and Cyberware Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** fix everything the user reported in the second in-game re-test, and add installable cyberware. The source is
`out/user-issues.md`, section "Re-test 2026-10-03 ~15:05-15:20", items R1 to R6:
- **R1** the elytra "works but it teleports you, the camera only updates every few seconds";
- **R2** hacking fails with "target lost/gone" (and scans often find nothing);
- **R3** infinite skill points for testing (free perks in creative, `/cyberblock perks free on|off` in survival);
- **R4** placed blocks never sit ON the Night City ground, always about a block above;
- **R5** a wind charge cannot launch Steve (and V);
- **R6** NEW: cyberware the player installs in custom ripperdoc slots (screen on U, activation on Y, HUD cooldown
  bars), every implant with Minecraft VFX and, where it makes sense, the real Cyberpunk effect.

**Root causes (from the investigations, evidence in the logs of 15:04 to 15:21):**
- **R1:** the per-frame script loop in `Flight.reds` runs one frame and stops. `DelayCallbackNextFrame` re-queued from
  inside the callback never fires in 2.31, so only the 10 Hz watchdog restarts it, about once per 1.05 s. Every flight
  length in the log is a whole number of watchdog periods (3.139 s = 3 x 1.046 s, 24.25 s = 23 x 1.054 s). V and the
  FPP camera jump ~17 m per second at glide speed. The landing is then decided from a position up to 1 s old (V is
  left short, possibly in mid-air), and Steve snaps back to V after the 0.5 s settle.
- **R2:** Codeware's `EntityID.ToHash(id: script_ref<EntityID>)` hashes garbage when it is given a call result
  (`EntityID.ToHash(object.GetEntityID())`). Four call sites (Scan, Kills, Puppets, Effects) send heap-address-like
  ids (2668059858640, 0) instead of real ones (10019101). Minecraft locks that id, the `hack` comes back with it, and
  `FindEntityByID` fails: "target gone". Separately, the Minecraft pick needs the exact camera ray to cross a 0.6 m
  client-side box that trails the real NPC.
- **R3:** `PerkShop` always charges XP levels, and there is no free mode.
- **R4:** ground barriers use `top = ceil(z) - 1`, so the barrier's top face is 0 to 1 m above the real floor, and
  the two worlds have no vertical alignment at all.
- **R5:** `PlayerSync` pins Steve to V and zeroes his motion every tick, and in creative the server never sends
  explosion knockback to a flying player. Even if Steve did fly, V could only follow through the broken R1 loop.
- **R6:** a new feature. Nothing models installed cyberware today: there are held weapons, perks and an inventory deck,
  but no slots, install state, activation key, synced cooldowns or Cyberpunk-side effects beyond Sandevistan.

**Architecture:** the same three parts as layers 1 to 6 (spec section 3). Two changes in where the work runs:
- **R1:** the DLL moves V every rendered frame from RED4ext's Running-state `OnUpdate`, the place that already
  publishes the camera every frame. Redscript only decides whether V may be moved (10 Hz) and owns the fall-damage
  rules.
- **R4:** Minecraft owns a per-world vertical offset `k/64` m. The DLL applies it to every position conversion, both
  ways.

Everything else follows the existing pattern:
1. a wire message (section 1.2);
2. the DLL validates and converts it into an event or a snapshot;
3. redscript applies it with real game systems.

**Sources:**
- Spec `docs/superpowers/specs/2026-10-02-minecraft-cyberpunk-passthrough-design.md`.
- Previous plan `docs/superpowers/plans/2026-10-03-layers3-6-complete-mod.md` (cited as "L3-6 plan").
- Protocol `shared/protocol.md`.
- Audit `docs/notes/cp-mechanics-audit.md`.
- Flight notes `docs/notes/flight-spike.md`.
- Evidence:
  - `D:\SteamLibrary\steamapps\common\Cyberpunk 2077\red4ext\logs\Cyberblock.log` (2026-10-03 15:04-15:21);
  - `D:\Cyberblock\mc\run\logs\latest.log` (same window);
  - `D:\SteamLibrary\steamapps\common\Cyberpunk 2077\bin\x64\ReShade.log`.

---

## 0. Ground rules (all packages)

- **Worktrees:**
  - Work only in your own files (table below), on your own branch in your own worktree: `r2/<package>`, for example
    `git worktree add D:\Cyberblock-wt\r2-D -b r2/D main`.
  - **Never build or run Gradle inside `D:\Cyberblock\mc`**: the user's Minecraft may be running from it. Build and test
    only in your worktree.
- **Commits:**
  - Commit after every task, with explicit paths (`git add <paths>`, never `git add -A`).
  - The message ends with a blank line and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
  - Never push. Never rewrite main.
- **Cyberpunk is off-limits:**
  - Never launch Cyberpunk.
  - The Cyberpunk folder is read-only, except for the integrator's install command.
  - No system installs and no tool downloads (build-dependency downloads are fine).
- **No game assets or decompiled source in the repo:**
  - Never copy Minecraft or Cyberpunk assets or decompiled source into the repo.
  - Unpack sources only outside the repo (your scratchpad, `%TEMP%`) and delete them when done.
  - New item and GUI art is procedural: extend `art/gen_item_art.py`, then copy its outputs into the mod resources.
- **Nobody is awake.** Never wait for input. If you are blocked:
  - record it in your report (`blocked`);
  - keep the code compiling (a logging no-op behind the contract signature);
  - move on to the next task.
- **APIs: never guess a name.**
  - Cyberpunk: NativeDB (https://nativedb.red4ext.com), the Codeware sources
    (`...\red4ext\plugins\Codeware\Scripts`), the decompiled 2.31 scripts the compile checker uses, and tweakdb. Then
    run `uv run python cp/scripts/compile_check.py`.
  - RED4ext: `third_party/RED4ext.SDK`.
  - Minecraft 26.3: the Loom sources jar (`D:\Cyberblock-wt\track-B\mc\.gradle\loom-cache`, or `gradlew genSources` in
    your worktree).
  - Cite the source in the file header, as the existing files do.
  - Anything that can only be checked in game goes to the report's `needsInGame` list, with a concrete check (a CET
    console line or an in-game action, plus the expected log line or result).
- **Building against a contract another package owns:**
  - Java: everything that crosses the M/C boundary goes through `Bridge.java` (section 1.6), which M owns. C (and
    anyone else who needs it) edits `Bridge.java` in their own worktree to the exact section 1.6 content and **never
    commits it**. The same rule applies to any other file another package owns.
  - redscript: S owns all of `cp/scripts/`, so no stubs are needed.
  - C++: D owns all of `cp/plugin/`, so no stubs are needed.
- **Minecraft test clients:**
  - Use only your package's port and frame name (table), passed as
    `gradlew runClient -Pcyberblock.port=<port> "-Pcyberblock.frame=<frame>"`. Never use 25600 or
    `Local\CyberblockFrame`.
  - Launch only when system commit is under 60 GB. Check it with `Get-CimInstance Win32_OperatingSystem`: commit is
    `TotalVirtualMemorySize - FreeVirtualMemory`.
  - Always close what you launched. Never kill a process you did not start.
- **Test suites:**
  - `uv run pytest -q`;
  - in `mc/` of your worktree, `gradlew.bat test build` (`JAVA_HOME` = `%USERPROFILE%\AppData\Local\Cyberblock\jdk-25.0.4.1+1`);
  - the DLL with `"C:\Program Files\CMake\bin\cmake.exe" --build cp/plugin/build --config Release`, then
    `cp\plugin\build\Release\cb_tests.exe`. Configure `cp/plugin/build` in your worktree first if it does not exist
    there;
  - scripts with `uv run python cp/scripts/compile_check.py`.
- **Report format** (each package, at the end):
  `{package, commits[], done[], notDone[], blocked[], needsInGame[], contractDeviations[]}`.
  - A contract deviation is allowed only when the contract is impossible as written.
  - Keep the contract signature, and explain the deviation.

| Pkg | Scope | Owns (exclusive) | Model | Test port / frame |
|---|---|---|---|---|
| **D** DLL & conversion math | R1 drive, R4 offset, R6 binds/events | `cp/plugin/**`, `tools/cbmath.py`, `tools/gen_vectors.py`, `shared/vectors.json`, `tests/test_cbmath.py` | Opus | 25723 (link_smoke only) |
| **S** redscript | R1 policy/landing, R2 ids, R4 collision keys, R6 Cyberpunk effects | `cp/scripts/**` (all `.reds`, `compile_check.py`), `tests/test_reds_ids.py` (new) | Opus | — |
| **M** Minecraft world | R1/R5 flight and launch, R2 server pick, R4 alignment, R6 Bridge/HostState/ProxyEntity hooks | `mc/**` **except** the C and A paths (incl. `Bridge.java`, `Wire.java`, `Cyberblock.java`, `WorldBridge.java`, `ProxyManager.java`, `ProxyEntity.java`, `mixin/**`, `client/*.java`, `client/mixin/**`, `cyberblock.mixins.json`, `cyberblock.client.mixins.json`, `mc/src/test/java/dev/cyberblock/*.java`, `.../client/HostStateTest.java`, `build.gradle`) | Opus | 25721 / `Local\CyberblockFrameR2M` |
| **C** Minecraft cyber | R2 client scan, R3 free perks, R6 cyberware logic, UI, keys | `mc/src/main/java/dev/cyberblock/cyber/**`, `mc/src/client/java/dev/cyberblock/client/cyber/**`, `mc/src/test/java/dev/cyberblock/cyber/**` **except** `CyberAssetsTest.java`, `mc/src/main/resources/cyberblock.cyber.mixins.json`, `mc/src/client/resources/cyberblock.cyber.client.mixins.json`, `mc/src/main/resources/fabric.mod.json` | Opus | 25722 / `Local\CyberblockFrameR2C` |
| **A** art, assets, lang, recipes | R6 items/art, every new lang string | `art/**`, `mc/src/main/resources/assets/**`, `mc/src/main/resources/data/cyberblock/recipe/**`, `mc/src/test/java/dev/cyberblock/cyber/CyberAssetsTest.java` | Sonnet | — |
| **P** protocol, tools & docs (integrator docs) | wire docs, fakehost, player docs, test plan | `shared/protocol.md`, `tools/fakehost.py`, `tests/test_protocol_md.py`, `tests/test_fakehost.py`, `docs/HOW-TO-PLAY.md`, `docs/TESTING-PLAN.md`, `docs/notes/flight-spike.md`, `docs/notes/layer6-cp.md`, `docs/notes/cp-mechanics-audit.md`, `docs/superpowers/specs/2026-10-02-minecraft-cyberpunk-passthrough-design.md` | Sonnet | — |

Every package may start at once, because the contracts in section 1 are the only coupling. Each package does its
tasks in the listed order, which is also the priority order. Nobody edits `out/`, `README.md` or older plans.

---

## 1. Contracts (fixed; change only through the integrator)

### 1.1 Coordinates, units and the ground alignment (R4)
- **Offset:** a world has an alignment `k`, an integer from 0 to 63, with `off = k / 64` metres. It is exact in float32,
  double and JSON. The rule is **Minecraft y = Cyberpunk z − off**. Minecraft owns `k` (section 1.2 `align`); before
  any `align` arrives, and after every host `hello`, the DLL uses `k = 0`.
- **Positions:**
  - `cp_to_mc_pos(x, y, z, off) = (x, z − off, −y)`;
  - `mc_to_cp_pos(x, y, z, off) = (x, −z, y + off)`.
- **Directions and velocities never get the offset:**
  - NEW `cp_dir_to_mc(x, y, z) = (x, z, −y)`;
  - `mc_vel_to_cp(vx, vy, vz) = (vx, −vz, vy)`, written out explicitly, not through `mc_to_cp_pos`;
  - `cp_quat_to_mc_yaw_pitch` uses `cp_dir_to_mc`;
  - impulses (`hit.impulse`), `flight.v`, `proj[].v`, camera facing/forward, yaw and pitch get no offset.
- **Blocks:** `mc_block_to_cp_aabb(bx, by, bz, off)` gives min `(bx, −(bz+1), by + off)` and max
  `(bx+1, −bz, by + 1 + off)`.
- **Ground ranges, nearest rounding:**
  - `down_hit_to_range(x, y, z, off)`: `top = floor(z − off + 0.5) − 1` (ties go up; Python uses `math.floor`, never
    `round()`). It returns `(floor x, floor −y, top − 1, top)`.
  - `wall_hit_to_range(x, y, z, off)`: `b = floor(z − off)`.
- **Choosing k** (Python `align_k`, Java `GroundAlign.kOf`; the DLL never chooses):
  `k = floorMod(floor((z − floor z) · 64 − 0.125), 64)`, where `z` is V's feet in Cyberpunk z.
  - The aligned floor then maps to `N + e`, with `e` in [1.95 mm, 17.6 mm).
  - The nearest-rounded top face is exactly `N`, so Steve's feet are never inside the barrier under him.

  Fixed cases, used in every test suite:

  | z | k | | z | k |
  |---|---|---|---|---|
  | 6.907 | 57 | | 312.999 | 63 |
  | 6.001 | 63 | | −0.0005 | 63 |
  | 0.0 | 63 | | 64.5 | 31 |
  | −3.25 | 47 | | | |

- **C++ signatures:** `off` is a **required** parameter on every `cbmath` and `make_*` function, so that every call site
  is revisited. Only the `parse_*` functions may default it (to 0.0, for tests); their production callers pass it
  explicitly.

  | Gets `+off` (Minecraft → Cyberpunk) | Gets `−off` (Cyberpunk → Minecraft) | No offset |
  |---|---|---|
  | block AABBs, `explosion.pos`, `teleport.p`, `lead.anchor`, `pull.toward`, `knock.pos`, `mobs[].p`, `proj[].p`, `flight.p` | `cam.p`, `cam.pl`, `dmg.from`, `npcs[].p`, `pcells` (floor of y − off), `scaninfo.p`, probe centre, down/wall ranges | `hit.impulse` (new `wire::cp_vector`), `flight.v`, `proj[].v`, camera facing and forward, look quaternion, yaw/pitch, `SetAim` distance |

- **Entity ids:** they are unchanged (decimal `Uint64` strings). **New script rule (R2):** never pass a call result to
  `EntityID.ToHash`. Always use `CyberblockIds.Hash(entity)` (section 1.5).

### 1.2 Wire protocol changes (`proto` stays 1; unknown `t` and unknown fields are ignored)
**Minecraft → Cyberpunk:**

| `t` | fields | when |
|---|---|---|
| `flight` (extended) | adds optional `kind` ∈ `"glide"`, `"launch"` (anything else or absent = glide) and optional `ts`: Minecraft's monotonic sample clock in ms, finite, 0..1e13, computed as `(System.nanoTime() − Flight.EPOCH) / 1e6`. A bad `ts` is ignored, not rejected. Positions get `+off` | up to 120 Hz while Steve is driving (glide or launch). One `active:false` at touchdown, with `p` = the landing spot |
| `align` (new) | `k` integer 0..63 | from the server thread on world attach while linked, and on every change, **immediately followed** by a block resync (`blocks reset:true`, same thread). From the link thread on each host `hello` while a world is attached |
| `cyberware` (new) | `list:[{id, on:0\|1}]`, all 17 ids of section 1.7, ≤ 32 entries | after each host `hello`, on join, after every install/remove. All `on:0` on disconnect. Kept while not Active |
| `cyberfx` (new) | `id` ∈ `sandevistan`, `kerenzikov`, `berserk`; `seconds` 0..30 (0 = end now) | an implant effect starts (transient; discarded while not Active). It replaces Minecraft's use of `perkfx`; the DLL still accepts `perkfx` |
| `binds` (extended) | `keys:{hack, skills, cyberware, activate}` VK codes 1..254. A missing name keeps the current key (older Minecraft builds) | after each host `hello`, and when rebound |

Examples (one line each, in protocol.md's jsonl block):
- `{"t":"flight","active":true,"kind":"launch","ts":81234.5,"p":[1,70,2],"v":[0,1.2,0],"yaw":0,"pitch":10}`
- `{"t":"align","k":57}`
- `{"t":"cyberware","list":[{"id":"mantis_blades","on":1}, …]}`
- `{"t":"cyberfx","id":"sandevistan","seconds":6}`
- `{"t":"binds","keys":{"hack":71,"skills":75,"cyberware":85,"activate":89}}`

**Cyberpunk → Minecraft:**

| `t` | change |
|---|---|
| `cam` | adds `lv` (int 0..63): the `k` the DLL converted this cam with. When it is absent (fakehost, older host), Minecraft assumes its current `k`. `fly` keeps its meaning (V follows Steve's glide **or launch**), but the DLL drive now sets it on the first frame it moves V (it is no longer a script native). Minecraft still calls off a flight that `fly` does not confirm within 0.5 s |
| `key` | `k` also ∈ `cyberware`, `activate` (the keys from `binds`; swallowed while forwarding) |

**Validation (receiver):** `align.k` must be an integer 0..63; a bad message is counted and the old `k` kept.
`cyberware[].id` must be one of the 17 ids and `on` 0 or 1; a bad entry is dropped. `cyberfx.seconds` must be finite,
0..30. `flight.ts` must be finite, 0..1e13. Existing limits are unchanged.

### 1.3 DLL ↔ redscript natives (S declares them in `Natives.reds`, D implements them)
```swift
// ---- re-test 2 (plan 2026-10-03-retest2 section 1.3) ----
public native func Cyberblock_SetFlightAllowed(allowed: Bool) -> Void; // 10 Hz from CyberblockFlight.Tick: V may be moved (live, not mounted, not locked). Cleared by Clear and by SetActive(false)
public native func Cyberblock_FlightDriving() -> Bool;                 // the DLL is moving V this flight (same value as cam.fly)
public native func Cyberblock_SecondsSinceFlight() -> Float;           // 0 while driving, seconds since the last flight ended, 999 if none yet
// REMOVED: Cyberblock_SetFlightFollowing (the DLL drive is the only writer of cam.fly)
// KEPT, no longer called by Flight.reds: Cyberblock_SetLookCapture (dead until a follow-up), FlightPos/Velocity/Yaw/Pitch/Seq/TimedOut
```
**New event kinds** from `Cyberblock_NextEvent` (no new natives; they ride on `EventName` and `EventAmount`):

| kind | Name | Amount | while not Active |
|---|---|---|---|
| `cyber` | cyberware id | 1 installed, 0 not | kept (outranks like `perk`) |
| `cyberfx` | `sandevistan`/`kerenzikov`/`berserk` | seconds (0 = end) | discarded |

One `cyberware` message becomes one `cyber` event per entry.

**DLL-internal API (D only, listed for reviewers):**
- `cb::drive::tick()` is called from `on_running_update` **before** `cb::camera::publish()`. With it come
  `cb::drive::driving()` and `cb::drive::seconds_since_end()`.
- `cb::follow::Follower` is the pure frame state machine.
- `cb::world::level_k()` and `cb::world::level_offset(k)` give the alignment.
- `cb::natives::flightAllowed` is a new atomic.

### 1.4 Natives behaviour that S relies on
- `cam.fly` is true from the DLL's first move of V until the flight ends (landing, ground, stop).
- While the DLL drives, it teleports V every rendered frame:
  - to the interpolated flight sample (playout delay 8..60 ms, extrapolation ≤ 50 ms);
  - clipped against Night City Static and Terrain geometry (stopping 0.3 m short);
  - keeping V's current facing.
- At touchdown it lands V on the ground below the landing spot, never in mid-air. It never moves V while
  `flightAllowed` is false; it stops without teleporting instead.
- The DLL keeps ticking while the game is paused. While `menuOpen` it does nothing and freezes its timers.

### 1.5 Script API (S owns every file; listed because several S files call each other)
```swift
// Ids.reds (new)
public abstract class CyberblockIds {
  public static func Hash(entity: ref<Entity>) -> Uint64;   // 0ul when not defined; copies GetEntityID() to a local, then EntityID.ToHash(local)
}
// Flight.reds (rewritten small)
public abstract class CyberblockFlight {
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void; // sets Cyberblock_SetFlightAllowed
  public static func IsFlying(game: GameInstance) -> Bool;                                 // Cyberblock_FlightDriving()
  public static func Center(game: GameInstance, player: ref<PlayerPuppet>) -> Vector4;      // player.GetWorldPosition()
  public static func SuppressFall(game: GameInstance) -> Bool;                             // SecondsSinceFlight() < 2.0 || effects fall suppression
  public static func GroundBelow(game: GameInstance, pos: Vector4) -> Vector4;              // now public (Effects.Teleport uses it)
  public static func Clear(game: GameInstance) -> Void;
}
// Scan.reds: CyberblockScan.Scan(...) now returns String (a success note; "" = already logged)
// Cyberware.reds (new)
public abstract class CyberblockCyberware {
  public static func Set(game: GameInstance, id: CName, on: Bool) -> Void;
  public static func Has(game: GameInstance, id: CName) -> Bool;
  public static func Fx(game: GameInstance, id: CName, seconds: Float) -> Void;
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, active: Bool) -> Void;
  public static func Clear(game: GameInstance) -> Void;
}
```
`CyberblockPerks.Fx` delegates to `CyberblockCyberware.Fx` (old `perkfx` still works). `SetCameraOnly`/`IsCameraOnly`
are removed.

### 1.6 Java Bridge (M owns `mc/src/main/java/dev/cyberblock/Bridge.java`; these are the exact additions)
```java
// imports: java.util.Set, java.util.concurrent.atomic.AtomicInteger, java.util.function.Predicate,
//          net.minecraft.world.phys.Vec3, org.jspecify.annotations.Nullable

/** Scan pick from authoritative proxy positions (plan retest2 R2). M sets it (ProxyManager::aimedAt); call on the server thread only. */
@FunctionalInterface
public interface ProxyAimer {
	@Nullable Entity at(Vec3 eye, Vec3 dir, double reach);
}
public static volatile ProxyAimer proxyAimedAt = (eye, dir, reach) -> null;
/** The proxy stands for a hostile NPC (synced entity data, so it is valid on the client). M sets it. */
public static volatile Predicate<Entity> hostileProxy = e -> false;
/** Ids (section 1.7) of the cyberware installed in the local player: an immutable set that C replaces on every change, join, respawn, and with Set.of() on disconnect. Any thread may read it. */
public static volatile Set<String> installedCyberware = Set.of();
/** V is in the air (the host's cam.air). M sets it. */
public static volatile boolean hostAirborne;
/** Host jump key presses since start (monotonic). M increments it in HostState on every jump key-down. Readers keep their own last value; nobody consumes it. */
public static final AtomicInteger hostJumpPresses = new AtomicInteger();
/** Ground alignment k of the attached world (0 without one). M sets it. */
public static volatile int alignK;
/** V's feet, Cyberpunk z (from cam.pl + lv/64); NaN before the first cam. M sets it. */
public static volatile double hostFeetCpZ = Double.NaN;
/** V stands still on the ground: cam !air && !held && !fly. M sets it. */
public static volatile boolean hostStands;
```
Existing members stay. `Bridge.perkRank` stays: C sets it, and it now answers 1 for owned perks as before.

### 1.7 Cyberware catalogue (C implements it, A makes the items, S and D know the ids)
**Item ids** are `cyberblock:<id>`. **Wire ids** are the same lower-case ids, in this fixed order:
`mantis_blades, gorilla_arms, monowire, projectile_launch_system, reinforced_tendons, fortified_ankles, lynx_paws,
sandevistan, berserk, cyberdeck, kerenzikov, optical_camo, subdermal_armor, kiroshi_optics, biomonitor, second_heart,
ram_upgrade` (17 ids; `cyberdeck` is the existing item, re-registered with the same id).

**Slots** (`CyberSlot`, name and capacity; slot key `"<slot>/<n>"`):

| Slot | Capacity | Slot | Capacity |
|---|---|---|---|
| `arms` | 1 | `integumentary` | 2 |
| `legs` | 1 | `eyes` | 1 |
| `operating_system` | 1 | `circulatory` | 2 |
| `nervous_system` | 1 | `frontal_cortex` | 1 |

**Kinds:**
- PASSIVE: always on.
- ACTIVE: fired with Y.
- AUTO: fires by itself.

Times are in ticks at 20 TPS (seconds in brackets).

| id | slot | kind | dur / cd | Minecraft (VFX) | Cyberpunk |
|---|---|---|---|---|---|
| mantis_blades | arms | PASSIVE | — | empty main hand: ATTACK_DAMAGE +6, ATTACK_SPEED −2.2; on hit SWEEP_ATTACK, 6x DUST 0xFF2B45 1.2, PLAYER_ATTACK_STRONG | existing `hit` melee on proxies |
| gorilla_arms | arms | PASSIVE | — | empty hand: ATTACK_DAMAGE +4, ATTACK_SPEED −2.0, ATTACK_KNOCKBACK +2; GUST, PLAYER_ATTACK_KNOCKBACK, MACE_SMASH_GROUND pitch 1.4 | proxy shove through `hit.impulse` (ProxyDamage.gorillaArms) |
| monowire | arms | PASSIVE | — | empty hand: ATTACK_DAMAGE +3, ATTACK_SPEED −1.6, ENTITY_INTERACTION_RANGE +3; ELECTRIC_SPARK line eye→target, PLAYER_ATTACK_SWEEP pitch 1.8 | `hit` |
| projectile_launch_system | arms | ACTIVE | — / 160 (8 s) | hitscan to ≤ 64 blocks (refused under 4: "too close", no cooldown); SMOKE+FLAME trail, CROSSBOW_SHOOT + FIRECHARGE_USE, `level.explode(player, …, 2.5F, NONE)` | the existing `explosion` (power 2.5) |
| reinforced_tendons | legs | PASSIVE | — | SAFE_FALL_DISTANCE +2; airborne jump: CLOUD x8 ring, SMALL_GUST, BREEZE_JUMP | V's HasDoubleJump (+1) |
| fortified_ankles | legs | PASSIVE | — | SAFE_FALL_DISTANCE +4; charged jump (held ≥ 10 ticks, leaves ground ≤ 4 ticks after release): DUST_PLUME x12, GUST_EMITTER_SMALL, BREEZE_CHARGE | V's HasChargeJump (+1) |
| lynx_paws | legs | PASSIVE | — | `Player.getMovementEmission` → NONE; mob visibility x0.5 (shared with silent_steps, once) | Visibility x0.5 (shared, one modifier) |
| sandevistan | operating_system | ACTIVE | 120 / 500 (6 s / 25 s); with perk 160 / 360 | Speed II, server tick rate 10, DUST 0x33E6FF/0xFF3DF2 afterimages + END_ROD, BEACON_ACTIVATE 1.8 / BEACON_DEACTIVATE | `cyberfx sandevistan` → dilation 0.2, V ignored |
| berserk | operating_system | ACTIVE | 160 / 800 (8 s / 40 s) | Strength I, Resistance I, Jump Boost I, DUST ring r 2, ANGRY_VILLAGER, MACE_SMASH_GROUND_HEAVY + RESPAWN_ANCHOR_CHARGE, knock back Enemy mobs ≤ 3 blocks | `cyberfx berserk`. **Gated:** the CP status effect only if S verifies it (task S6); otherwise Minecraft-side only (jump boost still reaches V through `effect`) |
| cyberdeck | operating_system | PASSIVE | — | netrunning RAM | — |
| kerenzikov | nervous_system | AUTO | 40 / 200 (2 s / 10 s) | fires on using a bow, crossbow or trident while `Bridge.hostAirborne`; tick rate 10, END_ROD ring, BEACON_POWER_SELECT 0.6 | `cyberfx kerenzikov 2` → dilation 0.3 |
| optical_camo | integumentary | ACTIVE | 200 / 600 (10 s / 30 s) | Invisibility 200 (no particles, icon on); REVERSE_PORTAL + ILLUSIONER_MIRROR_MOVE; breaks on Steve's attack or a PLS shot (only the camo-given instance) | the existing `effect invisibility` → OpticalCamoPlayerBuffRare + SetInvisible |
| subdermal_armor | integumentary | PASSIVE | — | ARMOR +6, ARMOR_TOUGHNESS +2, ARMOR_EQUIP_NETHERITE on install | (protects against Night City damage through HostDamage) |
| kiroshi_optics | eyes | PASSIVE | — | scan range 80 (else 48); hostile outline: Gizmos cuboid stroke 0xC0FF2B45 w 2, fill 0x10FF2B45 around hostile proxies and Enemy mobs ≤ 32 blocks | Scan.reds `maxDistance` 80 |
| biomonitor | circulatory | AUTO | — / 1800 (90 s) | at ≤ 30 % max health: heal 8 + Regeneration I 120, HEART x6, AMETHYST_BLOCK_RESONATE, overlay message | — |
| second_heart | circulatory | AUTO | — / 6000 (5 min, persisted) | instead of death (not BYPASSES_INVULNERABILITY): full health, Regeneration II 100, Absorption I 600, entity event 35 (totem animation) | — |
| ram_upgrade | frontal_cortex | PASSIVE | — | max RAM +4 | — |

**Rules:**
- Arms modifiers apply only while the main hand is empty. The modifier ids are `cyberblock:cw_<id>`.
- **Perk interplay (one effect per source):**
  - double_jump: with any LEGS implant, SAFE_FALL_DISTANCE +4. It never touches HasDoubleJump.
  - sandevistan: upgrades the implant (8 s / 18 s). A double-tapped sprint also fires it, but only when both the perk
    and the implant are present.
  - optical_camo: re-cloaks after 2 s crouched and still, only with the implant.
  - gorilla_arms (perk) or the implant: one shove.
  - silent_steps or lynx_paws: one visibility modifier.
  - extra_ram and ram_upgrade add up (+4 each).
- **RAM:** `Ram.max(deck, extraRam, ramUpgrade) = deck ? 8 + 4·extra + 4·upgrade : 0`.
  `hasDeck = installed(cyberdeck) || (OS slot empty && a cyberdeck in the inventory)`.
- **Migration:** once per player, owners of the double_jump, sandevistan and optical_camo perks receive the
  reinforced_tendons, sandevistan and optical_camo **items** (never auto-installed).
- **Cooldowns** use level game time and are persisted in an attachment.
- **Creative:** installs are free and the screen lists every implant. Cooldowns still apply.

### 1.8 Keys (Minecraft `KeyMapping` ↔ the DLL's `binds`)

| Action | Default | VK | Minecraft mapping | lang key |
|---|---|---|---|---|
| Hack | G | 0x47 (71) | existing | existing |
| Skill tree | K | 0x4B (75) | existing | existing |
| Cyberware screen | U | 0x55 (85) | `key.cyberblock.cyberware` `InputConstants.KEY_U` | `key.cyberblock.cyberware` |
| Activate implant | Y | 0x59 (89) | `key.cyberblock.activate` `InputConstants.KEY_Y` | `key.cyberblock.activate` |

- **Clash rule** (identical in `VirtualKeys` and `keys::apply_binds`): a key outside the bindable set falls back to its
  default. If any two of the four resolved keys are equal, **all four** go back to G/K/U/Y.
- **Y gestures:** a tap (release within 8 client ticks) fires the selected implant. Holding Y for 8 ticks cycles the
  selection.
- U and Y are swallowed while forwarding. Cyberpunk's Y (SceneFastForward, CameraResetPitch) works with F9 off.
- The DLL binds log line is `binds: hack G, skills K, cyberware U, activate Y`.

### 1.9 Commands
- `/cyberblock perks free` reports the state. `/cyberblock perks free on|off` (permission 0; C, in `CyberMod`) sets a
  persistent per-player attachment `FREE_PERKS`. Perks are free when `isCreative() || FREE_PERKS`.
- `/cyberblock level` (permission 0; M, in `Cyberblock.onInitialize`) re-aligns to the ground under V. It fails with
  `cyberblock.level.no_host` when there is no host and `cyberblock.level.not_standing` when V is not standing.
- `/cyberblock kit` is unchanged. Brigadier merges the `cyberblock` literals.

### 1.10 Lang keys (A writes every one in `en_us.json`; others only reference them)
- **R3:**
  - `cyberblock.skills.bought_free` "Perk unlocked (free): %s"
  - `cyberblock.skills.free` "Perks are free"
  - `cyberblock.perks.free.on` "Free perks on: the skill tree costs no levels in this world"
  - `cyberblock.perks.free.off` "Free perks off: perks cost XP levels again"
  - `cyberblock.perks.free.creative` "Free perks off, but perks stay free while you are in creative"
- **R4:**
  - `cyberblock.level.done` "Ground aligned: offset %s m (was %s m). Blocks you placed elsewhere moved by less than 1 m."
  - `cyberblock.level.no_host` "Cyberpunk is not connected, or Cyberblock is off"
  - `cyberblock.level.not_standing` "V must stand on the ground (not in the air, a vehicle or a scene)"
- **R6:**
  - `item.cyberblock.<id>` for all 16 new ids.
  - `cyberware.cyberblock.<id>.mc` and `.cp` for all 17 ids.
  - `cyberblock.cyberware.slot.<slot>` for the 8 slots.
  - `key.cyberblock.cyberware` "Cyberware" and `key.cyberblock.activate` "Activate implant".
  - The `cyberblock.cyberware.*` messages: `title` "Cyberware", `installed` "Installed %s", `removed` "Removed %s",
    `replaced` "Installed %s (returned %s)", `selected` "Y fires %s", `none` "No active implant installed",
    `cooldown` "%s is cooling down (%s s)", `too_close` "Too close to fire", `not_owned` "You do not have that implant",
    `second_heart` "Second Heart restarted you", `biomonitor` "Biomonitor: emergency heal", `migrated` "Your perks now
    need cyberware: you received %s. Install them with U", `hud.active` "active", `tooltip.slot` "Slot: %s",
    `tooltip.cooldown` "Cooldown: %s s", `tooltip.install` "Install: right-click or the Cyberware screen (%s)",
    `screen.available` "Available", `screen.empty` "Nothing to install: craft cyberware or use /cyberblock kit".
  - `cyberblock.hack.no_deck` changes to "No cyberdeck: install one in the Operating System slot (U) or carry one".
  - `perk.cyberblock.{double_jump,sandevistan,optical_camo}.mc/.cp` are reworded to "upgrades your <implant>".

### 1.11 Log lines (the in-game checks grep for these)
- **DLL:**
  - `drive: ready (Teleport <param types>, raycast <param types>)`, or one `drive: disabled: <what> missing`.
  - `flight: start (glide|launch)`, `flight: landed (glide|launch) after X s`, `flight: over, V stays where it is`.
  - `flight: stopped, V is held, in a vehicle or Cyberblock is off`, `flight: no samples, holding V`,
    `flight: no samples for 1 s, V put on the ground`, `flight: V stopped short of Night City geometry`.
  - The per-flight summary `flight: N frames, M samples (median spacing a ms, max gap b ms), max step c m, clipped yes|no`.
  - `ground alignment: Minecraft y = Cyberpunk z - %.4f (k %d/64)`.
  - The binds line from section 1.8.
- **Scripts:**
  - `flight: ignored ... (<LockReason>)`.
  - `scan N: ok <class> <8-digit id> (requested|look-at|near crosshair)`.
  - `dmg instant kill (landing) not reported: flight`.
  - `cyberware: <id> on|off`, `cyberfx: <id> <s> s`, `berserk: Minecraft side only`.
- **Minecraft:**
  - `launch started at {} with {} m/s`.
  - `ground alignment: k {} -> {} (offset {} m) at Cyberpunk z {} ({})`.
  - `cyberware: installed {} in {}`, `cyberware: activate {}`.

---

## 2. Package D: DLL and conversion math (Opus)

### D1. Alignment math, source of truth (R4)
**Files:** `tools/cbmath.py`, `tools/gen_vectors.py`, `shared/vectors.json`, `tests/test_cbmath.py`.
- [ ] `cbmath.py`:
  - add `LEVEL_STEPS = 64` and `align_k(z)`;
  - give `cp_to_mc_pos`, `mc_to_cp_pos`, `mc_block_to_cp_aabb`, `down_hit_to_range` and `wall_hit_to_range` an `off`
    parameter (default 0.0 in Python only);
  - add `cp_dir_to_mc`;
  - make `mc_vel_to_cp` explicit;
  - make `cp_quat_to_mc_yaw_pitch` use `cp_dir_to_mc`;
  - use nearest rounding as in section 1.1.
- [ ] `gen_vectors.py`:
  - `down`/`wall` cases at k ∈ {0, 32, 57, 63}, plus noise cases: the hits (3.5, 2.5, 6.907 ± 1e-6) at k 57 give the
    same range;
  - `mc_pos`/`mc_block` cases at k ∈ {0, 57};
  - a new `align` set: the 7 cases of section 1.1.

  Regenerate `shared/vectors.json` (existing `down` results change, for example 12.25 now gives top 11).
- [ ] `test_cbmath.py`:
  - offset round trips (`math.isclose`), AABB with offset, nearest rounding and noise stability;
  - the `align_k` margin property over 10 000 random z (mapped fraction in [1.95 mm, 17.6 mm));
  - direction and quaternion independent of the offset;
  - the vector file matches truth, including `k` and `align`.
**Done:** `uv run pytest -q tests/test_cbmath.py` passes; `vectors.json` is regenerated and committed.

### D2. C++ offset everywhere (R4)
**Files:**
- source: `cbmath.hpp/.cpp`, `wire.hpp/.cpp`, `world.hpp/.cpp`, `snapshots.hpp/.cpp`, `probes.hpp/.cpp`, `natives.cpp`,
  `camera.cpp`;
- tests: `tests/test_cbmath.cpp`, `test_probes.cpp`, `test_world.cpp`, `test_events.cpp`, `test_messages.cpp`,
  `test_snapshots.cpp`.
- [ ] `cbmath`: the section 1.1 signatures with a **required** `off`; `cp_dir_to_mc`; explicit `mc_vel_to_cp`.
  `align_k` is not mirrored.
- [ ] `wire`: `cp_triple(m, key, off)` goes through `mc_to_cp_pos`. NEW `cp_vector(m, key)` goes through
  `mc_vel_to_cp`.
- [ ] `world`:
  - `std::atomic<int> g_levelK{0}`, `int level_k()`, `inline double level_offset(int k) { return k / 64.0; }`;
  - in `on_minecraft_message`: `hello` resets `k` to 0; `align` validates `k` (0..63; bad → `note_invalid("align: …")`,
    old `k` kept) and logs the section 1.11 line on a change;
  - `parse_event(m, off)` threads `off` into blocks, explosion, teleport, lead anchor, pull toward and knock pos;
  - `hit.impulse` uses `cp_vector`;
  - `make_dmg/make_npcs/make_pcells/make_scaninfo` take `off`; `make_cam(..., int levelK)` adds `lv`.
- [ ] `snapshots`: `parse_mobs/parse_proj/parse_flight(…, double off = 0.0)` offset positions only.
  `snap::on_minecraft_message` reads `world::level_offset(world::level_k())` once per message. `main.cpp` already calls
  world before snap, so an `align` takes effect before any later message.
- [ ] `probes::submit(center, down, wall, radius, off, now)`: the centre and the ranges go through the offset functions.
- [ ] `natives.cpp`:
  - `Mashup.levelK`. In `SubmitProbes`, under the lock, when `k` changed: `restart_ground()` and log;
  - pass `level_offset(level_k())` in Damage, SubmitNpcs, SubmitProjectileHits and ScanResult.
- [ ] `camera.cpp`: read `k` once per publish; position and feet go through `cp_to_mc_pos(…, off)`; facing and forward
  through `cp_dir_to_mc`; `make_cam(…, k)`; `set_host_pose` gets the offset position; the pose log prints `k`.
- [ ] After the change, grep for `cp_to_mc_pos`, `mc_to_cp_pos` and `cp_triple`: every call site matches the section 1.1
  table.
- [ ] Tests:
  - vector `k` fields; probe nearest-rounding expectations;
  - block z = by + off; explosion/teleport/lead/pull/knock + off; impulse unchanged;
  - `align` parsing, bad `k` rejected, `hello` resets `k`;
  - `make_*` with off and `cam.lv`; snapshot positions + off with velocities unchanged.
**Done:** `cb_tests.exe` passes. `pytest` agrees with the C++ suite on `vectors.json`.

### D3. Flight samples with timestamps and interpolation (R1, R5)
**Files:** `snapshots.hpp/.cpp`, `tests/test_snapshots.cpp`.
- [ ] `FlightSample` gains `bool launch` and `std::optional<double> ts`. `parse_flight` reads `kind` (only `"launch"`
  means launch) and `ts` (a bad one is ignored).
- [ ] While active, `Flight` keeps a ring of the last 32 `{sample, local}` entries:
  - with `ts`: `local = ts + offset`, where offset = the minimum of (arrival − ts) over the ring;
  - without `ts`: `local = arrival`.

  The ring is cleared on every active change and on `reset()`.
- [ ] NEW `Vec3 Flight::render_pos(now)` (locked, calls `expire`):
  - playout delay `D = clamp(1.5 × median spacing, 8 ms, 60 ms)` and `u = now − D`;
  - linear interpolation between the two samples around `u`;
  - past the newest sample, extrapolate `v × min(u − newest.local, 50 ms)`;
  - inactive: the landing spot.
- [ ] NEW `bool Flight::launch(now)`. Keep `pos()`.
- [ ] Tests:
  - linear motion with jittered arrivals and `ts`: error < 2 cm, and monotonic;
  - no `ts` still follows;
  - extrapolation capped at 50 ms;
  - inactive gives the landing spot;
  - `kind` parsing.
**Done:** `cb_tests` passes.

### D4. Follower state machine, pure (R1)
**Files:** `src/follow.hpp` (new), `src/follow.cpp` (new), `tests/test_follow.cpp` (new), `CMakeLists.txt`.
- [ ] Types: `cb::follow::Act {None, Move, Land, Ground, Stop}`;
  `In {gate, menu, active, timedOut, launch, target, feet, now}`; `Out {act, to, log}`.
  `Follower::step / applied / driving / seconds_since_end`, states Idle/Following/Holding.
- [ ] Constants: `kHold 1.0 s`, `kQuickLand 0.3 s`, `kNearLand 1.5 m`, `kClipMargin 0.3 m`, `kBodyLift 0.9 m`.
- [ ] Rules:
  - **Start:** Idle + gate + active + !menu → Following, Move(target), "flight: start (glide|launch)".
  - **Gate closes:** !gate while Following or Holding → Stop, no teleport, "flight: stopped …".
  - **Menu:** returns None and moves `m_start`/`m_holdSince` forward by the frame time (frozen timers).
  - **Active:** Move(target). Holding → Following, "flight: samples back".
  - **Landing** (!active && !timedOut):
    - Stop with "flight: over, V stays where it is" when `now − start < 0.3 s` or `|target − feet| < 1.5 m`;
    - otherwise Land(target).
  - **Timeout** (!active && timedOut): Move(m_last) for up to 1 s ("flight: no samples, holding V"), then
    Ground(m_last).
  - **Ending:** Land, Ground and Stop set `m_endedAt` and return to Idle.
- [ ] `CMakeLists.txt`: `src/follow.cpp` and `tests/test_follow.cpp` in `cb_tests` (`src/drive.cpp` comes in through
  the GLOB).
- [ ] Tests, one per rule:
  - a 5 s menu, then samples back, with no Ground;
  - timeout → Hold 1 s → Ground;
  - the gate closes → Stop with no move;
  - quick landing;
  - normal landing;
  - `seconds_since_end` is 999 before any flight.
**Done:** `cb_tests` passes.

### D5. Per-frame drive (R1, R5), game-facing
**Files:** `src/drive.hpp` (new), `src/drive.cpp` (new), `src/main.cpp`, `src/natives.hpp`, `src/natives.cpp`.
- [ ] API: `cb::drive::tick()` (main thread, every frame), `bool driving()` (an atomic mirror),
  `float seconds_since_end()`. `main.cpp on_running_update` calls `cb::drive::tick()` before `cb::camera::publish()`.
- [ ] Resolve RTTI lazily, with the `camera.cpp` pattern:
  - `gameTeleportationFacility::Teleport(objectToTeleport, position: Vector4, orientation: EulerAngles)`, through
    `GetSystem(GetClass("gameTeleportationFacility"))` with a fallback to `"gameITeleportationFacility"`;
  - `gameSpatialQueriesSystem::SyncRaycastByCollisionGroup(start, end, collisionGroup: CName, out result: physicsTraceResult, staticOnly, dynamicOnly) -> Bool`;
  - `entEntity::GetWorldPosition`, `entEntity::GetWorldForward`, static `Vector4::ToRotation`, and global
    `GetPlayer;GameInstance`.

  Log each function's parameter types once (`drive: ready …`):
  - if Teleport's first parameter is a weak handle, pass a WeakHandle; otherwise pass the Handle;
  - if anything is missing, log one `drive: disabled: …` and stay disabled (safe: `cam.fly` stays false, Minecraft
    calls every flight off within 0.5 s).
- [ ] Each frame:
  - **Gate:** `natives::active && !inVehicle && !held && flightAllowed && link().linked()` and the player resolves.
  - **Input:** build `In` from `flight()` (active, timed_out, render_pos, launch), V's feet and `natives::menuOpen`, then
    act on the Follower's answer.
  - **Move:** clip from `m_last` to `to` with two rays (n"Static", then n"Terrain") from +0.9 m to +0.9 m. On a hit,
    stop 0.3 m short and log once per flight. Then `Teleport(V, clipped, ToRotation(V.GetWorldForward()))`,
    `applied(clipped)` and `flightFollowing = true`.
  - **Land:** if the path to the spot is blocked, use the ground below the clipped point. Otherwise ground-snap: a ray
    down from spot + 1.5 m to spot − 1000 m (Static and Terrain, the higher hit wins), taken when the hit is above the
    spot or the spot is > 1 m above it. Teleport, `flightFollowing = false`, log "landed".
  - **Ground:** a ray down from `m_last`, teleport, `flightFollowing = false`, log.
  - **Stop:** `flightFollowing = false` only.
  - **Summary:** the per-flight summary line at every end.
- [ ] `natives.hpp`: `inline std::atomic<bool> flightAllowed{false};`.
  `natives.cpp`:
  - register `Cyberblock_SetFlightAllowed`, `Cyberblock_FlightDriving` and `Cyberblock_SecondsSinceFlight`;
  - remove `Cyberblock_SetFlightFollowing`;
  - `SetActive(false)` also clears `flightAllowed`;
  - hello and landing still clear `flightFollowing`.
- [ ] `link_smoke` / build only. The behaviour is in-game.
**Done:**
- `cmake --build … Release` and `cb_tests` pass.
- The `needsInGame` list gets checks F-1 to F-3 and L-1 to L-7 (task P4) and the `drive: ready` signature line.
- The risk notes go in the report: Teleport from OnUpdate, a static call to `ToRotation` with a null context, and yaw
  stickiness on fast mouse turns (fix: read `GetWorldOrientation` just before the call).

### D6. Cyberware events and four binds (R6)
**Files:** `keys.hpp/.cpp`, `input.cpp`, `world.hpp/.cpp`, `tests/test_keys.cpp`, `tests/test_world.cpp`,
`tests/test_events.cpp`, `natives.cpp` (comments).
- [ ] `keys`:
  - `kU = 0x55`, `kY = 0x59`;
  - `Binds{hack = kG, skills = kK, cyberware = kU, activate = kY}`;
  - `BindsUpdate` gains `cyberwareRejected` and `activateRejected`;
  - the section 1.8 clash rule;
  - `is_swallowed` covers all four;
  - `apply_binds` reads the four names, and a missing name keeps the current key.
- [ ] `input.cpp`:
  - `g_bindCyberware` and `g_bindActivate` atomics;
  - `binds()` returns 4;
  - `key_name` returns `"cyberware"` and `"activate"`;
  - the binds log line from section 1.8.
- [ ] `world`:
  - `kCyberwareIds[17]` (section 1.7 order), `kCyberFxIds{sandevistan, kerenzikov, berserk}`,
    `kMaxCyberwareEntries = 32`, `EventKind::Cyber` and `EventKind::CyberFx`;
  - `parse_cyberware` (like `parse_perks`) and `parse_cyberfx` (0..`kMaxPerkFxSeconds`);
  - `event_name` gives `"cyber"` and `"cyberfx"`;
  - `Cyber` is kept while inactive and outranks like `perk`.
- [ ] Tests:
  - four-key `apply_binds`: every single-key refusal, two clashes, a missing name;
  - U and Y swallowed;
  - the cyberware list, bad entries dropped, the `cyberfx` range, `Cyber` kept while inactive and `CyberFx` dropped.
**Done:** `cb_tests` passes.

---

## 3. Package S: redscript (Opus)

### S1. Entity id hashing (R2, main cause)
**Files:** `cp/scripts/Cyberblock/Ids.reds` (new), `Scan.reds`, `Kills.reds`, `Puppets.reds`, `Effects.reds`,
`Npcs.reds`, `tests/test_reds_ids.py` (new).
- [ ] `CyberblockIds.Hash` (section 1.5). Its header cites Codeware.Global.reds 42199-42201 and the log evidence
  (hit 10019101 against withheld 2668059858640).
- [ ] Replace the call sites:
  - Scan.reds:143 `let hash = CyberblockIds.Hash(object);`;
  - Kills.reds:45;
  - Puppets.reds:212 `IsDefined(attacker) ? CyberblockIds.Hash(attacker) : 0ul`;
  - Effects.reds:290;
  - Npcs.reds:111 (already correct; changed for uniformity).

  Update the header comments.
- [ ] `tests/test_reds_ids.py`: for every `cp/scripts/Cyberblock/*.reds` with `//` comments stripped, each
  `EntityID\.ToHash\(\s*([^)]*)\)` argument must be a bare identifier. On failure it names `file:line`.
**Done:** `uv run pytest -q tests/test_reds_ids.py` and `compile_check.py` pass.

### S2. Scan visibility (R2)
**Files:** `Scan.reds`, `Events.reds`.
- [ ] `CyberblockScan.Scan` returns a `String`: `"scan N: ok <class> <id> (requested|look-at|near crosshair)"` on
  success, and `""` when the failure path has already logged.
- [ ] `Events.reds` passes it to the existing 1-per-second `Note`.
**Done:** compile_check passes; a needsInGame check that the scan log shows an 8-digit id.

### S3. Flight policy, landing safety (R1, R5)
**Files:** `Flight.reds`, `Natives.reds`, `Health.reds`.
- [ ] `Natives.reds`: the section 1.3 block (add three natives, remove `SetFlightFollowing`, update comments, including
  EventName's `cyberware id | cyberfx id`).
- [ ] Rewrite `Flight.reds` small (section 1.5).
  - **Removed:** `CyberblockFlightFrame`, the generation/lastFrame loop, `Frame`, `Land`, `Clip`, the camera-only code,
    `SpawnCamera`, `MoveCamera`, `SetCameraOnly` and `IsCameraOnly`.
  - **Tick:** `allowed = live && IsDefined(player) && !VehicleComponent.IsMountedToVehicle(game, player) && !CyberblockEffects.PlayerLocked(game, player)`,
    then `Cyberblock_SetFlightAllowed(allowed)`. When `Cyberblock_FlightActive() && !allowed`, log once
    `flight: ignored … (<LockReason>)`.
  - **Clear:** `SetFlightAllowed(false)` first. Then, if `FlightDriving()`, teleport V to `GroundBelow` (covers F9,
    Lost, before-save and session end).
  - **GroundBelow** becomes public static.
- [ ] Landing wraps in `Flight.reds`: `@wrapMethod(LocomotionAirDecisions)` on `ToHardLand`, `ToVeryHardLand`,
  `ToDeathLand` and `ToSuperheroLand` returns `false` while `CyberblockFlight.SuppressFall(game)`.
  - Take the exact protected/const signatures from the 2.31 scripts and the compile checker.
  - `ToRegularLand` is not wrapped. If the in-game check shows V hanging in Fall on the ground, record the
    `wrappedMethod || SuppressFall && IsOnGround()` follow-up in needsInGame.
- [ ] `Health.reds ReportInstantKill`: an AttackID of `Attacks.HardLanding`, `VeryHardLanding` or `DeathLanding` while
  `SuppressFall` → log `dmg instant kill (landing) not reported: flight` and return. Confirm the record names in tweakdb.
- [ ] Remove every caller of the removed functions (`Cyberblock.reds` keeps `Tick`/`Clear`/`Center`; `Probes.reds` keeps
  `IsFlying`/`Center`).
**Done:** compile_check passes. needsInGame: F-1 to F-3, L-1 to L-7.

### S4. Collision z and the pearl ground snap (R4)
**Files:** `Collision.reds`, `Effects.reds`.
- [ ] `Collision.reds:76` stores the exact `aabbMin.Z` (x and y stay rounded). `Key()` uses `FloorF(aabbMin.Z) + 32768`.
  Header: "integer x/y, z = block y + k/64".
- [ ] `Effects.reds Teleport` snaps to the Night City floor when it is within (feet − 0.6, feet + 1], using
  `CyberblockFlight.GroundBelow`.
**Done:** compile_check passes; needsInGame: placed blocks sit on the floor after alignment; pearl landings do not sink.

### S5. Cyberware on V (R6)
**Files:** `Cyberware.reds` (new), `Perks.reds`, `PlayerState.reds`, `Events.reds`, `Scan.reds`, `Cyberblock.reds`.
- [ ] `CyberblockCyberwareService extends ScriptableService`:
  - fields `ids`, `on`, `owner`, the `doubleJump`/`chargeJump`/`visibility` modifiers, slow-mo and berserk state;
  - `Known(id)` holds the 17 ids.
- [ ] `CyberblockCyberware.Fx`:
  - `sandevistan`: `SetIgnoreTimeDilationOnLocalPlayerZero(true)` + `SetTimeDilation(n"cyberblockSlowMo", 0.2, s + 1)`;
  - `kerenzikov`: the same with 0.3; a newer call replaces the factor and the end;
  - `0` seconds ends the effect;
  - `berserk`: see S6.

  Log `cyberfx: <id> <s> s`.
- [ ] `Tick` adds these through the Perks.reds owner/RemoveModifiers pattern, and handles slow-mo/berserk expiry on
  engine time:
  - HasDoubleJump +1 for reinforced_tendons;
  - HasChargeJump +1 for fortified_ankles;
  - Visibility x0.5 when `Has(lynx_paws) || CyberblockPerks.Has(silent_steps)`.

  `Clear` removes everything and unsets the dilation.
- [ ] `Perks.reds`: `Fx` delegates to `CyberblockCyberware.Fx`. Remove its doubleJump/silentSteps modifiers and its
  slow-mo fields.
- [ ] `PlayerState.reds ZeroAbilities` keeps an ability only when:
  - HasDoubleJump && reinforced_tendons;
  - or HasChargeJump && fortified_ankles;
  - or (dodge perk && HasDodge/HasDodgeAir).

  Everything else stays zeroed (`CanUseOpticalCamo` too; camo comes through `effect`).
- [ ] `Events.reds`:
  - `n"cyber"` → `Set(game, EventName, RoundF(EventAmount) == 1)`, also in the inactive branch like `n"perk"`;
  - `n"cyberfx"` → `Fx`, live only.
- [ ] `Scan.reds`: `query.maxDistance = CyberblockCyberware.Has(game, n"kiroshi_optics") ? 80.0 : 48.0`.
- [ ] `Cyberblock.reds`: Tick calls `CyberblockCyberware.Tick` after Perks; Clear calls `CyberblockCyberware.Clear`.
**Done:** compile_check passes. needsInGame: double jump only with tendons, charge jump with ankles, slow-mo on Y.

### S6. Berserk gate (R6)
- [ ] Dump `BaseStatusEffect.BerserkPlayerBuff` from tweakdb, using a tool outside the repo (`%TEMP%\cbs2\tools`, if
  present, or the compile checker's tweakdb reader). Check locomotionTransitions (superhero fall forced for Berserk)
  and damageManager.
- [ ] Apply it only if the record carries no stat packages beyond VFX and the forced superhero fall cannot stomp NPCs.
  Then: `StatusEffectHelper.ApplyStatusEffect(V, t"BaseStatusEffect.BerserkPlayerBuff", …)` on Fx, removed on expiry.
  Otherwise log `berserk: Minecraft side only` and record the reason.
**Done:** a decision written in the S5 file header and in the report.

---

## 4. Package M: Minecraft world (Opus)

### M1. Bridge and host state (contracts for C and R4)
**Files:** `Bridge.java`, `client/HostState.java`, `client/HostLink.java`, `test/.../client/HostStateTest.java`,
`ProxyEntity.java`.
- [ ] `Bridge.java`: the exact section 1.6 additions (commit first, so that C can mirror them).
- [ ] `HostState.update`:
  - `lv` (absent → `Bridge.alignK`) and `dy = (lv − Bridge.alignK) / 64`, added to `y` and `py`;
  - `Bridge.hostFeetCpZ = rawPy + lv / 64`, `Bridge.hostStands = !air && !held && !fly`,
    `Bridge.hostAirborne = air`.

  `setKey("jump", true)` increments `Bridge.hostJumpPresses`.
- [ ] `ProxyEntity`:
  - `hostile` becomes the synced `DATA_HOSTILE` (defineSynchedData) with a `hostile()` accessor;
  - `Bridge.hostileProxy = e -> e instanceof ProxyEntity p && p.hostile()` is set at init;
  - the gorilla shove is `perk("gorilla_arms") || Bridge.installedCyberware.contains("gorilla_arms") && attacker.getMainHandItem().isEmpty()`,
    applied once.
- [ ] Tests: `HostStateTest` (the `lv` correction, `hostFeetCpZ`, jump counter).
**Done:** `gradlew test build` passes in the worktree.

### M2. Ground alignment (R4)
**Files:** `GroundAlign.java` (new), `Wire.java`, `WorldBridge.java`, `Cyberblock.java`, `test/.../GroundAlignTest.java`
(new), `test/.../WireTest.java`.
- [ ] `GroundAlign extends SavedData` (the PlacedBlocks pattern):
  - CODEC `{k:int}`, `TYPE` id `cyberblock:align`, `UNSET = -1`;
  - `k()`, and `set(k)` calls `setDirty` on a change;
  - `static int kOf(double cpZ)` (section 1.1) and `static double offset(int k)`.
- [ ] `Wire.align(k)` → `{"t":"align","k":%d}`.
- [ ] `WorldBridge`:
  - `attach()` loads `GroundAlign`, sets `Bridge.alignK = max(k, 0)`, and sends `align` when linked, before the resync;
  - `detach()` resets; `hostHello()` sends `align`; NEW `linked()`;
  - `level(server, why)`: compute `kOf(Bridge.hostFeetCpZ)`, set it, send `align`, then `resyncDue = true` on the
    server thread, and log (section 1.11);
  - `tick()`: when active, linked, `k == UNSET`, `Bridge.hostStands`, `!Bridge.hostInVehicle` and the feet z is
    finite → `level(s, "first activation")`. This covers new and existing worlds.
- [ ] `Cyberblock.onInitialize` registers `/cyberblock level` (section 1.9), with sendSuccess/sendFailure and the
  section 1.10 keys.
- [ ] Tests:
  - `GroundAlignTest`: the 7 literal cases, plus `vectors.json align` when present (skip when absent);
  - `WireTest.align`.
**Done:** tests pass; needsInGame: a new world aligns on first activation (log line), and `/cyberblock level` moves the
blocks.

### M3. Server-side scan pick (R2)
**Files:** `ProxyAim.java` (new), `ProxyManager.java`, `Cyberblock.java`, `test/.../ProxyAimTest.java` (new).
- [ ] `ProxyAim.score(eye, dir, reach, box, margin)` gives the angle in degrees to the box centre, or NaN when the
  segment misses `box.inflate(margin)` (`contains(eye)` gives 0). `HUMAN_MARGIN = 0.3`, `VEHICLE_MARGIN = 0.5`.
- [ ] `ProxyManager.aimedAt(eye, dir, reach)`: the lowest finite score over live proxies, ties to the nearer one.
  `Cyberblock.java` sets `Bridge.proxyAimedAt = ProxyManager::aimedAt`.
- [ ] Tests:
  - centre hit;
  - 0.25 m off the box still picked;
  - a miss gives NaN;
  - beyond reach gives NaN;
  - the smaller angle wins.
**Done:** tests pass.

### M4. Wind-charge launch and the flight hand-off (R5, R1 Minecraft side)
**Files:** `client/Flight.java`, `client/mixin/ClientPacketListenerMixin.java` (new),
`src/client/resources/cyberblock.client.mixins.json`, `FlightRules.java`, `Wire.java`, `mixin/ServerExplosionMixin.java`,
`test/.../FlightRulesTest.java`, `test/.../WireTest.java`, `client/PlayerSync.java` (only if needed).
- [ ] `ClientPacketListenerMixin`:
  - `handleExplosion` TAIL: `packet.playerKnockback().ifPresent(v -> Flight.impulse(v, true))`;
  - `handleSetEntityMotion` TAIL: for the local player's id, `Flight.impulse(packet.movement(), false)`.

  Confirm the names in the 26.3 sources.
- [ ] `FlightRules`:
  - `LAUNCH_MIN_UP = 0.25`, `LAUNCH_MIN_SPEED = 0.4`, `MOTION_LAUNCH_MIN_UP = 0.6` (blocks/tick),
    `LAUNCH_SETTLE_NANOS = 100 ms`;
  - `launch(vx, vy, vz, explosion)` (non-finite gives false);
  - `launchLanded(onGround, inWater, sinceStartNanos)`.
- [ ] `Flight`:
  - `enum Mode {GLIDE, LAUNCH}`, `static final long EPOCH = System.nanoTime()`;
  - `impulse(v, explosion)` keeps a pending kick (summed for explosions, last one wins for motion);
  - `tick()` takes the kick first. When not driving, valid, and the host is not held, in a menu or in a vehicle, and
    the player is alive, not a spectator and not a passenger: `startLaunch`.
- [ ] `startLaunch`:
  - creative `flying = false` + `onUpdateAbilities`;
  - `setOnGround(false)` + StatusOnly;
  - set the position at V's feet, lifted out of collision by at most 1 block in 1/16 steps (`level().noCollision`);
  - `xo/yo/zo`, `setDeltaMovement(kick)`, gravity on;
  - `driving = true`, `mode = LAUNCH`, `follow.start`, `launchStart`;
  - log `launch started at {} with {} m/s`.
- [ ] `drive()`:
  - touchdown is `FlightRules.touchdown` (GLIDE) or `launchLanded` (LAUNCH);
  - in LAUNCH, a jump press with an elytra, a fall speed > 1 m/s and clearance ≥ 3 runs `tryToStartFallFlying` +
    `START_FALL_FLYING` and switches to GLIDE, with no new handshake;
  - `HostDamage.fallFreeUntil` is set as for a glide.
- [ ] `frame()` sends `Wire.flight(…, kind, (System.nanoTime() − EPOCH) / 1e6)`.
- [ ] `followsCam()` is also true as soon as a pose that arrived after touchdown has `!fly` and feet within 1.5 m of
  the landing spot. The 0.5 s settle stays as the upper bound.
- [ ] The double-jump flag (Flight.java:90) becomes `Bridge.installedCyberware.contains("reinforced_tendons")`, and the
  `Perk` import goes.
- [ ] `ServerExplosionMixin`: `@ModifyExpressionValue` on `Abilities.flying` in `hurtEntities` returns
  `flying && !Cyberblock.active`, so a creative-flying Steve gets knockback while active.
- [ ] Tests:
  - `FlightRulesTest`: launch thresholds, non-finite, `launchLanded`;
  - `WireTest`: `flight` with `kind` and `ts`.
**Done:** tests and build pass. A smoke run on port 25721 against `tools/fakehost.py` (after P's update, or with a
local copy) starts a launch from `/summon wind_charge` or a self-thrown charge, and logs `launch started`. needsInGame:
L-1 to L-7.

---

## 5. Package C: Minecraft cyber (Opus)

Mirror the section 1.6 Bridge locally (never commit it). Reference lang keys only by the section 1.10 names (A writes
the text).

### C1. Client scan through the server pick (R2)
**Files:** `client/cyber/ScanClient.java`, `test/cyber/ScanTest.java`.
- [ ] `pick()` returns only the ray: camera position, normalized forward, and `HostState.aimLimit(range(player))`.
  `range` is 80 with kiroshi_optics installed, else 48.
- [ ] `send()` hops to the server thread and there:
  - `e = Bridge.proxyAimedAt.at(…)`, then `target = e == null ? 0 : Bridge.proxyIdOf…`;
  - the throttle runs there (reset on release with `server.execute(throttle::reset)`);
  - `entityOf.put`, then `CyberWire.scan`.

  Update the ScanThrottle threading comment. Keep the unlock-on-miss.
**Done:** `ScanTest` passes.

### C2. Free perks (R3)
**Files:** `cyber/CyberData.java`, `cyber/PerkShop.java`, `cyber/CyberMod.java`, `client/cyber/SkillTreeScreen.java`,
`test/cyber/PerkShopTest.java`.
- [ ] `FREE_PERKS` attachment (persistent, copyOnDeath, synced targetOnly, default false) and
  `CyberData.freePerks(Player)`.
- [ ] `PerkShop.check(perk, ranks, levels, free)`: OWNED/LOCKED first, then
  `free || levels >= cost ? OK : TOO_POOR`. `buy()` takes no levels when free, and sends `cyberblock.skills.bought_free`.
- [ ] `/cyberblock perks free [on|off]` (section 1.9).
- [ ] `SkillTreeScreen`: grey cost labels, and the top-right text becomes `cyberblock.skills.free` when free.
- [ ] Tests: existing calls get `false`; free gives OK at 0 levels; free does not bypass LOCKED; free does not re-buy
  OWNED.
**Done:** tests pass. needsInGame: in survival with 0 levels, `/cyberblock perks free on` then buy; the setting survives
a reload.

### C3. Catalogue, rules, items, attachments (R6)
**Files:** `cyber/Cyberware.java`, `CyberSlot.java`, `CyberwareItem.java`, `CyberwareRules.java` (all new),
`CyberItems.java`, `CyberData.java`, `Ram.java`, `test/cyber/CyberwareRulesTest.java` (new), `RamTest.java`.
- [ ] `Cyberware` enum (section 1.7: slot, kind, duration, cooldown, `id()`, `byId`, `item()`) and `CyberSlot`
  (capacity, `langKey`, `key(n)`).
- [ ] `CyberwareItem` (stacksTo 1, RARE):
  - `use` on the server calls `Ripperdoc.install` and returns SUCCESS_SERVER; on the client it returns CONSUME;
  - `appendHoverText`: slot, `.mc`, `.cp`, cooldown, and the install line with `Component.keybind`.
- [ ] `CyberItems`:
  - a `CYBERWARE` map; `CYBERDECK` becomes the cyberdeck `CyberwareItem` with the same id;
  - every implant goes into the creative tab after the hacks;
  - mantis_blade and gorilla_gauntlet stay registered.
- [ ] `CyberData`: the `CYBERWARE`, `CYBER_READY`, `CYBER_SELECTED` and `CYBER_MIGRATED` attachments, with helpers
  `installed`, `has`, `readyAt`, `ready`. `hasDeck` and `maxRam` follow section 1.7.
- [ ] `CyberwareRules` (pure):
  - `install` (first free slot, else replace slot 0; ALREADY);
  - `activeOrder`, `defaultSelected`, `next`;
  - `cooldownTicks/durationTicks` with perks;
  - `biomonitor(hp, max)`, `chargeJump(held, sinceRelease)`, `remainingFraction`.
- [ ] Tests:
  - capacities, replace, ALREADY, order and cycling;
  - perk-adjusted times, the biomonitor threshold, chargeJump;
  - the id list equals section 1.7 exactly;
  - `RamTest` with the upgrade.
**Done:** tests pass.

### C4. Ripperdoc, effects, mixins, perk refactor, kit (R6)
**Files:**
- `cyber/Ripperdoc.java` (new), `CyberwareEffects.java` (new);
- `PerkEffects.java`, `PerkRules.java`, `CyberMod.java`, `CyberServer.java`, `CyberWire.java`, `Kit.java`;
- `cyber/mixin/CyberLivingEntityMixin.java`, `cyber/mixin/CyberPlayerMixin.java` (new), `cyberblock.cyber.mixins.json`;
- tests: `test/cyber/KitTest.java`, `test/cyber/CyberWireTest.java` (new).
- [ ] `Ripperdoc` (server thread):
  - `install` (survival consumes the item and returns a replaced one with `placeItemBackInInventory`; creative is
    free), `remove`, `select`, `cycle`;
  - VFX `ARMOR_EQUIP_NETHERITE` + `ELECTRIC_SPARK` x10, then an overlay message;
  - `sendCyberware` → `CyberWire.cyberware`, and `Bridge.installedCyberware` is replaced;
  - `migrate` (section 1.7).
- [ ] `CyberwareEffects`:
  - **Shared slow controller:** `slow(server, ms)`/`endSlow`, one wall-clock end; tick rate 10 while any slow-mo
    runs; restored on reset, server stop and disconnect.
  - **Modifiers:** `apply(player)` (arms only with an empty main hand, checked each tick).
  - **Tick:** biomonitor, camo expiry, Sandevistan afterimages, slow-mo expiry.
  - **activate(player, aim):** the selected or default implant; `none`/`cooldown`/`too_close` messages; dispatch per
    section 1.7; `CYBER_READY`; `cyberfx` where listed.
  - **Hooks:** `kerenzikov(player)`, `onAttack(player, target)` (arms VFX, camo break), `reset`.
- [ ] `PerkEffects`/`PerkRules`: Sandevistan and tick-rate code move to `CyberwareEffects`. Perk effects follow the
  section 1.7 interplay rules. `CyberWire.perkfx` is removed. Add `cyberware(Set)`, `cyberfx(id, s)` and `binds(int[4])`.
- [ ] `CyberMod`:
  - `AttackEntityCallback` (server, PASS);
  - `UseItemCallback` for Kerenzikov (BowItem, CrossbowItem, TridentItem with `Bridge.hostAirborne`);
  - JOIN → migrate + `sendCyberware`; DISCONNECT → all off + `Bridge.installedCyberware = Set.of()`;
  - the host-hello listener → `sendCyberware`; AFTER_RESPAWN → `apply`.
- [ ] Mixins:
  - `CyberLivingEntityMixin`: Second Heart on `checkTotemDeathProtection` RETURN (cancellable, `require = 1`); silent
    visibility from silent_steps or lynx_paws, applied once;
  - `CyberPlayerMixin`: `getMovementEmission` → NONE with lynx_paws.
- [ ] `Kit`:
  - the `INSTALL = -2` slot sentinel (install when there is room, else MAIN);
  - INSTALL entries: cyberdeck, kiroshi_optics, reinforced_tendons, biomonitor;
  - MAIN entries: sandevistan, optical_camo, projectile_launch_system;
  - mantis_blade and gorilla_gauntlet leave the kit.
- [ ] Tests:
  - `KitTest`: INSTALL entries; the worst case fits in 27 main slots;
  - `CyberWireTest`: the cyberware, cyberfx and binds JSON shapes.
**Done:** tests and build pass.

### C5. Screen, HUD, keys (R6)
**Files:** `client/cyber/CyberKeys.java`, `VirtualKeys.java`, `CyberLink.java`, `CyberClient.java`,
`CyberwareScreen.java` (new), `CyberwareHud.java` (new), `CyberwareClient.java` (new), `test/cyber/VirtualKeysTest.java`.
- [ ] `CyberKeys`:
  - the U and Y mappings; `binds()` and `note()` over all four;
  - U opens `CyberwareScreen` when no screen is open;
  - the Y tap/hold rule (section 1.8), run through `server.execute(() -> CyberwareEffects.activate(player, aim) / Ripperdoc.cycle(player))`;
  - the PLS aim is computed on the client along the camera ray (EntityPickMixin's method, `HostState.aimLimit(64)`,
    `level.clip` + `ProjectileUtil.getEntityHitResult`);
  - the pending tap is cleared when Cyberblock goes inactive.
- [ ] `VirtualKeys`: `DEFAULTS {G, K, U, Y}`, `binds(int...)` and `clash(int...)` with the section 1.8 rule.
- [ ] `CyberLink.key` handles `"cyberware"` and `"activate"`. The host's cyberware key closes `CyberwareScreen`.
- [ ] `CyberwareScreen` (not a pause screen, `textures/gui/cyberware_bg.png` 256x192):
  - left side: 8 category boxes in 2 columns, with frames per capacity;
  - right side: the available cyberware from the inventory (in creative, all of it), paged at 24;
  - left-click installs or removes; right-click on an ACTIVE implant selects it (a yellow Y marker);
  - tooltips; the note line.
- [ ] `CyberwareHud` (after HOTBAR, x = centre + 99, y = height − 22): one 16 px icon per ACTIVE/AUTO implant in
  `activeOrder`, with a 16x2 cooldown bar (grey to cyan), a magenta bar while active, and a yellow frame on the Y target.
- [ ] `CyberwareClient.tick`:
  - legs VFX from `Bridge.hostJumpPresses` (keep your own last value), `Bridge.hostAirborne` and
    `HostState.jumpDown()`;
  - Kiroshi Gizmos outlines through `Bridge.hostileProxy` and `Enemy` mobs.
- [ ] Tests: `VirtualKeysTest` (four-key clash and fallback, the same cases as `test_keys.cpp`).
**Done:** tests and build pass. A smoke run on port 25722: U opens the screen, a kit install shows on the HUD, and Y
fires Sandevistan (tick rate 10, log).

---

## 6. Package A: art, assets, lang, recipes (Sonnet)

### A1. Procedural art
**Files:** `art/gen_item_art.py`, outputs copied to `mc/src/main/resources/assets/cyberblock/textures/item/<id>.png` and
`textures/gui/cyberware_bg.png`.
- [ ] Add 16 sprite functions with the existing Sprite/mask helpers, one per new id: blade pair, fist, coiled wire, arm
  tube, tendon, ankle brace, paw, hourglass chip, red skull chip, spine, camo hex, plated skin, eye lens, heart monitor,
  double heart, RAM stick.
- [ ] Add `cyberware_bg()` 256x192: a notched panel with a line-art body silhouette.
**Done:** the script runs, the outputs are committed, and the art is original and procedural.

### A2. Item assets and recipes
**Files:** `assets/cyberblock/items/<id>.json`, `models/item/<id>.json` (generated, layer0),
`data/cyberblock/recipe/<id>.json` for the 16 new ids. The cyberdeck recipe is unchanged.
- [ ] Shapeless recipes, category equipment. Each one has redstone + copper_ingot plus:

  | id | extra ingredients |
  |---|---|
  | mantis_blades | diamond x2, iron_ingot x2 |
  | gorilla_arms | iron_block, piston |
  | monowire | string, lightning_rod, iron_ingot |
  | projectile_launch_system | tnt, crossbow, iron_ingot |
  | reinforced_tendons | slime_ball x2, rabbit_foot |
  | fortified_ankles | piston, iron_ingot x2 |
  | lynx_paws | white_wool x2, rabbit_hide |
  | sandevistan | clock, sugar, gold_ingot |
  | berserk | blaze_powder, gold_ingot, iron_ingot |
  | kerenzikov | clock, feather |
  | optical_camo | fermented_spider_eye, glass_pane |
  | subdermal_armor | iron_ingot x3, leather |
  | kiroshi_optics | spyglass, ender_eye |
  | biomonitor | glistering_melon_slice, glass_bottle |
  | second_heart | golden_apple, redstone_block, iron_ingot |
  | ram_upgrade | cyberblock:ram_shard x2, gold_ingot |

### A3. Lang and the assets test
**Files:** `assets/cyberblock/lang/en_us.json`, `test/cyber/CyberAssetsTest.java`.
- [ ] Every section 1.10 key. The `.mc`/`.cp` texts follow the section 1.7 table, in plain player language (what it
  does in Minecraft, and what V gets in Night City).
- [ ] `CyberAssetsTest`:
  - `items()` adds the 16 new ids as a literal list (no dependency on C's enum);
  - every Cyberware id has `cyberware.cyberblock.<id>.mc/.cp`;
  - `guiTexturesAreThere` adds `cyberware_bg`;
  - all R3/R4/R6 keys are present.
**Done:** `gradlew test` passes for `CyberAssetsTest` in the worktree (other tests may need C's code; run
`--tests dev.cyberblock.cyber.CyberAssetsTest`).

---

## 7. Package P: protocol, tools and docs (Sonnet; integrator docs)

### P1. Protocol
**Files:** `shared/protocol.md`, `tests/test_protocol_md.py`.
- [ ] Line 5 (conversion with `k/64`), the Block cells line (min z = by + k/64), and the units bullet (positions are
  offset; velocities and directions are not).
- [ ] Rows and jsonl examples from section 1.2:
  - `align`, `cyberware`, `cyberfx`;
  - `flight` `kind`/`ts`;
  - `binds` with four keys;
  - `cam.lv` and the new meaning of `cam.fly`;
  - `key` `cyberware`/`activate`;
  - validation ranges.
- [ ] `test_protocol_md.py`: the new types in the table sets, VALIDATORS for `align` (int 0..63), `cyberware`,
  `cyberfx` and four-key `binds`, and `lv` in the cam field set.
**Done:** `uv run pytest -q tests/test_protocol_md.py` passes.

### P2. Fake host
**Files:** `tools/fakehost.py`, `tests/test_fakehost.py`.
- [ ] Send `cam fly:true` while following a `flight`; accept `kind`/`ts`; print `align`, `cyberware` and `cyberfx`;
  ignore the align-triggered resync. `--layer6` also presses U and Y (`key k:"cyberware"/"activate"`).
**Done:** `uv run pytest -q` passes.

### P3. Player guide (`docs/HOW-TO-PLAY.md`)
- [ ] Section 3, key table: add U (Cyberware screen) and Y (tap fires, hold 0.4 s switches; Cyberpunk's Y works with F9
  off). The rebinding section covers four keys and the all-reset clash rule.
- [ ] Section 5:
  - **Layer 4:** wind-charge launch (own, others' and breeze charges, mace burst; launch then glide; V follows every
    frame).
  - **Layer 6 netrunning, updated:**
    - the deck comes from the OS slot, or from the bag while the OS slot is empty;
    - "target gone" now only means dead or despawned;
    - Kiroshi gives an 80 m scan.
  - **NEW "Cyberware" chapter:**
    - the screen and the slots;
    - the full section 1.7 table in player words;
    - Y and the HUD bars;
    - perk upgrades and the migration;
    - crafting, the kit, creative.
- [ ] Section 6:
  - free perks (creative, `/cyberblock perks free on|off`, per world);
  - `/cyberblock level`, and the automatic alignment on first activation (blocks elsewhere move by less than 1 m).
- [ ] Section 8 troubleshooting: blocks a little above or below the floor elsewhere → stand there and run
  `/cyberblock level`; a glide that stays put → look for `drive: disabled` in Cyberblock.log.

### P4. Test plan (`docs/TESTING-PLAN.md`)
- [ ] A new top section, "Re-test 2 (do this first)", above the current re-test. Each step has an action, the expected
  result and the log line to grep (section 1.11):
  - **R1:**
    - F-1 smoothness: the summary line shows a median spacing ≤ 17 ms and a max step < 1 m at 20 m/s;
    - F-2b turning while gliding;
    - F-3 landing within a frame of touchdown, with no `dmg fall` and no instant kill;
    - L-7 an Esc menu mid-flight for 5 s.
  - **R2:** scan a pedestrian, see an 8-digit id, memory_wipe ok.
  - **R3:** free perks in survival at 0 levels; the setting survives a reload.
  - **R4:** the first-activation align log; a block placed on the street sits on it; `/cyberblock level`.
  - **R5:**
    - L-1 own charge in survival;
    - L-2 the same in creative;
    - L-3 another player's or a breeze's charge;
    - L-4 mace wind burst;
    - L-5 launch then glide;
    - L-6 a launch while V sits or drives → no launch.
  - **R6:** each of the 17 implants (install with U, effect, VFX, Cyberpunk effect, HUD cooldown); the double jump only
    with tendons; Second Heart; Biomonitor; four-key rebinding.
- [ ] The appendix gets the needsInGame lists from the package reports (the integrator pastes them in).

### P5. Notes and spec
- [ ] `docs/notes/flight-spike.md`: the method becomes the DLL drive; the evidence for R1-a; checks F-1, F-2b, F-3,
  L-1 to L-7.
- [ ] `docs/notes/layer6-cp.md`: the ToHash rule (CyberblockIds.Hash), cyberware events, cyberfx.
- [ ] `docs/notes/cp-mechanics-audit.md`: G-9 is covered by the landing wraps; A.1 now keeps HasDoubleJump/HasChargeJump
  through cyberware.
- [ ] The spec: replace `yTop = ceil(z) − 1` with nearest rounding plus the per-world offset (one paragraph), and add a
  cyberware paragraph to section 5.
**Done:** `uv run pytest -q` passes; the docs match section 1.

---

## 8. Integration (integrator, after all packages report)

1. Merge in this order:
   - D (its vectors are used by M's test);
   - S;
   - M (Bridge);
   - C;
   - A;
   - P.

   Ownership is disjoint, so the merges are clean. `CyberMod.java` (C) and `Cyberblock.java` (M) both register
   `cyberblock` literals, which Brigadier merges.
2. In an integration worktree, run every suite: `uv run pytest -q`; `gradlew.bat test build`; the cmake build +
   `cb_tests.exe`; `compile_check.py`. Grep that no `zz_stub_*` file and no uncommitted Bridge copy is left.
3. Install with the integrator install command (the Cyberpunk folder is touched only there). Copy the mod jar as before.
4. Paste every package's needsInGame list into the TESTING-PLAN appendix. Update the HOW-TO-PLAY key table if a
   package deviated.
5. Tell the user: the Re-test 2 section of TESTING-PLAN, and to run `/cyberblock level` once while standing on the
   street.

## 9. Coverage (user report → tasks)

| Item | Tasks |
|---|---|
| R1 elytra teleports / camera every few s | D3, D4, D5 (drive), S3 (policy, landing wraps, Health), M4 (hand-off, `ts`), P1, P4 F-1 to F-3 and L-7, P5 |
| R2 hacking "target lost" | S1 (ids), S2 (visibility), M3 + C1 (tolerant server pick), P3/P4 |
| R3 infinite skill points | C2, A3 (lang), P3, P4 |
| R4 blocks one above the ground | D1, D2 (offset + nearest rounding), S4 (collision z, pearl snap), M1 (lv), M2 (GroundAlign, `/cyberblock level`), P1, P3, P5 |
| R5 cannot wind-charge yourself | M4 (launch, creative knockback), D3 to D5 (V follows), S3 (fall rules), P2, P3, P4 L-1 to L-6 |
| R6 cyberware with Cyberpunk actions and Minecraft VFX | C3, C4, C5, A1 to A3, D6, S5, S6, M1 (Bridge hooks, ProxyEntity), M4 (double jump flag), P1 to P4 |
