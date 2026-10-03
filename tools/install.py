"""Install Cyberblock into Cyberpunk 2077 and take it out again, touching nothing it did not write.

    uv run tools/install.py            # plugin DLL, redscript files and ReShade shader into the game folder
    uv run tools/install.py --remove   # delete exactly what was installed (files that changed since are left)

Every installed file is listed with its sha256 in <game>\\red4ext\\plugins\\Cyberblock\\install-manifest.json. An install
refuses (exit 2, nothing written) to overwrite a file that is not in that manifest, except an existing ReShade.fxh, which
other ReShade effects share: it is kept as it is and not recorded. Before the very first install (no manifest) the
Cyberpunk saves folder is zipped into %LOCALAPPDATA%\\Cyberblock\\save-backups.
The release installer release/install.ps1 writes the same manifest, so either one can remove what the other installed.
Stdlib only, so it also runs as a plain script.
"""
import argparse
import contextlib
import datetime
import hashlib
import json
import os
import re
import shutil
import sys
import winreg
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GAME_EXE = "bin/x64/Cyberpunk2077.exe"
MANIFEST = "red4ext/plugins/Cyberblock/install-manifest.json"
DLL = "red4ext/plugins/Cyberblock/Cyberblock.dll"
SCRIPTS = "r6/scripts/Cyberblock"
SHADER = "bin/x64/reshade-shaders/Shaders/Cyberblock.fx"
SHADER_HEADER = "bin/x64/reshade-shaders/Shaders/ReShade.fxh"  # Cyberblock.fx includes it (CC0, vendored)
PRESET = "bin/x64/ReShadePreset.ini"
PRESET_TEXT = "Techniques=Cyberblock@Cyberblock.fx\n"


class InstallError(Exception):
    code = 1


class Refused(InstallError):
    code = 2


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def find_game(steam: Path | None = None) -> Path:
    """Cyberpunk 2077's folder in any of Steam's libraries."""
    if steam is None:
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as key:
                steam = Path(winreg.QueryValueEx(key, "SteamPath")[0])
        except OSError:
            raise InstallError("Steam not found in the registry; pass --game") from None
    try:
        libraries = (steam / "steamapps/libraryfolders.vdf").read_text(encoding="utf-8", errors="replace")
    except OSError as e:
        raise InstallError(f"cannot read Steam's libraries ({e}); pass --game") from None
    for library in re.findall(r'"path"\s+"([^"]+)"', libraries):
        game = Path(library.replace("\\\\", "\\")) / "steamapps/common/Cyberpunk 2077"
        if (game / GAME_EXE).is_file():
            return game
    raise InstallError("Cyberpunk 2077 not found in the Steam libraries; pass --game")


def payload(build: Path, source: Path) -> dict[str, Path]:
    """Game-relative destination -> source file for everything an install writes (except the ReShade preset)."""
    scripts = sorted((source / "cp/scripts/Cyberblock").glob("*.reds"))
    files = {DLL: build / "Cyberblock.dll", SHADER: source / "cp/shaders/Cyberblock.fx",
             SHADER_HEADER: source / "cp/shaders/vendor/ReShade.fxh"}
    files |= {f"{SCRIPTS}/{p.name}": p for p in scripts}
    missing = [str(p) for p in files.values() if not p.is_file()]
    if not scripts:
        missing.append(str(source / "cp/scripts/Cyberblock/*.reds"))
    if missing:
        raise InstallError("nothing installed, these inputs are missing (build the plugin first?):\n  " + "\n  ".join(missing))
    return files


def load_manifest(game: Path) -> dict | None:
    try:
        manifest = json.loads((game / MANIFEST).read_text(encoding="utf-8"))
        if not isinstance(manifest["files"], dict) or not isinstance(manifest["dirs"], list):
            raise TypeError("files must be an object and dirs a list")
        names = [*manifest["files"], *manifest["dirs"]]
    except FileNotFoundError:
        return None
    except (ValueError, KeyError, TypeError) as e:
        raise InstallError(f"{game / MANIFEST} is unreadable ({e!r}); check it and delete it by hand") from None
    root = game.resolve()
    if outside := [n for n in names if not (game / n).resolve().is_relative_to(root)]:
        raise InstallError(f"{game / MANIFEST} lists paths outside the game folder, not trusting it: {outside}")
    return manifest


def delete_unchanged(game: Path, files: dict[str, str]) -> list[str]:
    """Delete the files whose content still matches the recorded sha256; return those left because they changed."""
    left = []
    for rel, digest in files.items():
        path = game / rel
        if not path.is_file():
            continue
        if sha256(path.read_bytes()) == digest:
            path.unlink()
        else:
            left.append(rel)
    return left


def remove_empty_dirs(game: Path, dirs: list[str]) -> None:
    for rel in sorted(dirs, key=lambda d: d.count("/"), reverse=True):  # deepest first
        with contextlib.suppress(OSError):  # not empty (something else lives there) or already gone
            (game / rel).rmdir()


def make_dirs(path: Path) -> list[str]:
    """mkdir -p; returns the absolute paths that did not exist before."""
    created = [str(p) for p in (path, *path.parents) if not p.exists()]
    path.mkdir(parents=True, exist_ok=True)
    return created


def backup_saves(saves: Path, backups: Path) -> None:
    if not saves.is_dir():
        print(f"no saves folder at {saves}; skipping the backup")
        return
    backups.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    name = f"saves-{stamp}"
    for n in range(2, 1000):  # never overwrite an earlier backup (an install, a removal and an install within one second)
        if not (backups / f"{name}.zip").exists():
            break
        name = f"saves-{stamp}-{n}"
    print(f"backing up {saves} ...")
    print("saves backed up to", shutil.make_archive(str(backups / name), "zip", saves))


def install(game: Path, build: Path, source: Path, saves: Path, backups: Path) -> None:
    if not (game / GAME_EXE).is_file():
        raise InstallError(f"{game} is not a Cyberpunk 2077 folder ({GAME_EXE} is missing)")
    files = payload(build, source)
    old = load_manifest(game)
    owned = old["files"] if old else {}
    if SHADER_HEADER not in owned and (game / SHADER_HEADER).exists():  # ReShade.fxh is shared with other ReShade effects: keep theirs
        print(f"kept the existing {SHADER_HEADER} (not ours: not touched, not recorded)")
        files = {rel: src for rel, src in files.items() if rel != SHADER_HEADER}
    if foreign := [rel for rel in files if (game / rel).exists() and rel not in owned]:
        raise Refused("refusing to overwrite files Cyberblock did not install, nothing changed:\n  " + "\n  ".join(foreign))
    if old is None:
        backup_saves(saves, backups)

    entries = {rel: digest for rel, digest in owned.items() if rel in files or rel == PRESET}  # still ours if we fail midway
    dirs = list(old["dirs"]) if old else []
    try:
        stale = {rel: digest for rel, digest in owned.items() if rel not in entries}  # shipped by an earlier version only
        for rel in delete_unchanged(game, stale):
            print(f"left {rel}: it changed since it was installed")
        for rel, src in files.items():
            dst = game / rel
            dirs += [Path(p).relative_to(game).as_posix() for p in make_dirs(dst.parent)]
            data = src.read_bytes()
            dst.write_bytes(data)
            entries[rel] = sha256(data)
        if not (game / PRESET).exists():  # never replace a preset the user (or ReShade) already has
            data = PRESET_TEXT.encode()
            (game / PRESET).write_bytes(data)
            entries[PRESET] = sha256(data)
    finally:
        if entries:
            (game / MANIFEST).write_text(json.dumps({"version": 1, "files": dict(sorted(entries.items())),
                                                      "dirs": sorted(set(dirs))}, indent=1) + "\n", encoding="utf-8")
    print(f"installed {len(files)} files into {game}")


def remove(game: Path) -> None:
    manifest = load_manifest(game)
    if manifest is None:
        print(f"nothing to remove: no install manifest at {game / MANIFEST}")
        return
    left = delete_unchanged(game, manifest["files"])
    (game / MANIFEST).unlink()
    remove_empty_dirs(game, manifest["dirs"])
    for rel in left:
        print(f"left {rel}: it changed since it was installed")
    print(f"removed {len(manifest['files']) - len(left)} files from {game}")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Install or remove Cyberblock in Cyberpunk 2077.")
    ap.add_argument("--remove", action="store_true", help="remove exactly what was installed")
    ap.add_argument("--game", type=Path, help="Cyberpunk 2077 folder (default: found through Steam)")
    ap.add_argument("--build", type=Path, default=ROOT / "cp/plugin/build/Release", help="folder holding Cyberblock.dll")
    ap.add_argument("--source", type=Path, default=ROOT, help="repository root holding cp/scripts and cp/shaders")
    ap.add_argument("--saves", type=Path, default=Path.home() / "Saved Games/CD Projekt Red/Cyberpunk 2077",
                    help="saves folder to back up before the first install")
    ap.add_argument("--backups", type=Path, default=Path(os.environ["LOCALAPPDATA"]) / "Cyberblock/save-backups",
                    help="where the saves backup zip goes")
    args = ap.parse_args(argv)
    try:
        game = args.game or find_game()
        if args.remove:
            remove(game)
        else:
            install(game, args.build, args.source, args.saves, args.backups)
    except InstallError as e:
        print(e, file=sys.stderr)
        return e.code
    except OSError as e:
        print(f"{e}\nIs Cyberpunk 2077 still running? Close it and run this again.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
