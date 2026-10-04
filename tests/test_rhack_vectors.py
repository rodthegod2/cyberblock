"""shared/rhack-vectors.json: the DOWN `rhack` of each remote hack exactly as the server builds it (mc ActWireTest checks ActWire.rhack against the same
lines). Here every line must obey shared/protocol.md (test_phase6_protocol.rhack_ok) and the fake DLL (tools/fakehost.py --hacks2) must answer it with one
`hackresult` ok, end to end: the vehicle, camera and area forms the Vehicle Panel, the Traffic Tool and the Net Map send."""
import json
from pathlib import Path

import pytest

from test_phase6_protocol import AREA_HACKS, REMOTE_HACKS, VEHICLE_HACKS, hackresult_ok, rhack_ok
from tools import fakehost

VECTORS = json.loads((Path(__file__).resolve().parents[1] / "shared" / "rhack-vectors.json").read_text(encoding="utf-8"))
CASES = VECTORS["cases"]


def test_the_vectors_cover_the_eleven_hacks_once():
    assert sorted(c["hack"] for c in CASES) == sorted(REMOTE_HACKS) and len(CASES) == 11
    assert len({c["id"] for c in CASES}) == 11


@pytest.mark.parametrize("case", CASES, ids=[c["hack"] for c in CASES])
def test_every_server_rhack_obeys_the_protocol(case):
    down = case["down"]
    assert rhack_ok(down), down
    assert down["hack"] == case["hack"] and down["id"] == case["id"] and down["target"] == case["target"]
    if case["hack"] in AREA_HACKS:
        assert down["p"] == case["p"] and down["target"] is None
    if case["hack"] in VEHICLE_HACKS:
        assert down["secs"] == 30  # "the server sends 30"


@pytest.mark.parametrize("case", CASES, ids=[c["hack"] for c in CASES])
def test_the_fake_dll_answers_every_server_rhack_ok(case):
    host = fakehost.Cyberpunk(frozenset(), frozenset({"hacks2"}))
    answers = host.reply(case["down"])
    assert answers and answers[0]["t"] == "hackresult" and hackresult_ok(answers[0]), answers
    assert answers[0] == {"t": "hackresult", "id": case["id"], "ok": True, "reason": ""}


def test_one_vehicle_and_one_area_hack_change_the_fake_scene():
    host = fakehost.Cyberpunk(frozenset(), frozenset({"hacks2"}))
    brake = next(c["down"] for c in CASES if c["hack"] == "veh_brake")
    blackout = next(c["down"] for c in CASES if c["hack"] == "blackout")
    assert host.reply(brake)[0]["ok"] and host.car["braked"] is True
    answers = host.reply(blackout)
    assert answers[0]["ok"] and any(m["t"] == "devstate" for m in answers[1:]), answers
