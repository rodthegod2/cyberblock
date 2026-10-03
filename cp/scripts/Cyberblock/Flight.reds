// Elytra flight (plan 2026-10-03 section 1.4, S2.4; method and evidence: docs/notes/flight-spike.md). While Steve
// glides, Minecraft's physics move him; the DLL keeps the latest flight sample (Cyberblock_Flight*). From the moment
// Cyberblock_FlightActive() turns true until it turns false again, a per-frame loop follows it:
//   primary: V is teleported to the flight position every frame, keeping V's own yaw and camera pitch (mouse look);
//   camera only (CyberblockFlight.SetCameraOnly(true) from CET, the fallback if that jitters): V waits at the take-off
//     spot (hidden by PlayerState while Active), a vanilla free camera flies at the flight position + 1.6 m looking
//     along Minecraft's yaw/pitch, and the mouse goes to Minecraft (Cyberblock_SetLookCapture).
// On landing V is placed at the landing spot; falls are not reported while flying and for 2 s after a landing (or a
// Teleport, Effects.reds). Clear stops everything and gives V back the view; if V was left in mid-air it is put on
// the ground below, so leaving Cyberblock in mid-glide does not drop V from the sky. No flight starts while V is in a
// vehicle or held by the game (CyberblockEffects.PlayerLocked: a scene, workspot, braindance or moving elevator), and
// one that is under way when that happens stops where V is, without the landing teleport.
// Minecraft hears whether V follows: Cyberblock_SetFlightFollowing(true) at the start and every frame while following
// puts cam.fly in every cam message, and every stop or refusal turns it false. A glide that is refused, or not
// followed within 0.5 s, is called off on the Minecraft side and Steve goes back to V (in-game 2026-10-03: a refused
// glide flew Steve 320 m alone, into the void).
//
// 2.31 + Codeware 1.20.3 APIs (decompiled 2.31 scripts, Codeware.Global.reds; compile-checked):
//   DelaySystem.DelayCallbackNextFrame(ref<DelayCallback>) (no DelayID: a generation number stops the loop),
//   TeleportationFacility.Teleport, Vector4.ToRotation(player.GetWorldForward()) (vanilla teleports that keep the
//   facing), Quaternion.BuildFromDirectionVector/ToEulerAngles, StaticEntitySystem.SpawnEntity/GetEntity/
//   DespawnEntity/DespawnTagged + StaticEntitySpec, template base\entities\cameras\simple_free_camera.ent
//   (gameFreeCamera: gameCameraComponent, dynamic sphere collider, editor box mesh), CameraComponent.Activate/
//   Deactivate(0.0, true) (as devices take and return the view, deviceBase.swift), IComponent.IsA/Toggle,
//   Entity.SetWorldTransform (Codeware), SpatialQueriesSystem.SyncRaycastByQueryFilter (Static + Terrain, as
//   Probes.reds), VehicleComponent.IsMountedToVehicle. Cyberblock_SetFlightFollowing is the DLL's (Natives.reds).

public abstract class CyberblockFlight {
  // 10 Hz; starts the per-frame loop when a flight starts. live false: stop following and restore.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void {
    CyberblockFlightService.Get().Tick(game, player, live);
  }

  public static func IsFlying(game: GameInstance) -> Bool {
    return CyberblockFlightService.Get().IsFlying();
  }

  // Flying, or less than 2 s since a landing or a Teleport: Health.reds does not report falls.
  public static func SuppressFall(game: GameInstance) -> Bool {
    return CyberblockFlightService.Get().IsFlying() || CyberblockEffectsService.Get().IsFallSuppressed(game);
  }

  // Stop following, restore V and the camera.
  public static func Clear(game: GameInstance) -> Void {
    CyberblockFlightService.Get().Clear(game);
  }

  // Fallback switch for the next flight, callable from the CET console: CyberblockFlight.SetCameraOnly(true).
  public static func SetCameraOnly(on: Bool) -> Void {
    CyberblockFlightService.Get().SetCameraOnly(on);
  }

  public static func IsCameraOnly() -> Bool {
    return CyberblockFlightService.Get().IsCameraOnly();
  }
}

public class CyberblockFlightFrame extends DelayCallback {
  public let game: GameInstance;
  public let generation: Int32;

  public func Call() -> Void {
    CyberblockFlightService.Get().Frame(this.game, this.generation);
  }
}

public class CyberblockFlightService extends ScriptableService {
  private let cameraOnly: Bool;
  private let flying: Bool;
  private let usingCamera: Bool; // fixed for the whole flight when it starts
  private let generation: Int32;
  private let lastFrame: Float;
  private let startTime: Float; // sim time at 'flight: start'
  private let anchor: Vector4; // camera only: where V waits
  private let camera: EntityID;
  private let cameraActive: Bool;
  private let ignoredLogged: Bool;

  public static func Get() -> ref<CyberblockFlightService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockFlightService") as CyberblockFlightService;
  }

  public static func CameraTag() -> CName {
    return n"CyberblockFlightCamera";
  }

  public func SetCameraOnly(on: Bool) -> Void {
    this.cameraOnly = on;
    Cyberblock_Log(on ? "flight: camera-only mode for the next flights" : "flight: primary mode for the next flights");
  }

  public func IsCameraOnly() -> Bool {
    return this.cameraOnly;
  }

  public func IsFlying() -> Bool {
    return this.flying;
  }

  public func Tick(game: GameInstance, player: ref<PlayerPuppet>, live: Bool) -> Void {
    if !live || !IsDefined(player) {
      if this.flying {
        this.Clear(game);
      }
      return;
    }
    if this.flying {
      // The loop stopped without a landing (e.g. the delay system dropped it): start a new one.
      if CyberblockEffectsService.Now(game) - this.lastFrame > 1.0 {
        this.Loop(game);
      }
      return;
    }
    if !Cyberblock_FlightActive() {
      this.ignoredLogged = false;
      return;
    }
    if VehicleComponent.IsMountedToVehicle(game, player) || CyberblockEffects.PlayerLocked(game, player) {
      // cam.fly stays false: Minecraft calls the glide off and puts Steve back at V
      Cyberblock_SetFlightFollowing(false);
      if !this.ignoredLogged {
        this.ignoredLogged = true;
        Cyberblock_Log(s"flight: ignored, V is in a vehicle or held (\(NameToString(CyberblockEffects.LockReason(game, player))))");
      }
      return;
    }
    this.flying = true;
    this.startTime = CyberblockEffectsService.Now(game);
    this.usingCamera = this.cameraOnly && this.SpawnCamera(Cyberblock_FlightPos());
    if this.usingCamera {
      this.anchor = player.GetWorldPosition();
      Cyberblock_SetLookCapture(true);
    }
    Cyberblock_Log(this.usingCamera ? "flight: start (camera only)" : "flight: start (primary)");
    Cyberblock_SetFlightFollowing(true);
    this.Loop(game);
  }

  public func Frame(game: GameInstance, generation: Int32) -> Void {
    if generation != this.generation || !this.flying {
      return;
    }
    let player = GetPlayer(game);
    if !IsDefined(player) {
      this.Clear(game);
      return;
    }
    this.lastFrame = CyberblockEffectsService.Now(game);
    if CyberblockEffects.PlayerLocked(game, player) {
      // A scene or the like took V mid-flight: leave V to it (Stop keeps falls quiet for 2 s).
      this.Stop(game);
      Cyberblock_Log(s"flight: stopped, V is held (\(NameToString(CyberblockEffects.LockReason(game, player))))");
      return;
    }
    let teleport = GameInstance.GetTeleportationFacility(game);
    if !Cyberblock_FlightActive() {
      this.Land(game, player);
      return;
    }
    // Still following (cam.fly): Minecraft calls a glide off when this turns false.
    Cyberblock_SetFlightFollowing(true);
    if this.usingCamera {
      if Vector4.DistanceSquared(player.GetWorldPosition(), this.anchor) > 0.0025 {
        teleport.Teleport(player, this.anchor, Vector4.ToRotation(player.GetWorldForward()));
      }
      this.MoveCamera(game, Cyberblock_FlightPos());
    } else {
      teleport.Teleport(player, Cyberblock_FlightPos(), Vector4.ToRotation(player.GetWorldForward()));
    }
    this.Next(game, generation);
  }

  // The flight is over: Cyberblock_FlightPos() is now the landing spot.
  private func Land(game: GameInstance, player: ref<PlayerPuppet>) -> Void {
    let spot = Cyberblock_FlightPos();
    // A flight that was over at once, or a spot V already stands at (a glide Minecraft called off reports V's own
    // feet), needs no teleport: in-game 2026-10-03 such landings put V 0.4-1 m off its ground.
    if CyberblockEffectsService.Now(game) - this.startTime < 0.3 || Vector4.Distance(spot, player.GetWorldPosition()) < 1.5 {
      this.Stop(game);
      Cyberblock_Log("flight: over, V stays where it is");
      return;
    }
    // Minecraft's barriers round Night City's ground to whole blocks, so the spot can be a little under the real floor:
    // the first surface down from 1.5 m above the spot wins when it is higher. A spot more than 1 m above that surface
    // means Minecraft went silent in mid-air: V goes down to it instead of falling from there.
    let ground: Vector4;
    if CyberblockFlightService.GroundBelow(game, new Vector4(spot.X, spot.Y, spot.Z + 1.0, 1.0), ground)
      && (ground.Z > spot.Z || spot.Z - ground.Z > 1.0) {
      spot = ground;
    }
    let facing = this.usingCamera ? CyberblockEffects.Facing(Cyberblock_FlightYaw()) : Vector4.ToRotation(player.GetWorldForward());
    GameInstance.GetTeleportationFacility(game).Teleport(player, spot, facing);
    this.Stop(game);
    Cyberblock_Log("flight: landed");
  }

  // Stops following. V left in mid-air (primary mode, not a landing) is put on the ground below first.
  public func Clear(game: GameInstance) -> Void {
    if this.flying && !this.usingCamera {
      let player = GetPlayer(game);
      let ground: Vector4;
      if IsDefined(player) && CyberblockFlightService.GroundBelow(game, player.GetWorldPosition(), ground) {
        GameInstance.GetTeleportationFacility(game).Teleport(player, ground, Vector4.ToRotation(player.GetWorldForward()));
      }
    }
    let wasFlying = this.flying;
    this.Stop(game); // cam.fly false, also when nothing was flying
    // A camera of an earlier session (ids do not survive a load) goes too.
    let entities = GameInstance.GetStaticEntitySystem();
    if IsDefined(entities) {
      entities.DespawnTagged(CyberblockFlightService.CameraTag());
    }
    if wasFlying {
      Cyberblock_Log("flight: cleared in mid-flight");
    }
  }

  private func Stop(game: GameInstance) -> Void {
    if this.flying {
      CyberblockEffectsService.Get().SuppressFall(game, 2.0);
    }
    if this.usingCamera {
      this.ReleaseCamera();
      Cyberblock_SetLookCapture(false);
    }
    this.flying = false;
    this.usingCamera = false;
    this.generation += 1;
    Cyberblock_SetFlightFollowing(false);
  }

  private func Loop(game: GameInstance) -> Void {
    this.generation += 1;
    this.lastFrame = CyberblockEffectsService.Now(game);
    this.Next(game, this.generation);
  }

  private func Next(game: GameInstance, generation: Int32) -> Void {
    let frame = new CyberblockFlightFrame();
    frame.game = game;
    frame.generation = generation;
    GameInstance.GetDelaySystem(game).DelayCallbackNextFrame(frame);
  }

  private func SpawnCamera(feet: Vector4) -> Bool {
    let entities = GameInstance.GetStaticEntitySystem();
    if !IsDefined(entities) || !entities.IsReady() {
      Cyberblock_Log("flight: no free camera (entity system not ready), primary mode");
      return false;
    }
    let spec = new StaticEntitySpec();
    spec.templatePath = r"base\\entities\\cameras\\simple_free_camera.ent";
    spec.position = feet + new Vector4(0.0, 0.0, 1.6, 0.0);
    spec.orientation = new Quaternion(0.0, 0.0, 0.0, 1.0);
    spec.attached = true;
    spec.tags = [CyberblockFlightService.CameraTag()];
    this.camera = entities.SpawnEntity(spec);
    this.cameraActive = false;
    if !EntityID.IsDefined(this.camera) {
      Cyberblock_Log("flight: free camera spawn failed, primary mode");
      return false;
    }
    return true;
  }

  // Eye at feet + 1.6 m, looking along Cyberpunk yaw a and Minecraft pitch p (positive = down).
  private func MoveCamera(game: GameInstance, feet: Vector4) -> Void {
    let entity = GameInstance.GetStaticEntitySystem().GetEntity(this.camera);
    if !IsDefined(entity) {
      return; // still spawning
    }
    if !this.cameraActive {
      for component in entity.GetComponents() {
        if component.IsA(n"entMeshComponent") || component.IsA(n"entColliderComponent") {
          component.Toggle(false);
        }
        let view = component as CameraComponent;
        if IsDefined(view) {
          view.Activate(0.0, true);
          this.cameraActive = true;
        }
      }
      if this.cameraActive {
        Cyberblock_Log("flight: free camera active");
      }
    }
    let yaw = Deg2Rad(Cyberblock_FlightYaw());
    let pitch = Deg2Rad(Cyberblock_FlightPitch());
    let look = new Vector4(-SinF(yaw) * CosF(pitch), CosF(yaw) * CosF(pitch), -SinF(pitch), 0.0);
    let orientation = Quaternion.BuildFromDirectionVector(look, new Vector4(0.0, 0.0, 1.0, 0.0));
    let eye = feet + new Vector4(0.0, 0.0, 1.6, 0.0);
    let object = entity as GameObject;
    if IsDefined(object) {
      GameInstance.GetTeleportationFacility(game).Teleport(object, eye, Quaternion.ToEulerAngles(orientation));
    } else {
      let transform: WorldTransform;
      WorldTransform.SetPosition(transform, eye);
      WorldTransform.SetOrientation(transform, orientation);
      entity.SetWorldTransform(transform);
    }
  }

  private func ReleaseCamera() -> Void {
    let entities = GameInstance.GetStaticEntitySystem();
    if !EntityID.IsDefined(this.camera) || !IsDefined(entities) {
      return;
    }
    let entity = entities.GetEntity(this.camera);
    if IsDefined(entity) && this.cameraActive {
      for component in entity.GetComponents() {
        let view = component as CameraComponent;
        if IsDefined(view) {
          view.Deactivate(0.0, true);
        }
      }
    }
    entities.DespawnEntity(this.camera);
    let none: EntityID;
    this.camera = none;
    this.cameraActive = false;
    Cyberblock_Log("flight: camera released");
  }

  private static func GroundBelow(game: GameInstance, from: Vector4, out ground: Vector4) -> Bool {
    let filter: QueryFilter;
    QueryFilter.AddGroup(filter, n"Static");
    QueryFilter.AddGroup(filter, n"Terrain");
    let result: TraceResult;
    let top = new Vector4(from.X, from.Y, from.Z + 0.5, 1.0);
    let bottom = new Vector4(from.X, from.Y, from.Z - 1000.0, 1.0);
    if GameInstance.GetSpatialQueriesSystem(game).SyncRaycastByQueryFilter(top, bottom, filter, result) {
      let hit = Cast<Vector4>(result.position);
      ground = new Vector4(hit.X, hit.Y, hit.Z, 1.0);
      return true;
    }
    return false;
  }
}
