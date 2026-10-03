"""A stand-in for Cyberpunk: drives Minecraft's camera and gives it a known ground, then dumps the frames Minecraft
exports, to check the mod before the real host exists. Based on universal-modder (MIT) by Rehan:
examples/minecraft-gta5-passthrough/host/fakehost.py

Start the Minecraft dev client first (mc\\gradlew.bat runClient --args="--quickPlaySingleplayer cyberblock"), then:
    uv run python -m tools.fakehost --frames 120 --out out/fakehost

It sends `hello`, `view`, a 17 x 17 stone-slab `ground` (x, z in [-8, 8], y 63; on top of it the slab is made visible
with reference-mod `cmd` server commands, which are not part of protocol v1) and one orbit of the camera around
(0, 65, 0) at 60 Hz. Every 30 frames it saves the latest exported frame as out/frame_XXX_{color,depth,overlay}.png:
colour and overlay composited over dark grey, depth as a grey ramp (white = near, 32 m = black, nothing = black).
"""
import argparse
import asyncio
import json
import math
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

from tools import cbmath, shm

URL = "ws://127.0.0.1:25600"
VIEW = (1280, 720)
CENTER = (0.0, 65.0, 0.0)
RADIUS, EYE_Y, FOV = 6.0, 66.5, 70.0
SLAB_HALF, SLAB_Y = 8, 63
SAVE_EVERY = 30
DEPTH_RANGE_M = 32.0
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
            "pl": [eye[0], SLAB_Y + 1.0, eye[2]], "h": yaw}


def save_frame(frame: dict, out: Path, f: int) -> None:
    def over_background(rgba: np.ndarray) -> Image.Image:  # premultiplied alpha
        px = rgba.astype(np.float64)
        return Image.fromarray((px[..., :3] + BACKGROUND * (1 - px[..., 3:4] / 255.0)).clip(0, 255).astype(np.uint8))

    z = shm.linear_depth(frame)
    ramp = np.where(np.isfinite(z), 255 * (1 - np.clip(z / DEPTH_RANGE_M, 0, 1)), 0).astype(np.uint8)
    over_background(frame["color"]).save(out / f"frame_{f:03d}_color.png")
    Image.fromarray(ramp).save(out / f"frame_{f:03d}_depth.png")
    over_background(frame["overlay"]).save(out / f"frame_{f:03d}_overlay.png")


async def listen(ws) -> None:
    """Print Minecraft's hello and every change of its status."""
    last = None
    async for raw in ws:
        m = json.loads(raw)
        if m.get("t") == "hello":
            print("hello:", m)
            if m.get("proto") != 1:
                print(f"warning: Minecraft speaks protocol {m.get('proto')}, this tool speaks 1", file=sys.stderr)
        elif m.get("t") == "status" and m != last:
            print("status:", m)
            last = m


async def run(url: str, frames: int, out: Path, fps: float = 60.0, shm_name: str = shm.NAME, setup: bool = True) -> int:
    """Returns the number of frame sets saved."""
    out.mkdir(parents=True, exist_ok=True)
    async with connect(url, max_size=None) as ws:
        listener = asyncio.create_task(listen(ws))
        for m in ({"t": "hello", "proto": 1, "game": "cp2077", "build": "2.31"}, {"t": "view", "w": VIEW[0], "h": VIEW[1]},
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
                print(f"saved frame_{f:03d}: Minecraft frame {frame['pose']['mc_frame']} for host frame "
                      f"{frame['pose']['host_frame']} ({f - frame['pose']['host_frame']} behind), {frame['width']}x{frame['height']}")
                saved, pending = saved + 1, False
            await asyncio.sleep(max(0.0, t0 + f / fps - time.perf_counter()))
        for _ in range(100 if pending else 0):  # the last sample may not have been published yet
            if frame := shm.read_latest(shm_name):
                save_frame(frame, out, frames)
                saved += 1
                break
            await asyncio.sleep(0.01)
        listener.cancel()
    return saved


def main() -> int:
    ap = argparse.ArgumentParser(description="Fake Cyberpunk host: drives the Cyberblock Minecraft mod and dumps its frames to PNG.")
    ap.add_argument("--frames", type=int, default=120, help="camera messages to send, one orbit (default 120)")
    ap.add_argument("--out", type=Path, default=Path("out/fakehost"), help="where the PNGs go (default out/fakehost)")
    ap.add_argument("--url", default=URL, help=f"Minecraft's WebSocket (default {URL})")
    ap.add_argument("--fps", type=float, default=60.0, help="camera message rate (default 60)")
    ap.add_argument("--shm", default=shm.NAME, help=f"shared memory name (default {shm.NAME})")
    ap.add_argument("--no-setup", action="store_true", help="do not send the visible-block setup commands")
    args = ap.parse_args()
    if args.frames < 1 or args.fps <= 0:
        ap.error("--frames and --fps must be positive")
    try:
        saved = asyncio.run(run(args.url, args.frames, args.out, args.fps, args.shm, not args.no_setup))
    except (ConnectionError, TimeoutError, ConnectionClosed) as e:
        print(f"no connection to Minecraft at {args.url} ({e}); start the dev client first:\n"
              '  mc\\gradlew.bat runClient --args="--quickPlaySingleplayer cyberblock"', file=sys.stderr)
        return 1
    print(f"saved {saved} frame set(s) to {args.out}")
    if not saved:
        print(f"no frames in the shared memory {args.shm}: is the mod exporting?", file=sys.stderr)
    return 0 if saved else 1


if __name__ == "__main__":
    sys.exit(main())
