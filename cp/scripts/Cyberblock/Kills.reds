// Night City deaths caused by Cyberblock (plan 2026-10-03 sections 1.2 killed and 1.4, task S3.5): when an NPC dies or
// a vehicle is destroyed after V hurt it, Minecraft gets Cyberblock_Killed(hash, vehicle, hostile) once per entity (NPC
// kill XP, Cold Blood). V is the instigator of everything Minecraft does to Night City (Effects.reds), and V's own
// combat is off while Active, so "V hurt it" means Steve did. Nothing is reported unless Cyberblock's god mode is on V
// (the audit's Active test), so V's own kills outside Cyberblock never count.
//
// 2.31 sources (decompiled 2.31 scripts; compile-checked):
//   NPC death: NPCPuppet.OnDeath(evt: ref<gameDeathEvent>) (protected cb; calls ScriptedPuppet.OnDeath ->
//     HandleDeathByTask(evt.instigator)); gameDeathEvent.instigator. NPCPuppet.GetMyKiller (set from the instigator in
//     OnPotentialDeath).
//   Vehicle destruction: VehicleComponent.OnDeath(evt: ref<gameDeathEvent>) (protected cb: siren off, ExplodeVehicle(
//     evt.instigator), FindAndRewardKiller, ...); VehicleComponent.GetVehicle (protected final const).
//   "V hurt it": the death event's instigator is V, or V is in the target's damage history of the killing frame
//     (GameObject.GetReceivedDamageByPlayerLastTimeStamp > 0: ProcessDamageReceived keeps only the current frame's
//     hits, the same history FindAndRewardKiller picks the killer from).
//   Active test: GodModeSystem.GetGodModeSources(V, Invulnerable) contains n"Cyberblock" (PlayerState.reds source).
//   Hostile: IsHostileTowardsPlayer (player.swift), for vehicles of the mounted driver (VehicleComponent
//     .GetDriverMounted). Hash: Codeware EntityID.ToHash.

public class CyberblockKillsService extends ScriptableService {
  // Entities already reported (bounded: the most recent 64), so each death is sent once.
  public let reported: array<Uint64>;

  public static func Get() -> ref<CyberblockKillsService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockKillsService") as CyberblockKillsService;
  }
}

public abstract class CyberblockKills {
  // Called from the death handlers below.
  public static func Died(victim: ref<GameObject>, instigator: wref<GameObject>, killer: wref<GameObject>, vehicle: Bool, hostile: Bool) -> Void {
    if !IsDefined(victim) {
      return;
    }
    let game = victim.GetGame();
    let player = GetPlayer(game);
    if !IsDefined(player) || !CyberblockKills.Active(game, player) {
      return;
    }
    let byV = instigator == player || killer == player || victim.GetReceivedDamageByPlayerLastTimeStamp() > 0.0;
    if !byV {
      return;
    }
    let kills = CyberblockKillsService.Get();
    let hash = EntityID.ToHash(victim.GetEntityID());
    if !IsDefined(kills) || ArrayContains(kills.reported, hash) {
      return;
    }
    if ArraySize(kills.reported) >= 64 {
      ArrayErase(kills.reported, 0);
    }
    ArrayPush(kills.reported, hash);
    Cyberblock_Killed(hash, vehicle, hostile);
    Cyberblock_Log(s"killed \(vehicle ? "vehicle" : "human") \(hash)\(hostile ? " (hostile)" : "")");
  }

  private static func Active(game: GameInstance, player: ref<PlayerPuppet>) -> Bool {
    let sources = GameInstance.GetGodModeSystem(game).GetGodModeSources(player.GetEntityID(), gameGodModeType.Invulnerable);
    return ArrayContains(sources, n"Cyberblock");
  }
}

@wrapMethod(NPCPuppet)
protected cb func OnDeath(evt: ref<gameDeathEvent>) -> Bool {
  let result = wrappedMethod(evt);
  CyberblockKills.Died(this, evt.instigator, this.GetMyKiller(), false, IsHostileTowardsPlayer(this));
  return result;
}

@wrapMethod(VehicleComponent)
protected cb func OnDeath(evt: ref<gameDeathEvent>) -> Bool {
  let vehicle = this.GetVehicle();
  let hostile = false;
  if IsDefined(vehicle) {
    hostile = IsHostileTowardsPlayer(VehicleComponent.GetDriverMounted(vehicle.GetGame(), vehicle.GetEntityID()));
  }
  let result = wrappedMethod(evt);
  if IsDefined(vehicle) {
    CyberblockKills.Died(vehicle, evt.instigator, null, true, hostile);
  }
  return result;
}
