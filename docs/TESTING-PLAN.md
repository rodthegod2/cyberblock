# Cyberblock — in-game test session (2026-10-03)

Everything below is built, unit-tested, compile-checked against the real 2.31 game scripts and reviewed. Work top to
bottom (**Re-test: Wave B, Phase 3 first** when you are testing Wave B, with the sections of Phases 4 and 5 beside it; then
**Re-test: Wave A**, which points into Re-test Q and Re-test 1 for the details; then **In-game fixes 3**, then Re-test 3); each step says what should happen. Tell Claude "F4: what you saw" or "R5 L-1: what you saw" (or "step N: ...") and it reads
`red4ext\logs\Cyberblock.log`, Minecraft's `mc\run\logs\latest.log` and captures the game window to diagnose.

## Re-test: Wave B, Phase 3 (the Night City replica)

The Phase 3 part of the Wave B session: **Night City as Minecraft**. The server builds Night City from the hosts' probes as
solid, coloured replica blocks that stay in the world, guests see the people and cars as figures, the Minecraft clock and
weather follow Cyberpunk, guests can build while the host is away, **Meet Up** takes you to a friend, and a server allows
four hosts. Phases 4 and 5 of Wave B add their own sections next to this one; you play them in one session. Plan:
`docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 7. The player guide is
[Night City as Minecraft](HOW-TO-PLAY.md#night-city-as-minecraft-phase-3). Tell Claude "P3-2: what you saw" and it reads
`Cyberblock.log`, the server log, `latest.log` and the game window.

**Before you start.**
- Restart Minecraft and the local test server the way you did for Wave A (ask Claude to start it); the guest is the harness
  client `CbGuest1` or a friend. A guest needs only Minecraft with the mod.
- Phase 0's CET lines (Wave A, W-A8) decide which way the plugin reads materials, weather and the teleport. If you have not run
  them yet, run **B1 to B5, ENV, G1 to G3, G5 and G6** first and paste the `CBP0` lines to Claude: it corrects the material
  names and the weather names once, and the replica looks right at once. Without them everything below still works, with the
  written fallbacks (the guess by height and district for materials; no weather if it cannot be read).
- Use **survival** for P3-1 to P3-5 and **creative** for P3-6.

**What to try (one screen):**
1. **Walk two streets** in Cyberpunk (Cyberblock On, joined to the test server), then fly over them as the guest: replica
   blocks in roughly the right colours, neon glowing (P3-1).
2. **Quit Cyberpunk:** the replica stays. The guest builds a wall across the street. Start Cyberpunk again: the wall stops V and
   a toast lists it; `/cyberblock changes` too (P3-2).
3. **The guest watches** people walking and cars driving at real speed; V destroys a car (a rocket), it leaves a dark wreck for 5 s (P3-3).
4. **Wait for Cyberpunk night:** Minecraft is night too; rain in Cyberpunk, rain in Minecraft if the plugin can read it (P3-4).
5. **`/cyberblock meetup <friend>`** (and the other way round): V appears at the friend's spot (P3-5).
6. **Creative guest breaks a replica block:** it comes back in about 10 s, nothing changes in Night City (P3-6).
7. **A second host** (only when you have one, and after `"maxHosts": 4` in the server's `server.json`) plays at the same time (P3-7).
8. **As a guest, open the Atlas:** it shows what the host explored (P3-8).
9. **Read the "which way" lines** and paste them to Claude (P3-9).
10. **The web map** (only if you installed BlueMap; [docs/WEBMAP.md](WEBMAP.md)) (P3-10).

| Item | What changed |
|---|---|
| Replica | `ground` ranges with a material code (`ground.m`) become `nc_*` blocks in air cells only, saved in the world; `unground` removes them only when the cell was seen empty (`seen:true`); the host's own client does not draw them |
| Figures | proxies are visible for guests, moving at real speed; wrecks 5 s, bodies 3 s; none without their owner's session |
| Environment | clock (`env`) and weather from the first host; Meet Up with `vteleport`/`vteleported`; `park` |
| Offline Builds | the resync gives a returning host every block placed meanwhile, a toast and `/cyberblock changes` |
| Hosts | `maxHosts` 4 for a newly written `server.json` (an existing one keeps its value: set it to 4 by hand); the shared Night City map (`terrain` both ways) |
| Tools | `tools.mpcheck replica twohosts2 meetup` (automatic checks with fake hosts); the docs and the web map page |

### P3-1. The replica from two streets
1. Join the test server as a host, switch Cyberblock On and walk two streets (a block or two of each is enough; go round a
   corner).
2. As the guest, fly (creative, or `/gamemode creative`) over the same streets.
3. **Expect:** the streets, sidewalks, walls, glass, metal and neon signs are solid blocks in roughly the colours they have in
   Night City; neon blocks **glow**; doors and gates are not walls; water is a flat blue surface. On your own screen as a host
   the blocks are **not** drawn (you see real Night City), and switching Cyberblock Off for a moment shows them.
4. Server log, near the top of what the replica does: lines about the replica blocks placed (grep `replica`); no
   `relay: ... dropped` for your link.

### P3-2. Offline Builds
1. Quit Cyberpunk (or press F9 to switch Cyberblock Off and close the game): the replica **stays** in the world, your figures
   disappear.
2. As the guest, build a wall of 2 to 3 blocks across a street you explored.
3. Start Cyberpunk and switch Cyberblock On again: the wall **stops V**, and a toast says "While you were away: N blocks
   placed, M removed (by <guest>)". `/cyberblock changes` lists them by region with coordinates.

### P3-3. Figures
1. As the guest, stand where the host walks: people appear as plain human figures walking along the streets, cars as boxes
   driving past, **at the speed they have in Cyberpunk**, no jumping; a hostile person has a red name tag.
2. V destroys a car that V damaged (a rocket, or shoot it until it blows up): a dark wreck stays about 5 s, then goes. A car
   that crashes into a wall on its own leaves no wreck (the plugin reports only deaths of what V hurt), so do not use that one.
   A dead person lies down about 3 s.
3. The host quits Cyberpunk: the figures are gone (the replica stays).

### P3-4. Time and weather
1. Look at the Cyberpunk clock (the HUD) and the Minecraft sky as the guest: night in one is night in the other (06:00 sunrise,
   12:00 noon, 18:00 sunset, 00:00 midnight). The daylight cycle is off, so the Minecraft sky does not run ahead.
2. If it rains in Cyberpunk, it rains in Minecraft; a storm is rain with thunder; other weather is clear. If Claude's reading of
   the "which way" lines (P3-9) says the weather cannot be read, only the clock follows: that is the written fallback.
3. With a second host (P3-7) the first host's time and weather stay.

### P3-5. Meet Up
1. Host to guest: `/cyberblock meetup <guest>`; the guest accepts with the clickable chat line (or `/cyberblock meetup accept`)
   within 60 s. V appears at the guest's coordinates (the streets load first, V lands on the ground) and Steve follows. Do it
   from about 1 km away.
2. Guest to host: `/cyberblock meetup <host>`; the host accepts; the guest appears next to the host's Steve.
3. A second request within 30 s is refused; so is one in a vehicle or while V is held ("blocked" with the reason).
4. Cyberblock.log has the teleport lines (grep `teleport`): see P3-9.

### P3-6. Breaking a replica block (creative)
1. As the creative guest, break a replica block: it **comes back in about 10 s**; Night City (the host's game) is unchanged;
   there is no `block` event for the host.
2. Survival: it cannot be broken, and TNT never removes it (a guest's TNT still reaches the host's game when the host allows
   explosions). Setting **Creative can erase the replica permanently** on: the broken block stays broken until a probe
   reports the spot again.

### P3-7. Two hosts (when available)
**First** (the server owner, once): the server wrote `config/cyberblock/server.json` on its first start and keeps it, so a server
that ran before Phase 3 still has `"maxHosts": 1` (the new default 4 is only for a new file). Set `"maxHosts": 4` in that file,
save and restart the server; its startup line must say `maxHosts 4`. Without this the second player silently joins as a guest.

A second player with Cyberpunk joins as a host while you are one: both see their own Night City, both streets are in the
replica, a hit on one host's NPC never reaches the other host (even if their ids collide), time and weather stay the first
host's. `tools.mpcheck twohosts2` already checks this with fake hosts.

### P3-8. The Atlas as a guest
As a guest open the Atlas (the map key): it draws the streets the hosts explored (the server's shared map), not an empty map.
A host's own Atlas keeps its own survey and also adds to the shared map.

### P3-9. Which way is it working? (the runtime paths)
The plugin and the server each write **one line** when a feature has a fallback. Open `red4ext\logs\Cyberblock.log` (and the
server log, or `latest.log` of the host) and copy these lines to Claude. The exact wording belongs to the packages that print
them: **search for the word**; the answers in the right column are what each result means.

| Feature | Search for | The line says |
|---|---|---|
| Materials of the probe hits | `material` | the names come from the hit's material (best), from the hit mesh's appearance name, or from the guess by height and district (fallback); names the table did not know are logged once each, as `unknown` |
| Doors and devices | `device` | probe rays that hit a device are cast again past it (doors never become walls); or the device filter of Phase 4 is needed |
| Weather | `weather` | which reader gives the weather (Codeware's weather state), or that it cannot be read; names that are not mapped count as `other` |
| Teleport (Meet Up) | `teleport` | the way V is moved (hold high until the streets load, then snap to the ground) and the result of each move: ok, `no ground`, `timeout` or `blocked (reason)` |
| Park | `park` | `park: on (<reason>)` and `park: off (<reason>)`, and whether V is unseen by NPCs (the invisible way) or held at a fast-travel point (fallback) |
| Server | `replica`, `meetup`, `env` | replica blocks placed and removed, Meet Up requests and results, and the clock and weather of the first host |

### P3-10. The web map (optional)
Only if you installed BlueMap ([docs/WEBMAP.md](WEBMAP.md)): open the page in a browser; the replica streets are there from
above in their colours, neon included; a block a guest places shows up soon after. Tell Claude what looks wrong; the fix is a
resource pack in `config/bluemap/packs/`.

### Phase 3 descopes and risks (short)
Material accuracy depends on Phase 0 (b): without the real material names the palette is a guess by height and district;
the weather depends on the Codeware reader; the replica grows with exploration (disk; Phase 9 measures it); a host's client
needs a re-mesh when Cyberblock is switched On or Off; the web map is optional. Details: `docs/notes/phase3-descopes.md`.

## Re-test: Wave A (Phase Q + Phase 1 + Phase 2 tools)

One combined session for everything Wave A built: **Phase Q** (quick wins), **Phase 1** (the server/client split: every
world, singleplayer too, now runs through a small server), the **Phase 2 tools** (server sync, modpack, resource pack,
server bundle, server admin) and **Phase 0's CET checks**. Plan: `docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md`
sections 3 to 6. The numbered details of each Phase Q item stay in "Re-test Q" and of Phase 1 in "Re-test 1" below; this
section is the order to play them in plus the checks the merge added. Tell Claude "W-A3: what you saw" and it reads
`Cyberblock.log`, `latest.log` and the game window. Restart Minecraft the way you always do: it builds the merged mod.

**What to try (one screen):**
1. **Solo, survival:** mine a placed stone at eye height and at your feet; hold attack through the inventory (W-A1).
2. **Solo:** walk up a staircase of five placed blocks: no vault or climb; a kerb and a ladder still work (W-A2).
3. **Solo, F5:** walk, sprint, glide, wind-charge launch, shield; a wall and a corridor; `/cyberblock debug view on` (W-A3).
4. **Solo, creative:** break a street outline block, it is back in about 10 s; the settings screen; the void rescue (W-A4).
5. **Solo:** Gorilla Arms, a charged blow and quick jabs, standing and sprinting (W-A5).
6. **Solo:** drive V more than 300 m with Cyberblock Off, then F9: a floor under Steve at once (W-A6).
7. **Solo:** a short Re-test 3 pass (blocks, TNT, a hack, a perk, an implant, Y/L/O, Sandevistan) behaves as before (W-A7).
8. **Solo:** the Phase 0 CET lines (W-A8), then paste the `CBP0` lines to Claude.
9. **Two local clients:** ask Claude to start the local test server; join it, add the test guest; TNT, hits, toggles,
   scans, quit and rejoin (W-B).
10. **Apex, deployment day (only when you are ready):** answer the Apex questions, then megaplan section 6.3 step by step (W-C).

| Part | What changed |
|---|---|
| Phase Q | survival mining, stairs and the step-up assist, the third-person camera, outline blocks in creative, the settings screen, Gorilla Arms charged blows, the UI coverage list |
| Phase 1 | client relay and server sessions (one per Cyberpunk host), guest toggles, version handshake, reconnect, rate limits, the DLL's `session`; singleplayer uses the same path |
| Phase 2 tools | `tools.server_sync` (backup, verify, upload; dry run by default; no delete), `tools.modpack`, `tools.respack`, `tools.server_bundle`, the "Join Cyberblock" title button, `/cyberblock status` and `/cyberblock hosts add|remove` |
| Phase 0 | `docs/notes/phase0-feasibility.md` (Cyberpunk checks b to h) and the contract amendment in the megaplan; the CET lines below confirm them |

### W-A. Solo (singleplayer with Cyberpunk)
**W-A1. Survival mining** (Re-test Q, Q1).
1. Survival, iron pickaxe, a placed stone at eye height and one at your feet: cracks grow and each breaks in vanilla time.
   With `/cyberblock debug aim on` the log shows `aim: target block d=.. limit=..` on the block every tick, the stage rising
   0 to 9 and no `destroying=false` while attack is held.
2. Hold attack on a stone, open and close the inventory (E) while still holding: breaking resumes without a new click
   (`aim: screen closed, attack held`). Release during the screen: attack is not stuck afterwards.
3. Place a block about 1 m in front of a wall and aim at it: the aim distance (`aim:` lines) is the wall's distance, not the
   block's.

**W-A2. Stairs** (Re-test Q, Q2).
1. Walk up a staircase of five 1-block placed blocks with the step-up assist on: no vault or climb, V is lifted step by step
   (`locomotion: step up ...`). Jumping at a block logs `locomotion: climb refused on Cyberblock box` or
   `locomotion: vault refused on Cyberblock box`.
2. Assist off (Esc, **Cyberblock...**, Step-up assist; log `locomotion: step-up assist off`): V climbs the staircase by
   jumping, still with no vault or climb animation.
3. A real Night City kerb or low wall still vaults, a high ledge still climbs, a ladder still works; none of them logs `refused`.
4. A block placed against a Night City wall or under an overhang never lifts V into or through the geometry; step-up never
   fires in a vehicle, a scene or a menu.
5. Release W while a menu is open, close the menu and stand next to a block: V is not lifted (no stuck MoveX/MoveY axis).

**W-A3. Third person** (Re-test Q, Q3).
1. F5, then walk, sprint, glide and launch (a wind charge or a mace burst under V): no Steve jitter against the camera, no
   one-frame standing pose; the overlay shows interp 1.00 and steve-V near 0. The launch pose was not covered by the fake
   host: watch it.
2. Back flat against a wall and into a corner: the camera comes in close to V's head and never shows the inside of the wall
   or what is behind it. Turning in a narrow corridor never clips.
3. The crosshair hits what it covers.
4. `/cyberblock debug view on`: within a tick the overlay shows `collide <m> smooth <m> age <ms>` and the 5 s `view:` line has
   a number for collide (not `n/a`). `/cyberblock debug view off`: the DLL log shows `prefs: ... debug off` and `cam` stops
   carrying `vdbg`.
5. Distance, shoulder, FOV and recenter change in Esc, **Cyberblock...**. Vehicle distance may do nothing: report which.
6. Log (Cyberblock.log): `view: gate open` on foot in Active; `view: gate menu`, `braindance`, `photo mode`, `scene` or
   `workspot` in those states, with third person off in each. In a car, switching camera with V logs
   `view: vehicle camera FPP|TPPClose|TPPMedium|TPPFar (tpp ...)` on each switch.

**W-A4. Outline blocks and the settings screen** (Re-test Q, Q5; now on Phase 1's per-host barriers).
1. Creative, holding a pickaxe or an empty hand (not a sword: vanilla creative cannot break blocks with a sword): break the
   street outline block under the crosshair. Log `outline: <name> broke x y z (snap back in 10 s)`, then about 10 s later
   `outline: restored 1` and the block is back; Cyberpunk receives no block event.
2. Survival: the outline block cannot be broken; TNT never removes outline blocks.
3. Esc: a **Cyberblock...** button at the top left opens "Cyberblock settings" (QB's real screen; **Done** returns to the
   pause screen). Turn **Creative can erase the replica permanently** on (log `outline: permanent erase on (by <name>)`),
   break an outline block: `kept: permanent erase`; it stays broken until V walks away and back (`outline: restored N`).
4. `/cyberblock settings` opens the same screen.
5. Void rescue with Cyberpunk Off (Steve not driven by V): in creative and not flying on the outline street, break the block
   under you: you fall, are put back on the last solid spot with no fall damage, and the action bar says "Caught you: back
   to safe ground" (log `outline: rescued <name> to x y z`).

**W-A5. Gorilla Arms** (Re-test Q, Q6).
1. Implant, empty hand, standing: a fully charged swing knocks the pedestrian down (Cyberblock.log `knock: ... tier=knockdown`
   at about `s=7`); uncharged clicks stagger (`tier=stagger` at about `s=4.5`).
2. Sprinting, quick uncharged jabs: each staggers (about 4.5 m/s), never a knockdown. Sprinting with a fully charged punch:
   still a strong knockdown (9.5 m/s).
3. The Gorilla Arms perk only: a sprinting quick jab staggers; a sprinting charged blow knocks down (7 m/s).
4. A mob hitting an NPC and the slam/lunge abilities (L): knockback unchanged.

**W-A6. Far activation** (Phase 1, 1N).
1. Drive V more than 300 m with Cyberblock Off, then F9: no `relay: <name> dropped ground x N (far)` line in latest.log and a
   solid Night City floor under Steve at once.
2. After a big teleport of Steve (fast travel or an elevator, Cyberblock On) the ground keeps building with no `far` drops.

**W-A7. Singleplayer as before** (Re-test 1, step 1; Phase 1, 1C).
1. A short Re-test 3 pass: place blocks, set off TNT, one hack, one glide, open the journal: all as before. latest.log has
   `handshake: <name> mod X dll X -> host` and `handshake: server X -> host`, and `session: <name> started`.
2. Press the scan key on an NPC: a scaninfo tooltip and no `act scan from <name> dropped` line. Then a quickhack chip, a perk
   bought in the skill tree, an implant installed and removed in the Cyberware screen, the Y/L/O keys and a Sandevistan
   sprint: each acts and none logs `bad act ... dropped`.
3. Quit to title while Cyberpunk is paused or in a menu, right after placing blocks: no collision boxes left in Night City
   afterwards, and Cyberblock.log shows `session: cleaned`.
4. `/cyberblock survey status` answers from your own game (the server asks your client with `survey_cmd`).

**W-A8. Phase 0 CET checks** (about 10 minutes; nothing changes a save). Make a manual save, free roam, do not save during
the checks. Open the CET console, paste the setup lines S1 to S8 of `docs/notes/phase0-feasibility.md` ("In-game CET
session", each prints `CBP0 ... ready`), then each line below in order and copy the `CBP0` lines back to Claude (they are
also in `<Cyberpunk 2077>\bin\x64\plugins\cyber_engine_tweaks\scripting.log`).

| # | Do this, then paste | Expected printout |
|---|---|---|
| B1 | face a plain building wall 2-20 m away: `CB.ray()` | a `Static` line with `mat=` a material name (not `None`/`nil`), `ent=none`, `obj=` a world node class, `look=` a mesh appearance |
| B2 | face a closed door: `CB.ray()` | a line with `ent=Door` (or a subclass); note the group |
| B3 | face a shop window or glass: `CB.ray()` | a glass-like material name (or a miss: glass may not block rays) |
| B4 | stand on a road: `CB.ray(true)` | a `Static` or `Terrain` line with an asphalt/road-like material |
| B5 | face a neon sign: `CB.ray()` | a material name; `ent=` a device class if the sign is a device |
| C1 | face the same door: `CB.dev(CB.hit or CB.look())` | `CBP0 Door id=<number> pid=<text> state=... door=... CLOSED ... locked=false` |
| C2 | `CB.act("ActionToggleOpen")`, wait 1 s, `CB.dev(CB.d)`, then `CB.act("ActionToggleOpen")` again | the door opens (`OPENED`), then closes |
| C3 | `CB.act("ActionToggleLock")`, `CB.dev(CB.d)`, then again | `locked=true`, then unlocked |
| C4 | face a light: `CB.dev(CB.look())`, `CB.act("ActionToggleON")`, then again | the light goes off, then on |
| D1 | `CB.spawn("Character.tyger_claws_gangster1_ranged1_nue_ma")` | a Tyger Claw appears about 10 m ahead within 2 s |
| D2 | after 2 s: `CB.npc()` | `CBP0 NPCPuppet high=...Relaxed... att=...` |
| D3 | `CB.hostile()`, wait 3 s, `CB.npc()` (get ready to fight) | he attacks; `high=` shows `Combat` |
| G5 | while he fights: `CB.park(true)`; wait 30 s next to him; try to save | `parked=true`; he searches, V takes no damage, saving is refused |
| G6 | `CB.park(false)`, then `CB.despawn()` | `parked=false`, then `deleted true` |
| D5 | `CB.spawn("Character.prevention_maxtac_rifle_ma")`, `CB.npc()` after 2 s, `CB.despawn()`; then the same with `"Character.ncpd_base_drone_wyvern"` | a Max-Tac trooper, then an NCPD drone appear and are deleted |
| E1 | `CB.heat()` | `CBP0 heat 0` |
| E2 | `CB.heat(2)`, wait 2 s, `CB.heat()` | two wanted stars; `CBP0 heat 2` |
| E3 | `CB.heat(0)`, wait 5 s, `CB.heat()` | the stars go; `CBP0 heat 0` |
| E4 | `CB.gang("tygerClaws")`, then `CB.gang("tygerClaws",Enum.new("EAIAttitude","AIA_Friendly"))` | the current attitude, then `AIA_Friendly` |
| E5 | `CB.gang("tygerClaws",CB.was)` | the first attitude again |
| F1 | look at a pedestrian, a ganger and a vendor in turn: `CB.tags()` | `npc fac=... type=Human arch=... g=<Female or Male> ... vendor=... stock=...` |
| F2 | look at a parked car: `CB.tags()` | `car type=...Car model=... look=<appearance> main=<three numbers or ->` |
| G1 | `CB.go(2000,0,80)` | `CBP0 moved`; V falls in a far place |
| G2 | at once and every 2 s until it hits: `CB.snap()` | `no ground yet` a few times, then `ground at z=<number>` |
| G3 | `CB.back()` | `CBP0 home` |
| H1 | look at a pedestrian about 10 m away: `CB.t=CB.look() CB.p(CB.n(CB.t:GetClassName()))` | `CBP0 NPCPuppet` |
| H2 | walk about 40 m away behind a wall: `CB.hack()` | `CBP0 asked ping at 4x m`; the ping outline; Cyberblock.log `hack ping on <id>: ok (...)` |
| H3 | a vending machine or TV: `CB.t=CB.look()`, 40 m away behind a wall, `CB.hack("device")` | its distraction starts; `hack device on <id>: ok (distract ...)` |
| ENV | `local t=Game.GetTimeSystem():GetGameTime() local w=Game.GetWeatherSystem():GetWeatherState() CB.p(GameTime.Hours(t)..":"..GameTime.Minutes(t), w and CB.n(w.name) or "no state")` | `CBP0 <h>:<m> <weather preset name>` |
| C5 (last) | note C1's `id=`; load the save you made; face the same door: `CB.dev(CB.look())` | the same `id=` as in C1 |

### W-B. Two local clients through a local test server (Phase 1)
Ask Claude: "start the local test server". Claude starts a dedicated Fabric 26.3 test server from a temporary worktree
(Gradle never runs in the folder you play from) on `127.0.0.1:25929` (offline mode, local only), whitelists and ops you, and
starts the offline test guest `CbGuest1` with `uv run python -m tools.mpcheck --guest 25929`. You join with your own
Minecraft (Multiplayer, Direct Connection, `127.0.0.1:25929`) and Cyberpunk, F9 on.
1. You are the host: latest.log `handshake: server X -> host`; `/cyberblock hosts` and `/cyberblock status` list you with
   `mod X dll Y, up N/s, dropped N` and the content counts.
2. The guest places TNT next to V: it explodes in Night City. `/cyberblock guests explosions off`: the same TNT breaks
   Minecraft blocks only and the guest's action bar says blocked; `on` brings it back. Toggle it several times: no new
   `session: X started`, no `blocks reset:true` resync, V's collision boxes stay.
3. The guest hits an NPC's hitbox (F3+B shows the proxy boxes): the NPC flinches. `/cyberblock guests damage off` stops it.
4. Gorilla Arms and demolitions are the guest's own: a guest with the implant or perk shoves a proxy, one without does not
   (even when you have it); a guest with demolitions gets TNT radius x1.5, your demolitions never enlarges a guest's TNT.
5. The guest scans your NPC with your hacks toggle on: the scan routes and shows info. `/cyberblock guests hacks off`: the
   guest gets the guest-blocked line.
6. A creative player breaks a Night City barrier: it comes back after 10 s or when V's probes report the cell again.
7. A big TNT chain near V: if the DOWN queue overflows, latest.log shows `block resync for <name>` on the next ticks and V's
   collision matches the world again.
8. Respawn while active: no server exception; blocks resync once you are back.
9. Quit and rejoin: `session: <name> ended (...)`, then `session: <name> started`; no leftover collision; the ground comes back.
10. As a level-2 op (and from a level-2 datapack function) `/cyberblock hosts add X` is refused as unknown or unauthorized; as a
    level-3+ op it succeeds and says it applies "at their next handshake". Add a host, reconnect that client:
    `handshake: ... -> host`. As a non-op, `/cyberblock hosts` is unavailable. Every refusal shows the server's `joinHelp`
    once (set one in `config/cyberblock/server.json` to see it).

### W-C. Apex deployment day (Phase 2 tools; megaplan section 6.3)
Only when you are ready to move the Apex server to Cyberblock; nothing is deleted on the server and Rod's Ores stays one panel
change away (megaplan section 6.4). First answer the open Apex questions (Claude asks them in one message). Then:
1. Downloads into a folder outside the repo: the Fabric API and voice chat jars for 26.3 and the Fabric server launcher
   jar (6.3 A).
2. Backup: Stop in the panel, then in **your own terminal** `uv run python -m tools.server_sync backup --to
   "%USERPROFILE%\CyberblockServerBackups\rods-ores-<date>"` (it asks for the FTP password hidden), then `verify`; confirm the
   file count and size (6.3 B).
3. On the real Apex FTP, run `upload --stage <stage>` as a dry run: the listing is read with no "cannot read the server
   listing" error, a staged non-allowlisted file that already exists (for example `plugins/<some>.jar`) is refused, and new
   world files show as `new`.
4. A real world upload (`--go`): the stage folder gets a `.cbupload.json`; re-running the same command after an
   interruption prints `skip ...` for files already sent.
5. Panel changes click by click (6.3 D), first join (6.3 E): `cyberblock server: ready (protocol 1, maxHosts 1, requireMod,
   whitelist on)` in the console.
6. Import the rebuilt mrpack into a new launcher instance and start it: latest.log no longer has "Removed resource pack fabric
   from options", and the Cyberblock pack (`file/cyberblock-pack-<ver>.zip`) is active under Resource Packs; the title screen
   shows **Join Cyberblock**.

**Descopes and risks (Wave A):** `maxHosts` stays 1 until Phase 3; a guest does not see Night City's people yet (figures are
Phase 3); a world opened to LAN refuses the offline test guest (W-B uses a local offline server instead, or a friend with a
real account); the vehicle camera distance, the climb refusal's obstacle point and a third-person FOV setter are unproven in
2.31; Phase 0's material names, combat AI of spawned NPCs, heat write and the stable device id wait for the CET lines; the
voice chat port and Java 25 on Apex wait for the panel answers; `call` and `buy` device actions and turrets as Night City
spawns are dropped (megaplan amendment of section 1.9).

## Re-test Q (do this first)

One session for **Wave A**: Phase Q (below) and Phase 0's CET lines (Q7) are in this list; the integrator appends "Re-test 1"
(the server/client split) and the Phase 2 tool checks **below the end marker of this section**, so everything is played in one
sitting. Plan: `docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 3 (Phase Q) and section 4 (Phase 0). The
player guide is `docs/HOW-TO-PLAY.md` ("Mining placed blocks, stairs and the step-up assist", "Third-person camera options
and the debug overlay", "Creative mode and the outline blocks", "The Cyberblock settings screen"); the wire is
`shared/protocol.md` "Phase Q". Tell Claude "Q3: what you saw" and it reads `red4ext\logs\Cyberblock.log`, Minecraft's
`mc\run\logs\latest.log` and captures the game window.

**What to try (one screen):**
1. Mine a placed stone with an iron pickaxe in survival: cracks grow, it breaks in the vanilla time.
2. Build a staircase of five placed blocks and walk up it: no vault or climb prompt. Try a real kerb and a ladder: they still work.
3. Press F5. Esc, then **Cyberblock...**: play with Distance, Shoulder, FOV and Auto-recenter; walk along a wall.
4. Glide, launch with a wind charge and raise the shield in third person: no flicker.
5. `/cyberblock debug view on`: look at the overlay (frame time, pose age) while you walk.
6. Creative: break the outline block under the crosshair on the street and watch it come back; survival: it cannot be broken.
7. Flip "Creative can erase the replica permanently" and break one again.
8. Esc, then Night City Menu... still opens Cyberpunk's own menu; read `docs/notes/ui-coverage.md` against what you see.
9. Try a Gorilla Arms charged punch (Q6).
10. Run the Phase 0 CET lines (Q7) and paste the output to Claude.

| Item | What changed |
|---|---|
| **Q1** mining placed blocks | The aim limit has a 0.35 m margin, smoothing and a 0.5 s sticky target; a held attack is not released by a screen opening; V on the ground no longer counts as mid-air for the mining speed; the Cyberpunk aim ray ignores Cyberblock's own boxes. |
| **Q2** stairs | Vault and climb are refused on Cyberblock boxes; a step-up assist lifts V onto a single block (a setting, on by default). |
| **Q3** third person | Four-ray camera collision, smoothing, shoulder offset with auto-recenter, distance, FOV and vehicle distance options; Steve is placed per rendered frame; steady elytra, shield and use-item poses; a crosshair at Steve's own aim; a debug overlay and log. |
| **Q4** UI coverage | `docs/notes/ui-coverage.md`; a Minecraft-style loading screen; the "Cyberblock..." button on the Night City pause screen; the "Night City Menu..." escape hatch is tested. |
| **Q5** outline blocks | Creative breaks them and they return in about 10 s (or stay broken with the new setting); survival and adventure cannot; a void-fall rescue; the first settings screen. |
| **Q6** Gorilla Arms | (package QG) the implant's strong blow needs a charged swing. |
| **Q7** Phase 0 | The CET lines that answer the Cyberpunk feasibility checks b to h (no game code changes). |

### Q1. Survival mining
1. Survival, iron pickaxe. Place a stone block at **eye height** and one at your **feet**, then hold the attack button on each.
   Expected: cracks grow steadily and each breaks in the vanilla time (about 0.4 s for stone with an iron pickaxe); not
   slower than in a normal Minecraft world. Do the same on the street in front of a wall, and with a block placed right
   against Night City's wall.
   - **Log (Minecraft):** `/cyberblock debug aim on`, then `aim: target block d=... limit=...` on every tick, never `none`
     while the crosshair is on the block. `/cyberblock debug aim off` afterwards.
2. Hold attack, then press T (or open a menu) and close it: mining carries on or stops cleanly, the button is never stuck down.

### Q2. A staircase of placed blocks
1. Build a staircase of **five** placed blocks (each step one block higher) against open street and walk up it.
   Expected: no vault or climb prompt; V goes up step by step with the assist (Esc, **Cyberblock...**, **Step-up assist**
   on), or by jumping with it off.
   - **Log (Cyberblock.log):** `locomotion: vault refused on Cyberblock box` or `locomotion: climb refused on Cyberblock box`
     (at most once a second).
2. A real kerb or ledge and a ladder in Night City: the vault and the climb still work as before.
3. The assist never lifts V onto Night City's own geometry, in a vehicle or in a scene.

### Q3. Third person
1. **F5**, then walk, sprint, **glide**, **launch with a wind charge** and **raise the shield**. Expected: no flicker of
   Steve's pose (wings open for the whole glide and launch, one steady shield pose), no clipping, no lag behind V.
2. Walk **along a wall** with the camera on the wall side and turn in a **corridor**. Expected: the camera swings off the wall
   towards the centre and back, and never shows the inside of a wall.
3. The **crosshair** sits where Steve's aim meets the target: what it covers is what you hit; at open air it is at the centre.
4. Esc, **Cyberblock...**: Distance (1.5 to 8 m), Shoulder (-1 to 1 m), FOV (0 or 50 to 110), Auto-recenter and Vehicle
   distance (a car's third-person camera; it may do nothing if Cyberpunk does not allow it: report which).
5. `/cyberblock debug view on`: an overlay at the top left; frame time and pose age stay steady while you walk and glide.
   - **Log:** latest.log `view: frame <ms> pose age <ms> interp <f> dist <m> collide <m>` every 5 s; Cyberblock.log
     `view: dist <m> collide <m> shoulder <m> recenter <on|off>` on a change and `view: gate <reason>` when third person is
     refused (braindance, photo mode, scenes, menus, vehicles' cameras).

### Q4. UI coverage and the escape hatch
1. Read `docs/notes/ui-coverage.md`: every Cyberpunk screen and layer in it matches what you see (Minecraft, pass-through or
   never touched), and each says which phase converts it.
2. A loading cover (load a save or travel far): a dark dirt-style Minecraft loading screen with "Loading Night City...",
   animated dots and a tip.
3. Esc, **Night City Menu...** still opens Cyberpunk's own menu; the Night City pause screen has a **Cyberblock...** button.

### Q5. Outline blocks and the settings screen
1. **Creative:** break the invisible outline block under the crosshair on the street. Expected: it **comes back in about
   10 seconds**.
   - **Log (Minecraft):** `outline: <name> broke <x> <y> <z> (snap back in 10 s)`, then `outline: restored N`. Nothing is
     sent to Cyberpunk (no `block` event in the Cyberblock.log).
2. **Survival:** the same block cannot be broken. Explosions (TNT) never remove outline blocks.
3. Esc, **Cyberblock...**, switch **Creative can erase the replica permanently** on (or `/cyberblock outline erase on`;
   `/cyberblock outline` shows the state): break an outline block in creative. Expected: it **stays broken until you walk
   away and come back** (`outline: <name> broke ... (kept: permanent erase)`). Switch it off again.
4. Dig through the street in creative and fall: you are put back on safe ground with "Caught you: back to safe ground".
5. As a non-operator (a second player or a test account) the row is greyed with the tooltip "Only the host or an operator
   can change this".

### Q6. Gorilla Arms charged punch (package QG)
1. Install **Gorilla Arms** (U), empty hand. Hit a pedestrian with a **fully charged** swing (wait for the attack indicator),
   then with quick, **uncharged** clicks. Expected: the fully charged swing knocks the pedestrian down (they fall); the
   uncharged clicks only stagger them (they stumble back, never fall). **L** still does the slam.
   - **Log (Cyberblock.log):** one `knock: <id> s=<speed> tier=<tier> impulse=<i>` line per hit: about `s=7` with
     `tier=knockdown` for the charged swing, about `s=4.5` with `tier=stagger` for an uncharged click.
   (QG is built in parallel: the integrator corrects this line to the merged behaviour.)

### Q7. Phase 0 CET checks (about 10 minutes, nothing changes a save)
The CET console lines from package 0R for checks (b) to (h) of the megaplan section 4.1, each with the printout to expect.
Paste what each prints (or tell Claude to read `Cyberblock.log` when the line logs there). Every line undoes what it does.
**The exact lines and printouts are in "Re-test: Wave A", W-A8** (setup lines S1 to S8 in `docs/notes/phase0-feasibility.md`).

| Check | What it asks of Cyberpunk 2.31 | CET lines (W-A8) |
|---|---|---|
| (b) | raycast hit object and material: cast at a wall, a door, glass, asphalt and a neon sign | B1 to B5 |
| (c) | devices: read type, state and locked; open and close a door, toggle a light; a stable id across a load | C1 to C5 |
| (d) | spawn a ganger 10 m away, make him hostile, despawn him; a Max-Tac trooper and a drone | D1 to D5 |
| (e) | raise heat to 2 and back to 0; make one gang friendly toward V and back | E1 to E5 |
| (f) | print role, affiliation, gender and archetype of a few NPCs, a car's record, class and colour, and a vendor's stock | F1, F2 |
| (g) | teleport V 2 km away and back; park V for 30 s next to a hostile NPC | G1 to G3, G5, G6 |
| (h) | hack an NPC and a device 40 m away behind a wall through the event pump | H1 to H3 |

**Descopes and risks (Phase Q):** the vehicle camera distance may not be settable in 2.31 (the row then does nothing and the
DLL log says so); the climb refusal depends on reading the obstacle point (fallbacks: a raycast tag test, then a collision
preset only if NPCs and cars still stop); third-person FOV depends on a verified setter. Phase 0's descopes are listed in its
notes and in the integrator's report.

<!-- end of Re-test Q. The Wave A integrator appends "## Re-test 1 (Wave A, after Re-test Q)" and the Phase 2 tool checks directly below this line, above "In-game fixes 3". -->

## Re-test 1 (multiplayer Phase 1: do this first; it goes directly below Re-test Q, one combined test for Wave A)

**What to try (one screen):**
1. Play singleplayer as usual (blocks, TNT, a hack, the glide, the UI): nothing should have changed.
2. Only with a second player who has a real Minecraft account (see the note below steps 2 to 4): open the world to LAN; the
   guest places TNT next to V and it explodes in Night City.
3. Same guest: `/cyberblock guests explosions off`: the same TNT no longer reaches Night City; `on` brings it back.
4. Same guest: they hit an NPC's hitbox (F3+B shows the proxy boxes): the NPC flinches. `/cyberblock guests damage off` stops it.
5. Quit and reopen the world: no collision left in Night City, and the ground comes back.

| What changed | Where |
|---|---|
| The mod is a client relay plus a server with one session per host; singleplayer uses the same path | all of `mc/` |
| A guest's explosions, hits, blocks, scans and hacks reach the host's Cyberpunk game, switchable with `/cyberblock guests` | server |
| Version handshake and messages; reconnect with the "Reconnecting to ..." screen; rate limits and the overflow kick | client and server |
| The DLL's `hello` carries `ver`; a new `session` message tells it its role and cleans Cyberpunk when it is not host | `cp/` |
| `tools/mpcheck.py`, `tools/content_check.py`, fake host `--name`, `--ver`, `--ids-from`, the test server templates | `tools/`, `server/` |

`<integration worktree>` below is the worktree the integrator names in its report (Gradle must never run in the folder you
play from). The automatic scenarios (`uv run python -m tools.mpcheck --all`: singleplayer, guest, versions, flood, reconnect,
twohosts) were run by the integrator, not by you; the `guest` scenario covers steps 2 to 4 with an offline guest on a local
test server.

**Steps 2 to 4 need a second real account.** A world opened to LAN checks every player's Minecraft login, so the offline test
guest of the harness (`tools.mpcheck --guest <port>`, name `CbGuest1`) is refused with "Failed to log in: Invalid session".
Do steps 2 to 4 with a friend (or a second PC) signed in with their own Minecraft account on your network; without one, skip
them (the integrator's `guest` scenario covered them on the test server).

1. **Singleplayer, as before.** Start Minecraft and Cyberpunk, F9 on, and play a short pass of Re-test 3 (place blocks, set
   off TNT, one hack, one glide, open the journal). Expected: everything behaves as before.
   Log: latest.log `handshake: <name> mod X dll X -> host` (server) and `handshake: server X -> host` (client);
   `session: <name> started`.
2. **A guest's TNT (needs a second real account, see above).** Esc, Open to LAN, Start LAN World; note the port in chat.
   Your guest joins with Multiplayer, Direct Connection, `<your PC's address>:<port>`. The guest places TNT next to V and
   lights it. Expected: it explodes in Night City too (V reacts, NPCs flee). Log: latest.log `handshake: <guest> mod X dll  ->
   guest`; Cyberblock.log shows the explosion event.
3. **The explosions toggle.** Type `/cyberblock guests explosions off`, repeat the TNT. Expected: Minecraft blocks break, but
   nothing happens in Night City, and the guest's action bar says the action was blocked. `/cyberblock guests explosions on`
   brings it back.
4. **A guest's hit.** The guest hits an NPC's hitbox (F3+B shows the proxy boxes). Expected: the NPC flinches or staggers.
   With `/cyberblock guests damage off` it does not; with `on` it does again.
5. **Quit and rejoin.** Close the guest, quit the world, reopen it (and the LAN world again). Expected: no leftover collision
   in Night City, and the ground comes back around V. Log: `session: <name> ended (...)` on the way out, then
   `session: <name> started`; Cyberblock.log shows the probes starting over (`clear`).
6. **Operators.** `/cyberblock hosts` lists the sessions with role, versions and message rates (it should show you as the
   one host).
7. **A mismatched plugin (only if you have one).** If your Cyberpunk plugin and Minecraft mod were ever from different
   releases, Minecraft says "Cyberblock: your Cyberpunk plugin is X but your mod is Y; you can play as a guest. Reinstall both
   from the same release." and Cyberpunk is cleaned up. Not needed when the versions match: the harness covered it
   (`tools.mpcheck versions`).

**Descopes and risks:** `maxHosts` stays 1 until Phase 3, so a second linked player joins as a guest; the payload size cap may
force more splitting of `ground` and `npcs` than planned (measured in 1-N1); a guest cannot see NPCs yet (figures come in
Phase 3); the harness scenarios assume the devtools commands of 1-N4 and that `runServer` honours `-Pcyberblock.runDir`;
the guest-side "blocked" action-bar message can only be seen in game. The harness's offline `--guest` client cannot join
a LAN world (26.3 authenticates every LAN login); the integrator decides between a devtools way to open a LAN world without
authentication, the user joining an offline test server, or a second real account (escalated by package 1T).

**needsInGame appendix:** each package's `needsInGame` checks (1N, 1W, 1C, 1L, 1H, 1T) are in "Re-test: Wave A" (W-A6,
W-A7 and W-B).

## In-game fixes 3 (2026-10-03 evening; do these first)

Three things from the 20:05-20:16 session (`fix/ingame3`):

| Item | What was wrong, and what changed |
|---|---|
| **G1** flying into something gets you stuck | The drive stopped V 0.3 m from the wall and kept it there every frame, because Minecraft (which does not have the building) glided on and the DLL kept following: V frozen, mouse turn stripped, until a save was loaded (log 20:15:37 "V stopped short", Steve glided on to 20:15:49). Now a collision ends the flight: V is put on the ground below the stop, cam.fly goes false, Minecraft calls the glide off, and the DLL does not take the same glide up again. A watchdog on both sides releases V if V and Steve drift apart for 1 s. Clip rays at shins, middle and head, 0.5 m margin. |
| **G2** hits do no damage and no knockback | The damage landed (NPCs went "defeated"), but no hit reaction ever played: `HitReactionComponent.EvaluateHit` returns at once for a hit without a hit shape, and ours had none, so no flinch, stagger or knockdown, and a defeated NPC was never finished off. Every hit on a person now carries a chest hit shape. `impulse=0` in `knock:` lines was never a lost push: it is the ragdoll impulse, 0 below the ragdoll tier. The Gorilla Arms perk made every plain swing 7 m/s (a knockdown each time); it now adds half as much (4.5, a stagger). |
| **G3** third person lags behind | The compositor re-projects Minecraft's older frame as if everything in it stood still, so Steve (drawn at V's feet of that frame) slid back by V's motion since; gliding, Minecraft also drew him at his own physics position, ahead of V and the camera. Now the effect moves Steve's pixels (his capsule) with V, Minecraft draws a third-person glider at V's feet, and the game's mouse turn is stripped during a glide in third person too (it fought the drive's owned yaw). |

1. **G1, wall:** glide (elytra, Space from a roof) straight into a building side. Expected: V stops at the wall, drops to
   the ground below at once with no damage, mouse and WASD work immediately, Steve is with V again within half a second.
   Cyberblock.log: `flight: V hit Night City geometry, the glide ends here (V put on the ground below)` and the flight
   summary with `clipped yes`; latest.log: `glide called off (the host stopped following)`. No `flight: start` follows
   until you open the elytra again. Repeat in third person (F5) and into a ceiling/overhang while climbing.
2. **G1, low pass:** glide low over a street into a kerb or a car roof: the same clean end, V on the street.
3. **G1, normal glides:** a long glide over open streets ending on a roof still lands normally (`flight: landed (glide)`),
   and no `flight: watchdog` line appears in a normal session.
4. **G2, plain hits:** punch and sword a pedestrian (no sprint). Each hit: a visible flinch, health drops (a sword about a
   third), after ~4 sword hits they go down (defeated), the next hit kills (`killed human <id>`). `knock: ... tier=impact`.
5. **G2, stronger hits:** sprint hits stagger (`tier=stagger`, they stumble back); with the Gorilla Arms perk plain hits
   stagger and sprint hits knock down (`tier=knockdown`, they fall); only gauntlet + Gorilla Arms perk + sprint ragdolls,
   short, nobody flies across the street.
6. **G3, third person:** F5, walk, sprint, then glide and turn hard with the mouse. Steve stays fixed in the frame (no
   sliding back when you start moving, no jitter while turning); blocks and the street stay registered with Night City.
   Look for a faint ghost of Steve or a hole behind him during fast moves (report it if you see one).

## Re-test 3

## Fast survey re-test (branch feat/survey-fast)
The first survey needed 5 h 36 min for 2116 fixed stops (about 9 s each: a 1.5 s wait, then 12321 rays at 400 per 100 ms
tick). It is now planned to a 15-minute budget: only the city (fast-travel points outside the Badlands, never the open sea),
then the Badlands coarser; the DLL casts the rays itself every frame; each stop waits 0.5 s (2 s at most), measures how far
loaded collision reaches and maps all of it while V flies on to the next stop; the detail (2/4/8/10 m) follows the measured
speed. Guide: `docs/HOW-TO-PLAY.md` "The Atlas (map)"; wire: `shared/protocol.md` "Atlas" (Tour).

### S1. It plans the city, not the sea
1. On foot, `/cyberblock survey redo` (or `/cyberblock survey` on an empty map).
- **Log:** `survey: rays ready, query filter (...)` once (**report** it if it says `one ray per collision group` or `rays
  disabled`), then `survey: area from P pins: city hull A km2 (C city points), outskirts hull B km2; squares left: X city
  (.. km2) + Y outskirts (.. km2) ...` and `survey: plan: city every 2 m, outskirts every 8 m, about S s left of the 720 s
  plan (..., guessed)`. **Report** A, B, X, Y. Expect A about 20-35 km2, X about 500-900; C should be most of the
  fast-travel points (if it says `dense-pin points`, the district lookup failed: report it).

### S2. Stops are short and the map is useful within minutes
1. Watch the first minute: V starts over V's own square and the Atlas (M) fills outward from there.
- **Log:** one line per stop: `survey: stop n at (gx, gy) [city]: settled after T s, reach R m (sampled R m), C columns,
  H hits in M ms (U us/column), S s in all; d of N squares, eta ...`. **Report** typical T (expect 0.5-0.7), R (how far
  collision reaches: 150-600), U, S, and how many stops say `not settled` or `nothing streamed`.
- After the first stops a new `survey: plan: ... (measured)` line appears whenever the detail changes. **Report** the
  city and outskirts cell sizes it settles on.
- The game runs at about 25 fps while it surveys; menus (Esc) still open at once and pause it.

### S3. It finishes within 15 minutes
1. Leave it alone. **Expect:** the boss bar "Atlas survey (city): n of N squares (p %), ~M min left", the minutes going
   down steadily (not jumping up by much), then "(outskirts)", then the toast "Atlas survey done", all within 15 minutes of
   wall time (menu time not counted). V is back exactly where V stood.
- **Log:** `survey: done (every square surveyed) after K stops in S s this run, N of N squares done`. **Report** K and S.
  If it says `stopped (15-minute budget used; ...)` instead, report the last plan line; `/cyberblock survey` resumes.
2. Press M: the whole city is drawn (coarser squares where the plan chose 4-10 m), the Badlands blockier; no stripes of
   parchment between stops. **Report** any gap.

### S4. Driving maps more
1. Drive fast through an unmapped part (or after `/cyberblock survey redo` and stop at once). **Expect:** a band on each
   side of the road appears on the Atlas and radar while driving, wider than on foot; frame rate as before.

### S5. Safety is unchanged
Repeat A4 steps 2-4 and A5 below (menu pauses, saving refused, F9 / stop / vehicle / combat put V back exactly).

## Atlas re-test (branch feat/atlas)
The Atlas (M) now draws Night City from Cyberpunk's collision, and `/cyberblock survey` maps the whole city once. Player
guide: `docs/HOW-TO-PLAY.md` "The Atlas (map)"; wire: `shared/protocol.md` "Atlas".

### A1. The Atlas answers at all (the root cause of the empty map)
1. Load a save, F9 on, press **M**. **Expect:** pins all over Night City (fast-travel points, vendors, ripperdocs, gigs,
   quests), not just the objective marker; hover one: its name, kind and (fast-travel points) district.
- **Log:** `ui: pump ready` (possibly "found on a later look"; never only `ui: pump not found yet`), then on M
  `ui: action atlasreq`, `script: ui: atlas reply: N pins of M map mappins, K more fast-travel points` and
  `ui: reply atlas 0 groups, 0 lines, N pins`. **Report** N, M and K.
2. J (Journal) and the phone also answer (they share the pump).

### A2. The map fills in as you move
1. Walk and drive a few blocks, then press M. **Expect:** the streets you passed are light sand, buildings grey (taller is
   darker), water blue, with Minecraft map shading; unmeasured areas stay parchment. Zoom in: crisp squares. Zoom out far:
   it still draws (region textures). The radar (top left) shows the same map under you, turning with the camera.
2. Quit Minecraft and start it again (or open another world): the map is still there.
- **Files:** `%APPDATA%\.minecraft-cyberblock\cyberblock\nightcity-map\r.*.cbat` appear within 30 s.
- **Report** anything mis-coloured (a street drawn as a building, water missing, the map mirrored or offset from the pins).

### A3. Pins, filters, districts, tracking
1. Toggle **Quests / Travel / Shops / Gigs / Places**: each kind hides and shows. Zoomed out, pins thin out instead of
   piling up, and district names appear.
2. Select a quest pin, press **Track**: the quest is tracked (the quest panel and the red marker follow).
- **Log:** `script: ui: tracked pin <i>` (or `track pin <i> unresolved (...)` for a non-quest pin, with a toast).

### A4. The survey tour (AFK)
1. On foot, out of combat, type `/cyberblock survey`. **Expect:** a boss bar "Atlas survey (city): n of N squares (p %), ~M min
   left" (feat/survey-fast; S1-S5 above); V is moved from stop to stop high above the city, invisible, taking no damage; the
   Atlas fills in around each stop.
2. Open a Cyberpunk menu (Esc passes through the Minecraft pause screen; or Night City Menu...): the bar says paused.
3. `/cyberblock survey status` prints the progress. Try saving: it must be refused while the tour runs.
4. `/cyberblock survey stop` (or F9): **V is put back exactly where V stood**, visible, saving works again.
5. `/cyberblock survey` again: it resumes (the stops already done are skipped). When all are done: toast "Atlas survey done";
   another `/cyberblock survey` says Night City is already mapped; `/cyberblock survey redo` starts over.
- **Log:** `survey: start asked by Minecraft (n squares already done)`, `survey: area from P pins: ...` (S1),
  `survey: started: V hidden, invulnerable, saving locked`, one `survey: stop n at (gx, gy) [...]: ...` per stop (S2),
  `survey: done|stopped (...)`,
  `survey: V put back at (...)` (DLL and script), `survey: V visible again, survey god mode and save lock removed`.
- **Report** the bounds line, how long a stop takes, and any stop where V was left somewhere or fell.

### A5. The survey refuses and ends safely
1. In a vehicle, in combat or during a scene: `/cyberblock survey` → toast "Atlas survey refused" (vehicle / combat / held).
2. While it runs: get V into a scene or let combat start (unlikely while invisible), or quit Minecraft: it ends with V home.

## Re-test 3 (do this first)

The third session (`out/user-issues.md`, section "Re-test 3 feedback") raised thirteen items, **F1 to F13**. They are all
built on this version; this list is one check per item, in the user's order. (The R1-R6 below, under "Re-test 2", are an
older list.) The plan behind them is `docs/superpowers/plans/2026-10-03-retest3-polish.md`; the wire is `shared/protocol.md`
("Re-test 3"); the player guide is `docs/HOW-TO-PLAY.md`.

| Item | What was wrong, and what changed |
|---|---|
| **F1** every quickhack feels the same | The hack wheel fired the first chip (Short Circuit) over the chip in your hand, and no chip had its own look. The chip in your hand wins now; every chip has its own Minecraft effect and a distinct Cyberpunk effect, also on crowd pedestrians. |
| **F2** a Minecraft UI for the rest of the game, third person | Radar, eddies, wanted stars, quest, locator-bar waypoint, prompts, choices, subtitles, toasts, calls, journal, phone, atlas and pause are drawn by Minecraft. **F5** is a third-person view. |
| **F3** Steve visible in vehicles | First-person: Steve's hand, no hotbar. Third-person camera: the whole seated Steve. |
| **F4** the glide could not turn | The DLL now owns the glide's yaw and applies your mouse turn; it steers like the vanilla elytra. |
| **F5** ground alignment | Perfect, unchanged: a regression check only. |
| **F6** arm cyberware models | 3D models for Mantis Blades, Gorilla Arms, Monowire and the Projectile Launch System, first and third person. |
| **F7** hits fling NPCs | Vanilla-like pushes; stagger, knockdown and ragdoll only on stronger hits. |
| **F8** one key for all implants | **Y** = Operating System implant, **L** = Arms implant, **O** = Optical Camo; six rebindable keys, no clashes. |
| **F9** deck and Sandevistan together | Two Operating System places. |
| **F10** Sandevistan looks like nothing | A ReShade screen effect (chromatic split, tint, ghost trails, edge glow) and a Minecraft flourish. |
| **F11** release-ready | Release zip with installer, launcher install, README, CHANGELOG, licences, CI, version 1.0.0. |
| **F12** the old Cyberpunk UI must never show | Every Cyberpunk layer is invisible; menus are Minecraft screens; the few that cannot be replaced are restyled and framed. |
| **F13** prompts and choices | Minecraft-styled prompt box and choice list, still driven by Cyberpunk's input. |

**Before testing:**
1. Close Cyberpunk. Either install from source (`uv run python -m tools.install` after building the DLL) or, for F11, the
   release zip (`uv run python -m tools.package`, then `install.bat` from the unzipped folder).
2. **Restart Minecraft** (`uv run python -m tools.play`, or the launcher installation): a Minecraft that was already running
   still has the old mod.
3. Load a save on a street, press **F9**, and wait for "Cyberblock on". Use survival (the kit is handed out) unless a check
   says otherwise.
4. To try the interface without Cyberpunk, a running Minecraft client can be driven by the fake host:
   `uv run python -m tools.fakehost --ui --hold 12`, `--hacks` (answers hacks with `upload`, `kind` and spreads) and
   `--seat` (a vehicle, `fp` flipping every 2 s).

**Logs to grep** (`<Cyberpunk 2077>\red4ext\logs\Cyberblock.log` for the DLL and scripts, `mc\run\logs\latest.log` for
Minecraft):

| Where | Line |
|---|---|
| DLL | `Cyberblock 1.0.0 loading`; `Minecraft says hello: mod cyberblock 1.0.0, Minecraft 26.3`; `version mismatch: DLL 1.0.0, mod X` |
| DLL | `binds: hack G, skills K, cyberware U, activate Y, arms L, camo O` |
| DLL | `flight: N frames, ..., yaw start A end B, mouse x N counts at R deg/count, pitch min..max`; `look: R deg per mouse count` |
| DLL | `seat: on (third person\|first person)`, `seat: off`; `view: third person on (distance D m)`, `view: first person (<reason>)`, `view: applied, eye moved D m` |
| DLL | `screenfx: sandevistan S s`; `compositor: menu pass-through on\|off` |
| DLL | `ui: pump ready` or `ui: pump disabled: CyberblockUi.Pump missing`; `ui: action <a>`; `ui: unpaused (<reason>)`; `ui: reply <kind> <n> groups, <m> lines, <k> pins, <b> bytes` |
| Scripts | `ui: ink layers <names>`, `ui: hidden (on)`, `ui: restored (<reason>)`, `ui: shown for <reason>`, `ui: menu <name> -> minecraft <screen>`, `ui: pass-through <menu>`, `ui: paused by Minecraft`, `ui: resumed`, `ui: notify <kind>`, `ui: escape hatch <menu>` |
| Scripts | `hack <name> on <id>: ok (<kind>, upload <s>s, +<n> spread)`; `hackfx: <hack> <kind> <what>`; `knock: <id> s=<s> tier=<tier> impulse=<i>`; `seat: slot <slot> hips (<x>, <y>, <z>) tpp <b>`, `seat: no Hips slot`; `cyberfx: <id> <s> s` |
| Minecraft | `hackfx: {} on {} upload {} s`; `webs: placed {} removed {}`; `binds: unbound vanilla {} ({})`; `cyberware: fire {} ({})`; `ui: screen {}`; `persp: {}`; `launcher: opening {} (autostart\|button)` |

### F1. Every quickhack is unique (and the chip in your hand fires)
Survival, the kit (deck and all ten chips), a **ganger** (a hostile NPC in a gang area: a red dot on the radar) and a **pedestrian** (crowd) within 20 m.
1. **Chip in hand.** Put **Blaze Chip** on a hotbar slot and select it. Hold **G** on the ganger, roll the wheel to
   **Redstone Charge**, right-click.
   - **Expect:** the action bar says "Hack: Blaze Chip" (orange); the **Blaze Chip** hack runs, not Short Circuit. Select a
     hotbar slot that holds a different chip: that one fires, and the wheel pick is forgotten.
   - **Log:** `script: hack overheat ...` (the chip in hand), never `short_circuit` for every shot.
2. **All ten, on the ganger, then on the pedestrian.** Fire each chip once at each, from the hand. Wait for the effect (1-4 s
   after the beam starts).

   | Chip | Minecraft at the impact | Ganger (vanilla quickhack) | Pedestrian (extra) |
   |---|---|---|---|
   | Redstone Charge | visual lightning bolt, sparks | electrocuted | electrocuted (kills) |
   | Blaze Chip | flame spiral, lava pops, smoke, no fire blocks | burns | set alight 5 s |
   | Ink Sac Daemon | ink burst and halo | blinded | blinded 5 s |
   | Cobweb Daemon | **real cobwebs** round the target, gone after 6 s | cannot walk | stunned 4 s |
   | Spider Eye Virus | green cloud, arcs to people it spreads to | contagion spreads | the 2 nearest within 6 m get it |
   | Wither Skull Daemon | wither trail, soul and smoke burst | brain damage | heavy hit, no ragdoll |
   | Compass Ping | gold ring, hostiles within 32 blocks glow through walls 10 s | squad revealed | hostile NPCs within 30 m revealed |
   | Bottle of Forgetting | swirl at the head; mobs drop their target | forgets V | dazed 2 s |
   | Creeper Brain | green creeper face above the head | attacks its friends | frenzy 6 s, hits the nearest person within 4 m |
   | Redstone Lever (on a camera, traffic light, vending machine, other) | click and smoke / bell and dust / notes / dust | the device's action | - |

   - **Log:** `hack <name> on <id>: ok (<kind>, upload <s>s, +<n> spread)` with `kind` `squad` for the ganger and `crowd` for
     the pedestrian (`driver` for a car, `camera` / `traffic` / `distract` / `device` for the lever); `hackfx: <hack> <kind>
     <what>` for every extra that ran on a pedestrian. Minecraft: `hackfx: <hack> on <id> upload <s> s`, and `webs: placed N
     removed N` for the Cobweb Daemon (N equal after 6 s).
   - **Report** any chip whose look is the same as another's, or whose Cyberpunk effect is invisible.
3. **Busy.** Fire a chip, then another at the same target during the beam: "<target> is still uploading a hack", RAM not spent.

### F2. The Minecraft interface on screen, and third person
1. **Radar and stats.** The 80 x 80 radar is top left with dots for people and squares for cars; under it the eddies
   (`€$ <money>`), the wanted stars, the time, the district. Walk around: the radar rotates with the camera.
2. **Quest and waypoint.** Open **J**, **Track** a quest (the Journal screen is Minecraft's). **Expect:** the quest panel at
   the top right with its objectives and the distance, a marker on the locator bar, and the arrow at the radar's edge
   pointing at it. Complete or change an objective: the panel updates.
3. **Money and wanted.** Earn or spend eddies (a vendor, a street vending machine): the figure changes and an "Eddies" toast
   shows. Make NCPD notice you (a TNT near police): stars appear and the toast says "Wanted".
4. **Calls and messages.** When a call arrives (story or a fixer): a toast "Incoming call: <name>" with `[T] Answer / hold [T]
   Reject`; **T** answers, hold **T** rejects; **T** is chat again afterwards. A new SMS gives a "Message" toast; **Messages**
   shows the Phone screen. (If no call arrives in your save, note it as "not tested".)
5. **Third person.** Press **F5** on foot: Steve seen from behind, the camera about 3 m back; walk into a wall, the camera
   shortens; **F5** again is first person. It returns to first person in a car, a scene and a menu.
   - **Log:** `view: third person on (distance D m)`, `view: applied, eye moved D m` (once, the measured movement),
     `view: first person (<reason>)`; Minecraft `persp: back`, `persp: first`.

### F3. Steve in vehicles
Get into a car and then a bike.
1. First-person vehicle camera: Steve's hand is visible; **no** Cyberpunk HUD, V's body is hidden. The hotbar, health,
   food and armour bars and the crosshair are all hidden.
2. Switch to the third-person vehicle camera: the **whole of Steve, seated** at V's seat. Switch back and forth several times:
   V's own body never appears in either camera.
3. Get out: bars and crosshair return.
- **Log:** `seat: on (third person)` / `seat: on (first person)`, `seat: slot <slot> hips (...) tpp <b>`, `seat: off`.
  `seat: no Hips slot` means the seat position fell back (Steve may float: report the vehicle's name).

### F4. The glide steers with the camera
Elytra, rockets, a megabuilding roof. Jump off, press Space, glide for 10 s and swing the mouse hard **left**, then **right**,
then up and down.
- **Expect:** the glide turns with the camera at once, like the vanilla elytra, left, right, up and down; it is no longer
  locked to the start direction; the picture stays smooth.
- **Log:** the flight summary `flight: N frames, ... yaw start A end B, mouse x N counts at R deg/count, pitch min..max`:
  `A` and `B` differ by about the turn you made, `N` is not 0, and `look: R deg per mouse count` appears once.

### F5. Ground alignment is unchanged
On a flat street place a stone block: it sits **on** the street, not a block above it (as in Re-test 2 R4). Run
`/cyberblock level` while standing on a different floor and place another: also level.
- **Log:** `ground alignment: ...` lines as before.

### F6. Arm implants in 3D
Install **Mantis Blades**, **Gorilla Arms**, **Monowire** and the **Projectile Launch System** in turn (U; one arm at a time).
- **Expect:** each has its own model on your arm in first person and on Steve's arms in third person (**F5**), and the model
  changes while the implant is out (blades extended, launcher open). Glowing parts glow; nothing floats away from the arm.
- Also look at Kiroshi Optics (glowing eyes), Subdermal Armor and Reinforced Tendons on Steve in third person, and Optical
  Camo (Steve shimmers translucent cyan in third person).
- **Report** a model that sits wrong on the arm (the transform is adjusted in code, not in the model).

### F7. Knockback tiers
Hit pedestrians with the iron sword, an empty hand with Gorilla Arms, a sprint hit, Knockback I and II swords and the
gauntlet.

| Hit | Expected |
|---|---|
| plain hit | a flinch, no flying |
| sprint hit or Knockback I | a stagger, a step back |
| Knockback II, gauntlet or Gorilla Arms | the person falls down |
| Gorilla Arms and sprint | falls down |
| gauntlet and Gorilla Arms | falls down and slides a short way (never flies metres) |

- **Log:** `knock: <id> s=<speed> tier=<tier> impulse=<...>` with `s` about 2, 4.5, 7, 9.5 and 12 and tiers impact,
  stagger, knockdown, knockdown and ragdoll. A Gorilla slam (**L**) knocks people down, no further.

### F8. Y, L and O, and rebinding
1. **Keys.** Install Sandevistan (U), Mantis Blades and Optical Camo. **Y** starts the Sandevistan, **L** lunges (aim at an
   entity within 10 blocks), **O** cloaks you. Each key fires only its own slot; with nothing for a key the chat says "No
   ... to fire (<key>)".
2. **First start.** The first time the mod runs after the update, the chat says "Cyberblock took L from Advancements (rebind
   it in Controls)" (and O from Friends). The pause screen has no Advancements button: bind **Advancements** to a free key
   in Esc, **Minecraft Options...**, Controls.
3. **Rebinding and a clash.** In Controls (category Cyberblock) set Arms to **Y**: all six keys go back to G K U Y L O and
   chat says so. Rebind Arms to **F10** (allowed): L is Cyberpunk's again, F10 lunges. A refused key (for example **E**) falls
   back to that action's own default.
   - **Log:** `binds: hack G, skills K, cyberware U, activate Y, arms L, camo O` (changing with each rebind), Minecraft
     `binds: unbound vanilla {} ({})` (first start only), `cyberware: fire <id> (<trigger>)`.

### F9. Deck and Sandevistan together
`/cyberblock kit`, press **U**: the Operating System slot has **two places**, the deck in the first and Sandevistan in the
second. Hack a ganger (the deck works), press **Y** (Sandevistan) and hack again during it. Install **Berserk**: it replaces
Sandevistan, never the deck. Take the deck out and carry it instead: RAM still works.
- **Expect:** the advancement "Two Brains" appears. An older save with Sandevistan in the first place has it moved to the
  second.
- **Log:** `cyberware: installed <id> in operating_system`, `cyberfx: sandevistan <s> s`, `hack <name> on <id>: ok` during it.

### F10. The Sandevistan screen effect
Fire the Sandevistan (**Y**) while moving and looking around.
- **Expect:** the picture gets a chromatic colour split at the edges, a lime and teal tint, ghost trails behind moving
  things, an edge glow and a pulsing vignette; it fades in over about 0.15 s and out over 0.4 s; your hotbar and hand stay
  sharp; the Minecraft side adds the afterimages and the "SANDEVISTAN" label. Kerenzikov (draw a bow while falling) shows
  half of it. Open a Cyberpunk menu in the middle: the effect is off meanwhile.
- **Slider:** in the ReShade overlay set **SandevistanStrength** to **0**: no screen effect (the Minecraft flourish stays);
  back to 1: it returns.
- **Log:** `screenfx: sandevistan <S> s`, `cyberfx: sandevistan <S> s`.

### F11. Release install, the launcher and the Play button
On a **clean copy** (a copy of the Cyberpunk folder with the frameworks, or a spare Windows account) with no earlier
Cyberblock:
1. `uv run python -m tools.package` and unzip `dist/Cyberblock-1.0.0.zip` somewhere else. Close Cyberpunk, run **install.bat**.
   **Expect:** it finds the game (or accepts a dropped folder), warns if RED4ext, redscript, Codeware or ReShade is missing, backs
   up the saves, refuses to overwrite a foreign file, keeps an existing `ReShade.fxh` and `ReShadePreset.ini`.
2. Create the launcher installation as README and HOW-TO-PLAY say (Fabric 26.3, game directory `%APPDATA%\.minecraft-cyberblock`,
   the JVM arguments **replaced** by `-Xmx2G -XX:+UseZGC --enable-native-access=ALL-UNNAMED -Dcyberblock.autostart=true`, the jar and
   Fabric API in `mods`). Start it from the launcher: the title screen has **Play Cyberblock** and the world opens by itself.
3. Start Cyberpunk, load a save, **F9**. Play for a minute; quit.
4. **uninstall.bat**: the game folder is as before (a file you edited is left with a message).
- **Log:** `Cyberblock 1.0.0 loading`, `Minecraft says hello: mod cyberblock 1.0.0, Minecraft 26.3`, no `version mismatch`;
  Minecraft `launcher: opening cyberblock (autostart)` or `(button)`.
- Also open the advancements screen (press the key you bound to Advancements in Controls, see F8 step 2): the Cyberblock tab
  lists six advancements.

### F12. No Cyberpunk HUD, ever (the full walk)
Cyberblock Active, survival. Do each of these and look at the whole screen the whole time. **No Cyberpunk interface element
may appear at any moment**: not the health bar, minimap, quest tracker, ammo, "F" prompts, dialogue wheel or subtitles, not a
notification, not the loading or waiting sign.
1. Walk up to a **door** (the prompt is Minecraft's tooltip box), an **item** to pick up, an **NPC** to talk to, a **vending
   machine**, and get into a **car** (and out).
2. Start a **dialogue** (choices are Minecraft's list, picked with E/Q/1-4/F), and a timed one if you find it.
3. Open a **vendor** (shop), a stash or the ripperdoc if you can reach one: the menu is Cyberpunk's but in Minecraft colours with
   a Minecraft title bar and frame, and the Minecraft world is not drawn over it. Its font stays Cyberpunk's (known limit).
4. Press **Esc**: the Minecraft "Night City (paused)" screen; Night City is frozen behind it. Press Esc to go back.
5. **M** (Atlas), **J** (Journal), **I** (inventory), **P** (skill tree), **H** (pause screen): all are Minecraft screens.
6. Click **Night City Menu...** in the pause screen: the real Cyberpunk pause menu opens once (the escape hatch); close it.
7. **Save** from the pause screen (Minecraft toast "Saving Night City..." then "Saved"), then **Load Last Save** and watch the
   loading cover ("Loading Night City...").
8. Die (`/kill` in chat in survival): the Minecraft death screen, no Cyberpunk game over.
9. A tutorial or shard popup (if one shows) is framed in Minecraft style and works.
10. **F9** off: Cyberpunk's own HUD is back at once; **F9** on: it vanishes.
- **Log:** `ui: ink layers <names>`, `ui: hidden (on)`, `ui: menu <name> -> minecraft <screen>`, `ui: pass-through <menu>`,
  `compositor: menu pass-through on|off`, `ui: paused by Minecraft` / `ui: resumed`, `ui: escape hatch <menu>`, `ui: restored
  (<reason>)` on F9 off. **A `ui: shown for <reason>` line is a report-worthy exception** (only a foreign pause popup or a
  braindance may cause it).
- **Report** every element that showed, with what you were doing and the `ui:` lines around that time.

### F13. Prompts and choices
1. Walk to several things: a door ("[F] Open"), a corpse or loot ("[F] Pick up", "[F] Loot"), an NPC ("[F] Talk"), a car ("[F]
   Enter"). **Expect:** a Minecraft tooltip box right of the crosshair with the key in yellow; several prompts on one thing
   form a list (E/Q or the wheel scroll, 1-4 pick, **F** confirms); a hold action shows a fill bar.
2. A **dialogue**: the list sits at the bottom centre above the subtitles; the selected line starts with `>`; unavailable lines
   are grey; quest lines gold; a timed line shows a timer bar that runs down. Picking works with E/Q/1-4/wheel/F as in
   Cyberpunk. Subtitles above the hotbar show the speaker in aqua.
- **Log:** `ui: notify ...` for the toasts; no `ui: shown for ...` lines.

If a check fails, tell Claude "F<n>: what you saw" and it reads the logs and captures the window to diagnose. For anything
visual, a screenshot helps most.

## Re-test 2 (still valid; do it after Re-test 3)

The second session (when you ran the first re-test below, `out/user-issues.md` section "Re-test 2026-10-03 ~15:05-15:20") found six things.
They are fixed or added on this build; **R1 to R6 below are those six, in the user's order**. (The R1-R6 of the older
re-test further down are a different list: call them "old R1" and so on.)

| Item | What was wrong, and what changed |
|---|---|
| **R1** elytra "works but teleports, the camera updates every few seconds" | V was moved by a script loop that only ran about once per second. The DLL now moves V **every rendered frame** from its own update hook, plays Minecraft's samples out a few ms late, and lands V on the real floor. |
| **R2** hacking says "target lost", scans find nothing | The scripts sent a garbage entity id to Minecraft (2668059858640 instead of 10019101), so every hack came back with an id that does not exist. Ids are fixed, and the scan pick now uses the proxies' real positions with some leeway. |
| **R3** infinite skill points | Perks are free in creative, and `/cyberblock perks free on` makes them free in survival. |
| **R4** blocks float a block above the street | Ground barriers were rounded up. Each world now has a vertical offset chosen at the first activation, applied in both directions, plus nearest rounding. `/cyberblock level` re-aligns. |
| **R5** a wind charge cannot launch you | Steve is now launched with Minecraft physics and V follows like a short flight, then a glide if you open the elytra. |
| **R6** NEW cyberware | A Cyberware screen on **U**, implants in ripperdoc slots, **Y** to fire them, HUD cooldown bars, all 17 implants with Minecraft VFX and, where it makes sense, the real Cyberpunk effect. |

**Before testing:** close Cyberpunk, run `uv run python -m tools.install`, and **restart Minecraft**
(`uv run python -m tools.play`): a Minecraft that was already running still has the old mod. Load a save on a flat
street, press **F9**, and stand still until the chat says Cyberblock is on. That first activation aligns the world's ground by
itself (R4). If V was not standing on the street then, or blocks still float, run **`/cyberblock level`** once while V
stands on the street. Everything is described in
`docs/HOW-TO-PLAY.md` (key table, Cyberware chapter).

**Logs to grep** (`<Cyberpunk 2077>\red4ext\logs\Cyberblock.log` for the DLL and scripts, `mc\run\logs\latest.log` for
Minecraft). The lines the checks below look for:

| Where | Line |
|---|---|
| DLL | `drive: ready (Teleport <param types>, raycast <param types>; ...)` once, on the first flight, or one `drive: disabled: <what> missing` |
| DLL | `flight: samples back`, and `ground: alignment k A -> B, Minecraft gets the ground again` after every align |
| DLL | `flight: start (glide\|launch)`, `flight: landed (glide\|launch) after X s`, `flight: over, V stays where it is` |
| DLL | `flight: N frames, M samples (median spacing a ms, max gap b ms), max step c m, clipped yes\|no` after every flight |
| DLL | `flight: stopped, V is held, in a vehicle or Cyberblock is off`, `flight: no samples, holding V`, `flight: no samples for 1 s, V put on the ground`, `flight: V stopped short of Night City geometry` |
| DLL | `ground alignment: Minecraft y = Cyberpunk z - %.4f (k %d/64)`, and `binds: hack G, skills K, cyberware U, activate Y` |
| Scripts | `flight: ignored, V may not be moved (<reason>)`, `flight: cleared in mid-flight, V put on the ground`, `dmg instant kill (landing) not reported: flight` |
| Scripts | `scan N: ok <class> <8-digit id> (requested\|look-at\|near crosshair)`, `hack <name> on <id>: ok` |
| Scripts | `cyberware: <id> on\|off`, `cyberfx: <id> <s> s`, `cyberfx: <id> slow-mo ended`, `berserk: Minecraft side only` |
| Minecraft | `launch started at {} with {} m/s`, `launch turned into a glide at {}`, `launch\|glide ended at {}` |
| Minecraft | `ground alignment: k {} -> {} (offset {} m) at Cyberpunk z {} ({})` (k -1 = never aligned), `block resync: {} cells` |
| Minecraft | `cyberware: installed {} in {}`, `cyberware: activate {}`, `glide called off (the host did not follow)` |

### R1. The elytra follows every frame (F-1 to F-3, L-7)
Cyberblock Active, an elytra on the chest and firework rockets (the survival kit has both), a megabuilding roof.
1. **Start-up line.** On the first flight Cyberblock.log has exactly one `drive: ready (Teleport handle:gameObject,
   Vector4, EulerAngles -> Void, raycast Vector4, Vector4, opt CName, out TraceResult (or physicsTraceResult), opt Bool,
   opt Bool -> Bool; ...)` and no `drive: disabled:` line. If it says `drive: disabled: <what> missing`, stop and send
   the line: the per-frame drive is off, `cam.fly` stays false and Minecraft calls every flight off within 0.5 s.
2. **F-1 smoothness.** Jump off the roof, press Space, glide for about 10 s with a few rockets (about 20 m/s).
   - **Expect:** V and the camera move **smoothly, every frame**: no stepping about once a second, no teleporting, no
     jitter of Night City, and the mouse still turns the view.
   - **Log:** `flight: start (glide)`; after the landing the summary line `flight: N frames, M samples (median spacing
     a ms, max gap b ms), max step c m, clipped no`: **a 17 ms or less, c under 1 m at 20 m/s**, and N about 60 x the
     seconds flown (the 15:04-15:21 log had about 1 per second). There is no `flight: ignored` line.
3. **F-2b turning while gliding.** Swing the mouse hard left, right, up and down during a glide.
   - **Expect:** the view follows the mouse at once, the glide steers with it, V's body keeps its own facing and the
     first-person camera turns smoothly. (Risk to watch: yaw "stickiness" or a snap back on fast mouse turns. Say so if
     you see it; the forward is already read just before every Teleport.)
4. **F-3 landing.** Glide from the roof down to the street.
   - **Expect:** V stands on the street within a frame of Steve's touchdown, never stopped in mid-air and never hanging
     in the fall animation on the ground; no death-landing effects, V's health unchanged.
   - **Log:** `flight: landed (glide) after X s` in the same frame as Minecraft's touchdown, **no** `dmg fall` within
     2 s, and no instant kill. If a landing attack still fires, these lines are fine: `dmg fall ... not reported: flight
     or teleport landing`, `dmg instant kill (landing) not reported: flight (Attacks.*Landing)`.
   - **Landing state:** V can walk or jump at once. If V stays frozen in the Fall pose on the ground for about 2 s and
     then plays a hard landing (or a `dmg fall` shows up about 2 s after landing), report it: the follow-up is a
     `ToRegularLand` wrap, `wrappedMethod(...) || SuppressFall && IsTouchingGround`.
   - A very short hop or a landing within 1.5 m of V logs `flight: over, V stays where it is` instead.
   - **Hand-off:** Steve takes V's position again as soon as V stands at the landing spot, with no visible 0.5 s
     freeze.
5. **L-7 menu mid-flight.** Glide (or launch), press **Esc** (or open the map) and wait **5 s**, then close it.
   - **Expect:** Steve waits in the air and the flight carries on; V never snaps to the ground meanwhile.
   - **Log:** possibly `flight: no samples, holding V` then `flight: samples back`; **no** `flight: no samples for 1 s,
     V put on the ground`; V either resumes or lands normally. Minecraft logs `glide paused at ... (Cyberpunk menu)` then
     `glide resumed`.
6. **Walls (clip).** Glide Steve into a Night City wall that Minecraft has no barrier for yet (fast, low, along a
   street).
   - **Expect:** V stops at the wall and ends on the ground on its side of it, never inside or through it.
   - **Log:** `flight: V stopped short of Night City geometry` once, and `clipped yes` in the summary line.
7. **Timeout.** Mid-glide, close Minecraft (or anything that freezes it for over a second).
   - **Log:** `flight: no samples, holding V`, then about 1 s later `flight: no samples for 1 s, V put on the ground`.
8. **Clear.** Press **F9** (or save) mid-glide.
   - **Expect:** V stands on the ground below, not in the air.
   - **Log:** `flight: cleared in mid-flight, V put on the ground`.
9. **No flight while V is held.** Sit in a car or a scene and try to start a glide.
   - **Log:** one `flight: ignored, V may not be moved (vehicle|scene|workspot|...)`; Minecraft calls Steve back within
     0.5 s (`glide called off ...`). A flight already under way when V gets held logs
     `flight: stopped, V is held, in a vehicle or Cyberblock is off`, and V is not teleported.

### R2. Scan and hack: real ids
1. **Scan id.** Hold **G** on a pedestrian 5 to 15 m away (the kit has a deck and chips).
   - **Expect:** the tooltip (name, faction, threat) and the glowing box on the right person.
   - **Log:** `events: scan scan N: ok NPCPuppet <8-digit id> (requested|look-at|near crosshair)`. The id has about
     **8 digits** (for example 10019101), **not** 13 digits (2668059858640) and not 0.
   - **Leeway:** aiming about 0.3 m beside a pedestrian, or 0.5 m beside a car, still picks it (the pick runs on the
     server from the proxies' real positions).
2. **Hack.** Keep G held, roll the wheel to **Bottle of Forgetting**, right-click.
   - **Expect:** a beacon sound, the upload starts, no "target lost/gone" message, RAM drops by 3.
   - **Log:** `hack memory_wipe on <same id>: ok` (it must not say `target gone`).
   - **Ids everywhere:** hit an NPC, then kill one. `events: hit target <id>`, `effects: push withheld from <id>` and
     `killed human <id>` use the same 8-digit id, never 0 and never a 13-digit one; `puppets: mobdmg ... by <id>` is a
     real id too.
3. **Target gone, for real.** Scan a person, kill them with your sword within the 5 s the lock lasts, then fire the
   chip.
   - **Expect:** "Hack failed: target gone" **only now**, with the RAM given back.
4. **Other targets:** an occupied car (the driver is hacked), a vending machine with the Redstone Lever (device
   path), and a person at about 60 m (only the look-at scan reaches there; with **Kiroshi Optics** installed the
   range is 80 m instead of 48).
   - **Log:** `scan N: ok ... (look-at)` or `(near crosshair)` for the far and device targets.

### R3. Free perks (infinite skill points)
1. Survival, `/xp set @s 0 levels`. Press **K** and click a tier-1 perk.
   - **Expect:** it refuses (not enough levels); costs are shown normally.
2. `/cyberblock perks free on`.
   - **Expect:** chat "Free perks on: the skill tree costs no levels in this world". **K** now shows "Perks are free" top
     right and greyed costs. Clicking a tier-1 perk says "Perk unlocked (free): <perk>" and your levels stay 0.
3. Click a tier-2 perk whose tier-1 you do not own, and a perk you already own.
   - **Expect:** the first stays locked, the second is not bought again (free skips the cost only).
4. `/cyberblock perks free` reports the state; `/cyberblock perks free off`.
   - **Expect:** "Free perks off: perks cost XP levels again", and perks cost levels again.
5. `/gamemode creative` with free perks off, press K and click a perk.
   - **Expect:** it is free, and `/cyberblock perks free` says "Free perks off, but perks stay free while you are in
     creative".
6. **Persistence.** Set it on, Save and Quit to the title, reopen the world (and once more after restarting Minecraft),
   then die and respawn.
   - **Expect:** `/cyberblock perks free` still says on each time.

### R4. Blocks sit on the street (ground alignment)
1. **First activation.** Stand still on a flat street, not in the air or a car, and press F9 (an existing world that was
   never aligned does this too).
   - **Log (Minecraft):** `ground alignment: k -1 -> <k> (offset <m> m) at Cyberpunk z <z> (first activation)` (-1:
     never aligned), then `block resync: N cells`.
   - **Log (DLL):** `ground alignment: Minecraft y = Cyberpunk z - 0.xxxx (k <k>/64)`, then
     `ground: alignment k 0 -> <k>, Minecraft gets the ground again`. The camera debug line's `lv` matches `k`.
2. **Place a block** (stone) on the street and look at it from a low angle.
   - **Expect:** it sits **on** the street: no gap, no block-high float, and the targeted barrier cube is no longer above
     the street. Stand on it: V's feet are one block above the street, not sunk and not floating. V's collision box
     sits flush on the street (V can step onto it, no 1 m gap).
3. **Another floor.** Go to a kerb, a sidewalk or a roof of a different height, place a block, note any offset (up to
   half a block is expected, nearest rounding). Stand there and run **`/cyberblock level`**.
   - **Expect:** chat "Ground aligned: offset <m> m (was <m> m). Blocks you placed elsewhere moved by less than 1 m.",
     and your blocks reappear after the resync (the earlier ones may have moved by less than 1 m).
   - **Log (Minecraft):** `ground alignment: k <old> -> <new> ... (/cyberblock level)`, then a block resync.
   - **Expect:** Steve's feet stay on V's floor, with no visible vertical jump of the picture during the re-align.
4. **Refusals.** `/cyberblock level` while jumping or in a car: "V must stand on the ground (not in the air, a vehicle
   or a scene)". With Cyberblock off: "Cyberpunk is not connected, or Cyberblock is off".
5. **Steve on the ground.** No `Player standing on air` spam in the Minecraft log, and an ender pearl landing on the
   street does not sink V or drop it (it is put on the Night City floor). A pearl onto a placed block: V stands on top
   of the block.

### R5. Wind-charge launches (L-1 to L-6; L-7 is under R1)
Wind charges are in the survival kit (8). Each launch should log `launch started at ... with ... m/s` (about 22 m/s
for your own charge at your feet) and later `launch ended at ...` in Minecraft, and `flight: start (launch)` then
`flight: landed (launch) after X s` in Cyberblock.log, with no Night City fall damage on V and no `dmg fall` reaching
Steve within 2 s.
1. **L-1 own charge, survival.** Look straight down and throw a wind charge at your own feet.
   - **Expect:** Steve and V are thrown up, V follows every frame (as in R1), you land on your feet and Cyberpunk
     movement takes over. People and cars near the burst are still knocked back.
2. **L-2 the same in creative.** `/gamemode creative` and repeat.
   - **Expect:** it launches too (creative flying is switched off for the launch). Toggle flying again afterwards.
3. **L-3 someone else's or a breeze's charge.** Burst one at your feet from outside (`/summon wind_charge ~ ~1 ~` above
   you, or a Breeze from a spawn egg shooting you).
   - **Expect:** the same launch. A tiny nudge that barely lifts you does not count.
4. **L-4 mace wind burst.** `/give @s mace` with the Wind Burst enchantment, drop onto a mob or a person from a height
   and smash.
   - **Expect:** the burst launches Steve; V follows.
5. **L-5 launch, then glide.** With the elytra on, launch yourself, and at the top (falling faster than 1 m/s with 3
   blocks clear below) press **Space**.
   - **Expect:** the elytra opens mid-air and you keep flying with no pause, no second `launch started`, and no V
     teleport; V follows the whole way.
   - **Log (Minecraft):** `launch turned into a glide at ...`, then `glide ended at ...`.
6. **L-6 no launch while V is held.** Sit on a bench or in a workspot (or drive), then throw a charge at your feet.
   - **Expect:** no launch: no `launch started` line, Steve stays with V, and nothing drops V.
   - If the DLL drive is disabled (R1 step 1), every launch shows `launch called off (the host did not follow)` within
     0.5 s and Steve snaps back to V.

### R6. Cyberware (screen on U, fire on Y, HUD bars)
**Set-up.** Survival with `/cyberblock kit` (installs the Cyberdeck, Kiroshi Optics, Reinforced Tendons and Biomonitor;
Sandevistan, Optical Camo and the Projectile Launch System land in the main inventory), or `/gamemode creative` where
the screen lists every implant and installs are free. Press **U** with no screen open.
1. **The screen.**
   - **Expect:** a Cyberware screen with the 8 slots on the left (Arms 1, Legs 1, Operating System 1, Nervous System 1,
     Integumentary 2, Eyes 1, Circulatory 2, Frontal Cortex 1) and the available implants on the right, 24 per page
     (all of them in creative; "Nothing to install: craft cyberware or use /cyberblock kit" when empty). U again or Esc
     closes it.
   - **Install and remove:** left-click on the right installs ("Installed <name>"), left-click an installed one removes
     it ("Removed <name>"). A full slot replaces: "Installed <new> (returned <old>)". In survival the item is used up and
     comes back on removal. Right-click on an active implant selects it (a yellow Y marker).
   - **Log (Minecraft):** `cyberware: installed <id> in <slot>`; **(scripts)** `cyberware: <id> on`.
2. **Y and the HUD.** Install Sandevistan and Optical Camo (the Operating System and Integumentary slots).
   - **Expect:** one icon per active or automatic implant right of the hotbar, each with a thin bar. **Tap Y** fires the
     selected one (log `cyberware: activate <id>`); **hold Y for 0.4 s** switches ("Y fires <implant>"). A recharging
     one says "<implant> is cooling down (n s)" and its bar fills grey to cyan; it is magenta while the effect runs. With
     no active implant Y says "No active implant installed". Cyberpunk's own Y (scene fast-forward, camera reset) must do
     nothing while Active.
3. **The 17 implants.** Install each one, use it, and check Minecraft, Night City and the log. (Arm implants only work
   with an **empty main hand**.)

| # | Implant | Do | Minecraft | Night City (V) | Log |
|---|---|---|---|---|---|
| 1 | Mantis Blades | empty hand, hit a person | +6 damage, red dust, sweep, strong-hit sound; none with an item in hand | person takes melee damage, ragdolls | `cyberware: mantis_blades on` |
| 2 | Gorilla Arms | empty hand, hit a person and a car | +4 damage, +2 knockback, gust and smash sounds; one shove even with the Gorilla Arms perk | person and car shoved far, once (the hit impulse is twice a bare punch; the perk alone gives the same, both together still one shove) | `cyberware: gorilla_arms on` |
| 3 | Monowire | empty hand, hit at about 6 blocks | +3 damage, +3 reach, a line of electric sparks | hit lands | `cyberware: monowire on` |
| 4 | Projectile Launch System | **Y** at a far target; at a wall under 4 blocks; again at once | smoke and flames trail, crossbow and fire-charge sounds, 8 s HUD bar; "Too close to fire" (no cooldown); "is cooling down" | a real explosion (power 2.5): a car blows up, NCPD comes | `cyberware: activate projectile_launch_system` |
| 5 | Reinforced Tendons | jump, then **Space** again in the air (and a third time, with the elytra on and high enough) | ring of cloud and a gust | V **double-jumps** (the 1st air press is V's double jump, the 2nd opens the elytra). CET `Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.HasDoubleJump)` is 1; **0 without the implant, even with the Double Jump perk** | `cyberware: reinforced_tendons on` |
| 6 | Fortified Ankles | hold Space about half a second, release | dust plume, gust, breeze-charge sound | V's charge jump (Space held over 0.15 s and released); a double jump does **not** happen; `HasChargeJump` is 1 with it, 0 without | `cyberware: fortified_ankles on` |
| 7 | Lynx Paws | walk past a sculk sensor and a guard; with and without it | no step vibration (the sculk sensor stays quiet), mobs notice you from half as far | CET `Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.Visibility)` is half its value without it; with the Silent Steps perk too it is still half, not a quarter | `cyberware: lynx_paws on` |
| 8 | Sandevistan | **Y**; then the perk | Speed II, the world at half speed (`/tick query` shows rate 10), cyan and magenta afterimages, beacon sounds, 6 s then the off sound, 25 s cooldown | NPCs and cars slow to x0.2, V does not; F9 off mid-effect ends it at once. With the Sandevistan Sprint perk: 8 s and 18 s, and a double-tap of Shift fires it (only with the implant). A Kerenzikov during a running Sandevistan switches the factor to x0.3 | `cyberware: activate sandevistan`, `cyberfx: sandevistan 6 s`, about 6 s later `cyberfx: sandevistan slow-mo ended` |
| 9 | Berserk | **Y** | Strength I, Resistance I, Jump Boost I for 8 s, dust ring, angry burst, smash and anchor sounds, mobs within 3 blocks knocked back, 40 s cooldown | V jumps higher; Cyberpunk's own Berserk status is not used, so V gets **no** superhero landing or ground slam on falls | `cyberfx: berserk 8 s`, `berserk: Minecraft side only` |
| 10 | Cyberdeck | survival, fresh kit: install the kit's Sandevistan (right-click it, or U then click); fire a chip and use a RAM Shard; U and click the returned Cyberdeck; empty the slot with the deck in the bag | "Installed Sandevistan (returned Cyberdeck)"; then RAM is 0 and the chip and the shard say "Your Operating System slot holds Sandevistan: swap in the cyberdeck with U to hack" (not "...or carry one"); swapping back says "Installed Cyberdeck (returned Sandevistan)", RAM max 8 again and chips fire; with the slot empty and the deck in the bag RAM is 8 again. Rebind Cyberware (for example to O) and the message names the new key | a quickhack works while the deck counts | `cyberware: installed sandevistan in operating_system/0`, `cyberware: cyberdeck on\|off` |
| 11 | Kerenzikov | jump, then draw a bow (or a crossbow or trident) in the air; on the ground | the world at half speed for 2 s, a ring of sparks, a rising chime, 10 s cooldown; **nothing on the ground** | Night City slows to x0.3 for 2 s | `cyberfx: kerenzikov 2 s` |
| 12 | Optical Camo | **Y**; attack; fire the Launch System; with the perk crouch still 2 s | Invisibility 10 s (the icon shows, no particles), a portal shimmer; it breaks on your attack or a Launch System shot; 30 s cooldown | hostiles lose V; CET `StatusEffectSystem.ObjectHasStatusEffect(Game.GetPlayer(), TweakDBID.new("BaseStatusEffect.OpticalCamoPlayerBuffRare"))` is true while it runs. The perk re-cloaks only with the implant | `cyberware: activate optical_camo` |
| 13 | Subdermal Armor | install, then take the same hit with and without | +6 armor, +2 toughness, a netherite equip sound | Night City hits do less to Steve | `cyberware: subdermal_armor on` |
| 14 | Kiroshi Optics | **G** on a person at about 60 m near the crosshair, with and without it; spawn a zombie and look around | the scan reaches 80 m (48 without); hostile proxies (gangers) and hostile mobs within 32 blocks get a red outline, passers-by do not | the scan range | with it `scan N: ok ... (look-at)`; without it `scan N: nothing under the crosshair` |
| 15 | Biomonitor | `/damage @s 14` from full health (20 max) | at 30 % or less: healed 8, Regeneration I 6 s, hearts, a chime, "Biomonitor: emergency heal"; again within 90 s does nothing | - | `cyberware: biomonitor on` |
| 16 | Second Heart | `/damage @s 100` (not `/kill`); again at once; save, quit, reopen | instead of dying: full health, Regeneration II 5 s, Absorption I 30 s, the totem animation, "Second Heart restarted you"; the second time within 5 minutes you die; the cooldown survives the reload; `/kill @s` still kills | - | `cyberware: second_heart on` |
| 17 | RAM Upgrade | install with the deck | RAM max 12 (16 with the Extra RAM perk too) | - | `cyberware: ram_upgrade on` |

4. **The double jump only with tendons.** Buy the Double Jump perk (free perks make it quick), then take the legs
   implant out: **no** double jump. Install Reinforced Tendons: double jump. The perk adds 4 blocks of safe fall to any
   legs implant, and nothing else.
5. **Migration.** On a world where Steve already owns the Double Jump, Sandevistan Sprint or Optical Camo perk, join
   once.
   - **Expect:** "Your perks now need cyberware: you received <items>. Install them with U", the items in the inventory
     and **not** installed, and no second message on the next join.
6. **Persistence.** Save and Quit, reopen; restart Cyberpunk while Minecraft stays up.
   - **Expect:** the implants are still installed and the HUD is back; the DLL gets the set again after each host hello
     (`cyberware: <id> on` lines).
7. **Four-key rebinding** (Minecraft Controls, category Cyberblock):
   - Cyberware to **O**, Activate to **L**: they work, and U and Y go back to Cyberpunk.
   - Cyberware and Hack both to G: **all four** go back to G K U Y, with a chat note;
     `binds: hack G, skills K, cyberware U, activate Y`.
   - Activate to **B** (refused): Activate falls back to Y and the others stay.
   - After each link Cyberblock.log has `binds: hack G, skills K, cyberware U, activate Y` (or the keys in force):
     Cyberware to L gives `binds: hack G, skills K, cyberware L, activate Y`; a clash gives a single warn and all four
     back to G/K/U/Y.
8. **Items and art.**
   - **Creative tab Cyberblock:** every implant shows its own icon (no purple-black missing texture) and its name.
   - **Recipes:** craft one or two from the HOW-TO-PLAY Cyberware chapter (redstone + copper ingot + clock + feather gives
     Kerenzikov; redstone + copper ingot + 2 RAM Shards + gold ingot gives RAM Upgrade). The Minecraft log has no recipe
     parse errors.
   - **The U screen background** draws unstretched: the body silhouette behind the slot boxes on the left, the implant
     list on the right. The slot boxes are placed by guess: say if a box sits badly on the art (the last row reaches
     slightly into the bottom strip).
   - **Tooltips:** hover an implant: Slot, the Minecraft and Night City lines, Cooldown and Install read as plain text,
     with no stray `%` placeholder. In the skill tree (K), Double Jump, Sandevistan Sprint and Optical Camo say they
     upgrade the implants.

---

## Re-test after the 2026-10-03 fixes (the first re-test; still valid)

The first session found the elytra flying Steve off alone, an empty NPC list, key clashes and netrunning gaps
(`out/user-issues.md`). Five fix packages are merged on main. Before testing: close Cyberpunk, make sure the install
ran (`uv run python -m tools.install`), and **restart Minecraft** (`uv run python -m tools.play`): a Minecraft that was
already running still has the old mod. The full key table and the netrunning walkthrough are in `docs/HOW-TO-PLAY.md`.

**What changed**
- **Keys (A, E):** F9 is Cyberblock's in every state (no QuickLoad); Mouse 4/5 and LCtrl stay Cyberpunk's; a dialogue
  with 2+ choices keeps E, Q, 1-4 and the wheel; Minecraft screens get the whole keyboard and real typing; Shift/Ctrl
  reach Minecraft (Shift-click, Ctrl+Q); X swaps to the offhand; either Shift sprints; Hack/Skill tree binds are limited
  to G K L O U Y 0 F1-F4 F6-F8 F10-F12 and fall back to G/K; Minecraft's Quick Actions no longer shares G.
- **Elytra and flight (A, B):** a glide needs 3 blocks of air and a Space while falling (2nd airborne press with Double
  Jump); cam now says fly/menu/air/held; a glide Cyberpunk does not follow is called off within 0.5 s and Steve returns;
  menus pause the glide; V stops at unmapped walls; no landing teleport for short glides; held V refuses pearls
  (refunded, no damage); Steve stands on V's ground with no "standing on air" spam or own fall damage.
- **Scenes, health, vehicles (C):** only scene tiers 2-5 hold V (free roam no longer counts); V is invincible in
  vehicles in every mode, hits go to Steve; instant kills go to Steve; falls arrive without a direction; the HUD shows
  F prompts and dialogue choices; explosions only reveal V when close; infinite potions survive saves.
- **NPCs and proxies (C, D):** one NPC registry; death judged by the puppet's own flag; the 5 s logs split people and
  cars and list why NPCs are left out; pushes say why they were withheld; lead tugs ragdoll people without killing
  them; collision boxes take their filters from the engine; proxies extrapolate (cars are 3 m wide), stay through
  menus and saves, and Minecraft logs how many exist; mobs prefer hostile NPCs; kill XP drops at Steve; an explosion
  hurts an NPC once.
- **Netrunning (E):** car hacks go to the driver; person hacks no longer need a proxy or get refused in free roam;
  refusals name their gate; scans fall back to the target nearest the crosshair; the lock and its tooltip last 5 s; a
  tap scans; hold G + wheel picks a chip, right-click fires it; chips show cost, recharge and usage; the kit fills RAM.

**R1. Keys (package A)**
1. F9 while Active, while Off and in the pause menu: it toggles Cyberblock and never quickloads.
2. Mouse 4 and 5 (dodge and scanner bindings) work in Cyberpunk while Active.
3. Talk to an NPC with 2 or more choices while Active: E, Q, 1-4 and the wheel scroll and pick in Cyberpunk, while the
   mouse buttons, 5-9, G and T still go to Minecraft. With a single-choice prompt, E still opens Steve's inventory.
4. Open Minecraft chat (T): letters, spaces, accented letters and emoji, plus Enter, Backspace, arrows, Home/End and
   Delete, all work. Cyberblock.log shows 'typing reaches Minecraft's screens' once. W A S D do not move V while a
   screen is open.
5. Close the inventory with E and immediately hold W: if W lands before Minecraft reports the screen closed (about
   50-100 ms), V does not walk until W is pressed again (the KeyGate first-down rule, by design). Does it feel OK?
6. Shift-click in a chest moves the whole stack; Ctrl+Q drops the whole stack; X swaps to the offhand; Right Shift
   also sprints, and a double-tap of either Shift triggers Sandevistan.
7. Rebind Hack to L and Skills to O in Minecraft Controls: they work, and G/K go back to Cyberpunk. Bind both to the
   same key, or one to B: both fall back to G/K and Cyberblock.log logs it once.
8. Open the pause menu, inventory or map while Active: cam.menu becomes true at once and forwarding stops.
9. Elytra end to end: cam.fly is true while V follows the glide and false after landing. A Minecraft hitch longer than
   0.5 s sets Cyberblock_FlightTimedOut but keeps cam.fly. cam.air is true while V jumps or falls, cam.held in a
   vehicle or a scene.
10. Camera-only flight fallback (`CyberblockFlight.SetCameraOnly(true)`): collision boxes appear around the flight
    position, not at V's parked spot.
11. Apply an infinite Minecraft effect (`/effect give @s speed infinite`), then save (quick or auto): the effect is
    back on V after the save. Leaving Active still forgets it.

**R2. Elytra, pearls, Steve on the ground (package B; `docs/notes/flight-spike.md`)**
1. F-1 to F-3: per-frame follow is smooth, the look is kept, and a rooftop-to-street landing puts V on the street. If
   death landings (or their effects) appear, report it (the fix is a LocomotionAirDecisions.ToDeathLand wrap).
2. F-8: start a glide while V is held (sitting, workspot). Expect 'flight: ignored ... (<reason>)' in Cyberblock.log,
   'glide called off (the host did not follow)' in the Minecraft log within 0.5 s, and Steve back with V.
3. F-9: Space at the end of a normal jump never opens the elytra; jumping off a ledge with 3+ blocks of air does. With
   the Double Jump perk, the 1st airborne Space double-jumps V and the 2nd opens the elytra.
4. F-10: open the map mid-glide for about 5 s. Expect 'glide paused' then 'glide resumed' in Minecraft, possibly
   'flight: no samples, holding V' then 'samples back' in Cyberpunk, and the glide continues.
5. F-11: glide into a building where nothing has been probed. V stops at the wall ('flight: V stopped short of Night
   City geometry' once) and Steve returns to V after landing.
6. F-12: throw a pearl while V sits or drives: overlay 'Pearl blocked: V cannot be moved right now', the pearl
   refunded, no 5 HP lost and no teleport.
7. health-1: no 'Player standing on air' spam in the Minecraft log, normal mining speed on the ground, no crits on the
   ground, and fall damage applied once (Cyberpunk's 'fall' only).
8. Camera-only mode: the probes and collision follow the flight (CyberblockFlight.Center).

**R3. Scenes, NPCs, health, HUD (package C)**
1. Elytra and pearl in free open-world play: no 'held' log, the glide follows. In a tier-2 quest scene: 'effects:
   teleport ignored, V is held (scene, scene tier 2)'. While swimming (tier 6), glides are allowed.
2. NPC list: every 5 s 'npcs: sent H people + C cars; attached H2 people + C2 cars; left out: ...'. Expect H > 0 near
   pedestrians. If it is still 0, the reasons listed (dead, defeated, mounted, puppet, detached, not_npc, over_cap)
   give the root cause.
3. Push withheld logs: hitting or leashing an ordinary pedestrian pushes them. If civilians show '(friendly)', that
   attitude is why pushes did nothing before. Quest NPCs in workspots show '(workspot)'.
4. Lead on a person: the tug ragdolls them at 4-8 m/s every 1.5 s, only once more than 0.5 m past the lead length,
   and the NPC survives. Vehicles are tugged as before.
5. A wind charge bursting at an NPC's feet or head pushes them; the log shows 'tether: knockback ..., N pushed' with
   N > 0.
6. Collision: the first box logs 'collision: World Static filters from the engine: simulation 376836/5070, query
   0/<mask with 7 bits>'. Then `docs/notes/collision-spike.md` checks 2-4: V is stopped by a box and can stand on it, a
   pedestrian is stopped by a 5-box wall, a traffic car and V's own car collide with it.
7. An explosion more than 30 m from V does not draw NPCs to V. One within 10 m reveals V; one at 10-30 m alerts NPCs
   without revealing V's position.
8. 'events: hit target <hash> amount <frac> by mob <id>', 'events: lead ...' and 'events: pull ...' appear at most once
   per second per kind.
9. While Active: F prompts (doors, talk, exit car) and dialogue choice lists are visible, the HUD hides again once
   they are gone, and the transition notification still shows for the first 2 s.
10. Vehicle in survival: 'vehicle: V invulnerable in the vehicle, hits go to Steve'. Crashes and gunfire on V's car
    give 'dmg vehicle/bullet ...' and V's health is unchanged. Getting out logs 'vehicle: V's own god mode removed'.
    In a save made in a car and then loaded, V has no leftover god mode.
11. G-9 kill test: in the Vehicle state, let gangers shoot V's car until Attacks.DriverKill fires (or another
    hitFlag.Kill hit lands on V). V lives, 'dmg instant kill reported as other x4 ...' is logged, and Steve dies. Also
    note whether BaseStatusEffect.ForceKill goes through ProcessInstantKill.
12. Fall damage arrives in Minecraft without a direction (no sideways knockback or tilt); the log reads 'dmg fall ...
    from unknown'.

**R4. Proxies, mobs, world (package D)**
1. With Minecraft on, open a Cyberpunk menu or save for longer than 2 s: leads, hooks and proxies are still there.
2. Cyberblock.log / Minecraft latest.log: the new 'proxies: H people, C cars (spawned n, removed m)' lines are the
   first direct evidence of whether Minecraft gets proxies at all. 0 people in Minecraft while the DLL says '0 sent of
   62' means the problem is on the DLL/script side.
3. A Cyberpunk explosion (own TNT, creeper, grenade) hurts an NPC once, not twice.
4. Drive or watch a fast car and swing or shoot at it: the proxy stays on the car (extrapolation up to 0.5 s; jumps
   over 40 m/s count as teleports, so cars above about 144 km/h do not extrapolate) and the 3 m box is easy to hit.
5. Mobs near real NPCs: zombies prefer hostile NPCs (gang members) over passers-by, never run after an NPC more than
   22 m from Steve, and mobs killed by NPC fire name the mob.
6. Kill an NPC far away with a gun: the XP appears at Steve.
7. Wind charge near a crowd: the knock reaches about 2.4 m, and the Demolitions perk does not widen it.
8. Flint and steel on a car does nothing and is not used up; on a person it ignites; an NPC standing in lava gets one
   ignite per 15 s.

**R5. Netrunning (package E; walkthrough in `docs/HOW-TO-PLAY.md`)**
1. Hold G on a pedestrian, then right-click a chip (in hand, or picked with the wheel while holding G). The hack
   lands, Cyberblock.log shows 'hack <name> on <id>: ok', and the RAM drops.
2. Hack an occupied car: the driver gets the effect. An empty car shows 'Hack failed: no driver' and refunds the RAM.
3. A quest or friendly NPC gives 'Hack failed: not hackable (god mode|friendly|quest character|no quickhack
   actions)', and the log says the same.
4. Press G with nothing under the crosshair: the log says 'scan N: nothing under the crosshair'. Aim about 2-3
   degrees off an NPC: the crosshair fallback still scans it. Note whether the TSF_Quickhackable pass ever finds
   anything while Active (expected: only the second pass does).
5. Release G after a scan: the tooltip and outline stay dimmed with 'Locked 5..1 s', then disappear. Moving the
   crosshair off the target while G is held clears them.
6. A quick tap of G scans.
7. With G held, the wheel cycles 'Chip: <name> (<n> RAM)' and does not move the hotbar. Without G, the wheel scrolls
   the hotbar.
8. A recharging chip shows 'Still recharging'. The chip tooltip shows the cost (1 lower with Cheap Hacks) and the
   usage line.
9. New survival world: the kit gives full RAM (8/8).
10. Controls screen: Quick Actions is unbound and G shows no conflict.
11. Rebind Hack to B: a chat note says B cannot be forwarded and G is used. Bind Hack and Skill Tree to the same
    allowed key: a note says G and K are used. Both notes also appear after a host hello.
12. With package A: typing in chat while Cyberpunk has focus (char/skey), Shift-click quick-move in a chest, Ctrl+Q
    with no screen and in an inventory, Esc on the death screen (it must stay open), X swaps to the offhand.

**R6. Verifier fixes**
1. Stand at an object with two or more F prompts (a body: "Pick up" / "Loot", or a vending machine): the wheel scrolls
   Cyberpunk's list and E does not open Steve's inventory. Step away: E opens it again.
2. Build a 3-block bridge over a gap, stand on it and save (quick save from the pause menu, and wait for an autosave):
   V does not drop. Then load that save with Cyberblock off: no invisible box is left (walk through where it stood).
3. Hold G on an NPC, release, open the pause menu for 2 s, close it: the tooltip and the lock agree (a hack fires at
   the NPC only while 'Locked n s' still shows).

---

# Full pass (first session's plan, updated for the fixes)

## 0. Before launching
- Close Roblox Studio (and check Task Manager for leftover `RobloxStudioBeta` processes) — memory headroom.
- Cyberpunk: Frame Generation **Off**, Resolution Scaling Off for the first run.
- In the repository root: `uv run python -m tools.play` (starts Minecraft, waits for it, then starts Cyberpunk).
- Load a save outdoors in a busy street (NPCs, cars, NCPD nearby). Press **F9**.

## 1. Layer 1 acceptance (5 min)
1. Walk up stairs and under an overpass: Steve's hand/hotbar stay; placed blocks rest on Night City floors.
2. Look at a wall that has a Minecraft block behind it: **no block outline shows through the wall** (aim-limit fix).
3. Get in a car and out again: no Cyberpunk HUD in the car (Steve rides along, hand only, no hotbar); the hotbar returns on foot.
4. Close the Minecraft window mid-walk: within ~2 s V is restored with a notification. Restart Minecraft: it resumes.
5. Save while active, then load: the save loads normally.

## 2. Cyberpunk mechanics disabled while active (2 min)
6. Tab does not scan, X does not heal, no double jump / dodge / air dash, Alt opens no radial wheel, weapons cannot be
   drawn, phone (T) goes to Minecraft chat instead.
7. Esc opens Cyberpunk's pause menu; inside it, keys do not move/act Steve; after closing, Minecraft input works again.

## 3. Health link — "Steve's health is the only health" (5 min)
8. Let NCPD/gangers shoot V: Steve's hearts drop, V never dies.
9. Jump from ~10 m: fall damage on Steve's hearts. Let a car hit V: damage. Stand near a grenade/fire: damage.
10. Die in survival: Minecraft's death screen appears; click Respawn with the mouse; play continues.
11. `/gamemode creative` (press T or /, type it in Cyberpunk, Enter): nothing hurts Steve or V.

## 4. Layer 2 — blocks, explosions, weapons, fire (10 min)
12. `/cyberblock kit` (or in survival the kit arrives on first activation). Hotbar 1-9: iron sword, bow, stone, TNT,
    flint & steel, ender pearls, firework rockets, leads, fishing rod; shield in the offhand; elytra on the chest; the
    quickhack chips, arrows, glass, potions, spawn eggs, Sandevistan, Optical Camo and the Projectile Launch System in
    the main inventory (E); the Cyberdeck, Kiroshi Optics, Reinforced Tendons and Biomonitor already installed (U).
13. Build a 3-block-high wall across a sidewalk and a road: pedestrians and cars stop at it; V can stand on it.
14. Place TNT under a parked car and light it: real Cyberpunk explosion, car destroyed, NCPD heat.
15. Sword a pedestrian, shoot one with the bow: they take damage/ragdoll, heat appears.
16. Flint & steel (or a fire charge) on an NPC: they burn.

## 5. Layer 3 — mobs vs Night City (5 min)
17. Spawn eggs (zombie, skeleton, creeper) near pedestrians/gangers: mobs attack NPCs; gangers/NCPD shoot back and can
    kill mobs; a creeper's explosion damages Night City.

## 6. Layer 4 — elytra & ender pearls (5 min)
18. From a rooftop with an elytra equipped, jump off the edge (3+ blocks of air below) and press Space while falling:
    glide; fireworks boost; landing returns normal movement. Open the pause menu mid-air for 2 s, resume: the glide
    carries on.
19. Throw an ender pearl: V teleports where it lands; no fall damage spike.

## 7. Layer 5 — interactive items (5 min)
20. Lead on a pedestrian and on a car, walk away: they are dragged. Use the lead again to release.
21. Fishing rod: hook an NPC/car and reel in. Wind charge: knockback blast. Shield raised toward a shooter: blocked.
22. Potions: speed (V faster), jump boost (higher jump), invisibility (hostiles lose V).

## 8. Layer 6 — Cyberpunk in Minecraft form (10 min)
23. RAM bar on the Minecraft HUD; it regenerates.
24. Hold **G** on a ganger: glowing outline + tooltip (name, faction, threat). On a vending machine: device info.
    A person behind a wall more than 8 m away gets no tooltip (log `scan N: behind Night City at <id>`); a parked car in
    the open does get one.
25. Use each quickhack item on gangers: Redstone Charge (short circuit), Blaze Chip (overheat), Ink Sac Daemon
    (blind), Cobweb Daemon (cripple), Spider Eye Virus (contagion), Wither Skull Daemon (synapse burnout), Compass Ping,
    Bottle of Forgetting (memory wipe), Creeper Brain (cyberpsychosis). Redstone Lever on a vending machine, a camera,
    a traffic light.
26. **K** skill tree: `/cyberblock perks free on` (or `/xp add @s 30 levels`), buy Double Jump (with a legs implant:
    4 more blocks of safe fall; V's double jump itself needs Reinforced Tendons), Sandevistan Sprint (upgrades the
    Sandevistan implant; double-tap Shift fires it), Iron Skin (+4 hearts), Silent Steps, Chain Hack; check each does
    what its tooltip says. Buy Dodge
    (best on a save where V lacks Cyberpunk's own Dash/Air Dash): V dashes, and air dashes off a jump. While Active,
    open Cyberpunk's perk screen (Esc → Character): the Reflexes milestones show V's real levels.
27. Cyberware: the mantis blade and gorilla gauntlet items are superseded by the implants, see "Re-test 2" R6 above.

## 9. Safety pass (3 min)
28. F9 off/on 10 times; drive; save and load while active: V always comes back normal (HUD, weapons, no god mode,
    visible body, normal time speed). Hold a pedestrian on a lead and save from the pause menu, then wait for an
    autosave: the lead stays in Minecraft and the pedestrian is still dragged afterwards. Get in a car while leading
    one: Minecraft drops the lead and Cyberpunk stops dragging (log `tether: released <id>`).
29. Optional: `uv run python -m tools.install --remove` then reinstall — the game folder is left exactly as before.

---

## Appendix — every in-game check the build agents recorded

### Phase Q — needsInGame lists

The checks only the real game can answer, from the Phase Q plan; each package appends its own lines here when the integrator
merges it. The Q1-Q7 checks at the top cover them in the user's order.
- [ ] **QB (Q-B1, Q-B6):** survival pickaxe on a placed stone shows growing cracks and breaks it in vanilla time; `aim:` lines
  stay on the block. In creative the street outline block under the crosshair is back after about 10 s; in survival it cannot
  be broken; with permanent erase on it stays broken until V walks away and back.
- [ ] **QV (Q-V1 to Q-V5):** under `tools.fakehost --view` and in game, third-person Steve shows no jitter while walking,
  sprinting, gliding and launching; glide, launch and shield poses are steady; the overlay numbers are steady.
- [ ] **QD (Q-D1):** F5, walk along a wall with the camera on the wall side: it swings to the centre and never shows the
  inside of the wall; turning in a corridor never clips. Whether the vehicle camera distance is settable.
- [ ] **QS (Q-S1, Q-S2):** looking at a placed block in front of a wall, the `aim` distance is the wall's, not the block's;
  walking up a one-block staircase of placed blocks shows no vault or climb prompt, and a real ledge and a ladder still
  vault and climb.
- [ ] **QU (Q-U2):** the loading cover and the Night City pause screen's "Cyberblock..." button.


### Re-test 3 — needsInGame lists

Each package of the Re-test 3 build listed the checks that only the real game can answer; they are below, per package
(merged on main, version 1.0.0). The F1-F13 checks above cover them in the user's order; these lists add the exact log
lines and tuning hints. Where a package's check was overtaken by a later fix, the line says so.

#### Re-test 3 — package D (DLL)
- [ ] **F4 glide steering:** glide with the elytra, swing the mouse left and right mid-glide. The glide turns at once, and
  the Cyberblock.log line `flight: N frames ... yaw start A end B, mouse x N counts at R deg/count, pitch min..max` shows
  A != B. Also expect `look: R deg per mouse count` after some walking on foot (about 0.15 or your sensitivity).
- [ ] **F3 seat:** get into a car and switch the camera (first and third person). Expect `seat: on (first person)` /
  `seat: on (third person)` and `seat: off` when leaving; Minecraft keeps its HUD and hand (`hud hidden:false`); Steve
  sits at the hips point; `cam.fp` is false in the third-person car camera.
- [ ] **F2 third person:** on foot press F5 in Minecraft. Expect `persp: Minecraft asks for the third-person view`,
  `view: third person on (distance 3.20 m)` and `view: applied, eye moved D m (expected E m)`. D should be close to E; if
  not, the FPPCameraComponent parent frame is not V's and the offset axes need fixing in `view.cpp`. Walk toward a wall or
  a Minecraft block: the camera shortens and does not clip. F5 again gives `view: first person (Minecraft asks for first
  person)`. Opening a menu, a scene, a vehicle or F9 off each restore first person with a reason.
- [ ] **F1:** fire a quickhack. With S's `Natives.reds` declaring the 5-parameter `Cyberblock_HackResult` (merged), the
  Minecraft log shows `hackresult` with `upload`/`kind`, and `hackspread` messages arrive for chained, spread, ping and
  frenzy targets (at most 16 per id).
- [ ] **F8:** press Y, L, O in game: Minecraft gets `key activate/arms/camo` and Cyberpunk does not act on L/O. Log
  `binds: hack G, skills K, cyberware U, activate Y, arms L, camo O`. Rebind two actions to the same key and all six fall
  back.
- [ ] **F10:** activate the Sandevistan. Expect `screenfx: sandevistan S s` (S's `Cyberblock_SetSlowMoFx` call is
  merged) and the colour split, afterimages on moving things, lime/cyan depth edges, green-teal tint and a pulsing
  vignette, with the HUD and hand crisp. In the ReShade overlay, set "Sandevistan effect strength" to 0 and the effect is
  gone; opening a Cyberpunk menu during it also hides it.
- [ ] **F12:** open a passed-through Cyberpunk menu (vendor). Expect `compositor: menu pass-through on` and no Minecraft
  world drawn over the menu, then `off` on close.
- [ ] **F13:** during an incoming call, T answers in Cyberpunk (log `phone: T goes to Cyberpunk ...`, via U's
  `Cyberblock_SetPhonePrompt`, merged) instead of opening Minecraft chat.
- [ ] **F11:** the load line reads `Cyberblock 1.0.0 loading`. With M's `hello.ver`: `Minecraft says hello: mod
  cyberblock 1.0.0, Minecraft 26.3`; an older mod gives `version mismatch: DLL 1.0.0, mod unknown`.

#### Re-test 3 — package U (Cyberpunk UI bridge)
- [ ] While On, trigger a tutorial popup that waits for input (for example a new-mechanic tutorial). Expect `ui: shown
  for blocking popup (TutorialPopupGameController)` or `(UI_TutorialPopup)`, `ui: pass-through tutorial`, and the popup
  visible and grey-tinted inside a Minecraft frame. Closing it should log `ui: hidden (on)` and `ui: pass-through ended
  (tutorial)`.
- [ ] While On, pick up a shard and open a codex popup: expect `ui: shown for blocking popup (...)` and a usable popup.
- [ ] While On in a car, open the vehicle radio or vehicles manager popup: expect `ui: shown for blocking popup
  (VehicleRadioPopupGameController/VehiclesManagerPopupGameController)` and pass-through `other`.
- [ ] While On, open a vendor (pass-through vendor) and buy a stackable item: the quantity picker must be visible and
  usable, and the log must show `ui: shown for menu popups`. Also check sell-junk and the ripperdoc purchase confirmation.
- [ ] Atlas pin tracking (`ui: tracked pin <i>`, `ui: track pin <i> unresolved (not a quest pin)`): **superseded** — W
  removed the Atlas Track button, so quests are tracked from the Journal (J). Check instead that the Journal's Track button
  tracks the quest on the HUD (see F2 step 2).
- [ ] While On, quick save from the Minecraft pause screen and let an autosave happen: no `ui: restored (saving)` line,
  and no Cyberpunk HUD, notifications or waiting sign appear during the save. The "Saving Night City..." and "Saved"
  toasts appear in Minecraft.
- [ ] Check that a non-blocking tutorial hint (`closeAtInput` and `pauseGame` both false) stays hidden. This is by
  design; it is not forwarded to Minecraft.

#### Re-test 3 — package S (scripts)
- [ ] **F1** (the F1 table above): fire every chip at a ganger and at a pedestrian. Cyberblock.log should show `hack
  <name> on <id>: ok (squad|crowd|driver, upload <s>s, +<n> spread)` with a non-zero upload, and the quickhack queue bar
  should appear over the target.
- [ ] **F1 crowd extras** after the upload: Overheat should log `hackfx: overheat crowd ignite <id> 5 s` and the
  pedestrian burns; Reboot Optics logs `blind ... 5 s`; Cripple Movement logs `stun ... 4 s` and the pedestrian stops;
  Memory Wipe logs `dazed ... 2 s`; Short Circuit logs `electrocuted vfx` (check whether status_electrocuted shows on a
  crowd NPC).
- [ ] **F1 Cyberpsychosis** on a pedestrian next to others: lines `hackfx: cyberpsychosis crowd <src> strikes <victim>`
  every 1.5 s, at most 4, then `frenzy of <src> over`. Victims flinch.
- [ ] **F1 Synapse Burnout** should log `hackfx: synapse_burnout <kind> heavy hit on <id>` and a `knock: <id> s=3.5
  tier=impact` line, with no ragdoll.
- [ ] **F1 Ping** near a gang should log `hackfx: ping <id> revealed N hostile NPCs` with N > 0; hostiles are revealed for
  about 10 s.
- [ ] **F1 Contagion** with 2 or more pedestrians within 6 m should log `+2 spread`, and both nearby NPCs get the poison.
- [ ] **F7:** punch (2 m/s) should log `tier=impact` and the NPC flinches; sprint hit (4.5) `tier=stagger`; Knockback II or
  gauntlet (7) `tier=knockdown` with no ragdoll; gauntlet + Gorilla Arms (12) `tier=ragdoll` with `impulse=32` and a
  `moved X m/s over 0.3 s` line (tune `RagdollImpulsePerMs` from X). A lethal 10+ m/s hit should log `ragdoll (lethal:
  ...)`.
- [ ] **F7 Tether:** a lead tug and a rod pull should still move people with no lethal flinging; a wind charge pushes cars
  about half as far as before (`VehicleShare` 0.5). Re-check that cars still respond enough.
- [ ] **F3:** get in a car while Active. Expect `seat: slot Hips hips (x, y, z) tpp false` (or `seat: no Hips slot`).
  Toggling the vehicle camera should flip the DLL's `seat: on (third person|first person)` at once; leaving the car should
  log `seat: off`.
- [ ] **F2:** with D's view module, F5 on foot should be allowed only while Active, not in a vehicle, menu, braindance or
  photo mode. Block targeting in third person should match first person, because Aim measures from `ViewEye`.
- [ ] **F10:** activate the Sandevistan. Expect `cyberfx: sandevistan N s`, then the DLL's `screenfx: sandevistan N s`;
  the screen effect ends with the slow-mo.

#### Re-test 3 — package M (Minecraft core)
- [ ] **F3 first person:** get into a car in Cyberpunk with Cyberblock On. Expected: Minecraft's hand stays visible (no
  `hud hidden:true`), and Steve's body is not drawn.
- [ ] **F3 third person:** switch to a third-person vehicle camera. Expected: Steve sits on V's seat in the riding pose
  (legs forward, walk animation still), facing the car's way. Check `SEAT_DROP` = 0.70 against a real seat: if Steve
  floats or sinks, the hips offset (S/D) or `SEAT_DROP` needs tuning. Expected Cyberblock.log: `seat: on (third person)`.
- [ ] **F2:** press F5 on foot. Expected: the action bar shows "Third person", latest.log has `persp: back`, and
  Cyberblock.log has `view: third person on`. F5 again gives "First person" and `persp: first`. Exiting the
  game/reconnecting resends the mode after hello.
- [ ] **F7:** hit a pedestrian with a plain fist or sword. Expected: `hit.impulse` is about 2 m/s and Cyberpunk shows an
  Impact reaction (no fling). A sprint hit is about 4.5 (Stagger). Gauntlet + Gorilla Arms perk is about 12 (knockdown +
  ragdoll). An arrow without Punch is 2 m/s. Check the `knock:` lines from S.
- [ ] **F11 launcher:** install the jar in a launcher profile with the JVM args from plan 1.13. Expected: the title screen
  shows "Play Cyberblock" above Singleplayer, and with `-Dcyberblock.autostart=true` the Night City world opens by itself
  once. latest.log: `launcher: opening cyberblock (autostart)`.
- [ ] **F11 version:** with the 1.0.0 DLL, Cyberblock.log shows `Minecraft says hello: mod cyberblock 1.0.0, Minecraft
  26.3` and no version mismatch.
- [ ] **F4:** glide with the elytra and swing the mouse mid-glide. Expected: latest.log's `glide started ... yaw A` and
  `glide ended ... yaw B` differ, and match the yaw change in the DLL flight summary.

#### Re-test 3 — package C (Minecraft cyber)
- [ ] Hosted, with Mantis Blades installed and an empty main hand, aim at an NPC proxy 5 to 10 blocks away and press L.
  Expect: "Mantis lunge" in red, a red dust line from Steve to the NPC, a sweep and a strong-hit sound. latest.log should
  show `cyberware: fire mantis_blades (arms)` and then `cyberware: mantis lunge hit ...`, and Cyberblock.log a `hit` for
  that proxy. V does not move.
- [ ] Standalone Minecraft only: a lunge at a mob that moves out of reach, so it times out after 12 ticks, should leave L
  ready right away (no 6 s cooldown on the HUD).

#### Re-test 3 — package W (Minecraft UI)
- [ ] Quest-teleport or other long load (over 10 s): the "Loading Night City..." cover stays up for the whole load.
  Afterwards the job tracker, Locator Bar waypoint, radar pins and eddies come back without needing a reconnect or a
  quest change.
- [ ] F9 off (`state active:false`) or a new host session (`hello`): the HUD data and the waypoint clear right away.
- [ ] Atlas (M): selecting a pin shows no Track button. A fast-travel pin shows no fast-travel button (fast travel from
  the Atlas is off in 1.0.0, `CyberblockMenus.FastTravelEnabled`); instead the Atlas says "Fast travel: use the Night City
  map (Night City Menu...)".
- [ ] Phone on a mid-game save with more than 11 contacts: the mouse wheel over the contact column scrolls it, a scroll
  bar shows, threads with pending replies are at the top, and clicking a scrolled row opens the right thread.

#### Re-test 3 — package A (art, assets, lang, data)
- [ ] Open the perk screen (P) and the ripperdoc screen (U) (C's container restyle is merged): panels should look like
  vanilla container panels, with slot wells aligned under the nodes, cells and boxes.
- [ ] The RAM bar HUD renders as an aqua notched boss-style bar and the radar frame (80x80) shows the white/grey bevel
  with a yellow north tick over the map content.
- [ ] The phone icon in the toast and screen is grey with a green/grey screen and a red dot.

#### Re-test 3 — package P (protocol, docs and release)
- [ ] **F3:** in a car, the first-person camera shows only Steve's hand, with no hotbar, bars or crosshair. In third
  person the whole seated Steve is drawn, also with no hotbar.
- [ ] **F11/F8:** Esc, **Minecraft Options...**, Controls, bind Advancements to a free key. Close the menus and press it:
  the advancements screen opens with the Cyberblock tab (six advancements). If the key does nothing while Active, ask W to
  add an Advancements button instead.
- **Release zip on a clean machine (F11).** Unzip `dist/Cyberblock-1.0.0.zip`, run `install.bat` on a copy of the game
  folder: it must refuse while Cyberpunk runs, find the game through Steam (or accept a dropped folder with `-Game`), warn
  about missing frameworks, back up the saves once, never overwrite a foreign file, keep an existing `ReShade.fxh` and
  `ReShadePreset.ini`. The manifest it writes must be readable by `tools/install.py --remove`, and the manifest of
  `tools/install.py` by `uninstall.bat`. (The automated tests run `install.ps1` against a fake game folder; the real
  Steam lookup and the registry are only checked here.)
- **Launcher install (F11).** Follow HOW-TO-PLAY section 1 literally with a fresh launcher profile: the dedicated game
  directory, the JVM arguments **replaced**, the jar and Fabric API in `mods`. The title screen must show "Play
  Cyberblock" and the world must open (autostart). If any step of the written instructions is wrong for the real launcher,
  correct HOW-TO-PLAY and README.
- **Docs against the build.** Compare the key table, the chip table (HOW-TO-PLAY "What each chip does") and the UI tour with
  what you see; tell Claude every sentence that is wrong.
- **Wire.** Run `uv run python -m tools.fakehost --ui --hacks --hold 12` against the dev client (port 25600 is the real
  link: use another port with `--port` and a client started with `-Pcyberblock.port`): the HUD elements, the pause and
  pass-through screens, the upload beam and the spreads must show, and Minecraft's `uiact`, `persp`, `status.pause`, `binds`
  lines print in the terminal.


### Re-test 2 — package D (DLL)
- [ ] Drive signature: on the first flight Cyberblock.log shows exactly one `drive: ready (Teleport handle:gameObject,
  Vector4, EulerAngles -> Void, raycast Vector4, Vector4, opt CName, out TraceResult (or physicsTraceResult), opt Bool,
  opt Bool -> Bool; ...)` and no `drive: disabled:` line. If it says disabled, `cam.fly` stays false and Minecraft calls
  every flight off within 0.5 s.
- [ ] F-1 smoothness: glide at about 20 m/s for 5 s or more and land. The summary line `flight: N frames, M samples
  (median spacing a ms, max gap b ms), max step c m, clipped no` shows a <= 17 ms and c < 1 m, with N about 60 x the
  seconds (not about 1 per second as in the 15:04-15:21 log).
- [ ] F-2b: turn the mouse hard while gliding. V's body keeps its own facing and the FPP camera turns smoothly. If yaw
  sticks or snaps back, record it (the forward is already read just before every Teleport).
- [ ] F-3 landing: `flight: landed (glide) after X s` is logged in the same frame as Minecraft's touchdown, V stands on
  the street (not in mid-air or inside it), and there is no `dmg fall` and no instant-kill report.
- [ ] L-7 menu: open the Esc menu mid-glide for 5 s, then close it. There is no `flight: no samples for 1 s, V put on
  the ground`; V either resumes (`flight: samples back`) or lands normally.
- [ ] L-1 to L-6 (wind charge launch): `flight: start (launch)` then `flight: landed (launch) after X s`. L-6: while V
  sits in a vehicle or a scene there is no start; a start already under way gives `flight: stopped, V is held, in a
  vehicle or Cyberblock is off` and V is not teleported.
- [ ] Clip: glide Steve through a Night City wall that Minecraft has no barrier for. Expect `flight: V stopped short of
  Night City geometry` once, `clipped yes` in the summary, and V ending on the ground on its side of the wall.
- [ ] Timeout: freeze or close Minecraft mid-glide. Expect `flight: no samples, holding V` and, about 1 s later,
  `flight: no samples for 1 s, V put on the ground`.
- [ ] Ground alignment (R4): after Minecraft's first-activation align, Cyberblock.log shows `ground alignment: Minecraft
  y = Cyberpunk z - 0.xxxx (k NN/64)` followed by `ground: alignment k 0 -> NN, Minecraft gets the ground again`. A
  block placed on the street sits on it; the cam log (`lv`) matches.
- [ ] Binds: after each link, `binds: hack G, skills K, cyberware U, activate Y`. Rebind U to L in Minecraft and expect
  `binds: hack G, skills K, cyberware L, activate Y`. Rebind two actions to the same key and expect a single warn and
  all four back to G/K/U/Y.
- [ ] Cyberware events: install an implant in Minecraft. The script log shows `cyberware: <id> on` (S5) for exactly that
  id; Y on Sandevistan gives `cyberfx: sandevistan 6 s`.

### Re-test 2 — package S (scripts)
- [ ] R2 scan: aim at a pedestrian and press G. Cyberblock.log shows `events: scan scan N: ok NPCPuppet <8-digit id>
  (look-at|near crosshair)`. Then fire memory_wipe: the `hack memory_wipe on <same id>` line must not say `target gone`.
- [ ] R2 ids: hit an NPC, then kill one. The `effects: push withheld from <id>` line and `killed human <id>` use the same
  8-digit id as `events: hit target <id>`, never 0 and never a 13-digit heap-like number. `puppets: mobdmg ... by <id>`
  is a real id too.
- [ ] F-1 to F-3 (with package D): glide with the elytra. There is no `flight: ignored` line, and the DLL summary line
  appears. After landing, Cyberblock.log has no `dmg fall` and no `dmg instant kill` for the landing. If a landing attack
  still fires, expect `dmg fall ... not reported: flight or teleport landing` or `dmg instant kill (landing) not
  reported: flight (Attacks.*Landing)`.
- [ ] Landing state (follow-up trigger): after a fast glide or wind-charge landing, V can walk or jump at once. If V stays
  frozen in Fall on the ground for about 2 s and then plays a hard landing (or the log shows a `dmg fall` about 2 s after
  landing), add the ToRegularLand wrap: `wrappedMethod || SuppressFall && IsTouchingGround`.
- [ ] L-6: start a glide or launch while V sits in a vehicle or is in a scene. Expect one `flight: ignored, V may not be
  moved (vehicle|scene|workspot|...)` line, and Minecraft calls Steve back within 0.5 s.
- [ ] Clear: press F9 (or save) mid-glide. Expect `flight: cleared in mid-flight, V put on the ground`, and V standing on
  the ground below, not in the air.
- [ ] R4: after the alignment, place a block on the street. V's collision box sits flush on the street (V can step onto
  it, no 1 m gap). Throw an ender pearl onto the street: V lands on the floor without sinking or dropping. Throw one onto
  a placed block: V stands on top of it.
- [ ] S5 jumps: install reinforced_tendons (log `cyberware: reinforced_tendons on`): V double-jumps. Remove it: no double
  jump, even with the double_jump perk. Install fortified_ankles: holding Space more than 0.15 s and releasing gives a
  charge jump, and a double jump does not.
- [ ] S5 lynx_paws: with lynx_paws (or the silent_steps perk), the CET command
  `Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.Visibility)` returns half the
  value it gives without either.
- [ ] S5 Sandevistan: press Y. Expect `cyberfx: sandevistan 6 s`, the world slowed to x0.2 with V at normal speed, then
  `cyberfx: sandevistan slow-mo ended` about 6 s later. Kerenzikov (bow or crossbow while airborne) gives `cyberfx:
  kerenzikov 2 s` and the world at x0.3. A Kerenzikov during a running Sandevistan switches the factor to 0.3: this
  checks that SetTimeDilation with the same reason replaces the old one.
- [ ] S6: activate Berserk. Expect `cyberfx: berserk 8 s` and `berserk: Minecraft side only`. V gets no superhero landing
  or ground slam on falls.
- [ ] Kiroshi: an NPC about 60 m away near the crosshair scans ok only with kiroshi_optics installed; without it the log
  says `scan N: nothing under the crosshair`.

### Re-test 2 — package M (Minecraft world)
- [ ] R4-1 (new or pre-retest-2 world): on the first activation with V standing still on the street,
  `mc/run/logs/latest.log` shows `ground alignment: k -1 -> N (offset X m) at Cyberpunk z Z (first activation)`, then
  `block resync: ...`. Cyberblock.log shows D's `ground alignment: Minecraft y = Cyberpunk z - X (k N/64)`. A block Steve
  then places on the street sits flush on it, not a block above.
- [ ] R4-2: standing on the street, run `/cyberblock level`. Chat: "Ground aligned: offset X m (was Y m)...". The log
  shows `ground alignment: k A -> B ... (/cyberblock level)` then a block resync. While jumping or in a car: "V must
  stand on the ground...". With F9 off or no Cyberpunk: "Cyberpunk is not connected, or Cyberblock is off".
- [ ] R4-3: after `/cyberblock level`, Steve's feet stay on V's floor with no visible vertical jump of the picture. This
  checks HostState's `lv` correction during the align round trip.
- [ ] R5 L-1 (survival): throw a wind charge at your own feet. The log shows `launch started at ... with ~22 m/s`; Steve
  and V rise together, land, then `launch ended at ...`. No Night City fall damage on V and no `dmg fall` reaching
  Steve.
- [ ] R5 L-2 (creative): the same, with the same log lines. This checks ServerExplosionMixin and
  SimpleExplosionDamageCalculatorMixin.
- [ ] R5 L-3: a breeze's or another player's wind charge near Steve launches him (`launch started`). L-4: a mace hit with
  Wind Burst launches the attacker.
- [ ] R5 L-5: in a launch with an elytra, press Space while falling more than 3 blocks above ground. The log shows
  `launch turned into a glide at ...` then `glide ended at ...`, and V follows the whole way.
- [ ] R5 L-6: while V sits in a car or is held by a scene, a wind charge at Steve's feet logs no `launch started` and
  Steve stays with V. If the DLL drive is disabled, a launch shows `launch called off (the host did not follow)` within
  0.5 s and Steve snaps back to V.
- [ ] R1 hand-off: after a glide or launch lands, Steve takes V's position again as soon as V stands at the landing spot,
  with no visible 0.5 s freeze. Flight messages carry `kind` and `ts`: the DLL's per-flight summary line shows the median
  spacing.
- [ ] R2 (with C1 merged): aiming about 0.3 m beside a pedestrian, or 0.5 m beside a car, and pressing G picks it.
  `Bridge.proxyAimedAt` works from the server's proxy positions.
- [ ] R6 hooks: with gorilla_arms installed and an empty main hand, a punch shoves the NPC once (the hit impulse is twice a
  bare punch); the gorilla_arms perk alone does the same, and both together still shove once. With reinforced_tendons
  installed, V's first air jump is V's own double jump and the second press opens the elytra. Kiroshi outlines appear
  only around hostile proxies: ProxyEntity DATA_HOSTILE is synced to the client.

### Re-test 2 — package C (Minecraft cyber)
- [ ] Survival, fresh kit: install the kit's Sandevistan (right-click it, or U then click). Expect the overlay "Installed
  Sandevistan (returned Cyberdeck)" and the latest.log line `cyberware: installed sandevistan in operating_system/0`.
  Then scan a target with G and fire a quickhack chip. Expect "Your Operating System slot holds Sandevistan: swap in the
  cyberdeck with U to hack" (the lang line `cyberblock.hack.no_deck_os`, added at integration; the code's fallback text
  is the same), not "...or carry one".
- [ ] In the same state, use a RAM shard. Expect the same OS-slot message instead of the generic no-deck one.
- [ ] Press U and click the returned Cyberdeck to swap it back in. Expect "Installed Cyberdeck (returned Sandevistan)",
  the RAM HUD back at 8+, and quickhacks firing again.
- [ ] Rebind the Cyberware key (for example to O) and repeat the first check. The message names the new key.

### Re-test 2 — package A (art, assets, lang, recipes)
- [ ] With C's items merged (they are), these are pure integration checks. In the creative tab, every new cyberware item
  shows its own icon (no purple-black missing texture) and its name from the lang file.
- [ ] Open each recipe in the recipe book or craft one: for example redstone + copper_ingot + clock + feather gives
  Kerenzikov; redstone + copper_ingot + cyberblock:ram_shard x2 + gold_ingot gives RAM Upgrade. Expected: no recipe
  parse errors in the Minecraft log.
- [ ] Press U: the cyberware screen background (`textures/gui/cyberware_bg.png`, 256x192) draws without stretching. The
  art layout is A's guess, so C's boxes may need nudging: the silhouette is centred on x=64 and spans y 20..177, the right
  list field spans x 135..247 and y 22..171, and the title strip (y<18) and bottom strip (y>175) are empty. (C's slot
  boxes span x 8..132 and y 20..184, so the last row reaches 9 px into the bottom strip; the implant cells span x
  150..238 and y 32..164, inside the list field.)
- [ ] Hover a cyberware item: the tooltip lines (Slot, .mc, .cp, Cooldown, Install) read as plain text and none contains
  a stray `%` placeholder. Open the skill tree (K) on Double Jump, Sandevistan Sprint and Optical Camo: the texts say
  they upgrade the implants.

### Re-test 2 — package P
- [ ] With a dev client on a test port (`-Pcyberblock.port=<port>`, `-Pcyberblock.frame=<frame>`) and `uv run python -m
  tools.fakehost --layer4 --port <port> --hold 15`: after a glide or launch starts, the fake host's `cam` carries
  `fly:true` while it follows `flight` (Minecraft keeps the flight beyond 0.5 s and does not log `glide called off`), and
  `fly:false` after touchdown. Only a real client can show this: the unit tests only check what the fake host sends.
- [ ] Same client with `--layer6 --hold 12`: Minecraft logs a `key cyberware` pair twice (the Cyberware screen opens and
  closes) and a `key activate` tap (Y fires the selected implant or says "No active implant installed"); fakehost prints
  `align`, `cyberware` and `cyberfx` lines when Minecraft sends them.
- [ ] A world aligned by fakehost (its `cam` has no `lv`, so Minecraft converts it at its own `k`): fakehost prints `align`
  once and then the `blocks reset:true` resync, and nothing else changes.
- [x] Integration: the DLL's and Minecraft's alignment tests agree with the fixed `align` table in `shared/protocol.md`
  and `shared/vectors.json` (6.907 gives 57, 6.001 gives 63, 0.0 gives 63, -3.25 gives 47, 312.999 gives 63, -0.0005
  gives 63, 64.5 gives 31). Checked at integration: Python `align_k`, Java `GroundAlignTest` and `shared/vectors.json`
  agree; `cb_tests` checks that the vector file holds the 7 cases (the DLL never chooses `k`).
- [ ] All user-facing Re-test 2 checks (R1 to R6, F-1..F-3, L-1..L-7, the 17 implants) are in the "Re-test 2" section
  at the top with the log lines to grep.

### Layer 2 — track P
- [ ] Track M's M6 oracle: with the dev Minecraft client running on a test port, run 'uv run python -m tools.fakehost --layer2 --url ws://127.0.0.1:<port> --shm <name> --cmd "summon tnt 0 64 0" --hold 15' and confirm that explosion arrives (power 4), that block events arrive when blocks are placed or destroyed, that hit arrives when a bow or sword hits a proxy, and that Steve loses 5 hearts (25% of max health) from the dmg. Only Minecraft can show this, since the fake host has no real Minecraft side.
- [ ] Integration: confirm the DLL's own copy of the layer-2 conversions (cp/plugin/src/cbmath.*) passes the new mc_pos and mc_block vectors in shared/vectors.json.

### Layer 2 — track M
- [ ] With the dev client on a test port (-Pcyberblock.port, -Pcyberblock.frame) and the real DLL and updated S1 script installed: /fill a region of about 2000 cells (e.g. fill 30 70 30 50 80 50 stone) and then clear it; confirm in Night City that Cyberblock_Log shows no queue drops (QueueStats dropped stays 0) and that every collision box appears and disappears, with no invisible walls left behind. Rerun mc/tools-dev/layer2_check.py against the dev client to confirm the 363-cell fill/clear checks still pass with the slower pacing (expected about 1.2 s per fill).

### Layer 2 — track D
- [ ] After loading, red4ext/logs/Cyberblock.log shows 'redscript natives registered' and no 'native ...: unknown parameter type' or 'unknown return type' lines. This is where array:Uint64, array:Float, array:Bool and Uint64 resolve in the game's RTTI at post-register time. Layer 1 only proved array:Vector4. If any of these lines appear, the redscript call to that native will misbehave.
- [ ] With the mashup Active and Minecraft attached: run /summon tnt in Minecraft. A CET console call to Cyberblock_NextEvent() should return the CName explosion, then Cyberblock_EventPos() should be the TNT position in Cyberpunk space and Cyberblock_EventAmount() should be 4.0. A second call to Cyberblock_NextEvent() should return an empty CName. Once S1's pump lands the scripts drain events themselves, so this check is easiest before then or with the pump disabled.
- [ ] The 5 s memory line in the log reads 'mc events: N queued, M dropped, K rejected'. In normal play dropped and rejected stay 0 and queued is near 0. A climbing dropped count means the redscript pump is not draining events (up to 64 per tick).
- [ ] Yaw convention for Cyberblock_SubmitNpcs: the DLL assumes Cyberpunk yaw in degrees as Entity.GetWorldYaw reports it (0 faces +Y, counter-clockwise from above). Stand an NPC facing north and check that its Minecraft proxy faces the same way (Cyberpunk yaw 0 becomes Minecraft yaw 180, facing -Z). S1's Npcs.reds must pass degrees, not radians.
- [ ] Cyberblock_Damage: shoot V and check the Minecraft log or fakehost shows a dmg with a sensible frac and a from position that is correctly converted (Cyberpunk shooter at (x, y, z) arrives as Minecraft (x, z, -y)).

### Layer 2 — track S1
- [ ] God mode still reaches the damage pipeline (Active, on foot): let an NPC shoot V. Cyberblock.log should show 'dmg bullet <dmg> of <max> from known (Attacks....)'. In CET, Game.GetStatPoolsSystem():GetStatPoolValue(Game.GetPlayer():GetEntityID(), gamedataStatPoolType.Health, true) should stay at 100. If no dmg line appears, the native layer skipped OnHit for the invulnerable player.
- [ ] Fall damage: jump from about 10-15 m while Active. Expect a 'dmg fall' line, and frac >= 1 for a death landing. If there is no line, the landing AoE attack does not hit its own instigator (self-damage flag missing from the effect set).
- [ ] Vehicle hit: let a car hit V while Active. Expect 'dmg vehicle ... from known'. Check that the from point (Minecraft knockback direction) is on the car's side, which confirms the hitDirection sign used in the fallback.
- [ ] Grenade or explosive barrel near V: expect 'dmg explosion'. Standing in fire: expect 'dmg fire' or 'dmg explosion'.
- [ ] NPC registry: on a busy street while Active, the 5 s line 'npcs: N sent of M NPCs/vehicles attached' should show M > 0 and N <= 48. Stand next to parked or moving traffic and confirm vehicle proxies appear in Minecraft; this proves the native type name n"vehicleBaseObject" resolves in EntityTarget.Type. Also confirm M does not keep growing over minutes (detach pruning works).
- [ ] Yaw: an NPC proxy's facing in Minecraft matches the NPC in Night City. Or compare CET npc:GetWorldYaw() with Rad2Deg(atan2(-fwd.x, fwd.y)).
- [ ] Codeware entity callbacks during a heavy spawn (fast travel into a crowded area): no crash or script errors. The callbacks only push or remove weak refs in a script array, which assumes entity attach runs on the game thread.
- [ ] After a save or load while Active: the log shows the collision clear lines and boxes come back on the next live tick (needs S2's Collision).

### Layer 2 — track S2
- [ ] Sword kill on a pedestrian (CET: CyberblockEffects.Hit(g, <ped EntityID hash>, 2.0, Vector4.new(6,0,0,0))): the pedestrian plays a normal death or ragdoll and is NOT dismembered, and the redscript/Cyberblock log has no errors. A non-lethal hit (frac 0.2) plays a melee hit reaction.
- [ ] Same Hit on a quest NPC or companion (Invulnerable/Immortal god mode or friendly, e.g. a fixer or Judy) and on an NPC sitting in a workspot (a bar stool, a bench): no ragdoll and no push. A plain hostile ganger in the open gets ragdolled.
- [ ] Hit with frac 0.2 and impulse (6,0,0) on a traffic car: the car takes damage and is pushed. The same call while V drives that car: the car is not pushed.
- [ ] Hit on a vehicle in decay state (burning or about to explode): no extra damage is applied.
- [ ] After a sword hit on a civilian, NCPD heat and the crime report still appear (needs SetAttackTime and attackType for PreventionSystem.CreateNewPreventionDamageRequest).
- [ ] Collision check 6 in docs/notes/collision-spike.md: add a box, CyberblockCollision.Reset(g), then Stream after moving 5 m. The box does not return, and the log has 'collision: reset, 1 boxes forgotten'.

### Layers 3-6 — track P
- [ ] README claims to confirm during the I.2 acceptance run (they come from the plan, not from anything verified here): Space while falling with an elytra opens it and a fall above 15 m/s for 0.5 s opens it on its own; double-tapping Left Shift within 300 ms with the Sandevistan perk fires it; F5 does not quick-save; the G/K rebinding limits.
- [ ] Run uv run python -m tools.fakehost --port 25710 --layer3 --layer4 --layer6 --hold 12 against a test client (track M or C oracle) and check that Minecraft's printed 'mobs:', 'flight:', 'proj:', 'scan:' and 'hack:' lines match shared/protocol.md and that the fall cam at x=-1, z=-1 starts a glide when the jump tap arrives. Nothing here could be checked without a client: no client was launched and no port was opened beyond the ephemeral test servers.

### Layers 3-6 — track D
- [ ] G-1 keyboard path: press F9 to activate, then read red4ext\logs\Cyberblock.log for the line 'raw keyboard input is registered (flags ...)' or 'raw keyboard input is not registered'. Then, while Active, press F5 and G and K: expect no quicksave notification, no walk toggle, no crafting menu. Press E, Q, T, 1, 2: nothing happens in Cyberpunk (1/2 do not pick interaction choices), and Minecraft receives inventory/drop/chat/slot.
- [ ] IAT hook: the log should show 'hooked GetRawInputData in the game's import table' at startup. While Active, left/right/middle click and scroll: Cyberpunk shows no weapon wheel or weapon switching, no Tag/scanner zoom, and V's camera still turns with the mouse. After F9 off, the mouse buttons work again. If the log says 'could not hook GetRawInputData', mouse buttons also reach Cyberpunk.
- [ ] Key-up rule: hold E, press F9 (Active), release E: Cyberpunk must have no stuck E (interaction does not repeat). Hold the left mouse button, press F9, release: no stuck fire or attack in Cyberpunk.
- [ ] Space and Left Shift while Active: V still jumps and sprints in Cyberpunk, and Minecraft receives key jump and key sprint (cp\scripts or fakehost output) with no auto-repeat.
- [ ] Binds: rebind the hack key in Minecraft's controls to Z: Z is swallowed and G reaches Cyberpunk again. Bind it to W or E: Minecraft's chat says it works in Cyberpunk as G, and G is the hack key there (Minecraft now sends the default for the keys the DLL refuses).
- [ ] Menus (needs S1 calling Cyberblock_SetMenu): open Cyberpunk's pause or inventory menu while Active: E/Q/T/1-9 and mouse clicks behave normally in the menu, and forwarding resumes when it closes.
- [ ] Look capture, from the CET console: Cyberblock_SetLookCapture(true), move the mouse, and fakehost/Minecraft log shows look {d:[dyaw,dpitch]} (right = positive yaw, down = positive pitch, 0.15 degrees per count, at most 120/s). Cyberblock_SetLookCapture(false) stops it.
- [ ] Stance: Cyberblock_SetStance(true) from CET, and fakehost shows cam.cr true on every cam message; false clears it.
- [ ] Game mode: Cyberblock_GameMode() == CName.new("creative") in a creative Minecraft world, n"" before the first status or after Minecraft closes.
- [ ] The hook with ReShade's own input hooks: open and close the ReShade overlay (Home) while Active and check the mouse still works and no crash on unload (F-key exit or alt-F4).
- [ ] Mob/flight/proj natives with a real Minecraft (track M): CET Cyberblock_MobCount() and Cyberblock_FlightSeq() change as mobs appear and when gliding starts and ends.

### Layers 3-6 — track M
- [ ] With real Cyberpunk: start a glide (elytra, jump while V falls), open the pause menu mid-air for more than 2 s, then resume. Expect: Steve is not left gliding; the elytra durability tooltip does not drop while paused or afterwards; V/Steve hang at the stop point and follow V again; a new jump-while-falling starts a fresh glide.

### Layers 3-6 — track C
- [ ] With real Cyberpunk: let Cyberpunk connect while Minecraft is at the title screen, then open a world where Steve owns double_jump and dodge. Expected, without buying anything: the Cyberpunk log or CET shows a 'perks' message with 20 entries carrying those ranks, V can double jump, and dodge works.
- [ ] In the same session, Save and Quit to the title in Minecraft. Expected: Cyberpunk receives a 'perks' message with all ranks at 0, double jump no longer works, and silent_steps Visibility is back to normal. Then open a different world with different ranks: Cyberpunk gets that world's ranks.
- [ ] After merging with M (whose HostLink calls Bridge.dispatch): start Cyberpunk first, so its link waits, then start Minecraft. Expected: 'binds' arrives right after hello. If it is ever missing, see the entrypoint-order note.

### Layers 3-6 — track S1
- [ ] G-1 keyboard path: track D logs whether GetRegisteredRawInputDevices lists usage page 1 / usage 6. With Cyberblock Active on foot, press F5 and G: there should be no 'Quicksave' notification and no walk toggle. The DLL swallow comes first, and the Disable.reds wraps on HandleQuickSave and ProcessToggleWalkInput are the second line.
- [ ] G-2 stat modifiers are not saved: equip Reinforced Tendons and go Active on foot. Double jump should fail, and CET Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.HasDoubleJump) should return 0. Save from the pause menu while Active (PlayerState removes the modifiers before the save), turn Cyberblock Off and reload: the same CET line should return 1.
- [ ] G-9 lethal paths under Cyberblock god mode. Fall test: Active, jump off a megabuilding roof (DeathLand). V should survive, and Steve should take fall damage with frac about 4 (Cyberblock.log line 'dmg fall'). Kill test: in a throwaway save, apply BaseStatusEffect.ForceKill to V while Active. Does V die?
- [ ] G-10 DoT wrap: while Active, let an NPC with an incendiary weapon (or another Burning applicator) set V on fire. Expect several 'dmg fire' lines in Cyberblock.log from one ignition, with V's Health unchanged.
- [ ] G-11 camo perks: with a camo slide or grapple perk owned and Cyberblock Active (CanUseOpticalCamo and CanUseTerrainCamo x0 applied), slide or grapple. V should not cloak.
- [ ] G-12 superhero landing: with a ground-slam source owned and Cyberblock Active (HasSuperheroFall x0 applied), crouch in mid-air. There should be no slam.
- [ ] G-13 radial wheel and vehicle: Active on foot, holding Alt opens no wheel and the world does not freeze. Get into a car (Active to Vehicle; PlayerState clears NoRadialMenus and its VehicleBlockRadioInput tag): the vehicle radio wheel should work.
- [ ] G-14 restrictions not left in saves: save while Active, then load. CET StatusEffectSystem.ObjectHasStatusEffect(Game.GetPlayer(), TweakDBID.new('GameplayRestriction.NoScanning')) should be false, as for NoHealing, NoRadialMenus, NoQuickMelee, NoZooming and BodyCarryingForceDrop. Optionally the audit's raw check: apply NoScanning with isSavable=false, save, reload, and see whether it is gone.
- [ ] DISABLE rows while Active on foot: Tab does not scan (no world slowdown); X does not heal; there is no double jump, charge jump, hover, dodge or air dash; dead or downed NPC bodies cannot be picked up; zoom does nothing. After Steve buys the dodge perk (K): dash and air dash work. After Steve buys double_jump: double jump works (S3 grants +1, S1 no longer zeroes it).
- [ ] Menu flag: Active, open the pause menu and press W/A/S/D and E. Steve must not move or act in Minecraft; after the menu closes, input reaches Minecraft again.
- [ ] Stance: Active, crouch V (C), then slide. Fake host or Minecraft should show cam.cr=true (Steve in the crouch pose); stand up and cr returns to false.
- [ ] Probes through our boxes: Active, build a 3-block Minecraft wall 2 m from V on a street. In Minecraft, no barrier should appear in the air cell in front of the wall, the wall block can be targeted and outlined (the aim is no longer stopped at the box face), and the street under a placed block stays ground.
- [ ] Flight probes: glide from a roof (layer 4). Minecraft's barrier ground should appear well ahead in the glide direction, up to 64 m, and the 2 m grid should fill in to 1 m once within 24 m.
- [ ] Creative: Minecraft in creative, F9 while driving. Crash into a wall at speed and let NPCs shoot the car: V cannot die. Cyberblock.log should show 'creative: V invulnerable in the vehicle', and 'creative: V vulnerable again' on getting out or switching to survival.
- [ ] Fall suppression: land a glide or an ender pearl teleport from a height. Within 2 s there should be no 'dmg fall' sent; the log should show 'not reported: flight or teleport landing'.
- [ ] block_reset: reconnect Minecraft while Off. Cyberblock.log should show 'events: block reset' and 'collision: reset'; after F9 the boxes come back from Minecraft's resync.
- [ ] Inactive requests: with Cyberblock Off, hold G in Minecraft and use a quickhack item. Minecraft should get scaninfo ok:false and hackresult ok:false with reason 'Cyberblock is not active'.

### Layers 3-6 — track S2
- [ ] Gate check, after the Npcs.reds patch is merged: Cyberblock Active, Minecraft running, spawn a zombie from a spawn egg next to Steve. Expected: Cyberblock.log has 'puppets: spawned for ... zombie', the 'npcs: N sent' count does not grow by one, and Minecraft shows no human NPC proxy at the zombie's position (F3+B shows only the zombie's hitbox there).
- [ ] Despawn window: kill the zombie in Minecraft. Expected: 'puppets: despawned mob <id>' in the log and no human proxy appearing at the spot, not even briefly. This confirms that the Codeware tag (IsTagged) outlives the entity's attachment, or detaches in the same frame. If a proxy flashes, the Despawn spawner event comes before Entity/Detach.

### Layers 3-6 — track S3
- [ ] G-3 Quickhack: in CET, npc = Game.GetTargetingSystem():GetLookAtObject(Game.GetPlayer(), false, false), then StatusEffectHelper.ApplyStatusEffect(npc, TweakDBID.new('BaseStatusEffect.OverloadLevel1'), Game.GetPlayer():GetEntityID()). Expect electrocution VFX and damage. Then use a Redstone Charge from Minecraft on a ganger. Expect an upload delay, the same result plus NCPD heat, and Cyberblock.log 'hack short_circuit on <id>: ok'. Repeat on a crowd NPC and on a mech.
- [ ] G-4 Device hack: Redstone Lever on a vending machine -> the screen glitches and cans drop (log 'hack device on <id>: ok'). Redstone Lever on a camera -> its detection cone stops tracking V, and the Minecraft view must NOT switch to the CCTV.
- [ ] G-5 Traffic: Redstone Lever on a traffic light at an intersection. Do the intersection's lights change, and do cars stop and go?
- [ ] G-6 Slow-mo: buy Sandevistan and double-tap sprint. NPCs and cars run at x0.2 while V does not, for 5 s. Log: 'perks: sandevistan slow-mo for 5 s', then 'perks: slow-mo ended'. Note any screen effect. Pressing F9 during the slow-mo must restore normal time at once.
- [ ] G-7 Camo: in combat, drink an invisibility potion -> hostiles lose V. Compare with only Game.GetPlayer():SetInvisible(true) in CET. After 10 s, StatusEffectSystem.ObjectHasStatusEffect(Game.GetPlayer(), TweakDBID.new('BaseStatusEffect.OpticalCamoPlayerBuffRare')) should still be true (Buffs re-applies it once a second if camo decay removes it).
- [ ] G-8 Visibility: with Silent Steps bought, Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.Visibility) is half its perk-off value. Crouch-walk towards an idle guard and compare how long the detection meter takes to fill with and without the perk.
- [ ] Hack short_circuit (Redstone Charge) on a ganger: after the upload, electrocution VFX and damage; hackresult ok:true.
- [ ] Hack overheat (Blaze Chip): the ganger burns (overheat VFX) and its health ticks down; Steve takes no damage.
- [ ] Hack reboot_optics (Ink Sac Daemon): blinded VFX, and the ganger loses track of V for the effect's duration.
- [ ] Hack cripple_movement (Cobweb Daemon): the ganger stops moving.
- [ ] Hack contagion (Spider Eye Virus): poison VFX and damage over time on the target. Note whether it spreads (that depends on V's spread perks).
- [ ] Hack synapse_burnout (Wither Skull Daemon): brain-bolt VFX and heavy damage.
- [ ] Hack ping (Compass Ping): the log shows ok, and in CET StatusEffectSystem.ObjectHasStatusEffect(npc, TweakDBID.new('BaseStatusEffect.Ping')) is true. Nothing is visible because the HUD is hidden.
- [ ] Hack memory_wipe (Bottle of Forgetting) on a ganger fighting V: it drops combat and returns to idle.
- [ ] Hack cyberpsychosis (Creeper Brain) on a ganger in a group: it attacks its squad, then kills itself.
- [ ] Hack device (Redstone Lever): on a vending machine, a camera and a traffic light as in G-4/G-5; on a TV or radio it glitches or distracts; on a powered-off device, hackresult ok:false with reason 'not hackable'. The camera must never take over the view.
- [ ] Chain Hack perk: with the perk, a ganger within 8 m of the target gets the same hack (two VFX). A companion, or a quest NPC in a scene, gives 'not hackable'; a dead target gives 'target gone'.
- [ ] Buff invisibility: see G-7. When the potion ends, V is visible to NPCs again (SetInvisible(false)) and the camo status effect is gone.
- [ ] Buff speed: read GetStatValue(V, gamedataStatType.MaxSpeed) before and during Speed II -> x1.4, and V crosses a measured street visibly faster. The value is back to normal at potion end and on F9 off.
- [ ] Buff slowness: Slowness I -> MaxSpeed x0.85 and V is slower. Slowness X stays at x0.3.
- [ ] Buff jump_boost: with Jump Boost II, V clears an obstacle about twice the normal jump height. Normal again afterwards.
- [ ] Buff nausea: eating a pufferfish puts the drunk wobble on V (StatusEffectSystem.ObjectHasStatusEffect(Game.GetPlayer(), TweakDBID.new('BaseStatusEffect.Drunk')) is true). It is removed when Minecraft removes the effect. Check whether the wobble is visible behind the Minecraft frame.
- [ ] Buff blindness: splash blindness gives the blind glitch screen on V (AIQuickHackStatusEffect.HackBlind). It is gone when the Minecraft effect ends, even if that is later than its own 8 s.
- [ ] Buff darkness: same as blindness (/effect give @p darkness).
- [ ] Unmapped effects (poison, wither): the log shows 'buffs: effect poison has no Cyberpunk mapping, ignored' once. V gets no effect, and Steve takes only Minecraft's own poison damage.
- [ ] Perk double_jump: with the perk bought and Cyberblock Active, V double-jumps, and GetStatValue(V, HasDoubleJump) >= 1. After F9 off, V's own value is back.
- [ ] Scan: hack key held on a ganger -> scaninfo ok:true with name, faction and threat; on a vending machine -> device:true; on the sky -> ok:false.
- [ ] Kills: killing a hostile ganger with a sword logs 'killed human <id> (hostile)' once; destroying a car with TNT logs 'killed vehicle <id>'; a kill V makes with Cyberblock Off logs nothing.
