# Layer 6 and potions on the Cyberpunk side (track S3)

Date: 2026-10-03 · Plan: `docs/superpowers/plans/2026-10-03-layers3-6-complete-mod.md` §1.4, §1.5, track S3 ·
Audit: `docs/notes/cp-mechanics-audit.md` (B, C, needsInGame G-3…G-8).

Files: `cp/scripts/Cyberblock/Hacks.reds`, `Scan.reds`, `Perks.reds`, `Buffs.reds`, `Kills.reds`. All compile against
the 2.31 scripts plus Codeware with `cp/scripts/compile_check.py` (run with stubs for the three §1.3 natives S1 adds).

## Sources and how IDs were confirmed

- **Scripts:** the decompiled 2.31 scripts the audit used (codeberg.org/adamsmasher/cyberpunk at `a2e6bb3 "2.31"`).
  Each file header cites the functions it relies on.
- **TweakDB:** read-only from the game's `r6/cache/tweakdb.bin` and `tweakdb_ep1.bin`. A record ID is
  `crc32(name) | len(name) << 32`; the records section of each file lists `(id, type hash)` pairs, so an ID is
  confirmed when it is present in that section of **both** files. Flat values and names came from the audit's
  read-only dump (CET's `tweakdbstr.kark`, decompressed).
- **Compile check:** `uv run python cp/scripts/compile_check.py`. A deliberate unknown method
  (`PuppetAction.SetCanSkipPayCostXYZ`) was rejected with `UNRESOLVED_METHOD`, so the checker really type-checks.

## S3.1 Quickhacks (`Hacks.reds`)

`CyberblockHacks.Hack(game, id, target, hack, chain)` always answers `Cyberblock_HackResult` exactly once.

| `hack` | Record (both TweakDB files, ObjectAction type) | Lower tiers |
|---|---|---|
| `short_circuit` | `QuickHack.OverloadLvl1Hack` | — |
| `overheat` | `QuickHack.OverheatLvl1Hack` | — |
| `reboot_optics` | `QuickHack.BlindLvl1Hack` | — |
| `cripple_movement` | `QuickHack.LocomotionMalfunctionLvl2Hack` | `Lvl1` absent |
| `contagion` | `QuickHack.ContagionLvl2Hack` (spread allowed) | `Lvl1` absent |
| `synapse_burnout` | `QuickHack.BrainMeltLvl2Hack` | `Lvl1` absent |
| `ping` | `QuickHack.PingLvl2Hack` | `Lvl1` absent |
| `memory_wipe` | `QuickHack.MemoryWipeLvl2Hack` | `Lvl1` absent |
| `cyberpsychosis` | `QuickHack.MadnessLvl3Hack` | `Lvl1`, `Lvl2` absent |
| `device` | device action (below) | — |

**Puppet hacks** use the audit B.1 pattern unchanged (`PuppetAction`, `SetCanSkipPayCost(true)`,
`SetDisableSpread(true)` except Contagion, `ProcessRPGAction`).

Reasons:
- **"no such hack"**: an unknown name, or the record is missing at run time.
- **"target gone"**: no player, no entity for the id, or the NPC is not active (`ScriptedPuppet.IsActive`: dead,
  defeated, unconscious or turned off).
- **"not hackable"**: the entity is not a `ScriptedPuppet` (for `device`: not a `Device`), V's friend,
  Invulnerable, or in a scene. These three guards keep quest characters out of reach. They are a deliberate
  addition: vanilla's own UI check (`ScriptedPuppet.IsQuickHackAble`) cannot be used, because it needs a cyberdeck and
  is blocked by `NoQuickHacks`, which `NoCombat` puts on V while Active.

**Chain** (`chain:true`, the `chain_hack` perk) fires the same hack at the nearest other active, hackable NPC within 8 m
of the target (`GameObject.GetNPCsAroundObject`, the query `SpreadAreaEffector` uses). It only applies to puppet
hacks. Only the main target's result is reported.

**Device hacks:**
- **Traffic lights:** the intersection flip (audit `FlipIntersection`).
- **Cameras** (`SurveillanceCamera`): `ForceIgnoreTargets`. The first one in the camera's list is
  `DeviceAction.OverrideAttitudeClassHack`.
- **Any device offering `QuickHackDistraction`** (vending machines, arcades, TVs, radios, jukeboxes…): that action.
- **Otherwise:** the device's first allowed quickhack.
- **Never used:** `ToggleTakeOverControl` (the CCTV would become the streamed camera) and `RemoteBreach` (a
  minigame).
- **What "allowed" means:** the device's own conditions. Its actions are listed twice through the public
  `GetRemoteActions`:
  - once with `GetActionsContext.ignoresRPG = true`;
  - once with the normal context.

  The RPG pass marks every device hack inactive for a V without a cyberdeck (instigator prereq
  `StatPrereq HasCyberdeck`, read from `DeviceAction.MalfunctionClassHack`). It also overwrites the device's own
  inactive reason. So availability is read from the first list, and `LocKey#7021` ("not exposed", a breach UI gate)
  is ignored. The action that runs comes from the normal list (same order), so it is a proper quickhack with V as
  executor.
- **Result:** no allowed action means "not hackable".

Side effects (audit B.4) stay as in vanilla:
- V gets each hack's cooldown status effect and Intelligence XP.
- NCPD reacts where the completion effects notify it.

## S3.2 Scan (`Scan.reds`)

`CyberblockScan.Scan(game, id, target)` always answers `Cyberblock_ScanResult` exactly once.

- **Target:** a target of 0 means what V looks at. That is `TargetingSystem.GetLookAtObject(V, true, true)`, the
  same call `PlayerPuppet.ReevaluateLookAtTarget` makes. Any other value is `EntityID.FromHash` +
  `FindEntityByID`.
- **NPC** (`ScriptedPuppet`):
  - name: the scanner's own rules (alternative name, then civilian `DisplayName`, else `FullDisplayName` /
    `DisplayName`, else `GetDisplayName`);
  - faction: `Affiliation().LocalizedName()`;
  - size: 0.6 × 1.8 m.
- **Vehicle:**
  - name: `Vehicle_Record.DisplayName`;
  - faction: empty;
  - threat: from its mounted driver;
  - HP: 0 once destroyed;
  - size: 2 × 1.6 m. No 2.31 script API exposes a vehicle bounding box (`vehicleBoundingBox` exists only inside the
    native vehicle camera context), so the fallback is used.
- **Device:**
  - name: `GetDisplayName`, else `GetDeviceName` (the order of `Device.GetScannerName`);
  - threat: `none`;
  - size: 1 × 2 m.
- **Anything else** (props, items, nothing): `ok:false` with empty strings.
- **Threat:** `RPGManager.CalculatePowerDifferential` (the helper the scanner's bounty level uses) maps
  TRASH/EASY → `low`, NORMAL → `medium`, HARD → `high`, IMPOSSIBLE → `extreme`. It is `none` for non-aggressive
  (civilian) or inactive NPCs.
- **HP:** `GetStatPoolValue(Health, perc=true) / 100`. The percentage scale was confirmed by
  `AdamSmasherComponent`'s 50/30 thresholds. It is 1 for objects without a Health pool.
- **Text:**
  - Loc keys go through `GetLocalizedTextByKey`.
  - `LocKey#` strings go through `GetLocalizedText`.
  - Everything is clipped to 64 characters with Codeware's `UTF8StrLeft`.

## S3.3 Perks (`Perks.reds`)

Ranks live in `CyberblockPerksService` (a Codeware `ScriptableService`: one per game process, never saved). `Set`
keeps them while not Active, and accepts only the 20 §1.5 ids. `Clear` keeps them too.

| Perk | Cyberpunk side | Source |
|---|---|---|
| `double_jump` | `HasDoubleJump` Additive +1 while Active | audit C.2 `GrantStat`. `DoubleJumpDecisions.EnterCondition` checks `HasStatFlag(HasDoubleJump)` and refuses while `HasChargeJump`/`HasAirHover` are set; S1 zeroes those while Active, so the granted double jump works even with charge-jump legs. |
| `silent_steps` | `Visibility` Multiplier 0.5 while Active | audit C.4 `Stealth`: `UpdateVisibilityModifier` → `VisibleObjectDetectionMultEvent` |
| `sandevistan` (`perkfx`) | `SetIgnoreTimeDilationOnLocalPlayerZero(true)` + `SetTimeDilation(n"cyberblockSlowMo", 0.2, s + 1)`; end: `UnsetTimeDilation(…, n"SandevistanEaseOut")` + ignore off | audit C.1 `SlowMo`/`SlowMoEnd` |
| `dodge` | nothing here: S1 reads `Has(n"dodge")` | plan §1.5 |

How the Sandevistan slow-mo is timed:
- **End time:** it runs on engine time (`GameInstance.GetEngineTime`, the clock `GameObject.ProcessDamageReceived`
  stamps damage with), so the slow-mo cannot stretch its own end. The engine duration `s + 1` is only a safety net if
  ticks stop.
- **Never stacked:** a second `perkfx` while one is running only moves the end time. `seconds` 0 ends it.
- **Only while Active:** it starts only when the last Tick was Active.

`Tick(active false)` and `Clear` remove both modifiers (the exact objects, from the entity they were added to) and end
the slow-mo. A changed V (load) drops the old modifiers first.

## S3.4 Buffs: potions (`Buffs.reds`)

Every record below is in the records section of both TweakDB files (status effects type `0x25107a6d`, stats
`0x26929cac`).

| `effect.name` | On V while Active | Why this record or stat |
|---|---|---|
| `invisibility` | `BaseStatusEffect.OpticalCamoPlayerBuffRare` + `SetInvisible(true)` | audit C.3 |
| `speed` | `MaxSpeed` Multiplier `min(1 + 0.2(amp+1), 3)` | the stat the player locomotion's own speed modifiers use (`AddMaxSpeedModifier`, `EnableReloadStatModifier`). Player speed buffs scale it (`DetectorRushPlayerBuff*`, `AlcoholDebuff`, `PlayerElectrocuted`). |
| `slowness` | `MaxSpeed` Multiplier `max(1 − 0.15(amp+1), 0.3)` | same |
| `jump_boost` | `JumpHeight` Multiplier `min(1 + 0.5(amp+1), 4)` | set per locomotion state by `PlayerLocomotion.JumpJumpHeightModifier` (+1) / `DoubleJumpJumpHeightModifier` (+2.6); `PlayerBleeding` scales it ×0.5 on V |
| `nausea` | `BaseStatusEffect.Drunk` (1 stack) | `PlayerPuppet.ProcessTieredDrunkEffect` starts the fullscreen `status_drunk_level_1` |
| `blindness`, `darkness` | `AIQuickHackStatusEffect.HackBlind` | the player-side Reboot Optics: VFX/SFX `status_blinded` plus `hacking_glitch_low` |

**No DoT is mapped.**
- `Drunk` carries `Debuff`/`DoT`/`Poison` tags, inherited from `Poisoned`, but has no attack. Its packages are only
  weapon-sway stat groups (`Prereqs.AnyWeaponHeldPrereq`) and the `DrunkSFX` effector. Nothing reaches Health.reds.
- `HackBlind`'s effectors are VFX, a sway stat group and the HUD weapon-malfunction effector. It has no attack either.
- Poison and wither are not mapped and are ignored like every other unmapped name. Each unmapped name is logged once.

**Caps.** `speed` and `jump_boost` are capped (×3, ×4). Without the caps, a `/effect` amplifier of 255 would give
×52 speed. This is a guard beyond the plan's formula. `slowness` keeps the plan's 0.3 floor.

**Lifetime.**
- **Ending:** each entry ends at engine time + `seconds`. `seconds` 0 removes it, and re-sending refreshes it.
- **Refreshing:** the remaining duration of an applied status effect is refreshed with
  `SetStatusEffectRemainingDuration`.
- **Ownership:** a status effect V already had from the game (a drink) is left alone. Ours is re-applied at most once
  a second if the game takes it off early (for example camo decay, or a healing item clearing `DoT`-tagged effects).
- **Stat modifiers:** these are the exact objects, removed from the entity they were added to.
- **`Tick(active false)`:** takes everything off V and keeps the entries, so a potion survives a vehicle ride or a save.
- **`Clear`:** does the same and also forgets infinite entries (86400 s). While Cyberblock is off, S1 drops `effect`
  events, so the removal of an infinite effect could be missed. Timed entries are bounded by their own expiry. It also
  removes any status effect still carrying our instigator ID (`t"Cyberblock.Buffs"`), in case a save or an earlier
  session left one on V.

## S3.5 Kills (`Kills.reds`)

Two wraps call `Cyberblock_Killed(hash, vehicle, hostile)`, once per entity (the last 64 reported hashes are
remembered):
- `NPCPuppet.OnDeath(evt: ref<gameDeathEvent>)`, a protected cb;
- `VehicleComponent.OnDeath(evt: ref<gameDeathEvent>)`, which explodes the vehicle and calls `FindAndRewardKiller`.

**Conditions:**
- Cyberblock's god mode must be on V (`GetGodModeSources(V, Invulnerable)` contains `n"Cyberblock"`).
- V must have hurt the victim, meaning one of these holds:
  - the death event's instigator is V;
  - the NPC's `GetMyKiller()` is V;
  - V is in the victim's damage history of the killing frame (`GetReceivedDamageByPlayerLastTimeStamp() > 0`, the
    history `FindAndRewardKiller` uses).

V is the instigator of every Minecraft effect (Effects.reds), so this covers Steve's hits, TNT, fire and hacks that
damage. It does not cover NPCs that a Cyberpsychosis victim kills, or the victim's own suicide.

**`hostile`:** `IsHostileTowardsPlayer` of the NPC, or of the vehicle's mounted driver, read before the vehicle's death
handler runs.

## Cross-check with S1 and S2

The five S3 files were compiled together with `l36/S1` at `41f14f5` and `l36/S2` at `2fd373a`, and again with
`l36/S2` at `a59ee62`. The S1 files were Natives (with the real §1.3 declarations), Events, Cyberblock, PlayerState,
Disable, Health and Probes. The S2 files were Effects and Puppets, plus Flight in the second run. Stubs covered only
the S2 classes that were not committed yet (Tether and Projectiles, plus Flight in the first run). Result: OK both
times. So every call S1 makes into the S3 API matches these signatures, and no class or wrap
collides.

## needsInGame

Run each check with Cyberblock Active (F9), Minecraft linked, and `red4ext/logs/Cyberblock.log` open. The log lines
quoted are the ones these files write.

### Audit checks

- **G-3 Quickhack.**
  1. In CET, get the NPC V looks at:
     `npc = Game.GetTargetingSystem():GetLookAtObject(Game.GetPlayer(), false, false)`.
  2. Run `StatusEffectHelper.ApplyStatusEffect(npc, TweakDBID.new("BaseStatusEffect.OverloadLevel1"), Game.GetPlayer():GetEntityID())`.
     Expect electrocution VFX and damage.
  3. Use a Redstone Charge on the same kind of ganger. Expect an upload delay, then the same result plus NCPD
     heat. The log shows `hack short_circuit on <id>: ok`.
  4. Repeat on a crowd NPC and on a mech.
- **G-4 Device hack.**
  1. Use a Redstone Lever on a vending machine. Expect the screen to glitch and cans to drop
     (`hack device on <id>: ok`).
  2. Use it on a camera. Its detection cone should stop tracking V, and the Minecraft view must **not** switch to
     the CCTV.
- **G-5 Traffic.** Use a Redstone Lever on a traffic light at an intersection. Do the lights change, and do cars stop
  and go?
- **G-6 Slow-mo.**
  1. Buy Sandevistan and double-tap sprint in Minecraft. NPCs and cars should slow to ×0.2 while V does not, for
     5 s; the log shows `perks: sandevistan slow-mo for 5 s` then `perks: slow-mo ended`.
  2. Note whether there is any screen effect.
  3. Toggle F9 during the slow-mo. Time must return to normal at once.
- **G-7 Camo.**
  1. In combat, drink an invisibility potion. Hostiles should lose V within a few seconds.
  2. Repeat with only `Game.GetPlayer():SetInvisible(true)` in CET and compare.
  3. Check that the effect is still on after 10 s:
     `StatusEffectSystem.ObjectHasStatusEffect(Game.GetPlayer(), TweakDBID.new("BaseStatusEffect.OpticalCamoPlayerBuffRare"))`.
     Combat camo decay may remove it; Buffs re-applies it once a second.
- **G-8 Visibility.**
  1. With Silent Steps bought, `Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.Visibility)`
     is half its value with the perk off.
  2. Crouch-walk towards an idle guard and compare the time the detection meter takes to fill, with and without the
     perk.

### One check per hack

The target is a hostile ganger, except where a check says otherwise. Every use answers `hackresult ok:true`.
- **Short Circuit (Redstone Charge):** after the upload, electrocution VFX and damage. Covered by G-3.
- **Overheat (Blaze Chip):** the target burns (overheat VFX) and its health ticks down. Steve takes no damage.
- **Reboot Optics (Ink Sac Daemon):** blinded VFX; the target loses track of V for its duration.
- **Cripple Movement (Cobweb Daemon):** the target stops moving.
- **Contagion (Spider Eye Virus):** poison VFX and damage over time on the target. Whether it spreads depends on V's
  spread perks.
- **Synapse Burnout (Wither Skull Daemon):** brain-bolt VFX and heavy damage.
- **Ping (Compass Ping):** the log shows `ok`, and in CET
  `StatusEffectSystem.ObjectHasStatusEffect(npc, TweakDBID.new("BaseStatusEffect.Ping"))` is true. Nothing is visible,
  because the HUD is hidden.
- **Memory Wipe (Bottle of Forgetting):** a ganger in combat with V stops fighting and returns to idle.
- **Cyberpsychosis (Creeper Brain):** on a ganger in a group, it attacks its squad, then kills itself.
- **Device (Redstone Lever):**
  - vending machine: as in G-4;
  - camera: as in G-4;
  - traffic light: as in G-5;
  - a TV or radio: it glitches or distracts;
  - a device with no allowed hack (for example a powered-off one): `hackresult ok:false` with reason
    "not hackable".
- **Chain Hack:** with the perk, a ganger standing within 8 m of the target gets the same hack (two VFX).
- **Refusals:**
  - a companion or a quest NPC in a scene: reason "not hackable";
  - a dead ganger: reason "target gone".

### One check per buff

- **invisibility:** see G-7. When the potion ends, `SetInvisible(false)` and the camo status effect is gone.
- **speed:**
  1. Read `GetStatValue(…, gamedataStatType.MaxSpeed)` before and during Speed II. Expect ×1.4.
  2. V crosses a measured street visibly faster.
  3. The value is back to normal when the potion ends or on F9.
- **slowness:** Slowness I gives `MaxSpeed` ×0.85, and V is slower. Slowness X stays at ×0.3.
- **jump_boost:** with Jump Boost II, V clears an obstacle about twice the normal jump height. Normal again
  afterwards.
- **nausea:** eating a pufferfish gives the drunk wobble
  (`StatusEffectSystem.ObjectHasStatusEffect(Game.GetPlayer(), TweakDBID.new("BaseStatusEffect.Drunk"))` is true). It
  is gone when Minecraft removes the effect. Check whether the wobble is visible behind the Minecraft frame.
- **blindness:** splash blindness gives the blind screen on V (`AIQuickHackStatusEffect.HackBlind` on V). It is gone
  when the effect ends, even if that is after 8 s.
- **darkness:** as blindness, near a Warden or with `/effect give @p darkness`.
- **poison and wither:** the log shows `buffs: effect poison has no Cyberpunk mapping, ignored` once. V gets no
  effect, and Steve takes only Minecraft's own poison damage.

### Perks, scan and kills

- **Double Jump:** with the perk bought, V double-jumps while Active, and
  `GetStatValue(…, gamedataStatType.HasDoubleJump)` ≥ 1. After F9 off, V's own value is back.
- **Scan:**
  - hack key held on a ganger: `scaninfo ok:true` with its name, faction and threat;
  - on a vending machine: `device:true`;
  - on the sky: `ok:false`.
- **Kills:**
  - killing a hostile ganger with a sword logs `killed human <id> (hostile)` once;
  - destroying a car with TNT logs `killed vehicle <id>`;
  - a kill V makes with Cyberblock Off logs nothing.