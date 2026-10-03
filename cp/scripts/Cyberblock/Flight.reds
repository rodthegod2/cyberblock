// Elytra glides and wind-charge launches: the script-side policy (plan 2026-10-03-retest2 sections 1.3-1.5, task S3;
// method and evidence: docs/notes/flight-spike.md). While Steve flies, Minecraft's physics move him and the DLL moves V
// after him every rendered frame (cb::drive, from RED4ext's Running-state OnUpdate, right before the camera is
// published): it interpolates the flight samples, clips V against Night City, lands V on the ground below the landing
// spot and is the only writer of cam.fly. Re-test 2 showed why it cannot be a script loop: a DelayCallbackNextFrame
// re-queued from inside its own callback never fires in 2.31, so the old per-frame loop ran one frame and waited for
// the 10 Hz watchdog to restart it, about once per 1.05 s (every flight in the log lasted a whole number of those
// periods), and V and the camera jumped ~17 m at a time.
// What stays here:
//   Tick (10 Hz): whether the DLL may move V at all (Cyberblock_SetFlightAllowed): live, with a player, not in a vehicle
//     and not held by the game (CyberblockEffects.PlayerLocked: a scene, workspot, braindance or moving elevator). A
//     flight Minecraft reports while that is false is named once in the log ('flight: ignored ...'); the DLL then never
//     sets cam.fly, and Minecraft calls the flight off within 0.5 s and puts Steve back at V.
//   Clear: no more moving, and V put on the ground below if the DLL was moving V (F9, Lost, before a save, session end),
//     so leaving Cyberblock in mid-glide does not drop V from the sky.
//   SuppressFall: Minecraft owns the fall rules of its flights. While the DLL drives V, for 2 s after a flight ended
//     (Cyberblock_SecondsSinceFlight) and for 2 s after an ender pearl teleport (Effects.reds), V's landings are never
//     hard, very hard, death or superhero landings (the wraps below), Health.reds reports no fall damage, and no
//     landing instant kill.
//   GroundBelow: the ground under a point, for Clear and for the pearl's ground snap (Effects.Teleport).
//
// 2.31 sources (decompiled 2.31 scripts; compile-checked):
//   VehicleComponent.IsMountedToVehicle, TeleportationFacility.Teleport + Vector4.ToRotation(GetWorldForward()) (vanilla
//   teleports that keep the facing), SpatialQueriesSystem.SyncRaycastByQueryFilter with QueryFilter.AddGroup Static +
//   Terrain (as Probes.reds; our collision boxes answer Static queries too, so Minecraft's blocks count as ground).
//   Landing decisions (locomotionTransitions.swift:3538-3615): LocomotionAirDecisions.ToHardLand / ToVeryHardLand /
//   ToSuperheroLand (protected final const) and ToDeathLand (protected const, overridden by SuperheroFallDecisions,
//   :4095); the hard, very hard and death landings start the Attacks.HardLanding / VeryHardLanding / DeathLanding AoE
//   attacks (:85-101), the superhero landing the GroundSlamFar/Near ones (:4946-4947). ToRegularLand is not wrapped: if
//   in game V hangs in Fall on the ground after a flight, the follow-up is to wrap it as
//   `wrappedMethod(...) || SuppressFall && IsTouchingGround` (an in-game check of the re-test 2 plan).
//   StateGameScriptInterface.GetGame (as locomotionTransitions.swift:463).

public abstract class CyberblockFlight {
  // 10 Hz from CyberblockSystem.Tick. live: Active or Vehicle, not saving.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void {
    let refusal = CyberblockFlight.Refusal(game, player, live);
    let allowed = Equals(refusal, n"");
    Cyberblock_SetFlightAllowed(allowed);
    let service = CyberblockFlightService.Get();
    if !IsDefined(service) {
      return;
    }
    if !Cyberblock_FlightActive() {
      service.ignoredLogged = false;
      return;
    }
    if !allowed && !service.ignoredLogged {
      service.ignoredLogged = true;
      Cyberblock_Log(s"flight: ignored, V may not be moved (\(NameToString(refusal)))");
    }
  }

  // The DLL is moving V this flight (the same value as cam.fly).
  public static func IsFlying(game: GameInstance) -> Bool {
    return Cyberblock_FlightDriving();
  }

  // Where the world around V is: V's feet (the DLL keeps V with the flight).
  public static func Center(game: GameInstance, player: ref<PlayerPuppet>) -> Vector4 {
    return player.GetWorldPosition();
  }

  // Minecraft owns the fall: flying, less than 2 s since a flight ended, or less than 2 s since a Teleport.
  public static func SuppressFall(game: GameInstance) -> Bool {
    if Cyberblock_SecondsSinceFlight() < 2.0 {
      return true;
    }
    let effects = CyberblockEffectsService.Get();
    return IsDefined(effects) && effects.IsFallSuppressed(game);
  }

  // The first surface (Night City's Static + Terrain, and our collision boxes) from 0.5 m above pos down to 1000 m below
  // it; pos itself when there is none.
  public static func GroundBelow(game: GameInstance, pos: Vector4) -> Vector4 {
    let filter: QueryFilter;
    QueryFilter.AddGroup(filter, n"Static");
    QueryFilter.AddGroup(filter, n"Terrain");
    let result: TraceResult;
    let top = new Vector4(pos.X, pos.Y, pos.Z + 0.5, 1.0);
    let bottom = new Vector4(pos.X, pos.Y, pos.Z - 1000.0, 1.0);
    if GameInstance.GetSpatialQueriesSystem(game).SyncRaycastByQueryFilter(top, bottom, filter, result) {
      let hit = Cast<Vector4>(result.position);
      return new Vector4(hit.X, hit.Y, hit.Z, 1.0);
    }
    return pos;
  }

  // No more moving V; if the DLL was moving V, V goes onto the ground below. The DLL reads the cleared gate on its next
  // frame, so FlightDriving still tells whether a flight was under way.
  public static func Clear(game: GameInstance) -> Void {
    Cyberblock_SetFlightAllowed(false);
    if !Cyberblock_FlightDriving() {
      return;
    }
    let player = GetPlayer(game);
    if IsDefined(player) {
      let ground = CyberblockFlight.GroundBelow(game, player.GetWorldPosition());
      GameInstance.GetTeleportationFacility(game).Teleport(player, ground, Vector4.ToRotation(player.GetWorldForward()));
    }
    Cyberblock_Log("flight: cleared in mid-flight, V put on the ground");
  }

  // n"" when the DLL may move V; else why not: n"off" (Off, Lost or a save), n"no_player", n"vehicle", or the lock
  // reason of CyberblockEffects.LockReason (n"workspot", n"braindance", n"elevator", n"scene").
  private static func Refusal(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> CName {
    if !live {
      return n"off";
    }
    if !IsDefined(player) {
      return n"no_player";
    }
    if VehicleComponent.IsMountedToVehicle(game, player) {
      return n"vehicle";
    }
    return CyberblockEffects.LockReason(game, player);
  }
}

// A Codeware service, so the once-per-flight log flag outlives save loads like the DLL's flight state.
public class CyberblockFlightService extends ScriptableService {
  public let ignoredLogged: Bool;

  public static func Get() -> ref<CyberblockFlightService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockFlightService") as CyberblockFlightService;
  }
}

// Landings while Minecraft owns the fall (CyberblockFlight.SuppressFall): never hard, very hard, death or superhero
// landings, so V neither takes nor deals landing damage and is not staggered where Steve landed safely.
@wrapMethod(LocomotionAirDecisions)
protected final const func ToHardLand(const stateContext: ref<StateContext>, const scriptInterface: ref<StateGameScriptInterface>) -> Bool {
  return wrappedMethod(stateContext, scriptInterface) && !CyberblockFlight.SuppressFall(scriptInterface.GetGame());
}

@wrapMethod(LocomotionAirDecisions)
protected final const func ToVeryHardLand(const stateContext: ref<StateContext>, const scriptInterface: ref<StateGameScriptInterface>) -> Bool {
  return wrappedMethod(stateContext, scriptInterface) && !CyberblockFlight.SuppressFall(scriptInterface.GetGame());
}

@wrapMethod(LocomotionAirDecisions)
protected final const func ToSuperheroLand(const stateContext: ref<StateContext>, const scriptInterface: ref<StateGameScriptInterface>) -> Bool {
  return wrappedMethod(stateContext, scriptInterface) && !CyberblockFlight.SuppressFall(scriptInterface.GetGame());
}

@wrapMethod(LocomotionAirDecisions)
protected const func ToDeathLand(const stateContext: ref<StateContext>, const scriptInterface: ref<StateGameScriptInterface>) -> Bool {
  return wrappedMethod(stateContext, scriptInterface) && !CyberblockFlight.SuppressFall(scriptInterface.GetGame());
}

// SuperheroFallDecisions overrides ToDeathLand, so the wrap above does not reach it.
@wrapMethod(SuperheroFallDecisions)
protected const func ToDeathLand(const stateContext: ref<StateContext>, const scriptInterface: ref<StateGameScriptInterface>) -> Bool {
  return wrappedMethod(stateContext, scriptInterface) && !CyberblockFlight.SuppressFall(scriptInterface.GetGame());
}
