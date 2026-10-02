# Cyberblock

Cyberblock lets you play Cyberpunk 2077 as a Minecraft player: real Minecraft Java (26.3 + a Fabric mod) runs
next to Cyberpunk 2077 (2.31) and is drawn into Night City every frame with the correct camera, depth and
Night City collision, while Cyberpunk moves the body and Steve acts. It is a passthrough in the style of
universal-modder's Minecraft x GTA V example (see `NOTICE.md`).

## Prerequisites
- Cyberpunk 2077 2.31 with RED4ext, redscript, Codeware and Cyber Engine Tweaks.
- ReShade 6.x **with add-on support**.
- JDK 25, Visual Studio 2022 Build Tools (C++ x64 + Windows SDK), CMake, Python 3.12 + uv.

See `docs/superpowers/specs/2026-10-02-minecraft-cyberpunk-passthrough-design.md` (section 2) for details.

## Use
```
uv run tools/install.py           # install the plugin, scripts and shader into the game
uv run tools/play.py              # start the Minecraft dev client, then Cyberpunk 2077
uv run tools/install.py --remove  # remove exactly what was installed
```
F9 toggles Cyberblock in game.
