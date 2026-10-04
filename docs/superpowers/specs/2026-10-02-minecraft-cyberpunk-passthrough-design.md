# Cyberblock: Minecraft × Cyberpunk 2077 passthrough — design

Date: 2026-10-02 · Status: approved in brainstorming, awaiting spec review · Amended 2026-10-03 after the second
in-game re-test (ground alignment in §3.2, the flight drive and launches in §5 layer 4, cyberware in §5) and after the
third (re-test 3: Minecraft draws every piece of interface, vehicles keep Steve, third person, distinct quickhacks, six
keys, release packaging: §4.3, §4.8, §5 layers 4 and 6) · Amended 2026-10-04 for online play (the multiplayer megaplan:
§1 non-goals and §1.1 below)

## 1. What we are building

Play Cyberpunk 2077 as a Minecraft player. Real Minecraft Java runs next to Cyberpunk and is drawn into
Night City every frame; Minecraft's mechanics (building, TNT, mobs, elytra, items, game modes) act on
Night City through real Cyberpunk effects (explosions, damage, collision, teleports, quickhacks).

### Decisions (from brainstorming)
| Question | Decision |
|---|---|
| Approach | **True passthrough**: real Minecraft + Fabric mod, composited into Cyberpunk (like the viral videos and the universal-modder Minecraft × GTA V example). |
| Who moves the body | **Cyberpunk moves, Steve acts.** Cyberpunk's movement on foot; Minecraft physics only during elytra flight. |
| Cyberpunk combat on foot | **Minecraft only.** Cyberpunk guns, cyberware and hacking are disabled while the mashup is on. **Cars stay**: V drives with Cyberpunk's controls and **Steve rides along** (re-test 3 reverses the first design, which hid Steve and restored the Cyberpunk HUD in a vehicle): Minecraft shows only Steve's hand in a first-person vehicle camera (hotbar hidden), the whole seated Steve in a third-person one, and Cyberpunk's HUD never comes back (§4.3, §4.8). |
| Game mode | **Both, switchable** with Minecraft's `/gamemode`. Survival spawns with a starter kit (`/cyberblock kit` refills); creative makes both Steve and V invincible. |
| Priorities | Building + TNT/creepers → mobs vs Night City → elytra + ender pearls → interactive items (lead/lasso, fishing rod, …) → Cyberpunk-as-Minecraft (quickhack items, hack key, skill tree). Nether and day/night sync are out of scope. |

### 1.1 Online play (amended 2026-10-04: now a goal)
The first version of this spec listed "online play of any kind" as a non-goal. **That is reversed.** The multiplayer megaplan
(`docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md`) builds an Apex-hosted Fabric 26.3 Minecraft world in which
Cyberpunk-linked players (**hosts**) and Minecraft-only friends (**guests**, who need the Cyberblock mod and resource pack)
play together; what either side does reaches the other through the game's own server. The design rules that follow from it:

- **Each Cyberpunk game owns its own Night City** (NPCs, devices, V's body and camera); **the Minecraft server owns the shared
  world** (blocks, items, mobs, players, perks, RAM). Only a host's own Minecraft client ever talks to that host's DLL.
- **The wire between the DLL and its client does not change** (`proto` 1). The client keeps LOCAL messages on the PC (camera,
  keys, interface, frame export) and relays the rest to the server as `cyberblock:up` payloads; the server sends a host's
  DOWN messages back as `cyberblock:down`. One server session per host, keyed by the player's UUID; Night City ids are scoped
  by their owner. [`shared/protocol.md`](../../../shared/protocol.md) "Multiplayer (Phase 1)" is the contract.
- **There is no separate relay service and no second code path.** Singleplayer is the integrated server reached through the
  same payloads; opening the world to LAN makes it a small multiplayer server with no other change.
- **Hosts decide what guests may do to their Cyberpunk game** (explosions, damage, hacks, devices, spawns), and the server
  checks every UP message (type, size, rate, position sanity) before any handler sees it.
- **Content is data** from Phase 1 on (datapacks under `data/<namespace>/cyberblock/<kind>/`, hot-reloaded), so friends add
  content without code; `tools/content_check.py` validates it.
- A no-install "Lite" tier for friends without the mod is a later version; the data layer leaves room for it and nothing is
  built for it now.

The rest of this document describes the single-player architecture, which is exactly what a host runs; sections that say
"one JVM" or "the local world" describe the mechanism the relay now carries over the network.

### Non-goals
- A separate relay service, or any online play that bypasses the game's own server (see 1.1). Cyberpunk itself stays
  single-player: every host plays its own copy.
- Replacing Cyberpunk's movement with Minecraft's on foot.
- Copying or redistributing game files: no Minecraft or Cyberpunk assets in this repo. Item textures are
  original 16×16 pixel art.
- VR, frame generation.

## 2. Environment (measured 2026-10-02)
- Cyberpunk 2077 **2.31** + Phantom Liberty, Steam (the game folder is found through Steam's libraries).
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
| `cp/scripts/Cyberblock/` | redscript | Mashup state (on/off, V hidden, combat blocked, ink layers hidden and UI data streamed, god mode); raycast probes; collision boxes; applying events as Cyberpunk effects; NPC list; quickhack and perk effects. | Codeware, RED4ext natives |
| `tools/` | Python | `install.py` (install/remove exactly our files, refuse to overwrite others, back up saves first), `play.py` (start Minecraft dev client then Cyberpunk), `fakehost.py` (oracle), `check_vectors.py`. | — |
| `shared/vectors.json` | JSON | Coordinate and protocol test vectors read by Java, C++ and Python tests. | — |

The redscript side only talks to the DLL; the DLL only talks to Minecraft. Each unit is testable alone:
Minecraft against `fakehost.py`, the compositor against recorded frames, the scripts in-game via the CET
console.

### 3.2 Coordinates
- 1 metre = 1 block. Cyberpunk is Z-up: Cyberpunk (x, y, z) → Minecraft (x, z − off, −y), so every placed block has a
  permanent Night City location. Directions, velocities and impulses use (x, z, −y) with no offset.
- **Ground alignment (amended after re-test 2).** The first design had no vertical offset and put the top of a ground
  barrier at `ceil(z) − 1`, which is 0 to 1 m above the real floor: every block the player placed floated about one
  block above the street. Each world now has an integer `k` in 0..63 with `off = k/64` m, so Minecraft y = Cyberpunk
  z − off. Minecraft owns `k`: it is chosen at the first activation, while V stands on the ground, so that the floor under
  V's feet lands just above a whole block boundary (`k = floorMod(floor(frac(z) · 64 − 0.125), 64)`), saved in the world,
  and sent to the DLL as `align`; `/cyberblock level` chooses it again for the ground V stands on now. The DLL applies
  `off` to every position in both directions and rounds other ground to the **nearest** block (`top = floor(z − off +
  0.5) − 1`) instead of rounding up, so any floor is within half a block of the barrier that stands for it. Before an
  `align` arrives the DLL uses `k = 0`. Details: `shared/protocol.md`, "Ground alignment".
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
- `teleport {pos[3], yaw}` · `flight {active, pos[3], vel[3], yaw, pitch, kind:"glide"|"launch", ts}` (re-test 2)
- `align {k}` (the world's ground alignment, 0..63) · `cyberware {list:[{id, on}]}` · `cyberfx {id, seconds}` (re-test 2)
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
explosions do not destroy; cells leaving the radius are cleared. Heights go through the world's ground alignment and
nearest rounding (§3.2).

### 4.3 V's state while active
V's body and weapons hidden, combat blocked by a gameplay restriction, Cyberpunk's 2D interface layers invisible
(§4.8), V invincible. **Entering a vehicle (re-test 3):** V stays invincible and V's body stays hidden in both vehicle
cameras, the interface stays invisible and **Steve rides along** in Minecraft: Steve's hand (no hotbar) in a first-person
camera, the whole seated Steve (at V's hips point, read from the vehicle's Hips slot) in a third-person camera; the DLL
reports `cam.seat` and an honest `cam.fp`, and Minecraft hides its hotbar, survival bars and crosshair while seated. Leaving:
back to the on-foot state. All state is applied at runtime and cleared before every save and on every load, so saves stay
vanilla.

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

### 4.8 Minecraft draws the interface (re-test 3)
**The principle:** Cyberpunk keeps the game logic and its own input; **Minecraft draws every piece of UI.** The user's
request was that the old Cyberpunk interface should not appear at all and that every UI element, prompts and dialogue
choices included, should be Minecraft-themed.
- **Hiding.** While Cyberblock is On (Active or in a vehicle) the scripts set the three 2D ink layers that carry the
  game's interface (`inkHUDLayer`, `inkGameNotificationsLayer`, `inkWaitingSignLayer`) to opacity 0 while their controllers
  keep running, so prompts and dialogue choices still take Cyberpunk's input (F, E/Q/1-4/wheel). World-space layers
  (street signs, advertisements), the system notifications layer and photo mode are never touched. Everything is restored
  on Off, Lost, session end, detach and before every save. Nothing re-shows the HUD for a prompt or a dialogue any more.
  Two logged exceptions: a popup that pauses the game by itself (tutorials, shards) and the braindance timeline.
- **Data over the wire.** Cyberpunk streams stats (eddies, wanted level, time, district, speed), the tracked quest and its
  waypoint, map pins, interaction prompts, dialogue choices, subtitles, phone calls and every notification (`stats`,
  `quest`, `pins`, `prompt`, `choices`, `sub`, `call`, `notify`); Minecraft renders them with its own font, sprites and
  colours (radar, quest panel, waypoint on the locator bar, tooltip-styled prompt and choice list, toasts) taken from the
  player's own Minecraft install at runtime, never copied.
- **Menus.** The Cyberpunk menu keys are intercepted and the menu is replaced by a Minecraft screen (pause, atlas,
  journal, phone, inventory, skills; journal, phone and atlas data come as `reply` messages). The Night City pause screen
  pauses Cyberpunk through `status.pause`, level-triggered. A menu with no Minecraft equivalent (shops, ripperdoc, stash,
  crafting benches, breach protocol, settings, save lists, death and loading screens, tutorial popups) is **passed
  through**: the scripts tint its widget tree in Minecraft colours, Minecraft draws a title bar and a bevelled frame
  around it (`screen.pass`), and the compositor (`cb_menu`) does not draw the Minecraft world over it. Limit: Minecraft's
  font cannot be copied into Cyberpunk, and an original pixel font in an `.archive` needs WolvenKit, so the text of
  pass-through menus stays in Cyberpunk's font. An escape hatch ("Night City Menu..." in the pause screen) opens the
  vanilla Cyberpunk menu once, and with no link every Cyberpunk menu works as in vanilla.
- **Third person (re-test 3).** F5 toggles an on-foot third-person view: Minecraft sends `persp`, and the DLL moves
  Cyberpunk's first-person camera component back and up (distance about 3.2 m, shortened by raycasts against Night City
  and the barrier blocks) while it is allowed (Active, on foot, not held, no menu, no braindance, no photo mode); `cam.fp`
  is honest, Steve is drawn in full, and Minecraft draws a centre crosshair. Vehicle cameras stay Cyberpunk's own.

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
- Melee → `hit` with impulse: vehicles are shoved, and people are pushed the way Minecraft pushes a mob (re-test 3:
  `|impulse| = (0.4 + knockback) x 5` m/s); Cyberpunk turns the speed into a flinch, a stagger, a knockdown or, only at
  10 m/s and more, a short ragdoll, instead of ragdolling everyone hit.
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
  control to Cyberpunk movement. Probe radius grows with speed. As built (re-test 2): the **DLL** teleports V every
  rendered frame from RED4ext's update hook, playing the stream out a few milliseconds late and interpolating between
  samples; redscript only decides whether V may be moved and owns the fall-damage rules. The camera-only fallback
  of the first design was dropped, because the script loop it was meant to back up never ran per frame in 2.31.
  **Steering (re-test 3):** the glide steers with the camera like the vanilla elytra. The DLL owns V's yaw while it
  drives: it adds the relative mouse-X counts to the yaw it teleports V with (and strips the game's own horizontal mouse
  turn meanwhile), so the camera yaw Minecraft reads from `cam` follows the mouse; before, V was teleported every frame with
  the yaw read before that frame's mouse delta, which undid the turn.
- **Launches (re-test 2):** a wind charge (Steve's own, another's or a breeze's) or a mace burst that throws Steve into
  the air starts the same flight pipeline with `kind:"launch"`: Minecraft physics move Steve, V follows every frame
  until the touchdown, and an elytra opened mid-air turns it into a glide.
- Ender pearl landing → `teleport`.
- **Done when:** glide from a megabuilding roof with firework boosts and land on a street.

### Layer 5: interactive items
- **Lead (lasso):** leash a proxy; beyond the leash length the NPC/vehicle is pulled toward Steve; release
  by using the lead again.
- **Fishing rod:** hook a proxy and reel it in (`pull`).
- **Wind charge:** `knock` blast on people and cars. A burst that reaches Steve also launches him (layer 4).
- **Shield:** while raised and facing the shooter, Cyberpunk gunfire damage to Steve is blocked.
- **Potions:** invisibility → optical camo, speed → sprint boost, others → closest Cyberpunk status effect. As built
  (plan 2026-10-03 §1.5), only invisibility, speed, slowness, jump boost, nausea, blindness and darkness are mapped;
  every other effect is ignored on V (logged once), and damage-over-time effects are never mapped.
- **Done when:** drag a pedestrian with a lead and reel a car with a fishing rod.

### Layer 6: Cyberpunk in Minecraft form
- **Hack key (G, rebindable):** hold to scan; the targeted proxy or device glows and shows a Minecraft-style
  tooltip (name, faction, threat).
- **RAM bar** in the Minecraft HUD; regenerates over time.
- **Quickhack items** (custom items, crafted or from the kit), each sending `hack` and firing the real
  Cyberpunk quickhack with its VFX. **Re-test 3:** the chip in the main hand fires (then the wheel pick), and every chip is
  unique in both games: its own Minecraft visuals and behaviour at the impact (lightning, flames, ink, real cobwebs, a
  green cloud, wither trail, outlines through walls, swirls, a creeper face, per-device effects), played when the upload
  completes (`hackresult.upload`, `hackspread`), and a distinct Cyberpunk effect, with a visible extra on crowd
  pedestrians that vanilla does not quickhack (`hackresult.kind`):

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

### Cyberware (re-test 2)
The stretch item of the first design became a feature of its own. **Cyberware** are items the player *installs* in
custom ripperdoc slots (arms, legs, operating system, nervous system, integumentary, eyes, circulatory, frontal
cortex; not armor slots), through a Cyberware screen on U and with activation keys (re-test 3: Y for the Operating System implant, L for the Arms
implant, O for Optical Camo); 17 implants in all, listed in
the player guide. Minecraft owns the whole state (what is installed, cooldowns, selection) and sends the installed set
to Cyberpunk as `cyberware` and timed effects as `cyberfx`.
- Every implant has a Minecraft effect with particles and sounds, and where it makes sense the real Cyberpunk effect on
  V: Sandevistan and Kerenzikov slow Night City, Optical Camo cloaks V, Reinforced Tendons and Fortified Ankles give V
  the double and charge jump back, the Projectile Launch System is a real explosion, Kiroshi Optics lengthens the scan,
  Lynx Paws halves detection. Berserk uses Cyberpunk's own effect only if it proves safe.
- The netrunning RAM now comes from the Cyberdeck implant (or from a deck carried while the operating-system slot is
  empty); Extra RAM and the RAM Upgrade implant add up.
- Three perks became upgrades of implants (Double Jump, Sandevistan Sprint, Optical Camo), so one effect never comes from
  two sources. Cyberpunk's own cyberware stays disabled (§1); Cyberblock's implants replace it.
- Items are craftable, in the creative tab and (a basic set) in the starter kit; in creative, installs are free.
- **Re-test 3:** the Operating System slot has two places (the Cyberdeck, and Sandevistan or Berserk) so a netrunner can run
  both; Mantis Blades, Gorilla Arms and Monowire became active implants on the Arms key with an ability each (a lunge, a
  slam, a lash); the four arm implants have original 3D models in first and third person; the Sandevistan adds a ReShade
  screen effect (chromatic split, tint, ghost trails, edge glow, strength slider) next to the Minecraft flourish; Optical
  Camo makes Steve a translucent shimmer in third person; the mod has its own advancements.

## 6. Risks
| Risk | Mitigation |
|---|---|
| ReShade + RED4ext/CET/DLSS conflicts on DX12 | Layer 1 starts with ReShade alone, then one mod framework at a time; fallback is our own D3D12 present hook in the DLL. |
| Cyberpunk depth buffer not found or wrong under DLSS/path tracing | Verified first in layer 1 with a depth-visualisation pass before any compositing. |
| redscript APIs differ in 2.31 | Each API is checked against Codeware/redscript references and the CET console before use. |
| NPCs fighting mobs via puppets | Fallback in layer 3. |
| Elytra jitter | The DLL drives V every rendered frame (re-test 2); the first design's camera-only fallback was dropped. |
| Patch updates break hooks | Pin to 2.31; the DLL checks the game build and refuses to activate on a mismatch. |

## 7. Rules
- Single-player only; no anti-cheat involved (Cyberpunk has none).
- Repository contains our code, our pixel art and converters only — never game files. Credit
  universal-modder (MIT) for the forked Minecraft and compositor code. Minecraft's look in the interface comes from the
  player's own install at runtime (its sprites and font), never from copies.
- Downloads and installs of tools and frameworks are done with the user's approval.
