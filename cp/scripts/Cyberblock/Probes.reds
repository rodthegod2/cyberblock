// Raycast probes that tell Minecraft where Night City is solid. Every 100 ms tick while Active/Vehicle casts a
// budget of 192 rays, round-robin over
//   (a) down rays: columns on a 1-m grid within 24 m of V (nearest first), from feet.z + 3 to feet.z - 10;
//   (b) wall rays: 32 directions x heights 0.5/1.5/2.5 m, 24 m long, from V's feet,
// and hands the hit positions (Cyberpunk space) to Cyberblock_SubmitProbes; the DLL converts and diffs them.
//
// 2.31 APIs (decompiled 2.31 scripts): SpatialQueriesSystem.SyncRaycastByQueryFilter(Vector4, Vector4,
// QueryFilter, out TraceResult, opt Bool, opt Bool) -> Bool (true on hit), QueryFilter.AddGroup(out QueryFilter,
// CName), TraceResult.position: Vector3, Cast(Vector3) -> Vector4, FloorF(Float) -> Int32, Deg2Rad, CosF, SinF.
// Collision groups n"Static" and n"Terrain" both exist (used together by AIFindTeleportPositionForTakedown).
// SyncRaycastByCollisionGroup takes a single group, so one query-filter ray covers Static + Terrain instead.

public class CyberblockProbes {
  // Column offsets (metres from V's 1-m cell) sorted by ring, so the ground under V is probed first.
  private let columnsX: array<Int32>;
  private let columnsY: array<Int32>;
  private let cursor: Int32;

  public func Tick(player: ref<PlayerPuppet>) -> Void {
    let radius = 24;
    let budget = 192;
    let wallDirections = 32;
    let wallHeights = 3; // 0.5, 1.5, 2.5 m
    if ArraySize(this.columnsX) == 0 {
      this.BuildColumns(radius);
    }

    let feet = player.GetWorldPosition();
    let cellX = Cast<Float>(FloorF(feet.X)) + 0.5;
    let cellY = Cast<Float>(FloorF(feet.Y)) + 0.5;
    let filter: QueryFilter;
    QueryFilter.AddGroup(filter, n"Static");
    QueryFilter.AddGroup(filter, n"Terrain");
    let queries = GameInstance.GetSpatialQueriesSystem(player.GetGame());

    let columns = ArraySize(this.columnsX);
    let total = columns + wallDirections * wallHeights;
    let downHits: array<Vector4>;
    let wallHits: array<Vector4>;
    let result: TraceResult;
    let n = 0;
    while n < budget {
      let i = this.cursor % total;
      this.cursor = (i + 1) % total;
      n += 1;
      if i < columns {
        let x = cellX + Cast<Float>(this.columnsX[i]);
        let y = cellY + Cast<Float>(this.columnsY[i]);
        let top = new Vector4(x, y, feet.Z + 3.0, 1.0);
        let bottom = new Vector4(x, y, feet.Z - 10.0, 1.0);
        if queries.SyncRaycastByQueryFilter(top, bottom, filter, result) {
          ArrayPush(downHits, Cast<Vector4>(result.position));
        }
      } else {
        let w = i - columns;
        let angle = Deg2Rad(Cast<Float>(w % wallDirections) * 360.0 / Cast<Float>(wallDirections));
        let height = 0.5 + Cast<Float>(w / wallDirections);
        let start = new Vector4(feet.X, feet.Y, feet.Z + height, 1.0);
        let end = new Vector4(feet.X + Cast<Float>(radius) * CosF(angle), feet.Y + Cast<Float>(radius) * SinF(angle), feet.Z + height, 1.0);
        if queries.SyncRaycastByQueryFilter(start, end, filter, result) {
          ArrayPush(wallHits, Cast<Vector4>(result.position));
        }
      }
    }
    Cyberblock_SubmitProbes(feet, downHits, wallHits, Cast<Float>(radius));
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
}
