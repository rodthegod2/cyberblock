#include "world.hpp"
#include "log.hpp"
#include <algorithm>
#include <atomic>
#include <charconv>
#include <cmath>
#include <iterator>
#include <string_view>

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
	// layers 3-6 (plan 2026-10-03 section 1.2)
	constexpr double kMinLeadLength = 1.0;
	constexpr double kMaxLeadLength = 32.0;
	constexpr double kMaxStrength = 50.0;    // pull and knock, m/s
	constexpr double kMaxKnockRadius = 16.0; // m
	constexpr double kMaxEffectSeconds = 86400.0;
	constexpr double kMaxAmplifier = 255.0;
	constexpr double kMaxPerkFxSeconds = 30.0;
	constexpr double kMaxRequestId = 2147483647.0; // scripts get request and mob ids as Int32
	constexpr size_t kMaxBlockCells = 64;
	constexpr size_t kMaxPerkEntries = 32; // the table has 20 ids: a sane bound, the rest is dropped
	constexpr size_t kMaxEffectName = 32;

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

	/// A whole number in [lo, hi] (3 and 3.0 are the same: a wire writer may print an int as a float).
	int32_t integer(const json &m, const char *key, double lo, double hi)
	{
		const double v = number(m, key, lo, hi);
		if (v != std::floor(v))
			throw Bad{std::string(key) + " must be a whole number"};
		return static_cast<int32_t>(v);
	}

	/// An optional bool, false when absent.
	bool optional_bool(const json &m, const char *key)
	{
		const auto it = m.find(key);
		if (it == m.end())
			return false;
		if (!it->is_boolean())
			throw Bad{std::string(key) + " is not a bool"};
		return it->get<bool>();
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

	/// A Minecraft-space [x, y, z] position or vector as the Cyberpunk-space one (plan 1.1: positions and velocities map
	/// the same way).
	cb::Vec3 cp_triple(const json &m, const char *key)
	{
		const cb::Vec3 at = triple(m, key, false);
		return cb::mc_to_cp_pos(at.x, at.y, at.z);
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

	/// A decimal-string id, or 0 when the key is absent or null (`scan.target` null: whatever V looks at).
	uint64_t optional_entity_id(const json &m, const char *key)
	{
		const auto it = m.find(key);
		return it == m.end() || it->is_null() ? 0 : entity_id(m, key);
	}

	/// A string that is one of `allowed`.
	template<size_t N>
	std::string one_of(const json &m, const char *key, const char *const (&allowed)[N])
	{
		const auto it = m.find(key);
		if (it == m.end() || !it->is_string())
			throw Bad{std::string(key) + " is missing or not a string"};
		const std::string &text = it->get_ref<const std::string &>();
		if (std::find(std::begin(allowed), std::end(allowed), std::string_view(text)) == std::end(allowed))
			throw Bad{std::string(key) + " is not a known name"};
		return text;
	}

	/// A Minecraft effect path: [a-z_]{1,32}.
	std::string effect_name(const json &m, const char *key)
	{
		const auto it = m.find(key);
		if (it == m.end() || !it->is_string())
			throw Bad{std::string(key) + " is missing or not a string"};
		const std::string &text = it->get_ref<const std::string &>();
		const bool ok = !text.empty() && text.size() <= kMaxEffectName &&
						std::all_of(text.begin(), text.end(), [](char c) { return (c >= 'a' && c <= 'z') || c == '_'; });
		if (!ok)
			throw Bad{std::string(key) + " is not [a-z_]{1,32}"};
		return text;
	}

	Event block_add(int bx, int by, int bz)
	{
		Event e;
		e.kind = EventKind::BlockAdd;
		e.pos = cb::mc_block_to_cp_aabb(bx, by, bz).min;
		return e;
	}

	Event parse_block(const json &m)
	{
		const auto op = m.find("op");
		if (op == m.end() || !op->is_string() || (*op != "add" && *op != "remove"))
			throw Bad{"op must be \"add\" or \"remove\""};
		const cb::Vec3 b = triple(m, "pos", true);
		Event e = block_add(int(b.x), int(b.y), int(b.z));
		if (*op == "remove")
			e.kind = EventKind::BlockRemove;
		return e;
	}

	/// {"t":"blocks","reset":bool,"c":[x,y,z, ...]}: a `block_reset` when it resets, then a `block_add` per cell. A bad cell
	/// is left out and counted; a `c` that is not a list of triples, or holds more than 64 cells, rejects the message.
	void parse_blocks(const json &m, cb::world::Parsed &out)
	{
		const bool reset = optional_bool(m, "reset");
		const auto cells = m.find("c");
		if (cells == m.end() || !cells->is_array() || cells->size() % 3 != 0)
			throw Bad{"c is missing or not a list of [x, y, z] cells"};
		if (cells->size() / 3 > kMaxBlockCells)
			throw Bad{"c holds more than 64 cells"};
		if (reset)
		{
			Event e;
			e.kind = EventKind::BlockReset;
			out.events.push_back(e);
		}
		for (size_t i = 0; i < cells->size(); i += 3)
		{
			double v[3];
			bool good = true;
			for (size_t k = 0; k < 3 && good; ++k)
			{
				const json &c = (*cells)[i + k];
				v[k] = c.is_number() ? c.get<double>() : 0.0;
				good = c.is_number() && std::abs(v[k]) < kMaxCoord && v[k] == std::floor(v[k]);
			}
			if (good)
				out.events.push_back(block_add(int(v[0]), int(v[1]), int(v[2])));
			else if (out.droppedEntries++ == 0)
				out.reason = "blocks: a cell is not three whole numbers within range";
		}
	}

	Event parse_explosion(const json &m)
	{
		Event e;
		e.kind = EventKind::Explosion;
		e.pos = cp_triple(m, "pos");
		e.amount = number(m, "power", 0.0, kMaxPower);
		e.flag = optional_bool(m, "fire");
		return e;
	}

	Event parse_hit(const json &m)
	{
		Event e;
		e.kind = EventKind::Hit;
		e.target = entity_id(m, "target");
		e.amount = number(m, "dmg", 0.0, kMaxDamage) / kHealthPerFraction;
		if (m.find("impulse") != m.end())
			e.vector = cp_triple(m, "impulse");
		e.id = -1; // not dealt by a mob: V's own hit
		if (const auto mob = m.find("mob"); mob != m.end() && !mob->is_null())
			e.id = integer(m, "mob", 0.0, kMaxRequestId);
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

	Event parse_teleport(const json &m)
	{
		Event e;
		e.kind = EventKind::Teleport;
		e.pos = cp_triple(m, "p");
		e.amount = cb::mc_yaw_to_cp_yaw(number(m, "yaw", -kMaxCoord, kMaxCoord));
		return e;
	}

	/// `lead` (a proxy was leashed), or `lead_release` for {"release":true}.
	Event parse_lead(const json &m)
	{
		Event e;
		e.target = entity_id(m, "target");
		if (optional_bool(m, "release"))
		{
			e.kind = EventKind::LeadRelease;
			return e;
		}
		e.kind = EventKind::Lead;
		e.amount = number(m, "length", kMinLeadLength, kMaxLeadLength);
		if (const auto anchor = m.find("anchor"); anchor != m.end() && !anchor->is_null())
		{
			e.pos = cp_triple(m, "anchor");
			e.flag = true; // a fixed anchor; without one Steve holds the leash
		}
		return e;
	}

	Event parse_pull(const json &m)
	{
		Event e;
		e.kind = EventKind::Pull;
		e.target = entity_id(m, "target");
		e.pos = cp_triple(m, "toward");
		e.amount = number(m, "strength", 0.0, kMaxStrength);
		return e;
	}

	Event parse_knock(const json &m)
	{
		Event e;
		e.kind = EventKind::Knock;
		e.pos = cp_triple(m, "pos");
		e.amount = number(m, "radius", 0.0, kMaxKnockRadius);
		e.extra = number(m, "strength", 0.0, kMaxStrength);
		return e;
	}

	Event parse_effect(const json &m)
	{
		Event e;
		e.kind = EventKind::Effect;
		e.name = effect_name(m, "name");
		e.amount = number(m, "seconds", 0.0, kMaxEffectSeconds);
		e.extra = integer(m, "amp", 0.0, kMaxAmplifier);
		return e;
	}

	Event parse_hack(const json &m)
	{
		Event e;
		e.kind = EventKind::Hack;
		e.id = integer(m, "id", 0.0, kMaxRequestId);
		e.target = entity_id(m, "target");
		e.name = one_of(m, "hack", cb::world::kHackNames);
		e.flag = optional_bool(m, "chain");
		return e;
	}

	Event parse_scan(const json &m)
	{
		Event e;
		e.kind = EventKind::Scan;
		e.id = integer(m, "id", 0.0, kMaxRequestId);
		e.target = optional_entity_id(m, "target");
		return e;
	}

	/// {"t":"perks","list":[{"id","rank"}]}: a `perk` per entry. A bad entry (not an object, an unknown id, a rank that is
	/// not 0 or 1, or past the 32nd) is left out and counted; a `list` that is not an array rejects the message.
	void parse_perks(const json &m, cb::world::Parsed &out)
	{
		const auto list = m.find("list");
		if (list == m.end() || !list->is_array())
			throw Bad{"list is missing or not an array"};
		size_t seen = 0;
		for (const json &entry : *list)
		{
			try
			{
				if (++seen > kMaxPerkEntries)
					throw Bad{"more than 32 entries"};
				if (!entry.is_object())
					throw Bad{"an entry is not an object"};
				Event e;
				e.kind = EventKind::Perk;
				e.name = one_of(entry, "id", cb::world::kPerkIds);
				e.amount = integer(entry, "rank", 0.0, 1.0);
				out.events.push_back(std::move(e));
			}
			catch (const Bad &bad)
			{
				if (out.droppedEntries++ == 0)
					out.reason = "perks: " + bad.why;
			}
		}
	}

	Event parse_perkfx(const json &m)
	{
		Event e;
		e.kind = EventKind::PerkFx;
		e.name = one_of(m, "id", cb::world::kPerkIds);
		e.amount = number(m, "seconds", 0.0, kMaxPerkFxSeconds);
		return e;
	}

	/// The kinds that are kept while the mashup is not Active (see EventQueue::set_active); every other kind is transient.
	bool kept_while_inactive(EventKind kind)
	{
		switch (kind)
		{
		case EventKind::BlockAdd:
		case EventKind::BlockRemove:
		case EventKind::BlockReset:
		case EventKind::Perk:
		case EventKind::Scan:
		case EventKind::Hack:
			return true;
		default:
			return false;
		}
	}

	bool transient(EventKind kind)
	{
		return kind != EventKind::None && !kept_while_inactive(kind);
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
		case EventKind::BlockReset: return "block_reset";
		case EventKind::Teleport: return "teleport";
		case EventKind::Lead: return "lead";
		case EventKind::LeadRelease: return "lead_release";
		case EventKind::Pull: return "pull";
		case EventKind::Knock: return "knock";
		case EventKind::Effect: return "effect";
		case EventKind::Hack: return "hack";
		case EventKind::Scan: return "scan";
		case EventKind::Perk: return "perk";
		case EventKind::PerkFx: return "perkfx";
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
			if (t == "blocks")
				parse_blocks(message, out);
			else if (t == "perks")
				parse_perks(message, out);
			else if (t == "block")
				out.events.push_back(parse_block(message));
			else if (t == "explosion")
				out.events.push_back(parse_explosion(message));
			else if (t == "hit")
				out.events.push_back(parse_hit(message));
			else if (t == "ignite")
				out.events.push_back(parse_ignite(message));
			else if (t == "teleport")
				out.events.push_back(parse_teleport(message));
			else if (t == "lead")
				out.events.push_back(parse_lead(message));
			else if (t == "pull")
				out.events.push_back(parse_pull(message));
			else if (t == "knock")
				out.events.push_back(parse_knock(message));
			else if (t == "effect")
				out.events.push_back(parse_effect(message));
			else if (t == "hack")
				out.events.push_back(parse_hack(message));
			else if (t == "scan")
				out.events.push_back(parse_scan(message));
			else if (t == "perkfx")
				out.events.push_back(parse_perkfx(message));
			else
				return out; // another layer's message
			out.status = Parsed::Status::Event;
		}
		catch (const Bad &bad)
		{
			out = {}; // a rejected message queues nothing, not even the events built before the bad field
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

	void EventQueue::note_rejected(size_t count)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		m_rejected += count;
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
		for (const Event &event : parsed.events)
			events().push(event);
		const size_t bad = parsed.status == Parsed::Status::Rejected ? 1 : parsed.droppedEntries;
		if (bad > 0)
		{
			events().note_rejected(bad);
			static std::atomic<int> logged{0};
			constexpr int kMaxLogged = 10; // the memory log line carries the total
			if (logged++ < kMaxLogged)
				log::warn("ignoring an invalid message or list entry from Minecraft (%s)", parsed.reason.c_str());
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
