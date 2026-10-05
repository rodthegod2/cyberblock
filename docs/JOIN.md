# Joining a Cyberblock server (for friends)

A friend has set up a Cyberblock server: **Minecraft with Night City in it**. You walk, build, blow things up and hack in a
Night City that a friend's Cyberpunk 2077 game explores for everybody. This page gets you in. You do **not** need Cyberpunk 2077
to play: you are a **guest** with Minecraft only. (If you own Cyberpunk and want your own game linked, that is the
[host setup](HOW-TO-PLAY.md#1-setup).)

## What you need

- **Minecraft: Java Edition**, bought on your own account. The server uses version **26.3**.
- The **Modrinth App** or **Prism Launcher** (both free). They install the whole modpack, mods included, for you.
- The **modpack file** your friend sends you: `cyberblock-<version>.mrpack`. It holds the server's address, so **do not post it
  anywhere public**; it is for you.
- The **password**, if the server has one. Your friend tells you.

## Install, once (about five minutes)

1. Open the Modrinth App (or Prism Launcher).
2. Import the modpack:
   - **Modrinth App:** **File**, **Import from file** (or drag the `.mrpack` into the window);
   - **Prism Launcher:** **Add Instance**, **Import**, choose the `.mrpack` file.
3. Wait while it downloads Fabric, the Cyberblock mod, Fabric API and the voice chat mod, and creates the instance.
4. Press **Play** on the new instance.

## Join

1. On the title screen press **Join Cyberblock**. (It is next to **Play Cyberblock**, which opens your own singleplayer world.)
   It is two clicks from the launcher: **Play**, then **Join Cyberblock**. If the button is missing, use **Multiplayer**,
   **Add Server** and type the address your friend gave you.
2. A prompt offers the server's **resource pack**: accept it.
3. **If the server has a password**, a small box opens by itself: type the password. Or press **T** and type
   `/login <password>`. You type it **once**; the server remembers you. Until you do, you cannot move or chat. Five wrong tries,
   or two minutes without the password, disconnect you.
4. You arrive on a small platform in a dark, empty world. That is correct: **the city appears as the hosts explore it.** When a
   host walks a street in Cyberpunk, solid blocks in the colours of that street appear in your world.

## What you can do

You play ordinary Minecraft; Cyberblock turns it into Night City effects for the hosts and lets you use the city.
- **Build** on the streets. Blocks you place are real collision in a host's Night City. If the host is away, you can build
  anyway; the host gets "While you were away" when they return.
- **Explode and fight.** TNT, creepers, arrows and wind charges near a host's V are real explosions and hits in their game, **if
  the host allows it.** When a host has switched something off you see **blocked** on the action bar; it still happens in your
  Minecraft world.
- **See the people and cars** of the host's Night City as figures with skins, poses and colours, and **hack them** with the chips
  (the Hack Wheel, a Breach Protocol on a device, the interaction map).
- **Meet Up** with a friend: `/cyberblock meetup <player>`; they accept with the clickable chat line.
- **Visit the Afterlife** with `/cyberblock hub`: talk to the fixer, the bartender and the ripperdoc, buy and sell, take a gig, use
  the Holo-Table to jump to a place, start or join a match at the Match Terminal.
- **Take a job in a match:** a teammate, a **Hacker in the Van**, a spectator or the **Overlord**.
- **Talk by voice.** The voice chat mod is proximity based: players near you hear you. Set your microphone in its own settings.
- Everything is in the [player guide](HOW-TO-PLAY.md#5-what-you-can-do-layer-by-layer).

## Your settings

Press **Esc**, then **Cyberblock...** (or type `/cyberblock settings`): one screen with your own choices: your cyber skin, whether PvP
is on for you, and **Performance mode** if the game is slow on your PC: on the **Performance** tab lower **Frame scale**
(50 to 100 percent, 100 by default), then **Figures** (16 to 48, 48 by default); both are saved on your PC only
([what they do](HOW-TO-PLAY.md#performance-mode)). Guest permissions are the **host's** choice, not yours.

## When something goes wrong

| What you see | What to do |
|---|---|
| "This server needs the Cyberblock modpack" | you are not using the modpack's instance: start the Cyberblock instance in the launcher |
| "Cyberblock version mismatch: server X, your mod Y. Update from the modpack." | the server has a newer version: ask your friend for the new `.mrpack` and import it again |
| "This server needs a password: type /login <password>" | type `/login` and the password your friend gave you |
| "Reconnecting to ... in N s" (with **Cancel**) | your connection dropped; it retries by itself for ten minutes |
| the world is empty around you | no host has explored this part of Night City yet: ask a host to walk there, or use the Holo-Table to go where the map exists |
| the game stutters | Performance mode: Esc, Cyberblock..., Performance, lower Frame scale, then Figures |

## Respect the server

Do not share the address, the password or the `.mrpack` file in public places, and do not paste the address in screenshots.
The server is a friend's own; the person who runs it can read [SERVER.md](SERVER.md), and you can add your own dialogue,
skins and shops as a datapack with [CONTENT.md](CONTENT.md) if the server owner agrees.
