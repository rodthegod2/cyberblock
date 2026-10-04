// Role readers (megaplan 2026-10-04 task 7-S1, the scripts part of 7-S1/7-D1/7-A1/7-P1; Phase 5 tags of section 1.9
// amendment item 7; Phase 0 check (f)).
//
//   VendorType  the vendor type name of a vendor puppet (gamedataVendorType: RipperDoc, Clothes, Gunsmith, Medical, ...),
//               n"" for anyone else. Npcs.reds sends it as the npcs `role` tag (unchanged from 5-S1: the wire role is the
//               vendor type only). Fixers, and bartenders, medics and mechanics whose record names no vendor type, are
//               not readable (Phase 0 (f)): Minecraft takes them from `npc_role` and `skin_map` content.
// The vendor V deals with is never guessed here: only the vendor screen Cyberpunk itself opened is listed to Minecraft
// (Vendors.reds, 7-V1, with its pass-through rules).
//
// 2.31 script API (NativeDB, redscript), compile-checked by cp/scripts/compile_check.py: ScriptedPuppet.IsVendor,
// Character_Record.VendorID() -> Vendor_Record.VendorType() -> VendorType_Record.Type() -> gamedataVendorType,
// EnumValueToName(CName, Int64).

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
}
