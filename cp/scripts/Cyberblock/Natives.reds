// Natives.reds — declarations only; implemented by Cyberblock.dll (version 2.0.0)
public native func Cyberblock_Version() -> String;              // "2.0.0"
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
public native func Cyberblock_SubmitNpcs(ids: array<Uint64>, feet: array<Vector4>, yaws: array<Float>, vehicles: array<Bool>, hostile: array<Bool>, dead: array<Bool>) -> Void;
// Event queue (Minecraft -> Cyberpunk), already converted to Cyberpunk space. NextEvent pops one and makes it current.
public native func Cyberblock_NextEvent() -> CName;      // n"explosion" | n"hit" | n"ignite" | n"block_add" | n"block_remove" | ...
                                                         // (Events.reds lists every kind) | n"" when empty
public native func Cyberblock_EventPos() -> Vector4;     // explosion centre; block AABB min corner (z = block y + k/64)
public native func Cyberblock_EventTarget() -> Uint64;   // hit/ignite target (EntityID hash)
public native func Cyberblock_EventAmount() -> Float;    // explosion power | hit dmg/20 (fraction of target max health) | ignite seconds
                                                         // | cyber 1 installed, 0 not | cyberfx seconds (0 = end)
public native func Cyberblock_EventVector() -> Vector4;  // hit impulse (Cyberpunk space, m/s)
public native func Cyberblock_EventFlag() -> Bool;       // explosion fire
// ---- layers 3-6 (plan 2026-10-03 section 1.3) ----
// Event fields beyond layer 2's (the current event, after Cyberblock_NextEvent):
public native func Cyberblock_EventName() -> CName;     // effect name | hack name | perk id | perkfx id | cyberware id | cyberfx id
public native func Cyberblock_EventExtra() -> Float;    // knock strength | effect amplifier | (else 0)
public native func Cyberblock_EventId() -> Int32;       // hit: mob id (-1 none) | hack/scan: request id
// ---- Phase 1 (megaplan 2026-10-04 section 1.8): n"session" events (name = state, text = reason, extra = perms bits) ----
public native func Cyberblock_EventText() -> String;    // the current event's string payload: session reason | (else "")
// ---- Phase 3 (megaplan 2026-10-04 section 1.8 + amendment item 4, task 3-S1) ----
// Events, filled by the DLL (package 3D) from `vteleport` / `park` (1.9), read with the getters above:
//   n"vteleport": EventPos = target feet (Cyberpunk space), EventAmount = yaw to face (Cyberpunk degrees, as n"teleport"),
//                 EventId = request id, EventName = why (n"meetup" | n"holo" | n"hub_exit"); answered by
//                 Cyberblock_TeleportDone (declared in Teleport.reds).
//   n"park":      EventFlag = on.
// Natives declared where they are called (rule 0.14): Cyberblock_SubmitProbeMaterials (Probes.reds), Cyberblock_SetEnv
// (Env.reds), Cyberblock_TeleportDone (Teleport.reds).
// ---- Phase 4 (megaplan 2026-10-04 section 1.8 + amendment items 5-6, task 4-S1) ----
// Event, filled by the DLL (package 4D) from `devact` (1.9), read with the getters above:
//   n"devact": EventId = request id, EventTarget = device id (the id Cyberblock_SubmitDevices sent),
//              EventName = act (n"open" | n"close" | n"toggle" | n"lock" | n"unlock" | n"on" | n"off" | n"disable" |
//              n"enable" | n"distract" | n"friendly"; anything else is answered "unsupported"); answered by
//              Cyberblock_DeviceResult whatever the state.
// Natives declared in Devices.reds (section 1.8): Cyberblock_SubmitDevices, Cyberblock_DeviceState, Cyberblock_DeviceResult.
// ---- Phase 6 (megaplan 2026-10-04 section 1.8 row 6, section 1.9 rows `rhack` / `daemon`, task 6-S1) ----
// Events, filled by the DLL (package 6D), read with the getters above; no new native: both are answered with
// Cyberblock_HackResult whatever the state (the reply travels as `hackresult`), reveals with Cyberblock_HackSpread `ping`:
//   n"rhack":  EventId = request id, EventName = hack (n"veh_lock" | n"veh_engine_off" | n"veh_brake" | n"veh_start" |
//              n"veh_jam" | n"traffic_switch" | n"gridlock" | n"blackout" | n"cam_feed" | n"outline" | n"ping_area";
//              anything else is answered "no such hack"), EventTarget = target id (a car, person or device id as the npcs
//              / devices streams sent it; 0 = none), EventPos = the wire's `p` converted to Cyberpunk space (area hacks
//              centre on it; zero = absent, they centre on V), EventExtra = the wire's `r` in metres (blackout uses
//              min(r, 150); 0 = absent, 150), EventAmount = the wire's `secs` in SECONDS (blackout: 0 = absent, 120 s;
//              clamped 30..600), EventText = a district name (blackout; logged only). Refusals are free text <= 64
//              characters; the Phase 0 (h) 50 m limit answers exactly "cyberblock.hack.out_of_range", a target that is
//              not streamed "target gone".
//   n"daemon": EventId = request id, EventTarget = the breached device id, EventAmount = strength 1..3, EventText = the
//              daemon ids joined with "," (when EventText is empty, EventName is read as the only one); the daemon id
//              n"alarm" is a failed Breach (alarm and heat). Radius and duration per strength are the shipped daemon
//              content's tiers (Daemons.reds); an unknown id is answered "unsupported daemon" while the rest still run.
// Handled in RemoteHacks.reds and Daemons.reds.
// ---- Phase 8 (megaplan 2026-10-04 section 1.8 row 8, section 1.9 rows `spawn` / `heat` / `attitude` / `glitch` with the
// Phase 0 amendment item 8, task 8-S1) ----
// Events, filled by the DLL (package 8D) from the DOWN messages, read with the getters above, handed over whatever the
// state (each handler decides what it does while not live):
//   n"spawn":    EventId = request id, EventName = rec (n"ganger" | n"ncpd" | n"ncpd_heavy" | n"maxtac" | n"drone"; no
//                turret; anything else is answered "unsupported rec <rec>"), EventPos = the wire's `p` converted to
//                Cyberpunk space (feet), EventAmount = `n` 1..8, EventFlag = `hostile`, EventText = `faction` ("" none).
//                Answered with Cyberblock_SpawnResult (declared in Spawns.reds; sent UP as `spawnresult {id, ok, n,
//                reason}`) whatever the state.
//   n"heat":     EventAmount = `tier` 0..5.
//   n"attitude": EventName = `faction`, EventText = `toward` (n"friendly" | "neutral" | "hostile"); the scripts also take
//                the two the other way round, or EventAmount's sign (+1 friendly, 0 neutral, -1 hostile) for `toward`
//                when EventText is empty (section 1.8 says "faction, delta").
//   n"glitch":   EventAmount = `seconds` 0..10 (0 ends it).
// Handled in Spawns.reds, Heat.reds and Glitch.reds.
// Stance, menus, game mode
public native func Cyberblock_SetStance(crouched: Bool, inAir: Bool, held: Bool) -> Void; // carried as cam.cr / cam.air / cam.held until changed
public native func Cyberblock_SetMenu(open: Bool) -> Void;           // a Cyberpunk menu is open: input forwarding pauses; cam.menu
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
// Layer 4 flight (latest flight message; position extrapolated by v * min(age, 0.05 s); the landing spot once inactive).
// Since re-test 2 the DLL moves V itself every rendered frame (plan 2026-10-03-retest2 sections 1.3 and 1.4), and the
// getters below are no longer called by Flight.reds; they stay for the CET console and a follow-up.
public native func Cyberblock_FlightActive() -> Bool;              // a sample older than 0.5 s counts as ended (Flight.reds' 'ignored' log)
public native func Cyberblock_FlightSeq() -> Int32;                 // +1 on every start and every end (landing, 0.5 s timeout, reset)
public native func Cyberblock_FlightTimedOut() -> Bool;             // the last flight ended by the 0.5 s timeout, not a landing
public native func Cyberblock_FlightPos() -> Vector4;               // feet, Cyberpunk space
public native func Cyberblock_FlightVelocity() -> Vector4;          // m/s, Cyberpunk space
public native func Cyberblock_FlightYaw() -> Float;                 // Cyberpunk yaw, degrees
public native func Cyberblock_FlightPitch() -> Float;               // Minecraft pitch, degrees (positive = down)
public native func Cyberblock_SetLookCapture(on: Bool) -> Void;     // raw mouse -> look deltas (dead until a follow-up)
// ---- re-test 2 (plan 2026-10-03-retest2 section 1.3) ----
// The DLL drive (cb::drive, every rendered frame) is the only writer of cam.fly; Cyberblock_SetFlightFollowing is gone.
public native func Cyberblock_SetFlightAllowed(allowed: Bool) -> Void; // 10 Hz from CyberblockFlight.Tick: V may be moved (live, not mounted, not locked). Cleared by Clear and by SetActive(false)
public native func Cyberblock_FlightDriving() -> Bool;                 // the DLL is moving V this flight (same value as cam.fly)
public native func Cyberblock_SecondsSinceFlight() -> Float;           // 0 while driving, seconds since the last flight ended, 999 if none yet
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
public native func Cyberblock_Killed(target: Uint64, vehicle: Bool, hostile: Bool) -> Void;
// ---- re-test 3 (plan 2026-10-03-retest3-polish section 1.2) ----
public native func Cyberblock_HackResult(id: Int32, ok: Bool, reason: String, upload: Float, kind: CName) -> Void; // CHANGED: upload clamped 0..30, kind checked against kHackKinds (n"" allowed when !ok)
public native func Cyberblock_HackSpread(id: Int32, target: Uint64, why: CName) -> Void;   // why in chain|spread|ping|frenzy; ≤ 16 per id
public native func Cyberblock_SetSeat(seated: Bool, tpp: Bool, hipsLocal: Vector4) -> Void; // 10 Hz and on camera switch; hipsLocal in V's local axes (Y forward, Z up), metres
public native func Cyberblock_SetSeatForward(forward: Vector4) -> Void; // the car's forward while seated (Steve faces it)
public native func Cyberblock_SetSlowMoFx(id: CName, seconds: Float) -> Void;               // n"sandevistan"|n"kerenzikov", 0..30; n"" / 0 ends it
public native func Cyberblock_SetViewAllowed(allowed: Bool) -> Void;                        // third person may be applied (Active on foot, not held, no menu, no braindance, no photo mode)
public native func Cyberblock_ViewEye() -> Vector4;                                         // V's first-person eye (the camera without the third-person offset)
