# Cyberblock Re-test 3: Distinct Quickhacks, Minecraft UI Everywhere, Vehicles, Cyberware Polish and Release 1.0.0

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** do everything the user asked for after the third in-game re-test. The source is `out/user-issues.md`,
section "Re-test 3 feedback" (build e771fba + de06c0a), items F1 to F13. All of them must be done:
- **F1** every quickhack is unique and cool in **both** games (Minecraft visuals and behaviour that match the chip, and
  a distinct Cyberpunk quickhack); find and fix why they all feel the same;
- **F2** a Minecraft-style UI for the rest of the game (radar/minimap, tracked quest text and a waypoint, eddies,
  wanted level, calls, messages, notifications, everything needed to play quests), plus a third-person view;
- **F3** Steve stays visible in vehicles: Minecraft hands in first person, the whole seated Steve in third-person cameras;
- **F4** the elytra glide must steer with the camera like vanilla (today it stays locked to the start direction);
- **F5** ground alignment is perfect: **keep it** (no change to the alignment math; a regression check only);
- **F6** original 3D models for the arm cyberware (Mantis Blades, Gorilla Arms, Monowire, Projectile Launch System) in
  first person and on Steve in third person;
- **F7** Minecraft hits fling NPCs: vanilla-like knockback, ragdoll only on strong hits;
- **F8** separate binds: Y = Operating System implant, L = Arms implant, O = Optical Camo; rebindable, no clashes;
- **F9** two Operating System slots: Sandevistan (or Berserk) together with the Cyberdeck;
- **F10** a Sandevistan screen effect (ReShade: chromatic split, tint, ghost trails, edge glow) plus a Minecraft flourish;
- **F11** whatever else a full Minecraft x Cyberpunk experience still lacks, and a publish-ready project: README with a
  normal-launcher install, release zip with an installer, GitHub Actions CI, CHANGELOG, version 1.0.0, LICENSE/NOTICE
  credits, legal check, screenshot placeholders. **No remote repository, no push**: the user does that step;
- **F12** the old Cyberpunk UI must **never** appear while Cyberblock is on. Every UI element is Minecraftified: HUD,
  menus (pause, map, inventory, journal, phone, shops, crafting, perks), notifications, loading and save prompts. A
  Cyberpunk menu that cannot be replaced is restyled to a Minecraft theme instead of keeping its own look;
- **F13** interaction prompts (F to talk, open, pick up, doors) and dialogue choice lists are Minecraft-styled boxes and
  lists, still driven by Cyberpunk's input (F, E/Q/1-4/wheel in choices).

The user's relayed request stresses F12 and F13 once more: "the old cyberpunk ui shouldn't appear", "every UI element
should be minecraftified", "interact prompts also fall into this minecraft theme". Section 1.11 is the binding rule set
for that.

**Root causes (from the investigations; evidence in Cyberblock.log and latest.log of 2026-10-03 17:29-17:55):**
- **F1 (main cause, Minecraft):** `HackWheel.current()` prefers the remembered wheel pick over the chip in the main hand
  and falls back to the first chip (SHORT_CIRCUIT). Cyberblock.log 17:30-17:42 has 15 `script: hack` lines: 13
  `short_circuit` and 2 `cyberpsychosis`, although the F9 kit holds one of each chip.
- **F1 (Minecraft feedback):** `Hacking.onResult` only plays BEACON_POWER_SELECT; no chip has its own visuals.
  `Pending` forgets the hack and the target, and proxies are invisible, so nothing can be drawn on them.
- **F1 (Cyberpunk):** the mapping is correct (nine distinct ObjectAction records, applied by ProcessRPGAction), but
  most targets are crowd pedestrians: vanilla never quickhacks crowd records, and the squad- and AI-driven effects
  (Madness, Ping, Memory Wipe, Blind, Locomotion Malfunction) do nothing visible on them. Contagion spread needs V's
  spread stats (V has none). Ping is built as a plain PuppetAction instead of PingSquad. The upload lands 1-3 s after
  Minecraft already reacted. `Upload` omits the GameplayRoleComponent (no queue mappin).
- **F2/F12/F13:** `CyberblockPlayerState.Update` brings back the **whole** inkHUDLayer whenever `ChoicesShown()` is
  true (every "F Talk/Open/Pick up" prompt and every dialogue) and keeps it up for 2 s (`hudDelay`). The Vehicle state
  shows Cyberpunk's HUD by design. `inkGameNotificationsLayer` (quest updates, items, money, SMS, wanted, level-up) and
  `inkWaitingSignLayer` are never hidden. No Cyberpunk menu is intercepted (Esc, M, J, I, P, H open vanilla menus), and
  the compositor keeps drawing Minecraft's world and HUD on top of them. The wire carries no quest, money, wanted, pin,
  prompt, choice, subtitle, call or notification data. `ToastManagerMixin` cancels every Toast, and `Bridge.listeners`
  allows one listener per type. Third person was never wired (`make_cam` hard-codes `fp:true`).
- **F3:** `EntityRenderDispatcherMixin` cancels the local player's render in vehicles on purpose; the DLL sends
  `hud hidden:true` in vehicles and `ClientInput.applyHud` hides the GUI, which in 26.3 also removes the first-person hand
  (`renderItemInHand` returns on `isHudHidden`); `fp` is always true and `pl` is V's root, not the seat; V's meshes come
  back in the Vehicle state.
- **F4:** the per-frame drive teleports V every frame with the yaw read **before** that frame's mouse delta, so the
  mouse-X turn is undone every frame; the cam yaw that steers Steve stays constant. latest.log 17:36:40-45: a 108 m glide
  on an exact diagonal.
- **F6:** nothing renders installed implants; their models are flat 16x16 sprites; there is no player render layer.
- **F7:** every melee hit sends at least 6 m/s, and Cyberpunk ragdolls any person at >= 4 m/s with an impulse of
  speed x 75 over a 10 km influence radius (vanilla uses dir x 10 over 5 m). Sprint, gauntlet and Gorilla Arms multiply
  it; projectiles pass 50 % of the arrow speed (about 30 m/s).
- **F8:** one Minecraft mapping (Y) fires the selected implant; the DLL knows four binds only. L and O are vanilla
  Advancements and Friends keys in 26.3.
- **F9:** `CyberSlot.OPERATING_SYSTEM` has capacity 1 and Cyberdeck, Sandevistan and Berserk all map to it.
- **F10:** Cyberpunk only sets a time dilation; Cyberblock.fx has no slow-mo uniform or history texture; a Minecraft
  post effect would only distort the overlay layer while hosted.
- **F11:** launcher players never reach the Night City world (quick-play arg only from `tools/play.py`); `configure()`
  rewrites options.txt (needs a dedicated game directory); no release artifact, no CI, version 0.1.0 hard-coded in 7
  places, credits without license texts, no disclaimer, no CHANGELOG. **Legal audit: clean** (all 3457 objects of every
  ref scanned; no game assets, fonts, decompiled code or secrets; all 39 textures regenerate byte-identical from
  `art/gen_item_art.py`).

**Architecture:** the same three parts as layers 1 to 6. The principle added by this plan (F12/F13): **Cyberpunk keeps
the game logic and its own input; Minecraft draws every piece of UI.** While Cyberblock is On (Active or Vehicle),
Cyberpunk's 2D ink layers are invisible (opacity 0, still alive so prompts and choices keep their input). Cyberpunk
streams the UI data over the wire; Minecraft renders it with its own font, sprites and colours from the player's own
Minecraft install (nothing is copied). Cyberpunk menus are intercepted and replaced by Minecraft screens; the few that
cannot be replaced are passed through restyled in Minecraft colours with a Minecraft frame, and the Minecraft world is not
composited over them.

**Sources:**
- Spec `docs/superpowers/specs/2026-10-02-minecraft-cyberpunk-passthrough-design.md` (its line 17, "entering a vehicle
  restores the Cyberpunk HUD", is reversed by this plan).
- Previous plan `docs/superpowers/plans/2026-10-03-retest2-and-cyberware.md` ("R2 plan"; its sections 1.1 alignment,
  1.7 catalogue and 1.8 keys stay valid except where section 1 below changes them).
- Protocol `shared/protocol.md`; audit `docs/notes/cp-mechanics-audit.md`; flight notes `docs/notes/flight-spike.md`.
- Evidence: `D:\SteamLibrary\steamapps\common\Cyberpunk 2077\red4ext\logs\Cyberblock.log` (2026-10-03 session),
  `D:\Cyberblock\mc\run\logs\latest.log`.
- References outside the repo (read only, never copied): the 2.31 scripts in `%TEMP%\cbs2\cp231`, TweakDB check
  `%TEMP%\cbs2\tools\tdb_has.py`, `r6\config\inputUserMappings.xml`, Codeware's `Codeware.Global.reds`.

---

## 0. Ground rules (all packages)

- **Worktrees:**
  - Work only in your own files (table below), on your own branch in your own worktree: `r3/<package>`, for example
    `git worktree add D:\Cyberblock-wt\r3-D -b r3/D main`.
  - **Never build or run Gradle inside `D:\Cyberblock\mc`**: the user's Minecraft may be running from it.
- **Commits:**
  - Commit after every task, with explicit paths (`git add <paths>`, never `git add -A`).
  - The message ends with a blank line and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
  - Never push. Never create a remote. Never rewrite main. Never tag.
- **Cyberpunk is off-limits:**
  - Never launch Cyberpunk. The Cyberpunk folder is read-only, except for the integrator's install command.
  - No system installs and no tool downloads (build-dependency downloads through Gradle/CMake/uv are fine).
- **No game assets or decompiled source in the repo** (F11 legal state must stay clean):
  - Never copy Minecraft or Cyberpunk assets, fonts, shaders or decompiled source into the repo.
  - Unpack sources only outside the repo (`%TEMP%` or your scratchpad) and delete them when done. Run `git status`
    before every commit; a stray `assets/minecraft/...` or `net/...` path is a stop-and-clean event.
  - New art is procedural (extend `art/gen_item_art.py`) or hand-authored JSON (models, advancements). The Minecraft
    look comes from **runtime** use of the player's own vanilla sprites and font (`Identifier.withDefaultNamespace(...)`
    sprite ids, `Font`), never from copies.
  - Comments cite APIs as "2.31 script API" (NativeDB, redscript), not as copied code.
- **Nobody is awake.** Never wait for input. If you are blocked: record it in your report (`blocked`), keep the code
  compiling (a logging no-op behind the contract signature) and move on.
- **APIs: never guess a name.**
  - Cyberpunk: NativeDB (https://nativedb.red4ext.com), the Codeware sources (`...\red4ext\plugins\Codeware\Scripts`),
    the 2.31 scripts the compile checker uses, TweakDB (`tdb_has.py`). Run `uv run python cp/scripts/compile_check.py`.
  - Ink layer names come from the 2.31 scripts / Codeware.Global.reds (`inkHUDLayer`, `inkGameNotificationsLayer`,
    `inkMenuLayer`, `inkLoadingLayer`, `inkWaitingSignLayer`, `inkSystemNotificationsLayer`, `inkWorldLayer`, ...).
  - RED4ext: `third_party/RED4ext.SDK`. ReShade: `third_party/reshade` (add-on API and the vendored FX compiler).
  - Minecraft 26.3: the Loom sources jar / `javap` on the jars in your worktree's `mc/.gradle/loom-cache` (unpack
    outside the repo only).
  - Cite the source in the file header, as the existing files do.
  - Anything only checkable in game goes to the report's `needsInGame` list, with a concrete check (an in-game action
    or CET console line, plus the expected log line or result).
- **Building against a contract another package owns:** create the other package's file (or the members you need) in
  your worktree with the **exact** section 1 content and **never commit it**. Name new stand-in files
  `zz_stub_*` where a name is free (`cp/plugin/src/zz_stub_ui.cpp`, `cp/scripts/Cyberblock/zz_stub_seat.reds`). Java
  stand-ins edit the real path (`Bridge.java`, `StatusReporter.java`, ...) and stay uncommitted.
- **Natives rule:** a native is **declared** in the `.reds` file of the package whose scripts call it (S: `Natives.reds`;
  U: `UiNatives.reds`), and **implemented** where section 1.2 says (D: `natives.cpp`; U: `ui_game.cpp`). Every native is
  declared exactly once.
- **Lang rule:** A writes every key of section 1.12 in `en_us.json`. Code always uses
  `Component.translatableWithFallback(key, "<the section 1.12 English>")` for **new** keys, so a missing key never
  shows raw. A key you need that section 1.12 lacks: use translatableWithFallback and list it in
  `contractDeviations` (the integrator adds it).
- **Minecraft test clients:**
  - Only your package's port and frame name (table), passed as
    `gradlew runClient -Pcyberblock.port=<port> "-Pcyberblock.frame=<frame>"`. Never 25600 or `Local\CyberblockFrame`.
  - Launch only when system commit is under 60 GB (`Get-CimInstance Win32_OperatingSystem`: `TotalVirtualMemorySize -
    FreeVirtualMemory`). Always close what you launched; never kill a process you did not start.
  - UI and render work (W, C, M) takes screenshots of the dev client (with `tools/fakehost.py` driving it where useful)
    and lists them in the report.
- **Test suites:** `uv run pytest -q`; in `mc/` of your worktree `gradlew.bat test build` (`JAVA_HOME` =
  `%USERPROFILE%\AppData\Local\Cyberblock\jdk-25.0.4.1+1`); `"C:\Program Files\CMake\bin\cmake.exe" --build
  cp/plugin/build --config Release` then `cp\plugin\build\Release\cb_tests.exe` (configure `cp/plugin/build` in your
  worktree first); `uv run python cp/scripts/compile_check.py`.
- **Report format** (each package, at the end):
  `{package, commits[], done[], notDone[], blocked[], needsInGame[], contractDeviations[], screenshots[]}`.
  A contract deviation is allowed only when the contract is impossible as written; keep the signature and explain.

| Pkg | Scope | Owns (exclusive) | Model | Test port / frame |
|---|---|---|---|---|
| **D** DLL core and shader | F1 natives, F2 third-person view, F3 seat/fp, F4 owned yaw, F8 six binds, F10 screen FX, F11 version, F12 compositor gate and phone key | `cp/plugin/**` **except** U's four files; `cp/shaders/**`; `tools/cbmath.py`, `tools/gen_vectors.py`, `shared/vectors.json`, `tests/test_cbmath.py` | Opus | 25734 (link_smoke only) |
| **U** Cyberpunk UI bridge | F12/F13 Cyberpunk side end to end: ink layers, HUD data, notifications, menu interception, restyle, UI natives and pump; F3 V-body hiding in vehicles | `cp/plugin/src/ui.hpp`, `cp/plugin/src/ui.cpp`, `cp/plugin/src/ui_game.cpp`, `cp/plugin/tests/test_ui.cpp` (all new); `cp/scripts/Cyberblock/Cyberblock.reds`, `PlayerState.reds`, and new `Ui.reds`, `Hud.reds`, `Notifications.reds`, `Menus.reds`, `Restyle.reds`, `UiNatives.reds` | Opus | — |
| **S** Cyberpunk gameplay scripts | F1 distinct hacks, F3 seat data, F2 view gate and aim eye, F7 knockback tiers, F10 slow-mo hook | `cp/scripts/**` **except** U's files (incl. `Natives.reds`, `Hacks.reds`, new `HackFx.reds`, `Effects.reds`, `Tether.reds`, `Probes.reds`, `Cyberware.reds`, new `Seat.reds`, new `View.reds`, `compile_check.py`); `tests/test_reds_ids.py` | Opus | — |
| **M** Minecraft core | F3 Steve in vehicles, F7 Minecraft impulse, F2 perspective toggle, F11 launcher install/version, shared Minecraft contracts (Bridge, toasts, status, fabric.mod.json) | `mc/**` **except** the C, W and A paths (incl. `Bridge.java`, `ProxyEntity.java`, `ProxyDamage.java`, `BlockEvents.java`, `client/*.java`, `client/mixin/**`, `cyberblock.mixins.json`, `cyberblock.client.mixins.json`, `fabric.mod.json`, `build.gradle`, `gradle.properties`, `mc/src/test/java/dev/cyberblock/*.java`, `.../client/HostStateTest.java`) | Opus | 25731 / `Local\CyberblockFrameR3M` |
| **C** Minecraft cyber | F1 wheel and Minecraft hack effects, F6 arm models in game, F8 keys, F9 OS slots, F10 Minecraft flourish, F11 arms abilities, Steve's cyber look, camo shimmer, advancement awards | `mc/src/main/java/dev/cyberblock/cyber/**`, `mc/src/client/java/dev/cyberblock/client/cyber/**`, `mc/src/test/java/dev/cyberblock/cyber/**` **except** `CyberAssetsTest.java`; `cyberblock.cyber.mixins.json`, `cyberblock.cyber.client.mixins.json`; `mc/src/main/resources/assets/cyberblock/post_effect/**` and `.../assets/cyberblock/shaders/**` | Opus | 25732 / `Local\CyberblockFrameR3C` |
| **W** Minecraft UI | F2/F12/F13 Minecraft side: HUD (radar, stats, quest, waypoint, prompt, choices, subtitles), toasts, Night City screens, pass-through frame | `mc/src/client/java/dev/cyberblock/client/ui/**`, `mc/src/test/java/dev/cyberblock/client/ui/**`, `mc/src/client/resources/cyberblock.ui.client.mixins.json` (all new) | Opus | 25733 / `Local\CyberblockFrameR3W` |
| **A** art, assets, lang, data | F6 models/textures, F2 UI sprites, F11 cyber-look textures, advancements, icon; every lang string | `art/**`, `mc/src/main/resources/assets/**` **except** C's `post_effect/**` and `shaders/**`; `mc/src/main/resources/data/cyberblock/recipe/**`, `.../data/cyberblock/advancement/**`; `mc/src/test/java/dev/cyberblock/cyber/CyberAssetsTest.java` | Sonnet | — |
| **P** protocol, docs and release | wire docs, fakehost, player docs, test plan, notes, spec, README, CHANGELOG, credits, packaging, installer, CI | `shared/protocol.md`, `tools/fakehost.py`, `tests/test_protocol_md.py`, `tests/test_fakehost.py`, `docs/**` **except** `docs/superpowers/plans/**`, `README.md`, `CHANGELOG.md`, `NOTICE.md`, `THIRD-PARTY-NOTICES.md`, `LICENSE`, `tools/install.py`, `tools/package.py`, `release/**`, `.github/**`, `tests/test_install.py`, `tests/test_package.py`, `tests/test_install_ps1.py`, `tests/test_version.py`, `pyproject.toml`, `uv.lock`, `.gitignore` | Sonnet | — |

Every package may start at once: section 1 is the only coupling. Each package does its tasks in the listed order (also
the priority order). Nobody edits `out/`, `third_party/` or older plans. Files nobody owns (`tools/play.py`,
`tools/shm.py`, `tests/test_play.py`, `tests/test_shm.py`, `cp/plugin/tests/replay_frame.py` aside from D) stay unchanged.

---

## 1. Contracts (fixed; change only through the integrator)

### 1.1 Wire protocol (`proto` stays 1; unknown `t` and unknown fields are ignored by older builds)

**Cyberpunk → Minecraft** (built by the DLL; positions use `cp_to_mc_pos(…, off)` of R2 plan section 1.1):

| `t` | fields | when | built by |
|---|---|---|---|
| `hackresult` (ext) | adds optional `upload` (seconds, finite, clamped 0..30; `action.GetActivationTime()`; 0 = instant) and `kind` ∈ `squad, crowd, driver, camera, traffic, distract, device` (anything else is dropped by the DLL). Both only matter when `ok` | reply to every `hack` | D |
| `hackspread` (new) | `id` (the `hack` request id), `target:"<id>"` (decimal Uint64), `why` ∈ `chain, spread, ping, frenzy` | one per extra NPC the hack touched, ≤ 16 per id. `chain`/`spread` before that id's `hackresult`; `ping`/`frenzy` may come **after** it (upload completion) | D |
| `cam` (ext) | adds `seat:bool` (V sits in a vehicle; `pl` is then V's hips point, not the feet). `fp` is now honest: false in a third-person vehicle camera and while the on-foot third-person view is applied | every frame | D |
| `hud` | kept for older Minecraft builds; the DLL now always sends `hidden:false` | on change | D |
| `key` (ext) | `k` also ∈ `arms`, `camo` (the new binds; swallowed while forwarding) | input | D |
| `stats` | `money` int ≥ 0, `wanted` int 0..5, `district` str ≤ 64, `time:[h 0..23, m 0..59]`, `speed` km/h ≥ 0 or -1 on foot | 1 Hz and on change, while On and linked | U |
| `quest` | `title` ≤ 128, `kind` ∈ `main, side, gig, other`, `obj:[{text ≤ 256, cur, tot (ints ≥ 0; tot 0 = no counter), opt:bool, on:bool}]` ≤ 12, `wp:[x,y,z]` or `null` | on change; `wp` ≤ 5 Hz | U |
| `pins` | `list:[{p:[x,y,z], kind ∈ quest, vendor, fasttravel, gig, police, ripperdoc, apartment, car, other, tr:bool}]` ≤ 32, within 150 m | ≤ 2 Hz and on change | U |
| `prompt` | `title` ≤ 128 (may be ""), `list:[{text ≤ 128, key ≤ 3 chars (`F`, `R`, `1`, `2`, ...), hold:bool, inactive:bool}]` ≤ 6; an empty list clears | on change | U |
| `choices` | `hubs:[{id int, title ≤ 128, list:[{text ≤ 256, f:[flag…]}]}]` ≤ 4 hubs, ≤ 12 entries in all; flags ∈ `inactive, pay, quest, check_ok, check_fail, timed`; `active` hub id, `sel` int (-1 none), `left` seconds or -1; empty `hubs` clears | on change | U |
| `sub` | `show:bool`, `key` str ≤ 64 (line id), `who` ≤ 64, `text` ≤ 256, `secs` 0..30 | each dialogue line shown/hidden | U |
| `call` | `phase` ∈ `incoming, start, active, end`, `who` ≤ 64, `audio:bool`, `reject:bool` | on change | U |
| `notify` | `kind` ∈ `quest, item, money, xp, level, sms, shard, zone, wanted, warning, save, info`, `title` ≤ 128, `text` ≤ 256, `secs` 0..30 | each Cyberpunk notification while On | U |
| `screen` | either `open` ∈ `pause, map, journal, phone, inventory, skills, cyberware` (Minecraft opens that screen), or `pass` = a section 1.11 pass-through name, `""` = pass-through ended | a menu was intercepted / a pass-through menu opened or closed | U |
| `reply` | `kind` ∈ `journal, phone, atlas`, `id` (the request id), `groups:[{hash, title ≤ 128, kind, tracked:bool, done:bool, lines:[{hash, text ≤ 256, state}]}]` ≤ 128 groups and ≤ 512 lines in all, `pins:[{p:[x,y,z], kind, name ≤ 64, i}]` ≤ 256; whole message ≤ 256 KB | answer to `uiact journalreq/phonereq/atlasreq` | U |

`reply` meanings: **journal**: groups = quests (`kind` main/side/gig/other), lines = objectives (`state` 0 active, 1
done, 2 failed). **phone**: groups = contacts/threads (`kind` `sms`), lines = messages (`state` 0 from the contact, 1
from V, 2 a reply V can choose; its `hash` is what `smsreply` sends). **atlas**: `pins` = every Map-target mappin plus
fast-travel points (`i` = fast-travel index, -1 when not one), `groups` empty. Hashes are Uint32 as JSON numbers.

**Minecraft → Cyberpunk:**

| `t` | fields | when | sent by |
|---|---|---|---|
| `binds` (ext) | `keys:{hack, skills, cyberware, activate, arms, camo}` VK 1..254; a missing name keeps the current key | after each host `hello`, and when rebound | C |
| `hello` (ext) | adds `ver` (the mod version, e.g. `"1.0.0"`) | on connect | M |
| `status` (ext) | adds `pause:bool` (the Night City pause screen is open: Cyberpunk must be paused) | 10 Hz and on change | M |
| `uiact` (new) | `a` ∈ `save, loadlast, mainmenu, cpmenu, track, smsreply, fasttravel, journalreq, phonereq, atlasreq`, `arg` str ≤ 64, `hash` Uint32, `id` int | a Night City screen button | W |
| `persp` (new) | `mode` ∈ `first, back` | on F5 (`key perspective`) and after each host `hello` | M |

**Validation:** every receiver checks types and limits, counts a bad message and drops it (or the bad entry). Strings
are cut to their limit in UTF-8 and stripped of control characters by the DLL before sending. Unknown enum values
become `other` for `pins.kind`/`quest.kind`, are dropped for flags, and make the message invalid for `screen`,
`hackspread.why`, `uiact.a`, `persp.mode`. Pause authority is **level-triggered**: Cyberpunk is paused by Minecraft
exactly while `status.pause` is true **and** the link is up **and** Cyberblock is On; on a link drop, F9 off, Lost or a
host `hello`, the DLL unpauses.

### 1.2 Natives

**Declared by S in `Natives.reds`, implemented by D in `natives.cpp`:**
```swift
// ---- re-test 3 (plan 2026-10-03-retest3-polish section 1.2) ----
public native func Cyberblock_HackResult(id: Int32, ok: Bool, reason: String, upload: Float, kind: CName) -> Void; // CHANGED: upload clamped 0..30, kind checked against kHackKinds (n"" allowed when !ok)
public native func Cyberblock_HackSpread(id: Int32, target: Uint64, why: CName) -> Void;   // why in chain|spread|ping|frenzy; ≤ 16 per id
public native func Cyberblock_SetSeat(seated: Bool, tpp: Bool, hipsLocal: Vector4) -> Void; // 10 Hz and on camera switch; hipsLocal in V's local axes (Y forward, Z up), metres
public native func Cyberblock_SetSlowMoFx(id: CName, seconds: Float) -> Void;               // n"sandevistan"|n"kerenzikov", 0..30; n"" / 0 ends it
public native func Cyberblock_SetViewAllowed(allowed: Bool) -> Void;                        // third person may be applied (Active on foot, not held, no menu, no braindance, no photo mode)
public native func Cyberblock_ViewEye() -> Vector4;                                         // V's first-person eye (the camera without the third-person offset)
```
**Declared by U in `UiNatives.reds`, implemented by D in `natives.cpp`:**
```swift
public native func Cyberblock_SetPhonePrompt(open: Bool) -> Void; // a call rings or an actionable notification shows: T goes to Cyberpunk, not Minecraft chat
```
**Declared by U in `UiNatives.reds`, implemented by U in `ui_game.cpp`** (all fire-and-forget unless a return type is
given; each builds a section 1.1 message through the pure builders and sends it only when On and linked, deduplicated):
```swift
public native func Cyberblock_OpenScreen(name: CName) -> Bool;     // true: Minecraft will open it (linked, On, Minecraft not in a screen); sends screen.open
public native func Cyberblock_PassMenu(name: CName) -> Void;       // n"" ends; sends screen.pass
public native func Cyberblock_HudStats(money: Int32, wanted: Int32, district: String, hour: Int32, minute: Int32, speed: Float) -> Void;
public native func Cyberblock_HudQuest(title: String, kind: CName, texts: array<String>, cur: array<Int32>, tot: array<Int32>, optional: array<Bool>, tracked: Int32, hasWp: Bool, wp: Vector4) -> Void;
public native func Cyberblock_HudPins(pos: array<Vector4>, kinds: array<CName>, tracked: array<Bool>) -> Void;
public native func Cyberblock_HudPrompt(title: String, texts: array<String>, keys: array<CName>, holds: array<Bool>, inactive: array<Bool>) -> Void;
public native func Cyberblock_HudChoices(hubIds: array<Int32>, hubTitles: array<String>, hubOf: array<Int32>, texts: array<String>, flags: array<Int32>, activeHub: Int32, selected: Int32, secondsLeft: Float) -> Void; // flags bitmask: 1 inactive, 2 pay, 4 quest, 8 check_ok, 16 check_fail, 32 timed
public native func Cyberblock_Subtitle(show: Bool, key: String, speaker: String, text: String, seconds: Float) -> Void;
public native func Cyberblock_PhoneCall(phase: CName, contact: String, audio: Bool, rejectable: Bool) -> Void;
public native func Cyberblock_Notify(kind: CName, title: String, text: String, seconds: Float) -> Void;
public native func Cyberblock_NextUiAction() -> CName;             // n"" when empty; pause, resume, save, loadlast, mainmenu, cpmenu, track, smsreply, fasttravel, journalreq, phonereq, atlasreq
public native func Cyberblock_UiActionArg() -> String;
public native func Cyberblock_UiActionHash() -> Uint32;
public native func Cyberblock_UiActionId() -> Int32;
public native func Cyberblock_ReplyBegin(kind: CName, id: Int32) -> Void;
public native func Cyberblock_ReplyGroup(hash: Uint32, title: String, kind: CName, tracked: Bool, done: Bool) -> Void;
public native func Cyberblock_ReplyLine(hash: Uint32, text: String, state: Int32) -> Void; // belongs to the last group
public native func Cyberblock_ReplyPin(pos: Vector4, kind: CName, name: String, index: Int32) -> Void;
public native func Cyberblock_ReplyEnd() -> Void;                  // sends the reply (≤ 256 KB, extra entries dropped and counted)
```
Unchanged natives keep their meaning. `Cyberblock_SetMenu` stays the input-forwarding gate. `Cyberblock_SetChoices`
stays (E, Q, 1-4 and the wheel stay Cyberpunk's while choices show).

### 1.3 DLL interfaces between D and U
- **D extracts the helper** (`cp/plugin/src/native_reg.hpp`, new, D-owned); the body moves verbatim from `natives.cpp`:
  ```cpp
  #pragma once
  #include <RED4ext/RED4ext.hpp>
  #include <initializer_list>
  #include <utility>
  #include "log.hpp"
  namespace cb::natives {
      using Param = std::pair<const char *, const char *>; // RTTI type, name
      template<typename T>
      void add(RED4ext::CRTTISystem *rtti, const char *name, RED4ext::ScriptingFunction_t<T> fn, const char *returnType,
               std::initializer_list<Param> params = {});
  }
  ```
- **U provides** (`cp/plugin/src/ui.hpp`; the game-facing part lives in `ui_game.cpp`, the pure part in `ui.cpp`):
  ```cpp
  namespace RED4ext { struct CRTTISystem; }
  namespace cb::ui {
      void register_natives(RED4ext::CRTTISystem *rtti); // D calls it at the end of natives::post_register_types
      void on_minecraft_message(const nlohmann::json &m); // D's link handler in main.cpp, after cb::input
      void pump();                                        // D's on_running_update, after cb::input::tick(); throttles itself to 30 Hz
      void on_link_dropped();                             // D's link-drop callback, after cb::snap::on_link_dropped
  }
  ```
- **D provides for U** (read-only use): `cb::natives::active`, `inVehicle`, `menuOpen`, `held`; `cb::link().linked()`,
  `screen_open()`, `send()`; `cb::world::level_offset(cb::world::level_k())`; NEW `bool cb::world::minecraft_pause()` =
  the last `status.pause` (false before any status and after a link drop or `hello`).
- **CMake (D):** the DLL gets `src/*.cpp` through the GLOB as now. `cb_tests` adds `src/ui.cpp tests/test_ui.cpp` only
  `if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/src/ui.cpp AND EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/tests/test_ui.cpp)`.
  `ui.cpp` must not include RED4ext.
- Until U merges, D keeps an uncommitted `src/zz_stub_ui.cpp` with empty bodies; U keeps an uncommitted copy of
  `native_reg.hpp` and may wire `main.cpp`/`natives.cpp` locally (uncommitted) to test.

### 1.4 Script APIs
```swift
// S, Seat.reds (new) — U calls it from CyberblockSystem
public abstract class CyberblockSeat {
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, on: Bool) -> Void; // 10 Hz; mounted & on: Cyberblock_SetSeat(true, tpp, hips); registers the UI_ActiveVehicleData.IsTPPCameraOn listener on mount
  public static func Clear(game: GameInstance) -> Void;                                      // Cyberblock_SetSeat(false, false, zero), unregisters the listener
}
// S, View.reds (new) — U calls it from CyberblockSystem
public abstract class CyberblockView {
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, active: Bool, held: Bool, menu: Bool) -> Void; // Cyberblock_SetViewAllowed(active && !held && !menu && !braindance && !photo mode)
  public static func Clear(game: GameInstance) -> Void;                                                                 // SetViewAllowed(false)
}
// S, Effects.reds (changed): Strike gains an attacker
public static func StrikeBy(game: GameInstance, target: ref<GameObject>, frac: Float, impulse: Vector4, attacker: ref<GameObject>) -> Void;
// U, Ui.reds (new)
public abstract class CyberblockInk {
  public static func Apply(game: GameInstance, on: Bool) -> Void; // every tick and from the pump; hides/restores the 2D layers (section 1.11)
  public static func Restore(game: GameInstance) -> Void;         // all layers back to opacity 1 (Off, Lost, session end, detach, before saves)
}
public abstract class CyberblockUi {
  public static func Pump() -> Void; // called by the DLL (cb::ui::pump) up to 30 Hz, also while paused; drains Cyberblock_NextUiAction
}
```
`CyberblockSystem` (U) keeps calling every existing S module exactly as today; its new calls are `CyberblockSeat.Tick`,
`CyberblockView.Tick` (10 Hz) and `CyberblockSeat.Clear`/`CyberblockView.Clear` on every Clear path.

### 1.5 Java contracts
**`Bridge.java` (M owns; exact additions):**
```java
/** Host message listeners by "t" (link thread). Several listeners per type run in registration order; one that throws is logged and the rest still run. */
public static final Map<String, List<Consumer<JsonObject>>> listeners = new ConcurrentHashMap<>(); // values are CopyOnWriteArrayList
public static void listen(final String type, final Consumer<JsonObject> listener) { listeners.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>()).add(listener); }
/** The proxy is pinged (Compass Ping): synced entity data, valid on the client. M sets it. */
public static volatile Predicate<Entity> pingedProxy = e -> false;
/** Marks a proxy pinged for `ticks` (server thread; 0 clears). M sets it. */
public static volatile ObjIntConsumer<Entity> pingProxy = (e, ticks) -> {};
```
`dispatch` iterates the list. Every caller of `listeners.put` (C's `CyberLink.listen`) switches to `Bridge.listen`.

**Other M-owned members others use:**
- `dev.cyberblock.client.StatusReporter`: `public static volatile boolean nightCityPaused;` (W sets it; sent as
  `status.pause`).
- `dev.cyberblock.client.AllowedToast` (new, empty marker interface): `ToastManagerMixin` lets any Toast that implements
  it through. W's `CyberToast` implements it.
- `HostState.Pose` gains `boolean seat` (flag `seat`, false when absent).
- `ProxyEntity`: synced `DATA_PINGED` (int ticks left), `setPinged(int)`, counts down in `tick()`; drives
  `Bridge.pingedProxy`/`pingProxy`.
- `fabric.mod.json`: client entrypoints add `dev.cyberblock.client.ui.UiClient`; `mixins` adds
  `cyberblock.ui.client.mixins.json` (environment client); `depends` `"fabric-api": ">=0.161.0"`; `icon`
  `assets/cyberblock/icon.png`.
- `HostLink` sends `hello.ver` from `FabricLoader.getInstance().getModContainer("cyberblock")` metadata.

**C-owned members W uses:** `SkillTreeScreen()` and `CyberwareScreen()` keep public no-arg constructors.

### 1.6 Quickhacks: one unique effect per chip (F1)
**Wheel rule:** the chip in the main hand wins, then the wheel pick, then the first chip. Selecting a hotbar slot that
holds a chip clears the wheel pick. Firing shows `cyberblock.hack.fired` on the action bar in the chip colour.

**Minecraft phases (C, `HackFx`):** on send, an upload beam (chip-coloured `DustParticleOptions` from Steve's eyes to the
target) lasts `upload` seconds (`HackFxRules.clampUpload`, 0..30; from `hackresult`, which arrives after send: the beam
runs from send until the result, then for `upload` more seconds), with `BEACON_POWER_SELECT` at the chip's pitch. The
**impact** plays when the upload completes. Effects follow a live proxy (`Bridge.proxyById`) or a fixed point (devices:
the scan lock position). A target that is still uploading refuses a new hack (`cyberblock.hack.busy`, no RAM spent).
Spreads arriving for a finished id are accepted for the last 32 ids, unknown ids are dropped quietly.

| Chip (item) | wire | colour | Minecraft impact | ticks | Cyberpunk (vanilla record) | Cyberpunk extra, crowd and always (S, `HackFx.reds`, at upload end) |
|---|---|---|---|---|---|---|
| Redstone Charge | `short_circuit` | 0xFF2B2B | `LightningBolt` with `setVisualOnly(true)`; ELECTRIC_SPARK burst; red dust sparks | 40 | Overload (electrocutes, kills pedestrians) | none (vanilla is visible); optional `status_electrocuted` VFX on crowd |
| Blaze Chip | `overheat` | 0xFF8A00 | BLAZE_SHOOT; rising FLAME spiral, LAVA pops, SMOKE; **no fire blocks** | 100 | Overheat | crowd: `CyberblockEffects.Ignite(target, 5)` |
| Ink Sac Daemon | `reboot_optics` | 0x24243A | SQUID_SQUIRT; SQUID_INK burst at eye height, ink halo around the head | 80 | Blind | crowd: `BaseStatusEffect.Blind`, remaining 5 s |
| Cobweb Daemon | `cripple_movement` | 0xE8E8E8 | COBWEB_PLACE + SPIDER_AMBIENT; ITEM_COBWEB; **real** `Blocks.COBWEB` in the feet cell, its 4 horizontal neighbours and the head cell, only into air, never within 1.5 blocks of a player, removed after 6 s, tracked in SavedData `HackWebs` (cleaned on load) | 120 | Locomotion Malfunction | crowd: `BaseStatusEffect.Stun` 4 s (the root) |
| Spider Eye Virus | `contagion` | 0x5BD13A | SPLASH_POTION_BREAK; `AreaEffectCloud` (custom `ColorParticleOption ENTITY_EFFECT` green, radius 2.5 shrinking, 6 s, owner Steve, **no effects**); each `spread` target gets its own cloud plus a green particle arc from the source | 120 | Contagion | always: upload ContagionLvl2 (spread off) to the 2 nearest valid NPCs within 6 m, each reported `spread` |
| Wither Skull Daemon | `synapse_burnout` | 0x6FE3E0 | WITHER_SHOOT at Steve; SOUL/LARGE_SMOKE trail to the target over 8 ticks; WITHER_SKELETON_HURT with SCULK_SOUL + LARGE_SMOKE burst | 40 | Synapse Burnout (BrainMelt) | always: `StrikeBy(target, 0.35, impulse < 4 m/s, attacker V)` heavy hit (no ragdoll) |
| Compass Ping | `ping` | 0xFFD34A | LODESTONE_COMPASS_LOCK; END_ROD column; every hostile proxy within 32 blocks and every `ping` spread target pinged 200 ticks (gold outline, client); vanilla `Enemy` mobs within 32 get GLOWING 200 ticks | 200 | Ping (built as `PingSquad`, as vanilla does) | always: `SendForceRevealObjectEvent(npc, true, n"CyberblockPing", V, 10.0)` on hostile NPCs ≤ 30 m (≤ 16), each reported `ping` |
| Bottle of Forgetting | `memory_wipe` | 0xB57CFF | BOTTLE_EMPTY + ILLUSIONER_MIRROR_MOVE; WITCH/ENCHANT swirl around the head; mobs targeting this proxy drop it (`setTarget(null)`) | 80 | Memory Wipe | crowd: `Stun` 2 s ("dazed") |
| Creeper Brain | `cyberpsychosis` | 0x3FD13A | CREEPER_PRIMED; the 8x8 creeper-face mask (`HackFxRules.CREEPER_MASK`, an original pattern) in green dust above the head, facing Steve, redrawn every 10 ticks; ANGRY_VILLAGER on every `frenzy` victim | 120 | Cyberpsychosis (Madness) | crowd: frenzy for 6 s, every 1.5 s the target `StrikeBy`s the nearest NPC within 4 m (frac 0.15, attacker = the hacked puppet), each victim reported `frenzy` |
| Redstone Lever | `device` | 0xFF2B2B | by `kind`: **camera** LEVER_CLICK + black SMOKE + red-dust puff; **traffic** BELL_BLOCK + alternating red/green dust; **distract** NOTE particles + NOTE_BLOCK_CHIME at random pitches; **device** LEVER_CLICK + redstone dust | 40 | the device action | none |

`kind` (S, `CyberblockHacks.Run`): `driver` when the target is a car; `crowd` when `record.IsCrowd()` or
`AISquadHelper.GetSquadName(puppet)` is not valid; else `squad`; devices answer `camera`, `traffic`, `distract` or
`device`. Every crowd extra is logged (`hackfx: …`). Upload extras run through `DelaySystem.DelayCallback(cb, upload)`.
The extra hits respect `IsProtected` and `Withheld` (quest NPCs and V's friends are spared). All TweakDB records named
were checked present in `tweakdb.bin` and `tweakdb_ep1.bin`.

### 1.7 Keys (F8)

| Action | Default | VK | Minecraft mapping | wire name |
|---|---|---|---|---|
| Hack | G | 0x47 | existing | `hack` |
| Skill tree | K | 0x4B | existing | `skills` |
| Cyberware screen | U | 0x55 | existing | `cyberware` |
| Operating System implant | Y | 0x59 | `key.cyberblock.activate` (renamed label only) | `activate` |
| Arms implant | L | 0x4C | `key.cyberblock.arms` `InputConstants.KEY_L` | `arms` |
| Optical Camo | O | 0x4F | `key.cyberblock.camo` `InputConstants.KEY_O` | `camo` |

- **Clash rule** (identical in `VirtualKeys` and `keys::apply_binds`, pinned by `shared/vectors.json` `binds_apply`): a
  key outside the bindable set falls back to **its own** default; if any two of the six resolved keys are equal, **all
  six** go back to G/K/U/Y/L/O. A missing name keeps the current key.
- **Vanilla clashes:** on the first client tick, every vanilla mapping (not our category) bound to one of our six keys is
  unbound once (`keyAdvancements` L, `keyFriends` O, `keyQuickActions` G), then `KeyMapping.resetMapping()` and
  `options.save()`, with a log line and the chat note `cyberblock.binds.vanilla_unbound`.
- The tap/hold selection cycling is **removed** (CYBER_SELECTED goes away). Each key fires its slot's ACTIVE implant.
- DLL log line: `binds: hack G, skills K, cyberware U, activate Y, arms L, camo O`.

### 1.8 Cyberware changes (F6, F9, F11)
- **OS slots (F9):** `CyberSlot.OPERATING_SYSTEM` capacity 2 with fixed positions: `CyberSlot.position(Cyberware)` = 0
  for `cyberdeck`, 1 for `sandevistan` and `berserk`, -1 (first free) for every other implant. Installing into an
  occupied fixed position replaces (Berserk replaces Sandevistan, never the deck). `CyberwareRules.normalize` moves an old
  save's Sandevistan/Berserk from `operating_system/0` to `/1`, once on join, before the first `cyberware` send.
  `Ram.hasDeck(installed, carried) = installed || carried`. The kit installs Sandevistan with the deck.
- **Triggers (F8):** `CyberwareRules.Trigger {OS, ARMS, CAMO}`; `forTrigger(Set<Cyberware>, Trigger)` = the installed
  ACTIVE implant in OPERATING_SYSTEM, ARMS or the optical camo. `CyberwareEffects.fire(player, trigger, aim)`.
- **Arms abilities on L (F11):** Mantis Blades, Gorilla Arms and Monowire become ACTIVE (their passive modifiers stay):

  | id | ability | cooldown | Minecraft | Cyberpunk |
  |---|---|---|---|---|
  | mantis_blades | lunge to the aimed entity ≤ 10 blocks, 1.5x hit on arrival | 120 (6 s) | `setDeltaMovement` + `hurtMarked`, which the R5 launch pipeline turns into V following; SWEEP_ATTACK, red dust trail | the arrival hit through `hit` |
  | gorilla_arms | ground slam: knockback strength 1.0 to living entities and proxies ≤ 4 blocks | 160 (8 s) | DUST_PLUME ring, MACE_SMASH_GROUND_HEAVY | proxies get `hit` with F7 impulses (Knockdown tier at most) |
  | monowire | 120° lash ≤ 5 blocks, 4 damage, Slowness II 60 ticks | 100 (5 s) | ELECTRIC_SPARK arcs, PLAYER_ATTACK_SWEEP pitch 1.8 | `hit` per proxy |
  | projectile_launch_system | unchanged (hitscan explosion) | 160 | unchanged | unchanged |

- **Arm models (F6):** item definitions `assets/cyberblock/items/<id>.json` for `mantis_blades`, `gorilla_arms`,
  `monowire`, `projectile_launch_system` become `minecraft:select` on `minecraft:custom_model_data` (strings index 0):
  case `"arm"` → `cyberblock:item/<id>_arm`, case `"arm_out"` → `cyberblock:item/<id>_arm_out`, fallback = the existing
  flat model. Textures `textures/item/arm_<id>.png` (32x32, procedural). **Arm frame:** model units, the shoulder pivot
  is model point (8, 16, 8), the arm runs toward −y: y 16 = shoulder, y 4 = wrist; Steve's arm is the box x 6..10,
  y 4..16, z 6..10; implants wrap it 0.25-0.5 units outside; blades, wire and launcher may extend to y −12 (elements
  within −16..32). Glowing edges use `light_emission` 15. C renders `new CustomModelData(List.of(), List.of(),
  List.of(deployed ? "arm_out" : "arm"), List.of())` with `ItemDisplayContext.NONE` so that this frame sits on the
  arm's ModelPart; if the frame proves wrong C fixes its transform, never A's models.
- **Cyber look (F11):** overlay textures in the 64x64 player-skin layout: `textures/entity/cyber/kiroshi_eyes.png`,
  `subdermal_armor.png`, `reinforced_tendons.png` (original, procedural, mostly transparent; emissive pixels for eyes and
  tendons).
- **Optical camo shimmer (F11):** while the camo timer runs, Steve (self, third person) renders translucent with a cyan
  tint instead of disappearing; mobs still get vanilla Invisibility.
- **Advancements (F11),** data in `data/cyberblock/advancement/` (A), awarded by C through
  `ServerPlayer.getAdvancements().award(holder, criterion)`:

  | id | parent | criteria | awarded when |
  |---|---|---|---|
  | `root` | — | one `minecraft:tick` criterion (A verifies the trigger id in 26.3; fallback `impossible` awarded on join) | joining |
  | `first_implant` | root | `installed` (`impossible`) | any install |
  | `dual_os` | first_implant | `installed` (`impossible`) | deck + Sandevistan or Berserk installed |
  | `first_quickhack` | root | `hacked` (`impossible`) | first `hackresult` ok |
  | `every_quickhack` | first_quickhack | the 10 wire names, each `impossible` | each chip's first ok result |
  | `sandevistan_kill` | dual_os | `kill` (`impossible`) | a kill while the Sandevistan timer runs |

  Icons are Cyberblock items; frames task/goal/challenge as A sees fit; texts in section 1.12. Vanilla's L
  (advancements key) moves to the pause menu, as HOW-TO-PLAY says.
- **Not in this plan (F11 follow-ups, documented in CHANGELOG "Planned"):** a ripperdoc flow with eddie costs (needs the
  money plumbing of F2 and verified TransactionSystem names first) and a cyberware capacity budget.

### 1.9 Knockback tiers (F7)
- **Minecraft impulse (M, `ProxyDamage`):** `|impulse| = (0.4 + extra) x 5 m/s`, horizontal. `extra` = the existing
  knockback level ((`ATTACK_KNOCKBACK` + sprint) x 0.5). Gorilla Arms adds +1.0 strength on people (vehicles keep x3).
  Projectiles: `(0.4 + 0.6 x Punch level) x 5` along the projectile's horizontal direction (not 50 % of its speed). Mob
  attackers: knockback level without sprint. Results: plain hit 2 m/s, sprint or Knockback I 4.5, Knockback II /
  gauntlet / Gorilla Arms 7, Gorilla Arms + sprint 9.5, gauntlet + Gorilla Arms 12.
- **Cyberpunk tiers (S, `Effects.reds` Strike), by `s = |impulse|` on people:** `s < 4.0` Impact (severity 1/1);
  `4.0 ≤ s < 6.5` Stagger (`SetHitReactionSeverityMin/Max(2)` after PreAttack); `6.5 ≤ s < 10.0` Knockdown
  (`hitFlag.ForceKnockdown` + severity 3/3); `s ≥ 10.0` Knockdown plus a ragdoll after the damage lands. Named
  constants `StaggerSpeed`, `KnockdownSpeed`, `RagdollSpeed`.
- **Ragdoll (Knock on people):** speed = `MinF(s, 8.0)` (under the 11 m/s impact-kill threshold), direction = horizontal
  + 0.25 up, impulse = dir x speed x `RagdollImpulsePerMs` (start 4.0), influence radius 2.0. Skipped on a lethal hit
  (the game's death reaction ragdolls). Vehicles: impulse x `GetTotalMass()` x `VehicleShare` 0.5. Tether keeps its own
  speeds with the new constant and is re-checked in game.
- The wire shape of `hit` is unchanged.

### 1.10 Shader and compositor (D)
- `uniform float4 cb_slowmo` (hidden): x intensity 0..1 (envelope: 0.15 s ease-in, hold, 0.4 s ease-out; 0 while a
  Cyberpunk menu is open), y kind (0 sandevistan, 1 kerenzikov), z seconds since start, w 0.
- `uniform bool cb_menu` (hidden): a Cyberpunk menu is open and passed through (`menuOpen && !link().screen_open()`):
  the Composite pass skips the Minecraft world; the Final pass still adds the overlay (now only Minecraft's frame).
- User slider `SandevistanStrength` 0..1, default 1 (motion-sensitive players set 0).
- Textures `GhostHistTex`, `GhostTmpTex`: half resolution, RGBA8, persistent; passes `GhostAccum`, `GhostStore` after
  Bright and before Final; everything skipped when `cb_slowmo.x == 0`.
- Effect in `PS_Final` before the overlay: k = x · strength · (kind 1 ? 0.5 : 1): chromatic split (R/B at ±0.012k
  radial), ghost trails (sGhostHist x 0.6k), edge glow (Sobel on Night City and Minecraft depth, lime/cyan), lime/teal
  tint with a pulsing vignette. HUD and hand stay crisp.
- `fx_check` (ctest): builds the vendored ReShade FX front end and compiles `cp/shaders/Cyberblock.fx` with
  `ReShade.fxh`; a syntax error fails the build.

### 1.11 Minecraft-themed UI rules (F2, F12, F13; U and W)
- **While On (Active or Vehicle)** Cyberpunk's `inkHUDLayer`, `inkGameNotificationsLayer` and `inkWaitingSignLayer` get
  `SetOpacity(0.0)` with `SetVisible(true)` on their virtual windows (controllers, listeners and choice input keep
  running). Never touched: `inkWorldLayer`, `inkAdvertisementsLayer`, `inkStreetSignsLayer`, `inkSystemNotificationsLayer`,
  `inkPhotoModeLayer`. Restored to 1.0 on Off, Lost, session end, detach and before saves. **No code path shows the
  Cyberpunk HUD because of prompts or choices any more** (`ChoicesShown` re-show and `hudDelay` are deleted).
- **Exceptions (logged):** `inkGameNotificationsLayer` shows while the game is paused by something that is not
  Cyberblock (tutorial and shard popups that wait for input), restyled by `CyberblockRestyle` and framed by Minecraft;
  `inkHUDLayer` shows during a braindance (editor timeline) with the same restyle.
- **Look (W):** Minecraft font only; vanilla tooltip sprites (`tooltip/background`, `tooltip/frame`) for boxes; vanilla
  button widgets; container panel colours #C6C6C6 panels, #8B8B8B slots, #555555/#FFFFFF bevels; text colours from
  `ChatFormatting` (yellow #FFFF55 keys and selection, gold #FFAA00 quest-important, aqua #55FFFF speakers, gray #AAAAAA
  inactive, red #FF5555 hostile/wanted, green #55FF55 done). Sprite ids are verified in the 26.3 jar and used at
  runtime; nothing is copied.
- **HUD placement** (GUI px, scales with GUI scale; nothing overlaps vanilla's hotbar, effects, boss bars or chat):

  | Element | Where | Content |
  |---|---|---|
  | RadarHud | top-left, 80x80, 4 px margin | map-item style: terrain shading of the client level within 24 m, NPC dots (red hostile, white neutral, grey squares vehicles), Minecraft mobs, pins with vanilla map-decoration sprites, Steve arrow, objective edge arrow; rotates with the host yaw |
  | StatsHud | under the radar | `€$ <money>` with a gold-nugget item icon, wanted level as 5 original pixel stars, clock `hh:mm`, district, km/h in vehicles |
  | QuestHud | top-right below the effect icons (y ≥ 52), right-aligned, ≤ 160 px wide | title in yellow, objectives with box/tick marks, counters, `(optional)`, distance to the waypoint in metres |
  | Waypoint | vanilla Locator Bar | `ClientWaypointManager.trackWaypoint(TrackedWaypoint.setPosition(UUID, icon, Vec3i))`; QuestHud's arrow is the fallback when the bar is not shown |
  | PromptHud | right of the crosshair (x + 12) | tooltip box, `[F] Talk` with the key in yellow; hold actions get a fill bar; inactive entries grey |
  | ChoicesHud | bottom centre above SubtitleHud | tooltip-styled list; selected `> ` in yellow; inactive grey; quest-important gold; pay/check suffixes; timed hub gets a boss-bar style timer |
  | SubtitleHud | above the hotbar (height − 72) | speaker in aqua, text white, fades after `secs` |
  | CyberToast | vanilla toast area | `notify` (icon per kind), `call` (stays while ringing: `[T] Answer / hold [T] Reject`), save toasts |
  | MenuFrameHud | whole screen while `HostState.frame().menu()` | Minecraft title bar with the pass-through label and a #555555/#FFFFFF bevel border; every other UI element returns early |
  | Vehicle HUD | — | while `HostState.vehicle()`: hotbar, health, food, armour, XP level and crosshair are hidden by `replaceElement` wrappers; the hand still renders |
  | Third-person crosshair | screen centre | drawn while the host camera is third person on foot (vanilla omits it when detached) |

- **Per-menu decision** (U intercepts in `gameuiInGameMenuGameController.OnAction` while On, linked, not held, not
  bypassing; KBM release / pad hold-complete as vanilla):

  | Cyberpunk menu (key) | Result |
  |---|---|
  | Pause (Esc), Hub (H) | Minecraft `NightCityPauseScreen` (`open:"pause"`) |
  | World map (M) | Minecraft `AtlasScreen` (`open:"map"`) |
  | Journal (J) | Minecraft `JournalScreen` (`open:"journal"`) |
  | Messages / phone | Minecraft `PhoneScreen` (`open:"phone"`), SMS replies through JournalManager |
  | Inventory (I), Crafting | Minecraft inventory (`open:"inventory"`) |
  | Perks (P) | `SkillTreeScreen` (`open:"skills"`) |
  | Cyberware (U, existing) | `CyberwareScreen` |
  | Vendors/shops, ripperdoc (incl. the prologue install), stash, wardrobe, crafting bench, breach protocol, arcade, braindance editor, tutorial and shard popups, settings and save/load lists (through the escape hatch), death screen, loading screens | **pass-through**: `CyberblockRestyle` tints the widget tree in Minecraft colours, Minecraft draws the frame (`screen.pass` names: `vendor, ripperdoc, stash, wardrobe, crafting, breach, arcade, braindance, tutorial, settings, saves, death, loading, other`), `cb_menu` hides the Minecraft world |

  Fonts inside pass-through menus stay Cyberpunk's: Minecraft's font cannot be copied, and cooking an original pixel
  font into an `.archive` needs WolvenKit, which is not in the toolchain. HOW-TO-PLAY says so.
- **Escape hatch:** the Minecraft pause screen's "Night City Menu..." sends `uiact cpmenu` with `arg` `pause`; U opens the
  vanilla menu once with a bypass flag. It must always work, and the Minecraft side must never strand the player: when
  not linked, vanilla OnAction runs unchanged.
- **Pause:** the Night City pause screen sets `StatusReporter.nightCityPaused` while open; the DLL turns `status.pause`
  into `pause`/`resume` UI actions; the script pauses with Codeware's system-requests handler and remembers
  `pausedByUs`. Loading screens during quest teleports: U sends `screen.pass:"loading"`; W draws "Loading Night
  City..." as the cover.

### 1.12 Lang keys (A writes all of them; code uses translatableWithFallback with this English)
- **F1:** `cyberblock.hack.fired` "Hack: %s"; `cyberblock.hack.busy` "%s is still uploading a hack".
- **F8:** `key.cyberblock.activate` "Operating System implant"; `key.cyberblock.arms` "Arms implant";
  `key.cyberblock.camo` "Optical Camo"; `cyberblock.cyberware.none.os` "No Operating System implant to fire (%s)";
  `cyberblock.cyberware.none.arms` "No active Arms implant to fire (%s)"; `cyberblock.cyberware.none.camo` "No Optical
  Camo installed (%s)"; `cyberblock.binds.vanilla_unbound` "Cyberblock took %s from %s (rebind it in Controls)";
  `cyberblock.cyberware.tooltip.key` "Fire: %s". Remove `cyberblock.cyberware.selected`.
- **F9:** `cyberblock.cyberware.screen.os_deck` "Cyberdeck goes here"; `cyberblock.cyberware.screen.os_combat`
  "Sandevistan or Berserk goes here". Remove `cyberblock.hack.no_deck_os`; `cyberblock.hack.no_deck` "No cyberdeck:
  install one in the Operating System slot (U) or carry one".
- **F11 arms:** `cyberblock.cyberware.lunge` "Mantis lunge"; `cyberblock.cyberware.slam` "Gorilla slam";
  `cyberblock.cyberware.lash` "Monowire lash"; `cyberblock.cyberware.no_target` "Nothing to lunge at"; update
  `cyberware.cyberblock.{mantis_blades,gorilla_arms,monowire}.mc` to mention the L ability.
- **F11 advancements:** `advancements.cyberblock.<id>.title` / `.description`: root "Cyberblock" / "Night City meets the
  Overworld"; first_implant "Chromed Up" / "Install your first cyberware"; dual_os "Two Brains" / "Run a cyberdeck and
  a Sandevistan or Berserk at once"; first_quickhack "Netrunner" / "Land your first quickhack"; every_quickhack "Script
  Kiddie No More" / "Land every quickhack chip"; sandevistan_kill "Time Is on My Side" / "Defeat an enemy while the
  Sandevistan runs".
- **F10:** `cyberblock.sandevistan.hud` "SANDEVISTAN".
- **F11 launcher:** `menu.cyberblock.play` "Play Cyberblock".
- **F2 view:** `cyberblock.view.third` "Third person"; `cyberblock.view.first` "First person".
- **F2/F12/F13 UI** (`cyberblock.ui.*`):
  - pause: `pause.title` "Night City (paused)", `pause.back` "Back to Night City", `pause.save` "Save Game",
    `pause.saving_locked` "Saving is not possible right now", `pause.load_last` "Load Last Save", `pause.map` "Map",
    `pause.journal` "Journal", `pause.messages` "Messages", `pause.cp_menu` "Night City Menu...", `pause.options`
    "Minecraft Options...", `pause.quit` "Quit to Cyberpunk Main Menu", `pause.quit_confirm` "Quit to the Cyberpunk main
    menu? Unsaved progress is lost.";
  - atlas: `atlas.title` "Atlas", `atlas.you` "You", `atlas.track` "Track", `atlas.fast_travel` "Fast travel to %s",
    `atlas.fast_travel_off` "Fast travel: use the Night City map (Night City Menu...)";
  - journal: `journal.title` "Journal", `journal.main` "Main Jobs", `journal.side` "Side Jobs", `journal.gig` "Gigs",
    `journal.other` "Other", `journal.track` "Track", `journal.tracked` "Tracked", `journal.done` "Completed",
    `journal.empty` "No jobs";
  - phone: `phone.title` "Phone", `phone.contacts` "Contacts", `phone.reply` "Reply", `phone.empty` "No messages";
  - call: `call.incoming` "Incoming call: %s", `call.answer` "[%s] Answer / hold [%s] Reject", `call.active` "On call:
    %s", `call.ended` "Call ended";
  - toasts: `notify.saving` "Saving Night City...", `notify.saved` "Saved", `notify.<kind>` for the 12 kinds of section
    1.1 (quest "Job", item "Item", money "Eddies", xp "Experience", level "Level Up", sms "Message", shard "Shard", zone
    "Zone", wanted "Wanted", warning "Warning", save "Save", info "Night City");
  - HUD: `money` "€$ %s", `time` "%s:%s", `speed` "%s km/h", `quest.optional` "(optional)", `quest.distance` "%s m",
    `prompt.hold` "Hold";
  - pass-through labels `pass.<name>` for the 14 names of section 1.11 (vendor "Shop", ripperdoc "Ripperdoc", stash
    "Stash", wardrobe "Wardrobe", crafting "Crafting", breach "Breach Protocol", arcade "Arcade", braindance
    "Braindance", tutorial "Tip", settings "Settings", saves "Saves", death "Flatlined", loading "Loading Night City...",
    other "Night City");
  - `screen.unavailable` "Night City is not connected".

### 1.13 Version and packaging (F11)
- **Version 1.0.0 everywhere:** `cp/plugin/CMakeLists.txt` `project(Cyberblock VERSION 1.0.0)` with
  `CB_VERSION`/`CB_VERSION_MAJOR/MINOR/PATCH` compile definitions for `Cyberblock` and `cb_tests`; `main.cpp` Query uses
  them; `natives.hpp` `kVersion = CB_VERSION`; `mc/gradle.properties` `version=1.0.0`; `pyproject.toml` 1.0.0 +
  `uv lock`; the `Natives.reds` header comment; the CHANGELOG's top entry. `tests/test_version.py` asserts they agree.
- **Release zip** `dist/Cyberblock-1.0.0.zip` (built by `tools/package.py`, `dist/` ignored by git):
  ```
  Cyberblock-1.0.0/
    install.bat  uninstall.bat  install.ps1
    README.md  HOW-TO-PLAY.md  CHANGELOG.md  LICENSE  NOTICE.md  THIRD-PARTY-NOTICES.md
    game/        red4ext/plugins/Cyberblock/Cyberblock.dll, r6/scripts/Cyberblock/*.reds,
                 bin/x64/reshade-shaders/Shaders/Cyberblock.fx, bin/x64/reshade-shaders/Shaders/ReShade.fxh
    minecraft/mods/cyberblock-1.0.0.jar
  ```
  The `game/` list comes from `tools.install.payload()` (one source of truth). The PowerShell installer writes the same
  manifest as `install.py` (`red4ext/plugins/Cyberblock/install-manifest.json`, `{"version":1,"files":{rel:sha256},
  "dirs":[...]}`), so the dev installer and the release uninstaller are interchangeable. An existing `ReShade.fxh` is
  accepted and skipped (not refused) by **both** installers.
- **Minecraft side for launcher players:** a dedicated launcher installation with game directory
  `%APPDATA%\.minecraft-cyberblock` and the JVM-arguments field **replaced** by
  `-Xmx2G -XX:+UseZGC --enable-native-access=ALL-UNNAMED -Dcyberblock.autostart=true`; the title screen gets a "Play
  Cyberblock" button; the link stays on 127.0.0.1:25600.

### 1.14 Log lines (the in-game checks grep for these)
- **DLL (D):** `binds: …` (section 1.7); flight summary gains `, yaw start A end B, mouse x N counts at R deg/count,
  pitch min..max`; `look: R deg per mouse count`; `seat: on (third person|first person)` / `seat: off`;
  `view: third person on (distance D m)`, `view: first person (<reason>)`, `view: applied, eye moved D m`;
  `screenfx: sandevistan S s`; `compositor: menu pass-through on|off`; `Minecraft says hello: mod cyberblock 1.0.0,
  Minecraft 26.3`, `version mismatch: DLL 1.0.0, mod X`.
- **DLL (U):** `ui: pump ready` or `ui: pump disabled: CyberblockUi.Pump missing`; `ui: action <a>`; `ui: unpaused
  (<reason>)`; `ui: reply <kind> <n> groups, <m> lines, <k> pins, <b> bytes`.
- **Scripts (U):** `ui: ink layers <names>`, `ui: hidden (on)`, `ui: restored (<reason>)`, `ui: shown for <reason>`,
  `ui: menu <name> -> minecraft <screen>`, `ui: pass-through <menu>`, `ui: paused by Minecraft`, `ui: resumed`,
  `ui: notify <kind>`, `ui: escape hatch <menu>`.
- **Scripts (S):** `hack <name> on <id>: ok (<kind>, upload <s>s, +<n> spread)`; `hackfx: <hack> <kind> <what>`;
  `knock: <id> s=<s> tier=<tier> impulse=<i>`; `seat: slot <slot> hips (<x>, <y>, <z>) tpp <b>`, `seat: no Hips slot`;
  `cyberfx: <id> <s> s`.
- **Minecraft:** `hackfx: {} on {} upload {} s`; `webs: placed {} removed {}`; `binds: unbound vanilla {} ({})`;
  `cyberware: fire {} ({})`; `ui: screen {}`; `persp: {}`; `launcher: opening {} (autostart|button)`.

---

## 2. Package D: DLL core and shader (Opus)

### D1. Hack result and spread (F1)
**Files:** `src/world.hpp/.cpp`, `src/natives.cpp`, `tests/test_events.cpp`, `tests/test_messages.cpp`.
- [ ] `world.hpp`: `kHackKinds[] = {squad, crowd, driver, camera, traffic, distract, device}`,
  `kSpreadWhy[] = {chain, spread, ping, frenzy}`, `kMaxSpreadPerId = 16`.
- [ ] `make_hackresult(id, ok, reason, upload, kind)`: upload clamped 0..30 (non-finite → 0), `kind` omitted when empty
  or unknown; `make_hackspread(id, target, why)` (target as an unsigned decimal string; nullopt on an unknown why).
- [ ] `natives.cpp`: the changed `Cyberblock_HackResult` signature and the new `Cyberblock_HackSpread` (counter per id,
  the 17th and later dropped and counted); register both.
- [ ] Tests: clamp, unknown kind, omitted fields when !ok, spread decimal id, unknown why, the 16 cap.
**Done:** `cb_tests` passes.

### D2. Six binds (F8)
**Files:** `src/keys.hpp/.cpp`, `src/input.hpp/.cpp`, `tools/gen_vectors.py`, `shared/vectors.json`,
`tests/test_cbmath.py`, `tests/test_keys.cpp`.
- [ ] `kL = 0x4C`, `kO = 0x4F`; `Binds{…, arms = kL, camo = kO}`; `BindsUpdate` gains `armsRejected`,
  `camoRejected`; `fellBack()` includes them; `is_swallowed` covers six; `apply_binds` reads six names with the section
  1.7 clash rule.
- [ ] `input.cpp`: `g_bindArms`, `g_bindCamo`; `binds()` returns six; `key_name` gives `"arms"`, `"camo"`; the log line.
- [ ] `gen_vectors.py`: a Python reference of resolve + clash; emit a new `binds_apply` set
  (`{given:{name: vk|absent}, current, expect, clash}`) with every single refusal, a two-key clash, a missing name, L/O
  as hack/skills (which now clashes). Regenerate `vectors.json`; `test_cbmath.py` checks the set against the reference.
- [ ] `test_keys.cpp`: run the `binds_apply` set; rewrite the cases that used L/O as custom hack/skills keys.
**Done:** `cb_tests` and `pytest tests/test_cbmath.py` pass.

### D3. Owned yaw while gliding (F4)
**Files:** `src/input.cpp/.hpp`, `src/keys.hpp/.cpp`, `src/follow.hpp/.cpp`, `src/drive.cpp/.hpp`,
`src/camera.cpp/.hpp`, `src/main.cpp`, `tests/test_follow.cpp`, `tests/test_keys.cpp`.
- [ ] `input`: `std::atomic<int64_t> g_mouseX`; in `on_raw_mouse` (relative packets) add `lLastX` while `forwarding() &&
  !screen_open()`; `int64_t take_mouse_x()` = exchange(0). In the GetRawInputData IAT hook, while
  `cb::drive::driving() && forwarding() && !screen_open()`, zero the game's `lLastX` only (pitch untouched). Gate the
  strip on first person (`!view::applied()`, D5).
- [ ] `keys`: `class TurnRate { void sample(double yawDeltaDeg, int64_t counts); double degrees_per_count() const; }`:
  sums |dyaw| and |counts| over frames with |counts| ≥ 2 and |dyaw| < 30, halves both past ~4000 counts, returns 0.15
  until 200 counts, clamps to [0.002, 1.0].
- [ ] `follow`: `double turned_yaw(double cpYaw, int64_t counts, double degreesPerCount)` = `cpYaw − counts · rate`,
  normalised to (−180, 180] (Cyberpunk yaw is counter-clockwise; mouse right lowers it).
- [ ] `drive`: `tick(int64_t mouseX, double degreesPerCount)`; `g_yaw` set from the existing forward read on the
  Idle → driving transition (that frame's counts discarded), then `turned_yaw` each later frame not behind a menu;
  `teleport(game, feet, yaw)` passes `EulerAngles{0, 0, g_yaw}` for Move/Land/Ground (no per-frame
  GetWorldForward/ToRotation). Summary line gains yaw start/end, counts, rate; camera keeps pitch min/max while driving.
- [ ] `camera::publish(int64_t mouseX)` feeds `TurnRate` with the wrapped yaw change and the counts only while
  active, !held, !menu, !inVehicle and `seconds_since_end() > 0.5`; logs `look: …` once when settled or when it changes
  by > 10 %.
- [ ] `main.cpp`: `const auto dx = cb::input::take_mouse_x(); cb::drive::tick(dx, g_turn.degrees_per_count());
  cb::camera::publish(dx);`.
- [ ] Tests: `turned_yaw` sign, wrap, zero counts; TurnRate warm-up, window, clamps.
**Done:** `cb_tests` passes; needsInGame: mid-glide mouse swing turns the glide at once (summary shows the yaw change).

### D4. Seat and honest first-person flag (F3)
**Files:** `src/natives.hpp/.cpp`, `src/camera.cpp`, `src/world.hpp/.cpp`, `src/cbmath.hpp/.cpp`,
`tests/test_cbmath.cpp`, `tests/test_world.cpp`.
- [ ] `Cyberblock_SetSeat` → atomics `seated`, `seatTpp`, `hipsLocal` (x/y/z); cleared by `SetActive(false)` and link
  drop. Log `seat: on (…)`/`seat: off` on change.
- [ ] `cbmath`: `Vec3 rotate(const Quat &q, Vec3 v)` (C++ only, pure; Cyberpunk-internal, so not mirrored in
  `cbmath.py`) with tests.
- [ ] `camera.cpp`: when seated, read V's `entEntity.GetWorldOrientation` and send `pl` = feet + rotate(q, hipsLocal),
  `fp = !seatTpp`, `seat:true`; on foot `fp = !view::applied()` (D5).
- [ ] `world::make_cam(..., bool firstPerson, bool seat, int levelK)`; tests.
- [ ] `send_state()` sends `hud hidden:false` always (the Vehicle state no longer hides Minecraft's HUD).
**Done:** `cb_tests` passes.

### D5. Third-person view (F2)
**Files:** `src/view.hpp/.cpp` (new), `src/camera.cpp`, `src/main.cpp`, `src/natives.cpp`, `src/world.cpp`,
`tests/test_view.cpp` (new), `CMakeLists.txt`.
- [ ] Pure: `struct Orbit{distance 3.2, height 0.25, shoulder 0.0}`; `Vec3 desired_local(mode, pitchDeg)` =
  (shoulder, −d·cos p, h + d·sin p) in V's local axes (Y forward, Z up); critically damped distance smoothing;
  collision distance = hit − 0.25 m, min 0.4 m. Tests.
- [ ] Game: every frame before `camera::publish`: when mode == back (from `persp`, D-owned parse in `world.cpp`; reset to
  first on `hello`), `viewAllowed` (`Cyberblock_SetViewAllowed`) and linked: `player.GetFPPCameraComponent()` →
  `SetLocalPosition(offset)` (both verified in the 2.31 scripts), shortened by `SyncRaycastByCollisionGroup` from the eye
  (Minecraft's collision boxes are real Static entities, so blocks stop the camera); otherwise restore the initial local
  position (read once). Never applied in vehicles (native cameras), scenes, menus, braindance, photo mode; reset on
  every exit and on link loss. `eye` = camera position without the offset, served by `Cyberblock_ViewEye`.
- [ ] `bool view::applied()` feeds `cam.fp` and D3's strip gate. Log lines of section 1.14, including the first measured
  world delta (verifies the component frame).
**Done:** `cb_tests` passes; needsInGame: F5 on foot shows Steve from behind, walls shorten the camera, F5 again is
first person, every exit restores.

### D6. UI plumbing, compositor gate, phone key (F12, F13)
**Files:** `src/native_reg.hpp` (new), `src/natives.cpp/.hpp`, `src/main.cpp`, `src/world.hpp/.cpp`,
`src/keys.hpp/.cpp`, `src/input.cpp`, `src/compositor.h/.cpp`, `CMakeLists.txt`, `tests/test_keys.cpp`,
`tests/test_world.cpp`.
- [ ] Section 1.3: move `add`/`Param` to `native_reg.hpp`; call `cb::ui::register_natives(rtti)` at the end of
  `post_register_types`; `cb::ui::on_minecraft_message(message)` in the link handler; `cb::ui::pump()` after
  `cb::input::tick()`; the link-drop callback calls `cb::snap::on_link_dropped()` then `cb::ui::on_link_dropped()`; the
  conditional `cb_tests` sources. Keep the uncommitted `zz_stub_ui.cpp`.
- [ ] `world`: parse `status.pause` into `minecraft_pause()` (false on hello and link drop); accept `persp`; accept
  `hello.ver` (D8).
- [ ] `Cyberblock_SetPhonePrompt` → `natives::phonePrompt`; `keys::Context` gains `phone`: while set and no Minecraft
  screen is open, T is neither swallowed nor sent as `chat`. Tests.
- [ ] `compositor::set_menu_mode(bool)` → uniform `cb_menu` (section 1.10), driven each frame from
  `menuOpen && !link().screen_open()`; log on change.
**Done:** `cb_tests` passes; the DLL builds with the stub.

### D7. Sandevistan screen effect (F10)
**Files:** `src/natives.cpp`, `src/compositor.h/.cpp`, `src/screenfx.hpp/.cpp` (new), `tests/test_screenfx.cpp`
(new), `tests/fx_check.cpp` (new), `CMakeLists.txt`, `cp/shaders/Cyberblock.fx`.
- [ ] `Cyberblock_SetSlowMoFx` → `compositor::set_slowmo(kind, seconds)` (steady clock start; `set_active(false)` and
  seconds 0 clear it); `screenfx::envelope(since, duration)` pure with tests.
- [ ] `on_begin_effects` sets `cb_slowmo` (section 1.10).
- [ ] `Cyberblock.fx`: section 1.10 uniforms, slider, textures, passes and the `PS_Final` block.
- [ ] `fx_check`: an executable built from `third_party/reshade/source` (effect lexer, preprocessor, parser, symbol
  table, expression, codegen_hlsl) that compiles `Cyberblock.fx` with `ReShade.fxh` and `BUFFER_WIDTH/HEIGHT` defined;
  `add_test(NAME fx_check …)`; optionally D3DCompile the generated HLSL. If the vendored front end cannot be built,
  report `blocked` and keep a test that at least preprocesses the file.
- [ ] Optional: drive the dead `set_screen_fx` (shake) from `explosion` near V.
**Done:** `cb_tests` and `fx_check` pass; `replay_frame.py` with a forced `cb_slowmo` shows the look (screenshot).

### D8. Version 1.0.0 and hello check (F11)
**Files:** `CMakeLists.txt`, `src/main.cpp`, `src/natives.hpp`, `src/link.cpp`, `tests/test_link.cpp`.
- [ ] Section 1.13 version wiring; `Link::handle` logs `hello.ver` and warns on a mismatch with `CB_VERSION`; test.
**Done:** all D suites pass; `Cyberblock 1.0.0 loading` in the load log line.

---

## 3. Package U: Cyberpunk UI bridge (Opus)

### U1. Hide the old UI for good (F12 core, F3 body)
**Files:** `cp/scripts/Cyberblock/Ui.reds` (new), `PlayerState.reds`, `Cyberblock.reds`, `UiNatives.reds` (new).
- [ ] `CyberblockInk.Apply/Restore` (section 1.4/1.11) through `GameInstance.GetInkSystem().GetLayer(name)
  .GetVirtualWindow()`; first Apply logs `ui: ink layers <all names from GetLayers()>`; exceptions for foreign pauses
  (Codeware `GetSystemRequestsHandler().IsGamePaused()` and not `pausedByUs`) and braindance (BraindanceBlackboard).
- [ ] `PlayerState.reds`: delete `HudWindow`, `ShowHud`, `HideHud`, `ChoicesShown` and `hudDelay`. `Update(player,
  active, vehicle, screenOpen)`: in the Vehicle state only `HideBody` runs (no restrictions, god mode or HUD changes),
  checked every tick (`bodyCheckDelay` 0 in vehicles) so meshes re-enabled by an FPP/TPP switch vanish within a tick;
  `Clear`/`ShowBody` on Off, Lost, saving, session end, detach.
- [ ] `Cyberblock.reds`: `UpdatePlayer` calls `CyberblockInk.Apply(on = Active || Vehicle)` and passes
  `Equals(state, Vehicle)`; every former `ShowHud` site calls `CyberblockInk.Restore`; `Notify` uses `Cyberblock_Notify`
  while linked and keeps the Cyberpunk WarningMessage only for "Start Minecraft first" and "Minecraft lost"; calls
  `CyberblockSeat.Tick/Clear` and `CyberblockView.Tick/Clear` (section 1.4; uncommitted `zz_stub_seat.reds` until S
  merges); owns `CyberblockHud` Register/Unregister/Tick (U3).
- [ ] `UiNatives.reds`: every section 1.2 declaration that U owns.
**Done:** compile_check passes; needsInGame: walk up to a door/NPC/item while Active and in a car: no Cyberpunk HUD
element appears at any time (`ui: hidden (on)`), V's body never shows in either car camera.

### U2. DLL UI builders, actions and pump (F2, F12, F13)
**Files:** `cp/plugin/src/ui.hpp`, `ui.cpp`, `ui_game.cpp`, `cp/plugin/tests/test_ui.cpp` (all new).
- [ ] Pure builders (`ui.cpp`): `make_stats`, `make_quest`, `make_pins`, `make_prompt`, `make_choices`, `make_subtitle`,
  `make_call`, `make_notify`, `make_screen_open`, `make_screen_pass`, and a `ReplyBuilder`; section 1.1 limits, UTF-8
  safe truncation, control characters stripped, positions through `cp_to_mc_pos(…, off)`, unknown enums handled as
  section 1.1 says; dedupe (`Last<T>`) and rate limits (stats 1 Hz, pins 2 Hz, waypoint 5 Hz, plus on change).
- [ ] `UiActions` queue: fed by `uiact` (validated), drained by Pump; non-resume actions are dropped while not On;
  level-triggered pause from `world::minecraft_pause()` (section 1.1); on link drop, Off or `hello` a `resume` is queued.
- [ ] `ui_game.cpp`: the section 1.2 U natives through `cb::natives::add`; `pump()` resolves
  `GetClass("CyberblockUi")->GetFunction("Pump")` once (log ready/disabled), calls it on the game thread ≤ 30 Hz, also
  while paused.
- [ ] `test_ui.cpp`: every builder (limits, truncation in the middle of a multi-byte character, offsets, enum mapping),
  the reply 256 KB cap, the queue policy and the pause edge rules.
**Done:** `cb_tests` passes (with D's CMake rule or a local equivalent).

### U3. HUD data: stats, quest, pins, subtitles, calls, prompts, choices (F2, F13)
**Files:** `cp/scripts/Cyberblock/Hud.reds` (new), `Cyberblock.reds`.
- [ ] `CyberblockHud` with delayed blackboard listeners (also fire while paused): UIInteractions
  (InteractionChoiceHub, DialogChoiceHubs, ActiveChoiceHubID, SelectedIndex), UIGameData (ShowDialogLine,
  HideDialogLine), UI_ComDevice.PhoneCallInformation, UI_WantedBar.CurrentWantedLevel, UI_System.TrackedMappin,
  UI_Map.currentLocation; `JournalManager.RegisterScriptCallback(this, n"OnTracked", gameJournalListenerType.Tracked)`.
- [ ] 2 Hz poll: money (`TransactionSystem.GetItemQuantity(player, MarketSystem.Money())`), tracked objective as
  quest_tracker does (title, kind, phase objectives, counters, optional), waypoint (TrackedMappin world position, else
  `GetQuestMappinPositionsByObjective`), minimap pins (`GetMappins(Minimap)`, visible, ≤ 150 m, ≤ 32), game time,
  district, vehicle speed.
- [ ] Prompts: `GetLocalizedText(localizedName)`, key from inputAction (Choice1 F, Choice2 R, Choice3 1, Choice4 2;
  verified in inputUserMappings.xml), hold, `ChoiceTypeWrapper.IsType` flags. Choices: `ListChoiceData.localizedName`,
  hubs, active hub, selection, timed seconds.
- [ ] `Cyberblock_SetPhonePrompt(true)` while a call is incoming or a notification with an action shows.
- [ ] Everything only when On and linked; the builders dedupe.
**Done:** compile_check passes; needsInGame: tracked job text and waypoint, money changes, a call rings, a dialogue
with 3 choices and a selection change, an `F Pick up` prompt, all on the Minecraft side.

### U4. Notifications and save prompts (F12)
**Files:** `cp/scripts/Cyberblock/Notifications.reds` (new), `Cyberblock.reds`.
- [ ] `@wrapMethod SetNotificationData` on `GenericNotificationController` and on every subclass that overrides it
  (grep the 2.31 scripts: JournalNotification, MessengerNotification, ItemAddedNotification, CurrencyNotification,
  LevelUpNotification, ShardCollectedNotification, ZoneAlertNotification, UIInGameNotification, ProgressionNotification,
  CraftingNotification, BountyCollectedNotification, and any other found): `Cyberblock_Notify(kind, title, text, secs)`
  then `wrappedMethod`.
- [ ] Session BeforeSave/AfterSave: `notify save` "Saving Night City..." / "Saved".
**Done:** compile_check passes; needsInGame: pick up an item, gain eddies, receive an SMS: Minecraft toasts only.

### U5. Menus replaced by Minecraft screens (F12)
**Files:** `cp/scripts/Cyberblock/Menus.reds` (new).
- [ ] `@wrapMethod(gameuiInGameMenuGameController) OnAction`: section 1.11 mapping; `Cyberblock_OpenScreen(name)` true →
  `ListenerActionConsumer.Consume(consumer)` and return false, else `wrappedMethod`; vanilla behaviour unchanged when not
  linked, held, during `q301_02a_stadium_stopUIInCutscene`, or bypassing; gamepad hold-complete honoured.
- [ ] `@wrapMethod OnInitialize` keeps a wref in a ScriptableService; `@addMethod CyberblockOpenVanilla(menu)` spawns
  `OnOpenPauseMenu` or `OnOpenHubMenu_InitData` (`HubMenuInitData{m_menuName}`) with a one-shot bypass.
- [ ] `CyberblockUi.Pump` drains actions: `pause`/`resume` (Codeware PauseGame/UnpauseGame, `pausedByUs`), `save`
  (`IsSavingLocked` check, `QuickSave()`; locked → toast), `loadlast`, `mainmenu`, `cpmenu`, `track`
  (`JournalManager.TrackEntry`), `smsreply` (messengerDialogView's ActivateReply logic), `fasttravel` (behind a
  `fastTravelEnabled` flag, default false until verified), `journalreq`/`phonereq`/`atlasreq` (reply builders); then
  `CyberblockRestyle.Pass()` when a menu is open and `CyberblockInk.Apply`.
**Done:** compile_check passes; needsInGame: Esc, M, J, I, P, H each open the Minecraft screen (`ui: menu …`);
"Night City Menu..." opens vanilla pause; pause really stops Night City; save works.

### U6. Pass-through restyle and frames (F12)
**Files:** `cp/scripts/Cyberblock/Restyle.reds` (new), `Menus.reds`.
- [ ] `CyberblockRestyle.Pass()`: Codeware `GetLayer(n"inkMenuLayer").GetGameControllers()` → root widgets, recursive
  walk ≤ 600 widgets per pass, tinted widgets remembered; inkText #FFFFFF, disabled #A0A0A0, selected/hovered #FFFF55;
  large inkImage/inkRectangle backgrounds #C6C6C6 at 0.92 alpha, borders #555555, slots #8B8B8B; icons and item art
  untouched.
- [ ] Detect pass-through menus (menu scenario / controller class names verified in the 2.31 scripts) and loading/death
  screens; `Cyberblock_PassMenu(<section 1.11 name>)` on open, `n""` on close; log `ui: pass-through …`.
**Done:** compile_check passes; needsInGame: a vendor shows Minecraft colours with the Minecraft frame and no Minecraft
world over it.

---

## 4. Package S: Cyberpunk gameplay scripts (Opus)

### S1. Distinct quickhacks (F1)
**Files:** `Natives.reds`, `Hacks.reds`, `Events.reds` (only if needed), `Npcs.reds` (read-only helpers may be made
public).
- [ ] `Natives.reds`: the S block of section 1.2; header version comment 1.0.0.
- [ ] `Run()` works out `kind` (section 1.6); `Upload()` returns the action, passes `target.GetGameplayRoleComponent()`
  as vanilla `ExecuteAction` does, uses `new PingSquad()` for ping; `Hack()` sends `upload = action.GetActivationTime()`
  and `kind`.
- [ ] Chain: `NearestN(game, target, range, n, exclude)`, each chained target reported `chain` (hash via
  `CyberblockIds.Hash`); devices return their kind with the reason; Contagion's own spread (section 1.6).
- [ ] Log line of section 1.14.
**Done:** compile_check passes.

### S2. Upload-time extras on any NPC (F1)
**Files:** `HackFx.reds` (new), `Effects.reds`.
- [ ] `CyberblockHackFx` + `CyberblockHackFxCallback extends DelayCallback` (target hash, hack, kind) queued with
  `DelaySystem.DelayCallback(cb, upload)`; the section 1.6 extras column; `StatusEffectSystem.ApplyStatusEffect` +
  `SetStatusEffectRemainingDuration`; Ping reveal over the tether/NPC registry with the Npcs hostility test (≤ 30 m,
  ≤ 16, reported `ping`); frenzy loop (reported `frenzy`); optional `GameObjectEffectHelper.StartEffectEvent` VFX names
  from audit B, crowd only, logged.
- [ ] `Effects.reds`: public `StrikeBy(game, target, frac, impulse, attacker)` (Strike delegates with V).
**Done:** compile_check passes; needsInGame: every chip on a ganger and on a pedestrian looks different (TESTING-PLAN
F1 table).

### S3. Vanilla-like knockback (F7)
**Files:** `Effects.reds`, `Tether.reds`.
- [ ] Section 1.9 tiers in `Strike`/`Expect`/`Apply`, ragdoll only for the top tier and vehicles, lethal-hit skip
  (Health via `StatPoolsSystem.GetStatPoolValue`), new `Knock` constants, influence radius 2.0, log line plus a delayed
  0.3 s velocity read.
- [ ] `Tether.reds`: keep its speeds with the new constant (comment the re-check).
**Done:** compile_check passes; needsInGame: punches flinch, sprint hits stagger, gauntlet hits knock down, only
gauntlet + Gorilla Arms ragdolls; leads and wind charges still pull without flinging.

### S4. Seat data (F3)
**Files:** `Seat.reds` (new).
- [ ] Section 1.4 `CyberblockSeat`: `GetMountedVehicle`, `GetCameraManager().IsTPPActive()`, Hips via
  `GetSlotComponent().GetSlotTransform(n"Hips", wt)` converted to V-local with `Quaternion.TransformInverse`; fallback
  (0, 0, 0.45) with `seat: no Hips slot` once; the IsTPPCameraOn listener for instant switches; once-per-mount log.
**Done:** compile_check passes.

### S5. View gate and aim eye (F2)
**Files:** `View.reds` (new), `Probes.reds`.
- [ ] `CyberblockView.Tick/Clear` (section 1.4) with braindance and photo-mode checks verified in the 2.31 scripts.
- [ ] `SetAim` measures from `Cyberblock_ViewEye()` instead of the camera (identical in first person).
**Done:** compile_check passes.

### S6. Slow-mo screen effect hook and tooling (F10, F11)
**Files:** `Cyberware.reds`, `compile_check.py`.
- [ ] `CyberblockCyberware.Fx` calls `Cyberblock_SetSlowMoFx(id, seconds)` after `SetTimeDilation`; `EndSlowMo` calls it
  with `n""`, 0.
- [ ] `compile_check.py`: the default game path comes from `tools.install.find_game()` (no `D:\SteamLibrary` default).
**Done:** compile_check passes; `uv run pytest -q tests/test_reds_ids.py` passes.

---

## 5. Package M: Minecraft core (Opus)

### M1. Shared contracts (do first)
**Files:** `Bridge.java`, `ProxyEntity.java`, `ProxyManager.java`, `client/StatusReporter.java`,
`client/AllowedToast.java` (new), `client/mixin/ToastManagerMixin.java`, `client/HostState.java`,
`client/HostLink.java`, `fabric.mod.json`, `BridgeTest.java`, `HostStateTest.java`.
- [ ] Section 1.5 exactly; `BridgeTest` covers two listeners, a throwing one, order; `HostStateTest` covers `seat`.
**Done:** `gradlew test` passes; commit early so C and W can mirror it.

### M2. Steve in vehicles (F3)
**Files:** `client/mixin/EntityRenderDispatcherMixin.java`, `client/mixin/AvatarRendererMixin.java`,
`client/mixin/LivingEntityMixin.java`, `client/mixin/CameraMixin.java`, `client/PlayerSync.java`,
`client/ClientInput.java`.
- [ ] Remove the vehicle render cancel (keep it only until the first `seat` arrives); `AvatarRendererMixin`
  extractRenderState TAIL sets `state.isPassenger = true` for the local player while `HostState.vehicle()`;
  `LivingEntityMixin` freezes the walk animation in vehicles; `ClientInput.applyHud` drops `|| HostState.vehicle()`.
- [ ] `PlayerSync`: when `p.seat()`, place Steve at (px, py − 0.70, pz), `yBodyRot = bodyYaw`, head yaw = camera yaw in
  first person and body yaw in third person; camera type follows `fp`. `CameraMixin` keeps its 0.8 m inside guard.
**Done:** `gradlew test build`; dev-client screenshots with fakehost sending `seat:true` in both `fp` values.

### M3. Vanilla-like impulse (F7)
**Files:** `ProxyDamage.java`, `ProxyEntity.java`, `ProxyDamageTest.java`, `InteractiveItemsTest.java`.
- [ ] Section 1.9 Minecraft side (`BASE_KNOCKBACK 0.4`, `PUSH_PER_STRENGTH 5.0`, Gorilla +1.0, projectiles with Punch
  via `EnchantmentHelper.modifyKnockback`, mobs without sprint). Tests for every listed result.
**Done:** `gradlew test` passes.

### M4. Perspective toggle (F2)
**Files:** `client/ClientInput.java`, `client/PlayerSync.java`, `client/HostLink.java`.
- [ ] `key perspective` toggles first/back, sends `persp`, resends it after each host `hello`, shows
  `cyberblock.view.*` on the action bar; PlayerSync renders THIRD_PERSON_BACK from `fp:false` (existing) with the head
  rule of M2.
**Done:** `gradlew test build`; fakehost check that `persp` is sent.

### M5. Launcher install and release bits (F11)
**Files:** `client/AutoWorld.java`, `client/CyberblockClient.java`, `client/HostLink.java`, `build.gradle`,
`gradle.properties`.
- [ ] `AutoWorld.start(mc, parent)`: open the existing `cyberblock` world (`createWorldOpenFlows().openWorld(WORLD,
  () -> mc.setScreen(parent))`) or create it as today; `-Dcyberblock.autostart=true` starts once per session from the
  title screen; `ScreenEvents.AFTER_INIT` adds the "Play Cyberblock" button on `TitleScreen` via
  `Screens.getWidgets(screen).add(...)`, placed without overlapping (screenshot).
- [ ] `hello.ver`; `version=1.0.0`; the jar includes `LICENSE`, `NOTICE.md`, `THIRD-PARTY-NOTICES.md` from the repo root
  (missing files are skipped so M builds before P merges); pin `loom_version=1.18.2` if the plugin marker resolves,
  else keep the snapshot and report it.
**Done:** `gradlew test build`; the jar listing shows the notices (after integration).

### M6. Small supports (F1, F4, F5)
**Files:** `BlockEvents.java`, `BlockEventsTest.java`, `client/Flight.java`, `GroundAlignTest.java`.
- [ ] Prove (test) that placing/removing `Blocks.COBWEB` sends no `block` event (empty collision); if it would, filter
  blocks with an empty collision shape.
- [ ] `Flight` start/land logs append `getYRot()`.
- [ ] F5: no alignment change; add one regression test pinning `GroundAlign.kOf` on the R2 table if missing.
**Done:** `gradlew test` passes.

---

## 6. Package C: Minecraft cyber (Opus)

### C1. Wheel precedence (F1, main cause)
**Files:** `client/cyber/HackWheel.java`, `test/cyber/HackTest.java` (or a new `HackWheelTest.java`).
- [ ] Section 1.6 wheel rule; `cyberblock.hack.fired` on fire. Test: a chip in hand beats the stored pick; a slot
  change to a chip clears it.
**Done:** `gradlew test` passes.

### C2. Minecraft hack effects (F1)
**Files:** `cyber/Hacking.java`, `HackResult.java`, `HackSpread.java` (new), `HackFx.java` (new), `HackFxRules.java`
(new), `HackWebs.java` (new), `CyberServer.java`, `CyberMod.java`, `ScanInfo.java`, `client/cyber/CyberLink.java`,
`client/cyber/ScanClient.java`, tests `HackFxRulesTest`, `HackResultTest`/`HackSpreadTest`.
- [ ] `Locked` gains x/y/z; `Pending` keeps target, hack, lock position; `HackResult` parses `upload`/`kind` (defaults
  0/""), `HackSpread` record + parse; `CyberLink` uses `Bridge.listen` and adds `hackspread` → `server.execute`.
- [ ] `HackFx` (server thread, ticked from `CyberServer.tick`, cleared by `CyberMod`): beam then impact per section 1.6
  table; busy refusal; last-32-ids memory; `Bridge.pingProxy`; GLOWING for Enemy mobs; cobwebs through `HackWebs`
  SavedData (6 s, cleaned on load, never near a player, only into air). Lightning visual-only, clouds without effects,
  nothing reaches ExplosionEvents, ProxyDamage or MobWar.
- [ ] `HackFxRules` (pure): durations, clamp, web cells and exclusion, the original `CREEPER_MASK`, beam points.
**Done:** `gradlew test build`; dev-client screenshots of each chip's impact on a fakehost proxy.

### C3. Ping outlines (F1)
**Files:** `client/cyber/CyberwareClient.java` (or `HackFxClient.java`, new).
- [ ] Gold Gizmos cuboids around entities with `Bridge.pingedProxy` true, like the Kiroshi outline.
**Done:** screenshot.

### C4. Six binds and triggers (F8)
**Files:** `client/cyber/CyberKeys.java`, `VirtualKeys.java`, `CyberwareHud.java`, `CyberwareScreen.java`,
`cyber/CyberWire.java`, `CyberwareRules.java`, `CyberwareEffects.java`, `CyberData.java`, `Ripperdoc.java`,
`VirtualKeysTest.java`, `CyberWireTest.java`, `CyberwareRulesTest.java`.
- [ ] Section 1.7: six mappings, `BIND_NAMES`, `freeVanillaClashes`, removal of selection cycling and
  `CYBER_SELECTED`, `Trigger`/`forTrigger`/`fire`, HUD shows the real key on each ACTIVE icon, tooltips use the keybind.
  `CyberLink.key` maps `arms` and `camo` to the new mappings (`CyberLink.java` is C's).
  `VirtualKeysTest` runs `vectors.json` `binds_apply`.
**Done:** `gradlew test` passes.

### C5. Two OS slots (F9)
**Files:** `cyber/CyberSlot.java`, `CyberwareRules.java`, `Ram.java`, `CyberData.java`, `Hacking.java`, `Kit.java`,
`Ripperdoc.java`, `client/cyber/CyberwareScreen.java`, tests `CyberwareRulesTest`, `RamTest`, `KitTest`.
- [ ] Section 1.8 OS rules, `normalize` on join before the first send, `noDeck` plain message, ghost icons in empty OS
  frames.
**Done:** `gradlew test` passes.

### C6. Arm cyberware in 3D and abilities on L (F6, F11)
**Files:** `client/cyber/ArmCyberware.java` (new), `ArmCyberwareLayer.java` (new), `mixin/AvatarRendererCyberMixin.java`
(new), `cyberblock.cyber.client.mixins.json`, `CyberClient.java`, `cyber/Cyberware.java`, `CyberwareEffects.java`,
tests `ArmCyberwareTest`.
- [ ] `ArmCyberware.armStack(installed, mainHandEmpty, hostGun, vehicle, deployed)` (pure); the layer registered with
  `LivingEntityRenderLayerRegistrationCallback`; the mixin at the TAIL of `extractRenderState` (local player, resolves an
  `ItemStackRenderState` with `ItemModelResolver.updateForLiving`, stored with `setData`) and at the TAIL of
  `renderHand(PoseStack, SubmitNodeCollector, int, Identifier, ModelPart, boolean)` for first person; hidden with the
  gun pose and in vehicles; section 1.8 arm frame.
- [ ] Mantis/Gorilla/Monowire ACTIVE with the section 1.8 abilities and cooldowns; muzzle spark at the arm tip.
**Done:** `gradlew test build`; first- and third-person screenshots of all four implants, idle and deployed.

### C7. Sandevistan flourish, cyber look, camo shimmer, advancements (F10, F11)
**Files:** `client/cyber/SandevistanFx.java` (new), `client/cyber/mixin/GameRendererCyberMixin.java` (new),
`assets/cyberblock/post_effect/sandevistan.json` (new), `assets/cyberblock/shaders/post/sandevistan.fsh` (new),
`ArmCyberwareLayer.java`, `CyberClient.java`, `cyber/CyberwareEffects.java`, `Ripperdoc.java`, `Hacking.java`,
`CyberMod.java`.
- [ ] HUD element `sandevistan` (lime corner brackets, countdown bar, `cyberblock.sandevistan.hud`) always; the post
  effect only when **not** hosted (`!Cyberblock.active`), written originally against 26.3's post-shader layout read
  outside the repo; third-person afterimages (3 translucent cyan/magenta copies from a 2-tick position history).
- [ ] Cyber look overlays (Kiroshi eyes, subdermal, tendons) on Steve from A's textures; camo shimmer (section 1.8).
- [ ] Advancement awards (section 1.8 table).
**Done:** `gradlew test build`; screenshots (standalone post effect, hosted HUD flourish, afterimages, camo, cyber look).

---

## 7. Package W: Minecraft UI (Opus)

### W1. Model, link, theme (F2, F12, F13)
**Files:** `client/ui/UiClient.java`, `UiLink.java`, `UiModel.java`, `McTheme.java` (all new),
`cyberblock.ui.client.mixins.json` (new, may list no mixins), tests `client/ui/UiModelTest.java`.
- [ ] `UiModel`: one immutable volatile snapshot per message type of section 1.1 with `parse` (null when invalid,
  counted); `UiLink` registers with `Bridge.listen` (also `npcs`, `state`, `hello` for the radar and resets);
  `screen.open` opens the screen on the client thread; `McTheme` draws tooltip boxes, panels, bevels and key labels
  with the section 1.11 colours and verified vanilla sprite ids.
**Done:** `gradlew test` passes (every parser: valid, each limit, unknown enums).

### W2. Radar, stats, quest, waypoint, vehicle HUD (F2, F12)
**Files:** `client/ui/RadarHud.java`, `StatsHud.java`, `QuestHud.java`, `Waypoints.java`, `VehicleHud.java`,
`CrosshairHud.java` (all new), tests `RadarProjectionTest`.
- [ ] Section 1.11 placement via `HudElementRegistry` (`addLast`, `attachElementAfter`, `replaceElement`); radar
  projection pure and tested; stars and icons from A's sprites; Locator Bar waypoint (check the 26.3 contextual-bar
  rules; QuestHud arrow as fallback); vehicle wrappers; third-person crosshair.
**Done:** tests pass; fakehost-driven screenshots.

### W3. Prompts, choices, subtitles (F13)
**Files:** `client/ui/PromptHud.java`, `ChoicesHud.java`, `SubtitleHud.java` (new).
- [ ] Section 1.11 styles; choices react to `sel`/`active`/`left` updates; nothing handles input (Cyberpunk keeps it).
**Done:** fakehost screenshots: a 2-entry prompt with a hold entry, a 4-choice hub with a timer, a subtitle.

### W4. Toasts (F12)
**Files:** `client/ui/CyberToast.java` (new).
- [ ] `implements Toast, AllowedToast`; notify toasts with an item icon per kind; call toast stays while ringing; save
  toasts.
**Done:** screenshot; a vanilla toast is still cancelled.

### W5. Night City screens (F12)
**Files:** `client/ui/screen/NightCityPauseScreen.java`, `AtlasScreen.java`, `JournalScreen.java`, `PhoneScreen.java`
(all new), `client/ui/UiLink.java`, tests `AtlasProjectionTest`.
- [ ] Pause screen (`isPauseScreen` true, vanilla buttons, the section 1.12 labels; sets
  `StatusReporter.nightCityPaused` in `init`/`removed`; quit asks to confirm); Atlas (pannable/zoomable parchment-colour
  grid drawn in code, pins, V, tracked objective, Track, fast travel only when the reply marks it enabled); Journal
  (vanilla book texture at runtime, quests by kind, objectives, Track); Phone (contacts → thread → reply buttons).
  `inventory` → vanilla InventoryScreen, `skills`/`cyberware` → C's screens. Without a link: `screen.unavailable`.
**Done:** tests pass; screenshots of each screen with fakehost data.

### W6. Pass-through frame and loading cover (F12)
**Files:** `client/ui/MenuFrameHud.java` (new).
- [ ] While `HostState.frame().menu()`: title bar with `pass.<name>` and the bevel border; `loading` draws the full cover;
  every other UI element returns early.
**Done:** screenshot with fakehost `cam menu:true` + `screen pass:"vendor"`.

---

## 8. Package A: art, assets, lang, data (Sonnet)

### A1. Procedural art
**Files:** `art/gen_item_art.py`, outputs copied into `mc/src/main/resources/assets/cyberblock/textures/...`.
- [ ] `arm_mantis_blades`, `arm_gorilla_arms`, `arm_monowire`, `arm_projectile_launch_system` (32x32, panel lines, neon
  edges); `entity/cyber/kiroshi_eyes`, `subdermal_armor`, `reinforced_tendons` (64x64 skin-layout overlays, mostly
  transparent); GUI sprites `gui/ui/wanted_star`, `wanted_star_empty`, `phone`, `radar_frame` (original pixel art);
  `icon.png` 128x128 for the mod.
**Done:** the script runs twice with byte-identical output; outputs committed.

### A2. Arm models and item definitions (F6)
**Files:** `assets/cyberblock/models/item/<id>_arm.json`, `<id>_arm_out.json` (8 new), `assets/cyberblock/items/<id>.json`
(4 changed).
- [ ] Hand-authored `elements` in the section 1.8 arm frame: Mantis (forearm sheath, blade folded / blade out past the
  fist), Gorilla (knuckle plates, glowing pistons / plates spread), Monowire (wrist spool / glowing wire hanging to
  y −12), PLS (forearm tube / tube opened with a glowing muzzle); `light_emission` on glow faces; the select item
  definitions.
**Done:** every file parses; `CyberAssetsTest` passes.

### A3. Advancements (F11)
**Files:** `data/cyberblock/advancement/*.json` (6 new).
- [ ] Section 1.8 table; verify the `minecraft:tick` and `minecraft:impossible` trigger ids in the 26.3 jar.
**Done:** `gradlew test` (assets test) passes.

### A4. Lang and the assets test
**Files:** `assets/cyberblock/lang/en_us.json`, `test/cyber/CyberAssetsTest.java`.
- [ ] Every section 1.12 key; removals; `CyberAssetsTest` checks all section 1.12 keys, the arm models and textures, the
  select cases, the overlay textures, the GUI sprites, the advancements, and that no file under `assets/minecraft/`
  exists.
**Done:** `gradlew test --tests dev.cyberblock.cyber.CyberAssetsTest` passes.

---

## 9. Package P: protocol, docs and release (Sonnet)

### P1. Protocol
**Files:** `shared/protocol.md`, `tests/test_protocol_md.py`.
- [ ] A "Re-test 3" section with every section 1.1 row, limits, examples (one jsonl line per new type), the pause rule,
  `cam.seat`/honest `fp`, `hud` always false, six binds, `key arms/camo`; VALIDATORS for every new type.
**Done:** `uv run pytest -q tests/test_protocol_md.py` passes.

### P2. Fake host
**Files:** `tools/fakehost.py`, `tests/test_fakehost.py`.
- [ ] `--ui` plays stats, quest with a waypoint, pins, a prompt, a 4-choice hub with selection changes and a timer, a
  subtitle, a ringing call, three notifications, `screen open:"pause"` and `pass:"vendor"` with `cam menu:true`, and
  answers `uiact journalreq/phonereq/atlasreq` with sample replies; `--hacks` answers `hack` with `upload`/`kind` and
  sends `hackspread`; `--seat` sends `seat:true` with `fp` toggling; prints `persp`, `uiact`, `status.pause`, six-key
  `binds`, `key arms/camo`.
**Done:** `uv run pytest -q` passes.

### P3. Player guide (`docs/HOW-TO-PLAY.md`)
- [ ] Section 1/2 split into a **release install** (zip, install.bat, Fabric Installer for 26.3, Fabric API ≥ 0.161.0,
  the dedicated launcher installation of section 1.13, Play Cyberblock button) and a **from source** path.
- [ ] Section 3 key table: Y/L/O, F5 third person, T for calls, Esc/M/J/I/P/H open Minecraft screens, vanilla
  Advancements moved off L.
- [ ] Section 5: the quickhack table (section 1.6 in player words: Minecraft effect, Cyberpunk effect, ganger vs
  pedestrian), "the chip in your hand fires"; cyberware: two OS slots, abilities on L, arm models, Sandevistan screen
  effect and the strength slider; vehicles (Steve rides along); the Minecraft UI tour (radar, quest, waypoint, prompts,
  choices, phone, journal, atlas, pause) and the pass-through menus with their font limitation; knockback tiers.
- [ ] Section 8 troubleshooting: port 25600, Fabric API missing, JVM arguments replaced not appended, `version
  mismatch`, Night City stuck paused (press Esc / F9), ReShade effect off (`fx`), stray Cyberpunk HUD (report the
  `ui:` lines). Section 9: uninstall.bat.
**Done:** consistent with section 1.

### P4. Test plan (`docs/TESTING-PLAN.md`)
- [ ] New top section "Re-test 3 (do this first)" above Re-test 2. One check per item, each with action, expected result
  and grep line (section 1.14): F1 every chip on a ganger and on a pedestrian (table) plus chip-in-hand; F2 radar,
  quest/waypoint, money, wanted, call, SMS, third person on/off; F3 both car cameras and a bike; F4 steer a glide left
  and right; F5 a block on the street still sits on it; F6 four implants first/third person; F7 punch/sprint/gauntlet/
  Gorilla tiers; F8 Y/L/O and rebinding with a clash; F9 deck + Sandevistan; F10 Sandevistan screen effect and the
  slider at 0; F11 release zip install on a clean copy and the Play button; F12 a full walk through door, vendor,
  dialogue, Esc, M, J, I, P, H, death, save: **no Cyberpunk HUD element ever**; F13 prompts and a timed dialogue.
- [ ] The appendix receives the packages' needsInGame lists (integrator).

### P5. Notes and spec
- [ ] `flight-spike.md`: owned yaw (method, evidence, lLastX strip). `cp-mechanics-audit.md` B: the crowd finding and the
  extras. The spec: line 17 reversed (Minecraft UI everywhere, vehicles included), F12 principle paragraph, third person.

### P6. README, CHANGELOG, credits
**Files:** `README.md`, `CHANGELOG.md` (new), `NOTICE.md`, `THIRD-PARTY-NOTICES.md` (new), `LICENSE` (check only),
`docs/media/.gitkeep` (new).
- [ ] README: pitch, non-affiliation and trademark disclaimer (not affiliated with CD PROJEKT RED or Mojang/Microsoft;
  owners of Cyberpunk 2077 and Minecraft required), screenshot placeholders as comments plus a visible "screenshots
  pending" line, Players install (requirements with links: RED4ext, redscript, Codeware, ReShade with add-on support,
  Fabric Installer, Fabric API), Build from source, Keys, Credits, License.
- [ ] CHANGELOG (Keep a Changelog): 1.0.0 with layers 1-6, cyberware, re-tests 1-3; "Planned" (ripperdoc flow and
  eddies, capacity budget, gamepad glide steering).
- [ ] NOTICE: every credit and the disclaimer. THIRD-PARTY-NOTICES: full MIT/BSD-3 texts for universal-modder,
  RED4ext.SDK, nlohmann/json 3.11.3, Java-WebSocket 1.6.0, ReShade add-on headers and ReShade.fxh (copied from
  `third_party/` license files and the dependency jars' licenses, never retyped from memory).
- [ ] Wording: "decompiled 2.31 scripts" in docs becomes "2.31 script API (NativeDB, redscript)"; scrub personal paths
  in docs you own.

### P7. Release packaging and installer
**Files:** `tools/package.py` (new), `tools/install.py`, `release/install.ps1`, `release/install.bat`,
`release/uninstall.bat` (new), `tests/test_package.py`, `tests/test_install_ps1.py`, `tests/test_install.py`, `.gitignore`.
- [ ] Section 1.13 layout, fixed zip timestamps, SHA-256 printed; `install.py` accepts an existing `ReShade.fxh`.
- [ ] `install.ps1` (Windows PowerShell 5.1, no Python): refuses while Cyberpunk runs; finds the game via
  `HKCU\Software\Valve\Steam` SteamPath and `libraryfolders.vdf`, `-Game`, or a folder dropped on install.bat; refuses
  foreign files, backs up saves before the first install, writes `ReShadePreset.ini` only when absent, removes stale
  files, `-Remove`; warns on missing RED4ext, redscript, Codeware, ReShade; explicit `ConvertTo-Json -Depth`.
- [ ] Tests: zip entries equal `payload()` keys plus docs and jar; ps1 parity scenarios through `powershell.exe` on a fake
  game folder (foreign file refused, update, remove keeps a changed file, preset only if absent, stale removal,
  cross-install with install.py).
**Done:** `uv run pytest -q` passes.

### P8. CI and version check
**Files:** `.github/workflows/ci.yml` (new), `tests/test_version.py` (new), `pyproject.toml`, `uv.lock`.
- [ ] Jobs: python (windows-latest, setup-uv, `uv sync --locked`, pytest, vectors regenerate + `git diff --exit-code`),
  mod (wrapper-validation, temurin 25, setup-gradle, `gradlew.bat test build`, upload jar), dll (windows-2022,
  `submodules: true` non-recursive, `cmake -A x64`, Release build, `cb_tests.exe`, `ctest` for fx_check, upload DLL),
  package (downloads, `tools.package`, uploads the zip; on tags only uploads). `permissions: contents: read`.
  compile_check is documented as a local release gate.
- [ ] `test_version.py` (section 1.13); pyproject 1.0.0 and `uv lock`.
**Done:** `uv run pytest -q` passes; each CI step run locally where possible (listed in the report).

---

## 10. Integration (integrator, after all packages report)

1. Merge in this order: **D** (native_reg, vectors), **U**, **S**, **M** (Bridge, StatusReporter, AllowedToast,
   fabric.mod.json), **C**, **W**, **A**, **P**. Ownership is disjoint, so merges are clean. Then grep that no
   `zz_stub_*` file and no uncommitted stand-in is left, and that `git ls-files` has nothing under `assets/minecraft/`,
   `net/` or `com/mojang`.
2. In an integration worktree run every suite: `uv run pytest -q`; `gradlew.bat test build`; cmake build + `cb_tests.exe`
   + `ctest` (fx_check); `compile_check.py`. Fix integration-only breaks in the owning package's files and note them.
3. Add any lang keys reported as deviations; update HOW-TO-PLAY's key table and the protocol doc if a package deviated.
4. Paste every package's needsInGame list into the TESTING-PLAN appendix (under "Re-test 3").
5. Build the release zip with `uv run python -m tools.package` into `dist/` (git-ignored), and list its SHA-256.
6. Install with the integrator install command (the Cyberpunk folder is touched only there) and copy the mod jar as
   before.
7. Tell the user: the TESTING-PLAN "Re-test 3" section; that version 1.0.0 is set in files but **not tagged** until the
   re-test passes; that no remote was created and nothing was pushed; and offer (do not run) a `git filter-repo
   --mailmap` to a GitHub noreply address before the first push, since all commits carry their email.

## 11. Coverage (user report → tasks)

| Item | Tasks |
|---|---|
| F1 quickhacks all the same | C1 (wheel, main cause), C2 + C3 (Minecraft effects, ping outlines), D1 (natives), S1 + S2 (kind, upload timing, PingSquad, own contagion spread, crowd extras), M1 (ping contract), M6 (cobweb events), A4 (lang), P1, P2, P3, P4 |
| F2 Minecraft UI for the rest of the game, third person | U2 + U3 (data), W1 + W2 + W3 + W4 + W5 (radar, quest, waypoint, money, wanted, calls, messages, notifications, screens), D5 + S5 + M4 (third person), D6 (phone key, pause), P1, P3, P4 |
| F3 Steve visible in vehicles | D4 (seat, fp, hud), S4 (hips, TPP), U1 (V's body hidden in vehicles), M2 (render, seated pose, hand, HUD), W2 (vehicle HUD), P1, P4 |
| F4 glide steering | D3 (owned yaw), M6 (log), P4, P5 |
| F5 keep the ground alignment | no change; M6 regression test, P4 check |
| F6 3D arm cyberware | A1 + A2 (textures, models), C6 (render first/third person), A4, P3 |
| F7 knockback flings | M3 (Minecraft impulse), S3 (Cyberpunk tiers), P1 (impulse meaning), P4 |
| F8 separate binds Y/L/O | D2 (DLL, vectors), C4 (Minecraft), A4, P1, P3, P4 |
| F9 two OS slots | C5, A4, P3, P4 |
| F10 Sandevistan VFX | D7 (ReShade effect, fx_check), S6 (hook), C7 (Minecraft flourish), A4, P3 |
| F11 more content and publish-ready | C6 (arms abilities), C7 (cyber look, camo shimmer, advancements), A1 + A3, M5 (launcher, version), D8 (version), S6 (compile_check path), P6 + P7 + P8 (README, CHANGELOG, credits, packaging, installer, CI), integration step 7 (no push) |
| F12 old Cyberpunk UI never appears; every UI element Minecraftified | U1 (ink layers, no re-show), U4 (notifications, saves), U5 (menus → Minecraft screens), U6 (restyle + pass-through), D6 (cb_menu), W1-W6, M1 (toasts, pause status), P3, P4 |
| F13 Minecraft-styled prompts and choices | U3 (prompt/choice data), U2 (builders), W3 (PromptHud, ChoicesHud), P4 |
