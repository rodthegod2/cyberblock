"""Phase 9 protocol (megaplan task 9-F1): `prefs.scale`, Performance mode's frame scale (section 1.9 row 9), its row, example and validator."""
import copy

from test_protocol_md import PQ_EXAMPLES, documented, num, prefs_ok, rows_by_type, section

P9_MC_TO_CP, P9_MC_EXAMPLES = "Phase 9: Minecraft → Cyberpunk", "Phase 9 examples, Minecraft to Cyberpunk:"
SCALE = (0.5, 1.0)


def prefs9_ok(m) -> bool:
    """What Minecraft sends from Phase 9 on: the seven Phase Q fields, each in its range, and `scale` 0.5..1."""
    return isinstance(m, dict) and "scale" in m and num(m["scale"], *SCALE) and prefs_ok({k: v for k, v in m.items() if k != "scale"})


def p9_examples():
    from test_protocol_md import examples
    return examples(P9_MC_EXAMPLES)


def test_p9_prefs_row_extends_phase_q_and_names_scale():
    assert documented(P9_MC_TO_CP) == {"prefs"}
    row = rows_by_type(P9_MC_TO_CP)["prefs"]
    assert "**extends Phase Q:**" in row and "`scale`" in row and "0.5..1" in row and "rounded half up" in row


def test_p9_example_is_a_valid_eight_field_prefs():
    found = p9_examples()
    assert [m["t"] for m in found] == ["prefs"]
    m = found[0]
    assert prefs9_ok(m) and m["scale"] == 0.75
    phase_q = next(e for t, e in PQ_EXAMPLES if t == "prefs")
    assert set(m) == set(phase_q) | {"scale"}  # the Phase Q fields, all of them, plus scale


def test_p9_validator_refuses_what_the_dll_would_clamp_or_keep():
    m = p9_examples()[0]
    for bad in (0.49, 1.01, -1, "0.75", None, True, float("nan")):
        assert not prefs9_ok({**copy.deepcopy(m), "scale": bad}), bad
    assert not prefs9_ok({k: v for k, v in m.items() if k != "scale"}), "Minecraft sends all eight fields"
    assert prefs9_ok({**m, "scale": 0.5}) and prefs9_ok({**m, "scale": 1})


def test_p9_rules_are_stated():
    text = " ".join((section("Phase 9 semantics and limits") + section(P9_MC_TO_CP)).split())
    for phrase in ("`scale` 1", "after each `hello`", "clamps `scale` into 0.5..1", "ignores the field", "`UpscaleSharpen`",
                   "compositor: Minecraft renders at", "frame export:", "uploads:"):
        assert phrase in text, phrase
