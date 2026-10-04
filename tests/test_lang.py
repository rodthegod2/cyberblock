"""The lang file agrees with the code: a key the code sends with an English fallback takes as many arguments in en_us.json."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LANG = ROOT / "mc" / "src" / "main" / "resources" / "assets" / "cyberblock" / "lang" / "en_us.json"
# translatableWithFallback("key", "fallback" ...: both literals (a computed key or fallback is left out)
CALL = re.compile(r'translatableWithFallback\(\s*"([^"\\]+)"\s*,\s*"((?:[^"\\]|\\.)*)"')
ARG = re.compile(r"%(?:(\d+)\$)?s")


def arguments(text: str) -> int:
    """How many arguments a Minecraft format string reads (%s, %1$s; %% is a literal percent sign)."""
    found = [m.group(1) for m in ARG.finditer(text.replace("%%", ""))]
    return max([int(i) for i in found if i] + [sum(1 for i in found if not i)])


def fallbacks() -> dict[str, set[str]]:
    out: dict[str, set[str]] = {}
    for path in [p for s in ("main", "client") for p in (ROOT / "mc" / "src" / s).rglob("*.java")]:  # tests may send broken lines on purpose
        for key, fallback in CALL.findall(path.read_text("utf-8")):
            out.setdefault(key, set()).add(fallback.encode().decode("unicode_escape"))
    return out


def test_arguments_counts():
    assert arguments("plain") == 0 and arguments("%s and %s") == 2 and arguments("%2$s %1$s") == 2 and arguments("100%% %s") == 1


def test_every_key_with_a_fallback_takes_the_same_arguments_in_the_lang():
    lang = json.loads(LANG.read_text("utf-8"))
    found = fallbacks()
    assert found, "no translatableWithFallback calls found"
    wrong = [f"{key}: lang {arguments(lang[key])} vs code {sorted(arguments(f) for f in texts)}"
             for key, texts in sorted(found.items()) if key in lang and any(arguments(f) != arguments(lang[key]) for f in texts)]
    assert not wrong, "\n".join(wrong)
