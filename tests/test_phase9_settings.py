"""Megaplan 9-S1: the settings screen's `cyberblock:act`/`cyberblock:ui` `settings` pair as shared/protocol.md "Phase 9" documents it agrees with the code
(SettingsWire builds the snapshot, ServerSettings answers it, the screen sends only /cyberblock commands the server registers), and the join password
is never part of it."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEXT = (ROOT / "shared" / "protocol.md").read_text(encoding="utf-8")
MAIN = ROOT / "mc" / "src" / "main" / "java" / "dev" / "cyberblock"
CLIENT = ROOT / "mc" / "src" / "client" / "java" / "dev" / "cyberblock" / "client"
WIRE = (MAIN / "settings" / "SettingsWire.java").read_text(encoding="utf-8")
SCREEN = (CLIENT / "settings" / "CyberblockSettingsScreen.java").read_text(encoding="utf-8")
TOP = {"perms", "devices", "body", "pvp", "hidePin", "host", "admin", "erase", "eraseForced", "overlord", "heat"}
WORLD = {"allowHosts", "hosts", "maxHosts", "requireMod", "rate", "sanityRadius", "pvpDefault", "backups", "webMap", "joinPassword", "joinHelp"}


def block(first_line: str) -> list[dict]:
    m = re.search(rf"{re.escape(first_line)}\n```jsonl\n(.*?)\n```", TEXT, re.S)
    assert m, first_line
    return [json.loads(line) for line in m.group(1).splitlines()]


def test_the_examples_have_the_documented_shape():
    assert block('Phase 9 examples, client to server (`cyberblock:act`, `t:"settings"`):') == [{}]
    guest, admin = block('Phase 9 examples, server to client (`cyberblock:ui`, `t:"settings"`), a guest and an operator:')
    assert set(guest) == TOP and not guest["admin"], "server.json's rows go to admins only"
    assert set(admin) == TOP | {"world"} and admin["admin"]
    assert set(admin["world"]) == WORLD and set(admin["world"]["backups"]) == {"daily", "keep", "hour"}
    for s in (guest, admin):
        assert s["body"] in ("male", "female") and s["overlord"] in ("chill", "normal", "brutal") and 25 <= s["heat"] <= 200
        assert 0 <= s["perms"] <= 31 and 0 <= s["devices"] <= 0x7FF


def test_the_code_writes_every_documented_field_and_nothing_else():
    written = set(re.findall(r'addProperty\("(\w+)"', WIRE)) | set(re.findall(r'\.add\("(\w+)"', WIRE))
    assert written == TOP | WORLD | {"world", "daily", "keep", "hour"}


def test_the_join_password_is_only_ever_a_bool():
    assert re.search(r'addProperty\("joinPassword", w\.joinPassword\(\)\)', WIRE)
    assert "boolean joinPassword" in WIRE and "String joinPassword" not in WIRE
    server = (MAIN / "settings" / "ServerSettings.java").read_text(encoding="utf-8")
    assert "c.joinPasswordOn()" in server and "c.joinPassword()" not in server


def test_the_rows_send_only_commands_the_server_registers():
    sent = set(re.findall(r'"cyberblock (\w+)', SCREEN))
    assert sent == {"guests", "body", "holo", "pvp", "difficulty", "outline"}
    registered = "\n".join(p.read_text(encoding="utf-8") for p in MAIN.rglob("*.java"))
    for literal in sent:
        assert f'Commands.literal("{literal}")' in registered, literal
    assert 'Commands.literal("difficulty")' in (MAIN / "settings" / "ServerSettings.java").read_text(encoding="utf-8")
