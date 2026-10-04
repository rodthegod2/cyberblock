"""tools/mpcheck.py `breach` (megaplan task 6-P1) without Minecraft: the scenario logic against a simulated server that does what the plan says for the
Phase 6 server (a guest's Breach on the Net Port: the owner's `hacks` toggle, the `daemon` of a solved Breach or the alarm of a failed one, the Net Port gone with
the session). The real run (`tools.mpcheck breach`) needs packages 6B, 6R and 6I merged: it is the integrator's."""
import json
import re
import time

import pytest

from test_mpcheck import (SESSION_HOST, Sim4Env, Sim4Server, SimProc, append, failed, quick, run_scenario)  # noqa: F401  (quick is an autouse fixture)
from tools import fakehost, mpcheck
from tools.mpcheck import LogFile

KIND6 = {key: kind for key, _, _, kind, *_ in fakehost.HACKS2_SCENE}
SOLVED = ["open_door", "disable_cameras"]
SIM_COOLDOWN_S = 0.3  # the simulated server's failure cooldown (10 s on the real one), shrunk like every other wait


@pytest.fixture(autouse=True)
def breach_cooldown(quick, monkeypatch):
    """The scenario waits a little longer than the simulated cooldown (`quick` set it to 0)."""
    monkeypatch.setattr(mpcheck, "BREACH_COOLDOWN_S", SIM_COOLDOWN_S + 0.05)


class Sim6Server(Sim4Server):
    """Phase 6 as the plan describes it, on top of the device layer: the Net Port is a device block; `cyberblock test breach <x> <y> <z> solve|fail` run as a
    guest sends the owner's DLL the `daemon` of a solved Breach (the owner's `hacks` toggle allowing) or the alarm of a failed one; the fake host's own logic
    answers it. `bugs` switch parts off."""

    def __init__(self, tmp, port, env_sim, bugs, join_password=""):
        super().__init__(tmp, port, env_sim, bugs, join_password)
        self.breaches = []
        self.failed_at = {}  # (player, cell) -> monotonic time of that player's last failed Breach on that device

    def place_devices(self, owner, msg):
        ids = {i: k for k, i in fakehost.device_ids(fakehost.HACKS2_SCENE).items()}
        for entry in msg["list"]:
            key = ids[entry["id"]]
            cell = tuple(int(c // 1) for c in entry["p"])
            if cell in self.dev_blocks:
                continue
            door = KIND6[key] == "door"
            self.dev_blocks[cell] = {"id": entry["id"], "owner": owner, "key": key, "door": door, "block": "cyberblock:device", "open": entry["st"] == 1}
            for cleared in (cell, (cell[0], cell[1] + 1, cell[2])) if door else (cell,):
                self.replica.discard(cleared)
                self.world.pop(cleared, None)

    def rcon(self, command):
        actor, inner = None, command
        if match := re.fullmatch(r"execute as (\S+) run (.*)", command):
            actor, inner = match.groups()
        if match := re.fullmatch(r"cyberblock test breach (-?\d+) (-?\d+) (-?\d+) (solve|fail)", inner):
            self.advance()
            return self.breach(actor, tuple(int(v) for v in match.groups()[:3]), match.group(4))
        return super().rcon(command)

    def breach(self, actor, cell, how):
        block = self.dev_blocks.get(cell)
        if not block or block["key"] != "netport":
            return ""
        owner = block["owner"]
        if time.monotonic() - self.failed_at.get((actor, cell), -SIM_COOLDOWN_S) < SIM_COOLDOWN_S:
            return ""  # the failure cooldown: `cyberblock.breach.cooldown` on the action bar, nothing reaches the DLL
        if not self.allows(owner, actor, "hacks") and "ignore-hacks-toggle" not in self.bugs:
            return ""
        if how == "fail":
            self.failed_at[(actor, cell)] = time.monotonic()
            if "no-alarm" in self.bugs:
                return ""
            listing, strength = (["open_door"] if "solved-on-fail" in self.bugs else ["alarm"]), 1
        else:
            listing, strength = (["alarm"] if "alarm-on-solve" in self.bugs else list(SOLVED)), (4 if "bad-strength" in self.bugs else 2)
        self.request += 1
        msg = {"t": "daemon", "id": self.request, "dev": block["id"], "list": listing, "strength": strength}
        host = self.sim.hosts[owner]
        append(host["log"], f"[{owner}] daemon: {json.dumps(msg)}")
        self.breaches.append((actor, how, msg))
        for answer in host["cp"].reply(msg):
            if answer["t"] == "hackresult":
                append(host["log"], f"[{owner}] hackresult: {json.dumps(answer)}")
            elif answer["t"] == "devstate":
                self.apply_devstate(answer)
        return ""


class Sim6Env(Sim4Env):
    def server(self, scenario, max_hosts=1, join_password=False):
        self.max_hosts = max_hosts
        self.server_obj = Sim6Server(self.tmp, self.ports.server(scenario), self, self.bugs)
        self.server_obj.log.path.write_text("Done (1s)!\n", encoding="utf-8")
        self.logs["server"] = self.server_obj.log
        return self.server_obj

    def grant(self, name, host, layers, modes, ids_from):
        super().grant(name, host, layers, modes, ids_from)
        if "hacks2" not in modes:
            return
        import time
        srv, extra = self.server_obj, self.extra.get(name, {})
        cp = fakehost.Cyberpunk(frozenset(layers), frozenset(modes), ids_from, name, extra.get("shift", 0), extra.get("env_hours", 0))
        for m in cp.reply(SESSION_HOST):
            if m["t"] == "ground":
                srv.place(m)
            elif m["t"] == "devices":
                srv.place_devices(name, m)
        host.update(cp=cp, t0=time.monotonic())
        srv.pos[name] = [fakehost.REPLICA_FEET[0], fakehost.REPLICA_FEET[1], fakehost.REPLICA_FEET[2]]


def run6(tmp, bugs=()):
    return run_scenario(Sim6Env(tmp, bugs), "breach")


def test_breach_scenario_passes_against_an_ideal_server(tmp_path):
    sim = Sim6Env(tmp_path)
    rep = run_scenario(sim, "breach")
    assert rep.ok, failed(rep)
    labels = [label for label, _, _ in rep.checks]
    assert len(labels) == 16 and sim.max_hosts == 1 and any("guests hacks off" in x for x in labels) and any("`[\"alarm\"]`" in x for x in labels)
    host_log = sim.hosts["CbHost1"]["log"].text()
    daemons = [json.loads(line.split("daemon: ", 1)[1]) for line in host_log.splitlines() if "] daemon: " in line]
    assert [m["list"] for m in daemons] == [SOLVED, ["alarm"], SOLVED]  # the solved one, the failure, and (toggle off: nothing) the solved one again
    assert all(m["dev"] == fakehost.device_ids(fakehost.HACKS2_SCENE)["netport"] for m in daemons) and len({m["id"] for m in daemons}) == 3
    results = [json.loads(line.split("hackresult: ", 1)[1]) for line in host_log.splitlines() if "] hackresult: " in line]
    assert [m["ok"] for m in results] == [True, True, True] and [m["id"] for m in results] == [m["id"] for m in daemons]


@pytest.mark.parametrize("bug, expected", [
    ("ignore-hacks-toggle", {"with `/cyberblock guests hacks off` a guest's Breach never reaches the DLL"}),
    ("no-alarm", {'a failed Breach sends the alarm: a `daemon` with exactly `["alarm"]` for the Net Port'}),
    ("solved-on-fail", {'a failed Breach sends the alarm: a `daemon` with exactly `["alarm"]` for the Net Port'}),
    ("alarm-on-solve", {"a solved Breach sends the owner's DLL a `daemon` for the Net Port: 1 to 3 known daemon ids, strength 1..3"}),
    ("bad-strength", {"a solved Breach sends the owner's DLL a `daemon` for the Net Port: 1 to 3 known daemon ids, strength 1..3"}),
    ("devices-stay", {"the Net Port goes with the session"}),
])
def test_breach_scenario_notices_each_breach_bug(tmp_path, bug, expected):
    rep = run6(tmp_path, [bug])
    assert expected <= set(failed(rep)) and not rep.ok


def test_breach_scenario_waits_out_the_failure_cooldown_before_it_checks_the_toggle(tmp_path, monkeypatch):
    monkeypatch.setattr(mpcheck, "BREACH_COOLDOWN_S", 0.0)  # a scenario that does not wait would see the cooldown, not the toggle, block the Breach
    rep = run6(tmp_path)
    assert "with `guests hacks on` the Breach works again" in failed(rep) and not rep.ok


def test_breach_scenario_runs_the_breaches_as_the_guest_and_the_toggle_as_the_host(tmp_path, monkeypatch):
    sent = []
    real = Sim6Server.rcon
    monkeypatch.setattr(Sim6Server, "rcon", lambda self, c: sent.append(c) or real(self, c))
    assert run6(tmp_path).ok
    x, y, z = fakehost.device_cell("netport", 0, fakehost.HACKS2_SCENE)
    breaches = [c for c in sent if "test breach" in c]
    assert breaches == [f"execute as CbGuest1 run cyberblock test breach {x} {y} {z} {how}" for how in ("solve", "fail", "solve", "solve")]
    assert [c for c in sent if "guests hacks" in c] == [f"execute as CbHost1 run cyberblock guests hacks {w}" for w in ("off", "on")]
    assert any(c.startswith("tp CbGuest1 ") for c in sent)


def test_the_breach_scenario_asks_a_fake_host_for_the_hacks2_mode_with_no_layer(tmp_path):
    seen = []

    class Spy(Sim6Env):
        def fakehost(self, client, **kw):
            seen.append((client.name, kw.get("layers"), kw.get("modes"), kw.get("wait_host")))
            return super().fakehost(client, **kw)

    run_scenario(Spy(tmp_path), "breach")
    assert seen == [("CbHost1", (), ("hacks2",), mpcheck.T_JOIN)]


def test_the_breach_scenario_uses_the_phase_6_block_when_asked(capsys):
    argv = ["breach", "--dry-run", "--client-port-base", "25806", "--server-port-base", "25963", "--frame-prefix", "Local\\CyberblockFrame6P"]
    assert mpcheck.main(argv) == 0
    out = capsys.readouterr().out
    assert "-Pcyberblock.serverPort=25966" in out and "-Pcyberblock.port=25806" in out and "Local\\CyberblockFrame6PH" in out and "--hacks2" in out and "--layer" not in out
    assert "--replica" not in out and "--devices" not in out and "25600" not in out and "Local\\CyberblockFrame " not in out


def test_the_hacks2_argv_parses_with_the_real_fakehost(monkeypatch):
    seen = []

    async def fake_run(*args, **kwargs):
        seen.append((args, kwargs))
        return 1

    from pathlib import Path
    monkeypatch.setattr(fakehost, "run", fake_run)
    argv = mpcheck.fakehost_argv(port=25806, name="CbHost1", ver="1.0.0", frame="Local\\F", out_dir=Path("o"), layers=(), modes=("hacks2",), wait_host=150.0)
    monkeypatch.setattr("sys.argv", ["fakehost", *argv[4:]])
    assert fakehost.main() == 0
    args, kwargs = seen[0]
    assert args[9] == frozenset({"hacks2"}) and args[8] == frozenset() and kwargs == {"wait_host": 150.0}


def test_the_breach_log_readers_read_what_the_fake_host_prints_even_with_windows_line_ends(tmp_path):
    log = LogFile(tmp_path / "h.log")
    netport = fakehost.device_ids(fakehost.HACKS2_SCENE)["netport"]
    log.path.write_text(f'[CbHost1] daemon: {{"t": "daemon", "id": 3, "dev": "{netport}", "list": ["open_door"], "strength": 2}}\r\n[CbHost2] daemon: {{"t": "daemon", "id": 4}}\r\n'
                        '[CbHost1] hackresult: {"t": "hackresult", "id": 3, "ok": true, "reason": ""}\r\n[CbHost1] daemon: not json\r\n', encoding="utf-8")
    host = mpcheck.FakeHost("CbHost1", SimProc(), log, None)
    events = mpcheck.daemons_since(host, 0)
    assert [m["id"] for m in events] == [3] and mpcheck.daemon_is_a_solved_breach(events[0]) and not mpcheck.daemon_is_the_alarm(events[0])
    assert mpcheck.answered_ok(host, events, 0) and not mpcheck.answered_ok(host, events, log.mark()) and not mpcheck.answered_ok(host, [], 0)
    assert mpcheck.daemons_since(host, log.mark()) == [] and mpcheck.logged_events(LogFile(tmp_path / "missing.log"), mpcheck.DAEMON_EVENT) == []


def test_the_daemon_checks_accept_exactly_what_the_protocol_allows():
    dev = fakehost.device_ids(fakehost.HACKS2_SCENE)["netport"]
    good = {"t": "daemon", "id": 1, "dev": dev, "list": ["open_door", "overload", "reveal_enemies"], "strength": 3}
    assert mpcheck.daemon_is_a_solved_breach(good) and mpcheck.daemon_is_a_solved_breach({**good, "list": ["x"], "strength": 1})
    for bad in ({**good, "dev": "5"}, {**good, "list": []}, {**good, "list": ["a", "b", "c", "d"]}, {**good, "list": ["a", "a"]}, {**good, "list": ["alarm"]},
                {**good, "list": ["alarm", "a"]}, {**good, "list": ["A"]}, {**good, "list": [5]}, {**good, "strength": 0}, {**good, "strength": 4}, {**good, "strength": True},
                {**good, "list": "open_door"}):
        assert not mpcheck.daemon_is_a_solved_breach(bad), bad
    alarm = {**good, "list": ["alarm"], "strength": 1}
    assert mpcheck.daemon_is_the_alarm(alarm) and not mpcheck.daemon_is_the_alarm(good) and not mpcheck.daemon_is_the_alarm({**alarm, "list": ["alarm", "x"]})
    assert not mpcheck.daemon_is_the_alarm({**alarm, "dev": "5"}) and not mpcheck.daemon_is_the_alarm({**alarm, "strength": 4})


def test_the_netport_helper_reads_the_answers_rcon_gives():
    class S:
        def __init__(self, answers):
            self.answers = answers

        def passes(self, command):
            return mpcheck.passed(self.answers.get(command, "Test failed"))

    x, y, z = fakehost.device_cell("netport", 0, fakehost.HACKS2_SCENE)
    assert mpcheck.netport_stands(S({f"execute unless block {x} {y} {z} minecraft:air": "Test passed"})) and not mpcheck.netport_stands(S({}))
    x, y, z = fakehost.device_cell("netport", 8, fakehost.HACKS2_SCENE)
    assert mpcheck.netport_stands(S({f"execute unless block {x} {y} {z} minecraft:air": "Test passed"}), 8) and (x, y, z) == (12, 64, 3)
    assert mpcheck.BREACH_IDS["netport"] == "11529215046068469770" and mpcheck.RESERVED_DAEMON == "alarm"


def test_a_server_without_the_breach_devtools_command_is_a_clear_error(tmp_path):
    class Refusing(Sim6Server):
        def rcon(self, command):
            if "test breach" in command:
                return "Unknown or incomplete command"
            return super().rcon(command)

    class RefusingEnv(Sim6Env):
        def server(self, scenario, max_hosts=1, join_password=False):
            self.max_hosts = max_hosts
            self.server_obj = Refusing(self.tmp, self.ports.server(scenario), self, self.bugs)
            self.server_obj.log.path.write_text("Done (1s)!\n", encoding="utf-8")
            self.logs["server"] = self.server_obj.log
            return self.server_obj

    with pytest.raises(mpcheck.MpError) as e:
        mpcheck.SCENARIO_FUNCS["breach"](RefusingEnv(tmp_path), mpcheck.Report("breach", lambda line: None))
    assert "test breach" in str(e.value) and "devtools" in str(e.value)
