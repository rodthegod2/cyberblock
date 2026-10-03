// Minecraft quickhack items fired at Night City (plan 2026-10-03 sections 1.4 and 1.5, task S3.1; re-test 3 plan
// 2026-10-03-retest3-polish section 1.6, task S1). Minecraft owns RAM and cooldowns; Cyberpunk runs the hack through the
// game's own quickhack path with V as the executor, so the upload, the status effect (damage, VFX, SFX, AI reaction),
// NCPD heat and kill credit are vanilla. Every request is answered once with Cyberblock_HackResult(id, ok, reason,
// upload, kind), after one Cyberblock_HackSpread(id, target, why) per extra NPC the hack reached (`chain`: the chain
// hack's second target; `spread`: Contagion's own spread). Reasons: "target gone", "not hackable (<which gate>)",
// "no driver", "no such hack" (at most 64 characters, protocol hackresult.reason). Each answer is logged as
// 'hack <name> on <id>: ok (<kind>, upload <s>s, +<n> spread)' or 'hack <name> on <id>: <reason>'.
//
// Re-test 3 (F1, "every quickhack feels the same"): the vanilla records were already distinct, but most targets are crowd
// pedestrians, on whom the squad- and AI-driven effects show nothing, and Minecraft reacted 1-3 s before the upload
// ended. So the answer now carries:
//   upload: the action's own upload time (ScriptableDeviceAction.GetActivationTime, baseDeviceActions.swift:444, read
//     after SetExecutor/SetObjectActionID as ProcessRPGAction reads it), 0 for an instant action; Minecraft times its
//     impact by it;
//   kind: `driver` (a car's driver), `crowd` (Character_Record.IsCrowd, or no AI squad: AISquadHelper.GetSquadName is
//     not a valid name), `squad` (anyone else); devices answer `camera`, `traffic`, `distract` or `device`.
// The upload-time extras that make a hack visible on any NPC live in HackFx.reds (CyberblockHackFx.Queue).
//
// 2.31 sources (decompiled 2.31 scripts; TweakDB read from r6/cache/tweakdb.bin and tweakdb_ep1.bin; compile-checked):
//   Puppet hacks: the PuppetAction pattern of ApplyObjectActionEffector.ActionOn / SpreadAreaEffector.ActionOn
//     (quickhackEffectors.swift): new PuppetAction, RegisterAsRequester(target), SetExecutor(V), SetObjectActionID,
//     SetUp(target.GetPuppetPS()), then ProcessRPGAction (baseDeviceActions.swift:308: queue or StartUpload, completion
//     effects) with the target's GameplayRoleComponent, as ScriptedPuppet.ExecuteAction (scriptedPuppet.swift:2875)
//     does, so the upload joins the target's quickhack queue (PutActionInQuickhackQueue). A record whose ActionName is
//     Ping is built as PingSquad, as ScriptedPuppetPS.GetAction (scriptedPuppetPS.swift:796) does. SetCanSkipPayCost(true)
//     skips the RAM cost (CanSkipPayCost). SetDisableSpread(true) on every upload: the vanilla spread reads V's spread
//     stats (HackingDataDef.GetValuesFromSpreadMap), which V has none of, so Contagion spreads itself (below).
//     ProcessRPGAction checks neither the cyberdeck nor the target's quickhack list (UI-side checks).
//   Record IDs (ObjectAction records present in both tweakdb.bin and tweakdb_ep1.bin; lowest tier that exists):
//     QuickHack.OverloadLvl1Hack, OverheatLvl1Hack, BlindLvl1Hack, LocomotionMalfunctionLvl2Hack (no Lvl1),
//     ContagionLvl2Hack (no Lvl1), BrainMeltLvl2Hack (no Lvl1), PingLvl2Hack (no Lvl1), MemoryWipeLvl2Hack (no Lvl1),
//     MadnessLvl3Hack (no Lvl1/Lvl2). Each is also looked up at run time (TweakDBInterface.GetObjectActionRecord).
//   Living target: ScriptedPuppet.IsActive (alive, not defeated, unconscious or turned off), the test the game's own
//     spread executor uses (EffectExecutor_Spread.Process). Hackable: vanilla's gates (ScriptedPuppet.IsQuickHackAble,
//     scriptedPuppet.swift:3217) that concern the target, so quest characters are left alone: not V's friends
//     (GameObject.IsFriendlyTowardsPlayer), no Invulnerable or Immortal god mode (GodModeSystem.HasGodMode; Immortal is
//     how quest-essential NPCs are kept alive), and a record that offers a PuppetQuickHack ObjectAction
//     (Character_Record.ObjectActions, ObjectActionType().Type()), which is how story NPCs are made unhackable. Not a
//     gate: SceneSystemInterface.IsEntityInScene, which the 2026-10-03 in-game test found true in free open-world play
//     (for V; commit 641e99f), so it refused ordinary people too. Each refusal names its gate in the reason
//     ("not hackable (god mode)", ...), which reaches Minecraft and the log. Not taken over: the cyberdeck check
//     (Minecraft owns it), the crowd and aggressiveness gates (Minecraft hacks pedestrians too), and QuickhackModule.IsQuickhackBlockedByScene, whose
//     NoQuickHacks tag Cyberblock's own NoCombat puts on V while Active (PlayerState.reds); only its other half is
//     kept: no hack while V is in scene tier 3-5 (PlayerPuppet.GetSceneTier). Crowd records offer no quickhack at all
//     (Character.AsianMale has only GenericInteraction.Talk, TweakDB), so a crowd NPC (Character_Record.IsCrowd)
//     counts unless its record has a quest affiliation other than General (Character_Record.Quest().Type();
//     e.g. Character.lizzies_customer_female_high: MainQuest).
//   Cars: a vehicle is no ScriptedPuppet, so a person hack aimed at one goes to its driver
//     (VehicleComponent.GetDriverMounted, the scanner's driver lookup); an empty car answers "no driver".
//   Chain and spread (NearestN): the nearest living, hackable NPCs around the target from the NPC registry that the
//     Minecraft proxies come from (CyberblockTetherService.Known, Npcs.reds), so every extra target has a proxy that
//     Minecraft can draw on; Cyberblock's mob stand-ins (CyberblockPuppets.IsPuppet) never count. The chain hack reaches
//     the nearest one within 8 m; Contagion uploads ContagionLvl2 (spread off) to the 2 nearest within 6 m of its target.
//   Device hacks (audit B, device hacks): Device.GetDevicePS(), ScriptableDeviceComponentPS.GenerateContext(
//     gamedeviceRequestType.Remote, Device.GetInteractionClearance(), V, device id) and GetRemoteActions (public; it
//     runs the device's GetQuickHackActions). A second list built with GetActionsContext.ignoresRPG = true tells which
//     actions the device itself allows: the RPG pass (FinalizeGetQuickHackActions -> EvaluateActionsRPGAvailabilty)
//     marks every device hack inactive for a V without a cyberdeck (instigator prereq StatPrereq HasCyberdeck,
//     DeviceAction.MalfunctionClassHack) and overwrites the device's own inactive reason, so it cannot be trusted
//     here; "LocKey#7021" (quickhacks not exposed by a breach) is UI gating too. The action that runs comes from the
//     normal list (same order: the same GetQuickHackActions), so it is marked as a quickhack with V as executor;
//     then RegisterAsRequester, SetExecutor, SetCanSkipPayCost(true), ProcessRPGAction, as Device.ExecuteAction.
//     Action names: QuickHackDistraction (mediaDeviceActions.swift; vending machines, arcades, TVs, radios...),
//     ForceIgnoreTargets for SurveillanceCamera (the first one is DeviceAction.OverrideAttitudeClassHack,
//     surveillanceCameraController.swift). ToggleTakeOverControl is never used: it makes the CCTV camera the active
//     camera, which Cyberblock streams to Minecraft. RemoteBreach (a minigame) is never used either.
//   Traffic lights: no player quickhack in 2.31 (TrafficLightControllerPS has no GetQuickHackActions); the whole
//     intersection flips through its TrafficIntersectionManagerControllerPS.HandleLightChangeRequest, reached through
//     the light's DeviceComponentPS.GetParents (audit CyberblockAudit.FlipIntersection).
//   Targets: Codeware EntityID.FromHash + GameInstance.FindEntityByID; ids on the wire through CyberblockIds.Hash.

// What one hack request did: its kind and upload time, the hacked person (null for devices) and every extra NPC it
// reached, in the order they are reported (parallel arrays).
public class CyberblockHackRun {
  public let kind: CName;
  public let upload: Float;
  public let target: wref<ScriptedPuppet>;
  public let chained: array<wref<ScriptedPuppet>>;
  public let extras: array<Uint64>;
  public let whys: array<CName>;

  public func Add(npc: ref<ScriptedPuppet>, why: CName) -> Void {
    ArrayPush(this.extras, CyberblockIds.Hash(npc));
    ArrayPush(this.whys, why);
  }
}

public abstract class CyberblockHacks {
  public static func Hack(game: GameInstance, id: Int32, target: Uint64, hack: CName, chain: Bool) -> Void {
    let run = new CyberblockHackRun();
    let reason = CyberblockHacks.Run(game, target, hack, chain, run);
    let ok = StrLen(reason) == 0;
    if !ok {
      Cyberblock_HackResult(id, false, reason, 0.0, n"");
      Cyberblock_Log(s"hack \(NameToString(hack)) on \(target): \(reason)");
      return;
    }
    // chain/spread go before the result (protocol hackspread)
    let i = 0;
    while i < ArraySize(run.extras) {
      Cyberblock_HackSpread(id, run.extras[i], run.whys[i]);
      i += 1;
    }
    Cyberblock_HackResult(id, true, "", run.upload, run.kind);
    Cyberblock_Log(s"hack \(NameToString(hack)) on \(target): ok (\(NameToString(run.kind)), upload \(run.upload)s, +\(ArraySize(run.extras)) spread)");
  }

  // The quickhack ObjectAction for a hack name (plan section 1.5), TDBID.None() for device or unknown names.
  public static func ActionFor(hack: CName) -> TweakDBID {
    if Equals(hack, n"short_circuit") {
      return t"QuickHack.OverloadLvl1Hack";
    }
    if Equals(hack, n"overheat") {
      return t"QuickHack.OverheatLvl1Hack";
    }
    if Equals(hack, n"reboot_optics") {
      return t"QuickHack.BlindLvl1Hack";
    }
    if Equals(hack, n"cripple_movement") {
      return t"QuickHack.LocomotionMalfunctionLvl2Hack";
    }
    if Equals(hack, n"contagion") {
      return t"QuickHack.ContagionLvl2Hack";
    }
    if Equals(hack, n"synapse_burnout") {
      return t"QuickHack.BrainMeltLvl2Hack";
    }
    if Equals(hack, n"ping") {
      return t"QuickHack.PingLvl2Hack";
    }
    if Equals(hack, n"memory_wipe") {
      return t"QuickHack.MemoryWipeLvl2Hack";
    }
    if Equals(hack, n"cyberpsychosis") {
      return t"QuickHack.MadnessLvl3Hack";
    }
    return TDBID.None();
  }

  // section 1.6 kind of a person target: crowd (a crowd record, or no AI squad) or squad.
  public static func PersonKind(puppet: ref<ScriptedPuppet>) -> CName {
    let record = puppet.GetRecord();
    if IsDefined(record) && record.IsCrowd() || !IsNameValid(AISquadHelper.GetSquadName(puppet)) {
      return n"crowd";
    }
    return n"squad";
  }

  // "" on success (run filled in), else the reason sent back to Minecraft.
  private static func Run(game: GameInstance, target: Uint64, hack: CName, chain: Bool, run: ref<CyberblockHackRun>) -> String {
    let isDevice = Equals(hack, n"device");
    let action = CyberblockHacks.ActionFor(hack);
    if !isDevice && (!TDBID.IsValid(action) || !IsDefined(TweakDBInterface.GetObjectActionRecord(action))) {
      return "no such hack";
    }
    let player = GetPlayer(game);
    let object = GameInstance.FindEntityByID(game, EntityID.FromHash(target)) as GameObject;
    if !IsDefined(player) || !IsDefined(object) {
      return "target gone";
    }
    let tier = PlayerPuppet.GetSceneTier(player);
    if tier >= 3 && tier <= 5 {
      return "not hackable (V is in a scene)";
    }
    if isDevice {
      return CyberblockHacks.DeviceHack(player, object as Device, run);
    }
    // a car is hacked through its driver (the scanner's VehicleComponent.GetDriverMounted, as Scan.reds)
    let vehicle = object as VehicleObject;
    if IsDefined(vehicle) {
      object = VehicleComponent.GetDriverMounted(game, vehicle.GetEntityID());
      if !IsDefined(object) || object == player {
        return "no driver";
      }
    }
    let puppet = object as ScriptedPuppet;
    if !IsDefined(puppet) {
      return "not hackable (not a person)";
    }
    if !ScriptedPuppet.IsActive(puppet) {
      return "target gone";
    }
    let refusal = CyberblockHacks.Refusal(game, puppet);
    if StrLen(refusal) > 0 {
      return s"not hackable (\(refusal))";
    }
    run.kind = IsDefined(vehicle) ? n"driver" : CyberblockHacks.PersonKind(puppet);
    run.target = puppet;
    run.upload = CyberblockHacks.Upload(player, puppet, action);
    let reached: array<ref<ScriptedPuppet>>;
    ArrayPush(reached, puppet);
    if chain {
      for extra in CyberblockHacks.NearestN(game, puppet, 8.0, 1, reached) {
        CyberblockHacks.Upload(player, extra, action);
        ArrayPush(reached, extra);
        ArrayPush(run.chained, extra);
        run.Add(extra, n"chain");
      }
    }
    if Equals(hack, n"contagion") {
      for extra in CyberblockHacks.NearestN(game, puppet, 6.0, 2, reached) {
        CyberblockHacks.Upload(player, extra, action);
        ArrayPush(reached, extra);
        run.Add(extra, n"spread");
      }
    }
    return "";
  }

  // Audit B.1: the game's own quickhack path, without RAM cost and without the vanilla spread; the action's upload
  // time (seconds, 0 = instant) is returned.
  private static func Upload(player: ref<PlayerPuppet>, target: ref<ScriptedPuppet>, action: TweakDBID) -> Float {
    let record = TweakDBInterface.GetObjectActionRecord(action);
    let hack: ref<PuppetAction>;
    if IsDefined(record) && Equals(record.ActionName(), n"Ping") {
      hack = new PingSquad();
    } else {
      hack = new PuppetAction();
    }
    hack.RegisterAsRequester(target.GetEntityID());
    hack.SetExecutor(player);
    hack.SetObjectActionID(action);
    hack.SetUp(target.GetPuppetPS());
    hack.SetCanSkipPayCost(true);
    hack.SetDisableSpread(true);
    let upload = MaxF(hack.GetActivationTime(), 0.0);
    hack.ProcessRPGAction(target.GetGame(), target.GetGameplayRoleComponent());
    return upload;
  }

  // Which of the header's gates keeps puppet from being hacked ("" when none does).
  public static func Refusal(game: GameInstance, puppet: ref<ScriptedPuppet>) -> String {
    if GameObject.IsFriendlyTowardsPlayer(puppet) {
      return "friendly";
    }
    let id = puppet.GetEntityID();
    let godModes = GameInstance.GetGodModeSystem(game);
    if godModes.HasGodMode(id, gameGodModeType.Invulnerable) || godModes.HasGodMode(id, gameGodModeType.Immortal) {
      return "god mode";
    }
    let record = puppet.GetRecord();
    if !IsDefined(record) {
      return "no record";
    }
    if record.IsCrowd() {
      let quest = record.Quest();
      return !IsDefined(quest) || Equals(quest.Type(), gamedataNPCQuestAffiliation.General) ? "" : "quest character";
    }
    let actions: array<wref<ObjectAction_Record>>;
    record.ObjectActions(actions);
    for action in actions {
      if IsDefined(action) && IsDefined(action.ObjectActionType()) && Equals(action.ObjectActionType().Type(), gamedataObjectActionType.PuppetQuickHack) {
        return "";
      }
    }
    return "no quickhack actions";
  }

  // The n nearest living, hackable NPCs within range metres of target (nearest first), from the NPC registry; never
  // V, a mob stand-in or one in exclude.
  public static func NearestN(game: GameInstance, target: ref<GameObject>, range: Float, n: Int32, exclude: array<ref<ScriptedPuppet>>) -> array<ref<ScriptedPuppet>> {
    let picked: array<ref<ScriptedPuppet>>;
    let pickedDistance2: array<Float>;
    let tether = CyberblockTetherService.Get();
    if !IsDefined(tether) || !IsDefined(target) || n <= 0 {
      return picked;
    }
    let center = target.GetWorldPosition();
    let range2 = range * range;
    for object in tether.Known() {
      let npc = object as ScriptedPuppet;
      if IsDefined(npc) && !npc.IsPlayer() && !ArrayContains(exclude, npc) && npc.IsAttached() {
        let distance2 = Vector4.DistanceSquared(npc.GetWorldPosition(), center);
        if distance2 <= range2 && ScriptedPuppet.IsActive(npc) && !CyberblockPuppets.IsPuppet(game, npc.GetEntityID())
          && StrLen(CyberblockHacks.Refusal(game, npc)) == 0 {
          let at = ArraySize(picked);
          while at > 0 && pickedDistance2[at - 1] > distance2 {
            at -= 1;
          }
          if at < n {
            ArrayInsert(picked, at, npc);
            ArrayInsert(pickedDistance2, at, distance2);
            if ArraySize(picked) > n {
              ArrayResize(picked, n);
              ArrayResize(pickedDistance2, n);
            }
          }
        }
      }
    }
    return picked;
  }

  // Audit B device hacks: cameras ignore V, traffic lights flip their intersection, media and vending devices
  // distract, anything else runs its first quickhack the device allows. Sets run.kind and run.upload.
  private static func DeviceHack(player: ref<PlayerPuppet>, device: ref<Device>, run: ref<CyberblockHackRun>) -> String {
    if !IsDefined(device) || !IsDefined(device.GetDevicePS()) {
      return "not hackable (not a device)";
    }
    if IsDefined(device as TrafficLight) {
      run.kind = n"traffic";
      return CyberblockHacks.FlipIntersection(device) ? "" : "not hackable (no intersection)";
    }
    if IsDefined(device as SurveillanceCamera) {
      run.kind = n"camera";
      return CyberblockHacks.DeviceAction(player, device, n"ForceIgnoreTargets", run) ? "" : "not hackable (camera allows no hack)";
    }
    run.kind = n"distract";
    if CyberblockHacks.DeviceAction(player, device, n"QuickHackDistraction", run) {
      return "";
    }
    run.kind = n"device";
    return CyberblockHacks.DeviceAction(player, device, n"", run) ? "" : "not hackable (device allows no hack)";
  }

  // Runs the first remote action named name (any allowed one for n"") that the device allows, its upload time in
  // run.upload. False if none.
  private static func DeviceAction(player: ref<PlayerPuppet>, device: ref<Device>, name: CName, run: ref<CyberblockHackRun>) -> Bool {
    let ps = device.GetDevicePS();
    let deviceID = device.GetEntityID();
    let probeContext = ps.GenerateContext(gamedeviceRequestType.Remote, Device.GetInteractionClearance(), player, deviceID);
    probeContext.ignoresRPG = true;
    let context = ps.GenerateContext(gamedeviceRequestType.Remote, Device.GetInteractionClearance(), player, deviceID);
    let probe: array<ref<DeviceAction>>;
    let actions: array<ref<DeviceAction>>;
    ps.GetRemoteActions(probe, probeContext);
    ps.GetRemoteActions(actions, context);
    if ArraySize(probe) != ArraySize(actions) {
      Cyberblock_Log(s"hack: device action lists differ (\(ArraySize(probe)) vs \(ArraySize(actions)))");
      return false;
    }
    let i = 0;
    while i < ArraySize(probe) {
      let allowed = probe[i] as ScriptableDeviceAction;
      let action = actions[i] as ScriptableDeviceAction;
      if IsDefined(allowed) && IsDefined(action) && Equals(action.actionName, allowed.actionName)
        && (!IsNameValid(name) || Equals(allowed.actionName, name)) && CyberblockHacks.Allowed(allowed) {
        action.RegisterAsRequester(deviceID);
        action.SetExecutor(player);
        action.SetCanSkipPayCost(true);
        run.upload = MaxF(action.GetActivationTime(), 0.0);
        action.ProcessRPGAction(player.GetGame());
        return true;
      }
      i += 1;
    }
    return false;
  }

  private static func Allowed(action: ref<ScriptableDeviceAction>) -> Bool {
    if Equals(action.actionName, n"ToggleTakeOverControl") || IsDefined(action as ToggleTakeOverControl) || Equals(action.actionName, n"RemoteBreach") {
      return false;
    }
    return !action.IsInactive() || Equals(action.GetInactiveReason(), "LocKey#7021");
  }

  // Audit B.4: the intersection manager flips every light of the intersection.
  private static func FlipIntersection(light: ref<Device>) -> Bool {
    let parents: array<ref<DeviceComponentPS>>;
    light.GetDevicePS().GetParents(parents);
    for parent in parents {
      let manager = parent as TrafficIntersectionManagerControllerPS;
      if IsDefined(manager) {
        manager.HandleLightChangeRequest();
        return true;
      }
    }
    return false;
  }
}
