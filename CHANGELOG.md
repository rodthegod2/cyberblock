# Changelog

All notable changes to Cyberblock are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). One version number covers the DLL, the scripts, the shader, the Minecraft mod
and the tools.

## [1.0.0] - unreleased
Version 1.0.0 is set in the files on 2026-10-03; it is tagged and published only after the in-game re-test
([docs/TESTING-PLAN.md](docs/TESTING-PLAN.md), "Re-test 3") has passed.

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
