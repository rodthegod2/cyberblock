"""The content datapack check (megaplan task 9-C1): a real dedicated server and a real client, a real `/reload`, on tools/mpcheck.py's harness.

    uv run python tests/mpcheck_content.py [--keep]

Run it from a git worktree (it runs Gradle). It starts a dedicated test server on 127.0.0.1:25990 (package 9C's, megaplan section 1.15) and a
guest client on link port 25830 (frame Local\\CyberblockFrame9CG; no fake host is needed: the client only has to receive `cyberblock:content`).
Checks: every kind of section 1.7 loads at startup with no skipped file and no missing loader; the client receives the material palette; the
example datapack (examples/content-datapack, copied into the world's datapacks folder) shows up after `/reload` for all six kinds it adds and
`/cyberblock status` counts them; a palette file added to the datapack reaches the client through `cyberblock:content` after the next reload; a
broken edit is skipped with its reason and the server keeps running; fixing it brings it back; taking the datapack away takes its content away.
This file is not a pytest module (no `test_` prefix): it starts Minecraft.
"""
import argparse
import json
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from tools import mpcheck as mp, play  # noqa: E402

CLIENT, SERVER = 25830, 25990
FRAME_PREFIX = "Local\\CyberblockFrame9C"
PACK = "neon_crew"
EXAMPLE = ROOT / "examples" / "content-datapack"
DEFAULTS = ROOT / "mc" / "src" / "main" / "resources" / "data" / "cyberblock" / "cyberblock"
ADDED = {"dialogue": 1, "skin_map": 1, "shop": 1, "landmark": 1, "daemon": 1, "event": 1}  # what the example adds to the shipped counts
T_RELOAD = 60.0


class ContentPorts(mp.Ports):
    """9C's ports: one server port, one guest link port; the frame Local\\CyberblockFrame9CG."""

    def server(self, scenario: str) -> int:
        return SERVER

    def client(self, name: str) -> int:
        return {"CbGuest1": CLIENT}[name]

    def check(self) -> None:
        if mp.FORBIDDEN_PORTS & {SERVER, CLIENT} or self.frame("CbGuest1") == "Local\\CyberblockFrame":
            raise mp.MpError("9C's ports and frames must not be the user's own")


def shipped(kind: str) -> int:
    return len(list((DEFAULTS / kind).glob("*.json")))


def loaded(log: mp.LogFile, kind: str, n: int, namespaces: str, since: int) -> bool:
    """The reload printed `content: loaded <kind> <n> (<namespaces>)` after `since`."""
    return bool(log.find(rf"content: loaded {kind} {n} \({re.escape(namespaces)}\)", since))


def reload_and_wait(server: mp.Server, kind: str, n: int, namespaces: str) -> bool:
    mark = server.log.mark()
    server.rcon("reload")
    return bool(mp.poll(lambda: loaded(server.log, kind, n, namespaces, mark), T_RELOAD, 0.5, f"/reload to load {kind} {n}"))


def scenario_content(env: mp.Env, rep: mp.Report) -> None:
    server = env.server("content", max_hosts=1)
    pack = server.dir / "world" / "datapacks" / PACK
    kinds = ("material_palette", "device_map", "shop", "skin_map", "vehicle_map", "daemon", "breach_tier", "dialogue", "contract", "ripperdoc",
             "landmark", "npc_role", "event", "spawn_palette", "heat_tier", "match_mode")
    startup = server.log.text()
    for kind in kinds:
        rep.step(f"startup: {kind} loaded all {shipped(kind)} shipped files", lambda kind=kind: loaded(server.log, kind, shipped(kind), "cyberblock", 0))
    rep.step("startup: no content file was skipped", lambda: not re.search(r"content: \S+ \S+ skipped", startup))
    rep.step("startup: every kind of section 1.7 has a loader", lambda: "has no loader" not in startup)

    guest = env.client("CbGuest1", join=server.port)
    rep.step("a client joins", lambda: server.log.wait(r"handshake: CbGuest1 ", mp.T_JOIN), must=True)
    rep.step("the client received the material palette (cyberblock:content)",
             lambda: guest.log.wait(rf"content: loaded material_palette {shipped('material_palette')} \(cyberblock\)", mp.T_EFFECT))

    # the example datapack, dropped into the world's datapacks folder, shows up after /reload
    shutil.copytree(EXAMPLE, pack)
    mark = server.log.mark()
    server.rcon("reload")
    rep.step("/reload reads the datapack: landmark gains the example's file",
             lambda: mp.poll(lambda: loaded(server.log, "landmark", shipped("landmark") + 1, f"cyberblock, {PACK}", mark), T_RELOAD, 0.5, "the landmark"))
    if not loaded(server.log, "landmark", shipped("landmark") + 1, f"cyberblock, {PACK}", mark):
        server.rcon(f'datapack enable "file/{PACK}"')  # an older rule: a pack found by /reload is listed, not enabled
        rep.step("(the pack needed /datapack enable)", lambda: reload_and_wait(server, "landmark", shipped("landmark") + 1, f"cyberblock, {PACK}"))
    for kind, extra in ADDED.items():
        rep.step(f"... {kind} gains {extra}", lambda kind=kind, extra=extra: loaded(server.log, kind, shipped(kind) + extra, f"cyberblock, {PACK}", mark))
    rep.step("... device_map keeps its count (the vending file replaces the shipped one)",
             lambda: loaded(server.log, "device_map", shipped("device_map"), f"cyberblock, {PACK}", mark) or loaded(server.log, "device_map", shipped("device_map"), "cyberblock", mark))
    rep.step("... and no file of the example was skipped", lambda: not re.search(r"content: \S+ \S+ skipped", server.log.text(mark)))
    rep.step("/cyberblock status counts the new entries",
             lambda: re.search(rf"landmark {shipped('landmark') + 1}\b", server.rcon("cyberblock status")) is not None)

    # an edit to a client kind reaches the client
    palette = next((DEFAULTS / "material_palette").glob("*.json"))
    extra = pack / "data" / PACK / "cyberblock" / "material_palette" / "probe.json"
    extra.parent.mkdir(parents=True, exist_ok=True)
    extra.write_bytes(palette.read_bytes())
    cmark = guest.log.mark()
    rep.step("/reload with a new palette file: the server counts it",
             lambda: reload_and_wait(server, "material_palette", shipped("material_palette") + 1, f"cyberblock, {PACK}"))
    rep.step("... and the client gets the new palette through cyberblock:content",
             lambda: guest.log.wait(rf"content: loaded material_palette {shipped('material_palette') + 1} \(cyberblock, {PACK}\)", mp.T_EFFECT, cmark))

    # a broken edit is skipped and logged, never fatal; fixing it brings it back
    good = extra.read_bytes()
    extra.write_text("{ not json", encoding="utf-8")
    mark, cmark = server.log.mark(), guest.log.mark()
    server.rcon("reload")
    rep.step("a broken file is skipped with its reason", lambda: server.log.wait(rf"content: material_palette {PACK}:probe skipped: ", T_RELOAD, mark))
    rep.step("... the server still answers", lambda: server.rcon("list") != "")
    rep.step("... and the client drops the entry", lambda: guest.log.wait(rf"content: loaded material_palette {shipped('material_palette')} \(cyberblock\)", mp.T_EFFECT, cmark))
    extra.write_bytes(good)
    rep.step("fixing the file and /reload brings it back",
             lambda: reload_and_wait(server, "material_palette", shipped("material_palette") + 1, f"cyberblock, {PACK}"))

    # the daemon file is edited in place: the id stays, the content changes (the sequence)
    unlatch = pack / "data" / PACK / "cyberblock" / "daemon" / "unlatch.json"
    doc = json.loads(unlatch.read_text(encoding="utf-8"))
    unlatch.write_text(json.dumps({**doc, "sequence": ["7A", "E9"]}), encoding="utf-8")
    rep.step("an edited daemon file reloads without a skip", lambda: reload_and_wait(server, "daemon", shipped("daemon") + 1, f"cyberblock, {PACK}")
             and not re.search(r"content: daemon \S+ skipped", server.log.text()))

    # taking the datapack away takes its content away
    server.rcon(f'datapack disable "file/{PACK}"')
    rep.step("disabling the datapack takes its content away (landmark back to the shipped count)",
             lambda: reload_and_wait(server, "landmark", shipped("landmark"), "cyberblock"))
    rep.step("... and the client's palette is the shipped one again",
             lambda: guest.log.wait(rf"content: loaded material_palette {shipped('material_palette')} \(cyberblock\)", mp.T_EFFECT, cmark))
    rep.step("the relay dropped nothing", lambda: not server.log.find(r"relay: \S+ dropped"))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="The content datapack check (megaplan 9-C1). Run it from a worktree.")
    ap.add_argument("--keep", action="store_true", help="keep the run folder for debugging")
    args = ap.parse_args(argv)
    if not (ROOT / ".git").is_file():
        print(f"{ROOT} is the main checkout: run this from a git worktree (it runs Gradle)", file=sys.stderr)
        return 2
    java = play.find_jdk(play.default_jdk_candidates())
    if java is None:
        print(f"no JDK {play.MIN_JAVA} found: set JAVA_HOME to one", file=sys.stderr)
        return 1
    env = mp.Env(ROOT, ContentPorts(CLIENT, SERVER, FRAME_PREFIX), java, keep=args.keep)
    mp.SCENARIO_FUNCS["content"] = scenario_content
    try:
        return mp.summary(mp.run_scenarios(env, ["content"]))
    except KeyboardInterrupt:
        print("interrupted: stopping everything this run started", file=sys.stderr)
        return 130
    except mp.MpError as e:
        print(f"mpcheck_content: {e}", file=sys.stderr)
        return 1
    finally:
        env.close()


if __name__ == "__main__":
    sys.exit(main())
