// Steve's installed cyberware on the Cyberpunk side (plan 2026-10-03-retest2 sections 1.2 cyberware/cyberfx, 1.3,
// 1.5 and 1.7; tasks S5 and S6). Minecraft owns the install state: it sends the whole list after each host hello, on
// join and after every install or removal, and the DLL turns each entry into one `cyber` event (kept while not Active,
// like perks). This file keeps the list for the whole game process and, while Cyberblock is Active on foot, applies the
// Cyberpunk side of the section 1.7 table:
//   reinforced_tendons -> V's HasDoubleJump +1 (PlayerState.reds leaves it unzeroed while installed);
//   fortified_ankles   -> V's HasChargeJump +1 (likewise; legs hold one implant, so never both: DoubleJumpDecisions
//                         refuses while HasChargeJump is set);
//   lynx_paws          -> V's Visibility x0.5, one modifier shared with Steve's silent_steps perk;
//   kiroshi_optics     -> Scan.reds looks for targets out to 80 m instead of 48 m (Has);
//   cyberfx sandevistan -> world slow-mo x0.2 with V at normal speed; kerenzikov -> x0.3. A newer one replaces the factor
//                          and the end; 0 s ends the running one. Each start and end also drives the DLL's
//                          Sandevistan screen effect (Cyberblock_SetSlowMoFx; re-test 3 plan
//                          2026-10-03-retest3-polish task S6, F10);
//   cyberfx berserk    -> Minecraft side only (S6 below).
// The other implants act through messages that already reach Night City (mantis blades, gorilla arms and monowire hits,
// the projectile launcher's explosion, optical camo's invisibility effect) or are Minecraft-side only. Tick(active
// false) and Clear take every modifier and the slow-mo off V; the list stays.
//
// S6, the Berserk gate (decided 2026-10-03 from r6/cache/tweakdb.bin + tweakdb_ep1.bin, read outside the repo with a
// throwaway reader, and the decompiled 2.31 scripts): BaseStatusEffect.BerserkPlayerBuff is NOT applied to V. Its
// packages carry stats beyond VFX: GLP.MaxStamina (Stamina x2); Armor, Thermal/Electric/Chemical resistance and health
// regen bonuses scaled by V's own Berserk stats; weapon stat groups (recoil and sway reductions, KnockdownImpulse x1.5
// on melee weapons). Worse, while V carries it, LocomotionAirEvents.OnUpdate (locomotionTransitions.swift:3685)
// requests a superhero landing on every fall, and SuperheroLandEvents.OnUpdate (:4946-4947) then starts the
// Attacks.GroundSlamFar / GroundSlamNear area attacks with V as instigator, stomping the NPCs around any landing. So
// cyberfx berserk logs 'berserk: Minecraft side only'; its Jump Boost still reaches V through `effect jump_boost`
// (Buffs.reds).
//
// 2.31 sources (decompiled 2.31 scripts; compile-checked):
//   Codeware ScriptableService (one instance per game process, not saved), looked up as CyberblockPerksService is.
//   Stat modifiers: RPGManager.CreateStatModifier(stat, gameStatModifierType, value) + StatsSystem.AddModifier /
//     RemoveModifier(StatsObjectID, the same object), the Perks.reds owner pattern (a load replaces V and its modifiers).
//   HasDoubleJump: DoubleJumpDecisions.EnterCondition (locomotionTransitions.swift:4237). HasChargeJump:
//     ChargeJumpDecisions.EnterCondition (:4315, a jump held over 0.15 s and released). Visibility:
//     PlayerPuppet.UpdateVisibilityModifier through VisibilityStatListener (audit C.4).
//   Slow-mo (audit C.1): TimeSystem.SetIgnoreTimeDilationOnLocalPlayerZero(true), then SetTimeDilation(reason, factor,
//     duration), as SandevistanEvents does through TimeDilationTransitions.SetTimeDilationGlobal for n"sandevistan";
//     setting the same reason again replaces it, as vanilla's repeated n"meleeHit" and n"deflect" dilations do
//     (player.swift:841, 5746). UnsetTimeDilation(reason, n"SandevistanEaseOut") and
//     SetIgnoreTimeDilationOnLocalPlayerZero(false) end it. Own reason n"cyberblockSlowMo". The end is engine time
//     (GameInstance.GetEngineTime), not stretched by the slow-mo itself; the engine duration (seconds + 1) is only a
//     safety net if ticks stop.

public class CyberblockCyberwareService extends ScriptableService {
  // The installed implants (section 1.7 ids), as Minecraft last reported them.
  public let installed: array<CName>;
  // Our modifiers on V, removed exactly; owner is the entity they were added to.
  public let owner: EntityID;
  public let doubleJump: ref<gameStatModifierData>;
  public let chargeJump: ref<gameStatModifierData>;
  public let visibility: ref<gameStatModifierData>;
  // Active on foot at the last Tick: slow-mo only starts then.
  public let active: Bool;
  // The running slow-mo: which implant, and its end (engine time).
  public let slowMo: Bool;
  public let slowMoId: CName;
  public let slowMoUntil: Float;

  public static func Get() -> ref<CyberblockCyberwareService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockCyberwareService") as CyberblockCyberwareService;
  }
}

public abstract class CyberblockCyberware {
  // Records one implant as installed or not; kept while not Active. 'cyberware: <id> on|off' on every change.
  public static func Set(game: GameInstance, id: CName, on: Bool) -> Void {
    let cw = CyberblockCyberwareService.Get();
    if !IsDefined(cw) {
      return;
    }
    if !CyberblockCyberware.Known(id) {
      Cyberblock_Log(s"cyberware: unknown id \(NameToString(id)) ignored");
      return;
    }
    if Equals(ArrayContains(cw.installed, id), on) {
      return;
    }
    if on {
      ArrayPush(cw.installed, id);
    } else {
      ArrayRemove(cw.installed, id);
    }
    Cyberblock_Log(s"cyberware: \(NameToString(id)) \(on ? "on" : "off")");
  }

  public static func Has(game: GameInstance, id: CName) -> Bool {
    let cw = CyberblockCyberwareService.Get();
    return IsDefined(cw) && ArrayContains(cw.installed, id);
  }

  // An implant effect started (seconds 0..30) or ended (0): sandevistan, kerenzikov or berserk.
  public static func Fx(game: GameInstance, id: CName, seconds: Float) -> Void {
    let cw = CyberblockCyberwareService.Get();
    if !IsDefined(cw) {
      return;
    }
    let dilation = CyberblockCyberware.Dilation(id);
    if dilation <= 0.0 && !Equals(id, n"berserk") {
      Cyberblock_Log(s"cyberfx: unknown effect \(NameToString(id)) ignored");
      return;
    }
    seconds = ClampF(seconds, 0.0, 30.0);
    Cyberblock_Log(s"cyberfx: \(NameToString(id)) \(seconds) s");
    if Equals(id, n"berserk") {
      if seconds > 0.0 {
        Cyberblock_Log("berserk: Minecraft side only");
      }
      return;
    }
    if seconds <= 0.0 {
      if cw.slowMo && Equals(cw.slowMoId, id) {
        CyberblockCyberware.EndSlowMo(game, cw);
      }
      return;
    }
    if !cw.active || !IsDefined(GetPlayer(game)) {
      Cyberblock_Log(s"cyberfx: \(NameToString(id)) ignored (not Active on foot)");
      return;
    }
    let time = GameInstance.GetTimeSystem(game);
    time.SetIgnoreTimeDilationOnLocalPlayerZero(true);
    time.SetTimeDilation(n"cyberblockSlowMo", dilation, seconds + 1.0);
    Cyberblock_SetSlowMoFx(id, seconds); // the DLL's screen effect (ReShade cb_slowmo)
    cw.slowMo = true;
    cw.slowMoId = id;
    cw.slowMoUntil = CyberblockCyberware.Now(game) + seconds;
  }

  // Every tick, after CyberblockPerks.Tick. active: Cyberblock is Active on foot and no save is being written.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, active: Bool) -> Void {
    let cw = CyberblockCyberwareService.Get();
    if !IsDefined(cw) {
      return;
    }
    cw.active = active && IsDefined(player);
    if !cw.active {
      CyberblockCyberware.RemoveAll(game, cw);
      return;
    }
    if cw.slowMo && CyberblockCyberware.Now(game) >= cw.slowMoUntil {
      CyberblockCyberware.EndSlowMo(game, cw);
    }
    let id = player.GetEntityID();
    if cw.owner != id {
      CyberblockCyberware.RemoveModifiers(game, cw); // V was replaced (load): the old modifiers went with the old V
    }
    cw.owner = id;
    let stats = GameInstance.GetStatsSystem(game);
    let statsId = Cast<StatsObjectID>(id);
    cw.doubleJump = CyberblockCyberware.Keep(stats, statsId, cw.doubleJump, gamedataStatType.HasDoubleJump,
      gameStatModifierType.Additive, 1.0, CyberblockCyberware.Has(game, n"reinforced_tendons"));
    cw.chargeJump = CyberblockCyberware.Keep(stats, statsId, cw.chargeJump, gamedataStatType.HasChargeJump,
      gameStatModifierType.Additive, 1.0, CyberblockCyberware.Has(game, n"fortified_ankles"));
    cw.visibility = CyberblockCyberware.Keep(stats, statsId, cw.visibility, gamedataStatType.Visibility,
      gameStatModifierType.Multiplier, 0.5, CyberblockCyberware.Has(game, n"lynx_paws") || CyberblockPerks.Has(game, n"silent_steps"));
  }

  // Before saves, on session ready and end, and when leaving Active/Vehicle: modifiers and slow-mo go, the list stays.
  public static func Clear(game: GameInstance) -> Void {
    let cw = CyberblockCyberwareService.Get();
    if IsDefined(cw) {
      cw.active = false;
      CyberblockCyberware.RemoveAll(game, cw);
    }
  }

  // The modifier to keep on V: current while wanted, a new one when wanted and missing, none when not wanted.
  private static func Keep(stats: ref<StatsSystem>, owner: StatsObjectID, current: ref<gameStatModifierData>, stat: gamedataStatType, type: gameStatModifierType, value: Float, want: Bool) -> ref<gameStatModifierData> {
    if want && !IsDefined(current) {
      let mod = RPGManager.CreateStatModifier(stat, type, value);
      stats.AddModifier(owner, mod);
      return mod;
    }
    if !want && IsDefined(current) {
      stats.RemoveModifier(owner, current);
      return null;
    }
    return current;
  }

  private static func RemoveAll(game: GameInstance, cw: ref<CyberblockCyberwareService>) -> Void {
    CyberblockCyberware.RemoveModifiers(game, cw);
    CyberblockCyberware.EndSlowMo(game, cw);
  }

  private static func RemoveModifiers(game: GameInstance, cw: ref<CyberblockCyberwareService>) -> Void {
    let stats = GameInstance.GetStatsSystem(game);
    let owner = Cast<StatsObjectID>(cw.owner);
    if IsDefined(cw.doubleJump) {
      stats.RemoveModifier(owner, cw.doubleJump);
      cw.doubleJump = null;
    }
    if IsDefined(cw.chargeJump) {
      stats.RemoveModifier(owner, cw.chargeJump);
      cw.chargeJump = null;
    }
    if IsDefined(cw.visibility) {
      stats.RemoveModifier(owner, cw.visibility);
      cw.visibility = null;
    }
  }

  private static func EndSlowMo(game: GameInstance, cw: ref<CyberblockCyberwareService>) -> Void {
    if !cw.slowMo {
      return;
    }
    let time = GameInstance.GetTimeSystem(game);
    time.UnsetTimeDilation(n"cyberblockSlowMo", n"SandevistanEaseOut");
    time.SetIgnoreTimeDilationOnLocalPlayerZero(false);
    Cyberblock_SetSlowMoFx(n"", 0.0);
    cw.slowMo = false;
    Cyberblock_Log(s"cyberfx: \(NameToString(cw.slowMoId)) slow-mo ended");
  }

  // World time factor of a slow-mo implant; 0 for any other id.
  private static func Dilation(id: CName) -> Float {
    if Equals(id, n"sandevistan") {
      return 0.2;
    }
    return Equals(id, n"kerenzikov") ? 0.3 : 0.0;
  }

  private static func Now(game: GameInstance) -> Float {
    return EngineTime.ToFloat(GameInstance.GetEngineTime(game));
  }

  // The 17 implant ids of plan section 1.7, in its fixed order.
  private static func Known(id: CName) -> Bool {
    let known: array<CName> = [
      n"mantis_blades", n"gorilla_arms", n"monowire", n"projectile_launch_system", n"reinforced_tendons",
      n"fortified_ankles", n"lynx_paws", n"sandevistan", n"berserk", n"cyberdeck", n"kerenzikov", n"optical_camo",
      n"subdermal_armor", n"kiroshi_optics", n"biomonitor", n"second_heart", n"ram_upgrade"
    ];
    return ArrayContains(known, id);
  }
}
