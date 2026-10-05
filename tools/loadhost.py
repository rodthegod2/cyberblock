"""A load-test stand-in for one Cyberpunk host (megaplan task 9-F1): it streams what a busy Night City street sends, as fast as the
server's relay guard lets a real DLL, and measures the frame export of the Minecraft client it drives.

    uv run python -m tools.loadhost --port <client link port> --name CbHost1 --shm "Local\\CyberblockFrame9FH" --seconds 120

Once the client makes it the host (`session state:host`), V walks east along a street at WALK m/s and the link carries:
  ground    every column of the city within RADIUS m of V (a road, sidewalks, buildings on 8 m lots, 2 to 24 blocks high), the first
            area at once and then the columns V walks into, paced under the guard's budget (CELLS_PER_SECOND cells and MSGS_PER_SECOND
            messages a second), with `m` material codes; `unground` (not seen: they only left the radius) for the columns left behind
  npcs      48 figures around V at 10 Hz (40 walking people with the Phase 5 tags, 8 driving cars), the protocol's maximum
  devices   the 64 devices nearest V along the sidewalks (doors, lights, cameras, vending machines) every 5 s, and DEVSTATE_HZ `devstate`
            changes a second (an NPC opening a door, a light switching)
  cam       60 Hz, V's eye in first person
It sends `view` like the DLL: the size given by --view times Minecraft's last `prefs.scale` (megaplan 9-F1, 0.5..1, default 1). Every
REPORT_S seconds and at the end it prints `[NAME] load: {...}`: what it sent and the frame export it saw in the shared memory (frames a
second, and the time from capture to publish of the newest frame, sampled at 60 Hz).
"""
import argparse
import asyncio
import contextlib
import json
import math
import statistics
import sys
import time

from websockets.asyncio.client import connect

from tools import fakehost, package, shm

RADIUS = 48  # m: the DLL streams the ground and the figures within this of V
WALK = 4.0  # m/s east
FLOOR_Y = 63
ROAD_HALF, SIDEWALK_HALF, LOT = 4, 6, 8  # |dz| <= 4 road, 5..6 sidewalk, then 8 m building lots with a 1 m alley
MAX_STOREYS = 24
ROAD, SIDEWALK, ALLEY = 3, 4, 9  # material codes (shared/protocol.md "Phase 3": material_palette codes)
BUILDING_CODES = (1, 2, 7, 8)
QUADS_PER_MESSAGE = 256  # ground/unground messages are split under the UP cap (30000 bytes); 256 quadruples is about 6 KB
CELLS_PER_SECOND, MSGS_PER_SECOND = 18_000, 30  # under the relay guard's 20000 cells/s and 40 ground messages/s (no drops)
NPC_HZ, PEOPLE, CARS = 10, 40, 8
DEVICE_COUNT, DEVICE_EVERY, DEVICE_SPACING, DEVSTATE_HZ = 64, 5.0, 3, 4
DEVICE_KINDS = (("Door", "door"), ("ElectricLight", "light"), ("SurveillanceCamera", "camera"), ("VendingMachine", "vending"))
NPC_ID_BASE, DEVICE_ID_BASE = 7_000_000_000_000_000_000, 12_000_000_000_000_000_000
FPS, EYE = 60.0, 1.62
VIEW = (1920, 1080)
REPORT_S = 10.0


def mix(*values: int) -> int:
    """A small deterministic hash of whole numbers (the city looks the same on every run)."""
    h = 0x9E3779B1
    for v in values:
        h = (h ^ (v & 0xFFFFFFFF)) * 0x85EBCA6B & 0xFFFFFFFF
        h ^= h >> 13
    return h


def city_column(x: int, dz: int) -> tuple[int, int, int]:
    """(yBottom, yTop, material code) of the column at x, `dz` blocks from the street's centre line."""
    a = abs(dz)
    if a <= ROAD_HALF:
        return FLOOR_Y, FLOOR_Y, ROAD
    if a <= SIDEWALK_HALF:
        return FLOOR_Y, FLOOR_Y, SIDEWALK
    lx, lz = x // LOT, (a - SIDEWALK_HALF - 1) // LOT
    if x % LOT == LOT - 1 or (a - SIDEWALK_HALF - 1) % LOT == LOT - 1:
        return FLOOR_Y, FLOOR_Y, ALLEY
    lot = mix(lx, lz, 1 if dz > 0 else 0)
    return FLOOR_Y, FLOOR_Y + 2 + lot % (MAX_STOREYS - 1), BUILDING_CODES[(lot >> 8) % len(BUILDING_CODES)]


def area(vx: float, street_z: int, radius: int = RADIUS) -> set[tuple[int, int]]:
    """The columns (x, z) within `radius` m of V at (vx, street_z)."""
    cx = math.floor(vx)
    return {(x, z) for x in range(cx - radius, cx + radius + 1) for z in range(street_z - radius, street_z + radius + 1)
            if (x + 0.5 - vx) ** 2 + (z - street_z) ** 2 <= radius * radius}


def quads(columns, street_z: int) -> list[tuple[int, int, int, int, int]]:
    """(x, z, yBottom, yTop, code) of the columns, west to east."""
    return [(x, z, *city_column(x, z - street_z)) for x, z in sorted(columns)]


def cells_of(q: tuple) -> int:
    return q[3] - q[2] + 1


def messages(kind: str, qs: list[tuple]) -> list[dict]:
    """`ground` (with `m`) or `unground` messages of QUADS_PER_MESSAGE quadruples each."""
    out = []
    for i in range(0, len(qs), QUADS_PER_MESSAGE):
        chunk = qs[i:i + QUADS_PER_MESSAGE]
        m = {"t": kind, "c": [v for q in chunk for v in q[:4]]}
        if kind == "ground":
            m["m"] = [q[4] for q in chunk]
        out.append(m)
    return out


class Pacer:
    """Ground and unground messages in order, sent no faster than the guard allows (cells and messages a second, with a 1 s burst)."""

    def __init__(self, cells_per_s: float = CELLS_PER_SECOND, msgs_per_s: float = MSGS_PER_SECOND) -> None:
        self.queue: list[dict] = []
        self.cells_per_s, self.msgs_per_s = cells_per_s, msgs_per_s
        self.cells, self.msgs, self.at = cells_per_s, msgs_per_s, None

    def due(self, now: float) -> list[dict]:
        if self.at is not None:
            dt = now - self.at
            self.cells = min(self.cells_per_s, self.cells + dt * self.cells_per_s)
            self.msgs = min(self.msgs_per_s, self.msgs + dt * self.msgs_per_s)
        self.at = now
        out = []
        while self.queue:
            n = sum(c[3] - c[2] + 1 for c in zip(*[iter(self.queue[0]["c"])] * 4))
            if self.msgs < 1 or self.cells < n:
                break
            self.cells -= n
            self.msgs -= 1
            out.append(self.queue.pop(0))
        return out


class Street:
    """One host's busy street: what V's walk streams, and when."""

    def __init__(self, street_z: int = 0, ids_from: int = NPC_ID_BASE, x0: float = 0.0) -> None:
        self.z, self.ids_from, self.x0 = street_z, ids_from, x0
        self.sent: set[tuple[int, int]] = set()
        self.column: int | None = None
        self.pacer = Pacer()
        self.cells_sent = self.ground_msgs = self.npcs_sent = self.devstates = 0

    def vx(self, t: float) -> float:
        return self.x0 + WALK * t

    def restart(self) -> list[dict]:
        """A new host session: `clear`, and the whole area again (the DLL starts over)."""
        self.sent.clear()
        self.column = None
        self.pacer.queue.clear()
        return [{"t": "clear"}]

    def walk(self, t: float) -> None:
        """Queue the ground V walked into and the unground of what it left (once per metre walked)."""
        column = math.floor(self.vx(t))
        if self.sent and column == self.column:
            return
        self.column = column
        now = area(column + 0.5, self.z)
        new, left = now - self.sent, self.sent - now
        self.pacer.queue += messages("ground", quads(new, self.z)) + messages("unground", quads(left, self.z))
        self.sent = now

    def ground_due(self, now: float) -> list[dict]:
        out = self.pacer.due(now)
        for m in out:
            if m["t"] == "ground":
                self.ground_msgs += 1
                self.cells_sent += sum(c[3] - c[2] + 1 for c in zip(*[iter(m["c"])] * 4))
        return out

    def cam(self, f: int, t: float) -> dict:
        x, y, z = self.vx(t), FLOOR_Y + 1.0, self.z + 0.5
        return {"t": "cam", "f": f, "p": [x, y + EYE, z], "r": [-90.0, 0.0, 0.0], "fov": fakehost.FOV, "fp": True, "pl": [x, y, z], "h": -90.0,
                "cr": False, "air": False, "fly": False}

    def npcs(self, t: float) -> dict:
        """48 figures around V: people walking on both sidewalks, cars driving both ways on the road."""
        vx, feet, out = self.vx(t), FLOOR_Y + 1.0, []
        for i in range(PEOPLE):
            side = 1 if i % 2 else -1
            x = vx - RADIUS + 4 + (i * 2.1 + 1.4 * t * side) % (2 * RADIUS - 8)
            out.append({"id": str(self.ids_from + i), "p": [x, feet, self.z + side * 5.5], "yaw": -90.0 if side > 0 else 90.0, "kind": "human",
                        "hostile": i % 10 == 0, "fac": "Civilian" if i % 10 else "Maelstrom", "arch": "Civilian" if i % 10 else "Gunner",
                        "g": 1 + i % 2, "anim": 1 | (128 if i % 10 == 0 else 0), "hp": 1.0})
        for i in range(CARS):
            side = 1 if i % 2 else -1
            x = vx - RADIUS + 4 + (i * 11.0 + 8.0 * t * side) % (2 * RADIUS - 8)
            out.append({"id": str(self.ids_from + PEOPLE + i), "p": [x, feet, self.z + side * 2.0], "yaw": -90.0 if side > 0 else 90.0,
                        "kind": "vehicle", "hostile": False, "fac": "Civilian", "g": 0, "anim": 0, "vc": "Car/Turbo", "hp": 1.0})
        self.npcs_sent += 1
        return {"t": "npcs", "list": out}

    def devices(self, t: float) -> dict:
        """The DEVICE_COUNT devices nearest V: one every DEVICE_SPACING m on each sidewalk's outer edge."""
        k0 = math.floor(self.vx(t) / DEVICE_SPACING)
        out = []
        for k in range(k0 - DEVICE_COUNT // 4, k0 + DEVICE_COUNT // 4):
            for side in (-1, 1):
                cls, kind = DEVICE_KINDS[mix(k, side) % len(DEVICE_KINDS)]
                out.append({"id": str(DEVICE_ID_BASE + 2 * (k + 1_000_000) + (side > 0)), "cls": cls, "kind": kind,
                            "p": [k * DEVICE_SPACING + 0.5, FLOOR_Y + 1.0, self.z + side * SIDEWALK_HALF + 0.5], "yaw": 0.0 if side > 0 else 180.0,
                            "st": mix(k, side, 7) % 2, "fl": 16})
        return {"t": "devices", "list": out}

    def devstate(self, t: float, n: int) -> dict:
        """The n-th change: one device of the current list switches (a door opens or closes, a light goes on or off)."""
        d = self.devices(t)["list"][mix(n) % DEVICE_COUNT]
        self.devstates += 1
        return {"t": "devstate", "id": d["id"], "st": n % 2, "fl": 16, "by": "npc"}


class ExportProbe:
    """The client's frame export as the shared memory shows it: frames a second and capture-to-publish milliseconds."""

    def __init__(self, name: str) -> None:
        self.name, self.first, self.last, self.seen = name, None, None, -1
        self.latency_ms: list[float] = []
        self.size = (0, 0)

    def poll(self, now: float) -> None:
        timing = shm.read_timing(self.name)
        if timing is None:
            return
        if self.first is None:
            self.first = (now, timing["published"])
        self.last = (now, timing["published"])
        if timing["published"] != self.seen and timing["publish_ns"] > timing["capture_ns"] > 0:
            self.seen = timing["published"]
            self.latency_ms.append((timing["publish_ns"] - timing["capture_ns"]) / 1e6)
            self.size = (timing["width"], timing["height"])

    def summary(self) -> dict:
        """Since the last summary: frames a second, the capture-to-publish time (avg, p95) and the frame size; then it starts over."""
        out = {"w": self.size[0], "h": self.size[1], "fps": 0.0, "latency_ms_avg": 0.0, "latency_ms_p95": 0.0}
        if self.first and self.last and self.last[0] > self.first[0]:
            out["fps"] = round((self.last[1] - self.first[1]) / (self.last[0] - self.first[0]), 1)
        if self.latency_ms:
            ordered = sorted(self.latency_ms)
            out["latency_ms_avg"] = round(statistics.fmean(ordered), 2)
            out["latency_ms_p95"] = round(ordered[min(len(ordered) - 1, math.ceil(len(ordered) * 0.95) - 1)], 2)
        self.first, self.latency_ms = self.last, []
        return out


def scaled_view(view: tuple[int, int], scale: float) -> tuple[int, int]:
    """The DLL's rule (megaplan 9-F1, compositor::frame_size): each side times the scale, rounded half up, at least 1."""
    return tuple(max(1, math.floor(v * scale + 0.5)) for v in view)


def prefs_scale(m: dict, current: float) -> float:
    """The DLL's rule for `prefs.scale`: a finite number is clamped into 0.5..1; anything else keeps the current scale."""
    v = m.get("scale")
    if isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v):
        return min(max(float(v), 0.5), 1.0)
    return current


async def run(port: int, name: str, frame: str, seconds: float, street: Street, view: tuple[int, int], ver: str) -> dict:
    tag, state = f"[{name}] ", {"session": None, "scale": 1.0, "view": None, "started": None}

    async with connect(f"ws://127.0.0.1:{port}", max_size=None) as ws:
        async def send(m: dict) -> None:
            await ws.send(json.dumps(m))

        async def send_view() -> None:
            size = scaled_view(view, state["scale"])
            if size != state["view"]:
                state["view"] = size
                await send({"t": "view", "w": size[0], "h": size[1]})
                print(f"{tag}view {size[0]}x{size[1]} (scale {state['scale']:g})")

        async def listen() -> None:
            async for raw in ws:
                m = json.loads(raw)
                if m.get("t") == "session":
                    was, state["session"] = state["session"], m.get("state")
                    print(f"{tag}session: {state['session']}")
                    if state["session"] == "host" and was != "host":
                        state["started"] = time.perf_counter()
                        for out in street.restart():
                            await send(out)
                elif m.get("t") == "prefs":
                    state["scale"] = prefs_scale(m, state["scale"])
                    await send_view()

        listener = asyncio.create_task(listen())
        for m in (fakehost.hello_message(ver), {"t": "state", "active": True, "vehicle": False}, {"t": "hud", "hidden": False}):
            await send(m)
        await send_view()
        probe, report = ExportProbe(frame), {}
        t0 = time.perf_counter()
        next_report, f, changes = t0 + REPORT_S, 0, 0
        last_npcs = last_devices = last_devstate = -1e9
        while time.perf_counter() - t0 < seconds:
            f += 1
            now = time.perf_counter()
            started = state["started"]
            t = now - started if started is not None else 0.0
            await send(street.cam(f, t))
            if started is not None:
                street.walk(t)
                for m in street.ground_due(now):
                    await send(m)
                if now - last_npcs >= 1.0 / NPC_HZ:
                    last_npcs = now
                    await send(street.npcs(t))
                if now - last_devices >= DEVICE_EVERY:
                    last_devices = now
                    await send(street.devices(t))
                if now - last_devstate >= 1.0 / DEVSTATE_HZ:
                    last_devstate, changes = now, changes + 1
                    await send(street.devstate(t, changes))
            probe.poll(now)
            if now >= next_report:
                next_report += REPORT_S
                report = {"t": round(now - t0, 1), "host_s": round(t, 1), "export": probe.summary(), "ground_cells": street.cells_sent,
                          "ground_msgs": street.ground_msgs, "queued_msgs": len(street.pacer.queue), "npcs": street.npcs_sent, "devstate": street.devstates}
                print(f"{tag}load: {json.dumps(report)}")
            await asyncio.sleep(max(0.0, t0 + f / FPS - time.perf_counter()))
        listener.cancel()
    return report


def main(argv: list[str] | None = None) -> int:
    with contextlib.suppress(AttributeError, ValueError):
        sys.stdout.reconfigure(line_buffering=True)
    ap = argparse.ArgumentParser(description="Load-test stand-in for one Cyberpunk host (megaplan 9-F1).")
    ap.add_argument("--port", type=int, required=True, help="the Minecraft client's link port (never 25600)")
    ap.add_argument("--name", default="host", help="log prefix")
    ap.add_argument("--shm", required=True, help="the client's frame name (Local\\CyberblockFrame<...>; never the real game's)")
    ap.add_argument("--seconds", type=float, default=120.0)
    ap.add_argument("--street-z", type=int, default=0, help="z of this host's street (a second host walks its own street)")
    ap.add_argument("--ids-from", type=int, default=NPC_ID_BASE)
    ap.add_argument("--view", default=f"{VIEW[0]}x{VIEW[1]}", help="the host's picture size before prefs.scale (default 1920x1080)")
    args = ap.parse_args(argv)
    if args.port == fakehost.DEFAULT_PORT or args.shm == shm.NAME:
        ap.error("never the real game's link port or frame name")
    w, _, h = args.view.partition("x")
    if not (w.isdigit() and h.isdigit() and 0 < int(w) <= 3840 and 0 < int(h) <= 2160):
        ap.error("--view is WxH, at most 3840x2160")
    asyncio.run(run(args.port, args.name, args.shm, args.seconds, Street(args.street_z, args.ids_from), (int(w), int(h)), package.version()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
