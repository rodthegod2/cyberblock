// Puppets: invisible Cyberpunk stand-ins for the Minecraft mobs that fight near Steve (plan 2026-10-03 section 1.4,
// S2.2; method and evidence: docs/notes/puppet-spike.md). Every 100 ms tick while live, the latest `mobs` snapshot
// (Cyberblock_MobCount, at most 12) decides which puppets exist: a new mob id spawns one, a known id is teleported to
// the mob's feet and yaw, a missing id is despawned. NPCs and NCPD see a hostile ganger and shoot it; whatever they
// hit is reported to Minecraft as mobdmg and never applied to the puppet. Puppets never deal damage themselves; only
// Cyberblock's own HitFrom hits (a mob hitting an NPC) count. CyberblockPuppets.SetEnabled(false) (CET) is the
// fallback of spec layer 3: no puppets, mob hits are V's.
//
// 2.31 + Codeware 1.20.3 APIs (decompiled 2.31 scripts, Codeware.Global.reds, TweakDB; compile-checked):
//   DynamicEntitySystem.IsReady/CreateEntity/DeleteEntity/DeleteTagged/GetEntity + DynamicEntitySpec, CallbackSystem
//   events Entity/Initialize and Entity/AfterAttach with DynamicEntityTarget.Tag, IComponent.IsA/IsEnabled/Toggle on
//   entIVisualComponent (all render components, including the morph-target head), GodModeSystem.AddGodMode/
//   ClearGodMode (Immortal), AttitudeAgent.SetAttitudeGroup (group hostileToEveryoneExceptPlayer: hostile to all,
//   friendly to the player), TransactionSystem.RemoveAllItems/GetItemInSlot, TeleportationFacility.Teleport,
//   DamageSystem.InvulnerabilityCheck/DealDamages (private final, scripted), AttackData.AddFlag(DealNoDamage)/
//   GetInstigator, gameAttackComputed.GetTotalAttackValue, StatsSystem.GetStatValue(Health) (max health, as
//   Health.reds), EntityID.ToHash (Codeware).
//   Record Character.animals_grunt1_melee1_fists_mb: fists-only Animals grunt, no quest, loot only on death.

public abstract class CyberblockPuppets {
  // 10 Hz. live false: despawn all.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void {
    CyberblockPuppetsService.Get().Tick(game, live);
  }

  // Despawns all (save, load, Off).
  public static func Clear(game: GameInstance) -> Void {
    CyberblockPuppetsService.Get().Clear();
  }

  // Fallback switch, callable from the CET console: CyberblockPuppets.SetEnabled(false).
  public static func SetEnabled(on: Bool) -> Void {
    CyberblockPuppetsService.Get().SetEnabled(on);
  }

  public static func IsEnabled() -> Bool {
    return CyberblockPuppetsService.Get().IsEnabled();
  }

  // The spawned puppet of a mob, or null (none yet, disabled, or not attached).
  public static func Find(game: GameInstance, mob: Int32) -> ref<GameObject> {
    return CyberblockPuppetsService.Get().Find(mob);
  }

  // Whether an entity is one of our puppets (for the NPC list sent to Minecraft, which must leave them out).
  public static func IsPuppet(game: GameInstance, id: EntityID) -> Bool {
    return CyberblockPuppetsService.Get().Slot(id) >= 0;
  }
}

// A Codeware service: the switch outlives save loads. Spawned puppets are never saved (persistSpawn false) and are
// despawned before every save anyway.
public class CyberblockPuppetsService extends ScriptableService {
  private let disabled: Bool;
  // Parallel arrays, one slot per puppet: Minecraft mob id and spawned entity.
  private let mobs: array<Int32>;
  private let ids: array<EntityID>;
  private let ticks: Int32;
  private let initHandler: ref<CallbackSystemHandler>;
  private let attachHandler: ref<CallbackSystemHandler>;
  private let spawnFailLogged: Bool;

  public static func Get() -> ref<CyberblockPuppetsService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockPuppetsService") as CyberblockPuppetsService;
  }

  public static func Tag() -> CName {
    return n"CyberblockPuppet";
  }

  public func SetEnabled(on: Bool) -> Void {
    this.disabled = !on;
    Cyberblock_Log(on ? "puppets: enabled" : "puppets: disabled (mob hits are V's)");
  }

  public func IsEnabled() -> Bool {
    return !this.disabled;
  }

  public func Tick(game: GameInstance, live: Bool) -> Void {
    if !live || this.disabled {
      if ArraySize(this.ids) > 0 {
        this.Clear();
      }
      return;
    }
    let entities = GameInstance.GetDynamicEntitySystem();
    if !IsDefined(entities) || !entities.IsReady() {
      return;
    }
    this.Listen();

    let max = 12;
    let count = Min(Cyberblock_MobCount(), max);
    let wanted: array<Int32>;
    let i = 0;
    while i < count {
      ArrayPush(wanted, Cyberblock_MobId(i));
      i += 1;
    }
    let slot = ArraySize(this.mobs) - 1;
    while slot >= 0 {
      if !ArrayContains(wanted, this.mobs[slot]) {
        this.Despawn(slot);
      }
      slot -= 1;
    }

    this.ticks += 1;
    let recheck = this.ticks % 10 == 0; // every second
    i = 0;
    while i < count {
      let mob = wanted[i];
      slot = this.SlotOfMob(mob);
      if slot < 0 {
        if ArraySize(this.mobs) < max {
          this.Spawn(entities, mob, Cyberblock_MobKind(i), Cyberblock_MobPos(i), Cyberblock_MobYaw(i), Cyberblock_MobSize(i));
        }
      } else {
        let puppet = entities.GetEntity(this.ids[slot]) as ScriptedPuppet;
        if IsDefined(puppet) {
          GameInstance.GetTeleportationFacility(game).Teleport(puppet, Cyberblock_MobPos(i), CyberblockEffects.Facing(Cyberblock_MobYaw(i)));
          if recheck {
            CyberblockPuppetsService.Hide(puppet);
            CyberblockPuppetsService.Disarm(puppet);
          }
        }
      }
      i += 1;
    }
  }

  // Despawns every puppet (also any left from before a reload of this service's state).
  public func Clear() -> Void {
    let entities = GameInstance.GetDynamicEntitySystem();
    if IsDefined(entities) {
      for id in this.ids {
        CyberblockPuppetsService.Forget(id);
      }
      entities.DeleteTagged(CyberblockPuppetsService.Tag());
    }
    if ArraySize(this.ids) > 0 {
      Cyberblock_Log(s"puppets: cleared \(ArraySize(this.ids))");
    }
    ArrayClear(this.mobs);
    ArrayClear(this.ids);
  }

  public func Find(mob: Int32) -> ref<GameObject> {
    let slot = this.SlotOfMob(mob);
    if slot < 0 {
      return null;
    }
    let entities = GameInstance.GetDynamicEntitySystem();
    if !IsDefined(entities) {
      return null;
    }
    let puppet = entities.GetEntity(this.ids[slot]) as GameObject;
    if IsDefined(puppet) && puppet.IsAttached() {
      return puppet;
    }
    return null;
  }

  public func Slot(id: EntityID) -> Int32 {
    let i = 0;
    while i < ArraySize(this.ids) {
      if this.ids[i] == id {
        return i;
      }
      i += 1;
    }
    return -1;
  }

  // InvulnerabilityCheck: a puppet's own attack deals nothing (and so provokes no retaliation either); Cyberblock's
  // HitFrom hits pass.
  public func Guard(hitEvent: ref<gameHitEvent>) -> Void {
    if this.ByPuppet(hitEvent) && !CyberblockEffects.IsOurHit(hitEvent) {
      hitEvent.attackData.AddFlag(hitFlag.DealNoDamage, n"CyberblockPuppet");
    }
  }

  // DealDamages: true for a hit that must not be applied. A hit on a puppet is reported to Minecraft (unless V or a
  // puppet caused it: those are Minecraft's own). A puppet's own attack is refused here too, whatever the order of
  // the InvulnerabilityCheck wraps.
  public func Absorb(hitEvent: ref<gameHitEvent>) -> Bool {
    if ArraySize(this.ids) == 0 || !IsDefined(hitEvent.target) {
      return false;
    }
    let byPuppet = this.ByPuppet(hitEvent);
    let slot = this.Slot(hitEvent.target.GetEntityID());
    if slot < 0 {
      return byPuppet && !CyberblockEffects.IsOurHit(hitEvent);
    }
    let attacker = hitEvent.attackData.GetInstigator();
    if byPuppet || IsDefined(attacker) && attacker.IsPlayer() {
      return true;
    }
    let damage = hitEvent.attackComputed.GetTotalAttackValue(gamedataStatPoolType.Health);
    let maxHealth = GameInstance.GetStatsSystem(hitEvent.target.GetGame()).GetStatValue(Cast<StatsObjectID>(hitEvent.target.GetEntityID()), gamedataStatType.Health);
    if damage > 0.0 && maxHealth > 0.0 {
      let by = IsDefined(attacker) ? EntityID.ToHash(attacker.GetEntityID()) : 0ul;
      Cyberblock_MobDamage(this.mobs[slot], damage / maxHealth, by, IsDefined(attacker));
      Cyberblock_Log(s"puppets: mobdmg mob \(this.mobs[slot]) \(damage / maxHealth) by \(by)");
    }
    return true;
  }

  private func Spawn(entities: ref<DynamicEntitySystem>, mob: Int32, kind: CName, feet: Vector4, yaw: Float, size: Vector4) -> Void {
    let spec = new DynamicEntitySpec();
    spec.recordID = t"Character.animals_grunt1_melee1_fists_mb";
    spec.position = feet;
    spec.orientation = EulerAngles.ToQuat(CyberblockEffects.Facing(yaw));
    spec.persistState = false;
    spec.persistSpawn = false;
    spec.alwaysSpawned = true;
    spec.spawnInView = true;
    spec.active = true;
    spec.tags = [CyberblockPuppetsService.Tag()];
    let id = entities.CreateEntity(spec);
    if !EntityID.IsDefined(id) {
      if !this.spawnFailLogged {
        this.spawnFailLogged = true;
        Cyberblock_Log("puppets: CreateEntity failed, retrying");
      }
      return;
    }
    ArrayPush(this.mobs, mob);
    ArrayPush(this.ids, id);
    // No template takes a size: every puppet has the record's human capsule.
    Cyberblock_Log(s"puppets: spawned for \(NameToString(kind)) \(mob) (\(size.X) x \(size.Y) m)");
  }

  private func Despawn(slot: Int32) -> Void {
    let entities = GameInstance.GetDynamicEntitySystem();
    CyberblockPuppetsService.Forget(this.ids[slot]);
    if IsDefined(entities) {
      entities.DeleteEntity(this.ids[slot]);
    }
    Cyberblock_Log(s"puppets: despawned mob \(this.mobs[slot])");
    ArrayErase(this.mobs, slot);
    ArrayErase(this.ids, slot);
  }

  private func ByPuppet(hitEvent: ref<gameHitEvent>) -> Bool {
    if ArraySize(this.ids) == 0 {
      return false;
    }
    let instigator = hitEvent.attackData.GetInstigator();
    return IsDefined(instigator) && this.Slot(instigator.GetEntityID()) >= 0;
  }

  private func SlotOfMob(mob: Int32) -> Int32 {
    let i = 0;
    while i < ArraySize(this.mobs) {
      if this.mobs[i] == mob {
        return i;
      }
      i += 1;
    }
    return -1;
  }

  private func Listen() -> Void {
    let callbacks = GameInstance.GetCallbackSystem();
    if !IsDefined(this.initHandler) || !this.initHandler.IsRegistered() {
      this.initHandler = callbacks.RegisterCallback(n"Entity/Initialize", this, n"OnPuppetInitialize")
        .AddTarget(DynamicEntityTarget.Tag(CyberblockPuppetsService.Tag()));
    }
    if !IsDefined(this.attachHandler) || !this.attachHandler.IsRegistered() {
      this.attachHandler = callbacks.RegisterCallback(n"Entity/AfterAttach", this, n"OnPuppetAttach")
        .AddTarget(DynamicEntityTarget.Tag(CyberblockPuppetsService.Tag()));
    }
  }

  // As early as possible, so the ganger is never seen.
  private cb func OnPuppetInitialize(event: ref<EntityLifecycleEvent>) {
    let entity = event.GetEntity();
    if IsDefined(entity) {
      CyberblockPuppetsService.Hide(entity);
    }
  }

  private cb func OnPuppetAttach(event: ref<EntityLifecycleEvent>) {
    let puppet = event.GetEntity() as ScriptedPuppet;
    if !IsDefined(puppet) {
      return;
    }
    GameInstance.GetGodModeSystem(puppet.GetGame()).AddGodMode(puppet.GetEntityID(), gameGodModeType.Immortal, CyberblockPuppetsService.Tag());
    let attitude = puppet.GetAttitudeAgent();
    if IsDefined(attitude) {
      attitude.SetAttitudeGroup(n"hostileToEveryoneExceptPlayer");
    }
    GameInstance.GetTransactionSystem(puppet.GetGame()).RemoveAllItems(puppet);
    CyberblockPuppetsService.Hide(puppet);
  }

  // Every render component (skinned and plain meshes, the morph-target head, cloth, decals) is an entIVisualComponent.
  private static func Hide(entity: ref<Entity>) -> Void {
    for component in entity.GetComponents() {
      if component.IsA(n"entIVisualComponent") && component.IsEnabled() {
        component.Toggle(false);
      }
    }
  }

  // Equipment arrives asynchronously after spawn: whatever reaches a hand is taken away with the rest.
  private static func Disarm(puppet: ref<ScriptedPuppet>) -> Void {
    let transactions = GameInstance.GetTransactionSystem(puppet.GetGame());
    if IsDefined(transactions.GetItemInSlot(puppet, t"AttachmentSlots.WeaponRight")) || IsDefined(transactions.GetItemInSlot(puppet, t"AttachmentSlots.WeaponLeft")) {
      transactions.RemoveAllItems(puppet);
    }
  }

  private static func Forget(id: EntityID) -> Void {
    GameInstance.GetGodModeSystem(GetGameInstance()).ClearGodMode(id, CyberblockPuppetsService.Tag());
  }
}

@wrapMethod(DamageSystem)
private final func InvulnerabilityCheck(hitEvent: ref<gameHitEvent>, cache: ref<CacheData>) -> Void {
  wrappedMethod(hitEvent, cache);
  CyberblockPuppetsService.Get().Guard(hitEvent);
}

// Hits on puppets go to Minecraft only; every other hit is left to the other wraps and the game.
@wrapMethod(DamageSystem)
private final func DealDamages(hitEvent: ref<gameHitEvent>) -> Void {
  if CyberblockPuppetsService.Get().Absorb(hitEvent) {
    return;
  }
  wrappedMethod(hitEvent);
}
