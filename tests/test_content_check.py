"""tools/content_check.py: the JSON Schema subset, the file rules, id references and the command line."""
import json
from pathlib import Path

import pytest

from tools import content_check as cc

REPO = Path(__file__).resolve().parents[1]
GOOD = {"schema": 1, "name": "Rooftop", "colour": "#FF00aa", "pos": [1, 64.5, -3], "count": 3, "mode": "a", "tags": ["x", "y"]}


def put(root: Path, namespace: str, kind: str, name: str, content, raw: bytes | None = None) -> Path:
    folder = root / "data" / namespace / "cyberblock" / kind
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"{name}.json"
    path.write_bytes(raw if raw is not None else json.dumps(content).encode("utf-8"))
    return path


def put_pack(root: Path, pack: object = None) -> Path:
    """A datapack root's pack.mcmeta (the format of Minecraft 26.3 unless given)."""
    root.mkdir(parents=True, exist_ok=True)
    path = root / "pack.mcmeta"
    path.write_text(json.dumps({"pack": {"description": "x", "min_format": cc.DATA_PACK_FORMAT, "max_format": cc.DATA_PACK_FORMAT}} if pack is None else pack), encoding="utf-8")
    return path


def run(root: Path, **kw) -> cc.Result:
    return cc.check([cc.data_root(root)], **kw)


def problems(result: cc.Result) -> str:
    return "\n".join(result.problems)


# ---- the schema subset --------------------------------------------------------------------------------------------------
def errors(value, schema, root=None):
    return cc.Checker(root or schema).validate(value, schema)


@pytest.mark.parametrize("value, schema, ok", [
    ("a", {"type": "string"}, True), (1, {"type": "string"}, False), (True, {"type": "integer"}, False), (3, {"type": "integer"}, True),
    (3.0, {"type": "integer"}, True), (3.5, {"type": "integer"}, False), (True, {"type": "number"}, False), (None, {"type": ["null", "string"]}, True),
    ({}, {"type": "array"}, False), ([], {"type": "array"}, True), (True, {"type": "boolean"}, True), (1, {"type": "boolean"}, False),
    (1, {"const": 1}, True), (True, {"const": 1}, False), ("a", {"enum": ["a", "b"]}, True), (1, {"enum": [True]}, False),
    ("ab", {"minLength": 3}, False), ("abc", {"minLength": 3, "maxLength": 3}, True), ("abcd", {"maxLength": 3}, False),
    ("ééé", {"maxLength": 3, "maxBytes": 5}, False), ("ééé", {"maxBytes": 6}, True), ("a1", {"pattern": "^[a-z]+$"}, False),
    (5, {"minimum": 5, "maximum": 5}, True), (4, {"minimum": 5}, False), (6, {"maximum": 5}, False), (5, {"exclusiveMinimum": 5}, False),
    (5, {"exclusiveMaximum": 5}, False), (4.9, {"exclusiveMaximum": 5, "exclusiveMinimum": 4}, True),
    ([1, 2], {"minItems": 3}, False), ([1, 2, 3], {"maxItems": 2}, False), ([1, 1], {"uniqueItems": True}, False), ([1, "1"], {"uniqueItems": True}, True),
    ([1, "a"], {"items": {"type": "integer"}}, False), ({"a": 1}, {"required": ["a"]}, True), ({}, {"required": ["a"]}, False),
    ({"b": 1}, {"properties": {"a": {}}, "additionalProperties": False}, False), ({"b": "x"}, {"additionalProperties": {"type": "integer"}}, False),
    ({"a": 1}, {"properties": {"a": {"type": "string"}}}, False),
    (1, {"anyOf": [{"type": "string"}, {"type": "integer"}]}, True), (1.5, {"anyOf": [{"type": "string"}, {"type": "integer"}]}, False),
    (1, {"oneOf": [{"type": "integer"}, {"minimum": 0}]}, False), (-1, {"oneOf": [{"type": "integer"}, {"minimum": 0}]}, True),
    (3, {"allOf": [{"type": "integer"}, {"minimum": 4}]}, False), (1, True, True), (1, False, False),
])
def test_schema_keywords(value, schema, ok):
    assert (not errors(value, schema)) is ok


def test_ref_to_a_local_definition_and_the_refusal_of_other_refs():
    root = {"$defs": {"n": {"type": "integer", "minimum": 0}}, "properties": {"a": {"$ref": "#/$defs/n"}}}
    assert not errors({"a": 1}, root) and errors({"a": -1}, root) and errors({"a": "x"}, root)
    with pytest.raises(cc.SchemaError):
        errors({"a": 1}, {"properties": {"a": {"$ref": "other.json"}}})
    with pytest.raises(cc.SchemaError):
        errors({"a": 1}, {"properties": {"a": {"$ref": "#/$defs/missing"}}})


def test_error_locations_name_the_path_to_the_value():
    schema = {"properties": {"a": {"items": {"properties": {"b": {"type": "string"}}}}}}
    assert errors({"a": [{"b": "x"}, {"b": 2}]}, schema) == ["$.a[1].b: expected string, got int"]


def test_a_schema_keyword_this_checker_does_not_implement_is_refused_not_ignored():
    with pytest.raises(cc.SchemaError, match="unsupported schema keyword 'format'"):
        cc.check_schema({"properties": {"a": {"type": "string", "format": "uri"}}})
    with pytest.raises(cc.SchemaError, match="patternProperties"):
        cc.check_schema({"patternProperties": {}})
    cc.check_schema({"title": "x", "description": "y", "$defs": {"a": {"type": "string"}}, "properties": {"a": {"$ref": "#/$defs/a"}}})


def test_strict_json_rejects_duplicates_nan_and_a_bom():
    assert cc.parse(b'{"a": [1, 2.5, null, true]}') == {"a": [1, 2.5, None, True]}
    for raw in (b'{"a": 1, "a": 2}', b'{"a": NaN}', b'{"a": Infinity}', b'\xef\xbb\xbf{"a": 1}', b'{"a": 1,}', b'\xff', b''):
        with pytest.raises(ValueError):
            cc.parse(raw)


# ---- the files ----------------------------------------------------------------------------------------------------------
def test_a_valid_file_passes_and_the_report_says_so(tmp_path):
    put(tmp_path, "cyberblock", "test", "rooftop", GOOD)
    result = run(tmp_path)
    said = []
    assert result.ok and result.files == 1 and cc.report(result, said.append) == 0
    assert said == ["content: 1 file(s) ok (kinds: test; namespaces: cyberblock)"]


@pytest.mark.parametrize("change, expected", [
    ({"schema": 2}, "$.schema: must be the number 1"), ({"schema": "1"}, "$.schema: must be the number 1"), ({"schema": True}, "$.schema: must be the number 1"),
    ({"colour": "red"}, "$.colour"), ({"colour": "#12345"}, "$.colour"), ({"name": ""}, "$.name"), ({"name": "é" * 33}, "bytes, over the cap of 64"),
    ({"pos": [1, 2]}, "$.pos"), ({"pos": [1, 2, 4e9]}, "$.pos[2]"), ({"count": 1001}, "$.count"), ({"count": 1.5}, "$.count"), ({"mode": "d"}, "$.mode"),
    ({"tags": ["x", "x"]}, "$.tags: items must be unique"), ({"tags": ["t" * 33]}, "$.tags[0]"), ({"extra": 1}, "unknown key 'extra'"),
    ({"id": "cyberblock:other"}, "$.id: is \"cyberblock:other\" but the file is cyberblock:rooftop"), ({"id": "Bad Id"}, "$.id"),
])
def test_each_rule_fails_with_a_message_that_names_the_file_and_the_place(tmp_path, change, expected):
    put(tmp_path, "cyberblock", "test", "rooftop", {**GOOD, **change})
    result = run(tmp_path)
    assert not result.ok and expected in problems(result)
    assert all(line.startswith("data/cyberblock/cyberblock/test/rooftop.json: ") for line in result.problems)


def test_a_missing_required_key_and_the_wrong_top_level_type(tmp_path):
    put(tmp_path, "cyberblock", "test", "a", {"schema": 1})
    put(tmp_path, "cyberblock", "test", "b", [1, 2])
    out = problems(run(tmp_path))
    assert "a.json: $: missing required key 'name'" in out and "b.json: $: a content file is one JSON object" in out


def test_bad_json_is_reported_not_raised(tmp_path):
    put(tmp_path, "cyberblock", "test", "broken", None, raw=b'{"schema": 1, "name": ')
    put(tmp_path, "cyberblock", "test", "dupe", None, raw=b'{"schema": 1, "name": "a", "name": "b"}')
    put(tmp_path, "cyberblock", "test", "latin1", None, raw=b'{"schema": 1, "name": "\xe9"}')
    result = run(tmp_path)
    assert len(result.problems) == 3 and all(": json: " in p for p in result.problems)
    assert "duplicate key 'name'" in problems(result)


def test_a_file_over_the_byte_cap_is_refused_before_it_is_parsed(tmp_path):
    put(tmp_path, "cyberblock", "test", "huge", None, raw=b'{"schema": 1, "name": "' + b"a" * cc.MAX_FILE_BYTES + b'"}')
    assert "over the cap of 1000000" in problems(run(tmp_path))


def test_an_unknown_kind_and_bad_names_are_problems(tmp_path):
    put(tmp_path, "cyberblock", "nonsense", "a", {"schema": 1})
    put(tmp_path, "Bad NS", "test", "Upper Case", {"schema": 1, "name": "x"})
    out = problems(run(tmp_path))
    assert "unknown kind 'nonsense'" in out and out.count(": name: ") == 1  # one name problem per file


def test_stray_files_are_warnings_not_failures(tmp_path):
    put(tmp_path, "cyberblock", "test", "ok", GOOD)
    nested = tmp_path / "data" / "cyberblock" / "cyberblock" / "test" / "deep"
    nested.mkdir()
    (nested / "x.json").write_text("{}", encoding="utf-8")
    (nested.parent / "notes.txt").write_text("hi", encoding="utf-8")
    result = run(tmp_path)
    assert result.ok and len(result.warnings) == 2 and any("sub-folder" in w for w in result.warnings) and any("not a .json" in w for w in result.warnings)
    said = []
    assert cc.report(result, said.append) == 0 and said[0].startswith("warning: ")


def test_folders_without_a_cyberblock_folder_are_not_content(tmp_path):
    (tmp_path / "data" / "minecraft" / "recipe").mkdir(parents=True)
    (tmp_path / "data" / "minecraft" / "recipe" / "x.json").write_text("{}", encoding="utf-8")
    result = run(tmp_path)
    said = []
    assert result.ok and result.files == 0 and cc.report(result, said.append) == 0 and "nothing to check" in said[0]


def test_a_datapack_root_and_a_data_folder_are_both_accepted(tmp_path):
    put(tmp_path, "friend", "test", "mine", GOOD)
    assert cc.data_root(tmp_path) == tmp_path / "data" and cc.data_root(tmp_path / "data") == tmp_path / "data"
    assert run(tmp_path).files == 1 and cc.check([tmp_path / "data"]).files == 1


# ---- id references ------------------------------------------------------------------------------------------------------
def test_a_reference_resolves_across_files_and_namespaces(tmp_path):
    put(tmp_path, "cyberblock", "test", "base", GOOD)
    put(tmp_path, "friend", "test", "child", {**GOOD, "parent": "cyberblock:base"})
    assert run(tmp_path).ok


def test_a_dangling_reference_names_the_missing_id(tmp_path):
    put(tmp_path, "friend", "test", "child", {**GOOD, "parent": "cyberblock:gone"})
    out = problems(run(tmp_path))
    assert out == 'data/friend/cyberblock/test/child.json: $.parent: refers to test "cyberblock:gone", which does not exist'


def test_a_reference_to_a_broken_file_does_not_resolve(tmp_path):
    put(tmp_path, "cyberblock", "test", "base", {"schema": 1})  # no name: invalid
    put(tmp_path, "friend", "test", "child", {**GOOD, "parent": "cyberblock:base"})
    assert "which does not exist" in problems(run(tmp_path))


def test_references_also_resolve_against_the_shipped_defaults(tmp_path):
    shipped, pack = tmp_path / "shipped", tmp_path / "pack"
    put(shipped, "cyberblock", "test", "base", GOOD)
    put(pack, "friend", "test", "child", {**GOOD, "parent": "cyberblock:base"})
    assert cc.check([pack / "data"], reference_roots=[shipped / "data"]).ok
    bad = cc.check([pack / "data"])
    assert not bad.ok and bad.files == 1  # without the defaults the parent is missing, and only the pack counts as checked


def test_defaults_that_are_only_references_are_not_reported(tmp_path):
    shipped, pack = tmp_path / "shipped", tmp_path / "pack"
    put(shipped, "cyberblock", "test", "broken", {"schema": 1})
    put(pack, "friend", "test", "ok", GOOD)
    assert cc.check([pack / "data"], reference_roots=[shipped / "data"]).ok


# ---- the shipped schemas and defaults -----------------------------------------------------------------------------------------
def test_every_shipped_schema_loads_and_uses_only_supported_keywords():
    schemas = sorted(cc.SCHEMA_DIR.glob("*.schema.json"))
    assert "test.schema.json" in [s.name for s in schemas]  # later kinds sort before it (material_palette, ...)
    for path in schemas:
        schema = cc.load_schema(path.name.removesuffix(".schema.json"))
        assert schema["type"] == "object" and schema["properties"]["schema"] == {"const": 1} and "schema" in schema["required"], path.name


def test_the_shipped_defaults_pass():
    result = cc.check([cc.DEFAULT_DATA] if cc.DEFAULT_DATA.is_dir() else [])
    assert result.ok, "\n".join(result.problems)


def test_the_test_schema_covers_the_plan_rules():
    schema = cc.load_schema("test")
    props = schema["properties"]
    assert props["colour"] == {"$ref": "#/$defs/colour"} and schema["$defs"]["colour"]["pattern"] == "^#[0-9A-Fa-f]{6}$"  # colours are #RRGGBB
    assert schema["$defs"]["pos"]["minItems"] == schema["$defs"]["pos"]["maxItems"] == 3  # positions are [x, y, z]
    assert props["name"]["maxBytes"] == 64 and schema["additionalProperties"] is False
    assert {"x-ref": "test"} in props["parent"]["allOf"]  # references are ids


def test_an_unusable_schema_is_reported_per_file(tmp_path):
    schemas = tmp_path / "schemas"
    schemas.mkdir()
    (schemas / "test.schema.json").write_text('{"properties": {"a": {"format": "uri"}}}', encoding="utf-8")
    put(tmp_path, "cyberblock", "test", "a", {"schema": 1})
    result = cc.check([tmp_path / "data"], schema_dir=schemas)
    assert not result.ok and "unusable" in problems(result) and "format" in problems(result)
    (schemas / "test.schema.json").write_text("{not json", encoding="utf-8")
    assert "unusable" in problems(cc.check([tmp_path / "data"], schema_dir=schemas))


# ---- the command line --------------------------------------------------------------------------------------------------------
def test_main_exit_codes_and_report(tmp_path, capsys):
    put_pack(tmp_path)
    put(tmp_path, "cyberblock", "test", "ok", GOOD)
    assert cc.main(["--dir", str(tmp_path), "--no-defaults"]) == 0
    assert "1 file(s) ok" in capsys.readouterr().out
    put(tmp_path, "cyberblock", "test", "bad", {**GOOD, "colour": "red"})
    assert cc.main(["--dir", str(tmp_path)]) == 1
    out = capsys.readouterr().out
    assert "FAIL  data/cyberblock/cyberblock/test/bad.json: $.colour" in out and "content: 1 problem(s) in 2 file(s)" in out


def test_main_without_arguments_checks_the_shipped_defaults(capsys):
    assert cc.main([]) == 0


@pytest.mark.parametrize("argv", [["--dir", "no-such-folder-anywhere"], ["--no-defaults"]])
def test_main_usage_errors_exit_2(argv):
    with pytest.raises(SystemExit) as e:
        cc.main(argv)
    assert e.value.code == 2


def test_two_pack_folders_can_be_checked_at_once(tmp_path, capsys):
    a, b = tmp_path / "a", tmp_path / "b"
    put_pack(a)
    put_pack(b)
    put(a, "one", "test", "x", GOOD)
    put(b, "two", "test", "y", {**GOOD, "parent": "one:x"})
    assert cc.main(["--dir", str(a), "--dir", str(b)]) == 0
    assert "2 file(s) ok" in capsys.readouterr().out


# ---- pack.mcmeta: Minecraft ignores a datapack folder without a usable one ------------------------------------------------
def test_a_datapack_root_without_pack_mcmeta_fails_and_says_why(tmp_path, capsys):
    put(tmp_path, "friend", "test", "mine", GOOD)
    assert cc.main(["--dir", str(tmp_path)]) == 1
    out = capsys.readouterr().out
    assert "/pack.mcmeta: missing" in out and "ignores" in out


@pytest.mark.parametrize("pack, expected", [
    ("{nope", "not valid JSON"),
    ({"pack": {"min_format": 121}}, "description"),
    ({"pack": {"description": "x"}}, "min_format"),
    ({"pack": {"description": "x", "min_format": "121"}}, "min_format"),
    ({"pack": {"description": "x", "min_format": 100, "max_format": 110}}, "do not cover"),
    ({"pack": {"description": "x", "min_format": 130}}, "do not cover"),
    ({"pack": {"description": "x", "min_format": 121, "max_format": "x"}}, "max_format"),
])
def test_a_bad_pack_mcmeta_is_a_problem(tmp_path, capsys, pack, expected):
    put(tmp_path, "friend", "test", "mine", GOOD)
    path = put_pack(tmp_path, {})
    path.write_text(pack if isinstance(pack, str) else json.dumps(pack), encoding="utf-8")
    assert cc.main(["--dir", str(tmp_path)]) == 1
    assert expected in capsys.readouterr().out


@pytest.mark.parametrize("pack", [
    {"pack": {"description": "x", "min_format": 121}},
    {"pack": {"description": {"text": "x"}, "min_format": [121, 0], "max_format": [121, 4]}},
    {"pack": {"description": "x", "min_format": 100, "max_format": 130}},
])
def test_pack_mcmeta_forms_that_cover_the_game_pass(tmp_path, capsys, pack):
    put(tmp_path, "friend", "test", "mine", GOOD)
    put_pack(tmp_path, pack)
    assert cc.main(["--dir", str(tmp_path)]) == 0, capsys.readouterr().out


def test_a_bare_data_folder_needs_no_pack_mcmeta(tmp_path):
    put(tmp_path, "friend", "test", "mine", GOOD)
    assert cc.main(["--dir", str(tmp_path / "data")]) == 0
