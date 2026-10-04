# Cyberblock Multiplayer Megaplan: Server Multiplayer, Interactive Night City, Roles and Modes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan phase by phase, task by task. Steps use checkbox (`- [ ]`) syntax
> for tracking. **This plan is executed one phase at a time; every phase ends with a STOP for the user's in-game test and
> the next phase starts only after the user says "go".**

**Goal:** build everything in the user's mega brief of 2026-10-04 (`out/multiplayer-mega-brief.md`; local only, kept out
of git because it contains private paths). In one sentence: an Apex-hosted Fabric 26.3 Minecraft world that is also a
live, growing, block-built map of Night City, where Cyberpunk-linked players (hosts) and Minecraft-only friends (guests)
play together, and everything either side does reaches the other through the guest game's own server. **Rule of the
whole mod: every Cyberpunk object appears as its Minecraft version and is used the Minecraft way.** Guests need the
Cyberblock mod and resource pack (the "Full" tier). A no-install "Lite" tier is v2: the data layer is designed so it is
possible (section 1.7), nothing is built for it.

The brief's phases, in its order, each a section of this plan:

| Phase | Brief title | Plan section |
|---|---|---|
| **Q** | Quick wins before the server work: Q1 pickaxe survival mining, Q2 auto-mount on block stairs, Q3 third-person polish, Q4 UI coverage, Q5 creative can break the outline blocks | 3 |
| **0** | Feasibility (a) to (h), each with a method and a written fallback; Apex panel facts from the user | 4 |
| **1** | Server/client split: relay, per-host routing, handshake, security, singleplayer through the same path | 5 |
| **2** | Apex deployment: backup, jar swap, world upload, whitelist, modpack, resource pack, two-click join, friend test | 6 |
| **3** | Night City replica: visible material blocks, saved world, web map, figures, time and weather, neon, guest actions, Offline Builds, Meet Up | 7 |
| **4** | Device layer | 8 |
| **5** | Figures, skins and cyber skins | 9 |
| **6** | Redstone Hacking, Breach Protocol, the interaction map and its screens | 10 |
| **7** | NPC interaction layer, the Afterlife hub, the Holo-Table | 11 |
| **8** | Roles and modes: Hacker in the Van, Overlord, matches/PvP/Gang Wars, hacking players, Server Heat and Max-Tac, Downed and revive, spectator mode | 12 |
| **9** | Content-as-data finish, UI gap work, performance pass, settings screen, backups, docs, release notes | 13 |

Section 14 is the dependency map and the effort estimate, section 15 maps every brief item to tasks, section 16 lists
what this plan does **not** build (the brief's section 6).

**Where we start (repo state on 2026-10-04):**
- `main` at `ba2c95b`: version 1.0.0 is set in the files and not tagged; layers 1 to 6, re-tests 2 and 3, the in-game
  fixes 3 and the Atlas are merged; `art/skins/v_male_classic.png` and `art/skins/v_female_lucy_classic_template.png`
  (64 x 64, classic layout) are committed.
- **Branch `feat/survey-fast`** (Atlas survey speed) is in progress in its own worktree. Until it is merged, it owns
  `cp/plugin/src/main.cpp`, `cp/plugin/src/survey.cpp`, `survey.hpp`, `survey_game.cpp`, `survey_game.hpp`,
  `cp/plugin/tests/test_survey.cpp`, `cp/scripts/Cyberblock/Probes.reds`, `Survey.reds` and `SurveyNatives.reds`. No
  package of this plan edits those files before the merge (rule 0.12).
- **Today's architecture is single-player by construction:** one JVM shares static state between the client and the
  integrated server (`Cyberblock.active`, `Cyberblock.events`, the `Bridge.host*` fields). The client's `HostLink` is the
  WebSocket server on `127.0.0.1:25600`; server-side code (`WorldBridge`, `ProxyManager`, `MobWar`, `HostDamage`,
  `PlayerEffects`, `Projectiles`, `ExplosionEvents`, `ProxyEntity`, the `cyber/` track and several mixins) writes straight
  into `Cyberblock.events`, which only works because the integrated server lives in the client's JVM. Phase 1 replaces
  this with per-host sessions and custom payloads. The spec's non-goal "Online play of any kind"
  (`docs/superpowers/specs/2026-10-02-minecraft-cyberpunk-passthrough-design.md` section 1) is reversed by this plan; the
  Phase 1 docs package amends the spec.

**Architecture after Phase 1:**
```
HOST PC                                                                    MINECRAFT SERVER (Apex, or the integrated server)
Cyberpunk 2077 + Cyberblock.dll                host's Minecraft client      HostSessions (one per host, keyed by player UUID)
  link: ws 127.0.0.1:25600  ───────────────►  HostLink ─┬─ LOCAL: cam, key, hud, view, UI data, frame export (never leaves the PC)
                            ◄───────────────            └─ Relay ── cyberblock:up ──────►  session → WorldBridge, ProxyManager, Hacking, ...
                                                           Relay ◄── cyberblock:down ─────  routed back to the host that owns the Cyberpunk game
                                                                                          guests' clients ◄── vanilla block and entity sync
                                                                                                          ◄── cyberblock:roster, typed payloads
```
- **Authorities:** the server owns the shared Minecraft world (blocks, items, mobs, players, perks, RAM, matches, Heat).
  Each Cyberpunk game owns its own Night City (NPCs, devices, V's body and camera). Only the host's own Minecraft client
  ever talks to that host's DLL.
- **The guest game's server is the netcode.** There is no separate relay service. Singleplayer is the integrated server
  reached through exactly the same payloads, so one code path serves singleplayer, LAN and Apex.
- **Content is data** (section 1.7) from Phase 1 on: dialogue, contracts, Breach daemons, events, skin mappings, shop lists,
  spawn points, device mappings and heat tiers are data files, hot-reloadable with `/reload`.

**Sources (read before starting a phase):** this plan; the brief (local); `shared/protocol.md`; `README.md`,
`CHANGELOG.md`, `docs/HOW-TO-PLAY.md`, `docs/TESTING-PLAN.md`; the spec; the earlier plans (their contracts stay valid
unless section 1 changes them); `docs/notes/collision-spike.md` (box filters, probe side effects),
`docs/notes/cp-mechanics-audit.md`, `docs/notes/flight-spike.md`, `docs/notes/layer6-cp.md`,
`docs/notes/puppet-spike.md`. References outside the repo, read only and never copied: the 2.31 scripts the compile
checker uses, NativeDB, the installed Codeware sources, the Minecraft 26.3 and Fabric API sources jars.

---

## 0. Ground rules (every phase, every package)

**0.1 How a phase runs (Workflows).**
- First the integrator reads the repo state (`git status`, `git log`, open worktrees; a polish or survey build may still
  be merging) and rebases nothing that is not its own.
- Packages run in parallel, each in its own worktree on its own branch: `git worktree add <repo>-wt\mp-<phase>-<pkg>
  -b mp/<phase>-<pkg> main` (for example `mp/q-b`, `mp/1-n`). `<repo>` is the main checkout.
- **Model choice:** Sonnet for mechanical packages (docs, protocol text, fake host modes, lang, data files, procedural art
  scripts, packaging), Opus for hard or novel work (the relay, sessions, replica, devices, rendering, Breach, Holo-Table,
  modes). The packages table of each phase fixes the model.
- **Every package gets an Opus reviewer and an Opus fixer** before it is merged: the reviewer checks the package against
  this plan (contracts, ownership, tests, done criteria, rule 0.5 hygiene); the fixer applies the findings in the same
  branch.
- **The integrator** merges in the order the phase lists, then runs **all** suites in a temporary integration worktree
  (rule 0.9) and fixes integration-only breaks in the owning package's files (noted in the report).
- **After each phase:** install into the game only if Cyberpunk is closed (`Get-Process Cyberpunk2077 -ErrorAction
  SilentlyContinue` is empty; never touch the process otherwise); append a **"Re-test <phase>"** section at the top of
  `docs/TESTING-PLAN.md`; update `docs/HOW-TO-PLAY.md` and `CHANGELOG.md`; give the user a **one-screen "what to try"
  list** and a short **descopes and risks** note; then **STOP** for the user's in-game test.
- After Phase 0 and after Phase 2 the integrator explicitly asks the user for the Apex panel facts and actions (sections
  4.2 and 6.3).

**0.2 Gradle and the user's game.** Never run Gradle (or anything that writes `mc/build`, `mc/.gradle` or `mc/run`) in
`<repo>\mc`: the user may be playing Minecraft from it. Build and run only in worktrees.

**0.3 Cyberpunk is off-limits.** Never launch Cyberpunk. The Cyberpunk folder is read-only, except for the integrator's
install step while the game is closed. Never kill a process you did not start.

**0.4 Commits.**
- Commit after every task, with explicit paths (`git add <paths>`, never `git add -A`).
- The message ends with a blank line and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Commits use the GitHub noreply address. Before the first commit in a worktree, `git config user.email` must end in
  `@users.noreply.github.com`; if it does not, stop and report (never change the user's global config).
- **DO NOT PUSH.** No tags, no releases, no remote changes, until the user says "push".

**0.5 Public repo hygiene (the repo is public: rodthegod2/cyberblock).**
- Never commit secrets, game files, decompiled sources, unpacked jars, the user's Apex FTP password, the FTP host or user
  name, the server address, or private paths. Paths in tracked files use placeholders: `<repo>`, `%USERPROFILE%`,
  `%APPDATA%`, `%LOCALAPPDATA%`, `<Cyberpunk 2077>`, `<server backup folder>`.
- `git status` before every commit; a stray `assets/minecraft/...`, `net/...`, `com/mojang/...`, `*.archive`, `.ent`,
  `.reds` copied from the game, or an unpacked jar is a stop-and-clean event.
- Phase Q adds `tests/test_hygiene.py` (task Q-P2); from then on every suite run checks it, and the integrator also greps
  the phase diff (`git diff main...<branch>`) for drive-letter paths (`[A-Z]:\\`), the user's Windows account name and
  any `*_PASS` assignment.

**0.6 The Apex FTP password is never printed or stored.**
- Never print it, echo it, log it, write it to any file (repo, scratchpad, staging, backups), put it on a command line, or
  paste it in chat or a report. **Never use an inline `$env:APEX_FTP_PASS = '...'` assignment** (that puts it in the
  transcript); the user's private server notes show that pattern: do not copy it.
- Tools read it only from the process environment (`APEX_FTP_PASS`) or, when interactive, from a hidden `getpass`
  prompt the **user** types into. Agents may only test whether it is set (`if ($env:APEX_FTP_PASS) {'set'} else {'not set'}`).
- When it is not set in the agent's environment, the agent asks the user to run the command in their own terminal
  (or to set a user environment variable themselves and restart the session). The user's private server-tools folder
  (outside the repo) is read only for its method, never copied.

**0.7 Minecraft test clients and test servers.**
- Each package uses only its own client ports, frame names and server ports (section 1.15). Never 25600, never
  `Local\CyberblockFrame`, never the user's real server.
- Client: `gradlew runClient -Pcyberblock.port=<port> "-Pcyberblock.frame=<frame>" -Pcyberblock.runDir=<dir>
  --args="--username <CbHost1|CbGuest1>"` (the `runDir` and `--username` support arrive in task 1-L1; before that one
  client per worktree).
- Dedicated test server: `gradlew runServer -Pcyberblock.serverPort=<port>` with `online-mode=false`, `server-ip=127.0.0.1`
  (task 1-T2 writes the template). Offline-mode servers are bound to 127.0.0.1 only.
- **Commit check:** launch a client or server only when system commit is under 60 GB
  (`Get-CimInstance Win32_OperatingSystem`: `TotalVirtualMemorySize - FreeVirtualMemory`; the PC has a fixed 16 GB page
  file). A client needs about 3 GB of commit, a server 2.5 GB. Close everything you launched.
- UI and render packages take screenshots (fake host driving the client where useful) and list them in the report.

**0.8 Never guess an API.**
- Cyberpunk: NativeDB, the Codeware sources, the 2.31 scripts the compile checker uses, TweakDB (`tdb_has`); prove every
  new call with `uv run python cp/scripts/compile_check.py`. Cite "2.31 script API (NativeDB, redscript)" in headers.
- RED4ext: `third_party/RED4ext.SDK`. ReShade: `third_party/reshade`; every shader change passes `fx_check`.
- Minecraft 26.3 names from the Loom sources jar or `javap` on the jars in your worktree's `mc/.gradle/loom-cache`
  (unpack outside the repo only, delete when done). Fabric API names (networking, attachments, resource reload, commands,
  HUD) from the Fabric API sources jar.
- Anything only checkable in game goes to `needsInGame` with a concrete check (an in-game action or CET console line and
  the expected log line).

**0.9 Test suites (the integrator runs all of them; packages run the ones they touch).**
- `uv run pytest -q`
- in `mc/` of the worktree: `gradlew.bat test build` (`JAVA_HOME` = `%LOCALAPPDATA%\Cyberblock\jdk-25.0.4.1+1`)
- `"%ProgramFiles%\CMake\bin\cmake.exe" --build cp/plugin/build --config Release`, then
  `cp\plugin\build\Release\cb_tests.exe` and `ctest -C Release` (runs `fx_check`)
- `uv run python cp/scripts/compile_check.py` (redscript against the installed 2.31 scripts; a local gate)
- `uv run python -m tools.gen_vectors` (no diff expected unless the phase changes vectors)
- from Phase 1: `uv run python -m tools.mpcheck --all` (dedicated server + two offline clients + fake hosts, task 1-T2)
- from Phase 1: `uv run python -m tools.content_check` (every content file, task 1-T3)

**0.10 Descope rule.** If a Phase 0 check fails, apply its written fallback (section 4), say so in the phase report and
the user's descopes note, and continue. If a later task proves impossible, keep the contract signature compiling (a
logging no-op), report `blocked` with the evidence, apply the nearest written fallback and move on. Never stall.

**0.11 Original art and text only.** Procedural scripts in `art/` (like `art/gen_item_art.py`) or hand-authored JSON;
deterministic (the script run twice gives byte-identical output). The user accepts the two fan skins of section 1.12 for
this passion project; still no game assets of any kind. Dialogue lines, rumours, contract texts and event texts are
original (content files), never copied game text. The Minecraft look comes from runtime use of the player's own vanilla
sprites and font, never from copies.

**0.12 Branches owned by others.** `feat/survey-fast` owns its files (listed above) until it is merged. A phase that
needs one of them starts after the merge; if it has not merged, the package defers that task to the end and the
integrator rebases it after the merge. Nobody edits `out/`, `third_party/` or older plans.

**0.13 Building against another package's contract.** Create the other package's file or members in your worktree with
the exact section 1 content and never commit it; new stand-ins are named `zz_stub_*` where a name is free. The
integrator greps that none is left after merging.

**0.14 Natives rule.** A native is declared exactly once, in the `.reds` file of the package whose scripts call it, and
implemented where section 1.8 says. **Lang rule:** each phase names one owner of `en_us.json`; code uses
`Component.translatableWithFallback(key, "<English>")` for new keys, so a missing key never shows raw; a key the owner
lacks goes into `contractDeviations`.

**0.15 Report format** (each package, at the end):
`{package, commits[], done[], notDone[], blocked[], needsInGame[], contractDeviations[], screenshots[], risks[]}`.
A contract deviation is allowed only when the contract is impossible as written; keep the signature and explain.

**0.16 Nobody is awake inside a phase.** Never wait for input during a phase's build: if blocked, record it, keep the code
compiling and move on. The only waits are the phase-end STOP and the user actions the plan names (Apex facts and panel
clicks, the friend test).

---

## 1. Fixed contracts (change only through the integrator)

### 1.1 Terms
- **Host:** a player whose Minecraft client has a live link to its own Cyberblock.dll (a Cyberpunk game) **and** whose
  handshake granted the host role (section 1.5). **Guest:** any other player (Minecraft only). **Linked player:** a host
  (the server flags it to every client: cyber skin, `[V]` tag, list marker).
- **Session:** the server's state for one host, keyed by the player's UUID (section 1.4). **Active session:** the host's
  Cyberpunk is On and streaming (`hstate.active`). **Primary host:** the earliest active session; it drives server-wide
  time of day and weather (Phase 3).
- **Owner:** the session whose Cyberpunk game a Night City id (NPC, vehicle, device) belongs to. Ids are only meaningful
  together with their owner: the server keys them as `(owner UUID, id)`.
- **LOCAL:** a wire message that stays between the DLL and its own host's Minecraft client and never leaves the PC.
  **UP:** sent by the DLL, relayed by the host's client to the server as a `cyberblock:up` payload. **DOWN:** built on
  the server, sent to the owner's client as `cyberblock:down`, forwarded verbatim to its DLL. **BOTH:** used locally and
  also relayed UP.

### 1.2 Every existing wire message, LOCAL or RELAYED
The wire between the DLL and the client (`shared/protocol.md`, `proto` 1) does **not** change shape for the relay: the
client forwards UP messages as they are (JSON text) and forwards DOWN messages to the DLL unchanged. The server runs the
same validators the client ran before (they move with the code), plus the envelope checks of section 1.6.

**Cyberpunk → Minecraft:**

| `t` | Class | After Phase 1, handled by | Notes |
|---|---|---|---|
| `hello` | BOTH | client handshake (LOCAL); UP as the "host session (re)started" notice with the DLL's new `ver` | the server then sends `align`, `perks`, `cyberware`, `effect` set and the block resync DOWN (the existing "after each host hello" order) |
| `cam` | LOCAL | `HostState`, `PlayerSync`, `FrameExporter` (client) | the server never sees it; it gets the derived `hstate` below |
| `ground`, `unground`, `clear` | UP | `WorldBridge` per session (server) | the client splits arrays to fit the payload cap (section 1.3) |
| `state` | BOTH | client HUD/vehicle rules; session `active`, `vehicle` | |
| `view`, `hud` | LOCAL | client | |
| `aim` | BOTH | client pick limit (`HostState.aimLimit`); session aim distance for the server-side scan pick and the host's reach check | |
| `key`, `slot`, `scroll`, `cursor`, `click`, `char`, `skey` | LOCAL | `ClientInput`, cyber key listeners | actions they trigger go to the server through vanilla packets or the typed payloads below |
| `dmg` | UP | `HostDamage` for the session's player | |
| `npcs` | BOTH | client radar (LOCAL); `ProxyManager` per session (UP) | Phase 3 makes the proxies visible figures for guests |
| `mobdmg`, `pcells`, `unlead`, `killed` | UP | `MobWar`, `WorldBridge` temp cells, `ProxyManager`, `Kills` | |
| `scaninfo`, `hackresult`, `hackspread` | UP | `Hacking` (server), routed to the player who asked (request id map, section 1.4) | |
| `look` | LOCAL | unused | |
| `stats` | BOTH | client HUD (LOCAL); UP at ≤ 1 Hz as the session's `money`, `wanted`, `district`, `time` | shops paid in eddies, Heat, time of day |
| `quest`, `pins`, `prompt`, `choices`, `sub`, `call`, `notify`, `screen`, `reply` | LOCAL | client UI (`UiLink`) | the host's own Night City UI |
| `terrain` | LOCAL until Phase 3, then BOTH | `AtlasStore` (client); Phase 3: UP into the server's shared Night City map | Holo-Table and web map |
| `surveystate` | LOCAL | `SurveyClient` | |
| `cmd` | LOCAL, test tools only | `WorldBridge.command` in a dev client | never relayed; the relay drops it; a dedicated server refuses it |
| frame export (`Local\CyberblockFrame`) | LOCAL | `FrameExporter` | |

**Minecraft → Cyberpunk:**

| `t` | Class | Built by (after Phase 1) | Notes |
|---|---|---|---|
| `hello` | LOCAL | client `HostLink` | |
| `status`, `binds`, `uiact`, `persp`, `flight` | LOCAL | client (`StatusReporter`, `CyberKeys`, `UiLink`, `ClientInput`, `Flight`) | the client owns Steve's glide physics |
| `survey` | LOCAL | client `SurveyClient` | `/cyberblock survey` becomes a server command that asks the issuing host's client over `cyberblock:down` (`t:"survey_cmd"`, handled by the client, never forwarded to the DLL) |
| `block`, `blocks`, `align` | DOWN | `WorldBridge` per session | one ground alignment `k` per world; every host uses it |
| `explosion`, `knock` | DOWN | `ExplosionEvents`, wind charges: to every active session whose V is within 128 m | subject to the owner's `explosions` toggle when a guest caused it |
| `hit`, `ignite`, `lead`, `pull` | DOWN | `ProxyManager`/`ProxyEntity`: to the owner of the proxy | subject to the owner's `damage` toggle when a guest caused it |
| `mobs`, `proj`, `teleport`, `effect` | DOWN | `MobWar`, `Projectiles`, `ThrownEnderpearlMixin`, `PlayerEffects` for that host's player | |
| `scan`, `hack` | DOWN | `Hacking`: to the owner of the target (a guest's request too) | `scan` with `target:null` is only valid for the host's own game; owner's `hacks` toggle |
| `perks`, `cyberware`, `cyberfx` | DOWN | `PerkShop`, `Ripperdoc`, `CyberwareEffects` for that host | `perkfx` stays superseded (never sent) |
| `pteleport` | DOWN | `ServerPlayerMixin` (`{"t":"pteleport","pos":[x,y,z]}`, Steve teleported by a command) | **undocumented today: Phase 1 documents it** in `shared/protocol.md` |

**New in Phase 1 (contract):**
- UP `hstate`: `{t:"hstate", active, vehicle, air, cr, held, fly, launch, seat, lv, feetCpZ}` (booleans; `lv` 0..63;
  `feetCpZ` finite or absent), built by the client from `cam`/`state`/`Flight` at 5 Hz and on change. It replaces the
  JVM-shared `Bridge.hostAirborne`, `hostCrouching`, `hostHeld`, `hostInVehicle`, `hostFeetCpZ`, `hostStands` and
  `Cyberblock.launching` on the server.
- Client → DLL `session`: `{t:"session", state:"none"|"host"|"guest"|"refused", reason ≤ 128, perms:{explosions,
  damage, hacks, devices, spawns}}` after every handshake result, server disconnect and reconnect. On `none`, `guest`
  or `refused` the DLL and scripts clean Cyberpunk state exactly as on a link drop (collision boxes reset, puppets
  despawned, tethers released, Minecraft pause released, survey stopped, parked V released) while keeping the WebSocket.
- DLL `hello` gains `ver` (the DLL version, ≤ 32 chars), as Minecraft's `hello` already has.

> **Amendment 2026-10-04 (Phase 3, `npcs` death flag; 3F review fix, for the Phase 3 integrator to confirm).** `killed` is
> sent only for deaths V (Steve) caused, so 3-F1's wrecks and bodies need a death signal that does not depend on who
> caused it. `npcs` entries gain an optional `dead:true`: an NPC that the previous list sent alive and that is now dead or
> defeated, or a vehicle now destroyed, is listed once more after the living (sharing the cap of 48) with `dead:true` at
> its current position; the key is absent otherwise. `Cyberblock_SubmitNpcs` gains a parallel `dead: array<Bool>`
> (`Npcs.reds`, `natives.cpp`, `world::make_npcs`). The server's `ProxyManager` buries a live proxy on `dead:true` (a body
> for 3 s, a wreck for 5 s) and never spawns a new one from a dead entry; `killed` stays the XP path and lays a body only
> when the list's flag has not already. The server log line `figures: <vehicle|person> <id> died (<wreck|body> for N s;
> from the <npcs dead flag|killed>)` shows which path fired. Fake hosts that model a destroyed car send it once with
> `dead:true` before it leaves the list. Phase 5's `anim` bit 32 (dead) is the same state and comes on the same entry.

### 1.3 The Minecraft custom-payload protocol (client ↔ server)
Registered with Fabric's payload registry and play networking (exact 26.3 class and method names verified in task
1-N1 from the Fabric API sources). Every payload is a record with a `StreamCodec`; strings are UTF-8 with a length cap.
Receivers run on the server thread (server) or are moved to the client thread with `execute` (client).

| Id | Direction | Fields | When |
|---|---|---|---|
| `cyberblock:hs` | C2S | `proto` int (1), `mod` str ≤ 32 (mod version), `dll` str ≤ 32 (`""` = no DLL linked), `wantHost` bool | on join; again when the DLL link comes up, drops, or its `ver` changes |
| `cyberblock:hs_ack` | S2C | `proto` int, `server` str ≤ 32 (server mod version), `ok` bool, `role` ∈ `host, guest`, `reason` str ≤ 256 (shown to the player when not empty), `perms` int bitmask (1 explosions, 2 damage, 4 hacks, 8 devices, 16 spawns), `maxHosts` int, `features` int bitmask (phase features the server has: 1 replica, 2 devices, 4 figures, 8 hacking, 16 hub, 32 modes) | answer to every `hs` |
| `cyberblock:up` | C2S | `seq` int (per connection, +1 each), `t` str ≤ 16, `json` str ≤ 30000 bytes | one UP message (section 1.2) |
| `cyberblock:down` | S2C | `json` str ≤ 1 000 000 bytes | one DOWN message for this client's DLL (or a client-only `survey_cmd`) |
| `cyberblock:roster` | S2C | `list:[{uuid, name, linked, body ∈ male/female, team int (-1 none), pvp, downed, hidden}]` ≤ 200 | on join and on every change |
| `cyberblock:perms` | C2S | `perms` int bitmask, `devices` int bitmask (Phase 4 device types, section 1.11) | a host changes what guests may do to its game |
| `cyberblock:content` | S2C | `kind` str ≤ 32, `json` str ≤ 1 000 000 | content a client needs (skin map, vehicle map, palettes) on join and after `/reload` |
| `cyberblock:act` | C2S | `t` str ≤ 16, `json` str ≤ 8000 | a player's request that the server routes to an owner: `scan`, `hack`, `devact`, `meetup`, `spawn`, `breach`, ... (each phase lists its `t`) |
| `cyberblock:ui` | S2C | `t` str ≤ 16, `json` str ≤ 1 000 000 | server data for a screen: shop, breach grid, Holo-Table pins, match state, Heat, feed frames |

- **Size caps:** vanilla limits serverbound custom payloads (32767 bytes in recent versions; task 1-N1 confirms the 26.3
  number and adjusts the 30000 cap below it). The client relay splits `ground`, `unground` and `terrain` arrays (whole
  quadruples) and `npcs` lists into several UP messages under the cap; any other UP message over the cap is dropped and
  counted.
- **Order:** UP messages of one host arrive in `seq` order (one TCP connection); the server processes them in order on
  the server thread. The "`align` immediately followed by `blocks reset:true`" rule of re-test 2 is kept by building both
  in one server tick into one session's DOWN queue.
- **Down queue:** per session, bounded at 2048 messages; the newest `mobs` and `proj` replace a queued one of the same
  type; past the bound the oldest droppable message goes and a `blocks` resync is scheduled. A client that is not the
  session's host ignores `down` (counted).

### 1.4 Per-host session model (server)
```java
package dev.cyberblock.net;                       // owned by package 1-N
public final class HostSessions {                 // server thread only
    public static @Nullable HostSession of(UUID player);
    public static @Nullable HostSession of(ServerPlayer player);
    public static Collection<HostSession> all();      // every session (linked or active)
    public static Collection<HostSession> active();   // hstate.active
    public static @Nullable HostSession primary();    // earliest active session
    public static @Nullable HostSession nearestActive(ServerLevel level, Vec3 pos, double radius);
    public static void listen(String t, BiConsumer<HostSession, JsonObject> handler); // UP messages, server thread, registration order
    public static void onStart(Consumer<HostSession> l);  // after each host hello (the resync point)
    public static void onEnd(Consumer<HostSession> l);    // disconnect, DLL link lost, role revoked, server stop
}
public final class HostSession {
    public UUID uuid(); public ServerPlayer player(); public State state();   // NONE, LINKED, ACTIVE
    public boolean active(), vehicle(), airborne(), crouching(), held(), flying(), launching(), seated(), stands();
    public double feetCpZ(); public double aim(); public int levelK();
    public long money(); public int wanted(); public String district(); public int[] time(); // from stats (UP, 1 Hz)
    public Permissions perms();                       // what guests may do to this host's game
    public void send(String json);                    // DOWN to this host's DLL (dropped unless LINKED or ACTIVE)
    public int newRequest(UUID asker);                // a request id routed back to the asker (scan, hack, devact, spawn)
    public @Nullable UUID asker(int requestId);       // remembers the last 256 ids
}
```
- **Keys:** sessions are keyed by player UUID. Night City ids are scoped by owner: `ProxyEntity` stores its owner UUID
  next to the Cyberpunk id, and every lookup (`Bridge.proxyById`, hit routing, scan pick) takes the owner. Two hosts can
  never collide on an id.
- **Lifecycle:** `NONE` (a player without a granted host role) → `LINKED` (`hs_ack role:host` and a host `hello` UP) →
  `ACTIVE` (`hstate.active`) → back to `LINKED` when Cyberpunk goes Off → **end** on disconnect, a DLL link loss
  (`hs` with `dll:""`), a role revoke or server stop. On end the server removes that session's outline barriers, temp
  cells, proxies and figures, mob targets and tethers, forgets its pending requests and logs `session: <name> ended
  (<reason>)`. Saved replica blocks (Phase 3) stay.
- **Several hosts:** the model is multi-host from Phase 1; `maxHosts` (server config, section 1.13) is **1** until
  Phase 3's multi-host check passes, then it is raised (default 4). A host over the limit joins as a guest with the reason
  "Cyberblock: another Cyberpunk host is already active (max N)".
- **Statics go away:** `Cyberblock.events` and the server reads of `Cyberblock.active` and `Bridge.host*` are removed from
  the `main` source set (a compile error finds every caller). Client code that talks to its own DLL uses
  `dev.cyberblock.client.HostLink.send(String)`; server code uses `HostSession.send`. Code that runs on both sides (for
  example `PlayerMixin`) asks `Cyberblock.hostActive(Player)`, which uses the session on the server and the client's own
  `HostState` on the client (a `Predicate<Player>` the client entrypoint installs).
- **Singleplayer** goes through exactly the same path: the integrated server receives the local client's payloads over
  the in-memory connection. Opening the world to LAN makes it a small multiplayer server with no other change.

### 1.5 Handshake and versioning
1. On join the client sends `hs` (its mod version, its DLL's `ver` when a DLL is linked, else `""`).
2. The server answers `hs_ack`:
   - `proto` differs, or the mod versions differ in major.minor → `ok:false`, the server disconnects the player with
     "Cyberblock version mismatch: server X, your mod Y. Update from the modpack." (kept on the disconnect screen).
   - A DLL is linked and its `ver` differs from the client's mod version → `ok:true, role:guest`, reason
     "Cyberblock: your Cyberpunk plugin is X but your mod is Y; you can play as a guest. Reinstall both from the same
     release." The client sends `session state:"refused"` with that reason to the DLL, whose scripts show it.
   - Host role refused for another reason (not whitelisted, not in `hosts`, `maxHosts` reached, server `allowHosts` false)
     → `role:guest` with the reason.
   - Otherwise `role:host` (when `wantHost`) or `role:guest`.
3. **No `hs` within 10 s:** the client has no Cyberblock mod. With `requireMod:true` (default; the Full tier) the server
   disconnects it with "This server needs the Cyberblock modpack: <how-to link from server.json>". `requireMod:false` is
   reserved for the v2 Lite tier and is not supported by this plan beyond not crashing.
4. The client re-sends `hs` whenever its DLL link comes up, drops, or reports another `ver`. A role change is answered
   with a new `hs_ack`.
5. **Reconnect:** when the server connection drops (not a kick for version or whitelist), the client shows "Reconnecting
   to <server> in N s" with Cancel and retries after 5, 10, 20, 40 and then every 60 s for 10 minutes. Meanwhile it sends
   `session state:"none"` to its DLL so Cyberpunk is cleaned (section 1.2).
6. Log lines: server `handshake: <name> mod X dll Y -> host|guest (<reason>)`; client `handshake: server X -> <role>`.

### 1.6 Security, rate limits and host permissions
- **Who may host:** only players on the server whitelist (`white-list=true` is required on a dedicated server; the
  singleplayer or LAN owner always may), and, when `hosts` in the server config is not empty, only the players listed
  there. **Join password** (optional, `joinPassword` in section 1.13): a player who has not passed it is locked (held in
  place, no interaction, chat, damage or command but `/login`) and its host link waits as a guest with "Cyberblock: type
  /login <password> first; your Cyberpunk link starts right after that"; the right password re-runs its handshake at once.
  A player who passed it counts as whitelisted for hosting (the `hosts` list still applies). Five wrong tries or 120 s
  without one kick; verified UUIDs are kept per world with a salted fingerprint of the password, and a new password locks
  everyone again. Guests can never send `up`: an `up` from a player without an active host role is dropped and counted,
  and 50 of them within a minute kick the player.
- **Envelope checks on every `up`** (server, before any handler): `t` is one of the UP types of section 1.2; `json`
  parses and its `t` equals the envelope `t`; size within the cap; the per-type token bucket below has a token; for types
  with positions, every position is within the session's **sanity radius** of the host's player (160 blocks
  horizontally; `terrain` 400 blocks because survey stops cover 220 m squares); replies (`scaninfo`, `hackresult`,
  `hackspread`, later `devresult`, `spawnresult`) must carry a request id the session issued. A failing message or entry
  is dropped and counted; counters are logged every 5 s (`relay: <name> dropped <t> x N (<why>)`).

  | `t` | tokens per second (burst) | | `t` | tokens per second (burst) |
  |---|---|---|---|---|
  | `ground`, `unground`, `clear` | 40 (120), ≤ 20000 cells/s | | `scaninfo`, `hackresult`, `hackspread` | 40 (80) |
  | `npcs` | 15 (30) | | `killed`, `unlead` | 10 (20) |
  | `dmg` | 20 (40) | | `stats` | 2 (4) |
  | `aim` | 15 (30) | | `terrain` | 30 (200 during a survey) |
  | `hstate`, `state` | 10 (20) | | `hello` | 1 (3) |
  | `mobdmg`, `pcells` | 30 (60) | | Phase 4+: `devices`, `devstate`, `devresult` | 10 (40) |

  A session that keeps overflowing at five times its rate for 10 s is ended and the player kicked with "Cyberblock: too
  many messages from your Cyberpunk link". The rate multiplier `rate` in the server config scales all buckets.
- **Validation:** the existing receivers keep every limit of `shared/protocol.md` (they move from the client link thread
  to the server thread with the code). Bad messages never end a session except through the overflow rule above.
- **Host permissions (toggles):** each host decides what **guests** may do to its own Cyberpunk game. Stored per host
  UUID on the server (SavedData `cyberblock_hosts`), editable by that host with `/cyberblock guests <toggle> on|off`
  and in the settings screen (section 1.13). The host's own actions are always allowed.

  | Toggle | Default | What it gates when a **guest** causes it |
  |---|---|---|
  | `explosions` | on | guest explosions and wind charges near this host's V reaching its game |
  | `damage` | on | guest hits, fire, leads and rods on this host's NPCs and cars |
  | `hacks` | on | guest scans and quickhacks on this host's NPCs, cars and devices (Phase 6 widens it) |
  | `devices` | on | guest device control; Phase 4 adds one bit per device type (section 1.11) |
  | `spawns` | off | guest and Overlord spawns of Night City NPCs and events into this game (Phase 8) |

  A gated action still happens in the Minecraft world (a guest's TNT still breaks Minecraft blocks); it just does not
  reach that host's Cyberpunk game, and the guest sees `cyberblock.guest.blocked` on the action bar.
- **The `cmd` test message** is never relayed and is refused on a dedicated server. The client's `HostLink` keeps its
  browser-Origin refusal.

### 1.7 Content as data (a design rule from Phase 1 on)
- **Location:** datapacks. The loader reads every namespace's `data/<namespace>/cyberblock/<kind>/<name>.json`, so a
  friend adds content as a datapack in `<world>/datapacks/` with their own namespace, no code. The mod ships defaults in
  `mc/src/main/resources/data/cyberblock/cyberblock/<kind>/`. **Hot reload:** vanilla `/reload` (a Fabric resource reload
  listener on the server data; the 26.3 API name is verified in task 1-N4); clients get the kinds they need through
  `cyberblock:content`. Textures (NPC skins, vehicle textures, block art) are resource-pack assets under
  `assets/<namespace>/textures/cyberblock/...`; the server-pushed pack (Phase 2) can add more, referenced by id from the
  data.
- **Format:** one JSON object per file with `"schema": 1`; the id is `<namespace>:<name>`; strings have byte caps; colours
  are `#RRGGBB`; positions are Minecraft `[x, y, z]`; references are ids. A bad file is logged (`content: <kind> <id>
  skipped: <why>`) and skipped, never fatal. JSON Schemas live in `shared/content-schemas/<kind>.schema.json`;
  `tools/content_check.py` validates the shipped defaults and any folder given to it (`--dir`), in every suite run.
- **Kinds** (the phase that creates the loader for each):

  | Kind | Phase | Holds |
  |---|---|---|
  | `material_palette` | 3 | Cyberpunk surface material (or heuristic class) → replica block id and colour |
  | `device_map` | 4 | Cyberpunk device class → device kind (section 1.11), Minecraft block, colours |
  | `shop` | 4 (vending), 7 (vendors) | stock lists, eddie prices, restock times |
  | `skin_map` | 5 | NPC role/faction/gender/archetype → skin id and model (classic/slim); cyber skin entries |
  | `vehicle_map` | 5 | vehicle record/class → one of the 12 vehicle models, default colour |
  | `daemon` | 6 | Breach daemon sequences, lengths, the real action each maps to, strength tiers |
  | `breach_tier` | 6 | grid size, buffer, timer by device security level and Firewall rank |
  | `dialogue` | 7 | original lines per NPC role (greetings, rumours, barks, farewells) |
  | `contract` | 7 | fixer gigs: title, text, objective type, target, reward |
  | `ripperdoc` | 7 | implant catalogue, prices, surgery risk per implant |
  | `landmark` | 7 | district landmarks and spawn points (Minecraft position, name, district, kind) |
  | `npc_role` | 7 | role → screen, services, hub placement |
  | `event` | 8 | Overlord events (blackout, EMP, airstrike, traffic jam, supply drop, weather, cyberpsychosis outbreak): cost, warning, effect parameters |
  | `spawn_palette` | 8 | Overlord spawn entries (Minecraft mobs and Night City enemies), Threat Point costs |
  | `heat_tier` | 8 | Server Heat tiers 1-5: thresholds, decay, responses |
  | `match_mode` | 8 | match modes (team deathmatch, Gang Wars, ...): rules, time limits, zone settings |
- **Lite readiness (v2, nothing built now):** all content lives server-side and reaches clients by payload; textures by
  resource pack; replica cells store material ids, not Java block classes (section 7), so a later server-side mapping to
  vanilla blocks for unmodded clients stays possible.

### 1.8 New natives (draft; exact names fixed by the phase's S package after the Phase 0 evidence)
New Minecraft → Cyberpunk requests reuse the existing event pump (`Cyberblock_NextEvent` plus the `Cyberblock_Event*`
getters) with new event names, so few new natives are needed. New Cyberpunk → Minecraft data uses submit natives in the
style of `Cyberblock_SubmitNpcs`.

| Phase | Native (declared in) | Implemented in | Purpose |
|---|---|---|---|
| 1 | `Cyberblock_EventText() -> String` (`Natives.reds`) | `natives.cpp` | string payload of the current event (session reason, device action name, Comms text) |
| 1 | events `session` (name = state, text = reason, extra = perms bitmask) | `world.cpp` (parse), `natives.cpp` | session changes reach the scripts |
| 3 | `Cyberblock_SubmitProbes` gains a material array (or a new `Cyberblock_SubmitProbeMaterials`) (`Probes.reds`) | `natives.cpp`, `probes.cpp` | material per hit (Phase 0 b) |
| 3 | `Cyberblock_SetEnv(hour: Int32, minute: Int32, weather: CName) -> Void` (`Env.reds`) | `natives.cpp` | time and weather UP in `stats`/`env` |
| 3 | events `vteleport` (pos, yaw, id) and `park` (flag); `Cyberblock_TeleportDone(id: Int32, ok: Bool, reason: String)` (`Teleport.reds`) | `natives.cpp` | Meet Up, Holo-Table spawns, hub parking |
| 4 | `Cyberblock_SubmitDevices(ids: array<Uint64>, classes: array<CName>, kinds: array<CName>, pos: array<Vector4>, yaws: array<Float>, sizes: array<Vector4>, states: array<Int32>, flags: array<Int32>) -> Void`, `Cyberblock_DeviceState(id: Uint64, state: Int32, flags: Int32, by: CName) -> Void`, `Cyberblock_DeviceResult(id: Int32, ok: Bool, reason: String) -> Void` (`Devices.reds`); events `devact` | new `devices.cpp`, `natives.cpp` | device stream and actions |
| 5 | the NPC submit native gains parallel arrays `roles: array<CName>, factions: array<CName>, genders: array<Int32>, archetypes: array<CName>, anims: array<Int32>, vclass: array<CName>, colours: array<Int32>, hp: array<Float>` (new `Cyberblock_SubmitNpcs2`, the old one stays) (`Npcs.reds`) | `natives.cpp`, `world.cpp` | figure tags and animation flags |
| 6 | events `rhack` (remote hack: name, target, id), `daemon` (Breach result); `Cyberblock_RemoteHackResult` reuses `Cyberblock_HackResult` | `natives.cpp` | interaction map actions |
| 7 | `Cyberblock_ReplyVendor...` reuses the `Reply*` natives with `kind` `vendor`; event `hub` (flag). Buy and sell are **not** events (fixed 2026-10-04 at the 7S review): they are `uiact` `a` `buy`/`sell` (7-D1 `kUiActs`, read through `Cyberblock_UiAction*` in `Menus.reds`, traded by `Vendors.reds`, 7-V1) | `ui_game.cpp`, `world.cpp` | vendor screens, hub |
| 8 | events `spawn` (record, pos, count, hostile, id), `heat` (tier), `attitude` (faction, delta), `glitch` (seconds); `Cyberblock_SpawnResult(id: Int32, ok: Bool, count: Int32, reason: String)` (`Spawns.reds`) | `natives.cpp` | Overlord, Heat, hacking players |

### 1.9 New DLL ↔ client wire messages, by phase (contract shapes; Phase 0 may narrow fields, never rename them)
| Phase | `t` | Direction / class | Fields |
|---|---|---|---|
| Q2, Q3 | `prefs` | MC → CP, LOCAL | `dist` 1.5..8 m (third-person distance), `shoulder` -1..1 m, `fov` 0 (the game's) or 50..110, `recenter` bool, `vdist` 0 (the game's) or 3..14 m (vehicle camera distance, only if Phase Q finds it settable), `stepup` bool (Q2 step-up assist), `debug` bool (the `/cyberblock debug view` flag that gates `cam.vdbg`; added by the Phase Q integrator, default false); after each host `hello` and on change. Phase 9 adds `scale` 0.5..1.0 (Performance mode frame-export scale) |
| Q3 | `cam` (ext) | CP → MC, LOCAL | adds `vdbg:[collide m, smooth m, age ms]` only while `/cyberblock debug view` is on |
| 3 | `unground` (ext) | CP → MC, UP | adds `seen:true` when the cells were observed empty inside the probe radius (absent: they only left the radius); replica blocks go only on `seen:true` |
| 3 | `ground` (ext) | CP → MC, UP | adds optional `m:[code, ...]`, one material code per quadruple (0 unknown; codes from `material_palette`) |
| 3 | `env` | CP → MC, UP | `time:[h,m]`, `weather` ∈ `clear, cloudy, rain, storm, fog, smog, sandstorm, other` (Phase 0 decides what is readable) |
| 3 | `vteleport` / `vteleported` | MC → CP DOWN / CP → MC UP | `{id, p:[x,y,z], yaw, why ∈ meetup, holo, hub_exit}` / `{id, ok, reason, p}` |
| 3 | `park` / `parked` | DOWN / UP | `{on}` / `{on, ok, reason}` |
| 4 | `devices` | CP → MC, UP | `list:[{id, cls, kind, p, yaw, size:[w,h,d], st, fl}]` ≤ 64 within 48 m, deltas plus a full list every 5 s; `fl` bits: 1 locked, 2 secure, 4 quest-locked, 8 broken, 16 powered |
| 4 | `devstate` | UP | `{id, st, fl, by ∈ npc, host, guest, script}` |
| 4 | `devact` / `devresult` | DOWN / UP | `{id (request), dev, act ∈ open, close, lock, unlock, on, off, toggle, call, buy, disable, enable, friendly, breach, distract}` / `{id, ok, reason}` |
| 5 | `npcs` (ext) | UP | entries add `role`, `fac`, `g` (0 unknown, 1 male, 2 female), `arch`, `anim` bits (1 walk, 2 run, 4 aim, 8 fire, 16 ragdoll, 32 dead, 64 crouch, 128 combat), `vc` (vehicle class), `col` (`#RRGGBB`), `hp` 0..1 |
| 6 | `rhack` / `hackresult` | DOWN / UP | `{id, target, hack}` where `hack` adds `veh_lock, veh_engine_off, veh_brake, veh_start, veh_jam, traffic_switch, gridlock, blackout, cam_feed, outline, ping_area` |
| 6 | `daemon` | DOWN | `{id, dev, list:[daemon ids], strength 1..3}` |
| 6 | `feed` | CP → MC, BOTH | `{w:128, h:128, px:"<base64 of 16384 map-colour bytes>"}` ≤ 1 per second while a feed is requested (`feedreq {on}` DOWN) |
| 7 | `hub` | DOWN | `{in:bool}`: the DLL switches the compositor to full-screen Minecraft (the loading-cover path) and the scripts park V |
| 7 | `reply` (ext) | UP | `kind:"vendor"` (7-V1's layout, pinned 2026-10-04): unrequested, `id` = the takeover counter; `pins[0]` = the vendor (kind `vendor` or `ripperdoc`, text = shop name); groups = stock categories (hash = category index, kind `buy`/`sell`, which the DLL files as `other`), `done:false` = the vendor's stock, `done:true` = V's items the vendor takes; lines = items (hash = low 32 bits of the item's TweakDB id, text = name, `state` = unit price in eddies) |
| 7 | `uiact` (ext) | DOWN | `a:"buy"\|"sell"`: `hash` = the item's line hash (required, non-zero), `id` = the line's index on its side, `arg` = the quantity |
| 8 | `spawn` / `spawnresult` | DOWN / UP | `{id, rec ∈ ganger, ncpd, maxtac, drone, turret, ..., p, n 1..8, hostile, faction}` / `{id, ok, n, reason}` |
| 8 | `heat` | DOWN | `{tier 0..5}` (the host's real wanted level follows when Phase 0 e allows) |
| 8 | `attitude` | DOWN | `{faction, toward ∈ friendly, neutral, hostile}` |
| 8 | `glitch` | DOWN | `{seconds 0..10}` (Reboot Optics on a linked victim: ReShade glitch) |
| 9 | `prefs` (ext) | MC → CP, LOCAL | adds `scale` 0.5..1.0 (Performance mode: frame-export resolution scale) |

Every new message gets validators on both sides, `shared/protocol.md` rows and examples, `tests/test_protocol_md.py`
VALIDATORS and a fake host mode, in the phase that adds it.

> **Amendment 2026-10-04 (Phase 0 contract narrowing; Wave A integrator).** From package 0R's desk evidence
> (`docs/notes/phase0-feasibility.md`, "Contract narrowing needed"). Names are unchanged; only fields and values narrow.
> Items marked *(CET Xn)* are confirmed or corrected by that line of the Phase 0 CET session in Re-test Wave A; until
> then they are the working contract for the phase's S/D packages.
>
> **Section 1.8 (natives):**
> 1. **Ph3 probe materials:** the second form, `Cyberblock_SubmitProbeMaterials(down: array<CName>, wall: array<CName>)`
>    (`Probes.reds`), parallel to the down and wall hit arrays of the same tick and called right before
>    `Cyberblock_SubmitProbes`. Each entry is `TraceResult.material`, or the hit node's `meshAppearance` when the material
>    is `None`. One shared, fixed name table (`shared/`) maps names to `material_palette` codes; unknown names are 0
>    (logged once each). *(CET B1-B5)*
> 2. **Ph3 device hits:** a probe ray whose `GetHitEntity` is a `Device` is cast again past it, as Cyberblock boxes are, so
>    doors and gates never enter the static replica. *(CET B2; else Phase 4's device-box filter)*
> 3. **Ph3 `Cyberblock_SetEnv`:** `weather` is the raw `worldWeatherState.name` CName (Codeware
>    `WeatherSystem.GetWeatherState().name`); the DLL maps it to the 1.9 `env` enum by name. Time is
>    `TimeSystem.GetGameTime`. *(CET ENV)*
> 4. **Ph3 `vteleport` and `park`:** `vteleport` = `TeleportationFacility.Teleport` + the survey's settle probe (hold high,
>    3 x 3 down rays stable 0.5 s, 15 s at most) + a down-ray snap. `park` = `SetInvisible` + god mode `Invulnerable`
>    (source `n"CyberblockPark"`) + save lock (`n"CyberblockPark"`) + `GameplayRestriction.NoMovement` and
>    `NoCameraControl`. `Cyberblock_TeleportDone` reasons: `""`, `"no ground"`, `"timeout"`, `"blocked (<why>)"`.
>    *(CET G1-G3, G5-G6)*
> 5. **Ph4 `Cyberblock_SubmitDevices`:** `ids` = `CyberblockIds.Hash(device)` (fallback: hash of the position rounded to
>    25 cm plus the class); `classes` = the RTTI class name; `kinds` from the class's `IsA` chain; `sizes` always zero
>    (Minecraft takes the size from `device_map`); `states` = `EDeviceStatus`, or `EDoorStatus` for doors; `flags` as 1.9.
>    `Cyberblock_DeviceState.by` comes from the performed action's executor. The list comes from a Codeware attach
>    registry (`EntityTarget.Type(n"Device")`), changes from `@wrapMethod(Device)` `OnPerformedAction` and
>    `OnPSChangedEvent` (fallback: 2 Hz polling within 48 m). *(CET C1-C5)*
> 6. **Ph4 `devact` events:** `act` limited to `open, close, toggle, lock, unlock, on, off, disable, enable, distract,
>    friendly`. `call`, `buy` and `breach` are not Cyberpunk actions (lift calls are unproven, vending is a Minecraft shop,
>    Breach is a Minecraft minigame whose daemons send the supported acts).
> 7. **Ph5 `Cyberblock_SubmitNpcs2`:** `roles` = the vendor type name (`RipperDoc`, `Clothes`, ...) or `n""`; `factions`
>    = `Affiliation().EnumName()`; `genders` from `GetResolvedGenderName`; `archetypes` =
>    `ArchetypeData().Type().EnumName()`; `anims` from the puppet-state blackboard and `IsRagdolling`, bits 1 and 2 always
>    0 (the DLL derives walk and run from position deltas); `vclass` = `"<type>/<model>"` from `Vehicle_Record.Type()` and
>    `Model()`; `colours` from the matching `VehicleColorTemplate` `MainColor`, -1 when none; `hp` from the Health stat
>    pool. *(CET F1-F2; F2 fixes the colour number format)*
> 8. **Ph8 `spawn` events:** `rec` values map to the verified character records of check (d); `turret` is removed (a
>    turret is a device, not a character record). *(CET D1-D5)*
>
> **Section 1.9 (wire messages):**
> 1. **3 `ground` (ext):** the `m` codes come from the shared name table of 1.8 item 1; 0 is unknown.
> 2. **3 `env`:** `time` from the game time; `weather` mapped from the preset name, `other` when unmapped.
> 3. **3 `vteleported`:** `reason` ∈ `""`, `no ground`, `timeout`, `blocked (<why>)`; `p` is the snapped feet position.
> 4. **4 `devices`:** `size` is omitted (Minecraft uses `device_map`); `st` is `EDeviceStatus` (-2..1) or, for doors,
>    `EDoorStatus` (-2..1); `fl` bit 4 (quest-locked) = door `SEALED` or device `DISABLED`; bit 16 (powered) = the state
>    is not `UNPOWERED`; `cls` ≤ 48 chars.
> 5. **4 `devstate`:** `by` = `host` when the executor is V, `npc` when an NPC, `script` when there is none, `guest` when the
>    action came from a guest's `devact` (the DLL remembers the request for 2 s).
> 6. **4 `devact`:** `act` limited to the list of 1.8 item 6; other values answer `{ok:false, reason:"unsupported"}`. The
>    `vending` (buy) and `elevator` (call) rows of 1.11 show greyed (`cyberblock.device.unsupported`) until proven.
> 7. **5 `npcs` (ext):** `role` from the vendor type only (fixer, bartender, medic and mechanic come from `npc_role` and
>    `skin_map` content); `g` from the resolved gender name; `anim` bits 1/2 set by the DLL; `vc` = `"<type>/<model>"`
>    (for example `Car/Turbo`, ≤ 32 chars); `col` only when a colour template matches (else the class default colour).
> 8. **8 `spawn`:** `rec` loses `turret`; turrets are Minecraft-side only.
>
> **Section 1.11 note:** confirmed script classes: `Door`, `SecurityGate`, `RoadBlock`, `ElectricLight`, `InteractiveSign`,
> `BillboardDevice`, `SurveillanceCamera`, `Terminal`, `Computer`, `VendingMachine`, `TrafficLight`, `LiftDevice`,
> `SecurityTurret`, `TV`. There is no `GarageDoor` class: garage doors are `Door` types, road barriers `RoadBlock`.
> Containers are `gameContainerObjectBase`, not devices (read-only, unchanged).
>
> **Fallbacks applied now (section 4.1):** (a) Apex has a Custom Jar option, so the no-custom-jar branch is dropped; the
> Java 25, disk and UDP-port branches stay in force. (b) no material → `material_palette` heuristic plus `meshAppearance`.
> (c) size from `device_map`; lift call and vending buy greyed. (d) turrets Minecraft-side only. (f) roles from vendor type
> plus content; walk/run from position deltas; class default colour. (g) the hold-high-until-a-down-ray-hits method is
> the method. (h) only streamed targets; others answer `cyberblock.hack.out_of_range`.

> **Amendment 2026-10-04 (Phase 7 contract; package 7D review).** Names are unchanged; this fixes where the Phase 7 rows
> of 1.8 and 1.9 meet, so 7S, 7V, 7H and 7P build to one shape. The integrator aligns the other packages to it.
>
> **Section 1.8, Phase 7 row:**
> 1. **`buy` and `sell` are UI actions, not events.** They reach the scripts through `Cyberblock_NextUiAction` (the
>    LOCAL `uiact` class, like `track` or `fasttravel`), never through `Cyberblock_NextEvent`: `Cyberblock_UiActionHash` =
>    the item's line `hash`, `Cyberblock_UiActionId` = the line's index within its side (the `buy` groups, or the `sell`
>    groups, in reply order), `Cyberblock_UiActionArg` = the quantity as a decimal string. One vendor implementation
>    stays: 7V's `Vendors.reds` (dispatched from `Menus.reds`). 7S's `n"buy"`/`n"sell"` branch of `Events.reds` and its
>    `VendorStock.reds` trade path are dropped, or routed to 7V's handler, before merging. The stock `reply` is sent
>    unrequested when the vendor opens (7V). The DLL has no `vendorreq` action.
> 2. **`hub` (flag) stays an event** and is kept while Cyberblock is not Active, like `park`: Hub.reds keeps its own copy
>    and re-parks V while it says in, so the scripts must hear every out. The DLL queues a `hub` event (flag false) whenever
>    it ends the hub itself (a `hello`, a link drop, a non-host `session`), and one with flag true whenever it shows it.
>
> **Section 1.9, Phase 7 rows:**
> 3. **`hub` and the session.** The DLL shows the hub (full-screen Minecraft) only while its session is `host`. A `hub`
>    that arrives before the host grant (no session yet, a guest locked until `/login`, a refusal) is remembered and
>    shown at the grant, with its `hub` event queued after the `session` event. On every non-host `session`, a `hello`
>    and a link drop the DLL ends the hub (cleaned like a link drop, section 1.2). The remembered word is forgotten on a
>    `hello`, a link drop, `session none` and `hub in:false`. The server (7H) still sends `hub {in}` after every host grant
>    (`hs_ack role host`, including after `/login` and a reconnect) while the player is in `cyberblock:afterlife`. Repeats
>    are harmless. The mpcheck `hub` scenario (7P) covers guest, then `hub`, then host.
> 4. **Input in the hub.** While the hub is shown and the DLL forwards input (Cyberblock On, on foot, no Cyberpunk menu,
>    linked, window in front), W, A, S, D and Ctrl go DOWN as `key` `forward`, `left`, `back`, `right`, `sneak` (down and
>    up, with `mods`). Space and both Shifts go as `jump` and `sprint`, as before. All seven are kept from Cyberpunk, and
>    the raw mouse motion goes as `look {d:[dyaw, dpitch]}` (0.15 degrees per count, at most 120 per second) while
>    Cyberpunk's own camera motion is zeroed. Mouse buttons, the wheel, E, Q, T, X, 1-9, `/`, F5 and the binds work as
>    before. While in the hub the client (7H) stops `PlayerSync` from placing Steve on V and drives Steve from these keys
>    and look deltas. Today `Flight.look` applies `look` only while gliding, and `ClientInput` ignores key names it does
>    not know, so older clients are unaffected. DLL log line: `hub: input to Minecraft on|off`.
> 5. **`reply` (ext) `kind:"vendor"`:** each group's `kind` ∈ `buy` (the vendor's stock), `sell` (what V can sell to it).
>    The DLL keeps them for vendor replies and maps any other value to `buy`. The other reply kinds keep their own group
>    kinds, so `buy` or `sell` there becomes `other`. The client's `UiModel` validator accepts both (7V, 7P); 7V's `done:true`
>    on sell groups stays allowed but is no longer the marker.
> 6. **`uiact` `buy` / `sell`:** `hash` 1..2^32-1 (required), `id` = the line index of item 1, `arg` = the quantity. 7P's
>    protocol rows (`vendorreq`, "one unit per action", `id` = the reply's id) are aligned to this by the integrator.

### 1.10 Keys (unchanged; keep the table clash-free)
| Key | Action | Rebindable |
|---|---|---|
| F9 | toggle Cyberblock | no |
| G | hack (hold) | yes |
| K | skill tree | yes |
| U | Cyberware screen | yes |
| Y | Operating System implant | yes |
| L | Arms implant | yes |
| O | Optical Camo | yes |

The allowed rebinding set stays G K L O U Y 0 F1-F4 F6-F8 F10-F12; the refused set and the clash rule stay as
`shared/protocol.md` "Re-test 3: keys" says. **This plan adds no key:** every new screen opens by right-clicking a block,
an item or an NPC figure, by a pause-screen button or by a command. If a package finds a key truly needed, it is a
contract deviation: the integrator updates the table, the DLL (`keys.cpp`), the Java mappings and `shared/vectors.json`
together, in one commit.

### 1.11 Device-type mapping (Phase 4; Cyberpunk classes are candidates until Phase 0 c confirms them)
| Brief device | Cyberpunk class candidates | Minecraft version | Synced both ways | Actions from Minecraft | Toggle bit |
|---|---|---|---|---|---|
| Doors, single and double | `Door`, `DoorControllerPS` | wooden door; iron door for secure doors; a double door is two doors; a red lamp block above while locked | open/closed, locked | right-click open/close; lock/unlock (locked needs Breach or a Device Relay) | 1 `door` |
| Gates, garage doors, barriers | `GarageDoor`, `SecurityGate`, road barrier classes | multi-block sliding gate (custom block, slides in steps) | open/closed | open/close | 2 `gate` |
| Lights, neon | `Lights`/light device classes, neon signs | redstone lamp, glowstone or sea lantern in the matching colour (coloured lamp block) | on/off, colour | on/off | 4 `light` |
| Cameras | `SurveillanceCamera` | "eye" block with a particle vision cone | on/off, alarm, friendly | disable/enable; feed (Phase 6) | 8 `camera` |
| Terminals, computers | `Terminal`, `Computer` | "Net Port" block: right-click = Breach Protocol (Phase 6) | locked/breached | breach | 16 `terminal` |
| Vending machines, shops | `VendingMachine` and shop devices | machine block with a shop screen paid in eddies | stock (if readable), broken | buy | 32 `vending` |
| Traffic lights | `TrafficLight` | three-colour lamp block | colour state | switch (Traffic Tool, Phase 6) | 64 `traffic` |
| Elevators | `LiftDevice` | lift platform with a call button | floor, moving | call | 128 `elevator` |
| Turrets | `SecurityTurret` | dispenser-style turret block | active/off/friendly | disable, make friendly | 256 `turret` |
| TVs, billboards | `TV`, billboard devices | item frame showing a map image | on/off | breach = distraction | 512 `screen` |
| Containers | container/loot classes | chest, only if the loot is readable (else a closed crate block) | contents (read only) | open to view | 1024 `container` |

Rules: door (and gate) hits are filtered out of the static solid replica (doors are not walls). Quest- or cutscene-locked
devices show greyed (block state `quest_locked`) with the message `cyberblock.device.quest_locked`. Hosts can disable
device control per type (the toggle bits). State changes made by NPCs show in Minecraft (`devstate by:"npc"`).

### 1.12 Skin assets
| Asset | Source in `art/` | Shipped at | Model |
|---|---|---|---|
| Male V (cyber skin) | `art/skins/v_male_classic.png` (committed) | `assets/cyberblock/textures/entity/cyber_skin/v_male.png` | classic (4 px arms) |
| Female V = Lucy (cyber skin) | `art/skins/v_female_lucy_classic_template.png` (classic template, committed) → `art/convert_slim.py` writes `art/skins/v_female_lucy_slim.png` (arms narrowed by one pixel to the slim layout, both layers, both arms, with the top/bottom faces moved to match) | `assets/cyberblock/textures/entity/cyber_skin/v_female.png` | slim (3 px arms) |
| NPC skins (about 30) | `art/gen_npc_skins.py` (procedural, original) | `assets/cyberblock/textures/entity/npc/<id>.png` | per `skin_map`: female NPCs slim, male classic |
| Vehicle models (about 12) | `art/gen_vehicle_art.py` (textures) + Java model code | `assets/cyberblock/textures/entity/vehicle/<id>.png` | custom entity models |

- Linked players **always** show in the cyber skin on every client: forced, no opt-out. The server flags linked players
  in `cyberblock:roster`; the mod swaps the skin in the player renderer; the skin ships inside the mod (works offline).
  `/cyberblock body male|female` (default male) is stored per player on the server; NPCs follow their own gender.
- The conversion is checked visually: `art/preview_skins.py` renders front/back/side previews of both models into
  `art/preview_skins.png`, and the Phase 5 render package adds a dev-client screenshot of each.
- Today's `AbstractClientPlayerMixin` (the local player's own skin while active, `-Dcyberblock.ownSkin`) is replaced by
  the roster-driven rule in Phase 5.
- For a future public build, the defaults would be original skins with these two optional: the cyber skin entries live in
  `skin_map` (`cyber_male`, `cyber_female`), so a public build swaps data, not code.

### 1.13 Server config and the settings screen
- `config/cyberblock/server.json` (written with defaults on first start; bad values are logged and replaced by defaults):
  `{"schema":1, "allowHosts":true, "hosts":[], "maxHosts":1, "requireMod":true, "joinHelp":"", "rate":1.0,
  "sanityRadius":160, "creativeErasesReplica":false, "pvpDefault":false, "backups":{"daily":true, "keep":7, "hour":5},
  "webMap":false, "joinPassword":""}`. `hosts` holds player names or UUIDs. Phase 3 raises the default `maxHosts` to 4 when
  its multi-host check passes. `joinPassword` (added after Phase 2, at most 64 characters, trimmed; `""` = off) lets a server
  run without a whitelist: every player must `/login <password>` once per world (section 1.6); it is never logged, sent to a
  client or shown, and `/cyberblock status` says only `join password: on|off`.
- Per host: SavedData `cyberblock_hosts` (guest toggles, last session time for Offline Builds). Per player (Fabric data
  attachments, persisted): `body`, `pvp`, `hidePin`.
- **The Cyberblock settings screen** starts in Phase Q (task Q-B5, one row: "Creative can erase the replica permanently",
  host/ops only) and every later phase adds its rows through the owner named in that phase; Phase 9 finishes it (guest
  permissions, skin mode, PvP, difficulty, performance, creative erase). It opens from a "Cyberblock..." button on the
  pause screens and with `/cyberblock settings`. Server-side values are changed through `/cyberblock` commands the screen
  sends, so the server decides (never a client flag).

### 1.14 Log lines (in-game checks grep for these; each phase adds its own in its section)
- Server: `handshake: ...` (1.5), `session: <name> started|active|ended (<reason>)`, `relay: <name> dropped <t> x N
  (<why>)`, `content: loaded <kind> N (<namespaces>)`, `content: <kind> <id> skipped: <why>`, `outline: <name> broke
  <x> <y> <z> (snap back in 10 s|kept: permanent erase)`, `outline: restored N`.
- Client: `handshake: server X -> <role>`, `relay: up N msgs/s, down N msgs/s`, `reconnect: in N s (<reason>)`,
  `aim: target <block|entity|none> d=<m> limit=<m>` (Q1 debug), `view: <pipeline line>` (Q3 debug).
- DLL: `session: <state> (<reason>)`, `Cyberblock <ver> loading`, `hello ver <ver>`.
- Scripts: `session: cleaned (<reason>)`, `locomotion: vault refused on Cyberblock box`, `locomotion: climb refused on
  Cyberblock box` (Q2).

### 1.15 Test ports, frame names, test servers
| Phase | Client ports | Frame names | Dedicated test server ports | Offline names |
|---|---|---|---|---|
| Q | 25740-25749 | `Local\CyberblockFrameQ<pkg>` | — | — |
| 1 | 25750-25759 | `Local\CyberblockFrame1<pkg>` (`<pkg>` + `H`/`G` for host/guest client) | 25910-25919 | `CbHost1`, `CbHost2`, `CbGuest1`, `CbGuest2` |
| 2 | 25760-25769 | `Local\CyberblockFrame2<pkg>` | 25920-25929 | same |
| 3 | 25770-25779 | `Local\CyberblockFrame3<pkg>` | 25930-25939 | same |
| 4 | 25780-25789 | `Local\CyberblockFrame4<pkg>` | 25940-25949 | same |
| 5 | 25790-25799 | `Local\CyberblockFrame5<pkg>` | 25950-25959 | same |
| 6 | 25800-25809 | `Local\CyberblockFrame6<pkg>` | 25960-25969 | same |
| 7 | 25810-25819 | `Local\CyberblockFrame7<pkg>` | 25970-25979 | same |
| 8 | 25820-25829 | `Local\CyberblockFrame8<pkg>` | 25980-25989 | same |
| 9 | 25830-25839 | `Local\CyberblockFrame9<pkg>` | 25990-25999 | same |

Within a phase, each package uses the port and server port whose last digit is its row in the phase's package table
(first row 0). The integrator uses the phase's last port (9).

### 1.16 Eddies (one currency rule for every phase)
- **Minecraft eddies** are an item, `cyberblock:eddies` (stackable to 64, original art; a "credchip" of 100 is
  `cyberblock:eddie_chip`), owned by the server like any item. Every Minecraft-side price (device vending machines,
  hub shops, ripperdoc surgery, Trauma Team) is paid in Minecraft eddies. They are earned from gigs,
  kills in matches, selling to hub vendors and `shop` sell lists.
- **A host's real Cyberpunk money** (`stats.money`) is shown on that host's HUD as today and is spent only in the
  host's own Cyberpunk vendor and ripperdoc screens (Phase 7, task 7-V), through the host's game.
- There is no exchange between the two in this plan.

### 1.17 Cyberware ids added by this plan
`firewall` (Phase 6: raises Breach difficulty when defending, blocks player hacks in Phase 8) and `netrunner` (Phase 8:
resists player hacks) are appended to the fixed order of the cyberware list (`shared/protocol.md`, 17 ids today, at most
32). Both sides accept them from the phase that adds them; an older DLL drops unknown ids per entry, as the protocol says.

---

## 2. How every phase ends (deliverables template)

Each phase section below ends with these items; the integrator produces them before the STOP.
1. **Working code, every suite green** (rule 0.9), the merge order of that phase, no `zz_stub_*` left, hygiene clean.
2. **Installed build:** with Cyberpunk closed, `uv run python -m tools.install` (DLL, scripts, shader) and the mod jar into
   the user's Minecraft (dev run folder or `%APPDATA%\.minecraft-cyberblock\mods`, whichever the user plays; from Phase 2
   also the server-side files listed in the phase). If Cyberpunk is running, say so and leave the install for later.
3. **`docs/TESTING-PLAN.md`:** a new top section "Re-test <phase> (do this first)": a table of what changed, then one
   numbered check per item (action, expected result, the log line to grep), then the packages' `needsInGame` lists in the
   appendix.
4. **`docs/HOW-TO-PLAY.md` and `CHANGELOG.md`** updated for what the player can now do (the "Unreleased" entry).
5. **A one-screen "what to try" list** for the user (at most about 15 lines, in chat and at the top of the Re-test
   section).
6. **A short descopes and risks note:** every fallback applied, every `blocked` item, every risk still open.
7. **STOP** until the user says "go". The next phase begins with rule 0.1's repo read.

Every package's last task is its share of items 3 and 4 (the P package writes them; others report `needsInGame`).

---

## 3. Phase Q: quick wins (before the server work)

**After Phase Q the user can:** mine placed blocks in survival with a pickaxe (cracks and normal speed); walk up a
staircase of placed blocks without Cyberpunk's vault or climb prompt; use a third-person camera that does not clip,
glitch or lag, also with the elytra and the shield; break the invisible outline blocks in creative and watch them come
back; see in one table which Cyberpunk screens are Minecraft, which pass through, and which phase converts the rest.

**Depends on:** nothing (Phase 0 runs at the same time, section 4). Starts after `feat/survey-fast` is merged (rule
0.12): Q-S1 (in `Probes.reds`) and anything in `main.cpp` wait for it.

| Pkg | Scope | Owns (exclusive) | Model | Port / frame |
|---|---|---|---|---|
| **QB** blocks and aim | Q1 Minecraft side (aim margin, smoothing, target stickiness, target log, attack press), Q5 (break rule on the server, snap-back, rescue, the setting, the first settings screen) | `mc/src/client/java/dev/cyberblock/client/HostState.java`, `client/ClientInput.java`, `client/CyberblockClient.java`, `client/mixin/EntityPickMixin.java`, `client/mixin/MouseHandlerMixin.java`, new `client/settings/**`; `mc/src/main/java/dev/cyberblock/WorldBridge.java`, `BlockEvents.java`, `Cyberblock.java`, new `OutlineRules.java`, `OutlineGuard.java`, `SafeSpots.java`, new `mixin/ServerPlayerGameModeMixin.java` (only if task Q-B4 finds a break gate is needed), `mixin/ServerExplosionMixin.java`, `cyberblock.mixins.json`, `fabric.mod.json`; tests `HostStateTest.java`, `BlockEventsTest.java`, new `OutlineRulesTest.java`, `OutlineGuardTest.java`, `SafeSpotsTest.java`, `ClientInputTest.java` | Opus | 25740 / `Local\CyberblockFrameQB` |
| **QV** view, Minecraft side | Q3 Minecraft side (Steve's render interpolation, elytra and shield poses, flicker on state changes, third-person crosshair and aim, the debug overlay); Q1's on-ground and no-physics mining check (it lives in `PlayerSync`) | `client/PlayerSync.java`, `client/Flight.java`, `client/FrameExporter.java`, `client/mixin/AvatarRendererMixin.java`, `CameraMixin.java`, `LivingEntityMixin.java`, `EntityRenderDispatcherMixin.java`, `GameRendererMixin.java`, `client/ui/CrosshairHud.java`, new `client/view/**`, `cyberblock.client.mixins.json`; tests `PlayerSyncTest.java`, new `client/view/*Test.java` | Opus | 25741 / `Local\CyberblockFrameQV` |
| **QD** DLL view | Q3 DLL side (camera collision from the DLL, smoothing, over-the-shoulder offset, distance and FOV, auto-recenter, no clipping, vehicle distance, `prefs`, `vdbg`), view pipeline log | `cp/plugin/**` **except** the `feat/survey-fast` files (rule 0.12); `cp/shaders/**` | Opus | 25742 (link_smoke only) |
| **QS** Cyberpunk scripts | Q1 the aim ray ignores Cyberblock boxes; Q2 vault/climb refusal on our boxes and the step-up assist; Q3 view gate details | `cp/scripts/**` **except** the `feat/survey-fast` files until merged (`Probes.reds` after the merge); `tests/test_reds_ids.py`, `cp/scripts/compile_check.py` | Opus | — |
| **QU** UI coverage and lang | Q4: coverage audit, cheap gaps, escape-hatch regression; the Q lang keys; the "Cyberblock..." button on the Night City pause screen | `mc/src/client/java/dev/cyberblock/client/ui/**` **except** `CrosshairHud.java`; `mc/src/test/java/dev/cyberblock/client/ui/**`; `mc/src/main/resources/assets/cyberblock/lang/en_us.json`; new `docs/notes/ui-coverage.md` | Sonnet | 25743 / `Local\CyberblockFrameQU` |
| **QP** protocol, docs, hygiene | `prefs` and `vdbg` in the protocol, fake host `--view` and `--stairs`, hygiene test, player docs, Re-test Q | `shared/protocol.md`, `tools/fakehost.py`, `tests/test_protocol_md.py`, `tests/test_fakehost.py`, new `tests/test_hygiene.py`, `docs/**` **except** `docs/superpowers/plans/**` and `docs/notes/ui-coverage.md`, `README.md`, `CHANGELOG.md` | Sonnet | — |

Lang owner in Phase Q: **QU**. Merge order: QD, QS, QB, QV, QU, QP. QV's client code starts from
`dev.cyberblock.client.view.ViewClient.init()` (QV creates it); QB adds that one call to `CyberblockClient` and keeps an
uncommitted stand-in until QV merges.

### Q-B1. Aim limit with margin, smoothing and stickiness (Q1)
**Files:** `HostState.java`, `client/mixin/EntityPickMixin.java`, `HostStateTest.java`.
- [ ] Root-cause the report first, with logs: under `/cyberblock debug aim` (Q-B3) log every client tick the hit result
  (block/entity/none, position, distance), the raw `aim.d`, the limit used, whether attack is down and the destroy
  progress (`MultiPlayerGameMode` progress through a verified 26.3 accessor). Record the evidence in the report.
- [ ] `HostState.aimLimit(reach)` becomes `min(reach, smoothedAim + MARGIN)` with `MARGIN = 0.35` m: the smoothed
  distance falls only after 3 consecutive lower samples or 300 ms and rises at once; a negative `aim` means no limit.
- [ ] Stickiness: while attack is held and the block under destruction still exists, the pick keeps that block even if
  the limit dips below it for up to 500 ms (`EntityPickMixin` asks `HostState.stickyTarget()`).
- [ ] Tests (pure): a box face at `d` and the block face at `d + 0.002` is inside the limit; one-tick drops do not change
  the limit; a real wall in front (`d` 1 m shorter) still blocks targeting behind it; stickiness ends on release and on
  block removal.
**Done:** `gradlew test` passes; needsInGame: survival pickaxe on a placed stone shows growing cracks and breaks it in
vanilla time; `aim:` lines stay on the block.

### Q-B2. The attack press (Q1)
**Files:** `ClientInput.java`, `client/mixin/MouseHandlerMixin.java`, `ClientInputTest.java`.
- [ ] Prove (log and test) that `key attack down:true` keeps `keyAttack` down until `down:false` (no synthetic click on
  each message) and that a screen opening and closing does not leave it stuck or released early. Fix whatever breaks
  continuous destruction.
**Done:** `gradlew test` passes.

### Q-B3. Debug switches
**Files:** `Cyberblock.java`, `client/settings/DebugFlags.java` (new).
- [ ] `/cyberblock debug aim|view on|off` (client flags through a client command or a client-side handler of the server
  command; permission level 0). `view` is read by QV (`DebugFlags.view()`, public static) and sent to the DLL by QV.
**Done:** `gradlew test build`.

### Q-B4. Outline blocks: who may break them (Q5)
**Files:** `WorldBridge.java`, `BlockEvents.java`, `OutlineRules.java` (new), `mixin/ServerExplosionMixin.java`, new
`mixin/ServerPlayerGameModeMixin.java` only if needed, `OutlineRulesTest.java`, `BlockEventsTest.java`.
- [ ] Find what protects the barriers today (candidates: `WorldBridge`'s barrier set and re-placement, `BlockEvents`, the
  explosion immunity in `ServerExplosionMixin`, the aim limit of Q-B1 stopping the pick short, any break cancel). Record
  the evidence.
- [ ] `OutlineRules.decide(GameType mode, boolean creativeErases, Kind kind) -> Decision {DENY, SNAP_BACK, KEEP}` with
  `Kind {OUTLINE_BARRIER, PROJECTILE_BARRIER, REPLICA}`: survival/adventure deny; creative + erase OFF = SNAP_BACK;
  creative + erase ON = KEEP (until the probes report the cell again); spectators deny. Decided on the **server thread**
  from the `ServerPlayer`'s game mode, never from a client flag.
- [ ] Breaking an outline block removes only the Minecraft block: no `block` event, nothing to the DLL
  (`BlockEvents.change` already returns null for barrier → air: pin it with a test), and the cell leaves the barrier set
  so a later `unground` does not try to remove it.
- [ ] Explosions keep never removing outline blocks.
- [ ] Q-B1's margin and stickiness must let a creative player target an outline block (the outline shows and the
  break lands): a test with the aim distance at the block's own face.
**Done:** `gradlew test` passes; the pure rule test covers game mode x setting x block kind.

### Q-B5. The setting and the first settings screen (Q5)
**Files:** `Cyberblock.java`, `OutlineRules.java`, `client/settings/CyberblockSettingsScreen.java` (new),
`client/settings/SettingsClient.java` (new), `client/CyberblockClient.java`, `fabric.mod.json`.
- [ ] Server setting "Creative can erase the replica permanently" (default OFF), stored in the world (SavedData
  `cyberblock_settings`; Phase 1 moves the default into `config/cyberblock/server.json`, section 1.13). Command
  `/cyberblock outline erase on|off` and `/cyberblock outline` (status), allowed for ops (permission 2) and the
  singleplayer owner; Phase 1 adds active hosts.
- [ ] `CyberblockSettingsScreen` (vanilla widgets): one row with a toggle that sends the command; greyed with a tooltip
  when the player may not change it. Opens with `/cyberblock settings` and from a "Cyberblock..." button that
  `SettingsClient` adds to the vanilla pause screen (ScreenEvents); QU adds the same button to the Night City pause screen
  (calls `CyberblockSettingsScreen.open(parent)`, public static, QB owns it).
**Done:** `gradlew test build`; screenshot of the screen.

### Q-B6. Snap-back and the void-fall rescue (Q5)
**Files:** `OutlineGuard.java` (new), `SafeSpots.java` (new), `WorldBridge.java`, `OutlineGuardTest.java`,
`SafeSpotsTest.java`.
- [ ] `OutlineGuard` (server thread): a broken outline block with decision SNAP_BACK is put back after 200 ticks if the
  cell is still air and still claimed by the host's ground, or at once when the host's probes report that cell again
  (`WorldBridge.solid` puts barriers only into air, so a report re-places it); KEEP leaves it until such a report. Cells
  are forgotten on `clear` and on detach. Log `outline: ...` (section 1.14).
- [ ] `SafeSpots`: per player the last position standing on a solid non-air block; a player (not flying, not the
  driven host) who falls more than 16 blocks below that spot within 3 s after an outline break nearby, or below the
  world's minimum build height plus 8, is teleported back to it with fall damage cancelled (`cyberblock.outline.rescued`
  on the action bar).
- [ ] Tests: the timer (199 ticks no, 200 yes, occupied cell no, unclaimed cell no), the probe re-report path, KEEP,
  the rescue thresholds, and **a regression that no outline break ever produces a host event**.
**Done:** `gradlew test` passes; needsInGame: in creative, break the street outline block under the crosshair: it is
back after about 10 s; in survival it cannot be broken; with permanent erase ON it stays broken until V walks away and
back.

### Q-V1. Steve's render position follows the camera (Q3)
**Files:** `PlayerSync.java`, `client/view/ViewInterp.java` (new), `client/view/ViewInterpTest.java` (new).
- [ ] Reproduce with logs first (Q-V4 overlay and log): frame times, pose age (`cam` receive to render), the
  interpolation factor, Steve's render position versus V's feet, in walking, sprinting, gliding and launching.
- [ ] Steve's render position is set per rendered frame from the newest pose (interpolating between the last two `cam`
  samples by receive time, extrapolating at most 50 ms), not per 20 Hz tick, so he stays locked to the camera; the tick
  position stays the authoritative one for collisions and the server.
**Done:** `gradlew test`; dev-client screenshots under `tools.fakehost --view` (Q-P2) show no jitter.

### Q-V2. Elytra, shield and use-item poses without flicker (Q3)
**Files:** `client/mixin/AvatarRendererMixin.java`, `LivingEntityMixin.java`, `EntityRenderDispatcherMixin.java`,
`Flight.java`.
- [ ] The render state's fall-flying, using-item and blocking flags are debounced (a change must hold 2 ticks, except a
  real start or end of a glide) so third-person Steve does not flash between poses; wings stay open for the whole glide
  and launch; the shield-block pose is stable while right-click is held.
- [ ] Glide and launch keep one consistent pose from the first frame (no one-frame standing pose at launch).
**Done:** `gradlew test build`; third-person screenshots of a glide, a launch and a shield block.

### Q-V3. Third-person crosshair and aim (Q3)
**Files:** `client/ui/CrosshairHud.java`, `client/view/AimProjector.java` (new), test.
- [ ] In third person the crosshair is drawn where Steve's own aim (the eye ray Minecraft picks with) meets the target,
  projected into the host camera's view, so what the crosshair covers is what gets hit; it falls back to the screen centre
  when nothing is within reach.
**Done:** tests (projection math) pass; screenshot.

### Q-V4. View debug overlay and log (Q3)
**Files:** `client/view/ViewDebugHud.java` (new), `PlayerSync.java`.
- [ ] While `DebugFlags.view()`: an overlay (top left, small font) with frame time, pose age, interpolation factor,
  first/third person, camera distance and collision distance from `cam.vdbg`; a 5 s log line `view: frame <ms> pose age
  <ms> interp <f> dist <m> collide <m>`. Sends `prefs` (section 1.9) after each host `hello` and on change (values from
  the settings screen rows QV adds: distance, shoulder, FOV, recenter, vehicle distance).
**Done:** `gradlew test build`; screenshot.

### Q-V5. Mining while V is on the ground (Q1)
**Files:** `PlayerSync.java`, `PlayerSyncTest.java`.
- [ ] Prove that Steve's `onGround` (set from `cam.air`) and the no-physics mode do not apply vanilla's airborne mining
  penalty (speed / 5) while V stands, on the client and on the server (the server recomputes destroy progress); if they
  do, fix it (keep `onGround` true while V stands, never while V is in the air). Test the rule.
**Done:** `gradlew test` passes.

### Q-D1. Third-person camera in the DLL (Q3)
**Files:** `src/view.hpp/.cpp`, `src/camera.cpp`, `src/world.hpp/.cpp`, `tests/test_view.cpp`, `tests/test_world.cpp`.
- [ ] Parse `prefs` (section 1.9; reset to defaults on `hello`): distance (default 3.2), shoulder (default 0.45 m right),
  FOV (0 = the game's), recenter (default on), vehicle distance (0 = the game's), `stepup` (passed to the scripts
  through the existing event pump as event `prefs`).
- [ ] Camera collision with Night City geometry from the DLL: four rays (the eye to the camera corners of a small box)
  instead of one, distance = nearest hit − 0.25 m, minimum 0.4 m, so the camera never clips through walls; critically
  damped smoothing on distance (fast in, slower out).
- [ ] Over-the-shoulder offset with **auto-recenter**: when a wall is on the shoulder side within 0.6 m, the shoulder
  offset swings smoothly to the centre, and back when clear; after a collision ends the distance returns smoothly.
- [ ] FOV: when `fov` is not 0, the third-person FOV is applied through the verified camera component call (the 2.31
  API; if no setter exists, report it and leave the game's FOV).
- [ ] Vehicle distance: find whether the 2.31 vehicle third-person camera exposes a settable distance (NativeDB); if it
  does, apply `vdist`; if not, record it and leave the game's vehicle cameras (descope note).
- [ ] `cam.vdbg` while the debug flag says so; log `view: dist <m> collide <m> shoulder <m> recenter <on|off>` on change.
- [ ] Pure tests: corner rays, smoothing, recenter, the clamps, `prefs` parsing and defaults.
**Done:** `cb_tests` passes; needsInGame: F5 walk along a wall with the camera on the wall side: it swings to centre and
never shows the inside of the wall; turning in a corridor never clips.

### Q-S1. The aim ray ignores Cyberblock boxes (Q1, after the survey-fast merge)
**Files:** `cp/scripts/Cyberblock/Probes.reds`.
- [ ] The `Aim` trace drops hits whose entity carries the `CyberblockCollision` tag (as the probe and projectile rays
  already do, `TraceResult.GetHitEntity`), casting again from just past the box (at most 4 times), so the aim distance is
  always the real Night City surface.
**Done:** compile_check passes; needsInGame: looking at a placed block in front of a wall, the `aim` distance (fake host or
log) is the wall's, not the block's.

### Q-S2. No vault or climb onto our boxes (Q2)
**Files:** `cp/scripts/Cyberblock/Locomotion.reds` (new), `Collision.reds` (a read-only `Has(cell)` helper, if missing).
- [ ] Confirm the exact 2.31 classes and methods in `locomotionTransitions.swift` (candidates: `ClimbDecisions`,
  `VaultDecisions` and their `EnterCondition`, the obstacle info they read) with compile_check.
- [ ] `@wrapMethod` their `EnterCondition`: when Cyberblock is Active and the obstacle point the decision uses lies in a
  cell occupied by a Cyberblock collision box, return false and log `locomotion: vault|climb refused on Cyberblock box`
  once per second; otherwise the vanilla result. Real Night City ledges and ladders keep working. If the obstacle point
  is not readable, use a short raycast in the move direction and test the hit entity's tag. Fallback if neither works: a
  collision preset for the boxes that the obstacle system ignores, only if it still stops NPCs and cars (needsInGame).
- [ ] Step-up assist: when `stepup` is on (event `prefs`, default on), V is on the ground and moving into a single
  Cyberblock box whose top is at most 1.05 m above V's feet with 2 m of free space above it, lift V onto it in 0.15 s
  (verified teleport/impulse call); never on Night City geometry, never in a vehicle or scene.
**Done:** compile_check passes; needsInGame: walk up a 1-block staircase of placed blocks: no vault/climb prompt, V goes
up step by step (with the assist, or by jumping if the assist is off); a real ledge and a ladder still vault and climb.

### Q-S3. View gate details (Q3)
**Files:** `cp/scripts/Cyberblock/View.reds`.
- [ ] Keep third person off in braindance, photo mode, scenes and menus; add the vehicle-camera probe Q-D1 needs (the
  verified vehicle camera reference), and log `view: gate <reason>` on change.
**Done:** compile_check passes.

### Q-U1. UI coverage audit (Q4)
**Files:** `docs/notes/ui-coverage.md` (new).
- [ ] One table of every Cyberpunk screen, popup, HUD element and world-space layer in the brief's section 5, with its
  status today (Minecraft, pass-through, never touched), the decision and the phase that converts it: vendors/shops and
  ripperdoc → Phase 7 (NPC interaction layer), breach protocol → Phase 6 (our Breach screen), death screen → Phase 8
  (Downed and revive), save/load lists → Phase 9, loading screens → Phase Q (Q-U2), fast travel → Phase 7 via the
  Holo-Table (the Atlas stays map-only); stash, wardrobe, crafting benches, arcade, braindance editor, tutorial and shard
  popups, settings stay pass-through unless a later phase finds them cheap.
- [ ] A decision for each world-space element, documented: NPC markers and mappins (`inkWorldLayer`), billboards
  (`inkAdvertisementsLayer`), street signs (`inkStreetSignsLayer`), system pop-ups (`inkSystemNotificationsLayer`), photo
  mode (`inkPhotoModeLayer`): left as they are, with the reason for each.
**Done:** the file exists and matches section 5 of the brief item by item.

### Q-U2. Cheap gaps (Q4)
**Files:** `client/ui/MenuFrameHud.java`, `client/ui/screen/NightCityPauseScreen.java`, `en_us.json`.
- [ ] The loading cover becomes a proper Minecraft loading screen look (dark dirt-style background drawn from the
  player's own vanilla sprite at runtime, "Loading Night City...", animated dots, one tip line from a lang list).
- [ ] The Night City pause screen gets the "Cyberblock..." button (opens `CyberblockSettingsScreen`, Q-B5).
- [ ] Lang keys for Phase Q: `cyberblock.outline.erase` "Creative can erase the replica permanently",
  `cyberblock.outline.erase.on|off`, `cyberblock.outline.rescued` "Caught you: back to safe ground",
  `cyberblock.settings.title` "Cyberblock settings", `cyberblock.settings.button` "Cyberblock...",
  `cyberblock.settings.locked` "Only the host or an operator can change this", `cyberblock.view.distance|shoulder|fov|
  recenter|vehicle_distance`, `cyberblock.stepup` "Step-up assist", `cyberblock.loading.tip.<n>`, `cyberblock.debug.*`.
**Done:** `gradlew test build`; screenshots.

### Q-U3. The escape hatch always works (Q4)
**Files:** `client/ui/screen/NightCityPauseScreen.java`, `mc/src/test/java/dev/cyberblock/client/ui/**`.
- [ ] A test that the "Night City Menu..." button exists in every state of the pause screen and sends `uiact cpmenu`;
  without a link the screen shows `screen.unavailable` and never strands the player.
**Done:** `gradlew test` passes.

### Q-P1. Protocol and fake host
**Files:** `shared/protocol.md`, `tests/test_protocol_md.py`, `tools/fakehost.py`, `tests/test_fakehost.py`.
- [ ] `prefs` and `cam.vdbg` rows, limits, examples, VALIDATORS. Fake host `--view` (a walking, sprinting, gliding pose
  stream with third-person `fp:false` and `vdbg`) and `--stairs` (ground under a staircase of placed blocks, `aim` at the
  block faces) to reproduce Q1 and Q3 without Cyberpunk; prints `prefs`.
**Done:** `uv run pytest -q` passes.

### Q-P2. Hygiene test
**Files:** `tests/test_hygiene.py` (new).
- [ ] Over `git ls-files`: fails on the user's Windows account name, on `APEX_FTP_PASS` followed by `=` and a quoted
  value, on host names of the hosting provider's FTP nodes, on `ftp://` URLs with credentials, and on drive-letter paths
  in files this plan creates (a list in the test, extended by each phase's P package). Older plans are exempt.
**Done:** `uv run pytest -q` passes on main.

### Q-P3. Docs
- [ ] HOW-TO-PLAY: survival mining, stairs and the step-up assist, the third-person camera options and the debug
  overlay, **a short "Creative mode and the outline blocks" note** (what the outline blocks are, creative may break them,
  they come back in about 10 s, the permanent-erase setting, survival keeps them unbreakable), the Cyberblock settings
  screen. CHANGELOG. TESTING-PLAN "Re-test Q" with the checks below and Phase 0's in-game checks (section 4.3).

### Phase Q integration and deliverables
- Merge order above; all suites; install (rule 0.1).
- **In-game checks (Re-test Q):** (1) survival, iron pickaxe, a placed stone at eye height and one at the feet: cracks
  grow and each breaks in vanilla time; `aim:` lines show the block every tick; (2) a staircase of five placed blocks:
  walk up it, no vault/climb prompt, `locomotion: ... refused` in the log; a real kerb ledge and a ladder still work;
  (3) F5: walk, sprint, glide, launch with a wind charge, raise the shield, walk along a wall and into a corridor: no
  flicker, no clipping, the camera swings off the wall, the crosshair hits what it covers; the debug overlay numbers
  (frame time, pose age) stay steady; (4) creative: break the outline block under the crosshair on the street, it returns
  in about 10 s; survival: it cannot be broken; permanent erase ON: it stays until you walk away and back; (5) `docs/notes/ui-coverage.md`
  matches what you see; Esc → Night City Menu... still opens Cyberpunk's own menu.
- **What to try (one screen):** mine with a pickaxe in survival; climb a block staircase; F5 and play with distance,
  shoulder and FOV in Esc → Cyberblock...; glide and block with the shield in third person; break outline blocks in
  creative and watch them return; flip "Creative can erase the replica permanently"; run the Phase 0 CET checks.
- **Descopes and risks:** the vehicle camera distance may not be settable in 2.31 (Q-D1 says); the climb refusal depends
  on reading the obstacle point (fallback: raycast tag test, then a preset change only if NPCs and cars still stop);
  FOV in third person depends on a verified setter.

---

## 4. Phase 0: feasibility (report with fallbacks; needs the user for Apex)

**After Phase 0 the user knows** which parts of the brief Cyberpunk 2.31 and Apex allow as written, and which fallback
replaces the rest, before any server work starts. Phase 0 changes no game code.

**Depends on:** nothing; runs **at the same time as Phase Q**. Its in-game checks ride on the Re-test Q session (CET
console lines the user pastes, about 10 minutes), so no extra Cyberpunk session is needed.

| Pkg | Scope | Owns (exclusive) | Model |
|---|---|---|---|
| **0R** Cyberpunk feasibility | checks (b) to (h): API evidence from NativeDB, Codeware and the 2.31 scripts, throwaway compile_check spikes in the scratchpad (never committed), CET check lines for the user | new `docs/notes/phase0-feasibility.md` | Opus |
| **0M** Minecraft and server ecosystem | check (a)'s desk part (what the Fabric server launcher for 26.3 needs, Java 25, how Apex documents custom jars and extra ports, from public docs), and the mods Phase 2 and 3 need: a proximity voice chat mod and a web map mod for Fabric 26.3 (names, versions, licences, ports, download pages; **no downloads**), the Modrinth modpack format, the vanilla resource-pack push fields | new `docs/notes/phase0-server.md` | Sonnet |
| **0U** the user | the Apex panel facts (section 4.2) | — | — |

Both notes files get a results table: check, verdict (yes / partly / no), evidence, fallback applied. The integrator
merges them, asks the user section 4.2's questions, adds the CET checks to Re-test Q, and after the user's answers and
CET results writes the final verdicts into both files (one commit).

### 4.1 The checks, each with its method and its written fallback
**(a) Apex: custom jar accepted, Java version choices, disk space.**
- Method: 0M reads the public Apex knowledge base on custom jars, Fabric and extra ports, and the Fabric server launcher
  requirements for 26.3 (Java 25; the mod's `fabric.mod.json` already needs `java >= 25`). The user answers section 4.2
  from the panel (screenshots welcome). The agent may list the server's files read-only (FTP `LIST` with the
  environment's credentials, rule 0.6, or the user's read-only mount) to measure the size of the current server.
- Fallback: **no Java 25** → ask Apex support for Java 25 (the user sends the message 0M drafts); meanwhile Phase 2 runs
  the same Fabric 26.3 server **on the user's PC** (a dedicated server started by `tools/mpcheck.py`'s launcher in
  "local host" mode, reachable by friends through the user's router or a tunnel of the user's choice, documented in
  `docs/SERVER.md`), and Apex stays on Rod's Ores. **No custom jar** → use Apex's built-in Fabric option if it lists
  26.3; else the same local fallback. **Too little disk** → the backup of Rod's Ores lives on the PC, nothing is deleted on
  the server; the user decides between a plan upgrade and the local fallback (the plan never deletes Rod's Ores files).
  **No extra UDP port** → voice chat falls back to the voice mod's own proxy mode if it has one over the game port, else
  voice chat is optional (friends use Discord) and the modpack still installs it disabled.

**(b) Raycast hit object and material (`TraceResult`): tell doors and devices from walls; surface material for block
colours.**
- Method: 0R checks `TraceResult` fields (material CName, hit entity via Codeware's `GetHitEntity`, already used),
  `SpatialQueriesSystem` variants that return the hit object, and whether a hit entity is a `Device`/`Door`; a compile
  spike; CET check: cast at a wall, a door, glass, asphalt, a neon sign and print the material name and the hit entity's
  class.
- Fallback: **no material** → block colours come from a heuristic palette (`material_palette` "heuristic" entries: by
  height above ground, district from `stats.district`, wall vs floor, and the Atlas water flag), so the replica is still
  visible but less accurate. **No hit object** → doors and devices are told apart from walls by the device stream's
  boxes (Phase 4 filters probe hits inside a device's box), so door hits still leave the static replica.

**(c) Device APIs: type, stable id (PersistentID or position + class), state, open/close/lock/unlock/on/off,
state-change events.**
- Method: 0R reads the device classes and their persistent states (`Device`, `ScriptableDeviceComponentPS` and the
  per-type PS classes such as doors, lights, cameras, terminals, vending machines, traffic lights, lifts, turrets, TVs),
  the persistent id API, the action classes the vanilla interaction and quickhack menus use, and any state-change
  callback; compile spikes for: enumerate devices within 48 m, read type/state/locked, open/close a door, toggle a light;
  CET checks for each.
- Fallback: **no stable id** → id = hash of (rounded position, class name), resolved at runtime (the Cyberdeck Link of
  Phase 6 stores exactly this). **No events** → state polled at 2 Hz for devices within 48 m. **An action not callable**
  → only the actions the vanilla device offers through its own interaction/quickhack actions are exposed; the rest show
  greyed with `cyberblock.device.unsupported`.

**(d) Spawn Night City NPCs (gangers, NCPD, Max-Tac, drones, turrets) at a position.**
- Method: 0R checks Codeware's dynamic entity spawning with character records (the record ids verified with `tdb_has`
  for a ganger of each gang in the brief, NCPD patrol and SWAT, Max-Tac, a drone, a turret), whether spawned NPCs get
  combat AI and attitude, and how they are despawned; CET check: spawn one ganger 10 m away and despawn it.
- Fallback: **spawning fails or spawned NPCs stay passive** → Overlord and Heat spawns of Night City enemies become
  Minecraft-side squads (skinned Minecraft mobs with Cyberblock skins standing in for gangers, NCPD and Max-Tac) for
  everyone, and for hosts the real wanted level (check e) brings the game's own NCPD and Max-Tac.

**(e) Faction/attitude changes, wanted level read and write (`PreventionSystem`).**
- Method: 0R checks the attitude system calls (group relations, persistent or not) and the prevention (heat) system's
  read and write calls in the 2.31 scripts; wanted is already read for `stats.wanted`. CET checks: raise heat to 2 and
  back to 0; set one gang friendly toward the player and back.
- Fallback: **no write** → Heat tiers for hosts only spawn through (d) or Minecraft squads, the host's wanted stars stay
  the game's own; **no attitude write** → Gang Wars reputation does not change how Night City treats a team (the brief
  makes it conditional) and the docs say so.

**(f) NPC role, affiliation, gender and archetype tags; vehicle record, class, colour; NPC animation state (aiming,
firing, ragdoll, dead); vendor stock.**
- Method: 0R checks the character record (character type, affiliation, gender, archetype/visual tags, vendor flags), the
  NPC role components (vendors, ripperdocs, fixers, bartenders, medics, mechanics), vehicle records (type, class, the
  colour or appearance name), AI and animation state readers (high-level state, combat, ragdoll, dead), and the market
  system's vendor stock; CET checks print each for a few NPCs, a car and a vendor.
- Fallback: **no role or gender** → skin by faction and threat only, unknown gender uses the classic model; **no
  animation state** → flags from motion (speed from position deltas: idle/walk/run), alive/dead from the list and
  `killed`; **no vehicle colour** → the class's default colour; **no vendor stock** → shops use `shop` content lists.

**(g) Safe V teleport to far coordinates (wait for streaming, ground snap) and a safe PARKED state for V while Steve is
in the hub.**
- Method: 0R builds on the survey tour, which already moves V far, holds V while the city streams in and puts V back
  exactly (`Survey.reds`, `survey_game.cpp`): check the teleport call, a streaming-ready test, and a ground snap by a
  down ray; for PARKED: invisible (the survey's), invulnerable (the survey's), unseen by NPCs (the verified "ignored by
  AI" or stealth status call), saving locked, camera locked. CET checks: teleport 2 km away and back; park for 30 s next
  to a hostile NPC.
- Fallback: **no streaming test** → teleport, hold V like a survey stop (high above the target, 15 s at most) until a down
  ray hits ground, then snap; **cannot make V unseen** → PARKED fast-travels V to the nearest fast-travel point (the
  vanilla fast travel) and holds V there invisible and invulnerable while the hub is open.

**(h) Quickhack and device actions callable for a remote player.**
- Method: 0R checks that `Hacks.reds`' upload path works with any target the host's game has streamed, regardless of V's
  line of sight and distance (the instigator stays V), and the same for device actions (check c); CET check: hack an NPC
  40 m away behind a wall through the event pump.
- Fallback: **range or sight limits** → remote hacks and device actions work only on targets within the host's streamed
  area and within 50 m of V, attributed to V; others are refused with `cyberblock.hack.out_of_range`.

### 4.2 Apex panel facts (the integrator asks the user, in one message, after 0M's notes)
1. Does the server-type or jar list in the panel show **Fabric for 26.3**? Does it offer **Custom Jar** (and where do you
   type the jar name)? A screenshot of the dropdown helps.
2. Which **Java versions** can you pick (Java 25 is required)? Which one does Rod's Ores use now?
3. **Disk:** the quota and the current usage.
4. **RAM** of the plan.
5. **Extra ports:** can you add a UDP port (proximity voice chat) and a TCP port (web map)? How (panel page)?
6. **Backups:** does the panel have its own backups, and how many can you keep?
7. Does the **File Manager** extract `.zip` files?
8. The exact **jar name** Rod's Ores runs on now (Paper 1.21.11), so the way back is exact.
9. The server **address and port** for friends (it stays out of the repo: kept only in your local, untracked notes).

### 4.3 In-game checks for Phase 0 (added to Re-test Q)
The CET lines from 0R for (b) to (h), each with the expected printout; the user pastes the output (or Claude reads
`Cyberblock.log` when the lines log there). Nothing in these lines changes a save (each undoes what it does).

### Phase 0 deliverables
- The two notes files with final verdicts and the fallbacks applied; section 1.8 and 1.9 narrowed accordingly (the
  integrator updates this plan's contracts in the same commit, as a dated amendment block).
- **What to try:** nothing new to play; paste the CET outputs and answer section 4.2.
- **Descopes and risks:** each failed check and its fallback, listed for the user before Phase 1 starts.

---

## 5. Phase 1: server/client split

**After Phase 1 the user can:** play singleplayer exactly as before (now through the same relay a server uses); open
the world to LAN and have a second player join as a guest whose TNT, hits and blocks reach V's Cyberpunk game; see a
clear message on a version mismatch; lose and regain the server connection without leftovers in Cyberpunk. Everything
is tested with the fake host and a second offline client.

**Depends on:** Phase Q merged (it changes `HostState`, `WorldBridge`, `ClientInput`, `PlayerSync`). Not on Phase 0.

| Pkg | Scope | Owns (exclusive) | Model | Port / frame / server |
|---|---|---|---|---|
| **1N** net core | payloads (1.3), sessions (1.4), handshake (1.5), envelope checks, rate limits and permissions (1.6), server config (1.13), the content loader skeleton (1.7), dev-only test commands | new `mc/src/main/java/dev/cyberblock/net/**`, new `mc/src/main/java/dev/cyberblock/content/**`, `Bridge.java`, `Cyberblock.java`, `fabric.mod.json`; tests `mc/src/test/java/dev/cyberblock/net/**`, `.../content/**`, `BridgeTest.java` | Opus | 25750 / `Local\CyberblockFrame1N` / 25910 |
| **1W** world track on sessions | every world-track class and main mixin onto `HostSession` (no `Cyberblock.events`, no shared statics), owner-scoped proxies, per-session barriers, block events and resync, guest toggles for explosions and damage | `mc/src/main/java/dev/cyberblock/*.java` **except** `Bridge.java` and `Cyberblock.java`, new `WorldModule.java`; `mc/src/main/java/dev/cyberblock/mixin/**`; `cyberblock.mixins.json`; their tests in `mc/src/test/java/dev/cyberblock/*.java` **except** `BridgeTest.java` | Opus | 25751 / `Local\CyberblockFrame1W` / 25911 |
| **1C** cyber track on sessions | `cyber/**` and `client/cyber/**`: scans and hacks routed by the server to the owner (a guest's too), perks, cyberware and implant effects DOWN, guest `hacks` toggle | `mc/src/main/java/dev/cyberblock/cyber/**`, `mc/src/client/java/dev/cyberblock/client/cyber/**`, `cyberblock.cyber.mixins.json`, `cyberblock.cyber.client.mixins.json`, `mc/src/test/java/dev/cyberblock/cyber/**` | Opus | 25752 / `Local\CyberblockFrame1C` / 25912 |
| **1L** client relay | `HostLink` → relay (LOCAL/UP/DOWN by section 1.2), `hstate`, client handshake, reconnect, `session` to the DLL, roster store, every client LOCAL sender on `HostLink.send`, Gradle run properties | `mc/src/client/java/dev/cyberblock/client/**` **except** `client/cyber/**`; `cyberblock.client.mixins.json`, `cyberblock.ui.client.mixins.json`; `mc/build.gradle`, `mc/gradle.properties`, `mc/settings.gradle`; `mc/src/test/java/dev/cyberblock/client/**` | Opus | 25753 / `Local\CyberblockFrame1L` / 25913 |
| **1H** host side | DLL `hello.ver`, `session` parsing into a script event, `Cyberblock_EventText`, the cleanup path, probe reset on a new session | `cp/plugin/**`, `cp/scripts/**`, `tests/test_reds_ids.py` | Sonnet | — |
| **1T** tools, protocol, docs | protocol "Multiplayer" section, fake host multi-instance, `tools/mpcheck.py`, `tools/content_check.py` and schemas, the dedicated-server template, spec amendment, docs, Re-test 1 | `shared/protocol.md`, new `shared/content-schemas/**`, `tools/fakehost.py`, new `tools/mpcheck.py`, new `tools/content_check.py`, new `server/**` (templates only), `tests/test_protocol_md.py`, `tests/test_fakehost.py`, new `tests/test_mpcheck.py`, new `tests/test_content_check.py`, `tests/test_hygiene.py`, `docs/**` **except** plans, `README.md`, `CHANGELOG.md` | Sonnet | 25754 (harness runs use 25914-25918) |

Lang owner in Phase 1: **1L** (`en_us.json` is under `mc/src/main/resources/assets/cyberblock/lang/`; 1L owns it for
this phase). Merge order: 1N, 1W, 1C, 1L, 1H, 1T. 1W, 1C and 1L build against 1N's section 1.4 API with uncommitted
stand-ins until 1N merges.

### 1-N1. Payloads and networking
**Files:** `net/Payloads.java`, `net/ServerNet.java` (new), `fabric.mod.json`, `net/PayloadsTest.java`.
- [ ] Verify in the Fabric API and 26.3 sources: the payload type registry (play C2S and S2C), the server and client
  play networking receivers and senders, the thread they run on, the custom payload size limits both ways. Record the
  names and limits in the class header; adjust the caps of section 1.3 if 26.3 differs.
- [ ] Records and codecs for every section 1.3 payload; registration on both sides (common init registers types; client
  receivers are registered by 1L through `ClientNet.init()` from its own entrypoint).
- [ ] Tests: codec round trips, caps (an over-cap string is refused at encode).
**Done:** `gradlew test` passes.

### 1-N2. Sessions, handshake, routing
**Files:** `net/HostSessions.java`, `net/HostSession.java`, `net/Handshake.java`, `net/Permissions.java`,
`net/RequestIds.java`, `Bridge.java`, `Cyberblock.java`, tests.
- [ ] Section 1.4 API exactly, section 1.5 handshake (10 s timeout, version rules, host rules, messages), `hs_ack`,
  `cyberblock:roster` on every change, `HostSessions.onStart/onEnd/listen`, the `hstate` fields, `Cyberblock.hostActive`.
- [ ] `Bridge`: remove the `host*` statics, `listeners`/`listen`/`dispatch` (server listeners use `HostSessions.listen`,
  client listeners use 1L's `HostLink.listen`); keep the proxy hooks with an owner parameter
  (`proxyById(UUID owner, long id)`, `proxyOwnerOf(Entity)`), `installedCyberware` becomes per player (1C's
  `CyberData`), `surveyCommand` goes (the server command sends `survey_cmd` DOWN to the issuing host, 1-L1).
- [ ] `Cyberblock.java`: remove `active`, `events`, `launching`; call `WorldModule.init()` (1W) and keep the
  `/cyberblock` root; add `/cyberblock guests <toggle> on|off` (the host for its own game), `/cyberblock hosts`
  (ops: list sessions, roles, versions, rates).
- [ ] Tests: lifecycle (NONE → LINKED → ACTIVE → end), `maxHosts`, role rules, version messages, request-id routing and
  expiry, end cleanup hooks called once.
**Done:** `gradlew test` passes.

### 1-N3. Envelope checks, rate limits, permissions
**Files:** `net/RelayGuard.java`, `net/TokenBucket.java`, `net/Sanity.java`, tests.
- [ ] Section 1.6 exactly: type whitelist, parse and `t` match, size, token buckets per type and session (with the
  `rate` multiplier), the sanity radius per type (positions read with the existing `Wire`/`Ranges` parsers), request-id
  checks for replies, counters and the 5 s log line, the overflow kick, guests' `up` drops and kick.
- [ ] Permission toggles stored in SavedData `cyberblock_hosts`, `cyberblock:perms` receiver, `Permissions.allows(owner,
  actor, toggle)`.
- [ ] Tests: every rule with a fake clock; a flood test (10 000 `ground` messages) stays within the budget.
**Done:** `gradlew test` passes.

### 1-N4. Server config and the content loader skeleton
**Files:** `net/ServerConfig.java`, `content/ContentLoader.java`, `content/ContentKind.java`, `content/Content.java`,
tests.
- [ ] `config/cyberblock/server.json` (section 1.13): create with defaults, validate, log and replace bad values.
- [ ] The loader of section 1.7: reads `data/<ns>/cyberblock/<kind>/*.json` on server start and `/reload` (verified
  Fabric reload listener), keeps immutable snapshots per kind, logs `content: loaded ...`, skips bad files, sends
  client-side kinds through `cyberblock:content`. No kinds yet besides a `test` kind for the tests; later phases register
  theirs with `ContentKind.register(id, parser, clientSide)`.
- [ ] Dev-only test commands behind `-Dcyberblock.devtools=true` (never on by default): `/cyberblock test hit <host>
  <id> <dmg>`, `test explode <x> <y> <z> <power>`, `test session <host>` (dump), used by `tools/mpcheck.py` to act as a
  guest through the same code paths.
**Done:** `gradlew test` passes.

### 1-W1. World track onto sessions
**Files:** every 1W file; `WorldModule.java` (new: registers the world track's events, moved out of `Cyberblock.java`).
- [ ] Replace every `Cyberblock.events.accept` with `HostSession.send` to the right session(s) per section 1.2 (owner,
  "within 128 m", "that host's player") and every server read of `Cyberblock.active`/`Bridge.host*` with the session's
  fields; `PlayerMixin` and other two-sided code use `Cyberblock.hostActive(Player)`.
- [ ] UP handlers via `HostSessions.listen`: `ground`, `unground`, `clear`, `npcs`, `dmg`, `mobdmg`, `pcells`,
  `unlead`, `hello` (resync: `align`, `blocks reset:true`, `effect` set, in one tick; the old link-thread `ALIGN_LOCK`
  path goes), `hstate`, `state`, `aim`, `stats`.
- [ ] Per-session state: barriers (and Q5's outline guard) per session; proxies owner-scoped (`ProxyEntity` stores the
  owner UUID; `ProxyManager.byId(owner, id)`); block events delivered to every active session whose player is within
  256 blocks of the cell (resync per session too); `HostDamage` damages that host's `ServerPlayer`; `MobWar`,
  `Projectiles`, `PlayerEffects` per host player; pearl teleports and `pteleport` DOWN to the thrower's session.
- [ ] Guest toggles: an explosion or wind charge caused by a guest reaches a host only with `explosions`; a guest's hit,
  ignite, lead or pull on a host's proxy only with `damage` (otherwise `cyberblock.guest.blocked` to the guest).
- [ ] `onEnd`: remove that session's barriers, temp cells, proxies, mob targets, tethers.
- [ ] Tests: convert every existing test to sessions; new tests for owner-scoped ids (two owners, same id), routing by
  distance, toggles, end cleanup, resync order (`align` immediately followed by `blocks reset:true` in one session queue).
**Done:** `gradlew test` passes; no `Cyberblock.events` or `Cyberblock.active` left in `mc/src/main`.

### 1-C1. Cyber track onto sessions
**Files:** every 1C file.
- [ ] Scans: the client sends `cyberblock:act t:"scan"` (target or null, eye, look direction); the server's `Hacking`
  picks with the owner-scoped proxy aimer, checks range, RAM and the owner's `hacks` toggle (guests), sends `scan` DOWN
  to the owner with a session request id, and returns `scaninfo` to the asker through `cyberblock:ui t:"scaninfo"`.
  `target:null` is allowed only for the host's own game.
- [ ] Hacks: the same route (`act t:"hack"`), `hackresult`/`hackspread` UP from the owner, effects as today on the
  server; `HackFx` follows owner-scoped proxies.
- [ ] `perks`, `cyberware`, `cyberfx` DOWN to the player's own session (only hosts have one; guests keep their perks and
  implants Minecraft-side); `Bridge.installedCyberware` becomes `CyberData` per player; client `CyberLink` listeners use
  1L's `HostLink.listen` for LOCAL data and `cyberblock:ui` for server answers.
- [ ] Tests: a guest scan and hack routed to the owner; refused by the toggle; request ids; perks/cyberware go only to
  the owning host.
**Done:** `gradlew test` passes.

### 1-L1. Relay and LOCAL senders
**Files:** `client/HostLink.java`, new `client/net/Relay.java`, `client/net/ClientNet.java`, `client/net/HState.java`,
`client/net/Roster.java`, `client/atlas/SurveyClient.java`, `client/ui/UiLink.java`, `client/StatusReporter.java`,
`client/ClientInput.java`, `client/Flight.java`, `client/HostState.java`, `client/CyberblockClient.java`, tests.
- [ ] `HostLink.send(String)` (to the DLL) and `HostLink.listen(t, listener)` (client listeners, replacing
  `Bridge.listen` on the client); `HostLink.onMessage` classifies by section 1.2: LOCAL handled on the client, UP sent
  through `Relay.up` (split to the cap), BOTH both. `cmd` stays local and only in a dev client.
- [ ] `cyberblock:down` → `HostLink.send` verbatim when this client's session is host and the DLL is linked; `survey_cmd`
  goes to `SurveyClient`.
- [ ] `hstate` builder (5 Hz and on change) from `HostState` and `Flight`; `Cyberblock.hostActive` predicate for the
  client side.
- [ ] Client handshake (`hs` on join, on DLL link up/down and on a DLL `ver` change), `hs_ack` handling (toast and chat
  for reasons), `session` to the DLL after every result and on disconnect (`none`), the reconnect screen of section 1.5,
  `Roster` store (read by Phase 5 for skins, by the HUD for markers).
- [ ] `build.gradle`: `cyberblock.runDir` (a run folder per client), `cyberblock.serverPort` and a `runServer` config
  with an `eula`, `online-mode=false`, `server-ip=127.0.0.1` template copied from `server/test-server.properties` (1T);
  `--username` passes through `--args`.
- [ ] Tests: classification of every `t` of section 1.2 (a table test), splitting under the cap, DOWN forwarding only
  for the host role, reconnect backoff schedule, `hstate` change detection.
**Done:** `gradlew test build` passes; `runClient` twice with two run folders works.

### 1-H1. DLL and scripts for sessions
**Files:** `cp/plugin/src/link.cpp`, `world.hpp/.cpp`, `natives.cpp`, `probes.cpp`, tests; `cp/scripts/Cyberblock/
Natives.reds`, `Cyberblock.reds`, new `Session.reds`.
- [ ] DLL `hello` adds `ver` (`CB_VERSION`); log `hello ver <ver>`.
- [ ] Parse `session` (validate per section 1.2); queue event `session` (name = state, text = reason, extra = perms);
  `Cyberblock_EventText`. On a change **to** `host` the DLL forgets its probe state and sends `clear`, so the new server
  session receives the whole ground again; on `none`/`guest`/`refused` it does what a link drop does on the DLL side
  (unpause, flight stop) and keeps the socket.
- [ ] Scripts: on `session` not `host`: `CyberblockCollision.Reset`, puppets despawned, tethers released, survey stopped,
  log `session: cleaned (<reason>)`; a `refused` reason shows once as Cyberpunk's own warning only when Minecraft cannot
  draw it (not linked).
- [ ] Tests: `session` validation, the event, the probe reset and `clear`.
**Done:** `cb_tests` and compile_check pass.

### 1-T1. Protocol
**Files:** `shared/protocol.md`, `tests/test_protocol_md.py`.
- [ ] A "Multiplayer (Phase 1)" section: the section 1.2 tables, `hstate`, `session`, `hello.ver`, `pteleport`
  (documented now), `survey_cmd` (client-only), the payload table of 1.3 (for reference), rate limits; VALIDATORS for the
  new messages.
**Done:** `uv run pytest -q` passes.

### 1-T2. Fake hosts and the multiplayer harness
**Files:** `tools/fakehost.py`, new `tools/mpcheck.py`, new `server/test-server.properties`, new
`server/server.json.example`, tests.
- [ ] Fake host: `--name` (log prefix), `--ver` (fake DLL version for mismatch tests), `--ids-from N` (to force id
  collisions between two fake hosts), prints `session`.
- [ ] `tools/mpcheck.py` (from a worktree, never the main checkout): checks commit (< 60 GB), starts a dedicated server on
  the given port with `-Dcyberblock.devtools=true` and a per-run random RCON password that lives only in memory and in the
  temporary run folder, starts clients (`--quickPlayMultiplayer`, offline names) and fake hosts, runs scenarios, asserts
  on logs and RCON answers, stops everything it started. Scenarios: `singleplayer` (one client, integrated server, the
  existing fake host modes through the relay), `guest` (host + guest: barriers, proxies, a guest hit and explosion reach
  the fake host, toggles block them), `versions` (mod and DLL mismatch messages), `flood` (rate limits and the kick),
  `reconnect` (host drop: barriers and proxies gone; return: `blocks reset:true` and the whole ground again),
  `twohosts` (with `maxHosts:2` in the run config: colliding ids, each fake host gets only its own hits). `--all` runs
  them in order.
**Done:** `uv run pytest -q` (unit parts) passes; `tools.mpcheck --all` passes in the integration worktree (integrator).

### 1-T3. Content check
**Files:** `tools/content_check.py`, `shared/content-schemas/test.schema.json`, `tests/test_content_check.py`.
- [ ] Validates shipped defaults and `--dir <datapack>`: JSON parses, `schema` 1, the kind's JSON Schema, byte caps, id
  references resolve; exit code and a readable report.
**Done:** `uv run pytest -q` passes.

### 1-T4. Docs
- [ ] Spec amendment (online play is now a goal; the architecture of this plan's top); HOW-TO-PLAY "Playing together
  (LAN)" (open to LAN, a guest joins, what reaches V, toggles, messages); CHANGELOG; Re-test 1.

### Phase 1 integration and deliverables
- Merge order; all suites including `tools.mpcheck --all` and `tools.content_check`; grep that `Cyberblock.events`,
  `Cyberblock.active` and `Bridge.listen` are gone from `mc/src/main`; install.
- **In-game checks (Re-test 1):** (1) singleplayer with Cyberpunk: a short pass of Re-test 3 (blocks, TNT, a hack, the
  glide, the UI) behaves as before; `handshake: ... -> host` in latest.log; (2) Open to LAN, start a guest with
  `uv run python -m tools.mpcheck --guest <your LAN port>` (it launches one offline client and nothing else): the guest
  places TNT next to V and it explodes in Night City; with `/cyberblock guests explosions off` it does not; (3) the guest
  hits an NPC's hitbox (F3+B shows proxy boxes) and the NPC flinches; (4) quit and reopen the world: no leftover
  collision in Night City, the ground comes back; (5) start Cyberpunk with a mismatched plugin (not needed if versions
  match; the harness covered it) and read the messages in TESTING-PLAN.
- **What to try:** singleplayer as usual; Open to LAN plus the harness guest; TNT and hits from the guest; the guest
  toggles; quit and rejoin.
- **Descopes and risks:** `maxHosts` stays 1 until Phase 3; the payload size cap may force more splitting than planned
  (measured in 1-N1); a guest cannot see NPCs yet (figures come in Phase 3).

---

## 6. Phase 2: Apex deployment

**After Phase 2 the user can:** run the Cyberblock world on the Apex server (Rod's Ores safely backed up and one panel
change away), whitelist friends, and hand them a modpack that installs everything (mod, Fabric API, proximity voice chat,
resource pack) so they join with two clicks; a friend joins and plays with the user's Cyberpunk game.

**Depends on:** Phase 1; Phase 0 (a) answers (Java 25, custom jar, disk, ports). If (a) failed, the local-server
fallback of section 4.1 replaces the Apex steps; everything else in this phase is the same.

| Pkg | Scope | Owns (exclusive) | Model |
|---|---|---|---|
| **2S** server sync tool | `tools/server_sync.py`: backup (download mirror with manifest), verify, stage, upload; secrets rule 0.6 | new `tools/server_sync.py`, new `tests/test_server_sync.py`, `pyproject.toml`, `uv.lock` (dev dependency `pyftpdlib` for the local FTP test server) | Opus |
| **2K** kit | modpack, resource pack, server bundle, join button | new `tools/modpack.py`, `tools/respack.py`, `tools/server_bundle.py`, new `respack/**` (pack sources: `pack.mcmeta`, original assets only), tests `tests/test_modpack.py`, `tests/test_respack.py`, `tests/test_server_bundle.py`; `mc/src/client/java/dev/cyberblock/client/AutoWorld.java` (the "Join Cyberblock" title button) | Sonnet |
| **2M** server admin bits | dedicated-server polish: `joinHelp` on refusals, whitelist warning, `/cyberblock status`, `/cyberblock hosts add|remove`, startup line | new `mc/src/main/java/dev/cyberblock/net/admin/**`, `Cyberblock.java` (command registration only), tests | Sonnet |
| **2D** docs | `docs/SERVER.md` (Apex steps and the way back), `docs/JOIN.md` (friends), HOW-TO-PLAY, README "Play together", CHANGELOG, Re-test 2 | `docs/**` except plans, `README.md`, `CHANGELOG.md`, `tests/test_hygiene.py` | Sonnet |

Lang owner in Phase 2: **2K**. Merge order: 2S, 2M, 2K, 2D.

### 2-S1. `tools/server_sync.py`
- [ ] Reads `APEX_FTP_HOST`, `APEX_FTP_USER`, `APEX_FTP_PASS`, `APEX_FTP_PORT` (default 21) and `APEX_FTP_BASE`
  (default `/default`) from the environment; with no password and an interactive terminal it asks with `getpass`
  (hidden). **It never prints, logs or writes the password, the host or the user name** (log lines say "the server");
  exceptions are caught and re-raised without the connection string.
- [ ] `backup --to <folder>`: a full recursive download of the server folder (resumable, skips files already complete
  by size and modification time), then `manifest.json` (relative path, size, sha256) and a summary (files, bytes, the
  `level-name` read from the downloaded `server.properties`, the jar files present at the root). Refuses a destination
  inside the repo. Optional `--from-mount <folder>`: copy from the user's read-only mount instead (no credentials).
- [ ] `verify --backup <folder>`: re-hashes against the manifest.
- [ ] `upload --stage <folder>`: uploads exactly the staged files (relative paths), one by one, with a dry-run default
  (`--go` to really upload); **there is no delete command at all**, and it refuses to overwrite a remote file that is not
  in an allowlist (`server.properties`, `server.properties.*`, `mods/*`, `config/cyberblock/*`, `cyberblock.zip`, the
  Fabric launcher jar name), so Rod's Ores files cannot be clobbered.
- [ ] Tests against a local `pyftpdlib` server on 127.0.0.1 with throwaway credentials generated in the test: backup,
  resume, manifest, verify, upload allowlist, dry run, no secret in any output (captured stdout/stderr and log scanned).
**Done:** `uv run pytest -q` passes.

### 2-K1. Modpack (`tools/modpack.py`)
- [ ] Builds `dist/cyberblock-<ver>.mrpack` (git-ignored): `modrinth.index.json` (format 1, game `minecraft`,
  dependencies `minecraft 26.3` and `fabric-loader` ≥ 0.19.5), files Fabric API and the voice chat mod named in Phase 0
  (official Modrinth download URLs with sha1/sha512 computed from jars **the user downloaded** into a folder given with
  `--mods-from`, outside the repo), overrides: `mods/cyberblock-<ver>.jar`, `resourcepacks/cyberblock-pack-<ver>.zip`,
  `options.txt` defaults that enable the pack, `config/cyberblock/join.json` (server name and address from a local,
  untracked `--server-file`; never committed), `servers.dat` with the same entry.
- [ ] Tests: index schema, hashes, overrides, no address in any tracked file.
**Done:** `uv run pytest -q` passes.

### 2-K2. Resource pack (`tools/respack.py`)
- [ ] Builds `dist/cyberblock-pack-<ver>.zip` from `respack/` (original assets only: the server-side content textures
  that may change without a mod release, the pack icon), prints its SHA-1 for `server.properties`
  (`resource-pack-sha1`). The mod jar keeps carrying its own assets; the pushed pack is for content textures friends add
  later (skins of Phase 5, device and block art) without a mod update.
- [ ] Where the pack is served from: (A) a GitHub release asset of the public repo, **only after the user says "push"**;
  (B) any HTTPS host the user picks. Until one exists, `require-resource-pack` stays off and the modpack's copy is used.
**Done:** `uv run pytest -q` passes.

### 2-K3. Server bundle (`tools/server_bundle.py`)
- [ ] Stages into `%LOCALAPPDATA%\Cyberblock\server-staging\` (outside the repo): `mods/` (the Cyberblock jar, the Fabric
  API and voice chat jars from `--mods-from`), `config/cyberblock/server.json` (from `server/server.json.example`,
  `hosts` from the user's answer), `server.properties` (the backup's own file with exactly these keys changed:
  `level-name=cyberblock`, `allow-flight=true`, `white-list=true`, `enforce-whitelist=true`, `motd=Cyberblock`, the
  resource-pack keys when a URL exists), `server.properties.rodsores` (a byte-identical copy of the backup's file),
  `cyberblock.zip` (the user's world folder `cyberblock` with its `datapacks/` folder and dimension data, zipped with
  `level.dat` at `cyberblock/level.dat`, made only
  while Minecraft is closed: the tool refuses when `session.lock` is held), and the Fabric server launcher jar for 26.3
  (from `--launcher`, a file the user downloaded from fabricmc.net, or downloaded by the agent only after the user says
  yes). It prints the list with sizes and the exact jar name for the panel.
**Done:** tests pass; a dry run on a fake backup prints the right list.

### 2-K4. Two-click join
**Files:** `client/AutoWorld.java`.
- [ ] When `config/cyberblock/join.json` exists, the title screen shows **Join Cyberblock** (next to Play Cyberblock); it
  connects to the configured server. Two clicks after the one-time import: launcher **Play**, then **Join Cyberblock**.
**Done:** `gradlew test build`; screenshot.

### 2-M1. Admin bits
- [ ] Startup line `cyberblock server: ready (protocol 1, maxHosts N, requireMod, whitelist on|OFF)`; a loud warning
  every minute while a dedicated server runs with the whitelist off; `joinHelp` (server config) appended to refusals;
  `/cyberblock status` (sessions, roles, versions, rates, content counts); `/cyberblock hosts add|remove <name>` (ops,
  edits `hosts` in `server.json`).
**Done:** `gradlew test` passes.

### 6.3 Deployment day: exact steps (agent and user)
The user does every panel action; the agent does the file work and says when to click. Nothing on the server is deleted.
Labels in **bold** are the ones Apex shows; Phase 0 (a) confirmed them from the user's screenshots (if a label differs,
the agent uses the confirmed one in the message to the user).

**A. Before the day (agent):**
1. Build everything (Phase 1 suites green), `tools.modpack`, `tools.respack`.
2. Ask the user to download, into a folder outside the repo, the Fabric API jar and the voice chat jar for 26.3 from the
   Modrinth pages Phase 0 listed, and the Fabric server launcher jar for 26.3 from fabricmc.net (or to allow the agent to
   download them).
3. Ask which local world to upload: the dev world (`<repo>\mc\run\saves\cyberblock`) or the launcher world
   (`%APPDATA%\.minecraft-cyberblock\saves\cyberblock`); ask the user to close Minecraft.

**B. Backup (user, then agent):**
1. User: open the Apex panel, select the server, click **Stop**, wait until the status shows **Offline**.
2. User (if the panel has backups): **Backups** → **Create Backup** → wait for it to finish.
3. Agent: `uv run python -m tools.server_sync backup --to "%USERPROFILE%\CyberblockServerBackups\rods-ores-<date>"`
   The default is that **the user runs this exact line in their own terminal**, where the tool asks for the password
   hidden; the agent runs it itself only when `APEX_FTP_PASS` is already set in its environment (it checks only whether
   it is set). Either way the password is never seen, typed or printed by the agent. Or `--from-mount` if the user's read-only mount is
   up.
4. Agent: `tools.server_sync verify`, then reports: file count, total size, the `level-name` found, the jar at the root,
   the plugin list. **The user confirms the numbers look complete before anything else happens.**

**C. Upload (agent):**
1. `tools.server_bundle` stages the files (2-K3); the agent shows the list.
2. `tools.server_sync upload --stage ... ` (dry run), shows what would be written, then `--go`.

**D. Panel changes (user, click by click):**
1. **File Manager** → open the root folder → find `cyberblock.zip` → **Extract** (or **Unarchive**) → confirm that a
   folder `cyberblock` appeared with `level.dat` inside (`/cyberblock/level.dat`). (If the File Manager cannot extract:
   the agent uploads the world folder file by file instead; slower.)
2. **Java version** (on the panel's server settings page) → choose **Java 25** → **Save**. Note the old value (Phase 0
   recorded it) for the way back.
3. **Server type / jar** → choose **Custom Jar** (or the panel's **Fabric 26.3** entry if Phase 0 found one) → type the
   jar name the agent gives (the launcher jar's file name) → **Save**.
4. Leave the server name as **Cyberblock**.
5. Click **Start** and open **Console**. Expect `Done`, `cyberblock loaded`, `cyberblock server: ready (protocol 1,
   maxHosts 1, requireMod, whitelist on)`.
6. In the console type `whitelist add <your name>` and one line per friend, then `op <your name>` (if you are not an op
   already). Optional: `cyberblock hosts add <your name>` to allow only you to host.

**E. First join (user):**
1. Start Minecraft with the Cyberblock installation, **Multiplayer** → **Add Server** → the address → **Join** (or the
   new **Join Cyberblock** button when the modpack's `join.json` is present). Expect the resource pack prompt (accept)
   and `handshake: server <ver> -> host` in latest.log once Cyberpunk is linked.
2. Start Cyberpunk, load a save, F9. The barriers, blocks and NPC proxies now live on the Apex server.

**F. Friend join test (user and a friend):**
1. Send the friend `dist/cyberblock-<ver>.mrpack` and `docs/JOIN.md` (import in the Modrinth App or Prism Launcher:
   **File** → **Import** / drag the file in; then **Play** → **Join Cyberblock**).
2. The friend joins, hears you through proximity voice chat, places TNT near Steve: it explodes in your Night City.

### 6.4 The way back to Rod's Ores (user, about 2 minutes; nothing was deleted)
1. Panel: **Stop**.
2. **Server type / jar** → back to the jar Rod's Ores used (its exact name is in the backup summary of step B4; for
   Paper 1.21.11 that is the Paper entry or the custom jar name recorded) → **Save**.
3. **Java version** → back to the old value recorded in Phase 0 → **Save**.
4. **File Manager** → rename `server.properties` to `server.properties.cyberblock`, then rename
   `server.properties.rodsores` to `server.properties` (it holds Rod's `level-name` and every original setting).
5. **Start.** Paper ignores the Fabric files (`mods/`, `config/cyberblock/`, `.fabric/`, `libraries/`, `versions/`, the
   `cyberblock` world folder); Paper's own `config/paper-*.yml` were never touched. Rod's worlds, plugins and configs are
   exactly as they were (the PC backup of step B3 can restore any file with `tools.server_sync upload` if ever needed).
To switch to Cyberblock again: the same four changes the other way (jar, Java, the two renames), then **Start**.

### Phase 2 deliverables
- **Re-test 2 (in game):** the deployment day steps above, ticked; the friend join test; the way back tried once and
  then forward again (optional, when convenient).
- **What to try:** join your own Apex server with Cyberpunk; give a friend the modpack; voice chat; TNT and hits from
  the friend; flip a guest toggle.
- **Descopes and risks:** the resource pack stays inside the modpack until a pack URL exists (push or a host of your
  choice); the voice chat UDP port depends on the plan; first start downloads the server (a few minutes); world upload
  speed over FTP.
- **Asking the user:** at the start of this phase, for the downloads of 6.3 A and the panel actions of 6.3 B, D and E;
  **after this phase**, for the Apex facts Phase 3 needs: whether the friend test passed, where the resource pack should
  be hosted (a GitHub release after "push", or another host), and whether an extra TCP port for a web map exists.

---

## 7. Phase 3: the Night City replica

**After Phase 3 guests see Night City as Minecraft:** visible material blocks built from every host's probes, saved in
the world so the map grows as anyone explores and stays when the host is offline; NPC and vehicle figures moving at
real speed; Cyberpunk's time of day and weather; neon as glowing blocks; a live web map if a mod for 26.3 exists. Guests'
blocks, TNT and hits reach the host; guests can build while the host is away (Offline Builds) and the host sees what
changed; Meet Up teleports V to a friend at the same coordinates. Creative may break replica blocks under the Q5 rule;
breaking never changes the real Night City. A second host can join at once (`maxHosts` 4).

**Depends on:** Phase 1 (sessions), Phase 2 for the real-server test (development works on the local test server),
Phase 0 (b) material, (g) teleport. Fallbacks: (b) heuristic palette; (g) survey-style hold.

| Pkg | Scope | Owns (exclusive) | Model |
|---|---|---|---|
| **3R** replica, server | replica blocks from `ground`/`unground`, saved, the `seen` rule, materials through `material_palette`, Q5 rule for replica, shared Night City map store (terrain UP), Offline Builds log, multi-host check | new `mc/src/main/java/dev/cyberblock/replica/**`, `WorldBridge.java`, `BlockEvents.java`, `BlockSync.java`, `PlacedBlocks.java`, `OutlineRules.java`, `OutlineGuard.java`, `SafeSpots.java`, `ExplosionEvents.java`, `net/ServerConfig.java` (the `maxHosts` default), tests | Opus |
| **3C** replica, client | replica blocks invisible on an active host's client (they would double Night City), visible for guests; the shared map on the guest Atlas | new `mc/src/client/java/dev/cyberblock/client/replica/**`, `client/atlas/**`, `client/net/**` (the `terrain` classification flips to BOTH), `cyberblock.client.mixins.json`, tests | Opus |
| **3F** figures | proxies become visible figures for guests (a plain human figure and a plain vehicle box until Phase 5), hidden for active hosts, interpolation at real speed, wrecks stay a few seconds | `ProxyEntity.java`, `ProxyManager.java`, `ProxyEntities.java`, `ProxyDiff.java`, `ProxyAim.java`, new `mc/src/client/java/dev/cyberblock/client/figure/**`, tests | Opus |
| **3E** environment, Meet Up | time and weather from the primary host, Meet Up, V teleport and park requests, the Offline Builds summary on the host's return | new `mc/src/main/java/dev/cyberblock/env/**`, `meetup/**`, `client/ui/**` (the summary toast and the Meet Up prompt), tests | Opus |
| **3S** scripts | material per probe hit, `seen` support, time and weather read, `Teleport.reds`, `Park.reds` | `cp/scripts/**` | Opus |
| **3D** DLL | probe materials and `seen`, `env`, `vteleport`/`park` events and results, builders, tests | `cp/plugin/**` | Opus |
| **3A** art and data | replica block textures and models, `material_palette` defaults, lang | `art/**`, `mc/src/main/resources/assets/cyberblock/**`, `mc/src/main/resources/data/cyberblock/cyberblock/material_palette/**`, `shared/content-schemas/material_palette.schema.json` | Sonnet |
| **3P** protocol, tools, web map, docs | protocol rows, fake host `--replica`, mpcheck `replica`/`twohosts2`/`meetup`, web map check and docs, Re-test 3 | `shared/protocol.md`, `tools/**`, `tests/**` (python), `docs/**` except plans, `README.md`, `CHANGELOG.md` | Sonnet |

Lang owner: **3A**. Merge order: 3D, 3S, 3A, 3R, 3F, 3C, 3E, 3P.

### 3-R1. Replica blocks
**Files:** `replica/ReplicaBlocks.java`, `replica/ReplicaRules.java`, `replica/Palette.java`, `WorldBridge.java`,
`BlockEvents.java`, tests.
- [ ] `ground` ranges place replica blocks (section 7 blocks registered by 3A: `cyberblock:nc_<material>`) into **air
  cells only** (never over a player's block); the material comes from `ground.m` through `material_palette`, else the
  heuristic entries. Replica blocks are solid full blocks: they are the host's collision now (barriers stay only for
  `pcells` temp cells and for cells a player's block already occupies).
- [ ] `unground` removes replica blocks only with `seen:true` (observed empty); leaving the probe radius keeps them (the
  growing map). `clear` no longer removes replica blocks (only outline barriers and temp cells).
- [ ] Replica changes are host ground (`Cause.HOST_GROUND`): never sent to any host as player blocks.
- [ ] Unbreakable in survival (like barriers); creative follows Q5's `OutlineRules` with `Kind.REPLICA` (snap back after
  10 s or when probes report the cell; permanent erase keeps it broken until a probe report); breaking never changes
  Cyberpunk (no event).
- [ ] Explosions never remove replica blocks (Night City does not break); a guest's TNT still reaches the host's game
  as an `explosion` (toggle).
- [ ] Door and device filter hook: `ReplicaRules.excluded(cell)` (Phase 4 fills it from device boxes; with Phase 0 b's
  hit object, the scripts already drop door hits).
- [ ] Tests: air-only placement, `seen` rule, radius keep, Q5 rule matrix for `REPLICA`, no events, explosions.
**Done:** `gradlew test` passes.

### 3-R2. Shared Night City map, Offline Builds log, multi-host
**Files:** `replica/NightCityMap.java`, `replica/ChangeLog.java`, `net/ServerConfig.java`, tests.
- [ ] `terrain` becomes BOTH (1L's classification table entry flips in this phase through 3C's client change): the server
  keeps the cells in the world (`<world>/cyberblock/nightcity-map/`, the Atlas region format) so every client's Atlas and
  the Phase 7 Holo-Table share one growing map; clients get tiles on request (`cyberblock:ui t:"maptile"`).
- [ ] `ChangeLog`: every player block change with player name and time, kept per region for 30 days; per host the last
  session end (SavedData `cyberblock_hosts`).
- [ ] With two fake hosts in overlapping areas (mpcheck `twohosts2`): replica, proxies and routing stay correct; then the
  `maxHosts` default becomes 4.
**Done:** `gradlew test`; `tools.mpcheck --scenario twohosts2` passes.

### 3-C1. Replica rendering and the guest Atlas
**Files:** `client/replica/ReplicaRender.java`, a client mixin on block rendering (verified 26.3 chunk-section render
path), `client/atlas/*`, tests.
- [ ] While this client is an **active host**, replica blocks render as nothing (no faces, no outline unless creative is
  aiming at them, so Q5 still works), so Night City is not drawn twice; guests and inactive hosts see them normally.
  Switching re-meshes the loaded chunks once. `ReplicaRender.registerHostHidden(Block... blocks)` lets later phases hide
  their blocks the same way (Phase 4 device blocks).
- [ ] The Atlas and radar of a guest draw from the server's shared map tiles (3-R2); a host keeps its own store and also
  uploads (BOTH).
**Done:** `gradlew test build`; screenshots of a guest view of a replica street and the same spot as an active host.

### 3-F1. Visible figures
**Files:** `ProxyEntity.java`, `ProxyManager.java`, `ProxyEntities.java`, `client/figure/FigureRenderer.java`,
`client/figure/VehicleFigureRenderer.java`, tests.
- [ ] Proxies get a renderer: a human figure (player model, a neutral default skin from 3A) and a vehicle figure (a box
  model sized by the proxy) for guests; nothing for an active host's own client (it sees the real NPCs). Hostile flag
  tints the name tag red (Phase 5 adds the full look).
- [ ] Interpolation at real speed (the existing 0.5 s extrapolation, smoothed client-side); a vehicle that dies stays as a
  dark wreck for 5 s; a dead human lies down for 3 s, then goes.
- [ ] Figures exist only while their owner's session is active; offline host = saved blocks only, no live figures.
**Done:** tests; screenshots with a fake host `--replica` (figures walking and a car driving past).

### 3-E1. Time of day and weather
**Files:** `env/EnvSync.java`, tests.
- [ ] While a primary host exists: `doDaylightCycle` off and the day time set from its `stats.time`/`env.time`
  (`ticks = ((h·60 + m) / 1440 · 24000 + 18000) mod 24000`), weather from `env.weather` (rain, storm = thunder,
  others clear) as Phase 0 allowed; with no host the world keeps the last values. A second host never changes them.
**Done:** tests of the mapping.

### 3-E2. Meet Up
**Files:** `meetup/MeetUp.java`, `client/ui/MeetUpPrompt.java`, tests.
- [ ] `/cyberblock meetup <player>` sends a request; the target accepts with a clickable chat line (or `/cyberblock
  meetup accept`) within 60 s. Host to anyone: the server sends `vteleport` (the target's position, `why:"meetup"`) to the
  host's session; the DLL and scripts teleport V there (Phase 0 g: wait for streaming, ground snap); on `vteleported ok`
  Steve follows V; on failure the reason shows. Guest to a host: the guest is teleported next to the host's Steve. 30 s
  cooldown; not into a match zone (Phase 8 rule hook); not while the requester's host is held or in a vehicle.
**Done:** tests; mpcheck `meetup` passes with a fake host answering `vteleported`.

### 3-E3. Offline Builds summary
**Files:** `env/OfflineBuilds.java`, `client/ui/OfflineBuildsToast.java`.
- [ ] When a host's session starts, the usual resync sends every placed block (so guests' builds made while it was away
  are in Night City at once), and the host gets a toast "While you were away: N blocks placed, M removed (by A, B)" and
  `/cyberblock changes` lists them by region with coordinates.
**Done:** tests.

### 3-S1. Probe materials and `seen`; time and weather; teleport and park (scripts)
**Files:** `Probes.reds`, new `Env.reds`, new `Teleport.reds`, new `Park.reds`, `Natives.reds`.
- [ ] Probe hits carry the material (Phase 0 b) mapped to the palette codes in the scripts' table (codes shared with
  `material_palette` through `shared/vectors.json` key `materials`, regenerated by `tools.gen_vectors`); door and device
  hits are dropped when the hit object is a device (b).
- [ ] `Env.reds`: game time and weather at 1 Hz (verified calls) through `Cyberblock_SetEnv`.
- [ ] `Teleport.reds`: on event `vteleport`, the Phase 0 (g) method (streaming wait or survey-style hold, ground snap),
  then `Cyberblock_TeleportDone`; refuses in vehicles, scenes, combat (reason text).
- [ ] `Park.reds`: on event `park` on/off, the Phase 0 (g) PARKED state (invisible, invulnerable, unseen by NPCs or the
  fallback, saving locked, camera locked) and back; logs `park: on|off (<reason>)`.
**Done:** compile_check passes; needsInGame: Meet Up to a friend 1 km away; park for a minute next to a ganger.

### 3-D1. DLL for the replica
**Files:** `src/probes.hpp/.cpp`, `src/world.hpp/.cpp`, `src/natives.cpp`, tests.
- [ ] `ProbeSet` reports `seen` cells (a column hit again inside the radius without the old range) separately from cells
  that only left the radius; `ground.m` codes per range; `env` builder (1 Hz, dedup); `vteleport`/`park` parsing and
  events; `vteleported`/`parked` builders. Pure tests.
**Done:** `cb_tests` passes.

### 3-A1. Replica blocks and palette
**Files:** `art/gen_block_art.py` (new), blockstates/models/textures for `nc_concrete_light`, `nc_concrete_dark`,
`nc_asphalt`, `nc_sidewalk`, `nc_glass`, `nc_metal`, `nc_brick`, `nc_tile`, `nc_ground`, `nc_water` (still, visual),
`nc_neon_<16 colours>` (light level 15), the neutral figure skin; `material_palette` defaults (material names from Phase 0
b → block, and the heuristic entries); lang.
**Done:** the art script runs twice byte-identical; `content_check` passes; assets test passes.

### 3-P1. Protocol, tools, web map, docs
- [ ] Protocol rows for `unground.seen`, `ground.m`, `env`, `vteleport`/`vteleported`, `park`/`parked`; fake host
  `--replica` (a street of ground with materials, `seen` removals, figures walking and driving, `env` changes, answers
  `vteleport` after 2 s); mpcheck scenarios `replica`, `twohosts2`, `meetup`.
- [ ] **Web map:** take Phase 0's verdict on a BlueMap- or Dynmap-class mod for Fabric 26.3. If one exists: `docs/WEBMAP.md`
  (install on the server, the extra TCP port from the Apex facts, the custom block colours for `nc_*` blocks through
  that mod's own config or resource format, written by 3P) and `webMap` in `server.json` set by the user. **If none exists
  for 26.3: skip it and say so** in the descopes note and the docs.
- [ ] HOW-TO-PLAY "Night City as Minecraft" (the replica, figures, time and weather, Offline Builds, Meet Up, the Q5 rule
  for replica blocks), CHANGELOG, Re-test 3.

### Phase 3 deliverables
- **In-game checks (Re-test 3):** (1) walk two streets with Cyberpunk, then a guest (harness client or a friend) flies
  over them: replica blocks in roughly the right colours, neon glowing; (2) quit Cyberpunk: the replica stays; the guest
  builds a wall on the street; reconnect: the wall stops V and the toast lists it; (3) the guest sees figures walking and
  cars driving at real speed, a destroyed car leaves a wreck; (4) Cyberpunk night = Minecraft night; rain if readable;
  (5) `/cyberblock meetup <friend>`: V appears at the friend's spot; (6) creative guest breaks a replica block: it returns
  in 10 s, nothing changes in Night City; (7) a second host (when available) plays at the same time.
- **What to try:** fly over your explored streets as a guest; build while the host is away; Meet Up; watch day and
  night follow Cyberpunk; the web map (if it exists).
- **Descopes and risks:** material accuracy depends on (b) (heuristic palette otherwise); weather depends on (b)/(f)
  readers; replica size grows with exploration (disk; Phase 9 measures); render suppression for hosts needs a re-mesh on
  toggle; the web map may be skipped for 26.3.

---

## 8. Phase 4: the device layer

**After Phase 4 everyone uses Night City's devices the Minecraft way:** doors are Minecraft doors (iron for secure ones,
a red lamp when locked), gates slide, lights and neon are lamps in their colour, cameras are eye blocks with a vision
cone, terminals are Net Port blocks, vending machines sell for eddies, traffic lights change, elevators have a call
button, turrets are turret blocks, TVs and billboards are item frames, containers are chests when loot is readable. Both
directions: a guest opens a door and it opens in the host's game; an NPC opens a door and it opens in Minecraft. Hosts can
switch device control off per type.

**Depends on:** Phase 3 (replica, figures), Phase 0 (b) and (c). Fallbacks of (c): position+class ids, 2 Hz polling,
vanilla actions only.

| Pkg | Scope | Owns (exclusive) | Model |
|---|---|---|---|
| **4S** scripts | device scan within 48 m, ids, state, flags, change detection, actions, results, NPC-caused changes | new `cp/scripts/Cyberblock/Devices.reds`, `Probes.reds` (device hit drop), `Natives.reds` (declarations), `compile_check.py` | Opus |
| **4D** DLL | `devices.cpp/hpp`: builders, deltas and the 5 s full list, validators, `devact` events, `devresult`, probe filter by device boxes | `cp/plugin/**` | Opus |
| **4M** devices, server | device registry per owner, the section 1.11 mapping through `device_map`, block placement and both-way sync, action routing with toggles and type bits, quest-locked greying, the replica door filter, vending purchases with Minecraft eddies (looked up by id; 4M never registers the eddies items) | new `mc/src/main/java/dev/cyberblock/device/**`, `replica/ReplicaRules.java`, `net/Permissions.java` (type bits), tests | Opus |
| **4B** device blocks and client | custom blocks and block entities: coloured lamp, eye camera (client particle cone), Net Port, vending machine with its shop screen, three-colour traffic lamp, lift platform and call button, turret block, sliding gate, item-frame screens, crate; the shop screen; the Java registration of the eddies items (`DeviceBlocks.EDDIES`, `DeviceBlocks.EDDIE_CHIP`, the only place they are registered) | new `mc/src/main/java/dev/cyberblock/device/block/**`, new `mc/src/client/java/dev/cyberblock/client/device/**`, tests | Opus |
| **4A** art and data | device block art and models, `device_map` and `shop` defaults, the eddies items' art, models, item definitions and lang (their registration is 4B's), all Phase 4 lang | `art/**`, `assets/cyberblock/**`, `data/cyberblock/cyberblock/device_map/**`, `.../shop/**`, schemas | Sonnet |
| **4P** protocol, tools, docs | protocol rows, fake host `--devices`, mpcheck `devices`, docs, Re-test 4 | as 3P | Sonnet |

Lang owner: **4A**. Merge order: 4D, 4S, 4A, 4B, 4M, 4P.

### 4-S1. Device stream and actions (scripts)
- [ ] Every 0.5 s: devices within 48 m (Phase 0 c method), each with id (persistent id, or the position+class hash
  fallback), class, kind (`device_map` kinds mirrored in the scripts' table), position, yaw, size, state, flags (locked,
  secure, quest-locked, broken, powered) → `Cyberblock_SubmitDevices`; changes (events or the 2 Hz poll fallback) →
  `Cyberblock_DeviceState` with `by` (npc, host, script).
- [ ] Event `devact`: run the action through the device's own persistent-state actions (open, close, lock, unlock, on,
  off, toggle, call, disable, enable, friendly, distract; `breach` belongs to Phase 6), never on quest-locked devices;
  answer `Cyberblock_DeviceResult`.
- [ ] Probe hits whose hit object is a device are dropped (doors are not walls), if (b) gives the hit object.
**Done:** compile_check passes; needsInGame per device kind (open a door from Minecraft, an NPC opens a door, lights
off, a vending purchase, an elevator call).

### 4-D1. Device messages
- [ ] Section 1.9 `devices` (≤ 64 entries, deltas plus a full list every 5 s, positions through `cp_to_mc_pos`), `devstate`,
  `devact` parsing into events, `devresult`; the probe filter: hits inside a known device box never become ground; pure
  tests for every builder and limit.
**Done:** `cb_tests` passes.

### 4-M1. Devices on the server
- [ ] A registry per owner: `devices` UP keeps a device table per session; each device becomes its section 1.11
  Minecraft block or entity at its position (through `device_map`), placed only into air or replica cells (never over a
  player's block), removed when the device leaves the list for 10 s or the session ends; replica cells inside device
  boxes are excluded (`ReplicaRules.excluded`).
- [ ] Both ways: `devstate` updates the block state (door open, lamp lit, traffic colour, lift floor, turret mode);
  a player using the block (right-click a door, a lamp switch, a lift call button, buying) sends `cyberblock:act
  t:"devact"` → permission check (the owner's `devices` toggle and type bit for guests) → `devact` DOWN; the block
  changes only after `devresult ok` (or the next `devstate`), so Minecraft never shows a state Night City refused.
- [ ] Quest-locked devices show greyed (block state `quest_locked`) and say `cyberblock.device.quest_locked`.
- [ ] Vending: the shop screen lists the `shop` entry for that machine kind; prices in Minecraft eddies (section 1.16);
  the item is given server-side.
- [ ] Tests: mapping, placement rules, permissions per type bit, refused actions keep the old state, removal timers.
**Done:** `gradlew test`; mpcheck `devices` passes.

### 4-B1. Device blocks
- [ ] Blocks and behaviour per section 1.11: vanilla wooden/iron doors (driven by the server), a coloured lamp block
  (16 colours, lit/unlit), an eye camera block with a client-side particle vision cone (alarm: red cone), the Net Port
  block (right-click opens Phase 6's Breach; until then a "Phase 6" message), the vending machine block with
  `VendingScreen`, a three-colour traffic lamp, a lift platform that moves between the floors `devstate` reports plus a
  call button, a turret block that turns toward its target and shows its mode, a multi-block sliding gate, item frames with
  a procedural "screen" map image, a crate for unreadable containers and a vanilla chest view for readable loot.
- [ ] Hosts (active) do not see these blocks either (render suppression as replica blocks, 3-C1 hook), guests do.
- [ ] The eddies items of section 1.16 (amended 2026-10-04: no other package registers them; 4M looks them up by id,
  4A ships only their art, item definitions and lang): `cyberblock:eddies` stacking to 64 and `cyberblock:eddie_chip`
  (worth 100) with the tooltip `item.cyberblock.eddie_chip.tooltip`. A test asserts both ids and their max stack size
  (registered in the mod's init, or built through the same properties factory the registration uses, as
  `DeviceTestBlocks` does for blocks).
**Done:** `gradlew test build`; screenshots of every device block.

### 4-A1. Art and data
- [ ] `art/gen_block_art.py` extends with every device texture (original); models; `device_map` defaults for every
  confirmed class; `shop` defaults for vending kinds; art, models and item definitions for the `cyberblock:eddies` and
  `eddie_chip` items (registered in Java by 4B, see 4-B1); lang for
  devices, the shop screen, quest-locked, unsupported, per-type toggles.
**Done:** byte-identical art; `content_check`; assets test.

### 4-P1. Protocol, tools, docs
- [ ] Protocol rows and examples; fake host `--devices` (a door, a light, a camera, a vending machine, an elevator, NPC
  changes every few seconds, answers `devact`); mpcheck `devices`; HOW-TO-PLAY "Devices" (the table of section 1.11 in
  player words, per-type toggles `/cyberblock guests devices <type> on|off`); CHANGELOG; Re-test 4.

### Phase 4 deliverables
- **In-game checks (Re-test 4):** a guest opens and closes a door in front of V (it opens in Night City); an NPC walks
  through a door (it opens in Minecraft); lights off from Minecraft; a camera's cone turns red when it spots the guest; a
  vending purchase with eddies; an elevator call; `/cyberblock guests devices door off` blocks the guest; a quest door
  shows greyed.
- **What to try:** play doorman, light switch and elevator operator for the host; buy from a vending machine; watch NPCs
  use doors.
- **Descopes and risks:** each device action exists only if (c) found a callable action (else greyed "unsupported");
  ids may be position-based (a moved device is a new device); container contents only if readable.

---

## 9. Phase 5: figures, skins and cyber skins

**After Phase 5 Night City's people and cars look the part for guests:** about 30 original procedural NPC skins by
faction and role (female NPCs on the slim model), about 12 vehicle models in their colours, animations from the host
(idle, walk, run, aim, fire, ragdoll, dead), hostile figures with a red outline through walls, a name tag and threat
colour, hit flashes, hearts and explosion particles. **Linked players always wear the cyber skin** (male V, or female V =
Lucy on the slim model, chosen with `/cyberblock body male|female`), with a coloured `[V]` name-tag prefix, a player-list
marker and team-colour outlines for allies.

**Depends on:** Phase 3 (figures), Phase 0 (f). Fallbacks of (f): faction-only skins, motion-based animation flags,
default vehicle colours.

| Pkg | Scope | Owns (exclusive) | Model |
|---|---|---|---|
| **5A** art | the slim conversion of Lucy, about 30 NPC skins, 12 vehicle textures, previews, `skin_map` and `vehicle_map` defaults | `art/**`, `assets/cyberblock/textures/entity/**`, `data/cyberblock/cyberblock/skin_map/**`, `.../vehicle_map/**`, schemas | Opus (quality matters) |
| **5F** figure rendering | skins on figures, animation flags to poses, outlines, name tags and threat colours, hit flashes, hearts, particles, 12 vehicle models in code, interpolation, wrecks | `client/figure/**`, `ProxyEntity.java` (synced data: skin id, anim bits, threat, colour, hp), `ProxyManager.java`, new `mc/src/main/java/dev/cyberblock/figure/**`, tests | Opus |
| **5K** cyber skins and indicators | the forced skin swap from the roster, `/cyberblock body`, `[V]` prefix, list marker, ally outlines | new `mc/src/main/java/dev/cyberblock/skin/**`, new `mc/src/client/java/dev/cyberblock/client/skin/**`, `client/mixin/AbstractClientPlayerMixin.java`, a new tab-list mixin, `cyberblock.client.mixins.json`, tests | Opus |
| **5S** scripts | NPC tags (role, faction, gender, archetype), animation state, vehicle class and colour, hp, through `Cyberblock_SubmitNpcs2` | `cp/scripts/**` | Opus |
| **5D** DLL | the `npcs` extension: native, builder, validators | `cp/plugin/**` | Sonnet |
| **5P** protocol, tools, docs | protocol rows, fake host `--figures`, mpcheck `figures`, docs, Re-test 5 | as 3P | Sonnet |

Lang owner: **5K**. Merge order: 5D, 5S, 5A, 5F, 5K, 5P.

### 5-A1. Cyber skins
- [ ] `art/convert_slim.py`: reads `art/skins/v_female_lucy_classic_template.png` and writes
  `art/skins/v_female_lucy_slim.png` in the slim layout (each arm 3 px wide instead of 4: the front, back, outer and inner
  faces narrowed by one pixel column, top and bottom faces 3 x 4, both the base layer and the overlay, both arms, the
  free pixels transparent); deterministic.
- [ ] `art/preview_skins.py`: front, back and side previews of both cyber skins (classic and slim models) in
  `art/preview_skins.png`; the reviewer checks it visually (no shifted pixels, no seams at the shoulders).
- [ ] Ship both at the section 1.12 paths.
**Done:** both scripts run twice byte-identical; preview attached to the report.

### 5-A2. NPC skins and vehicles
- [ ] `art/gen_npc_skins.py`: about 30 original procedural skins: civilians (corpo, street, worker, tourist, homeless),
  gangs (Tyger Claws, Valentinos, Maelstrom, 6th Street, Animals, Scavengers, Voodoo Boys, Mox, Aldecaldos), corps and law
  (Arasaka, Militech, Kang Tao, NCPD patrol, NCPD SWAT, Max-Tac, Trauma Team, Netwatch), roles (ripperdoc, vendor, fixer,
  bartender), cyberpsycho; each in a male (classic) and female (slim) variant where the role has both; colour schemes
  and motifs are our own (no logos, no copied art).
- [ ] `art/gen_vehicle_art.py`: textures for about 12 vehicle models (sedan, sports car, truck, van, bike, taxi, police
  cruiser with light bar, armoured van, Trauma Team aerodyne, NCPD aerodyne, delivery truck, pickup), tintable by colour.
- [ ] `skin_map` (role/faction/gender/archetype → skin, model) and `vehicle_map` (record/class → model, default colour)
  defaults, including the fallback rows (faction only; unknown gender → classic).
**Done:** byte-identical; `content_check`.

### 5-F1. Figures look and move like Night City
- [ ] Synced figure data per proxy: skin id (from `skin_map` on the server), slim flag, anim bits, threat (from hostile
  and scan threat), colour (vehicles), hp.
- [ ] Animations: idle, walk and run from speed (or `anim` bits), aim and fire poses (arms raised, muzzle flash particle
  on fire), crouch, ragdoll (limp tumble), dead (lying, then removed after 3 s).
- [ ] Hostile figures: a red outline through walls for guests (the Kiroshi outline technique), the name tag with the
  threat colour, a red hit flash and heart particles on damage, explosion particles on death by explosion.
- [ ] Vehicles: 12 models built in Java model code (no copied geometry), coloured by `col`, police cruiser lights
  flashing when the car is in a chase (hostile), wrecks dark for 5 s.
- [ ] Interpolation at real speed, no rubber-banding at 10 Hz updates (client-side smoothing over 2 samples).
**Done:** tests; screenshots of each faction group, each vehicle, a hostile outline through a wall.

### 5-K1. Cyber skins, forced
- [ ] Server: linked players flagged in `cyberblock:roster` (from sessions), `body` per player (attachment),
  `/cyberblock body male|female`.
- [ ] Client: the player renderer uses the cyber skin for every roster-linked player (male → classic `v_male`, female →
  slim `v_female`), always, on every client, offline-safe (textures in the mod); the local player's own view follows the
  same rule (replaces `-Dcyberblock.ownSkin`).
- [ ] A coloured `[V]` name-tag prefix (server scoreboard team prefix, so vanilla renders it), a marker in the player
  list, and team-colour outlines through walls for allies (teams from Phase 8 matches; until then "allies" = none, the
  hook is in place).
**Done:** tests; screenshots of both bodies in first and third person and from a guest's view.

### 5-S1. NPC tags (scripts)
- [ ] For every entry of the NPC list: role (vendor, ripperdoc, fixer, bartender, medic, mechanic, none), faction,
  gender, archetype, animation state bits, vehicle class and colour, hp, through `Cyberblock_SubmitNpcs2` (the Phase 0 f
  readers, or the fallbacks).
**Done:** compile_check; needsInGame: a ganger, a cop, a vendor and a car print the right tags (CET line in the notes).

### 5-D1, 5-P1
- [ ] DLL: `Cyberblock_SubmitNpcs2`, the `npcs` fields of section 1.9, validators, tests. Protocol rows, fake host
  `--figures` (one NPC per skin group, all animations, a few vehicles, a chase), mpcheck `figures`, HOW-TO-PLAY "Figures
  and skins" and "Your cyber skin", CHANGELOG, Re-test 5.

### Phase 5 deliverables
- **In-game checks (Re-test 5):** the guest sees gangers in their gang's look, cops, a vendor; women on slim models; an
  NPC aiming and firing; a ragdoll and a body; cars in the right colours, a police car flashing in a chase; a hostile
  outline through a wall; you (linked) appear as V on the guest's screen with `[V]`; `/cyberblock body female` turns you
  into Lucy on every client.
- **What to try:** start a fight and watch it from the guest's side; switch your body; drive past the guest.
- **Descopes and risks:** role, gender and animation accuracy depend on (f); skin quality is procedural pixel art.

---

## 10. Phase 6: Redstone Hacking, Breach Protocol and the interaction map

**After Phase 6 Minecraft-side players hack Night City with redstone and screens:** Device Relays (redstone toggles a
linked device), Proximity Sensors, Heat Sensors, Breach Ports, Charges (real Cyberpunk explosions) and the Cyberdeck Link
item; a Breach Protocol screen (hex grid, buffer, daemons, timer) whose daemons do real things; and one screen per entry
of the interaction map: Hack Wheel on people, Vehicle Panel, Traffic Tool, Net Map (blackouts and recon), Comms Panel.

**Depends on:** Phase 4 (devices), Phase 5 (figures to target), Phase 0 (h) and (c). Fallback of (h): remote actions only
within the host's streamed area and 50 m of V.

| Pkg | Scope | Owns (exclusive) | Model |
|---|---|---|---|
| **6R** redstone hacking | Device Relay, Proximity Sensor, Heat Sensor, Breach Port, Charge, Cyberdeck block (RAM), Cyberdeck Link item; link storage and runtime resolution; rate limits and loop detection | new `mc/src/main/java/dev/cyberblock/hack/redstone/**`, tests | Opus |
| **6B** Breach Protocol | puzzle generation (5x5 to 7x7 by security, buffer, daemons from content, timer, Firewall raises difficulty), the screen, results to daemon actions, failure = alarm + Heat; reusable for player hacks (Phase 8) and the Breach Port | new `mc/src/main/java/dev/cyberblock/hack/breach/**`, new `mc/src/client/java/dev/cyberblock/client/hack/breach/**`, tests | Opus |
| **6I** interaction map screens and routing | Hack Wheel on people for guests, Vehicle Panel, Traffic Tool, Net Map (district blackout, camera feeds, outlines, pings, photo feed), Comms Panel; server routing with the `hacks`/`devices` toggles | new `mc/src/main/java/dev/cyberblock/hack/actions/**`, new `mc/src/client/java/dev/cyberblock/client/hack/screen/**`, `cyber/Hacking.java`, `client/cyber/HackWheel.java`, `cyber/Cyberware.java` and `CyberwareRules.java` (the `firewall` implant), tests | Opus |
| **6S** scripts | remote hacks (vehicle lock, engine off, brake, remote start, jam; traffic switch, gridlock; district blackout; outlines, area ping; camera feed source), daemon actions (open door, disable cameras, reveal enemies, overload, ...), alarm and heat on failure | `cp/scripts/**` | Opus |
| **6D** DLL | `rhack`/`daemon` events, the photo `feed` (Cyberpunk's picture downscaled to 128 x 128 map colours, ≤ 1 fps, only while requested), builders, tests | `cp/plugin/**`, `cp/shaders/**` (if the capture needs a pass; `fx_check`) | Opus |
| **6A** art and data | block and item art, `daemon` and `breach_tier` defaults, the `firewall` implant item, lang | `art/**`, `assets/cyberblock/**`, `data/cyberblock/cyberblock/daemon/**`, `.../breach_tier/**`, recipes, schemas | Sonnet |
| **6P** protocol, tools, docs | protocol rows, fake host `--hacks2`, mpcheck `breach`, docs, Re-test 6 | as 3P | Sonnet |

Lang owner: **6A**. Merge order: 6D, 6S, 6A, 6B, 6R, 6I, 6P.

### 6-R1. Redstone hacking blocks
- [ ] **Cyberdeck Link** item: scan a device with G (the existing scan), then right-click a Cyberblock redstone block to
  link it; the link is stored as (owner UUID, rounded Cyberpunk position, class) and resolved at runtime against that
  owner's device table (works after restarts; shows "unresolved" while the owner is offline).
- [ ] **Device Relay:** a redstone rising edge toggles the linked device (`devact toggle`, or open/close for doors);
  respects the owner's toggles. **Proximity Sensor:** outputs a signal when a person, a car or V (configurable in its
  screen) is within range (1-16 blocks) from figures and the host's position. **Heat Sensor:** signal strength = NCPD heat
  0-5 × 3 of the nearest host (`stats.wanted`; Phase 8's Server Heat adds a mode). **Breach Port:** on a signal, fires the
  chosen quickhack at targets in range (a screen picks the hack), paying RAM from a **Cyberdeck block** placed next to it
  (the block stores RAM and refills slowly). **Charge:** on a signal, a real Cyberpunk explosion at its position (an
  `explosion` DOWN to the owner; the owner's `explosions` toggle for guests' charges).
- [ ] **Rate limits and loop detection:** each block acts at most 2 times a second; a link chain that toggles the same
  device more than 8 times in 4 s is frozen for 30 s with a smoke particle and `cyberblock.redstone.loop`.
- [ ] Tests: link resolution, rate limit, loop detection, permission refusals.
**Done:** `gradlew test`; screenshots.

### 6-B1. Breach Protocol
- [ ] Server generates the puzzle: grid 5x5 to 7x7 by device security (`breach_tier`), hex codes (`1C`, `55`, `BD`,
  `E9`, `7A`, `FF`), a buffer of 4-8, 1-3 daemon sequences from the `daemon` content for that device kind, a timer; a
  `firewall` implant on the **defending** side (Phase 8 player hacks) raises the tier. Pure generator with a seed; the
  classic row/column alternation rule.
- [ ] Client `BreachScreen` (Minecraft widgets and font): grid, buffer, sequences, timer bar; the server validates every
  pick (no client trust).
- [ ] Results: solved daemons map to real actions (`daemon` DOWN to the owner: open door, disable cameras, reveal enemies,
  overload, ...); more and longer sequences = stronger effect (`strength` 1-3); failure = an alarm in the host's game
  (cameras alert) and Heat (+5, Phase 8 meter; until then the host's wanted level +1 if Phase 0 e allows).
- [ ] Opened by right-clicking a Net Port block (Phase 4), by a Breach Port and (Phase 8) by player hacks.
- [ ] Tests: generator properties (every daemon solvable within the buffer), validation, results mapping.
**Done:** `gradlew test`; screenshots of a 5x5 and a 7x7 breach.

### 6-I1. The interaction map screens
- [ ] **Hack Wheel on people** for guests: the existing wheel works on any figure a guest can see (owner routing,
  `hacks` toggle).
- [ ] **Vehicle Panel** (right-click a car figure with the Cyberdeck Link or from the Net Map): lock doors, kill engine,
  force brake, remote start, jam.
- [ ] **Traffic Tool** (item): switch a district's traffic lights, gridlock a road (the cars on a road segment stop for a
  minute).
- [ ] **Net Map** (item and screen, built on the Atlas view): black out a district for minutes (lights and devices off,
  then back), recon: camera feeds (a camera device's view, below), outlines (figures in an area outlined for the team),
  pings, and the host's **photo feed** on a map item (6-D1).
- [ ] **Comms Panel** (item): send a waypoint or a short message to a host's HUD: Minecraft-side, the host's client shows
  it as a toast and a locator-bar waypoint (no Cyberpunk change needed; the host's HUD is Minecraft's).
- [ ] **Overlord Console** and **build/TNT** are the other two interaction map entries: the console is Phase 8 (task
  8-O1); building and TNT are the normal game.
- [ ] Each action is a `cyberblock:act` request → permission check → `rhack` DOWN → `hackresult` UP → feedback.
**Done:** `gradlew test build`; screenshots of each screen with the fake host.

### 6-S1. Remote hacks and daemons (scripts)
- [ ] Event `rhack`: vehicle actions on the car (verified vehicle component calls), traffic switch and gridlock (traffic
  system calls, or the traffic-light devices of Phase 4 as the fallback), district blackout (devices of a district off for
  N minutes and back; fallback: the devices within 150 m of V), outlines (reveal events as the Ping chip does), area ping,
  camera feed: report the camera device's position and facing so Minecraft can render the replica from there (fallback for
  a true Cyberpunk camera view, which would take over the host's screen).
- [ ] Event `daemon`: each daemon id's action at its strength; failure alarm (camera alert / combat alert of the
  device's area); results through `Cyberblock_HackResult`.
**Done:** compile_check; needsInGame per action.

### 6-D1. Photo feed and events (DLL)
- [ ] `feedreq on/off` from Minecraft; while on, once a second the compositor copies Cyberpunk's back buffer (the host's
  own view, already available to the add-on), downscales it to 128 x 128, maps each pixel to the Minecraft map palette and
  sends `feed` (BOTH: the host's client shows it locally, and relays it for the Net Map and the Phase 8 Van). Fallback if
  the copy costs frame time over 0.5 ms: a top-down replica render on the Minecraft side instead, documented.
- [ ] `rhack`, `daemon` events; builders and tests.
**Done:** `cb_tests`, `fx_check`.

### Phase 6 deliverables
- **In-game checks (Re-test 6):** a guest links a Device Relay to a door with the Cyberdeck Link and opens it with a lever;
  a Proximity Sensor lights a lamp when an NPC walks by; a Charge with a button blows up in Night City; a Breach on a Net
  Port (5x5) disables cameras; a guest stops your car with the Vehicle Panel; a district blackout; a Comms waypoint on your
  HUD; the photo feed on a map item.
- **What to try:** build a redstone trap for NPCs; breach a terminal; black out a district; send the host a waypoint.
- **Descopes and risks:** remote action range depends on (h); traffic and blackout calls may fall back to Phase 4 devices;
  a true Cyberpunk camera view is replaced by a replica view from the camera's position; the photo feed is low-res by
  design and may fall back to a replica render.

---

## 11. Phase 7: NPC interaction layer, the Afterlife hub and the Holo-Table

**After Phase 7 Minecraft players talk to Night City's people:** right-click a ripperdoc, vendor, fixer, bartender, medic
or mechanic figure for a Minecraft screen with original dialogue; the **Afterlife hub** is open even with no host online
(Match Terminal, shops, leaderboards, a bartender NPC, a ripperdoc, the Holo-Table); linked players entering the hub see
full-screen Minecraft while V is parked safely; the **Holo-Table** shows the shared live 3D miniature of the replica with
every player as a pin and spawns players anywhere on the map. Hosts also get Cyberpunk's own vendors and ripperdocs as
true Minecraft screens, and fast travel through the Holo-Table.

**Depends on:** Phase 5 (figures with roles), Phase 3 (shared map, teleport, park), Phase 0 (f) and (g).

| Pkg | Scope | Owns (exclusive) | Model |
|---|---|---|---|
| **7N** NPC interaction layer | role screens for figures and hub NPCs: ripperdoc (install implants, surgery minigame with risk for the best ones), vendor (shop, eddies), fixer (gigs from `contract`), bartender (rumours, drink buffs), medic (heal), mechanic (vehicle repair); original dialogue from `dialogue`; hub NPCs that always exist | new `mc/src/main/java/dev/cyberblock/npc/**`, new `mc/src/client/java/dev/cyberblock/client/npc/**`, `cyber/Ripperdoc.java` (install from the screen), tests | Opus |
| **7H** Afterlife hub | the hub dimension and its original build, Match Terminal block (Phase 8 fills it), shops, leaderboards, entering and leaving (full-screen Minecraft and parking for linked players), hub portal and `/cyberblock hub` | new `mc/src/main/java/dev/cyberblock/hub/**`, `mc/src/main/resources/data/cyberblock/dimension/**`, `.../dimension_type/afterlife.json`, new `client/hub/**`, tests | Opus |
| **7T** Holo-Table | the 3D miniature (client mesh from the shared map tiles and the replica), pins, layers, rotate/zoom/tilt, spawning from the map (landmarks, team bases, Spawn Beacons, join a friend), spawn rules, privacy, the AtlasScreen reuse | new `mc/src/main/java/dev/cyberblock/holo/**`, new `mc/src/client/java/dev/cyberblock/client/holo/**`, `client/ui/screen/AtlasScreen.java`, `AtlasView.java`, tests | Opus |
| **7V** host vendors, ripperdoc, fast travel | Cyberpunk vendors and ripperdocs as Minecraft screens with the host's real stock and money (UI gap plan), fast travel via the Holo-Table | `client/ui/**` (except AtlasScreen/AtlasView), `cp/scripts/Cyberblock/Menus.reds`, `Restyle.reds`, new `Vendors.reds`, tests | Opus |
| **7S** scripts | vendor stock replies, buy/sell, the hub park (Phase 3 `Park.reds`), mechanic repair and medic heal hooks, role readers | `cp/scripts/**` **except** 7V's three files | Opus |
| **7D** DLL | `hub` (full-screen Minecraft through the loading-cover path), `reply kind:"vendor"`, `buy`/`sell` `uiact` actions, tests | `cp/plugin/**`, `cp/shaders/**` | Sonnet |
| **7A** art and data | `art/gen_hub.py` (the original hub layout as data), block art, `dialogue`, `contract`, `ripperdoc`, `landmark`, `npc_role`, `shop` defaults (all text original), lang | `art/**`, `assets/cyberblock/**`, `data/cyberblock/cyberblock/{dialogue,contract,ripperdoc,landmark,npc_role,shop,hub}/**`, schemas | Sonnet |
| **7P** protocol, tools, docs | protocol rows, fake host `--vendor`, mpcheck `hub`, docs, Re-test 7 | as 3P | Sonnet |

Lang owner: **7A**. Merge order: 7D, 7S, 7V, 7A, 7N, 7H, 7T, 7P.

### 7-N1. Role screens
- [ ] Right-click on a figure whose role (Phase 5 tags, or `npc_role` content for hub NPCs) has a screen: ripperdoc
  (the existing implant install, eddie prices from `ripperdoc`, a short surgery minigame for the top implants: a timing
  bar; failure = damage and a 30 s debuff, never death), vendor (`shop` list, buy/sell for Minecraft eddies), fixer
  (`contract` gigs: take one, objective tracking, reward on completion; gig types: deliver an item, defeat N hostiles of a
  faction, reach a landmark, breach a device), bartender (rumours from `dialogue`, drinks = short potion buffs), medic
  (heal Steve for eddies), mechanic (repair: the host's current Cyberpunk vehicle through a verified call; for guests,
  repair of elytra and armour).
- [ ] Dialogue: every line from `dialogue` content (original), picked by role and context.
- [ ] Hub NPCs: server-side figure entities with roles, always present in the hub, independent of hosts.
**Done:** tests; screenshots of each screen.

### 7-H1. The Afterlife hub
- [ ] A separate dimension `cyberblock:afterlife` (its own dimension type, always day, no mob spawns), built once from the
  original layout in `data/cyberblock/cyberblock/hub/afterlife.json` (generated by `art/gen_hub.py`: a bar, booths, a
  ripperdoc corner, a shop row, the Match Terminal, leaderboards, the Holo-Table room). Entered by `/cyberblock hub`, a
  hub portal block placeable in the replica, and the pause screen; left the same way (back to where you came from).
- [ ] **Linked players:** entering sends `hub in:true` DOWN (the compositor switches to full-screen Minecraft, the
  loading-cover path; no depth blending) and the scripts park V (Phase 3 `park`: invulnerable, unseen by NPCs, camera
  locked); leaving reverses both; a dropped link or session end also unparks.
- [ ] Leaderboards (scoreboard objectives on board blocks: match wins, gigs done, chaos), shops (hub vendors), the
  bartender and ripperdoc NPCs, the Match Terminal block (opens Phase 8's match screen; "coming in Phase 8" until then).
**Done:** tests; screenshots; mpcheck `hub` (a fake host gets `hub` and `park`).

### 7-T1. The Holo-Table
- [ ] A Holo-Table block in the hub; right-click opens the table view: a shared live 3D miniature of the replica (a
  height mesh from the shared Night City map tiles of 3-R2, coloured like the Atlas, with replica detail near players),
  drag to rotate, scroll to zoom, tilt; a 2D mode reuses `AtlasScreen` (and is the fallback if the 3D view costs too
  much frame time).
- [ ] Pins for every player: head, name, team colour, `[V]` for linked players, status (in a car, in combat, downed).
  Optional layers: NPC density, enemy hot-spots, devices, Server Heat per district (Phase 8), events, Gang Wars territory,
  matches, Van drones.
- [ ] **Spawning from the map:** district landmarks (`landmark` content), team bases and Spawn Beacons (a placeable
  block), "join a friend". Minecraft-only players: Steve is placed in the replica. Linked players: V is teleported
  (`vteleport why:"holo"`: ground snap, wait for streaming) and Steve placed to match, V and Steve positions paired
  consistently. Short spawn protection (5 s), a cooldown (60 s), never into a locked match zone, in matches only the team's
  spawns.
- [ ] **Privacy:** outside matches all pins are visible, with a "hide my pin" toggle; in matches only your team's pins
  plus scan-revealed ones; spectators and overlords see all.
**Done:** tests (spawn rules, privacy); screenshots.

### 7-V1. Hosts' Cyberpunk vendors and ripperdocs as Minecraft screens; fast travel
- [ ] When a host opens a Cyberpunk vendor or ripperdoc while Cyberblock is on, Minecraft opens a true Minecraft screen
  instead of the pass-through: the stock comes from `reply kind:"vendor"` (Phase 0 f), buy and sell go back as `uiact
  buy|sell` with the item hash, paid with the host's real Cyberpunk money (section 1.16). The prologue ripperdoc install
  and anything quest-critical stay pass-through (logged), as does any vendor whose stock is not readable.
- [ ] Fast travel: the Holo-Table offers the host's fast-travel points (`uiact fasttravel`, enabled once verified); the
  Atlas stays map-only.
**Done:** compile_check, `gradlew test build`; needsInGame: buy and sell at a gun shop as a host.

### 7-S1, 7-D1, 7-A1, 7-P1
- [ ] Scripts: vendor stock reply and transactions (one path: 7V's `Vendors.reds` through `uiact buy|sell`), mechanic
  repair and medic heal hooks (verified), hub park reuse. DLL: `hub` compositor mode, the vendor reply kind, `buy`/`sell`
  `uiact` actions, tests. Art and data: the hub layout generator,
  blocks (Holo-Table, Spawn Beacon, hub portal, Match Terminal, leaderboard board), defaults for every content kind of this
  phase with original text, lang. Docs: HOW-TO-PLAY "Talking to Night City", "The Afterlife hub", "The Holo-Table",
  CHANGELOG, Re-test 7.

### Phase 7 deliverables
- **In-game checks (Re-test 7):** a guest buys from a vendor figure, takes a gig from a fixer and completes it, installs
  an implant at a ripperdoc (surgery minigame); you enter the hub: full-screen Minecraft, V parked (an NPC walks by and
  ignores V), leave: back in Night City; the Holo-Table shows everyone, spawn at a landmark (V teleported and Steve with
  it), join a friend; buy and sell at a Cyberpunk gun shop through the Minecraft screen; fast travel from the Holo-Table;
  drop the link while in the hub (close or crash the Minecraft client), rejoin outside the hub: V is free (visible,
  moves, camera works) and saving is allowed (log `hub: forgotten (Minecraft lost)` or `(the link dropped)`).
- **What to try:** a night in the Afterlife; gigs; the Holo-Table spawn and "join a friend".
- **Descopes and risks:** vendor stock for hosts depends on (f); the 3D table may fall back to 2D; parked V's "unseen"
  depends on (g); mechanic repair of Cyberpunk cars depends on a verified call.
- **7V review amendment (2026-10-04):** (1) **Descope:** installing cyberware stays Cyberpunk's own ripperdoc screen
  (the Minecraft ripperdoc screen is a shop: buy and sell only; "Night City vendor..." opens Cyberpunk's for installing).
  (2) A vendor or ripperdoc whose hub seats V (the ripperdoc chair, a workspot) or runs inside its dialogue scene is
  still taken over; only quest locks (`LockInHubMenu`), the prologue ripperdoc and the ripperdoc tutorials stay
  pass-through. (3) Fast travel picked at the Holo-Table while V is parked in the hub runs 1 s after the hub exit
  (never while parked: vanilla fast travel strips the park's restrictions); the Holo-Table lists the host's points
  through 7T's `HoloFastTravel.points` hook, fed by 7V's `FastTravelPoints.known()` / `travel()` (installed at the
  Phase 7 merge). Re-test 7 adds: open a gun shop and a ripperdoc as the host and grep `Cyberblock.log` for
  `vendors: <name> pass-through`: there is none, and `vendors: <name> -> minecraft screen open` is there for both; at the
  Holo-Table pick a fast-travel point (toast "when you leave the hub"), leave the hub: V fast travels there, Steve
  follows, and V stays at the destination (`fast travel: to <name> (Holo-Table)` after `park: off (hub exit)`).
- **7S fix amendment (2026-10-04):** (1) The hub survives a load: the DLL keeps its hub through a game load or a quit to
  the main menu and the server is not told, so `Hub.reds` keeps its hub through session ready, session end and detach
  while linked (`hub: kept through the <reason> ...`) and parks V again at the next On; it is still forgotten on a link
  drop, Minecraft lost and a lost host grant. (2) **Descope:** the mechanic's repair of the host's Cyberpunk car. Sections
  1.8 and 1.9 name no `repair` message and the DLL has no event kind for it, so 7S's `Services.reds` repair hook (the
  vanilla `VehicleRepairEvent` path with a direct-reset fallback) is unwired; wiring it needs a DOWN `repair {id}` in 1.9,
  the DLL event kind, a result back so `Garage.java` can confirm and charge (1.16), and a server-side permission and rate
  limit. The medic has no Cyberpunk hook (it heals Steve on the Minecraft side; the V-side heal hook was removed).
  needsInGame once wired: `mechanic: repaired <car> (repair event, hp N %)` (vanilla path) versus `(fallback: direct
  reset; ...)`. Re-test 7 adds: in the hub open the Cyberpunk menu, load a save, press F9: a `park: on (...)` line, V
  invisible and frozen.

---

## 12. Phase 8: roles and modes

**After Phase 8 the server has game modes:** a guest runs ops as the **Hacker in the Van**; one or two players act as
**Overlord** with a console, Threat Points and fair spawns; Cyberpunk-linked players create **matches** at the Match
Terminal (PvP only when both players opt in, Gang Wars with districts and capture points); chips work on **players**
(opted-in or in war zones) with counterplay; one **Server Heat** meter brings NCPD and Max-Tac (the real ones for hosts);
players go **Down** instead of dying and get revived or call Trauma Team; a **spectator/caster** mode watches it all.

**Depends on:** Phase 6 (hacks, Breach), Phase 7 (hub, Match Terminal, Holo-Table), Phase 0 (d) and (e). Fallbacks: (d)
Minecraft-side squads; (e) no real wanted writes and no reputation change.

| Pkg | Scope | Owns (exclusive) | Model |
|---|---|---|---|
| **8V** Hacker in the Van | placeable Van, netrunner seat, the host-area miniature, the photo feed on a map item, flyable drones, the full Hack Wheel on anything visible | new `mc/src/main/java/dev/cyberblock/mode/van/**`, new `client/mode/van/**`, tests | Opus |
| **8O** Overlord | Overlord Console (top-down replica view, spawn palette, event timeline), Threat Points, spawns and events, fairness, intensity, opt-in, Auto-Director | new `mode/overlord/**`, `client/mode/overlord/**`, tests | Opus |
| **8M** matches, PvP, Gang Wars | Match Terminal screen (only linked players create), teams per match, roles for Minecraft-only players, the PvP toggle rule, hits on linked players through Steve's hearts, Gang Wars (colours, districts, spawns, scoreboard, capture points), reputation if (e) | new `mode/match/**`, `client/mode/match/**`, `hub/MatchTerminal*.java`, tests | Opus |
| **8X** hacking players | chips on players (Short Circuit, Reboot Optics, Cripple Movement, Ping, Overheat, Memory Wipe), opt-in and war-zone rule, Intrusion warning with a short Breach, Firewall and Netrunner implants, RAM and cooldowns, the glitch overlay | new `mode/pvphack/**`, `client/mode/pvphack/**`, `cyber/Hacking.java`, `cyber/CyberwareRules.java`, `cyber/Cyberware.java` (the `netrunner` implant), tests | Opus |
| **8T** Heat, Downed, spectator | Server Heat 0-100 and its tiers, Max-Tac, Minecraft squads, the HUD meter; Downed and revive, Trauma Team flyer, the death screen tie-in (UI gap); spectator/caster mode | new `mode/heat/**`, `mode/downed/**`, `mode/spectate/**`, matching `client/mode/**`, `client/ui/**` (meter, downed and caster HUD), tests | Opus |
| **8S** scripts | spawns (d), heat writes and attitude (e), the glitch hook, the death screen hand-off | `cp/scripts/**` | Opus |
| **8D** DLL | `spawn`/`spawnresult`, `heat`, `attitude`, `glitch` (a ReShade glitch uniform and pass; `fx_check`), tests | `cp/plugin/**`, `cp/shaders/**` | Opus |
| **8A** art and data | Van, drone, console, flyer, beacon art; `event`, `spawn_palette`, `heat_tier`, `match_mode` defaults; the `netrunner` implant item; lang | `art/**`, `assets/cyberblock/**`, `data/cyberblock/cyberblock/{event,spawn_palette,heat_tier,match_mode}/**`, schemas | Sonnet |
| **8P** protocol, tools, docs | protocol rows, fake host `--modes`, mpcheck `match`/`heat`/`overlord`, docs, Re-test 8 | as 3P | Sonnet |

Lang owner: **8A**. Merge order: 8D, 8S, 8A, 8T, 8X, 8M, 8O, 8V, 8P.

### 8-V1. Hacker in the Van
- [ ] A placeable Van (a block-entity structure or an entity, Phase 5 vehicle model); a guest sits in as netrunner and gets
  the Van screen: a 3D miniature of the host's area (the Holo-Table renderer, scoped), the live photo feed of the host's
  view on a map item (6-D1), drones they fly through the replica as eyes (a small flying entity; the camera switches to it;
  limited range from the Van, battery), and the full Hack Wheel on anything they can see from the Van's screen or a drone
  (people, cars, devices); hacks run in the host's real game through the server (owner routing, toggles).
**Done:** tests; screenshots; mpcheck with a fake host.

### 8-O1. Overlord
- [ ] **Overlord Console** (a block in the hub and a craftable item): a top-down replica view of the active match area, a
  spawn palette (`spawn_palette`: Minecraft mobs and Night City enemies: gangers, NCPD, Max-Tac, drones, turrets), events
  (`event`: blackout, EMP, airstrike, traffic jam, supply drop, weather, cyberpsychosis outbreak) and an event timeline.
- [ ] **Threat Points** regenerate; bigger spawns cost more. Night City enemies near a host go through `spawn` DOWN
  (Phase 0 d) with the owner's `spawns` toggle; otherwise and for Minecraft-only players they are Minecraft squads.
- [ ] **Fairness:** a 3 s warning (sound and a marker) before spawns near a player, a minimum distance, a cap on
  simultaneous spawns, host-set intensity Chill/Normal/Brutal; applies **only to players in an active match who opted in**.
- [ ] **Auto-Director** (rule-based) when no human overlord is present: spends points by match pace and Heat.
**Done:** tests (fairness rules, costs, regeneration); screenshots.

### 8-M1. Matches, PvP and Gang Wars
- [ ] Matches are created **only by Cyberpunk-linked players** at the Match Terminal: mode (`match_mode`), teams, zones
  (Holo-Table area pick), rules, time limit, invites; Minecraft-only players join as teammate, hacker (Van), spectator or
  overlord. Teams are per match (not persistent).
- [ ] **PvP:** damage between two players applies only if **both** have the PvP toggle ON (a HUD button and a Cyberware
  screen button); after the last hit a player must wait 15 s before turning it off; a red name tag while on; **off for
  everyone by default until a match turns it on** for its players. Hits on a linked player land on V through Steve's
  hearts (the existing one-health rule).
- [ ] **Gang Wars:** team colours, districts, spawn points, a scoreboard, capture points (stand in a zone to capture);
  if Phase 0 (e) allows, a team's reputation changes how Night City's factions treat its hosts (`attitude` DOWN).
**Done:** tests (PvP rule matrix, the 15 s lock, capture logic); screenshots.

### 8-X1. Hacking players
- [ ] Chips work on players who opted in (PvP on) or inside war zones, **never on a Minecraft-only guest who has not
  opted in**: Short Circuit (stun and damage), Reboot Optics (blind with a glitch overlay; Cyberpunk-linked victims also
  get the ReShade glitch through `glitch` DOWN), Cripple Movement (slow), Ping (outline for the hacker's team), Overheat
  (burn), Memory Wipe (clears the selected hotbar slot: the item drops at the victim's feet, not deleted).
- [ ] **Counterplay:** "Intrusion detected" warns the target, who gets a few seconds for a short Breach (6-B1, 4x4, 5 s)
  to block it; a `firewall` implant blocks hacks (cooldown); a `netrunner` implant resists (shorter effects). Hacks cost RAM
  and have cooldowns.
**Done:** tests (opt-in rule, counterplay, costs); screenshots of the glitch overlay.

### 8-T1. Server Heat and Max-Tac
- [ ] One server-wide meter 0-100 fed by chaos (explosions, kills, destroyed cars, failed breaches, PvP kills), decaying
  over time (`heat_tier`). Tiers: 1 more NCPD patrols, 2 NCPD chases the loudest players, 3 heavy units (armoured NCPD,
  air vehicles), 4 Max-Tac hunts the top chaos player, 5 full response on everyone. For hosts the tiers map to their
  **real wanted level** (`heat` DOWN, Phase 0 e) so NCPD and Max-Tac are the game's own; for Minecraft-only players tough
  Minecraft squads hunt them in the replica. A Heat meter on the HUD.
**Done:** tests (feed, decay, tier mapping).

### 8-T2. Downed and revive
- [ ] Instead of dying a player goes **down** (crawling, a bleed-out timer of 60 s); teammates revive by holding
  right-click for 4 s; or the player pays eddies to call a **Trauma Team** flyer that arrives after a countdown (30 s) and
  revives them. Ties to the death screen: Minecraft's death screen appears only when the bleed-out ends; for hosts the
  Cyberpunk death screen (pass-through today) is replaced by this flow (UI gap plan).
**Done:** tests; screenshots.

### 8-T3. Spectator and caster mode
- [ ] Spectators (Minecraft-only players, from the Match Terminal or `/cyberblock spectate`): vanilla spectator movement,
  every pin visible, a caster overlay (scoreboard, Heat, match timer, team lists, kill feed).
**Done:** screenshots.

### 8-S1, 8-D1, 8-A1, 8-P1
- [ ] Scripts: spawns with the Phase 0 (d) method, heat stage writes and attitude changes (e), glitch hook, the death
  screen hand-off. DLL: the four messages, the `cb_glitch` uniform and pass (`fx_check`), tests. Art and data: models and
  defaults for every content kind of this phase (original texts), lang. Docs: HOW-TO-PLAY "Modes" (Van, Overlord, matches,
  PvP rule, Gang Wars, player hacks and counterplay, Heat, Downed, spectating), CHANGELOG, Re-test 8.

### Phase 8 deliverables
- **In-game checks (Re-test 8):** a guest in the Van hacks an NPC near you through a drone; an overlord spawns gangers near
  you with a 3 s warning (only after you opted in to the match); a match at the Match Terminal with PvP on both sides; a
  player hack and its Breach counterplay; Heat climbs to tier 4 and Max-Tac comes for the top chaos player (your real wanted
  level for you); you go down and a teammate revives you; Trauma Team arrives; a spectator watches with the caster overlay.
- **What to try:** a Gang Wars match with a friend as overlord; the Van; push the Heat to 5.
- **Descopes and risks:** Night City spawns depend on (d) (Minecraft squads otherwise); real wanted and reputation depend on
  (e); this is the biggest phase: it may be split into 8a (Van, Overlord, Heat) and 8b (matches, player hacks, Downed,
  spectator) with a user test in between if the integrator sees the merge risk.

---

## 13. Phase 9: content-as-data finish, UI gaps, performance, settings, backups, docs, release notes

**After Phase 9 the mod is complete and tunable:** every content kind is data and hot-reloadable with a guide for
friends; the remaining UI gaps are closed or documented; a Performance mode lowers the frame-export resolution and the
figure count; one settings screen holds every option; the server backs itself up daily; the docs and release notes cover
everything.

**Depends on:** all earlier phases.

| Pkg | Scope | Owns (exclusive) | Model |
|---|---|---|---|
| **9C** content finish | every kind registered, schemas complete, hot reload verified, `docs/CONTENT.md`, an example datapack | `mc/src/main/java/dev/cyberblock/content/**`, `shared/content-schemas/**`, `tools/content_check.py`, new `examples/content-datapack/**`, tests | Sonnet |
| **9U** UI gaps | save/load lists as a Minecraft screen (if the 2.31 save-list API is verified; else pass-through stays, documented), the remaining pass-through decisions, the escape hatch check | `client/ui/**`, `cp/scripts/Cyberblock/Menus.reds`, `Restyle.reds`, new `Saves.reds`, tests | Opus |
| **9F** performance | Performance mode: `prefs.scale` (frame export at 50-100 % resolution, the compositor upscales), fewer figures (cap and distance LOD), server tick budgets (replica placement, device sync, payload rates), measurements before and after | `client/FrameExporter.java`, `client/figure/**`, `replica/**` (budgets only), `cp/plugin/src/compositor.*`, `cp/shaders/**`, tests | Opus |
| **9S** settings screen | the full settings screen: guest permissions per toggle and device type, skin mode (body), PvP, difficulty (Overlord intensity default, Heat scaling), performance, creative erase-replica | `client/settings/**`, `net/Permissions.java` (commands only), tests | Opus |
| **9B** backups | daily server backups: a zip of the world at the configured hour, keep N, skip and warn when free disk is low, a log line; docs for downloading them with `tools.server_sync` | new `mc/src/main/java/dev/cyberblock/backup/**`, `tools/server_sync.py` (a `pull-backups` command), tests | Sonnet |
| **9P** docs and release notes | HOW-TO-PLAY complete pass (including the "Creative mode and the outline blocks" note and the new setting), README, CHANGELOG release notes, TESTING-PLAN full multiplayer pass, version bump in every place `tests/test_version.py` checks (no tag until "push") | `docs/**` except plans, `README.md`, `CHANGELOG.md`, version files, `tests/test_version.py` | Sonnet |

Lang owner: **9S**. Merge order: 9C, 9U, 9F, 9S, 9B, 9P.

### 9-C1. Content finish
- [ ] Every content kind of section 1.7 has a schema, a loader, defaults, hot reload through `/reload` (and the client
  update through `cyberblock:content`) verified by a test that edits a file and reloads; `docs/CONTENT.md` shows a friend
  how to add a dialogue line, a skin, a shop, a landmark, a Breach daemon and an Overlord event as a datapack, with the
  example in `examples/content-datapack/`.
**Done:** `content_check` on the example; tests.

### 9-U1. UI gaps
- [ ] Save/load lists: if the 2.31 save-list calls are verified (compile_check), a Minecraft "Night City saves" screen
  (list, load, save) replaces the pass-through; otherwise the pass-through stays and `docs/notes/ui-coverage.md` says
  why. Stash, wardrobe, crafting benches, arcade, braindance editor, tutorial and shard popups and settings: converted only
  if cheap (each decision written), else pass-through. The escape hatch to the vanilla Cyberpunk menu works at all times
  (a test plus an in-game check).
**Done:** compile_check, tests; the coverage note updated.

### 9-F1. Performance pass
- [ ] Measure first (frame export time, compositor time, server tick time with 2 hosts and 4 guests in mpcheck), then:
  `prefs.scale` (Minecraft renders and exports at a lower resolution; the shader upscales), a figure cap (nearest 48 →
  configurable 16-48) and distance LOD (far figures without animation), server budgets (replica placements per tick,
  device updates per tick, DOWN queue per tick). Report the numbers before and after.
**Done:** tests; `fx_check`; numbers in the report and in HOW-TO-PLAY.

### 9-S1. Settings screen
- [ ] Every row of section 1.13 in one screen with tabs (Guests, Look, PvP, Difficulty, Performance, World): guest
  permissions (each toggle and device type), skin mode (body male/female), PvP toggle, difficulty (Overlord intensity
  default, Heat scaling), performance (scale, figure cap), creative erase-replica (host/ops only). Server-side values go
  through `/cyberblock` commands; client-only values save locally.
**Done:** tests; screenshots of every tab.

### 9-B1. Daily backups
- [ ] At `backups.hour` (server local time) the server saves, zips the world into `backups/cyberblock-<date>.zip`, keeps
  `backups.keep`, skips with a warning when free disk is under twice the world size, logs `backup: <file> <size>`;
  `/cyberblock backup now` (ops); `tools.server_sync pull-backups --to <folder>` downloads them (secrets rule 0.6).
**Done:** tests.

### 9-P1. Docs and release notes
- [ ] HOW-TO-PLAY full pass (including **a short "Creative mode and the outline blocks" note and the new setting**),
  `docs/SERVER.md`, `docs/JOIN.md`, `docs/CONTENT.md` linked from the README; CHANGELOG release notes for the multiplayer
  version; TESTING-PLAN "Full multiplayer pass"; the version bump (proposed 2.0.0: the wire grew a server; the user
  decides) everywhere `tests/test_version.py` checks. **No tag and no release until the user says "push".**

### Phase 9 deliverables
- **In-game checks (Re-test 9):** a friend adds a dialogue line and a skin as a datapack and `/reload` shows them; the
  settings screen changes every option; Performance mode keeps frame time steady with four guests; a backup appears at the
  set hour and `pull-backups` downloads it; the save/load screen (or its documented pass-through); the escape hatch.
- **What to try:** write a content datapack with a friend; Performance mode; the settings screen; a full evening session.
- **Descopes and risks:** the save/load list conversion depends on verified APIs; backups need disk on the Apex plan.

---

## 14. Dependency map and effort

```
Phase Q (quick wins) ───────────────┐
Phase 0 (feasibility; parallel to Q) ┼──► Phase 1 (server/client split; needs Q merged)
                                     │          │
       0(a) Apex facts ─────────────────────────┴──► Phase 2 (Apex deployment)
       0(b) material, 0(g) teleport ───────────────► Phase 3 (replica)  ◄── Phase 1 (+ Phase 2 for the real-server test)
       0(b), 0(c) devices ─────────────────────────► Phase 4 (devices)  ◄── Phase 3
       0(f) tags ──────────────────────────────────► Phase 5 (figures, skins) ◄── Phase 3   (4 and 5 could be built in parallel; tested one after the other)
       0(h) remote actions ────────────────────────► Phase 6 (hacking, Breach, interaction map) ◄── Phases 4 and 5
       0(f), 0(g) ─────────────────────────────────► Phase 7 (NPC layer, hub, Holo-Table) ◄── Phases 3 and 5
       0(d), 0(e) ─────────────────────────────────► Phase 8 (roles and modes) ◄── Phases 6 and 7
                                                     Phase 9 (finish) ◄── everything
```
The brief's order is kept: each phase ends with a user test. Within a phase all packages run in parallel. Phases 4 and 5
touch disjoint files except `ProxyEntity`/`ProxyManager` (5F) and `replica/ReplicaRules` (4M); if the user wants speed, 5
can be built while 4 is being tested, merged only after the user's "go" for 4.

**Effort (rough; agents = packages, each with its own Opus reviewer and fixer; wall-clock is the agents' build time, not
counting the user's test sessions):**

| Phase | Packages (Opus + Sonnet) | Agent runs incl. review and fix | Wall-clock | User time |
|---|---|---|---|---|
| Q | 6 (4 + 2) | about 18 | 4-7 h | 45 min test + 10 min Phase 0 CET checks |
| 0 | 2 (1 + 1) + the user | about 6 | 2-4 h (parallel to Q) | 15 min of panel facts |
| 1 | 6 (4 + 2) | about 18 | 8-12 h | 45 min test |
| 2 | 4 (1 + 3) | about 12 | 3-5 h | 45-90 min deployment day + the friend test |
| 3 | 8 (6 + 2) | about 24 | 12-18 h | 60 min test |
| 4 | 6 (4 + 2) | about 18 | 9-14 h | 45 min test |
| 5 | 6 (4 + 2, art on Opus) | about 18 | 8-12 h | 45 min test |
| 6 | 7 (5 + 2) | about 21 | 12-18 h | 60 min test |
| 7 | 8 (5 + 3) | about 24 | 14-22 h | 60-90 min test |
| 8 | 9 (7 + 2) | about 27 | 18-28 h (or two halves) | 90 min test (or two) |
| 9 | 6 (3 + 3) | about 18 | 7-11 h | 60 min test |
| **Total** | **68 packages** | **about 215 agent runs** (3 per package plus one integrator per phase) | **about 95-147 h of build time** (Phase 0 overlaps Q) | **about 10-12 h of the user's testing and panel time over 10 sessions** |

---

## 15. Coverage (brief item → tasks)

| Brief item | Tasks |
|---|---|
| §0 plan first, summary, wait for "go" | this file; the summary in chat |
| §0 Workflows, models, reviewer/fixer, integrator, all suites, install, Re-test section, stop | rule 0.1, 0.9, section 2 |
| §0 never Gradle in the main checkout's `mc`; never launch Cyberpunk; ports/frames; never kill others' processes; commit < 60 GB | rules 0.2, 0.3, 0.7, section 1.15 |
| §0 commit locally with the trailer; no push, tags or releases; noreply email; no secrets, game files, decompiled sources, unpacked jars, FTP password, private paths | rules 0.4, 0.5, 0.6; Q-P2 hygiene test |
| §0 APIs confirmed from NativeDB/Codeware/2.31 scripts (compile_check), Minecraft names from Loom sources, needsInGame; no tool downloads without asking | rule 0.8, 0.5; 2-K1/2-K3 user-downloaded jars |
| §0 descope rule; original art; the fan skins accepted | rules 0.10, 0.11; section 1.12 |
| §1 vision: Apex world = live growing Night City map, guests see it as Minecraft, both ways, the server is the netcode, Full tier, Lite designed-for only | top architecture; sections 1.2-1.7; Phases 1-3 |
| §2 Apex facts unknown → Phase 0 asks | 4.1 (a), 4.2 |
| §2 backup whole server over the existing FTP method, password never printed or stored | 2-S1, 6.3 B, rule 0.6 |
| §2 switch to a custom Fabric 26.3 jar, new world "cyberblock" uploaded with its datapack/dimension, allow-flight, whitelist; never wipe Rod's Ores; way back; panel steps click by click; name stays Cyberblock | 2-K3, 6.3 C-E, 6.4 |
| §2 server cannot reach 127.0.0.1: client relays; LOCAL vs RELAYED for every message; singleplayer same path | 1.2, 1.3, 1-L1, 1-W1, 1-C1 |
| §2 several hosts (sessions by UUID), single host first | 1.4, 1-N2, 3-R2 (`maxHosts` 4) |
| §2 security: whitelisted hosts, validate and rate-limit, version handshake with clear messages, host toggles, auto reconnect and cleanup | 1.5, 1.6, 1-N2, 1-N3, 1-L1, 1-H1 |
| Q1 pickaxe survival mining (aim ray ignores our boxes, margin, smoothing, per-tick log, on-ground and attack press checks, regression test, game check) | Q-B1, Q-B2, Q-B3, Q-S1, Q-V5 |
| Q2 auto-mount on block stairs (2.31 vault/climb decisions refused for our boxes, real ledges keep working, step-up assist if needed) | Q-S2, Q-D1 (`prefs.stepup`) |
| Q3 third-person polish (logs; render interpolation; elytra/shield/use-item flicker; camera collision, smoothing, shoulder, distance and FOV, recenter, no clipping, vehicle distance, crosshair; debug overlay) | Q-V1 to Q-V4, Q-D1, Q-S3 |
| Q4 UI coverage: cheap gaps now, the rest into later phases | Q-U1, Q-U2, Q-U3; 7-V1, 6-B1, 8-T2, 9-U1 |
| Q5 creative breaks outline blocks (server-side game mode, Minecraft block only, no host event, snap back ~10 s or probe report, void-fall rescue, permanent-erase setting host/ops in screen and command, Q1 lets creative target them, three tests, in-game check) | Q-B1, Q-B4, Q-B5, Q-B6; 3-R1 for replica blocks |
| Phase 0 (a) to (h) with fallbacks | 4.1, 4.2, 4.3 |
| Phase 1 | section 5 |
| Phase 2 (backup, jar swap, world upload, whitelist, modpack with mod + Fabric API + voice chat + resource pack, server pushes the pack, two-click join, friend test) | 2-S1, 2-K1 to 2-K4, 2-M1, 6.3, 6.4 |
| Phase 3 (visible material blocks saved and growing, web map if feasible, figures, time and weather, neon, guest actions, Offline Builds, Meet Up, Q5 rule for replica) | 3-R1, 3-R2, 3-C1, 3-F1, 3-E1 to 3-E3, 3-S1, 3-D1, 3-A1, 3-P1 |
| Device layer (all device kinds, both ways, door filter, quest-locked grey, per-type toggles, NPC changes) | section 1.11; 4-S1, 4-D1, 4-M1, 4-B1, 4-A1 |
| Figures and skins (about 30 skins, slim/classic by gender, about 12 vehicles in colour, animation flags, red outline, name tag, threat colour, hit flashes, hearts, particles, interpolation, wrecks, offline host = blocks only) | 5-A2, 5-F1, 5-S1, 3-F1 |
| Cyber skins (forced, server flag, swap in the renderer, shipped in the mod, male classic, Lucy converted to slim and checked, `/cyberblock body`, `[V]` prefix, list marker, ally outlines; public-build note) | 5-A1, 5-K1, section 1.12 |
| Redstone Hacking (Relay, Proximity, Heat, Breach Port + Cyberdeck block, Charge, Cyberdeck Link, rate limits, loop detection) | 6-R1 |
| Breach Protocol (grid by security, buffer, daemons to real actions, timer, strength, Firewall, failure alarm and heat, player hacks; leaderboard optional later) | 6-B1, 8-X1 |
| Interaction map (Breach, Hack Wheel, Vehicle Panel, Traffic Tool, Net Map, recon, Comms Panel, Overlord Console, build/TNT) | 6-I1, 6-S1, 6-D1, 8-O1 |
| NPC interaction layer (ripperdoc with surgery, vendors, fixers, bartenders, medics, mechanics; original dialogue as data; hub NPCs always) | 7-N1, 7-A1 |
| Afterlife hub (open without a host, Match Terminal, shops, leaderboards, bartender, ripperdoc, Holo-Table, full-screen Minecraft and parked V) | 7-H1, 7-D1, 3-S1 (`Park.reds`) |
| Holo-Table (3D miniature, controls, pins, layers, spawning, rules, privacy, AtlasScreen reuse, V and Steve paired) | 7-T1 |
| Hacker in the Van | 8-V1 |
| Overlord (console, Threat Points, spawns and events, fairness, intensity, opt-in, Auto-Director) | 8-O1 |
| Matches, PvP, Gang Wars (linked players create, roles for others, per-match teams, both-on PvP, 15 s, red tag, default off, hits on V through Steve's hearts, Gang Wars, reputation if possible) | 8-M1 |
| Hacking players (six chips, opt-in or war zone, counterplay, Firewall, Netrunner, RAM, cooldowns, never on non-opted guests) | 8-X1, section 1.17 |
| Server Heat and Max-Tac (meter, decay, five tiers, real wanted for hosts, Minecraft squads, HUD meter) | 8-T1 |
| Downed and revive (teammates, Trauma Team for eddies, death screen) | 8-T2, section 1.16 |
| Foundation extras: proximity voice chat, Meet Up, settings screen, Performance mode, daily backups, spectator/caster mode, content as data | 2-K1, 3-E2, Q-B5 + 9-S1, 9-F1, 9-B1, 8-T3, section 1.7 + 9-C1 |
| Keys (clash-free; new screens by right-click; keys only if needed, table/DLL/Java/vectors together) | section 1.10 |
| §5 UI coverage and gap plan (convert vendors/ripperdoc, breach, death screen, save/load, loading, fast travel via Holo-Table; keep the rest; decide world-space elements; escape hatch always) | Q-U1 to Q-U3, 6-B1, 7-V1, 7-T1, 8-T2, 9-U1 |
| §7 deliverables per phase; ask for Apex facts after Phase 0 and Phase 2 | section 2; 4.2; 6.3 and the Phase 2 deliverables |

---

## 16. Not in this plan (the brief's section 6: dropped or deferred, do not build)
The Net dimension; the Heist Editor; live contracts, living districts, turf markers; the Time Machine (rollback: rely on
the daily backups of 9-B1); the Training sim; the V Creator (replaced by `/cyberblock body`); a solo AI crew; broadcast
tools; the Lite (no-install) tier, Chaos Stream, Braindance replays and game adapters (v2 ideas only). Other parked ideas
stay out unless the user re-adds them. The optional Daily Breach leaderboard is "later", not in this plan.
