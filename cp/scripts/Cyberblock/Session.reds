// The Minecraft server's verdict on this host (megaplan 2026-10-04 sections 1.2 and 1.8, task 1-H1). Minecraft sends
// `session` to the DLL after every handshake result, server disconnect and reconnect; the DLL queues an n"session" event
// on every change (Cyberblock_EventName = n"none" | n"host" | n"guest" | n"refused", Cyberblock_EventText = the reason,
// Cyberblock_EventExtra = the guest permissions bitmask: 1 explosions, 2 damage, 4 hacks, 8 devices, 16 spawns), kept
// whatever the state, and Events.reds hands it to OnEvent.
//
// When the state is not host, Night City is cleaned exactly as on a link drop while the WebSocket stays open: the
// collision boxes and their registry are reset (the next host session sends `blocks reset:true` and every block
// again), puppets are despawned and tethers released, a running V teleport is aborted and a parked V released
// (Teleport.reds, Park.reds), and the Atlas survey tour stops (Survey.reds counts the tour blocked while IsHost is false,
// which ends a running tour with V put back). The DLL has already done its side (flight
// stop, Minecraft pause released). A refused reason shows once as Cyberpunk's own warning, and only while Minecraft is
// not drawn (Cyberblock not On, or the link gone): while it is, Minecraft shows the reason itself.
//
// Before any `session` (a mod older than Phase 1 never sends one) this host counts as host, so singleplayer with an
// older mod behaves as before.
//
// 2.31 script API (NativeDB, redscript): only the natives (Natives.reds), CyberblockCollision.Reset,
// CyberblockPuppets.Clear, CyberblockTether.Clear, CyberblockSystem.Get/InkOn/Notify (UI_Notifications.WarningMessage),
// CyberblockTeleport.Abort, CyberblockPark.Release.

public class CyberblockSession {
  // The last state was none, guest or refused (false before any session event).
  private let notHost: Bool;
  // The refused reason already shown as a Cyberpunk warning ("" none).
  private let shownRefusal: String;

  public func IsHost() -> Bool {
    return !this.notHost;
  }

  // An n"session" event is current (Cyberblock_NextEvent): any state, with or without a player.
  public func OnEvent(game: GameInstance) -> Void {
    let state = Cyberblock_EventName();
    let reason = Cyberblock_EventText();
    let perms = RoundF(Cyberblock_EventExtra());
    Cyberblock_Log(s"session: \(NameToString(state)) (\(reason)), guest perms \(perms)");
    if Equals(state, n"host") {
      this.notHost = false;
      this.shownRefusal = "";
      return;
    }
    this.notHost = true;
    CyberblockCollision.Reset(game);
    CyberblockPuppets.Clear(game);
    CyberblockTether.Clear(game);
    // A running V teleport is aborted (answered "blocked (session <state>)" while linked) and a parked V released
    // ('park: off (session <state>)'), so nothing a host session started holds V after the server said otherwise.
    let system = CyberblockSystem.Get(game);
    if IsDefined(system) {
      system.Teleport().Abort(game, s"session \(NameToString(state))");
      system.Park().Release(game, s"session \(NameToString(state))");
    }
    // Megaplan 7-S1: a hub of the ended host session is over (Hub.reds); V was released just above.
    CyberblockHub.Forget(s"session \(NameToString(state))");
    Cyberblock_Log(s"session: cleaned (\(StrLen(reason) > 0 ? reason : NameToString(state)))");
    if Equals(state, n"refused") && StrLen(reason) > 0 && NotEquals(reason, this.shownRefusal) {
      if IsDefined(system) && (!system.InkOn() || !Cyberblock_IsLinked()) {
        system.Notify(reason);
        this.shownRefusal = reason;
      }
    }
  }
}
