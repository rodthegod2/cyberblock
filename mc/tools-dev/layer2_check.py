"""Layer 2 oracle for the Cyberblock Minecraft mod: a minimal fake Cyberpunk that drives the running dev client and checks what
Minecraft sends back (block, explosion, hit, ignite) and what it does with what it is sent (npcs, dmg).

Start the dev client first (a test client beside the real game uses its own port and frame name; wait until the world is loaded):
    cd mc && gradlew.bat runClient -Pcyberblock.port=25702 "-Pcyberblock.frame=Local\\CyberblockFrameTestM" --args="--quickPlaySingleplayer cyberblock"
then, from the repo root:
    uv run python mc/tools-dev/layer2_check.py --port 25702 --log mc/run/logs/latest.log

It plays a Cyberpunk that keeps sending a camera pose (Minecraft only counts the host as attached while it does) and an `npcs`
list, and uses server commands (`cmd`, a test-only message) and real input (`key`, `slot`) to make things happen: setblock/fill,
summoned TNT, creepers and fireworks, a sword swing, a bow shot and flint and steel aimed at proxies. Entities and blocks are read
back with `say` through the game log, Steve's health from `status`. Prints PASS/FAIL per check and a transcript of the
Minecraft to Cyberpunk messages seen; exit code 1 on any FAIL.
"""
import argparse
import asyncio
import json
import math
import re
import sys
import time
import uuid
from pathlib import Path

from websockets.asyncio.client import connect

sys.path.insert(0, str(Path(__file__).resolve().parent))
from link_check import FAILED, Link, report  # noqa: E402

HUMAN_A = "9007199254740993"  # above 2^53: a JSON double cannot hold it
HUMAN_B = "18446744073709551615"  # 2^64 - 1
CAR = "4503599627370497"
CAMERA = [0.5, 65.62, 0.5]  # eye of a Steve whose feet are at (0.5, 64, 0.5), looking along +Z (yaw 0)
FEET = [0.5, 64.0, 0.5]


class Oracle:
    def __init__(self, link: Link, log: Path):
        self.link, self.log, self.nonce = link, log, uuid.uuid4().hex[:8]
        self.npcs: list[dict] | None = None  # sent at 10 Hz while not None
        self.frame = 0
        self.cam_on = True
        self.tasks = [asyncio.create_task(self._cam()), asyncio.create_task(self._npcs())]

    async def _cam(self):
        while True:
            if self.cam_on:
                self.frame += 1
                await self.link.send(t="cam", f=self.frame, p=CAMERA, r=[0.0, 0.0, 0.0], fov=70.0, fp=True, pl=FEET, h=0.0)
            await asyncio.sleep(1 / 30)

    async def _npcs(self):
        while True:
            if self.npcs is not None and self.cam_on:
                await self.link.send(t="npcs", list=self.npcs)
            await asyncio.sleep(0.1)

    async def cmd(self, command: str):
        await self.link.send(t="cmd", c=command)

    def mark(self) -> int:
        return len(self.link.messages)

    def since(self, mark: int, t: str) -> list[dict]:
        return [m for m in self.link.messages[mark:] if m.get("t") == t]

    async def wait_for(self, mark: int, t: str, cond=lambda m: True, timeout: float = 5.0):
        return await self.link.wait(lambda: next((m for m in self.since(mark, t) if cond(m)), None), timeout)

    async def said(self, selector_command: str, label: str) -> bool | None:
        """Runs `execute if/unless ...` with a say on each branch and reads the answer from the log."""
        tag = f"L2-{self.nonce}-{label}"
        await self.cmd(f"execute if {selector_command} run say {tag} yes")
        await self.cmd(f"execute unless {selector_command} run say {tag} no")
        end = time.monotonic() + 6.0
        while time.monotonic() < end:
            text = self.log.read_text(encoding="utf-8", errors="replace")
            # the mod also logs every command it runs ("command: ... say TAG yes"): only the server's own chat line is the answer
            if f"[Server] {tag} yes" in text:
                return True
            if f"[Server] {tag} no" in text:
                return False
            await asyncio.sleep(0.1)
        return None

    async def block_is(self, x: int, y: int, z: int, block: str, label: str) -> bool | None:
        return await self.said(f"block {x} {y} {z} minecraft:{block}", label)

    async def hp(self, expected: float, timeout: float = 4.0) -> float | None:
        r = await self.link.wait(lambda: self.link.status if self.link.status and abs(self.link.status["hp"] - expected) < 0.06 else None, timeout)
        return r["hp"] if r else (self.link.status or {}).get("hp")

    async def npc_gone_and_back(self, id_: str, entry: dict):
        """Removes the proxy by leaving it out of the list for a moment, then lists it again (a new entity, a new ignition)."""
        keep = [e for e in (self.npcs or []) if e["id"] != id_]
        self.npcs = keep
        await asyncio.sleep(0.5)
        self.npcs = keep + [entry]
        await asyncio.sleep(0.8)

    def stop(self):
        for t in self.tasks:
            t.cancel()


def npc(id_: str, x: float, y: float, z: float, yaw: float, kind: str, hostile: bool = False) -> dict:
    return {"id": id_, "p": [x, y, z], "yaw": yaw, "kind": kind, "hostile": hostile}


def near(v: list[float], target: list[float], tol: float) -> bool:
    return all(abs(a - b) <= tol for a, b in zip(v, target))


async def clean(o: Oracle):
    """The world persists between runs: take away what an earlier run built, before any message is counted."""
    await o.link.send(t="clear")
    for x, y, z in [(5, 70, 5), (9, 70, 9), (6, 70, 6), (6, 70, 7), (7, 70, 7), (8, 70, 8), (8, 71, 8), (60, 70, 60), (62, 70, 60), (10, 64, 10), (10, 63, 10)]:
        await o.cmd(f"setblock {x} {y} {z} minecraft:air")
    await o.cmd("fill 30 70 30 40 72 40 minecraft:air")
    await o.cmd("kill @e[type=minecraft:tnt]")
    await asyncio.sleep(2.0)


async def blocks(o: Oracle):
    print("\n-- M1 block events")
    m = o.mark()
    await o.cmd("setblock 5 70 5 minecraft:stone")
    r = await o.wait_for(m, "block", lambda b: b["pos"] == [5, 70, 5])
    report("placing stone sends block add", r is not None and r["op"] == "add", str(r))
    m = o.mark()
    await o.cmd("setblock 5 70 5 minecraft:air")
    r = await o.wait_for(m, "block", lambda b: b["pos"] == [5, 70, 5])
    report("breaking it sends block remove", r is not None and r["op"] == "remove", str(r))

    m = o.mark()
    await o.link.send(t="ground", c=[8, 8, 70, 71])
    await asyncio.sleep(1.0)
    report("the host's own ground (barriers) is not reported", not o.since(m, "block"), str(o.since(m, "block")))
    report("  (the ground did get placed)", await o.block_is(8, 70, 8, "barrier", "grd"))

    m = o.mark()
    await o.cmd("setblock 9 70 9 minecraft:barrier")
    await o.cmd("setblock 6 70 6 minecraft:torch")
    await o.cmd("setblock 6 70 7 minecraft:water")
    await asyncio.sleep(1.0)
    report("barriers, torches and water are not solid for the host", not o.since(m, "block"), str(o.since(m, "block")))
    m = o.mark()
    await o.cmd("setblock 9 70 9 minecraft:stone")
    r = await o.wait_for(m, "block", lambda b: b["pos"] == [9, 70, 9])
    report("stone over a barrier is an add", r is not None and r["op"] == "add", str(r))

    m = o.mark()
    await o.cmd("setblock 7 70 7 minecraft:oak_door[half=lower,open=false]")
    r = await o.wait_for(m, "block", lambda b: b["pos"] == [7, 70, 7])
    report("a closed door is solid", r is not None and r["op"] == "add", str(r))
    m = o.mark()
    await o.cmd("setblock 7 70 7 minecraft:oak_door[half=lower,open=true]")
    r = await o.wait_for(m, "block", lambda b: b["pos"] == [7, 70, 7])
    report("opening it removes the box", r is not None and r["op"] == "remove", str(r))

    m = o.mark()
    await o.cmd("fill 30 70 30 40 72 40 minecraft:stone")  # 11 x 3 x 11 = 363 cells
    await asyncio.sleep(0.5)
    t0 = time.monotonic()
    await o.link.wait(lambda: len(o.since(m, "block")) >= 363, 8)
    got = o.since(m, "block")
    cells = {tuple(b["pos"]) for b in got}
    report("a 363 block fill arrives complete, one event per cell", len(got) == 363 and len(cells) == 363 and all(b["op"] == "add" for b in got), f"{len(got)} events, {len(cells)} cells")
    m2 = o.mark()
    await o.cmd("fill 30 70 30 40 72 40 minecraft:air")
    await o.link.wait(lambda: len(o.since(m2, "block")) >= 363, 8)
    report("clearing it sends 363 removes", sum(1 for b in o.since(m2, "block") if b["op"] == "remove") == 363)


async def explosions(o: Oracle):
    print("\n-- M2 explosion events")
    m = o.mark()
    await o.cmd("summon minecraft:tnt 0.5 70 40 {fuse:10s,NoGravity:1b}")
    r = await o.wait_for(m, "explosion")
    report("TNT: power 4, no fire", r is not None and r["power"] == 4.0 and r["fire"] is False and near(r["pos"], [0.5, 70.06, 40.5], 1.0), str(r))

    m = o.mark()
    await o.cmd("summon minecraft:creeper 0.5 70 50 {Fuse:1s,ignited:1b,NoGravity:1b}")
    r = await o.wait_for(m, "explosion", timeout=8)
    report("creeper: power 3", r is not None and r["power"] == 3.0, str(r))
    m = o.mark()
    await o.cmd("summon minecraft:creeper 0.5 70 55 {powered:1b,Fuse:1s,ignited:1b,NoGravity:1b}")
    r = await o.wait_for(m, "explosion", timeout=8)
    report("charged creeper: power 6", r is not None and r["power"] == 6.0, str(r))

    star = ('{LifeTime:10,FireworksItem:{id:"minecraft:firework_rocket",count:1,components:{"minecraft:fireworks":'
            '{explosions:[{shape:"small_ball",colors:[I;16711680]}],flight_duration:1}}}}')
    m = o.mark()
    await o.cmd(f"summon minecraft:firework_rocket 0.5 70 60 {star}")
    r = await o.wait_for(m, "explosion", timeout=8)
    report("firework with a star: power 1.5", r is not None and r["power"] == 1.5 and near(r["pos"], [0.5, 70, 60.5], 8.0), str(r))
    m = o.mark()
    await o.cmd('summon minecraft:firework_rocket 0.5 70 65 {LifeTime:5,FireworksItem:{id:"minecraft:firework_rocket",count:1,components:{"minecraft:fireworks":{flight_duration:1}}}}')
    await asyncio.sleep(2.0)
    report("a plain flight rocket (no stars) sends nothing", not o.since(m, "explosion"), str(o.since(m, "explosion")))

    # barriers survive, ordinary blocks do not
    await o.cmd("setblock 60 70 60 minecraft:barrier")
    await o.cmd("setblock 62 70 60 minecraft:stone")
    await asyncio.sleep(0.5)
    m = o.mark()
    await o.cmd("summon minecraft:tnt 61.5 70 60.5 {fuse:5s,NoGravity:1b}")
    await o.wait_for(m, "explosion")
    await asyncio.sleep(1.0)
    report("TNT between a barrier and stone: the stone is gone", await o.block_is(62, 70, 60, "air", "bst"))
    report("  and the barrier is still there", await o.block_is(60, 70, 60, "barrier", "bbar"))
    removed = [b for b in o.since(m, "block") if b["pos"] == [62, 70, 60] and b["op"] == "remove"]
    report("  the stone's removal reached the host as a block remove", bool(removed))
    await o.cmd("setblock 60 70 60 minecraft:air")


async def proxies(o: Oracle):
    print("\n-- M3 proxies")
    o.npcs = [npc(HUMAN_A, 0.5, 64.0, 5.5, 90.0, "human"), npc(HUMAN_B, -3.0, 64.0, 5.0, -45.0, "human", True), npc(CAR, 4.5, 64.0, 8.5, 180.0, "vehicle")]
    await asyncio.sleep(1.0)
    at = lambda t, x, y, z, tol=0.3: f"entity @e[type=cyberblock:proxy_{t},x={x},y={y},z={z},distance=..{tol}]"  # noqa: E731
    report("human A stands at its position", await o.said(at("human", 0.5, 64, 5.5), "a1"))
    report("human B stands at its position", await o.said(at("human", -3, 64, 5), "b1"))
    report("the car stands at its position", await o.said(at("vehicle", 4.5, 64, 8.5), "c1"))
    probe = lambda t, x, y, z: f"entity @e[type=cyberblock:proxy_{t},x={x},y={y},z={z},dx=0,dy=0,dz=0]"  # noqa: E731
    report("human hitbox: 0.6 wide (inside at 0.25, outside at 0.35)",
           await o.said(probe("human", 0.75, 64.5, 5.5), "w1") and not await o.said(probe("human", 0.85, 64.5, 5.5), "w2"))
    report("human hitbox: 1.8 high", await o.said(probe("human", 0.5, 65.7, 5.5), "h1") and not await o.said(probe("human", 0.5, 65.9, 5.5), "h2"))
    report("vehicle hitbox: 2.0 wide, 1.6 high",
           await o.said(probe("vehicle", 5.4, 64.5, 8.5), "v1") and not await o.said(probe("vehicle", 5.6, 64.5, 8.5), "v2")
           and await o.said(probe("vehicle", 4.5, 65.5, 8.5), "v3") and not await o.said(probe("vehicle", 4.5, 65.7, 8.5), "v4"))

    await o.cmd("tag @e[type=cyberblock:proxy_human,x=0.5,y=64,z=5.5,distance=..0.3] add L2MOVED")
    o.npcs = [npc(HUMAN_A, 2.5, 64.0, 5.5, 0.0, "human"), npc(HUMAN_B, -3.0, 64.0, 5.0, -45.0, "human"), npc(CAR, 4.5, 64.0, 8.5, 180.0, "vehicle")]
    await asyncio.sleep(0.8)
    report("a moved id moves the same entity", await o.said("entity @e[type=cyberblock:proxy_human,tag=L2MOVED,x=2.5,y=64,z=5.5,distance=..0.3]", "mv1"))
    report("  and nothing is left at the old spot", not await o.said(at("human", 0.5, 64, 5.5), "mv2"))

    o.npcs = [npc(CAR, 4.5, 64.0, 8.5, 180.0, "vehicle")]
    await asyncio.sleep(0.8)
    report("ids missing from the list are removed", not await o.said("entity @e[type=cyberblock:proxy_human]", "rm1"))
    report("  the car stays", await o.said(at("vehicle", 4.5, 64, 8.5), "rm2"))

    o.npcs = [npc(CAR, 4.5, 64.0, 8.5, 180.0, "human")]
    await asyncio.sleep(0.8)
    report("an id that changes kind is replaced", await o.said(at("human", 4.5, 64, 8.5), "k1") and not await o.said("entity @e[type=cyberblock:proxy_vehicle]", "k2"))

    mark = o.mark()
    bad = [npc("x", 0, 64, 0, 0, "human"), npc("-5", 0, 64, 0, 0, "human"), npc("18446744073709551616", 0, 64, 0, 0, "human"),
           {"id": "7", "p": [1, 2], "yaw": 0, "kind": "human"}, {"id": 8, "p": [0, 64, 0], "yaw": 0, "kind": "human"},
           npc("9", 0, 99999, 0, 0, "human"), npc("10", 5e9, 64, 0, 0, "human"), npc("11", 0, 64, 0, 0, "tank"), 5, None, "x",
           npc("12", 6.5, 64.0, 6.5, 0.0, "human")]
    o.npcs = bad
    await asyncio.sleep(0.8)
    report("a list full of bad entries: the good one spawns, nothing else", await o.said(at("human", 6.5, 64, 6.5), "bad1")
           and not await o.said("entity @e[type=cyberblock:proxy_human,x=0,y=64,z=0,distance=..2]", "bad2"))
    await o.link.send(t="npcs", list="nope")
    await o.link.send(t="npcs")
    await o.link.send(t="npcs", list=[{"id": "1", "p": [float("nan"), 0, 0], "yaw": 0, "kind": "human"}])
    await asyncio.sleep(0.5)
    text = o.log.read_text(encoding="utf-8", errors="replace")
    report("bad npcs messages never throw inside Minecraft", "Error executing task" not in text)

    o.npcs = None
    await asyncio.sleep(3.0)
    report("no list for 2 seconds: the proxies are gone", not await o.said("entity @e[type=cyberblock:proxy_human]", "to1"))

    o.npcs = [npc(HUMAN_A, 0.5, 64.0, 5.5, 0.0, "human")]
    await asyncio.sleep(0.8)
    o.cam_on = False
    await asyncio.sleep(3.0)
    report("the host stops driving: the proxies are gone", not await o.said("entity @e[type=cyberblock:proxy_human]", "to2"))
    o.cam_on = True
    await asyncio.sleep(1.0)
    o.npcs = None


async def hits(o: Oracle):
    print("\n-- M3 hits, M5 ignite (real input aimed at proxies)")
    # human A one block short of reach, straight ahead of Steve's eyes
    o.npcs = [npc(HUMAN_A, 0.5, 64.0, 3.0, 0.0, "human"), npc(HUMAN_B, 10.5, 64.0, 10.5, 0.0, "human")]
    await asyncio.sleep(1.0)
    await o.cmd("clear @p")
    await o.cmd("give @p minecraft:diamond_sword")
    await o.cmd("give @p minecraft:bow")
    await o.cmd("give @p minecraft:arrow 64")
    await o.cmd("give @p minecraft:flint_and_steel")
    await asyncio.sleep(1.0)

    await o.link.send(t="slot", n=0)
    await asyncio.sleep(1.5)  # a new item resets the attack cooldown
    m = o.mark()
    await o.link.send(t="key", k="attack", down=True)
    await o.link.send(t="key", k="attack", down=False)
    r = await o.wait_for(m, "hit", lambda h: h["target"] == HUMAN_A)
    report("sword swing: hit melee on human A", r is not None and r["kind"] == "melee" and 6.0 <= r["dmg"] <= 8.0, str(r))
    report("  impulse is 6 m/s along Steve's view (+Z)", r is not None and near(r["impulse"], [0, 0, 6.0], 0.5), str(r and r["impulse"]))
    print("  transcript:", r)

    await asyncio.sleep(1.0)
    await o.link.send(t="slot", n=1)
    await asyncio.sleep(0.5)
    m = o.mark()
    await o.link.send(t="key", k="use", down=True)
    await asyncio.sleep(1.4)
    await o.link.send(t="key", k="use", down=False)
    r = await o.wait_for(m, "hit", lambda h: h["target"] == HUMAN_A, timeout=6)
    report("bow shot: hit arrow on human A", r is not None and r["kind"] == "arrow" and 5.0 <= r["dmg"] <= 10.0, str(r))
    report("  impulse is half the arrow's speed (about 30 m/s along +Z)", r is not None and 18.0 <= r["impulse"][2] <= 31.0 and abs(r["impulse"][0]) < 3.0, str(r and r["impulse"]))
    print("  transcript:", r)

    await asyncio.sleep(1.0)
    await o.link.send(t="slot", n=3)
    await asyncio.sleep(0.5)
    m = o.mark()
    await o.link.send(t="key", k="use", down=True)
    await o.link.send(t="key", k="use", down=False)
    r = await o.wait_for(m, "ignite", lambda h: h["target"] == HUMAN_A)
    report("flint and steel on human A: ignite 8 s", r is not None and abs(r["seconds"] - 8.0) < 0.1, str(r))
    print("  transcript:", r)
    await asyncio.sleep(0.5)
    m = o.mark()
    await o.link.send(t="key", k="use", down=True)
    await o.link.send(t="key", k="use", down=False)
    await asyncio.sleep(1.5)
    report("lighting it again while it still burns reports nothing new", not o.since(m, "ignite"), str(o.since(m, "ignite")))
    await o.link.send(t="slot", n=0)

    # commands as the oracle for the rest: /damage by the player
    await asyncio.sleep(1.0)
    m = o.mark()
    await o.cmd(f"damage @e[type=cyberblock:proxy_human,x=0.5,y=64,z=3,distance=..0.5,limit=1] 5 minecraft:player_attack by @p")
    r = await o.wait_for(m, "hit", lambda h: h["target"] == HUMAN_A)
    report("/damage 5 by the player: hit melee 5", r is not None and r["kind"] == "melee" and abs(r["dmg"] - 5.0) < 0.01, str(r))
    m = o.mark()
    await o.cmd("damage @e[type=cyberblock:proxy_human,x=0.5,y=64,z=3,distance=..0.5,limit=1] 3 minecraft:player_attack by @p")
    await asyncio.sleep(0.4)
    report("a weaker hit inside the damage cooldown counts for nothing", not o.since(m, "hit"), str(o.since(m, "hit")))
    await o.cmd("damage @e[type=cyberblock:proxy_human,x=0.5,y=64,z=3,distance=..0.5,limit=1] 8 minecraft:player_attack by @p")
    r = await o.wait_for(m, "hit")
    report("a stronger one counts for the difference (8 - 5 = 3)", r is not None and abs(r["dmg"] - 3.0) < 0.01, str(r))

    await asyncio.sleep(1.0)
    m = o.mark()
    await o.cmd("damage @e[type=cyberblock:proxy_human,x=0.5,y=64,z=3,distance=..0.5,limit=1] 6 minecraft:explosion by @p")
    await asyncio.sleep(1.0)
    r = (o.since(m, "hit") or [None])[0]
    report("an explosion with the player behind it is a hit of kind explosion", r is not None and r["kind"] == "explosion" and abs(r["dmg"] - 6.0) < 0.01, str(r))

    # nothing that is not a player or a mob hurts a proxy
    await asyncio.sleep(1.0)
    m = o.mark()
    await o.cmd("damage @e[type=cyberblock:proxy_human,x=0.5,y=64,z=3,distance=..0.5,limit=1] 6 minecraft:generic")
    await o.cmd("damage @e[type=cyberblock:proxy_human,x=0.5,y=64,z=3,distance=..0.5,limit=1] 6 minecraft:lava")
    await o.cmd("damage @e[type=cyberblock:proxy_human,x=0.5,y=64,z=3,distance=..0.5,limit=1] 6 minecraft:out_of_world")
    await o.cmd("summon minecraft:tnt 12.5 64.5 12.5 {fuse:5s,NoGravity:1b}")  # beside human B, 17 m from Steve
    await asyncio.sleep(2.0)
    report("generic, lava, void and unowned TNT (beside human B) send no hit", not o.since(m, "hit"), str(o.since(m, "hit")))

    # lava lights it (vanilla's own way)
    m = o.mark()
    await o.cmd("setblock 10 64 10 minecraft:lava")
    r = await o.wait_for(m, "ignite", lambda h: h["target"] == HUMAN_B, timeout=5)
    report("a proxy in lava: ignite 15 s, once", r is not None and abs(r["seconds"] - 15.0) < 0.6, str(r))
    await asyncio.sleep(2.0)
    report("  and the lava's own damage sends no hit", not [h for h in o.since(m, "hit") if h["target"] == HUMAN_B], str(o.since(m, "hit")))
    report("  and it is not re-reported while it burns", len([h for h in o.since(m, "ignite") if h["target"] == HUMAN_B]) == 1, str(o.since(m, "ignite")))
    await o.cmd("setblock 10 64 10 minecraft:air")
    await asyncio.sleep(1.0)
    await o.cmd("setblock 10 63 10 minecraft:stone")
    await asyncio.sleep(0.3)
    await o.npc_gone_and_back(HUMAN_B, npc(HUMAN_B, 10.5, 64.0, 10.5, 0.0, "human"))  # a fresh proxy: no ignition left running
    m = o.mark()
    await o.cmd("setblock 10 64 10 minecraft:fire")
    r = await o.wait_for(m, "ignite", lambda h: h["target"] == HUMAN_B, timeout=5)
    report("a proxy standing in a fire block: ignite 8 s", r is not None and abs(r["seconds"] - 8.0) < 0.6, str(r))
    await o.cmd("setblock 10 64 10 minecraft:air")
    await o.cmd("setblock 10 63 10 minecraft:air")
    o.npcs = None


async def steve(o: Oracle):
    await o.cmd("effect give @p minecraft:instant_health 1 5")
    await o.hp(20.0, 6.0)
    print("\n-- M4 damage to Steve")
    st = await o.link.wait(lambda: o.link.status, 5)
    report("Steve starts at 20 hp, survival", st is not None and st["gm"] == "survival" and st["maxHp"] == 20.0 and st["hp"] == 20.0, str(st))
    await o.cmd("effect clear @p")
    await asyncio.sleep(0.5)

    await o.link.send(t="dmg", frac=0.25, kind="bullet", **{"from": [0.5, 65.0, 10.0]})
    report("dmg 0.25 bullet: 20 -> 15", await o.hp(15.0) == 15.0, str(o.link.status))
    for _ in range(5):  # the damage cooldown must not swallow any of a burst
        await o.link.send(t="dmg", frac=0.05, kind="bullet", **{"from": None})
    report("five dmg 0.05 in a burst: 15 -> 10", await o.hp(10.0) == 10.0, str(o.link.status))
    for kind in ("explosion", "melee", "fall", "fire", "vehicle", "other", "laser"):
        before = o.link.status["hp"]
        await o.link.send(t="dmg", frac=0.025, kind=kind, **{"from": [0.5, 64.0, 10.0]})
        report(f"dmg kind {kind}: -0.5", await o.hp(before - 0.5) == before - 0.5, f"{before} -> {o.link.status['hp']}")
    hp = o.link.status["hp"]
    for bad in ('{"t":"dmg","frac":5,"kind":"bullet"}', '{"t":"dmg","frac":-1,"kind":"bullet"}', '{"t":"dmg","frac":NaN,"kind":"bullet"}',
                '{"t":"dmg","frac":"0.5","kind":"bullet"}', '{"t":"dmg","kind":"bullet"}', '{"t":"dmg","frac":0,"kind":"bullet"}'):
        await o.link.ws.send(bad)
    await asyncio.sleep(0.8)
    report("bad or zero frac does nothing", o.link.status["hp"] == hp, f"{hp} -> {o.link.status['hp']}")

    await o.cmd("gamemode creative @p")
    await asyncio.sleep(0.5)
    await o.link.send(t="dmg", frac=0.5, kind="bullet", **{"from": None})
    await asyncio.sleep(0.8)
    report("creative Steve takes nothing", o.link.status["gm"] == "creative" and o.link.status["hp"] >= hp, str(o.link.status))
    await o.cmd("gamemode survival @p")
    await o.cmd("effect give @p minecraft:instant_health 1 3")
    await asyncio.sleep(1.0)

    # armour counts (vanilla rules): full iron armour takes a bullet down
    await o.cmd("item replace entity @p armor.chest with minecraft:iron_chestplate")
    await o.cmd("item replace entity @p armor.legs with minecraft:iron_leggings")
    await o.cmd("item replace entity @p armor.head with minecraft:iron_helmet")
    await o.cmd("item replace entity @p armor.feet with minecraft:iron_boots")
    await o.cmd("effect give @p minecraft:instant_health 1 3")
    await asyncio.sleep(1.0)
    await o.hp(20.0)
    await o.link.send(t="dmg", frac=0.5, kind="bullet", **{"from": None})
    await asyncio.sleep(0.8)
    armoured = o.link.status["hp"]
    report("iron armour reduces a 10 hp bullet to between 10 and 20 hp left", 10.0 < armoured < 20.0, f"hp {armoured}")
    await o.cmd("clear @p minecraft:iron_chestplate")
    await o.cmd("item replace entity @p armor.legs with minecraft:air")
    await o.cmd("item replace entity @p armor.head with minecraft:air")
    await o.cmd("item replace entity @p armor.feet with minecraft:air")
    await o.cmd("item replace entity @p armor.chest with minecraft:air")
    await o.cmd("effect give @p minecraft:instant_health 1 3")
    await asyncio.sleep(1.0)
    await o.hp(20.0)

    # death: the screen stays while the host drives, and the host's cursor and click respawn him
    await o.link.send(t="dmg", frac=4.0, kind="bullet", **{"from": [0.5, 65.0, 10.0]})
    dead = await o.link.wait(lambda: o.link.status if o.link.status["hp"] <= 0.0 else None, 4)
    report("frac 4 kills a survival Steve", dead is not None, str(o.link.status))
    screen = await o.link.wait(lambda: o.link.status if o.link.status["screen"] else None, 4)
    report("the death screen is up", screen is not None, str(o.link.status))
    await asyncio.sleep(3.5)
    report("it stays up while the host drives (no auto-respawn)", o.link.status["screen"] and o.link.status["hp"] <= 0.0, str(o.link.status))
    clicked = False
    for y in (0.552, 0.50, 0.60, 0.45, 0.65):
        await o.link.send(t="cursor", x=0.5, y=y)
        await asyncio.sleep(0.2)
        await o.link.send(t="click", b=0, down=True)
        await o.link.send(t="click", b=0, down=False)
        back = await o.link.wait(lambda: o.link.status if o.link.status["hp"] > 0.0 else None, 2)
        if back:
            clicked = True
            print(f"  respawn button clicked at cursor y={y}")
            break
    report("host cursor + click on the death screen respawns Steve", clicked, str(o.link.status))
    await asyncio.sleep(1.0)
    report("  the screen is closed again", not o.link.status["screen"], str(o.link.status))

    # no host driving: a death is respawned for him (layer 1 behaviour for the void)
    await o.cmd("kill @p")
    await o.link.wait(lambda: o.link.status if o.link.status["hp"] <= 0.0 else None, 4)
    o.cam_on = False
    back = await o.link.wait(lambda: o.link.status if o.link.status["hp"] > 0.0 and not o.link.status["screen"] else None, 8)
    report("with no host driving a death is respawned automatically", back is not None, str(o.link.status))
    o.cam_on = True


async def main(port: int, log: Path, wait_s: float) -> int:
    url = f"ws://127.0.0.1:{port}"
    print(f"connecting to {url} (waiting up to {wait_s:.0f} s for Minecraft)")
    ws = None
    end = time.monotonic() + wait_s
    while ws is None:
        try:
            ws = await connect(url, max_size=None)
        except OSError:
            if time.monotonic() > end:
                print(f"no Minecraft on {port}: start the dev client first", file=sys.stderr)
                return 2
            await asyncio.sleep(1)
    link = Link(ws)
    hello = await link.wait(lambda: next((m for m in link.messages if m.get("t") == "hello"), None), 5)
    report("hello", hello is not None and hello.get("proto") == 1 and hello.get("mod") == "cyberblock", str(hello))
    await link.send(t="hello", proto=1, game="cp2077", build="2.31")
    await link.send(t="view", w=1920, h=1080)
    await link.send(t="state", active=True, vehicle=False)
    await link.send(t="hud", hidden=False)
    o = Oracle(link, log)
    st = await link.wait(lambda: link.status, wait_s)
    report("status arrives", st is not None, str(st))
    await asyncio.sleep(3.0)  # the player is placed by the camera pose; the first frames of a new world are slow
    await link.send(t="cmd", c="gamerule send_command_feedback false")

    try:
        await clean(o)
        for part in (blocks, explosions, proxies, hits, steve):
            await part(o)
    finally:
        o.cam_on = False
        o.stop()
    text = log.read_text(encoding="utf-8", errors="replace")
    errors = [line for line in text.splitlines() if ("Error executing task" in line or "Exception" in line) and "cyberblock" in line.lower()]
    report("no errors from the mod in the game log", not errors, "; ".join(errors[:3]))
    await link.send(t="state", active=False, vehicle=False)
    await ws.close()
    print("FAILED: " + ", ".join(FAILED) if FAILED else "all checks passed")
    return 1 if FAILED else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, default=25702, help="the dev client's -Dcyberblock.port (default 25702, never the real game's 25600)")
    ap.add_argument("--log", type=Path, required=True, help="the dev client's latest.log (mc/run/logs/latest.log)")
    ap.add_argument("--wait", type=float, default=120.0, help="seconds to wait for Minecraft and its first status (default 120)")
    args = ap.parse_args()
    if args.port == 25600:
        sys.exit("refusing port 25600: that is the real game's")
    sys.exit(asyncio.run(main(args.port, args.log, args.wait)))
