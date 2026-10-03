# Spike S2.1: invisible 1 m collision boxes at runtime, from scripts only

Question (layer 2 plan, S2.1): can redscript make an invisible, solid 1 m box at runtime that stops the player, NPCs
and vehicles, without shipping any game file and without modding tools?

**Answer: yes, with Codeware alone.** Spawn a tiny vanilla entity with Codeware's `StaticEntitySystem`, and while it
initializes, hide its only mesh and add a `ColliderComponent` built in script (one `physicsColliderBox`, half extents
0.5 m, collision preset `World Static`). Nothing is written to the game folder and no asset is shipped; the
collider is a plain object constructed at runtime. Everything compiles against the real 2.31 scripts plus Codeware
1.20.3 (`cp/scripts/compile_check.py`). Whether the box physically stops each kind of actor can only be confirmed in
game; the checks are listed at the end.

## Method (S2.2 implements it in `cp/scripts/Cyberblock/Collision.reds`)

1. `GameInstance.GetStaticEntitySystem().SpawnEntity(spec)` with
   `spec.templatePath = r"engine\\fallback\\helper_no_entity.ent"`, `spec.position` = the box centre,
   `spec.attached = true`, `spec.tags = [n"CyberblockCollision"]`.
2. A Codeware callback on `Entity/Initialize`, targeted with `StaticEntityTarget.Tag(n"CyberblockCollision")`:
   - `component.Toggle(false)` on every `entMeshComponent` of the entity (the template's helper mesh);
   - `entity.AddComponent(collider)` where `collider` is a new `ColliderComponent` with
     `colliders = [physicsColliderBox{halfExtents = (0.5, 0.5, 0.5)}]`, `simulationType = Static`,
     `filterData = physicsFilterData{preset = n"World Static", simulation and query masks of World Static}`,
     `dynamicTrafficSetting.impact = TrafficGenDynamicImpact.Blocking`.
3. Despawn with `DespawnEntity(id)`; `DespawnTagged(n"CyberblockCollision")` removes every box at once.

## Evidence

| Fact | Source |
|---|---|
| `StaticEntitySystem`, `StaticEntitySpec{templatePath, appearanceName, position, orientation, attached, tags}`, `SpawnEntity/DespawnEntity/DespawnTagged` | Codeware 1.20.3 `Codeware.Global.reds` (installed copy) |
| `Entity.AddComponent`, `Entity.GetComponents`, `EntityLifecycleEvent.GetEntity`, `CallbackSystem.RegisterCallback(...) -> CallbackSystemHandler`, `AddTarget`, `StaticEntityTarget.Tag` | same file |
| Event `Entity/Initialize`: "entity and its components are initializing; entity ID is assigned" | Codeware wiki, Lifecycle events |
| Components must be added while the entity is assembled/initialized, not later | Codeware wiki, Dynamic entities ("To properly initialize components, you have to add components at the right moment") |
| `ColliderComponent` script fields `colliders, simulationType, filterData, dynamicTrafficSetting, ...`; `physicsColliderBox.halfExtents`; `physicsFilterData{simulationFilter, queryFilter, preset, customFilterData}`; `SimulationFilter/QueryFilter.mask1/mask2` | Codeware `@addField`/native declarations; NativeDB dump (`entColliderComponent`, `physicsColliderBox`, `physicsFilterData`) |
| `TrafficGenDynamicImpact { Ignored, Blocking }`, `TrafficGenDynamicTrafficSetting.impact` | NativeDB dump, Codeware declarations |
| The same pattern works in a shipped mod: World Builder / entSpawner (CET) builds `entColliderComponent` + `physicsColliderBox` + `physicsFilterData{preset, masks}` in its `Entity/Initialize` callback and calls `entity:AddComponent` | github.com/justarandomguyintheinternet/CP77_entSpawner `modules/classes/spawn/collision/collider.lua`, `r6/scripts/entBuilder.reds` (read only, no code copied) |
| entSpawner spawns its own `base\spawner\empty_entity.ent` (an `entEntity` with one targeting component) from its own archive. That path is **not** in any 2.31 archive (FNV1a64 lookup over every `archive\pc\content` and `ep1` index), so we cannot use it | archive index lookup |
| `engine\fallback\helper_no_entity.ent` exists in `basegame_1_engine.archive` (837 bytes): class `entEntity`, one `entMeshComponent` (`engine\fallback\helper_no_entity.w2mesh`), no gameplay components, no persistence | archive index + CR2W strings of the extracted file (scratch only, never copied into the repo) |
| Vanilla cooked colliders store both `preset` and explicit masks (`simple_free_camera.ent`: sphere, preset `World Dynamic`, `simulationFilter{mask1,mask2}`, `queryFilter`) | CR2W strings of `base\entities\cameras\simple_free_camera.ent` |
| Collision presets of 2.31 (below) | `engine\physics\collision_presets.json` in `basegame_1_engine.archive`, decoded |

All of the above compiled in one throwaway spike script (temp tree only) before `Collision.reds` was written; a
deliberately wrong member in the same spike fails the check (`UNRESOLVED_MEMBER`), so the check really resolves
these APIs.

## Choosing the preset (from the 2.31 `collision_presets.json`)

| Preset | CollisionType | CollisionMask | QueryDetect |
|---|---|---|---|
| Player Collision (V) | Player | AI, Static, Dynamic, Destructible, Terrain, Collider, PlayerBlocker | Visibility |
| NPC Collision | AI | Static, Dynamic, Destructible, Terrain, Collider, Ragdoll, Ragdoll Inner | AI, PhotoModeCamera, NPCCollision |
| Vehicle Chassis | Vehicle | Destructible, Ragdoll Inner, Ragdoll, Tank, Terrain, Vehicle, VehicleBlocker | Vehicle, Interaction |
| Vehicle Chassis Traffic | Vehicle | Destructible, Ragdoll Inner, Ragdoll, Tank, Terrain, VehicleBlocker | Vehicle, Interaction |
| **World Static** (buildings) | Static, VehicleBlocker, TankBlocker, PlayerBlocker, NPCBlocker | AI, Static, Dynamic, Destructible, Terrain, Collider, Particle, Debris | Static, Visibility, Shooting, VehicleBlocker, PhotoModeCamera, TankBlocker, PlayerBlocker |
| Simple Environment Collision | Static, VehicleBlocker, TankBlocker, PlayerBlocker, NPCBlocker | Ragdoll, Ragdoll Inner, Dynamic, Destructible, Debris, Particle | Static, VehicleBlocker, TankBlocker, PlayerBlocker, NPCBlocker, PhotoModeCamera |
| Road Barrier Simple Collision | PlayerBlocker, VehicleBlocker, TankBlocker, NPCBlocker | NPCBlocker | PlayerBlocker, VehicleBlocker, TankBlocker |
| Block Player and Vehicles | PlayerBlocker, VehicleBlocker, TankBlocker | — | PlayerBlocker, VehicleBlocker, TankBlocker |

`World Static` is the only preset whose type is in the mask of all three movers: Player (Static, PlayerBlocker),
NPC (Static) and vehicles (VehicleBlocker). It is what Night City's buildings use, and it also stops NPC bullets
(Shooting) and NPC sight (Visibility), which is right for a solid Minecraft wall. `Road Barrier Simple Collision`
would avoid the probe side effect below, but no NPC preset collides with `NPCBlocker`, so pedestrians would walk
through it.

### The masks (fixed 2026-10-03, fix package C, world-3)

The first version hard-coded the masks entSpawner ships for World Static: simulation `mask1 = 114696` (0x1C008),
`mask2 = 23627` (0x5C4B), query `mask1 = 0`, `mask2 = 70107400` (0x42DC008). The in-game log of 2026-10-03 showed
that 2.31 builds different ones: `SimulationFilter.SimulationFilter_BuildFromPreset(f, n"World Static")` gave
`376836/5070` (0x5C004: 5 type bits, the five CollisionType groups including NPCBlocker; 0x13CE: 8 bits, the eight
CollisionMask groups). entSpawner's constants are an older group bit layout (Static at bit 3 instead of 2, no
NPCBlocker), so the boxes carried the wrong groups.

`Collision.reds` now builds both from the engine at runtime and logs them once
(`collision: World Static filters from the engine: simulation m1/m2, query m1/m2`):

- simulation: `SimulationFilter.SimulationFilter_BuildFromPreset(simulation, n"World Static")`;
- query: `QueryFilter.ZERO()` plus `QueryFilter.AddGroup` for each World Static QueryDetect group: `Static`,
  `Visibility`, `Shooting`, `VehicleBlocker`, `PhotoModeCamera`, `TankBlocker`, `PlayerBlocker`.

Evidence for the query side:

| Fact | Source |
|---|---|
| World Static QueryDetect is exactly those seven groups (VehicleBlocker is listed twice in the file) | 2.31 `engine\physics\collision_presets.json` (CR2W `physicsCollisionPresetsResource`), decoded again 2026-10-03 in a scratch folder, not kept |
| `Static`, `PlayerBlocker` and `VehicleBlocker` are group names vanilla scripts pass to `QueryFilter.AddGroup` / `RaycastWithASingleGroup` / `OverlapWithASingleGroup` | decompiled 2.31 scripts (`spatialQueriesSystem.swift`, `defaultTransition.swift`, `locomotionTransitions.swift`) |
| The native `QueryFilter.AddGroup` looks the name up in the engine's group table and ORs `1 << index` into `mask2` (offset 8); an unknown name adds nothing | 2.31 `Cyberpunk2077.exe`, the native registered under the name `AddGroup` (read-only disassembly, nothing kept) |
| Colliders keep QueryDetect in the query `mask2` with `mask1 = 0` | entSpawner's World Static constants above (query `0/0x42DC008`) |

So the logged query filter should read `0/<7 bits>`; fewer than seven bits would mean a group name the engine does not
know. `filter.preset = n"World Static"` is kept as well, as cooked colliders do.

## Side effects the integrator must know

- **Probes see the boxes.** World Static is query-detected as `Static`, and `Probes.reds` raycasts `Static` +
  `Terrain`. Down rays would report the tops of Minecraft blocks as ground, wall rays their faces (a face hit at an
  exact integer coordinate can floor into the air cell in front of the block, so Minecraft may put a barrier there),
  and `Cyberblock_SetAim` would stop at a block's own face. Done: `Probes.reds` and `Projectiles.reds` ignore a hit
  whose entity (`TraceResult.GetHitEntity`, Codeware) carries the `CyberblockCollision` tag.
  Excluding `Static` from the boxes' query filter instead is not safe: it is unknown whether NPC movement uses the
  query filter, and NPCs are the main target.
- **Traffic.** Traffic cars follow lanes. `dynamicTrafficSetting.impact = Blocking` is the per-collider switch the
  traffic generator reads for dynamic obstacles; whether runtime-added colliders are registered with the traffic
  system is unknown until tested. A player-driven car is stopped by physics either way.
- **NPC pathing.** Navmesh is baked, so NPCs do not plan around the boxes; they are stopped physically and may push
  against a wall. `gameinfluenceObstacleComponent` (influence-map obstacle) or `physicsColliderBox.isObstacle` might
  make them avoid it; neither is used, as nothing documents their effect.
- **Lifetime.** The box registry lives in a Codeware `ScriptableService`, so it survives loading a save (the
  Minecraft world keeps its blocks). It does not survive a game restart, and Minecraft never re-sends existing
  blocks; a later layer needs a "resend blocks on connect" message (or Codeware persistent service fields).
  Spawned entities are never saved: `Clear` despawns all of them before every save, on deactivate and at session end.
- **Blocker for the integrator: nothing empties the registry.** `Clear` only despawns, and plan §1.4 has no reset.
  If the user connects to a different Minecraft world or server, or Minecraft removes a block while the link is down
  (that `remove` never arrives), the old boxes stay registered. Whenever Cyberblock is Active, `Stream` spawns them
  again as invisible World Static walls. They block NPCs, cars and V, stop bullets and are hit by the layer-1
  probes, and only a game restart removes them. S2 now provides `CyberblockCollision.Reset(game)`, which despawns
  every box and forgets the registry. To finish the fix:
  1. The integrator adds `public static func Reset(game: GameInstance) -> Void; // forget all boxes` to §1.4.
  2. S1 calls it when the link to Minecraft comes up (a new hello, i.e. `Cyberblock_IsLinked()` turns true) and
     when the link drops.
  3. Because Minecraft never re-sends its blocks, step 2 also forgets the blocks of the same world after a reconnect,
     so those blocks lose their collision. Missing collision is a safer failure than a phantom wall. To get both
     right, M needs to re-send its placed blocks after hello (a "resend blocks on connect" message, see Lifetime
     above), and S1 must call `Reset` before those blocks arrive.

  Until this is wired, the user can recover from the CET console:
  `CyberblockCollision.Reset(Game.GetPlayer():GetGame())`.

## Options rejected

- A vanilla template that already is an invisible static collider: none found among the 21,345 `.ent` paths
  Codeware knows (searched for collision/blocker/collider/empty/dummy/marker/placeholder); the candidates found are
  devices (`base\gameplay\devices\empty_visible_object.ent` is a full `GenericDevice` with interactions and
  persistent state) or have visible meshes.
- `SimpleColliderComponent.Resize`: still needs a template containing that component.
- A `worldCollisionNode` or our own `.ent`/`.archive`: needs authoring tools (WolvenKit), excluded by the rules.

## In-game checks (needsInGame)

CET console, V standing on a street, mashup off (static functions are callable from CET):
```lua
local g = Game.GetPlayer():GetGame(); local p = Game.GetPlayer():GetWorldPosition()
CyberblockCollision.Add(g, Vector4.new(math.floor(p.x) + 2, math.floor(p.y), math.floor(p.z), 1))
CyberblockCollision.Stream(g, p)
```
Status 2026-10-03: check 1 seen in the user's session (boxes spawned, nothing reported visible). Checks 2-4 are still
pending, and must be redone with the engine-built masks of fix package C (the earlier boxes carried an older bit
layout). Also check that the first box logs `collision: World Static filters from the engine: simulation
376836/5070, query 0/<mask with 7 bits>`.

1. Nothing is visible 2 m east of V; `red4ext/logs/Cyberblock.log` has the `collision:` lines.
2. Walking east, V is stopped by the box; jumping on it works.
3. Repeat with a wall of 5 boxes across a pavement: a pedestrian walking into it stops (does not pass through).
4. Same wall across a lane: a traffic car stops (or at least collides); a car driven by V crashes into it.
5. `CyberblockCollision.Clear(g)`: the box is gone; saving and loading show no leftovers.
6. Add the box again, `CyberblockCollision.Reset(g)`, then `CyberblockCollision.Stream(g, p)` with V moved 5 m: the
   box does not come back, and the log has `collision: reset, 1 boxes forgotten`.
