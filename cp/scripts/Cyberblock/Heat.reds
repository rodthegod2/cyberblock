// Server Heat as V's real wanted level, and Gang Wars reputation as Night City's faction attitudes (megaplan 2026-10-04
// tasks 8-S1, 8-T1 and 8-M1; section 1.8 Phase 8 row, section 1.9 `heat` and `attitude`; Phase 0 check (e),
// docs/notes/phase0-feasibility.md).
//
// Event n"heat" (Events.reds hands it here whatever the state): Cyberblock_EventAmount = the server's tier 0..5. The tier
// is remembered and applied while live (at once, or as soon as Cyberblock is On again):
//   - a tier above V's wanted level raises it to the tier (tier N = N stars: 1 more NCPD patrols ... 4 and 5 Max-Tac,
//     the game's own NCPD and Max-Tac);
//   - a tier below it lowers it to the tier (0: the vanilla forced de-escalation) only while V's wanted level is not
//     above the one the server set; stars V earned beyond it in Night City are V's own and stay.
//   The tier is applied once per change (and once more each time Night City is live again), never forced every tick,
//   so V can still lose the police.
// Event n"attitude": the faction (Cyberblock_EventName, or Cyberblock_EventText when the name is the attitude word) and
//   the attitude toward the host: Cyberblock_EventText "friendly" | "neutral" | "hostile", else the sign of
//   Cyberblock_EventAmount (+ friendly, 0 neutral, - hostile). The faction's attitude group (CyberblockFactions.Group,
//   Spawns.reds) gets that relation toward the group n"player" while live. Unknown factions are logged and ignored.
//
// Method (Phase 0 e, desk verdict "yes", unconfirmed in game: CET E1-E5): heat = the quest path, a SetWantedLevel request
// queued with PreventionSystem.QueueRequest (OnSetWantedLevel; the quest flag it sets is removed again at heat 0);
// attitude = AttitudeSystem.SetAttitudeRelation(group, n"player", attitude), the non-persistent call (never saved).
// Runtime feature checks and the written fallback (plan 4.1 e): two raises in a row (at least 30 s apart) that left the
// wanted level where it was 2 s later, or no PreventionSystem -> no more wanted-level writes until the next load (the
// server's Minecraft squads stay the only Heat response); an attitude that does not read back as set, or no
// AttitudeSystem -> no more attitude writes until the next load (Gang Wars reputation does not change how Night City
// treats the host). The game may legitimately hold heat back, so these do not count toward the fallback: while a quest
// or scene has the prevention system off (PreventionSystem.IsSystemEnabled false, or IsSystemLocked) a raise waits for
// that to end ('heat: raise held (<why>, wanted level A), kept'; at the check: 'heat: raise ignored (<why>, ...), kept')
// instead of forcing it on; a raise that moved the level but stopped below the tier (a quest or district heat
// cap) works. A first failed raise is logged 'heat: raise ignored (<why>, strike 1 of 2), kept' and tried again 30 s
// later. One log line names each path at its first event, one more on a switch, and the first write that took effect is
// logged as confirmed in game:
//   'heat: path: <path>' / 'heat: path: <fallback> (fallback: <why>)', 'attitude: path: ...' likewise.
// Cleanup: what we wrote is undone when Night City stops being live (Off, Lost), on a non-host session (the tier and the
// factions are then forgotten too), at session end and on detach: our wanted level goes back to 0 unless V's own is
// above it, every faction we touched gets its relation from before. On load the save's state rules: nothing is written,
// the record of our writes is forgotten, the tier is kept and applied again once live.
// Log lines: 'heat: server tier N', 'heat: wanted level A -> N (server tier)', 'heat: tier N left V's own wanted level
// A', 'heat: wanted level cleared (<why>)', 'attitude: <group> -> <attitude> (server)', 'attitude: <group> back to
// <attitude> (<why>)'.
//
// 2.31 script API (NativeDB, redscript), compile-checked by cp/scripts/compile_check.py: SetWantedLevel.m_wantedLevel,
// EPreventionHeatStage, PreventionSystem.QueueRequest (static), GetHeatStageAsInt, IsSystemEnabled and IsSystemLocked
// (preventionSystem.swift 331-336, 383, 3522, 4200), ScriptableSystemsContainer.Get(n"PreventionSystem"), GameInstance.GetAttitudeSystem,
// AttitudeSystem.GetAttitudeRelation/SetAttitudeRelation (orphans.swift 26919-26923), EAIAttitude, EnumInt/IntEnum.

public class CyberblockHeatService extends ScriptableService {
  // The server's last tier, and whether it was applied since Night City is live.
  public let hasTier: Bool;
  public let tier: Int32;
  public let synced: Bool;
  // The wanted level we set (0: none of ours); a raise waits for its check (ticks left, the level asked for, the level
  // before it).
  public let wrote: Int32;
  public let checkIn: Int32;
  public let checkFor: Int32;
  public let checkFrom: Int32;
  // Failed raises in a row (the fallback comes at the second), ticks until the next try, a raise held by a lock.
  public let heatStrikes: Int32;
  public let retryIn: Int32;
  public let heatHeld: Bool;
  public let heatFallback: Bool;
  public let heatLogged: Bool;
  public let heatConfirmed: Bool;
  // Factions (parallel arrays): attitude group, the relation the server wants (EAIAttitude as Int32), the relation
  // before our first write (-1: not read yet), whether the wanted one is in place.
  public let groups: array<CName>;
  public let wants: array<Int32>;
  public let originals: array<Int32>;
  public let applied: array<Bool>;
  public let attFallback: Bool;
  public let attLogged: Bool;
  public let attConfirmed: Bool;

  public static func Get() -> ref<CyberblockHeatService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockHeatService") as CyberblockHeatService;
  }
}

public abstract class CyberblockHeat {
  // An n"heat" event is current (Cyberblock_NextEvent). live: Active or Vehicle, with a player, not saving.
  public static func OnHeat(game: GameInstance, live: Bool) -> Void {
    let s = CyberblockHeatService.Get();
    if !IsDefined(s) {
      return;
    }
    if !s.heatLogged {
      s.heatLogged = true;
      Cyberblock_Log(s.heatFallback
        ? "heat: path: none, no wanted-level writes; Minecraft squads are the Heat response (Phase 0 e fallback)"
        : "heat: path: PreventionSystem SetWantedLevel (the quest path), the server's Heat tier is V's wanted level (Phase 0 e, unconfirmed in game)");
    }
    s.tier = Clamp(RoundF(Cyberblock_EventAmount()), 0, 5);
    s.hasTier = true;
    s.synced = false;
    Cyberblock_Log(s"heat: server tier \(s.tier)\(live ? "" : " (applied once Cyberblock is On)")");
    if live {
      CyberblockHeat.Sync(game, s);
    }
  }

  // An n"attitude" event is current.
  public static func OnAttitude(game: GameInstance, live: Bool) -> Void {
    let s = CyberblockHeatService.Get();
    if !IsDefined(s) {
      return;
    }
    let raw = Cyberblock_EventName();
    let name = Equals(raw, n"") ? "" : NameToString(raw); // NameToString(n"") is "None"
    let text = Cyberblock_EventText();
    let faction = name;
    let toward = StrLower(text);
    if CyberblockHeat.IsToward(StrLower(name)) {
      toward = StrLower(name);
      faction = text;
    } else {
      if !CyberblockHeat.IsToward(toward) {
        let amount = Cyberblock_EventAmount();
        toward = amount > 0.0 ? "friendly" : (amount < 0.0 ? "hostile" : "neutral");
      }
      if StrLen(faction) == 0 {
        faction = text;
      }
    }
    let group = CyberblockFactions.Group(faction);
    if Equals(group, n"") {
      Cyberblock_Log(s"attitude: unknown faction '\(faction)' ignored (no attitude group)");
      return;
    }
    if !s.attLogged {
      s.attLogged = true;
      Cyberblock_Log(s.attFallback
        ? "attitude: path: none, no reputation writes (Phase 0 e fallback)"
        : "attitude: path: AttitudeSystem.SetAttitudeRelation(<group>, player), not saved (Phase 0 e, unconfirmed in game)");
    }
    let want = Equals(toward, "friendly") ? EnumInt(EAIAttitude.AIA_Friendly) : (Equals(toward, "hostile") ? EnumInt(EAIAttitude.AIA_Hostile) : EnumInt(EAIAttitude.AIA_Neutral));
    let i = ArrayFindFirst(s.groups, group);
    if i < 0 {
      ArrayPush(s.groups, group);
      ArrayPush(s.wants, want);
      ArrayPush(s.originals, -1);
      ArrayPush(s.applied, false);
    } else {
      s.wants[i] = want;
      s.applied[i] = false;
    }
    Cyberblock_Log(s"attitude: \(NameToString(group)) -> \(toward) (server)\(live ? "" : ", applied once Cyberblock is On")");
    if live {
      CyberblockHeat.ApplyAttitudes(game, s);
    }
  }

  // Every CyberblockSystem tick with a player: a tier or attitude that came while not live, and the raise check.
  public static func Tick(game: GameInstance, live: Bool) -> Void {
    let s = CyberblockHeatService.Get();
    if !IsDefined(s) || !live {
      return;
    }
    if s.hasTier && !s.synced {
      CyberblockHeat.Sync(game, s);
    }
    if ArrayContains(s.applied, false) {
      CyberblockHeat.ApplyAttitudes(game, s);
    }
    if s.retryIn > 0 {
      s.retryIn -= 1;
      if s.retryIn == 0 {
        s.synced = false; // the next tick tries the tier again
      }
    }
    if s.checkIn > 0 {
      s.checkIn -= 1;
      if s.checkIn == 0 {
        CyberblockHeat.Check(game, s);
      }
    }
  }

  // 2 s after a raise: reached (or capped above where it was) confirms the path; a lock keeps it for later; else a strike.
  private static func Check(game: GameInstance, s: ref<CyberblockHeatService>) -> Void {
    let now = CyberblockHeat.Stage(game);
    if now >= s.checkFor || now > s.checkFrom {
      s.heatStrikes = 0;
      if now < s.checkFor {
        Cyberblock_Log(s"heat: raise capped at \(now) (asked \(s.checkFor)), kept");
      }
      if !s.heatConfirmed {
        s.heatConfirmed = true;
        Cyberblock_Log(s"heat: path confirmed in game: wanted level \(now) after asking for \(s.checkFor)");
      }
      return;
    }
    let blocked = CyberblockHeat.Blocked(GameInstance.GetScriptableSystemsContainer(game).Get(n"PreventionSystem") as PreventionSystem);
    if StrLen(blocked) > 0 {
      s.synced = false; // Sync holds the raise until the block lifts
      Cyberblock_Log(s"heat: raise ignored (\(blocked), wanted level \(now)), kept");
      return;
    }
    let why = s"wanted level \(now) 2 s after asking for \(s.checkFor)";
    if s.heatStrikes > 0 {
      CyberblockHeat.FallHeat(s, s"\(why), twice in a row");
      return;
    }
    s.heatStrikes = 1;
    s.retryIn = 300;
    Cyberblock_Log(s"heat: raise ignored (\(why), strike 1 of 2), kept");
  }

  // Undoes what we wrote (Off, Lost, a non-host session, session end, detach); forget: the tier and the factions are no
  // longer the server's word for this host (a non-host session).
  public static func Restore(game: GameInstance, why: String, forget: Bool) -> Void {
    let s = CyberblockHeatService.Get();
    if !IsDefined(s) {
      return;
    }
    s.checkIn = 0;
    s.retryIn = 0;
    s.heatHeld = false;
    s.synced = false;
    if s.wrote > 0 {
      let now = CyberblockHeat.Stage(game);
      if now > 0 && now <= s.wrote {
        CyberblockHeat.Write(game, 0);
        Cyberblock_Log(s"heat: wanted level cleared (\(why))");
      }
      s.wrote = 0;
    }
    let attitudes = GameInstance.GetAttitudeSystem(game);
    let i = 0;
    while i < ArraySize(s.groups) {
      if s.applied[i] && s.originals[i] >= 0 && IsDefined(attitudes) {
        attitudes.SetAttitudeRelation(s.groups[i], n"player", IntEnum<EAIAttitude>(s.originals[i]));
        Cyberblock_Log(s"attitude: \(NameToString(s.groups[i])) back to \(CyberblockHeat.AttitudeName(s.originals[i])) (\(why))");
      }
      s.applied[i] = false;
      s.originals[i] = -1;
      i += 1;
    }
    if forget {
      s.hasTier = false;
      ArrayClear(s.groups);
      ArrayClear(s.wants);
      ArrayClear(s.originals);
      ArrayClear(s.applied);
    }
  }

  // On load: the save's wanted level and attitudes rule; our record of writes goes, the server's word stays. The
  // fallbacks end too (as in Spawns.Forget): each path is checked again, and logged again at its next event.
  public static func Forget() -> Void {
    let s = CyberblockHeatService.Get();
    if !IsDefined(s) {
      return;
    }
    s.synced = false;
    s.wrote = 0;
    s.checkIn = 0;
    s.retryIn = 0;
    s.heatHeld = false;
    s.heatStrikes = 0;
    s.heatFallback = false;
    s.heatLogged = false;
    s.attFallback = false;
    s.attLogged = false;
    let i = 0;
    while i < ArraySize(s.groups) {
      s.applied[i] = false;
      s.originals[i] = -1;
      i += 1;
    }
  }

  private static func Sync(game: GameInstance, s: ref<CyberblockHeatService>) -> Void {
    s.synced = true;
    if s.heatFallback {
      return;
    }
    let prevention = GameInstance.GetScriptableSystemsContainer(game).Get(n"PreventionSystem") as PreventionSystem;
    if !IsDefined(prevention) {
      CyberblockHeat.FallHeat(s, "no PreventionSystem");
      return;
    }
    let now = Cast<Int32>(prevention.GetHeatStageAsInt());
    if s.tier > now {
      let blocked = CyberblockHeat.Blocked(prevention);
      if StrLen(blocked) > 0 {
        s.synced = false; // tried again every tick, written once the block lifts
        if !s.heatHeld {
          s.heatHeld = true;
          Cyberblock_Log(s"heat: raise held (\(blocked), wanted level \(now)), kept");
        }
        return;
      }
      s.heatHeld = false;
      CyberblockHeat.Write(game, s.tier);
      s.wrote = s.tier;
      s.checkIn = 20;
      s.checkFor = s.tier;
      s.checkFrom = now;
      Cyberblock_Log(s"heat: wanted level \(now) -> \(s.tier) (server tier)");
      return;
    }
    if s.tier < now {
      if s.wrote > 0 && now <= s.wrote {
        CyberblockHeat.Write(game, s.tier);
        s.wrote = s.tier;
        Cyberblock_Log(s"heat: wanted level \(now) -> \(s.tier) (server tier)");
      } else {
        Cyberblock_Log(s"heat: tier \(s.tier) left V's own wanted level \(now)");
      }
    }
  }

  private static func Write(game: GameInstance, stage: Int32) -> Void {
    let request = new SetWantedLevel();
    request.m_wantedLevel = IntEnum<EPreventionHeatStage>(Clamp(stage, 0, 5));
    PreventionSystem.QueueRequest(game, request);
  }

  // Why the prevention system holds heat back right now ("" when it does not): a quest or scene lock source or the
  // stars blinking turn it off (IsSystemEnabled false, preventionSystem.swift 3213-3229, 1338), the console lock
  // (IsSystemLocked). A SetWantedLevel would force it on again, so a raise waits instead of overriding a quest.
  private static func Blocked(prevention: ref<PreventionSystem>) -> String {
    if !IsDefined(prevention) {
      return "";
    }
    if !prevention.IsSystemEnabled() {
      return "prevention disabled by a quest or scene";
    }
    return prevention.IsSystemLocked() ? "prevention locked" : "";
  }

  private static func Stage(game: GameInstance) -> Int32 {
    let prevention = GameInstance.GetScriptableSystemsContainer(game).Get(n"PreventionSystem") as PreventionSystem;
    return IsDefined(prevention) ? Cast<Int32>(prevention.GetHeatStageAsInt()) : 0;
  }

  private static func ApplyAttitudes(game: GameInstance, s: ref<CyberblockHeatService>) -> Void {
    if s.attFallback {
      return;
    }
    let attitudes = GameInstance.GetAttitudeSystem(game);
    if !IsDefined(attitudes) {
      CyberblockHeat.FallAttitude(s, "no AttitudeSystem");
      return;
    }
    let i = 0;
    while i < ArraySize(s.groups) {
      if !s.applied[i] {
        let group = s.groups[i];
        if s.originals[i] < 0 {
          s.originals[i] = EnumInt(attitudes.GetAttitudeRelation(group, n"player"));
        }
        attitudes.SetAttitudeRelation(group, n"player", IntEnum<EAIAttitude>(s.wants[i]));
        let now = EnumInt(attitudes.GetAttitudeRelation(group, n"player"));
        if now != s.wants[i] {
          CyberblockHeat.FallAttitude(s, s"\(NameToString(group)) read back as \(CyberblockHeat.AttitudeName(now)) after setting \(CyberblockHeat.AttitudeName(s.wants[i]))");
          return;
        }
        s.applied[i] = true;
        if !s.attConfirmed {
          s.attConfirmed = true;
          Cyberblock_Log(s"attitude: path confirmed in game: \(NameToString(group)) toward the player reads \(CyberblockHeat.AttitudeName(now))");
        }
      }
      i += 1;
    }
  }

  private static func FallHeat(s: ref<CyberblockHeatService>, why: String) -> Void {
    if s.heatFallback {
      return;
    }
    s.heatFallback = true;
    s.heatLogged = true;
    s.checkIn = 0;
    Cyberblock_Log(s"heat: path: none, no wanted-level writes; Minecraft squads are the Heat response (fallback: \(why))");
    Cyberblock_Notify(n"warning", "Server Heat", "Night City's police do not follow the server Heat here", 4.0);
  }

  private static func FallAttitude(s: ref<CyberblockHeatService>, why: String) -> Void {
    if s.attFallback {
      return;
    }
    s.attFallback = true;
    s.attLogged = true;
    Cyberblock_Log(s"attitude: path: none, no reputation writes (fallback: \(why))");
    Cyberblock_Notify(n"warning", "Gang Wars", "Night City's gangs do not follow your team's reputation here", 4.0);
  }

  private static func IsToward(word: String) -> Bool {
    return Equals(word, "friendly") || Equals(word, "neutral") || Equals(word, "hostile");
  }

  private static func AttitudeName(value: Int32) -> String {
    if value == EnumInt(EAIAttitude.AIA_Friendly) {
      return "friendly";
    }
    if value == EnumInt(EAIAttitude.AIA_Hostile) {
      return "hostile";
    }
    return "neutral";
  }
}
