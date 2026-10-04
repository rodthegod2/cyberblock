# Phase 8 (roles and modes): descopes and risks

Plan: `docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 12 (deliverables, "Descopes and risks") and the dated Phase 0 amendment after section 1.9.
Written by package 8P with the docs, **before** the other Phase 8 packages merged; the integrator adds what they found.

## Descoped by the Phase 0 research (written fallbacks, applied now)
- **(d) Spawning Night City enemies.** The in-game CET lines D1 to D5 have not been run, so the verdict "yes for people and drones, pending combat AI" is the working
  contract. Gangers, NCPD, Max-Tac and drones are spawned through Codeware's dynamic entities; if spawning fails, or the spawned NPCs stay passive, every spawn becomes a
  **Minecraft squad** (skinned mobs standing in for them) for everyone, and for hosts the real wanted level (check (e)) brings the game's own NCPD and Max-Tac. The scripts print
  `spawn: path: ...` to say which one is active. **Turrets are always Minecraft-side**: a turret is a `SecurityTurret` device, not a character, so `turret` is not a `spawn.rec`.
- **(e) The wanted level and the factions' attitude.** CET lines E2 to E5 have not been run. With no write of the wanted level the stars stay the game's own and the Heat only
  reaches hosts through spawns or Minecraft squads; with no write of the attitude, a Gang Wars team's reputation does **not** change how Night City treats its hosts (the
  brief made it conditional) and the docs say so (`heat: path: ...` and `attitude: path: ...` in the log).
- **Without the host's switch.** The `spawns` toggle is **off by default**: Night City enemies, the real wanted level and the attitudes only reach a host that turned it on; for
  everyone else they are Minecraft squads, so a host who only builds is never surprised.

## Limits of this version of the contract
- **Shapes the plan left open, fixed by 8P** (the integrator confirms them against 8D, 8S, 8T, 8X, 8M, 8O and 8V): the four values of `spawn.rec` (the plan's `turret` is out), the
  `faction` of `spawn` and `attitude` (the attitude group names of Phase 0 check (e)), `spawnresult` (`ok` exactly when `n` is 1..8, the reason empty exactly when ok, a rate of 10 per
  second with a burst of 40), `heat` sent on change and after every host `hello`, `glitch.seconds` 0..10 (0 ends it), the rule that every effect ends with the session, a `hello` and a
  link drop, and the `spawns` toggle gating `spawn`, `heat` and `attitude`. A later change on either side must change `shared/protocol.md` and `tests/test_phase8_protocol.py` together.
- **The wire is small on purpose.** Matches, PvP, the Van, the Overlord Console, Downed and the spectator mode are Minecraft and server features; their `cyberblock:act` and
  `cyberblock:ui` payloads belong to their packages and are not in the protocol document until the integrator copies them in.
- **Attitude has no answer.** The DLL does not reply to `attitude`, `heat` or `glitch`; the log lines are the evidence.

## Risks
- **The devtools commands are assumptions.** `tools.mpcheck match`, `heat` and `overlord` assume `/cyberblock test heat set <0..100>`, `test match start|join|end` and `test overlord
  spawn <palette id> <n> <player>` (`PHASE8_COMMANDS` in `tools/mpcheck.py`, the one table to edit) next to the real `/cyberblock spectate` and `/cyberblock guests spawns on|off`. They
  were written before 8M, 8T and 8O and are tested against a simulated server only; a server without them gives a clear error that names the table. With `--all` they are reported as
  SKIP in a checkout that has no `mode/match`, `mode/heat` or `mode/overlord` package folder, and a SKIP is not a pass.
- **PvP is measured with `/damage`.** The `match` scenario hits with vanilla's `/damage ... minecraft:player_attack by <player>`: a PvP rule that only hooks real swings would not
  see it. If so, the scenario needs another way to hit.
- **The first real run is the test.** Nothing of Phase 8 has run against a live Cyberpunk game: the spawn records, their combat AI, `SetWantedLevel`, the attitude relations and the
  `cb_glitch` pass are the Phase 0 paper verdicts. Re-test 8 (P8-9) prints which path is active.
- **The biggest phase.** The plan allows splitting it into 8a (Van, Overlord, Heat) and 8b (matches, player hacks, Downed, spectator) with a user test in between, if the merge risk
  is too high.

## For the integrator
- The merge order of the phase is 8D, 8S, 8A, 8T, 8X, 8M, 8O, 8V, 8P. The class tables, the rate table and `CYBERWARE_IDS` of `tests/test_protocol_md.py` changed here (`spawn`,
  `heat`, `attitude`, `glitch`, `spawnresult`, the 19th cyberware id), as did the cyberware example of `shared/protocol.md` and the 18 to 19 wording of `tests/test_phase6_protocol.py`: the
  Java and C++ tests of the cyberware ids belong to 8X and 8D.
- `tools/mpcheck.py`'s `SCENARIOS` ends with `match`, `heat` and `overlord` (server slot 0 each), and the fake host's mode list gains `modes`.
