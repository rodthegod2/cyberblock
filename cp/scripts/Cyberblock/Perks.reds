// Steve's skill-tree perks on the Cyberpunk side (plan 2026-10-03 sections 1.4 and 1.5, task S3.3; re-test 2 plan
// sections 1.5 and 1.7, task S5). Minecraft owns the ranks (perks after each host hello and on every purchase); this
// file keeps them for the whole game process. Since the cyberware of re-test 2 the perks upgrade implants, so most
// Cyberpunk-side rows moved to Cyberware.reds:
//   double_jump  -> Minecraft side only (it upgrades the reinforced_tendons implant, which gives V HasDoubleJump);
//   silent_steps -> V's Visibility x0.5, one modifier shared with lynx_paws (CyberblockCyberware.Tick reads Has here);
//   sandevistan  -> perkfx from older Minecraft builds still works: Fx hands it to CyberblockCyberware.Fx (cyberfx);
//   dodge        -> V's HasDodgeAir +1 while Active (S1 reads Has(n"dodge") for its IsNewPerkBought wrap and stat
//                   exceptions).
// Every other perk is Minecraft-side only. Tick(active false) and Clear remove the modifier; ranks stay.
//
// 2.31 sources (decompiled 2.31 scripts; compile-checked):
//   Codeware ScriptableService (one instance per game process, not saved), looked up as CyberblockEffectsService is.
//   Stat modifiers (audit C.2 GrantStat): RPGManager.CreateStatModifier(stat, gameStatModifierType, value) +
//     StatsSystem.AddModifier / RemoveModifier(StatsObjectID, the same modifier object).
//   HasDodgeAir: the 2.x air dash runs in the dodge state (DodgeEvents.OnEnter, TreatDashAsAirDash), which needs HasDodge
//     and the Reflexes milestone 3 that S1's wrap reports; the air dodge state (DodgeAirDecisions.EnterCondition,
//     locomotionTransitions.swift:4584) also needs HasDodgeAir, which the player's base stats do not grant. It is
//     granted with a modifier, so the Dodge perk gives an air dash whichever of the two states the locomotion graph
//     enters.

public class CyberblockPerksService extends ScriptableService {
  public let ids: array<CName>;
  public let ranks: array<Int32>;
  // Our modifier on V, removed exactly; owner is the entity it was added to.
  public let dodgeAir: ref<gameStatModifierData>;
  public let owner: EntityID;

  public static func Get() -> ref<CyberblockPerksService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockPerksService") as CyberblockPerksService;
  }
}

public abstract class CyberblockPerks {
  // Records a rank (0 or 1) for one of the 20 plan section 1.5 perk ids; kept while not Active.
  public static func Set(game: GameInstance, id: CName, rank: Int32) -> Void {
    let perks = CyberblockPerksService.Get();
    if !IsDefined(perks) {
      return;
    }
    if !CyberblockPerks.Known(id) {
      Cyberblock_Log(s"perks: unknown perk \(NameToString(id)) ignored");
      return;
    }
    rank = Max(rank, 0);
    let i = ArrayFindFirst(perks.ids, id);
    if i < 0 {
      ArrayPush(perks.ids, id);
      ArrayPush(perks.ranks, rank);
    } else {
      if perks.ranks[i] == rank {
        return;
      }
      perks.ranks[i] = rank;
    }
    Cyberblock_Log(s"perks: \(NameToString(id)) rank \(rank)");
  }

  public static func Has(game: GameInstance, id: CName) -> Bool {
    let perks = CyberblockPerksService.Get();
    if !IsDefined(perks) {
      return false;
    }
    let i = ArrayFindFirst(perks.ids, id);
    return i >= 0 && perks.ranks[i] > 0;
  }

  // perkfx (older Minecraft builds): the same effect as cyberfx.
  public static func Fx(game: GameInstance, id: CName, seconds: Float) -> Void {
    CyberblockCyberware.Fx(game, id, seconds);
  }

  // Every tick. active: Cyberblock is Active on foot and no save is being written.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, active: Bool) -> Void {
    let perks = CyberblockPerksService.Get();
    if !IsDefined(perks) {
      return;
    }
    if !active || !IsDefined(player) {
      CyberblockPerks.RemoveModifiers(game, perks);
      return;
    }
    let id = player.GetEntityID();
    if IsDefined(perks.dodgeAir) && perks.owner != id {
      CyberblockPerks.RemoveModifiers(game, perks); // V was replaced (load): the old modifier went with the old V
    }
    perks.owner = id;
    let wantDodgeAir = CyberblockPerks.Has(game, n"dodge");
    if wantDodgeAir && !IsDefined(perks.dodgeAir) {
      perks.dodgeAir = RPGManager.CreateStatModifier(gamedataStatType.HasDodgeAir, gameStatModifierType.Additive, 1.0);
      GameInstance.GetStatsSystem(game).AddModifier(Cast<StatsObjectID>(id), perks.dodgeAir);
    } else {
      if !wantDodgeAir && IsDefined(perks.dodgeAir) {
        CyberblockPerks.RemoveModifiers(game, perks);
      }
    }
  }

  // Before saves, on session ready and end, and when leaving Active/Vehicle: the modifier goes, ranks stay.
  public static func Clear(game: GameInstance) -> Void {
    let perks = CyberblockPerksService.Get();
    if IsDefined(perks) {
      CyberblockPerks.RemoveModifiers(game, perks);
    }
  }

  private static func RemoveModifiers(game: GameInstance, perks: ref<CyberblockPerksService>) -> Void {
    if IsDefined(perks.dodgeAir) {
      GameInstance.GetStatsSystem(game).RemoveModifier(Cast<StatsObjectID>(perks.owner), perks.dodgeAir);
      perks.dodgeAir = null;
    }
  }

  // The 20 perk ids of plan section 1.5.
  private static func Known(id: CName) -> Bool {
    let known: array<CName> = [
      n"iron_skin", n"gorilla_arms", n"painkiller", n"juggernaut", n"double_jump", n"sandevistan", n"quick_draw",
      n"dodge", n"tinkerer", n"smart_arrows", n"field_medic", n"demolitions", n"extra_ram", n"cheap_hacks",
      n"chain_hack", n"overclock", n"optical_camo", n"headhunter", n"cold_blood", n"silent_steps"
    ];
    return ArrayContains(known, id);
  }
}
