// Minecraft's hack key scan (plan 2026-10-03 sections 1.2 scaninfo and 1.4, task S3.2): what Night City has at the
// requested id (0: whatever V looks at). Every request is answered once with Cyberblock_ScanResult; ok false with empty
// strings when nothing scannable is there.
//
// 2.31 sources (decompiled 2.31 scripts; compile-checked):
//   Look-at: TargetingSystem.GetLookAtObject(V, withLOS true, ignoreTransparent true), the call PlayerPuppet
//     .ReevaluateLookAtTarget makes for V's own look-at target. When it gives nothing, or nothing Minecraft can show,
//     TargetingSystem.GetObjectClosestToCrosshair(V, out angle distance, TargetSearchQuery) is asked, as
//     kiroshiHighlightEffector and HUDManager.LookAtNearestCroshairTarget do: query.maxDistance 48 m, the default
//     testedSet (TargetingSet.Visible), and the hit is kept only within 4 degrees of the crosshair (|Yaw| and |Pitch|
//     of the EulerAngles distance). The first query uses vanilla's quickhack filter TSF_Quickhackable(); its
//     St_QuickHackable state follows IsQuickHackAble, which QuickhackModule.IsQuickhackBlockedByScene turns off for
//     everything while V carries NoCombat's NoQuickHacks tag (Cyberblock Active, PlayerState.reds), so a second query
//     takes any target but V (TSF_Not(TSFMV.Obj_Player)). The log names what refused: "nothing under the crosshair"
//     or "not scannable: <class name>" (IScriptable.GetClassName).
//   NPC name: the scanner's rules in NPCPuppet's scanner data (ScannerName chunk): alternative names when
//     GamePuppetPS.HasAlternativeName; civilians use Character_Record.DisplayName; others FullDisplayName, then
//     DisplayName; GameObject.GetDisplayName as the last resort. Loc keys resolve with GetLocalizedTextByKey and
//     "LocKey#" strings with GetLocalizedText (as craftingMain / ButtonHints do).
//   Faction: Character_Record.Affiliation().LocalizedName() (the scanner's ScannerFaction chunk).
//   Threat: RPGManager.CalculatePowerDifferential(target) -> gameEPowerDifferential, the helper the scanner's bounty
//     level uses: TRASH and EASY -> low, NORMAL -> medium, HARD -> high, IMPOSSIBLE -> extreme. "none" for NPCs that
//     are not aggressive (ScriptedPuppet.IsAggressive: civilians), not active (dead, defeated, unconscious), devices,
//     and vehicles without an active aggressive driver (VehicleComponent.GetDriverMounted).
//   HP: StatPoolsSystem.GetStatPoolValue(id, Health, perc true) is 0..100 (AdamSmasherComponent compares it to 50 and
//     30); 1 when the object has no Health pool (IsStatPoolAdded), 0 when dead or destroyed.
//   Vehicle name: Vehicle_Record.DisplayName (the scanner's ScannerVehicleName). Device name: GameObject
//     .GetDisplayName, else Device.GetDeviceName (Device.GetScannerName's order).
//   Size: humans 0.6 x 1.8 m, vehicles 2 x 1.6 m, devices 1 x 2 m. No script API exposes a vehicle's bounding box in
//     2.31 (the only one, vehicleBoundingBox, lives in the native vehicle camera context), so the plan's fallback is
//     used for every vehicle.
//   Text limit: 64 characters (Codeware UTF8StrLeft, so a multi-byte letter is never cut in half).
//   Line of sight for a requested target: Minecraft's pick only knows the Night City surface within 8 m of the crosshair
//     (Probes.reds Aim, the aim message), so it can pick a proxy 8-48 m away behind a wall. Such a target is answered
//     ok:false (and so never locked for a quickhack) when Night City (CyberblockProbes.Blocked: Static + Terrain, not
//     Minecraft's blocks) cuts both lines from the active camera (CameraSystem.GetActiveCameraWorldTransform, as Probes
//     .Aim) toward the target's middle and its top, each stopping short of the target (0.5 m, cars 2.5 m) so its own
//     body never counts. The look-at path already asks GetLookAtObject for line of sight.

public abstract class CyberblockScan {
  public static func Scan(game: GameInstance, id: Int32, target: Uint64) -> Void {
    let player = GetPlayer(game);
    let why = "no player";
    if IsDefined(player) {
      if target == 0ul {
        let looked = GameInstance.GetTargetingSystem(game).GetLookAtObject(player, true, true);
        if CyberblockScan.Try(game, id, player, looked) {
          return;
        }
        let near = CyberblockScan.NearCrosshair(game, player);
        if CyberblockScan.Try(game, id, player, near) {
          return;
        }
        why = CyberblockScan.Refused(player, looked, near);
      } else {
        let object = GameInstance.FindEntityByID(game, EntityID.FromHash(target)) as GameObject;
        if !IsDefined(object) {
          why = "target gone";
        } else {
          if !CyberblockScan.InSight(game, object) {
            why = "behind Night City";
          } else {
            if CyberblockScan.Try(game, id, player, object) {
              return;
            }
            why = CyberblockScan.Refused(player, object, null);
          }
        }
      }
    }
    Cyberblock_ScanResult(id, false, target, "", "", n"none", 0.0, false, new Vector4(0.0, 0.0, 0.0, 1.0), new Vector4(0.0, 0.0, 0.0, 0.0));
    Cyberblock_Log(s"scan \(id): \(why) at \(target)");
  }

  // Answers the scan with object when it is something Minecraft can show (never V herself).
  private static func Try(game: GameInstance, id: Int32, player: ref<PlayerPuppet>, object: ref<GameObject>) -> Bool {
    return IsDefined(object) && object != player && CyberblockScan.Answer(game, id, object);
  }

  // The log's reason for a scan that found nothing to show.
  private static func Refused(player: ref<PlayerPuppet>, first: ref<GameObject>, second: ref<GameObject>) -> String {
    if IsDefined(first) && first != player {
      return s"not scannable: \(NameToString(first.GetClassName()))";
    }
    if IsDefined(second) && second != player {
      return s"not scannable: \(NameToString(second.GetClassName()))";
    }
    return "nothing under the crosshair";
  }

  // The fallback when V's look-at target is missing or nothing Minecraft can show: the object closest to the crosshair
  // within 48 m and 4 degrees (see the header), first among vanilla's quickhack targets, then among any target but V.
  private static func NearCrosshair(game: GameInstance, player: ref<PlayerPuppet>) -> ref<GameObject> {
    let query: TargetSearchQuery;
    query.maxDistance = 48.0;
    query.searchFilter = TSF_Quickhackable();
    let found = CyberblockScan.ClosestInCone(game, player, query);
    if IsDefined(found) {
      return found;
    }
    query.searchFilter = TSF_Not(TSFMV.Obj_Player);
    return CyberblockScan.ClosestInCone(game, player, query);
  }

  private static func ClosestInCone(game: GameInstance, player: ref<PlayerPuppet>, query: TargetSearchQuery) -> ref<GameObject> {
    let angle: EulerAngles;
    let object = GameInstance.GetTargetingSystem(game).GetObjectClosestToCrosshair(player, angle, query);
    if IsDefined(object) && AbsF(angle.Yaw) < 4.0 && AbsF(angle.Pitch) < 4.0 {
      return object;
    }
    return null;
  }

  // The camera sees the target's middle or its top past Night City (see the header).
  private static func InSight(game: GameInstance, object: ref<GameObject>) -> Bool {
    let view: Transform;
    if !GameInstance.GetCameraSystem(game).GetActiveCameraWorldTransform(view) {
      return true;
    }
    let eye = Transform.GetPosition(view);
    let feet = object.GetWorldPosition();
    let vehicle = IsDefined(object as VehicleObject);
    let height = vehicle ? 1.6 : 1.8;
    let margin = vehicle ? 2.5 : 0.5;
    return CyberblockScan.ClearLine(game, eye, new Vector4(feet.X, feet.Y, feet.Z + 0.5 * height, 1.0), margin)
      || CyberblockScan.ClearLine(game, eye, new Vector4(feet.X, feet.Y, feet.Z + 0.9 * height, 1.0), margin);
  }

  // No Night City on the line from the eye toward a point of the target, up to `margin` metres short of the point, so
  // the target's own body (a car is up to 2.3 m from its middle to its ends) never counts as a wall.
  private static func ClearLine(game: GameInstance, eye: Vector4, point: Vector4, margin: Float) -> Bool {
    let d = Vector4.Distance(eye, point);
    if d <= margin {
      return true;
    }
    let along = (d - margin) / d;
    let end = new Vector4(eye.X + (point.X - eye.X) * along, eye.Y + (point.Y - eye.Y) * along, eye.Z + (point.Z - eye.Z) * along, 1.0);
    return !CyberblockProbes.Blocked(game, eye, end);
  }

  // Sends the scan of object; false when it is nothing Minecraft can show (not an NPC, vehicle or device).
  private static func Answer(game: GameInstance, id: Int32, object: ref<GameObject>) -> Bool {
    let hash = CyberblockIds.Hash(object);
    let pos = object.GetWorldPosition();
    let puppet = object as ScriptedPuppet;
    if IsDefined(puppet) {
      let record = puppet.GetRecord();
      let faction = "";
      if IsDefined(record) && IsDefined(record.Affiliation()) {
        faction = CyberblockScan.Text(record.Affiliation().LocalizedName());
      }
      Cyberblock_ScanResult(id, true, hash, CyberblockScan.Clip(CyberblockScan.NpcName(puppet)), CyberblockScan.Clip(faction), CyberblockScan.NpcThreat(puppet), CyberblockScan.Hp(game, object), false, pos, new Vector4(0.6, 1.8, 0.0, 0.0));
      return true;
    }
    let vehicle = object as VehicleObject;
    if IsDefined(vehicle) {
      let name = "";
      if IsDefined(vehicle.GetRecord()) {
        name = CyberblockScan.Text(vehicle.GetRecord().DisplayName());
      }
      let threat = n"none";
      let driver = VehicleComponent.GetDriverMounted(game, vehicle.GetEntityID()) as ScriptedPuppet;
      if IsDefined(driver) {
        threat = CyberblockScan.NpcThreat(driver);
      }
      let hp = vehicle.IsDestroyed() ? 0.0 : CyberblockScan.Hp(game, object);
      Cyberblock_ScanResult(id, true, hash, CyberblockScan.Clip(name), "", threat, hp, false, pos, new Vector4(2.0, 1.6, 0.0, 0.0));
      return true;
    }
    let device = object as Device;
    if IsDefined(device) {
      let name = CyberblockScan.Localize(device.GetDisplayName());
      if StrLen(name) == 0 && IsDefined(device.GetDevicePS()) {
        name = CyberblockScan.Localize(device.GetDeviceName());
      }
      Cyberblock_ScanResult(id, true, hash, CyberblockScan.Clip(name), "", n"none", CyberblockScan.Hp(game, object), true, pos, new Vector4(1.0, 2.0, 0.0, 0.0));
      return true;
    }
    return false;
  }

  // The scanner's NPC name rules (NPCPuppet scanner data, ScannerName).
  private static func NpcName(puppet: ref<ScriptedPuppet>) -> String {
    let record = puppet.GetRecord();
    if !IsDefined(record) {
      return CyberblockScan.Localize(puppet.GetDisplayName());
    }
    let ps = puppet.GetPuppetPS();
    if IsDefined(ps) && ps.HasAlternativeName() {
      if IsNameValid(record.AlternativeFullDisplayName()) {
        return CyberblockScan.Text(record.AlternativeFullDisplayName());
      }
      return CyberblockScan.Text(record.AlternativeDisplayName());
    }
    if !puppet.IsCharacterCivilian() && IsNameValid(record.FullDisplayName()) {
      return CyberblockScan.Text(record.FullDisplayName());
    }
    if IsNameValid(record.DisplayName()) {
      return CyberblockScan.Text(record.DisplayName());
    }
    return CyberblockScan.Localize(puppet.GetDisplayName());
  }

  private static func NpcThreat(puppet: ref<ScriptedPuppet>) -> CName {
    if !ScriptedPuppet.IsActive(puppet) || !puppet.IsAggressive() {
      return n"none";
    }
    switch RPGManager.CalculatePowerDifferential(puppet) {
      case gameEPowerDifferential.IMPOSSIBLE:
        return n"extreme";
      case gameEPowerDifferential.HARD:
        return n"high";
      case gameEPowerDifferential.NORMAL:
        return n"medium";
      default:
        return n"low";
    }
  }

  // Fraction 0..1 of the Health pool; 1 for objects without one, 0 for the dead.
  private static func Hp(game: GameInstance, object: ref<GameObject>) -> Float {
    let id = Cast<StatsObjectID>(object.GetEntityID());
    let pools = GameInstance.GetStatPoolsSystem(game);
    if !pools.IsStatPoolAdded(id, gamedataStatPoolType.Health) {
      return 1.0;
    }
    return ClampF(pools.GetStatPoolValue(id, gamedataStatPoolType.Health, true) / 100.0, 0.0, 1.0);
  }

  private static func Text(key: CName) -> String {
    if !IsNameValid(key) {
      return "";
    }
    return GetLocalizedTextByKey(key);
  }

  private static func Localize(text: String) -> String {
    if StrBeginsWith(text, "LocKey#") {
      return GetLocalizedText(text);
    }
    return text;
  }

  private static func Clip(text: String) -> String {
    if UTF8StrLen(text) > 64 {
      return UTF8StrLeft(text, 64);
    }
    return text;
  }
}
