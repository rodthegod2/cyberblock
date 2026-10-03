// Layer 2 (world interaction), the parts that need no game: what Minecraft sends (`block`, `explosion`, `hit`,
// `ignite`) is validated, converted to Cyberpunk space and queued for redscript, and the `dmg` and `npcs` messages for
// Minecraft are built from what redscript submits. natives.cpp is the thin game-facing layer over this.
// Wire formats: shared/protocol.md; plan: docs/superpowers/plans/2026-10-02-layer2-world-interaction.md section 1.
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
	};

	/// The CName redscript sees from Cyberblock_NextEvent: "explosion", "hit", "ignite", "block_add", "block_remove";
	/// "" (the empty CName) for None.
	const char *event_name(EventKind kind);

	/// One Minecraft event, already in Cyberpunk space.
	struct Event
	{
		EventKind kind = EventKind::None;
		Vec3 pos{};          // explosion centre | block AABB min corner
		uint64_t target = 0; // hit / ignite target: the EntityID hash
		double amount = 0;   // explosion power | hit dmg / 20 (fraction of the target's max health) | ignite seconds
		Vec3 vector{};       // hit impulse, m/s
		bool flag = false;   // explosion fire
	};

	struct Parsed
	{
		enum class Status
		{
			Ignored,  // not one of the four event types (or not an object): another layer's message, nothing to do
			Event,    // `event` is valid
			Rejected, // one of the four types, but a field is missing or invalid: `reason` says which
		};
		Status status = Status::Ignored;
		Event event;
		std::string reason;
	};

	/// Validates one message from Minecraft. Limits: coordinates finite with |v| < 3e7 (block coordinates whole
	/// numbers), explosion power 0..20, hit dmg 0..1000, ignite seconds 0..120, ids decimal uint64 strings.
	/// `hit` without `impulse` gets a zero impulse. Never throws.
	Parsed parse_event(const nlohmann::json &message);

	struct QueueStats
	{
		size_t queued = 0;
		uint64_t dropped = 0;  // oldest events dropped because the queue was full
		uint64_t rejected = 0; // messages parse_event rejected
	};

	/// Events for redscript: a mutex-guarded FIFO of at most kCapacity events (the oldest is dropped), and the "current"
	/// event the Cyberblock_Event* natives read. Never blocks: every operation is a short critical section.
	class EventQueue
	{
	public:
		static constexpr size_t kCapacity = 256;

		/// Whether the mashup is Active. While it is not, explosion / hit / ignite events are discarded (nothing can act
		/// on them) and each change of state discards the ones already queued, so a stale blast never replays on the
		/// next activation. Block events are always kept: redscript's block registry needs every one of them.
		void set_active(bool active);
		void push(const Event &event);
		/// Counts a message parse_event rejected.
		void note_rejected();
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

	/// The link's observer (runs on the link thread for every message from Minecraft): queues valid `block`,
	/// `explosion`, `hit` and `ignite` events in events(), counts and logs invalid ones (the first 10 only: a
	/// misbehaving peer must not fill the log) and forgets the old session's events on each `hello`.
	void on_minecraft_message(const nlohmann::json &message);

	// ---- Cyberpunk -> Minecraft -------------------------------------------------------------------------------------

	/// The damage kinds the `dmg` message knows; anything else is sent as "other".
	inline constexpr const char *kDamageKinds[] = {"bullet", "explosion", "melee", "fall", "vehicle", "fire", "other"};

	/// Minecraft yaw (degrees, -180..180) of a Cyberpunk yaw (degrees, 0 = facing +Y, counter-clockwise from above, as
	/// Entity.GetWorldYaw reports): Cyberpunk forward (-sin a, cos a) becomes Minecraft (-sin m, cos m) at m = 180 - a.
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
