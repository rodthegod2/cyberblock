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
// (the native name of VehicleObject, RED4ext.SDK vehicle/BaseObject.hpp). Handlers registered from a non-service
// object live for the session (CallbackLifetime::Session) and hold the object weakly.
//
// Left out: dead, defeated or destroyed ones (ScriptedPuppet.IsAlive + ScriptedPuppet.IsDefeated, the Defeated and
// DefeatedWithRecover status effect types; VehicleObject.IsDestroyed), NPCs mounted in a vehicle (their vehicle's
// proxy stands for them, VehicleComponent.IsMountedToVehicle) and Cyberblock's own mob puppets
// (CyberblockPuppets.IsPuppet). Every 5 s the log counts what was sent and attached, people and cars apart, and why
// the rest was left out (beyond 48 m, over the cap of 48, or the Reason).
//
// Yaw: degrees counter-clockwise from +Y about +Z, from Entity.GetWorldForward (0 = facing +Y, 90 = facing -X), the
// EulerAngles yaw convention the DLL's cp_yaw_to_mc_yaw (180 - yaw) expects. AtanF(a, b) is atan2(a, b) (as used in
// timeSkipPopup: AtanF(dy, dx)).
// Hostile: GameObject attitude towards V is AIA_Hostile (IsHostileTowardsPlayer); a vehicle is hostile when its
// driver is (VehicleComponent.GetDriverMounted).
// EntityID hash: Codeware EntityID.ToHash (the full 64-bit id; EntityID.FromHash is the inverse).

public class CyberblockNpcs {
  private let known: array<wref<GameObject>>;
  private let sent: Bool;
  private let logDelay: Int32;

  public func Register() -> Void {
    let callbacks = GameInstance.GetCallbackSystem();
    callbacks.RegisterCallback(n"Entity/AfterAttach", this, n"OnEntityAttach")
      .AddTarget(EntityTarget.Type(n"NPCPuppet"))
      .AddTarget(EntityTarget.Type(n"vehicleBaseObject"));
    callbacks.RegisterCallback(n"Entity/Detach", this, n"OnEntityDetach")
      .AddTarget(EntityTarget.Type(n"NPCPuppet"))
      .AddTarget(EntityTarget.Type(n"vehicleBaseObject"));
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

  // Every tick. active: state is Active (on foot). Leaving Active sends one empty list so every proxy goes.
  public func Tick(player: ref<PlayerPuppet>, active: Bool) -> Void {
    let ids: array<Uint64>;
    let feet: array<Vector4>;
    let yaws: array<Float>;
    let vehicles: array<Bool>;
    let hostile: array<Bool>;
    if !active {
      if this.sent {
        Cyberblock_SubmitNpcs(ids, feet, yaws, vehicles, hostile);
        this.sent = false;
      }
      return;
    }

    let game = player.GetGame();
    let center = player.GetWorldPosition();
    let maxCount = 48;
    let maxDistance2 = 48.0 * 48.0;
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
    let i = ArraySize(this.known) - 1;
    while i >= 0 {
      let object = this.known[i];
      if !IsDefined(object) {
        ArrayErase(this.known, i);
      } else {
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
      i -= 1;
    }

    let sentCars = 0;
    for object in picked {
      let id = object.GetEntityID();
      let forward = object.GetWorldForward();
      let vehicle = object as VehicleObject;
      ArrayPush(ids, EntityID.ToHash(id));
      ArrayPush(feet, object.GetWorldPosition());
      ArrayPush(yaws, Rad2Deg(AtanF(-forward.X, forward.Y)));
      ArrayPush(vehicles, IsDefined(vehicle));
      if IsDefined(vehicle) {
        sentCars += 1;
        ArrayPush(hostile, IsHostileTowardsPlayer(VehicleComponent.GetDriverMounted(game, id)));
      } else {
        ArrayPush(hostile, IsHostileTowardsPlayer(object));
      }
    }
    Cyberblock_SubmitNpcs(ids, feet, yaws, vehicles, hostile);
    this.sent = true;

    if this.logDelay > 0 {
      this.logDelay -= 1;
    } else {
      this.logDelay = 50; // 5 s
      Cyberblock_Log(s"npcs: sent \(ArraySize(ids) - sentCars) people + \(sentCars) cars; attached \(people) people + \(cars) cars; left out: \(far) beyond 48 m, \(overCap) over_cap, \(CyberblockNpcs.Tally(rejected))");
    }
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
