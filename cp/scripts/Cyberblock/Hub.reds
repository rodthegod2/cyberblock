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
// The hub is forgotten (V released by the same paths) when this host stops being host (Session.reds), on every load
// and at session end. Cyberblock going Off or Lost releases V as for any park (Cyberblock.reds); the hub is kept, so V
// is parked again once Cyberblock is On while Steve is still in the hub.
// Log lines: 'hub: in (parking V)', 'hub: V parked', 'hub: V not parked yet (<why>); trying every 1 s',
// 'hub: out (V released)', 'hub: out (was not in the hub)', 'hub: forgotten (<reason>)'.
//
// 2.31 script API (NativeDB, redscript): only CyberblockPark (Park.reds: WhyNot, Hold, Release, Parked) and the natives;
// Codeware ScriptableService for the state.

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

  // Every CyberblockSystem tick with a player: while in the hub and V is not parked, a try every 1 s.
  public static func Tick(game: GameInstance, live: Bool) -> Void {
    let s = CyberblockHubService.Get();
    if !IsDefined(s) || !s.inHub || CyberblockPark.Parked(game) {
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
