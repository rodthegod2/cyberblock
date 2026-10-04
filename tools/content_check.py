"""Validate Cyberblock content files (megaplan section 1.7): data as JSON, one object per file.

    uv run python -m tools.content_check                 # the shipped defaults (mc/src/main/resources/data)
    uv run python -m tools.content_check --dir <pack>    # a datapack folder (or its data/ folder), checked together with the defaults

Files live at `data/<namespace>/cyberblock/<kind>/<name>.json`; the id of a file is `<namespace>:<name>`. Each file must be
UTF-8 JSON (no duplicate keys, no NaN), at most 1 000 000 bytes, an object with `"schema": 1` that matches
`shared/content-schemas/<kind>.schema.json`. The schemas use a JSON Schema subset (this checker validates with the standard
library only, and refuses a schema keyword it does not implement, so nothing passes by being ignored):
type, enum, const, required, properties, additionalProperties, items, minItems, maxItems, uniqueItems, minLength, maxLength,
pattern, minimum, maximum, exclusiveMinimum, exclusiveMaximum, anyOf, oneOf, allOf, `$ref` to `#/$defs/<name>`, plus two
Cyberblock keywords: `maxBytes` (a string's UTF-8 byte cap, the plan's "byte caps") and `x-ref` (the string is the id of an
existing file of that kind, in the checked folders or the shipped defaults).

A `--dir` folder that is a datapack root (it holds `data/`) must also have a usable `pack.mcmeta` (`pack.description` and an integer or
[major, minor] `pack.min_format`, with `max_format` if given, covering the data pack format of this game version), because Minecraft
silently ignores a datapack folder without one. A bare `data/` folder needs none.

Exit code 0: nothing wrong; 1: problems (listed one per line, `<file>: <where>: <what>`); 2: bad usage. Warnings (a file in a
sub-folder, which the loader never reads) are listed but do not fail the check.
"""
import argparse
import json
import re
import sys
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_DIR = ROOT / "shared" / "content-schemas"
DEFAULT_DATA = ROOT / "mc" / "src" / "main" / "resources" / "data"
DATA_PACK_FORMAT = 121  # data_major of Minecraft 26.3 (version.json "pack_version" in the game jar)
MAX_FILE_BYTES = 1_000_000  # the `cyberblock:content` payload cap: one file can never be larger than a payload
NAMESPACE_RE, NAME_RE = re.compile(r"[a-z0-9_.-]+"), re.compile(r"[a-z0-9_.-]+")
ANNOTATIONS = {"$schema", "$id", "$comment", "title", "description", "default", "examples", "$defs"}
KEYWORDS = {"type", "enum", "const", "required", "properties", "additionalProperties", "items", "minItems", "maxItems", "uniqueItems",
            "minLength", "maxLength", "maxBytes", "pattern", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "anyOf", "oneOf",
            "allOf", "$ref", "x-ref"}
TYPES = {"object": dict, "array": list, "string": str, "boolean": bool, "null": type(None)}


class SchemaError(Exception):
    """A schema file that cannot be used (bad JSON, a keyword this checker does not implement)."""


def reject_duplicates(pairs: list[tuple[str, object]]) -> dict:
    keys = [k for k, _ in pairs]
    if dupes := sorted({k for k in keys if keys.count(k) > 1}):
        raise ValueError(f"duplicate key {dupes[0]!r}")
    return dict(pairs)


def reject_constant(name: str) -> None:
    raise ValueError(f"{name} is not valid JSON")


def parse(raw: bytes) -> object:
    """Strict JSON: UTF-8 (no BOM), no duplicate keys, no NaN or Infinity."""
    return json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicates, parse_constant=reject_constant)


def is_number(x: object) -> bool:
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def type_ok(value: object, name: str) -> bool:
    if name == "integer":
        return isinstance(value, int) and not isinstance(value, bool) or (isinstance(value, float) and value.is_integer())
    if name == "number":
        return is_number(value)
    return isinstance(value, TYPES[name]) and (name == "boolean" or not isinstance(value, bool))


def check_schema(schema: object, where: str = "$") -> None:
    """Refuse a schema that uses a keyword this checker would silently ignore."""
    if isinstance(schema, bool):
        return
    if not isinstance(schema, dict):
        raise SchemaError(f"{where}: a schema must be an object")
    for key, value in schema.items():
        if key in ANNOTATIONS:
            if key == "$defs":
                for name, sub in value.items():
                    check_schema(sub, f"{where}/$defs/{name}")
            continue
        if key not in KEYWORDS:
            raise SchemaError(f"{where}: unsupported schema keyword {key!r}")
        if key in ("properties",):
            for name, sub in value.items():
                check_schema(sub, f"{where}/properties/{name}")
        elif key in ("anyOf", "oneOf", "allOf"):
            for i, sub in enumerate(value):
                check_schema(sub, f"{where}/{key}/{i}")
        elif key in ("items", "additionalProperties"):
            check_schema(value, f"{where}/{key}")


@dataclass
class Checker:
    """Validates instances against one schema; collects the `x-ref` values to resolve once every id is known."""
    root: dict
    refs: list[tuple[str, str, str]] = field(default_factory=list)  # (where, kind, value)

    def resolve(self, ref: str) -> object:
        if not ref.startswith("#/$defs/") or ref[8:] not in self.root.get("$defs", {}):
            raise SchemaError(f"unsupported or unknown $ref {ref!r}")
        return self.root["$defs"][ref[8:]]

    def validate(self, value: object, schema: object, where: str = "$") -> list[str]:
        if schema is True:
            return []
        if schema is False:
            return [f"{where}: no value is allowed here"]
        errors: list[str] = []
        if "$ref" in schema:
            errors += self.validate(value, self.resolve(schema["$ref"]), where)
        if "type" in schema:
            names = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
            if not any(type_ok(value, n) for n in names):
                return errors + [f"{where}: expected {' or '.join(names)}, got {type(value).__name__}"]
        if "const" in schema and (value != schema["const"] or type(value) is not type(schema["const"])):
            errors.append(f"{where}: must be {json.dumps(schema['const'])}")
        if "enum" in schema and not any(value == e and type(value) is type(e) for e in schema["enum"]):
            errors.append(f"{where}: {json.dumps(value)} is not one of {json.dumps(schema['enum'])}")
        for sub in schema.get("allOf", []):
            errors += self.validate(value, sub, where)
        if "anyOf" in schema and not any(not Checker(self.root).validate(value, s, where) for s in schema["anyOf"]):
            errors.append(f"{where}: matches none of the allowed forms")
        if "oneOf" in schema and sum(not Checker(self.root).validate(value, s, where) for s in schema["oneOf"]) != 1:
            errors.append(f"{where}: must match exactly one of the allowed forms")
        if isinstance(value, str):
            errors += self.string(value, schema, where)
        elif is_number(value):
            errors += self.number(value, schema, where)
        elif isinstance(value, list):
            errors += self.array(value, schema, where)
        elif isinstance(value, dict):
            errors += self.obj(value, schema, where)
        return errors

    def string(self, value: str, schema: dict, where: str) -> list[str]:
        errors = []
        if "minLength" in schema and len(value) < schema["minLength"]:
            errors.append(f"{where}: shorter than {schema['minLength']} characters")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            errors.append(f"{where}: longer than {schema['maxLength']} characters")
        if "maxBytes" in schema and len(value.encode("utf-8")) > schema["maxBytes"]:
            errors.append(f"{where}: {len(value.encode('utf-8'))} bytes, over the cap of {schema['maxBytes']}")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            errors.append(f"{where}: {json.dumps(value)} does not match {schema['pattern']}")
        if "x-ref" in schema:
            self.refs.append((where, schema["x-ref"], value))
        return errors

    @staticmethod
    def number(value: float, schema: dict, where: str) -> list[str]:
        checks = (("minimum", lambda v, s: v < s, "below the minimum"), ("maximum", lambda v, s: v > s, "above the maximum"),
                  ("exclusiveMinimum", lambda v, s: v <= s, "not above"), ("exclusiveMaximum", lambda v, s: v >= s, "not below"))
        return [f"{where}: {value} is {text} {schema[key]}" for key, bad, text in checks if key in schema and bad(value, schema[key])]

    def array(self, value: list, schema: dict, where: str) -> list[str]:
        errors = []
        if "minItems" in schema and len(value) < schema["minItems"]:
            errors.append(f"{where}: fewer than {schema['minItems']} items")
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            errors.append(f"{where}: more than {schema['maxItems']} items")
        if schema.get("uniqueItems") and len({json.dumps(v, sort_keys=True) for v in value}) != len(value):
            errors.append(f"{where}: items must be unique")
        if "items" in schema:
            for i, item in enumerate(value):
                errors += self.validate(item, schema["items"], f"{where}[{i}]")
        return errors

    def obj(self, value: dict, schema: dict, where: str) -> list[str]:
        errors = [f"{where}: missing required key {k!r}" for k in schema.get("required", []) if k not in value]
        props = schema.get("properties", {})
        for key, item in value.items():
            if key in props:
                errors += self.validate(item, props[key], f"{where}.{key}")
            elif "additionalProperties" in schema:
                extra = schema["additionalProperties"]
                errors += [f"{where}: unknown key {key!r}"] if extra is False else self.validate(item, extra, f"{where}.{key}")
        return errors


def load_schema(kind: str, schema_dir: Path = SCHEMA_DIR) -> dict | None:
    """The schema of a kind, checked for unsupported keywords; None when the kind has no schema file."""
    path = schema_dir / f"{kind}.schema.json"
    if not path.is_file():
        return None
    try:
        schema = parse(path.read_bytes())
    except (ValueError, OSError) as e:
        raise SchemaError(f"{path.name}: {e}") from None
    check_schema(schema)
    return schema


@dataclass(frozen=True)
class Entry:
    namespace: str
    kind: str
    name: str
    path: Path
    root: Path

    @property
    def id(self) -> str:
        return f"{self.namespace}:{self.name}"

    @property
    def label(self) -> str:
        return self.path.relative_to(self.root.parent).as_posix() if self.path.is_relative_to(self.root.parent) else self.path.as_posix()


@dataclass
class Result:
    files: int = 0
    kinds: set[str] = field(default_factory=set)
    namespaces: set[str] = field(default_factory=set)
    problems: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.problems


def data_root(folder: Path) -> Path:
    """`folder` is a datapack root (it holds data/) or a data folder itself."""
    return folder / "data" if (folder / "data").is_dir() else folder


def scan(root: Path, result: Result | None = None) -> list[Entry]:
    """Every `<root>/<namespace>/cyberblock/<kind>/<name>.json`; stray files that the loader never reads become warnings."""
    out: list[Entry] = []
    for ns_dir in sorted(p for p in root.glob("*") if p.is_dir()):
        base = ns_dir / "cyberblock"
        if not base.is_dir():
            continue
        for kind_dir in sorted(p for p in base.glob("*") if p.is_dir()):
            for path in sorted(kind_dir.rglob("*")):
                if not path.is_file():
                    continue
                entry = Entry(ns_dir.name, kind_dir.name, path.stem, path, root)
                if path.parent != kind_dir:
                    if result:
                        result.warnings.append(f"{entry.label}: in a sub-folder, the loader reads only {kind_dir.name}/*.json")
                elif path.suffix != ".json":
                    if result:
                        result.warnings.append(f"{entry.label}: not a .json file, ignored")
                else:
                    out.append(entry)
    return out


def inspect(entry: Entry, schemas: dict, schema_dir: Path) -> tuple[list[tuple[str, str]], list[tuple[str, str, str]]]:
    """Everything wrong with one file as [(where, message)], and the `x-ref` values it holds as [(where, kind, value)]."""
    problems: list[tuple[str, str]] = []
    if not NAMESPACE_RE.fullmatch(entry.namespace) or not NAME_RE.fullmatch(entry.name):
        problems.append(("name", "namespace and file name use only a-z, 0-9, _, . and -"))
    if entry.kind not in schemas:
        try:
            schemas[entry.kind] = load_schema(entry.kind, schema_dir)
        except SchemaError as e:
            schemas[entry.kind] = e
    schema = schemas[entry.kind]
    if schema is None:
        return problems + [("kind", f"unknown kind {entry.kind!r} (no {entry.kind}.schema.json)")], []
    if isinstance(schema, SchemaError):
        return problems + [("kind", f"the schema of {entry.kind!r} is unusable: {schema}")], []
    raw = entry.path.read_bytes()
    if len(raw) > MAX_FILE_BYTES:
        return problems + [("size", f"{len(raw)} bytes, over the cap of {MAX_FILE_BYTES}")], []
    try:
        data = parse(raw)
    except ValueError as e:
        return problems + [("json", str(e))], []
    if not isinstance(data, dict):
        return problems + [("$", "a content file is one JSON object")], []
    if data.get("schema") != 1 or type(data.get("schema")) is not int:
        problems.append(("$.schema", "must be the number 1"))
    if "id" in data and data["id"] != entry.id:
        problems.append(("$.id", f"is {json.dumps(data['id'])} but the file is {entry.id}"))
    checker = Checker(schema)
    try:
        messages = checker.validate(data, schema)
    except SchemaError as e:
        messages = [f"$: {e}"]
    problems += [tuple(m.split(": ", 1)) for m in messages]
    return problems, checker.refs


def check(roots: list[Path], schema_dir: Path = SCHEMA_DIR, reference_roots: list[Path] = ()) -> Result:
    """Check every file under `roots`; the ids of `reference_roots` (the shipped defaults) also count for `x-ref`."""
    result = Result()
    entries = [(e, True) for root in roots for e in scan(root, result)] + [(e, False) for root in reference_roots for e in scan(root)]
    schemas: dict = {}
    known: dict[str, set[str]] = {}
    pending: list[tuple[str, list[tuple[str, str, str]]]] = []
    for entry, checked in entries:
        problems, refs = inspect(entry, schemas, schema_dir)
        if not problems:
            known.setdefault(entry.kind, set()).add(entry.id)
        if checked:
            result.files += 1
            result.kinds.add(entry.kind)
            result.namespaces.add(entry.namespace)
            result.problems += [f"{entry.label}: {where}: {message}" for where, message in problems]
            pending.append((entry.label, refs))
    for label, refs in pending:
        for where, kind, value in refs:
            if value not in known.get(kind, set()):
                result.problems.append(f"{label}: {where}: refers to {kind} {json.dumps(value)}, which does not exist")
    return result


def format_major(value: object) -> int | None:
    """A pack format as the major number: an integer or a [major, minor] pair; None for anything else."""
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    if isinstance(value, list) and len(value) == 2 and all(isinstance(v, int) and not isinstance(v, bool) for v in value):
        return value[0]
    return None


def check_pack_meta(folder: Path) -> list[str]:
    """What is wrong with the pack.mcmeta of a datapack root; [] for a bare data folder (nothing to check) or a good one."""
    if not (folder / "data").is_dir():
        return []
    path = folder / "pack.mcmeta"
    if not path.is_file():
        return ["pack.mcmeta: missing: Minecraft ignores a datapack folder without a pack.mcmeta (needs pack.description and pack.min_format)"]
    try:
        meta = parse(path.read_bytes())
    except ValueError as e:
        return [f"pack.mcmeta: not valid JSON: {e}"]
    pack = meta.get("pack") if isinstance(meta, dict) else None
    if not isinstance(pack, dict):
        return ["pack.mcmeta: needs a \"pack\" object"]
    problems = []
    if not isinstance(pack.get("description"), (str, dict, list)):
        problems.append("pack.mcmeta: pack.description is missing (text)")
    low = format_major(pack.get("min_format"))
    if low is None:
        problems.append(f"pack.mcmeta: pack.min_format must be a whole number such as {DATA_PACK_FORMAT}")
    high = format_major(pack.get("max_format")) if "max_format" in pack else None
    if "max_format" in pack and high is None:
        problems.append("pack.mcmeta: pack.max_format must be a whole number or [major, minor]")
    if low is not None and not problems and not (low <= DATA_PACK_FORMAT and (high is None or DATA_PACK_FORMAT <= high)):
        problems.append(f"pack.mcmeta: the pack formats {low}..{high if high is not None else 'any'} do not cover this game's data pack format "
                        f"{DATA_PACK_FORMAT}, so Minecraft will not enable it by itself")
    return problems


def report(result: Result, say: Callable[[str], None] = print) -> int:
    for line in result.warnings:
        say(f"warning: {line}")
    for line in result.problems:
        say(f"FAIL  {line}")
    kinds = ", ".join(sorted(result.kinds)) or "none"
    if result.files == 0 and not result.problems:
        say("content: no content files found (nothing to check)")
    elif result.ok:
        say(f"content: {result.files} file(s) ok (kinds: {kinds}; namespaces: {', '.join(sorted(result.namespaces))})")
    else:
        say(f"content: {len(result.problems)} problem(s) in {result.files} file(s) (kinds: {kinds})")
    return 0 if result.ok else 1


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Validate Cyberblock content files (data/<namespace>/cyberblock/<kind>/<name>.json).")
    ap.add_argument("--dir", type=Path, action="append", default=[], metavar="PACK",
                    help="a datapack folder (or its data/ folder) to check together with the shipped defaults (repeatable)")
    ap.add_argument("--no-defaults", action="store_true", help="with --dir: check only the given folders and do not resolve ids against the shipped defaults")
    args = ap.parse_args(argv)
    if args.no_defaults and not args.dir:
        ap.error("--no-defaults needs --dir")
    for d in args.dir:
        if not d.is_dir():
            ap.error(f"{d} is not a folder")
    defaults = [DEFAULT_DATA] if DEFAULT_DATA.is_dir() else []
    if args.dir:
        result = check([data_root(d) for d in args.dir], reference_roots=[] if args.no_defaults else defaults)
        result.problems[:0] = [f"{d.as_posix()}/{message}" for d in args.dir for message in check_pack_meta(d)]
    else:
        result = check(defaults)
    return report(result)


if __name__ == "__main__":
    sys.exit(main())
