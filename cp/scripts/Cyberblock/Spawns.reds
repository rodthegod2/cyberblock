// Night City spawns for the Overlord and Server Heat (megaplan 2026-10-04 tasks 8-S1 and 8-O1; section 1.8 Phase 8 row,
// section 1.9 `spawn` / `spawnresult` with the Phase 0 amendment items 8 (no turret: turrets are Minecraft-side only);
// Phase 0 check (d), docs/notes/phase0-feasibility.md).
//
// Event n"spawn" (Events.reds hands it here whatever the state; every request is answered with Cyberblock_SpawnResult,
// which the DLL sends UP as `spawnresult {id, ok, n, reason}`):
//   Cyberblock_EventId = request id, Cyberblock_EventName = rec, Cyberblock_EventPos = the wire's `p` (Cyberpunk-space
//   feet), Cyberblock_EventAmount = `n` (1..8), Cyberblock_EventFlag = `hostile`, Cyberblock_EventText = `faction`
//   ("" none).
//   rec:  ganger      a gang member of `faction` (default Tyger Claws); the records of check (d)
//         ncpd        Character.ncpd_police_ranged2_lexington_ma (an NCPD patrol officer)
//         ncpd_heavy  Character.prevention_enforcer_rifle_ma (2.31 has no swat record; the Heat tier 3 heavy unit)
//         maxtac      Character.prevention_maxtac_rifle_ma
//         drone       Character.ncpd_base_drone_wyvern (faction arasaka: Character.arasaka_base_drone_octant)
//   Anything else (turret included) is answered "unsupported rec <rec>". Factions are the attitude group names
//   (tygerClaws, maelstrom, valentinos, 6thStreet, animals, voodooBoys, scavenger, wraiths, kurtz_army) or loose
//   spellings of them (tyger_claws, 6th_street, voodoo_boys, barghest, mox, ...), see CyberblockFactions.
//
// Method (Phase 0 d, desk verdict "yes", unconfirmed in game: CET D1-D5): Codeware DynamicEntitySystem.CreateEntity with
// a DynamicEntitySpec by record (persistState and persistSpawn false, never saved; tag n"CyberblockSpawn"), spread within
// 2 m of p. Once attached a hostile one gets the vanilla ambush treatment (DynamicSpawnSystem.ChangeAttitude and
// SpawnRequestFinished, dynamicSpawnSystem.swift): attitude group n"hostile", hostile toward V, and an
// AIInjectCombatThreatCommand on the player for 120 s. The answer waits until every NPC of the request attached, at most
// 5 s: ok with n = the attached count.
// Runtime feature check and the written fallback (plan 4.1 d): spawning fails (no DynamicEntitySystem, CreateEntity
// returns no id, none of a request attaches within 5 s) or the spawned NPCs stay passive (6 s after it attached, a
// hostile NPC and every other of its request are still Relaxed) -> the fallback for the rest of the game session: every
// spawn is answered ok:false "Night City spawns unavailable; Minecraft squads instead", so the
// server sends Minecraft squads, and the passive NPCs are deleted. One log line names the path in use at the first
// spawn, one more on the switch: 'spawns: path: <path>' / 'spawns: path: <fallback> (fallback: <why>)'; the first
// attached spawn and the first hostile one seen fighting are logged as confirmed in game.
// Limits: at most 24 spawned NPCs at once (a request gets what room is left; none: "spawn cap (24) reached"), p within
// 150 m of V ("too far from V (N m)"); each spawned NPC is deleted 300 s after it was created or once farther than 200 m
// from V, and forgotten once the game streamed it out after it attached. All are deleted when Night City stops being live (Off, Lost), on a non-host session, at session end and on
// detach (Clear), and forgotten on load (never saved).
// Log lines: 'spawns: <id> <rec>[ <faction>] x<n> <hostile|friendly> at <d> m from V: <k> created', 'spawns: <id> ok
// (<k> attached)', 'spawns: <id> refused (<why>)', 'spawns: cleared <k> (<why>)'.
//
// 2.31 script API (NativeDB, redscript) and Codeware 1.20.3, compile-checked by cp/scripts/compile_check.py:
// DynamicEntitySystem.IsReady/CreateEntity/GetEntity/DeleteEntity + DynamicEntitySpec (recordID, position, persistState,
// persistSpawn, spawnInView, active, tags), TweakDBInterface.GetCharacterRecord, GameObject.GetAttitudeAgent,
// AttitudeAgent.SetAttitudeGroup/SetAttitudeTowards, EAIAttitude, AIInjectCombatThreatCommand (targetPuppetRef,
// duration), CreateEntityReference, AIComponent.SendCommand, ScriptedPuppet.GetHighLevelStateFromBlackboard and
// gamedataNPCHighLevelState, Entity.IsAttached, Vector4.Distance, StrLower/StrReplaceAll.

public native func Cyberblock_SpawnResult(id: Int32, ok: Bool, count: Int32, reason: String) -> Void;

public class CyberblockSpawnsService extends ScriptableService {
  // The written fallback is in force (every spawn refused) and why.
  public let fallback: Bool;
  public let fallbackWhy: String;
  public let pathLogged: Bool;
  public let attachConfirmed: Bool;
  public let fightConfirmed: Bool;
  // One slot per spawned NPC (parallel arrays): entity, its request, hostile, attached (hostility applied), ticks since
  // it was created, ticks to its passive check (0 none).
  public let ids: array<EntityID>;
  public let requests: array<Int32>;
  public let hostile: array<Bool>;
  public let attached: array<Bool>;
  public let ages: array<Int32>;
  public let checks: array<Int32>;
  // Requests waiting for their answer (parallel arrays): id, NPCs created, ticks waited.
  public let pending: array<Int32>;
  public let pendingMade: array<Int32>;
  public let pendingWait: array<Int32>;

  public static func Get() -> ref<CyberblockSpawnsService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockSpawnsService") as CyberblockSpawnsService;
  }

  public static func Tag() -> CName {
    return n"CyberblockSpawn";
  }
}

public abstract class CyberblockSpawns {
  // An n"spawn" event is current (Cyberblock_NextEvent). live: Active or Vehicle, with a player, not saving.
  public static func OnEvent(game: GameInstance, live: Bool) -> Void {
    let id = Cyberblock_EventId();
    let s = CyberblockSpawnsService.Get();
    if !IsDefined(s) {
      Cyberblock_SpawnResult(id, false, 0, "Cyberblock is not ready");
      return;
    }
    CyberblockSpawns.LogPath(s);
    let rec = Cyberblock_EventName();
    let faction = Cyberblock_EventText();
    let count = Clamp(RoundF(Cyberblock_EventAmount()), 1, 8);
    let hostile = Cyberblock_EventFlag();
    let at = Cyberblock_EventPos();
    let player = GetPlayer(game);
    if !live || !IsDefined(player) {
      CyberblockSpawns.Refuse(id, "Cyberblock is not active");
      return;
    }
    if s.fallback {
      CyberblockSpawns.Refuse(id, "Night City spawns unavailable; Minecraft squads instead");
      return;
    }
    let entities = GameInstance.GetDynamicEntitySystem();
    if !IsDefined(entities) {
      CyberblockSpawns.Fall(game, "no DynamicEntitySystem (Codeware)");
      CyberblockSpawns.Refuse(id, "Night City spawns unavailable; Minecraft squads instead");
      return;
    }
    if !entities.IsReady() {
      CyberblockSpawns.Refuse(id, "Night City is not ready");
      return;
    }
    let record = CyberblockSpawns.Record(rec, faction);
    if !TDBID.IsValid(record) {
      CyberblockSpawns.Refuse(id, s"unsupported rec \(NameToString(rec))");
      return;
    }
    if !IsDefined(TweakDBInterface.GetCharacterRecord(record)) {
      Cyberblock_Log(s"spawns: no TweakDB record \(TDBID.ToStringDEBUG(record)) for \(NameToString(rec))");
      CyberblockSpawns.Refuse(id, s"unsupported rec \(NameToString(rec)) (no record)");
      return;
    }
    let distance = Vector4.Distance(player.GetWorldPosition(), at);
    if distance > 150.0 {
      CyberblockSpawns.Refuse(id, s"too far from V (\(RoundF(distance)) m)");
      return;
    }
    let room = 24 - ArraySize(s.ids);
    if room <= 0 {
      CyberblockSpawns.Refuse(id, "spawn cap (24) reached");
      return;
    }
    let n = Min(count, room);
    let made = 0;
    let i = 0;
    while i < n {
      let spec = new DynamicEntitySpec();
      spec.recordID = record;
      spec.position = CyberblockSpawns.Spot(at, i, n);
      spec.persistState = false;
      spec.persistSpawn = false;
      spec.spawnInView = true;
      spec.active = true;
      spec.tags = [CyberblockSpawnsService.Tag()];
      let entity = entities.CreateEntity(spec);
      if EntityID.IsDefined(entity) {
        ArrayPush(s.ids, entity);
        ArrayPush(s.requests, id);
        ArrayPush(s.hostile, hostile);
        ArrayPush(s.attached, false);
        ArrayPush(s.ages, 0);
        ArrayPush(s.checks, 0);
        made += 1;
      }
      i += 1;
    }
    let who = StrLen(faction) > 0 ? s"\(NameToString(rec)) \(faction)" : NameToString(rec);
    Cyberblock_Log(s"spawns: \(id) \(who) x\(count) \(hostile ? "hostile" : "friendly") at \(RoundF(distance)) m from V: \(made) created");
    if made == 0 {
      CyberblockSpawns.Fall(game, s"CreateEntity gave no entity for \(TDBID.ToStringDEBUG(record))");
      CyberblockSpawns.Refuse(id, "Night City spawns unavailable; Minecraft squads instead");
      return;
    }
    ArrayPush(s.pending, id);
    ArrayPush(s.pendingMade, made);
    ArrayPush(s.pendingWait, 0);
  }

  // Every CyberblockSystem tick with a player: hostility on attach, the answers, the passive checks, the age and
  // distance limits. live false: nothing of ours stays in Night City.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void {
    let s = CyberblockSpawnsService.Get();
    if !IsDefined(s) || ArraySize(s.ids) == 0 && ArraySize(s.pending) == 0 {
      return;
    }
    if !live {
      CyberblockSpawns.Clear(game, "Cyberblock is not active");
      return;
    }
    let entities = GameInstance.GetDynamicEntitySystem();
    if !IsDefined(entities) {
      return;
    }
    let center = player.GetWorldPosition();
    let i = ArraySize(s.ids) - 1;
    while i >= 0 {
      s.ages[i] += 1;
      let puppet = entities.GetEntity(s.ids[i]) as ScriptedPuppet;
      if !s.attached[i] && IsDefined(puppet) && puppet.IsAttached() {
        s.attached[i] = true;
        if !s.attachConfirmed {
          s.attachConfirmed = true;
          Cyberblock_Log("spawns: path confirmed in game: a spawned NPC attached");
        }
        if s.hostile[i] {
          CyberblockSpawns.MakeHostile(puppet, player);
          s.checks[i] = s.fightConfirmed ? 0 : 60;
        }
      }
      if s.checks[i] > 0 {
        s.checks[i] -= 1;
        if s.checks[i] == 0 && CyberblockSpawns.Passive(s, entities, i) {
          CyberblockSpawns.Fall(game, s"hostile spawns of request \(s.requests[i]) still Relaxed 6 s after they attached");
          return; // Fall cleared every spawn
        }
      }
      if s.ages[i] >= 3000 || IsDefined(puppet) && Vector4.Distance(puppet.GetWorldPosition(), center) > 200.0 {
        entities.DeleteEntity(s.ids[i]);
        CyberblockSpawns.Erase(s, i);
      } else if s.attached[i] && !IsDefined(puppet) {
        CyberblockSpawns.Erase(s, i); // streamed out or deleted by the game
      }
      i -= 1;
    }
    CyberblockSpawns.Answer(game, s);
  }

  // Deletes every spawned NPC and answers every waiting request with ok:false (Off, Lost, a non-host session, session
  // end, detach, the fallback).
  public static func Clear(game: GameInstance, why: String) -> Void {
    let s = CyberblockSpawnsService.Get();
    if !IsDefined(s) {
      return;
    }
    for request in s.pending {
      CyberblockSpawns.Refuse(request, s"cleared (\(why))");
    }
    ArrayClear(s.pending);
    ArrayClear(s.pendingMade);
    ArrayClear(s.pendingWait);
    let n = ArraySize(s.ids);
    let entities = GameInstance.GetDynamicEntitySystem();
    if IsDefined(entities) && n > 0 {
      for entity in s.ids {
        entities.DeleteEntity(entity);
      }
      entities.DeleteTagged(CyberblockSpawnsService.Tag());
    }
    CyberblockSpawns.Reset(s);
    if n > 0 {
      Cyberblock_Log(s"spawns: cleared \(n) (\(why))");
    }
  }

  // On load: spawns are never saved, so the slots are only forgotten; the fallback holds for one game session.
  public static func Forget() -> Void {
    let s = CyberblockSpawnsService.Get();
    if IsDefined(s) {
      ArrayClear(s.pending);
      ArrayClear(s.pendingMade);
      ArrayClear(s.pendingWait);
      CyberblockSpawns.Reset(s);
      s.fallback = false;
      s.fallbackWhy = "";
      s.pathLogged = false;
    }
  }

  // The verified character record of check (d) for rec and faction; TDBID.None() when there is none.
  public static func Record(rec: CName, faction: String) -> TweakDBID {
    if Equals(rec, n"ganger") {
      return CyberblockFactions.GangRecord(faction);
    }
    if Equals(rec, n"ncpd") {
      return t"Character.ncpd_police_ranged2_lexington_ma";
    }
    if Equals(rec, n"ncpd_heavy") {
      return t"Character.prevention_enforcer_rifle_ma";
    }
    if Equals(rec, n"maxtac") {
      return t"Character.prevention_maxtac_rifle_ma";
    }
    if Equals(rec, n"drone") {
      return Equals(CyberblockFactions.Key(faction), "arasaka") ? t"Character.arasaka_base_drone_octant" : t"Character.ncpd_base_drone_wyvern";
    }
    return TDBID.None();
  }

  private static func LogPath(s: ref<CyberblockSpawnsService>) -> Void {
    if !s.pathLogged {
      s.pathLogged = true;
      Cyberblock_Log(s.fallback
        ? s"spawns: path: none, every spawn refused so the server sends Minecraft squads (fallback: \(s.fallbackWhy))"
        : "spawns: path: Codeware DynamicEntitySystem.CreateEntity by record; hostile ones get group hostile and a combat threat on V (Phase 0 d, unconfirmed in game)");
    }
  }

  // The written fallback (plan 4.1 d) for the rest of this game session; every spawn so far is deleted.
  private static func Fall(game: GameInstance, why: String) -> Void {
    let s = CyberblockSpawnsService.Get();
    if !IsDefined(s) || s.fallback {
      return;
    }
    s.fallback = true;
    s.fallbackWhy = why;
    s.pathLogged = true;
    Cyberblock_Log(s"spawns: path: none, every spawn refused so the server sends Minecraft squads (fallback: \(why))");
    CyberblockSpawns.Clear(game, "Phase 0 d fallback");
  }

  private static func Refuse(id: Int32, why: String) -> Void {
    Cyberblock_SpawnResult(id, false, 0, why);
    Cyberblock_Log(s"spawns: \(id) refused (\(why))");
  }

  // The answers: a request is answered once each of its NPCs attached or is gone, or after 5 s.
  private static func Answer(game: GameInstance, s: ref<CyberblockSpawnsService>) -> Void {
    let p = ArraySize(s.pending) - 1;
    while p >= 0 {
      let request = s.pending[p];
      s.pendingWait[p] += 1;
      let waiting = 0;
      let attached = 0;
      let i = 0;
      while i < ArraySize(s.ids) {
        if s.requests[i] == request {
          if s.attached[i] {
            attached += 1;
          } else {
            waiting += 1;
          }
        }
        i += 1;
      }
      if waiting == 0 || s.pendingWait[p] >= 50 {
        let made = s.pendingMade[p];
        ArrayErase(s.pending, p);
        ArrayErase(s.pendingMade, p);
        ArrayErase(s.pendingWait, p);
        if attached > 0 {
          Cyberblock_SpawnResult(request, true, attached, "");
          Cyberblock_Log(s"spawns: \(request) ok (\(attached) attached)");
        } else {
          if waiting > 0 {
            CyberblockSpawns.Fall(game, s"none of \(made) spawned NPCs of request \(request) attached within 5 s");
            CyberblockSpawns.Refuse(request, "Night City spawns unavailable; Minecraft squads instead");
            return; // Fall cleared the rest
          }
          CyberblockSpawns.Refuse(request, "the spawned NPCs were gone before they attached");
        }
      }
      p -= 1;
    }
  }

  // Vanilla's ambush treatment (DynamicSpawnSystem.ChangeAttitude and SpawnRequestFinished).
  private static func MakeHostile(puppet: ref<ScriptedPuppet>, player: ref<PlayerPuppet>) -> Void {
    let agent = puppet.GetAttitudeAgent();
    let target = player.GetAttitudeAgent();
    if IsDefined(agent) && IsDefined(target) {
      agent.SetAttitudeGroup(n"hostile");
      agent.SetAttitudeTowards(target, EAIAttitude.AIA_Hostile);
    }
    let names: array<CName>;
    let threat = new AIInjectCombatThreatCommand();
    threat.targetPuppetRef = CreateEntityReference("#player", names);
    threat.duration = 120.0;
    AIComponent.SendCommand(puppet, threat);
  }

  // True when slot i is Relaxed and so is every other hostile NPC of its request (none fighting, alerted or sneaking);
  // a fighter confirms the path instead.
  private static func Passive(s: ref<CyberblockSpawnsService>, entities: ref<DynamicEntitySystem>, i: Int32) -> Bool {
    if s.fightConfirmed {
      return false;
    }
    let relaxed = false;
    let j = 0;
    while j < ArraySize(s.ids) {
      if s.requests[j] == s.requests[i] && s.hostile[j] && s.attached[j] {
        let puppet = entities.GetEntity(s.ids[j]) as ScriptedPuppet;
        if IsDefined(puppet) {
          let state = puppet.GetHighLevelStateFromBlackboard();
          if Equals(state, gamedataNPCHighLevelState.Combat) || Equals(state, gamedataNPCHighLevelState.Alerted) || Equals(state, gamedataNPCHighLevelState.Stealth) {
            s.fightConfirmed = true;
            Cyberblock_Log(s"spawns: path confirmed in game: hostile spawns of request \(s.requests[i]) fight (\(EnumInt(state)))");
            return false;
          }
          if Equals(state, gamedataNPCHighLevelState.Relaxed) {
            relaxed = true;
          }
        }
      }
      j += 1;
    }
    return relaxed;
  }

  // The i-th of n spots: the first on p, the rest on a 2 m circle around it.
  private static func Spot(at: Vector4, i: Int32, n: Int32) -> Vector4 {
    if i == 0 {
      return at;
    }
    let angle = 6.2831855 * Cast<Float>(i) / Cast<Float>(Max(n - 1, 1));
    return new Vector4(at.X + 2.0 * CosF(angle), at.Y + 2.0 * SinF(angle), at.Z, 1.0);
  }

  private static func Erase(s: ref<CyberblockSpawnsService>, i: Int32) -> Void {
    ArrayErase(s.ids, i);
    ArrayErase(s.requests, i);
    ArrayErase(s.hostile, i);
    ArrayErase(s.attached, i);
    ArrayErase(s.ages, i);
    ArrayErase(s.checks, i);
  }

  private static func Reset(s: ref<CyberblockSpawnsService>) -> Void {
    ArrayClear(s.ids);
    ArrayClear(s.requests);
    ArrayClear(s.hostile);
    ArrayClear(s.attached);
    ArrayClear(s.ages);
    ArrayClear(s.checks);
  }
}

// Faction names as Minecraft sends them (spawn `faction`, attitude `faction`) -> the 2.31 attitude group (TweakDB
// Attitudes.Group_*.name, Phase 0 e) and the gang's verified character record (Phase 0 d). Loose spellings are accepted:
// case, spaces, '_' and '-' are ignored.
public abstract class CyberblockFactions {
  public static func Key(faction: String) -> String {
    return StrReplaceAll(StrReplaceAll(StrReplaceAll(StrLower(faction), "_", ""), "-", ""), " ", "");
  }

  // The attitude group; n"" when there is none (the Mox have no group of their own in 2.31).
  public static func Group(faction: String) -> CName {
    let k = CyberblockFactions.Key(faction);
    if Equals(k, "tygerclaws") || Equals(k, "tygers") || Equals(k, "tyger") { return n"tygerClaws"; }
    if Equals(k, "maelstrom") { return n"maelstrom"; }
    if Equals(k, "valentinos") { return n"valentinos"; }
    if Equals(k, "6thstreet") || Equals(k, "sixthstreet") { return n"6thStreet"; }
    if Equals(k, "animals") { return n"animals"; }
    if Equals(k, "voodooboys") || Equals(k, "voodoo") { return n"voodooBoys"; }
    if Equals(k, "scavenger") || Equals(k, "scavengers") || Equals(k, "scavs") { return n"scavenger"; }
    if Equals(k, "wraiths") { return n"wraiths"; }
    if Equals(k, "kurtzarmy") || Equals(k, "barghest") { return n"kurtz_army"; }
    if Equals(k, "aldecaldos") { return n"aldecaldos"; }
    if Equals(k, "kangtao") { return n"kangtao"; }
    if Equals(k, "arasaka") { return n"arasaka"; }
    if Equals(k, "militech") { return n"militech"; }
    if Equals(k, "police") || Equals(k, "ncpd") { return n"police"; }
    return n"";
  }

  // The gang's ranged grunt (check d); an empty faction is the Tyger Claws; TDBID.None() for anything else.
  public static func GangRecord(faction: String) -> TweakDBID {
    let k = CyberblockFactions.Key(faction);
    if StrLen(k) == 0 || Equals(k, "tygerclaws") || Equals(k, "tygers") || Equals(k, "tyger") { return t"Character.tyger_claws_gangster1_ranged1_nue_ma"; }
    if Equals(k, "maelstrom") { return t"Character.maelstrom_grunt1_ranged1_copperhead_ma"; }
    if Equals(k, "valentinos") { return t"Character.valentinos_grunt1_ranged1_nova_ma"; }
    if Equals(k, "6thstreet") || Equals(k, "sixthstreet") { return t"Character.sixthstreet_hooligan_ranged1_nova_ma"; }
    if Equals(k, "animals") { return t"Character.animals_grunt1_ranged1_nova_mb"; }
    if Equals(k, "voodooboys") || Equals(k, "voodoo") { return t"Character.voodooboys_grunt1_ranged2_sidewinder_ma"; }
    if Equals(k, "scavenger") || Equals(k, "scavengers") || Equals(k, "scavs") { return t"Character.scavenger_grunt1_melee1_fists_ma"; }
    if Equals(k, "wraiths") { return t"Character.wraiths_grunt1_ranged1_nova_ma"; }
    if Equals(k, "mox") { return t"Character.kab_the_mox_1_ranged1_lexington_wa"; }
    if Equals(k, "kurtzarmy") || Equals(k, "barghest") { return t"Character.kurtz_soldier1_ranged2_rifle_ma"; }
    return TDBID.None();
  }
}
