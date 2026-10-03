// Entity id hashes on the wire (plan 2026-10-03-retest2 sections 1.1 and 1.5, task S1).
//
// Codeware declares EntityID.ToHash(id: script_ref<EntityID>) -> Uint64 (Codeware.Global.reds 42199-42201): the native
// reads the id through a reference. Handed a call result, EntityID.ToHash(object.GetEntityID()), it hashed garbage in
// game: in the re-test of 2026-10-03 (Cyberblock.log 15:09-15:13) scans, kills, puppet damage and withheld pushes sent
// heap-address-like ids (the push withheld from 2668059858640 right after the hit on 10019101, the same NPC; "killed
// human 0"), while the NPC list, which hashed a local (Npcs.reds), sent real ones. Minecraft locked those ids for its
// hacks, and FindEntityByID(EntityID.FromHash(...)) found nothing: "target gone".
// Rule: never pass a call result to EntityID.ToHash; use CyberblockIds.Hash, which copies the id to a local first
// (tests/test_reds_ids.py enforces it).

public abstract class CyberblockIds {
  // The entity's id hash (Codeware EntityID.FromHash is the inverse); 0ul when the entity is not defined.
  public static func Hash(entity: ref<Entity>) -> Uint64 {
    if !IsDefined(entity) {
      return 0ul;
    }
    let id = entity.GetEntityID();
    return EntityID.ToHash(id);
  }
}
