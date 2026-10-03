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
//
// Layer 2 wiring (plan section 1.4 and track S1): every tick drains the Minecraft event queue (Events.reds; live
// only while Active/Vehicle and not saving), keeps CyberblockCollision boxes streamed around V while live, and sends
// the NPC/vehicle list while Active (Npcs.reds). Collision boxes are cleared whenever Night City stops being live
// (Off, Lost), before every save, on every load and at session end. The health link (Health.reds) needs no call:
// it acts exactly while our god mode is V's only invulnerability, i.e. while Active. CyberblockCreative (Health.reds)
// keeps V invulnerable in the Vehicle state while Minecraft is in creative mode (Cyberblock_GameMode).

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
    this.playerState.ShowHud();
    CyberblockCollision.Clear(this.GetGameInstance());
    this.state = CyberblockState.Off;
    Cyberblock_SetActive(false, false);
  }

  // Saves stay vanilla: status effects, god mode and collision boxes are removed before the save; after it the
  // effects come back if still Active and the boxes on the next live tick (the HUD layer is not saved). Ticks leave
  // V and Night City alone while the save is written.
  private cb func OnBeforeSave(event: ref<GameSessionEvent>) {
    this.saving = true;
    this.playerState.ClearSaved(GetPlayer(this.GetGameInstance()));
    this.creative.Clear(GetPlayer(this.GetGameInstance()));
    CyberblockCollision.Clear(this.GetGameInstance());
    Cyberblock_Log("before save: effects, god mode and collision boxes removed");
  }

  private cb func OnAfterSave(event: ref<GameSessionEvent>) {
    this.saving = false;
    this.UpdatePlayer(GetPlayer(this.GetGameInstance()));
    Cyberblock_Log(s"after save: state \(CyberblockSystem.Name(this.state))");
  }

  // On every load (and new game): remove anything of ours the save may carry and start Off.
  private cb func OnSessionReady(event: ref<GameSessionEvent>) {
    let player = GetPlayer(this.GetGameInstance());
    this.playerState.Clear(player);
    this.creative.Clear(player);
    if IsDefined(player) {
      CyberblockPlayerState.ClearLeftovers(player);
      CyberblockCreative.ClearLeftovers(player);
    }
    CyberblockCollision.Clear(this.GetGameInstance());
    this.state = CyberblockState.Off;
    Cyberblock_SetActive(false, false);
    Cyberblock_Log("session ready: leftovers removed, Off");
  }

  private cb func OnSessionEnd(event: ref<GameSessionEvent>) {
    this.playerState.Clear(GetPlayer(this.GetGameInstance()));
    this.creative.Clear(GetPlayer(this.GetGameInstance()));
    CyberblockCollision.Clear(this.GetGameInstance());
    this.state = CyberblockState.Off;
    Cyberblock_SetActive(false, false);
    Cyberblock_Log("session end: V restored, collision boxes removed, Off");
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
    Cyberblock_SetMenu(CyberblockSystem.InMenu(game));
    let player = GetPlayer(game);
    if !IsDefined(player) {
      // No world to act on: Minecraft block changes wait for the next live tick, the other events are dropped.
      this.events.Tick(game, false);
      return;
    }
    this.Step(player, toggle);
    this.UpdatePlayer(player);
    let on = CyberblockSystem.IsOn(this.state);
    if on {
      this.probes.Tick(player);
    }
    let live = on && !this.saving;
    this.events.Tick(game, live);
    if live {
      CyberblockCollision.Stream(game, player.GetWorldPosition());
    }
    this.npcs.Tick(player, Equals(this.state, CyberblockState.Active) && !this.saving);
  }

  // A Cyberpunk menu is open (pause, hub, popups): the DLL stops forwarding input to Minecraft (audit A.3, InMenu).
  private static func InMenu(game: GameInstance) -> Bool {
    return GameInstance.GetBlackboardSystem(game).Get(GetAllBlackboardDefs().UI_System).GetBool(GetAllBlackboardDefs().UI_System.IsInMenu);
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
      CyberblockCollision.Clear(this.GetGameInstance());
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
