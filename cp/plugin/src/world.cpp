#include "world.hpp"
#include "log.hpp"
#include <algorithm>
#include <atomic>
#include <charconv>
#include <cmath>

namespace
{
	using nlohmann::json;
	using cb::world::Event;
	using cb::world::EventKind;

	constexpr double kMaxCoord = 3e7; // |coordinate| must stay below this
	constexpr double kMaxPower = 20.0;
	constexpr double kMaxDamage = 1000.0; // Minecraft health points
	constexpr double kMaxSeconds = 120.0;
	constexpr double kMaxFrac = 4.0;
	constexpr double kHealthPerFraction = 20.0; // hit.dmg / 20 = fraction of the target's max health

	/// A field that failed validation; caught in parse_event.
	struct Bad
	{
		std::string why;
	};

	/// A JSON number within [lo, hi] (NaN and infinities fail the comparison).
	double number(const json &m, const char *key, double lo, double hi)
	{
		const auto it = m.find(key);
		if (it == m.end() || !it->is_number())
			throw Bad{std::string(key) + " is missing or not a number"};
		const double v = it->get<double>();
		if (!(v >= lo && v <= hi))
			throw Bad{std::string(key) + " is out of range"};
		return v;
	}

	/// [x, y, z] with every |component| < 3e7; `whole` also demands whole numbers (block coordinates).
	cb::Vec3 triple(const json &m, const char *key, bool whole)
	{
		const auto it = m.find(key);
		if (it == m.end() || !it->is_array() || it->size() != 3)
			throw Bad{std::string(key) + " is missing or not [x, y, z]"};
		double v[3];
		for (size_t i = 0; i < 3; ++i)
		{
			const json &c = (*it)[i];
			if (!c.is_number())
				throw Bad{std::string(key) + " holds a non-number"};
			v[i] = c.get<double>();
			if (!(std::abs(v[i]) < kMaxCoord))
				throw Bad{std::string(key) + " is out of range"};
			if (whole && v[i] != std::floor(v[i]))
				throw Bad{std::string(key) + " must be whole numbers"};
		}
		return {v[0], v[1], v[2]};
	}

	/// A decimal uint64 in a string (the wire cannot carry one as a number).
	uint64_t entity_id(const json &m, const char *key)
	{
		const auto it = m.find(key);
		if (it == m.end() || !it->is_string())
			throw Bad{std::string(key) + " is missing or not a decimal string"};
		const std::string &text = it->get_ref<const std::string &>();
		uint64_t id = 0;
		const char *const end = text.data() + text.size();
		const auto [stop, error] = std::from_chars(text.data(), end, id);
		if (error != std::errc{} || stop != end)
			throw Bad{std::string(key) + " is not a decimal uint64"};
		return id;
	}

	Event parse_block(const json &m)
	{
		const auto op = m.find("op");
		if (op == m.end() || !op->is_string() || (*op != "add" && *op != "remove"))
			throw Bad{"op must be \"add\" or \"remove\""};
		const cb::Vec3 b = triple(m, "pos", true);
		Event e;
		e.kind = *op == "add" ? EventKind::BlockAdd : EventKind::BlockRemove;
		e.pos = cb::mc_block_to_cp_aabb(int(b.x), int(b.y), int(b.z)).min;
		return e;
	}

	Event parse_explosion(const json &m)
	{
		const cb::Vec3 at = triple(m, "pos", false);
		Event e;
		e.kind = EventKind::Explosion;
		e.pos = cb::mc_to_cp_pos(at.x, at.y, at.z);
		e.amount = number(m, "power", 0.0, kMaxPower);
		if (const auto fire = m.find("fire"); fire != m.end())
		{
			if (!fire->is_boolean())
				throw Bad{"fire is not a bool"};
			e.flag = fire->get<bool>();
		}
		return e;
	}

	Event parse_hit(const json &m)
	{
		Event e;
		e.kind = EventKind::Hit;
		e.target = entity_id(m, "target");
		e.amount = number(m, "dmg", 0.0, kMaxDamage) / kHealthPerFraction;
		if (m.find("impulse") != m.end())
		{
			const cb::Vec3 i = triple(m, "impulse", false);
			e.vector = cb::mc_to_cp_pos(i.x, i.y, i.z);
		}
		return e;
	}

	Event parse_ignite(const json &m)
	{
		Event e;
		e.kind = EventKind::Ignite;
		e.target = entity_id(m, "target");
		e.amount = number(m, "seconds", 0.0, kMaxSeconds);
		return e;
	}

	bool transient(EventKind kind)
	{
		return kind == EventKind::Explosion || kind == EventKind::Hit || kind == EventKind::Ignite;
	}

	/// Wire numbers: rounded so floats do not print as 12.345000267028809, and always a plain JSON number.
	double rounded(double v, double scale)
	{
		return std::round(v * scale) / scale;
	}

	json triple_json(const cb::Vec3 &v)
	{
		return {rounded(v.x, 1e3), rounded(v.y, 1e3), rounded(v.z, 1e3)};
	}

	bool finite(const cb::Vec3 &v)
	{
		return std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z);
	}
}

namespace cb::world
{
	const char *event_name(EventKind kind)
	{
		switch (kind)
		{
		case EventKind::Explosion: return "explosion";
		case EventKind::Hit: return "hit";
		case EventKind::Ignite: return "ignite";
		case EventKind::BlockAdd: return "block_add";
		case EventKind::BlockRemove: return "block_remove";
		case EventKind::None: break;
		}
		return "";
	}

	Parsed parse_event(const json &message)
	{
		Parsed out;
		if (!message.is_object())
			return out;
		const auto type = message.find("t");
		if (type == message.end() || !type->is_string())
			return out;
		const std::string &t = type->get_ref<const std::string &>();
		try
		{
			if (t == "block")
				out.event = parse_block(message);
			else if (t == "explosion")
				out.event = parse_explosion(message);
			else if (t == "hit")
				out.event = parse_hit(message);
			else if (t == "ignite")
				out.event = parse_ignite(message);
			else
				return out; // another layer's message
			out.status = Parsed::Status::Event;
		}
		catch (const Bad &bad)
		{
			out.status = Parsed::Status::Rejected;
			out.reason = t + ": " + bad.why;
		}
		return out;
	}

	void EventQueue::set_active(bool active)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		if (active == m_active)
			return;
		m_active = active;
		std::erase_if(m_queue, [](const Event &e) { return transient(e.kind); });
	}

	void EventQueue::push(const Event &event)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		if (event.kind == EventKind::None || (transient(event.kind) && !m_active))
			return;
		if (m_queue.size() >= kCapacity)
		{
			m_queue.pop_front();
			++m_dropped;
		}
		m_queue.push_back(event);
	}

	void EventQueue::note_rejected()
	{
		std::lock_guard<std::mutex> lock(m_lock);
		++m_rejected;
	}

	EventKind EventQueue::next()
	{
		std::lock_guard<std::mutex> lock(m_lock);
		m_current = {};
		if (m_queue.empty())
			return EventKind::None;
		m_current = m_queue.front();
		m_queue.pop_front();
		return m_current.kind;
	}

	Event EventQueue::current() const
	{
		std::lock_guard<std::mutex> lock(m_lock);
		return m_current;
	}

	void EventQueue::clear()
	{
		std::lock_guard<std::mutex> lock(m_lock);
		m_queue.clear();
		m_current = {};
	}

	QueueStats EventQueue::stats() const
	{
		std::lock_guard<std::mutex> lock(m_lock);
		return {m_queue.size(), m_dropped, m_rejected};
	}

	void on_minecraft_message(const json &message)
	{
		const auto type = message.find("t");
		if (type != message.end() && *type == "hello")
		{
			// a new Minecraft session: whatever the old one queued is stale
			events().clear();
			return;
		}
		const Parsed parsed = parse_event(message);
		if (parsed.status == Parsed::Status::Event)
			events().push(parsed.event);
		else if (parsed.status == Parsed::Status::Rejected)
		{
			events().note_rejected();
			static std::atomic<int> logged{0};
			constexpr int kMaxLogged = 10; // the memory log line carries the total
			if (logged++ < kMaxLogged)
				log::warn("ignoring an invalid message from Minecraft (%s)", parsed.reason.c_str());
		}
	}

	double cp_yaw_to_mc_yaw(double cpYaw)
	{
		double yaw = std::fmod(180.0 - cpYaw, 360.0);
		if (yaw > 180.0)
			yaw -= 360.0;
		else if (yaw <= -180.0)
			yaw += 360.0;
		return yaw;
	}

	std::optional<json> make_dmg(double frac, std::string_view kind, Vec3 fromCp, bool hasFrom)
	{
		if (!std::isfinite(frac))
			return std::nullopt;
		const bool known = std::find(std::begin(kDamageKinds), std::end(kDamageKinds), kind) != std::end(kDamageKinds);
		json from = nullptr;
		if (hasFrom && finite(fromCp))
		{
			const Vec3 mc = cp_to_mc_pos(fromCp.x, fromCp.y, fromCp.z);
			from = triple_json(mc);
		}
		return json{{"t", "dmg"},
					{"frac", rounded(std::clamp(frac, 0.0, kMaxFrac), 1e4)},
					{"kind", known ? std::string(kind) : std::string("other")},
					{"from", std::move(from)}};
	}

	std::optional<json> make_npcs(std::span<const uint64_t> ids, std::span<const Vec3> feetCp,
								  std::span<const double> yawsCp, std::span<const uint8_t> vehicles,
								  std::span<const uint8_t> hostile)
	{
		const size_t n = ids.size();
		if (feetCp.size() != n || yawsCp.size() != n || vehicles.size() != n || hostile.size() != n)
			return std::nullopt;
		json list = json::array();
		for (size_t i = 0; i < n; ++i)
		{
			if (!finite(feetCp[i]) || !std::isfinite(yawsCp[i]))
				continue;
			const Vec3 feet = cp_to_mc_pos(feetCp[i].x, feetCp[i].y, feetCp[i].z);
			list.push_back({{"id", std::to_string(ids[i])},
							{"p", triple_json(feet)},
							{"yaw", rounded(cp_yaw_to_mc_yaw(yawsCp[i]), 1e2)},
							{"kind", vehicles[i] != 0 ? "vehicle" : "human"},
							{"hostile", hostile[i] != 0}});
		}
		return json{{"t", "npcs"}, {"list", std::move(list)}};
	}
}
