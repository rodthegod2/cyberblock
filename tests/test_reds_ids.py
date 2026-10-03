"""Guard for the entity id hash rule (plan 2026-10-03-retest2 sections 1.1 and 1.5, task S1).

Codeware's EntityID.ToHash takes script_ref<EntityID>; handed a call result (EntityID.ToHash(x.GetEntityID())) it
hashed garbage in game, so every EntityID.ToHash argument in our scripts must be a bare identifier (a local copy).
"""
import re
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


def test_the_guard_catches_call_results():
    assert bad_calls("let h = EntityID.ToHash(object.GetEntityID());") == [(1, "object.GetEntityID(")]
    assert bad_calls("// EntityID.ToHash(a.GetEntityID())\nlet h = EntityID.ToHash( id );") == []
    assert bad_calls('Log("x // y"); let h = EntityID.ToHash(o.GetEntityID());') == [(1, "o.GetEntityID(")]
