# Spike S2.1: puppets, invisible stand-ins for Minecraft mobs in Night City

Question (plan 2026-10-03, S2.1): what makes invisible Cyberpunk stand-ins that NPCs and NCPD shoot at, that never
fight back and never die?

**Answer: an ordinary hostile ganger NPC spawned with Codeware's `DynamicEntitySystem`, set up when it attaches.**
Every step below uses vanilla 2.31 or Codeware 1.20.3 API. The spike snippet compiled against the real 2.31 scripts
plus Codeware (`cp/scripts/compile_check.py`), and a deliberately wrong member in the same snippet failed with
`UNRESOLVED_MEMBER`, so the checker really resolves these names. Whether NCPD actually engages, and whether hidden
meshes still take bullets, can only be seen in game: the checks are at the end. The fallback (spec layer 3) is
always available: `CyberblockPuppets.SetEnabled(false)` spawns nothing, so mob hits are attributed to V and NPCs
target V; damage still lands on Steve through `Health.reds`.

## Method (S2.2 implements it in `cp/scripts/Cyberblock/Puppets.reds`)

1. **Spawn.** `GameInstance.GetDynamicEntitySystem().CreateEntity(spec)` with:
   - `recordID = t"Character.animals_grunt1_melee1_fists_mb"` (a fists-only Animals grunt, no quest);
   - `position` = the mob's feet, `orientation` from the mob's yaw;
   - `persistState = false`, `persistSpawn = false` (never saved), `alwaysSpawned = true`, `spawnInView = true`,
     `active = true`, `tags = [n"CyberblockPuppet"]`.
2. **Hide** (Codeware callback `Entity/Initialize` with `DynamicEntityTarget.Tag(n"CyberblockPuppet")`, again on
   `Entity/AfterAttach`, and every second from the 10 Hz tick): `Toggle(false)` on every component that `IsA`
   `entIVisualComponent`. That class covers all render components: skinned meshes and garments, the morph-target
   head (which is **not** an `entSkinnedMeshComponent`, so the PlayerState filter would miss it), cloth, meshes and
   decals.
3. **Never dies** (`Entity/AfterAttach`):
   - `GodModeSystem.AddGodMode(id, gameGodModeType.Immortal, n"CyberblockPuppet")`. Immortal only flags the hit
     (`ImmortalTarget`), so damage is still computed through `Process` up to `DealDamages`;
   - a `@wrapMethod(DamageSystem) DealDamages` returns without calling the original for puppet targets. That stage is
     the only one that applies damage, so the puppet's health never moves. The wrap reports
     `damage / maxHealth` with `Cyberblock_MobDamage` instead.
4. **Never fights back:**
   - `TransactionSystem.RemoveAllItems(puppet)` on attach, and again whenever something sits in
     `AttachmentSlots.WeaponRight`/`WeaponLeft` (the record's secondary equipment is a handgun pool, and equipment
     arrives asynchronously);
   - **damage guard:** a `@wrapMethod(DamageSystem) InvulnerabilityCheck` adds `hitFlag.DealNoDamage` (source
     `n"CyberblockPuppet"`) to every hit whose instigator is a puppet, except Cyberblock's own `HitFrom` hits (found
     in `CyberblockEffectsService`'s pending list). `Process` returns on that flag, so no damage is dealt, and
     `TargetTrackingExtension.OnHit` returns on it too, so nobody even retaliates against a stray punch.
5. **Who shoots it:** `AttitudeAgent.SetAttitudeGroup(n"hostileToEveryoneExceptPlayer")`. That group's default
   attitude and its attitude to itself are Hostile, and its only relation record makes it Friendly to the player. So
   NCPD, gangs and civilians see it as hostile, while V (who is not fighting while Active) is left alone.
   - Not `n"hostile"`: gangers belong to that family (`Group_EnemyHostile` and others have it as parent), and
     `DamageSystem.CheckForQuickExit` cancels hits between Friendly pairs (`FriendlyFireIgnored`). So a puppet in
     `hostile` could not hurt gangers when a mob hits them.
6. **Retaliation:** when a mob hits an NPC, `CyberblockEffects.HitFrom` makes the puppet the instigator.
   `TargetTrackingExtension.OnHit` then makes an aggressive NPC hostile to it and adds it as a threat. Civilians are
   not aggressive, so they flee instead. Explicit threat injection
   (`TargetTrackerComponent.AddThreat(puppet, true, pos, 1.0, -1.0, false)`, the vanilla call pattern) is available
   as a lever but not needed for that.
7. **Movement:** `TeleportationFacility.Teleport(puppet, feet, Vector4.ToRotation(forward))` every 100 ms tick,
   with `forward = (-sin a, cos a, 0)` for the Cyberpunk yaw `a` (plan section 1.1).
8. **Despawn:** `DeleteEntity(id)` when the mob id leaves the `mobs` snapshot; `DeleteTagged(n"CyberblockPuppet")` in
   `Clear` (before saves, on load, at session end, on leaving Active/Vehicle).

## Evidence

| Fact | Source |
|---|---|
| `DynamicEntitySpec{recordID, templatePath, appearanceName, position, orientation, persistState, persistSpawn, alwaysSpawned, spawnInView, active, tags}`, `DynamicEntitySystem.IsReady/CreateEntity/DeleteEntity/GetEntity/IsTagged/DeleteTagged`, `GameInstance.GetDynamicEntitySystem()` | Codeware 1.20.3 `Codeware.Global.reds` (installed copy, lines 43408–43449) |
| `DynamicEntityTarget.Tag(CName)` for `CallbackSystem` handlers; events `Entity/Initialize`, `Entity/AfterAttach` | `Codeware.Global.reds` 41898; same events used by `Collision.reds` and `Npcs.reds` |
| `Character.animals_grunt1_melee1_fists_mb`: archetype `humanoid`, `NPCType.Human`, `quest` none, primary equipment `Items.w_melee_004__fists_a`, secondary equipment `AnimalsSecondaryHandgunPool`, loot `Loot.AnimalsGenericMeleeGruntT1_fist` (dropped only on death), reaction preset `Ganger_Aggressive`, `baseAttitudeGroup animals`. Its record exists in `tweakdb.bin` and `tweakdb_ep1.bin`; its `entityTemplatePath` hash resolves in `ep1_2_gamedata.archive` | TweakDB read from `r6/cache` (read-only); archive index lookup |
| Attitude group `Attitudes.Group_HostileToEveryoneExceptPlayer`: `name = hostileToEveryoneExceptPlayer`, `defaultAttitude = Hostile`, `attitudeToSelf = Hostile`. Its only relation is `Attitude_HostileToEveryoneExceptPlayer_Player` = Friendly | TweakDB |
| `AttitudeAgent.SetAttitudeGroup(CName)`; vanilla does the same to spawned hostiles (`DynamicSpawnSystem.ChangeAttitude`: `SetAttitudeGroup(n"hostile")`) | decompiled 2.31 scripts: `orphans.swift` 22489, `core/systems/dynamicSpawnSystem.swift` 61–74 |
| Friendly instigator/target pairs get `DealNoDamage` (`FriendlyFireIgnored`) unless the hit has `hitFlag.FriendlyFire` | `cyberpunk/damage/damageSystem.swift` 1824–1836 |
| An aggressive NPC hit by a non-friendly instigator turns hostile to it and adds it as a threat; it skips `DealNoDamage` hits | `core/components/targetTrackingComponent.swift` 308–366 (`TargetTrackingExtension.OnHit`) |
| `TargetTrackerComponent.AddThreat(entity, isHostile, pos, accuracy, cooldown, isPersistent)`, vanilla pattern `(…, 1.00, -1.00, false)` | `orphans.swift` 24428; `core/ai/actions/aiActionHelper.swift` 306 |
| `GodModeSystem.AddGodMode(EntityID, gameGodModeType, CName)`; `ImmortalityCheck` only adds `ImmortalTarget`, `InvulnerabilityCheck` adds `DealNoDamage` | `orphans.swift` 20708; `damageSystem.swift` 500–531 |
| Pipeline order `PreProcess` (… `InvulnerabilityCheck`, `ImmortalityCheck` …) → `Process` (returns on `DealNoDamage`; … `DealDamages` last) → `ProcessHitReaction` → `PostProcess` | `damageSystem.swift` 18–28, 260–275, 560–582 |
| Render component classes: `entSkinnedMeshComponent`, `entGarmentSkinnedMeshComponent`, `entMorphTargetSkinnedMeshComponent` (extends `entISkinTargetComponent`, not the skinned mesh), `entSkinnedClothComponent`, `entMeshComponent`, `entDecalComponent` all derive from `entIVisualComponent` | NativeDB class dump |
| `TransactionSystem.RemoveAllItems(obj)`, `GetItemInSlot(obj, slot)` | `orphans.swift` 18097, 18131 |
| `TeleportationFacility.Teleport(GameObject, Vector4, EulerAngles)` (vanilla teleports NPCs with it, e.g. `AICerberusRangedKillCommand`), `Vector4.ToRotation(dir)` for the orientation as `worldMap.swift` 949 does | `orphans.swift` 31935; `cyberpunk/ai/commands/aiCerberusRangedKillCommand.swift` 26; `core/math/vector.swift` 108 |

## Limits and integration notes

- **Capsule size.** No template lets a script resize an NPC capsule, so every puppet uses the record's human
  capsule (about 0.6 × 1.8 m). `MobSize` is only logged. A spider's puppet is taller than the spider, and NPCs aim
  at human height, but every hit on the puppet still counts.
- **`Npcs.reds` (S1) must skip puppets.** Puppets are `NPCPuppet`s near V, so without a filter Minecraft gets a human
  proxy for every mob. `CyberblockPuppets.IsPuppet(game, id)` is the test: one line in `CyberblockNpcs.Counts`.
- **Hits V causes are Minecraft's.** Explosions and other hits instigated by V land on the mob in Minecraft already,
  so the wrap neither reports nor applies them for puppet targets.
- **AI versus teleport.** The puppet's AI keeps running. It may try to walk or take cover between two 100 ms
  teleports, which is harmless because it is invisible, and its punches deal nothing because of the damage guard.
- The record's template lives in the Phantom Liberty archive `ep1_2_gamedata.archive`. The record itself is in both
  TweakDBs.

## Options rejected

- **A civilian (crowd) record.** Civilians are not aggressive, but crowd NPCs get special handling in the damage and
  reaction systems (`ProcessCrowdTarget`, the crowd fear reactions), and nothing in the scripts shows NCPD targeting
  them.
- **The `hostile` attitude group.** It makes the puppet friendly to most gangers, so their hits on it are cancelled
  (see the evidence above).
- **Status effects that stop AI movement or attacks** (`BaseStatusEffect.Stun`, `GameplayRestriction.NoMovement`,
  weapon malfunction). They are timed or player-oriented, and they carry VFX/SFX. The damage guard is exact and
  costs nothing.

## In-game checks (needsInGame)

The CET console works on the vanilla API directly, so P-1…P-6 test the method without Minecraft (`pid`/`npc` are
globals in the console).

- **P-1 spawn:**
  ```lua
  local p = Game.GetPlayer(); local pos = p:GetWorldPosition(); local f = p:GetWorldForward()
  local spec = DynamicEntitySpec.new()
  spec.recordID = TweakDBID.new("Character.animals_grunt1_melee1_fists_mb")
  spec.position = Vector4.new(pos.x + 5 * f.x, pos.y + 5 * f.y, pos.z, 1)
  spec.alwaysSpawned = true; spec.spawnInView = true; spec.active = true; spec.tags = {"CyberblockPuppetTest"}
  pid = Game.GetDynamicEntitySystem():CreateEntity(spec)
  ```
  Expected: an Animals ganger appears 5 m ahead; after 1 s `npc = Game.GetDynamicEntitySystem():GetEntity(pid)` is
  not nil.
- **P-2 hide:** `for _, c in pairs(npc:GetComponents()) do if c:IsA("entIVisualComponent") then c:Toggle(false) end end`.
  Expected: nothing is visible, including no floating head or hair. Then shoot the spot in a test save (outside
  Cyberblock): the hit still registers (blood decal or a hit reaction sound).
- **P-3 immortal and unarmed:**
  `Game.GetGodModeSystem():AddGodMode(pid, gameGodModeType.Immortal, "CyberblockPuppetTest")`,
  `Game.GetTransactionSystem():RemoveAllItems(npc)`. Expected: emptying a magazine into it never kills it, and it
  draws no gun.
- **P-4 attitude and NCPD:** `npc:GetAttitudeAgent():SetAttitudeGroup("hostileToEveryoneExceptPlayer")`, then walk it
  (P-5) next to an NCPD officer. Expected: the officer draws and shoots at the empty spot, and civilians nearby flee.
  If the officer ignores it, inject a threat while looking at the officer:
  `cop = Game.GetTargetingSystem():GetLookAtObject(Game.GetPlayer(), false, false)`,
  `cop:GetTargetTrackerComponent():AddThreat(npc, true, npc:GetWorldPosition(), 1.0, -1.0, false)`. Expected: the
  officer engages. Report which of the two was needed.
- **P-5 teleport:**
  `Game.GetTeleportationFacility():Teleport(npc, Vector4.new(x, y, z, 1), EulerAngles.new(0, 0, 90))`. Expected: it
  moves there facing -X (west) without falling through the floor.
- **P-6 cleanup:** `Game.GetDynamicEntitySystem():DeleteEntity(pid)`. Expected: gone; a save and reload shows no
  ganger.
- **P-7 real path** (Cyberblock Active, Minecraft running, a zombie from a spawn egg next to a pedestrian):
  - `red4ext/logs/Cyberblock.log` has `puppets: spawned` lines;
  - nothing is visible where the zombie is;
  - the zombie's hit makes the pedestrian flee (or a ganger fight back), and V gets **no** NCPD heat from it.
- **P-8 NCPD kills a mob** (heat 2, a skeleton near officers): the log shows `puppets: mobdmg` lines with a `by` id,
  the skeleton dies in Minecraft, and its puppet despawns (`puppets: despawned`).
- **P-9 fallback:** `CyberblockPuppets.SetEnabled(false)` in CET. Expected: the log shows `puppets: disabled`, every
  puppet goes, and mob hits now give V heat. `CyberblockPuppets.SetEnabled(true)` brings them back on the next tick.
