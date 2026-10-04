// Remote hacks from Minecraft's interaction map (megaplan 2026-10-04 task 6-S1; section 1.8 row 6 and section 1.9 rows
// `rhack` / `hackresult`; Phase 0 checks (c) and (h), docs/notes/phase0-feasibility.md). Minecraft's Vehicle Panel, Traffic
// Tool and Net Map send `rhack {id, target, hack}` DOWN; the DLL (package 6D) queues an n"rhack" event (fields in
// Natives.reds) and Events.reds hands it to CyberblockRemoteHacks.OnEvent whatever the state. Every request is answered
// once with Cyberblock_HackResult(id, ok, reason, 0, kind) (the reply travels as `hackresult`), after one
// Cyberblock_HackSpread(id, target, n"ping") per figure an outline or area ping revealed (at most 16). Each answer is
// logged 'rhack <hack> on <target>: ok (<note>)' or 'rhack <hack> on <target>: <reason>'.
//
// Hacks (the target is a car, a person or a device id as the npcs / devices streams sent it; 0 = none):
//   veh_lock        the car's doors locked (a person's car when the target is a person), restored after 120 s (15 s on
//                   V's own car, so V is never shut in for long).
//   veh_engine_off  vehicle and engine off and held off for 30 s (10 s on V's car); a moving car is braked to a stop;
//                   the engine comes back when it ran before (always on V's car).
//   veh_brake       brakes: on an NPC's car the Force Brakes quickhack's own call; on V's car the NCPD hack loop's call
//                   (5 s, with the immobilized effect).
//   veh_start       vehicle and engine on (a held car is released first).
//   veh_jam         a temporary loss of control, 3 s of horn and of the immobilized effect.
//   traffic_switch  the intersection of the target traffic light (or the nearest one within 40 m of the target) flips.
//   gridlock        the cars within 25 m of the target (at most 12, never V's) stop for 60 s; the nearest intersection
//                   within 40 m flips.
//   blackout        the lights and devices within 150 m of V switched off for N minutes (EventAmount, default 2,
//                   0.5..10) and the city lights forced off; then back on. EventText (a district) is only logged.
//   cam_feed        a powered camera's position and yaw are logged and confirmed: Minecraft renders the replica from the
//                   camera's devices-stream position and yaw (the plan's fallback for a true camera view).
//   outline         people and cars within 25 m of the target revealed 15 s (nearest 16), each reported `ping`.
//   ping_area       the vanilla Ping upload on the hackable person nearest the point, then the hostile people, cameras
//                   and turrets within 30 m revealed 10 s (nearest 16 people reported `ping`).
// Refusals (at most 64 characters, protocol hackresult.reason): "Cyberblock is not active", "target gone", "no such
// hack", "out of range", "not hackable (<why>)", "no traffic light near", "no traffic near", "camera off". Kinds:
// traffic_switch and gridlock `traffic`, blackout `device`, cam_feed `camera`, the rest none.
//
// Phase 0 runtime checks (the CET session has not run): every call below that 0R did not prove in game is checked on its
// first uses, and its written fallback takes over for good when it did nothing; each feature logs one
// 'rhack: <feature> path: <path>' line when first used, 'rhack: <feature> path confirmed in game: ...' once a check
// passed, and 'rhack: <feature> path: <fallback> (fallback: <why>)' on a switch:
//   veh_lock    VehicleQuestChangeDoorStateEvent LockAll queued to the car's persistent state (as quests and
//               VehicleComponent.OnRemoteControlEvent do); check 1 s later: the front-left door is Locked; fallback
//               VehicleComponentPS.SetDoorInteractionState Locked on each of the 6 doors.
//   veh_engine  VehicleQuestToggleEngineEvent (lockState Lock / Unlock) queued the same way; check 1.5 s later
//               (IsEngineTurnedOn); fallback the calls its handler makes (VehicleComponent.ToggleVehicleSystems:
//               LockVehicleOnState, TurnVehicleOn, TurnEngineOn).
//   veh_brake   VehicleObject.ActivateNetrunnerQuickhack(ForceBrakes) (VehicleComponentPS.OnVehicleOverrideForceBrakes);
//               check 1.5 s later on a car that moved faster than 3 m/s (speed below 60 % of the start, or 2 m/s);
//               fallback ForceBrakesUntilStoppedOrFor(6).
//   gridlock    VehicleObject.ForceBrakesFor(60) (no vanilla caller); check 3 s later on the first moving car; fallback
//               the engine held off 60 s (as veh_engine_off's fallback).
//   citylights  ForceCLSStateRequest ForcedOFF (source n"CyberblockBlackout", priority High, not savable) queued to
//               CityLightSystem (as DeviceOperations' CLS request); check 1 s later: CityLightSystem.GetState is ForcedOFF;
//               else devices only.
//   device_off  ScriptableDeviceComponentPS.ExecutePSAction(ActionSetDeviceOFF), V the executor (blackout, daemons; the
//               Phase 4 devact path); check 1 s later: at least one of the batch is OFF; else Phase 0 (c)'s fallback (the
//               action is not callable: devices stay as they are, logged).
//   range (h)   Phase 0 (h): every streamed target, no distance limit. When a car hack took no effect by either path
//               on a car more than 50 m from V, the written fallback takes over: remote hacks and daemons only within
//               50 m of V (farther targets answer "out of range"); logged 'rhack: remote range: ...'.
// Not checkable from scripts (logged path, needsInGame): veh_brake on V's car, veh_jam, traffic_switch, blackout's
// devices, cam_feed, outline and ping_area. No 2.31 script call switches traffic lights or stops a road's traffic
// (TrafficSystem has only IsPathIntersectingWithTraffic and FindEntitiesNearPlane), and none lists the devices of a
// district, so traffic_switch, gridlock and blackout run on the written fallbacks (Phase 4 devices, the cars near the
// point, the devices within 150 m of V).
//
// Night City is left as vanilla as possible: held engines, locked doors, switched-off devices and the forced city lights
// are restored by their timers, and all at once by Clear (CyberblockSystem.ClearWorld: before every save, on leaving
// live, on load and session end), so a save never keeps them. Quest-marked and quest-locked devices, cars in a scene,
// AVs and destroyed cars are left alone; nothing runs while V is in scene tier 3-5.
//
// 2.31 script API (NativeDB, redscript), compile-checked: VehicleObject.GetVehiclePS / IsPlayerMounted / IsDestroyed /
// IsPerformingSceneAnimation / IsEngineTurnedOn / TurnVehicleOn / TurnEngineOn / LockVehicleOnState / ForceBrakesFor /
// ForceBrakesUntilStoppedOrFor / ActivateNetrunnerQuickhack / ActivateTemporaryLossOfControl / ToggleHorn /
// GetLinearVelocity (vehicles.swift 2-282), VehicleComponentPS.GetDoorInteractionState / SetDoorInteractionState
// (vehicleComponentPS.swift 430, 434), VehicleQuestChangeDoorStateEvent / VehicleQuestToggleEngineEvent (1799, 1812),
// GamePersistencySystem.QueuePSEvent, PersistentState.GetID, VehicleComponent.GetVehicle, AVObject,
// GameObjectEffectHelper.StartEffectEvent / StopEffectEvent (n"immobilized", preventionSystemHackerLoop.swift 514, 497),
// TrafficLight, CityLightSystem.GetState / ForceCLSStateRequest (cityLightSystem.swift 53, 598),
// GameInstance.QueueScriptableSystemRequest, ScriptableDeviceComponentPS.ActionSetDeviceON / ActionSetDeviceOFF /
// ExecutePSAction / IsON / IsOFF / IsMarkedAsQuest, GameObject.SendForceRevealObjectEvent, SurveillanceCamera,
// SecurityTurret, IsHostileTowardsPlayer, DelaySystem.DelayCallback. Ours: CyberblockDeviceService.Find / Known
// (Devices.reds), CyberblockTetherService.Known (Tether.reds), CyberblockHacks.Upload / FlipIntersection / Refusal
// (Hacks.reds), CyberblockIds.Hash, CyberblockEffects.Find, CyberblockPuppets.IsPuppet.

// What one request did: its kind, a note for the log, and the figures it revealed (reported before the result).
public class CyberblockRemoteRun {
  public let kind: CName;
  public let note: String;
  public let spread: array<Uint64>;

  public func Reveal(object: ref<GameObject>) -> Void {
    if ArraySize(this.spread) < 16 {
      ArrayPush(this.spread, CyberblockIds.Hash(object));
    }
  }
}

public abstract class CyberblockRemoteHacks {
  // From CyberblockEvents.Tick for every n"rhack", whatever the state: every request gets its Cyberblock_HackResult.
  public static func OnEvent(game: GameInstance, live: Bool) -> Void {
    let id = Cyberblock_EventId();
    let hack = Cyberblock_EventName();
    let target = Cyberblock_EventTarget();
    let service = CyberblockRemoteService.Get();
    let run = new CyberblockRemoteRun();
    let reason = "Cyberblock is not active";
    if live && IsDefined(service) && IsDefined(GetPlayer(game)) {
      reason = service.Run(game, GetPlayer(game), hack, target, run);
    }
    CyberblockRemoteHacks.Answer(id, s"rhack \(NameToString(hack)) on \(target)", reason, run);
  }

  // The reveals, then the one result, then the log line (shared with Daemons.reds).
  public static func Answer(id: Int32, what: String, reason: String, run: ref<CyberblockRemoteRun>) -> Void {
    let ok = StrLen(reason) == 0;
    if ok {
      for revealed in run.spread {
        Cyberblock_HackSpread(id, revealed, n"ping");
      }
    }
    let kind = ok ? run.kind : n"";
    Cyberblock_HackResult(id, ok, reason, 0.0, kind);
    Cyberblock_Log(s"\(what): \(ok ? s"ok (\(run.note))" : reason)");
  }

  // ClearWorld (Cyberblock.reds): everything held is restored at once.
  public static func Clear(game: GameInstance) -> Void {
    let service = CyberblockRemoteService.Get();
    if IsDefined(service) {
      service.Clear(game);
    }
  }

  // A listed device (the id the devices stream sent, which may be the position+class fallback id), else the entity.
  public static func FindObject(game: GameInstance, id: Uint64) -> ref<GameObject> {
    if id == 0ul {
      return null;
    }
    let devices = CyberblockDeviceService.Get();
    if IsDefined(devices) {
      let device = devices.Find(id);
      if IsDefined(device) {
        return device;
      }
    }
    return CyberblockEffects.Find(game, id);
  }

  // The centre of an area hack: the target, else the event position when the DLL gave one, else V.
  public static func Center(player: ref<PlayerPuppet>, object: ref<GameObject>) -> Vector4 {
    if IsDefined(object) {
      return object.GetWorldPosition();
    }
    let pos = Cyberblock_EventPos();
    return Vector4.IsXYZZero(pos) ? player.GetWorldPosition() : pos;
  }

  // People (and cars) of the NPC registry within radius of center, nearest first, at most max; never V, V's car, a
  // Cyberblock mob stand-in or a dead person. hostileOnly keeps people hostile to V (and no cars).
  public static func Nearby(game: GameInstance, player: ref<PlayerPuppet>, center: Vector4, radius: Float, cars: Bool, hostileOnly: Bool, max: Int32) -> array<ref<GameObject>> {
    let picked: array<ref<GameObject>>;
    let pickedDistance2: array<Float>;
    let tether = CyberblockTetherService.Get();
    if !IsDefined(tether) || max <= 0 {
      return picked;
    }
    let mounted: wref<VehicleObject>;
    VehicleComponent.GetVehicle(game, player, mounted);
    let radius2 = radius * radius;
    for object in tether.Known() {
      if IsDefined(object) && object.IsAttached() && object != player && object != mounted {
        let npc = object as ScriptedPuppet;
        let car = object as VehicleObject;
        let wanted = false;
        if IsDefined(npc) {
          wanted = !npc.IsPlayer() && ScriptedPuppet.IsAlive(npc) && !CyberblockPuppets.IsPuppet(game, npc.GetEntityID())
            && (!hostileOnly || IsHostileTowardsPlayer(npc));
        } else {
          wanted = cars && !hostileOnly && IsDefined(car) && !car.IsDestroyed();
        }
        let distance2 = Vector4.DistanceSquared(object.GetWorldPosition(), center);
        if wanted && distance2 <= radius2 {
          CyberblockRemoteHacks.Insert(picked, pickedDistance2, object, distance2, max);
        }
      }
    }
    return picked;
  }

  // Attached devices of the registry within radius of center, nearest first, at most max.
  public static func NearbyDevices(center: Vector4, radius: Float, max: Int32) -> array<ref<GameObject>> {
    let picked: array<ref<GameObject>>;
    let pickedDistance2: array<Float>;
    let devices = CyberblockDeviceService.Get();
    if !IsDefined(devices) {
      return picked;
    }
    let radius2 = radius * radius;
    for device in devices.Known() {
      if IsDefined(device) && device.IsAttached() {
        let distance2 = Vector4.DistanceSquared(device.GetWorldPosition(), center);
        if distance2 <= radius2 {
          CyberblockRemoteHacks.Insert(picked, pickedDistance2, device, distance2, max);
        }
      }
    }
    return picked;
  }

  // Sorted insert, nearest first, keeping at most max.
  private static func Insert(out picked: array<ref<GameObject>>, out distances: array<Float>, object: ref<GameObject>, distance2: Float, max: Int32) -> Void {
    let at = ArraySize(picked);
    while at > 0 && distances[at - 1] > distance2 {
      at -= 1;
    }
    if at < max {
      ArrayInsert(picked, at, object);
      ArrayInsert(distances, at, distance2);
      if ArraySize(picked) > max {
        ArrayResize(picked, max);
        ArrayResize(distances, max);
      }
    }
  }

  // The nearest TrafficLight device within radius of center, null when none.
  public static func NearestTrafficLight(center: Vector4, radius: Float) -> ref<Device> {
    for object in CyberblockRemoteHacks.NearbyDevices(center, radius, 64) {
      if IsDefined(object as TrafficLight) {
        return object as Device;
      }
    }
    return null;
  }
}

public class CyberblockRemoteService extends ScriptableService {
  // Feature paths (parallel arrays): the feature, its fallback in use, its primary path confirmed in game.
  private let pathNames: array<CName>;
  private let pathFallback: array<Bool>;
  private let pathConfirmed: array<Bool>;
  // Phase 0 (h): false = every streamed target; true = only within 50 m of V (the written fallback).
  private let rangeLimited: Bool;
  private let rangeLogged: Bool;
  // Each hold, lock and switch-off batch gets a serial; a timer only undoes the serial it was set for.
  private let serial: Int32;
  // Devices we switched off (blackout, daemons) and their batch.
  private let offDevices: array<ref<ScriptableDeviceComponentPS>>;
  private let offSerials: array<Int32>;
  private let blackoutSerial: Int32;
  private let cityLights: Bool;
  // Cars whose engine is held off: id, serial, whether the engine comes back on.
  private let heldCars: array<Uint64>;
  private let heldSerials: array<Int32>;
  private let heldRestart: array<Bool>;
  // Cars we locked: id, serial, and the 6 door interaction states before the lock (6 entries per car).
  private let lockedCars: array<Uint64>;
  private let lockedSerials: array<Int32>;
  private let lockedStates: array<Int32>;

  public static func Get() -> ref<CyberblockRemoteService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockRemoteService") as CyberblockRemoteService;
  }

  public func NextSerial() -> Int32 {
    this.serial += 1;
    return this.serial;
  }

  // ---- feature paths (the header's runtime checks) ----

  public static func PathText(what: CName, fallback: Bool) -> String {
    if Equals(what, n"veh_lock") {
      return fallback ? "VehicleComponentPS.SetDoorInteractionState Locked on each door" : "VehicleQuestChangeDoorStateEvent LockAll queued to the car's persistent state (the quest path)";
    }
    if Equals(what, n"veh_engine") {
      return fallback ? "VehicleObject.LockVehicleOnState + TurnVehicleOn + TurnEngineOn" : "VehicleQuestToggleEngineEvent queued to the car's persistent state (the quest path)";
    }
    if Equals(what, n"veh_brake") {
      return fallback ? "VehicleObject.ForceBrakesUntilStoppedOrFor 6 s" : "VehicleObject.ActivateNetrunnerQuickhack ForceBrakes (the Force Brakes quickhack's own call)";
    }
    if Equals(what, n"veh_brake_v") {
      return "ForceBrakesUntilStoppedOrFor 5 s + the immobilized effect on V's car (the NCPD hack loop's calls)";
    }
    if Equals(what, n"veh_jam") {
      return "VehicleObject.ActivateTemporaryLossOfControl + 3 s horn + the immobilized effect (not checkable from scripts)";
    }
    if Equals(what, n"gridlock") {
      return fallback ? "engine off and held 60 s on the cars within 25 m" : "VehicleObject.ForceBrakesFor 60 s on the cars within 25 m, nearest intersection flipped (2.31 TrafficSystem has no lane call)";
    }
    if Equals(what, n"traffic") {
      return "the nearest TrafficLight device's intersection flipped (2.31 TrafficSystem has no light call: the Phase 4 device fallback)";
    }
    if Equals(what, n"blackout") {
      return "the lights and devices within 150 m of V off (no 2.31 script lists a district's devices: the written fallback)";
    }
    if Equals(what, n"citylights") {
      return fallback ? "devices only (CityLightSystem did not take the forced OFF)" : "CityLightSystem forced OFF (ForceCLSStateRequest, not savable)";
    }
    if Equals(what, n"cam_feed") {
      return "the camera's devices-stream position and yaw; Minecraft renders the replica from there (fallback for a true camera view)";
    }
    if Equals(what, n"outline") {
      return "GameObject.SendForceRevealObjectEvent on the people and cars around the point, as the Ping chip";
    }
    if Equals(what, n"ping_area") {
      return "the vanilla Ping upload on the nearest hackable person, then hostiles, cameras and turrets within 30 m revealed";
    }
    if Equals(what, n"device_off") {
      return fallback ? "none: devices stay as they are (Phase 0 c fallback: the action is not callable; blackout keeps the city lights)" : "ScriptableDeviceComponentPS.ExecutePSAction(SetDeviceOFF) with V as executor (the Phase 4 devact path)";
    }
    if Equals(what, n"heat") {
      return fallback ? "Minecraft Heat only (Phase 0 e fallback)" : "PreventionSystem SetWantedLevel +1 (the quest path)";
    }
    return "?";
  }

  // The feature's slot; its first use logs which path is active.
  private func Slot(what: CName) -> Int32 {
    let i = ArrayFindFirst(this.pathNames, what);
    if i < 0 {
      ArrayPush(this.pathNames, what);
      ArrayPush(this.pathFallback, false);
      ArrayPush(this.pathConfirmed, false);
      i = ArraySize(this.pathNames) - 1;
      Cyberblock_Log(s"rhack: \(NameToString(what)) path: \(CyberblockRemoteService.PathText(what, false))");
    }
    return i;
  }

  public func UsesFallback(what: CName) -> Bool {
    return this.pathFallback[this.Slot(what)];
  }

  public func Confirm(what: CName) -> Void {
    let i = this.Slot(what);
    if !this.pathFallback[i] && !this.pathConfirmed[i] {
      this.pathConfirmed[i] = true;
      Cyberblock_Log(s"rhack: \(NameToString(what)) path confirmed in game: \(CyberblockRemoteService.PathText(what, false))");
    }
  }

  public func Fall(what: CName, why: String) -> Void {
    let i = this.Slot(what);
    if !this.pathFallback[i] {
      this.pathFallback[i] = true;
      Cyberblock_Log(s"rhack: \(NameToString(what)) path: \(CyberblockRemoteService.PathText(what, true)) (fallback: \(why))");
    }
  }

  // Phase 0 (h): "" or "out of range" (only once the 50 m fallback is in force).
  public func RangeRefusal(player: ref<PlayerPuppet>, target: ref<GameObject>) -> String {
    if !this.rangeLogged {
      this.rangeLogged = true;
      Cyberblock_Log("rhack: remote range: every streamed target, no distance limit (Phase 0 h, unconfirmed in game; car hacks check it)");
    }
    if this.rangeLimited && IsDefined(target) && Vector4.Distance(player.GetWorldPosition(), target.GetWorldPosition()) > 50.0 {
      return "out of range";
    }
    return "";
  }

  public func RangeLimit() -> Float {
    return this.rangeLimited ? 50.0 : 10000.0;
  }

  private func LimitRange(why: String) -> Void {
    if !this.rangeLimited {
      this.rangeLimited = true;
      this.rangeLogged = true;
      Cyberblock_Log(s"rhack: remote range: 50 m of V (Phase 0 h fallback: \(why))");
    }
  }

  // ---- the hacks ----

  // "" when it ran (run filled in), else the refusal.
  public func Run(game: GameInstance, player: ref<PlayerPuppet>, hack: CName, target: Uint64, run: ref<CyberblockRemoteRun>) -> String {
    let tier = PlayerPuppet.GetSceneTier(player);
    if tier >= 3 && tier <= 5 {
      return "not hackable (V is in a scene)";
    }
    let object = CyberblockRemoteHacks.FindObject(game, target);
    if target != 0ul && (!IsDefined(object) || !object.IsAttached()) {
      return "target gone";
    }
    if IsDefined(object) {
      let far = this.RangeRefusal(player, object);
      if StrLen(far) > 0 {
        return far;
      }
    }
    let name = NameToString(hack);
    if StrBeginsWith(name, "veh_") {
      return this.Vehicle(game, player, hack, object, run);
    }
    if Equals(hack, n"traffic_switch") {
      return this.TrafficSwitch(player, object, run);
    }
    if Equals(hack, n"gridlock") {
      return this.Gridlock(game, player, object, run);
    }
    if Equals(hack, n"blackout") {
      return this.Blackout(game, player, run);
    }
    if Equals(hack, n"cam_feed") {
      return this.CamFeed(object, run);
    }
    if Equals(hack, n"outline") {
      return this.Outline(game, player, object, run);
    }
    if Equals(hack, n"ping_area") {
      return this.PingArea(game, player, object, run);
    }
    return "no such hack";
  }

  private func Vehicle(game: GameInstance, player: ref<PlayerPuppet>, hack: CName, object: ref<GameObject>, run: ref<CyberblockRemoteRun>) -> String {
    if !IsDefined(object) {
      return "target gone";
    }
    let vehicle = object as VehicleObject;
    if !IsDefined(vehicle) {
      let mounted: wref<VehicleObject>;
      if VehicleComponent.GetVehicle(game, object, mounted) {
        vehicle = mounted;
      }
    }
    if !IsDefined(vehicle) {
      return "not hackable (not a car)";
    }
    if vehicle.IsDestroyed() {
      return "not hackable (destroyed)";
    }
    if IsDefined(vehicle as AVObject) {
      return "not hackable (AV)";
    }
    if vehicle.IsPerformingSceneAnimation() {
      return "not hackable (in a scene)";
    }
    let mine = vehicle.IsPlayerMounted();
    let far = Vector4.Distance(player.GetWorldPosition(), vehicle.GetWorldPosition()) > 50.0;
    let speed = Vector4.Length(vehicle.GetLinearVelocity());
    let id = CyberblockIds.Hash(vehicle);
    run.note = s"\(mine ? "V's car" : "car") \(id)";
    if Equals(hack, n"veh_lock") {
      return this.LockCar(game, vehicle, mine ? 15.0 : 120.0, far, run);
    }
    if Equals(hack, n"veh_engine_off") {
      if mine || speed > 1.0 {
        vehicle.ForceBrakesUntilStoppedOrFor(8.0);
      }
      let fallback = this.UsesFallback(n"veh_engine");
      let running = vehicle.IsEngineTurnedOn();
      this.Hold(game, vehicle, mine ? 10.0 : 30.0, mine || running);
      CyberblockRemoteService.Engine(game, vehicle, false, fallback);
      if running {
        this.Check(game, n"veh_engine_off", id, fallback ? 2 : 1, 1.5, speed, far);
      } else {
        run.note += ", engine was off (not checked)";
      }
      return "";
    }
    if Equals(hack, n"veh_start") {
      this.Release(game, id, 0, false);
      if vehicle.IsEngineTurnedOn() && !vehicle.IsVehicleOnStateLocked() {
        run.note += ", already running";
        return "";
      }
      let fallback = this.UsesFallback(n"veh_engine");
      CyberblockRemoteService.Engine(game, vehicle, true, fallback);
      this.Check(game, n"veh_start", id, fallback ? 2 : 1, 1.5, speed, far);
      return "";
    }
    if Equals(hack, n"veh_brake") {
      if mine {
        this.Slot(n"veh_brake_v");
        vehicle.ForceBrakesUntilStoppedOrFor(5.0);
        GameObjectEffectHelper.StartEffectEvent(vehicle, n"immobilized");
        this.Schedule(game, n"immobilized", id, 0, 5.0);
        return "";
      }
      let fallback = this.UsesFallback(n"veh_brake");
      if fallback {
        vehicle.ForceBrakesUntilStoppedOrFor(6.0);
      } else {
        vehicle.ActivateNetrunnerQuickhack(VehicleNetrunnerQuickhackType.ForceBrakes);
      }
      if speed > 3.0 {
        this.Check(game, n"veh_brake", id, fallback ? 2 : 1, 1.5, speed, far);
      } else {
        run.note += ", standing (not checked)";
      }
      return "";
    }
    if Equals(hack, n"veh_jam") {
      this.Slot(n"veh_jam");
      vehicle.ActivateTemporaryLossOfControl();
      vehicle.ToggleHorn(true);
      GameObjectEffectHelper.StartEffectEvent(vehicle, n"immobilized");
      this.Schedule(game, n"horn", id, 0, 3.0);
      this.Schedule(game, n"immobilized", id, 0, 3.0);
      return "";
    }
    return "no such hack";
  }

  // Vehicle and engine on or off, through the quest event or (fallback) the calls its handler makes; off holds the
  // on-state lock so the driver cannot restart it, on releases it.
  private static func Engine(game: GameInstance, vehicle: ref<VehicleObject>, on: Bool, fallback: Bool) -> Void {
    if fallback {
      vehicle.LockVehicleOnState(false);
      vehicle.TurnVehicleOn(on);
      vehicle.TurnEngineOn(on);
      if !on {
        vehicle.LockVehicleOnState(true);
      }
      return;
    }
    let ps = vehicle.GetVehiclePS();
    if !IsDefined(ps) {
      return;
    }
    let evt = new VehicleQuestToggleEngineEvent();
    evt.toggle = on;
    evt.lockState = on ? VehicleQuestEngineLockState.Unlock : VehicleQuestEngineLockState.Lock;
    GameInstance.GetPersistencySystem(game).QueuePSEvent(ps.GetID(), ps.GetClassName(), evt);
  }

  // Holds the car's engine off for seconds (restart: switch it back on afterwards).
  private func Hold(game: GameInstance, vehicle: ref<VehicleObject>, seconds: Float, restart: Bool) -> Void {
    let id = CyberblockIds.Hash(vehicle);
    let serial = this.NextSerial();
    let i = ArrayFindFirst(this.heldCars, id);
    if i >= 0 {
      this.heldSerials[i] = serial; // the engine state before the first hold stays the one restored
    } else {
      ArrayPush(this.heldCars, id);
      ArrayPush(this.heldSerials, serial);
      ArrayPush(this.heldRestart, restart);
    }
    this.Schedule(game, n"engine", id, serial, seconds);
  }

  // Ends a hold (serial 0: whichever); restore: switch the engine back on when it ran before.
  private func Release(game: GameInstance, id: Uint64, serial: Int32, restore: Bool) -> Void {
    let i = ArrayFindFirst(this.heldCars, id);
    if i < 0 || serial != 0 && this.heldSerials[i] != serial {
      return;
    }
    let restart = this.heldRestart[i];
    ArrayErase(this.heldCars, i);
    ArrayErase(this.heldSerials, i);
    ArrayErase(this.heldRestart, i);
    let vehicle = CyberblockEffects.Find(game, id) as VehicleObject;
    if !IsDefined(vehicle) {
      return;
    }
    vehicle.LockVehicleOnState(false);
    if restore && restart {
      vehicle.TurnVehicleOn(true);
      vehicle.TurnEngineOn(true);
    }
    Cyberblock_Log(s"rhack: engine hold on car \(id) released\(restore && restart ? ", engine back on" : "")");
  }

  // Locks the car's doors for seconds; the states before come back afterwards.
  private func LockCar(game: GameInstance, vehicle: ref<VehicleObject>, seconds: Float, far: Bool, run: ref<CyberblockRemoteRun>) -> String {
    let ps = vehicle.GetVehiclePS();
    if !IsDefined(ps) {
      return "not ready";
    }
    let id = CyberblockIds.Hash(vehicle);
    let serial = this.NextSerial();
    let i = ArrayFindFirst(this.lockedCars, id);
    if i >= 0 {
      this.lockedSerials[i] = serial;
      this.Schedule(game, n"lock", id, serial, seconds);
      run.note += ", lock renewed";
      return "";
    }
    let front = ps.GetDoorInteractionState(EVehicleDoor.seat_front_left);
    if Equals(front, VehicleDoorInteractionState.Locked) || Equals(front, VehicleDoorInteractionState.QuestLocked) {
      run.note += ", already locked";
      return "";
    }
    ArrayPush(this.lockedCars, id);
    ArrayPush(this.lockedSerials, serial);
    let d = 0;
    while d < 6 {
      ArrayPush(this.lockedStates, EnumInt(ps.GetDoorInteractionState(IntEnum<EVehicleDoor>(d))));
      d += 1;
    }
    this.Schedule(game, n"lock", id, serial, seconds);
    let fallback = this.UsesFallback(n"veh_lock");
    CyberblockRemoteService.Lock(game, vehicle, fallback);
    this.Check(game, n"veh_lock", id, fallback ? 2 : 1, 1.0, 0.0, far);
    return "";
  }

  private static func Lock(game: GameInstance, vehicle: ref<VehicleObject>, fallback: Bool) -> Void {
    let ps = vehicle.GetVehiclePS();
    if !IsDefined(ps) {
      return;
    }
    if fallback {
      let d = 0;
      while d < 6 {
        ps.SetDoorInteractionState(IntEnum<EVehicleDoor>(d), VehicleDoorInteractionState.Locked, "Cyberblock");
        d += 1;
      }
      return;
    }
    let evt = new VehicleQuestChangeDoorStateEvent();
    evt.newState = EQuestVehicleDoorState.LockAll;
    GameInstance.GetPersistencySystem(game).QueuePSEvent(ps.GetID(), ps.GetClassName(), evt);
  }

  // Puts the doors back as they were before the lock (serial 0: whichever).
  private func Unlock(game: GameInstance, id: Uint64, serial: Int32) -> Void {
    let i = ArrayFindFirst(this.lockedCars, id);
    if i < 0 || serial != 0 && this.lockedSerials[i] != serial {
      return;
    }
    let vehicle = CyberblockEffects.Find(game, id) as VehicleObject;
    let ps: ref<VehicleComponentPS>;
    if IsDefined(vehicle) {
      ps = vehicle.GetVehiclePS();
    }
    let d = 0;
    while d < 6 {
      if IsDefined(ps) {
        ps.SetDoorInteractionState(IntEnum<EVehicleDoor>(d), IntEnum<VehicleDoorInteractionState>(this.lockedStates[i * 6]), "Cyberblock");
      }
      ArrayErase(this.lockedStates, i * 6);
      d += 1;
    }
    ArrayErase(this.lockedCars, i);
    ArrayErase(this.lockedSerials, i);
    Cyberblock_Log(s"rhack: doors of car \(id) \(IsDefined(ps) ? "restored" : "left (car gone)")");
  }

  private func TrafficSwitch(player: ref<PlayerPuppet>, object: ref<GameObject>, run: ref<CyberblockRemoteRun>) -> String {
    this.Slot(n"traffic");
    run.kind = n"traffic";
    let light = object as TrafficLight;
    if !IsDefined(light) {
      light = CyberblockRemoteHacks.NearestTrafficLight(CyberblockRemoteHacks.Center(player, object), 40.0) as TrafficLight;
    }
    if !IsDefined(light) {
      return "no traffic light near";
    }
    if !CyberblockHacks.FlipIntersection(light) {
      return "not hackable (no intersection)";
    }
    run.note = s"intersection of light \(CyberblockIds.Hash(light)) flipped";
    return "";
  }

  private func Gridlock(game: GameInstance, player: ref<PlayerPuppet>, object: ref<GameObject>, run: ref<CyberblockRemoteRun>) -> String {
    run.kind = n"traffic";
    let center = CyberblockRemoteHacks.Center(player, object);
    let fallback = this.UsesFallback(n"gridlock");
    let limit = this.RangeLimit();
    let stopped = 0;
    let checked = false;
    for found in CyberblockRemoteHacks.Nearby(game, player, center, 25.0, true, false, 64) {
      let car = found as VehicleObject;
      if stopped < 12 && IsDefined(car) && !IsDefined(car as AVObject) && !car.IsPerformingSceneAnimation()
        && Vector4.Distance(player.GetWorldPosition(), car.GetWorldPosition()) <= limit {
        let speed = Vector4.Length(car.GetLinearVelocity());
        if fallback {
          this.Hold(game, car, 60.0, car.IsEngineTurnedOn());
          CyberblockRemoteService.Engine(game, car, false, true);
        } else {
          car.ForceBrakesFor(60.0);
        }
        if !checked && speed > 3.0 {
          checked = true;
          let far = Vector4.Distance(player.GetWorldPosition(), car.GetWorldPosition()) > 50.0;
          this.Check(game, n"gridlock", CyberblockIds.Hash(car), fallback ? 2 : 1, 3.0, speed, far);
        }
        stopped += 1;
      }
    }
    let light = CyberblockRemoteHacks.NearestTrafficLight(center, 40.0);
    let flipped = IsDefined(light) && CyberblockHacks.FlipIntersection(light);
    if stopped == 0 && !flipped {
      return "no traffic near";
    }
    run.note = s"\(stopped) cars stopped for 60 s\(flipped ? ", intersection flipped" : "")";
    return "";
  }

  private func Blackout(game: GameInstance, player: ref<PlayerPuppet>, run: ref<CyberblockRemoteRun>) -> String {
    this.Slot(n"blackout");
    run.kind = n"device";
    if this.blackoutSerial != 0 {
      this.EndBlackout(game, this.blackoutSerial, "a new blackout");
    }
    let minutes = Cyberblock_EventAmount() > 0.0 ? ClampF(Cyberblock_EventAmount(), 0.5, 10.0) : 2.0;
    let serial = this.NextSerial();
    this.blackoutSerial = serial;
    let off = 0;
    for object in CyberblockRemoteHacks.NearbyDevices(player.GetWorldPosition(), MinF(150.0, this.RangeLimit()), 160) {
      let device = object as Device;
      if IsDefined(device) && CyberblockRemoteService.Blackoutable(device) && this.SwitchOff(device, player, serial) {
        off += 1;
      }
    }
    let request = new ForceCLSStateRequest();
    request.state = ECLSForcedState.ForcedOFF;
    request.sourceName = n"CyberblockBlackout";
    request.priority = EPriority.High;
    request.removePreviousRequests = true;
    request.savable = false;
    if !this.UsesFallback(n"citylights") {
      GameInstance.QueueScriptableSystemRequest(game, n"CityLightSystem", request);
      this.cityLights = true;
      this.Check(game, n"citylights", 0ul, 1, 1.0, 0.0, false);
    }
    this.Schedule(game, n"off", 0ul, serial, minutes * 60.0);
    if off > 0 {
      this.Check(game, n"device_off", 0ul, 1, 1.0, Cast<Float>(serial), false);
    }
    let district = Cyberblock_EventText();
    run.note = s"\(off) devices off for \(minutes) min within 150 m of V\(StrLen(district) > 0 ? s" (district \(district) asked)" : "")";
    return "";
  }

  // Lights, signs, screens, cameras, turrets, vending machines and traffic lights; never doors, gates, terminals or lifts.
  private static func Blackoutable(device: ref<Device>) -> Bool {
    let kind = CyberblockDeviceService.Kind(device);
    return Equals(kind, n"light") || Equals(kind, n"screen") || Equals(kind, n"camera") || Equals(kind, n"turret")
      || Equals(kind, n"vending") || Equals(kind, n"traffic");
  }

  // Switches a powered device that is ON off, remembered under serial for its restore. False when left alone.
  public func SwitchOff(device: ref<Device>, player: ref<PlayerPuppet>, serial: Int32) -> Bool {
    let ps = device.GetDevicePS();
    if !IsDefined(ps) || !Equals(device.GetDeviceState(), EDeviceStatus.ON) || ps.IsMarkedAsQuest()
      || CyberblockDeviceService.QuestLocked(device) || ArrayContains(this.offDevices, ps) {
      return false;
    }
    let action = ps.ActionSetDeviceOFF();
    if !IsDefined(action) {
      return false;
    }
    action.SetExecutor(player);
    ps.ExecutePSAction(action);
    ArrayPush(this.offDevices, ps);
    ArrayPush(this.offSerials, serial);
    return true;
  }

  // Switches back on the devices of batch serial (0: all) that are still off; the count.
  private func RestoreOff(game: GameInstance, serial: Int32) -> Int32 {
    let player = GetPlayer(game);
    let n = 0;
    let i = ArraySize(this.offDevices) - 1;
    while i >= 0 {
      if serial == 0 || this.offSerials[i] == serial {
        let ps = this.offDevices[i];
        if IsDefined(ps) && ps.IsOFF() {
          let action = ps.ActionSetDeviceON();
          if IsDefined(action) {
            if IsDefined(player) {
              action.SetExecutor(player);
            }
            ps.ExecutePSAction(action);
            n += 1;
          }
        }
        ArrayErase(this.offDevices, i);
        ArrayErase(this.offSerials, i);
      }
      i -= 1;
    }
    return n;
  }

  private func EndBlackout(game: GameInstance, serial: Int32, why: String) -> Void {
    if serial == 0 || serial != this.blackoutSerial {
      return;
    }
    this.blackoutSerial = 0;
    let n = this.RestoreOff(game, serial);
    if this.cityLights {
      this.cityLights = false;
      let request = new ForceCLSStateRequest();
      request.state = ECLSForcedState.DEFAULT;
      request.sourceName = n"CyberblockBlackout";
      request.savable = false;
      GameInstance.QueueScriptableSystemRequest(game, n"CityLightSystem", request);
    }
    Cyberblock_Log(s"rhack: blackout over (\(why)): \(n) devices back on, city lights back to their own state");
  }

  private func CamFeed(object: ref<GameObject>, run: ref<CyberblockRemoteRun>) -> String {
    let camera = object as SurveillanceCamera;
    if !IsDefined(camera) {
      return "not hackable (not a camera)";
    }
    let ps = camera.GetDevicePS();
    if !IsDefined(ps) || !ps.IsON() {
      return "camera off";
    }
    if ps.IsBroken() {
      return "not hackable (broken)";
    }
    this.Slot(n"cam_feed");
    run.kind = n"camera";
    let pos = camera.GetWorldPosition();
    let forward = camera.GetWorldForward();
    run.note = s"camera \(CyberblockIds.Hash(camera)) at \(pos.X),\(pos.Y),\(pos.Z) yaw \(Rad2Deg(AtanF(-forward.X, forward.Y)))";
    return "";
  }

  private func Outline(game: GameInstance, player: ref<PlayerPuppet>, object: ref<GameObject>, run: ref<CyberblockRemoteRun>) -> String {
    this.Slot(n"outline");
    let instigator = player.GetEntityID();
    let center = CyberblockRemoteHacks.Center(player, object);
    let shown = CyberblockRemoteHacks.Nearby(game, player, center, 25.0, true, false, 16);
    for found in shown {
      GameObject.SendForceRevealObjectEvent(found, true, n"CyberblockOutline", instigator, 15.0);
      run.Reveal(found);
    }
    run.note = s"\(ArraySize(shown)) people and cars outlined 15 s";
    return "";
  }

  private func PingArea(game: GameInstance, player: ref<PlayerPuppet>, object: ref<GameObject>, run: ref<CyberblockRemoteRun>) -> String {
    this.Slot(n"ping_area");
    let instigator = player.GetEntityID();
    let center = CyberblockRemoteHacks.Center(player, object);
    // The vanilla Ping (its own network reveal) on the nearest person who may be hacked.
    let uploaded = false;
    for found in CyberblockRemoteHacks.Nearby(game, player, center, 30.0, false, false, 8) {
      let npc = found as ScriptedPuppet;
      if !uploaded && IsDefined(npc) && ScriptedPuppet.IsActive(npc) && StrLen(CyberblockHacks.Refusal(game, npc)) == 0 {
        CyberblockHacks.Upload(player, npc, t"QuickHack.PingLvl2Hack");
        uploaded = true;
      }
    }
    let people = CyberblockRemoteHacks.Nearby(game, player, center, 30.0, false, true, 16);
    for found in people {
      GameObject.SendForceRevealObjectEvent(found, true, n"CyberblockPing", instigator, 10.0);
      run.Reveal(found);
    }
    let devices = 0;
    for found in CyberblockRemoteHacks.NearbyDevices(center, 30.0, 32) {
      if IsDefined(found as SurveillanceCamera) || IsDefined(found as SecurityTurret) {
        GameObject.SendForceRevealObjectEvent(found, true, n"CyberblockPing", instigator, 10.0);
        devices += 1;
      }
    }
    run.note = s"\(ArraySize(people)) hostiles and \(devices) cameras or turrets revealed 10 s\(uploaded ? ", vanilla ping uploaded" : "")";
    return "";
  }

  // ---- timers and checks ----

  private func Schedule(game: GameInstance, what: CName, target: Uint64, serial: Int32, seconds: Float) -> Void {
    let timer = new CyberblockRemoteTimer();
    timer.game = game;
    timer.what = what;
    timer.target = target;
    timer.serial = serial;
    GameInstance.GetDelaySystem(game).DelayCallback(timer, seconds);
  }

  // Daemons.reds: the switch-off batch serial comes back on after seconds.
  public func ScheduleOff(game: GameInstance, serial: Int32, seconds: Float) -> Void {
    this.Schedule(game, n"off", 0ul, serial, seconds);
    this.Check(game, n"device_off", 0ul, 1, 1.0, Cast<Float>(serial), false);
  }

  public func OnTimer(game: GameInstance, what: CName, target: Uint64, serial: Int32) -> Void {
    if Equals(what, n"engine") {
      this.Release(game, target, serial, true);
    } else if Equals(what, n"lock") {
      this.Unlock(game, target, serial);
    } else if Equals(what, n"off") {
      if serial == this.blackoutSerial {
        this.EndBlackout(game, serial, "time");
      } else {
        let n = this.RestoreOff(game, serial);
        if n > 0 {
          Cyberblock_Log(s"rhack: \(n) devices switched off by a daemon back on");
        }
      }
    } else {
      let vehicle = CyberblockEffects.Find(game, target) as VehicleObject;
      if IsDefined(vehicle) {
        if Equals(what, n"horn") {
          vehicle.ToggleHorn(false);
        } else if Equals(what, n"immobilized") {
          GameObjectEffectHelper.StopEffectEvent(vehicle, n"immobilized");
        }
      }
    }
  }

  // A check of what (stage 1: the primary path ran; 2: the fallback ran) after seconds.
  public func Check(game: GameInstance, what: CName, target: Uint64, stage: Int32, seconds: Float, speed: Float, far: Bool) -> Void {
    let check = new CyberblockRemoteCheck();
    check.game = game;
    check.what = what;
    check.target = target;
    check.stage = stage;
    check.speed = speed;
    check.far = far;
    GameInstance.GetDelaySystem(game).DelayCallback(check, seconds);
  }

  public func Verify(check: ref<CyberblockRemoteCheck>) -> Void {
    let game = check.game;
    let what = check.what;
    if Equals(what, n"citylights") {
      let cls = GameInstance.GetScriptableSystemsContainer(game).Get(n"CityLightSystem") as CityLightSystem;
      if IsDefined(cls) && Equals(cls.GetState(), ECLSForcedState.ForcedOFF) {
        this.Confirm(what);
      } else if this.cityLights {
        this.Fall(what, s"state \(IsDefined(cls) ? EnumInt(cls.GetState()) : -1) after the request");
      }
      return;
    }
    if Equals(what, n"device_off") {
      let serial = RoundF(check.speed);
      let asked = 0;
      let off = 0;
      let i = 0;
      while i < ArraySize(this.offSerials) {
        if this.offSerials[i] == serial {
          asked += 1;
          if IsDefined(this.offDevices[i]) && this.offDevices[i].IsOFF() {
            off += 1;
          }
        }
        i += 1;
      }
      if off > 0 {
        this.Confirm(what);
      } else if asked > 0 {
        this.Fall(what, s"none of \(asked) devices was off 1 s later");
      }
      return;
    }
    if Equals(what, n"heat") {
      let prevention = GameInstance.GetScriptableSystemsContainer(game).Get(n"PreventionSystem") as PreventionSystem;
      let now = IsDefined(prevention) ? Cast<Int32>(prevention.GetHeatStageAsInt()) : -1;
      if now >= RoundF(check.speed) {
        this.Confirm(what);
      } else {
        this.Fall(what, s"wanted level \(now) after asking for \(RoundF(check.speed))");
      }
      return;
    }
    let vehicle = CyberblockEffects.Find(game, check.target) as VehicleObject;
    if !IsDefined(vehicle) || !vehicle.IsAttached() {
      Cyberblock_Log(s"rhack: \(NameToString(what)) check on car \(check.target): car gone, not judged");
      return;
    }
    let feature = Equals(what, n"veh_engine_off") || Equals(what, n"veh_start") ? n"veh_engine" : what;
    if CyberblockRemoteService.TookEffect(what, vehicle, check.speed) {
      if check.stage == 1 {
        this.Confirm(feature);
      }
      return;
    }
    if check.stage == 1 {
      this.Fall(feature, s"no effect on car \(check.target) after the primary call");
      this.ApplyFallback(game, what, vehicle);
      this.Check(game, what, check.target, 2, Equals(what, n"gridlock") ? 3.0 : 1.5, check.speed, check.far);
      return;
    }
    if check.far {
      this.LimitRange(s"\(NameToString(what)) on car \(check.target) more than 50 m from V took no effect by either path");
    } else {
      Cyberblock_Log(s"rhack: \(NameToString(what)) on car \(check.target) took no effect by either path (within 50 m of V)");
    }
  }

  private static func TookEffect(what: CName, vehicle: ref<VehicleObject>, speed: Float) -> Bool {
    if Equals(what, n"veh_lock") {
      let ps = vehicle.GetVehiclePS();
      return IsDefined(ps) && Equals(ps.GetDoorInteractionState(EVehicleDoor.seat_front_left), VehicleDoorInteractionState.Locked);
    }
    if Equals(what, n"veh_engine_off") {
      return !vehicle.IsEngineTurnedOn();
    }
    if Equals(what, n"veh_start") {
      return vehicle.IsEngineTurnedOn();
    }
    // veh_brake, gridlock: slower than 60 % of the start, or under 2 m/s
    let now = Vector4.Length(vehicle.GetLinearVelocity());
    return now < MaxF(2.0, 0.6 * speed);
  }

  // The fallback path on the car a primary path did nothing to.
  private func ApplyFallback(game: GameInstance, what: CName, vehicle: ref<VehicleObject>) -> Void {
    if Equals(what, n"veh_lock") {
      CyberblockRemoteService.Lock(game, vehicle, true);
    } else if Equals(what, n"veh_engine_off") {
      CyberblockRemoteService.Engine(game, vehicle, false, true);
    } else if Equals(what, n"veh_start") {
      CyberblockRemoteService.Engine(game, vehicle, true, true);
    } else if Equals(what, n"veh_brake") {
      vehicle.ForceBrakesUntilStoppedOrFor(6.0);
    } else if Equals(what, n"gridlock") {
      this.Hold(game, vehicle, 60.0, true);
      CyberblockRemoteService.Engine(game, vehicle, false, true);
    }
  }

  // ---- heat (Daemons.reds' failure alarm; Phase 0 e) ----

  // Raises V's wanted level by one star; "" or why not.
  public func RaiseHeat(game: GameInstance) -> String {
    if this.UsesFallback(n"heat") {
      return "Minecraft Heat only";
    }
    let prevention = GameInstance.GetScriptableSystemsContainer(game).Get(n"PreventionSystem") as PreventionSystem;
    if !IsDefined(prevention) {
      this.Fall(n"heat", "no PreventionSystem");
      return "no PreventionSystem";
    }
    let before = Cast<Int32>(prevention.GetHeatStageAsInt());
    if before >= 5 {
      return "already 5 stars";
    }
    let request = new SetWantedLevel();
    request.m_wantedLevel = IntEnum<EPreventionHeatStage>(before + 1);
    PreventionSystem.QueueRequest(game, request);
    this.Check(game, n"heat", 0ul, 1, 2.0, Cast<Float>(before + 1), false);
    return "";
  }

  // ---- Clear ----

  // Everything held goes back now (ClearWorld: before a save, on leaving live, on load and session end).
  public func Clear(game: GameInstance) -> Void {
    if ArraySize(this.heldCars) == 0 && ArraySize(this.lockedCars) == 0 && ArraySize(this.offDevices) == 0 && !this.cityLights {
      return;
    }
    while ArraySize(this.heldCars) > 0 {
      this.Release(game, this.heldCars[0], 0, true);
    }
    while ArraySize(this.lockedCars) > 0 {
      this.Unlock(game, this.lockedCars[0], 0);
    }
    if this.blackoutSerial != 0 {
      this.EndBlackout(game, this.blackoutSerial, "cleared");
    }
    let n = this.RestoreOff(game, 0);
    if this.cityLights {
      this.cityLights = false;
      let request = new ForceCLSStateRequest();
      request.state = ECLSForcedState.DEFAULT;
      request.sourceName = n"CyberblockBlackout";
      request.savable = false;
      GameInstance.QueueScriptableSystemRequest(game, n"CityLightSystem", request);
    }
    Cyberblock_Log(s"rhack: cleared (engines, locks and switched-off devices restored; \(n) more devices back on)");
  }
}

// A timed restore: n"engine" | n"lock" (target, serial) | n"off" (serial) | n"horn" | n"immobilized" (target).
public class CyberblockRemoteTimer extends DelayCallback {
  public let game: GameInstance;
  public let what: CName;
  public let target: Uint64;
  public let serial: Int32;

  public func Call() -> Void {
    let service = CyberblockRemoteService.Get();
    if IsDefined(service) {
      service.OnTimer(this.game, this.what, this.target, this.serial);
    }
  }
}

// A runtime check of a feature path (the header): speed is the car's speed at the start (for heat: the stars asked for);
// far: the car was more than 50 m from V.
public class CyberblockRemoteCheck extends DelayCallback {
  public let game: GameInstance;
  public let what: CName;
  public let target: Uint64;
  public let stage: Int32;
  public let speed: Float;
  public let far: Bool;

  public func Call() -> Void {
    let service = CyberblockRemoteService.Get();
    if IsDefined(service) {
      service.Verify(this);
    }
  }
}
