"""The settings screen's multiplayer check and screenshot tour (megaplan task 9-S1), on tools/mpcheck.py's harness.

    uv run python tests/mpcheck_settings.py [--keep] [--shots DIR]

Run it from a git worktree (it runs Gradle). It starts a dedicated test server on 127.0.0.1:25993 and one host client CbHost1 on link port 25833
(frame Local\\CyberblockFrame9SH; package 9S's slots, megaplan section 1.15) driven by a fake host, made an operator. The client runs the settings
screen's screenshot tour (CYBERBLOCK_SETTINGS_SHOTS): it opens every tab, then changes guest spawns and the Heat scaling through the screen's own
command path. Checks: the server's start-up line names the difficulty; the screen's snapshot reaches the client (admin, host, server.json rows shown);
the screen's commands change the server (spawns on, Heat scaling 150 %) and the next snapshot shows it; `/cyberblock difficulty` answers with the new
values; after `deop` the snapshot says admin no (RCON's `execute as` keeps operator permission, so a non-operator's refused command is left to the
command tree's `requires`); then, as a non-operator, the tour scrolls the mouse wheel over a server row (nothing changes) and presses all 16 Guests
rows in one tick: the client paces the commands, every toggle and device type ends off, and the player is not kicked for command spam (vanilla
spares only operators); an Overlord default set from the console is kept; the server logged no settings warnings. With --shots DIR the tour's PNGs
(9s_*.png) are copied into DIR. This file is not a pytest module (no `test_` prefix): it starts Minecraft.
"""
import argparse
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from tools import mpcheck as mp, play  # noqa: E402

CLIENT_HOST, SERVER = 25833, 25993
FRAME_PREFIX = "Local\\CyberblockFrame9S"
T_TOUR = 600.0
T_BURST = 60.0  # 16 commands, one every 1.1 s, and the last answer


class SettingsPorts(mp.Ports):
    """9S's ports: one server port and the host's link port; frame Local\\CyberblockFrame9SH."""

    def server(self, scenario: str) -> int:
        return SERVER

    def client(self, name: str) -> int:
        return {"CbHost1": CLIENT_HOST}[name]

    def check(self) -> None:
        if mp.FORBIDDEN_PORTS & {SERVER, CLIENT_HOST} or self.frame("CbHost1") == "Local\\CyberblockFrame":
            raise mp.MpError("9S's ports and frames must not be the user's own")


class ShotsEnv(mp.Env):
    """The harness, with the client running the settings screenshot tour."""

    def _child_env(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        return super()._child_env({**(extra or {}), "CYBERBLOCK_SETTINGS_SHOTS": "1"})


def scenario_settings(env: mp.Env, rep: mp.Report) -> None:
    server = env.server("settings", max_hosts=1)
    rep.step("the server names the world's difficulty at start", lambda: bool(server.log.wait(
        r"settings: difficulty: Overlord intensity default normal, Heat scaling 100 % \(world setting", mp.T_EFFECT)))
    client, _ = mp.start_host(env, rep, server, "CbHost1", ver=env.mod_version, wait_host=mp.T_JOIN)
    server.rcon("op CbHost1")
    rep.step("the screen gets the server's values (admin, host, server.json rows shown)", lambda: bool(client.log.wait(
        r"settings: server values received \(admin yes, host yes, server.json rows shown\)", T_TOUR)), must=True)
    rep.step("the screen's command turned guest spawns on (the server's next snapshot shows guests 31)",
             lambda: bool(client.log.wait(r"settings: server values: admin yes, guests 31 devices 2047", T_TOUR)))
    rep.step("the screen's command set the Heat scaling (server log)", lambda: bool(server.log.wait(r"settings: Heat scaling 150 % \(by CbHost1\)", T_TOUR)))
    rep.step("... and the next snapshot shows it", lambda: bool(client.log.wait(r"settings: server values: .*heat 150 %", mp.T_EFFECT)))
    rep.step("the tour shot every tab", lambda: bool(client.log.wait(r"shots: done", T_TOUR)), must=True)
    rep.step("/cyberblock difficulty answers with the new values", lambda: "Heat scaling: 150 %" in server.rcon("cyberblock difficulty"))

    server.rcon("deop CbHost1")
    time.sleep(1.0)
    mark = client.log.mark()
    server.command('cyberblock test act settings {}', as_player="CbHost1")
    rep.step("after deop the snapshot says admin no", lambda: bool(client.log.wait(r"settings: server values: admin no,", mp.T_EFFECT, mark)))

    # the tour, now as a non-operator (the server's command spam kick spares operators): the wheel over a server row, then every Guests row in one tick
    server_mark = server.log.mark()
    rep.step("as a non-operator the wheel over a server row changed nothing, and all 16 Guests rows were pressed at once", lambda: bool(client.log.wait(
        r"shots: burst: wheel over a server row ignored: yes; pressed 16 server rows", mp.T_EFFECT, mark)))
    rep.step("... their commands reached the server: every guest toggle and device type is off", lambda: bool(client.log.wait(
        r"settings: server values: admin no, guests 0 devices 0,", T_BURST, mark)))
    rep.step("... one command per row, paced", lambda: len(re.findall(r"settings: /cyberblock guests ", client.log.text(mark))) == 16)
    rep.step("... and CbHost1 was not kicked for command spam", lambda: "CbHost1" in server.rcon("list") and not server.log.find(r"CbHost1 lost connection", server_mark)
             and not client.log.find(r"disconnect\.spam|Kicked for spamming", mark))

    server.rcon("cyberblock difficulty overlord brutal")
    rep.step("the console sets the Overlord default", lambda: "new matches: brutal" in server.rcon("cyberblock difficulty")
             and bool(server.log.wait(r"settings: Overlord intensity default brutal \(by \w+\)", mp.T_EFFECT)))
    rep.step("the server logged no settings warnings", lambda: not server.log.find(r"WARN.*settings: ") and not client.log.find(r"WARN.*settings: "))


def copy_shots(env: mp.Env, out: Path) -> int:
    out.mkdir(parents=True, exist_ok=True)
    shots = sorted(env.run.glob("client-CbHost1-*/screenshots/9s_*.png"))
    for png in shots:
        (out / png.name).write_bytes(png.read_bytes())
    print("\n".join(f"  {out / p.name}" for p in shots))
    return len(shots)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="The settings screen's multiplayer check (megaplan 9-S1). Run it from a worktree.")
    ap.add_argument("--keep", action="store_true", help="keep the run folder for debugging")
    ap.add_argument("--shots", type=Path, metavar="DIR", help="copy the tour's PNGs (9s_*.png) into DIR")
    args = ap.parse_args(argv)
    if not (ROOT / ".git").is_file():
        print(f"{ROOT} is the main checkout: run this from a git worktree (it runs Gradle)", file=sys.stderr)
        return 2
    java = play.find_jdk(play.default_jdk_candidates())
    if java is None:
        print(f"no JDK {play.MIN_JAVA} found: set JAVA_HOME to one", file=sys.stderr)
        return 1
    env = ShotsEnv(ROOT, SettingsPorts(CLIENT_HOST, SERVER, FRAME_PREFIX), java, keep=args.keep)
    mp.SCENARIO_FUNCS["settings"] = scenario_settings
    try:
        code = mp.summary(mp.run_scenarios(env, ["settings"]))
        if args.shots and copy_shots(env, args.shots) < 8:
            print("fewer than the 8 screenshots of the tour", file=sys.stderr)
            code = code or 1
        return code
    except KeyboardInterrupt:
        print("interrupted: stopping everything this run started", file=sys.stderr)
        return 130
    except mp.MpError as e:
        print(f"mpcheck_settings: {e}", file=sys.stderr)
        return 1
    finally:
        env.close()


if __name__ == "__main__":
    sys.exit(main())
