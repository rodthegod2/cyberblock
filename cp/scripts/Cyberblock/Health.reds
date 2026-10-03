// Steve's health is the only health. While Active, V is Invulnerable through our god mode (source n"Cyberblock",
// PlayerState.reds), yet every hit the game aims at V still runs through the damage pipeline to its would-be damage,
// which goes to Minecraft (Cyberblock_Damage -> dmg {frac, kind, from}) instead of to V.
//
// 2.31 damage pipeline (decompiled scripts, DamageSystem): ProcessPipeline -> PreProcess -> InvulnerabilityCheck adds
// hitFlag.DealNoDamage (source n"invulnerable") for an Invulnerable target, and Process returns early on that flag, so
// without help the damage is never computed. Two wraps:
//   1. InvulnerabilityCheck: when our god mode is V's only Invulnerable source (and V's IsInvulnerable stat is 0),
//      the flag this check just added is removed again, so Process computes the damage exactly as without god mode
//      (armor, difficulty scaling, one-shot protection, fixed-percentage explosions, vehicle hits...).
//   2. DealDamages: for those hits the computed Health damage is reported and not applied to V.
// Everything else stays vanilla: dodge/evasion/friendly-fire/self-damage hits keep their DealNoDamage and are not
// reported, other god modes (quests, scenes) keep V invulnerable and Steve unharmed, status-effect immunity still
// checks the god mode directly (IsImmune), and so does the player's hit reaction (ReactToHitProcess returns for an
// Invulnerable player). Falls: hard/very hard/death landings start the AoE attacks Attacks.HardLanding /
// VeryHardLanding / DeathLanding with V as instigator and source (locomotionTransitions, PrepareGameEffectAoEAttack);
// there is no other scripted fall damage, and r6/cache/tweakdb.bin gives DeathLanding a +10000 physical damage
// modifier (Attacks.DeathLanding_inline0), so these attacks are what hurts V on landing. When one damages V it
// arrives here and is sent as fall. A fall is not sent while CyberblockFlight.SuppressFall (S2): during a glide and
// for 2 s after its landing or an ender pearl teleport, Minecraft applies its own fall rules.
// Damage over time (burning, poison, shock) also reaches Steve: the IsImmune wrap below lets DoT debuffs land on V
// while only our god mode protects V, and their ticks come through DealDamages like any hit.
//
// APIs: DamageSystem.InvulnerabilityCheck(ref<gameHitEvent>, ref<CacheData>) and DealDamages(ref<gameHitEvent>)
// (private final, scripted), AttackData.HasFlag/RemoveFlag(hitFlag, CName)/GetAttackType/GetSource/GetInstigator/
// GetAttackDefinition, IAttack.GetRecord() -> wref<Attack_Record>, gameAttackComputed.GetTotalAttackValue(
// gamedataStatPoolType.Health)/GetDominatingDamageType, GodModeSystem.GetGodModeSources(EntityID, gameGodModeType)
// -> array<CName>, StatsSystem.GetStatValue(StatsObjectID, gamedataStatType.Health | IsInvulnerable) (max health as in
// DamageSystem.ProcessOneShotProtection), gameHitEvent.hitDirection, Vector4.IsZero/Normalize,
// DamageSystem.IsImmune(ref<GameObject>, TweakDBID, ref<AttackData>) -> Bool (private final, scripted),
// TweakDBInterface.GetStatusEffectRecord, StatusEffect_Record.GameplayTagsContains/ImmunityStats.

// Removes only the flag the god-mode branch adds (AddFlag never duplicates a flag, so with no DealNoDamage before the
// call, a DealNoDamage with source n"invulnerable" afterwards is that branch's). RemoveFlag(flag, source) is a no-op
// when it is absent, unlike RemoveFlag(flag), which erases index 0 of an empty flag list.
@wrapMethod(DamageSystem)
private final func InvulnerabilityCheck(hitEvent: ref<gameHitEvent>, cache: ref<CacheData>) -> Void {
  let hadNoDamage = hitEvent.attackData.HasFlag(hitFlag.DealNoDamage);
  wrappedMethod(hitEvent, cache);
  if !hadNoDamage && CyberblockHealth.Covers(hitEvent.target) && !CyberblockHealth.StatInvulnerable(hitEvent.target) {
    hitEvent.attackData.RemoveFlag(hitFlag.DealNoDamage, n"invulnerable");
  }
}

@wrapMethod(DamageSystem)
private final func DealDamages(hitEvent: ref<gameHitEvent>) -> Void {
  if CyberblockHealth.Covers(hitEvent.target) {
    CyberblockHealth.Report(hitEvent);
    return;
  }
  wrappedMethod(hitEvent);
}

// Audit D gap 1 (docs/notes/cp-mechanics-audit.md appendix, verbatim but for its OnlyCyberblock test, which is
// CyberblockHealth.Covers): let damage-over-time debuffs land on V while only Cyberblock's god mode protects V, so
// their ticks reach Steve through the report above. Every other immunity (DoNotApplyOnPlayer, immunity stats, other
// god modes) stays. 2.31: DamageSystem.IsImmune (damageSystem.swift:1526) returns true for any Debuff-tagged effect on
// an Invulnerable target (:1561-1565); every DoT record is tagged Debuff and DoT (TweakDB). The ticks are attacks
// with AttackType.Effect (e.g. BaseStatusEffect.PlayerBurning -> TriggerContinuousAttackEffector ->
// Attacks.LightThermalDamageOverTime, damage type Thermal), which Kind reports as fire.
@wrapMethod(DamageSystem)
private final func IsImmune(target: ref<GameObject>, statusEffectID: TweakDBID, attackData: ref<AttackData>) -> Bool {
  let immune = wrappedMethod(target, statusEffectID, attackData);
  if !immune || !target.IsPlayer() || !CyberblockHealth.Covers(target) {
    return immune;
  }
  let record = TweakDBInterface.GetStatusEffectRecord(statusEffectID);
  if !IsDefined(record) || !record.GameplayTagsContains(n"DoT") || record.GameplayTagsContains(n"DoNotApplyOnPlayer") {
    return immune;
  }
  let stats: array<wref<Stat_Record>>;
  record.ImmunityStats(stats);
  for stat in stats {
    if GameInstance.GetStatsSystem(target.GetGame()).GetStatValue(Cast<StatsObjectID>(target.GetEntityID()), stat.StatType()) > 0.0 {
      return immune;
    }
  }
  return false;
}

public abstract class CyberblockHealth {
  // True while V's invulnerability comes from Cyberblock alone, i.e. Cyberblock is Active and nothing else in the game
  // made V invulnerable.
  public static func Covers(target: wref<GameObject>) -> Bool {
    let player = target as PlayerPuppet;
    if !IsDefined(player) {
      return false;
    }
    let sources = GameInstance.GetGodModeSystem(player.GetGame()).GetGodModeSources(player.GetEntityID(), gameGodModeType.Invulnerable);
    if ArraySize(sources) == 0 {
      return false;
    }
    for source in sources {
      if NotEquals(source, n"Cyberblock") {
        return false;
      }
    }
    return true;
  }

  public static func StatInvulnerable(target: wref<GameObject>) -> Bool {
    return GameInstance.GetStatsSystem(target.GetGame()).GetStatValue(Cast<StatsObjectID>(target.GetEntityID()), gamedataStatType.IsInvulnerable) > 0.0;
  }

  public static func Report(hitEvent: ref<gameHitEvent>) -> Void {
    let target = hitEvent.target;
    let damage = hitEvent.attackComputed.GetTotalAttackValue(gamedataStatPoolType.Health);
    let maxHealth = GameInstance.GetStatsSystem(target.GetGame()).GetStatValue(Cast<StatsObjectID>(target.GetEntityID()), gamedataStatType.Health);
    if damage <= 0.0 || maxHealth <= 0.0 {
      return;
    }
    let from: Vector4;
    let hasFrom = CyberblockHealth.From(hitEvent, from);
    let kind = CyberblockHealth.Kind(hitEvent);
    // Minecraft owns the fall damage of a glide or an ender pearl teleport (plan 2026-10-03 S1.3, CyberblockFlight).
    if Equals(kind, n"fall") && CyberblockFlight.SuppressFall(target.GetGame()) {
      Cyberblock_Log(s"dmg fall \(damage) of \(maxHealth) not reported: flight or teleport landing");
      return;
    }
    Cyberblock_Damage(damage / maxHealth, kind, from, hasFrom);
    Cyberblock_Log(s"dmg \(NameToString(kind)) \(damage) of \(maxHealth) from \(hasFrom ? "known" : "unknown") (\(TDBID.ToStringDEBUG(CyberblockHealth.AttackID(hitEvent))))");
  }

  // Where the hit came from: the attack's source (grenade, vehicle, shooter), else its instigator, unless that is V;
  // else 1 m back along the hit direction (a car hitting V reports V as instigator and source).
  private static func From(hitEvent: ref<gameHitEvent>, out from: Vector4) -> Bool {
    let target = hitEvent.target;
    let attacker = hitEvent.attackData.GetSource();
    if !IsDefined(attacker) || attacker == target {
      attacker = hitEvent.attackData.GetInstigator();
    }
    if IsDefined(attacker) && attacker != target {
      from = attacker.GetWorldPosition();
      return true;
    }
    if !Vector4.IsZero(hitEvent.hitDirection) {
      from = target.GetWorldPosition() - Vector4.Normalize(hitEvent.hitDirection);
      return true;
    }
    return false;
  }

  private static func Kind(hitEvent: ref<gameHitEvent>) -> CName {
    let attack = hitEvent.attackData;
    let id = CyberblockHealth.AttackID(hitEvent);
    if IsDefined(hitEvent as gameVehicleHitEvent) || attack.HasFlag(hitFlag.VehicleImpact) || attack.HasFlag(hitFlag.VehicleImpactWithPlayer) || id == t"Attacks.CarHitPlayer" {
      return n"vehicle";
    }
    if id == t"Attacks.HardLanding" || id == t"Attacks.VeryHardLanding" || id == t"Attacks.DeathLanding" {
      return n"fall";
    }
    let type = attack.GetAttackType();
    if AttackData.IsAreaOfEffect(type) {
      return n"explosion";
    }
    if AttackData.IsMelee(type) || AttackData.IsWhip(type) || Equals(type, gamedataAttackType.GuardBreak) {
      return n"melee";
    }
    if AttackData.IsRangedOrDirect(type) || AttackData.IsThrown(type) {
      return n"bullet";
    }
    if Equals(hitEvent.attackComputed.GetDominatingDamageType(), gamedataDamageType.Thermal) {
      return n"fire";
    }
    return n"other";
  }

  private static func AttackID(hitEvent: ref<gameHitEvent>) -> TweakDBID {
    let attack = hitEvent.attackData.GetAttackDefinition();
    if IsDefined(attack) {
      let record = attack.GetRecord();
      if IsDefined(record) {
        return record.GetID();
      }
    }
    return TDBID.None();
  }
}

// Creative (plan 2026-10-03 S1.3): in Minecraft's creative mode Steve cannot be hurt, so neither can V where Steve's
// health does not cover V: in the Vehicle state (on foot, Active already makes V invulnerable through the report
// above). The source is n"CyberblockCreative", not n"Cyberblock", so Covers stays false (nothing is reported) and the
// Active-gated wraps (Disable.reds, IsImmune above) stay off. Removed in every other state or game mode, before
// saves, on load and at Off. GodModeSystem.AddGodMode/ClearGodMode as PlayerState.reds.
public class CyberblockCreative {
  private let applied: Bool;

  public static func Source() -> CName {
    return n"CyberblockCreative";
  }

  // Every tick, except while a save is written. vehicle: the Cyberblock state is Vehicle.
  public func Update(player: ref<PlayerPuppet>, vehicle: Bool) -> Void {
    let on = vehicle && Equals(Cyberblock_GameMode(), n"creative");
    if on && !this.applied {
      GameInstance.GetGodModeSystem(player.GetGame()).AddGodMode(player.GetEntityID(), gameGodModeType.Invulnerable, CyberblockCreative.Source());
      this.applied = true;
      Cyberblock_Log("creative: V invulnerable in the vehicle");
    } else {
      if !on {
        this.Clear(player);
      }
    }
  }

  public func Clear(player: ref<PlayerPuppet>) -> Void {
    if !this.applied || !IsDefined(player) {
      return;
    }
    GameInstance.GetGodModeSystem(player.GetGame()).ClearGodMode(player.GetEntityID(), CyberblockCreative.Source());
    this.applied = false;
    Cyberblock_Log("creative: V vulnerable again");
  }

  // On load: a save may still carry it.
  public static func ClearLeftovers(player: ref<PlayerPuppet>) -> Void {
    GameInstance.GetGodModeSystem(player.GetGame()).ClearGodMode(player.GetEntityID(), CyberblockCreative.Source());
  }
}
