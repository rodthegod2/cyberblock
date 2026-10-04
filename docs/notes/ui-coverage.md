# UI coverage: what is Minecraft, what passes through, what is never touched

Task Q-U1 of the multiplayer megaplan (Phase Q, item Q4). One table per kind of Cyberpunk UI, each row with its status
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
| Night City pause (Esc, H) | Minecraft (`NightCityPauseScreen`) | keep; gets the "Cyberblock..." button (Q-U2); "Night City Menu..." stays as the escape hatch (Q-U3) | Q |
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
| Save and load lists (`saves`) | pass-through, reached through "Night City Menu..." | convert to a Minecraft list | 9 |
| Loading screens (`loading`) | Minecraft cover over Cyberpunk's loading screen | convert to the Minecraft loading screen look | Q (Q-U2) |
| Fast travel | not offered | Holo-Table spawn and travel; the Atlas stays map-only | 7 (Holo-Table) |
| Stash (`stash`) | pass-through | stays pass-through, unless a later phase finds it cheap | none planned |
| Wardrobe (`wardrobe`) | pass-through | stays pass-through | none planned |
| Crafting benches (`crafting`) | pass-through | stays pass-through | none planned |
| Arcade machines (`arcade`) | pass-through | stays pass-through | none planned |
| Braindance editor (`braindance`) | pass-through (the HUD layer is shown for its timeline) | stays pass-through | none planned |
| Tutorial and shard popups (`tutorial`) | pass-through (game-notifications layer shown while a popup blocks) | stays pass-through | none planned |
| Settings (`settings`) | pass-through, reached through "Night City Menu..." | stays pass-through; Cyberblock's own settings live in the "Cyberblock..." screen | none planned (9 finishes the Cyberblock settings screen) |
| Any other Cyberpunk menu (`other`) | pass-through | stays pass-through | none planned |

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
   the Minecraft options usable, so the player is never stranded (Q-U3, tested).
2. **Pass-through is the default.** A Cyberpunk screen is converted only when a phase of this plan needs the Minecraft
   version anyway (shops, ripperdoc, Breach, Downed, saves, the Holo-Table).
3. **No Cyberpunk layer other than the three hidden ones is changed**, and the three are restored on Off, link loss,
   session end and before saves.
