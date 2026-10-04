"""tools/fakehost.py `--hacks2` (megaplan task 6-P1): the DLL's side of the Phase 6 remote hacks, the Breach daemons and the photo feed, against the contract of
shared/protocol.md "Phase 6". The fake host is the stand-in the harness (tools/mpcheck.py `breach`) and the dev client use before the real DLL speaks."""
import base64
import math
import time
from pathlib import Path

import pytest

from test_fakehost import (HOST_SESSION, exporting, mode_set, printed, quick, run_against_fake_minecraft, types)  # noqa: F401  (exporting and quick are fixtures)
from test_phase6_protocol import daemon_ok, feed_ok, feedreq_ok, hackresult_ok, rhack_ok
from test_protocol_md import devstate_ok, devices_ok, uint64_string
from tools import fakehost

DEV = fakehost.device_ids(fakehost.HACKS2_SCENE)
CAR = fakehost.NPC_IDS[2]
HOSTILE = fakehost.NPC_IDS[0]


def host(**kw):
    return fakehost.Cyberpunk(frozenset(), frozenset({"hacks2"}), **kw)


def rhack(hack, target=None, rid=21, **extra):
    """A DOWN `rhack`; a vehicle hack carries the server's `secs` 30 unless the test says otherwise (every vehicle effect is timed)."""
    if hack in fakehost.VEHICLE_HACKS:
        extra.setdefault("secs", 30)
    return {"t": "rhack", "id": rid, "target": target, "hack": hack, **extra}


def area(hack, p=(0.0, 64.0, 0.0), r=60, secs=180, rid=23):
    return rhack(hack, None, rid, p=list(p), r=r, secs=secs)


def daemon(*ids, dev=None, strength=2, rid=24):
    return {"t": "daemon", "id": rid, "dev": dev or DEV["netport"], "list": list(ids), "strength": strength}


def result(answers):
    assert answers and answers[0]["t"] == "hackresult" and hackresult_ok(answers[0]), answers
    return answers[0]


# ---- the scene -------------------------------------------------------------------------------------------------------------------------
def test_the_hacks2_scene_is_the_devices_scene_plus_the_net_port_and_devices_mode_is_unchanged():
    assert fakehost.HACKS2_SCENE[:-1] == fakehost.DEVICE_SCENE and fakehost.HACKS2_SCENE[-1][0] == "netport"
    assert len(fakehost.device_table()) == 7 and len(fakehost.device_table(0, fakehost.HACKS2_SCENE)) == 8 and set(fakehost.device_ids()) < set(DEV)
    assert DEV["netport"] == "11529215046068469770" and uint64_string(DEV["netport"]) and fakehost.device_cell("netport", 0, fakehost.HACKS2_SCENE) == (4, 64, 3)
    table = fakehost.device_table(0, fakehost.HACKS2_SCENE)
    assert table[DEV["netport"]]["kind"] == "terminal" and table[DEV["netport"]]["cls"] == "Terminal" and devices_ok(fakehost.devices_message(table))
    street = fakehost.replica_cells()
    assert fakehost.device_cell("netport", 0, fakehost.HACKS2_SCENE) not in street  # in the air above the street, like the other devices
    assert host().dev_table == table and fakehost.Cyberpunk(frozenset(), frozenset({"devices"})).dev_table == fakehost.device_table()


def test_hacks2_stands_on_the_street_like_devices_and_still_answers_devact():
    h = host()
    assert h.devices and h.street and h.cam(1, 0.0, {})["pl"] == [-6.5, 64.0, 0.5] and h.script() == []
    assert h.reply({"t": "devact", "id": 7, "dev": DEV["door"], "act": "open"})[0] == {"t": "devresult", "id": 7, "ok": True, "reason": ""}
    got = host(shift=8).reply(HOST_SESSION)
    assert [m["t"] for m in got] == ["clear", *["ground"] * 6, "devices", "npcs"] and len(got[-2]["list"]) == 8


def test_only_hacks2_mode_answers_the_phase_6_requests():
    for modes in ({"devices"}, {"replica"}, {"figures"}):
        h = fakehost.Cyberpunk(frozenset(), frozenset(modes))
        assert h.reply(rhack("veh_brake", CAR)) == [] and h.reply(daemon("open_door")) == [] and h.reply({"t": "feedreq", "on": True}) == [] and not h.feed_on


# ---- rhack: vehicle and camera hacks ----------------------------------------------------------------------------------------------------
@pytest.mark.parametrize("hack, effect", sorted(fakehost.VEHICLE_EFFECT.items()))
def test_every_vehicle_hack_is_ok_on_the_car_and_changes_its_state(hack, effect):
    h = host()
    before = dict(h.car)
    answers = h.reply(rhack(hack, CAR))
    assert answers == [{"t": "hackresult", "id": 21, "ok": True, "reason": ""}] and hackresult_ok(answers[0])
    assert h.car == {**before, **effect}


def test_a_vehicle_hack_on_something_that_is_not_a_car_in_the_list_says_vehicle_gone():
    h = host()
    for target in (HOSTILE, "99999", DEV["camera"]):  # a person, an id the list does not have, a device
        assert result(h.reply(rhack("veh_lock", target))) == {"t": "hackresult", "id": 21, "ok": False, "reason": "vehicle gone"}
    assert h.car == {"locked": False, "engine": True, "braked": False, "jammed": False}


def test_a_target_or_centre_farther_than_50_m_from_v_is_out_of_range_the_documented_reason():
    far = host(shift=100)  # V stands 100 m east of the figures and, once the devices are put back at the origin, of them too
    far.dev_table = fakehost.device_table(0, fakehost.HACKS2_SCENE)
    assert fakehost.OUT_OF_RANGE == "cyberblock.hack.out_of_range" and len(fakehost.OUT_OF_RANGE) <= 64
    for m in (rhack("veh_start", CAR), rhack("cam_feed", DEV["camera"]), area("blackout", (0.0, 64.0, 0.0)), area("outline", (0.0, 64.0, 0.0))):
        assert result(far.reply(m))["reason"] == fakehost.OUT_OF_RANGE and result(far.reply(m))["ok"] is False
    assert far.car["engine"] is True
    near = host()
    assert result(near.reply(area("ping_area", (-6.5, 64.0, 0.5 + fakehost.HACK_RANGE_M))))["ok"] is True  # exactly 50 m is still in range
    assert result(near.reply(area("ping_area", (-6.5, 64.0, 0.5 + fakehost.HACK_RANGE_M + 0.1))))["reason"] == fakehost.OUT_OF_RANGE


def test_cam_feed_needs_a_camera_in_the_table():
    h = host()
    assert result(h.reply(rhack("cam_feed", DEV["camera"]))) == {"t": "hackresult", "id": 21, "ok": True, "reason": ""}
    assert result(h.reply(rhack("cam_feed", DEV["light"])))["reason"] == "not a camera"
    assert result(h.reply(rhack("cam_feed", "123")))["reason"] == "device gone"
    assert h.reply(rhack("cam_feed", None)) == []  # no target at all: the wrong shape is dropped


def test_a_hack_name_outside_the_eleven_is_unsupported_not_dropped():
    h = host()
    for name in ("explode", "short_circuit", "", "VEH_LOCK"):
        assert result(h.reply(rhack(name, CAR))) == {"t": "hackresult", "id": 21, "ok": False, "reason": "unsupported"}
    assert set(fakehost.REMOTE_HACKS) == set(fakehost.VEHICLE_HACKS) | set(fakehost.AREA_HACKS) | {"cam_feed"} and len(fakehost.REMOTE_HACKS) == 11


@pytest.mark.parametrize("bad", [rhack("veh_lock", CAR, rid=-1), rhack("veh_lock", CAR, rid=True), rhack("veh_lock", CAR, rid="21"), rhack("veh_lock", 5),
                                 rhack("veh_lock", "0"), rhack("veh_lock", "-3"), rhack("veh_lock", "x1"), {"t": "rhack", "id": 21, "target": CAR},
                                 {"t": "rhack", "id": 21, "hack": "veh_lock"}, {"t": "rhack", "target": CAR, "hack": "veh_lock"}, rhack(None, CAR), rhack(7, CAR),
                                 {k: v for k, v in rhack("veh_lock", CAR).items() if k != "secs"}, rhack("veh_jam", CAR, secs=0), rhack("veh_jam", CAR, secs=601),
                                 rhack("veh_jam", CAR, secs=1.5), rhack("veh_jam", CAR, secs=True)])
def test_a_bad_rhack_is_dropped_and_not_answered(bad):
    assert host().reply(bad) == []


# ---- rhack: area hacks -------------------------------------------------------------------------------------------------------------------
def test_blackout_switches_the_powered_devices_in_the_area_off_and_gives_them_back():
    h = host()
    answers = h.reply(area("blackout", (0.0, 64.0, 3.5), r=60, secs=3))
    assert result(answers)["ok"] is True
    states = {m["id"]: m for m in answers[1:] if m["t"] == "devstate"}
    assert all(devstate_ok(m) and m["by"] == "script" and m["st"] == -1 and not m["fl"] & fakehost.FL_POWERED for m in states.values())
    kinds = {h.dev_table[i]["kind"] for i in states}
    assert kinds == {"light", "camera", "vending", "elevator", "terminal"}  # doors are left alone (their -1 is `locked`), the sealed door is quest-locked anyway
    assert all(d["st"] != -1 for d in h.dev_table.values() if d["kind"] == "door") and len(h.restores) == len(states) == 5
    assert h.due(time.perf_counter()) == [] and len(h.restores) == 5  # not yet
    back = h.due(time.perf_counter() + 3 * fakehost.SCRIPT_SCALE + 1)
    assert {m["id"] for m in back} == set(states) and all(m["by"] == "script" and m["st"] >= 0 and m["fl"] & fakehost.FL_POWERED for m in back)
    assert h.dev_table == fakehost.device_table(0, fakehost.HACKS2_SCENE) and h.restores == [] and h.blacked == {}


def test_blackout_stays_in_its_radius_and_always_has_a_timer():
    h = host()
    near = h.reply(area("blackout", (-1.0, 64.0, 4.0), r=1, secs=5))  # the light at (0.5, 64, 4.5) is 1.58 m from (-1, 64, 4): outside r 1
    assert [m["t"] for m in near] == ["hackresult"] and h.restores == []
    small = h.reply(area("blackout", (0.5, 64.0, 4.5), r=1, secs=5))
    assert [m["t"] for m in small] == ["hackresult", "devstate"] and small[1]["id"] == DEV["light"] and len(h.restores) == 1  # it comes back
    assert h.dev_table[DEV["light"]]["st"] == -1
    again = h.reply(area("blackout", (0.5, 64.0, 4.5), r=1, secs=5))
    assert [m["t"] for m in again] == ["hackresult"] and len(h.restores) == 1  # already unpowered: nothing changes twice
    assert h.reply(area("blackout", (0.5, 64.0, 4.5), r=1, secs=0)) == []  # secs 0 would leave the devices off for good: dropped


def test_vehicle_effects_wear_off_after_secs_and_a_second_hack_restarts_the_timer_but_keeps_the_first_state():
    h = host()
    start = time.perf_counter()
    assert result(h.reply(rhack("veh_lock", CAR, secs=2)))["ok"] and result(h.reply(rhack("veh_engine_off", CAR, secs=4, rid=22)))["ok"]
    assert h.car == {"locked": True, "engine": False, "braked": False, "jammed": False}
    h.due(start + 2 * fakehost.SCRIPT_SCALE + 0.5)
    assert h.car == {"locked": False, "engine": False, "braked": False, "jammed": False}  # the lock wore off, the engine is still dead
    assert result(h.reply(rhack("veh_start", CAR, secs=10, rid=23)))["ok"] and h.car["engine"] is True  # a second engine hack: the timer restarts
    h.due(start + 4 * fakehost.SCRIPT_SCALE + 0.5)
    assert h.car["engine"] is True  # not yet: veh_start restarted the engine field's timer
    h.due(time.perf_counter() + 10 * fakehost.SCRIPT_SCALE + 1)
    assert h.car == {"locked": False, "engine": True, "braked": False, "jammed": False} and h.car_due == {} and h.car_before == {}  # as before the first hack


@pytest.mark.parametrize("state", ["none", "guest", "refused"])
def test_a_session_that_is_not_host_puts_every_pending_blackout_and_car_effect_back_at_once(state):
    h = host()
    h.reply(HOST_SESSION)
    h.reply(area("blackout", (0.0, 64.0, 3.5), r=60, secs=600))
    h.reply(rhack("veh_jam", CAR, secs=600))
    h.reply(rhack("veh_brake", CAR, secs=600, rid=22))
    assert h.restores and h.car["jammed"] and h.car["braked"]
    h.reply({"t": "session", "state": state, "reason": "test", "perms": {}})
    assert h.dev_table == fakehost.device_table(0, fakehost.HACKS2_SCENE) and h.restores == [] and h.blacked == {}
    assert h.car == {"locked": False, "engine": True, "braked": False, "jammed": False} and h.car_due == {}


def test_outline_and_ping_area_reveal_the_hostile_people_of_the_area_like_the_ping_chip():
    for name in ("outline", "ping_area"):
        h = host()
        got = h.reply(area(name, (2.5, 64.0, 2.5), r=3, secs=20, rid=31))
        assert [m["t"] for m in got] == ["hackresult", "hackspread"] and got[1] == {"t": "hackspread", "id": 31, "target": HOSTILE, "why": "ping"}
        assert [m["t"] for m in h.reply(area(name, (-2.5, 64.0, 2.5), r=1))] == ["hackresult"]  # the friendly person is never outlined, the hostile one is too far
        assert result(h.reply(area(name, (2.5, 64.0, 2.5), r=3, secs=0)))["ok"] is True  # a one-shot: nothing lasts, so secs 0 is fine
    assert fakehost.MAX_SPREADS == 16


def test_traffic_switch_and_gridlock_are_ok_and_change_nothing_here():
    h = host()
    for name in ("traffic_switch", "gridlock"):
        assert h.reply(area(name, (0.0, 64.0, 0.0), r=40, secs=60)) == [{"t": "hackresult", "id": 23, "ok": True, "reason": ""}]
    assert h.dev_table == fakehost.device_table(0, fakehost.HACKS2_SCENE)


@pytest.mark.parametrize("bad", [{k: v for k, v in area("blackout").items() if k != key} for key in ("p", "r", "secs")]
                         + [area("blackout", r=0), area("blackout", r=151), area("blackout", r=1.5), area("blackout", secs=-1), area("blackout", secs=601),
                            area("blackout", secs=0), area("traffic_switch", secs=0), area("gridlock", secs=0),
                            area("blackout", p=(0.0, 64.0)), area("blackout", p=(0.0, math.nan, 0.0)), area("blackout", p=(3e7, 0.0, 0.0)),
                            {**area("blackout"), "target": "5"}, area("blackout", rid=-2)])
def test_a_bad_area_hack_is_dropped_and_changes_nothing(bad):
    h = host()
    assert h.reply(bad) == [] and h.dev_table == fakehost.device_table(0, fakehost.HACKS2_SCENE) and h.restores == []


# ---- daemon ------------------------------------------------------------------------------------------------------------------------------
def test_open_door_opens_the_closed_doors_but_never_a_quest_door_and_disable_cameras_disables_the_cameras():
    h = host()
    got = h.reply(daemon("open_door", "disable_cameras"))
    assert got[0] == {"t": "hackresult", "id": 24, "ok": True, "reason": ""} and daemon_ok(daemon("open_door", "disable_cameras"))
    states = {m["id"]: m for m in got[1:]}
    assert set(states) == {DEV["door"], DEV["npcdoor"], DEV["camera"]} and all(m["by"] == "script" and devstate_ok(m) for m in states.values())
    assert states[DEV["door"]]["st"] == 1 and states[DEV["camera"]]["st"] == -2 and states[DEV["camera"]]["fl"] == 20
    assert h.dev_table[DEV["sealed"]]["st"] == -2  # the quest door stays shut
    assert [m["t"] for m in h.reply(daemon("open_door", "disable_cameras", rid=25))] == ["hackresult"]  # nothing left to change


def test_reveal_enemies_pings_the_hostile_people_and_overload_and_alarm_change_nothing():
    h = host()
    assert h.reply(daemon("reveal_enemies", rid=40)) == [{"t": "hackresult", "id": 40, "ok": True, "reason": ""},
                                                       {"t": "hackspread", "id": 40, "target": HOSTILE, "why": "ping"}]
    for ids in (("overload",), ("alarm",)):
        assert h.reply(daemon(*ids, strength=1)) == [{"t": "hackresult", "id": 24, "ok": True, "reason": ""}]
    assert h.dev_table == fakehost.device_table(0, fakehost.HACKS2_SCENE)


def test_every_shipped_daemon_content_id_is_answered_ok():
    # a solved Breach sends ids from 6A's `daemon` content; a real DLL runs them all (Daemons.reds), so the fake one must not refuse any
    shipped = sorted(p.stem for p in Path("mc/src/main/resources/data/cyberblock/cyberblock/daemon").glob("*.json"))
    assert shipped
    for i, name in enumerate(shipped):
        assert result(host().reply(daemon(name, rid=i)))["ok"] is True, name


def test_an_unknown_daemon_id_fails_the_result_but_the_others_still_run():
    h = host()
    got = h.reply(daemon("open_door", "teleport_cash", "disable_cameras"))
    assert got[0] == {"t": "hackresult", "id": 24, "ok": False, "reason": "unsupported daemon"} and {m["id"] for m in got[1:]} == {DEV["door"], DEV["npcdoor"], DEV["camera"]}
    assert result(h.reply(daemon("x", rid=3)))["reason"] == "unsupported daemon"


def test_a_daemon_for_a_device_that_is_not_there_says_device_gone():
    assert result(host().reply(daemon("open_door", dev="123"))) == {"t": "hackresult", "id": 24, "ok": False, "reason": "device gone"}
    assert host().dev_table[DEV["door"]]["st"] == 0


@pytest.mark.parametrize("bad", [daemon(), daemon("a", "b", "c", "d"), daemon("a", "a"), daemon("Open"), daemon("alarm", "open_door"), daemon("x" * 33), daemon("a", strength=0),
                                 daemon("a", strength=4), daemon("a", strength=True), daemon("a", rid=-1), daemon("a", dev="0"), daemon("a", dev=5),
                                 {**daemon("a"), "list": "open_door"}, {k: v for k, v in daemon("a").items() if k != "dev"}, {k: v for k, v in daemon("a").items() if k != "id"}])
def test_a_bad_daemon_is_dropped_and_not_answered(bad):
    h = host()
    assert h.reply(bad) == [] and h.dev_table == fakehost.device_table(0, fakehost.HACKS2_SCENE)


def test_the_examples_of_the_protocol_are_answered_the_way_the_document_says():
    from test_phase6_protocol import P6_EXAMPLES
    h = host()
    sent = [m for t, m in P6_EXAMPLES if t in ("rhack", "daemon")]
    assert len(sent) == 5
    answers = {m["id"]: result(h.reply(m)) for m in sent}
    assert answers[21]["ok"] is True and h.car["braked"] is True  # veh_brake on the car
    assert answers[22]["ok"] is True  # cam_feed on the camera
    assert answers[23] == {"t": "hackresult", "id": 23, "ok": False, "reason": fakehost.OUT_OF_RANGE}  # the example's blackout is in Night City's far corner
    assert answers[24]["ok"] is True and answers[25]["ok"] is True  # the solved Breach and the failure alarm


# ---- the photo feed ----------------------------------------------------------------------------------------------------------------------
def test_feedreq_on_sends_a_frame_at_once_and_one_a_second_until_it_is_off(quick):
    h = host()
    assert not [m for m in h.frame_messages(0.0) if m["t"] == "feed"] and h.reply({"t": "feedreq", "on": True}) == []  # nothing before the request
    s = fakehost.SCRIPT_SCALE
    first = [m for m in h.frame_messages(1.0 * s) if m["t"] == "feed"]
    assert len(first) == 1 and feed_ok(first[0]) and h.feed_n == 1  # at once
    assert not [m for m in h.frame_messages(1.5 * s) if m["t"] == "feed"] and not [m for m in h.frame_messages(1.99 * s) if m["t"] == "feed"]
    second = [m for m in h.frame_messages(2.0 * s) if m["t"] == "feed"]
    assert len(second) == 1 and second[0]["px"] != first[0]["px"] and h.feed_n == 2  # the test card moves
    assert h.reply({"t": "feedreq", "on": False}) == [] and not [m for m in h.frame_messages(5 * s) if m["t"] == "feed"]
    assert h.reply({"t": "feedreq", "on": True}) == [] and [m for m in h.frame_messages(5.1 * s) if m["t"] == "feed"]  # on again: a frame at once


def test_a_feed_frame_is_128_by_128_map_colours_without_the_transparent_bytes_and_valid_feedreq_only_changes_the_state():
    for n in (1, 2, 59, 60, 1000):
        frame = fakehost.feed_message(n)
        px = base64.b64decode(frame["px"], validate=True)
        assert feed_ok(frame) and len(px) == 16384 and min(px) >= 4 and max(px) <= 239
    assert fakehost.feed_message(5)["px"] == fakehost.feed_message(5)["px"] != fakehost.feed_message(6)["px"]  # deterministic, and it moves
    h = host()
    for bad in ({"t": "feedreq", "on": 1}, {"t": "feedreq", "on": "true"}, {"t": "feedreq"}):
        h.reply(bad)
        assert not h.feed_on
    h.reply({"t": "feedreq", "on": True})
    assert h.feed_on and feedreq_ok({"t": "feedreq", "on": True})


# ---- the whole mode against a fake Minecraft -----------------------------------------------------------------------------------------------
def test_hacks2_mode_answers_everything_over_the_link_and_prints_both_sides(tmp_path, exporting, quick, capsys):
    asks = [HOST_SESSION, rhack("veh_brake", CAR, rid=1), area("blackout", (0.0, 64.0, 3.5), r=60, secs=1, rid=2), daemon("open_door", "disable_cameras", rid=3),
            daemon("nonsense_daemon", rid=4), {"t": "feedreq", "on": True}, rhack("veh_lock", "99", rid=5), {"t": "rhack", "id": 6, "hack": "veh_lock"}]
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=asks, hold=2.5, layers=frozenset(), modes=mode_set("hacks2"))
    kinds = types(got)
    results = {m["id"]: m for m in got if m["t"] == "hackresult"}
    assert {i: (m["ok"], m["reason"]) for i, m in results.items()} == {1: (True, ""), 2: (True, ""), 3: (True, ""), 4: (False, "unsupported daemon"), 5: (False, "vehicle gone")}
    assert all(hackresult_ok(m) for m in results.values()) and kinds.count("feed") >= 2 and all(feed_ok(m) for m in got if m["t"] == "feed")
    by_script = [m for m in got if m["t"] == "devstate" and m["by"] == "script"]
    assert {m["id"] for m in by_script} >= {DEV["door"], DEV["camera"], DEV["light"]} and any(m["st"] >= 0 and m["id"] == DEV["light"] for m in by_script)  # the blackout came back
    lines = printed(capsys)
    assert [m["id"] for m in lines["rhack"]] == [1, 2, 5, 6] and [m["id"] for m in lines["daemon"]] == [3, 4] and lines["feedreq"] == [{"t": "feedreq", "on": True}]
    assert sorted(m["id"] for m in lines["hackresult"]) == [1, 2, 3, 4, 5] and [m["n"] for m in lines["feed"]][:2] == [1, 2] and lines["feed"][0]["w"] == 128
    assert "devact" not in lines  # nothing asked a device


def test_hacks2_events_are_printed_only_with_the_mode(tmp_path, exporting, capsys):
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=[rhack("veh_brake", CAR), daemon("open_door"), {"t": "feedreq", "on": True}], hold=0.1, modes=mode_set("seat"))
    lines = printed(capsys)
    assert not {"rhack", "daemon", "feedreq", "hackresult", "feed"} & set(lines)
    assert fakehost.printed_events(frozenset(), frozenset({"hacks2"})) >= {"devact", "rhack", "daemon", "feedreq"}


def test_hacks2_and_devices_combine_and_a_second_host_is_shifted(tmp_path, exporting, quick):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[HOST_SESSION], hold=0.4, layers=frozenset(), modes=mode_set("devices", "hacks2"), shift=8)
    listed = {e["id"]: e["p"] for e in next(m for m in got if m["t"] == "devices")["list"]}
    assert listed == {i: d["p"] for i, d in fakehost.device_table(8, fakehost.HACKS2_SCENE).items()} and listed[DEV["netport"]][0] == 4 + 8 + 0.5


def test_hacks2_flags_pick_the_mode_and_conflicts_with_the_camera_modes_are_refused(monkeypatch):
    seen = []

    async def fake_run(*args, **kwargs):
        seen.append((args, kwargs))
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    for argv in (["--hacks2"], ["--hacks2", "--devices", "--shift", "8", "--hold", "3"], ["--hacks2", "--layer2", "--shift", "-4"], ["--hacks2", "--figures"]):
        monkeypatch.setattr("sys.argv", ["fakehost", *argv])
        assert fakehost.main() == 0
    assert [a[9] for a, _ in seen] == [frozenset({"hacks2"}), frozenset({"hacks2", "devices"}), frozenset({"hacks2"}), frozenset({"hacks2", "figures"})]
    assert [k for _, k in seen] == [{}, {"shift": 8}, {"shift": -4}, {}]
    for argv in (["--hacks2", "--view"], ["--hacks2", "--stairs"], ["--hacks2", "--seat"], ["--hacks2", "--layer4"], ["--hacks2", "--env-hours", "3"],
                 ["--hacks2", "--shift", "1001"]):
        monkeypatch.setattr("sys.argv", ["fakehost", *argv])
        with pytest.raises(SystemExit) as e:
            fakehost.main()
        assert e.value.code == 2, argv


def test_the_module_docstring_describes_the_mode():
    doc = " ".join(fakehost.__doc__.split())
    for phrase in ("--hacks2", "Net Port", "`cyberblock.hack.out_of_range`", "`unsupported daemon`", "`feedreq on:true`", "50 m from V", "`vehicle gone`"):
        assert phrase in doc, phrase
