"""tools/mpcheck.py without Minecraft: the pieces (commit wait, files, command lines, RCON, logs), the scenario logic against a
simulated server, the CLI and the secret handling. The real run (`tools.mpcheck --all`) is the integrator's."""
import asyncio
import json
import re
import socket
import struct
import threading
from pathlib import Path

import pytest
from websockets.asyncio.server import serve

from tools import fakehost, mpcheck
from tools.mpcheck import LogFile, MpError

REPO = Path(__file__).resolve().parents[1]
MOD = "1.0.0"
SESSION_HOST = {"t": "session", "state": "host", "reason": "", "perms": {"explosions": True, "damage": True, "hacks": True, "devices": True, "spawns": False}}


@pytest.fixture(autouse=True)
def quick(monkeypatch):
    """Every wait of the scenarios shrinks to a fraction of a second (the real values are minutes)."""
    for name, value in (("T_QUIET", 0.05), ("T_EFFECT", 0.6), ("T_JOIN", 0.6), ("T_SERVER", 0.6), ("T_CLIENT", 0.6), ("FLOOD_S", 0.1), ("T_STOP", 0.05)):
        monkeypatch.setattr(mpcheck, name, value)


# ---- commit check ----------------------------------------------------------------------------------------------------------
def test_wait_for_commit_returns_at_once_under_the_limit():
    slept = []
    assert mpcheck.wait_for_commit(60, read=lambda: 41.5, sleep=slept.append, say=lambda m: None) == 41.5 and slept == []
    assert mpcheck.wait_for_commit(60, read=lambda: 60.0, sleep=slept.append, say=lambda m: None) == 60.0  # exactly 60 GB is allowed


def test_wait_for_commit_waits_60_seconds_and_looks_again_while_above_the_limit():
    readings, slept, said = iter([72.0, 66.0, 59.9]), [], []
    assert mpcheck.wait_for_commit(60, read=lambda: next(readings), sleep=slept.append, say=said.append) == 59.9
    assert slept == [60.0, 60.0] and len(said) == 2 and "72.0" in said[0] and "waiting 60 s" in said[0]


def test_wait_for_commit_gives_up_after_the_maximum_wait():
    slept = []
    with pytest.raises(MpError, match="stayed above 60 GB"):
        mpcheck.wait_for_commit(60, read=lambda: 70.0, sleep=slept.append, max_wait=180, say=lambda m: None)
    assert slept == [60.0, 60.0, 60.0]


def test_the_real_commit_charge_is_a_plausible_number_of_gigabytes():
    used = mpcheck.commit_gb()
    assert 0.5 < used < 2000


def test_the_defaults_are_the_task_and_the_plan_limits():
    assert (mpcheck.COMMIT_LIMIT_GB, mpcheck.COMMIT_RECHECK_S) == (60.0, 60.0)
    assert mpcheck.GRADLE_HEAP == "-Dorg.gradle.jvmargs=-Xmx1536m"


# ---- the files of a run folder ---------------------------------------------------------------------------------------------
def test_offline_uuid_is_the_name_based_uuid_minecraft_uses():
    assert mpcheck.offline_uuid("Notch") == "b50ad385-829d-3141-a216-7e7d7539ba7f"
    ids = {mpcheck.offline_uuid(n) for n in mpcheck.OFFLINE_NAMES}
    assert len(ids) == 4 and all(i[14] == "3" for i in ids)


def test_render_properties_replaces_known_keys_and_appends_the_others():
    template = "# comment server-port=1\nserver-port=25565\nonline-mode=false\n"
    out = mpcheck.render_properties(template, {"server-port": 25914, "enable-rcon": True, "rcon.port": 30001})
    assert out.splitlines() == ["# comment server-port=1", "server-port=25914", "online-mode=false", "enable-rcon=true", "rcon.port=30001"]
    assert mpcheck.render_properties("a=1\n", {}) == "a=1\n"


def test_the_test_server_template_is_offline_loopback_and_whitelisted():
    props = dict(line.split("=", 1) for line in (REPO / "server" / "test-server.properties").read_text(encoding="utf-8").splitlines()
                 if line.strip() and not line.startswith("#"))
    assert props["online-mode"] == "false" and props["server-ip"] == "127.0.0.1"
    assert props["white-list"] == "true" and props["enforce-whitelist"] == "true"
    assert props["allow-flight"] == "true"  # the host moves its player, so Steve hovers
    assert not {"server-port", "rcon.password", "enable-rcon", "rcon.port"} & set(props)  # the harness adds the per-run values


def test_the_eula_template_accepts_the_eula_for_the_throw_away_folder_only():
    text = (REPO / "server" / "eula.txt.template").read_text(encoding="utf-8")
    assert "eula=true" in text.splitlines() and "Apex" in text


def test_server_json_example_has_the_defaults_of_plan_section_1_13():
    config = json.loads((REPO / "server" / "server.json.example").read_text(encoding="utf-8"))
    assert config == {"schema": 1, "allowHosts": True, "hosts": [], "maxHosts": 1, "requireMod": True, "joinHelp": "", "rate": 1.0,
                      "sanityRadius": 160, "creativeErasesReplica": False, "pvpDefault": False, "backups": {"daily": True, "keep": 7, "hour": 5},
                      "webMap": False}


def test_server_json_sets_max_hosts_and_keeps_everything_else():
    example = (REPO / "server" / "server.json.example").read_text(encoding="utf-8")
    two = json.loads(mpcheck.server_json(example, 2))
    assert two["maxHosts"] == 2 and {k: v for k, v in two.items() if k != "maxHosts"} == {k: v for k, v in json.loads(example).items() if k != "maxHosts"}


def test_mod_version_is_read_from_gradle_properties_and_the_other_versions_are_refused_ones():
    assert mpcheck.mod_version(REPO) == (REPO / "pyproject.toml").read_text(encoding="utf-8").split('version = "')[1].split('"')[0]
    assert mpcheck.other_version("1.0.0") == "1.1.0" and mpcheck.other_version("2.7.9") == "2.8.0"
    assert mpcheck.mismatched_dll_version("1.0.0") == "0.0.1" and mpcheck.mismatched_dll_version("0.0.1") == "0.0.2"


# ---- command lines ---------------------------------------------------------------------------------------------------------
def test_client_command_passes_port_frame_run_dir_and_the_offline_name_through_gradle():
    argv = mpcheck.client_argv(Path("W"), name="CbHost1", port=25754, frame="Local\\CyberblockFrame1TH", run_dir=Path("R"), join=25914)
    assert argv[0].endswith("gradlew.bat") and "mc" in argv[0] and argv[1:4] == ["--no-daemon", mpcheck.GRADLE_HEAP, "runClient"]
    assert argv[4:7] == ["-Pcyberblock.port=25754", "-Pcyberblock.frame=Local\\CyberblockFrame1TH", "-Pcyberblock.runDir=R"]
    assert argv[7] == "--args=--username CbHost1 --quickPlayMultiplayer 127.0.0.1:25914" and len(argv) == 8


def test_client_command_for_a_world_and_for_another_mod_version():
    argv = mpcheck.client_argv(Path("W"), name="CbHost1", port=25754, frame="f", run_dir=Path("R"), world="w", version="1.1.0")
    assert "-Pversion=1.1.0" in argv and argv[-1] == "--args=--username CbHost1 --quickPlaySingleplayer w"


def test_server_command_names_the_port_and_the_run_folder():
    argv = mpcheck.server_argv(Path("W"), port=25915, run_dir=Path("R"))
    assert argv[1:4] == ["--no-daemon", mpcheck.GRADLE_HEAP, "runServer"] and argv[4:6] == ["-Pcyberblock.serverPort=25915", "-Pcyberblock.runDir=R"]


def test_server_command_skips_the_gradle_task_that_would_rewrite_server_properties():
    """1L's runServer depends on `cyberblockServerFiles`, which rewrites server.properties from the template: the RCON lines
    the harness wrote would be gone and RCON would never open."""
    argv = mpcheck.server_argv(Path("W"), port=25915, run_dir=Path("R"))
    assert argv[argv.index("-x") + 1] == "cyberblockServerFiles"


def test_fakehost_command_has_its_own_name_port_frame_and_version():
    argv = mpcheck.fakehost_argv(port=25754, name="CbHost1", ver="1.0.0", frame="Local\\F", out_dir=Path("O"), ids_from=5000, cmds=["say hi"], layers=(2, 6), modes=("ui",))
    joined = " ".join(argv)
    assert argv[1:4] == ["-u", "-m", "tools.fakehost"] and "--port 25754" in joined and "--name CbHost1" in joined and "--shm Local\\F" in joined
    assert "--ids-from 5000" in joined and "--ver 1.0.0" in joined and "--layer2" in argv and "--layer6" in argv and "--ui" in argv and "--no-setup" in argv
    assert argv[argv.index("--cmd") + 1] == "say hi"
    plain = mpcheck.fakehost_argv(port=1, name="x", ver=None, frame="f", out_dir=Path("O"), no_setup=False)
    assert "--no-setup" not in plain and plain[plain.index("--ver") + 1] == ""  # no version: an empty --ver sends none


def test_the_fake_hosts_output_is_unbuffered_so_the_scenarios_see_it_while_it_runs(tmp_path):
    """Its stdout is a log file: without -u its lines would sit in Python's buffer until it exits, and every live wait on it
    (hit, explosion, `session refused`, `blocks reset:true`) would time out while every "does not arrive" check passed blindly."""
    argv = mpcheck.fakehost_argv(port=1, name="x", ver=None, frame="f", out_dir=Path("O"))
    assert argv[0] == mpcheck.sys.executable and argv[1] == "-u" and argv.index("-u") < argv.index("-m")
    # the same interpreter flag on a child that prints and keeps running: the line is in the log file while it runs
    proc = mpcheck.Proc("child", [argv[0], "-u", "-c", "import time; print('ready'); time.sleep(30)"], tmp_path, dict(mpcheck.os.environ), tmp_path / "child.log")
    try:
        assert proc.log.wait("ready", 20)
    finally:
        proc.stop()


def test_the_fakehost_command_line_parses_with_the_real_fakehost(monkeypatch):
    """The harness's arguments are the fake host's own (a typo would only show in a real run)."""
    from tools import fakehost
    argv = mpcheck.fakehost_argv(port=25754, name="CbHost1", ver="1.0.0", frame="Local\\F", out_dir=Path("O"), ids_from=5000, layers=(2, 3, 6),
                                 modes=("ui", "hacks"), cmds=["summon tnt 3 64 0"], no_setup=False)[4:]
    seen = []

    async def fake_run(*args):
        seen.append(args)
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    monkeypatch.setattr("sys.argv", ["fakehost", *argv])
    assert fakehost.main() == 0
    (args,) = seen
    assert args[0] == "ws://127.0.0.1:25754" and args[4] == "Local\\F" and args[5] is True and args[6] == 900 and args[7] == ["summon tnt 3 64 0"]
    assert args[8] == frozenset({2, 3, 6}) and args[9] == frozenset({"ui", "hacks"}) and args[10:] == ("CbHost1", "1.0.0", 5000)
    monkeypatch.setattr("sys.argv", ["fakehost", *mpcheck.fakehost_argv(port=1, name="x", ver=None, frame="f", out_dir=Path("O"))[4:]])
    assert fakehost.main() == 0 and seen[-1][10:] == ("x", None, None)  # an empty --ver means no ver at all


# ---- ports and frames ------------------------------------------------------------------------------------------------------
def test_default_ports_are_package_1t_and_stay_in_the_phase_1_ranges():
    p = mpcheck.Ports()
    p.check()
    servers = {name: p.server(name) for name in mpcheck.SCENARIOS}
    assert servers == {"singleplayer": 25914, "guest": 25914, "versions": 25915, "flood": 25916, "reconnect": 25917, "twohosts": 25918}
    assert {p.client(n) for n in mpcheck.OFFLINE_NAMES} == {25754, 25755, 25756, 25757} and 25600 not in servers.values()
    assert [p.frame(n) for n in mpcheck.OFFLINE_NAMES] == ["Local\\CyberblockFrame1TH", "Local\\CyberblockFrame1TH2", "Local\\CyberblockFrame1TG", "Local\\CyberblockFrame1TG2"]


@pytest.mark.parametrize("ports", [mpcheck.Ports(client_base=25598), mpcheck.Ports(server_base=25596), mpcheck.Ports(server_base=80),
                                   mpcheck.Ports(client_base=25914, server_base=25914), mpcheck.Ports(frame_prefix="CyberblockFrame1T")])
def test_unusable_ports_and_frame_names_are_refused(ports):
    with pytest.raises(MpError):
        ports.check()


def test_a_frame_prefix_that_makes_the_users_frame_name_is_refused():
    class Same(mpcheck.Ports):
        def frame(self, name):
            return "Local\\CyberblockFrame"

    with pytest.raises(MpError):
        Same().check()


def test_every_scenario_has_a_function_and_a_server_slot():
    assert set(mpcheck.SCENARIOS) == set(mpcheck.SCENARIO_FUNCS) == set(mpcheck.SERVER_SLOT)
    assert mpcheck.SCENARIOS == ("singleplayer", "guest", "versions", "flood", "reconnect", "twohosts")


# ---- logs ------------------------------------------------------------------------------------------------------------------
def test_logfile_find_wait_mark_quiet_and_tail(tmp_path):
    log = LogFile(tmp_path / "x.log")
    assert log.text() == "" and log.mark() == 0 and log.find("a") is None
    (tmp_path / "x.log").write_text("one\ntwo\n", encoding="utf-8")
    mark = log.mark()
    assert log.find("two") and not log.find("two", mark)
    with (tmp_path / "x.log").open("a", encoding="utf-8") as f:
        f.write("three 3\n")
    assert log.wait(r"three (\d)", 1, mark).group(1) == "3" and log.tail(2) == "two\nthree 3"
    assert not log.quiet("three", 0.01, mark) and log.quiet("four", 0.01, mark)


def test_logfile_wait_times_out_and_notices_a_dead_process(tmp_path):
    (tmp_path / "x.log").write_text("hello\n", encoding="utf-8")
    with pytest.raises(mpcheck.Timeout, match="/never/"):
        LogFile(tmp_path / "x.log").wait("never", 0.3)
    dead = LogFile(tmp_path / "x.log", alive=lambda: False)
    with pytest.raises(MpError, match="ended before"):
        dead.wait("never", 5)
    assert dead.wait("hello", 5)  # its last words still count


def test_logfile_reads_bytes_it_cannot_decode(tmp_path):
    (tmp_path / "x.log").write_bytes(b"ok \xff\xfe line\n")
    assert LogFile(tmp_path / "x.log").find("ok .* line")


# ---- RCON ------------------------------------------------------------------------------------------------------------------
def fake_rcon_server(password, answers):
    """A one-thread RCON server on a free port: returns (port, stop). `answers` maps a command to its reply."""
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(5)
    seen = []

    def serve_one():
        while True:
            try:
                conn, _ = srv.accept()
            except OSError:
                return
            with conn:
                try:
                    _, _, body = mpcheck.rcon_read(conn)
                    conn.sendall(mpcheck.rcon_packet(1 if body == password else -1, 2, ""))
                    if body != password:
                        continue
                    request, _, command = mpcheck.rcon_read(conn)
                    seen.append(command)
                    conn.sendall(mpcheck.rcon_packet(request, 0, answers.get(command, "")))
                except (ConnectionError, OSError):
                    continue

    t = threading.Thread(target=serve_one, daemon=True)
    t.start()
    return srv.getsockname()[1], seen, srv.close


def test_rcon_logs_in_sends_one_command_and_returns_the_answer():
    port, seen, stop = fake_rcon_server("s3cret", {"list": "There are 0 of a max of 4 players online:"})
    try:
        assert mpcheck.rcon(port, "s3cret", "list") == "There are 0 of a max of 4 players online:"
        assert seen == ["list"]
    finally:
        stop()


def test_rcon_refuses_a_wrong_password_with_a_hint():
    port, seen, stop = fake_rcon_server("s3cret", {})
    try:
        with pytest.raises(MpError, match="refused the RCON login"):
            mpcheck.rcon(port, "wrong", "list")
        assert seen == []
    finally:
        stop()


def test_rcon_packets_have_the_documented_framing():
    packet = mpcheck.rcon_packet(7, 2, "list")
    length, request, kind = struct.unpack("<iii", packet[:12])
    assert length == len(packet) - 4 == 10 + 4 and (request, kind) == (7, 2) and packet.endswith(b"list\x00\x00")


@pytest.mark.parametrize("answer, ok", [("Test passed", True), ("Test passed, count: 3", True), ("  Test passed", True), ("Test failed", False), ("", False)])
def test_test_answers(answer, ok):
    assert mpcheck.passed(answer) is ok


def test_a_server_that_does_not_know_the_devtools_command_is_a_clear_error(tmp_path):
    class S(mpcheck.Server):
        def rcon(self, command):
            return "Unknown or incomplete command, see below for error"

    s = S(1, 2, "pw", None, LogFile(tmp_path / "s.log"), tmp_path)
    with pytest.raises(MpError, match="devtools"):
        s.test("hit CbHost1 5 1.0")
    assert "pw" not in repr(s)  # the password never shows in a repr or a traceback


# ---- the environment and the secrets ---------------------------------------------------------------------------------------
class FakeProc:
    """A stand-in for mpcheck.Proc: starts nothing; a server says it is done and every child's port counts as open until it stops."""
    started, listening = [], set()

    def __init__(self, name, argv, cwd, env, log_path):
        self.name, self.argv, self.cwd, self.env, self.stopped = name, argv, cwd, env, False
        self.ports = {int(a.split("=")[1]) for a in argv if a.startswith(("-Pcyberblock.port=", "-Pcyberblock.serverPort="))}
        FakeProc.listening |= self.ports
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text("Done (1.2s)! For help, type \"help\"\n" if "runServer" in argv else "", encoding="utf-8")
        self.log = LogFile(log_path, self.alive)
        FakeProc.started.append(self)

    def alive(self):
        return not self.stopped

    def stop(self):
        self.stopped = True
        FakeProc.listening -= self.ports


@pytest.fixture
def env(tmp_path, monkeypatch):
    FakeProc.started, FakeProc.listening = [], set()
    monkeypatch.setattr(mpcheck, "Proc", FakeProc)
    monkeypatch.setattr(mpcheck, "wait_for_commit", lambda *a, **k: 1.0)
    monkeypatch.setattr(mpcheck.play, "port_open", lambda port: port in FakeProc.listening)
    monkeypatch.setattr(mpcheck, "rcon", lambda port, password, command, timeout=15.0: "There are 0 players" if command == "list" else "")
    monkeypatch.setenv("APEX_FTP_PASS", "never-pass-this-on")
    said = []
    e = mpcheck.Env(REPO, mpcheck.Ports(), Path("C:/jdk"), say=said.append, run_root=tmp_path)
    e.said = said
    yield e
    e.close()


def test_server_start_writes_the_run_folder_and_starts_runserver_with_devtools(env):
    server = env.server("guest", max_hosts=2)
    d = server.dir
    props = (d / "server.properties").read_text(encoding="utf-8")
    assert "server-port=25914" in props and "enable-rcon=true" in props and "online-mode=false" in props and "server-ip=127.0.0.1" in props
    assert f"rcon.port={server.rcon_port}" in props and "rcon.password=" in props
    assert (d / "eula.txt").read_text(encoding="utf-8").count("eula=true") == 1
    assert [e["name"] for e in json.loads((d / "whitelist.json").read_text(encoding="utf-8"))] == list(mpcheck.OFFLINE_NAMES)
    assert json.loads((d / "config" / "cyberblock" / "server.json").read_text(encoding="utf-8"))["maxHosts"] == 2
    (proc,) = FakeProc.started
    assert "runServer" in proc.argv and "-Pcyberblock.serverPort=25914" in proc.argv and proc.cwd == REPO / "mc"
    assert proc.argv[proc.argv.index("-x") + 1] == "cyberblockServerFiles"  # Gradle must not rewrite the file with the RCON lines
    assert proc.env["JAVA_TOOL_OPTIONS"] == "-Dcyberblock.devtools=true" and proc.env["JAVA_HOME"] == str(Path("C:/jdk"))


def test_the_rcon_password_stays_in_memory_and_in_the_run_folder_only(env, capsys):
    password = env._password
    assert len(password) >= 20 and password != mpcheck.Env(REPO, mpcheck.Ports(), None, run_root=env.run)._password  # random per run
    server = env.server("guest")
    env.client("CbHost1", join=server.port)
    env.fakehost(env.client("CbGuest1", join=server.port), ver=MOD)
    env.say(f"{env!r} {server!r}")
    for proc in FakeProc.started:
        assert password not in " ".join(proc.argv) and password not in repr(proc.argv)
        assert password not in "".join(f"{k}={v}" for k, v in proc.env.items())
    assert password not in "\n".join(env.said) and password not in capsys.readouterr().out and password not in repr(env) and password not in repr(server)
    holders = [p for p in env.run.rglob("*") if p.is_file() and password in p.read_text(encoding="utf-8", errors="ignore")]
    assert holders == [server.dir / "server.properties"]  # and nowhere else, not even in a log


def test_the_hosting_password_is_removed_from_the_environment_of_every_child(env):
    server = env.server("guest")
    client = env.client("CbHost1", join=server.port)
    env.fakehost(client, ver=MOD)
    assert all("APEX_FTP_PASS" not in p.env for p in FakeProc.started) and len(FakeProc.started) == 3


def test_clients_get_their_own_port_frame_and_run_folder_and_never_the_users_link(env):
    host = env.client("CbHost1", join=25914)
    guest = env.client("CbGuest1", join=25914)
    assert (host.port, guest.port) == (25754, 25756) and host.frame != guest.frame and host.frame.startswith("Local\\CyberblockFrame1T")
    a, b = (p.argv for p in FakeProc.started)
    assert not any("25600" in x or x == "-Pcyberblock.frame=Local\\CyberblockFrame" for x in a + b)
    assert next(x for x in a if "runDir" in x) != next(x for x in b if "runDir" in x)


def test_a_port_already_in_use_is_never_touched(env):
    FakeProc.listening.add(25914)  # somebody else's process
    with pytest.raises(MpError, match="already in use"):
        env.server("guest")
    FakeProc.listening.add(25754)
    with pytest.raises(MpError, match="already in use"):
        env.client("CbHost1", join=25914)
    assert FakeProc.started == []


def test_close_stops_everything_and_removes_the_run_folder_unless_kept(env, tmp_path):
    server = env.server("guest")
    env.client("CbHost1", join=server.port)
    run = env.run
    env.close()
    assert all(p.stopped for p in FakeProc.started) and not run.exists()
    kept = mpcheck.Env(REPO, mpcheck.Ports(), None, keep=True, say=lambda m: None, run_root=tmp_path)
    kept.close()
    assert kept.run.exists()


def test_a_server_starts_in_a_new_folder_each_time(env):
    first = env.server("guest")
    env.stop_all()
    second = env.server("guest")
    assert first.dir != second.dir and first.dir.exists() and second.dir.exists()


def test_env_refuses_unusable_ports_before_creating_anything(tmp_path):
    with pytest.raises(MpError):
        mpcheck.Env(REPO, mpcheck.Ports(server_base=25596), None, run_root=tmp_path)
    assert list(tmp_path.iterdir()) == []


# ---- a simulated server: the scenario logic -------------------------------------------------------------------------------
def append(log, line):
    with log.path.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


class SimProc:
    def __init__(self):
        self.running = True

    def alive(self):
        return self.running

    def stop(self):
        self.running = False


class SimServer(mpcheck.Server):
    """An ideal server as the megaplan describes it, driven by RCON commands and fake-host events. `bugs` switch parts off."""

    def __init__(self, tmp, port, env_sim, bugs):
        super().__init__(port, 0, "pw", None, LogFile(tmp / f"server-{port}.log"), tmp)
        self.sim, self.bugs, self.toggles = env_sim, bugs, {}

    def allows(self, owner, actor, toggle):
        """As 1N's Permissions.allows: no player behind the action (the console), the owner or another host is always allowed;
        a guest only while the owner's toggle is on."""
        guest = actor is not None and actor != owner and actor not in self.sim.hosts
        return not guest or self.toggles.get(owner, {}).get(toggle, True) or "ignore-toggles" in self.bugs

    def rcon(self, command):
        sim = self.sim
        if command == "list":
            return "There are 0 of a max of 4 players online:"
        if command.startswith("execute if block"):
            return "Test passed" if any(h["linked"] and h["ground"] for h in sim.hosts.values()) else "Test failed"
        if command.startswith("execute if entity"):
            return "Test passed, count: 3" if any(h["linked"] for h in sim.hosts.values()) else "Test failed"
        actor = None
        if match := re.fullmatch(r"execute as (\S+) run (.*)", command):
            actor, command = match.groups()
        if command.startswith("cyberblock guests "):
            _, _, toggle, state = command.split()
            self.toggles.setdefault(actor, {})[toggle] = state == "on"
            return ""
        if command.startswith("cyberblock test hit "):
            _, _, _, owner, target, _ = command.split()
            host = sim.hosts.get(owner)
            ok = host and host["linked"] and target in host["ids"] and self.allows(owner, actor, "damage")
            if "drop-hits" in self.bugs:
                ok = False
            if ok:
                append(host["log"], f'[{owner}] hit: {{"t": "hit", "target": "{target}", "dmg": 5.0}}')
            if host and host["linked"] and target in host["ids"] and "leak-hits" in self.bugs:  # every other host gets it too
                for other, h in sim.hosts.items():
                    if other != owner and h["linked"]:
                        append(h["log"], f'[{other}] hit: {{"t": "hit", "target": "{target}", "dmg": 5.0}}')
            return "ok"
        if command.startswith("cyberblock test explode"):
            for name, host in sim.hosts.items():
                if host["linked"] and self.allows(name, actor, "explosions"):
                    append(host["log"], f'[{name}] explosion: {{"t": "explosion", "power": 4.0}}')
            return "ok"
        return ""


class SimEnv:
    def __init__(self, tmp, bugs=()):
        self.tmp, self.bugs, self.hosts, self.logs, self.said = tmp, set(bugs), {}, {}, []
        self.mod_version, self.ports, self.stopped = MOD, mpcheck.Ports(), []
        self.server_obj = None

    def say(self, line):
        self.said.append(line)

    def server(self, scenario, max_hosts=1):
        self.max_hosts = max_hosts
        self.server_obj = SimServer(self.tmp, self.ports.server(scenario), self, self.bugs)
        self.server_obj.log.path.write_text("Done (1s)!\n", encoding="utf-8")
        self.logs["server"] = self.server_obj.log
        return self.server_obj

    def make_world(self):
        return "w", self.tmp

    def client(self, name, *, join=None, world=None, version=None, run_dir=None):
        log = LogFile(self.tmp / f"client-{name}.log")
        log.path.write_text("", encoding="utf-8")
        proc = SimProc()
        c = mpcheck.Client(name, self.ports.client(name), self.ports.frame(name), proc, log)
        c.wait_listening = lambda timeout=None: None
        srv = self.server_obj
        if version and srv:
            append(srv.log, f"[Server thread/INFO]: {name} lost connection: Cyberblock version mismatch: server {MOD}, your mod {version}. Update from the modpack.")
        elif join and srv:
            append(srv.log, f"[Server thread/INFO]: handshake: {name} mod {MOD} dll  -> guest (no DLL)")
        if world:
            self.world_client = c
        return c

    def fakehost(self, client, *, ver, ids_from=None, layers=(2,), modes=(), cmds=(), hold=900, no_setup=True):
        name, srv = client.name, self.server_obj
        log = LogFile(self.tmp / f"fakehost-{name}-{len(self.hosts)}.log")
        log.path.write_text("", encoding="utf-8")
        proc = SimProc()
        base = ids_from if ids_from is not None else 9007199254740993
        host = {"log": log, "linked": False, "ground": False, "ids": {str(base), str(base + 1), str(base + 2)}}
        if name in self.hosts:  # a host returning: the old log is replaced, the ids stay
            host["ids"] = self.hosts[name]["ids"]
        self.hosts[name] = host
        fh = mpcheck.FakeHost(name, proc, log, client)
        stop = proc.stop

        def stop_host():
            stop()
            host["linked"] = False
            if "no-session-end" not in self.bugs:
                append(srv.log, f"[Server thread/INFO]: session: {name} ended (link lost)")
        proc.stop = stop_host
        if srv is None:  # singleplayer: the integrated server speaks in the client's log
            append(client.log, f"handshake: server {MOD} -> host")
            for line in ('session: {"t": "session", "state": "host"}', "align: {}", 'blocks: {"t": "blocks", "reset": true}', "perks: {}",
                         "cyberware: {}", 'binds: {"keys": {"camo": 79}}', "explosion: {}", "scan: {}"):
                append(log, f"[{name}] {line}")
            proc.running = False
            return fh
        if ver == MOD:
            append(srv.log, f"[Server thread/INFO]: handshake: {name} mod {MOD} dll {ver} -> host (ok)")
            append(srv.log, f"[Server thread/INFO]: session: {name} started")
            append(log, f'[{name}] session: {{"t": "session", "state": "host"}}')
            append(log, f'[{name}] blocks: {{"t": "blocks", "reset": true, "c": []}}')
            host["linked"] = True
            # the ground the fake host sent when its link opened was dropped (it was no host yet): only what it sends in
            # answer to `session state:host` reaches the server, as with the real client
            answer = fakehost.Cyberpunk(frozenset(layers), frozenset(modes), ids_from, name).reply(SESSION_HOST)
            host["ground"] = any(m["t"] == "ground" and m["c"] for m in answer)
        else:
            append(srv.log, f"[Server thread/INFO]: handshake: {name} mod {MOD} dll {ver} -> guest (plugin differs)")
            append(log, f'[{name}] session: {{"t": "session", "state": "refused", "reason": "Cyberblock: your Cyberpunk plugin is {ver} but your mod is {MOD}; you can play as a guest."}}')
        return fh

    def stop_all(self):
        self.stopped.append(True)


class StubFlooder:
    """Stands in for the flooding DLL link: it links as a host, and `go` makes the simulated server react like the real one."""
    sim = None

    def __init__(self, port, ver, **kwargs):
        self.ver, self.go = ver, threading.Event()

    def start(self):
        log = StubFlooder.sim.server_obj.log
        append(log, f"[Server thread/INFO]: handshake: CbHost1 mod {self.ver} dll {self.ver} -> host (ok)")

        def react():
            self.go.wait(5)
            append(log, "[Server thread/WARN]: relay: CbHost1 dropped ground x 812 (rate)")
            append(log, "[Server thread/INFO]: CbHost1 lost connection: Cyberblock: too many messages from your Cyberpunk link")
            append(log, "[Server thread/INFO]: session: CbHost1 ended (overflow)")

        threading.Thread(target=react, daemon=True).start()

    def stop(self):
        self.go.set()


def run_scenario(sim, name):
    report = mpcheck.Report(name, sim.say)
    try:
        mpcheck.SCENARIO_FUNCS[name](sim, report)
    except mpcheck.Abort:
        pass
    return report


def fresh(folder):
    folder.mkdir(parents=True)
    return folder


def failed(report):
    return [label for label, ok, _ in report.checks if not ok]


def test_guest_scenario_passes_against_an_ideal_server(tmp_path):
    rep = run_scenario(SimEnv(tmp_path), "guest")
    assert rep.ok, failed(rep)
    labels = [label for label, _, _ in rep.checks]
    assert any("`guests damage off`" in x for x in labels) and any("`guests explosions off`" in x for x in labels) and len(labels) == 13


@pytest.mark.parametrize("bug, expected", [("ignore-toggles", {"with `guests damage off` the hit does not reach it", "with `guests explosions off` the explosion does not reach it"}),
                                           ("drop-hits", {"a guest hit reaches the fake host (`hit` for its proxy)", "with `guests damage on` it reaches it again"})])
def test_guest_scenario_notices_a_server_that_ignores_the_toggles_or_drops_hits(tmp_path, bug, expected):
    rep = run_scenario(SimEnv(tmp_path, bugs=[bug]), "guest")
    assert expected <= set(failed(rep)) and not rep.ok


def test_guest_scenario_runs_the_guest_actions_as_the_guest_not_from_the_console(tmp_path, monkeypatch):
    """From the RCON console there is no player behind a hit or an explosion, and the server always allows those: the toggle
    checks would fail and the guest path would never be tested."""
    sent, real = [], mpcheck.Server.test
    monkeypatch.setattr(mpcheck.Server, "test", lambda self, command, as_player=None: sent.append(as_player) or real(self, command, as_player))
    rep = run_scenario(SimEnv(fresh(tmp_path / "a")), "guest")
    assert rep.ok, failed(rep)
    assert sent and set(sent) == {"CbGuest1"}
    monkeypatch.setattr(mpcheck.Server, "test", lambda self, command, as_player=None: real(self, command))  # from the console
    rep = run_scenario(SimEnv(fresh(tmp_path / "b")), "guest")
    assert {"with `guests damage off` the hit does not reach it", "with `guests explosions off` the explosion does not reach it"} <= set(failed(rep))


def test_a_guest_command_for_a_player_who_is_not_online_is_a_clear_error(tmp_path):
    class S(mpcheck.Server):
        def rcon(self, command):
            assert command == "execute as CbGuest1 run cyberblock test explode 1 2 3 4"
            return "No entity was found"

    with pytest.raises(MpError, match="CbGuest1 is not on the server"):
        S(1, 2, "pw", None, LogFile(tmp_path / "s.log"), tmp_path).test("explode 1 2 3 4", as_player="CbGuest1")


def test_the_ground_shows_only_because_the_fake_host_re_sends_it_when_it_becomes_the_host(tmp_path, monkeypatch):
    """The client drops what the DLL sends before it is the host; a fake host that does not answer `session host` with the
    ground leaves the guest and reconnect scenarios without barriers."""
    monkeypatch.setattr(fakehost.Cyberpunk, "reply", lambda self, m: [])
    guest, reconnect = run_scenario(SimEnv(fresh(tmp_path / "a")), "guest"), run_scenario(SimEnv(fresh(tmp_path / "b")), "reconnect")
    assert "the ground is there as barrier blocks" in failed(guest) and "the ground and the proxies are there" in failed(reconnect)


def test_guest_scenario_stops_when_the_host_never_becomes_a_host(tmp_path):
    class Silent(SimEnv):
        def fakehost(self, client, **kw):
            fh = super().fakehost(client, **{**kw, "ver": "9.9.9"})  # the server answers guest
            return fh

    rep = run_scenario(Silent(tmp_path), "guest")
    assert failed(rep) == ["CbHost1 is a host"] and len(rep.checks) == 1  # aborted: nothing after it ran


def test_versions_scenario_passes_and_checks_both_messages(tmp_path):
    rep = run_scenario(SimEnv(tmp_path), "versions")
    assert rep.ok, failed(rep)
    assert [label for label, _, _ in rep.checks] == ["a DLL of another version plays as a guest", "the DLL is told `session refused` with the plugin and mod versions",
                                                     "no host session starts", "a mod of another major.minor version is refused with the modpack message"]


def test_reconnect_scenario_passes_and_fails_when_the_session_never_ends(tmp_path):
    rep = run_scenario(SimEnv(tmp_path), "reconnect")
    assert rep.ok, failed(rep)
    bad = run_scenario(SimEnv(tmp_path, bugs=["no-session-end"]), "reconnect")
    assert "the session ended when the link dropped" in failed(bad)


def test_twohosts_scenario_passes_and_notices_a_hit_that_leaks_to_the_other_host(tmp_path):
    sim = SimEnv(tmp_path)
    rep = run_scenario(sim, "twohosts")
    assert rep.ok, failed(rep)
    assert sim.max_hosts == 2 and {"CbHost1", "CbHost2"} <= set(sim.hosts) and sim.hosts["CbHost1"]["ids"] == sim.hosts["CbHost2"]["ids"]  # the ids do collide
    leaky = run_scenario(SimEnv(tmp_path, bugs=["leak-hits"]), "twohosts")
    assert "a hit on id 5000 of host 1 reaches only host 1" in failed(leaky)


def test_singleplayer_scenario_passes_when_the_relay_shows_the_whole_story(tmp_path):
    rep = run_scenario(SimEnv(tmp_path), "singleplayer")
    assert rep.ok, failed(rep)
    assert {"the six binds arrive", "a TNT explosion reaches the DLL", "the block resync starts with `blocks reset:true`"} <= {label for label, _, _ in rep.checks}


def test_flood_scenario_checks_the_drop_log_the_kick_and_that_the_server_survives(tmp_path, monkeypatch):
    sim = SimEnv(tmp_path)
    StubFlooder.sim = sim
    monkeypatch.setattr(mpcheck, "Flooder", StubFlooder)
    rep = run_scenario(sim, "flood")
    assert rep.ok, failed(rep)
    assert len(rep.checks) == 5


def test_a_scenario_that_cannot_start_is_reported_as_a_failure_not_a_crash(tmp_path):
    class Broken(SimEnv):
        def server(self, scenario, max_hosts=1):
            raise MpError("port 25914 (test server) is already in use: not touching whatever listens there")

    said = []
    reports = mpcheck.run_scenarios(Broken(tmp_path), ["guest", "versions"], said.append)
    assert [r.ok for r in reports] == [False, False] and "already in use" in reports[0].checks[0][2]
    assert mpcheck.summary(reports, said.append) == 1 and any("could not run" in line for line in said)


def test_run_scenarios_stops_everything_between_scenarios_and_prints_logs_of_failures(tmp_path):
    sim = SimEnv(tmp_path, bugs=["drop-hits"])
    said = []
    reports = mpcheck.run_scenarios(sim, ["guest"], said.append)
    assert not reports[0].ok and sim.stopped == [True]
    assert any("last lines of the server log" in line for line in said)
    sim2, said2 = SimEnv(tmp_path), []
    ok_reports = mpcheck.run_scenarios(sim2, ["versions"], said2.append)
    assert ok_reports[0].ok and not any("last lines" in line for line in said2) and mpcheck.summary(ok_reports, said2.append) == 0


def test_main_makes_its_output_line_buffered_so_a_captured_run_shows_progress(monkeypatch):
    import io
    out = io.TextIOWrapper(io.BytesIO(), encoding="utf-8")  # like a pipe or a file: block-buffered
    monkeypatch.setattr(mpcheck.sys, "stdout", out)
    assert not out.line_buffering
    assert mpcheck.main(["guest", "--dry-run"]) == 0
    assert out.line_buffering


def test_summary_of_nothing_is_a_failure():
    assert mpcheck.summary([], lambda m: None) == 1


# ---- the flooding DLL link --------------------------------------------------------------------------------------------------
def test_flooder_says_hello_waits_for_go_and_then_floods_valid_ground_messages():
    got = []

    async def run():
        async def handler(ws):
            async for raw in ws:
                got.append(json.loads(raw))

        async with serve(handler, "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            flooder = mpcheck.Flooder(port, "1.0.0", seconds=0.3, rate_hz=2000)
            flooder.start()
            await asyncio.sleep(0.4)
            before = len(got)
            flooder.go.set()
            await asyncio.sleep(0.8)
            flooder.stop()
            return before

    before = asyncio.run(run())
    assert [m["t"] for m in got[:2]] == ["hello", "state"] and got[0]["ver"] == "1.0.0" and before == 2  # nothing flooded before `go`
    grounds = [m for m in got if m["t"] == "ground"]
    assert len(grounds) > 100 and all(len(m["c"]) == 4 and -8 <= m["c"][0] <= 8 and -8 <= m["c"][1] <= 8 and m["c"][2:] == [63, 63] for m in grounds)


# ---- the command line --------------------------------------------------------------------------------------------------------
def test_dry_run_prints_every_command_and_starts_nothing(capsys, monkeypatch):
    monkeypatch.setattr(mpcheck, "Env", lambda *a, **k: pytest.fail("a dry run must not create an environment"))
    assert mpcheck.main(["--all", "--dry-run"]) == 0
    out = capsys.readouterr().out
    for name in mpcheck.SCENARIOS:
        assert f"== {name}" in out
    assert out.count("runServer") == 5 and "-Pcyberblock.serverPort=25918" in out and "JAVA_TOOL_OPTIONS=-Dcyberblock.devtools=true" in out
    assert "--quickPlaySingleplayer" in out and "--quickPlayMultiplayer 127.0.0.1:25915" in out and "tools.fakehost" in out


def test_guest_dry_run_shows_one_client_and_nothing_else(capsys):
    assert mpcheck.main(["--guest", "51234", "--dry-run"]) == 0
    out = capsys.readouterr().out.strip().splitlines()
    assert len(out) == 1 and "--username CbGuest1 --quickPlayMultiplayer 127.0.0.1:51234" in out[0] and "runServer" not in out[0]


@pytest.mark.parametrize("argv", [[], ["nonsense"], ["--all", "guest"], ["--guest", "80"], ["--guest", "70000"], ["--guest", "51234", "guest"], ["--guest", "51234", "--all"],
                                  ["--all", "--server-port-base", "25596"], ["--all", "--client-port-base", "25600"], ["--all", "--frame-prefix", "Frame"]])
def test_bad_arguments_are_refused(argv, capsys):
    with pytest.raises(SystemExit) as e:
        mpcheck.main(argv)
    assert e.value.code == 2


def test_the_main_checkout_is_refused_so_gradle_never_touches_the_users_mc_folder(tmp_path, capsys, monkeypatch):
    (tmp_path / ".git").mkdir()  # a main checkout has a .git folder; a worktree has a .git file
    monkeypatch.setattr(mpcheck, "Env", lambda *a, **k: pytest.fail("no environment in the main checkout"))
    assert mpcheck.main(["guest"], repo=tmp_path) == 2
    assert "main checkout" in capsys.readouterr().err


def test_a_worktree_is_accepted_and_runs_the_scenarios_it_was_given(tmp_path, monkeypatch):
    (tmp_path / ".git").write_text("gitdir: elsewhere\n", encoding="utf-8")
    ran = []

    class E:
        def __init__(self, repo, ports, java, keep=False, commit_limit=60.0):
            ran.append(("env", repo, commit_limit, keep))

        def close(self):
            ran.append("closed")

    monkeypatch.setattr(mpcheck, "Env", E)
    monkeypatch.setattr(mpcheck.play, "find_jdk", lambda candidates: Path("C:/jdk"))
    monkeypatch.setattr(mpcheck, "run_scenarios", lambda env, names: [mpcheck.Report("x")] and [])
    monkeypatch.setattr(mpcheck, "summary", lambda reports: 0)
    assert mpcheck.main(["guest", "--commit-limit", "50", "--keep"], repo=tmp_path) == 0
    assert ran == [("env", tmp_path, 50.0, True), "closed"]


def test_no_jdk_is_a_clear_error(tmp_path, capsys, monkeypatch):
    (tmp_path / ".git").write_text("gitdir: elsewhere\n", encoding="utf-8")
    monkeypatch.setattr(mpcheck.play, "find_jdk", lambda candidates: None)
    assert mpcheck.main(["guest"], repo=tmp_path) == 1 and "no JDK" in capsys.readouterr().err


def test_the_module_never_prints_or_logs_the_password_by_construction():
    """The secret goes to the properties file and the RCON login only, and the Server dataclass keeps it out of its repr."""
    src = (REPO / "tools" / "mpcheck.py").read_text(encoding="utf-8")
    assert not re.findall(r"(?:print|say)\(.*(?:password|_password)", src)
    assert "field(repr=False)" in src and 'env.pop("APEX_FTP_PASS", None)' in src
    assert src.count("self._password") == 3  # created, written to server.properties, handed to the Server object
