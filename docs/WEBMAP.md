# A live web map of the Night City replica (BlueMap)

Phase 3 builds Night City as solid blocks in the server's world, so any web map that reads Minecraft worlds can draw it. This
page is for the **server owner**. It is optional: nothing in Cyberblock needs it, and the replica works the same without it.

**Verdict (Phase 0, checked again for Phase 3).** A BlueMap build for **Fabric on Minecraft 26.3** exists: the Modrinth page of
BlueMap lists the Fabric files 5.24 to 5.28 (September 2026) for 26.1, 26.1.1, 26.1.2, 26.2 and 26.3. Dynmap had no 26.x build
when this was written. So the web map is **possible**, and BlueMap is the one this page describes. This was checked from the
mod's public listing and its installation page; **no server was run here**, so the in-game steps below are the first test.
Download BlueMap yourself from its official page (Modrinth or its own site), never from a link someone sends you.

## What you need
- The dedicated Fabric 26.3 server that already runs Cyberblock (the same Java 25 that the Cyberblock server needs also runs
  BlueMap: it requires Java 25 or newer).
- **One extra TCP port** for the map's web page. On the hosting panel this is an **Apex fact only you can supply**: ask the
  host for an extra TCP port (or check whether the plan has a second port at all). If the host cannot give one, the map can
  still be used through a reverse proxy on the same machine, or not at all; the replica itself does not depend on it.
- **Never put the server address or the port in this repository**, in a commit or in an issue. They go only in your own,
  untracked notes and in the server's own config files.

## Install on the server
1. Stop the server. Put the BlueMap Fabric jar for 26.3 into the server's `mods` folder (the panel's File Manager or FTP; the
   Cyberblock server tools never touch it).
2. Start the server once. BlueMap writes its configuration into `config/bluemap/` and stops short of rendering.
3. Open `config/bluemap/core.conf` and set `accept-download` to `true`. That lets BlueMap fetch the Minecraft client resources
   it needs for textures from Mojang (read the BlueMap page for what that means before you accept).
4. Open `config/bluemap/webserver.conf` and set its `port` to the **extra TCP port** the host gave you (the default port will
   not be reachable through the panel).
5. Start the server again. In the console or as an operator in game, `/bluemap` shows the state and `/bluemap reload` re-reads
   the configuration after you change a file. The first full render of a map is started with BlueMap's own render command
   (see its `/bluemap` help); afterwards it keeps up with changes by itself.
6. Open `http://<your server address>:<the extra TCP port>/` in a browser. Share it only with the people you want to see it.

## The `nc_*` replica blocks
The replica blocks are ordinary full blocks of the Cyberblock mod (`cyberblock:nc_concrete_light`, `nc_concrete_dark`,
`nc_asphalt`, `nc_sidewalk`, `nc_glass`, `nc_metal`, `nc_brick`, `nc_tile`, `nc_ground`, `nc_water` and the sixteen
`nc_neon_<dye>`). BlueMap on Fabric reads the models and textures of the mods the server loads, so the blocks should appear
with their own look, seen from above, and the neon blocks keep their colours. **If a block shows up wrong** (missing, black or
the wrong colour), the way to fix it is BlueMap's own resource-pack mechanism, not Cyberblock:
- put a resource pack (a folder or a zip) into `config/bluemap/packs/`; packs there override each other in alphabetical
  order, so name yours with a prefix such as `99_cyberblock`;
- in it, give the block the model and texture you want under `assets/cyberblock/` (the same paths the mod uses), and use
  BlueMap's own block-colour and block-property files if you only want to change how a block is tinted or treated (see the
  BlueMap wiki, "Resource packs"; the file names there are BlueMap's and can change between versions);
- after changing packs, run `/bluemap purge <map>` and render again, as BlueMap's page says, to avoid a half-old render;
- BlueMap writes its problems to its own debug log (`bluemap/logs/debug.log` on the server).

The world of the Apex server is a **void world** (see "The Apex server's world" in the
[player guide](HOW-TO-PLAY.md#night-city-as-minecraft-phase-3)): the map shows the replica and the spawn platform and nothing
else, which is exactly the explored part of Night City.

## Telling Cyberblock about it
`config/cyberblock/server.json` has `"webMap": false`. Set it to `true` yourself once the map works. It is a flag for you and
the server tools to remember that a map exists: Cyberblock does not start, stop or configure BlueMap, and BlueMap works whether
or not the flag is set.

## If it does not work
- No page at all: the port in `webserver.conf` is not the one the host gave you, or the host has not opened it.
- A map of nothing: the world has no replica yet (a host has to walk the streets first), or the first render has not run.
- The server runs out of memory or the panel complains: BlueMap's rendering needs RAM; render at a quiet time or lower its
  render threads in its own config, and keep your normal backup first.
- Anything else: BlueMap's own wiki and its Discord are the place; this page does not change how BlueMap works.
