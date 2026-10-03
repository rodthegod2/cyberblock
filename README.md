# Cyberblock

Cyberblock lets you play Cyberpunk 2077 as a Minecraft player: real Minecraft Java (26.3 + a Fabric mod) runs
next to Cyberpunk 2077 (2.31) and is drawn into Night City every frame with the correct camera, depth and
Night City collision, while Cyberpunk moves the body and Steve acts. It is a passthrough in the style of
universal-modder's Minecraft x GTA V example (see `NOTICE.md`).

## Prerequisites
- Cyberpunk 2077 2.31 with RED4ext, redscript, Codeware and Cyber Engine Tweaks.
- ReShade 6.x **with add-on support**.
- JDK 25, Visual Studio 2022 Build Tools (C++ x64 + Windows SDK), CMake, Python 3.12 + uv.

See `docs/superpowers/specs/2026-10-02-minecraft-cyberpunk-passthrough-design.md` (section 2) for details.

## Use
**Player guide: [`docs/HOW-TO-PLAY.md`](docs/HOW-TO-PLAY.md)**. It covers setup, ReShade settings, every key, every
mechanic, quickhacks and RAM, the skill tree, game modes and the kit, what Cyberpunk stops doing, troubleshooting and
uninstalling.
```
cmake -S cp/plugin -B cp/plugin/build -G "Visual Studio 17 2022" -A x64
cmake --build cp/plugin/build --config Release   # build Cyberblock.dll
uv run tools/install.py           # install the plugin, scripts and shader into the game (Cyberpunk closed)
uv run tools/play.py              # start the Minecraft dev client, then Cyberpunk 2077
uv run tools/install.py --remove  # remove exactly what was installed
```
F9 toggles Cyberblock in game. Turn frame generation off in Cyberpunk.

Status: layer 1 is verified in game. Layers 2 to 6 are implemented and pass every automated suite; their in-game
acceptance list is `docs/superpowers/plans/2026-10-03-layers3-6-complete-mod.md`, section I.2.

## What you can do
Cyberpunk moves V, Steve acts. Everything below works on Night City's real NPCs, vehicles and devices.

- **Layer 1, passthrough:** Minecraft is drawn into Night City with hand and hotbar; placed blocks sit on Night City's
  floors and are hidden behind its walls; Night City's floors and walls are solid for Steve, arrows and mobs.
- **Layer 2, building and explosions:** blocks you place stop pedestrians and cars. TNT, creepers and fireworks explode
  as Cyberpunk explosions. Swords, arrows, tridents and crossbows hurt NPCs and vehicles (ragdolls, shoves, NCPD heat);
  flint and steel, fire charges and lava set them alight.
- **Layer 3, mobs vs Night City:** hostile mobs attack pedestrians as well as Steve. NPCs and NCPD shoot back at up to
  12 nearest mobs through invisible stand-in puppets; the damage they deal lands on the mob. Fallback if puppets
  misbehave: `CyberblockPuppets.SetEnabled(false)`.
- **Layer 4, movement:** open an elytra in free fall (Space) and glide over Night City with firework boosts; touching
  down gives control back to Cyberpunk movement. An ender pearl teleports V to where it lands. Fallback if the
  per-frame body moves jitter: `CyberblockFlight.SetCameraOnly(true)`.
- **Layer 5, interactive items:**
  - a lead tethers a pedestrian (5 m) or a car (8 m) to Steve and drags it back when it strays; a fishing rod reels one in;
  - a wind charge knocks back everything around the burst;
  - a raised shield facing a shooter blocks the bullets;
  - potions map to Cyberpunk effects: invisibility is optical camo, speed, slowness and jump boost change how V moves,
    nausea and blindness hit the screen. Damage-over-time potions (poison, wither) are never mapped.
- **Layer 6, Cyberpunk in Minecraft form:**
  - hold the hack key to scan: the target gets an outline and a tooltip with name, faction, threat and health;
  - a RAM bar left of the hotbar (it needs the cyberdeck in your inventory);
  - quickhack items fire the real Cyberpunk quickhack at the scanned target for RAM:

    | Item | Quickhack | RAM |
    |---|---|---|
    | Redstone Charge | Short Circuit | 3 |
    | Blaze Chip | Overheat | 4 |
    | Ink Sac Daemon | Reboot Optics | 3 |
    | Cobweb Daemon | Cripple Movement | 2 |
    | Spider Eye Virus | Contagion | 4 |
    | Wither Skull Daemon | Synapse Burnout | 6 |
    | Compass Ping | Ping | 1 |
    | Bottle of Forgetting | Memory Wipe | 3 |
    | Creeper Brain | Cyberpsychosis | 8 |
    | Redstone Lever | device hack (traffic lights, cameras, vending machines) | 1 |

  - a skill tree with 20 perks in five branches (Body, Reflexes, Technical, Intelligence, Cool), four each, bought with
    XP levels (3, 5, 8, 12 per tier), each with a Minecraft and a Cyberpunk effect; NPCs you kill drop XP;
  - `/cyberblock kit` gives the starter kit (cyberdeck, RAM shards, every quickhack item, blocks, weapons, elytra,
    rockets, potions, spawn eggs).

## Keys
Cyberpunk keeps focus: it keeps the movement keys and the camera; Cyberblock forwards the rest to Minecraft and stops
Cyberpunk from acting on it. The full conflict-free table, with the dialogue and screen rules, is in
[docs/HOW-TO-PLAY.md](docs/HOW-TO-PLAY.md#3-controls).

| Key | What it does |
|---|---|
| F9 | Toggle Cyberblock on and off, in every state; Cyberpunk's QuickLoad on F9 never fires (the watchdog also turns Cyberblock off if Minecraft goes away) |
| W A S D, mouse look, C / RCtrl | Cyberpunk moves V; Steve crouches when V crouches (Optical Camo needs it) |
| Space | V jumps as in Cyberpunk. Pressed while V falls with at least 3 blocks of air below and an elytra on, it opens the elytra (with the Double Jump perk: the 2nd press in the air); a fall faster than 12 m/s for 0.3 s opens it on its own |
| Left or Right Shift | V sprints as in Cyberpunk. Double-tap either within 300 ms with the Sandevistan perk for Sandevistan Sprint |
| LMB, RMB, MMB, wheel, 1-9, E, Q, T, /, X | Minecraft: attack, use, pick, hotbar, inventory, drop (Ctrl+Q: stack), chat, command, swap to offhand. In a Cyberpunk dialogue or interaction list with 2+ choices, E, Q, 1-4 and the wheel go to Cyberpunk |
| LCtrl, Mouse 4, Mouse 5, F, Tab, Alt and Cyberpunk's other keys | Cyberpunk's own (F: interact, talk, exit car) |
| F5 | Ignored while Active; it no longer quick-saves in Cyberpunk |
| G (hold) | Hack key: scan and lock the target for 5 s; while held, the wheel picks a quickhack chip and right-click fires it |
| K | Skill tree |

G and K are the defaults. Rebind them in Minecraft's Controls (category Cyberblock) to G K L O U Y 0 F1-F4 F6-F8 or
F10-F12; any other key, or both actions on one key, falls back to G and K in Cyberpunk, and a chat message says so.
While a Minecraft screen is open (inventory, chat, skill tree) every key but F9 goes to it, typing included.

While Cyberblock is active on foot, Cyberpunk's own guns, cyberware, scanner, quickhacks, healing, radial menus, quick
melee, zoom, crafting (K, while it is the skill-tree key), walk toggle (G) and quick save (F5) are off, and V has no dodge, air dash or double jump
until you buy the perk. In a vehicle the Cyberpunk HUD and controls return.

## Survival and creative
Steve's health is the only health. While Cyberblock is on, V is invincible on foot and in vehicles, and every hit V
would take (bullets, explosions, melee, falls, vehicles, fire, damage over time, instant kills) becomes damage to Steve,
scaled by V's maximum health. Cyberpunk never shows a game over.

- **Survival** (default): Steve can die and respawns the Minecraft way. The first activation in survival gives the
  starter kit once, with the elytra on Steve's chest if that slot is empty. Hacks cost RAM, perks cost XP levels.
  Cyberpunk's fall damage is ignored under an open elytra, for 2 s after touchdown or a pearl teleport, and with Slow
  Falling; vanilla elytra rules (durability, kinetic damage) still apply.
- **Creative** (`/gamemode creative`): Steve and V are both invincible, also in vehicles.
  The kit is not handed out on activation; `/cyberblock kit` still works.
- In a vehicle, in every game mode, V stays invincible and the hits on V or V's car go to Steve.

## Development
```
uv run pytest -q                                              # protocol, tools and fake-host tests
cd mc && gradlew.bat test build                               # Minecraft mod (JAVA_HOME = JDK 25)
cmake --build cp/plugin/build --config Release                # then cp\plugin\build\Release\cb_tests.exe
uv run python cp/scripts/compile_check.py                     # redscript against the real 2.31 scripts
uv run python -m tools.gen_vectors                            # regenerate shared/vectors.json (no diff expected)
uv run python -m tools.fakehost --layer3 --layer4 --layer6    # fake Cyberpunk for a running Minecraft client
```
`tools.fakehost` plays Cyberpunk's part of layers 2 to 6 (`--layer2` .. `--layer6`, combinable, `--port` for a client
on another port, `--cmd` for server commands) and prints what Minecraft reports. The wire format is
`shared/protocol.md`. The untracked `net/` folder holds decompiled Minecraft source for reference; it is git-ignored and
must never be committed.
