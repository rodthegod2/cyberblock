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
//
// Tags (megaplan 2026-10-04 task 5-S1, section 1.8 amendment item 7, 1.9 amendment item 7; Phase 0 (f)): the list goes
// to Cyberblock_SubmitNpcs2, the six arrays above plus eight parallel ones, one entry per list entry. The old
// Cyberblock_SubmitNpcs (Natives.reds) stays declared and implemented but is no longer called.
//   roles      the vendor type name (RipperDoc, Clothes, Gunsmith, ...) when ScriptedPuppet.IsVendor, else n"". Fixer,
//              bartender, medic and mechanic are not readable; Minecraft takes them from npc_role / skin_map content.
//   factions   Character_Record.Affiliation().EnumName() (n"" when the record has none).
//   genders    from gamePuppet.GetResolvedGenderName(): a name containing "female" -> 2, "male" -> 1, else 0
//              (case-insensitive; "female" is tested first). The names seen are logged once for the user's test:
//              'npcs: gender names seen <a>, <b>'.
//   archetypes Character_Record.ArchetypeData().Type().EnumName() (n"" when missing).
//   anims      1.9 npcs bits from the puppet-state blackboard (ScriptedPuppet.GetPuppetStateBlackboard): 4 aim and
//              8 fire from PuppetState.UpperBody (gamedataNPCUpperBodyState Aim / Shoot), 32 dead and 128 combat from
//              GetHighLevelStateFromBlackboard (Dead / Combat), 64 crouch from GetStanceStateFromBlackboard (Crouch),
//              16 ragdoll from NPCPuppet.IsRagdolling; an entry sent flagged dead always has 32. Bits 1 (walk) and 2
//              (run) are always 0: the DLL derives them from position deltas. Vehicles send 0.
//   vclass     "<type>/<model>" from Vehicle_Record.Type().Type() (gamedataVehicleType: Car, Bike, ...) and
//              Model().Type() (gamedataVehicleModel: Turbo, Type66, ...), at most 32 characters; n"" for people.
//   colours    vehicles: the VehicleColorTemplate_Record of Vehicle_Record.AppearancesToColorTemplate().ColorTemplates()
//              whose MatchingAppearances holds Entity.GetCurrentAppearanceName(); its MainColor's three raw numbers
//              (a, b, c; HSB 0..255 each by the desk guess, CET F2 not run) packed as the DLL's colour_hex reads them
//              (world.hpp, package 5D): a << 16 | b << 8 | c, each clamped to 0..255. -1 when no template matches, for
//              people and when MainColor is not three numbers. The DLL decodes the format (it logs 'npcs: colour
//              format hsb (unconfirmed)').
//   hp         StatPoolsSystem Health pool, percent / 100, 0..1; 1 when the pool is not added; 0 for the dead.
// CET F1/F2 were not run, so every reader is feature-checked at runtime: a missing record, blackboard or template
// falls back per (f) (faction only, gender 0, archetype n"", anims without the blackboard bits, colour -1). The path in
// use is logged once and again when it changes, at the 5 s log: 'npcs: tags full|faction only', 'npcs: anims from
// blackboard|ragdoll and dead only', 'npcs: vehicle colours from colour templates|default'.
//
// 2.31 script API (NativeDB, redscript), compile-checked by cp/scripts/compile_check.py: ScriptedPuppet.GetRecord() ->
// Character_Record (Affiliation, ArchetypeData, VendorID), Vendor_Record.VendorType() -> VendorType_Record.Type() ->
// gamedataVendorType, ArchetypeData_Record.Type() -> ArchetypeType_Record.EnumName(), Affiliation_Record.EnumName(),
// gamePuppet.GetResolvedGenderName() -> CName, ScriptedPuppet.IsVendor(), GetPuppetStateBlackboard(),
// GetHighLevelStateFromBlackboard(), GetStanceStateFromBlackboard(), GetAllBlackboardDefs().PuppetState.UpperBody,
// NPCPuppet.IsRagdolling(), VehicleObject.GetRecord() -> Vehicle_Record (Type, Model, AppearancesToColorTemplate),
// VehicleColorTemplate_Record.MatchingAppearances() / MainColor(), Entity.GetCurrentAppearanceName(),
// EnumValueToName(CName, Int64), StatPoolsSystem.IsStatPoolAdded / GetStatPoolValue(id, Health, true).

// Declared here (rule 0.14), implemented by Cyberblock.dll (natives.cpp, world.cpp; package 5D). Every array has the
// length of ids; the first six are those of Cyberblock_SubmitNpcs, in the same order.
public native func Cyberblock_SubmitNpcs2(ids: array<Uint64>, feet: array<Vector4>, yaws: array<Float>, vehicles: array<Bool>, hostile: array<Bool>, dead: array<Bool>, roles: array<CName>, factions: array<CName>, genders: array<Int32>, archetypes: array<CName>, anims: array<Int32>, vclass: array<CName>, colours: array<Int32>, hp: array<Float>) -> Void;

public class CyberblockNpcs {
  private let sent: Bool;
  private let logDelay: Int32;
  // The hashes the last list sent alive, and how many were sent flagged dead since the last log line.
  private let lastAlive: array<Uint64>;
  private let deadSent: Int32;
  // Tag paths for the 5 s log: counts since the last log line, and the paths last logged ("" none yet).
  private let tagPeople: Int32;
  private let tagFull: Int32;
  private let tagBoard: Int32;
  private let tagCars: Int32;
  private let tagColoured: Int32;
  private let tagPath: String;
  private let animPath: String;
  private let colourPath: String;
  // Distinct resolved gender names, logged once; how many active ticks have passed while waiting for a second one.
  private let genderNames: array<CName>;
  private let genderLogged: Bool;
  private let genderWait: Int32;

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
    let roles: array<CName>;
    let factions: array<CName>;
    let genders: array<Int32>;
    let archetypes: array<CName>;
    let anims: array<Int32>;
    let vclass: array<CName>;
    let colours: array<Int32>;
    let hp: array<Float>;
    if !active {
      if this.sent {
        Cyberblock_SubmitNpcs2(ids, feet, yaws, vehicles, hostile, dead, roles, factions, genders, archetypes, anims, vclass, colours, hp);
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
      ArrayPush(hp, isDead ? 0.0 : CyberblockNpcs.Hp(game, object));
      if IsDefined(vehicle) {
        sentCars += 1;
        ArrayPush(hostile, IsHostileTowardsPlayer(VehicleComponent.GetDriverMounted(game, object.GetEntityID())));
        ArrayPush(roles, n"");
        ArrayPush(factions, n"");
        ArrayPush(genders, 0);
        ArrayPush(archetypes, n"");
        ArrayPush(anims, 0);
        ArrayPush(vclass, CyberblockNpcs.VehicleClass(vehicle));
        let colour = CyberblockNpcs.Colour(vehicle);
        ArrayPush(colours, colour);
        this.tagCars += 1;
        if colour >= 0 {
          this.tagColoured += 1;
        }
      } else {
        ArrayPush(hostile, IsHostileTowardsPlayer(object));
        // Nested ifs throughout the readers: nothing is called on a missing puppet, record or blackboard.
        let puppet = object as ScriptedPuppet;
        let record: wref<Character_Record>;
        let genderName = n"";
        let board = false;
        if IsDefined(puppet) {
          record = puppet.GetRecord();
          genderName = puppet.GetResolvedGenderName();
          board = IsDefined(puppet.GetPuppetStateBlackboard());
        }
        let archetype = CyberblockNpcs.Archetype(record);
        this.NoteGender(genderName);
        ArrayPush(roles, CyberblockNpcs.Role(puppet, record));
        ArrayPush(factions, CyberblockNpcs.Faction(record));
        ArrayPush(genders, CyberblockNpcs.Gender(genderName));
        ArrayPush(archetypes, archetype);
        ArrayPush(anims, CyberblockNpcs.Anims(puppet, board, isDead));
        ArrayPush(vclass, n"");
        ArrayPush(colours, -1);
        this.tagPeople += 1;
        if IsNameValid(genderName) && IsNameValid(archetype) {
          this.tagFull += 1;
        }
        if board {
          this.tagBoard += 1;
        }
      }
      k += 1;
    }
    Cyberblock_SubmitNpcs2(ids, feet, yaws, vehicles, hostile, dead, roles, factions, genders, archetypes, anims, vclass, colours, hp);
    this.sent = true;
    this.lastAlive = alive;
    this.deadSent += ArraySize(dying);

    if this.logDelay > 0 {
      this.logDelay -= 1;
    } else {
      this.logDelay = 50; // 5 s
      Cyberblock_Log(s"npcs: sent \(ArraySize(ids) - sentCars) people + \(sentCars) cars; attached \(people) people + \(cars) cars; left out: \(far) beyond 48 m, \(overCap) over_cap, \(CyberblockNpcs.Tally(rejected)); deaths sent flagged dead (bodies and wrecks for Minecraft): \(this.deadSent)");
      this.deadSent = 0;
      this.LogTags();
    }
  }

  // The tag paths in use, each logged once and again when it changes; then the gender names seen, once.
  private func LogTags() -> Void {
    if this.tagPeople > 0 {
      let path = this.tagFull > 0 ? "full" : "faction only";
      if NotEquals(path, this.tagPath) {
        this.tagPath = path;
        Cyberblock_Log(s"npcs: tags \(path) (\(this.tagFull) of \(this.tagPeople) people entries with gender and archetype)");
      }
      let anim = this.tagBoard > 0 ? "blackboard" : "ragdoll and dead only";
      if NotEquals(anim, this.animPath) {
        this.animPath = anim;
        Cyberblock_Log(s"npcs: anims from \(anim) (\(this.tagBoard) of \(this.tagPeople) people entries had a puppet-state blackboard)");
      }
    }
    if this.tagCars > 0 {
      let colour = this.tagColoured > 0 ? "colour templates" : "default";
      if NotEquals(colour, this.colourPath) {
        this.colourPath = colour;
        Cyberblock_Log(s"npcs: vehicle colours from \(colour) (\(this.tagColoured) of \(this.tagCars) car entries matched a template)");
      }
    }
    this.tagPeople = 0;
    this.tagFull = 0;
    this.tagBoard = 0;
    this.tagCars = 0;
    this.tagColoured = 0;

    // Once two names are seen, or after 60 s with one: the user's test checks the names behind genders.
    this.genderWait += 1;
    if !this.genderLogged && (ArraySize(this.genderNames) >= 2 || (this.genderWait >= 12 && ArraySize(this.genderNames) > 0)) {
      this.genderLogged = true;
      let text = "";
      for name in this.genderNames {
        text += StrLen(text) == 0 ? NameToString(name) : s", \(NameToString(name))";
      }
      Cyberblock_Log(s"npcs: gender names seen \(text)");
    }
  }

  // Remembers each distinct resolved gender name (at most 8) until they are logged.
  private func NoteGender(name: CName) -> Void {
    if !this.genderLogged && IsNameValid(name) && !ArrayContains(this.genderNames, name) && ArraySize(this.genderNames) < 8 {
      ArrayPush(this.genderNames, name);
    }
  }

  // 2 for a name containing "female", 1 for "male", else 0 (case-insensitive, "female" first).
  private static func Gender(name: CName) -> Int32 {
    if !IsNameValid(name) {
      return 0;
    }
    let text = StrLower(NameToString(name));
    if StrContains(text, "female") {
      return 2;
    }
    return StrContains(text, "male") ? 1 : 0;
  }

  // The vendor type name (RipperDoc, Clothes, ...) for a vendor whose record names one, else n"".
  private static func Role(puppet: ref<ScriptedPuppet>, record: wref<Character_Record>) -> CName {
    if IsDefined(puppet) && IsDefined(record) {
      if puppet.IsVendor() {
        let vendor = record.VendorID();
        if IsDefined(vendor) {
          let type = vendor.VendorType();
          if IsDefined(type) {
            return EnumValueToName(n"gamedataVendorType", Cast<Int64>(EnumInt(type.Type())));
          }
        }
      }
    }
    return n"";
  }

  private static func Faction(record: wref<Character_Record>) -> CName {
    if IsDefined(record) {
      let affiliation = record.Affiliation();
      if IsDefined(affiliation) {
        return affiliation.EnumName();
      }
    }
    return n"";
  }

  private static func Archetype(record: wref<Character_Record>) -> CName {
    if IsDefined(record) {
      let data = record.ArchetypeData();
      if IsDefined(data) {
        let type = data.Type();
        if IsDefined(type) {
          return type.EnumName();
        }
      }
    }
    return n"";
  }

  // 1.9 npcs anim bits; bits 1 and 2 (walk, run) are the DLL's. board: the puppet's state blackboard exists.
  private static func Anims(puppet: ref<ScriptedPuppet>, board: Bool, isDead: Bool) -> Int32 {
    let bits = isDead ? 32 : 0;
    let npc = puppet as NPCPuppet;
    if IsDefined(npc) {
      if npc.IsRagdolling() {
        bits += 16;
      }
    }
    if !board {
      return bits;
    }
    let upper = IntEnum<gamedataNPCUpperBodyState>(puppet.GetPuppetStateBlackboard().GetInt(GetAllBlackboardDefs().PuppetState.UpperBody));
    if Equals(upper, gamedataNPCUpperBodyState.Aim) {
      bits += 4;
    }
    if Equals(upper, gamedataNPCUpperBodyState.Shoot) {
      bits += 8;
    }
    let high = puppet.GetHighLevelStateFromBlackboard();
    if Equals(high, gamedataNPCHighLevelState.Dead) && !isDead {
      bits += 32;
    }
    if Equals(high, gamedataNPCHighLevelState.Combat) {
      bits += 128;
    }
    if Equals(puppet.GetStanceStateFromBlackboard(), gamedataNPCStanceState.Crouch) {
      bits += 64;
    }
    return bits;
  }

  // "<type>/<model>" (Car/Turbo), at most 32 characters; n"" without a record.
  private static func VehicleClass(vehicle: ref<VehicleObject>) -> CName {
    let record = vehicle.GetRecord();
    if IsDefined(record) {
      let type = record.Type();
      let model = record.Model();
      if IsDefined(type) && IsDefined(model) {
        let typeName = EnumValueToName(n"gamedataVehicleType", Cast<Int64>(EnumInt(type.Type())));
        let modelName = EnumValueToName(n"gamedataVehicleModel", Cast<Int64>(EnumInt(model.Type())));
        return StringToName(StrLeft(s"\(NameToString(typeName))/\(NameToString(modelName))", 32));
      }
    }
    return n"";
  }

  // The current appearance's colour template MainColor, three numbers packed 8 bits each; -1 when none matches.
  private static func Colour(vehicle: ref<VehicleObject>) -> Int32 {
    let record = vehicle.GetRecord();
    if !IsDefined(record) {
      return -1;
    }
    let map = record.AppearancesToColorTemplate();
    if !IsDefined(map) {
      return -1;
    }
    let look = vehicle.GetCurrentAppearanceName();
    let templates: array<wref<VehicleColorTemplate_Record>>;
    map.ColorTemplates(templates);
    for template in templates {
      if IsDefined(template) {
        if ArrayContains(template.MatchingAppearances(), look) {
          let main = template.MainColor();
          if ArraySize(main) != 3 {
            return -1;
          }
          return Clamp(main[0], 0, 255) * 65536 + Clamp(main[1], 0, 255) * 256 + Clamp(main[2], 0, 255);
        }
      }
    }
    return -1;
  }

  // Fraction 0..1 of the Health pool; 1 for objects without one.
  private static func Hp(game: GameInstance, object: ref<GameObject>) -> Float {
    let id = Cast<StatsObjectID>(object.GetEntityID());
    let pools = GameInstance.GetStatPoolsSystem(game);
    if !pools.IsStatPoolAdded(id, gamedataStatPoolType.Health) {
      return 1.0;
    }
    return ClampF(pools.GetStatPoolValue(id, gamedataStatPoolType.Health, true) / 100.0, 0.0, 1.0);
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
