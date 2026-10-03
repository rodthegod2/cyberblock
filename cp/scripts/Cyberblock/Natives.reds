// Natives.reds — declarations only; implemented by Cyberblock.dll
public native func Cyberblock_Version() -> String;              // "0.1.0"
public native func Cyberblock_IsLinked() -> Bool;              // WebSocket open and hello exchanged
public native func Cyberblock_SecondsSinceFrame() -> Float;    // since the last shared-memory frame or status; 999 if never
public native func Cyberblock_ScreenOpen() -> Bool;            // last status.screen
public native func Cyberblock_ConsumeToggle() -> Bool;         // true once per F9 press (caught by the DLL's window hook)
public native func Cyberblock_SetActive(active: Bool, inVehicle: Bool) -> Void;  // sends state/hud/clear, gates cam + compositor + input
public native func Cyberblock_SubmitProbes(center: Vector4, downHits: array<Vector4>, wallHits: array<Vector4>, radius: Float) -> Void;
                                                               // Cyberpunk-space hits; DLL converts, diffs, sends ground/unground
public native func Cyberblock_SetAim(distance: Float) -> Void; // metres to the Night City surface under the crosshair, -1 if none
public native func Cyberblock_Log(msg: String) -> Void;        // to red4ext/logs/Cyberblock.log
public native func Cyberblock_Damage(frac: Float, kind: CName, from: Vector4, hasFrom: Bool) -> Void;
public native func Cyberblock_SubmitNpcs(ids: array<Uint64>, feet: array<Vector4>, yaws: array<Float>, vehicles: array<Bool>, hostile: array<Bool>) -> Void;
// Event queue (Minecraft -> Cyberpunk), already converted to Cyberpunk space. NextEvent pops one and makes it current.
public native func Cyberblock_NextEvent() -> CName;      // n"explosion" | n"hit" | n"ignite" | n"block_add" | n"block_remove" | n"" when empty
public native func Cyberblock_EventPos() -> Vector4;     // explosion centre; block AABB min corner
public native func Cyberblock_EventTarget() -> Uint64;   // hit/ignite target (EntityID hash)
public native func Cyberblock_EventAmount() -> Float;    // explosion power | hit dmg/20 (fraction of target max health) | ignite seconds
public native func Cyberblock_EventVector() -> Vector4;  // hit impulse (Cyberpunk space, m/s)
public native func Cyberblock_EventFlag() -> Bool;       // explosion fire
// ---- layers 3-6 (plan 2026-10-03 section 1.3) ----
// Event fields beyond layer 2's (the current event, after Cyberblock_NextEvent):
public native func Cyberblock_EventName() -> CName;     // effect name | hack name | perk id | perkfx id
public native func Cyberblock_EventExtra() -> Float;    // knock strength | effect amplifier | (else 0)
public native func Cyberblock_EventId() -> Int32;       // hit: mob id (-1 none) | hack/scan: request id
// Stance, menus, game mode
public native func Cyberblock_SetStance(crouched: Bool) -> Void;     // carried as cam.cr until changed
public native func Cyberblock_SetMenu(open: Bool) -> Void;           // a Cyberpunk menu is open: input forwarding pauses
public native func Cyberblock_SetChoices(open: Bool) -> Void;        // 2+ dialogue choices shown: E, Q, 1-4, wheel stay Cyberpunk's
public native func Cyberblock_GameMode() -> CName;                   // last status.gm, n"" before any status
// Layer 3 mobs: Cyberblock_MobCount takes a snapshot of the latest mobs list that the getters read (i in 0..count-1)
public native func Cyberblock_MobCount() -> Int32;
public native func Cyberblock_MobId(i: Int32) -> Int32;
public native func Cyberblock_MobKind(i: Int32) -> CName;            // n"zombie", n"skeleton", ...
public native func Cyberblock_MobPos(i: Int32) -> Vector4;           // feet, Cyberpunk space
public native func Cyberblock_MobYaw(i: Int32) -> Float;             // Cyberpunk yaw, degrees
public native func Cyberblock_MobSize(i: Int32) -> Vector4;          // X width, Y height, Z hp, W max hp
public native func Cyberblock_MobDamage(id: Int32, frac: Float, by: Uint64, hasBy: Bool) -> Void;   // -> mobdmg
// Layer 4 flight (latest flight message; position extrapolated by v * min(age, 0.1 s); the landing spot once inactive)
public native func Cyberblock_FlightActive() -> Bool;
public native func Cyberblock_FlightSeq() -> Int32;                 // +1 on every start and every landing
public native func Cyberblock_FlightPos() -> Vector4;               // feet, Cyberpunk space
public native func Cyberblock_FlightVelocity() -> Vector4;          // m/s, Cyberpunk space
public native func Cyberblock_FlightYaw() -> Float;                 // Cyberpunk yaw, degrees
public native func Cyberblock_FlightPitch() -> Float;               // Minecraft pitch, degrees (positive = down)
public native func Cyberblock_SetLookCapture(on: Bool) -> Void;     // camera-only fallback: raw mouse -> look deltas
// Layer 4 projectiles: Cyberblock_ProjCount takes a snapshot
public native func Cyberblock_ProjCount() -> Int32;
public native func Cyberblock_ProjPos(i: Int32) -> Vector4;         // Cyberpunk space
public native func Cyberblock_ProjVelocity(i: Int32) -> Vector4;    // m/s, Cyberpunk space
public native func Cyberblock_ProjGravity(i: Int32) -> Float;       // m/s^2, along -Z
public native func Cyberblock_SubmitProjectileHits(hits: array<Vector4>) -> Void;  // Cyberpunk-space surface points -> pcells
// Layer 5
public native func Cyberblock_Unlead(target: Uint64) -> Void;                       // -> unlead
// Layer 6
public native func Cyberblock_ScanResult(id: Int32, ok: Bool, target: Uint64, name: String, faction: String, threat: CName, hp: Float, device: Bool, pos: Vector4, size: Vector4) -> Void;
public native func Cyberblock_HackResult(id: Int32, ok: Bool, reason: String) -> Void;
public native func Cyberblock_Killed(target: Uint64, vehicle: Bool, hostile: Bool) -> Void;
