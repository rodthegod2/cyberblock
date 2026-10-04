"""Performance measurement for megaplan task 9-F1, on tools/mpcheck.py's harness: a dedicated test server, two host clients each driven
by tools/loadhost.py (a busy Night City street: ground, 48 figures, 64 devices) and four guest clients, then the server's tick time,
the hosts' frame export and the guests' figure counts and frame rate.

    uv run python -m tools.perfcheck --out <report.json> [--seconds 60] [--scale 1.0] [--figure-cap 48] [--guests 4]

Run it from a git worktree (it runs Gradle, rule 0.2). Ports (megaplan section 1.15, package 9F): the host clients' links 25832 and
25836, the guests' links on a port the system picks (port 0: they need none, so they take nobody's), the server 25992, the frames
`Local\\CyberblockFrame9F` + H, H2 (hosts) and G, G2, ... (guests). The commit charge is checked before each start (rule 0.7);
everything started is stopped at the end.

The run: the server takes up to MAX_PLAYERS and whitelists the extra guests (3 and 4 are not among mpcheck's offline names), every
client joins first (one at a time: concurrent Gradle runs of one project wait on each other anyway), then both load hosts link at once. Their sessions start with the
whole street at once (the burst), then V walks. `/tick query` (vanilla: the last 100 ticks' average and percentiles) is read every
SAMPLE_S seconds over BURST_S seconds of burst and `--seconds` of steady walking; the guests are teleported to their host every
FOLLOW_S seconds. Each client's run folder gets an options.txt (no vsync, no frame limit when unfocused) and
`config/cyberblock/performance.json` with --scale and --figure-cap (megaplan 9-F1; a build without Performance mode ignores it).
"""
import argparse
import contextlib
import json
import re
import statistics
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

from tools import mpcheck, play

HOST_PORTS = {"CbHost1": 25832, "CbHost2": 25836}
SERVER_PORT = 25992
FRAME_PREFIX = "Local\\CyberblockFrame9F"
GUEST_NAMES = ("CbGuest1", "CbGuest2", "CbGuest3", "CbGuest4")
STREET_Z = {"CbHost1": 0, "CbHost2": 160}  # each host walks its own street, 160 m apart
IDS_FROM = {"CbHost1": 7_000_000_000_000_000_000, "CbHost2": 7_100_000_000_000_000_000}
SAMPLE_S, FOLLOW_S, BURST_S = 5.0, 15.0, 15.0
CLIENT_HEAP, MAX_PLAYERS = "-Xmx1536m", 8
# options.txt of every client; `version` is 26.3's data version (without it the file is read as a very old one)
OPTIONS = ("version:5023", "enableVsync:false", "maxFps:260", 'inactivityFpsLimit:"minimized"', "pauseOnLostFocus:false", "renderDistance:8")
TICK_AVG = re.compile(r"Average time per tick: ([\d.]+) ?ms")
TICK_PCT = re.compile(r"P50: ([\d.]+) ?ms P95: ([\d.]+) ?ms P99: ([\d.]+) ?ms\. Sample: (\d+)")
LOAD_LINE = re.compile(r"\[(\w+)\] load: (\{.*\})")
EXPORT_LINE = re.compile(r"frame export: (\d+)x(\d+), ([\d.]+) fps, readback ([\d.]+) ms avg \(p95 ([\d.]+) ms\), copy ([\d.]+) ms avg")
FIGURES_LINE = re.compile(r"figures: ([\d.]+) drawn of ([\d.]+) near \(max (\d+), cap (\w+)\), ([\d.]+) animated; client (\d+) fps")
DROPS = re.compile(r"relay: \w+ dropped (\w+) x (\d+)")


@dataclass
class PerfPorts(mpcheck.Ports):
    """mpcheck's ports for this run: the two host links, guests on port 0, one server port, the 9F frame names."""
    client_base: int = 0
    server_base: int = SERVER_PORT
    frame_prefix: str = FRAME_PREFIX
    links: dict = field(default_factory=lambda: dict(HOST_PORTS))

    def server(self, scenario: str) -> int:
        return self.server_base

    def client(self, name: str) -> int:
        return self.links.get(name, 0)

    def check(self) -> None:
        ports = [self.server_base, *self.links.values()]
        if mpcheck.FORBIDDEN_PORTS & set(ports) or len(set(ports)) != len(ports) or not all(1024 <= p <= 65535 for p in ports):
            raise mpcheck.MpError(f"unusable ports {ports}")
        if not self.frame_prefix.startswith("Local\\") or self.frame_prefix == "Local\\CyberblockFrame":
            raise mpcheck.MpError("the frame prefix must start with Local\\ and differ from the real game's")


class PerfEnv(mpcheck.Env):
    """mpcheck's Env with the clients' Java heap capped (CLIENT_HEAP): six clients must fit under the commit limit (rule 0.7). The
    server keeps its own JAVA_TOOL_OPTIONS (the devtools switch); its server.properties takes MAX_PLAYERS and its whitelist the extra
    guests too (the test template holds four)."""

    def _child_env(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        return super()._child_env(extra if extra is not None else {"JAVA_TOOL_OPTIONS": CLIENT_HEAP})

    def server(self, scenario: str, max_hosts: int = 1, join_password: bool = False) -> mpcheck.Server:
        render, names = mpcheck.render_properties, mpcheck.OFFLINE_NAMES
        mpcheck.render_properties = lambda template, overrides: render(template, {**overrides, "max-players": MAX_PLAYERS})
        mpcheck.OFFLINE_NAMES = tuple(dict.fromkeys((*names, *HOST_PORTS, *GUEST_NAMES)))
        try:
            return super().server(scenario, max_hosts, join_password)
        finally:
            mpcheck.render_properties, mpcheck.OFFLINE_NAMES = render, names


def parse_tick_query(text: str) -> dict | None:
    """`/tick query`'s answer as {"avg", "p50", "p95", "p99", "sample"} (milliseconds), None when it is not one."""
    avg, pct = TICK_AVG.search(text), TICK_PCT.search(text)
    if not (avg and pct):
        return None
    return {"avg": float(avg.group(1)), "p50": float(pct.group(1)), "p95": float(pct.group(2)), "p99": float(pct.group(3)), "sample": int(pct.group(4))}


def summarize_ticks(samples: list[dict]) -> dict:
    """Samples of one window: the mean of the averages and of the medians, the worst p95 and p99 (milliseconds)."""
    if not samples:
        return {}
    return {"samples": len(samples), "avg_ms": round(statistics.fmean(s["avg"] for s in samples), 2),
            "p50_ms": round(statistics.fmean(s["p50"] for s in samples), 2), "p95_max_ms": max(s["p95"] for s in samples),
            "p99_max_ms": max(s["p99"] for s in samples)}


def last_loads(text: str) -> dict[str, dict]:
    """Each load host's last `load:` report."""
    out = {}
    for m in LOAD_LINE.finditer(text):
        out[m.group(1)] = json.loads(m.group(2))
    return out


def export_lines(text: str) -> list[dict]:
    return [{"w": int(m.group(1)), "h": int(m.group(2)), "fps": float(m.group(3)), "readback_ms": float(m.group(4)), "readback_p95_ms": float(m.group(5)),
             "copy_ms": float(m.group(6))} for m in EXPORT_LINE.finditer(text)]


def figure_lines(text: str) -> list[dict]:
    return [{"drawn": float(m.group(1)), "near": float(m.group(2)), "max": int(m.group(3)), "cap": m.group(4), "animated": float(m.group(5)),
             "fps": int(m.group(6))} for m in FIGURES_LINE.finditer(text)]


def relay_drops(text: str) -> dict[str, int]:
    out: dict[str, int] = {}
    for m in DROPS.finditer(text):
        out[m.group(1)] = out.get(m.group(1), 0) + int(m.group(2))
    return out


def prepare_run_dir(d: Path, scale: float, figure_cap: int) -> Path:
    """A client run folder with options.txt and the Performance mode file (megaplan 9-F1)."""
    (d / "config" / "cyberblock").mkdir(parents=True, exist_ok=True)
    (d / "options.txt").write_text("\n".join(OPTIONS) + "\n", encoding="utf-8")
    (d / "config" / "cyberblock" / "performance.json").write_text(json.dumps({"scale": scale, "figureCap": figure_cap}), encoding="utf-8")
    return d


def loadhost_argv(name: str, port: int, frame: str, seconds: float) -> list[str]:
    return [sys.executable, "-u", "-m", "tools.loadhost", "--port", str(port), "--name", name, "--shm", frame, "--seconds", f"{seconds:g}",
            "--street-z", str(STREET_Z[name]), "--ids-from", str(IDS_FROM[name])]


def measure(env: mpcheck.Env, args: argparse.Namespace) -> dict:
    server = env.server("perf", max_hosts=2)
    names = [*HOST_PORTS, *GUEST_NAMES[:args.guests]]
    clients = {}
    for name in names:
        d = prepare_run_dir(env.run / f"client-{name}", args.scale, args.figure_cap)
        mark = server.log.mark()
        clients[name] = env.client(name, join=server.port, run_dir=d)
        server.log.wait(rf"{name} joined the game", mpcheck.T_CLIENT + mpcheck.T_JOIN, mark)
        env.say(f"{name} joined")
    hosts = {}
    total = BURST_S + args.seconds
    for name in HOST_PORTS:
        clients[name].wait_listening()
        argv = loadhost_argv(name, clients[name].port, clients[name].frame, total + 60)
        proc = mpcheck.Proc(f"loadhost-{name}", argv, env.repo, env._child_env(), env.run / "logs" / f"loadhost-{name}.log")
        env.started.append(proc)
        hosts[name] = proc
    for name in HOST_PORTS:
        server.log.wait(rf"session: {name} started", mpcheck.T_JOIN)
    env.say("both hosts started: measuring")
    start = time.monotonic()
    burst, steady, follow_at = [], [], 0.0
    while (elapsed := time.monotonic() - start) < total:
        if elapsed >= follow_at:
            follow_at += FOLLOW_S
            for i, guest in enumerate(GUEST_NAMES[:args.guests]):
                server.rcon(f"tp {guest} {list(HOST_PORTS)[i % 2]}")
        time.sleep(SAMPLE_S)
        sample = parse_tick_query(server.rcon("tick query"))
        if sample:
            sample["t"] = round(time.monotonic() - start, 1)
            (burst if sample["t"] <= BURST_S + 1 else steady).append(sample)
            env.say(f"  t={sample['t']:5.1f} s  tick avg {sample['avg']:.2f} ms  p95 {sample['p95']:.2f}  p99 {sample['p99']:.2f}")
    report = {"config": {"seconds": args.seconds, "scale": args.scale, "figure_cap": args.figure_cap, "guests": args.guests, "hosts": len(HOST_PORTS)},
              "tick": {"burst": summarize_ticks(burst), "steady": summarize_ticks(steady), "samples": burst + steady}}
    loads = {}
    for name, proc in hosts.items():
        loads.update(last_loads(proc.log.text()))
    report["load"] = loads
    report["export"] = {name: export_lines(clients[name].log.text()) for name in HOST_PORTS}
    report["figures"] = {name: figure_lines(clients[name].log.text()) for name in GUEST_NAMES[:args.guests]}
    report["relay_drops"] = relay_drops(server.log.text())
    return report


def main(argv: list[str] | None = None, repo: Path = mpcheck.ROOT) -> int:
    with contextlib.suppress(AttributeError, ValueError):
        sys.stdout.reconfigure(line_buffering=True)
    ap = argparse.ArgumentParser(description="Megaplan 9-F1 measurements: 2 load hosts and 4 guests on a test server.")
    ap.add_argument("--out", type=Path, required=True, help="where the JSON report goes")
    ap.add_argument("--seconds", type=float, default=60.0, help="steady seconds after the burst (default 60)")
    ap.add_argument("--scale", type=float, default=1.0, help="Performance mode frame scale written for every client (0.5..1)")
    ap.add_argument("--figure-cap", type=int, default=48, help="Performance mode figure cap written for every client (16..48)")
    ap.add_argument("--guests", type=int, default=4, choices=range(0, 5))
    ap.add_argument("--keep", action="store_true", help="keep the run folder")
    args = ap.parse_args(argv)
    if not 0.5 <= args.scale <= 1.0 or not 16 <= args.figure_cap <= 48:
        ap.error("--scale is 0.5..1 and --figure-cap 16..48")
    if not (repo / ".git").is_file():
        print("run it from a git worktree: it runs Gradle (megaplan rule 0.2)", file=sys.stderr)
        return 2
    java = play.find_jdk(play.default_jdk_candidates())
    if java is None:
        print("no JDK found: set JAVA_HOME", file=sys.stderr)
        return 1
    env = PerfEnv(repo, PerfPorts(), java, keep=args.keep)
    try:
        report = measure(env, args)
    except KeyboardInterrupt:
        return 130
    except mpcheck.MpError as e:
        print(f"perfcheck: {e}", file=sys.stderr)
        for label, log in env.logs.items():
            print(f"--- {label}\n{log.tail(20)}", file=sys.stderr)
        return 1
    finally:
        env.close()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=1), encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("config", "load", "relay_drops")} | {"tick": {k: report["tick"][k] for k in ("burst", "steady")}}, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
