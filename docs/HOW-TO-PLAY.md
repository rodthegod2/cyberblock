# How to play Cyberblock

Cyberblock puts real Minecraft into Night City. Minecraft Java (26.3, with the Cyberblock Fabric mod) runs next to
Cyberpunk 2077 (2.31). Every frame, its picture is drawn into Cyberpunk at the right camera and depth.

The split is simple: **Cyberpunk moves V, Steve acts.** You walk, run, jump and drive with Cyberpunk's controls. You
place blocks, fight, throw, glide and hack with Minecraft's items, and those act on Night City's real people, cars and
devices.

> **Status (2026-10-03).** Layer 1 (the passthrough itself) has been verified in game. The first in-game session of
> layers 2 to 6 found bugs, and the second one (the re-test) found more: the elytra stuttered, hacks said "target lost",
> placed blocks floated a block above the street, a wind charge could not launch you, and there was no infinite skill
> points option. This version fixes all of them and adds **cyberware** (implants you install in ripperdoc slots). It
> passes every automated test, but none of it has been re-played in game yet: `docs/TESTING-PLAN.md` starts with the
> "Re-test 2" list. The fallback switches under [Troubleshooting](#8-troubleshooting) are still there for rough edges.
>
> **Quick links:** [the key table](#3-controls), [netrunning, step by step](#netrunning-step-by-step),
> [cyberware](#cyberware-u-and-y) and [free perks and `/cyberblock level`](#6-game-modes-and-the-starter-kit).

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
- **Updating:** after you pull a new version, close Cyberpunk, rebuild the DLL and run the same command again. It
  replaces its own files. Then restart Minecraft (`tools/play.py` rebuilds the mod): a Minecraft that was already
  running keeps the old mod.

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
   [Troubleshooting](#8-troubleshooting). Untick it again afterwards.
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

Then load a save and press **F9**. Cyberblock owns F9 in every state, so Cyberpunk's default QuickLoad on F9 never
fires. If you want a QuickLoad key, bind one in Cyberpunk's own key settings.

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

Cyberpunk keeps the focus, the movement keys and the camera. Cyberblock's DLL reads every key before Cyberpunk does and
decides who gets it: Cyberpunk, Minecraft, or (for Space and Shift) both on purpose. Every key has exactly one owner
in every situation, so nothing fires twice.

### Final key bindings (Cyberblock Active, V on foot)

| Key | Cyberpunk | Minecraft | Notes |
|---|---|---|---|
| **F9** | never reaches Cyberpunk (its default QuickLoad on F9 is blocked; rebind QuickLoad in Cyberpunk if wanted) | - | toggles Cyberblock on/off, in every state |
| **W A S D**, mouse look | move / look (V) | Steve follows V | swallowed while a Minecraft screen is open (V stands still) |
| **Space** | jump / double jump (the double jump needs the Reinforced Tendons implant) | "jump" signal: opens the elytra only while falling with >= 3 blocks of air below (with Reinforced Tendons installed: the 2nd airborne press); also steers a wind-charge launch into a glide | shared on purpose |
| **Left/Right Shift** | sprint | "sprint" signal: double-tap either Shift fires the Sandevistan implant, when you also own the Sandevistan Sprint perk | shared |
| **C**, **RCtrl** | crouch | Steve crouches with V | - |
| **LCtrl**, **Mouse4**, **Mouse5** | Cyberpunk's own (dodge/dash, scanner hold etc.; dodge works with Steve's Dodge perk) | - | no longer swallowed |
| **F** | interact / talk / dialogue choice 1 / exit vehicle | - | never bindable |
| **LMB / RMB / MMB** | (blocked combat) | attack / use / pick block | Shift/Ctrl held are passed as modifiers in Minecraft screens (Shift-click quick-move) |
| **Wheel** | - | hotbar scroll; while G held: choose quickhack chip | goes to Cyberpunk while a dialogue or interaction list with 2+ choices is shown |
| **1-9** | - | hotbar slots (in inventories: swap hovered stack) | 1-4 go to Cyberpunk while a dialogue or interaction list with 2+ choices is shown |
| **E** | - | inventory | goes to Cyberpunk (scroll choices) while 2+ choices are shown |
| **Q** | - | drop item (Ctrl+Q: whole stack) | goes to Cyberpunk while 2+ choices are shown |
| **T** | - (phone answer unavailable while Active: press F9 to take a call) | chat (real typing: letters, Enter, Backspace, arrows reach Minecraft while a screen is open) | - |
| **/** | - | command line | - |
| **X** | (healing hotkey, already blocked while Active) | swap item to offhand (NEW) | - |
| **G** (hold) | (walk toggle, suppressed) | scan / lock target 5 s; while held: wheel = pick chip, right-click = fire chip | Minecraft's own Quick Actions (also G in 26.3) is unbound automatically |
| **K** | (crafting, swallowed) | skill tree | - |
| **U** (NEW) | (swallowed) | Cyberware screen (install and remove implants); U again closes it | rebindable, like G and K |
| **Y** (NEW) | (swallowed; Cyberpunk's own Y, scene fast-forward and camera pitch reset, works again with F9 off) | tap: fires the selected implant (Sandevistan, Optical Camo, Projectile Launch System, Berserk); hold 0.4 s: switches the selected implant | rebindable. Tap = released within 8 client ticks (0.4 s) |
| **F5** | (QuickSave, swallowed) | ignored | dead key while Active |
| **Esc** | pause menu when no Minecraft screen is open | closes the open Minecraft screen (respects screens that refuse Esc) | - |
| **Tab, CapsLock, Alt, R, B, I, M, J, H, N, P, V, Z** | Cyberpunk's own (scanner/radial/reload/holster are blocked while Active; menus open and pause forwarding) | - | passed through |

### Context rules
- **A Cyberpunk dialogue or interaction list with 2 or more choices** (a conversation, or several prompts on one object such as "Pick up" / "Loot"): E, Q, 1-4 and the wheel go to Cyberpunk only, so
  you scroll and pick choices as usual (F picks the highlighted one). The mouse buttons and the other keys (5-9, G, T,
  ...) still go to Minecraft. A single-choice prompt ("[F] Talk") does not count: E still opens Steve's inventory.
- **A Minecraft screen is open** (inventory, chest, chat, skill tree, death screen): every key except F9 goes to
  Minecraft: the named keys plus typed text, Enter, Backspace, Tab, the arrows, Home/End, PgUp/PgDn and Delete. V
  stands still, the camera is frozen, and the mouse moves Minecraft's cursor.
- **A Cyberpunk menu is open** (pause menu, map, journal, Cyberpunk inventory), **V is in a vehicle**, or **Cyberblock
  is off:** nothing is forwarded except F9. The menus work exactly as in vanilla.
- Minecraft's own key settings do not matter for the keys above: Cyberpunk sends Minecraft the action (inventory,
  drop, swap, ...), not the key. Only the four Cyberblock keys below (Hack, Skill tree, Cyberware, Activate implant) are
  rebindable.

### In a Minecraft screen
- **Esc** closes it (the death screen stays: click Respawn). **E** also closes the inventory and containers, and
  **K** the skill tree.
- **Shift-click** moves a whole stack, **Q** drops the hovered item, **Ctrl+Q** the hovered stack, **1-9** swap the
  hovered stack with that hotbar slot, and **X** swaps it into the offhand.
- **Chat (T) and commands (/)** take real typing, accents and emoji included. Enter sends, Esc cancels. No more
  Alt+Tab.
- Closing a screen with E and holding W at once: if W goes down before Minecraft has reported the screen closed (about
  50-100 ms), V does not walk until you press W again. That is the rule that keeps keys from sticking in Cyberpunk.

### Rebinding the four Cyberblock keys: Hack (G), Skill tree (K), Cyberware (U), Activate implant (Y)
Minecraft → Options → Controls → Key Binds → category **Cyberblock**. The binding is sent to Cyberpunk at once.
- **Refused** (both sides; `shared/vectors.json`): W A S D C E Q T X F I M J H N B P V Z R, 1-9, F5, F9, Space, Shift,
  Ctrl, Esc. These are Cyberpunk's own keys or keys Cyberblock already forwards.
- **Allowed:** G K L O U Y 0 F1-F4 F6-F8 F10-F12.
- **A refused key** goes back to its own default (G, K, U or Y). **If any two of the four keys end up the same, all
  four go back to G, K, U and Y** (nothing is half-applied). Minecraft says so in chat (also after every reconnect).
  Cyberblock.log logs it once, and its binds line reads `binds: hack G, skills K, cyberware U, activate Y`.
- The keys the four actions use are taken from Cyberpunk. Move Skill tree off K and K opens Cyberpunk's crafting menu
  again; move Activate off Y and Y is Cyberpunk's own again (scene fast-forward, camera pitch reset); move Cyberware off U
  and U goes back to Cyberpunk too. Cyberpunk's walk toggle stays off while Active whichever key Hack uses.

---

## 4. The one health rule

**Steve's health is the only health.**
- While Cyberblock is on, V is invincible: on foot, and in a vehicle too, in every Minecraft game mode.
- Every hit V would have taken becomes damage to Steve: bullets, explosions, melee, falls, vehicles, fire and burning
  damage-over-time. The damage is scaled by V's maximum health. A hit worth a quarter of V's health takes a quarter of
  Steve's.
- In a car, crashes and gunfire on V's car go to Steve the same way (Cyberblock.log: "vehicle: V invulnerable in the
  vehicle, hits go to Steve"). Getting out hands over to the on-foot protection.
- An instant kill aimed at V (a scripted kill, a car destroyed with V inside) kills Steve instead of V.
- A fall reaches Steve once, from Cyberpunk's landing, and without a direction: no sideways knockback. Minecraft adds
  no fall damage of its own while it is moving Steve with V.
- Cyberpunk never shows a game over. If Steve dies, the Minecraft death screen appears: click **Respawn** with the
  mouse.
- The world keeps your inventory when you die (`keepInventory` is on).
- Implants can help (see [Cyberware](#cyberware-u-and-y)): Subdermal Armor makes Night City hits go through Steve's
  armor, Biomonitor heals him at 30 % health, and Second Heart restarts him once instead of dying (5 minute recharge).

---

## 5. What you can do, layer by layer

Every Night City person and car within 48 m exists in Minecraft as an invisible **proxy** with a matching hitbox
(people 0.6 × 1.8 m, cars 3 m wide so they are easy to hit). Between Cyberpunk's updates a proxy keeps moving at its
NPC's speed for up to 0.5 s, so it stays on a driving car. Minecraft weapons, mobs, leads and hooks hit those proxies.
The result is applied as real Cyberpunk damage or force, so ragdolls, NCPD heat, deaths and explosions are all native.
Every 5 s, Cyberblock.log says how many were sent ("npcs: sent H people + C cars; attached ...; left out: ...") and
the Minecraft log how many proxies exist ("proxies: H people, C cars").

### Layer 1: Minecraft in Night City
- Steve stands at V's feet. You see his hand, the hotbar and the HUD, drawn over Night City with correct depth:
  - placed blocks sit **on** Night City's floors;
  - Night City's walls hide them.
- **Ground alignment.** Night City's floors are at arbitrary heights, while Minecraft's blocks sit on whole numbers.
  Each Minecraft world therefore keeps one vertical offset (a multiple of 1/64 m, "k" in the logs):
  - It is chosen **automatically** the first time Cyberblock is active in that world while V stands on the ground. The
    floor under V's feet becomes a whole block boundary, so a block you place on the street sits on it, not about a
    block above it. New and existing worlds both do this once; the offset is saved in the world.
  - Other floors are rounded to the nearest block, so a floor of a different height can still be up to half a block
    off.
  - **`/cyberblock level`** (press **T**, type it, Enter) re-aligns to the ground V stands on right now. Do it
    standing on the floor you want to build on. Blocks you placed earlier move by less than 1 m. It refuses with
    "Cyberpunk is not connected, or Cyberblock is off" or "V must stand on the ground (not in the air, a vehicle or a
    scene)".
  - Minecraft's log says `ground alignment: k 63 -> 57 (offset ... m) at Cyberpunk z ...`, and Cyberblock.log says
    `ground alignment: Minecraft y = Cyberpunk z - ... (k 57/64)`.
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
  - boxes stay through saves (the save never contains them), so a Minecraft bridge does not vanish under V during an
    autosave;
  - after a restart or reconnect, Minecraft sends all your blocks again.
- **Explosions** become Cyberpunk explosions scaled by their power (TNT, creepers, firework rockets). Cars blow up
  and NCPD comes. An NPC is hurt once, by the Cyberpunk blast. NPCs hear it: within 10 m of V it reveals V, at 10-30 m
  it alerts them without giving V away, and further away it does not draw them to V.
- **Weapons:** melee weapons (swords, axes…), arrows, tridents and crossbows hurt people and cars. People ragdoll,
  and melee shoves cars. A kill drops its XP at Steve, however far away it happened.
- **Fire:** flint and steel, fire charges, lava, Fire Aspect and flaming arrows set people alight. Flint and steel or
  a fire charge on a car does nothing and is not used up. A person standing in lava is set alight once every 15 s.

### Layer 3: mobs against Night City
- Hostile Minecraft mobs (zombies, skeletons, creepers, spiders and the like) attack Night City's pedestrians as well
  as Steve. Spawn them with spawn eggs: natural spawning is off.
- They go for hostile NPCs (gangers) before passers-by, only hunt NPCs that stand on Night City ground Cyberblock has
  mapped, and never chase one more than 22 m from Steve.
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

### Layer 4: elytra, wind-charge launches and ender pearls
- **Opening the elytra:**
  1. Wear an elytra (the starter kit puts one on the chest).
  2. Get V falling with **at least 3 blocks of free air below V's feet**: step or jump off a roof, a balcony or a
     megabuilding ledge. Night City's ground counts as solid.
  3. Press **Space** while V falls. A Space at the top of an ordinary jump on flat ground never opens it.
  - With the **Reinforced Tendons** implant installed, the 1st Space in the air is V's double jump and the **2nd**
    opens the elytra.
  - A fall faster than 12 m/s for 0.3 s opens it on its own (same 3-block rule).
- **While gliding:** Minecraft physics fly Steve and V follows **every rendered frame**. The Cyberblock DLL moves V
  itself, a few milliseconds behind Minecraft's positions and smoothed between them, so the flight no longer steps once
  a second and the camera no longer jumps. Steer with the mouse as usual; **firework rockets** (right mouse) boost
  you. Collision mapping reaches further ahead the faster you fly.
- **Wind-charge launches** (new): a **wind charge** thrown at your own feet (look down and throw), another player's or a
  breeze's charge that bursts next to you, and the wind burst of a **mace** smash all throw Steve into the air with
  Minecraft physics, in survival and in creative (creative flying is switched off for the launch).
  - V follows every frame exactly as in a glide. The Minecraft log says `launch started at ... with ... m/s`, and
    Cyberblock.log says `flight: start (launch)`.
  - **Launch, then glide:** with an elytra on, 3 blocks of free air below and a fall faster than 1 m/s, press **Space**
    to open the elytra mid-launch. Nothing restarts: you simply keep flying.
  - The launch ends when Steve touches the ground or water, and V is put on the real floor there. A weak nudge that
    barely lifts Steve is not a launch.
  - **No launch** while V is held (a quest scene, a seated conversation or other workspot, a braindance, a moving
    elevator), in a vehicle or in a Cyberpunk menu: the charge still knocks people and cars back, but Steve stays with
    V.
- **When no glide or launch starts**, or it ends at once: V is in a vehicle, a Cyberpunk menu is open, or the game
  holds V (as above; swimming does not count). A flight Cyberpunk does not follow within 0.5 s is called off and Steve
  goes straight back to V (Minecraft log: "glide called off"; Cyberblock.log: "flight: ignored ... (<reason>)"). The
  next try is possible after 1 s.
  - **If V never moves at all**, the DLL could not set up its per-frame drive: Cyberblock.log has one
    `drive: disabled: <what> missing` line, and every flight is called off after 0.5 s.
- **Menus mid-flight:** opening the map or pause menu pauses the flight; Steve waits in the air and it carries on when
  the menu closes (the DLL freezes its timers meanwhile).
- **Walls:** V never passes through Night City. If Minecraft flies through a building it has not mapped yet, V stops
  at the wall ("flight: V stopped short of Night City geometry") and Steve comes back to V after landing.
- **Landing:** touch down on a roof or a street and V is put on the real floor there, within a frame of Steve's
  touchdown and never in mid-air; Cyberpunk movement takes over again. A very short flight, or one that ends within
  1.5 m of V, leaves V where it is ("flight: over, V stays where it is"). If Minecraft stops sending positions
  mid-air (a hitch longer than 1 s), V is held for a second and then put on the ground below ("flight: no samples,
  holding V").
- **Fall damage:**
  - Cyberpunk's fall damage is ignored while flying and for 2 s after landing, and V plays no hard or death landing in
    that window; Minecraft's own elytra rules apply (durability, crashing into walls);
  - it is always ignored under Slow Falling.
- **Ender pearls:** throw one (right mouse). Where it lands, V is teleported (and put on the Night City floor when the
  pearl came down within about a block of it, so V does not sink), with the same 2 s fall grace. Steve takes the usual pearl damage.
- **While V cannot be moved** (in a vehicle, or held by the game as above), pearls, gliding and launches leave V where
  V is: a pearl shows "Pearl blocked: V cannot be moved right now" and is given back (no damage, no teleport), no
  flight starts, and one already under way stops (with the same 2 s fall grace). This keeps quests from breaking.
- Thrown things (pearls, arrows, tridents, potions, wind charges, the fishing hook) also stop on Night City walls,
  because the walls on their predicted paths are filled in ahead of time.
- There is **no camera-only fallback** any more (`CyberblockFlight.SetCameraOnly` is gone): V is moved by the DLL, and
  Troubleshooting covers what to read when it does not move.

### Layer 5: interactive items
- **Lead (lasso):**
  - right-click a person or a car with a lead. It stays within 5 m (a person) or 8 m (a car) of Steve, and is dragged
    back when it strays. A person more than 0.5 m past the lead's length is tugged toward you (a short ragdoll at
    4-8 m/s, at most every 1.5 s) and survives it; a car is dragged;
  - right-click a fence post to tie it there instead;
  - right-click the tethered one again to let go.
  - The lead snaps if the target is pulled more than 12 m past that length for 2 s, or when it despawns.
- **Fishing rod:** cast at a person or a car, then reel in (right mouse again). It is yanked toward you: people at
  9 m/s with a small hop, cars at 7 m/s.
- **Wind charge:** the burst knocks back every person and car within about 2.4 m of it (measured to the closest point
  of the body, so a burst at the feet or the head counts). It causes no damage and no explosion, and the Demolitions
  perk does not widen it. A burst that reaches **Steve** (your own charge at your feet, someone else's, a breeze's, a
  mace smash) launches him, and V follows: see [layer 4](#layer-4-elytra-wind-charge-launches-and-ender-pearls).
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
  Infinite effects (for example `/effect give @s speed infinite`) come back on V after every save, quick or auto;
  leaving Active forgets them.

### Layer 6: Cyberpunk in Minecraft form

#### Netrunning, step by step
Netrunning is Minecraft's half of Cyberpunk's quickhacks: Minecraft owns the cyberdeck, the RAM, the chips and their
recharge; Cyberpunk runs the real quickhack (upload, VFX, damage, AI reaction, NCPD heat) with V as the hacker.

**Step 0: be ready.** Cyberblock Active on foot ("Cyberblock on" after F9). It works in survival and creative (RAM
costs apply in both), not in spectator, and not from a car (nothing is forwarded in a vehicle).

**Step 1: get a cyberdeck.** The Cyberdeck is an implant: it lives in the **Operating System** slot (see
[Cyberware](#cyberware-u-and-y)).
- In survival, the starter kit arrives by itself the first time you press F9 in a world ("Cyberblock starter kit
  received"). It **installs the Cyberdeck for you**, and it holds 2 **RAM Shards** and one of each of the 10
  **quickhack chips** in the main inventory (open it with **E**). It starts your RAM full (8/8).
- At any time: **T** (or **/**), type `/cyberblock kit`, Enter. Or craft a Cyberdeck (recipe below), or take one from
  the **Cyberblock** creative tab, then install it with **U**.
- **The deck counts** when it is installed in the Operating System slot, **or** when that slot is empty and a Cyberdeck
  is anywhere in your inventory (hotbar or main inventory). If the slot holds another implant (Sandevistan or Berserk)
  and the deck is not installed, you have no deck. Without one your maximum RAM is 0 and every chip says "No cyberdeck:
  install one in the Operating System slot (U) or carry one".

**Step 2: watch your RAM.** The cyan bar left of the hotbar, level with the experience bar, is labelled "RAM n/m".
- The maximum is 8 with a deck, plus 4 with the Extra RAM perk and plus 4 with the RAM Upgrade implant (up to 16). It
  regenerates 1 point every 3 s (every 1.5 s with Overclock).
- A **RAM Shard** in your hand plus right-click restores 4 at once ("RAM is already full" when it is). Losing the
  cyberdeck empties the RAM.

**Step 3: find your chips.** They stay in the main inventory; you do not have to move them, because the hack wheel
fires chips from anywhere in the inventory. Hover one (E) to read its quickhack, its cost ("Costs n RAM", 1 less with
Cheap Hacks) and how to use it. You can still put a favourite on the hotbar (E, hover it, press a number key).

**Step 4: scan.** Hold **G** (the Hack key) and put the crosshair on a person, a car or a device.
- While G is held, Minecraft asks Cyberpunk what is there about 5 times a second (once per second for the same
  target). A quick tap of G scans once.
- On a person or a car (their proxy is under the crosshair): that one. The pick uses the proxies' real positions, with
  about 0.3 m of leeway around a person and 0.5 m around a car, so you do not have to hit the exact pixel. Otherwise
  Cyberpunk scans what V looks at, or the target nearest the crosshair within about 4° and 48 m (**80 m with Kiroshi
  Optics**), which is how **devices** are scanned (cameras, vending machines, TVs, radios, arcades, traffic lights).
  People do not need a proxy for this.
- A tooltip appears right of the crosshair: name, faction, "Threat: None / Low / Medium / High / Extreme" (colour
  coded), an HP bar for people and cars, or "Device". A glowing box marks the target in Night City.
- A person behind a Night City wall is not scanned (log: "scan N: behind Night City").
- Every successful scan is logged as `scan N: ok <class> <8-digit id> (requested|look-at|near crosshair)`. That id is
  what the hack is then sent to; a real id has about 8 digits.

**Step 5: lock.** The last target a scan found stays **locked for 5 s**. Let go of G and the tooltip dims and counts
down "Locked 5..1 s". A scan that finds nothing (crosshair on the sky or a bare wall while G is held) drops the lock.

**Step 6: pick a chip and fire.** Two ways:
- **The hack wheel (easiest):** keep **G** held, roll the **mouse wheel** to pick a chip (the hotbar does not move
  while G is held; the tooltip shows "Chip: <name> (<n> RAM)" or "(recharging)"), then **right-click**. The wheel
  starts at the chip in your hand, else the first one you own, and remembers your pick.
- **From the hand:** hold a chip on the hotbar, scan with G (you may let go: the lock lasts 5 s) and right-click.

Minecraft then checks, in this order: a cyberdeck, a lock from the last 5 s, the right kind of target (the Redstone
Lever wants a device, every other chip a person or a car), and enough RAM. If all pass, it spends the RAM, starts the
chip's recharge (2 s; 10 s for Synapse Burnout and Cyberpsychosis) and Cyberpunk runs the hack:
- on a **person**: the quickhack itself, after its upload;
- on a **car**: the hack goes to its driver; an empty car fails with "no driver";
- with **Chain Hack**: the nearest other hackable living person within 8 m of the target gets the same hack.

A success plays a short beacon sound and Cyberblock.log says "hack <name> on <id>: ok". That answer comes when the
upload **starts**, not when the effect lands: Cyberpunk's upload bar is on the HUD that Cyberblock hides, so watch the
target instead. Long uploads (Synapse Burnout, Cyberpsychosis) take a few seconds, and if the target dies, despawns
or leaves first, the hack is lost and its RAM is not given back.

**Step 7: read the answer.** Messages appear above the hotbar. Every failure Cyberpunk reports gives the RAM back.

| Message | What to do |
|---|---|
| Hold G and look at a target | nothing is locked: scan first (the lock lasts 5 s) |
| No cyberdeck: install one in the Operating System slot (U) or carry one | install the deck with **U**, or empty the slot and carry one (step 1) |
| Not enough RAM: n of m | wait for it to regenerate, or use a RAM Shard |
| Still recharging | that chip is on its 2 s (or 10 s) recharge: pick another with the wheel |
| The Redstone Lever only works on devices | you locked a person or a car |
| This quickhack needs a person or an occupied car | you locked a device: use the Redstone Lever |
| Hack failed: no driver | the car is empty |
| Hack failed: not hackable (friendly / god mode / quest character / no quickhack actions / not a person) | Cyberpunk protects this one (friends, quest characters, story NPCs) |
| Hack failed: not hackable (V is in a scene) | finish the scene first |
| Hack failed: target gone | it really died or despawned (this used to appear for live targets because of an id bug, fixed in re-test 2: if you still get it on someone alive, check the scan line in Cyberblock.log, see Troubleshooting) |
| Hack failed: not hackable (no intersection / camera allows no hack / device allows no hack / not a device) | that device offers nothing to hack |
| Night City is not connected | Cyberpunk never answered (60 s, or 10 s with the link down) |

**Step 8: devices.** The **Redstone Lever** (1 RAM) is the device chip. Aim at the device itself, not at a person in
front of it, scan, and fire:
- a traffic light flips its whole intersection;
- a camera stops seeing V (it never takes over the view);
- vending machines, TVs, radios and arcades make a distraction;
- any other device runs the first quickhack it allows.

**Step 9: perks.** Press **K** and buy the **Intelligence** branch in order (XP levels: 3, 5, 8, 12): **Extra RAM**
(+4 maximum RAM), **Cheap Hacks** (every chip costs 1 RAM less, at least 1), **Chain Hack** (step 6) and **Overclock**
(RAM regenerates twice as fast). XP comes from kills in Night City (the orbs drop at Steve) or `/xp add @s 30 levels`. In creative, or after
`/cyberblock perks free on`, perks cost nothing (see [free perks](#6-game-modes-and-the-starter-kit)).

**A quick run:** F9 (the kit arrives) → hold G on a ganger → roll the wheel to "Redstone Charge" → right-click → let
go of G. The ganger is electrocuted and your RAM drops by 3.

#### The chips

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
| Cyberdeck | The netrunning implant (Operating System slot): needed to have RAM | 2 iron ingots, 2 redstone, glass pane, copper ingot, gold ingot |
| RAM Shard | +4 RAM, used up (stacks to 16) | lapis lazuli, redstone, glass pane |
| Mantis Blade | Sword: 7 damage, 1.8 attacks/s. An older item: the Mantis Blades implant replaces it, and it is no longer in the kit | iron sword, diamond, redstone, copper ingot |
| Gorilla Gauntlet | Fist weapon: 4 damage, knockback +2 (shoves cars). An older item: the Gorilla Arms implant replaces it, and it is no longer in the kit | iron block, leather, redstone, copper ingot |

All of them, and every implant below, are also in the **Cyberblock** creative tab.

#### Cyberware (U and Y)
Cyberware are implants you **install** instead of carrying. Each one goes into a ripperdoc slot of its own (custom
slots, not armor slots) and then works by itself, or when you press **Y**. Every implant has a Minecraft effect, with
particles and sounds, and where it makes sense the real Cyberpunk effect on V (time dilation, optical camo, a double
jump, an explosion).

**The screen (U).** Press **U** to open the Cyberware screen. **U** again or **Esc** closes it.
- **Left:** the eight slots, as boxes with one frame per place: Arms 1, Legs 1, Operating System 1, Nervous System 1,
  Integumentary 2, Eyes 1, Circulatory 2, Frontal Cortex 1.
- **Right:** the implants you can install. These are the ones in your inventory (in creative, all of them), 24 to a page.
- **Left-click** an implant on the right to install it, or an installed one on the left to take it out. **Right-click**
  an active implant to make it the one Y fires (a yellow Y marks it). Hover for its slot, what it does in Minecraft and
  in Night City, its cooldown and how to install it. You can also install by right-clicking the item in your hand.
- A full slot is replaced: the new implant goes in and the old one comes back to your inventory ("Installed <new>
  (returned <old>)").
- In survival, installing uses the item up and removing gives it back. In **creative**, installs are free and the
  screen lists every implant; cooldowns still apply.

**Firing (Y).** An implant is always on, fired with Y, or automatic.
- **Tap Y** (let go within 0.4 s) to fire the selected active implant. **Hold Y for 0.4 s** to switch to the next one
  ("Y fires <implant>"). With none installed Y says "No active implant installed"; a recharging one says "<implant> is
  cooling down (n s)".
- **The HUD** shows each active or automatic implant right of the hotbar as a 16-pixel icon with a thin bar under it:
  grey filling to cyan while it recharges, magenta while it runs, and a yellow frame on the one Y fires.
- Cooldowns count game time, are saved with the world, and still apply in creative.
- Cyberpunk's own Y (scene fast-forward, camera pitch reset) is swallowed while Cyberblock is active, and works again
  with F9 off.

**The 17 implants.** "Slot" is where it goes, "Kind" is always on / **Y** / automatic.

| Implant | Slot | Kind | In Minecraft | In Night City (V) |
|---|---|---|---|---|
| **Mantis Blades** | Arms | on | empty main hand: +6 attack damage, slower swings; red dust, a sweep and a strong-hit sound on every hit | your hits hurt people and cars as melee, as always |
| **Gorilla Arms** | Arms | on | empty hand: +4 damage, +2 knockback, slower swings; a gust and smash sounds | shoves people and cars hard (the same shove as the Gorilla Arms perk, never doubled) |
| **Monowire** | Arms | on | empty hand: +3 damage, +3 reach, slower swings; a line of electric sparks to the target | your hits land as melee |
| **Projectile Launch System** | Arms | **Y**, 8 s cooldown | fires along the crosshair up to 64 blocks (closer than 4 blocks: "Too close to fire", no cooldown): smoke and flames, crossbow and fire-charge sounds, a power 2.5 explosion | a real Cyberpunk explosion there: cars blow up, NCPD comes |
| **Reinforced Tendons** | Legs | on | 2 more blocks of safe fall; a jump in mid-air makes a ring of cloud and a gust | V's **double jump** |
| **Fortified Ankles** | Legs | on | 4 more blocks of safe fall; a charged jump (hold Space about half a second, then let go) throws dust and a gust | V's **charge jump** |
| **Lynx Paws** | Legs | on | Steve makes no step vibrations, and mobs notice you from half as far | people notice V from half as far (one effect, shared with the Silent Steps perk) |
| **Sandevistan** | Operating System | **Y**, 6 s on, 25 s cooldown (8 s and 18 s with the Sandevistan Sprint perk) | Speed II and the Minecraft world at half speed; cyan and magenta afterimages, beacon sounds | Night City slows to 20 %, V does not |
| **Berserk** | Operating System | **Y**, 8 s on, 40 s cooldown | Strength I, Resistance I and Jump Boost I; a ring of dust, an angry burst, a smash sound; hostile mobs within 3 blocks are knocked back | the jump boost reaches V. Cyberpunk's own berserk effect is used only if it proved safe, otherwise Minecraft only (Cyberblock.log: "berserk: Minecraft side only") |
| **Cyberdeck** | Operating System | on | your netrunning RAM (8 at most, see [netrunning](#netrunning-step-by-step)) | quickhacks run as before |
| **Kerenzikov** | Nervous System | automatic, 2 s on, 10 s cooldown | fires by itself when you draw a bow, crossbow or trident while V is in the air: the Minecraft world at half speed, a ring of sparks, a rising chime | Night City slows to 30 % |
| **Optical Camo** | Integumentary | **Y**, 10 s on, 30 s cooldown | Invisibility without particles (the icon shows), a portal-like shimmer; it breaks when you attack or fire the Launch System | V's optical camo, as with the Invisibility potion |
| **Subdermal Armor** | Integumentary | on | +6 armor and +2 toughness; a netherite-armor sound on install | protects against Night City damage, which goes through Steve's armor |
| **Kiroshi Optics** | Eyes | on | the scan reaches 80 m instead of 48; hostile proxies and hostile mobs within 32 blocks get a red outline | the scan range |
| **Biomonitor** | Circulatory | automatic, 90 s cooldown | at 30 % health or less: heals 8 (4 hearts) and Regeneration I for 6 s; hearts, a chime and "Biomonitor: emergency heal" | - |
| **Second Heart** | Circulatory | automatic, 5 minute cooldown | instead of dying: full health, Regeneration II for 5 s, Absorption I for 30 s and the totem animation ("Second Heart restarted you"). It does not save you from `/kill` or the void. The cooldown is saved | - |
| **RAM Upgrade** | Frontal Cortex | on | +4 maximum RAM (adds to the Extra RAM perk) | - |

**Slot rules worth knowing:**
- **One Operating System implant.** Sandevistan, Berserk and the Cyberdeck share one slot. Installing Sandevistan over
  the deck sends the deck back to your bag, but then your RAM is 0 until the deck is back in (or the slot is empty and
  the deck is in your inventory).
- One arm implant at a time. Integumentary (Optical Camo and Subdermal Armor fit together) and Circulatory (Biomonitor
  and Second Heart) hold two each.
- Arm implants work only while your main hand is **empty**.

**Perks that upgrade implants** (the skill tree, K):
- **Double Jump** adds 4 blocks of safe fall to any legs implant. V's double jump itself now comes from Reinforced
  Tendons only.
- **Sandevistan Sprint** makes the Sandevistan implant run 8 s with an 18 s cooldown (6 s and 25 s without it), and
  lets a double-tap of Shift fire it. It needs the implant.
- **Optical Camo** re-cloaks you after 2 s crouched and still. It needs the implant.
- **Gorilla Arms** and the Gorilla Arms implant give the same shove, not two. **Silent Steps** and Lynx Paws give the
  same one visibility effect. **Extra RAM** and RAM Upgrade add up (+4 each).
- **Migration:** a character that already owns the Double Jump, Sandevistan Sprint or Optical Camo perks receives
  the matching implant items once, never installed for you: "Your perks now need cyberware: you received <items>.
  Install them with U".

**Getting implants:**
- **Craft** them (all shapeless; every recipe also needs 1 redstone and 1 copper ingot). The Cyberdeck recipe is in
  the table above.

  | Implant | Plus |
  |---|---|
  | Mantis Blades | 2 diamonds, 2 iron ingots |
  | Gorilla Arms | iron block, piston |
  | Monowire | string, lightning rod, iron ingot |
  | Projectile Launch System | TNT, crossbow, iron ingot |
  | Reinforced Tendons | 2 slime balls, rabbit's foot |
  | Fortified Ankles | piston, 2 iron ingots |
  | Lynx Paws | 2 white wool, rabbit hide |
  | Sandevistan | clock, sugar, gold ingot |
  | Berserk | blaze powder, gold ingot, iron ingot |
  | Kerenzikov | clock, feather |
  | Optical Camo | fermented spider eye, glass pane |
  | Subdermal Armor | 3 iron ingots, leather |
  | Kiroshi Optics | spyglass, eye of ender |
  | Biomonitor | glistering melon slice, glass bottle |
  | Second Heart | golden apple, redstone block, iron ingot |
  | RAM Upgrade | 2 RAM Shards, gold ingot |

- **The starter kit** (survival, once per world; `/cyberblock kit` again): it installs the Cyberdeck, Kiroshi Optics,
  Reinforced Tendons and Biomonitor, and puts Sandevistan, Optical Camo and the Projectile Launch System in the main
  inventory for you to install with **U**. An implant whose slot is already full goes to the main inventory instead.
- **Creative:** the **Cyberblock** creative tab has every implant, and the screen installs any of them for free.

#### Skill tree (K)
Press **K** to open the skill tree: five branches of four perks.
- **Buying:** click a perk to buy it with **XP levels**. Tier 1 / 2 / 3 / 4 cost 3 / 5 / 8 / 12 levels, and each
  tier needs the one above it in its branch. Hover a perk to see its cost, what it needs, and its Minecraft and
  Cyberpunk effects. **In creative, or with `/cyberblock perks free on`, perks are free:** the tree says "Perks are
  free" at the top right, greys the costs, and buying says "Perk unlocked (free): <perk>" (the tier order still
  applies).
- **Earning XP:** people and cars that die from your attacks in Night City drop XP orbs: 5 points, or 10 for a
  hostile. Vanilla XP works too.

| Branch | Perk (tier) | In Minecraft | In Cyberpunk |
|---|---|---|---|
| **Body** | Iron Skin (1) | +4 hearts of max health | — |
| | Gorilla Arms (2) | melee shoves people ×2 and cars ×3 as hard (the Gorilla Arms implant gives the same shove, not a second one) | the shove lands in Night City |
| | Painkiller (3) | natural regeneration twice as fast | — |
| | Juggernaut (4) | full knockback resistance, also against Night City hits | — |
| **Reflexes** | Double Jump (1) | upgrades your legs implants: 4 more blocks of safe fall (with any legs implant) | — (V's double jump comes from Reinforced Tendons) |
| | Sandevistan Sprint (2) | upgrades the Sandevistan implant: 8 s of it and an 18 s cooldown (6 s and 25 s without the perk); double-tap Shift (either one) fires it | Night City slows to 20 %, V does not |
| | Quick Draw (3) | bows and crossbows charge 30 % faster | — |
| | Dodge (4) | — | V can dash and air dash |
| **Technical** | Tinkerer (1) | TNT and arrow recipes make twice as many | — |
| | Smart Arrows (2) | your arrows home onto people and cars (20° cone, 32 m) | — |
| | Field Medic (3) | food and healing potions heal 50 % more | — |
| | Demolitions (4) | your explosions (your TNT, creepers you lit) are 1.5× as big | so are their blasts in Night City |
| **Intelligence** | Extra RAM (1) | +4 max RAM (adds to the RAM Upgrade implant) | — |
| | Cheap Hacks (2) | quickhacks cost 1 RAM less (at least 1) | — |
| | Chain Hack (3) | — | a hack also hits the nearest other person within 8 m of the target |
| | Overclock (4) | RAM regenerates twice as fast | — |
| **Cool** | Optical Camo (1) | upgrades the Optical Camo implant: crouch and stand still for 2 s and it cloaks you again (Invisibility for 10 s, renewed while you stay still); it needs the implant | V's optical camo |
| | Headhunter (2) | arrow hits near a person's head deal double damage | — |
| | Cold Blood (3) | Speed I for 10 s after a kill in Night City | — |
| | Silent Steps (4) | mobs notice you from half as far (Lynx Paws gives the same, not a second halving) | people notice V from half as far |

Perks are stored on Steve in the Minecraft world. They survive death and are sent to Cyberpunk every time the two
connect.

---

## 6. Game modes and the starter kit

Switch modes with Minecraft's `/gamemode` (cheats are on in the `cyberblock` world).

- **Survival** (default):
  - Steve can die and respawns the Minecraft way;
  - hacks cost RAM, and perks cost XP levels (unless free perks are on, below);
  - in a car, V stays invincible and the hits go to Steve (see [the health rule](#4-the-one-health-rule)).
- **Creative:**
  - Steve cannot be hurt, so neither can V, on foot or in a car;
  - **perks are free** and implants install for free; RAM costs and implant cooldowns still apply.
- **Adventure / spectator:** vanilla rules. In adventure you cannot place or break blocks. A spectator takes no
  damage.

**Free perks (infinite skill points).** To try the whole skill tree without grinding levels:
- In **creative**, every perk is free: press **K** and click.
- In **survival**, `/cyberblock perks free on` makes every perk free, at 0 levels, and `/cyberblock perks free off`
  makes them cost levels again. `/cyberblock perks free` alone tells you which it is. The setting is stored on Steve in
  that Minecraft world (per world), and survives death and restarts. With it off in creative the message says "Free
  perks off, but perks stay free while you are in creative".
- Free only removes the level cost: a perk whose tier above is not bought yet is still locked, and one you own is not
  bought twice.

**Ground alignment.** Cyberblock aligns the world's blocks to the street the first time it is active in a world, and
`/cyberblock level` does it again on demand: see [layer 1](#layer-1-minecraft-in-night-city).

**Starter kit.** The first time you turn Cyberblock on in survival, Steve gets the kit (once per world).
`/cyberblock kit` hands it out again at any time.
- **Hotbar**, keys 1–9: iron sword, bow, stone, TNT, flint and steel, ender pearls, firework rockets, leads, fishing
  rod. The arrows are in the main inventory; the bow finds them there.
- **Offhand:** the shield. Hold right mouse with the sword (or an empty hand) to raise it.
- **Chest:** the elytra.
- **Main inventory (E):** everything else, including the quickhack chips and the implants that were not installed
  (Sandevistan, Optical Camo, the Projectile Launch System). Chips work from there with the hack wheel (hold G, wheel,
  right-click); see [netrunning](#netrunning-step-by-step).
- **Installed for you** (U shows them): the Cyberdeck, Kiroshi Optics, Reinforced Tendons and Biomonitor; see
  [Cyberware](#cyberware-u-and-y).
- RAM is filled to the maximum whenever the kit is handed out.
- A slot that is already taken sends its kit item to the main inventory instead. A stack Steve already has is topped
  up. Whatever does not fit drops at his feet.

| Group | Items |
|---|---|
| Cyber | 2 RAM Shards, one of each of the 10 quickhack items; implants: Cyberdeck, Kiroshi Optics, Reinforced Tendons, Biomonitor (installed, else in the bag) and Sandevistan, Optical Camo, Projectile Launch System (in the bag) |
| Building | 64 stone, 64 glass, 8 TNT, flint and steel |
| Weapons | bow, 32 arrows, shield, iron sword |
| Layer 5 items | 2 leads, fishing rod, 8 wind charges, 8 ender pearls |
| Flight | elytra, 16 firework rockets |
| Potions | Swiftness, Invisibility, Leaping |
| Spawn eggs | 4 zombie, 2 skeleton, 2 creeper |

### Commands
Press **T** (chat) or **/** (command line) and type right in Cyberpunk: while a Minecraft screen is open, the text you
type, Enter, Backspace, Delete, the arrows, Home/End and Tab all go to Minecraft, accents and emoji included. Enter
sends, Esc cancels. V stands still meanwhile. (Cyberblock.log notes "typing reaches Minecraft's screens" the first
time.)

Useful commands: `/cyberblock kit`, `/cyberblock level` (re-align the ground to where V stands),
`/cyberblock perks free on|off` (free skill points), `/gamemode creative`, `/gamemode survival`,
`/xp add @s 30 levels`, `/effect clear @s`.

---

## 7. What Cyberpunk stops doing while Cyberblock is active

All of this applies **on foot, while active**. Getting into a car (where only V's invincibility stays), pressing F9,
or "Minecraft lost" restores everything. Before every save, all of it is taken off and put back afterwards, so your saves stay vanilla.

**V:**
- V is invincible (Steve takes the damage) and invisible: the body meshes are hidden, and Steve stands there instead.
- Weapons are holstered.
- The Cyberpunk HUD is hidden on foot, except while an interaction prompt (F: doors, talking, getting out of a car) or
  a dialogue choice list is up; it hides again once they are gone. The 2 s transition notifications still show.

**Blocked:**
- combat: guns, melee, Cyberpunk's own cyberware arms, grenades and gadgets (Cyberblock's own implants replace them:
  see [Cyberware](#cyberware-u-and-y));
- Cyberpunk's own quickhacks, the phone, healing items (X), the scanner (Tab) and the weapon/radial wheel (Alt);
- quick melee, zoom, and picking up or carrying bodies.

**Movement abilities, switched off:**
- double jump, charge jump, air hover, dodge and air dash;
- superhero landing, Kerenzikov slide, Sandevistan;
- Cyberpunk's own optical camo, terrain camo, sprint-shove and dash-shoot.

**Exceptions:** the **Reinforced Tendons** implant gives V the double jump back, **Fortified Ankles** the charge
jump, and Steve's **Dodge** perk the dash and air dash. Sandevistan, Kerenzikov and Optical Camo run only through
Cyberblock's own implants, which slow Night City or cloak V when you fire them. (Steve's Double Jump perk no longer
gives V a double jump: it upgrades the legs implants.)

**Keys** (the full table is in [Controls](#3-controls)):
- F5 does not quick-save. Save from the pause menu or turn Cyberblock off first. F9 never quick-loads, in any state.
- G does not toggle walking. K does not open crafting while it is the skill-tree key (the default): rebind the skill
  tree to another key and K opens Cyberpunk's crafting menu again.
- U (Cyberware screen) and Y (activate implant) never reach Cyberpunk while Active. Cyberpunk's Y (scene fast-forward,
  camera pitch reset) works with Cyberblock off, or once Activate is rebound.
- 1–9, E, Q, T, X, /, the left/right/middle mouse buttons and the wheel go only to Minecraft, except that E, Q, 1–4
  and the wheel go to Cyberpunk while a dialogue offers 2 or more choices.
- T cannot answer the phone while Active: press F9 to take a call, then F9 again.
- Mouse 4 and 5 and Left Ctrl stay Cyberpunk's (dodge, scanner hold, whatever you bound there).

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
- **A glide or launch leaves V standing still:** open Cyberblock.log and look for `drive: disabled: <what> missing`
  (the DLL could not find a game function for its per-frame drive; send the line) or `drive: ready (...)` (it works).
  `flight: stopped, V is held, in a vehicle or Cyberblock is off` and `flight: ignored ... (<reason>)` mean the game
  holds V. The `flight: N frames, M samples (median spacing ... ms ...)` line after each flight shows how smooth it
  was: a median spacing around 16 ms is a frame per sample.
- **Blocks sit a little above or below the floor somewhere else:** stand on that floor and run `/cyberblock level`.
  Blocks everywhere one block above the street means the alignment never ran: check Cyberblock.log for
  `ground alignment: Minecraft y = Cyberpunk z - ...` and the Minecraft log for `ground alignment: k ... -> ...`, and
  that V was standing on the ground when you first activated.
- **A wind charge does not launch Steve:** the burst has to reach him (at his feet), V must not be held or in a
  vehicle, and the kick must be strong enough; the Minecraft log says `launch started at ...` when it works.
- **A wall of invisible collision is left in Night City:** run
  `CyberblockCollision.Reset(Game.GetPlayer():GetGame())` in CET. This also happens by itself on every reconnect.
- **You cannot pick a dialogue option:** with 2 or more choices on screen, E/Q or the wheel scroll them, 1–4 pick
  directly and F confirms. A Minecraft screen left open covers them: close it with Esc. If a conversation still
  does not react, press F9 to turn Cyberblock off for it, then back on.
- **A Cyberpunk key does nothing:** check [the key table](#3-controls); the key may be one Minecraft owns while
  Active.
- **The elytra does not open:** V must be falling with 3 blocks of free air below, not held by the game and not in a
  menu (see [layer 4](#layer-4-elytra-wind-charge-launches-and-ender-pearls)). Cyberblock.log says why a glide was ignored
  ("flight: ignored, V is in a vehicle or held (<reason>)"); the Minecraft log shows "glide called off".
- **Weapons, leads and mobs ignore people:** look at the 5 s lines "npcs: sent H people + C cars; ... left out: ..."
  in Cyberblock.log and "proxies: H people, C cars" in the Minecraft log. The left-out reasons (dead, defeated,
  mounted, puppet, detached, not_npc, over_cap) say why. Scanning and quickhacks on people do not need proxies.
- **The hack, skill-tree, Cyberware or Activate key does nothing:** rebind it in Minecraft's Controls to an allowed key
  (G K L O U Y 0 F1-F4 F6-F8 F10-F12). A refused key goes back to its default, and if any two of the four clash all four
  go back to G K U Y; chat says so, and Cyberblock.log's `binds: hack G, skills K, cyberware U, activate Y` line shows
  what is in force.
- **A hack says "Not enough RAM" or "No cyberdeck":** install the Cyberdeck in the Operating System slot (U), or empty
  that slot and carry the deck. RAM refills over time, or use a RAM Shard. More messages:
  [netrunning step 7](#netrunning-step-by-step).
- **A hack says "target gone" on someone alive:** read the scan line in Cyberblock.log, `scan N: ok <class> <id>
  (...)`. A real id has about 8 digits (for example 10019101). A much longer id (such as 2668059858640) or 0 means the
  scripts hashed the wrong thing, which re-test 2 fixed; send the log. "Target gone" now only means dead or despawned.
- **Y says "No active implant installed" or does nothing:** open the Cyberware screen (U), install a Y implant
  (Sandevistan, Berserk, Optical Camo, Projectile Launch System) and right-click it to select it. Only one Operating
  System implant fits at a time.
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
