# How to play Cyberblock

Cyberblock puts real Minecraft into Night City. Minecraft Java (26.3, with the Cyberblock Fabric mod) runs next to
Cyberpunk 2077 (2.31). Every frame, its picture is drawn into Cyberpunk at the right camera and depth.

The split is simple: **Cyberpunk moves V, Steve acts, and Minecraft draws the interface.** You walk, run, jump and drive
with Cyberpunk's controls. You place blocks, fight, throw, glide and hack with Minecraft's items, and those act on Night
City's real people, cars and devices. Everything you read on screen (radar, quest, prompts, dialogue choices, phone,
journal, map, pause) is drawn by Minecraft in Minecraft's style: Cyberpunk's own interface stays invisible while
Cyberblock is on.

> **Status (2026-10-03).** Layer 1 (the passthrough itself) has been verified in game. The first two in-game sessions found
> bugs that are fixed (elytra, hacks, ground alignment, wind charges). The third session (the "Re-test 3" list) asked for
> more, and this version adds it: every quickhack is unique in both games, a Minecraft-drawn interface everywhere (radar,
> quest and waypoint, prompts, choices, phone, journal, map, pause), a third-person view, Steve visible in vehicles, a glide
> that steers with the camera, vanilla-like knockback, separate keys for the Operating System implant (**Y**), the Arms
> implant (**L**) and Optical Camo (**O**), two Operating System slots, 3D arm implants, a Sandevistan screen effect and a
> release package with an installer. It passes every automated test, but none of it has been re-played in game yet:
> `docs/TESTING-PLAN.md` starts with the "Re-test 3" list. The fallback switches under
> [Troubleshooting](#8-troubleshooting) are still there for rough edges.
>
> **Phase Q (2026-10-04, not played in game yet).** Survival mining of placed blocks, stairs with no vault or climb prompt
> (plus a step-up assist), a third-person camera that does not clip or flicker and has its own options, breakable and
> returning outline blocks in creative, and the first Cyberblock settings screen. See
> [mining and stairs](#mining-placed-blocks-stairs-and-the-step-up-assist),
> [the camera options](#third-person-camera-options-and-the-debug-overlay),
> [creative mode and the outline blocks](#creative-mode-and-the-outline-blocks) and
> [the settings screen](#the-cyberblock-settings-screen); `docs/TESTING-PLAN.md` starts with "Re-test Q".
>
> **Quick links:** [release install](#1-setup), [the key table](#3-controls), [the Minecraft interface](#the-minecraft-interface),
> [netrunning, step by step](#netrunning-step-by-step), [what each chip does](#what-each-chip-does),
> [cyberware](#cyberware-u-y-l-and-o), [vehicles](#vehicles-and-third-person) and
> [free perks and `/cyberblock level`](#6-game-modes-and-the-starter-kit) and
> [playing together over LAN](#playing-together-lan).

---

## 1. Setup

There are two ways to get Cyberblock: the **release package** (for players: a zip with an installer, no programming tools)
and **building from source** (for developers). Both end in the same installed files.

### What you need
- **Cyberpunk 2077 2.31** on Steam (Phantom Liberty is fine). Install these mods first:
  - [RED4ext](https://github.com/WopsS/RED4ext): loads `Cyberblock.dll`;
  - [redscript](https://github.com/jac3km4/redscript): compiles the scripts;
  - [Codeware](https://github.com/psiberx/cp2077-codeware);
  - [Cyber Engine Tweaks (CET)](https://github.com/maximegmd/CyberEngineTweaks): needed only for its console, which the
    fallback switches use.
- **[ReShade](https://reshade.me) 6.x with add-on support** (the "with full add-on support" installer), installed for
  `bin\x64\Cyberpunk2077.exe` with the **DirectX 10/11/12** API.
- **Minecraft: Java Edition 26.3** from the official launcher (you must own it), plus:
  - the [Fabric Installer](https://fabricmc.net/use/installer/) (Fabric Loader 0.19.5 or newer for 26.3);
  - [Fabric API](https://modrinth.com/mod/fabric-api) **0.161.0 or newer** for 26.3.
- **RAM:** about 64 GB, and a page file big enough for both games (see
  [Memory and the page file](#memory-and-the-page-file)).
- Windows 10 or 11. The release installer needs only Windows PowerShell 5.1 (it is part of Windows), not Python.

### Release install (players)
1. **Download** `Cyberblock-1.0.0.zip` from the project's releases page and unzip it anywhere. It contains `install.bat`,
   `uninstall.bat`, `install.ps1`, this guide, the licence texts, a `game\` folder (the plugin, the scripts and the ReShade
   shader) and `minecraft\mods\cyberblock-1.0.0.jar`.
2. **Close Cyberpunk**, then double-click **`install.bat`**. (If it cannot find your game, drag your Cyberpunk 2077 folder
   onto `install.bat`, or run `install.ps1 -Game "<your Cyberpunk 2077 folder>"` in PowerShell.) The installer:
   - finds the game through Steam's libraries and **refuses to run while Cyberpunk is running**;
   - **never overwrites a file it did not write itself**, and records every file it wrote, with a checksum, in
     `red4ext\plugins\Cyberblock\install-manifest.json`;
   - **backs up your saves** once, before the first install, into `%LOCALAPPDATA%\Cyberblock\save-backups`;
   - writes a `ReShadePreset.ini` that turns the Cyberblock effect on **only if you have no preset yet**, and keeps a
     `ReShade.fxh` you already have;
   - warns (but continues) when it does not see RED4ext, redscript, Codeware or ReShade.
3. **Minecraft, once:**
   1. Run the Fabric Installer, pick Minecraft **26.3**, loader 0.19.5 or newer, and tick *Create profile*. This adds a
      Fabric installation to the Minecraft Launcher.
   2. In the launcher open **Installations**, edit the Fabric 26.3 installation (or duplicate it and name it
      "Cyberblock") and click **More options**:
      - **Game directory:** `%APPDATA%\.minecraft-cyberblock`. Use this dedicated folder: Cyberblock rewrites
        `options.txt` (window size, frame rate cap, controls) and must not touch your normal Minecraft.
      - **JVM arguments:** **replace** the whole field with
        `-Xmx2G -XX:+UseZGC --enable-native-access=ALL-UNNAMED -Dcyberblock.autostart=true`. Replace it, do not append:
        the default arguments contain a larger heap and another garbage collector that fight with Cyberpunk for memory.
   3. Copy `minecraft\mods\cyberblock-1.0.0.jar` **and the Fabric API jar** into `%APPDATA%\.minecraft-cyberblock\mods`
      (create the folder if it is missing).
4. **Play:** start the Cyberblock installation from the launcher. The title screen has a **Play Cyberblock** button that
   opens (or, the first time, creates) the Night City world; with `-Dcyberblock.autostart=true` it opens the world by
   itself. Leave the Minecraft window open behind Cyberpunk, start Cyberpunk, load a save and press **F9**.
   The Minecraft side listens on `127.0.0.1:25600`; that is only reachable from your own computer.

**Updating:** unzip the new release, close Cyberpunk, run `install.bat` again (it replaces its own files and removes files
a newer version no longer ships), replace the jar in the `mods` folder, and restart Minecraft. A Minecraft that was
already running keeps the old mod. If Cyberblock.log says `version mismatch: DLL 1.0.0, mod X`, the two halves are from
different releases.

### From source (developers)
Needs:
- JDK 25;
- Visual Studio 2022 Build Tools (C++ x64 and the Windows SDK) and CMake;
- Python 3.12 with `uv`;
- git (the repository uses submodules: `git submodule update --init`).

From the repository root:
```
cmake -S cp/plugin -B cp/plugin/build -G "Visual Studio 17 2022" -A x64
cmake --build cp/plugin/build --config Release
uv run tools/install.py
```
The build produces `cp/plugin/build/Release/Cyberblock.dll`. You do not build the Minecraft mod by hand: `tools/play.py`
builds and starts it through Gradle (see [Launching](#2-launching)). To build the release zip yourself:
`uv run python -m tools.package` writes `dist/Cyberblock-1.0.0.zip`.

`tools/install.py` copies the same files as the release installer and writes the **same manifest**, so the two installers
and both uninstallers are interchangeable:
- `red4ext/plugins/Cyberblock/Cyberblock.dll`;
- the scripts, into `r6/scripts/Cyberblock/`;
- `Cyberblock.fx` and `ReShade.fxh`, into `bin/x64/reshade-shaders/Shaders/` (an existing `ReShade.fxh` is kept);
- a `bin/x64/ReShadePreset.ini` that turns the `Cyberblock` effect on, **only if you have no preset yet**.

It refuses to overwrite any file it did not write itself, backs up your saves before the very first install
(`--backups <folder>` puts the zip somewhere else) and, when you pull a new version, replaces its own files when you run it
again (close Cyberpunk first).

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
6. **Sandevistan screen effect strength:** the Cyberblock effect has a slider **SandevistanStrength** (0 to 1, default 1).
   While the Sandevistan runs the picture gets a chromatic split, a lime and teal tint, ghost trails and edge glow. Set
   the slider to **0** if you are sensitive to motion effects; Kerenzikov uses half of the strength.

---

## 2. Launching

**Release players:** start the Cyberblock installation from the Minecraft Launcher (see [Release install](#1-setup)), start
Cyberpunk from Steam, load a save and press **F9**.

**From source:**
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
| "Start Minecraft first" | Minecraft is not running, or not linked yet. Start it and press F9 again. (This one and "Minecraft lost" are the only messages Cyberpunk still draws itself, because Minecraft is not there to draw them.) |
| "Cyberblock: in vehicle" / "Cyberblock: on foot" | You got into or out of a car. Steve rides along (see [Vehicles](#vehicles-and-third-person)). |
| "Minecraft lost — V restored" | No Minecraft frame for 2 s (it closed or hung). V is back to normal and Cyberpunk's own interface is back. |
| "Minecraft back — Cyberblock on" | Minecraft came back. Cyberblock resumes by itself. |
| "Cyberblock off" | You pressed F9 again. Everything is back to vanilla, interface included. |

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
| **LCtrl**, **Mouse4**, **Mouse5** | Cyberpunk's own (dodge/dash, scanner hold etc.; dodge works with Steve's Dodge perk) | - | not swallowed |
| **F** | interact / talk / dialogue choice 1 / exit vehicle (the prompt itself is drawn by Minecraft) | - | never bindable |
| **LMB / RMB / MMB** | (blocked combat) | attack / use / pick block | Shift/Ctrl held are passed as modifiers in Minecraft screens (Shift-click quick-move) |
| **Wheel** | - | hotbar scroll; while G held: choose quickhack chip | goes to Cyberpunk while a dialogue or interaction list with 2+ choices is shown |
| **1-9** | - | hotbar slots (in inventories: swap hovered stack) | 1-4 go to Cyberpunk while a dialogue or interaction list with 2+ choices is shown |
| **E** | - | inventory | goes to Cyberpunk (scroll choices) while 2+ choices are shown |
| **Q** | - | drop item (Ctrl+Q: whole stack) | goes to Cyberpunk while 2+ choices are shown |
| **T** | **phone**: answers a ringing call, hold to reject (see [Calls](#the-minecraft-interface)) | chat (real typing: letters, Enter, Backspace, arrows reach Minecraft while a screen is open) | goes to Cyberpunk only while a call rings or an actionable notification shows |
| **/** | - | command line | - |
| **X** | (healing hotkey, already blocked while Active) | swap item to offhand | - |
| **G** (hold) | (walk toggle, suppressed) | scan / lock target 5 s; while held: wheel = pick chip, right-click = fire chip | Minecraft's own Quick Actions (also G in 26.3) is unbound automatically |
| **K** | (crafting, swallowed) | skill tree | - |
| **U** | (swallowed) | Cyberware screen (install and remove implants); U again closes it | rebindable |
| **Y** | (swallowed; Cyberpunk's own Y, scene fast-forward and camera pitch reset, works again with F9 off) | fires the **Operating System** implant: Sandevistan or Berserk | rebindable |
| **L** | (swallowed) | fires the **Arms** implant: Mantis Blades lunge, Gorilla Arms slam, Monowire lash, Projectile Launch System | rebindable. Minecraft's own Advancements key was L: it is unbound (see Vanilla clashes below) |
| **O** | (swallowed) | fires **Optical Camo** (Integumentary) | rebindable. Minecraft's own Friends key was O, it is unbound |
| **F5** | (QuickSave, swallowed) | toggles **third person** on foot ("Third person" / "First person" above the hotbar) | see [Third person](#vehicles-and-third-person); never quick-saves |
| **Esc, M, J, I, P, H** | Cyberpunk's pause menu, map, journal, inventory, perks and hub | the menu is **replaced by a Minecraft screen** (pause, atlas, journal, inventory, skills, pause) | see [the Minecraft interface](#the-minecraft-interface); menus Cyberblock cannot replace stay Cyberpunk's, restyled |
| **Tab, CapsLock, Alt, R, B, N, V, Z** | Cyberpunk's own (scanner/radial/reload/holster are blocked while Active) | - | passed through |

### Context rules
- **A Cyberpunk dialogue or interaction list with 2 or more choices** (a conversation, or several prompts on one object such as "Pick up" / "Loot"): E, Q, 1-4 and the wheel go to Cyberpunk only, so
  you scroll and pick choices as usual (F picks the highlighted one). The list itself is drawn by Minecraft. The mouse
  buttons and the other keys (5-9, G, T, ...) still go to Minecraft. A single-choice prompt ("[F] Talk") does not count: E
  still opens Steve's inventory.
- **A Minecraft screen is open** (inventory, chest, chat, skill tree, the Night City screens, death screen): every key
  except F9 goes to Minecraft: the named keys plus typed text, Enter, Backspace, Tab, the arrows, Home/End, PgUp/PgDn and
  Delete. V stands still, the camera is frozen, and the mouse moves Minecraft's cursor.
- **A Cyberpunk menu is open that Minecraft does not replace** (shops, ripperdoc, stash, settings and similar, see
  [pass-through menus](#menus-cyberblock-cannot-replace)), or **Cyberblock is off:** nothing is forwarded except F9. The
  menus work exactly as in vanilla.
- **V is in a vehicle:** the driving keys are Cyberpunk's; Minecraft shows only Steve's hand or the seated Steve, no hotbar (see [Vehicles](#vehicles-and-third-person)).
- Minecraft's own key settings do not matter for the keys above: Cyberpunk sends Minecraft the action (inventory,
  drop, swap, ...), not the key. Only the six Cyberblock keys below are rebindable.

### In a Minecraft screen
- **Esc** closes it (the death screen stays: click Respawn). **E** also closes the inventory and containers, and
  **K** the skill tree.
- **Shift-click** moves a whole stack, **Q** drops the hovered item, **Ctrl+Q** the hovered stack, **1-9** swap the
  hovered stack with that hotbar slot, and **X** swaps it into the offhand.
- **Chat (T) and commands (/)** take real typing, accents and emoji included. Enter sends, Esc cancels. No more
  Alt+Tab.
- Closing a screen with E and holding W at once: if W goes down before Minecraft has reported the screen closed (about
  50-100 ms), V does not walk until you press W again. That is the rule that keeps keys from sticking in Cyberpunk.

### Rebinding the six Cyberblock keys: Hack (G), Skill tree (K), Cyberware (U), Operating System implant (Y), Arms implant (L), Optical Camo (O)
Minecraft → Options → Controls → Key Binds → category **Cyberblock**. The binding is sent to Cyberpunk at once.
- **Refused** (both sides; `shared/vectors.json`): W A S D C E Q T X F I M J H N B P V Z R, 1-9, F5, F9, Space, Shift,
  Ctrl, Esc. These are Cyberpunk's own keys or keys Cyberblock already forwards.
- **Allowed:** G K L O U Y 0 F1-F4 F6-F8 F10-F12.
- **A refused key** goes back to its own default (G, K, U, Y, L or O). **If any two of the six keys end up the same, all
  six go back to G, K, U, Y, L and O** (nothing is half-applied). Minecraft says so in chat (also after every reconnect).
  Cyberblock.log logs it once, and its binds line reads `binds: hack G, skills K, cyberware U, activate Y, arms L, camo O`.
- **Vanilla clashes:** on the first start, Cyberblock unbinds the vanilla Minecraft keys that sit on one of its six keys
  (Advancements on L, Friends on O, Quick Actions on G), saves the options and tells you in chat ("Cyberblock took L from
  Advancements (rebind it in Controls)"). The pause screen has no Advancements button, so to open the advancements screen
  bind **Advancements** to a free key (Esc, **Minecraft Options...**, Controls).
- The keys the six actions use are taken from Cyberpunk. Move Skill tree off K and K opens Cyberpunk's crafting menu
  again; move Activate off Y and Y is Cyberpunk's own again (scene fast-forward, camera pitch reset); the same goes for U,
  L and O. Cyberpunk's walk toggle stays off while Active whichever key Hack uses.

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
- Implants can help (see [Cyberware](#cyberware-u-y-l-and-o)): Subdermal Armor makes Night City hits go through Steve's
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
  - Minecraft's log says `ground alignment: k 63 -> 57 (offset ... m) at Cyberpunk z ... (/cyberblock level)` (the
    first one says `k -1 -> 57 ... (first activation)`: -1 means "never aligned"), then `block resync: N cells`.
    Cyberblock.log says `ground alignment: Minecraft y = Cyberpunk z - ... (k 57/64)` and
    `ground: alignment k 63 -> 57, Minecraft gets the ground again`.
- **Night City is solid for Minecraft.** Raycasts around V turn floors and walls into invisible barrier blocks:
  - the radius is 24 m on foot, and grows to 64 m while gliding;
  - Steve, mobs and arrows stand on them and bump into them;
  - in survival and adventure you cannot break them; creative can, and they come back (see
    [Creative mode and the outline blocks](#creative-mode-and-the-outline-blocks)).
- **In a car:** Steve rides along: Minecraft shows his hand or the whole seated Steve, and hides the hotbar. Details under
  [Vehicles and third person](#vehicles-and-third-person).

### Mining placed blocks, stairs and the step-up assist
Placed blocks behave like blocks in Minecraft, in survival too:
- **Mining.** Hold the attack button on a placed block with a pickaxe (or whatever fits the block): the cracks grow and it
  breaks in the normal Minecraft time, at eye height and at your feet alike. Night City's real surfaces behind a block
  never get in the way of the aim, and V standing on the ground does not slow the mining down as if you were in mid-air.
  To see what the crosshair is aiming at, switch the aim debug on (`/cyberblock debug aim on`): Minecraft's latest.log
  then gets one `aim: target <block|entity|none> d=<m> limit=<m>` line per tick.
- **Stairs.** Build a staircase of placed blocks and walk up it: Cyberpunk no longer offers its **vault** or **climb**
  prompt on your blocks, so V simply walks up. Real Night City ledges, ladders and kerbs still vault and climb as before.
  Cyberblock.log says `locomotion: vault refused on Cyberblock box` (or `climb`) at most once a second when it steps in.
- **Step-up assist.** With **Step-up assist** on (the default; Esc, then **Cyberblock...**, see
  [the settings screen](#the-cyberblock-settings-screen)), walking into a single placed block that is at most one block
  high, with 2 m of free room above it, lifts V onto it smoothly in about a sixth of a second. It never lifts V onto
  Night City's own geometry, and not in a vehicle or a scene. With it off, jump up the steps instead.

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
- **Weapons:** melee weapons (swords, axes…), arrows, tridents and crossbows hurt people and cars. A kill drops its XP
  at Steve, however far away it happened.
- **Knockback is vanilla-like.** A hit pushes a person the way Minecraft pushes a mob, and only strong hits knock them
  down:

  | Hit | Push | What Cyberpunk does |
  |---|---|---|
  | plain hit | 2 m/s | the person flinches (impact reaction) |
  | sprint hit, Knockback I, the Gorilla Arms perk | 4.5 m/s | a stagger (they stumble back) |
  | Knockback II, gauntlet, Gorilla Arms implant (empty hand), Gorilla Arms perk plus sprint | 7 m/s | a knockdown (they fall) |
  | gauntlet plus the Gorilla Arms perk | 9.5 m/s | a knockdown |
  | gauntlet, Gorilla Arms perk and sprint | 12 m/s | a knockdown plus a short ragdoll |

  Every hit also deals its share of the person's health (a sword hit about 30 %); a person at zero health is
  defeated (down), and the next hit finishes them. The reaction's own animation is the push: nobody is thrown across the
  street. Arrows push by the Punch enchantment instead of by their speed. Cars are shoved ×3 by Gorilla Arms as before.
  Cyberblock.log has a `knock: <id> s=<speed> tier=<tier> impulse=<...>` line per hit, which says which tier a hit
  became (`impulse` is the ragdoll's or the car's push, 0 for the tiers that only play a reaction).
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
  a second and the camera no longer jumps. **You steer with the mouse exactly like the vanilla elytra:** move the mouse
  and the glide turns with the camera, left, right, up and down (the mouse turn is applied to the glide's own heading,
  not to V's body, so it is no longer locked to the direction you started in). **Firework rockets** (right mouse) boost
  you. Collision mapping reaches further ahead the faster you fly. Cyberblock.log's flight summary shows the turn: `yaw
  start A end B, mouse x N counts at R deg/count`.
- **Wind-charge launches:** a **wind charge** thrown at your own feet (look down and throw), another player's or a
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
- **Walls end the flight:** V never passes through Night City. Flying into a wall, a ceiling, a kerb or anything solid
  Minecraft has not mapped yet ends the glide or launch right there: V is stopped half a metre short of it, put on the
  ground below that spot, and gets full control back at once; Steve's glide is called off in Minecraft and he is back with
  V within a moment (Cyberblock.log: "flight: V hit Night City geometry, the glide ends here"; Minecraft log: "glide
  called off (the host stopped following)"). Open the elytra again from a ledge to fly on. No fall damage either way.
  - A safety net releases V too if V and Steve ever drift apart for a second (Cyberblock.log "flight: watchdog: V could
    not follow ...", Minecraft log "the host's V stayed N blocks from Steve for a second"): V is put on the ground where
    it was and the glide is called off, so a flight never leaves you stuck.
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
- **F9 or a save mid-flight** ends the flight and puts V on the ground below, never in mid-air (Cyberblock.log:
  "flight: cleared in mid-flight, V put on the ground").
- Thrown things (pearls, arrows, tridents, potions, wind charges, the fishing hook) also stop on Night City walls,
  because the walls on their predicted paths are filled in ahead of time.

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

### Vehicles and third person
**Steve rides along.** Getting into a car, bike or any other vehicle no longer hides Steve or Minecraft:
- **First-person vehicle camera:** you see Steve's hand, drawn over the dashboard. The hotbar is hidden.
- **Third-person vehicle camera:** you see the **whole of Steve, seated** at V's seat. Switching Cyberpunk's vehicle camera
  switches between the two at once.
- Minecraft hides its hotbar, survival bars (health, food, armour, experience level) and the crosshair while you are in a
  vehicle and shows them again when you get out. Only the hand (first person) or the seated Steve (third person) is drawn.
- V's own body stays hidden in both vehicle cameras, and **Cyberpunk's HUD never appears in a vehicle**: the speed and the
  district are drawn by Minecraft (see [the interface](#the-minecraft-interface)).
- The health rule, the driving, the radio and the quickhack ban in cars are as before. Minecraft's items, hacks and
  implants that need V on foot do nothing while you drive.

**Third person on foot (F5).** Press **F5** to see Steve from behind: the camera moves back (about 3 m), is shortened by
walls and blocks so it never ends up inside them, and F5 again returns to the first-person view. The DLL moves
Cyberpunk's own first-person camera component, so V still moves and turns normally and the crosshair is drawn at the
screen centre. Steve stays locked in the frame while you walk, run or glide: the compositor moves him with V and the
camera instead of where V was when Minecraft drew the picture, and in a third-person glide Minecraft draws him where V
is (Steve's own physics may be a block or two ahead), so the camera no longer seems to trail him. It switches itself off (back to first person) in vehicles, scenes, workspots, menus, braindances and
photo mode, and whenever Cyberblock goes off. A "Third person" or "First person" message confirms it. Cyberblock.log says
`view: third person on (distance D m)` or `view: first person (<reason>)`.

#### Third-person camera options and the debug overlay
The third-person view (F5) has settings of its own, in [the settings screen](#the-cyberblock-settings-screen) (Esc, then
**Cyberblock...**, or `/cyberblock settings`). They apply at once:

| Row | What it does | Range (default) |
|---|---|---|
| **Distance** | how far behind Steve the camera sits | 1.5 to 8 m (3.2) |
| **Shoulder** | how far to the right of Steve the camera sits (negative = left) | -1 to 1 m (0.45) |
| **FOV** | the third-person field of view; 0 keeps the game's own | 0, or 50 to 110 (0) |
| **Auto-recenter** | when a wall is close on the shoulder side, the camera swings to the centre, and back when it is clear | on |
| **Vehicle distance** | the third-person vehicle camera's distance; 0 keeps the game's own, and it does nothing if Cyberpunk's vehicle camera cannot be changed | 0, or 3 to 14 m (0) |

The camera no longer clips through walls: it tests four rays instead of one, stops 0.25 m in front of the nearest surface
(never closer than 0.4 m to Steve), eases in and out instead of jumping, and never shows the inside of a wall when you
turn in a corridor. Elytra, shield and use-item poses of third-person Steve no longer flicker, and Steve stays locked to
the camera while you walk, sprint, glide or launch. The crosshair is drawn where Steve's own aim meets the target, so what
it covers is what you hit; with nothing in reach it is at the screen centre. Third person stays off in braindances, photo
mode, scenes and menus (Cyberblock.log says `view: gate <reason>` when the reason changes).

**The view debug overlay.** `/cyberblock debug view on` (and `off`) shows a small overlay at the top left: frame time, the
age of the newest camera pose, the interpolation factor, first or third person, the camera distance and the collision
distance. Minecraft's latest.log gets `view: frame <ms> pose age <ms> interp <f> dist <m> collide <m>` every 5 s, and
Cyberblock.log `view: dist <m> collide <m> shoulder <m> recenter <on|off>` whenever it changes. Steady numbers mean a smooth
view; if you report jitter, please include these lines. Both debug switches (`aim` and `view`) need no operator rights and
are off at every start.

### Layer 6: Cyberpunk in Minecraft form

#### Netrunning, step by step
Netrunning is Minecraft's half of Cyberpunk's quickhacks: Minecraft owns the cyberdeck, the RAM, the chips and their
recharge; Cyberpunk runs the real quickhack (upload, VFX, damage, AI reaction, NCPD heat) with V as the hacker.

**Step 0: be ready.** Cyberblock Active on foot ("Cyberblock on" after F9). It works in survival and creative (RAM
costs apply in both), not in spectator, and not from a car (nothing is forwarded in a vehicle).

**Step 1: get a cyberdeck.** The Cyberdeck is an implant: it lives in the **Operating System** slot, which now has **two
places** (see [Cyberware](#cyberware-u-y-l-and-o)): the Cyberdeck takes the first, Sandevistan or Berserk the second, so
you can run both.
- In survival, the starter kit arrives by itself the first time you press F9 in a world ("Cyberblock starter kit
  received"). It **installs the Cyberdeck for you** (and Sandevistan next to it), and it holds 2 **RAM Shards** and one of
  each of the 10 **quickhack chips** in the main inventory (open it with **E**). It starts your RAM full (8/8).
- At any time: **T** (or **/**), type `/cyberblock kit`, Enter. Or craft a Cyberdeck (recipe below), or take one from
  the **Cyberblock** creative tab, then install it with **U**.
- **The deck counts** when it is installed, **or** when a Cyberdeck is anywhere in your inventory (hotbar or main
  inventory). Sandevistan or Berserk no longer get in its way. With no deck at all your maximum RAM is 0 and every chip
  says "No cyberdeck: install one in the Operating System slot (U) or carry one".

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

**Step 6: pick a chip and fire.** **The chip in your main hand always wins.** The rule is:
1. the chip you **hold** in your main hand;
2. else the chip you picked with the **wheel**;
3. else the first chip you own.

Selecting a hotbar slot that holds a chip clears the wheel pick, so the hand and the wheel never disagree. Two ways to
use it:
- **From the hand:** hold a chip on the hotbar, scan with G (you may let go: the lock lasts 5 s) and right-click.
- **The hack wheel (easiest):** keep **G** held, roll the **mouse wheel** to pick a chip (the hotbar does not move
  while G is held; the tooltip shows "Chip: <name> (<n> RAM)" or "(recharging)"), then **right-click**.

When you fire, the action bar says "Hack: <chip name>" in the chip's colour, so you can see which chip actually went out.

Minecraft then checks, in this order: a cyberdeck, a lock from the last 5 s, the right kind of target (the Redstone
Lever wants a device, every other chip a person or a car), that the target is not still uploading a hack, and enough RAM.
If all pass, it spends the RAM, starts the chip's recharge (2 s; 10 s for Synapse Burnout and Cyberpsychosis) and
Cyberpunk runs the hack:
- on a **person**: the quickhack itself, after its upload;
- on a **car**: the hack goes to its driver; an empty car fails with "no driver";
- with **Chain Hack**: the nearest other hackable living person within 8 m of the target gets the same hack.

**An upload beam runs from Steve to the target** (in the chip's colour) while Cyberpunk uploads the hack. Cyberblock.log
says `hack <name> on <id>: ok (<kind>, upload <s>s, +<n> spread)` when the upload **starts**; the effect lands when the
upload completes, and that is when Minecraft plays the chip's **impact** (see [what each chip does](#what-each-chip-does)).
Long uploads (Synapse Burnout, Cyberpsychosis) take a few seconds, and if the target dies, despawns or leaves first, the
hack is lost and its RAM is not given back. A target that is still uploading refuses a second hack ("<name> is still
uploading a hack", no RAM spent).

**Step 7: read the answer.** Messages appear above the hotbar. Every failure Cyberpunk reports gives the RAM back.

| Message | What to do |
|---|---|
| Hold G and look at a target | nothing is locked: scan first (the lock lasts 5 s) |
| No cyberdeck: install one in the Operating System slot (U) or carry one | install the deck with **U**, or carry one in your inventory (step 1) |
| Not enough RAM: n of m | wait for it to regenerate, or use a RAM Shard |
| Still recharging | that chip is on its 2 s (or 10 s) recharge: pick another with the wheel |
| <name> is still uploading a hack | wait for the upload beam to end |
| The Redstone Lever only works on devices | you locked a person or a car |
| This quickhack needs a person or an occupied car | you locked a device: use the Redstone Lever |
| Hack failed: no driver | the car is empty |
| Hack failed: not hackable (friendly / god mode / quest character / no quickhack actions / not a person) | Cyberpunk protects this one (friends, quest characters, story NPCs) |
| Hack failed: not hackable (V is in a scene) | finish the scene first |
| Hack failed: target gone | it really died or despawned (if you still get it on someone alive, check the scan line in Cyberblock.log, see Troubleshooting) |
| Hack failed: not hackable (no intersection / camera allows no hack / device allows no hack / not a device) | that device offers nothing to hack |
| Night City is not connected | Cyberpunk never answered (60 s, or 10 s with the link down) |

**Step 8: devices.** The **Redstone Lever** (1 RAM) is the device chip. Aim at the device itself, not at a person in
front of it, scan, and fire:
- a traffic light flips its whole intersection (red and green dust, a bell);
- a camera stops seeing V (it never takes over the view; black smoke);
- vending machines, TVs, radios and arcades make a distraction (music notes and chimes);
- any other device runs the first quickhack it allows (a lever click and redstone dust).

**Step 9: perks.** Press **K** and buy the **Intelligence** branch in order (XP levels: 3, 5, 8, 12): **Extra RAM**
(+4 maximum RAM), **Cheap Hacks** (every chip costs 1 RAM less, at least 1), **Chain Hack** (step 6) and **Overclock**
(RAM regenerates twice as fast). XP comes from kills in Night City (the orbs drop at Steve) or `/xp add @s 30 levels`. In creative, or after
`/cyberblock perks free on`, perks cost nothing (see [free perks](#6-game-modes-and-the-starter-kit)).

**A quick run:** F9 (the kit arrives) → hold G on a ganger → roll the wheel to "Redstone Charge" → right-click → let
go of G. A lightning bolt strikes, the ganger is electrocuted and your RAM drops by 3.

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

Every other quickhack than the Redstone Lever needs a person or a car; the Lever needs a device.

#### What each chip does
Every chip is unique in **both** games: a Minecraft effect that matches the chip, and a different Cyberpunk quickhack.
A person who walks around the street as part of the **crowd** (a pedestrian) is not an AI-driven character, and Cyberpunk
does not run quickhacks on crowd characters at all. A **ganger or NCPD officer** (a squad member) gets the vanilla
quickhack. So that a pedestrian shows the effect too, Cyberblock adds a visible extra on pedestrians, listed in the last
column. Cyberblock.log's `hackfx:` lines say which extra ran.

| Chip | In Minecraft (at the impact) | In Cyberpunk, on a ganger | On a pedestrian (extra added by Cyberblock) |
|---|---|---|---|
| **Redstone Charge** | a visual-only lightning bolt, an electric spark burst and red sparks | Overload: electrocutes (kills pedestrians outright) | nothing extra: the vanilla effect is already visible |
| **Blaze Chip** | blaze shot, a rising spiral of flames, lava pops and smoke (no fire blocks) | Overheat: burns | set alight for 5 s |
| **Ink Sac Daemon** | squid ink bursts at the head and leaves an ink halo | Reboot Optics: blinds | blinded for 5 s |
| **Cobweb Daemon** | **real cobwebs** appear in the target's cell, the four cells around it and the head cell (only in air, never within 1.5 blocks of you), and vanish after 6 s | Cripple Movement: cannot walk | stunned (rooted) for 4 s |
| **Spider Eye Virus** | a green poison cloud spreads, and a green arc jumps to each person it reaches | Contagion: spreads to others | always uploads a mild contagion to the 2 nearest people within 6 m |
| **Wither Skull Daemon** | a wither-skull trail to the target, then soul and smoke particles | Synapse Burnout: heavy brain damage | a heavy hit (35 % of health), no ragdoll |
| **Compass Ping** | a gold ring and light column, and **every hostile within 32 blocks glows through walls** (gold outline, also Minecraft's own hostile mobs) for 10 s | Ping: reveals the squad and enemies | reveals hostile NPCs within 30 m (up to 16) |
| **Bottle of Forgetting** | a swirl of enchantment particles round the head; mobs that targeted that person drop it | Memory Wipe: the person forgets V | dazed for 2 s |
| **Creeper Brain** | a green creeper face floats above the head; angry-villager marks on every victim | Cyberpsychosis: the person attacks its friends | frenzy for 6 s: every 1.5 s it hits the nearest person within 4 m |
| **Redstone Lever** | depends on the device: a camera (lever click, black smoke), a traffic light (bell, red and green dust), a distraction device (music notes, chimes) or any other (lever click, redstone dust) | the device's own action | - |

Pedestrians that are quest characters or V's friends are spared the extras. The extras happen when the upload completes,
about 1-3 s after the beam starts.

**Other cyber items:**

| Item | What it does | Recipe (shapeless) |
|---|---|---|
| Cyberdeck | The netrunning implant (Operating System slot): needed to have RAM | 2 iron ingots, 2 redstone, glass pane, copper ingot, gold ingot |
| RAM Shard | +4 RAM, used up (stacks to 16) | lapis lazuli, redstone, glass pane |
| Mantis Blade | Sword: 7 damage, 1.8 attacks/s. An older item: the Mantis Blades implant replaces it, and it is no longer in the kit | iron sword, diamond, redstone, copper ingot |
| Gorilla Gauntlet | Fist weapon: 4 damage, knockback +2 (shoves cars). An older item: the Gorilla Arms implant replaces it, and it is no longer in the kit | iron block, leather, redstone, copper ingot |

All of them, and every implant below, are also in the **Cyberblock** creative tab.

#### Cyberware (U, Y, L and O)
Cyberware are implants you **install** instead of carrying. Each one goes into a ripperdoc slot of its own (custom
slots, not armor slots) and then works by itself, or when you press its key. Every implant has a Minecraft effect, with
particles and sounds, and where it makes sense the real Cyberpunk effect on V (time dilation, optical camo, a double
jump, an explosion).

**The screen (U).** Press **U** to open the Cyberware screen. **U** again or **Esc** closes it.
- **Left:** the slots, as boxes with one frame per place: Arms 1, Legs 1, **Operating System 2** (a Cyberdeck place and a
  Sandevistan-or-Berserk place), Nervous System 1, Integumentary 2, Eyes 1, Circulatory 2, Frontal Cortex 1.
- **Right:** the implants you can install. These are the ones in your inventory (in creative, all of them), 24 to a page.
- **Left-click** an implant on the right to install it, or an installed one on the left to take it out. Hover for its
  slot, what it does in Minecraft and in Night City, its cooldown, **which key fires it** ("Fire: Y") and how to install it.
  You can also install by right-clicking the item in your hand.
- A full slot is replaced: the new implant goes in and the old one comes back to your inventory ("Installed <new>
  (returned <old>)"). In the two Operating System places the Cyberdeck always goes to the first and Sandevistan or Berserk
  to the second, so installing Berserk replaces Sandevistan, **never the deck**.
- In survival, installing uses the item up and removing gives it back. In **creative**, installs are free and the
  screen lists every implant; cooldowns still apply.
- A character saved before this version that has Sandevistan or Berserk in the first Operating System place is moved to
  the second place by itself, once, on joining.

**Firing: three keys.** An implant is always on, fired with its key, or automatic.
- **Y** fires the **Operating System** implant (Sandevistan or Berserk).
- **L** fires the **Arms** implant (Mantis Blades, Gorilla Arms, Monowire, Projectile Launch System).
- **O** fires **Optical Camo**.
- With nothing fireable in that slot, the key says so ("No Operating System implant to fire (Y)", "No active Arms
  implant to fire (L)", "No Optical Camo installed (O)"); a recharging one says "<implant> is cooling down (n s)".
- There is no selection or cycling any more: each key fires exactly its own slot, so a Sandevistan and a Mantis lunge are
  never on the same key. All three keys are rebindable (see [Controls](#3-controls)).
- **The HUD** shows each active or automatic implant right of the hotbar as a 16-pixel icon with a thin bar under it:
  grey filling to cyan while it recharges and magenta while it runs. While the Sandevistan runs the HUD says
  "SANDEVISTAN".
- Cooldowns count game time, are saved with the world, and still apply in creative.
- Cyberpunk's own Y, L and O (scene fast-forward, camera pitch reset and the others) are swallowed while Cyberblock is
  active, and work again with F9 off.

**The 17 implants.** "Slot" is where it goes, "Kind" is always on / the key that fires it / automatic.

| Implant | Slot | Kind | In Minecraft | In Night City (V) |
|---|---|---|---|---|
| **Mantis Blades** | Arms | on, **L**: lunge, 6 s cooldown | empty main hand: +6 attack damage, slower swings; red dust, a sweep and a strong-hit sound on every hit. **L**: lunge at the entity you aim at (up to 10 blocks) for a 1.5× hit (with Cyberpunk running it strikes at once along a red trail and V stays where she is; a lunge that misses gives the cooldown back) | your hits hurt people and cars as melee, as always; the lunge lands as a `hit` |
| **Gorilla Arms** | Arms | on, **L**: ground slam, 8 s cooldown | empty hand: +4 damage, +2 knockback, slower swings; a gust and smash sounds. **L**: a slam that knocks back everything within 4 blocks, with a dust ring and a smash | shoves people and cars hard (the same shove as the Gorilla Arms perk, never doubled); the slam knocks people down at most |
| **Monowire** | Arms | on, **L**: lash, 5 s cooldown | empty hand: +3 damage, +3 reach, slower swings; a line of electric sparks to the target. **L**: a 120° lash up to 5 blocks, 4 damage and Slowness II for 3 s | your hits land as melee; the lash hits each person in reach |
| **Projectile Launch System** | Arms | **L**, 8 s cooldown | fires along the crosshair up to 64 blocks (closer than 4 blocks: "Too close to fire", no cooldown): smoke and flames, crossbow and fire-charge sounds, a power 2.5 explosion | a real Cyberpunk explosion there: cars blow up, NCPD comes |
| **Reinforced Tendons** | Legs | on | 2 more blocks of safe fall; a jump in mid-air makes a ring of cloud and a gust | V's **double jump** |
| **Fortified Ankles** | Legs | on | 4 more blocks of safe fall; a charged jump (hold Space about half a second, then let go) throws dust and a gust | V's **charge jump** |
| **Lynx Paws** | Legs | on | Steve makes no step vibrations, and mobs notice you from half as far | people notice V from half as far (one effect, shared with the Silent Steps perk) |
| **Sandevistan** | Operating System (2nd place) | **Y**, 6 s on, 25 s cooldown (8 s and 18 s with the Sandevistan Sprint perk) | Speed II and the Minecraft world at half speed; cyan and magenta afterimages, beacon sounds, a "SANDEVISTAN" label | Night City slows to 20 %, V does not, and **ReShade draws the Sandevistan screen effect** (below) |
| **Berserk** | Operating System (2nd place) | **Y**, 8 s on, 40 s cooldown | Strength I, Resistance I and Jump Boost I; a ring of dust, an angry burst, a smash sound; hostile mobs within 3 blocks are knocked back | the jump boost reaches V. Cyberpunk's own Berserk status is **not** used (it would give V a superhero landing and a ground slam on falls): Cyberblock.log says "berserk: Minecraft side only" |
| **Cyberdeck** | Operating System (1st place) | on | your netrunning RAM (8 at most, see [netrunning](#netrunning-step-by-step)) | quickhacks run as before |
| **Kerenzikov** | Nervous System | automatic, 2 s on, 10 s cooldown | fires by itself when you draw a bow, crossbow or trident while V is in the air: the Minecraft world at half speed, a ring of sparks, a rising chime | Night City slows to 30 %, with half the screen effect |
| **Optical Camo** | Integumentary | **O**, 10 s on, 30 s cooldown | Steve turns translucent and cyan in third person (a shimmer) instead of vanishing, mobs get plain Invisibility; it breaks when you attack or fire the Launch System | V's optical camo, as with the Invisibility potion |
| **Subdermal Armor** | Integumentary | on | +6 armor and +2 toughness; a netherite-armor sound on install | protects against Night City damage, which goes through Steve's armor |
| **Kiroshi Optics** | Eyes | on | the scan reaches 80 m instead of 48; hostile proxies and hostile mobs within 32 blocks get a red outline | the scan range |
| **Biomonitor** | Circulatory | automatic, 90 s cooldown | at 30 % health or less: heals 8 (4 hearts) and Regeneration I for 6 s; hearts, a chime and "Biomonitor: emergency heal" | - |
| **Second Heart** | Circulatory | automatic, 5 minute cooldown | instead of dying: full health, Regeneration II for 5 s, Absorption I for 30 s and the totem animation ("Second Heart restarted you"). It does not save you from `/kill` or the void. The cooldown is saved | - |
| **RAM Upgrade** | Frontal Cortex | on | +4 maximum RAM (adds to the Extra RAM perk) | - |

**Arm implants are visible.** Mantis Blades, Gorilla Arms, Monowire and the Projectile Launch System have 3D models: when
installed you see them on your arm in first person, and on Steve's arms in third person (F5). The model changes to the
deployed version while the implant is out (blades extended, the launcher open). Kiroshi Optics, Subdermal Armor and
Reinforced Tendons also show on Steve (glowing eyes, plating, tendons) in third person.

**The Sandevistan screen effect.** While the Sandevistan runs (and, at half strength, Kerenzikov) ReShade adds a chromatic
split, a lime and teal tint, ghost trails behind moving things, an edge glow and a pulsing vignette. It fades in over
0.15 s and out over 0.4 s, and is off while a Cyberpunk menu is open. Your HUD and your hand stay sharp. The strength slider
is in the Cyberblock effect (**SandevistanStrength**, 0 turns it off). Cyberblock.log says `screenfx: sandevistan S s`.

**Slot rules worth knowing:**
- **Two Operating System places.** The Cyberdeck takes the first; Sandevistan or Berserk the second. You can run a deck
  and a Sandevistan together. (The kit installs both.)
- One arm implant at a time. Integumentary (Optical Camo and Subdermal Armor fit together) and Circulatory (Biomonitor
  and Second Heart) hold two each.
- Arm implants' passive bonuses work only while your main hand is **empty**; their **L** ability works with anything in
  the hand.

**Perks that upgrade implants** (the skill tree, K):
- **Double Jump** adds 4 blocks of safe fall to any legs implant. V's double jump itself comes from Reinforced
  Tendons only.
- **Sandevistan Sprint** makes the Sandevistan implant run 8 s with an 18 s cooldown (6 s and 25 s without it), and
  lets a double-tap of Shift fire it. It needs the implant.
- **Optical Camo** re-cloaks you after 2 s crouched and still. It needs the implant.
- **Gorilla Arms** and the Gorilla Arms implant give the same shove, not two. **Silent Steps** and Lynx Paws give the
  same one visibility effect. **Extra RAM** and RAM Upgrade add up (+4 each).
- **Migration:** a character that already owns the Double Jump, Sandevistan Sprint or Optical Camo perks receives
  the matching implant items once, never installed for you: "Your perks now need cyberware: you received <items>.
  Install them with U".

**Advancements.** Cyberblock has its own advancement tab (the pause screen has no button for it: bind **Advancements** to a free key in Esc, **Minecraft Options...**, Controls, then press it): *Cyberblock*
(root), *Chromed Up* (install your first cyberware), *Two Brains* (run a cyberdeck and a Sandevistan or Berserk at once),
*Netrunner* (land your first quickhack), *Script Kiddie No More* (land every quickhack chip) and *Time Is on My Side*
(defeat an enemy while the Sandevistan runs).

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

- **The starter kit** (survival, once per world; `/cyberblock kit` again): it installs the Cyberdeck, Sandevistan, Kiroshi
  Optics, Reinforced Tendons and Biomonitor, and puts Optical Camo and the Projectile Launch System in the main
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

### The Minecraft interface
**Cyberpunk's own interface stays invisible while Cyberblock is on; Minecraft draws every part of it** in Minecraft's own
font, panels and colours (from your own Minecraft install: nothing is copied). Cyberpunk keeps doing the work and keeps its
input (F, E/Q/1-4/wheel in dialogues); it only sends the information to Minecraft.

**What is on the screen** (it scales with Minecraft's GUI scale and never covers the hotbar, effect icons, boss bars or
chat):

| Where | What |
|---|---|
| **Top left** | the **radar**: an 80 × 80 map in the style of a Minecraft map item. It shows the [Atlas](#the-atlas-map) map of Night City around you and your own blocks (24 m), people as dots (red hostile, white neutral), cars as grey squares, Minecraft mobs, map pins with Minecraft's own map markers, an arrow for Steve and an arrow at the edge that points to your quest objective. It rotates with where you look. |
| **Under the radar** | **eddies** with a gold nugget (`€$ 12450`), the **wanted level** as up to five stars, the **time** and **district**, and the **speed** in km/h in a vehicle |
| **Top right** | the **tracked quest**: its title in yellow, its objectives with a box or a tick, counters ("2/3"), "(optional)" and the **distance to the waypoint** in metres |
| **Waypoint** | the objective also shows on Minecraft's **locator bar** (the strip near the hotbar), so you can follow it in any direction; if the bar is not shown, the arrow on the radar and the quest panel still point to it |
| **Right of the crosshair** | the **interaction prompt**: a Minecraft tooltip box with `[F] Talk`, `[F] Open`, `[F] Pick up` (the key in yellow). Hold actions show a fill bar and inactive ones are grey. It replaces Cyberpunk's own "F ..." prompt for doors, people, items and getting into cars. |
| **Bottom centre** | the **dialogue choice list**: a tooltip-style list above the subtitle. The selected line starts with `>` in yellow, quest-important lines are gold, unavailable ones grey; payment and skill-check suffixes show; a **timed** choice gets a timer bar like a boss bar. Pick as before: E/Q or the wheel scroll, 1-4 pick, F confirms. |
| **Above the hotbar** | **subtitles**: the speaker's name in aqua and the line in white, fading after its time |
| **Toast area** (top right) | **notifications** as Minecraft toasts with an icon: job updates, new items, eddies, experience, level ups, messages, shards, zones, wanted level, warnings and saves ("Saving Night City...", "Saved"). **Phone calls** stay on the toast while they ring: `[T] Answer / hold [T] Reject`. |
| **Whole screen** | in a pass-through menu (below): a Minecraft title bar with the menu's name and a grey and white bevelled border |

**Phone calls.** When someone calls, a toast says "Incoming call: <name>" with `[T] Answer / hold [T] Reject`. Press **T** to
answer, hold **T** to reject (if the call can be rejected). During the call the toast says "On call: <name>", and ends with
"Call ended". T is Cyberpunk's only while a call rings or an actionable notification shows; the rest of the time it is
Minecraft's chat key.

**Menus replaced by Minecraft screens.** The Cyberpunk key still does what it always did, but the menu that opens is a
Minecraft screen:

| You press | You get |
|---|---|
| **Esc** or **H** | the **Night City pause screen** ("Night City (paused)"): Back to Night City, Save Game, Load Last Save, Map, Journal, Messages, Night City Menu..., Minecraft Options..., Quit to Cyberpunk Main Menu (it asks first). **Cyberpunk is paused while this screen is open.** Saving says "Saving is not possible right now" when Cyberpunk does not allow a save. |
| **M** | the **Atlas**: Night City as a Minecraft map, with every map pin (see [The Atlas (map)](#the-atlas-map)). **Fast travel is not offered from the Atlas in 1.0.0:** selecting a fast-travel point tells you to use the Night City map through *Night City Menu...* |
| **J** | the **Journal**: Main Jobs, Side Jobs, Gigs and Other, each quest with its objectives (done ones ticked), a **Track** button and a "Tracked" mark |
| **Messages / the phone** | the **Phone**: your contacts and message threads, with a Reply button that sends the choice you pick |
| **I** (or Cyberpunk's crafting menu) | Minecraft's **inventory** |
| **P** | the **skill tree** (the same screen as K) |
| **U** | the Cyberware screen (as before) |

Crafting benches in the world are different: they are pass-through menus (below).

#### The Atlas (map)
**M** opens the Atlas: Night City drawn like a Minecraft filled map. Nothing of Cyberpunk's own map art is used: Cyberblock
measures the city itself with Cyberpunk's collision (long rays straight down) and keeps the **highest surface of every 2 m
square**. Minecraft colours it like a map item: **streets and open ground light sand**, low buildings light grey, buildings
grey, tall buildings dark grey, megabuildings and towers nearly black, **water blue**, with Minecraft's map shading (an edge
facing north is brighter, the ground north of a tall building darker). Squares nobody has measured yet stay plain parchment.
- **It fills in as you move:** while Cyberblock is on, about 48 rays per tick map everything within about 96 m of V, so the
  streets you walk through appear on their own (the **radar** shows the same map around you). **Driving maps more:** in a
  vehicle Cyberblock casts its own rays every frame, so a fast drive leaves a band about 60-96 m wide on each side of the
  road.
- **Survey once, and every world and save uses it.** Type **`/cyberblock survey`** once, stand somewhere safe on foot and
  leave the game alone (AFK). **It takes 15 minutes at most** (it aims for about 12). Cyberblock makes V invisible and
  invulnerable, locks saving, and flies V over Night City: first the city itself (worked out from the fast-travel points
  outside the Badlands, never the open sea), outward from where V stands so the map is useful within minutes, then the
  Badlands as a coarser pass. At each stop V hangs in the air until the city has streamed in (half a second, 2 s at most),
  everything already loaded around V is measured at once, and V flies on towards the next stop meanwhile. Cyberblock
  measures how fast your PC does this and plans the detail to fit the 15 minutes: every 2 m of the city if it can, else
  every 4, 8 or 10 m (the whole city coarser rather than gaps; walking and driving fill in the detail later), and the
  Badlands at 8-20 m. A boss bar at the top shows the 200 m squares done, the pass (city, outskirts) and an honest "~N min
  left"; a toast says when it is done. **At the end V is put back exactly where V stood**, visible, with saving unlocked.
  The map is kept **once for the whole installation** in
  `%APPDATA%\.minecraft-cyberblock\cyberblock\nightcity-map\` (Night City is the same in every Minecraft world and every
  Cyberpunk save), so you only ever survey once.
  - `/cyberblock survey stop` ends it early (V goes back home); so do **F9**, getting into a vehicle, a scene or combat
    starting, or Minecraft disconnecting. Opening a Cyberpunk menu only pauses it (the paused time does not count).
  - It resumes: `/cyberblock survey` again skips every square already on the map (also if the 15 minutes ran out). On a
    finished map it says Night City is already mapped; **`/cyberblock survey redo`** maps it all again.
    `/cyberblock survey status` shows the progress.
  - It only starts while Cyberblock is on, V is on foot, alive, not in combat, not in a scene, not gliding. The game runs
    at about 25 fps while it surveys (V is held in the air; that frame time goes into the rays).
- **Pins:** every pin of Cyberpunk's world map (fast-travel points, vendors, ripperdocs, gigs, quests, apartments, cars and
  the rest) as one of Minecraft's own map markers. **Hover** a pin for its name, its kind and its district; the buttons at the
  top (**Quests, Travel, Shops, Gigs, Places**) hide or show each kind; where pins would cover each other the more important
  one is shown (quests always). Zoomed out, **district names** label the map.
- **Moving around:** drag to pan, the wheel or **+**/**-** to zoom (it stays crisp: every square is one block of colour),
  **You** centres the map on V. The tracked objective is the red marker and V is the white arrow.
- **Tracking:** select a quest pin and press **Track** to track that job (as the Night City map does for quest pins); other
  pins are tracked on the Night City map (*Night City Menu...*). The Journal's **Track** works as before.

#### The Cyberblock settings screen
**Esc, then Cyberblock...** (a button on Minecraft's pause screen and on the Night City pause screen), or
`/cyberblock settings`, opens one place for Cyberblock's own settings. It is plain Minecraft widgets; a row you may not
change is greyed, and its tooltip says "Only the host or an operator can change this".
- **Third-person camera:** distance, shoulder, FOV, auto-recenter and vehicle distance (see
  [the camera options](#third-person-camera-options-and-the-debug-overlay)), and **Step-up assist**
  (see [stairs](#mining-placed-blocks-stairs-and-the-step-up-assist)).
- **Creative can erase the replica permanently:** the world setting of
  [the outline blocks](#creative-mode-and-the-outline-blocks) (off by default; only you in your own world, or an
  operator, can change it).

#### Menus Cyberblock cannot replace
Some Cyberpunk menus have no Minecraft equivalent that could do the same job: **shops and vendors, the ripperdoc, the
stash, the wardrobe, crafting benches, breach protocol, arcade machines, the braindance editor, tutorial and shard popups,
settings, save and load lists, the death screen and loading screens.** These stay Cyberpunk's own menus, but **restyled**:
Cyberblock tints them with Minecraft's colours and puts a Minecraft frame (a title bar with the menu's name and a bevelled
border) around them, and Minecraft's own world is not drawn over them. When the menu closes, everything is back.
- **Limitation: the font.** Minecraft's font cannot be copied into Cyberpunk, and making an original pixel font for Cyberpunk
  needs WolvenKit, which this project does not use. So **text inside these pass-through menus stays in Cyberpunk's font**.
  Colours and panels are Minecraft's.
- A tutorial or shard popup that waits for input (the game is paused by Cyberpunk itself) and the braindance timeline are
  shown the same way.
- Loading screens during quest teleports show "Loading Night City..." drawn by Minecraft as a cover.
- **Escape hatch:** if you ever want Cyberpunk's own pause menu, open the Minecraft pause screen and click **Night City
  Menu...**. It opens the real Cyberpunk menu once. If Minecraft is not running or not linked, every Cyberpunk menu works
  as in vanilla.

### Playing together (LAN)
Since Phase 1 every world, including your singleplayer one, runs through a small server, so a friend can join it. You play
exactly as before: you are the **host** (your Minecraft is linked to your Cyberpunk). Your friend is a **guest**: a second
Minecraft player in your Night City, with **Minecraft only** (no Cyberpunk needed). Both of you need the Cyberblock mod,
from the same release (the version must match in major and minor, for example 1.0.x with 1.0.y).

**Start.** In your Minecraft press Esc, **Open to LAN**, **Start LAN World**. Minecraft prints the port in chat ("Local game
hosted on port ..."). Your friend adds your address and that port under Multiplayer, Direct Connection (same network or a
VPN; nothing is opened to the internet by Cyberblock). A world you open to LAN has one Cyberpunk host (you); a dedicated server
allows up to `maxHosts` (4 from Phase 3), and a linked player beyond that joins as a guest.

**What reaches your Cyberpunk game** (a guest's actions are real Minecraft actions that Cyberblock turns into Night City
effects, exactly like yours):
- TNT, creepers, fireworks and wind charges near V become Cyberpunk explosions.
- Hits, arrows, fire, leads and fishing rods on an NPC or car hurt it (they hit the same invisible proxies as yours).
- Blocks the guest places become collision in your Night City; a scan or quickhack of the guest on one of your NPCs runs
  in your game.
- Since Phase 3 the guest also **sees Night City**: the streets as solid replica blocks and the people and cars as figures
  (see [Night City as Minecraft](#night-city-as-minecraft-phase-3)).

**Your guests' permissions.** You decide what guests may do to *your* game: `/cyberblock guests <explosions | damage | hacks |
devices | spawns> on|off`. Explosions, damage, hacks and devices are on, spawns are off. A switched-off action still
happens in the Minecraft world (a guest's TNT still breaks Minecraft blocks); it just does not reach your Cyberpunk
game, and the guest sees "blocked" on the action bar. Your own actions are never limited. Server operators list sessions,
roles, versions and message rates with `/cyberblock hosts`. Since Phase 4 the `devices` toggle has one switch per kind of device
(`/cyberblock guests devices door off` blocks only doors; see [Devices](#devices-phase-4)).

**Messages you may see.**
| Message | Meaning and what to do |
|---|---|
| "Cyberblock version mismatch: server X, your mod Y. Update from the modpack." | the two mods differ in major.minor: install the same release |
| "Cyberblock: your Cyberpunk plugin is X but your mod is Y; you can play as a guest. Reinstall both from the same release." | your Cyberpunk plugin and your Minecraft mod are from different releases: you play as a guest until you reinstall both |
| "Cyberblock: another Cyberpunk host is already active (max N)" | the server allows N hosts and they are taken: you play as a guest |
| "This server needs the Cyberblock modpack: ..." | the player has no Cyberblock mod: install it first |
| "Reconnecting to ... in N s" (with **Cancel**) | the connection dropped; it retries after 5, 10, 20, 40 and then every 60 s for 10 minutes. Meanwhile Night City is cleaned up as if the link had dropped, and when you are back the whole ground returns |
| "This server needs a password: type /login <password>" | a dedicated server with a join password: type `/login` and the password the owner gave you (see "On a dedicated server") |
| "Cyberblock: too many messages from your Cyberpunk link" | the server's rate limit kicked a link that flooded it: restart Cyberpunk and Minecraft, and report it if it was not a test |

**Leaving.** When a host quits or loses the link, the server removes that host's collision, proxies and tethers at once;
nothing of it stays in the world file. Reopen the world and the ground comes back.

**Testing it yourself (developers).** From a git worktree (never the folder you play from), `uv run python -m tools.mpcheck
--all` runs the automatic checks (a test server, offline clients and fake hosts; it needs about 3 GB of free commit per client
and stops everything it started). `uv run python -m tools.mpcheck --guest <port>` starts one offline test guest (name
`CbGuest1`), but a world opened to LAN checks every Minecraft login and refuses it ("Invalid session") until the world's
owner types `/cyberblock lan offline on`. With a join password set, such an offline guest must type `/login` on every join:
an offline name's login holds for that connection only and is never remembered (anyone could type that name), while a
real-account player who logged in earlier stays verified.

#### On a dedicated server (Phase 2 tools)
The same mod runs on a dedicated Fabric 26.3 server (a hosting panel's **Custom Jar** option runs the Fabric server launcher
jar). The step-by-step move of a hosted server, with the way back, is section 6.3 and 6.4 of the multiplayer plan; the
player guide for it comes with the Phase 2 docs. What is already there:
- **Admin commands** (operators): `/cyberblock status` lists every player's role, session state, `mod X dll Y`, message
  rates and the loaded content; `/cyberblock hosts add|remove <name>` edits who may host (level 3, like `/whitelist`) and
  applies at that player's next handshake. The server log starts with `cyberblock server: ready (protocol 1, maxHosts N,
  requireMod, whitelist on|OFF)` and warns every minute while a dedicated server runs without a whitelist. The `joinHelp`
  text of `config/cyberblock/server.json` is added to every refusal.
- **Join password (a server without a whitelist).** Set `"joinPassword": "<your password>"` in
  `config/cyberblock/server.json` (at most 64 characters) and restart the server. Every player who joins is then held in
  place with "This server needs a password: type /login <password>" until they type `/login <password>` (or
  `/cyberblock login <password>`; with the Cyberblock mod a small password box opens by itself). Until then they cannot
  move, build, break, use or pick up items, chat, take damage or link their Cyberpunk game; their Cyberpunk link starts
  right after the right password. One try per second; five wrong tries, or 120 s without the password, disconnect them.
  The server remembers each player who logged in on this world, so they type it once; change the password and everyone
  must log in again. A player who logged in may host Cyberpunk like a whitelisted one (the `hosts` list still applies).
  The minute warning about the missing whitelist stays quiet while a join password is set. Operators see `join password:
  on|off` in `/cyberblock status`, `/cyberblock password` shows how many players are verified and `/cyberblock password
  reset` (level 3) clears that list. The password is never logged, sent to players or shown by a command; share it the way
  you would share the server address.
- **Tools** (run by the server owner from a checkout): `tools.server_sync` backs up a server over FTP with a manifest,
  verifies it and uploads only staged files (a dry run unless `--go`; it has no delete command and refuses to overwrite
  files outside an allowlist; the FTP password is read only from the environment or a hidden prompt, never printed);
  `tools.server_bundle` stages the server files outside the repo; `tools.modpack` and `tools.respack` build the modpack
  (`dist/cyberblock-<version>.mrpack`) and the resource pack for friends.
- **Join Cyberblock.** A modpack with a `config/cyberblock/join.json` adds a **Join Cyberblock** button to the title screen
  that connects to that server.

### Night City as Minecraft (Phase 3)
Until now a guest saw only the Minecraft world and what their actions did. With Phase 3 a guest sees **Night City itself, built
as Minecraft** from what the hosts have explored, and can play in it even when no host is online.

**The replica.** Whatever a host's Cyberpunk game sees around V (streets, sidewalks, walls, glass, metal, brick, tile, water,
neon signs) becomes **solid material blocks** in the server's world, the colour of what they stand for: grey and dark concrete,
asphalt, sidewalk, glass, metal, brick, tile, ground, water, and **16 glowing neon colours** (light level 15, the vanilla dye
colours). Doors, gates and other devices are left out on purpose, so a door never becomes a wall. The blocks are saved in the
world:
- the map **grows as anyone explores**; leaving a host's probe radius never removes anything, only a spot that Cyberpunk saw
  empty again (a car that drove away, a door that opened) does;
- when the host quits Cyberpunk, the replica **stays**; the host's own people and cars go with the session (no live figures
  without their owner);
- **your own screen is not changed:** a host sees real Night City, so the replica blocks are simply not drawn on a host's
  client while Cyberblock is active (nothing is drawn twice); guests and hosts who are Off see them;
- replica blocks are **never sent back to Cyberpunk** as player blocks, and breaking one never changes Night City;
- explosions never remove replica blocks (Night City does not break); a guest's TNT still reaches the host's game when the
  host allows `explosions`;
- the [Atlas](#the-atlas-map) and the radar of a guest draw from the same shared map the server keeps (a host also adds
  what its own Cyberpunk surveys), so every player's map is the one growing Night City.

**Breaking replica blocks (the Q5 rule).** They are like the outline blocks of [Creative mode](#creative-mode-and-the-outline-blocks):
in **survival and adventure** a replica block cannot be broken; in **creative** you may break one, and it **comes back in about
10 seconds** (or at once when a host's Cyberpunk reports that spot again). With the setting **Creative can erase the replica
permanently** on, a broken block stays broken until a host's probes report the spot again. Blocks that players place are
different: they are yours, they become collision in the hosts' Night City and can be broken by anyone allowed to.

**People and cars.** A guest sees the host's Night City **people and cars as figures** moving at real speed: a plain human
figure for people, a box car for vehicles (hostile people get a red name tag; since Phase 5 they also get [skins, poses and colours](#figures-and-skins-phase-5)).
A car that V destroyed (shot, hit with a rocket or blown up after V damaged it) stays as a dark wreck for 5 seconds, a person
who dies lies down for 3 seconds. A car that wrecks itself without V's help (it crashes into a wall on its own) just goes: the
plugin reports only deaths of what V hurt. The host does not see
them (the host sees the real ones). They exist only while their owner is playing.

**Time of day and weather.** While a host is playing, the Minecraft world follows that host's Cyberpunk: **Cyberpunk's night is
Minecraft's night** (the clock is set from the game time every second; 06:00 is sunrise and 00:00 midnight, and the daylight
cycle is switched off), and rain and storms follow Cyberpunk's weather when the game lets the plugin read it (a storm is rain
with thunder; any other weather is clear). With several hosts the **first host is the primary**: a second host never changes
the time or the weather. With no host online the world keeps the last values.

**Offline Builds.** Guests may build on the replica **while the host is away**. When the host's Cyberpunk starts again, the
usual resync gives the host every block that was placed, so a wall a guest built across a street **stops V at once**, and the
host sees a toast "While you were away: N blocks placed, M removed (by A, B)". `/cyberblock changes` lists the changes by
region with coordinates (the server keeps the last 30 days).

**Meet Up.** `/cyberblock meetup <player>` asks that player to meet you; they accept with the clickable chat line (or
`/cyberblock meetup accept`) within 60 seconds.
- A **host** who asks anyone: V is teleported in Night City to the same coordinates as the player (Cyberpunk waits for the
  streets to load there, then puts V on the ground), and Steve follows V. If it cannot, the reason is shown ("no ground",
  "timeout" after 15 seconds, or "blocked" in a vehicle, a scene or a fight).
- A **guest** who asks a host: the guest is teleported next to the host's Steve.
- 30 seconds between your requests; not into a match zone (a later phase); not while your V is held or in a vehicle.

**More than one host.** A Phase 3 server allows **4 hosts at once** (`maxHosts` in `config/cyberblock/server.json`, default 4;
a singleplayer or LAN world keeps one host). The default 4 applies only to a `server.json` that the server writes new: the file
is written with every value on the first start and kept after that, so **a server that ran an earlier version still says
`"maxHosts": 1`**. Server owner: open `config/cyberblock/server.json`, set `"maxHosts": 4`, save and restart the server (the
startup line then says `maxHosts 4`); until then a second linked player joins as a guest. Each host's people, cars, hits and hacks stay with their own game even when two
hosts' ids collide, and where two hosts' streets overlap the first block placed wins.

**The Apex server's world.** The dedicated server runs a **void world** (level type `cyberblock:nightcity`, level name
`nightcity`), so the only ground in it is the replica and the small **spawn platform** where players arrive before Night City
is explored. At start the server checks that its world really is the void world (`worldcheck`) and says so in its log.

**A web map.** A live map of the replica for people with a browser is possible with BlueMap (a Fabric 26.3 build exists):
[docs/WEBMAP.md](WEBMAP.md).

**Which way is it working? (for the test.)** Some features have a written fallback if the game does not offer the exact call,
and the plugin writes **one line saying which way is active** into `Cyberblock.log`: where the surface **material** of a probe
hit comes from (the hit's material name, the mesh name, or the guess by height and district), how the **weather** is read (or
that it cannot be), how **Meet Up** moves V (waits for the streets to load, then snaps to the ground) and how **park** keeps V
unseen. [Re-test: Wave B, Phase 3](TESTING-PLAN.md) lists what to look for.

### Devices (Phase 4)
Night City is full of things that switch: doors, gates, lights, cameras, terminals, vending machines, traffic lights, lifts, turrets,
screens. With Phase 4 every one of them that a host's Cyberpunk game has near V (within 48 metres) shows up in Minecraft as a **block you
can use**, and using it changes the real thing in the host's Night City, both ways:
- a **guest opens a door** in front of V: the door opens in Night City, and V can walk through;
- a **pedestrian opens a door** in Night City: the door opens in Minecraft a moment later;
- the **host** does it too: Steve may use a device block like anyone, and Night City's own state always wins.
- a **redstone block** does it for you: a Device Relay linked to a door opens it when a lever is flipped ([Redstone hacking](#redstone-hacking-phase-6), Phase 6).

**What each device is in Minecraft, and what you do with it.** Everything is a right-click on the block.
| Night City | In Minecraft | What a right-click does | Name for the toggle |
|---|---|---|---|
| Doors (single and double) | a wooden door, an **iron door** for a secure one, a **red lamp** above it while it is locked | opens or closes it; a locked door stays shut (it needs Breach or a Device Relay, a later phase) | `door` |
| Gates, garage doors, road barriers | a wide sliding gate that slides in steps | opens or closes it | `gate` |
| Lights and neon signs | a lamp in the light's colour | switches it on or off | `light` |
| Surveillance cameras | an eye block with a vision cone of particles | switches it on or off | `camera` |
| Terminals and computers | a **Net Port** block | opens the **Breach Protocol** ([Phase 6](#breach-protocol-phase-6)) | `terminal` |
| Vending machines | a machine block (snack, drinks or gear machine) | opens a **shop paid in eddies** | `vending` |
| Traffic lights | a three-colour lamp block | nothing on its own: the **Traffic Tool** of [Phase 6](#the-interaction-map-phase-6) switches a district's lights | `traffic` |
| Lifts | a lift platform with a call button | the button is **greyed** (see below) | `elevator` |
| Turrets | a turret block that shows its mode | switches it on or off; **sneak** and right-click makes it friendly | `turret` |
| TVs and billboards | a screen in an item frame | distracts the people nearby (a distraction in Night City) | `screen` |
| Containers | a closed **crate** (a chest, only when the loot can be read) | nothing: its contents are unknown | `container` |

**Waiting for Night City.** The block never shows a state that Night City refused. You right-click, the action bar says "Waiting for Night
City...", and the block changes only when Night City answers **yes** (a refusal says why, and nothing changes; no answer in 10 seconds
says so). A device you cannot reach (more than 8 blocks away) or use twice within a fraction of a second is simply ignored.

**Greyed devices.** Two kinds of device stay greyed, with a message, and are never sent to Night City:
- **Quest devices.** A door that belongs to a mission (sealed), or a device the game has disabled, shows greyed with "This device belongs to
  a mission and cannot be used now". Night City never lets a mission door be touched.
- **What Cyberpunk cannot be asked yet.** The plugin cannot **call a lift** (nobody has proven that it can), so the lift's call button says
  "Night City cannot do that with this device yet". **Buying** is not a Cyberpunk action at all: a vending machine is a Minecraft shop with
  its own stock (below), nothing is bought in the host's Night City. **Breach** is the Minecraft [Breach Protocol](#breach-protocol-phase-6) of Phase 6.

**Vending machines and eddies.** A vending machine's shop sells Minecraft items for **eddies**, an item (`cyberblock:eddies`, and the
*Eddie Chip* worth 100, as its tooltip says) that is yours like any other: gigs and sales earn them in later phases, and an operator can `/give` some. The prices, the stock and when it restocks come from the shop files (`data/cyberblock/cyberblock/shop/`), so a server can
change them with a datapack. The host's own Cyberpunk money is a different thing and is not touched.

**Who may use what.** The host decides, per kind of device, whether guests may use it: `/cyberblock guests devices <type> on|off` with a
type from the table (`door`, `gate`, `light`, `camera`, `terminal`, `vending`, `traffic`, `elevator`, `turret`, `screen`, `container`);
`/cyberblock guests devices <type>` alone shows whether that type is allowed, and `/cyberblock guests devices on|off` switches all of them (the `devices` toggle of
[Playing together (LAN)](#playing-together-lan)). A blocked guest sees "blocked" on the action bar and nothing reaches Night City. The host's own
use is never blocked.

**Which devices you see.** A **host does not see these blocks on their own screen**: Night City has the real devices, so the blocks are not
drawn (as for the replica); guests and hosts who are Off see them. They exist **only while their host is playing**: when the host quits
Cyberpunk, or a device is no longer near V for 10 seconds, its block goes. The replica never builds a wall where a device stands (doors are not
walls), and the blocks never replace something a player built.

**What is not carried over.** Only on/off and locked or open travel: a lift's floor, a traffic light's colour or a camera's alarm have no
field in this version of the protocol, so those blocks show a plain state. A device's identity comes from the game's own id, or, when the
game does not give a steady one, from its position: a device that moves is then a new device. A closed crate stands where a container's
loot cannot be read.

**Which way is it working? (for the test.)** The plugin writes one line saying where the device **ids** come from (the game's own entity
hash, or the position fallback), one saying how **changes** are found (the game's events, or polling twice a second), and one saying whether the
**device-box filter** is needed (probe rays that hit a device are cast past it, or the DLL drops hits inside a known device box).
[Re-test: Wave B, Phase 4](TESTING-PLAN.md) lists what to search for.

**Until Phase 0 is run in game.** The in-game CET checks (the "Cyberblock Phase 0" window of the CET overlay) have not been run yet, so the
replica materials, the weather, the device ids and the NPC tags use the research's best guess with a runtime check, and each writes one log
line naming the path it took (the guess or the fallback). Everything works either way; the CET lines only make the guesses exact.
[Re-test: Wave B (Phases 3, 4, 5)](TESTING-PLAN.md) is the one combined test.


### Figures and skins (Phase 5)
With Phase 5 a guest no longer sees plain boxes and grey humans: **Night City's people and cars look and move like Night City's.**
The figures come from the host's game (the same list as in [Night City as Minecraft](#night-city-as-minecraft-phase-3)); what is new is
that every person and car now carries a few **tags** that the server turns into a look.

**People.** Each figure gets a skin from its **faction, role, gender and type**: gangers in their gang's colours (Tyger Claws,
Valentinos, Maelstrom, 6th Street, Animals, Scavengers, Voodoo Boys, Mox, Aldecaldos), corporate security (Arasaka, Militech, Kang Tao),
NCPD patrol and SWAT, Max-Tac, Trauma Team and Netwatch, civilians (corpo, street, worker, tourist, homeless), a ripperdoc and other
vendors, a fixer, a bartender and cyberpsychos. About 30 original skins, drawn by a script (pixel art of our own: no logos and no
copied art). **Women are drawn on the slim model**, men on the classic one; a person whose gender the game does not say is drawn
classic.

**Poses and animations.** A figure stands, **walks** and **runs** at the pace it has in Cyberpunk, **aims**, **fires** (a muzzle flash),
**crouches**, **tumbles when it is knocked over** (a limp ragdoll) and **lies down when it dies** (3 seconds, then it goes). A walking
figure moves smoothly at real speed; there is no rubber-banding at 10 updates a second.

**Hostile people.** A person who is hostile to V has a **red outline you can see through walls** (the same technique as the Kiroshi
outline), a name tag in the threat colour, a red flash and heart particles when hurt and explosion particles when blown up.

**Cars.** About twelve vehicle models built in the mod (a sedan, a sports car, a truck, a van, a bike, a taxi, a police cruiser with a
light bar, an armoured van, aerodynes and more), **in the car's own colour** when Cyberpunk says it; a police car that is **hostile
(a chase) flashes its lights**, and a destroyed car stays as a dark wreck for 5 seconds.

**Which way is it working? (for the test.)** The plugin cannot read everything from the game, so some of this has a **written
fallback**, and `Cyberblock.log` says which one is active:
- `npcs: tags full ...` means people arrive with faction, gender and archetype (both gender and archetype are needed);
  `npcs: tags faction only ...` means they do not (the skin then comes from the faction and the threat alone, and every woman is
  drawn classic); the line appears only while people are near;
- **fixers, bartenders, medics and mechanics** are never in the game's data: the server's content (`npc_role`, `skin_map`) decides
  those looks; vendors and ripperdocs come from the game's vendor type;
- **walk and run** are not readable in the game either, so the plugin works them out from how far each figure moved between two
  updates (walking above 0.6 m/s, running above 3.2 m/s);
- **car colours** come from the game's colour template of the car's look; a car without one gets its model's default colour, and
  `npcs: colour format hsb (unconfirmed)` says how the three numbers are read until the colour check (Phase 0, F2) has run.
[Re-test 5](TESTING-PLAN.md) lists what to look for and which lines to paste to Claude.

**Limits.** The skins are procedural pixel art, so expect "recognisable by faction", not portraits; what a figure does is only as
good as what the game tells the plugin (a person in a cutscene may stand still).

### Your cyber skin (Phase 5)
**A host always looks like V, to everyone.** A player whose Cyberpunk is linked (a host) is drawn in the **cyber skin** on every
screen, including their own in third person: **male V** by default, or **Lucy** (female V, on the slim model) with
`/cyberblock body female`; `/cyberblock body male` switches back. The choice is saved on the server for that player and every client
shows the change at once. A guest (no Cyberpunk link) keeps the normal Minecraft skin.
- It is forced: there is no option to turn it off, and no skin from the internet is needed (both skins are inside the mod, so it
  also works offline).
- A host's name tag starts with a coloured **`[V]`**, and the player list (Tab) shows a small marker next to linked players.
- **Allies:** players on your team show as coloured outlines through walls. Teams come with the matches of a later phase; until then
  nobody is an ally and the hook stays empty.
- NPC figures follow their own gender, not the host's body choice.
Check it with [Re-test 5](TESTING-PLAN.md), P5-5.

### Redstone hacking (Phase 6)
With Phase 6 you hack Night City **the Minecraft way: with blocks and redstone.** A handful of blocks watch Night City or act on it, and a
redstone signal is all they need, so a lever, a button, a pressure plate, an observer or a whole clock circuit can open a door, light a lamp,
set off a charge or fire a quickhack in the host's game. They work for **guests and hosts alike**; the host's own are never blocked, a guest's
obey the host's permissions (see "What the host controls" below).

**The Cyberdeck Link.** First you tell a block which Night City device it belongs to: **scan the device with the hack key (G)**, then
**right-click the relay or sensor block with the Cyberdeck Link item**. The link remembers the host, the device's (rounded) position and its
kind, not a loose id, so it still works after the host restarts Cyberpunk or the server restarts; while the host is away the block shows
**unresolved** and does nothing.

| Block | What the redstone does | What happens in Night City |
|---|---|---|
| **Device Relay** | a signal that **turns on** (a rising edge) toggles the linked device | a door opens or closes, a light switches, a camera, a turret or a screen toggles (`devact toggle`); the host's `devices` toggle applies to a guest's relay |
| **Proximity Sensor** | **gives out a signal** while someone is within range (1 to 16 blocks, set on its screen) | it looks at the people and cars that the host's game shows as figures, and at the host's own Steve; the screen picks which of them count: a person, a car or V |
| **Heat Sensor** | **gives out a signal** of 0 to 15: the nearest host's police heat, 0 to 5 stars, times 3 | a wanted level of 3 stars is signal 9; a later phase adds Server Heat as a second mode |
| **Breach Port** | a signal makes it **fire the chosen quickhack** at the targets in range (its screen picks the hack) | the hack runs in the host's game and **costs RAM**, taken from a **Cyberdeck block** placed next to it |
| **Charge** | a signal sets it off | **a real Cyberpunk explosion** at the block's position (the explosion goes to the host's game; the host's `explosions` toggle applies to a guest's charge) |
| **Cyberdeck block** | (none) | stores RAM for the Breach Ports next to it and **refills slowly** |

**Limits that keep the world calm.** Every block acts **at most twice a second**. A chain of links that toggles the **same device more than
8 times in 4 seconds** is a loop: the blocks involved are **frozen for 30 seconds** with a puff of smoke (`cyberblock.redstone.loop` in the
log). Nothing is lost, and the host's game is never flooded.

**A redstone trap for NPCs.** Put a Proximity Sensor on the street, set it to people, wire it to a Charge or a Breach Port loaded with Short
Circuit, and watch what the first pedestrian does. A door that opens when a button is pressed outside it, a lamp that lights when a car comes
by, a lift of redstone: the blocks are plain, what you build with them is yours. Check it with [Re-test 6](TESTING-PLAN.md), P6-1 to P6-3.

**What the host controls.** Device Relays and sensors count as device control (`/cyberblock guests devices <type> on|off`), Breach Ports and
quickhacks as hacks (`/cyberblock guests hacks on|off`) and a Charge as an explosion (`/cyberblock guests explosions on|off`). A refused action
still happens in Minecraft (the lever still clicks), it just does not reach that host's Night City, and the guest sees "blocked" on the action
bar.

### Breach Protocol (Phase 6)
**Right-click a Net Port** (the block a terminal or computer becomes, see [Devices](#devices-phase-4)) and the **Breach Protocol** opens: a
grid of hex codes, a buffer, a list of **daemons** to upload and a timer. It is the hacking game of the street, built from Minecraft's own
widgets and font, and it is **played on the server**, so no client can cheat.
- **The grid.** 5 x 5 for an ordinary device up to **7 x 7** for a well-protected one (the size comes from the device's security, set in
  `breach_tier`); each cell is one of the codes `1C`, `55`, `BD`, `E9`, `7A` and `FF`.
- **The picks.** You pick cells with the mouse. The **first pick is in the top row**, the next one in the **column** of the first, the next in
  the **row** of that one, and so on, as in the classic game; a cell cannot be picked twice. Every pick goes into the **buffer** (4 to 8
  codes). The timer bar starts running with your **first pick**; a Breach left open without a pick closes after a minute.
- **The daemons.** One to three sequences of codes are on the list (the daemon files of the device kind decide which). A daemon is **solved**
  when its codes come one after another in your buffer. More daemons and longer sequences mean a **stronger effect** (strength 1 to 3).
- **What a solved daemon does.** It is **a real action in the host's Night City**, sent to the host's game: open a door, **disable the
  cameras** of the area, **reveal enemies** (as the Ping chip does), **overload** a device, and more (they are data files, so a datapack can
  add one). The **Net Port that you breach is the one it runs from.**
- **Failing.** Running out of buffer or time **raises an alarm in the host's game** (the cameras of the area alert): breaching is a risk.
  A failure also costs **Heat**; the Heat meter belongs to a later phase, and until it exists the host's wanted level goes up by one **only
  if the game allows that** (the plugin says which in the log).
- **No walking away from a bad grid.** **Closing the screen after your first pick is a failure** (the alarm and the Heat), and so is walking
  more than 8 blocks away from the Net Port. Closing it before any pick costs nothing, and the next right-click shows a new grid. After a Breach
  that ended with a pick, **that device is locked for 30 seconds** for everyone; after a **failure you cannot breach any device for 30 seconds**
  (the action bar says so); and a host gets **at most one alarm a minute**: a second failure inside that minute raises no new alarm and costs no
  Heat.
- **The Firewall implant.** A new 18th piece of [cyberware](#cyberware-u-y-l-and-o), the **Firewall**, makes breaching **you** harder (the
  grid grows). It does nothing in Cyberpunk itself, and it only matters once players can hack players (a later phase).
- **Who may.** A guest needs the host's `hacks` toggle (`/cyberblock guests hacks on|off`) and the `devices` toggle with
  that kind of device (`/cyberblock guests devices ...`). A refused Breach does not open: the action bar says blocked, and nothing reaches the host's game.
Check it with [Re-test 6](TESTING-PLAN.md), P6-4.

### The interaction map (Phase 6)
Every kind of thing you can do to Night City from Minecraft has **one tool**, and a guest uses them all.

| What you want | Your tool | What it does | Needs |
|---|---|---|---|
| Hack a person | the **Hack Wheel** (the hack key, G) on any figure you can see | the quickhacks you already know ([What each chip does](#what-each-chip-does)), now for guests too | the host's `hacks` toggle |
| Stop or steal a car | the **Vehicle Panel** (right-click a car figure with the Cyberdeck Link, or click a car in the Net Map's vehicle mode; it is a screen, not an item) | **lock the doors** (2 minutes), **kill the engine** (30 seconds), **force a brake** (a few seconds), **jam it** (3 seconds), then the car is as it was (shorter on the host's own car); **start it remotely** (that one stays) | the host's `hacks` toggle |
| Control traffic | the **Traffic Tool** (an item) | **switch the lights** of the intersection nearest V, **gridlock a road** (the cars within 25 blocks of where you aim stop for a minute) | the host's `hacks` and `devices` toggles |
| Black out a district, scout | the **Net Map** (an item and a screen built on the [Atlas](#the-atlas-map)) | **black out the lights around V for 1, 2 or 5 minutes** (lights and devices off, then back on), see a **camera's feed**, **outline** the figures of an area for your team, **ping** an area, and watch the host's **photo feed** (in the Net Map or on a feed map item) | the host's `hacks` toggle (also for the photo feed), and `devices` for traffic, blackouts and camera feeds |
| Talk to a host | the **Comms Panel** (an item) | sends the host a **waypoint** or a short message (up to 64 characters): it shows as a **toast and a locator-bar waypoint** on the host's Minecraft HUD | nothing (it never reaches Cyberpunk) |
| Break into a terminal | a **Net Port** | the [Breach Protocol](#breach-protocol-phase-6) | the host's `hacks` toggle |
| Run the city | the **Overlord Console** | a later phase (Phase 8) | - |
| Build and blow things up | your hands and TNT | the normal game, [Layer 2](#layer-2-building-explosions-weapons) | the host's `explosions` toggle |

Every action is the same chain: **the tool asks the server, the server checks the host's permissions, the host's game does it, Night City
answers, and you see the result** (a message on the action bar, a changed block, a toast).

**How far a remote hack reaches.** A remote action reaches **everything the host's game is showing the server**: the car figures, the devices it
lists and any point within 150 blocks of a host's V, however far from V that is (this is the desk verdict of Phase 0's check (h); the in-game run
confirms it). If the host's game ever refuses a far target, the mod switches to the written fallback by itself: from then on only targets within
**50 metres of V** are reached, and anything farther says it is **out of range**. The distance is measured **from V, not from you**: the Net Map and
the Traffic Tool work from anywhere in the world. Only the Breach needs you at the Net Port, right-clicking a car figure needs the car in reach,
and a camera's feed needs the camera within your view distance (the view is drawn from what your client has loaded).

**Nothing a hack does lasts.** Each car hack wears off after its own time (the doors after 2 minutes, the engine after 30 seconds, a brake or a
jam after a few seconds; a remote start simply stays), a blackout after its minutes and a gridlock after a minute: the host's game puts
everything back as it was. It also puts everything back **at once** before **every save**, when the host leaves, when Cyberpunk loses its link
or when the host's game is no longer the host, so a save never keeps a locked car or a dark street.

**What the tools do behind the scenes, and what falls back.**
- **Traffic and blackouts** would use the game's traffic and district calls, but the game's scripts have none, so they **fall back to the device
  layer**: the traffic light nearest V (one intersection) and the lights and devices within 150 metres of V. The log says which way is active.
- **Camera feeds** are **not** Cyberpunk's own camera view (that would take over the host's screen): Minecraft draws the **replica of Night City
  from the camera's position and facing**, with the people and cars the figures show.
- **The photo feed** is the host's own view, shrunk to **128 x 128 map colours**, at most **once a second** and **only while someone is
  watching it**. It is **low-resolution by design**. If copying the picture would cost the host's game more than half a millisecond a frame,
  the plugin stops sending it for the session and there is **no picture** (a stand-in replica view for the feed is not built yet).
- **Comms** needs no Cyberpunk change at all: it is Minecraft's own HUD.

**Who may do what.** The host's switches: `/cyberblock guests hacks on|off` (every hack, Breach and remote action),
`/cyberblock guests devices on|off` (device control, also the traffic hacks, blackouts and camera feeds) and `/cyberblock guests explosions on|off`.
A blocked action leaves Minecraft as it was and shows "blocked" on the action bar. The host's own actions are never blocked. The **photo feed** is
behind the `hacks` toggle too: when the host turns it off, every guest watching the feed loses it at once.

**Which way is it working? (for the test.)** The scripts and the plugin write **one line** for every Phase 6 feature that has a fallback: which
traffic path, which blackout path, how vehicle calls are made, how far remote hacks reach, and whether the photo feed uses the compositor copy or
gives no picture. The server logs each remote hack it routed or refused and each Breach. [Re-test 6](TESTING-PLAN.md) lists what to search for.

### Talking to Night City (Phase 7)
With Phase 7 Night City's people are not only scenery: **right-click a figure that has a job** and Minecraft opens a screen with that
person's own words and wares. Every line of dialogue is original text from the server's content files (`data/cyberblock/cyberblock/dialogue/`),
picked by the person's job and what you are doing, so a server can change them with a datapack. Everything here is paid in **eddies**, the
Minecraft item (`cyberblock:eddies`, and the Eddie Chip worth 100) of [Devices](#devices-phase-4); the host's real Cyberpunk money is a
different thing and is only spent at Night City's own shops (see below).

| Who | What the screen offers |
|---|---|
| **Ripperdoc** | the **implants** you know from [Cyberware](#cyberware-u-y-l-and-o), with eddie prices from the `ripperdoc` content. The strongest implants come with a short **surgery minigame**: a timing bar that you have to stop in the safe zone. A slip costs health and gives a **30 second debuff**; it never kills you |
| **Vendor** | the shop's list (from the `shop` content): **buy and sell** items for eddies |
| **Fixer** | **gigs**, from the `contract` content: take one and Minecraft tracks its objective like a quest; the reward arrives when it is done. A gig is one of four kinds: **deliver an item**, **defeat a number of hostiles of one faction**, **reach a landmark**, or **breach a device** |
| **Bartender** | **rumours** about the city, and **drinks** that give a short potion-style buff |
| **Medic** | **heals** Steve for eddies |
| **Mechanic** | **repairs**: a guest's elytra and armour. For a host the plan is to repair V's current Cyberpunk car, which only works if the game call has been verified (see the limits below) |

**Where a person's job comes from.** The game itself only tells the plugin a person's **vendor type** (a ripperdoc, a clothes seller, a gun shop),
so vendors and ripperdocs get their screen from the game's data. **Fixers, bartenders, medics and mechanics are not in the game's data**:
the server's `npc_role` and `skin_map` content decides which figure is which (the same files that give them their [skin](#figures-and-skins-phase-5)).
The people of the [Afterlife hub](#the-afterlife-hub-phase-7) are always there, whoever is online, because the server puts them there itself.

**Cyberpunk's own vendors and ripperdocs, as a Minecraft screen (hosts).** When you open a shop in your own game while Cyberblock is on, Minecraft
now opens a **true Minecraft shop screen** instead of letting Cyberpunk's own show through: the stock is the shop's real stock, and a purchase or a sale
goes into your game and is **paid with your real Cyberpunk money** (the amount in the HUD). One unit per click, up to ten with Shift+click; the list
updates after every trade. The screen's **Night City vendor...** button opens Cyberpunk's own screen for the same shop (installing cyberware at a ripperdoc,
buyback). If the plugin cannot read a shop's stock, or the shop belongs to a mission (the **ripperdoc of the prologue** and anything quest-critical), the
usual Cyberpunk screen stays (the pass-through of [The Minecraft interface](#the-minecraft-interface)) and the log says so.
**Fast travel** is offered in the [Holo-Table](#the-holo-table-phase-7): your game's fast-travel points, and a click fast travels you the game's own way.
When the game does not allow it right now (a job locks fast travel, V is in a vehicle or in combat, a scene holds V) the list says so and a click only
says "Not now".

**Limits and fallbacks (Phase 0 and the in-game check).**
- **Hosts' shop stock** depends on Phase 0 check (f): the stock is read from the shop's own screen data when you open it. If a shop's stock cannot be
  read, that shop stays Cyberpunk's own screen (the written fallback); guests are never affected, their shops are the server's.
- **The mechanic's repair of a Cyberpunk car** needs a game call nobody has verified yet; until it is, that part is switched off (the written
  descope) and the mechanic repairs elytra and armour.
- The check list is [Re-test: Wave C, Phase 7](TESTING-PLAN.md), P7-1 to P7-4 and P7-7.

### The Afterlife hub (Phase 7)
The **Afterlife** is a place of its own on the server, a Minecraft dimension (`cyberblock:afterlife`) with its own build: a bar with booths, a
**ripperdoc corner**, a **row of shops**, the **Match Terminal**, **leaderboards** and the room of the [Holo-Table](#the-holo-table-phase-7). It is **always
day and nothing hostile spawns**, and it is open **even when no host is online**: its people (the bartender, the ripperdoc, the vendors) are the server's, not a host's.
The build is drawn from a layout file by a script (original, like all the art).

**Getting in and out.** `/cyberblock hub` takes you there, and so does a **hub portal** block (placeable in the replica) or the **Cyberblock...**
button of the pause screen. You leave **the same way** and are brought **back to where you came from**.

**If you are a host (Cyberpunk linked):** the hub is a **full-screen Minecraft** place, so Cyberpunk's picture is hidden while you are in it, the way
a loading screen covers the game. Meanwhile **V is parked**: invisible, invulnerable, unseen by the people of Night City (an NPC walks past and
ignores V), the camera and movement locked and saving switched off. Leaving puts everything back: Night City comes back, V is released where it was.
The cover only ever goes up over a parked V: you cannot go in while V is in a vehicle or held by a scene ("Not now"), and if your game still cannot park V
you are taken straight back out with the reason. If your link drops, Cyberblock goes Off or the session ends while you are in the hub, Cyberpunk releases V
by itself; nobody stays parked. When Cyberpunk comes back while you are still in the hub, the hub picks up again (full screen, V parked).
Before you have typed `/login` on a server with a join password, none of the ways in works.
**If you are a guest** there is nothing to switch: you simply walk in.

**What is in it.**
- **Shops** (hub vendors), the **bartender** and the **ripperdoc**: see [Talking to Night City](#talking-to-night-city-phase-7).
- **Leaderboards**: boards that show the scoreboards of the server: **match wins**, **gigs done** and **chaos**.
- The **Match Terminal**: where matches are made. It says "coming in Phase 8" until the modes arrive.
- The **Holo-Table**, in its own room.

**Limits.** The "unseen by NPCs" part of the park depends on Phase 0 check (g); when it is not available the plugin parks V by a fallback (a point near the
nearest fast-travel point), and the log line `park: on ...` says which method was used.

### The Holo-Table (Phase 7)
The **Holo-Table** is a block in the hub. Right-click it for a **shared live 3D model of Night City**: a height model built from the server's shared
map (the map every explored street adds to), coloured like the [Atlas](#the-atlas-map), with the replica's detail near the players. **Drag** to rotate
it, **scroll** to zoom, tilt it as you like. If the 3D view costs too much frame time, it falls back to a **2D map** (the Atlas screen again).

**Pins.** Every player is a pin on it: the player's head and name in the **team colour**, a **`[V]`** for linked players, and what they are doing (in a car,
in combat, downed). Optional layers (switches on the screen) show NPC density, enemy hot spots, devices and events; the layers for Server Heat, Gang Wars
territory, matches and Van drones fill in as those modes arrive.

**Spawning from the map.** Click a place and spawn there:
- the **district landmarks** (from the `landmark` content), your **team's base**, a **Spawn Beacon** (a block you can place) or **a friend** ("join a friend");
- a **Minecraft-only player** is put in the replica at that spot; for a **linked player V is teleported** there (the plugin snaps V to the ground and waits
  for the world to stream in) and Steve is placed to match, so both stay together. From the table in the hub you leave the hub first (Night City comes
  back and V is released), then V is teleported;
- you get **5 seconds of spawn protection**, then a **60 second cooldown** before the next spawn. You never spawn into a locked match zone, and inside a
  match only your team's spawns are offered.
- A host also gets its **fast-travel points** here (the Atlas itself stays a plain map): see [Talking to Night City](#talking-to-night-city-phase-7).

**Privacy.** Outside matches everybody's pin is visible, and **"hide my pin"** hides yours. In a match you see your own team's pins plus anybody a scan
revealed; spectators and the Overlord see all.

**Limits.** The 3D table may fall back to 2D on a slow PC; a spawn that cannot find ground says why ("no ground" or "timeout") and moves nobody.
The check list is [Re-test: Wave C, Phase 7](TESTING-PLAN.md), P7-5 and P7-6.

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
  (Optical Camo, the Projectile Launch System). Chips work from there with the hack wheel (hold G, wheel,
  right-click); see [netrunning](#netrunning-step-by-step).
- **Installed for you** (U shows them): the Cyberdeck, Sandevistan, Kiroshi Optics, Reinforced Tendons and Biomonitor; see
  [Cyberware](#cyberware-u-y-l-and-o).
- RAM is filled to the maximum whenever the kit is handed out.
- A slot that is already taken sends its kit item to the main inventory instead. A stack Steve already has is topped
  up. Whatever does not fit drops at his feet.

| Group | Items |
|---|---|
| Cyber | 2 RAM Shards, one of each of the 10 quickhack items; implants: Cyberdeck, Sandevistan, Kiroshi Optics, Reinforced Tendons, Biomonitor (installed, else in the bag) and Optical Camo, Projectile Launch System (in the bag) |
| Building | 64 stone, 64 glass, 8 TNT, flint and steel |
| Weapons | bow, 32 arrows, shield, iron sword |
| Layer 5 items | 2 leads, fishing rod, 8 wind charges, 8 ender pearls |
| Flight | elytra, 16 firework rockets |
| Potions | Swiftness, Invisibility, Leaping |
| Spawn eggs | 4 zombie, 2 skeleton, 2 creeper |

### Creative mode and the outline blocks
Night City's streets and walls exist in Minecraft as **outline blocks**: invisible barrier blocks that Cyberblock puts
around V so Steve, mobs and arrows have something solid to stand on. In creative mode you see them as outlines.
- **Survival and adventure:** they cannot be broken, and explosions never remove them.
- **Creative:** you may break one (it is only a Minecraft block: nothing happens in Cyberpunk). It **comes back in about
  10 seconds**, or at once when Cyberpunk reports that spot again. If you dig through the street and fall, Cyberblock
  catches you and puts you back on the last safe ground ("Caught you: back to safe ground").
- **Permanent erase:** the setting **Creative can erase the replica permanently** (off by default; Esc, **Cyberblock...**,
  or `/cyberblock outline erase on|off`; `/cyberblock outline` shows the state; operators, or you in singleplayer) makes
  broken outline blocks stay broken until you walk away and back and Cyberpunk reports the spot again.
- The server decides from your game mode, so a client cannot cheat around it. Log lines: `outline: <name> broke <x> <y> <z>
  (snap back in 10 s)` or `(kept: permanent erase)`, and `outline: restored N`.

### Commands
Press **T** (chat) or **/** (command line) and type right in Cyberpunk: while a Minecraft screen is open, the text you
type, Enter, Backspace, Delete, the arrows, Home/End and Tab all go to Minecraft, accents and emoji included. Enter
sends, Esc cancels. V stands still meanwhile. (Cyberblock.log notes "typing reaches Minecraft's screens" the first
time.)

Useful commands: `/cyberblock kit`, `/cyberblock settings` (the [settings screen](#the-cyberblock-settings-screen)),
`/cyberblock outline erase on|off`, `/cyberblock debug aim|view on|off`, `/cyberblock level` (re-align the ground to where V stands), `/cyberblock survey`
(map all of Night City once for the [Atlas](#the-atlas-map); `stop`, `status`, `redo`),
`/cyberblock meetup <player>` and `/cyberblock meetup accept` (go to a friend, see [Night City as Minecraft](#night-city-as-minecraft-phase-3)),
`/cyberblock changes` (what guests built while you were away),
`/cyberblock guests devices <type> on|off` (which Night City devices guests may use, see [Devices](#devices-phase-4)),
`/cyberblock guests hacks on|off` (whether guests may hack, breach and use remote hacks, see [The interaction map](#the-interaction-map-phase-6)),
`/cyberblock body male|female` (your cyber skin: V or Lucy, see [Your cyber skin](#your-cyber-skin-phase-5)),
`/cyberblock hub` (go to the Afterlife and back, see [The Afterlife hub](#the-afterlife-hub-phase-7)),
`/cyberblock perks free on|off` (free skill points), `/gamemode creative`, `/gamemode survival`,
`/xp add @s 30 levels`, `/effect clear @s`. Playing with friends: `/cyberblock guests <toggle> on|off` and, for server
operators, `/cyberblock hosts`, `/cyberblock status` and `/cyberblock hosts add|remove <name>` (see
[Playing together (LAN)](#playing-together-lan) and [On a dedicated server](#on-a-dedicated-server-phase-2-tools)).

---

## 7. What Cyberpunk stops doing while Cyberblock is active

All of this applies **on foot, while active**. Getting into a car (where only V's invincibility, the hidden body and the
hidden interface stay), pressing F9, or "Minecraft lost" restores everything else. Before every save, all of it is taken
off and put back afterwards, so your saves stay vanilla.

**V:**
- V is invincible (Steve takes the damage) and invisible: the body meshes are hidden (also in both vehicle cameras), and
  Steve stands or sits there instead.
- Weapons are holstered.
- **Cyberpunk's interface is invisible, always:** the HUD, the notifications, interaction prompts, dialogue choices,
  subtitles and the waiting sign are hidden (they keep running underneath, so F, E/Q/1-4 and the wheel still work) and
  Minecraft draws them instead. It does **not** come back for prompts or dialogues any more. The only times Cyberpunk's own
  interface shows are the pass-through menus (restyled), a popup that pauses the game by itself, the braindance timeline,
  and the two messages "Start Minecraft first" and "Minecraft lost".

**Blocked:**
- combat: guns, melee, Cyberpunk's own cyberware arms, grenades and gadgets (Cyberblock's own implants replace them:
  see [Cyberware](#cyberware-u-y-l-and-o));
- Cyberpunk's own quickhacks, healing items (X), the scanner (Tab) and the weapon/radial wheel (Alt);
- quick melee, zoom, and picking up or carrying bodies.

**Movement abilities, switched off:**
- double jump, charge jump, air hover, dodge and air dash;
- superhero landing, Kerenzikov slide, Sandevistan;
- Cyberpunk's own optical camo, terrain camo, sprint-shove and dash-shoot.

**Exceptions:** the **Reinforced Tendons** implant gives V the double jump back, **Fortified Ankles** the charge
jump, and Steve's **Dodge** perk the dash and air dash. Sandevistan, Kerenzikov and Optical Camo run only through
Cyberblock's own implants, which slow Night City or cloak V when you fire them. (Steve's Double Jump perk does not
give V a double jump: it upgrades the legs implants.)

**Keys** (the full table is in [Controls](#3-controls)):
- F5 does not quick-save: it is the third-person key. Save from the pause screen or turn Cyberblock off first. F9 never
  quick-loads, in any state.
- G does not toggle walking. K does not open crafting while it is the skill-tree key (the default): rebind the skill
  tree to another key and K opens Cyberpunk's crafting menu again.
- U, Y, L and O (Cyberware screen, Operating System, Arms, Optical Camo) never reach Cyberpunk while Active. Cyberpunk's
  own use of those keys works with Cyberblock off, or once the action is rebound.
- Esc, M, J, I, P and H open Minecraft screens instead of Cyberpunk's menus (see [the interface](#the-minecraft-interface)).
- 1–9, E, Q, X, /, the left/right/middle mouse buttons and the wheel go only to Minecraft, except that E, Q, 1–4
  and the wheel go to Cyberpunk while a dialogue offers 2 or more choices.
- T goes to Minecraft's chat, except while a call rings or an actionable notification shows: then it answers (hold: rejects).
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
- Is Minecraft running and in the Night City world (`cyberblock`)? Press F9 and watch for "Start Minecraft first".
- Is the Minecraft window minimized? Restore it, and leave it behind Cyberpunk.
- **Port 25600 is already in use:** the Minecraft side listens on `127.0.0.1:25600`. If another program (or a second
  Minecraft with the mod) holds it, Minecraft logs that it could not listen and Cyberpunk says "Start Minecraft first".
  Close the other program or the second Minecraft and restart Cyberblock's Minecraft.
- **The mod does not load / "Fabric API missing":** the Minecraft log (or the launcher's crash screen) names the missing
  dependency. Put the Fabric API jar (0.161.0 or newer for 26.3) next to the Cyberblock jar in the `mods` folder.
- **The JVM arguments were appended instead of replaced:** if Minecraft starts with a big heap, stutters, or fights with
  Cyberpunk for memory, open the launcher installation again (More options) and make the JVM arguments field contain
  **only** `-Xmx2G -XX:+UseZGC --enable-native-access=ALL-UNNAMED -Dcyberblock.autostart=true`.
- **No "Play Cyberblock" button:** the mod is not loaded (see the line above), or the installation does not use the Fabric
  26.3 profile.
- **`version mismatch: DLL 1.0.0, mod X`** in Cyberblock.log: the plugin and the mod jar come from different versions. Install
  both from the same release zip and restart Minecraft.
- **Logs:**
  - `<Cyberpunk 2077>\red4ext\logs\Cyberblock.log` for the DLL and the scripts;
  - `mc\run\logs\latest.log` (from source) or `%APPDATA%\.minecraft-cyberblock\logs\latest.log` (release) for Minecraft.

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

### The ReShade effect is off, or the Sandevistan effect does nothing
Open the ReShade overlay (**Home**) and tick the **Cyberblock** effect (the installer does not change a preset you already
had; `fx` lines in Cyberblock.log say whether the effect loaded). The Sandevistan look needs the effect on and
**SandevistanStrength** above 0.

### A Cyberpunk HUD element appears while Cyberblock is on
That should never happen (apart from the exceptions in [section 7](#7-what-cyberpunk-stops-doing-while-cyberblock-is-active)).
If you see one, note what it was and when, and send the lines of Cyberblock.log that start with `ui:` around that time
(`ui: ink layers ...`, `ui: hidden (on)`, `ui: restored (<reason>)`, `ui: shown for <reason>`). Pressing F9 twice restores and
re-hides everything.

### Night City stays paused
Cyberpunk is paused only while the Minecraft **Night City pause screen** is open. If the world looks frozen, press **Esc**
in Minecraft's pause screen (or **F9** to turn Cyberblock off): leaving the screen, Cyberblock off, a lost link and a
Minecraft restart all un-pause Cyberpunk. Cyberblock.log says `ui: unpaused (<reason>)`.

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
- **The glide does not turn with the mouse:** the flight summary line shows `yaw start A end B, mouse x N counts`; if N
  is 0 the mouse is not reaching the DLL (a Minecraft screen is open, or Cyberblock lost the focus).
- **Third person (F5) does nothing:** it works only on foot while Active, not held by a scene, workspot or braindance and
  with no menu open; `view: first person (<reason>)` in Cyberblock.log names the reason.
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
  ("flight: ignored, V may not be moved (<reason>)"); the Minecraft log shows "glide called off".
- **Weapons, leads and mobs ignore people:** look at the 5 s lines "npcs: sent H people + C cars; ... left out: ..."
  in Cyberblock.log and "proxies: H people, C cars" in the Minecraft log. The left-out reasons (dead, defeated,
  mounted, puppet, detached, not_npc, over_cap) say why. Scanning and quickhacks on people do not need proxies.
- **A hotkey (hack, skills, Cyberware, Y, L or O) does nothing:** rebind it in Minecraft's Controls to an allowed key
  (G K L O U Y 0 F1-F4 F6-F8 F10-F12). A refused key goes back to its default, and if any two of the six clash all six
  go back to G K U Y L O; chat says so, and Cyberblock.log's `binds: hack G, skills K, cyberware U, activate Y, arms L,
  camo O` line shows what is in force.
- **A hack says "Not enough RAM" or "No cyberdeck":** install the Cyberdeck in the Operating System slot (U), or carry
  one. RAM refills over time, or use a RAM Shard. More messages: [netrunning step 7](#netrunning-step-by-step).
- **Every hack looks the same:** the chip **in your hand** is the one that fires, and the action bar says "Hack: <chip>".
  Check the `script: hack <name>` and `hack <name> on <id>: ok (<kind>, ...)` lines in the logs.
- **A hack says "target gone" on someone alive:** read the scan line in Cyberblock.log, `scan N: ok <class> <id>
  (...)`. A real id has about 8 digits (for example 10019101). A much longer id (such as 2668059858640) or 0 means the
  scripts hashed the wrong thing; send the log. "Target gone" only means dead or despawned.
- **Y, L or O says "No ... implant":** open the Cyberware screen (U) and install one for that slot (Sandevistan or
  Berserk for Y; Mantis Blades, Gorilla Arms, Monowire or the Projectile Launch System for L; Optical Camo for O).
- **"Hold G and look at a target":** scan first. The lock lasts 5 s.

---

## 9. Uninstall

**Release install:**
1. Close Cyberpunk.
2. Double-click **`uninstall.bat`** in the unzipped release folder (it runs `install.ps1 -Remove`). It deletes exactly the
   files the installer wrote: the DLL, the scripts, the shader, its header (if the installer wrote it), and the ReShade
   preset if the installer created it.
   - A file you changed since the install is left in place, with a message.
   - Folders the installer created are removed if they are empty.
3. In `%APPDATA%\.minecraft-cyberblock\mods`, delete the Cyberblock jar (and, if nothing else needs them, the Fabric API
   jar). Delete the whole `.minecraft-cyberblock` folder to remove the Night City world too, and remove the launcher
   installation from the launcher.

**From source:** `uv run tools/install.py --remove` does the same (the two installers share one manifest, so either can
remove what the other installed).

Afterwards:
- **Your saves** stay loadable without the mod. Cyberblock never stores anything in them, because everything is
  removed before each save. The backup zip from the first install is still in `%LOCALAPPDATA%\Cyberblock\save-backups`
  (or the `--backups` folder you used).
- **The Minecraft world** (release: `%APPDATA%\.minecraft-cyberblock\saves\cyberblock`; source: `mc\run\saves\cyberblock`).
  Delete that folder for a fresh world: a new starter kit, and no perks.
- **ReShade** and the other frameworks (RED4ext, redscript, Codeware, CET) are separate installs. Remove them with
  their own uninstallers if you no longer want them.
