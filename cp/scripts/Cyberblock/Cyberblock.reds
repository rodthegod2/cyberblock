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
// (Off, Lost), on every load and at session end; a save leaves them (StaticEntitySystem entities are never saved). The health link (Health.reds) needs no call:
// it acts exactly while our god mode is V's only invulnerability, i.e. while Active. CyberblockCreative (Health.reds)
// keeps V invulnerable in the Vehicle state while Minecraft is in creative mode (Cyberblock_GameMode).
//
// Layers 3-6 wiring (plan 2026-10-03 S1.6, section 1.4): every tick with a player also runs CyberblockPuppets,
// CyberblockFlight, CyberblockTether and CyberblockProjectiles (live: Active or Vehicle, not saving) and
// CyberblockPerks, CyberblockCyberware (re-test 2 plan S5) and CyberblockBuffs (active: Active, not saving), reports
// whether V is crouched, in the air or held by
// a vehicle or the game (Cyberblock_SetStance), whether a Cyberpunk menu is open (Cyberblock_SetMenu) and whether a dialogue offers two or more
// choices while Active (Cyberblock_SetChoices: E, Q, 1-4 and the wheel then stay Cyberpunk's). ClearWorld (every section 1.4
// Clear) runs before every save, on session ready, at session end and on every transition out of Active/Vehicle; all
// but the save also run CyberblockCollision.Clear (the boxes are never saved, and V may stand on them).
//
// While a save is written (Session/BeforeSave until Session/AfterSave) Minecraft's side is left as it is: its events
// wait in the DLL's queue and the tethers stay (ClearWorld only restarted their timers); all of it carries on after the
// save. Minecraft's NPC proxies (and their leads) stay as long as Minecraft still gets cam frames, as through a save or a
// Cyberpunk menu, and are removed only when the host goes inactive. The saving flag is also cleared on session ready and
// end, and after 10 s without an AfterSave (a save that failed or was aborted; counted by the tick and by the UI pump,
// which also runs while the game is paused), so it can never stay stuck.
//
// Re-test 3 (plan 2026-10-03-retest3-polish task U1, sections 1.4 and 1.11): while On (Active or Vehicle, not saving)
// every tick runs CyberblockInk.Apply, which keeps Cyberpunk's HUD, game-notification and waiting-sign layers invisible
// (Minecraft draws the UI); every Clear path but the save (Off, Lost, session ready and end, detach) runs
// CyberblockInk.Restore. Through a save the layers stay hidden (ink opacity is not saved; F12: Cyberpunk's HUD never
// flashes up over Minecraft): CyberblockUi.Pump keeps applying them with InkOn, which ignores the saving flag. The Vehicle state only hides V's meshes (CyberblockPlayerState). Our own messages go to
// Minecraft as toasts (Cyberblock_Notify) while it is shown; "Start Minecraft first", "Minecraft lost" and the switch
// back to Off stay Cyberpunk warnings, since Minecraft is not drawn then. Each tick also runs package S's
// CyberblockSeat.Tick and CyberblockView.Tick (10 Hz), and every Clear path their Clear.
//
// Phase 1 (megaplan 2026-10-04 task 1-H1): the system owns the CyberblockSession (Session.reds), which Events.reds hands
// every n"session" event; while the server does not count this host as host it cleans Night City as a link drop does.

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
  private let hud: ref<CyberblockHud>;
  private let surveyTour: ref<CyberblockSurveyTour>;
  private let session: ref<CyberblockSession>;
  private let saving: Bool;
  private let savingTicks: Int32;
  private let savingPumps: Int32;
  private let menuListener: ref<CallbackHandle>;

  private func OnAttach() -> Void {
    this.state = CyberblockState.Off;
    this.playerState = new CyberblockPlayerState();
    this.creative = new CyberblockCreative();
    this.probes = new CyberblockProbes();
    this.events = new CyberblockEvents();
    this.npcs = new CyberblockNpcs();
    this.npcs.Register();
    this.hud = new CyberblockHud();
    this.surveyTour = new CyberblockSurveyTour();
    this.session = new CyberblockSession();
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
    CyberblockInk.RestoreFor(this.GetGameInstance(), "detach");
    this.hud.Unregister();
    Cyberblock_SurveyTourReset();
    this.surveyTour.Undo(this.GetGameInstance(), GetPlayer(this.GetGameInstance()));
    CyberblockSystem.ClearWorld(this.GetGameInstance(), true);
    this.state = CyberblockState.Off;
    Cyberblock_SetActive(false, false);
  }

  // Saves stay vanilla: status effects, stat modifiers, god modes, puppets, tethers, perk and potion effects are
  // removed before the save; after it V's state comes back if still Active and the rest on the next live tick (the HUD
  // layers are not saved, so they stay hidden). The collision boxes stay: the save never holds them, and V may be standing on one. Ticks leave
  // V and Night City alone while the save is written.
  private cb func OnBeforeSave(event: ref<GameSessionEvent>) {
    if CyberblockSystem.IsOn(this.state) {
      Cyberblock_Notify(n"save", "Saving Night City...", "", 3.0);
    }
    if CyberblockSurveyTour.Running(this.GetGameInstance()) {
      Cyberblock_Log("before save: a save began during the survey tour (its save lock should prevent this)");
    }
    this.saving = true;
    this.savingTicks = 0;
    this.savingPumps = 0;
    this.playerState.ClearSaved(GetPlayer(this.GetGameInstance()));
    this.creative.Clear(GetPlayer(this.GetGameInstance()));
    CyberblockSystem.ClearWorld(this.GetGameInstance(), false);
    Cyberblock_Log("before save: effects, god modes and world objects removed");
  }

  private cb func OnAfterSave(event: ref<GameSessionEvent>) {
    if CyberblockSystem.IsOn(this.state) {
      Cyberblock_Notify(n"save", "Saved", "", 2.0);
    }
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
    CyberblockInk.RestoreFor(this.GetGameInstance(), "session ready");
    this.playerState.Clear(player);
    this.creative.Clear(player);
    if IsDefined(player) {
      CyberblockPlayerState.ClearLeftovers(player);
      CyberblockCreative.ClearLeftovers(player);
    }
    CyberblockSystem.ClearWorld(this.GetGameInstance(), true);
    this.state = CyberblockState.Off;
    Cyberblock_SetActive(false, false);
    this.hud.Unregister();
    this.hud.Register(this.GetGameInstance());
    Cyberblock_Log("session ready: leftovers removed, Off");
  }

  private cb func OnSessionEnd(event: ref<GameSessionEvent>) {
    this.saving = false;
    Cyberblock_SurveyTourReset();
    this.surveyTour.Undo(this.GetGameInstance(), GetPlayer(this.GetGameInstance()));
    CyberblockInk.RestoreFor(this.GetGameInstance(), "session end");
    this.hud.ClearMinecraft();
    this.hud.Unregister();
    this.playerState.Clear(GetPlayer(this.GetGameInstance()));
    this.creative.Clear(GetPlayer(this.GetGameInstance()));
    CyberblockSystem.ClearWorld(this.GetGameInstance(), true);
    this.state = CyberblockState.Off;
    Cyberblock_SetActive(false, false);
    Cyberblock_Log("session end: V restored, world objects removed, Off");
  }

  private func UpdatePlayer(player: ref<PlayerPuppet>) -> Void {
    if IsDefined(player) && !this.saving {
      this.playerState.Update(player, Equals(this.state, CyberblockState.Active), Equals(this.state, CyberblockState.Vehicle), Cyberblock_ScreenOpen());
      this.creative.Update(player, Equals(this.state, CyberblockState.Vehicle));
      CyberblockInk.Apply(this.GetGameInstance(), CyberblockSystem.IsOn(this.state));
    }
  }

  // Cyberblock is On and not writing a save: Minecraft is shown and draws the UI (CyberblockUi.Pump asks this).
  public func UiOn() -> Bool {
    return CyberblockSystem.IsOn(this.state) && !this.saving;
  }

  // Cyberblock is On, saves included: Cyberpunk's ink layers stay hidden and its notifications go to Minecraft.
  public func InkOn() -> Bool {
    return CyberblockSystem.IsOn(this.state);
  }

  // From CyberblockUi.Pump (at most 30 Hz, also while paused, when the DelaySystem tick stands still): a save without
  // an AfterSave ends after 300 passes (at least 10 s).
  public func PumpSave() -> Void {
    if this.saving {
      this.savingPumps += 1;
      if this.savingPumps >= 300 {
        this.EndSave("save: no AfterSave within 10 s (pump), carrying on");
      }
    }
  }

  // The Atlas survey tour (Survey.reds).
  public func SurveyTour() -> ref<CyberblockSurveyTour> {
    return this.surveyTour;
  }

  // The Minecraft server's verdict on this host (Session.reds; Events.reds and Survey.reds ask it).
  public func Session() -> ref<CyberblockSession> {
    return this.session;
  }

  // The HUD data stream (Hud.reds); Notifications.reds tells it about actionable notifications.
  public func Hud() -> ref<CyberblockHud> {
    return this.hud;
  }

  public static func Get(game: GameInstance) -> ref<CyberblockSystem> {
    return GameInstance.GetScriptableSystemsContainer(game).Get(n"CyberblockSystem") as CyberblockSystem;
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
    // The Atlas survey tour (Survey.reds): every tick, so F9, a vehicle or a scene ends it at once with V put back.
    this.surveyTour.Tick(game, player, Equals(this.state, CyberblockState.Active), this.saving);
    // A dialogue or choice list with two or more choices keeps E, Q, 1-4 and the wheel for Cyberpunk (the DLL).
    Cyberblock_SetChoices(Equals(this.state, CyberblockState.Active) && CyberblockSystem.ChoiceCount(game) >= 2);
    this.UpdatePlayer(player);
    let on = CyberblockSystem.IsOn(this.state);
    if on {
      this.probes.Tick(player);
    }
    let live = on && !this.saving;
    let active = Equals(this.state, CyberblockState.Active) && !this.saving;
    // cam.cr / cam.air / cam.held: Minecraft keeps Steve crouched, airborne or still with V.
    let held = VehicleComponent.IsMountedToVehicle(game, player) || CyberblockEffects.PlayerLocked(game, player);
    Cyberblock_SetStance(CyberblockSystem.Crouched(player), CyberblockSystem.InAir(player), held);
    // Package S (plan section 1.4): seat data for Steve in vehicles, and the third-person view gate.
    CyberblockSeat.Tick(game, player, live);
    CyberblockView.Tick(game, player, active, held, CyberblockDisable.InMenu(game));
    if !this.saving {
      this.events.Tick(game, live);
    }
    if live {
      CyberblockCollision.Stream(game, CyberblockFlight.Center(game, player));
    }
    CyberblockPuppets.Tick(game, player, live);
    CyberblockFlight.Tick(game, player, live);
    if !this.saving {
      CyberblockTether.Tick(game, player, live);
    }
    CyberblockProjectiles.Tick(game, player, live);
    CyberblockPerks.Tick(game, player, active);
    CyberblockCyberware.Tick(game, player, active);
    CyberblockBuffs.Tick(game, player, active);
    if !this.saving {
      this.npcs.Tick(player, active);
    }
    // Minecraft draws Cyberpunk's HUD: stream its data (U3). Registered on session ready; also here after a script
    // reload in the middle of a session.
    this.hud.Register(game);
    this.hud.Tick(player, live);
    // Notifications shown while Off are dropped here; while On (saves included) CyberblockUi.Pump reports them at once.
    CyberblockNotifications.Poll(game, on);
    // The DLL pumps the UI bridge at 30 Hz; while it does not (CyberblockUi.Pump not found yet), Minecraft's screen
    // requests (the Atlas, Journal and Phone replies) are answered from here.
    if this.UiOn() && !Cyberblock_UiPumpLive() {
      CyberblockUi.Pump();
    }
  }

  // Everything of ours in Night City (plan 2026-10-03 section 1.4 Clears). Perk ranks, installed cyberware and the
  // collision registry stay.
  // leaving: Night City stops being live (Off, Lost, load, session end, detach), so Steve's infinite potions are
  // forgotten too; false before a save, after which they come back.
  // The collision boxes stay through a save (leaving false): Codeware's StaticEntitySystem never writes its entities into
  // a save (StaticEntitySpec has no persistence options, unlike DynamicEntitySystem's), so despawning them only took the
  // floor from under V on a Minecraft bridge or tower until the next live tick streamed them back (64 per 100 ms).
  private static func ClearWorld(game: GameInstance, leaving: Bool) -> Void {
    if leaving {
      CyberblockCollision.Clear(game);
    }
    CyberblockPuppets.Clear(game);
    CyberblockFlight.Clear(game);
    CyberblockTether.Clear(game);
    CyberblockPerks.Clear(game);
    CyberblockCyberware.Clear(game);
    CyberblockBuffs.Clear(game, leaving);
    CyberblockSeat.Clear(game);
    CyberblockView.Clear(game);
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

  // In the air as the detailed locomotion state has it (PlayerStateMachine.LocomotionDetailed, the blackboard Crouched
  // reads; gamePSMDetailedLocomotionStates): falling, jumping, double or charged jumps, air thrusters/hover, air dash.
  private static func InAir(player: ref<PlayerPuppet>) -> Bool {
    let psm = player.GetPlayerStateMachineBlackboard();
    if !IsDefined(psm) {
      return false;
    }
    let state = IntEnum<gamePSMDetailedLocomotionStates>(psm.GetInt(GetAllBlackboardDefs().PlayerStateMachine.LocomotionDetailed));
    return Equals(state, gamePSMDetailedLocomotionStates.SlideFall) || Equals(state, gamePSMDetailedLocomotionStates.Fall)
      || Equals(state, gamePSMDetailedLocomotionStates.AirThrusters) || Equals(state, gamePSMDetailedLocomotionStates.AirHover)
      || Equals(state, gamePSMDetailedLocomotionStates.SuperheroFall) || Equals(state, gamePSMDetailedLocomotionStates.Jump)
      || Equals(state, gamePSMDetailedLocomotionStates.DoubleJump) || Equals(state, gamePSMDetailedLocomotionStates.ChargeJump)
      || Equals(state, gamePSMDetailedLocomotionStates.HoverJump) || Equals(state, gamePSMDetailedLocomotionStates.DodgeAir);
  }

  // The choices on screen: every choice of every dialogue choice hub (UIInteractions.DialogChoiceHubs, as
  // DefaultTransition.AreChoiceHubsActive reads it, defaultTransition.swift:1586; DialogChoiceHubs.choiceHubs and
  // ListChoiceHubData.choices, orphans.swift), plus those of an active interaction list (UIInteractions
  // .InteractionChoiceHub, InteractionChoiceHubData.active/choices, as settingsMain.swift's noChoiceHubsShown reads it): both are
  // the InteractionActions input context, which scrolls with the wheel and picks choices 3 and 4 with 1 and 2.
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
    let list = FromVariant<InteractionChoiceHubData>(interactions.GetVariant(GetAllBlackboardDefs().UIInteractions.InteractionChoiceHub));
    if list.active {
      count += ArraySize(list.choices);
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
      // Minecraft still listens: take down its prompt, choices and subtitles before it stops being shown.
      this.hud.ClearMinecraft();
      CyberblockSystem.ClearWorld(this.GetGameInstance(), true);
      CyberblockInk.RestoreFor(this.GetGameInstance(), Equals(next, CyberblockState.Lost) ? "lost" : "off");
    }
    Cyberblock_SetActive(active, mounted);
    if active {
      // Minecraft is drawn from now on: the message is a Minecraft toast.
      Cyberblock_Notify(n"info", "Cyberblock", message, 2.0);
    } else {
      // Off or Lost: Minecraft is no longer drawn, so the message stays a Cyberpunk warning.
      this.Notify(message);
    }
  }

  // A Cyberpunk on-screen warning, only for the moments Minecraft is not shown ("Start Minecraft first", "Minecraft
  // lost", "Cyberblock off", a refused session's reason from Session.reds).
  public func Notify(message: String) -> Void {
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
