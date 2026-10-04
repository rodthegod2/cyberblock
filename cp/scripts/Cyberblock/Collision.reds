// Invisible 1 m collision boxes for blocks placed in Minecraft (layer 2 plan §1.4, S2.2; method: docs/notes/collision-spike.md).
// Every box Minecraft reports is registered by its AABB min corner: integer x/y, z = block y + k/64, the world's ground
// alignment (plan 2026-10-03-retest2 section 1.1, task S4; k is 0..63, so z is exact in Float and FloorF(z) is the
// block y). The box keeps that exact z, so a block placed on the aligned street sits on it rather than up to 1 m
// above; the registry key uses FloorF(z). Stream keeps those within 64 m of V spawned,
// at most 1000 (nearest first). A box is the vanilla engine\fallback\helper_no_entity.ent spawned by Codeware's
// StaticEntitySystem, whose mesh is switched off and which gets a script-built World Static box collider while it
// initializes. Spawned boxes are runtime only: Clear despawns them all (deactivate, session end, before save) and keeps
// the registry; Reset also empties the registry (the link to Minecraft starts or ends, see docs/notes/collision-spike.md).
//
// 2.31 + Codeware 1.20.3 APIs (Codeware.Global.reds, NativeDB, compile-checked): StaticEntitySystem.SpawnEntity/
// DespawnEntity/DespawnTagged, StaticEntitySpec, CallbackSystem.RegisterCallback(n"Entity/Initialize") +
// CallbackSystemHandler.AddTarget(StaticEntityTarget.Tag), EntityLifecycleEvent.GetEntity, Entity.GetComponents/
// AddComponent, IComponent.Toggle/IsA, ColliderComponent fields, physicsColliderBox, physicsFilterData,
// SimulationFilter/QueryFilter masks, SimulationFilter.SimulationFilter_BuildFromPreset, QueryFilter.ZERO/AddGroup,
// TrafficGenDynamicImpact, ScriptableServiceContainer.GetService, inkIntHashMap (Uint64 -> Int32, native).

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

  // Plan 2026-10-03 §1.4 contract (block_reset): despawns all boxes and forgets every registered one, for when the
  // boxes may no longer match the Minecraft world (a new link session; Minecraft then re-sends its blocks).
  public static func Reset(game: GameInstance) -> Void {
    CyberblockCollisionService.Get().Reset();
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
    ArrayPush(this.mins, new Vector4(Cast<Float>(RoundF(aabbMin.X)), Cast<Float>(RoundF(aabbMin.Y)), aabbMin.Z, 1.0));
    let none: EntityID;
    ArrayPush(this.spawned, none);
    this.dirty = true;
  }

  // Read only (megaplan Q-S2): a registered box contains `point`. A box spans [min z, min z + 1) with min z = block y +
  // k/64 and is keyed by the block y, so the boxes keyed FloorF(z) and FloorF(z) - 1 are the only candidates.
  public func Has(point: Vector4) -> Bool {
    let aabbMin: Vector4;
    return this.Find(point, aabbMin);
  }

  // The registered box that contains `point`, as its AABB min corner.
  public func Find(point: Vector4, out aabbMin: Vector4) -> Bool {
    let below = 0;
    while below <= 1 {
      let key: Uint64;
      let corner = new Vector4(Cast<Float>(FloorF(point.X)), Cast<Float>(FloorF(point.Y)), Cast<Float>(FloorF(point.Z) - below), 1.0);
      if CyberblockCollisionService.Key(corner, key) && this.Slots().KeyExist(key) {
        let m = this.mins[this.slots.Get(key)];
        if m.Z <= point.Z && point.Z < m.Z + 1.0 {
          aabbMin = m;
          return true;
        }
      }
      below += 1;
    }
    return false;
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

  // Despawns every box and empties the registry; the only way to forget boxes besides Remove.
  public func Reset() -> Void {
    this.Clear();
    Cyberblock_Log(s"collision: reset, \(ArraySize(this.keys)) boxes forgotten");
    ArrayClear(this.keys);
    ArrayClear(this.mins);
    ArrayClear(this.spawned);
    this.slots = new inkIntHashMap();
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

    // World Static: what buildings use; its type is in the collision mask of the player, NPCs and vehicles. Both masks
    // come from the engine's own group table, so they match the running game: the simulation filter from the preset,
    // the query filter from the preset's QueryDetect groups (2.31 engine\physics\collision_presets.json), each set by
    // QueryFilter.AddGroup (it ORs the group's bit into mask2, where cooked colliders keep QueryDetect; mask1 stays 0
    // as in cooked colliders). The masks once hard-coded here were an older bit layout (docs/notes/collision-spike.md).
    let simulation: SimulationFilter;
    SimulationFilter.SimulationFilter_BuildFromPreset(simulation, n"World Static");
    let query = QueryFilter.ZERO();
    let groups: array<CName> = [n"Static", n"Visibility", n"Shooting", n"VehicleBlocker", n"PhotoModeCamera", n"TankBlocker", n"PlayerBlocker"];
    for group in groups {
      QueryFilter.AddGroup(query, group);
    }
    let filter = new physicsFilterData();
    filter.preset = n"World Static";
    filter.simulationFilter = simulation;
    filter.queryFilter = query;
    if !this.filterLogged {
      this.filterLogged = true;
      Cyberblock_Log(s"collision: World Static filters from the engine: simulation \(simulation.mask1)/\(simulation.mask2), query \(query.mask1)/\(query.mask2)");
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

  // Packs integer x, y (|v| < 2^21 m) and the block y FloorF(z) (|z| < 2^15 m) into one key. Night City spans about
  // ±10 km.
  private static func Key(aabbMin: Vector4, out key: Uint64) -> Bool {
    if AbsF(aabbMin.X) >= 2097152.0 || AbsF(aabbMin.Y) >= 2097152.0 || AbsF(aabbMin.Z) >= 32768.0 {
      return false;
    }
    let packed = Cast<Int64>(RoundF(aabbMin.X) + 2097152) * 274877906944l
      + Cast<Int64>(RoundF(aabbMin.Y) + 2097152) * 65536l
      + Cast<Int64>(FloorF(aabbMin.Z) + 32768);
    key = Cast<Uint64>(packed);
    return true;
  }
}
