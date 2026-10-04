# Running a Cyberblock server

This page is for the **server owner**: the person who runs the dedicated server friends join. A friend who only wants to join
reads [JOIN.md](JOIN.md); the in-game side of everything is in [HOW-TO-PLAY](HOW-TO-PLAY.md#playing-together-lan).

A Cyberblock server is a normal **Fabric 26.3 dedicated Minecraft server** with the Cyberblock mod in `mods/`. Its world is a
**void world** (level type `cyberblock:nightcity`): the only ground is the Night City replica that the players' Cyberpunk games
build as they explore, and a small spawn platform where players arrive before anything is explored. Players come in two kinds:

- a **guest** has Minecraft only and plays in the replica;
- a **host** has Minecraft linked to its own Cyberpunk 2077 (the setup in [HOW-TO-PLAY](HOW-TO-PLAY.md#1-setup)). The server relays
  between the hosts' games and everybody else. Up to `maxHosts` hosts play at once (4 by default); a linked player beyond that
  joins as a guest.

**Rules of this page.** Never put the server address, a password, the FTP host or the FTP user name in the repository, in a
commit, in an issue or in a screenshot. Every tool below reads secrets from the environment or asks for them hidden, and never
prints them.

## 1. What you need

| Need | Notes |
|---|---|
| A host that runs **Java 25** and a **custom or Fabric server jar** | a hosting panel with a "Custom Jar" and a Java version setting works (Apex is the one this project was written against; the labels below are Apex's, other panels word them differently). A PC at home works as well. |
| The **Fabric server launcher jar for 26.3** | downloaded by you from [fabricmc.net](https://fabricmc.net/use/server/) |
| **Fabric API** for 26.3 (0.161.0 or newer) | [Modrinth](https://modrinth.com/mod/fabric-api) |
| The **Cyberblock mod jar** | `cyberblock-<version>.jar` from the release zip, or `uv run python -m tools.package` |
| Optional: a **proximity voice chat** mod for 26.3 | the modpack carries it so friends hear each other |
| Disk space | the replica grows as people explore, and each daily backup is a zip of the world: leave room for at least twice the world size |
| Your PC, to do the file work | a checkout of this repository, Python with `uv`, and Windows PowerShell; nothing is built on the server |

## 2. First setup, step by step

The tools in `tools/` do the file work and **never delete anything on the server**. You click in the hosting panel.

1. **Back up first.** Stop the server in the panel. If the panel has backups, create one. Then, in your own terminal (the tool asks
   for the FTP password hidden; do not put it on the command line):
   ```
   uv run python -m tools.server_sync backup --to "%USERPROFILE%\CyberblockServerBackups\before-cyberblock"
   uv run python -m tools.server_sync verify --backup "%USERPROFILE%\CyberblockServerBackups\before-cyberblock"
   ```
   The connection comes from the environment (`APEX_FTP_HOST`, `APEX_FTP_USER`, `APEX_FTP_PASS`, `APEX_FTP_PORT`,
   `APEX_FTP_BASE`) or, for the password, a saved Windows credential, or the hidden prompt. `verify` re-hashes every file against
   the manifest; look at the file count and size it prints and check they look complete before you go on.
2. **Download the jars** (Fabric API, voice chat if you want it, the Fabric server launcher) into a folder **outside** the repository.
3. **Stage the files.** This writes into `%LOCALAPPDATA%\Cyberblock\server-staging\`, outside the repository:
   ```
   uv run python -m tools.server_bundle --backup "<server backup folder>" --world "<saves\cyberblock>" ^
       --mods-from "<jar folder>" --launcher "<fabric server launcher jar>" --hosts "<YourName>"
   ```
   It stages `mods/`, `config/cyberblock/server.json`, a `server.properties` (your old one with a few keys changed), a byte-identical
   copy of your old one that the tool names `server.properties.rodsores` (the way back, see section 8), and the world as `cyberblock.zip`. `--dry-run`
   prints the list and writes nothing. **Before uploading, open the staged `config\cyberblock\server.json`** in
   `%LOCALAPPDATA%\Cyberblock\server-staging\`: it is a copy of the repository's `server/server.json.example`, which says
   `"maxHosts": 1`. Set `"maxHosts": 4` (or how many Cyberpunk players you expect) and, if you use the join password instead of the
   whitelist (section 3), set `"joinPassword"` there too. The server's built-in default of 4 applies only when it writes the file
   itself, and a staged file is already there.
   **`tools.server_bundle` is for this first move only.** Never run it again against a server that already runs Cyberblock: its
   upload would replace the live `server.json` (and with it the join password), `server.properties` and
   `server.properties.rodsores` (your only copy of the old server's properties). Updates are jar-only (section 7). For a **fresh Night City void world** rather than an uploaded one, edit the staged
   `server.properties` before uploading: `level-name=nightcity` and `level-type=cyberblock:nightcity`; the server then generates
   the void world on its first start.
4. **Upload.** A dry run first (it lists what would be written, and it refuses to overwrite a remote file outside an allowlist):
   ```
   uv run python -m tools.server_sync upload --stage "%LOCALAPPDATA%\Cyberblock\server-staging"
   uv run python -m tools.server_sync upload --stage "%LOCALAPPDATA%\Cyberblock\server-staging" --go
   ```
   An interrupted upload simply runs again.
5. **Panel changes** (you, click by click):
   1. If you uploaded a world zip: **File Manager**, **Extract** `cyberblock.zip`, and check that a folder `cyberblock` with
      `level.dat` inside appeared.
   2. **Java version**: choose **Java 25** and save. Write the old value down for the way back.
   3. **Server type / jar**: choose **Custom Jar** and type the exact launcher jar file name the tool printed.
   4. **Start**, then open the **Console**.
6. **Read the log.** A healthy start says `cyberblock loaded`, then
   `cyberblock server: ready (protocol 1, maxHosts N, requireMod, whitelist on|OFF)`, then `worldcheck: overworld is cyberblock:nightcity (ok)`.
   `N` is the `maxHosts` of the uploaded `server.json`: 4 if you changed it in step 3, 1 if you did not (then set it now, section 4,
   and restart). Anything else is in section 9.
7. **Decide who may join** (section 3), then **join yourself**: Minecraft with the Cyberblock installation, Multiplayer, add the
   address, join. In the log: `handshake: <you> ...` and, once your Cyberpunk is linked, `session: <you> active`.

## 3. Who may join

There are two ways to keep strangers out. Use one of them.

- **The whitelist.** `white-list=true` and `enforce-whitelist=true` in `server.properties`; in the console `whitelist add <name>` for
  each friend and `op <your name>`. This is what the staged `server.properties` has.
- **The join password.** For a server without a whitelist: set `"joinPassword"` in `config/cyberblock/server.json` (at most 64
  characters) and restart. Every player is then held in place until they type `/login <password>` once per world; the Cyberblock
  mod opens a small password box by itself. Five wrong tries, or 120 seconds without one, disconnect them. The server remembers
  who logged in, so it is typed once; **a new password locks everyone again.** The password is never logged, sent to a client or
  shown by a command (`/cyberblock status` says only `join password: on|off`); share it the way you share the address.

**Who may host** (link a Cyberpunk game): a whitelisted player, or one who passed the join password, and, when `hosts` in
`server.json` is not empty, only the players listed there. `/cyberblock hosts add|remove <name>` edits that list.

## 4. The configuration file

`config/cyberblock/server.json` is read at every start. If it is missing, the server writes it with every value at its default
(the table) and keeps it after that. A value that is wrong is logged and replaced by its default. **An existing file keeps its
values:** the file `tools.server_bundle` stages (a copy of `server/server.json.example`, `"maxHosts": 1`) and a file from an earlier
version both say `"maxHosts": 1` until you change it. **To change a value, edit the file and restart the server.**

| Key | Default | What it does |
|---|---|---|
| `allowHosts` | `true` | `false` turns every player into a guest |
| `hosts` | `[]` | player names or UUIDs allowed to host; empty means every whitelisted (or logged-in) player |
| `maxHosts` | `4` (the staged example file says `1`: set it) | how many Cyberpunk games play at once |
| `requireMod` | `true` | refuse players without the Cyberblock mod, with `joinHelp` in the message |
| `joinHelp` | `""` | one line added to every refusal ("get the modpack from ...") |
| `rate` | `1.0` | scales every message rate limit |
| `sanityRadius` | `160` | how far from its player a host's messages may place things (blocks) |
| `creativeErasesReplica` | `false` | creative-mode breaking of replica blocks is permanent when `true` |
| `pvpDefault` | `false` | the starting value of every player's PvP switch |
| `backups.daily` | `true` | make the daily backup (section 6) |
| `backups.keep` | `7` | how many backups to keep |
| `backups.hour` | `5` | the hour of the day, server local time (0 to 23), at which the daily backup runs |
| `webMap` | `false` | a note to yourself that the optional BlueMap page runs, [WEBMAP.md](WEBMAP.md); it starts nothing |
| `joinPassword` | `""` | the join password; `""` is off |

Per host the server also keeps (in the world, not the file) the host's guest permissions and its last session time; per player
the cyber skin (`body`), the PvP switch and a few other choices. Hosts change their own switches with `/cyberblock guests ...` or
the settings screen (Esc, **Cyberblock...**, or `/cyberblock settings`; see
[HOW-TO-PLAY](HOW-TO-PLAY.md#the-cyberblock-settings-screen)). The screen's **World** tab **only shows** the values of this file to
the host and operators, read-only; you change them in the file, then restart. The one exception is the creative erase, which an
operator switches for the world with `/cyberblock outline erase on|off` (or its row on that tab) while `creativeErasesReplica` is
`false`; `true` in the file forces it on.

## 5. Operator commands

| Command | Who | What it does |
|---|---|---|
| `/cyberblock status` | operator | every player's role, session state, versions and message rates, the loaded content, `join password: on|off` |
| `/cyberblock hosts` | operator | the sessions, roles, versions and rates |
| `/cyberblock hosts add|remove <name>` | level 3 | edit who may host; applies at that player's next handshake |
| `/cyberblock password` | operator | how many players are verified |
| `/cyberblock password reset` | level 3 | clear the verified list: everybody types the password again |
| `/cyberblock worldcheck [on|off]` | operator | the void world check; `off` lets hosts play on a world that is not the Night City type |
| `/cyberblock outline erase on|off` | operator | the creative erase setting (also `/cyberblock outline` for the state) |
| `/cyberblock backup now` | operator | make a backup right now (section 6) |
| `/cyberblock guests <toggle> on|off` | each host | what guests may do to that host's own game |
| `/reload` | operator | reload every datapack, including your content ([CONTENT.md](CONTENT.md)) |

## 6. Daily backups

At `backups.hour` (server local time) the server saves the world and writes a zip, `backups/cyberblock-<date>.zip`, into the
server folder. It keeps the newest `backups.keep` zips and deletes the older ones **of its own making**. If the free disk space is
less than **twice the world size** it skips the backup and logs a warning instead of filling the disk. A finished backup logs
`backup: <file> <size>`. `/cyberblock backup now` does the same on demand.

**Download them to your PC** (this is what protects you from the host itself failing):
```
uv run python -m tools.server_sync pull-backups --to "<server backup folder>"
```
It uses the same environment credentials as above, downloads only the zips that are not already on your PC, and prints "the
server", never the address or the password. A backup folder inside the repository is refused.

**Restoring:** stop the server, move the current world folder aside (do not delete it), unpack a backup next to `server.properties`
so the world folder is back, start the server. A backup holds the replica, the players' builds and the saved per-host and
per-player data; it does not hold the Night City map your own PC keeps (the survey in `%APPDATA%\.minecraft-cyberblock`).

## 7. Updating, content and tuning

- **Updating the mod** (jar only). Every player's mod must match the server's in major and minor version (`2.0.x` with `2.0.y`).
  1. **Stop** the server in the panel.
  2. **File Manager:** delete or rename (for example to `.jar.old`) the old `mods/cyberblock-<old version>.jar`. The new jar has a
     different file name, so an upload does not replace the old one, and two Cyberblock jars in `mods/` stop Fabric from starting
     (duplicate mod id `cyberblock`). `tools.server_sync` never deletes, so this step is yours.
  3. **Upload only the new jar** `mods/cyberblock-<new version>.jar`: through the panel's File Manager, or with
     `tools.server_sync upload --stage <folder>` (dry run first, then `--go`) where `<folder>` holds nothing but
     `mods\cyberblock-<new version>.jar`.
  4. Update Fabric API the same way only if the release notes ask for a newer one.
  5. **Start**, and check the console for `cyberblock server: ready`.

  **Never re-run `tools.server_bundle` for an update.** It is for the first move only: its staged `config/cyberblock/server.json`
  (from the example file, `joinPassword` empty and `maxHosts` 1), `server.properties` (whitelist on, `level-name=cyberblock`) and
  `server.properties.rodsores` would overwrite the live join password, the world and whitelist settings, and the only copy of the
  old server's properties that section 8 needs. Players update from the new modpack. A player on another version sees
  "Cyberblock version mismatch: server X, your mod Y. Update from the modpack."
- **Content.** Friends' dialogue lines, skins, shops, landmarks, daemons and events are datapacks in the world's `datapacks/`
  folder. `/reload` loads them. [CONTENT.md](CONTENT.md) has a worked example of each.
- **The resource pack.** `uv run python -m tools.respack` builds `dist/cyberblock-pack-<version>.zip` and prints its SHA-1. Put the
  pack on a place friends can download from, and set `resource-pack` and `resource-pack-sha1` in `server.properties`.
  `require-resource-pack` stays off until the pack has a host; the modpack carries its own copy meanwhile.
- **The modpack for friends.** `uv run python -m tools.modpack --mods-from <jar folder> --urls <urls.json> --server-file
  <server.json>` builds `dist/cyberblock-<version>.mrpack` with a **Join Cyberblock** button wired to your server. The three input
  files stay outside the repository, and the `.mrpack` holds your address, so give it to friends directly, never publish it.
- **Performance.** Players on weaker PCs use **Performance mode** in their own settings screen: they lower **Frame scale**, then
  **Figures**, saved on their own PC ([HOW-TO-PLAY](HOW-TO-PLAY.md#performance-mode)); nothing for you to configure.
  <!-- integrator: keep the next sentence only if wc/9F merged the server tick budgets. -->
  Where the release notes list them, the server's replica placements, device updates and downed-player queue also have a
  per-tick budget so a busy night stays smooth. `/cyberblock status` shows message rates, and
  the log lists `relay: <name> dropped <t> x N (<why>)` when a player's game floods the server.
- **A web map** of the replica is possible with BlueMap: [WEBMAP.md](WEBMAP.md).

## 8. The way back to your old server

Nothing was deleted on the way in, so going back takes a few minutes:

1. Panel: **Stop**.
2. **Server type / jar**: back to the jar you used before (its name is in the backup's `summary.json`).
3. **Java version**: back to the value you wrote down.
4. **File Manager**: rename `server.properties` to `server.properties.cyberblock` and `server.properties.rodsores` to
   `server.properties` (that file is the byte-identical copy of the old one).
5. **Start.** The old server ignores the Fabric files (`mods/`, `config/cyberblock/`, `.fabric/`, `libraries/`, `versions/` and the
   Cyberblock world folder). To switch to Cyberblock again make the same four changes the other way, then **Start**.

If anything of the old server is ever missing, `tools.server_sync upload --overwrite <path>` restores a single file from the PC
backup of step 1.

## 9. When something is wrong

| What you see | What it means and what to do |
|---|---|
| the server does not start, Java version error in the console | the panel is not on Java 25: change **Java version** |
| `worldcheck: overworld is minecraft:overworld, not cyberblock:nightcity; hosts refused` every minute | the world is not the Night City void world. Use a fresh `level-type=cyberblock:nightcity` world, or `/cyberblock worldcheck off` if you really want hosts there |
| `whitelist OFF` warning every minute | no whitelist and no join password: strangers can join. Turn the whitelist on or set `joinPassword` |
| a player says "This server needs a password: type /login <password>" | the join password is on: they type `/login` and the password you gave them |
| "This server needs the Cyberblock modpack" | the player has no Cyberblock mod: send them [JOIN.md](JOIN.md) |
| "Cyberblock version mismatch: server X, your mod Y" | update the mod on one side so major and minor match |
| a second linked player is a guest | `maxHosts` in `server.json` is still 1 (the staged example file, or an older file): set it to 4 and restart |
| `relay: <name> dropped <t> x N (<why>)` | that player's game sends more than its rate allows or something invalid; if it persists they are kicked with "too many messages from your Cyberpunk link": ask them to restart both games |
| `content: <kind> <id> skipped: <why>` | a datapack file is wrong; [CONTENT.md](CONTENT.md) |
| `backup: ...` never appears | `backups.daily` is `false`, or the disk is under twice the world size (the log says so), or the hour has not come yet; try `/cyberblock backup now` |
