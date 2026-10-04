# Phase 0 feasibility: what Cyberpunk 2.31 and Apex allow (package 0R, plus the known Apex facts)

Date: 2026-10-04 · Plan: `docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 4 (checks (a) to (h)),
contracts in sections 1.8, 1.9 and 1.11 · Companion: `docs/notes/phase0-server.md` (package 0M, the server ecosystem).

**Status: desk verdicts.** Every API below was read in the sources and the snippets were compile-checked. What only the
running game can show is in [the CET session](#in-game-cet-session-re-test-q-about-10-minutes). The integrator writes
the final verdicts after the user's CET output and Apex answers (plan section 4, last paragraph).

## Sources and method

- **Scripts:** the 2.31 script API (NativeDB, redscript): the scripts the game ships, read outside this repository,
  cited as `file.swift line`. **Codeware 1.20.3:** `Codeware.Global.reds` of the installed Codeware, cited by line.
- **TweakDB:** record ids checked with `tdb_has` against `r6/cache/tweakdb.bin` and `tweakdb_ep1.bin` (both files),
  flat values from the read-only dump the audit used.
- **Compile spike:** one throwaway file (`zz_spike_phase0.reds`, scratchpad only, never committed or installed) holding
  every snippet below was compiled together with `cp/scripts/Cyberblock` by `cp/scripts/compile_check.py`'s
  `compile_tree`: **OK**; a second throwaway file holding the snippets of these notes verbatim (each wrapped in a
  function) also compiled **OK**. The first file with two wrong members (`TraceResult.materialNope`,
  `ScriptableDeviceComponentPS.ActionSetDeviceNope`) failed with `UNRESOLVED_MEMBER` and `UNRESOLVED_METHOD`, so the
  checker really resolves these names.
- **In-game evidence already on hand** (`Cyberblock.log` of 2026-10-03): the Atlas survey tour moved V across the city
  for 13 minutes and put V back (`survey: started: V hidden, invulnerable, saving locked` ... `survey: V put back at`);
  the Codeware attach registry sees NPCs and cars (`npcs: sent 1 people + 4 cars; attached 30 people + 89 cars`).

## Results

| Check | Verdict (desk) | Exact API | Fallback applied |
|---|---|---|---|
| (a) Apex: custom jar, Java, disk | **partly** (Custom Jar option exists; the rest is open) | Apex panel | none yet; see [(a)](#a-apex-custom-jar-java-disk) |
| (b) raycast hit object and material | hit object **yes** (proven in game); material **partly** (field exists, values unproven) | `TraceResult.material: CName`; Codeware `TraceResult.GetHitEntity` / `GetHitObject` | if the material is always `None`: heuristic palette, plus the hit world node's `meshAppearance` as a hint |
| (c) device APIs | **yes** for type, state, actions, events, listing; **partly** for stable id; **no** for size | `Device.GetDevicePS`, `DoorControllerPS`, `Action*` + `GamePersistencySystem.QueuePSDeviceEvent`, `@wrapMethod(Device) OnPerformedAction` | size from `device_map`; id fallback key ready; `call`/`buy` greyed until proven |
| (d) spawn Night City NPCs | **yes** for people and drones (pending CET for combat AI); **no** for turrets | Codeware `DynamicEntitySystem.CreateEntity(DynamicEntitySpec{recordID})`, `AttitudeAgent.SetAttitudeGroup/SetAttitudeTowards` | turrets: Minecraft-side only |
| (e) attitude and wanted level | **yes** (pending CET) | `PreventionSystem` request `SetWantedLevel`, `GetHeatStageAsInt`; `AttitudeSystem.SetAttitudeRelation` (not persistent) | none expected |
| (f) NPC and vehicle tags, animation, vendor stock | **partly**: faction, type, gender, archetype, animation state, vehicle type and model, vendor stock **yes**; fixer/bartender/medic/mechanic role **no**; car colour **partly**; walk/run **no** script API | `Character_Record` accessors, `gamePuppet.GetResolvedGenderName`, puppet-state blackboard, `Vehicle_Record`, `VehicleColorTemplate_Record`, `MarketSystem.GetVendorItemsForSale` | roles from vendor type + content; walk/run from position deltas in the DLL; colour from the class default when no template matches |
| (g) far teleport and PARKED | **yes** (teleport, hold and put-back proven by the survey); no engine streaming test, the survey's settle probe is the test | `TeleportationFacility.Teleport`, down-ray snap, `PlayerPuppet.SetInvisible`, god mode, `SaveLocksManager`, `GameplayRestriction.NoMovement/NoCameraControl` | the written "hold high until a down ray hits" method is the method |
| (h) remote quickhacks and device actions | **yes** (pending CET) for every streamed target; no range or sight check in the upload path | `CyberblockHacks.Hack` (PuppetAction + `ProcessRPGAction`), device `GetRemoteActions` | targets that are not streamed answer `target gone` (`cyberblock.hack.out_of_range` on the Minecraft side) |

## (a) Apex: custom jar, Java, disk

- **Known (user, 2026-10-04):** the Apex panel has a **Custom Jar** option, so the Fabric 26.3 server launcher jar can be
  run as a custom jar. This removes the "no custom jar" branch of the written fallback.
- **Still open (plan section 4.2), asked by the integrator in one message:**
  1. Where is the Custom Jar name typed (a server-type dropdown entry, a startup/jar field, a file name the panel
     expects)? Does the server-type list also show **Fabric for 26.3** directly? A screenshot of the dropdown helps.
  2. Which **Java versions** can be picked (Java 25 is required: `fabric.mod.json` needs `java >= 25`)? Which one does
     Rod's Ores use now?
  3. **Disk:** the quota and the current usage.
  4. **RAM** of the plan.
  5. **Extra ports:** can a UDP port (proximity voice chat) and a TCP port (web map) be added, and on which panel page?
  6. **Backups:** does the panel have its own backups, and how many can be kept?
  7. Does the **File Manager** extract `.zip` files?
  8. The exact **jar name** Rod's Ores runs on now (Paper 1.21.11), so the way back is exact.
  9. The server **address and port** for friends (kept only in the user's local, untracked notes, never in the repo).
- **Fallbacks still in force (plan 4.1 a):** no Java 25 → Apex support is asked for Java 25 (0M drafts the message) and
  Phase 2 runs the same Fabric 26.3 server on the user's PC meanwhile; too little disk → the Rod's Ores backup lives on
  the PC, nothing is deleted on the server; no extra UDP port → voice chat over the game port if the voice mod has a
  proxy mode, else voice chat is optional (installed disabled).

## (b) Raycast hit object and material

**Verdict: hit object yes, material partly (pending CET B1 to B5).**

- `TraceResult` (2.31 `orphans.swift` 27598-27607) has `position`, `normal` and **`material: CName`**. No vanilla script
  reads `material`, so whether it names the surface (and with what names) is only visible in game.
- `SpatialQueriesSystem.SyncRaycastByQueryFilter / SyncRaycastByCollisionGroup / SyncRaycastByCollisionPreset`
  (`orphans.swift` 27641-27647) all fill a `TraceResult`. Collision groups used by vanilla: `Static`, `Dynamic`,
  `Terrain`, `Water`, `Collider`, `Destructible`, `PlayerBlocker`, `Interaction`, `AI` (`aiFindTeleportPositionForTakedown.swift`
  113-117, `defaultTransition.swift` 481-512).
- **Hit object:** Codeware adds `TraceResult.GetHitEntity(script_ref<TraceResult>) -> ref<Entity>` and
  `TraceResult.GetHitObject(script_ref<TraceResult>) -> ref<ISerializable>` (`Codeware.Global.reds` 42277-42280).
  `GetHitEntity` already works in game: `Probes.reds` and `Projectiles.reds` use it to see through Cyberblock's own
  boxes. A door or device hit is `entity as Door` / `entity as Device` (`door.swift` 2, `deviceBase.swift` 82).
- **Bonus for colours:** a hit on static geometry returns a world node instance from `GetHitObject`; Codeware's
  `worldINodeInstance.GetNode()` (43554) gives `worldMeshNode.meshAppearance` (38159) or
  `worldInstancedMeshNode.meshAppearance` (43563), a mesh appearance name such as a paint or concrete variant. If the
  physics material turns out to be coarse or `None`, this name feeds the `material_palette` heuristic.

Compile-checked snippet (spike `ZzSpikeP0.B`):
```swift
let filter: QueryFilter;
QueryFilter.AddGroup(filter, n"Static");
QueryFilter.AddGroup(filter, n"Terrain");
QueryFilter.AddGroup(filter, n"Dynamic");
let r: TraceResult;
if GameInstance.GetSpatialQueriesSystem(game).SyncRaycastByQueryFilter(from, to, filter, r) {
  let material: CName = r.material;
  let entity = TraceResult.GetHitEntity(r);
  let isDoor = IsDefined(entity as Door);
  let isDevice = IsDefined(entity as Device);
  let node = TraceResult.GetHitObject(r) as worldINodeInstance;
  let mesh = IsDefined(node) ? node.GetNode() as worldMeshNode : null;
  let look = IsDefined(mesh) ? NameToString(mesh.meshAppearance) : "";
}
```

**Fallback (written, plan 4.1 b):** no material → `material_palette` "heuristic" entries (height above ground, district
from `stats.district`, wall vs floor, the Atlas water flag), plus the mesh appearance name when it is readable. No hit
object (doors come back `ent=none`) → Phase 4 filters probe hits inside a device's box.

## (c) Device APIs

**Verdict: yes for type, state, actions, events and listing; partly for a stable id (pending CET C5); no for size.**

- **Classes** (all script classes, `cyberpunk/devices/...`): `Device` extends `DeviceBase` extends `GameObject`
  (`deviceBase.swift` 51, 82); `InteractiveDevice` (`interactiveDevice.swift` 2). Per type: `Door`
  (`DoorControllerPS`), `SecurityGate` (`SecurityGateControllerPS`), `RoadBlock` (barriers), `ElectricLight`
  (`ElectricLightControllerPS`), `InteractiveSign` and `BillboardDevice` (signs, billboards), `SurveillanceCamera`,
  `Terminal` / `Computer`, `VendingMachine` (with `WeaponVendingMachine`, `IceMachine`), `TrafficLight` (with
  `CrossingLight`), `LiftDevice` (`LiftControllerPS`), `SecurityTurret`, `TV`. There is **no `GarageDoor` class**: garage
  doors are `Door`s (door types in `EDoorType`, opening types in `EDoorOpeningType`, `orphans.swift` 9408) and road
  barriers are `RoadBlock`. Containers are `gameContainerObjectBase` (`lootContainers.swift` 693), not devices.
- **Listing within 48 m:** no script query lists devices. The NPC registry pattern that already works in game
  (Codeware `Entity/AfterAttach` + `Entity/Detach` with `EntityTarget.Type`, `Tether.reds` 286-317) works with
  `EntityTarget.Type(n"Device")` (compiled in the spike's `ZzSpikeDeviceRegistry`).
- **State:** `Device.GetDeviceState() -> EDeviceStatus` (`deviceBase.swift` 860; `DISABLED -2, UNPOWERED -1, OFF 0,
  ON 1`, `orphans.swift` 10474), `IsDeviceSecured()` (864), `ScriptableDeviceComponentPS.IsBroken()`
  (`scriptableDeviceBasePS.swift` 5770). Doors: `DoorControllerPS.GetDoorState() -> EDoorStatus` (`SEALED -2, LOCKED -1,
  CLOSED 0, OPENED 1`), `IsOpen/IsLocked/IsSealed` (`doorController.swift` 98-118, 198). Lifts:
  `LiftControllerPS.GetActiveFloor/IsMoving` (`liftController.swift` 1010, 1022). Traffic lights: the colour is the
  protected `TrafficLight.m_lightState: worldTrafficLightColor` (`trafficLight.swift` 4), read through an
  `@addMethod(TrafficLight)` getter. Vending stock: `MarketSystem.GetVendorItemsForSale(vendorObject, false)`
  (`marketSystem.swift` 150).
- **Stable id:** `PersistentState.GetID() -> PersistentID` (`orphans.swift` 11318) has no script hash or
  serialization (only `ToDebugString`, `IsStatic`, `ExtractEntityID`, 16717-16734). World devices are streamed world
  nodes whose `EntityID` hash comes from the node, so `CyberblockIds.Hash(device)` is expected to be the same after a
  reload; CET C5 confirms it.
- **Actions** (the vanilla path, `ScriptableDeviceComponentPS.ExecutePSAction` 5427 = `RegisterAsRequester` +
  `GamePersistencySystem.QueuePSDeviceEvent`, `orphans.swift` 16756):
  door `ActionSetOpened / ActionSetClosed / ActionToggleOpen / ActionToggleLock` (`doorController.swift` 745, 765, 675,
  831; all public); any device `ActionSetDeviceON / ActionSetDeviceOFF / ActionToggleON`
  (`scriptableDeviceBasePS.swift` 3833, 3853, 3800); `DeviceAction.SetExecutor(V)` (`baseDeviceActions.swift` 58).
  `ForceOpen` and the `Quest*` actions are protected or private and are not used.
- **Events:** every executed device action reaches the entity as `Device.OnPerformedAction(PerformedAction)`
  (`deviceBase.swift` 1037; the action carries its executor), and every persistent-state change as
  `OnPSChangedEvent` (787). Both wrap cleanly (`@wrapMethod(Device)`, compiled). The executor tells `devstate.by`:
  V → `host`, an NPC → `npc`, none → `script`.
- **Size:** no general bounding-box API for an entity in 2.31 or Codeware (only `InfluenceObstacleComponent.GetBoundingBox`,
  `orphans.swift` 46347). Size comes from `device_map`.

Compile-checked snippet (spike `ZzSpikeP0.CRead` / `CAct` and the wraps):
```swift
let ps = device.GetDevicePS();
let door = ps as DoorControllerPS;
let action: ref<ScriptableDeviceAction> = IsDefined(door) ? door.ActionToggleOpen() : ps.ActionSetDeviceON();
action.SetExecutor(player);
ps.ExecutePSAction(action);

@wrapMethod(Device)
protected cb func OnPerformedAction(evt: ref<PerformedAction>) -> Bool {
  let action = evt.m_action as ScriptableDeviceAction;
  let byPlayer = IsDefined(action) && IsDefined(action.GetExecutor() as PlayerPuppet);
  return wrappedMethod(evt);
}
```

**Fallback (written, plan 4.1 c):** no stable id → id = hash of (position rounded to 25 cm, class name), the spike's
`CFallbackKey`, resolved at run time; no events → poll at 2 Hz within 48 m; an action not callable → only the device's
own interaction/quickhack actions, the rest greyed with `cyberblock.device.unsupported`. Applied now: `size` from
`device_map`; `call` (lifts) and `buy` (vending) greyed until a later check proves them (vending is a Minecraft shop).

## (d) Spawn Night City NPCs

**Verdict: yes for people and drones (combat AI pending CET D2/D3); no for turrets.**

- **Spawn:** Codeware `DynamicEntitySystem.CreateEntity(DynamicEntitySpec)` with `recordID`, `position`,
  `persistState = false`, `persistSpawn = false`, `tags` (`Codeware.Global.reds` 43408-43447). Already in use and
  working in game for the mob stand-ins (`Puppets.reds`, `puppets: spawned`). **Despawn:** `DeleteEntity(id)` or
  `DeleteTagged(tag)`.
- **AI and attitude:** a record-spawned NPC runs the record's AI (the puppets' AI kept running, `puppet-spike.md`).
  Vanilla's own ambush spawner does exactly this to make spawned NPCs fight: `SetAttitudeGroup(n"hostile")` +
  `SetAttitudeTowards(player, AIA_Hostile)`, and `AIInjectCombatThreatCommand` for a combat start
  (`dynamicSpawnSystem.swift` 8-74).
- **Record ids** (present in both TweakDB files): Tyger Claws `Character.tyger_claws_gangster1_ranged1_nue_ma`,
  Maelstrom `Character.maelstrom_grunt1_ranged1_copperhead_ma`, Valentinos `Character.valentinos_grunt1_ranged1_nova_ma`,
  6th Street `Character.sixthstreet_hooligan_ranged1_nova_ma`, Animals `Character.animals_grunt1_ranged1_nova_mb`,
  Voodoo Boys `Character.voodooboys_grunt1_ranged2_sidewinder_ma`, Scavengers `Character.scavenger_grunt1_melee1_fists_ma`,
  Wraiths `Character.wraiths_grunt1_ranged1_nova_ma`, Mox `Character.kab_the_mox_1_ranged1_lexington_wa`, Barghest
  `Character.kurtz_soldier1_ranged2_rifle_ma`; NCPD patrol `Character.ncpd_police_ranged2_lexington_ma` (Heat spawns use
  `Character.prevention_police_rifle_ma`), NCPD heavy `Character.ncpd_enforcer_shotgun2_tactician_ma_rare` /
  `Character.prevention_enforcer_rifle_ma` (there is no `swat` record in 2.31), Max-Tac
  `Character.prevention_maxtac_rifle_ma` (AV crew `Character.maxtac_av_LMG_mb`), drones `Character.ncpd_base_drone_wyvern`,
  `Character.prevention_unit_wyvern_drone`, `Character.arasaka_base_drone_octant`.
- **Turrets:** a turret is a `SecurityTurret` device, not a character: the only `*turret*` character record
  (`Character.turret_fighter`) is a Tyger Claws human. Spawning a device from a template has no persistent state or
  network behind it, so it is not attempted.

Compile-checked snippet (spike `ZzSpikeP0.DSpawn` / `DHostile`):
```swift
let spec = new DynamicEntitySpec();
spec.recordID = t"Character.tyger_claws_gangster1_ranged1_nue_ma";
spec.position = at;
spec.persistState = false;
spec.persistSpawn = false;
spec.spawnInView = true;
spec.active = true;
spec.tags = [n"CyberblockSpawn"];
let id = GameInstance.GetDynamicEntitySystem().CreateEntity(spec);
// once attached:
let agent = npc.GetAttitudeAgent();
agent.SetAttitudeGroup(n"hostile");
agent.SetAttitudeTowards(player.GetAttitudeAgent(), EAIAttitude.AIA_Hostile);
```

**Fallback (written, plan 4.1 d):** spawning fails or spawned NPCs stay passive → Overlord and Heat spawns become
Minecraft-side squads (skinned mobs) for everyone, and for hosts the real wanted level (e) brings NCPD and Max-Tac.
Applied now: turrets are Minecraft-side only (`spawn.rec` loses `turret`).

## (e) Faction attitude and wanted level

**Verdict: yes (pending CET E2 to E5).**

- **Read:** `PreventionSystem.GetHeatStage() -> EPreventionHeatStage` / `GetHeatStageAsInt()`
  (`preventionSystem.swift` 375, 383; `Heat_0`..`Heat_5`, `orphans.swift` 9733). The system is the scriptable system
  `n"PreventionSystem"`.
- **Write:** the quest path, request `SetWantedLevel { m_wantedLevel }` (`preventionSystem.swift` 4200) queued with
  `PreventionSystem.QueueRequest(game, request)` (3522). `OnSetWantedLevel` (3259) turns the system on, changes the heat
  stage, and for `Heat_0` while chasing queues the forced de-escalation. The quest-managed flag it sets
  (`prevention_state_managed_by_quest_generic`) is removed again when heat drops to 0 (2222), so 2 then 0 leaves no
  trace of that flag. Two other effects do last: `OnSetWantedLevel` first calls `TogglePreventionSystem(true)` (3830),
  which turns the police back on where a quest or a police-free area had turned them off, and it overwrites the
  persistent `m_ignoreSecurityAreasByQuest` with the request's `m_forceIgnoreSecurityAreas` (default false), which no
  heat change restores. So Cyberblock (6S `RaiseHeat`) sends nothing while `IsSystemEnabled()` (331) is false or V is
  in a scene, and passes the flag's current value (read with Codeware reflection; no reflection = the written fallback).
- **Attitude:** `AttitudeSystem.SetAttitudeRelation(firstGroup, secondGroup, EAIAttitude)` and `GetAttitudeRelation`
  (`orphans.swift` 26911-26925). The `...Persistent` variants exist and are **not** used (they would be saved). Group
  names (TweakDB `Attitudes.Group_*.name`): `tygerClaws`, `maelstrom`, `valentinos`, `6thStreet`, `animals`,
  `voodooBoys`, `scavenger`, `wraiths`, `aldecaldos`, `kangtao`, `kurtz_army` (Barghest), `arasaka`, `militech`,
  `police`, `traumateam`, `civilian`, `player`. The Mox have no attitude group of their own in 2.31.

Compile-checked snippet (spike `ZzSpikeP0.EHeat` / `EAttitude`):
```swift
let request = new SetWantedLevel();
request.m_wantedLevel = EPreventionHeatStage.Heat_2;
PreventionSystem.QueueRequest(game, request);
let system = GameInstance.GetScriptableSystemsContainer(game).Get(n"PreventionSystem") as PreventionSystem;
let stars = system.GetHeatStageAsInt();
let attitudes = GameInstance.GetAttitudeSystem(game);
let before = attitudes.GetAttitudeRelation(n"tygerClaws", n"player");
attitudes.SetAttitudeRelation(n"tygerClaws", n"player", EAIAttitude.AIA_Friendly);
```

**Fallback (written, plan 4.1 e):** no write → Heat for hosts only through (d) or Minecraft squads; no attitude write →
Gang Wars reputation does not change how Night City treats a team, and the docs say so.

## (f) NPC and vehicle tags, animation state, vendor stock

**Verdict: partly.**

| Tag | API | Readable |
|---|---|---|
| faction | `ScriptedPuppet.GetRecord().Affiliation().EnumName()` (`scriptedPuppet.swift` 1114; `Character_Record`, `orphans.swift` 16188-16520) | yes |
| character type | `Character_Record.CharacterType().EnumName()` (`NPCType`: Human, Android, Drone, Mech, ...) | yes |
| archetype | `Character_Record.ArchetypeData().Type().EnumName()` | yes |
| gender | `gamePuppet.GetResolvedGenderName()` and `GetBodyType()` (`orphans.swift` 16578-16580) | yes (names pending CET F1) |
| role | `ScriptedPuppet.IsVendor()` (1336), `Character_Record.VendorID().VendorType().Type()` (`gamedataVendorType`: RipperDoc, Clothes, Gunsmith, Medical, Drinks, Food, ..., `orphans.swift` 5683); `IsCharacterCivilian/IsCharacterPolice/IsCharacterGanger` (1394-1410) | vendors and ripperdocs yes; **fixer, bartender, medic, mechanic no** |
| visual tags | `SpawnableObject_Record.VisualTags()` (e.g. `Maelstrom, Lvl1, Grunt`) | yes |
| animation | puppet-state blackboard: `GetHighLevelStateFromBlackboard()` (Relaxed, Alerted, Combat, Dead, Fear, Unconscious, ...), `GetStanceStateFromBlackboard()` (Crouch, Cover, Vehicle, ...) (`scriptedPuppet.swift` 1172, 1184), `PuppetState.UpperBody` (Aim, Shoot, Attack, Reload, ..., read as in `aiActionHelper.swift` 1447), `NPCPuppet.IsRagdolling()` (`NPCPuppet.swift` 2598) | aiming, firing, crouch, combat, ragdoll, dead yes; **walk/run no** |
| vehicle | `VehicleObject.GetRecord()`: `Type().Type()` (Bike, Car, Panzer), `Model().Type()` (`gamedataVehicleModel`: Turbo, Type66, Galena, ...; `orphans.swift` 5296, 5310), `Manufacturer()`, `Affiliation()` | yes |
| vehicle colour | `Entity.GetCurrentAppearanceName()` (`entity.swift` 38) matched against `Vehicle_Record.AppearancesToColorTemplate().ColorTemplates()`; each `VehicleColorTemplate_Record` has `MatchingAppearances` and `MainColor: [Int32]` (`orphans.swift` 21226-21256; e.g. `archer_hella__basic_poor_1` → `[56, 55, 64]`, probably HSB) | **partly**: cars with colour templates; the number format is confirmed in CET F2 |
| vendor stock | `MarketSystem.GetVendorItemsForSale(vendor, false) -> [SItemStack]`, `Vendor_Record.ItemStock` | yes |
| health | `StatPoolsSystem.GetStatPoolValue(id, Health, true)` (already used by `Scan.reds`) | yes |

Compile-checked snippet (spike `ZzSpikeP0.FNpc` / `FCar`):
```swift
let record = npc.GetRecord();
let faction = NameToString(record.Affiliation().EnumName());
let gender = npc.GetResolvedGenderName();
let upper = IntEnum<gamedataNPCUpperBodyState>(npc.GetPuppetStateBlackboard().GetInt(GetAllBlackboardDefs().PuppetState.UpperBody));
let combat = Equals(npc.GetHighLevelStateFromBlackboard(), gamedataNPCHighLevelState.Combat);
let crouch = Equals(npc.GetStanceStateFromBlackboard(), gamedataNPCStanceState.Crouch);
let car = vehicle.GetRecord();
let type = EnumInt(car.Type().Type());   // gamedataVehicleType
let model = EnumInt(car.Model().Type()); // gamedataVehicleModel
let look = vehicle.GetCurrentAppearanceName();
```

**Fallback (written, plan 4.1 f) applied in part:** no role → skin by faction and threat, so fixers, bartenders, medics
and mechanics come from content (`npc_role`, `skin_map`), not from the game; no walk/run → the DLL derives bits 1 and 2
from position deltas; no colour → the class default colour.

## (g) Safe far teleport and PARKED

**Verdict: yes.** Proven in game by the Atlas survey tour, which holds V over far squares and puts V back exactly.

- **Teleport:** `TeleportationFacility.Teleport(GameObject, Vector4, EulerAngles)` (`orphans.swift` 31935), as
  `Survey.reds` 150 does.
- **Streaming-ready test:** there is **no** engine or Codeware call for it (the only `IsStreaming` /
  `IsStreamingTestFinished` are signature-less members of the test-only `FunctionalTestsGameSystem`,
  `Codeware.Global.reds` 16744-16794). The survey's settle probe is the test and works in game:
  hold V high over the target, cast a 3 x 3 grid of down rays 60 m apart until the hits stay the same for 0.5 s
  (2 s at most), then snap (`cp/plugin/src/survey.hpp` 22-30, constants 225-227).
- **Ground snap:** one long down ray (Static + Terrain), then teleport to the hit.
- **PARKED:** unseen by NPCs: `PlayerPuppet.SetInvisible(true)` switches V's `VisibleObjectComponent` (the senses
  target) off (`player.swift` 3854); invulnerable: `GodModeSystem.AddGodMode(id, gameGodModeType.Invulnerable, source)`;
  saving locked: `SaveLocksManager.RequestSaveLockAdd(game, reason)` (`saveLocksManager.swift` 30); no movement and no
  camera: status effects `GameplayRestriction.NoMovement` and `GameplayRestriction.NoCameraControl` (both TweakDB files)
  through `StatusEffectHelper.ApplyStatusEffect / RemoveStatusEffect`. The first three are exactly the survey's
  `begin`, proven in game.

Compile-checked snippet (spike `ZzSpikeP0.GTeleport` / `GPark`):
```swift
let ground = queries.SyncRaycastByQueryFilter(new Vector4(t.X, t.Y, t.Z + 300.0, 1.0), new Vector4(t.X, t.Y, t.Z - 300.0, 1.0), filter, r);
GameInstance.GetTeleportationFacility(game).Teleport(player, Cast<Vector4>(r.position), facing);
player.SetInvisible(true);
GameInstance.GetGodModeSystem(game).AddGodMode(player.GetEntityID(), gameGodModeType.Invulnerable, n"CyberblockPark");
SaveLocksManager.RequestSaveLockAdd(game, n"CyberblockPark");
StatusEffectHelper.ApplyStatusEffect(player, t"GameplayRestriction.NoMovement");
StatusEffectHelper.ApplyStatusEffect(player, t"GameplayRestriction.NoCameraControl");
```

**Fallback (written, plan 4.1 g):** no streaming test → hold V high (15 s at most) until a down ray hits, then snap: this
**is** the method now. Cannot make V unseen (CET G5 fails) → PARKED fast-travels V to the nearest fast-travel point and
holds V there invisible and invulnerable.

## (h) Quickhacks and device actions for a remote player

**Verdict: yes for every streamed target (pending CET H2/H3).**

- `CyberblockHacks.Hack` (`Hacks.reds` 1-71 header) builds the vanilla `PuppetAction` with V as executor and calls
  `ProcessRPGAction` (`baseDeviceActions.swift` 308). Range and line of sight are checked only by the scanner and the
  quickhack UI, never in that path, so a target behind a wall 40 m away gets the same upload. Devices go through
  `ScriptableDeviceComponentPS.GetRemoteActions` the same way.
- The limit is streaming: the target is found by `GameInstance.FindEntityByID(EntityID.FromHash(id))`, which only finds
  attached entities (NPCs and cars are attached roughly within the crowd and traffic streaming range; the registry
  shows dozens of NPCs and 90 cars attached at once).

Compile-checked snippet (spike `ZzSpikeP0.H`; the path itself is `Hacks.reds`, compiled with every suite):
```swift
CyberblockHacks.Hack(game, requestId, CyberblockIds.Hash(target), n"ping", false);
```

**Fallback (written, plan 4.1 h):** range or sight limits found in game → remote hacks and device actions only within
the host's streamed area and 50 m of V, attributed to V; others refused with `cyberblock.hack.out_of_range`.

## Extra: time and weather (for 1.9 `env`, "Phase 0 decides what is readable")

- Time: `TimeSystem.GetGameTime()` with `GameTime.Hours / Minutes` (already used for `stats.time`).
- Weather: vanilla `WeatherSystem` exposes only rain values (`GetRainIntensity`, `GetRainIntensityType`,
  `orphans.swift` 43599-43611). Codeware adds `WeatherSystem.GetWeatherState() -> worldWeatherState` whose `name` is the
  weather preset's CName (`Codeware.Global.reds` 43539, 39772). Compiled in the spike (`ZzSpikeP0.Env`); the names are
  printed by CET line ENV.

## Contract narrowing needed (sections 1.8, 1.9 and a note for 1.11)

Names stay as drafted; only fields and values narrow. The integrator copies these into the plan as a dated amendment
block once the CET results are in (each item names the CET line it depends on).

**Section 1.8 (natives):**
1. **Phase 3, probe materials:** take the second form, `Cyberblock_SubmitProbeMaterials(down: array<CName>, wall:
   array<CName>)`, parallel to the down and wall hit arrays of the same tick, called right before
   `Cyberblock_SubmitProbes` (`Probes.reds`). Each entry is `TraceResult.material`, or the hit node's `meshAppearance`
   when the material is `None` (B1-B5). The DLL maps names to `material_palette` codes through one shared, fixed name
   table (`shared/`), unknown names to 0 (logged once each).
2. **Phase 3, device hits in the probes:** a probe ray whose `GetHitEntity` is a `Device` is cast again past it, the
   way `TraceNightCity` already passes Cyberblock boxes, so doors and gates never enter the static replica (needs B2:
   `ent=Door`; else Phase 4's box filter).
3. **Phase 3, `Cyberblock_SetEnv(hour, minute, weather)`:** `weather` is the raw `worldWeatherState.name` CName; the DLL
   maps it to the 1.9 enum by name (ENV fixes the table).
4. **Phase 3, teleport and park:** `vteleport` is done with `TeleportationFacility.Teleport` plus the survey's settle
   probe (hold high, 3 x 3 down rays stable 0.5 s, 15 s at most) and a down-ray snap; `park` is `SetInvisible` + god
   mode `Invulnerable` (source `n"CyberblockPark"`) + save lock (`n"CyberblockPark"`) + `GameplayRestriction.NoMovement`
   and `NoCameraControl`. `Cyberblock_TeleportDone` reasons: `""`, `"no ground"`, `"timeout"`, `"blocked (<why>)"`.
5. **Phase 4, `Cyberblock_SubmitDevices`:** `ids` = `CyberblockIds.Hash(device)` (C5; else the fallback key's hash);
   `classes` = the RTTI class name; `kinds` from the class's `IsA` chain; `sizes` always zero (Minecraft takes the size
   from `device_map`); `states` = `EDeviceStatus` for devices, `EDoorStatus` for doors; `flags` as 1.9.
   `Cyberblock_DeviceState.by` from the performed action's executor. The device list comes from a Codeware attach
   registry (`EntityTarget.Type(n"Device")`), state changes from `@wrapMethod(Device) OnPerformedAction` and
   `OnPSChangedEvent`.
6. **Phase 4, `devact` events:** supported `act` values `open, close, toggle, lock, unlock, on, off, disable, enable,
   distract, friendly`; `call`, `buy` and `breach` are not Cyberpunk actions (lift calls unproven, vending is a Minecraft
   shop, Breach is a Minecraft minigame whose daemons send the supported acts).
7. **Phase 5, `Cyberblock_SubmitNpcs2`:** `roles` = the vendor type name (`RipperDoc`, `Clothes`, ...) or `n""`;
   `factions` = `Affiliation().EnumName()`; `genders` from `GetResolvedGenderName` (F1 fixes the names);
   `archetypes` = `ArchetypeData().Type().EnumName()`; `anims` from the blackboard and `IsRagdolling` with bits 1 and 2
   always 0 (the DLL derives walk and run); `vclass` = `"<type>/<model>"` from `Vehicle_Record.Type()` and `Model()`;
   `colours` from the matching colour template, -1 when none (F2 fixes the number format); `hp` from the Health stat
   pool.
8. **Phase 8, `spawn`:** `rec` values map to the record ids of (d); `turret` is removed (no character record).

**Section 1.9 (wire messages):**
1. **3 `ground` (ext):** `m` codes come from the shared name table (item 1.8-1); 0 unknown.
2. **3 `env`:** `time` from `GameTime`; `weather` mapped from the preset name (ENV), `other` when unmapped.
3. **3 `vteleported`:** `reason` ∈ `""`, `no ground`, `timeout`, `blocked (<why>)`; `p` is the snapped feet position.
4. **4 `devices`:** `size` is omitted (Minecraft uses `device_map`); `st` is `EDeviceStatus` (-2..1) or, for doors,
   `EDoorStatus` (-2..1); `fl` bit 4 (quest-locked) = door `SEALED` or device `DISABLED`; bit 16 (powered) = state
   is not `UNPOWERED`; `cls` ≤ 48 chars.
5. **4 `devstate`:** `by` = `host` when the executor is V, `npc` when an NPC, `script` when none, `guest` when the
   action came from a guest's `devact` (the DLL remembers the request for 2 s).
6. **4 `devact`:** `act` limited to the list of 1.8-6; other values answer `{ok:false, reason:"unsupported"}`.
7. **5 `npcs` (ext):** `role` from vendor type only; `g` from the resolved gender name; `anim` bits 1/2 set by the DLL;
   `vc` = `"<type>/<model>"` (e.g. `Car/Turbo`, ≤ 32 chars); `col` only when a colour template matches.
8. **8 `spawn`:** `rec` loses `turret`.

**Section 1.11 note:** candidates confirmed as script classes: `Door`, `SecurityGate`, `RoadBlock`, `ElectricLight`,
`InteractiveSign`, `BillboardDevice`, `SurveillanceCamera`, `Terminal`, `Computer`, `VendingMachine`, `TrafficLight`,
`LiftDevice`, `SecurityTurret`, `TV`. Replace `GarageDoor` with `Door` (garage door types) and `RoadBlock`;
containers are `gameContainerObjectBase`, not devices (read-only, unchanged).

## In-game CET session (Re-test Q, about 10 minutes)

**Before you start:** make a manual save, play in free roam (no mission running), and **do not save during the
checks**. Every line undoes what it does (heat back to 0, attitude restored, door and light toggled back, the spawned
NPC deleted, V brought home, park undone). Open the CET console, paste one line at a time, and copy the `CBP0` lines
back to Claude (or tell Claude to read `<Cyberpunk 2077>\bin\x64\plugins\cyber_engine_tweaks\scripting.log`, where
every `CBP0` line is also written). Cyberblock can be On or Off.

### Setup (paste each once)

S1 (helpers):
```lua
CB={} function CB.p(...) print("CBP0",...) end function CB.n(c) if c==nil then return "nil" end local ok,s=pcall(function() return c.value end) return (ok and s and s~="") and s or tostring(c) end function CB.try(f) local ok,v=pcall(f) if ok then return v end return nil end function CB.look() return Game.GetTargetingSystem():GetLookAtObject(Game.GetPlayer(),false,false) end CB.p("helpers ready")
```
Expected: `CBP0 helpers ready`

S2 (ray, for b):
```lua
function CB.ray(down) local p=Game.GetPlayer() local o=p:GetWorldPosition() local a=Vector4.new(o.x,o.y,o.z+1.6,1) local f=Game.GetCameraSystem():GetActiveCameraForward() local b=down and Vector4.new(o.x,o.y,o.z-3,1) or Vector4.new(a.x+f.x*25,a.y+f.y*25,a.z+f.z*25,1) for _,g in ipairs({"Static","Dynamic","Terrain"}) do local ok,r=Game.GetSpatialQueriesSystem():SyncRaycastByCollisionGroup(a,b,g,false,false) if ok then local e=CB.try(function() return TraceResult.GetHitEntity(r) end) local h=CB.try(function() return TraceResult.GetHitObject(r) end) local look=CB.try(function() return CB.n(h:GetNode().meshAppearance) end) or "-" if e then CB.hit=e end CB.p(g,"mat="..CB.n(r.material),"ent="..(e and CB.n(e:GetClassName()) or "none"),"obj="..(h and CB.n(h:GetClassName()) or "none"),"look="..look) else CB.p(g,"miss") end end end CB.p("ray ready")
```
Expected: `CBP0 ray ready`

S3 (devices, for c):
```lua
function CB.dev(d) if not d or not CB.try(function() return d:GetDevicePS() end) then return CB.p("not a device: "..(d and CB.n(d:GetClassName()) or "nil")) end CB.d=d local ps=d:GetDevicePS() local s=CB.n(d:GetClassName()).." id="..tostring(d:GetEntityID().hash).." pid="..tostring(CB.try(function() return PersistentID.ToDebugString(ps:GetID()) end)).." state="..tostring(d:GetDeviceState()) local door=CB.try(function() return tostring(ps:GetDoorState()).." locked="..tostring(ps:IsLocked()) end) if door then s=s.." door="..door end CB.p(s) end function CB.act(name) local ps=CB.d:GetDevicePS() local a=ps[name](ps) a:SetExecutor(Game.GetPlayer()) a:RegisterAsRequester(CB.d:GetEntityID()) Game.GetPersistencySystem():QueuePSDeviceEvent(a) CB.p("sent "..name) end CB.p("devices ready")
```
Expected: `CBP0 devices ready`

S4 (NPCs, heat, teleport, park, hacks, for d to h):
```lua
function CB.spawn(rec) local p=Game.GetPlayer() local o=p:GetWorldPosition() local f=p:GetWorldForward() local s=DynamicEntitySpec.new() s.recordID=TweakDBID.new(rec) s.position=Vector4.new(o.x+10*f.x,o.y+10*f.y,o.z,1) s.persistState=false s.persistSpawn=false s.spawnInView=true s.active=true s.tags={"CyberblockP0"} CB.id=Game.GetDynamicEntitySystem():CreateEntity(s) CB.p("spawned "..rec) end function CB.npc() local n=Game.GetDynamicEntitySystem():GetEntity(CB.id) if not n then return CB.p("not attached yet") end CB.p(CB.n(n:GetClassName()).." high="..tostring(n:GetHighLevelStateFromBlackboard()).." att="..tostring(n:GetAttitudeAgent():GetAttitudeTowards(Game.GetPlayer():GetAttitudeAgent()))) return n end function CB.hostile() local n=CB.npc() n:GetAttitudeAgent():SetAttitudeGroup("hostile") n:GetAttitudeAgent():SetAttitudeTowards(Game.GetPlayer():GetAttitudeAgent(),Enum.new("EAIAttitude","AIA_Hostile")) CB.p("made hostile") end function CB.despawn() CB.p("deleted "..tostring(Game.GetDynamicEntitySystem():DeleteEntity(CB.id))) end CB.p("npc ready")
```
Expected: `CBP0 npc ready`

S5:
```lua
function CB.heat(n) local ps=Game.GetScriptableSystemsContainer():Get("PreventionSystem") if n then local r=SetWantedLevel.new() r.m_wantedLevel=Enum.new("EPreventionHeatStage","Heat_"..n) ps:QueueRequest(r) end CB.p("heat "..tostring(ps:GetHeatStageAsInt())) end function CB.gang(g,att) local a=Game.GetAttitudeSystem() if att then CB.was=CB.was or a:GetAttitudeRelation(g,"player") a:SetAttitudeRelation(g,"player",att) end CB.p(g.." to player "..tostring(a:GetAttitudeRelation(g,"player"))) end CB.p("heat ready")
```
Expected: `CBP0 heat ready`

S6:
```lua
function CB.tags(o) o=o or CB.look() if not o then return CB.p("look at someone") end local r=o:GetRecord() if o:IsA("vehicleBaseObject") then local look=CB.n(o:GetCurrentAppearanceName()) local col="-" CB.try(function() for _,t in ipairs(r:AppearancesToColorTemplate():ColorTemplates()) do for _,m in ipairs(t:MatchingAppearances()) do if CB.n(m)==look then col=table.concat(t:MainColor(),",") end end end end) return CB.p("car type="..tostring(r:Type():Type()).." model="..tostring(r:Model():Type()).." look="..look.." main="..col) end CB.p("npc fac="..tostring(CB.try(function() return CB.n(r:Affiliation():EnumName()) end)).." type="..tostring(CB.try(function() return CB.n(r:CharacterType():EnumName()) end)).." arch="..tostring(CB.try(function() return CB.n(r:ArchetypeData():Type():EnumName()) end)).." g="..CB.n(o:GetResolvedGenderName()).." body="..CB.n(o:GetBodyType()).." high="..tostring(o:GetHighLevelStateFromBlackboard()).." stance="..tostring(o:GetStanceStateFromBlackboard()).." upper="..tostring(CB.try(function() return o:GetPuppetStateBlackboard():GetInt(Game.GetAllBlackboardDefs().PuppetState.UpperBody) end)).." ragdoll="..tostring(CB.try(function() return o:IsRagdolling() end)).." vendor="..tostring(CB.try(function() return tostring(r:VendorID():VendorType():Type()) end)).." stock="..tostring(CB.try(function() return #MarketSystem.GetVendorItemsForSale(o,false) end))) end CB.p("tags ready")
```
Expected: `CBP0 tags ready`

S7:
```lua
function CB.go(dx,dy,up) local p=Game.GetPlayer() local o=p:GetWorldPosition() CB.home=CB.home or o Game.GetGodModeSystem():AddGodMode(p:GetEntityID(),Enum.new("gameGodModeType","Invulnerable"),"CyberblockP0") Game.GetTeleportationFacility():Teleport(p,Vector4.new(o.x+dx,o.y+dy,o.z+up,1),EulerAngles.new(0,0,0)) CB.p("moved") end function CB.snap() local p=Game.GetPlayer() local o=p:GetWorldPosition() local ok,r=Game.GetSpatialQueriesSystem():SyncRaycastByCollisionGroup(Vector4.new(o.x,o.y,o.z+300,1),Vector4.new(o.x,o.y,o.z-300,1),"Static",false,false) if ok then Game.GetTeleportationFacility():Teleport(p,Vector4.new(r.position.x,r.position.y,r.position.z,1),EulerAngles.new(0,0,0)) CB.p("ground at z="..string.format("%.1f",r.position.z)) else CB.p("no ground yet") end end function CB.back() local p=Game.GetPlayer() Game.GetTeleportationFacility():Teleport(p,CB.home,EulerAngles.new(0,0,0)) Game.GetGodModeSystem():RemoveGodMode(p:GetEntityID(),Enum.new("gameGodModeType","Invulnerable"),"CyberblockP0") CB.home=nil CB.p("home") end CB.p("teleport ready")
```
Expected: `CBP0 teleport ready`

S8:
```lua
function CB.park(on) local p=Game.GetPlayer() local g=p:GetGame() local t=Enum.new("gameGodModeType","Invulnerable") p:SetInvisible(on) if on then Game.GetGodModeSystem():AddGodMode(p:GetEntityID(),t,"CyberblockPark") SaveLocksManager.RequestSaveLockAdd(g,"CyberblockPark") else Game.GetGodModeSystem():RemoveGodMode(p:GetEntityID(),t,"CyberblockPark") SaveLocksManager.RequestSaveLockRemove(g,"CyberblockPark") end CB.p("parked="..tostring(p:IsInvisible())) end function CB.hack(what) if not CB.t then return CB.p("pick a target first") end CyberblockHacks.Hack(Game.GetPlayer():GetGame(),0,CyberblockIds.Hash(CB.t),what or "ping",false) CB.p("asked "..(what or "ping").." at "..string.format("%.0f m",Vector4.Distance(Game.GetPlayer():GetWorldPosition(),CB.t:GetWorldPosition()))) end CB.p("park ready")
```
Expected: `CBP0 park ready`

### The checks (one line each, in this order)

| # | Do this, then paste | Expected printout |
|---|---|---|
| B1 | face a plain building wall 2-20 m away: `CB.ray()` | a `Static` line with `mat=` a material name (not `None`/`nil`), `ent=none`, `obj=` a world node instance class, `look=` a mesh appearance |
| B2 | face a closed door: `CB.ray()` | one line (Static or Dynamic) with `ent=Door` (or a door subclass); note which group |
| B3 | face a shop window or glass: `CB.ray()` | a line with a glass-like material name (or a miss: glass may not block rays) |
| B4 | stand on a road: `CB.ray(true)` | a `Static` or `Terrain` line with an asphalt/road-like material name |
| B5 | face a neon sign: `CB.ray()` | a line with a material name; `ent=` a device class if the sign is a device |
| C1 | face the same door: `CB.dev(CB.hit or CB.look())` | `CBP0 Door id=<number> pid=<text> state=... door=... CLOSED ... locked=false` |
| C2 | `CB.act("ActionToggleOpen")`, wait 1 s, `CB.dev(CB.d)`, then `CB.act("ActionToggleOpen")` again | the door opens, `door=` shows `OPENED`, then it closes again |
| C3 | `CB.act("ActionToggleLock")`, `CB.dev(CB.d)`, then `CB.act("ActionToggleLock")` again | `locked=true` (or `door=` `LOCKED`), then unlocked again |
| C4 | face a light or a lit sign you can scan: `CB.dev(CB.look())`, `CB.act("ActionToggleON")`, then again | the light goes off, then on again (`not a device` means pick another light) |
| D1 | `CB.spawn("Character.tyger_claws_gangster1_ranged1_nue_ma")` | a Tyger Claw appears about 10 m ahead within 2 s |
| D2 | after 2 s: `CB.npc()` | `CBP0 NPCPuppet high=...Relaxed... att=...` (Neutral or Hostile) |
| D3 | `CB.hostile()`, wait 3 s, `CB.npc()` (get ready to fight) | he draws and attacks; `high=` shows `Combat` |
| G5 | while he is fighting: `CB.park(true)`; wait 30 s next to him; open the menu and try to save | `parked=true`; he stops shooting and searches, V takes no damage, saving is refused |
| G6 | `CB.park(false)`, then `CB.despawn()` | `parked=false`, then `deleted true` and he is gone |
| D5 | `CB.spawn("Character.prevention_maxtac_rifle_ma")`, `CB.npc()` after 2 s, `CB.despawn()`; then the same with `"Character.ncpd_base_drone_wyvern"` | a Max-Tac trooper, then an NCPD drone appear and are deleted (`deleted true`) |
| E1 | `CB.heat()` | `CBP0 heat 0` |
| E2 | `CB.heat(2)`, wait 2 s, `CB.heat()` | two wanted stars appear; `CBP0 heat 2` |
| E3 | `CB.heat(0)`, wait 5 s, `CB.heat()` | the stars blink out; `CBP0 heat 0` |
| E4 | `CB.gang("tygerClaws")`, then `CB.gang("tygerClaws",Enum.new("EAIAttitude","AIA_Friendly"))` | first the current attitude (Hostile or Neutral), then `AIA_Friendly` |
| E5 | `CB.gang("tygerClaws",CB.was)` | the first attitude again |
| F1 | look at a pedestrian, a ganger and a vendor (shopkeeper or ripperdoc) in turn: `CB.tags()` | `npc fac=... type=Human arch=... g=<Female or Male> body=... high=... stance=... upper=<6 normal, 0 aim, 9 shoot> ragdoll=false vendor=<VendorType or nil> stock=<count or nil>` |
| F2 | look at a parked car: `CB.tags()` | `car type=...Car model=...<model> look=<appearance> main=<three numbers or ->` |
| G1 | `CB.go(2000,0,80)` (2 km east, 80 m up; V is invulnerable) | `CBP0 moved`; V falls in a far place |
| G2 | at once and again every 2 s until it hits: `CB.snap()` | `no ground yet` at most a few times, then `ground at z=<number>` and V stands on the ground (water or a void means: `CB.back()` and try `CB.go(-2000,0,80)`) |
| G3 | `CB.back()` | `CBP0 home`, V is back where G1 started |
| H1 | look at a pedestrian about 10 m away: `CB.t=CB.look() CB.p(CB.n(CB.t:GetClassName()))` | `CBP0 NPCPuppet` |
| H2 | walk away until about 40 m away with a wall between you, then: `CB.hack()` | `CBP0 asked ping at 4x m`; the pedestrian gets the ping outline; `Cyberblock.log` has `hack ping on <id>: ok (...)` |
| H3 | look at a vending machine or a TV: `CB.t=CB.look()`, walk 40 m away behind a wall, then `CB.hack("device")` | the machine starts its distraction; `Cyberblock.log` has `hack device on <id>: ok (distract ...)` |
| ENV | `local t=Game.GetTimeSystem():GetGameTime() local w=Game.GetWeatherSystem():GetWeatherState() CB.p(GameTime.Hours(t)..":"..GameTime.Minutes(t), w and CB.n(w.name) or "no state")` | `CBP0 <h>:<m> <weather preset name>` |
| C5 (last) | note C1's `id=`; load the save you made (this also proves nothing was left behind); face the same door: `CB.dev(CB.look())` | the same `id=` number as in C1 (the helpers survive a load; if a line says `attempt to index a nil value`, paste S1 to S8 again) |

If a line prints a Lua error instead (for example `no property m_wantedLevel`), paste the error: the fix is a naming
detail of CET, not a verdict (for that one, `r.wantedLevel` is the other spelling).

## Risks

- CET calls redscript statics (`CyberblockHacks.Hack`, `CyberblockIds.Hash`, `SaveLocksManager`, `MarketSystem`)
  through the RTTI; if a CET build refuses one, H2/H3 fall back to firing the hack from Minecraft (the real path).
- `TraceResult.GetHitEntity` takes a `script_ref`; the CET call is wrapped so a binding problem prints `ent=none`
  instead of failing. The in-game proof of hit entities is the probes' box filter, not CET.
- Heat 2 brings real NCPD for a few seconds; E3 removes it. Do E2/E3 away from crowds.
- The spawned ganger in D3 fights for real; V can die, which reloads the save you made. Nothing is saved by these lines.
