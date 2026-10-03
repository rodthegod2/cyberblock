# How to play Cyberblock

Cyberblock puts real Minecraft into Night City. Minecraft Java (26.3, with the Cyberblock Fabric mod) runs next to
Cyberpunk 2077 (2.31). Every frame, its picture is drawn into Cyberpunk at the right camera and depth.

The split is simple: **Cyberpunk moves V, Steve acts.** You walk, run, jump and drive with Cyberpunk's controls. You
place blocks, fight, throw, glide and hack with Minecraft's items, and those act on Night City's real people, cars and
devices.

> **Status (2026-10-03).** Layer 1 (the passthrough itself) has been verified in game. Layers 2 to 6 are built and
> pass every automated test, but they have **not been played in game yet**. Their acceptance list is in
> `docs/superpowers/plans/2026-10-03-layers3-6-complete-mod.md`, section I.2. Expect rough edges. The fallback
> switches under [Troubleshooting](#troubleshooting) exist for exactly that.

---

## 1. Setup

### What you need
- **Cyberpunk 2077 2.31** on Steam (Phantom Liberty is fine). These mods must be installed:
  - **RED4ext**: loads `Cyberblock.dll`;
  - **redscript**: compiles the scripts;
  - **Codeware**;
  - **Cyber Engine Tweaks (CET)**: needed only for its console, which the fallback switches use.
- **ReShade 6.x with add-on support** (the "with full add-on support" installer from reshade.me), installed for
  `bin\x64\Cyberpunk2077.exe` with the **DirectX 10/11/12** API.
- **To build:**
  - JDK 25;
  - Visual Studio 2022 Build Tools (C++ x64 and the Windows SDK) and CMake;
  - Python 3.12 with `uv`;
  - git (the repository uses submodules: `git submodule update --init`).
- **RAM:** about 64 GB, and a page file big enough for both games (see
  [Memory and the page file](#memory-and-the-page-file)).

### Build
From the repository root:
```
cmake -S cp/plugin -B cp/plugin/build -G "Visual Studio 17 2022" -A x64
cmake --build cp/plugin/build --config Release
```
This produces `cp/plugin/build/Release/Cyberblock.dll`. You do not build the Minecraft mod by hand: `tools/play.py`
builds and starts it through Gradle.

### Install into Cyberpunk
Close Cyberpunk first, then run:
```
uv run tools/install.py
```
The installer copies these files into the game folder:
- `red4ext/plugins/Cyberblock/Cyberblock.dll`;
- the scripts, into `r6/scripts/Cyberblock/`;
- `Cyberblock.fx` and `ReShade.fxh`, into `bin/x64/reshade-shaders/Shaders/`;
- a `bin/x64/ReShadePreset.ini` that turns the `Cyberblock` effect on. It writes this file **only if you have no preset
  yet**.

What the installer guarantees:
- **No overwriting:** it records every file it wrote, with a checksum, in
  `red4ext/plugins/Cyberblock/install-manifest.json`. It refuses to overwrite any file it did not write itself.
- **Save backup:** before the very first install, it zips your saves folder
  (`%USERPROFILE%\Saved Games\CD Projekt Red\Cyberpunk 2077`) into `%LOCALAPPDATA%\Cyberblock\save-backups`. Use
  `--backups <folder>` to put the zip somewhere else.
- **Updating:** after you pull a new version, rebuild the DLL and run the same command again. It replaces its own
  files.

### ReShade: one-time settings
1. Start Cyberpunk and open the ReShade overlay (**Home**).
2. **Add-ons tab:** "Cyberblock" must be listed and enabled. The Generic Depth add-on must be on as well, because
   Cyberblock needs Night City's depth buffer.
3. **Home tab:** the **Cyberblock** effect must be ticked. If you already had a ReShade preset, the installer left it
   alone, so tick it yourself.
4. **Check the depth** once: tick **CyberblockDepthDebug** for a moment. You should see this:
   - black right in front of you, white at 50 m;
   - a thin red line every 5 m, and the lines follow the floor and the walls.

   If the picture is flat or does not follow the scene, the wrong depth buffer was picked. See
   [Troubleshooting](#troubleshooting). Untick it again afterwards.
5. **In Cyberpunk's graphics settings, turn off frame generation** (DLSS Frame Generation, FSR Frame Generation and
   similar). Generated frames have no matching Minecraft frame, so Minecraft would tear and swim. DLSS Super
   Resolution, Ray Reconstruction and path tracing all work.

---

## 2. Launching

```
uv run tools/play.py
```
The script does this:
1. It finds JDK 25.
2. It starts the Minecraft dev client into the world `cyberblock`. On the first run it creates this world: a void
   "Night City" world with build height −512 to 1535, cheats on, always noon, clear weather and no natural mob
   spawns.
3. It waits until the mod listens on `127.0.0.1:25600`.
4. It starts Cyberpunk through Steam.

The first start downloads and builds things, so give it a few minutes. Press **Ctrl+C** in the terminal to stop
Minecraft.

- `uv run tools/play.py --no-game` starts only Minecraft. Use it if you start Cyberpunk yourself, or if Cyberpunk is
  already running.
- **Leave the Minecraft window open behind Cyberpunk. Do not minimize it:** a minimized Minecraft drops its frame rate,
  and the picture in Cyberpunk stutters. Cyberpunk keeps the focus. The Minecraft window resizes itself to
  Cyberpunk's resolution.

Then load a save and press **F9**.

| You see | Meaning |
|---|---|
| "Cyberblock on" | Active on foot. V is hidden and invincible, and Steve's hand and hotbar appear. |
| "Start Minecraft first" | Minecraft is not running, or not linked yet. Start it and press F9 again. |
| "Cyberblock: in vehicle" / "Cyberblock: on foot" | You got into or out of a car. |
| "Minecraft lost — V restored" | No Minecraft frame for 2 s (it closed or hung). V is back to normal. |
| "Minecraft back — Cyberblock on" | Minecraft came back. Cyberblock resumes by itself. |
| "Cyberblock off" | You pressed F9 again. Everything is back to vanilla. |

---

## 3. Controls

Cyberpunk keeps the focus. It keeps the movement keys and the camera. Cyberblock forwards the keys below to Minecraft,
and Cyberpunk never sees the ones marked *swallowed*.

| Key | While Cyberblock is active on foot |
|---|---|
| **F9** | Turn Cyberblock on or off. |
| **W A S D**, mouse look | Cyberpunk moves V; Steve follows V's feet and camera. |
| **C** (crouch) | V crouches, and Steve crouches with him. Optical Camo needs this. |
| **Space** | V jumps, as in Cyberpunk. While falling with an elytra on, it also opens the elytra (see layer 4). |
| **Left Shift** | V sprints, as in Cyberpunk. With the Sandevistan perk, a double-tap (within 300 ms) fires Sandevistan Sprint. |
| **Left mouse** *(swallowed)* | Minecraft attack / break. |
| **Right mouse** *(swallowed)* | Minecraft use / place: blocks, bow, shield, potions, pearls, leads, fishing rod, wind charges, quickhack items. |
| **Middle mouse** *(swallowed)* | Minecraft pick block. |
| **Mouse wheel** *(swallowed)* | Scroll the hotbar. |
| **1–9** *(swallowed)* | Hotbar slots. |
| **E** *(swallowed)* | Minecraft inventory. |
| **Q** *(swallowed)* | Drop the held item. |
| **T** / **/** *(swallowed)* | Open Minecraft chat / a command line (see [Commands](#commands)). |
| **F5** *(swallowed)* | Minecraft's perspective key. It does nothing while Cyberpunk drives the camera, and it no longer quick-saves Cyberpunk. |
| **G** (hold) *(swallowed)* | **Hack key**: scan whatever is under the crosshair (layer 6). |
| **K** *(swallowed)* | **Skill tree** (layer 6). |
| **Esc** | With a Minecraft screen open, it closes that screen. Otherwise it opens Cyberpunk's pause menu as usual. |
| **F** | Still Cyberpunk's interact: doors, talking, picking things up. |

**Minecraft screens** (inventory, chest, crafting table, skill tree, death screen):
- While one is open, the mouse moves Minecraft's cursor, clicks go to the screen, and Cyberpunk's camera is frozen.
- **Esc** closes it. **E** also closes the inventory and containers, and **K** the skill tree.
- **Q** drops the hovered stack, and **1–9** swap the hovered stack into that slot.

**Cyberpunk menus** (map, journal, pause menu, Cyberpunk inventory): input forwarding pauses while one is open, so
they work as usual.

**Rebinding the hack and skill-tree keys:** Minecraft → Options → Controls → Key Binds → category **Cyberblock**.
- Cyberpunk can only forward letters, digits and F-keys. A binding on any other key works in Cyberpunk as the default
  (G or K), and a chat message tells you so.
- These keys are refused, and the previous binding is kept: W, A, S, D, Space, Shift, Ctrl, C, F9, Esc, and the
  forwarded keys above (E, Q, T, 1–9, /, F5).

---

## 4. The one health rule

**Steve's health is the only health.**
- While Cyberblock is active on foot, V is invincible.
- Every hit V would have taken becomes damage to Steve: bullets, explosions, melee, falls, vehicles, fire and burning
  damage-over-time. The damage is scaled by V's maximum health. A hit worth a quarter of V's health takes a quarter of
  Steve's.
- Cyberpunk never shows a game over. If Steve dies, the Minecraft death screen appears: click **Respawn** with the
  mouse.
- The world keeps your inventory when you die (`keepInventory` is on).

---

## 5. What you can do, layer by layer

Every Night City person and car within 48 m exists in Minecraft as an invisible **proxy** with a matching hitbox.
Minecraft weapons, mobs, leads and hooks hit those proxies. The result is applied as real Cyberpunk damage or force,
so ragdolls, NCPD heat, deaths and explosions are all native.

### Layer 1: Minecraft in Night City
- Steve stands at V's feet. You see his hand, the hotbar and the HUD, drawn over Night City with correct depth:
  - placed blocks sit on Night City's floors;
  - Night City's walls hide them.
- **Night City is solid for Minecraft.** Raycasts around V turn floors and walls into invisible barrier blocks:
  - the radius is 24 m on foot, and grows to 64 m while gliding;
  - Steve, mobs and arrows stand on them and bump into them;
  - you cannot break them.
- **In a car:** Steve and the Minecraft HUD are hidden, Cyberpunk's HUD and controls come back, and everything below
  is restored. Getting out switches back.

### Layer 2: building, explosions, weapons
- **Placed blocks are solid in Night City too.** They stop pedestrians, traffic and V:
  - each is an invisible 1 m collision box;
  - boxes are kept within 64 m of V, at most 1000, nearest first;
  - mining a block removes its box;
  - after a restart or reconnect, Minecraft sends all your blocks again.
- **Explosions** become Cyberpunk explosions scaled by their power (TNT, creepers, firework rockets). Cars blow up
  and NCPD comes.
- **Weapons:** melee weapons (swords, axes…), arrows, tridents and crossbows hurt people and cars. People ragdoll,
  and melee shoves cars.
- **Fire:** flint and steel, fire charges, lava, Fire Aspect and flaming arrows set people alight.

### Layer 3: mobs against Night City
- Hostile Minecraft mobs (zombies, skeletons, creepers, spiders and the like) attack Night City's pedestrians as well
  as Steve. Spawn them with spawn eggs: natural spawning is off.
- Mobs that think with a "brain" (piglins, hoglins, zoglins, breezes, the creaking, the warden) stay vanilla and only
  go for Steve.
- Night City fights back:
  - up to the 12 nearest mobs get an invisible stand-in "puppet" in Cyberpunk;
  - pedestrians flee from them, and NCPD and gangers shoot at them;
  - damage done to a puppet is taken off the mob in Minecraft.
- Undead do not burn in the (permanent) Minecraft daylight.
- A mob that wanders more than 22 blocks from Steve, or off known ground, is held in place until it is back in range,
  so mobs never fall into the void around the area Cyberblock has mapped.
- **Fallback** if puppets misbehave: `CyberblockPuppets.SetEnabled(false)` in the CET console. Then mob attacks count
  as V's, and NPCs react to V instead.

### Layer 4: elytra and ender pearls
- **Gliding:**
  - wear an elytra (the starter kit puts one on);
  - while V falls (jump off a roof or a megabuilding), press **Space** to open it. A fall faster than 15 m/s for half
    a second opens it on its own;
  - while gliding, Minecraft physics fly Steve and V follows every frame. You steer with the mouse as usual;
  - **firework rockets** (right mouse) boost you;
  - Collision mapping reaches further ahead the faster you fly.
- **Landing:** when you touch down on a roof or a street, Cyberpunk movement takes over again.
- **Fall damage:**
  - Cyberpunk's fall damage is ignored while gliding and for 2 s after landing; Minecraft's own elytra rules apply
    (durability, crashing into walls);
  - it is always ignored under Slow Falling.
- **Ender pearls:** throw one (right mouse). Where it lands, V is teleported, with the same 2 s fall grace. Steve
  takes the usual pearl damage.
- Thrown things (pearls, arrows, tridents, potions, wind charges, the fishing hook) also stop on Night City walls,
  because the walls on their predicted paths are filled in ahead of time.
- **Fallback** if the per-frame flight jitters: `CyberblockFlight.SetCameraOnly(true)` in CET. Then only the camera
  flies, and V is placed at the landing spot. `CyberblockFlight.SetCameraOnly(false)` switches back.

### Layer 5: interactive items
- **Lead (lasso):**
  - right-click a person or a car with a lead. It stays within 5 m (a person) or 8 m (a car) of Steve, and is dragged
    back when it strays;
  - right-click a fence post to tie it there instead;
  - right-click the tethered one again to let go.
  - The lead snaps if the target is pulled more than 12 m past that length for 2 s, or when it despawns.
- **Fishing rod:** cast at a person or a car, then reel in (right mouse again). It is yanked toward you: people at
  9 m/s with a small hop, cars at 7 m/s.
- **Wind charge:** the burst knocks back every person and car around it. It causes no damage and no explosion.
- **Shield:** raise it (hold right mouse) facing a shooter, and Cyberpunk gunfire and blasts from that side are
  blocked, with vanilla durability and sound.
- **Potions on Steve also act on V:**

  | Potion effect | On V |
  |---|---|
  | Invisibility | Cyberpunk optical camo |
  | Speed | moves faster: +20 % per level, at most ×3 |
  | Slowness | moves slower: −15 % per level, at least ×0.3 |
  | Jump Boost | jumps higher: +50 % per level, at most ×4 |
  | Nausea | Cyberpunk's drunk screen |
  | Blindness, Darkness | Cyberpunk's blinded screen |

  Damage-over-time effects (poison, wither) are never passed to V. Their damage already reaches Steve in Minecraft.

### Layer 6: Cyberpunk in Minecraft form

#### Scanning (G)
1. Hold **G** and look at a person, a car or a device (camera, traffic light, vending machine, TV…).
2. A Minecraft-style tooltip shows its name, faction, threat (None / Low / Medium / High / Extreme, colour-coded) and
   health. A glowing outline marks it in Night City.

The scanned target stays **locked** for 5 s, so you can switch to a quickhack item and fire. When the crosshair is on
no person or car, Cyberpunk scans whatever V looks at, which is how you target devices.

#### RAM
- The RAM bar sits above the hotbar. You only have RAM while a **Cyberdeck** is in your inventory: without one, it is
  0.
- The maximum is 8, or 12 with the Extra RAM perk.
- It regenerates 1 point every 3 s (every 1.5 s with Overclock).
- A **RAM Shard** (right mouse) restores 4 at once.

#### Quickhacks
To fire one: lock a target with G, hold the item, and right-click.
- The RAM is spent and the item recharges, then the real Cyberpunk quickhack fires, with its VFX and its NCPD
  reaction.
- If Cyberpunk refuses (the target died; it is protected: quest characters, friends, people in scenes, anyone vanilla
  Cyberpunk would never let you hack; or V is in a cutscene), your RAM is refunded and the reason shows above the
  hotbar.

| Item | Quickhack | RAM | Recharge | Recipe (shapeless) |
|---|---|---|---|---|
| Redstone Charge | Short Circuit | 3 | 2 s | redstone, iron nugget, copper ingot |
| Blaze Chip | Overheat | 4 | 2 s | blaze powder, iron nugget, copper ingot |
| Ink Sac Daemon | Reboot Optics | 3 | 2 s | ink sac, iron nugget, copper ingot |
| Cobweb Daemon | Cripple Movement | 2 | 2 s | 2 string, iron nugget, copper ingot |
| Spider Eye Virus | Contagion (spreads) | 4 | 2 s | spider eye, iron nugget, copper ingot |
| Wither Skull Daemon | Synapse Burnout | 6 | 10 s | bone, coal, iron nugget, copper ingot |
| Compass Ping | Ping | 1 | 2 s | compass, iron nugget, copper ingot |
| Bottle of Forgetting | Memory Wipe | 3 | 2 s | glass bottle, sugar, iron nugget, copper ingot |
| Creeper Brain | Cyberpsychosis | 8 | 10 s | gunpowder, rotten flesh, redstone, copper ingot |
| Redstone Lever | Device hack | 1 | 2 s | lever, redstone, copper ingot |

The Redstone Lever works only on devices. What it does depends on the device:
- traffic lights flip the intersection;
- cameras stop seeing you;
- vending machines, TVs, radios and arcades make a distraction;
- anything else runs its first available quickhack.

Every other quickhack needs a person or a car.

**Other cyber items:**

| Item | What it does | Recipe (shapeless) |
|---|---|---|
| Cyberdeck | Needed to have RAM | 2 iron ingots, 2 redstone, glass pane, copper ingot, gold ingot |
| RAM Shard | +4 RAM, used up (stacks to 16) | lapis lazuli, redstone, glass pane |
| Mantis Blade | Sword: 7 damage, 1.8 attacks/s | iron sword, diamond, redstone, copper ingot |
| Gorilla Gauntlet | Fist weapon: 4 damage, knockback +2 (shoves cars) | iron block, leather, redstone, copper ingot |

All of them are also in the **Cyberblock** creative tab.

#### Skill tree (K)
Press **K** to open the skill tree: five branches of four perks.
- **Buying:** click a perk to buy it with **XP levels**. Tier 1 / 2 / 3 / 4 cost 3 / 5 / 8 / 12 levels, and each
  tier needs the one above it in its branch. Hover a perk to see its cost, what it needs, and its Minecraft and
  Cyberpunk effects.
- **Earning XP:** people and cars that die from your attacks in Night City drop XP orbs: 5 points, or 10 for a
  hostile. Vanilla XP works too.

| Branch | Perk (tier) | In Minecraft | In Cyberpunk |
|---|---|---|---|
| **Body** | Iron Skin (1) | +4 hearts of max health | — |
| | Gorilla Arms (2) | melee shoves people ×2 and cars ×3 as hard | the shove lands in Night City |
| | Painkiller (3) | natural regeneration twice as fast | — |
| | Juggernaut (4) | full knockback resistance, also against Night City hits | — |
| **Reflexes** | Double Jump (1) | — | V can double jump |
| | Sandevistan Sprint (2) | double-tap Left Shift: Speed II and the Minecraft world at half speed for 5 s, 20 s cooldown | Night City slows to 20 %, V does not |
| | Quick Draw (3) | bows and crossbows charge 30 % faster | — |
| | Dodge (4) | — | V can dash and air dash |
| **Technical** | Tinkerer (1) | TNT and arrow recipes make twice as many | — |
| | Smart Arrows (2) | your arrows home onto people and cars (20° cone, 32 m) | — |
| | Field Medic (3) | food and healing potions heal 50 % more | — |
| | Demolitions (4) | your explosions (your TNT, creepers you lit) are 1.5× as big | so are their blasts in Night City |
| **Intelligence** | Extra RAM (1) | +4 max RAM | — |
| | Cheap Hacks (2) | quickhacks cost 1 RAM less (at least 1) | — |
| | Chain Hack (3) | — | a hack also hits the nearest other person within 8 m of the target |
| | Overclock (4) | RAM regenerates twice as fast | — |
| **Cool** | Optical Camo (1) | crouch and stand still for 2 s: Invisibility for 10 s, renewed while you stay still | V's optical camo |
| | Headhunter (2) | arrow hits near a person's head deal double damage | — |
| | Cold Blood (3) | Speed I for 10 s after a kill in Night City | — |
| | Silent Steps (4) | mobs notice you from half as far | people notice V from half as far |

Perks are stored on Steve in the Minecraft world. They survive death and are sent to Cyberpunk every time the two
connect.

---

## 6. Game modes and the starter kit

Switch modes with Minecraft's `/gamemode` (cheats are on in the `cyberblock` world).

- **Survival** (default):
  - Steve can die and respawns the Minecraft way;
  - hacks cost RAM, and perks cost XP levels;
  - in a car, V takes normal Cyberpunk damage.
- **Creative:**
  - Steve cannot be hurt, so neither can V, and V also stays invincible in cars;
  - RAM and perk costs still apply. Give yourself levels with `/xp add @s 100 levels`.
- **Adventure / spectator:** vanilla rules. In adventure you cannot place or break blocks. A spectator takes no
  damage.

**Starter kit.** The first time you turn Cyberblock on in survival, Steve gets the kit (once per world). The elytra
goes on his chest if that slot is empty. Whatever does not fit in the inventory drops at his feet. `/cyberblock kit`
hands it out again at any time.

| Group | Items |
|---|---|
| Cyber | Cyberdeck, 2 RAM Shards, one of each of the 10 quickhack items, Mantis Blade, Gorilla Gauntlet |
| Building | 64 stone, 64 glass, 8 TNT, flint and steel |
| Weapons | bow, 32 arrows, shield, iron sword |
| Layer 5 items | 2 leads, fishing rod, 8 wind charges, 8 ender pearls |
| Flight | elytra, 16 firework rockets |
| Potions | Swiftness, Invisibility, Leaping |
| Spawn eggs | 4 zombie, 2 skeleton, 2 creeper |

### Commands
Cyberpunk forwards keys, not text. **T** and **/** open Minecraft's chat, but what you type must go into the Minecraft
window:
1. Alt+Tab to Minecraft.
2. Type the command and press Enter.
3. Alt+Tab back to Cyberpunk.

Useful commands: `/cyberblock kit`, `/gamemode creative`, `/gamemode survival`, `/xp add @s 30 levels`,
`/effect clear @s`.

---

## 7. What Cyberpunk stops doing while Cyberblock is active

All of this applies **on foot, while active**. Getting into a car, pressing F9, or "Minecraft lost" restores
everything. Before every save, all of it is taken off and put back afterwards, so your saves stay vanilla.

**V:**
- V is invincible (Steve takes the damage) and invisible: the body meshes are hidden, and Steve stands there instead.
- Weapons are holstered.
- The Cyberpunk HUD is hidden on foot.

**Blocked:**
- combat: guns, melee, cyberware arms, grenades and gadgets;
- Cyberpunk's own quickhacks, the phone, healing items (X), the scanner (Tab) and the weapon/radial wheel (Alt);
- quick melee, zoom, and picking up or carrying bodies.

**Movement abilities, switched off:**
- double jump, charge jump, air hover, dodge and air dash;
- superhero landing, Kerenzikov slide, Sandevistan;
- Cyberpunk's own optical camo, terrain camo, sprint-shove and dash-shoot.

**Exceptions:** Steve's **Double Jump** perk gives V the double jump back, and his **Dodge** perk gives the dash and
air dash back.

**Keys:**
- F5 does not quick-save. Save from the pause menu or turn Cyberblock off first.
- G does not toggle walking, and K does not open crafting.
- 1–9, E, Q, T, /, the mouse buttons and the wheel go only to Minecraft.

**Camera:** it is frozen while a Minecraft screen is open.

---

## 8. Troubleshooting

### Minecraft does not appear in Cyberpunk
- Check the ReShade overlay (Home):
  - **Add-ons:** Cyberblock is listed and enabled;
  - **Home:** the Cyberblock effect is ticked.
  - If Cyberblock is missing, ReShade was installed without add-on support. Reinstall it with the add-on
    installer.
- Is Minecraft running and in the `cyberblock` world? Press F9 and watch for "Start Minecraft first".
- Is the Minecraft window minimized? Restore it, and leave it behind Cyberpunk.
- **Logs:**
  - `<Cyberpunk 2077>\red4ext\logs\Cyberblock.log` for the DLL and the scripts;
  - `mc\run\logs\latest.log` for Minecraft.

### Blocks show through walls, or vanish on the ground (ReShade depth)
Cyberblock reads Night City's depth buffer through ReShade's depth support.
- Tick **CyberblockDepthDebug**. Black near and white at 50 m, with red lines every 5 m that hug the floor, means the
  depth is right.
- **A flat or garbage picture:**
  1. In the Add-ons tab, open **Generic Depth** and pick the depth buffer whose size matches the render resolution
     (with DLSS it is smaller than the screen; that is fine).
  2. Try "copy depth buffer before clear operations" if it is offered.
- **Lines at the wrong distances:** adjust **Night City depth scale** in the Cyberblock effect. Only if that cannot
  fix it, change the preprocessor value `CB_HOST_DEPTH_MODE`: 0 (the default, right for 2.31's reversed infinite Z),
  1 or 2.
- **Fine tuning** in the Cyberblock effect:
  - **Depth bias** lets blocks resting on the ground show;
  - **Depth bias per grazing slope** helps far ground;
  - **Debug view** shows the depth difference.
- **Frame generation must stay off.** With it on, Minecraft lags behind and swims.

### Stutter, swimming or lag
- Turn frame generation off. Keep "Re-project to Cyberpunk's camera" on in the effect.
- Minecraft is capped at 120 fps and 2 GB of heap on purpose, so it leaves the GPU and memory to Cyberpunk.

### Memory and the page file
Running both games needs a lot of **Windows commit** (RAM plus page file), not just VRAM.
- **What happened before:** the crashes seen in development ("D3D12MA: Out of memory" in Cyberpunk, other programs
  closing) were Windows running out of commit (error 1455). The page file was fixed at 16 GB, next to another
  memory-hungry program. They were not a lack of VRAM.
- **What to do:**
  - set the page file to *System managed* (or at least 32 GB) under System → About → Advanced system settings →
    Performance → Advanced → Virtual memory;
  - close other heavy programs while playing.
- **Watching it:** `Cyberblock.log` logs system commit next to VRAM, and warns above 92 % of the limit.

### Something else
- **Mobs and NCPD do nothing to each other:** run `CyberblockPuppets.SetEnabled(false)` in CET. Mobs still hurt
  people, and NPCs then react to V.
- **Gliding jitters:** run `CyberblockFlight.SetCameraOnly(true)` in CET.
- **A wall of invisible collision is left in Night City:** run
  `CyberblockCollision.Reset(Game.GetPlayer():GetGame())` in CET. This also happens by itself on every reconnect.
- **You cannot pick a dialogue option, or a Cyberpunk key does nothing:** the key may be one Cyberblock forwards.
  Press F9 to turn Cyberblock off for the conversation, then back on.
- **The hack or skill-tree key does nothing:** another key may hold that binding. Rebind it in Minecraft's Controls
  to a letter, digit or F-key.
- **A hack says "Not enough RAM" or "You need a cyberdeck in your inventory":** keep the Cyberdeck in your inventory.
  RAM refills over time, or use a RAM Shard.
- **"Hold G and look at a target":** scan first. The lock lasts 5 s.

---

## 9. Uninstall

1. Close Cyberpunk.
2. Run:
   ```
   uv run tools/install.py --remove
   ```
   This deletes exactly the files the installer wrote: the DLL, the scripts, the shader, its header, and the ReShade
   preset if the installer created it.
   - A file you changed since the install is left in place, with a message.
   - Folders the installer created are removed if they are empty.
3. **Your saves** stay loadable without the mod. Cyberblock never stores anything in them, because everything is
   removed before each save. The backup zip from the first install is still in `%LOCALAPPDATA%\Cyberblock\save-backups`
   (or the `--backups` folder you used).
4. **The Minecraft world** lives in `mc\run\saves\cyberblock`. Delete that folder for a fresh world: a new starter kit,
   and no perks.
5. **ReShade** and the other frameworks (RED4ext, redscript, Codeware, CET) are separate installs. Remove them with
   their own uninstallers if you no longer want them.
