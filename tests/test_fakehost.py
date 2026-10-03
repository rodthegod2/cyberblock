import asyncio
import json
import math
import uuid

import numpy as np
import pytest
from PIL import Image
from websockets.asyncio.server import serve

from test_protocol_md import L36_EXAMPLES, VALIDATORS  # shared/protocol.md: the example messages and the documented limits of layers 3-6
from tools import fakehost, shm

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
