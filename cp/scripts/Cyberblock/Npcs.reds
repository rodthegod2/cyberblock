// The NPCs and vehicles near V, for Minecraft's proxy entities (plan section 1.2 npcs): every 100 ms while Active,
// the living NPCs and vehicles within 48 m of V, at most 48, nearest first, go to Cyberblock_SubmitNpcs as EntityID
// hash, feet position and yaw (Cyberpunk space), vehicle flag and hostile-to-V. The DLL converts and sends npcs.
//
// Which entities exist: there is no 2.31 script query that returns traffic vehicles (TargetingSystem only sees
// enabled TargetingComponents, and a vehicle's are switched on only while hostile NPCs ride it, see
// VehicleComponent.ToggleTargetingComponents), so a registry of attached NPCs and vehicles is kept from Codeware's
// entity lifecycle callbacks: Entity/AfterAttach and Entity/Detach (Codeware 1.20.3 EntityAttachHook /
// EntityDetachHook), filtered natively with EntityTarget.Type, which matches subclasses (entity type IsA; any one
// target matching is enough). Type names are RTTI names: n"NPCPuppet" (a script class) and n"vehicleBaseObject"
// (the native name of VehicleObject, RED4ext.SDK vehicle/BaseObject.hpp). The registry is CyberblockTetherService's
// (Tether.reds, Known): one set of handlers, owned by a Codeware service with CallbackLifetime.Forever and
// re-registered whenever one is gone, so the list cannot go stale with a handler of an ended session.
//
// Left out: dead, defeated or destroyed ones (ScriptedPuppet.IsAlive + ScriptedPuppet.IsDefeated, the Defeated and
// DefeatedWithRecover status effect types; VehicleObject.IsDestroyed), NPCs mounted in a vehicle (their vehicle's
// proxy stands for them, VehicleComponent.IsMountedToVehicle) and Cyberblock's own mob puppets
// (CyberblockPuppets.IsPuppet). Every 5 s the log counts what was sent and attached, people and cars apart, and why
// the rest was left out (beyond 48 m, over the cap of 48, or the Reason).
//
// Deaths (megaplan 2026-10-04 section 1.2 amendment, task 3-F1): one that the last list sent alive and that is now
// dead, defeated or destroyed goes once more, flagged dead, whoever caused it (NCPD, gangs, traffic, a crash), so
// Minecraft leaves a body or a wreck. Cyberblock_Killed (Kills.reds) stays the XP report for V's own kills.
//
// Yaw: degrees counter-clockwise from +Y about +Z, from Entity.GetWorldForward (0 = facing +Y, 90 = facing -X), the
// EulerAngles yaw convention the DLL's cp_yaw_to_mc_yaw (180 - yaw) expects. AtanF(a, b) is atan2(a, b) (as used in
// timeSkipPopup: AtanF(dy, dx)).
// Hostile: GameObject attitude towards V is AIA_Hostile (IsHostileTowardsPlayer); a vehicle is hostile when its
// driver is (VehicleComponent.GetDriverMounted).
// EntityID hash: CyberblockIds.Hash (Ids.reds: the full 64-bit id through a local copy; EntityID.FromHash is the
// inverse), the same hash every other file sends.

public class CyberblockNpcs {
  private let sent: Bool;
  private let logDelay: Int32;
  // The hashes the last list sent alive, and how many were sent flagged dead since the last log line.
  private let lastAlive: array<Uint64>;
  private let deadSent: Int32;

  // Makes sure the shared registry listens (it also does from its own service load and every Tick).
  public func Register() -> Void {
    let tether = CyberblockTetherService.Get();
    if IsDefined(tether) {
      tether.Listen();
    }
  }

  // Every tick. active: state is Active (on foot). Leaving Active sends one empty list so every proxy goes.
  public func Tick(player: ref<PlayerPuppet>, active: Bool) -> Void {
    let ids: array<Uint64>;
    let feet: array<Vector4>;
    let yaws: array<Float>;
    let vehicles: array<Bool>;
    let hostile: array<Bool>;
    let dead: array<Bool>;
    if !active {
      if this.sent {
        Cyberblock_SubmitNpcs(ids, feet, yaws, vehicles, hostile, dead);
        this.sent = false;
      }
      ArrayClear(this.lastAlive);
      return;
    }

    let game = player.GetGame();
    let center = player.GetWorldPosition();
    let maxCount = 48;
    let maxDistance2 = 48.0 * 48.0;
    let known: array<wref<GameObject>>;
    let tether = CyberblockTetherService.Get();
    if IsDefined(tether) {
      known = tether.Known();
    }
    // Nearest first: insertion into arrays kept sorted by squared distance, at most maxCount long.
    let picked: array<wref<GameObject>>;
    let pickedDistance2: array<Float>;
    // What is attached and why entities were left out this tick, for the 5 s log line (in-game test 2026-10-03 sent
    // 0 of 62). Reason is computed once per entity within 48 m.
    let people = 0;
    let cars = 0;
    let far = 0;
    let overCap = 0;
    let rejected: array<CName>;
    // Died since the last list (it sent them alive): their last sighting, flagged dead.
    let dying: array<wref<GameObject>>;
    for object in known {
      if IsDefined(object as VehicleObject) {
        cars += 1;
      } else {
        people += 1;
      }
      let distance2 = Vector4.DistanceSquared(object.GetWorldPosition(), center);
      if distance2 >= maxDistance2 {
        far += 1;
      } else {
        let reason = CyberblockNpcs.Reason(game, object);
        if NotEquals(reason, n"") {
          ArrayPush(rejected, reason);
          if CyberblockNpcs.Died(reason) && ArrayContains(this.lastAlive, CyberblockIds.Hash(object)) {
            ArrayPush(dying, object);
          }
        } else {
          let at = ArraySize(picked);
          while at > 0 {
            if pickedDistance2[at - 1] <= distance2 {
              break;
            }
            at -= 1;
          }
          ArrayInsert(picked, at, object);
          ArrayInsert(pickedDistance2, at, distance2);
          if ArraySize(picked) > maxCount {
            ArrayResize(picked, maxCount);
            ArrayResize(pickedDistance2, maxCount);
            overCap += 1;
          }
        }
      }
    }

    // The dying share the cap of 48: the farthest living ones make room.
    if ArraySize(dying) > maxCount {
      ArrayResize(dying, maxCount);
    }
    if ArraySize(picked) + ArraySize(dying) > maxCount {
      ArrayResize(picked, maxCount - ArraySize(dying));
    }

    // The living first (nearest first), then the dying: entries from aliveCount on are flagged dead.
    let aliveCount = ArraySize(picked);
    for object in dying {
      ArrayPush(picked, object);
    }

    let sentCars = 0;
    let alive: array<Uint64>;
    let k = 0;
    while k < ArraySize(picked) {
      let object = picked[k];
      let hash = CyberblockIds.Hash(object);
      let isDead = k >= aliveCount;
      let forward = object.GetWorldForward();
      let vehicle = object as VehicleObject;
      ArrayPush(ids, hash);
      ArrayPush(feet, object.GetWorldPosition());
      ArrayPush(yaws, Rad2Deg(AtanF(-forward.X, forward.Y)));
      ArrayPush(vehicles, IsDefined(vehicle));
      ArrayPush(dead, isDead);
      if !isDead {
        ArrayPush(alive, hash);
      }
      if IsDefined(vehicle) {
        sentCars += 1;
        ArrayPush(hostile, IsHostileTowardsPlayer(VehicleComponent.GetDriverMounted(game, object.GetEntityID())));
      } else {
        ArrayPush(hostile, IsHostileTowardsPlayer(object));
      }
      k += 1;
    }
    Cyberblock_SubmitNpcs(ids, feet, yaws, vehicles, hostile, dead);
    this.sent = true;
    this.lastAlive = alive;
    this.deadSent += ArraySize(dying);

    if this.logDelay > 0 {
      this.logDelay -= 1;
    } else {
      this.logDelay = 50; // 5 s
      Cyberblock_Log(s"npcs: sent \(ArraySize(ids) - sentCars) people + \(sentCars) cars; attached \(people) people + \(cars) cars; left out: \(far) beyond 48 m, \(overCap) over_cap, \(CyberblockNpcs.Tally(rejected)); deaths sent flagged dead (bodies and wrecks for Minecraft): \(this.deadSent)");
      this.deadSent = 0;
    }
  }

  // The Reasons that mean it died (or is down) rather than only being out of reach.
  private static func Died(reason: CName) -> Bool {
    return Equals(reason, n"dead") || Equals(reason, n"defeated") || Equals(reason, n"destroyed");
  }

  // n"" when the entity goes to Minecraft: alive, attached, not riding in a vehicle; else why not. Nested ifs:
  // nothing is called on a missing object.
  private static func Reason(game: GameInstance, object: wref<GameObject>) -> CName {
    if !object.IsAttached() {
      return n"detached";
    }
    let vehicle = object as VehicleObject;
    if IsDefined(vehicle) {
      return vehicle.IsDestroyed() ? n"destroyed" : n"";
    }
    let npc = object as NPCPuppet;
    if !IsDefined(npc) {
      return n"not_npc";
    }
    // Our own mob stand-ins (Puppets.reds): a proxy on every mob would draw the mobs' and Steve's attacks.
    if CyberblockPuppets.IsPuppet(game, npc.GetEntityID()) {
      return n"puppet";
    }
    // ScriptedPuppet.IsAlive reads the puppet's own dead flag (IsDeadNoStatPool). Not NPCPuppet.IsDead: it reads the
    // Health stat pool (HasStatPoolValueReachedMin), which says nothing reliable for an NPC whose pool is not set up.
    if !ScriptedPuppet.IsAlive(npc) {
      return n"dead";
    }
    if ScriptedPuppet.IsDefeated(npc) {
      return n"defeated";
    }
    return VehicleComponent.IsMountedToVehicle(game, npc) ? n"mounted" : n"";
  }

  // "detached 2, puppet 40" style counts for the log.
  private static func Tally(reasons: array<CName>) -> String {
    let names: array<CName>;
    let counts: array<Int32>;
    for r in reasons {
      let at = ArrayFindFirst(names, r);
      if at < 0 {
        ArrayPush(names, r);
        ArrayPush(counts, 1);
      } else {
        counts[at] += 1;
      }
    }
    let text = "";
    let k = 0;
    while k < ArraySize(names) {
      text += s"\(NameToString(names[k])) \(counts[k]) ";
      k += 1;
    }
    return ArraySize(names) == 0 ? "nothing else" : text;
  }
}
