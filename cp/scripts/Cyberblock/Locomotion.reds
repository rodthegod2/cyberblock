// Minecraft's blocks are walked on, never vaulted or climbed (megaplan 2026-10-04 section 3, task Q-S2; brief Q2).
//
// Refusal: ClimbDecisions and VaultDecisions decide from the player obstacle system's current climb info. While
// Cyberblock is Active, a vanilla "yes" becomes "no" when that obstacle is one of our collision boxes (Collision.reds):
// the climbed entity carries the CyberblockCollision tag, or the obstacle's top point (5 cm into the obstacle, below
// its top edge) lies in a registered box. Without climb info, a 1.5 m ray ahead of V at knee height decides by the hit
// entity's tag. Every refusal logs 'locomotion: climb|vault refused on Cyberblock box', at most once per second per kind.
// Night City ledges and ladders (LadderDecisions, not wrapped) are untouched.
//
// Step-up assist (prefs.stepup from Minecraft, event prefs with the flag = stepup; on until Minecraft says off): at
// 10 Hz, while Active on foot, standing on the ground (not in a vehicle, a scene, a workspot, a menu or a flight) and
// pushing the move input into a single box whose top is at most 1.05 m above V's feet with 2 m of free space above it
// (no Minecraft block, no Night City), V is lifted onto the box in 0.15 s: three teleports 50 ms apart (half the rise,
// the full rise in place, then onto the middle of the box top). Nothing of Night City may lie between V and the box,
// above V's head or on the way over, so V is never moved through or onto Night City geometry. 'locomotion: step up
// <rise> m' at most once per second.
//
// CyberblockSystem (Cyberblock.reds) calls Tick at 10 Hz and Clear on every Clear path; Events.reds hands over prefs.
//
// 2.31 script API (NativeDB, redscript), compile-checked:
//   ClimbDecisions.EnterCondition and VaultDecisions.EnterCondition, protected const (locomotionTransitions.swift:2562,
//   :2952); both read StateGameScriptInterface.GetSpatialQueriesSystem().GetPlayerObstacleSystem()
//   .GetCurrentClimbInfo(scriptInterface.owner) -> PlayerClimbInfo (descResult: GeometryDescriptionResult with topPoint,
//   collisionNormal and climbedEntity; orphans.swift). ClimbDecisions publishes AnimFeature_PreClimbing through
//   StateContext.SetConditionScriptableParameter(n"PreClimbAnimFeature", ..., true); a refusal publishes valid 0.
//   GameObject.RegisterInputListener / UnregisterInputListener (gameObject.swift:152-156) with the move axes n"MoveX" /
//   n"MoveY" (DefaultTransition.GetMovementInputActionValue, defaultTransition.swift:586), ListenerAction.IsAction /
//   GetValue; Entity.GetWorldForward / GetWorldRight (entity.swift:50-52); PlayerStateMachine.LocomotionDetailed
//   (gamePSMDetailedLocomotionStates); TeleportationFacility.Teleport + Vector4.ToRotation (as Flight.reds);
//   DelaySystem.DelayCallback (as Cyberblock.reds); VehicleComponent.IsMountedToVehicle; SpatialQueriesSystem
//   .SyncRaycastByQueryFilter (as Probes.reds).
//   Codeware 1.20.3: TraceResult.GetHitEntity, StaticEntitySystem.IsTagged(EntityID, CName).

public class CyberblockLocomotionService extends ScriptableService {
  public let active: Bool;      // Cyberblock Active on foot and not saving (Tick)
  public let stepUpOff: Bool;   // prefs.stepup was false (the default is on)
  public let moveX: Float;      // the move input axes, -1..1
  public let moveY: Float;
  public let listening: wref<PlayerPuppet>;
  public let busyUntil: Float;  // a step-up is under way until this simulation time
  public let stepId: Int32;     // + 1 on every step-up and on Clear, so a cleared step-up stops
  public let loggedAt: array<Float>; // climb, vault, step up: when the last line was written

  public static func Get() -> ref<CyberblockLocomotionService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockLocomotionService") as CyberblockLocomotionService;
  }

  public func Listen(player: ref<PlayerPuppet>) -> Void {
    let current = this.listening;
    if current == player {
      return;
    }
    this.Unlisten();
    player.RegisterInputListener(this, n"MoveX");
    player.RegisterInputListener(this, n"MoveY");
    this.listening = player;
  }

  public func Unlisten() -> Void {
    let current = this.listening;
    if IsDefined(current) {
      current.UnregisterInputListener(this);
    }
    this.listening = null;
    this.moveX = 0.0;
    this.moveY = 0.0;
  }

  protected cb func OnAction(action: ListenerAction, consumer: ListenerActionConsumer) -> Bool {
    if ListenerAction.IsAction(action, n"MoveX") {
      this.moveX = ListenerAction.GetValue(action);
    } else if ListenerAction.IsAction(action, n"MoveY") {
      this.moveY = ListenerAction.GetValue(action);
    }
    return false;
  }

  // A log line, at most one per second per kind (0 climb, 1 vault, 2 step up).
  public func Note(game: GameInstance, kind: Int32, line: String) -> Void {
    if ArraySize(this.loggedAt) < 3 {
      ArrayResize(this.loggedAt, 3);
    }
    let now = CyberblockEffectsService.Now(game);
    let age = now - this.loggedAt[kind];
    if age >= 0.0 && age < 1.0 && this.loggedAt[kind] > 0.0 {
      return;
    }
    this.loggedAt[kind] = now;
    Cyberblock_Log(line);
  }
}

public class CyberblockStepUpCallback extends DelayCallback {
  public let player: wref<PlayerPuppet>;
  public let id: Int32;
  public let feet: Vector4;

  public func Call() -> Void {
    CyberblockLocomotion.StepTo(this.player, this.id, this.feet);
  }
}

public abstract class CyberblockLocomotion {
  // 10 Hz. active: Cyberblock is Active (on foot) and not saving.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, active: Bool) -> Void {
    let service = CyberblockLocomotionService.Get();
    if !IsDefined(service) {
      return;
    }
    service.active = active && IsDefined(player);
    if !service.active {
      service.Unlisten();
      return;
    }
    service.Listen(player);
    if CyberblockDisable.InMenu(game) {
      // A key let go while a menu has the input never reaches us.
      service.moveX = 0.0;
      service.moveY = 0.0;
      return;
    }
    if !service.stepUpOff {
      CyberblockLocomotion.StepUp(game, player, service);
    }
  }

  public static func Clear(game: GameInstance) -> Void {
    let service = CyberblockLocomotionService.Get();
    if IsDefined(service) {
      service.active = false;
      service.stepId += 1;
      service.busyUntil = 0.0;
      service.Unlisten();
    }
  }

  // Event prefs (Events.reds): Minecraft's step-up setting.
  public static func SetStepUp(on: Bool) -> Void {
    let service = CyberblockLocomotionService.Get();
    if IsDefined(service) && NotEquals(service.stepUpOff, !on) {
      service.stepUpOff = !on;
      let state = on ? "on" : "off";
      Cyberblock_Log(s"locomotion: step-up assist \(state)");
    }
  }

  // From the ClimbDecisions / VaultDecisions wraps, after a vanilla "yes": true when the obstacle is one of our boxes.
  public static func Refuse(scriptInterface: ref<StateGameScriptInterface>, what: String) -> Bool {
    let service = CyberblockLocomotionService.Get();
    if !IsDefined(service) || !service.active {
      return false;
    }
    let game = scriptInterface.GetGame();
    let info = scriptInterface.GetSpatialQueriesSystem().GetPlayerObstacleSystem().GetCurrentClimbInfo(scriptInterface.owner);
    let ours: Bool;
    if IsDefined(info) && IsDefined(info.descResult) {
      let top = info.descResult.topPoint;
      let normal = info.descResult.collisionNormal;
      let inside = new Vector4(top.X - normal.X * 0.05, top.Y - normal.Y * 0.05, top.Z - 0.05, 1.0);
      ours = CyberblockLocomotion.IsBox(info.descResult.climbedEntity) || CyberblockLocomotion.InBox(inside);
    } else {
      ours = CyberblockLocomotion.BoxAhead(game, scriptInterface.executionOwner);
    }
    if ours {
      service.Note(game, Equals(what, "climb") ? 0 : 1, s"locomotion: \(what) refused on Cyberblock box");
    }
    return ours;
  }

  private static func IsBox(entity: wref<Entity>) -> Bool {
    let object = entity;
    let entities = GameInstance.GetStaticEntitySystem();
    return IsDefined(object) && IsDefined(entities) && entities.IsTagged(object.GetEntityID(), CyberblockCollisionService.Tag());
  }

  private static func InBox(point: Vector4) -> Bool {
    let collision = CyberblockCollisionService.Get();
    return IsDefined(collision) && collision.Has(point);
  }

  // The fallback without climb info: the first thing a 1.5 m ray ahead of V at knee height hits is one of our boxes.
  private static func BoxAhead(game: GameInstance, owner: ref<GameObject>) -> Bool {
    if !IsDefined(owner) {
      return false;
    }
    let filter: QueryFilter;
    QueryFilter.AddGroup(filter, n"Static");
    let feet = owner.GetWorldPosition();
    let forward = owner.GetWorldForward();
    let from = new Vector4(feet.X, feet.Y, feet.Z + 0.5, 1.0);
    let to = new Vector4(from.X + forward.X * 1.5, from.Y + forward.Y * 1.5, from.Z, 1.0);
    let hit: TraceResult;
    if !GameInstance.GetSpatialQueriesSystem(game).SyncRaycastByQueryFilter(from, to, filter, hit) {
      return false;
    }
    return CyberblockLocomotion.IsBox(TraceResult.GetHitEntity(hit));
  }

  // V stands, walks, sprints or crouches on the ground (not jumping, falling, sliding, vaulting, climbing, on a ladder).
  private static func OnGround(player: ref<PlayerPuppet>) -> Bool {
    let psm = player.GetPlayerStateMachineBlackboard();
    if !IsDefined(psm) {
      return false;
    }
    let state = IntEnum<gamePSMDetailedLocomotionStates>(psm.GetInt(GetAllBlackboardDefs().PlayerStateMachine.LocomotionDetailed));
    return Equals(state, gamePSMDetailedLocomotionStates.Stand) || Equals(state, gamePSMDetailedLocomotionStates.AimWalk)
      || Equals(state, gamePSMDetailedLocomotionStates.Crouch) || Equals(state, gamePSMDetailedLocomotionStates.Sprint)
      || Equals(state, gamePSMDetailedLocomotionStates.CrouchSprint);
  }

  private static func StepUp(game: GameInstance, player: ref<PlayerPuppet>, service: ref<CyberblockLocomotionService>) -> Void {
    let now = CyberblockEffectsService.Now(game);
    let wait = service.busyUntil - now;
    if wait > 0.0 && wait < 1.0 {
      return; // a step-up is under way (a deadline from another session does not count)
    }
    if SqrtF(service.moveX * service.moveX + service.moveY * service.moveY) < 0.5 {
      return;
    }
    if VehicleComponent.IsMountedToVehicle(game, player) || CyberblockEffects.PlayerLocked(game, player) || Cyberblock_FlightDriving() || !CyberblockLocomotion.OnGround(player) {
      return;
    }
    let collision = CyberblockCollisionService.Get();
    if !IsDefined(collision) {
      return;
    }
    // Where the move input pushes V, flat.
    let forward = player.GetWorldForward();
    let right = player.GetWorldRight();
    let dx = forward.X * service.moveY + right.X * service.moveX;
    let dy = forward.Y * service.moveY + right.Y * service.moveX;
    let length = SqrtF(dx * dx + dy * dy);
    if length < 0.01 {
      return;
    }
    dx /= length;
    dy /= length;
    let feet = player.GetWorldPosition();

    // The box V walks into: the cell 0.65 m ahead (V's capsule is 0.4 m wide in radius) at knee or hip height.
    let box: Vector4;
    let ahead = new Vector4(feet.X + dx * 0.65, feet.Y + dy * 0.65, feet.Z + 0.3, 1.0);
    if !collision.Find(ahead, box) {
      ahead.Z = feet.Z + 0.9;
      if !collision.Find(ahead, box) {
        return;
      }
    }
    let top = box.Z + 1.0;
    let rise = top - feet.Z;
    if rise <= 0.05 || rise > 1.05 {
      return;
    }
    let landing = new Vector4(box.X + 0.5, box.Y + 0.5, top + 0.02, 1.0);
    // A single box with 2 m free above it: no Minecraft block, no Night City.
    if collision.Has(new Vector4(landing.X, landing.Y, top + 0.5, 1.0)) || collision.Has(new Vector4(landing.X, landing.Y, top + 1.5, 1.0)) {
      return;
    }
    if CyberblockProbes.Blocked(game, new Vector4(landing.X, landing.Y, top + 0.05, 1.0), new Vector4(landing.X, landing.Y, top + 2.0, 1.0)) {
      return;
    }
    // The obstacle is our box, not Night City in front of it; V rises in place under free space (no Night City, no
    // block over V's head) and crosses to the box top without passing Night City.
    if CyberblockProbes.Blocked(game, new Vector4(feet.X, feet.Y, ahead.Z, 1.0), ahead) {
      return;
    }
    let head = feet.Z + 1.8;
    if collision.Has(new Vector4(feet.X, feet.Y, head + rise, 1.0)) || CyberblockProbes.Blocked(game, new Vector4(feet.X, feet.Y, head, 1.0), new Vector4(feet.X, feet.Y, head + rise + 0.1, 1.0)) {
      return;
    }
    if CyberblockProbes.Blocked(game, new Vector4(feet.X, feet.Y, top + 0.3, 1.0), new Vector4(landing.X, landing.Y, top + 0.3, 1.0))
      || CyberblockProbes.Blocked(game, new Vector4(feet.X, feet.Y, top + 1.5, 1.0), new Vector4(landing.X, landing.Y, top + 1.5, 1.0)) {
      return;
    }

    service.stepId += 1;
    service.busyUntil = now + 0.25;
    let delays = GameInstance.GetDelaySystem(game);
    CyberblockLocomotion.Schedule(delays, player, service.stepId, new Vector4(feet.X, feet.Y, feet.Z + rise * 0.5, 1.0), 0.05);
    CyberblockLocomotion.Schedule(delays, player, service.stepId, new Vector4(feet.X, feet.Y, top + 0.03, 1.0), 0.10);
    CyberblockLocomotion.Schedule(delays, player, service.stepId, landing, 0.15);
    service.Note(game, 2, s"locomotion: step up \(rise) m");
  }

  private static func Schedule(delays: ref<DelaySystem>, player: ref<PlayerPuppet>, id: Int32, feet: Vector4, delay: Float) -> Void {
    let step = new CyberblockStepUpCallback();
    step.player = player;
    step.id = id;
    step.feet = feet;
    delays.DelayCallback(step, delay, false);
  }

  // One teleport of a step-up, unless it was cleared or V got into a vehicle, a scene or a workspot meanwhile.
  public static func StepTo(target: wref<PlayerPuppet>, id: Int32, feet: Vector4) -> Void {
    let player = target;
    let service = CyberblockLocomotionService.Get();
    if !IsDefined(player) || !IsDefined(service) || !service.active || service.stepId != id {
      return;
    }
    let game = player.GetGame();
    if VehicleComponent.IsMountedToVehicle(game, player) || CyberblockEffects.PlayerLocked(game, player) {
      return;
    }
    GameInstance.GetTeleportationFacility(game).Teleport(player, feet, Vector4.ToRotation(player.GetWorldForward()));
  }
}

// Vanilla first; our boxes only turn a "yes" into a "no". The hand-reach feature the decision published is withdrawn.
@wrapMethod(ClimbDecisions)
protected const func EnterCondition(const stateContext: ref<StateContext>, const scriptInterface: ref<StateGameScriptInterface>) -> Bool {
  if !wrappedMethod(stateContext, scriptInterface) {
    return false;
  }
  if !CyberblockLocomotion.Refuse(scriptInterface, "climb") {
    return true;
  }
  let preClimb = new AnimFeature_PreClimbing();
  preClimb.valid = 0.0;
  stateContext.SetConditionScriptableParameter(n"PreClimbAnimFeature", preClimb, true);
  return false;
}

@wrapMethod(VaultDecisions)
protected const func EnterCondition(const stateContext: ref<StateContext>, const scriptInterface: ref<StateGameScriptInterface>) -> Bool {
  return wrappedMethod(stateContext, scriptInterface) && !CyberblockLocomotion.Refuse(scriptInterface, "vault");
}
