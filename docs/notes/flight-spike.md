# Spike S2.4: following Minecraft's elytra flight with V, every frame, from scripts

Question (plan 2026-10-03, S2.4): while Steve glides, Minecraft's physics move him and the DLL keeps the latest
`flight` sample (`Cyberblock_FlightPos`, extrapolated by at most 0.1 s). Can redscript put V there every rendered frame
and keep the mouse look working? What does Cyberpunk's locomotion make of it? And what is the fallback if it
jitters?

**Answer:**
- **Primary mode.** A `DelayCallback` that re-queues itself with `DelaySystem.DelayCallbackNextFrame` runs once per
  frame. Each run teleports V with `TeleportationFacility.Teleport(V, flightPos, Vector4.ToRotation(V's forward))`.
  The body keeps its own yaw and the camera keeps its pitch, so the mouse look is untouched.
- **Fallback, camera only** (`CyberblockFlight.SetCameraOnly(true)`). V stays where the glide started (already hidden
  by PlayerState while Active). A vanilla free-camera entity is spawned and activated, and it is moved every frame to
  the flight position + 1.6 m, looking along Minecraft's yaw/pitch. Minecraft gets the mouse through
  `Cyberblock_SetLookCapture(true)`.

Everything compiles against the 2.31 scripts plus Codeware (`Flight.reds`, `cp/scripts/compile_check.py`). Smoothness,
the landing behaviour and the camera switch can only be judged in game: the checks are at the end.

## Method (`cp/scripts/Cyberblock/Flight.reds`)

1. **Start.** The 10 Hz `CyberblockFlight.Tick` sees `Cyberblock_FlightActive()` turn true (V on foot, live, not
   held). It remembers the start time and V's position, calls `Cyberblock_SetFlightFollowing(true)` (Minecraft reads
   it as `cam.fly`), bumps a loop generation and queues the first frame callback. `FlightSeq` is not used: the loop
   runs until `Cyberblock_FlightActive()` is false. A refused start (vehicle, `PlayerLocked`) sets following false and
   logs the lock reason; Minecraft then calls the glide off within 0.5 s and Steve goes back to V.
2. **Every frame.** The callback stops as soon as its generation is stale (`DelayCallbackNextFrame` returns no id, so
   it cannot be cancelled), then:
   - **landing:** if `Cyberblock_FlightActive()` is false and the flight was not timed out (Minecraft sent
     `active:false`), the loop ends at `Cyberblock_FlightPos()` (the landing spot): no teleport when the flight
     lasted under 0.3 s or the spot is within 1.5 m of V; otherwise the ground is raycast from 1.5 m above the spot
     and a higher surface wins (V never ends under the real floor), and a spot more than 1 m above the ground goes
     down to it. In primary mode the spot is first clipped like a frame (below);
   - **timed out** (`Cyberblock_FlightTimedOut()`: the samples stopped without `active:false`): V is held where it is
     for up to 1 s and following resumes if the samples come back; after that V is put on the ground below;
   - **primary:** otherwise V goes to `Cyberblock_FlightPos()`, stopped 0.3 m short of any Night City surface
     (Static + Terrain, not our collision boxes) between the last position V was put at and it;
   - **camera only:** V is put back at the take-off spot if it drifted more than 5 cm. The camera entity is teleported
     to `FlightPos + (0, 0, 1.6)` looking along `(−sin a·cos p, cos a·cos p, −sin p)` for Cyberpunk yaw `a` and
     Minecraft pitch `p` (positive = down).
   Every frame that follows calls `Cyberblock_SetFlightFollowing(true)` again; while the camera flies,
   `CyberblockFlight.Center` (the flight position) is where `Probes.reds` probes the ground.
3. **Landing.** The game is told nothing: V is simply teleported to the landing spot (when the rules above call for a
   teleport). Fall reports are suppressed for 2 s after it (`CyberblockFlight.SuppressFall`, which S1's `Health.reds`
   reads). In camera-only mode the camera is deactivated and despawned, look capture is switched off, and V is turned
   to the flight yaw. Every stop sets following false.
4. **`Clear`** (before saves, on load, at session end, on leaving Active/Vehicle) stops the loop and restores the
   camera and look capture. A save in mid-flight therefore saves V wherever the last frame put it. The next live tick
   restarts following if Minecraft is still gliding.

## Minecraft side (`Flight.java`, `FlightRules.java`)

- **Start:** a usable elytra, at least 3 blocks of free air straight down from V's feet (a `COLLIDER` ray, so barriers
  count) and Steve's gliding box at V's feet clear of any collision; then a jump press while V falls faster than
  1 m/s between the newest two samples, or a fall faster than 12 m/s for 0.3 s. With Steve's Double Jump perk the
  first press while `cam.air` is V's own double jump and the second opens the elytra. Not while `cam.held` or
  `cam.menu`, nor for 1 s after a glide was called off.
- **Following:** a glide whose `cam.fly` does not turn true within 0.5 s, or turns false after it was true, is called
  off: Steve snaps back to V's feet and the host hears `active:false` at V's own feet (no landing teleport). The host's
  fall damage is ignored, and the 0.5 s landing settle armed, only once the host followed.
- **Menus:** while `cam.menu` Steve waits in the air where he was, with no gravity and no flight samples; when it
  closes he carries on with the velocity he had (the host holds V meanwhile, step 2 "timed out").

## Evidence

| Fact | Source |
|---|---|
| `DelaySystem.DelayCallbackNextFrame(delayCallback: ref<DelayCallback>) -> Void` (no `DelayID`), vanilla use with a `DelayCallback` subclass | decompiled 2.31 scripts `orphans.swift` 11826; `cyberpunk/player/psm/equipment.swift` 524 |
| `TeleportationFacility.Teleport(GameObject, Vector4, EulerAngles)` | `orphans.swift` 31935 |
| Vanilla player teleports that keep the facing pass `Vector4.ToRotation(player.GetWorldForward())` | `cyberpunk/UI/fullscreen/map/worldMap.swift` 949, `cyberpunk/devices/roadBlock/roadBlockTrap.swift` 74 |
| The look pitch belongs to `FPPCameraComponent` (`ResetPitch()`). Fast travel calls `GetFPPCameraComponent().ResetPitch()` explicitly before `TeleportToNode`, so a teleport by itself does not reset the pitch [inferred] | `orphans.swift` 27006–27011; `core/systems/fastTravelSystem.swift` 335–339 |
| Locomotion in the air: `FallDecisions` → `LocomotionAirEvents.OnUpdate`. The landing type (Regular / Hard / VeryHard / Death) is chosen from `GetVerticalSpeed` = `physicsStateValue.LinearVelocity.Z` against speeds derived from fall heights (`GetFallingSpeedBasedOnHeight`, default gravity −16). Hard, very hard and death landings start `Attacks.HardLanding` / `VeryHardLanding` / `DeathLanding`, which `Health.reds` reports as `fall` | `cyberpunk/player/psm/locomotionTransitions.swift` 214–226, 3617–3754, 3813–3850, 99–103; `defaultTransition.swift` 553–557 |
| Free camera template `base\entities\cameras\simple_free_camera.ent`: entity class `gameFreeCamera` (extends `gameObject`); components `gameCameraComponent` "camera", `entColliderComponent` "collider" (dynamic sphere, preset `World Dynamic`), `entMeshComponent` "mesh" (`engine\meshes\editor\box_red.w2mesh`) | archive `basegame_4_gamedata.archive`, CR2W strings (scratch only, never copied); NativeDB `gameFreeCamera` |
| `CameraComponent.Activate(opt blendTime, opt shouldOverrideAudioListeners)` / `Deactivate(...)`; devices take the view with `Activate(0.00, true)` and give it back with `Deactivate(0.00, true)` | `orphans.swift` 21894–21916; `cyberpunk/devices/core/deviceBase.swift` 3831, 3838 |
| `StaticEntitySystem.SpawnEntity/GetEntity/DespawnEntity`, `StaticEntitySpec`, `Entity.GetComponents`, `IComponent.Toggle/IsA` | Codeware 1.20.3 `Codeware.Global.reds` 43500–43532, 42157–42168 |
| `Quaternion.BuildFromDirectionVector(direction, opt up)`, `Quaternion.ToEulerAngles` | `orphans.swift` 23007, 23049 |

## What locomotion is expected to do (to verify in game)

- **During the glide**, V is in the air every frame, so the locomotion state machine enters `Fall`: falling wind loop,
  `Fall` animation event. V's meshes are hidden while Active, so only the sound matters. Whether teleporting resets
  the character controller's velocity is native code. If it does not, gravity builds up a large
  `LinearVelocity.Z` between frames.
- **At touchdown**, `LocomotionAirEvents.OnUpdate` picks the landing from that vertical speed. With a built-up speed
  this is a hard, very hard or **death** landing:
  - the landing attacks are reported as `fall` but suppressed (flying or less than 2 s after landing);
  - V is invulnerable under Cyberblock's god mode;
  - whether a death landing still kills an invulnerable V is the open audit G-9.
- **If check F-3 shows death landings,** the narrow fix is to wrap `LocomotionAirDecisions.ToDeathLand` (protected
  const) to return false while `CyberblockFlight.SuppressFall(game)`. It is not shipped, because whether it is needed
  depends on F-3.

## In-game checks (needsInGame)

Mashup Active, Steve with an elytra and rockets, fakehost or real Minecraft. `red4ext/logs/Cyberblock.log` is the
log.

- **F-1 per-frame hook:** start a glide. Expected:
  - the log has `flight: start (primary)`;
  - V follows Steve smoothly (no 10 Hz stepping), with no visible jitter of Night City;
  - the mouse still turns the view, and Steve's look follows `cam.r`.
- **F-2 look kept:** look 45° down, then start a glide. Expected: the pitch stays at 45° down at take-off (not reset
  to level), and the yaw is unchanged.
- **F-3 landing:** glide from a megabuilding roof down to the street. Expected:
  - the log has `flight: over, V stays where it is` (primary: V is already at the spot) or `flight: landed`, and no
    `dmg fall` line within 2 s;
  - V stands at the landing spot and V's Health is unchanged;
  - note which landing plays (sound/camera shake): regular, hard or death. If V dies or the death-landing effects
    play, enable the `ToDeathLand` wrap described above.
- **F-4 camera only:** `CyberblockFlight.SetCameraOnly(true)` in CET, then glide. Expected:
  - the log has `flight: start (camera only)`;
  - the view flies with Steve while V stays at the take-off spot;
  - moving the mouse turns the view through Minecraft (look capture);
  - on landing V appears at the landing spot, the view is V's own again, and the log has `flight: camera released`.
  - `CyberblockFlight.SetCameraOnly(false)` switches back for the next flight.
- **F-5 free camera entity:** during F-4, check that no red box is visible around the camera and that the camera
  does not fall or collide (its collider is switched off).
- **F-6 clear mid-flight:** quicksave is blocked while Active, so test with F9 (Off) in mid-glide. Expected: following
  stops at once, the camera is V's own, and look capture is off (Minecraft's look no longer moves without the
  mouse).
- **F-7 ender pearl:** throw a pearl onto a rooftop. Expected:
  - V is teleported there facing Steve's yaw, with the camera pitch unchanged;
  - the log has no `dmg fall` within 2 s.
- **F-8 refused glide (fix B):** start a glide while V is held (a workspot, e.g. sitting) or let it be refused.
  Expected: `flight: ignored ... (<reason>)` in the Cyberpunk log; Minecraft's log has `glide called off (the host
  did not follow)` within 0.5 s and Steve is back with V; no 300 m solo flight.
- **F-9 clearance and double jump (fix B):** Space at the end of a normal jump never opens the elytra; a jump off a
  ledge with 3+ blocks below does. With Steve's Double Jump perk, the first Space in the air double-jumps V and the
  second opens the elytra.
- **F-10 menu mid-glide (fix B):** open the map mid-glide for 5 s, close it. Expected: Minecraft logs `glide paused`
  then `glide resumed`, the Cyberpunk log may show `flight: no samples, holding V` then `flight: samples back`, and
  V carries on gliding from where it was.
- **F-11 walls (fix B):** glide fast into a building away from probed ground. Expected: V stops at the wall
  (`flight: V stopped short of Night City geometry ...` once) instead of entering it; after the landing Steve comes
  back to V.
- **F-12 pearl refused (fix B):** throw a pearl while V sits or drives. Expected: the overlay "Pearl blocked: V cannot
  be moved right now", the pearl back in the inventory, no 5 HP lost, no `teleport` handled by Cyberpunk.

**Pending in-game (fix B, 2026-10-03):** F-1 to F-3 were never run with a real Cyberpunk, and F-8 to F-12 check the
fix-B changes; all need package A's `Cyberblock_SetFlightFollowing`, `Cyberblock_FlightTimedOut` and the cam flags
`fly`, `menu`, `air`, `held`. If F-3 shows death landings (V dies, or the death-landing effects play), ship the
`LocomotionAirDecisions.ToDeathLand` wrap described above.
