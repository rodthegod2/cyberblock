#include "snapshots.hpp"
#include "natives.hpp"
#include "wire.hpp"
#include "world.hpp"
#include <algorithm>
#include <cmath>
#include <limits>

namespace
{
	using nlohmann::json;
	using namespace cb;
	using wire::Bad;

	constexpr double kMaxId = std::numeric_limits<int32_t>::max(); // scripts get ids as Int32
	constexpr double kMaxMobSize = 16.0;                             // w and h, m
	constexpr double kMaxMobHealth = 2048.0;                         // hp and maxHp
	constexpr double kMaxSpeed = 200.0;                              // flight.v and proj.v, m/s
	constexpr double kMaxGravity = 40.0;                             // proj.g, m/s^2
	constexpr size_t kMaxKindLength = 32;
	constexpr double kMaxPitch = 90.0;

	/// The array under "list", or Bad.
	const json &list_of(const json &message)
	{
		const auto list = message.find("list");
		if (list == message.end() || !list->is_array())
			throw Bad{"list is missing or not an array"};
		return *list;
	}

	/// A [x, y, z] of Minecraft m/s with |v| <= 200, in Cyberpunk space.
	Vec3 velocity(const json &m, const char *key)
	{
		const Vec3 v = wire::triple(m, key, false);
		if (std::hypot(v.x, v.y, v.z) > kMaxSpeed)
			throw Bad{std::string(key) + " is faster than 200 m/s"};
		return mc_vel_to_cp(v.x, v.y, v.z);
	}

	snap::Mob parse_mob(const json &entry)
	{
		snap::Mob mob;
		mob.id = wire::integer(entry, "id", 0.0, kMaxId);
		mob.kind = wire::word(entry, "kind", kMaxKindLength, true);
		mob.feet = wire::cp_triple(entry, "p");
		mob.yaw = mc_yaw_to_cp_yaw(wire::number(entry, "yaw", -wire::kMaxCoord, wire::kMaxCoord));
		mob.width = wire::number(entry, "w", 0.0, kMaxMobSize);
		mob.height = wire::number(entry, "h", 0.0, kMaxMobSize);
		mob.hp = wire::number(entry, "hp", 0.0, kMaxMobHealth);
		mob.maxHp = wire::number(entry, "maxHp", 0.0, kMaxMobHealth);
		return mob;
	}

	snap::Projectile parse_projectile(const json &entry)
	{
		snap::Projectile p;
		p.id = wire::integer(entry, "id", 0.0, kMaxId);
		p.pos = wire::cp_triple(entry, "p");
		p.velocity = velocity(entry, "v");
		p.gravity = wire::number(entry, "g", 0.0, kMaxGravity);
		return p;
	}

	/// The good entries of `message.list`, at most `limit` of them; a bad one or one past the limit is dropped and counted.
	template<typename T, typename ParseEntry>
	snap::ListParse<T> parse_list(const json &message, const char *type, size_t limit, ParseEntry parseEntry)
	{
		snap::ListParse<T> out;
		try
		{
			const json &list = list_of(message);
			out.valid = true;
			for (const json &entry : list)
			{
				try
				{
					if (out.items.size() >= limit)
						throw Bad{"more entries than the limit"};
					if (!entry.is_object())
						throw Bad{"an entry is not an object"};
					out.items.push_back(parseEntry(entry));
				}
				catch (const Bad &bad)
				{
					if (out.dropped++ == 0)
						out.reason = std::string(type) + ": " + bad.why;
				}
			}
		}
		catch (const Bad &bad)
		{
			out.reason = std::string(type) + ": " + bad.why;
		}
		return out;
	}

	/// Counts and logs what a list parse left out, and tells whether the list itself is usable.
	template<typename T>
	bool accept(const snap::ListParse<T> &parsed)
	{
		if (!parsed.valid)
			world::note_invalid(1, parsed.reason);
		else if (parsed.dropped > 0)
			world::note_invalid(parsed.dropped, parsed.reason);
		return parsed.valid;
	}
}

namespace cb::snap
{
	ListParse<Mob> parse_mobs(const json &message)
	{
		return parse_list<Mob>(message, "mobs", kMaxMobs, parse_mob);
	}

	ListParse<Projectile> parse_proj(const json &message)
	{
		return parse_list<Projectile>(message, "proj", kMaxProjectiles, parse_projectile);
	}

	std::optional<FlightSample> parse_flight(const json &message, std::string &reason)
	{
		try
		{
			FlightSample s;
			const auto active = message.find("active");
			if (active == message.end() || !active->is_boolean())
				throw Bad{"active is missing or not a bool"};
			s.active = active->get<bool>();
			s.pos = wire::cp_triple(message, "p");
			// the touchdown message only needs the landing spot; what it carries must still be valid
			if (s.active || message.contains("v"))
				s.velocity = velocity(message, "v");
			constexpr double kAbsent = std::numeric_limits<double>::quiet_NaN(); // Flight::update keeps the previous look
			s.yaw = kAbsent;
			s.pitch = kAbsent;
			if (s.active || message.contains("yaw"))
				s.yaw = mc_yaw_to_cp_yaw(wire::number(message, "yaw", -wire::kMaxCoord, wire::kMaxCoord));
			if (s.active || message.contains("pitch"))
				s.pitch = std::clamp(wire::number(message, "pitch", -wire::kMaxCoord, wire::kMaxCoord), -kMaxPitch, kMaxPitch);
			return s;
		}
		catch (const Bad &bad)
		{
			reason = "flight: " + bad.why;
			return std::nullopt;
		}
	}

	// ---- Flight ----------------------------------------------------------------------------------------------------

	Vec3 Flight::extrapolated(Clock::time_point now) const
	{
		const double age = std::chrono::duration<double>(now - m_receivedAt).count();
		const double t = std::clamp(age, 0.0, std::chrono::duration<double>(kMaxExtrapolation).count());
		return {m_sample.pos.x + m_sample.velocity.x * t, m_sample.pos.y + m_sample.velocity.y * t,
				m_sample.pos.z + m_sample.velocity.z * t};
	}

	void Flight::expire(Clock::time_point now)
	{
		if (!m_sample.active || now - m_receivedAt <= kMaxAge)
			return;
		// Minecraft vanished mid-flight: V ends where the flight was last seen
		m_sample.pos = extrapolated(now);
		m_sample.velocity = {};
		m_sample.active = false;
		m_timedOut = true;
		++m_seq;
	}

	void Flight::update(const FlightSample &sample, Clock::time_point now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		expire(now);
		m_timedOut = false;
		if (sample.active != m_sample.active)
			++m_seq;
		const double yaw = std::isnan(sample.yaw) ? m_sample.yaw : sample.yaw;
		const double pitch = std::isnan(sample.pitch) ? m_sample.pitch : sample.pitch;
		m_sample = sample;
		m_sample.yaw = yaw;
		m_sample.pitch = pitch;
		m_receivedAt = now;
	}

	void Flight::reset(Clock::time_point now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		m_timedOut = false;
		if (!m_sample.active)
			return;
		m_sample.pos = extrapolated(now);
		m_sample.velocity = {};
		m_sample.active = false;
		++m_seq;
	}

	bool Flight::active(Clock::time_point now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		expire(now);
		return m_sample.active;
	}

	int32_t Flight::seq(Clock::time_point now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		expire(now);
		return m_seq;
	}

	bool Flight::timed_out(Clock::time_point now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		expire(now);
		return m_timedOut;
	}

	Vec3 Flight::pos(Clock::time_point now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		expire(now);
		return m_sample.active ? extrapolated(now) : m_sample.pos;
	}

	Vec3 Flight::velocity(Clock::time_point now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		expire(now);
		return m_sample.velocity;
	}

	double Flight::yaw(Clock::time_point now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		expire(now);
		return m_sample.yaw;
	}

	double Flight::pitch(Clock::time_point now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		expire(now);
		return m_sample.pitch;
	}

	// ---- the link observer -----------------------------------------------------------------------------------------

	void on_minecraft_message(const json &message)
	{
		const auto type = message.find("t");
		if (type == message.end() || !type->is_string())
			return;
		const std::string &t = type->get_ref<const std::string &>();
		const Clock::time_point now = Clock::now();
		if (t == "hello")
		{
			// a new Minecraft session: what the old one showed is stale
			mobs().clear();
			projectiles().clear();
			flight().reset(now);
			natives::flightFollowing = false;
		}
		else if (t == "mobs")
		{
			ListParse<Mob> parsed = parse_mobs(message);
			if (accept(parsed))
				mobs().set(std::move(parsed.items), now);
		}
		else if (t == "proj")
		{
			ListParse<Projectile> parsed = parse_proj(message);
			if (accept(parsed))
				projectiles().set(std::move(parsed.items), now);
		}
		else if (t == "flight")
		{
			std::string reason;
			if (const std::optional<FlightSample> sample = parse_flight(message, reason))
			{
				flight().update(*sample, now);
				if (!sample->active)
					natives::flightFollowing = false; // Steve landed: V stops following
			}
			else
				world::note_invalid(1, reason);
		}
	}

	void on_link_dropped()
	{
		mobs().clear();
		projectiles().clear();
		flight().reset(Clock::now());
		natives::flightFollowing = false;
	}
}
