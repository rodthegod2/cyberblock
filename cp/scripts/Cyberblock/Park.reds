// The PARKED state: V left safely in Night City while the player is busy in Minecraft (the Phase 7 hub; megaplan
// 2026-10-04 task 3-S1, section 1.8 amendment item 4, 1.9 `park` / `parked`; Phase 0 check (g)).
//
// Event n"park" (Events.reds hands it here whatever the state): Cyberblock_EventFlag = on.
//   on:  V is made unseen by NPCs (PlayerPuppet.SetInvisible), invulnerable (god mode source n"CyberblockPark"), saving is
//        locked (SaveLocksManager, n"CyberblockPark"), and movement and camera are locked (status effects
//        GameplayRestriction.NoMovement and GameplayRestriction.NoCameraControl). Refused (logged) without a player,
//        while Cyberblock is not active, in a vehicle, dead, during a teleport or the Atlas survey tour.
//   off: everything undone, V's own invisibility as it was. Also when Cyberblock stops being On (F9, Minecraft lost),
//        at session end and on detach, so V is never left frozen.
// The CET lines G5/G6 were not run, so each step is feature-checked at runtime: SetInvisible must leave IsInvisible true,
// the god mode source must show in GetGodModeSources, the SaveLocksManager system must exist, and each status effect
// record must exist and apply. If any step is unavailable, the written fallback (plan 4.1 g) is used: V goes to the
// nearest fast-travel point (TeleportationFacility.TeleportToNode on its marker, as vanilla fast travel does) and stays
// there invisible and invulnerable; park off then brings V back to where V was parked.
// One log line per change: 'park: on (<what was applied>)', 'park: on (fallback: ...; unavailable: ...)',
// 'park: on refused (<why>)', 'park: off (<reason>)'. Minecraft's `parked` answer is the DLL's (no native is named for it
// in section 1.8); the log line is the evidence.
//
// 2.31 script API (NativeDB, redscript): PlayerPuppet.SetInvisible/IsInvisible (player.swift 3854-3858),
// GodModeSystem.AddGodMode/RemoveGodMode/GetGodModeSources(EntityID, gameGodModeType[, CName]) (orphans.swift),
// SaveLocksManager.RequestSaveLockAdd/RequestSaveLockRemove (saveLocksManager.swift), ScriptableSystemsContainer.Get,
// TweakDBInterface.GetStatusEffectRecord(TweakDBID), StatusEffectHelper.ApplyStatusEffect/RemoveStatusEffect
// (statusEffectSystem.swift 4, 47), FastTravelSystem.GetFastTravelPoints, FastTravelPointData.mappinID/GetMarkerRef/
// GetPointDisplayName (fastTravelSystem.swift), MappinSystem.GetMappin + IMappin.GetWorldPosition (as Survey.reds),
// TeleportationFacility.Teleport/TeleportToNode(GameObject, NodeRef), GetLocalizedText, VehicleComponent.IsMountedToVehicle.

public class CyberblockPark {
  private let parked: Bool;
  // What on applied, so off undoes exactly that.
  private let wasInvisible: Bool;
  private let effects: array<TweakDBID>;
  private let saveLocked: Bool;
  // The fallback moved V to a fast-travel point from here.
  private let fallback: Bool;
  private let home: Vector4;
  private let homeYaw: Float;

  public static func Parked(game: GameInstance) -> Bool {
    let system = CyberblockSystem.Get(game);
    return IsDefined(system) && system.Park().parked;
  }

  // An n"park" event is current (Cyberblock_NextEvent). live: Active or Vehicle, with a player, not saving.
  public func OnEvent(game: GameInstance, live: Bool) -> Void {
    if Cyberblock_EventFlag() {
      this.On(game, live);
    } else {
      this.Release(game, "requested");
    }
  }

  private func On(game: GameInstance, live: Bool) -> Void {
    if this.parked {
      Cyberblock_Log("park: on (already parked)");
      return;
    }
    let player = GetPlayer(game);
    let refusal = this.Refusal(game, player, live);
    if StrLen(refusal) > 0 {
      Cyberblock_Log(s"park: on refused (\(refusal))");
      return;
    }
    this.parked = true;
    this.fallback = false;
    this.home = player.GetWorldPosition();
    this.homeYaw = Vector4.ToRotation(player.GetWorldForward()).Yaw;
    let missing: array<String>;

    this.wasInvisible = player.IsInvisible();
    player.SetInvisible(true);
    if !player.IsInvisible() {
      ArrayPush(missing, "unseen");
    }

    let id = player.GetEntityID();
    let gods = GameInstance.GetGodModeSystem(game);
    gods.AddGodMode(id, gameGodModeType.Invulnerable, n"CyberblockPark");
    let sources = gods.GetGodModeSources(id, gameGodModeType.Invulnerable);
    if !ArrayContains(sources, n"CyberblockPark") {
      ArrayPush(missing, "invulnerable");
    }

    if IsDefined(GameInstance.GetScriptableSystemsContainer(game).Get(n"SaveLocksManager")) {
      SaveLocksManager.RequestSaveLockAdd(game, n"CyberblockPark");
      this.saveLocked = true;
    } else {
      ArrayPush(missing, "save lock");
    }

    this.Restrict(player, t"GameplayRestriction.NoMovement", "no movement", missing);
    this.Restrict(player, t"GameplayRestriction.NoCameraControl", "no camera control", missing);

    if ArraySize(missing) == 0 {
      Cyberblock_Log("park: on (invisible, invulnerable, saving locked, movement and camera locked)");
      return;
    }
    let unavailable = CyberblockPark.Join(missing);
    let name = "";
    let distance = 0.0;
    let point = CyberblockPark.NearestFastTravel(game, this.home, name, distance);
    if !IsDefined(point) {
      Cyberblock_Log(s"park: on (fallback: no fast-travel point known, V stays here invisible and invulnerable; unavailable: \(unavailable))");
      return;
    }
    this.fallback = true;
    CyberblockEffectsService.Get().SuppressFall(game, 3.0);
    GameInstance.GetTeleportationFacility(game).TeleportToNode(player, point.GetMarkerRef());
    Cyberblock_Log(s"park: on (fallback: nearest fast-travel point \(name), \(RoundF(distance)) m away, invisible and invulnerable; unavailable: \(unavailable))");
  }

  // Undoes on (`reason` for the log: requested, Cyberblock off, Minecraft lost, session end, detach).
  public func Release(game: GameInstance, reason: String) -> Void {
    if !this.parked {
      if Equals(reason, "requested") {
        Cyberblock_Log("park: off (not parked)");
      }
      return;
    }
    this.parked = false;
    let player = GetPlayer(game);
    let back = false;
    if IsDefined(player) {
      for effect in this.effects {
        StatusEffectHelper.RemoveStatusEffect(player, effect);
      }
      GameInstance.GetGodModeSystem(game).RemoveGodMode(player.GetEntityID(), gameGodModeType.Invulnerable, n"CyberblockPark");
      player.SetInvisible(this.wasInvisible);
      if this.fallback {
        CyberblockEffectsService.Get().SuppressFall(game, 3.0);
        GameInstance.GetTeleportationFacility(game).Teleport(player, this.home, CyberblockEffects.Facing(this.homeYaw));
        back = true;
      }
    }
    ArrayClear(this.effects);
    if this.saveLocked {
      SaveLocksManager.RequestSaveLockRemove(game, n"CyberblockPark");
      this.saveLocked = false;
    }
    this.fallback = false;
    Cyberblock_Log(s"park: off (\(reason)\(back ? ", V back from the fast-travel point" : ""))");
  }

  // On every load: a park of the previous session is forgotten (Release ran at its end).
  public func Forget() -> Void {
    this.parked = false;
    this.fallback = false;
    this.saveLocked = false;
    ArrayClear(this.effects);
  }

  private func Refusal(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> String {
    if !IsDefined(player) {
      return "no player";
    }
    if !live {
      return "Cyberblock is not active";
    }
    if player.IsDead() {
      return "dead";
    }
    if VehicleComponent.IsMountedToVehicle(game, player) {
      return "in a vehicle";
    }
    if CyberblockTeleport.Busy(game) {
      return "teleporting";
    }
    if CyberblockSurveyTour.Running(game) {
      return "survey tour";
    }
    return "";
  }

  // Applies one restriction status effect, remembered for off; `label` goes to `missing` when it is unavailable.
  private func Restrict(player: ref<PlayerPuppet>, effect: TweakDBID, label: String, out missing: array<String>) -> Void {
    if !IsDefined(TweakDBInterface.GetStatusEffectRecord(effect)) || !StatusEffectHelper.ApplyStatusEffect(player, effect) {
      ArrayPush(missing, label);
      return;
    }
    ArrayPush(this.effects, effect);
  }

  // The known fast-travel point nearest to `from` (null when none), with its display name and distance.
  private static func NearestFastTravel(game: GameInstance, from: Vector4, out name: String, out distance: Float) -> ref<FastTravelPointData> {
    let travel = GameInstance.GetScriptableSystemsContainer(game).Get(n"FastTravelSystem") as FastTravelSystem;
    if !IsDefined(travel) {
      return null;
    }
    let mappins = GameInstance.GetMappinSystem(game);
    let best: ref<FastTravelPointData>;
    for point in travel.GetFastTravelPoints() {
      let mappin = IsDefined(point) ? mappins.GetMappin(point.mappinID) : null;
      if IsDefined(mappin) {
        let d = Vector4.Distance(from, mappin.GetWorldPosition());
        if !IsDefined(best) || d < distance {
          best = point;
          distance = d;
        }
      }
    }
    if IsDefined(best) {
      name = GetLocalizedText(best.GetPointDisplayName());
    }
    return best;
  }

  private static func Join(parts: array<String>) -> String {
    let text = "";
    for part in parts {
      text += StrLen(text) > 0 ? s", \(part)" : part;
    }
    return text;
  }
}
