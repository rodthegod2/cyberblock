import asyncio
import json
import math
import uuid

import numpy as np
import pytest
from PIL import Image
from websockets.asyncio.server import serve

from tools import fakehost, shm

HELLO = {"t": "hello", "proto": 1, "mod": "cyberblock", "mc": "26.3"}
STATUS = {"t": "status", "hp": 20, "maxHp": 20, "food": 20, "gm": "survival", "screen": False}
W, H = 8, 4


def run_against_fake_minecraft(out, frames, shm_name, replies=(), **kwargs):
    """fakehost.run against a local server that plays Minecraft's part (and also sends `replies`, e.g. layer 2 events
    like an explosion); returns (frame sets saved, messages received)."""
    received = []

    async def minecraft(ws):
        await ws.send(json.dumps(HELLO))
        await ws.send(json.dumps(STATUS))
        for m in replies:
            await ws.send(json.dumps(m))
        async for raw in ws:
            received.append(json.loads(raw))

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
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, layer2_hold=0.05)
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
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, layer2_hold=0.3)
    cams = [m for m in got if m["t"] == "cam"]
    assert len(cams) > 100 and [m["f"] for m in cams] == list(range(1, len(cams) + 1))
    assert all(m["p"] == cams[29]["p"] and m["r"] == cams[29]["r"] for m in cams[29:])  # held at the last orbit pose
    assert 2 <= types(got).count("npcs") <= 6  # about 10 Hz: the first at once, then one per 100 ms


def test_layer2_prints_every_event_minecraft_reports(tmp_path, exporting, capsys):
    run_against_fake_minecraft(tmp_path, 30, exporting, replies=EVENTS, layer2_hold=0.1)
    lines = capsys.readouterr().out.splitlines()
    for event in EVENTS:
        line = next(l for l in lines if l.startswith(f"{event['t']}: "))
        assert json.loads(line.split(": ", 1)[1]) == event


def test_layer2_commands_are_sent_after_the_dmg_in_order(tmp_path, exporting):
    _, got = run_against_fake_minecraft(tmp_path, 30, exporting, layer2_hold=0.05, commands=["summon tnt 0 64 0", "kill @e[type=tnt]"])
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
