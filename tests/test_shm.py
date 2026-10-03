import math
import struct
import subprocess
import sys
import uuid

import numpy as np
import pytest

from tools import shm


@pytest.fixture
def mapping():
    """A 4x2 mapping under a throwaway name, so tests never touch the real Local\\CyberblockFrame."""
    name = f"Local\\CyberblockTest-{uuid.uuid4().hex}"
    mm = shm.create_mapping(name, 4, 2)
    yield name, mm
    mm.close()


def known_frame():
    color = np.arange(2 * 4 * 4, dtype=np.uint8).reshape(2, 4, 4)
    depth = np.linspace(0.0, 1.0, 8, dtype=np.float32).reshape(2, 4)
    overlay = (255 - color).astype(np.uint8)
    return color, depth, overlay


def test_reads_back_the_frame_that_was_written(mapping):
    name, mm = mapping
    color, depth, overlay = known_frame()
    slot = shm.write_test_frame(mm, color, depth, overlay, mc_frame=7, host_frame=42, near=0.05, far=512.0, fov=70.0,
                                pos=(1.5, 64.0, -2.5), rot=(90.0, 10.0, 0.0), first_person=False, capture_ns=11, publish_ns=22)
    f = shm.read_latest(name)
    assert f is not None and f["slot"] == slot and f["seq"] == 2 and (f["width"], f["height"]) == (4, 2)
    np.testing.assert_array_equal(f["color"], color)
    np.testing.assert_array_equal(f["depth"], depth)
    np.testing.assert_array_equal(f["overlay"], overlay)
    assert f["pose"] == {"mc_frame": 7, "host_frame": 42, "near": pytest.approx(0.05), "far": 512.0, "fov": 70.0,
                         "flags": 7, "pos": (1.5, 64.0, -2.5), "rot": (90.0, 10.0, 0.0), "first_person": False,
                         "capture_ns": 11, "publish_ns": 22}


def test_rows_are_returned_top_down_for_either_row_order(mapping):
    name, mm = mapping
    color, depth, overlay = known_frame()
    shm.write_test_frame(mm, color, depth, overlay, flags=shm.FLAG_ZERO_TO_ONE | shm.FLAG_REVERSED_Z)  # stored top-down
    top_down = shm.read_latest(name)
    shm.write_test_frame(mm, color, depth, overlay)  # stored bottom-up
    bottom_up = shm.read_latest(name)
    for f in (top_down, bottom_up):
        np.testing.assert_array_equal(f["color"], color)
    raw = bytes(mm[shm.HEADER + bottom_up["slot"] * 96:][:16])  # first stored row of the bottom-up slot is the image's last row
    assert raw == color[1].tobytes()


def test_slots_rotate_and_seq_advances(mapping):
    name, mm = mapping
    color, depth, overlay = known_frame()
    slots = [shm.write_test_frame(mm, color, depth, overlay) for _ in range(4)]
    assert slots == [0, 1, 2, 0]
    assert shm.read_latest(name)["seq"] == 4  # slot 0 was published twice


def test_nothing_published_yet_is_none(mapping):
    assert shm.read_latest(mapping[0]) is None


def test_missing_mapping_is_none_and_not_created():
    name = f"Local\\CyberblockTest-{uuid.uuid4().hex}"
    assert shm.read_latest(name) is None
    assert shm.read_latest(name) is None  # still absent, the reader did not create it


def test_wrong_magic_is_rejected(mapping):
    name, mm = mapping
    shm.write_test_frame(mm, *known_frame())
    assert shm.read_latest(name) is not None
    struct.pack_into("<i", mm, 0, 0x5450434D)  # the GTA reference's "MCPT"
    assert shm.read_latest(name) is None


def test_odd_seq_is_rejected(mapping):
    name, mm = mapping
    slot = shm.write_test_frame(mm, *known_frame())
    desc = shm.SLOT_DESC + shm.SLOT_DESC_BYTES * slot
    (seq,) = struct.unpack_from("<q", mm, desc)
    struct.pack_into("<q", mm, desc, seq + 1)  # writer is mid-write
    assert shm.read_latest(name) is None
    struct.pack_into("<q", mm, desc, seq + 2)  # writer finished
    assert shm.read_latest(name)["seq"] == seq + 2


def test_nonsense_slot_geometry_is_rejected(mapping):
    name, mm = mapping
    slot = shm.write_test_frame(mm, *known_frame())
    struct.pack_into("<i", mm, shm.SLOT_DESC + shm.SLOT_DESC_BYTES * slot + 24, 4096)  # width beyond the mapping's maximum
    assert shm.read_latest(name) is None


def test_header_claiming_more_than_the_mapping_holds_is_rejected(mapping):
    name, mm = mapping
    shm.write_test_frame(mm, *known_frame())
    struct.pack_into("<q", mm, 16, 1 << 40)  # stride
    assert shm.read_latest(name) is None


def test_another_process_can_read_it(mapping):
    name, mm = mapping
    color, depth, overlay = known_frame()
    shm.write_test_frame(mm, color, depth, overlay, host_frame=99)
    code = f"from tools import shm; f = shm.read_latest({name!r}); print(f['pose']['host_frame'], int(f['color'].sum()))"
    out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True).stdout.split()
    assert out == ["99", str(int(color.sum()))]


def test_linear_depth_reversed_zero_to_one():
    depth = np.array([[1.0, 0.5, 0.0]], dtype=np.float32)
    f = {"depth": depth, "pose": {"near": 0.05, "far": 1000.0, "flags": shm.FLAG_ZERO_TO_ONE | shm.FLAG_REVERSED_Z}}
    z = shm.linear_depth(f)
    assert math.isclose(z[0, 0], 0.05, rel_tol=1e-6) and math.isclose(z[0, 1], 0.05 * 1000 / (0.05 + 0.5 * 999.95), rel_tol=1e-6)
    assert math.isinf(z[0, 2])
