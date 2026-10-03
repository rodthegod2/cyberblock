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
