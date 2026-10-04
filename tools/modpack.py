"""Build the Cyberblock modpack for the Modrinth App and Prism Launcher (plan 2-K1).

    uv run python -m tools.modpack --mods-from <folder> --urls <urls.json> --server-file <server.json>

Writes dist/cyberblock-<version>.mrpack (git-ignored): a Modrinth modpack (format 1; Minecraft 26.3, Fabric loader) whose
`files` are the jars the user downloaded from Modrinth into --mods-from (Fabric API, the proximity voice chat mod; one entry
each, with the official download URL and sha1/sha512 computed here from the jar) and whose `overrides` carry:

    mods/cyberblock-<version>.jar            the mod
    resourcepacks/cyberblock-pack-<version>.zip   tools.respack's pack
    options.txt                              enables the pack
    config/cyberblock/join.json              what the "Join Cyberblock" title button connects to
    servers.dat                              the same server in the multiplayer list

Private inputs, kept outside the repo and never committed (the tool refuses them inside it):
  --mods-from   a folder with the downloaded jars (the Fabric API jar is required)
  --urls        {"<jar file name>": "https://cdn.modrinth.com/data/<project>/versions/<version>/<jar file name>", ...}
  --server-file {"name": "Cyberblock", "address": "<host>[:<port>]"}
The mrpack dependency is the exact loader the mod was built against (an .mrpack must name a version the launcher can
install); fabric.mod.json keeps requiring >= 0.19.5. Stdlib only apart from tools.package and tools.respack.
"""
import argparse
import hashlib
import json
import re
import struct
import sys
from pathlib import Path
from urllib.parse import unquote, urlparse

from tools import respack
from tools.package import PackageError, check_jar, version

ROOT = Path(__file__).resolve().parents[1]
MINECRAFT = "26.3"
FABRIC_LOADER = "0.19.5"
MODRINTH_HOST = "cdn.modrinth.com"
ADDRESS = re.compile(r"^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?(:\d{1,5})?$")


def inside(path: Path, root: Path = ROOT) -> bool:
    return path.resolve().is_relative_to(root.resolve())


def read_json(path: Path, what: str) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise PackageError(f"cannot read {what} {path.name}: {e}") from None
    if not isinstance(data, dict):
        raise PackageError(f"{what} {path.name} must hold a JSON object")
    return data


def server_entry(server_file: Path) -> tuple[str, str]:
    """(name, address) from the user's untracked server file, validated (plain host or IPv4, optional port)."""
    data = read_json(server_file, "the server file")
    name, address = data.get("name"), data.get("address")
    if not isinstance(name, str) or not name.strip() or len(name) > 64 or not name.isascii() or not name.isprintable():
        raise PackageError("server file: name must be 1 to 64 printable ASCII characters")
    if not isinstance(address, str) or not ADDRESS.fullmatch(address):
        raise PackageError("server file: address must be <host>[:<port>] (letters, digits, dots, dashes)")
    if ":" in address and not 0 < int(address.rsplit(":", 1)[1]) < 65536:
        raise PackageError("server file: port out of range")
    return name, address


def nbt_string(text: str) -> bytes:
    raw = text.encode("ascii")  # callers validated: plain ASCII is the same in modified UTF-8
    return struct.pack(">H", len(raw)) + raw


def servers_dat(name: str, address: str) -> bytes:
    """An uncompressed NBT servers.dat with one entry: {servers: [{ip, name}]}."""
    entry = b"\x08" + nbt_string("ip") + nbt_string(address) + b"\x08" + nbt_string("name") + nbt_string(name) + b"\x00"
    return b"\x0a" + nbt_string("") + b"\x09" + nbt_string("servers") + b"\x0a" + struct.pack(">i", 1) + entry + b"\x00"


# Data version of Minecraft 26.3 (version.json world_version). Options.load runs the options datafixer from the file's
# `version:` line, so stamping the current one means no fixer runs.
OPTIONS_DATA_VERSION = 5023


def options_txt(pack_name: str) -> bytes:
    """Only what enables the pack: every other option keeps its default (the launcher imports into a new instance).

    Pack ids are only ones that exist in 26.3 with Fabric API ("fabric" does not; the client drops unknown ids)."""
    return (f'version:{OPTIONS_DATA_VERSION}\nresourcePacks:["vanilla","file/{pack_name}"]\n'
            'incompatibleResourcePacks:[]\n').encode()


def mod_entries(mods_from: Path, urls: dict) -> list[dict]:
    """One modrinth.index.json file entry per jar in mods_from (sorted), hashes computed from the jar itself."""
    jars = sorted(p for p in mods_from.glob("*.jar") if p.is_file())
    if not jars:
        raise PackageError(f"no jars in {mods_from}")
    if not any(j.name.lower().startswith("fabric-api") for j in jars):
        raise PackageError("the Fabric API jar (fabric-api-*.jar) is missing from the mods folder")
    files = []
    for jar in jars:
        if jar.name.lower().startswith("cyberblock"):
            raise PackageError(f"{jar.name}: the mod itself is added from --jar, not from the mods folder")
        url = urls.get(jar.name)
        parsed = urlparse(url) if isinstance(url, str) else None
        if not parsed or parsed.scheme != "https" or parsed.hostname != MODRINTH_HOST or not unquote(parsed.path).endswith("/" + jar.name):
            raise PackageError(f"{jar.name}: the urls file needs its official Modrinth download URL (https://{MODRINTH_HOST}/data/.../{jar.name})")
        check_jar(jar)
        data = jar.read_bytes()
        files.append({
            "path": f"mods/{jar.name}",
            "hashes": {"sha1": hashlib.sha1(data).hexdigest(), "sha512": hashlib.sha512(data).hexdigest()},
            "env": {"client": "required", "server": "required"},
            "downloads": [url],
            "fileSize": len(data),
        })
    return files


def index(ver: str, files: list[dict]) -> dict:
    return {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": ver,
        "name": "Cyberblock",
        "summary": "Minecraft inside Night City: the Cyberblock mod, Fabric API, proximity voice chat and the resource pack.",
        "files": files,
        "dependencies": {"minecraft": MINECRAFT, "fabric-loader": FABRIC_LOADER},
    }


def build(out: Path, jar: Path, pack: Path, mods_from: Path, urls_file: Path, server_file: Path, ver: str) -> Path:
    """Write <out>/cyberblock-<ver>.mrpack and return its path. Nothing is written when an input is refused."""
    for private in (mods_from, urls_file, server_file):
        if inside(private):
            raise PackageError(f"{private.name} is a private input and must live outside the repository")
    for needed, hint in ((jar, "run gradlew build in mc/"), (pack, "run tools.respack")):
        if not needed.is_file():
            raise PackageError(f"missing {needed} ({hint})")
    check_jar(jar)
    name, address = server_entry(server_file)
    files = mod_entries(mods_from, read_json(urls_file, "the urls file"))
    pack_name = f"cyberblock-pack-{ver}.zip"
    entries: dict[str, Path | bytes] = {
        "modrinth.index.json": (json.dumps(index(ver, files), indent=2) + "\n").encode(),
        f"overrides/mods/cyberblock-{ver}.jar": jar,
        f"overrides/resourcepacks/{pack_name}": pack,
        "overrides/options.txt": options_txt(pack_name),
        "overrides/config/cyberblock/join.json": (json.dumps({"schema": 1, "name": name, "address": address}, indent=2) + "\n").encode(),
        "overrides/servers.dat": servers_dat(name, address),
    }
    target = out / f"cyberblock-{ver}.mrpack"
    respack.write_zip(target, entries)
    return target


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Build the Cyberblock modpack (dist/cyberblock-<version>.mrpack).")
    ap.add_argument("--mods-from", type=Path, required=True, help="folder (outside the repo) with the downloaded Fabric API and voice chat jars")
    ap.add_argument("--urls", type=Path, required=True, help="JSON file (outside the repo): jar file name -> its Modrinth download URL")
    ap.add_argument("--server-file", type=Path, required=True, help='JSON file (outside the repo): {"name": ..., "address": ...}')
    ap.add_argument("--jar", type=Path, help="the mod jar (default mc/build/libs/cyberblock-<version>.jar)")
    ap.add_argument("--pack", type=Path, help="the resource pack zip (default: built from respack/ into --out)")
    ap.add_argument("--out", type=Path, default=ROOT / "dist", help="where the .mrpack goes (default dist/)")
    ap.add_argument("--version", help="default: the project version")
    args = ap.parse_args(argv)
    try:
        ver = args.version or version(ROOT)
        pack = args.pack or respack.build(ROOT / "respack", args.out, ver)
        jar = args.jar or ROOT / f"mc/build/libs/cyberblock-{ver}.jar"
        target = build(args.out, jar, pack, args.mods_from, args.urls, args.server_file, ver)
    except PackageError as e:
        print(e, file=sys.stderr)
        return 1
    print(f"{target} ({target.stat().st_size} bytes)")
    print(f"sha1 {respack.sha1(pack)}  {pack.name}  (resource-pack-sha1 for server.properties, once the pack has a URL)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
