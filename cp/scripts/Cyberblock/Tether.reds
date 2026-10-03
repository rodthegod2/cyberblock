// Leads, fishing-rod pulls and wind-charge knockback on Night City (plan 2026-10-03 section 1.4, S2.5). All pushes
// are CyberblockEffects.Knock impulses (m/s, Cyberpunk space): a person is knocked over into a ragdoll by 4 m/s and
// more (ScriptedPuppet.CanRagdoll), a vehicle gets the impulse times its mass. Targets the effects leave alone (V,
// V's car, god modes, V's friends, workspots, scenes: CyberblockEffects.IsProtected, as Hit) are never pushed.
//   Lead: every tick, a target farther than length from its anchor (the knot, or V when Steve holds the lead) is
//     tugged toward it at min(12, 3 x excess) m/s, at most every 0.5 s per target (each tug is a velocity change; at
//     10 Hz they would add up to a throw). More than length + 12 m for 2 s, or the target gone: Cyberblock_Unlead.
//   Pull: one impulse toward the rod at strength, plus 3 m/s up for people.
//   Knockback: a radial impulse of strength x (1 - d / radius) on every NPC and vehicle within radius; no damage.
// Tethers are plain data (ids and numbers): Clear (save, load, Off) keeps them, so an autosave does not cut a lead
// Minecraft still shows; Tick with live false drops them silently (contract); a target that no longer exists after a
// load is unleaded on the next live tick.
//
// Which NPCs and vehicles exist: no 2.31 script query lists them (see Npcs.reds), so this service keeps its own
// registry from Codeware's entity lifecycle callbacks Entity/AfterAttach and Entity/Detach, filtered with
// EntityTarget.Type(n"NPCPuppet" | n"vehicleBaseObject") exactly as Npcs.reds, registered when the service loads
// (Codeware ScriptableService OnLoad; callbacks registered by a service stay registered, Codeware wiki).
//
// 2.31 + Codeware APIs (decompiled 2.31 scripts, Codeware.Global.reds; compile-checked): CallbackSystem.
// RegisterCallback + CallbackSystemHandler.AddTarget/SetLifetime(CallbackLifetime.Forever)/IsRegistered,
// EntityTarget.Type, EntityLifecycleEvent.GetEntity, GameObject.IsAttached, ScriptedPuppet.IsAlive,
// VehicleObject.IsDestroyed, VehicleComponent.IsMountedToVehicle, TimeSystem.GetSimTime.

public abstract class CyberblockTether {
  public static func Lead(game: GameInstance, target: Uint64, anchor: Vector4, fixed: Bool, length: Float) -> Void {
    CyberblockTetherService.Get().Lead(target, anchor, fixed, length);
  }

  public static func Release(game: GameInstance, target: Uint64) -> Void {
    CyberblockTetherService.Get().Release(target);
  }

  public static func Pull(game: GameInstance, target: Uint64, toward: Vector4, strength: Float) -> Void {
    let object = CyberblockEffects.Find(game, target);
    let player = GetPlayer(game);
    if !IsDefined(object) || !IsDefined(player) || strength <= 0.0 || CyberblockEffects.IsProtected(game, object, player) {
      return;
    }
    let impulse = CyberblockTetherService.Toward(object.GetWorldPosition(), toward) * MinF(strength, 50.0);
    if !IsDefined(object as VehicleObject) {
      impulse.Z += 3.0;
    }
    CyberblockEffects.Knock(object, object.GetWorldPosition() + new Vector4(0.0, 0.0, 1.0, 0.0), new Vector4(impulse.X, impulse.Y, impulse.Z, 0.0));
  }

  public static func Knockback(game: GameInstance, center: Vector4, radius: Float, strength: Float) -> Void {
    CyberblockTetherService.Get().Knockback(game, center, radius, strength);
  }

  // 10 Hz. live false: drop tethers silently.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void {
    CyberblockTetherService.Get().Tick(game, player, live);
  }

  public static func Clear(game: GameInstance) -> Void {
    CyberblockTetherService.Get().Clear();
  }
}

public class CyberblockTetherService extends ScriptableService {
  // One slot per tether, parallel arrays.
  private let targets: array<Uint64>;
  private let anchors: array<Vector4>;
  private let fixed: array<Bool>;
  private let lengths: array<Float>;
  private let farSince: array<Float>; // sim time the target went beyond length + 12 m, -1 when not
  private let lastTug: array<Float>;
  // NPCs and vehicles attached to the world.
  private let known: array<wref<GameObject>>;
  private let attachHandler: ref<CallbackSystemHandler>;
  private let detachHandler: ref<CallbackSystemHandler>;

  public static func Get() -> ref<CyberblockTetherService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockTetherService") as CyberblockTetherService;
  }

  private cb func OnLoad() {
    this.Listen();
  }

  public func Lead(target: Uint64, anchor: Vector4, fixed: Bool, length: Float) -> Void {
    let slot = this.Slot(target);
    if slot < 0 {
      slot = ArraySize(this.targets);
      ArrayPush(this.targets, target);
      ArrayPush(this.anchors, anchor);
      ArrayPush(this.fixed, fixed);
      ArrayPush(this.lengths, 0.0);
      ArrayPush(this.farSince, -1.0);
      ArrayPush(this.lastTug, -1000.0);
    }
    this.anchors[slot] = new Vector4(anchor.X, anchor.Y, anchor.Z, 1.0);
    this.fixed[slot] = fixed;
    this.lengths[slot] = ClampF(length, 1.0, 32.0);
    Cyberblock_Log(s"tether: lead \(target) length \(this.lengths[slot]) \(fixed ? "to a knot" : "held by V")");
  }

  public func Release(target: Uint64) -> Void {
    let slot = this.Slot(target);
    if slot >= 0 {
      this.Forget(slot);
      Cyberblock_Log(s"tether: released \(target)");
    }
  }

  public func Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void {
    this.Listen();
    if !live || !IsDefined(player) {
      if ArraySize(this.targets) > 0 {
        Cyberblock_Log(s"tether: \(ArraySize(this.targets)) dropped (not live)");
        this.Drop();
      }
      return;
    }
    let now = CyberblockEffectsService.Now(game);
    let slot = ArraySize(this.targets) - 1;
    while slot >= 0 {
      this.Follow(game, player, slot, now);
      slot -= 1;
    }
  }

  // Keeps the tethers (plain data); only the timers restart.
  public func Clear() -> Void {
    let i = 0;
    while i < ArraySize(this.targets) {
      this.farSince[i] = -1.0;
      this.lastTug[i] = -1000.0;
      i += 1;
    }
  }

  public func Knockback(game: GameInstance, center: Vector4, radius: Float, strength: Float) -> Void {
    let player = GetPlayer(game);
    if !IsDefined(player) || radius <= 0.0 || strength <= 0.0 {
      return;
    }
    center = new Vector4(center.X, center.Y, center.Z, 1.0);
    let pushed = 0;
    let i = ArraySize(this.known) - 1;
    while i >= 0 {
      let object = this.known[i];
      if !IsDefined(object) {
        ArrayErase(this.known, i);
      } else {
        let middle = object.GetWorldPosition() + new Vector4(0.0, 0.0, 1.0, 0.0);
        let d = Vector4.Distance(center, middle);
        if d < radius && CyberblockTetherService.Pushable(game, object, player) {
          let direction = CyberblockTetherService.Toward(center, middle);
          if Vector4.IsZero(direction) {
            direction = new Vector4(0.0, 0.0, 1.0, 0.0);
          }
          CyberblockEffects.Knock(object, middle, direction * (MinF(strength, 50.0) * (1.0 - d / radius)));
          pushed += 1;
        }
      }
      i -= 1;
    }
    Cyberblock_Log(s"tether: knockback radius \(radius) strength \(strength), \(pushed) pushed");
  }

  private func Follow(game: GameInstance, player: ref<PlayerPuppet>, slot: Int32, now: Float) -> Void {
    let target = this.targets[slot];
    let object = CyberblockEffects.Find(game, target);
    if !IsDefined(object) || !object.IsAttached() {
      this.Snap(slot, "target gone");
      return;
    }
    let anchor = this.fixed[slot] ? this.anchors[slot] : player.GetWorldPosition();
    let position = object.GetWorldPosition();
    let distance = Vector4.Distance(position, anchor);
    let length = this.lengths[slot];
    if distance > length + 12.0 {
      if this.farSince[slot] < 0.0 || this.farSince[slot] > now {
        this.farSince[slot] = now;
      } else {
        if now - this.farSince[slot] >= 2.0 {
          this.Snap(slot, "snapped");
          return;
        }
      }
    } else {
      this.farSince[slot] = -1.0;
    }
    let excess = distance - length;
    if excess <= 0.0 || now - this.lastTug[slot] < 0.5 && now >= this.lastTug[slot] {
      return;
    }
    if CyberblockEffects.IsProtected(game, object, player) {
      return;
    }
    this.lastTug[slot] = now;
    let direction = CyberblockTetherService.Toward(position, anchor);
    let impulse = direction * MinF(12.0, 3.0 * excess);
    CyberblockEffects.Knock(object, position + new Vector4(0.0, 0.0, 1.0, 0.0), new Vector4(impulse.X, impulse.Y, impulse.Z, 0.0));
  }

  private func Snap(slot: Int32, why: String) -> Void {
    let target = this.targets[slot];
    Cyberblock_Unlead(target);
    Cyberblock_Log(s"tether: \(target) \(why), unlead");
    this.Forget(slot);
  }

  // NPCs (alive, on foot, not a puppet) and vehicles (not destroyed) that the effects may push.
  private static func Pushable(game: GameInstance, object: wref<GameObject>, player: ref<PlayerPuppet>) -> Bool {
    if !object.IsAttached() {
      return false;
    }
    let vehicle = object as VehicleObject;
    if IsDefined(vehicle) {
      if vehicle.IsDestroyed() {
        return false;
      }
    } else {
      if !ScriptedPuppet.IsAlive(object) || VehicleComponent.IsMountedToVehicle(game, object) || CyberblockPuppets.IsPuppet(game, object.GetEntityID()) {
        return false;
      }
    }
    return !CyberblockEffects.IsProtected(game, object, player);
  }

  // Unit direction from one point to another, w 0 (positions from the DLL and the game may differ in w).
  public static func Toward(from: Vector4, to: Vector4) -> Vector4 {
    return Vector4.Normalize(new Vector4(to.X - from.X, to.Y - from.Y, to.Z - from.Z, 0.0));
  }

  private func Slot(target: Uint64) -> Int32 {
    let i = 0;
    while i < ArraySize(this.targets) {
      if this.targets[i] == target {
        return i;
      }
      i += 1;
    }
    return -1;
  }

  private func Forget(slot: Int32) -> Void {
    ArrayErase(this.targets, slot);
    ArrayErase(this.anchors, slot);
    ArrayErase(this.fixed, slot);
    ArrayErase(this.lengths, slot);
    ArrayErase(this.farSince, slot);
    ArrayErase(this.lastTug, slot);
  }

  private func Drop() -> Void {
    ArrayClear(this.targets);
    ArrayClear(this.anchors);
    ArrayClear(this.fixed);
    ArrayClear(this.lengths);
    ArrayClear(this.farSince);
    ArrayClear(this.lastTug);
  }

  // Registered once when the service loads; re-registered if a handler is ever gone.
  private func Listen() -> Void {
    let callbacks = GameInstance.GetCallbackSystem();
    if !IsDefined(callbacks) {
      return;
    }
    if !IsDefined(this.attachHandler) || !this.attachHandler.IsRegistered() {
      this.attachHandler = callbacks.RegisterCallback(n"Entity/AfterAttach", this, n"OnEntityAttach")
        .AddTarget(EntityTarget.Type(n"NPCPuppet"))
        .AddTarget(EntityTarget.Type(n"vehicleBaseObject"))
        .SetLifetime(CallbackLifetime.Forever);
    }
    if !IsDefined(this.detachHandler) || !this.detachHandler.IsRegistered() {
      this.detachHandler = callbacks.RegisterCallback(n"Entity/Detach", this, n"OnEntityDetach")
        .AddTarget(EntityTarget.Type(n"NPCPuppet"))
        .AddTarget(EntityTarget.Type(n"vehicleBaseObject"))
        .SetLifetime(CallbackLifetime.Forever);
    }
  }

  private cb func OnEntityAttach(event: ref<EntityLifecycleEvent>) {
    let object = event.GetEntity() as GameObject;
    if IsDefined(object) && !ArrayContains(this.known, object) {
      ArrayPush(this.known, object);
    }
  }

  private cb func OnEntityDetach(event: ref<EntityLifecycleEvent>) {
    let object = event.GetEntity() as GameObject;
    if IsDefined(object) {
      ArrayRemove(this.known, object);
    }
  }
}
