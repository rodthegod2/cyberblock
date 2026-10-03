// Natives.reds — declarations only; implemented by Cyberblock.dll
public native func Cyberblock_Version() -> String;              // "0.1.0"
public native func Cyberblock_IsLinked() -> Bool;              // WebSocket open and hello exchanged
public native func Cyberblock_SecondsSinceFrame() -> Float;    // since the last shared-memory frame or status; 999 if never
public native func Cyberblock_ScreenOpen() -> Bool;            // last status.screen
public native func Cyberblock_ConsumeToggle() -> Bool;         // true once per F9 press (caught by the DLL's window hook)
public native func Cyberblock_SetActive(active: Bool, inVehicle: Bool) -> Void;  // sends state/hud/clear, gates cam + compositor + input
public native func Cyberblock_SubmitProbes(center: Vector4, downHits: array<Vector4>, wallHits: array<Vector4>, radius: Float) -> Void;
                                                               // Cyberpunk-space hits; DLL converts, diffs, sends ground/unground
public native func Cyberblock_Log(msg: String) -> Void;        // to red4ext/logs/Cyberblock.log
