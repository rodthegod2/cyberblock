"""Guards for the Cyberpunk scripts that the compile checker cannot see.

Entity id hash rule (plan 2026-10-03-retest2 sections 1.1 and 1.5, task S1): Codeware's EntityID.ToHash takes
script_ref<EntityID>; handed a call result (EntityID.ToHash(x.GetEntityID())) it hashed garbage in game, so every
EntityID.ToHash argument in our scripts must be a bare identifier (a local copy).

Natives rule (plan 2026-10-03-retest3-polish sections 0 and 1.2): every native is declared exactly once, and the ones
package S calls are declared in Natives.reds with the contract's signature.
"""
import importlib.util
import re
import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent / "cp" / "scripts" / "Cyberblock"
# A string literal is kept (it may hold "//"); a // comment is dropped up to the end of its line.
COMMENT = re.compile(r'("(?:\\.|[^"\\\n])*")|//[^\n]*')
CALL = re.compile(r"EntityID\.ToHash\(\s*([^)]*)\)")
BARE = re.compile(r"[A-Za-z_]\w*")


def strip_comments(text: str) -> str:
    return COMMENT.sub(lambda m: m.group(1) or "", text)


def bad_calls(text: str) -> list[tuple[int, str]]:
    code = strip_comments(text)
    return [(code.count("\n", 0, m.start()) + 1, m.group(1).strip())
            for m in CALL.finditer(code) if not BARE.fullmatch(m.group(1).strip())]


def test_scripts_exist():
    assert list(SCRIPTS.glob("*.reds")), SCRIPTS


def test_to_hash_takes_only_bare_identifiers():
    bad = [f"{path.name}:{line}: EntityID.ToHash({arg})"
           for path in sorted(SCRIPTS.glob("*.reds"))
           for line, arg in bad_calls(path.read_text(encoding="utf-8"))]
    assert not bad, "pass CyberblockIds.Hash(entity), or a local EntityID, never a call result:\n" + "\n".join(bad)


def test_ids_hash_copies_to_a_local():
    text = strip_comments((SCRIPTS / "Ids.reds").read_text(encoding="utf-8"))
    assert re.search(r"public static func Hash\(entity: ref<Entity>\) -> Uint64", text)
    assert re.search(r"let (\w+) = entity\.GetEntityID\(\);\s*return EntityID\.ToHash\(\1\);", text)


# Plan 2026-10-03-retest3-polish section 1.2: the natives package S declares (D implements them in natives.cpp).
RETEST3_NATIVES = [
    "public native func Cyberblock_HackResult(id: Int32, ok: Bool, reason: String, upload: Float, kind: CName) -> Void",
    "public native func Cyberblock_HackSpread(id: Int32, target: Uint64, why: CName) -> Void",
    "public native func Cyberblock_SetSeat(seated: Bool, tpp: Bool, hipsLocal: Vector4) -> Void",
    "public native func Cyberblock_SetSlowMoFx(id: CName, seconds: Float) -> Void",
    "public native func Cyberblock_SetViewAllowed(allowed: Bool) -> Void",
    "public native func Cyberblock_ViewEye() -> Vector4",
]
NATIVE_NAME = re.compile(r"native func (\w+)\(")


def all_code() -> dict[str, str]:
    return {path.name: strip_comments(path.read_text(encoding="utf-8")) for path in sorted(SCRIPTS.glob("*.reds"))}


def test_retest3_natives_are_declared_once_in_natives_reds():
    code = all_code()
    for signature in RETEST3_NATIVES:
        name = NATIVE_NAME.search(signature).group(1)
        declared = [(file, text.count(f"native func {name}(")) for file, text in code.items() if f"native func {name}(" in text]
        assert declared == [("Natives.reds", 1)], f"{name}: {declared}"
        assert signature + ";" in code["Natives.reds"], f"{name}: the signature differs from plan section 1.2"


def test_hack_results_carry_upload_and_kind():
    # Every caller of the changed native passes the five arguments (id, ok, reason, upload, kind).
    calls = [(file, m.group(1)) for file, text in all_code().items()
             for m in re.finditer(r"(?<!func )Cyberblock_HackResult\(([^;]*)\);", text)]
    assert calls, "no Cyberblock_HackResult call found"
    for file, args in calls:
        depth, commas = 0, 0
        for ch in args:
            depth += ch in "(["
            depth -= ch in ")]"
            commas += ch == "," and depth == 0
        assert commas == 4, f"{file}: Cyberblock_HackResult({args})"


def load_compile_check():
    spec = importlib.util.spec_from_file_location("compile_check", SCRIPTS.parent / "compile_check.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_compile_check_finds_the_game_like_the_installer(monkeypatch, tmp_path):
    # Plan 2026-10-03-retest3-polish S6 (F11): no hard-coded D:\SteamLibrary; the installer's Steam lookup decides.
    from tools import install
    monkeypatch.setattr(install, "find_game", lambda: tmp_path)
    check = load_compile_check()
    assert check.default_game() == tmp_path
    assert "SteamLibrary" not in (SCRIPTS.parent / "compile_check.py").read_text(encoding="utf-8")


def test_compile_check_says_why_without_a_game(monkeypatch, capsys):
    from tools import install

    def missing():
        raise install.InstallError("Steam not found in the registry; pass --game")
    monkeypatch.setattr(install, "find_game", missing)
    monkeypatch.setattr(sys, "argv", ["compile_check.py"])
    assert load_compile_check().main() == 2
    assert "pass --game" in capsys.readouterr().err


def test_the_guard_catches_call_results():
    assert bad_calls("let h = EntityID.ToHash(object.GetEntityID());") == [(1, "object.GetEntityID(")]
    assert bad_calls("// EntityID.ToHash(a.GetEntityID())\nlet h = EntityID.ToHash( id );") == []
    assert bad_calls('Log("x // y"); let h = EntityID.ToHash(o.GetEntityID());') == [(1, "o.GetEntityID(")]
