# Cyberblock — in-game test session (2026-10-03)

Everything below is built, unit-tested, compile-checked against the real 2.31 game scripts and reviewed. Work top to
bottom (**Re-test 3 first**); each step says what should happen. Tell Claude "F4: what you saw" or "R5 L-1: what you saw" (or "step N: ...") and it reads
`red4ext\logs\Cyberblock.log`, Minecraft's `mc\run\logs\latest.log` and captures the game window to diagnose.

## Re-test 3 (do this first)

The third session (`out/user-issues.md`, section "Re-test 3 feedback") raised thirteen items, **F1 to F13**. They are all
built on this version; this list is one check per item, in the user's order. (The R1-R6 below, under "Re-test 2", are an
older list.) The plan behind them is `docs/superpowers/plans/2026-10-03-retest3-polish.md`; the wire is `shared/protocol.md`
("Re-test 3"); the player guide is `docs/HOW-TO-PLAY.md`.

| Item | What was wrong, and what changed |
|---|---|
| **F1** every quickhack feels the same | The hack wheel fired the first chip (Short Circuit) over the chip in your hand, and no chip had its own look. The chip in your hand wins now; every chip has its own Minecraft effect and a distinct Cyberpunk effect, also on crowd pedestrians. |
| **F2** a Minecraft UI for the rest of the game, third person | Radar, eddies, wanted stars, quest, locator-bar waypoint, prompts, choices, subtitles, toasts, calls, journal, phone, atlas and pause are drawn by Minecraft. **F5** is a third-person view. |
| **F3** Steve visible in vehicles | First-person: Steve's hands and the hotbar. Third-person camera: the whole seated Steve. |
| **F4** the glide could not turn | The DLL now owns the glide's yaw and applies your mouse turn; it steers like the vanilla elytra. |
| **F5** ground alignment | Perfect, unchanged: a regression check only. |
| **F6** arm cyberware models | 3D models for Mantis Blades, Gorilla Arms, Monowire and the Projectile Launch System, first and third person. |
| **F7** hits fling NPCs | Vanilla-like pushes; stagger, knockdown and ragdoll only on stronger hits. |
| **F8** one key for all implants | **Y** = Operating System implant, **L** = Arms implant, **O** = Optical Camo; six rebindable keys, no clashes. |
| **F9** deck and Sandevistan together | Two Operating System places. |
| **F10** Sandevistan looks like nothing | A ReShade screen effect (chromatic split, tint, ghost trails, edge glow) and a Minecraft flourish. |
| **F11** release-ready | Release zip with installer, launcher install, README, CHANGELOG, licences, CI, version 1.0.0. |
| **F12** the old Cyberpunk UI must never show | Every Cyberpunk layer is invisible; menus are Minecraft screens; the few that cannot be replaced are restyled and framed. |
| **F13** prompts and choices | Minecraft-styled prompt box and choice list, still driven by Cyberpunk's input. |

**Before testing:**
1. Close Cyberpunk. Either install from source (`uv run python -m tools.install` after building the DLL) or, for F11, the
   release zip (`uv run python -m tools.package`, then `install.bat` from the unzipped folder).
2. **Restart Minecraft** (`uv run python -m tools.play`, or the launcher installation): a Minecraft that was already running
   still has the old mod.
3. Load a save on a street, press **F9**, and wait for "Cyberblock on". Use survival (the kit is handed out) unless a check
   says otherwise.
4. To try the interface without Cyberpunk, a running Minecraft client can be driven by the fake host:
   `uv run python -m tools.fakehost --ui --hold 12`, `--hacks` (answers hacks with `upload`, `kind` and spreads) and
   `--seat` (a vehicle, `fp` flipping every 2 s).

**Logs to grep** (`<Cyberpunk 2077>\red4ext\logs\Cyberblock.log` for the DLL and scripts, `mc\run\logs\latest.log` for
Minecraft):

| Where | Line |
|---|---|
| DLL | `Cyberblock 1.0.0 loading`; `Minecraft says hello: mod cyberblock 1.0.0, Minecraft 26.3`; `version mismatch: DLL 1.0.0, mod X` |
| DLL | `binds: hack G, skills K, cyberware U, activate Y, arms L, camo O` |
| DLL | `flight: N frames, ..., yaw start A end B, mouse x N counts at R deg/count, pitch min..max`; `look: R deg per mouse count` |
| DLL | `seat: on (third person\|first person)`, `seat: off`; `view: third person on (distance D m)`, `view: first person (<reason>)`, `view: applied, eye moved D m` |
| DLL | `screenfx: sandevistan S s`; `compositor: menu pass-through on\|off` |
| DLL | `ui: pump ready` or `ui: pump disabled: CyberblockUi.Pump missing`; `ui: action <a>`; `ui: unpaused (<reason>)`; `ui: reply <kind> <n> groups, <m> lines, <k> pins, <b> bytes` |
| Scripts | `ui: ink layers <names>`, `ui: hidden (on)`, `ui: restored (<reason>)`, `ui: shown for <reason>`, `ui: menu <name> -> minecraft <screen>`, `ui: pass-through <menu>`, `ui: paused by Minecraft`, `ui: resumed`, `ui: notify <kind>`, `ui: escape hatch <menu>` |
| Scripts | `hack <name> on <id>: ok (<kind>, upload <s>s, +<n> spread)`; `hackfx: <hack> <kind> <what>`; `knock: <id> s=<s> tier=<tier> impulse=<i>`; `seat: slot <slot> hips (<x>, <y>, <z>) tpp <b>`, `seat: no Hips slot`; `cyberfx: <id> <s> s` |
| Minecraft | `hackfx: {} on {} upload {} s`; `webs: placed {} removed {}`; `binds: unbound vanilla {} ({})`; `cyberware: fire {} ({})`; `ui: screen {}`; `persp: {}`; `launcher: opening {} (autostart\|button)` |

### F1. Every quickhack is unique (and the chip in your hand fires)
Survival, the kit (deck and all ten chips), a **ganger** (a hostile NPC in a gang area: a red dot on the radar) and a **pedestrian** (crowd) within 20 m.
1. **Chip in hand.** Put **Blaze Chip** on a hotbar slot and select it. Hold **G** on the ganger, roll the wheel to
   **Redstone Charge**, right-click.
   - **Expect:** the action bar says "Hack: Blaze Chip" (orange); the **Blaze Chip** hack runs, not Short Circuit. Select a
     hotbar slot that holds a different chip: that one fires, and the wheel pick is forgotten.
   - **Log:** `script: hack overheat ...` (the chip in hand), never `short_circuit` for every shot.
2. **All ten, on the ganger, then on the pedestrian.** Fire each chip once at each, from the hand. Wait for the effect (1-4 s
   after the beam starts).

   | Chip | Minecraft at the impact | Ganger (vanilla quickhack) | Pedestrian (extra) |
   |---|---|---|---|
   | Redstone Charge | visual lightning bolt, sparks | electrocuted | electrocuted (kills) |
   | Blaze Chip | flame spiral, lava pops, smoke, no fire blocks | burns | set alight 5 s |
   | Ink Sac Daemon | ink burst and halo | blinded | blinded 5 s |
   | Cobweb Daemon | **real cobwebs** round the target, gone after 6 s | cannot walk | stunned 4 s |
   | Spider Eye Virus | green cloud, arcs to people it spreads to | contagion spreads | the 2 nearest within 6 m get it |
   | Wither Skull Daemon | wither trail, soul and smoke burst | brain damage | heavy hit, no ragdoll |
   | Compass Ping | gold ring, hostiles within 32 blocks glow through walls 10 s | squad revealed | hostile NPCs within 30 m revealed |
   | Bottle of Forgetting | swirl at the head; mobs drop their target | forgets V | dazed 2 s |
   | Creeper Brain | green creeper face above the head | attacks its friends | frenzy 6 s, hits the nearest person within 4 m |
   | Redstone Lever (on a camera, traffic light, vending machine, other) | click and smoke / bell and dust / notes / dust | the device's action | - |

   - **Log:** `hack <name> on <id>: ok (<kind>, upload <s>s, +<n> spread)` with `kind` `squad` for the ganger and `crowd` for
     the pedestrian (`driver` for a car, `camera` / `traffic` / `distract` / `device` for the lever); `hackfx: <hack> <kind>
     <what>` for every extra that ran on a pedestrian. Minecraft: `hackfx: <hack> on <id> upload <s> s`, and `webs: placed N
     removed N` for the Cobweb Daemon (N equal after 6 s).
   - **Report** any chip whose look is the same as another's, or whose Cyberpunk effect is invisible.
3. **Busy.** Fire a chip, then another at the same target during the beam: "<target> is still uploading a hack", RAM not spent.

### F2. The Minecraft interface on screen, and third person
1. **Radar and stats.** The 80 x 80 radar is top left with dots for people and squares for cars; under it the eddies
   (`€$ <money>`), the wanted stars, the time, the district. Walk around: the radar rotates with the camera.
2. **Quest and waypoint.** Open **J**, **Track** a quest (the Journal screen is Minecraft's). **Expect:** the quest panel at
   the top right with its objectives and the distance, a marker on the locator bar, and the arrow at the radar's edge
   pointing at it. Complete or change an objective: the panel updates.
3. **Money and wanted.** Earn or spend eddies (a vendor, a street vending machine): the figure changes and an "Eddies" toast
   shows. Make NCPD notice you (a TNT near police): stars appear and the toast says "Wanted".
4. **Calls and messages.** When a call arrives (story or a fixer): a toast "Incoming call: <name>" with `[T] Answer / hold [T]
   Reject`; **T** answers, hold **T** rejects; **T** is chat again afterwards. A new SMS gives a "Message" toast; **Messages**
   shows the Phone screen. (If no call arrives in your save, note it as "not tested".)
5. **Third person.** Press **F5** on foot: Steve seen from behind, the camera about 3 m back; walk into a wall, the camera
   shortens; **F5** again is first person. It returns to first person in a car, a scene and a menu.
   - **Log:** `view: third person on (distance D m)`, `view: applied, eye moved D m` (once, the measured movement),
     `view: first person (<reason>)`; Minecraft `persp: back`, `persp: first`.

### F3. Steve in vehicles
Get into a car and then a bike.
1. First-person vehicle camera: Steve's hands and the hotbar are visible; **no** Cyberpunk HUD, V's body is hidden. The
   health, food and armour bars are hidden, the hotbar stays.
2. Switch to the third-person vehicle camera: the **whole of Steve, seated** at V's seat. Switch back and forth several times:
   V's own body never appears in either camera.
3. Get out: bars and crosshair return.
- **Log:** `seat: on (third person)` / `seat: on (first person)`, `seat: slot <slot> hips (...) tpp <b>`, `seat: off`.
  `seat: no Hips slot` means the seat position fell back (Steve may float: report the vehicle's name).

### F4. The glide steers with the camera
Elytra, rockets, a megabuilding roof. Jump off, press Space, glide for 10 s and swing the mouse hard **left**, then **right**,
then up and down.
- **Expect:** the glide turns with the camera at once, like the vanilla elytra, left, right, up and down; it is no longer
  locked to the start direction; the picture stays smooth.
- **Log:** the flight summary `flight: N frames, ... yaw start A end B, mouse x N counts at R deg/count, pitch min..max`:
  `A` and `B` differ by about the turn you made, `N` is not 0, and `look: R deg per mouse count` appears once.

### F5. Ground alignment is unchanged
On a flat street place a stone block: it sits **on** the street, not a block above it (as in Re-test 2 R4). Run
`/cyberblock level` while standing on a different floor and place another: also level.
- **Log:** `ground alignment: ...` lines as before.

### F6. Arm implants in 3D
Install **Mantis Blades**, **Gorilla Arms**, **Monowire** and the **Projectile Launch System** in turn (U; one arm at a time).
- **Expect:** each has its own model on your arm in first person and on Steve's arms in third person (**F5**), and the model
  changes while the implant is out (blades extended, launcher open). Glowing parts glow; nothing floats away from the arm.
- Also look at Kiroshi Optics (glowing eyes), Subdermal Armor and Reinforced Tendons on Steve in third person, and Optical
  Camo (Steve shimmers translucent cyan in third person).
- **Report** a model that sits wrong on the arm (the transform is adjusted in code, not in the model).

### F7. Knockback tiers
Hit pedestrians with the iron sword, an empty hand with Gorilla Arms, a sprint hit, Knockback I and II swords and the
gauntlet.

| Hit | Expected |
|---|---|
| plain hit | a flinch, no flying |
| sprint hit or Knockback I | a stagger, a step back |
| Knockback II, gauntlet or Gorilla Arms | the person falls down |
| Gorilla Arms and sprint | falls down |
| gauntlet and Gorilla Arms | falls down and slides a short way (never flies metres) |

- **Log:** `knock: <id> s=<speed> tier=<tier> impulse=<...>` with `s` about 2, 4.5, 7, 9.5 and 12 and tiers impact,
  stagger, knockdown, knockdown and ragdoll. A Gorilla slam (**L**) knocks people down, no further.

### F8. Y, L and O, and rebinding
1. **Keys.** Install Sandevistan (U), Mantis Blades and Optical Camo. **Y** starts the Sandevistan, **L** lunges (aim at an
   entity within 10 blocks), **O** cloaks you. Each key fires only its own slot; with nothing for a key the chat says "No
   ... to fire (<key>)".
2. **First start.** The first time the mod runs after the update, the chat says "Cyberblock took L from Advancements (rebind
   it in Controls)" (and O from Friends). Advancements are in the pause menu.
3. **Rebinding and a clash.** In Controls (category Cyberblock) set Arms to **Y**: all six keys go back to G K U Y L O and
   chat says so. Rebind Arms to **F10** (allowed): L is Cyberpunk's again, F10 lunges. A refused key (for example **E**) falls
   back to that action's own default.
   - **Log:** `binds: hack G, skills K, cyberware U, activate Y, arms L, camo O` (changing with each rebind), Minecraft
     `binds: unbound vanilla {} ({})` (first start only), `cyberware: fire <id> (<trigger>)`.

### F9. Deck and Sandevistan together
`/cyberblock kit`, press **U**: the Operating System slot has **two places**, the deck in the first and Sandevistan in the
second. Hack a ganger (the deck works), press **Y** (Sandevistan) and hack again during it. Install **Berserk**: it replaces
Sandevistan, never the deck. Take the deck out and carry it instead: RAM still works.
- **Expect:** the advancement "Two Brains" appears. An older save with Sandevistan in the first place has it moved to the
  second.
- **Log:** `cyberware: installed <id> in operating_system`, `cyberfx: sandevistan <s> s`, `hack <name> on <id>: ok` during it.

### F10. The Sandevistan screen effect
Fire the Sandevistan (**Y**) while moving and looking around.
- **Expect:** the picture gets a chromatic colour split at the edges, a lime and teal tint, ghost trails behind moving
  things, an edge glow and a pulsing vignette; it fades in over about 0.15 s and out over 0.4 s; your hotbar and hand stay
  sharp; the Minecraft side adds the afterimages and the "SANDEVISTAN" label. Kerenzikov (draw a bow while falling) shows
  half of it. Open a Cyberpunk menu in the middle: the effect is off meanwhile.
- **Slider:** in the ReShade overlay set **SandevistanStrength** to **0**: no screen effect (the Minecraft flourish stays);
  back to 1: it returns.
- **Log:** `screenfx: sandevistan <S> s`, `cyberfx: sandevistan <S> s`.

### F11. Release install, the launcher and the Play button
On a **clean copy** (a copy of the Cyberpunk folder with the frameworks, or a spare Windows account) with no earlier
Cyberblock:
1. `uv run python -m tools.package` and unzip `dist/Cyberblock-1.0.0.zip` somewhere else. Close Cyberpunk, run **install.bat**.
   **Expect:** it finds the game (or accepts a dropped folder), warns if RED4ext, redscript, Codeware or ReShade is missing, backs
   up the saves, refuses to overwrite a foreign file, keeps an existing `ReShade.fxh` and `ReShadePreset.ini`.
2. Create the launcher installation as README and HOW-TO-PLAY say (Fabric 26.3, game directory `%APPDATA%\.minecraft-cyberblock`,
   the JVM arguments **replaced** by `-Xmx2G -XX:+UseZGC --enable-native-access=ALL-UNNAMED -Dcyberblock.autostart=true`, the jar and
   Fabric API in `mods`). Start it from the launcher: the title screen has **Play Cyberblock** and the world opens by itself.
3. Start Cyberpunk, load a save, **F9**. Play for a minute; quit.
4. **uninstall.bat**: the game folder is as before (a file you edited is left with a message).
- **Log:** `Cyberblock 1.0.0 loading`, `Minecraft says hello: mod cyberblock 1.0.0, Minecraft 26.3`, no `version mismatch`;
  Minecraft `launcher: opening cyberblock (autostart)` or `(button)`.
- Also open the advancements screen (pause menu): the Cyberblock tab lists six advancements.

### F12. No Cyberpunk HUD, ever (the full walk)
Cyberblock Active, survival. Do each of these and look at the whole screen the whole time. **No Cyberpunk interface element
may appear at any moment**: not the health bar, minimap, quest tracker, ammo, "F" prompts, dialogue wheel or subtitles, not a
notification, not the loading or waiting sign.
1. Walk up to a **door** (the prompt is Minecraft's tooltip box), an **item** to pick up, an **NPC** to talk to, a **vending
   machine**, and get into a **car** (and out).
2. Start a **dialogue** (choices are Minecraft's list, picked with E/Q/1-4/F), and a timed one if you find it.
3. Open a **vendor** (shop), a stash or the ripperdoc if you can reach one: the menu is Cyberpunk's but in Minecraft colours with
   a Minecraft title bar and frame, and the Minecraft world is not drawn over it. Its font stays Cyberpunk's (known limit).
4. Press **Esc**: the Minecraft "Night City (paused)" screen; Night City is frozen behind it. Press Esc to go back.
5. **M** (Atlas), **J** (Journal), **I** (inventory), **P** (skill tree), **H** (pause screen): all are Minecraft screens.
6. Click **Night City Menu...** in the pause screen: the real Cyberpunk pause menu opens once (the escape hatch); close it.
7. **Save** from the pause screen (Minecraft toast "Saving Night City..." then "Saved"), then **Load Last Save** and watch the
   loading cover ("Loading Night City...").
8. Die (`/kill` in chat in survival): the Minecraft death screen, no Cyberpunk game over.
9. A tutorial or shard popup (if one shows) is framed in Minecraft style and works.
10. **F9** off: Cyberpunk's own HUD is back at once; **F9** on: it vanishes.
- **Log:** `ui: ink layers <names>`, `ui: hidden (on)`, `ui: menu <name> -> minecraft <screen>`, `ui: pass-through <menu>`,
  `compositor: menu pass-through on|off`, `ui: paused by Minecraft` / `ui: resumed`, `ui: escape hatch <menu>`, `ui: restored
  (<reason>)` on F9 off. **A `ui: shown for <reason>` line is a report-worthy exception** (only a foreign pause popup or a
  braindance may cause it).
- **Report** every element that showed, with what you were doing and the `ui:` lines around that time.

### F13. Prompts and choices
1. Walk to several things: a door ("[F] Open"), a corpse or loot ("[F] Pick up", "[F] Loot"), an NPC ("[F] Talk"), a car ("[F]
   Enter"). **Expect:** a Minecraft tooltip box right of the crosshair with the key in yellow; several prompts on one thing
   form a list (E/Q or the wheel scroll, 1-4 pick, **F** confirms); a hold action shows a fill bar.
2. A **dialogue**: the list sits at the bottom centre above the subtitles; the selected line starts with `>`; unavailable lines
   are grey; quest lines gold; a timed line shows a timer bar that runs down. Picking works with E/Q/1-4/wheel/F as in
   Cyberpunk. Subtitles above the hotbar show the speaker in aqua.
- **Log:** `ui: notify ...` for the toasts; no `ui: shown for ...` lines.

If a check fails, tell Claude "F<n>: what you saw" and it reads the logs and captures the window to diagnose. For anything
visual, a screenshot helps most.

## Re-test 2 (still valid; do it after Re-test 3)

The second session (when you ran the first re-test below, `out/user-issues.md` section "Re-test 2026-10-03 ~15:05-15:20") found six things.
They are fixed or added on this build; **R1 to R6 below are those six, in the user's order**. (The R1-R6 of the older
re-test further down are a different list: call them "old R1" and so on.)

| Item | What was wrong, and what changed |
|---|---|
| **R1** elytra "works but teleports, the camera updates every few seconds" | V was moved by a script loop that only ran about once per second. The DLL now moves V **every rendered frame** from its own update hook, plays Minecraft's samples out a few ms late, and lands V on the real floor. |
| **R2** hacking says "target lost", scans find nothing | The scripts sent a garbage entity id to Minecraft (2668059858640 instead of 10019101), so every hack came back with an id that does not exist. Ids are fixed, and the scan pick now uses the proxies' real positions with some leeway. |
| **R3** infinite skill points | Perks are free in creative, and `/cyberblock perks free on` makes them free in survival. |
| **R4** blocks float a block above the street | Ground barriers were rounded up. Each world now has a vertical offset chosen at the first activation, applied in both directions, plus nearest rounding. `/cyberblock level` re-aligns. |
| **R5** a wind charge cannot launch you | Steve is now launched with Minecraft physics and V follows like a short flight, then a glide if you open the elytra. |
| **R6** NEW cyberware | A Cyberware screen on **U**, implants in ripperdoc slots, **Y** to fire them, HUD cooldown bars, all 17 implants with Minecraft VFX and, where it makes sense, the real Cyberpunk effect. |

**Before testing:** close Cyberpunk, run `uv run python -m tools.install`, and **restart Minecraft**
(`uv run python -m tools.play`): a Minecraft that was already running still has the old mod. Load a save on a flat
street, press **F9**, and stand still until the chat says Cyberblock is on. That first activation aligns the world's ground by
itself (R4). If V was not standing on the street then, or blocks still float, run **`/cyberblock level`** once while V
stands on the street. Everything is described in
`docs/HOW-TO-PLAY.md` (key table, Cyberware chapter).

**Logs to grep** (`<Cyberpunk 2077>\red4ext\logs\Cyberblock.log` for the DLL and scripts, `mc\run\logs\latest.log` for
Minecraft). The lines the checks below look for:

| Where | Line |
|---|---|
| DLL | `drive: ready (Teleport <param types>, raycast <param types>; ...)` once, on the first flight, or one `drive: disabled: <what> missing` |
| DLL | `flight: samples back`, and `ground: alignment k A -> B, Minecraft gets the ground again` after every align |
| DLL | `flight: start (glide\|launch)`, `flight: landed (glide\|launch) after X s`, `flight: over, V stays where it is` |
| DLL | `flight: N frames, M samples (median spacing a ms, max gap b ms), max step c m, clipped yes\|no` after every flight |
| DLL | `flight: stopped, V is held, in a vehicle or Cyberblock is off`, `flight: no samples, holding V`, `flight: no samples for 1 s, V put on the ground`, `flight: V stopped short of Night City geometry` |
| DLL | `ground alignment: Minecraft y = Cyberpunk z - %.4f (k %d/64)`, and `binds: hack G, skills K, cyberware U, activate Y` |
| Scripts | `flight: ignored, V may not be moved (<reason>)`, `flight: cleared in mid-flight, V put on the ground`, `dmg instant kill (landing) not reported: flight` |
| Scripts | `scan N: ok <class> <8-digit id> (requested\|look-at\|near crosshair)`, `hack <name> on <id>: ok` |
| Scripts | `cyberware: <id> on\|off`, `cyberfx: <id> <s> s`, `cyberfx: <id> slow-mo ended`, `berserk: Minecraft side only` |
| Minecraft | `launch started at {} with {} m/s`, `launch turned into a glide at {}`, `launch\|glide ended at {}` |
| Minecraft | `ground alignment: k {} -> {} (offset {} m) at Cyberpunk z {} ({})` (k -1 = never aligned), `block resync: {} cells` |
| Minecraft | `cyberware: installed {} in {}`, `cyberware: activate {}`, `glide called off (the host did not follow)` |

### R1. The elytra follows every frame (F-1 to F-3, L-7)
Cyberblock Active, an elytra on the chest and firework rockets (the survival kit has both), a megabuilding roof.
1. **Start-up line.** On the first flight Cyberblock.log has exactly one `drive: ready (Teleport handle:gameObject,
   Vector4, EulerAngles -> Void, raycast Vector4, Vector4, opt CName, out TraceResult (or physicsTraceResult), opt Bool,
   opt Bool -> Bool; ...)` and no `drive: disabled:` line. If it says `drive: disabled: <what> missing`, stop and send
   the line: the per-frame drive is off, `cam.fly` stays false and Minecraft calls every flight off within 0.5 s.
2. **F-1 smoothness.** Jump off the roof, press Space, glide for about 10 s with a few rockets (about 20 m/s).
   - **Expect:** V and the camera move **smoothly, every frame**: no stepping about once a second, no teleporting, no
     jitter of Night City, and the mouse still turns the view.
   - **Log:** `flight: start (glide)`; after the landing the summary line `flight: N frames, M samples (median spacing
     a ms, max gap b ms), max step c m, clipped no`: **a 17 ms or less, c under 1 m at 20 m/s**, and N about 60 x the
     seconds flown (the 15:04-15:21 log had about 1 per second). There is no `flight: ignored` line.
3. **F-2b turning while gliding.** Swing the mouse hard left, right, up and down during a glide.
   - **Expect:** the view follows the mouse at once, the glide steers with it, V's body keeps its own facing and the
     first-person camera turns smoothly. (Risk to watch: yaw "stickiness" or a snap back on fast mouse turns. Say so if
     you see it; the forward is already read just before every Teleport.)
4. **F-3 landing.** Glide from the roof down to the street.
   - **Expect:** V stands on the street within a frame of Steve's touchdown, never stopped in mid-air and never hanging
     in the fall animation on the ground; no death-landing effects, V's health unchanged.
   - **Log:** `flight: landed (glide) after X s` in the same frame as Minecraft's touchdown, **no** `dmg fall` within
     2 s, and no instant kill. If a landing attack still fires, these lines are fine: `dmg fall ... not reported: flight
     or teleport landing`, `dmg instant kill (landing) not reported: flight (Attacks.*Landing)`.
   - **Landing state:** V can walk or jump at once. If V stays frozen in the Fall pose on the ground for about 2 s and
     then plays a hard landing (or a `dmg fall` shows up about 2 s after landing), report it: the follow-up is a
     `ToRegularLand` wrap, `wrappedMethod(...) || SuppressFall && IsTouchingGround`.
   - A very short hop or a landing within 1.5 m of V logs `flight: over, V stays where it is` instead.
   - **Hand-off:** Steve takes V's position again as soon as V stands at the landing spot, with no visible 0.5 s
     freeze.
5. **L-7 menu mid-flight.** Glide (or launch), press **Esc** (or open the map) and wait **5 s**, then close it.
   - **Expect:** Steve waits in the air and the flight carries on; V never snaps to the ground meanwhile.
   - **Log:** possibly `flight: no samples, holding V` then `flight: samples back`; **no** `flight: no samples for 1 s,
     V put on the ground`; V either resumes or lands normally. Minecraft logs `glide paused at ... (Cyberpunk menu)` then
     `glide resumed`.
6. **Walls (clip).** Glide Steve into a Night City wall that Minecraft has no barrier for yet (fast, low, along a
   street).
   - **Expect:** V stops at the wall and ends on the ground on its side of it, never inside or through it.
   - **Log:** `flight: V stopped short of Night City geometry` once, and `clipped yes` in the summary line.
7. **Timeout.** Mid-glide, close Minecraft (or anything that freezes it for over a second).
   - **Log:** `flight: no samples, holding V`, then about 1 s later `flight: no samples for 1 s, V put on the ground`.
8. **Clear.** Press **F9** (or save) mid-glide.
   - **Expect:** V stands on the ground below, not in the air.
   - **Log:** `flight: cleared in mid-flight, V put on the ground`.
9. **No flight while V is held.** Sit in a car or a scene and try to start a glide.
   - **Log:** one `flight: ignored, V may not be moved (vehicle|scene|workspot|...)`; Minecraft calls Steve back within
     0.5 s (`glide called off ...`). A flight already under way when V gets held logs
     `flight: stopped, V is held, in a vehicle or Cyberblock is off`, and V is not teleported.

### R2. Scan and hack: real ids
1. **Scan id.** Hold **G** on a pedestrian 5 to 15 m away (the kit has a deck and chips).
   - **Expect:** the tooltip (name, faction, threat) and the glowing box on the right person.
   - **Log:** `events: scan scan N: ok NPCPuppet <8-digit id> (requested|look-at|near crosshair)`. The id has about
     **8 digits** (for example 10019101), **not** 13 digits (2668059858640) and not 0.
   - **Leeway:** aiming about 0.3 m beside a pedestrian, or 0.5 m beside a car, still picks it (the pick runs on the
     server from the proxies' real positions).
2. **Hack.** Keep G held, roll the wheel to **Bottle of Forgetting**, right-click.
   - **Expect:** a beacon sound, the upload starts, no "target lost/gone" message, RAM drops by 3.
   - **Log:** `hack memory_wipe on <same id>: ok` (it must not say `target gone`).
   - **Ids everywhere:** hit an NPC, then kill one. `events: hit target <id>`, `effects: push withheld from <id>` and
     `killed human <id>` use the same 8-digit id, never 0 and never a 13-digit one; `puppets: mobdmg ... by <id>` is a
     real id too.
3. **Target gone, for real.** Scan a person, kill them with your sword within the 5 s the lock lasts, then fire the
   chip.
   - **Expect:** "Hack failed: target gone" **only now**, with the RAM given back.
4. **Other targets:** an occupied car (the driver is hacked), a vending machine with the Redstone Lever (device
   path), and a person at about 60 m (only the look-at scan reaches there; with **Kiroshi Optics** installed the
   range is 80 m instead of 48).
   - **Log:** `scan N: ok ... (look-at)` or `(near crosshair)` for the far and device targets.

### R3. Free perks (infinite skill points)
1. Survival, `/xp set @s 0 levels`. Press **K** and click a tier-1 perk.
   - **Expect:** it refuses (not enough levels); costs are shown normally.
2. `/cyberblock perks free on`.
   - **Expect:** chat "Free perks on: the skill tree costs no levels in this world". **K** now shows "Perks are free" top
     right and greyed costs. Clicking a tier-1 perk says "Perk unlocked (free): <perk>" and your levels stay 0.
3. Click a tier-2 perk whose tier-1 you do not own, and a perk you already own.
   - **Expect:** the first stays locked, the second is not bought again (free skips the cost only).
4. `/cyberblock perks free` reports the state; `/cyberblock perks free off`.
   - **Expect:** "Free perks off: perks cost XP levels again", and perks cost levels again.
5. `/gamemode creative` with free perks off, press K and click a perk.
   - **Expect:** it is free, and `/cyberblock perks free` says "Free perks off, but perks stay free while you are in
     creative".
6. **Persistence.** Set it on, Save and Quit to the title, reopen the world (and once more after restarting Minecraft),
   then die and respawn.
   - **Expect:** `/cyberblock perks free` still says on each time.

### R4. Blocks sit on the street (ground alignment)
1. **First activation.** Stand still on a flat street, not in the air or a car, and press F9 (an existing world that was
   never aligned does this too).
   - **Log (Minecraft):** `ground alignment: k -1 -> <k> (offset <m> m) at Cyberpunk z <z> (first activation)` (-1:
     never aligned), then `block resync: N cells`.
   - **Log (DLL):** `ground alignment: Minecraft y = Cyberpunk z - 0.xxxx (k <k>/64)`, then
     `ground: alignment k 0 -> <k>, Minecraft gets the ground again`. The camera debug line's `lv` matches `k`.
2. **Place a block** (stone) on the street and look at it from a low angle.
   - **Expect:** it sits **on** the street: no gap, no block-high float, and the targeted barrier cube is no longer above
     the street. Stand on it: V's feet are one block above the street, not sunk and not floating. V's collision box
     sits flush on the street (V can step onto it, no 1 m gap).
3. **Another floor.** Go to a kerb, a sidewalk or a roof of a different height, place a block, note any offset (up to
   half a block is expected, nearest rounding). Stand there and run **`/cyberblock level`**.
   - **Expect:** chat "Ground aligned: offset <m> m (was <m> m). Blocks you placed elsewhere moved by less than 1 m.",
     and your blocks reappear after the resync (the earlier ones may have moved by less than 1 m).
   - **Log (Minecraft):** `ground alignment: k <old> -> <new> ... (/cyberblock level)`, then a block resync.
   - **Expect:** Steve's feet stay on V's floor, with no visible vertical jump of the picture during the re-align.
4. **Refusals.** `/cyberblock level` while jumping or in a car: "V must stand on the ground (not in the air, a vehicle
   or a scene)". With Cyberblock off: "Cyberpunk is not connected, or Cyberblock is off".
5. **Steve on the ground.** No `Player standing on air` spam in the Minecraft log, and an ender pearl landing on the
   street does not sink V or drop it (it is put on the Night City floor). A pearl onto a placed block: V stands on top
   of the block.

### R5. Wind-charge launches (L-1 to L-6; L-7 is under R1)
Wind charges are in the survival kit (8). Each launch should log `launch started at ... with ... m/s` (about 22 m/s
for your own charge at your feet) and later `launch ended at ...` in Minecraft, and `flight: start (launch)` then
`flight: landed (launch) after X s` in Cyberblock.log, with no Night City fall damage on V and no `dmg fall` reaching
Steve within 2 s.
1. **L-1 own charge, survival.** Look straight down and throw a wind charge at your own feet.
   - **Expect:** Steve and V are thrown up, V follows every frame (as in R1), you land on your feet and Cyberpunk
     movement takes over. People and cars near the burst are still knocked back.
2. **L-2 the same in creative.** `/gamemode creative` and repeat.
   - **Expect:** it launches too (creative flying is switched off for the launch). Toggle flying again afterwards.
3. **L-3 someone else's or a breeze's charge.** Burst one at your feet from outside (`/summon wind_charge ~ ~1 ~` above
   you, or a Breeze from a spawn egg shooting you).
   - **Expect:** the same launch. A tiny nudge that barely lifts you does not count.
4. **L-4 mace wind burst.** `/give @s mace` with the Wind Burst enchantment, drop onto a mob or a person from a height
   and smash.
   - **Expect:** the burst launches Steve; V follows.
5. **L-5 launch, then glide.** With the elytra on, launch yourself, and at the top (falling faster than 1 m/s with 3
   blocks clear below) press **Space**.
   - **Expect:** the elytra opens mid-air and you keep flying with no pause, no second `launch started`, and no V
     teleport; V follows the whole way.
   - **Log (Minecraft):** `launch turned into a glide at ...`, then `glide ended at ...`.
6. **L-6 no launch while V is held.** Sit on a bench or in a workspot (or drive), then throw a charge at your feet.
   - **Expect:** no launch: no `launch started` line, Steve stays with V, and nothing drops V.
   - If the DLL drive is disabled (R1 step 1), every launch shows `launch called off (the host did not follow)` within
     0.5 s and Steve snaps back to V.

### R6. Cyberware (screen on U, fire on Y, HUD bars)
**Set-up.** Survival with `/cyberblock kit` (installs the Cyberdeck, Kiroshi Optics, Reinforced Tendons and Biomonitor;
Sandevistan, Optical Camo and the Projectile Launch System land in the main inventory), or `/gamemode creative` where
the screen lists every implant and installs are free. Press **U** with no screen open.
1. **The screen.**
   - **Expect:** a Cyberware screen with the 8 slots on the left (Arms 1, Legs 1, Operating System 1, Nervous System 1,
     Integumentary 2, Eyes 1, Circulatory 2, Frontal Cortex 1) and the available implants on the right, 24 per page
     (all of them in creative; "Nothing to install: craft cyberware or use /cyberblock kit" when empty). U again or Esc
     closes it.
   - **Install and remove:** left-click on the right installs ("Installed <name>"), left-click an installed one removes
     it ("Removed <name>"). A full slot replaces: "Installed <new> (returned <old>)". In survival the item is used up and
     comes back on removal. Right-click on an active implant selects it (a yellow Y marker).
   - **Log (Minecraft):** `cyberware: installed <id> in <slot>`; **(scripts)** `cyberware: <id> on`.
2. **Y and the HUD.** Install Sandevistan and Optical Camo (the Operating System and Integumentary slots).
   - **Expect:** one icon per active or automatic implant right of the hotbar, each with a thin bar. **Tap Y** fires the
     selected one (log `cyberware: activate <id>`); **hold Y for 0.4 s** switches ("Y fires <implant>"). A recharging
     one says "<implant> is cooling down (n s)" and its bar fills grey to cyan; it is magenta while the effect runs. With
     no active implant Y says "No active implant installed". Cyberpunk's own Y (scene fast-forward, camera reset) must do
     nothing while Active.
3. **The 17 implants.** Install each one, use it, and check Minecraft, Night City and the log. (Arm implants only work
   with an **empty main hand**.)

| # | Implant | Do | Minecraft | Night City (V) | Log |
|---|---|---|---|---|---|
| 1 | Mantis Blades | empty hand, hit a person | +6 damage, red dust, sweep, strong-hit sound; none with an item in hand | person takes melee damage, ragdolls | `cyberware: mantis_blades on` |
| 2 | Gorilla Arms | empty hand, hit a person and a car | +4 damage, +2 knockback, gust and smash sounds; one shove even with the Gorilla Arms perk | person and car shoved far, once (the hit impulse is twice a bare punch; the perk alone gives the same, both together still one shove) | `cyberware: gorilla_arms on` |
| 3 | Monowire | empty hand, hit at about 6 blocks | +3 damage, +3 reach, a line of electric sparks | hit lands | `cyberware: monowire on` |
| 4 | Projectile Launch System | **Y** at a far target; at a wall under 4 blocks; again at once | smoke and flames trail, crossbow and fire-charge sounds, 8 s HUD bar; "Too close to fire" (no cooldown); "is cooling down" | a real explosion (power 2.5): a car blows up, NCPD comes | `cyberware: activate projectile_launch_system` |
| 5 | Reinforced Tendons | jump, then **Space** again in the air (and a third time, with the elytra on and high enough) | ring of cloud and a gust | V **double-jumps** (the 1st air press is V's double jump, the 2nd opens the elytra). CET `Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.HasDoubleJump)` is 1; **0 without the implant, even with the Double Jump perk** | `cyberware: reinforced_tendons on` |
| 6 | Fortified Ankles | hold Space about half a second, release | dust plume, gust, breeze-charge sound | V's charge jump (Space held over 0.15 s and released); a double jump does **not** happen; `HasChargeJump` is 1 with it, 0 without | `cyberware: fortified_ankles on` |
| 7 | Lynx Paws | walk past a sculk sensor and a guard; with and without it | no step vibration (the sculk sensor stays quiet), mobs notice you from half as far | CET `Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.Visibility)` is half its value without it; with the Silent Steps perk too it is still half, not a quarter | `cyberware: lynx_paws on` |
| 8 | Sandevistan | **Y**; then the perk | Speed II, the world at half speed (`/tick query` shows rate 10), cyan and magenta afterimages, beacon sounds, 6 s then the off sound, 25 s cooldown | NPCs and cars slow to x0.2, V does not; F9 off mid-effect ends it at once. With the Sandevistan Sprint perk: 8 s and 18 s, and a double-tap of Shift fires it (only with the implant). A Kerenzikov during a running Sandevistan switches the factor to x0.3 | `cyberware: activate sandevistan`, `cyberfx: sandevistan 6 s`, about 6 s later `cyberfx: sandevistan slow-mo ended` |
| 9 | Berserk | **Y** | Strength I, Resistance I, Jump Boost I for 8 s, dust ring, angry burst, smash and anchor sounds, mobs within 3 blocks knocked back, 40 s cooldown | V jumps higher; Cyberpunk's own Berserk status is not used, so V gets **no** superhero landing or ground slam on falls | `cyberfx: berserk 8 s`, `berserk: Minecraft side only` |
| 10 | Cyberdeck | survival, fresh kit: install the kit's Sandevistan (right-click it, or U then click); fire a chip and use a RAM Shard; U and click the returned Cyberdeck; empty the slot with the deck in the bag | "Installed Sandevistan (returned Cyberdeck)"; then RAM is 0 and the chip and the shard say "Your Operating System slot holds Sandevistan: swap in the cyberdeck with U to hack" (not "...or carry one"); swapping back says "Installed Cyberdeck (returned Sandevistan)", RAM max 8 again and chips fire; with the slot empty and the deck in the bag RAM is 8 again. Rebind Cyberware (for example to O) and the message names the new key | a quickhack works while the deck counts | `cyberware: installed sandevistan in operating_system/0`, `cyberware: cyberdeck on\|off` |
| 11 | Kerenzikov | jump, then draw a bow (or a crossbow or trident) in the air; on the ground | the world at half speed for 2 s, a ring of sparks, a rising chime, 10 s cooldown; **nothing on the ground** | Night City slows to x0.3 for 2 s | `cyberfx: kerenzikov 2 s` |
| 12 | Optical Camo | **Y**; attack; fire the Launch System; with the perk crouch still 2 s | Invisibility 10 s (the icon shows, no particles), a portal shimmer; it breaks on your attack or a Launch System shot; 30 s cooldown | hostiles lose V; CET `StatusEffectSystem.ObjectHasStatusEffect(Game.GetPlayer(), TweakDBID.new("BaseStatusEffect.OpticalCamoPlayerBuffRare"))` is true while it runs. The perk re-cloaks only with the implant | `cyberware: activate optical_camo` |
| 13 | Subdermal Armor | install, then take the same hit with and without | +6 armor, +2 toughness, a netherite equip sound | Night City hits do less to Steve | `cyberware: subdermal_armor on` |
| 14 | Kiroshi Optics | **G** on a person at about 60 m near the crosshair, with and without it; spawn a zombie and look around | the scan reaches 80 m (48 without); hostile proxies (gangers) and hostile mobs within 32 blocks get a red outline, passers-by do not | the scan range | with it `scan N: ok ... (look-at)`; without it `scan N: nothing under the crosshair` |
| 15 | Biomonitor | `/damage @s 14` from full health (20 max) | at 30 % or less: healed 8, Regeneration I 6 s, hearts, a chime, "Biomonitor: emergency heal"; again within 90 s does nothing | - | `cyberware: biomonitor on` |
| 16 | Second Heart | `/damage @s 100` (not `/kill`); again at once; save, quit, reopen | instead of dying: full health, Regeneration II 5 s, Absorption I 30 s, the totem animation, "Second Heart restarted you"; the second time within 5 minutes you die; the cooldown survives the reload; `/kill @s` still kills | - | `cyberware: second_heart on` |
| 17 | RAM Upgrade | install with the deck | RAM max 12 (16 with the Extra RAM perk too) | - | `cyberware: ram_upgrade on` |

4. **The double jump only with tendons.** Buy the Double Jump perk (free perks make it quick), then take the legs
   implant out: **no** double jump. Install Reinforced Tendons: double jump. The perk adds 4 blocks of safe fall to any
   legs implant, and nothing else.
5. **Migration.** On a world where Steve already owns the Double Jump, Sandevistan Sprint or Optical Camo perk, join
   once.
   - **Expect:** "Your perks now need cyberware: you received <items>. Install them with U", the items in the inventory
     and **not** installed, and no second message on the next join.
6. **Persistence.** Save and Quit, reopen; restart Cyberpunk while Minecraft stays up.
   - **Expect:** the implants are still installed and the HUD is back; the DLL gets the set again after each host hello
     (`cyberware: <id> on` lines).
7. **Four-key rebinding** (Minecraft Controls, category Cyberblock):
   - Cyberware to **O**, Activate to **L**: they work, and U and Y go back to Cyberpunk.
   - Cyberware and Hack both to G: **all four** go back to G K U Y, with a chat note;
     `binds: hack G, skills K, cyberware U, activate Y`.
   - Activate to **B** (refused): Activate falls back to Y and the others stay.
   - After each link Cyberblock.log has `binds: hack G, skills K, cyberware U, activate Y` (or the keys in force):
     Cyberware to L gives `binds: hack G, skills K, cyberware L, activate Y`; a clash gives a single warn and all four
     back to G/K/U/Y.
8. **Items and art.**
   - **Creative tab Cyberblock:** every implant shows its own icon (no purple-black missing texture) and its name.
   - **Recipes:** craft one or two from the HOW-TO-PLAY Cyberware chapter (redstone + copper ingot + clock + feather gives
     Kerenzikov; redstone + copper ingot + 2 RAM Shards + gold ingot gives RAM Upgrade). The Minecraft log has no recipe
     parse errors.
   - **The U screen background** draws unstretched: the body silhouette behind the slot boxes on the left, the implant
     list on the right. The slot boxes are placed by guess: say if a box sits badly on the art (the last row reaches
     slightly into the bottom strip).
   - **Tooltips:** hover an implant: Slot, the Minecraft and Night City lines, Cooldown and Install read as plain text,
     with no stray `%` placeholder. In the skill tree (K), Double Jump, Sandevistan Sprint and Optical Camo say they
     upgrade the implants.

---

## Re-test after the 2026-10-03 fixes (the first re-test; still valid)

The first session found the elytra flying Steve off alone, an empty NPC list, key clashes and netrunning gaps
(`out/user-issues.md`). Five fix packages are merged on main. Before testing: close Cyberpunk, make sure the install
ran (`uv run python -m tools.install`), and **restart Minecraft** (`uv run python -m tools.play`): a Minecraft that was
already running still has the old mod. The full key table and the netrunning walkthrough are in `docs/HOW-TO-PLAY.md`.

**What changed**
- **Keys (A, E):** F9 is Cyberblock's in every state (no QuickLoad); Mouse 4/5 and LCtrl stay Cyberpunk's; a dialogue
  with 2+ choices keeps E, Q, 1-4 and the wheel; Minecraft screens get the whole keyboard and real typing; Shift/Ctrl
  reach Minecraft (Shift-click, Ctrl+Q); X swaps to the offhand; either Shift sprints; Hack/Skill tree binds are limited
  to G K L O U Y 0 F1-F4 F6-F8 F10-F12 and fall back to G/K; Minecraft's Quick Actions no longer shares G.
- **Elytra and flight (A, B):** a glide needs 3 blocks of air and a Space while falling (2nd airborne press with Double
  Jump); cam now says fly/menu/air/held; a glide Cyberpunk does not follow is called off within 0.5 s and Steve returns;
  menus pause the glide; V stops at unmapped walls; no landing teleport for short glides; held V refuses pearls
  (refunded, no damage); Steve stands on V's ground with no "standing on air" spam or own fall damage.
- **Scenes, health, vehicles (C):** only scene tiers 2-5 hold V (free roam no longer counts); V is invincible in
  vehicles in every mode, hits go to Steve; instant kills go to Steve; falls arrive without a direction; the HUD shows
  F prompts and dialogue choices; explosions only reveal V when close; infinite potions survive saves.
- **NPCs and proxies (C, D):** one NPC registry; death judged by the puppet's own flag; the 5 s logs split people and
  cars and list why NPCs are left out; pushes say why they were withheld; lead tugs ragdoll people without killing
  them; collision boxes take their filters from the engine; proxies extrapolate (cars are 3 m wide), stay through
  menus and saves, and Minecraft logs how many exist; mobs prefer hostile NPCs; kill XP drops at Steve; an explosion
  hurts an NPC once.
- **Netrunning (E):** car hacks go to the driver; person hacks no longer need a proxy or get refused in free roam;
  refusals name their gate; scans fall back to the target nearest the crosshair; the lock and its tooltip last 5 s; a
  tap scans; hold G + wheel picks a chip, right-click fires it; chips show cost, recharge and usage; the kit fills RAM.

**R1. Keys (package A)**
1. F9 while Active, while Off and in the pause menu: it toggles Cyberblock and never quickloads.
2. Mouse 4 and 5 (dodge and scanner bindings) work in Cyberpunk while Active.
3. Talk to an NPC with 2 or more choices while Active: E, Q, 1-4 and the wheel scroll and pick in Cyberpunk, while the
   mouse buttons, 5-9, G and T still go to Minecraft. With a single-choice prompt, E still opens Steve's inventory.
4. Open Minecraft chat (T): letters, spaces, accented letters and emoji, plus Enter, Backspace, arrows, Home/End and
   Delete, all work. Cyberblock.log shows 'typing reaches Minecraft's screens' once. W A S D do not move V while a
   screen is open.
5. Close the inventory with E and immediately hold W: if W lands before Minecraft reports the screen closed (about
   50-100 ms), V does not walk until W is pressed again (the KeyGate first-down rule, by design). Does it feel OK?
6. Shift-click in a chest moves the whole stack; Ctrl+Q drops the whole stack; X swaps to the offhand; Right Shift
   also sprints, and a double-tap of either Shift triggers Sandevistan.
7. Rebind Hack to L and Skills to O in Minecraft Controls: they work, and G/K go back to Cyberpunk. Bind both to the
   same key, or one to B: both fall back to G/K and Cyberblock.log logs it once.
8. Open the pause menu, inventory or map while Active: cam.menu becomes true at once and forwarding stops.
9. Elytra end to end: cam.fly is true while V follows the glide and false after landing. A Minecraft hitch longer than
   0.5 s sets Cyberblock_FlightTimedOut but keeps cam.fly. cam.air is true while V jumps or falls, cam.held in a
   vehicle or a scene.
10. Camera-only flight fallback (`CyberblockFlight.SetCameraOnly(true)`): collision boxes appear around the flight
    position, not at V's parked spot.
11. Apply an infinite Minecraft effect (`/effect give @s speed infinite`), then save (quick or auto): the effect is
    back on V after the save. Leaving Active still forgets it.

**R2. Elytra, pearls, Steve on the ground (package B; `docs/notes/flight-spike.md`)**
1. F-1 to F-3: per-frame follow is smooth, the look is kept, and a rooftop-to-street landing puts V on the street. If
   death landings (or their effects) appear, report it (the fix is a LocomotionAirDecisions.ToDeathLand wrap).
2. F-8: start a glide while V is held (sitting, workspot). Expect 'flight: ignored ... (<reason>)' in Cyberblock.log,
   'glide called off (the host did not follow)' in the Minecraft log within 0.5 s, and Steve back with V.
3. F-9: Space at the end of a normal jump never opens the elytra; jumping off a ledge with 3+ blocks of air does. With
   the Double Jump perk, the 1st airborne Space double-jumps V and the 2nd opens the elytra.
4. F-10: open the map mid-glide for about 5 s. Expect 'glide paused' then 'glide resumed' in Minecraft, possibly
   'flight: no samples, holding V' then 'samples back' in Cyberpunk, and the glide continues.
5. F-11: glide into a building where nothing has been probed. V stops at the wall ('flight: V stopped short of Night
   City geometry' once) and Steve returns to V after landing.
6. F-12: throw a pearl while V sits or drives: overlay 'Pearl blocked: V cannot be moved right now', the pearl
   refunded, no 5 HP lost and no teleport.
7. health-1: no 'Player standing on air' spam in the Minecraft log, normal mining speed on the ground, no crits on the
   ground, and fall damage applied once (Cyberpunk's 'fall' only).
8. Camera-only mode: the probes and collision follow the flight (CyberblockFlight.Center).

**R3. Scenes, NPCs, health, HUD (package C)**
1. Elytra and pearl in free open-world play: no 'held' log, the glide follows. In a tier-2 quest scene: 'effects:
   teleport ignored, V is held (scene, scene tier 2)'. While swimming (tier 6), glides are allowed.
2. NPC list: every 5 s 'npcs: sent H people + C cars; attached H2 people + C2 cars; left out: ...'. Expect H > 0 near
   pedestrians. If it is still 0, the reasons listed (dead, defeated, mounted, puppet, detached, not_npc, over_cap)
   give the root cause.
3. Push withheld logs: hitting or leashing an ordinary pedestrian pushes them. If civilians show '(friendly)', that
   attitude is why pushes did nothing before. Quest NPCs in workspots show '(workspot)'.
4. Lead on a person: the tug ragdolls them at 4-8 m/s every 1.5 s, only once more than 0.5 m past the lead length,
   and the NPC survives. Vehicles are tugged as before.
5. A wind charge bursting at an NPC's feet or head pushes them; the log shows 'tether: knockback ..., N pushed' with
   N > 0.
6. Collision: the first box logs 'collision: World Static filters from the engine: simulation 376836/5070, query
   0/<mask with 7 bits>'. Then `docs/notes/collision-spike.md` checks 2-4: V is stopped by a box and can stand on it, a
   pedestrian is stopped by a 5-box wall, a traffic car and V's own car collide with it.
7. An explosion more than 30 m from V does not draw NPCs to V. One within 10 m reveals V; one at 10-30 m alerts NPCs
   without revealing V's position.
8. 'events: hit target <hash> amount <frac> by mob <id>', 'events: lead ...' and 'events: pull ...' appear at most once
   per second per kind.
9. While Active: F prompts (doors, talk, exit car) and dialogue choice lists are visible, the HUD hides again once
   they are gone, and the transition notification still shows for the first 2 s.
10. Vehicle in survival: 'vehicle: V invulnerable in the vehicle, hits go to Steve'. Crashes and gunfire on V's car
    give 'dmg vehicle/bullet ...' and V's health is unchanged. Getting out logs 'vehicle: V's own god mode removed'.
    In a save made in a car and then loaded, V has no leftover god mode.
11. G-9 kill test: in the Vehicle state, let gangers shoot V's car until Attacks.DriverKill fires (or another
    hitFlag.Kill hit lands on V). V lives, 'dmg instant kill reported as other x4 ...' is logged, and Steve dies. Also
    note whether BaseStatusEffect.ForceKill goes through ProcessInstantKill.
12. Fall damage arrives in Minecraft without a direction (no sideways knockback or tilt); the log reads 'dmg fall ...
    from unknown'.

**R4. Proxies, mobs, world (package D)**
1. With Minecraft on, open a Cyberpunk menu or save for longer than 2 s: leads, hooks and proxies are still there.
2. Cyberblock.log / Minecraft latest.log: the new 'proxies: H people, C cars (spawned n, removed m)' lines are the
   first direct evidence of whether Minecraft gets proxies at all. 0 people in Minecraft while the DLL says '0 sent of
   62' means the problem is on the DLL/script side.
3. A Cyberpunk explosion (own TNT, creeper, grenade) hurts an NPC once, not twice.
4. Drive or watch a fast car and swing or shoot at it: the proxy stays on the car (extrapolation up to 0.5 s; jumps
   over 40 m/s count as teleports, so cars above about 144 km/h do not extrapolate) and the 3 m box is easy to hit.
5. Mobs near real NPCs: zombies prefer hostile NPCs (gang members) over passers-by, never run after an NPC more than
   22 m from Steve, and mobs killed by NPC fire name the mob.
6. Kill an NPC far away with a gun: the XP appears at Steve.
7. Wind charge near a crowd: the knock reaches about 2.4 m, and the Demolitions perk does not widen it.
8. Flint and steel on a car does nothing and is not used up; on a person it ignites; an NPC standing in lava gets one
   ignite per 15 s.

**R5. Netrunning (package E; walkthrough in `docs/HOW-TO-PLAY.md`)**
1. Hold G on a pedestrian, then right-click a chip (in hand, or picked with the wheel while holding G). The hack
   lands, Cyberblock.log shows 'hack <name> on <id>: ok', and the RAM drops.
2. Hack an occupied car: the driver gets the effect. An empty car shows 'Hack failed: no driver' and refunds the RAM.
3. A quest or friendly NPC gives 'Hack failed: not hackable (god mode|friendly|quest character|no quickhack
   actions)', and the log says the same.
4. Press G with nothing under the crosshair: the log says 'scan N: nothing under the crosshair'. Aim about 2-3
   degrees off an NPC: the crosshair fallback still scans it. Note whether the TSF_Quickhackable pass ever finds
   anything while Active (expected: only the second pass does).
5. Release G after a scan: the tooltip and outline stay dimmed with 'Locked 5..1 s', then disappear. Moving the
   crosshair off the target while G is held clears them.
6. A quick tap of G scans.
7. With G held, the wheel cycles 'Chip: <name> (<n> RAM)' and does not move the hotbar. Without G, the wheel scrolls
   the hotbar.
8. A recharging chip shows 'Still recharging'. The chip tooltip shows the cost (1 lower with Cheap Hacks) and the
   usage line.
9. New survival world: the kit gives full RAM (8/8).
10. Controls screen: Quick Actions is unbound and G shows no conflict.
11. Rebind Hack to B: a chat note says B cannot be forwarded and G is used. Bind Hack and Skill Tree to the same
    allowed key: a note says G and K are used. Both notes also appear after a host hello.
12. With package A: typing in chat while Cyberpunk has focus (char/skey), Shift-click quick-move in a chest, Ctrl+Q
    with no screen and in an inventory, Esc on the death screen (it must stay open), X swaps to the offhand.

**R6. Verifier fixes**
1. Stand at an object with two or more F prompts (a body: "Pick up" / "Loot", or a vending machine): the wheel scrolls
   Cyberpunk's list and E does not open Steve's inventory. Step away: E opens it again.
2. Build a 3-block bridge over a gap, stand on it and save (quick save from the pause menu, and wait for an autosave):
   V does not drop. Then load that save with Cyberblock off: no invisible box is left (walk through where it stood).
3. Hold G on an NPC, release, open the pause menu for 2 s, close it: the tooltip and the lock agree (a hack fires at
   the NPC only while 'Locked n s' still shows).

---

# Full pass (first session's plan, updated for the fixes)

## 0. Before launching
- Close Roblox Studio (and check Task Manager for leftover `RobloxStudioBeta` processes) — memory headroom.
- Cyberpunk: Frame Generation **Off**, Resolution Scaling Off for the first run.
- In `D:\Cyberblock`: `uv run python -m tools.play` (starts Minecraft, waits for it, then starts Cyberpunk).
- Load a save outdoors in a busy street (NPCs, cars, NCPD nearby). Press **F9**.

## 1. Layer 1 acceptance (5 min)
1. Walk up stairs and under an overpass: Steve's hand/hotbar stay; placed blocks rest on Night City floors.
2. Look at a wall that has a Minecraft block behind it: **no block outline shows through the wall** (aim-limit fix).
3. Get in a car and out again: Cyberpunk HUD returns while driving; Steve/hotbar return on foot.
4. Close the Minecraft window mid-walk: within ~2 s V is restored with a notification. Restart Minecraft: it resumes.
5. Save while active, then load: the save loads normally.

## 2. Cyberpunk mechanics disabled while active (2 min)
6. Tab does not scan, X does not heal, no double jump / dodge / air dash, Alt opens no radial wheel, weapons cannot be
   drawn, phone (T) goes to Minecraft chat instead.
7. Esc opens Cyberpunk's pause menu; inside it, keys do not move/act Steve; after closing, Minecraft input works again.

## 3. Health link — "Steve's health is the only health" (5 min)
8. Let NCPD/gangers shoot V: Steve's hearts drop, V never dies.
9. Jump from ~10 m: fall damage on Steve's hearts. Let a car hit V: damage. Stand near a grenade/fire: damage.
10. Die in survival: Minecraft's death screen appears; click Respawn with the mouse; play continues.
11. `/gamemode creative` (press T or /, type it in Cyberpunk, Enter): nothing hurts Steve or V.

## 4. Layer 2 — blocks, explosions, weapons, fire (10 min)
12. `/cyberblock kit` (or in survival the kit arrives on first activation). Hotbar 1-9: iron sword, bow, stone, TNT,
    flint & steel, ender pearls, firework rockets, leads, fishing rod; shield in the offhand; elytra on the chest; the
    quickhack chips, arrows, glass, potions, spawn eggs, Sandevistan, Optical Camo and the Projectile Launch System in
    the main inventory (E); the Cyberdeck, Kiroshi Optics, Reinforced Tendons and Biomonitor already installed (U).
13. Build a 3-block-high wall across a sidewalk and a road: pedestrians and cars stop at it; V can stand on it.
14. Place TNT under a parked car and light it: real Cyberpunk explosion, car destroyed, NCPD heat.
15. Sword a pedestrian, shoot one with the bow: they take damage/ragdoll, heat appears.
16. Flint & steel (or a fire charge) on an NPC: they burn.

## 5. Layer 3 — mobs vs Night City (5 min)
17. Spawn eggs (zombie, skeleton, creeper) near pedestrians/gangers: mobs attack NPCs; gangers/NCPD shoot back and can
    kill mobs; a creeper's explosion damages Night City.

## 6. Layer 4 — elytra & ender pearls (5 min)
18. From a rooftop with an elytra equipped, jump off the edge (3+ blocks of air below) and press Space while falling:
    glide; fireworks boost; landing returns normal movement. Open the pause menu mid-air for 2 s, resume: the glide
    carries on.
19. Throw an ender pearl: V teleports where it lands; no fall damage spike.

## 7. Layer 5 — interactive items (5 min)
20. Lead on a pedestrian and on a car, walk away: they are dragged. Use the lead again to release.
21. Fishing rod: hook an NPC/car and reel in. Wind charge: knockback blast. Shield raised toward a shooter: blocked.
22. Potions: speed (V faster), jump boost (higher jump), invisibility (hostiles lose V).

## 8. Layer 6 — Cyberpunk in Minecraft form (10 min)
23. RAM bar on the Minecraft HUD; it regenerates.
24. Hold **G** on a ganger: glowing outline + tooltip (name, faction, threat). On a vending machine: device info.
    A person behind a wall more than 8 m away gets no tooltip (log `scan N: behind Night City at <id>`); a parked car in
    the open does get one.
25. Use each quickhack item on gangers: Redstone Charge (short circuit), Blaze Chip (overheat), Ink Sac Daemon
    (blind), Cobweb Daemon (cripple), Spider Eye Virus (contagion), Wither Skull Daemon (synapse burnout), Compass Ping,
    Bottle of Forgetting (memory wipe), Creeper Brain (cyberpsychosis). Redstone Lever on a vending machine, a camera,
    a traffic light.
26. **K** skill tree: `/cyberblock perks free on` (or `/xp add @s 30 levels`), buy Double Jump (with a legs implant:
    4 more blocks of safe fall; V's double jump itself needs Reinforced Tendons), Sandevistan Sprint (upgrades the
    Sandevistan implant; double-tap Shift fires it), Iron Skin (+4 hearts), Silent Steps, Chain Hack; check each does
    what its tooltip says. Buy Dodge
    (best on a save where V lacks Cyberpunk's own Dash/Air Dash): V dashes, and air dashes off a jump. While Active,
    open Cyberpunk's perk screen (Esc → Character): the Reflexes milestones show V's real levels.
27. Cyberware: the mantis blade and gorilla gauntlet items are superseded by the implants, see "Re-test 2" R6 above.

## 9. Safety pass (3 min)
28. F9 off/on 10 times; drive; save and load while active: V always comes back normal (HUD, weapons, no god mode,
    visible body, normal time speed). Hold a pedestrian on a lead and save from the pause menu, then wait for an
    autosave: the lead stays in Minecraft and the pedestrian is still dragged afterwards. Get in a car while leading
    one: Minecraft drops the lead and Cyberpunk stops dragging (log `tether: released <id>`).
29. Optional: `uv run python -m tools.install --remove` then reinstall — the game folder is left exactly as before.

---

## Appendix — every in-game check the build agents recorded

### Re-test 3 — needsInGame lists

Each package of the Re-test 3 build lists the checks that only the real game can answer. The integrator pastes the lists
of packages D, U, S, M, C, W and A under the headings below; the F1-F13 checks above cover them in the user's order. The
checks of package P (docs and release) are filled in.

#### Re-test 3 — package D (DLL)
_(the integrator pastes the list here)_

#### Re-test 3 — package U (Cyberpunk UI bridge)
_(the integrator pastes the list here)_

#### Re-test 3 — package S (scripts)
_(the integrator pastes the list here)_

#### Re-test 3 — package M (Minecraft core)
_(the integrator pastes the list here)_

#### Re-test 3 — package C (Minecraft cyber)
_(the integrator pastes the list here)_

#### Re-test 3 — package W (Minecraft UI)
_(the integrator pastes the list here)_

#### Re-test 3 — package A (art, assets, lang, data)
_(the integrator pastes the list here)_

#### Re-test 3 — package P (protocol, docs and release)
- **Release zip on a clean machine (F11).** Unzip `dist/Cyberblock-1.0.0.zip`, run `install.bat` on a copy of the game
  folder: it must refuse while Cyberpunk runs, find the game through Steam (or accept a dropped folder with `-Game`), warn
  about missing frameworks, back up the saves once, never overwrite a foreign file, keep an existing `ReShade.fxh` and
  `ReShadePreset.ini`. The manifest it writes must be readable by `tools/install.py --remove`, and the manifest of
  `tools/install.py` by `uninstall.bat`. (The automated tests run `install.ps1` against a fake game folder; the real
  Steam lookup and the registry are only checked here.)
- **Launcher install (F11).** Follow HOW-TO-PLAY section 1 literally with a fresh launcher profile: the dedicated game
  directory, the JVM arguments **replaced**, the jar and Fabric API in `mods`. The title screen must show "Play
  Cyberblock" and the world must open (autostart). If any step of the written instructions is wrong for the real launcher,
  correct HOW-TO-PLAY and README.
- **Docs against the build.** Compare the key table, the chip table (HOW-TO-PLAY "What each chip does") and the UI tour with
  what you see; tell Claude every sentence that is wrong.
- **Wire.** Run `uv run python -m tools.fakehost --ui --hacks --hold 12` against the dev client (port 25600 is the real
  link: use another port with `--port` and a client started with `-Pcyberblock.port`): the HUD elements, the pause and
  pass-through screens, the upload beam and the spreads must show, and Minecraft's `uiact`, `persp`, `status.pause`, `binds`
  lines print in the terminal.


### Re-test 2 — package D (DLL)
- [ ] Drive signature: on the first flight Cyberblock.log shows exactly one `drive: ready (Teleport handle:gameObject,
  Vector4, EulerAngles -> Void, raycast Vector4, Vector4, opt CName, out TraceResult (or physicsTraceResult), opt Bool,
  opt Bool -> Bool; ...)` and no `drive: disabled:` line. If it says disabled, `cam.fly` stays false and Minecraft calls
  every flight off within 0.5 s.
- [ ] F-1 smoothness: glide at about 20 m/s for 5 s or more and land. The summary line `flight: N frames, M samples
  (median spacing a ms, max gap b ms), max step c m, clipped no` shows a <= 17 ms and c < 1 m, with N about 60 x the
  seconds (not about 1 per second as in the 15:04-15:21 log).
- [ ] F-2b: turn the mouse hard while gliding. V's body keeps its own facing and the FPP camera turns smoothly. If yaw
  sticks or snaps back, record it (the forward is already read just before every Teleport).
- [ ] F-3 landing: `flight: landed (glide) after X s` is logged in the same frame as Minecraft's touchdown, V stands on
  the street (not in mid-air or inside it), and there is no `dmg fall` and no instant-kill report.
- [ ] L-7 menu: open the Esc menu mid-glide for 5 s, then close it. There is no `flight: no samples for 1 s, V put on
  the ground`; V either resumes (`flight: samples back`) or lands normally.
- [ ] L-1 to L-6 (wind charge launch): `flight: start (launch)` then `flight: landed (launch) after X s`. L-6: while V
  sits in a vehicle or a scene there is no start; a start already under way gives `flight: stopped, V is held, in a
  vehicle or Cyberblock is off` and V is not teleported.
- [ ] Clip: glide Steve through a Night City wall that Minecraft has no barrier for. Expect `flight: V stopped short of
  Night City geometry` once, `clipped yes` in the summary, and V ending on the ground on its side of the wall.
- [ ] Timeout: freeze or close Minecraft mid-glide. Expect `flight: no samples, holding V` and, about 1 s later,
  `flight: no samples for 1 s, V put on the ground`.
- [ ] Ground alignment (R4): after Minecraft's first-activation align, Cyberblock.log shows `ground alignment: Minecraft
  y = Cyberpunk z - 0.xxxx (k NN/64)` followed by `ground: alignment k 0 -> NN, Minecraft gets the ground again`. A
  block placed on the street sits on it; the cam log (`lv`) matches.
- [ ] Binds: after each link, `binds: hack G, skills K, cyberware U, activate Y`. Rebind U to L in Minecraft and expect
  `binds: hack G, skills K, cyberware L, activate Y`. Rebind two actions to the same key and expect a single warn and
  all four back to G/K/U/Y.
- [ ] Cyberware events: install an implant in Minecraft. The script log shows `cyberware: <id> on` (S5) for exactly that
  id; Y on Sandevistan gives `cyberfx: sandevistan 6 s`.

### Re-test 2 — package S (scripts)
- [ ] R2 scan: aim at a pedestrian and press G. Cyberblock.log shows `events: scan scan N: ok NPCPuppet <8-digit id>
  (look-at|near crosshair)`. Then fire memory_wipe: the `hack memory_wipe on <same id>` line must not say `target gone`.
- [ ] R2 ids: hit an NPC, then kill one. The `effects: push withheld from <id>` line and `killed human <id>` use the same
  8-digit id as `events: hit target <id>`, never 0 and never a 13-digit heap-like number. `puppets: mobdmg ... by <id>`
  is a real id too.
- [ ] F-1 to F-3 (with package D): glide with the elytra. There is no `flight: ignored` line, and the DLL summary line
  appears. After landing, Cyberblock.log has no `dmg fall` and no `dmg instant kill` for the landing. If a landing attack
  still fires, expect `dmg fall ... not reported: flight or teleport landing` or `dmg instant kill (landing) not
  reported: flight (Attacks.*Landing)`.
- [ ] Landing state (follow-up trigger): after a fast glide or wind-charge landing, V can walk or jump at once. If V stays
  frozen in Fall on the ground for about 2 s and then plays a hard landing (or the log shows a `dmg fall` about 2 s after
  landing), add the ToRegularLand wrap: `wrappedMethod || SuppressFall && IsTouchingGround`.
- [ ] L-6: start a glide or launch while V sits in a vehicle or is in a scene. Expect one `flight: ignored, V may not be
  moved (vehicle|scene|workspot|...)` line, and Minecraft calls Steve back within 0.5 s.
- [ ] Clear: press F9 (or save) mid-glide. Expect `flight: cleared in mid-flight, V put on the ground`, and V standing on
  the ground below, not in the air.
- [ ] R4: after the alignment, place a block on the street. V's collision box sits flush on the street (V can step onto
  it, no 1 m gap). Throw an ender pearl onto the street: V lands on the floor without sinking or dropping. Throw one onto
  a placed block: V stands on top of it.
- [ ] S5 jumps: install reinforced_tendons (log `cyberware: reinforced_tendons on`): V double-jumps. Remove it: no double
  jump, even with the double_jump perk. Install fortified_ankles: holding Space more than 0.15 s and releasing gives a
  charge jump, and a double jump does not.
- [ ] S5 lynx_paws: with lynx_paws (or the silent_steps perk), the CET command
  `Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.Visibility)` returns half the
  value it gives without either.
- [ ] S5 Sandevistan: press Y. Expect `cyberfx: sandevistan 6 s`, the world slowed to x0.2 with V at normal speed, then
  `cyberfx: sandevistan slow-mo ended` about 6 s later. Kerenzikov (bow or crossbow while airborne) gives `cyberfx:
  kerenzikov 2 s` and the world at x0.3. A Kerenzikov during a running Sandevistan switches the factor to 0.3: this
  checks that SetTimeDilation with the same reason replaces the old one.
- [ ] S6: activate Berserk. Expect `cyberfx: berserk 8 s` and `berserk: Minecraft side only`. V gets no superhero landing
  or ground slam on falls.
- [ ] Kiroshi: an NPC about 60 m away near the crosshair scans ok only with kiroshi_optics installed; without it the log
  says `scan N: nothing under the crosshair`.

### Re-test 2 — package M (Minecraft world)
- [ ] R4-1 (new or pre-retest-2 world): on the first activation with V standing still on the street,
  `mc/run/logs/latest.log` shows `ground alignment: k -1 -> N (offset X m) at Cyberpunk z Z (first activation)`, then
  `block resync: ...`. Cyberblock.log shows D's `ground alignment: Minecraft y = Cyberpunk z - X (k N/64)`. A block Steve
  then places on the street sits flush on it, not a block above.
- [ ] R4-2: standing on the street, run `/cyberblock level`. Chat: "Ground aligned: offset X m (was Y m)...". The log
  shows `ground alignment: k A -> B ... (/cyberblock level)` then a block resync. While jumping or in a car: "V must
  stand on the ground...". With F9 off or no Cyberpunk: "Cyberpunk is not connected, or Cyberblock is off".
- [ ] R4-3: after `/cyberblock level`, Steve's feet stay on V's floor with no visible vertical jump of the picture. This
  checks HostState's `lv` correction during the align round trip.
- [ ] R5 L-1 (survival): throw a wind charge at your own feet. The log shows `launch started at ... with ~22 m/s`; Steve
  and V rise together, land, then `launch ended at ...`. No Night City fall damage on V and no `dmg fall` reaching
  Steve.
- [ ] R5 L-2 (creative): the same, with the same log lines. This checks ServerExplosionMixin and
  SimpleExplosionDamageCalculatorMixin.
- [ ] R5 L-3: a breeze's or another player's wind charge near Steve launches him (`launch started`). L-4: a mace hit with
  Wind Burst launches the attacker.
- [ ] R5 L-5: in a launch with an elytra, press Space while falling more than 3 blocks above ground. The log shows
  `launch turned into a glide at ...` then `glide ended at ...`, and V follows the whole way.
- [ ] R5 L-6: while V sits in a car or is held by a scene, a wind charge at Steve's feet logs no `launch started` and
  Steve stays with V. If the DLL drive is disabled, a launch shows `launch called off (the host did not follow)` within
  0.5 s and Steve snaps back to V.
- [ ] R1 hand-off: after a glide or launch lands, Steve takes V's position again as soon as V stands at the landing spot,
  with no visible 0.5 s freeze. Flight messages carry `kind` and `ts`: the DLL's per-flight summary line shows the median
  spacing.
- [ ] R2 (with C1 merged): aiming about 0.3 m beside a pedestrian, or 0.5 m beside a car, and pressing G picks it.
  `Bridge.proxyAimedAt` works from the server's proxy positions.
- [ ] R6 hooks: with gorilla_arms installed and an empty main hand, a punch shoves the NPC once (the hit impulse is twice a
  bare punch); the gorilla_arms perk alone does the same, and both together still shove once. With reinforced_tendons
  installed, V's first air jump is V's own double jump and the second press opens the elytra. Kiroshi outlines appear
  only around hostile proxies: ProxyEntity DATA_HOSTILE is synced to the client.

### Re-test 2 — package C (Minecraft cyber)
- [ ] Survival, fresh kit: install the kit's Sandevistan (right-click it, or U then click). Expect the overlay "Installed
  Sandevistan (returned Cyberdeck)" and the latest.log line `cyberware: installed sandevistan in operating_system/0`.
  Then scan a target with G and fire a quickhack chip. Expect "Your Operating System slot holds Sandevistan: swap in the
  cyberdeck with U to hack" (the lang line `cyberblock.hack.no_deck_os`, added at integration; the code's fallback text
  is the same), not "...or carry one".
- [ ] In the same state, use a RAM shard. Expect the same OS-slot message instead of the generic no-deck one.
- [ ] Press U and click the returned Cyberdeck to swap it back in. Expect "Installed Cyberdeck (returned Sandevistan)",
  the RAM HUD back at 8+, and quickhacks firing again.
- [ ] Rebind the Cyberware key (for example to O) and repeat the first check. The message names the new key.

### Re-test 2 — package A (art, assets, lang, recipes)
- [ ] With C's items merged (they are), these are pure integration checks. In the creative tab, every new cyberware item
  shows its own icon (no purple-black missing texture) and its name from the lang file.
- [ ] Open each recipe in the recipe book or craft one: for example redstone + copper_ingot + clock + feather gives
  Kerenzikov; redstone + copper_ingot + cyberblock:ram_shard x2 + gold_ingot gives RAM Upgrade. Expected: no recipe
  parse errors in the Minecraft log.
- [ ] Press U: the cyberware screen background (`textures/gui/cyberware_bg.png`, 256x192) draws without stretching. The
  art layout is A's guess, so C's boxes may need nudging: the silhouette is centred on x=64 and spans y 20..177, the right
  list field spans x 135..247 and y 22..171, and the title strip (y<18) and bottom strip (y>175) are empty. (C's slot
  boxes span x 8..132 and y 20..184, so the last row reaches 9 px into the bottom strip; the implant cells span x
  150..238 and y 32..164, inside the list field.)
- [ ] Hover a cyberware item: the tooltip lines (Slot, .mc, .cp, Cooldown, Install) read as plain text and none contains
  a stray `%` placeholder. Open the skill tree (K) on Double Jump, Sandevistan Sprint and Optical Camo: the texts say
  they upgrade the implants.

### Re-test 2 — package P
- [ ] With a dev client on a test port (`-Pcyberblock.port=<port>`, `-Pcyberblock.frame=<frame>`) and `uv run python -m
  tools.fakehost --layer4 --port <port> --hold 15`: after a glide or launch starts, the fake host's `cam` carries
  `fly:true` while it follows `flight` (Minecraft keeps the flight beyond 0.5 s and does not log `glide called off`), and
  `fly:false` after touchdown. Only a real client can show this: the unit tests only check what the fake host sends.
- [ ] Same client with `--layer6 --hold 12`: Minecraft logs a `key cyberware` pair twice (the Cyberware screen opens and
  closes) and a `key activate` tap (Y fires the selected implant or says "No active implant installed"); fakehost prints
  `align`, `cyberware` and `cyberfx` lines when Minecraft sends them.
- [ ] A world aligned by fakehost (its `cam` has no `lv`, so Minecraft converts it at its own `k`): fakehost prints `align`
  once and then the `blocks reset:true` resync, and nothing else changes.
- [x] Integration: the DLL's and Minecraft's alignment tests agree with the fixed `align` table in `shared/protocol.md`
  and `shared/vectors.json` (6.907 gives 57, 6.001 gives 63, 0.0 gives 63, -3.25 gives 47, 312.999 gives 63, -0.0005
  gives 63, 64.5 gives 31). Checked at integration: Python `align_k`, Java `GroundAlignTest` and `shared/vectors.json`
  agree; `cb_tests` checks that the vector file holds the 7 cases (the DLL never chooses `k`).
- [ ] All user-facing Re-test 2 checks (R1 to R6, F-1..F-3, L-1..L-7, the 17 implants) are in the "Re-test 2" section
  at the top with the log lines to grep.

### Layer 2 — track P
- [ ] Track M's M6 oracle: with the dev Minecraft client running on a test port, run 'uv run python -m tools.fakehost --layer2 --url ws://127.0.0.1:<port> --shm <name> --cmd "summon tnt 0 64 0" --hold 15' and confirm that explosion arrives (power 4), that block events arrive when blocks are placed or destroyed, that hit arrives when a bow or sword hits a proxy, and that Steve loses 5 hearts (25% of max health) from the dmg. Only Minecraft can show this, since the fake host has no real Minecraft side.
- [ ] Integration: confirm the DLL's own copy of the layer-2 conversions (cp/plugin/src/cbmath.*) passes the new mc_pos and mc_block vectors in shared/vectors.json.

### Layer 2 — track M
- [ ] With the dev client on a test port (-Pcyberblock.port, -Pcyberblock.frame) and the real DLL and updated S1 script installed: /fill a region of about 2000 cells (e.g. fill 30 70 30 50 80 50 stone) and then clear it; confirm in Night City that Cyberblock_Log shows no queue drops (QueueStats dropped stays 0) and that every collision box appears and disappears, with no invisible walls left behind. Rerun mc/tools-dev/layer2_check.py against the dev client to confirm the 363-cell fill/clear checks still pass with the slower pacing (expected about 1.2 s per fill).

### Layer 2 — track D
- [ ] After loading, red4ext/logs/Cyberblock.log shows 'redscript natives registered' and no 'native ...: unknown parameter type' or 'unknown return type' lines. This is where array:Uint64, array:Float, array:Bool and Uint64 resolve in the game's RTTI at post-register time. Layer 1 only proved array:Vector4. If any of these lines appear, the redscript call to that native will misbehave.
- [ ] With the mashup Active and Minecraft attached: run /summon tnt in Minecraft. A CET console call to Cyberblock_NextEvent() should return the CName explosion, then Cyberblock_EventPos() should be the TNT position in Cyberpunk space and Cyberblock_EventAmount() should be 4.0. A second call to Cyberblock_NextEvent() should return an empty CName. Once S1's pump lands the scripts drain events themselves, so this check is easiest before then or with the pump disabled.
- [ ] The 5 s memory line in the log reads 'mc events: N queued, M dropped, K rejected'. In normal play dropped and rejected stay 0 and queued is near 0. A climbing dropped count means the redscript pump is not draining events (up to 64 per tick).
- [ ] Yaw convention for Cyberblock_SubmitNpcs: the DLL assumes Cyberpunk yaw in degrees as Entity.GetWorldYaw reports it (0 faces +Y, counter-clockwise from above). Stand an NPC facing north and check that its Minecraft proxy faces the same way (Cyberpunk yaw 0 becomes Minecraft yaw 180, facing -Z). S1's Npcs.reds must pass degrees, not radians.
- [ ] Cyberblock_Damage: shoot V and check the Minecraft log or fakehost shows a dmg with a sensible frac and a from position that is correctly converted (Cyberpunk shooter at (x, y, z) arrives as Minecraft (x, z, -y)).

### Layer 2 — track S1
- [ ] God mode still reaches the damage pipeline (Active, on foot): let an NPC shoot V. Cyberblock.log should show 'dmg bullet <dmg> of <max> from known (Attacks....)'. In CET, Game.GetStatPoolsSystem():GetStatPoolValue(Game.GetPlayer():GetEntityID(), gamedataStatPoolType.Health, true) should stay at 100. If no dmg line appears, the native layer skipped OnHit for the invulnerable player.
- [ ] Fall damage: jump from about 10-15 m while Active. Expect a 'dmg fall' line, and frac >= 1 for a death landing. If there is no line, the landing AoE attack does not hit its own instigator (self-damage flag missing from the effect set).
- [ ] Vehicle hit: let a car hit V while Active. Expect 'dmg vehicle ... from known'. Check that the from point (Minecraft knockback direction) is on the car's side, which confirms the hitDirection sign used in the fallback.
- [ ] Grenade or explosive barrel near V: expect 'dmg explosion'. Standing in fire: expect 'dmg fire' or 'dmg explosion'.
- [ ] NPC registry: on a busy street while Active, the 5 s line 'npcs: N sent of M NPCs/vehicles attached' should show M > 0 and N <= 48. Stand next to parked or moving traffic and confirm vehicle proxies appear in Minecraft; this proves the native type name n"vehicleBaseObject" resolves in EntityTarget.Type. Also confirm M does not keep growing over minutes (detach pruning works).
- [ ] Yaw: an NPC proxy's facing in Minecraft matches the NPC in Night City. Or compare CET npc:GetWorldYaw() with Rad2Deg(atan2(-fwd.x, fwd.y)).
- [ ] Codeware entity callbacks during a heavy spawn (fast travel into a crowded area): no crash or script errors. The callbacks only push or remove weak refs in a script array, which assumes entity attach runs on the game thread.
- [ ] After a save or load while Active: the log shows the collision clear lines and boxes come back on the next live tick (needs S2's Collision).

### Layer 2 — track S2
- [ ] Sword kill on a pedestrian (CET: CyberblockEffects.Hit(g, <ped EntityID hash>, 2.0, Vector4.new(6,0,0,0))): the pedestrian plays a normal death or ragdoll and is NOT dismembered, and the redscript/Cyberblock log has no errors. A non-lethal hit (frac 0.2) plays a melee hit reaction.
- [ ] Same Hit on a quest NPC or companion (Invulnerable/Immortal god mode or friendly, e.g. a fixer or Judy) and on an NPC sitting in a workspot (a bar stool, a bench): no ragdoll and no push. A plain hostile ganger in the open gets ragdolled.
- [ ] Hit with frac 0.2 and impulse (6,0,0) on a traffic car: the car takes damage and is pushed. The same call while V drives that car: the car is not pushed.
- [ ] Hit on a vehicle in decay state (burning or about to explode): no extra damage is applied.
- [ ] After a sword hit on a civilian, NCPD heat and the crime report still appear (needs SetAttackTime and attackType for PreventionSystem.CreateNewPreventionDamageRequest).
- [ ] Collision check 6 in docs/notes/collision-spike.md: add a box, CyberblockCollision.Reset(g), then Stream after moving 5 m. The box does not return, and the log has 'collision: reset, 1 boxes forgotten'.

### Layers 3-6 — track P
- [ ] README claims to confirm during the I.2 acceptance run (they come from the plan, not from anything verified here): Space while falling with an elytra opens it and a fall above 15 m/s for 0.5 s opens it on its own; double-tapping Left Shift within 300 ms with the Sandevistan perk fires it; F5 does not quick-save; the G/K rebinding limits.
- [ ] Run uv run python -m tools.fakehost --port 25710 --layer3 --layer4 --layer6 --hold 12 against a test client (track M or C oracle) and check that Minecraft's printed 'mobs:', 'flight:', 'proj:', 'scan:' and 'hack:' lines match shared/protocol.md and that the fall cam at x=-1, z=-1 starts a glide when the jump tap arrives. Nothing here could be checked without a client: no client was launched and no port was opened beyond the ephemeral test servers.

### Layers 3-6 — track D
- [ ] G-1 keyboard path: press F9 to activate, then read red4ext\logs\Cyberblock.log for the line 'raw keyboard input is registered (flags ...)' or 'raw keyboard input is not registered'. Then, while Active, press F5 and G and K: expect no quicksave notification, no walk toggle, no crafting menu. Press E, Q, T, 1, 2: nothing happens in Cyberpunk (1/2 do not pick interaction choices), and Minecraft receives inventory/drop/chat/slot.
- [ ] IAT hook: the log should show 'hooked GetRawInputData in the game's import table' at startup. While Active, left/right/middle click and scroll: Cyberpunk shows no weapon wheel or weapon switching, no Tag/scanner zoom, and V's camera still turns with the mouse. After F9 off, the mouse buttons work again. If the log says 'could not hook GetRawInputData', mouse buttons also reach Cyberpunk.
- [ ] Key-up rule: hold E, press F9 (Active), release E: Cyberpunk must have no stuck E (interaction does not repeat). Hold the left mouse button, press F9, release: no stuck fire or attack in Cyberpunk.
- [ ] Space and Left Shift while Active: V still jumps and sprints in Cyberpunk, and Minecraft receives key jump and key sprint (cp\scripts or fakehost output) with no auto-repeat.
- [ ] Binds: rebind the hack key in Minecraft's controls to Z: Z is swallowed and G reaches Cyberpunk again. Bind it to W or E: Minecraft's chat says it works in Cyberpunk as G, and G is the hack key there (Minecraft now sends the default for the keys the DLL refuses).
- [ ] Menus (needs S1 calling Cyberblock_SetMenu): open Cyberpunk's pause or inventory menu while Active: E/Q/T/1-9 and mouse clicks behave normally in the menu, and forwarding resumes when it closes.
- [ ] Look capture, from the CET console: Cyberblock_SetLookCapture(true), move the mouse, and fakehost/Minecraft log shows look {d:[dyaw,dpitch]} (right = positive yaw, down = positive pitch, 0.15 degrees per count, at most 120/s). Cyberblock_SetLookCapture(false) stops it.
- [ ] Stance: Cyberblock_SetStance(true) from CET, and fakehost shows cam.cr true on every cam message; false clears it.
- [ ] Game mode: Cyberblock_GameMode() == CName.new("creative") in a creative Minecraft world, n"" before the first status or after Minecraft closes.
- [ ] The hook with ReShade's own input hooks: open and close the ReShade overlay (Home) while Active and check the mouse still works and no crash on unload (F-key exit or alt-F4).
- [ ] Mob/flight/proj natives with a real Minecraft (track M): CET Cyberblock_MobCount() and Cyberblock_FlightSeq() change as mobs appear and when gliding starts and ends.

### Layers 3-6 — track M
- [ ] With real Cyberpunk: start a glide (elytra, jump while V falls), open the pause menu mid-air for more than 2 s, then resume. Expect: Steve is not left gliding; the elytra durability tooltip does not drop while paused or afterwards; V/Steve hang at the stop point and follow V again; a new jump-while-falling starts a fresh glide.

### Layers 3-6 — track C
- [ ] With real Cyberpunk: let Cyberpunk connect while Minecraft is at the title screen, then open a world where Steve owns double_jump and dodge. Expected, without buying anything: the Cyberpunk log or CET shows a 'perks' message with 20 entries carrying those ranks, V can double jump, and dodge works.
- [ ] In the same session, Save and Quit to the title in Minecraft. Expected: Cyberpunk receives a 'perks' message with all ranks at 0, double jump no longer works, and silent_steps Visibility is back to normal. Then open a different world with different ranks: Cyberpunk gets that world's ranks.
- [ ] After merging with M (whose HostLink calls Bridge.dispatch): start Cyberpunk first, so its link waits, then start Minecraft. Expected: 'binds' arrives right after hello. If it is ever missing, see the entrypoint-order note.

### Layers 3-6 — track S1
- [ ] G-1 keyboard path: track D logs whether GetRegisteredRawInputDevices lists usage page 1 / usage 6. With Cyberblock Active on foot, press F5 and G: there should be no 'Quicksave' notification and no walk toggle. The DLL swallow comes first, and the Disable.reds wraps on HandleQuickSave and ProcessToggleWalkInput are the second line.
- [ ] G-2 stat modifiers are not saved: equip Reinforced Tendons and go Active on foot. Double jump should fail, and CET Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.HasDoubleJump) should return 0. Save from the pause menu while Active (PlayerState removes the modifiers before the save), turn Cyberblock Off and reload: the same CET line should return 1.
- [ ] G-9 lethal paths under Cyberblock god mode. Fall test: Active, jump off a megabuilding roof (DeathLand). V should survive, and Steve should take fall damage with frac about 4 (Cyberblock.log line 'dmg fall'). Kill test: in a throwaway save, apply BaseStatusEffect.ForceKill to V while Active. Does V die?
- [ ] G-10 DoT wrap: while Active, let an NPC with an incendiary weapon (or another Burning applicator) set V on fire. Expect several 'dmg fire' lines in Cyberblock.log from one ignition, with V's Health unchanged.
- [ ] G-11 camo perks: with a camo slide or grapple perk owned and Cyberblock Active (CanUseOpticalCamo and CanUseTerrainCamo x0 applied), slide or grapple. V should not cloak.
- [ ] G-12 superhero landing: with a ground-slam source owned and Cyberblock Active (HasSuperheroFall x0 applied), crouch in mid-air. There should be no slam.
- [ ] G-13 radial wheel and vehicle: Active on foot, holding Alt opens no wheel and the world does not freeze. Get into a car (Active to Vehicle; PlayerState clears NoRadialMenus and its VehicleBlockRadioInput tag): the vehicle radio wheel should work.
- [ ] G-14 restrictions not left in saves: save while Active, then load. CET StatusEffectSystem.ObjectHasStatusEffect(Game.GetPlayer(), TweakDBID.new('GameplayRestriction.NoScanning')) should be false, as for NoHealing, NoRadialMenus, NoQuickMelee, NoZooming and BodyCarryingForceDrop. Optionally the audit's raw check: apply NoScanning with isSavable=false, save, reload, and see whether it is gone.
- [ ] DISABLE rows while Active on foot: Tab does not scan (no world slowdown); X does not heal; there is no double jump, charge jump, hover, dodge or air dash; dead or downed NPC bodies cannot be picked up; zoom does nothing. After Steve buys the dodge perk (K): dash and air dash work. After Steve buys double_jump: double jump works (S3 grants +1, S1 no longer zeroes it).
- [ ] Menu flag: Active, open the pause menu and press W/A/S/D and E. Steve must not move or act in Minecraft; after the menu closes, input reaches Minecraft again.
- [ ] Stance: Active, crouch V (C), then slide. Fake host or Minecraft should show cam.cr=true (Steve in the crouch pose); stand up and cr returns to false.
- [ ] Probes through our boxes: Active, build a 3-block Minecraft wall 2 m from V on a street. In Minecraft, no barrier should appear in the air cell in front of the wall, the wall block can be targeted and outlined (the aim is no longer stopped at the box face), and the street under a placed block stays ground.
- [ ] Flight probes: glide from a roof (layer 4). Minecraft's barrier ground should appear well ahead in the glide direction, up to 64 m, and the 2 m grid should fill in to 1 m once within 24 m.
- [ ] Creative: Minecraft in creative, F9 while driving. Crash into a wall at speed and let NPCs shoot the car: V cannot die. Cyberblock.log should show 'creative: V invulnerable in the vehicle', and 'creative: V vulnerable again' on getting out or switching to survival.
- [ ] Fall suppression: land a glide or an ender pearl teleport from a height. Within 2 s there should be no 'dmg fall' sent; the log should show 'not reported: flight or teleport landing'.
- [ ] block_reset: reconnect Minecraft while Off. Cyberblock.log should show 'events: block reset' and 'collision: reset'; after F9 the boxes come back from Minecraft's resync.
- [ ] Inactive requests: with Cyberblock Off, hold G in Minecraft and use a quickhack item. Minecraft should get scaninfo ok:false and hackresult ok:false with reason 'Cyberblock is not active'.

### Layers 3-6 — track S2
- [ ] Gate check, after the Npcs.reds patch is merged: Cyberblock Active, Minecraft running, spawn a zombie from a spawn egg next to Steve. Expected: Cyberblock.log has 'puppets: spawned for ... zombie', the 'npcs: N sent' count does not grow by one, and Minecraft shows no human NPC proxy at the zombie's position (F3+B shows only the zombie's hitbox there).
- [ ] Despawn window: kill the zombie in Minecraft. Expected: 'puppets: despawned mob <id>' in the log and no human proxy appearing at the spot, not even briefly. This confirms that the Codeware tag (IsTagged) outlives the entity's attachment, or detaches in the same frame. If a proxy flashes, the Despawn spawner event comes before Entity/Detach.

### Layers 3-6 — track S3
- [ ] G-3 Quickhack: in CET, npc = Game.GetTargetingSystem():GetLookAtObject(Game.GetPlayer(), false, false), then StatusEffectHelper.ApplyStatusEffect(npc, TweakDBID.new('BaseStatusEffect.OverloadLevel1'), Game.GetPlayer():GetEntityID()). Expect electrocution VFX and damage. Then use a Redstone Charge from Minecraft on a ganger. Expect an upload delay, the same result plus NCPD heat, and Cyberblock.log 'hack short_circuit on <id>: ok'. Repeat on a crowd NPC and on a mech.
- [ ] G-4 Device hack: Redstone Lever on a vending machine -> the screen glitches and cans drop (log 'hack device on <id>: ok'). Redstone Lever on a camera -> its detection cone stops tracking V, and the Minecraft view must NOT switch to the CCTV.
- [ ] G-5 Traffic: Redstone Lever on a traffic light at an intersection. Do the intersection's lights change, and do cars stop and go?
- [ ] G-6 Slow-mo: buy Sandevistan and double-tap sprint. NPCs and cars run at x0.2 while V does not, for 5 s. Log: 'perks: sandevistan slow-mo for 5 s', then 'perks: slow-mo ended'. Note any screen effect. Pressing F9 during the slow-mo must restore normal time at once.
- [ ] G-7 Camo: in combat, drink an invisibility potion -> hostiles lose V. Compare with only Game.GetPlayer():SetInvisible(true) in CET. After 10 s, StatusEffectSystem.ObjectHasStatusEffect(Game.GetPlayer(), TweakDBID.new('BaseStatusEffect.OpticalCamoPlayerBuffRare')) should still be true (Buffs re-applies it once a second if camo decay removes it).
- [ ] G-8 Visibility: with Silent Steps bought, Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.Visibility) is half its perk-off value. Crouch-walk towards an idle guard and compare how long the detection meter takes to fill with and without the perk.
- [ ] Hack short_circuit (Redstone Charge) on a ganger: after the upload, electrocution VFX and damage; hackresult ok:true.
- [ ] Hack overheat (Blaze Chip): the ganger burns (overheat VFX) and its health ticks down; Steve takes no damage.
- [ ] Hack reboot_optics (Ink Sac Daemon): blinded VFX, and the ganger loses track of V for the effect's duration.
- [ ] Hack cripple_movement (Cobweb Daemon): the ganger stops moving.
- [ ] Hack contagion (Spider Eye Virus): poison VFX and damage over time on the target. Note whether it spreads (that depends on V's spread perks).
- [ ] Hack synapse_burnout (Wither Skull Daemon): brain-bolt VFX and heavy damage.
- [ ] Hack ping (Compass Ping): the log shows ok, and in CET StatusEffectSystem.ObjectHasStatusEffect(npc, TweakDBID.new('BaseStatusEffect.Ping')) is true. Nothing is visible because the HUD is hidden.
- [ ] Hack memory_wipe (Bottle of Forgetting) on a ganger fighting V: it drops combat and returns to idle.
- [ ] Hack cyberpsychosis (Creeper Brain) on a ganger in a group: it attacks its squad, then kills itself.
- [ ] Hack device (Redstone Lever): on a vending machine, a camera and a traffic light as in G-4/G-5; on a TV or radio it glitches or distracts; on a powered-off device, hackresult ok:false with reason 'not hackable'. The camera must never take over the view.
- [ ] Chain Hack perk: with the perk, a ganger within 8 m of the target gets the same hack (two VFX). A companion, or a quest NPC in a scene, gives 'not hackable'; a dead target gives 'target gone'.
- [ ] Buff invisibility: see G-7. When the potion ends, V is visible to NPCs again (SetInvisible(false)) and the camo status effect is gone.
- [ ] Buff speed: read GetStatValue(V, gamedataStatType.MaxSpeed) before and during Speed II -> x1.4, and V crosses a measured street visibly faster. The value is back to normal at potion end and on F9 off.
- [ ] Buff slowness: Slowness I -> MaxSpeed x0.85 and V is slower. Slowness X stays at x0.3.
- [ ] Buff jump_boost: with Jump Boost II, V clears an obstacle about twice the normal jump height. Normal again afterwards.
- [ ] Buff nausea: eating a pufferfish puts the drunk wobble on V (StatusEffectSystem.ObjectHasStatusEffect(Game.GetPlayer(), TweakDBID.new('BaseStatusEffect.Drunk')) is true). It is removed when Minecraft removes the effect. Check whether the wobble is visible behind the Minecraft frame.
- [ ] Buff blindness: splash blindness gives the blind glitch screen on V (AIQuickHackStatusEffect.HackBlind). It is gone when the Minecraft effect ends, even if that is later than its own 8 s.
- [ ] Buff darkness: same as blindness (/effect give @p darkness).
- [ ] Unmapped effects (poison, wither): the log shows 'buffs: effect poison has no Cyberpunk mapping, ignored' once. V gets no effect, and Steve takes only Minecraft's own poison damage.
- [ ] Perk double_jump: with the perk bought and Cyberblock Active, V double-jumps, and GetStatValue(V, HasDoubleJump) >= 1. After F9 off, V's own value is back.
- [ ] Scan: hack key held on a ganger -> scaninfo ok:true with name, faction and threat; on a vending machine -> device:true; on the sky -> ok:false.
- [ ] Kills: killing a hostile ganger with a sword logs 'killed human <id> (hostile)' once; destroying a car with TNT logs 'killed vehicle <id>'; a kill V makes with Cyberblock Off logs nothing.
