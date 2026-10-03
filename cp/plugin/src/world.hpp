// Layers 2-6 (world interaction, mobs, flight, tethers, powers), the parts that need no game: what Minecraft sends
// (`block`, `blocks`, `explosion`, `hit`, `ignite`, `teleport`, `lead`, `pull`, `knock`, `effect`, `hack`, `scan`, `perks`,
// `perkfx`) is validated, converted to Cyberpunk space and queued for redscript, and the messages for Minecraft are built
// from what redscript submits. natives.cpp is the thin game-facing layer over this.
// Wire formats: shared/protocol.md; plans: docs/superpowers/plans/2026-10-02-layer2-world-interaction.md section 1 and
// docs/superpowers/plans/2026-10-03-layers3-6-complete-mod.md sections 1.2, 1.3 and 1.5.
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
	};

	/// The CName redscript sees from Cyberblock_NextEvent: "explosion", "hit", "ignite", "block_add", "block_remove",
	/// "block_reset", "teleport", "lead", "lead_release", "pull", "knock", "effect", "hack", "scan", "perk", "perkfx";
	/// "" (the empty CName) for None.
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
							 // effect seconds | perk rank | perkfx seconds
		Vec3 vector{};       // hit impulse, m/s
		bool flag = false;   // explosion fire | lead has a fixed anchor | hack chains
		std::string name;    // effect name | hack name | perk id | perkfx id
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
		/// resets) followed by a `block_add` per cell, `perks` is a `perk` per entry; a `blocks` without cells has none.
		std::vector<Event> events;
		/// List entries (`perks` entries, `blocks` cells) that were invalid and left out; `reason` names the first one.
		size_t droppedEntries = 0;
		std::string reason;
	};

	/// Validates one message from Minecraft. Limits: coordinates finite with |v| < 3e7 (block coordinates whole
	/// numbers), explosion power 0..20, hit dmg 0..1000, ignite seconds 0..120, ids decimal uint64 strings, and the
	/// layers 3-6 limits of plan section 1.2: lead length 1..32, pull/knock strength 0..50, knock radius 0..16, effect
	/// seconds 0..86400 and amplifier 0..255, perkfx seconds 0..30, at most 64 block cells per `blocks`, hack names and
	/// perk ids from the lists above, effect names [a-z_]{1,32}. `hit` without `impulse` gets a zero impulse, and `hit`
	/// without `mob` the mob id -1. Never throws.
	Parsed parse_event(const nlohmann::json &message);

	struct QueueStats
	{
		size_t queued = 0;
		uint64_t dropped = 0;  // oldest events dropped because the queue was full
		uint64_t rejected = 0; // messages parse_event rejected (and list entries it dropped)
	};

	/// Events for redscript: a mutex-guarded FIFO of at most kCapacity events (the oldest is dropped), and the "current"
	/// event the Cyberblock_Event* natives read. Never blocks: every operation is a short critical section.
	class EventQueue
	{
	public:
		static constexpr size_t kCapacity = 256;

		/// Whether the mashup is Active. While it is not, the transient events (explosion, hit, ignite, teleport, lead,
		/// lead_release, pull, knock, effect, perkfx) are discarded (nothing can act on them), and each change of state
		/// discards the ones already queued, so a stale blast never replays on the next activation. `block_add`,
		/// `block_remove`, `block_reset`, `perk`, `scan` and `hack` are always kept: redscript's block registry and perk
		/// ranks need every one of them, and a scan or a hack must be answered.
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
		std::deque<Event> m_queue;
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

	/// The link's observer (runs on the link thread for every message from Minecraft): queues the events of the valid
	/// messages in events(), counts and logs invalid ones (the first 10 only: a misbehaving peer must not fill the log)
	/// and forgets the old session's events on each `hello`.
	void on_minecraft_message(const nlohmann::json &message);

	// ---- Cyberpunk -> Minecraft -------------------------------------------------------------------------------------

	/// The damage kinds the `dmg` message knows; anything else is sent as "other".
	inline constexpr const char *kDamageKinds[] = {"bullet", "explosion", "melee", "fall", "vehicle", "fire", "other"};

	/// Minecraft yaw (degrees, -180..180) of a Cyberpunk yaw (degrees, 0 = facing +Y, counter-clockwise from above, as
	/// Entity.GetWorldYaw reports it): Cyberpunk forward (-sin a, cos a) becomes Minecraft (-sin m, cos m) at m = 180 - a.
	double cp_yaw_to_mc_yaw(double cpYaw);

	/// {"t":"dmg","frac","kind","from"}: `frac` (fraction of V's max health) is clamped to 0..4; `from` (Cyberpunk
	/// space, sent in Minecraft space) is null unless `hasFrom` and finite. nullopt when `frac` is not finite.
	std::optional<nlohmann::json> make_dmg(double frac, std::string_view kind, Vec3 fromCp, bool hasFrom);

	/// {"t":"npcs","list":[{"id","p","yaw","kind","hostile"}]} from the arrays redscript submits: Cyberpunk feet
	/// positions and yaws, converted to Minecraft. nullopt unless all five arrays have the same length. An entry with a
	/// non-finite position or yaw is left out.
	std::optional<nlohmann::json> make_npcs(std::span<const uint64_t> ids, std::span<const Vec3> feetCp,
											std::span<const double> yawsCp, std::span<const uint8_t> vehicles,
											std::span<const uint8_t> hostile);
}
