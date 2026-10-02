"""Protocol check for the Cyberblock Minecraft mod: a minimal fake Cyberpunk that talks to the running dev client.

Start the dev client first (it opens or creates the "cyberblock" world, so wait until the world is loaded):
    mc\\gradlew.bat runClient --args="--quickPlaySingleplayer cyberblock"
then, from the repo root:
    uv run python mc/tools-dev/link_check.py --log mc/run/logs/latest.log

Checks hello, status, protocol mismatch, browser origins, bad input, ground/unground (read back with `say` through the
game log, so --log is needed for those) and the screen keys. Prints PASS/FAIL per check; exit code 1 on any FAIL.
This is a dev aid inside mc/, separate from tools/fakehost.py (the frame oracle).
"""
import argparse
import asyncio
import json
import sys
import time
import uuid
from pathlib import Path

from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

URL = "ws://127.0.0.1:25600"
FAILED = []


def report(name: str, ok: bool, detail: str = "") -> None:
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))
    if not ok:
        FAILED.append(name)


class Link:
    """One connection; keeps the newest status and every message in a list."""

    def __init__(self, ws):
        self.ws = ws
        self.messages: list[dict] = []
        self.status: dict | None = None
        self.status_times: list[float] = []
        self.reader = asyncio.create_task(self._read())

    async def _read(self):
        try:
            async for raw in self.ws:
                m = json.loads(raw)
                self.messages.append(m)
                if m.get("t") == "status":
                    self.status = m
                    self.status_times.append(time.monotonic())
        except ConnectionClosed:
            pass

    async def send(self, **m) -> None:
        await self.ws.send(json.dumps(m))

    async def wait(self, cond, timeout: float):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if (r := cond()):
                return r
            await asyncio.sleep(0.02)
        return None


class Game:
    """`say` through the server log tells us what blocks are where."""

    def __init__(self, link: Link, log: Path | None):
        self.link, self.log, self.nonce = link, log, uuid.uuid4().hex[:8]

    async def block_is(self, x: int, y: int, z: int, block: str, label: str) -> bool | None:
        if self.log is None:
            return None
        tag = f"CBCHK-{self.nonce}-{label}"
        await self.link.send(t="cmd", c=f"execute if block {x} {y} {z} minecraft:{block} run say {tag} yes")
        await self.link.send(t="cmd", c=f"execute unless block {x} {y} {z} minecraft:{block} run say {tag} no")
        end = time.monotonic() + 5.0
        while time.monotonic() < end:
            text = self.log.read_text(encoding="utf-8", errors="replace")
            if f"{tag} yes" in text:
                return True
            if f"{tag} no" in text:
                return False
            await asyncio.sleep(0.1)
        return None


async def main(log: Path | None, wait_s: float) -> int:
    print(f"connecting to {URL} (waiting up to {wait_s:.0f} s for Minecraft)")
    ws = None
    end = time.monotonic() + wait_s
    while ws is None:
        try:
            ws = await connect(URL, max_size=None)
        except OSError:
            if time.monotonic() > end:
                print("no Minecraft on 25600: start the dev client first", file=sys.stderr)
                return 2
            await asyncio.sleep(1)
    link = Link(ws)

    hello = await link.wait(lambda: next((m for m in link.messages if m.get("t") == "hello"), None), 5)
    report("hello", hello is not None and hello.get("proto") == 1 and hello.get("mod") == "cyberblock" and hello.get("mc") == "26.3", str(hello))

    await link.send(t="hello", proto=1, game="cp2077", build="2.31")
    await link.send(t="view", w=1920, h=1080)
    await link.send(t="state", active=True, vehicle=False)
    await link.send(t="hud", hidden=False)
    st = await link.wait(lambda: link.status, wait_s)
    fields = {"hp", "maxHp", "food", "gm", "screen", "t"}
    report("status fields", st is not None and set(st) == fields and st["gm"] in {"survival", "creative", "adventure", "spectator"}
           and isinstance(st["screen"], bool) and all(isinstance(st[k], (int, float)) for k in ("hp", "maxHp", "food")), str(st))
    await asyncio.sleep(1.0)
    t_end = time.monotonic()
    n = sum(1 for t in link.status_times if t_end - 1.0 <= t <= t_end)
    report("status about 10 per second", 7 <= n <= 13, f"{n} in the last second")

    # protocol mismatch: Minecraft closes the link
    other = await connect(URL, max_size=None)
    await other.send(json.dumps({"t": "hello", "proto": 2, "game": "cp2077", "build": "9.99"}))
    try:
        await asyncio.wait_for(other.wait_closed(), 5)
        code = other.close_code
    except asyncio.TimeoutError:
        code = None
    report("hello with another proto closes the link", code == 1002, f"close code {code}")

    # a browser page can reach 127.0.0.1 too: connections that carry an Origin are refused
    try:
        browser = await connect(URL, max_size=None, origin="https://example.com")
        try:
            await asyncio.wait_for(browser.wait_closed(), 5)
            code = browser.close_code
        except asyncio.TimeoutError:
            code = None
    except Exception as e:  # noqa: BLE001 - a refused handshake is fine too
        code = f"refused: {e!r}"
    report("browser origin refused", code == 1008 or str(code).startswith("refused"), f"{code}")

    # bad input is ignored and the link lives on
    for bad in ('not json', '[]', '{}', '{"t":"ground"}', '{"t":"ground","c":[1,2,3]}', '{"t":"ground","c":[0,0,5,4]}',
                '{"t":"ground","c":["a"]}', '{"t":"cam"}', '{"t":"slot"}', '{"t":"nope","x":1}'):
        await ws.send(bad)
    before = len(link.status_times)
    await asyncio.sleep(0.6)
    report("bad input ignored, link alive", len(link.status_times) > before and not ws.close_code, f"{len(link.status_times) - before} status after")
    if log is not None:
        report("bad input never throws inside Minecraft", "Error executing task" not in log.read_text(encoding="utf-8", errors="replace"))

    game = Game(link, log)
    if log is None:
        print("SKIP  ground/unground checks (no --log)")
    else:
        await link.send(t="clear")
        await link.send(t="ground", c=[10, 10, 70, 71, 11, 10, 70, 70])
        await link.send(t="cmd", c="setblock 12 70 10 minecraft:stone")
        await link.send(t="ground", c=[12, 10, 70, 70])
        report("ground places barriers", (await game.block_is(10, 71, 10, "barrier", "g1")) and (await game.block_is(11, 70, 10, "barrier", "g2")))
        report("ground only replaces air", await game.block_is(12, 70, 10, "stone", "g3"))
        await link.send(t="unground", c=[11, 10, 70, 70, 12, 10, 70, 70])
        report("unground removes barriers", await game.block_is(11, 70, 10, "air", "u1"))
        report("unground keeps blocks that are not ours", await game.block_is(12, 70, 10, "stone", "u2"))
        report("unground keeps the rest", await game.block_is(10, 70, 10, "barrier", "u3"))
        await link.send(t="ground", c=[20, 20, -100000, 100000])
        report("huge range is clamped to the world", (await game.block_is(20, 1535, 20, "barrier", "h1")) and (await game.block_is(20, -512, 20, "barrier", "h2")))
        await link.send(t="ground", c=[v for x in range(30, 39) for v in (x, 30, -100000, 100000)])
        report("more cells than the limit are ignored", await game.block_is(30, 0, 30, "air", "l1"))
        await link.send(t="clear")
        report("clear removes our barriers", (await game.block_is(10, 70, 10, "air", "c1")) and (await game.block_is(20, 1535, 20, "air", "c2")))
        await link.send(t="cmd", c="setblock 12 70 10 minecraft:air")

    # keys that open Minecraft screens: status.screen says so
    for key in ("inventory", "chat", "command"):
        await link.send(t="key", k=key, down=True)
        await link.send(t="key", k=key, down=False)
        opened = await link.wait(lambda: link.status and link.status["screen"], 3)
        report(f"key {key} opens a screen", bool(opened))
        await link.send(t="key", k="escape", down=True)
        closed = await link.wait(lambda: link.status and not link.status["screen"], 3)
        report(f"escape closes it ({key})", bool(closed))

    await link.send(t="state", active=False, vehicle=False)
    await ws.close()
    print("FAILED: " + ", ".join(FAILED) if FAILED else "all checks passed")
    return 1 if FAILED else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--log", type=Path, help="the dev client's latest.log (mc/run/logs/latest.log), to read blocks back")
    ap.add_argument("--wait", type=float, default=90.0, help="seconds to wait for Minecraft and its first status (default 90)")
    args = ap.parse_args()
    sys.exit(asyncio.run(main(args.log, args.wait)))
