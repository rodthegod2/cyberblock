"""Multiplayer harness: a dedicated Cyberblock server, offline clients and fake hosts, scenarios with assertions.

    uv run python -m tools.mpcheck --all                 # every scenario, in order (the integrator's run)
    uv run python -m tools.mpcheck guest versions        # some of them
    uv run python -m tools.mpcheck --guest <LAN port>    # ONE offline guest client for a world opened to LAN (see Limit below)
    uv run python -m tools.mpcheck --all --dry-run       # print the commands, start nothing

Run it from a git worktree, never from the main checkout: it runs Gradle (`runClient`, `runServer`), and nothing may write
`mc/build`, `mc/.gradle` or `mc/run` of the folder the user plays from (megaplan rule 0.2). Everything it starts runs in a
throw-away run folder under %TEMP% and is stopped when it ends (only processes it started are ever stopped).

Scenarios (megaplan task 1-T2). Each one starts what it needs on its own test port; the defaults are package 1T's:
  singleplayer  one client, its integrated server, the existing fake host modes through the relay
  guest         a host (client + fake host) and a guest: the ground (replica blocks), proxies, a guest hit and explosion reach the fake host,
                the guest toggles block them
  versions      the DLL version mismatch (guest, `session refused`) and the mod version mismatch (disconnect message)
  flood         a DLL link that floods `ground`: rate limits, the overflow kick, the server survives
  reconnect     the host's DLL link drops (proxies gone, the ground stays as replica blocks with no barrier) and returns
                (`blocks reset:true`, the whole ground again)
  twohosts      `maxHosts` 2, two fake hosts with colliding ids: each one gets only its own hits
  replica       Phase 3: one fake host `--replica` and a guest: the street stands as replica blocks of the right materials, a `seen`
                removal takes one scaffold away and a plain one keeps the other, `clear` removes nothing, the car V destroyed (`killed`)
                stays a wreck about 5 s, the host's clock and weather
                drive the world, the replica stays when the host's link drops and a guest's wall reaches the returning host
  twohosts2     Phase 3: two replica hosts that overlap, with colliding ids: both streets and both figure sets stand, a hit reaches only
                its owner, and only the first host's clock drives the world
  meetup        Phase 3: Meet Up both ways with a fake host answering `vteleport` (the cooldown too)
  figures       Phase 5: one fake host `--figures` (the tagged `npcs` list: a person per skin group, every animation bit, coloured cars, a police
                chase): every person and car stands as a proxy, the dead victim's body goes and does not come back, a hit on a tagged figure
                reaches the host, the figures go with the session and the relay drops nothing
  joinpassword  a server with a join password: a host waits as a guest until `/login`, then hosts at once; after a reconnect it
                is remembered; five wrong passwords kick; the password is in no log (it is random per run, like the RCON one)

Safety: the commit charge must be under 60 GB before anything starts (it waits 60 s and checks again); the server is offline
mode on 127.0.0.1; its RCON password is random per run, held in memory and written only to server.properties in the run
folder (it is never printed, logged or put on a command line); APEX_FTP_PASS is removed from the environment of every child;
port 25600 and `Local\\CyberblockFrame` are never used. The scenarios assume the plan's devtools commands (task 1-N4,
`-Dcyberblock.devtools=true`): `/cyberblock test hit <host> <id> <dmg>`, `/cyberblock test explode <x> <y> <z> <power>` and
`/cyberblock test place <x> <y> <z> <block>`
run as the guest with `/execute as CbGuest1 run ...` (from the RCON console alone there is no player behind them, and an action
with no player behind it is always allowed), and `/execute as <host> run cyberblock guests <toggle> on|off` flips that host's
toggle. They also assume that `runServer` and `runClient` honour `-Pcyberblock.runDir` (task 1-L1) and that the dedicated
server's world is `<run dir>/world`. The harness writes the whole server.properties (RCON included) and eula.txt itself, so it
skips 1L's `cyberblockServerFiles` task (`-x`), which would rewrite server.properties from the template and drop the RCON lines.

`--guest` starts an OFFLINE client, and a world opened to LAN in 26.3 authenticates every remote login
(IntegratedServer.initServer calls setUsesAuthentication(true) and publishServer keeps it), so it refuses this guest
("Invalid session") until the world's owner types `/cyberblock lan offline on` (LanAccess). The automatic guest checks run in
the `guest` scenario on the offline test server.
"""
import argparse
import asyncio
import contextlib
import ctypes
import hashlib
import json
import math
import os
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from pathlib import Path

from tools import cbmath, fakehost, play

ROOT = Path(__file__).resolve().parents[1]
COMMIT_LIMIT_GB, COMMIT_RECHECK_S, COMMIT_MAX_WAIT_S = 60.0, 60.0, 1800.0  # megaplan rule 0.7 and the task text
CLIENT_BASE, SERVER_BASE = 25754, 25914  # package 1T (megaplan section 1.15): client 25754.., harness servers 25914-25918
FRAME_PREFIX = "Local\\CyberblockFrame1T"  # + H (host client) or G (guest client), + the client number from 2 on
OFFLINE_NAMES = ("CbHost1", "CbHost2", "CbGuest1", "CbGuest2")
SCENARIOS = ("singleplayer", "guest", "versions", "flood", "reconnect", "twohosts", "joinpassword", "replica", "twohosts2", "meetup", "figures")
# + SERVER_BASE; scenarios run one after another, so two may share a port
SERVER_SLOT = {"singleplayer": 0, "guest": 0, "versions": 1, "flood": 2, "reconnect": 3, "twohosts": 4, "joinpassword": 1, "replica": 0, "twohosts2": 2,
               "meetup": 1, "figures": 3}
FORBIDDEN_PORTS = {25600}  # the user's own Minecraft <-> Cyberpunk link
GRADLE_HEAP = "-Dorg.gradle.jvmargs=-Xmx1536m"
# seconds: a server starting, a client opening its link port, a join, an effect to show up, "this must not happen"
T_SERVER, T_CLIENT, T_JOIN, T_EFFECT, T_QUIET = 300.0, 400.0, 150.0, 45.0, 6.0
T_STOP = 60.0  # a server gets this long to stop after RCON `stop` before its process tree is killed
HOLD_S = 900 # a fake host keeps its link this long (the scenario stops it earlier)
T_ENV = 60.0  # `replica`: the env cycle of the fake host takes 40 s, so each of its states shows up within this
T_POLL = 0.5  # seconds between two looks of a poll
T_WRECK, WRECK_MIN_S = 10.0, 2.5  # `replica`: a wreck (5 s on the server) goes within T_WRECK of its log line and stands at least WRECK_MIN_S
DAY_TICKS = 24000
COOLDOWN_S = 31.0  # `meetup`: the Meet Up cooldown is 30 s (megaplan 3-E2)
FLOOD_S = 16.0  # how long `flood` keeps flooding (the overflow rule needs more than 10 s)
LOGIN_GAP_S = 1.2  # `joinpassword`: between two /login attempts (the server takes one per second)
HOSTILE_ID = "9007199254740993"  # the fake host's hostile human (protocol.md example id)
COLLIDING_ID = 5000  # `twohosts`: both fake hosts number their NPCs from here
SLAB = (0, 63, 0)  # a cell of the fake host's ground and its four far corners (x, y, z)
SLAB_CORNERS = ((-8, 63, -8), (8, 63, 8), (-8, 63, 8), (8, 63, -8))


class MpError(Exception):
    """The harness cannot go on (a missing tool, a busy port, a dead process); the message says what to do."""


class Timeout(MpError):
    pass


class Abort(Exception):
    """A prerequisite of a scenario failed: the rest of that scenario is skipped."""


# ---- system commit (megaplan rule 0.7) -------------------------------------------------------------------------------
def commit_gb() -> float:
    """The system commit charge in GB: TotalVirtualMemorySize - FreeVirtualMemory of Win32_OperatingSystem."""
    class Status(ctypes.Structure):
        _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong), ("total_phys", ctypes.c_ulonglong), ("avail_phys", ctypes.c_ulonglong),
                    ("total_page", ctypes.c_ulonglong), ("avail_page", ctypes.c_ulonglong), ("total_virtual", ctypes.c_ulonglong),
                    ("avail_virtual", ctypes.c_ulonglong), ("avail_extended", ctypes.c_ulonglong)]

    status = Status()
    status.length = ctypes.sizeof(status)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
        raise MpError("cannot read the system commit charge (GlobalMemoryStatusEx failed)")
    return (status.total_page - status.avail_page) / 2**30


def wait_for_commit(limit: float = COMMIT_LIMIT_GB, read: Callable[[], float] = commit_gb, sleep: Callable[[float], None] = time.sleep,
                    recheck: float = COMMIT_RECHECK_S, max_wait: float = COMMIT_MAX_WAIT_S, say: Callable[[str], None] = print) -> float:
    """Return the commit charge once it is at most `limit` GB; above it, wait `recheck` s and look again (MpError after `max_wait`)."""
    waited = 0.0
    while True:
        used = read()
        if used <= limit:
            return used
        if waited >= max_wait:
            raise MpError(f"the system commit stayed above {limit:.0f} GB for {max_wait:.0f} s (now {used:.1f} GB): close something and run again")
        say(f"commit {used:.1f} GB is above {limit:.0f} GB: waiting {recheck:.0f} s")
        sleep(recheck)
        waited += recheck


# ---- files the run folder needs ---------------------------------------------------------------------------------------
def offline_uuid(name: str) -> str:
    """The UUID an offline-mode server gives a player name: the name-based (v3) UUID of "OfflinePlayer:<name>"."""
    digest = bytearray(hashlib.md5(f"OfflinePlayer:{name}".encode()).digest())
    digest[6] = digest[6] & 0x0F | 0x30
    digest[8] = digest[8] & 0x3F | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def render_properties(template: str, overrides: dict[str, str | int | bool]) -> str:
    """The template's server.properties with each override replacing the line of its key (or appended)."""
    values = {k: (str(v).lower() if isinstance(v, bool) else str(v)) for k, v in overrides.items()}
    out, seen = [], set()
    for line in template.splitlines():
        key = line.split("=", 1)[0].strip()
        if not line.lstrip().startswith("#") and "=" in line and key in values:
            out.append(f"{key}={values[key]}")
            seen.add(key)
        else:
            out.append(line)
    out += [f"{k}={v}" for k, v in values.items() if k not in seen]
    return "\n".join(out) + "\n"


def mod_version(repo: Path) -> str:
    """The mod version the worktree builds (mc/gradle.properties)."""
    match = re.search(r"^version=(\S+)", (repo / "mc" / "gradle.properties").read_text(encoding="utf-8"), re.M)
    if not match:
        raise MpError(f"no version= in {repo / 'mc' / 'gradle.properties'}")
    return match.group(1)


def other_version(version: str) -> str:
    """The next minor version: the server must refuse a mod of it (versions that differ in major.minor)."""
    major, minor, _ = (int(x) for x in re.match(r"(\d+)\.(\d+)\.(\d+)", version).groups())
    return f"{major}.{minor + 1}.0"


def mismatched_dll_version(version: str) -> str:
    return "0.0.1" if version != "0.0.1" else "0.0.2"


def server_json(example: str, max_hosts: int, join_password: str = "") -> str:
    """config/cyberblock/server.json: the defaults of the template with `maxHosts` (and `joinPassword` when given) set."""
    config = json.loads(example)
    config["maxHosts"] = max_hosts
    if join_password:
        config["joinPassword"] = join_password
    return json.dumps(config, indent=2) + "\n"


# ---- command lines ----------------------------------------------------------------------------------------------------
def gradle(repo: Path, task: str, props: dict[str, str | int | Path], args: str | None = None) -> list[str]:
    """`gradlew.bat --no-daemon runClient|runServer -P... [--args=...]` for the mc/ project of `repo` (a worktree)."""
    return [str(repo / "mc" / "gradlew.bat"), "--no-daemon", GRADLE_HEAP, task, *(f"-P{k}={v}" for k, v in props.items()),
            *([f"--args={args}"] if args else [])]


def client_argv(repo: Path, *, name: str, port: int, frame: str, run_dir: Path, join: int | None = None, world: str | None = None,
                version: str | None = None) -> list[str]:
    """The dev client for offline player `name`: it joins 127.0.0.1:`join` (a server) or opens saved world `world`."""
    target = f"--quickPlayMultiplayer 127.0.0.1:{join}" if join is not None else f"--quickPlaySingleplayer {world}"
    props = {"cyberblock.port": port, "cyberblock.frame": frame, "cyberblock.runDir": run_dir, **({"version": version} if version else {})}
    return gradle(repo, "runClient", props, f"--username {name} {target}")


def server_argv(repo: Path, *, port: int, run_dir: Path) -> list[str]:
    """`runServer` without 1L's `cyberblockServerFiles`: that task rewrites server.properties from the template and would drop
    the RCON lines (and port) Env.server already wrote into the run folder."""
    return [*gradle(repo, "runServer", {"cyberblock.serverPort": port, "cyberblock.runDir": run_dir}), "-x", "cyberblockServerFiles"]


def fakehost_argv(*, port: int, name: str, ver: str | None, frame: str, out_dir: Path, ids_from: int | None = None, hold: float = HOLD_S,
                  layers: Iterable[int] = (2,), modes: Iterable[str] = (), cmds: Iterable[str] = (), no_setup: bool = True, frames: int = 60,
                  wait_host: float | None = None, shift: int = 0, env_hours: int = 0) -> list[str]:
    """tools.fakehost as one fake host: its own link port, log prefix, frame name and DLL version. `-u`: its output goes to a
    log file that the scenarios read while it runs, so it must not sit in a buffer until the process ends."""
    argv = [sys.executable, "-u", "-m", "tools.fakehost", "--port", str(port), "--name", name, "--shm", frame, "--out", str(out_dir),
            "--frames", str(frames), "--hold", f"{hold:g}", "--ver", ver if ver is not None else ""]
    argv += [f"--layer{n}" for n in layers] + [f"--{m}" for m in modes]
    argv += ["--no-setup"] if no_setup else []
    argv += ["--ids-from", str(ids_from)] if ids_from is not None else []
    argv += ["--wait-host", f"{wait_host:g}"] if wait_host is not None else []
    argv += ["--shift", str(shift)] if shift else []
    argv += ["--env-hours", str(env_hours)] if env_hours else []
    for command in cmds:
        argv += ["--cmd", command]
    return argv


# ---- the log files and RCON -------------------------------------------------------------------------------------------
def poll(fn: Callable[[], object], timeout: float, interval: float = 0.5, what: str = "the condition") -> object:
    """Call `fn` until it returns something truthy (returned), or raise Timeout."""
    end = time.monotonic() + timeout
    while True:
        if result := fn():
            return result
        if time.monotonic() >= end:
            raise Timeout(f"timed out after {timeout:.0f} s waiting for {what}")
        time.sleep(interval)


class LogFile:
    """A growing text file (a process's output). Offsets are byte positions from `mark()`, so a check can ignore old lines."""

    def __init__(self, path: Path, alive: Callable[[], bool] | None = None) -> None:
        self.path, self.alive = path, alive or (lambda: True)

    def text(self, since: int = 0) -> str:
        try:
            with self.path.open("rb") as f:
                f.seek(since)
                return f.read().decode("utf-8", "replace")
        except OSError:
            return ""

    def mark(self) -> int:
        try:
            return self.path.stat().st_size
        except OSError:
            return 0

    def find(self, pattern: str, since: int = 0) -> re.Match | None:
        return re.search(pattern, self.text(since))

    def wait(self, pattern: str, timeout: float, since: int = 0) -> re.Match:
        """The first match of `pattern` after `since`; Timeout when it does not come, MpError when the process died first."""
        end = time.monotonic() + timeout
        while True:
            if match := self.find(pattern, since):
                return match
            if not self.alive():
                if match := self.find(pattern, since):  # its last words
                    return match
                raise MpError(f"the process of {self.path.name} ended before /{pattern}/ appeared")
            if time.monotonic() >= end:
                raise Timeout(f"timed out after {timeout:.0f} s waiting for /{pattern}/ in {self.path.name}")
            time.sleep(0.2)

    def quiet(self, pattern: str, seconds: float, since: int = 0) -> bool:
        """True when `pattern` does not show up in the next `seconds` (and was not there already since `since`)."""
        time.sleep(seconds)
        return self.find(pattern, since) is None

    def tail(self, lines: int = 30) -> str:
        return "\n".join(self.text().splitlines()[-lines:])


def rcon_packet(request: int, kind: int, body: str) -> bytes:
    data = struct.pack("<ii", request, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def rcon_read(sock: socket.socket) -> tuple[int, int, str]:
    def exactly(n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("RCON closed the connection")
            buf += chunk
        return buf

    (length,) = struct.unpack("<i", exactly(4))
    data = exactly(length)
    request, kind = struct.unpack("<ii", data[:8])
    return request, kind, data[8:-2].decode("utf-8", "replace")


def rcon(port: int, password: str, command: str, timeout: float = 15.0) -> str:
    """One RCON command (login, command, close) on 127.0.0.1:`port`; returns the answer text."""
    with socket.create_connection(("127.0.0.1", port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        sock.sendall(rcon_packet(1, 3, password))
        request, _, _ = rcon_read(sock)
        if request == -1:
            raise MpError("the server refused the RCON login (is runServer using the run folder's server.properties? task 1-L1)")
        sock.sendall(rcon_packet(2, 2, command))
        return rcon_read(sock)[2]


def passed(answer: str) -> bool:
    """`execute if ...` answers `Test passed` (with a count for entities) or `Test failed`."""
    return answer.lstrip().startswith("Test passed")


# ---- processes --------------------------------------------------------------------------------------------------------
class Proc:
    """One child the harness started: its output goes to a log file; stop() kills its whole process tree (nothing else)."""

    def __init__(self, name: str, argv: list[str], cwd: Path, env: dict[str, str], log_path: Path) -> None:
        self.name = name
        log_path.parent.mkdir(parents=True, exist_ok=True)
        self._out = log_path.open("wb")
        flags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)  # Ctrl+C reaches the harness only; it stops the tree itself
        self.popen = subprocess.Popen(argv, cwd=cwd, env=env, stdout=self._out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, creationflags=flags)
        self.log = LogFile(log_path, self.alive)

    def alive(self) -> bool:
        return self.popen.poll() is None

    def stop(self) -> None:
        with contextlib.suppress(Exception):
            play.stop(self.popen)
        with contextlib.suppress(Exception):
            self._out.close()


@dataclass
class Server:
    port: int
    rcon_port: int
    password: str = field(repr=False)  # never in a repr, a traceback or a report
    proc: Proc | None
    log: LogFile
    dir: Path
    join_password: str = field(default="", repr=False)  # `joinpassword` only; never in a repr or a report

    def rcon(self, command: str) -> str:
        return rcon(self.rcon_port, self.password, command)

    def passes(self, command: str) -> bool:
        return passed(self.rcon(command))

    def test(self, command: str, as_player: str | None = None) -> str:
        """A `/cyberblock test ...` devtools command, run as player `as_player` (`execute as <player> run ...`) or from the
        console (no player behind it: always allowed); fails loudly when the server does not know it or the player is not online."""
        full = f"cyberblock test {command}" if as_player is None else f"execute as {as_player} run cyberblock test {command}"
        answer = self.rcon(full)
        if re.search(r"unknown or incomplete|incorrect argument|unknown command", answer, re.I):
            raise MpError(f"the server refused `{full}`: is -Dcyberblock.devtools=true honoured and task 1-N4 merged? ({answer.strip()[:120]})")
        if re.search(r"no entity was found", answer, re.I):
            raise MpError(f"`{full}`: {as_player} is not on the server")
        return answer


@dataclass
class Client:
    name: str
    port: int
    frame: str
    proc: Proc
    log: LogFile

    def wait_listening(self, timeout: float | None = None) -> None:
        end = time.monotonic() + (T_CLIENT if timeout is None else timeout)
        while not play.port_open(self.port):
            if not self.proc.alive():
                raise MpError(f"the client {self.name} ended before opening its link port {self.port}; last log lines:\n{self.log.tail()}")
            if time.monotonic() >= end:
                raise Timeout(f"timed out waiting for the client {self.name} to open its link port {self.port}")
            time.sleep(1.0)


@dataclass
class FakeHost:
    name: str
    proc: Proc
    log: LogFile
    client: Client

    def stop(self) -> None:
        self.proc.stop()


# ---- the environment: run folder, ports, everything started ------------------------------------------------------------
@dataclass
class Ports:
    client_base: int = CLIENT_BASE
    server_base: int = SERVER_BASE
    frame_prefix: str = FRAME_PREFIX

    def server(self, scenario: str) -> int:
        return self.server_base + SERVER_SLOT[scenario]

    def client(self, name: str) -> int:
        return self.client_base + OFFLINE_NAMES.index(name)

    def frame(self, name: str) -> str:
        number = int(name[-1])
        return f"{self.frame_prefix}{'H' if name.startswith('CbHost') else 'G'}{number if number > 1 else ''}"

    def check(self) -> None:
        """Refuse the user's own link (25600) and the default frame name; ports must be unprivileged and not collide."""
        ports = [self.server_base + i for i in range(5)] + [self.client_base + i for i in range(len(OFFLINE_NAMES))]
        if FORBIDDEN_PORTS & set(ports) or not all(1024 <= p <= 65535 for p in ports) or len(set(ports)) != len(ports):
            raise MpError(f"unusable ports {sorted(ports)}: never 25600, 1024..65535 only, no overlap")
        names = [self.frame(n) for n in OFFLINE_NAMES]
        if not self.frame_prefix.startswith("Local\\") or "Local\\CyberblockFrame" in names or len(set(names)) != len(names):
            raise MpError(f"the frame names {names} must start with Local\\, differ from Local\\CyberblockFrame and from each other")


class Env:
    """Everything one run starts, in a throw-away folder; stop_all() stops it (reverse order), close() also removes the folder."""

    def __init__(self, repo: Path, ports: Ports, java_home: Path | None, keep: bool = False, say: Callable[[str], None] = print,
                 commit_limit: float = COMMIT_LIMIT_GB, run_root: Path | None = None) -> None:
        ports.check()
        self.repo, self.ports, self.java_home, self.keep, self.say, self.commit_limit = repo, ports, java_home, keep, say, commit_limit
        self.run = Path(tempfile.mkdtemp(prefix="cbmp-", dir=run_root))
        self._password = secrets.token_urlsafe(18)  # in memory, and in server.properties of the run folder: never printed or logged
        self.mod_version = mod_version(repo)
        self.started: list[Proc] = []
        self.servers: list[Server] = []
        self.logs: dict[str, LogFile] = {}
        self._serials = 0

    def __repr__(self) -> str:
        return f"Env(run={self.run})"  # no password

    # -- children -----------------------------------------------------------------------------------------------------
    def _child_env(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        env = {**os.environ, **(extra or {})}
        env.pop("APEX_FTP_PASS", None)  # the hosting password never reaches anything this harness starts
        if self.java_home:
            env["JAVA_HOME"] = str(self.java_home)
        return env

    @staticmethod
    def _free_port() -> int:
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            return s.getsockname()[1]

    @staticmethod
    def _require_free(port: int, what: str) -> None:
        if play.port_open(port):
            raise MpError(f"port {port} ({what}) is already in use: not touching whatever listens there")

    def server(self, scenario: str, max_hosts: int = 1, join_password: bool = False) -> Server:
        """A dedicated test server on this scenario's port (offline mode, whitelist of the offline names, RCON on a free port);
        with `join_password`, a random one in server.json (held like the RCON password: never printed or logged)."""
        port = self.ports.server(scenario)
        self._require_free(port, "test server")
        serial = self._serial()
        d = self.run / f"server-{port}-{serial}"
        (d / "config" / "cyberblock").mkdir(parents=True)
        rcon_port = self._free_port()
        template = (self.repo / "server" / "test-server.properties").read_text(encoding="utf-8")
        (d / "server.properties").write_text(render_properties(template, {"server-port": port, "enable-rcon": True, "rcon.port": rcon_port,
                                                                          "rcon.password": self._password, "broadcast-rcon-to-ops": False}), encoding="utf-8")
        (d / "eula.txt").write_text((self.repo / "server" / "eula.txt.template").read_text(encoding="utf-8"), encoding="utf-8")
        (d / "whitelist.json").write_text(json.dumps([{"uuid": offline_uuid(n), "name": n} for n in OFFLINE_NAMES], indent=2), encoding="utf-8")
        join = secrets.token_urlsafe(12) if join_password else ""
        (d / "config" / "cyberblock" / "server.json").write_text(
            server_json((self.repo / "server" / "server.json.example").read_text(encoding="utf-8"), max_hosts, join), encoding="utf-8")
        wait_for_commit(self.commit_limit, say=self.say)
        self.say(f"starting the test server on 127.0.0.1:{port} (maxHosts {max_hosts}{', /login required' if join else ''})")
        proc = Proc(f"server-{port}", server_argv(self.repo, port=port, run_dir=d), self.repo / "mc",
                    self._child_env({"JAVA_TOOL_OPTIONS": "-Dcyberblock.devtools=true"}), self.run / "logs" / f"server-{port}-{serial}.log")
        self.started.append(proc)
        server = Server(port, rcon_port, self._password, proc, proc.log, d, join)
        self.servers.append(server)
        self.logs[f"server {port}"] = proc.log
        proc.log.wait(r"Done \(", T_SERVER)
        poll(lambda: self._rcon_up(server), 60, 1.0, "the server's RCON")
        return server

    @staticmethod
    def _rcon_up(server: Server) -> bool:
        try:
            server.rcon("list")
            return True
        except MpError as e:
            if "refused the RCON login" in str(e):
                raise
            return False
        except OSError:
            return False

    def _serial(self) -> int:
        self._serials += 1
        return self._serials

    def client(self, name: str, *, join: int | None = None, world: str | None = None, version: str | None = None, run_dir: Path | None = None) -> Client:
        port, frame = self.ports.client(name), self.ports.frame(name)
        self._require_free(port, f"the link port of {name}'s client")
        serial = self._serial()
        d = run_dir or self.run / f"client-{name}-{serial}"
        d.mkdir(parents=True, exist_ok=True)
        wait_for_commit(self.commit_limit, say=self.say)
        self.say(f"starting the offline client {name} (link port {port}, frame {frame})")
        proc = Proc(f"client-{name}", client_argv(self.repo, name=name, port=port, frame=frame, run_dir=d, join=join, world=world, version=version),
                    self.repo / "mc", self._child_env(), self.run / "logs" / f"client-{name}-{serial}.log")
        self.started.append(proc)
        self.logs[f"client {name}"] = proc.log
        return Client(name, port, frame, proc, proc.log)

    def fakehost(self, client: Client, *, ver: str | None, ids_from: int | None = None, layers: Iterable[int] = (2,), modes: Iterable[str] = (),
                 cmds: Iterable[str] = (), hold: float = HOLD_S, no_setup: bool = True, wait_host: float | None = None, shift: int = 0,
                 env_hours: int = 0) -> FakeHost:
        """A fake Cyberpunk host connected to `client`'s link (waits until the client listens)."""
        client.wait_listening()
        argv = fakehost_argv(port=client.port, name=client.name, ver=ver, frame=client.frame, out_dir=self.run / f"frames-{client.name}", ids_from=ids_from,
                             hold=hold, layers=layers, modes=modes, cmds=cmds, no_setup=no_setup, wait_host=wait_host, shift=shift, env_hours=env_hours)
        proc = Proc(f"fakehost-{client.name}", argv, self.repo, self._child_env(), self.run / "logs" / f"fakehost-{client.name}-{len(self.started)}.log")
        self.started.append(proc)
        self.logs[f"fake host {client.name}"] = proc.log
        return FakeHost(client.name, proc, proc.log, client)

    def make_world(self) -> tuple[str, Path]:
        """A small flat world for the singleplayer scenario: a short dedicated-server run saves it, it is copied into the
        saves folder of a new client run folder. Returns (the world's name, that run folder: pass it to `client(run_dir=)`)."""
        server = self.server("singleplayer")
        server.rcon("save-all flush")
        self.stop_server(server)
        client_dir = self.run / "client-singleplayer"
        (client_dir / "saves").mkdir(parents=True, exist_ok=True)
        shutil.copytree(server.dir / "world", client_dir / "saves" / "cbmp-world")
        return "cbmp-world", client_dir

    # -- stopping -------------------------------------------------------------------------------------------------------
    @staticmethod
    def stop_server(server: Server) -> None:
        """Ask the server to stop (RCON `stop`, 60 s), then kill its process tree."""
        with contextlib.suppress(Exception):
            server.rcon("stop")
        if server.proc:
            end = time.monotonic() + T_STOP
            while server.proc.alive() and time.monotonic() < end:
                time.sleep(0.5)
            server.proc.stop()

    def stop_all(self) -> None:
        """Stop everything started so far: the fake hosts and clients, then the servers (RCON `stop` first)."""
        for proc in reversed(self.started):
            if proc not in [s.proc for s in self.servers]:
                proc.stop()
        for server in reversed(self.servers):
            self.stop_server(server)
        self.started, self.servers = [], []

    def close(self) -> None:
        self.stop_all()
        if self.keep:
            self.say(f"kept the run folder {self.run} (it holds server.properties; delete it when done)")
        else:
            shutil.rmtree(self.run, ignore_errors=True)


# ---- reports -----------------------------------------------------------------------------------------------------------
class Report:
    """The checks of one scenario; every check is printed as it is made."""

    def __init__(self, name: str, say: Callable[[str], None] = print) -> None:
        self.name, self.say, self.checks = name, say, []

    def step(self, label: str, fn: Callable[[], object], must: bool = False) -> bool:
        try:
            ok, detail = bool(fn()), ""
        except (MpError, OSError) as e:
            ok, detail = False, (str(e).splitlines() or [type(e).__name__])[0]
        self.checks.append((label, ok, detail))
        self.say(f"  {'PASS' if ok else 'FAIL'}  {self.name}: {label}" + (f"  ({detail})" if detail else ""))
        if must and not ok:
            raise Abort(label)
        return ok

    @property
    def ok(self) -> bool:
        return bool(self.checks) and all(ok for _, ok, _ in self.checks)


# ---- the scenarios -----------------------------------------------------------------------------------------------------
def host_pattern(name: str) -> str:
    return rf"handshake: {name} .*-> host"


def hit_pattern(name: str, target: str) -> str:
    """The line a fake host prints for a `hit` on `target` (its `--name` prefix, then the message as JSON)."""
    return rf'\[{name}\] hit: .*"target": "{target}"'


def explosion_pattern(name: str) -> str:
    return rf"\[{name}\] explosion: "


def start_host(env: Env, rep: Report, server: Server, name: str, *, ver: str | None, ids_from: int | None = None, **fake) -> tuple[Client, FakeHost]:
    """Join `server` as `name`, link a fake host (`fake`: more arguments of Env.fakehost, such as `modes` and `shift`) and wait until the
    server made it a host."""
    client = env.client(name, join=server.port)
    host = env.fakehost(client, ver=ver, ids_from=ids_from, **fake)
    rep.step(f"{name} is a host", lambda: server.log.wait(host_pattern(name), T_JOIN), must=True)
    rep.step(f"{name}: session started", lambda: server.log.wait(rf"session: {name} started", T_EFFECT))
    return client, host


def ground_stands(server: Server, cells: Iterable[tuple[int, int, int]] = (SLAB,)) -> bool:
    """The fake host's ground fills these cells. From Phase 3 (3-R1) host ground is replica blocks whose material the palette
    picks, so any block counts: the test world is empty there otherwise."""
    return all(server.passes(f"execute unless block {x} {y} {z} minecraft:air") for x, y, z in cells)


def barrier_left(server: Server, cells: Iterable[tuple[int, int, int]]) -> bool:
    return any(server.passes(f"execute if block {x} {y} {z} minecraft:barrier") for x, y, z in cells)


def proxies_stand(server: Server) -> bool:
    return server.passes("execute if entity @e[type=cyberblock:proxy_human]")  # ProxyEntities.HUMAN


def scenario_singleplayer(env: Env, rep: Report) -> None:
    world, client_dir = env.make_world()
    client = env.client("CbHost1", world=world, run_dir=client_dir)
    # the link opens at the title screen, long before the world: the scenario (the TNT command, the hack key) waits for the
    # client's `session host`, which comes once the integrated server made this player the host
    host = env.fakehost(client, ver=env.mod_version, layers=(2, 3, 6), modes=("ui", "hacks"), cmds=["summon tnt 3 64 0"], hold=30, no_setup=False,
                        wait_host=T_JOIN)
    rep.step("the client joined its own world as the host", lambda: client.log.wait(r"handshake: server \S+ -> host", T_JOIN), must=True)
    rep.step("the fake host ran to its end", lambda: poll(lambda: not host.proc.alive(), 30 + T_EFFECT + 60, 1.0, "the fake host to finish"))
    text = host.log.text()
    for label, pattern in (("the DLL is told it is the host (`session`)", r'\[CbHost1\] session: .*"state": "host"'),
                           ("the ground alignment arrives (`align`)", r"\[CbHost1\] align: "),
                           ("the block resync starts with `blocks reset:true`", r'\[CbHost1\] blocks: .*"reset": true'),
                           ("the perks arrive after the hello", r"\[CbHost1\] perks: "),
                           ("the cyberware list arrives after the hello", r"\[CbHost1\] cyberware: "),
                           ("the six binds arrive", r'\[CbHost1\] binds: .*"camo"'),
                           ("a TNT explosion reaches the DLL", explosion_pattern("CbHost1")),
                           ("the hack key makes a `scan` reach the DLL", r"\[CbHost1\] scan: ")):
        rep.step(label, lambda pattern=pattern: re.search(pattern, text))


def scenario_guest(env: Env, rep: Report) -> None:
    server = env.server("guest")
    _, host = start_host(env, rep, server, "CbHost1", ver=env.mod_version)
    rep.step("the ground is there (replica blocks)", lambda: poll(lambda: ground_stands(server, SLAB_CORNERS + (SLAB,)), T_EFFECT, 1.0, "the ground"))
    rep.step("the NPCs are there as proxies", lambda: poll(lambda: proxies_stand(server), T_EFFECT, 1.0, "proxies"))
    env.client("CbGuest1", join=server.port)
    rep.step("the second client joined as a guest", lambda: server.log.wait(r"handshake: CbGuest1 .*-> guest", T_JOIN), must=True)
    rep.step("the guest has no session", lambda: not server.log.find(r"session: CbGuest1 started"))

    def send_hit() -> None:  # as the guest: the toggles apply to a guest's actions only
        server.test(f"hit CbHost1 {HOSTILE_ID} 5.0", as_player="CbGuest1")

    def send_explosion() -> None:
        server.test("explode 3.5 64.0 0.5 4.0", as_player="CbGuest1")

    def arrives(send: Callable[[], None], pattern: str) -> bool:
        mark = host.log.mark()
        send()
        return bool(host.log.wait(pattern, T_EFFECT, mark))

    def blocked(send: Callable[[], None], pattern: str) -> bool:
        mark = host.log.mark()
        send()
        return host.log.quiet(pattern, T_QUIET, mark)

    def toggle(name: str, on: bool) -> None:
        server.rcon(f"execute as CbHost1 run cyberblock guests {name} {'on' if on else 'off'}")

    hit, boom = hit_pattern("CbHost1", HOSTILE_ID), explosion_pattern("CbHost1")
    rep.step("a guest hit reaches the fake host (`hit` for its proxy)", lambda: arrives(send_hit, hit))
    rep.step("a guest explosion reaches the fake host (`explosion`)", lambda: arrives(send_explosion, boom))
    toggle("damage", False)
    rep.step("with `guests damage off` the hit does not reach it", lambda: blocked(send_hit, hit))
    toggle("damage", True)
    rep.step("with `guests damage on` it reaches it again", lambda: arrives(send_hit, hit))
    toggle("explosions", False)
    rep.step("with `guests explosions off` the explosion does not reach it", lambda: blocked(send_explosion, boom))
    toggle("explosions", True)
    rep.step("with `guests explosions on` it reaches it again", lambda: arrives(send_explosion, boom))
    rep.step("the server logged no relay drops for the host", lambda: not server.log.find(r"relay: CbHost1 dropped"))


def scenario_versions(env: Env, rep: Report) -> None:
    server = env.server("versions")
    version, wrong, newer = env.mod_version, mismatched_dll_version(env.mod_version), other_version(env.mod_version)
    client = env.client("CbHost1", join=server.port)
    host = env.fakehost(client, ver=wrong)
    rep.step("a DLL of another version plays as a guest", lambda: server.log.wait(rf"handshake: CbHost1 mod {re.escape(version)} dll {re.escape(wrong)} -> guest", T_JOIN), must=True)
    rep.step("the DLL is told `session refused` with the plugin and mod versions",
             lambda: host.log.wait(rf'\[CbHost1\] session: .*"state": "refused".*plugin is {re.escape(wrong)} but your mod is {re.escape(version)}', T_EFFECT))
    rep.step("no host session starts", lambda: not server.log.find(r"session: CbHost1 started"))
    host.stop()
    client.proc.stop()
    env.client("CbGuest1", join=server.port, version=newer)
    rep.step("a mod of another major.minor version is refused with the modpack message",
             lambda: server.log.wait(rf"CbGuest1 lost connection: Cyberblock version mismatch: server {re.escape(version)}, your mod {re.escape(newer)}\. Update from the modpack\.", T_JOIN))


def scenario_flood(env: Env, rep: Report) -> None:
    server = env.server("flood")
    client = env.client("CbHost1", join=server.port)
    client.wait_listening()
    flooder = Flooder(client.port, env.mod_version)
    flooder.start()
    try:
        rep.step("the flooding DLL link is a host first", lambda: server.log.wait(host_pattern("CbHost1"), T_JOIN), must=True)
        flooder.go.set()
        rep.step("the rate limit drops `ground` messages and says so", lambda: server.log.wait(r"relay: CbHost1 dropped ground x \d+", T_EFFECT))
        rep.step("a link that keeps overflowing is ended and its player kicked",
                 lambda: server.log.wait(r"CbHost1 lost connection: Cyberblock: too many messages from your Cyberpunk link", FLOOD_S + T_EFFECT))
        rep.step("the session ended", lambda: server.log.wait(r"session: CbHost1 ended", T_EFFECT))
        rep.step("the server is still answering", lambda: server.rcon("list").lstrip().startswith("There are"))
    finally:
        flooder.stop()


def scenario_reconnect(env: Env, rep: Report) -> None:
    server = env.server("reconnect")
    client, host = start_host(env, rep, server, "CbHost1", ver=env.mod_version)
    rep.step("the ground and the proxies are there", lambda: poll(lambda: ground_stands(server, SLAB_CORNERS) and proxies_stand(server), T_EFFECT, 1.0, "ground and proxies"), must=True)
    mark = server.log.mark()
    host.stop()  # the DLL link drops: the client tells the server (`hs` with no DLL)
    rep.step("the session ended when the link dropped", lambda: server.log.wait(r"session: CbHost1 ended", T_EFFECT, mark), must=True)
    # Phase 3 (3-R1): the replica is the growing map and stays while the host is away; only barriers would go
    rep.step("the ground stays as replica blocks, no barrier left", lambda: poll(lambda: ground_stands(server, SLAB_CORNERS + (SLAB,))
                                                                                  and not barrier_left(server, SLAB_CORNERS + (SLAB,)), T_EFFECT, 1.0, "the replica"))
    rep.step("the proxies are gone", lambda: poll(lambda: not proxies_stand(server), T_EFFECT, 1.0, "the proxies to go"))
    mark = server.log.mark()
    returned = env.fakehost(client, ver=env.mod_version)
    rep.step("the link returning makes the player a host again", lambda: server.log.wait(host_pattern("CbHost1"), T_JOIN, mark), must=True)
    rep.step("the resync starts with `blocks reset:true`", lambda: returned.log.wait(r'\[CbHost1\] blocks: .*"reset": true', T_EFFECT))
    rep.step("the whole ground comes back", lambda: poll(lambda: ground_stands(server, SLAB_CORNERS + (SLAB,)), T_EFFECT, 1.0, "the ground"))
    rep.step("the proxies come back", lambda: poll(lambda: proxies_stand(server), T_EFFECT, 1.0, "the proxies"))


def scenario_twohosts(env: Env, rep: Report) -> None:
    server = env.server("twohosts", max_hosts=2)
    _, first = start_host(env, rep, server, "CbHost1", ver=env.mod_version, ids_from=COLLIDING_ID)
    _, second = start_host(env, rep, server, "CbHost2", ver=env.mod_version, ids_from=COLLIDING_ID)
    target = str(COLLIDING_ID)  # the hostile human's id on both hosts

    def reaches_only(owner: FakeHost, other: FakeHost) -> bool:
        marks = owner.log.mark(), other.log.mark()
        # as the owner's own player: a hit with no player behind it (the bare RCON source) is "other" damage, which never reaches
        # a host (ProxyDamage.kind), so it would prove nothing
        server.test(f"hit {owner.name} {target} 5.0", as_player=owner.name)
        return bool(owner.log.wait(hit_pattern(owner.name, target), T_EFFECT, marks[0])) and other.log.quiet(hit_pattern(other.name, target), T_QUIET, marks[1])

    rep.step("two hosts at once with maxHosts 2", lambda: server.log.find(host_pattern("CbHost1")) and server.log.find(host_pattern("CbHost2")))
    rep.step(f"a hit on id {target} of host 1 reaches only host 1", lambda: reaches_only(first, second))
    rep.step("a hit on the same id of host 2 reaches only host 2",lambda: reaches_only(second, first))


def scenario_joinpassword(env: Env, rep: Report) -> None:
    server = env.server("joinpassword", join_password=True)
    client = env.client("CbHost1", join=server.port)
    host = env.fakehost(client, ver=env.mod_version)
    rep.step("a player who has not logged in is locked", lambda: server.log.wait(r"login: CbHost1 is locked until /login", T_JOIN), must=True)
    rep.step("its Cyberpunk link waits as a guest with the /login reason", lambda: server.log.wait(r"handshake: CbHost1 .*-> guest \(Cyberblock: type /login", T_JOIN),
             must=True)
    rep.step("no host session starts while it is locked", lambda: server.log.quiet(r"session: CbHost1 started", T_QUIET))
    mark = server.log.mark()
    server.rcon(f"execute as CbHost1 run login {server.join_password}")
    rep.step("the right password unlocks the player", lambda: server.log.wait(r"login: CbHost1 verified", T_EFFECT, mark), must=True)
    rep.step("the waiting link becomes a host at once", lambda: server.log.wait(host_pattern("CbHost1"), T_EFFECT, mark))
    rep.step("its session starts", lambda: server.log.wait(r"session: CbHost1 started", T_EFFECT, mark))
    mark = server.log.mark()
    host.stop()
    client.proc.stop()
    rep.step("the player left", lambda: server.log.wait(r"CbHost1 lost connection", T_EFFECT, mark), must=True)
    mark = server.log.mark()
    env.fakehost(env.client("CbHost1", join=server.port), ver=env.mod_version)
    rep.step("after a reconnect the remembered player hosts without /login", lambda: server.log.wait(host_pattern("CbHost1"), T_JOIN, mark))
    rep.step("and is not locked again", lambda: not server.log.find(r"login: CbHost1 is locked", mark))
    env.client("CbGuest1", join=server.port)
    rep.step("a new player is locked", lambda: server.log.wait(r"login: CbGuest1 is locked until /login", T_JOIN), must=True)
    mark = server.log.mark()
    for _ in range(5):
        server.rcon("execute as CbGuest1 run login not-the-password")
        time.sleep(LOGIN_GAP_S)
    rep.step("five wrong passwords kick", lambda: server.log.wait(r"CbGuest1 lost connection: Cyberblock: wrong join password", T_EFFECT, mark))
    rep.step("the password is in no log", lambda: all(server.join_password not in log.text() for log in env.logs.values()))


# ---- Phase 3: the replica, two overlapping hosts, Meet Up (megaplan 3-P1) ----------------------------------------------------
# These scenarios need packages 3R (replica blocks, shared map, offline builds log, multi-host) and 3E (env, Meet Up) merged: the
# fake host `--replica` (tools/fakehost.py) plays Cyberpunk, RCON looks at the world. The block ids come from
# `shared/vectors.json` `materials` (tools.cbmath), the clock from the plan's formula (3-E1).
POS = re.compile(r"\[(-?[\d.eE+-]+)d, (-?[\d.eE+-]+)d, (-?[\d.eE+-]+)d\]")
REPLICA_WALL = ((2, 64, 0), (2, 65, 0))  # the wall a guest builds on the road (its floor is y 62 and 63)
TWO_SHIFT, TWO_HOURS = 8, 12  # `twohosts2`: the second street starts 8 blocks east, its clock is 12 hours off
MEET_TOL = 6.0  # blocks: how close "at the target's position" and "next to the host's Steve" must be
VTELEPORT = r'\[CbHost1\] vteleport: (\{.*\})'
ENV_STATES = [(cbmath.env_ticks(*t), w in ("rain", "storm"), w == "storm", f"{t[0]:02d}:{t[1]:02d} {w}") for _, t, w in fakehost.ENV_CYCLE]


def block_stands(server: Server, cell: tuple[int, int, int], block: str) -> bool:
    x, y, z = cell
    return server.passes(f"execute if block {x} {y} {z} {block}")


def landmarks_stand(server: Server, shift: int = 0, only: Callable[[tuple[int, int, int]], bool] = lambda cell: True) -> bool:
    """One cell of every material of the fake host's street (shifted east by `shift`) holds that material's replica block."""
    return all(block_stands(server, cell, cbmath.material_block(code)) for cell, code in fakehost.replica_landmarks(shift) if only(cell))


def scaffold_cell(which: tuple[int, int], shift: int = 0) -> tuple[int, int, int]:
    return which[0] + shift, fakehost.SCAFFOLD_RANGE[0] + 1, which[1]


def daytime(server: Server) -> int | None:
    """The day time 0..23999: 26.3's `time query time` gives the default clock's total ticks ("Clock minecraft:overworld is at
    88500 tick(s)"; 26.3 has no `time query daytime`), and 3E's EnvSync keeps that total going forward, so the day time is it mod 24000."""
    match = re.search(r"Clock \S+ is at (-?\d+) tick", server.rcon("time query time"))
    return int(match.group(1)) % DAY_TICKS if match else None


def ticks_apart(a: int, b: int) -> int:
    """How far apart two day times are on the 24000-tick circle (23990 and 10 are 20 apart)."""
    d = abs(a - b) % DAY_TICKS
    return min(d, DAY_TICKS - d)


def time_is(server: Server, ticks: int, tol: int = 40) -> bool:
    now = daytime(server)
    return now is not None and ticks_apart(now, ticks) <= tol


def weather_is(server: Server, *, rain: bool, thunder: bool) -> bool:
    """The world's weather by two inline `weather_check` predicates (a storm is rain and thunder). 26.3 dispatches loot
    conditions on `type` (a `Codec.dispatch` without a key name, javap on LootItemCondition), not the older `condition`."""
    raining = server.passes('execute if predicate {type:"minecraft:weather_check",raining:true}')
    thundering = server.passes('execute if predicate {type:"minecraft:weather_check",thundering:true}')
    return raining == rain and thundering == thunder


def daylight_holds(server: Server) -> bool:
    """With `doDaylightCycle` off the time stands still between two env messages: two looks a moment apart read the same tick (three
    tries, because an env step may fall in between)."""
    for _ in range(3):
        first = daytime(server)
        time.sleep(T_QUIET / 4)
        second = daytime(server)
        if first is not None and second is not None and ticks_apart(first, second) <= 3:
            return True
    return False


def player_pos(server: Server, name: str) -> tuple[float, float, float] | None:
    match = POS.search(server.rcon(f"data get entity {name} Pos"))
    return tuple(float(v) for v in match.groups()) if match else None


def near_player(server: Server, name: str, where: Iterable[float] | None, tol: float = MEET_TOL) -> bool:
    here, there = player_pos(server, name), tuple(where) if where is not None else None
    return here is not None and there is not None and math.hypot(here[0] - there[0], here[2] - there[2]) <= tol


def reported_cells(text: str, name: str) -> set[tuple[int, int, int]]:
    """Every cell a fake host's output says Minecraft reported as a player block: the `blocks` resyncs and the `block` events."""
    cells: set[tuple[int, int, int]] = set()
    for match in re.finditer(rf"^\[{re.escape(name)}\] (blocks|block): (\{{.*\}})\s*$", text, re.M):  # \s*: a log written on Windows ends its lines with CRLF
        try:
            msg = json.loads(match.group(2))
        except ValueError:
            continue
        if match.group(1) == "block" and isinstance(msg.get("pos"), list) and len(msg["pos"]) == 3:
            cells.add(tuple(msg["pos"]))
        if match.group(1) == "blocks" and isinstance(msg.get("c"), list):
            cells |= {tuple(msg["c"][i:i + 3]) for i in range(0, len(msg["c"]) - 2, 3)}
    return cells


def proxy_count(server: Server, kind: str = "human") -> int:
    """How many Night City people (`human`) or cars (`vehicle`) stand as proxies (26.3: `Test passed. Count: N`; 0 when none)."""
    answer = server.rcon(f"execute if entity @e[type=cyberblock:proxy_{kind}]")
    match = re.search(r"count: (\d+)", answer, re.I)
    return int(match.group(1)) if match else int(passed(answer))


def hit_reaches_only(server: Server, owner: FakeHost, other: FakeHost, target: str) -> bool:
    """A hit by the owner's own player on `target` reaches the owner's fake host and not the other one (colliding ids)."""
    marks = owner.log.mark(), other.log.mark()
    server.test(f"hit {owner.name} {target} 5.0", as_player=owner.name)
    return bool(owner.log.wait(hit_pattern(owner.name, target), T_EFFECT, marks[0])) and other.log.quiet(hit_pattern(other.name, target), T_QUIET, marks[1])


def wreck_stands(server: Server, shift: int = 0) -> bool:
    """A vehicle proxy stands where the fake host's destroyed car stood (the driving car passes a metre away, at z 0.5)."""
    x, y, z = -5.5 + shift, fakehost.SLAB_Y + 1.0, -0.5
    return server.passes(f"execute if entity @e[type=cyberblock:proxy_vehicle,x={x},y={y},z={z},distance=..0.6]")


def wreck_stays_then_goes(server: Server) -> bool:
    """The fake host reports its standing car `killed` (V destroyed it): 3F's ProxyManager logs the wreck, the proxy stays there for
    about 5 s and then goes. A proxy that only left `npcs` would be removed at once, without the log line."""
    died = rf"figures: vehicle {fakehost.replica_ids()[3]} died \(wreck"
    # before the log line the car stood there alive, so both must hold: the proxy at the spot and the line saying it died
    poll(lambda: wreck_stands(server) and server.log.find(died), T_EFFECT, T_POLL, "the wreck")
    seen = time.monotonic()
    poll(lambda: not wreck_stands(server), T_WRECK, T_POLL, "the wreck to go")
    return time.monotonic() - seen >= WRECK_MIN_S


def changes_listed(server: Server, host_client: Client) -> bool:
    """`/cyberblock changes` run as the host names the guest who built. 26.3's CommandSourceStack.sendSystemMessage goes to the
    player behind `execute as`, not to RCON, so the list shows in the host client's chat (its log), and RCON answers nothing."""
    mark = host_client.log.mark()
    answer = server.rcon("execute as CbHost1 run cyberblock changes")
    if re.search(r"unknown or incomplete|incorrect argument|unknown command", answer, re.I):
        raise MpError(f"the server refused `cyberblock changes` ({' '.join(answer.split())[:120]})")
    return bool(re.search(r"CbGuest1", answer)) or bool(host_client.log.wait(r"\[CHAT\] In the last .*\(by [^)]*CbGuest1", T_EFFECT, mark))


def scenario_replica(env: Env, rep: Report) -> None:
    server = env.server("replica", max_hosts=1)
    client, host = start_host(env, rep, server, "CbHost1", ver=env.mod_version, layers=(), modes=("replica",), wait_host=T_JOIN)
    metal, scaffolds = cbmath.material_block(6), (scaffold_cell(fakehost.SCAFFOLD_SEEN), scaffold_cell(fakehost.SCAFFOLD_LEFT))
    rep.step("the street stands as replica blocks of the right materials (asphalt, sidewalk, brick, glass, three neon dyes, water, ...)",
             lambda: poll(lambda: landmarks_stand(server), T_EFFECT, T_POLL, "the street"), must=True)
    rep.step("both scaffold columns stand before the removals", lambda: all(block_stands(server, cell, metal) for cell in scaffolds))
    rep.step("the replica is host ground: it never comes back to the host as a player block",
             lambda: not reported_cells(host.log.text(), "CbHost1") & set(fakehost.replica_cells()))
    rep.step("the figures are there as proxies", lambda: poll(lambda: proxies_stand(server), T_EFFECT, T_POLL, "proxies"))
    rep.step("the car V destroyed (`killed`) stays a wreck for about 5 s, then goes", lambda: wreck_stays_then_goes(server))
    rep.step("an `unground` with `seen:true` takes its scaffold away", lambda: poll(lambda: not block_stands(server, scaffolds[0], metal), T_EFFECT, T_POLL,
                                                                                    "the seen removal"))
    time.sleep(T_QUIET)  # the plain `unground` comes half a second after the seen one, `clear` and the whole ground again (without both) two seconds after it
    rep.step("an `unground` without `seen` keeps its scaffold (it only left the probe radius)", lambda: block_stands(server, scaffolds[1], metal))
    rep.step("`clear` and the ground sent again change nothing: the street stands, the removed scaffold stays removed, the other stays",
             lambda: landmarks_stand(server) and not block_stands(server, scaffolds[0], metal) and block_stands(server, scaffolds[1], metal))
    for ticks, rain, thunder, label in ENV_STATES:
        rep.step(f"the clock and weather follow Cyberpunk: {label} (tick {ticks})",
                 lambda ticks=ticks, rain=rain, thunder=thunder, label=label: poll(lambda: time_is(server, ticks) and weather_is(server, rain=rain, thunder=thunder),
                                                                                    T_ENV, T_POLL, label))
    rep.step("the daylight cycle is off: the time stands between two env messages", lambda: daylight_holds(server))
    env.client("CbGuest1", join=server.port)
    rep.step("a guest joins", lambda: server.log.wait(r"handshake: CbGuest1 .*-> guest", T_JOIN), must=True)
    mark = server.log.mark()
    host.stop()  # Cyberpunk is closed: the DLL link drops, Steve stays in the world
    rep.step("the session ends when the link drops", lambda: server.log.wait(r"session: CbHost1 ended", T_EFFECT, mark), must=True)
    rep.step("the figures go with the session", lambda: poll(lambda: not proxies_stand(server), T_EFFECT, T_POLL, "the figures to go"))
    rep.step("the replica stays while the host is away", lambda: landmarks_stand(server))
    for x, y, z in REPLICA_WALL:  # the guest builds a wall on the road, as its own placement (a plain /setblock is no player's change)
        server.test(f"place {x} {y} {z} minecraft:stone", as_player="CbGuest1")
    rep.step("the guest's wall stands on the street", lambda: all(block_stands(server, cell, "minecraft:stone") for cell in REPLICA_WALL))
    mark = server.log.mark()
    returned = env.fakehost(client, ver=env.mod_version, layers=(), modes=("replica",), wait_host=T_JOIN)
    rep.step("the host returns", lambda: server.log.wait(host_pattern("CbHost1"), T_JOIN, mark), must=True)
    wall = r"\[CbHost1\] blocks: .*(?<![-\d])2, 64, 0(?!\d)"
    rep.step("the resync tells the returning host about the guest's wall (Offline Builds)", lambda: returned.log.wait(wall, T_EFFECT))
    rep.step("the resync carries no replica block", lambda: not reported_cells(returned.log.text(), "CbHost1") & set(fakehost.replica_cells()))
    rep.step("`/cyberblock changes` lists what the guest built", lambda: changes_listed(server, client))
    rep.step("the street still stands after the return", lambda: landmarks_stand(server))
    rep.step("the server logged no relay drops for the host", lambda: not server.log.find(r"relay: CbHost1 dropped"))


def scenario_twohosts2(env: Env, rep: Report) -> None:
    server = env.server("twohosts2", max_hosts=2)
    _, first = start_host(env, rep, server, "CbHost1", ver=env.mod_version, ids_from=COLLIDING_ID, layers=(), modes=("replica",), wait_host=T_JOIN)
    _, second = start_host(env, rep, server, "CbHost2", ver=env.mod_version, ids_from=COLLIDING_ID, layers=(), modes=("replica",), wait_host=T_JOIN,
                           shift=TWO_SHIFT, env_hours=TWO_HOURS)
    rep.step("two hosts at once with maxHosts 2", lambda: server.log.find(host_pattern("CbHost1")) and server.log.find(host_pattern("CbHost2")))
    rep.step("host 1's own part of the street stands (x < 0)", lambda: poll(lambda: landmarks_stand(server, 0, lambda c: c[0] < 0), T_EFFECT, T_POLL, "street 1"))
    rep.step("host 2's own part of the street stands (x > 8)",
             lambda: poll(lambda: landmarks_stand(server, TWO_SHIFT, lambda c: c[0] > 8), T_EFFECT, T_POLL, "street 2"))
    asphalt, sidewalk = cbmath.material_block(3), cbmath.material_block(4)
    rep.step("where the streets overlap (x 0..8) the road and the sidewalk are whole",
             lambda: all(block_stands(server, (x, fakehost.SLAB_Y, 0), asphalt) and block_stands(server, (x, fakehost.SLAB_Y, 2), sidewalk) for x in range(0, 9)))
    rep.step("each host has its own figures: four proxies with two colliding id sets", lambda: poll(lambda: proxy_count(server) == 4, T_EFFECT, T_POLL, "4 proxies"))
    target = str(COLLIDING_ID)  # the hostile runner's id on both hosts
    rep.step(f"a hit on id {target} of host 1 reaches only host 1", lambda: hit_reaches_only(server, first, second, target))
    rep.step("a hit on the same id of host 2 reaches only host 2", lambda: hit_reaches_only(server, second, first, target))
    mine = {cbmath.env_ticks(*t) for _, t, _ in fakehost.ENV_CYCLE}
    other = {cbmath.env_ticks(*fakehost.replica_env(0.0 + i * 10, TWO_HOURS)["time"]) for i in range(4)}

    def only_the_first_clock() -> bool:
        samples = []
        for _ in range(5):
            samples.append(daytime(server))
            time.sleep(T_QUIET / 4)
        return all(s is not None and any(ticks_apart(s, t) <= 40 for t in mine) and not any(ticks_apart(s, t) <= 40 for t in other) for s in samples)

    rep.step("only the first host's clock drives the world (the second host's is 12 hours off and never shows)", only_the_first_clock)
    rep.step("the server logged no relay drops", lambda: not server.log.find(r"relay: CbHost[12] dropped"))


def scenario_meetup(env: Env, rep: Report) -> None:
    server = env.server("meetup", max_hosts=1)
    _, host = start_host(env, rep, server, "CbHost1", ver=env.mod_version, layers=(), modes=("replica",), wait_host=T_JOIN)
    env.client("CbGuest1", join=server.port)
    rep.step("the guest joined", lambda: server.log.wait(r"handshake: CbGuest1 .*-> guest", T_JOIN), must=True)

    def meet(asker: str, target: str) -> None:
        server.rcon(f"execute as {asker} run cyberblock meetup {target}")
        server.rcon(f"execute as {target} run cyberblock meetup accept")

    poll(lambda: player_pos(server, "CbGuest1"), T_JOIN, T_POLL, "the guest's position")
    guest_at, mark = player_pos(server, "CbGuest1"), host.log.mark()
    meet("CbHost1", "CbGuest1")

    def teleport_sent() -> bool:
        msg = json.loads(host.log.wait(VTELEPORT, T_EFFECT, mark).group(1))
        arrives = isinstance(msg.get("p"), list) and len(msg["p"]) == 3 and math.hypot(msg["p"][0] - guest_at[0], msg["p"][2] - guest_at[2]) <= MEET_TOL
        teleport_sent.p = msg.get("p")
        return arrives and msg.get("why") == "meetup" and isinstance(msg.get("id"), int)

    rep.step("a Meet Up from a host to a guest sends `vteleport` (why meetup) to the host's DLL, at the guest's position", teleport_sent, must=True)
    rep.step("after `vteleported ok` Steve follows V to that spot", lambda: poll(lambda: near_player(server, "CbHost1", teleport_sent.p, 3.0), T_EFFECT, T_POLL, "Steve at the target"))
    mark = host.log.mark()
    meet("CbHost1", "CbGuest1")
    rep.step("a second request inside the 30 s cooldown sends no new `vteleport`", lambda: host.log.quiet(VTELEPORT, T_QUIET, mark))
    time.sleep(COOLDOWN_S)
    host_at = player_pos(server, "CbHost1")
    meet("CbGuest1", "CbHost1")
    rep.step("a Meet Up from a guest to a host puts the guest next to the host's Steve",
             lambda: poll(lambda: near_player(server, "CbGuest1", host_at), T_EFFECT, T_POLL, "the guest next to Steve"))
    mark = host.log.mark()
    time.sleep(COOLDOWN_S)
    meet("CbHost1", "CbGuest1")
    rep.step("after the cooldown a host can ask again", lambda: host.log.wait(VTELEPORT, T_EFFECT, mark))
    rep.step("the server logged no relay drops for the host", lambda: not server.log.find(r"relay: CbHost1 dropped"))


# ---- Phase 5: figures (megaplan 5-P1) -----------------------------------------------------------------------------------------
# The fake host `--figures` sends the Phase 5 `npcs` list (tools/fakehost.py figures_npcs): 46 tagged figures, 6 of them cars. The server's proxies
# must follow it exactly as before (the extra keys are only tags). This checks the part that does not depend on the figure renderer (packages 5F and
# 5K): counts, the dead victim, a hit on a tagged id, the session end, the relay. Skins and poses are checked on screen (Re-test 5).
FIGURE_PEOPLE = len(fakehost.figure_roster()) + len(fakehost.FIGURE_POSES) + 2  # people at rest: the rows, the poses, the walker and the runner
FIGURE_CARS = len(fakehost.FIGURE_PARKED) + 2  # the parked cars, the cruiser and the car it chases
FIGURE_HIT = fakehost.figure_ids()[len(fakehost.figure_roster())]  # the first pose: a hostile Maelstrom gunner who aims


def scenario_figures(env: Env, rep: Report) -> None:
    server = env.server("figures", max_hosts=1)
    _, host = start_host(env, rep, server, "CbHost1", ver=env.mod_version, layers=(), modes=("figures",), wait_host=T_JOIN)
    rep.step(f"every tagged person and car stands as a proxy ({FIGURE_PEOPLE} people once the victim's body has gone, {FIGURE_CARS} cars)",
             lambda: poll(lambda: proxy_count(server) == FIGURE_PEOPLE and proxy_count(server, "vehicle") == FIGURE_CARS, T_EFFECT, T_POLL, "the figures"), must=True)
    time.sleep(T_QUIET)
    rep.step("the dead victim does not come back and no figure is added or lost",
             lambda: proxy_count(server) == FIGURE_PEOPLE and proxy_count(server, "vehicle") == FIGURE_CARS)
    mark = host.log.mark()
    server.test(f"hit CbHost1 {FIGURE_HIT} 5.0")  # from the console: no player behind it, always allowed
    rep.step("a hit on a tagged figure reaches the fake host by its id", lambda: host.log.wait(hit_pattern("CbHost1", FIGURE_HIT), T_EFFECT, mark))
    mark = server.log.mark()
    host.stop()  # Cyberpunk is closed: the DLL link drops
    rep.step("the session ends when the link drops", lambda: server.log.wait(r"session: CbHost1 ended", T_EFFECT, mark), must=True)
    rep.step("the figures go with the session",
             lambda: poll(lambda: proxy_count(server) == 0 and proxy_count(server, "vehicle") == 0, T_EFFECT, T_POLL, "the figures to go"))
    rep.step("the server logged no relay drops for the host", lambda: not server.log.find(r"relay: CbHost1 dropped"))


SCENARIO_FUNCS = {"singleplayer": scenario_singleplayer, "guest": scenario_guest, "versions": scenario_versions, "flood": scenario_flood,
                  "reconnect": scenario_reconnect, "twohosts": scenario_twohosts, "joinpassword": scenario_joinpassword,
                  "replica": scenario_replica, "twohosts2": scenario_twohosts2, "meetup": scenario_meetup, "figures": scenario_figures}
PHASE3 = ("replica", "twohosts2", "meetup")  # the scenarios whose fake host runs `--replica`
SCENARIO_MODES = {**dict.fromkeys(PHASE3, ("replica",)), "figures": ("figures",)}  # the fake host mode (and no layer) of each scenario after Phase 1


class Flooder(threading.Thread):
    """A DLL that links, says hello and, when told to (`go`), floods the client's link with `ground` messages (about 2000 per second)."""

    def __init__(self, port: int, ver: str, seconds: float = FLOOD_S, rate_hz: int = 2000) -> None:
        super().__init__(daemon=True)
        self.port, self.ver, self.seconds, self.rate_hz = port, ver, seconds, rate_hz
        self.go, self.halt = threading.Event(), threading.Event()

    def run(self) -> None:
        with contextlib.suppress(Exception):
            asyncio.run(self._flood())

    async def _flood(self) -> None:
        from websockets.asyncio.client import connect
        async with connect(f"ws://127.0.0.1:{self.port}", max_size=None) as ws:
            for m in ({"t": "hello", "proto": 1, "game": "cp2077", "build": "2.31", "ver": self.ver}, {"t": "state", "active": True, "vehicle": False}):
                await ws.send(json.dumps(m))
            while not self.go.is_set():
                if self.halt.is_set():
                    return
                await asyncio.sleep(0.1)
            end, i, batch = time.monotonic() + self.seconds, 0, 100
            while time.monotonic() < end and not self.halt.is_set():
                for _ in range(batch):
                    await ws.send(json.dumps({"t": "ground", "c": [i % 17 - 8, (i // 17) % 17 - 8, 63, 63]}))
                    i += 1
                await asyncio.sleep(batch / self.rate_hz)

    def stop(self) -> None:
        self.halt.set()
        self.join(timeout=5)


# ---- running -----------------------------------------------------------------------------------------------------------
def run_scenarios(env: Env, names: Iterable[str], say: Callable[[str], None] = print) -> list[Report]:
    """Run the scenarios in order; everything a scenario started is stopped before the next one (its ports must be free)."""
    reports = []
    for name in names:
        say(f"== {name}")
        rep = Report(name, say)
        try:
            SCENARIO_FUNCS[name](env, rep)
        except Abort as e:
            say(f"  (the rest of {name} is skipped: {e} failed)")
        except (MpError, OSError) as e:
            rep.checks.append((f"{name} could not run", False, (str(e).splitlines() or [type(e).__name__])[0]))
            say(f"  FAIL  {name}: could not run ({e})")
        if not rep.ok:
            for label, log in env.logs.items():
                say(f"  --- last lines of the {label} log")
                say("\n".join("  | " + line for line in log.tail(25).splitlines()))
        reports.append(rep)
        env.stop_all()
        env.logs = {}
    return reports


def summary(reports: list[Report], say: Callable[[str], None] = print) -> int:
    say("== summary")
    for rep in reports:
        say(f"  {'PASS' if rep.ok else 'FAIL'}  {rep.name}: {sum(ok for _, ok, _ in rep.checks)}/{len(rep.checks)} checks")
    return 0 if reports and all(r.ok for r in reports) else 1


def run_guest_client(env: Env, lan_port: int) -> int:
    """Re-test 1: one offline guest client for the world the user opened to LAN; nothing else is started."""
    client = env.client("CbGuest1", join=lan_port)
    env.say(f"started the offline guest CbGuest1 for 127.0.0.1:{lan_port}; close its window or press Ctrl+C here to stop it")
    env.say("note: a LAN world authenticates every login, so it refuses this offline guest (\"Invalid session\") until you type "
            "/cyberblock lan offline on in that world (see docs/TESTING-PLAN.md, W-A9)")
    try:
        while client.proc.alive():
            time.sleep(0.5)
    except KeyboardInterrupt:
        env.say("stopping the guest client")
    return 0


def dry_run(repo: Path, ports: Ports, names: list[str], lan_port: int | None, say: Callable[[str], None] = print) -> None:
    """Print what would be started (no process, no folder, no password)."""
    d = Path("<run folder>")
    if lan_port is not None:
        say("client: " + " ".join(client_argv(repo, name="CbGuest1", port=ports.client("CbGuest1"), frame=ports.frame("CbGuest1"),
                                              run_dir=d / "client-CbGuest1", join=lan_port)))
        return
    for name in names:
        say(f"== {name} (server port {ports.server(name)})")
        if name != "singleplayer":
            say("server: " + " ".join(server_argv(repo, port=ports.server(name), run_dir=d / f"server-{ports.server(name)}"))
                + "   (JAVA_TOOL_OPTIONS=-Dcyberblock.devtools=true)")
        for who in ("CbHost1",) if name == "singleplayer" else ("CbHost1", "CbGuest1"):
            say(f"client {who}: " + " ".join(client_argv(repo, name=who, port=ports.client(who), frame=ports.frame(who), run_dir=d / f"client-{who}",
                                                         join=None if name == "singleplayer" else ports.server(name),
                                                         world="<world>" if name == "singleplayer" else None)))
        say("fake host: " + " ".join(fakehost_argv(port=ports.client("CbHost1"), name="CbHost1", ver="<mod version>", frame=ports.frame("CbHost1"), out_dir=d / "frames",
                                                    layers=() if name in SCENARIO_MODES else (2,), modes=SCENARIO_MODES.get(name, ()))))


def main(argv: list[str] | None = None, repo: Path = ROOT) -> int:
    with contextlib.suppress(AttributeError, ValueError):  # each line shows at once, also when the output is piped or captured
        sys.stdout.reconfigure(line_buffering=True)
    ap = argparse.ArgumentParser(description="Multiplayer harness: dedicated server, offline clients and fake hosts. Run it from a worktree.",
                                 epilog="scenarios: " + ", ".join(SCENARIOS))
    ap.add_argument("scenarios", nargs="*", metavar="scenario", help="the scenarios to run, in the order given")
    ap.add_argument("--all", action="store_true", help="run every scenario, in order")
    ap.add_argument("--guest", type=int, metavar="LAN_PORT",
                    help="start ONE offline guest client (CbGuest1) for 127.0.0.1 on this port, nothing else (a LAN world refuses offline logins unless the mod allows them)")
    ap.add_argument("--dry-run", action="store_true", help="print the commands and start nothing")
    ap.add_argument("--keep", action="store_true", help="keep the run folder (it holds the run's server.properties) for debugging")
    ap.add_argument("--client-port-base", type=int, default=CLIENT_BASE, help=f"first client link port (default {CLIENT_BASE}; four are used)")
    ap.add_argument("--server-port-base", type=int, default=SERVER_BASE, help=f"first test server port (default {SERVER_BASE}; five are used)")
    ap.add_argument("--frame-prefix", default=FRAME_PREFIX, help=f"frame name prefix (default {FRAME_PREFIX}; H or G and the client number are appended)")
    ap.add_argument("--commit-limit", type=float, default=COMMIT_LIMIT_GB, help=f"GB of system commit above which nothing starts (default {COMMIT_LIMIT_GB:g})")
    args = ap.parse_args(argv)
    if bad := [s for s in args.scenarios if s not in SCENARIOS]:
        ap.error(f"unknown scenario {bad[0]!r} (choose from {', '.join(SCENARIOS)})")
    if args.all and args.scenarios:
        ap.error("--all runs every scenario: do not name any")
    names = list(SCENARIOS) if args.all else list(args.scenarios)
    if args.guest is None and not names:
        ap.error("give a scenario, --all or --guest <LAN port>")
    if args.guest is not None and (names or args.all):
        ap.error("--guest starts one client and nothing else: do not combine it with scenarios")
    if args.guest is not None and not 1024 <= args.guest <= 65535:
        ap.error("--guest needs the LAN port Minecraft printed in chat (1024 to 65535)")
    ports = Ports(args.client_port_base, args.server_port_base, args.frame_prefix)
    try:
        ports.check()
    except MpError as e:
        ap.error(str(e))
    if args.dry_run:
        dry_run(repo, ports, names, args.guest)
        return 0
    if not (repo / ".git").is_file():
        print(f"{repo} is the main checkout (or not a worktree): the harness runs Gradle, which must never touch the mc/ folder you play from.\n"
              "Run it from a git worktree, for example the integration worktree the integrator names.", file=sys.stderr)
        return 2
    java = play.find_jdk(play.default_jdk_candidates())
    if java is None:
        print(f"no JDK {play.MIN_JAVA} found: set JAVA_HOME to one", file=sys.stderr)
        return 1
    env = Env(repo, ports, java, keep=args.keep, commit_limit=args.commit_limit)
    try:
        if args.guest is not None:
            return run_guest_client(env, args.guest)
        return summary(run_scenarios(env, names))
    except KeyboardInterrupt:
        print("interrupted: stopping everything this run started", file=sys.stderr)
        return 130
    except MpError as e:
        print(f"mpcheck: {e}", file=sys.stderr)
        return 1
    finally:
        env.close()


if __name__ == "__main__":
    sys.exit(main())
