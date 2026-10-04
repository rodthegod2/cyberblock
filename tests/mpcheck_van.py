"""The Hacker in the Van's multiplayer check (megaplan task 8-V1, "mpcheck with a fake host"), on tools/mpcheck.py's harness.

    uv run python tests/mpcheck_van.py [--keep]

Run it from a git worktree (it runs Gradle). It starts a dedicated test server on 127.0.0.1:25980 (package 8V's, megaplan section 1.15), a
host client on link port 25820 driven by a fake host `--hacks2 --figures` with layer 6 (people, cars and devices to target; it prints
and answers every `hack`, `devact` and `rhack`), and a guest client (link port 25827, a slot no Phase 8 package that starts clients
owns). The guest sits in a Van and acts through the devtools commands `/cyberblock test van ...`, which take the server paths of the
Van screen and its Hack Wheel. Checks: the Van screen sees the host's people, cars and devices; a chip from the screen reaches the
owner's DLL as a `hack`; a device action as a `devact` that the DLL answers; a drone takes off, sees targets with a clear line and its
chip reaches the DLL too; its leash holds it at 64 blocks; `guests hacks off` stops the Van's chips and device actions and `on` brings
them back; a shot drone docks with an empty battery and cannot take off; a host cannot sit in; a netrunner who reconnects while
seated gets his seat back; getting out docks nothing left behind; the relay drops nothing. This file is not a pytest module (no `test_` prefix): it starts Minecraft. Phase 8's protocol package can wire it into
tools/mpcheck.py as a `van` scenario.
"""
import argparse
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from tools import mpcheck as mp, play  # noqa: E402

CLIENT_HOST, CLIENT_GUEST, SERVER = 25820, 25827, 25980
FRAME_PREFIX = "Local\\CyberblockFrame8V"
GUEST_AT = (-1.5, 64.0, 6.5)  # on the street in front of the fake host's figures and devices (mpcheck's devices spot)
HACK_EVENT = r"\[CbHost1\] hack: (\{.*\})"
RHACK_EVENT = r"\[CbHost1\] rhack: (\{.*\})"
STATE = re.compile(r"host (\S+), (\d+) targets \((\d+) screen, (\d+) drone; (\d+) people, (\d+) cars, (\d+) devices\), drone (docked|at [\d.]+ m), battery (\d+) %")
RAM_WAIT_S = 9.0  # RAM refills 1 every 3 s; the checks spend at most 2 between waits (Ping costs 1)
CHIP_GAP_S = 2.5  # Ping recharges in 2 s


class VanPorts(mp.Ports):
    """8V's ports: one server port, the host's link port and a guest link port; frames Local\\CyberblockFrame8VH and ...8VG."""

    def server(self, scenario: str) -> int:
        return SERVER

    def client(self, name: str) -> int:
        return {"CbHost1": CLIENT_HOST, "CbGuest1": CLIENT_GUEST}[name]

    def check(self) -> None:
        ports = [SERVER, CLIENT_HOST, CLIENT_GUEST]
        if mp.FORBIDDEN_PORTS & set(ports) or self.frame("CbHost1") == "Local\\CyberblockFrame":
            raise mp.MpError("8V's ports and frames must not be the user's own")


def state(server: mp.Server) -> dict | None:
    """`/cyberblock test van state` as the guest, parsed: host, counts, drone, battery."""
    m = STATE.search(server.test("van state", as_player="CbGuest1"))
    if not m:
        return None
    keys = ("host", "targets", "screen", "drone", "people", "cars", "devices", "where", "battery")
    return {k: (v if k in ("host", "where") else int(v)) for k, v in zip(keys, m.groups())}


def events(host: mp.FakeHost, pattern: str, since: int) -> list[dict]:
    return mp.logged_events(host.log, pattern, since)


def hack_reaches(host: mp.FakeHost, since: int, name: str) -> bool:
    """The fake host got a `hack` of that chip on a target id after `since`."""
    return any(m.get("hack") == name and str(m.get("target", "")).isdigit() for m in events(host, HACK_EVENT, since))


def scenario_van(env: mp.Env, rep: mp.Report) -> None:
    server = env.server("van", max_hosts=1)
    _, host = mp.start_host(env, rep, server, "CbHost1", ver=env.mod_version, layers=(6,), modes=("hacks2", "figures"), wait_host=mp.T_JOIN)
    rep.step("the host's figures stand", lambda: mp.poll(lambda: mp.proxies_stand(server), mp.T_EFFECT, mp.T_POLL, "the figures"), must=True)
    guest = env.client("CbGuest1", join=server.port)
    rep.step("a guest joins", lambda: server.log.wait(r"handshake: CbGuest1 .*-> guest", mp.T_JOIN), must=True)
    rep.step("a Cyberpunk host cannot sit in a Van (it is for guests)", lambda: "-> not seated" in server.test("van sit", as_player="CbHost1"))
    server.rcon("execute at CbHost1 run kill @e[type=cyberblock:van,distance=..4]")  # the Van that test parked for him
    server.rcon("execute as CbGuest1 run cyberblock kit")  # the quickhack chips and the cyberdeck
    server.rcon(f"tp CbGuest1 {GUEST_AT[0]} {GUEST_AT[1]} {GUEST_AT[2]}")
    time.sleep(2.0)
    rep.step("the guest sits in a Van as netrunner, linked to the host", lambda: "-> seated" in server.test("van sit", as_player="CbGuest1")
             and bool(server.log.wait(r"van: CbGuest1 sits in the Van .*\(host CbHost1\)", mp.T_EFFECT)), must=True)
    rep.step("the Van screen sees the host's people, cars and devices (the host's area)",
             lambda: mp.poll(lambda: (s := state(server)) and s["host"] == "CbHost1" and s["people"] > 0 and s["cars"] > 0 and s["devices"] > 0,
                             mp.T_EFFECT, 1.0, "the targets"))
    time.sleep(RAM_WAIT_S)

    mark = host.log.mark()
    rep.step("a Ping from the Van screen reaches the owner's DLL as a `hack` on that person",
             lambda: "sent to" in server.test("van hack ping screen", as_player="CbGuest1")
             and mp.poll(lambda: hack_reaches(host, mark, "ping"), mp.T_EFFECT, mp.T_POLL, "the hack"))
    rep.step("the server logged the Van's chip", lambda: bool(server.log.wait(r"van: CbGuest1 -> ping on .* of CbHost1 \(from the screen\)", mp.T_EFFECT)))

    mark = host.log.mark()
    rep.step("a device action from the Van screen reaches the owner's DLL as a `devact` (open)",
             lambda: "sent to" in server.test("van device open screen", as_player="CbGuest1")
             and mp.poll(lambda: any(m.get("act") == "open" for m in events(host, mp.DEVICE_ACT, mark)), mp.T_EFFECT, mp.T_POLL, "the devact"))
    rep.step("... and the DLL's `devresult` reaches the Van", lambda: bool(server.log.wait(r"van: CbHost1 devresult \d+ open ok", mp.T_EFFECT)))

    rep.step("a drone takes off from the Van", lambda: "-> flying" in server.test("van launch", as_player="CbGuest1")
             and bool(server.log.wait(r"van: CbGuest1 launched a drone", mp.T_EFFECT)))
    server.test("van fly 0 4 -6", as_player="CbGuest1")
    rep.step("the drone sees targets with a clear line", lambda: mp.poll(lambda: (s := state(server)) and s["drone"] > 0, mp.T_EFFECT, 1.0, "drone targets"))
    time.sleep(CHIP_GAP_S)
    mark = host.log.mark()
    rep.step("a Ping from the drone reaches the owner's DLL", lambda: "(drone)" in server.test("van hack ping drone", as_player="CbGuest1")
             and mp.poll(lambda: hack_reaches(host, mark, "ping"), mp.T_EFFECT, mp.T_POLL, "the hack"))
    rep.step("the drone's leash holds it 64 blocks from the Van", lambda: "(64.0 m from the Van)" in server.test("van fly 120 0 0", as_player="CbGuest1"))
    server.test("van fly 0 4 -6", as_player="CbGuest1")

    time.sleep(RAM_WAIT_S)
    mp.guests_hacks(server, False)
    mark = host.log.mark()
    server.test("van hack ping", as_player="CbGuest1")
    server.test("van device close", as_player="CbGuest1")
    time.sleep(mp.T_QUIET)
    rep.step("with `/cyberblock guests hacks off` the Van's chips and device actions never reach the DLL",
             lambda: not hack_reaches(host, mark, "ping") and not events(host, mp.DEVICE_ACT, mark))
    mp.guests_hacks(server, True)
    time.sleep(CHIP_GAP_S)
    mark = host.log.mark()
    rep.step("with `guests hacks on` they work again", lambda: "sent to" in server.test("van hack ping", as_player="CbGuest1")
             and mp.poll(lambda: hack_reaches(host, mark, "ping"), mp.T_EFFECT, mp.T_POLL, "the hack"))

    server.rcon("damage @e[type=cyberblock:van_drone,limit=1] 1 minecraft:player_attack by CbHost1")
    rep.step("a shot drone docks with an empty battery", lambda: bool(server.log.wait(r"van: CbGuest1 docked a drone \(shot down by CbHost1; battery 0 %\)", mp.T_EFFECT)))
    rep.step("... and cannot take off until it recharged", lambda: "-> not launched" in server.test("van launch", as_player="CbGuest1"))
    rep.step("no drone is left in the world", lambda: not server.passes("execute if entity @e[type=cyberblock:van_drone]"))

    # vanilla saves a ridden Van with its player and seats him in it again at login: the Van must give him a seat again
    mark = server.log.mark()
    guest.proc.stop()
    rep.step("the netrunner leaves while seated", lambda: server.log.wait(r"CbGuest1 lost connection", mp.T_EFFECT, mark), must=True)
    mark = server.log.mark()
    guest = env.client("CbGuest1", join=server.port)
    rep.step("after a reconnect he is seated in his Van again, with a seat",
             lambda: server.log.wait(r"van: CbGuest1 sits in the Van at .* as netrunner again", mp.T_JOIN, mark), must=True)
    rep.step("... and the Van answers him (its state, linked to the host)",
             lambda: mp.poll(lambda: (s := state(server)) and s["host"] == "CbHost1", mp.T_EFFECT, 1.0, "the state"))

    server.rcon("ride CbGuest1 dismount")
    rep.step("getting out of the Van is logged", lambda: bool(server.log.wait(r"van: CbGuest1 left the Van", mp.T_EFFECT)))
    rep.step("the Van stays parked", lambda: server.passes("execute if entity @e[type=cyberblock:van]"))
    rep.step("the server logged no relay drops for the host", lambda: not server.log.find(r"relay: CbHost1 dropped"))
    rep.step("the server logged no van warnings", lambda: not server.log.find(r"WARN.*van: "))


class ShotsEnv(mp.Env):
    """The same harness, with the guest's client running the Van's screenshot tour (CYBERBLOCK_VAN_SHOTS) and nobody else's."""

    def client(self, name: str, **kw) -> mp.Client:
        if name != "CbGuest1":
            return super().client(name, **kw)
        child_env = self._child_env
        self._child_env = lambda extra=None: child_env({**(extra or {}), "CYBERBLOCK_VAN_SHOTS": "1"})
        try:
            return super().client(name, **kw)
        finally:
            self._child_env = child_env


def run_shots(env: ShotsEnv, out: Path) -> int:
    """The screenshot tour (VanShots, client side): the same server, host and guest; the guest is an operator for the devtools
    command the tour sends. The PNGs are copied into `out`."""
    rep = mp.Report("van shots")
    server = env.server("van", max_hosts=1)
    mp.start_host(env, rep, server, "CbHost1", ver=env.mod_version, layers=(6,), modes=("hacks2", "figures"), wait_host=mp.T_JOIN)
    guest = env.client("CbGuest1", join=server.port)
    server.log.wait(r"handshake: CbGuest1 .*-> guest", mp.T_JOIN)
    server.rcon("op CbGuest1")
    server.rcon("execute as CbGuest1 run cyberblock kit")
    server.rcon(f"tp CbGuest1 {GUEST_AT[0]} {GUEST_AT[1]} {GUEST_AT[2]} 180 10")
    guest.log.wait(r"shots: done", 900.0)
    out.mkdir(parents=True, exist_ok=True)
    shots = sorted(env.run.glob("client-CbGuest1-*/screenshots/8v_*.png"))
    for png in shots:
        (out / png.name).write_bytes(png.read_bytes())
    print("\n".join(f"  {out / p.name}" for p in shots))
    return 0 if shots else 1


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="The Hacker in the Van's multiplayer check (megaplan 8-V1). Run it from a worktree.")
    ap.add_argument("--keep", action="store_true", help="keep the run folder for debugging")
    ap.add_argument("--shots", type=Path, metavar="DIR", help="run the Van's screenshot tour instead and copy its PNGs into DIR")
    args = ap.parse_args(argv)
    if not (ROOT / ".git").is_file():
        print(f"{ROOT} is the main checkout: run this from a git worktree (it runs Gradle)", file=sys.stderr)
        return 2
    java = play.find_jdk(play.default_jdk_candidates())
    if java is None:
        print(f"no JDK {play.MIN_JAVA} found: set JAVA_HOME to one", file=sys.stderr)
        return 1
    env = (ShotsEnv if args.shots else mp.Env)(ROOT, VanPorts(CLIENT_HOST, SERVER, FRAME_PREFIX), java, keep=args.keep)
    mp.SCENARIO_FUNCS["van"] = scenario_van
    try:
        if args.shots:
            return run_shots(env, args.shots)
        return mp.summary(mp.run_scenarios(env, ["van"]))
    except KeyboardInterrupt:
        print("interrupted: stopping everything this run started", file=sys.stderr)
        return 130
    except mp.MpError as e:
        print(f"mpcheck_van: {e}", file=sys.stderr)
        return 1
    finally:
        env.close()


if __name__ == "__main__":
    sys.exit(main())
