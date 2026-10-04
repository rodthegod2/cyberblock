"""tools.server_sync against a throwaway FTP server on 127.0.0.1 (stdlib only: the commands server_sync uses)."""
import io
import json
import os
import secrets
import socket
import socketserver
import threading
import time
from pathlib import Path

import pytest

from tools import server_sync

REPO = Path(__file__).resolve().parents[1]
OLD = 1_700_000_000  # fixed modification time of the fake server's files


class FtpHandler(socketserver.StreamRequestHandler):
    """USER PASS TYPE NOOP PASV MLSD LIST MDTM RETR STOR MKD QUIT; paths are absolute, under server.root."""

    def reply(self, line):
        self.wfile.write(f"{line}\r\n".encode())

    def local(self, arg):
        path = (self.server.root / arg.lstrip("/")).resolve()
        if path != self.server.root and self.server.root not in path.parents:
            raise PermissionError(arg)
        return path

    def data(self, pasv, payload=None):
        """Send payload over the passive connection (None: receive and return the bytes)."""
        self.reply("150 opening data connection")
        conn, _ = pasv.accept()
        with conn:
            if payload is not None:
                conn.sendall(payload)
                received = None
            else:
                chunks = []
                while chunk := conn.recv(65536):
                    chunks.append(chunk)
                received = b"".join(chunks)
        pasv.close()
        self.reply("226 transfer complete")
        return received

    def handle(self):
        srv, user, authed, pasv = self.server, None, False, None
        self.reply("220 test ftp")
        while line := self.rfile.readline():
            cmd, _, arg = line.decode().rstrip("\r\n").partition(" ")
            cmd = cmd.upper()
            srv.commands.append((cmd, "" if cmd == "PASS" else arg))
            if cmd == "USER":
                user = arg
                self.reply("331 password please")
            elif cmd == "PASS":
                authed = user == srv.user and arg == srv.password
                self.reply("230 logged in" if authed else "530 Login incorrect")
            elif cmd == "QUIT":
                self.reply("221 bye")
                return
            elif not authed:
                self.reply("530 log in first")
            elif cmd in ("TYPE", "NOOP"):
                self.reply("200 ok")
            elif cmd == "PASV":
                pasv = socket.create_server(("127.0.0.1", 0))
                port = pasv.getsockname()[1]
                self.reply(f"227 Entering Passive Mode (127,0,0,1,{port >> 8},{port & 255})")
            elif cmd == "MLSD" and srv.no_mlsd:
                self.reply("500 unknown command")
            elif cmd in ("MLSD", "LIST"):
                folder = self.local(arg)
                if not folder.is_dir():
                    self.reply("550 no such folder")
                    continue
                lines = []
                for p in sorted(folder.iterdir()):
                    st = p.stat()
                    if cmd == "MLSD":
                        stamp = time.strftime("%Y%m%d%H%M%S", time.gmtime(st.st_mtime))
                        kind = "dir" if p.is_dir() else "file"
                        lines.append(f"type={kind};size={st.st_size};modify={stamp}; {p.name}")
                    else:
                        lines.append(f"{'d' if p.is_dir() else '-'}rw-r--r-- 1 owner group {st.st_size:>8} Jan 01 12:00 {p.name}")
                self.data(pasv, "".join(f"{x}\r\n" for x in lines).encode())
            elif cmd == "MDTM":
                p = self.local(arg)
                self.reply(f"213 {time.strftime('%Y%m%d%H%M%S', time.gmtime(p.stat().st_mtime))}" if p.is_file()
                           else "550 no such file")
            elif cmd == "RETR":
                p = self.local(arg)
                if p.is_file():
                    self.data(pasv, p.read_bytes())
                else:
                    self.reply("550 no such file")
            elif cmd == "STOR":
                p = self.local(arg)
                if not p.parent.is_dir():
                    self.reply("553 no such folder")
                else:
                    p.write_bytes(self.data(pasv))
            elif cmd == "MKD":
                p = self.local(arg)
                if p.exists():
                    self.reply("550 exists")
                else:
                    p.mkdir()
                    self.reply(f'257 "{arg}" created')
            else:
                self.reply("502 not implemented")


class FtpServer(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, root):
        super().__init__(("127.0.0.1", 0), FtpHandler)
        self.root = root.resolve()
        self.user, self.password = "u" + secrets.token_hex(6), secrets.token_urlsafe(18)
        self.commands, self.no_mlsd = [], False

    def sent(self, cmd):
        return [arg for c, arg in self.commands if c == cmd]


REMOTE = {  # a small Paper-like server folder
    "server.properties": b"motd=Rods Ores\nlevel-name=rods_ores\nwhite-list=true\n",
    "paper-1.21.11.jar": b"PK paper" * 50,
    "plugins/Essentials.jar": b"PK essentials",
    "plugins/Essentials/config.yml": b"x: 1\n",
    "rods_ores/level.dat": b"\x1f\x8b level",
    "rods_ores/region/r.0.0.mca": os.urandom(300_000),
    "logs/latest log.txt": b"started\n",
}


@pytest.fixture
def ftp(tmp_path, monkeypatch):
    root = tmp_path / "ftproot"
    for rel, content in REMOTE.items():
        p = root / "default" / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)
        os.utime(p, (OLD, OLD))
    srv = FtpServer(root)
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    monkeypatch.setenv("APEX_FTP_HOST", "127.0.0.1")
    monkeypatch.setenv("APEX_FTP_PORT", str(srv.server_address[1]))
    monkeypatch.setenv("APEX_FTP_USER", srv.user)
    monkeypatch.setenv("APEX_FTP_PASS", srv.password)
    monkeypatch.setenv("APEX_FTP_BASE", "/default")
    srv.remote = root / "default"
    yield srv
    srv.shutdown()
    srv.server_close()


def assert_no_secret(srv, *texts):
    for text in texts:
        for secret in (srv.password, srv.user, "127.0.0.1"):
            assert secret not in text


# --- backup -------------------------------------------------------------------------------------------------------

def test_backup_mirrors_everything_with_manifest_and_summary(ftp, tmp_path, capsys):
    dest = tmp_path / "backup"
    assert server_sync.main(["backup", "--to", str(dest)]) == 0
    for rel, content in REMOTE.items():
        local = dest / "server" / rel
        assert local.read_bytes() == content
        assert int(local.stat().st_mtime) == OLD
    manifest = json.loads((dest / "manifest.json").read_text(encoding="utf-8"))["files"]
    assert {f["path"] for f in manifest} == set(REMOTE)
    for f in manifest:
        assert f["size"] == len(REMOTE[f["path"]])
        assert f["sha256"] == server_sync.hashlib.sha256(REMOTE[f["path"]]).hexdigest()
    summary = json.loads((dest / "summary.json").read_text(encoding="utf-8"))
    assert summary == {"files": len(REMOTE), "bytes": sum(map(len, REMOTE.values())), "level-name": "rods_ores",
                       "root_jars": ["paper-1.21.11.jar"], "plugins": ["Essentials.jar"], "skipped": []}
    out = capsys.readouterr().out
    assert "level-name: rods_ores" in out and "paper-1.21.11.jar" in out and "the server" in out
    assert not list(dest.rglob("*" + server_sync.PART))


def test_backup_resumes_and_skips_complete_files(ftp, tmp_path):
    dest = tmp_path / "backup"
    assert server_sync.main(["backup", "--to", str(dest)]) == 0
    assert len(ftp.sent("RETR")) == len(REMOTE)
    (dest / "server" / "rods_ores" / "region" / "r.0.0.mca").write_bytes(b"cut short")  # an interrupted file
    (dest / "server" / "plugins" / "Essentials.jar").unlink()
    (dest / "server" / "logs" / "latest log.txt.cbpart").write_bytes(b"half")  # a leftover partial download
    ftp.commands.clear()
    assert server_sync.main(["backup", "--to", str(dest)]) == 0
    assert sorted(ftp.sent("RETR")) == ["/default/plugins/Essentials.jar", "/default/rods_ores/region/r.0.0.mca"]
    assert (dest / "server" / "rods_ores" / "region" / "r.0.0.mca").read_bytes() == REMOTE["rods_ores/region/r.0.0.mca"]
    assert server_sync.main(["verify", "--backup", str(dest)]) == 0


def test_backup_falls_back_to_list_and_mdtm(ftp, tmp_path):
    ftp.no_mlsd = True
    dest = tmp_path / "backup"
    assert server_sync.main(["backup", "--to", str(dest)]) == 0
    assert (dest / "server" / "logs" / "latest log.txt").read_bytes() == REMOTE["logs/latest log.txt"]
    assert int((dest / "server" / "server.properties").stat().st_mtime) == OLD
    ftp.commands.clear()
    assert server_sync.main(["backup", "--to", str(dest)]) == 0
    assert ftp.sent("RETR") == []


def test_backup_refuses_a_destination_inside_the_repo(ftp, capsys):
    target = REPO / "out" / "zz-backup-test"
    assert server_sync.main(["backup", "--to", str(target)]) == 1
    assert "refusing to back up into the repository" in capsys.readouterr().err
    assert not target.exists() and ftp.commands == []


def test_backup_from_mount_needs_no_credentials(tmp_path, monkeypatch):
    for name in ("APEX_FTP_HOST", "APEX_FTP_USER", "APEX_FTP_PASS"):
        monkeypatch.delenv(name, raising=False)
    mount = tmp_path / "mount"
    for rel, content in REMOTE.items():
        (mount / rel).parent.mkdir(parents=True, exist_ok=True)
        (mount / rel).write_bytes(content)
    dest = tmp_path / "backup"
    assert server_sync.main(["backup", "--to", str(dest), "--from-mount", str(mount)]) == 0
    assert (dest / "server" / "rods_ores" / "level.dat").read_bytes() == REMOTE["rods_ores/level.dat"]
    assert json.loads((dest / "summary.json").read_text(encoding="utf-8"))["level-name"] == "rods_ores"
    assert server_sync.main(["verify", "--backup", str(dest)]) == 0


# --- verify -------------------------------------------------------------------------------------------------------

def test_verify_detects_changed_and_missing_files(ftp, tmp_path, capsys):
    dest = tmp_path / "backup"
    assert server_sync.main(["backup", "--to", str(dest)]) == 0
    assert server_sync.main(["verify", "--backup", str(dest)]) == 0
    level = dest / "server" / "rods_ores" / "level.dat"
    level.write_bytes(b"\x1f\x8b LEVEL")  # same size, other bytes
    (dest / "server" / "paper-1.21.11.jar").unlink()
    capsys.readouterr()
    assert server_sync.main(["verify", "--backup", str(dest)]) == 1
    err = capsys.readouterr().err
    assert "changed: rods_ores/level.dat" in err and "missing: paper-1.21.11.jar" in err
    assert "2 of 7 files" in err


def test_verify_without_manifest_fails_cleanly(tmp_path, capsys):
    assert server_sync.main(["verify", "--backup", str(tmp_path)]) == 1
    assert "manifest.json" in capsys.readouterr().err


# --- upload -------------------------------------------------------------------------------------------------------

def stage(tmp_path, files):
    root = tmp_path / "stage"
    for rel, content in files.items():
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_bytes(content)
    return root


LAUNCHER = "fabric-server-mc.26.3-loader.0.19.5-launcher.1.1.0.jar"
STAGED = {"server.properties": b"level-name=cyberblock\n", "server.properties.rodsores": REMOTE["server.properties"],
          "mods/cyberblock-1.0.0.jar": b"PK cb", "config/cyberblock/server.json": b"{}", "cyberblock.zip": b"PK zip",
          LAUNCHER: b"PK launcher"}


@pytest.mark.parametrize("rel, ok", [
    ("server.properties", True), ("server.properties.rodsores", True), ("server.properties.cyberblock", True),
    ("mods/fabric-api.jar", True), ("config/cyberblock/server.json", True), ("cyberblock.zip", True),
    (LAUNCHER, True), ("paper-1.21.11.jar", False), ("server.jar", False), ("plugins/Essentials.jar", False),
    ("config/paper-global.yml", False), ("x/mods/a.jar", False), ("mods/sub/a.jar", False),
    ("rods_ores/level.dat", False), ("config/cyberblock", False), ("whitelist.json", False),
])
def test_allowlist(rel, ok):
    assert server_sync.allowed(rel) is ok


def test_upload_is_a_dry_run_by_default(ftp, tmp_path, capsys):
    before = {p: p.read_bytes() for p in ftp.remote.rglob("*") if p.is_file()}
    assert server_sync.main(["upload", "--stage", str(stage(tmp_path, STAGED))]) == 0
    assert ftp.sent("STOR") == [] and ftp.sent("MKD") == []
    assert {p: p.read_bytes() for p in ftp.remote.rglob("*") if p.is_file()} == before
    out = capsys.readouterr().out
    assert "would upload server.properties (22 bytes, overwrite)" in out
    assert f"would upload {LAUNCHER} (11 bytes, new)" in out and "dry run: nothing was written" in out


def test_upload_go_writes_exactly_the_staged_files(ftp, tmp_path):
    files = dict(STAGED, **{"cyberblock/level.dat": b"new world", "cyberblock/region/r.0.0.mca": b"region"})
    assert server_sync.main(["upload", "--stage", str(stage(tmp_path, files)), "--go"]) == 0
    assert sorted(ftp.sent("STOR")) == sorted(f"/default/{rel}" for rel in files)
    for rel, content in files.items():
        assert (ftp.remote / rel).read_bytes() == content
    for rel, content in REMOTE.items():  # the old server's other files are untouched
        if rel != "server.properties":
            assert (ftp.remote / rel).read_bytes() == content


def test_upload_refuses_to_overwrite_outside_the_allowlist(ftp, tmp_path, capsys):
    files = dict(STAGED, **{"plugins/Essentials.jar": b"evil", "rods_ores/level.dat": b"evil"})
    assert server_sync.main(["upload", "--stage", str(stage(tmp_path, files)), "--go"]) == 1
    err = capsys.readouterr().err
    assert "refused: plugins/Essentials.jar" in err and "refused: rods_ores/level.dat" in err
    assert "nothing was uploaded" in err
    assert ftp.sent("STOR") == []
    for rel, content in REMOTE.items():
        assert (ftp.remote / rel).read_bytes() == content


def test_there_is_no_delete_at_all(ftp, tmp_path):
    dest = tmp_path / "backup"
    assert server_sync.main(["backup", "--to", str(dest)]) == 0
    assert server_sync.main(["upload", "--stage", str(stage(tmp_path, STAGED)), "--go"]) == 0
    assert not {c for c, _ in ftp.commands} & {"DELE", "RMD", "XRMD", "RNFR", "RNTO", "SITE"}
    source = Path(server_sync.__file__).read_text(encoding="utf-8")
    for word in (".delete(", ".rmd(", ".rename(", "DELE", "RMD", "RNFR"):
        assert word not in source


# --- secrets (rule 0.6) -------------------------------------------------------------------------------------------

def test_no_secret_in_any_output(ftp, tmp_path, capsys, monkeypatch):
    dest = tmp_path / "backup"
    assert server_sync.main(["backup", "--to", str(dest)]) == 0
    assert server_sync.main(["verify", "--backup", str(dest)]) == 0
    assert server_sync.main(["upload", "--stage", str(stage(tmp_path, STAGED))]) == 0
    assert server_sync.main(["upload", "--stage", str(stage(tmp_path, {"plugins/Essentials.jar": b"x"}))]) == 1
    real = ftp.password
    monkeypatch.setenv("APEX_FTP_PASS", real + "-wrong")
    assert server_sync.main(["backup", "--to", str(tmp_path / "b2")]) == 1
    monkeypatch.setenv("APEX_FTP_PASS", real)
    with socket.create_server(("127.0.0.1", 0)) as closed:
        port = closed.getsockname()[1]
    monkeypatch.setenv("APEX_FTP_PORT", str(port))  # nobody listens there any more: connection refused
    assert server_sync.main(["backup", "--to", str(tmp_path / "b3")]) == 1
    captured = capsys.readouterr()
    assert "Login incorrect" in captured.err and "talking to the server failed" in captured.err
    written = [p.read_text(encoding="utf-8") for p in tmp_path.rglob("*.json")]
    assert_no_secret(ftp, captured.out, captured.err, *written)
    assert "-wrong" not in captured.err
    assert repr(server_sync.conn_from_env()).count(real) == 0


def test_scrub_masks_every_secret():
    conn = server_sync.Conn("ftp.example.invalid", "someuser", "hunter2pass")
    text = conn.scrub("530 someuser@ftp.example.invalid rejected hunter2pass")
    assert text == "530 <user>@the server rejected ***"
    assert "hunter2pass" not in repr(conn) and "someuser" not in repr(conn)


class Tty(io.StringIO):
    def isatty(self):
        return True


def test_password_is_asked_hidden_on_a_terminal(ftp, tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("APEX_FTP_PASS")
    monkeypatch.setattr(server_sync.sys, "stdin", Tty())
    prompts = []
    monkeypatch.setattr(server_sync.getpass, "getpass", lambda prompt: prompts.append(prompt) or ftp.password)
    assert server_sync.main(["backup", "--to", str(tmp_path / "backup")]) == 0
    assert prompts == ["FTP password for the server (hidden): "]
    assert_no_secret(ftp, *capsys.readouterr())


def test_no_password_and_no_terminal_fails_without_connecting(ftp, tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("APEX_FTP_PASS")
    monkeypatch.setattr(server_sync.sys, "stdin", io.StringIO())
    assert server_sync.main(["backup", "--to", str(tmp_path / "backup")]) == 1
    assert "APEX_FTP_PASS is not set" in capsys.readouterr().err
    assert ftp.commands == []


def test_missing_host_or_user_is_named(monkeypatch, tmp_path, capsys):
    for name in ("APEX_FTP_HOST", "APEX_FTP_USER"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("APEX_FTP_PASS", "irrelevant-test-value")
    assert server_sync.main(["upload", "--stage", str(tmp_path)]) == 1
    assert "set APEX_FTP_HOST and APEX_FTP_USER" in capsys.readouterr().err
