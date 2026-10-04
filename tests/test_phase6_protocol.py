"""Phase 6 protocol (megaplan task 6-P1): the rows, examples and validators of `rhack`, `daemon`, `feedreq`, `feed`, the extended `hackresult`, the four
`cyberblock:act` payloads (`rhack`, `breach`, `feedreq`, `comms`), the three `cyberblock:ui` payloads (`breach`, `feed`, `comms`) and the 18th cyberware id,
`firewall`. Plan 2026-10-04 megaplan sections 1.8 and 1.9 (rows 6), 1.17 and 10, with the Phase 0 amendment (fallback (h): only streamed targets)."""
import base64
import binascii
import copy
import re

import pytest

from test_protocol_md import (CYBERWARE, CYBERWARE_IDS, TEXT, class_rows, documented, dev_id_ok, examples, MP_CP, MP_MC, MP_PAYLOADS, MP_SECURITY,
                              num, rate_rows, rows_by_type, section, vec3, whole)

P6_CP_TO_MC, P6_MC_TO_CP = "Phase 6: Cyberpunk → Minecraft", "Phase 6: Minecraft → Cyberpunk"
P6_CP_EXAMPLES, P6_MC_EXAMPLES = "Phase 6 examples, Cyberpunk to Minecraft:", "Phase 6 examples, Minecraft to Cyberpunk:"
P6_SECTION, P6_PAYLOADS = "Phase 6 semantics and limits", "Phase 6: payloads (client ↔ server)"
ACT_EXAMPLES = {t: f"Phase 6 examples, client to server (`cyberblock:act`, `t:\"{t}\"`):" for t in ("rhack", "breach", "feedreq", "comms")}
UI_EXAMPLES = {t: f"Phase 6 examples, server to client (`cyberblock:ui`, `t:\"{t}\"`):" for t in ("breach", "feed", "comms")}


def names6(label: str) -> list[str]:
    """Like `names`, but the Phase 6 lists also hold upper-case hex codes and digits (`1C`, `55`)."""
    line = re.search(rf"^- \*\*{re.escape(label)}\*\*.*$", TEXT, re.M).group(0)
    return re.findall(r"`([A-Za-z0-9_]+)`", line.split("—", 1)[1])


REMOTE_HACKS, AREA_HACKS = names6("Remote hack names"), names6("Area hacks")
BREACH_CODES, BREACH_STATES, BREACH_OPS, RESERVED = names6("Breach codes"), names6("Breach states"), names6("Breach ops"), names6("Reserved daemon id")
VEHICLE_HACKS = ["veh_lock", "veh_engine_off", "veh_brake", "veh_start", "veh_jam"]
DAEMON_ID = re.compile(r"[a-z0-9_]{1,32}")
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
POS_LIMIT, FEED_BYTES, TEXT_CAP = 30_000_000, 128 * 128, 80


# ---- validators ---------------------------------------------------------------------------------------------------------------------
def request_id_ok(x) -> bool:
    return whole(x)


def block_pos_ok(pos) -> bool:
    return isinstance(pos, list) and len(pos) == 3 and all(whole(c, -POS_LIMIT, POS_LIMIT) for c in pos)


TIMED_AREA_HACKS = ("traffic_switch", "gridlock", "blackout")  # they change Cyberpunk state, so `secs` is 1..600 and the scripts put it back
SECS_MAX = 600


def rhack_ok(m) -> bool:
    """DOWN `rhack`: a vehicle hack needs a car's id and `secs` 1..600, `cam_feed` a camera's id and nothing else, an area hack `target:null` and `p`,
    `r`, `secs` (1..600 for the timed ones, 0..600 for `outline` and `ping_area`)."""
    base = {"t", "id", "target", "hack"}
    if not (isinstance(m, dict) and base <= set(m) and request_id_ok(m["id"]) and isinstance(m["hack"], str)):
        return False
    hack = m["hack"]
    if hack in AREA_HACKS:
        low = 1 if hack in TIMED_AREA_HACKS else 0
        return (set(m) == base | {"p", "r", "secs"} and m["target"] is None and vec3(m["p"]) and whole(m["r"], 1, 150) and whole(m["secs"], low, SECS_MAX))
    if hack in VEHICLE_HACKS:
        return set(m) == base | {"secs"} and dev_id_ok(m["target"]) and whole(m["secs"], 1, SECS_MAX)
    return set(m) == base and hack == "cam_feed" and dev_id_ok(m["target"])


def daemon_ok(m) -> bool:
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
    return set(m) == {"t", "w", "h", "px"} and m["w"] == 128 and m["h"] == 128 and type(m["w"]) is int and type(m["h"]) is int and px_ok(m["px"])


def hackresult_ok(m) -> bool:
    """The Phase 6 form: `id`, `ok`, `reason` (at most 64 characters, `""` when ok); `upload` and `kind` are never sent for `rhack` and `daemon`."""
    return (set(m) == {"t", "id", "ok", "reason"} and request_id_ok(m["id"]) and isinstance(m["ok"], bool) and isinstance(m["reason"], str)
            and len(m["reason"]) <= 64 and (m["reason"] == "") == m["ok"])


P6_VALIDATORS = {"hackresult": hackresult_ok, "feed": feed_ok, "rhack": rhack_ok, "daemon": daemon_ok, "feedreq": feedreq_ok}


def act_rhack_ok(m) -> bool:
    """`cyberblock:act` `t:"rhack"`: the client names a car figure's entity id, a camera's block or an area's centre block and radius, never an owner."""
    hack = m.get("hack")
    if hack in VEHICLE_HACKS:
        return set(m) == {"hack", "target"} and whole(m["target"])
    if hack == "cam_feed":
        return set(m) == {"hack", "pos"} and block_pos_ok(m["pos"])
    return hack in AREA_HACKS and set(m) == {"hack", "pos", "r"} and block_pos_ok(m["pos"]) and whole(m["r"], 1, 150)


def act_breach_ok(m) -> bool:
    if m.get("op") == "pick":
        return set(m) == {"op", "id", "cell"} and whole(m["id"]) and whole(m["cell"], 0, 48)
    return m.get("op") == "abort" and set(m) == {"op", "id"} and whole(m["id"])


def act_feedreq_ok(m) -> bool:
    return set(m) == {"owner", "on"} and isinstance(m["owner"], str) and UUID.fullmatch(m["owner"]) is not None and isinstance(m["on"], bool)


def text_ok(s) -> bool:
    return isinstance(s, str) and len(s) <= TEXT_CAP


def act_comms_ok(m) -> bool:
    return (set(m) == {"to", "text", "pos"} and isinstance(m["to"], str) and UUID.fullmatch(m["to"]) is not None and text_ok(m["text"])
            and (m["pos"] is None or block_pos_ok(m["pos"])) and not (m["text"] == "" and m["pos"] is None))


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


def ui_feed_ok(m) -> bool:
    return (set(m) == {"owner", "w", "h", "px"} and isinstance(m["owner"], str) and UUID.fullmatch(m["owner"]) is not None and m["w"] == 128 and m["h"] == 128
            and px_ok(m["px"]))


def ui_comms_ok(m) -> bool:
    return (set(m) == {"from", "text", "pos"} and isinstance(m["from"], str) and 1 <= len(m["from"]) <= 32 and text_ok(m["text"])
            and (m["pos"] is None or block_pos_ok(m["pos"])))


ACT_VALIDATORS = {"rhack": act_rhack_ok, "breach": act_breach_ok, "feedreq": act_feedreq_ok, "comms": act_comms_ok}
UI_VALIDATORS = {"breach": ui_breach_ok, "feed": ui_feed_ok, "comms": ui_comms_ok}
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


def test_p6_the_examples_show_every_form_and_tell_one_story():
    rhacks = [m for t, m in P6_EXAMPLES if t == "rhack"]
    assert [m["hack"] for m in rhacks] == ["veh_brake", "cam_feed", "blackout"]  # a car, a camera, an area
    assert rhacks[0]["target"] and rhacks[1]["target"] and rhacks[2]["target"] is None and {"p", "r", "secs"} <= set(rhacks[2])
    assert rhacks[0]["secs"] == 30 and "secs" not in rhacks[1] and rhacks[2]["secs"] == 180  # the server's durations: a car hack 30 s, a blackout 180 s
    daemons = [m for t, m in P6_EXAMPLES if t == "daemon"]
    assert daemons[1]["list"] == ["alarm"] and "alarm" not in daemons[0]["list"] and len(daemons[0]["list"]) == 2  # a solved Breach and the failure alarm
    results = [m for t, m in P6_EXAMPLES if t == "hackresult"]
    assert [m["ok"] for m in results] == [True, False] and results[1]["reason"] == "cyberblock.hack.out_of_range"
    assert results[0]["id"] == rhacks[0]["id"] and results[1]["id"] == rhacks[1]["id"]  # a request and its answer share the id
    assert [m["on"] for t, m in P6_EXAMPLES if t == "feedreq"] == [True]
    assert rhacks[1]["target"] == "11529215046068469765" and daemons[0]["dev"] == "11529215046068469770"  # the Phase 4 camera, the Net Port of the fake host


def test_p6_the_names_have_the_plan_values():
    assert REMOTE_HACKS == ["veh_lock", "veh_engine_off", "veh_brake", "veh_start", "veh_jam", "traffic_switch", "gridlock", "blackout", "cam_feed", "outline", "ping_area"]
    assert AREA_HACKS == ["traffic_switch", "gridlock", "blackout", "outline", "ping_area"] and set(VEHICLE_HACKS) | set(AREA_HACKS) | {"cam_feed"} == set(REMOTE_HACKS)
    assert BREACH_CODES == ["1C", "55", "BD", "E9", "7A", "FF"] and BREACH_OPS == ["pick", "abort"] and RESERVED == ["alarm"]
    assert BREACH_STATES == ["run", "solved", "failed", "timeout", "aborted"]


def test_p6_the_firewall_implant_is_the_18th_cyberware_id_in_every_place_the_document_lists_it():
    assert CYBERWARE == CYBERWARE_IDS and len(CYBERWARE_IDS) == 18 and CYBERWARE_IDS[-1] == "firewall" and CYBERWARE_IDS[:17][-1] == "ram_upgrade"
    cw = next(m for t, m in P6_OLD_EXAMPLES if t == "cyberware")
    assert [e["id"] for e in cw["list"]] == CYBERWARE_IDS
    flat = " ".join(TEXT.split())
    assert "one of the 18 cyberware ids" in flat and "`firewall` is the 18th, Phase 6" in flat and "a DLL that does not know it drops that one entry" in flat
    assert "one of the 17 cyberware ids" not in flat


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


def test_p6_the_act_and_ui_payload_examples_are_valid():
    for t, check in ACT_VALIDATORS.items():
        got = examples(ACT_EXAMPLES[t])
        assert got and all(check(m) for m in got), t
    for t, check in UI_VALIDATORS.items():
        got = examples(UI_EXAMPLES[t])
        assert len(got) == 1 and check(got[0]), t
    assert [m["hack"] for m in examples(ACT_EXAMPLES["rhack"])] == ["veh_brake", "cam_feed", "blackout"]
    assert [m["op"] for m in examples(ACT_EXAMPLES["breach"])] == ["pick", "abort"]
    for m in (*examples(ACT_EXAMPLES["rhack"]), *examples(ACT_EXAMPLES["breach"]), *examples(ACT_EXAMPLES["feedreq"]), *examples(ACT_EXAMPLES["comms"])):
        assert len(json_text(m).encode()) <= 8000  # every `act` json is at most 8000 bytes


def json_text(m) -> str:
    import json
    return json.dumps(m, separators=(",", ":"))


def test_p6_the_breach_example_follows_the_row_and_column_rule_and_its_sequences_fit_the_grid():
    m = ui("breach")
    size, grid, picks = m["size"], m["grid"], m["picks"]
    assert picks[0] // size == 0  # the first pick is in the top row
    for i in range(1, len(picks)):  # then column, row, column, ... of the previous pick
        same = (picks[i] % size == picks[i - 1] % size) if i % 2 == 1 else (picks[i] // size == picks[i - 1] // size)
        assert same, (i, picks)
    assert all(code in grid for s in m["seqs"] for code in s["codes"])
    assert m["state"] == "run" and m["strength"] == 0 and len({s["id"] for s in m["seqs"]}) == len(m["seqs"])
    assert m["dev"] == p6("daemon")["dev"]


def test_p6_the_feed_examples_are_one_frame_of_map_colours_without_the_transparent_bytes():
    frame = base64.b64decode(p6("feed")["px"], validate=True)
    assert len(frame) == FEED_BYTES and min(frame) >= 4
    assert ui("feed")["px"] == p6("feed")["px"]  # the server forwards the host's frame unchanged


def test_p6_payload_rows_name_every_example_field():
    rows = {(m.group(1), m.group(2)): m.group(0) for m in re.finditer(r'^\| `cyberblock:(act|ui)` `t:"(\w+)"` \|.*$', section(P6_PAYLOADS), re.M)}
    assert set(rows) == {("act", "rhack"), ("act", "breach"), ("act", "feedreq"), ("act", "comms"), ("ui", "breach"), ("ui", "feed"), ("ui", "comms")}
    for t in ACT_EXAMPLES:
        for key in {k for m in examples(ACT_EXAMPLES[t]) for k in m}:
            assert re.search(rf"`{key}\b", rows[("act", t)]), f"act {t}.{key}"
    for t in UI_EXAMPLES:
        for key in set(examples(UI_EXAMPLES[t])[0]) | ({"id", "codes", "ok"} if t == "breach" else set()):
            assert re.search(rf"`{key}\b", rows[("ui", t)]), f"ui {t}.{key}"
    for state in BREACH_STATES:
        assert f"`{state}`" in rows[("ui", "breach")]
    assert all(f"`{code}`" in rows[("ui", "breach")] for code in BREACH_CODES)


def test_p6_rules_are_stated_in_the_document():
    text = " ".join((section(P6_SECTION) + section(P6_PAYLOADS) + section(P6_CP_TO_MC) + section(P6_MC_TO_CP)).split())
    for phrase in ("DOWN to the target's owner only", "DOWN to the device's owner only", "`cyberblock.hack.out_of_range`", "farther than 50 m from V",
                   "the fallback of Phase 0 check (h)", "answered `unsupported`", "`unsupported daemon`", "reserved for a failed Breach", "always sent alone",
                   "exactly one `hackresult`", "must carry an id the session issued", "the owner's `hacks` toggle", "`/cyberblock guests hacks on|off`",
                   "the owner's `devices` toggle", "`cyberblock.guest.blocked`", "never trusted with a result", "within 1 s of the request", "at most one `feed` per second",
                   "0.5 ms of frame time", "top-down replica view", "`base × 4 + shade`", "transparent colour", "`hs_ack.features` bit 8 (hacking)", "an 18th id, `firewall`",
                   "never names an owner or an id", "validates **every** pick", "the first pick is in row 0", "no cell twice", "`daemon` with `[\"alarm\"]`",
                   "`/cyberblock test breach <x> <y> <z> solve|fail`", "the written fallback is the Phase 4 devices", "150 m of V", "never fatal", "Every `act` is at most 8000 bytes",
                   "Minecraft's own use path", "at most 4 per second", "at most one every 2 s", "the camera's position and facing are already in the `devices` list"):
        assert phrase in text, f"{phrase!r} is not stated"


def test_p6_reach_effects_feed_and_abort_rules_close_the_review_gaps():
    """The 6P review fixes: the player-reach rule only for hand uses, every Cyberpunk effect timed and restored (session end, link drop, save), the
    photo feed behind the owner's `hacks` toggle, and an abort that is not a free re-roll."""
    text = " ".join((section(P6_SECTION) + section(P6_PAYLOADS) + section(P6_MC_TO_CP)).split())
    for phrase in (
            # reach: only hand uses are checked against the player; the Net Map and the Traffic Tool are not
            "The player-reach rule covers only the acts that stand for a hand use:", "a Breach pick (the player's eyes within 8 blocks of the Net Port block)",
            "a Vehicle Panel opened by right-clicking a car figure", "**Net Map and Traffic Tool `rhack` acts (the `cam_feed` `pos`, an area's `pos`, a vehicle target picked on the map) and the `comms` `pos` are not checked against the player's position**",
            "bounded only by the V-based 50 m rule above and by the owning host's sanity radius (160 blocks)",
            # every effect ends
            "a **vehicle** hack also carries `secs` (a whole number 1..600", "1..600 for `traffic_switch`, `gridlock` and `blackout`", "`secs` 0 is accepted only for `outline` and `ping_area`",
            "restore **every pending effect at once** when the session goes to `none`, `guest` or `refused` and on a link drop", "**a save never holds one**",
            "hold a save lock (source `n\"CyberblockHack\"`", "V's own car and the car V sits in are not exempt", "restarts its timer and keeps the first saved state",
            # the photo feed
            "A guest's `feedreq` needs the owner's `hacks` toggle; the host's own client is always allowed", "removes that host's guest viewers **at once**",
            "stops forwarding `cyberblock:ui` `feed` to them", "sends the DLL `feedreq` `on:false`",
            # abort
            "an `aborted` Breach after the first pick send the `daemon` with `[\"alarm\"]`", "**An abort is not a free re-roll.**", "the server **keeps that puzzle**",
            "for **10 s**", "`cyberblock.breach.cooldown`", "The puzzle's timer starts when the screen opens"):
        assert phrase in text, f"{phrase!r} is not stated"
    assert "more than 8 blocks from the player's eyes for a block, 50 m for a car" not in text  # the old rule that reach-checked every act
    assert "0 = no timer" not in text
    session = " ".join(TEXT.split("- **`session`:**", 1)[1].split("- **", 1)[0].split())
    assert "every pending blackout, traffic and vehicle hack effect restored and its save lock released" in session


def test_p6_section_sits_between_phase_5_and_the_shared_memory_section():
    assert TEXT.index("## Phase 5 (figures and skins)") < TEXT.index("## Phase 6 (hacking and the interaction map)") < TEXT.index("## Shared memory")
    assert "2026-10-04-multiplayer-megaplan.md" in TEXT.split("## Phase 6 (hacking and the interaction map)", 1)[1].split("###", 1)[0]


def test_p6_log_lines_are_listed_with_the_fallback_rule():
    text = " ".join(section("Phase 6 log lines").split())
    for phrase in ("which runtime path is active", "`rhack: <name> -> <owner> (<hack>)`", "`cyberblock.redstone.loop`", "`feedreq on|off`", "Re-test 6"):
        assert phrase in text, phrase


# ---- limits ---------------------------------------------------------------------------------------------------------------------------
def _bad_rhack():
    area = lambda **kw: p6("rhack", 2, **kw)  # noqa: E731
    car = lambda **kw: p6("rhack", 0, **kw)  # noqa: E731
    return [
        car(id=-1), car(id=1.5), car(id="21"), car(id=True), car(target=None), car(target="0"), car(target=12), car(target="18446744073709551616"), car(hack="veh_explode"),
        car(hack=None), {**car(), "p": [0, 0, 0]}, {k: v for k, v in car().items() if k != "target"}, p6("rhack", 1, target=None), {**p6("rhack", 1), "r": 5},
        area(target="123"), area(r=0), area(r=151), area(r=1.5), area(secs=-1), area(secs=601), area(p=[0, 0]), area(p=[0, float("nan"), 0]), area(p=[3e7, 0, 0]),
        {k: v for k, v in area().items() if k != "secs"}, {k: v for k, v in area().items() if k != "p"}, {k: v for k, v in area().items() if k != "r"},
        area(hack="veh_lock"),  # a vehicle hack with an area's fields
        # every effect ends: a vehicle hack always carries secs 1..600, a timed area hack never secs 0, cam_feed never carries secs
        {k: v for k, v in car().items() if k != "secs"}, car(secs=0), car(secs=601), car(secs=1.5), car(secs=True), car(secs=None),
        {**p6("rhack", 1), "secs": 30}, area(secs=0), area(hack="traffic_switch", secs=0), area(hack="gridlock", secs=0),
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
            {k: v for k, v in f().items() if k != "px"}, {**f(), "n": 1}]


P6_OUT_OF_LIMITS = ([("rhack", m) for m in _bad_rhack()] + [("daemon", m) for m in _bad_daemon()] + [("feed", m) for m in _bad_feed()]
                    + [("feedreq", p6("feedreq", on=1)), ("feedreq", p6("feedreq", on="true")), ("feedreq", {"t": "feedreq"}), ("feedreq", {**p6("feedreq"), "owner": "x"}),
                       ("hackresult", p6("hackresult", 0, ok=False)), ("hackresult", p6("hackresult", 1, reason="")), ("hackresult", p6("hackresult", 1, reason="x" * 65)),
                       ("hackresult", p6("hackresult", 0, id=-3)), ("hackresult", {**p6("hackresult"), "kind": "camera"}), ("hackresult", {**p6("hackresult"), "upload": 1.0})])


@pytest.mark.parametrize("t, m", P6_OUT_OF_LIMITS, ids=[f"{t}-{i}" for i, (t, _) in enumerate(P6_OUT_OF_LIMITS)])
def test_p6_validators_reject_out_of_limit_messages(t, m):
    try:
        ok = P6_VALIDATORS[t](m)
    except (KeyError, TypeError):
        ok = False
    assert not ok


def test_p6_validators_accept_the_limits_themselves():
    car = lambda **kw: p6("rhack", 0, **kw)  # noqa: E731
    assert all(rhack_ok(car(hack=h, id=0, target="18446744073709551615", secs=s)) for h in VEHICLE_HACKS for s in (1, SECS_MAX))
    assert rhack_ok({"t": "rhack", "id": 1, "target": "1", "hack": "cam_feed"})
    for hack in AREA_HACKS:
        low = 1 if hack in TIMED_AREA_HACKS else 0
        for r, secs in ((1, low), (150, SECS_MAX)):
            assert rhack_ok({"t": "rhack", "id": 2**31 - 1, "target": None, "hack": hack, "p": [-29_999_999, -64, 29_999_999], "r": r, "secs": secs}), hack
    assert set(TIMED_AREA_HACKS) < set(AREA_HACKS) and rhack_ok(p6("rhack", 2, hack="ping_area", secs=0)) and rhack_ok(p6("rhack", 2, hack="outline", secs=0))
    assert daemon_ok(p6("daemon", 0, list=["a", "b_2", "x" * 32], strength=3)) and daemon_ok(p6("daemon", 0, list=["alarm"], strength=1)) and daemon_ok(p6("daemon", 0, list=["z"], id=0))
    assert feedreq_ok({"t": "feedreq", "on": False}) and feed_ok(p6("feed", px=base64.b64encode(bytes(FEED_BYTES)).decode()))
    assert hackresult_ok({"t": "hackresult", "id": 0, "ok": False, "reason": "x" * 64}) and hackresult_ok({"t": "hackresult", "id": 7, "ok": True, "reason": ""})


def _bad_acts():
    hack = lambda **kw: act("rhack", 0, **kw)  # noqa: E731
    return [
        ("rhack", hack(target=-1)), ("rhack", hack(target="212")), ("rhack", hack(target=1.5)), ("rhack", {**hack(), "owner": "x"}), ("rhack", {"hack": "veh_brake"}),
        ("rhack", {"hack": "cam_feed", "target": 5}), ("rhack", {"hack": "cam_feed", "pos": [1, 2]}), ("rhack", {"hack": "cam_feed", "pos": [0.5, 2, 3]}),
        ("rhack", {"hack": "cam_feed", "pos": [POS_LIMIT + 1, 0, 0]}), ("rhack", act("rhack", 2, r=0)), ("rhack", act("rhack", 2, r=151)), ("rhack", act("rhack", 2, r=1.5)),
        ("rhack", {k: v for k, v in act("rhack", 2).items() if k != "r"}), ("rhack", {**act("rhack", 2), "secs": 60}), ("rhack", {"hack": "nuke", "pos": [0, 0, 0], "r": 5}),
        ("breach", {"op": "pick", "id": 12}), ("breach", act("breach", 0, cell=49)), ("breach", act("breach", 0, cell=-1)), ("breach", act("breach", 0, cell=1.5)),
        ("breach", act("breach", 0, id=-1)), ("breach", {"op": "abort", "id": 12, "cell": 3}), ("breach", {"op": "solve", "id": 12}), ("breach", {"op": "pick", "cell": 1}),
        ("feedreq", act("feedreq", owner="3F2A8D1C-5B7E-4C1A-9D3E-0A1B2C3D4E5F")), ("feedreq", act("feedreq", owner="3f2a8d1c5b7e4c1a9d3e0a1b2c3d4e5f")),
        ("feedreq", act("feedreq", owner=None)), ("feedreq", act("feedreq", on=1)), ("feedreq", {"on": True}), ("feedreq", {**act("feedreq"), "x": 1}),
        ("comms", act("comms", text="x" * 81)), ("comms", act("comms", text="", pos=None)), ("comms", act("comms", pos=[1, 2])), ("comms", act("comms", pos=[0.5, 0, 0])),
        ("comms", act("comms", to="nobody")), ("comms", act("comms", text=None)), ("comms", {k: v for k, v in act("comms").items() if k != "pos"}),
    ]


@pytest.mark.parametrize("t, m", _bad_acts(), ids=lambda v: v[0] if isinstance(v, str) else "act")
def test_p6_act_validators_reject_out_of_limit_payloads(t, m):
    try:
        ok = ACT_VALIDATORS[t](m)
    except (KeyError, TypeError):
        ok = False
    assert not ok, m


def test_p6_act_validators_accept_the_limits_themselves():
    assert act_comms_ok({"to": "0" * 8 + "-" + "0000-" * 3 + "0" * 12, "text": "x" * TEXT_CAP, "pos": None}) and act_comms_ok(act("comms", text="", pos=[-POS_LIMIT, 0, POS_LIMIT]))
    assert act_breach_ok(act("breach", 0, cell=0, id=0)) and act_breach_ok(act("breach", 0, cell=48)) and act_breach_ok({"op": "abort", "id": 2**31 - 1})
    assert all(act_rhack_ok({"hack": h, "pos": [0, 0, 0], "r": r}) for h in AREA_HACKS for r in (1, 150)) and act_rhack_ok({"hack": "veh_start", "target": 0})
    assert act_feedreq_ok(act("feedreq", on=False))


def _bad_ui():
    b = lambda **kw: ("breach", ui("breach", **kw))  # noqa: E731
    return [
        b(size=4), b(size=8), b(size=6), b(buffer=3), b(buffer=9), b(grid=["1C"] * 24), b(grid=["1C"] * 24 + ["ZZ"]), b(grid=["1c"] * 25), b(picks=[25]), b(picks=[-1]),
        b(picks=[2, 2]), b(picks=[0, 1, 2, 3, 4, 5, 6]), b(seqs=[]), b(seqs=[{"id": "a", "codes": ["BD"], "ok": False}]), b(seqs=[{"id": "A", "codes": ["BD", "1C"], "ok": False}]),
        b(seqs=[{"id": "a", "codes": ["BD", "1C"], "ok": 1}]), b(seqs=[{"id": "a", "codes": ["BD", "1C", "55", "7A", "E9", "FF", "55"], "ok": False}]),
        b(seqs=[{"id": f"d{i}", "codes": ["BD", "1C"], "ok": False} for i in range(4)]), b(total=0), b(total=301), b(left=-1), b(left=46), b(state="won"), b(strength=4),
        b(strength=-1), b(dev="-1"), b(dev="00"), b(id=-1), b(size=3, dev="0", grid=["1C"] * 9, picks=[]),
        b(size=4, grid=["1C"] * 16, picks=[], seqs=[{"id": "a", "codes": ["BD", "1C"], "ok": False}]), ("breach", {k: v for k, v in ui("breach").items() if k != "left"}), ("breach", {**ui("breach"), "extra": 1}),
    ] + [
        ("feed", ui("feed", owner="x")), ("feed", ui("feed", w=127)), ("feed", ui("feed", px="AA==")), ("feed", {k: v for k, v in ui("feed").items() if k != "owner"}),
        ("comms", ui("comms", text="x" * 81)), ("comms", ui("comms", **{"from": ""})), ("comms", ui("comms", **{"from": "x" * 33})), ("comms", ui("comms", pos=[1, 2, 3.5])),
        ("comms", {k: v for k, v in ui("comms").items() if k != "pos"}),
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
    assert ui_comms_ok(ui("comms", text="", pos=None, **{"from": "x" * 32})) and ui_comms_ok(ui("comms", text="x" * TEXT_CAP))
