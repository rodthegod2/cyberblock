"""Build the Cyberblock release zip.

    uv run python -m tools.package                      # dist/Cyberblock-<version>.zip from the default build outputs
    uv run python -m tools.package --build <dir> --jar <file> --out <dir>

Layout (one top-level folder, fixed timestamps and order, so the same inputs give the same bytes):

    Cyberblock-<version>/
      install.bat  uninstall.bat  install.ps1
      README.md  HOW-TO-PLAY.md  CHANGELOG.md  LICENSE  NOTICE.md  THIRD-PARTY-NOTICES.md
      game/        every file tools.install.payload() lists, at its game-relative path
      minecraft/mods/cyberblock-<version>.jar

The `game/` list comes from tools.install.payload(), the single source of truth for the installers: install.ps1 copies the
`game/` folder and writes the same install manifest as tools/install.py. Prints the SHA-256 of the zip.
Stdlib only apart from tools.install.
"""
import argparse
import hashlib
import re
import sys
import zipfile
from pathlib import Path

from tools import install

ROOT = Path(__file__).resolve().parents[1]
ZIP_TIME = (2026, 1, 1, 0, 0, 0)  # every entry carries this timestamp (zip's earliest is 1980)
CRLF_SUFFIXES = {".bat", ".ps1"}  # Windows scripts are shipped with CRLF line endings whatever the checkout used
DOCS = {"README.md": "README.md", "HOW-TO-PLAY.md": "docs/HOW-TO-PLAY.md", "CHANGELOG.md": "CHANGELOG.md", "LICENSE": "LICENSE",
        "NOTICE.md": "NOTICE.md", "THIRD-PARTY-NOTICES.md": "THIRD-PARTY-NOTICES.md"}
SCRIPTS = ("install.bat", "uninstall.bat", "install.ps1")  # in release/
FORBIDDEN_IN_JAR = ("net/minecraft/", "com/mojang/")  # game code must never ship in our jar
SUSPECT_IN_JAR = ("assets/minecraft/",)  # vanilla-namespace assets: allowed (an override of our own) but reported


class PackageError(Exception):
    pass


def version(root: Path = ROOT) -> str:
    """The project version, from pyproject.toml (tests/test_version.py keeps every other place equal to it)."""
    match = re.search(r'^version\s*=\s*"([^"]+)"', (root / "pyproject.toml").read_text(encoding="utf-8"), re.M)
    if not match:
        raise PackageError(f"no version in {root / 'pyproject.toml'}")
    return match.group(1)


def check_jar(jar: Path) -> list[str]:
    """Refuse a jar that contains Minecraft's code; return a warning per vanilla-namespace asset folder it holds."""
    try:
        with zipfile.ZipFile(jar) as z:
            names = z.namelist()
    except (OSError, zipfile.BadZipFile) as e:
        raise PackageError(f"{jar} is not a readable jar ({e})") from None
    if bad := sorted({n for n in names if n.startswith(FORBIDDEN_IN_JAR)}):
        raise PackageError(f"{jar} contains game code, refusing to ship it: {', '.join(bad[:5])}")
    return [f"warning: {jar.name} has entries under {prefix}" for prefix in SUSPECT_IN_JAR if any(n.startswith(prefix) for n in names)]


def crlf(data: bytes) -> bytes:
    return data.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")


def entries(build: Path, jar: Path, root: Path = ROOT, ver: str | None = None) -> dict[str, bytes]:
    """Zip path (below the top-level folder) -> content, in the order they are written."""
    ver = ver or version(root)
    files: dict[str, bytes] = {}
    for name in SCRIPTS:
        script = root / "release" / name
        if not script.is_file():
            raise PackageError(f"missing {script}")
        files[name] = crlf(script.read_bytes())
    for name, rel in DOCS.items():
        doc = root / rel
        if not doc.is_file():
            raise PackageError(f"missing {doc}")
        data = doc.read_bytes()
        if name == "README.md":  # the guide sits next to the README in the zip
            data = data.replace(b"](docs/HOW-TO-PLAY.md", b"](HOW-TO-PLAY.md")
        files[name] = data
    try:
        for rel, src in install.payload(build, root).items():
            files[f"game/{rel}"] = src.read_bytes()
    except install.InstallError as e:
        raise PackageError(str(e)) from None
    if not jar.is_file():
        raise PackageError(f"the mod jar is missing: {jar} (run gradlew build in mc/)")
    files[f"minecraft/mods/cyberblock-{ver}.jar"] = jar.read_bytes()
    return dict(sorted(files.items()))


def build_zip(out: Path, build: Path, jar: Path, root: Path = ROOT, ver: str | None = None) -> Path:
    """Write <out>/Cyberblock-<version>.zip and return its path. Nothing is written when an input is missing."""
    ver = ver or version(root)
    warnings = check_jar(jar) if jar.is_file() else []
    files = entries(build, jar, root, ver)
    for w in warnings:
        print(w, file=sys.stderr)
    out.mkdir(parents=True, exist_ok=True)
    target = out / f"Cyberblock-{ver}.zip"
    top = f"Cyberblock-{ver}"
    tmp = target.with_suffix(".zip.part")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, data in files.items():
            info = zipfile.ZipInfo(f"{top}/{name}", ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            info.create_system = 3  # the same bytes whichever OS builds the zip
            z.writestr(info, data, compresslevel=9)
    tmp.replace(target)
    return target


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Build the Cyberblock release zip (dist/Cyberblock-<version>.zip).")
    ap.add_argument("--build", type=Path, default=ROOT / "cp/plugin/build/Release", help="folder holding Cyberblock.dll")
    ap.add_argument("--jar", type=Path, help="the mod jar (default mc/build/libs/cyberblock-<version>.jar)")
    ap.add_argument("--source", type=Path, default=ROOT, help="repository root (scripts, shader, docs, release/)")
    ap.add_argument("--out", type=Path, default=ROOT / "dist", help="where the zip goes (default dist/)")
    args = ap.parse_args(argv)
    try:
        ver = version(args.source)
        jar = args.jar or args.source / f"mc/build/libs/cyberblock-{ver}.jar"
        target = build_zip(args.out, args.build, jar, args.source, ver)
    except PackageError as e:
        print(e, file=sys.stderr)
        return 1
    with zipfile.ZipFile(target) as z:
        count = len(z.namelist())
    print(f"{target} ({count} files)")
    print(f"sha256 {sha256(target)}  {target.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
