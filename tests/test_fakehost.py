import asyncio
import json
import math
import uuid

import numpy as np
import pytest
from PIL import Image
from websockets.asyncio.server import serve

from test_protocol_md import (uint64_string, vec3, AT_VALIDATORS, L36_EXAMPLES, L36_NEW_CP_TO_MC, MP_EXAMPLES, P3_VALIDATORS, PQ_EXAMPLES, PQ_VALIDATORS, R3_EXT_CP,  # shared/protocol.md:
                              R3_NEW_CP, R3_VALIDATORS, VALIDATORS, mp_hello_ok, vdbg_ok)  # the example messages and documented limits of
from tools import fakehost, package, shm  # layers 3-6, re-test 3, the Atlas, Phase Q and Multiplayer

HELLO = {"t": "hello", "proto": 1, "mod": "cyberblock", "mc": "26.3"}
STATUS = {"t": "status", "hp": 20, "maxHp": 20, "food": 20, "gm": "survival", "screen": False}
W, H = 8, 4


def run_against_fake_minecraft(out, frames, shm_name, replies=(), react=None, **kwargs):
    """fakehost.run against a local server that plays Minecraft's part: it sends `replies` at once (e.g. layer 2 events
    like an explosion) and, for every message it receives, whatever `react(message)` returns; returns (frame sets saved,
    messages received)."""
    received = []

    async def minecraft(ws):
        await ws.send(json.dumps(HELLO))
        await ws.send(json.dumps(STATUS))
        for m in replies:
            await ws.send(json.dumps(m))
        async for raw in ws:
            received.append(json.loads(raw))
            for m in react(received[-1]) if react else ():
                await ws.send(json.dumps(m))

    async def go():
        async with serve(minecraft, "127.0.0.1", 0) as server:
            url = f"ws://127.0.0.1:{server.sockets[0].getsockname()[1]}"
            return await fakehost.run(url, frames, out, fps=1000, shm_name=shm_name, **kwargs)

    return asyncio.run(go()), received


@pytest.fixture
def exporting():
    """A mapping under a throwaway name holding one frame: left half opaque orange and near, right half empty."""
    name = f"Local\\CyberblockTest-{uuid.uuid4().hex}"
    mm = shm.create_mapping(name, W, H)
    color = np.zeros((H, W, 4), np.uint8)
    color[:, : W // 2] = (200, 100, 50, 255)
    depth = np.zeros((H, W), np.float32)
    depth[:, : W // 2] = 1.0  # reversed-Z: 1 = at the near plane
    overlay = np.zeros((H, W, 4), np.uint8)
    overlay[0, :] = (255, 255, 255, 255)
    shm.write_test_frame(mm, color, depth, overlay, host_frame=30)
    yield name
    mm.close()


def test_sends_hello_view_ground_and_a_camera_orbit(tmp_path, exporting):
    saved, got = run_against_fake_minecraft(tmp_path, 60, exporting)
    assert saved == 2
    assert [m["t"] for m in got[:5]] == ["hello", "view", "clear", "state", "hud"]
    assert got[0]["proto"] == 1 and got[0]["game"] == "cp2077" and (got[1]["w"], got[1]["h"]) == (1280, 720)
    assert got[4 + len(fakehost.SETUP_COMMANDS) + 1]["t"] == "ground"
    ground = next(m for m in got if m["t"] == "ground")["c"]
    assert len(ground) == 17 * 17 * 4 and ground[:4] == [-8, -8, 63, 63] and ground[-4:] == [8, 8, 63, 63]
    cams = [m for m in got if m["t"] == "cam"]
    assert [m["f"] for m in cams] == list(range(1, 61))
    assert all(m["fp"] is True and m["fov"] == 70.0 and m["r"][2] == 0.0 for m in cams)


def test_camera_keeps_looking_at_the_centre_from_the_orbit_radius():
    for f in range(1, 121):
        cam = fakehost.orbit_cam(f, 120)
        yaw, pitch = map(math.radians, cam["r"][:2])
        fwd = (-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch))  # Minecraft look vector
        to_centre = [c - p for c, p in zip(fakehost.CENTER, cam["p"])]
        norm = math.dist(to_centre, (0, 0, 0))
        assert sum(a * b / norm for a, b in zip(fwd, to_centre)) == pytest.approx(1.0, abs=1e-9)
        assert math.hypot(cam["p"][0], cam["p"][2]) == pytest.approx(fakehost.RADIUS)
    assert fakehost.orbit_cam(1, 120)["p"] == pytest.approx(fakehost.orbit_cam(121, 120)["p"])  # one full revolution


def test_saves_colour_depth_and_overlay_pngs(tmp_path, exporting):
    run_against_fake_minecraft(tmp_path, 60, exporting)
    assert sorted(p.name for p in tmp_path.iterdir()) == [f"frame_{f:03d}_{k}.png" for f in (30, 60) for k in ("color", "depth", "overlay")]
    color = np.asarray(Image.open(tmp_path / "frame_030_color.png"))
    assert tuple(color[0, 0]) == (200, 100, 50) and tuple(color[0, -1]) == (40, 40, 48)  # drawn, then composited over grey
    depth = np.asarray(Image.open(tmp_path / "frame_030_depth.png"))
    assert depth.shape == (H, W) and depth[0, 0] > 250 and depth[0, -1] == 0  # near is white, nothing is black
    overlay = np.asarray(Image.open(tmp_path / "frame_030_overlay.png"))
    assert tuple(overlay[0, 0]) == (255, 255, 255) and tuple(overlay[-1, 0]) == (40, 40, 48)


def test_no_setup_commands_when_disabled(tmp_path, exporting):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, setup=False)
    assert not [m for m in got if m["t"] == "cmd"]


def test_saves_nothing_and_reports_when_minecraft_publishes_no_frame(tmp_path):
    saved, got = run_against_fake_minecraft(tmp_path, 30, f"Local\\CyberblockTest-{uuid.uuid4().hex}")
    assert saved == 0 and not list(tmp_path.iterdir()) and len([m for m in got if m["t"] == "cam"]) == 30


def test_unreachable_minecraft_is_a_clean_failure(capsys, monkeypatch):
    monkeypatch.setattr("sys.argv", ["fakehost", "--url", "ws://127.0.0.1:1", "--frames", "1"])
    assert fakehost.main() == 1
    assert "no connection to Minecraft" in capsys.readouterr().err


# ---- --layer2: NPC proxies and a bullet hit sent to Minecraft, Minecraft's block/explosion/hit/ignite events printed ----
EVENTS = [
    {"t": "block", "op": "add", "pos": [10, 4, -21]},
    {"t": "explosion", "pos": [10.5, 4.0, -20.5], "power": 4.0, "fire": False},
    {"t": "hit", "target": "9007199254740993", "dmg": 7.0, "kind": "melee", "impulse": [0.0, 1.5, 6.0]},
    {"t": "ignite", "target": "9007199254740993", "seconds": 8.0},
]


def types(messages):
    return [m["t"] for m in messages]


def test_layer2_sends_three_npcs_then_a_bullet_hit_after_the_layer1_script(tmp_path, exporting):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.05)
    t = types(got)
    assert t[:5] == ["hello", "view", "clear", "state", "hud"]
    first_npcs, first_dmg = t.index("npcs"), t.index("dmg")
    last_orbit_cam = max(i for i, m in enumerate(got) if m["t"] == "cam" and m["f"] <= 30)
    assert t.index("ground") < t.index("cam") < last_orbit_cam < first_npcs
    assert first_dmg == first_npcs + 1 and t.count("dmg") == 1
    npcs = got[first_npcs]["list"]
    assert [e["kind"] for e in npcs] == ["human", "human", "vehicle"] and any(e["hostile"] for e in npcs)
    dmg = got[first_dmg]
    assert dmg["frac"] == 0.25 and dmg["kind"] == "bullet" and len(dmg["from"]) == 3


def test_layer2_npcs_are_valid_and_stand_clear_of_the_setup_blocks():
    msg = fakehost.npcs_message()
    assert msg["t"] == "npcs" and len(msg["list"]) == 3 and len({e["id"] for e in msg["list"]}) == 3
    blocks = [tuple(int(v) for v in c.split()[1:4]) for c in fakehost.SETUP_COMMANDS if c.startswith("setblock")]
    for e in msg["list"]:
        assert set(e) == {"id", "p", "yaw", "kind", "hostile"} and e["id"].isdigit() and int(e["id"]) < 2**64
        x, y, z = e["p"]
        assert y == fakehost.SLAB_Y + 1 and abs(x) <= fakehost.SLAB_HALF and abs(z) <= fakehost.SLAB_HALF  # standing on the slab
        width, height = (0.6, 1.8) if e["kind"] == "human" else (2.0, 1.6)  # the proxy hitboxes of shared/protocol.md
        for bx, by, bz in blocks:
            assert not (abs(x - (bx + 0.5)) < 0.5 + width / 2 and abs(z - (bz + 0.5)) < 0.5 + width / 2 and y < by + 1 and y + height > by)


def test_layer2_holds_the_last_camera_and_resends_the_npcs_while_listening(tmp_path, exporting):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.3)
    cams = [m for m in got if m["t"] == "cam"]
    assert len(cams) > 100 and [m["f"] for m in cams] == list(range(1, len(cams) + 1))
    assert all(m["p"] == cams[29]["p"] and m["r"] == cams[29]["r"] for m in cams[29:])  # held at the last orbit pose
    assert 2 <= types(got).count("npcs") <= 6  # about 10 Hz: the first at once, then one per 100 ms


def test_layer2_prints_every_event_minecraft_reports(tmp_path, exporting, capsys):
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=EVENTS, hold=0.1)
    lines = capsys.readouterr().out.splitlines()
    for event in EVENTS:
        line = next(l for l in lines if l.startswith(f"{event['t']}: "))
        assert json.loads(line.split(": ", 1)[1]) == event


def test_layer2_commands_are_sent_after_the_dmg_in_order(tmp_path, exporting):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.05, commands=["summon tnt 0 64 0", "kill @e[type=tnt]"])
    after = got[types(got).index("dmg") + 1:]
    assert [m["c"] for m in after if m["t"] == "cmd"] == ["summon tnt 0 64 0", "kill @e[type=tnt]"]


def test_layer1_run_sends_no_layer2_messages(tmp_path, exporting):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting)
    assert not {"npcs", "dmg"} & set(types(got))


def test_layer2_options_are_checked(monkeypatch, capsys):
    for argv in (["--cmd", "say hi"], ["--layer2", "--hold", "-1"]):
        monkeypatch.setattr("sys.argv", ["fakehost", *argv])
        with pytest.raises(SystemExit) as e:
            fakehost.main()
        assert e.value.code == 2
    monkeypatch.setattr("sys.argv", ["fakehost", "--layer2", "--url", "ws://127.0.0.1:1", "--frames", "1"])
    assert fakehost.main() == 1 and "no connection to Minecraft" in capsys.readouterr().err


# ---- --layer3 .. --layer6 (plan 2026-10-03 track P3): Cyberpunk's scripted messages and answers, Minecraft's events printed ----
def example_messages(*wanted):
    """The protocol.md examples (layers 3-6) of the given types, as Minecraft would send them."""
    return [m for t, m in L36_EXAMPLES if t in wanted]


def hold_cams(got):
    return [m for m in got if m["t"] == "cam" and m["f"] > 30]  # after the 30 orbit frames


def printed(capsys):
    """The `<type>: <json>` lines of Minecraft's events that fakehost printed, as {type: [messages]} (`hello` and `status`
    lines are printed as Python dicts and are not events)."""
    out = {}
    for line in capsys.readouterr().out.splitlines():
        head, _, tail = line.partition(": ")
        if head.isidentifier() and head not in ("hello", "status") and tail.startswith("{"):
            out.setdefault(head, []).append(json.loads(tail))
    return out


@pytest.fixture
def quick(monkeypatch):
    """The layer 4 and 6 scripts at 1/20 of their real speed, so that a hold of 0.3 s sees all of them."""
    monkeypatch.setattr(fakehost, "SCRIPT_SCALE", 0.05)


def layer_set(*numbers):
    return frozenset(numbers)


def test_layer3_sends_the_npcs_but_no_bullet_dmg_and_holds_the_orbit_pose(tmp_path, exporting):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.05, layers=layer_set(3))
    t = types(got)
    assert t.count("npcs") >= 1 and "dmg" not in t and t.index("npcs") > max(i for i, m in enumerate(got) if m["t"] == "cam" and m["f"] <= 30)
    cams = hold_cams(got)
    assert cams and all(m["p"] == got[t.index("npcs") - 1]["p"] and m["pl"][1] == fakehost.SLAB_Y + 1.0 for m in cams)  # not falling


def test_layer3_answers_the_first_non_empty_mobs_list_once_with_a_mobdmg(tmp_path, exporting):
    mob = {"id": 412, "kind": "zombie", "p": [0.5, 64.0, 0.5], "yaw": 90.0, "w": 0.6, "h": 1.95, "hp": 20, "maxHp": 20}
    react = lambda m: [{"t": "mobs", "list": [mob, {**mob, "id": 413}]}] if m["t"] == "npcs" else []  # a mobs list per npcs list
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[{"t": "mobs", "list": []}], react=react, hold=0.35, layers=layer_set(3))
    assert types(got).count("npcs") >= 3  # the resend at 10 Hz: Minecraft's mobs list arrived several times
    assert [m for m in got if m["t"] == "mobdmg"] == [{"t": "mobdmg", "id": 412, "frac": 0.5, "by": "9007199254740993"}]
    hostile_humans = [e["id"] for e in fakehost.npcs_message()["list"] if e["kind"] == "human" and e["hostile"]]
    assert hostile_humans == ["9007199254740993"] and VALIDATORS["mobdmg"]([m for m in got if m["t"] == "mobdmg"][0])


def test_layer3_ignores_a_malformed_mobs_list(tmp_path, exporting):
    bad = [{"t": "mobs", "list": [None]}, {"t": "mobs", "list": ["zombie"]}, {"t": "mobs", "list": [{"kind": "zombie"}]}, {"t": "mobs"}]
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=bad, hold=0.1, layers=layer_set(3))
    assert "mobdmg" not in types(got) and len(hold_cams(got)) > 50  # and the link is still being served


def test_layer4_pcells_is_a_3x3_wall_across_the_heading_half_a_second_ahead():
    along_z = {"id": 501, "p": [-1244.0, 66.0, 811.0], "v": [10.0, 4.0, 20.0], "g": 24.0}  # 0.5 s later: (-1239, 65, 821)
    assert fakehost.pcells_message([along_z]) == {"t": "pcells", "c": [
        -1240, 64, 821, -1239, 64, 821, -1238, 64, 821, -1240, 65, 821, -1239, 65, 821, -1238, 65, 821,
        -1240, 66, 821, -1239, 66, 821, -1238, 66, 821]}
    along_x = {"id": 2, "p": [0.2, 10.5, 0.2], "v": [-20.0, 0.0, 5.0], "g": 0.0}  # (-9.8, 10.5, 2.7): floored per axis
    assert fakehost.pcells_message([along_x])["c"] == [-10, 9, 1, -10, 9, 2, -10, 9, 3, -10, 10, 1, -10, 10, 2, -10, 10, 3, -10, 11, 1, -10, 11, 2, -10, 11, 3]


def test_layer4_pcells_deduplicates_caps_and_skips_what_is_not_a_projectile():
    p = {"id": 1, "p": [0.0, 70.0, 0.0], "v": [0.0, 0.0, 10.0], "g": 0.0}
    assert len(fakehost.pcells_message([p, dict(p, id=2)])["c"]) == 27  # the same wall twice: 9 cells once
    many = [dict(p, id=i, p=[10.0 * i, 70.0, 0.0]) for i in range(16)]
    c = fakehost.pcells_message(many)["c"]
    assert len(c) == 3 * 64 and len(set(zip(c[::3], c[1::3], c[2::3]))) == 64  # 144 cells, the first 64 kept
    assert fakehost.pcells_message([]) is None and fakehost.pcells_message([None, "x", {"p": [0, 0, 0]}, dict(p, p=[float("nan"), 0, 0])]) is None
    assert len(fakehost.pcells_message([None, p])["c"]) == 27


def test_layer4_answers_proj_with_pcells_but_not_the_empty_list(tmp_path, exporting):
    proj = {"t": "proj", "list": [{"id": 501, "p": [-1244.0, 66.0, 811.0], "v": [10.0, 4.0, 20.0], "g": 24.0}]}
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[{"t": "proj", "list": []}, proj], hold=0.1, layers=layer_set(4))
    pcells = [m for m in got if m["t"] == "pcells"]
    assert pcells == [fakehost.pcells_message(proj["list"])] and VALIDATORS["pcells"](pcells[0])


def test_layer4_replays_a_fall_at_8_m_per_s_from_200_with_a_jump_tap(tmp_path, exporting, quick):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.3, layers=layer_set(4))
    cams = hold_cams(got)
    assert len(cams) >= 250 and [m["f"] for m in cams] == list(range(31, 31 + len(cams)))
    for k in (0, 50, 200):  # frames are 1 ms apart: 8 mm of fall per frame
        assert cams[k]["pl"] == pytest.approx([-1.0, 200.0 - 0.008 * k, -1.0])
    assert all(m["p"][1] == pytest.approx(m["pl"][1] + 1.62) and m["r"] == [0.0, 0.0, 0.0] and m["cr"] is False for m in cams)
    assert all(m["fly"] is False and "lv" not in m for m in cams)  # falling is not a flight; no `lv`: Minecraft keeps its own k
    keys = [(i, m["k"], m["down"]) for i, m in enumerate(got) if m["t"] == "key"]
    assert [(k, down) for _, k, down in keys] == [("jump", True), ("jump", False)]
    before = lambda i: len([m for m in got[:i] if m["t"] == "cam" and m["f"] > 30])
    assert before(keys[0][0]) == pytest.approx(25, abs=2) and before(keys[1][0]) == pytest.approx(30, abs=2)  # 0.5 s and 0.6 s, scaled by 1/20


def test_layer4_body_falls_follows_flight_and_stays_where_it_lands():
    body = fakehost.Body()
    assert body.cam(1, 0.0)["pl"] == [-1.0, 200.0, -1.0] and body.cam(2, 2.5)["pl"][1] == 180.0
    assert body.cam(3, 1000.0)["pl"][1] == fakehost.FLOOR_Y  # it lands on the slab
    for garbage in ({"t": "flight", "active": True, "p": "nope"}, {"t": "flight", "active": True, "p": [1, 2]}, {"t": "cam", "p": [1, 2, 3]}):
        body.update(garbage)
        assert body.mode == "fall"
    assert body.cam(1, 0.0)["fly"] is False and body.cam(3, 1000.0)["fly"] is False  # falling, or landed on the slab
    body.update({"t": "flight", "active": True, "p": [1, 2, 3], "yaw": 10.0, "pitch": 5.0})
    assert body.mode == "flight" and body.cam(4, 99.0)["pl"] == [1.0, 2.0, 3.0] and body.cam(4, 99.0)["r"] == [10.0, 5.0, 0.0]
    assert body.cam(4, 99.0)["fly"] is True  # the host follows the flight: Minecraft must see `fly` within 0.5 s
    body.update({"t": "flight", "active": False, "p": [4, 5, 6]})
    assert body.mode == "ground" and body.cam(5, 0.0)["pl"] == [4.0, 5.0, 6.0] and body.cam(5, 0.0)["r"] == [10.0, 5.0, 0.0]
    assert body.cam(5, 0.0)["fly"] is False  # touchdown ends it
    body.update({"t": "teleport", "p": [7, 8, 9], "yaw": -45.0})
    assert body.mode == "ground" and body.cam(6, 0.0)["pl"] == [7.0, 8.0, 9.0] and body.cam(6, 0.0)["r"][0] == -45.0
    assert body.cam(6, 0.0)["fly"] is False


def test_layer4_body_accepts_the_flight_kind_and_sample_clock_and_ignores_bad_ones():
    """Re-test 2: `flight` carries `kind` (glide or launch) and `ts` (ms). V follows every sample either way."""
    for extra in ({"kind": "glide", "ts": 10.0}, {"kind": "launch", "ts": 81234.5}, {"kind": "fly", "ts": -1}, {"kind": 7, "ts": "late"},
                  {"ts": float("nan")}, {"kind": None}):
        body = fakehost.Body()
        body.update({"t": "flight", "active": True, "p": [5, 70, 6], "yaw": 20.0, "pitch": 3.0, **extra})
        cam = body.cam(1, 0.0)
        assert cam["pl"] == [5.0, 70.0, 6.0] and cam["fly"] is True and cam["r"] == [20.0, 3.0, 0.0], extra


def test_layer4_cam_follows_flight_then_stays_where_it_touches_down(tmp_path, exporting, quick):
    flight = {"t": "flight", "active": True, "p": [5.0, 150.0, 6.0], "v": [1.0, -2.0, 3.0], "yaw": 90.0, "pitch": 10.0}
    touchdown = {"t": "flight", "active": False, "p": [1.5, 64.0, 2.5], "v": [0.0, 0.0, 0.0], "yaw": 90.0, "pitch": 0.0}
    react = lambda m: [touchdown] if m["t"] == "key" and m["k"] == "jump" and not m["down"] else []
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[flight], react=react, hold=0.3, layers=layer_set(4))
    cams = hold_cams(got)
    flying = [i for i, m in enumerate(cams) if m["pl"] == [5.0, 150.0, 6.0]]
    landed = [i for i, m in enumerate(cams) if m["pl"] == [1.5, 64.0, 2.5]]
    assert flying and landed and flying == list(range(len(flying))) and landed == list(range(len(cams) - len(landed), len(cams)))
    assert len(flying) + len(landed) == len(cams)  # nothing in between: V never fell
    assert all(cams[i]["fly"] is True for i in flying) and all(cams[i]["fly"] is False for i in landed)  # `fly` while following only
    assert cams[0]["r"] == [90.0, 10.0, 0.0] and cams[0]["h"] == 90.0 and cams[0]["p"][1] == pytest.approx(150.0 + 1.62)
    assert cams[-1]["r"] == [90.0, 0.0, 0.0]


def test_layer4_cam_moves_to_an_ender_pearls_teleport(tmp_path, exporting):
    teleport = {"t": "teleport", "p": [9.5, 70.0, 9.5], "yaw": 45.0}
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[teleport], hold=0.1, layers=layer_set(4))
    cams = hold_cams(got)
    assert cams and all(m["pl"] == [9.5, 70.0, 9.5] and m["r"][0] == 45.0 for m in cams)


LAYER5_EVENTS = [
    {"t": "lead", "target": "9007199254740993", "anchor": None, "length": 5.0},
    {"t": "lead", "target": "9007199254740993", "release": True},
    {"t": "pull", "target": "9007199254740993", "toward": [-1245.5, 66.5, 812.25], "strength": 9.0},
    {"t": "knock", "pos": [-1240.0, 64.0, 809.0], "radius": 3.5, "strength": 14.4},
    {"t": "effect", "name": "speed", "seconds": 30.0, "amp": 1},
]


def test_layer5_prints_lead_pull_knock_and_effect_and_sends_only_the_scene(tmp_path, exporting, capsys):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=LAYER5_EVENTS, hold=0.1, layers=layer_set(5), commands=["give @s lead"])
    lines = printed(capsys)
    assert lines["lead"] == LAYER5_EVENTS[:2] and lines["pull"] == [LAYER5_EVENTS[2]]
    assert lines["knock"] == [LAYER5_EVENTS[3]] and lines["effect"] == [LAYER5_EVENTS[4]]
    assert "give @s lead" in [m["c"] for m in got if m["t"] == "cmd"]
    assert not {"key", "mobdmg", "pcells", "scaninfo", "hackresult", "killed", "dmg"} & set(types(got))  # layer 5 is print-only


def test_layer6_answers_every_scan_with_the_canned_scaninfo_of_the_first_human(tmp_path, exporting):
    scans = [{"t": "scan", "id": 7, "target": "9007199254740993"}, {"t": "scan", "id": 9, "target": None}, {"t": "scan", "id": 11, "target": None}]
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=scans, hold=0.1, layers=layer_set(6))
    infos = [m for m in got if m["t"] == "scaninfo"]
    human = next(e for e in fakehost.npcs_message()["list"] if e["kind"] == "human")
    assert [m["id"] for m in infos] == [7, 9, 11]
    for m in infos:
        assert (m["ok"], m["name"], m["faction"], m["threat"], m["hp"], m["device"]) == (True, "Officer Test", "NCPD", "high", 0.8, False)
        assert m["target"] == human["id"] and m["p"] == human["p"] and m["size"] == [0.6, 1.8] and VALIDATORS["scaninfo"](m)


def test_layer6_answers_every_hack_with_hackresult_ok(tmp_path, exporting):
    hack = {"t": "hack", "id": 8, "target": "9007199254740993", "hack": "short_circuit", "chain": False}
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[hack, {**hack, "id": 9, "chain": True}], hold=0.1, layers=layer_set(6))
    results = [m for m in got if m["t"] == "hackresult"]
    assert results == [{"t": "hackresult", "id": 8, "ok": True, "reason": ""}, {"t": "hackresult", "id": 9, "ok": True, "reason": ""}]
    assert all(VALIDATORS["hackresult"](m) for m in results)


def test_layer6_ignores_a_scan_or_hack_without_a_request_id(tmp_path, exporting):
    bad = [{"t": "scan", "target": None}, {"t": "hack", "target": "1", "hack": "ping", "chain": False}, {"t": "scan", "id": "7", "target": None}]
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[*bad, {"t": "scan", "id": 5, "target": None}], hold=0.1, layers=layer_set(6))
    assert [m["id"] for m in got if m["t"] == "scaninfo"] == [5] and "hackresult" not in types(got)


def test_layer6_presses_the_hack_and_skills_keys_then_reports_a_kill_then_the_cyberware_keys(tmp_path, exporting, quick):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.3, layers=layer_set(6))
    script = [m for m in got if m["t"] in ("key", "killed")]
    assert [(m["t"], m.get("k"), m.get("down")) for m in script] == [
        ("key", "hack", True), ("key", "hack", False), ("key", "skills", True), ("key", "skills", False), ("killed", None, None),
        ("key", "cyberware", True), ("key", "cyberware", False), ("key", "cyberware", True), ("key", "cyberware", False),  # U opens, U closes
        ("key", "activate", True), ("key", "activate", False)]  # a Y tap (released well inside 8 client ticks)
    assert script[4] == {"t": "killed", "target": "9007199254740993", "kind": "human", "hostile": True}
    assert all(VALIDATORS[m["t"]](m) for m in script)
    assert hold_cams(got)[-1]["pl"][1] == fakehost.SLAB_Y + 1.0  # layer 6 alone holds the orbit pose


@pytest.mark.parametrize("layer", [3, 4, 5, 6])
def test_layer_prints_the_protocol_examples_of_its_own_types_only(tmp_path, exporting, capsys, layer):
    own = fakehost.LAYER_EVENTS[layer]
    others = {t for n, ts in fakehost.LAYER_EVENTS.items() if n != layer for t in ts}
    replies = example_messages(*own, *others)
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=replies, hold=0.1, layers=layer_set(layer))
    lines = printed(capsys)
    for t in own:
        assert lines[t] == example_messages(t)
    assert not others & set(lines)


def test_layer6_prints_the_cyberware_list_and_the_cyberfx_effects(tmp_path, exporting, capsys):
    replies = example_messages("cyberware", "cyberfx", "binds")
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=replies, hold=0.1, layers=layer_set(6))
    lines = printed(capsys)
    assert lines["cyberware"] == example_messages("cyberware") and lines["cyberfx"] == example_messages("cyberfx")
    assert lines["binds"] == example_messages("binds") and len(lines["binds"][0]["keys"]) == 4


def test_cyberware_and_cyberfx_are_not_printed_without_layer6(tmp_path, exporting, capsys):
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=example_messages("cyberware", "cyberfx"), hold=0.1, layers=layer_set(2))
    assert not {"cyberware", "cyberfx"} & set(printed(capsys))


@pytest.mark.parametrize("layers", [(2,), (4,), (6,), (3, 5)])
def test_align_is_printed_with_every_layer_and_its_resync_needs_no_answer(tmp_path, exporting, capsys, layers):
    """Minecraft's `align` is always followed by a `blocks reset:true` resync: the fake host prints both and sends nothing back."""
    align, resync = example_messages("align")[0], {"t": "blocks", "reset": True, "c": [10, 4, -21]}
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[align, resync], hold=0.1, layers=layer_set(*layers))
    lines = printed(capsys)
    assert lines["align"] == [align] and lines["blocks"] == [resync]
    assert {m["t"] for m in got} <= {"hello", "view", "clear", "state", "hud", "cmd", "ground", "cam", "npcs", "dmg", "key", "killed", "scaninfo",
                                     "hackresult", "mobdmg", "pcells"}  # nothing was invented in answer to align or blocks


def test_blocks_resync_is_printed_with_the_layer2_events(tmp_path, exporting, capsys):
    blocks = example_messages("blocks")
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=blocks, hold=0.1, layers=layer_set(2))
    assert printed(capsys)["blocks"] == blocks


def test_layers_combine_and_every_message_sent_obeys_the_documented_limits(tmp_path, exporting, quick):
    replies = example_messages("mobs", "proj", "scan", "hack", "flight")
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=replies, hold=0.3, layers=layer_set(2, 3, 4, 5, 6))
    t = types(got)
    assert t.count("dmg") == 1 and t.count("mobdmg") == 1 and t.count("pcells") == 1 and t.count("scaninfo") == 2
    assert t.count("hackresult") == 1 and t.count("killed") == 1 and t.count("npcs") >= 3
    assert [(m["k"], m["down"]) for m in got if m["t"] == "key"] == [
        ("jump", True), ("jump", False), ("hack", True), ("hack", False), ("skills", True), ("skills", False),
        ("cyberware", True), ("cyberware", False), ("cyberware", True), ("cyberware", False), ("activate", True), ("activate", False)]
    cams = hold_cams(got)
    assert cams and all(m["pl"] == example_messages("flight")[0]["p"] for m in cams)  # layer 4's body, following the flight
    assert all(m["fly"] is True for m in cams)  # the protocol.md launch example (kind and ts included) is followed and confirmed
    for m in got:
        if m["t"] in VALIDATORS:
            assert VALIDATORS[m["t"]](m), m


def test_every_cam_carries_the_crouch_flag(tmp_path, exporting):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.05)
    assert all(m["cr"] is False for m in got if m["t"] == "cam") and all(VALIDATORS["cam"](m) for m in got if m["t"] == "cam")


def test_layer_flags_pick_the_scripts_the_port_and_the_hold(monkeypatch):
    seen = []

    async def fake_run(*args):
        seen.append(args)
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    for argv in ([], ["--port", "25710", "--layer3", "--layer6", "--cmd", "say hi"], ["--layer4", "--layer5", "--hold", "2.5"],
                 ["--layer2"], ["--url", "ws://127.0.0.1:9", "--port", "25710", "--layer5"]):
        monkeypatch.setattr("sys.argv", ["fakehost", *argv])
        assert fakehost.main() == 0
    urls, holds, commands, picked = ([a[i] for a in seen] for i in (0, 6, 7, 8))
    assert urls == ["ws://127.0.0.1:25600", "ws://127.0.0.1:25710", "ws://127.0.0.1:25600", "ws://127.0.0.1:25600", "ws://127.0.0.1:9"]
    assert holds == [None, 10.0, 2.5, 10.0, 10.0] and commands == [[], ["say hi"], [], [], []]
    assert picked == [frozenset(), frozenset({3, 6}), frozenset({4, 5}), frozenset({2}), frozenset({5})]


def test_layer_options_are_checked(monkeypatch):
    for argv in (["--layer4", "--hold", "-1"], ["--cmd", "say hi", "--port", "25710"]):
        monkeypatch.setattr("sys.argv", ["fakehost", *argv])
        with pytest.raises(SystemExit) as e:
            fakehost.main()
        assert e.value.code == 2


# ---- re-test 3 modes (plan 2026-10-03-retest3-polish P2): --ui, --hacks and --seat ----
def mode_set(*names):
    return frozenset(names)


CP_SENT = L36_NEW_CP_TO_MC | {"cam", "key", "click", "terrain"} | R3_NEW_CP | R3_EXT_CP  # the types the fake host sends that protocol.md gives a validator


def valid3(m):
    """A message the fake host sent obeys the documented limits (the re-test 3 validators win over the layers 3-6 ones)."""
    if m["t"] not in CP_SENT:
        return True
    if m["t"] == "terrain" or (m["t"] == "reply" and m.get("kind") == "atlas"):  # the Atlas extension wins for these
        return AT_VALIDATORS[m["t"]](m)
    return (R3_VALIDATORS if m["t"] in R3_NEW_CP | R3_EXT_CP else VALIDATORS)[m["t"]](m)


def test_ui_script_covers_every_ui_message_and_every_message_is_valid():
    script = fakehost.ui_script()
    assert [t for t, _ in script] == sorted(t for t, _ in script)
    sent = [m for _, m in script]
    assert all(valid3(m) for m in sent)
    kinds = [m["t"] for m in sent]
    assert {"stats", "quest", "pins", "prompt", "choices", "sub", "call", "notify", "screen", "key"} <= set(kinds)
    quest = next(m for m in sent if m["t"] == "quest")
    assert quest["wp"] is not None and len(quest["obj"]) == 2
    hubs = [m for m in sent if m["t"] == "choices" and m["hubs"]]
    assert len(hubs[0]["hubs"][0]["list"]) == 4 and len({m["sel"] for m in hubs}) >= 3  # four choices, the selection moves
    assert [m["left"] for m in hubs] == sorted((m["left"] for m in hubs), reverse=True)  # the timer runs down
    assert [m for m in sent if m["t"] == "choices"][-1]["hubs"] == []  # and the hub is cleared at the end
    assert [m["phase"] for m in sent if m["t"] == "call"] == ["incoming", "active", "end"]
    assert [m["kind"] for m in sent if m["t"] == "notify"] == ["quest", "money", "item"]
    assert [m["show"] for m in sent if m["t"] == "sub"] == [True, False]
    screens = [m for m in sent if m["t"] == "screen"]
    assert screens == [{"t": "screen", "open": "pause"}, {"t": "screen", "pass": "vendor"}, {"t": "screen", "pass": ""}]
    assert [m["list"] for m in sent if m["t"] == "prompt"][-1] == []  # the prompt is cleared too
    assert fakehost.PERSPECTIVE_KEY[0] > fakehost.UI_MENU[1]  # F5 is tapped after the menu is closed


def test_ui_replies_answer_the_three_requests_and_nothing_else():
    for action, kind in (("journalreq", "journal"), ("phonereq", "phone"), ("atlasreq", "atlas")):
        (reply,) = fakehost.ui_replies({"t": "uiact", "a": action, "arg": "", "hash": 0, "id": 41})
        assert reply["kind"] == kind and reply["id"] == 41 and valid3(reply)
    for action in ("save", "loadlast", "mainmenu", "cpmenu", "track", "smsreply", "fasttravel"):
        assert fakehost.ui_replies({"t": "uiact", "a": action, "id": 1}) == []
    assert fakehost.ui_replies({"t": "uiact"}) == [] and fakehost.ui_replies({"t": "uiact", "a": 5}) == []


def test_ui_mode_plays_the_stream_opens_the_menu_cam_and_answers_requests(tmp_path, exporting, quick, capsys):
    requests = [{"t": "uiact", "a": a, "arg": "", "hash": 0, "id": i} for i, a in enumerate(("journalreq", "phonereq", "atlasreq", "save"), 10)]
    status = {**STATUS, "pause": True}
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[status, *requests, {"t": "persp", "mode": "first"}], hold=0.6, modes=mode_set("ui"))
    assert [m["t"] for m in got if m["t"] in ("stats", "quest", "pins")] == ["stats", "quest", "pins"] and all(valid3(m) for m in got)
    assert [(m["kind"], m["id"]) for m in got if m["t"] == "reply"] == [("journal", 10), ("phone", 11), ("atlas", 12)]
    cams = hold_cams(got)
    menu = [i for i, m in enumerate(cams) if m["menu"]]
    assert menu and menu == list(range(menu[0], menu[-1] + 1)) and not cams[0]["menu"] and not cams[-1]["menu"]  # one window, then closed
    assert [m["k"] for m in got if m["t"] == "key"] == ["perspective", "perspective"]
    out = capsys.readouterr()
    assert "'pause': True" in out.out  # the status with the Night City pause is printed
    lines = {}
    for line in out.out.splitlines():
        head, _, tail = line.partition(": ")
        if head in ("uiact", "persp"):
            lines.setdefault(head, []).append(json.loads(tail))
    assert lines["uiact"] == requests and lines["persp"] == [{"t": "persp", "mode": "first"}]


def test_ui_mode_follows_persp_back_with_a_third_person_camera(tmp_path, exporting, quick):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.6, modes=mode_set("ui"),
                                        react=lambda m: [{"t": "persp", "mode": "back"}] if m["t"] == "key" and m["k"] == "perspective" and m["down"] else [])
    cams = hold_cams(got)
    assert cams[0]["fp"] is True and cams[-1]["fp"] is False  # F5 was tapped, Minecraft answered `persp back`, the cam is third person
    assert all(valid3(m) for m in cams)


def test_hack_replies_give_kind_and_upload_per_target_and_chip():
    hostile, crowd, car = "9007199254740993", "18446744073709551615", "4503599627370497"
    for name, upload in fakehost.HACK_UPLOAD.items():
        for target, kind in ((hostile, "squad"), (crowd, "crowd"), (car, "driver")):
            now, later, wait = fakehost.hack_replies({"t": "hack", "id": 3, "target": target, "hack": name, "chain": False})
            result = now[-1]
            assert result["t"] == "hackresult" and result["ok"] is True and result["reason"] == "" and wait == upload == result["upload"]
            assert result["kind"] == (kind if name != "device" else fakehost.DEVICE_KINDS[3 % 4]) and valid3(result)
            assert all(valid3(m) and m["id"] == 3 for m in (*now, *later))
    assert {fakehost.hack_replies({"t": "hack", "id": i, "target": hostile, "hack": "device", "chain": False})[0][-1]["kind"] for i in range(4)} == set(fakehost.DEVICE_KINDS)
    assert fakehost.HACK_UPLOAD["device"] == 0.0 and max(fakehost.HACK_UPLOAD.values()) <= 30


def test_hack_replies_spread_chain_and_contagion_before_the_result_and_ping_and_frenzy_after_it():
    hostile, crowd = "9007199254740993", "18446744073709551615"
    chain, later, _ = fakehost.hack_replies({"t": "hack", "id": 5, "target": hostile, "hack": "short_circuit", "chain": True})
    assert [(m["t"], m.get("why"), m.get("target")) for m in chain] == [("hackspread", "chain", crowd), ("hackresult", None, None)] and later == []
    contagion, later, _ = fakehost.hack_replies({"t": "hack", "id": 6, "target": crowd, "hack": "contagion", "chain": False})
    assert [m.get("why") for m in contagion] == ["spread", None] and contagion[0]["target"] == hostile and later == []
    ping, later, _ = fakehost.hack_replies({"t": "hack", "id": 7, "target": crowd, "hack": "ping", "chain": False})
    assert [m["t"] for m in ping] == ["hackresult"] and [(m["why"], m["target"]) for m in later] == [("ping", hostile)]
    frenzy, later, upload = fakehost.hack_replies({"t": "hack", "id": 8, "target": hostile, "hack": "cyberpsychosis", "chain": False})
    assert [m["t"] for m in frenzy] == ["hackresult"] and [(m["why"], m["target"]) for m in later] == [("frenzy", crowd)] and upload > 0
    plain, later, _ = fakehost.hack_replies({"t": "hack", "id": 9, "target": hostile, "hack": "memory_wipe", "chain": False})
    assert len(plain) == 1 and later == []


def test_hacks_mode_answers_with_results_and_spreads_and_delays_ping_and_frenzy(tmp_path, exporting, quick):
    hack = lambda i, name, target="9007199254740993", chain=False: {"t": "hack", "id": i, "target": target, "hack": name, "chain": chain}  # noqa: E731
    replies = [hack(1, "contagion"), hack(2, "ping"), hack(3, "cyberpsychosis", chain=True), {**hack(4, "nuke")}, {"t": "hack", "target": "1", "hack": "ping", "chain": False}]
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=replies, hold=0.6, modes=mode_set("hacks"))
    results = {m["id"]: m for m in got if m["t"] == "hackresult"}
    assert set(results) == {1, 2, 3} and all(valid3(m) for m in got)  # an unknown chip and a missing id get no answer
    assert results[1]["kind"] == "squad" and results[1]["upload"] == 1.5 and "upload" in results[2]
    order = [(m["t"], m["id"], m.get("why")) for m in got if m["t"] in ("hackresult", "hackspread")]
    assert order.index(("hackspread", 1, "spread")) < order.index(("hackresult", 1, None))  # spread comes first
    assert order.index(("hackspread", 3, "chain")) < order.index(("hackresult", 3, None)) < order.index(("hackspread", 3, "frenzy"))
    assert order.index(("hackresult", 2, None)) < order.index(("hackspread", 2, "ping"))  # ping after the upload


def test_hacks_mode_taps_the_hack_arms_and_camo_keys_and_prints_the_six_binds(tmp_path, exporting, quick, capsys):
    binds = {"t": "binds", "keys": {"hack": 71, "skills": 75, "cyberware": 85, "activate": 89, "arms": 76, "camo": 79}}
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[binds, {"t": "persp", "mode": "back"}], hold=0.3, modes=mode_set("hacks"))
    assert [(m["k"], m["down"]) for m in got if m["t"] == "key"] == [("hack", True), ("hack", False), ("arms", True), ("arms", False), ("camo", True), ("camo", False)]
    lines = printed(capsys)
    assert lines["binds"] == [binds] and len(lines["binds"][0]["keys"]) == 6 and lines["persp"] == [{"t": "persp", "mode": "back"}]


def test_seat_mode_rides_a_vehicle_with_the_hips_point_and_a_flipping_camera(tmp_path, exporting, quick):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.5, modes=mode_set("seat"))
    assert {"t": "state", "active": True, "vehicle": True} in got
    cams = hold_cams(got)
    assert cams and all(m["seat"] is True and valid3(m) and "fly" not in m for m in cams)
    assert all(m["pl"] == [-1.0, fakehost.SLAB_Y + 1.0 + fakehost.HIPS_UP, -1.0] for m in cams)  # the hips, not the feet
    flips = [a["fp"] for a, b in zip(cams, cams[1:]) if a["fp"] != b["fp"]]
    assert cams[0]["fp"] is True and len(flips) >= 2  # first person, then third person, then first person again
    third = next(m for m in cams if not m["fp"])
    assert math.dist(third["p"], third["pl"]) > 3.0 > math.dist(cams[0]["p"], cams[0]["pl"])  # the third-person camera sits back from V


def test_modes_combine_with_layers_and_every_message_sent_is_valid(tmp_path, exporting, quick):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[{"t": "persp", "mode": "back"}], hold=0.6, layers=layer_set(2),
                                        modes=mode_set("ui", "hacks"))
    assert {"stats", "quest", "dmg", "npcs"} <= set(types(got))
    assert all(valid3(m) for m in got)


def test_modes_alone_send_npcs_but_no_layer_messages(tmp_path, exporting):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.05, layers=layer_set(), modes=mode_set("seat"))
    assert "npcs" in types(got) and not {"dmg", "mobdmg", "pcells"} & set(types(got))


def test_layer_runs_do_not_send_re_test_3_messages(tmp_path, exporting, quick):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.3, layers=layer_set(2, 3, 4, 5, 6))
    assert not {"stats", "quest", "pins", "prompt", "choices", "sub", "call", "notify", "screen", "reply", "hackspread"} & set(types(got))
    assert all("seat" not in m and "menu" not in m for m in hold_cams(got))


def test_mode_flags_pick_the_modes_the_hold_and_the_commands(monkeypatch):
    seen = []

    async def fake_run(*args):
        seen.append(args)
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    for argv in (["--ui"], ["--hacks", "--seat", "--hold", "3"], ["--ui", "--layer2", "--cmd", "say hi"], ["--layer6"], []):
        monkeypatch.setattr("sys.argv", ["fakehost", *argv])
        assert fakehost.main() == 0
    holds, commands, layers, modes = ([a[i] for a in seen] for i in (6, 7, 8, 9))
    assert holds == [10.0, 3.0, 10.0, 10.0, None] and commands == [[], [], ["say hi"], [], []]
    assert layers == [frozenset(), frozenset(), frozenset({2}), frozenset({6}), frozenset()]
    assert modes == [frozenset({"ui"}), frozenset({"hacks", "seat"}), frozenset({"ui"}), frozenset(), frozenset()]


def test_a_command_needs_a_layer_or_a_mode(monkeypatch):
    monkeypatch.setattr("sys.argv", ["fakehost", "--cmd", "say hi"])
    with pytest.raises(SystemExit) as e:
        fakehost.main()
    assert e.value.code == 2
    monkeypatch.setattr("sys.argv", ["fakehost", "--seat", "--hold", "-1"])
    with pytest.raises(SystemExit) as e:
        fakehost.main()
    assert e.value.code == 2


@pytest.mark.parametrize("mode", ["ui", "hacks", "seat"])
def test_modes_print_persp_and_the_six_key_binds_and_nothing_of_other_modes(tmp_path, exporting, capsys, mode):
    persp, binds = {"t": "persp", "mode": "back"}, {"t": "binds", "keys": dict(zip(("hack", "skills", "cyberware", "activate", "arms", "camo"), (71, 75, 85, 89, 76, 79)))}
    other = {"t": "hack", "id": 1, "target": "9007199254740993", "hack": "ping", "chain": False}  # printed by --hacks only
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=[persp, binds, other], hold=0.1, modes=mode_set(mode))
    lines = printed(capsys)
    assert lines["persp"] == [persp] and lines["binds"] == [binds]
    assert ("hack" in lines) == (mode == "hacks")


# ---- Phase Q modes (plan 2026-10-04 megaplan Q-P1): --view and --stairs ----
def validq(m):
    """A message the Phase Q modes sent obeys the documented limits (`cam` with its `vdbg`, `aim`'s distance, else as before)."""
    if m["t"] == "cam":
        return PQ_VALIDATORS["cam"](m)
    if m["t"] == "aim":
        return set(m) == {"t", "d"} and (m["d"] == -1 or 0 <= m["d"] <= 8)
    return valid3(m)


def prefs_message(**changes):
    return {**next(m for t, m in PQ_EXAMPLES if t == "prefs"), **changes}


def eye_of(cam):
    """V's eye (feet + 1.62 m) of a `cam` message."""
    return [cam["pl"][0], cam["pl"][1] + fakehost.EYE_HEIGHT, cam["pl"][2]]


def test_view_walk_pose_walks_at_4_3_then_sprints_at_5_6_round_the_circle_and_faces_where_it_goes():
    dt = 1e-3
    for t, speed in ((0.5, fakehost.WALK_SPEED), (2.9, fakehost.WALK_SPEED), (3.1, fakehost.SPRINT_SPEED), (5.9, fakehost.SPRINT_SPEED)):
        (x, y, z), yaw = fakehost.walk_pose(t)
        (x2, _, z2), _ = fakehost.walk_pose(t + dt)
        assert y == fakehost.FLOOR_Y and math.hypot(x, z) == pytest.approx(fakehost.VIEW_RADIUS)
        assert math.hypot(x2 - x, z2 - z) / dt == pytest.approx(speed, rel=1e-2)
        assert yaw == pytest.approx(math.degrees(math.atan2(-(x2 - x), z2 - z)), abs=0.1)  # the body faces its heading
    assert fakehost.walk_pose(99.0) == fakehost.walk_pose(fakehost.WALK_FOR + fakehost.SPRINT_FOR) and fakehost.walk_pose(-1.0) == fakehost.walk_pose(0.0)
    assert fakehost.WALK_FOR * fakehost.WALK_SPEED + fakehost.SPRINT_FOR * fakehost.SPRINT_SPEED < 2 * math.pi * fakehost.VIEW_RADIUS  # less than one lap


def test_view_circle_stays_on_the_slab_clear_of_the_setup_blocks():
    for i in range(61):
        (x, _, z), _ = fakehost.walk_pose(i * 0.1)
        assert max(abs(x), abs(z)) < fakehost.SLAB_HALF - 1
        assert min(math.hypot(x - bx, z - bz) for bx, bz in ((0, 0), (3, 0), (-3, 0), (0, 3), (0, -3))) > 1.5


def test_third_person_eye_sits_behind_the_head_and_to_the_right():
    eye = fakehost.third_person_eye([10.0, 64.0, 20.0], 0.0, 0.0, 3.2, 0.45)  # yaw 0 faces +z: behind is -z, right is -x
    assert eye == pytest.approx([10.0 - 0.45, 64.0 + 1.62, 20.0 - 3.2])
    east = fakehost.third_person_eye([0.0, 64.0, 0.0], -90.0, 0.0, 2.0, 0.0)  # yaw -90 faces +x
    assert east == pytest.approx([-2.0, 65.62, 0.0])
    down = fakehost.third_person_eye([0.0, 64.0, 0.0], 0.0, 90.0, 4.0, 0.0)  # looking straight down: the camera is above the head
    assert down == pytest.approx([0.0, 65.62 + 4.0, 0.0])
    for yaw, pitch in ((30.0, 10.0), (-135.0, -20.0)):
        e = fakehost.third_person_eye([0.0, 0.0, 0.0], yaw, pitch, 3.2, 0.45)
        assert math.dist(e, [0.0, 1.62, 0.0]) == pytest.approx(math.hypot(3.2, 0.45))


def test_clamp_prefs_clamps_keeps_the_current_value_of_a_bad_field_and_allows_zero_for_fov_and_vdist():
    cur = dict(fakehost.PREFS_DEFAULT)
    assert fakehost.clamp_prefs({k: v for k, v in prefs_message().items() if k != "t"}, cur) == cur
    low = fakehost.clamp_prefs({"dist": 0.1, "shoulder": -9, "fov": 10, "vdist": 1}, cur)
    assert low == {**cur, "dist": 1.5, "shoulder": -1.0, "fov": 50.0, "vdist": 3.0}  # above 0 and below the lower bound: raised to it
    high = fakehost.clamp_prefs({"dist": 99, "shoulder": 99, "fov": 500, "vdist": 99, "recenter": False, "stepup": False, "debug": True}, cur)
    assert high == {"dist": 8.0, "shoulder": 1.0, "fov": 110.0, "vdist": 14.0, "recenter": False, "stepup": False, "debug": True}
    assert fakehost.clamp_prefs({"fov": 0, "vdist": 0}, {**cur, "fov": 80, "vdist": 9}) == {**cur, "fov": 0, "vdist": 0}
    assert fakehost.clamp_prefs({"fov": -5}, {**cur, "fov": 80})["fov"] == 0
    bad = {"dist": "far", "shoulder": float("nan"), "fov": None, "vdist": True, "recenter": 1, "stepup": "yes", "debug": "on"}
    assert fakehost.clamp_prefs(bad, cur) == cur and fakehost.clamp_prefs({}, cur) == cur


def test_view_vdbg_pulls_the_camera_in_at_the_wall_and_smooth_follows_late():
    lo, hi = fakehost.WALL_WINDOW
    mid = (lo + hi) / 2
    assert fakehost.view_collide(0.0, 3.2) == 3.2 and fakehost.view_collide(lo - 0.1, 3.2) == 3.2 and fakehost.view_collide(hi + 0.1, 3.2) == 3.2
    assert fakehost.view_collide(mid, 3.2) == pytest.approx(fakehost.WALL_DIST) and fakehost.view_collide(mid, 1.1) == pytest.approx(1.1)  # never above `dist`
    assert fakehost.view_collide(mid - 0.2, 3.2) > fakehost.view_collide(mid - 0.1, 3.2) > fakehost.view_collide(mid, 3.2)  # a smooth dip
    for f in range(1, 9):
        collide, smooth, age = fakehost.view_vdbg(f, mid, 3.2)
        assert vdbg_ok([collide, smooth, age]) and 12 <= age <= 18 and collide == pytest.approx(1.2)
        assert smooth == pytest.approx(fakehost.view_collide(mid - fakehost.SMOOTH_LAG, 3.2), abs=1e-3) and smooth > collide  # the dip arrives late
    assert fakehost.view_vdbg(1, 0.0, 3.2)[:2] == [3.2, 3.2]


def test_view_mode_plays_walk_sprint_and_glide_in_third_person_with_vdbg_on_every_cam(tmp_path, exporting, quick, capsys):
    flight = {"t": "flight", "active": True, "kind": "glide", "ts": 5.0, "p": [4.0, 100.0, 5.0], "v": [3.0, -1.0, 3.0], "yaw": 40.0, "pitch": 25.0}
    react = lambda m: [flight] if m["t"] == "key" and m["k"] == "jump" and m["down"] else []
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[prefs_message(debug=True)], react=react, hold=0.6, modes=mode_set("view"))
    cams = hold_cams(got)
    assert len(cams) >= 400 and all(validq(m) for m in got) and all(m["fp"] is False and "vdbg" in m and m["cr"] is False for m in cams)
    walking = [m for m in cams if m["pl"][1] == fakehost.FLOOR_Y and not m["fly"]]
    assert walking[0] is cams[0] and all(math.hypot(m["pl"][0], m["pl"][2]) == pytest.approx(fakehost.VIEW_RADIUS) for m in cams[:50])
    assert all(m["air"] is False for m in walking[:100])
    glide = [m for m in cams if m["fly"]]
    assert glide and all(m["air"] is True and m["pl"] == [4.0, 100.0, 5.0] and m["r"][:2] == [40.0, 25.0] for m in glide)  # follows Minecraft's flight
    assert math.dist(glide[0]["p"], eye_of(glide[0])) == pytest.approx(math.hypot(3.2, 0.45)) and glide[0]["p"][1] > 100.0  # camera behind and above V's head
    assert [(m["k"], m["down"]) for m in got if m["t"] == "key"] == [("sprint", True), ("sprint", False), ("jump", True), ("jump", False)]
    assert all(m["vdbg"][2] >= 12 for m in cams) and any(m["vdbg"][0] < 3.2 for m in cams)  # the wall window shows
    assert printed(capsys)["prefs"] == [prefs_message(debug=True)]


def test_view_mode_places_the_camera_by_minecraft_prefs(tmp_path, exporting, quick):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[prefs_message(dist=6.0, shoulder=0.0, fov=90)], hold=0.2, modes=mode_set("view"))
    cams = hold_cams(got)
    assert cams and all(validq(m) for m in cams) and all(m["fov"] == 90.0 for m in cams[10:])
    assert all(math.dist(m["p"], eye_of(m)) == pytest.approx(6.0) for m in cams[10:])


def test_view_mode_sends_vdbg_only_while_prefs_debug_is_true(tmp_path, exporting, quick):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[prefs_message(debug=False)], hold=0.2, modes=mode_set("view"))
    cams = hold_cams(got)
    assert cams and all(validq(m) and m["fp"] is False and "vdbg" not in m for m in cams)


def test_view_mode_without_prefs_uses_the_dll_defaults(tmp_path, exporting, quick):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.1, modes=mode_set("view"))
    cams = hold_cams(got)
    assert all(m["fov"] == fakehost.FOV and "vdbg" not in m for m in cams)  # debug defaults to false
    assert math.dist(cams[0]["p"], eye_of(cams[0])) == pytest.approx(math.hypot(3.2, 0.45))


def test_stairs_commands_put_five_columns_one_to_five_high_on_the_slab_clear_of_the_setup_blocks():
    cmds = fakehost.STAIRS_COMMANDS
    assert len(cmds) == 5
    for k, c in enumerate(cmds):
        word, x1, y1, z1, x2, y2, z2, block = c.split()
        assert word == "fill" and block == "minecraft:cobblestone" and (x1, z1) == (x2, z2) == (str(fakehost.STAIRS_X0 + k), str(fakehost.STAIRS_Z))
        assert int(y1) == fakehost.SLAB_Y + 1 and int(y2) - int(y1) + 1 == k + 1  # on the slab's surface, k + 1 blocks high
        assert abs(int(x1)) <= fakehost.SLAB_HALF and abs(int(z1)) <= fakehost.SLAB_HALF
    assert not {tuple(c.split()[1:4]) for c in cmds} & {tuple(c.split()[1:4]) for c in fakehost.SETUP_COMMANDS}


def test_stairs_feet_walk_up_one_metre_per_column_in_0_15_s():
    assert fakehost.stairs_feet(0.0) == [fakehost.STAIRS_START_X, fakehost.FLOOR_Y, fakehost.STAIRS_Z + 0.5]
    ys = [fakehost.stairs_feet(i * 0.01)[1] for i in range(700)]
    assert ys == sorted(ys) and ys[-1] == fakehost.FLOOR_Y + 5 and all(0 <= y - fakehost.FLOOR_Y <= 5 for y in ys)
    for k in range(5):  # each column edge, less V's radius, starts one 0.15 s lift of exactly 1 m
        reached = (fakehost.STAIRS_X0 + k - fakehost.STAIRS_REACH - fakehost.STAIRS_START_X) / fakehost.STAIRS_SPEED
        assert fakehost.stairs_feet(reached)[1] == pytest.approx(fakehost.FLOOR_Y + k)
        assert fakehost.stairs_feet(reached + fakehost.STAIRS_RISE)[1] == pytest.approx(fakehost.FLOOR_Y + k + 1)
        assert fakehost.stairs_feet(reached + fakehost.STAIRS_RISE / 2)[1] == pytest.approx(fakehost.FLOOR_Y + k + 0.5)
    assert fakehost.stairs_feet(99.0) == [2.5, fakehost.FLOOR_Y + 5, fakehost.STAIRS_Z + 0.5]  # stands on the top column
    assert all(fakehost.stairs_feet(i * 0.01)[0] <= fakehost.stairs_top_x() for i in range(900))


def test_ray_box_hits_misses_and_starts_inside():
    lo, hi = (0, 0, 0), (1, 1, 1)
    assert fakehost.ray_box((-2, 0.5, 0.5), (1, 0, 0), lo, hi) == pytest.approx(2.0)
    assert fakehost.ray_box((-2, 0.5, 0.5), (-1, 0, 0), lo, hi) is None  # pointing away
    assert fakehost.ray_box((-2, 1.5, 0.5), (1, 0, 0), lo, hi) is None  # passes over it
    assert fakehost.ray_box((0.5, 0.5, 0.5), (1, 0, 0), lo, hi) == 0.0  # starts inside
    s = 1 / math.sqrt(2)
    assert fakehost.ray_box((-1, 0.0, 0.5), (s, s, 0), lo, hi) == pytest.approx(math.sqrt(2))  # a diagonal from (-1, 0) enters at x 0, y 1: the corner
    assert fakehost.ray_box((5, 5, 5), (0, 1, 0), lo, hi) is None


def test_stairs_aim_is_the_block_face_2_mm_in_front_and_ground_or_none_otherwise():
    eye = [fakehost.STAIRS_START_X, fakehost.FLOOR_Y + 1.62, fakehost.STAIRS_Z + 0.5]
    yaw = -90.0  # facing +x
    riser = (fakehost.STAIRS_X0 - eye[0]) / math.cos(math.radians(fakehost.STAIRS_PITCH))  # the first column's face at x -2, 2.5 m ahead
    assert fakehost.stairs_aim(eye, yaw, fakehost.STAIRS_PITCH) == pytest.approx(riser + fakehost.AIM_BIAS, abs=1e-3)
    assert fakehost.AIM_BIAS == -0.002  # the Cyberblock box face is 2 mm in front of the block face
    assert fakehost.stairs_aim(eye, yaw, 0.0) == pytest.approx(fakehost.STAIRS_X0 + 1 - eye[0] + fakehost.AIM_BIAS, abs=1e-3)  # over column 1, into column 2's face
    ground = fakehost.stairs_aim([-6.5, fakehost.FLOOR_Y + 1.62, fakehost.STAIRS_Z + 0.5], yaw, 80.0)  # steeply down: the slab, not a block
    assert ground == pytest.approx(1.62 / math.sin(math.radians(80)), abs=1e-3)
    top = [fakehost.stairs_top_x(), fakehost.FLOOR_Y + 5 + 1.62, fakehost.STAIRS_Z + 0.5]
    assert fakehost.stairs_aim(top, yaw, -20.0) == -1.0 and fakehost.stairs_aim(top, yaw, 0.0) == -1.0  # nothing ahead within 8 m
    assert fakehost.stairs_aim([-4.5, fakehost.FLOOR_Y + 1.62, 3.0], yaw, 0.0) == -1.0  # beside the staircase


def test_stairs_mode_places_the_staircase_walks_up_it_and_sends_aim_at_the_faces(tmp_path, exporting, quick, capsys):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[prefs_message()], hold=0.5, modes=mode_set("stairs"))
    assert all(validq(m) for m in got)
    cmds = [m["c"] for m in got if m["t"] == "cmd"]
    assert cmds[len(fakehost.SETUP_COMMANDS):] == fakehost.STAIRS_COMMANDS  # after the orbit's setup, before the walk
    first_cam = next(i for i, m in enumerate(got) if m["t"] == "cam" and m["f"] > 30)
    assert max(i for i, m in enumerate(got) if m["t"] == "cmd") < first_cam
    cams = hold_cams(got)
    assert all(m["fp"] is True and m["pl"][2] == fakehost.STAIRS_Z + 0.5 and m["r"][0] == pytest.approx(-90.0) and m["r"][1] == fakehost.STAIRS_PITCH for m in cams)
    ys = [m["pl"][1] for m in cams]
    assert ys == sorted(ys) and ys[0] == fakehost.FLOOR_Y and ys[-1] == fakehost.FLOOR_Y + 5 and cams[-1]["pl"][0] == fakehost.stairs_top_x()
    aims = [m for m in got if m["t"] == "aim"]
    assert aims and aims[0]["d"] == pytest.approx(2.5 / math.cos(math.radians(20)) - 0.002, abs=1e-3)  # first look: the first column's face
    assert any(a["d"] == -1 for a in aims) and any(0 < a["d"] < 8 for a in aims)  # nothing in front on the top, faces on the way up
    for a, b in zip(aims, aims[1:]):
        assert abs(b["d"] - a["d"]) >= fakehost.AIM_STEP - 1e-9 or (a["d"] < 0) != (b["d"] < 0)  # only on change
    assert len(aims) < len(cams) / 3  # at most 10 Hz (scaled), not every frame
    assert printed(capsys)["prefs"] == [prefs_message()]


def test_stairs_mode_follows_persp_back_with_a_third_person_flag(tmp_path, exporting, quick):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[{"t": "persp", "mode": "back"}], hold=0.1, modes=mode_set("stairs"))
    cams = hold_cams(got)
    assert cams and all(m["fp"] is False for m in cams[5:])


def test_view_and_stairs_modes_combine_with_layer2_and_every_message_is_valid(tmp_path, exporting, quick):
    for mode in ("view", "stairs"):
        _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.2, layers=layer_set(2), modes=mode_set(mode))
        assert {"npcs", "dmg", "cam"} <= set(types(got)) and all(validq(m) for m in got)


@pytest.mark.parametrize("mode", ["view", "stairs"])
def test_phase_q_modes_print_prefs_and_nothing_of_other_modes(tmp_path, exporting, capsys, mode):
    persp, binds = {"t": "persp", "mode": "back"}, {"t": "binds", "keys": dict(zip(("hack", "skills", "cyberware", "activate", "arms", "camo"), (71, 75, 85, 89, 76, 79)))}
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=[prefs_message(), persp, binds], hold=0.1, modes=mode_set(mode))
    lines = printed(capsys)
    assert lines["prefs"] == [prefs_message()] and "persp" not in lines and "binds" not in lines


def test_prefs_is_not_printed_without_a_phase_q_mode(tmp_path, exporting, capsys):
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=[prefs_message()], hold=0.1, modes=mode_set("seat"))
    assert "prefs" not in printed(capsys)


def test_phase_q_flags_pick_the_modes_and_refuse_a_second_camera_owner(monkeypatch):
    seen = []

    async def fake_run(*args):
        seen.append(args)
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    for argv in (["--view"], ["--stairs", "--hold", "3"], ["--view", "--layer2", "--cmd", "say hi"], ["--ui", "--stairs"]):
        monkeypatch.setattr("sys.argv", ["fakehost", *argv])
        assert fakehost.main() == 0
    assert [a[6] for a in seen] == [10.0, 3.0, 10.0, 10.0]
    assert [a[9] for a in seen] == [frozenset({"view"}), frozenset({"stairs"}), frozenset({"view"}), frozenset({"ui", "stairs"})]
    for argv in (["--view", "--stairs"], ["--view", "--seat"], ["--stairs", "--seat"], ["--view", "--layer4"], ["--stairs", "--layer4"]):
        monkeypatch.setattr("sys.argv", ["fakehost", *argv])
        with pytest.raises(SystemExit) as e:
            fakehost.main()
        assert e.value.code == 2, argv


# ---- multiplayer (plan 2026-10-04 task 1-T2): --name, --ver, --ids-from and the printed `session` ----
SESSION_HOST = next(m for t, m in MP_EXAMPLES if t == "session" and m["state"] == "host")
SESSION_REFUSED = next(m for t, m in MP_EXAMPLES if t == "session" and m["state"] == "refused")
OLD_IDS = ["9007199254740993", "18446744073709551615", "4503599627370497"]


def test_hello_carries_the_dll_version_when_one_is_given_and_is_valid(tmp_path, exporting):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, ver="1.2.3")
    assert got[0] == {"t": "hello", "proto": 1, "game": "cp2077", "build": "2.31", "ver": "1.2.3"} and mp_hello_ok(got[0])
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting)
    assert got[0] == {"t": "hello", "proto": 1, "game": "cp2077", "build": "2.31"}  # a DLL from before Phase 1 sends no ver


def test_session_messages_are_printed_with_every_run(tmp_path, exporting, capsys):
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=[SESSION_HOST, SESSION_REFUSED])
    assert printed(capsys)["session"] == [SESSION_HOST, SESSION_REFUSED]
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=[SESSION_HOST], hold=0.05, layers=layer_set(3))
    assert printed(capsys)["session"] == [SESSION_HOST]


def test_becoming_the_host_re_sends_clear_the_whole_ground_and_the_npcs_as_the_dll_does():
    """protocol.md `session`: on a change to host the DLL forgets its probes and sends `clear`, so the new server session gets
    the whole ground again (the client dropped the ground sent before it was the host)."""
    host = fakehost.Cyberpunk(layer_set(2), ids_from=100)
    resend = host.reply(SESSION_HOST)
    assert resend == [{"t": "clear"}, {"t": "ground", "c": fakehost.ground_slab()}, fakehost.npcs_message(100)]
    assert host.reply(SESSION_HOST) == []  # still the host: nothing again
    assert host.reply({**SESSION_HOST, "state": "guest"}) == [] and host.reply(SESSION_HOST) == resend  # host again: the whole ground again
    assert fakehost.Cyberpunk(frozenset()).reply(SESSION_HOST) == resend[:2]  # no layer or mode runs: no NPCs (none are ever sent)
    assert fakehost.Cyberpunk(layer_set(2)).reply(SESSION_REFUSED) == []


def test_a_live_session_host_brings_the_ground_after_it(tmp_path, exporting):
    def react(m):
        return [SESSION_HOST] if m["t"] == "hud" else []  # the client says `session host` after the DLL's hello

    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, react=react, hold=0.05)
    kinds = [m["t"] for m in got]
    assert kinds.count("clear") == 2  # the one with the hello, and the answer to `session host`
    second = kinds.index("clear", kinds.index("hud"))
    assert "ground" in kinds[second:] and "npcs" in kinds[kinds.index("ground", second):]


def test_ids_from_numbers_the_three_npcs_from_n_and_keeps_everything_else(tmp_path, exporting):
    assert [e["id"] for e in fakehost.npcs_message()["list"]] == OLD_IDS
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, hold=0.05, ids_from=100)
    npcs = next(m for m in got if m["t"] == "npcs")
    assert [e["id"] for e in npcs["list"]] == ["100", "101", "102"]
    assert [(e["kind"], e["hostile"], e["p"]) for e in npcs["list"]] == [(e["kind"], e["hostile"], e["p"]) for e in fakehost.npcs_message()["list"]]
    dmg = next(m for m in got if m["t"] == "dmg")
    assert dmg == fakehost.dmg_message()  # the bullet comes from the same place whatever the ids are
    assert all(m["list"] == npcs["list"] for m in got if m["t"] == "npcs")  # every resend uses the same ids


def test_ids_from_reaches_the_canned_answers_so_two_hosts_collide_on_purpose(tmp_path, exporting):
    ids = lambda base: [str(base + i) for i in range(3)]  # noqa: E731
    scan, hack = {"t": "scan", "id": 7, "target": "200"}, {"t": "hack", "id": 8, "target": "202", "hack": "short_circuit", "chain": False}
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[scan, hack], hold=0.1, layers=layer_set(6), ids_from=200)
    info = next(m for m in got if m["t"] == "scaninfo")
    assert info["target"] == "200" and info["id"] == 7 and VALIDATORS["scaninfo"](info)
    assert next(m for m in got if m["t"] == "npcs")["list"][0]["id"] == "200"
    assert fakehost.hostile_human(300)["id"] == "300" and fakehost.hostile_human()["id"] == OLD_IDS[0]
    assert fakehost.hack_kind("short_circuit", "301", 1, ids_from=300) == "crowd"  # the second human
    assert fakehost.hack_kind("short_circuit", "302", 1, ids_from=300) == "driver"  # the vehicle
    assert fakehost.hack_kind("short_circuit", "300", 1, ids_from=300) == "squad"
    assert ids(5) == [e["id"] for e in fakehost.npcs_message(5)["list"]]


def test_two_fake_hosts_with_the_same_ids_from_send_the_same_ids_and_with_other_ones_do_not_overlap():
    a, b, c = (fakehost.npcs_message(n) for n in (1000, 1000, 2000))
    ids = lambda m: {e["id"] for e in m["list"]}  # noqa: E731
    assert ids(a) == ids(b) and not ids(a) & ids(c)


@pytest.mark.parametrize("n", [0, -1, 2**64 - 2, 2**64])
def test_ids_from_outside_the_uint64_range_is_refused(n):
    with pytest.raises(ValueError):
        fakehost.npcs_message(n)


def test_the_highest_ids_from_still_fits_a_uint64():
    ids = [e["id"] for e in fakehost.npcs_message(2**64 - 3)["list"]]
    assert ids[-1] == str(2**64 - 1) and all(int(i) < 2**64 for i in ids)


def test_name_prefixes_every_printed_line(tmp_path, exporting, capsys):
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=[SESSION_HOST, EVENTS[0]], hold=0.05, name="CbHost1")
    lines = [line for line in capsys.readouterr().out.splitlines() if line.strip()]
    assert lines and all(line.startswith("[CbHost1] ") for line in lines)
    assert any(line.startswith("[CbHost1] session: ") for line in lines) and any(line.startswith("[CbHost1] block: ") for line in lines)
    assert any(line.startswith("[CbHost1] hello: ") for line in lines)


def test_no_name_keeps_the_old_output_format(tmp_path, exporting, capsys):
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=[EVENTS[0]], hold=0.05)
    assert printed(capsys)["block"] == [EVENTS[0]]


@pytest.mark.parametrize("bad", ["", "a b", "x" * 33, "a;b", "[x]"])
def test_a_bad_name_is_refused(monkeypatch, bad):
    monkeypatch.setattr("sys.argv", ["fakehost", "--name", bad])
    with pytest.raises(SystemExit) as e:
        fakehost.main()
    assert e.value.code == 2


def test_multiplayer_flags_reach_run_after_the_old_arguments(monkeypatch):
    seen = []

    async def fake_run(*args):
        seen.append(args)
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    for argv in (["--name", "CbHost2", "--ver", "9.9.9", "--ids-from", "500"], ["--ver", ""], []):
        monkeypatch.setattr("sys.argv", ["fakehost", *argv])
        assert fakehost.main() == 0
    assert [a[10:] for a in seen] == [("CbHost2", "9.9.9", 500), (None, None, None), (None, package.version(), None)]
    assert all(len(a) == 13 for a in seen)  # the old nine arguments stay where they were


def test_ids_from_and_ver_are_checked(monkeypatch):
    for argv in (["--ids-from", "0"], ["--ids-from", str(2**64 - 2)], ["--ids-from", "x"], ["--ver", "v" * 33]):
        monkeypatch.setattr("sys.argv", ["fakehost", *argv])
        with pytest.raises(SystemExit) as e:
            fakehost.main()
        assert e.value.code == 2


def test_the_documented_example_hello_is_what_the_fake_host_sends_with_a_version(tmp_path, exporting):
    example = next(m for t, m in MP_EXAMPLES if t == "hello")
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, ver=example["ver"])
    assert got[0] == example


# ---- Phase 3: --replica (megaplan 3-P1): the street, the figures, env, vteleport and park ----
RUNNER, WALKER, CAR, WRECK = fakehost.replica_ids()


def npcs_ok(m):
    """The layer 2 `npcs` limits: at most 48 entries, decimal uint64 ids, a position, a yaw, a kind and a hostile flag."""
    return (set(m) == {"t", "list"} and len(m["list"]) <= 48 and all(set(e) == {"id", "p", "yaw", "kind", "hostile"} and uint64_string(e["id"]) and vec3(e["p"])
                                                                      and e["kind"] in ("human", "vehicle") and isinstance(e["hostile"], bool) for e in m["list"]))


def p3_valid(m):
    if m["t"] in P3_VALIDATORS:
        return P3_VALIDATORS[m["t"]](m)
    return npcs_ok(m) if m["t"] == "npcs" else m == {"t": "clear"} if m["t"] == "clear" else VALIDATORS[m["t"]](m)


def test_replica_street_has_every_material_and_a_valid_ground_message_per_chunk():
    chunks = fakehost.replica_ground()
    assert len(chunks) == 5 and all(m["t"] == "ground" and p3_valid(m) for m in chunks)
    codes = {c for m in chunks for c in m["m"]}
    assert codes >= set(range(1, 11)) and {13, 20, 25} <= codes and all(1 <= c <= 26 for c in codes)  # every surface and three neon dyes
    quads = [tuple(m["c"][i:i + 4]) for m in chunks for i in range(0, len(m["c"]), 4)]
    assert len(quads) == sum(len(m["m"]) for m in chunks) == 17 * 17 + 2
    assert {(x, z) for x, z, _, _ in quads} >= {(x, z) for x in range(-8, 9) for z in range(-8, 9)}  # every column of the slab area has ground
    assert all(lo <= hi and -8 <= x <= 8 and -8 <= z <= 8 for x, z, lo, hi in quads)


def test_replica_neon_codes_are_the_dye_blocks_of_the_material_table():
    from tools import cbmath
    blocks = {code: cbmath.material_block(code) for code in {c for _, c in fakehost.replica_landmarks()}}
    assert blocks[13] == "cyberblock:nc_neon_magenta" and blocks[20] == "cyberblock:nc_neon_cyan" and blocks[25] == "cyberblock:nc_neon_red"
    assert blocks[3] == "cyberblock:nc_asphalt" and blocks[10] == "cyberblock:nc_water" and all(blocks.values())


def test_replica_landmarks_are_cells_of_their_own_material_and_shift_moves_them_east():
    cells = fakehost.replica_cells()
    for cell, code in fakehost.replica_landmarks():
        assert cells[cell] == code, cell
    shifted = fakehost.replica_cells(8)
    assert {(x - 8, y, z): c for (x, y, z), c in shifted.items()} == cells
    assert [(c[0] - 8, c[1], c[2]) for c, _ in fakehost.replica_landmarks(8)] == [c for c, _ in fakehost.replica_landmarks()]
    assert max(x for x, _, _ in cells) == 8 and min(x for x, _, _ in shifted) == 0  # two hosts overlap on x 0..8


def test_replica_ground_without_the_scaffolds_leaves_the_floor_under_them():
    full, without = fakehost.replica_ground(), fakehost.replica_ground(without=[fakehost.SCAFFOLD_SEEN, fakehost.SCAFFOLD_LEFT])
    count = lambda chunks: sum(len(m["m"]) for m in chunks)  # noqa: E731
    assert count(full) - count(without) == 2
    cells = {(x, y, z) for m in without for x, z, lo, hi in [m["c"][i:i + 4] for i in range(0, len(m["c"]), 4)] for y in range(lo, hi + 1)}
    x, z = fakehost.SCAFFOLD_SEEN
    assert (x, fakehost.SLAB_Y, z) in cells and (x, fakehost.SCAFFOLD_RANGE[0], z) not in cells


def test_replica_unground_messages_say_seen_only_when_it_was_observed():
    seen, left = fakehost.unground_message(7, 4), fakehost.unground_message(7, 6, seen=False)
    assert seen == {"t": "unground", "c": [7, 4, 64, 66], "seen": True} and left == {"t": "unground", "c": [7, 6, 64, 66]}
    assert fakehost.unground_message(7, 4, 8)["c"][0] == 15 and p3_valid(seen) and p3_valid(left)


def test_replica_figures_move_at_real_speed_and_the_standing_car_is_destroyed():
    at = lambda t: {e["id"]: e for e in fakehost.replica_npcs(t)["list"]}  # noqa: E731
    a, b = at(0.5), at(1.5)
    assert abs(b[WALKER]["p"][0] - a[WALKER]["p"][0] - fakehost.WALK_X) < 1e-9
    assert abs(a[RUNNER]["p"][0] - b[RUNNER]["p"][0] - fakehost.RUN_X) < 1e-9  # the runner goes the other way
    assert abs(b[CAR]["p"][0] - a[CAR]["p"][0] - fakehost.CAR_X) < 1e-9
    assert WRECK in at(fakehost.WRECK_AT - 0.1) and WRECK not in at(fakehost.WRECK_AT)  # destroyed: gone from the list
    assert all(p3_valid(fakehost.replica_npcs(t)) for t in (0.0, 3.3, 5.9, 6.0, 25.0))
    assert [e["kind"] for e in fakehost.replica_npcs(0)["list"]] == ["human", "human", "vehicle", "vehicle"]
    assert RUNNER == fakehost.hostile_human()["id"] and (RUNNER, WALKER, CAR) == fakehost.NPC_IDS  # the canned answers keep working


def test_replica_figures_stay_on_the_street_and_wrap_round():
    for t in range(0, 60):
        for e in fakehost.replica_npcs(t / 2)["list"]:
            assert -8 <= e["p"][0] < 8 and -8 <= e["p"][2] <= 8 and e["p"][1] == fakehost.SLAB_Y + 1.0
    assert fakehost.replica_npcs(3.0, shift=8)["list"][2]["p"][0] == fakehost.replica_npcs(3.0)["list"][2]["p"][0] + 8


def test_replica_ids_number_four_figures_and_collide_on_purpose():
    assert fakehost.replica_ids(100) == ("100", "101", "102", "103") and fakehost.replica_ids()[:3] == fakehost.NPC_IDS
    assert fakehost.replica_ids(2**64 - 4)[-1] == str(2**64 - 1)
    with pytest.raises(ValueError):
        fakehost.replica_ids(2**64 - 3)
    a, b = fakehost.replica_npcs(1.0, ids_from=5000), fakehost.replica_npcs(1.0, shift=8, ids_from=5000)
    assert [e["id"] for e in a["list"]] == [e["id"] for e in b["list"]]


def test_replica_env_cycles_clock_and_weather_and_a_second_host_gets_another_sky():
    steps = [fakehost.replica_env(t) for t in (0, 10, 20, 30, 40, 65)]
    assert [(m["time"], m["weather"]) for m in steps] == [([22, 30], "clear"), ([23, 0], "rain"), ([23, 30], "storm"), ([5, 45], "clear"),
                                                          ([22, 30], "clear"), ([23, 30], "storm")]
    assert all(p3_valid(m) for m in steps)
    second = fakehost.replica_env(10, hours=12)
    assert second == {"t": "env", "time": [11, 0], "weather": "fog"} and p3_valid(second)
    assert fakehost.replica_env(0, hours=-23)["time"] == [23, 30]
    from tools import cbmath
    ticks = {cbmath.env_ticks(*m["time"]) for m in steps}
    assert not ticks & {cbmath.env_ticks(*fakehost.replica_env(t, hours=12)["time"]) for t in (0, 10, 20, 30)}  # the harness can tell the hosts apart


def test_teleport_answer_accepts_a_valid_request_and_drops_a_bad_one():
    ask = {"t": "vteleport", "id": 4, "p": [10, 64, -3.5], "yaw": 90.0, "why": "meetup"}
    assert fakehost.teleport_answer(ask) == {"t": "vteleported", "id": 4, "ok": True, "reason": "", "p": [10.0, 64.0, -3.5]}
    assert p3_valid(fakehost.teleport_answer(ask))
    for bad in ({**ask, "id": -1}, {**ask, "id": "4"}, {**ask, "p": [1, 2]}, {**ask, "p": [1, 2, float("nan")]}, {**ask, "p": [3e7, 0, 0]},
                {**ask, "why": "fun"}, {k: v for k, v in ask.items() if k != "p"}, {**ask, "id": True}):
        assert fakehost.teleport_answer(bad) is None


def test_replica_session_host_brings_clear_the_street_with_materials_and_the_figures():
    host = fakehost.Cyberpunk(frozenset(), frozenset({"replica"}), shift=8)
    got = host.reply(SESSION_HOST)
    assert [m["t"] for m in got] == ["clear", *["ground"] * 5, "npcs"] and all(p3_valid(m) for m in got[1:6])
    assert got[1] == fakehost.replica_ground(8)[0] and got[-1] == fakehost.replica_npcs(0.0, 8)
    assert host.reply(SESSION_HOST) == []


def test_replica_script_scans_seen_then_leaves_then_clears_and_regrows_without_the_scaffolds():
    timeline = fakehost.Cyberpunk(frozenset(), frozenset({"replica"})).script()
    kinds = [(t, m["t"], m.get("seen")) for t, m in timeline]
    assert kinds[0] == (fakehost.WRECK_AT - fakehost.KILL_LEAD, "killed", None) and timeline[0][1] == fakehost.replica_killed()
    assert kinds[1:4] == [(12.0, "unground", True), (12.5, "unground", None), (14.0, "clear", None)]
    regrow = [m for t, m in timeline if t > 14.0]
    assert len(regrow) == 5 and all(m["t"] == "ground" for m in regrow) and regrow == fakehost.replica_ground(
        without=[fakehost.SCAFFOLD_SEEN, fakehost.SCAFFOLD_LEFT])
    assert [t for t, _ in timeline] == sorted(t for t, _ in timeline) and all(p3_valid(m) for _, m in timeline)


def test_replica_destroyed_car_is_reported_killed_before_it_leaves_the_list():
    """3F's ProxyManager makes a wreck only from `killed`; a proxy that just leaves `npcs` is removed without one."""
    killed = fakehost.replica_killed()
    assert killed == {"t": "killed", "target": WRECK, "kind": "vehicle", "hostile": False} and p3_valid(killed)
    assert 0 < fakehost.KILL_LEAD < 0.5 and WRECK in {e["id"] for e in fakehost.replica_npcs(fakehost.WRECK_AT - fakehost.KILL_LEAD)["list"]}
    assert fakehost.replica_killed(1000)["target"] == fakehost.replica_ids(1000)[3]


def test_replica_vteleport_is_answered_after_two_seconds_and_v_arrives(monkeypatch):
    monkeypatch.setattr(fakehost.time, "perf_counter", lambda: 100.0)
    host = fakehost.Cyberpunk(frozenset(), frozenset({"replica"}))
    ask = {"t": "vteleport", "id": 9, "p": [40.5, 64.0, -12.0], "yaw": 0.0, "why": "meetup"}
    assert host.reply(ask) == [] and host.due(101.9) == [] and host.cam(1, 0.0, {})["pl"] == [-6.5, 64.0, 0.5]  # not yet
    assert host.due(102.0) == [{"t": "vteleported", "id": 9, "ok": True, "reason": "", "p": [40.5, 64.0, -12.0]}]
    cam = host.cam(2, 0.0, {})
    assert cam["pl"] == [40.5, 64.0, -12.0] and cam["fp"] is True and valid3(cam)  # Steve follows V
    assert host.reply({**ask, "why": "fun"}) == [] and host.due(500.0) == []  # a bad request is dropped, not answered


def test_replica_park_is_answered_at_once_with_the_state_it_is_in():
    host = fakehost.Cyberpunk(frozenset(), frozenset({"replica"}))
    assert host.reply({"t": "park", "on": True}) == [{"t": "parked", "on": True, "ok": True, "reason": ""}]
    assert host.reply({"t": "park", "on": False}) == [{"t": "parked", "on": False, "ok": True, "reason": ""}]
    assert host.reply({"t": "park", "on": "yes"}) == [] and p3_valid(host.reply({"t": "park", "on": True})[0])
    assert fakehost.Cyberpunk(frozenset(), frozenset({"seat"})).reply({"t": "park", "on": True}) == []  # only --replica answers


def test_replica_mode_plays_the_street_the_figures_the_env_and_answers_a_teleport(tmp_path, exporting, quick, capsys):
    ask = {"t": "vteleport", "id": 5, "p": [30.0, 64.0, 2.0], "yaw": -90.0, "why": "meetup"}
    react = lambda m: [SESSION_HOST] if m["t"] == "hud" else []  # noqa: E731
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[ask, {"t": "park", "on": True}], react=react, hold=1.0, layers=layer_set(),
                                        modes=mode_set("replica"))
    kinds = types(got)
    assert kinds.count("clear") >= 3 and kinds.count("ground") >= 10 and "unground" in kinds and "env" in kinds
    assert all(p3_valid(m) for m in got if m["t"] in ("ground", "unground", "env", "vteleported", "parked", "npcs", "clear"))
    assert sum(1 for m in got if m["t"] == "ground" and "m" in m) >= 10 and all("m" in m for m in got if m["t"] == "ground" and len(m["c"]) > 4 * 17 * 17)
    seen = [m for m in got if m["t"] == "unground"]
    assert [m.get("seen") for m in seen] == [True, None]
    assert [m["t"] for m in got if m["t"] in ("vteleported", "parked")] == ["parked", "vteleported"]
    assert next(m for m in got if m["t"] == "vteleported")["p"] == [30.0, 64.0, 2.0]
    cams = hold_cams(got)
    assert cams[0]["pl"] == [-6.5, 64.0, 0.5] and cams[-1]["pl"] == [30.0, 64.0, 2.0] and all(valid3(m) for m in cams)
    npcs = [m for m in got if m["t"] == "npcs"]
    assert len({json.dumps(m) for m in npcs}) > 3 and any(len(m["list"]) == 4 for m in npcs) and any(len(m["list"]) == 3 for m in npcs)  # they move, a car goes
    assert not {"dmg", "mobdmg"} & set(kinds)
    assert [m for m in got if m["t"] == "killed"] == [fakehost.replica_killed()]  # the destroyed car: the server leaves a wreck
    assert printed(capsys)["session"] == [SESSION_HOST]


def test_replica_prints_vteleport_and_park_and_only_with_the_mode(tmp_path, exporting, capsys):
    ask, park = {"t": "vteleport", "id": 5, "p": [1.0, 64.0, 2.0], "yaw": 0.0, "why": "holo"}, {"t": "park", "on": False}
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=[ask, park], hold=0.1, layers=layer_set(), modes=mode_set("replica"))
    lines = printed(capsys)
    assert lines["vteleport"] == [ask] and lines["park"] == [park]
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=[ask, park], hold=0.1, modes=mode_set("seat"))
    assert "vteleport" not in printed(capsys) and "park" not in printed(capsys)


def test_two_replica_hosts_with_the_same_ids_send_overlapping_streets_and_different_skies(tmp_path, exporting, quick):
    _, one = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[SESSION_HOST], hold=0.4, layers=layer_set(), modes=mode_set("replica"), ids_from=5000)
    _, two = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[SESSION_HOST], hold=0.4, layers=layer_set(), modes=mode_set("replica"), ids_from=5000, shift=8, env_hours=12)
    xs = lambda got: {m["c"][i] for m in got if m["t"] == "ground" and "m" in m for i in range(0, len(m["c"]), 4)}  # noqa: E731
    assert min(xs(one)) == -8 and max(xs(one)) == 8 and min(xs(two)) == 0 and max(xs(two)) == 16
    assert {e["id"] for m in one if m["t"] == "npcs" for e in m["list"]} == {e["id"] for m in two if m["t"] == "npcs" for e in m["list"]}
    assert {m["weather"] for m in two if m["t"] == "env"} == {"fog"} and "fog" not in {m["weather"] for m in one if m["t"] == "env"}


def test_replica_flags_pick_the_mode_and_pass_shift_and_env_hours_as_keywords(monkeypatch):
    seen = []

    async def fake_run(*args, **kwargs):
        seen.append((args, kwargs))
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    for argv in (["--replica"], ["--replica", "--hold", "3", "--shift", "8", "--env-hours", "12"], ["--replica", "--layer2", "--ids-from", "5000"]):
        monkeypatch.setattr("sys.argv", ["fakehost", *argv])
        assert fakehost.main() == 0
    assert [a[9] for a, _ in seen] == [frozenset({"replica"})] * 3 and [a[6] for a, _ in seen] == [10.0, 3.0, 10.0]
    assert [k for _, k in seen] == [{}, {"shift": 8, "env_hours": 12}, {}] and all(len(a) == 13 for a, _ in seen)  # the old thirteen stay put


@pytest.mark.parametrize("argv", [["--shift", "8"], ["--env-hours", "3"], ["--seat", "--shift", "1"], ["--replica", "--shift", "1001"],
                                  ["--replica", "--env-hours", "24"], ["--replica", "--env-hours", "-24"], ["--replica", "--view"],
                                  ["--replica", "--stairs"], ["--replica", "--seat"], ["--replica", "--layer4"],
                                  ["--replica", "--ids-from", str(2**64 - 3)]])
def test_replica_flags_are_checked(monkeypatch, argv):
    async def fake_run(*args, **kwargs):
        return 1

    monkeypatch.setattr(fakehost, "run", fake_run)
    monkeypatch.setattr("sys.argv", ["fakehost", *argv])
    with pytest.raises(SystemExit) as e:
        fakehost.main()
    assert e.value.code == 2


def test_replica_combines_with_ui_and_hacks_modes(tmp_path, exporting, quick):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, replies=[SESSION_HOST], hold=0.3, modes=mode_set("replica", "ui"))
    assert {"ground", "env", "stats", "quest"} <= set(types(got))
