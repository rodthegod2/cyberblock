"""Reader and test writer for the frames Minecraft publishes in the shared memory "Local\\CyberblockFrame" (Windows only).

Layout: shared/protocol.md (the reference FrameExporter.java layout, magic 0x52464243 "CBFR").

    frame = read_latest()      # None until Minecraft has published a frame
    frame["color"], frame["depth"], frame["overlay"]   # top-down numpy arrays: RGBA8 premultiplied, float32 raw, RGBA8

The reader opens the mapping read-only and never creates it. `create_mapping` / `write_test_frame` are the writer half,
used by the tests and by the synthetic frame replayer; they go through the same layout constants.
"""
import contextlib
import ctypes
import ctypes.wintypes as wt
import mmap
import os
import struct

import numpy as np

NAME = "Local\\CyberblockFrame"
MAGIC = 0x52464243
VERSION = 1
HEADER = 4096
SLOT_DESC = 256
SLOT_DESC_BYTES = 128
FLAG_ZERO_TO_ONE, FLAG_BOTTOM_UP, FLAG_REVERSED_Z = 1, 2, 4
HEADER_FMT = "<iiiiqiiqii"  # magic, version, header bytes, slots, stride, max w, max h, publish counter, latest slot, pid
SLOT_FMT = "<qqiifffidddfffiqq"  # at slot descriptor +8: mc frame, host frame, w, h, near, far, fov, flags, xyz, yaw/pitch/roll, fp, capture, publish
HEADER_SIZE, SLOT_SIZE = struct.calcsize(HEADER_FMT), struct.calcsize(SLOT_FMT)
FILE_MAP_READ = 0x4
READ_ATTEMPTS = 3


class _MemoryBasicInformation(ctypes.Structure):
    _fields_ = [("BaseAddress", ctypes.c_void_p), ("AllocationBase", ctypes.c_void_p), ("AllocationProtect", wt.DWORD),
                ("PartitionId", wt.WORD), ("RegionSize", ctypes.c_size_t), ("State", wt.DWORD), ("Protect", wt.DWORD),
                ("Type", wt.DWORD)]


_k32 = ctypes.WinDLL("kernel32", use_last_error=True)
_k32.OpenFileMappingW.restype, _k32.OpenFileMappingW.argtypes = wt.HANDLE, [wt.DWORD, wt.BOOL, wt.LPCWSTR]
_k32.MapViewOfFile.restype = ctypes.c_void_p
_k32.MapViewOfFile.argtypes = [wt.HANDLE, wt.DWORD, wt.DWORD, wt.DWORD, ctypes.c_size_t]
_k32.UnmapViewOfFile.argtypes = [ctypes.c_void_p]
_k32.VirtualQuery.argtypes = [ctypes.c_void_p, ctypes.POINTER(_MemoryBasicInformation), ctypes.c_size_t]
_k32.CloseHandle.argtypes = [wt.HANDLE]


@contextlib.contextmanager
def _view(name: str):
    """Map the named section read-only. Yields (address, size in bytes), or None if it does not exist."""
    handle = _k32.OpenFileMappingW(FILE_MAP_READ, False, name)
    base = _k32.MapViewOfFile(handle, FILE_MAP_READ, 0, 0, 0) if handle else None
    try:
        if not base:
            yield None
            return
        info = _MemoryBasicInformation()
        _k32.VirtualQuery(base, ctypes.byref(info), ctypes.sizeof(info))
        yield base, info.RegionSize
    finally:
        if base:
            _k32.UnmapViewOfFile(base)
        if handle:
            _k32.CloseHandle(handle)


def _read_slot(base: int, header: int, stride: int, max_w: int, max_h: int, slot: int) -> dict | None:
    """One seqlock attempt at a slot; None if it is mid-write, changed during the copy, or describes nonsense."""
    desc = base + SLOT_DESC + SLOT_DESC_BYTES * slot
    (seq,) = struct.unpack("<q", ctypes.string_at(desc, 8))
    if seq & 1:
        return None
    (mc_frame, host_frame, w, h, near, far, fov, flags, x, y, z, yaw, pitch, roll, fp, capture_ns, publish_ns) = \
        struct.unpack(SLOT_FMT, ctypes.string_at(desc + 8, SLOT_SIZE))
    if not (0 < w <= max_w and 0 < h <= max_h):
        return None
    n = w * h * 4
    color, depth, overlay = np.empty((h, w, 4), np.uint8), np.empty((h, w), np.float32), np.empty((h, w, 4), np.uint8)
    data = base + header + stride * slot
    for i, layer in enumerate((color, depth, overlay)):
        ctypes.memmove(layer.ctypes.data, data + i * n, n)
    if struct.unpack("<q", ctypes.string_at(desc, 8))[0] != seq:
        return None
    if flags & FLAG_BOTTOM_UP:
        color, depth, overlay = color[::-1], depth[::-1], overlay[::-1]
    pose = {"mc_frame": mc_frame, "host_frame": host_frame, "near": near, "far": far, "fov": fov, "flags": flags,
            "pos": (x, y, z), "rot": (yaw, pitch, roll), "first_person": bool(fp),
            "capture_ns": capture_ns, "publish_ns": publish_ns}
    return {"seq": seq, "slot": slot, "width": w, "height": h, "pose": pose, "color": color, "depth": depth, "overlay": overlay}


def read_latest(name: str = NAME) -> dict | None:
    """The most recently completed frame, or None (no mapping, wrong magic, nothing published, or the slot kept changing)."""
    with _view(name) as v:
        if v is None or v[1] < HEADER_SIZE:
            return None
        base, size = v
        magic, version, header, slots, stride, max_w, max_h, _, latest, _ = struct.unpack(HEADER_FMT, ctypes.string_at(base, HEADER_SIZE))
        if magic != MAGIC or version != VERSION or not 0 <= latest < slots:
            return None
        if header < SLOT_DESC + SLOT_DESC_BYTES * slots or stride < 0 or header + stride * slots > size or 12 * max_w * max_h > stride:
            return None
        for _ in range(READ_ATTEMPTS):
            if frame := _read_slot(base, header, stride, max_w, max_h, latest):
                return frame
        return None


def read_timing(name: str = NAME) -> dict | None:
    """The newest frame's size and times without its pixels (megaplan 9-F1 measurements): {"published": the publish counter,
    "width", "height", "capture_ns", "publish_ns"} (both times System.nanoTime of the Minecraft process), or None when there is
    no mapping, nothing published yet, or the slot was being rewritten."""
    with _view(name) as v:
        if v is None or v[1] < HEADER_SIZE:
            return None
        base, _ = v
        magic, version, _, slots, _, _, _, published, latest, _ = struct.unpack(HEADER_FMT, ctypes.string_at(base, HEADER_SIZE))
        if magic != MAGIC or version != VERSION or not 0 <= latest < slots:
            return None
        desc = base + SLOT_DESC + SLOT_DESC_BYTES * latest
        (seq,) = struct.unpack("<q", ctypes.string_at(desc, 8))
        fields = struct.unpack(SLOT_FMT, ctypes.string_at(desc + 8, SLOT_SIZE))
        if seq & 1 or struct.unpack("<q", ctypes.string_at(desc, 8))[0] != seq:
            return None
        return {"published": published, "width": fields[2], "height": fields[3], "capture_ns": fields[-2], "publish_ns": fields[-1]}


def linear_depth(frame: dict) -> np.ndarray:
    """View-space depth in metres along the view axis (inf where Minecraft drew nothing), from the frame's raw depth."""
    n, f, flags = frame["pose"]["near"], frame["pose"]["far"], frame["pose"]["flags"]
    d = frame["depth"].astype(np.float64)
    ndc = 2.0 * d - 1.0  # used only when depth is [-1, 1] mapped to [0, 1] window depth
    if flags & FLAG_REVERSED_Z:  # 1 = near, 0 = far
        z = n * f / (n + d * (f - n)) if flags & FLAG_ZERO_TO_ONE else 2.0 * n * f / ((f + n) + ndc * (f - n))
        z[d <= 0.0] = np.inf
    else:
        z = n * f / (f - d * (f - n)) if flags & FLAG_ZERO_TO_ONE else 2.0 * n * f / ((f + n) - ndc * (f - n))
        z[d >= 1.0] = np.inf
    return z


def create_mapping(name: str, max_w: int, max_h: int, slots: int = 3) -> mmap.mmap:
    """Create the named mapping with an initialised header (what FrameExporter does). Keep the result alive while in use."""
    stride = max_w * max_h * 4 * 3
    mm = mmap.mmap(-1, HEADER + stride * slots, tagname=name)
    struct.pack_into(HEADER_FMT, mm, 0, MAGIC, VERSION, HEADER, slots, stride, max_w, max_h, 0, -1, os.getpid())
    return mm


def write_test_frame(mm: mmap.mmap, color: np.ndarray, depth: np.ndarray, overlay: np.ndarray, *, mc_frame: int = 0,
                     host_frame: int = 0, near: float = 0.05, far: float = 1024.0, fov: float = 70.0,
                     flags: int = FLAG_ZERO_TO_ONE | FLAG_BOTTOM_UP | FLAG_REVERSED_Z, pos=(0.0, 0.0, 0.0),
                     rot=(0.0, 0.0, 0.0), first_person: bool = True, capture_ns: int = 0, publish_ns: int = 0) -> int:
    """Publish one frame the way FrameExporter does (next slot, seqlock, then latest slot and counter); returns its slot.

    color/overlay are top-down (h, w, 4) uint8 premultiplied, depth is (h, w) float32; they are stored bottom-up when
    `flags` has FLAG_BOTTOM_UP, so read_latest returns exactly what was written.
    """
    h, w = depth.shape
    _, _, header, slots, stride, max_w, max_h, counter, latest, _ = struct.unpack_from(HEADER_FMT, mm, 0)
    if w > max_w or h > max_h:
        raise ValueError(f"{w}x{h} does not fit a {max_w}x{max_h} mapping")
    slot = (latest + 1) % slots
    desc = SLOT_DESC + SLOT_DESC_BYTES * slot
    (seq,) = struct.unpack_from("<q", mm, desc)
    seq += seq & 1
    struct.pack_into("<q", mm, desc, seq + 1)  # odd while being written
    n, data = w * h * 4, header + stride * slot
    for i, (layer, dtype) in enumerate(((color, np.uint8), (depth, np.float32), (overlay, np.uint8))):
        rows = layer[::-1] if flags & FLAG_BOTTOM_UP else layer
        mm[data + i * n:data + (i + 1) * n] = np.ascontiguousarray(rows, dtype=dtype).tobytes()
    struct.pack_into(SLOT_FMT, mm, desc + 8, mc_frame, host_frame, w, h, near, far, fov, flags, *pos, *rot,
                     int(first_person), capture_ns, publish_ns)
    struct.pack_into("<q", mm, desc, seq + 2)
    struct.pack_into("<i", mm, 40, slot)
    struct.pack_into("<q", mm, 32, counter + 1)
    return slot
