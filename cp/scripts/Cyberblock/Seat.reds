// Where V sits in a vehicle, for Steve's seated body in Minecraft (re-test 3 plan 2026-10-03-retest3-polish sections
// 1.2 and 1.4, task S4; F3 "Steve stays visible in vehicles"). CyberblockSystem (Cyberblock.reds) calls Tick at 10 Hz
// and Clear on every Clear path. While V is mounted and Cyberblock is on, Tick sends Cyberblock_SetSeat(true, tpp,
// hipsLocal): tpp = the vehicle camera is third person, hipsLocal = V's Hips slot in V's local axes (Y forward, Z up,
// metres from V's position), which the DLL turns back into a world point with V's orientation and sends as cam.pl with
// cam.seat. A listener on UI_ActiveVehicleData.IsTPPCameraOn (registered on mount) sends the switch between first and
// third person at once instead of on the next tick. Without a Hips slot the hips are taken 0.45 m above V's position
// ('seat: no Hips slot', once). Each mount logs 'seat: slot <slot> hips (<x>, <y>, <z>) tpp <b>' once.
//
// 2.31 sources (decompiled 2.31 scripts; compile-checked):
//   GetMountedVehicle(object) (vehicles.swift:1489, the mounting facility's parent of V); VehicleObject.GetCameraManager()
//     .IsTPPActive() (VehicleCameraManager, native);
//   UI_ActiveVehicleData.IsTPPCameraOn: IBlackboard.RegisterListenerBool / UnregisterListenerBool on the blackboard
//     BlackboardSystem.Get(GetAllBlackboardDefs().UI_ActiveVehicleData), as VehicleComponent.SetupVehicleTPPBBListener
//     (vehicleComponent.swift:4236-4254) and car_hud.swift do;
//   ScriptedPuppet.GetSlotComponent().GetSlotTransform(slot, out WorldTransform) -> Bool (SlotComponent, native),
//     WorldPosition.ToVector4(WorldTransform.GetWorldPosition(wt)) as aiActionHelper.swift does;
//   Entity.GetWorldOrientation (Quaternion), Quaternion.TransformInverse(quat, v) (native).

public class CyberblockSeatService extends ScriptableService {
  public let seated: Bool;
  public let player: wref<PlayerPuppet>;
  public let tpp: Bool;
  public let listener: ref<CallbackHandle>;
  public let loggedMount: Bool;
  public let loggedNoHips: Bool;

  public static func Get() -> ref<CyberblockSeatService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockSeatService") as CyberblockSeatService;
  }

  public func Listen(game: GameInstance) -> Void {
    if IsDefined(this.listener) {
      return;
    }
    let board = GameInstance.GetBlackboardSystem(game).Get(GetAllBlackboardDefs().UI_ActiveVehicleData);
    if IsDefined(board) {
      this.listener = board.RegisterListenerBool(GetAllBlackboardDefs().UI_ActiveVehicleData.IsTPPCameraOn, this, n"OnTppCameraChanged");
    }
  }

  public func Unlisten(game: GameInstance) -> Void {
    if !IsDefined(this.listener) {
      return;
    }
    let board = GameInstance.GetBlackboardSystem(game).Get(GetAllBlackboardDefs().UI_ActiveVehicleData);
    if IsDefined(board) {
      board.UnregisterListenerBool(GetAllBlackboardDefs().UI_ActiveVehicleData.IsTPPCameraOn, this.listener);
    }
    this.listener = null;
  }

  // The vehicle camera switched: tell the DLL now.
  protected cb func OnTppCameraChanged(on: Bool) -> Bool {
    let player = this.player;
    if this.seated && IsDefined(player) && NotEquals(on, this.tpp) {
      this.tpp = on;
      Cyberblock_SetSeat(true, on, CyberblockSeat.Hips(player, this));
    }
    return true;
  }
}

public abstract class CyberblockSeat {
  // 10 Hz. on: Cyberblock is on (Active or Vehicle) for this player.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, on: Bool) -> Void {
    let seat = CyberblockSeatService.Get();
    if !IsDefined(seat) {
      return;
    }
    let vehicle: wref<VehicleObject>;
    if on && IsDefined(player) {
      vehicle = GetMountedVehicle(player);
    }
    if !IsDefined(vehicle) {
      if seat.seated {
        CyberblockSeat.Clear(game);
      }
      return;
    }
    if !seat.seated {
      seat.seated = true;
      seat.loggedMount = false;
      seat.Listen(game);
    }
    seat.player = player;
    let cameras = vehicle.GetCameraManager();
    seat.tpp = IsDefined(cameras) && cameras.IsTPPActive();
    let hips = CyberblockSeat.Hips(player, seat);
    Cyberblock_SetSeat(true, seat.tpp, hips);
    // Steve faces where the car faces: V's own forward does not turn with the car while mounted (in-game 2026-10-03:
    // seated Steve faced the camera).
    Cyberblock_SetSeatForward(vehicle.GetWorldForward());
  }

  // The world point of V's seat: the vehicle's slot for the mounting slot V uses (seat_front_left, ...). In-game
  // 2026-10-03 V had no Hips slot while mounted and V's own position is not at the seat, so Steve sat on the roof.
  private static func SeatPoint(player: ref<PlayerPuppet>, out point: Vector4) -> Bool {
    let vehicle = GetMountedVehicle(player);
    if !IsDefined(vehicle) {
      return false;
    }
    let mount = GameInstance.GetMountingFacility(player.GetGame()).GetMountingInfoSingleWithObjects(player);
    let slots = vehicle.FindComponentByName(n"vehicle_slots") as SlotComponent;
    let transform: WorldTransform;
    if !IsDefined(slots) || !slots.GetSlotTransform(mount.slotId.id, transform) {
      return false;
    }
    point = WorldPosition.ToVector4(WorldTransform.GetWorldPosition(transform));
    return true;
  }

  public static func Clear(game: GameInstance) -> Void {
    let seat = CyberblockSeatService.Get();
    if IsDefined(seat) {
      seat.Unlisten(game);
      seat.seated = false;
      seat.tpp = false;
      seat.player = null;
    }
    Cyberblock_SetSeat(false, false, new Vector4(0.0, 0.0, 0.0, 0.0));
  }

  // V's Hips slot in V's local axes (Y forward, Z up), metres from V's position; 0.45 m up without the slot.
  public static func Hips(player: ref<PlayerPuppet>, seat: ref<CyberblockSeatService>) -> Vector4 {
    let slot = "Hips";
    let local = new Vector4(0.0, 0.0, 0.45, 0.0);
    let slots = player.GetSlotComponent();
    let transform: WorldTransform;
    let seatPoint: Vector4;
    if CyberblockSeat.SeatPoint(player, seatPoint) {
      slot = "vehicle seat";
      let offset = seatPoint - player.GetWorldPosition();
      local = Quaternion.TransformInverse(player.GetWorldOrientation(), new Vector4(offset.X, offset.Y, offset.Z, 0.0));
      local.W = 0.0;
    } else if IsDefined(slots) && slots.GetSlotTransform(n"Hips", transform) {
      let world = WorldPosition.ToVector4(WorldTransform.GetWorldPosition(transform));
      let offset = world - player.GetWorldPosition();
      local = Quaternion.TransformInverse(player.GetWorldOrientation(), new Vector4(offset.X, offset.Y, offset.Z, 0.0));
      local.W = 0.0;
    } else {
      slot = "fallback";
      if !seat.loggedNoHips {
        seat.loggedNoHips = true;
        Cyberblock_Log("seat: no Hips slot");
      }
    }
    if !seat.loggedMount {
      seat.loggedMount = true;
      Cyberblock_Log(s"seat: slot \(slot) hips (\(local.X), \(local.Y), \(local.Z)) tpp \(seat.tpp)");
    }
    return local;
  }
}
