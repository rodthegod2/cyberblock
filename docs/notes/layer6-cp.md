# Layer 6 and potions on the Cyberpunk side (track S3)

Date: 2026-10-03 · Plan: `docs/superpowers/plans/2026-10-03-layers3-6-complete-mod.md` §1.4, §1.5, track S3 ·
Audit: `docs/notes/cp-mechanics-audit.md` (B, C, needsInGame G-3…G-8).

Files: `cp/scripts/Cyberblock/Hacks.reds`, `Scan.reds`, `Perks.reds`, `Buffs.reds`, `Kills.reds`. All compile against
the 2.31 scripts plus Codeware with `cp/scripts/compile_check.py` (run with stubs for the three §1.3 natives S1 adds).

**Re-test 2 (2026-10-03, plan `2026-10-03-retest2-and-cyberware.md`).** Three things below changed and are collected in
[Re-test 2: ids, cyberware and cyberfx](#re-test-2-ids-cyberware-and-cyberfx) at the end: how entity ids are hashed,
the `cyberware` and `cyberfx` messages (which moved the double jump, the visibility modifier and the slow-mo out of
`Perks.reds` into `Cyberware.reds`), and the scan range. Rows and sentences affected are marked "(re-test 2)".

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
  defeated, unconscious or turned off). (Re-test 2: this now only happens for an entity that is really dead or
  despawned. Before, every hack on a live target said "target gone" because the id Minecraft was given was the wrong
  hash, see the section at the end.)
- **"not hackable"**: the entity is not a `ScriptedPuppet` (for `device`: not a `Device`), V's friend,
  Invulnerable or Immortal, in a scene, or a non-crowd NPC whose record offers no `PuppetQuickHack` ObjectAction (how
  story NPCs are made unhackable); a crowd NPC whose record has a quest affiliation other than General; or V is in
  scene tier 3-5. These guards keep quest characters out of reach. They are vanilla's own target gates
  (`ScriptedPuppet.IsQuickHackAble`), which cannot be called as a whole: it needs a cyberdeck, refuses crowd NPCs
  (whose records offer no quickhack at all) and is blocked by `NoQuickHacks`, which `NoCombat` puts on V while Active.

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
  `FindEntityByID`. (Re-test 2: the value is now picked by Minecraft on the server thread from the proxies' real
  positions, and `Scan` returns a one-line note, `scan N: ok <class> <8-digit id> (requested|look-at|near crosshair)`,
  that `Events.reds` logs at most once a second. The maximum scan distance is 80 m with the `kiroshi_optics` implant,
  else 48 m.)
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
| `double_jump` | **(re-test 2) nothing on V.** The perk never touches `HasDoubleJump`: Minecraft only adds 4 blocks of safe fall with a legs implant. V's `HasDoubleJump` Additive +1 now comes from the **`reinforced_tendons`** implant (`Cyberware.reds`), the original design of this row | audit C.2 `GrantStat`. `DoubleJumpDecisions.EnterCondition` checks `HasStatFlag(HasDoubleJump)` and refuses while `HasChargeJump`/`HasAirHover` are set; S1 zeroes those while Active, so the granted double jump works even with charge-jump legs. |
| `silent_steps` | `Visibility` Multiplier 0.5 while Active, **one modifier shared with the `lynx_paws` implant** (re-test 2: it lives in `Cyberware.reds` and applies once if either is present) | audit C.4 `Stealth`: `UpdateVisibilityModifier` → `VisibleObjectDetectionMultEvent` |
| `sandevistan` | **(re-test 2)** the slow-mo is `cyberfx sandevistan` from the implant, handled by `CyberblockCyberware.Fx`; `CyberblockPerks.Fx` delegates to it, so an old `perkfx` still works. `SetIgnoreTimeDilationOnLocalPlayerZero(true)` + `SetTimeDilation(n"cyberblockSlowMo", 0.2, s + 1)`; end: `UnsetTimeDilation(…, n"SandevistanEaseOut")` + ignore off | audit C.1 `SlowMo`/`SlowMoEnd` |
| `dodge` | nothing here: S1 reads `Has(n"dodge")` | plan §1.5 |

How the Sandevistan slow-mo is timed (re-test 2: the same code, now in `Cyberware.reds`, shared with Kerenzikov):
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

## Re-test 2: ids, cyberware and cyberfx

Plan `docs/superpowers/plans/2026-10-03-retest2-and-cyberware.md` §1.3, §1.5, §1.7 and tasks S1, S2, S5, S6. Wire
messages are in `shared/protocol.md` (the **re-test 2** rows).

### The `ToHash` rule: never pass a call result (R2, the main cause of "target lost")

- **Symptom:** every scan logged "nothing under the crosshair at 0" and every hack "target gone", although Minecraft had
  40+ proxies.
- **Cause:** Codeware's `EntityID.ToHash(id: script_ref<EntityID>)` takes a **reference**. When it is given a call result
  (`EntityID.ToHash(object.GetEntityID())`) there is no variable to refer to, and it hashes garbage: a heap-address-like
  number. Four call sites (Scan, Kills, Puppets, Effects) sent such ids (`2668059858640`, `0`) instead of the real ones
  (`10019101`). Minecraft locked that id, the `hack` came back with it, and `FindEntityByID` failed with "target gone".
  The log shows both numbers for one NPC, two lines apart: `events: hit target 10019101 ...`, then
  `effects: push withheld from 2668059858640 (workspot)` (`Cyberblock.log` 2026-10-03 15:12:12). Codeware:
  `Codeware.Global.reds` 42199-42201.
- **Rule:** **always** `CyberblockIds.Hash(entity)` (`cp/scripts/Cyberblock/Ids.reds`). It returns `0ul` when the entity is not
  defined, copies `GetEntityID()` into a local first, and calls `EntityID.ToHash(local)`. Scan, Kills, Puppets, Effects and
  Npcs all use it now.
- **Guard:** `tests/test_reds_ids.py` strips the `//` comments of every `cp/scripts/Cyberblock/*.reds` and requires that
  each `EntityID.ToHash(...)` argument is a bare identifier; a failure names `file:line`.
- **Checking in game:** the scan line carries the id (`scan N: ok <class> <id> ...`): a real id has about 8 digits.

### `cyberware` events (R6)

- **Wire to script:** one `cyberware` message becomes one `cyber` event per entry (`EventName` = the cyberware id,
  `EventAmount` = 1 installed or 0 not). Unlike `cyberfx`, `cyber` is **kept while the host is not Active** and outranks
  like `perk`, so installs survive a vehicle ride or F9. `Events.reds` applies it with
  `CyberblockCyberware.Set(game, EventName, RoundF(EventAmount) == 1)` in both the live and the inactive branch.
- **The service** (`Cyberware.reds`, `CyberblockCyberwareService`, a Codeware `ScriptableService` like the perks):
  it holds the 17 known ids, the install flags, the owner entity and the modifiers it added. API: `Set`, `Has`, `Fx`,
  `Tick`, `Clear`. `Tick` (live and not live) adds or removes its modifiers through the `Perks.reds` owner and
  `RemoveModifiers` pattern, on engine time:

  | Implant | On V (while Active) |
  |---|---|
  | `reinforced_tendons` | `HasDoubleJump` Additive +1 (`PlayerState.ZeroAbilities` keeps the ability only for it) |
  | `fortified_ankles` | `HasChargeJump` Additive +1 (same rule) |
  | `lynx_paws` (or the `silent_steps` perk) | `Visibility` Multiplier 0.5, **one** modifier |
  | `kiroshi_optics` | `CyberblockScan` `maxDistance` 80 instead of 48 |
  | everything else | nothing on V: its effect is Minecraft's, or it comes through `cyberfx` or `effect` |

  `PlayerState.ZeroAbilities` still zeroes everything else (`CanUseOpticalCamo` too: camo comes through `effect`
  `invisibility`), and keeps `HasDodge` / `HasDodgeAir` only for the `dodge` perk.
- **Clear** removes every modifier, ends any slow-mo and unsets the dilation.
- **Log:** `cyberware: <id> on|off`.

### `cyberfx` (R6)

- **Wire to script:** `cyberfx` (`id`, `seconds` 0..30) becomes a `cyberfx` event (`EventName` the id, `EventAmount` the
  seconds, 0 = end). It is **discarded while the host is not Active**. `Events.reds` calls
  `CyberblockCyberware.Fx(game, id, seconds)` for live ones only.
- **`sandevistan`:** `SetIgnoreTimeDilationOnLocalPlayerZero(true)` and `SetTimeDilation(n"cyberblockSlowMo", 0.2, s + 1)`
  (the C.1 recipe above, ended on engine time).
- **`kerenzikov`:** the same with factor 0.3. A newer call replaces the factor and the end; it never stacks; `0` ends the
  effect.
- **`berserk` (task S6, gated):** the record `BaseStatusEffect.BerserkPlayerBuff` is applied only if its tweakdb entry
  carries nothing beyond visual effects and its forced superhero fall cannot stomp NPCs; otherwise the script logs
  `berserk: Minecraft side only` and the jump boost still reaches V through the ordinary `effect` path. The decision and
  its reason are written in the header of `Cyberware.reds` and in package S's report.
- **Log:** `cyberfx: <id> <s> s`.

### needsInGame (re-test 2)

The ready-made list is `docs/TESTING-PLAN.md` "Re-test 2". The ones that belong to this file:
- **Scan id:** hold G on a pedestrian: `scan N: ok <class> <8-digit id> (requested)`; then a Bottle of Forgetting gives
  `hack memory_wipe on <same id>: ok`.
- **Cyberware events:** `CyberblockCyberware.Has(Game.GetPlayer():GetGame(), n"reinforced_tendons")` is true after
  installing it; `Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.HasDoubleJump)` is 1
  with it and 0 without, even with the Double Jump perk; `HasChargeJump` likewise with Fortified Ankles.
- **cyberfx:** Y on Sandevistan logs `cyberfx: sandevistan 6 s` and Night City slows to x0.2 while V does not; Kerenzikov
  in the air logs `cyberfx: kerenzikov 2 s` and slows to x0.3; F9 off mid-effect restores time at once.
- **Berserk:** note which line appears (`cyberfx: berserk 8 s` with or without `berserk: Minecraft side only`).
