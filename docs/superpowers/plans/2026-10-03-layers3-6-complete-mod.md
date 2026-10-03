# Cyberblock Layers 3–6 (Complete Mod) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Everything that is left of the spec after layers 1–2, in one parallel wave:
- layer 3: Minecraft mobs fight Night City, with puppets and a fallback;
- layer 4: elytra flight with fireworks, ender pearls;
- layer 5: lead/lasso, fishing rod, wind charge, shield, potions;
- layer 6: Cyberpunk in Minecraft form (quickhack items, RAM, hack key, skill tree, kit, keybinds, cyberware);
- the complete DISABLE list from the mechanics audit;
- the layer 1–2 leftovers.

**Architecture:** Same three parts as layers 1–2 (spec §3). Every new behaviour is the same pattern:
1. A wire message (§1.2, Minecraft convention).
2. The DLL validates it, converts it, and either queues an event for redscript or keeps a snapshot that redscript reads
   through natives (§1.3).
3. redscript applies it with real game systems. S1 dispatches; S2 and S3 implement the §1.4 script API.

Minecraft is the authority for everything Minecraft-side: perks, RAM, items, gliding physics, mob AI. Cyberpunk is the
authority for Night City: NPCs, devices, V's body and camera.

**Tech Stack:** as layers 1–2. Spec: `docs/superpowers/specs/2026-10-02-minecraft-cyberpunk-passthrough-design.md` §5.
Audit: `docs/notes/cp-mechanics-audit.md` (sections A–D, cited below as "audit A.2" and so on). Collision notes:
`docs/notes/collision-spike.md`.

---

## 0. Ground rules (all tracks)

- Layer 1 and 2 rules apply (layer 1 plan §0, layer 2 plan §0):
  - Work only in your own files (table below) on your own branch/worktree (`l3/<track>`).
  - Commit after every task. The message ends with a blank line and
    `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never push. Never rewrite main.
  - Never write into or launch Cyberpunk. No system installs and no tool downloads (no WolvenKit); build-dependency
    downloads are fine.
  - Never copy Minecraft or Cyberpunk assets into the repo. The untracked `net/` folder at the repo root holds decompiled
    Minecraft source: never `git add` it, never commit it, and use `git add <paths>` rather than `git add -A`.
- **Nobody is awake.** Never wait for input. If you are blocked, record it in your report (`blocked`), keep the code
  compiling (a logging no-op behind the contract signature), and move on to the next task.
- **APIs:**
  - Cyberpunk: confirm every API from real sources: NativeDB, Codeware sources, the decompiled 2.31 scripts the compile
    checker uses, `r6/cache/tweakdb.bin` / `tweakdb_ep1.bin`. Then run
    `uv run python cp/scripts/compile_check.py`. Cite the source in the file header, as the layer 2 files do.
  - Minecraft 26.3: read the Loom sources (`gradlew genSources`). Never guess a name.
  - Anything that can only be checked in game goes to the report's `needsInGame` list with a concrete check (a CET
    console line or an in-game action plus the expected result).
- **Building against a contract another track owns:**
  - redscript: put stubs with the exact §1.3/§1.4 signatures in `cp/scripts/Cyberblock/zz_stub_<track>.reds`. It is
    **never committed**: commit with explicit paths, and delete it before your final commit. The compile checker copies
    the whole folder, so the stubs get compiled.
  - Java: track C creates `mc/src/main/java/dev/cyberblock/Bridge.java` locally with the exact §1.6 content and never
    commits it (M owns and commits it).
- **Minecraft test clients:**
  - Use only your track's port and frame name (table), passed as
    `gradlew runClient -Pcyberblock.port=<port> "-Pcyberblock.frame=<frame>"`. Never use 25600 or
    `Local\CyberblockFrame`.
  - Before launching, check system commit
    (`Get-CimInstance Win32_OperatingSystem`: `TotalVirtualMemorySize - FreeVirtualMemory`). Do not launch above 60 GB.
  - Always close what you launched. Never kill a process you did not start.
- Test suites: `uv run pytest -q`; in `mc/`, `gradlew.bat test build` (JAVA_HOME =
  `C:\Users\<user>\AppData\Local\Cyberblock\jdk-25.0.4.1+1`); the DLL with
  `cmake --build cp/plugin/build --config Release` then `cp\plugin\build\Release\cb_tests.exe`; scripts with
  `uv run python cp/scripts/compile_check.py`.
- **Report format** (each track, at the end):
  `{track, commits[], done[], notDone[], blocked[], needsInGame[], contractDeviations[]}`. A contract deviation is only
  allowed if the contract is impossible as written. Keep the contract signature, and explain the deviation.

| Track | Owns (exclusive) | Model | Test port / frame |
|---|---|---|---|
| P protocol & tools | `shared/`, `tools/`, `tests/`, `.gitignore`, `README.md`, `NOTICE.md`, `pyproject.toml`, `uv.lock` | Sonnet | 25710 / `Local\CyberblockFrameL3P` |
| D DLL | `cp/plugin/` | Sonnet | 25713 (link_smoke only) |
| M Minecraft world (layers 3–5, leftovers, Bridge) | `mc/` **except** C's paths | Opus | 25711 / `Local\CyberblockFrameL3M` |
| C Minecraft cyber (layer 6) | `mc/src/main/java/dev/cyberblock/cyber/`, `mc/src/client/java/dev/cyberblock/client/cyber/`, `mc/src/test/java/dev/cyberblock/cyber/`, `mc/src/main/resources/fabric.mod.json`, `mc/src/main/resources/cyberblock.cyber.mixins.json`, `mc/src/client/resources/cyberblock.cyber.client.mixins.json`, `mc/src/main/resources/assets/`, `mc/src/main/resources/data/cyberblock/recipe/`, `art/` | Opus | 25712 / `Local\CyberblockFrameL3C` |
| S1 redscript core & DISABLE | `cp/scripts/Cyberblock/Cyberblock.reds`, `Natives.reds`, `Events.reds`, `PlayerState.reds`, `Health.reds`, `Probes.reds`, `Npcs.reds`, `Disable.reds` (new), `cp/scripts/compile_check.py` | Opus | — |
| S2 redscript world effects (layers 3–5) | `cp/scripts/Cyberblock/Effects.reds`, `Collision.reds`, `Puppets.reds`, `Flight.reds`, `Tether.reds`, `Projectiles.reds` (new), `docs/notes/puppet-spike.md`, `docs/notes/flight-spike.md` | Opus | — |
| S3 redscript powers (layer 6 + potions) | `cp/scripts/Cyberblock/Hacks.reds`, `Scan.reds`, `Perks.reds`, `Buffs.reds`, `Kills.reds` (new), `docs/notes/layer6-cp.md` | Opus | — |

Every task in every track may start at once: the contracts in §1 are the only coupling. Each track does its tasks in
the listed order, because that order is also the priority order.

---

## 1. Contracts (fixed; change only via the integrator)

### 1.1 Coordinates and units
- **Positions:** Minecraft `(x, y, z)` → Cyberpunk `(x, -z, y)` (layer 2 `mc_to_cp_pos`). Cyberpunk → Minecraft
  `(x, z, -y)`.
- **Velocities and impulses** (m/s) use the same axis mapping with no offset. Add `mc_vel_to_cp(vx, vy, vz)` to
  `tools/cbmath.py` (equal to `mc_to_cp_pos`). It is a separate name so that callers say what they mean.
- **Yaw:**
  - Minecraft yaw `m` (0 = facing +Z, degrees) ↔ Cyberpunk yaw `a` (0 = facing +Y, counter-clockwise from above, as
    `Npcs.reds` and `cp_yaw_to_mc_yaw` use): `a = 180 − m` and `m = 180 − a`, normalised to (−180, 180].
  - Add `mc_yaw_to_cp_yaw` to `tools/cbmath.py`. Add a `mc_yaw` vector set (6 cases, including ±180 and 0) and a
    `mc_vel` vector set (6 cases).
  - Pitch is Minecraft pitch (positive = down) both ways. Cyberpunk scripts get it unchanged.
- **Gravity** (projectiles) is a scalar in m/s² pointing down (Minecraft −Y = Cyberpunk −Z).
- **Ids:**
  - Cyberpunk entities (NPC, vehicle, device): the EntityID hash as a decimal string on the wire, `Uint64` in scripts.
    0 means none.
  - Minecraft mobs: the Minecraft network entity id, an int ≥ 0. Scripts get it as `Int32`.
  - Request ids (`scan`, `hack`): int ≥ 0, chosen by Minecraft, echoed in the reply.

### 1.2 Wire protocol additions (`proto` stays 1; unknown `t` and unknown fields are ignored)
All limits are checked by the receiver. A bad message (or a bad list entry) is dropped and counted, and never ends the
link.

**Cyberpunk → Minecraft (Minecraft convention):**
| `t` | fields | when |
|---|---|---|
| `cam` (extended) | adds `cr:bool` (V crouched) | every frame, from `Cyberblock_SetStance` |
| `key` (extended) | `k` also ∈ `jump`, `sprint` (forwarded, **not** swallowed: Cyberpunk keeps them), `perspective` (F5, swallowed), `hack`, `skills` (the keys from `binds`, swallowed) | input while forwarding |
| `mobdmg` | `id` (mob), `frac` 0..4 (fraction of the puppet's max health), `by:"<npc id>"` or `null` | a Cyberpunk NPC damaged a mob's puppet |
| `pcells` | `c:[x,y,z, …]` integer block cells, ≤ 64 cells | Night City surfaces on predicted projectile paths: Minecraft makes them temporary barriers for 3 s |
| `unlead` | `target:"<id>"` | Cyberpunk gave up a tether (target gone, or snapped: more than length + 12 m for 2 s) |
| `scaninfo` | `id`, `ok:bool`, `target:"<id>"`, `name` (≤ 64 chars), `faction` (≤ 64), `threat` ∈ `none, low, medium, high, extreme`, `hp` 0..1, `device:bool`, `p:[x,y,z]` (feet / device base), `size:[w,h]` m | reply to every `scan` (`ok:false` with empty strings when nothing is there) |
| `hackresult` | `id`, `ok:bool`, `reason` (≤ 64 chars, `""` on success) | reply to every `hack` |
| `killed` | `target:"<id>"`, `kind:"human"\|"vehicle"`, `hostile:bool` | an NPC or vehicle that V (Cyberblock) damaged died or was destroyed |
| `look` | `d:[dyaw, dpitch]` degrees | only in the camera-only flight fallback (`Cyberblock_SetLookCapture(true)`), ≤ 120 Hz |

**Minecraft → Cyberpunk (Minecraft convention):**
| `t` | fields | when |
|---|---|---|
| `hit` (extended) | adds `mob:int` (the mob that dealt a `kind:"mob"` or mob `arrow` hit; absent otherwise) | as layer 2 |
| `blocks` | `reset:bool`, `c:[x,y,z, …]` ≤ 64 cells, all solid non-barrier blocks of the player | after each host `hello`: first message `reset:true` (it may have an empty `c`), then the rest at ≤ 5 messages/s |
| `mobs` | `list:[{id, kind:"zombie", p:[x,y,z] feet, yaw, w, h, hp, maxHp}]`, ≤ 12 entries, nearest first | 10 Hz while active: hostile goal-driven mobs within 48 m of Steve. One empty list after the last one goes |
| `flight` | `active:bool`, `p:[x,y,z]` feet, `v:[x,y,z]` m/s, `yaw`, `pitch` | every client frame (≤ 120 Hz) while gliding. One `active:false` at touchdown, with `p` = landing spot |
| `teleport` | `p:[x,y,z]` feet, `yaw` | an ender pearl of Steve's landed |
| `proj` | `list:[{id, p:[x,y,z], v:[x,y,z] m/s, g}]`, ≤ 16 | 10 Hz while a projectile Steve owns flies (pearl, arrow, trident, firework, potion, wind charge, fishing hook). One empty list after |
| `lead` | `target`, `anchor:[x,y,z]` or `null` (null: Steve holds it), `length` m (1..32) | a proxy was leashed |
| `lead` | `target`, `release:true` | the leash was removed, broken or dropped |
| `pull` | `target`, `toward:[x,y,z]`, `strength` m/s (0..50) | a fishing rod reeled in a hooked proxy |
| `knock` | `pos:[x,y,z]`, `radius` m (0..16), `strength` m/s (0..50) | a wind charge burst (sent **instead of** `explosion`) |
| `effect` | `name` (Minecraft effect path, `[a-z_]{1,32}`), `seconds` 0..86400 (0 = removed; infinite = 86400), `amp` 0..255 | a status effect on Steve was added, changed or removed |
| `scan` | `id`, `target:"<id>"` or `null` (null: whatever V looks at) | the hack key targets something (≤ 5/s) |
| `hack` | `id`, `target:"<id>"`, `hack` ∈ §1.5, `chain:bool` | a quickhack item was used on the locked target |
| `perks` | `list:[{id, rank}]`: all 20 ids of §1.5, rank 0 or 1 | after each host `hello`, and on every purchase |
| `perkfx` | `id:"sandevistan"`, `seconds` 0..30 | Sandevistan Sprint fired |
| `binds` | `keys:{hack:<vk>, skills:<vk>}` Windows virtual-key codes 1..254 | after each host `hello`, and when rebound in Minecraft's controls |

**Validation limits:**
- Every position/vector is finite with `|c| < 3e7`; `flight.v` and `proj.v` have `|v| ≤ 200`.
- `proj.g` is 0..40, `mobs[].hp` and `maxHp` are 0..2048, `w`/`h` are 0..16, `kind` matches `[a-z0-9_]{1,32}`.
- `hack.hack` must be one of the §1.5 hack names, and `perks[].id` one of the §1.5 perk ids.

**Scaling (one place each):** `mobdmg.frac` → the mob loses `frac × its max health` (Minecraft).
`pull.strength` / `knock.strength` are speeds that Cyberpunk applies as given (humans: ragdoll impulse; vehicles:
× vehicle mass, as `CyberblockEffects.Knock` does).

### 1.3 DLL ↔ redscript natives (S1 appends these to `Natives.reds` in task S1.1; D implements them)
Existing natives stay as they are. `Cyberblock_NextEvent` also returns the new kinds below, and its queue keeps these
while not Active: `block_*`, `block_reset`, `perk`, `scan`, `hack` (the last two must be answered). The other new kinds
are discarded while not Active, like explosions.

```swift
// ---- layers 3-6 (plan 2026-10-03 section 1.3) ----
// Event fields beyond layer 2's (the current event, after Cyberblock_NextEvent):
public native func Cyberblock_EventName() -> CName;     // effect name | hack name | perk id | perkfx id
public native func Cyberblock_EventExtra() -> Float;    // knock strength | effect amplifier | (else 0)
public native func Cyberblock_EventId() -> Int32;       // hit: mob id (-1 none) | hack/scan: request id
// Stance, menus, game mode
public native func Cyberblock_SetStance(crouched: Bool) -> Void;     // carried as cam.cr until changed
public native func Cyberblock_SetMenu(open: Bool) -> Void;           // a Cyberpunk menu is open: input forwarding pauses
public native func Cyberblock_GameMode() -> CName;                   // last status.gm, n"" before any status
// Layer 3 mobs: Cyberblock_MobCount takes a snapshot of the latest mobs list that the getters read (i in 0..count-1)
public native func Cyberblock_MobCount() -> Int32;
public native func Cyberblock_MobId(i: Int32) -> Int32;
public native func Cyberblock_MobKind(i: Int32) -> CName;            // n"zombie", n"skeleton", ...
public native func Cyberblock_MobPos(i: Int32) -> Vector4;           // feet, Cyberpunk space
public native func Cyberblock_MobYaw(i: Int32) -> Float;             // Cyberpunk yaw, degrees
public native func Cyberblock_MobSize(i: Int32) -> Vector4;          // X width, Y height, Z hp, W max hp
public native func Cyberblock_MobDamage(id: Int32, frac: Float, by: Uint64, hasBy: Bool) -> Void;   // -> mobdmg
// Layer 4 flight (latest flight message; position extrapolated by v * min(age, 0.1 s); the landing spot once inactive)
public native func Cyberblock_FlightActive() -> Bool;
public native func Cyberblock_FlightSeq() -> Int32;                 // +1 on every start and every landing
public native func Cyberblock_FlightPos() -> Vector4;               // feet, Cyberpunk space
public native func Cyberblock_FlightVelocity() -> Vector4;          // m/s, Cyberpunk space
public native func Cyberblock_FlightYaw() -> Float;                 // Cyberpunk yaw, degrees
public native func Cyberblock_FlightPitch() -> Float;               // Minecraft pitch, degrees (positive = down)
public native func Cyberblock_SetLookCapture(on: Bool) -> Void;     // camera-only fallback: raw mouse -> look deltas
// Layer 4 projectiles: Cyberblock_ProjCount takes a snapshot
public native func Cyberblock_ProjCount() -> Int32;
public native func Cyberblock_ProjPos(i: Int32) -> Vector4;         // Cyberpunk space
public native func Cyberblock_ProjVelocity(i: Int32) -> Vector4;    // m/s, Cyberpunk space
public native func Cyberblock_ProjGravity(i: Int32) -> Float;       // m/s^2, along -Z
public native func Cyberblock_SubmitProjectileHits(hits: array<Vector4>) -> Void;  // Cyberpunk-space surface points -> pcells
// Layer 5
public native func Cyberblock_Unlead(target: Uint64) -> Void;                       // -> unlead
// Layer 6
public native func Cyberblock_ScanResult(id: Int32, ok: Bool, target: Uint64, name: String, faction: String, threat: CName, hp: Float, device: Bool, pos: Vector4, size: Vector4) -> Void;
public native func Cyberblock_HackResult(id: Int32, ok: Bool, reason: String) -> Void;
public native func Cyberblock_Killed(target: Uint64, vehicle: Bool, hostile: Bool) -> Void;
```

**Event table.** These are the fields each kind fills (Cyberpunk space). Fields not listed are zero.
| kind | Pos | Target | Amount | Vector | Flag | Name | Extra | Id |
|---|---|---|---|---|---|---|---|---|
| `hit` (extended) | — | target | dmg/20 | impulse | — | — | — | mob id or −1 |
| `block_reset` | — | — | — | — | — | — | — | — |
| `teleport` | feet | — | Cyberpunk yaw | — | — | — | — | — |
| `lead` | anchor (when Flag) | target | length | — | fixed anchor | — | — | — |
| `lead_release` | — | target | — | — | — | — | — | — |
| `pull` | toward | target | strength | — | — | — | — | — |
| `knock` | centre | — | radius | — | — | — | strength | — |
| `effect` | — | — | seconds (0 = remove) | — | — | name | amplifier | — |
| `hack` | — | target | — | — | chain | hack | — | request id |
| `scan` | — | target (0 = look-at) | — | — | — | — | — | request id |
| `perk` | — | — | rank | — | — | perk id | — | — |
| `perkfx` | — | — | seconds | — | — | id | — | — |

**DLL message handling:**
- `blocks` becomes one `block_reset` (when `reset`) followed by one `block_add` per cell.
- `perks` becomes one `perk` event per entry.
- The queue cap stays at 256. Minecraft paces `blocks` at ≤ 64 cells per message and ≤ 5 messages/s.

### 1.4 S1 ↔ S2 ↔ S3 script API (the owner implements exactly these; S1 calls them)
All of them are `public abstract class` with `public static func`, as in layer 2 §1.4. They must be safe to call at
any time, including with no player and before anything was spawned.
```swift
// ---- S2 ----
// Effects.reds (layer 2 functions unchanged), plus:
CyberblockEffects.HitFrom(game: GameInstance, target: Uint64, frac: Float, impulse: Vector4, mob: Int32) -> Void;
    // mob -1: as Hit (V is the attacker). Otherwise that mob's puppet is the instigator when it exists, else V.
CyberblockEffects.Teleport(game: GameInstance, feet: Vector4, yaw: Float) -> Void;   // V to feet, keeping camera pitch
// Collision.reds: Reset(game) is now part of the contract (exists): despawn all boxes and forget the registry.
// Puppets.reds
CyberblockPuppets.Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void;  // 10 Hz; live false -> despawn all
CyberblockPuppets.Clear(game: GameInstance) -> Void;                                        // despawn all (save, load, Off)
// Flight.reds
CyberblockFlight.Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void;   // 10 Hz; runs its own per-frame loop while flying
CyberblockFlight.IsFlying(game: GameInstance) -> Bool;
CyberblockFlight.SuppressFall(game: GameInstance) -> Bool;  // flying, or < 2 s since a landing or a Teleport
CyberblockFlight.Clear(game: GameInstance) -> Void;         // stop following, restore V and the camera
// Tether.reds
CyberblockTether.Lead(game: GameInstance, target: Uint64, anchor: Vector4, fixed: Bool, length: Float) -> Void;
CyberblockTether.Release(game: GameInstance, target: Uint64) -> Void;
CyberblockTether.Pull(game: GameInstance, target: Uint64, toward: Vector4, strength: Float) -> Void;
CyberblockTether.Knockback(game: GameInstance, center: Vector4, radius: Float, strength: Float) -> Void;
CyberblockTether.Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void;   // 10 Hz; live false -> drop tethers silently
CyberblockTether.Clear(game: GameInstance) -> Void;
// Projectiles.reds
CyberblockProjectiles.Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void;  // 10 Hz
// ---- S3 ----
CyberblockHacks.Hack(game: GameInstance, id: Int32, target: Uint64, hack: CName, chain: Bool) -> Void;  // always answers Cyberblock_HackResult
CyberblockScan.Scan(game: GameInstance, id: Int32, target: Uint64) -> Void;                             // always answers Cyberblock_ScanResult
CyberblockPerks.Set(game: GameInstance, id: CName, rank: Int32) -> Void;   // records the rank (kept while not Active)
CyberblockPerks.Has(game: GameInstance, id: CName) -> Bool;
CyberblockPerks.Fx(game: GameInstance, id: CName, seconds: Float) -> Void;
CyberblockPerks.Tick(game: GameInstance, player: ref<PlayerPuppet>, active: Bool) -> Void;  // applies perk modifiers while active, removes them otherwise
CyberblockPerks.Clear(game: GameInstance) -> Void;                          // removes modifiers and slow-mo; keeps ranks
CyberblockBuffs.Effect(game: GameInstance, name: CName, seconds: Float, amplifier: Int32) -> Void;
CyberblockBuffs.Tick(game: GameInstance, player: ref<PlayerPuppet>, active: Bool) -> Void;  // expiry; active false -> remove all
CyberblockBuffs.Clear(game: GameInstance) -> Void;
```
S1 calls Clear on all of S2's and S3's classes (and `CyberblockCollision.Clear`) at the same points:
- before every save;
- on session ready;
- at session end;
- on every transition out of Active/Vehicle.

`Kills.reds` (S3) has no API: it reports deaths itself. It only reports while Cyberblock's god mode is on V (the audit
`Active` test).

### 1.5 Names
**Hacks** (`hack` wire value → Cyberpunk; S3 confirms the record IDs in the TweakDB, falling back to the lowest tier
that exists):
| Item (C) | `hack` | RAM | Cyberpunk (audit B) |
|---|---|---|---|
| Redstone Charge | `short_circuit` | 3 | `QuickHack.OverloadLvl1Hack` (Short Circuit) |
| Blaze Chip | `overheat` | 4 | `QuickHack.OverheatLvl1Hack` |
| Ink Sac Daemon | `reboot_optics` | 3 | `QuickHack.BlindLvl1Hack` |
| Cobweb Daemon | `cripple_movement` | 2 | `QuickHack.LocomotionMalfunctionLvl2Hack` |
| Spider Eye Virus | `contagion` | 4 | `QuickHack.ContagionLvl2Hack` (spread allowed) |
| Wither Skull Daemon | `synapse_burnout` | 6 | `QuickHack.BrainMeltLvl2Hack` |
| Compass Ping | `ping` | 1 | `QuickHack.PingLvl2Hack` |
| Bottle of Forgetting | `memory_wipe` | 3 | `QuickHack.MemoryWipeLvl2Hack` |
| Creeper Brain | `cyberpsychosis` | 8 | `QuickHack.MadnessLvl3Hack` |
| Redstone Lever | `device` | 1 | device action (audit B, device hacks): `QuickHackDistraction` for media and vending devices; `ForceIgnoreTargets` for cameras (**never** `ToggleTakeOverControl`); intersection flip for traffic lights; otherwise the device's first quickhack action |

**Perks** (`perks[].id`, branch order = purchase order; cost in XP levels by tier 3 / 5 / 8 / 12; tier n needs tier
n−1 of its branch):

| id | Minecraft effect (owner) | Cyberpunk effect (owner) |
|---|---|---|
| `iron_skin` | +8 max health (C) | — |
| `gorilla_arms` | melee impulse on proxies ×2 for humans, ×3 for vehicles (M, via Bridge) | — (the impulse does it) |
| `painkiller` | natural regeneration twice as fast (C) | — |
| `juggernaut` | knockback resistance 1.0 (C); host `dmg` knockback respects it (M, vanilla) | — (V has no hit reactions under god mode, audit D.4) |
| `double_jump` | — | V's `HasDoubleJump` granted while Active (S3); S1 does not zero it (S1) |
| `sandevistan` | double-tap `key sprint` (≤ 300 ms apart) → Speed II 5 s, server tick rate 10 for 5 s, cooldown 20 s, sends `perkfx` (C) | world slow-mo ×0.2 for `seconds`, V unslowed (audit C.1) (S3) |
| `quick_draw` | bow and crossbow charge 30 % faster (C) | — |
| `dodge` | — | dash and air dash: S1's `IsNewPerkBought` wrap reports the Reflexes milestones as bought, and S1 does not zero `HasDodge`/`HasDodgeAir` (S1) |
| `tinkerer` | TNT and arrow crafting results ×2 (C) | — |
| `smart_arrows` | Steve's arrows home onto the nearest proxy in a 20° cone, ≤ 32 m (C) | — |
| `field_medic` | food and healing potions heal 50 % more (C) | — |
| `demolitions` | radius ×1.5 for explosions Steve caused (his TNT, creepers he ignited, fire charges) (M, via Bridge); the reported `explosion.power` is the real radius | (follows from the power) |
| `extra_ram` | max RAM +4 (C) | — |
| `cheap_hacks` | hack cost −1, minimum 1 (C) | — |
| `chain_hack` | `hack.chain = true` (C) | the same hack on the nearest other NPC within 8 m of the target (S3) |
| `overclock` | RAM regeneration ×2 (C) | — |
| `optical_camo` | V crouched (`Bridge.hostCrouching`) and still (feet moved < 0.1 m) for 2 s → Invisibility 10 s, refreshed while still (C); forwarded as `effect` (M) | camo through Buffs `invisibility` (S3) |
| `headhunter` | arrow hit on a human proxy within 0.35 m of its top → damage ×2 (M, via Bridge) | — |
| `cold_blood` | on `killed` → Speed I 10 s (C) | — (`killed` comes from Kills.reds) |
| `silent_steps` | mobs target Steve from half the range (C) | V's `Visibility` stat ×0.5 while Active (audit C.4) (S3) |

**Effects mapped to Cyberpunk** (`effect.name`; S3 confirms every record or stat; others are ignored and logged
once):
- `invisibility` → optical camo: audit C.3 `OpticalCamoPlayerBuffRare`, plus `SetInvisible(true)`.
- `speed` → V's movement speed ×(1 + 0.2·(amp+1)).
- `slowness` → ×(1 − 0.15·(amp+1)), minimum 0.3.
- `jump_boost` → jump height ×(1 + 0.5·(amp+1)).
- `nausea` → a screen-wobble/drunk status effect on V.
- `blindness` / `darkness` → a blind screen effect on V.

**Never** map a damage-over-time effect (poison, wither): a DoT on V would reach Steve a second time through
Health.reds.

### 1.6 Minecraft cross-track bridge (M commits it in task M0; C keeps an identical local copy, never committed)
`mc/src/main/java/dev/cyberblock/Bridge.java`, exactly:
```java
package dev.cyberblock;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import net.minecraft.world.entity.Entity;

/** Contract between the Minecraft world track (M, owns this file) and the cyber track (C). Plan 2026-10-03 section 1.6. */
public final class Bridge {
	/** Host message listeners by "t": HostLink calls the one registered for a message's type after its own handling, on the link thread. */
	public static final Map<String, Consumer<JsonObject>> listeners = new ConcurrentHashMap<>();
	/** Perk rank (0 = not owned) of the local player by perk id (plan section 1.5). C sets it; M reads it on the server thread. */
	public static volatile ToIntFunction<String> perkRank = id -> 0;
	/** The live proxy entity for a Cyberpunk id, or null. M sets it; server thread only. */
	public static volatile LongFunction<Entity> proxyById = id -> null;
	/** The Cyberpunk id a proxy stands for, 0 when the entity is not a proxy. M sets it; server thread only. */
	public static volatile ToLongFunction<Entity> proxyIdOf = e -> 0L;
	/** V is crouched (the host's cam.cr). M sets it. */
	public static volatile boolean hostCrouching;

	private Bridge() {
	}

	/** HostLink calls this for every host message (link thread). A listener that throws is logged, never fatal. */
	public static void dispatch(final String type, final JsonObject message) {
		Consumer<JsonObject> listener = listeners.get(type);
		if (listener != null) {
			try {
				listener.accept(message);
			} catch (RuntimeException e) {
				Cyberblock.LOG.warn("listener for {} failed: {}", type, e.toString());
			}
		}
	}
}
```
- **C registers listeners** for: `hello`, `state`, `key` (C handles only `hack`, `skills`, `sprint`), `scaninfo`,
  `hackresult`, `killed`.
- **M registers none.** M handles its own types in HostLink's switch.
- **Sending:** both tracks send through the existing `Cyberblock.events`.
- **Mod setup:** C adds its own entrypoints and mixin configs to `fabric.mod.json`:
  - main `dev.cyberblock.cyber.CyberMod`;
  - client `dev.cyberblock.client.cyber.CyberClient`;
  - mixin configs `cyberblock.cyber.mixins.json` and `cyberblock.cyber.client.mixins.json` (client environment).
- **Ownership on the Minecraft side:**
  - M never edits `fabric.mod.json`.
  - C never edits files M owns.
  - Both may add mixins targeting the same vanilla class.

### 1.7 Vectors
P adds the `mc_yaw` and `mc_vel` vector sets to `shared/vectors.json` (P1). D's tests read them; until P's commit is
on main, D generates them locally with the same generator logic and does not commit `shared/`.

---

## 2. What layers 1–2 left open (now assigned)
| Gap | Source | Task |
|---|---|---|
| Collision registry never reset; Minecraft never re-sends blocks after a restart or reconnect | collision-spike "Blocker for the integrator", integration report | M1 (`blocks` resync), D2 (`block_reset`), S1.4 (Reset + replay) |
| Probes hit our own collision boxes (ground/walls/aim at Minecraft block faces) | collision-spike "Side effects" | S1.5 |
| DISABLE list: NoHealing, NoScanning, NoRadialMenus (+ NoQuickMelee, NoZooming, BodyCarryingForceDrop), ability stats zeroed, perk-gated dash, F5 quicksave, G walk toggle, K crafting, keys 1/2 interaction choices, menus | audit A.2–A.4 | S1.2, S1.3, D5 |
| Damage-over-time never reaches Steve | audit D gap 1 | S1.3 |
| Keys reach Cyberpunk (only Esc is swallowed); F5 not forwarded | audit key finding 1 | D5 |
| `status.gm` not read by the DLL (creative keeps V invincible in vehicles too) | spec §1, decisions | D6, S1.3 |
| Join wipes Steve's chest slot (would delete the elytra) and the new-world block kit | `CyberblockClient` | M0 |
| Untracked decompiled `net/` must never be committed | integration report | P4 (`/net/` in `.gitignore`) |
| `Reset` missing from the contract | integration report | §1.4 (now contract) |
| Layer 2 in-game acceptance never run | layer 2 plan, Integration | Integration §I.2 |

---

## Track P — protocol & tools (Sonnet)

### P1 cbmath yaw and velocity
- [ ] TDD in `tests/test_cbmath.py`:
  - `mc_yaw_to_cp_yaw(0) == 180`, `mc_yaw_to_cp_yaw(90) == 90`, `mc_yaw_to_cp_yaw(-90) == -90` (normalised to
    (−180, 180]);
  - round trip with the layer 2 `cp_yaw_to_mc_yaw` formula `m = 180 − a`;
  - `mc_vel_to_cp(1, 2, 3) == (1, -3, 2)`.
- [ ] Implement both in `tools/cbmath.py`. Extend `tools/gen_vectors.py` with 6 `mc_yaw` and 6 `mc_vel` cases
  (including −180, 180, 0, 359.5). Regenerate. Expected: no change to the existing sets. Then `uv run pytest -q`
  green. Commit.

### P2 protocol document
- [ ] Add every §1.2 row (new types and the extended `cam`, `key`, `hit`) with the §1.2 limits and scaling to
  `shared/protocol.md` under "Layers 3–6", plus one JSON example per new type in the examples blocks.
- [ ] Extend `tests/test_protocol_md.py` so every type in both tables has an example and every example parses. Green.
  Commit.

### P3 fake host for layers 3–6
`tools/fakehost.py`: add `--layer3`, `--layer4`, `--layer5` and `--layer6` (combinable, after the layer-1 script) and
`--port` (default 25600). Each prints every Minecraft→Cyberpunk message of its layer as `<t>: <json>`.
- **`--layer3`:** sends the layer-2 `npcs`. When a `mobs` list arrives, it answers once
  `mobdmg {id:<first>, frac:0.5, by:<hostile human id>}`.
- **`--layer4`:**
  1. Replays a fall: `cam` with `pl.y` dropping 8 m/s from 200, plus `key jump` down/up at 0.5 s.
  2. While `flight.active`, it feeds `cam` at the flight position, as Cyberpunk would.
  3. On `proj` it answers `pcells` with a 3×3 wall 0.5 s ahead of the projectile.
  4. It prints `flight`, `teleport` and `proj`.
- **`--layer5`:** prints `lead`, `pull`, `knock` and `effect`. Use `--cmd` for the setup commands.
- **`--layer6`:**
  - answers every `scan` with a canned `scaninfo` (`ok:true`, name "Officer Test", faction "NCPD", threat "high",
    hp 0.8, at the first human);
  - answers every `hack` with `hackresult ok:true`;
  - sends `key hack` down/up, `key skills` down/up and `killed {target:<human>, kind:"human", hostile:true}`;
  - prints `perks`, `perkfx`, `binds`, `scan` and `hack`.
- [ ] Tests in `tests/test_fakehost.py` with the existing local fake-Minecraft server harness: each layer flag sends
  its scripted messages and answers `scan`/`hack`/`proj`/`mobs` as above. Commit.

### P4 housekeeping
- [ ] `.gitignore`: add `/net/` (decompiled Minecraft source must never be committed).
- [ ] `README.md`: the feature list for layers 3–6, the keys (F9, G, K, Space/Shift behaviour) and the creative/survival
  rules.
- [ ] `uv run pytest -q` green. Commit.

**P done when:** pytest green; protocol.md lists every §1.2 message with an example; fakehost layer flags work against
the harness.

---

## Track D — DLL (Sonnet)
Wire formats: §1.2. Natives: §1.3. Mirror every conversion from `tools/cbmath.py` (`mc_yaw_to_cp_yaw`,
`mc_vel_to_cp`).

### D1 cbmath mirror
- [ ] Add `cb::mc_yaw_to_cp_yaw` and `cb::mc_vel_to_cp` to `cp/plugin/src/cbmath.*`.
- [ ] Add cb_tests over the `mc_yaw`/`mc_vel` vector sets (§1.7). Commit.

### D2 events
- [ ] Extend `world::parse_event` and `Event` for `teleport`, `lead`, `pull`, `knock`, `effect`, `hack`, `scan`,
  `perks` (one `perk` event per entry), `perkfx`, `blocks` (`block_reset` + `block_add` per cell) and `hit.mob`.
  - Validate every field with the §1.2 limits: hack names and perk ids from §1.5 only, effect names `[a-z_]{1,32}`.
  - Convert positions, vectors and yaw.
  - Add `name`, `extra` and `id` to `Event`.
- [ ] Queue rules (§1.3):
  - Kept while not Active: `block_*`, `block_reset`, `perk`, `scan`, `hack`. Everything else is discarded while not
    Active.
  - A `hello` clears the queue.
- [ ] Implement `Cyberblock_EventName/Extra/Id`.
- [ ] cb_tests: one valid and one rejected message per type; `blocks` expansion order; the queue keeps `hack`/`perk`
  while inactive. Commit.

### D3 snapshots
- [ ] `mobs`:
  - Keep the latest validated list (≤ 12 entries; bad entries dropped).
  - `Cyberblock_MobCount` copies it into a snapshot that the getters read.
  - An index out of range returns zero values.
  - The list is emptied when it is 2 s old, when the link drops, and on `hello`.
- [ ] `flight`:
  - Keep the last sample and its receive time (steady clock).
  - `Cyberblock_FlightPos` = `p + v × min(age, 0.1 s)` while active; the landing spot after `active:false`.
  - `FlightSeq` +1 on each change of `active`.
  - Treat a sample older than 0.5 s as `active:false` (Minecraft vanished mid-flight) and bump `FlightSeq`.
- [ ] `proj`: snapshot like `mobs`, emptied after 0.5 s without a list.
- [ ] Implement the §1.3 natives for these. cb_tests: extrapolation clamp, staleness, snapshot isolation (a new
  message does not change an existing snapshot). Commit.

### D4 messages to Minecraft
- [ ] Builders and natives:
  - `mobdmg` (`Cyberblock_MobDamage`: frac clamped 0..4, `by` null unless `hasBy`);
  - `pcells` (`Cyberblock_SubmitProjectileHits`: each hit → its block cell with Minecraft `floor` per axis after
    conversion; deduplicate; ≤ 64 cells, extra hits dropped);
  - `unlead`, `scaninfo` (strings truncated to 64 UTF-8 bytes on a character boundary; threat must be a §1.2 value,
    else `none`), `hackresult`, `killed`.
- [ ] `cam` gets `cr` from `Cyberblock_SetStance`.
- [ ] cb_tests for every builder. Commit.

### D5 input: the DISABLE keys and the new keys (audit A.3)
While `forwarding()` is true (which now also needs `!menuOpen`, set by `Cyberblock_SetMenu`):
- [ ] **Swallowed keys** (Cyberpunk never sees them): E, Q, T, 1–9, `/`, F5, and the two VKs from `binds`
  (defaults G = 0x47, K = 0x4B).
  - Drop both the `WM_INPUT` keyboard packet (return `DefWindowProcW`) and `WM_KEYDOWN`/`WM_KEYUP`/`WM_SYSKEY*`
    (return 0).
  - **Key-up rule:** a key-up whose key-down Cyberpunk saw (pressed before forwarding started) is passed through, so
    no key sticks in Cyberpunk.
  - F5 → `key perspective`; the binds → `key hack` / `key skills`.
- [ ] **Forwarded but not swallowed:** Space → `key jump`, Left Shift → `key sprint` (down and up, no auto-repeat).
- [ ] **Mouse buttons and wheel:**
  - IAT-hook `USER32!GetRawInputData` in `Cyberpunk2077.exe`'s import table.
  - While forwarding, clear `RI_MOUSE_LEFT/RIGHT/MIDDLE_BUTTON_DOWN`, `RI_MOUSE_BUTTON_4/5_DOWN`,
    `RI_MOUSE_WHEEL` and `RI_MOUSE_HWHEEL` in packets returned to the game. Keep the `*_UP` bits and the motion.
  - Also eat `WM_*BUTTONDOWN` and `WM_MOUSEWHEEL`.
  - Our own `on_raw_input` must still see the unmodified data: call the original function there.
  - Unhook on unload.
- [ ] **`binds` from Minecraft:** validate (1..254, not F9, not Esc, not a key in the swallow set above, not
  W/A/S/D/Space/Shift/Ctrl/C) and update the two VKs. An invalid one keeps the previous one and logs once.
- [ ] **Look capture:** while `Cyberblock_SetLookCapture(true)`, raw mouse deltas × 0.15° per count are summed and
  sent as `look {d:[dyaw,dpitch]}` at ≤ 120 Hz.
- [ ] Log once whether the keyboard is registered for raw input (`GetRegisteredRawInputDevices`, usage page 1,
  usage 6), for audit G-1.
- [ ] Build. cb_tests for the pure parts (the swallow set incl. binds, bind validation, the key-up rule as a state
  machine). Commit.

### D6 status game mode, menu, stance
- [ ] Link parses `status.gm` (`survival|creative|adventure|spectator`, else ignored) into an atomic. Implement
  `Cyberblock_GameMode` (n"" before any status), `Cyberblock_SetMenu` and `Cyberblock_SetStance`.
- [ ] Build + cb_tests green. Commit.

**D done when:** `cb_tests.exe` passes with the new cases; `Cyberblock.dll` builds; every §1.3 native is registered
with exactly the declared name, parameter types and return type (list them in the report). Cross-check them against
your local copy of S1's §1.3 block.

---

## Track M — Minecraft world (Opus)
Look up every 26.3 name in the Loom sources. Reference implementations (MIT, same author):
- `third_party/universal-modder/examples/minecraft-gta5-passthrough/mc/src/main/java/dev/rehan/passthrough/MobWar.java`
- `ProxyTargetGoal.java`, `mixin/MobMixin.java`
- `client/PlayerSync.java` (drive mode)

Port with the layer 1 §0 renames and credit header.

### M0 bridge and fixes
- [ ] Add `Bridge.java` exactly as in §1.6.
  - In `Cyberblock.onInitialize`, set `Bridge.proxyById` and `Bridge.proxyIdOf`, backed by `ProxyManager`'s map
    (add a package-private lookup).
  - `HostLink.onMessage`: after its own switch, call `Bridge.dispatch(t, m)` for every message.
  - `HostState`: parse optional `cam.cr` into `Bridge.hostCrouching`. Steve shows the crouch pose while it is true
    (client player crouching / shift key state).
- [ ] `CyberblockClient` JOIN: keep `stopFallFlying()`, but **stop clearing the chest slot** (it would delete the
  elytra). Remove the new-world block `KIT` (C's starter kit replaces it).
- [ ] `ClientInput`:
  - `key jump` and `key sprint` are stored for M3 and C. Do not press vanilla jump/sprint: the host moves Steve.
  - `key perspective` is ignored while the host drives the camera.
- [ ] JUnit for `Bridge.dispatch` (a throwing listener is contained). Commit.

### M1 block resync (layer 2 leftover)
- [ ] A per-world `SavedData` registry (`cyberblock_blocks`: a set of packed block positions) of the solid non-barrier
  blocks that `BlockEvents` reports (adds and removes), maintained where `block` events are sent.
- [ ] On each host `hello`:
  1. Send `blocks {reset:true, c:[…first ≤ 64…]}`.
  2. Then the rest, ≤ 64 per message and ≤ 5 messages/s.
  3. Live `block` events sent meanwhile keep their order after the resync starts.
- [ ] JUnit: packing round trip, the chunking/pacing plan (pure function), add/remove keeps the set exact. Commit.

### M2 mobs vs Night City (layer 3)
- [ ] **Port `ProxyTargetGoal`:**
  - Hostile goal-driven mobs (`Enemy`, not brain-driven: piglin, hoglin, zoglin, breeze, creaking, warden) get a
    target goal for **human** proxies (`ProxyEntities.HUMAN` only), ignoring invisibility and line of sight.
  - They keep targeting Steve as vanilla does.
  - Follow range ≥ 48.
- [ ] **No sunburn** while `Cyberblock.active` (Minecraft is fixed at noon). Port `MobMixin.burnUndead`.
- [ ] **Hold mobs off unknown ground:** a fighting mob more than 22 blocks horizontally from Steve, or with no block
  within 4 below it, gets no gravity and zero velocity (pathing paused) until it is within range of the barrier ground
  again. So mobs never fall into the void outside the probed area.
- [ ] **`mobs` 10 Hz** (§1.2): ≤ 12 nearest fighters within 48 m, while active. One empty list when the last one
  goes.
- [ ] **`mobdmg`:**
  - On the server thread: `mob.hurtServer(frac × maxHealth)` with `damageCooldownTime = 0`.
  - The source is `mobAttack(proxy of by)` when that proxy exists (so the mob turns on that NPC), else generic.
- [ ] **`hit.mob`:** a proxy hit whose attacker (or arrow owner) is a mob carries `mob:<entity id>` (extend
  `Wire.hit` with an overload).
- [ ] JUnit:
  - the fighter filter;
  - `mobs` JSON (`Wire.mobs`) with the 12-cap and nearest-first order;
  - `mobdmg` parse and validation;
  - the hold-off rule as a pure function.
- [ ] Commit.

### M3 elytra flight (layer 4)
- [ ] **Glide start** (client):
  - Conditions: `key jump` down while Steve wears a usable elytra and the host's feet fell faster than 1 m/s over the
    last 3 ticks (from `cam.pl`).
  - Auto-start when they fall faster than 15 m/s for 0.5 s with an elytra.
  - Then: gravity on, the velocity measured from the host feet, start fall flying (the vanilla client path that sends
    the start-fall-flying command).
- [ ] **Drive mode** (`PlayerSync`, port the reference):
  - While gliding, Minecraft physics move the player.
  - The look comes from `cam.r`, or from summed `look` deltas when any arrived during this flight (fallback).
  - The camera stays first person at the host camera.
  - Send `flight {active:true,p,v,yaw,pitch}` every frame (≤ 120 Hz).
  - Fireworks boost as vanilla: `use` with a rocket.
- [ ] **Touchdown:**
  - On the ground on a barrier/block, or fall flying stopped: send `flight {active:false, p:<feet>}`.
  - Leave drive mode, `setNoGravity(true)` again, and resume following `cam`.
  - Ignore `cam` positions for 0.5 s after touchdown, until the host has placed V.
- [ ] **Vanilla elytra rules stay:** durability, fall/kinetic damage to Steve.
- [ ] **Fall damage from Cyberpunk:** `dmg kind:fall` is ignored while gliding and for 2 s after touchdown. It is
  always ignored with Slow Falling.
- [ ] JUnit: glide-start conditions; touchdown rule; `flight` JSON (pure functions over numbers). Commit.

### M4 projectiles and ender pearls (layer 4)
- [ ] **`proj` 10 Hz** (§1.2):
  - Projectiles whose owner is Steve: pearls, arrows, tridents, firework rockets, thrown potions, wind charges, fishing
    hooks.
  - `v` in m/s; `g` = that projectile class's gravity × 400 (blocks/tick² → m/s²).
  - One empty list after.
- [ ] **`pcells`:** each cell becomes a managed barrier (only into air), tracked separately from the `ground`
  barriers and removed after 3 s unless the probes made it ground meanwhile. `clear` removes them too.
- [ ] **Ender pearl:**
  - When Steve's pearl lands while active: send `teleport {p: landing feet, yaw: Steve's yaw}`.
  - Keep vanilla's pearl damage to Steve.
  - Do **not** move the player in Minecraft (the host moves V, `cam` follows). Skip the endermite spawn.
- [ ] JUnit: `proj` JSON and gravity scaling; the pcells TTL bookkeeping (pure). Commit.

### M5 interactive items (layer 5)
- [ ] **Lead:**
  - Make `ProxyEntity` leashable (the 26.3 `Leashable` interface; look up the contract).
  - Leashing sends `lead {target, anchor:null|knot pos, length: humans 5, vehicles 8}`. Removing, dropping or
    breaking it sends `lead {target, release:true}`.
  - The proxy never moves from the leash itself (the host moves it).
  - `unlead` from the host drops the leash with its item.
- [ ] **Fishing rod:** reeling in while the hook holds a proxy sends `pull {target, toward: Steve's eye pos, strength:
  humans 9, vehicles 7}` instead of vanilla's pull. Hooks must be able to catch proxies (verify the entity-hit filter).
- [ ] **Wind charge:**
  - Explosions with `Level.ExplosionInteraction.TRIGGER` (wind charges, breeze charges; confirm) send
    `knock {pos, radius, strength: 12 × power}` and **no** `explosion`. Change the `ExplosionEvents` filter.
  - Vanilla knockback in Minecraft stays.
- [ ] **Shield:**
  - Host `dmg` of kind `bullet` or `explosion` with `from` uses a damage source with that source position, so
    vanilla blocking applies: blocked while raised and facing `from`, with durability, sound and stat.
  - Verify with the 26.3 blocking code path and adjust `HostDamage.source` if needed.
- [ ] **Potions:** a mixin on Steve's effect add/update/remove (server player only) sends `effect {name, seconds,
  amp}` (§1.2; infinite → 86400; removal → 0) while active, and the full current set again on host `hello`.
- [ ] **Perk hooks (Bridge.perkRank):**
  - `gorilla_arms`: melee impulse ×2 on humans, ×3 on vehicles (`ProxyEntity.impulse`).
  - `headhunter`: arrow damage ×2 when the arrow's position at impact is within 0.35 m of a human proxy's top.
  - `demolitions`: radius ×1.5 for explosions whose causing entity is Steve (or owned by him). Modify the radius
    before `ServerLevelMixin` reports it, so the reported power is the real one.
- [ ] JUnit for every pure rule (lead JSON, pull strengths, the TRIGGER filter, the headhunter band, the impulse
  scaling). Commit.

### M6 oracle
- [ ] With `gradlew runClient -Pcyberblock.port=25711 "-Pcyberblock.frame=Local\CyberblockFrameL3M"` (observe the
  §0 memory check):
  1. Run `uv run python -m tools.fakehost --port 25711 --layer3 --layer4 --layer5` (P's flags; if they are not on main
     yet, drive the link with a tiny local script and never commit it).
  2. Use `--cmd` to summon a zombie and a skeleton next to the proxies, give Steve an elytra, rockets, pearls, a lead,
     a fishing rod, wind charges, a shield and a speed potion.
- [ ] Confirm `mobs`, `hit` with `mob`, `flight` start/landing, `proj`/`pcells`, `teleport`, `knock`, `effect` and
  `blocks` (reset + cells after reconnect). Record outputs in the report.
- [ ] Commit fixes.

**M done when:** `gradlew.bat test build` green; the M6 oracle shows every message type of layers 3–5; nothing in C's
paths changed.

---

## Track C — Minecraft cyber, layer 6 (Opus)
Look up every 26.3 and Fabric API name in the Loom sources (items and item models, GUI rendering, key mappings, HUD
layers, data attachments, commands, world rendering). Singleplayer only (spec non-goal): client code may hand work to
the integrated server with `Minecraft.getSingleplayerServer().execute(...)`. Prefer a synced data attachment for state
the client draws.

### C0 scaffolding
- [ ] Create the local, uncommitted `Bridge.java` copy (§1.6).
- [ ] `dev.cyberblock.cyber.CyberMod` (main) and `dev.cyberblock.client.cyber.CyberClient` (client) entrypoints.
- [ ] `fabric.mod.json` entries and both mixin configs (§1.6).
- [ ] Persistent player attachments, copied on death: `perks` (id → rank), `ram` (int), `kitGiven` (bool).
- [ ] `Bridge.perkRank` reads the local server player's `perks`.
- [ ] Commit.

### C1 items and assets
- [ ] **Register the items:**
  - the 10 quickhack items of §1.5 (ids `cyberblock:redstone_charge`, `blaze_chip`, `ink_sac_daemon`,
    `cobweb_daemon`, `spider_eye_virus`, `wither_skull_daemon`, `compass_ping`, `bottle_forgetting`, `creeper_brain`,
    `redstone_lever`): stack size 1, reusable, per-item cooldown 2 s (10 s for synapse_burnout and cyberpsychosis);
  - `cyberdeck` (needed in the inventory to hack), `ram_shard` (consumable, +4 RAM);
  - stretch: `mantis_blade` (sword: 7 damage, attack speed 1.8), `gorilla_gauntlet` (weapon with knockback +2 and
    4 damage). The proxy impulse follows knockback, so it shoves cars.
- [ ] **Assets:**
  - Textures: copy `art/items/*.png` to `mc/src/main/resources/assets/cyberblock/textures/item/` (our own art).
  - 26.3 item model definitions and models, `lang/en_us.json` (item names, the key category and key names, the screen
    texts), a creative tab.
  - Shapeless recipes in `data/cyberblock/recipe/` from vanilla items, e.g. Redstone Charge = redstone + iron nugget +
    copper ingot; one each.
- [ ] JUnit: the item table matches §1.5 (hack name, RAM cost) one to one. Commit.

### C2 RAM
- [ ] **Server rules:**
  - max RAM 8 + 4 with `extra_ram`; 0 without a cyberdeck in the inventory;
  - +1 every 3 s (every 1.5 s with `overclock`) up to max;
  - `ram_shard` +4 up to max.
  - Pure `Ram` rules class.
- [ ] **HUD bar:** `art/gui/ram_bar.png` above the hotbar, left of the XP bar area, with the value as segments. It is
  visible only with a cyberdeck. It must be in the overlay Minecraft exports (it is part of the HUD).
- [ ] JUnit for regen, cap, cost with `cheap_hacks`. Commit.

### C3 keybinds
- [ ] **Key mappings** in a "Cyberblock" category: Hack (default G) and Skill tree (default K), rebindable in
  Minecraft's Controls.
- [ ] **Host keys:** the `key` listener sets them down/up from `key hack` and `key skills` (on the client thread,
  with the click count, as `ClientInput` does).
- [ ] **`binds`:** send `{"t":"binds","keys":{"hack":vk,"skills":vk}}` on host `hello` and whenever a binding changes.
  - The VK mapping is a pure function: letters → 0x41–0x5A, digits → 0x30–0x39, F1–F12 → 0x70–0x7B.
  - Any other key maps to the default (G/K), and the screen notes "Cyberpunk only forwards letters, digits and F-keys".
- [ ] JUnit for the VK mapping. Commit.

### C4 scan (hack key held)
- [ ] **While Hack is held** (every 4 ticks):
  1. Pick the proxy under the crosshair within 48 blocks: look-vector entity pick that ignores invisibility, capped by
     `HostState.aimLimit` so nothing behind Night City walls is picked.
  2. Find its id with `Bridge.proxyIdOf` on the server thread.
  3. Send `scan {id, target}`, or `scan {id, target:null}` when no proxy is hit (Cyberpunk then scans the device or
     NPC V looks at).
  4. Send only when the target changes or every 1 s, at most 5/s.
- [ ] **`scaninfo` ok:**
  - Draw a Minecraft-style tooltip next to the crosshair: name, faction, threat colour-coded, an HP bar.
  - Draw a glowing outline box: the proxy's bounding box, or `p` + `size` for devices. Render in the world pass so the
    outline is composited at the NPC's place in Night City.
  - It stays until 1 s after release.
  - The scanned target becomes the **locked target** for 5 s, kept on the server.
- [ ] JUnit for the request throttling and the scaninfo parse/validation. Commit.

### C5 quickhacks
- [ ] **Using a quickhack item** (server):
  1. Needs a locked target; else an action-bar message: "Hold G and look at a target".
  2. `redstone_lever` needs a device target; the others need a human or vehicle proxy target.
  3. Needs enough RAM (cost − `cheap_hacks`).
  4. Then deduct RAM, start the item cooldown and send `hack {id, target, hack, chain: chain_hack owned}`.
- [ ] **`hackresult`:** `ok:false` → refund the RAM and show the reason on the action bar; `ok:true` → the hack sound
  in Minecraft.
- [ ] JUnit for the use rules (pure). Commit.

### C6 skill tree (K)
- [ ] **Screen:**
  - Advancement-style, on `art/gui/skill_tree_bg.png`.
  - Five branch columns with `art/items/skill_*.png` headers; four perks each (§1.5), using `perk_token.png` for
    owned perks.
  - The cost in levels, the prerequisite, and the Minecraft and Cyberpunk effect text (§1.5) on hover.
  - Driven by the host cursor and clicks like every screen.
- [ ] **Buying:**
  - Server side: check tier order and `experienceLevel ≥ cost`, take the levels, set the rank.
  - Send `perks` (all 20) after each purchase and on every host `hello`.
- [ ] JUnit for the purchase rules and the `perks` JSON. Commit.

### C7 perk effects in Minecraft (§1.5, owner C rows)
- [ ] Implement each C-owned row:
  - `iron_skin`, `juggernaut`: attribute modifiers with fixed ids, re-applied on respawn.
  - `painkiller`: a regeneration-interval mixin.
  - `sandevistan`: `key sprint` double-tap. Speed II 5 s, `tick rate 10` for 5 s then `tick rate 20` (also restored on
    disconnect and stop), cooldown 20 s, `perkfx`.
  - `quick_draw`: charge time ×0.7.
  - `tinkerer`: crafted TNT/arrows ×2.
  - `smart_arrows`: per-tick steering of Steve's arrows, ≤ 6°/tick toward the nearest human/vehicle proxy in a 20°
    cone, ≤ 32 m.
  - `field_medic`: food nutrition and saturation ×1.5; instant health ×1.5.
  - `optical_camo`: as §1.5.
  - `cold_blood`: as §1.5.
  - `silent_steps`: a mob's targeting range for Steve ×0.5.
- [ ] JUnit for each pure rule (double-tap timing, camo stillness timer, homing cone, multipliers). Commit.

### C8 starter kit and NPC kill XP
- [ ] `/cyberblock kit` (permission level 0) gives:
  - the cyberdeck, 2 RAM shards and one of each quickhack item;
  - 64 stone, 64 glass, 8 TNT, flint and steel;
  - bow + 32 arrows, a shield, an iron sword;
  - 2 leads, a fishing rod, 8 wind charges, 8 ender pearls;
  - an elytra and 16 firework rockets;
  - speed, invisibility and jump-boost potions;
  - spawn eggs: zombie ×4, skeleton ×2, creeper ×2;
  - mantis blade and gauntlet if C1's stretch is done.
- [ ] Given automatically on the first `state {active:true}` while in survival when `kitGiven` is false: the elytra is
  equipped if the chest slot is empty, the rest goes to the inventory (overflow dropped at Steve's feet); then
  `kitGiven = true`.
- [ ] **`killed`:** XP orbs worth 5 (hostile: 10) at the proxy's position, or at Steve if it is gone; plus
  `cold_blood`.
- [ ] JUnit for the kit list and the XP rule. Commit.

### C9 oracle
- [ ] With `gradlew runClient -Pcyberblock.port=25712 "-Pcyberblock.frame=Local\CyberblockFrameL3C"` (observe the §0
  memory check), run `uv run python -m tools.fakehost --port 25712 --layer2 --layer6`.
- [ ] Confirm:
  - the kit arrives on activation;
  - `binds` and `perks` on hello;
  - holding `key hack` sends `scan` and draws the tooltip/outline: save an exported overlay PNG with `tools/shm.py`
    and the track's frame name;
  - a Redstone Charge sends `hack` and the RAM drops;
  - K opens the screen;
  - `killed` drops XP.
- [ ] Record outputs. Commit fixes.

**C done when:** `gradlew.bat test build` green on C's branch with the uncommitted Bridge copy; the C9 oracle passes;
C committed nothing outside its paths.

---

## Track S1 — redscript core & DISABLE (Opus)

### S1.1 natives first
- [ ] Append the §1.3 block to `Natives.reds` verbatim. compile_check OK. Commit this **before anything else** (the
  other S tracks stub the same signatures).

### S1.2 DISABLE list (audit A.2–A.4)
- [ ] **`PlayerState.reds`, while Active (on foot):**
  - Also own `GameplayRestriction.NoHealing`, `NoScanning`, `NoRadialMenus`, `NoQuickMelee`, `NoZooming` and
    `BodyCarryingForceDrop`, applied as the existing ones are. They are released in every other state, before saves
    and on load.
  - Zero the audit A.4 ability stats with runtime `StatsSystem.AddModifier` multiplier-0 modifiers. Keep the modifier
    objects and remove exactly them in `Clear`/`ClearSaved`.
  - **Perk exceptions** (re-evaluated every tick): with `CyberblockPerks.Has(game, n"double_jump")`, do not zero
    `HasDoubleJump`; with `n"dodge"`, do not zero `HasDodge` and `HasDodgeAir`.
- [ ] **`Disable.reds`** (new; compile-checked snippets from the audit appendix, gated on Cyberblock's god mode source
  on V):
  - `HandleQuickSave` wrap (F5);
  - `ProcessToggleWalkInput` wrap (G);
  - `IsNewPerkBought` wrap: Reflexes milestone 2/3 report bought only with `CyberblockPerks.Has(game, n"dodge")`,
    else not bought.
  - Keep the wraps exactly as narrow as the audit's.
- [ ] **Menus:** each tick, `Cyberblock_SetMenu(UI_System.IsInMenu)` (audit appendix `InMenu`).
- [ ] Compile. Commit.

### S1.3 health and creative
- [ ] `Health.reds`:
  - Add the audit D `IsImmune` DoT wrap verbatim (thermal ticks report as `fire`).
  - Skip `fall` reports while `CyberblockFlight.SuppressFall(game)`.
- [ ] Creative: while in Vehicle state with `Cyberblock_GameMode() == n"creative"`, add god mode
  `gameGodModeType.Invulnerable` with source `n"CyberblockCreative"` (it is not `n"Cyberblock"`, so Health does not
  report and the audit wraps stay off). Remove it otherwise, before saves, on load and at Off.
- [ ] Compile. Commit.

### S1.4 event dispatch
- [ ] `Events.reds` dispatches the §1.3 event table to §1.4:
  - `hit` → `HitFrom` with `Cyberblock_EventId()`;
  - `block_reset` → `CyberblockCollision.Reset` (and it also clears the pending replay list);
  - `teleport` → `CyberblockEffects.Teleport`;
  - `lead`, `lead_release`, `pull`, `knock` → `CyberblockTether.*`;
  - `effect` → `CyberblockBuffs.Effect`;
  - `hack` → `CyberblockHacks.Hack`;
  - `scan` → `CyberblockScan.Scan`;
  - `perk` → `CyberblockPerks.Set`;
  - `perkfx` → `CyberblockPerks.Fx`.
- [ ] **While not live:**
  - `block_*`/`block_reset` are kept in order (as now);
  - `perk` is applied;
  - `hack`/`scan` are answered directly with `ok:false`, reason "Cyberblock is not active";
  - all else is dropped (counted, as now).
- [ ] Compile with stubs for S2/S3. Commit.

### S1.5 probes
- [ ] Ignore hits on our collision boxes: `TraceResult` hit entity (Codeware) tagged `n"CyberblockCollision"` in
  `StaticEntitySystem` (collision-spike "Side effects"). Same filter for `Cyberblock_SetAim`.
- [ ] **During flight** (`CyberblockFlight.IsFlying`):
  - radius `min(64, 24 + speed)` (speed from V's position delta per tick);
  - down rays reach `feet.z − (10 + speed)`;
  - columns inside a ±60° cone ahead of the horizontal velocity are probed first, on a 2 m grid beyond 24 m;
  - budget unchanged.
- [ ] Compile. Commit.

### S1.6 wiring
- [ ] `Cyberblock.reds`:
  - Each tick, call `CyberblockPuppets.Tick`, `CyberblockFlight.Tick`, `CyberblockTether.Tick`,
    `CyberblockProjectiles.Tick` (live = Active or Vehicle, not saving) and `CyberblockPerks.Tick` /
    `CyberblockBuffs.Tick` (active = Active, not saving).
  - `Cyberblock_SetStance(crouched)` from the player state machine locomotion state (confirm the 2.31 blackboard
    definition and enum).
  - Call all §1.4 `Clear`s at the §1.4 points.
- [ ] Compile with stubs. Commit, after deleting the stub file.

**S1 done when:** compile_check OK with only committed files plus the S2/S3 stubs; every audit A.2 "No" row is covered
or explicitly listed as in-game-only; `needsInGame` lists audit G-1, G-2, G-9…G-14 with the audit's checks.

---

## Track S2 — redscript world effects, layers 3–5 (Opus)

### S2.1 puppet spike
- [ ] **Question:** what makes invisible Cyberpunk stand-ins that NPCs and NCPD shoot at, that never fight back and
  never die?
- [ ] Research (scripts, TweakDB, Codeware `DynamicEntitySystem`):
  - an NPC record to spawn: a hostile ganger without loot/quest ties;
  - hiding its meshes, as PlayerState hides V;
  - god mode `Immortal` so damage is still computed;
  - no attacks: unequip, plus a restriction or AI flag; confirm;
  - an attitude that makes civilians flee and NCPD engage (attitude group or `SetAttitudeTowards`; threat injection
    into nearby NPCs' target trackers);
  - teleporting it each tick.
- [ ] Write `docs/notes/puppet-spike.md` with the evidence table and the in-game checks. The fallback (spec layer 3)
  is always available: no puppets, so mob hits are attributed to V and NPCs target V; damage still lands on Steve.
- [ ] Commit.

### S2.2 puppets
- [ ] `Puppets.reds` (§1.4):
  - Up to 12 puppets matching `Cyberblock_MobCount` snapshot ids. Spawn, move to `MobPos`/`MobYaw` each tick, despawn
    when the id goes. Use a capsule matching `MobSize` where a template allows it.
  - A `@wrapMethod(DamageSystem) DealDamages` for puppet targets:
    - report `damage / maxHealth` with `Cyberblock_MobDamage(id, frac, attacker hash, hasAttacker)`;
    - apply nothing to the puppet;
    - leave non-puppet hits to the existing wraps.
  - Fallback switch `CyberblockPuppets.SetEnabled(false)`, callable from CET and stored in a `ScriptableService`. When
    disabled, or when the spike found no working method, it spawns nothing.
- [ ] Compile. Commit.

### S2.3 mob attribution and teleport
- [ ] `Effects.reds`:
  - `HitFrom`: as `Hit`, with the mob's puppet as instigator/source when it exists, else V.
  - `Teleport`: V to `feet` with yaw, keeping camera pitch (confirm `TeleportationFacility.Teleport` and how to keep
    the look). Start the 2 s fall suppression shared with Flight.
- [ ] Compile. Commit.

### S2.4 flight
- [ ] **Spike, written to `docs/notes/flight-spike.md`:**
  - a per-frame script hook (confirm `DelaySystem.DelayCallbackNextFrame`);
  - per-frame `TeleportationFacility.Teleport` of V that keeps the mouse look (pass V's current orientation);
  - what Cyberpunk's locomotion does when V is moved in mid-air (fall state, landing animations).
- [ ] **`Flight.reds` (§1.4), primary mode:**
  - When `Cyberblock_FlightActive()` turns true, run a per-frame loop that moves V to `Cyberblock_FlightPos()`, until
    `FlightSeq` changes with inactive.
  - Then place V at the landing spot.
  - `SuppressFall` is true while flying and for 2 s after.
- [ ] **Fallback camera-only mode** (`CyberblockFlight.SetCameraOnly(true)`, stored in a service, callable from CET):
  - V is frozen and hidden in place;
  - a camera entity is spawned (confirm `base\entities\cameras\simple_free_camera.ent` from the collision-spike
    evidence) and activated;
  - each frame it is moved to the flight position + 1.6 m with `FlightYaw`/`FlightPitch`;
  - `Cyberblock_SetLookCapture(true)` is on;
  - on landing, V is teleported there, the camera deactivated and despawned, look capture off.
- [ ] `Clear` restores everything in both modes. Compile. Commit.

### S2.5 tethers and knockback
- [ ] `Tether.reds` (§1.4):
  - **Lead:** each tick, if a tethered target is farther than `length` from its anchor (V's position when not fixed),
    push it toward the anchor:
    - humans: ragdoll impulse `min(12, 3 × excess)` m/s;
    - vehicles: `CyberblockEffects.Knock`-style impulse scaled by mass.
  - **Snapping:** more than `length + 12` for 2 s, or the target is gone → `Cyberblock_Unlead`.
  - **Pull:** a single impulse toward `toward` at `strength`, plus 3 m/s up for humans.
  - **Knockback:** radial impulse `strength × (1 − d/radius)` on every NPC and vehicle within `radius`, no damage, V
    excluded.
  - Protected targets (god mode, friendly, scenes/workspots) are skipped, exactly as `CyberblockEffects.Hit` does.
- [ ] Compile. Commit.

### S2.6 projectile lookahead
- [ ] `Projectiles.reds`: each tick, for each `Cyberblock_ProjCount` entry:
  - trace the ballistic path for the next 0.6 s in 4 segments (`p + v·t − ½·g·t²` along −Z) with the Probes filter
    (Static + Terrain; skip our collision boxes);
  - collect the first hit per projectile;
  - `Cyberblock_SubmitProjectileHits(hits)`.
- [ ] Compile. Commit, after deleting the stub file.

**S2 done when:** compile_check OK with stubs; both spike notes list concrete CET checks; fallbacks for puppets and
flight exist behind their switches.

---

## Track S3 — redscript powers, layer 6 + potions (Opus)

### S3.1 quickhacks
- [ ] `Hacks.reds` (§1.4), using audit B's `PuppetAction` pattern (`SetCanSkipPayCost(true)`; `SetDisableSpread(true)`
  except contagion):
  1. Resolve the target with `EntityID.FromHash` + `FindEntityByID`.
  2. Puppet hacks need a living `ScriptedPuppet`. `device` needs a `Device`: `QuickHackDistraction` for media and
     vending; `ForceIgnoreTargets` for cameras; the intersection flip for traffic lights; else the first available
     quickhack action.
  3. Never use `ToggleTakeOverControl`.
  4. `chain`: also hack the nearest other living NPC within 8 m of the target.
  5. Answer `Cyberblock_HackResult(id, ok, reason)` every time. Reasons: "target gone", "not hackable",
     "no such hack".
- [ ] Confirm every §1.5 TweakDB id (`tweakdb.bin` / `tweakdb_ep1.bin`); use the lowest existing tier. Compile.
  Commit.

### S3.2 scan
- [ ] `Scan.reds`:
  - Target 0 → the object V looks at (`TargetingSystem.GetLookAtObject`, confirm).
  - Name: the localized display name. Faction: the affiliation display name. Device: the device name.
  - Threat: from the power-level difference, as the scanner does (confirm the 2.31 helper).
  - HP: fraction of the Health stat pool.
  - Position and size: humans 0.6 × 1.8, vehicles from their bounding box if available, else 2 × 1.6; devices 1 × 2.
  - Always answer `Cyberblock_ScanResult`.
- [ ] Compile. Commit.

### S3.3 perks
- [ ] `Perks.reds`, a `ScriptableService` holding the ranks:
  - `double_jump`: Additive +1 `HasDoubleJump` modifier while Active (audit C.2 `GrantStat`).
  - `silent_steps`: `Visibility` ×0.5 (audit C.4).
  - `Fx(n"sandevistan", s)`: audit C.1 `SlowMo(0.2)` for `s` seconds, then `SlowMoEnd`, with reason
    `n"cyberblockSlowMo"`. Never stack.
  - `dodge` has no modifier here: S1 reads `Has`.
  - `Tick(active false)` and `Clear` remove every modifier and end slow-mo.
- [ ] Compile. Commit.

### S3.4 buffs (potions)
- [ ] `Buffs.reds`: the §1.5 effect mapping with expiry:
  - `seconds` 0 removes;
  - re-sending refreshes;
  - stat modifiers are kept and removed exactly;
  - camo through audit C.3 (`OpticalCamoPlayerBuffRare` + `SetInvisible`).
- [ ] Confirm the speed and jump stats the 2.31 locomotion reads, and the nausea and blind status-effect records (in
  the TweakDB). Map nothing you cannot confirm; log it.
- [ ] Compile. Commit.

### S3.5 kills
- [ ] `Kills.reds`: wrap the 2.31 NPC death and vehicle destruction handlers (confirm which).
  - When the killer or instigator is V and Cyberblock's god mode is on V, call
    `Cyberblock_Killed(hash, vehicle, hostile)` once per entity.
- [ ] Compile. Commit, after deleting the stub file.

**S3 done when:** compile_check OK with stubs; every §1.5 Cyberpunk-side row is implemented or listed as blocked with
its reason; `needsInGame` has audit G-3…G-8 plus a check per hack and per buff.

---

## Integration (main session, after all tracks)

### I.1 Merge and suites
- [ ] Merge `l3/P`, `l3/D`, `l3/M`, `l3/C`, `l3/S1`, `l3/S2`, `l3/S3` into main (`--no-ff`, in this order). Make sure
  no `zz_stub_*.reds` and no second `Bridge.java` came along.
- [ ] Contract cross-checks:
  - S1's Natives.reds against D's registrations (names and types);
  - every §1.2 type: a sender, a parser and an example in protocol.md;
  - the §1.4 signatures S1 calls against S2/S3's definitions.
- [ ] All suites green: pytest, `gradlew.bat test build`, cmake build + cb_tests, compile_check.
  `uv run python -m tools.gen_vectors` gives no diff.
- [ ] Install with `uv run python -m tools.install --backups D:/Cyberblock-backups`, only while
  `Cyberpunk2077.exe` is not running.

### I.2 In-game acceptance (user, morning)
- **Layer 2 (never run yet):**
  - an NPC shoots V → Steve's hearts drop;
  - a wall stops a pedestrian and a car;
  - TNT under a car → explosion and heat;
  - sword and bow hit pedestrians;
  - flint and steel ignites an NPC.
- **Layer 3:**
  - spawn eggs from the kit next to pedestrians: a zombie kills a pedestrian;
  - puppets get no proxy: with a zombie and a creeper next to Steve and pedestrians, the log's `npcs: N sent` count
    does not grow by one per mob, the creeper walks to a pedestrian before it fuses, and Steve's sword damages the
    zombie on the first swing (no invisible human proxy standing on the mob);
  - with heat 2, an NCPD officer kills a skeleton (puppets). If not, `CyberblockPuppets.SetEnabled(false)` and confirm
    the fallback.
- **Layer 4:**
  - glide from a megabuilding roof with fireworks and land on a street (if it jitters:
    `CyberblockFlight.SetCameraOnly(true)`);
  - an ender pearl onto a rooftop teleports V.
- **Layer 5:**
  - drag a pedestrian with a lead;
  - reel a car with the fishing rod;
  - a wind charge knocks a crowd;
  - a raised shield facing a shooter blocks the hits;
  - an invisibility potion → camo; a speed potion → V faster.
- **Layer 6:**
  - G shows the tooltip and outline;
  - a Redstone Charge short-circuits an NCPD officer;
  - the RAM bar drains and regenerates;
  - K → buy Double Jump → V double-jumps;
  - Sandevistan slows Night City;
  - `/cyberblock kit` refills.
- **DISABLE:**
  - F5, G, K, E, Q, T and 1–9 do nothing to V;
  - Tab does not scan; Alt opens no wheel; X does not heal; no dodge/air dash without the perk;
  - leaving to a vehicle restores the radio wheel and everything else;
  - saving while Active, then loading without the mod, gives a vanilla V.
  - Audit G-1…G-14.
- **Creative:** V cannot die in a vehicle; Steve takes no damage.
