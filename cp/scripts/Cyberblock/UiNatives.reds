// UiNatives.reds — declarations only (plan 2026-10-03-retest3-polish section 1.2, package U). Implemented by
// Cyberblock.dll: Cyberblock_SetPhonePrompt in natives.cpp (package D), every other one in ui_game.cpp (package U).
// Each Cyberblock_Hud*/Subtitle/PhoneCall/Notify/PassMenu builds a section 1.1 message (UTF-8 safe limits, control
// characters stripped, positions converted with the ground alignment) and sends it only while Cyberblock is On and
// Minecraft is linked (Notify: while linked), skipping repeats of the last message of its kind.

// A call rings or an actionable notification shows: T goes to Cyberpunk, not Minecraft chat.
public native func Cyberblock_SetPhonePrompt(open: Bool) -> Void;

// true: Minecraft will open that screen (linked, On, Minecraft not in a screen); sends screen.open.
// name: n"pause" | n"map" | n"journal" | n"phone" | n"inventory" | n"skills" | n"cyberware".
public native func Cyberblock_OpenScreen(name: CName) -> Bool;
// A pass-through menu opened (section 1.11 names: vendor, ripperdoc, stash, wardrobe, crafting, breach, arcade,
// braindance, tutorial, settings, saves, death, loading, other); n"" ends it. Sends screen.pass on change.
public native func Cyberblock_PassMenu(name: CName) -> Void;

// HUD data (sections 1.1 and 1.11).
public native func Cyberblock_HudStats(money: Int32, wanted: Int32, district: String, hour: Int32, minute: Int32, speed: Float) -> Void; // speed km/h, < 0 on foot
// One entry per objective in texts/cur/tot/optional (same length); tracked is the index of the tracked objective (-1
// none); wp is in Cyberpunk space and used only when hasWp.
public native func Cyberblock_HudQuest(title: String, kind: CName, texts: array<String>, cur: array<Int32>, tot: array<Int32>, optional: array<Bool>, tracked: Int32, hasWp: Bool, wp: Vector4) -> Void;
public native func Cyberblock_HudPins(pos: array<Vector4>, kinds: array<CName>, tracked: array<Bool>) -> Void;
// keys: n"F", n"R", n"1", n"2", ... (at most 3 characters); an empty texts array clears the prompt.
public native func Cyberblock_HudPrompt(title: String, texts: array<String>, keys: array<CName>, holds: array<Bool>, inactive: array<Bool>) -> Void;
// hubOf[i] is the index into hubIds/hubTitles of entry i. flags bitmask: 1 inactive, 2 pay, 4 quest, 8 check_ok,
// 16 check_fail, 32 timed. selected -1 = none; secondsLeft < 0 = not timed. Empty hubIds clears the list.
public native func Cyberblock_HudChoices(hubIds: array<Int32>, hubTitles: array<String>, hubOf: array<Int32>, texts: array<String>, flags: array<Int32>, activeHub: Int32, selected: Int32, secondsLeft: Float) -> Void;
public native func Cyberblock_Subtitle(show: Bool, key: String, speaker: String, text: String, seconds: Float) -> Void;
public native func Cyberblock_PhoneCall(phase: CName, contact: String, audio: Bool, rejectable: Bool) -> Void; // phase: incoming | start | active | end
// kind: quest | item | money | xp | level | sms | shard | zone | wanted | warning | save | info (anything else: info)
public native func Cyberblock_Notify(kind: CName, title: String, text: String, seconds: Float) -> Void;

// Night City screen actions from Minecraft (uiact) and the level-triggered pause, drained by CyberblockUi.Pump.
// NextUiAction pops one and makes it current: n"pause", n"resume", n"save", n"loadlast", n"mainmenu", n"cpmenu",
// n"track", n"smsreply", n"fasttravel", n"journalreq", n"phonereq", n"atlasreq"; n"" when empty.
public native func Cyberblock_NextUiAction() -> CName;
public native func Cyberblock_UiActionArg() -> String;
public native func Cyberblock_UiActionHash() -> Uint32;
public native func Cyberblock_UiActionId() -> Int32;

// The answer to journalreq / phonereq / atlasreq (section 1.1 `reply`): Begin, then groups (each followed by its lines)
// and pins, then End sends it (at most 128 groups, 512 lines, 256 pins and 256 KB; the rest is dropped and counted).
public native func Cyberblock_ReplyBegin(kind: CName, id: Int32) -> Void;             // kind: journal | phone | atlas
public native func Cyberblock_ReplyGroup(hash: Uint32, title: String, kind: CName, tracked: Bool, done: Bool) -> Void;
public native func Cyberblock_ReplyLine(hash: Uint32, text: String, state: Int32) -> Void; // belongs to the last group
public native func Cyberblock_ReplyPin(pos: Vector4, kind: CName, name: String, index: Int32) -> Void;
public native func Cyberblock_ReplyEnd() -> Void;
