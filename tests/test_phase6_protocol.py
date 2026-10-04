"""Phase 6 protocol (megaplan task 6-P1): the rows, examples and validators of `rhack`, `daemon`, `feedreq`, `feed`, the extended `hackresult`, the six
`cyberblock:act` payloads (`rhack`, `breach`, `feed`, `feedmap`, `netmap`, `comms`), the ten `cyberblock:ui` payloads (`ract`, `netmap`, `feed`, `vpanel`,
`camfeed`, `outline`, `netping`, `comms`, `breach`, `scanowner`) and the 18th cyberware id, `firewall`. Plan 2026-10-04 megaplan sections 1.8 and 1.9
(rows 6), 1.17 and 10, with the Phase 0 amendment (fallback (h): only streamed targets). The shapes are the merged code's (ActWire, BreachWire, rhack.cpp,
feed.cpp, RemoteHacks.reds); shared/phase6-messages.json and shared/rhack-vectors.json hold real messages that the Java and C++ tests check against the
builders and the DLL, and that these validators must accept."""
import base64
import binascii
import copy
import json
import re
from pathlib import Path

import pytest

from test_protocol_md import (CYBERWARE, CYBERWARE_IDS, TEXT, class_rows, documented, dev_id_ok, examples, MP_CP, MP_MC, MP_PAYLOADS, MP_SECURITY,
                              num, rate_rows, rows_by_type, section, vec3, whole)

P6_CP_TO_MC, P6_MC_TO_CP = "Phase 6: Cyberpunk → Minecraft", "Phase 6: Minecraft → Cyberpunk"
P6_CP_EXAMPLES, P6_MC_EXAMPLES = "Phase 6 examples, Cyberpunk to Minecraft:", "Phase 6 examples, Minecraft to Cyberpunk:"
P6_SECTION, P6_PAYLOADS = "Phase 6 semantics and limits", "Phase 6: payloads (client ↔ server)"
SHARED = json.loads((Path(__file__).resolve().parent.parent / "shared" / "phase6-messages.json").read_text(encoding="utf-8"))


def names6(label: str) -> list[str]:
    """Like `names`, but the Phase 6 lists also hold upper-case hex codes and digits (`1C`, `55`)."""
    line = re.search(rf"^- \*\*{re.escape(label)}\*\*.*$", TEXT, re.M).group(0)
    return re.findall(r"`([A-Za-z0-9_]+)`", line.split("—", 1)[1])


REMOTE_HACKS, AREA_HACKS = names6("Remote hack names"), names6("Area hacks")
POINT_HACKS, DISTRICT_HACKS, HACK_KINDS = names6("Point hacks"), names6("District hacks"), names6("Remote hack kinds")
ACT_TYPES, UI_TYPES = names6("Phase 6 act types"), names6("Phase 6 ui types")
BREACH_CODES, BREACH_STATES, BREACH_OPS, RESERVED = names6("Breach codes"), names6("Breach states"), names6("Breach ops"), names6("Reserved daemon id")
VEHICLE_HACKS = ["veh_lock", "veh_engine_off", "veh_brake", "veh_start", "veh_jam"]
ACT_EXAMPLES = {t: f"Phase 6 examples, client to server (`cyberblock:act`, `t:\"{t}\"`):" for t in ACT_TYPES}
UI_EXAMPLES = {t: f"Phase 6 examples, server to client (`cyberblock:ui`, `t:\"{t}\"`):" for t in UI_TYPES}
DAEMON_ID = re.compile(r"[a-z0-9_]{1,32}")
DLL_DAEMON_ID = re.compile(r"[a-z0-9_.:/-]{1,64}")
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
POS_LIMIT, Y_LIMIT, FEED_BYTES, TEXT_CAP, R_MAX, SECS_MAX = 30_000_000, 20_000, 128 * 128, 64, 150, 600
NO_TARGET = (None, "", "0")
TIMED_AREA_HACKS = ("traffic_switch", "gridlock", "blackout")  # they change Cyberpunk state: `secs` 1..600 from the server


# ---- validators: the DLL's wire -------------------------------------------------------------------------------------------------------
def request_id_ok(x) -> bool:
    return whole(x)


def rhack_ok(m) -> bool:
    """DOWN `rhack` as the DLL takes it (rhack.cpp): a target for the vehicle hacks and `cam_feed`, none (`""`, null, absent or "0") allowed for the area
    hacks; `district`, `p`, `r` (any number 0..150) and `secs` (0..600) optional, each with its rule."""
    if not (isinstance(m, dict) and {"t", "id", "hack"} <= set(m) <= {"t", "id", "target", "hack", "district", "p", "r", "secs"} and request_id_ok(m["id"])
            and isinstance(m["hack"], str)):
        return False
    target = m.get("target")
    if target not in NO_TARGET and not dev_id_ok(target):
        return False
    return ((target not in NO_TARGET or m["hack"] in AREA_HACKS) and ("p" not in m or vec3(m["p"])) and ("r" not in m or num(m["r"], 0, R_MAX))
            and ("secs" not in m or whole(m["secs"], 0, SECS_MAX)) and ("district" not in m or isinstance(m["district"], str)))


def rhack_as_the_server_sends_it(m) -> bool:
    """The stricter shape the server builds (ActWire.rhack, shared/rhack-vectors.json): a vehicle hack a car's id and `secs` 30, `cam_feed` a camera's id
    and nothing else, an area hack `target:null` and always whole `p`, `r` and `secs` (1..600 for the timed ones, a blackout 60..300, 0..600 for
    `outline` and `ping_area`)."""
    base = {"t", "id", "target", "hack"}
    if not (rhack_ok(m) and set(m) >= base and m["hack"] in REMOTE_HACKS):
        return False
    hack = m["hack"]
    if hack in AREA_HACKS:
        low, high = (60, 300) if hack == "blackout" else (1, SECS_MAX) if hack in TIMED_AREA_HACKS else (0, SECS_MAX)
        return set(m) == base | {"p", "r", "secs"} and m["target"] is None and vec3(m["p"]) and whole(m["r"], 1, R_MAX) and whole(m["secs"], low, high)
    if hack in VEHICLE_HACKS:
        return set(m) == base | {"secs"} and dev_id_ok(m["target"]) and m["secs"] == 30 and type(m["secs"]) is int
    return set(m) == base and dev_id_ok(m["target"])


def daemon_ok(m) -> bool:
    """`daemon` as the server sends it: 1 to 3 distinct content ids, the reserved `alarm` alone."""
    lst = m.get("list")
    return (set(m) == {"t", "id", "dev", "list", "strength"} and request_id_ok(m["id"]) and dev_id_ok(m["dev"]) and isinstance(lst, list) and 1 <= len(lst) <= 3
            and len(set(map(str, lst))) == len(lst) and all(isinstance(d, str) and DAEMON_ID.fullmatch(d) for d in lst) and whole(m["strength"], 1, 3)
            and (RESERVED[0] not in lst or lst == RESERVED))


def feedreq_ok(m) -> bool:
    return set(m) == {"t", "on"} and isinstance(m["on"], bool)


def px_ok(px) -> bool:
    try:
        return isinstance(px, str) and len(base64.b64decode(px, validate=True)) == FEED_BYTES
    except (binascii.Error, ValueError):
        return False


def feed_ok(m) -> bool:
    """A frame: 128 x 128 map colours (the only `feed` shape; a DLL whose copy fell back sends none)."""
    return set(m) == {"t", "w", "h", "px"} and m["w"] == 128 and m["h"] == 128 and type(m["w"]) is int and type(m["h"]) is int and px_ok(m["px"])


def hackresult_ok(m) -> bool:
    """The answer to `rhack` and `daemon` as the DLL builds it (world::make_hackresult): `id`, `ok`, `reason` (at most 64 characters, `""` when ok); an ok
    one also `upload` 0 and maybe its `kind`."""
    if not (isinstance(m, dict) and {"t", "id", "ok", "reason"} <= set(m) and request_id_ok(m["id"]) and isinstance(m["ok"], bool)
            and isinstance(m["reason"], str) and len(m["reason"]) <= 64 and (m["reason"] == "") == m["ok"]):
        return False
    if not m["ok"]:
        return set(m) == {"t", "id", "ok", "reason"}
    return {"upload"} <= set(m) - {"t", "id", "ok", "reason"} <= {"upload", "kind"} and m["upload"] == 0 and m.get("kind", "traffic") in HACK_KINDS


P6_VALIDATORS = {"hackresult": hackresult_ok, "feed": feed_ok, "rhack": rhack_ok, "daemon": daemon_ok, "feedreq": feedreq_ok}


# ---- validators: the client <-> server payloads ----------------------------------------------------------------------------------------
def uuid_ok(s) -> bool:
    return isinstance(s, str) and UUID.fullmatch(s) is not None


def act_pos_ok(p) -> bool:
    return (isinstance(p, list) and len(p) == 3 and all(num(c, -1e300, 1e300) for c in p) and abs(p[0]) < POS_LIMIT and abs(p[1]) < Y_LIMIT
            and abs(p[2]) < POS_LIMIT)


def block_pos_ok(p) -> bool:
    return isinstance(p, list) and len(p) == 3 and all(whole(c, -POS_LIMIT, POS_LIMIT) for c in p)


def act_rhack_ok(m) -> bool:
    """`cyberblock:act` `t:"rhack"` (ActWire.parse): a vehicle hack names a car figure's entity id, `cam_feed` the owner and the camera, the district hacks
    the owner (and a blackout's 60..300 s), the point hacks only the point."""
    hack = m.get("hack")
    if hack in VEHICLE_HACKS:
        return set(m) == {"hack", "eid"} and whole(m["eid"])
    if hack == "cam_feed":
        return set(m) == {"hack", "host", "dev"} and uuid_ok(m["host"]) and dev_id_ok(m["dev"])
    if hack == "traffic_switch":
        return set(m) == {"hack", "host"} and uuid_ok(m["host"])
    if hack == "blackout":
        return {"hack", "host"} <= set(m) <= {"hack", "host", "secs"} and uuid_ok(m["host"]) and ("secs" not in m or whole(m["secs"], 60, 300))
    return hack in POINT_HACKS and set(m) == {"hack", "p"} and act_pos_ok(m["p"])


def act_breach_ok(m) -> bool:
    if m.get("op") == "pick":
        return set(m) == {"op", "id", "cell"} and whole(m["id"]) and whole(m["cell"], 0, 48)
    return m.get("op") == "abort" and set(m) == {"op", "id"} and whole(m["id"])


def act_feed_ok(m) -> bool:
    return set(m) == {"host", "on"} and uuid_ok(m["host"]) and isinstance(m["on"], bool)


def act_feedmap_ok(m) -> bool:
    return set(m) == {"host"} and uuid_ok(m["host"])


def act_netmap_ok(m) -> bool:
    return m == {}


def text_ok(s) -> bool:
    return isinstance(s, str) and len(s) <= TEXT_CAP


def act_comms_ok(m) -> bool:
    return ({"to"} <= set(m) <= {"to", "text", "p"} and uuid_ok(m["to"]) and text_ok(m.get("text", "")) and (m.get("p") is None or act_pos_ok(m["p"]))
            and not (m.get("text", "") == "" and m.get("p") is None))


def seq_ok(s, buffer) -> bool:
    return (isinstance(s, dict) and set(s) == {"id", "codes", "ok"} and isinstance(s["id"], str) and DAEMON_ID.fullmatch(s["id"]) is not None
            and isinstance(s["codes"], list) and 2 <= len(s["codes"]) <= buffer and all(c in BREACH_CODES for c in s["codes"]) and isinstance(s["ok"], bool))


def ui_breach_ok(m) -> bool:
    keys = {"id", "dev", "size", "grid", "buffer", "picks", "seqs", "total", "left", "state", "strength"}
    no_device = m.get("dev") == "0"  # a practice on a lone Net Port, Phase 8's short breach on a player
    if set(m) != keys or not ((no_device or dev_id_ok(m["dev"])) and whole(m["id"]) and whole(m["size"], 4 if no_device else 5, 7) and whole(m["buffer"], 4, 8)):
        return False
    size, buffer = m["size"], m["buffer"]
    return (isinstance(m["grid"], list) and len(m["grid"]) == size * size and all(c in BREACH_CODES for c in m["grid"])
            and isinstance(m["picks"], list) and len(m["picks"]) <= buffer and all(whole(c, 0, size * size - 1) for c in m["picks"])
            and len(set(m["picks"])) == len(m["picks"]) and isinstance(m["seqs"], list) and 1 <= len(m["seqs"]) <= 3 and all(seq_ok(s, buffer) for s in m["seqs"])
            and whole(m["total"], 1, 300) and num(m["left"], 0, m["total"]) and m["state"] in BREACH_STATES and whole(m["strength"], 0, 3))


def ui_ract_ok(m) -> bool:
    return set(m) == {"hack", "ok", "reason"} and m["hack"] in REMOTE_HACKS and isinstance(m["ok"], bool) and isinstance(m["reason"], str) and (m["reason"] == "") == m["ok"]


def ui_netmap_ok(m) -> bool:
    host_keys, cam_keys = {"id", "name", "p", "district", "active", "blackout"}, {"h", "id", "p", "yaw"}
    return (set(m) == {"hosts", "cams", "range"} and isinstance(m["hosts"], list) and isinstance(m["cams"], list) and len(m["cams"]) <= 512 and m["range"] in (0, 50)
            and all(isinstance(h, dict) and set(h) == host_keys and uuid_ok(h["id"]) and isinstance(h["name"], str) and vec3(h["p"]) and isinstance(h["district"], str)
                    and isinstance(h["active"], bool) and whole(h["blackout"]) for h in m["hosts"])
            and all(isinstance(c, dict) and set(c) == cam_keys and uuid_ok(c["h"]) and dev_id_ok(c["id"]) and vec3(c["p"]) and num(c["yaw"], -360, 360) for c in m["cams"]))


def ui_feed_ok(m) -> bool:
    return set(m) == {"host", "px"} and uuid_ok(m["host"]) and px_ok(m["px"])


def ui_vpanel_ok(m) -> bool:
    return set(m) == {"eid", "name"} and whole(m["eid"]) and isinstance(m["name"], str)


def ui_camfeed_ok(m) -> bool:
    return set(m) == {"p", "yaw", "name"} and vec3(m["p"]) and num(m["yaw"], -360, 360) and isinstance(m["name"], str)


def ui_outline_ok(m) -> bool:
    return set(m) == {"ids", "secs"} and isinstance(m["ids"], list) and all(whole(i) for i in m["ids"]) and whole(m["secs"], 0, SECS_MAX)


def ui_netping_ok(m) -> bool:
    return set(m) == {"p", "secs", "by"} and vec3(m["p"]) and whole(m["secs"], 0, SECS_MAX) and isinstance(m["by"], str) and 1 <= len(m["by"]) <= 32


def ui_comms_ok(m) -> bool:
    return ({"from", "text"} <= set(m) <= {"from", "text", "p"} and isinstance(m["from"], str) and 1 <= len(m["from"]) <= 32 and text_ok(m["text"])
            and ("p" not in m or block_pos_ok(m["p"])))


def ui_scanowner_ok(m) -> bool:
    return set(m) == {"name"} and isinstance(m["name"], str)


ACT_VALIDATORS = {"rhack": act_rhack_ok, "breach": act_breach_ok, "feed": act_feed_ok, "feedmap": act_feedmap_ok, "netmap": act_netmap_ok, "comms": act_comms_ok}
UI_VALIDATORS = {"ract": ui_ract_ok, "netmap": ui_netmap_ok, "feed": ui_feed_ok, "vpanel": ui_vpanel_ok, "camfeed": ui_camfeed_ok, "outline": ui_outline_ok,
                 "netping": ui_netping_ok, "comms": ui_comms_ok, "breach": ui_breach_ok, "scanowner": ui_scanowner_ok}
P6_EXAMPLES = [(m["t"], m) for first in (P6_CP_EXAMPLES, P6_MC_EXAMPLES) for m in examples(first)]


def p6(t, index=0, **changes):
    m = copy.deepcopy([m for u, m in P6_EXAMPLES if u == t][index])
    m.update(changes)
    return m


def act(t, index=0, **changes):
    m = copy.deepcopy(examples(ACT_EXAMPLES[t])[index])
    m.update(changes)
    return m


def ui(t, **changes):
    m = copy.deepcopy(examples(UI_EXAMPLES[t])[0])
    m.update(changes)
    return m


def shared_down() -> list[dict]:
    return [json.loads(e["json"]) for e in SHARED["down_rhack"]]


# ---- the document -----------------------------------------------------------------------------------------------------------------------
def test_p6_every_type_has_a_row_an_example_and_a_validator():
    assert documented(P6_CP_TO_MC) == {"hackresult", "feed"} and documented(P6_MC_TO_CP) == {"rhack", "daemon", "feedreq"}
    assert {t for t, _ in P6_EXAMPLES} == set(P6_VALIDATORS)
    assert "**extends layers 3–6 and re-test 3:**" in rows_by_type(P6_CP_TO_MC)["hackresult"]
    assert all("**extends" not in row for t, row in {**rows_by_type(P6_CP_TO_MC), **rows_by_type(P6_MC_TO_CP)}.items() if t != "hackresult")


def test_p6_every_example_field_is_named_in_its_row():
    rows = {**rows_by_type(P6_CP_TO_MC), **rows_by_type(P6_MC_TO_CP)}
    for t, m in P6_EXAMPLES:
        for key in set(m) - {"t"}:
            assert re.search(rf"\b{key}\b", rows[t]), f"{t}.{key} is not described in its Phase 6 row"


@pytest.mark.parametrize("t, m", P6_EXAMPLES, ids=[f"{t}-{i}" for i, (t, _) in enumerate(P6_EXAMPLES)])
def test_p6_example_obeys_the_documented_limits(t, m):
    assert P6_VALIDATORS[t](m)


def test_p6_the_examples_are_real_messages_of_the_shared_file_and_tell_one_story():
    down = shared_down()
    rhacks = [m for t, m in P6_EXAMPLES if t == "rhack"]
    assert all(m in down for m in rhacks) and all(rhack_as_the_server_sends_it(m) for m in rhacks)
    assert [m["hack"] for m in rhacks] == ["veh_brake", "cam_feed", "traffic_switch", "blackout", "ping_area"]  # a car, a camera, two district hacks, a point
    daemons = [m for t, m in P6_EXAMPLES if t == "daemon"]
    assert daemons[1]["list"] == ["alarm"] and "alarm" not in daemons[0]["list"] and len(daemons[0]["list"]) == 2  # a solved Breach and the failure alarm
    results = [m for t, m in P6_EXAMPLES if t == "hackresult"]
    assert [m["ok"] for m in results] == [True, True, False] and results[1]["kind"] == "device" and results[2]["reason"] == "cyberblock.hack.out_of_range"
    assert [m["id"] for m in results] == [23, 29, 26] and {m["id"] for m in results} <= {m["id"] for m in rhacks}  # a request and its answer share the id
    assert [m["on"] for t, m in P6_EXAMPLES if t == "feedreq"] == [True]
    assert rhacks[1]["target"] == "11529215046068469765" and daemons[0]["dev"] == "11529215046068469770"  # the Phase 4 camera, the Net Port of the fake host
    feeds = [m for t, m in P6_EXAMPLES if t == "feed"]
    assert len(feeds) == 1 and feeds[0]["w"] == 128  # one frame: a DLL whose copy fell back sends no `feed` at all
    assert [rhacks[0]["secs"], rhacks[3]["secs"], rhacks[4]["secs"]] == [30, 180, 0]  # the server's durations: a car 30 s, a blackout 180 s, a ping 0
    assert {json.dumps(m, sort_keys=True) for m in results[:2]} <= {json.dumps(json.loads(e["json"]), sort_keys=True) for e in SHARED["up_hackresult"]} | {
        json.dumps({"t": "hackresult", "id": 29, "ok": True, "reason": "", "upload": 0.0, "kind": "device"}, sort_keys=True)}


def test_p6_the_names_have_the_plan_values():
    assert REMOTE_HACKS == ["veh_lock", "veh_engine_off", "veh_brake", "veh_start", "veh_jam", "traffic_switch", "gridlock", "blackout", "cam_feed", "outline", "ping_area"]
    assert AREA_HACKS == ["traffic_switch", "gridlock", "blackout", "outline", "ping_area"] and set(VEHICLE_HACKS) | set(AREA_HACKS) | {"cam_feed"} == set(REMOTE_HACKS)
    assert POINT_HACKS == ["gridlock", "outline", "ping_area"] and DISTRICT_HACKS == ["traffic_switch", "blackout"] and set(POINT_HACKS) | set(DISTRICT_HACKS) == set(AREA_HACKS)
    assert HACK_KINDS == ["traffic", "device", "camera"]
    assert ACT_TYPES == ["rhack", "breach", "feed", "feedmap", "netmap", "comms"]
    assert UI_TYPES == ["ract", "netmap", "feed", "vpanel", "camfeed", "outline", "netping", "comms", "breach", "scanowner"]
    assert BREACH_CODES == ["1C", "55", "BD", "E9", "7A", "FF"] and BREACH_OPS == ["pick", "abort"] and RESERVED == ["alarm"]
    assert BREACH_STATES == ["run", "solved", "failed", "timeout", "aborted"]


def test_p6_the_firewall_implant_is_the_18th_cyberware_id_in_every_place_the_document_lists_it():
    assert CYBERWARE == CYBERWARE_IDS and len(CYBERWARE_IDS) == 19 and CYBERWARE_IDS[17] == "firewall" and CYBERWARE_IDS[:17][-1] == "ram_upgrade"  # Phase 8 appended the 19th
    cw = next(m for t, m in P6_OLD_EXAMPLES if t == "cyberware")
    assert [e["id"] for e in cw["list"]] == CYBERWARE_IDS
    flat = " ".join(TEXT.split())
    assert "one of the 19 cyberware ids" in flat and "`firewall` is the 18th, Phase 6" in flat and "a DLL that does not know it drops that one entry" in flat
    assert "one of the 17 cyberware ids" not in flat and "one of the 18 cyberware ids" not in flat


def _old_examples():
    from test_protocol_md import L36_CP_EXAMPLES, L36_MC_EXAMPLES
    return [(m["t"], m) for first in (L36_CP_EXAMPLES, L36_MC_EXAMPLES) for m in examples(first)]


P6_OLD_EXAMPLES = _old_examples()


def test_p6_the_new_types_have_class_rows_a_rate_limit_and_the_feature_bit():
    cp, mc = dict(class_rows(MP_CP)), dict(class_rows(MP_MC))
    assert cp["feed"] == "BOTH" and cp["hackresult"] == "UP" and mc["rhack"] == mc["daemon"] == mc["feedreq"] == "DOWN"
    assert rate_rows()["feed"] == (2, 4) and rate_rows()["hackresult"] == (40, 80)
    assert "8 hacking" in " ".join(section(MP_PAYLOADS).split())  # hs_ack.features: 1 replica, 2 devices, 4 figures, 8 hacking, ...
    assert len("feed") <= 16 and len(rate_rows()) >= 20 and section(MP_SECURITY)
    java = (Path(__file__).resolve().parent.parent / "mc/src/main/java/dev/cyberblock/net/ServerNet.java").read_text(encoding="utf-8")
    assert re.search(r"static final int FEATURES = [^;]*Payloads\.FEATURE_HACKING", java)  # the server really sets bit 8


def test_p6_the_act_and_ui_payload_examples_are_valid():
    for t, check in ACT_VALIDATORS.items():
        got = examples(ACT_EXAMPLES[t])
        assert got and all(check(m) for m in got), t
        for m in got:
            assert len(json.dumps(m, separators=(",", ":")).encode()) <= 8000  # every `act` json is at most 8000 bytes
    for t, check in UI_VALIDATORS.items():
        got = examples(UI_EXAMPLES[t])
        assert len(got) == 1 and check(got[0]), t
    assert [m["op"] for m in examples(ACT_EXAMPLES["breach"])] == ["pick", "abort"]


def test_p6_the_act_examples_are_the_shared_messages_that_the_server_parses():
    """The Java test builds every shared act string back from ActWire.parse; the document shows the same strings."""
    assert [json.dumps(m, separators=(",", ":")) for m in examples(ACT_EXAMPLES["rhack"])] == SHARED["act_rhack"]
    for t in ("comms", "feed", "feedmap"):
        assert [json.dumps(m, separators=(",", ":")) for m in examples(ACT_EXAMPLES[t])] == SHARED[f"act_{t}"], t
    assert {json.loads(s)["hack"] for s in SHARED["act_rhack"]} == set(REMOTE_HACKS) - set(VEHICLE_HACKS) | {"veh_engine_off"}


def test_p6_the_shared_down_rhacks_cover_every_hack_and_pass_both_validators():
    down = shared_down()
    assert sorted(m["hack"] for m in down) == sorted(REMOTE_HACKS) and len(down) == 11
    assert all(rhack_ok(m) and rhack_as_the_server_sends_it(m) for m in down)
    assert all(type(m["r"]) is int for m in down if "r" in m) and {m["r"] for m in down if "r" in m} == {24, 32, 150}  # whole metres on the wire
    assert [m["hack"] for m in down if "secs" not in m] == ["cam_feed"]  # every other hack is timed (a ping 0)
    assert all(m["target"] is None and "p" in m for m in down if m["hack"] in AREA_HACKS) and not any("district" in m for m in down)
    vectors = json.loads((Path(__file__).resolve().parent.parent / "shared" / "rhack-vectors.json").read_text(encoding="utf-8"))["cases"]
    assert down == [c["down"] for c in vectors]  # the same eleven lines as the vectors
    assert all(feedreq_ok(json.loads(s)) for s in SHARED["down_feedreq"]) and "up_feed_fallback" not in SHARED
    assert all(hackresult_ok(json.loads(e["json"])) for e in SHARED["up_hackresult"])


def test_p6_the_feed_examples_are_one_frame_of_map_colours_without_the_transparent_bytes():
    frame = base64.b64decode(p6("feed")["px"], validate=True)
    assert len(frame) == FEED_BYTES and min(frame) >= 4
    assert ui("feed")["px"] == p6("feed")["px"]  # the server forwards the host's frame unchanged


def test_p6_payload_rows_name_every_example_field():
    rows = {(m.group(1), m.group(2)): m.group(0) for m in re.finditer(r'^\| `cyberblock:(act|ui)` `t:"(\w+)"` \|.*$', section(P6_PAYLOADS), re.M)}
    assert set(rows) == {("act", t) for t in ACT_TYPES} | {("ui", t) for t in UI_TYPES}
    for t in ACT_TYPES:
        for key in {k for m in examples(ACT_EXAMPLES[t]) for k in m}:
            assert re.search(rf"`{key}\b", rows[("act", t)]), f"act {t}.{key}"
    nested = {"breach": {"id", "codes", "ok"}, "netmap": {"id", "name", "p", "district", "active", "blackout", "h", "yaw"}}
    for t in UI_TYPES:
        for key in set(examples(UI_EXAMPLES[t])[0]):
            assert re.search(rf"`{key}\b", rows[("ui", t)]), f"ui {t}.{key}"
        for key in nested.get(t, set()):
            assert re.search(rf"[{{ ]{key}[,}}]", rows[("ui", t)]), f"ui {t}.{key}"
    for state in BREACH_STATES:
        assert f"`{state}`" in rows[("ui", "breach")]
    assert all(f"`{code}`" in rows[("ui", "breach")] for code in BREACH_CODES)


def test_p6_rules_are_stated_in_the_document():
    text = " ".join((section(P6_SECTION) + section(P6_PAYLOADS) + section(P6_CP_TO_MC) + section(P6_MC_TO_CP)).split())
    for phrase in ("DOWN to the target's owner only", "DOWN to the device's owner only", "`cyberblock.hack.out_of_range`", "every streamed target",
                   "**written fallback switches itself on**", "more than 50 m from V", "`hack range: every streamed target (...)`", "`rhack: remote range: ...`",
                   "answered `unsupported`", "`no such hack`", "`unsupported daemon`", "reserved for a failed Breach", "always sent alone", "exactly one `hackresult`",
                   "must carry an id the session issued", "the owner's `hacks` toggle", "`/cyberblock guests hacks on|off`", "the `devices` toggle and the `light` bit",
                   "`cyberblock.guest.blocked`", "never trusted with a result", "within 1 s of the request", "at most one `feed` per second", "0.5 ms of frame time",
                   "top-down replica view", "sends no other message", "no `feed` within 3 s of `feedreq` `on:true`", "`base × 4 + shade`", "transparent colour", "`hs_ack.features` bit 8 (hacking)",
                   "an 18th id, `firewall`", "validates **every** pick", "the first pick is in row 0", "no cell twice", "the `daemon` with `[\"alarm\"]`",
                   "`/cyberblock test breach <x> <y> <z> solve|fail`", "`/cyberblock test act <t> <json>`", "`/cyberblock test eid <targets>`",
                   "the **written fallback is the Phase 4 devices**", "150 m of V", "never fatal", "Every `act` is at most 8000 bytes", "Minecraft's own use path",
                   "at most 4 per second per player", "at most one every 2 s", "the extras only `blackout` reads `secs` and `r`",
                   "the DLL takes an absent, `\"\"` or `\"0\"` target the same way", "a whole number of metres: 150 for `traffic_switch` and `blackout`",
                   "`cam_feed` carries none of them", "a success also carries `upload` 0", "The client names no owner except `host`",
                   "the **nearest** active host whose V is within 150 m of the point", "The server sends exactly the shapes above"):
        assert phrase in text, f"{phrase!r} is not stated"


def test_p6_effects_range_feed_and_breach_rules_match_the_merged_code():
    """The integration of the Phase 6 review fixes: the scripts' own effect times and restore-before-save (6P, no save lock), the range rule (6R), the
    photo feed's replica stand-in (6D) and the feed map's per-holder frames (6I), and the Breach rules (6B: the timer from the opening, a kept puzzle, a
    10 s cooldown, one alarm a minute)."""
    text = " ".join((section(P6_SECTION) + section(P6_PAYLOADS) + section(P6_MC_TO_CP)).split())
    for phrase in (
            # every effect ends, with the scripts' own times
            "the car's doors locked for **120 s** (15 s on the car V is in and on V's own car)", "held off for **30 s** (10 s on V's car)", "(about **6 s**)", "the NCPD hack's **5 s** brake",
            "it is **not undone**", "**3 s** of lost control", "**before every save**", "there is no save lock", "restarts its timer and keeps the first saved state",
            "one intersection, not a whole district", "a new blackout ends the running one first",
            # the photo feed
            "it checks the toggle for every frame", "**stops forwarding** `cyberblock:ui` `feed` to the guests **at once**", "sends the DLL `feedreq` `on:false`",
            "**The feed map item** is held to the same rule at **every frame**", "the item's saved map colours stay blank", "a holder turned away gets a blank picture at once",
            # reach
            "The player-reach rule covers only the acts that stand for a hand use:", "a Breach pick (the player's eyes within 8 blocks of the Net Port block)",
            "The one exception is `cam_feed`: the camera must lie within the player's view distance",
            # Breach
            "The puzzle's timer starts when the screen opens", "**An abort is not a free re-roll.**", "the server **keeps that puzzle**", "for **10 s**",
            "`cyberblock.breach.cooldown`", "An owner gets at most one alarm a minute", "no `daemon`, no Heat; the server logs `alarm merged`"):
        assert phrase in text, f"{phrase!r} is not stated"
    for stale in ("hold a save lock", "`n\"CyberblockHack\"`", "**The timer starts with the first pick**", "`cyberblock.breach.locked_out`",
                  "`cyberblock.breach.player_locked_out`", "fallback notice", "no stand-in picture", "`target:\"\"`", "carry `district`",
                  "`upload` and `kind` are never sent", "never names an owner or an id", "farther than 50 m from V, is answered"):
        assert stale not in text, f"{stale!r} is still stated"
    session = " ".join(TEXT.split("- **`session`:**", 1)[1].split("- **", 1)[0].split())
    assert "held engine, locked door, braked car, switched-off device and forced city light" in session and "save lock" not in session


def test_p6_section_sits_between_phase_5_and_the_shared_memory_section():
    assert TEXT.index("## Phase 5 (figures and skins)") < TEXT.index("## Phase 6 (hacking and the interaction map)") < TEXT.index("## Shared memory")
    intro = " ".join(TEXT.split("## Phase 6 (hacking and the interaction map)", 1)[1].split("###", 1)[0].split())
    assert "2026-10-04-multiplayer-megaplan.md" in intro and "**`shared/phase6-messages.json`**" in intro and "as the merged code sends and takes them" in intro


def test_p6_log_lines_are_the_ones_the_code_prints():
    text = " ".join(section("Phase 6 log lines").split())
    for phrase in ("which runtime path is active", "`rhack: <feature> path: ...`", "`rhack: <player> -> <host> <hack> <target> (<n> m from V)`",
                   "`rhack: <host> answered <hack> ok|refused (<reason>)`", "`cyberblock.redstone.loop`", "`feed: <host> feedreq on|off`",
                   "`feed: off (Minecraft stopped asking)`", "`feed: on (...)`", "Re-test 6", "`alarm merged`"):
        assert phrase in text, phrase
    root = Path(__file__).resolve().parent.parent
    java = (root / "mc/src/main/java/dev/cyberblock/hack/actions/RemoteActions.java").read_text(encoding="utf-8")
    assert '"rhack: %s -> %s %s %s (%.0f m from V)"' in java and '"rhack: {} answered {} {}{}"' in java
    feed = (root / "cp/plugin/src/feed.cpp").read_text(encoding="utf-8")
    assert '"feed: off (Minecraft stopped asking)"' in feed and '"feed: on (path: ' in feed
    photo = (root / "mc/src/main/java/dev/cyberblock/hack/actions/PhotoFeed.java").read_text(encoding="utf-8")
    assert '"feed: {} feedreq {}"' in photo and "path: the top-down replica render around V (the fallback)" in photo
    assert "path: the top-down replica render around V (the fallback)" in text and '"fallback"' not in feed  # the DLL sends no notice


# ---- limits ---------------------------------------------------------------------------------------------------------------------------
def _bad_rhack():
    car = lambda **kw: p6("rhack", 0, **kw)  # noqa: E731
    area = lambda **kw: p6("rhack", 4, **kw)  # noqa: E731  (ping_area)
    return [
        car(id=-1), car(id=1.5), car(id="21"), car(id=True), car(id=2**31), car(target=None), car(target="0"), car(target=""), car(target=12),
        car(target="18446744073709551616"), car(target="-3"), car(hack=None), car(hack=7), {k: v for k, v in car().items() if k != "target"},
        {k: v for k, v in car().items() if k != "hack"}, {k: v for k, v in car().items() if k != "id"}, p6("rhack", 1, target=""),
        area(target="x1"), area(r=-1), area(r=150.5), area(r="24"), area(r=True), area(secs=-1), area(secs=601), area(secs=1.5), area(secs=True),
        area(p=[0, 0]), area(p=[0, float("nan"), 0]), area(p=[3e7, 0, 0]), area(district=5), {**area(), "extra": 1},
    ]


def _bad_daemon():
    d = lambda **kw: p6("daemon", 0, **kw)  # noqa: E731
    return [d(id=-1), d(id="24"), d(dev="0"), d(dev=5), d(list=[]), d(list="open_door"), d(list=["a", "b", "c", "d"]), d(list=["a", "a"]), d(list=["Open"]),
            d(list=[""]), d(list=["x" * 33]), d(list=[7]), d(list=["alarm", "open_door"]), d(strength=0), d(strength=4), d(strength=1.5), d(strength=True),
            {k: v for k, v in d().items() if k != "strength"}, {**d(), "extra": 1}]


def _bad_feed():
    ok = base64.b64encode(bytes(FEED_BYTES)).decode()
    short = base64.b64encode(bytes(FEED_BYTES - 1)).decode()
    f = lambda **kw: p6("feed", 0, **kw)  # noqa: E731
    return [f(w=64), f(h=127), f(w=128.0), f(px=short), f(px=base64.b64encode(bytes(FEED_BYTES + 1)).decode()), f(px="not base64!"), f(px=ok[:-1]), f(px=None), f(px=7),
            {k: v for k, v in f().items() if k != "px"}, {**f(), "n": 1}, f(w=0),
            {"t": "feed", "w": 0, "h": 0, "px": "", "fallback": "the capture cost 0.82 ms a frame"}]  # the notice the DLL no longer sends


P6_OUT_OF_LIMITS = ([("rhack", m) for m in _bad_rhack()] + [("daemon", m) for m in _bad_daemon()] + [("feed", m) for m in _bad_feed()]
                    + [("feedreq", p6("feedreq", on=1)), ("feedreq", p6("feedreq", on="true")), ("feedreq", {"t": "feedreq"}), ("feedreq", {**p6("feedreq"), "owner": "x"}),
                       ("hackresult", p6("hackresult", 0, ok=False)), ("hackresult", p6("hackresult", 2, reason="")), ("hackresult", p6("hackresult", 2, reason="x" * 65)),
                       ("hackresult", p6("hackresult", 0, id=-3)), ("hackresult", p6("hackresult", 1, kind="squad")), ("hackresult", p6("hackresult", 0, upload=1.0)),
                       ("hackresult", {k: v for k, v in p6("hackresult", 0).items() if k != "upload"}), ("hackresult", {**p6("hackresult", 2), "kind": "camera"})])


@pytest.mark.parametrize("t, m", P6_OUT_OF_LIMITS, ids=[f"{t}-{i}" for i, (t, _) in enumerate(P6_OUT_OF_LIMITS)])
def test_p6_validators_reject_out_of_limit_messages(t, m):
    try:
        ok = P6_VALIDATORS[t](m)
    except (KeyError, TypeError):
        ok = False
    assert not ok


def test_p6_validators_accept_the_limits_themselves():
    for target in NO_TARGET:  # the DLL takes "", null, absent and "0" alike for an area hack
        assert rhack_ok({"t": "rhack", "id": 0, "target": target, "hack": "blackout"}) and rhack_ok({"t": "rhack", "id": 0, "hack": "gridlock"})
    for hack in REMOTE_HACKS:
        assert rhack_ok({"t": "rhack", "id": 2**31 - 1, "target": "18446744073709551615", "hack": hack, "p": [-29_999_999, -64, 29_999_999], "r": 150, "secs": 600}), hack
    assert rhack_ok(p6("rhack", 4, r=0, secs=0)) and rhack_ok(p6("rhack", 4, r=0.5)) and rhack_ok({"t": "rhack", "id": 1, "target": "5", "hack": "nuke"})  # answered unsupported
    assert rhack_as_the_server_sends_it(p6("rhack", 0, secs=30)) and not rhack_as_the_server_sends_it(p6("rhack", 0, secs=29))
    assert not rhack_as_the_server_sends_it(p6("rhack", 3, secs=301)) and not rhack_as_the_server_sends_it(p6("rhack", 4, target=""))
    assert not rhack_as_the_server_sends_it(p6("rhack", 4, r=32.0)) and not rhack_as_the_server_sends_it({**p6("rhack", 2), "district": "Watson"})
    assert daemon_ok(p6("daemon", 0, list=["a", "b_2", "x" * 32], strength=3)) and daemon_ok(p6("daemon", 0, list=["alarm"], strength=1)) and daemon_ok(p6("daemon", 0, list=["z"], id=0))
    assert feedreq_ok({"t": "feedreq", "on": False}) and feed_ok(p6("feed", px=base64.b64encode(bytes(FEED_BYTES)).decode()))
    assert hackresult_ok({"t": "hackresult", "id": 0, "ok": False, "reason": "x" * 64}) and hackresult_ok({"t": "hackresult", "id": 7, "ok": True, "reason": "", "upload": 0})
    assert all(hackresult_ok({"t": "hackresult", "id": 7, "ok": True, "reason": "", "upload": 0.0, "kind": k}) for k in HACK_KINDS)


def _bad_acts():
    hack = lambda **kw: act("rhack", 0, **kw)  # noqa: E731
    return [
        ("rhack", hack(eid=-1)), ("rhack", hack(eid="212")), ("rhack", hack(eid=1.5)), ("rhack", {**hack(), "host": "x"}), ("rhack", {"hack": "veh_brake"}),
        ("rhack", {"hack": "veh_brake", "target": 212}), ("rhack", act("rhack", 1, dev="0")), ("rhack", act("rhack", 1, host="3F2A8D1C-5B7E-4C1A-9D3E-0A1B2C3D4E5F")),
        ("rhack", {k: v for k, v in act("rhack", 1).items() if k != "dev"}), ("rhack", {**act("rhack", 2), "secs": 60}), ("rhack", act("rhack", 3, secs=59)),
        ("rhack", act("rhack", 3, secs=301)), ("rhack", act("rhack", 4, p=[1, 2])), ("rhack", act("rhack", 4, p=[3e7, 0, 0])), ("rhack", act("rhack", 4, p=[0, 20000, 0])),
        ("rhack", {**act("rhack", 4), "r": 5}), ("rhack", {"hack": "nuke", "p": [0, 0, 0]}),
        ("breach", {"op": "pick", "id": 12}), ("breach", act("breach", 0, cell=49)), ("breach", act("breach", 0, cell=-1)), ("breach", act("breach", 0, cell=1.5)),
        ("breach", act("breach", 0, id=-1)), ("breach", {"op": "abort", "id": 12, "cell": 3}), ("breach", {"op": "solve", "id": 12}), ("breach", {"op": "pick", "cell": 1}),
        ("feed", act("feed", host="3f2a8d1c5b7e4c1a9d3e0a1b2c3d4e5f")), ("feed", act("feed", host=None)), ("feed", act("feed", on=1)), ("feed", {"on": True}),
        ("feed", {**act("feed"), "owner": "x"}), ("feedmap", {}), ("feedmap", act("feedmap", host="nobody")), ("netmap", {"x": 1}),
        ("comms", act("comms", text="x" * 65)), ("comms", act("comms", text="", p=None)), ("comms", act("comms", p=[1, 2])), ("comms", act("comms", p=[3e7, 0, 0])),
        ("comms", act("comms", to="nobody")), ("comms", act("comms", text=None)), ("comms", {**act("comms"), "pos": [0, 0, 0]}),
    ]


@pytest.mark.parametrize("t, m", _bad_acts(), ids=lambda v: v[0] if isinstance(v, str) else "act")
def test_p6_act_validators_reject_out_of_limit_payloads(t, m):
    try:
        ok = ACT_VALIDATORS[t](m)
    except (KeyError, TypeError):
        ok = False
    assert not ok, m


def test_p6_act_validators_accept_the_limits_themselves():
    host = "0" * 8 + "-" + "0000-" * 3 + "0" * 12
    assert act_comms_ok({"to": host, "text": "x" * TEXT_CAP}) and act_comms_ok(act("comms", text="", p=[-29_999_999.5, 0, 29_999_999])) and act_comms_ok(act("comms", p=None))
    assert act_breach_ok(act("breach", 0, cell=0, id=0)) and act_breach_ok(act("breach", 0, cell=48)) and act_breach_ok({"op": "abort", "id": 2**31 - 1})
    assert all(act_rhack_ok({"hack": h, "p": [0.5, -64, 0.25]}) for h in POINT_HACKS) and act_rhack_ok({"hack": "veh_start", "eid": 0})
    assert act_rhack_ok({"hack": "blackout", "host": host}) and act_rhack_ok({"hack": "blackout", "host": host, "secs": 60}) and act_rhack_ok({"hack": "blackout", "host": host, "secs": 300})
    assert act_feed_ok(act("feed", on=False)) and act_feedmap_ok({"host": host}) and act_netmap_ok({})


def _bad_ui():
    b = lambda **kw: ("breach", ui("breach", **kw))  # noqa: E731
    host = ui("netmap")["hosts"][0]
    return [
        b(size=4), b(size=8), b(size=6), b(buffer=3), b(buffer=9), b(grid=["1C"] * 24), b(grid=["1C"] * 24 + ["ZZ"]), b(grid=["1c"] * 25), b(picks=[25]), b(picks=[-1]),
        b(picks=[2, 2]), b(picks=[0, 1, 2, 3, 4, 5, 6]), b(seqs=[]), b(seqs=[{"id": "a", "codes": ["BD"], "ok": False}]), b(seqs=[{"id": "A", "codes": ["BD", "1C"], "ok": False}]),
        b(seqs=[{"id": "a", "codes": ["BD", "1C"], "ok": 1}]), b(seqs=[{"id": "a", "codes": ["BD", "1C", "55", "7A", "E9", "FF", "55"], "ok": False}]),
        b(seqs=[{"id": f"d{i}", "codes": ["BD", "1C"], "ok": False} for i in range(4)]), b(total=0), b(total=301), b(left=-1), b(left=46), b(state="won"), b(strength=4),
        b(strength=-1), b(dev="-1"), b(dev="00"), b(id=-1), b(size=3, dev="0", grid=["1C"] * 9, picks=[]),
        b(size=4, grid=["1C"] * 16, picks=[], seqs=[{"id": "a", "codes": ["BD", "1C"], "ok": False}]), ("breach", {k: v for k, v in ui("breach").items() if k != "left"}),
        ("breach", {**ui("breach"), "extra": 1}),
    ] + [
        ("feed", ui("feed", host="x")), ("feed", ui("feed", px="AA==")), ("feed", {**ui("feed"), "w": 128}), ("feed", {k: v for k, v in ui("feed").items() if k != "host"}),
        ("comms", ui("comms", text="x" * 65)), ("comms", ui("comms", **{"from": ""})), ("comms", ui("comms", **{"from": "x" * 33})), ("comms", ui("comms", p=[1, 2, 3.5])),
        ("comms", ui("comms", p=None)), ("ract", ui("ract", hack="nuke")), ("ract", ui("ract", ok=False)), ("ract", ui("ract", reason="x")),
        ("netmap", ui("netmap", range=10)), ("netmap", ui("netmap", hosts=[{**host, "id": "x"}])), ("netmap", ui("netmap", hosts=[{**host, "active": 1}])),
        ("netmap", ui("netmap", cams=[{"h": host["id"], "id": "0", "p": [0, 0, 0], "yaw": 0}])), ("vpanel", ui("vpanel", eid=-1)), ("camfeed", ui("camfeed", p=[0, 0])),
        ("outline", ui("outline", ids=[1.5])), ("outline", ui("outline", secs=-1)), ("netping", ui("netping", by="")), ("netping", ui("netping", secs=601)),
        ("scanowner", ui("scanowner", name=None)), ("scanowner", {**ui("scanowner"), "id": 1}),
    ]


@pytest.mark.parametrize("t, m", _bad_ui(), ids=lambda v: v[0] if isinstance(v, str) else "ui")
def test_p6_ui_validators_reject_out_of_limit_payloads(t, m):
    try:
        ok = UI_VALIDATORS[t](m)
    except (KeyError, TypeError):
        ok = False
    assert not ok, m


def test_p6_ui_validators_accept_the_limits_themselves():
    seven = ui("breach", size=7, grid=(["1C", "55", "BD", "E9", "7A", "FF", "1C"] * 7), buffer=8, picks=list(range(8)), total=300, left=300.0, state="solved", strength=3,
              seqs=[{"id": f"d{i}", "codes": ["BD"] * 8, "ok": True} for i in range(3)])
    assert ui_breach_ok(seven) and all(ui_breach_ok(ui("breach", state=s)) for s in BREACH_STATES) and ui_breach_ok(ui("breach", picks=[], left=0))
    short = ui("breach", dev="0", size=4, grid=["1C", "55", "BD", "E9"] * 4, buffer=4, picks=[], seqs=[{"id": "a", "codes": ["BD", "1C"], "ok": False}], total=5, left=5)
    assert ui_breach_ok(short) and ui_breach_ok({**short, "size": 5, "grid": ["1C"] * 25})  # Phase 8's 4x4 and a practice, both with no device
    assert ui_comms_ok({"from": "x" * 32, "text": ""}) and ui_comms_ok(ui("comms", text="x" * TEXT_CAP)) and ui_scanowner_ok({"name": ""})
    assert ui_netmap_ok(ui("netmap", hosts=[], cams=[], range=50)) and ui_ract_ok({"hack": "blackout", "ok": False, "reason": "busy"}) and ui_outline_ok(ui("outline", ids=[]))
