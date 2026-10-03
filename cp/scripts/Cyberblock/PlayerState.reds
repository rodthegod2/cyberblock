// V's state while Cyberblock is Active (on foot): weapons holstered, combat, quickhacks and phone blocked, god
// mode, Cyberpunk HUD hidden, camera frozen while a Minecraft screen is open. Everything is runtime-only and is
// removed before every save and on every load (see CyberblockSystem in Cyberblock.reds).
//
// DISABLE list (plan 2026-10-03 S1.2, docs/notes/cp-mechanics-audit.md A.2 and A.4): also the healing hotkey, the
// scanner (and its world slow-down), the radial/weapon wheel (and its freeze), quick melee, zoom and body pickup are
// blocked by restrictions, and the movement abilities NoCombat leaves on (double/charge jump, hover, dodge and air
// dodge, superhero landing, Kerenzikov slide, Sandevistan tier 2, camo, sprint harass, dash shoot) are zeroed with our
// own runtime stat modifiers. Steve's perks keep two of them (CyberblockPerks, S3, re-read every tick): double_jump
// keeps HasDoubleJump, dodge keeps HasDodge and HasDodgeAir. F5, G and V's perk-gated dash are in Disable.reds.
//
// 2.31 sources:
//   Status effect records and their gameplayTags, read from r6/cache/tweakdb.bin (all six extra ones also exist in
//   tweakdb_ep1.bin and have no stat packages):
//     GameplayRestriction.NoCombat              -> NoCombat, NoQuickHacks, NoGrenadeOrGadget
//     GameplayRestriction.NoPhone               -> NoPhone
//     GameplayRestriction.NoCameraControl       -> NoCameraControl
//     GameplayRestriction.NoHealing             -> NoInhalerOrInjector (IsUsingConsumableRestricted)
//     GameplayRestriction.NoScanning            -> NoScanning (PlayerVisionModeController.UpdateNoScanningRestriction)
//     GameplayRestriction.NoRadialMenus         -> NoRadialMenus, VehicleBlockRadioInput (WeaponWheelDecisions;
//                                                  the radio tag is harmless: this state only exists on foot)
//     GameplayRestriction.NoQuickMelee          -> NoQuickMelee
//     GameplayRestriction.NoZooming             -> NoZooming
//     GameplayRestriction.BodyCarryingForceDrop -> BodyCarryingForceDrop (excluded by GenericInteraction.PickUpBody)
//   There is no GameplayRestriction.NoQuickHacks record; NoCombat carries the NoQuickHacks tag that
//   QuickhackModule.IsQuickhackBlockedByScene checks.
//   Ability stats: gamedataStatType members as in the audit appendix ZeroAbilities (compile-checked); modifiers from
//   RPGManager.CreateStatModifier(stat, gameStatModifierType.Multiplier, 0.0), StatsSystem.AddModifier/RemoveModifier
//   (StatsObjectID, ref<gameStatModifierData>) on the same object. AddModifier is the non-saved variant (NativeDB lists
//   AddSavedModifier separately); they are removed before saves anyway (audit G-2).
//   StatusEffectSystem.ApplyStatusEffect(EntityID, TweakDBID, opt instigatorID: TweakDBID, ...),
//   StatusEffectSystem.RemoveStatusEffect, StatusEffectSystem.ObjectHasStatusEffect,
//   StatusEffectHelper.RemoveStatusEffectsByInstigatorID, GodModeSystem.AddGodMode/ClearGodMode,
//   EquipmentSystemWeaponManipulationRequest + EquipmentManipulationAction.UnequipAll (as QuickSlotsManager.HideWeapon).
//   Codeware: GameInstance.GetInkSystem().GetLayer(n"inkHUDLayer").GetVirtualWindow() (Codeware wiki, ink layers).

public class CyberblockPlayerState {
  // Restrictions we applied. One the game already had is left alone and never removed by us.
  private let own: array<TweakDBID>;
  private let applied: Bool;
  private let hudHidden: Bool;
  private let hudWasVisible: Bool;
  private let hudDelay: Int32;
  // V's mesh components we switched off (first-person arms and body would otherwise show over Steve).
  private let hiddenMeshes: array<wref<IComponent>>;
  // Ticks until the next body check (every 2 s: toggling meshes rebuilds their render proxies on the GPU).
  private let bodyCheckDelay: Int32;
  // Ability stats we zeroed and the modifier on each (parallel arrays); exactly these are removed again.
  private let zeroedStats: array<gamedataStatType>;
  private let zeroMods: array<ref<gameStatModifierData>>;

  // Marks our status effects so a load can remove leftovers without touching the game's own restrictions.
  public static func Instigator() -> TweakDBID {
    return t"Cyberblock.PlayerState";
  }

  // Called every tick (except while a save is written). active: Cyberblock state is Active (on foot).
  public func Update(player: ref<PlayerPuppet>, active: Bool, screenOpen: Bool) -> Void {
    if !active {
      this.Clear(player);
      return;
    }
    if !this.applied {
      this.Holster(player);
      GameInstance.GetGodModeSystem(player.GetGame()).AddGodMode(player.GetEntityID(), gameGodModeType.Invulnerable, n"Cyberblock");
      this.applied = true;
      if !this.hudHidden {
        this.hudDelay = 20; // 2 s at 10 Hz, so the transition notification is read before the HUD goes away
      }
    }
    // Re-checked every tick so a restriction the game removes in the meantime comes back, and so meshes the game
    // adds later (equipping clothes or cyberware) are hidden too.
    this.HideBody(player);
    this.Own(player, t"GameplayRestriction.NoCombat");
    this.Own(player, t"GameplayRestriction.NoPhone");
    this.Own(player, t"GameplayRestriction.NoHealing");
    this.Own(player, t"GameplayRestriction.NoScanning");
    this.Own(player, t"GameplayRestriction.NoRadialMenus");
    this.Own(player, t"GameplayRestriction.NoQuickMelee");
    this.Own(player, t"GameplayRestriction.NoZooming");
    this.Own(player, t"GameplayRestriction.BodyCarryingForceDrop");
    this.ZeroAbilities(player);
    if screenOpen {
      this.Own(player, t"GameplayRestriction.NoCameraControl");
    } else {
      this.Release(player, t"GameplayRestriction.NoCameraControl");
    }
    if this.hudDelay > 0 {
      this.hudDelay -= 1;
    } else {
      this.HideHud();
    }
  }

  // Removes everything this object applied. Cheap when nothing is applied.
  public func Clear(player: ref<PlayerPuppet>) -> Void {
    this.ShowHud();
    this.ClearSaved(player);
  }

  // Removes what a save would carry (status effects, god mode). The HUD layer is not saved, so it stays hidden.
  public func ClearSaved(player: ref<PlayerPuppet>) -> Void {
    this.ShowBody();
    if !IsDefined(player) {
      return;
    }
    while ArraySize(this.own) > 0 {
      GameInstance.GetStatusEffectSystem(player.GetGame()).RemoveStatusEffect(player.GetEntityID(), ArrayPop(this.own));
    }
    while ArraySize(this.zeroedStats) > 0 {
      this.Unzero(player, 0);
    }
    if this.applied {
      GameInstance.GetGodModeSystem(player.GetGame()).ClearGodMode(player.GetEntityID(), n"Cyberblock");
      this.applied = false;
    }
  }

  // On load: removes anything of ours that a save may still carry.
  public static func ClearLeftovers(player: ref<PlayerPuppet>) -> Void {
    StatusEffectHelper.RemoveStatusEffectsByInstigatorID(player, CyberblockPlayerState.Instigator());
    GameInstance.GetGodModeSystem(player.GetGame()).ClearGodMode(player.GetEntityID(), n"Cyberblock");
  }

  public func ShowHud() -> Void {
    if !this.hudHidden {
      return;
    }
    let hud = this.HudWindow();
    if IsDefined(hud) {
      hud.SetVisible(this.hudWasVisible);
    }
    this.hudHidden = false;
  }

  private func HideHud() -> Void {
    let hud = this.HudWindow();
    if !IsDefined(hud) {
      return;
    }
    if !this.hudHidden {
      this.hudWasVisible = hud.IsVisible();
      this.hudHidden = true;
    }
    // Re-asserted every tick in case the game shows the layer again (e.g. after photo mode).
    if hud.IsVisible() {
      hud.SetVisible(false);
    }
  }

  private func HudWindow() -> wref<inkVirtualWindow> {
    let layer = GameInstance.GetInkSystem().GetLayer(n"inkHUDLayer");
    if IsDefined(layer) {
      return layer.GetVirtualWindow();
    }
    return null;
  }

  private func Own(player: ref<PlayerPuppet>, id: TweakDBID) -> Void {
    if StatusEffectSystem.ObjectHasStatusEffect(player, id) {
      return;
    }
    GameInstance.GetStatusEffectSystem(player.GetGame()).ApplyStatusEffect(player.GetEntityID(), id, CyberblockPlayerState.Instigator());
    if !ArrayContains(this.own, id) {
      ArrayPush(this.own, id);
    }
  }

  private func Release(player: ref<PlayerPuppet>, id: TweakDBID) -> Void {
    if ArrayContains(this.own, id) {
      GameInstance.GetStatusEffectSystem(player.GetGame()).RemoveStatusEffect(player.GetEntityID(), id);
      ArrayRemove(this.own, id);
    }
  }

  // Audit A.4: the V abilities GameplayRestriction.NoCombat does not zero (it zeroes only HasBerserk, HasSandevistan,
  // HasKerenzikov and CanUseOverclock), minus CanUseStrongArms (Gorilla Arms opening doors is not combat).
  private static func AbilityStats() -> array<gamedataStatType> {
    return [
      gamedataStatType.HasDoubleJump, gamedataStatType.HasChargeJump, gamedataStatType.HasAirHover,
      gamedataStatType.HasDodge, gamedataStatType.HasDodgeAir, gamedataStatType.HasSuperheroFall,
      gamedataStatType.HasKerenzikovSlide, gamedataStatType.HasSandevistanTier2, gamedataStatType.CanUseOpticalCamo,
      gamedataStatType.CanUseTerrainCamo, gamedataStatType.CanSprintHarass, gamedataStatType.SandevistanDashShoot
    ];
  }

  // Zeroes every ability stat except the ones Steve's perks grant right now; a perk bought or lost while Active takes
  // effect on the next tick.
  private func ZeroAbilities(player: ref<PlayerPuppet>) -> Void {
    let game = player.GetGame();
    let doubleJump = CyberblockPerks.Has(game, n"double_jump");
    let dodge = CyberblockPerks.Has(game, n"dodge");
    for stat in CyberblockPlayerState.AbilityStats() {
      let kept = (doubleJump && Equals(stat, gamedataStatType.HasDoubleJump))
        || (dodge && (Equals(stat, gamedataStatType.HasDodge) || Equals(stat, gamedataStatType.HasDodgeAir)));
      let i = ArrayFindFirst(this.zeroedStats, stat);
      if kept && i >= 0 {
        this.Unzero(player, i);
      } else {
        if !kept && i < 0 {
          let mod = RPGManager.CreateStatModifier(stat, gameStatModifierType.Multiplier, 0.0);
          GameInstance.GetStatsSystem(game).AddModifier(Cast<StatsObjectID>(player.GetEntityID()), mod);
          ArrayPush(this.zeroedStats, stat);
          ArrayPush(this.zeroMods, mod);
        }
      }
    }
  }

  private func Unzero(player: ref<PlayerPuppet>, i: Int32) -> Void {
    GameInstance.GetStatsSystem(player.GetGame()).RemoveModifier(Cast<StatsObjectID>(player.GetEntityID()), this.zeroMods[i]);
    ArrayErase(this.zeroedStats, i);
    ArrayErase(this.zeroMods, i);
  }

  // Switches off every enabled mesh component on V (Codeware Entity.GetComponents; IComponent.Toggle/IsEnabled)
  // and remembers exactly those, so ShowBody restores V as she or he was.
  // A mesh that is enabled again after we hid it means the game re-enabled it: count it, log it, and never track it
  // twice, so a game that keeps re-enabling meshes cannot grow the list or churn GPU resources more than every 2 s.
  private func HideBody(player: ref<PlayerPuppet>) -> Void {
    if this.bodyCheckDelay > 0 {
      this.bodyCheckDelay -= 1;
      return;
    }
    this.bodyCheckDelay = 20;
    let hidden = 0;
    let reenabled = 0;
    for component in player.GetComponents() {
      if (component.IsA(n"entSkinnedMeshComponent") || component.IsA(n"entMeshComponent")) && component.IsEnabled() {
        component.Toggle(false);
        if ArrayContains(this.hiddenMeshes, component) {
          reenabled += 1;
        } else {
          ArrayPush(this.hiddenMeshes, component);
          hidden += 1;
        }
      }
    }
    if hidden > 0 || reenabled > 0 {
      Cyberblock_Log(s"body: hid \(hidden) new meshes, \(reenabled) re-enabled by the game, tracking \(ArraySize(this.hiddenMeshes))");
    }
  }

  private func ShowBody() -> Void {
    this.bodyCheckDelay = 0;
    for component in this.hiddenMeshes {
      if IsDefined(component) {
        component.Toggle(true);
      }
    }
    ArrayClear(this.hiddenMeshes);
  }

  private func Holster(player: ref<PlayerPuppet>) -> Void {
    let request = new EquipmentSystemWeaponManipulationRequest();
    request.owner = player;
    request.requestType = EquipmentManipulationAction.UnequipAll;
    GameInstance.GetScriptableSystemsContainer(player.GetGame()).Get(n"EquipmentSystem").QueueRequest(request);
  }
}
