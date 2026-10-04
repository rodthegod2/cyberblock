# Phase 3 (the Night City replica): descopes and risks

Plan: `docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 7 (deliverables, "Descopes and risks"). Written by
package 3P with the docs; the integrator adds what the other packages found.

## Web map: not skipped
Phase 0's server notes (`docs/notes/phase0-server.md`, package 0M) were never committed, so 3P looked the question up itself: a
**BlueMap build for Fabric on Minecraft 26.3 exists** (its Modrinth page lists the Fabric files 5.24 to 5.28 for 26.1 to
26.3; Dynmap had no 26.x build). `docs/WEBMAP.md` therefore exists: install on the server, the extra TCP port as an Apex fact
only the owner can supply, the `nc_*` blocks through BlueMap's own resource-pack mechanism if a block looks wrong, and the
`webMap` flag the owner sets in `server.json`. **Nothing of it has been run** (no server was available to 3P): the first
install is the test. If the owner's plan has no extra TCP port, the map is not possible there and nothing else is affected.

## Descopes and risks
- **Material accuracy** depends on Phase 0 check (b). The material table of `ground.m` (`tools/cbmath.py`,
  `shared/vectors.json` `materials`) is a **working** table: the substrings are guesses until the CET lines B1 to B5 show the
  real `TraceResult.material` and `meshAppearance` names. With a material that is always `None`, the palette falls back to the
  mesh appearance and then to the guess by height and district (the written fallback); the street still builds, in less
  accurate colours. One correction of the table, by the integrator, fixes the DLL, the scripts and the palette together because
  they all check the same vectors.
- **Weather** depends on the Codeware weather reader (`WeatherSystem.GetWeatherState`, names unconfirmed until the CET line
  ENV) and on how the DLL maps names; an unmapped preset is `other` (clear). The clock does not depend on it.
- **Teleport and park** follow the Phase 0 (g) amendment: the survey's settle probe and a down-ray snap, and the invisible
  way of parking; if CET G5 shows NPCs still see V, park falls back to holding V at a fast-travel point.
- **Replica size** grows with exploration (disk). Phase 9 measures it; until then nothing is pruned.
- **Render suppression** for hosts needs one re-mesh of the loaded chunks whenever Cyberblock is switched On or Off.
- **Doors and devices:** probe rays that hit a device are cast again past it only if CET B2 confirms the hit entity; otherwise
  Phase 4's device-box filter removes them from the replica later.
- **Overlapping hosts:** where two hosts' streets overlap, the first block placed wins (replica blocks go into air cells only).
- **Not verified by 3P in the game:** everything that needs Cyberpunk or a Minecraft client. 3P's own checks are the unit tests,
  `tools.gen_vectors`, and `tools.mpcheck` against a simulated server; the real `replica`, `twohosts2` and `meetup` runs need
  the merged Java side (3R, 3E) and are the integrator's.
- **Contract additions made by 3P** (the integrator confirms): rate limits for the new UP types as 3E's RelayGuard has them
  (`env` and `vteleported` 2/s burst 4), and the `maptile` exchange as 3R's NightCityMap and 3C's AtlasWire speak it
  (`cyberblock:act` request `{tiles, have}`, up to 32 tiles, 64 tiles/s burst 128; `cyberblock:ui` answer `{tx, tz, v, c}` with
  `v` the CRC32 of the cells), which megaplan 3-R2 names only as `cyberblock:ui t:"maptile"`.
- **`park` / `parked` relayed from Phase 7:** the DLL and scripts handle `park` and answer `parked` from Phase 3, but no Phase 3
  server sends `park` (the hub needs it), so the client relay and RelayGuard have no `parked` yet; Phase 7 adds the relay and
  its rate row.
- **`maxHosts` on an existing server:** the default 4 applies only to a newly written `config/cyberblock/server.json`; a server
  that ran before keeps `"maxHosts": 1` until its owner changes the file (HOW-TO-PLAY and TESTING-PLAN P3-7 say so).
- **Wrecks only for cars V hurt:** the server makes a wreck from `killed`, which the plugin sends only for entities V damaged; a
  car that crashes on its own just goes.
