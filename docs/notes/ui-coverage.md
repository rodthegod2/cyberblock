# UI coverage: what is Minecraft, what passes through, what is never touched

Task Q-U1 of the multiplayer megaplan (Phase Q, item Q4), updated by task 9-U1 (Phase 9: the save and load lists, the
remaining pass-through decisions, the escape hatch). One table per kind of Cyberpunk UI, each row with its status
today (1.0.0 plus the Phase Q build), the decision, and the phase that converts it. Sources: the brief's section 5 and
the retest 3 polish plan (section 1.11 and its per-menu table, `Ui.reds`, `Restyle.reds`, `UiEvents.java`).

**Statuses**

- **Minecraft**: drawn by Minecraft from data the DLL streams; Cyberpunk's own widget is hidden while Cyberblock is On.
- **Pass-through**: Cyberpunk's own widgets and font, tinted in Minecraft colours (`CyberblockRestyle`), framed by
  Minecraft (`MenuFrameHud` title bar and bevel border), with the Minecraft world hidden (`cb_menu`).
- **Never touched**: left exactly as Cyberpunk draws it.

While Cyberblock is On, only three ink layers are hidden (opacity 0, controllers still running): `inkHUDLayer`,
`inkGameNotificationsLayer` and `inkWaitingSignLayer`. Two layers are only tinted, never hidden: `inkMenuLayer` and
`inkLoadingLayer`. Everything else is untouched.

## 1. HUD elements

| Element | Status today | Decision | Converting phase |
|---|---|---|---|
| Radar / minimap (80 x 80, top left) | Minecraft (`RadarHud`) | keep | none |
| Eddies, wanted-level stars, time, district | Minecraft (`StatsHud`) | keep; Phase 8 adds the server Heat meter next to it | 8 (adds Heat) |
| Speed in vehicles | Minecraft (`StatsHud`, `VehicleHud`) | keep | none |
| Vehicle HUD (vanilla hotbar, health and food hidden while driving) | Minecraft (`VehicleHud`) | keep | none |
| Tracked quest panel | Minecraft (`QuestHud`) | keep | none |
| Waypoint to the tracked objective | Minecraft (vanilla Locator Bar, `QuestHud` arrow as fallback) | keep | none |
| Interaction prompts | Minecraft (`PromptHud`) | keep | none |
| Dialogue choices | Minecraft (`ChoicesHud`) | keep | none |
| Subtitles | Minecraft (`SubtitleHud`) | keep | none |
| Third-person crosshair | Minecraft (`CrosshairHud`) | Phase Q (QV) projects it onto the aim point | Q (QV) |
| Toasts: notifications, phone calls (T answers, hold T rejects), saves | Minecraft (`CyberToast`) | keep | none |
| Cyberware, RAM and Sandevistan HUDs | Minecraft (`CyberwareHud`, `RamHud`, `SandevistanFx`) | keep | none |

## 2. Screens that are already Minecraft

| Screen (key) | Status today | Decision | Converting phase |
|---|---|---|---|
| Night City pause (Esc, H) | Minecraft (`NightCityPauseScreen`) | keep; gets the "Cyberblock..." button (Q-U2); "Night City Menu..." stays as the escape hatch (Q-U3); "Load Last Save" became "Saves..." (9-U1) | Q, 9 |
| Night City saves (from "Saves...") | Minecraft (`SavesScreen`, `Saves.reds`) | new in Phase 9, see section 3a | 9 (9-U1) |
| Atlas (M) | Minecraft (`AtlasScreen`), map only: fast travel is not offered in 1.0.0 | keep as a map; fast travel moves to the Holo-Table | 7 (Holo-Table) |
| Journal (J), with Track | Minecraft (`JournalScreen`) | keep | none |
| Phone and messages | Minecraft (`PhoneScreen`) | keep | none |
| Inventory (I) | Minecraft (vanilla inventory) | keep | none |
| Skill tree (P, K) | Minecraft (`SkillTreeScreen`) | keep | none |
| Cyberware (U) | Minecraft (`CyberwareScreen`) | keep | none |
| Loading cover ("Loading Night City...") | Minecraft (`MenuFrameHud`), plain menu background | becomes a Minecraft loading screen look: dirt-style background, animated dots, a tip line | Q (Q-U2) |

## 3. Screens that pass through (Cyberpunk's widgets and font, Minecraft colours and frame)

The font stays Cyberpunk's: Minecraft's font cannot be copied into a Cyberpunk archive, and cooking one needs WolvenKit,
which is not in the toolchain.

| Screen (`screen.pass` name) | Status today | Decision | Converting phase |
|---|---|---|---|
| Vendors and shops (`vendor`) | pass-through | convert to a Minecraft shop screen on the NPC figure (right-click), paid in Minecraft eddies; the host's own Cyberpunk vendor stays a pass-through screen paid in real eddies | 7 (NPC interaction layer) |
| Ripperdoc, including the prologue install (`ripperdoc`) | pass-through | convert to a Minecraft ripperdoc screen with a surgery minigame; the prologue install stays Cyberpunk's | 7 (NPC interaction layer) |
| Breach protocol (`breach`) | pass-through | replaced by Cyberblock's own Breach Protocol screen (hex grid, buffer, daemons, timer) | 6 |
| Death screen (`death`) | pass-through | replaced by Downed and revive | 8 |
| Save and load lists (`saves`) | Minecraft "Night City saves" screen (Phase 9); Cyberpunk's own lists stay reachable through "Night City Menu..." as a pass-through | converted; the pass-through is the fallback when the list does not come (section 3a) | 9 (9-U1) |
| Loading screens (`loading`) | Minecraft cover over Cyberpunk's loading screen | convert to the Minecraft loading screen look | Q (Q-U2) |
| Fast travel | not offered | Holo-Table spawn and travel; the Atlas stays map-only | 7 (Holo-Table) |
| Stash (`stash`) | pass-through | stays pass-through (Phase 9 decision): not cheap. A Minecraft stash needs both item lists (V's inventory and the stash) streamed with icons and a transfer action per item and quantity; none of that exists on the wire, and the DLL and protocol are outside the UI package | none |
| Wardrobe (`wardrobe`) | pass-through | stays pass-through (Phase 9 decision): not cheap. Outfits are Cyberpunk clothing appearances previewed on V's 3D model; Minecraft has nothing to show them on (Steve is the player's own skin) and the preview is the point of the menu | none |
| Crafting benches (`crafting`) | pass-through (the crafting key itself opens the Minecraft inventory, Menus.reds) | stays pass-through (Phase 9 decision): not cheap. Recipes, components, quality tiers and upgrades are a full CraftingSystem screen with its own data stream and actions; Minecraft crafting already covers Steve's items | none |
| Arcade machines (`arcade`) | pass-through | stays pass-through (Phase 9 decision): the arcade minigames are their own games drawn and driven by Cyberpunk; a Minecraft copy would be a new game, not a conversion | none |
| Braindance editor (`braindance`) | pass-through (the HUD layer is shown for its timeline) | stays pass-through (Phase 9 decision): the editor's layers and scrubbing drive the 3D scene itself; it only works as Cyberpunk's own tool | none |
| Tutorial and shard popups (`tutorial`) | pass-through (game-notifications layer shown while a popup blocks); a shard read also reaches Minecraft as a `shard` toast | stays pass-through (Phase 9 decision): not cheap. A shard's text is longer than the wire's 256-character notification text and needs its own message and a dismiss action; tutorials must stay readable exactly as the game shows them | none |
| Settings (`settings`) | pass-through, reached through "Night City Menu..." | stays pass-through (Phase 9 decision): Cyberpunk's settings are hundreds of engine options with live preview; Cyberblock's own settings live in the "Cyberblock..." screen (9-S1) | none |
| Any other Cyberpunk menu (`other`) | pass-through | stays pass-through | none planned |

## 3a. The save and load lists (Phase 9, task 9-U1)

**The API check.** The 2.31 save-list calls compile against the installed game's scripts (`compile_check`), so the
lists are converted: `inkISystemRequestsHandler.RequestSavesForLoad`, answered through `RegisterToCallback` by
`OnSavesForLoadReady(saves: array<String>)` and one `OnSaveMetadataReady(info: ref<SaveMetadataInfo>)` per save (the
two callbacks `LoadGameMenuGameController` declares; a wrapper with the same signatures compiled), `LoadSavedGame(saveId:
Int32)`, `ManualSave(saveName: String)`, and `SaveMetadataInfo` with `saveIndex`, `locationName`, `trackedQuest`,
`playTime`, `level`, `timestamp`, `saveType`, `lifePath`, `isValid`. The 2.31 handler has no call that asks for one
save's metadata, so the metadata is expected to follow the list; whatever has not come 1.5 s after the list is left out
(the save is listed by its name, log `saves: metadata for <k> of <n>`).

**The screen.** Esc, then "Saves...": the saves newest first (location, type, level, play time, date, tracked job),
Load (with a confirmation naming the place, the type and the date, or the save's name when the date is unknown, so two
saves at one place can be told apart), New Save (a manual save), Quick Save, Load Last Save, Night City Menu... and Back.
The selection is kept by the save's name, so the list asked for again after a save (the new one sorts to the top) does
not move it onto another save; a save that is gone leaves nothing selected. A save that Cyberpunk marks invalid, whose
metadata did not come (its validity is unknown), or whose name is longer than the 64 bytes a `uiact` can carry, is
listed but not loadable from Minecraft (Night City Menu... still offers it, with Cyberpunk's own check). At most 127 saves are listed (the reply's group cap). The wire carries
nothing new: the list is `uiact journalreq arg:"saves"` with a `reply` of kind `journal` (the Saves.reds header has the
exact shape), a load is `uiact loadlast arg:<save name> id:<index>`, a new save `uiact save arg:"new"`.

**Only checkable in game, so checked at run time.** Whether the handler answers a script object outside Cyberpunk's own
load menu, and the unit of the timestamp. When no list comes within 3 s, the reply says "unavailable", the screen says
"Night City did not list its saves" and points to Night City Menu... (Cyberpunk's own lists, the `saves` pass-through,
exactly as before Phase 9). The game log says which path is active, once per change:
`saves: Minecraft save list active (RequestSavesForLoad answered, <n> saves)` or `saves: pass-through
(RequestSavesForLoad gave no answer in 3 s; Night City Menu... has the lists)`. The timestamp is read as Unix seconds,
milliseconds or a Windows file time, whichever is a date in 2000-2200; otherwise the row shows the save's name instead.
A new save passes the name `"ManualSave"` (the prefix the 2.31 scripts read in manual save names); that the new entry
appears is part of the in-game check, and Quick Save stays as the proven way to save.

**A load is by name.** Minecraft sends the save's name and index; Saves.reds asks for the list again and loads the save
with exactly that name (the index only while it still names that save), so a save made in between cannot shift the load
onto another one. It then waits up to 1.5 s for that save's metadata and loads only when `isValid` is true, the
check Cyberpunk's own load menu makes, so a corrupt save, one from a newer version or one needing a missing DLC never
reaches `LoadSavedGame`; otherwise a warning toast and `saves: load "<name>" dropped (not valid)` (or `(not valid: no
metadata in 1.5 s)`). Night City's pause ends first, as with Load Last Save.

**For the integrator (9U owns neither `shared/protocol.md` nor `tools/fakehost.py`).** Text for protocol.md, a
"Phase 9" table after the Phase 7 one, in the same form:

| `t` | fields | when |
|---|---|---|
| `uiact` | **extends re-test 3 and Phase 7:** `journalreq` with `arg:"saves"` asks for Night City's save list, answered by a `reply` `kind:"journal"` (shape below). `loadlast` with an `arg` (the save's name, as line 1 of its group) and `id` (its index, the group's `hash`) loads that save: the host asks for the list again, finds the save by its exact name (the index only while it still names that save) and loads it only when Cyberpunk calls it valid; without an `arg` it is still "Load Last Save". `save` with `arg:"new"` makes a new manual save; without an `arg` it is still the quick save | the Minecraft "Night City saves" screen (9-U1); LOCAL |

- **The saves `reply`.** Group 0 is the status: `hash:0`, `title` `"ok"` or `"unavailable: <why>"`, `done` = not ok,
  no lines; an unavailable list has no further groups. Then one group per save, in Cyberpunk's order, at most 127:
  `hash` = the save's index in Cyberpunk's list, `title` = its location (else its name), `done` = Cyberpunk marks it
  invalid; lines: `hash:1` the save's name (the load key), `hash:2` `"<type>|<level>|<seconds played>|<timestamp>|<life
  path>"` (only when its metadata came; type is the `inkSaveType` name), `hash:3` the tracked job (only when it has
  one). Minecraft offers a load only for a save whose line 2 came, whose `done` is false and whose name fits `arg`.

Follow-up for `tools/fakehost.py --ui`: `ui_replies` answers every `journalreq` with the sample journal, which
SavesScreen reads as "not a save list". Answer `arg:"saves"` first, so SavesScreen can be rendered and screenshotted:

```python
if m.get("a") == "journalreq" and m.get("arg") == "saves":
    return [{"t": "reply", "kind": "journal", "id": rid, "pins": [], "groups": [
        {"hash": 0, "title": "ok", "kind": "other", "tracked": False, "done": False, "lines": []},
        {"hash": 0, "title": "Watson", "kind": "other", "tracked": False, "done": False, "lines": [
            {"hash": 1, "text": "ManualSave-2", "state": 0},
            {"hash": 2, "text": "ManualSave|12|43500|1759000000|StreetKid", "state": 0},
            {"hash": 3, "text": "The Pickup", "state": 0}]},
        {"hash": 1, "title": "Watson", "kind": "other", "tracked": False, "done": False, "lines": [
            {"hash": 1, "text": "AutoSave-1", "state": 0},
            {"hash": 2, "text": "AutoSave|11|40200|1758900000|StreetKid", "state": 0}]}]}]
```

(Group hash 0 appears twice on purpose: the status, then the save at index 0; SavesModel reads group 0 of the list as
the status by position.)

## 4. World-space layers (never touched)

These are drawn inside the 3D scene or by the game's system, not on the HUD. Cyberblock leaves all five exactly as they
are. The Minecraft compositor draws Minecraft's world with Night City depth, so Cyberpunk's own world-space drawing
stays visible and correct where Minecraft does not cover it.

| Layer | What it holds | Status today | Decision and reason |
|---|---|---|---|
| `inkWorldLayer` | NPC markers, mappins and name plates in the world | never touched | leave. The tracked objective already has a Minecraft waypoint, and hiding the layer would also hide world markers that Minecraft has no replacement for. The figures of Phase 3 and later carry their own Minecraft name tags. |
| `inkAdvertisementsLayer` | billboards and ad panels | never touched | leave. They are part of the Night City look the player came for; a Minecraft replacement would add nothing. |
| `inkStreetSignsLayer` | street signs and district name signs | never touched | leave. They help orientation and cost nothing; the Atlas and the Holo-Table already name districts. |
| `inkSystemNotificationsLayer` | system pop-ups: controller, connection, save-data warnings | never touched | leave. These are platform messages that Cyberblock must never hide or fake; the player has to be able to read them. |
| `inkPhotoModeLayer` | photo mode | never touched | leave. Photo mode is Cyberpunk's own full-screen tool, and the view gate keeps third person off while it is open (Q-S3). |

## 5. Rules that hold for every row

1. **The escape hatch always works.** Esc, then "Night City Menu..." opens Cyberpunk's own menu through `uiact cpmenu`,
   in every state of the pause screen. Without a link the screen says "Night City is not connected" and keeps Back and
   the Minecraft options usable, so the player is never stranded (Q-U3, tested). Phase 9 (9-U1, tested): the saves
   screen has the same button in every state (no link, waiting, no list, empty, a list) and Back never needs the link;
   and when Cyberpunk swallowed a menu key but Minecraft cannot open its screen (no world loaded, or Minecraft already
   shows a screen the DLL did not know of yet), Minecraft opens Cyberpunk's own menu instead (`uiact cpmenu`, log
   `ui: screen <name> not opened (<why>): Night City opens its own menu`), so the key is never lost.
2. **Pass-through is the default.** A Cyberpunk screen is converted only when a phase of this plan needs the Minecraft
   version anyway (shops, ripperdoc, Breach, Downed, saves, the Holo-Table). Phase 9 reviewed the rest (stash,
   wardrobe, crafting benches, arcade, braindance editor, tutorial and shard popups, settings): none is cheap, each
   decision and its reason is in its row of section 3.
3. **No Cyberpunk layer other than the three hidden ones is changed**, and the three are restored on Off, link loss,
   session end and before saves.
