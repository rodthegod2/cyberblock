// Minecraft events applied as real Cyberpunk effects (layer 2 plan §1.4, S2.3). V is the instigator of everything, so
// the game's own systems give NCPD heat, hostility, kill credit and quest protection (god modes) as for V's attacks;
// V is never hit by them (the damage pipeline ignores self-damage without hitFlag.CanDamageSelf). Hit's push is not
// part of that pipeline, so Hit withholds it itself from the same targets and from NPCs in workspots, and says so in
// the log (IsProtected, Withheld).
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
//   Hit: a gameHitEvent queued to DamageSystem as VehicleComponent.CreateHitEventOnSelf does for car collisions
//     (AttackData SetAttackDefinition, PreAttack, SetAttackPosition, SetAttackTime, SetInstigator/SetSource/AddFlag),
//     attack record Attacks.Impact (attackType AttackType.Melee, hit-reaction severity min 1, read from tweakdb.bin);
//     the exact amount is set in DamageSystem.DealDamages, the last stage before StatPoolsManager.ApplyDamage, with
//     MultAttackValue(0) + SetAttackValue as ProcessVehicleTarget does. Knockback, applied there too (only for a hit
//     the pipeline accepted): CreateForceRagdollEvent + delayed CreateRagdollApplyImpulseEvent
//     (AIDeathReactionTask.TurnOnRagdoll) where ScriptedPuppet.CanRagdoll allows; vehicles: PhysicalImpulseEvent
//     (MotorcycleComponent.OnKnockOverBikeEvent) with VehicleObject.GetTotalMass. Withheld from protected targets:
//     GodModeSystem.HasGodMode (Invulnerable, Immortal; DamageSystem.ProcessVehicleTarget), GameObject
//     .IsFriendlyTowardsPlayer, VehicleComponent.GetVehicle/GetDriver, WorkspotGameSystem.IsActorInWorkspot. Not
//     SceneSystemInterface.IsEntityInScene: in game (2026-10-03) it was true for V in free open-world play, so it
//     cannot tell who a scene holds.
//   Ignite: StatusEffectSystem.ApplyStatusEffect(t"BaseStatusEffect.Burning", instigator V) +
//     SetStatusEffectRemainingDuration.
//   HitFrom (layers 3-6 plan §1.4, S2.3): Hit with the mob's puppet (Puppets.reds) as instigator and source; an
//     aggressive NPC turns on whoever hits it (TargetTrackingExtension.OnHit), and V's friends, whom the pipeline
//     spares from V's hits only (DamageSystem.CheckForQuickExit, FriendlyFireIgnored), are spared explicitly.
//   Teleport: TeleportationFacility.Teleport(player, feet, Vector4.ToRotation(forward)) as vanilla teleports keep the
//     look (worldMap.swift, roadBlockTrap.swift); the camera pitch lives in FPPCameraComponent, which fast travel
//     resets explicitly (ResetPitch) before teleporting, so a plain teleport keeps it. Fall suppression on sim time
//     (TimeSystem.GetSimTime). Refused while V is in a vehicle or PlayerLocked: WorkspotGameSystem.IsActorInWorkspot,
//     PlayerStateMachine.HighLevel in gamePSMHighLevel.SceneTier2..SceneTier5 (vanilla IsInSafeSceneTier,
//     defaultTransition.swift), BraindanceSystem.GetIsInBraindance (fetched from the scriptable systems container as
//     PlayerPuppet does), PlayerStateMachine.IsPlayerInsideMovingElevator (settingsMain.swift). Minecraft blocks the
//     pearl itself while V is held; the 'teleport ignored' log stays as the second line.
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
    // NPCs react as to a grenade V threw. The stim goes out from V (the only object we have at hand) with the grenade's
    // 30 m radius, so it is sent only for an explosion within 30 m of V; one farther away (TNT Steve set off and walked
    // away from, a creeper behind a wall) must not draw everyone to V. Only one within 10 m tells them where V is.
    let distance = Vector4.Distance(pos, player.GetWorldPosition());
    if distance <= 30.0 {
      let investigate: stimInvestigateData;
      investigate.attackInstigator = player;
      investigate.attackInstigatorPosition = player.GetWorldPosition();
      investigate.revealsInstigatorPosition = distance <= 10.0;
      StimBroadcasterComponent.BroadcastStim(player, gamedataStimType.Explosion, 30.0, investigate);
    }
  }

  // Damages the NPC or vehicle by frac of its max health and pushes it with impulse (m/s, Cyberpunk space).
  public static func Hit(game: GameInstance, target: Uint64, frac: Float, impulse: Vector4) -> Void {
    CyberblockEffects.Strike(game, target, frac, impulse, GetPlayer(game));
  }

  // Hit by a Minecraft mob (mob -1: by Steve). The mob's puppet is the instigator and source when it exists, so the
  // target turns on the puppet and V gets no heat for it; else V is, as Hit.
  public static func HitFrom(game: GameInstance, target: Uint64, frac: Float, impulse: Vector4, mob: Int32) -> Void {
    let puppet: ref<GameObject>;
    if mob >= 0 {
      puppet = CyberblockPuppets.Find(game, mob);
    }
    if !IsDefined(puppet) {
      CyberblockEffects.Hit(game, target, frac, impulse);
      return;
    }
    // The pipeline only spares V's friends from V's own hits (friendly fire), and a puppet is hostile to them: spare
    // them here, so a mob hurts exactly whom Steve could.
    let object = CyberblockEffects.Find(game, target);
    if !IsDefined(object) || GameObject.IsFriendlyTowardsPlayer(object) || IsDefined(object as VehicleObject) && GameObject.IsFriendlyTowardsPlayer(VehicleComponent.GetDriverMounted(game, object.GetEntityID())) {
      return;
    }
    CyberblockEffects.Strike(game, target, frac, impulse, puppet);
  }

  // Moves V to feet (an ender pearl landed), facing Cyberpunk yaw. Only the body turns: the camera pitch is the FPP
  // camera's own and survives a teleport (fast travel calls FPPCameraComponent.ResetPitch explicitly before its
  // TeleportToNode). Falls are not reported for 2 s after it.
  public static func Teleport(game: GameInstance, feet: Vector4, yaw: Float) -> Void {
    let player = GetPlayer(game);
    if !IsDefined(player) {
      return;
    }
    if VehicleComponent.IsMountedToVehicle(game, player) {
      Cyberblock_Log("effects: teleport ignored, V is in a vehicle");
      return;
    }
    let lock = CyberblockEffects.LockReason(game, player);
    if NotEquals(lock, n"") {
      Cyberblock_Log(s"effects: teleport ignored, V is held (\(NameToString(lock)), scene tier \(CyberblockEffects.SceneTier(player)))");
      return;
    }
    CyberblockEffectsService.Get().SuppressFall(game, 2.0);
    GameInstance.GetTeleportationFacility(game).Teleport(player, feet, CyberblockEffects.Facing(yaw));
  }

  // V is held by the game: a workspot (seated dialogue, quest animations), a scene, a braindance or a moving elevator.
  // Moving V then (an ender pearl, a glide) could break the scene or the quest, and turning Cyberblock off would not
  // undo it.
  public static func PlayerLocked(game: GameInstance, player: ref<PlayerPuppet>) -> Bool {
    return NotEquals(CyberblockEffects.LockReason(game, player), n"");
  }

  // Why the game holds V (n"" when it does not). In-game test 2026-10-03: SceneSystem.IsEntityInScene(V) was true
  // during free open-world play and blocked every glide and pearl, so scenes are judged by the player state
  // machine's high level instead, as vanilla IsInSafeSceneTier (defaultTransition.swift) does: tiers 2-5 are quest
  // scenes (2 = restricted gameplay in a quest scene, 3-5 take movement from the player). Tier 0-1 is free gameplay
  // and 6 is swimming (gamePSMHighLevel.Swimming), neither of them a scene.
  public static func LockReason(game: GameInstance, player: ref<PlayerPuppet>) -> CName {
    if GameInstance.GetWorkspotSystem(game).IsActorInWorkspot(player) {
      return n"workspot";
    }
    let braindance = GameInstance.GetScriptableSystemsContainer(game).Get(n"BraindanceSystem") as BraindanceSystem;
    if IsDefined(braindance) && braindance.GetIsInBraindance() {
      return n"braindance";
    }
    let psm = player.GetPlayerStateMachineBlackboard();
    if IsDefined(psm) {
      if psm.GetBool(GetAllBlackboardDefs().PlayerStateMachine.IsPlayerInsideMovingElevator) {
        return n"elevator";
      }
      let tier = psm.GetInt(GetAllBlackboardDefs().PlayerStateMachine.HighLevel);
      if tier >= EnumInt(gamePSMHighLevel.SceneTier2) && tier <= EnumInt(gamePSMHighLevel.SceneTier5) {
        return n"scene";
      }
    }
    return n"";
  }

  // The player state machine's high level (gamePSMHighLevel as Int32; -1 without a blackboard), for the logs.
  public static func SceneTier(player: ref<PlayerPuppet>) -> Int32 {
    let psm = player.GetPlayerStateMachineBlackboard();
    return IsDefined(psm) ? psm.GetInt(GetAllBlackboardDefs().PlayerStateMachine.HighLevel) : -1;
  }

  private static func Strike(game: GameInstance, target: Uint64, frac: Float, impulse: Vector4, attacker: ref<GameObject>) -> Void {
    let object = CyberblockEffects.Find(game, target);
    let player = GetPlayer(game);
    if !IsDefined(object) || !IsDefined(player) || !IsDefined(attacker) {
      return;
    }
    impulse = new Vector4(impulse.X, impulse.Y, impulse.Z, 0.0); // a direction: w must not count
    let id = object.GetEntityID();
    let hitPosition = object.GetWorldPosition() + new Vector4(0.0, 0.0, 1.0, 0.0);
    let direction = Vector4.Normalize(impulse);
    if Vector4.IsZero(direction) {
      direction = Vector4.Normalize(object.GetWorldPosition() - attacker.GetWorldPosition());
    }
    // The damage pipeline protects its targets from the damage only; the push is ours to withhold.
    if CyberblockEffects.Withheld(game, object, player) {
      impulse = new Vector4(0.0, 0.0, 0.0, 0.0);
    }
    if frac <= 0.0 {
      CyberblockEffects.Knock(object, hitPosition, impulse);
      return;
    }

    let amount = ClampF(frac, 0.0, 4.0) * GameInstance.GetStatsSystem(game).GetStatValue(Cast<StatsObjectID>(id), gamedataStatType.Health);
    let context: AttackInitContext;
    context.record = TweakDBInterface.GetAttackRecord(t"Attacks.Impact");
    context.instigator = attacker;
    context.source = attacker;
    let attack = IAttack.Create(context);
    // Keeps the attack value above zero, so the pipeline does not mark the hit DealNoDamage before DealDamages.
    attack.AddStatModifier(RPGManager.CreateStatModifier(gamedataStatType.PhysicalDamage, gameStatModifierType.Additive, amount));
    let hit = new gameHitEvent();
    hit.attackData = new AttackData();
    hit.target = object;
    hit.attackData.SetAttackDefinition(attack);
    // Attack type (AttackType.Melee) and hit-reaction severity from Attacks.Impact; left unset, the type is enum 0,
    // ChargedWhipAttack, which dismembers on kill.
    hit.attackData.PreAttack();
    hit.attackData.SetAttackPosition(hitPosition - direction); // where the blow came from, 1 m back
    hit.attackData.SetAttackTime(EngineTime.ToFloat(GameInstance.GetTimeSystem(game).GetSimTime()));
    hit.attackData.SetInstigator(attacker);
    hit.attackData.SetSource(attacker);
    hit.attackData.AddFlag(hitFlag.ForceNoCrit, n"Cyberblock");
    hit.hitPosition = hitPosition;
    hit.hitDirection = direction;
    // The push follows the damage (CyberblockEffectsService.Apply), so a hit the pipeline refuses pushes nothing.
    CyberblockEffectsService.Get().Expect(hit, amount, impulse);
    GameInstance.GetDamageSystem(game).QueueHitEvent(hit, object);
  }

  // Pushes a vehicle, or knocks a person over with a Minecraft-strength blow (melee is 6 m/s and more) as a car hit
  // would, where the game allows a ragdoll (ScriptedPuppet.CanRagdoll: not seated in a vehicle, not a boss, ...).
  public static func Knock(object: ref<GameObject>, hitPosition: Vector4, impulse: Vector4) -> Void {
    let speed = Vector4.Length(impulse);
    if !IsDefined(object) || speed <= 0.0 {
      return;
    }
    let vehicle = object as VehicleObject;
    if IsDefined(vehicle) {
      let push = new PhysicalImpulseEvent();
      push.radius = 1.0;
      push.worldPosition = Vector4.Vector4To3(object.GetWorldPosition() + new Vector4(0.0, 0.0, 0.5, 0.0));
      push.worldImpulse = Vector4.Vector4To3(impulse * vehicle.GetTotalMass());
      vehicle.QueueEvent(push);
      return;
    }
    let ragdollSpeed = 4.0;
    let personMass = 75.0;
    if speed >= ragdollSpeed && ScriptedPuppet.CanRagdoll(object) {
      object.QueueEvent(CreateForceRagdollEvent(n"Cyberblock"));
      GameInstance.GetDelaySystem(object.GetGame()).DelayEvent(object, CreateRagdollApplyImpulseEvent(hitPosition, impulse * personMass, 10000.0), 0.10, false);
    }
  }

  // Why the push must leave a target alone, n"" when it may be pushed: n"player" (V or V's car), n"godmode" (an
  // Invulnerable or Immortal god mode on it, or on the driver of a car), n"friendly" (friendly towards V, or a car a
  // friend of V drives), n"workspot" (a quest animates it; a forced ragdoll would pull it out). The first three are
  // what the damage pipeline protects too.
  public static func IsProtected(game: GameInstance, object: ref<GameObject>, player: ref<PlayerPuppet>) -> CName {
    let id = object.GetEntityID();
    let mounted: wref<GameObject>;
    if object == player || VehicleComponent.GetVehicle(game, player, mounted) && mounted == object {
      return n"player";
    }
    if CyberblockEffects.HasGodMode(game, id) {
      return n"godmode";
    }
    if GameObject.IsFriendlyTowardsPlayer(object) {
      return n"friendly";
    }
    let vehicle = object as VehicleObject;
    if IsDefined(vehicle) {
      let driver = VehicleComponent.GetDriver(game, vehicle, id);
      if IsDefined(driver) {
        if CyberblockEffects.HasGodMode(game, driver.GetEntityID()) {
          return n"godmode";
        }
        if GameObject.IsFriendlyTowardsPlayer(driver) {
          return n"friendly";
        }
      }
    }
    return GameInstance.GetWorkspotSystem(game).IsActorInWorkspot(object) ? n"workspot" : n"";
  }

  // True when the push must leave object alone (IsProtected); each target is named in the log at most every 10 s.
  public static func Withheld(game: GameInstance, object: ref<GameObject>, player: ref<PlayerPuppet>) -> Bool {
    let reason = CyberblockEffects.IsProtected(game, object, player);
    if Equals(reason, n"") {
      return false;
    }
    CyberblockEffectsService.Get().LogWithheld(game, EntityID.ToHash(object.GetEntityID()), reason);
    return true;
  }

  private static func HasGodMode(game: GameInstance, id: EntityID) -> Bool {
    let godModes = GameInstance.GetGodModeSystem(game);
    return godModes.HasGodMode(id, gameGodModeType.Invulnerable) || godModes.HasGodMode(id, gameGodModeType.Immortal);
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

  public static func Find(game: GameInstance, target: Uint64) -> ref<GameObject> {
    return GameInstance.FindEntityByID(game, EntityID.FromHash(target)) as GameObject;
  }

  // A hit Hit/HitFrom queued: it carries ForceNoCrit with source n"Cyberblock" (Attacks.Impact has no hit flags of
  // its own, and AttackData.AddFlag keeps the first source of a flag), at every stage of the pipeline.
  public static func IsOurHit(hitEvent: ref<gameHitEvent>) -> Bool {
    for flag in hitEvent.attackData.GetFlags() {
      if Equals(flag.source, n"Cyberblock") {
        return true;
      }
    }
    return false;
  }

  // Orientation facing Cyberpunk yaw (degrees, 0 = +Y, counter-clockwise from above: forward = (-sin, cos)), built
  // from the direction as vanilla teleports do (Vector4.ToRotation, worldMap.swift), so no Euler convention is assumed.
  public static func Facing(yaw: Float) -> EulerAngles {
    return Vector4.ToRotation(new Vector4(-SinF(Deg2Rad(yaw)), CosF(Deg2Rad(yaw)), 0.0, 0.0));
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

// Hits Hit queued, with the exact damage each must deal and the push that goes with it; DamageSystem.DealDamages
// applies them. Bounded: a hit the pipeline drops before DealDamages (dead or invulnerable target) is forgotten when
// it is released or pushed out.
public class CyberblockEffectsService extends ScriptableService {
  private let hits: array<wref<gameHitEvent>>;
  private let amounts: array<Float>;
  private let impulses: array<Vector4>;
  // Falls are not reported until this simulation time (Teleport, and flight landings in Flight.reds).
  private let fallQuietUntil: Float;
  private let fallQuietFor: Float;
  // Targets whose withheld push was logged in the last 10 s, and when (parallel arrays).
  private let withheldTargets: array<Uint64>;
  private let withheldAt: array<Float>;

  public static func Get() -> ref<CyberblockEffectsService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockEffectsService") as CyberblockEffectsService;
  }

  public static func Now(game: GameInstance) -> Float {
    return EngineTime.ToFloat(GameInstance.GetTimeSystem(game).GetSimTime());
  }

  public func SuppressFall(game: GameInstance, seconds: Float) -> Void {
    this.fallQuietFor = seconds;
    this.fallQuietUntil = CyberblockEffectsService.Now(game) + seconds;
  }

  // A deadline further away than it was set (simulation time restarted with another session) does not count.
  public func IsFallSuppressed(game: GameInstance) -> Bool {
    let left = this.fallQuietUntil - CyberblockEffectsService.Now(game);
    return left > 0.0 && left <= this.fallQuietFor;
  }

  // 'effects: push withheld from <hash> (<reason>)', once per target per 10 s. Entries older than that (or stamped in
  // another session, whose simulation time lies ahead) are dropped first, so the list stays short.
  public func LogWithheld(game: GameInstance, target: Uint64, reason: CName) -> Void {
    let now = CyberblockEffectsService.Now(game);
    let i = ArraySize(this.withheldTargets) - 1;
    while i >= 0 {
      let age = now - this.withheldAt[i];
      if age >= 10.0 || age < 0.0 {
        ArrayErase(this.withheldTargets, i);
        ArrayErase(this.withheldAt, i);
      }
      i -= 1;
    }
    if ArrayContains(this.withheldTargets, target) {
      return;
    }
    ArrayPush(this.withheldTargets, target);
    ArrayPush(this.withheldAt, now);
    Cyberblock_Log(s"effects: push withheld from \(target) (\(NameToString(reason)))");
  }

  public func Expect(hit: ref<gameHitEvent>, amount: Float, impulse: Vector4) -> Void {
    let i = ArraySize(this.hits) - 1;
    while i >= 0 {
      if !IsDefined(this.hits[i]) {
        this.Forget(i);
      }
      i -= 1;
    }
    if ArraySize(this.hits) >= 64 {
      this.Forget(0);
    }
    ArrayPush(this.hits, hit);
    ArrayPush(this.amounts, amount);
    ArrayPush(this.impulses, impulse);
  }

  // Replaces the computed damage of one of our hits with the exact amount and pushes the target; any other hit is
  // left untouched. A hit the pipeline refused on the way (DealNoDamage: e.g. a vehicle immune while decaying) keeps
  // its zero damage and pushes nothing.
  public func Apply(hitEvent: ref<gameHitEvent>) -> Void {
    let i = 0;
    while i < ArraySize(this.hits) {
      if this.hits[i] == hitEvent {
        let amount = this.amounts[i];
        let impulse = this.impulses[i];
        this.Forget(i);
        if !hitEvent.attackData.HasFlag(hitFlag.DealNoDamage) {
          hitEvent.attackComputed.MultAttackValue(0.0);
          hitEvent.attackComputed.SetAttackValue(amount, gamedataDamageType.Physical);
          CyberblockEffects.Knock(hitEvent.target, hitEvent.hitPosition, impulse);
        }
        return;
      }
      i += 1;
    }
  }

  private func Forget(i: Int32) -> Void {
    ArrayErase(this.hits, i);
    ArrayErase(this.amounts, i);
    ArrayErase(this.impulses, i);
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
