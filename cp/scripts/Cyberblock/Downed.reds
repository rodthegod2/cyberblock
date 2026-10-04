// The death screen hand-off (megaplan 2026-10-04 tasks 8-S1 and 8-T2; docs/notes/ui-coverage.md row "Death screen
// (`death`): pass-through, replaced by Downed and revive, Phase 8").
//
// While Cyberblock is On, Steve's health is the only health: every hit on V goes to Steve (Health.reds), V stays
// invulnerable through our god mode, so V never dies and Cyberpunk's death screen never comes; when Steve's health runs
// out, Minecraft's Downed flow (8T: down, bleed-out, revive or Trauma Team, Minecraft's death screen only at the end of
// the bleed-out) decides. What is left is a death that bypasses the damage pipeline (a scripted or quest kill, a forced
// kill effect, a stat pool set to zero): V really dies, and Cyberpunk shows its death screen (still a pass-through,
// Restyle.reds `death`), whose only way on is loading a save. For that case the scripts hand the death to Minecraft:
// Steve takes 4 x his max health (as Health.reds' instant kill), so the server's Downed flow starts for the host's
// player exactly as for any lethal hit, and the friends see the host go down. Nothing in Cyberpunk is held back:
// blocking Cyberpunk's death screen (the quest fact block_death_menu) would leave a dead V with no way to load.
// Only when Cyberblock is On (saves included), this player is the server's host (Session.reds) and the link is up;
// once per death (V cannot die twice without a load). Off or Lost, a death is Cyberpunk's own, as before.
// Log line: 'downed: V died while Cyberblock is On (by <instigator>); Steve takes lethal damage, Minecraft's Downed flow
// takes over; Cyberpunk's death screen follows (load a save)'.
//
// 2.31 script API (NativeDB, redscript), compile-checked by cp/scripts/compile_check.py: PlayerPuppet.OnDeath(
// ref<gameDeathEvent>) (protected cb, player.swift 5341), gameDeathEvent.instigator (orphans.swift 34839),
// GameObject.GetWorldPosition/GetClassName; natives Cyberblock_Damage, Cyberblock_IsLinked.

public class CyberblockDownedService extends ScriptableService {
  // The death was handed to Minecraft (reset on load).
  public let handed: Bool;

  public static func Get() -> ref<CyberblockDownedService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockDownedService") as CyberblockDownedService;
  }
}

public abstract class CyberblockDowned {
  public static func OnDeath(player: ref<PlayerPuppet>, evt: ref<gameDeathEvent>) -> Void {
    let s = CyberblockDownedService.Get();
    let system = CyberblockSystem.Get(player.GetGame());
    if !IsDefined(s) || !IsDefined(system) || s.handed || !system.InkOn() || !system.Session().IsHost() || !Cyberblock_IsLinked() {
      return;
    }
    s.handed = true;
    let from: Vector4;
    let hasFrom = false;
    let by = "unknown";
    let instigator = IsDefined(evt) ? evt.instigator : null;
    if IsDefined(instigator) && instigator != player {
      from = instigator.GetWorldPosition();
      hasFrom = true;
      by = NameToString(instigator.GetClassName());
    }
    Cyberblock_Damage(4.0, n"other", from, hasFrom);
    Cyberblock_Log(s"downed: V died while Cyberblock is On (by \(by)); Steve takes lethal damage, Minecraft's Downed flow takes over; Cyberpunk's death screen follows (load a save)");
  }

  // On load.
  public static func Forget() -> Void {
    let s = CyberblockDownedService.Get();
    if IsDefined(s) {
      s.handed = false;
    }
  }
}

@wrapMethod(PlayerPuppet)
protected cb func OnDeath(evt: ref<gameDeathEvent>) -> Bool {
  CyberblockDowned.OnDeath(this, evt);
  return wrappedMethod(evt);
}
