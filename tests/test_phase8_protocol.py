"""Phase 8 protocol (megaplan task 8-P1): the rows, examples and validators of `spawn`, `spawnresult`, `heat`, `attitude` and `glitch`, the class and rate rows, the
`hs_ack` feature bit and the 19th cyberware id, `netrunner`. Plan 2026-10-04 megaplan sections 1.8 and 1.9 (rows 8), 1.17 and 12, with the Phase 0 amendment
(fallbacks (d) and (e): no turret record, attitude and wanted level through the game's own systems)."""
import copy
import re

import pytest

from test_protocol_md import (CYBERWARE, CYBERWARE_IDS, L36_CP_EXAMPLES, L36_MC_EXAMPLES, MP_CP, MP_MC, MP_PAYLOADS, MP_SECURITY, TEXT, class_rows, documented,
                              examples, num, rate_rows, rows_by_type, section, vec3, whole)

P8_CP_TO_MC, P8_MC_TO_CP = "Phase 8: Cyberpunk → Minecraft", "Phase 8: Minecraft → Cyberpunk"
P8_CP_EXAMPLES, P8_MC_EXAMPLES = "Phase 8 examples, Cyberpunk to Minecraft:", "Phase 8 examples, Minecraft to Cyberpunk:"
P8_SECTION, P8_DIFF, P8_LOGS = "Phase 8 semantics and limits", "Phase 8 differences from megaplan section 1 (for the integrator)", "Phase 8 log lines"
P8_HEADING = "## Phase 8 (roles and modes)"


def p8_names(label: str) -> list[str]:
    """The backticked words after the dash of a Phase 8 name line (they may start with a digit: `6thStreet`, `0`)."""
    line = re.search(rf"^- \*\*{re.escape(label)}\*\*.*$", section("Phase 8 names"), re.M).group(0)
    return re.findall(r"`([A-Za-z0-9_]+)`", line.split("—", 1)[1])


SPAWN_RECORDS, STANCES = p8_names("Spawn records"), p8_names("Attitude stances")
GROUPS, TIERS = p8_names("Attitude groups"), p8_names("Heat tiers")
REC_RE, FACTION_RE, GROUP_RE = re.compile(r"[a-z0-9_]{1,32}"), re.compile(r"[A-Za-z0-9_]{0,32}"), re.compile(r"[A-Za-z0-9_]{1,32}")
MAX_SPAWN, MAX_REASON, MAX_GLITCH = 8, 64, 10


# ---- validators ---------------------------------------------------------------------------------------------------------------------
def spawn_ok(m) -> bool:
    """DOWN `spawn`: a request id, a record name (an unknown one is answered, so any well-formed string passes), a position, 1..8, hostile, a faction."""
    return (isinstance(m, dict) and set(m) == {"t", "id", "rec", "p", "n", "hostile", "faction"} and whole(m["id"]) and isinstance(m["rec"], str)
            and REC_RE.fullmatch(m["rec"]) is not None and vec3(m["p"]) and whole(m["n"], 1, MAX_SPAWN) and isinstance(m["hostile"], bool)
            and isinstance(m["faction"], str) and FACTION_RE.fullmatch(m["faction"]) is not None)


def spawnresult_ok(m) -> bool:
    """UP `spawnresult`: `ok` exactly when `n` is 1..8, and the reason is empty exactly when `ok`."""
    return (isinstance(m, dict) and set(m) == {"t", "id", "ok", "n", "reason"} and whole(m["id"]) and isinstance(m["ok"], bool) and whole(m["n"], 0, MAX_SPAWN)
            and (m["n"] >= 1) == m["ok"] and isinstance(m["reason"], str) and len(m["reason"]) <= MAX_REASON and (m["reason"] == "") == m["ok"])


def heat_ok(m) -> bool:
    return isinstance(m, dict) and set(m) == {"t", "tier"} and whole(m["tier"], 0, 5)


def attitude_ok(m) -> bool:
    return (isinstance(m, dict) and set(m) == {"t", "faction", "toward"} and isinstance(m["faction"], str) and GROUP_RE.fullmatch(m["faction"]) is not None
            and m["toward"] in STANCES)


def glitch_ok(m) -> bool:
    return isinstance(m, dict) and set(m) == {"t", "seconds"} and num(m["seconds"], 0, MAX_GLITCH)


P8_VALIDATORS = {"spawn": spawn_ok, "spawnresult": spawnresult_ok, "heat": heat_ok, "attitude": attitude_ok, "glitch": glitch_ok}
P8_EXAMPLES = [(m["t"], m) for first in (P8_CP_EXAMPLES, P8_MC_EXAMPLES) for m in examples(first)]


def p8(t, index=0, **changes):
    m = copy.deepcopy([m for u, m in P8_EXAMPLES if u == t][index])
    m.update(changes)
    return m


# ---- the tables, examples and names ------------------------------------------------------------------------------------------------------
def test_p8_every_type_has_a_row_an_example_and_a_validator():
    assert documented(P8_CP_TO_MC) == {"spawnresult"} and documented(P8_MC_TO_CP) == {"spawn", "heat", "attitude", "glitch"}
    assert {t for t, _ in P8_EXAMPLES} == set(P8_VALIDATORS)
    rows = {**rows_by_type(P8_CP_TO_MC), **rows_by_type(P8_MC_TO_CP)}
    assert all("**extends" not in row for row in rows.values())  # five new messages, none extends an older row


def test_p8_every_example_field_is_named_in_its_row():
    rows = {**rows_by_type(P8_CP_TO_MC), **rows_by_type(P8_MC_TO_CP)}
    for t, m in P8_EXAMPLES:
        for key in set(m) - {"t"}:
            assert re.search(rf"`{key}\b", rows[t]), f"{t}.{key} is not described in its row"


@pytest.mark.parametrize("t, m", P8_EXAMPLES, ids=[f"{t}-{i}" for i, (t, _) in enumerate(P8_EXAMPLES)])
def test_p8_example_obeys_the_documented_limits(t, m):
    assert P8_VALIDATORS[t](m)


def test_p8_names_have_the_plan_values():
    # megaplan 1.9 row 8 minus the turret of Phase 0 check (d), plus the heavy NCPD unit 8D and 8S accept (a record, never a `heavy` flag)
    assert SPAWN_RECORDS == ["ganger", "ncpd", "ncpd_heavy", "maxtac", "drone"] and "turret" not in SPAWN_RECORDS
    assert STANCES == ["friendly", "neutral", "hostile"] and TIERS == ["0", "1", "2", "3", "4", "5"]
    assert GROUPS == ["tygerClaws", "maelstrom", "valentinos", "6thStreet", "animals", "voodooBoys", "scavenger", "wraiths", "aldecaldos", "kangtao", "kurtz_army",
                      "arasaka", "militech", "police", "traumateam", "civilian"]  # Phase 0 check (e), without V's own group `player`
    assert all(GROUP_RE.fullmatch(g) for g in GROUPS) and len(set(GROUPS)) == len(GROUPS)


def test_p8_the_examples_tell_one_story():
    spawns, results = [m for t, m in P8_EXAMPLES if t == "spawn"], [m for t, m in P8_EXAMPLES if t == "spawnresult"]
    assert [m["rec"] for m in spawns] == ["ganger", "drone", "maxtac"] and {m["rec"] for m in spawns} <= set(SPAWN_RECORDS)
    assert len({m["id"] for m in spawns}) == len(spawns)  # one request id each
    assert [m["ok"] for m in results] == [True, False] and results[0]["id"] == spawns[0]["id"] and results[1]["id"] == spawns[1]["id"]  # a request and its answer share the id
    assert results[0]["n"] == spawns[0]["n"] == 3 and results[1]["n"] == 0 and results[1]["reason"] == "no ground"
    assert spawns[0]["hostile"] is True and spawns[1]["hostile"] is False and spawns[2]["faction"] == ""  # a squad that fights, a drone that stands about, the default gang
    assert {m["faction"] for m in spawns if m["faction"]} <= set(GROUPS)
    assert [m["tier"] for t, m in P8_EXAMPLES if t == "heat"] == [4, 0]  # Max-Tac's tier and the calm after it
    assert [m["seconds"] for t, m in P8_EXAMPLES if t == "glitch"] == [4.5, 0]  # a glitch, and its end
    assert [m["faction"] for t, m in P8_EXAMPLES if t == "attitude"] == ["tygerClaws"] and p8("attitude")["faction"] in GROUPS


def test_p8_rules_are_stated_in_the_document():
    intro = TEXT.split(P8_HEADING, 1)[1].split("###", 1)[0]
    text = " ".join((intro + section(P8_SECTION) + section(P8_CP_TO_MC) + section(P8_MC_TO_CP) + section(P8_LOGS)).split())
    for phrase in ("Five things cross the DLL wire", "exactly **one `spawnresult` for every `spawn`**", "`turret` is **not** a `rec`", "Minecraft-side only", "answered `ok:false` with `reason:\"unsupported\"`",
                   "`hostile:true` puts them in combat", "every one is deleted when the session ends", "the 3 second warning", "only to players **in an active match who opted in**",
                   "The DLL checks none of them", "`/cyberblock guests spawns on|off`", "off by default", "Minecraft squads", "The **fallback of Phase 0 check (d)**",
                   "`spawn: path: ...`", "the wanted level 0..5 the server chose for this host", "`top_chaos_wanted`",
                   "`heat` and `attitude` are **not** gated by it", "never a flag", "outside the five is answered", "its real wanted level", "`SetWantedLevel`", "again after every host `hello`", "put it back when the session leaves `host`",
                   "no write is the written fallback of check (e)", "`AttitudeSystem.SetAttitudeRelation`", "the `...Persistent` variants are never used", "`player` is V's own group",
                   "restore it", "no attitude write is the written fallback", "does not change how Night City treats its hosts", "`cb_glitch`", "a new `glitch` replaces the running one",
                   "`0` ends it", "a Minecraft-only victim gets Minecraft's own overlay", "delete every NPC they spawned", "restore every attitude", "is ignored", "never fatal",
                   "bit 32 (modes)", "a 19th id, `netrunner`", "resists player hacks", "`firewall` **blocks** them", "a DLL that does not know it drops that one entry",
                   "Rate row: 10 per second, burst 40", "is dropped and counted", "one line saying which runtime path is active", "Reboot Optics", "through Steve's hearts",
                   "`/cyberblock heat set <0..100>`", "`/cyberblock test match start <mode>`", "`/cyberblock test overlord spawn <palette id> <player>`",
                   "`/cyberblock spectate stop`", "`/cyberblock overlord optin on`"):
        assert phrase.lower() in text.lower(), f"{phrase!r} is not stated"
    for phrase in ("No `turret` in `spawn.rec`", "`attitude` says `toward`", "`spawnresult.n` is the native's `count`", "A rate for `spawnresult`", "The `spawns` toggle gates only `spawn`",
                   "`ncpd_heavy` is a fifth `rec`", "`heat.tier` is a wanted level",
                   "`heat` is sent again after every `hello`", "`PHASE8_COMMANDS`"):
        assert phrase in section(P8_DIFF), f"{phrase!r} is not in the differences list"


def test_p8_class_rate_and_feature_rows():
    cp, mc = dict(class_rows(MP_CP)), dict(class_rows(MP_MC))
    assert cp["spawnresult"] == "UP" and mc["spawn"] == mc["heat"] == mc["attitude"] == mc["glitch"] == "DOWN"
    assert rate_rows()["spawnresult"] == (10, 40) and rate_rows()["devresult"] == (10, 40)
    assert "32 modes" in " ".join(section(MP_PAYLOADS).split())  # hs_ack.features: ... 32 modes
    assert "`spawnresult`" in section(MP_SECURITY) and len("spawnresult") <= 16  # the envelope `t` is at most 16 characters
    assert "`spawns`" in section("Multiplayer: new messages")  # session.perms keeps its `spawns` bit, which this phase starts to use


def test_p8_the_netrunner_implant_is_the_19th_cyberware_id_in_every_place_the_document_lists_it():
    assert CYBERWARE == CYBERWARE_IDS and len(CYBERWARE_IDS) == 19 and CYBERWARE_IDS[17:] == ["firewall", "netrunner"]
    old = [m for first in (L36_CP_EXAMPLES, L36_MC_EXAMPLES) for m in examples(first) if m["t"] == "cyberware"]
    assert len(old) == 1 and [e["id"] for e in old[0]["list"]] == CYBERWARE_IDS and old[0]["list"][-1] == {"id": "netrunner", "on": 0}
    flat = " ".join(TEXT.split())
    assert "one of the 19 cyberware ids (below; `firewall` is the 18th, Phase 6, and `netrunner` the 19th, Phase 8)" in flat
    assert "Phase 8 `netrunner` as the 19th" in flat and "must be one of the 19 cyberware ids" in flat and "one of the 18 cyberware ids" not in flat


def test_p8_section_sits_after_phase_7_and_before_the_shared_memory_section():
    assert TEXT.index("## Phase 7 (NPC interaction, the Afterlife hub and the Holo-Table)") < TEXT.index(P8_HEADING) < TEXT.index("## Shared memory")
    assert "2026-10-04-multiplayer-megaplan.md" in TEXT.split(P8_HEADING, 1)[1].split("###", 1)[0]
    for heading in ("Phase 8: Cyberpunk → Minecraft", "Phase 8: Minecraft → Cyberpunk", P8_SECTION, P8_DIFF, "Phase 8 names", P8_LOGS):
        assert section(heading).strip()


# ---- the limits, both sides of each ---------------------------------------------------------------------------------------------------
def _p8_out_of_limits():
    return [
        ("spawn", p8("spawn", id=-1)), ("spawn", p8("spawn", id=1.5)), ("spawn", p8("spawn", id=True)), ("spawn", p8("spawn", rec="")), ("spawn", p8("spawn", rec="Ganger")),
        ("spawn", p8("spawn", rec="a" * 33)), ("spawn", p8("spawn", rec="gang er")), ("spawn", p8("spawn", rec=7)), ("spawn", p8("spawn", n=0)), ("spawn", p8("spawn", n=9)),
        ("spawn", p8("spawn", n=2.5)), ("spawn", p8("spawn", n=True)), ("spawn", p8("spawn", hostile=1)), ("spawn", p8("spawn", hostile="true")),
        ("spawn", p8("spawn", faction="Tyger Claws")), ("spawn", p8("spawn", faction="a" * 33)), ("spawn", p8("spawn", faction=None)),
        ("spawn", p8("spawn", p=[1.0, 2.0])), ("spawn", p8("spawn", p=[3e7, 0.0, 0.0])), ("spawn", p8("spawn", p=[float("nan"), 0.0, 0.0])), ("spawn", p8("spawn", p="1,2,3")),
        ("spawn", {k: v for k, v in p8("spawn").items() if k != "faction"}), ("spawn", {**p8("spawn"), "extra": 1}), ("spawn", {**p8("spawn"), "heavy": True}), ("spawn", {k: v for k, v in p8("spawn").items() if k != "n"}),
        ("spawnresult", p8("spawnresult", id=-1)), ("spawnresult", p8("spawnresult", ok=1)), ("spawnresult", p8("spawnresult", n=9)), ("spawnresult", p8("spawnresult", n=-1)),
        ("spawnresult", p8("spawnresult", n=0)), ("spawnresult", p8("spawnresult", 1, n=2)), ("spawnresult", p8("spawnresult", reason="x")),  # ok with n 0, a failure with n 2 and an ok with a reason
        ("spawnresult", p8("spawnresult", 1, reason="")), ("spawnresult", p8("spawnresult", 1, reason="é" * 65)), ("spawnresult", p8("spawnresult", reason=3)),
        ("spawnresult", {k: v for k, v in p8("spawnresult").items() if k != "reason"}), ("spawnresult", {**p8("spawnresult"), "count": 3}),
        ("heat", p8("heat", tier=-1)), ("heat", p8("heat", tier=6)), ("heat", p8("heat", tier=2.5)), ("heat", p8("heat", tier="4")), ("heat", p8("heat", tier=True)),
        ("heat", {"t": "heat"}), ("heat", {**p8("heat"), "wanted": 4}),
        ("attitude", p8("attitude", faction="")), ("attitude", p8("attitude", faction="Tyger Claws")), ("attitude", p8("attitude", faction="a" * 33)),
        ("attitude", p8("attitude", toward="angry")), ("attitude", p8("attitude", toward="Friendly")), ("attitude", p8("attitude", toward=1)),
        ("attitude", {k: v for k, v in p8("attitude").items() if k != "toward"}), ("attitude", {**p8("attitude"), "delta": 1}),
        ("glitch", p8("glitch", seconds=-0.1)), ("glitch", p8("glitch", seconds=10.1)), ("glitch", p8("glitch", seconds=float("nan"))), ("glitch", p8("glitch", seconds=float("inf"))),
        ("glitch", p8("glitch", seconds=True)), ("glitch", p8("glitch", seconds="3")), ("glitch", {"t": "glitch"}), ("glitch", {**p8("glitch"), "kind": "optics"}),
    ]


P8_OUT_OF_LIMITS = _p8_out_of_limits()


@pytest.mark.parametrize("t, m", P8_OUT_OF_LIMITS, ids=[f"{t}-{i}" for i, (t, _) in enumerate(P8_OUT_OF_LIMITS)])
def test_p8_validators_reject_out_of_limit_messages(t, m):
    try:
        ok = P8_VALIDATORS[t](m)
    except (KeyError, TypeError):
        ok = False
    assert not ok


def test_p8_validators_accept_the_limits_themselves():
    assert spawn_ok(p8("spawn", n=1)) and spawn_ok(p8("spawn", n=8)) and spawn_ok(p8("spawn", id=0)) and spawn_ok(p8("spawn", id=2**31 - 1))
    assert spawn_ok(p8("spawn", rec="a" * 32)) and spawn_ok(p8("spawn", rec="turret")) and spawn_ok(p8("spawn", faction="")) and spawn_ok(p8("spawn", faction="Z_9" * 10 + "ab"))
    assert spawn_ok(p8("spawn", p=[-29999999.5, 0.0, 29999999.5])) and spawn_ok(p8("spawn", hostile=False))  # an unknown `rec` is answered `unsupported`, so it is valid on the wire
    assert spawnresult_ok(p8("spawnresult", n=1)) and spawnresult_ok(p8("spawnresult", n=8)) and spawnresult_ok(p8("spawnresult", 1, reason="é" * 64))
    assert spawnresult_ok(p8("spawnresult", id=0)) and all(heat_ok({"t": "heat", "tier": v}) for v in range(6))
    assert all(attitude_ok({"t": "attitude", "faction": g, "toward": s}) for g in GROUPS for s in STANCES) and attitude_ok(p8("attitude", faction="z" * 32))
    assert glitch_ok(p8("glitch", seconds=0)) and glitch_ok(p8("glitch", seconds=10)) and glitch_ok(p8("glitch", seconds=0.001)) and glitch_ok(p8("glitch", seconds=10.0))
