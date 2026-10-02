"""Synthetic Minecraft frames for checking the compositor in game without Minecraft (plan task F1).

Writes a frame into the shared memory "Local\\CyberblockFrame" and keeps republishing it:
  world   checkerboard (magenta / black, 64 px cells) at a constant depth of 5 m
  overlay a red square near the top-left corner, transparent elsewhere

In game: ReShade overlay (Home) -> enable "Cyberblock" and tick "Show test frames without the link". The checkerboard
shows wherever Night City is farther than 5 m, so a wall 5 m away should cut it exactly at the wall; the red square is
always on top.

The mapping uses the reference FrameExporter layout (plan section 1.3) with the same sizes as the Minecraft mod, so the
two are interchangeable; run one at a time. tools/shm.py was not available, so the writer lives here.

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


def mapping_exists() -> bool:
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.OpenFileMappingW.restype = ctypes.c_void_p
    kernel32.OpenFileMappingW.argtypes = (ctypes.c_uint32, ctypes.c_int, ctypes.c_wchar_p)
    kernel32.CloseHandle.argtypes = (ctypes.c_void_p,)
    handle = kernel32.OpenFileMappingW(FILE_MAP_READ, False, NAME)
    if handle:
        kernel32.CloseHandle(handle)
    return bool(handle)


class FrameWriter:
    """The writer half of the frame export: header once, then each publish fills the next slot under its seqlock."""

    def __init__(self):
        self.mm = mmap.mmap(-1, HEADER + STRIDE * SLOTS, tagname=NAME)
        struct.pack_into("<iiiiqii", self.mm, 0, MAGIC, VERSION, HEADER, SLOTS, STRIDE, MAX_W, MAX_H)
        struct.pack_into("<qii", self.mm, 32, 0, -1, os.getpid())
        self.slot = 0
        self.published = 0

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
    if mapping_exists():
        print(f"{NAME} already exists: Minecraft or another replay is exporting frames; stop it first", file=sys.stderr)
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
    print(f"published {writer.published} frames")
    return 0


if __name__ == "__main__":
    sys.exit(main())
