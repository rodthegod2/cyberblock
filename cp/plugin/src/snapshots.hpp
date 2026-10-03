// Layers 3-4: the latest `mobs`, `flight` and `proj` messages from Minecraft, kept as state that redscript reads through
// natives (plan 2026-10-03 section 1.3) instead of being queued as events. Everything that needs no game lives here;
// natives.cpp is the thin layer over it. Time is passed in, so the tests do not sleep.
// Wire formats: shared/protocol.md "Layers 3-6" (plan sections 1.2 and 1.3).
#pragma once
#include "cbmath.hpp"
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <nlohmann/json.hpp>
#include <optional>
#include <string>
#include <vector>

namespace cb::snap
{
	using Clock = std::chrono::steady_clock;

	// ---- the messages, validated and converted to Cyberpunk space ---------------------------------------------------

	/// One hostile goal-driven mob near Steve.
	struct Mob
	{
		int32_t id = 0;     // Minecraft network entity id
		std::string kind;   // "zombie", "skeleton", ...
		Vec3 feet{};        // Cyberpunk space
		double yaw = 0;     // Cyberpunk degrees
		double width = 0, height = 0; // m
		double hp = 0, maxHp = 0;
	};

	/// One projectile Steve owns.
	struct Projectile
	{
		int32_t id = 0;
		Vec3 pos{};      // Cyberpunk space
		Vec3 velocity{}; // m/s, Cyberpunk space
		double gravity = 0; // m/s^2 along -Z
	};

	/// A `flight` message.
	struct FlightSample
	{
		bool active = false;
		Vec3 pos{};          // feet, Cyberpunk space (the landing spot when not active)
		Vec3 velocity{};     // m/s, Cyberpunk space
		double yaw = 0;      // Cyberpunk degrees
		double pitch = 0;    // Minecraft degrees, positive = down
	};

	template<typename T>
	struct ListParse
	{
		bool valid = false;      // false: the message itself is malformed (`reason` says how) and nothing is kept
		std::vector<T> items;    // the good entries, in order
		size_t dropped = 0;      // entries that were invalid or past the limit (`reason` names the first)
		std::string reason;
	};

	inline constexpr size_t kMaxMobs = 12;
	inline constexpr size_t kMaxProjectiles = 16;

	// The parsers take the ground alignment `off` (plan 2026-10-03-retest2 section 1.1): positions get `+off`, velocities
	// never do. The production caller (on_minecraft_message) passes the current level_offset; 0.0 is for tests.

	/// {"t":"mobs","list":[{id, kind, p, yaw, w, h, hp, maxHp}]}: at most 12 entries. id is an int >= 0, kind matches
	/// [a-z0-9_]{1,32}, p is finite with |c| < 3e7, yaw is finite, w and h are 0..16, hp and maxHp 0..2048. A bad entry or
	/// one past the 12th is dropped and counted; a `list` that is not an array makes the message invalid.
	ListParse<Mob> parse_mobs(const nlohmann::json &message, double off = 0.0);

	/// {"t":"proj","list":[{id, p, v, g}]}: at most 16 entries. id is an int >= 0, p and v are finite (p with |c| < 3e7, v
	/// with |v| <= 200 m/s), g is 0..40 m/s^2. Dropping works as in parse_mobs.
	ListParse<Projectile> parse_proj(const nlohmann::json &message, double off = 0.0);

	/// {"t":"flight","active","p","v","yaw","pitch"}: p is always needed; v, yaw and pitch only while `active` (|v| <= 200
	/// m/s, yaw and pitch finite; the pitch is clamped to -90..90). nullopt when the message is malformed, with `reason`.
	std::optional<FlightSample> parse_flight(const nlohmann::json &message, std::string &reason, double off = 0.0);

	// ---- the state -------------------------------------------------------------------------------------------------

	/// The latest list of a message type and the snapshot of it the natives index into. Thread-safe.
	template<typename T>
	class ListState
	{
	public:
		/// A list older than `maxAge` counts as empty.
		explicit ListState(Clock::duration maxAge) : m_maxAge(maxAge) {}

		void set(std::vector<T> list, Clock::time_point now)
		{
			std::lock_guard<std::mutex> lock(m_lock);
			m_latest = std::move(list);
			m_receivedAt = now;
		}

		/// Forgets the latest list (the snapshot already taken stays valid until the next snapshot()).
		void clear()
		{
			std::lock_guard<std::mutex> lock(m_lock);
			m_latest.clear();
		}

		/// Copies the latest list into the snapshot at(i) reads, and returns its size. Empty when the latest list is
		/// older than `maxAge`.
		size_t snapshot(Clock::time_point now)
		{
			std::lock_guard<std::mutex> lock(m_lock);
			m_snapshot = now - m_receivedAt <= m_maxAge ? m_latest : std::vector<T>{};
			return m_snapshot.size();
		}

		/// Entry `index` of the snapshot; nullopt out of range (also before any snapshot()).
		std::optional<T> at(int index) const
		{
			std::lock_guard<std::mutex> lock(m_lock);
			if (index < 0 || size_t(index) >= m_snapshot.size())
				return std::nullopt;
			return m_snapshot[size_t(index)];
		}

	private:
		mutable std::mutex m_lock;
		const Clock::duration m_maxAge;
		std::vector<T> m_latest;
		Clock::time_point m_receivedAt{};
		std::vector<T> m_snapshot;
	};

	/// Minecraft's elytra flight as the last `flight` sample. Thread-safe. Every query takes the current time: a sample
	/// older than kMaxAge means Minecraft vanished mid-flight and counts as `active: false`.
	class Flight
	{
	public:
		static constexpr auto kMaxAge = std::chrono::milliseconds(500);
		/// Minecraft's physics move Steve once per 50 ms tick: guessing past one tick of travel only overshoots, and V snaps
		/// back when the next sample lands.
		static constexpr auto kMaxExtrapolation = std::chrono::milliseconds(50);

		void update(const FlightSample &sample, Clock::time_point now);
		/// A new Minecraft session or a dropped link: a flight in progress ends where it was last seen.
		void reset(Clock::time_point now);

		bool active(Clock::time_point now);
		/// +1 on every change of `active`: every start and every landing (also a stale or reset end).
		int32_t seq(Clock::time_point now);
		/// The flight ended because its sample went stale (kMaxAge without a new one), not by a landing message; false again
		/// after any new sample and after reset().
		bool timed_out(Clock::time_point now);
		/// Feet, Cyberpunk space: p + v * min(age, 0.05 s) while active, else the landing spot.
		Vec3 pos(Clock::time_point now);
		Vec3 velocity(Clock::time_point now);
		double yaw(Clock::time_point now);
		double pitch(Clock::time_point now);

	private:
		/// Locked: ends a flight whose sample is too old.
		void expire(Clock::time_point now);
		/// Locked: the position `sample` extrapolates to at `now`.
		Vec3 extrapolated(Clock::time_point now) const;

		std::mutex m_lock;
		FlightSample m_sample;
		Clock::time_point m_receivedAt{};
		int32_t m_seq = 0;
		bool m_timedOut = false;
	};

	inline ListState<Mob> &mobs()
	{
		static ListState<Mob> *instance = new ListState<Mob>(std::chrono::seconds(2));
		return *instance;
	}

	inline ListState<Projectile> &projectiles()
	{
		static ListState<Projectile> *instance = new ListState<Projectile>(std::chrono::milliseconds(500));
		return *instance;
	}

	inline Flight &flight()
	{
		static Flight *instance = new Flight;
		return *instance;
	}

	/// The link's observer for these types (link thread): keeps valid `mobs`, `flight` and `proj` messages, counts and
	/// logs invalid ones like world::on_minecraft_message, and clears all three on `hello`. A `hello` and a landing
	/// (`flight` with active false) also end V's following (natives::flightFollowing).
	void on_minecraft_message(const nlohmann::json &message);
	/// The link dropped (link thread): the mobs, flight and projectiles of the old session are gone, and V's following.
	void on_link_dropped();
}
