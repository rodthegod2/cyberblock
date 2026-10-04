"""tools/fakehost.py `--modes` (megaplan task 8-P1): the DLL's side of the Phase 8 `spawn`, `heat`, `attitude` and `glitch`, against the contract of
shared/protocol.md "Phase 8". The fake host is the stand-in the harness (tools/mpcheck.py `match`, `heat` and `overlord`) and the dev client use before the real
DLL speaks."""
import pytest

from test_fakehost import HOST_SESSION, exporting, mode_set, printed, quick, run_against_fake_minecraft, types  # noqa: F401  (exporting and quick are fixtures)
from test_phase8_protocol import attitude_ok, glitch_ok, heat_ok, spawn_ok, spawnresult_ok
from test_protocol_md import R3_VALIDATORS, p5_npcs_ok
from tools import fakehost

LEAVE = {"t": "session", "state": "guest", "reason": "", "perms": {"explosions": False, "damage": False, "hacks": False, "devices": False, "spawns": False}}


def host(**kw):
    """A --modes fake host whose session is already `host` (the DLL ignores the four messages without one)."""
    h = fakehost.Cyberpunk(frozenset(), frozenset({"modes"}), **kw)
    h.reply(HOST_SESSION)
    return h


def spawn(rec="ganger", n=3, rid=41, p=(-1.5, 64.0, 2.5), hostile=True, faction="maelstrom"):
    return {"t": "spawn", "id": rid, "rec": rec, "p": list(p), "n": n, "hostile": hostile, "faction": faction}


def answer(answers):
    assert len(answers) == 1 and answers[0]["t"] == "spawnresult" and spawnresult_ok(answers[0]), answers
    return answers[0]


# ---- the scene and the modes that do not answer ------------------------------------------------------------------------------------------
def test_modes_stands_on_the_street_and_only_with_the_mode_answers_the_phase_8_messages():
    h = host()
    assert h.modes8 and h.street and not h.devices and h.cam(1, 0.0, {})["pl"] == [-6.5, 64.0, 0.5] and h.script() == []
    for modes in ({"hacks2"}, {"replica"}, {"figures"}, {"vendor"}):
        other = fakehost.Cyberpunk(frozenset(), frozenset(modes))
        other.reply(HOST_SESSION)
        assert other.reply(spawn()) == [] and other.reply({"t": "heat", "tier": 3}) == [] and not other.modes8 and other.spawned == []
    got = fakehost.Cyberpunk(frozenset(), frozenset({"modes"}), shift=8).reply(HOST_SESSION)
    kinds = [m["t"] for m in got]
    assert kinds[0] == "clear" and kinds[-1] == "npcs" and len(kinds) > 2 and set(kinds[1:-1]) == {"ground"}  # a --modes host sends its ground and the npcs when the client makes it the host


def test_the_four_messages_are_ignored_without_a_host_session():
    h = fakehost.Cyberpunk(frozenset(), frozenset({"modes"}))  # no `session` yet
    assert h.reply(spawn()) == [] and h.reply({"t": "heat", "tier": 4}) == [] and h.reply({"t": "attitude", "faction": "police", "toward": "hostile"}) == []
    assert h.reply({"t": "glitch", "seconds": 3}) == [] and (h.spawned, h.heat, h.attitudes, h.glitch) == ([], 0, {}, 0.0)
    h.reply(HOST_SESSION)
    h.reply(LEAVE)  # a session that is not `host`
    assert h.reply(spawn()) == [] and h.spawned == []


# ---- spawn ----------------------------------------------------------------------------------------------------------------------------------
@pytest.mark.parametrize("rec", fakehost.SPAWN_RECORDS)
def test_every_record_is_answered_ok_and_the_figures_join_the_npcs_list(rec):
    h = host()
    before = len(h.npcs(0.0)["list"])
    m = spawn(rec, n=3)
    assert spawn_ok(m)
    assert answer(h.reply(m)) == {"t": "spawnresult", "id": 41, "ok": True, "n": 3, "reason": ""}
    listed = h.npcs(0.0)
    assert len(listed["list"]) == before + 3 and p5_npcs_ok(listed)
    new = listed["list"][before:]
    assert [e["p"] for e in new] == [[-1.5, 64.0, 2.5]] * 3 and all(e["hostile"] is True and e["kind"] == "human" and e["arch"] == fakehost.SPAWN_ARCH[rec] for e in new)
    assert len({e["id"] for e in new}) == 3 and all(int(e["id"]) > fakehost.SPAWN_ID_BASE for e in new)


def test_a_faction_or_the_records_default_gang_is_the_figures_affiliation_and_hostile_false_means_standing_about():
    h = host()
    h.reply(spawn("ganger", 1, faction="tygerClaws"))
    h.reply(spawn("ganger", 1, rid=42, faction=""))
    h.reply(spawn("drone", 1, rid=43, faction="police", hostile=False))
    h.reply(spawn("maxtac", 1, rid=44, faction=""))
    fresh = h.npcs(0.0)["list"][3:]
    assert [e["fac"] for e in fresh] == ["TygerClaws", "Maelstrom", "NCPD", "NCPD"] and [e["hostile"] for e in fresh] == [True, True, False, True]
    assert [e["arch"] for e in fresh] == ["Gunner", "Gunner", "Drone", "Heavy"] and fresh[2]["anim"] == 0 and fresh[0]["anim"] == 128  # combat bit for the ones that fight
    assert len({e["id"] for e in fresh}) == 4


def test_the_spawn_refusals_each_say_why_and_change_nothing():
    h = host()
    for rec, y, n, reason in (("turret", 64.0, 1, "unsupported"), ("nonsense", 64.0, 1, "unsupported"), ("ganger", 400.0, 1, "no ground"), ("ganger", -100.0, 1, "no ground")):
        res = answer(h.reply(spawn(rec, n, p=(0.0, y, 0.0))))
        assert res == {"t": "spawnresult", "id": 41, "ok": False, "n": 0, "reason": reason}
    assert h.spawned == [] and h.npcs(0.0)["list"] == fakehost.npcs_message()["list"]
    for _ in range(3):
        assert answer(h.reply(spawn(n=8)))["ok"]
    assert len(h.spawned) == fakehost.SPAWN_CAP == 24
    assert answer(h.reply(spawn(n=1)))["reason"] == "too many" and len(h.spawned) == 24  # the cap is the scripts' count of live spawned NPCs
    assert answer(h.reply(spawn(n=1, p=(0.0, 64.0, 0.0), rec="ganger")))["ok"] is False


def test_a_spawn_with_a_bad_type_or_limit_is_dropped_not_answered():
    h = host()
    for bad in ({**spawn(), "id": -1}, {**spawn(), "id": "41"}, {**spawn(), "n": 0}, {**spawn(), "n": 9}, {**spawn(), "n": True}, {**spawn(), "rec": ""}, {**spawn(), "rec": "Ganger"},
                {**spawn(), "rec": 5}, {**spawn(), "hostile": 1}, {**spawn(), "faction": "Tyger Claws"}, {**spawn(), "faction": 5}, {**spawn(), "p": [1.0, 2.0]},
                {**spawn(), "p": [3e7, 0.0, 0.0]}, {k: v for k, v in spawn().items() if k != "faction"}, {k: v for k, v in spawn().items() if k != "id"}):
        assert h.reply(bad) == [], bad
    assert h.spawned == []


def test_the_spawned_figures_stand_in_every_kind_of_npc_list():
    for modes in ({"modes"}, {"modes", "replica"}, {"modes", "hacks2"}):
        h = fakehost.Cyberpunk(frozenset(), frozenset(modes))
        h.reply(HOST_SESSION)
        base = len(h.npcs(0.0)["list"])
        h.reply(spawn(n=2))
        assert len(h.npcs(0.0)["list"]) == base + 2 and p5_npcs_ok(h.npcs(0.0)), modes


# ---- heat, attitude, glitch ----------------------------------------------------------------------------------------------------------------
@pytest.mark.parametrize("tier", range(6))
def test_heat_writes_the_wanted_level_and_the_next_stats_carries_it(tier):
    h = host()
    m = {"t": "heat", "tier": tier}
    assert heat_ok(m)
    stats = h.reply(m)
    assert stats == [fakehost.modes_stats(tier)] and stats[0]["wanted"] == tier and R3_VALIDATORS["stats"](stats[0]) and h.heat == tier


def test_a_bad_heat_attitude_or_glitch_is_dropped():
    h = host()
    for bad in ({"t": "heat", "tier": 6}, {"t": "heat", "tier": -1}, {"t": "heat", "tier": 2.5}, {"t": "heat", "tier": "3"}, {"t": "heat", "tier": True}, {"t": "heat"},
                {"t": "attitude", "faction": "police", "toward": "angry"}, {"t": "attitude", "faction": "", "toward": "hostile"}, {"t": "attitude", "faction": 3, "toward": "hostile"},
                {"t": "attitude", "faction": "Tyger Claws", "toward": "hostile"}, {"t": "glitch", "seconds": 10.5}, {"t": "glitch", "seconds": -1}, {"t": "glitch", "seconds": True},
                {"t": "glitch", "seconds": "3"}, {"t": "glitch", "seconds": float("nan")}, {"t": "glitch"}):
        assert h.reply(bad) == [], bad
    assert (h.heat, h.attitudes, h.glitch) == (0, {}, 0.0)


def test_attitude_keeps_a_known_groups_stance_and_ignores_an_unknown_group():
    h = host()
    for group in fakehost.ATTITUDE_GROUPS:
        m = {"t": "attitude", "faction": group, "toward": "friendly"}
        assert attitude_ok(m) and h.reply(m) == []
    assert h.attitudes == {g: "friendly" for g in fakehost.ATTITUDE_GROUPS} and "player" not in h.attitudes
    h.reply({"t": "attitude", "faction": "police", "toward": "hostile"})
    h.reply({"t": "attitude", "faction": "player", "toward": "hostile"})  # V's own group is never a `faction`
    h.reply({"t": "attitude", "faction": "nobody", "toward": "hostile"})
    assert h.attitudes["police"] == "hostile" and "player" not in h.attitudes and "nobody" not in h.attitudes and len(h.attitudes) == 16


def test_glitch_is_kept_and_a_new_one_replaces_it_and_zero_ends_it():
    h = host()
    for seconds, kept in ((4.5, 4.5), (10, 10.0), (0, 0.0), (0.25, 0.25)):
        m = {"t": "glitch", "seconds": seconds}
        assert glitch_ok(m) and h.reply(m) == [] and h.glitch == kept


def test_the_end_of_the_session_ends_every_effect_and_puts_the_stars_back():
    h = host()
    h.reply(spawn(n=4))
    h.reply({"t": "heat", "tier": 5})
    h.reply({"t": "attitude", "faction": "maelstrom", "toward": "hostile"})
    h.reply({"t": "glitch", "seconds": 8})
    assert len(h.spawned) == 4 and h.heat == 5
    assert h.reply(LEAVE) == [fakehost.modes_stats(0)]  # the stars go back (the next `stats` says 0)
    assert (h.spawned, h.heat, h.attitudes, h.glitch) == ([], 0, {}, 0.0) and h.npcs(0.0)["list"] == fakehost.npcs_message()["list"]
    assert h.reply(LEAVE) == []  # nothing more to put back
    h.reply(HOST_SESSION)  # a new session starts clean and answers again
    assert answer(h.reply(spawn(n=1)))["n"] == 1


# ---- the whole mode against a fake Minecraft ------------------------------------------------------------------------------------------------
def test_modes_mode_answers_over_the_link_and_prints_both_sides(tmp_path, exporting, quick, capsys):
    asks = [HOST_SESSION, spawn(rid=1), spawn("turret", 1, rid=2), {"t": "heat", "tier": 4}, {"t": "attitude", "faction": "tygerClaws", "toward": "friendly"},
            {"t": "glitch", "seconds": 3}, spawn("drone", 1, rid=3, hostile=False, faction="police"), {"t": "spawn", "id": 4, "rec": "ganger"}]
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=asks, hold=1.5, layers=frozenset(), modes=mode_set("modes"))
    results = {m["id"]: m for m in got if m["t"] == "spawnresult"}
    assert {i: (m["ok"], m["n"], m["reason"]) for i, m in results.items()} == {1: (True, 3, ""), 2: (False, 0, "unsupported"), 3: (True, 1, "")} and all(spawnresult_ok(m) for m in results.values())
    stats = [m for m in got if m["t"] == "stats"]
    assert [m["wanted"] for m in stats] == [4] and all(R3_VALIDATORS["stats"](m) for m in stats)
    npcs = [m for m in got if m["t"] == "npcs"]
    assert len(npcs[0]["list"]) == 3 and len(npcs[-1]["list"]) == 3 + 3 + 1 and all(p5_npcs_ok(m) for m in npcs)  # the enemies join the list the host resends at 10 Hz
    assert "ground" in types(got) and "devices" not in types(got)
    lines = printed(capsys)
    assert [m["id"] for m in lines["spawn"]] == [1, 2, 3, 4] and lines["heat"] == [{"t": "heat", "tier": 4}] and lines["glitch"] == [{"t": "glitch", "seconds": 3}]
    assert lines["attitude"] == [{"t": "attitude", "faction": "tygerClaws", "toward": "friendly"}] and sorted(m["id"] for m in lines["spawnresult"]) == [1, 2, 3]


def test_modes_events_are_printed_only_with_the_mode(tmp_path, exporting, capsys):
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=[HOST_SESSION, spawn(), {"t": "heat", "tier": 2}], hold=0.1, modes=mode_set("seat"))
    lines = printed(capsys)
    assert not {"spawn", "heat", "attitude", "glitch", "spawnresult"} & set(lines)
    assert fakehost.printed_events(frozenset(), frozenset({"modes"})) >= {"spawn", "heat", "attitude", "glitch"}


def test_modes_flag_picks_the_mode_and_the_conflicts_are_refused(monkeypatch):
    seen = []

    async def fake_run(*args, **kwargs):
        seen.append((args, kwargs))
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    for argv in (["--modes"], ["--modes", "--hacks2", "--shift", "8", "--hold", "3"], ["--modes", "--layer2", "--shift", "-4"], ["--modes", "--replica"], ["--modes", "--devices"]):
        monkeypatch.setattr("sys.argv", ["fakehost", *argv])
        assert fakehost.main() == 0
    assert [a[9] for a, _ in seen] == [frozenset({"modes"}), frozenset({"modes", "hacks2"}), frozenset({"modes"}), frozenset({"modes", "replica"}), frozenset({"modes", "devices"})]
    assert [k for _, k in seen] == [{}, {"shift": 8}, {"shift": -4}, {}, {}]
    for argv in (["--modes", "--view"], ["--modes", "--stairs"], ["--modes", "--seat"], ["--modes", "--layer4"], ["--modes", "--vendor"], ["--modes", "--ui"],
                 ["--modes", "--figures"], ["--modes", "--env-hours", "3"], ["--modes", "--shift", "1001"], ["--modes", "--park-refuse"]):
        monkeypatch.setattr("sys.argv", ["fakehost", *argv])
        with pytest.raises(SystemExit) as e:
            fakehost.main()
        assert e.value.code == 2, argv


def test_the_module_docstring_describes_the_mode():
    doc = " ".join(fakehost.__doc__.split())
    for phrase in ("--modes", "`spawnresult`", "`unsupported`", "`turret`", "`no ground`", "`too many`", "`stats.wanted`", "`SetWantedLevel`", "`attitude`", "`glitch`", "without a host session"):
        assert phrase in doc, phrase
