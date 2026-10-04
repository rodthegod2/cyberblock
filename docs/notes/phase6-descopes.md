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
- **The photo feed is low-resolution by design, and may be a replica.** The DLL scales Cyberpunk's picture to 128 x 128 map colours, at most once a
  second and only while someone asks for it. If the copy costs more than 0.5 ms of frame time (or cannot run) it stops, and after 3 s without a
  picture the Minecraft server draws a top-down replica render around V on the Net Map and the map item instead. Either way the picture is small
  and the colours are the map palette's.

## Limits of this version of the contract
- **One contract, checked from four sides.** The 6P review found the document and the code apart; the integration of the review fixes settled it
  this way: the code moved to the contract where the contract was the plan's (6I: a `null` target and whole `p`, `r` and `secs` on every area
  hack, `secs` 30 on every vehicle hack, the district hacks centred on V; 6B: the Breach timer from the opening, a kept puzzle, a 10 s cooldown;
  6D: no feed fallback notice, the server's replica stand-in), and the document moved to the code where the code was right (6P: the act fields
  `eid`/`host`/`dev`/`p`, a 64-character Comms text, the scripts' own effect times, restore-before-save instead of a save lock, the DLL's wider
  acceptance). `shared/phase6-messages.json` and `shared/rhack-vectors.json` hold real messages that the Java, C++ and Python tests all check,
  so a later change on any side must change the builder, the DLL, the document and those files together. The `hs_ack` feature bit 8 is set by
  the server.
- **Every effect ends.** The scripts time each effect themselves (a lock 120 s, an engine hold 30 s, a brake about 5 to 6 s, a jam 3 s; 15 s and
  10 s on V's own car; a gridlock 60 s; a blackout its `secs`) and restore everything at once before every save, on leaving live, on load, on the
  session end and a link drop (there is no save lock). `veh_start` and a flipped intersection are not undone.
- **Breach.** The timer starts when the screen opens; an abort before the first pick sends nothing but keeps the puzzle (the same grid and running
  timer next time), one after it is a failure, and a failure sends the reserved daemon id `alarm` alone (`["alarm"]`). The limits: after a
  failure the same player waits 10 s on that device, and an owner gets one alarm a minute (a later failure sends nothing and costs no Heat). The
  devtools `test breach` drops the player's cooldown and kept puzzle on that device, not the alarm window.
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
