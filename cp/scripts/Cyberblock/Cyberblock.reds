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
  private let probes: ref<CyberblockProbes>;
  private let saving: Bool;

  private func OnAttach() -> Void {
    this.state = CyberblockState.Off;
    this.playerState = new CyberblockPlayerState();
    this.probes = new CyberblockProbes();
    Cyberblock_SetActive(false, false);
    let callbacks = GameInstance.GetCallbackSystem();
    callbacks.RegisterCallback(n"Session/BeforeSave", this, n"OnBeforeSave");
    callbacks.RegisterCallback(n"Session/AfterSave", this, n"OnAfterSave");
    callbacks.RegisterCallback(n"Session/Ready", this, n"OnSessionReady");
    callbacks.RegisterCallback(n"Session/BeforeEnd", this, n"OnSessionEnd");
    this.ScheduleTick();
  }

  private func OnDetach() -> Void {
    GameInstance.GetDelaySystem(this.GetGameInstance()).CancelCallback(this.tickId);
    this.playerState.ShowHud();
    this.state = CyberblockState.Off;
    Cyberblock_SetActive(false, false);
  }

  // Saves stay vanilla: status effects and god mode are removed before the save and re-applied after it if
  // still Active (the HUD layer is not saved). Ticks leave V alone while the save is written.
  private cb func OnBeforeSave(event: ref<GameSessionEvent>) {
    this.saving = true;
    this.playerState.ClearSaved(GetPlayer(this.GetGameInstance()));
    Cyberblock_Log("before save: effects and god mode removed");
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
    if IsDefined(player) {
      CyberblockPlayerState.ClearLeftovers(player);
    }
    this.state = CyberblockState.Off;
    Cyberblock_SetActive(false, false);
    Cyberblock_Log("session ready: leftovers removed, Off");
  }

  private cb func OnSessionEnd(event: ref<GameSessionEvent>) {
    this.playerState.Clear(GetPlayer(this.GetGameInstance()));
    this.state = CyberblockState.Off;
    Cyberblock_SetActive(false, false);
    Cyberblock_Log("session end: V restored, Off");
  }

  private func UpdatePlayer(player: ref<PlayerPuppet>) -> Void {
    if IsDefined(player) && !this.saving {
      this.playerState.Update(player, Equals(this.state, CyberblockState.Active), Cyberblock_ScreenOpen());
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
    let player = GetPlayer(this.GetGameInstance());
    if !IsDefined(player) {
      return;
    }
    this.Step(player, toggle);
    this.UpdatePlayer(player);
    if Equals(this.state, CyberblockState.Active) || Equals(this.state, CyberblockState.Vehicle) {
      this.probes.Tick(player);
    }
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
    this.state = next;
    let active = Equals(next, CyberblockState.Active) || Equals(next, CyberblockState.Vehicle);
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
