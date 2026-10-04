// The Afterlife hub on the Cyberpunk side (megaplan 2026-10-04 tasks 7-S1 and 7-H1; section 1.8 Phase 7 row, 1.9 `hub`;
// Phase 0 check (g)). While the host's Steve is in the hub, Minecraft is shown full screen (the DLL's loading-cover path)
// and V waits in Night City PARKED: the Phase 3 park of Park.reds (unseen by NPCs, invulnerable, saving locked, no
// movement or camera; its written (g) fallback included), reused as it is.
//
// Event n"hub" (Events.reds hands it here whatever the state): Cyberblock_EventFlag = in.
//   in:  the hub is remembered and V is parked. When V cannot be parked yet (Cyberblock not On, in a vehicle, a
//        teleport or the survey tour running, dead), Tick tries again every 1 s while the hub lasts; the reason is
//        logged once per change.
//   out: V is released ('park: off (hub exit)'), whoever parked V (a separate `park` that came with the hub too).
// The hub is forgotten (V released by the same paths) when this host stops being host (Session.reds), on every load,
// at session end, on Minecraft lost (Cyberblock.reds) and on any tick the link is down (also while Off): the DLL forgets
// its hub at every link drop and hello and tells the scripts nothing, and 7H says `hub in` again after the next hello
// while Steve is still in the hub, so a stale hub never parks V again. Cyberblock going Off releases V as for any park
// (Cyberblock.reds) but keeps the hub (the link and the DLL's hub are unchanged), so V is parked again at On.
// Log lines: 'hub: in (parking V)', 'hub: V parked', 'hub: V not parked yet (<why>); trying every 1 s',
// 'hub: out (V released)', 'hub: out (was not in the hub)', 'hub: forgotten (<reason>)'.
//
// 2.31 script API (NativeDB, redscript): only CyberblockPark (Park.reds: WhyNot, Hold, Release, Parked) and the natives
// (Cyberblock_IsLinked, Cyberblock_EventFlag, Cyberblock_Log); Codeware ScriptableService for the state.

public class CyberblockHubService extends ScriptableService {
  public let inHub: Bool;
  // The last reason V could not be parked ("" none) and the ticks until the next try.
  public let why: String;
  public let wait: Int32;

  public static func Get() -> ref<CyberblockHubService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockHubService") as CyberblockHubService;
  }
}

public abstract class CyberblockHub {
  public static func In() -> Bool {
    let s = CyberblockHubService.Get();
    return IsDefined(s) && s.inHub;
  }

  // An n"hub" event is current (Cyberblock_NextEvent). live: Active or Vehicle, with a player, not saving.
  public static func OnEvent(game: GameInstance, live: Bool) -> Void {
    let s = CyberblockHubService.Get();
    let system = CyberblockSystem.Get(game);
    if !IsDefined(s) || !IsDefined(system) {
      return;
    }
    if Cyberblock_EventFlag() {
      s.inHub = true;
      s.why = "";
      s.wait = 0;
      Cyberblock_Log("hub: in (parking V)");
      CyberblockHub.TryPark(game, live, s, system);
      return;
    }
    let was = s.inHub;
    s.inHub = false;
    s.why = "";
    if CyberblockPark.Parked(game) {
      system.Park().Release(game, "hub exit");
      Cyberblock_Log("hub: out (V released)");
    } else {
      Cyberblock_Log(was ? "hub: out (V was not parked)" : "hub: out (was not in the hub)");
    }
  }

  // Every CyberblockSystem tick with a player: the hub ends with the link; while in the hub and V is not parked, a try
  // every 1 s.
  public static func Tick(game: GameInstance, live: Bool) -> Void {
    let s = CyberblockHubService.Get();
    if !IsDefined(s) || !s.inHub {
      return;
    }
    if !Cyberblock_IsLinked() {
      CyberblockHub.Forget("the link dropped");
      return;
    }
    if CyberblockPark.Parked(game) {
      return;
    }
    s.wait -= 1;
    if s.wait > 0 {
      return;
    }
    let system = CyberblockSystem.Get(game);
    if IsDefined(system) {
      CyberblockHub.TryPark(game, live, s, system);
    }
  }

  // Session ready or end, detach, or this host no longer host: the hub of that session is over.
  public static func Forget(reason: String) -> Void {
    let s = CyberblockHubService.Get();
    if IsDefined(s) && s.inHub {
      s.inHub = false;
      s.why = "";
      Cyberblock_Log(s"hub: forgotten (\(reason))");
    }
  }

  private static func TryPark(game: GameInstance, live: Bool, s: ref<CyberblockHubService>, system: ref<CyberblockSystem>) -> Void {
    s.wait = 10;
    let park = system.Park();
    let why = park.WhyNot(game, live);
    if StrLen(why) == 0 {
      if park.Hold(game, live) {
        s.why = "";
        Cyberblock_Log("hub: V parked");
      }
      return;
    }
    if NotEquals(why, s.why) {
      s.why = why;
      Cyberblock_Log(s"hub: V not parked yet (\(why)); trying every 1 s");
    }
  }
}
