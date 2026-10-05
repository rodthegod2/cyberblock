# Changelog

All notable changes to Cyberblock are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). One version number covers the DLL, the scripts, the shader, the Minecraft mod
and the tools.

## [2.0.0] - unreleased
Version 2.0.0 is **proposed** (the wire grew a server: the owner decides the number) and is set in the files on 2026-10-04. It is tagged
and published only after the in-game multiplayer session ([docs/TESTING-PLAN.md](docs/TESTING-PLAN.md), "Full multiplayer pass" and
"Re-test 9") has passed **and the owner says "push"**. The never-released 1.0.0 draft is part of it: every entry below, down to
"Added", ships in 2.0.0.

### Release notes: 2.0.0, the multiplayer version
**Cyberblock is now a multiplayer game.** What was a single-player passthrough of Minecraft in Night City is a Minecraft server
that friends join with Minecraft only, while one or more players ("hosts") link their own Cyberpunk 2077 game to it.

**What is new, in one list**
- **A server.** Every world, singleplayer included, runs through a server; a LAN world and a dedicated Fabric 26.3 server work the same
  way. Hosts link Cyberpunk, guests play with Minecraft only. A join password (or the whitelist) keeps strangers out.
  Guide for the owner: [docs/SERVER.md](docs/SERVER.md); for friends: [docs/JOIN.md](docs/JOIN.md).
- **Night City as Minecraft.** The streets the hosts explore become a saved, shared replica of solid coloured blocks; the people and
  cars are figures with skins and poses; Cyberpunk's time and weather follow the primary host; guests build while the host is away.
- **A world you can use.** Devices (doors, gates, lights, cameras, terminals, vending machines, traffic lights, lifts, turrets,
  screens) are blocks; redstone hacking, the Breach Protocol and the interaction map hack Night City from Minecraft.
- **People and places.** Ripperdocs, vendors, fixers, bartenders, medics and mechanics are screens; the Afterlife hub and the
  Holo-Table; hosts' Cyberpunk shops as Minecraft screens.
- **Roles and modes.** The Hacker in the Van, the Overlord, matches (team deathmatch, Gang Wars), PvP by consent, hacking players with
  counterplay, Server Heat with Max-Tac, going down and revives, the Trauma Team, spectators and a caster overlay.
- **The finish (Phase 9).** Every content kind is data: friends add dialogue, skins, shops, landmarks, Breach daemons and Overlord events
  as a datapack ([docs/CONTENT.md](docs/CONTENT.md)); one **settings screen** with the tabs Guests, Look, PvP, Difficulty, Performance
  and World (the World tab shows `server.json` read-only); **Performance mode**, two sliders saved on each player's PC: **Frame scale**
  (50 to 100 %, default 100) and **Figures** (16 to 48, default 48), plus figures that stop animating beyond a distance and per-tick
  **server budgets** for replica placements, device updates and the messages to each host's Cyberpunk; **daily server backups** with
  `tools.server_sync pull-backups`; the save and load lists as a Minecraft screen where the game's API allows, otherwise documented
  as pass-through.
- **Quality of life from the first sessions.** Survival mining of placed blocks, stairs with no vault prompt and a step-up assist,
  a calmer third-person camera, outline blocks that come back in creative, the escape hatch to Cyberpunk's own menu at all times.

**Upgrading from the 1.0.0 draft or earlier**
- **Install the same version on both sides.** The mod's major and minor version must match the server's (`2.0.x` with `2.0.y`); the
  DLL, the scripts and the mod jar come from the same release, as before. Run `install.bat` again and replace the mod jar.
- **Update a running server jar-only** ([docs/SERVER.md](docs/SERVER.md) section 7): stop it, delete or rename the old
  `mods/cyberblock-<old>.jar` (the new jar has another name, and two Cyberblock jars stop Fabric from starting), upload only the new jar,
  start. **Never re-run `tools.server_bundle` against a live Cyberblock server:** it is for the first move only, and its upload replaces
  `config/cyberblock/server.json` (the join password), `server.properties` and `server.properties.rodsores` (the way back).
- **A server that ran an earlier build keeps its old `config/cyberblock/server.json`**: set `"maxHosts": 4` yourself if you want more than
  one host (only a file the server writes itself gets 4; the example file `tools.server_bundle` stages says 1), and look at the new
  `backups` and `joinPassword` keys.
- The new `/cyberblock` commands are listed in [HOW-TO-PLAY](docs/HOW-TO-PLAY.md#commands): `settings`, `backup now`, `login`,
  `hub`, `spectate`, `guests <toggle>` and more. Hosts' guest permissions default to **on** except `spawns` (off).
- Nothing in Cyberpunk's saves changes: everything Cyberblock adds is removed before each save, as in 1.0.0.

**Known limits** (the full list is each phase's descopes note and "Phase 9 descopes and risks" in the test plan)
- The Phase 0 in-game checks have **not** been run. Wherever the game's API is unconfirmed, the build uses the documented call with a runtime
  check and the written fallback (Minecraft squads instead of Night City enemies, a guessed surface material, no wanted level write, ...),
  and logs one line saying which path is active.
- Everything passes the automatic suites (pytest, Gradle, the C++ tests, `fx_check`, `compile_check`, `content_check` and `mpcheck
  --all`); the Cyberpunk side of it is played in game only through the test lists.
- The performance numbers in the guide are filled in after the first measured run (R9-5).

### Unreleased additions: Phase 9 integration (Wave C)
Phase 9's packages merged in plan order (9C, 9U, 9F, 9S, 9B, 9P) on top of Phases 6, 7 and 8.
- **Content (9C).** `examples/content-datapack/` (Neon Crew: one dialogue line, skin, shop, landmark, Breach daemon and Overlord event, with a
  `pack.mcmeta` and the resource-pack half), loader checks, the client's `cyberblock:content` receiver and reload tests. `tools.content_check
  --dir` refuses a datapack root without a usable `pack.mcmeta` (both `min_format` and `max_format`, as Minecraft 26.3 requires).
- **Night City saves (9U).** Esc, **Saves...** is a Minecraft screen (`SavesScreen`, `Saves.reds`): list, load by name (only a save Cyberpunk
  calls valid), New Save, Quick Save. When Night City gives no list within 3 s, Night City Menu... (Cyberpunk's own lists) is the fallback and
  the log says which path is active. The escape hatch also catches screens Minecraft cannot open. `shared/protocol.md` documents the `uiact`
  extensions and the saves `reply`; `tools.fakehost --ui` answers `journalreq arg:"saves"`.
- **Performance (9F).** `prefs.scale` (Minecraft renders at 50-100 % and Cyberblock.fx upscales), the figure cap and distance LOD on each
  client, per-tick server budgets for replica placements, device updates and the DOWN queue (a full queue runs the host's oldest job, so a
  host's messages keep their order), `tools.perfcheck` and `tools.loadhost`.
- **Settings screen (9S).** The full screen with the tabs Guests, Look, PvP, Difficulty, Performance and World, its server half (the settings
  snapshot, `/cyberblock difficulty`), commands paced one about every 1.1 s so a non-operator is never kicked for spam, and no mouse-wheel
  changes on server rows.
- **Backups (9B).** Daily world backups, `/cyberblock backup now` (at most 3 extra timed zips a day, never evicting a daily one), no half file on
  failure or shutdown, and `tools.server_sync pull-backups`.
- **Merge fixes.** `shared/protocol.md` keeps 9F's Performance mode and 9S's settings sections side by side; `Cyberblock.java` runs both
  `ServerSettings.init` and `Backups.init`; `docs/CONTENT.md` is 9P's tested guide plus 9C's worked example; the docs state the distance LOD
  and the server budgets plainly (9F merged them); the recipe test writes the guide's `pack.mcmeta`.
- **Test plan.** `docs/TESTING-PLAN.md` starts with **Re-test: Wave C (Phases 6, 7, 8, 9)**: a one-screen "What to try" (solo first, then on the
  server with friends) and one id per check (6R-1 ... 9P-3).

### Unreleased additions: Phase 9 (multiplayer plan, package 9P)
Phase 9 of the multiplayer plan, docs and release side: the finish. The mod, client, tools and scripts of Phase 9 have their own entries
(packages 9C content, 9U UI gaps, 9F performance, 9S settings screen and 9B backups).
- **Docs.** New [docs/SERVER.md](docs/SERVER.md) (running a server: the setup step by step, who may join, `server.json`, operator commands, daily
  backups and `pull-backups`, updating, the way back and a troubleshooting table), [docs/JOIN.md](docs/JOIN.md) (a friend's way in: the modpack,
  **Join Cyberblock**, the password, settings) and [docs/CONTENT.md](docs/CONTENT.md) (a datapack with one worked, machine-checked recipe each for a
  dialogue line, a skin, a shop, a landmark, a Breach daemon and an Overlord event); all three are linked from the README ("Play together").
  HOW-TO-PLAY has a full pass: the version, the status banner, the settings screen with its six tabs, **Performance mode**, datapack content, daily
  backups, the save and load lists and the escape hatch, joining troubleshooting, the commands list, and the short
  "Creative mode and the outline blocks" note with the World-tab setting **Creative can erase the replica permanently**.
- **Test plan.** `docs/TESTING-PLAN.md` starts with the **Full multiplayer pass** (the one session for Waves A to C and Phase 9, steps M-1 to
  M-10, each pointing into the detailed older sections) and **Re-test 9** (R9-1 to R9-8: a friend's datapack with a dialogue line and a skin shown
  by `/reload`, the other kinds from CONTENT.md and the example datapack, every settings tab, a backup at the set hour and `pull-backups`, Performance mode with four guests, the save
  and load screen or its documented pass-through, the escape hatch, the "which way" lines and the machine checks).
- **Version 2.0.0 (proposed).** `pyproject.toml`, `uv.lock`, `cp/plugin/CMakeLists.txt` (and with it `CB_VERSION` and the DLL's `kVersion`),
  `mc/gradle.properties` (the mod jar and `fabric.mod.json`), the `Natives.reds` header and this changelog say 2.0.0, and the documents name
  `Cyberblock-2.0.0.zip` and `cyberblock-2.0.0.jar` (`tests/test_version.py` checks every one). The DLL's version test in
  `cp/plugin/tests/test_link.cpp` moved with it. **No tag, no release and no push** until the owner says so; if the owner prefers another number,
  one search and replace in those files changes it.
- **Tests.** `tests/test_phase9_docs.py` checks the three new guides (links, anchors, the recipes validated by `tools.content_check`, no private
  value), the guide's Phase 9 sections, the test plan's new sections and this entry.

### Unreleased additions: Phase 6 (multiplayer plan, package 6P)
Phase 6 of the multiplayer plan: **Redstone Hacking, Breach Protocol and the interaction map**. This entry is the protocol, tools and docs
side; the mod, plugin and scripts of Phase 6 have their own entries. The in-game list is [docs/TESTING-PLAN.md](docs/TESTING-PLAN.md),
"Re-test 6".
- **Protocol.** `shared/protocol.md` "Phase 6": `rhack` (DOWN to the owner only: five vehicle hacks with `secs` 30, `cam_feed`, and five area hacks
  with `target:null` and whole `p`, `r` and `secs`, the district hacks centred on V), `daemon` (a solved Breach's daemons at strength 1..3, or
  exactly `["alarm"]` for a failed one), `feedreq`, `feed` (a 128 x 128 picture of map colours, BOTH: shown locally and relayed UP, at most once a
  second, rate 2 per second), `hackresult` extended to answer `rhack` and `daemon` (`upload` 0 and its `kind` on a success), the six
  `cyberblock:act` payloads (`rhack`, `breach`, `feed`, `feedmap`, `netmap`, `comms`) and the ten `cyberblock:ui` payloads (`breach`, `feed`,
  `comms`, `ract`, `netmap`, `vpanel`, `camfeed`, `outline`, `netping`, `scanowner`) as the server builds them, the range rule (every streamed
  target, the 50 m fallback of Phase 0's check (h) switching itself on, `cyberblock.hack.out_of_range`), the `hs_ack` feature bit 8 (set by the
  server), and the 18th cyberware id `firewall`. Every Cyberpunk effect has the scripts' own time and is restored before every save and on the
  session end; the photo feed needs the owner's `hacks` toggle; an abort after the first pick is a failed Breach (the same puzzle is kept
  otherwise, and a failure has a 10 s cooldown; an owner hears at most one alarm a minute; the screen's `size` is 4 and `dev` `"0"` for Phase 8's
  short breach on a player). `shared/phase6-messages.json` and `shared/rhack-vectors.json` hold real messages that the Java, C++ and Python tests
  all check (validators, names and examples in `tests/test_phase6_protocol.py`).
- **One `rhack` contract (6I review).** The server's DOWN `rhack` now has exactly the documented shape (`target` null and whole `p`, `r`, `secs` on
  every area hack, the district hacks centred on V, `secs` 30 on every vehicle hack), so the fake host and a DLL built to the document answer it;
  `shared/rhack-vectors.json` holds all eleven, checked by the mod's tests and by pytest against the fake host. The feed map item no longer
  stores the picture: each frame is sent to each holder the owner's `hacks` toggle (and the join password) admits at that frame, and a holder
  turned away gets a blank picture.
- **Fake host.** `tools.fakehost --hacks2`: the `--devices` scene plus a Net Port and a traffic light; every `rhack` the real DLL takes and every
  valid `daemon` is answered with one `hackresult` the way the scripts answer (vehicle hacks change the car for the scripts' own times, `cam_feed`
  needs a camera that is on, a traffic switch and a gridlock need a light or cars, a blackout switches the devices around V off and gives them back,
  `outline` and `ping_area` reveal figures with `hackspread` before the result, `open_door` and `disable_cameras` change the devices; `unsupported`,
  `no such hack`, `target gone`, `unsupported daemon` and `device gone` say why); `feedreq on` streams a 128 x 128 test card once a second.
- **Harness.** `tools.mpcheck breach`: a guest's solved Breach sends the owner's DLL a `daemon` that it answers, a failed one the alarm, `guests hacks
  off` blocks it and `on` brings it back, a guest's Vehicle Panel and Net Map hacks reach the DLL as one `rhack` each in the server's shape and are
  answered, and the Net Port goes with the session. It needs `/cyberblock test breach <x> <y> <z> solve|fail` (6B) and the new devtools
  `/cyberblock test act <t> <json>` and `/cyberblock test eid <targets>`; the scenario is tested against a simulated server.
- **Docs.** [HOW-TO-PLAY](docs/HOW-TO-PLAY.md#redstone-hacking-phase-6) has "Redstone hacking", "Breach Protocol" and "The interaction map"; the
  Re-test 6 list is at the top of [docs/TESTING-PLAN.md](docs/TESTING-PLAN.md), each expected result tied to the log line the code prints; the
  descopes (the remote range, the traffic and blackout fallbacks,
  the replica camera view, the low-resolution or replica photo feed) are in `docs/notes/phase6-descopes.md`.
- **Photo feed fallback (6D fix).** When the DLL's back-buffer copy is too slow or cannot run it only logs one line and sends nothing (section 1.9
  has one `feed` shape); the server treats no picture within 3 s of `feedreq on` as the fallback and draws a top-down replica view around V into
  the same 128 x 128 map colours for the Net Map and the feed map, logging which path is active.

### Unreleased additions: Phase 7 (multiplayer plan, package 7P)
Phase 7 of the multiplayer plan: **talking to Night City's people, the Afterlife hub and the Holo-Table**. This entry is the protocol, tools and docs
side; the mod, plugin and scripts of Phase 7 have their own entries. The in-game list is [docs/TESTING-PLAN.md](docs/TESTING-PLAN.md),
"Re-test: Wave C, Phase 7".
- **Protocol.** `shared/protocol.md` "Phase 7": `hub` (DOWN to a host's DLL: full-screen Minecraft on or off, always sent together with a `park`: on
  entering `hub in:true` then `park on:true`, on leaving `park on:false` then `hub in:false`), the hub's safety rules (the cover only over a parked V: a
  `parked ok:false` takes the player out; a new host session of a player still in the hub hears the entering pair again; a Holo-Table spawn leaves the hub
  before the `vteleport`; no way in before `/login`), `reply` with `kind:"vendor"` (sent unrequested when the host opens a vendor; the vendor as its pin,
  stock categories as groups whose sell side is `kind:"sell"` or `done:true`, items as lines with the unit price in `state`; LOCAL: a host's shop never
  leaves its PC), `uiact` `buy` and `sell` (a line's `hash`, its index on its side and a quantity, paid in the host's real Cyberpunk money), the Holo-Table's
  `fasttravel` list (`atlasreq arg:"fasttravel"`), the `parked` relay row (2 per second, burst 4: the server relays the DLL's answer to `park` from the hub
  on), class rows for `hub` and `parked`, and the readings that differ from megaplan section 1 for the integrator; validators, names and examples in
  `tests/test_protocol_md.py`.
- **Fake host.** `tools.fakehost --vendor`: a Night City gun shop (V's 5000 eddies in `stats`, the shop's screen from 2 s to 8 s with its unrequested stock: a
  stack of two pistols and a revolver to buy, a broken radio to sell), `uiact buy` and `sell` answered the way the scripts do (a money toast, the new
  `stats` and the stock again; refusals as warnings; the quantity clamped to the stack and the money), the fast-travel list, `park` answered with `parked`
  at once and `vteleport` after 2 s; it prints `uiact`, `hub`, `park` and `vteleport`. `--park-refuse` answers `park on:true` with `parked ok:false`.
- **Harness.** `tools.mpcheck hub`, on a server with the join password like the live one: a locked player's `/cyberblock hub` does nothing; `/cyberblock hub`
  sends the host's DLL `hub in:true` then `park on:true`, once each, puts Steve in `cyberblock:afterlife` and the DLL's `parked` reaches the server; a guest's
  trip tells no DLL; leaving sends `park on:false` then `hub in:false` and puts Steve back; a link dropped inside the hub ends the session and a returning one
  hears the entering pair again; a park refused while Cyberblock is Off (`--park-off`) keeps the host in, one refused while live takes the host out; the
  relay drops nothing. It needs 7H's `/cyberblock hub`.
- **Hub fixes (7H review).** A park refused with no cover drawn (`blocked (Cyberblock is not active)`, `blocked (no player)`) no longer takes the player out
  of the hub: a host with Cyberblock Off may use it and V is parked at On (`hub: <name> V not parked yet (...)`). The client stops placing Steve from `cam`
  as its level becomes the hub (before his first tick there); the DLL counts as showing the hub only while its cams come (5 s), so with Cyberblock Off
  Minecraft's own window is the input and the DLL's last cursor is forgotten. Holding a block or food item no longer stops a survival guest's right-click on
  the Holo-Table (placing stays refused by `mayBuild`).
- **Docs.** HOW-TO-PLAY "Talking to Night City (Phase 7)", "The Afterlife hub (Phase 7)" and "The Holo-Table (Phase 7)", "Re-test: Wave C, Phase 7" in
  `docs/TESTING-PLAN.md` (the host shop, the hub with V parked and its safety checks, the Holo-Table spawns, fast travel), and the descopes of Phase 0
  checks (f) and (g): hosts' shop stock, the 2D fallback of the table, V unseen while parked, the repair of a Cyberpunk car and fast travel only when the
  game allows it.
- **Fixes (7V review).** The Holo-Table now lists the host's fast-travel points (the hook is installed at client init). A host's Minecraft shop keeps
  Cyberpunk's buy rules: a ware locked by Street Cred or level, or cyberware V cannot equip, shows "(locked)" and is refused; grenades, injectors and
  inhalers buy one at a time and ammo stops at V's cap ("Ammo full").

### Unreleased additions: Phase 8 (multiplayer plan, package 8P)
Phase 8 of the multiplayer plan: **roles and modes**: the Hacker in the Van, the Overlord, matches with PvP and Gang Wars, player hacks and their counterplay, Server
Heat, going down and the Trauma Team, and the spectator and caster mode. This entry is the protocol, tools and docs side; the mod, plugin and scripts of Phase 8 have
their own entries. The in-game list is [docs/TESTING-PLAN.md](docs/TESTING-PLAN.md), "Re-test: Wave C, Phase 8".
- **Protocol.** `shared/protocol.md` "Phase 8": `spawn` (DOWN to the owner's session only: a record `ganger`, `ncpd`, `ncpd_heavy`, `maxtac` or `drone`, a position, `n` 1..8,
  `hostile` and a `faction`) with its `spawnresult` (UP, one for every `spawn`: `ok`, `n` 0..8, a reason of at most 64 characters; rate row 10 per second, burst 40),
  `heat` (the wanted level 0..5 the server chose for each active host from the Server Heat tier, its real wanted level, sent when it changes and again after every host
  `hello`), `attitude` (a faction's stance toward V after a Gang Wars match, to the hosts of its teams: `friendly`, `neutral` or `hostile`) and `glitch` (`seconds` 0..10: the ReShade glitch of a hacked linked player). Every effect ends with the session, a link
  drop and a `hello`: spawned NPCs are deleted, attitudes and the wanted stage restored, the glitch ended. The fairness rules (the 3 second warning, the minimum distance,
  the caps, the intensity) and the opt-in rule stay on the server; the host's `spawns` toggle (off by default) gates `spawn` only, and with it off or for a
  Minecraft-only player the same enemies are Minecraft squads, which is also the written fallback of Phase 0 check (d). The plan's `turret` is not a `rec`
  (a turret is a device: Minecraft-side only). `hs_ack.features` bit 32 (modes), the 19th cyberware id `netrunner` (resists player hacks, as the 18th, `firewall`, blocks
  them), class rows, validators, names and examples in `tests/test_phase8_protocol.py` (and the class, rate and id tables of `tests/test_protocol_md.py`). A section of
  differences from megaplan section 1 lists what the integrator confirms: no `turret`, `ncpd_heavy` as a fifth `rec` (never a `heavy` flag), `attitude` says `toward`, the rate
  of `spawnresult`, the `spawns` toggle gating only `spawn`, `heat.tier` as a wanted level, `heat` again after every `hello`, and the test commands.
- **Fake host.** `tools.fakehost --modes`: the Night City side of the modes on the street of `--hacks2`. Every valid `spawn` is answered with one `spawnresult` and the
  enemies join the `npcs` list (`unsupported` for any record but the five, `no ground` outside the world's height, `too many` past 24 live ones); `heat` is written as the
  wanted level in the next `stats`; `attitude` (known groups) and `glitch` are kept; a session that leaves `host` deletes the spawned enemies and puts the stars back; all four
  are ignored without a host session. It prints `spawn`, `heat`, `attitude`, `glitch` and `spawnresult`.
- **Harness.** `tools.mpcheck match`, `heat` and `overlord`. `match`: PvP is off by default, a Minecraft-only guest cannot create a match, the linked host's match turns PvP
  on for its players and off again after the 15 second lock, `/cyberblock spectate` puts the guest in spectator mode and `spectate stop` brings it back. `heat`: with the
  `spawns` switch off, the Server Heat wanted level reaches the host's DLL in order up to 5, falls back to 0 and is told again to a new session. `overlord`: no spawn near a player in
  no match or before `/cyberblock overlord optin on`, then the Overlord's spawn of a palette entry reaches the DLL after its warning with an answered `spawnresult`, and `guests
  spawns off` keeps it out. They need the Phase 8 server (8M, 8T, 8O) and the commands that `PHASE8_COMMANDS` names (8T's `heat set`, 8M's `test match`, 8O's `test overlord spawn`)
  (the one table to edit if the packages named them otherwise); they are tested against a simulated server with a bug list each, and with `--all` a scenario whose package folder is
  not in the checkout is reported as SKIP (never as a pass).
- **Docs.** HOW-TO-PLAY "Modes: the Van, the Overlord, matches and more (Phase 8)" (the Van, the Overlord, matches and the PvP rule, Gang Wars, hacking players and counterplay,
  Server Heat, going down and the Trauma Team, spectating, and the limits), "Re-test: Wave C, Phase 8" in `docs/TESTING-PLAN.md` (P8-1 to P8-11), and the descopes of Phase 0
  checks (d) and (e) in `docs/notes/phase8-descopes.md`: Minecraft squads when spawning fails or the spawned NPCs stay passive, no real wanted level and no reputation effect when the
  writes are not available.

### Unreleased additions: Phase 8 integration (Wave C)
- **Merged packages** (in the plan's order): 8D (DLL), 8S (scripts), 8A (art and data), 8T (Heat, Downed, spectate), 8X (player hacks), 8M (matches, PvP,
  Gang Wars), 8O (Overlord), 8V (Hacker in the Van), 8P (protocol, tools, docs).
- **Wiring.** The packages were built behind fail-closed hooks; `dev.cyberblock.mode.ModeWiring` now installs them: the Overlord acts only in a running match
  and inside its zone and its Auto-Director reads the Server Heat, player hacks use the PvP toggle, the war zones and the match teams, Downed revives go to match
  teammates, and the Match Terminal's spectators, the kill feed and the caster overlay's scoreboard come from the matches. The roster carries each player's
  match team, PvP toggle and downed flag.
- **Contract.** A heavy `ncpd` palette entry goes on the wire as `rec:"ncpd_heavy"` (no `heavy` key); `/cyberblock test overlord spawn <palette id> <player>`
  (devtools) queues a spawn through the console's own path; the `spawnresult` relay rows have tests; megaplan section 1 has the dated Phase 8 amendment.

### Unreleased additions: Wave B integration (Phases 3, 4 and 5 together)
Phases 4 and 5 merged on top of Phase 3 (and the Wave A leftovers) for one combined in-game test,
[docs/TESTING-PLAN.md](docs/TESTING-PLAN.md) "Re-test: Wave B (Phases 3, 4, 5)".
- **Merged packages.** Phase 4: scripts (device stream and actions), DLL (device messages, the device-box probe filter), server (device
  registry, placement, both-way sync, per-type toggles, vending in eddies), device blocks and client, art and data, protocol and tools.
  Phase 5: NPC and vehicle skins, figure rendering (skins, poses, outlines, vehicle models), forced cyber skins with the `[V]` team, NPC
  tags in the scripts, the DLL's `Cyberblock_SubmitNpcs2`, protocol and tools.
- **Integration.** The fake host runs `--devices` and `--figures` (each with its own checks); `tools.mpcheck` has both the `devices` and
  the `figures` scenario; the mod registers the device blocks and the cyber skins at startup; the Eddie Chip shows "Worth 100 eddies" when
  hovered; the native check reads `Cyberblock_SubmitNpcs2`'s parameters again.
- **Phase 0 still open.** The in-game CET checks (the "Cyberblock Phase 0" window of the CET overlay) have not been run; every reader whose
  verdict is unconfirmed logs which path it took (the research guess or the written fallback).

### Unreleased additions: Phase 4 (multiplayer plan, package 4P)
Phase 4 of the multiplayer plan: **Night City's devices as Minecraft blocks**. This entry is the protocol, tools and docs side; the
mod, plugin and scripts of Phase 4 have their own entries. The in-game list is [docs/TESTING-PLAN.md](docs/TESTING-PLAN.md),
"Re-test: Wave B, Phase 4".
- **Protocol.** `shared/protocol.md` "Phase 4": `devices` (at most 64 devices within 48 m: the new and changed ones and a full list every 5 s, no `size`),
  `devstate` (`by` `npc`, `host`, `guest` or `script`), `devresult`, `devact` (DOWN to the owner only), the `cyberblock:act` `t:"devact"` payload (only the vending
  screen's purchase: `pos`, `act` `buy`, the shop `line`; right-clicks go through the server's use path and send no payload), the vending shop
  `cyberblock:ui` `t:"shop"` (`pos`, `title`, `eddies`, `result`, up to 27 `lines`), the device kinds with their toggle bits, the 14 acts (the DLL runs
  11, `call`, `buy` and `breach` are `unsupported`), class rows for the four types, validators, names and examples in `tests/test_protocol_md.py`.
- **Fake host.** `tools.fakehost --devices`: a door, a light, a camera, a vending machine, a lift, a sealed quest door and a door a Night City
  person opens and closes every 4 s (`devstate by:npc`) on the replica street's ground, a `ground` column through the guest door (the server must keep the door),
  and every `devact` answered with a `devresult` (and the `devstate by:guest`): ok for the supported acts, `unsupported` for `call`/`buy`/`breach`,
  `quest locked`, `device gone`. `--shift` moves the scene for a second host.
- **Harness.** `tools.mpcheck devices`: the devices stand as blocks (the doors as `#minecraft:doors` or 4B's `cyberblock:device_door` and `cyberblock:device_iron_door`), the guest door ground does not become a replica block,
  a guest's use reaches the owner's DLL and opens, closes and switches in Minecraft, the NPC door follows Night City, a quest door and a lift call change
  nothing, `/cyberblock guests devices door off` blocks only doors, the whole `devices` toggle blocks all, and the devices go with the session. It
  needs 4M's `/cyberblock test device <x> <y> <z> <act>` (run as the guest).
- **Docs.** HOW-TO-PLAY "Devices (Phase 4)" (the table of device types in player words, greyed devices, vending and eddies, the per-type toggles),
  "Re-test: Wave B, Phase 4" in `docs/TESTING-PLAN.md` (to be played in the same session right after Phase 3's), `docs/notes/phase4-descopes.md`.

### Unreleased additions: Phase 3 (multiplayer plan, package 3P)
Phase 3 of the multiplayer plan: **Night City as Minecraft** on a server. This entry is the protocol, tools and docs side; the
mod, plugin and scripts of Phase 3 have their own entries. The in-game list is [docs/TESTING-PLAN.md](docs/TESTING-PLAN.md),
"Re-test: Wave B, Phase 3".
- **Protocol.** `shared/protocol.md` "Phase 3": `ground.m` (one material code 0..26 per range), `unground.seen`, `env` (clock and
  weather), `vteleport`/`vteleported` (Meet Up), `park`/`parked` (relayed by the server from Phase 7), the `maptile` exchange for the
  shared map as 3R and 3C speak it, class and rate rows for the new UP types, validators and examples in `tests/test_protocol_md.py`.
- **Material codes.** `tools.cbmath` (`material_code`, `material_block`, `env_ticks`) and the new `materials` key of
  `shared/vectors.json` (written by `tools.gen_vectors`): the codes (0 unknown, 1 to 10 the surface blocks, 11 to 26 the sixteen
  `nc_neon_<dye>`), the name rules and 38 name cases; the clock formula is the separate top-level `clock` key. The substrings are a working table until Phase 0's
  CET lines B1 to B5 show the real names.
- **Fake host.** `tools.fakehost --replica`: a street of ground with materials, `seen` and plain removals, figures walking and
  driving at real speed (and a car V destroys: `killed`, then gone from the list), `env` steps, `vteleport` answered after 2 s, `park` answered at once; `--shift`
  and `--env-hours` make a second host that overlaps the first and has another sky.
- **Harness.** `tools.mpcheck replica twohosts2 meetup`: the street's replica blocks by material, the `seen` rule, `clear`, the
  wreck of the destroyed car, the clock and weather (26.3's `time query time`), the replica staying when the host leaves, a guest's wall reaching the returning host, two overlapping
  hosts with colliding ids, Meet Up both ways with its cooldown.
- **Docs.** HOW-TO-PLAY "Night City as Minecraft (Phase 3)" (an existing `server.json` keeps `"maxHosts": 1`: set it to 4 by
  hand), `docs/WEBMAP.md` (a BlueMap build for Fabric 26.3 exists; nothing of
  it is tested yet), `docs/notes/phase3-descopes.md`.

### Unreleased additions: Phase 5 (multiplayer plan, packages 5D and 5P)
Phase 5 of the multiplayer plan: **figures and skins**. This entry is the DLL extension, protocol, tools and docs side; the figure
renderer, the cyber skins, the scripts and the art have their own entries. The in-game list is
[docs/TESTING-PLAN.md](docs/TESTING-PLAN.md), "Re-test: Wave B, Phase 5".
- **DLL.** New native `Cyberblock_SubmitNpcs2` (the old `Cyberblock_SubmitNpcs` stays): the `npcs` entries gain `role`, `fac`, `g`,
  `arch`, `anim`, `vc`, `col` and `hp`, with validators and limits. The DLL sets the walk and run bits of `anim` from the position deltas
  between lists (thresholds in `world.hpp`), and converts the car's colour-template triple once, as hue, saturation and brightness until
  Phase 0's CET line F2 confirms it (it logs `npcs: colour format hsb (unconfirmed)`). Runtime-path log lines: `npcs: tags full|faction only`
  (printed by the scripts only, full = gender and archetype) and the DLL's 10-second summary. `shared/vectors.json` gains `colour`, written by `tools.gen_vectors` from `tools.cbmath.colour_hex`.
- **Protocol.** `shared/protocol.md` "Phase 5": the `npcs` row extension, semantics and limits, names, log lines and an example;
  `P5_VALIDATORS` in `tests/test_protocol_md.py`.
- **Fake host.** `tools.fakehost --figures`: 46 tagged figures, one person per skin group (five again in the other gender), every
  animation bit, a victim who dies, coloured parked cars and a hostile police car chasing a car.
- **Harness.** `tools.mpcheck figures`: every tagged figure stands as a proxy, the dead victim goes and stays gone, a hit on a tagged
  figure reaches the host, the figures go with the session, and the relay drops nothing.
- **Docs.** HOW-TO-PLAY "Figures and skins (Phase 5)" and "Your cyber skin (Phase 5)" (`/cyberblock body male|female`, the `[V]` prefix, the
  list marker), and Re-test: Wave B, Phase 5 with the runtime-path lines and fallbacks.

### Unreleased additions: join password
- **Join password for dedicated servers.** `joinPassword` in `config/cyberblock/server.json` (default `""`, off) lets a
  server run without a whitelist: a player who joins is held in place and can do nothing (no building, items, chat, damage
  or Cyberpunk host link) until `/login <password>` (also `/cyberblock login`; Cyberblock clients get a password box).
  One try per second, five wrong tries or 120 s without one kick; verified players are remembered per world (with a salted
  fingerprint of the password, so a new password locks everyone again). A waiting Cyberpunk host becomes a host right after
  the password. `/cyberblock password [reset]` for operators, `join password: on|off` in `/cyberblock status`; the whitelist
  warning is quiet while a password is set. The password is never logged, sent to clients or shown.
- **Wave A leftovers.** Dedicated servers check the world type (`/cyberblock worldcheck on|off`; hosts refused on a world
  that is not `cyberblock:nightcity`) and give guests a spawn platform over the void with the void rescue; `/cyberblock lan
  offline on` (owner only, off at every world start) lets offline guests join a LAN world, the join password still applies
  (an offline name's `/login` lasts for that connection only, never remembered) and the owner's name is refused; wrong `/login` tries survive a reconnect for 10 minutes; server.json
  `creativeErasesReplica: true` forces permanent erase on (`/cyberblock status` shows the source); the DLL hands
  `prefs.vdist` to the scripts, which pick the nearest vehicle camera preset; the collision boxes log `collision: wrap path`
  or use a fallback filter; `shared/vectors.json` gains `prefs_apply` (with `prefs.debug`, which drives `cam.vdbg`).
- **Harness.** `tools.mpcheck joinpassword` checks the lock, the host link after `/login`, a reconnect, the kick after five
  wrong passwords and that the password reaches no log.

### Unreleased additions: Phase Q (multiplayer plan)
Phase Q of the multiplayer plan (`docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md`): quick wins before the server
work, on top of the 1.0.0 work below. Built and unit-tested; the in-game list is
[docs/TESTING-PLAN.md](docs/TESTING-PLAN.md), "Re-test Q". The later Wave A phases (the Phase 0 feasibility notes, the
server/client split and the Apex tools) are the next entry.

#### Added
- **Survival mining.** Placed blocks crack and break at the normal Minecraft speed with a pickaxe, at eye height and at the
  feet: the aim limit has a margin, smoothing and a sticky target, and V on the ground no longer counts as mid-air.
- **Stairs.** Cyberpunk's vault and climb prompts are refused on your placed blocks (real ledges and ladders still work), and
  a **step-up assist** lifts V onto a single block in the way (a setting, on by default).
- **A third-person camera that holds still.** The DLL tests four rays for wall collision, smooths the distance, swings the
  shoulder offset to the centre near walls and takes a distance, shoulder offset, FOV and vehicle distance; Steve is placed per
  rendered frame, his elytra, shield and use-item poses no longer flicker, and the crosshair is drawn where his aim meets the
  target. `/cyberblock debug view on|off` shows a frame time and pose age overlay; `/cyberblock debug aim on|off` logs the
  aim.
- **Outline blocks in creative.** Creative can break the invisible outline blocks of Night City (they return in about 10 s,
  or stay broken with the new setting "Creative can erase the replica permanently", `/cyberblock outline erase on|off`); a
  player who falls through is rescued; survival and adventure keep them unbreakable.
- **The Cyberblock settings screen** (`/cyberblock settings`, a "Cyberblock..." button on both pause screens).
- **A Minecraft-style loading screen** for the loading cover, and `docs/notes/ui-coverage.md`, one table of which Cyberpunk
  screens are Minecraft, pass through, or are converted by which later phase.
- **Protocol.** `prefs` (Minecraft to Cyberpunk) and `cam.vdbg` in `shared/protocol.md` "Phase Q".
- **Tools.** `tools.fakehost --view` (a walking, sprinting and gliding third-person stream) and `--stairs` (a staircase of
  placed blocks with the aim at their faces) reproduce the camera and mining cases without Cyberpunk.
- **Repository hygiene.** `tests/test_hygiene.py` fails the suite when a tracked file carries the account name of whoever runs
  it, a server password, the hosting provider's FTP host names or a drive-letter path in the plan's new files.

#### Changed
- Creative can break the invisible outline blocks of Night City (and they come back) instead of them being untouchable.

### Unreleased additions: Wave A (Phase 0, Phase 1, Phase 2 tools)
The rest of Wave A of the multiplayer plan, merged with Phase Q for one combined in-game test
([docs/TESTING-PLAN.md](docs/TESTING-PLAN.md), "Re-test: Wave A").

#### Added
- **Playing together (LAN), Phase 1.** The Minecraft mod is split into a client relay and a server with one session per
  Cyberpunk host; singleplayer goes through the same path, so opening a world to LAN lets a friend join as a guest. A guest's
  TNT, explosions, hits and blocks reach the host's Cyberpunk game; the host switches what guests may do with
  `/cyberblock guests <explosions|damage|hacks|devices|spawns> on|off`. Perks and implants are per player (a guest's Gorilla
  Arms and demolitions are the guest's own). A version handshake with clear messages, a reconnect that cleans Night City and
  brings the ground back, and per-type rate limits. Wire contract: `shared/protocol.md` "Multiplayer (Phase 1)" (new
  `hstate`, `session`, `survey_cmd`, the DLL's `hello.ver` and the documented `pteleport`).
- **Content as data.** `tools/content_check.py` validates content files (`data/<namespace>/cyberblock/<kind>/<name>.json`) against
  the JSON Schemas in `shared/content-schemas/`.
- **Multiplayer test tools.** `tools/mpcheck.py` runs a dedicated test server, offline clients and fake hosts through the
  scenarios singleplayer, guest, versions, flood, reconnect and twohosts (`--guest <port>` starts one test guest);
  `tools/fakehost.py` gains `--name`, `--ver`, `--ids-from` and `--wait-host`; `server/` holds the test server templates.
- **Dedicated-server admin (Phase 2).** `/cyberblock status`, `/cyberblock hosts add|remove <name>`, a startup line, a
  whitelist warning and the server's `joinHelp` on every refusal.
- **Server and modpack tools (Phase 2).** `tools.server_sync` (FTP backup with a manifest, verify, a dry-run-first upload of
  staged files with an overwrite allowlist and no delete; the password never printed or stored), `tools.server_bundle`,
  `tools.modpack` (a Modrinth `.mrpack`), `tools.respack` (the resource pack and its SHA-1), and a **Join Cyberblock** title
  button when the modpack carries a server.
- **Phase 0 feasibility notes.** `docs/notes/phase0-feasibility.md`: what Cyberpunk 2.31 allows for the later phases
  (raycast materials, devices, spawns, heat and attitude, NPC tags, far teleport and parking, remote hacks), the fallbacks,
  and the CET lines of the combined test; the plan's contracts in sections 1.8 and 1.9 are narrowed to match.

#### Changed
- Online play is now a goal of the design (spec section 1.1); the spec's non-goal "online play of any kind" is reversed.
- Steve's fall grace around a glide or launch is kept by the server per host (from the client's `hstate`).

### Added
- **Layer 1, the passthrough.** Real Minecraft Java 26.3 (Fabric mod) is drawn into Cyberpunk 2077 2.31 every frame through a
  ReShade add-on, with Cyberpunk's camera, depth and a Night City collision map (barrier blocks) around V. F9 toggles it;
  V is hidden and invincible, Steve's health is the only health.
- **Layer 2, building and explosions.** Placed blocks are collision boxes in Night City; TNT, creepers and fireworks become
  Cyberpunk explosions; weapons, arrows and fire hurt NPCs and vehicles through invisible proxies.
- **Layer 3, mobs against Night City.** Hostile mobs attack pedestrians; NPCs and NCPD shoot at puppets standing in for the mobs.
- **Layer 4, movement.** Elytra glides and wind-charge or mace launches move Steve with Minecraft physics while the DLL moves V
  every rendered frame; ender pearls teleport V.
- **Layer 5, interactive items.** Leads, fishing rods, wind charges, shields and potions that act on V.
- **Layer 6, Cyberpunk in Minecraft form.** The hack key, RAM, ten quickhack chips, a 20-perk skill tree and the starter kit.
- **Cyberware.** 17 implants in ripperdoc slots with a Cyberware screen (U): Mantis Blades, Gorilla Arms, Monowire, Projectile
  Launch System, Reinforced Tendons, Fortified Ankles, Lynx Paws, Sandevistan, Berserk, Cyberdeck, Kerenzikov, Optical Camo,
  Subdermal Armor, Kiroshi Optics, Biomonitor, Second Heart and the RAM Upgrade.
- **Re-test 3, interface.** Minecraft draws every piece of Cyberpunk's interface: radar, eddies, wanted level, quest and
  waypoint, interaction prompts, dialogue choices, subtitles, toasts, phone calls, journal, phone, atlas, inventory, skills and
  the Night City pause screen. Cyberpunk's own layers stay invisible while Cyberblock is on. Menus that cannot be replaced
  (shops, ripperdoc, stash, crafting, breach protocol, settings, saves, death, loading) are restyled in Minecraft colours with
  a Minecraft frame. A third-person view on F5.
- **Re-test 3, quickhacks.** The chip in your hand fires; every chip has its own Minecraft effect (lightning, flames, ink, real
  cobwebs, poison cloud, wither trail, outlines through walls, swirls, a creeper face, per-device effects) played when the
  upload completes, and a distinct Cyberpunk effect, with visible extras on crowd pedestrians. New wire messages `hackspread`
  and the `upload` and `kind` of `hackresult`.
- **Re-test 3, vehicles.** Steve rides along: his hand in a first-person vehicle camera, the whole seated Steve in a
  third-person one (the hotbar, survival bars and crosshair are hidden in a vehicle).
- **Re-test 3, cyberware.** Separate keys: Y for the Operating System implant, L for the Arms implant, O for Optical Camo
  (six rebindable keys, clash-free); two Operating System places (a Cyberdeck and Sandevistan or Berserk together); active
  abilities for Mantis Blades (lunge), Gorilla Arms (slam) and Monowire (lash); original 3D models for the arm implants in
  first and third person; a Sandevistan screen effect in ReShade with a strength slider; Steve's cyber look and an optical
  camo shimmer; advancements.
- **Release 1.0.0.** A release zip with an installer (`install.bat`, `uninstall.bat`, `install.ps1`), a launcher install
  with a "Play Cyberblock" button, GitHub Actions CI, this changelog, licence and third-party notices.
- **Tools.** `tools.fakehost` modes `--ui`, `--hacks` and `--seat`; `tools.package` builds the release zip.

### Changed
- Cyberpunk's HUD no longer comes back for prompts, dialogues or vehicles: Minecraft draws them.
- Melee, sprint, gauntlet and Gorilla Arms hits push NPCs like Minecraft does (stagger, knockdown and a short ragdoll only on
  strong hits) instead of flinging everyone.
- The glide steers with the camera like the vanilla elytra (the DLL owns the glide's yaw).
- Implants no longer share one activation key or a tap-and-hold selection.

### Fixed
- Re-test 3: every hack looked the same because the hack wheel preferred its remembered pick over the chip in the hand.
- Re-test 2: the elytra teleported and updated once a second (V is now moved every frame); hacks said "target lost" because of
  a wrong entity id; blocks floated a block above the street (a per-world ground alignment, `/cyberblock level`); a wind charge
  could not launch Steve; perks can be free (`/cyberblock perks free on`).
- Re-test 1: the elytra gate treated open-world V as in a scene; the NPC list was empty; key clashes between Cyberpunk and
  Minecraft; a "standing on air" log spam; typing in Minecraft screens; car hacks and scans.

## Planned
- A ripperdoc flow with eddy costs for implants (needs verified money and transaction plumbing).
- A cyberware capacity budget.
- Gamepad steering of the elytra glide.
- Screenshots in the README and the releases page.
