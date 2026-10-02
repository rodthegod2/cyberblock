# Cyberblock: Minecraft × Cyberpunk 2077 passthrough — design

Date: 2026-10-02 · Status: approved in brainstorming, awaiting spec review

## 1. What we are building

Play Cyberpunk 2077 as a Minecraft player. Real Minecraft Java runs next to Cyberpunk and is drawn into
Night City every frame; Minecraft's mechanics (building, TNT, mobs, elytra, items, game modes) act on
Night City through real Cyberpunk effects (explosions, damage, collision, teleports, quickhacks).

### Decisions (from brainstorming)
| Question | Decision |
|---|---|
| Approach | **True passthrough**: real Minecraft + Fabric mod, composited into Cyberpunk (like the viral videos and the universal-modder Minecraft × GTA V example). |
| Who moves the body | **Cyberpunk moves, Steve acts.** Cyberpunk's movement on foot; Minecraft physics only during elytra flight. |
| Cyberpunk combat on foot | **Minecraft only.** Cyberpunk guns, cyberware and hacking are disabled while the mashup is on. **Cars stay**: entering a vehicle hides Steve and restores the Cyberpunk HUD. |
| Game mode | **Both, switchable** with Minecraft's `/gamemode`. Survival spawns with a starter kit (`/cyberblock kit` refills); creative makes both Steve and V invincible. |
| Priorities | Building + TNT/creepers → mobs vs Night City → elytra + ender pearls → interactive items (lead/lasso, fishing rod, …) → Cyberpunk-as-Minecraft (quickhack items, hack key, skill tree). Nether and day/night sync are out of scope. |

### Non-goals
- Online play of any kind. Cyberpunk is single-player; the Minecraft world is a local singleplayer world.
- Replacing Cyberpunk's movement with Minecraft's on foot.
- Copying or redistributing game files: no Minecraft or Cyberpunk assets in this repo. Item textures are
  original 16×16 pixel art.
- VR, frame generation.

## 2. Environment (measured 2026-10-02)
- Cyberpunk 2077 **2.31** + Phantom Liberty, Steam, `D:\SteamLibrary\steamapps\common\Cyberpunk 2077`.
  Already installed: Cyber Engine Tweaks, RED4ext, redscript, Codeware, ArchiveXL, TweakXL, plus ~40 content
  mods (Nova City 2, vehicles, Virtual Atelier, …) that must keep working.
- Minecraft Java launcher with Fabric (1.21.11 profile) and 26.x versions.
- RTX 5090, Ryzen 9 9950X3D, 63 GB RAM, one 2560×1440 display. C: 63 GB free, D: 325 GB free.
- Present: git, gh, Python 3.12, uv, node, ffmpeg.
- **Missing, to install with the user's approval:** JDK 25, Visual Studio 2022 Build Tools (C++ x64 +
  Windows SDK), CMake, ReShade 6.x **with add-on support** (official installer from reshade.me),
  RED4ext.SDK (git submodule, used only at build time).

## 3. Architecture

```
CYBERPUNK 2077 (DX12, has focus)                     MINECRAFT JAVA 26.3 + Fabric (window behind)
 Cyberblock.dll (red4ext/plugins/Cyberblock/)           cyberblock mod (dev.cyberblock)
  ├ link: WebSocket client ── 127.0.0.1:25600 ───────►  HostLink: cam · player · probes · npcs · input · dmg
  │                        ◄──────────────────────────   events: explosion · hit · block± · teleport · …
  ├ redscript natives: Cyberblock.Poll/Send/Probe/…
  └ ReShade add-on: compositor ◄── shared memory ──────  FrameExporter: world RGBA+depth, overlay RGBA, pose
       + Cyberblock.fx (reproject, depth test, relight)
 r6/scripts/Cyberblock/*.reds (gameplay glue)           world "cyberblock": void, dimension Y −512…1535,
                                                        Night City collision = managed barrier blocks
```

### 3.1 Units
| Unit | Language | Responsibility | Depends on |
|---|---|---|---|
| `mc/` Fabric mod | Java 25 | Render from the host camera; export frames; turn probes into barrier blocks; turn NPCs into proxy entities; emit events; custom items, keys, screens. Forked from universal-modder's `examples/minecraft-gta5-passthrough/mc` (MIT, credited). | Fabric API, Java-WebSocket |
| `cp/plugin/` Cyberblock.dll | C++20 (MSVC) | WebSocket client + message queue; per-frame camera publish; redscript natives; ReShade add-on registration and compositor (ported from the GTA example's `compositor.cpp`/`ws.cpp`). | RED4ext.SDK, ReShade add-on headers |
| `cp/shaders/Cyberblock.fx` | ReShade FX | Reprojection, depth test, relight, overlay. | ReShade |
| `cp/scripts/Cyberblock/` | redscript | Mashup state (on/off, V hidden, combat blocked, HUD swap, god mode); raycast probes; collision boxes; applying events as Cyberpunk effects; NPC list; quickhack and perk effects. | Codeware, RED4ext natives |
| `tools/` | Python | `install.py` (install/remove exactly our files, refuse to overwrite others, back up saves first), `play.py` (start Minecraft dev client then Cyberpunk), `fakehost.py` (oracle), `check_vectors.py`. | — |
| `shared/vectors.json` | JSON | Coordinate and protocol test vectors read by Java, C++ and Python tests. | — |

The redscript side only talks to the DLL; the DLL only talks to Minecraft. Each unit is testable alone:
Minecraft against `fakehost.py`, the compositor against recorded frames, the scripts in-game via the CET
console.

### 3.2 Coordinates
- 1 metre = 1 block. Cyberpunk is Z-up: Cyberpunk (x, y, z) → Minecraft (x, z, −y), no offset, so every
  placed block has a permanent Night City location.
- Minecraft world uses a datapack dimension type with `min_y = -512`, `height = 2048`.
- Rotation conversion (yaw/pitch/roll, Cyberpunk quaternion → Minecraft yaw/pitch) is fixed by
  `shared/vectors.json`, measured in-game in layer 1 (step "cube test") and checked by all three test suites.

### 3.3 Protocol (WebSocket, JSON text frames; `t` = message type, as in the GTA reference)
All positions and angles on the wire are already in **Minecraft convention**: the DLL is the only place that
converts from Cyberpunk. Full field lists live in `shared/protocol.md`.

Cyberpunk → Minecraft
- `hello {proto:1, game:"cp2077", build:"2.31"}`
- `cam {f, p[3], r[yaw,pitch,roll], fov, fp, pl[3], h}` — every Cyberpunk frame (camera, vertical fov, feet,
  body yaw)
- `ground {c:[x,z,yBottom,yTop,…]}` / `unground {c:[…]}` — solid Night City ranges per column (inclusive
  block coords), deltas only; several ranges per column for multi-level places; `clear` drops all
- `npcs {list:[{id, pos[3], yaw, kind:"human"|"vehicle", faction, hostile, hp}]}` — 10 Hz, within 48 m
- `input {buttons, wheel, keys[], cursor[2]|null}`
- `dmg {amount, kind, from[3]}` — damage V received (V is invincible; Steve takes it)
- `state {active, inVehicle}`

Minecraft → Cyberpunk
- `hello {proto:1}`
- `status {hp, maxHp, food, gamemode, screenOpen, ram, maxRam}` — 10 Hz
- `explosion {pos[3], power, fire}`
- `hit {target, dmg, kind:"melee"|"arrow"|"trident"|"mob", impulse[3]}`
- `ignite {target, seconds}`
- `block {op:"add"|"remove", pos[3]}`
- `teleport {pos[3], yaw}` · `flight {active, pos[3], vel[3], yaw, pitch}`
- `lead {target, anchor[3], length}` / `lead {target, release:true}` · `pull {target, toward[3], strength}`
- `knock {pos[3], radius, strength}` (wind charge)
- `effect {name, seconds}` (potions → Cyberpunk status effects)
- `hack {target, hack}` · `perk {id, rank}`

### 3.4 Shared memory frame
Named mapping `Local\CyberblockFrame`, same layout as the GTA reference's `FrameExporter` (4096-byte
header, three slots with seqlock, render pose in Minecraft convention, near/far/fov, depth flags) with magic
`"CBFR"`. Each slot: world RGBA8, world depth float32, overlay RGBA8 (hand, hotbar, HUD, open screens). Minecraft renders at Cyberpunk's output
resolution (2560×1440 → ~44 MB per slot). Asynchronous GPU readback; neither process ever waits on the other.

## 4. Layer 1: core passthrough

### 4.1 Rendering
1. Each Cyberpunk frame the DLL sends `cam` + `player`.
2. Minecraft renders from that pose with sky, fog and clouds off; Steve stands at V's feet; V's own body is
   hidden in Cyberpunk.
3. The ReShade add-on uploads the newest slot. `Cyberblock.fx`:
   - **reprojects** the Minecraft frame from its render pose to Cyberpunk's current camera (rotation, then
     6-DoF with depth) to hide link latency;
   - **depth-tests** against Cyberpunk's depth buffer (reversed-Z, linearised with `near/far`; with DLSS the
     depth buffer is at render resolution and is sampled by UV);
   - **relights** Minecraft pixels from a blurred copy of the Cyberpunk frame (neon, night) and applies
     Cyberpunk's haze; softens edges;
   - draws the overlay on top.
4. Required settings: frame generation **off**. DLSS super resolution, ray reconstruction and path
   tracing are supported.

### 4.2 Collision from Night City
Every frame the scripts raycast a budget of probes (default 192) around V and send solid cells as deltas:
- **down rays** per column within V's feet −10 m … head +3 m, 24-block radius, so floors under overpasses
  and inside buildings are found;
- **wall rays** horizontally in 32 directions at 3 heights (0.5, 1.5, 2.5 m).
Minecraft fills those cells with barrier blocks in a managed layer that the player cannot break and that
explosions do not destroy; cells leaving the radius are cleared.

### 4.3 V's state while active
V's body and weapons hidden, combat blocked by a gameplay restriction, Cyberpunk HUD hidden on foot,
V invincible. Entering a vehicle: Steve hidden in Minecraft, Cyberpunk HUD restored; leaving: back.
All state is applied at runtime and cleared before every save and on every load, so saves stay vanilla.

### 4.4 Input
Cyberpunk keeps focus and keeps WASD/jump/crouch/sprint/camera. The DLL forwards mouse buttons, wheel,
1–9, E, Q, T, `/`, F5 and the mod's keys to Minecraft and stops Cyberpunk from acting on them. When a
Minecraft screen is open (`status.screenOpen`), Cyberpunk's camera input is frozen and mouse movement
drives Minecraft's cursor.

### 4.5 Failure handling
- **F9** toggles the whole mashup.
- Watchdog: no Minecraft frame or `status` for 2 s → restore normal V, show a notification, keep
  reconnecting; resume automatically when Minecraft returns.
- Protocol mismatch in `hello` → refuse to activate, notify.
- Shared-memory frame from a stale pose (> 250 ms) → not drawn.

### 4.6 Install and launch
- `tools/install.py` copies `Cyberblock.dll`, scripts, shader and ReShade config into the game folder, records
  what it wrote, refuses to replace files it did not write, and `--remove` deletes exactly its own files.
  Before the first install it backs up `%USERPROFILE%\Saved Games\CD Projekt Red\Cyberpunk 2077`.
- `tools/play.py` starts the Minecraft dev client into world `cyberblock`, waits for the link, starts
  Cyberpunk via Steam.

### 4.7 Testing and oracles
- `shared/vectors.json` checked by JUnit, a C++ test exe and pytest.
- `tools/fakehost.py` drives Minecraft with scripted poses/probes and dumps shared-memory frames to PNG.
- **Cube test** (layer 1 acceptance): one block placed at a known Night City spot is drawn at that spot,
  hidden behind a wall when it should be, and stays put while the camera turns — verified by screenshots.
- **Lag test**: a gold wall in front of a fixed landmark; measure drift during fast turns.

**Layer 1 done when:** walking around Night City as Steve with hand and hotbar visible; placed blocks sit
on Night City floors and are occluded correctly; F9 and watchdog restore normal V; install/remove are clean.

## 5. Layers 2–6

Rule for all layers: **Steve's health is the only health.** While active, V is invincible and every
Cyberpunk damage to V becomes `dmg` to Steve. Survival: Steve can die and respawns the Minecraft way;
Cyberpunk never game-overs. Creative: both invincible.

**Proxies:** each Cyberpunk NPC and vehicle within 48 m (`npcs`) exists in Minecraft as an invisible proxy
entity at its position with matching hitbox. Minecraft weapons and mobs hit proxies; damage to a proxy is
sent as `hit` and applied as real Cyberpunk damage, so heat, NCPD, ragdolls and deaths are native.

### Layer 2: building and explosions
- `block` add/remove → invisible Cyberpunk collision box (1 m), streamed within 64 m, cap 1,000.
- TNT, creepers, firework impacts → `explosion` → Cyberpunk explosion scaled by power.
- Arrows, tridents, crossbow → `hit` on the proxy struck (projectiles also stop on Night City barrier cells).
- Melee → `hit` with impulse: NPCs ragdoll, vehicles are shoved.
- Fire charge, flint and steel, lava → `ignite`.
- **Done when:** a wall stops a car and pedestrians; TNT under a car blows it up and draws NCPD.

### Layer 3: mobs vs Night City
- Hostile Minecraft mobs target proxies of humans as well as Steve.
- Cyberpunk NPCs fight back at invisible Cyberpunk stand-in puppets spawned on up to 12 nearest mobs;
  damage to a puppet → mob damage in Minecraft.
- Fallback if puppets prove unreliable: NPCs react to the chaos and target V (damage still lands on Steve).
- **Done when:** a zombie kills a pedestrian; an NCPD officer kills a skeleton.

### Layer 4: movement
- Elytra: while gliding, Minecraft physics move Steve; `flight` moves V each frame; touching down returns
  control to Cyberpunk movement. Probe radius grows with speed. Fallback if per-frame moves jitter: move
  only the camera during flight and place V on landing.
- Ender pearl landing → `teleport`.
- **Done when:** glide from a megabuilding roof with firework boosts and land on a street.

### Layer 5: interactive items
- **Lead (lasso):** leash a proxy; beyond the leash length the NPC/vehicle is pulled toward Steve; release
  by using the lead again.
- **Fishing rod:** hook a proxy and reel it in (`pull`).
- **Wind charge:** `knock` blast.
- **Shield:** while raised and facing the shooter, Cyberpunk gunfire damage to Steve is blocked.
- **Potions:** invisibility → optical camo, speed → sprint boost, others → closest Cyberpunk status effect.
- **Done when:** drag a pedestrian with a lead and reel a car with a fishing rod.

### Layer 6: Cyberpunk in Minecraft form
- **Hack key (G, rebindable):** hold to scan; the targeted proxy or device glows and shows a Minecraft-style
  tooltip (name, faction, threat).
- **RAM bar** in the Minecraft HUD; regenerates over time.
- **Quickhack items** (custom items, crafted or from the kit), each sending `hack` and firing the real
  Cyberpunk quickhack with its VFX:

  | Item | Cyberpunk quickhack |
  |---|---|
  | Redstone Charge | Short Circuit |
  | Blaze Chip | Overheat |
  | Ink Sac Daemon | Reboot Optics |
  | Cobweb Daemon | Cripple Movement |
  | Spider Eye Virus | Contagion |
  | Wither Skull Daemon | Synapse Burnout |
  | Compass Ping | Ping |
  | Redstone Lever | device hacks (traffic lights, cameras, vending machines) |

- **Skill tree (K, rebindable):** an advancement-style screen with five branches (Body, Reflexes,
  Technical, Intelligence, Cool), four perks each, bought with XP levels, each with a Minecraft and a
  Cyberpunk effect:

  | Branch | Perks |
  |---|---|
  | Body | Iron Skin (+4 hearts) · Gorilla Arms (melee shoves vehicles, ×2 knockback on NPCs) · Painkiller (faster natural regen) · Juggernaut (knockback resistance; NPC melee cannot stagger) |
  | Reflexes | Double Jump · Sandevistan Sprint (Speed II + Cyberpunk slow-mo on double-tap sprint) · Quick Draw (bow and crossbow load 30% faster) · Dodge (Cyberpunk air dash enabled on foot) |
  | Technical | Tinkerer (TNT and arrow recipes yield ×2) · Smart Arrows (arrows home onto proxies) · Field Medic (food and potions heal 50% more) · Demolitions (explosion power ×1.5 in both games) |
  | Intelligence | Extra RAM (+4 max) · Cheap Hacks (−1 RAM per hack) · Chain Hack (a hack spreads to one more target) · Overclock (RAM regen ×2) |
  | Cool | Optical Camo (crouch still 2 s → invisibility + Cyberpunk camo) · Headhunter (×2 arrow damage on headshots vs NPCs) · Cold Blood (Speed I for 10 s after a kill) · Silent Steps (mobs and NPCs detect at half range) |
- **Done when:** short-circuit an NCPD officer with a Redstone Charge, buy and feel Double Jump.

Stretch after layer 6: cyberware items (mantis-blade sword, gorilla-arm gauntlets).

## 6. Risks
| Risk | Mitigation |
|---|---|
| ReShade + RED4ext/CET/DLSS conflicts on DX12 | Layer 1 starts with ReShade alone, then one mod framework at a time; fallback is our own D3D12 present hook in the DLL. |
| Cyberpunk depth buffer not found or wrong under DLSS/path tracing | Verified first in layer 1 with a depth-visualisation pass before any compositing. |
| redscript APIs differ in 2.31 | Each API is checked against Codeware/redscript references and the CET console before use. |
| NPCs fighting mobs via puppets | Fallback in layer 3. |
| Elytra jitter | Fallback in layer 4. |
| Patch updates break hooks | Pin to 2.31; the DLL checks the game build and refuses to activate on a mismatch. |

## 7. Rules
- Single-player only; no anti-cheat involved (Cyberpunk has none).
- Repository contains our code, our pixel art and converters only — never game files. Credit
  universal-modder (MIT) for the forked Minecraft and compositor code.
- Downloads and installs of tools and frameworks are done with the user's approval.
