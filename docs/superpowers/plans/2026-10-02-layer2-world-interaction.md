# Cyberblock Layer 2 (World Interaction) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Minecraft actions act on Night City and Night City acts on Steve: Cyberpunk damage hits Steve's hearts
(Steve's health is the only health), placed blocks are solid to NPCs and cars, TNT/creepers/firework impacts are real
Cyberpunk explosions, Minecraft weapons damage Cyberpunk NPCs and vehicles, fire sets them burning.

**Architecture:** Same three parts as layer 1 (spec §3). New wire messages both ways; the DLL converts Minecraft
positions to Cyberpunk and queues Minecraft events for redscript, which applies them with real game systems.
Cyberpunk NPCs/vehicles near V become invisible **proxy entities** in Minecraft so Minecraft weapons can hit them.

**Tech Stack:** as layer 1. Spec: `docs/superpowers/specs/2026-10-02-minecraft-cyberpunk-passthrough-design.md` §5.

---

## 0. Ground rules (all tracks)
- Layer 1 rules (plan `2026-10-02-layer1-core-passthrough.md` §0) apply: own directories only, own worktree, commit per
  task with the `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` trailer, never write into or launch the game,
  no system installs, no downloads beyond build dependencies. **Do not download WolvenKit or any game-modding tool.**
- Every Cyberpunk API must be confirmed from real sources (NativeDB https://nativedb.red4ext.com, the decompiled 2.31
  scripts the layer-1 compile checker uses, Codeware sources) and compile-checked with
  `uv run python cp/scripts/compile_check.py`. Unconfirmable behaviour goes in `needsInGame` with a CET console check.
- Layer 1 state you build on: `main` at the commit this plan is committed in. Read `shared/protocol.md` and the layer 1
  plan §1 first.

| Track | Owns | Model |
|---|---|---|
| P protocol & tools | `shared/`, `tools/`, `tests/` | Sonnet |
| M Minecraft | `mc/` | Sonnet |
| D DLL | `cp/plugin/` | Sonnet |
| S1 redscript core | `cp/scripts/Cyberblock/Cyberblock.reds`, `Natives.reds`, `Health.reds`, `Npcs.reds`, `Events.reds` | Opus |
| S2 redscript effects | `cp/scripts/Cyberblock/Effects.reds`, `Collision.reds` | Opus |

## 1. Contracts (fixed; change only via the integrator)

### 1.1 Coordinates
Minecraft `(x, y, z)` → Cyberpunk `(x, -z, y)` (inverse of layer 1). Minecraft block `(bx, by, bz)` occupies
Cyberpunk AABB min `(bx, -(bz+1), by)` max `(bx+1, -bz, by+1)`. Add `mc_to_cp_pos` and `mc_block_to_cp_aabb` to
`tools/cbmath.py` (truth) and mirror in `cp/plugin/src/cbmath.*`, with vectors in `shared/vectors.json`
(`mc_pos`, `mc_block` cases) checked by pytest and cb_tests.

### 1.2 Wire protocol additions (protocol stays `proto:1`; unknown types are ignored)
Cyberpunk → Minecraft (Minecraft convention):
| `t` | fields | when |
|---|---|---|
| `dmg` | `frac` (0..4: fraction of V's max health the hit would have taken), `kind` ∈ `bullet, explosion, melee, fall, vehicle, fire, other`, `from:[x,y,z]` or `null` | each damage event on V while Active |
| `npcs` | `list:[{id:"<uint64 as decimal string>", p:[x,y,z] feet, yaw, kind:"human"|"vehicle", hostile:bool}]` | 10 Hz while Active, everything within 48 m that is alive; an id missing from the list is gone |

Minecraft → Cyberpunk (Minecraft convention):
| `t` | fields | when |
|---|---|---|
| `block` | `op:"add"|"remove"`, `pos:[bx,by,bz]` | the player or an explosion places/removes a solid non-barrier block |
| `explosion` | `pos:[x,y,z]`, `power` (Minecraft radius: TNT 4, creeper 3, charged creeper 6, firework 1.5), `fire:bool` | every explosion in the Minecraft world while the host is attached |
| `hit` | `target:"<id>"`, `dmg` (Minecraft health points after armour), `kind` ∈ `melee, arrow, trident, mob, explosion, fire`, `impulse:[x,y,z]` m/s | a proxy takes damage |
| `ignite` | `target:"<id>"`, `seconds` | a proxy is set on fire (fire charge, flint & steel, lava, fire aspect, flaming arrow) |

Scaling (single place each): `dmg.frac` → Steve takes `frac × Steve max health × 1.0` (Minecraft side).
`hit.dmg` → Cyberpunk applies `dmg / 20` of the target's max health (redscript side).

### 1.3 DLL ↔ redscript natives (append to `Natives.reds`; S1 owns the file, D implements)
```swift
public native func Cyberblock_Damage(frac: Float, kind: CName, from: Vector4, hasFrom: Bool) -> Void;
public native func Cyberblock_SubmitNpcs(ids: array<Uint64>, feet: array<Vector4>, yaws: array<Float>, vehicles: array<Bool>, hostile: array<Bool>) -> Void;
// Event queue (Minecraft -> Cyberpunk), already converted to Cyberpunk space. NextEvent pops one and makes it current.
public native func Cyberblock_NextEvent() -> CName;      // n"explosion" | n"hit" | n"ignite" | n"block_add" | n"block_remove" | n"" when empty
public native func Cyberblock_EventPos() -> Vector4;     // explosion centre; block AABB min corner
public native func Cyberblock_EventTarget() -> Uint64;   // hit/ignite target (EntityID hash)
public native func Cyberblock_EventAmount() -> Float;    // explosion power | hit dmg/20 (fraction of target max health) | ignite seconds
public native func Cyberblock_EventVector() -> Vector4;  // hit impulse (Cyberpunk space, m/s)
public native func Cyberblock_EventFlag() -> Bool;       // explosion fire
```
The DLL bounds the queue at 256 events (drops oldest) and never blocks the link thread.

### 1.4 S1 ↔ S2 script API (S2 implements exactly these; S1 calls them)
```swift
public abstract class CyberblockEffects {
  public static func Explode(game: GameInstance, pos: Vector4, power: Float, fire: Bool) -> Void;
  public static func Hit(game: GameInstance, target: Uint64, frac: Float, impulse: Vector4) -> Void;
  public static func Ignite(game: GameInstance, target: Uint64, seconds: Float) -> Void;
}
public abstract class CyberblockCollision {
  public static func Add(game: GameInstance, aabbMin: Vector4) -> Void;     // 1 m invisible solid box
  public static func Remove(game: GameInstance, aabbMin: Vector4) -> Void;
  public static func Stream(game: GameInstance, center: Vector4) -> Void;   // keep boxes within 64 m spawned, cap 1000
  public static func Clear(game: GameInstance) -> Void;                     // despawn all (deactivate, load, before save)
}
```

---

## Track P — protocol & tools
### P1 coordinates back
- [ ] TDD in `tests/test_cbmath.py`: `mc_to_cp_pos(1,3,-2) == (1,2,3)`; round trip with `cp_to_mc_pos`;
  `mc_block_to_cp_aabb(10,4,-21) == ((10,20,4),(11,21,5))`. Implement in `tools/cbmath.py`; extend
  `tools/gen_vectors.py` with 6 `mc_pos` and 6 `mc_block` cases; regenerate; `uv run pytest -v` green. Commit.
### P2 protocol doc
- [ ] Add §1.2 rows and one JSON example per new type to `shared/protocol.md`; `tests/test_protocol_md.py` green
  (extend its type lists for the new directions' examples). Commit.
### P3 fake host for layer 2
- [ ] `tools/fakehost.py --layer2`: after the layer-1 script, sends 3 `npcs` (two humans, one vehicle) around the
  player, then a `dmg {frac:0.25,kind:"bullet"}`; prints every Minecraft→Cyberpunk message it receives
  (`block`, `explosion`, `hit`, `ignite`). Test with the existing local fake-Minecraft server harness. Commit.

## Track M — Minecraft
Look up 26.3 names in the Loom sources jar (`gradlew genSources`); never guess.
### M1 block events
- [ ] Re-enable WorldBridge's block-change reporting as `block {op,pos}` for solid non-barrier blocks changed by the
  player or explosions (not by `ground`/`unground`). JUnit for the filter (`BlockEvents.shouldReport(state, cause)`). Commit.
### M2 explosion events
- [ ] Mixin on the server explosion entry point: send `explosion {pos,power,fire}` for every explosion (TNT 4,
  creeper 3/6, firework rocket impact 1.5 — fireworks are not vanilla explosions: hook the rocket's explode/hit and
  send power 1.5). Keep vanilla Minecraft explosion behaviour (blocks break in Minecraft; barriers are immune —
  verify and enforce barrier immunity). Commit.
### M3 proxies
- [ ] `npcs` → `ProxyManager` (server thread): one invisible, invulnerable-to-everything-but-players-and-mobs,
  no-AI, no-gravity entity per id at `p`/`yaw`; humans 0.6×1.8, vehicles 2.0×1.6 (use a custom `EntityType` with
  those dimensions registered by the mod; invisible renderer). Ids missing from a message despawn. Validate every
  number (finite, ranges) like HostState; bad entries are skipped, never crash. JUnit for the diff logic
  (`ProxyDiff.apply(currentIds, message) -> spawn/move/remove`).
- [ ] Damage to a proxy (any source) is cancelled in Minecraft and sent as `hit {target,dmg,kind,impulse}` where
  impulse is the attacker's knockback direction × strength (melee: 6 m/s × (1 + knockback level); arrows: arrow
  velocity × 0.5). Arrows/tridents stick or drop as vanilla. Commit.
### M4 damage to Steve
- [ ] `dmg` → on the server thread, `player.hurt(source(kind), frac × player.getMaxHealth())` with a knockback from
  `from` when present; kinds map to vanilla damage types (bullet → generic arrow-like `mob_projectile`, explosion →
  explosion, melee → mob attack, fall → fall, vehicle → `cramming`-free generic, fire → on_fire, other → generic).
  Creative/spectator: ignored by vanilla rules. JUnit for kind mapping + frac validation (0..4, finite). Commit.
### M5 ignite
- [ ] A proxy set on fire (remaining fire ticks > 0 after any vanilla source) sends `ignite {target, seconds}` once per
  ignition, then its fire is extinguished in Minecraft. Commit.
### M6 oracle
- [ ] Run `tools/fakehost.py --layer2` against `gradlew runClient`; with commands (`/summon tnt`, give a bow, shoot a
  proxy) confirm `explosion`, `hit`, `block` arrive; record outputs in the report. Commit fixes.

## Track D — DLL
### D1 cbmath back-conversion + vectors (mirror of P1; tests read `shared/vectors.json`; if P's vectors are not on main
  yet, generate locally like layer 1 C2 did). Commit.
### D2 natives §1.3 + event queue
- [ ] Parse `block`, `explosion`, `hit`, `ignite` from Minecraft (validate: finite, |coords| < 3e7, power 0..20,
  dmg 0..1000, seconds 0..120, ids decimal uint64), convert to Cyberpunk space, push to a mutex-guarded deque (cap 256,
  drop oldest, count drops in the 5 s memory log line). Implement the six event natives over a "current event".
- [ ] `Cyberblock_Damage` → `dmg` (from converted with `cp_to_mc_pos`, `null` when `hasFrom` false; frac clamped 0..4).
- [ ] `Cyberblock_SubmitNpcs` → `npcs` (arrays must have equal length, else log once and drop; ids as decimal strings).
- [ ] cb_tests: queue bounds, parse rejects, conversions. Build + tests green. Commit.

## Track S1 — redscript core
### S1.1 events pump
- [ ] `Events.reds`: each tick while Active or Vehicle drain up to 64 events (`Cyberblock_NextEvent` until n"") and
  dispatch to `CyberblockEffects` / `CyberblockCollision` (§1.4). For compile checks before S2 lands, create a stub with
  the §1.4 signatures in the compile checker's temp tree only (never commit it). Commit.
### S1.2 health link
- [ ] `Health.reds`: while Active (V is in god mode), capture every damage event aimed at V and its would-be damage as
  a fraction of V's max health (confirm the 2.31 hook: e.g. a `@wrapMethod` on the player's hit handling, or
  `gameHitEvent` / attack-computed values; god mode must still deliver the event). Map to `kind`, compute `from`
  (attacker position when known) and call `Cyberblock_Damage`. Falls: detect landing damage the same way. Never block
  the game's own handling. Commit.
### S1.3 NPC and vehicle list
- [ ] `Npcs.reds`: at 10 Hz while Active, collect living NPCs (humans) and vehicles within 48 m (confirm a 2.31 API:
  targeting/entity queries; cap 48 entries nearest-first), with EntityID hash, feet position, yaw, hostile-to-player;
  call `Cyberblock_SubmitNpcs`. Commit.
### S1.4 wiring
- [ ] `Cyberblock.reds`: call the pump, health link, NPC list, and `CyberblockCollision.Stream/Clear` at the right
  state transitions (Clear on deactivate, session end, before save; Stream each tick while Active/Vehicle). Commit.

## Track S2 — redscript effects
### S2.1 spike: runtime collision without shipping game assets
- [ ] Find a way to make an invisible, solid 1 m box at runtime from scripts that blocks NPCs, the player and
  vehicles, without shipping game files: (a) Codeware `DynamicEntitySystem` spawning an existing vanilla template that
  is (or can be made) an invisible static collider, (b) runtime-created collider components on a spawned entity,
  (c) anything else documented. Write findings to `docs/notes/collision-spike.md`. If none works from scripts alone,
  implement `CyberblockCollision` as logging no-ops and report it as blocked (do not download tools). Commit.
### S2.2 collision
- [ ] Implement §1.4 `CyberblockCollision` with the spike's method; track boxes by integer AABB key; stream within
  64 m; cap 1000 (nearest kept); Clear despawns everything. Commit.
### S2.3 explosions, hits, ignite
- [ ] `Effects.reds`: `Explode` → a real Cyberpunk explosion at `pos` scaled by `power` (confirm a 2.31 API used by
  grenades/explosive barrels: damage to NPCs and vehicles, VFX, police reaction); `Hit` → damage the entity whose
  EntityID hash is `target` by `frac` of its max health with `impulse` (ragdoll/knockback where available; vehicles
  take damage and are pushed); `Ignite` → the vanilla burning status effect for `seconds`. Unknown target: no-op.
  Commit.

## Integration (main session)
Merge P, D, M, S1, S2; all suites green; install; in-game: (1) NPC shoots V → Steve's hearts drop, survival death →
Minecraft death screen, respawn via cursor; (2) wall of blocks stops a pedestrian and a car; (3) TNT under a car →
Cyberpunk explosion, NCPD heat; (4) sword and bow hit pedestrians; (5) flint & steel ignites an NPC.
