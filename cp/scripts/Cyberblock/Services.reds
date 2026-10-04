// The mechanic's repair of the host's Cyberpunk car (megaplan 2026-10-04 tasks 7-S1 and 7-N1: a Minecraft mechanic
// repairs "the host's current Cyberpunk vehicle through a verified call").
//
// NOT WIRED (Phase 7 descope, megaplan Phase 7 "Descopes and risks", 7S fix amendment): sections 1.8 and 1.9 name no
// `repair` wire message, the DLL has no `repair` event kind and no result native, so nothing queues n"repair" and
// Minecraft's Garage greys the host car repair out ("your Cyberpunk game cannot confirm a car repair"). This hook is the
// Cyberpunk half, ready for when the integrator adds the contract: a DOWN `repair {id}`, the DLL event kind, a result
// back to Minecraft so Garage can confirm it and charge (section 1.16), and the server-side permission and rate limit.
// The script itself charges nothing and only refuses a second repair while one is being checked. The medic needs no
// Cyberpunk hook: 7-N1's medic heals Steve for eddies on the Minecraft side (Steve's health is the only health).
//
// Event n"repair" (Events.reds hands it here whatever the state; refused unless live): the host's current vehicle is
// repaired: the one V is mounted in, else V's own car nearest to V within 30 m (VehicleObject.IsPlayerVehicle; the
// attached-entity registry of Tether.reds). A destroyed car is refused (a wreck stays a wreck).
// Cyberblock_EventId = the request id (logged only).
//
// The repair is not proven in game, so it is checked at runtime: vanilla's own path is used first, a VehicleRepairEvent
// queued on the car (VehicleComponent.OnVehicleRepairEvent runs VehicleComponent.RepairVehicle). 10 ticks (1 s) later
// the car's Health pool must be at least 99 %; if it is not, the written fallback runs: VehicleComponent.RepairVehicle
// called directly, the destruction grid and glass reset (VehicleObject.DestructionResetGrid/DestructionResetGlass) and
// the Health pool set to its maximum.
// Log lines (the in-game check once wired): 'mechanic: repair <id> refused (<why>)', 'mechanic: repair <id> started on
// <car> (hp N %)', then 'mechanic: repaired <car> (repair event, hp N %)' (vanilla path) or 'mechanic: repaired <car>
// (fallback: direct reset; the repair event left hp at N %)'. Results are also Minecraft toasts.
//
// 2.31 script API (NativeDB, redscript), compile-checked by cp/scripts/compile_check.py: VehicleComponent.GetVehicle(
// GameInstance, wref<GameObject>, out wref<VehicleObject>), VehicleObject.IsPlayerVehicle/IsDestroyed/GetVehicleComponent/
// DestructionResetGrid/DestructionResetGlass, VehicleRepairEvent, Entity.QueueEvent, VehicleComponent.RepairVehicle,
// StatPoolsSystem.GetStatPoolValue(StatsObjectID, gamedataStatPoolType, Bool)/RequestSettingStatPoolMaxValue,
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
  // An n"repair" event is current (Cyberblock_NextEvent). live: Active or Vehicle, with a player, not saving.
  public static func OnEvent(game: GameInstance, live: Bool) -> Void {
    CyberblockServices.Repair(game, Cyberblock_EventId(), live);
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
