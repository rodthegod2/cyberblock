// Projectile lookahead (plan 2026-10-03 section 1.4, S2.6): Steve's flying projectiles (pearls, arrows, tridents,
// rockets, potions, wind charges, hooks) must stop at Night City's walls, which Minecraft only knows near Steve. Every
// 100 ms tick while live, each projectile of the latest proj snapshot (at most 16) is traced along its ballistic path
// for the next 0.6 s in 4 straight segments, p + v t - (0, 0, g t^2 / 2), and the first Night City surface it would
// hit goes to Cyberblock_SubmitProjectileHits; Minecraft turns those cells into barriers for 3 s (pcells).
//   Rays use the Probes.reds filter (Static + Terrain). A hit on one of our collision boxes is a Minecraft block,
//   which stops the projectile in Minecraft anyway: that projectile is done and nothing is submitted for it.
//   Each surface point is moved 5 cm on along the ray, into the surface, so the DLL's floor() picks the solid cell
//   and not the air cell in front of a face that lies on an integer coordinate.
//
// 2.31 + Codeware APIs (decompiled 2.31 scripts, Codeware.Global.reds; compile-checked):
// SpatialQueriesSystem.SyncRaycastByQueryFilter(Vector4, Vector4, QueryFilter, out TraceResult, ...) -> Bool,
// QueryFilter.AddGroup, TraceResult.position, Codeware TraceResult.GetHitEntity(script_ref<TraceResult>),
// StaticEntitySystem.IsTagged (the collision boxes' tag, Collision.reds).

public abstract class CyberblockProjectiles {
  // 10 Hz.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void {
    if !live {
      return;
    }
    let count = Min(Cyberblock_ProjCount(), 16);
    if count <= 0 {
      return;
    }
    let filter: QueryFilter;
    QueryFilter.AddGroup(filter, n"Static");
    QueryFilter.AddGroup(filter, n"Terrain");
    let queries = GameInstance.GetSpatialQueriesSystem(game);
    let hits: array<Vector4>;
    let i = 0;
    while i < count {
      let hit: Vector4;
      if CyberblockProjectiles.Trace(queries, filter, Cyberblock_ProjPos(i), Cyberblock_ProjVelocity(i), Cyberblock_ProjGravity(i), hit) {
        ArrayPush(hits, hit);
      }
      i += 1;
    }
    if ArraySize(hits) > 0 {
      Cyberblock_SubmitProjectileHits(hits);
    }
  }

  // The first Night City surface on the path within 0.6 s, if any (false also when a Minecraft block comes first).
  private static func Trace(queries: ref<SpatialQueriesSystem>, filter: QueryFilter, p: Vector4, v: Vector4, g: Float, out hit: Vector4) -> Bool {
    let segments = 4;
    let step = 0.15; // s
    let from = new Vector4(p.X, p.Y, p.Z, 1.0);
    let result: TraceResult;
    let k = 1;
    while k <= segments {
      let t = step * Cast<Float>(k);
      let to = new Vector4(p.X + v.X * t, p.Y + v.Y * t, p.Z + v.Z * t - 0.5 * g * t * t, 1.0);
      if queries.SyncRaycastByQueryFilter(from, to, filter, result) {
        if CyberblockProjectiles.IsOurBox(result) {
          return false;
        }
        let surface = Cast<Vector4>(result.position);
        let direction = Vector4.Normalize(new Vector4(to.X - from.X, to.Y - from.Y, to.Z - from.Z, 0.0));
        hit = new Vector4(surface.X + direction.X * 0.05, surface.Y + direction.Y * 0.05, surface.Z + direction.Z * 0.05, 1.0);
        return true;
      }
      from = to;
      k += 1;
    }
    return false;
  }

  private static func IsOurBox(result: TraceResult) -> Bool {
    let entity = TraceResult.GetHitEntity(result);
    let boxes = GameInstance.GetStaticEntitySystem();
    return IsDefined(entity) && IsDefined(boxes) && boxes.IsTagged(entity.GetEntityID(), CyberblockCollisionService.Tag());
  }
}
