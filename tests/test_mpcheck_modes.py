"""tools/mpcheck.py `match`, `heat` and `overlord` (megaplan task 8-P1) without Minecraft: the scenario logic against a simulated server that does what
shared/protocol.md "Phase 8" says and the packages built (PvP only between players of one match, 8T's spectating with its own way back, the Server Heat wanted level
told to every active host whatever its `spawns` switch, 8O's spawns of a palette entry after their warning and only into a game whose owner is in the match, opted in and
allows spawns). The real run (`tools.mpcheck match heat overlord`) needs packages 8M, 8T and 8O merged and the commands of `mpcheck.PHASE8_COMMANDS`: it is the integrator's."""
import json
import re
import threading
import time
from pathlib import Path

import pytest

from test_mpcheck import SESSION_HOST, SimEnv, SimProc, SimServer, append, failed, fresh, quick, run_scenario  # noqa: F401  (quick is an autouse fixture)
from tools import fakehost, mpcheck
from tools.mpcheck import LogFile, MpError

SIM_WARNING_S = 0.25  # the simulated Overlord's warning (3 s on the real one), shrunk like every other wait
SIM_LOCK_S = 0.3  # the simulated PvP lock (15 s on the real one)
TIER_AT = (10, 30, 50, 70, 90)  # the meter values where the simulated tiers 1 to 5 start
CHECKS = {"match": 9, "heat": 12, "overlord": 13}
PALETTE = {"gang_maelstrom": 3, "gang_tyger_claws": 3, "gang_valentinos": 3}  # the simulated `spawn_palette` entries the scenario uses, with their `count`


@pytest.fixture(autouse=True)
def modes_waits(quick, monkeypatch):
    monkeypatch.setattr(mpcheck, "PVP_LOCK_S", SIM_LOCK_S + 0.1)
    monkeypatch.setattr(mpcheck, "SPAWN_WARNING_S", SIM_WARNING_S)
    monkeypatch.setattr(mpcheck, "HEAT_STEP_S", 0.0)
    monkeypatch.setattr(mpcheck, "HIT_GAP_S", 0.0)


class Sim8Server(SimServer):
    """Phase 8 as the plan describes it. `bugs` switch parts off or break them."""

    def __init__(self, tmp, port, env_sim, bugs, join_password=""):
        super().__init__(tmp, port, env_sim, bugs, join_password)
        self.health, self.gametype = {}, {}
        self.match = None  # {"creator", "members": {name: role}}
        self.pvp_until = {}  # player -> monotonic time until which PvP is still on for it (the lock after a match)
        self.meter, self.told = 0, {}  # the Server Heat meter and the tier last told to each host
        self.opted = set()  # match players who typed `/cyberblock overlord optin on`
        self.watching = set()  # players 8T's `/cyberblock spectate` started
        self.request = 0
        self.timers = []

    # -- Server Heat ------------------------------------------------------------------------------------------------------------
    def tier(self, meter):
        tier = sum(meter >= v for v in TIER_AT)
        return 5 if "heat-skips-tier-4" in self.bugs and tier == 4 else tier

    def tell_heat(self, name, force=False):
        host = self.sim.hosts.get(name)
        if not (host and host["linked"]):
            return
        if "heat-needs-spawns" in self.bugs and not self.toggles.get(name, {}).get("spawns", False):
            return  # the old reading: the `spawns` switch gating `heat`
        tier = self.tier(self.meter)
        if "no-heat-zero" in self.bugs and tier == 0:
            return
        if self.told.get(name) == tier and not force and "heat-twice" not in self.bugs:
            return
        self.told[name] = tier
        for _ in range(2 if "heat-twice" in self.bugs else 1):
            append(host["log"], f'[{name}] heat: {json.dumps({"t": "heat", "tier": 6 if "heat-bad-tier" in self.bugs and tier == 5 else tier})}')

    def on_session(self, name):
        """A new host session of a player: the tier it should have is told again (the same rule as the other restore messages)."""
        self.told.pop(name, None)
        if "no-heat-resync" not in self.bugs:
            self.tell_heat(name, force=True)

    # -- PvP --------------------------------------------------------------------------------------------------------------------
    def pvp_on(self, name):
        if "no-pvp" in self.bugs:
            return False
        if "pvp-default-on" in self.bugs:
            return True
        in_match = self.match is not None and name in self.match["members"] and self.match["members"][name] != "overlord"
        return in_match or time.monotonic() < self.pvp_until.get(name, 0.0)

    def damage(self, victim, amount, attacker):
        if self.pvp_on(victim) and self.pvp_on(attacker):
            self.health[victim] = max(0.0, self.health.get(victim, 20.0) - amount)
            for name in (victim, attacker):
                self.pvp_until[name] = time.monotonic() + SIM_LOCK_S
            if "pvp-stays-on" in self.bugs:
                self.pvp_until = {n: float("inf") for n in (victim, attacker)}

    def linked(self, name):
        return bool(name in self.sim.hosts and self.sim.hosts[name]["linked"])

    # -- the Overlord -----------------------------------------------------------------------------------------------------------
    def overlord_spawn(self, actor, entry, player):
        """8O's `Overlord.request`: an Overlord of a match, a palette entry, an opted-in match player to land beside."""
        in_match = self.match is not None and self.match["members"].get(actor) == "overlord" and self.match["members"].get(player, "overlord") != "overlord"
        if entry not in PALETTE or not (in_match or "spawn-without-match" in self.bugs):
            return "No such entry or no match"
        if player not in self.opted and not {"spawn-without-optin", "spawn-without-match"} & set(self.bugs):  # no match check, no opt-in check either
            return "No opted-in player of this match within 64 blocks"
        delay = 0.0 if "no-warning" in self.bugs else SIM_WARNING_S
        timer = threading.Timer(delay, self.deliver, (player, PALETTE[entry]))
        self.timers.append(timer)
        timer.start()
        return "Queued: it lands after the warning"

    def deliver(self, owner, n):
        host = self.sim.hosts.get(owner)
        if not (host and host["linked"] and host.get("cp")):
            return
        if not self.toggles.get(owner, {}).get("spawns", False) and "ignore-spawns-toggle" not in self.bugs:
            return  # Minecraft squads stand in
        self.request += 1
        msg = {"t": "spawn", "id": self.request, "rec": "ganger", "p": [-1.5, 64.0, 2.5], "n": n, "hostile": True, "faction": "maelstrom"}
        if "spawn-heavy-flag" in self.bugs:
            msg["heavy"] = True  # 8O as built: a flag instead of the `ncpd_heavy` record
        append(host["log"], f"[{owner}] spawn: {json.dumps(msg)}")
        if "no-spawn" in self.bugs:
            return
        for answer in host["cp"].reply({k: v for k, v in msg.items() if k != "heavy"}):
            if answer["t"] == "spawnresult":
                append(host["log"], f"[{owner}] spawnresult: {json.dumps(answer)}")

    # -- commands ---------------------------------------------------------------------------------------------------------------
    def rcon(self, command):
        if match := re.fullmatch(r"data get entity (\S+) Health", command):
            return f"{match.group(1)} has the following entity data: {self.health.get(match.group(1), 20.0)}f"
        if match := re.fullmatch(r"data get entity (\S+) playerGameType", command):
            return f"{match.group(1)} has the following entity data: {self.gametype.get(match.group(1), 0)}"
        if match := re.fullmatch(r"damage (\S+) (\d+) minecraft:player_attack by (\S+)", command):
            self.damage(match.group(1), int(match.group(2)), match.group(3))
            return "Applied damage"
        if match := re.fullmatch(r"gamemode (survival|spectator) (\S+)", command):
            self.gametype[match.group(2)] = 0 if match.group(1) == "survival" else 3
            return ""
        actor, inner = None, command
        if match := re.fullmatch(r"execute as (\S+) run (.*)", command):
            actor, inner = match.groups()
        if inner == "cyberblock spectate":  # 8T: a second one is refused, `spectate stop` is the way back
            if actor in self.watching:
                return "You are already spectating (/cyberblock spectate stop)"
            if "no-spectate" not in self.bugs:
                self.gametype[actor] = 3
                self.watching.add(actor)
            return ""
        if inner == "cyberblock spectate stop":
            if actor in self.watching and "no-spectate-stop" not in self.bugs:
                self.watching.discard(actor)
                self.gametype[actor] = 0
            return ""
        if inner == "cyberblock overlord optin on":  # 8O: only a player of a running match (not its Overlord)
            if self.match and self.match["members"].get(actor, "overlord") != "overlord":
                self.opted.add(actor)
                return "Opted in: the Overlord may send enemies and events near you in this match"
            return "Opting in needs a running match you play in"
        if match := re.fullmatch(r"cyberblock heat set (\d+)", inner):  # 8T's operator command, not under `test`
            self.meter = int(match.group(1))
            for name in list(self.sim.hosts):
                self.tell_heat(name)
            return "ok"
        if match := re.fullmatch(r"cyberblock test match start (\S+)", inner):
            if self.linked(actor):
                self.match = {"creator": actor, "members": {actor: "creator"}}
            elif "guest-creates-match" in self.bugs:
                self.match = {"creator": actor, "members": {n: "creator" for n in (*self.sim.hosts, actor)}}
            return "ok"
        if match := re.fullmatch(r"cyberblock test match join (\S+)( \S+)?", inner):
            if self.match and self.match["creator"] == match.group(1):
                self.match["members"][actor] = (match.group(2) or "").strip() or "opponent"
            return "ok"
        if inner == "cyberblock test match end":
            if self.match and self.match["creator"] == actor:
                self.match = None
                self.opted.clear()
            return "ok"
        if match := re.fullmatch(r"cyberblock test overlord spawn (\S+) (\S+)", inner):
            return self.overlord_spawn(actor, match.group(1), match.group(2))
        if match := re.fullmatch(r"cyberblock guests spawns (on|off)", inner):
            self.toggles.setdefault(actor, {})["spawns"] = match.group(1) == "on"
            return ""
        return super().rcon(command)


class Sim8Env(SimEnv):
    def __init__(self, tmp, bugs=()):
        super().__init__(tmp, bugs)
        self.started = []

    def server(self, scenario, max_hosts=1, join_password=False):
        self.max_hosts = max_hosts
        self.server_obj = Sim8Server(self.tmp, self.ports.server(scenario), self, self.bugs)
        self.server_obj.log.path.write_text("Done (1s)!\n", encoding="utf-8")
        self.logs["server"] = self.server_obj.log
        return self.server_obj

    def fakehost(self, client, **kw):
        self.started.append((client.name, kw.get("layers"), kw.get("modes")))
        return super().fakehost(client, **kw)

    def grant(self, name, host, layers, modes, ids_from):
        super().grant(name, host, layers, modes, ids_from)
        cp = fakehost.Cyberpunk(frozenset(layers), frozenset(modes), ids_from, name)
        cp.reply(SESSION_HOST)
        host["cp"] = cp
        self.server_obj.on_session(name)


def run8(tmp, name, bugs=()):
    sim = Sim8Env(tmp, bugs)
    try:
        return sim, run_scenario(sim, name)
    finally:
        for timer in sim.server_obj.timers if sim.server_obj else ():
            timer.cancel()


@pytest.mark.parametrize("name", ["match", "heat", "overlord"])
def test_the_phase_8_scenarios_pass_against_an_ideal_server(tmp_path, name):
    sim, rep = run8(tmp_path, name)
    assert rep.ok, failed(rep)
    assert len(rep.checks) >= CHECKS[name] and sim.max_hosts == 1


def test_the_heat_story_in_the_ideal_run(tmp_path):
    sim, rep = run8(tmp_path, "heat")
    assert rep.ok, failed(rep)
    host = mpcheck.FakeHost("CbHost1", SimProc(), sim.hosts["CbHost1"]["log"], None)
    assert mpcheck.heat_tiers(host)[0] == 5 and mpcheck.heat_tiers(host)[-1] == 0  # the second link: tier 5 again at its start, then the calm
    assert sim.started == [("CbHost1", (), ("modes",)), ("CbHost1", (), ("modes",))]  # the first link and the returning one


def test_the_overlord_story_in_the_ideal_run(tmp_path):
    sim, rep = run8(tmp_path, "overlord")
    assert rep.ok, failed(rep)
    host = mpcheck.FakeHost("CbHost1", SimProc(), sim.hosts["CbHost1"]["log"], None)
    spawns = mpcheck.spawns_since(host)
    assert spawns and all(mpcheck.spawn_is_valid(m) for m in spawns) and mpcheck.answered_every_spawn(host)
    assert [m["n"] for m in spawns] == [mpcheck.OVERLORD_ENTRY_N] * 2  # after the opt-in one entry, none while spawns were off, then one again
    assert mpcheck.OVERLORD_ENTRY_N == PALETTE[mpcheck.OVERLORD_ENTRIES[0]] and set(mpcheck.OVERLORD_ENTRIES) == set(PALETTE)


@pytest.mark.parametrize("name, bug, expected", [
    ("match", "pvp-default-on", "PvP is off for everyone by default"),
    ("match", "guest-creates-match", "a Minecraft-only guest cannot create a match"),
    ("match", "no-pvp", "a match the linked host created turns PvP on for its players"),
    ("match", "pvp-stays-on", "after the match and the 15 second lock PvP is off again for both"),
    ("match", "no-spectate", "`/cyberblock spectate` puts the guest in spectator mode"),
    ("match", "no-spectate-stop", "`/cyberblock spectate stop` brings the guest back to survival"),
    ("match", "no-session-end", "the session ends when the link drops"),
    ("heat", "heat-needs-spawns", "the meter at 100 tells the host's DLL `heat` tier 5"),
    ("heat", "heat-skips-tier-4", "the tiers came in order"),
    ("heat", "heat-twice", "each tier is told once"),
    ("heat", "no-heat-zero", "the meter back at 0 tells the DLL `heat` tier 0"),
    ("heat", "no-heat-resync", "a new host session at tier 5 hears `heat` tier 5 again"),
    ("heat", "heat-bad-tier", "every `heat` the DLL got is a whole tier 0..5"),
    ("overlord", "spawn-without-match", "a player who is in no match gets no spawn"),
    ("overlord", "spawn-without-optin", "a match player who did not opt in gets no spawn"),
    ("overlord", "no-warning", "... not before the"),
    ("overlord", "ignore-spawns-toggle", "with `/cyberblock guests spawns off` nothing reaches the DLL"),
    ("overlord", "spawn-heavy-flag", "after `/cyberblock overlord optin on` the Overlord's spawn reaches the host's DLL as a valid `spawn`"),
    ("overlord", "no-spawn", "the entry's 3 figures were spawned in all and the DLL answered every `spawn`"),
])
def test_each_phase_8_scenario_notices_its_bugs(tmp_path, name, bug, expected):
    _, rep = run8(tmp_path, name, [bug])
    assert any(label.startswith(expected) for label in failed(rep)) and not rep.ok, (failed(rep), bug)


def test_the_match_scenario_waits_out_the_pvp_lock_before_it_checks_that_pvp_is_off(tmp_path, monkeypatch):
    monkeypatch.setattr(mpcheck, "PVP_LOCK_S", 0.0)  # a scenario that does not wait sees the lock, not the end of the match
    _, rep = run8(tmp_path, "match")
    assert "after the match and the 15 second lock PvP is off again for both" in failed(rep)


def test_the_scenarios_run_the_creating_and_joining_as_the_players_and_the_meter_from_the_console(tmp_path, monkeypatch):
    sent = []
    real = Sim8Server.rcon
    monkeypatch.setattr(Sim8Server, "rcon", lambda self, c: sent.append(c) or real(self, c))
    run8(tmp_path, "match")
    assert [c for c in sent if "test match" in c] == ["execute as CbGuest1 run cyberblock test match start team_deathmatch", "execute as CbHost1 run cyberblock test match start team_deathmatch",
                                                      "execute as CbGuest1 run cyberblock test match join CbHost1", "execute as CbHost1 run cyberblock test match end"]
    assert [c for c in sent if "spectate" in c] == ["execute as CbGuest1 run cyberblock spectate", "execute as CbGuest1 run cyberblock spectate stop"]
    assert not any(c.startswith("gamemode") for c in sent)  # 8T's own way back, never `/gamemode` around it
    assert all(c.startswith("damage ") and "minecraft:player_attack by " in c for c in sent if c.startswith("damage"))
    sent.clear()
    run8(fresh(tmp_path / "heat"), "heat")
    assert [c for c in sent if "heat set" in c] == [f"cyberblock heat set {v}" for v in (*mpcheck.HEAT_STEPS, 0, 100, 0)]  # 8T's operator command, from the console
    assert not any("guests spawns" in c for c in sent)  # the `spawns` switch does not gate `heat`: the scenario leaves it off
    sent.clear()
    run8(fresh(tmp_path / "overlord"), "overlord")
    first, second, third = mpcheck.OVERLORD_ENTRIES
    assert [c for c in sent if "test overlord spawn" in c] == [f"execute as CbGuest1 run cyberblock test overlord spawn {e} CbHost1" for e in (first, first, first, second, third)]
    assert [c for c in sent if "guests spawns" in c] == [f"execute as CbHost1 run cyberblock guests spawns {w}" for w in ("on", "off", "on")]
    assert [c for c in sent if "optin" in c] == ["execute as CbHost1 run cyberblock overlord optin on"] and "gamemode spectator CbGuest1" in sent


def test_the_phase_8_scenarios_ask_a_fake_host_for_the_modes_mode_with_no_layer_and_use_slot_0(tmp_path, capsys):
    for name in ("match", "heat", "overlord"):
        sim, _ = run8(fresh(tmp_path / name), name)
        assert {(who, layers, modes) for who, layers, modes in sim.started} == {("CbHost1", (), ("modes",))}  # the guest is a plain client: no fake host
        assert mpcheck.Ports().server(name) == mpcheck.SERVER_BASE and mpcheck.SCENARIO_MODES[name] == ("modes",) and name in mpcheck.SCENARIOS
    assert mpcheck.SCENARIOS[-3:] == ("match", "heat", "overlord")
    assert mpcheck.main(["match", "--dry-run", "--client-port-base", "25828", "--server-port-base", "25988", "--frame-prefix", "Local\\CyberblockFrame8P"]) == 0
    out = capsys.readouterr().out
    assert "-Pcyberblock.serverPort=25988" in out and "-Pcyberblock.port=25828" in out and "--modes" in out and "--layer" not in out and "Local\\CyberblockFrame8PH" in out
    assert "25600" not in out and "Local\\CyberblockFrame " not in out


def test_the_modes_argv_parses_with_the_real_fakehost(monkeypatch):
    seen = []

    async def fake_run(*args, **kwargs):
        seen.append((args, kwargs))
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    argv = mpcheck.fakehost_argv(port=25828, name="CbHost1", ver="1.0.0", frame="Local\\CyberblockFrame8PH", out_dir=Path("frames"), layers=(), modes=("modes",), wait_host=150.0)
    assert "--modes" in argv and not any(a.startswith("--layer") for a in argv)
    monkeypatch.setattr("sys.argv", ["fakehost", *argv[4:]])
    assert fakehost.main() == 0
    args, kwargs = seen[0]
    assert args[8] == frozenset() and args[9] == frozenset({"modes"}) and kwargs == {"wait_host": 150.0}


# ---- the helpers and the rules around the scenarios ------------------------------------------------------------------------------------------
def test_the_log_readers_read_what_the_fake_host_prints_even_with_windows_line_ends(tmp_path):
    log = LogFile(tmp_path / "h.log")
    log.path.write_bytes(b'[CbHost1] heat: {"t": "heat", "tier": 2}\r\n[CbHost2] heat: {"t": "heat", "tier": 4}\r\n[CbHost1] heat: not json\r\n[CbHost1] heat: {"t": "heat", "tier": true}\r\n'
                         b'[CbHost1] spawn: {"t": "spawn", "id": 3, "rec": "ganger", "p": [1.0, 64.0, 2.0], "n": 2, "hostile": true, "faction": ""}\r\n'
                         b'[CbHost1] spawnresult: {"t": "spawnresult", "id": 3, "ok": true, "n": 2, "reason": ""}\r\n[CbHost1] heat: {"t": "heat", "tier": 5}\r\n')
    host = mpcheck.FakeHost("CbHost1", SimProc(), log, None)
    assert mpcheck.heat_tiers(host) == [2, 5] and mpcheck.heat_tiers(host, log.mark()) == []  # only CbHost1's, whole numbers, in order
    spawns = mpcheck.spawns_since(host)
    assert [m["id"] for m in spawns] == [3] and mpcheck.spawn_is_valid(spawns[0]) and mpcheck.answered_every_spawn(host)
    assert not mpcheck.answered_every_spawn(host, log.text().index("[CbHost1] spawnresult")) and not mpcheck.answered_every_spawn(host, log.mark())


def test_the_spawn_check_accepts_exactly_what_the_protocol_allows():
    good = {"t": "spawn", "id": 1, "rec": "maxtac", "p": [0.0, 64.0, 0.0], "n": 8, "hostile": True, "faction": ""}
    assert mpcheck.spawn_is_valid(good) and mpcheck.spawn_is_valid({**good, "n": 1, "faction": "police", "hostile": False})
    assert mpcheck.spawn_is_valid({**good, "rec": "ncpd_heavy"})  # the heavy unit is a record...
    for bad in ({**good, "heavy": True}, {**good, "rec": "ncpd", "heavy": True}, {**good, "rec": "turret"},{**good, "n": 0}, {**good, "n": 9}, {**good, "n": True}, {**good, "id": -1}, {**good, "p": [1.0, 2.0]}, {**good, "hostile": 1},
                {**good, "faction": "Tyger Claws"}, {**good, "extra": 1}, {k: v for k, v in good.items() if k != "faction"}):
        assert not mpcheck.spawn_is_valid(bad), bad


def test_the_rcon_readers_read_the_answers_minecraft_gives():
    class S:
        def __init__(self, answers):
            self.answers = answers

        def rcon(self, command):
            return self.answers.get(command, "No entity was found")

    s = S({"data get entity CbHost1 Health": "CbHost1 has the following entity data: 16.0f\r\n", "data get entity CbGuest1 playerGameType": "CbGuest1 has the following entity data: 3"})
    assert mpcheck.health_of(s, "CbHost1") == 16.0 and mpcheck.health_of(s, "Nobody") is None
    assert mpcheck.game_type_of(s, "CbGuest1") == 3 and mpcheck.game_type_of(s, "CbHost1") is None


def test_a_hit_that_cannot_be_measured_is_a_clear_error():
    class S:
        def rcon(self, command):
            return "No entity was found"

    with pytest.raises(MpError) as e:
        mpcheck.hit_hurts(S(), "CbGuest1", "CbHost1")
    assert "Health" in str(e.value)


def test_a_server_without_the_phase_8_devtools_commands_is_a_clear_error_that_names_the_table(tmp_path):
    class Refusing(Sim8Server):
        def rcon(self, command):
            if "heat set" in command:
                return "Unknown or incomplete command"
            return super().rcon(command)

    class RefusingEnv(Sim8Env):
        def server(self, scenario, max_hosts=1, join_password=False):
            self.max_hosts = max_hosts
            self.server_obj = Refusing(self.tmp, self.ports.server(scenario), self, self.bugs)
            self.server_obj.log.path.write_text("Done (1s)!\n", encoding="utf-8")
            self.logs["server"] = self.server_obj.log
            return self.server_obj

    with pytest.raises(MpError) as e:
        mpcheck.SCENARIO_FUNCS["heat"](RefusingEnv(tmp_path), mpcheck.Report("heat", lambda line: None))
    assert "PHASE8_COMMANDS" in str(e.value) and "cyberblock heat set" in str(e.value) and set(mpcheck.PHASE8_COMMANDS) == {"heat", "match_start", "match_join", "match_end", "overlord_spawn"}


def test_a_scenario_whose_server_code_is_not_in_the_checkout_is_a_skip_with_all_and_never_a_pass(tmp_path):
    sim, said = SimEnv(tmp_path), []
    sim.repo = tmp_path  # an empty checkout: none of the Phase 8 package folders exist
    reports = mpcheck.run_scenarios(sim, ["match", "heat", "overlord", "versions"], said.append, skip_unbuilt=True)
    assert [bool(r.skipped) for r in reports] == [True, True, True, False] and all(r.ok for r in reports[:3]) and reports[0].checks == [] and sim.stopped == [True]
    assert "mode/match" in reports[0].skipped.replace("\\", "/") and any(line.startswith("  SKIP  match:") for line in said)
    said.clear()
    assert mpcheck.summary(reports, said.append) == 0 and [line for line in said if "SKIP" in line][:3] == [f"  SKIP  {n}: {reports[i].skipped}" for i, n in enumerate(("match", "heat", "overlord"))]
    assert any("not a pass" in line for line in said) and any("PASS  versions" in line for line in said)
    only_skipped = mpcheck.run_scenarios(sim, ["match"], lambda line: None, skip_unbuilt=True)
    assert mpcheck.summary(only_skipped, lambda line: None) == 0  # a run that skipped everything it was asked for does not fail, and says so


def test_a_named_scenario_always_runs_and_a_present_package_folder_is_not_skipped(tmp_path):
    sim = Sim8Env(tmp_path)
    sim.repo = tmp_path  # no package folders...
    reports = mpcheck.run_scenarios(sim, ["match"], lambda line: None)  # ... but not asked with --all: it runs, against the (ideal) simulated server
    assert not reports[0].skipped and reports[0].ok
    (tmp_path / mpcheck.PHASE8_SOURCE / "mode" / "heat").mkdir(parents=True)
    assert mpcheck.unbuilt(tmp_path, "heat") == "" and mpcheck.unbuilt(tmp_path, "overlord") and mpcheck.unbuilt(None, "overlord") == "" and mpcheck.unbuilt(tmp_path, "guest") == ""


def test_main_with_all_passes_the_skip_flag_and_naming_a_scenario_does_not(monkeypatch, tmp_path):
    seen = []
    monkeypatch.setattr(mpcheck, "run_scenarios", lambda env, names, say=print, skip_unbuilt=False: seen.append((list(names), skip_unbuilt)) or [])
    monkeypatch.setattr(mpcheck, "summary", lambda reports, say=print: 0)
    (tmp_path / ".git").write_text("gitdir: elsewhere", encoding="utf-8")
    monkeypatch.setattr(mpcheck.play, "find_jdk", lambda candidates: tmp_path)
    monkeypatch.setattr(mpcheck, "mod_version", lambda repo: "1.0.0")
    monkeypatch.setattr(mpcheck.Env, "close", lambda self: None)
    assert mpcheck.main(["--all"], repo=tmp_path) == 0 and mpcheck.main(["match"], repo=tmp_path) == 0
    assert seen == [(list(mpcheck.SCENARIOS), True), (["match"], False)]


def test_the_phase_8_scenarios_name_their_assumptions():
    doc = " ".join(mpcheck.__doc__.split())
    for phrase in ("match         Phase 8", "heat          Phase 8", "overlord      Phase 8", "`PHASE8_COMMANDS`", "`PHASE8_NEEDS`", "reported as SKIP", "Naming a scenario always runs it",
                   "`guests spawns off`", "PvP is off for everyone by default"):
        assert " ".join(phrase.split()) in doc, phrase
    assert mpcheck.PHASE8_NEEDS == {"match": "mode/match", "heat": "mode/heat", "overlord": "mode/overlord"} and mpcheck.SPAWN_WARNING_S == SIM_WARNING_S  # patched by the fixture
