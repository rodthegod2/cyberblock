// The mechanic's repair and the medic's heal on the host's Cyberpunk side (megaplan 2026-10-04 tasks 7-S1 and 7-N1: a
// Minecraft mechanic repairs "the host's current Cyberpunk vehicle through a verified call", a medic heals Steve).
//
// Events (Events.reds hands them here whatever the state; each refuses itself unless live):
//   n"repair": the host's current vehicle is repaired: the one V is mounted in, else V's own car nearest to V within
//              30 m (VehicleObject.IsPlayerVehicle; the attached-entity registry of Tether.reds). A destroyed car is
//              refused (a wreck stays a wreck).
//   n"heal":   Steve's health is the only health (Health.reds), so the medic heals Steve in Minecraft; on V's side the
//              damage-over-time effects that would keep hurting Steve through Health.reds (burning, bleeding, poison,
//              shock: every effect tagged DoT) are removed and V's own Health pool is filled.
//   Cyberblock_EventId = the request id (logged only: no result native is named for these in section 1.8).
// Section 1.8 names no Phase 7 event for either (only hub, buy, sell): the DLL queues them once the integrator adds the
// wire message; until then these hooks are reached only by that change (a contract deviation in the 7S report).
//
// The repair is not proven in game, so it is checked at runtime: vanilla's own path is used first, a VehicleRepairEvent
// queued on the car (VehicleComponent.OnVehicleRepairEvent runs VehicleComponent.RepairVehicle). 10 ticks (1 s) later
// the car's Health pool must be at least 99 %; if it is not, the written fallback runs: VehicleComponent.RepairVehicle
// called directly, the destruction grid and glass reset (VehicleObject.DestructionResetGrid/DestructionResetGlass) and
// the Health pool set to its maximum. The heal is checked at once: an effect tagged DoT left after
// StatusEffectHelper.RemoveStatusEffectsWithTag is removed by its record (the fallback).
// Log lines: 'mechanic: repair <id> refused (<why>)', 'mechanic: repair <id> started on <car> (hp N %)',
// 'mechanic: repaired <car> (repair event, hp N %)', 'mechanic: repaired <car> (fallback: direct reset; the repair event
// left hp at N %)', 'medic: heal <id> refused (<why>)', 'medic: healed V (N DoT effects removed[, fallback: by record];
// health pool full)'. Results are also Minecraft toasts.
//
// 2.31 script API (NativeDB, redscript), compile-checked by cp/scripts/compile_check.py: VehicleComponent.GetVehicle(
// GameInstance, wref<GameObject>, out wref<VehicleObject>), VehicleObject.IsPlayerVehicle/IsDestroyed/GetVehicleComponent/
// DestructionResetGrid/DestructionResetGlass, VehicleRepairEvent, Entity.QueueEvent, VehicleComponent.RepairVehicle,
// StatPoolsSystem.GetStatPoolValue(StatsObjectID, gamedataStatPoolType, Bool)/RequestSettingStatPoolMaxValue,
// StatusEffectHelper.RemoveStatusEffectsWithTag(GameObject, CName)/RemoveStatusEffect(GameObject, TweakDBID),
// StatusEffectSystem.GetAppliedEffectsWithTag(EntityID, CName, out array<ref<StatusEffect>>), StatusEffect.GetRecord,
// GameObject.GetDisplayName.

public class CyberblockServicesService extends ScriptableService {
  // A repair waiting for its check: the car and the ticks left (0 none).
  public let car: wref<VehicleObject>;
  public let carName: String;
  public let checkIn: Int32;

  public static func Get() -> ref<CyberblockServicesService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockServicesService") as CyberblockServicesService;
  }
}

public abstract class CyberblockServices {
  // An n"repair" or n"heal" event is current (Cyberblock_NextEvent). live: Active or Vehicle, with a player, not saving.
  public static func OnEvent(game: GameInstance, kind: CName, live: Bool) -> Void {
    let id = Cyberblock_EventId();
    if Equals(kind, n"repair") {
      CyberblockServices.Repair(game, id, live);
    } else {
      CyberblockServices.Heal(game, id, live);
    }
  }

  // "" when the repair started, else why not.
  public static func Repair(game: GameInstance, id: Int32, live: Bool) -> String {
    let s = CyberblockServicesService.Get();
    let player = GetPlayer(game);
    let why = "";
    let car: ref<VehicleObject>;
    if !IsDefined(s) || !IsDefined(player) || !live {
      why = "Cyberblock is not active";
    } else {
      car = CyberblockServices.CurrentCar(game, player);
      if !IsDefined(car) {
        why = "no car of V's within 30 m";
      } else {
        if car.IsDestroyed() {
          why = "the car is destroyed";
        } else {
          if s.checkIn > 0 {
            why = "a repair is still running";
          }
        }
      }
    }
    if StrLen(why) > 0 {
      Cyberblock_Log(s"mechanic: repair \(id) refused (\(why))");
      Cyberblock_Notify(n"warning", "Mechanic", why, 3.0);
      return why;
    }
    s.car = car;
    s.carName = car.GetDisplayName();
    if StrLen(s.carName) == 0 {
      s.carName = NameToString(car.GetClassName());
    }
    s.checkIn = 10;
    Cyberblock_Log(s"mechanic: repair \(id) started on \(s.carName) (hp \(RoundF(CyberblockServices.CarHp(game, car))) %)");
    car.QueueEvent(new VehicleRepairEvent());
    return "";
  }

  // "" when V was healed, else why not.
  public static func Heal(game: GameInstance, id: Int32, live: Bool) -> String {
    let player = GetPlayer(game);
    if !IsDefined(player) || !live {
      let why = "Cyberblock is not active";
      Cyberblock_Log(s"medic: heal \(id) refused (\(why))");
      return why;
    }
    let effects = GameInstance.GetStatusEffectSystem(game);
    let entity = player.GetEntityID();
    let before: array<ref<StatusEffect>>;
    effects.GetAppliedEffectsWithTag(entity, n"DoT", before);
    StatusEffectHelper.RemoveStatusEffectsWithTag(player, n"DoT");
    let left: array<ref<StatusEffect>>;
    effects.GetAppliedEffectsWithTag(entity, n"DoT", left);
    let fallback = ArraySize(left) > 0;
    for effect in left {
      if IsDefined(effect) && IsDefined(effect.GetRecord()) {
        StatusEffectHelper.RemoveStatusEffect(player, effect.GetRecord().GetID());
      }
    }
    GameInstance.GetStatPoolsSystem(game).RequestSettingStatPoolMaxValue(Cast<StatsObjectID>(entity), gamedataStatPoolType.Health, player);
    Cyberblock_Log(s"medic: healed V (\(ArraySize(before)) DoT effects removed\(fallback ? ", fallback: by record" : ""); health pool full)");
    Cyberblock_Notify(n"info", "Medic", "Patched up", 2.0);
    return "";
  }

  // Every CyberblockSystem tick with a player: the repair check (see the header).
  public static func Tick(game: GameInstance) -> Void {
    let s = CyberblockServicesService.Get();
    if !IsDefined(s) || s.checkIn <= 0 {
      return;
    }
    s.checkIn -= 1;
    if s.checkIn > 0 {
      return;
    }
    let car: ref<VehicleObject> = s.car;
    s.car = null;
    if !IsDefined(car) {
      Cyberblock_Log(s"mechanic: repair of \(s.carName) not checked (the car is gone)");
      return;
    }
    let hp = CyberblockServices.CarHp(game, car);
    if hp >= 99.0 {
      Cyberblock_Log(s"mechanic: repaired \(s.carName) (repair event, hp \(RoundF(hp)) %)");
    } else {
      let component = car.GetVehicleComponent();
      if IsDefined(component) {
        component.RepairVehicle();
      }
      car.DestructionResetGrid();
      car.DestructionResetGlass();
      let carId = car.GetEntityID();
      GameInstance.GetStatPoolsSystem(game).RequestSettingStatPoolMaxValue(Cast<StatsObjectID>(carId), gamedataStatPoolType.Health, GetPlayer(game));
      Cyberblock_Log(s"mechanic: repaired \(s.carName) (fallback: direct reset; the repair event left hp at \(RoundF(hp)) %)");
    }
    Cyberblock_Notify(n"info", "Mechanic", s"\(s.carName) repaired", 3.0);
  }

  // On every load and at session end.
  public static func Forget() -> Void {
    let s = CyberblockServicesService.Get();
    if IsDefined(s) {
      s.car = null;
      s.checkIn = 0;
    }
  }

  // The car V is mounted in, else V's own car nearest to V within 30 m; null when none.
  private static func CurrentCar(game: GameInstance, player: ref<PlayerPuppet>) -> ref<VehicleObject> {
    let mounted: wref<VehicleObject>;
    if VehicleComponent.GetVehicle(game, player, mounted) && IsDefined(mounted) {
      return mounted;
    }
    let tether = CyberblockTetherService.Get();
    if !IsDefined(tether) {
      return null;
    }
    let center = player.GetWorldPosition();
    let best: ref<VehicleObject>;
    let bestDistance2 = 30.0 * 30.0;
    for object in tether.Known() {
      let car = object as VehicleObject;
      if IsDefined(car) && car.IsPlayerVehicle() {
        let d2 = Vector4.DistanceSquared(car.GetWorldPosition(), center);
        if d2 <= bestDistance2 {
          best = car;
          bestDistance2 = d2;
        }
      }
    }
    return best;
  }

  // The car's Health pool in percent.
  private static func CarHp(game: GameInstance, car: ref<VehicleObject>) -> Float {
    let carId = car.GetEntityID();
    return GameInstance.GetStatPoolsSystem(game).GetStatPoolValue(Cast<StatsObjectID>(carId), gamedataStatPoolType.Health, true);
  }
}
