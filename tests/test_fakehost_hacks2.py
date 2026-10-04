"""tools/fakehost.py `--hacks2` (megaplan task 6-P1): the DLL's side of the Phase 6 remote hacks, the Breach daemons and the photo feed, against the contract of
shared/protocol.md "Phase 6". The fake host is the stand-in the harness (tools/mpcheck.py `breach`) and the dev client use before the real DLL speaks."""
import base64
import json
import math
import time
from pathlib import Path

import pytest

from test_fakehost import (HOST_SESSION, exporting, mode_set, printed, quick, run_against_fake_minecraft, types)  # noqa: F401  (exporting and quick are fixtures)
from test_phase6_protocol import daemon_ok, feed_ok, feedreq_ok, hackresult_ok, rhack_as_the_server_sends_it, rhack_ok
from test_protocol_md import devstate_ok, devices_ok, uint64_string
from tools import fakehost

DEV = fakehost.device_ids(fakehost.HACKS2_SCENE)
CAR = fakehost.NPC_IDS[2]
HOSTILE = fakehost.NPC_IDS[0]


def host(**kw):
    return fakehost.Cyberpunk(frozenset(), frozenset({"hacks2"}), **kw)


def rhack(hack, target="", rid=21, **extra):
    """A DOWN `rhack` in the server's shape (ActWire.rhack): no `secs` on a vehicle hack, `target` "" for an area hack."""
    return {"t": "rhack", "id": rid, "target": target, "hack": hack, **extra}


def area(hack, p=(0.0, 64.0, 0.0), r=24.0, secs=60, rid=23):
    return rhack(hack, "", rid, p=list(p), r=r, secs=secs)


SHARED = [json.loads(e["json"]) for e in json.loads(Path("shared/phase6-messages.json").read_text(encoding="utf-8"))["down_rhack"]]


def daemon(*ids, dev=None, strength=2, rid=24):
    return {"t": "daemon", "id": rid, "dev": dev or DEV["netport"], "list": list(ids), "strength": strength}


def result(answers):
    """The one `hackresult` of an answer (the reveals come before it, the device changes after it)."""
    results = [m for m in answers if m["t"] == "hackresult"]
    assert len(results) == 1 and hackresult_ok(results[0]), answers
    return results[0]


def ok(rid, kind=""):
    return {"t": "hackresult", "id": rid, "ok": True, "reason": "", "upload": 0.0, **({"kind": kind} if kind else {})}


def refused(rid, reason):
    return {"t": "hackresult", "id": rid, "ok": False, "reason": reason}


# ---- the scene -------------------------------------------------------------------------------------------------------------------------
def test_the_hacks2_scene_is_the_devices_scene_plus_the_net_port_and_devices_mode_is_unchanged():
    assert fakehost.HACKS2_SCENE[:-2] == fakehost.DEVICE_SCENE and [d[0] for d in fakehost.HACKS2_SCENE[-2:]] == ["netport", "traffic"]
    assert len(fakehost.device_table()) == 7 and len(fakehost.device_table(0, fakehost.HACKS2_SCENE)) == 9 and set(fakehost.device_ids()) < set(DEV)
    assert DEV["traffic"] == "11529215046068469771" and fakehost.device_table(0, fakehost.HACKS2_SCENE)[DEV["traffic"]]["kind"] == "traffic"
    assert DEV["netport"] == "11529215046068469770" and uint64_string(DEV["netport"]) and fakehost.device_cell("netport", 0, fakehost.HACKS2_SCENE) == (4, 64, 3)
    table = fakehost.device_table(0, fakehost.HACKS2_SCENE)
    assert table[DEV["netport"]]["kind"] == "terminal" and table[DEV["netport"]]["cls"] == "Terminal" and devices_ok(fakehost.devices_message(table))
    street = fakehost.replica_cells()
    assert all(fakehost.device_cell(k, 0, fakehost.HACKS2_SCENE) not in street for k in ("netport", "traffic"))  # above the street, like the other devices
    assert host().dev_table == table and fakehost.Cyberpunk(frozenset(), frozenset({"devices"})).dev_table == fakehost.device_table()


def test_hacks2_stands_on_the_street_like_devices_and_still_answers_devact():
    h = host()
    assert h.devices and h.street and h.cam(1, 0.0, {})["pl"] == [-6.5, 64.0, 0.5] and h.script() == []
    assert h.reply({"t": "devact", "id": 7, "dev": DEV["door"], "act": "open"})[0] == {"t": "devresult", "id": 7, "ok": True, "reason": ""}
    got = host(shift=8).reply(HOST_SESSION)
    assert [m["t"] for m in got] == ["clear", *["ground"] * 6, "devices", "npcs"] and len(got[-2]["list"]) == 9


def test_only_hacks2_mode_answers_the_phase_6_requests():
    for modes in ({"devices"}, {"replica"}, {"figures"}):
        h = fakehost.Cyberpunk(frozenset(), frozenset(modes))
        assert h.reply(rhack("veh_brake", CAR)) == [] and h.reply(daemon("open_door")) == [] and h.reply({"t": "feedreq", "on": True}) == [] and not h.feed_on


# ---- rhack: every shape the real server sends --------------------------------------------------------------------------------------------
@pytest.mark.parametrize("m", SHARED, ids=[m["hack"] for m in SHARED])
def test_every_rhack_the_server_builds_gets_exactly_one_hackresult(m):
    """shared/phase6-messages.json: the exact strings ActWire.rhack produces for the eleven hacks (ActWireTest checks the builder against them)."""
    assert rhack_ok(m) and rhack_as_the_server_sends_it(m) and fakehost.rhack_valid(m)
    answers = host().reply(m)
    assert [a["id"] for a in answers if a["t"] == "hackresult"] == [m["id"]]
    assert result(answers)["ok"] is True  # this scene has what each one needs: the car, the camera, a traffic light, devices, figures


def test_the_fake_dll_takes_every_target_form_and_extra_the_real_one_takes():
    for target in ("", None, "0"):
        assert result(host().reply({"t": "rhack", "id": 9, "target": target, "hack": "blackout"}))["ok"]
    assert result(host().reply({"t": "rhack", "id": 9, "hack": "gridlock", "p": [-4.5, 64.0, -4.5]}))["ok"]  # no target, no r, no secs
    for extra in ({"r": 0}, {"r": 150}, {"r": 24.5}, {"secs": 0}, {"secs": 600}, {"district": ""}, {"district": "Watson"}):
        assert result(host().reply({**area("ping_area", (2.5, 64.0, 2.5), r=24.0, secs=10), **extra}))["ok"], extra
    assert result(host().reply(rhack("veh_lock", CAR, secs=30)))["ok"]  # a `secs` the scripts do not read is still taken


@pytest.mark.parametrize("hack, field, value, secs", [("veh_lock", "locked", True, 120), ("veh_engine_off", "engine", False, 30), ("veh_brake", "braked", True, 5),
                                                     ("veh_jam", "jammed", True, 3)])
def test_every_timed_vehicle_hack_changes_the_car_for_the_scripts_own_time(hack, field, value, secs):
    h = host()
    before = dict(h.car)
    start = time.perf_counter()
    assert h.reply(rhack(hack, CAR)) == [ok(21)]
    assert h.car == {**before, field: value}
    h.due(start + secs * fakehost.SCRIPT_SCALE - 0.5)
    assert h.car[field] == value  # not yet
    h.due(time.perf_counter() + secs * fakehost.SCRIPT_SCALE + 1)
    assert h.car == before and h.car_due == {} and h.car_before == {}


def test_veh_start_is_not_undone_and_ends_an_engine_hold():
    h = host()
    assert result(h.reply(rhack("veh_engine_off", CAR)))["ok"] and h.car["engine"] is False and "engine" in h.car_due
    assert result(h.reply(rhack("veh_start", CAR, rid=22)))["ok"] and h.car["engine"] is True and "engine" not in h.car_due
    h.due(time.perf_counter() + 200 * fakehost.SCRIPT_SCALE)
    assert h.car["engine"] is True


def test_a_second_hack_restarts_the_timer_but_keeps_the_first_state():
    h = host()
    start = time.perf_counter()
    h.reply(rhack("veh_lock", CAR))
    h.car["locked"] = "changed by the first hack"  # what the restore must not take as the state before
    h.reply(rhack("veh_lock", CAR, rid=22))
    h.due(start + 120 * fakehost.SCRIPT_SCALE + 1)
    assert h.car["locked"] is False


def test_a_target_that_is_not_streamed_is_target_gone_and_a_wrong_kind_is_not_hackable():
    h = host()
    for target in ("99999", "123"):  # in no list: the server reads it as out of range
        assert result(h.reply(rhack("veh_lock", target))) == refused(21, "target gone")
        assert result(h.reply(rhack("cam_feed", target))) == refused(21, "target gone")
    for target in (HOSTILE, DEV["camera"]):  # a person (whose car is not modelled), a device
        assert result(h.reply(rhack("veh_lock", target))) == refused(21, "not hackable (not a car)")
    assert result(h.reply(rhack("cam_feed", DEV["light"]))) == refused(21, "not hackable (not a camera)")
    assert result(h.reply(rhack("cam_feed", DEV["camera"]))) == ok(21, "camera")
    h.dev_table[DEV["camera"]]["st"] = 0
    assert result(h.reply(rhack("cam_feed", DEV["camera"]))) == refused(21, "camera off")
    assert h.car == {"locked": False, "engine": True, "braked": False, "jammed": False}


def test_the_range_is_every_streamed_target_whatever_vs_distance():
    far = host(shift=400)  # V stands 400 m east of the figures and, once the devices are put back at the origin, of them too
    far.dev_table = fakehost.device_table(0, fakehost.HACKS2_SCENE)
    for m in (rhack("veh_start", CAR), rhack("cam_feed", DEV["camera"]), area("outline", (2.5, 64.0, 2.5)), area("gridlock", (-4.5, 64.0, -4.5))):
        assert result(far.reply(m))["ok"] is True, m
    assert not hasattr(fakehost, "HACK_RANGE_M")  # the 50 m fallback is in-game evidence only (RemoteHacks.reds, RangeRule)


def test_a_hack_name_outside_the_eleven_is_unsupported_and_a_quickhack_name_no_such_hack():
    h = host()
    for name in ("explode", "", "VEH_LOCK"):
        assert result(h.reply(rhack(name, CAR))) == refused(21, "unsupported")
        assert result(h.reply(rhack(name, ""))) == refused(21, "unsupported")  # an unknown name is answered whatever its target
    assert result(h.reply(rhack("short_circuit", HOSTILE))) == refused(21, "no such hack")
    assert h.reply(rhack("short_circuit", "")) == []  # a quickhack needs a target: the DLL drops it
    assert set(fakehost.REMOTE_HACKS) == set(fakehost.VEHICLE_HACKS) | set(fakehost.AREA_HACKS) | {"cam_feed"} and len(fakehost.REMOTE_HACKS) == 11


@pytest.mark.parametrize("bad", [rhack("veh_lock", CAR, rid=-1), rhack("veh_lock", CAR, rid=True), rhack("veh_lock", CAR, rid="21"), rhack("veh_lock", CAR, rid=2**31),
                                 rhack("veh_lock", 5), rhack("veh_lock", "-3"), rhack("veh_lock", "x1"), rhack("veh_lock", "18446744073709551616"),
                                 rhack("veh_lock", ""), rhack("veh_lock", None), rhack("cam_feed", "0"), {"t": "rhack", "id": 21, "target": CAR},
                                 {"t": "rhack", "target": CAR, "hack": "veh_lock"}, rhack(None, CAR), rhack(7, CAR),
                                 rhack("veh_jam", CAR, secs=601), rhack("veh_jam", CAR, secs=1.5), rhack("veh_jam", CAR, secs=True), rhack("veh_jam", CAR, secs=-1),
                                 area("blackout", r=-1), area("blackout", r=150.5), area("blackout", r=True), area("blackout", p=(0.0, 64.0)),
                                 area("blackout", p=(0.0, math.nan, 0.0)), area("blackout", p=(3e7, 0.0, 0.0)), {**area("blackout"), "district": 5},
                                 {**area("blackout"), "target": "x"}, area("blackout", rid=-2)])
def test_a_rhack_the_dll_would_reject_is_dropped_and_changes_nothing(bad):
    h = host()
    assert h.reply(bad) == [] and h.dev_table == fakehost.device_table(0, fakehost.HACKS2_SCENE) and h.restores == []


# ---- rhack: area hacks -------------------------------------------------------------------------------------------------------------------
def test_blackout_switches_the_lights_cameras_and_machines_around_v_off_and_gives_them_back():
    h = host()
    answers = h.reply({"t": "rhack", "id": 29, "target": "", "hack": "blackout", "district": "Watson", "secs": 3})  # as the server sends it: no p, no r
    assert answers[0] == ok(29, "device")
    states = {m["id"]: m for m in answers[1:] if m["t"] == "devstate"}
    assert all(devstate_ok(m) and m["by"] == "script" and m["st"] == -1 and not m["fl"] & fakehost.FL_POWERED for m in states.values())
    assert {h.dev_table[i]["kind"] for i in states} == {"light", "camera", "vending", "traffic"}  # never doors, terminals or lifts
    assert len(h.restores) == len(states) == 4
    assert h.due(time.perf_counter()) == [] and len(h.restores) == 4  # not yet: 3 s is clamped to the scripts' 30 s minimum
    assert h.due(time.perf_counter() + 3 * fakehost.SCRIPT_SCALE + 1) == []
    back = h.due(time.perf_counter() + 30 * fakehost.SCRIPT_SCALE + 1)
    assert {m["id"] for m in back} == set(states) and all(m["by"] == "script" and m["st"] == 1 and m["fl"] & fakehost.FL_POWERED for m in back)
    assert h.dev_table == fakehost.device_table(0, fakehost.HACKS2_SCENE) and h.restores == [] and h.blacked == {}


def test_blackout_keeps_to_its_radius_its_default_time_and_a_new_one_ends_the_last():
    h = host()
    light = h.dev_table[DEV["light"]]["p"]
    near = h.reply(area("blackout", (light[0] + 1.5, light[1], light[2]), r=1, secs=0))
    assert [m["t"] for m in near] == ["hackresult"] and h.restores == []  # the light is 1.5 m away: outside r 1
    small = h.reply(area("blackout", tuple(light), r=1, secs=0))
    assert [m["t"] for m in small] == ["hackresult", "devstate"] and small[1]["id"] == DEV["light"] and len(h.restores) == 1
    assert h.restores[0][0] - time.perf_counter() == pytest.approx(fakehost.BLACKOUT_SECS * fakehost.SCRIPT_SCALE, abs=1)  # secs 0: the scripts' 120 s
    again = h.reply(area("blackout", tuple(light), r=1, secs=60))
    assert [(m["t"], m.get("st")) for m in again] == [("hackresult", None), ("devstate", 1), ("devstate", -1)]  # the last one ends, the new one starts
    assert len(h.restores) == 1 and h.dev_table[DEV["light"]]["st"] == -1


@pytest.mark.parametrize("state", ["none", "guest", "refused"])
def test_a_session_that_is_not_host_puts_every_pending_blackout_and_car_effect_back_at_once(state):
    h = host()
    h.reply(HOST_SESSION)
    h.reply(area("blackout", (0.0, 64.0, 3.5), r=60, secs=600))
    h.reply(rhack("veh_jam", CAR))
    h.reply(rhack("veh_lock", CAR, rid=22))
    assert h.restores and h.car["jammed"] and h.car["locked"]
    h.reply({"t": "session", "state": state, "reason": "test", "perms": {}})
    assert h.dev_table == fakehost.device_table(0, fakehost.HACKS2_SCENE) and h.restores == [] and h.blacked == {}
    assert h.car == {"locked": False, "engine": True, "braked": False, "jammed": False} and h.car_due == {}


def test_outline_reveals_the_people_and_cars_and_ping_area_the_hostile_people_before_the_result():
    h = host()
    got = h.reply(area("outline", (0.0, 64.0, 0.0), secs=20, rid=31))
    assert [m["t"] for m in got] == ["hackspread"] * 3 + ["hackresult"] and got[-1] == ok(31)  # the reveals first, as the scripts send them
    assert {m["target"] for m in got[:3]} == set(fakehost.NPC_IDS) and all(m["why"] == "ping" and m["id"] == 31 for m in got[:3])
    got = h.reply(area("ping_area", (0.0, 64.0, 0.0), r=32.0, secs=10, rid=32))
    assert got == [{"t": "hackspread", "id": 32, "target": HOSTILE, "why": "ping"}, ok(32)]  # only the hostile person; no car
    assert [m["t"] for m in h.reply(area("outline", (200.0, 64.0, 0.0)))] == ["hackresult"]  # nobody within 25 m there
    assert fakehost.MAX_SPREADS == 16


def test_traffic_switch_needs_a_light_and_gridlock_holds_the_cars_near_the_point():
    h = host()
    assert h.reply({"t": "rhack", "id": 27, "target": "", "hack": "traffic_switch", "district": "Watson"}) == [ok(27, "traffic")]  # the light is 3.6 m from V
    assert h.reply(rhack("traffic_switch", DEV["camera"])) == [ok(21, "traffic")]  # a target: the light nearest it
    far = host()
    del far.dev_table[DEV["traffic"]]  # a street without a traffic light
    assert far.reply(rhack("traffic_switch", "")) == [refused(21, "no traffic light near")]
    start = time.perf_counter()
    assert h.reply(area("gridlock", (-4.5, 64.0, -4.5))) == [ok(23, "traffic")] and h.car["braked"] is True
    h.due(start + fakehost.GRIDLOCK_SECS * fakehost.SCRIPT_SCALE + 1)
    assert h.car["braked"] is False
    assert far.reply(area("gridlock", (500.0, 64.0, 0.0))) == [refused(23, "no traffic near")]


# ---- daemon ------------------------------------------------------------------------------------------------------------------------------
def test_open_door_opens_the_closed_doors_but_never_a_quest_door_and_disable_cameras_disables_the_cameras():
    h = host()
    got = h.reply(daemon("open_door", "disable_cameras"))
    assert got[0] == ok(24, "device") and daemon_ok(daemon("open_door", "disable_cameras"))
    states = {m["id"]: m for m in got[1:]}
    assert set(states) == {DEV["door"], DEV["npcdoor"], DEV["camera"]} and all(m["by"] == "script" and devstate_ok(m) for m in states.values())
    assert states[DEV["door"]]["st"] == 1 and states[DEV["camera"]]["st"] == -2 and states[DEV["camera"]]["fl"] == 20
    assert h.dev_table[DEV["sealed"]]["st"] == -2  # the quest door stays shut
    assert [m["t"] for m in h.reply(daemon("open_door", "disable_cameras", rid=25))] == ["hackresult"]  # nothing left to change


def test_reveal_enemies_pings_the_hostile_people_and_overload_and_alarm_change_nothing():
    h = host()
    assert h.reply(daemon("reveal_enemies", rid=40)) == [{"t": "hackspread", "id": 40, "target": HOSTILE, "why": "ping"}, ok(40, "device")]  # the reveal first
    for ids in (("overload",), ("alarm",)):
        assert h.reply(daemon(*ids, strength=1)) == [ok(24, "device")]
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
    assert len(sent) == 7
    answers = {m["id"]: result(h.reply(m)) for m in sent}
    assert answers[23] == ok(23) and h.car["braked"] is True  # veh_brake on the car
    assert answers[26] == ok(26, "camera") and answers[27] == ok(27, "traffic") and answers[29] == ok(29, "device")  # the camera, the light, the blackout
    assert answers[31] == ok(31)  # ping_area in Night City's far corner: every streamed target, nobody there to reveal
    assert answers[32] == ok(32, "device") and answers[33] == ok(33, "device")  # the solved Breach and the failure alarm


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
    assert {i: (m["ok"], m["reason"]) for i, m in results.items()} == {1: (True, ""), 2: (True, ""), 3: (True, ""), 4: (False, "unsupported daemon"), 5: (False, "target gone")}
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
    for phrase in ("--hacks2", "Net Port", "Every `rhack` the real DLL takes", "every streamed target", "`target gone`", "`unsupported daemon`", "`feedreq on:true`",
                   "the lock for 120 s", "`veh_start` is not undone", "a traffic light", "`no such hack`"):
        assert phrase in doc, phrase
