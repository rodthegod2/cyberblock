# Phase 6 (hacking and the interaction map): descopes and risks

Plan: `docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 10 (deliverables, "Descopes and risks") and the dated Phase 0
amendment after section 1.9. Written by package 6P with the docs; the integrator adds what the other packages found.

## Descoped by the Phase 0 research (written fallbacks, applied now)
- **Remote range (check (h)).** The in-game CET lines have not been run, so the mod runs on the desk verdict: a remote action reaches **every target
  the host's game streams** (a car figure, a device in the `devices` list, a point within 150 m of an active V), however far from V. The written
  fallback, **nothing farther than 50 m from V** (`cyberblock.hack.out_of_range`), switches itself on from in-game evidence: on the server
  (`RangeRule`) when Cyberpunk refuses a target past 50 m for range or sight, in the scripts when a car hack past 50 m took no effect by either
  path. Each side logs which path is active (`hack range: ...`, `rhack: remote range: ...`). The real reach is a number to find in game.
- **Traffic and blackout fall back to the device layer.** No 2.31 script call switches traffic lights or lists a district's devices, so a traffic
  switch flips the intersection of the traffic light nearest V (one intersection, not a district), a gridlock stops the cars within 25 m of the
  point, and a blackout switches off the lights and devices within 150 m of V instead of a whole district. Each path prints one log line saying which
  one is active.
- **No true Cyberpunk camera view.** A Cyberpunk camera view would take over the host's screen. `cam_feed` only reports success, and Minecraft draws
  the replica of Night City from the camera device's position and facing (both are already in the `devices` list), with the figures that are
  streamed; the camera must be within the asker's view distance.
- **The photo feed is low-resolution by design, and may be absent.** The DLL scales Cyberpunk's picture to 128 x 128 map colours, at most once a
  second and only while someone asks for it. If the copy costs more than 0.5 ms of frame time (three times in a row) or cannot run, it stops for the
  session and sends the fallback notice; the server logs it and **Minecraft shows no picture**. The top-down replica render the plan names as the
  stand-in is **not built** (a known gap: a later task can draw the replica on the feed map when the notice comes).

## Limits of this version of the contract
- **The shapes are the merged code's.** The 6P review found that the first contract (a `null` target, `p`, `r` and `secs` on every area hack, `secs`
  30 on every vehicle hack, the act fields `target`/`pos`/`owner`, an 80-character Comms text, a save lock, a 10 s Breach cooldown) did not match what
  6I, 6B, 6D and 6S built, and the fake host dropped six of the seven real `rhack` shapes. `shared/protocol.md` "Phase 6" now describes the shipped
  shapes (`target:""` for an area hack, `district` for `traffic_switch` and `blackout`, `p` with a fractional `r` and `secs` only for the point hacks,
  `secs` for a blackout, no `secs` on a vehicle hack, the six act and nine ui types, the feed fallback notice, `hackresult.kind` on a success) and
  `shared/phase6-messages.json` holds real messages that the Java, C++ and Python tests all check, so a later change on any side must change the
  builder, the DLL, the document and that file together. The `hs_ack` feature bit 8 is now set by the server.
- **Every effect ends.** The scripts time each effect themselves (a lock 120 s, an engine hold 30 s, a brake about 5 to 6 s, a jam 3 s; 15 s and
  10 s on V's own car; a gridlock 60 s; a blackout its `secs`) and restore everything at once before every save, on leaving live, on load, on the
  session end and a link drop (there is no save lock). `veh_start` and a flipped intersection are not undone.
- **Breach.** The timer starts with the first pick; an abort before it sends nothing (a new grid next time), one after it is a failure, and a failure sends the reserved daemon id `alarm` alone (`["alarm"]`). The limits:
  a device is locked 30 s after any Breach that ended with a pick, a player 30 s after a failure, and an owner gets one alarm a minute (a later failure
  sends nothing and costs no Heat). The devtools `test breach` clears the device's and player's lockouts, not the alarm window.
- **Daemon ids are content.** The examples use `open_door`, `disable_cameras`, `reveal_enemies` and `overload` (the megaplan's words); the shipped
  set belongs to the `daemon` files of package 6A, and the scripts answer `unsupported daemon` for any id they do not run while the others still run.
- **Heat.** The Heat Sensor reads the nearest host's wanted level; its Server Heat mode and the Heat a failed Breach costs (+5) belong to Phase 8's
  meter. Until it exists a failure raises an alarm and, only if Phase 0's check (e) allows it, the host's wanted level by one.
- **One answer per `daemon`.** `hackresult` is `ok` only when every daemon of the message ran; an unknown id does not stop the others.
- **The `feed` is BOTH.** The host's own client shows it locally and relays it UP; the relay's token bucket is 2 per second (burst 4) and a frame
  is about 22 KB of base64 (under the 30000-byte UP cap).

## Risks
- **The first real run is the test.** Nothing of Phase 6 has run against a live Cyberpunk game: the vehicle calls, the traffic and district
  calls, the alarm and the back-buffer copy are the Phase 0 paper verdicts. Re-test 6 (P6-9) prints which path is active.
- **Devtools command.** `tools.mpcheck breach` drives the guest with `/cyberblock test breach <x> <y> <z> solve|fail` (6B), and since the 6P fix with
  `/cyberblock test eid <targets>` and `/cyberblock test act <t> <json>` for a Vehicle Panel and a Net Map hack through the real server; without them
  that scenario cannot run (the harness says so).
- **Loops.** A redstone clock wired to a Device Relay toggles a door twice a second at most and freezes after 8 toggles in 4 s (30 s); the harness
  does not build circuits, so Re-test 6 (P6-1) is where it is seen.
- **Frame cost.** A feed that costs frame time makes the DLL stop sending it; the in-game check is that the host does not notice.

## For the integrator
- The merge order of the phase is 6D, 6S, 6A, 6B, 6R, 6I, 6P. The class tables, the rate table and `CYBERWARE_IDS` of `tests/test_protocol_md.py`
  changed here (`feed`, `rhack`, `daemon`, `feedreq`, the 18th cyberware id), as did the cyberware example of `shared/protocol.md`: the Java and C++
  tests of the 17 ids belong to 6I and 6D.
- Phase 7's package edits the same files (`shared/protocol.md`, `tests/test_protocol_md.py`, the guide, the testing plan, the changelog): the
  Phase 6 and Phase 7 parts are separate blocks and merge by keeping both.
