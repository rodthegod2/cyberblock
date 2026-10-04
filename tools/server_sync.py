"""Back up, verify and upload the Apex server's files over FTP (megaplan task 2-S1).

    uv run python -m tools.server_sync backup --to "<server backup folder>"
    uv run python -m tools.server_sync backup --to "<server backup folder>" --from-mount <folder>
    uv run python -m tools.server_sync verify --backup "<server backup folder>"
    uv run python -m tools.server_sync upload --stage <folder>          # dry run: lists what would be written
    uv run python -m tools.server_sync upload --stage <folder> --go     # really uploads
    uv run python -m tools.server_sync upload --stage <folder> --overwrite <relpath> [--overwrite ...] [--go]

Connection, from the environment only: APEX_FTP_HOST, APEX_FTP_USER, APEX_FTP_PASS, APEX_FTP_PORT (default 21) and
APEX_FTP_BASE (default /default; a server profile lives in its own /profile_xxxxx folder). With no APEX_FTP_PASS the
password saved in Windows Credential Manager is used (`uvx keyring set cyberblock-apex-ftp ftp` once, then run with
`uv run --with keyring`); failing that, an interactive terminal asks for it hidden (getpass). Rule 0.6: the password, the host and the user name are never printed, logged or written; output says
"the server" and errors are re-raised with those values scrubbed.

backup: a resumable recursive download (a file is skipped when its size and modification time already match) into
<to>/server/, then <to>/manifest.json (path, size, sha256 per file) and <to>/summary.json (files, bytes, the level-name
from server.properties, the jars at the root and in plugins/). --from-mount copies from a mounted folder instead
(no credentials). A destination inside the repository is refused.
verify: re-hashes <backup>/server/ against the manifest.
upload: sends exactly the staged files, dry run unless --go. There is no delete command at all, and a remote file that
already exists is overwritten only when its path is in ALLOWLIST, is named with --overwrite <relpath> (repeatable, for
a deliberate restore), or was written by an earlier run of this same stage (its journal), so the old server's files
cannot be clobbered. The check fails closed: a staged path the listing does not show is confirmed absent with SIZE (or
MDTM), a folder that cannot be listed is refused unless its parent's listing shows it missing, and an unreadable listing
stops the upload. The journal <stage>/.cbupload.json (one JSON object per line: base, path, size, mtime_ns, done) is
appended before and after every STOR, so an interrupted --go can simply be run again: files it already finished are
skipped and a half-written one is overwritten. The journal is never uploaded.
Stdlib only.
"""
import argparse
import calendar
import ftplib
import getpass
import hashlib
import json
import os
import posixpath
import shutil
import sys
import time
from contextlib import contextmanager, nullcontext
from dataclasses import dataclass, field
from fnmatch import fnmatchcase
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# Remote paths (relative to APEX_FTP_BASE) that upload may overwrite. The last entry is the Fabric server launcher jar
# (fabric-server-mc.<mc>-loader.<loader>-launcher.<launcher>.jar), which the panel runs as its Custom Jar.
ALLOWLIST = ("server.properties", "server.properties.*", "mods/*", "config/cyberblock/*", "cyberblock.zip",
             "fabric-server-mc.*-launcher.*.jar")
MIRROR, MANIFEST, SUMMARY, JOURNAL = "server", "manifest.json", "summary.json", ".cbupload.json"
PART = ".cbpart"  # suffix of a file still downloading; a complete file is renamed into place
KEYRING_SERVICE = "cyberblock-apex-ftp"
WINDOWS_BAD = set('<>:"|?*\\/') | {chr(c) for c in range(32)}


class SyncError(Exception):
    pass


def say(msg):
    print(msg, flush=True)


def warn(msg):
    print(msg, file=sys.stderr, flush=True)


@dataclass
class Conn:
    host: str = field(repr=False)
    user: str = field(repr=False)
    password: str = field(repr=False)
    port: int = 21
    base: str = "/default"

    def scrub(self, text: str) -> str:
        """`text` with the password, host and user name masked (for error messages from ftplib or the socket layer)."""
        for secret, mask in ((self.password, "***"), (self.host, "the server"), (self.user, "<user>")):
            if secret:
                text = text.replace(secret, mask)
        return text

    def path(self, rel: str) -> str:
        return posixpath.join(self.base, rel) if rel else self.base


def saved_password() -> str:
    """The password saved once with `uvx keyring set cyberblock-apex-ftp ftp` (Windows Credential Manager), or "".
    keyring is optional: run with `uv run --with keyring` to use it; without it this returns ""."""
    try:
        import keyring
    except ImportError:
        return ""
    return keyring.get_password(KEYRING_SERVICE, "ftp") or ""


def conn_from_env(env=os.environ) -> Conn:
    host, user = env.get("APEX_FTP_HOST", ""), env.get("APEX_FTP_USER", "")
    if missing := [name for name, value in (("APEX_FTP_HOST", host), ("APEX_FTP_USER", user)) if not value]:
        raise SyncError(f"set {' and '.join(missing)} in your environment first")
    password = env.get("APEX_FTP_PASS", "") or saved_password()
    if not password:
        if not sys.stdin.isatty():
            raise SyncError("APEX_FTP_PASS is not set and there is no terminal to ask for it: run this in your own terminal")
        password = getpass.getpass("FTP password for the server (hidden): ")
    try:
        port = int(env.get("APEX_FTP_PORT") or 21)
    except ValueError:
        raise SyncError("APEX_FTP_PORT must be a number") from None
    return Conn(host, user, password, port, "/" + (env.get("APEX_FTP_BASE") or "/default").strip("/"))


@contextmanager
def connect(conn: Conn):
    """A logged-in binary-mode FTP session; every FTP or socket error leaves as a scrubbed SyncError."""
    ftp = ftplib.FTP(timeout=60)
    try:
        ftp.connect(conn.host, conn.port)
        ftp.login(conn.user, conn.password)
        ftp.voidcmd("TYPE I")
        say("connected to the server")
        yield ftp
    except (OSError, EOFError, ftplib.Error) as e:
        raise SyncError(f"talking to the server failed: {conn.scrub(f'{type(e).__name__}: {e}')}") from None
    finally:
        try:
            ftp.quit()
        except (OSError, EOFError, ftplib.Error, AttributeError):
            ftp.close()


def ftp_time(value: str) -> int:
    """Seconds since the epoch from an FTP timestamp (YYYYMMDDHHMMSS[.sss], UTC)."""
    return calendar.timegm(time.strptime(value[:14], "%Y%m%d%H%M%S"))


def list_dir(ftp: ftplib.FTP, path: str) -> list[tuple[str, bool, int, int | None]]:
    """(name, is_dir, size, mtime or None) per entry of one remote folder: MLSD, else a Unix-style LIST plus MDTM.
    Links and other special entries are left out."""
    try:
        return [(name, facts["type"] == "dir", int(facts.get("size", 0)),
                 ftp_time(facts["modify"]) if "modify" in facts else None)
                for name, facts in ftp.mlsd(path) if facts.get("type") in ("file", "dir")]
    except ftplib.error_perm as e:
        if not str(e).startswith(("500", "501", "502", "504")):  # MLSD unknown to this server; anything else is real
            raise
    lines = []
    ftp.retrlines(f"LIST {path}", lines.append)
    entries = []
    for line in lines:  # drwxr-xr-x 1 owner group 4096 Jan 01 12:00 name with spaces
        parts = line.split(None, 8)
        if not parts or (parts[0] == "total" and len(parts) == 2):
            continue
        if len(parts) < 9 or line[0] not in "-dlbcps" or not parts[4].isdigit():
            # A listing that cannot be read must not look like an empty folder (upload would then overwrite files it
            # did not see, backup would miss them). The line itself is not shown: it may name the FTP user.
            raise SyncError(f"cannot read the server listing of {path} (not the Unix LIST format)")
        if parts[8] in (".", "..") or line[0] not in "-d":
            continue
        is_dir, mtime = line[0] == "d", None
        if not is_dir:
            try:
                mtime = ftp_time(ftp.sendcmd(f"MDTM {posixpath.join(path, parts[8])}").split()[1])
            except (ftplib.Error, ValueError, IndexError):
                pass  # no MDTM: completeness falls back to the size alone
        entries.append((parts[8], is_dir, 0 if is_dir else int(parts[4]), mtime))
    return entries


def bad_name(name: str) -> bool:
    """A name that cannot be stored safely on Windows (also blocks path tricks like '..' from a hostile server)."""
    return name in ("", ".", "..") or any(c in WINDOWS_BAD for c in name) or name.endswith((" ", "."))


def walk_remote(ftp, conn: Conn, skipped: list[str], rel: str = ""):
    """Yield (relative path, size, mtime) for every file under the base folder."""
    for name, is_dir, size, mtime in sorted(list_dir(ftp, conn.path(rel))):
        child = f"{rel}/{name}" if rel else name
        if bad_name(name):
            warn(f"warning: skipped {child!r}: the name cannot be saved on Windows")
            skipped.append(child)
        elif is_dir:
            yield from walk_remote(ftp, conn, skipped, child)
        else:
            yield child, size, mtime


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def is_complete(local: Path, size: int, mtime) -> bool:
    if not local.is_file():
        return False
    st = local.stat()
    return st.st_size == size and (mtime is None or int(st.st_mtime) == int(mtime))


def mirror_files(entries, mirror: Path, fetch) -> tuple[int, int]:
    """Bring every (rel, size, mtime) entry into `mirror` with fetch(rel, part_path); return (fetched, already there)."""
    fetched = kept = 0
    for rel, size, mtime in entries:
        local = mirror / rel
        if is_complete(local, size, mtime):
            kept += 1
            continue
        local.parent.mkdir(parents=True, exist_ok=True)
        part = local.with_name(local.name + PART)
        fetch(rel, part)
        if (got := part.stat().st_size) != size:
            raise SyncError(f"{rel}: got {got} bytes, expected {size}; run backup again to resume")
        if mtime is not None:
            os.utime(part, (mtime, mtime))
        os.replace(part, local)
        fetched += 1
        if fetched % 100 == 0:
            say(f"... {fetched} files fetched")
    return fetched, kept


def level_name(properties: Path) -> str | None:
    if properties.is_file():
        for line in properties.read_text(encoding="utf-8", errors="replace").splitlines():
            key, sep, value = line.partition("=")
            if sep and key.strip() == "level-name":
                return value.strip()
    return None


def backup(dest, conn: Conn | None = None, mount=None) -> dict:
    """Mirror the server (or `mount`) into dest/server/, write the manifest and the summary; return the summary."""
    dest = Path(dest).resolve()
    if dest == ROOT or ROOT in dest.parents:
        raise SyncError(f"refusing to back up into the repository ({dest}); pick a folder outside it")
    mirror, skipped = dest / MIRROR, []
    if mount:
        source = Path(mount)
        if not source.is_dir():
            raise SyncError(f"the mount {source} is not a folder")
        entries = [(p.relative_to(source).as_posix(), p.stat().st_size, p.stat().st_mtime)
                   for p in sorted(source.rglob("*")) if p.is_file()]
        say(f"copying {len(entries)} files from the mount")
        fetched, kept = mirror_files(entries, mirror, lambda rel, part: shutil.copyfile(source / rel, part))
    else:
        with connect(conn) as ftp:
            entries = list(walk_remote(ftp, conn, skipped))
            say(f"the server has {len(entries)} files under {conn.base}")

            def fetch(rel, part):
                with open(part, "wb") as f:
                    ftp.retrbinary(f"RETR {conn.path(rel)}", f.write, blocksize=1 << 16)

            fetched, kept = mirror_files(entries, mirror, fetch)
    say(f"fetched {fetched} files, {kept} were already complete")
    files = [{"path": rel, "size": size, "sha256": sha256(mirror / rel)} for rel, size, _ in entries]
    (dest / MANIFEST).write_text(json.dumps({"files": files}, indent=1) + "\n", encoding="utf-8")
    rels = [f["path"] for f in files]
    summary = {"files": len(files), "bytes": sum(f["size"] for f in files),
               "level-name": level_name(mirror / "server.properties"),
               "root_jars": [r for r in rels if "/" not in r and r.endswith(".jar")],
               "plugins": [r[len("plugins/"):] for r in rels if r.startswith("plugins/") and r.count("/") == 1
                           and r.endswith(".jar")],
               "skipped": skipped}
    (dest / SUMMARY).write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
    say(f"backup complete: {summary['files']} files, {summary['bytes']} bytes")
    say(f"level-name: {summary['level-name']}")
    say(f"jars at the root: {', '.join(summary['root_jars']) or 'none'}")
    say(f"plugins: {', '.join(summary['plugins']) or 'none'}")
    if skipped:
        warn(f"warning: {len(skipped)} entries were skipped (listed in {SUMMARY})")
    return summary


def verify(backup_dir) -> None:
    backup_dir = Path(backup_dir)
    try:
        files = json.loads((backup_dir / MANIFEST).read_text(encoding="utf-8"))["files"]
    except (OSError, ValueError, KeyError) as e:
        raise SyncError(f"no readable {MANIFEST} in {backup_dir} ({e})") from None
    mirror, problems = backup_dir / MIRROR, []
    for item in files:
        local = mirror / item["path"]
        if not local.is_file():
            problems.append(f"missing: {item['path']}")
        elif local.stat().st_size != item["size"] or sha256(local) != item["sha256"]:
            problems.append(f"changed: {item['path']}")
    listed = {item["path"] for item in files}
    for p in sorted(mirror.rglob("*")) if mirror.is_dir() else []:
        if p.is_file() and (rel := p.relative_to(mirror).as_posix()) not in listed:
            warn(f"warning: not in the manifest: {rel}")
    for problem in problems:
        warn(problem)
    if problems:
        raise SyncError(f"{len(problems)} of {len(files)} files do not match the manifest")
    say(f"verified {len(files)} files, {sum(f['size'] for f in files)} bytes: all match the manifest")


def allowed(rel: str) -> bool:
    """True when the remote path may be overwritten: every segment matches the same segment of an ALLOWLIST pattern."""
    parts = rel.split("/")
    return any(len(pat := pattern.split("/")) == len(parts) and all(map(fnmatchcase, parts, pat))
               for pattern in ALLOWLIST)


def read_journal(stage: Path, base: str) -> tuple[set[str], dict[str, tuple[int, int]]]:
    """From <stage>/JOURNAL, for this base folder: (paths an earlier run started to upload, {path: (size, mtime_ns)} of
    those whose upload finished and whose staged file has not been started again since)."""
    started, done = set(), {}
    path = stage / JOURNAL
    if not path.exists():
        return started, done
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as e:
        raise SyncError(f"cannot read the upload journal {path} ({e})") from None
    for line in lines:
        try:
            rec = json.loads(line)
            if rec["base"] != base:
                continue
            if rec["done"]:
                done[rec["path"]] = (rec["size"], rec["mtime_ns"])
            else:
                started.add(rec["path"])
                done.pop(rec["path"], None)
        except (ValueError, KeyError, TypeError):
            continue  # a line cut short when a run was killed
    return started, done


def remote_exists(ftp: ftplib.FTP, path: str) -> bool | None:
    """Ask the server directly whether a file exists: True on 213, False on 550, None when it cannot tell.
    SIZE first, MDTM when the server does not know SIZE."""
    for cmd in ("SIZE", "MDTM"):
        try:
            return True if ftp.sendcmd(f"{cmd} {path}").startswith("213") else None
        except ftplib.error_perm as e:
            if str(e).startswith("550"):
                return False
            if not str(e).startswith(("500", "501", "502", "504")):  # only "unknown command" moves on to MDTM
                return None
        except ftplib.Error:
            return None
    return None


def upload(stage, conn: Conn, go: bool = False, overwrite=()) -> None:
    stage = Path(stage)
    if not stage.is_dir():
        raise SyncError(f"the stage {stage} is not a folder")
    files = sorted(rel for p in stage.rglob("*")
                   if p.is_file() and (rel := p.relative_to(stage).as_posix()) != JOURNAL)
    if not files:
        raise SyncError(f"nothing is staged in {stage}")
    overrides = {rel.replace("\\", "/").strip("/") for rel in overwrite}
    if unknown := sorted(overrides - set(files)):
        raise SyncError(f"--overwrite names files that are not staged: {', '.join(unknown)}")
    started, done = read_journal(stage, conn.base)
    try:
        journal = open(stage / JOURNAL, "a", encoding="utf-8") if go else nullcontext()
    except OSError as e:
        raise SyncError(f"cannot write the upload journal in {stage} ({e})") from None
    with journal as log, connect(conn) as ftp:
        listings: dict[str, dict[str, int]] = {}

        def names_in(folder: str) -> dict[str, int]:
            """{name: size} of one remote folder; {} only when the folder is known not to exist."""
            if folder not in listings:
                try:
                    listings[folder] = {name: size for name, _, size, _ in list_dir(ftp, conn.path(folder))}
                except ftplib.error_perm as e:
                    if not str(e).startswith("550"):
                        raise
                    # 550 also means "you may not list this": trust "missing" only when the parent's listing agrees
                    if not folder or posixpath.basename(folder) in names_in(posixpath.dirname(folder)):
                        raise SyncError(f"the server refused to list {conn.path(folder)} although it exists, so "
                                        "nothing can be checked safely; nothing was uploaded") from None
                    listings[folder] = {}
            return listings[folder]

        plan, refused = [], []
        for rel in files:
            st, name = (stage / rel).stat(), posixpath.basename(rel)
            remote = names_in(posixpath.dirname(rel))
            exists = name in remote
            if done.get(rel) == (st.st_size, st.st_mtime_ns) and remote.get(name) == st.st_size:
                plan.append((rel, "skip"))
            elif allowed(rel):
                plan.append((rel, "overwrite" if exists else "new"))
            elif rel in overrides:
                plan.append((rel, "overwrite, --overwrite" if exists else "new"))
            elif rel in started:  # this stage wrote it (perhaps half) in an earlier run
                plan.append((rel, "overwrite, finishing an earlier upload" if exists else "new"))
            elif exists:
                refused.append(f"{rel} already exists on the server and is not in the overwrite allowlist")
            elif (found := remote_exists(ftp, conn.path(rel))) is None:
                refused.append(f"{rel}: the server could not confirm that it is new (no SIZE or MDTM answer)")
            elif found:
                refused.append(f"{rel} already exists on the server (its listing did not show it) and is not in "
                               "the overwrite allowlist")
            else:
                plan.append((rel, "new"))
        if refused:
            for problem in refused:
                warn(f"refused: {problem}")
            raise SyncError(f"{len(refused)} staged files were refused; nothing was uploaded "
                            "(to restore a file on purpose, name it with --overwrite <relpath>)")
        for rel, how in plan:
            size = (stage / rel).stat().st_size
            if how == "skip":
                say(f"skip {rel} ({size} bytes, already uploaded by an earlier run)")
            else:
                say(f"{'upload' if go else 'would upload'} {rel} ({size} bytes, {how})")
        todo = [rel for rel, how in plan if how != "skip"]
        if not go:
            say(f"dry run: nothing was written; add --go to upload these {len(todo)} files")
            return

        def note(rel, st, finished):
            log.write(json.dumps({"base": conn.base, "path": rel, "size": st.st_size, "mtime_ns": st.st_mtime_ns,
                                  "done": finished}) + "\n")
            log.flush()

        for rel in todo:
            folder = ""
            for segment in posixpath.dirname(rel).split("/") if "/" in rel else []:
                parent, folder = folder, posixpath.join(folder, segment)
                if segment not in names_in(parent):
                    try:
                        ftp.mkd(conn.path(folder))
                    except ftplib.error_perm:
                        pass  # made meanwhile; a real problem shows in STOR below
                    names_in(parent)[segment] = 0
            st = (stage / rel).stat()
            note(rel, st, False)  # before STOR: a half-written remote file is still known to be ours
            with open(stage / rel, "rb") as f:
                ftp.storbinary(f"STOR {conn.path(rel)}", f)
            note(rel, st, True)
        say(f"uploaded {len(todo)} files to the server")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="tools.server_sync", description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("backup", help="download the server folder with a manifest")
    p.add_argument("--to", required=True, help="backup folder, outside the repository")
    p.add_argument("--from-mount", help="copy from this mounted server folder instead of FTP (no credentials)")
    p = sub.add_parser("verify", help="re-hash a backup against its manifest")
    p.add_argument("--backup", required=True)
    p = sub.add_parser("upload", help="upload the staged files (dry run unless --go)")
    p.add_argument("--stage", required=True)
    p.add_argument("--go", action="store_true", help="really upload")
    p.add_argument("--overwrite", action="append", default=[], metavar="RELPATH",
                   help="allow this staged path to overwrite an existing server file outside the allowlist "
                        "(a deliberate restore; repeat for more files)")
    args = parser.parse_args(argv)
    try:
        if args.command == "backup":
            backup(args.to, None if args.from_mount else conn_from_env(), args.from_mount)
        elif args.command == "verify":
            verify(args.backup)
        else:
            upload(args.stage, conn_from_env(), args.go, args.overwrite)
    except SyncError as e:
        warn(f"error: {e}")
        return 1
    except KeyboardInterrupt:
        warn("stopped; run the same command again to resume")
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
