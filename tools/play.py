"""Start the Minecraft dev client (world "cyberblock"), wait until the mod listens on 127.0.0.1:25600, then start
Cyberpunk 2077 through Steam. Ctrl+C stops Minecraft.

    uv run tools/play.py             # Minecraft, then Cyberpunk
    uv run tools/play.py --no-game   # Minecraft only (start Cyberpunk yourself, or test with tools.fakehost)

Needs a JDK 25: JAVA_HOME, %LOCALAPPDATA%\\Cyberblock\\jdk-25*, a Temurin install from the registry, or `java` on PATH.
Stdlib only, so it also runs as a plain script.
"""
import argparse
import contextlib
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import winreg
from collections.abc import Callable, Iterable, Iterator
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PORT = 25600
MC_ARGS = "--quickPlaySingleplayer cyberblock"
STEAM_URL = "steam://rungameid/1091500"  # Cyberpunk 2077
MIN_JAVA = 25


def adoptium_homes() -> Iterator[str]:
    """Install folders of every Eclipse Temurin JDK the MSI registered (HKLM\\SOFTWARE\\Eclipse Adoptium\\JDK)."""
    with contextlib.suppress(OSError), winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Eclipse Adoptium\JDK") as jdks:
        for i in range(winreg.QueryInfoKey(jdks)[0]):
            with contextlib.suppress(OSError), winreg.OpenKey(jdks, winreg.EnumKey(jdks, i) + r"\hotspot\MSI") as msi:
                yield winreg.QueryValueEx(msi, "Path")[0]


def default_jdk_candidates() -> Iterator[str | Path | None]:
    yield os.environ.get("JAVA_HOME")
    yield from sorted(Path(os.environ.get("LOCALAPPDATA", "")).joinpath("Cyberblock").glob("jdk-*"), reverse=True)
    yield from adoptium_homes()
    if java := shutil.which("java"):
        yield Path(java).resolve().parent.parent


def find_jdk(candidates: Iterable[str | Path | None]) -> Path | None:
    """The first candidate that is a JDK of version MIN_JAVA or newer (it has bin\\java.exe and a matching `release` file)."""
    for candidate in candidates:
        if not candidate:
            continue
        home = Path(candidate)
        with contextlib.suppress(OSError):
            version = re.search(r'JAVA_VERSION="(\d+)', (home / "release").read_text())
            if (home / "bin/java.exe").is_file() and version and int(version.group(1)) >= MIN_JAVA:
                return home
    return None


def port_open(port: int) -> bool:
    try:
        socket.create_connection(("127.0.0.1", port), timeout=1).close()
        return True
    except OSError:
        return False


def stop(proc: subprocess.Popen) -> None:
    """Kill Minecraft's launcher and its children. The Gradle daemon cancels the build when its client dies."""
    if proc.poll() is None:
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
        proc.wait()


def play(mc: Path, java_home: Path, port: int, timeout: float, start_game: Callable[[], None] | None) -> int:
    """Start the Minecraft dev client from `mc`, wait for `port`, call start_game. Returns the exit code."""
    if port_open(port):
        print(f"something already listens on 127.0.0.1:{port}; assuming Minecraft is running and not starting another")
        proc = None
    else:
        gradlew = mc / "gradlew.bat"
        if not gradlew.is_file():
            print(f"{gradlew} not found: is the Minecraft mod (mc/) in this checkout?", file=sys.stderr)
            return 1
        print(f"starting Minecraft: gradlew.bat runClient --args=\"{MC_ARGS}\" (JAVA_HOME={java_home})")
        # Own process group: the console's Ctrl+C then reaches only us, and we stop the whole tree ourselves.
        proc = subprocess.Popen([str(gradlew), "runClient", f"--args={MC_ARGS}"], cwd=mc, env={**os.environ, "JAVA_HOME": str(java_home)},
                                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
    try:
        if proc is not None:
            print(f"waiting up to {timeout:.0f} s for Minecraft to open 127.0.0.1:{port} (first start downloads and builds, be patient) ...")
            start = time.monotonic()
            while not port_open(port):
                if proc.poll() is not None:
                    print(f"Minecraft exited with code {proc.returncode} before opening 127.0.0.1:{port}", file=sys.stderr)
                    return proc.returncode or 1
                if time.monotonic() - start > timeout:
                    print(f"Minecraft did not open 127.0.0.1:{port} within {timeout:.0f} s; stopping it", file=sys.stderr)
                    return 1
                time.sleep(1)
            print("Minecraft is listening")
        if start_game:
            print("starting Cyberpunk 2077 through Steam")
            start_game()
        if proc is not None:
            print("Minecraft is running; press Ctrl+C here to stop it")
            return proc.wait()
        return 0
    except KeyboardInterrupt:
        print("stopping Minecraft")
        return 0
    finally:
        if proc is not None:
            stop(proc)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Start the Minecraft dev client, then Cyberpunk 2077.")
    ap.add_argument("--no-game", action="store_true", help="start only Minecraft, not Cyberpunk 2077")
    ap.add_argument("--timeout", type=float, default=120, help="seconds to wait for Minecraft to listen (default 120)")
    args = ap.parse_args(argv)
    jdk = find_jdk(default_jdk_candidates())
    if jdk is None:
        print(f"no JDK {MIN_JAVA} found: set JAVA_HOME to one, or install Temurin {MIN_JAVA}", file=sys.stderr)
        return 1
    return play(ROOT / "mc", jdk, PORT, args.timeout, None if args.no_game else lambda: os.startfile(STEAM_URL))


if __name__ == "__main__":
    sys.exit(main())
