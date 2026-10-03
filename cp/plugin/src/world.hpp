// Layers 2-6 (world interaction, mobs, flight, tethers, powers), the parts that need no game: what Minecraft sends
// (`block`, `blocks`, `explosion`, `hit`, `ignite`, `teleport`, `lead`, `pull`, `knock`, `effect`, `hack`, `scan`, `perks`,
// `perkfx`, and since re-test 2 `cyberware` and `cyberfx`) is validated, converted to Cyberpunk space and queued for
// redscript, `align` sets the ground alignment, and the messages for Minecraft are built
// from what redscript submits. natives.cpp is the thin game-facing layer over this.
// Wire formats: shared/protocol.md; plans: docs/superpowers/plans/2026-10-02-layer2-world-interaction.md section 1 and
// docs/superpowers/plans/2026-10-03-layers3-6-complete-mod.md sections 1.2, 1.3 and 1.5, and
// docs/superpowers/plans/2026-10-03-retest2-and-cyberware.md sections 1.1-1.3.
//
// Ground alignment (retest2 section 1.1): Minecraft owns k (0..63) and sends it as `align`; positions convert with
// off = k / 64 m both ways (Minecraft y = Cyberpunk z - off). Every position conversion below takes `off` explicitly.
#pragma once
#include "cbmath.hpp"
#include <array>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <map>
#include <mutex>
#include <nlohmann/json.hpp>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace cb::world
{
	// ---- Minecraft -> Cyberpunk -------------------------------------------------------------------------------------

	enum class EventKind
	{
		None,
		Explosion,
		Hit,
		Ignite,
		BlockAdd,
		BlockRemove,
		BlockReset,
		Teleport,
		Lead,
		LeadRelease,
		Pull,
		Knock,
		Effect,
		Hack,
		Scan,
		Perk,
		PerkFx,
		Cyber,   // re-test 2: one installed/removed implant (plan section 1.3)
		CyberFx, // re-test 2: an implant effect starts or ends
	};

	/// The CName redscript sees from Cyberblock_NextEvent: "explosion", "hit", "ignite", "block_add", "block_remove",
	/// "block_reset", "teleport", "lead", "lead_release", "pull", "knock", "effect", "hack", "scan", "perk", "perkfx",
	/// "cyber", "cyberfx"; "" (the empty CName) for None.
	const char *event_name(EventKind kind);

	/// One Minecraft event, already in Cyberpunk space. Which fields each kind fills is the event table of plan
	/// 2026-10-03 section 1.3; the others are zero.
	struct Event
	{
		EventKind kind = EventKind::None;
		Vec3 pos{};          // explosion centre | block AABB min corner | teleport feet | lead anchor | pull toward | knock centre
		uint64_t target = 0; // hit / ignite / lead / pull / hack / scan target: the EntityID hash (scan: 0 = look-at)
		double amount = 0;   // explosion power | hit dmg / 20 (fraction of the target's max health) | ignite seconds |
							 // teleport yaw (Cyberpunk degrees) | lead length | pull strength | knock radius |
							 // effect seconds | perk rank | perkfx seconds | cyber 1 installed, 0 not | cyberfx seconds
		Vec3 vector{};       // hit impulse, m/s
		bool flag = false;   // explosion fire | lead has a fixed anchor | hack chains
		std::string name;    // effect name | hack name | perk id | perkfx id | cyberware id | cyberfx id
		double extra = 0;    // knock strength | effect amplifier
		int32_t id = 0;      // hit: the mob that dealt it (-1 none) | hack, scan: the request id
	};

	/// The ten hack names of plan section 1.5 (`hack.hack`) and the twenty perk ids (`perks[].id`, `perkfx.id`).
	inline constexpr const char *kHackNames[] = {"short_circuit", "overheat",        "reboot_optics", "cripple_movement",
												 "contagion",     "synapse_burnout", "ping",          "memory_wipe",
												 "cyberpsychosis", "device"};
	inline constexpr const char *kPerkIds[] = {"iron_skin",    "gorilla_arms", "painkiller", "juggernaut",   "double_jump",
											   "sandevistan",  "quick_draw",   "dodge",      "tinkerer",     "smart_arrows",
											   "field_medic",  "demolitions",  "extra_ram",  "cheap_hacks",  "chain_hack",
											   "overclock",    "optical_camo", "headhunter", "cold_blood",   "silent_steps"};

	/// Re-test 2 (plan section 1.7): the 17 cyberware ids, in the wire order, for `cyberware.list[].id`, and the three
	/// implant effects of `cyberfx.id`.
	inline constexpr const char *kCyberwareIds[] = {
		"mantis_blades", "gorilla_arms", "monowire",     "projectile_launch_system", "reinforced_tendons", "fortified_ankles",
		"lynx_paws",     "sandevistan",  "berserk",      "cyberdeck",                "kerenzikov",         "optical_camo",
		"subdermal_armor", "kiroshi_optics", "biomonitor", "second_heart",           "ram_upgrade"};
	inline constexpr const char *kCyberFxIds[] = {"sandevistan", "kerenzikov", "berserk"};
	/// Most entries one `cyberware` message may hold (17 ids: a sane bound, the rest is dropped).
	inline constexpr size_t kMaxCyberwareEntries = 32;

	struct Parsed
	{
		enum class Status
		{
			Ignored,  // not one of the handled types (or not an object): another layer's message, nothing to do
			Event,    // `events` is valid
			Rejected, // one of the handled types, but a field is missing or invalid: `reason` says which
		};
		Status status = Status::Ignored;
		/// Status::Event: what the message expands to, in order. Usually one event. `blocks` is a `block_reset` (when it
		/// resets) followed by a `block_add` per cell, `perks` is a `perk` per entry, `cyberware` a `cyber` per entry; a
		/// `blocks` without cells has none.
		std::vector<Event> events;
		/// List entries (`perks` and `cyberware` entries, `blocks` cells) that were invalid and left out; `reason` names
		/// the first one.
		size_t droppedEntries = 0;
		std::string reason;
	};

	/// Validates one message from Minecraft. Limits: coordinates finite with |v| < 3e7 (block coordinates whole
	/// numbers), explosion power 0..20, hit dmg 0..1000, ignite seconds 0..120, ids decimal uint64 strings, and the
	/// layers 3-6 limits of plan section 1.2: lead length 1..32, pull/knock strength 0..50, knock radius 0..16, effect
	/// seconds 0..86400 and amplifier 0..255, perkfx seconds 0..30, at most 64 block cells per `blocks`, hack names and
	/// perk ids from the lists above, effect names [a-z_]{1,32}; re-test 2: `cyberware` entries {id of kCyberwareIds,
	/// on 0|1} (at most 32; a bad one is dropped and counted), `cyberfx` {id of kCyberFxIds, seconds 0..30}. `hit` without `impulse` gets a zero impulse, and `hit`
	/// without `mob` the mob id -1. Positions (block AABBs, explosion, teleport, lead anchor, pull toward, knock centre)
	/// get `+off`; the hit impulse does not. The production caller passes the current level_offset; the 0.0 default is
	/// for tests. Never throws.
	Parsed parse_event(const nlohmann::json &message, double off = 0.0);

	struct QueueStats
	{
		size_t queued = 0;     // events waiting, block changes included
		uint64_t dropped = 0;  // events dropped because the queue (or the block cell table) was full
		uint64_t rejected = 0; // messages parse_event rejected (and list entries it dropped)
	};

	/// Events for redscript, and the "current" event the Cyberblock_Event* natives read. Never blocks: every operation is
	/// a short critical section.
	///
	/// Block changes are state, so they are never lost to a burst: they are kept per cell (the last add or remove of each
	/// cell wins, and a `block_reset` forgets every change before it), so memory is bounded by the cells that changed, at
	/// most kMaxBlockCells. Every other kind is a FIFO of at most kCapacity events; when it is full the oldest transient
	/// event goes first (a perk, cyber, scan or hack only when nothing else is left). next() hands out that FIFO first, then a
	/// pending `block_reset`, then the block changes (by cell, not by arrival: their order does not matter once each cell
	/// has one change).
	class EventQueue
	{
	public:
		static constexpr size_t kCapacity = 256;
		static constexpr size_t kMaxBlockCells = size_t(1) << 20;

		/// Whether the mashup is Active. While it is not, the transient events (explosion, hit, ignite, teleport, lead,
		/// lead_release, pull, knock, effect, perkfx, cyberfx) are discarded (nothing can act on them), and each change of
		/// state discards the ones already queued, so a stale blast never replays on the next activation. `block_add`,
		/// `block_remove`, `block_reset`, `perk`, `cyber`, `scan` and `hack` are always kept: redscript's block registry,
		/// perk ranks and installed cyberware need every one of them, and a scan or a hack must be answered.
		void set_active(bool active);
		void push(const Event &event);
		/// Counts a message parse_event rejected.
		void note_rejected(size_t count = 1);
		/// Pops the oldest event and makes it current; with none queued the current event becomes None.
		EventKind next();
		Event current() const;
		/// Forgets every queued event and the current one (Minecraft reconnected: they belong to the old session).
		void clear();
		QueueStats stats() const;

	private:
		mutable std::mutex m_lock;
		std::deque<Event> m_queue; // every kind but the block ones, oldest first
		bool m_blockReset = false; // a block_reset is pending; it comes out before m_blocks
		std::map<std::array<double, 3>, EventKind> m_blocks; // AABB min corner -> BlockAdd or BlockRemove
		Event m_current;
		bool m_active = false;
		uint64_t m_dropped = 0;
		uint64_t m_rejected = 0;
	};

	/// The plugin's event queue. Never destroyed: the link thread may still use it while the process exits.
	inline EventQueue &events()
	{
		static EventQueue *instance = new EventQueue;
		return *instance;
	}

	// ---- the ground alignment (plan 2026-10-03-retest2 section 1.1) ----

	/// k is a whole number of 1/64 m steps.
	inline constexpr int kLevelSteps = 64;
	/// The alignment k of the attached world: 0..63, from Minecraft's last `align`; 0 before any and after every `hello`.
	int level_k();
	/// off = k / 64 m, exact in float32, double and JSON.
	inline double level_offset(int k)
	{
		return k / 64.0;
	}

	/// The link's observer (runs on the link thread for every message from Minecraft): queues the events of the valid
	/// messages in events() (converted with the current alignment), counts and logs invalid ones (the first 10 only: a
	/// misbehaving peer must not fill the log), forgets the old session's events and resets k to 0 on each `hello`, and
	/// takes k from `align` (an integer 0..63; a bad one is counted and the old k kept; a change is logged).
	void on_minecraft_message(const nlohmann::json &message);
	/// Counts `count` invalid messages or list entries (events().stats().rejected) and logs `reason` for the first 10 only.
	void note_invalid(size_t count, const std::string &reason);

	// ---- Cyberpunk -> Minecraft -------------------------------------------------------------------------------------

	/// The damage kinds the `dmg` message knows; anything else is sent as "other".
	inline constexpr const char *kDamageKinds[] = {"bullet", "explosion", "melee", "fall", "vehicle", "fire", "other"};

	/// Minecraft yaw (degrees, -180..180) of a Cyberpunk yaw (degrees, 0 = facing +Y, counter-clockwise from above, as
	/// Entity.GetWorldYaw reports it): Cyberpunk forward (-sin a, cos a) becomes Minecraft (-sin m, cos m) at m = 180 - a.
	double cp_yaw_to_mc_yaw(double cpYaw);

	/// {"t":"dmg","frac","kind","from"}: `frac` (fraction of V's max health) is clamped to 0..4; `from` (Cyberpunk
	/// space, sent in Minecraft space with `-off`) is null unless `hasFrom` and finite. nullopt when `frac` is not finite.
	std::optional<nlohmann::json> make_dmg(double frac, std::string_view kind, Vec3 fromCp, bool hasFrom, double off);

	/// {"t":"npcs","list":[{"id","p","yaw","kind","hostile"}]} from the arrays redscript submits: Cyberpunk feet
	/// positions (`-off`) and yaws, converted to Minecraft. nullopt unless all five arrays have the same length. An entry
	/// with a non-finite position or yaw is left out.
	std::optional<nlohmann::json> make_npcs(std::span<const uint64_t> ids, std::span<const Vec3> feetCp,
											std::span<const double> yawsCp, std::span<const uint8_t> vehicles,
											std::span<const uint8_t> hostile, double off);

	// ---- layers 3-6 (plan 2026-10-03 section 1.2) ----

	/// The threat levels `scaninfo.threat` knows; anything else is sent as "none".
	inline constexpr const char *kThreatLevels[] = {"none", "low", "medium", "high", "extreme"};
	/// Longest `scaninfo.name`, `scaninfo.faction` and `hackresult.reason`, in UTF-8 bytes.
	inline constexpr size_t kMaxTextBytes = 64;
	/// Most block cells one `pcells` message holds.
	inline constexpr size_t kMaxPcells = 64;

	/// What `cam` says about V besides the pose.
	struct CamFlags
	{
		bool crouched = false; // `cr`: V is crouched
		bool flying = false;   // `fly`: the DLL drive moves V after Steve's glide or launch (natives::flightFollowing)
		bool menu = false;     // `menu`: a Cyberpunk menu is open
		bool air = false;      // `air`: V is in the air (jump, fall, double jump, air dash, ...)
		bool held = false;     // `held`: V is in a vehicle or held by a scene, workspot, braindance or moving elevator
	};

	/// {"t":"cam",...}: the camera and V, every Cyberpunk frame while active (Minecraft convention, so `position`, `feet`
	/// and `look` are already converted, the positions with the offset of `levelK`). `r` is [yaw, pitch, 0]; `cr`, `fly`,
	/// `menu`, `air` and `held` are `flags`; `lv` is `levelK`, the alignment the positions were converted with. Re-test 3
	/// (plan section 1.1): `fp` is `firstPerson` (false in a third-person vehicle camera and while the on-foot third-person
	/// view is applied) and `seat` is `seat` (V sits in a vehicle; `feet` is then V's hips point). Numbers are not rounded.
	nlohmann::json make_cam(int64_t frame, Vec3 position, YawPitch look, double vfov, Vec3 feet, double bodyYaw, CamFlags flags,
							bool firstPerson, bool seat, int levelK);

	/// {"t":"look","d":[dyaw,dpitch]}: raw mouse movement as Minecraft look deltas in degrees (right and down are positive).
	nlohmann::json make_look(double dyaw, double dpitch);

	/// {"t":"mobdmg","id","frac","by"}: a Cyberpunk NPC damaged the puppet of mob `id`. `frac` (fraction of the puppet's
	/// max health) is clamped to 0..4; `by` is the NPC's id as a decimal string, null unless `hasBy`. nullopt when `id`
	/// is negative or `frac` is not finite.
	std::optional<nlohmann::json> make_mobdmg(int32_t id, double frac, uint64_t by, bool hasBy);

	/// {"t":"pcells","c":[x,y,z, ...]}: the Minecraft block cells (`floor` per axis, after converting from Cyberpunk space
	/// with `-off`) of Night City surface points hit by predicted projectiles. Points with a non-finite or out-of-range
	/// coordinate are skipped, repeated cells are sent once, and past 64 cells the rest is dropped. nullopt when no cell
	/// is left.
	std::optional<nlohmann::json> make_pcells(std::span<const Vec3> hitsCp, double off);

	/// {"t":"unlead","target":"<id>"}: Cyberpunk gave up the tether on `target`.
	nlohmann::json make_unlead(uint64_t target);

	/// {"t":"scaninfo",...}: the reply to a `scan`. `name` and `faction` are cut to 64 UTF-8 bytes on a character
	/// boundary, `threat` is one of kThreatLevels (else "none"), `hp` is clamped to 0..1, `feetCp` (Cyberpunk space) is
	/// sent in Minecraft space (`-off`) and `width` x `height` as `size`. A non-finite number is sent as 0. `ok` false
	/// sends the empty reply (empty strings, threat "none", hp 0, no target, no device, zeros). nullopt when `id` is
	/// negative.
	std::optional<nlohmann::json> make_scaninfo(int32_t id, bool ok, uint64_t target, std::string_view name,
												std::string_view faction, std::string_view threat, double hp, bool device,
												Vec3 feetCp, double width, double height, double off);

	// ---- re-test 3 (plan 2026-10-03-retest3-polish sections 1.1 and 1.6): distinct quickhacks ----

	/// The target kinds `hackresult.kind` knows (anything else is left out).
	inline constexpr const char *kHackKinds[] = {"squad", "crowd", "driver", "camera", "traffic", "distract", "device"};
	/// Why `hackspread` touched an extra NPC.
	inline constexpr const char *kSpreadWhy[] = {"chain", "spread", "ping", "frenzy"};
	/// Most `hackspread` messages one hack request id may send; the 17th and later are dropped and counted.
	inline constexpr int kMaxSpreadPerId = 16;
	/// Longest upload a `hackresult` reports, seconds.
	inline constexpr double kMaxUploadSeconds = 30.0;

	/// {"t":"hackresult","id","ok","reason"[,"upload","kind"]}: the reply to a `hack`; `reason` is cut to 64 UTF-8 bytes and
	/// is "" when `ok`. Only when `ok`: `upload` (seconds the upload takes, clamped to 0..30, a non-finite one is 0) and
	/// `kind` (one of kHackKinds; left out when empty or unknown). nullopt when `id` is negative.
	std::optional<nlohmann::json> make_hackresult(int32_t id, bool ok, std::string_view reason, double upload = 0.0,
												  std::string_view kind = {});

	/// {"t":"hackspread","id","target","why"}: hack request `id` also touched NPC `target` (sent as a decimal string).
	/// nullopt when `id` is negative or `why` is not one of kSpreadWhy.
	std::optional<nlohmann::json> make_hackspread(int32_t id, uint64_t target, std::string_view why);

	/// Counts `hackspread` messages per hack request id (kMaxSpreadPerId each). Remembers the last kRemembered ids, so its
	/// memory is bounded. Thread-safe.
	class SpreadCounter
	{
	public:
		static constexpr size_t kRemembered = 64;
		/// Whether one more spread may be sent for `id`; false (and counted as dropped) past kMaxSpreadPerId.
		bool allow(int32_t id);
		/// Spreads refused so far.
		uint64_t dropped() const;

	private:
		mutable std::mutex m_lock;
		std::deque<std::pair<int32_t, int>> m_counts; // id -> spreads sent, oldest id first
		uint64_t m_dropped = 0;
	};

	/// {"t":"killed","target","kind","hostile"}: an NPC or vehicle that V damaged died or was destroyed.
	nlohmann::json make_killed(uint64_t target, bool vehicle, bool hostile);
}
