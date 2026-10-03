// V's state while Cyberblock is Active (on foot): weapons holstered, combat, quickhacks and phone blocked, god
// mode, Cyberpunk HUD hidden, camera frozen while a Minecraft screen is open. Everything is runtime-only and is
// removed before every save and on every load (see CyberblockSystem in Cyberblock.reds).
//
// 2.31 sources:
//   Status effect records and their gameplayTags, read from r6/cache/tweakdb.bin:
//     GameplayRestriction.NoCombat        -> NoCombat, NoQuickHacks, NoGrenadeOrGadget
//     GameplayRestriction.NoPhone         -> NoPhone
//     GameplayRestriction.NoCameraControl -> NoCameraControl
//   There is no GameplayRestriction.NoQuickHacks record; NoCombat carries the NoQuickHacks tag that
//   QuickhackModule.IsQuickhackBlockedByScene checks.
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
