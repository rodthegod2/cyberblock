# How to play Cyberblock

Cyberblock puts real Minecraft into Night City. Minecraft Java (26.3, with the Cyberblock Fabric mod) runs next to
Cyberpunk 2077 (2.31). Every frame, its picture is drawn into Cyberpunk at the right camera and depth.

The split is simple: **Cyberpunk moves V, Steve acts.** You walk, run, jump and drive with Cyberpunk's controls. You
place blocks, fight, throw, glide and hack with Minecraft's items, and those act on Night City's real people, cars and
devices.

> **Status (2026-10-03).** Layer 1 (the passthrough itself) has been verified in game. The first in-game session of
> layers 2 to 6 found bugs (the elytra, the NPC list, key conflicts, netrunning). This version fixes them and passes
> every automated test, but the fixes have **not been re-played in game yet**: `docs/TESTING-PLAN.md` starts with the
> re-test list. The fallback switches under [Troubleshooting](#troubleshooting) are still there for rough edges.
>
> **Quick links:** [the key table](#3-controls) and [netrunning, step by step](#netrunning-step-by-step).

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
| **Space** | jump / double jump | "jump" signal: opens the elytra only while falling with >= 3 blocks of air below (with the Double Jump perk: the 2nd airborne press) | shared on purpose |
| **Left/Right Shift** | sprint | "sprint" signal: double-tap either Shift = Sandevistan Sprint perk | shared |
| **C**, **RCtrl** | crouch | Steve crouches with V | - |
| **LCtrl**, **Mouse4**, **Mouse5** | Cyberpunk's own (dodge/dash, scanner hold etc.; dodge works with Steve's Dodge perk) | - | no longer swallowed |
| **F** | interact / talk / dialogue choice 1 / exit vehicle | - | never bindable |
| **LMB / RMB / MMB** | (blocked combat) | attack / use / pick block | Shift/Ctrl held are passed as modifiers in Minecraft screens (Shift-click quick-move) |
| **Wheel** | - | hotbar scroll; while G held: choose quickhack chip | goes to Cyberpunk while a dialogue with 2+ choices is shown |
| **1-9** | - | hotbar slots (in inventories: swap hovered stack) | 1-4 go to Cyberpunk while a dialogue with 2+ choices is shown |
| **E** | - | inventory | goes to Cyberpunk (scroll choices) in a 2+ choice dialogue |
| **Q** | - | drop item (Ctrl+Q: whole stack) | goes to Cyberpunk in a 2+ choice dialogue |
| **T** | - (phone answer unavailable while Active: press F9 to take a call) | chat (real typing: letters, Enter, Backspace, arrows reach Minecraft while a screen is open) | - |
| **/** | - | command line | - |
| **X** | (healing hotkey, already blocked while Active) | swap item to offhand (NEW) | - |
| **G** (hold) | (walk toggle, suppressed) | scan / lock target 5 s; while held: wheel = pick chip, right-click = fire chip | Minecraft's own Quick Actions (also G in 26.3) is unbound automatically |
| **K** | (crafting, swallowed) | skill tree | - |
| **F5** | (QuickSave, swallowed) | ignored | dead key while Active |
| **Esc** | pause menu when no Minecraft screen is open | closes the open Minecraft screen (respects screens that refuse Esc) | - |
| **Tab, CapsLock, Alt, R, B, I, M, J, H, N, P, V, Z** | Cyberpunk's own (scanner/radial/reload/holster are blocked while Active; menus open and pause forwarding) | - | passed through |

### Context rules
- **A Cyberpunk dialogue or choice list with 2 or more choices:** E, Q, 1-4 and the wheel go to Cyberpunk only, so
  you scroll and pick choices as usual (F picks the highlighted one). The mouse buttons and the other keys (5-9, G, T,
  ...) still go to Minecraft. A single-choice prompt ("[F] Talk") does not count: E still opens Steve's inventory.
- **A Minecraft screen is open** (inventory, chest, chat, skill tree, death screen): every key except F9 goes to
  Minecraft: the named keys plus typed text, Enter, Backspace, Tab, the arrows, Home/End, PgUp/PgDn and Delete. V
  stands still, the camera is frozen, and the mouse moves Minecraft's cursor.
- **A Cyberpunk menu is open** (pause menu, map, journal, Cyberpunk inventory), **V is in a vehicle**, or **Cyberblock
  is off:** nothing is forwarded except F9. The menus work exactly as in vanilla.
- Minecraft's own key settings do not matter for the keys above: Cyberpunk sends Minecraft the action (inventory,
  drop, swap, ...), not the key. Only the two Cyberblock keys below are rebindable.

### In a Minecraft screen
- **Esc** closes it (the death screen stays: click Respawn). **E** also closes the inventory and containers, and
  **K** the skill tree.
- **Shift-click** moves a whole stack, **Q** drops the hovered item, **Ctrl+Q** the hovered stack, **1-9** swap the
  hovered stack with that hotbar slot, and **X** swaps it into the offhand.
- **Chat (T) and commands (/)** take real typing, accents and emoji included. Enter sends, Esc cancels. No more
  Alt+Tab.
- Closing a screen with E and holding W at once: if W goes down before Minecraft has reported the screen closed (about
  50-100 ms), V does not walk until you press W again. That is the rule that keeps keys from sticking in Cyberpunk.

### Rebinding the Hack (default G) and Skill tree (default K) keys
Minecraft → Options → Controls → Key Binds → category **Cyberblock**. The binding is sent to Cyberpunk at once.
- **Refused** (both sides; `shared/vectors.json`): W A S D C E Q T X F I M J H N B P V Z R, 1-9, F5, F9, Space, Shift,
  Ctrl, Esc. These are Cyberpunk's own keys or keys Cyberblock already forwards.
- **Allowed:** G K L O U Y 0 F1-F4 F6-F8 F10-F12.
- If both keys resolve to the same key, or to a refused one, they fall back to G/K, and Minecraft says so in chat
  (also after every reconnect). Cyberblock.log logs it once.
- The keys the two actions use are taken from Cyberpunk. Move Skill tree off K and K opens Cyberpunk's crafting menu
  again; Cyberpunk's walk toggle stays off while Active whichever key Hack uses.

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

### Layer 4: elytra and ender pearls
- **Opening the elytra:**
  1. Wear an elytra (the starter kit puts one on the chest).
  2. Get V falling with **at least 3 blocks of free air below V's feet**: step or jump off a roof, a balcony or a
     megabuilding ledge. Night City's ground counts as solid.
  3. Press **Space** while V falls. A Space at the top of an ordinary jump on flat ground never opens it.
  - With Steve's **Double Jump** perk, the 1st Space in the air is V's double jump and the **2nd** opens the elytra.
  - A fall faster than 12 m/s for 0.3 s opens it on its own (same 3-block rule).
- **While gliding:** Minecraft physics fly Steve and V follows every frame. Steer with the mouse as usual;
  **firework rockets** (right mouse) boost you. Collision mapping reaches further ahead the faster you fly.
- **When no glide starts**, or it ends at once: V is in a vehicle, a Cyberpunk menu is open, or the game holds V (a
  quest scene of tier 2-5, a seated conversation or other workspot, a braindance, a moving elevator; swimming does not
  count). A glide Cyberpunk does not follow within 0.5 s is called off and Steve goes straight back to V
  (Minecraft log: "glide called off"; Cyberblock.log: "flight: ignored ... (<reason>)"). The next try is possible after
  1 s.
- **Menus mid-glide:** opening the map or pause menu pauses the glide; Steve waits in the air and it carries on when
  the menu closes.
- **Walls:** V never passes through Night City. If Minecraft flies through a building it has not mapped yet, V stops
  at the wall ("flight: V stopped short of Night City geometry") and Steve comes back to V after landing.
- **Landing:** touch down on a roof or a street and V is put there, on the real floor; Cyberpunk movement takes over
  again. If Minecraft stops sending positions mid-air (a hitch longer than 1 s), V is put on the ground below.
- **Fall damage:**
  - Cyberpunk's fall damage is ignored while gliding and for 2 s after landing; Minecraft's own elytra rules apply
    (durability, crashing into walls);
  - it is always ignored under Slow Falling.
- **Ender pearls:** throw one (right mouse). Where it lands, V is teleported, with the same 2 s fall grace. Steve
  takes the usual pearl damage.
- **While V cannot be moved** (in a vehicle, or held by the game as above), pearls and gliding leave V where V is: a
  pearl shows "Pearl blocked: V cannot be moved right now" and is given back (no damage, no teleport), no glide starts,
  and a glide already under way stops (with the same 2 s fall grace). This keeps quests from breaking.
- Thrown things (pearls, arrows, tridents, potions, wind charges, the fishing hook) also stop on Night City walls,
  because the walls on their predicted paths are filled in ahead of time.
- **Fallback** if the per-frame flight jitters: `CyberblockFlight.SetCameraOnly(true)` in CET. Then only the camera
  flies, and V is placed at the landing spot. `CyberblockFlight.SetCameraOnly(false)` switches back.

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
  perk does not widen it.
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

**Step 1: get a cyberdeck.**
- In survival, the starter kit arrives by itself the first time you press F9 in a world ("Cyberblock starter kit
  received"). It holds the **Cyberdeck**, 2 **RAM Shards** and one of each of the 10 **quickhack chips**, all in the
  main inventory (open it with **E**), and it starts your RAM full (8/8).
- At any time: **T** (or **/**), type `/cyberblock kit`, Enter. Or craft a Cyberdeck (recipe below), or take one from
  the **Cyberblock** creative tab.
- The deck only has to be somewhere in your inventory (hotbar or main inventory). Without it your maximum RAM is 0 and
  every chip says "You need a cyberdeck in your inventory".

**Step 2: watch your RAM.** The bar above the hotbar reads "RAM n/m".
- The maximum is 8, or 12 with the Extra RAM perk. It regenerates 1 point every 3 s (every 1.5 s with Overclock).
- A **RAM Shard** in your hand plus right-click restores 4 at once ("RAM is already full" when it is). Losing the
  cyberdeck empties the RAM.

**Step 3: find your chips.** They stay in the main inventory; you do not have to move them, because the hack wheel
fires chips from anywhere in the inventory. Hover one (E) to read its quickhack, its cost ("Costs n RAM", 1 less with
Cheap Hacks) and how to use it. You can still put a favourite on the hotbar (E, hover it, press a number key).

**Step 4: scan.** Hold **G** (the Hack key) and put the crosshair on a person, a car or a device.
- While G is held, Minecraft asks Cyberpunk what is there about 5 times a second (once per second for the same
  target). A quick tap of G scans once.
- On a person or a car (their proxy is under the crosshair): that one. Otherwise Cyberpunk scans what V looks at, or
  the target nearest the crosshair within about 4° and 48 m, which is how **devices** are scanned (cameras, vending
  machines, TVs, radios, arcades, traffic lights). People do not need a proxy for this.
- A tooltip appears right of the crosshair: name, faction, "Threat: None / Low / Medium / High / Extreme" (colour
  coded), an HP bar for people and cars, or "Device". A glowing box marks the target in Night City.
- A person behind a Night City wall is not scanned (log: "scan N: behind Night City").

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

A success plays a short beacon sound and Cyberblock.log says "hack <name> on <id>: ok".

**Step 7: read the answer.** Messages appear above the hotbar. Every failure Cyberpunk reports gives the RAM back.

| Message | What to do |
|---|---|
| Hold G and look at a target | nothing is locked: scan first (the lock lasts 5 s) |
| You need a cyberdeck in your inventory | get one (step 1) |
| Not enough RAM: n of m | wait for it to regenerate, or use a RAM Shard |
| Still recharging | that chip is on its 2 s (or 10 s) recharge: pick another with the wheel |
| The Redstone Lever only works on devices | you locked a person or a car |
| This quickhack needs a person or an occupied car | you locked a device: use the Redstone Lever |
| Hack failed: no driver | the car is empty |
| Hack failed: not hackable (friendly / god mode / quest character / no quickhack actions / not a person) | Cyberpunk protects this one (friends, quest characters, story NPCs) |
| Hack failed: not hackable (V is in a scene) | finish the scene first |
| Hack failed: target gone | it died or despawned |
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
(RAM regenerates twice as fast). XP comes from kills in Night City (the orbs drop at Steve) or `/xp add @s 30 levels`.

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
  - in a car, V stays invincible and the hits go to Steve (see [the health rule](#4-the-one-health-rule)).
- **Creative:**
  - Steve cannot be hurt, so neither can V, on foot or in a car;
  - RAM and perk costs still apply. Give yourself levels with `/xp add @s 100 levels`.
- **Adventure / spectator:** vanilla rules. In adventure you cannot place or break blocks. A spectator takes no
  damage.

**Starter kit.** The first time you turn Cyberblock on in survival, Steve gets the kit (once per world).
`/cyberblock kit` hands it out again at any time.
- **Hotbar**, keys 1–9: iron sword, bow, stone, TNT, flint and steel, ender pearls, firework rockets, leads, fishing
  rod. The arrows are in the main inventory; the bow finds them there.
- **Offhand:** the shield. Hold right mouse with the sword (or an empty hand) to raise it.
- **Chest:** the elytra.
- **Main inventory (E):** everything else, including the Cyberdeck and the quickhack chips. Chips work from there
  with the hack wheel (hold G, wheel, right-click); see [netrunning](#netrunning-step-by-step).
- RAM is filled to the maximum whenever the kit is handed out.
- A slot that is already taken sends its kit item to the main inventory instead. A stack Steve already has is topped
  up. Whatever does not fit drops at his feet.

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
Press **T** (chat) or **/** (command line) and type right in Cyberpunk: while a Minecraft screen is open, the text you
type, Enter, Backspace, Delete, the arrows, Home/End and Tab all go to Minecraft, accents and emoji included. Enter
sends, Esc cancels. V stands still meanwhile. (Cyberblock.log notes "typing reaches Minecraft's screens" the first
time.)

Useful commands: `/cyberblock kit`, `/gamemode creative`, `/gamemode survival`, `/xp add @s 30 levels`,
`/effect clear @s`.

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
- combat: guns, melee, cyberware arms, grenades and gadgets;
- Cyberpunk's own quickhacks, the phone, healing items (X), the scanner (Tab) and the weapon/radial wheel (Alt);
- quick melee, zoom, and picking up or carrying bodies.

**Movement abilities, switched off:**
- double jump, charge jump, air hover, dodge and air dash;
- superhero landing, Kerenzikov slide, Sandevistan;
- Cyberpunk's own optical camo, terrain camo, sprint-shove and dash-shoot.

**Exceptions:** Steve's **Double Jump** perk gives V the double jump back, and his **Dodge** perk gives the dash and
air dash back.

**Keys** (the full table is in [Controls](#3-controls)):
- F5 does not quick-save. Save from the pause menu or turn Cyberblock off first. F9 never quick-loads, in any state.
- G does not toggle walking. K does not open crafting while it is the skill-tree key (the default): rebind the skill
  tree to another key and K opens Cyberpunk's crafting menu again.
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
- **Gliding jitters:** run `CyberblockFlight.SetCameraOnly(true)` in CET.
- **A wall of invisible collision is left in Night City:** run
  `CyberblockCollision.Reset(Game.GetPlayer():GetGame())` in CET. This also happens by itself on every reconnect.
- **You cannot pick a dialogue option:** with 2 or more choices on screen, E/Q or the wheel scroll them, 1–4 pick
  directly and F confirms. A Minecraft screen left open covers them: close it with Esc. If a conversation still
  does not react, press F9 to turn Cyberblock off for it, then back on.
- **A Cyberpunk key does nothing:** check [the key table](#3-controls); the key may be one Minecraft owns while
  Active.
- **The elytra does not open:** V must be falling with 3 blocks of free air below, not held by the game and not in a
  menu (see [layer 4](#layer-4-elytra-and-ender-pearls)). Cyberblock.log says why a glide was ignored
  ("flight: ignored, V is in a vehicle or held (<reason>)"); the Minecraft log shows "glide called off".
- **Weapons, leads and mobs ignore people:** look at the 5 s lines "npcs: sent H people + C cars; ... left out: ..."
  in Cyberblock.log and "proxies: H people, C cars" in the Minecraft log. The left-out reasons (dead, defeated,
  mounted, puppet, detached, not_npc, over_cap) say why. Scanning and quickhacks on people do not need proxies.
- **The hack or skill-tree key does nothing:** rebind it in Minecraft's Controls to an allowed key (G K L O U Y 0
  F1-F4 F6-F8 F10-F12). A refused key, or both actions on one key, falls back to G/K and chat says so.
- **A hack says "Not enough RAM" or "You need a cyberdeck in your inventory":** keep the Cyberdeck in your inventory.
  RAM refills over time, or use a RAM Shard. More messages: [netrunning step 7](#netrunning-step-by-step).
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
