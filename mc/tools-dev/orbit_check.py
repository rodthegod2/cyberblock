"""Frame oracle for the Cyberblock Minecraft mod: a minimal fake Cyberpunk that orbits the camera around a few blocks and
checks, then saves, the frames Minecraft exports. A local stand-in for tools/fakehost.py (track A) that asserts instead of
only dumping PNGs. Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough/host/fakehost.py, mcframe.py

Start the dev client first and wait for the world:
    mc\\gradlew.bat runClient --args="--quickPlaySingleplayer cyberblock"
then from the repo root:
    uv run python mc/tools-dev/orbit_check.py --out out/fakehost

Phase 1: 120 camera messages (one orbit, 60 Hz) around a gold block on a stone slab (placed with `cmd`, like tools/fakehost.py),
every 30th frame is saved as frame_XXX_{color,depth,overlay}.png and checked (header, pose echo, lag, colour/depth/overlay).
Phase 2: a third-person camera on Steve, once normally and once with state {vehicle:true}: Steve and the HUD must be gone.
Exit code 1 if any check fails.
"""
import argparse
import asyncio
import json
import math
import mmap
import struct
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image
from websockets.asyncio.client import connect

URL = "ws://127.0.0.1:25600"
NAME = "CyberblockFrame"  # Local\CyberblockFrame: Python's tagname is the part after "Local\"
MAGIC = 0x52464243  # "CBFR"
HEADER, SLOT_DESC, SLOT_DESC_BYTES = 4096, 256, 128
VIEW = (1280, 720)
CENTER = (0.0, 65.0, 0.0)
SLAB_Y = 63
SETUP = [
    f"fill -8 {SLAB_Y} -8 8 {SLAB_Y} 8 minecraft:stone",
    "fill -8 64 -8 8 70 8 minecraft:air",
    "setblock 0 64 0 minecraft:gold_block",
    "setblock 3 64 0 minecraft:red_concrete",
    "setblock -3 64 0 minecraft:blue_concrete",
    "setblock 0 64 3 minecraft:lime_concrete",
    "setblock 0 64 -3 minecraft:yellow_concrete",
]
FAILED = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))
    if not ok:
        FAILED.append(name)


class Frames:
    """Reads the frames Minecraft publishes in the shared memory (layout: FrameExporter's Javadoc)."""

    def __init__(self):
        probe = mmap.mmap(-1, HEADER, tagname=NAME, access=mmap.ACCESS_READ)
        magic, _, header, slots, stride = struct.unpack_from("<iiiiq", probe, 0)
        probe.close()
        if magic != MAGIC:
            raise RuntimeError("no frame export yet (is the mod running with a host attached?)")
        self.header, self.stride = header, stride
        self.mm = mmap.mmap(-1, header + stride * slots, tagname=NAME, access=mmap.ACCESS_READ)

    def latest(self):
        for _ in range(4):
            slot = struct.unpack_from("<i", self.mm, 40)[0]
            if slot < 0:
                return None
            d = SLOT_DESC + SLOT_DESC_BYTES * slot
            seq = struct.unpack_from("<q", self.mm, d)[0]
            if seq & 1:
                continue
            (mc_frame, host_frame, w, h, near, far, fov, flags, cx, cy, cz, yaw, pitch, roll, fp, _, _) = struct.unpack_from(
                "<qqiifffidddfffiqq", self.mm, d + 8)
            n = w * h * 4
            base = self.header + self.stride * slot
            buf = memoryview(self.mm)[base:base + 3 * n]
            world = np.frombuffer(buf, np.uint8, n, 0).reshape(h, w, 4).copy()
            depth = np.frombuffer(buf, np.float32, w * h, n).reshape(h, w).copy()
            over = np.frombuffer(buf, np.uint8, n, 2 * n).reshape(h, w, 4).copy()
            if struct.unpack_from("<q", self.mm, d)[0] != seq:
                continue
            if flags & 2:  # rows bottom-up
                world, depth, over = world[::-1], depth[::-1], over[::-1]
            return dict(mc_frame=mc_frame, host_frame=host_frame, w=w, h=h, near=near, far=far, fov=fov, flags=flags,
                        cam=(cx, cy, cz), rot=(yaw, pitch, roll), fp=bool(fp), color=world, depth=depth, overlay=over)
        return None


def basis_look(eye, target):
    d = np.subtract(target, eye)
    return math.degrees(math.atan2(-d[0], d[2])), math.degrees(-math.atan2(d[1], math.hypot(d[0], d[2])))


def png_color(rgba: np.ndarray, background=(40, 40, 48)) -> Image.Image:  # premultiplied alpha
    px = rgba.astype(np.float64)
    return Image.fromarray((px[..., :3] + np.array(background) * (1 - px[..., 3:4] / 255.0)).clip(0, 255).astype(np.uint8))


def save(frame: dict, out: Path, tag: str) -> None:
    depth = frame["depth"]
    flat = depth[depth > 0]  # reversed Z: 1 = near, 0 = nothing
    ramp = np.zeros(depth.shape, np.uint8)
    if flat.size:
        ramp = (255 * (depth - flat.min()) / max(1e-9, depth.max() - flat.min())).clip(0, 255).astype(np.uint8)
        ramp[depth <= 0] = 0
    png_color(frame["color"]).save(out / f"{tag}_color.png")
    Image.fromarray(ramp).save(out / f"{tag}_depth.png")
    png_color(frame["overlay"]).save(out / f"{tag}_overlay.png")


def orbit_cam(f: int, frames: int) -> dict:
    a = 2 * math.pi * (f - 1) / frames
    eye = (CENTER[0] + 6 * math.sin(a), 66.5, CENTER[2] + 6 * math.cos(a))
    yaw, pitch = basis_look(eye, (CENTER[0], 65.0, CENTER[2]))
    return {"t": "cam", "f": f, "p": list(eye), "r": [yaw, pitch, 0.0], "fov": 70.0, "fp": True, "pl": [eye[0], SLAB_Y + 1.0, eye[2]], "h": yaw}


async def main(out: Path, frames: int, wait_s: float) -> int:
    out.mkdir(parents=True, exist_ok=True)
    ws, end = None, time.monotonic() + wait_s
    while ws is None:
        try:
            ws = await connect(URL, max_size=None)
        except OSError:
            if time.monotonic() > end:
                print("no Minecraft on 25600: start the dev client first", file=sys.stderr)
                return 2
            await asyncio.sleep(1)
    seen = []

    async def listen():
        async for raw in ws:
            m = json.loads(raw)
            if m.get("t") in ("hello", "status") and (not seen or seen[-1] != m):
                seen.append(m)
                print(m["t"] + ":", m)
    task = asyncio.create_task(listen())

    async def send(**m):
        await ws.send(json.dumps(m))

    for m in ({"t": "hello", "proto": 1, "game": "cp2077", "build": "2.31"}, {"t": "view", "w": VIEW[0], "h": VIEW[1]},
              {"t": "clear"}, {"t": "state", "active": True, "vehicle": False}, {"t": "hud", "hidden": False}):
        await send(**m)
    for c in SETUP:
        await send(t="cmd", c=c)
    # a known ground slab as barriers one block below the stone slab: invisible, but it must not disturb anything
    await send(t="ground", c=[v for x in range(-8, 9) for z in range(-8, 9) for v in (x, z, SLAB_Y - 1, SLAB_Y - 1)])

    reader, sent, saved = None, {}, 0
    t0 = time.perf_counter()
    for f in range(1, frames + 1):
        cam = orbit_cam(f, frames)
        sent[f] = cam
        await send(**cam)
        if reader is None:
            try:
                reader = Frames()
            except (OSError, RuntimeError):
                pass
        if reader is not None and f % 30 == 0:
            fr = reader.latest()
            if fr is not None:
                saved += 1
                tag = f"frame_{f:03d}"
                save(fr, out, tag)
                s = sent.get(fr["host_frame"])
                lag = f - fr["host_frame"]
                alpha = fr["color"][..., 3] > 0
                over_alpha = fr["overlay"][..., 3] > 0
                depth = fr["depth"]
                check(f"{tag} size {VIEW[0]}x{VIEW[1]}", (fr["w"], fr["h"]) == VIEW, f"{fr['w']}x{fr['h']} flags {fr['flags']}")
                check(f"{tag} pose echoed for host frame {fr['host_frame']}", s is not None and np.allclose(fr["cam"], s["p"], atol=1e-3)
                      and abs(fr["rot"][0] - s["r"][0]) < 0.01 and abs(fr["rot"][1] - s["r"][1]) < 0.01 and abs(fr["fov"] - 70.0) < 0.01)
                check(f"{tag} lag at most 5 frames", 0 <= lag <= 5, f"{lag} behind")
                check(f"{tag} world colour drawn", 0.2 < alpha.mean() < 0.99, f"{alpha.mean():.3f} covered")
                check(f"{tag} depth is a gradient", np.unique(depth[alpha]).size > 500 and (depth[~alpha] <= 0).all(),
                      f"{np.unique(depth[alpha]).size} distinct values")
                check(f"{tag} overlay has the hotbar", 0.001 < over_alpha.mean() < 0.6 and over_alpha[-80:].any(), f"{over_alpha.mean():.4f} covered")
        await asyncio.sleep(max(0.0, t0 + f / 60.0 - time.perf_counter()))
    check("frames were saved", saved >= 3, f"{saved} sets in {out}")

    # phase 2: third person on Steve; then in a vehicle he and the HUD are gone
    feet = (0.0, 64.0, 0.0)
    eye = (feet[0], feet[1] + 3.4, feet[2] - 4.0)
    yaw, pitch = basis_look(eye, (feet[0], feet[1] + 1.2, feet[2]))
    tp = {"t": "cam", "p": list(eye), "r": [yaw, pitch, 0.0], "fov": 70.0, "fp": False, "pl": list(feet), "h": yaw}

    async def settle(n_frames: int, base: int):
        for i in range(n_frames):
            await send(**tp, f=base + i)
            await asyncio.sleep(1 / 60)
        end = time.monotonic() + 3
        while time.monotonic() < end:
            fr = reader.latest()
            if fr and fr["host_frame"] >= base + n_frames - 10:
                return fr
            await send(**tp, f=base + n_frames)
            await asyncio.sleep(1 / 60)
        return reader.latest()

    await send(t="cmd", c="fill -8 64 -8 8 70 8 minecraft:air")
    walking = await settle(90, 1000)
    await send(t="state", active=True, vehicle=True)
    driving = await settle(90, 2000)
    if reader is None or walking is None or driving is None:
        check("third-person frames", False, "no frames")
    else:
        save(walking, out, "steve_walking")
        save(driving, out, "steve_in_vehicle")
        steve = (walking["color"][..., 3] != driving["color"][..., 3]).sum() + (np.abs(walking["color"].astype(int) - driving["color"].astype(int)).sum(-1) > 30).sum()
        check("Steve is drawn on foot and gone in a vehicle", steve > 3000, f"{steve} pixels differ")
        check("HUD is drawn on foot and gone in a vehicle", (walking["overlay"][..., 3] > 0).sum() > 2000 and (driving["overlay"][..., 3] > 0).sum() == 0,
              f"{(walking['overlay'][..., 3] > 0).sum()} -> {(driving['overlay'][..., 3] > 0).sum()} overlay pixels")
    await send(t="state", active=False, vehicle=False)
    await send(t="clear")
    task.cancel()
    await ws.close()
    print("FAILED: " + ", ".join(FAILED) if FAILED else "all checks passed")
    return 1 if FAILED else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", type=Path, default=Path("out/fakehost"), help="where the PNGs go (default out/fakehost)")
    ap.add_argument("--frames", type=int, default=120, help="camera messages in the orbit (default 120)")
    ap.add_argument("--wait", type=float, default=90.0, help="seconds to wait for Minecraft (default 90)")
    args = ap.parse_args()
    sys.exit(asyncio.run(main(args.out, args.frames, args.wait)))
