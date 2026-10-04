// Breach Protocol results in the host's game (megaplan 2026-10-04 task 6-S1; section 1.9 row `daemon`; task 6-B1's
// results). Minecraft's Breach screen solves daemons on the server, which sends `daemon {id, dev, list, strength}` DOWN;
// the DLL (package 6D) queues an n"daemon" event (fields in Natives.reds) and Events.reds hands it to
// CyberblockDaemons.OnEvent whatever the state. Every request is answered once with Cyberblock_HackResult(id, ok,
// reason, 0, n"device") (the reply travels as `hackresult`), after one Cyberblock_HackSpread(id, npc, n"ping") per
// revealed or overloaded person (at most 16). Logged: 'daemon <list> s<strength> on <dev>: ok (<what each did>)' or
// 'daemon ...: <reason>' (followed by '(<what each did>; skipped <ids>)' when some ran).
//
// Daemon ids: a namespace ("cyberblock:") is dropped, case and '-' are ignored, and these names (with their aliases)
// run, each around the breached device at its strength 1-3. Radius and duration per strength are fixed here and mirrored
// by the shipped daemon content (data/cyberblock/cyberblock/daemon/<action>.json `tiers`, seconds 0 when untimed;
// tests/test_gen_hack_art.py keeps Radius() and Seconds() equal to them; the `daemon` message carries only the
// strength, so a datapack cannot change them), listed as radius / seconds for strength 1, 2, 3:
//   open_door (door, open, unlock, unlock_door)        doors unlocked and opened: the device itself when it is a door,
//                                                     else the nearest one (strength 1); every door in the radius (2-3).
//                                                     8 / 14 / 24 m.
//   disable_cameras (camera_off, cameras_off, camera_shutdown)   cameras switched off for the duration, then back on.
//                                                     15 / 25 / 40 m, 20 / 45 / 90 s.
//   disable_turrets (turret_off, turrets_off, turret_shutdown)   turrets likewise. 12 / 20 / 30 m, 20 / 40 / 80 s.
//   make_friendly                                      SetDeviceAttitude on the cameras and turrets (vanilla: it lasts
//                                                     until the device resets). 10 / 16 / 26 m.
//   friendly_cameras / friendly_turrets (camera_friendly, turrets_friendly, ...)   the same on cameras or turrets only.
//   lights_out (lights_off, blackout, power_off)       lights, signs and screens off for the duration, then back on.
//                                                     10 / 18 / 30 m, 20 / 40 / 90 s.
//   distract                                           the Phase 4 devact `distract` (the vanilla distraction quickhack,
//                                                     its own duration) on the screens, vending machines and lights.
//                                                     10 / 16 / 24 m.
//   traffic_switch                                     the intersection of the device (when it is a traffic light) or of
//                                                     the nearest traffic light flips (CyberblockHacks.FlipIntersection).
//                                                     20 / 35 / 60 m.
//   reveal_enemies (reveal, ping)                      hostile people revealed. 20 / 35 / 60 m, 10 / 20 / 40 s.
//   overload (short_circuit, overload_enemies)         the 1 / 2 / 4 nearest hackable hostile people within the radius
//                                                     get the vanilla Overload upload (QuickHack.OverloadLvl1Hack).
//                                                     5 / 8 / 12 m.
//   alarm (fail, failure)                              a failed Breach: the device's security system raises an ALARM
//                                                     (ScriptableDeviceComponentPS.TriggerSecuritySystemNotification,
//                                                     the vanilla security path); a device with no security system
//                                                     broadcasts an Alarm stim (StimBroadcasterComponent.BroadcastStim,
//                                                     20 / 30 / 40 m) instead, and one with neither alerts nothing; then
//                                                     V's wanted level +1 (Phase 0 e: PreventionSystem SetWantedLevel,
//                                                     checked 2 s later; when it did not rise the written fallback
//                                                     "Minecraft Heat only" takes over for good: logged by
//                                                     RemoteHacks.reds as 'rhack: heat path: ...').
// Other ids are skipped: the known ones of the list still run, and the answer is ok:false "unsupported daemon" (6P
// protocol: ok only when every id ran); the log line names what ran and what was skipped. A daemon that finds nothing
// in its radius still counts as run. The same leave-alone rules as RemoteHacks.reds: quest-marked and quest-locked
// devices, sealed doors, V in scene tier 3-5; the breached device must be streamed ("target gone" otherwise) and, once
// Phase 0 (h)'s fallback is in force, within 50 m of V ("cyberblock.hack.out_of_range" otherwise). Devices switched off come back on by timer and on ClearWorld
// (CyberblockRemoteService.SwitchOff), so a save never keeps them.
//
// Phase 0 runtime checks: the alarm path is chosen per device at run time (security system present or not) and named in
// the log line; heat is checked as above. The door, camera, turret and light actions are the Phase 4 device path
// (Devices.reds, its own checks); reveals and the Overload upload are the Ping chip's and Hacks.reds' paths.
//
// 2.31 script API (NativeDB, redscript), compile-checked: Device.GetDevicePS / GetDeviceState,
// ScriptableDeviceComponentPS.TriggerSecuritySystemNotification / GetSecuritySystem / ActionSetDeviceAttitude /
// ExecutePSAction / IsMarkedAsQuest / IsUnpowered (scriptableDeviceBasePS.swift 2088, 5087; deviceComponentBase.swift
// 565, 646), ESecurityNotificationType.ALARM, DoorControllerPS.IsLocked / IsSealed / IsOpen / ActionToggleLock /
// ActionSetOpened, SurveillanceCamera, SecurityTurret, SensorDevice, ElectricLight, InteractiveSign, BillboardDevice, TV,
// GameObject.GetStimBroadcasterComponent, StimBroadcasterComponent.BroadcastStim (stimBroadcasterComponent.swift 129),
// gamedataStimType.Alarm, stimInvestigateData, GameObject.SendForceRevealObjectEvent, StrSplit, StrLower, StrReplaceAll,
// StrAfterFirst, TrafficLight. Ours: CyberblockRemoteService / CyberblockRemoteHacks (RemoteHacks.reds),
// CyberblockHacks.Upload / Refusal / FlipIntersection (Hacks.reds), CyberblockDeviceService.QuestLocked / Kind and
// ScriptableDeviceComponentPS.CyberblockDistraction (Devices.reds), CyberblockIds.Hash.

public abstract class CyberblockDaemons {
  // From CyberblockEvents.Tick for every n"daemon", whatever the state: every request gets its Cyberblock_HackResult.
  public static func OnEvent(game: GameInstance, live: Bool) -> Void {
    let id = Cyberblock_EventId();
    let dev = Cyberblock_EventTarget();
    let strength = Clamp(RoundF(Cyberblock_EventAmount()), 1, 3);
    let list = CyberblockDaemons.List(Cyberblock_EventText(), Cyberblock_EventName());
    let service = CyberblockRemoteService.Get();
    let player = GetPlayer(game);
    let run = new CyberblockRemoteRun();
    run.kind = n"device";
    let reason = "Cyberblock is not active";
    if live && IsDefined(service) && IsDefined(player) {
      reason = CyberblockDaemons.Run(game, service, player, dev, list, strength, run);
    }
    CyberblockRemoteHacks.Answer(id, s"daemon \(CyberblockDaemons.Join(list)) s\(strength) on \(dev)", reason, run);
  }

  // The daemon ids: EventText split on ',', else EventName alone.
  private static func List(text: String, name: CName) -> array<String> {
    let list: array<String>;
    for part in StrSplit(text, ",") {
      let id = CyberblockDaemons.Normalize(part);
      if StrLen(id) > 0 {
        ArrayPush(list, id);
      }
    }
    if ArraySize(list) == 0 && IsNameValid(name) {
      let id = CyberblockDaemons.Normalize(NameToString(name));
      if StrLen(id) > 0 {
        ArrayPush(list, id);
      }
    }
    return list;
  }

  // "Cyberblock:Disable-Cameras " -> "disable_cameras".
  private static func Normalize(raw: String) -> String {
    let id = StrLower(StrReplaceAll(raw, " ", ""));
    if StrContains(id, ":") {
      id = StrAfterFirst(id, ":");
    }
    return StrReplaceAll(id, "-", "_");
  }

  private static func Join(list: array<String>) -> String {
    let text = "";
    for id in list {
      text += StrLen(text) > 0 ? s"+\(id)" : id;
    }
    return StrLen(text) > 0 ? text : "(none)";
  }

  // The daemon an id names (n"" for none).
  public static func Action(id: String) -> CName {
    if Equals(id, "open_door") || Equals(id, "door") || Equals(id, "open") || Equals(id, "unlock") || Equals(id, "unlock_door") || Equals(id, "open_doors") {
      return n"open_door";
    }
    if Equals(id, "disable_cameras") || Equals(id, "camera_off") || Equals(id, "cameras_off") || Equals(id, "camera_shutdown") {
      return n"disable_cameras";
    }
    if Equals(id, "disable_turrets") || Equals(id, "turret_off") || Equals(id, "turrets_off") || Equals(id, "turret_shutdown") {
      return n"disable_turrets";
    }
    if Equals(id, "friendly_cameras") || Equals(id, "camera_friendly") || Equals(id, "cameras_friendly") {
      return n"friendly_cameras";
    }
    if Equals(id, "friendly_turrets") || Equals(id, "turret_friendly") || Equals(id, "turrets_friendly") {
      return n"friendly_turrets";
    }
    if Equals(id, "make_friendly") {
      return n"make_friendly";
    }
    if Equals(id, "lights_out") || Equals(id, "lights_off") || Equals(id, "blackout") || Equals(id, "power_off") {
      return n"lights_out";
    }
    if Equals(id, "distract") {
      return n"distract";
    }
    if Equals(id, "traffic_switch") {
      return n"traffic_switch";
    }
    if Equals(id, "reveal_enemies") || Equals(id, "reveal") || Equals(id, "ping") {
      return n"reveal_enemies";
    }
    if Equals(id, "overload") || Equals(id, "short_circuit") || Equals(id, "overload_enemies") {
      return n"overload";
    }
    if Equals(id, "alarm") || Equals(id, "fail") || Equals(id, "failure") {
      return n"alarm";
    }
    return n"";
  }

  // "" when every daemon ran (run.note says what each did), else the refusal; with an unknown id the known ones still
  // run and the answer is "unsupported daemon".
  private static func Run(game: GameInstance, service: ref<CyberblockRemoteService>, player: ref<PlayerPuppet>, dev: Uint64, list: array<String>, strength: Int32, run: ref<CyberblockRemoteRun>) -> String {
    let tier = PlayerPuppet.GetSceneTier(player);
    if tier >= 3 && tier <= 5 {
      return "not hackable (V is in a scene)";
    }
    let device = CyberblockRemoteHacks.FindObject(game, dev) as Device;
    if !IsDefined(device) || !device.IsAttached() {
      return "target gone";
    }
    let far = service.RangeRefusal(player, device.GetWorldPosition());
    if StrLen(far) > 0 {
      return far;
    }
    let notes = "";
    let skipped = "";
    for id in list {
      let action = CyberblockDaemons.Action(id);
      if Equals(action, n"") {
        skipped += StrLen(skipped) > 0 ? s", \(id)" : id;
      } else {
        let note = CyberblockDaemons.Daemon(game, service, player, device, action, strength, run);
        notes += StrLen(notes) > 0 ? s"; \(note)" : note;
      }
    }
    if StrLen(skipped) > 0 {
      run.note = StrLen(notes) > 0 ? s"\(notes); skipped \(skipped)" : s"skipped \(skipped)";
      return "unsupported daemon";
    }
    run.note = notes;
    return "";
  }

  // The shipped daemon content's tiers (data/cyberblock/cyberblock/daemon/*.json): radius in metres at strength 1-3.
  private static func Radius(action: CName, strength: Int32) -> Float {
    if Equals(action, n"open_door") {
      return CyberblockDaemons.Tier(strength, 8.0, 14.0, 24.0);
    }
    if Equals(action, n"disable_cameras") {
      return CyberblockDaemons.Tier(strength, 15.0, 25.0, 40.0);
    }
    if Equals(action, n"disable_turrets") {
      return CyberblockDaemons.Tier(strength, 12.0, 20.0, 30.0);
    }
    if Equals(action, n"make_friendly") || Equals(action, n"friendly_cameras") || Equals(action, n"friendly_turrets") {
      return CyberblockDaemons.Tier(strength, 10.0, 16.0, 26.0);
    }
    if Equals(action, n"lights_out") {
      return CyberblockDaemons.Tier(strength, 10.0, 18.0, 30.0);
    }
    if Equals(action, n"distract") {
      return CyberblockDaemons.Tier(strength, 10.0, 16.0, 24.0);
    }
    if Equals(action, n"traffic_switch") {
      return CyberblockDaemons.Tier(strength, 20.0, 35.0, 60.0);
    }
    if Equals(action, n"reveal_enemies") {
      return CyberblockDaemons.Tier(strength, 20.0, 35.0, 60.0);
    }
    if Equals(action, n"overload") {
      return CyberblockDaemons.Tier(strength, 5.0, 8.0, 12.0);
    }
    return CyberblockDaemons.Tier(strength, 20.0, 30.0, 40.0); // alarm: the Alarm stim's reach
  }

  // Likewise the duration in seconds (for the daemons that time out).
  private static func Seconds(action: CName, strength: Int32) -> Float {
    if Equals(action, n"disable_cameras") {
      return CyberblockDaemons.Tier(strength, 20.0, 45.0, 90.0);
    }
    if Equals(action, n"disable_turrets") {
      return CyberblockDaemons.Tier(strength, 20.0, 40.0, 80.0);
    }
    if Equals(action, n"lights_out") {
      return CyberblockDaemons.Tier(strength, 20.0, 40.0, 90.0);
    }
    if Equals(action, n"reveal_enemies") {
      return CyberblockDaemons.Tier(strength, 10.0, 20.0, 40.0);
    }
    return 0.0;
  }

  private static func Tier(strength: Int32, one: Float, two: Float, three: Float) -> Float {
    return strength >= 3 ? three : (strength == 2 ? two : one);
  }

  // One daemon around device; what it did, for the log.
  private static func Daemon(game: GameInstance, service: ref<CyberblockRemoteService>, player: ref<PlayerPuppet>, device: ref<Device>, action: CName, strength: Int32, run: ref<CyberblockRemoteRun>) -> String {
    let center = device.GetWorldPosition();
    let radius = CyberblockDaemons.Radius(action, strength);
    let seconds = CyberblockDaemons.Seconds(action, strength);
    let name = NameToString(action);
    if Equals(action, n"open_door") {
      return s"\(name) \(CyberblockDaemons.OpenDoors(player, device, strength, radius))";
    }
    if Equals(action, n"traffic_switch") {
      let light = device as TrafficLight;
      if !IsDefined(light) {
        light = CyberblockRemoteHacks.NearestTrafficLight(center, radius) as TrafficLight;
      }
      if !IsDefined(light) {
        return s"\(name): no traffic light within \(RoundF(radius)) m";
      }
      return s"\(name) intersection of light \(CyberblockIds.Hash(light)) \(CyberblockHacks.FlipIntersection(light) ? "flipped" : "not flipped (no intersection)")";
    }
    if Equals(action, n"distract") {
      let n = 0;
      for object in CyberblockRemoteHacks.NearbyDevices(center, radius, 32) {
        let other = object as Device;
        if IsDefined(other) && CyberblockDaemons.Matches(action, other) && CyberblockDaemons.Distract(player, other) {
          n += 1;
        }
      }
      return s"\(name) \(n)";
    }
    if Equals(action, n"disable_cameras") || Equals(action, n"disable_turrets") || Equals(action, n"lights_out") {
      let serial = service.NextSerial();
      let n = 0;
      for object in CyberblockRemoteHacks.NearbyDevices(center, radius, 64) {
        let other = object as Device;
        if IsDefined(other) && CyberblockDaemons.Matches(action, other) && service.SwitchOff(other, player, serial) {
          n += 1;
        }
      }
      if n > 0 {
        service.ScheduleOff(game, serial, seconds);
      }
      return s"\(name) \(n) off for \(RoundF(seconds)) s";
    }
    if Equals(action, n"make_friendly") || Equals(action, n"friendly_cameras") || Equals(action, n"friendly_turrets") {
      let n = 0;
      for object in CyberblockRemoteHacks.NearbyDevices(center, radius, 64) {
        let sensor = object as SensorDevice;
        if IsDefined(sensor) && CyberblockDaemons.Matches(action, sensor) && CyberblockDaemons.Friendly(player, sensor) {
          n += 1;
        }
      }
      return s"\(name) \(n)";
    }
    if Equals(action, n"reveal_enemies") {
      let instigator = player.GetEntityID();
      let shown = CyberblockRemoteHacks.Nearby(game, player, center, radius, false, true, 16);
      for found in shown {
        GameObject.SendForceRevealObjectEvent(found, true, n"CyberblockDaemon", instigator, seconds);
        run.Reveal(found);
      }
      return s"\(name) \(ArraySize(shown)) for \(RoundF(seconds)) s";
    }
    if Equals(action, n"overload") {
      let count = strength >= 3 ? 4 : strength;
      let n = 0;
      for found in CyberblockRemoteHacks.Nearby(game, player, center, radius, false, true, 16) {
        let npc = found as ScriptedPuppet;
        if n < count && IsDefined(npc) && ScriptedPuppet.IsActive(npc) && StrLen(CyberblockHacks.Refusal(game, npc)) == 0 {
          CyberblockHacks.Upload(player, npc, t"QuickHack.OverloadLvl1Hack");
          run.Reveal(npc);
          n += 1;
        }
      }
      return s"\(name) \(n)";
    }
    if Equals(action, n"alarm") {
      return s"\(name) \(CyberblockDaemons.Alarm(player, device, radius)), heat \(CyberblockDaemons.Heat(game, service))";
    }
    return s"\(name) ?";
  }

  // Which devices a switch-off, friendly or distract daemon acts on.
  private static func Matches(action: CName, device: ref<Device>) -> Bool {
    if Equals(action, n"disable_cameras") || Equals(action, n"friendly_cameras") {
      return IsDefined(device as SurveillanceCamera);
    }
    if Equals(action, n"disable_turrets") || Equals(action, n"friendly_turrets") {
      return IsDefined(device as SecurityTurret);
    }
    if Equals(action, n"make_friendly") {
      return IsDefined(device as SurveillanceCamera) || IsDefined(device as SecurityTurret);
    }
    if Equals(action, n"lights_out") {
      return IsDefined(device as ElectricLight) || IsDefined(device as InteractiveSign) || IsDefined(device as BillboardDevice) || IsDefined(device as TV);
    }
    if Equals(action, n"distract") {
      let kind = CyberblockDeviceService.Kind(device);
      return Equals(kind, n"screen") || Equals(kind, n"vending") || Equals(kind, n"light");
    }
    return false;
  }

  // The Phase 4 devact `distract` (Devices.reds: ActionQuickHackDistraction, V the executor); false when left alone.
  private static func Distract(player: ref<PlayerPuppet>, device: ref<Device>) -> Bool {
    let ps = device.GetDevicePS();
    if !IsDefined(ps) || ps.IsMarkedAsQuest() || CyberblockDeviceService.QuestLocked(device) || ps.IsUnpowered() {
      return false;
    }
    let action = ps.CyberblockDistraction();
    if !IsDefined(action) {
      return false;
    }
    action.SetExecutor(player);
    ps.ExecutePSAction(action);
    return true;
  }

  // Unlocks (when powered) and opens doors: the breached door itself or the nearest one (strength 1), every door in the
  // radius (2-3). Sealed, quest-locked and quest-marked doors are left alone.
  private static func OpenDoors(player: ref<PlayerPuppet>, device: ref<Device>, strength: Int32, radius: Float) -> String {
    let opened = 0;
    let doors: array<ref<GameObject>>;
    if IsDefined(device as Door) {
      ArrayPush(doors, device);
    }
    if strength >= 2 || ArraySize(doors) == 0 {
      for object in CyberblockRemoteHacks.NearbyDevices(device.GetWorldPosition(), radius, 32) {
        if IsDefined(object as Door) && !ArrayContains(doors, object) && (strength >= 2 || ArraySize(doors) == 0) {
          ArrayPush(doors, object);
        }
      }
    }
    for object in doors {
      let door = object as Door;
      let ps: ref<DoorControllerPS>;
      if IsDefined(door) {
        ps = door.GetDevicePS() as DoorControllerPS;
      }
      if IsDefined(ps) &&!ps.IsSealed() && !ps.IsMarkedAsQuest() && !CyberblockDeviceService.QuestLocked(door) {
        if ps.IsLocked() && !ps.IsUnpowered() {
          let unlock = ps.ActionToggleLock();
          unlock.SetExecutor(player);
          ps.ExecutePSAction(unlock);
        }
        if !ps.IsOpen() {
          let open = ps.ActionSetOpened();
          open.SetExecutor(player);
          ps.ExecutePSAction(open);
          opened += 1;
        }
      }
    }
    return s"\(opened) of \(ArraySize(doors))";
  }

  // SetDeviceAttitude (friendly to V) on a camera or turret; false when it is left alone.
  private static func Friendly(player: ref<PlayerPuppet>, sensor: ref<SensorDevice>) -> Bool {
    let ps = sensor.GetDevicePS();
    if !IsDefined(ps) || ps.IsMarkedAsQuest() || CyberblockDeviceService.QuestLocked(sensor) || ps.IsUnpowered() {
      return false;
    }
    let action = ps.ActionSetDeviceAttitude();
    if !IsDefined(action) {
      return false;
    }
    action.SetExecutor(player);
    ps.ExecutePSAction(action);
    return true;
  }

  // The failed Breach's alarm; which path ran.
  private static func Alarm(player: ref<PlayerPuppet>, device: ref<Device>, reach: Float) -> String {
    let ps = device.GetDevicePS();
    if IsDefined(ps) && IsDefined(ps.GetSecuritySystem()) {
      ps.TriggerSecuritySystemNotification(player, player.GetWorldPosition(), ESecurityNotificationType.ALARM, true);
      return "via the device's security system (ALARM)";
    }
    if IsDefined(device.GetStimBroadcasterComponent()) {
      let investigate: stimInvestigateData;
      investigate.attackInstigator = player;
      investigate.attackInstigatorPosition = player.GetWorldPosition();
      investigate.revealsInstigatorPosition = true;
      StimBroadcasterComponent.BroadcastStim(device, gamedataStimType.Alarm, reach, investigate);
      return s"via an Alarm stim from the device, \(RoundF(reach)) m (no security system)";
    }
    return "none (no security system and no stim broadcaster on the device)";
  }

  private static func Heat(game: GameInstance, service: ref<CyberblockRemoteService>) -> String {
    let why = service.RaiseHeat(game);
    return StrLen(why) > 0 ? s"unchanged (\(why))" : "+1 star asked";
  }
}
