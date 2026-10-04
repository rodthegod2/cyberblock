// Role readers (megaplan 2026-10-04 task 7-S1, the scripts part of 7-S1/7-D1/7-A1/7-P1; Phase 5 tags of section 1.9
// amendment item 7; Phase 0 check (f)).
//
// One reader for every place that asks who a Night City person is:
//   VendorType  the vendor type name of a vendor puppet (gamedataVendorType: RipperDoc, Clothes, Gunsmith, Medical, ...),
//               n"" for anyone else. Npcs.reds sends it as the npcs `role` tag (unchanged from 5-S1: the wire role is the
//               vendor type only).
//   ScreenRole  the Phase 7 screen a vendor type stands for: n"ripperdoc", n"bartender", n"medic", n"mechanic" or
//               n"vendor" (any other vendor), n"" for none. Matched on the type name's text (case-insensitive: "ripper",
//               "drink"/"bar", "medic"/"pharm", "mechanic"), so no enum member name is guessed. Fixers, and bartenders,
//               medics and mechanics whose record names no such vendor type, are not readable (Phase 0 (f)): Minecraft
//               takes them from `npc_role` and `skin_map` content. The vendor reply (VendorStock.reds) tells Minecraft
//               the screen role of the vendor it lists; each vendor type is logged once with its role:
//               'roles: vendor type <type> -> <role>'.
//   IsVendor    a GameObject Night City trades with: MarketSystem.GetVendorID is a valid record (vendor NPCs, vending
//               machines, drop points).
//   Vendor      the vendor V is dealing with when nobody named one (VendorStock.reds): the object V looks at when it is a
//               vendor, else the nearest vendor person within `radius` of V (the attached-NPC registry of Tether.reds).
//
// 2.31 script API (NativeDB, redscript), compile-checked by cp/scripts/compile_check.py: ScriptedPuppet.IsVendor/
// GetRecord, Character_Record.VendorID() -> Vendor_Record.VendorType() -> VendorType_Record.Type() -> gamedataVendorType,
// EnumValueToName(CName, Int64), MarketSystem.GetVendorID(GameObject) -> TweakDBID, TDBID.IsValid,
// TargetingSystem.GetLookAtObject(GameObject, Bool, Bool), GameObject.GetWorldPosition, Vector4.DistanceSquared.

public class CyberblockRolesService extends ScriptableService {
  // Vendor types already logged with their screen role (at most 64).
  public let logged: array<CName>;

  public static func Get() -> ref<CyberblockRolesService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockRolesService") as CyberblockRolesService;
  }
}

public abstract class CyberblockRoles {
  // The vendor type name (RipperDoc, Clothes, ...) for a vendor whose record names one, else n"".
  public static func VendorType(puppet: ref<ScriptedPuppet>, record: wref<Character_Record>) -> CName {
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

  // The vendor type of any object (a puppet's record; n"" for vending machines and everyone else).
  public static func VendorTypeOf(object: ref<GameObject>) -> CName {
    let puppet = object as ScriptedPuppet;
    if IsDefined(puppet) {
      return CyberblockRoles.VendorType(puppet, puppet.GetRecord());
    }
    return n"";
  }

  // See the header. Logged once per vendor type.
  public static func ScreenRole(vendorType: CName) -> CName {
    if !IsNameValid(vendorType) {
      return n"";
    }
    let text = StrLower(NameToString(vendorType));
    let role = n"vendor";
    if StrContains(text, "ripper") {
      role = n"ripperdoc";
    } else {
      if StrContains(text, "drink") || StrContains(text, "bar") {
        role = n"bartender";
      } else {
        if StrContains(text, "medic") || StrContains(text, "pharm") {
          role = n"medic";
        } else {
          if StrContains(text, "mechanic") {
            role = n"mechanic";
          }
        }
      }
    }
    let s = CyberblockRolesService.Get();
    if IsDefined(s) && !ArrayContains(s.logged, vendorType) && ArraySize(s.logged) < 64 {
      ArrayPush(s.logged, vendorType);
      Cyberblock_Log(s"roles: vendor type \(NameToString(vendorType)) -> \(NameToString(role))");
    }
    return role;
  }

  public static func IsVendor(object: ref<GameObject>) -> Bool {
    if !IsDefined(object) {
      return false;
    }
    return TDBID.IsValid(MarketSystem.GetVendorID(object));
  }

  // See the header; null when there is none.
  public static func Vendor(game: GameInstance, player: ref<PlayerPuppet>, radius: Float) -> ref<GameObject> {
    if !IsDefined(player) {
      return null;
    }
    let look = GameInstance.GetTargetingSystem(game).GetLookAtObject(player, false, false);
    if CyberblockRoles.IsVendor(look) {
      return look;
    }
    let tether = CyberblockTetherService.Get();
    if !IsDefined(tether) {
      return null;
    }
    let center = player.GetWorldPosition();
    let best: ref<GameObject>;
    let bestDistance2 = radius * radius;
    for object in tether.Known() {
      if IsDefined(object) && IsDefined(object as ScriptedPuppet) {
        let d2 = Vector4.DistanceSquared(object.GetWorldPosition(), center);
        if d2 <= bestDistance2 && CyberblockRoles.IsVendor(object) {
          best = object;
          bestDistance2 = d2;
        }
      }
    }
    return best;
  }
}
