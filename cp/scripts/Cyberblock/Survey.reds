// The automatic Atlas survey tour (`/cyberblock survey start|stop|status` in Minecraft; the DLL's survey.hpp owns the
// plan and the state machine, this class carries out each step on the game thread).
//
// While Cyberblock is Active, Minecraft is linked, V is on foot, alive, not held (workspot, scene tier 2-5, braindance,
// moving elevator), not in combat and not flying, the tour:
//   bounds  derives Night City's extent from the game: the x/y extents of every Map mappin and every known fast-travel
//           point (the DLL adds a 200 m margin, cuts the box to 9 km and needs at least 8 pins);
//   begin   makes V invisible to NPCs (PlayerPuppet.SetInvisible), invulnerable (god mode source n"CyberblockSurvey")
//           and locks saving (SaveLocksManager, reason n"CyberblockSurvey"), so no save can ever hold V at a stop;
//   settle  casts a 3 x 3 grid of long down rays 80 m apart around the stop where the DLL holds V (every frame) until
//           Night City's collision has streamed in;
//   sample  casts up to 400 survey rays per tick at the stop's 2-m columns (Probes.reds SurveyRay);
//   end     puts V back exactly where V stood, facing as then (TeleportationFacility.Teleport; falls are not reported for
//           3 s), and undoes begin: V's own invisibility as it was, the god mode source and the save lock removed.
// Anything that blocks the tour while it runs (F9, a vehicle, a scene, combat, death, the link dropping) ends it at
// once, with V put back; a Cyberpunk menu pauses it (V stays held). A session that ends forgets it.
//
// 2.31 script API (decompiled scripts): MappinSystem.GetMappins(gamemappinsMappinTargetType.Map), IMappin
// GetWorldPosition, FastTravelSystem (scriptable system n"FastTravelSystem") GetFastTravelPoints, FastTravelPointData
// mappinID / MappinSystem.GetMappin (fastTravelSystem.swift), PlayerPuppet.SetInvisible/IsInvisible/IsInCombat
// (player.swift), GameObject.IsDead, GodModeSystem.AddGodMode/RemoveGodMode(EntityID, gameGodModeType, CName),
// SaveLocksManager.RequestSaveLockAdd/RequestSaveLockRemove (saveLocksManager.swift), TeleportationFacility.Teleport,
// Vector4.ToRotation, VehicleComponent.IsMountedToVehicle, SpatialQueriesSystem as Probes.reds.

public class CyberblockSurveyTour {
  // begin was applied and not undone yet
  private let applied: Bool;
  private let wasInvisible: Bool;

  public static func Running(game: GameInstance) -> Bool {
    let system = CyberblockSystem.Get(game);
    return IsDefined(system) && system.SurveyTour().applied;
  }

  // Every CyberblockSystem tick with a player. active: the Active state (on foot); saving: a save is being written.
  public func Tick(game: GameInstance, player: ref<PlayerPuppet>, active: Bool, saving: Bool) -> Void {
    let blocked = this.Blocked(game, player, active, saving);
    let feet = player.GetWorldPosition();
    let yaw = Vector4.ToRotation(player.GetWorldForward()).Yaw;
    let action = Cyberblock_SurveyTourTick(feet, yaw, blocked, CyberblockDisable.InMenu(game));
    switch action {
      case n"bounds":
        this.Bounds(game);
        break;
      case n"begin":
        this.Begin(game, player);
        break;
      case n"settle":
        this.Settle(game);
        break;
      case n"sample":
        this.Sample(game);
        break;
      case n"end":
        this.End(game, player);
        break;
      default:
        break;
    }
  }

  // Why V may not tour now (n"" when it may).
  private func Blocked(game: GameInstance, player: ref<PlayerPuppet>, active: Bool, saving: Bool) -> CName {
    if !active || saving {
      return n"off";
    }
    if !Cyberblock_IsLinked() {
      return n"unlinked";
    }
    if player.IsDead() {
      return n"dead";
    }
    if VehicleComponent.IsMountedToVehicle(game, player) {
      return n"vehicle";
    }
    if CyberblockEffects.PlayerLocked(game, player) {
      return n"held";
    }
    if player.IsInCombat() {
      return n"combat";
    }
    if !this.applied && (CyberblockFlight.IsFlying(game) || Cyberblock_FlightDriving()) {
      return n"flight";
    }
    return n"";
  }

  // Night City's extent: every Map mappin and every known fast-travel point.
  private func Bounds(game: GameInstance) -> Void {
    let mappins = GameInstance.GetMappinSystem(game);
    let lo = new Vector4(100000.0, 100000.0, 0.0, 1.0);
    let hi = new Vector4(-100000.0, -100000.0, 0.0, 1.0);
    let pins = 0;
    let all: array<ref<IMappin>>;
    mappins.GetMappins(gamemappinsMappinTargetType.Map, all);
    for m in all {
      if IsDefined(m) && CyberblockSurveyTour.Grow(m.GetWorldPosition(), lo, hi) {
        pins += 1;
      }
    }
    let travel = GameInstance.GetScriptableSystemsContainer(game).Get(n"FastTravelSystem") as FastTravelSystem;
    if IsDefined(travel) {
      for point in travel.GetFastTravelPoints() {
        let mappin = IsDefined(point) ? mappins.GetMappin(point.mappinID) : null;
        if IsDefined(mappin) && CyberblockSurveyTour.Grow(mappin.GetWorldPosition(), lo, hi) {
          pins += 1;
        }
      }
    }
    Cyberblock_SurveyTourBounds(lo, hi, pins);
  }

  // Widens lo..hi by a pin's position; false for a position that is no place in Night City (zero, or beyond 10 km).
  private static func Grow(p: Vector4, out lo: Vector4, out hi: Vector4) -> Bool {
    if (p.X == 0.0 && p.Y == 0.0) || AbsF(p.X) > 10000.0 || AbsF(p.Y) > 10000.0 {
      return false;
    }
    lo.X = MinF(lo.X, p.X);
    lo.Y = MinF(lo.Y, p.Y);
    hi.X = MaxF(hi.X, p.X);
    hi.Y = MaxF(hi.Y, p.Y);
    return true;
  }

  private func Begin(game: GameInstance, player: ref<PlayerPuppet>) -> Void {
    if !this.applied {
      this.wasInvisible = player.IsInvisible();
    }
    this.applied = true;
    player.SetInvisible(true);
    GameInstance.GetGodModeSystem(game).AddGodMode(player.GetEntityID(), gameGodModeType.Invulnerable, n"CyberblockSurvey");
    SaveLocksManager.RequestSaveLockAdd(game, n"CyberblockSurvey");
    Cyberblock_Log("survey: started: V hidden, invulnerable, saving locked");
  }

  // The 3 x 3 streaming probes around the stop.
  private func Settle(game: GameInstance) -> Void {
    let stop = Cyberblock_SurveyTourStop();
    let queries = GameInstance.GetSpatialQueriesSystem(game);
    let probes: array<Vector4>;
    let casts = 0;
    let dy = -1;
    while dy <= 1 {
      let dx = -1;
      while dx <= 1 {
        ArrayPush(probes, CyberblockProbes.SurveyRay(queries, stop.X + 80.0 * Cast<Float>(dx), stop.Y + 80.0 * Cast<Float>(dy), 100.0, casts));
        dx += 1;
      }
      dy += 1;
    }
    Cyberblock_SurveyTourSettle(probes);
  }

  private func Sample(game: GameInstance) -> Void {
    let stop = Cyberblock_SurveyTourStop();
    CyberblockProbes.Survey(GameInstance.GetSpatialQueriesSystem(game), new Vector4(stop.X, stop.Y, 100.0, 1.0), 400);
  }

  // V back home, then everything begin did undone.
  private func End(game: GameInstance, player: ref<PlayerPuppet>) -> Void {
    let home = Cyberblock_SurveyTourHome();
    let facing: EulerAngles;
    facing.Yaw = home.W;
    CyberblockEffectsService.Get().SuppressFall(game, 3.0);
    GameInstance.GetTeleportationFacility(game).Teleport(player, new Vector4(home.X, home.Y, home.Z, 1.0), facing);
    Cyberblock_Log(s"survey: V put back at (\(home.X), \(home.Y), \(home.Z))");
    this.Undo(game, player);
  }

  // Undoes begin (also when the session ends with the tour running).
  public func Undo(game: GameInstance, player: ref<PlayerPuppet>) -> Void {
    if !this.applied {
      return;
    }
    this.applied = false;
    if IsDefined(player) {
      player.SetInvisible(this.wasInvisible);
      GameInstance.GetGodModeSystem(game).RemoveGodMode(player.GetEntityID(), gameGodModeType.Invulnerable, n"CyberblockSurvey");
    }
    SaveLocksManager.RequestSaveLockRemove(game, n"CyberblockSurvey");
    Cyberblock_Log("survey: V visible again, survey god mode and save lock removed");
  }
}
