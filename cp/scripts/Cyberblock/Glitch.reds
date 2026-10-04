// The glitch hook (megaplan 2026-10-04 tasks 8-S1 and 8-X1; section 1.8 Phase 8 row, section 1.9 `glitch`): a player's
// Reboot Optics on a linked victim. The DLL (8D) runs the ReShade cb_glitch pass over the whole picture; the scripts add
// Cyberpunk's own screen glitch on V for the same time, so the parts of the frame Night City draws glitch too.
//
// Event n"glitch" (Events.reds hands it here whatever the state): Cyberblock_EventAmount = seconds 0..10 (0 ends it).
// Only while live (Cyberblock On, not saving); otherwise it is dropped (logged): the hack is a moment that has passed.
// A new glitch replaces the running one's remaining time. It ends on time, on 0, and whenever Night City stops being
// live, on a non-host session, at session end and on detach (Clear); on load it is forgotten (the effect is not saved).
// Method: GameObjectEffectHelper.StartEffectEvent(V, n"transition_glitch_loop") and StopEffectEvent, the effect vanilla
// plays on V while a scene is fast-forwarded (player.swift 1240-1245, scenesFastForward.swift 77-81). Not checkable from
// scripts: the in-game check looks for it (needsInGame).
// Log lines: 'glitch: <n> s on V (transition_glitch_loop; the ReShade pass is the DLL's)', 'glitch: ended (<why>)',
// 'glitch: <n> s dropped (Cyberblock is not active)'.
//
// 2.31 script API (NativeDB, redscript), compile-checked by cp/scripts/compile_check.py:
// GameObjectEffectHelper.StartEffectEvent(GameObject, CName, opt Bool) / StopEffectEvent(GameObject, CName)
// (orphans.swift 21764-21768).

public class CyberblockGlitchService extends ScriptableService {
  // Ticks (100 ms) left of the running glitch; 0 none.
  public let left: Int32;

  public static func Get() -> ref<CyberblockGlitchService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockGlitchService") as CyberblockGlitchService;
  }
}

public abstract class CyberblockGlitch {
  public static func Effect() -> CName {
    return n"transition_glitch_loop";
  }

  // An n"glitch" event is current (Cyberblock_NextEvent). live: Active or Vehicle, with a player, not saving.
  public static func OnEvent(game: GameInstance, live: Bool) -> Void {
    let s = CyberblockGlitchService.Get();
    let seconds = ClampF(Cyberblock_EventAmount(), 0.0, 10.0);
    let player = GetPlayer(game);
    if !IsDefined(s) || !live || !IsDefined(player) {
      Cyberblock_Log(s"glitch: \(seconds) s dropped (Cyberblock is not active)");
      return;
    }
    if seconds <= 0.0 {
      CyberblockGlitch.Clear(game, "ended by Minecraft");
      return;
    }
    if s.left <= 0 {
      GameObjectEffectHelper.StartEffectEvent(player, CyberblockGlitch.Effect(), false);
    }
    s.left = Max(1, RoundF(seconds * 10.0));
    Cyberblock_Log(s"glitch: \(seconds) s on V (transition_glitch_loop; the ReShade pass is the DLL's)");
  }

  // Every CyberblockSystem tick with a player.
  public static func Tick(game: GameInstance, live: Bool) -> Void {
    let s = CyberblockGlitchService.Get();
    if !IsDefined(s) || s.left <= 0 {
      return;
    }
    if !live {
      CyberblockGlitch.Clear(game, "Cyberblock is not active");
      return;
    }
    s.left -= 1;
    if s.left <= 0 {
      CyberblockGlitch.Stop(game, "time");
    }
  }

  // Ends a running glitch.
  public static func Clear(game: GameInstance, why: String) -> Void {
    let s = CyberblockGlitchService.Get();
    if IsDefined(s) && s.left > 0 {
      CyberblockGlitch.Stop(game, why);
    }
  }

  private static func Stop(game: GameInstance, why: String) -> Void {
    let s = CyberblockGlitchService.Get();
    s.left = 0;
    let player = GetPlayer(game);
    if IsDefined(player) {
      GameObjectEffectHelper.StopEffectEvent(player, CyberblockGlitch.Effect());
    }
    Cyberblock_Log(s"glitch: ended (\(why))");
  }

  // On load: the effect is not saved.
  public static func Forget() -> Void {
    let s = CyberblockGlitchService.Get();
    if IsDefined(s) {
      s.left = 0;
    }
  }
}
