// SurveyNatives.reds — declarations only; implemented by Cyberblock.dll (survey_game.cpp). The Atlas survey: long down
// rays that map the highest Night City surface in 2-m cells for Minecraft's Atlas and radar (`terrain`), and the
// automatic survey tour (`/cyberblock survey`). Positions are Cyberpunk space; the DLL converts with the alignment.

// The next column (x, y) to cast a survey ray at, W 1; W 0: none this tick. Walking: around V (nearest first, nothing
// twice within 5 minutes). Never during a tour, and not in a vehicle once the DLL casts its own rays: the DLL samples
// those itself every frame.
public native func Cyberblock_SurveyNext(feet: Vector4) -> Vector4;
// One entry per column handed out: x, y the column, z the surface hit, W 0 miss, 1 ground or building, 2 water.
public native func Cyberblock_SubmitSurvey(hits: array<Vector4>) -> Void;

// The tour, every tick: blocked n"" when V may tour, else why not (n"off", n"vehicle", n"held", n"combat", n"flight",
// n"unlinked", n"dead"); menu: a Cyberpunk menu is open (the tour waits). Returns what to do now: n"" nothing,
// n"bounds" (call Cyberblock_SurveyTourArea), n"begin" (hide V, god mode, save lock), n"end" (put V back at
// Cyberblock_SurveyTourHome and undo n"begin"). Meanwhile the DLL holds V over each stop and casts the rays every frame.
public native func Cyberblock_SurveyTourTick(feet: Vector4, yaw: Float, blocked: CName, menu: Bool) -> CName;
// x, y of every map pin and fast-travel point; W 0 a fast-travel point outside the Badlands, 1 one in them, 2 a map pin.
public native func Cyberblock_SurveyTourArea(pins: array<Vector4>) -> Void;
public native func Cyberblock_SurveyTourHome() -> Vector4;   // V's feet when the tour started, W V's yaw (degrees)
public native func Cyberblock_SurveyTourReset() -> Void;     // the session ended: forget the tour
