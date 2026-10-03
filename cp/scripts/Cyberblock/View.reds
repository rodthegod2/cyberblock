// When the DLL may put the camera behind V (re-test 3 plan 2026-10-03-retest3-polish sections 1.2 and 1.4, task S5;
// F2 third-person view). The DLL owns the third-person offset (Minecraft's F5 sends `persp`); this gate tells it when
// the offset may be applied: Cyberblock is Active on foot, V is not held (a vehicle, a workspot, a quest scene, an
// elevator: CyberblockSystem's `held`), no Cyberpunk menu is open, and no braindance or photo mode runs (both drive
// the camera themselves). CyberblockSystem (Cyberblock.reds) calls Tick at 10 Hz and Clear on every Clear path; the
// gate is sent every tick, so a missed change heals within 100 ms.
//
// 2.31 sources (decompiled 2.31 scripts; compile-checked): BraindanceSystem.GetIsInBraindance, fetched from the
// scriptable systems container as PlayerPuppet does (and CyberblockEffects.LockReason); GameInstance.GetPhotoModeSystem
// (game).IsPhotoModeActive() (gameObject.swift:2255, playerVisionModeController.swift:178);
// VehicleComponent.IsMountedToVehicle.

public abstract class CyberblockView {
  // 10 Hz. active: Cyberblock is Active on foot; held: V is held by a vehicle or the game; menu: a Cyberpunk menu is open.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, active: Bool, held: Bool, menu: Bool) -> Void {
    Cyberblock_SetViewAllowed(active && !held && !menu && IsDefined(player) && !CyberblockView.CameraTaken(game, player));
  }

  public static func Clear(game: GameInstance) -> Void {
    Cyberblock_SetViewAllowed(false);
  }

  // Something else drives the camera: a braindance, photo mode or a vehicle (its own native cameras).
  public static func CameraTaken(game: GameInstance, player: ref<PlayerPuppet>) -> Bool {
    let braindance = GameInstance.GetScriptableSystemsContainer(game).Get(n"BraindanceSystem") as BraindanceSystem;
    if IsDefined(braindance) && braindance.GetIsInBraindance() {
      return true;
    }
    let photoMode = GameInstance.GetPhotoModeSystem(game);
    if IsDefined(photoMode) && photoMode.IsPhotoModeActive() {
      return true;
    }
    return VehicleComponent.IsMountedToVehicle(game, player);
  }
}
