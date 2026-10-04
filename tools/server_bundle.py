"""Stage the files for the Cyberblock dedicated server (plan 2-K3).

    uv run python -m tools.server_bundle --backup <server backup folder> --world <saves\\cyberblock> --mods-from <folder> ^
        --launcher <fabric server launcher jar> [--hosts Name1,Name2] [--dry-run]

Stages into %LOCALAPPDATA%\\Cyberblock\\server-staging\\ (outside the repo), ready for `tools.server_sync upload`:

    mods/                              the Cyberblock jar and the jars from --mods-from (Fabric API, voice chat)
    config/cyberblock/server.json      server/server.json.example, with `hosts` from --hosts
    server.properties                  the backup's own file with exactly level-name=cyberblock, allow-flight=true,
                                       white-list=true, enforce-whitelist=true, motd=Cyberblock (plus resource-pack and
                                       resource-pack-sha1 when --pack-url and --pack-sha1 are given)
    server.properties.rodsores         a byte-identical copy of the backup's file (the way back)
    cyberblock.zip                     the world folder, level.dat at cyberblock/level.dat (refused while session.lock is held)
    <launcher jar>                     the Fabric server launcher for 26.3, from --launcher (never downloaded by this tool)

Prints the list with sizes and the exact jar name to type into the Apex panel's Custom Jar field. --dry-run prints the same
list and writes nothing. Nothing from the backup is changed. Windows only (the session.lock check uses byte-range locks).
"""
import argparse
import json
import os
import re
import sys
import zipfile
from pathlib import Path

from tools import respack
from tools.package import PackageError, check_jar, version

ROOT = Path(__file__).resolve().parents[1]
WORLD_NAME = "cyberblock"
PROPERTIES = {"level-name": WORLD_NAME, "allow-flight": "true", "white-list": "true", "enforce-whitelist": "true", "motd": "Cyberblock"}
HOST_NAME = re.compile(r"^(\w{1,16}|[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12})$")
PACK_SHA1 = re.compile(r"^[0-9a-f]{40}$")


class WorldZip:
    """The world folder to be zipped as cyberblock/..., without its session.lock."""

    def __init__(self, folder: Path):
        self.files = {f"{WORLD_NAME}/{p.relative_to(folder).as_posix()}": p for p in sorted(folder.rglob("*"))
                      if p.is_file() and p.name != "session.lock"}
        self.size = sum(p.stat().st_size for p in self.files.values())


def session_lock_held(world: Path) -> bool:
    """True when another process (Minecraft) holds the world's session.lock: Java locks the whole file, a byte-range lock."""
    import msvcrt

    lock = world / "session.lock"
    if not lock.is_file():
        return False
    try:
        with lock.open("r+b") as f:
            try:
                msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError:
                return True
            f.seek(0)
            msvcrt.locking(f.fileno(), msvcrt.LK_UNLCK, 1)
    except OSError:
        return True  # cannot even open it: not safe to copy
    return False


def set_properties(raw: bytes, changes: dict[str, str]) -> bytes:
    """`raw` (a server.properties) with exactly the keys of `changes` set: other lines, comments and line endings are kept."""
    eol = b"\r\n" if b"\r\n" in raw else b"\n"
    lines = raw.splitlines(keepends=True)
    seen = set()
    for i, line in enumerate(lines):
        key = re.match(rb"\s*([^\s=:#!][^=:]*?)\s*[=:]", line)
        name = key.group(1).decode("latin-1") if key else None
        if name in changes:
            seen.add(name)
            lines[i] = f"{name}={changes[name]}".encode() + eol
    if lines and not lines[-1].endswith((b"\n", b"\r")):
        lines[-1] += eol
    lines += [f"{k}={v}".encode() + eol for k, v in changes.items() if k not in seen]
    return b"".join(lines)


def server_json(example: Path, hosts: list[str]) -> bytes:
    try:
        data = json.loads(example.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise PackageError(f"cannot read {example}: {e} (server/server.json.example comes with Phase 1, task 1-T)") from None
    if not isinstance(data, dict):
        raise PackageError(f"{example} must hold a JSON object")
    if hosts:
        data["hosts"] = hosts
    return (json.dumps(data, indent=2) + "\n").encode()


def check_launcher(launcher: Path) -> None:
    try:
        with zipfile.ZipFile(launcher) as z:
            z.getinfo("META-INF/MANIFEST.MF")
    except (OSError, KeyError, zipfile.BadZipFile):
        raise PackageError(f"{launcher.name} is not a runnable jar (no META-INF/MANIFEST.MF); download the Fabric server launcher for 26.3 from fabricmc.net") from None


def plan(backup: Path, world: Path, mods_from: Path, launcher: Path, jar: Path, example: Path, hosts: list[str],
         pack: tuple[str, str] | None, ver: str) -> dict[str, Path | bytes | WorldZip]:
    """Staged relative path -> source, after every input is checked. Writes nothing."""
    properties = backup / "server.properties"
    if not properties.is_file():
        raise PackageError(f"{backup} has no server.properties: is it a backup made by tools.server_sync?")
    if not (world / "level.dat").is_file():
        raise PackageError(f"{world} has no level.dat: pass the world folder itself (saves\\cyberblock)")
    if session_lock_held(world):
        raise PackageError(f"{world.name} is open in Minecraft (session.lock is held): close Minecraft, then run this again")
    if not jar.is_file():
        raise PackageError(f"missing {jar} (run gradlew build in mc/)")
    check_jar(jar)
    check_launcher(launcher)
    bad = [h for h in hosts if not HOST_NAME.fullmatch(h)]
    if bad:
        raise PackageError(f"not player names or UUIDs: {', '.join(bad)}")
    mods = sorted(p for p in mods_from.glob("*.jar") if p.is_file())
    if not any(m.name.lower().startswith("fabric-api") for m in mods):
        raise PackageError("the Fabric API jar (fabric-api-*.jar) is missing from the mods folder")
    if any(m.name.lower().startswith("cyberblock") for m in mods):
        raise PackageError("the mods folder must not hold a cyberblock jar: the mod is added from --jar")
    changes = dict(PROPERTIES)
    if pack:
        changes |= {"resource-pack": pack[0], "resource-pack-sha1": pack[1]}
    raw = properties.read_bytes()
    staged: dict[str, Path | bytes | WorldZip] = {f"mods/{m.name}": m for m in mods}
    staged[f"mods/cyberblock-{ver}.jar"] = jar
    staged["config/cyberblock/server.json"] = server_json(example, hosts)
    staged["server.properties"] = set_properties(raw, changes)
    staged["server.properties.rodsores"] = raw
    staged["cyberblock.zip"] = WorldZip(world)
    staged[launcher.name] = launcher
    return dict(sorted(staged.items()))


def size_of(source: Path | bytes | WorldZip) -> int:
    return len(source) if isinstance(source, bytes) else source.size if isinstance(source, WorldZip) else source.stat().st_size


def listing(staged: dict[str, Path | bytes | WorldZip], launcher: Path, rods_pack: bool, sizes: dict[str, int] | None = None) -> str:
    lines = []
    for name, source in staged.items():
        note = f"  ({len(source.files)} files before compression)" if isinstance(source, WorldZip) and sizes is None else ""
        lines.append(f"  {name:<46}{(sizes or {}).get(name, size_of(source)):>14,} bytes{note}")
    lines += ["", f"Apex panel, Server type > Custom Jar field: {launcher.name}",
              "Java version: 25.  After Start the console should show: Done, cyberblock loaded, cyberblock server: ready."]
    if rods_pack:
        lines.append("warning: the backup's server.properties pushes a resource pack (Rod's Ores): it stays in the staged file until you give --pack-url and --pack-sha1.")
    return "\n".join(lines)


def stage(staging: Path, staged: dict[str, Path | bytes | WorldZip]) -> dict[str, int]:
    """Write every entry under staging; returns the sizes actually written."""
    sizes = {}
    for name, source in staged.items():
        target = staging / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(source, WorldZip):
            respack.write_zip(target, source.files, level=6)
        elif isinstance(source, bytes):
            target.write_bytes(source)
        else:
            target.write_bytes(source.read_bytes())
        sizes[name] = target.stat().st_size
    return sizes


def default_staging() -> Path:
    base = os.environ.get("LOCALAPPDATA")
    if not base:
        raise PackageError("LOCALAPPDATA is not set: pass --staging")
    return Path(base) / "Cyberblock" / "server-staging"


def prepare_staging(staging: Path, clean: bool) -> None:
    if staging.resolve().is_relative_to(ROOT.resolve()):
        raise PackageError("the staging folder must be outside the repository")
    if staging.exists() and any(staging.iterdir()):
        if not (clean and staging.name == "server-staging"):
            raise PackageError(f"{staging} is not empty: use --clean (only allowed for a folder named server-staging)")
        for path in sorted(staging.rglob("*"), reverse=True):
            path.rmdir() if path.is_dir() else path.unlink()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Stage the Cyberblock dedicated-server files (outside the repo).")
    ap.add_argument("--backup", type=Path, required=True, help="the server backup folder made by tools.server_sync (has server.properties)")
    ap.add_argument("--world", type=Path, required=True, help="the local world folder (...\\saves\\cyberblock); Minecraft must be closed")
    ap.add_argument("--mods-from", type=Path, required=True, help="folder with the downloaded Fabric API and voice chat jars")
    ap.add_argument("--launcher", type=Path, required=True, help="the Fabric server launcher jar for 26.3 (downloaded from fabricmc.net)")
    ap.add_argument("--hosts", default="", help="comma-separated player names or UUIDs allowed to host (server.json `hosts`)")
    ap.add_argument("--pack-url", help="HTTPS URL of the resource pack, once it has one")
    ap.add_argument("--pack-sha1", help="its SHA-1 (tools.respack prints it)")
    ap.add_argument("--jar", type=Path, help="the mod jar (default mc/build/libs/cyberblock-<version>.jar)")
    ap.add_argument("--example", type=Path, default=ROOT / "server/server.json.example")
    ap.add_argument("--staging", type=Path, help="default %%LOCALAPPDATA%%\\Cyberblock\\server-staging")
    ap.add_argument("--clean", action="store_true", help="empty an existing staging folder first")
    ap.add_argument("--dry-run", action="store_true", help="print the list, write nothing")
    args = ap.parse_args(argv)
    try:
        if bool(args.pack_url) != bool(args.pack_sha1):
            raise PackageError("--pack-url and --pack-sha1 go together")
        if args.pack_url and (not args.pack_url.startswith("https://") or not PACK_SHA1.fullmatch(args.pack_sha1)):
            raise PackageError("--pack-url must be https:// and --pack-sha1 40 lowercase hex digits")
        ver = version(ROOT)
        staging = args.staging or default_staging()
        staged = plan(args.backup, args.world, args.mods_from, args.launcher, args.jar or ROOT / f"mc/build/libs/cyberblock-{ver}.jar",
                      args.example, [h.strip() for h in args.hosts.split(",") if h.strip()],
                      (args.pack_url, args.pack_sha1) if args.pack_url else None, ver)
        rods_pack = not args.pack_url and re.search(rb"(?m)^resource-pack=\S", staged["server.properties.rodsores"]) is not None
        if args.dry_run:
            print(f"dry run: would stage into {staging}\n" + listing(staged, args.launcher, rods_pack))
            return 0
        prepare_staging(staging, args.clean)
        sizes = stage(staging, staged)
    except PackageError as e:
        print(e, file=sys.stderr)
        return 1
    print(f"staged into {staging}\n" + listing(staged, args.launcher, rods_pack, sizes))
    return 0


if __name__ == "__main__":
    sys.exit(main())
