# Phase 6 (hacking and the interaction map): descopes and risks

Plan: `docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 10 (deliverables, "Descopes and risks") and the dated Phase 0
amendment after section 1.9. Written by package 6P with the docs; the integrator adds what the other packages found.

## Descoped by the Phase 0 research (written fallbacks, applied now)
- **Remote range (check (h)).** The in-game CET lines have not been run, so a remote action only reaches what the host's game streams to the
  server: a target in the `npcs` or `devices` list, and nothing farther than 50 m from V. A target or a centre past that answers
  `cyberblock.hack.out_of_range`, and the server checks the same range before it sends, so a guest sees the message on the action bar. The real
  reach is a number to find in game.
- **Traffic and blackout fall back to the device layer.** If the scripts cannot prove the traffic system's calls, a traffic switch and a gridlock use
  the Phase 4 traffic-light devices, and a district blackout switches off the devices within 150 m of V instead of a whole district. Each path prints
  one log line saying which one is active.
- **No true Cyberpunk camera view.** A Cyberpunk camera view would take over the host's screen. `cam_feed` only reports success, and Minecraft draws
  the replica of Night City from the camera device's position and facing (both are already in the `devices` list), with the figures that are
  streamed.
- **The photo feed is low-resolution by design, and may be a replica.** The DLL scales Cyberpunk's picture to 128 x 128 map colours, at most once a
  second and only while someone asks for it. If the copy costs more than 0.5 ms of frame time (or cannot run) it stops, and after 3 s without a
  picture the Minecraft server draws a top-down replica render around V on the Net Map and the map item instead. Either way the picture is small and the colours are the map palette's.

## Limits of this version of the contract
- **Shapes the plan left open, fixed by 6P** (the integrator confirms them against 6B, 6I, 6R, 6S and 6D): the optional `p`, `r` and `secs` of an
  area `rhack` (`target` is `null` there), the reserved daemon id `alarm` (a failed Breach sends exactly `["alarm"]`), `hackresult` answering `rhack`
  and `daemon` with the same `id` and no `upload` or `kind`, the four `cyberblock:act` payloads, the three `cyberblock:ui` payloads, the
  `breach` payload with a puzzle id and `pick`/`abort`, and the 50 m range. After the 6P review: `secs` on every vehicle hack (the server sends 30)
  and never 0 on `traffic_switch`, `gridlock` and `blackout`, every timed effect restored on the session end, a link drop and before a save (save
  lock `n"CyberblockHack"`), the photo feed behind the owner's `hacks` toggle, an abort after the first pick as a failure (the puzzle kept
  otherwise, a 10 s cooldown after a failure), and the player-reach rule only for hand uses (a Breach pick, a right-clicked car figure). A later change on either side must change `shared/protocol.md` and
  `tests/test_phase6_protocol.py` together.
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
- **Devtools command.** `tools.mpcheck breach` drives the guest with `/cyberblock test breach <x> <y> <z> solve|fail` (6B); without it that scenario
  cannot run (the harness says so). It is tested against a simulated server only.
- **Loops.** A redstone clock wired to a Device Relay toggles a door twice a second at most and freezes after 8 toggles in 4 s (30 s); the harness
  does not build circuits, so Re-test 6 (P6-1) is where it is seen.
- **Frame cost.** A feed that costs frame time makes the DLL stop sending it; the in-game check is that the host does not notice.

## For the integrator
- The merge order of the phase is 6D, 6S, 6A, 6B, 6R, 6I, 6P. The class tables, the rate table and `CYBERWARE_IDS` of `tests/test_protocol_md.py`
  changed here (`feed`, `rhack`, `daemon`, `feedreq`, the 18th cyberware id), as did the cyberware example of `shared/protocol.md`: the Java and C++
  tests of the 17 ids belong to 6I and 6D.
- Phase 7's package edits the same files (`shared/protocol.md`, `tests/test_protocol_md.py`, the guide, the testing plan, the changelog): the
  Phase 6 and Phase 7 parts are separate blocks and merge by keeping both.
