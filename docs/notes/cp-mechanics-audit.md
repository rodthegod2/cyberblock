# Cyberpunk 2.31 mechanics audit: turning V off while Steve is Active

Date: 2026-10-02 · Scope: research only (no code changes) · Game: Cyberpunk 2077 2.31 + Phantom Liberty

While Cyberblock is **Active** (on foot), Steve is the player: Cyberpunk keeps V's on-foot movement, and
all of V's combat and abilities must be off. This note lists every V mechanic that has to be blocked, how 2.31
implements it, whether `cp/scripts/Cyberblock/PlayerState.reds` (main at `db865c9`) already blocks it, and what to
use instead. It also covers how to fire quickhacks and perk effects from scripts, and cross-checks the damage hook.

## Sources and legend

| Tag | Source |
|---|---|
| **S** | Decompiled 2.31 game scripts: codeberg.org/adamsmasher/cyberpunk at `a2e6bb3 "2.31"` (local clone in `%TEMP%\cbs2\cp231`). Cited as `file.swift:line`. |
| **T** | The game's TweakDB, `r6/cache/tweakdb_ep1.bin` (the database the game uses when Phantom Liberty is installed). Read-only, with record and flat names from CET's `tweakdbstr.kark`. Every ID cited here also exists in `tweakdb.bin`. |
| **K** | Installed default bindings: `r6/config/inputUserMappings.xml` (key → mapping) and `inputContexts.xml` (mapping → action and context). These do not include the player's own rebinds. |
| **N** | NativeDB class dump (gameStatsSystem, gameTimeSystem, gameStatusEffectSystem, gameGodModeSystem, gamedeviceAction). |
| **X** | `dumpbin /imports Cyberpunk2077.exe`, which lists the USER32 imports `RegisterRawInputDevices` and `GetRawInputData` and **no** `GetRawInputBuffer` or DirectInput. |
| **R** | This repo: `PlayerState.reds`, `cp/plugin/src/input.cpp` (main), `Health.reds` (branch `l2/S1` at `abcc2b4`). |
| **CC** | Compile-checked: the snippets in the Appendix compile against the 2.31 scripts plus Codeware. They were checked two ways: (1) with main's scripts, and (2) with `l2/S1`'s scripts (including Health.reds) plus stubs for the S2 classes. |

Each claim is marked with one of these tags:
- **[C] confirmed**: read directly in the source given.
- **[I] inferred**: reasoned from the sources, but not traced to the end (for example, native code).
- **[G] needsInGame**: can only be settled in the game. Each one has a concrete check in the needsInGame section.

## Key findings

1. **Cyberpunk still receives every forwarded key** [C, R]. `input.cpp` forwards E, Q, T, `/`, 1–9, the mouse
   buttons and the wheel to Minecraft, but `wnd_proc` passes all of them on to Cyberpunk (`CallWindowProcW`). Only
   Esc is swallowed, and only while a Minecraft screen is open. **F5 is not forwarded at all.** So whether a key is
   safe today depends entirely on the gameplay restrictions.
2. **`GameplayRestriction.NoCombat` already blocks most of V's combat** [C, S/T]: equipping and firing weapons,
   melee, fists, grenades and gadgets (middle mouse), the Projectile Launch System, activating Optical Camo, arm
   cyberware, Berserk, Sandevistan, Kerenzikov, Overclock, the quickhack panel, takedowns, grapples and finishers.
3. **These are not blocked today:**
   - the healing hotkey (X);
   - the scanner (Tab), including its world slow-down;
   - opening the weapon wheel (Alt), including the freeze it causes;
   - double jump, charge jump and hover;
   - dodge, dash and air dash (dash and air dash are gated by perks, not by stats);
   - superhero landing;
   - picking up bodies;
   - **F5 quicksave**;
   - **G (toggle walk)** and **K (crafting menu)**, both relevant because G and K are about to be added as keys;
   - **1 and 2, which pick interaction choices 3 and 4.**
4. **Quickhacks can be fired from scripts without V's cyberdeck** [C, S]. Use the same `PuppetAction` pattern the
   game itself uses in `ApplyObjectActionEffector` and `SpreadAreaEffector`, with `SetCanSkipPayCost(true)`. Each
   hack's damage, VFX and SFX live in its status effect record, so applying the effect directly also works.
5. **The damage hook in Health.reds matches 2.31** [C]. There are three gaps: damage-over-time effects never land on
   V (because the god mode makes V immune to debuffs), instant-kill hits bypass the hook, and the death landing
   needs an in-game check. A fix for the first gap is compile-checked (Appendix, `IsImmune` wrap).

---

## A. Disable list

### A.1 What NoCombat does in 2.31

From the TweakDB [T]:
- `GameplayRestriction.NoCombat` has gameplayTags `GameplayRestriction, NoCombat, NoQuickHacks, NoGrenadeOrGadget`.
- Its package multiplies these stats by 0 (Multiplier 0): `HasBerserk`, `HasSandevistan`, `HasKerenzikov`,
  `CanUseOverclock`.
- `GameplayRestriction.NoPhone` has the tag `NoPhone`, and `GameplayRestriction.NoCameraControl` has the tag
  `NoCameraControl`.

In the scripts [S], the `NoCombat` tag drives `DefaultTransition.IsNoCombatActionsForced`
(`defaultTransition.swift:1225`), which feeds three checks:
- **`IsEmptyHandsForced`** (`:1334`). This makes `ForceEmptyHandsDecisions` enter the `forceEmptyHands` state, whose
  `OnEnter` sends `UnequipAll` (`upperBodyTransitions.swift:293–331`).
- **`CheckGenericEquipItemConditions`** (`:1338`). This gates every weapon-equip input in `EmptyHandsEvents`
  (`upperBodyTransitions.swift:530`).
- **`IsUsingLeftHandAllowed`** (`:1267`). This gates grenades, gadgets and left-hand cyberware.

### A.2 Mechanics: how each is blocked, and whether PlayerState covers it

| V mechanic | 2.31 mechanism (source) | Covered now? | Recommendation |
|---|---|---|---|
| Firearms and melee weapons: draw, fire, swing | The `NoCombat` tag puts V in `forceEmptyHands` (UnequipAll). `EmptyHandsEvents` returns early on `CheckGenericEquipItemConditions` (`upperBodyTransitions.swift:530`), so LMB, 1–4, the wheel, Alt and B never request a weapon. The weapon is "not ready" (`weaponTransitions.swift:599,619`). `MeleeTransition.IsWeaponReady` is false (`meleeTransitions.swift:618`). [C] | **Yes** | Keep. |
| Fists / unarmed | Same as above. Fists are only forced by `Fists`-tagged restrictions, such as the quest `GameplayRestriction.FistFight` (`statusEffectSystem.swift:443`). [C] | **Yes** | Keep. |
| Weapon wheel (hold Alt; `WeaponWheel` action) | `WeaponWheelDecisions` opens unless `NoRadialMenus`, `NoWeaponWheel` or `FocusModeLocomotion` is set (`quickSlotsTransitions.swift:436`). Under NoCombat, `RadialWheel.DrawItem` refuses (`radialWheel.swift:492`) and selecting combat items is prevented (`quickSlotsManager.swift:245`). The radial menu applies time dilation `timeSystem.radialMenu.timeDilation = 1e-6`, which freezes Night City [T]. [C] | **Partly**: the wheel still opens and freezes the world | Add `GameplayRestriction.NoRadialMenus` (tags `NoRadialMenus, VehicleBlockRadioInput`). The radio tag is harmless, because PlayerState is only applied on foot. |
| Grenades and gadgets (middle mouse, `UseCombatGadget`) | `EmptyHandsEvents` needs `IsUsingLeftHandAllowed` (`upperBodyTransitions.swift:600–604`). The EquipmentSystem hotkey check, `IsHotkeyRestricted(RB)`, reads the tags in `DPadUIData.RB.restrictionTags = [NoGrenadeOrGadget]` (`equipmentSystem.swift:3659`, `statusEffectSystem.swift:598–621`, T). [C] | **Yes** | Keep. |
| Projectile Launch System (quick wheel `Cyb_Launcher`) | Same middle-mouse path (`upperBodyTransitions.swift:630–638`) and left-hand cyberware `IsUsingCyberwareAllowed` (`leftHandCyberwareTransitions.swift:267–271`). [C] | **Yes** | Keep. |
| Optical Camo activation (quick wheel `Cyb_Ability`) | Same middle-mouse path (`upperBodyTransitions.swift:618–628`). [C] Perk camo variants, which apply `OpticalCamoSlideCoolPerk` on a slide and `OpticalCamoGrapple` on a grapple (`player.swift:4361`), are not gated by NoCombat. [I] | **Activation yes. Perk variants unclear** | Also multiply `CanUseOpticalCamo` and `CanUseTerrainCamo` by 0 (A.4). [G-11] |
| Arm cyberware: Mantis Blades, Gorilla Arms, Monowire | Equipped as weapons in `ArmsCW`, so blocked by the forceEmptyHands path. Do **not** use `GameplayRestriction.NoDangerousCyberware`: its `NoArmsCW` tag calls `PlayerGameplayRestrictions.RequestFists` (`statusEffectSystem.swift:449`), which would put fists in V's hands. [C] | **Yes** | Keep. Avoid NoDangerousCyberware. |
| Berserk, Sandevistan, Overclock (E, `IconicCyberware`) | `PlayerPuppet.ActivateIconicCyberware` checks `HasBerserk`, then `HasSandevistan`, then `HasCyberdeck && CanUseOverclock` (`player.swift:1282–1329`). All are multiplied by 0 by NoCombat [T]. `SandevistanDecisions` also listens for `HasSandevistan` changes (`timeDilationTransitions.swift:115–136`). [C] | **Yes** | Keep. |
| Kerenzikov | `KerenzikovDecisions` is enabled by `HasKerenzikov` (`timeDilationTransitions.swift:237–253`), which is multiplied by 0. [C] | **Yes** | Keep. |
| Quickhack wheel | `QuickhackModule.BaseOpenCheck` needs focus (scanner) mode and `!IsQuickhackBlockedByScene` (the `NoQuickHacks` tag) (`quickhackModule.swift:71–90`). [C] | **Yes** (the NoQuickHacks tag comes from NoCombat) | Keep. Blocking the scanner (next row) also blocks it. |
| Scanner (hold Tab / CapsLock, `Vision*`) | `PlayerVisionModeController.UpdateNoScanningRestriction` reads the `NoScanning` tag (`playerVisionModeController.swift:413`, triggered from `player.swift:4259`). Focus mode slows the world: `timeSystem.focusModeTimeDilation.timeDilation = 0.03` [T]. [C] | **No** | Add `GameplayRestriction.NoScanning`. The layer-6 hack key must then use its own highlight, not Cyberpunk's scanner. |
| Consumables / healing hotkey (X, `UseConsumable`) | Allowed even in forceEmptyHands (`upperBodyTransitions.swift:337–344`), and only refused by `IsUsingConsumableRestricted`. That calls `IsHotkeyRestricted(DPAD_UP)`, which reads `DPadUIData.DPAD_UP.restrictionTags = [NoInhalerOrInjector]` (`defaultTransition.swift:1294–1316`, T). [C] | **No** | Add `GameplayRestriction.NoHealing` (tag `NoInhalerOrInjector`). |
| Double jump | `AirThrustersDecisions.ToDoubleJump` and `DoubleJumpDecisions` check `HasDoubleJump` (`locomotionTransitions.swift:3936,4237`). Granted by `Items.BoostedTendonsRare` and `Items.AdvancedBoostedTendonsUncommon` (+1) [T]. [C] | **No** | Multiply `HasDoubleJump` by 0 (A.4). The Steve perk removes that modifier (C.2). |
| Charge jump / hover | `ChargeJumpDecisions` needs `HasChargeJump` and no `HasAirHover`; `HoverJumpDecisions` uses `HasAirHover` (`locomotionTransitions.swift:4312–4334,4440`). Granted by `Items.ReinforcedMuscles*` [T]. [C] | **No** | Multiply `HasChargeJump` and `HasAirHover` by 0. |
| Dodge, dash, air dash | `LocomotionTransition.WantsToDodge` needs `HasDodge`, which every player gets from `Character.Player_Primary_Stats_Base_inline100` [T]. In the air it also needs perk `Reflexes_Central_Milestone_3` at level 3 (`locomotionTransitions.swift:262–299`). `DodgeDecisions` turns a dodge into a **Dash**, which leaps at a hostile in the crosshair (`LeapToTarget`), when `Reflexes_Central_Milestone_2` is at level 2 (`:2340–2364`). `DodgeAirDecisions` also needs `HasDodgeAir` (`:4581–4604`). [C] | **No** | Multiply `HasDodge` and `HasDodgeAir` by 0. This turns off the ground dodge too, which matches the layer-6 Dodge perk design (see C.2). Air dash and dash depend on perks, so stats cannot grant them. Use the `IsNewPerkBought` wrap (Appendix). |
| Superhero landing (ground slam) | Stat `HasSuperheroFall`, which `NoDangerousCyberware` multiplies by 0 [T]. No script reads it, so it is gated in PSM/TweakDB data [I]. `SuperheroFallEvents` lives at `locomotionTransitions.swift:4114`. | **No** | Multiply `HasSuperheroFall` by 0. [G-12] |
| Takedowns, grapples, aerial takedowns, finishers | `Takedown.Grapple`, `Takedown.AerialTakedown` and `Takedown.NewPerkFinisher` list `Takedown.GameplayRestrictions` among their instigatorPrereqs. That is an AND of inverted `StatusEffectPrereq`s on, among others, **exactly the ID `GameplayRestriction.NoCombat`** (the ID, not the tag) [T]. [C] | **Yes** | Keep applying that exact record. |
| Body pickup (`GenericInteraction.PickUpBody`, interaction on downed or dead NPCs) | Its prereqs only exclude `GameplayRestriction.BodyCarryingForceDrop` and `BaseStatusEffect.Berserk` [T]. Bodies left by Minecraft kills can still be carried. [C] | **No** | Optionally add `GameplayRestriction.BodyCarryingForceDrop`. Its only tag is `BodyCarryingForceDrop` [T]. [G] |
| Quick melee (Q while holding a gun) | Needs a ranged weapon in hand, which is impossible under forceEmptyHands. `NoQuickMelee` is checked at `weaponTransitions.swift:332`. [C] | **Yes** | `GameplayRestriction.NoQuickMelee` is optional, belt and braces. |
| Phone (T) | `PhoneSystem.IsBlockedByStatus`: `NoPhone` (`phoneSystem.swift:300–305`). [C] | **Yes** | Keep. |
| Crafting menu (K) | `TryOpenCraftingMenu` → `HubMenuUtility.IsCraftingAvailable` (the `NoCrafting` tag) (`inGameMenuGameController.swift:598`, `menuDataBuilder.swift:123–129`). With NoCrafting, K shows a "combat restriction" notification instead. [C] | **No** | Swallow K in the DLL (A.3). |
| Quicksave (F5) | `gameuiInGameMenuGameController.OnAction(QuickSave)` → `HandleQuickSave` (`inGameMenuGameController.swift:170,204,537`). The only things that stop it are save locks. [C] | **No** | Swallow F5 in the DLL, or use the compile-checked `HandleQuickSave` wrap. |
| Toggle walk (G) | `PlayerPuppet.OnAction(ToggleWalk)` → `ProcessToggleWalkInput` (`player.swift:1078,1203,1266`). [C] | **No** | Swallow G, or use the compile-checked `ProcessToggleWalkInput` wrap. |
| Interaction choices 3 and 4 (keys 1 and 2) | `Choice3`/`Choice4` are mapped to IK_1/IK_2 in context `InteractionActions` [K]. They are active whenever a world interaction hub is shown. [C] | **No** | Swallow 1–9 in the DLL. |

### A.3 Every forwarded key: Cyberpunk default and how to block it

The defaults come from K (mapping → `<action>` → context). The current behaviour comes from R: every key below also
reaches Cyberpunk.

| Key | Cyberpunk default (mapping → action, context) | Reaches V today? | Recommended block |
|---|---|---|---|
| E | `IconicCyberware_Button` → `IconicCyberware` (Abilities and vehicle contexts). `InspectionEnd`. UI: selectDown, PhotoMode next, `upgrade_cyberware`. `LeanRight_Button` is mapped but in no context. | Iconic cyberware is blocked by the stats (A.2). In UI it still moves selections. | DLL swallow (keyboard path, below). |
| Q | `QuickMelee_Button` → `QuickMelee` (Combat). `PickUpBodyFromTakedown_Button` / `BodyDrop_Button` (AbilitiesWithoutIconic). `VehicleCameraToggle` (vehicle). UI selectUp. | Blocked by restrictions (needs a weapon or a takedown). | DLL swallow. |
| T | `PhoneInteract_Button` (UIToggles), `Notification_Button` (UIExploration, SettingsLocomotion), `CycleObjectives_Button` (UI_DPad), `InspectionTake`, `CameraToggleStep`, `LockCameraButton` (photo mode). | Phone is blocked by NoPhone. Objective cycling and notifications are **not** [I]. | DLL swallow. |
| 1–4 | `WeaponSlot1..4_Button` (KeyboardExclusiveWeaponSwitching). 1/2 = **`Choice3`/`Choice4`** (InteractionActions). 1/3 = `CyclePrev`/`CycleNext`, `popup_prior`/`next`. | Weapon slots are blocked (A.2). **Interaction choices 3 and 4 are not.** | DLL swallow. |
| 5–9 | `Keyboard_5..9` (UI_Keyboard_Numbers, UI only). 8/9 = `prior_sub_menu`/`next_sub_menu`. | UI only. | DLL swallow (cheap, consistent). |
| `/` | Not bound (`IK_Slash` absent). | No. | Nothing needed. Swallowing keeps it consistent. |
| F5 | `QuickSave_Button` → `QuickSave` (QuickLoadAndSave). `pause_menu_quicksave`. | **Not forwarded yet.** Once forwarded it would quicksave on every perspective toggle. | DLL swallow (and/or the `HandleQuickSave` wrap). |
| G (planned) | `ToggleWalk_Button` → `ToggleWalk` (Locomotion). `Vehicle_Autodrive` (vehicle). `gog_rewards`/`sell_junk` (menus). | Would toggle V's walk mode. | DLL swallow (and/or the `ProcessToggleWalkInput` wrap). |
| K (planned) | `OpenCraftingMenu_Button` → `OpenCraftingMenu` (UIToggles, UIMenu) → crafting hub. | Would open the crafting menu. | DLL swallow (NoCrafting only swaps the menu for a notification). |
| Left mouse | `RangedAttack_Button` / `MeleeAttack_Button` (Combat), `ThrowNPCAlt`, vehicle shooting. UI: `menu_click`, `activate`, `SelectWheelItem`. | Blocked by restrictions on foot. | Mouse path (below). |
| Right mouse | `RangedADS_Button` / `MeleeBlock_Button` (Combat, Camera, …). UI: cancel, back, `ExitWheel`. | Nothing to aim with. UI back still works. | Mouse path. |
| Middle mouse | `CombatGadget_Button` → `UseCombatGadget` and `ThrowNPC` (AbilitiesWithoutIconic). `Tag_Button` (InteractionActions, Aiming). | The gadget is blocked. `Tag` on interactables is not [I]. | Mouse path. |
| Wheel | `NextWeapon`/`PreviousWeapon` (KeyboardExclusiveWeaponSwitching), `ZoomIn`/`ZoomOut` (Aiming), UI MoveUp/Down, `dpad_up`/`dpad_down`. | Weapon switching is blocked (A.2). Zoom needs ADS or the scanner. UI and dialogue lists still scroll [I]. | Mouse path. |

**Recommended blocking methods** (DLL, `cp/plugin/src/input.cpp`):

- **Keyboard keys** (E, Q, T, 1–9, `/`, F5, G, K).
  - **How:** treat them like the existing Esc path while `forwarding()` is true. Drop the `WM_INPUT` keyboard packet
    (`RAWKEYBOARD.VKey` in the set, `return DefWindowProcW`) **and** the `WM_KEYDOWN`/`WM_KEYUP` message
    (`return 0`).
  - **Why both:** the game imports `GetRawInputData` and `RegisterRawInputDevices` [X]. Which path it reads
    keyboard keys from is [G-1], so blocking both covers either case.
  - **Key-up rule:** a key-up whose key-down Cyberpunk saw must still reach it, so no key can stick.
- **Mouse buttons and wheel.**
  - **Why not drop the packet:** the raw mouse packet also carries the camera motion.
  - **How:** IAT-hook `USER32!GetRawInputData` in `Cyberpunk2077.exe`. It is imported, and `GetRawInputBuffer` is not
    [X]. While forwarding, clear these `RAWMOUSE.usButtonFlags` bits:
    - `RI_MOUSE_LEFT/RIGHT/MIDDLE_BUTTON_DOWN`
    - `RI_MOUSE_BUTTON_4/5_DOWN`
    - `RI_MOUSE_WHEEL`/`HWHEEL`
  - **Keep the `*_UP` bits,** and also eat `WM_*BUTTON*` and `WM_MOUSEWHEEL`.
  - **Until then:** A.2 shows that every on-foot action on these buttons is already blocked, except UI scrolling and
    `Tag`.
- **Cyberpunk menus.** `forwarding()` should also be false while a Cyberpunk menu is open. The script side can read
  `UI_System.IsInMenu` (`blackboardDefinitions.swift:700`, `CyberblockAudit.InMenu` in the Appendix [CC]) and pass
  it to the DLL with `Cyberblock_SetActive`.
- **Script-level alternatives for single actions** [CC]. These are useful even with the DLL block, as a second line:
  - `@wrapMethod(gameuiInGameMenuGameController) HandleQuickSave` for F5.
  - `@wrapMethod(PlayerPuppet) ProcessToggleWalkInput` for G.
  - Do **not** rely on `ListenerActionConsumer.Consume`. State-machine decisions read action state directly through
    `scriptInterface.IsActionJustPressed`, so consuming an action does not stop them [I].

### A.4 Changes to PlayerState

**1. Additional restrictions, applied with `Own()` like the existing ones** [T/S].
All of them are confirmed as records in both TweakDB files:
- `GameplayRestriction.NoHealing`
- `GameplayRestriction.NoScanning`
- `GameplayRestriction.NoRadialMenus`
- optional: `GameplayRestriction.NoQuickMelee`, `GameplayRestriction.NoZooming`, `GameplayRestriction.NoCrafting`,
  `GameplayRestriction.BodyCarryingForceDrop`

**2. Stats NoCombat does not zero.** Multiply them by 0 with runtime modifiers (`CyberblockAudit.ZeroAbilities` [CC]):
- `HasDoubleJump`, `HasChargeJump`, `HasAirHover`
- `HasDodge`, `HasDodgeAir`
- `HasSuperheroFall`, `HasKerenzikovSlide`, `HasSandevistanTier2`
- `CanUseOpticalCamo`, `CanUseTerrainCamo`
- `CanSprintHarass`, `SandevistanDashShoot`

The last two lists come from `GameplayRestriction.NoDangerousCyberware`'s package [T], minus `CanUseStrongArms`.
`CanUseStrongArms` is the Gorilla Arms body ability, which opens doors on foot, and it is not combat.

**How the modifiers work:**
- Add them with `StatsSystem.AddModifier`. Remove them with `RemoveModifier` on the same object.
- `AddModifier` and `AddSavedModifier` are separate natives [N], so plain `AddModifier` is presumably not saved [I].
  Remove the modifiers in `ClearSaved` anyway [G-2].

**3. Possible simplification.** `StatusEffectSystem.ApplyStatusEffect` takes an optional `isSavable: Bool` [N].
Applying the restrictions with `isSavable = false` (`CyberblockAudit.ApplyRestriction` [CC]) could remove the need to
strip them before every save [I, G-14].

**Avoid:**
- `NoDangerousCyberware`: it forces fists (see A.2).
- `NoJump`: it multiplies `CanJump` by 0 [T].
- `Cyberspace` and `Braindance`: they multiply `CanJump`, `CanSprint` and `CanCrouch` by 0 [T]. Steve must keep
  normal movement.

---

## B. Quickhack table

All IDs are records in both TweakDB files [T]. The display names were matched to internal names through the SFX names
in the records (`quickhack_shortcircuit`, `quickhack_synapse_burnout`, `quickhack_cyberpsychosis`, …) and through
`BaseStatusEffect.RebootOpticsCooldown` [T]. Mapping *Cripple Movement → LocomotionMalfunction* is by record and SFX
name [I].

| Hack | Program item / ObjectAction tiers | Effect applied to the target (completionEffects) | Damage / behaviour | VFX / SFX |
|---|---|---|---|---|
| Short Circuit | `Items.EMPOverloadLvl1..4(PlusPlus)Program`; `QuickHack.OverloadHack`, `OverloadLvl1Hack`…`OverloadLvl4PlusPlusHack` (DamageHack) | `BaseStatusEffect.OverloadLevel1` (type Overload; per-tier records) | `TriggerAttackOnOwnerEffect` → `Attacks.OverloadQuickHackAttackLevel1` (+100 `ElectricDamage`, `AttackType.Hack`, flags QuickHack/Nonlethal, `playerAsInstigator`) | `status_electrocuted`; `quickhack_shortcircuit` (`_mech` for non-humans) |
| Overheat | `Items.OverheatLvl1..`; `QuickHack.OverheatHack`, `OverheatLvl1Hack`… | `BaseStatusEffect.OverheatLevel1` (type Burning, DoT) | `TriggerContinuousAttackEffector` → `Attacks.OverheatQuickHackAttackLevel1` | `hacks_overheat_lvl1`; `quickhack_overheat` |
| Reboot Optics | `Items.BlindLvl1..`; `QuickHack.BlindHack`, `BlindLvl1Hack`… (ControlHack) | `BaseStatusEffect.ModerateBlind` + `BaseStatusEffect.QuickHackBlindLvl1`, plus `Effectors.NotifyPoliceEffector` | blinds; resets squad sync | `status_blinded` |
| Cripple Movement | `Items.LocomotionMalfunctionLvl2..`; `QuickHack.LocomotionMalfunctionHack`, `…Lvl2..4Hack` | `BaseStatusEffect.LocomotionMalfunction` (type QuickHackStaggerLocomotion), plus police notify | stops the NPC from moving | `hacks_locomotion_malfunction`; `quickhack_locomotion_malfunction` |
| Contagion | `Items.ContagionLvl2..`; `QuickHack.ContagionHack`, `ContagionLvl2..4Hack` | `BaseStatusEffect.ContagionPoison` (Poisoned DoT); spread through `SpreadEffector` → `QuickHack.ContagionHackSpread` | `Attacks.ContagionPoisonAttack` (continuous) | `hacks_contagion`; `quickhack_contagion` |
| Synapse Burnout | `Items.BrainMeltLvl2..` (no Lvl1); `QuickHack.BrainMeltLvl2Hack`… | `BaseStatusEffect.BrainMeltLevel2` (type BrainMelt) | `Attacks.BrainMeltHackAttackLevel2` at Head; `DismemberEffector`; `AndroidHeadRemovedBlind` | `hacks_brain_bolt`; `quickhack_synapse_burnout` |
| Ping | `Items.PingLvl2..`; `QuickHack.PingHack`, `PingLvl2..4Hack` | `BaseStatusEffect.Ping` (`PingSquadEffector`) | reveals squad and network. This is UI, so it is invisible with the HUD hidden | `quickhack_ping` |
| Memory Wipe | `Items.MemoryWipeLvl2..` (Lvl2 and up only); `QuickHack.MemoryWipeLvl2Hack`… | `BaseStatusEffect.MemoryWipeLevel2` (type Blind; tags ClearThreats, Stealth) | the NPC forgets threats | `quickhack_memory_wipe` (no VFX record) |
| Cyberpsychosis | `Items.MadnessLvl3..` (Lvl3 and up only); `QuickHack.MadnessLvl3Hack`… (UltimateHack) | `BaseStatusEffect.Madness` (`MadnessEffector`, `SuicideWithWeapon`), plus `Effectors.RewardPlayerWithCrimeScoreEffector` | the NPC attacks its squad, then kills itself | `quickhack_cyberpsychosis` |

**Most robust way to fire a hack without V's cyberdeck** [C, S; snippet `CyberblockAudit.Quickhack`, CC]:

1. **The pattern.** Use the game's own code path from `ApplyObjectActionEffector` (`quickhackEffectors.swift:2–33`)
   and `SpreadAreaEffector` (`:656–664`):
   - `new PuppetAction()`
   - `RegisterAsRequester(target id)`
   - `SetExecutor(player)`
   - `SetObjectActionID(t"QuickHack.…")`
   - `SetUp(target.GetPuppetPS())`
   - `SetCanSkipPayCost(true)` (`baseDeviceActions.swift:1006`)
   - `ProcessRPGAction(game)`
2. **What `ProcessRPGAction` does** (`baseDeviceActions.swift:308–324`):
   - skips the RAM cost through `CanSkipPayCost`;
   - runs the startEffects;
   - uploads the hack (`ScriptableDeviceAction.StartUpload` on the target's `QuickHackUpload` pool, `:1216`), or
     completes it at once;
   - on completion applies the completionEffects as status effects and effectors (`:345–436`). That is where the
     damage, the VFX, the NCPD notification and the crime score come from.
3. **What it does not check.** It does not check the cyberdeck, the instigator prereqs or the target's quickhack list
   (those are UI-side checks).
4. **Side effects** [C]:
   - V gets the hack's cooldown status effect (an Instigator startEffect, for example `BaseStatusEffect.OverloadCooldown`).
   - V gets Intelligence XP (`RPGManager.AwardExperienceFromQuickhack`, `:361`).
5. **Spread.** Use `SetDisableSpread(true)` except for Contagion. How far a hack spreads comes from the player's
   spread map (`HackingDataDef.GetValuesFromSpreadMap`, `quickhackEffectors.swift:580`) [I]. So the Chain Hack perk
   should fire a second hack itself.
6. **Instant variant.** `StatusEffectHelper.ApplyStatusEffect(target, t"BaseStatusEffect.OverloadLevel1",
   player.GetEntityID())` [C, CC]. It carries the damage, VFX, SFX and AI behaviour (all in the status effect's
   packages). It loses the upload, the police notification and the `WasQuickHacked`/`QuickHackUploaded` markers.
   [G-3]

**Device hacks** [C, S; snippet `CyberblockAudit.DeviceHack`, CC]:

1. **Getting the action.** Get the device PS (`Device.GetDevicePS()`). Build a Remote context with
   `ps.GenerateContext(gamedeviceRequestType.Remote, Device.GetInteractionClearance(), player, deviceID)`
   (`scriptableDeviceBasePS.swift:3419`). Then `ps.GetActionByName(name, context)`, which returns the device's
   quickhack actions (`:1449–1470`).
2. **Running it.** Then `RegisterAsRequester(deviceID)`, `SetExecutor(player)`, `SetCanSkipPayCost(true)` and
   `ProcessRPGAction`. This is the same as `Device.ExecuteAction` (`deviceBase.swift:682–693`). On completion,
   `QueuePSDeviceEvent` runs the device's own handler.
3. **Which action name to use:**
   - **Distract** (vending machines, arcade cabinets, radios, TVs, …): action class `QuickHackDistraction`
     (actionName `QuickHackDistraction`, `mediaDeviceActions.swift:168`) with object action
     `DeviceAction.MalfunctionClassHack`. For example `vendingMachineController.swift:119–125`, where the handler
     `OnQuickHackDistraction` (`:154`) also counts down the hack charges.
   - **Camera:** `ForceIgnoreTargets` (`DeviceAction.OverrideAttitudeClassHack`; the camera ignores V) or
     `QuickHackToggleON` (`DeviceAction.QuickHackToggleON`; switches it off)
     (`surveillanceCameraController.swift:165–215`). **Do not use `ToggleTakeOverControl`**
     (`DeviceAction.TakeControlCameraClassHack`). It makes the CCTV the active camera, and Cyberblock streams the
     active camera to Minecraft [I].
   - **Traffic lights:** 2.31 has **no player quickhack** for them. `TrafficLightControllerPS` does not override
     `GetQuickHackActions` or `CanCreateAnyQuickHackActions` (`trafficLightController.swift`). The base class returns
     false and only finalizes (`scriptableDeviceBasePS.swift:1604–1610`) [C]. Light changes go through the intersection:
     `TrafficIntersectionManagerControllerPS.HandleLightChangeRequest()` / `SetLightChangeRequest(color)` (public
     final, `intersectionManagerController.swift:41–47`). Reach it through the light's `GetParents`
     (`CyberblockAudit.FlipIntersection`, CC). Whether traffic reacts is [G-5].
4. **Expected VFX** come from each device's handler (screen glitch and sound) [I]. The device hack records have no
   completion effects: `DeviceAction.MalfunctionClassHack`, `QuickHackToggleON` and `OverrideAttitudeClassHack` have
   empty startEffects/completionEffects [T].

---

## C. Perk effects

**C.1 Slow-mo (Sandevistan Sprint)** [C, S; snippet `SlowMo`/`SlowMoEnd`, CC]

How the game does it:
- `SandevistanEvents.OnEnter` uses `SetTimeDilationGlobal(…, n"sandevistan", TimeDilationSandevistanTimeScale, 999)`
  (`timeDilationTransitions.swift:187–206`).
- For the `sandevistan` reason, `SetTimeDilationGlobal` calls `SetIgnoreTimeDilationOnLocalPlayerZero(true)` first,
  so the world slows down but V does not (`:78–86`).
- Exit is `UnsetTimeDilation(reason, n"SandevistanEaseOut")` (`:97–112`).

How to do it from our scripts:
- Use `TimeSystem.SetTimeDilation(reason, dilation, duration, easeIn, easeOut)`, `UnsetTimeDilation(reason, easeOut)`
  and `SetIgnoreTimeDilationOnLocalPlayerZero` / `RestorePreviousIgnoreTimeDilationOnLocalPlayerZero` [N]. Use our own
  reason, `n"cyberblockSlowMo"`.
- `TimeDilationHelper` (`timeDilationHelper.swift`) wraps the same calls. It refuses to start while another dilation
  is active unless `allowMultipleTimeDilationSimultaneously` is set.

Caveats:
- The `SandevistanPlayerBuff` record has no VFX [T]. The Sandevistan screen look comes from the PSM camera curve
  `SetCameraTimeDilationCurve(n"Sandevistan")`, which is only available inside the state machine [I, G-6].
- Minecraft does not slow down with it. That has to be done on the Minecraft side.

**C.2 Double jump / air dash on demand** [C, S/T; snippets `GrantStat`, `IsNewPerkBought` wrap, CC]

- **Double jump and charge jump** are pure stat flags (A.2), read fresh on every check. To grant them, remove our
  multiply-by-0 modifier, or add `Additive 1` to `HasDoubleJump` / `HasChargeJump` with
  `RPGManager.CreateStatModifier` (`rpgManager.swift:1387`) and `StatsSystem.AddModifier`.
- **Air dash** needs `HasDodge`, plus perk `Reflexes_Central_Milestone_3` reported at level 3, plus stamina
  (`WantsToDodge`). Dash needs `Reflexes_Central_Milestone_2` at level 2. Stats cannot grant either.
- **The perk wrap.** The compile-checked wrap of `PlayerDevelopmentData.IsNewPerkBought`
  (`playerDevelopmentSystem.swift:1378`, public final const) reports both perks as **not bought** while Active, and
  **bought** while Steve's Dodge perk is on.
  - **Where its state lives:** a non-persistent `ScriptableSystem` field, so nothing is saved.
  - **Scope:** it only changes answers while Cyberblock's god mode is on V.

**C.3 Optical camo / invisibility** [C]

- **Hard invisibility:** `PlayerPuppet.SetInvisible(true)` toggles V's `VisibleObjectComponent`
  (`player.swift:3854–3860`), so NPC senses cannot see V at all.
- **The full camo look and behaviour:** apply `BaseStatusEffect.OpticalCamoPlayerBuffRare` (or another rarity) to V.
  - **What the record contains** [T]: VFX `optical_camo`; a `cloaking_effect` shader on V's equipment and weapon; stat
    `OpticalCamoIsActive` +1; and tags `Cloak`, `CamoActiveOnPlayer`, `ActiveCamo`.
  - **What `OnStatusEffectApplied` does with it** (`player.swift:4335–4364`): in combat it vanishes from hostiles and
    starts camo-charge decay.
  - **Timing:** the duration is built from `OpticalCamoDuration` stats.
- How NPC senses read `OpticalCamoIsActive` is native [I, G-7].

**C.4 Detection and stealth (Silent Steps)** [C]

- **The mechanism:**
  - `PlayerPuppet.UpdateVisibilityModifier` sends `VisibleObjectDetectionMultEvent(multiplier = stat Visibility)`
    (`player.swift:1659–1668`).
  - It runs whenever `Visibility` changes, through `VisibilityStatListener` (`playerListeners.swift:544–555`,
    registered at `player.swift:3612–3615`).
- **The perk:** Silent Steps = `Visibility × 0.5` through `StatsSystem.AddModifier` (snippet `Stealth`, CC).
- **Open question:** whether the multiplier scales detection range or detection speed is native [I, G-8].

---

## D. Damage hook cross-check (Health.reds, `l2/S1` `abcc2b4`)

**What matches 2.31** [C, `damageSystem.swift`]:

1. **Hook signatures** match: `private final func InvulnerabilityCheck(hitEvent: ref<gameHitEvent>, cache:
   ref<CacheData>) -> Void` (`:500`) and `private final func DealDamages(hitEvent: ref<gameHitEvent>) -> Void`
   (`:1285`). Health.reds compiles against 2.31 [CC, with stubs for the S2 classes].
2. **Pipeline order** (`:18–28`, `:260–319`, `:560–582`):
   - `PreProcess`:
     - `CheckForQuickExit`
     - **`InvulnerabilityCheck`**: adds `DealNoDamage`, source `invulnerable`, when the cached immortality mode is
       Invulnerable. Adds it with source `invulnerable stat flag` when `IsInvulnerable` is above 0.
     - `ImmortalityCheck`
     - `DeathCheck`
     - `ModifyHitData`: damage reduction, localized damage, **instant kill**, dodge, evasion, mitigation, incoming
       multiplier, vehicle target.
   - `Process`: returns on any `DealNoDamage`; otherwise runs the modifiers, `ProcessOneShotProtection`, then
     `DealDamages`.
   - `ProcessHitReaction`
   - `PostProcess`: `ProcessStatusEffects`.
3. **Flag handling is correct.** The wrap removes only the `invulnerable` flag that this call itself added. Dodge and
   evasion add their own `DealNoDamage` *after* `InvulnerabilityCheck`, so dodged hits stay unreported. That is
   correct.
4. **Hit reactions.** `GameObject.ReactToHitProcess` returns early for an Invulnerable player
   (`gameObject.swift:1079–1086`). There is no hit UI, animation or camera reaction, which is good for Steve.

**Gaps:**

1. **Damage-over-time never reaches Steve.**
   - **Mechanism:** `PostProcess` → `ProcessStatusEffects` → `IsImmune` returns true for any `Debuff`-tagged status
     effect while V has an Invulnerable god mode (`:1561–1565`).
   - **What it covers:** every DoT is tagged Debuff: `PlayerBurning`, `PlayerBleeding`, `PlayerPoisoned`,
     `PlayerElectrocuted`, `Burning`, `Poisoned`, `OutOfOxygen`, … [T].
   - **Effect:** incendiary, poison and shock hits only report their first hit.
   - **Fix [CC]:** a `@wrapMethod(DamageSystem) IsImmune` that returns false for `DoT`-tagged effects. It only does
     this while Cyberblock is V's **only** Invulnerable source (same test as `CyberblockHealth.Covers`), and it keeps
     `DoNotApplyOnPlayer` and immunity-stat immunity. The ticks are attacks through the same pipeline, so Health.reds
     reports them (`fire` for thermal).
   - **Status:** [G-10].
2. **Instant-kill hits bypass the hook** (`hitFlag.Kill`).
   - **Mechanism:** `ProcessInstantKill` sets V's Health pool to its minimum directly
     (`RequestSettingStatPoolMinValue`, `:439–450`) during `PreProcess`, whatever the god mode, and flags the hit
     `DealNoDamage`.
   - **Effect:** it is never reported to Steve.
   - **Open question:** whether the native stat-pool system honours god mode is [G-9].
3. **Damage that never goes through the pipeline is not reported** [C]. Examples:
   - Overclock's health payment (`quickHackableHelper.swift:261`). It is disabled while Active.
   - The multiplayer-only `ProcessDamageEvents` (`player.swift:5313`).
   - Nothing else in the scripts lowers V's Health directly.
4. **`DealDamages` is skipped entirely, so `SendDamageEvents` never runs.**
   - **What is lost:** `gameTargetDamageEvent` is not sent to the attacker, and `gameDamageReceivedEvent` is not sent
     to V (`:1292–1315`).
   - **Possible effect:** listeners such as combat feedback or achievements will not see these hits [I].
   - **Possible fix:** call `SendDamageEvents` from the wrap with an empty `resourcesLost` if that ever matters.
5. **The death landing.**
   - **What the game does:** a very high fall plays the `DeathLand` locomotion state, and `highLevelTransitions.swift:175–186`
     forces Health to 0 when the player dies.
   - **What Health.reds does:** it reports `Attacks.DeathLanding` (+10000 physical [T]) as `fall`.
   - **Open question:** whether V's own death path is reached under god mode is [G-9].

---

## needsInGame

Each check is concrete. Where possible it uses the CET console (Lua) and a save made just for testing.

- **G-1 Keyboard path.**
  - **Log:** in `input.cpp`, log whether `GetRegisteredRawInputDevices` lists usage page 1 / usage 6 (keyboard).
  - **Test:** with Cyberblock Active, after adding the swallow, press F5 and G. Expect no "Quicksave" notification
    and no walk toggle.
- **G-2 Stat modifiers are not saved.**
  - **Setup:** with Reinforced Tendons equipped, apply `ZeroAbilities` and confirm double jump fails.
  - **Test:** save with the modifiers still on, then reload. `Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.HasDoubleJump)`
    should be 1 again.
- **G-3 Quickhack.**
  - **Target:** look at a ganger. `npc = Game.GetTargetingSystem():GetLookAtObject(Game.GetPlayer(), false, false)`.
  - **Test 1:** `StatusEffectHelper.ApplyStatusEffect(npc, TweakDBID.new("BaseStatusEffect.OverloadLevel1"), Game.GetPlayer():GetEntityID())`.
    Expect electrocution VFX and damage.
  - **Test 2:** repeat with `CyberblockAudit.Quickhack` once it ships. Expect an upload delay, then the same result
    plus NCPD heat.
  - **Also try:** a crowd NPC (are the AI behaviours the same?) and a mech (`quickhack_shortcircuit_mech`).
- **G-4 Device hack.** Look at a vending machine and run `DeviceHack(…, n"QuickHackDistraction")`. Expect the screen to
  glitch and cans to drop. Then a camera with `n"ForceIgnoreTargets"`: the camera's detection cone should stop
  tracking V.
- **G-5 Traffic.** On a light at an intersection, run `FlipIntersection`. Do the lights change, and do cars
  stop and go?
- **G-6 Slow-mo.** `Game.GetTimeSystem():SetIgnoreTimeDilationOnLocalPlayerZero(true); Game.GetTimeSystem():SetTimeDilation("cyberblockSlowMo", 0.2, 5)`.
  NPCs and cars should slow down while V does not. Note whether there is any screen effect.
- **G-7 Camo.** In combat, run `Game.GetPlayer():SetInvisible(true)`. Hostiles should lose V. Repeat with
  `OpticalCamoPlayerBuffRare` and compare.
- **G-8 Visibility.** Add `Visibility ×0.5`, crouch-walk towards an idle guard, and compare the detection meter fill
  time with and without it.
- **G-9 Lethal paths under Cyberblock god mode.**
  - **Fall test:** jump from a megabuilding roof (DeathLand). Expect V to survive and Steve to get `fall` damage with
    a frac of about 4.
  - **Kill test:** find a scripted kill, or apply `BaseStatusEffect.ForceKill` in a throwaway save. Does V die?
- **G-10 DoT wrap.** Let an NPC with an incendiary weapon (or a Burning-applicator) hit V. Expect several `dmg fire`
  log lines from one ignition, and V's Health unchanged.
- **G-11 Camo perks.** With a camo slide or grapple perk owned and `CanUseOpticalCamo ×0` applied: does sliding still
  cloak V?
- **G-12 Superhero landing.** With the Body/Reflexes ground-slam source owned and `HasSuperheroFall ×0` applied: does
  crouch in mid-air still slam?
- **G-13 Radial and vehicle.** After Active → Vehicle (PlayerState clears `NoRadialMenus`), check that the vehicle
  radio wheel works.
- **G-14 Not-savable restrictions.** Apply `GameplayRestriction.NoScanning` with `isSavable = false`, save, then
  reload. Is it gone?

---

## Appendix: compile-checked snippets

Compiled together with main's `cp/scripts/Cyberblock` (2.31 scripts + Codeware): result **OK**. Also compiled together
with `l2/S1`'s scripts (including Health.reds) plus stubs for the S2 classes: result **OK**. A deliberate unknown
method and a deliberate unknown enum member were both rejected, which confirms the checker really checks types. These
snippets are proposals and are not installed. The `@wrapMethod`s change game behaviour and belong in PlayerState or
Health work items.

```swift
// Compile-check only (never installed): snippets proposed in docs/notes/cp-mechanics-audit.md.

public abstract class CyberblockAudit {
  // Gate used by the wraps below: Cyberblock's god mode is on V (same source name PlayerState.reds uses).
  public static func Active(game: GameInstance) -> Bool {
    let player = GetPlayer(game);
    if !IsDefined(player) {
      return false;
    }
    let sources = GameInstance.GetGodModeSystem(game).GetGodModeSources(player.GetEntityID(), gameGodModeType.Invulnerable);
    return ArrayContains(sources, n"Cyberblock");
  }

  // Same test as Health.reds CyberblockHealth.Covers: Cyberblock is V's only Invulnerable source.
  public static func OnlyCyberblock(target: ref<GameObject>) -> Bool {
    let sources = GameInstance.GetGodModeSystem(target.GetGame()).GetGodModeSources(target.GetEntityID(), gameGodModeType.Invulnerable);
    if ArraySize(sources) == 0 {
      return false;
    }
    for source in sources {
      if NotEquals(source, n"Cyberblock") {
        return false;
      }
    }
    return true;
  }

  // A.3 a Cyberpunk menu is open (pause, hub, popups): input forwarding should pause too.
  public static func InMenu(game: GameInstance) -> Bool {
    return GameInstance.GetBlackboardSystem(game).Get(GetAllBlackboardDefs().UI_System).GetBool(GetAllBlackboardDefs().UI_System.IsInMenu);
  }

  // A.1 extra restrictions, runtime-only (isSavable = false), tagged with our instigator.
  public static func ApplyRestriction(player: ref<PlayerPuppet>, id: TweakDBID) -> Void {
    GameInstance.GetStatusEffectSystem(player.GetGame()).ApplyStatusEffect(player.GetEntityID(), id, t"Cyberblock.PlayerState", player.GetEntityID(), 1u, new Vector4(0.0, 0.0, 0.0, 0.0), false);
  }

  // A.2 zero a V ability stat (multiplier 0) and give back the modifier so it can be removed later.
  public static func ZeroStat(player: ref<PlayerPuppet>, stat: gamedataStatType) -> ref<gameStatModifierData> {
    let mod = RPGManager.CreateStatModifier(stat, gameStatModifierType.Multiplier, 0.0);
    GameInstance.GetStatsSystem(player.GetGame()).AddModifier(Cast<StatsObjectID>(player.GetEntityID()), mod);
    return mod;
  }

  // A.2 the V ability flags NoCombat does not zero (2.31 TweakDB: GameplayRestriction.NoCombat zeroes only HasBerserk,
  // HasSandevistan, HasKerenzikov, CanUseOverclock).
  public static func ZeroAbilities(player: ref<PlayerPuppet>) -> array<ref<gameStatModifierData>> {
    let mods: array<ref<gameStatModifierData>>;
    let stats: array<gamedataStatType> = [
      gamedataStatType.HasDoubleJump, gamedataStatType.HasChargeJump, gamedataStatType.HasAirHover,
      gamedataStatType.HasDodge, gamedataStatType.HasDodgeAir, gamedataStatType.HasSuperheroFall,
      gamedataStatType.HasKerenzikovSlide, gamedataStatType.HasSandevistanTier2, gamedataStatType.CanUseOpticalCamo,
      gamedataStatType.CanUseTerrainCamo, gamedataStatType.CanSprintHarass, gamedataStatType.SandevistanDashShoot
    ];
    for stat in stats {
      ArrayPush(mods, CyberblockAudit.ZeroStat(player, stat));
    }
    return mods;
  }

  public static func RemoveStatModifier(player: ref<PlayerPuppet>, mod: ref<gameStatModifierData>) -> Void {
    GameInstance.GetStatsSystem(player.GetGame()).RemoveModifier(Cast<StatsObjectID>(player.GetEntityID()), mod);
  }

  // C.2 grant a movement ability on demand (Additive 1 on a 0..1 flag stat).
  public static func GrantStat(player: ref<PlayerPuppet>, stat: gamedataStatType) -> ref<gameStatModifierData> {
    let mod = RPGManager.CreateStatModifier(stat, gameStatModifierType.Additive, 1.0);
    GameInstance.GetStatsSystem(player.GetGame()).AddModifier(Cast<StatsObjectID>(player.GetEntityID()), mod);
    return mod;
  }

  // B.1 full quickhack, vanilla path (ApplyObjectActionEffector / SpreadAreaEffector): upload, completion effects,
  // NCPD notify, VFX; no RAM cost, no spread.
  public static func Quickhack(player: ref<PlayerPuppet>, target: ref<ScriptedPuppet>, action: TweakDBID) -> Void {
    let hack = new PuppetAction();
    hack.RegisterAsRequester(target.GetEntityID());
    hack.SetExecutor(player);
    hack.SetObjectActionID(action);
    hack.SetUp(target.GetPuppetPS());
    hack.SetCanSkipPayCost(true);
    hack.SetDisableSpread(true);
    hack.ProcessRPGAction(target.GetGame());
  }

  // B.2 instant: just the effect (damage, VFX, SFX and AI reaction live in the status effect record).
  public static func QuickhackEffect(player: ref<PlayerPuppet>, target: ref<GameObject>, effect: TweakDBID) -> Bool {
    return StatusEffectHelper.ApplyStatusEffect(target, effect, player.GetEntityID());
  }

  // B.3 device quickhack by action name (QuickHackDistraction, QuickHackToggleON, ForceIgnoreTargets, ...).
  public static func DeviceHack(player: ref<PlayerPuppet>, device: ref<Device>, actionName: CName) -> Bool {
    let ps = device.GetDevicePS();
    if !IsDefined(ps) {
      return false;
    }
    let context = ps.GenerateContext(gamedeviceRequestType.Remote, Device.GetInteractionClearance(), player, device.GetEntityID());
    let action = ps.GetActionByName(actionName, context) as ScriptableDeviceAction;
    if !IsDefined(action) {
      return false;
    }
    action.RegisterAsRequester(device.GetEntityID());
    action.SetExecutor(player);
    action.SetCanSkipPayCost(true);
    action.ProcessRPGAction(player.GetGame());
    return true;
  }

  // B.4 traffic lights: no player quickhack in 2.31; flip the whole intersection through its manager.
  public static func FlipIntersection(light: ref<Device>) -> Bool {
    let parents: array<ref<DeviceComponentPS>>;
    light.GetDevicePS().GetParents(parents);
    for parent in parents {
      let manager = parent as TrafficIntersectionManagerControllerPS;
      if IsDefined(manager) {
        manager.HandleLightChangeRequest();
        return true;
      }
    }
    return false;
  }

  // C.1 slow-mo for the world, V at normal speed (as SandevistanEvents does), and the restore.
  public static func SlowMo(game: GameInstance, scale: Float) -> Void {
    let time = GameInstance.GetTimeSystem(game);
    time.SetIgnoreTimeDilationOnLocalPlayerZero(true);
    time.SetTimeDilation(n"cyberblockSlowMo", scale, 999.0);
  }

  public static func SlowMoEnd(game: GameInstance) -> Void {
    let time = GameInstance.GetTimeSystem(game);
    time.UnsetTimeDilation(n"cyberblockSlowMo", n"SandevistanEaseOut");
    time.SetIgnoreTimeDilationOnLocalPlayerZero(false);
  }

  // C.3 hard stealth: NPC senses stop seeing V at all (PlayerPuppet.SetInvisible toggles the VisibleObjectComponent).
  public static func Invisible(player: ref<PlayerPuppet>, on: Bool) -> Void {
    player.SetInvisible(on);
  }

  // C.3 optical camo on V (buff record carries the cloak shader, VFX, OpticalCamoIsActive and the Cloak tags).
  public static func Camo(player: ref<PlayerPuppet>, on: Bool) -> Void {
    if on {
      StatusEffectHelper.ApplyStatusEffect(player, t"BaseStatusEffect.OpticalCamoPlayerBuffRare", player.GetEntityID());
    } else {
      StatusEffectHelper.RemoveStatusEffect(player, t"BaseStatusEffect.OpticalCamoPlayerBuffRare");
    }
  }

  // C.4 Silent Steps: NPC detection multiplier (PlayerPuppet.UpdateVisibilityModifier reads Visibility).
  public static func Stealth(player: ref<PlayerPuppet>) -> ref<gameStatModifierData> {
    let mod = RPGManager.CreateStatModifier(gamedataStatType.Visibility, gameStatModifierType.Multiplier, 0.5);
    GameInstance.GetStatsSystem(player.GetGame()).AddModifier(Cast<StatsObjectID>(player.GetEntityID()), mod);
    return mod;
  }
}

// A: F5 must not quicksave Cyberpunk while it toggles Minecraft's camera.
@wrapMethod(gameuiInGameMenuGameController)
private final func HandleQuickSave() -> Void {
  if CyberblockAudit.Active(this.GetPlayerControlledObject().GetGame()) {
    return;
  }
  wrappedMethod();
}

// A: G (future hack key) is Cyberpunk's ToggleWalk.
@wrapMethod(PlayerPuppet)
private final func ProcessToggleWalkInput() -> Void {
  if CyberblockAudit.Active(this.GetGame()) {
    return;
  }
  wrappedMethod();
}

// C.2: V's own perk-gated dash (Reflexes_Central_Milestone_2 == 2) and air dash (_Milestone_3 == 3) are reported
// as not bought while Active, and as bought while Steve's Dodge perk is on.
public class CyberblockAuditPerks extends ScriptableSystem {
  public let dodge: Bool;

  public static func Get(game: GameInstance) -> ref<CyberblockAuditPerks> {
    return GameInstance.GetScriptableSystemsContainer(game).Get(n"CyberblockAuditPerks") as CyberblockAuditPerks;
  }
}

@wrapMethod(PlayerDevelopmentData)
public final const func IsNewPerkBought(perkType: gamedataNewPerkType) -> Int32 {
  let level = wrappedMethod(perkType);
  if !IsDefined(this.m_owner) || !CyberblockAudit.Active(this.m_owner.GetGame()) {
    return level;
  }
  let dodge = CyberblockAuditPerks.Get(this.m_owner.GetGame()).dodge;
  if Equals(perkType, gamedataNewPerkType.Reflexes_Central_Milestone_2) {
    return dodge ? 2 : 0;
  }
  if Equals(perkType, gamedataNewPerkType.Reflexes_Central_Milestone_3) {
    return dodge ? 3 : 0;
  }
  return level;
}

// D: let damage-over-time debuffs land on V while only Cyberblock's god mode protects V, so their ticks reach Steve
// through the Health.reds report. Every other immunity (DoNotApplyOnPlayer, immunity stats, other god modes) stays.
@wrapMethod(DamageSystem)
private final func IsImmune(target: ref<GameObject>, statusEffectID: TweakDBID, attackData: ref<AttackData>) -> Bool {
  let immune = wrappedMethod(target, statusEffectID, attackData);
  if !immune || !target.IsPlayer() || !CyberblockAudit.OnlyCyberblock(target) {
    return immune;
  }
  let record = TweakDBInterface.GetStatusEffectRecord(statusEffectID);
  if !IsDefined(record) || !record.GameplayTagsContains(n"DoT") || record.GameplayTagsContains(n"DoNotApplyOnPlayer") {
    return immune;
  }
  let stats: array<wref<Stat_Record>>;
  record.ImmunityStats(stats);
  for stat in stats {
    if GameInstance.GetStatsSystem(target.GetGame()).GetStatValue(Cast<StatsObjectID>(target.GetEntityID()), stat.StatType()) > 0.0 {
      return immune;
    }
  }
  return false;
}
```
