// V teleport requests from Minecraft: Meet Up, and later Holo-Table spawns and the hub exit (megaplan 2026-10-04 task
// 3-S1, section 1.8 amendment item 4, 1.9 `vteleport` / `vteleported`; Phase 0 check (g)).
//
// Event n"vteleport" (Events.reds hands it here whatever the state, so every request gets an answer):
//   Cyberblock_EventPos = the target feet (Cyberpunk space), Cyberblock_EventAmount = the yaw to face (Cyberpunk degrees,
//   as n"teleport"), Cyberblock_EventId = the request id, Cyberblock_EventName = why (n"meetup", n"holo", n"hub_exit").
// Every request ends in exactly one Cyberblock_TeleportDone(id, ok, reason), reason one of "" (ok), "no ground",
// "timeout", "blocked (<why>)"; the DLL adds V's position as `vteleported.p`.
//
// Refused at once ("blocked (<why>)"): another teleport running (busy), no player, Cyberblock not active, a target that is
// no place in Night City, V dead, in a vehicle, held by a scene (CyberblockEffects.LockReason: a quest scene tier 2-5,
// a workspot, a braindance or a moving elevator: "in a scene: <lock>"), in combat, parked (Park.reds), during the Atlas
// survey tour, or while the DLL is moving V after a Minecraft flight.
//
// The method (Phase 0 (g): there is no engine "streaming ready" test, so the survey's settle probe is the test; the CET
// lines G1-G3 were not run, the log lines below show each step in game):
//   1. V is made invisible to NPCs (SetInvisible), invulnerable (god mode source n"CyberblockTeleport") and saving is
//      locked (n"CyberblockTeleport"), so no save ever holds V in the air; then V is held 60 m above the target, put back
//      there every 100 ms tick (falls are not reported meanwhile).
//   2. Settle probe: a 3 x 3 grid of down rays 10 m apart around the target, from 300 m above to 300 m below it, through
//      our collision boxes and devices (CyberblockProbes.Surface). When the centre ray hits and every ray's result stays
//      the same (heights rounded to 0.5 m) for 0.5 s, the collision there is loaded.
//   3. Down-ray snap: first from 2 m above the target's own height down to 40 m below it (the floor a friend stands on,
//      indoors too), else from 300 m above (the highest surface); V is put there facing the requested yaw, and the ok
//      answer follows 0.2 s later (the hold undone then), once the game has moved V.
//   No settle within 15 s: "timeout"; settled but neither snap ray hits: "no ground". On every failure V is put back
//   where V stood, facing as then. A session that ends (or a script reload) aborts a running teleport ("blocked (...)").
// Log lines: 'teleport: <id> (<why>) started ...', 'teleport: <id> done at (...)', 'teleport: <id> failed: <reason> ...',
// 'teleport: <id> (<why>) refused: blocked (<why>)'.
//
// 2.31 script API (NativeDB, redscript): TeleportationFacility.Teleport(GameObject, Vector4, EulerAngles),
// PlayerPuppet.SetInvisible/IsInvisible/IsInCombat (player.swift 3854-3858, 5400), GameObject.IsDead,
// GodModeSystem.AddGodMode/RemoveGodMode(EntityID, gameGodModeType, CName), SaveLocksManager.RequestSaveLockAdd/
// RequestSaveLockRemove (saveLocksManager.swift), VehicleComponent.IsMountedToVehicle, Vector4.ToRotation,
// SpatialQueriesSystem.SyncRaycastByQueryFilter (through CyberblockProbes.Surface), RoundF; our CyberblockEffects.LockReason
// / Facing, CyberblockEffectsService.Now / SuppressFall, CyberblockSurveyTour.Running, CyberblockPark.Parked.

// Declared here (rule 0.14), implemented by Cyberblock.dll (natives.cpp; package 3D): the answer to n"vteleport" `id`.
public native func Cyberblock_TeleportDone(id: Int32, ok: Bool, reason: String) -> Void;

public class CyberblockTeleport {
  // A request is being carried out (V held over the target).
  private let pending: Bool;
  private let id: Int32;
  private let why: CName;
  private let target: Vector4;
  private let yaw: Float;
  // Where V stood and faced when the request began, and V's own invisibility then.
  private let home: Vector4;
  private let homeYaw: Float;
  private let wasInvisible: Bool;
  // Sim time the request began; the settle probe's last result and since when it has stayed the same.
  private let startedAt: Float;
  private let signature: String;
  private let stableSince: Float;
  // V was put on the ground at landedAt; the answer goes 0.2 s later, once the game has moved V (the DLL reads V's
  // position for `vteleported.p` when the answer comes).
  private let landed: Bool;
  private let landedAt: Float;

  // A teleport is running (Probes, Flight, Survey and Park leave V alone meanwhile).
  public static func Busy(game: GameInstance) -> Bool {
    let system = CyberblockSystem.Get(game);
    return IsDefined(system) && system.Teleport().pending;
  }

  // An n"vteleport" event is current (Cyberblock_NextEvent). live: Active or Vehicle, with a player, not saving.
  public func Start(game: GameInstance, live: Bool) -> Void {
    let id = Cyberblock_EventId();
    let target = Cyberblock_EventPos();
    let yaw = Cyberblock_EventAmount();
    let why = Cyberblock_EventName();
    let player = GetPlayer(game);
    let refusal = this.Refusal(game, player, live, target);
    if StrLen(refusal) > 0 {
      Cyberblock_Log(s"teleport: \(id) (\(NameToString(why))) refused: blocked (\(refusal))");
      Cyberblock_TeleportDone(id, false, s"blocked (\(refusal))");
      return;
    }
    this.pending = true;
    this.id = id;
    this.why = why;
    this.target = new Vector4(target.X, target.Y, target.Z, 1.0);
    this.yaw = yaw;
    this.home = player.GetWorldPosition();
    this.homeYaw = Vector4.ToRotation(player.GetWorldForward()).Yaw;
    this.startedAt = CyberblockEffectsService.Now(game);
    this.signature = "";
    this.stableSince = this.startedAt;
    this.landed = false;
    this.wasInvisible = player.IsInvisible();
    player.SetInvisible(true);
    GameInstance.GetGodModeSystem(game).AddGodMode(player.GetEntityID(), gameGodModeType.Invulnerable, n"CyberblockTeleport");
    SaveLocksManager.RequestSaveLockAdd(game, n"CyberblockTeleport");
    this.Hold(game, player);
    Cyberblock_Log(s"teleport: \(id) (\(NameToString(why))) started: V hidden, invulnerable, saving locked, held over (\(target.X), \(target.Y), \(target.Z)) from (\(this.home.X), \(this.home.Y), \(this.home.Z))");
  }

  // Every CyberblockSystem tick with a player (also while not live: a running teleport always finishes).
  public func Tick(game: GameInstance, player: ref<PlayerPuppet>) -> Void {
    if !this.pending {
      return;
    }
    let now = CyberblockEffectsService.Now(game);
    let elapsed = now - this.startedAt;
    if elapsed < 0.0 {
      // the simulation clock restarted (another session): nothing of this request is left to finish
      this.Abort(game, "session changed");
      return;
    }
    if this.landed {
      if now - this.landedAt >= 0.2 {
        this.Finish(game, player, true, "", false);
      }
      return;
    }
    if player.IsDead() {
      this.Finish(game, player, false, "blocked (dead)", false);
      return;
    }
    this.Hold(game, player);
    let queries = GameInstance.GetSpatialQueriesSystem(game);
    let signature = this.Settle(queries);
    if StrLen(signature) > 0 && Equals(signature, this.signature) {
      if now - this.stableSince >= 0.5 {
        this.Snap(game, player, queries);
        return;
      }
    } else {
      this.signature = signature;
      this.stableSince = now;
    }
    if elapsed >= 15.0 {
      this.Finish(game, player, false, "timeout", true);
    }
  }

  // Ends a running request without moving V (session end, detach, script reload): the hold is undone and, while the link
  // is up, Minecraft gets "blocked (<why>)".
  public func Abort(game: GameInstance, why: String) -> Void {
    if !this.pending {
      return;
    }
    this.pending = false;
    this.Undo(game, GetPlayer(game));
    Cyberblock_Log(s"teleport: \(this.id) failed: blocked (\(why))");
    if Cyberblock_IsLinked() {
      Cyberblock_TeleportDone(this.id, false, s"blocked (\(why))");
    }
  }

  // On every load: a request of the previous session is forgotten (Abort ran at its end).
  public func Forget() -> Void {
    this.pending = false;
  }

  // Why the request cannot be carried out now ("" when it can).
  private func Refusal(game: GameInstance, player: ref<PlayerPuppet>, live: Bool, target: Vector4) -> String {
    if this.pending {
      return "busy";
    }
    if !IsDefined(player) {
      return "no player";
    }
    if !live {
      return "Cyberblock is not active";
    }
    if !CyberblockTeleport.InNightCity(target) {
      return "outside Night City";
    }
    if player.IsDead() {
      return "dead";
    }
    if VehicleComponent.IsMountedToVehicle(game, player) {
      return "in a vehicle";
    }
    let lock = CyberblockEffects.LockReason(game, player);
    if NotEquals(lock, n"") {
      return s"in a scene: \(NameToString(lock))";
    }
    if player.IsInCombat() {
      return "in combat";
    }
    if CyberblockPark.Parked(game) {
      return "parked";
    }
    if CyberblockSurveyTour.Running(game) {
      return "survey tour";
    }
    if Cyberblock_FlightDriving() {
      return "flying";
    }
    return "";
  }

  // A finite point within 10 km of the map centre and 2 km of sea level (NaN fails every comparison).
  private static func InNightCity(p: Vector4) -> Bool {
    return AbsF(p.X) <= 10000.0 && AbsF(p.Y) <= 10000.0 && AbsF(p.Z) <= 2000.0;
  }

  // V 60 m above the target, facing the requested yaw; falls are not reported meanwhile.
  private func Hold(game: GameInstance, player: ref<PlayerPuppet>) -> Void {
    CyberblockEffectsService.Get().SuppressFall(game, 1.0);
    GameInstance.GetTeleportationFacility(game).Teleport(player, new Vector4(this.target.X, this.target.Y, this.target.Z + 60.0, 1.0), CyberblockEffects.Facing(this.yaw));
  }

  // The settle probe's result: each of the 3 x 3 rays' hit height rounded to 0.5 m ("-" for a miss); "" while the
  // centre ray misses (never counts as settled).
  private func Settle(queries: ref<SpatialQueriesSystem>) -> String {
    let signature = "";
    let centreHit = false;
    let result: TraceResult;
    let gy = -1;
    while gy <= 1 {
      let gx = -1;
      while gx <= 1 {
        let x = this.target.X + 10.0 * Cast<Float>(gx);
        let y = this.target.Y + 10.0 * Cast<Float>(gy);
        let top = new Vector4(x, y, this.target.Z + 300.0, 1.0);
        let bottom = new Vector4(x, y, this.target.Z - 300.0, 1.0);
        if CyberblockProbes.Surface(queries, top, bottom, result) {
          signature += s"\(RoundF(result.position.Z * 2.0)),";
          if gx == 0 && gy == 0 {
            centreHit = true;
          }
        } else {
          signature += "-,";
        }
        gx += 1;
      }
      gy += 1;
    }
    return centreHit ? signature : "";
  }

  // The down-ray snap: the floor near the target's own height, else the highest surface; then done.
  private func Snap(game: GameInstance, player: ref<PlayerPuppet>, queries: ref<SpatialQueriesSystem>) -> Void {
    let t = this.target;
    let result: TraceResult;
    let near = CyberblockProbes.Surface(queries, new Vector4(t.X, t.Y, t.Z + 2.0, 1.0), new Vector4(t.X, t.Y, t.Z - 40.0, 1.0), result);
    if !near && !CyberblockProbes.Surface(queries, new Vector4(t.X, t.Y, t.Z + 300.0, 1.0), new Vector4(t.X, t.Y, t.Z - 300.0, 1.0), result) {
      this.Finish(game, player, false, "no ground", true);
      return;
    }
    let ground = Cast<Vector4>(result.position);
    CyberblockEffectsService.Get().SuppressFall(game, 3.0);
    GameInstance.GetTeleportationFacility(game).Teleport(player, new Vector4(ground.X, ground.Y, ground.Z + 0.05, 1.0), CyberblockEffects.Facing(this.yaw));
    Cyberblock_Log(s"teleport: \(this.id) snapped to the \(near ? "floor near the target" : "highest surface") at (\(ground.X), \(ground.Y), \(ground.Z))");
    this.landed = true;
    this.landedAt = CyberblockEffectsService.Now(game);
  }

  // Ends the request: V put back home on failure (`putBack`), the hold undone, Minecraft answered.
  private func Finish(game: GameInstance, player: ref<PlayerPuppet>, ok: Bool, reason: String, putBack: Bool) -> Void {
    this.pending = false;
    let seconds = CyberblockEffectsService.Now(game) - this.startedAt;
    if putBack {
      CyberblockEffectsService.Get().SuppressFall(game, 3.0);
      GameInstance.GetTeleportationFacility(game).Teleport(player, this.home, CyberblockEffects.Facing(this.homeYaw));
    }
    this.Undo(game, player);
    if ok {
      let p = player.GetWorldPosition();
      Cyberblock_Log(s"teleport: \(this.id) done at (\(p.X), \(p.Y), \(p.Z)) after \(seconds) s");
    } else {
      Cyberblock_Log(s"teleport: \(this.id) failed: \(reason) after \(seconds) s\(putBack ? ", V put back" : "")");
    }
    Cyberblock_TeleportDone(this.id, ok, reason);
  }

  // V's own invisibility back, the god mode source and the save lock removed.
  private func Undo(game: GameInstance, player: ref<PlayerPuppet>) -> Void {
    if IsDefined(player) {
      player.SetInvisible(this.wasInvisible);
      GameInstance.GetGodModeSystem(game).RemoveGodMode(player.GetEntityID(), gameGodModeType.Invulnerable, n"CyberblockTeleport");
    }
    SaveLocksManager.RequestSaveLockRemove(game, n"CyberblockTeleport");
  }
}
