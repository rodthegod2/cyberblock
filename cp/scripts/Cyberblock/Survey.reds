// The automatic Atlas survey tour (`/cyberblock survey start|stop|status` in Minecraft; the DLL's survey.hpp owns the
// plan and the state machine and casts the stop's rays itself every frame, this class carries out the steps that need
// the game's scripts).
//
// While Cyberblock is Active, Minecraft is linked, V is on foot, alive, not held (workspot, scene tier 2-5, braindance,
// moving elevator), not in combat and not flying, the tour:
//   bounds  hands the DLL every Map mappin and every known fast-travel point, each fast-travel point marked by its
//           district: the Badlands (Badlands, North Badlands, South Badlands, Morro Rock, or a district below one of them)
//           are the outskirts, every other district is the city (the DLL builds the city and the outskirts from them);
//   begin   makes V invisible to NPCs (PlayerPuppet.SetInvisible), invulnerable (god mode source n"CyberblockSurvey")
//           and locks saving (SaveLocksManager, reason n"CyberblockSurvey"), so no save can ever hold V at a stop;
//           the DLL then holds V over each stop every frame and casts the probes and survey rays itself;
//   end     puts V back exactly where V stood, facing as then (TeleportationFacility.Teleport; falls are not reported for
//           3 s), and undoes begin: V's own invisibility as it was, the god mode source and the save lock removed.
// Anything that blocks the tour while it runs (F9, a vehicle, a scene, combat, death, the link dropping, the Minecraft
// server no longer counting this host as host: Session.reds) ends it at once, with V put back; a Cyberpunk menu pauses it (V stays held). A session that ends forgets it.
//
// 2.31 script API (decompiled scripts): MappinSystem.GetMappins(gamemappinsMappinTargetType.Map), IMappin
// GetWorldPosition, FastTravelSystem (scriptable system n"FastTravelSystem") GetFastTravelPoints, FastTravelPointData
// mappinID / GetPointRecord / MappinSystem.GetMappin (fastTravelSystem.swift), TweakDBInterface.GetFastTravelPointRecord,
// FastTravelPoint_Record.District, District_Record.Type/ParentDistrict, gamedataDistrict (orphans.swift),
// PlayerPuppet.SetInvisible/IsInvisible/IsInCombat (player.swift), GameObject.IsDead,
// GodModeSystem.AddGodMode/RemoveGodMode(EntityID, gameGodModeType, CName), SaveLocksManager.RequestSaveLockAdd/
// RequestSaveLockRemove (saveLocksManager.swift), TeleportationFacility.Teleport, Vector4.ToRotation,
// VehicleComponent.IsMountedToVehicle.

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
    // megaplan 1-H1: the Minecraft server does not count this host as host (Session.reds): no tour, a running one ends
    let system = CyberblockSystem.Get(game);
    if IsDefined(system) && !system.Session().IsHost() {
      return n"session";
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
    // Megaplan 3-S1: never while a V teleport holds V or V is parked (Teleport.reds and Park.reds refuse the tour's time).
    if CyberblockTeleport.Busy(game) {
      return n"teleport";
    }
    if CyberblockPark.Parked(game) {
      return n"parked";
    }
    if !this.applied && (CyberblockFlight.IsFlying(game) || Cyberblock_FlightDriving()) {
      return n"flight";
    }
    return n"";
  }

  // Every Map mappin (W 2) and every known fast-travel point (W 0 in the city, 1 in the Badlands) for the DLL's area.
  private func Bounds(game: GameInstance) -> Void {
    let mappins = GameInstance.GetMappinSystem(game);
    let pins: array<Vector4>;
    let all: array<ref<IMappin>>;
    mappins.GetMappins(gamemappinsMappinTargetType.Map, all);
    for m in all {
      if IsDefined(m) {
        CyberblockSurveyTour.Add(pins, m.GetWorldPosition(), 2);
      }
    }
    let travel = GameInstance.GetScriptableSystemsContainer(game).Get(n"FastTravelSystem") as FastTravelSystem;
    if IsDefined(travel) {
      for point in travel.GetFastTravelPoints() {
        let mappin = IsDefined(point) ? mappins.GetMappin(point.mappinID) : null;
        if IsDefined(mappin) {
          let record = TweakDBInterface.GetFastTravelPointRecord(point.GetPointRecord());
          let kind = !IsDefined(record) ? 2 : (CyberblockSurveyTour.InBadlands(record.District()) ? 1 : 0);
          CyberblockSurveyTour.Add(pins, mappin.GetWorldPosition(), kind);
        }
      }
    }
    Cyberblock_SurveyTourArea(pins);
  }

  // A pin at `p` of `kind`; left out when it is no place in Night City (zero, or beyond 10 km).
  private static func Add(out pins: array<Vector4>, p: Vector4, kind: Int32) -> Void {
    if (p.X == 0.0 && p.Y == 0.0) || AbsF(p.X) > 10000.0 || AbsF(p.Y) > 10000.0 {
      return;
    }
    ArrayPush(pins, new Vector4(p.X, p.Y, 0.0, Cast<Float>(kind)));
  }

  // The district is one of the Badlands, or lies below one (sub-districts name their parent).
  private static func InBadlands(district: wref<District_Record>) -> Bool {
    let d = district;
    let depth = 0;
    while IsDefined(d) && depth < 8 {
      let t = d.Type();
      if Equals(t, gamedataDistrict.Badlands) || Equals(t, gamedataDistrict.NorthBadlands) || Equals(t, gamedataDistrict.SouthBadlands)
        || Equals(t, gamedataDistrict.MorroRock) {
        return true;
      }
      d = d.ParentDistrict();
      depth += 1;
    }
    return false;
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
