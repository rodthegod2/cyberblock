// Minecraft quickhack items fired at Night City (plan 2026-10-03 sections 1.4 and 1.5, task S3.1). Minecraft owns RAM and
// cooldowns; Cyberpunk runs the hack through the game's own quickhack path with V as the executor, so the upload, the
// status effect (damage, VFX, SFX, AI reaction), NCPD heat and kill credit are vanilla. Every request is answered once
// with Cyberblock_HackResult(id, ok, reason); reasons: "target gone", "not hackable", "no such hack".
//
// 2.31 sources (decompiled 2.31 scripts; TweakDB read from r6/cache/tweakdb.bin and tweakdb_ep1.bin; compile-checked):
//   Puppet hacks: the PuppetAction pattern of ApplyObjectActionEffector.ActionOn / SpreadAreaEffector.ActionOn
//     (quickhackEffectors.swift): new PuppetAction, RegisterAsRequester(target), SetExecutor(V), SetObjectActionID,
//     SetUp(target.GetPuppetPS()), then ProcessRPGAction (baseDeviceActions.swift: queue or StartUpload, completion
//     effects). SetCanSkipPayCost(true) skips the RAM cost (CanSkipPayCost); SetDisableSpread(true) except Contagion
//     (audit B.5). ProcessRPGAction checks neither the cyberdeck nor the target's quickhack list (UI-side checks).
//   Record IDs (ObjectAction records present in both tweakdb.bin and tweakdb_ep1.bin; lowest tier that exists):
//     QuickHack.OverloadLvl1Hack, OverheatLvl1Hack, BlindLvl1Hack, LocomotionMalfunctionLvl2Hack (no Lvl1),
//     ContagionLvl2Hack (no Lvl1), BrainMeltLvl2Hack (no Lvl1), PingLvl2Hack (no Lvl1), MemoryWipeLvl2Hack (no Lvl1),
//     MadnessLvl3Hack (no Lvl1/Lvl2). Each is also looked up at run time (TweakDBInterface.GetObjectActionRecord).
//   Living target: ScriptedPuppet.IsActive (alive, not defeated, unconscious or turned off), the test the game's own
//     spread executor uses (EffectExecutor_Spread.Process). Hackable: vanilla's gates (ScriptedPuppet.IsQuickHackAble,
//     scriptedPuppet.swift:3217) that concern the target, so quest characters are left alone: not V's friends
//     (GameObject.IsFriendlyTowardsPlayer), no Invulnerable or Immortal god mode (GodModeSystem.HasGodMode; Immortal is
//     how quest-essential NPCs are kept alive), not animated by a scene (SceneSystemInterface.IsEntityInScene), and a
//     record that offers a PuppetQuickHack ObjectAction (Character_Record.ObjectActions, ObjectActionType().Type()),
//     which is how story NPCs are made unhackable. Not taken over: the cyberdeck check (Minecraft owns it), the crowd
//     and aggressiveness gates (Minecraft hacks pedestrians too), and QuickhackModule.IsQuickhackBlockedByScene, whose
//     NoQuickHacks tag Cyberblock's own NoCombat puts on V while Active (PlayerState.reds); only its other half is
//     kept: no hack while V is in scene tier 3-5 (PlayerPuppet.GetSceneTier). Crowd records offer no quickhack at all
//     (Character.AsianMale has only GenericInteraction.Talk, TweakDB), so a crowd NPC (Character_Record.IsCrowd)
//     counts unless its record has a quest affiliation other than General (Character_Record.Quest().Type();
//     e.g. Character.lizzies_customer_female_high: MainQuest).
//   Chain: GameObject.GetNPCsAroundObject(range) (TargetingSystem query, as SpreadAreaEffector), nearest other living NPC
//     within 8 m of the target.
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
//   Targets: Codeware EntityID.FromHash + GameInstance.FindEntityByID.

public abstract class CyberblockHacks {
  public static func Hack(game: GameInstance, id: Int32, target: Uint64, hack: CName, chain: Bool) -> Void {
    let reason = CyberblockHacks.Run(game, target, hack, chain);
    let ok = StrLen(reason) == 0;
    Cyberblock_HackResult(id, ok, reason);
    Cyberblock_Log(s"hack \(NameToString(hack)) on \(target)\(chain ? " (chain)" : ""): \(ok ? "ok" : reason)");
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

  // "" on success, else the reason sent back to Minecraft.
  private static func Run(game: GameInstance, target: Uint64, hack: CName, chain: Bool) -> String {
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
      return "not hackable"; // V is in a scene that blocks quickhacks
    }
    if isDevice {
      return CyberblockHacks.DeviceHack(player, object as Device);
    }
    let puppet = object as ScriptedPuppet;
    if !IsDefined(puppet) {
      return "not hackable";
    }
    if !ScriptedPuppet.IsActive(puppet) {
      return "target gone";
    }
    if !CyberblockHacks.Hackable(game, puppet) {
      return "not hackable";
    }
    let spread = Equals(hack, n"contagion");
    CyberblockHacks.Upload(player, puppet, action, spread);
    if chain {
      let next = CyberblockHacks.Nearest(game, puppet, 8.0);
      if IsDefined(next) {
        CyberblockHacks.Upload(player, next, action, spread);
      }
    }
    return "";
  }

  // Audit B.1: the game's own quickhack path, without RAM cost; spread only for Contagion.
  private static func Upload(player: ref<PlayerPuppet>, target: ref<ScriptedPuppet>, action: TweakDBID, spread: Bool) -> Void {
    let hack = new PuppetAction();
    hack.RegisterAsRequester(target.GetEntityID());
    hack.SetExecutor(player);
    hack.SetObjectActionID(action);
    hack.SetUp(target.GetPuppetPS());
    hack.SetCanSkipPayCost(true);
    hack.SetDisableSpread(!spread);
    hack.ProcessRPGAction(target.GetGame());
  }

  private static func Hackable(game: GameInstance, puppet: ref<ScriptedPuppet>) -> Bool {
    if GameObject.IsFriendlyTowardsPlayer(puppet) {
      return false;
    }
    let id = puppet.GetEntityID();
    let godModes = GameInstance.GetGodModeSystem(game);
    if godModes.HasGodMode(id, gameGodModeType.Invulnerable) || godModes.HasGodMode(id, gameGodModeType.Immortal) {
      return false;
    }
    if GameInstance.GetSceneSystem(game).GetScriptInterface().IsEntityInScene(id) {
      return false;
    }
    let record = puppet.GetRecord();
    if !IsDefined(record) {
      return false;
    }
    if record.IsCrowd() {
      let quest = record.Quest();
      return !IsDefined(quest) || Equals(quest.Type(), gamedataNPCQuestAffiliation.General);
    }
    let actions: array<wref<ObjectAction_Record>>;
    record.ObjectActions(actions);
    for action in actions {
      if IsDefined(action) && IsDefined(action.ObjectActionType()) && Equals(action.ObjectActionType().Type(), gamedataObjectActionType.PuppetQuickHack) {
        return true;
      }
    }
    return false;
  }

  // The nearest other living, hackable NPC within range metres of target, or null.
  private static func Nearest(game: GameInstance, target: ref<ScriptedPuppet>, range: Float) -> ref<ScriptedPuppet> {
    let best: ref<ScriptedPuppet>;
    let bestDistance2 = range * range;
    let center = target.GetWorldPosition();
    for npc in target.GetNPCsAroundObject(range) {
      if IsDefined(npc) && npc != target && !npc.IsPlayer() && ScriptedPuppet.IsActive(npc) && CyberblockHacks.Hackable(game, npc) {
        let distance2 = Vector4.DistanceSquared(npc.GetWorldPosition(), center);
        if distance2 <= bestDistance2 {
          best = npc;
          bestDistance2 = distance2;
        }
      }
    }
    return best;
  }

  // Audit B device hacks: cameras ignore V, traffic lights flip their intersection, media and vending devices
  // distract, anything else runs its first quickhack the device allows.
  private static func DeviceHack(player: ref<PlayerPuppet>, device: ref<Device>) -> String {
    if !IsDefined(device) || !IsDefined(device.GetDevicePS()) {
      return "not hackable";
    }
    if IsDefined(device as TrafficLight) {
      return CyberblockHacks.FlipIntersection(device) ? "" : "not hackable";
    }
    if IsDefined(device as SurveillanceCamera) {
      return CyberblockHacks.DeviceAction(player, device, n"ForceIgnoreTargets") ? "" : "not hackable";
    }
    if CyberblockHacks.DeviceAction(player, device, n"QuickHackDistraction") {
      return "";
    }
    return CyberblockHacks.DeviceAction(player, device, n"") ? "" : "not hackable";
  }

  // Runs the first remote action named name (any allowed one for n"") that the device allows. False if none.
  private static func DeviceAction(player: ref<PlayerPuppet>, device: ref<Device>, name: CName) -> Bool {
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
