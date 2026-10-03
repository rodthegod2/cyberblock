# Flight: V follows Minecraft's glide and launch, every frame (spike S2.4, rewritten for re-test 2)

Question (plan 2026-10-03, S2.4): while Steve glides, Minecraft's physics move him. Can Cyberpunk put V there every
rendered frame, keep the mouse look working, and land V cleanly? Re-test 2 (R1 and R5) added a second source of
flight, the **launch** (a wind charge or a mace burst throws Steve), and found that the first answer did not work.

**Answer (current method, re-test 2): the DLL drives V.**
- **Where:** `cp/plugin/src/drive.cpp`, called from RED4ext's Running-state `OnUpdate` every rendered frame, just
  before the camera is published (the place that already publishes the camera every frame).
- **How:** it teleports V with `gameTeleportationFacility::Teleport(V, position, ToRotation(V's forward))`, so the body
  keeps its own yaw and the camera keeps its pitch: the mouse look is untouched.
- **What stays in redscript:** `cp/scripts/Cyberblock/Flight.reds` only decides, at 10 Hz, whether V *may* be moved
  (`Cyberblock_SetFlightAllowed`), clears the drive, and owns the fall-damage rules (landing wraps, `Health.reds`).
- **Gone:** the 10 Hz-restarted script loop, `Cyberblock_SetFlightFollowing`, and the camera-only fallback
  (`SetCameraOnly`, the free camera entity). Nothing calls `Cyberblock_SetLookCapture` any more; the native and the
  `look` message stay for a follow-up.

Everything compiles against the 2.31 scripts plus the RED4ext SDK (`cp/scripts/compile_check.py`, `cb_tests`).
Smoothness, the landing behaviour and the `Teleport`-from-`OnUpdate` call can only be judged in game: the checks are at
the end.

## Why the script loop was replaced (R1-a)

The first method queued `DelaySystem.DelayCallbackNextFrame` from inside its own callback so that a script ran once per
frame. In 2.31 that re-queue **never fires**: the callback runs one frame and stops, and only the 10 Hz watchdog
(`CyberblockFlight.Tick`) restarts it, about once per 1.05 s. The log of the re-test (`Cyberblock.log`, 2026-10-03)
shows it as flights that last a whole number of watchdog periods:

| `flight: start (primary)` | end line | length | in watchdog periods |
|---|---|---|---|
| 15:07:15.078 | 15:07:18.217 `over, V stays where it is` | 3.139 s | 3 x 1.046 s |
| 15:08:49.762 | 15:09:14.014 `V stopped short of Night City geometry ...` | 24.252 s | 23 x 1.054 s |
| 15:15:58.979 | 15:16:00.015 `landed` | 1.036 s | 1 x 1.036 s |

Minecraft's own log of the same flights (`glide started ... with 6.0 m/s`, `glide ended ...`) shows glides of 30 m and
about 400 m. Consequences, all matching the user's report ("it teleports you, the camera only updates every few
seconds"):
- V and the first-person camera jumped about **17 m per second** at glide speed, once per watchdog period.
- The landing was decided from a position up to **1 s old**, so V could be left short of the real spot, possibly in
  mid-air, and Steve snapped back to V after the 0.5 s settle.
- A wind-charge launch (R5) could never have worked on top of it: V could only follow through that broken loop.

## Method

### Samples (`snapshots.cpp`, `flight` messages)
- `flight` carries `kind` (`glide` or `launch`) and `ts` (Minecraft's sample clock in ms); see `shared/protocol.md`.
- The DLL keeps a ring of the last 32 `{sample, local}` entries. With `ts`: `local = ts + offset`, where the offset is the
  minimum of (arrival − `ts`) over the ring (so network jitter does not move the clock); without `ts`: `local = arrival`.
- `Flight::render_pos(now)`: it plays the stream out `D = clamp(1.5 x median spacing, 8 ms, 60 ms)` behind (`u = now − D`),
  interpolates linearly between the two samples around `u`, and past the newest sample extrapolates `v x min(u − newest, 50 ms)`.
  Inactive: the landing spot. The ring is cleared on every active change.

### Follower (`follow.cpp`, pure, unit-tested)
States Idle, Following, Holding. Constants: hold 1.0 s, quick landing 0.3 s, near landing 1.5 m, clip margin 0.3 m.
- **Start:** Idle, gate open, flight active, no menu: Following, move to the target, log `flight: start (glide|launch)`.
- **Gate closes** while Following or Holding: Stop with **no teleport**, log
  `flight: stopped, V is held, in a vehicle or Cyberblock is off`.
- **Menu open:** nothing happens and the timers are moved forward by the frame time (frozen).
- **Active:** move to the target; Holding becomes Following (`flight: samples back`).
- **Landing** (`active:false`, not timed out): Stop (`flight: over, V stays where it is`) when the flight lasted under 0.3 s
  or the target is within 1.5 m of V's feet; otherwise Land.
- **Timeout** (samples stopped without `active:false`): hold V at the last position for up to 1 s
  (`flight: no samples, holding V`), then put V on the ground below (`flight: no samples for 1 s, V put on the ground`).
- Land, Ground and Stop end the flight and return to Idle.

### Drive (`drive.cpp`, game-facing)
- **Gate:** the DLL is active, V is not in a vehicle and not held, `flightAllowed` is set by the script, the link is up,
  and the player resolves. It keeps ticking while the game is paused; with a menu open it does nothing.
- **Move:** two rays (Static, then Terrain) from the last position +0.9 m to the target +0.9 m; on a hit V stops 0.3 m short
  (`flight: V stopped short of Night City geometry`, once per flight); then `Teleport`, and `cam.fly` becomes true on the
  first frame V is moved.
- **Land:** the ground below the (clipped) spot: a ray down from spot +1.5 m to spot −1000 m, Static and Terrain, the
  higher hit wins, used when it is above the spot or the spot is more than 1 m above it. V never ends in mid-air.
- **Summary:** after every flight, `flight: N frames, M samples (median spacing a ms, max gap b ms), max step c m,
  clipped yes|no`.
- **Setup:** `drive: ready (Teleport <param types>, raycast <param types>)` once, or one `drive: disabled: <what> missing`
  (then `cam.fly` stays false and Minecraft calls every flight off within 0.5 s).

### Scripts (`Flight.reds`, `Health.reds`)
- `CyberblockFlight.Tick` (10 Hz): `allowed = live && V is defined && !mounted && !PlayerLocked`, then
  `Cyberblock_SetFlightAllowed(allowed)`. A flight that is active while `!allowed` logs
  `flight: ignored ... (<LockReason>)` once.
- `CyberblockFlight.Clear` (F9, Lost, before-save, session end): allowed false first; if the drive is moving V, V is
  teleported to the ground below.
- **Landing wraps** (audit G-9): `LocomotionAirDecisions.ToHardLand`, `ToVeryHardLand`, `ToDeathLand` and
  `ToSuperheroLand` return false while `CyberblockFlight.SuppressFall` (`Cyberblock_SecondsSinceFlight() < 2.0`, or a
  fall-suppressing effect such as Slow Falling). `ToRegularLand` is **not** wrapped.
- `Health.reds`: a landing instant kill (`Attacks.HardLanding`, `VeryHardLanding`, `DeathLanding`) while `SuppressFall` is
  not reported to Steve (`dmg instant kill (landing) not reported: flight`).

## Minecraft side (`Flight.java`, `FlightRules.java`, `ClientPacketListenerMixin`)

- **Glide start** (unchanged): a usable elytra, at least 3 blocks of free air straight down from V's feet (a `COLLIDER`
  ray, so barriers count) and Steve's gliding box at V's feet clear of any collision; then a jump press while V falls
  faster than 1 m/s, or a fall faster than 12 m/s for 0.3 s. With the **Reinforced Tendons** implant the first press in the
  air is V's own double jump and the second opens the elytra (this was the Double Jump perk before the cyberware
  rework). Not while `cam.held` or `cam.menu`, nor for 1 s after a flight was called off.
- **Launch start** (new): `ClientPacketListenerMixin` forwards the local player's explosion knockback (`handleExplosion`,
  `playerKnockback`) and its set-entity-motion packets (`handleSetEntityMotion`) to `Flight.impulse(v, explosion)`. A
  kick counts when it passes `FlightRules.launch` (`LAUNCH_MIN_UP` 0.25 and `LAUNCH_MIN_SPEED` 0.4 blocks/tick for an
  explosion, `MOTION_LAUNCH_MIN_UP` 0.6 blocks/tick for a motion packet), the host is not held, in a menu or in a vehicle,
  and Steve is alive and not a passenger or a spectator. `startLaunch`
  switches creative flying off, clears `onGround`, lifts Steve out of collision (at most 1 block, in 1/16 steps), sets his
  motion to the kick, and logs `launch started at {} with {} m/s`. A server-side mixin gives a creative-flying Steve
  explosion knockback while Cyberblock is active.
- **Touchdown:** the glide rule for a glide; for a launch, on the ground or in water after a 100 ms settle. A launch
  switches to a glide on a jump press with an elytra, a fall faster than 1 m/s and 3 blocks clear (no new handshake).
- **Samples:** `Wire.flight` now sends `kind` and `ts = (System.nanoTime() − Flight.EPOCH) / 1e6`.
- **Following:** a flight whose `cam.fly` does not turn true within 0.5 s, or turns false after it was true, is called off:
  Steve snaps back to V's feet and the host hears `active:false` at V's own feet. `followsCam()` also turns true as soon
  as a pose that arrived after touchdown has `!fly` and feet within 1.5 m of the landing spot (the 0.5 s settle stays as
  the upper bound). The host's fall damage is ignored, and `HostDamage.fallFreeUntil` armed, only once the host followed.
- **Menus:** while `cam.menu` Steve waits in the air where he was, with no gravity and no flight samples; when it closes he
  carries on with the velocity he had (the DLL holds V meanwhile, timers frozen).

## Evidence

| Fact | Source |
|---|---|
| `DelaySystem.DelayCallbackNextFrame(delayCallback: ref<DelayCallback>) -> Void` exists and vanilla uses it once per request; re-queueing it from inside its own callback **never fired** in 2.31 (R1-a, table above) | decompiled 2.31 scripts `orphans.swift` 11826, `cyberpunk/player/psm/equipment.swift` 524; `Cyberblock.log` 2026-10-03 15:07-15:16 |
| `gameTeleportationFacility::Teleport(objectToTeleport, position: Vector4, orientation: EulerAngles)`; scripts reach it through `TeleportationFacility.Teleport(GameObject, Vector4, EulerAngles)` | NativeDB; `orphans.swift` 31935 |
| `gameSpatialQueriesSystem::SyncRaycastByCollisionGroup(start, end, collisionGroup: CName, out result: physicsTraceResult, staticOnly, dynamicOnly) -> Bool` for the clip and ground rays | NativeDB |
| Vanilla player teleports that keep the facing pass `Vector4.ToRotation(player.GetWorldForward())` | `cyberpunk/UI/fullscreen/map/worldMap.swift` 949, `cyberpunk/devices/roadBlock/roadBlockTrap.swift` 74 |
| The look pitch belongs to `FPPCameraComponent` (`ResetPitch()`). Fast travel calls `GetFPPCameraComponent().ResetPitch()` explicitly before `TeleportToNode`, so a teleport by itself does not reset the pitch [inferred] | `orphans.swift` 27006-27011; `core/systems/fastTravelSystem.swift` 335-339 |
| Locomotion in the air: `FallDecisions` → `LocomotionAirEvents.OnUpdate`. The landing type (Regular / Hard / VeryHard / Death) is chosen from `GetVerticalSpeed` = `physicsStateValue.LinearVelocity.Z`. Hard, very hard and death landings start `Attacks.HardLanding` / `VeryHardLanding` / `DeathLanding`, which `Health.reds` reports as `fall` | `cyberpunk/player/psm/locomotionTransitions.swift` 214-226, 3617-3754, 3813-3850, 99-103; `defaultTransition.swift` 553-557 |
| The landing transitions the wraps cover are `LocomotionAirDecisions.ToHardLand`, `ToVeryHardLand`, `ToDeathLand`, `ToSuperheroLand` (protected const) | the 2.31 scripts and `cp/scripts/compile_check.py` |
| RED4ext calls the Running state's `OnUpdate` once per rendered frame; the camera publisher already runs there (`on_running_update` calls `cb::camera::publish()`) | `third_party/RED4ext.SDK` `GameStates.hpp` (`CRunningState::OnUpdate`), `cp/plugin/src/main.cpp` |

## Owned yaw: the glide steers with the camera (re-test 3, F4)

**Report.** After re-test 2 the glide was smooth, but "you cannot change direction: you stay locked to the start
direction". Minecraft's log of 2026-10-03 17:36:40-45 shows a 108 m glide on an exact diagonal, with the mouse in use.

**Cause.** Minecraft steers its glide with the camera yaw and pitch that arrive in `cam`, and `cam.r` is read from V. The
per-frame drive teleported V **every frame** with the yaw it had read from `GetWorldForward` **before** that frame's mouse
delta had been applied by the game, so the game's horizontal mouse turn was written to V's body and then overwritten by the
next teleport. The camera yaw never changed, and neither did the glide.

**Method (all in the DLL, `cp/plugin/src`; the pure parts are unit-tested in `cb_tests`):**
1. **Count the mouse.** `input.cpp` adds the relative raw-mouse X counts (`lLastX`, only while forwarding and no Minecraft
   screen is open) into an atomic; the main loop takes it once per frame (`take_mouse_x()`).
2. **Strip the game's own turn.** While the drive is moving V (and the camera is first person), the `GetRawInputData` hook
   sets the game's `lLastX` to zero, so the game does not turn V's body a second time. Pitch (`lLastY`) is untouched: the
   look up and down is still the game's.
3. **Learn the scale.** The game's degrees per mouse count depend on the player's sensitivity setting, so `TurnRate`
   (pure) learns it from frames **outside** a flight: it sums `|dyaw|` and `|counts|` over frames with `|counts| >= 2` and
   `|dyaw| < 30`, halves both sums past about 4000 counts (a moving window), returns 0.15 until 200 counts were seen and
   clamps to [0.002, 1.0]. The DLL logs `look: R deg per mouse count` once it has settled or when it moves by more than
   10 %.
4. **Turn the glide.** `follow::turned_yaw(cpYaw, counts, degreesPerCount) = cpYaw - counts * rate`, normalised to
   (-180, 180]. Cyberpunk's yaw is counter-clockwise, so moving the mouse right lowers it.
5. **Drive with an owned yaw.** On the idle-to-driving transition `g_yaw` is taken from the forward read the drive already
   did (that frame's counts are discarded); every later frame that is not behind a menu applies `turned_yaw`; the
   `Teleport` gets `EulerAngles{0, 0, g_yaw}` for the move, the landing and the ground put-down, so there is no per-frame
   `GetWorldForward` / `ToRotation` any more. The camera publishes V's yaw, so Minecraft steers the glide with it.
6. **Third person.** The strip applies only to the first-person view (`!view::applied()`), because the third-person
   camera is Cyberpunk's own first-person component moved back.

**Evidence.** Unit tests (`test_follow.cpp`, `test_keys.cpp`): `turned_yaw` sign, wrap and zero counts, `TurnRate` warm-up,
window and clamps. The flight summary line now ends with `yaw start A end B, mouse x N counts at R deg/count, pitch
min..max`.

**Still to confirm in game** (TESTING-PLAN F4): a mid-glide mouse swing turns the glide at once, `A` and `B` differ by about
the turn made, `N` is not 0, and the first-person camera stays smooth; no sticky or snapping yaw on fast turns (the risk
named above, now handled by owning the yaw instead of re-reading it).

## What locomotion is expected to do (to verify in game)

- **During the flight**, V is in the air every frame, so the locomotion state machine enters `Fall` (falling wind loop).
  V's meshes are hidden while Active, so only the sound matters. Whether `Teleport` resets the character controller's
  velocity is native code. If it does not, gravity builds up a large `LinearVelocity.Z` between frames.
- **At touchdown**, `LocomotionAirEvents.OnUpdate` picks the landing from that vertical speed. The hard, very hard,
  death and superhero landings are **suppressed by the wraps** for 2 s after the flight (audit G-9 is covered), and their
  attacks are not reported. `ToRegularLand` still runs: if V hangs in the Fall pose on the ground, the follow-up is
  `wrappedMethod(...) || SuppressFall && IsTouchingGround` in a `ToRegularLand` wrap (the name Flight.reds gives).
- **The risks of the drive:** `Teleport` called from `OnUpdate` (not from a game-thread script), a static call to
  `Vector4::ToRotation` with a null context, and yaw "stickiness" on fast mouse turns (re-test 3 removed the cause: the DLL now owns the yaw, see
  [Owned yaw](#owned-yaw-the-glide-steers-with-the-camera-re-test-3-f4)).

## In-game checks (needsInGame)

Cyberblock Active, Steve with an elytra and rockets (the survival kit), real Minecraft. `red4ext/logs/Cyberblock.log` is
the log; the lines quoted are in `docs/TESTING-PLAN.md` "Re-test 2".

- **F-0 start-up:** the log has `drive: ready (Teleport <param types>, raycast <param types>)` once. A `drive: disabled:
  <what> missing` line means the drive is off: send it.
- **F-1 per-frame drive:** glide for about 10 s with rockets at about 20 m/s. Expected:
  - the log has `flight: start (glide)`;
  - V and the camera move smoothly every frame, with no 1 Hz stepping and no jitter of Night City;
  - the summary line shows a **median spacing of 17 ms or less and a max step under 1 m**, `clipped no`.
- **F-2 look kept:** look 45° down, then start a glide. Expected: the pitch stays at 45° down at take-off, the yaw is
  unchanged.
- **F-2b turning while gliding:** swing the mouse hard in all directions mid-glide. Expected: the view and the glide
  follow the mouse at once; V's facing does not stick or snap back.
- **F-3 landing:** glide from a megabuilding roof to the street. Expected:
  - the log has `flight: landed (glide) after X s` (or `flight: over, V stays where it is` for a hop within 1.5 m);
  - V stands on the street within a frame of touchdown, not in mid-air and not hanging in the Fall pose;
  - **no** `dmg fall` within 2 s and no instant kill (`dmg instant kill (landing) not reported: flight` is fine);
  - V's health is unchanged and no death-landing effects play. If V hangs in the Fall pose, record it and ship the
    `IsTouchingGround` follow-up above.
- **F-6 clear mid-flight:** quicksave is blocked while Active, so test with F9 (Off) in mid-glide. Expected: the drive
  stops at once and V is put on the ground below; the camera is V's own.
- **F-7 ender pearl:** throw a pearl onto a rooftop. Expected: V is teleported there facing Steve's yaw, onto the Night City
  floor (no sinking), the pitch unchanged, and no `dmg fall` within 2 s.
- **F-8 refused glide:** start a glide while V is held (sitting) or let it be refused. Expected: `flight: ignored ...
  (<reason>)` in the Cyberpunk log; Minecraft's log has `glide called off (the host did not follow)` within 0.5 s and
  Steve is back with V; no solo flight.
- **F-9 clearance and double jump:** Space at the end of a normal jump never opens the elytra; a jump off a ledge with 3+
  blocks below does. With **Reinforced Tendons** installed, the first Space in the air double-jumps V and the second
  opens the elytra; the Double Jump perk alone gives no double jump.
- **F-11 walls:** glide fast into a building away from probed ground. Expected: V stops at the wall
  (`flight: V stopped short of Night City geometry ...` once); after the landing Steve comes back to V.
- **F-12 pearl refused:** throw a pearl while V sits or drives. Expected: the overlay "Pearl blocked: V cannot be moved
  right now", the pearl back, no 5 HP lost, no `teleport` handled by Cyberpunk.
- **L-1 own charge, survival:** throw a wind charge at your feet. Expected: Steve and V launch; the Minecraft log has
  `launch started at ... with ... m/s`, Cyberblock.log `flight: start (launch)` then `flight: landed (launch) after X s`;
  V follows every frame; no `dmg fall` within 2 s.
- **L-2 the same in creative:** it launches too (creative flying off for the launch).
- **L-3 another player's or a Breeze's charge:** the same launch.
- **L-4 mace wind burst:** smash from a height with the Wind Burst enchantment; the burst launches Steve and V follows.
- **L-5 launch, then glide:** with the elytra on, press Space at the top (falling faster than 1 m/s, 3 blocks clear).
  Expected: the elytra opens with no pause and no second `launch started`.
- **L-6 no launch while V is held:** sit or drive and throw a charge at your feet. Expected: no `launch started`, Steve
  stays with V, people and cars are still knocked back.
- **L-7 menu mid-flight:** glide or launch, press Esc (or open the map) for 5 s, close it. Expected: Steve waits in the
  air; Minecraft logs `glide paused` then `glide resumed`; the DLL may log `flight: no samples, holding V` then
  `flight: samples back`, never `V put on the ground`; the flight carries on.

## History: the script loop (replaced)

The first answer (plan 2026-10-03, S2.4) was a `DelayCallback` that re-queued itself with `DelayCallbackNextFrame`, with
`Teleport` per run, and a **camera-only fallback** (`CyberblockFlight.SetCameraOnly(true)`): a free-camera entity moved
with Steve while V stayed at the take-off spot, with `Cyberblock_SetLookCapture(true)` giving Minecraft the mouse. Both
were compiled but never run against a real Cyberpunk before the re-test, which showed the loop stops after one frame
(R1-a above). Re-test 2 removed the loop and the fallback together: there is no longer a second flight method to switch
to, and the checks F-4 (camera only) and F-5 (free camera entity) are gone with it.
