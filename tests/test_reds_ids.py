"""Guards for the Cyberpunk scripts that the compile checker cannot see.

Entity id hash rule (plan 2026-10-03-retest2 sections 1.1 and 1.5, task S1): Codeware's EntityID.ToHash takes
script_ref<EntityID>; handed a call result (EntityID.ToHash(x.GetEntityID())) it hashed garbage in game, so every
EntityID.ToHash argument in our scripts must be a bare identifier (a local copy).

Natives rule (plan 2026-10-03-retest3-polish sections 0 and 1.2): every native is declared exactly once, and the ones
package S calls are declared in Natives.reds with the contract's signature.

Session (megaplan 2026-10-04 section 1.8, task 1-H1): the n"session" event wiring and the cleanup it triggers.
"""
import importlib.util
import json
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


# In-game 2026-10-03 (after F7): Minecraft's blows dealt their damage but no flinch, stagger or knockdown ever played, and a
# defeated NPC was never finished off. HitReactionComponent.EvaluateHit returns at once for a hit without a hit shape
# (GetBodyPart reads hitRepresentationResult.hitShapes[0]), so every hit Effects.reds queues on a person carries one.
def test_strikes_on_people_carry_a_hit_shape():
    code = strip_comments((SCRIPTS / "Effects.reds").read_text(encoding="utf-8"))
    strike = code[code.index("public static func StrikeBy("):]
    strike = strike[:strike.index("QueueHitEvent(hit, object)")]
    assert re.search(r"if IsDefined\(object as ScriptedPuppet\) \{\s*CyberblockEffects\.AddHitShape\(hit, hitPosition, direction\);", strike)
    shape = code[code.index("public static func AddHitShape("):]
    shape = shape[:shape.index("\n  }\n")]
    for needed in ("new HitShapeUserDataBase()", "EHitShapeType.Flesh", "m_hitReactionZone = EHitReactionZone.ChestLeft",
                   "shape.result.hitPositionEnter = hitPosition", "shape.userData = zone",
                   "ArrayPush(hit.hitRepresentationResult.hitShapes, shape)"):
        assert needed in shape, needed

# Megaplan Q-S1: the aim ray drops hits on our collision boxes (tag test on the hit entity) and casts again from just past
# the box, at most 4 times, so the aim distance is the Night City surface behind a placed block.
def test_aim_ray_passes_through_at_most_four_boxes():
    code = strip_comments((SCRIPTS / "Probes.reds").read_text(encoding="utf-8"))
    aim = code[code.index("private func Aim("):]
    aim = aim[:aim.index("\n  }\n")]
    assert re.search(r"TraceNightCity\([^;]*,\s*4\)", aim), "Aim must trace with a 4-box limit"
    trace = code[code.index("private static func TraceNightCity("):]
    trace = trace[:trace.index("\n  }\n")]
    assert "maxBoxes: Int32" in trace and "boxes <= maxBoxes" in trace
    assert "IsTagged(entity.GetEntityID(), CyberblockCollisionService.Tag())" in trace


# Megaplan Q-S2: no vault or climb onto our boxes, a read-only registry lookup, the step-up assist fed by event prefs.
ENTER = ("protected const func EnterCondition(const stateContext: ref<StateContext>, "
         "const scriptInterface: ref<StateGameScriptInterface>) -> Bool {")


def test_vault_and_climb_are_refused_on_our_boxes():
    code = strip_comments((SCRIPTS / "Locomotion.reds").read_text(encoding="utf-8"))
    for cls in ("ClimbDecisions", "VaultDecisions"):
        assert f"@wrapMethod({cls})\n{ENTER}" in code, cls
    assert 's"locomotion: \\(what) refused on Cyberblock box"' in code
    assert 'Refuse(scriptInterface, "climb")' in code and 'Refuse(scriptInterface, "vault")' in code
    collision = strip_comments((SCRIPTS / "Collision.reds").read_text(encoding="utf-8"))
    assert "public func Has(point: Vector4) -> Bool" in collision
    assert "public func Find(point: Vector4, out aabbMin: Vector4) -> Bool" in collision


def test_step_up_follows_the_prefs_event_whatever_the_state():
    code = strip_comments((SCRIPTS / "Events.reds").read_text(encoding="utf-8"))
    tick = code[code.index("public func Tick("):code.index("private func Apply(")]
    prefs = tick.index('Equals(kind, n"prefs")')
    assert prefs < tick.index("} else if !live {"), "prefs must be recorded while not live too"
    assert "CyberblockLocomotion.SetStepUp(Cyberblock_EventFlag())" in tick
    system = strip_comments((SCRIPTS / "Cyberblock.reds").read_text(encoding="utf-8"))
    assert "CyberblockLocomotion.Tick(game, player, active)" in system
    assert "CyberblockLocomotion.Clear(game);" in system


# Megaplan Q-S3: the third-person gate names why it changed, and the vehicle camera perspective is probed for Q-D1.
def test_view_gate_logs_its_reason_and_probes_the_vehicle_camera():
    code = strip_comments((SCRIPTS / "View.reds").read_text(encoding="utf-8"))
    assert 's"view: gate \\(reason)"' in code
    assert "GetCameraManager().GetActivePerspective()" in code or "cameras.GetActivePerspective()" in code
    assert 's"view: vehicle camera ' in code
    for reason in ('"braindance"', '"photo mode"', '"menu"', '"vehicle"'):
        assert reason in code, reason


# Every native the scripts declare is registered by the DLL with the same parameter count (cb::natives::add), so a script
# call can never reach a missing function or read its arguments wrongly (the Atlas survey natives included).
DECL = re.compile(r"native func (Cyberblock_\w+)\(([^)]*)\)")
REG = re.compile(r'add\(rtti,\s*"(Cyberblock_\w+)",\s*&\w+,\s*(?:nullptr|"[^"]*")(?:,\s*\{(.*?)\})?\);', re.S)


def test_every_declared_native_is_registered_with_its_parameters():
    declared = {}
    for text in all_code().values():
        for name, params in DECL.findall(text):
            declared[name] = len([p for p in params.split(",") if p.strip()])
    src = Path(__file__).resolve().parent.parent / "cp" / "plugin" / "src"
    registered = {}
    for path in src.glob("*.cpp"):
        for name, params in REG.findall(path.read_text(encoding="utf-8")):
            registered[name] = len(re.findall(r'\{"[^"]+",\s*"\w+"\}', params or ""))
    assert {"Cyberblock_SurveyNext", "Cyberblock_SurveyTourTick", "Cyberblock_UiPumpLive"} <= set(declared)
    for name, count in declared.items():
        assert name in registered, f"{name} is declared but the DLL never registers it"
        assert registered[name] == count, f"{name}: {count} script parameters, {registered[name]} registered"


# Megaplan 2026-10-04 section 1.8 and task 1-H1: the session event. Cyberblock_EventText is declared once, in Natives.reds
# (rule 0.14), Events.reds hands n"session" to the session object before the not-live branch (the cleanup must happen
# whatever the state), and a state that is not host cleans Night City as a link drop does.
def test_event_text_is_declared_once_in_natives_reds():
    code = all_code()
    declared = [(file, text.count("native func Cyberblock_EventText(")) for file, text in code.items()
                if "native func Cyberblock_EventText(" in text]
    assert declared == [("Natives.reds", 1)], declared
    assert "public native func Cyberblock_EventText() -> String;" in code["Natives.reds"]


def test_session_events_are_handled_whatever_the_state():
    events = all_code()["Events.reds"]
    session = events.index('Equals(kind, n"session")')
    assert session < events.index("} else if !live {"), "n\"session\" must be handled before the not-live branch"
    assert "system.Session().OnEvent(game)" in events[session:events.index("} else if !live {")]


def test_leaving_the_host_role_cleans_night_city():
    session = all_code()["Session.reds"]
    on_event = session[session.index("public func OnEvent("):]
    host = on_event.index('if Equals(state, n"host")')
    cleanup = on_event[host:]
    for needed in ("CyberblockCollision.Reset(game);", "CyberblockPuppets.Clear(game);", "CyberblockTether.Clear(game);",
                   'Cyberblock_Log(s"session: cleaned ('):
        assert needed in cleanup, needed
    # a refused reason is a Cyberpunk warning only while Minecraft is not drawn, and once
    assert re.search(r"!system\.InkOn\(\) \|\| !Cyberblock_IsLinked\(\)", cleanup)
    assert "NotEquals(reason, this.shownRefusal)" in cleanup and "this.shownRefusal = reason;" in cleanup


# Megaplan task 6-S1 (fix): a solved Breach sends each daemon content's `action` as its id, so every shipped action (6A's
# data/cyberblock/cyberblock/daemon/*.json, once merged) must be one Daemons.reds runs; an unknown one answers
# "unsupported daemon", and the Phase 0 (h) range refusal is the contract's key.
SHIPPED_DAEMONS = {"open_door", "disable_cameras", "disable_turrets", "reveal_enemies", "overload", "lights_out",
                   "make_friendly", "distract", "traffic_switch"}


def daemon_ids() -> set[str]:
    code = all_code()["Daemons.reds"]
    action = code[code.index("public static func Action("):]
    action = action[:action.index("\n  }\n")]
    return set(re.findall(r'Equals\(id, "(\w+)"\)', action))


def test_every_shipped_daemon_action_runs():
    content = SCRIPTS.parent.parent.parent / "mc" / "src" / "main" / "resources" / "data" / "cyberblock" / "cyberblock" / "daemon"
    shipped = SHIPPED_DAEMONS | {json.loads(p.read_text(encoding="utf-8"))["action"] for p in content.glob("*.json")}
    assert shipped - daemon_ids() == set(), "daemon actions Daemons.reds does not run"
    code = all_code()
    assert 'return "unsupported daemon";' in code["Daemons.reds"]
    assert 'return "cyberblock.hack.out_of_range";' in code["RemoteHacks.reds"]


def test_the_survey_tour_is_blocked_while_not_host():
    survey = all_code()["Survey.reds"]
    blocked = survey[survey.index("private func Blocked("):]
    blocked = blocked[:blocked.index("\n  }\n")]
    assert re.search(r'!system\.Session\(\)\.IsHost\(\) \{\s*return n"session";', blocked)


def test_night_city_is_paused_only_while_the_dll_pump_can_deliver_the_resume():
    # 2026-10-04 live bug: the DLL never found CyberblockUi.Pump, the pause came from CyberblockSystem's tick, which stands
    # still while paused, so the `resume` was never drained and Night City stayed paused.
    code = all_code()
    callers = sorted(name for name, text in code.items() if re.search(r"(?<!Unpause)\bPauseGame\(", text))
    assert callers == ["Menus.reds"], "only Menus.reds may pause Night City"
    menus = code["Menus.reds"]
    case = menus[menus.index('case n"pause":'):]
    case = case[:case.index('case n"resume":')]
    assert len(re.findall(r"(?<!Unpause)\bPauseGame\(", case)) == len(re.findall(r"(?<!Unpause)\bPauseGame\(", menus)) == 1
    assert re.search(r"if Cyberblock_UiPumpLive\(\) \{\s*requests\.PauseGame\(\);", case)
    assert '"ui: not pausing Night City (the DLL pump is not running, a resume could not be delivered)"' in case
