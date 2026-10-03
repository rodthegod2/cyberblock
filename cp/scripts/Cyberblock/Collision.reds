// Invisible 1 m collision boxes for blocks placed in Minecraft (layer 2 plan §1.4, S2.2; method: docs/notes/collision-spike.md).
// Every box Minecraft reports is registered by its integer AABB min corner; Stream keeps those within 64 m of V spawned,
// at most 1000 (nearest first). A box is the vanilla engine\fallback\helper_no_entity.ent spawned by Codeware's
// StaticEntitySystem, whose mesh is switched off and which gets a script-built World Static box collider while it
// initializes. Spawned boxes are runtime only: Clear despawns them all (deactivate, session end, before save).
//
// 2.31 + Codeware 1.20.3 APIs (Codeware.Global.reds, NativeDB, compile-checked): StaticEntitySystem.SpawnEntity/
// DespawnEntity/DespawnTagged, StaticEntitySpec, CallbackSystem.RegisterCallback(n"Entity/Initialize") +
// CallbackSystemHandler.AddTarget(StaticEntityTarget.Tag), EntityLifecycleEvent.GetEntity, Entity.GetComponents/
// AddComponent, IComponent.Toggle/IsA, ColliderComponent fields, physicsColliderBox, physicsFilterData,
// SimulationFilter/QueryFilter masks, SimulationFilter.SimulationFilter_BuildFromPreset, TrafficGenDynamicImpact,
// ScriptableServiceContainer.GetService, inkIntHashMap (Uint64 -> Int32, native).

public abstract class CyberblockCollision {
  public static func Add(game: GameInstance, aabbMin: Vector4) -> Void {
    CyberblockCollisionService.Get().Add(aabbMin);
  }

  public static func Remove(game: GameInstance, aabbMin: Vector4) -> Void {
    CyberblockCollisionService.Get().Remove(aabbMin);
  }

  public static func Stream(game: GameInstance, center: Vector4) -> Void {
    CyberblockCollisionService.Get().Stream(center);
  }

  public static func Clear(game: GameInstance) -> Void {
    CyberblockCollisionService.Get().Clear();
  }
}

// A Codeware service, not a ScriptableSystem: the registry must outlive a save load, because the Minecraft world
// keeps its blocks and never re-sends them. It holds no game object references, only ids and positions.
public class CyberblockCollisionService extends ScriptableService {
  // Registry: parallel arrays, slot found by key through `slots`. Removal swaps the last slot in.
  private let slots: ref<inkIntHashMap>;
  private let keys: array<Uint64>;
  private let mins: array<Vector4>;
  private let spawned: array<EntityID>; // undefined EntityID when not spawned
  private let spawnedCount: Int32;
  // Stream re-evaluates when the registry changed, spawns are pending or V moved this far since the last pass.
  private let dirty: Bool;
  private let lastCenter: Vector4;
  private let capped: Bool;
  private let handler: ref<CallbackSystemHandler>;
  private let endHandler: ref<CallbackSystemHandler>;
  private let filterLogged: Bool;
  private let spawnFailLogged: Bool;

  public static func Get() -> ref<CyberblockCollisionService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockCollisionService") as CyberblockCollisionService;
  }

  public static func Tag() -> CName {
    return n"CyberblockCollision";
  }

  public func Add(aabbMin: Vector4) -> Void {
    let key: Uint64;
    if !CyberblockCollisionService.Key(aabbMin, key) {
      Cyberblock_Log(s"collision: add ignored, out of range \(aabbMin.X) \(aabbMin.Y) \(aabbMin.Z)");
      return;
    }
    if this.Slots().KeyExist(key) {
      return;
    }
    this.slots.Insert(key, ArraySize(this.keys));
    ArrayPush(this.keys, key);
    ArrayPush(this.mins, new Vector4(Cast<Float>(RoundF(aabbMin.X)), Cast<Float>(RoundF(aabbMin.Y)), Cast<Float>(RoundF(aabbMin.Z)), 1.0));
    let none: EntityID;
    ArrayPush(this.spawned, none);
    this.dirty = true;
  }

  public func Remove(aabbMin: Vector4) -> Void {
    let key: Uint64;
    if !CyberblockCollisionService.Key(aabbMin, key) || !this.Slots().KeyExist(key) {
      return;
    }
    let slot = this.slots.Get(key);
    this.Despawn(slot);
    let last = ArraySize(this.keys) - 1;
    if slot != last {
      this.keys[slot] = this.keys[last];
      this.mins[slot] = this.mins[last];
      this.spawned[slot] = this.spawned[last];
      this.slots.Set(this.keys[slot], slot);
    }
    ArrayPop(this.keys);
    ArrayPop(this.mins);
    ArrayPop(this.spawned);
    this.slots.Remove(key);
    this.dirty = true;
  }

  public func Stream(center: Vector4) -> Void {
    let radius = 64.0;
    let cap = 1000;
    let spawnBudget = 64; // per call (10 Hz): about 1.6 s to fill the cap, without a hitch
    let moved = Vector4.DistanceSquared(center, this.lastCenter) >= 16.0;
    if !this.dirty && !moved {
      return;
    }
    this.lastCenter = center;
    this.dirty = false;
    let count = ArraySize(this.keys);

    // Distances in 1 m rings; the cap keeps whole rings nearest first and fills the ring it ends in.
    let rings: array<Int32>;
    ArrayResize(rings, Cast<Int32>(radius));
    let dist: array<Int32>; // ring per slot, -1 outside the radius
    ArrayResize(dist, count);
    let i = 0;
    while i < count {
      let d = Vector4.Distance(center, this.Center(i));
      if d < radius {
        dist[i] = FloorF(d);
        rings[dist[i]] += 1;
      } else {
        dist[i] = -1;
      }
      i += 1;
    }
    let lastRing = ArraySize(rings); // rings below this one are kept whole
    let room = 0; // boxes kept from ring lastRing
    let left = cap;
    let ring = 0;
    while ring < ArraySize(rings) {
      if rings[ring] > left {
        lastRing = ring;
        room = left;
        break;
      }
      left -= rings[ring];
      ring += 1;
    }
    let isCapped = lastRing < ArraySize(rings);
    if NotEquals(isCapped, this.capped) {
      this.capped = isCapped;
      Cyberblock_Log(isCapped ? s"collision: cap \(cap) reached, keeping boxes within \(lastRing) m" : "collision: below cap again");
    }

    let wanted: array<Bool>;
    ArrayResize(wanted, count);
    i = 0;
    while i < count {
      if dist[i] >= 0 && dist[i] < lastRing {
        wanted[i] = true;
      } else {
        if dist[i] == lastRing && room > 0 {
          wanted[i] = true;
          room -= 1;
        }
      }
      i += 1;
    }

    // Despawn first, so the cap holds while spawning.
    i = 0;
    while i < count {
      if !wanted[i] {
        this.Despawn(i);
      }
      i += 1;
    }
    i = 0;
    while i < count {
      if wanted[i] && !EntityID.IsDefined(this.spawned[i]) {
        // Out of budget, or the entity system is not ready (session start): continue next call.
        if spawnBudget == 0 || !this.Spawn(i) {
          this.dirty = true;
          return;
        }
        spawnBudget -= 1;
      }
      i += 1;
    }
  }

  // Despawns every box; the registry is kept so Stream brings them back.
  public func Clear() -> Void {
    let entities = GameInstance.GetStaticEntitySystem();
    if IsDefined(entities) {
      entities.DespawnTagged(CyberblockCollisionService.Tag());
    }
    let none: EntityID;
    let i = 0;
    while i < ArraySize(this.spawned) {
      this.spawned[i] = none;
      i += 1;
    }
    if this.spawnedCount > 0 {
      Cyberblock_Log(s"collision: cleared \(this.spawnedCount) boxes (\(ArraySize(this.keys)) registered)");
    }
    this.spawnedCount = 0;
    this.dirty = true;
  }

  private func Spawn(slot: Int32) -> Bool {
    let entities = GameInstance.GetStaticEntitySystem();
    if !IsDefined(entities) || !entities.IsReady() {
      return false;
    }
    this.Listen();
    let spec = new StaticEntitySpec();
    spec.templatePath = r"engine\\fallback\\helper_no_entity.ent";
    spec.position = this.Center(slot);
    spec.orientation = new Quaternion(0.0, 0.0, 0.0, 1.0);
    spec.attached = true;
    spec.tags = [CyberblockCollisionService.Tag()];
    this.spawned[slot] = entities.SpawnEntity(spec);
    if !EntityID.IsDefined(this.spawned[slot]) {
      if !this.spawnFailLogged {
        this.spawnFailLogged = true;
        Cyberblock_Log("collision: SpawnEntity failed, retrying");
      }
      return false;
    }
    if this.spawnedCount == 0 {
      Cyberblock_Log(s"collision: first box spawned (\(ArraySize(this.keys)) registered)");
    }
    this.spawnedCount += 1;
    return true;
  }

  private func Despawn(slot: Int32) -> Void {
    let entities = GameInstance.GetStaticEntitySystem();
    if EntityID.IsDefined(this.spawned[slot]) && IsDefined(entities) {
      entities.DespawnEntity(this.spawned[slot]);
      let none: EntityID;
      this.spawned[slot] = none;
      this.spawnedCount -= 1;
    }
  }

  // Callbacks may end with the session, so re-register whenever a handler is gone.
  private func Listen() -> Void {
    let callbacks = GameInstance.GetCallbackSystem();
    if !IsDefined(this.handler) || !this.handler.IsRegistered() {
      this.handler = callbacks.RegisterCallback(n"Entity/Initialize", this, n"OnBoxInitialize")
        .AddTarget(StaticEntityTarget.Tag(CyberblockCollisionService.Tag()));
    }
    // CyberblockSystem calls Clear at session end too; this one also covers a session that ends without it, so ids of
    // a finished session are never mistaken for spawned boxes after a load.
    if !IsDefined(this.endHandler) || !this.endHandler.IsRegistered() {
      this.endHandler = callbacks.RegisterCallback(n"Session/BeforeEnd", this, n"OnSessionEnd");
    }
  }

  private cb func OnSessionEnd(event: ref<GameSessionEvent>) {
    this.Clear();
  }

  private cb func OnBoxInitialize(event: ref<EntityLifecycleEvent>) {
    let entity = event.GetEntity();
    if !IsDefined(entity) {
      return;
    }
    // The template's only mesh is an engine helper; the box must be invisible.
    for component in entity.GetComponents() {
      if component.IsA(n"entMeshComponent") {
        component.Toggle(false);
      }
    }
    entity.AddComponent(this.NewCollider());
  }

  private func NewCollider() -> ref<ColliderComponent> {
    let box = new physicsColliderBox();
    box.halfExtents = new Vector3(0.5, 0.5, 0.5);
    let shapes: array<ref<physicsICollider>>;
    ArrayPush(shapes, box);

    // World Static: what buildings use; its type is in the collision mask of the player, NPCs and vehicles.
    // Masks as cooked for World Static (the values World Builder/entSpawner ships), so the result does not depend on
    // whether the engine resolves the preset name at runtime.
    let simulation: SimulationFilter;
    simulation.mask1 = 114696ul;
    simulation.mask2 = 23627ul;
    let query: QueryFilter;
    query.mask1 = 0ul;
    query.mask2 = 70107400ul;
    let filter = new physicsFilterData();
    filter.preset = n"World Static";
    filter.simulationFilter = simulation;
    filter.queryFilter = query;
    if !this.filterLogged {
      this.filterLogged = true;
      let built: SimulationFilter;
      SimulationFilter.SimulationFilter_BuildFromPreset(built, n"World Static");
      Cyberblock_Log(s"collision: World Static simulation filter from preset \(built.mask1)/\(built.mask2), used \(simulation.mask1)/\(simulation.mask2)");
    }

    // Lets the traffic system treat the box as blocking a lane (if it registers runtime colliders).
    let traffic: TrafficGenDynamicTrafficSetting;
    traffic.impact = TrafficGenDynamicImpact.Blocking;

    let collider = new ColliderComponent();
    collider.name = n"cyberblock_box";
    collider.colliders = shapes;
    collider.simulationType = physicsSimulationType.Static;
    collider.filterData = filter;
    collider.dynamicTrafficSetting = traffic;
    return collider;
  }

  private func Center(slot: Int32) -> Vector4 {
    let m = this.mins[slot];
    return new Vector4(m.X + 0.5, m.Y + 0.5, m.Z + 0.5, 1.0);
  }

  private func Slots() -> ref<inkIntHashMap> {
    if !IsDefined(this.slots) {
      this.slots = new inkIntHashMap();
    }
    return this.slots;
  }

  // Packs integer x, y (|v| < 2^21 m) and z (|v| < 2^15 m) into one key. Night City spans about ±10 km.
  private static func Key(aabbMin: Vector4, out key: Uint64) -> Bool {
    if AbsF(aabbMin.X) >= 2097152.0 || AbsF(aabbMin.Y) >= 2097152.0 || AbsF(aabbMin.Z) >= 32768.0 {
      return false;
    }
    let packed = Cast<Int64>(RoundF(aabbMin.X) + 2097152) * 274877906944l
      + Cast<Int64>(RoundF(aabbMin.Y) + 2097152) * 65536l
      + Cast<Int64>(RoundF(aabbMin.Z) + 32768);
    key = Cast<Uint64>(packed);
    return true;
  }
}
