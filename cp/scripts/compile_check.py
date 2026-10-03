"""Compile-check cp/scripts/Cyberblock against the installed game's scripts without touching the game folder.

Usage: uv run python cp/scripts/compile_check.py [--game "<Cyberpunk 2077 dir>"]

The game folder is only read: scc_lib.dll, the vanilla r6/cache/final.redscripts and Codeware's scripts are
copied into a fresh temp tree, and the compiler (redscript's scc_lib C API, the library behind
engine/tools/scc.exe) runs with that tree as its r6 directory, so the cache, backup, timestamp and log files
all land in the temp tree. The error popup is disabled. Exit code 0 means zero compile errors.
"""
import argparse
import ctypes
import shutil
import sys
import tempfile
from pathlib import Path

DEFAULT_GAME = r"D:\SteamLibrary\steamapps\common\Cyberpunk 2077"
OURS = Path(__file__).resolve().parent / "Cyberblock"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--game", default=DEFAULT_GAME)
    game = Path(parser.parse_args().game)

    work = Path(tempfile.gettempdir()) / "cyberblock-scc"
    shutil.rmtree(work, ignore_errors=True)
    r6 = work / "r6"
    for sub in ("cache", "logs", "scripts"):
        (r6 / sub).mkdir(parents=True)

    shutil.copy2(game / "engine" / "tools" / "scc_lib.dll", work / "scc_lib.dll")
    shutil.copy2(game / "r6" / "cache" / "final.redscripts", r6 / "cache" / "final.redscripts")
    shutil.copytree(game / "red4ext" / "plugins" / "Codeware" / "Scripts", r6 / "scripts" / "Codeware",
                    ignore=shutil.ignore_patterns("*.log"))
    shutil.copytree(OURS, r6 / "scripts" / "Cyberblock")

    scc = ctypes.CDLL(str(work / "scc_lib.dll"))
    scc.scc_settings_new.restype = ctypes.c_void_p
    scc.scc_settings_new.argtypes = [ctypes.c_char_p]
    scc.scc_settings_set_custom_cache_file.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    scc.scc_settings_disable_error_popup.argtypes = [ctypes.c_void_p]
    scc.scc_compile.restype = ctypes.c_void_p
    scc.scc_compile.argtypes = [ctypes.c_void_p]
    scc.scc_get_success.restype = ctypes.c_void_p
    scc.scc_get_success.argtypes = [ctypes.c_void_p]
    scc.scc_copy_error.restype = ctypes.c_size_t
    scc.scc_copy_error.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_size_t]
    scc.scc_free_result.argtypes = [ctypes.c_void_p]

    settings = scc.scc_settings_new(str(r6).encode())
    scc.scc_settings_set_custom_cache_file(settings, str(r6 / "cache" / "final.redscripts").encode())
    scc.scc_settings_disable_error_popup(settings)
    result = scc.scc_compile(settings)  # takes ownership of settings
    ok = bool(scc.scc_get_success(result))
    if not ok:
        buffer = ctypes.create_string_buffer(1 << 16)
        scc.scc_copy_error(result, buffer, len(buffer))
        print(buffer.value.decode(errors="replace"), file=sys.stderr)
    scc.scc_free_result(result)

    print(f"compile_check: {'OK' if ok else 'FAILED'} (temp tree {work})")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
