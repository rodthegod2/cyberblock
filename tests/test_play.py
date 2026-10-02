import json
import socket
import subprocess
import sys

import pytest

from tools import play

FAKE_MC = r'''
import json, os, socket, sys, time
here = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(here, "invocation.json"), "w") as f:
    json.dump({"argv": sys.argv[1:], "java_home": os.environ.get("JAVA_HOME"), "pid": os.getpid()}, f)
mode = os.environ["FAKE_MODE"]
if mode == "exit3":
    sys.exit(3)
if mode == "listen":
    time.sleep(0.5)
    server = socket.socket()
    server.bind(("127.0.0.1", int(os.environ["FAKE_PORT"])))
    server.listen()
time.sleep(float(os.environ.get("FAKE_LIFETIME", "60")))
'''


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def port_open(port: int) -> bool:
    try:
        socket.create_connection(("127.0.0.1", port), timeout=0.5).close()
        return True
    except OSError:
        return False


def alive(pid: int) -> bool:
    return "No tasks" not in subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True).stdout


@pytest.fixture
def fake_mc(tmp_path, monkeypatch):
    """An mc folder whose gradlew.bat is a stand-in for the Minecraft dev client; FAKE_MODE picks its behaviour."""
    mc = tmp_path / "mc"
    mc.mkdir()
    (mc / "gradlew.bat").write_text('@"%FAKE_PYTHON%" "%~dp0fake_mc.py" %*\r\n')
    (mc / "fake_mc.py").write_text(FAKE_MC)
    port = free_port()
    monkeypatch.setenv("FAKE_PYTHON", sys.executable)
    monkeypatch.setenv("FAKE_PORT", str(port))
    monkeypatch.setenv("FAKE_MODE", "listen")
    return mc, port, tmp_path / "jdk"


def invocation(mc):
    return json.loads((mc / "invocation.json").read_text())


def test_starts_minecraft_waits_for_the_port_then_starts_the_game_and_ctrl_c_stops_minecraft(fake_mc, capsys):
    mc, port, jdk = fake_mc
    seen = {}

    def start_game():
        seen["port_open"] = port_open(port)
        raise KeyboardInterrupt  # the user pressed Ctrl+C

    assert play.play(mc, jdk, port, 30, start_game) == 0
    assert seen == {"port_open": True}
    inv = invocation(mc)
    assert inv["argv"] == ["runClient", "--args=--quickPlaySingleplayer cyberblock"] and inv["java_home"] == str(jdk)
    assert not alive(inv["pid"])
    assert f"127.0.0.1:{port}" in capsys.readouterr().out  # says what it is waiting for


def test_returns_minecrafts_exit_code_when_it_ends_by_itself(fake_mc, monkeypatch):
    mc, port, jdk = fake_mc
    monkeypatch.setenv("FAKE_LIFETIME", "3")
    started = []
    assert play.play(mc, jdk, port, 30, lambda: started.append(True)) == 0
    assert started == [True]


def test_minecraft_exiting_early_is_reported_and_the_game_is_not_started(fake_mc, monkeypatch, capsys):
    mc, port, jdk = fake_mc
    monkeypatch.setenv("FAKE_MODE", "exit3")
    assert play.play(mc, jdk, port, 30, lambda: pytest.fail("must not start the game")) == 3
    assert "exited with code 3" in capsys.readouterr().err


def test_timeout_stops_minecraft_and_fails(fake_mc, monkeypatch, capsys):
    mc, port, jdk = fake_mc
    monkeypatch.setenv("FAKE_MODE", "silent")
    assert play.play(mc, jdk, port, 1.5, lambda: pytest.fail("must not start the game")) == 1
    assert "did not open" in capsys.readouterr().err
    assert not alive(invocation(mc)["pid"])


def test_missing_gradlew_is_an_error(tmp_path, capsys):
    assert play.play(tmp_path, tmp_path, free_port(), 1, lambda: None) == 1
    assert "gradlew.bat" in capsys.readouterr().err


def test_a_running_minecraft_is_not_started_twice(fake_mc):
    mc, port, jdk = fake_mc
    started = []
    with socket.socket() as server:
        server.bind(("127.0.0.1", port))
        server.listen()
        assert play.play(mc, jdk, port, 30, lambda: started.append(True)) == 0
    assert started == [True] and not (mc / "invocation.json").exists()


def make_jdk(path, version="25.0.4.1", java=True):
    (path / "bin").mkdir(parents=True)
    if java:
        (path / "bin/java.exe").write_bytes(b"MZ")
    (path / "release").write_text(f'IMPLEMENTOR="Eclipse Adoptium"\nJAVA_VERSION="{version}"\n')
    return path


def test_find_jdk_takes_the_first_candidate_that_is_jdk_25_or_newer(tmp_path):
    old = make_jdk(tmp_path / "jdk21", "21.0.5")
    no_exe = make_jdk(tmp_path / "broken", java=False)
    good = make_jdk(tmp_path / "jdk25")
    newer = make_jdk(tmp_path / "jdk26", "26")
    assert play.find_jdk([None, "", tmp_path / "missing", old, no_exe, good, newer]) == good
    assert play.find_jdk([old, newer]) == newer
    assert play.find_jdk([old, no_exe]) is None


def test_default_candidates_include_java_home_and_the_portable_jdk(tmp_path, monkeypatch):
    portable = make_jdk(tmp_path / "Cyberblock/jdk-25.0.4.1+1")
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    monkeypatch.setenv("JAVA_HOME", str(tmp_path / "from-env"))
    candidates = [str(c) for c in play.default_jdk_candidates()]
    assert str(tmp_path / "from-env") == candidates[0] and str(portable) in candidates
