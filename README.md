# Cyberblock

> **Status: experimental work in progress, not a finished mod.** It is under active development, large parts have
> barely been played in game yet, and you should expect bugs, rough edges and breaking changes. The version number is
> an internal milestone, not a "done" release.

**Play Cyberpunk 2077 as a Minecraft player.** Real Minecraft Java (26.3 with a Fabric mod) runs next to Cyberpunk 2077
(2.31) and is drawn into Night City every frame, with the correct camera, depth and Night City collision. Cyberpunk moves V,
Steve acts, and **Minecraft draws the whole interface**: radar, quest and waypoint, prompts, dialogue choices, phone,
journal, map and pause are all Minecraft-styled, while Cyberpunk's own HUD stays hidden. Your blocks stop pedestrians and
cars, TNT blows up Night City, mobs fight NCPD, you glide over megabuildings with an elytra, hack gangers with quickhack
chips, and install cyberware (Mantis Blades, Sandevistan, Cyberdeck and more) in ripperdoc slots.

<!-- screenshot: docs/media/hero.png - Steve's hand and hotbar in a Night City street with the Minecraft radar and quest panel -->
<!-- screenshot: docs/media/quickhacks.png - a quickhack upload beam and the impact on a ganger -->
<!-- screenshot: docs/media/ui.png - the Minecraft-style dialogue choices and interaction prompt -->
<!-- screenshot: docs/media/sandevistan.png - the Sandevistan screen effect -->
<!-- screenshot: docs/media/vehicle-tpp.png - Steve seated in a third-person vehicle camera -->
*Screenshots pending: they will be added under `docs/media/` before the first release.*

> **Not affiliated.** Cyberblock is an unofficial fan project. It is **not affiliated with, endorsed by or connected to
> CD PROJEKT RED or Mojang Studios / Microsoft**. Cyberpunk 2077 is a trademark of CD PROJEKT S.A.; Minecraft is a trademark
> of Mojang Synergies AB. **You need your own legal copies of Cyberpunk 2077 and Minecraft: Java Edition.** No game files,
> assets, fonts or code from either game are included or redistributed. See [NOTICE.md](NOTICE.md).

Status: version 2.0.0 is set in the files (proposed: the wire grew a server; it is tagged and published only after the in-game
tests pass and the owner says so). Layer 1 (the passthrough) has been verified in game; everything else, the whole multiplayer plan
included, passes every automated suite and is being re-played in game (the checklists are in
[docs/TESTING-PLAN.md](docs/TESTING-PLAN.md): "Full multiplayer pass" and "Re-test 9" first). What 2.0.0 adds over a single-player mod is
**Cyberblock as a multiplayer game**: a dedicated server, friends who join with Minecraft only, Night City as a shared replica,
devices, figures, hacking, the Afterlife hub, game modes, content as datapacks, a settings screen, Performance mode and daily
server backups; the release notes are the top entry of [CHANGELOG.md](CHANGELOG.md).

## Play together
Three guides, one for each person involved:
- **[docs/JOIN.md](docs/JOIN.md): joining a server** (for friends): import the modpack, press **Join Cyberblock**, play with Minecraft only.
- **[docs/SERVER.md](docs/SERVER.md): running a server** (for the owner): the setup step by step, who may join (whitelist or join
  password), the configuration file, backups, updating and the way back.
- **[docs/CONTENT.md](docs/CONTENT.md): adding your own content** (for anyone): dialogue lines, skins, shops, landmarks, Breach
  daemons and Overlord events as a datapack, no code.

The player guide for everything in the game is [docs/HOW-TO-PLAY.md](docs/HOW-TO-PLAY.md); its
[playing together](docs/HOW-TO-PLAY.md#playing-together-lan) section covers a LAN world too.

## Install (players)
You need a Windows PC that can run both games at once (about 64 GB of RAM, see
[the guide](docs/HOW-TO-PLAY.md#memory-and-the-page-file)).

**Requirements:**
- [Cyberpunk 2077](https://store.steampowered.com/app/1091500/Cyberpunk_2077/) 2.31 on Steam (Phantom Liberty is fine) with
  - [RED4ext](https://github.com/WopsS/RED4ext), which loads the plugin,
  - [redscript](https://github.com/jac3km4/redscript), which compiles the scripts,
  - [Codeware](https://github.com/psiberx/cp2077-codeware),
  - optionally [Cyber Engine Tweaks](https://github.com/maximegmd/CyberEngineTweaks) (only for its console fallbacks);
- [ReShade](https://reshade.me) 6.x **with add-on support**, installed for `Cyberpunk2077.exe` with the DirectX 10/11/12 API;
- [Minecraft: Java Edition](https://www.minecraft.net/) 26.3 through the official launcher, the
  [Fabric Installer](https://fabricmc.net/use/installer/) (loader 0.19.5 or newer) and
  [Fabric API](https://modrinth.com/mod/fabric-api) 0.161.0 or newer.

**Steps** (the full version, with every setting, is in [docs/HOW-TO-PLAY.md](docs/HOW-TO-PLAY.md#1-setup)):
1. Download `Cyberblock-2.0.0.zip` from the releases page and unzip it.
2. Close Cyberpunk and double-click **`install.bat`**. It finds your game, backs up your saves once, never overwrites a file
   it did not write, and keeps your own ReShade preset. **`uninstall.bat`** removes exactly what it installed.
3. Run the Fabric Installer for Minecraft 26.3 to create a launcher installation. Edit it in the Minecraft Launcher: game
   directory `%APPDATA%\.minecraft-cyberblock`, JVM arguments **replaced** with
   `-Xmx2G -XX:+UseZGC --enable-native-access=ALL-UNNAMED -Dcyberblock.autostart=true`. Copy `minecraft\mods\cyberblock-2.0.0.jar`
   and the Fabric API jar into `%APPDATA%\.minecraft-cyberblock\mods`.
4. Start that installation (the title screen has a **Play Cyberblock** button), leave the window open behind Cyberpunk,
   start Cyberpunk, load a save and press **F9**. Turn frame generation off in Cyberpunk's graphics settings.

## Keys
Cyberpunk keeps focus, the movement keys and the camera; Cyberblock forwards the rest to Minecraft. The full conflict-free
table is in [docs/HOW-TO-PLAY.md](docs/HOW-TO-PLAY.md#3-controls).

| Key | What it does |
|---|---|
| F9 | Toggle Cyberblock on and off, in every state |
| W A S D, mouse, Space, Shift, C | Cyberpunk moves V (Space opens the elytra in free fall; the glide steers with the mouse) |
| LMB, RMB, MMB, wheel, 1-9, E, Q, T, / | Minecraft: attack, use, pick, hotbar, inventory, drop, chat, command (E, Q, 1-4 and the wheel go to Cyberpunk in dialogues; **T** answers a ringing phone) |
| **G** (hold) | Hack: scan and lock a target; the wheel picks a chip, right-click fires it (the chip in your hand wins) |
| **K** / **U** | Skill tree / Cyberware screen |
| **Y** / **L** / **O** | Fire the Operating System implant (Sandevistan, Berserk) / the Arms implant / Optical Camo |
| **F5** | Third person |
| Esc, M, J, I, P, H | Open Minecraft's pause, atlas, journal, inventory, skill and pause screens instead of Cyberpunk's menus |
| F | Interact, talk, choose (Cyberpunk's own; the prompt is drawn by Minecraft) |

The six Cyberblock keys (G K U Y L O) can be rebound in Minecraft's Controls (category Cyberblock) to G K L O U Y 0 F1-F4
F6-F8 F10-F12; a refused key falls back to its own default, and a clash resets all six.

## What you can do
Everything works on Night City's real NPCs, vehicles and devices.
- **Passthrough:** Minecraft is drawn into Night City with hand, hotbar and depth; blocks sit on the street and are hidden
  behind walls; Night City is solid for Steve, arrows and mobs.
- **Building and explosions:** blocks stop pedestrians and cars; TNT, creepers and fireworks explode as Cyberpunk
  explosions; weapons hurt people and cars with vanilla-like knockback; fire spreads to NPCs.
- **Mobs against Night City:** zombies and skeletons attack pedestrians; NPCs and NCPD shoot back.
- **Movement:** an elytra glide with firework boosts that steers with the camera, wind-charge launches, ender pearls, a
  third-person view (F5) and Steve riding along in vehicles.
- **Items:** leads, fishing rods, wind charges, shields and potions work on NPCs and cars.
- **Netrunning:** a RAM bar, a Cyberdeck implant and ten quickhack chips; each chip has its own Minecraft effect (lightning,
  flames, ink, real cobwebs, a poison cloud, a wither trail, outlines through walls, a creeper face) and its own Cyberpunk
  quickhack.
- **Cyberware:** 17 implants in ripperdoc slots, including two Operating System places (a deck and a Sandevistan together),
  arm implants with 3D models and abilities (lunge, slam, lash), and a Sandevistan screen effect.
- **A Minecraft interface for Night City:** radar, eddies, wanted stars, quest and waypoint, prompts, dialogue choices,
  subtitles, toasts, phone calls, journal, atlas and the pause screen. Menus that cannot be replaced (shops, ripperdoc,
  settings and similar) are restyled in Minecraft colours and framed.
- **The Atlas:** Night City as a Minecraft filled map, measured from the game's own collision as you move (no Cyberpunk map
  art), with every map pin; `/cyberblock survey` maps the whole city once (15 minutes at most), for every world and save
  ([details](docs/HOW-TO-PLAY.md#the-atlas-map)).
- A 20-perk skill tree, a starter kit, free perks in creative (or `/cyberblock perks free on`) and `/cyberblock level`.
- **Playing together (LAN):** open your world to LAN and a friend with only Minecraft (and the Cyberblock mod) joins as a
  guest: their TNT, hits and blocks reach your Cyberpunk game, and you decide what they may do with
  `/cyberblock guests` ([details](docs/HOW-TO-PLAY.md#playing-together-lan)). A dedicated server runs the same world for friends
  ([running one](docs/SERVER.md), [joining one](docs/JOIN.md)).
- **Night City as Minecraft (Phase 3, on a server):** guests see the streets the hosts explored as solid, coloured replica
  blocks (neon glows) that are saved in the world, the people and cars as figures at real speed, Cyberpunk's time of day and
  weather, can build while the host is away, and `/cyberblock meetup <player>` takes you to a friend
  ([details](docs/HOW-TO-PLAY.md#night-city-as-minecraft-phase-3); a [web map](docs/WEBMAP.md) is possible with BlueMap).
- **Devices (Phase 4, on a server):** Night City's doors, gates, lights, cameras, terminals, vending machines, traffic lights,
  lifts, turrets and screens stand in Minecraft as blocks a guest can use (a door opens in the host's game, a pedestrian's door opens
  in Minecraft); vending machines sell for eddies; hosts switch each kind off with `/cyberblock guests devices <type> off`
  ([details](docs/HOW-TO-PLAY.md#devices-phase-4)).
- **Figures and skins (Phase 5):** guests see Night City's people in their faction's skin (women on the slim model), posed and
  moving like the real ones, cars in their colours, and every linked player as V (or Lucy with `/cyberblock body female`) with a `[V]` tag
  ([details](docs/HOW-TO-PLAY.md#figures-and-skins-phase-5)).
- **Redstone hacking and the interaction map (Phase 6):** Device Relays, Proximity and Heat Sensors, Breach Ports, Charges and the
  Cyberdeck Link hack Night City with redstone; the Breach Protocol on a Net Port runs real daemons in the host's game; the Vehicle Panel, the
  Traffic Tool, the Net Map (blackouts, camera feeds, the host's photo feed) and the Comms Panel cover the rest of the interaction map
  ([redstone](docs/HOW-TO-PLAY.md#redstone-hacking-phase-6), [Breach](docs/HOW-TO-PLAY.md#breach-protocol-phase-6),
  [the map](docs/HOW-TO-PLAY.md#the-interaction-map-phase-6)).
- **Talking to Night City, the Afterlife hub and the Holo-Table (Phase 7):** right-click a ripperdoc, vendor, fixer, bartender, medic or mechanic for a
  Minecraft screen; `/cyberblock hub` takes you to the Afterlife (full-screen Minecraft, with V parked for linked players), where the Holo-Table shows a live 3D
  model of the replica with every player on it and spawns you anywhere; hosts also get Cyberpunk's own shops as Minecraft screens
  ([talking](docs/HOW-TO-PLAY.md#talking-to-night-city-phase-7), [hub](docs/HOW-TO-PLAY.md#the-afterlife-hub-phase-7),
  [Holo-Table](docs/HOW-TO-PLAY.md#the-holo-table-phase-7)).
- **Modes: the Van, the Overlord, matches and more (Phase 8):** a guest runs the host's hacks from a **Van** with flyable drones, an **Overlord** spawns enemies with a
  fair warning, Cyberpunk-linked players make **matches** at the Match Terminal (PvP only when both opt in, Gang Wars), chips work on **players** with a Breach to
  block them, one **Server Heat** meter brings the NCPD and Max-Tac, you go **down** and get revived (or call the Trauma Team) instead of dying, and spectators get a
  caster overlay ([details](docs/HOW-TO-PLAY.md#modes-the-van-the-overlord-matches-and-more-phase-8)).
- **The finish (Phase 9):** every kind of content is data a friend adds as a datapack ([CONTENT.md](docs/CONTENT.md)), one **settings screen**
  with a tab each for guests, look, PvP, difficulty, performance and the world
  ([details](docs/HOW-TO-PLAY.md#the-cyberblock-settings-screen)), **Performance mode** for weaker PCs
  ([details](docs/HOW-TO-PLAY.md#performance-mode)), **daily server backups** you can download
  ([SERVER.md](docs/SERVER.md)) and Night City's **save list as a Minecraft screen** (Esc, Saves...).

## Build from source
```
git clone --recurse-submodules <this repository>
cmake -S cp/plugin -B cp/plugin/build -G "Visual Studio 17 2022" -A x64
cmake --build cp/plugin/build --config Release   # Cyberblock.dll
uv run tools/install.py                          # install into the game (Cyberpunk closed)
uv run tools/play.py                             # start the Minecraft dev client, then Cyberpunk
uv run python -m tools.package                   # build dist/Cyberblock-2.0.0.zip
```
Requirements: JDK 25, Visual Studio 2022 Build Tools (C++ x64 + Windows SDK), CMake, Python 3.12 + [uv](https://docs.astral.sh/uv/),
git. The repository uses submodules (`third_party/`); the [spec](docs/superpowers/specs/2026-10-02-minecraft-cyberpunk-passthrough-design.md)
describes the design.

```
uv run pytest -q                                              # protocol, tools, installer and packaging tests
cd mc && gradlew.bat test build                               # Minecraft mod (JAVA_HOME = JDK 25)
cmake --build cp/plugin/build --config Release                # then cp\plugin\build\Release\cb_tests.exe
uv run python cp/scripts/compile_check.py                     # redscript against the installed game's scripts (a local release gate)
uv run python -m tools.gen_vectors                            # regenerate shared/vectors.json (no diff expected)
uv run python -m tools.fakehost --ui --hacks --seat           # fake Cyberpunk for a running Minecraft client
```
`tools.fakehost` plays Cyberpunk's part (`--layer2` .. `--layer6`, `--ui`, `--hacks`, `--seat`, combinable, `--port` for a
client on another port, `--cmd` for server commands) and prints what Minecraft reports. The wire format is
[shared/protocol.md](shared/protocol.md). Never commit unpacked Minecraft or Cyberpunk sources or assets: the root
`.gitignore` guards `/net/`, `/com/` and `/META-INF/`, and all art is generated by `art/gen_item_art.py` or hand-authored.

## Credits
Based on [universal-modder](https://github.com/rehan-remade/universal-modder) by Rehan (MIT), whose Minecraft x GTA V
passthrough example is the starting point of the mod, the compositor and the shader. Built with RED4ext.SDK, ReShade's
add-on API, nlohmann/json and Java-WebSocket. Needs RED4ext, redscript, Codeware, ReShade, Fabric Loader and Fabric API at
runtime. Every credit and its licence text is in [NOTICE.md](NOTICE.md) and
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## License
Cyberblock's own code is released under the [MIT License](LICENSE). The third-party components keep their own licences
([THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)). Minecraft and Cyberpunk 2077 are not part of this project and keep
theirs.
