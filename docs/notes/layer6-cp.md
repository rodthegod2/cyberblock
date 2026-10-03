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