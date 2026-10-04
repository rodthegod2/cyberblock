"""Build the Cyberblock resource pack (plan 2-K2).

    uv run python -m tools.respack                    # dist/cyberblock-pack-<version>.zip from respack/
    uv run python -m tools.respack --src <dir> --out <dir>

The pack carries the server-side content textures that may change without a mod release (skins, device and block art) and
the pack icon: original assets only. The mod jar keeps carrying its own assets. The zip is deterministic (sorted names, a
fixed timestamp, LF line endings for text files), so the SHA-1 printed for `resource-pack-sha1` in server.properties is the
same on every machine. `require-resource-pack` stays off until a host for the pack exists (a GitHub release asset only after
the user says "push", or any HTTPS host the user picks); until then the modpack carries its own copy.
Stdlib only.
"""
import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

from tools.package import PackageError, version

ROOT = Path(__file__).resolve().parents[1]
ZIP_TIME = (2026, 1, 1, 0, 0, 0)
TEXT_SUFFIXES = {".json", ".mcmeta", ".txt", ".properties", ".fsh", ".vsh", ".glsl", ".md"}
# game assets never ship: the vanilla namespace and the file types the game and Cyberpunk keep their data in
FORBIDDEN_PREFIX = ("assets/minecraft/",)
FORBIDDEN_SUFFIX = {".archive", ".ent", ".reds", ".class", ".jar", ".exe", ".dll"}


def write_zip(target: Path, entries: dict[str, Path | bytes], level: int = 9) -> None:
    """Write `entries` (zip name -> bytes or a file to stream) in sorted order with fixed metadata, via a .part file."""
    tmp = target.with_name(target.name + ".part")
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=level) as z:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            info.create_system = 3  # the same bytes whichever OS builds the zip
            source = entries[name]
            if isinstance(source, bytes):
                z.writestr(info, source, compresslevel=level)
            else:
                with source.open("rb") as src, z.open(info, "w") as dst:
                    while chunk := src.read(1 << 20):
                        dst.write(chunk)
    tmp.replace(target)


def sha1(path: Path) -> str:
    return hashlib.sha1(path.read_bytes()).hexdigest()


def collect(src: Path) -> dict[str, bytes]:
    """Zip name -> content for every file of the pack folder; refuses game assets and a pack without a valid pack.mcmeta."""
    if not src.is_dir():
        raise PackageError(f"no pack folder: {src}")
    files: dict[str, bytes] = {}
    for path in sorted(p for p in src.rglob("*") if p.is_file()):
        name = path.relative_to(src).as_posix()
        if any(part.startswith(".") for part in name.split("/")):
            continue  # .gitkeep and editor files
        if name.startswith(FORBIDDEN_PREFIX):
            raise PackageError(f"{name}: the vanilla namespace is not ours to ship (original assets only)")
        if path.suffix.lower() in FORBIDDEN_SUFFIX:
            raise PackageError(f"{name}: not a resource-pack file type")
        data = path.read_bytes()
        if path.suffix.lower() in TEXT_SUFFIXES:
            data = data.replace(b"\r\n", b"\n")
        files[name] = data
    try:
        pack = json.loads(files["pack.mcmeta"])["pack"]
        if not isinstance(pack["description"], (str, dict, list)) or not isinstance(pack["min_format"], int):
            raise TypeError
    except (KeyError, ValueError, TypeError):
        raise PackageError("pack.mcmeta is missing or invalid (needs pack.description and an integer pack.min_format)") from None
    return files


def build(src: Path, out: Path, ver: str) -> Path:
    """Write <out>/cyberblock-pack-<ver>.zip and return its path. Nothing is written when the pack is refused."""
    files = collect(src)
    target = out / f"cyberblock-pack-{ver}.zip"
    write_zip(target, files)
    return target


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Build the Cyberblock resource pack (dist/cyberblock-pack-<version>.zip).")
    ap.add_argument("--src", type=Path, default=ROOT / "respack", help="the pack folder (default respack/)")
    ap.add_argument("--out", type=Path, default=ROOT / "dist", help="where the zip goes (default dist/)")
    ap.add_argument("--version", help="default: the project version")
    args = ap.parse_args(argv)
    try:
        target = build(args.src, args.out, args.version or version(ROOT))
    except PackageError as e:
        print(e, file=sys.stderr)
        return 1
    print(f"{target} ({target.stat().st_size} bytes)")
    print(f"resource-pack-sha1={sha1(target)}")
    print("require-resource-pack stays false until the pack has an HTTPS URL (resource-pack=<url>)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
