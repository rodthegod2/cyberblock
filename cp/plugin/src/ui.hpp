// The Cyberpunk UI bridge (plan 2026-10-03-retest3-polish sections 1.1-1.3 and 1.11, package U): Cyberpunk's HUD,
// prompts, choices, subtitles, calls, notifications, menus and journal/phone/atlas answers go to Minecraft, which
// draws all of the UI; Minecraft's Night City screens send `uiact` actions back, and its pause screen pauses Night City
// (status.pause, level-triggered).
//
// Two halves:
// - ui.cpp (pure, no RED4ext, built into cb_tests): the message builders with the section 1.1 limits, the send gates
//   (deduplication and rate limits), the reply builder and the UI action queue with its pause rules;
// - ui_game.cpp (game-facing): the redscript natives of section 1.2 (declared in UiNatives.reds) and the pump that
//   calls the script CyberblockUi.Pump on the game thread.
// D wires the four entry points below into natives.cpp and main.cpp (section 1.3).
#pragma once
#include "cbmath.hpp"
#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>
#include <nlohmann/json.hpp>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace RED4ext
{
	struct CRTTISystem;
}

namespace cb::ui
{
	// ---- game-facing entry points (ui_game.cpp; section 1.3) ---------------------------------------------------------

	/// Registers the package U natives; D calls it at the end of natives::post_register_types.
	void register_natives(RED4ext::CRTTISystem *rtti);
	/// D's link handler (link thread), after cb::input: queues `uiact`, and a `hello` unpauses.
	void on_minecraft_message(const nlohmann::json &m);
	/// D's on_running_update (game thread), after cb::input::tick(): the pause edge and CyberblockUi.Pump, ≤ 30 Hz, also
	/// while the game is paused.
	void pump();
	/// D's link-drop callback (link thread): unpauses.
	void on_link_dropped();

	// ---- limits (section 1.1) ----------------------------------------------------------------------------------------

	inline constexpr size_t kTitleBytes = 128;    // quest/prompt/choice hub/notify titles, prompt entries, reply titles
	inline constexpr size_t kTextBytes = 256;     // objectives, choices, subtitles, notify texts, reply lines
	inline constexpr size_t kNameBytes = 64;      // district, speaker, contact, subtitle key, pin names, uiact arg
	inline constexpr size_t kKeyBytes = 3;        // prompt key labels ("F", "R", "1", ...)
	inline constexpr size_t kMaxObjectives = 12;  // quest.obj
	inline constexpr size_t kMaxPins = 32;        // pins.list
	inline constexpr size_t kMaxPromptEntries = 6;
	inline constexpr size_t kMaxChoiceHubs = 4;
	inline constexpr size_t kMaxChoiceEntries = 12; // in all hubs together
	inline constexpr double kMaxSeconds = 30.0;   // sub.secs, notify.secs

	// ---- names (section 1.1) -----------------------------------------------------------------------------------------

	inline constexpr const char *kQuestKinds[] = {"main", "side", "gig", "other"}; // unknown -> other
	inline constexpr const char *kPinKinds[] = {"quest", "vendor", "fasttravel", "gig", "police",
												"ripperdoc", "apartment", "car", "other"}; // unknown -> other
	/// Choice flags in bit order: bit 0 (1) inactive, 1 (2) pay, 2 (4) quest, 3 (8) check_ok, 4 (16) check_fail, 5 (32) timed.
	inline constexpr const char *kChoiceFlags[] = {"inactive", "pay", "quest", "check_ok", "check_fail", "timed"};
	inline constexpr const char *kCallPhases[] = {"incoming", "start", "active", "end"}; // unknown: invalid
	inline constexpr const char *kNotifyKinds[] = {"quest", "item", "money", "xp", "level", "sms",
												   "shard", "zone", "wanted", "warning", "save", "info"}; // unknown -> info
	inline constexpr const char *kScreens[] = {"pause", "map", "journal", "phone", "inventory", "skills", "cyberware"};
	inline constexpr const char *kPassNames[] = {"vendor",   "ripperdoc", "stash",    "wardrobe", "crafting",
												 "breach",   "arcade",    "braindance", "tutorial", "settings",
												 "saves",    "death",     "loading",  "other"};
	inline constexpr const char *kReplyKinds[] = {"journal", "phone", "atlas"};
	inline constexpr const char *kGroupKinds[] = {"main", "side", "gig", "other", "sms"}; // unknown -> other
	/// `uiact.a` (Minecraft -> Cyberpunk).
	inline constexpr const char *kUiActs[] = {"save",      "loadlast", "mainmenu",   "cpmenu",    "track",
											  "smsreply",  "fasttravel", "journalreq", "phonereq", "atlasreq"};

	/// The entry of `names` equal to `name`, or `fallback` when it is none of them.
	template<size_t N>
	std::string_view known_or(std::string_view name, const char *const (&names)[N], std::string_view fallback)
	{
		for (const char *candidate : names)
			if (name == candidate)
				return candidate;
		return fallback;
	}

	/// Text for the wire: invalid UTF-8 bytes and control characters (C0, DEL, C1) removed (tab, CR and LF become a
	/// space), surrounding spaces trimmed, then cut to `maxBytes` without splitting a character.
	std::string clean_text(std::string_view text, size_t maxBytes);

	// ---- Cyberpunk -> Minecraft builders -----------------------------------------------------------------------------

	/// {"t":"stats","money","wanted","district","time":[h,m],"speed"}: money ≥ 0, wanted 0..5, h 0..23, m 0..59 (all
	/// clamped), speed km/h rounded to 0.1 (≤ 1000), or -1 on foot (any negative or non-finite speed).
	nlohmann::json make_stats(int64_t money, int64_t wanted, std::string_view district, int64_t hour, int64_t minute,
							  double speedKmh);

	struct Objective
	{
		std::string text;
		int64_t cur = 0;
		int64_t tot = 0; // 0: no counter
		bool optional = false;
		bool on = false; // the tracked objective
	};
	/// {"t":"quest","title","kind","obj":[{text,cur,tot,opt,on}],"wp"}: at most 12 objectives; counters clamped to ≥ 0;
	/// `wpCp` (Cyberpunk space) converted with `off`, null when absent or not finite.
	nlohmann::json make_quest(std::string_view title, std::string_view kind, std::span<const Objective> objectives,
							  std::optional<Vec3> wpCp, double off);

	struct Pin
	{
		Vec3 posCp{};
		std::string kind;
		bool tracked = false;
	};
	/// {"t":"pins","list":[{p,kind,tr}]}: at most 32 (the script keeps those within 150 m); non-finite ones are left out.
	nlohmann::json make_pins(std::span<const Pin> pins, double off);

	struct PromptEntry
	{
		std::string text;
		std::string key;
		bool hold = false;
		bool inactive = false;
	};
	/// {"t":"prompt","title","list":[{text,key,hold,inactive}]}: at most 6; entries without text are left out; an empty
	/// list clears the prompt.
	nlohmann::json make_prompt(std::string_view title, std::span<const PromptEntry> entries);

	struct ChoiceHub
	{
		int32_t id = 0;
		std::string title;
	};
	struct ChoiceEntry
	{
		int32_t hub = 0; // index into the hubs
		std::string text;
		int32_t flags = 0; // kChoiceFlags bits
	};
	/// {"t":"choices","hubs":[{id,title,list:[{text,f:[...]}]}],"active","sel","left"}: at most 4 hubs and 12 entries in
	/// all (the rest dropped); an entry naming a missing hub is dropped; unknown flag bits are dropped; `sel` < -1
	/// becomes -1; `left` is seconds (≤ 3600) or -1 when negative or not finite. Empty `hubs` clears the list.
	nlohmann::json make_choices(std::span<const ChoiceHub> hubs, std::span<const ChoiceEntry> entries, int32_t activeHub,
								int32_t selected, double secondsLeft);

	/// {"t":"sub","show","key","who","text","secs"}: secs clamped to 0..30 (0 when not finite).
	nlohmann::json make_subtitle(bool show, std::string_view key, std::string_view who, std::string_view text, double secs);
	/// {"t":"call","phase","who","audio","reject"}; nullopt for an unknown phase.
	std::optional<nlohmann::json> make_call(std::string_view phase, std::string_view who, bool audio, bool reject);
	/// {"t":"notify","kind","title","text","secs"}: an unknown kind becomes "info"; secs clamped to 0..30.
	nlohmann::json make_notify(std::string_view kind, std::string_view title, std::string_view text, double secs);
	/// {"t":"screen","open":name}; nullopt unless `name` is one of kScreens.
	std::optional<nlohmann::json> make_screen_open(std::string_view name);
	/// {"t":"screen","pass":name}; "" ends the pass-through; nullopt for any other name not in kPassNames.
	std::optional<nlohmann::json> make_screen_pass(std::string_view name);

	/// The `reply` message, built entry by entry (Cyberblock_ReplyBegin/Group/Line/Pin/End). At most 128 groups, 512
	/// lines and 1024 pins, and the whole message at most 256 KB: anything past a limit is dropped and counted. A line
	/// belongs to the last group (dropped with it, or when there is none).
	class ReplyBuilder
	{
	public:
		static constexpr size_t kMaxGroups = 128;
		static constexpr size_t kMaxLines = 512;
		static constexpr size_t kMaxPins = 1024; // Atlas extension (was 256): every map pin of Night City fits
		static constexpr size_t kMaxBytes = 256 * 1024;

		/// Starts a reply (forgetting an unfinished one); false (and inactive) for an unknown kind.
		bool begin(std::string_view kind, int32_t id);
		void group(uint32_t hash, std::string_view title, std::string_view kind, bool tracked, bool done);
		/// state: 0 active / from the contact, 1 done / from V, 2 failed / a reply V can choose (clamped to 0..2).
		void line(uint32_t hash, std::string_view text, int32_t state);
		/// `posCp` in Cyberpunk space, converted with `off`; `index` is the fast-travel index (-1 none); a non-empty
		/// `district` (at most kNameBytes) is sent as `d` (Atlas extension: district labels).
		void pin(Vec3 posCp, std::string_view kind, std::string_view name, int32_t index, double off, std::string_view district = {});

		struct Result
		{
			nlohmann::json message;
			size_t groups = 0;
			size_t lines = 0;
			size_t pins = 0;
			size_t bytes = 0;   // the size of message.dump()
			size_t dropped = 0; // entries left out by the limits
		};
		/// The finished reply; nullopt when none was begun. The builder is inactive afterwards.
		std::optional<Result> end();
		bool active() const { return m_active; }

	private:
		bool fits(size_t bytes) const;

		bool m_active = false;
		std::string m_kind;
		int32_t m_id = 0;
		nlohmann::json m_groups;
		nlohmann::json m_pins;
		bool m_lastGroupKept = false;
		size_t m_lines = 0;
		size_t m_bytes = 0;
		size_t m_dropped = 0;
	};

	/// Deduplication and rate limit of one message type. `full` is the message text, `urgent` the part whose change is
	/// sent at once (everything else waits until `minInterval` seconds passed since the last send). An unchanged message
	/// is never sent twice. reset() forgets the last message (Minecraft reconnected).
	class Gate
	{
	public:
		bool admit(const std::string &full, const std::string &urgent, double now, double minInterval);
		/// For events: the same text again within `window` seconds is a duplicate; anything else passes.
		bool admit_event(const std::string &full, double now, double window);
		void reset();

	private:
		bool m_any = false;
		std::string m_full;
		std::string m_urgent;
		double m_time = 0.0;
	};

	// ---- Minecraft -> Cyberpunk: UI actions and the pause ------------------------------------------------------------

	struct UiAction
	{
		std::string a; // "pause", "resume" or one of kUiActs
		std::string arg;
		uint32_t hash = 0;
		int32_t id = 0;
	};

	/// Validates a `uiact`: `a` one of kUiActs, optional `arg` string ≤ 64 bytes, `hash` a whole number 0..2^32-1, `id`
	/// a whole number in Int32. nullopt (with `why`) when invalid.
	std::optional<UiAction> parse_uiact(const nlohmann::json &m, std::string *why = nullptr);

	/// The queue the script drains through Cyberblock_NextUiAction (thread-safe: the link thread pushes, the game thread
	/// pops). Pause authority is level-triggered (section 1.1): set_pause(want) with want = status.pause && linked && On
	/// queues `pause` or `resume` on each change; reset_pause() (link drop, Off/Lost, `hello`) forgets the pause and
	/// queues a `resume`. Actions other than `resume` are dropped while Cyberblock is not On, both when they arrive and
	/// when they are popped. At most kMax actions wait; past that a new one is dropped (a pause or resume evicts the
	/// oldest other action instead).
	class UiActions
	{
	public:
		static constexpr size_t kMax = 32;

		/// Queues a valid action while `on`; false when it was dropped (invalid or not On).
		bool push(const UiAction &action, bool on);
		void set_pause(bool want);
		/// Returns whether Night City was paused by Minecraft until now.
		bool reset_pause();
		std::optional<UiAction> pop(bool on);
		bool paused() const;
		bool empty() const;
		size_t size() const;
		uint64_t dropped() const;

	private:
		void enqueue(UiAction action); // with m_lock held

		mutable std::mutex m_lock;
		std::deque<UiAction> m_queue;
		bool m_paused = false;
		uint64_t m_dropped = 0;
	};
}
