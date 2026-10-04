"""tools/mpcheck.py without Minecraft: the pieces (commit wait, files, command lines, RCON, logs), the scenario logic against a
simulated server, the CLI and the secret handling. The real run (`tools.mpcheck --all`) is the integrator's."""
import asyncio
import json
import math
import re
import socket
import struct
import threading
import time
from pathlib import Path

import pytest
from websockets.asyncio.server import serve

from tools import cbmath, fakehost, mpcheck
from tools.mpcheck import LogFile, MpError

REPO = Path(__file__).resolve().parents[1]
MOD = "1.0.0"
SESSION_HOST = {"t": "session", "state": "host", "reason": "", "perms": {"explosions": True, "damage": True, "hacks": True, "devices": True, "spawns": False}}


@pytest.fixture(autouse=True)
def quick(monkeypatch):
    """Every wait of the scenarios shrinks to a fraction of a second (the real values are minutes)."""
    for name, value in (("T_QUIET", 0.05), ("T_EFFECT", 0.6), ("T_JOIN", 0.6), ("T_SERVER", 0.6), ("T_CLIENT", 0.6), ("FLOOD_S", 0.1), ("T_STOP", 0.05),
                        ("LOGIN_GAP_S", 0.0), ("T_ENV", 1.0), ("T_POLL", 0.01), ("COOLDOWN_S", 0.7),
                        ("T_WRECK", 0.6), ("WRECK_MIN_S", 0.04), ("DEVICE_PLACE_TIMEOUT", 1.0), ("GUEST_GAP_S", 0.0), ("BREACH_COOLDOWN_S", 0.0)):
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
                      "webMap": False, "joinPassword": ""}


def test_server_json_sets_max_hosts_and_keeps_everything_else():
    example = (REPO / "server" / "server.json.example").read_text(encoding="utf-8")
    two = json.loads(mpcheck.server_json(example, 2))
    assert two["maxHosts"] == 2 and {k: v for k, v in two.items() if k != "maxHosts"} == {k: v for k, v in json.loads(example).items() if k != "maxHosts"}
    locked = json.loads(mpcheck.server_json(example, 1, "hunter2"))
    assert locked["joinPassword"] == "hunter2" and json.loads(mpcheck.server_json(example, 1))["joinPassword"] == ""


def test_a_join_password_server_gets_a_random_password_that_no_repr_shows(env):
    server = env.server("joinpassword", join_password=True)
    config = json.loads((server.dir / "config" / "cyberblock" / "server.json").read_text(encoding="utf-8"))
    assert config["joinPassword"] == server.join_password and len(server.join_password) >= 12
    assert server.join_password not in repr(server)
    env.stop_all()
    assert env.server("guest").join_password == "" and json.loads((env.servers[-1].dir / "config" / "cyberblock" / "server.json").read_text(encoding="utf-8"))["joinPassword"] == ""


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


def test_the_singleplayer_wait_for_the_host_reaches_the_fakehost_as_a_keyword(monkeypatch):
    """--wait-host (the scenario waits for `session host`, the world) is passed only when asked, as a keyword of run()."""
    from tools import fakehost
    argv = mpcheck.fakehost_argv(port=1, name="x", ver=None, frame="f", out_dir=Path("O"), layers=(2, 6), wait_host=150)[4:]
    assert argv[argv.index("--wait-host") + 1] == "150"
    assert "--wait-host" not in mpcheck.fakehost_argv(port=1, name="x", ver=None, frame="f", out_dir=Path("O"))
    seen = []

    async def fake_run(*args, **kwargs):
        seen.append((args, kwargs))
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    monkeypatch.setattr("sys.argv", ["fakehost", *argv])
    assert fakehost.main() == 0 and seen[0][1] == {"wait_host": 150.0} and len(seen[0][0]) == 13


# ---- ports and frames ------------------------------------------------------------------------------------------------------
def test_default_ports_are_package_1t_and_stay_in_the_phase_1_ranges():
    p = mpcheck.Ports()
    p.check()
    servers = {name: p.server(name) for name in mpcheck.SCENARIOS}
    assert servers == {"singleplayer": 25914, "guest": 25914, "versions": 25915, "flood": 25916, "reconnect": 25917, "twohosts": 25918, "joinpassword": 25915,
                       "replica": 25914, "twohosts2": 25916, "meetup": 25915, "devices": 25917, "figures": 25917, "breach": 25917, "hub": 25916}
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
    assert mpcheck.SCENARIOS == ("singleplayer", "guest", "versions", "flood", "reconnect", "twohosts", "joinpassword", "replica", "twohosts2", "meetup", "devices",
                                 "figures", "breach", "hub")


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


@pytest.mark.parametrize("answer, ok", [("Test passed", True), ("Test passed. Count: 3", True), ("  Test passed", True), ("Test failed", False), ("", False)])
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

    def __init__(self, tmp, port, env_sim, bugs, join_password=""):
        super().__init__(port, 0, "pw", None, LogFile(tmp / f"server-{port}.log"), tmp, join_password)
        self.sim, self.bugs, self.toggles = env_sim, bugs, {}
        self.locked, self.verified, self.wrong, self.pending = set(), set(), {}, {}
        self.grounded = False  # a host's ground stands as replica blocks (from Phase 3 it stays when the host leaves)

    def login(self, actor, typed):
        """As JoinLock.login: the right password verifies (and the waiting host link is granted), five wrong ones kick."""
        if actor not in self.locked:
            return ""
        if "log-password" in self.bugs:
            append(self.log, f"[Server thread/INFO]: login: {actor} typed {typed}")
        if typed == self.join_password:
            self.locked.discard(actor)
            self.verified.add(actor)
            append(self.log, f"[Server thread/INFO]: login: {actor} verified")
            if actor in self.pending and "no-rehandshake" not in self.bugs:
                self.sim.grant(actor, *self.pending.pop(actor))
            return ""
        self.wrong[actor] = self.wrong.get(actor, 0) + 1
        if self.wrong[actor] >= 5:
            append(self.log, f"[Server thread/INFO]: {actor} lost connection: Cyberblock: wrong join password 5 times. Ask the server owner for the password.")
        return ""

    def allows(self, owner, actor, toggle):
        """As 1N's Permissions.allows: no player behind the action (the console), the owner or another host is always allowed;
        a guest only while the owner's toggle is on."""
        guest = actor is not None and actor != owner and actor not in self.sim.hosts
        return not guest or self.toggles.get(owner, {}).get(toggle, True) or "ignore-toggles" in self.bugs

    def rcon(self, command):
        sim = self.sim
        if command == "list":
            return "There are 0 of a max of 4 players online:"
        # Phase 3: host ground is replica blocks, kept after the link drops; the bug is the old rule (barriers that go with the link)
        barriers = "barrier-ground" in self.bugs and any(h["linked"] and h["ground"] for h in sim.hosts.values())
        if command.startswith("execute unless block") and command.endswith(" minecraft:air"):
            return "Test passed" if barriers or (self.grounded and "barrier-ground" not in self.bugs) else "Test failed"
        if command.startswith("execute if block") and command.endswith(" minecraft:barrier"):
            return "Test passed" if barriers else "Test failed"
        if command.startswith("execute if entity"):
            return "Test passed. Count: 3" if any(h["linked"] for h in sim.hosts.values()) else "Test failed"
        actor = None
        if match := re.fullmatch(r"execute as (\S+) run (.*)", command):
            actor, command = match.groups()
        if command.startswith("login "):
            return self.login(actor, command[len("login "):])
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
        if command.startswith("cyberblock test breach practice"):  # Breach.open: a player locked by the join password is refused
            if actor in self.locked and "breach-ignores-lock" not in self.bugs:
                append(self.log, f"[Server thread/INFO]: breach: {actor} refused (locked by the join password)")
            else:
                append(self.log, f"[Server thread/INFO]: breach: {actor} opened 4x4 on terminal 0 (security 1, firewall 0, buffer 4, 30 s, daemons [], seed 1)")
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

    def server(self, scenario, max_hosts=1, join_password=False):
        self.max_hosts = max_hosts
        self.server_obj = SimServer(self.tmp, self.ports.server(scenario), self, self.bugs, "sim-join-pw" if join_password else "")
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
            if srv.join_password and name not in srv.verified:
                srv.locked.add(name)
                append(srv.log, f"[Server thread/INFO]: login: {name} is locked until /login (join password)")
            append(srv.log, f"[Server thread/INFO]: handshake: {name} mod {MOD} dll  -> guest (no DLL)")
            stop = proc.stop

            def leave():
                stop()
                srv.locked.discard(name)
                append(srv.log, f"[Server thread/INFO]: {name} lost connection: Disconnected")
            proc.stop = leave
        if world:
            self.world_client = c
        return c

    def fakehost(self, client, *, ver, ids_from=None, layers=(2,), modes=(), cmds=(), hold=900, no_setup=True, wait_host=None):
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
        if ver == MOD and name in srv.locked:  # the join password: the link waits as a guest until /login
            append(srv.log, f"[Server thread/INFO]: handshake: {name} mod {MOD} dll {ver} -> guest (Cyberblock: type /login <password> first; "
                            "your Cyberpunk link starts right after that)")
            srv.pending[name] = (host, layers, modes, ids_from)
        elif ver == MOD:
            self.grant(name, host, layers, modes, ids_from)
        else:
            append(srv.log, f"[Server thread/INFO]: handshake: {name} mod {MOD} dll {ver} -> guest (plugin differs)")
            append(log, f'[{name}] session: {{"t": "session", "state": "refused", "reason": "Cyberblock: your Cyberpunk plugin is {ver} but your mod is {MOD}; you can play as a guest."}}')
        return fh

    def grant(self, name, host, layers, modes, ids_from):
        """The server makes the link a host: the session starts and the fake host answers `session host` with its ground."""
        srv, log = self.server_obj, host["log"]
        append(srv.log, f"[Server thread/INFO]: handshake: {name} mod {MOD} dll {MOD} -> host (ok)")
        append(srv.log, f"[Server thread/INFO]: session: {name} started")
        append(log, f'[{name}] session: {{"t": "session", "state": "host"}}')
        append(log, f'[{name}] blocks: {{"t": "blocks", "reset": true, "c": []}}')
        host["linked"] = True
        # the ground the fake host sent when its link opened was dropped (it was no host yet): only what it sends in
        # answer to `session state:host` reaches the server, as with the real client
        answer = fakehost.Cyberpunk(frozenset(layers), frozenset(modes), ids_from, name).reply(SESSION_HOST)
        host["ground"] = any(m["t"] == "ground" and m["c"] for m in answer)
        srv.grounded = srv.grounded or host["ground"]

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
    ground leaves the guest and reconnect scenarios without ground."""
    monkeypatch.setattr(fakehost.Cyberpunk, "reply", lambda self, m: [])
    guest, reconnect = run_scenario(SimEnv(fresh(tmp_path / "a")), "guest"), run_scenario(SimEnv(fresh(tmp_path / "b")), "reconnect")
    assert "the ground is there (replica blocks)" in failed(guest) and "the ground and the proxies are there" in failed(reconnect)


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


def test_reconnect_scenario_fails_when_host_ground_is_still_barriers(tmp_path):
    """Phase 3: host ground is replica blocks that stay while the host is away; a barrier left there is the pre-replica rule."""
    bad = run_scenario(SimEnv(tmp_path, bugs=["barrier-ground"]), "reconnect")
    assert "the ground stays as replica blocks, no barrier left" in failed(bad)


def test_twohosts_scenario_passes_and_notices_a_hit_that_leaks_to_the_other_host(tmp_path):
    sim = SimEnv(tmp_path)
    rep = run_scenario(sim, "twohosts")
    assert rep.ok, failed(rep)
    assert sim.max_hosts == 2 and {"CbHost1", "CbHost2"} <= set(sim.hosts) and sim.hosts["CbHost1"]["ids"] == sim.hosts["CbHost2"]["ids"]  # the ids do collide
    leaky = run_scenario(SimEnv(tmp_path, bugs=["leak-hits"]), "twohosts")
    assert "a hit on id 5000 of host 1 reaches only host 1" in failed(leaky)


def test_joinpassword_scenario_passes_against_an_ideal_server(tmp_path):
    sim = SimEnv(tmp_path)
    rep = run_scenario(sim, "joinpassword")
    assert rep.ok, failed(rep)
    assert len(rep.checks) == 13 and sim.server_obj.verified == {"CbHost1"} and sim.server_obj.wrong == {"CbGuest1": 5}


@pytest.mark.parametrize("bug, expected", [("no-rehandshake", {"the waiting link becomes a host at once", "its session starts"}),
                                           ("log-password", {"the password is in no log"}),
                                           ("breach-ignores-lock", {"a locked player cannot open a Breach (no interaction before /login)"})])
def test_joinpassword_scenario_notices_a_link_that_keeps_waiting_or_a_logged_password(tmp_path, bug, expected):
    rep = run_scenario(SimEnv(tmp_path, bugs=[bug]), "joinpassword")
    assert expected <= set(failed(rep)) and not rep.ok


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
    assert out.count("runServer") == len(mpcheck.SCENARIOS) - 1 and "-Pcyberblock.serverPort=25918" in out and "JAVA_TOOL_OPTIONS=-Dcyberblock.devtools=true" in out
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


# ---- Phase 3 scenarios (replica, twohosts2, meetup) against a simulated server --------------------------------------------------
# The simulation is the megaplan's server for Phase 3 as far as RCON can see it: replica blocks from `ground` (air cells only), the
# `seen` rule, `clear` leaving them alone, the world kept when a host leaves, the primary host's clock and weather, Meet Up with its
# cooldown, a guest's blocks reaching a returning host. One simulated second of the fake host's script is SIM real seconds.
SIM = 0.02
SIM_TOL = 40  # the scenarios' tolerance (ticks) for the clock


class Sim3Server(SimServer):
    def __init__(self, tmp, port, env_sim, bugs, join_password=""):
        super().__init__(tmp, port, env_sim, bugs, join_password)
        self.world, self.replica, self.pos, self.player_blocks, self.changes = {}, set(), {}, [], []
        self.pending_meet, self.cool, self.arrivals, self.meet_ids = {}, {}, [], 0
        self.ticks, self.weather = 18000, "clear"
        self.days = 3  # whole Minecraft days on the clock: `time query time` answers the total ticks (3 * 24000 + the day time)
        self.wrecks = {}  # (x, y, z) -> real time it goes: 3F's ProxyManager keeps a `killed` vehicle 5 s as a wreck

    # -- the world -------------------------------------------------------------------------------------------------------------
    def place(self, msg):
        c, m = msg["c"], msg.get("m", [0] * (len(msg["c"]) // 4))
        for i in range(0, len(c), 4):
            x, z, lo, hi = c[i:i + 4]
            block = cbmath.material_block(m[i // 4])
            for y in range(lo, hi + 1):
                if block and (x, y, z) not in self.world:  # air cells only
                    self.world[(x, y, z)] = block
                    self.replica.add((x, y, z))

    def unground(self, msg):
        if msg.get("seen") is not True and "unseen-removes" not in self.bugs:
            return  # only left the probe radius: the replica stays
        if "seen-ignored" in self.bugs:
            return
        c = msg["c"]
        for i in range(0, len(c), 4):
            x, z, lo, hi = c[i:i + 4]
            for y in range(lo, hi + 1):
                if (x, y, z) in self.replica:
                    self.replica.discard((x, y, z))
                    self.world.pop((x, y, z), None)

    def apply(self, msg, due, shift):
        if msg["t"] == "killed" and msg["kind"] == "vehicle" and "no-wreck" not in self.bugs:  # the bug: the proxy just goes
            append(self.log, f"[Server thread/INFO]: figures: vehicle {msg['target']} died (wreck for 5 s)")
            self.wrecks[(-5.5 + shift, fakehost.SLAB_Y + 1.0, -0.5)] = due + 5 * SIM
        elif msg["t"] == "ground":
            self.place(msg)
        elif msg["t"] == "unground":
            self.unground(msg)
        elif msg["t"] == "clear" and "clear-wipes" in self.bugs:
            for cell in self.replica:
                self.world.pop(cell, None)
            self.replica.clear()

    def advance(self):
        sim, now = self.sim, time.monotonic()
        linked = [(n, h) for n, h in sim.hosts.items() if h["linked"] and h.get("replica")]
        for _, host in linked:
            due = [e for e in host["script"] if host["t0"] + e[0] * SIM <= now]
            host["script"] = [e for e in host["script"] if e not in due]
            for at, m in due:
                self.apply(m, host["t0"] + at * SIM, host["shift"])
        self.wrecks = {cell: end for cell, end in self.wrecks.items() if end > now}
        if "replica-leaves" in self.bugs and any(h.get("replica") and not h["linked"] for h in sim.hosts.values()):
            for cell in self.replica:
                self.world.pop(cell, None)
            self.replica.clear()
        for arrival in [a for a in self.arrivals if a[0] <= now]:
            self.arrivals.remove(arrival)
            self.pos[arrival[1]] = list(arrival[2])
        if linked:  # the primary host (the first one) drives the clock; the bug lets the last one do it
            _, host = linked[-1] if "second-host-env" in self.bugs else linked[0]
            env = fakehost.replica_env((now - host["t0"]) / SIM, host["env_hours"])
            self.ticks, self.weather = cbmath.env_ticks(*env["time"]), env["weather"]
            if "daylight-runs" in self.bugs:
                self.ticks += int((now - host["t0"]) / SIM * 20)

    # -- commands --------------------------------------------------------------------------------------------------------------
    def rcon(self, command):
        self.advance()
        if match := re.fullmatch(r"execute if block (-?\d+) (-?\d+) (-?\d+) (\S+)", command):
            if match.group(4) != "minecraft:barrier":
                cell = tuple(int(v) for v in match.groups()[:3])
                return "Test passed" if self.world.get(cell) == match.group(4) else "Test failed"
        if command == "execute if entity @e[type=cyberblock:proxy_human]":
            n = sum(2 if h.get("replica") else 3 for h in self.sim.hosts.values() if h["linked"])
            return f"Test passed. Count: {n}" if n else "Test failed"  # 26.3's commands.execute.conditional.pass_count
        if match := re.fullmatch(r"execute if entity @e\[type=cyberblock:proxy_vehicle,x=(\S+),y=(\S+),z=(\S+),distance=\.\.0\.6\]", command):
            spot = tuple(float(v) for v in match.groups())
            return "Test passed. Count: 1" if any(math.dist(spot, cell) <= 0.6 for cell in self.wrecks) else "Test failed"
        if command == "time query time":  # 26.3: commands.time.query.absolute (there is no `time query daytime`)
            return f"Clock minecraft:overworld is at {self.days * 24000 + self.ticks} tick(s)"
        if match := re.match(r'execute if predicate \{type:"minecraft:weather_check",(raining|thundering):true\}', command):
            on = self.weather in ("rain", "storm") if match.group(1) == "raining" else self.weather == "storm"
            return "Test passed" if on else "Test failed"
        if match := re.fullmatch(r"data get entity (\S+) Pos", command):
            p = self.pos.get(match.group(1))
            return f"{match.group(1)} has the following entity data: [{p[0]}d, {p[1]}d, {p[2]}d]" if p else "No entity was found"
        actor, inner = None, command
        if match := re.fullmatch(r"execute as (\S+) run (.*)", command):
            actor, inner = match.groups()
        if inner.startswith("cyberblock test place "):  # 3R's ChangeLog records it as the player's own placement
            _, _, _, x, y, z, block = inner.split()
            cell = (int(x), int(y), int(z))
            self.world[cell] = block
            self.player_blocks.append(cell)
            self.changes.append((actor, cell))
            for name, host in self.sim.hosts.items():
                if host["linked"]:
                    append(host["log"], f'[{name}] block: {{"t": "block", "op": "add", "pos": [{x}, {y}, {z}]}}')
            return "Changed the block"
        if inner == "cyberblock changes":  # 26.3: the list goes to the player behind `execute as` (its chat), RCON gets nothing
            text = f"In the last 0 min: {len(self.changes)} blocks placed, 0 removed (by " + ", ".join(sorted({a for a, _ in self.changes})) + ")"                 if self.changes else "No blocks changed in the last 0 min"
            if actor and "changes-to-nobody" not in self.bugs:
                append(LogFile(self.sim.tmp / f"client-{actor}.log"), f"[Render thread/INFO] (Minecraft) [System] [CHAT] {text}")
            return ""
        if inner == "cyberblock meetup accept":
            asker = self.pending_meet.pop(actor, None)
            return self.meet(asker, actor) if asker else ""
        if inner.startswith("cyberblock meetup "):
            if self.cool.get(actor, 0) <= time.monotonic() or "no-cooldown" in self.bugs:
                self.pending_meet[inner.split()[2]] = actor
            return ""
        return super().rcon(command)

    def meet(self, asker, target):
        now, host = time.monotonic(), self.sim.hosts.get(asker)
        if host and host["linked"]:  # a host asks: its DLL gets `vteleport` at the target's position and answers two seconds later
            self.meet_ids += 1
            p = list(self.pos[target])
            if "no-teleport" not in self.bugs:
                append(host["log"], f'[{asker}] vteleport: {json.dumps({"t": "vteleport", "id": self.meet_ids, "p": p, "yaw": 0.0, "why": "meetup"})}')
            if "no-follow" not in self.bugs:
                self.arrivals.append((now + fakehost.TELEPORT_DELAY * SIM, asker, p))
        elif target in self.sim.hosts and self.sim.hosts[target]["linked"]:  # a guest asks a host: next to the host's Steve
            self.pos[asker] = [self.pos[target][0] + 1.5, self.pos[target][1], self.pos[target][2]]
        self.cool[asker] = now + 30 * SIM
        return ""


class Sim3Env(SimEnv):
    def __init__(self, tmp, bugs=()):
        super().__init__(tmp, bugs)
        self.extra = {}

    def server(self, scenario, max_hosts=1, join_password=False):
        self.max_hosts = max_hosts
        self.server_obj = Sim3Server(self.tmp, self.ports.server(scenario), self, self.bugs)
        self.server_obj.log.path.write_text("Done (1s)!\n", encoding="utf-8")
        self.logs["server"] = self.server_obj.log
        return self.server_obj

    def client(self, name, **kw):
        c = super().client(name, **kw)
        if name.startswith("CbGuest"):
            self.server_obj.pos[name] = [3.5, 64.0, 2.5]
        return c

    def fakehost(self, client, *, shift=0, env_hours=0, **kw):
        self.extra[client.name] = {"shift": shift, "env_hours": env_hours}
        return super().fakehost(client, **kw)

    def grant(self, name, host, layers, modes, ids_from):
        super().grant(name, host, layers, modes, ids_from)
        if "replica" not in modes:
            return
        srv, extra = self.server_obj, self.extra.get(name, {})
        shift = extra.get("shift", 0)
        cp = fakehost.Cyberpunk(frozenset(layers), frozenset(modes), ids_from, name, shift, extra.get("env_hours", 0))
        for m in cp.reply(SESSION_HOST):
            if m["t"] == "ground":
                srv.place(m)
        base = ids_from if ids_from is not None else 9007199254740993
        host.update(replica=True, t0=time.monotonic(), script=list(cp.script()), shift=shift, ids=host["ids"] | {str(base + 3)}, env_hours=extra.get("env_hours", 0))
        srv.pos[name] = [fakehost.REPLICA_FEET[0] + shift, fakehost.REPLICA_FEET[1], fakehost.REPLICA_FEET[2]]
        cells = list(srv.player_blocks) + (sorted(srv.replica) if "replica-to-host" in self.bugs else [])
        if cells:  # the resync after `blocks reset:true`: every player block (a bug adds the replica)
            append(host["log"], f'[{name}] blocks: {json.dumps({"t": "blocks", "reset": False, "c": [v for cell in cells for v in cell]})}')


def run3(tmp, name, bugs=()):
    return run_scenario(Sim3Env(tmp, bugs), name)


def test_replica_scenario_passes_against_an_ideal_server(tmp_path):
    sim = Sim3Env(tmp_path)
    rep = run_scenario(sim, "replica")
    assert rep.ok, failed(rep)
    labels = [label for label, _, _ in rep.checks]
    assert len(labels) == 26 and sum(label.startswith("the clock and weather follow Cyberpunk") for label in labels) == 4
    assert ("the clock and weather follow Cyberpunk: 23:00 rain (tick 17000)") in labels and sim.max_hosts == 1


@pytest.mark.parametrize("bug, expected", [
    ("seen-ignored", {"an `unground` with `seen:true` takes its scaffold away"}),
    ("unseen-removes", {"an `unground` without `seen` keeps its scaffold (it only left the probe radius)"}),
    ("clear-wipes", {"`clear` and the ground sent again change nothing: the street stands, the removed scaffold stays removed, the other stays"}),
    ("replica-leaves", {"the replica stays while the host is away"}),
    ("replica-to-host", {"the replica is host ground: it never comes back to the host as a player block", "the resync carries no replica block"}),
    ("daylight-runs", {"the daylight cycle is off: the time stands between two env messages"}),
    ("no-wreck", {"the car V destroyed (`killed`) stays a wreck for about 5 s, then goes"}),
])
def test_replica_scenario_notices_each_replica_bug(tmp_path, bug, expected):
    rep = run3(tmp_path, "replica", [bug])
    assert expected <= set(failed(rep)) and not rep.ok


def test_replica_scenario_checks_the_wall_the_guest_builds_and_the_changes_list(tmp_path):
    sim = Sim3Env(tmp_path)
    rep = run_scenario(sim, "replica")
    assert rep.ok and sim.server_obj.player_blocks == [(2, 64, 0), (2, 65, 0)] and {a for a, _ in sim.server_obj.changes} == {"CbGuest1"}
    host_log = sim.hosts["CbHost1"]["log"].text()
    assert "2, 64, 0" in host_log and "nc_" not in host_log  # the returning host heard of the wall, and of nothing else


def test_replica_scenario_reads_the_changes_list_from_the_host_chat(tmp_path):
    """26.3 sends `/cyberblock changes` to the player behind `execute as`: a list that reaches no chat fails the check."""
    rep = run3(tmp_path, "replica", bugs=["changes-to-nobody"])
    assert failed(rep) == ["`/cyberblock changes` lists what the guest built"]


def test_replica_scenario_runs_the_guest_actions_as_the_guest(tmp_path, monkeypatch):
    sent = []
    real = Sim3Server.rcon
    monkeypatch.setattr(Sim3Server, "rcon", lambda self, c: sent.append(c) or real(self, c))
    assert run3(tmp_path, "replica").ok
    assert [c for c in sent if "test place" in c] == [f"execute as CbGuest1 run cyberblock test place {x} {y} {z} minecraft:stone" for x, y, z in mpcheck.REPLICA_WALL]
    assert "execute as CbHost1 run cyberblock changes" in sent


def test_twohosts2_scenario_passes_and_the_second_host_is_shifted_with_the_same_ids_and_another_sky(tmp_path):
    sim = Sim3Env(tmp_path)
    rep = run_scenario(sim, "twohosts2")
    assert rep.ok, failed(rep)
    assert sim.max_hosts == 2 and sim.extra["CbHost2"] == {"shift": 8, "env_hours": 12} and sim.extra["CbHost1"] == {"shift": 0, "env_hours": 0}
    assert sim.hosts["CbHost1"]["ids"] == sim.hosts["CbHost2"]["ids"] and "5000" in sim.hosts["CbHost1"]["ids"] and len(rep.checks) == 13


@pytest.mark.parametrize("bug, expected", [
    ("leak-hits", {"a hit on id 5000 of host 1 reaches only host 1"}),
    ("second-host-env", {"only the first host's clock drives the world (the second host's is 12 hours off and never shows)"}),
])
def test_twohosts2_scenario_notices_a_leaking_hit_and_a_second_clock(tmp_path, bug, expected):
    rep = run3(tmp_path, "twohosts2", [bug])
    assert expected <= set(failed(rep)) and not rep.ok


def test_meetup_scenario_passes_and_checks_both_directions_and_the_cooldown(tmp_path):
    sim = Sim3Env(tmp_path)
    rep = run_scenario(sim, "meetup")
    assert rep.ok, failed(rep)
    assert len(rep.checks) == 9 and sim.server_obj.meet_ids == 2  # the cooldown-refused request never reached the DLL
    host_log = sim.hosts["CbHost1"]["log"].text()
    assert host_log.count("vteleport:") == 2 and '"why": "meetup"' in host_log


@pytest.mark.parametrize("bug, expected", [
    ("no-teleport", {"a Meet Up from a host to a guest sends `vteleport` (why meetup) to the host's DLL, at the guest's position"}),
    ("no-follow", {"after `vteleported ok` Steve follows V to that spot"}),
    ("no-cooldown", {"a second request inside the 30 s cooldown sends no new `vteleport`"}),
])
def test_meetup_scenario_notices_each_meetup_bug(tmp_path, bug, expected):
    rep = run3(tmp_path, "meetup", [bug])
    assert expected <= set(failed(rep)) and not rep.ok


def test_the_phase_3_scenarios_ask_a_fake_host_for_the_replica_mode_with_no_layer(tmp_path):
    seen = []

    class Spy(Sim3Env):
        def fakehost(self, client, **kw):
            seen.append((client.name, kw.get("layers"), kw.get("modes"), kw.get("wait_host"), kw.get("shift"), kw.get("env_hours")))
            return super().fakehost(client, **kw)

    for name in ("replica", "twohosts2", "meetup"):
        run_scenario(Spy(fresh(tmp_path / name)), name)
    assert {s[1:3] for s in seen} == {((), ("replica",))} and all(s[3] == mpcheck.T_JOIN for s in seen)
    assert [s for s in seen if s[0] == "CbHost2"] == [("CbHost2", (), ("replica",), mpcheck.T_JOIN, 8, 12)]


def test_the_phase_3_scenarios_use_the_borrowed_phase_7_block_when_asked(capsys):
    argv = ["replica", "twohosts2", "meetup", "--dry-run", "--client-port-base", "25814", "--server-port-base", "25975",
            "--frame-prefix", "Local\\CyberblockFrame3PM"]
    assert mpcheck.main(argv) == 0
    out = capsys.readouterr().out
    assert "-Pcyberblock.serverPort=25975" in out and "-Pcyberblock.serverPort=25976" in out and "-Pcyberblock.serverPort=25977" in out
    assert "-Pcyberblock.port=25814" in out and "Local\\CyberblockFrame3PMH" in out and out.count("--replica") == 3 and "--layer" not in out
    ports = mpcheck.Ports(25814, 25975, "Local\\CyberblockFrame3PM")
    ports.check()
    assert {ports.server(n) for n in mpcheck.SCENARIOS} <= set(range(25975, 25980)) and {ports.client(n) for n in mpcheck.OFFLINE_NAMES} <= set(range(25810, 25820))


def test_replica_argv_carries_shift_and_env_hours_only_when_set():
    base = dict(port=25814, name="CbHost2", ver="1.0.0", frame="Local\\F", out_dir=Path("o"), modes=("replica",), layers=())
    plain, shifted = mpcheck.fakehost_argv(**base), mpcheck.fakehost_argv(**base, shift=8, env_hours=12)
    assert "--shift" not in plain and "--env-hours" not in plain and "--replica" in plain and not any(a.startswith("--layer") for a in plain)
    assert shifted[shifted.index("--shift") + 1] == "8" and shifted[shifted.index("--env-hours") + 1] == "12"


def test_the_replica_argv_parses_with_the_real_fakehost(monkeypatch):
    seen = []

    async def fake_run(*args, **kwargs):
        seen.append((args, kwargs))
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    argv = mpcheck.fakehost_argv(port=25814, name="CbHost2", ver="1.0.0", frame="Local\\F", out_dir=Path("o"), ids_from=5000, layers=(), modes=("replica",),
                                 wait_host=150.0, shift=8, env_hours=12)
    monkeypatch.setattr("sys.argv", ["fakehost", *argv[4:]])  # after `python -u -m tools.fakehost`
    assert fakehost.main() == 0
    args, kwargs = seen[0]
    assert args[9] == frozenset({"replica"}) and args[8] == frozenset() and kwargs == {"wait_host": 150.0, "shift": 8, "env_hours": 12}


def test_reported_cells_reads_the_blocks_and_block_lines_of_a_log_written_with_windows_line_ends():
    text = ('[CbHost1] session: {"t": "session"}\r\n[CbHost1] blocks: {"t": "blocks", "reset": true, "c": [2, 64, 0, 2, 65, 0]}\r\n'
            '[CbHost1] block: {"t": "block", "op": "add", "pos": [5, 64, 5]}\r\n[CbHost2] block: {"t": "block", "op": "add", "pos": [9, 9, 9]}\r\n'
            '[CbHost1] blocks: not json\r\n')
    assert mpcheck.reported_cells(text, "CbHost1") == {(2, 64, 0), (2, 65, 0), (5, 64, 5)}
    assert mpcheck.reported_cells(text.replace("\r\n", "\n"), "CbHost1") == {(2, 64, 0), (2, 65, 0), (5, 64, 5)} and mpcheck.reported_cells("", "CbHost1") == set()


def test_the_replica_helpers_read_the_answers_rcon_gives():
    class S:
        def __init__(self, answers):
            self.answers = answers

        def rcon(self, command):
            return self.answers.get(command, "")

        def passes(self, command):
            return mpcheck.passed(self.rcon(command))

    clock = lambda n: S({"time query time": f"Clock minecraft:overworld is at {n} tick(s)"})  # noqa: E731  (26.3's commands.time.query.absolute)
    assert mpcheck.daytime(clock(16500)) == 16500 and mpcheck.daytime(clock(3 * 24000 + 16500)) == 16500 and mpcheck.daytime(S({})) is None
    assert mpcheck.daytime(S({"time query daytime": "The time is 16500"})) is None  # the invented command of before: 26.3 has none
    assert mpcheck.time_is(clock(16520), 16500) and not mpcheck.time_is(clock(17000), 16500) and mpcheck.time_is(clock(24010), 23990)
    assert mpcheck.ticks_apart(23990, 10) == 20 and mpcheck.ticks_apart(100, 100) == 0 and mpcheck.ticks_apart(0, 12000) == 12000
    pos = S({"data get entity CbGuest1 Pos": "CbGuest1 has the following entity data: [3.5d, 64.0d, -2.25d]"})
    assert mpcheck.player_pos(pos, "CbGuest1") == (3.5, 64.0, -2.25) and mpcheck.near_player(pos, "CbGuest1", (5.0, 64.0, -2.25), 2.0)
    assert not mpcheck.near_player(pos, "CbGuest1", (50.0, 64.0, 0.0), 2.0) and not mpcheck.near_player(pos, "Nobody", (0, 0, 0)) and mpcheck.player_pos(S({}), "x") is None
    assert mpcheck.proxy_count(S({"execute if entity @e[type=cyberblock:proxy_human]": "Test passed. Count: 4"})) == 4  # 26.3's pass_count
    assert mpcheck.proxy_count(S({"execute if entity @e[type=cyberblock:proxy_human]": "Test passed, count: 4"})) == 4
    assert mpcheck.proxy_count(S({"execute if entity @e[type=cyberblock:proxy_human]": "Test passed"})) == 1 and mpcheck.proxy_count(S({})) == 0
    weather = S({'execute if predicate {type:"minecraft:weather_check",raining:true}': "Test passed"})
    assert mpcheck.weather_is(weather, rain=True, thunder=False) and not mpcheck.weather_is(weather, rain=False, thunder=False)
    assert [s[:3] for s in mpcheck.ENV_STATES] == [(16500, False, False), (17000, True, False), (17500, True, True), (23750, False, False)]


# ---- Phase 4: the `devices` scenario against a simulated server (megaplan 4-P1) ------------------------------------------------------
# An ideal 4M/4B server as the plan describes it: the fake host's `devices` become blocks (the guest door is 4B's `cyberblock:device_door`, as 4M places it when 4B is merged, and the NPC door a vanilla door, as without 4B; the quest door 4B's iron door), a guest's `devact`
# is checked against the owner's toggles and the device type's bit, goes to the owner's DLL (the real fake host logic answers it) and
# its `devresult`/`devstate` change the block; NPC changes show; quest doors and unsupported acts are never sent; the devices go with the
# session. `bugs` switch parts off. One simulated second of the fake host's script is SIM real seconds.
DEVICE_KIND_OF = {key: kind for key, _, _, kind, *_ in fakehost.DEVICE_SCENE}
SIM_DOOR_BLOCKS = {"door": "cyberblock:device_door", "npcdoor": "#minecraft:doors", "sealed": "cyberblock:device_iron_door"}


class Sim4Server(Sim3Server):
    def __init__(self, tmp, port, env_sim, bugs, join_password=""):
        super().__init__(tmp, port, env_sim, bugs, join_password)
        self.dev_blocks = {}  # cell -> {"id", "owner", "key", "door", "open"}: the device blocks (a door's top half is the cell above)
        self.type_off = set()  # (owner, key's kind) the host switched off with `guests devices <type> off`
        self.request = 0

    # -- the world --------------------------------------------------------------------------------------------------------------
    def place_devices(self, owner, msg):
        for entry in msg["list"]:
            key = next(k for k, i in fakehost.device_ids().items() if i == entry["id"])
            cell = (math.floor(entry["p"][0]), math.floor(entry["p"][1]), math.floor(entry["p"][2]))
            if cell in self.dev_blocks:
                continue
            if cell in self.replica and "door-replica" in self.bugs:  # the bug: a replica block is in the way of the door
                continue
            door = DEVICE_KIND_OF[key] == "door"  # the sealed quest door is a (greyed) door too
            block = SIM_DOOR_BLOCKS.get(key, "cyberblock:device") if door else "cyberblock:device"
            self.dev_blocks[cell] = {"id": entry["id"], "owner": owner, "key": key, "door": door, "block": block, "open": entry["st"] == 1}
            for cleared in (cell, (cell[0], cell[1] + 1, cell[2])) if door else (cell,):  # the device replaces replica cells (a door's top half too)
                self.replica.discard(cleared)
                self.world.pop(cleared, None)

    def apply_devstate(self, msg):
        for block in self.dev_blocks.values():
            if block["id"] == msg["id"] and block["door"]:
                block["open"] = msg["st"] == 1

    def advance(self):
        super().advance()
        sim, now = self.sim, time.monotonic()
        for name, host in sim.hosts.items():
            cp = host.get("cp")
            if not cp:
                continue
            if host["linked"]:
                for m in cp.device_messages((now - host["t0"]) / SIM):
                    if m["t"] == "devstate" and "no-npc-sync" not in self.bugs:
                        self.apply_devstate(m)
            elif "devices-stay" not in self.bugs:  # the session ended: its devices go
                self.dev_blocks = {c: b for c, b in self.dev_blocks.items() if b["owner"] != name}

    # -- commands ---------------------------------------------------------------------------------------------------------------
    def rcon(self, command):
        self.advance()
        if match := re.fullmatch(r"execute if block (-?\d+) (-?\d+) (-?\d+) (#minecraft:doors|cyberblock:device_door|cyberblock:device_iron_door)(\[open=true\])?", command):
            block = self.dev_blocks.get(tuple(int(v) for v in match.groups()[:3]))
            return "Test passed" if block and block["door"] and block["block"] == match.group(4) and (not match.group(5) or block["open"]) else "Test failed"
        if match := re.fullmatch(r"execute unless block (-?\d+) (-?\d+) (-?\d+) minecraft:air", command):
            cell = tuple(int(v) for v in match.groups())
            return "Test passed" if cell in self.dev_blocks or cell in self.world else "Test failed"
        if match := re.fullmatch(r"execute if block (-?\d+) (-?\d+) (-?\d+) (\S+)", command):  # a replica block (a device cell is no replica block)
            cell = tuple(int(v) for v in match.groups()[:3])
            if cell in self.dev_blocks:
                return "Test failed"
        actor, inner = None, command
        if match := re.fullmatch(r"execute as (\S+) run (.*)", command):
            actor, inner = match.groups()
        if match := re.fullmatch(r"cyberblock guests devices (\w+) (on|off)", inner):  # one device type's bit
            (self.type_off.add if match.group(2) == "off" else self.type_off.discard)((actor, match.group(1)))
            return ""
        if match := re.fullmatch(r"cyberblock test device (-?\d+) (-?\d+) (-?\d+) (\w+)", inner):
            return self.devact(actor, tuple(int(v) for v in match.groups()[:3]), match.group(4))
        return super().rcon(command)

    def devact(self, actor, cell, act):
        block = self.dev_blocks.get(cell) or self.dev_blocks.get((cell[0], cell[1] - 1, cell[2]))
        if not block:
            return ""
        owner, key = block["owner"], block["key"]
        guest = actor is not None and actor != owner and actor not in self.sim.hosts
        if guest and not self.allows(owner, actor, "devices") and "ignore-toggles" not in self.bugs:
            return ""  # the whole layer is off
        if guest and (owner, DEVICE_KIND_OF[key]) in self.type_off and "ignore-type-bit" not in self.bugs:
            return ""
        if act in fakehost.UNSUPPORTED_ACTS:
            return ""  # greyed: never sent (a server that sends them gets `unsupported` from the DLL: nothing changes either way)
        host = self.sim.hosts[owner]
        if key == "sealed" and "guest-opens-sealed" in self.bugs:
            block["open"] = True  # the bug: the server opens the quest door itself
        elif key == "sealed":
            return ""  # greyed: quest-locked, never sent
        self.request += 1
        msg = {"t": "devact", "id": self.request, "dev": block["id"], "act": act}
        append(host["log"], f"[{owner}] devact: {json.dumps(msg)}")
        for answer in host["cp"].reply(msg):
            if answer["t"] == "devresult":
                append(host["log"], f"[{owner}] devresult: {json.dumps(answer)}")
            elif answer["t"] == "devstate":
                self.apply_devstate(answer)  # the block changes only after Night City answered
        return ""


class Sim4Env(Sim3Env):
    def server(self, scenario, max_hosts=1, join_password=False):
        self.max_hosts = max_hosts
        self.server_obj = Sim4Server(self.tmp, self.ports.server(scenario), self, self.bugs)
        self.server_obj.log.path.write_text("Done (1s)!\n", encoding="utf-8")
        self.logs["server"] = self.server_obj.log
        return self.server_obj

    def grant(self, name, host, layers, modes, ids_from):
        super().grant(name, host, layers, modes, ids_from)
        if "devices" not in modes:
            return
        srv, extra = self.server_obj, self.extra.get(name, {})
        cp = fakehost.Cyberpunk(frozenset(layers), frozenset(modes), ids_from, name, extra.get("shift", 0), extra.get("env_hours", 0))
        for m in cp.reply(SESSION_HOST):
            if m["t"] == "ground":
                srv.place(m)
            elif m["t"] == "devices":
                srv.place_devices(name, m)
        host.update(cp=cp, t0=time.monotonic())
        srv.pos[name] = [fakehost.REPLICA_FEET[0], fakehost.REPLICA_FEET[1], fakehost.REPLICA_FEET[2]]


def run4(tmp, bugs=()):
    return run_scenario(Sim4Env(tmp, bugs), "devices")


def test_devices_scenario_passes_against_an_ideal_server(tmp_path):
    sim = Sim4Env(tmp_path)
    rep = run_scenario(sim, "devices")
    assert rep.ok, failed(rep)
    labels = [label for label, _, _ in rep.checks]
    assert len(labels) == 22 and sim.max_hosts == 1 and any("guests devices door off" in x for x in labels)
    host_log = sim.hosts["CbHost1"]["log"].text()
    acts = [json.loads(line.split("devact: ", 1)[1])["act"] for line in host_log.splitlines() if "devact: " in line]
    assert acts == ["open", "close", "off", "on", "open"]  # the quest door, the lift call, the door-off request and the layer-off request never got there


@pytest.mark.parametrize("bug, expected", [
    ("door-replica", {"every device stands as a block at its position (the two doors are doors, the lift, light, camera, vending machine and quest door are not air)"}),
    ("no-npc-sync", {"a Night City person opens the NPC door (`devstate by:npc`) and it opens in Minecraft"}),
    ("guest-opens-sealed", {"a quest-locked (sealed) door never opens for a guest: Night City is never asked to, or refuses"}),
    ("ignore-type-bit", {"with `/cyberblock guests devices door off` a guest's door request never reaches the DLL and the door stays shut"}),
    ("ignore-toggles", {"with the whole `guests devices` toggle off no device request reaches the DLL"}),
    ("devices-stay", {"the devices go with the session (no door stands any more)"}),
])
def test_devices_scenario_notices_each_device_bug(tmp_path, bug, expected):
    rep = run4(tmp_path, [bug])
    assert expected <= set(failed(rep)) and not rep.ok


def test_devices_scenario_runs_the_guest_actions_as_the_guest_and_the_toggles_as_the_host(tmp_path, monkeypatch):
    sent = []
    real = Sim4Server.rcon
    monkeypatch.setattr(Sim4Server, "rcon", lambda self, c: sent.append(c) or real(self, c))
    assert run4(tmp_path).ok
    x, y, z = fakehost.device_cell("door")
    assert f"execute as CbGuest1 run cyberblock test device {x} {y} {z} open" in sent and not [c for c in sent if "test device" in c and not c.startswith("execute as CbGuest1")]
    assert [c for c in sent if "guests devices" in c] == [f"execute as CbHost1 run cyberblock guests devices {w}" for w in ("door off", "door on", "off", "on")]


def test_the_devices_scenario_asks_a_fake_host_for_the_devices_mode_with_no_layer(tmp_path):
    seen = []

    class Spy(Sim4Env):
        def fakehost(self, client, **kw):
            seen.append((client.name, kw.get("layers"), kw.get("modes"), kw.get("wait_host")))
            return super().fakehost(client, **kw)

    run_scenario(Spy(tmp_path), "devices")
    assert seen == [("CbHost1", (), ("devices",), mpcheck.T_JOIN)]


def test_the_devices_scenario_uses_the_borrowed_phase_7_block_when_asked(capsys):
    argv = ["devices", "--dry-run", "--client-port-base", "25810", "--server-port-base", "25970", "--frame-prefix", "Local\\CyberblockFrame4PM"]
    assert mpcheck.main(argv) == 0
    out = capsys.readouterr().out
    assert "-Pcyberblock.serverPort=25973" in out and "-Pcyberblock.port=25810" in out and "Local\\CyberblockFrame4PMH" in out and "--devices" in out and "--layer" not in out
    assert "--replica" not in out and "25600" not in out and "Local\\CyberblockFrame " not in out


def test_the_devices_argv_parses_with_the_real_fakehost(monkeypatch):
    seen = []

    async def fake_run(*args, **kwargs):
        seen.append((args, kwargs))
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    argv = mpcheck.fakehost_argv(port=25810, name="CbHost1", ver="1.0.0", frame="Local\\F", out_dir=Path("o"), layers=(), modes=("devices",), wait_host=150.0)
    monkeypatch.setattr("sys.argv", ["fakehost", *argv[4:]])
    assert fakehost.main() == 0
    args, kwargs = seen[0]
    assert args[9] == frozenset({"devices"}) and args[8] == frozenset() and kwargs == {"wait_host": 150.0}


def test_the_device_log_readers_read_what_the_fake_host_prints_even_with_windows_line_ends(tmp_path):
    log = LogFile(tmp_path / "h.log")
    door = fakehost.device_ids()["door"]
    log.path.write_text(f'[CbHost1] devact: {{"t": "devact", "id": 3, "dev": "{door}", "act": "open"}}\r\n[CbHost2] devact: {{"t": "devact", "id": 4}}\r\n'
                        '[CbHost1] devresult: {"t": "devresult", "id": 3, "ok": false, "reason": "unsupported"}\r\n[CbHost1] devact: not json\r\n', encoding="utf-8")
    host = mpcheck.FakeHost("CbHost1", SimProc(), log, None)
    assert [m["id"] for m in mpcheck.logged_events(log, mpcheck.DEVICE_ACT)] == [3] and mpcheck.devact_seen(host, "door", "open", 0)
    assert not mpcheck.devact_seen(host, "door", "close", 0) and not mpcheck.devact_seen(host, "light", "open", 0) and not mpcheck.devact_seen(host, "door", "open", log.mark())
    assert not mpcheck.result_ok_since(host, 0) and mpcheck.logged_events(log, mpcheck.DEVICE_RESULT)[0]["reason"] == "unsupported"
    assert mpcheck.logged_events(LogFile(tmp_path / "missing.log"), mpcheck.DEVICE_ACT) == []


def test_the_device_helpers_read_the_answers_rcon_gives():
    class S:
        def __init__(self, answers):
            self.answers = answers

        def passes(self, command):
            return mpcheck.passed(self.answers.get(command, "Test failed"))

    x, y, z = fakehost.device_cell("door")
    for block in ("#minecraft:doors", "cyberblock:device_door", "cyberblock:device_iron_door"):  # vanilla doors or 4B's door blocks
        stands = S({f"execute if block {x} {y} {z} {block}": "Test passed"})
        assert mpcheck.device_stands(stands, "door") and not mpcheck.device_stands(stands, "npcdoor") and not mpcheck.door_is_open(stands, "door")
        assert mpcheck.door_is_open(S({f"execute if block {x} {y} {z} {block}[open=true]": "Test passed"}), "door")
    assert not mpcheck.device_stands(S({f"execute if block {x} {y} {z} minecraft:stone": "Test passed"}), "door")  # any other block is no door
    lx, ly, lz = fakehost.device_cell("light")
    assert mpcheck.device_stands(S({f"execute unless block {lx} {ly} {lz} minecraft:air": "Test passed"}), "light")  # a light is any block
    sx, sy, sz = fakehost.device_cell("sealed")
    assert mpcheck.device_stands(S({f"execute unless block {sx} {sy} {sz} minecraft:air": "Test passed"}), "sealed")  # the sealed door shows greyed: any block
    assert mpcheck.DOOR_KEYS == ("door", "npcdoor") and set(mpcheck.DEVICE_IDS) == {k for k, *_ in fakehost.DEVICE_SCENE}


# ---- Phase 5: the figures scenario (megaplan 5-P1) against a simulated server --------------------------------------------------
# The server as the plan has it for the proxies: one human or vehicle proxy per entry of the host's tagged `npcs` list, the dead victim's body for
# 3 s and then gone, all of a host's figures gone with its session. SIM real seconds are one second of the fake host's script.
class Sim5Server(Sim3Server):
    def figure_hosts(self):
        keep = "figures-stay" in self.bugs
        return [h for h in self.sim.hosts.values() if h.get("figures") and (h["linked"] or keep)]

    def people(self, host):
        elapsed = (time.monotonic() - host["t0"]) / SIM
        body_gone = fakehost.FIGURE_DEAD_AT + fakehost.FIGURE_DEAD_FOR + 3.0  # the body lies down for 3 s (ProxyDiff.deadTicks)
        if "tags-dropped" in self.bugs:
            return 0
        if "figure-lost" in self.bugs:
            return mpcheck.FIGURE_PEOPLE - 1
        return mpcheck.FIGURE_PEOPLE + (1 if elapsed < body_gone or "victim-stays" in self.bugs else 0)

    def rcon(self, command):
        hosts = self.figure_hosts()
        if hosts and command in ("execute if entity @e[type=cyberblock:proxy_human]", "execute if entity @e[type=cyberblock:proxy_vehicle]"):
            n = sum(self.people(h) if command.endswith("human]") else (0 if "tags-dropped" in self.bugs else mpcheck.FIGURE_CARS) for h in hosts)
            return f"Test passed. Count: {n}" if n else "Test failed"
        return super().rcon(command)


class Sim5Env(Sim3Env):
    def server(self, scenario, max_hosts=1, join_password=False):
        self.max_hosts = max_hosts
        self.server_obj = Sim5Server(self.tmp, self.ports.server(scenario), self, self.bugs)
        self.server_obj.log.path.write_text("Done (1s)!\n", encoding="utf-8")
        self.logs["server"] = self.server_obj.log
        return self.server_obj

    def grant(self, name, host, layers, modes, ids_from):
        super().grant(name, host, layers, modes, ids_from)
        if "figures" in modes:
            host.update(figures=True, t0=time.monotonic(), ids=host["ids"] | set(fakehost.figure_ids(ids_from)))


def run5(tmp, name, bugs=()):
    return run_scenario(Sim5Env(tmp, bugs), name)


def test_figures_scenario_passes_against_an_ideal_server(tmp_path):
    sim = Sim5Env(tmp_path)
    rep = run_scenario(sim, "figures")
    assert rep.ok, failed(rep)
    assert len(rep.checks) == 9 and sim.max_hosts == 1
    assert mpcheck.FIGURE_PEOPLE == 39 and mpcheck.FIGURE_CARS == 6 and mpcheck.FIGURE_PEOPLE + mpcheck.FIGURE_CARS + 1 == fakehost.FIGURE_COUNT  # + the victim
    assert mpcheck.FIGURE_HIT in sim.hosts["CbHost1"]["ids"] and f'"target": "{mpcheck.FIGURE_HIT}"' in sim.hosts["CbHost1"]["log"].text()


@pytest.mark.parametrize("bug, expected", [
    ("tags-dropped", {"every tagged person and car stands as a proxy (39 people once the victim's body has gone, 6 cars)"}),
    ("figure-lost", {"every tagged person and car stands as a proxy (39 people once the victim's body has gone, 6 cars)"}),
    ("victim-stays", {"every tagged person and car stands as a proxy (39 people once the victim's body has gone, 6 cars)"}),
    ("drop-hits", {"a guest's hit on a tagged figure reaches the fake host by its id"}),
    ("figures-stay", {"the figures go with the session"}),
    ("no-session-end", {"the session ends when the link drops"}),
])
def test_figures_scenario_notices_each_figure_bug(tmp_path, bug, expected):
    rep = run5(tmp_path, "figures", [bug])
    assert expected <= set(failed(rep)) and not rep.ok


def test_figures_scenario_asks_a_fake_host_for_the_figures_mode_with_no_layer_and_uses_its_own_server_slot(tmp_path, capsys):
    seen = []

    class Spy(Sim5Env):
        def fakehost(self, client, **kw):
            seen.append((client.name, kw.get("layers"), kw.get("modes"), kw.get("wait_host")))
            return super().fakehost(client, **kw)

    run_scenario(Spy(tmp_path), "figures")
    assert seen == [("CbHost1", (), ("figures",), mpcheck.T_JOIN)]  # the guest is a plain client: no fake host
    assert mpcheck.Ports().server("figures") == mpcheck.SERVER_BASE + 3 and "figures" in mpcheck.SCENARIOS
    assert mpcheck.main(["figures", "--dry-run", "--client-port-base", "25824", "--server-port-base", "25985", "--frame-prefix", "Local\\CyberblockFrame5PM"]) == 0
    out = capsys.readouterr().out
    assert "-Pcyberblock.serverPort=25988" in out and "--figures" in out and "--layer" not in out and "Local\\CyberblockFrame5PMH" in out
    assert "-Pcyberblock.port=25824" in out and "25600" not in out


def test_figures_hit_is_the_first_pose_a_hostile_figure_of_the_fake_hosts_list():
    first = next(e for e in fakehost.figures_npcs(0.0)["list"] if e["id"] == mpcheck.FIGURE_HIT)
    assert first["hostile"] and first["anim"] & fakehost.FIGURE_ANIM_BITS["aim"] and first["kind"] == "human"


def test_a_version_refusal_counts_from_the_kick_reason_or_from_the_handshake_line_when_the_client_closed_first(tmp_path):
    message = r"Cyberblock version mismatch: server 1\.0\.0, your mod 1\.1\.0\. Update from the modpack\."
    kicked, closed, early = (LogFile(tmp_path / f"{n}.log") for n in ("kicked", "closed", "early"))
    kicked.path.write_text("CbGuest1 lost connection: Cyberblock version mismatch: server 1.0.0, your mod 1.1.0. Update from the modpack.\n", encoding="utf-8")
    shake = "handshake: CbGuest1 mod 1.1.0 dll none -> guest (Cyberblock version mismatch: server 1.0.0, your mod 1.1.0. Update from the modpack.)\n"
    closed.path.write_text(shake + "CbGuest1 lost connection: Disconnected\n", encoding="utf-8")
    early.path.write_text("CbGuest1 lost connection: Disconnected\n" + shake, encoding="utf-8")  # the connection loss must come after the refusal
    assert mpcheck.refused(kicked, "CbGuest1", "1.1.0", message) and mpcheck.refused(closed, "CbGuest1", "1.1.0", message)
    assert not mpcheck.refused(early, "CbGuest1", "1.1.0", message) and not mpcheck.refused(closed, "CbGuest2", "1.1.0", message)


# ---- Phase 7: the hub scenario (megaplan 7-P1) against a simulated server ----------------------------------------------------------
# The server as shared/protocol.md "Phase 7" has it for the Afterlife hub: the join password on (a locked player's `/cyberblock hub` does nothing),
# `/cyberblock hub` enters (and, run again, leaves), a linked player's DLL is told `hub in:true` then `park on:true` (and `park on:false` then `hub in:false`),
# a guest has no DLL so nobody is told, Steve is in `cyberblock:afterlife` while inside, a new session of a player in the hub hears the entering pair again,
# and a `parked ok:false` takes the player out.
SIM_HOME = "minecraft:overworld"
SIM_ENTER = [("hub", {"in": True}), ("park", {"on": True})]
SIM_LEAVE = [("park", {"on": False}), ("hub", {"in": False})]


class Sim7Server(SimServer):
    def __init__(self, tmp, port, env_sim, bugs, join_password=""):
        super().__init__(tmp, port, env_sim, bugs, join_password)
        self.dim, self.in_hub, self.path_logged = {}, set(), False

    def tell(self, name, messages):
        """The DLL of host `name` (when its link is up) is sent `messages` ((t, fields) pairs), in order; a `park on:true` is answered with `parked`."""
        host = self.sim.hosts.get(name)
        if not (host and host["linked"]):
            return
        for t, fields in messages:
            append(host["log"], f'[{name}] {t}: {json.dumps({"t": t, **fields})}')
        if ("park", {"on": True}) in messages:
            self.parked(name)

    def parked(self, name):
        """The DLL's `parked` answer to a `park on:true` reaches the hub (unless the relay drops it)."""
        if "relay-drops-parked" in self.bugs:
            append(self.log, f"[Server thread/WARN]: relay: {name} dropped parked x 1 (no rate row)")
            return
        if self.sim.off.get(name):  # Cyberblock Off: no cover drawn, the player stays
            append(self.log, f"[Server thread/INFO]: hub: {name} V not parked yet (blocked (Cyberblock is not active)): no cover is shown, kept in the Afterlife "
                             "(V is parked at On)")
            if "leaves-when-off" in self.bugs:
                self.leave(name)
        elif self.sim.refuse.get(name):
            append(self.log, f"[Server thread/INFO]: hub: {name} V not parked (blocked (in a vehicle))")
            if "keeps-refused-park" not in self.bugs:
                self.leave(name)
        elif not self.path_logged:
            self.path_logged = True
            append(self.log, "[Server thread/INFO]: hub: park path: confirmed by the parked answer (V parked by the Phase 0 g method or its fast-travel-point fallback)")

    def enter(self, name):
        self.in_hub.add(name)
        self.dim[name] = mpcheck.HUB_DIMENSION
        append(self.log, f"[Server thread/INFO]: hub: {name} entered (from {SIM_HOME} 0.5 64.0 0.5)")
        order = list(SIM_ENTER)
        if "park-before-hub" in self.bugs:
            order.reverse()
        if "no-park" in self.bugs:
            order = order[:1]
        if "double-hub" in self.bugs:
            order.append(("hub", {"in": True}))
        self.tell(name, order)
        if "guest-gets-hub" in self.bugs and name not in self.sim.hosts:  # the bug: a guest's trip is told to every host's DLL
            for host in self.sim.hosts:
                self.tell(host, order)

    def leave(self, name):
        self.in_hub.discard(name)
        if "stay-in-hub" not in self.bugs:
            self.dim[name] = SIM_HOME
        order = list(SIM_LEAVE)
        if "hub-first-on-leave" in self.bugs:
            order.reverse()
        self.tell(name, order)

    def resync(self, name):
        """A new host session: a player still in the hub hears the entering pair again."""
        if name in self.in_hub and "no-resync" not in self.bugs:
            self.tell(name, list(SIM_ENTER))

    def rcon(self, command):
        if match := re.fullmatch(r"data get entity (\S+) Dimension", command):
            dim = self.dim.get(match.group(1))
            return f'{match.group(1)} has the following entity data: "{dim}"' if dim else "No entity was found"
        actor, inner = None, command
        if match := re.fullmatch(r"execute as (\S+) run (.*)", command):
            actor, inner = match.groups()
        if actor in self.dim and inner in ("cyberblock hub", "cyberblock hub leave"):
            if actor in self.locked and "hub-ignores-lock" not in self.bugs:
                return "Type /login <password> first"
            inside = actor in self.in_hub
            if not inside and inner == "cyberblock hub":
                self.enter(actor)
            elif inside and (inner == "cyberblock hub leave" or "leave-command-only" not in self.bugs):
                self.leave(actor)
            return ""
        return super().rcon(command)


class Sim7Env(SimEnv):
    def __init__(self, tmp, bugs=()):
        super().__init__(tmp, bugs)
        self.refuse, self.off, self.started = {}, {}, []

    def server(self, scenario, max_hosts=1, join_password=False):
        self.max_hosts, self.join_password = max_hosts, join_password
        self.server_obj = Sim7Server(self.tmp, self.ports.server(scenario), self, self.bugs, "sim-join-pw" if join_password else "")
        self.server_obj.log.path.write_text("Done (1s)!\n", encoding="utf-8")
        self.logs["server"] = self.server_obj.log
        return self.server_obj

    def client(self, name, **kw):
        c = super().client(name, **kw)
        self.server_obj.dim.setdefault(name, SIM_HOME)
        return c

    def fakehost(self, client, *, park_refuse=False, park_off=False, **kw):
        self.refuse[client.name], self.off[client.name] = park_refuse, park_off
        self.started.append((client.name, kw.get("layers"), kw.get("modes"), "refuse" if park_refuse else "off" if park_off else None))
        return super().fakehost(client, **kw)

    def grant(self, name, host, layers, modes, ids_from):
        super().grant(name, host, layers, modes, ids_from)
        self.server_obj.resync(name)


def run7(tmp, bugs=()):
    return run_scenario(Sim7Env(tmp, bugs), "hub")


def test_hub_scenario_passes_against_an_ideal_server(tmp_path):
    sim = Sim7Env(tmp_path)
    rep = run_scenario(sim, "hub")
    assert rep.ok, failed(rep)
    assert len(rep.checks) == 29 and sim.max_hosts == 1 and sim.join_password  # the live server's join password is on
    host = mpcheck.FakeHost("CbHost1", SimProc(), sim.hosts["CbHost1"]["log"], None)
    assert mpcheck.hub_events(host) == mpcheck.HUB_ENTER + mpcheck.HUB_LEAVE  # the last link: the entering pair again, refused, taken out
    assert sim.started == [("CbHost1", (), ("vendor",), None), ("CbHost1", (), ("vendor",), None), ("CbHost1", (), ("vendor",), "off"),
                           ("CbHost1", (), ("vendor",), "refuse")]


def test_hub_scenario_accepts_a_server_that_leaves_with_its_own_command(tmp_path):
    rep = run7(tmp_path, ["leave-command-only"])
    assert rep.ok, failed(rep)  # `/cyberblock hub leave` is tried when `/cyberblock hub` again does not leave


@pytest.mark.parametrize("bug, expected", [
    ("hub-ignores-lock", "a locked host's `/cyberblock hub` leaves it where it is"),
    ("park-before-hub", "`/cyberblock hub` as the host sends its DLL `hub in:true` and then `park on:true`"),
    ("no-park", "`/cyberblock hub` as the host sends its DLL `hub in:true` and then `park on:true`"),
    ("double-hub", "`/cyberblock hub` as the host sends its DLL `hub in:true` and then `park on:true`"),
    ("relay-drops-parked", "the DLL's `parked` reached the server's hub"),
    ("guest-gets-hub", "a guest has no DLL: its trip sends nothing about the hub to the host's DLL"),
    ("hub-first-on-leave", "leaving sends `park on:false` and then `hub in:false`"),
    ("stay-in-hub", "the host's Steve is back in the dimension he came from"),
    ("no-session-end", "the session ends when the link drops"),
    ("no-resync", "the new session of a player still in the hub hears `hub in:true` and `park on:true` again"),
    ("keeps-refused-park", "`parked ok:false` takes the host out of the hub at once"),
    ("leaves-when-off", "`parked ok:false` (Cyberblock is not active) keeps the host in the hub"),
])
def test_hub_scenario_notices_each_hub_bug(tmp_path, bug, expected):
    rep = run7(tmp_path, [bug])
    assert any(label.startswith(expected) for label in failed(rep)) and not rep.ok


def test_the_relay_dropping_parked_also_fails_the_relay_step(tmp_path):
    rep = run7(tmp_path, ["relay-drops-parked"])
    assert "the server logged no relay drops for the host" in failed(rep)


def test_hub_scenario_asks_a_fake_host_for_the_vendor_mode_with_no_layer_and_uses_its_own_server_slot(tmp_path, capsys):
    sim = Sim7Env(tmp_path)
    run_scenario(sim, "hub")
    assert {(name, layers, modes) for name, layers, modes, _ in sim.started} == {("CbHost1", (), ("vendor",))}  # the guest is a plain client: no fake host
    assert mpcheck.Ports().server("hub") == mpcheck.SERVER_BASE + 2 and mpcheck.SCENARIOS[-1] == "hub" and mpcheck.SCENARIO_MODES["hub"] == ("vendor",)
    assert mpcheck.main(["hub", "--dry-run", "--client-port-base", "25814", "--server-port-base", "25975", "--frame-prefix", "Local\\CyberblockFrame7PM"]) == 0
    out = capsys.readouterr().out
    assert "-Pcyberblock.serverPort=25977" in out and "--vendor" in out and "--layer" not in out and "Local\\CyberblockFrame7PMH" in out
    assert "-Pcyberblock.port=25814" in out and "25600" not in out


def test_the_hub_argv_parses_with_the_real_fakehost(monkeypatch):
    seen = []

    async def fake_run(*args, **kwargs):
        seen.append((args, kwargs))
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    for refuse, off in ((False, False), (True, False), (False, True)):
        argv = mpcheck.fakehost_argv(port=25814, name="CbHost1", ver="1.0.0", frame="Local\\CyberblockFrame7PMH", out_dir=Path("frames"), layers=(), modes=("vendor",),
                                     wait_host=60.0, park_refuse=refuse, park_off=off)
        assert ("--park-refuse" in argv) is refuse and ("--park-off" in argv) is off
        monkeypatch.setattr("sys.argv", ["fakehost", *argv[4:]])  # after `python -u -m tools.fakehost`
        assert fakehost.main() == 0
    (args, kwargs), (_, refused), (_, off_kw) = seen
    assert args[8] == frozenset() and args[9] == frozenset({"vendor"}) and kwargs == {"wait_host": 60.0} and refused == {"wait_host": 60.0, "park_refuse": True}
    assert off_kw == {"wait_host": 60.0, "park_off": True}


def test_the_hub_helpers_read_what_the_fake_host_prints_and_what_rcon_answers(tmp_path):
    log = LogFile(tmp_path / "fh.log")
    log.path.write_bytes(b'[CbHost1] session: {"t": "session", "state": "host"}\r\n[CbHost1] hub: {"t": "hub", "in": true}\r\n'
                         b'[CbHost1] vteleport: {"t": "vteleport", "id": 1}\r\n[CbHost1] park: {"t": "park", "on": true}\r\n[CbHost1] hub: not json\r\n'
                         b'[CbHost2] hub: {"t": "hub", "in": false}\r\n[CbHost1] park: {"t": "park", "on": false}\r\n[CbHost1] hub: {"t": "hub", "in": false}\r\n')
    host = mpcheck.FakeHost("CbHost1", SimProc(), log, None)
    assert mpcheck.hub_events(host) == [("hub", True), ("park", True), ("park", False), ("hub", False)]  # only CbHost1's hub and park lines, in order
    assert mpcheck.hub_events(host, log.text().index("[CbHost1] park: {\"t\": \"park\", \"on\": true}")) == [("park", True), ("park", False), ("hub", False)]

    class S:
        def __init__(self, answer):
            self.answer = answer

        def rcon(self, command):
            assert command == "data get entity CbHost1 Dimension"
            return self.answer

    assert mpcheck.dimension_of(S('CbHost1 has the following entity data: "cyberblock:afterlife"'), "CbHost1") == "cyberblock:afterlife"
    assert mpcheck.dimension_of(S('CbHost1 has the following entity data: "minecraft:overworld"\r\n'), "CbHost1") == "minecraft:overworld"
    assert mpcheck.dimension_of(S("No entity was found"), "CbHost1") is None and mpcheck.HUB_DIMENSION == "cyberblock:afterlife"
    assert mpcheck.HUB_ENTER == [("hub", True), ("park", True)] and mpcheck.HUB_LEAVE == [("park", False), ("hub", False)]  # as 7H's HubRules.sync


def test_the_hub_scenario_names_its_limits():
    doc = mpcheck.__doc__
    assert "operator permission" in " ".join(doc.split()) and "Re-test P7-5" in doc
    assert re.search(mpcheck.PARK_CONFIRMED, "hub: park path: confirmed by the parked answer (V parked)")
    assert re.search(mpcheck.PARK_REFUSED, "hub: CbHost1 V not parked (blocked (in a vehicle))")
