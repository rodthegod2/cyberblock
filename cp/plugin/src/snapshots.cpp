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
	constexpr double kMaxTimestamp = 1e13;                           // flight.ts, ms
	constexpr double kGapBucketMs = 0.5;                             // FlightStats resolution

	double seconds(snap::Clock::time_point t)
	{
		return std::chrono::duration<double>(t.time_since_epoch()).count();
	}

	double seconds(snap::Clock::duration d)
	{
		return std::chrono::duration<double>(d).count();
	}

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

	snap::Mob parse_mob(const json &entry, double off)
	{
		snap::Mob mob;
		mob.id = wire::integer(entry, "id", 0.0, kMaxId);
		mob.kind = wire::word(entry, "kind", kMaxKindLength, true);
		mob.feet = wire::cp_triple(entry, "p", off);
		mob.yaw = mc_yaw_to_cp_yaw(wire::number(entry, "yaw", -wire::kMaxCoord, wire::kMaxCoord));
		mob.width = wire::number(entry, "w", 0.0, kMaxMobSize);
		mob.height = wire::number(entry, "h", 0.0, kMaxMobSize);
		mob.hp = wire::number(entry, "hp", 0.0, kMaxMobHealth);
		mob.maxHp = wire::number(entry, "maxHp", 0.0, kMaxMobHealth);
		return mob;
	}

	snap::Projectile parse_projectile(const json &entry, double off)
	{
		snap::Projectile p;
		p.id = wire::integer(entry, "id", 0.0, kMaxId);
		p.pos = wire::cp_triple(entry, "p", off);
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
	ListParse<Mob> parse_mobs(const json &message, double off)
	{
		return parse_list<Mob>(message, "mobs", kMaxMobs, [off](const json &entry) { return parse_mob(entry, off); });
	}

	ListParse<Projectile> parse_proj(const json &message, double off)
	{
		return parse_list<Projectile>(message, "proj", kMaxProjectiles,
									  [off](const json &entry) { return parse_projectile(entry, off); });
	}

	std::optional<FlightSample> parse_flight(const json &message, std::string &reason, double off)
	{
		try
		{
			FlightSample s;
			const auto active = message.find("active");
			if (active == message.end() || !active->is_boolean())
				throw Bad{"active is missing or not a bool"};
			s.active = active->get<bool>();
			const auto kind = message.find("kind");
			s.launch = kind != message.end() && kind->is_string() && *kind == "launch";
			// a bad ts is ignored, not rejected: the sample is still good, it just plays back by its arrival time
			if (const auto ts = message.find("ts"); ts != message.end() && ts->is_number())
				if (const double v = ts->get<double>(); v >= 0.0 && v <= kMaxTimestamp)
					s.ts = v;
			s.pos = wire::cp_triple(message, "p", off);
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

	void Flight::clear_ring()
	{
		m_ring.clear();
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
		clear_ring();
	}

	void Flight::update(const FlightSample &sample, Clock::time_point now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		expire(now);
		m_timedOut = false;
		if (sample.active != m_sample.active)
		{
			++m_seq;
			clear_ring();
			if (sample.active)
			{
				// a new flight: its summary starts from zero
				m_samples = 0;
				m_maxGap = 0;
				m_gaps.fill(0);
			}
		}
		const double yaw = std::isnan(sample.yaw) ? m_sample.yaw : sample.yaw;
		const double pitch = std::isnan(sample.pitch) ? m_sample.pitch : sample.pitch;
		m_sample = sample;
		m_sample.yaw = yaw;
		m_sample.pitch = pitch;
		m_receivedAt = now;
		if (!sample.active)
			return;
		const double arrival = seconds(now);
		if (m_samples++ > 0)
		{
			const double gap = std::max(arrival - m_lastArrival, 0.0);
			m_maxGap = std::max(m_maxGap, gap);
			++m_gaps[std::min(size_t(gap * 1000.0 / kGapBucketMs), kGapBuckets - 1)];
		}
		m_lastArrival = arrival;
		m_ring.push_back({m_sample, arrival});
		if (m_ring.size() > kRing)
			m_ring.pop_front();
	}

	void Flight::reset(Clock::time_point now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		m_timedOut = false;
		clear_ring();
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

	Vec3 Flight::render_pos(Clock::time_point now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		expire(now);
		if (!m_sample.active)
			return m_sample.pos;
		if (m_ring.empty())
			return extrapolated(now);

		// each sample's playout time: ts moved onto the local clock by the smallest (arrival - ts) seen, so a sample the
		// link held back is still placed where Minecraft took it; without ts, its arrival
		double offset = std::numeric_limits<double>::infinity();
		for (const Entry &e : m_ring)
			if (e.sample.ts)
				offset = std::min(offset, e.arrival - *e.sample.ts / 1000.0);
		std::vector<std::pair<double, const FlightSample *>> points;
		points.reserve(m_ring.size());
		for (const Entry &e : m_ring)
			points.push_back({e.sample.ts ? *e.sample.ts / 1000.0 + offset : e.arrival, &e.sample});
		std::stable_sort(points.begin(), points.end(), [](const auto &a, const auto &b) { return a.first < b.first; });

		double delay = seconds(kMaxPlayout);
		if (points.size() >= 2)
		{
			std::vector<double> spacing;
			spacing.reserve(points.size() - 1);
			for (size_t i = 1; i < points.size(); ++i)
				spacing.push_back(points[i].first - points[i - 1].first);
			const size_t mid = spacing.size() / 2;
			std::nth_element(spacing.begin(), spacing.begin() + mid, spacing.end());
			double median = spacing[mid];
			if (spacing.size() % 2 == 0)
				median = (median + *std::max_element(spacing.begin(), spacing.begin() + mid)) / 2.0;
			delay = std::clamp(1.5 * median, seconds(kMinPlayout), seconds(kMaxPlayout));
		}
		const double u = seconds(now) - delay;

		if (u <= points.front().first)
			return points.front().second->pos; // never extrapolated backwards
		for (size_t i = 1; i < points.size(); ++i)
		{
			const auto &[t1, b] = points[i];
			if (u > t1)
				continue;
			const auto &[t0, a] = points[i - 1];
			const double f = t1 > t0 ? (u - t0) / (t1 - t0) : 1.0;
			return {a->pos.x + (b->pos.x - a->pos.x) * f, a->pos.y + (b->pos.y - a->pos.y) * f,
					a->pos.z + (b->pos.z - a->pos.z) * f};
		}
		const auto &[tn, newest] = points.back();
		const double t = std::min(u - tn, seconds(kMaxExtrapolation));
		return {newest->pos.x + newest->velocity.x * t, newest->pos.y + newest->velocity.y * t,
				newest->pos.z + newest->velocity.z * t};
	}

	bool Flight::launch(Clock::time_point now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		expire(now);
		return m_sample.launch;
	}

	FlightStats Flight::stats()
	{
		std::lock_guard<std::mutex> lock(m_lock);
		FlightStats out;
		out.samples = m_samples;
		out.maxGapMs = m_maxGap * 1000.0;
		const uint32_t gaps = m_samples > 1 ? uint32_t(m_samples - 1) : 0;
		uint32_t seen = 0;
		for (size_t i = 0; i < kGapBuckets && gaps > 0; ++i)
		{
			seen += m_gaps[i];
			if (2 * seen >= gaps)
			{
				out.medianGapMs = (i + 0.5) * kGapBucketMs; // the bucket's middle
				break;
			}
		}
		return out;
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
		// world::on_minecraft_message runs first (main.cpp), so an `align` already applies to the messages after it
		const double off = world::level_offset(world::level_k());
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
			ListParse<Mob> parsed = parse_mobs(message, off);
			if (accept(parsed))
				mobs().set(std::move(parsed.items), now);
		}
		else if (t == "proj")
		{
			ListParse<Projectile> parsed = parse_proj(message, off);
			if (accept(parsed))
				projectiles().set(std::move(parsed.items), now);
		}
		else if (t == "flight")
		{
			std::string reason;
			if (const std::optional<FlightSample> sample = parse_flight(message, reason, off))
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
