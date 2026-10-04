// When the DLL may put the camera behind V (re-test 3 plan 2026-10-03-retest3-polish sections 1.2 and 1.4, task S5;
// F2 third-person view). The DLL owns the third-person offset (Minecraft's F5 sends `persp`); this gate tells it when
// the offset may be applied: Cyberblock is Active on foot, V is not held (a vehicle, a workspot, a quest scene, an
// elevator: CyberblockSystem's `held`), no Cyberpunk menu is open, and no braindance or photo mode runs (both drive
// the camera themselves). CyberblockSystem (Cyberblock.reds) calls Tick at 10 Hz and Clear on every Clear path; the
// gate is sent every tick, so a missed change heals within 100 ms.
// Megaplan 2026-10-04 Q-S3: the gate is closed with a reason (vehicle, off, menu, braindance, photo mode, workspot,
// elevator, scene, held) and opens as "open"; every change logs 'view: gate <reason>'. While V is mounted the vehicle
// camera is probed for Q-D1: its active perspective (FPP, TPPClose, TPPMedium, TPPFar, DriverCombatClose/Medium/Far)
// is logged on change as 'view: vehicle camera <perspective> (tpp <b>)'. The 2.31 vehicle camera has no script-settable
// distance: VehicleCameraManager exposes IsTPPActive, GetActivePerspective and GetPersistentPerspective only, and the
// game picks among its three third-person presets by queueing vehicleRequestCameraPerspectiveEvent on the player
// (DefaultTransition.RequestVehicleCameraPerspective, defaultTransition.swift:1649).
//
// 2.31 script API (NativeDB, redscript), compile-checked: BraindanceSystem.GetIsInBraindance, fetched from the
// scriptable systems container as PlayerPuppet does (and CyberblockEffects.LockReason); GameInstance.GetPhotoModeSystem
// (game).IsPhotoModeActive() (gameObject.swift:2255, playerVisionModeController.swift:178);
// VehicleComponent.IsMountedToVehicle; GetMountedVehicle(object) (vehicles.swift:1489); VehicleObject.GetCameraManager()
// .IsTPPActive() / .GetActivePerspective() -> vehicleCameraPerspective (orphans.swift, as equipment.swift:739 and
// vehicleTransition.swift:449 read it).

public class CyberblockViewService extends ScriptableService {
  public let gate: String;          // the last reason logged ("" before the first)
  public let perspective: Int32;    // the last vehicle camera perspective logged, while perspectiveKnown
  public let perspectiveKnown: Bool;

  public static func Get() -> ref<CyberblockViewService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockViewService") as CyberblockViewService;
  }
}

public abstract class CyberblockView {
  // 10 Hz. active: Cyberblock is Active on foot; held: V is held by a vehicle or the game; menu: a Cyberpunk menu is open.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, active: Bool, held: Bool, menu: Bool) -> Void {
    let reason = CyberblockView.Gate(game, player, active, held, menu);
    Cyberblock_SetViewAllowed(Equals(reason, "open"));
    CyberblockView.Note(reason);
    CyberblockView.ProbeVehicleCamera(player);
  }

  public static func Clear(game: GameInstance) -> Void {
    Cyberblock_SetViewAllowed(false);
    CyberblockView.Note("off");
  }

  // "open" when third person may be applied, else why not.
  private static func Gate(game: GameInstance, player: ref<PlayerPuppet>, active: Bool, held: Bool, menu: Bool) -> String {
    if !IsDefined(player) {
      return "no player";
    }
    if VehicleComponent.IsMountedToVehicle(game, player) {
      return "vehicle";
    }
    if !active {
      return "off";
    }
    if menu {
      return "menu";
    }
    let braindance = GameInstance.GetScriptableSystemsContainer(game).Get(n"BraindanceSystem") as BraindanceSystem;
    if IsDefined(braindance) && braindance.GetIsInBraindance() {
      return "braindance";
    }
    let photoMode = GameInstance.GetPhotoModeSystem(game);
    if IsDefined(photoMode) && photoMode.IsPhotoModeActive() {
      return "photo mode";
    }
    let lock = CyberblockEffects.LockReason(game, player); // workspot, elevator, scene
    if NotEquals(lock, n"") {
      return NameToString(lock);
    }
    return held ? "held" : "open";
  }

  private static func Note(reason: String) -> Void {
    let service = CyberblockViewService.Get();
    if IsDefined(service) && NotEquals(service.gate, reason) {
      service.gate = reason;
      Cyberblock_Log(s"view: gate \(reason)");
    }
  }

  // Q-D1's vehicle camera probe: the active perspective of the vehicle V sits in, logged on change.
  private static func ProbeVehicleCamera(player: ref<PlayerPuppet>) -> Void {
    let service = CyberblockViewService.Get();
    if !IsDefined(service) {
      return;
    }
    let vehicle: wref<VehicleObject>;
    if IsDefined(player) {
      vehicle = GetMountedVehicle(player);
    }
    let cameras: ref<VehicleCameraManager>;
    if IsDefined(vehicle) {
      cameras = vehicle.GetCameraManager();
    }
    if !IsDefined(cameras) {
      service.perspectiveKnown = false;
      return;
    }
    let perspective = EnumInt(cameras.GetActivePerspective());
    if !service.perspectiveKnown || service.perspective != perspective {
      service.perspectiveKnown = true;
      service.perspective = perspective;
      let tpp = cameras.IsTPPActive();
      Cyberblock_Log(s"view: vehicle camera \(CyberblockView.PerspectiveName(perspective)) (tpp \(tpp))");
    }
  }

  private static func PerspectiveName(perspective: Int32) -> String {
    switch perspective {
      case 0:
        return "FPP";
      case 1:
        return "TPPClose";
      case 2:
        return "TPPMedium";
      case 3:
        return "TPPFar";
      case 4:
        return "DriverCombatClose";
      case 5:
        return "DriverCombatMedium";
      case 6:
        return "DriverCombatFar";
      default:
        return s"perspective \(perspective)";
    }
  }
}
