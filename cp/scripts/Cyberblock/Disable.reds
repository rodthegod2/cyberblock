// DISABLE list, script side (plan 2026-10-03 S1.2; docs/notes/cp-mechanics-audit.md A.2, A.3, C.2 and its
// compile-checked appendix). While Cyberblock's god mode is on V (Active, on foot):
//   - F5 does not quicksave (Minecraft's perspective key);
//   - G does not toggle V's walk mode (the hack key);
//   - V's own perk-gated dash and air dash follow Steve's Dodge perk instead of V's Reflexes perks.
// The DLL swallows F5 and G as well (plan D5); these wraps are the second line. Each wrap is as narrow as the audit's:
// outside Active every answer is vanilla. The restrictions and the ability stats are in PlayerState.reds.
//
// 2.31 sources (decompiled 2.31 scripts):
//   gameuiInGameMenuGameController.OnAction(QuickSave) -> private final HandleQuickSave()
//     (inGameMenuGameController.swift:204-205, 537);
//   PlayerPuppet.OnAction(ToggleWalk) -> private final ProcessToggleWalkInput() (player.swift:1204, 1266);
//   PlayerDevelopmentData.IsNewPerkBought(gamedataNewPerkType) -> Int32, public final const, field m_owner:
//     wref<GameObject> (playerDevelopmentSystem.swift:4, 1378). LocomotionTransition.WantsToDodge needs
//     Reflexes_Central_Milestone_3 at level 3 for the air dash, DodgeDecisions needs Reflexes_Central_Milestone_2 at
//     level 2 for the dash (locomotionTransitions.swift:262-299, 2340-2364).
//   GodModeSystem.GetGodModeSources(EntityID, gameGodModeType) -> array<CName> (as Health.reds).

public abstract class CyberblockDisable {
  // Cyberblock's god mode is on V: the audit's Active test (source n"Cyberblock", PlayerState.reds).
  public static func Active(game: GameInstance) -> Bool {
    let player = GetPlayer(game);
    if !IsDefined(player) {
      return false;
    }
    let sources = GameInstance.GetGodModeSystem(game).GetGodModeSources(player.GetEntityID(), gameGodModeType.Invulnerable);
    return ArrayContains(sources, n"Cyberblock");
  }
}

// F5 must not quicksave Cyberpunk while it toggles Minecraft's camera.
@wrapMethod(gameuiInGameMenuGameController)
private final func HandleQuickSave() -> Void {
  let owner = this.GetPlayerControlledObject();
  if IsDefined(owner) && CyberblockDisable.Active(owner.GetGame()) {
    return;
  }
  wrappedMethod();
}

// G (the hack key) is Cyberpunk's ToggleWalk.
@wrapMethod(PlayerPuppet)
private final func ProcessToggleWalkInput() -> Void {
  if CyberblockDisable.Active(this.GetGame()) {
    return;
  }
  wrappedMethod();
}

// V's dash (Reflexes_Central_Milestone_2 at 2) and air dash (Reflexes_Central_Milestone_3 at 3) are reported as not
// bought while Active, and as bought while Steve owns the Dodge perk (CyberblockPerks, S3). PlayerState keeps
// HasDodge/HasDodgeAir for the same perk.
@wrapMethod(PlayerDevelopmentData)
public final const func IsNewPerkBought(perkType: gamedataNewPerkType) -> Int32 {
  let level = wrappedMethod(perkType);
  if !IsDefined(this.m_owner) || !CyberblockDisable.Active(this.m_owner.GetGame()) {
    return level;
  }
  if Equals(perkType, gamedataNewPerkType.Reflexes_Central_Milestone_2) {
    return CyberblockPerks.Has(this.m_owner.GetGame(), n"dodge") ? 2 : 0;
  }
  if Equals(perkType, gamedataNewPerkType.Reflexes_Central_Milestone_3) {
    return CyberblockPerks.Has(this.m_owner.GetGame(), n"dodge") ? 3 : 0;
  }
  return level;
}
