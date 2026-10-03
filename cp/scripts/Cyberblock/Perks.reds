// Steve's skill-tree perks on the Cyberpunk side (plan 2026-10-03 sections 1.4 and 1.5, task S3.3). Minecraft owns the
// ranks (perks after each host hello and on every purchase); this file keeps them for the whole game process and applies
// the Cyberpunk-side rows while Cyberblock is Active:
//   double_jump  -> V's HasDoubleJump +1 (S1 also leaves it unzeroed while the perk is owned);
//   silent_steps -> V's Visibility x0.5;
//   sandevistan  -> perkfx: world slow-mo x0.2 for the given seconds with V at normal speed;
//   dodge        -> V's HasDodgeAir +1 (S1 reads Has(n"dodge") for its IsNewPerkBought wrap and stat exceptions).
// Every other perk is Minecraft-side only. Tick(active false) and Clear remove every modifier and end the slow-mo;
// ranks stay.
//
// 2.31 sources (decompiled 2.31 scripts; compile-checked):
//   Codeware ScriptableService (one instance per game process, not saved), looked up as CyberblockEffectsService is.
//   Stat modifiers (audit C.2 GrantStat, C.4 Stealth): RPGManager.CreateStatModifier(stat, gameStatModifierType, value)
//     + StatsSystem.AddModifier / RemoveModifier(StatsObjectID, the same modifier object).
//   HasDoubleJump: DoubleJumpDecisions.EnterCondition checks HasStatFlag(HasDoubleJump), and refuses while
//     HasChargeJump or HasAirHover is set (S1 zeroes both while Active, audit A.4).
//   HasDodgeAir: the 2.x air dash runs in the dodge state (DodgeEvents.OnEnter, TreatDashAsAirDash), which needs HasDodge
//     and the Reflexes milestone 3 that S1's wrap reports; the air dodge state (DodgeAirDecisions.EnterCondition,
//     locomotionTransitions.swift:4584) also needs HasDodgeAir, which the player's base stats do not grant. It is granted
//     like HasDoubleJump, so the Dodge perk gives an air dash whichever of the two states the locomotion graph enters.
//   Visibility: PlayerPuppet.UpdateVisibilityModifier sends VisibleObjectDetectionMultEvent(multiplier = Visibility),
//     run by VisibilityStatListener whenever the stat changes (audit C.4).
//   Slow-mo (audit C.1): TimeSystem.SetIgnoreTimeDilationOnLocalPlayerZero(true) then SetTimeDilation(reason, 0.2,
//     duration), as SandevistanEvents does through TimeDilationTransitions.SetTimeDilationGlobal for n"sandevistan";
//     UnsetTimeDilation(reason, n"SandevistanEaseOut") and SetIgnoreTimeDilationOnLocalPlayerZero(false) to end it.
//     Own reason n"cyberblockSlowMo". The end time is engine time (GameInstance.GetEngineTime, the clock the damage
//     history uses), so it is not stretched by the slow-mo itself; the engine duration (seconds + 1) is only a safety
//     net if ticks stop.

public class CyberblockPerksService extends ScriptableService {
  public let ids: array<CName>;
  public let ranks: array<Int32>;
  // Our modifiers on V, removed exactly; owner is the entity they were added to.
  public let doubleJump: ref<gameStatModifierData>;
  public let dodgeAir: ref<gameStatModifierData>;
  public let silentSteps: ref<gameStatModifierData>;
  public let owner: EntityID;
  // Active on foot at the last Tick: slow-mo only starts then.
  public let active: Bool;
  public let slowMo: Bool;
  public let slowMoUntil: Float;

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

  // perkfx: n"sandevistan" slows the world for seconds (0..30; 0 ends it). A new one while running only moves the end
  // time; the dilation itself is never stacked.
  public static func Fx(game: GameInstance, id: CName, seconds: Float) -> Void {
    let perks = CyberblockPerksService.Get();
    if !IsDefined(perks) {
      return;
    }
    if !Equals(id, n"sandevistan") {
      Cyberblock_Log(s"perks: unknown perkfx \(NameToString(id)) ignored");
      return;
    }
    seconds = ClampF(seconds, 0.0, 30.0);
    if seconds <= 0.0 {
      CyberblockPerks.EndSlowMo(game, perks);
      return;
    }
    if !perks.active || !IsDefined(GetPlayer(game)) {
      Cyberblock_Log("perks: sandevistan ignored (not Active)");
      return;
    }
    let until = CyberblockPerks.Now(game) + seconds;
    if perks.slowMo {
      perks.slowMoUntil = MaxF(perks.slowMoUntil, until);
      return;
    }
    let time = GameInstance.GetTimeSystem(game);
    time.SetIgnoreTimeDilationOnLocalPlayerZero(true);
    time.SetTimeDilation(n"cyberblockSlowMo", 0.2, seconds + 1.0);
    perks.slowMo = true;
    perks.slowMoUntil = until;
    Cyberblock_Log(s"perks: sandevistan slow-mo for \(seconds) s");
  }

  // Every tick. active: Cyberblock is Active on foot and no save is being written.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, active: Bool) -> Void {
    let perks = CyberblockPerksService.Get();
    if !IsDefined(perks) {
      return;
    }
    perks.active = active && IsDefined(player);
    if !perks.active {
      CyberblockPerks.RemoveAll(game, perks);
      return;
    }
    if perks.slowMo && CyberblockPerks.Now(game) >= perks.slowMoUntil {
      CyberblockPerks.EndSlowMo(game, perks);
    }
    let id = player.GetEntityID();
    if (IsDefined(perks.doubleJump) || IsDefined(perks.dodgeAir) || IsDefined(perks.silentSteps)) && perks.owner != id {
      CyberblockPerks.RemoveModifiers(game, perks); // V was replaced (load): the old modifiers went with the old V
    }
    perks.owner = id;
    let stats = GameInstance.GetStatsSystem(game);
    let statsId = Cast<StatsObjectID>(id);
    let wantDoubleJump = CyberblockPerks.Has(game, n"double_jump");
    if wantDoubleJump && !IsDefined(perks.doubleJump) {
      perks.doubleJump = RPGManager.CreateStatModifier(gamedataStatType.HasDoubleJump, gameStatModifierType.Additive, 1.0);
      stats.AddModifier(statsId, perks.doubleJump);
    } else {
      if !wantDoubleJump && IsDefined(perks.doubleJump) {
        stats.RemoveModifier(statsId, perks.doubleJump);
        perks.doubleJump = null;
      }
    }
    let wantDodgeAir = CyberblockPerks.Has(game, n"dodge");
    if wantDodgeAir && !IsDefined(perks.dodgeAir) {
      perks.dodgeAir = RPGManager.CreateStatModifier(gamedataStatType.HasDodgeAir, gameStatModifierType.Additive, 1.0);
      stats.AddModifier(statsId, perks.dodgeAir);
    } else {
      if !wantDodgeAir && IsDefined(perks.dodgeAir) {
        stats.RemoveModifier(statsId, perks.dodgeAir);
        perks.dodgeAir = null;
      }
    }
    let wantSilentSteps = CyberblockPerks.Has(game, n"silent_steps");
    if wantSilentSteps && !IsDefined(perks.silentSteps) {
      perks.silentSteps = RPGManager.CreateStatModifier(gamedataStatType.Visibility, gameStatModifierType.Multiplier, 0.5);
      stats.AddModifier(statsId, perks.silentSteps);
    } else {
      if !wantSilentSteps && IsDefined(perks.silentSteps) {
        stats.RemoveModifier(statsId, perks.silentSteps);
        perks.silentSteps = null;
      }
    }
  }

  // Before saves, on session ready and end, and when leaving Active/Vehicle: modifiers and slow-mo go, ranks stay.
  public static func Clear(game: GameInstance) -> Void {
    let perks = CyberblockPerksService.Get();
    if IsDefined(perks) {
      perks.active = false;
      CyberblockPerks.RemoveAll(game, perks);
    }
  }

  private static func RemoveAll(game: GameInstance, perks: ref<CyberblockPerksService>) -> Void {
    CyberblockPerks.RemoveModifiers(game, perks);
    CyberblockPerks.EndSlowMo(game, perks);
  }

  private static func RemoveModifiers(game: GameInstance, perks: ref<CyberblockPerksService>) -> Void {
    if !IsDefined(perks.doubleJump) && !IsDefined(perks.dodgeAir) && !IsDefined(perks.silentSteps) {
      return;
    }
    let statsId = Cast<StatsObjectID>(perks.owner);
    let stats = GameInstance.GetStatsSystem(game);
    if IsDefined(perks.doubleJump) {
      stats.RemoveModifier(statsId, perks.doubleJump);
      perks.doubleJump = null;
    }
    if IsDefined(perks.dodgeAir) {
      stats.RemoveModifier(statsId, perks.dodgeAir);
      perks.dodgeAir = null;
    }
    if IsDefined(perks.silentSteps) {
      stats.RemoveModifier(statsId, perks.silentSteps);
      perks.silentSteps = null;
    }
  }

  private static func EndSlowMo(game: GameInstance, perks: ref<CyberblockPerksService>) -> Void {
    if !perks.slowMo {
      return;
    }
    let time = GameInstance.GetTimeSystem(game);
    time.UnsetTimeDilation(n"cyberblockSlowMo", n"SandevistanEaseOut");
    time.SetIgnoreTimeDilationOnLocalPlayerZero(false);
    perks.slowMo = false;
    Cyberblock_Log("perks: slow-mo ended");
  }

  private static func Now(game: GameInstance) -> Float {
    return EngineTime.ToFloat(GameInstance.GetEngineTime(game));
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
