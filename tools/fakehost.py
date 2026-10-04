"""A stand-in for Cyberpunk: drives Minecraft's camera and gives it a known ground, then dumps the frames Minecraft
exports, to check the mod before the real host exists. Based on universal-modder (MIT) by Rehan:
examples/minecraft-gta5-passthrough/host/fakehost.py

Start the Minecraft dev client first (mc\\gradlew.bat runClient --args="--quickPlaySingleplayer cyberblock"), then:
    uv run python -m tools.fakehost --frames 120 --out out/fakehost

It sends `hello`, `view`, a 17 x 17 stone-slab `ground` (x, z in [-8, 8], y 63; on top of it the slab is made visible
with reference-mod `cmd` server commands, which are not part of protocol v1) and one orbit of the camera around
(0, 65, 0) at 60 Hz. Every 30 frames it saves the latest exported frame as out/frame_XXX_{color,depth,overlay}.png:
colour and overlay composited over dark grey, depth as a grey ramp (white = near, 32 m = black, nothing = black).

With --layer2 it then plays Cyberpunk's part of layer 2 (shared/protocol.md): an `npcs` list with two humans (one
hostile) and a vehicle standing on the slab, and one `dmg` (25 % of V's health, a bullet from the hostile human), then
any --cmd server commands (e.g. --cmd "summon tnt 0 64 0"). For --hold seconds (default 10) it holds the last camera pose
and resends the NPC list at 10 Hz, as the real host does, while printing every `block`, `explosion`, `hit`, `ignite` and
`blocks` Minecraft reports, one line each: `<type>: <json>`.

--layer3 .. --layer6 (combinable with each other and with --layer2, all after the layer 1 script, all with the same
--hold, --cmd and NPC resend; --port picks Minecraft's port, default 25600) play Cyberpunk's part of layers 3-6 and
print what Minecraft reports for their layer as `<type>: <json>`:
  --layer3  sends the `npcs`; the first non-empty `mobs` list is answered once with `mobdmg` (frac 0.5, by the hostile
            human). Prints `mobs`.
  --layer4  V falls from y = 200 at 8 m/s (the `cam` stream) with a `key jump` tap at 0.5 s; while Minecraft reports
            `flight` active (a glide, or since re-test 2 a launch: `kind` and `ts` are accepted) the `cam` follows its
            position and look and says `fly:true`, which Minecraft needs within 0.5 s to keep the flight; a touchdown or
            a `teleport` puts V down there (`fly:false`); every `proj` is answered with `pcells`, a 3 x 3 wall 0.5 s
            ahead of each projectile. Prints `flight`, `teleport` and `proj`.
  --layer5  prints `lead`, `pull`, `knock` and `effect` (set the scene with --cmd).
  --layer6  answers every `scan` with a canned `scaninfo` (Officer Test, NCPD, high, 0.8, the first human) and every
            `hack` with `hackresult ok:true`; holds `key hack` from 1 s to 2 s, `key skills` from 2.5 s to 2.6 s and
            reports `killed` for the hostile human at 3 s; then presses the Cyberware key U twice (3.5 s opens the
            screen, 4 s closes it, `key cyberware`) and taps the Activate key Y at 4.5 s (`key activate`, released after
            0.1 s: a tap). Prints `perks`, `cyberware`, `cyberfx`, `perkfx`, `binds`, `scan` and `hack`.

Re-test 2: Minecraft's `align` (the world's ground alignment k) is printed with every run, like the `blocks` resync that
always follows it; neither needs an answer. The fake host's `cam` carries no `lv`, so Minecraft converts it at its
current k.

Re-test 3 modes (shared/protocol.md "Re-test 3"; combinable with each other and with the layer flags, same --hold, --cmd,
--port and NPC resend; each mode runs for --hold seconds after the orbit and prints what Minecraft reports):
  --ui     plays the UI stream: `stats`, a `quest` with a waypoint, `pins`, a patch of Atlas `terrain`, a `prompt`, a 4-choice `choices` hub whose
           selection changes while its timer runs down, a `sub`title, a ringing `call`, three `notify` toasts,
           `screen open:"pause"`, then `screen pass:"vendor"` with `cam menu:true` and `pass:""`, and a `key
           perspective` tap (F5); the camera follows `persp back` (`fp:false`). Answers `uiact journalreq`, `phonereq`
           and `atlasreq` with sample `reply` messages. Prints `uiact`, `persp`, `binds` and every `status`
           (`pause` included).
  --hacks  answers every `hack` with `hackresult` carrying `upload` and `kind` (the hostile human is a squad member, the
           other human a crowd pedestrian, the vehicle a driver, devices rotate camera/traffic/distract/device) and sends
           `hackspread`: `chain` and `spread` before the result, `ping` and `frenzy` after the upload; taps the Arms
           (`key arms`) and Optical Camo (`key camo`) keys. Prints `hack`, `uiact`, `persp` and `binds` (six keys).
  --seat   V rides in a vehicle: `state vehicle:true`, `cam seat:true` with `pl` at V's hips, and `fp` flipping every
           2 s (a first-person then a third-person vehicle camera). Prints `persp` and `binds`.

Phase Q modes (shared/protocol.md "Phase Q"; combinable with the layer flags except --layer4, but each of --view, --stairs and
--seat drives the camera, so use only one of them; same --hold, --cmd, --port and NPC resend; they print what Minecraft reports,
`prefs` included):
  --view   third-person view (`fp:false`): V walks (4.3 m/s) for 3 s and sprints (5.6 m/s, `key sprint`) for 3 s
           round a circle on the slab, with the camera 3.2 m behind and 0.45 m to the right (it follows Minecraft's `prefs`: dist,
           shoulder, fov), then falls and glides: a `key jump` tap 0.5 s into the fall, after which the `cam` follows Minecraft's
           `flight` like --layer4 (`fly:true`, `air:true`). Every `cam` carries `vdbg` while Minecraft's last `prefs` had
           `debug:true` (`/cyberblock debug view on`); it shows the camera pulled in by a wall between 4 s and 5 s. Use
           --hold 20 to see all three poses.
  --stairs a staircase of five placed blocks (cobblestone columns 1..5 high, `cmd fill` at the start) with ground under it (the slab);
           V walks up it at 1.5 m/s in first person, each step lifting V by 1 m in 0.15 s as the step-up assist does, then stands on top.
           `aim` is sent at 10 Hz at the face of the block under the crosshair (looking 20 degrees down), 2 mm in front of it: the
           Cyberblock box in front of the block, the case Phase Q's aim margin has to tolerate; `persp back` makes `fp` false.
Phase 3 mode (shared/protocol.md "Phase 3"; combinable with the layer flags except --layer4, a camera owner like --view; same --hold,
--cmd, --port, --name, --ids-from and NPC resend; prints what Minecraft reports, `vteleport` and `park` included):
  --replica  the Night City replica: V stands on a street of ground (the slab's area, x and z in [-8, 8]) built from material columns:
             asphalt road, sidewalks, brick, concrete, tile buildings, a glass shopfront, three neon signs, a metal pole, a puddle, ground
             (`ground` with `m` codes, in chunks of 64 quadruples, sent when the client makes the DLL the host). `npcs` carries four
             figures at real speed (a hostile runner at 4 m/s and a walker at 1.4 m/s on the sidewalks, a car at 8 m/s on the road and a
             second car that V destroys at 6 s: `killed` for it, then it is gone from the list); `env` steps every 10 s through 22:30 clear, 23:00 rain,
             23:30 storm, 05:45 clear (and again). Scripted: at 12 s `unground seen:true` of one scaffold column, at 12.5 s a plain
             `unground` of another (it only left the radius), at 14 s `clear` and the whole ground again without both. Every `vteleport` is
             answered 2 s later with `vteleported ok:true` (V is there from then on) and every `park` with `parked`.
             --shift DX moves the street, the figures and V DX blocks east; --env-hours H adds H hours to the clock and makes the weather
             fog (a second fake host that overlaps the first and whose sky must never reach the server).
Multiplayer (shared/protocol.md "Multiplayer (Phase 1)"; tools/mpcheck.py starts one fake host per host client):
  --name NAME      prefixes every line this tool prints with `[NAME] `, so the output of two fake hosts can be told apart.
  --ver VERSION    the DLL version in the `hello` (`ver`, at most 32 characters). The default is this checkout's version, the
                   mod's own: a host whose DLL differs from its mod version is a guest ("versions" scenario). `--ver ""` sends
                   no `ver`, like a DLL from before Phase 1.
  --ids-from N     numbers the three `npcs` N, N+1 and N+2 instead of the protocol.md example ids (1 <= N <= 2**64 - 3): two fake
                   hosts given the same N send the same ids, to force an id collision between two owners ("twohosts").
Minecraft's `session` message (the client tells the DLL its role after every handshake) is printed with every run, like `align`.
A change to `session state:host` is answered as the real DLL does (protocol.md `session`): `clear`, the whole `ground` slab
again and, when a layer or mode runs, the `npcs` list. The client drops what the DLL sends before it is the host, so the
ground sent when the link opened (at the title screen, before a server join) never reached the server.
A dedicated server refuses the reference-mod `cmd` commands: against a real client pass `--no-setup`.
"""
import argparse
import asyncio
import json
import math
import re
import sys
import time
from collections.abc import Sequence
from pathlib import Path

import numpy as np
from PIL import Image
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

from tools import cbmath, package, shm

DEFAULT_PORT = 25600
VIEW = (1280, 720)
CENTER = (0.0, 65.0, 0.0)
RADIUS, EYE_Y, FOV = 6.0, 66.5, 70.0
SLAB_HALF, SLAB_Y = 8, 63
SAVE_EVERY = 30
LAYER2_EVENTS = {"block", "explosion", "hit", "ignite", "blocks", "align", "session"}  # what Minecraft reports back to Cyberpunk in layer 2, the resync, the ground alignment and (Phase 1) the session role
LAYER_EVENTS = {  # ... and in layers 3-6 (printed only when the layer's flag is given)
    3: {"mobs"},
    4: {"flight", "teleport", "proj"},
    5: {"lead", "pull", "knock", "effect"},
    6: {"perks", "perkfx", "binds", "scan", "hack", "cyberware", "cyberfx"},
}
NPC_HZ = 10
SCRIPT_SCALE = 1.0  # multiplies every time of the layer 4 and layer 6 scripts (the tests shrink it)
FALL_FROM, FALL_SPEED, FALL_XZ, EYE_HEIGHT, FLOOR_Y = 200.0, 8.0, (-1.0, -1.0), 1.62, SLAB_Y + 1.0  # layer 4: V's fall
JUMP_AT, JUMP_FOR = 0.5, 0.1  # layer 4: seconds after the scenario starts, and how long the key stays down
HACK_KEY, SKILLS_KEY, KILLED_AT = (1.0, 2.0), (2.5, 2.6), 3.0  # layer 6: (down, up) and the kill, in seconds
CYBERWARE_KEYS, ACTIVATE_KEY = ((3.5, 3.6), (4.0, 4.1)), (4.5, 4.6)  # layer 6: U opens the Cyberware screen then closes it; Y is a tap
PCELLS_AHEAD, MAX_PCELLS = 0.5, 64  # layer 4: seconds ahead of a projectile for its wall, and the `pcells` cap
MODES = ("ui", "hacks", "seat", "view", "stairs", "replica")  # re-test 3: --ui, --hacks, --seat; Phase Q: --view, --stairs; Phase 3: --replica
MODE_EVENTS = {"ui": {"uiact", "persp", "binds"}, "hacks": {"hack", "uiact", "persp", "binds"}, "seat": {"persp", "binds"},
               "view": {"prefs"}, "stairs": {"prefs"}, "replica": {"vteleport", "park"}}
CAMERA_OWNERS = ("view", "stairs", "seat", "replica")  # the modes that drive the camera by themselves
UI_MENU = (6.0, 7.0)  # --ui: seconds in which `cam.menu` is true (a pass-through menu is open)
PERSPECTIVE_KEY = (8.0, 8.1)  # --ui: the F5 tap that makes Minecraft answer `persp`
HACKS_KEYS = {"hack": (1.0, 2.0), "arms": (2.5, 2.6), "camo": (3.0, 3.1)}  # --hacks: (down, up) in seconds
PREFS_DEFAULT = {"dist": 3.2, "shoulder": 0.45, "fov": 0, "recenter": True, "vdist": 0, "stepup": True, "debug": False}  # Phase Q: the DLL's defaults
PREFS_RANGES = {"dist": (1.5, 8.0), "shoulder": (-1.0, 1.0), "fov": (50.0, 110.0), "vdist": (3.0, 14.0)}  # `fov` and `vdist` also take 0
WALK_SPEED, SPRINT_SPEED, WALK_FOR, SPRINT_FOR, VIEW_RADIUS = 4.3, 5.6, 3.0, 3.0, 5.0  # --view: m/s, seconds each, circle radius (m)
VIEW_PITCH, GLIDE_FROM = 10.0, SLAB_Y + 1.0 + 40.0  # --view: camera pitch (degrees, down) on the ground; the glide starts 40 m up
WALL_WINDOW, WALL_DIST, SMOOTH_LAG = (4.0, 5.0), 1.2, 0.15  # --view vdbg: when a wall pulls the camera in, to what distance, how late `smooth` follows
STAIRS_Z, STAIRS_X0, STAIRS_STEPS, STAIRS_SPEED, STAIRS_RISE = 6, -2, 5, 1.5, 0.15  # --stairs: row z, first column x, columns 1..5 high, m/s, seconds per step
STAIRS_START_X, STAIRS_REACH, STAIRS_PITCH = -4.5, 0.3, 20.0  # --stairs: V's start x, V's radius (a step lifts V at x + REACH = column edge), look pitch
AIM_BIAS, AIM_MAX, AIM_EVERY, AIM_STEP = -0.002, 8.0, 0.1, 0.02  # --stairs: the box face sits 2 mm in front of the block face; 8 m cap; 10 Hz; 2 cm change
STAIRS_COMMANDS = [f"fill {STAIRS_X0 + k} {SLAB_Y + 1} {STAIRS_Z} {STAIRS_X0 + k} {SLAB_Y + 1 + k} {STAIRS_Z} minecraft:cobblestone"
                   for k in range(STAIRS_STEPS)]  # column k (0-based) is k + 1 blocks high on the slab's surface (y 64)
SEAT_PERIOD = 2.0  # --seat: seconds between camera switches (first person <-> third person)
SEAT_FEET, HIPS_UP = (-1.0, SLAB_Y + 1.0, -1.0), 0.6  # --seat: V's feet; `pl` is the hips point, HIPS_UP above them
HACK_UPLOAD = {"short_circuit": 0.6, "overheat": 1.0, "reboot_optics": 0.8, "cripple_movement": 1.2, "contagion": 1.5,
               "synapse_burnout": 3.0, "ping": 0.5, "memory_wipe": 1.0, "cyberpsychosis": 4.0, "device": 0.0}  # canned upload seconds
DEVICE_KINDS = ("camera", "traffic", "distract", "device")
DEPTH_RANGE_M = 32.0
NPC_IDS = ("9007199254740993", "18446744073709551615", "4503599627370497")  # the protocol.md example ids: two humans, a vehicle
NAME_RE, MAX_VER = re.compile(r"[A-Za-z0-9_.-]{1,32}"), 32  # --name: a log prefix that stays easy to grep; --ver: protocol.md `hello.ver`
BACKGROUND = np.array([40, 40, 48], np.float64)
SETUP_COMMANDS = [  # a gold block at the centre and four coloured blocks on the axes: +x red, -x blue, +z lime, -z yellow
    f"fill -{SLAB_HALF} {SLAB_Y} -{SLAB_HALF} {SLAB_HALF} {SLAB_Y} {SLAB_HALF} minecraft:stone",
    "setblock 0 64 0 minecraft:gold_block",
    "setblock 3 64 0 minecraft:red_concrete",
    "setblock -3 64 0 minecraft:blue_concrete",
    "setblock 0 64 3 minecraft:lime_concrete",
    "setblock 0 64 -3 minecraft:yellow_concrete",
]


def ground_slab() -> list[int]:
    """The `ground` ranges of the slab: one {x, z, yBottom, yTop} per column."""
    return [v for x in range(-SLAB_HALF, SLAB_HALF + 1) for z in range(-SLAB_HALF, SLAB_HALF + 1) for v in (x, z, SLAB_Y, SLAB_Y)]


def orbit_cam(f: int, frames: int) -> dict:
    """The `cam` message for frame f (1-based): one full orbit over `frames` frames, always looking at CENTER."""
    a = 2 * math.pi * (f - 1) / frames
    eye = (CENTER[0] + RADIUS * math.sin(a), EYE_Y, CENTER[2] + RADIUS * math.cos(a))
    yaw, pitch = cbmath.mc_yaw_pitch(*(c - e for c, e in zip(CENTER, eye)))
    return {"t": "cam", "f": f, "p": list(eye), "r": [yaw, pitch, 0.0], "fov": FOV, "fp": True,
            "pl": [eye[0], SLAB_Y + 1.0, eye[2]], "h": yaw, "cr": False}


def npc_ids(ids_from: int | None = None) -> tuple[str, str, str]:
    """The ids of the three NPCs: the examples of protocol.md, or `ids_from`, +1 and +2 (--ids-from; uint64 decimal strings)."""
    if ids_from is None:
        return NPC_IDS
    if not 1 <= ids_from <= 2**64 - 3:
        raise ValueError(f"--ids-from must be 1..{2**64 - 3} so that all three ids are non-zero uint64 values, not {ids_from}")
    return tuple(str(ids_from + i) for i in range(3))


def npcs_message(ids_from: int | None = None) -> dict:
    """The `npcs` list: two humans and a vehicle on the slab, clear of the setup blocks. Ids are the examples of protocol.md,
    or numbered from `ids_from` (two fake hosts with the same `ids_from` collide on purpose)."""
    feet = SLAB_Y + 1.0
    hostile, friendly, car = npc_ids(ids_from)
    return {"t": "npcs", "list": [
        {"id": hostile, "p": [2.5, feet, 2.5], "yaw": 135.0, "kind": "human", "hostile": True},
        {"id": friendly, "p": [-2.5, feet, 2.5], "yaw": -135.0, "kind": "human", "hostile": False},
        {"id": car, "p": [-4.5, feet, -4.5], "yaw": 180.0, "kind": "vehicle", "hostile": False},
    ]}


def dmg_message() -> dict:
    """A quarter of V's health, shot by the hostile human (from its chest)."""
    x, y, z = npcs_message()["list"][0]["p"]
    return {"t": "dmg", "frac": 0.25, "kind": "bullet", "from": [x, y + 1.3, z]}


def hostile_human(ids_from: int | None = None) -> dict:
    """The hostile human of `npcs_message`: the canned scan target, the `mobdmg` attacker and the kill."""
    return next(e for e in npcs_message(ids_from)["list"] if e["kind"] == "human" and e["hostile"])


def scaninfo_message(request: int, ids_from: int | None = None) -> dict:
    """The canned reply to a `scan`: the hostile human, Officer Test of the NCPD."""
    h = hostile_human(ids_from)
    return {"t": "scaninfo", "id": request, "ok": True, "target": h["id"], "name": "Officer Test", "faction": "NCPD",
            "threat": "high", "hp": 0.8, "device": False, "p": h["p"], "size": [0.6, 1.8]}


def pcells_message(projectiles: list) -> dict | None:
    """`pcells` for a `proj` list: per projectile a 3 x 3 wall of cells PCELLS_AHEAD s ahead on its ballistic path, across
    its horizontal heading. Cells are deduplicated and capped at MAX_PCELLS; None when there is nothing to send."""
    cells: dict[tuple[int, int, int], None] = {}
    for e in projectiles:
        try:
            (x, y, z), (vx, vy, vz), g = e["p"], e["v"], e["g"]
            t = PCELLS_AHEAD
            cx, cy, cz = (math.floor(c) for c in (x + vx * t, y + vy * t - g * t * t / 2, z + vz * t))
        except (KeyError, TypeError, ValueError, OverflowError):  # not a projectile entry
            continue
        for dy in (-1, 0, 1):
            for d in (-1, 0, 1):
                cells[(cx, cy + dy, cz + d) if abs(vx) >= abs(vz) else (cx + d, cy + dy, cz)] = None
    flat = [c for cell in list(cells)[:MAX_PCELLS] for c in cell]
    return {"t": "pcells", "c": flat} if flat else None


def hack_kind(name: str, target: str, request: int, ids_from: int | None = None) -> str:
    """The `hackresult.kind` of a canned answer: devices rotate through the four device kinds by request id, the vehicle's
    driver is hacked, the hostile human is a squad member and the other human a crowd pedestrian."""
    npc = next((e for e in npcs_message(ids_from)["list"] if e["id"] == target), None)
    if name == "device":
        return DEVICE_KINDS[request % len(DEVICE_KINDS)]
    if npc is None or npc["kind"] == "vehicle":
        return "driver"
    return "squad" if npc["hostile"] else "crowd"


def hack_replies(m: dict, ids_from: int | None = None) -> tuple[list[dict], list[dict], float]:
    """The canned answer to a `hack`: (messages to send at once, messages to send `upload` seconds later, that upload).
    `chain` and `spread` spreads come before the `hackresult`, `ping` and `frenzy` come after the upload, as the DLL does."""
    request, target, name = m["id"], m["target"], m["hack"]
    upload = HACK_UPLOAD.get(name, 0.0)
    humans = [e for e in npcs_message(ids_from)["list"] if e["kind"] == "human"]
    others = [e["id"] for e in humans if e["id"] != target]

    def spread(why: str, ids: list[str]) -> list[dict]:
        return [{"t": "hackspread", "id": request, "target": i, "why": why} for i in ids]

    now = spread("chain", others[:1]) if m.get("chain") is True else []
    now += spread("spread", others[:2]) if name == "contagion" else []
    result = {"t": "hackresult", "id": request, "ok": True, "reason": "", "upload": upload, "kind": hack_kind(name, target, request, ids_from)}
    later = spread("ping", [e["id"] for e in humans if e["hostile"]]) if name == "ping" else []
    later += spread("frenzy", others[:1]) if name == "cyberpsychosis" else []
    return [*now, result], later, upload


def hello_message(ver: str | None = None) -> dict:
    """The DLL's `hello`; since Phase 1 it carries `ver` (the DLL version). None leaves it out, like an older DLL."""
    return {"t": "hello", "proto": 1, "game": "cp2077", "build": "2.31", **({"ver": ver} if ver is not None else {})}


def ui_script() -> list[tuple[float, dict]]:
    """--ui: (seconds after the scenario starts, message) of the Minecraft-drawn UI stream, in time order. Every message is
    an example shape of shared/protocol.md "Re-test 3" (tests/test_fakehost.py checks them against its validators)."""
    hub = {"id": 3, "title": "Judy", "list": [{"text": "How are you?", "f": []}, {"text": "Pay 500 eddies", "f": ["pay", "quest"]},
                                              {"text": "Let's go", "f": ["check_ok"]}, {"text": "Leave", "f": ["timed"]}]}

    def choices(sel: int, left: float) -> dict:
        return {"t": "choices", "hubs": [hub], "active": 3, "sel": sel, "left": left}

    prompt = {"t": "prompt", "title": "Vending Machine", "list": [{"text": "Open", "key": "F", "hold": False, "inactive": False},
                                                                   {"text": "Hack", "key": "R", "hold": True, "inactive": True}]}
    sub = {"t": "sub", "show": True, "key": "q101_jackie_0042", "who": "Jackie", "text": "Hey, choom! Over here.", "secs": 3.5}
    call = {"t": "call", "who": "Judy", "audio": True, "reject": True}
    toast = lambda kind, title, text: {"t": "notify", "kind": kind, "title": title, "text": text, "secs": 4.0}  # noqa: E731
    key = lambda down: {"t": "key", "k": "perspective", "down": down}  # noqa: E731
    return sorted([
        (0.0, {"t": "stats", "money": 12450, "wanted": 2, "district": "Watson", "time": [14, 35], "speed": -1}),
        (0.0, {"t": "quest", "title": "The Pickup", "kind": "main", "wp": [4.5, SLAB_Y + 1.0, -6.5], "obj": [
            {"text": "Meet Jackie at the bar", "cur": 0, "tot": 0, "opt": False, "on": True},
            {"text": "Collect the shards", "cur": 1, "tot": 3, "opt": True, "on": False}]}),
        (0.0, {"t": "pins", "list": [{"p": [-5.5, SLAB_Y + 1.0, 3.5], "kind": "vendor", "tr": False},
                                     {"p": [4.5, SLAB_Y + 1.0, -6.5], "kind": "quest", "tr": True},
                                     {"p": [6.5, SLAB_Y + 1.0, 6.5], "kind": "police", "tr": False}]}),
        (0.2, prompt),
        (0.5, choices(0, 8.0)), (0.8, choices(1, 7.7)), (1.1, choices(2, 7.4)), (1.4, choices(3, 7.1)),
        (1.7, {"t": "choices", "hubs": [], "active": -1, "sel": -1, "left": -1}),
        (1.8, {**prompt, "title": "", "list": []}),
        (2.0, sub), (2.0, {**call, "phase": "incoming"}),
        (2.2, toast("quest", "Job updated", "The Pickup")), (2.5, toast("money", "Eddies", "+250")), (2.8, toast("item", "Item", "Iconic pistol")),
        (3.0, {**call, "phase": "active"}), (4.0, {**call, "phase": "end"}),
        (5.5, {**sub, "show": False}),
        (5.0, {"t": "screen", "open": "pause"}), (UI_MENU[0], {"t": "screen", "pass": "vendor"}), (UI_MENU[1], {"t": "screen", "pass": ""}),
        (PERSPECTIVE_KEY[0], key(True)), (PERSPECTIVE_KEY[1], key(False)),
    ] + [(0.3, m) for m in ui_terrain()], key=lambda e: e[0])


def ui_terrain() -> list[dict]:
    """--ui: a sample of the Atlas's explored map around the slab (protocol.md "Atlas" `terrain`): 2-m cells 80 m around, a
    street level at the slab, a 30 m building block north-east of it and the sea to the west, at most 64 cells a message."""
    cells = []
    for cz in range(-20, 20):
        for cx in range(-20, 20):
            water = cx < -12
            tall = 4 <= cx < 10 and -10 <= cz < -4
            cells += [cx, cz, -1 if water else int(SLAB_Y) + (30 if tall else 0), 1 if water else 0]
    return [{"t": "terrain", "c": cells[i:i + 256]} for i in range(0, len(cells), 256)]


def ui_replies(m: dict) -> list[dict]:
    """--ui: the sample `reply` to a `uiact journalreq`, `phonereq` or `atlasreq` (`id` echoed; nothing for other actions)."""
    rid = m.get("id", 0)
    if m.get("a") == "journalreq":
        return [{"t": "reply", "kind": "journal", "id": rid, "pins": [], "groups": [
            {"hash": 3735928559, "title": "The Pickup", "kind": "main", "tracked": True, "done": False,
             "lines": [{"hash": 1001, "text": "Meet Jackie at the bar", "state": 0}, {"hash": 1002, "text": "Get the car", "state": 1}]},
            {"hash": 3735928560, "title": "Sample Gig", "kind": "gig", "tracked": False, "done": False,
             "lines": [{"hash": 1003, "text": "Find the courier", "state": 0}]}]}]
    if m.get("a") == "phonereq":
        return [{"t": "reply", "kind": "phone", "id": rid, "pins": [], "groups": [
            {"hash": 77, "title": "Judy", "kind": "sms", "tracked": False, "done": False,
             "lines": [{"hash": 5, "text": "Call me later.", "state": 0}, {"hash": 6, "text": "Will do.", "state": 2}]}]}]
    if m.get("a") == "atlasreq":
        return [{"t": "reply", "kind": "atlas", "id": rid, "groups": [], "pins": [
            {"p": [-5.5, SLAB_Y + 1.0, 3.5], "kind": "fasttravel", "name": "Watson Docks", "i": 4, "d": "Watson"},
            {"p": [4.5, SLAB_Y + 1.0, -6.5], "kind": "quest", "name": "The Pickup", "i": -1}]}]
    return []


def seat_cam(f: int, t: float) -> dict:
    """--seat: V rides in a vehicle. `pl` is the hips point, `fp` flips every SEAT_PERIOD s (first person, then a third-person
    vehicle camera that sits behind and above V)."""
    x, y, z = SEAT_FEET
    hips = [x, y + HIPS_UP, z]
    first = int(t / (SEAT_PERIOD * SCRIPT_SCALE)) % 2 == 0
    eye = [x, y + HIPS_UP + 0.7, z] if first else [x, y + HIPS_UP + 1.5, z - 4.0]
    return {"t": "cam", "f": f, "p": eye, "r": [0.0, 0.0 if first else 15.0, 0.0], "fov": FOV, "fp": first, "pl": hips, "h": 0.0,
            "cr": False, "seat": True}


def clamp_prefs(m: dict, current: dict) -> dict:
    """The DLL's rule for `prefs` (protocol.md "Phase Q"): every number is clamped into its range (`fov` and `vdist` also
    allow 0, and a value above 0 below the lower bound is raised to it), a field that is missing, not finite or of the wrong
    type keeps its current value."""
    out = dict(current)
    for key, (lo, hi) in PREFS_RANGES.items():
        v = m.get(key)
        if isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v):
            out[key] = 0 if key in ("fov", "vdist") and v <= 0 else min(max(v, lo), hi)
    for key in ("recenter", "stepup", "debug"):
        if isinstance(m.get(key), bool):
            out[key] = m[key]
    return out


def third_person_eye(feet: Sequence[float], yaw: float, pitch: float, dist: float, shoulder: float) -> list[float]:
    """The third-person camera position: V's eye, `dist` m back along the look direction and `shoulder` m to the right of it."""
    yr, pr = math.radians(yaw), math.radians(pitch)
    look = (-math.sin(yr) * math.cos(pr), -math.sin(pr), math.cos(yr) * math.cos(pr))  # Minecraft look vector (yaw 0 faces +z)
    right = (-math.cos(yr), 0.0, -math.sin(yr))
    eye = (feet[0], feet[1] + EYE_HEIGHT, feet[2])
    return [eye[i] - look[i] * dist + right[i] * shoulder for i in range(3)]


def walk_pose(t: float) -> tuple[list[float], float]:
    """--view: V's feet and yaw t seconds in, on a circle of VIEW_RADIUS round the centre: walking for WALK_FOR s, then
    sprinting for SPRINT_FOR s (held at the end of the sprint afterwards)."""
    t = min(max(t, 0.0), WALK_FOR + SPRINT_FOR)
    walked = min(t, WALK_FOR)
    a = (WALK_SPEED * walked + SPRINT_SPEED * (t - walked)) / VIEW_RADIUS
    return [VIEW_RADIUS * math.sin(a), FLOOR_Y, VIEW_RADIUS * math.cos(a)], cbmath.mc_yaw_pitch(math.cos(a), 0.0, -math.sin(a))[0]


def view_collide(t: float, dist: float) -> float:
    """--view: the distance after camera collision: `dist`, pulled in to WALL_DIST (a triangle) while the wall window is open."""
    lo, hi = WALL_WINDOW
    bump = max(0.0, 1.0 - abs(t - (lo + hi) / 2) / ((hi - lo) / 2))
    return dist - (dist - min(dist, WALL_DIST)) * bump


def view_vdbg(f: int, t: float, dist: float) -> list[float]:
    """--view: `cam.vdbg` = [collide m, smooth m, age ms]. `smooth` is `collide` SMOOTH_LAG s late; `age` is a steady 12 to 18 ms."""
    return [round(view_collide(t, dist), 3), round(view_collide(max(0.0, t - SMOOTH_LAG), dist), 3), 12.0 + 2.0 * (f % 4)]


def stairs_top_x() -> float:
    """--stairs: where V stops: the middle of the last (highest) column."""
    return STAIRS_X0 + STAIRS_STEPS - 0.5


def stairs_feet(t: float) -> list[float]:
    """--stairs: V's feet t seconds in: walking +x at STAIRS_SPEED from STAIRS_START_X, and each column edge (minus V's radius) lifts V
    by 1 m over STAIRS_RISE s (the step-up assist), up to the top of the last column."""
    x = min(STAIRS_START_X + STAIRS_SPEED * t, stairs_top_x())
    y = FLOOR_Y
    for k in range(STAIRS_STEPS):
        reached = (STAIRS_X0 + k - STAIRS_REACH - STAIRS_START_X) / STAIRS_SPEED
        y += min(max((t - reached) / STAIRS_RISE, 0.0), 1.0)
    return [x, y, STAIRS_Z + 0.5]


def ray_box(origin: Sequence[float], direction: Sequence[float], lo: Sequence[float], hi: Sequence[float]) -> float | None:
    """Distance along a unit `direction` to the entry of the box [lo, hi] (0 when `origin` is inside it), None for a miss (slab method)."""
    near, far = 0.0, math.inf
    for o, d, a, b in zip(origin, direction, lo, hi):
        if abs(d) < 1e-12:
            if not a <= o <= b:
                return None
            continue
        t1, t2 = (a - o) / d, (b - o) / d
        near, far = max(near, min(t1, t2)), min(far, max(t1, t2))
    return near if near <= far else None


def stairs_aim(eye: Sequence[float], yaw: float, pitch: float) -> float:
    """--stairs: the `aim.d` for a crosshair along (yaw, pitch) from `eye`: the distance to the first block face (the cobblestone
    columns), AIM_BIAS in front of it, or to the slab's surface, whichever is nearer; -1 when nothing is within AIM_MAX."""
    yr, pr = math.radians(yaw), math.radians(pitch)
    look = (-math.sin(yr) * math.cos(pr), -math.sin(pr), math.cos(yr) * math.cos(pr))
    hits = [h + AIM_BIAS for k in range(STAIRS_STEPS)
            if (h := ray_box(eye, look, (STAIRS_X0 + k, FLOOR_Y, STAIRS_Z), (STAIRS_X0 + k + 1, FLOOR_Y + k + 1, STAIRS_Z + 1))) is not None]
    if look[1] < -1e-9 and (ground := (FLOOR_Y - eye[1]) / look[1]) >= 0:
        hits.append(ground)
    d = min(hits, default=-1.0)
    return round(d, 3) if 0 <= d <= AIM_MAX else -1.0


# ---- --replica (megaplan 3-P1): a street of ground with materials, seen removals, walking and driving figures, env, teleport ----
# The street is the slab's area (x and z in [-SLAB_HALF, SLAB_HALF]) built from material columns; `--shift DX` moves all of it
# (ground, figures, V) DX blocks east, so two fake hosts can overlap partly. Everything is deterministic.
ROAD_HALF, SIDEWALK_Z = 1, (2, 3)  # the asphalt strip is |z| <= 1; sidewalks are z in -3..-2 and 2..3
FLOOR_BOTTOM = SLAB_Y - 1  # a floor range is [top - 1, top]
SCAFFOLD_SEEN, SCAFFOLD_LEFT = (7, 4), (7, 6)  # x, z of the two scaffold columns [SLAB_Y + 1, SLAB_Y + 3] (code 6) above the floor
SCAFFOLD_RANGE = (SLAB_Y + 1, SLAB_Y + 3)
SEEN_AT, LEFT_AT, CLEAR_AT = 12.0, 12.5, 14.0  # --replica script, seconds after the scenario starts: `unground seen:true` of the first
# scaffold, `unground` (it only left the radius) of the second, then `clear` and the whole ground again without the first
GROUND_CHUNK = 64  # quadruples per `ground` message
REPLICA_FEET = (-6.5, SLAB_Y + 1.0, 0.5)  # V stands on the road facing east (+x); Minecraft yaw -90
REPLICA_YAW = -90.0
ENV_CYCLE = ((10.0, (22, 30), "clear"), (10.0, (23, 0), "rain"), (10.0, (23, 30), "storm"), (10.0, (5, 45), "clear"))  # seconds, time, weather; repeats
ENV_SECOND_WEATHER = "fog"  # --env-hours: a second host's sky is always fog
TELEPORT_DELAY = 2.0  # seconds before `vteleported ok` answers a `vteleport`
WRECK_AT = 6.0  # seconds after which the standing car is gone from `npcs`; `killed` (V destroyed it) comes just before, so the guest
# sees a wreck for 5 s: the server (3F's ProxyManager) makes a wreck only from `killed`, a proxy that just leaves the list is removed
KILL_LEAD = 0.05  # seconds between the car's `killed` and the first `npcs` without it
WALK_X, RUN_X, CAR_X = 1.4, 4.0, 8.0  # m/s along the street
TELEPORT_WHYS = ("meetup", "holo", "hub_exit")


def replica_code(x: int, z: int) -> tuple[int, int]:
    """(material code, top y) of the street's floor or building at (x, z) with x and z relative to the street's centre."""
    top = SLAB_Y
    if z <= -6:  # the building row: brick, light concrete, dark concrete, tile
        if x <= -5:
            return 7, SLAB_Y + 7
        return (1, SLAB_Y + 5) if x <= -1 else (2, SLAB_Y + 9) if x <= 3 else (8, SLAB_Y + 3)
    if abs(z) <= ROAD_HALF:
        return 3, top
    if z in (-3, -2) or z in SIDEWALK_Z:
        return (6, SLAB_Y + 5) if (x, z) == (4, -3) else (4, top)  # a streetlight pole stands on the sidewalk at (4, -3)
    if z == -4 and -3 <= x <= -1:
        return 5, SLAB_Y + 3  # a shopfront
    if z == -5 and 0 <= x <= 2:
        return 13, SLAB_Y + 2  # a magenta neon sign
    if z == -5 and x == -4:
        return 20, SLAB_Y + 3  # a cyan neon tube
    if z == -5 and x == 5:
        return 25, SLAB_Y + 2  # a red neon sign
    if z in (5, 6) and x in (5, 6):
        return 10, FLOOR_BOTTOM  # a puddle: one block of still water
    return 9, top


def replica_quads(shift: int = 0) -> list[tuple[int, int, int, int, int]]:
    """The street as (x, z, yBottom, yTop, code), x already shifted: one range per column (floor [top-1, top], buildings and signs
    from the floor up) plus the two scaffold ranges above their floors."""
    quads = []
    for x in range(-SLAB_HALF, SLAB_HALF + 1):
        for z in range(-SLAB_HALF, SLAB_HALF + 1):
            code, top = replica_code(x, z)
            quads.append((x + shift, z, FLOOR_BOTTOM, top, code))
    quads += [(x + shift, z, SCAFFOLD_RANGE[0], SCAFFOLD_RANGE[1], 6) for x, z in (SCAFFOLD_SEEN, SCAFFOLD_LEFT)]
    return quads


def replica_ground(shift: int = 0, without: Sequence[tuple[int, int]] = ()) -> list[dict]:
    """`ground` messages (with `m`) of the street, GROUND_CHUNK quadruples each; `without` lists scaffold columns (x, z, unshifted)
    the DLL no longer holds."""
    skip = {(x + shift, z) for x, z in without}
    quads = [q for q in replica_quads(shift) if not ((q[0], q[1]) in skip and q[2] == SCAFFOLD_RANGE[0])]
    return [{"t": "ground", "c": [v for q in chunk for v in q[:4]], "m": [q[4] for q in chunk]}
            for chunk in (quads[i:i + GROUND_CHUNK] for i in range(0, len(quads), GROUND_CHUNK))]


def replica_cells(shift: int = 0) -> dict[tuple[int, int, int], int]:
    """Every cell the street's ranges cover (x, y, z) -> material code, the scaffolds included."""
    return {(x, y, z): code for x, z, lo, hi, code in replica_quads(shift) for y in range(lo, hi + 1)}


def replica_landmarks(shift: int = 0) -> list[tuple[tuple[int, int, int], int]]:
    """One cell of each material of the street (the top cell of one column), ((x, y, z), code): the cells the harness looks at."""
    wanted = {3: (0, 0), 4: (0, 2), 9: (-8, 4), 1: (-3, -7), 2: (1, -7), 7: (-7, -7), 8: (6, -7), 5: (-2, -4), 6: (4, -3), 13: (1, -5),
              20: (-4, -5), 25: (5, -5), 10: (5, 5)}
    out = []
    for code, (x, z) in wanted.items():
        found = replica_code(x, z)
        assert found[0] == code, (code, x, z, found)
        out.append(((x + shift, found[1], z), code))
    return out


def unground_message(x: int, z: int, shift: int = 0, seen: bool = True) -> dict:
    """The `unground` of a scaffold column (range [SLAB_Y + 1, SLAB_Y + 3]): observed empty (`seen:true`) or only left the radius."""
    return {"t": "unground", "c": [x + shift, z, *SCAFFOLD_RANGE], **({"seen": True} if seen else {})}


def replica_ids(ids_from: int | None = None) -> tuple[str, str, str, str]:
    """The four figures' ids: the hostile runner, the friendly walker, the driving car (the three of npc_ids) and the wreck car."""
    base = npc_ids(ids_from)
    if ids_from is None:
        return (*base, "4503599627370498")
    if ids_from > 2**64 - 4:
        raise ValueError(f"--ids-from must be at most {2**64 - 4} with --replica (it numbers four figures)")
    return (*base, str(ids_from + 3))


def loop_x(start: float, speed: float, t: float) -> float:
    """x of a figure that starts at `start` and moves at `speed` m/s along the street, wrapping from one end to the other."""
    span = 2.0 * SLAB_HALF
    return -SLAB_HALF + (start + SLAB_HALF + speed * t) % span


def replica_npcs(t: float, shift: int = 0, ids_from: int | None = None) -> dict:
    """The `npcs` list at `t` seconds (unscaled): a runner (hostile) and a walker on the sidewalks, a car driving along the road and
    a second car that stands until WRECK_AT and is then gone from the list (V destroyed it: replica_killed comes just before)."""
    feet = SLAB_Y + 1.0
    runner, walker, car, wreck = replica_ids(ids_from)
    out = [
        {"id": runner, "p": [loop_x(0.0, -RUN_X, t) + shift, feet, -2.5], "yaw": 90.0, "kind": "human", "hostile": True},
        {"id": walker, "p": [loop_x(-6.0, WALK_X, t) + shift, feet, 2.5], "yaw": -90.0, "kind": "human", "hostile": False},
        {"id": car, "p": [loop_x(-8.0, CAR_X, t) + shift, feet, 0.5], "yaw": -90.0, "kind": "vehicle", "hostile": False},
    ]
    if t < WRECK_AT:
        out.append({"id": wreck, "p": [-5.5 + shift, feet, -0.5], "yaw": 0.0, "kind": "vehicle", "hostile": False})
    return {"t": "npcs", "list": out}


def replica_killed(ids_from: int | None = None) -> dict:
    """The `killed` of the standing car (sent at WRECK_AT - KILL_LEAD): V destroyed it, so the server leaves a wreck for 5 s."""
    return {"t": "killed", "target": replica_ids(ids_from)[3], "kind": "vehicle", "hostile": False}


def env_index(t: float) -> int:
    """Which step of ENV_CYCLE is playing at `t` seconds (the cycle repeats)."""
    at = t % sum(d for d, _, _ in ENV_CYCLE)
    for i, (d, _, _) in enumerate(ENV_CYCLE):
        if at < d:
            return i
        at -= d
    return len(ENV_CYCLE) - 1


def replica_env(t: float, hours: int = 0) -> dict:
    """The `env` at `t` seconds. `hours` (--env-hours) moves the clock by that many hours and makes the weather fog: a second host
    whose sky must never reach the server."""
    _, (h, m), weather = ENV_CYCLE[env_index(t)]
    return {"t": "env", "time": [(h + hours) % 24, m], "weather": ENV_SECOND_WEATHER if hours else weather}


def replica_cam(f: int, feet: Sequence[float]) -> dict:
    """V stands at `feet` facing east: a first-person `cam`."""
    x, y, z = feet
    return {"t": "cam", "f": f, "p": [x, y + EYE_HEIGHT, z], "r": [REPLICA_YAW, 0.0, 0.0], "fov": FOV, "fp": True, "pl": [x, y, z],
            "h": REPLICA_YAW, "cr": False, "air": False, "fly": False}


def teleport_answer(m: dict) -> dict | None:
    """The `vteleported ok` for a valid `vteleport` (None for a bad one, which the real DLL drops too)."""
    p, rid = m.get("p"), m.get("id")
    if not (isinstance(rid, int) and not isinstance(rid, bool) and rid >= 0 and isinstance(p, list) and len(p) == 3 and m.get("why") in TELEPORT_WHYS
            and all(isinstance(c, (int, float)) and not isinstance(c, bool) and math.isfinite(c) and abs(c) < 3e7 for c in p)):
        return None
    return {"t": "vteleported", "id": rid, "ok": True, "reason": "", "p": [float(c) for c in p]}


class Body:
    """Where V is in layer 4: falls from FALL_FROM at FALL_SPEED until Minecraft reports `flight`, then follows it (and
    says `fly:true`, as the real host's drive does); a touchdown (`flight` inactive) or an ender pearl's `teleport` puts V
    down at that spot. A `flight` sample's `kind` (glide or launch) and `ts` make no difference to the fake host."""

    def __init__(self, start_y: float = FALL_FROM) -> None:
        self.start_y = start_y
        self.mode, self.feet, self.yaw, self.pitch = "fall", [FALL_XZ[0], start_y, FALL_XZ[1]], 0.0, 0.0

    def update(self, m: dict) -> None:
        feet = m.get("p")
        if m.get("t") not in ("flight", "teleport") or not (isinstance(feet, list) and len(feet) == 3 and all(isinstance(c, (int, float)) for c in feet)):
            return
        self.mode = "flight" if m["t"] == "flight" and m.get("active") is True else "ground"
        self.feet = [float(c) for c in feet]
        self.yaw = m["yaw"] if isinstance(m.get("yaw"), (int, float)) else self.yaw
        self.pitch = m["pitch"] if isinstance(m.get("pitch"), (int, float)) else self.pitch

    def cam(self, f: int, t: float) -> dict:
        """The `cam` message for frame f, t seconds into the scenario."""
        x, y, z = self.feet
        if self.mode == "fall":
            y = max(FLOOR_Y, self.start_y - FALL_SPEED * t)
        return {"t": "cam", "f": f, "p": [x, y + EYE_HEIGHT, z], "r": [self.yaw, self.pitch, 0.0], "fov": FOV, "fp": True,
                "pl": [x, y, z], "h": self.yaw, "cr": False, "fly": self.mode == "flight"}


class Cyberpunk:
    """Cyberpunk's side of layers 2-6 once the orbit is over: the timed messages it sends by itself and its answers."""

    def __init__(self, layers: frozenset[int], modes: frozenset[str] = frozenset(), ids_from: int | None = None, name: str | None = None,
                 shift: int = 0, env_hours: int = 0) -> None:
        self.layers, self.modes, self.mobdmg_sent = layers, modes, False
        self.shift, self.env_hours = shift, env_hours  # --replica: the street's x shift and the clock shift of a second host
        self.replica = "replica" in modes
        self.arrived: list[float] | None = None  # --replica: where a `vteleport` put V (the camera follows)
        self.env_step = -1  # --replica: the last ENV_CYCLE step sent
        self.body = Body(GLIDE_FROM if "view" in modes else FALL_FROM)
        self.prefs = dict(PREFS_DEFAULT)  # Phase Q: what Minecraft's last `prefs` set (--view and --stairs place the camera by it)
        self.aim_at, self.aim_d = None, None  # --stairs: when (scenario seconds) the aim was last looked at, and the last `aim.d` sent
        self.ids_from, self.tag = ids_from, f"[{name}] " if name else ""  # --ids-from and the --name log prefix
        self.view_back = False  # --ui: Minecraft asked for the third-person view (`persp back`), so `cam.fp` is false
        self.delayed: list[tuple[float, dict]] = []  # (perf_counter due time, message): the `ping` and `frenzy` spreads of --hacks
        self.session: str | None = None  # the last `session` state Minecraft sent (a change to host re-sends the ground)

    def script(self) -> list[tuple[float, dict]]:
        """(seconds after the scenario starts, message), in time order: the layer 4 jump tap, the layer 6 keys and kill."""
        def key(k: str, down: bool) -> dict:
            return {"t": "key", "k": k, "down": down}

        events = []
        if 4 in self.layers:
            events += [(JUMP_AT, key("jump", True)), (JUMP_AT + JUMP_FOR, key("jump", False))]
        if 6 in self.layers:
            events += [(HACK_KEY[0], key("hack", True)), (HACK_KEY[1], key("hack", False)),
                       (SKILLS_KEY[0], key("skills", True)), (SKILLS_KEY[1], key("skills", False)),
                       (KILLED_AT, {"t": "killed", "target": hostile_human(self.ids_from)["id"], "kind": "human", "hostile": True})]
            for down, up in CYBERWARE_KEYS:  # U twice: the first press opens the Cyberware screen, the second closes it
                events += [(down, key("cyberware", True)), (up, key("cyberware", False))]
            events += [(ACTIVATE_KEY[0], key("activate", True)), (ACTIVATE_KEY[1], key("activate", False))]
        if "hacks" in self.modes:
            events += [(t, key(k, down)) for k, times in HACKS_KEYS.items() for t, down in zip(times, (True, False))]
        if "seat" in self.modes:
            events += [(0.0, {"t": "state", "active": True, "vehicle": True})]
        if "ui" in self.modes:
            events += ui_script()
        if "view" in self.modes:
            glide = WALK_FOR + SPRINT_FOR
            events += [(WALK_FOR, key("sprint", True)), (glide, key("sprint", False)),
                       (glide + JUMP_AT, key("jump", True)), (glide + JUMP_AT + JUMP_FOR, key("jump", False))]
        if self.replica:
            events += self.replica_script()
        return sorted(((t * SCRIPT_SCALE, m) for t, m in events), key=lambda e: e[0])

    def replica_script(self) -> list[tuple[float, dict]]:
        """--replica: V destroys the standing car (`killed`, just before it leaves `npcs`); the first scaffold is observed empty
        (`unground seen:true`), the second only leaves the radius (`unground`), then `clear` and the whole ground again without the
        first (the DLL starts over after a `clear`; the server keeps its replica)."""
        events = [(WRECK_AT - KILL_LEAD, replica_killed(self.ids_from)), (SEEN_AT, unground_message(*SCAFFOLD_SEEN, self.shift)), (LEFT_AT, unground_message(*SCAFFOLD_LEFT, self.shift, seen=False)),
                  (CLEAR_AT, {"t": "clear"})]
        events += [(CLEAR_AT + 0.01 * (i + 1), m) for i, m in enumerate(replica_ground(self.shift, without=[SCAFFOLD_SEEN, SCAFFOLD_LEFT]))]
        return events

    def npcs(self, t: float) -> dict:
        """The `npcs` list `t` seconds (scaled scenario time) into the scenario: the figures of --replica move, the others stand."""
        if self.replica:
            return replica_npcs(t / SCRIPT_SCALE, self.shift, self.ids_from)
        return npcs_message(self.ids_from)

    def setup_commands(self) -> list[str]:
        """The server commands the mode sends by itself at the start of the scenario: --stairs places its staircase."""
        return list(STAIRS_COMMANDS) if "stairs" in self.modes else []

    def view_cam(self, f: int, t: float) -> dict:
        """--view: V walks, sprints, then falls and glides (the body follows Minecraft's `flight`), seen from the third-person
        camera of Minecraft's `prefs`; `vdbg` rides on every `cam` while `prefs.debug` is true."""
        p, glide = self.prefs, (WALK_FOR + SPRINT_FOR) * SCRIPT_SCALE
        if t < glide:
            feet, yaw = walk_pose(t / SCRIPT_SCALE)
            pitch, fly = VIEW_PITCH, False
        else:
            body = self.body.cam(f, t - glide)
            feet, (yaw, pitch), fly = body["pl"], body["r"][:2], body["fly"]
        eye = third_person_eye(feet, yaw, pitch, p["dist"], p["shoulder"])
        cam = {"t": "cam", "f": f, "p": eye, "r": [yaw, pitch, 0.0], "fov": p["fov"] or FOV, "fp": False, "pl": feet, "h": yaw,
               "cr": False, "air": feet[1] > FLOOR_Y + 1e-6, "fly": fly}
        if p["debug"]:  # only while Minecraft's `/cyberblock debug view` is on (protocol.md "Phase Q": `prefs.debug`)
            cam["vdbg"] = view_vdbg(f, t / SCRIPT_SCALE, p["dist"])
        return cam

    def stairs_cam(self, f: int, t: float) -> dict:
        """--stairs: V walks up the staircase in first person looking STAIRS_PITCH degrees down (`persp back` makes `fp` false)."""
        feet = stairs_feet(t / SCRIPT_SCALE)
        yaw = cbmath.mc_yaw_pitch(1.0, 0.0, 0.0)[0]
        return {"t": "cam", "f": f, "p": [feet[0], feet[1] + EYE_HEIGHT, feet[2]], "r": [yaw, STAIRS_PITCH, 0.0], "fov": FOV,
                "fp": not self.view_back, "pl": feet, "h": yaw, "cr": False, "air": False, "fly": False}

    def frame_messages(self, t: float) -> list[dict]:
        """Messages sent alongside a frame's `cam`: --replica sends `env` when the clock or the weather steps; --stairs looks at the aim
        every AIM_EVERY s and sends `aim` when it changed by AIM_STEP."""
        if self.replica:
            step = env_index(t / SCRIPT_SCALE)
            if step == self.env_step:
                return []
            self.env_step = step
            return [replica_env(t / SCRIPT_SCALE, self.env_hours)]
        if "stairs" not in self.modes or (self.aim_at is not None and t - self.aim_at < AIM_EVERY * SCRIPT_SCALE - 1e-9):
            return []
        self.aim_at = t
        cam = self.stairs_cam(0, t)
        d = stairs_aim(cam["p"], cam["r"][0], cam["r"][1])
        if self.aim_d is not None and abs(d - self.aim_d) < AIM_STEP and (d < 0) == (self.aim_d < 0):
            return []
        self.aim_d = d
        return [{"t": "aim", "d": d}]

    def due(self, now: float) -> list[dict]:
        """The delayed messages (--hacks) whose time has come, oldest first; they are removed."""
        ready = sorted((d for d in self.delayed if d[0] <= now), key=lambda d: d[0])
        self.delayed = [d for d in self.delayed if d[0] > now]
        for _, m in ready:
            if m.get("t") == "vteleported" and m.get("ok") is True:  # V arrives: the camera is there from now on
                self.arrived = m["p"]
        return [m for _, m in ready]

    def cam(self, f: int, t: float, held: dict) -> dict:
        """The `cam` for frame f, t seconds into the scenario: V in a vehicle (--seat), V's body in layer 4, else the last
        orbit pose held (`fp:false` once Minecraft asked for the third-person view, `menu:true` while --ui shows a pass-through menu)."""
        if "seat" in self.modes:
            return seat_cam(f, t)
        if self.replica:
            return replica_cam(f, self.arrived or (REPLICA_FEET[0] + self.shift, REPLICA_FEET[1], REPLICA_FEET[2]))
        if "view" in self.modes:
            return self.view_cam(f, t)
        if "stairs" in self.modes:
            return self.stairs_cam(f, t)
        cam = self.body.cam(f, t) if 4 in self.layers else {**held, "f": f}
        if self.view_back:
            cam["fp"] = False
        if "ui" in self.modes:
            cam["menu"] = UI_MENU[0] * SCRIPT_SCALE <= t < UI_MENU[1] * SCRIPT_SCALE
        return cam

    def reply(self, m: dict) -> list[dict]:
        """What Cyberpunk answers to Minecraft's message `m` (only for the layers that were asked for)."""
        t, items, rid = m.get("t"), m.get("list"), m.get("id")
        if t == "session":
            was, self.session = self.session, m.get("state")
            self.env_step = -1  # a new server session needs the clock again
            if self.session == "host" and was != "host":  # a new server session: it needs the whole ground and the NPCs again
                ground = replica_ground(self.shift) if self.replica else [{"t": "ground", "c": ground_slab()}]
                return [{"t": "clear"}, *ground, *([self.npcs(0.0)] if self.layers or self.modes else [])]
            return []
        if self.replica and t == "vteleport" and (answer := teleport_answer(m)):
            self.delayed.append((time.perf_counter() + TELEPORT_DELAY * SCRIPT_SCALE, answer))  # `vteleported` after 2 s (due() lets V arrive)
            return []
        if self.replica and t == "park" and isinstance(m.get("on"), bool):
            return [{"t": "parked", "on": m["on"], "ok": True, "reason": ""}]
        if t == "persp" and m.get("mode") in ("first", "back"):
            self.view_back = m["mode"] == "back"
        if t == "prefs":
            self.prefs = clamp_prefs(m, self.prefs)
        if "view" in self.modes:
            self.body.update(m)
        if "ui" in self.modes and t == "uiact":
            return ui_replies(m)
        if "hacks" in self.modes and t == "hack" and isinstance(rid, int) and isinstance(m.get("target"), str) and m.get("hack") in HACK_UPLOAD:
            now, later, upload = hack_replies(m, self.ids_from)
            due = time.perf_counter() + upload * SCRIPT_SCALE
            self.delayed += [(due, d) for d in later]
            return now
        if 4 in self.layers:
            self.body.update(m)
            if t == "proj" and isinstance(items, list) and (pcells := pcells_message(items)):
                return [pcells]
        if 3 in self.layers and t == "mobs" and not self.mobdmg_sent and isinstance(items, list) and items:
            mob = items[0].get("id") if isinstance(items[0], dict) else None
            if isinstance(mob, int):
                self.mobdmg_sent = True
                return [{"t": "mobdmg", "id": mob, "frac": 0.5, "by": hostile_human(self.ids_from)["id"]}]
        if 6 in self.layers and isinstance(rid, int):
            if t == "scan":
                return [scaninfo_message(rid, self.ids_from)]
            if t == "hack":
                return [{"t": "hackresult", "id": rid, "ok": True, "reason": ""}]
        return []


def save_frame(frame: dict, out: Path, f: int) -> None:
    def over_background(rgba: np.ndarray) -> Image.Image:  # premultiplied alpha
        px = rgba.astype(np.float64)
        return Image.fromarray((px[..., :3] + BACKGROUND * (1 - px[..., 3:4] / 255.0)).clip(0, 255).astype(np.uint8))

    z = shm.linear_depth(frame)
    ramp = np.where(np.isfinite(z), 255 * (1 - np.clip(z / DEPTH_RANGE_M, 0, 1)), 0).astype(np.uint8)
    over_background(frame["color"]).save(out / f"frame_{f:03d}_color.png")
    Image.fromarray(ramp).save(out / f"frame_{f:03d}_depth.png")
    over_background(frame["overlay"]).save(out / f"frame_{f:03d}_overlay.png")


def printed_events(layers: frozenset[int], modes: frozenset[str] = frozenset()) -> set[str]:
    """The message types of Minecraft that are printed: layer 2's always, a layer 3-6 flag or a re-test 3 mode adds its own."""
    return LAYER2_EVENTS.union(*(LAYER_EVENTS.get(n, ()) for n in layers), *(MODE_EVENTS.get(m, ()) for m in modes))


async def listen(ws, host: Cyberpunk) -> None:
    """Print Minecraft's hello, every change of its status and the events of layer 2 and of the active layers; send
    `host`'s answers."""
    printed = printed_events(host.layers, host.modes)
    last = None
    async for raw in ws:
        m = json.loads(raw)
        if m.get("t") == "hello":
            print(f"{host.tag}hello:", m)
            if m.get("proto") != 1:
                print(f"{host.tag}warning: Minecraft speaks protocol {m.get('proto')}, this tool speaks 1", file=sys.stderr)
        elif m.get("t") == "status" and m != last:
            print(f"{host.tag}status:", m)
            last = m
        elif m.get("t") in printed:
            print(f"{host.tag}{m['t']}: {json.dumps(m)}")
        for reply in host.reply(m):
            await ws.send(json.dumps(reply))


async def scenario(ws, first_frame: int, cam: dict, fps: float, hold: float, commands: Sequence[str], host: Cyberpunk) -> None:
    """Cyberpunk's side of layers 2-6: the NPC list, the layer 2 bullet hit, the commands; then for `hold` seconds the
    camera (`host.cam`: `cam` held, or V's body in layer 4), the NPC list at 10 Hz and the layers' timed messages."""
    npcs = host.npcs(0.0)
    commands = [*host.setup_commands(), *commands]
    for m in (npcs, *([dmg_message()] if 2 in host.layers else []), *({"t": "cmd", "c": c} for c in commands)):
        await ws.send(json.dumps(m))
    label = ", ".join([*(f"layer {n}" for n in sorted(host.layers)), *(f"--{m}" for m in sorted(host.modes))])
    print(f"{host.tag}{label}: sent {len(npcs['list'])} npcs, {len(commands)} command(s)"
          f"{' and a bullet dmg' if 2 in host.layers else ''}; listening {hold:g} s for {', '.join(sorted(printed_events(host.layers, host.modes)))}")
    npcs_every = max(1, round(fps / NPC_HZ))
    timeline = host.script()
    t0 = time.perf_counter()
    for i in range(round(hold * fps)):
        t = i / fps
        if i and i % npcs_every == 0:
            await ws.send(json.dumps(host.npcs(t)))
        while timeline and timeline[0][0] <= t:
            await ws.send(json.dumps(timeline.pop(0)[1]))
        for m in host.due(time.perf_counter()):
            await ws.send(json.dumps(m))
        await ws.send(json.dumps(host.cam(first_frame + i, t, cam)))
        for m in host.frame_messages(t):
            await ws.send(json.dumps(m))
        await asyncio.sleep(max(0.0, t0 + (i + 1) / fps - time.perf_counter()))


async def run(url: str, frames: int, out: Path, fps: float = 60.0, shm_name: str = shm.NAME, setup: bool = True,
              hold: float | None = None, commands: Sequence[str] = (), layers: frozenset[int] = frozenset({2}),
              modes: frozenset[str] = frozenset(), name: str | None = None, ver: str | None = None, ids_from: int | None = None,
              wait_host: float | None = None, shift: int = 0, env_hours: int = 0) -> int:
    """Returns the number of frame sets saved. `hold` (seconds) runs the scripts of `layers` (2 to 6) and of the re-test 3
    `modes` (ui, hacks, seat) after the orbit. Multiplayer: `name` prefixes every printed line, `ver` is the DLL version in
    the `hello` (None: no `ver`, a DLL from before Phase 1), `ids_from` numbers the NPCs (None: the protocol.md example ids)."""
    out.mkdir(parents=True, exist_ok=True)
    npc_ids(ids_from)  # a bad --ids-from fails here, before anything is sent
    if "replica" in modes:
        replica_ids(ids_from)
    host = Cyberpunk(layers if hold is not None else frozenset(), modes if hold is not None else frozenset(), ids_from, name, shift, env_hours)
    async with connect(url, max_size=None) as ws:
        listener = asyncio.create_task(listen(ws, host))
        for m in (hello_message(ver), {"t": "view", "w": VIEW[0], "h": VIEW[1]},
                  {"t": "clear"}, {"t": "state", "active": True, "vehicle": False}, {"t": "hud", "hidden": False}):
            await ws.send(json.dumps(m))
        for command in SETUP_COMMANDS if setup else []:
            await ws.send(json.dumps({"t": "cmd", "c": command}))
        await ws.send(json.dumps({"t": "ground", "c": ground_slab()}))

        saved, pending = 0, False
        t0 = time.perf_counter()
        for f in range(1, frames + 1):
            await ws.send(json.dumps(orbit_cam(f, frames)))
            pending = pending or f % SAVE_EVERY == 0
            if pending and (frame := shm.read_latest(shm_name)):
                save_frame(frame, out, f)
                print(f"{host.tag}saved frame_{f:03d}: Minecraft frame {frame['pose']['mc_frame']} for host frame "
                      f"{frame['pose']['host_frame']} ({f - frame['pose']['host_frame']} behind), {frame['width']}x{frame['height']}")
                saved, pending = saved + 1, False
            await asyncio.sleep(max(0.0, t0 + f / fps - time.perf_counter()))
        for _ in range(100 if pending else 0):  # the last sample may not have been published yet
            if frame := shm.read_latest(shm_name):
                save_frame(frame, out, frames)
                saved += 1
                break
            await asyncio.sleep(0.01)
        if hold is not None:
            if wait_host is not None:  # the link opens at the title screen: the scenario waits for the world (`session host`)
                waited = time.perf_counter() + wait_host
                while host.session != "host" and time.perf_counter() < waited:
                    await asyncio.sleep(0.25)
                print(f"{host.tag}{'the client made this DLL the host' if host.session == 'host' else f'no session host within {wait_host:g} s'}; starting the scenario")
            await scenario(ws, frames + 1, orbit_cam(frames, frames), fps, hold, commands, host)
        listener.cancel()
    return saved


def main() -> int:
    ap = argparse.ArgumentParser(description="Fake Cyberpunk host: drives the Cyberblock Minecraft mod and dumps its frames to PNG.")
    ap.add_argument("--frames", type=int, default=120, help="camera messages to send, one orbit (default 120)")
    ap.add_argument("--out", type=Path, default=Path("out/fakehost"), help="where the PNGs go (default out/fakehost)")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"Minecraft's WebSocket port on 127.0.0.1 (default {DEFAULT_PORT})")
    ap.add_argument("--url", default=None, help="Minecraft's WebSocket (default ws://127.0.0.1:<--port>)")
    ap.add_argument("--fps", type=float, default=60.0, help="camera message rate (default 60)")
    ap.add_argument("--shm", default=shm.NAME, help=f"shared memory name (default {shm.NAME})")
    ap.add_argument("--no-setup", action="store_true", help="do not send the visible-block setup commands")
    ap.add_argument("--layer2", action="store_true", help="after the orbit: send 3 npcs and a bullet dmg, print Minecraft's block/explosion/hit/ignite/blocks/align")
    ap.add_argument("--layer3", action="store_true", help="after the orbit: send the npcs, answer the first mobs list with a mobdmg, print mobs")
    ap.add_argument("--layer4", action="store_true", help="after the orbit: V falls from y 200 with a jump tap, follows flight (glide or launch, with cam fly:true) and teleport, answers proj with pcells; print flight/teleport/proj")
    ap.add_argument("--layer5", action="store_true", help="after the orbit: print lead/pull/knock/effect (set the scene with --cmd)")
    ap.add_argument("--layer6", action="store_true", help="after the orbit: answer scan and hack, press the hack/skills keys, report a kill, press U (open, close) and tap Y; print perks/cyberware/cyberfx/perkfx/binds/scan/hack")
    ap.add_argument("--ui", action="store_true", help="after the orbit: play the Minecraft-drawn UI stream (stats, quest, pins, prompt, choices, subtitle, call, toasts, pause and pass-through screens), answer uiact requests; print uiact/persp/binds/status")
    ap.add_argument("--hacks", action="store_true", help="after the orbit: answer every hack with hackresult (upload, kind) and hackspread, tap the Arms and Camo keys; print hack/uiact/persp/binds")
    ap.add_argument("--seat", action="store_true", help="after the orbit: V rides in a vehicle (cam seat:true, pl at the hips, fp flipping every 2 s); print persp/binds")
    ap.add_argument("--view", action="store_true", help="after the orbit: third-person view (cam fp:false, with vdbg while prefs.debug is true): V walks, sprints, then falls and glides; the camera follows Minecraft's prefs; print prefs")
    ap.add_argument("--stairs", action="store_true", help="after the orbit: place a staircase of five blocks, walk V up it (step-up assist lifts), send aim at the block faces (box 2 mm in front); print prefs")
    ap.add_argument("--replica", action="store_true", help="after the orbit: the Phase 3 replica script: a street of ground with materials (`ground.m`), `unground seen`, walking and driving figures, `env` changes, `vteleport` answered after 2 s; print vteleport/park")
    ap.add_argument("--shift", type=int, default=None, metavar="DX", help="--replica: move the street, the figures and V DX blocks east (a second fake host that overlaps the first)")
    ap.add_argument("--env-hours", type=int, default=None, metavar="H", help="--replica: add H hours to the `env` clock and make the weather fog (a second host whose sky must never reach the server)")
    ap.add_argument("--hold", type=float, default=10.0, help="--layer2 .. --layer6, --ui, --hacks, --seat, --view, --stairs, --replica: seconds to keep the link up and listen (default 10)")
    ap.add_argument("--cmd", action="append", default=[], metavar="COMMAND", help='--layer2 .. --layer6, --ui, --hacks, --seat, --view, --stairs: a server command to send after the npcs, e.g. "summon tnt 0 64 0" (repeatable)')
    ap.add_argument("--name", default=None, help="a log prefix: every printed line starts with [NAME] (letters, digits, _ . -, at most 32), for two fake hosts at once")
    ap.add_argument("--ver", default=None, help=f"the DLL version in the hello (at most {MAX_VER} characters; default: this checkout's version; an empty value sends no ver, like a DLL from before Phase 1)")
    ap.add_argument("--ids-from", type=int, default=None, metavar="N", help="number the three NPCs N, N+1 and N+2 instead of the protocol.md example ids, to force an id collision between two fake hosts (1 <= N <= 2**64 - 3)")
    ap.add_argument("--wait-host", type=float, default=None, metavar="SECONDS", help="before the --hold scenario, wait up to this long for Minecraft's `session state:host` (the world is loaded), as tools/mpcheck.py's singleplayer scenario does")
    args = ap.parse_args()
    if args.wait_host is not None and args.wait_host < 0:
        ap.error("--wait-host must not be negative")
    if args.name is not None and not NAME_RE.fullmatch(args.name):
        ap.error("--name must be 1 to 32 letters, digits, '_', '.' or '-'")
    if args.ver is not None and len(args.ver) > MAX_VER:
        ap.error(f"--ver must be at most {MAX_VER} characters")
    if args.ids_from is not None and not 1 <= args.ids_from <= 2**64 - 3:
        ap.error("--ids-from must be between 1 and 2**64 - 3")
    ver = None if args.ver == "" else args.ver if args.ver is not None else package.version()
    tag = f"[{args.name}] " if args.name else ""
    layers = frozenset(n for n in range(2, 7) if getattr(args, f"layer{n}"))
    modes = frozenset(m for m in MODES if getattr(args, m))
    url = args.url or f"ws://127.0.0.1:{args.port}"
    if args.frames < 1 or args.fps <= 0:
        ap.error("--frames and --fps must be positive")
    if args.hold < 0:
        ap.error("--hold must not be negative")
    if args.cmd and not (layers or modes):
        ap.error("--cmd needs one of --layer2 .. --layer6, --ui, --hacks, --seat, --view, --stairs or --replica")
    if len(modes & set(CAMERA_OWNERS)) > 1 or (modes & {"view", "stairs", "replica"} and 4 in layers):
        ap.error("--view, --stairs, --seat, --replica and --layer4 each drive the camera: use only one of them")
    if (args.shift is not None or args.env_hours is not None) and "replica" not in modes:
        ap.error("--shift and --env-hours belong to --replica")
    if args.shift is not None and abs(args.shift) > 1000:
        ap.error("--shift must be between -1000 and 1000 blocks")
    if args.env_hours is not None and not -23 <= args.env_hours <= 23:
        ap.error("--env-hours must be between -23 and 23")
    if "replica" in modes and args.ids_from is not None and args.ids_from > 2**64 - 4:
        ap.error("--ids-from must be at most 2**64 - 4 with --replica (it numbers four figures)")
    try:
        saved = asyncio.run(run(url, args.frames, args.out, args.fps, args.shm, not args.no_setup,
                                args.hold if layers or modes else None, args.cmd, layers, modes, args.name, ver, args.ids_from,
                                **({"wait_host": args.wait_host} if args.wait_host is not None else {}),
                                **({"shift": args.shift} if args.shift else {}), **({"env_hours": args.env_hours} if args.env_hours else {})))
    except (ConnectionError, TimeoutError, ConnectionClosed) as e:
        print(f"{tag}no connection to Minecraft at {url} ({e}); start the dev client first:\n"
              '  mc\\gradlew.bat runClient --args="--quickPlaySingleplayer cyberblock"', file=sys.stderr)
        return 1
    print(f"{tag}saved {saved} frame set(s) to {args.out}")
    if not saved:
        print(f"{tag}no frames in the shared memory {args.shm}: is the mod exporting?", file=sys.stderr)
    return 0 if saved else 1


if __name__ == "__main__":
    sys.exit(main())
