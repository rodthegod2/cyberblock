// Raycast probes that tell Minecraft where Night City is solid. Every 100 ms tick while Active/Vehicle casts a
// budget of 192 rays and hands the hit positions (Cyberpunk space) to Cyberblock_SubmitProbes; the DLL converts and
// diffs them.
//   On foot / in a vehicle, round-robin over
//     (a) down rays: columns on a 1-m grid within 24 m of V (nearest first), from feet.z + 3 to feet.z - 10;
//     (b) wall rays: 32 directions x heights 0.5/1.5/2.5 m, 24 m long, from V's feet.
//   While the DLL moves V after Steve's flight (CyberblockFlight.IsFlying, plan 2026-10-03 S1.5 and retest2 S3), with
//   V's speed s from the position change since the last tick: radius min(64, 24 + s) for both kinds, down rays reaching
//   feet.z - (10 + s), columns on a 1-m grid within 24 m and a 2-m grid beyond. Each pass over them probes the columns
//   inside a +-60 degree cone ahead of the horizontal velocity first (nearest first), then the others; the wall rays
//   run before each of the two groups.
//   "V's feet" is CyberblockFlight.Center (V's position). The aim ray starts at V's first-person eye
//   (Cyberblock_ViewEye, the active camera without the third-person offset) along the active camera's forward.
// Our own collision boxes (Collision.reds, Minecraft's blocks) are not Night City: a ray that hits one goes on
// through it, so the probes and the aim report what is behind (collision-spike "Side effects").
//
// 2.31 script API (NativeDB, redscript): SpatialQueriesSystem.SyncRaycastByQueryFilter(Vector4, Vector4,
// QueryFilter, out TraceResult, opt Bool, opt Bool) -> Bool (true on hit), QueryFilter.AddGroup(out QueryFilter,
// CName), TraceResult.position: Vector3, Cast(Vector3) -> Vector4, FloorF(Float) -> Int32, Deg2Rad, CosF, SinF,
// SqrtF, CeilF, Entity.GetWorldPosition, TimeSystem.GetSimTime + EngineTime.ToFloat (as Effects.reds).
// Collision groups n"Static" and n"Terrain" both exist (used together by AIFindTeleportPositionForTakedown).
// SyncRaycastByCollisionGroup takes a single group, so one query-filter ray covers Static + Terrain instead.
// The Atlas survey (SurveyNatives.reds): after the probes, up to 48 more casts per tick go to long down rays at the 2-m
// columns Cyberblock_SurveyNext hands out around V (from feet.z + 500 to feet.z - 200 m, through our boxes like every
// probe): the highest Static, Terrain or Water surface of each column. A hit gets one short Water-only ray (0.5 m
// above to 0.5 m below it) that tells water from ground; the collision group n"Water" is the one vanilla's swim checks
// pass to RaycastWithASingleGroup (defaultTransition.swift). In a vehicle Cyberblock_SurveyNext hands out nothing once the
// DLL casts its own rays (survey_game.cpp frame(): 1.5 ms of rays every frame, columns 66-96 m from V), and never during
// the survey tour.
// Codeware 1.20.3: TraceResult.GetHitEntity(script_ref<TraceResult>) -> ref<Entity>,
// StaticEntitySystem.IsTagged(EntityID, CName); the boxes are 1 m cubes centred on their entity position, tagged
// CyberblockCollisionService.Tag().
//
// Megaplan 2026-10-04 task 3-S1 (section 1.8 amendment items 1 and 2, Phase 0 check (b); the CET lines B1-B5 were not
// run, so both are feature-checked at runtime and the log names the path in use):
//   - Materials: every down and wall hit gets a name, sent right before Cyberblock_SubmitProbes through
//     Cyberblock_SubmitProbeMaterials (parallel to the hit arrays): TraceResult.material when it names something, else
//     the mesh appearance of the hit world node (Codeware TraceResult.GetHitObject -> worldINodeInstance.GetNode ->
//     worldMeshNode / worldInstancedMeshNode .meshAppearance), else n"". The DLL maps the names to material_palette
//     codes through the shared table (shared/vectors.json key `materials`); the scripts only pass names. Once per session,
//     after the first 64 hits: 'probes: materials from trace|mesh appearance|none (heuristic palette)' with the counts.
//   - Device hits: a probe ray whose hit entity is a Device (Door extends InteractiveDevice extends Device, door.swift 2,
//     interactiveDevice.swift 2) is cast again from 10 cm past the hit, as our boxes are, so doors and gates never enter
//     the static replica; 'probes: device hits recast' once, when the first one is seen. The aim ray, Blocked (Scan's
//     line of sight) and the survey keep treating devices as solid (a closed door stops reach and sight).
//     Task 4-S1 adds the other path's line: when a listed device (Devices.reds) stood within 12 m of V for 30 s of probing
//     (300 ticks) and no probe hit was a Device, the hit entity does not name devices (CET B2 negative), so the DLL's
//     device-box filter (package 4D) is what keeps them out of the replica: 'probes: device hits not named by the hit
//     entity (...); device-box filter only', once per session.
// 2.31 script API (NativeDB, redscript): TraceResult.material: CName (orphans.swift), IsNameValid(CName) -> Bool;
// Codeware 1.20.3: TraceResult.GetHitObject(script_ref<TraceResult>) -> ref<ISerializable>, worldINodeInstance.GetNode()
// -> ref<worldNode>, worldMeshNode.meshAppearance, worldInstancedMeshNode.meshAppearance (CName).

// Declared here (rule 0.14), implemented by Cyberblock.dll (natives.cpp, probes.cpp; package 3D): the material name of
// each hit of the Cyberblock_SubmitProbes call that follows it, in the same order (n"" when nothing is known).
public native func Cyberblock_SubmitProbeMaterials(down: array<CName>, wall: array<CName>) -> Void;

public class CyberblockProbes {
  // Column offsets (metres from V's 1-m cell) sorted by ring, so the ground under V is probed first.
  private let columnsX: array<Int32>;
  private let columnsY: array<Int32>;
  private let cursor: Int32;
  // In flight: column offsets out to 64 m (1-m grid within 24 m, 2-m grid beyond), sorted by ring, and the current
  // pass over them (an index into flightX/Y, or -1 - w for wall ray w), rebuilt whenever a pass ends.
  private let flightX: array<Int32>;
  private let flightY: array<Int32>;
  private let pass: array<Int32>;
  private let passCursor: Int32;
  // V's feet and the sim time at the previous tick, for V's speed.
  private let lastFeet: Vector4;
  private let lastTime: Float;
  // Raycasts used so far this tick (a ray through our boxes takes one more per box).
  private let casts: Int32;
  // Where the hits' material names came from (trace, mesh appearance, none) until the once-per-session log line.
  private let fromTrace: Int32;
  private let fromMesh: Int32;
  private let fromNone: Int32;
  private let materialsLogged: Bool;
  private let devicesLogged: Bool;
  // Ticks probed with a listed device within 12 m and no device hit yet, and whether the fallback line was written.
  private let deviceQuietTicks: Int32;
  private let deviceFilterLogged: Bool;

  public func Tick(player: ref<PlayerPuppet>) -> Void {
    let game = player.GetGame();
    let budget = 192;
    let wallDirections = 32;
    let wallHeights = 3; // 0.5, 1.5, 2.5 m
    let walls = wallDirections * wallHeights;
    if ArraySize(this.columnsX) == 0 {
      this.BuildColumns(24);
    }

    let feet = CyberblockFlight.Center(game, player);
    let velocity = this.Velocity(game, feet);
    let flying = CyberblockFlight.IsFlying(game);
    let speed = flying ? MinF(Vector4.Length(velocity), 200.0) : 0.0; // 200 m/s: the flight message limit
    let radius = flying ? MinF(64.0, 24.0 + speed) : 24.0;
    let depth = 10.0 + speed;
    if !flying {
      ArrayClear(this.pass);
    }
    let cellX = Cast<Float>(FloorF(feet.X)) + 0.5;
    let cellY = Cast<Float>(FloorF(feet.Y)) + 0.5;
    let filter: QueryFilter;
    QueryFilter.AddGroup(filter, n"Static");
    QueryFilter.AddGroup(filter, n"Terrain");
    let queries = GameInstance.GetSpatialQueriesSystem(game);

    let downHits: array<Vector4>;
    let wallHits: array<Vector4>;
    let downMaterials: array<CName>;
    let wallMaterials: array<CName>;
    let result: TraceResult;
    this.casts = 0;
    while this.casts < budget {
      let i = flying ? this.NextInPass(velocity, radius, walls) : this.NextRoundRobin(walls);
      if i >= 0 {
        let x = cellX + Cast<Float>(flying ? this.flightX[i] : this.columnsX[i]);
        let y = cellY + Cast<Float>(flying ? this.flightY[i] : this.columnsY[i]);
        let top = new Vector4(x, y, feet.Z + 3.0, 1.0);
        let bottom = new Vector4(x, y, feet.Z - depth, 1.0);
        if this.Trace(queries, filter, top, bottom, result) {
          ArrayPush(downHits, Cast<Vector4>(result.position));
          ArrayPush(downMaterials, this.Material(result));
        }
      } else {
        let w = -1 - i;
        let angle = Deg2Rad(Cast<Float>(w % wallDirections) * 360.0 / Cast<Float>(wallDirections));
        let height = 0.5 + Cast<Float>(w / wallDirections);
        let start = new Vector4(feet.X, feet.Y, feet.Z + height, 1.0);
        let end = new Vector4(feet.X + radius * CosF(angle), feet.Y + radius * SinF(angle), feet.Z + height, 1.0);
        if this.Trace(queries, filter, start, end, result) {
          ArrayPush(wallHits, Cast<Vector4>(result.position));
          ArrayPush(wallMaterials, this.Material(result));
        }
      }
    }
    this.LogMaterials();
    this.CheckDeviceHits();
    Cyberblock_SubmitProbeMaterials(downMaterials, wallMaterials);
    Cyberblock_SubmitProbes(feet, downHits, wallHits, radius);
    this.Aim(player, queries, filter);
    if !CyberblockSurveyTour.Running(game) {
      CyberblockProbes.Survey(queries, feet, 48);
    }
  }

  // The Atlas survey's casts this tick: columns from Cyberblock_SurveyNext until `budget` rays are used or none is left.
  // Returns the number of columns cast.
  public static func Survey(queries: ref<SpatialQueriesSystem>, feet: Vector4, budget: Int32) -> Int32 {
    let hits: array<Vector4>;
    let casts = 0;
    while casts < budget {
      let column = Cyberblock_SurveyNext(feet);
      if column.W < 0.5 {
        break;
      }
      ArrayPush(hits, CyberblockProbes.SurveyRay(queries, column.X, column.Y, feet.Z, casts));
    }
    if ArraySize(hits) > 0 {
      Cyberblock_SubmitSurvey(hits);
    }
    return ArraySize(hits);
  }

  // One survey column: the highest Night City surface (Static, Terrain or Water) between `aroundZ` + 500 and - 200 m,
  // as (x, y, z, kind) with kind 0 miss, 1 ground or building, 2 water. `casts` counts the rays it took.
  public static func SurveyRay(queries: ref<SpatialQueriesSystem>, x: Float, y: Float, aroundZ: Float, out casts: Int32) -> Vector4 {
    let surface: QueryFilter;
    QueryFilter.AddGroup(surface, n"Static");
    QueryFilter.AddGroup(surface, n"Terrain");
    QueryFilter.AddGroup(surface, n"Water");
    let result: TraceResult;
    let used = 0;
    let top = new Vector4(x, y, aroundZ + 500.0, 1.0);
    let bottom = new Vector4(x, y, aroundZ - 200.0, 1.0);
    let devices = 0;
    let hit = CyberblockProbes.TraceNightCity(queries, surface, top, bottom, result, used, false, devices, 16);
    casts += used;
    if !hit {
      return new Vector4(x, y, 0.0, 0.0);
    }
    let z = result.position.Z;
    let water: QueryFilter;
    QueryFilter.AddGroup(water, n"Water");
    let check: TraceResult;
    casts += 1;
    let isWater = queries.SyncRaycastByQueryFilter(new Vector4(x, y, z + 0.5, 1.0), new Vector4(x, y, z - 0.5, 1.0), water, check);
    return new Vector4(x, y, z, isWater ? 2.0 : 1.0);
  }

  // A probe raycast against Night City (Static + Terrain) that passes through our collision boxes and through devices:
  // from just beyond the box or device it hit, it casts again, up to 16 deep. True with `result` on the first hit that
  // is neither.
  private func Trace(queries: ref<SpatialQueriesSystem>, filter: QueryFilter, from: Vector4, to: Vector4, out result: TraceResult) -> Bool {
    let casts = 0;
    let devices = 0;
    let hit = CyberblockProbes.TraceNightCity(queries, filter, from, to, result, casts, true, devices, 16);
    this.casts += casts;
    if devices > 0 && !this.devicesLogged {
      this.devicesLogged = true;
      Cyberblock_Log("probes: device hits recast");
    }
    return hit;
  }

  // Night City (Static + Terrain, not our collision boxes) lies between two points (Scan.reds' line of sight).
  public static func Blocked(game: GameInstance, from: Vector4, to: Vector4) -> Bool {
    let filter: QueryFilter;
    QueryFilter.AddGroup(filter, n"Static");
    QueryFilter.AddGroup(filter, n"Terrain");
    let result: TraceResult;
    let casts = 0;
    let devices = 0;
    return CyberblockProbes.TraceNightCity(GameInstance.GetSpatialQueriesSystem(game), filter, from, to, result, casts, false, devices, 16);
  }

  // Night City surface between two points for the scripts' other callers (Teleport.reds' settle probe and ground snap):
  // through our boxes and devices, as the probes see it.
  public static func Surface(queries: ref<SpatialQueriesSystem>, from: Vector4, to: Vector4, out result: TraceResult) -> Bool {
    let filter: QueryFilter;
    QueryFilter.AddGroup(filter, n"Static");
    QueryFilter.AddGroup(filter, n"Terrain");
    let casts = 0;
    let devices = 0;
    return CyberblockProbes.TraceNightCity(queries, filter, from, to, result, casts, true, devices, 16);
  }

  // Trace's raycast; `casts` counts the rays it took. A hit on one of our boxes is dropped and the ray cast again from
  // just past it, through at most `maxBoxes` boxes (false when there are more). With `passDevices`, a hit on a Device
  // (doors included) is dropped the same way and cast again from 10 cm past the hit point (`devices` counts them; they
  // share the `maxBoxes` limit).
  private static func TraceNightCity(queries: ref<SpatialQueriesSystem>, filter: QueryFilter, from: Vector4, to: Vector4, out result: TraceResult, out casts: Int32, passDevices: Bool, out devices: Int32, maxBoxes: Int32) -> Bool {
    let entities = GameInstance.GetStaticEntitySystem();
    let start = from;
    let boxes = 0;
    while boxes <= maxBoxes {
      casts += 1;
      let hit: TraceResult;
      if !queries.SyncRaycastByQueryFilter(start, to, filter, hit) {
        return false;
      }
      let entity = TraceResult.GetHitEntity(hit);
      let device = passDevices && IsDefined(entity) && (IsDefined(entity as Device) || IsDefined(entity as Door));
      if !device && (!IsDefined(entity) || !IsDefined(entities) || !entities.IsTagged(entity.GetEntityID(), CyberblockCollisionService.Tag())) {
        result = hit;
        return true;
      }
      if device {
        devices += 1;
        if !CyberblockProbes.PastPoint(start, to, Cast<Vector4>(hit.position), 0.1, start) {
          return false;
        }
      } else {
        if !CyberblockProbes.PastBox(start, to, entity.GetWorldPosition(), start) {
          return false;
        }
      }
      boxes += 1;
    }
    return false;
  }

  // The point `step` metres past `point` along the ray from -> to; false when that is beyond `to`.
  private static func PastPoint(from: Vector4, to: Vector4, point: Vector4, step: Float, out next: Vector4) -> Bool {
    let d = to - from;
    let length = Vector4.Length(d);
    if length <= 0.0 {
      return false;
    }
    let along = Vector4.Distance(from, point) + step;
    if along >= length {
      return false;
    }
    let t = along / length;
    next = new Vector4(from.X + d.X * t, from.Y + d.Y * t, from.Z + d.Z * t, 1.0);
    return true;
  }

  // The hit's material name: TraceResult.material when it names something, else the mesh appearance of the hit world
  // node, else n"" (counted for the once-per-session log line).
  private func Material(out result: TraceResult) -> CName {
    let material = result.material;
    if IsNameValid(material) && NotEquals(material, n"None") {
      this.fromTrace += 1;
      return material;
    }
    let look = CyberblockProbes.MeshAppearance(result);
    if IsNameValid(look) && NotEquals(look, n"None") {
      this.fromMesh += 1;
      return look;
    }
    this.fromNone += 1;
    return n"";
  }

  // The mesh appearance of the world node a ray hit (a static mesh or an instanced mesh), n"" for anything else.
  private static func MeshAppearance(out result: TraceResult) -> CName {
    let instance = TraceResult.GetHitObject(result) as worldINodeInstance;
    if !IsDefined(instance) {
      return n"";
    }
    let node = instance.GetNode();
    let mesh = node as worldMeshNode;
    if IsDefined(mesh) {
      return mesh.meshAppearance;
    }
    let instanced = node as worldInstancedMeshNode;
    if IsDefined(instanced) {
      return instanced.meshAppearance;
    }
    return n"";
  }

  // 'probes: materials from trace|mesh appearance|none (heuristic palette) (...)' once per session, after 64 hits:
  // trace when any hit carried a physics material, else mesh appearance when any carried one, else none.
  private func LogMaterials() -> Void {
    let total = this.fromTrace + this.fromMesh + this.fromNone;
    if this.materialsLogged || total < 64 {
      return;
    }
    this.materialsLogged = true;
    let source = this.fromTrace > 0 ? "trace" : (this.fromMesh > 0 ? "mesh appearance" : "none (heuristic palette)");
    Cyberblock_Log(s"probes: materials from \(source) (of \(total) hits: \(this.fromTrace) trace, \(this.fromMesh) mesh appearance, \(this.fromNone) none)");
  }

  // The device-hit path check of task 4-S1 (header): devices near V for 300 probed ticks and none hit.
  private func CheckDeviceHits() -> Void {
    if this.devicesLogged || this.deviceFilterLogged {
      return;
    }
    let service = CyberblockDeviceService.Get();
    if !IsDefined(service) || service.Nearest() > 12.0 {
      return;
    }
    this.deviceQuietTicks += 1;
    if this.deviceQuietTicks >= 300 {
      this.deviceFilterLogged = true;
      Cyberblock_Log("probes: device hits not named by the hit entity (a device within 12 m for 30 s of probing, none hit); device-box filter only");
    }
  }

  // The point 1 cm past where the ray from -> to leaves the 1 m box centred on `center`; false when that is beyond `to`.
  private static func PastBox(from: Vector4, to: Vector4, center: Vector4, out next: Vector4) -> Bool {
    let d = to - from;
    let length = Vector4.Length(d);
    if length <= 0.0 {
      return false;
    }
    let t = 1.0;
    if d.X != 0.0 {
      t = MinF(t, ((d.X > 0.0 ? center.X + 0.5 : center.X - 0.5) - from.X) / d.X);
    }
    if d.Y != 0.0 {
      t = MinF(t, ((d.Y > 0.0 ? center.Y + 0.5 : center.Y - 0.5) - from.Y) / d.Y);
    }
    if d.Z != 0.0 {
      t = MinF(t, ((d.Z > 0.0 ? center.Z + 0.5 : center.Z - 0.5) - from.Z) / d.Z);
    }
    t += 0.01 / length;
    if t >= 1.0 {
      return false;
    }
    next = new Vector4(from.X + d.X * t, from.Y + d.Y * t, from.Z + d.Z * t, 1.0);
    return true;
  }

  // On foot: the next column (>= 0) or wall ray (-1 - w) of the round-robin.
  private func NextRoundRobin(walls: Int32) -> Int32 {
    let columns = ArraySize(this.columnsX);
    let total = columns + walls;
    let i = this.cursor % total;
    this.cursor = (i + 1) % total;
    return i < columns ? i : -1 - (i - columns);
  }

  // In flight: the next entry of the current pass, starting a new pass when it is used up.
  private func NextInPass(velocity: Vector4, radius: Float, walls: Int32) -> Int32 {
    if this.passCursor >= ArraySize(this.pass) {
      this.BuildPass(velocity, radius, walls);
    }
    let i = this.pass[this.passCursor];
    this.passCursor += 1;
    return i;
  }

  // Walls, the columns within `radius` ahead of the horizontal velocity, walls again, then the other columns within
  // `radius`. Below 1 m/s horizontally every column counts as ahead (plain nearest-first order).
  private func BuildPass(velocity: Vector4, radius: Float, walls: Int32) -> Void {
    if ArraySize(this.flightX) == 0 {
      this.BuildFlightColumns(64, 24);
    }
    ArrayClear(this.pass);
    this.passCursor = 0;
    let rest: array<Int32>;
    let horizontal = SqrtF(velocity.X * velocity.X + velocity.Y * velocity.Y);
    let r2 = radius * radius;
    CyberblockProbes.PushWalls(this.pass, walls);
    let i = 0;
    while i < ArraySize(this.flightX) {
      let x = Cast<Float>(this.flightX[i]);
      let y = Cast<Float>(this.flightY[i]);
      let d2 = x * x + y * y;
      if d2 <= r2 {
        // cos 60 deg = 0.5: inside the cone when the angle to the velocity is at most 60 degrees.
        if horizontal < 1.0 || x * velocity.X + y * velocity.Y >= 0.5 * SqrtF(d2) * horizontal {
          ArrayPush(this.pass, i);
        } else {
          ArrayPush(rest, i);
        }
      }
      i += 1;
    }
    CyberblockProbes.PushWalls(this.pass, walls);
    for column in rest {
      ArrayPush(this.pass, column);
    }
  }

  private static func PushWalls(out pass: array<Int32>, walls: Int32) -> Void {
    let w = 0;
    while w < walls {
      ArrayPush(pass, -1 - w);
      w += 1;
    }
  }

  // V's velocity (m/s, Cyberpunk space) from the feet position at the previous tick; zero on the first tick and
  // after a gap of more than 1 s (a pause or a load).
  private func Velocity(game: GameInstance, feet: Vector4) -> Vector4 {
    let now = EngineTime.ToFloat(GameInstance.GetTimeSystem(game).GetSimTime());
    let dt = now - this.lastTime;
    let moved = feet - this.lastFeet;
    this.lastFeet = feet;
    this.lastTime = now;
    if dt <= 0.0 || dt > 1.0 {
      return new Vector4(0.0, 0.0, 0.0, 0.0);
    }
    return new Vector4(moved.X / dt, moved.Y / dt, moved.Z / dt, 0.0);
  }

  // The Night City surface under the crosshair, so Minecraft never targets (or outlines, places, breaks) a block
  // behind it. Minecraft reaches 4.5-5 blocks; 8 m covers that from Cyberpunk's camera with margin.
  // Re-test 3 (F2, task S5): measured from V's first-person eye (Cyberblock_ViewEye: the camera without the DLL's
  // third-person offset), as Minecraft measures its reach from Steve's eyes in either view; identical to the camera in
  // first person. A zero eye (none known yet) falls back to the camera.
  // Megaplan Q-S1: the ray goes through our boxes (Minecraft's blocks are not Night City), casting again from just past
  // each box at most 4 times, so a block placed in front of a wall reports the wall's distance. Behind more than 4 boxes
  // the aim is -1 (no limit): Minecraft's own blocks are in front then and stop its pick.
  private func Aim(player: ref<PlayerPuppet>, queries: ref<SpatialQueriesSystem>, filter: QueryFilter) -> Void {
    let cameras = GameInstance.GetCameraSystem(player.GetGame());
    let view: Transform;
    if !cameras.GetActiveCameraWorldTransform(view) {
      Cyberblock_SetAim(-1.0);
      return;
    }
    let eye = Cyberblock_ViewEye();
    let from = Vector4.IsZero(new Vector4(eye.X, eye.Y, eye.Z, 0.0)) ? Transform.GetPosition(view) : new Vector4(eye.X, eye.Y, eye.Z, 1.0);
    let forward = cameras.GetActiveCameraForward();
    let to = new Vector4(from.X + forward.X * 8.0, from.Y + forward.Y * 8.0, from.Z + forward.Z * 8.0, 1.0);
    let result: TraceResult;
    let casts = 0;
    let devices = 0;
    let hit = CyberblockProbes.TraceNightCity(queries, filter, from, to, result, casts, false, devices, 4);
    this.casts += casts;
    if hit {
      Cyberblock_SetAim(Vector4.Distance(from, Cast<Vector4>(result.position)));
    } else {
      Cyberblock_SetAim(-1.0);
    }
  }

  private func BuildColumns(radius: Int32) -> Void {
    let ring = 0;
    while ring <= radius {
      let inner = (ring - 1) * (ring - 1);
      let outer = ring * ring;
      let y = -radius;
      while y <= radius {
        let x = -radius;
        while x <= radius {
          let d2 = x * x + y * y;
          if d2 <= outer && (ring == 0 || d2 > inner) {
            ArrayPush(this.columnsX, x);
            ArrayPush(this.columnsY, y);
          }
          x += 1;
        }
        y += 1;
      }
      ring += 1;
    }
  }

  // Flight columns sorted by ring (ring r: (r - 1)^2 < d^2 <= r^2) with a counting sort, one sweep of the square to
  // count and one to place; beyond ring `fine` only even offsets (a 2-m grid).
  private func BuildFlightColumns(radius: Int32, fine: Int32) -> Void {
    let starts: array<Int32>;
    ArrayResize(starts, radius + 2);
    let sweep = 0;
    while sweep < 2 {
      let y = -radius;
      while y <= radius {
        let x = -radius;
        while x <= radius {
          let ring = CeilF(SqrtF(Cast<Float>(x * x + y * y)));
          if ring <= radius && (ring <= fine || (x % 2 == 0 && y % 2 == 0)) {
            if sweep == 0 {
              starts[ring + 1] += 1;
            } else {
              this.flightX[starts[ring]] = x;
              this.flightY[starts[ring]] = y;
              starts[ring] += 1;
            }
          }
          x += 1;
        }
        y += 1;
      }
      if sweep == 0 {
        let r = 1;
        while r <= radius + 1 {
          starts[r] += starts[r - 1];
          r += 1;
        }
        ArrayResize(this.flightX, starts[radius + 1]);
        ArrayResize(this.flightY, starts[radius + 1]);
      }
      sweep += 1;
    }
  }
}
