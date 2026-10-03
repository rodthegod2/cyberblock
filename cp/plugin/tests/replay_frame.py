"""Synthetic Minecraft frames for checking the compositor in game without Minecraft (plan task F1).

Writes a frame into the shared memory "Local\\CyberblockFrame" and keeps republishing it:
  world   checkerboard (magenta / black, 64 px cells) at a constant depth of 5 m
  overlay a red square near the top-left corner, transparent elsewhere

In game: ReShade overlay (Home) -> enable "Cyberblock" and tick "Show test frames without the link". The checkerboard
shows wherever Night City is farther than 5 m, so a wall 5 m away should cut it exactly at the wall; the red square is
always on top.

The mapping uses the reference FrameExporter layout (plan section 1.3) with the same sizes as the Minecraft mod, so the
two are interchangeable; run one at a time. tools/shm.py was not available, so the writer lives here.

The game's compositor keeps the mapping open once it has seen it, so a mapping left behind by an earlier replay or
Minecraft session is reused. The script refuses (exit 2) only while another writer is alive: its publish counter moves,
or the writer process recorded in the header is still running (Minecraft can sit idle without publishing).

    uv run python cp/plugin/tests/replay_frame.py [--width 1920] [--height 1080] [--hz 30] [--seconds 0]
"""
import argparse
import ctypes
import mmap
import os
import struct
import sys
import time

import numpy as np

NAME = "Local\\CyberblockFrame"
MAGIC = 0x52464243  # "CBFR"
VERSION = 1
HEADER = 4096
SLOTS = 3
SLOT_DESC = 256
SLOT_DESC_BYTES = 128
MAX_W, MAX_H = 3840, 2160
STRIDE = MAX_W * MAX_H * 4 * 3
FLAGS = 1 | 2 | 4  # depth in [0, 1], rows bottom-up, reversed Z (as the mod writes)
NEAR, FAR = 0.05, 1024.0
FOV = 60.0  # vertical; with test frames the compositor shows the frame as rendered, so the pose does not matter
DEPTH_M = 5.0
FILE_MAP_READ = 0x0004
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
SYNCHRONIZE = 0x00100000
WAIT_TIMEOUT = 0x102
LIVE_CHECK_S = 0.25  # a writer that publishes at all does so within this long


def reversed_depth(metres: float) -> float:
    """Reversed-Z [0, 1] depth of a point `metres` along the view axis (inverse of Cyberblock.fx's mc_linear)."""
    return (NEAR * FAR / metres - NEAR) / (FAR - NEAR)


def make_layers(w: int, h: int) -> tuple[bytes, bytes, bytes]:
    """World colour, world depth and overlay, rows bottom-up, as the mod exports them (RGBA8 premultiplied, float32)."""
    y, x = np.mgrid[0:h, 0:w]
    checker = ((x // 64 + y // 64) % 2).astype(bool)
    world = np.zeros((h, w, 4), np.uint8)
    world[checker] = (248, 0, 248, 255)
    world[~checker] = (0, 0, 0, 255)
    depth = np.full((h, w), reversed_depth(DEPTH_M), np.float32)
    overlay = np.zeros((h, w, 4), np.uint8)
    edge, size = h // 16, h // 8
    overlay[edge:edge + size, edge:edge + size] = (255, 0, 0, 255)  # top-left on screen
    return tuple(layer[::-1].tobytes() for layer in (world, depth, overlay))  # bottom-up rows (flag 2)


def kernel32():
    k = ctypes.WinDLL("kernel32", use_last_error=True)
    k.OpenFileMappingW.restype = ctypes.c_void_p
    k.OpenFileMappingW.argtypes = (ctypes.c_uint32, ctypes.c_int, ctypes.c_wchar_p)
    k.MapViewOfFile.restype = ctypes.c_void_p
    k.MapViewOfFile.argtypes = (ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_size_t)
    k.UnmapViewOfFile.argtypes = (ctypes.c_void_p,)
    k.OpenProcess.restype = ctypes.c_void_p
    k.OpenProcess.argtypes = (ctypes.c_uint32, ctypes.c_int, ctypes.c_uint32)
    k.WaitForSingleObject.argtypes = (ctypes.c_void_p, ctypes.c_uint32)
    k.WaitForSingleObject.restype = ctypes.c_uint32
    k.GetProcessTimes.argtypes = (ctypes.c_void_p,) + (ctypes.POINTER(ctypes.c_uint64),) * 4
    k.CloseHandle.argtypes = (ctypes.c_void_p,)
    return k


def writer_running(pid: int, last_publish_ns: int | None) -> bool:
    """Whether process `pid` runs and already existed at the last publish (a newer process merely reused the pid)."""
    k = kernel32()
    handle = k.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, False, pid)
    if not handle:
        return False
    try:
        if k.WaitForSingleObject(handle, 0) != WAIT_TIMEOUT:
            return False  # exited
        times = [ctypes.c_uint64() for _ in range(4)]  # creation, exit, kernel, user
        if last_publish_ns is None or not k.GetProcessTimes(handle, *(ctypes.byref(t) for t in times)):
            return True
        created = times[0].value / 1e7 - 11644473600  # FILETIME (100 ns ticks since 1601) to Unix seconds
        # publish times are QueryPerformanceCounter based (System.nanoTime, perf_counter_ns) like this clock
        published = time.time() - (time.perf_counter_ns() - last_publish_ns) / 1e9
        return created < published
    finally:
        k.CloseHandle(handle)


def mapping_in_use() -> str | None:
    """Why NAME can't be written now, or None: it doesn't exist, or it is the same layout left by a writer that stopped."""
    k = kernel32()
    handle = k.OpenFileMappingW(FILE_MAP_READ, False, NAME)
    if not handle:
        return None
    try:
        view = k.MapViewOfFile(handle, FILE_MAP_READ, 0, 0, HEADER)
        if not view:
            return "it exists but cannot be read"
        try:
            magic, _, header, slots, stride = struct.unpack("<iiiiq", ctypes.string_at(view, 24))
            if (magic, header, slots, stride) != (MAGIC, HEADER, SLOTS, STRIDE):
                return f"it exists with another layout (magic {magic:#x}, {slots} slots, stride {stride})"
            counter = struct.unpack("<q", ctypes.string_at(view + 32, 8))[0]
            time.sleep(LIVE_CHECK_S)
            latest, pid = struct.unpack("<ii", ctypes.string_at(view + 40, 8))
            if struct.unpack("<q", ctypes.string_at(view + 32, 8))[0] != counter:
                return f"a writer (pid {pid}) is publishing frames to it"
            last_publish = None
            if 0 <= latest < SLOTS:
                last_publish = struct.unpack("<q", ctypes.string_at(view + SLOT_DESC + SLOT_DESC_BYTES * latest + 96, 8))[0]
            if writer_running(pid, last_publish):
                return f"its writer (pid {pid}) is still running"
            return None
        finally:
            k.UnmapViewOfFile(view)
    finally:
        k.CloseHandle(handle)


class FrameWriter:
    """The writer half of the frame export: header once, then each publish fills the next slot under its seqlock."""

    def __init__(self):
        # creates the mapping, or opens the one an earlier writer left behind (mapping_in_use checked its layout)
        self.mm = mmap.mmap(-1, HEADER + STRIDE * SLOTS, tagname=NAME)
        published, latest = 0, -1
        if struct.unpack_from("<i", self.mm, 0)[0] == MAGIC:
            # continue the old counter so a reader that saw the last frame notices the new ones
            published, latest = struct.unpack_from("<qi", self.mm, 32)
        struct.pack_into("<iiiiqii", self.mm, 0, MAGIC, VERSION, HEADER, SLOTS, STRIDE, MAX_W, MAX_H)
        struct.pack_into("<qii", self.mm, 32, published, latest, os.getpid())
        self.slot = (latest + 1) % SLOTS
        self.published = published
        self.count = 0

    def publish(self, w: int, h: int, layers: tuple[bytes, bytes, bytes]):
        mm, slot = self.mm, self.slot
        self.slot = (slot + 1) % SLOTS
        desc = SLOT_DESC + SLOT_DESC_BYTES * slot
        seq = struct.unpack_from("<q", mm, desc)[0]
        seq += seq & 1
        struct.pack_into("<q", mm, desc, seq + 1)  # odd: being written
        base, n = HEADER + STRIDE * slot, w * h * 4
        for i, layer in enumerate(layers):
            mm[base + i * n:base + (i + 1) * n] = layer
        now = time.perf_counter_ns()
        self.published += 1
        self.count += 1
        struct.pack_into("<qqiifffidddfffiqq", mm, desc + 8,
                         self.published, 0, w, h, NEAR, FAR, FOV, FLAGS,
                         0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1, now, now)
        struct.pack_into("<q", mm, desc, seq + 2)
        struct.pack_into("<i", mm, 40, slot)
        struct.pack_into("<q", mm, 32, self.published)

    def close(self):
        self.mm.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--width", type=int, default=1920, help="frame width; match your screen's aspect ratio")
    parser.add_argument("--height", type=int, default=1080)
    parser.add_argument("--hz", type=float, default=30.0, help="publish rate")
    parser.add_argument("--seconds", type=float, default=0.0, help="stop after this long (0: until Ctrl+C)")
    args = parser.parse_args()
    if not (0 < args.width <= MAX_W and 0 < args.height <= MAX_H):
        parser.error(f"frame size must be within 1x1 .. {MAX_W}x{MAX_H}")
    if args.hz <= 0 or args.seconds < 0:
        parser.error("--hz must be positive and --seconds not negative")
    if sys.platform != "win32":
        parser.error("Windows only (named shared memory)")
    busy = mapping_in_use()
    if busy:
        print(f"{NAME} is in use: {busy} (Minecraft or another replay); stop it first", file=sys.stderr)
        return 2

    layers = make_layers(args.width, args.height)
    try:
        writer = FrameWriter()
    except OSError as e:
        print(f"could not create {NAME} ({(HEADER + STRIDE * SLOTS) >> 20} MB): {e}", file=sys.stderr)
        return 1
    print(f"publishing a {args.width}x{args.height} test frame ({DEPTH_M:g} m checkerboard, red square) to {NAME} "
          f"at {args.hz:g} Hz; Ctrl+C stops")
    end = time.monotonic() + args.seconds if args.seconds else None
    try:
        while end is None or time.monotonic() < end:
            writer.publish(args.width, args.height, layers)
            time.sleep(1.0 / args.hz)
    except KeyboardInterrupt:
        pass
    finally:
        writer.close()
    print(f"published {writer.count} frames")
    return 0


if __name__ == "__main__":
    sys.exit(main())
