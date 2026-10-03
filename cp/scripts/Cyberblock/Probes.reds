// Raycast probes that tell Minecraft where Night City is solid. Every 100 ms tick while Active/Vehicle casts a
// budget of 192 rays and hands the hit positions (Cyberpunk space) to Cyberblock_SubmitProbes; the DLL converts and
// diffs them.
//   On foot / in a vehicle, round-robin over
//     (a) down rays: columns on a 1-m grid within 24 m of V (nearest first), from feet.z + 3 to feet.z - 10;
//     (b) wall rays: 32 directions x heights 0.5/1.5/2.5 m, 24 m long, from V's feet.
//   While V glides (CyberblockFlight.IsFlying, plan 2026-10-03 S1.5), with V's speed s from the position change since
//   the last tick: radius min(64, 24 + s) for both kinds, down rays reaching feet.z - (10 + s), columns on a 1-m grid
//   within 24 m and a 2-m grid beyond. Each pass over them probes the columns inside a +-60 degree cone ahead of the
//   horizontal velocity first (nearest first), then the others; the wall rays run before each of the two groups.
// Our own collision boxes (Collision.reds, Minecraft's blocks) are not Night City: a ray that hits one goes on
// through it, so the probes and the aim report what is behind (collision-spike "Side effects").
//
// 2.31 APIs (decompiled 2.31 scripts): SpatialQueriesSystem.SyncRaycastByQueryFilter(Vector4, Vector4,
// QueryFilter, out TraceResult, opt Bool, opt Bool) -> Bool (true on hit), QueryFilter.AddGroup(out QueryFilter,
// CName), TraceResult.position: Vector3, Cast(Vector3) -> Vector4, FloorF(Float) -> Int32, Deg2Rad, CosF, SinF,
// SqrtF, CeilF, Entity.GetWorldPosition, TimeSystem.GetSimTime + EngineTime.ToFloat (as Effects.reds).
// Collision groups n"Static" and n"Terrain" both exist (used together by AIFindTeleportPositionForTakedown).
// SyncRaycastByCollisionGroup takes a single group, so one query-filter ray covers Static + Terrain instead.
// Codeware 1.20.3: TraceResult.GetHitEntity(script_ref<TraceResult>) -> ref<Entity>,
// StaticEntitySystem.IsTagged(EntityID, CName); the boxes are 1 m cubes centred on their entity position, tagged
// CyberblockCollisionService.Tag().

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

  public func Tick(player: ref<PlayerPuppet>) -> Void {
    let game = player.GetGame();
    let budget = 192;
    let wallDirections = 32;
    let wallHeights = 3; // 0.5, 1.5, 2.5 m
    let walls = wallDirections * wallHeights;
    if ArraySize(this.columnsX) == 0 {
      this.BuildColumns(24);
    }

    let feet = player.GetWorldPosition();
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
        }
      } else {
        let w = -1 - i;
        let angle = Deg2Rad(Cast<Float>(w % wallDirections) * 360.0 / Cast<Float>(wallDirections));
        let height = 0.5 + Cast<Float>(w / wallDirections);
        let start = new Vector4(feet.X, feet.Y, feet.Z + height, 1.0);
        let end = new Vector4(feet.X + radius * CosF(angle), feet.Y + radius * SinF(angle), feet.Z + height, 1.0);
        if this.Trace(queries, filter, start, end, result) {
          ArrayPush(wallHits, Cast<Vector4>(result.position));
        }
      }
    }
    Cyberblock_SubmitProbes(feet, downHits, wallHits, radius);
    this.Aim(player, queries, filter);
  }

  // A raycast against Night City (Static + Terrain) that passes through our collision boxes: from just beyond the
  // box it hit, it casts again, up to 16 boxes deep. True with `result` on the first hit that is not ours.
  private func Trace(queries: ref<SpatialQueriesSystem>, filter: QueryFilter, from: Vector4, to: Vector4, out result: TraceResult) -> Bool {
    let entities = GameInstance.GetStaticEntitySystem();
    let start = from;
    let boxes = 0;
    while boxes <= 16 {
      this.casts += 1;
      let hit: TraceResult;
      if !queries.SyncRaycastByQueryFilter(start, to, filter, hit) {
        return false;
      }
      let entity = TraceResult.GetHitEntity(hit);
      if !IsDefined(entity) || !IsDefined(entities) || !entities.IsTagged(entity.GetEntityID(), CyberblockCollisionService.Tag()) {
        result = hit;
        return true;
      }
      if !CyberblockProbes.PastBox(start, to, entity.GetWorldPosition(), start) {
        return false;
      }
      boxes += 1;
    }
    return false;
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
  private func Aim(player: ref<PlayerPuppet>, queries: ref<SpatialQueriesSystem>, filter: QueryFilter) -> Void {
    let cameras = GameInstance.GetCameraSystem(player.GetGame());
    let view: Transform;
    if !cameras.GetActiveCameraWorldTransform(view) {
      Cyberblock_SetAim(-1.0);
      return;
    }
    let from = Transform.GetPosition(view);
    let forward = cameras.GetActiveCameraForward();
    let to = new Vector4(from.X + forward.X * 8.0, from.Y + forward.Y * 8.0, from.Z + forward.Z * 8.0, 1.0);
    let result: TraceResult;
    if this.Trace(queries, filter, from, to, result) {
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
