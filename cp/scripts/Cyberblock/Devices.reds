// Night City's devices for Minecraft (megaplan 2026-10-04 task 4-S1; section 1.8 amendment items 5 and 6, section 1.9
// amendment items 4-6, section 1.11 and its amendment note; Phase 0 check (c), docs/notes/phase0-feasibility.md).
//
// Stream: every 0.5 s while live (Active or Vehicle, not saving) the devices within 48 m of V, at most 64, nearest first,
// go to Cyberblock_SubmitDevices; the DLL (package 4D) converts, diffs and sends `devices` (deltas plus a full list every
// 5 s). Leaving live sends one empty list. Per device:
//   id     CyberblockIds.Hash(device) (Ids.reds). The CET check C5 (the hash survives a reload) was not run, so the
//          fallback key is always at hand: the Codeware string hash (TDBID.ToNumber(TDBID.Create(s)), as
//          Codeware's LocalizationEntry.Hash) of "<class>@<x>,<y>,<z>" with the position rounded to 25 cm. It is used
//          when the entity hash is 0 or collides with another device of the same list. Once per live spell:
//          'devices: ids from entity hash|position+class fallback (...)'.
//   class  the RTTI class name (IScriptable.GetClassName), at most 48 characters.
//   kind   from the IsA chain, mirroring the device_map kinds of section 1.11 (Kind below); other classes are left out.
//   pos, yaw   world position (Cyberpunk space) and the yaw convention of Npcs.reds (degrees from +Y about +Z).
//   size   always zero (Minecraft takes the size from device_map; no 2.31 bounding-box API, Phase 0 c).
//   state  EDeviceStatus (DISABLED -2, UNPOWERED -1, OFF 0, ON 1), or EDoorStatus (SEALED -2, LOCKED -1, CLOSED 0,
//          OPENED 1) for doors. A device whose status is INVALID (logic not ready) is left out until it has one.
//   flags  1 locked (door), 2 secure (IsDeviceSecured), 4 quest-locked (door SEALED or device DISABLED), 8 broken,
//          16 powered (not UNPOWERED). Traffic lights add their colour (an @addMethod(TrafficLight) getter of the
//          protected m_lightState) as bits 32 and 64: 0 green, 32 red, 64 yellow, 96 invalid (contract deviation: 1.9
//          has no colour field, see the 4-S1 report).
//
// Which devices exist: no 2.31 script query lists them, so a registry is kept from Codeware's Entity/AfterAttach and
// Entity/Detach callbacks filtered with EntityTarget.Type(n"Device") (it matches subclasses), the Tether.reds pattern:
// owned by a Codeware ScriptableService, CallbackLifetime.Forever, re-registered whenever a handler is gone.
//
// Changes: @wrapMethod(Device) OnPerformedAction and OnPSChangedEvent mark a listed device changed (InteractiveDevice's
// override calls super, so its devices arrive here too); every 100 ms tick sends each changed state with
// Cyberblock_DeviceState. `by` comes from the performed action's executor: V -> host, an NPC (ScriptedPuppet) -> npc,
// none or anything else -> script (the DLL turns host into guest for a guest's devact). The CET checks C1-C5 were not
// run, so whether these events arrive is checked at run time: while no change event has ever come from a listed device
// and devices have been listed for 60 s, the 0.5 s scan also compares each device with its last state and sends the
// differences (by script): the 2 Hz poll fallback of Phase 0 (c). The first event switches to events for good. Each
// switch logs 'devices: changes via events|2 Hz poll (...)' (two lines at most).
//
// Actions: Minecraft's n"devact" events (EventId = request id, EventTarget = device id, EventName = act) run through the
// device's own persistent-state actions with V as executor (DeviceAction.SetExecutor + ScriptableDeviceComponentPS.
// ExecutePSAction, the vanilla path): doors ActionSetOpened / ActionSetClosed / ActionToggleOpen / ActionToggleLock;
// other devices ActionSetDeviceON / ActionSetDeviceOFF / ActionToggleON (on, off, enable, disable, toggle), the
// quickhack distraction (through an @addMethod, the action builder is protected) and ActionSetDeviceAttitude
// (friendly). Never on a quest-locked device; a locked door does not open. call, buy, breach and anything else answer
// Cyberblock_DeviceResult(id, false, "unsupported") (section 1.8 amendment item 6). Every request is answered; while not
// live with "Cyberblock is not active". Each one is logged: 'devices: devact <req> <act> <class> <id>: ok|refused (<why>)'.
//
// Wiring: CyberblockSystem.Tick calls Update every 100 ms, and Events.reds hands every n"devact" to OnEvent whatever the
// state (redscript cannot @wrapMethod a class of the same mod: UNRESOLVED_METHOD, so these are two plain calls).
//
// 2.31 script API (NativeDB, redscript), compile-checked: Device.GetDevicePS, Device.GetDeviceState,
// Device.IsDeviceSecured (deviceBase.swift 803, 860, 864), Device.OnPerformedAction / OnPSChangedEvent (1037, 787),
// PerformedAction.m_action, BaseScriptableAction.GetExecutor / SetExecutor (baseDeviceActions.swift 58, 62),
// ScriptableDeviceComponentPS.ExecutePSAction / IsBroken / ActionSetDeviceON / ActionSetDeviceOFF / ActionToggleON /
// ActionSetDeviceAttitude / ActionQuickHackDistraction (scriptableDeviceBasePS.swift 5427, 5770, 3833, 3853, 3800, 5087,
// 2524), DoorControllerPS.GetDoorState / IsLocked / IsSealed / ActionSetOpened / ActionSetClosed / ActionToggleOpen /
// ActionToggleLock (doorController.swift 198, 110, 118, 745, 765, 675, 831), TrafficLight.m_lightState
// (trafficLight.swift 4), TDBID.Create / TDBID.ToNumber. Codeware 1.20.3: CallbackSystem.RegisterCallback,
// CallbackSystemHandler.AddTarget / SetLifetime / IsRegistered, EntityTarget.Type, EntityLifecycleEvent.GetEntity.

// Declared here (rule 0.14, section 1.8), implemented by Cyberblock.dll (devices.cpp, natives.cpp; package 4D).
public native func Cyberblock_SubmitDevices(ids: array<Uint64>, classes: array<CName>, kinds: array<CName>, pos: array<Vector4>, yaws: array<Float>, sizes: array<Vector4>, states: array<Int32>, flags: array<Int32>) -> Void;
public native func Cyberblock_DeviceState(id: Uint64, state: Int32, flags: Int32, by: CName) -> Void;
public native func Cyberblock_DeviceResult(id: Int32, ok: Bool, reason: String) -> Void;

public class CyberblockDeviceService extends ScriptableService {
  // Devices attached to the world (the registry).
  private let known: array<wref<Device>>;
  private let attachHandler: ref<CallbackSystemHandler>;
  private let detachHandler: ref<CallbackSystemHandler>;
  // The last list sent (parallel arrays, at most 64): the device, its id, the state and flags Minecraft last got, and
  // whether a change event arrived since the last flush (with the executor's `by`).
  private let objects: array<wref<Device>>;
  private let ids: array<Uint64>;
  private let classes: array<CName>;
  private let states: array<Int32>;
  private let flags: array<Int32>;
  private let dirty: array<Bool>;
  private let dirtyBy: array<CName>;
  private let live: Bool;
  private let sent: Bool;
  private let ticks: Int32;
  // Change detection: polling once no change event came for 60 s (120 scans) with devices listed.
  private let polling: Bool;
  private let eventsSeen: Bool;
  private let quietScans: Int32;
  // 0 nothing logged, 1 'ids from entity hash', 2 'ids from ... fallback' (logged once per live spell, again on a switch).
  private let idsLogged: Int32;
  // Distance to the nearest listed device (999 when none), for the probes' device-hit check (Probes.reds).
  private let nearest: Float;
  private let logDelay: Int32;
  // devstate lines: at most one a second, the next one counts the rest.
  private let stateLogAt: Float;
  private let stateSkipped: Int32;

  public static func Get() -> ref<CyberblockDeviceService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockDeviceService") as CyberblockDeviceService;
  }

  // From CyberblockSystem.Tick, every 100 ms (live: Active or Vehicle, not saving; player null when there is none).
  public static func Update(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void {
    let service = CyberblockDeviceService.Get();
    if IsDefined(service) {
      service.Tick(game, player, live);
    }
  }

  // From CyberblockEvents.Tick for every n"devact", whatever the state: every request gets its Cyberblock_DeviceResult.
  public static func OnEvent(game: GameInstance) -> Void {
    let service = CyberblockDeviceService.Get();
    if IsDefined(service) {
      service.Act(game);
    } else {
      Cyberblock_DeviceResult(Cyberblock_EventId(), false, "not ready");
    }
  }

  private cb func OnLoad() {
    this.nearest = 999.0;
    this.Listen();
  }

  // Every 100 ms tick of CyberblockSystem. live: Active or Vehicle, not saving, with a player.
  public func Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void {
    this.Listen();
    if !live || !IsDefined(player) {
      if this.sent {
        let ids: array<Uint64>;
        let classes: array<CName>;
        let kinds: array<CName>;
        let pos: array<Vector4>;
        let yaws: array<Float>;
        let sizes: array<Vector4>;
        let states: array<Int32>;
        let flags: array<Int32>;
        Cyberblock_SubmitDevices(ids, classes, kinds, pos, yaws, sizes, states, flags);
        Cyberblock_Log("devices: not active, empty list sent");
      }
      this.Reset();
      return;
    }
    this.live = true;
    this.Flush(game);
    this.ticks += 1;
    if this.ticks >= 5 {
      this.ticks = 0;
      this.Scan(game, player);
    }
  }

  // Metres to the nearest listed device, 999 when none (Probes.reds).
  public func Nearest() -> Float {
    return this.live ? this.nearest : 999.0;
  }

  // From the Device wraps: a listed device's persistent state changed or one of its actions ran.
  public func OnChanged(device: wref<Device>, by: CName) -> Void {
    if !this.live {
      return;
    }
    let i = ArrayFindFirst(this.objects, device);
    if i < 0 {
      return;
    }
    if !this.eventsSeen || this.polling {
      this.eventsSeen = true;
      this.polling = false;
      Cyberblock_Log(s"devices: changes via events (first from \(NameToString(this.classes[i])) \(this.ids[i]))");
    }
    this.quietScans = 0;
    this.dirty[i] = true;
    if NotEquals(by, n"script") {
      this.dirtyBy[i] = by;
    }
  }

  // Minecraft's devact (the current event): runs the act on a listed device and answers it.
  public func Act(game: GameInstance) -> Void {
    let request = Cyberblock_EventId();
    let id = Cyberblock_EventTarget();
    let act = Cyberblock_EventName();
    let player = GetPlayer(game);
    let i = ArrayFindFirst(this.ids, id);
    let label = "device";
    let reason = "not in range";
    if !this.live || !IsDefined(player) {
      reason = "Cyberblock is not active";
    } else {
      if i >= 0 {
        label = NameToString(this.classes[i]);
        let device = this.objects[i];
        if IsDefined(device) && device.IsAttached() {
          reason = this.Run(device, player, act);
        }
      }
    }
    let ok = StrLen(reason) == 0;
    Cyberblock_DeviceResult(request, ok, reason);
    Cyberblock_Log(s"devices: devact \(request) \(NameToString(act)) \(label) \(id): \(ok ? "ok" : s"refused (\(reason))")");
  }

  // Runs one act; "" when it ran (or the device already is as asked), else why not.
  private func Run(device: ref<Device>, player: ref<PlayerPuppet>, act: CName) -> String {
    let ps = device.GetDevicePS();
    if !IsDefined(ps) {
      return "not ready";
    }
    if !CyberblockDeviceService.Supported(act) {
      return "unsupported";
    }
    if CyberblockDeviceService.QuestLocked(device) {
      return "quest locked";
    }
    let door = ps as DoorControllerPS;
    let action: ref<ScriptableDeviceAction>;
    if IsDefined(door) {
      if Equals(act, n"open") || (Equals(act, n"toggle") && !door.IsOpen()) {
        if door.IsLocked() {
          return "locked";
        }
        action = Equals(act, n"open") ? door.ActionSetOpened() : door.ActionToggleOpen();
      } else if Equals(act, n"close") || Equals(act, n"toggle") {
        action = Equals(act, n"close") ? door.ActionSetClosed() : door.ActionToggleOpen();
      } else if Equals(act, n"lock") || Equals(act, n"unlock") {
        if Equals(door.IsLocked(), Equals(act, n"lock")) {
          return ""; // already as asked
        }
        action = door.ActionToggleLock();
      } else {
        return "unsupported";
      }
    } else {
      if Equals(act, n"on") || Equals(act, n"enable") {
        action = ps.ActionSetDeviceON();
      } else if Equals(act, n"off") || Equals(act, n"disable") {
        action = ps.ActionSetDeviceOFF();
      } else if Equals(act, n"toggle") {
        action = ps.ActionToggleON();
      } else if Equals(act, n"distract") {
        action = ps.CyberblockDistraction();
      } else if Equals(act, n"friendly") {
        action = ps.ActionSetDeviceAttitude();
      } else {
        return "unsupported";
      }
    }
    if !IsDefined(action) {
      return "no action";
    }
    action.SetExecutor(player);
    ps.ExecutePSAction(action);
    return "";
  }

  // The acts the scripts run (section 1.8 amendment item 6).
  private static func Supported(act: CName) -> Bool {
    return Equals(act, n"open") || Equals(act, n"close") || Equals(act, n"toggle") || Equals(act, n"lock")
      || Equals(act, n"unlock") || Equals(act, n"on") || Equals(act, n"off") || Equals(act, n"disable")
      || Equals(act, n"enable") || Equals(act, n"distract") || Equals(act, n"friendly");
  }

  // The changed devices since the last tick: each whose state or flags differ from what Minecraft has goes as devstate.
  private func Flush(game: GameInstance) -> Void {
    let i = 0;
    while i < ArraySize(this.objects) {
      if this.dirty[i] {
        this.dirty[i] = false;
        let device = this.objects[i];
        if IsDefined(device) && IsDefined(device.GetDevicePS()) && NotEquals(device.GetDeviceState(), EDeviceStatus.INVALID) {
          this.Report(game, i, CyberblockDeviceService.State(device), CyberblockDeviceService.Flags(device), this.dirtyBy[i]);
        }
        this.dirtyBy[i] = n"script";
      }
      i += 1;
    }
  }

  // Cyberblock_DeviceState when state or flags differ from the last ones sent for slot i.
  private func Report(game: GameInstance, i: Int32, state: Int32, flags: Int32, by: CName) -> Void {
    if state == this.states[i] && flags == this.flags[i] {
      return;
    }
    this.states[i] = state;
    this.flags[i] = flags;
    Cyberblock_DeviceState(this.ids[i], state, flags, by);
    let now = CyberblockEffectsService.Now(game);
    let age = now - this.stateLogAt;
    if age >= 0.0 && age < 1.0 {
      this.stateSkipped += 1;
      return;
    }
    let more = this.stateSkipped > 0 ? s" (\(this.stateSkipped) more not logged before it)" : "";
    Cyberblock_Log(s"devices: \(NameToString(this.classes[i])) \(this.ids[i]) state \(state) flags \(flags) by \(NameToString(by))\(more)");
    this.stateLogAt = now;
    this.stateSkipped = 0;
  }

  // The 0.5 s list: the devices of a known kind within 48 m, at most 64, nearest first.
  private func Scan(game: GameInstance, player: ref<PlayerPuppet>) -> Void {
    let center = player.GetWorldPosition();
    let maxCount = 64;
    let maxDistance2 = 48.0 * 48.0;
    let picked: array<wref<Device>>;
    let pickedKinds: array<CName>;
    let pickedDistance2: array<Float>;
    let far = 0;
    let other = 0;
    let overCap = 0;
    let notReady = 0;
    let attached = 0;
    let i = ArraySize(this.known) - 1;
    while i >= 0 {
      let device = this.known[i];
      if !IsDefined(device) {
        ArrayErase(this.known, i);
      } else {
        if device.IsAttached() {
          attached += 1;
          let distance2 = Vector4.DistanceSquared(device.GetWorldPosition(), center);
          if distance2 >= maxDistance2 {
            far += 1;
          } else {
            let kind = CyberblockDeviceService.Kind(device);
            if Equals(kind, n"") {
              other += 1;
            } else {
              let at = ArraySize(picked);
              while at > 0 && pickedDistance2[at - 1] > distance2 {
                at -= 1;
              }
              ArrayInsert(picked, at, device);
              ArrayInsert(pickedKinds, at, kind);
              ArrayInsert(pickedDistance2, at, distance2);
              if ArraySize(picked) > maxCount {
                ArrayResize(picked, maxCount);
                ArrayResize(pickedKinds, maxCount);
                ArrayResize(pickedDistance2, maxCount);
                overCap += 1;
              }
            }
          }
        }
      }
      i -= 1;
    }

    let ids: array<Uint64>;
    let classes: array<CName>;
    let kinds: array<CName>;
    let pos: array<Vector4>;
    let yaws: array<Float>;
    let sizes: array<Vector4>;
    let states: array<Int32>;
    let flags: array<Int32>;
    let objects: array<wref<Device>>;
    let fallbacks = 0;
    let zero = new Vector4(0.0, 0.0, 0.0, 0.0);
    this.nearest = 999.0;
    let k = 0;
    while k < ArraySize(picked) {
      let device = picked[k];
      if !IsDefined(device.GetDevicePS()) || Equals(device.GetDeviceState(), EDeviceStatus.INVALID) {
        notReady += 1;
      } else {
        let position = device.GetWorldPosition();
        let cls = CyberblockDeviceService.ClassName(device);
        let id = CyberblockIds.Hash(device);
        if id == 0ul || ArrayContains(ids, id) {
          id = CyberblockDeviceService.FallbackId(cls, position);
          fallbacks += 1;
        }
        let state = CyberblockDeviceService.State(device);
        let flag = CyberblockDeviceService.Flags(device);
        let forward = device.GetWorldForward();
        if ArraySize(ids) == 0 {
          this.nearest = SqrtF(pickedDistance2[k]);
        }
        ArrayPush(objects, device);
        ArrayPush(ids, id);
        ArrayPush(classes, cls);
        ArrayPush(kinds, pickedKinds[k]);
        ArrayPush(pos, position);
        ArrayPush(yaws, Rad2Deg(AtanF(-forward.X, forward.Y)));
        ArrayPush(sizes, zero);
        ArrayPush(states, state);
        ArrayPush(flags, flag);
      }
      k += 1;
    }

    // The 2 Hz poll fallback: compare with what Minecraft last got (the list carries the new state anyway; devstate
    // tells it a change happened). Devices new to the list are not changes.
    if this.polling {
      let n = 0;
      while n < ArraySize(objects) {
        let old = ArrayFindFirst(this.objects, objects[n]);
        if old >= 0 {
          this.ids[old] = ids[n];
          this.Report(game, old, states[n], flags[n], n"script");
        }
        n += 1;
      }
    }

    Cyberblock_SubmitDevices(ids, classes, kinds, pos, yaws, sizes, states, flags);
    this.sent = true;
    this.objects = objects;
    this.ids = ids;
    this.classes = classes;
    this.states = states;
    this.flags = flags;
    ArrayResize(this.dirty, ArraySize(objects));
    ArrayResize(this.dirtyBy, ArraySize(objects));
    let d = 0;
    while d < ArraySize(objects) {
      this.dirty[d] = false;
      this.dirtyBy[d] = n"script";
      d += 1;
    }

    this.LogIds(ArraySize(ids), fallbacks);
    this.CheckEvents(ArraySize(ids));
    if this.logDelay > 0 {
      this.logDelay -= 1;
    } else {
      this.logDelay = 10; // 5 s
      Cyberblock_Log(s"devices: sent \(ArraySize(ids)) (\(CyberblockDeviceService.Tally(kinds))) of \(attached) attached; left out: \(far) beyond 48 m, \(other) other classes, \(overCap) over the cap of 64, \(notReady) not ready; changes via \(this.polling ? "2 Hz poll" : "events")");
    }
  }

  // 'devices: ids from entity hash|position+class fallback': once per live spell, and again when the path changes.
  private func LogIds(count: Int32, fallbacks: Int32) -> Void {
    if count == 0 {
      return;
    }
    let path = fallbacks > 0 ? 2 : 1;
    if path == this.idsLogged || path == 1 && this.idsLogged == 2 {
      return;
    }
    this.idsLogged = path;
    if path == 2 {
      Cyberblock_Log(s"devices: ids from position+class fallback (\(fallbacks) of \(count): entity hash 0 or shared)");
    } else {
      Cyberblock_Log(s"devices: ids from entity hash (\(count) devices)");
    }
  }

  // No change event from a listed device yet, for 60 s (120 scans) while devices are listed: poll. Once one event has
  // come the wraps are known to work, and quiet devices are only quiet.
  private func CheckEvents(count: Int32) -> Void {
    if this.polling || this.eventsSeen || count == 0 {
      return;
    }
    this.quietScans += 1;
    if this.quietScans >= 120 {
      this.polling = true;
      Cyberblock_Log(s"devices: changes via 2 Hz poll (no change event in 60 s with \(count) devices in range)");
    }
  }

  private func Reset() -> Void {
    ArrayClear(this.objects);
    ArrayClear(this.ids);
    ArrayClear(this.classes);
    ArrayClear(this.states);
    ArrayClear(this.flags);
    ArrayClear(this.dirty);
    ArrayClear(this.dirtyBy);
    this.live = false;
    this.sent = false;
    this.ticks = 0;
    this.quietScans = 0;
    this.idsLogged = 0;
    this.logDelay = 0;
    this.nearest = 999.0;
  }

  // The device_map kind (section 1.11) from the IsA chain; n"" for other classes.
  public static func Kind(device: ref<Device>) -> CName {
    if IsDefined(device as Door) {
      return n"door";
    }
    if IsDefined(device as SecurityGate) || IsDefined(device as RoadBlock) {
      return n"gate";
    }
    if IsDefined(device as ElectricLight) || IsDefined(device as InteractiveSign) {
      return n"light";
    }
    if IsDefined(device as SurveillanceCamera) {
      return n"camera";
    }
    if IsDefined(device as SecurityTurret) {
      return n"turret";
    }
    if IsDefined(device as Terminal) {
      return n"terminal"; // Computer extends Terminal
    }
    if IsDefined(device as VendingMachine) {
      return n"vending"; // WeaponVendingMachine, IceMachine
    }
    if IsDefined(device as TrafficLight) {
      return n"traffic"; // CrossingLight
    }
    if IsDefined(device as LiftDevice) {
      return n"elevator";
    }
    if IsDefined(device as TV) || IsDefined(device as BillboardDevice) {
      return n"screen";
    }
    return n"";
  }

  // EDoorStatus for doors, else EDeviceStatus.
  public static func State(device: ref<Device>) -> Int32 {
    let door = device.GetDevicePS() as DoorControllerPS;
    if IsDefined(door) {
      return EnumInt(door.GetDoorState());
    }
    return EnumInt(device.GetDeviceState());
  }

  // 1 locked, 2 secure, 4 quest-locked, 8 broken, 16 powered; traffic lights add their colour in bits 32 and 64.
  public static func Flags(device: ref<Device>) -> Int32 {
    let ps = device.GetDevicePS();
    if !IsDefined(ps) {
      return 0;
    }
    let status = device.GetDeviceState();
    let door = ps as DoorControllerPS;
    let flags = 0;
    if IsDefined(door) && door.IsLocked() {
      flags += 1;
    }
    if device.IsDeviceSecured() {
      flags += 2;
    }
    if CyberblockDeviceService.QuestLocked(device) {
      flags += 4;
    }
    if ps.IsBroken() {
      flags += 8;
    }
    if NotEquals(status, EDeviceStatus.UNPOWERED) {
      flags += 16;
    }
    let traffic = device as TrafficLight;
    if IsDefined(traffic) {
      flags += 32 * EnumInt(traffic.CyberblockLightColor());
    }
    return flags;
  }

  // Quest-locked: a SEALED door or a DISABLED device (section 1.9 amendment item 4).
  public static func QuestLocked(device: ref<Device>) -> Bool {
    let door = device.GetDevicePS() as DoorControllerPS;
    return Equals(device.GetDeviceState(), EDeviceStatus.DISABLED) || (IsDefined(door) && door.IsSealed());
  }

  // The RTTI class name, at most 48 characters.
  private static func ClassName(device: ref<Device>) -> CName {
    let name = NameToString(device.GetClassName());
    if StrLen(name) > 48 {
      return StringToName(StrLeft(name, 48));
    }
    return device.GetClassName();
  }

  // The fallback id: the Codeware string hash of the class and the position rounded to 25 cm.
  private static func FallbackId(cls: CName, position: Vector4) -> Uint64 {
    let key = s"\(NameToString(cls))@\(RoundF(position.X * 4.0)),\(RoundF(position.Y * 4.0)),\(RoundF(position.Z * 4.0))";
    return TDBID.ToNumber(TDBID.Create(key));
  }

  // devstate.by from a performed action's executor.
  public static func By(evt: ref<PerformedAction>) -> CName {
    let action = evt.m_action as ScriptableDeviceAction;
    if !IsDefined(action) {
      return n"script";
    }
    let executor = action.GetExecutor();
    if IsDefined(executor as PlayerPuppet) {
      return n"host";
    }
    if IsDefined(executor as ScriptedPuppet) {
      return n"npc";
    }
    return n"script";
  }

  // "door 2, light 5" style counts for the log.
  private static func Tally(kinds: array<CName>) -> String {
    let names: array<CName>;
    let counts: array<Int32>;
    for kind in kinds {
      let at = ArrayFindFirst(names, kind);
      if at < 0 {
        ArrayPush(names, kind);
        ArrayPush(counts, 1);
      } else {
        counts[at] += 1;
      }
    }
    let text = "";
    let k = 0;
    while k < ArraySize(names) {
      text += s"\(k > 0 ? ", " : "")\(NameToString(names[k])) \(counts[k])";
      k += 1;
    }
    return ArraySize(names) == 0 ? "none" : text;
  }

  // Registered when the service loads; re-registered if a handler is ever gone. Cheap to call again.
  public func Listen() -> Void {
    let callbacks = GameInstance.GetCallbackSystem();
    if !IsDefined(callbacks) {
      return;
    }
    if !IsDefined(this.attachHandler) || !this.attachHandler.IsRegistered() {
      this.attachHandler = callbacks.RegisterCallback(n"Entity/AfterAttach", this, n"OnEntityAttach")
        .AddTarget(EntityTarget.Type(n"Device"))
        .SetLifetime(CallbackLifetime.Forever);
    }
    if !IsDefined(this.detachHandler) || !this.detachHandler.IsRegistered() {
      this.detachHandler = callbacks.RegisterCallback(n"Entity/Detach", this, n"OnEntityDetach")
        .AddTarget(EntityTarget.Type(n"Device"))
        .SetLifetime(CallbackLifetime.Forever);
    }
  }

  private cb func OnEntityAttach(event: ref<EntityLifecycleEvent>) {
    let device = event.GetEntity() as Device;
    if IsDefined(device) && !ArrayContains(this.known, device) {
      ArrayPush(this.known, device);
    }
  }

  private cb func OnEntityDetach(event: ref<EntityLifecycleEvent>) {
    let device = event.GetEntity() as Device;
    if IsDefined(device) {
      ArrayRemove(this.known, device);
    }
  }
}

// The traffic light's colour (protected m_lightState, trafficLight.swift 4).
@addMethod(TrafficLight)
public func CyberblockLightColor() -> worldTrafficLightColor {
  return this.m_lightState;
}

// The distraction quickhack's action (the builder is protected; subclasses' overrides apply).
@addMethod(ScriptableDeviceComponentPS)
public func CyberblockDistraction() -> ref<ScriptableDeviceAction> {
  return this.ActionQuickHackDistraction();
}

@wrapMethod(Device)
protected cb func OnPerformedAction(evt: ref<PerformedAction>) -> Bool {
  let result = wrappedMethod(evt);
  let service = CyberblockDeviceService.Get();
  if IsDefined(service) {
    service.OnChanged(this, CyberblockDeviceService.By(evt));
  }
  return result;
}

@wrapMethod(Device)
protected cb func OnPSChangedEvent(evt: ref<PSChangedEvent>) -> Bool {
  let result = wrappedMethod(evt);
  let service = CyberblockDeviceService.Get();
  if IsDefined(service) {
    service.OnChanged(this, n"script");
  }
  return result;
}
