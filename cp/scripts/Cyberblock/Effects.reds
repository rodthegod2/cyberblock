// Minecraft events applied as real Cyberpunk effects (layer 2 plan §1.4, S2.3). V is the instigator of everything, so
// the game's own systems give NCPD heat, hostility, kill credit and quest protection (god modes) as for V's attacks;
// V is never hit by them (the damage pipeline ignores self-damage without hitFlag.CanDamageSelf).
//
// 2.31 sources (decompiled 2.31 scripts, NativeDB, TweakDB read from r6/cache, archive index; compile-checked):
//   Explode: the scripted-explosion pattern of ClaymoreMine.Explode / InitiateCyberwareExplosionEffector /
//     FragGrenade.SpawnAttack: IAttack.Create(AttackInitContext) as Attack_GameEffect, PrepareAttack, EffectData
//     range/radius/position/attack/attackStatModList, StartAttack; the physicalImpulseSphere effect of
//     CombatGadgetHelper.SpawnPhysicalImpulse; AudioSystem.PlayShockwave(n"explosion"); stim as FragGrenade
//     .TriggerStimuli (StimTypes.Explosion, radius 30 of Items.GrenadeFragRegular). Attack records (TweakDB):
//     Attacks.ExplosiveDeviceAttackBase / ExplosionMedium / ExplosionLarge / ExplosionEnormous (effect
//     explosions/default_explosion_device, ranges 2/6/8/10 m, NPC damage by tier as a share of max health, 600 vs
//     vehicles; the records explosive devices use) and Attacks.IncendiaryGrenade (burning). VFX: FxSystem.SpawnEffect
//     with Codeware's FxResource.effect; barrel_explosion.effect is what explosive devices (boiler, c4) reference,
//     w_explosives_001__frag_grenade_01.effect is the frag grenade explosion.
//   Hit: a gameHitEvent queued to DamageSystem as VehicleComponent does for car collisions (AttackData
//     SetAttackDefinition/SetInstigator/SetSource/AddFlag), attack record Attacks.Impact (melee, hit animation);
//     the exact amount is set in DamageSystem.DealDamages, the last stage before StatPoolsManager.ApplyDamage, with
//     MultAttackValue(0) + SetAttackValue as ProcessVehicleTarget does. Knockback: CreateForceRagdollEvent + delayed
//     CreateRagdollApplyImpulseEvent (AIDeathReactionTask.TurnOnRagdoll); vehicles: PhysicalImpulseEvent
//     (MotorcycleComponent.OnKnockOverBikeEvent) with VehicleObject.GetTotalMass.
//   Ignite: StatusEffectSystem.ApplyStatusEffect(t"BaseStatusEffect.Burning", instigator V) +
//     SetStatusEffectRemainingDuration.
//   Targets: Codeware EntityID.FromHash (inverse of the EntityID hash S1 sends) + GameInstance.FindEntityByID.

public abstract class CyberblockEffects {
  // Minecraft explosion power (TNT 4, creeper 3, charged creeper 6, firework 1.5) -> Cyberpunk explosion of radius
  // 2 x power metres, Minecraft's entity damage radius.
  public static func Explode(game: GameInstance, pos: Vector4, power: Float, fire: Bool) -> Void {
    let player = GetPlayer(game);
    if !IsDefined(player) || power <= 0.0 {
      return;
    }
    let radius = 2.0 * power;
    let record = t"Attacks.ExplosionEnormous";
    let fx = r"base\\fx\\environment\\destructibles\\barrel\\barrel_explosion.effect";
    if radius < 5.0 {
      record = t"Attacks.ExplosiveDeviceAttackBase"; // fireworks: 20% of a normal NPC's health
      fx = r"base\\fx\\weapons\\explosives\\frag_grenade\\w_explosives_001__frag_grenade_01.effect";
    } else {
      if radius < 7.0 {
        record = t"Attacks.ExplosionMedium"; // creeper
      } else {
        if radius < 10.0 {
          record = t"Attacks.ExplosionLarge"; // TNT
        }
      }
    }
    CyberblockEffects.StartExplosionAttack(player, record, pos, radius);
    if fire {
      CyberblockEffects.StartExplosionAttack(player, t"Attacks.IncendiaryGrenade", pos, radius);
      CyberblockEffects.SpawnFx(game, r"base\\fx\\weapons\\explosives\\incendiary_grenade\\w_explosives_001_incendiary_grenade_01.effect", pos);
    }
    CyberblockEffects.SpawnFx(game, fx, pos);

    // Pushes cars, props and ragdolls like an explosive device.
    let impulse = GameInstance.GetGameEffectSystem(game).CreateEffectStatic(n"physicalImpulseSphere", n"None", player);
    EffectData.SetFloat(impulse.GetSharedData(), GetAllBlackboardDefs().EffectSharedData.radius, radius);
    EffectData.SetVector(impulse.GetSharedData(), GetAllBlackboardDefs().EffectSharedData.position, pos);
    impulse.Run();

    GameInstance.GetAudioSystem(game).PlayShockwave(n"explosion", pos);
    // NPCs react as to a grenade V threw (the stim is broadcast from V, the only object we have at hand).
    let investigate: stimInvestigateData;
    investigate.attackInstigator = player;
    investigate.attackInstigatorPosition = player.GetWorldPosition();
    investigate.revealsInstigatorPosition = true;
    StimBroadcasterComponent.BroadcastStim(player, gamedataStimType.Explosion, 30.0, investigate);
  }

  // Damages the NPC or vehicle by frac of its max health and pushes it with impulse (m/s, Cyberpunk space).
  public static func Hit(game: GameInstance, target: Uint64, frac: Float, impulse: Vector4) -> Void {
    let object = CyberblockEffects.Find(game, target);
    let player = GetPlayer(game);
    if !IsDefined(object) || !IsDefined(player) {
      return;
    }
    impulse = new Vector4(impulse.X, impulse.Y, impulse.Z, 0.0); // a direction: w must not count
    let id = object.GetEntityID();
    let hitPosition = object.GetWorldPosition() + new Vector4(0.0, 0.0, 1.0, 0.0);
    let direction = Vector4.Normalize(impulse);
    if Vector4.IsZero(direction) {
      direction = Vector4.Normalize(object.GetWorldPosition() - player.GetWorldPosition());
    }

    if frac > 0.0 {
      let amount = ClampF(frac, 0.0, 4.0) * GameInstance.GetStatsSystem(game).GetStatValue(Cast<StatsObjectID>(id), gamedataStatType.Health);
      let context: AttackInitContext;
      context.record = TweakDBInterface.GetAttackRecord(t"Attacks.Impact");
      context.instigator = player;
      context.source = player;
      let attack = IAttack.Create(context);
      // Keeps the attack value above zero, so the pipeline does not mark the hit DealNoDamage before DealDamages.
      attack.AddStatModifier(RPGManager.CreateStatModifier(gamedataStatType.PhysicalDamage, gameStatModifierType.Additive, amount));
      let hit = new gameHitEvent();
      hit.attackData = new AttackData();
      hit.target = object;
      hit.attackData.SetAttackDefinition(attack);
      hit.attackData.SetInstigator(player);
      hit.attackData.SetSource(player);
      hit.attackData.AddFlag(hitFlag.ForceNoCrit, n"Cyberblock");
      hit.hitPosition = hitPosition;
      hit.hitDirection = direction;
      CyberblockEffectsService.Get().Expect(hit, amount);
      GameInstance.GetDamageSystem(game).QueueHitEvent(hit, object);
    }

    let speed = Vector4.Length(impulse);
    let vehicle = object as VehicleObject;
    if IsDefined(vehicle) {
      if speed > 0.0 {
        let push = new PhysicalImpulseEvent();
        push.radius = 1.0;
        push.worldPosition = Vector4.Vector4To3(object.GetWorldPosition() + new Vector4(0.0, 0.0, 0.5, 0.0));
        push.worldImpulse = Vector4.Vector4To3(impulse * vehicle.GetTotalMass());
        vehicle.QueueEvent(push);
      }
      return;
    }
    // People are knocked over by a Minecraft-strength blow (melee is 6 m/s and more), as a car hit would; not while
    // seated in a vehicle.
    let ragdollSpeed = 4.0;
    let personMass = 75.0;
    if speed >= ragdollSpeed && IsDefined(object as ScriptedPuppet) && !VehicleComponent.IsMountedToVehicle(game, object) {
      object.QueueEvent(CreateForceRagdollEvent(n"Cyberblock"));
      GameInstance.GetDelaySystem(game).DelayEvent(object, CreateRagdollApplyImpulseEvent(hitPosition, impulse * personMass, 10000.0), 0.10, false);
    }
  }

  // Sets an NPC on fire with the vanilla burning status effect (V as instigator: its damage, heat and kills are V's).
  // Vehicles have no burning status; they are left alone.
  public static func Ignite(game: GameInstance, target: Uint64, seconds: Float) -> Void {
    let object = CyberblockEffects.Find(game, target);
    let player = GetPlayer(game);
    if !IsDefined(object as ScriptedPuppet) || !IsDefined(player) || seconds <= 0.0 {
      return;
    }
    let effects = GameInstance.GetStatusEffectSystem(game);
    let burning = t"BaseStatusEffect.Burning";
    effects.ApplyStatusEffect(object.GetEntityID(), burning, TDBID.None(), player.GetEntityID());
    effects.SetStatusEffectRemainingDuration(object.GetEntityID(), burning, seconds);
  }

  private static func Find(game: GameInstance, target: Uint64) -> ref<GameObject> {
    return GameInstance.FindEntityByID(game, EntityID.FromHash(target)) as GameObject;
  }

  private static func StartExplosionAttack(player: ref<PlayerPuppet>, record: TweakDBID, pos: Vector4, radius: Float) -> Void {
    let context: AttackInitContext;
    context.record = TweakDBInterface.GetAttackRecord(record);
    context.instigator = player;
    context.source = player;
    let attack = IAttack.Create(context) as Attack_GameEffect;
    if !IsDefined(attack) {
      Cyberblock_Log(s"effects: no attack for \(TDBID.ToStringDEBUG(record))");
      return;
    }
    let effect = attack.PrepareAttack(player);
    let statMods: array<ref<gameStatModifierData>>;
    attack.GetStatModList(statMods);
    let data = effect.GetSharedData();
    let defs = GetAllBlackboardDefs().EffectSharedData;
    EffectData.SetFloat(data, defs.range, radius);
    EffectData.SetFloat(data, defs.radius, radius);
    EffectData.SetVector(data, defs.position, pos);
    EffectData.SetVariant(data, defs.attack, ToVariant(attack));
    EffectData.SetVariant(data, defs.attackStatModList, ToVariant(statMods));
    attack.StartAttack();
  }

  private static func SpawnFx(game: GameInstance, path: ResRef, pos: Vector4) -> Void {
    let effect: ResourceAsyncRef;
    ResourceAsyncRef.SetPath(effect, path);
    let fx: FxResource;
    fx.effect = effect;
    let transform: WorldTransform;
    WorldTransform.SetPosition(transform, pos);
    WorldTransform.SetOrientationFromDir(transform, new Vector4(0.0, 1.0, 0.0, 0.0));
    GameInstance.GetFxSystem(game).SpawnEffect(fx, transform);
  }
}

// Hits Hit queued and the exact damage each must deal; DamageSystem.DealDamages applies it. Bounded: a hit the
// pipeline drops before DealDamages (dead or invulnerable target) is forgotten when it is released or pushed out.
public class CyberblockEffectsService extends ScriptableService {
  private let hits: array<wref<gameHitEvent>>;
  private let amounts: array<Float>;

  public static func Get() -> ref<CyberblockEffectsService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockEffectsService") as CyberblockEffectsService;
  }

  public func Expect(hit: ref<gameHitEvent>, amount: Float) -> Void {
    let i = ArraySize(this.hits) - 1;
    while i >= 0 {
      if !IsDefined(this.hits[i]) {
        ArrayErase(this.hits, i);
        ArrayErase(this.amounts, i);
      }
      i -= 1;
    }
    if ArraySize(this.hits) >= 64 {
      ArrayErase(this.hits, 0);
      ArrayErase(this.amounts, 0);
    }
    ArrayPush(this.hits, hit);
    ArrayPush(this.amounts, amount);
  }

  // Replaces the computed damage of one of our hits with the exact amount; any other hit is left untouched.
  public func Apply(hitEvent: ref<gameHitEvent>) -> Void {
    let i = 0;
    while i < ArraySize(this.hits) {
      if this.hits[i] == hitEvent {
        hitEvent.attackComputed.MultAttackValue(0.0);
        hitEvent.attackComputed.SetAttackValue(this.amounts[i], gamedataDamageType.Physical);
        ArrayErase(this.hits, i);
        ArrayErase(this.amounts, i);
        return;
      }
      i += 1;
    }
  }
}

// Runs for every hit in the game; cheap when no Cyberblock hit is pending.
@wrapMethod(DamageSystem)
private final func DealDamages(hitEvent: ref<gameHitEvent>) -> Void {
  let effects = CyberblockEffectsService.Get();
  if IsDefined(effects) {
    effects.Apply(hitEvent);
  }
  wrappedMethod(hitEvent);
}
