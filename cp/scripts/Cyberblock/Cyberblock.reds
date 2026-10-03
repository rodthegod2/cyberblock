// Cyberblock mashup state machine. F9 is caught by Cyberblock.dll (Cyberblock_ConsumeToggle), so no input
// mapping mod is needed. A 10 Hz DelaySystem tick drives every transition.
//
// 2.31 APIs used (checked against the decompiled 2.31 scripts and compiled with the game's scc):
//   ScriptableSystem.OnAttach/OnDetach/GetGameInstance, DelaySystem.DelayCallback/CancelCallback, DelayCallback.Call,
//   GetPlayer(GameInstance), VehicleComponent.IsMountedToVehicle(GameInstance, wref<GameObject>),
//   UI_Notifications.WarningMessage + SimpleScreenMessage (as in PreventionSystem.ShowMessage).
//   Codeware 1.20 CallbackSystem.RegisterCallback(CName, ref<IScriptable>, CName) with the session events
//   Session/BeforeSave, Session/AfterSave, Session/Ready, Session/BeforeEnd (GameSessionEvent); callbacks
//   registered from a ScriptableSystem are removed by Codeware at the end of the session.
//   UI_System.IsInMenu (blackboardDefinitions.swift:702) through IBlackboard.GetBool/RegisterListenerBool/
//   UnregisterListenerBool. PlayerPuppet.GetPlayerStateMachineBlackboard + PlayerStateMachine.Locomotion
//   (gamePSMLocomotionStates; player.swift:1561-1577).
//
// Layer 2 wiring (plan section 1.4 and track S1): every tick drains the Minecraft event queue (Events.reds; live
// only while Active/Vehicle and not saving), keeps CyberblockCollision boxes streamed around V while live, and sends
// the NPC/vehicle list while Active (Npcs.reds). Collision boxes are cleared whenever Night City stops being live
// (Off, Lost), before every save, on every load and at session end. The health link (Health.reds) needs no call:
// it acts exactly while our god mode is V's only invulnerability, i.e. while Active. CyberblockCreative (Health.reds)
// keeps V invulnerable in the Vehicle state while Minecraft is in creative mode (Cyberblock_GameMode).
//
// Layers 3-6 wiring (plan 2026-10-03 S1.6, section 1.4): every tick with a player also runs CyberblockPuppets,
// CyberblockFlight, CyberblockTether and CyberblockProjectiles (live: Active or Vehicle, not saving) and
// CyberblockPerks and CyberblockBuffs (active: Active, not saving), reports whether V is crouched
// (Cyberblock_SetStance), whether a Cyberpunk menu is open (Cyberblock_SetMenu) and whether a dialogue offers two or more
// choices while Active (Cyberblock_SetChoices: E, Q, 1-4 and the wheel then stay Cyberpunk's). ClearWorld (every section 1.4
// Clear plus CyberblockCollision.Clear) runs before every save, on session ready, at session end and on every
// transition out of Active/Vehicle.
//
// While a save is written (Session/BeforeSave until Session/AfterSave) Minecraft's side is left as it is: its events
// wait in the DLL's queue, the last NPC list stands (its proxies keep their leads) and the tethers stay (ClearWorld only
// restarted their timers); all of it carries on after the save. The flag is also cleared on session ready and end, and
// after 10 s of ticks without an AfterSave (a save that failed or was aborted), so it can never stay stuck.

enum CyberblockState {
  Off = 0,
  Active = 1,
  Vehicle = 2,
  Lost = 3
}

public class CyberblockTick extends DelayCallback {
  public let system: wref<CyberblockSystem>;

  public func Call() -> Void {
    if IsDefined(this.system) {
      this.system.Tick();
    }
  }
}

public class CyberblockSystem extends ScriptableSystem {
  private let state: CyberblockState;
  private let tickId: DelayID;
  private let playerState: ref<CyberblockPlayerState>;
  private let creative: ref<CyberblockCreative>;
  private let probes: ref<CyberblockProbes>;
  private let events: ref<CyberblockEvents>;
  private let npcs: ref<CyberblockNpcs>;
  private let saving: Bool;
  private let savingTicks: Int32;
  private let menuListener: ref<CallbackHandle>;

  private func OnAttach() -> Void {
    this.state = CyberblockState.Off;
    this.playerState = new CyberblockPlayerState();
    this.creative = new CyberblockCreative();
    this.probes = new CyberblockProbes();
    this.events = new CyberblockEvents();
    this.npcs = new CyberblockNpcs();
    this.npcs.Register();
    Cyberblock_SetActive(false, false);
    // A menu can pause the game (and with it this tick), so its opening is also taken straight from the blackboard.
    let ui = GameInstance.GetBlackboardSystem(this.GetGameInstance()).Get(GetAllBlackboardDefs().UI_System);
    if IsDefined(ui) {
      this.menuListener = ui.RegisterListenerBool(GetAllBlackboardDefs().UI_System.IsInMenu, this, n"OnMenuChanged");
    }
    let callbacks = GameInstance.GetCallbackSystem();
    callbacks.RegisterCallback(n"Session/BeforeSave", this, n"OnBeforeSave");
    callbacks.RegisterCallback(n"Session/AfterSave", this, n"OnAfterSave");
    callbacks.RegisterCallback(n"Session/Ready", this, n"OnSessionReady");
    callbacks.RegisterCallback(n"Session/BeforeEnd", this, n"OnSessionEnd");
    this.ScheduleTick();
  }

  private func OnDetach() -> Void {
    GameInstance.GetDelaySystem(this.GetGameInstance()).CancelCallback(this.tickId);
    let ui = GameInstance.GetBlackboardSystem(this.GetGameInstance()).Get(GetAllBlackboardDefs().UI_System);
    if IsDefined(ui) && IsDefined(this.menuListener) {
      ui.UnregisterListenerBool(GetAllBlackboardDefs().UI_System.IsInMenu, this.menuListener);
    }
    Cyberblock_SetMenu(false);
    Cyberblock_SetChoices(false);
    this.playerState.ShowHud();
    CyberblockSystem.ClearWorld(this.GetGameInstance());
    this.state = CyberblockState.Off;
    Cyberblock_SetActive(false, false);
  }

  // Saves stay vanilla: status effects, stat modifiers, god modes, collision boxes, puppets, tethers, perk and potion
  // effects are removed before the save; after it V's state comes back if still Active and the rest on the next live
  // tick (the HUD layer is not saved). Ticks leave V and Night City alone while the save is written.
  private cb func OnBeforeSave(event: ref<GameSessionEvent>) {
    this.saving = true;
    this.savingTicks = 0;
    this.playerState.ClearSaved(GetPlayer(this.GetGameInstance()));
    this.creative.Clear(GetPlayer(this.GetGameInstance()));
    CyberblockSystem.ClearWorld(this.GetGameInstance());
    Cyberblock_Log("before save: effects, god modes and world objects removed");
  }

  private cb func OnAfterSave(event: ref<GameSessionEvent>) {
    this.EndSave("after save");
  }

  private func EndSave(why: String) -> Void {
    this.saving = false;
    this.UpdatePlayer(GetPlayer(this.GetGameInstance()));
    Cyberblock_Log(s"\(why): state \(CyberblockSystem.Name(this.state))");
  }

  // On every load (and new game): remove anything of ours the save may carry and start Off.
  private cb func OnSessionReady(event: ref<GameSessionEvent>) {
    this.saving = false;
    let player = GetPlayer(this.GetGameInstance());
    this.playerState.Clear(player);
    this.creative.Clear(player);
    if IsDefined(player) {
      CyberblockPlayerState.ClearLeftovers(player);
      CyberblockCreative.ClearLeftovers(player);
    }
    CyberblockSystem.ClearWorld(this.GetGameInstance());
    this.state = CyberblockState.Off;
    Cyberblock_SetActive(false, false);
    Cyberblock_Log("session ready: leftovers removed, Off");
  }

  private cb func OnSessionEnd(event: ref<GameSessionEvent>) {
    this.saving = false;
    this.playerState.Clear(GetPlayer(this.GetGameInstance()));
    this.creative.Clear(GetPlayer(this.GetGameInstance()));
    CyberblockSystem.ClearWorld(this.GetGameInstance());
    this.state = CyberblockState.Off;
    Cyberblock_SetActive(false, false);
    Cyberblock_Log("session end: V restored, world objects removed, Off");
  }

  private func UpdatePlayer(player: ref<PlayerPuppet>) -> Void {
    if IsDefined(player) && !this.saving {
      this.playerState.Update(player, Equals(this.state, CyberblockState.Active), Cyberblock_ScreenOpen());
      this.creative.Update(player, Equals(this.state, CyberblockState.Vehicle));
    }
  }

  private func ScheduleTick() -> Void {
    let tick = new CyberblockTick();
    tick.system = this;
    this.tickId = GameInstance.GetDelaySystem(this.GetGameInstance()).DelayCallback(tick, 0.1, false);
  }

  public func Tick() -> Void {
    this.ScheduleTick();
    // Consume F9 even without a player, so a press in the main menu cannot fire later.
    let toggle = Cyberblock_ConsumeToggle();
    let game = this.GetGameInstance();
    Cyberblock_SetMenu(CyberblockDisable.InMenu(game));
    if this.saving {
      this.savingTicks += 1;
      if this.savingTicks >= 100 {
        this.EndSave("save: no AfterSave within 10 s, carrying on");
      }
    }
    let player = GetPlayer(game);
    if !IsDefined(player) {
      Cyberblock_SetChoices(false);
      // No world to act on: Minecraft block changes still update the collision registry, the other events are dropped.
      this.events.Tick(game, false);
      return;
    }
    this.Step(player, toggle);
    // A dialogue or choice list with two or more choices keeps E, Q, 1-4 and the wheel for Cyberpunk (the DLL).
    Cyberblock_SetChoices(Equals(this.state, CyberblockState.Active) && CyberblockSystem.ChoiceCount(game) >= 2);
    this.UpdatePlayer(player);
    let on = CyberblockSystem.IsOn(this.state);
    if on {
      this.probes.Tick(player);
    }
    let live = on && !this.saving;
    let active = Equals(this.state, CyberblockState.Active) && !this.saving;
    Cyberblock_SetStance(CyberblockSystem.Crouched(player));
    if !this.saving {
      this.events.Tick(game, live);
    }
    if live {
      CyberblockCollision.Stream(game, player.GetWorldPosition());
    }
    CyberblockPuppets.Tick(game, player, live);
    CyberblockFlight.Tick(game, player, live);
    if !this.saving {
      CyberblockTether.Tick(game, player, live);
    }
    CyberblockProjectiles.Tick(game, player, live);
    CyberblockPerks.Tick(game, player, active);
    CyberblockBuffs.Tick(game, player, active);
    if !this.saving {
      this.npcs.Tick(player, active);
    }
  }

  // Everything of ours in Night City (plan 2026-10-03 section 1.4 Clears). Perk ranks and the collision registry stay.
  private static func ClearWorld(game: GameInstance) -> Void {
    CyberblockCollision.Clear(game);
    CyberblockPuppets.Clear(game);
    CyberblockFlight.Clear(game);
    CyberblockTether.Clear(game);
    CyberblockPerks.Clear(game);
    CyberblockBuffs.Clear(game);
  }

  // Crouched as the game's own crouch indicator counts it: Crouch, CrouchDodge or Slide
  // (CrouchIndicatorGameController.OnPSMLocomotionStateChanged, crouchIndicator.swift).
  private static func Crouched(player: ref<PlayerPuppet>) -> Bool {
    let psm = player.GetPlayerStateMachineBlackboard();
    if !IsDefined(psm) {
      return false;
    }
    let state = IntEnum<gamePSMLocomotionStates>(psm.GetInt(GetAllBlackboardDefs().PlayerStateMachine.Locomotion));
    return Equals(state, gamePSMLocomotionStates.Crouch) || Equals(state, gamePSMLocomotionStates.CrouchDodge) || Equals(state, gamePSMLocomotionStates.Slide);
  }

  // The choices on screen: every choice of every dialogue choice hub (UIInteractions.DialogChoiceHubs, as
  // DefaultTransition.AreChoiceHubsActive reads it, defaultTransition.swift:1586; DialogChoiceHubs.choiceHubs and
  // ListChoiceHubData.choices, orphans.swift).
  private static func ChoiceCount(game: GameInstance) -> Int32 {
    let interactions = GameInstance.GetBlackboardSystem(game).Get(GetAllBlackboardDefs().UIInteractions);
    if !IsDefined(interactions) {
      return 0;
    }
    let hubs = FromVariant<DialogChoiceHubs>(interactions.GetVariant(GetAllBlackboardDefs().UIInteractions.DialogChoiceHubs));
    let count = 0;
    for hub in hubs.choiceHubs {
      count += ArraySize(hub.choices);
    }
    return count;
  }

  private cb func OnMenuChanged(inMenu: Bool) -> Bool {
    Cyberblock_SetMenu(inMenu);
  }

  // Active or Vehicle: Minecraft is shown and acts on Night City.
  private static func IsOn(state: CyberblockState) -> Bool {
    return Equals(state, CyberblockState.Active) || Equals(state, CyberblockState.Vehicle);
  }

  private func Step(player: ref<PlayerPuppet>, toggle: Bool) -> Void {
    let mounted = VehicleComponent.IsMountedToVehicle(this.GetGameInstance(), player);
    let linked = Cyberblock_IsLinked();
    // Without the link the DLL cannot send cam, so Minecraft frames would go stale: treat it as lost too.
    let fresh = linked && Cyberblock_SecondsSinceFrame() <= 2.0;
    let on = mounted ? CyberblockState.Vehicle : CyberblockState.Active;

    if toggle {
      if NotEquals(this.state, CyberblockState.Off) {
        this.Enter(CyberblockState.Off, mounted, "Cyberblock off");
      } else {
        if linked {
          this.Enter(on, mounted, "Cyberblock on");
        } else {
          this.Notify("Start Minecraft first");
        }
      }
      return;
    }

    if Equals(this.state, CyberblockState.Active) || Equals(this.state, CyberblockState.Vehicle) {
      if !fresh {
        this.Enter(CyberblockState.Lost, mounted, "Minecraft lost — V restored");
      } else {
        if NotEquals(this.state, on) {
          this.Enter(on, mounted, mounted ? "Cyberblock: in vehicle" : "Cyberblock: on foot");
        }
      }
    } else {
      if Equals(this.state, CyberblockState.Lost) && fresh {
        this.Enter(on, mounted, "Minecraft back — Cyberblock on");
      }
    }
  }

  private func Enter(next: CyberblockState, mounted: Bool, message: String) -> Void {
    Cyberblock_Log(s"state \(CyberblockSystem.Name(this.state)) -> \(CyberblockSystem.Name(next))");
    let wasOn = CyberblockSystem.IsOn(this.state);
    this.state = next;
    let active = CyberblockSystem.IsOn(next);
    if wasOn && !active {
      CyberblockSystem.ClearWorld(this.GetGameInstance());
    }
    Cyberblock_SetActive(active, mounted);
    this.Notify(message);
  }

  private func Notify(message: String) -> Void {
    let msg: SimpleScreenMessage;
    msg.isShown = true;
    msg.duration = 2.0;
    msg.message = message;
    GameInstance.GetBlackboardSystem(this.GetGameInstance())
      .Get(GetAllBlackboardDefs().UI_Notifications)
      .SetVariant(GetAllBlackboardDefs().UI_Notifications.WarningMessage, ToVariant(msg), true);
  }

  private static func Name(state: CyberblockState) -> String {
    switch state {
      case CyberblockState.Active:
        return "Active";
      case CyberblockState.Vehicle:
        return "Vehicle";
      case CyberblockState.Lost:
        return "Lost";
      default:
        return "Off";
    }
  }
}
