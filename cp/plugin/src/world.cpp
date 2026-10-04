#include "world.hpp"
#include "log.hpp"
#include "wire.hpp"
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <iterator>
#include <utility>
#include <vector>

namespace
{
	using nlohmann::json;
	using cb::world::Event;
	using cb::world::EventKind;

	constexpr double kMaxCoord = cb::wire::kMaxCoord; // |coordinate| must stay below this
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
	constexpr size_t kMaxCyberwareEntries = cb::world::kMaxCyberwareEntries;
	constexpr size_t kMaxEffectName = 32;

	using cb::wire::Bad;
	using cb::wire::cp_triple;
	using cb::wire::cp_vector;
	using cb::wire::entity_id;
	using cb::wire::finite;
	using cb::wire::integer;
	using cb::wire::number;
	using cb::wire::one_of;
	using cb::wire::optional_bool;
	using cb::wire::optional_entity_id;
	using cb::wire::rounded;
	using cb::wire::triple;
	using cb::wire::triple_json;

	/// The ground alignment k of the attached world (plan 2026-10-03-retest2 section 1.1): Minecraft's `align`, back to 0
	/// on every `hello`. Written on the link thread, read by the natives and the camera on the game's threads.
	std::atomic<int> g_levelK{0};
	/// Re-test 3: the last `persp` was "back" (link thread writes, the game's threads read).
	std::atomic<bool> g_thirdPerson{false};
	/// Re-test 3: the last `status.pause`.
	std::atomic<bool> g_pause{false};
	/// Phase 1: the last valid `session` (link thread writes, the natives read), the ground epoch and the cleanup flag.
	std::mutex g_sessionLock;
	cb::world::Session g_session;
	std::atomic<int> g_groundEpoch{0};
	std::atomic<bool> g_sessionCleanup{false};

	/// A new Minecraft (hello) or none (link drop): its server session is not known yet.
	void forget_session()
	{
		std::lock_guard<std::mutex> lock(g_sessionLock);
		g_session = {};
	}

	void set_pause(bool pause)
	{
		if (g_pause.exchange(pause) != pause)
			cb::log::info("pause: Minecraft's Night City pause screen %s", pause ? "opened" : "closed");
	}

	void set_level_k(int k)
	{
		if (g_levelK.exchange(k) != k)
			cb::log::info("ground alignment: Minecraft y = Cyberpunk z - %.4f (k %d/64)", cb::world::level_offset(k), k);
	}

	/// A block's event position is its Cyberpunk AABB min corner: (bx, -(bz+1), by + off).
	Event block_add(int bx, int by, int bz, double off)
	{
		Event e;
		e.kind = EventKind::BlockAdd;
		e.pos = cb::mc_block_to_cp_aabb(bx, by, bz, off).min;
		return e;
	}

	Event parse_block(const json &m, double off)
	{
		const auto op = m.find("op");
		if (op == m.end() || !op->is_string() || (*op != "add" && *op != "remove"))
			throw Bad{"op must be \"add\" or \"remove\""};
		const cb::Vec3 b = triple(m, "pos", true);
		Event e = block_add(int(b.x), int(b.y), int(b.z), off);
		if (*op == "remove")
			e.kind = EventKind::BlockRemove;
		return e;
	}

	/// {"t":"blocks","reset":bool,"c":[x,y,z, ...]}: a `block_reset` when it resets, then a `block_add` per cell. A bad cell
	/// is left out and counted; a `c` that is not a list of triples, or holds more than 64 cells, rejects the message.
	void parse_blocks(const json &m, cb::world::Parsed &out, double off)
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
				out.events.push_back(block_add(int(v[0]), int(v[1]), int(v[2]), off));
			else if (out.droppedEntries++ == 0)
				out.reason = "blocks: a cell is not three whole numbers within range";
		}
	}

	Event parse_explosion(const json &m, double off)
	{
		Event e;
		e.kind = EventKind::Explosion;
		e.pos = cp_triple(m, "pos", off);
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
			e.vector = cp_vector(m, "impulse"); // an impulse is a velocity: never offset
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

	Event parse_teleport(const json &m, double off)
	{
		Event e;
		e.kind = EventKind::Teleport;
		e.pos = cp_triple(m, "p", off);
		e.amount = cb::mc_yaw_to_cp_yaw(number(m, "yaw", -kMaxCoord, kMaxCoord));
		return e;
	}

	/// `lead` (a proxy was leashed), or `lead_release` for {"release":true}.
	Event parse_lead(const json &m, double off)
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
			e.pos = cp_triple(m, "anchor", off);
			e.flag = true; // a fixed anchor; without one Steve holds the leash
		}
		return e;
	}

	Event parse_pull(const json &m, double off)
	{
		Event e;
		e.kind = EventKind::Pull;
		e.target = entity_id(m, "target");
		e.pos = cp_triple(m, "toward", off);
		e.amount = number(m, "strength", 0.0, kMaxStrength);
		return e;
	}

	Event parse_knock(const json &m, double off)
	{
		Event e;
		e.kind = EventKind::Knock;
		e.pos = cp_triple(m, "pos", off);
		e.amount = number(m, "radius", 0.0, kMaxKnockRadius);
		e.extra = number(m, "strength", 0.0, kMaxStrength);
		return e;
	}

	Event parse_effect(const json &m)
	{
		Event e;
		e.kind = EventKind::Effect;
		e.name = cb::wire::word(m, "name", kMaxEffectName, false); // a Minecraft effect path
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

	/// {"t":"cyberware","list":[{"id","on"}]} (plan 2026-10-03-retest2 section 1.2): a `cyber` per entry. A bad entry (not
	/// an object, an unknown id, an `on` that is not 0 or 1, or past the 32nd) is left out and counted; a `list` that is
	/// not an array rejects the message.
	void parse_cyberware(const json &m, cb::world::Parsed &out)
	{
		const auto list = m.find("list");
		if (list == m.end() || !list->is_array())
			throw Bad{"list is missing or not an array"};
		size_t seen = 0;
		for (const json &entry : *list)
		{
			try
			{
				if (++seen > kMaxCyberwareEntries)
					throw Bad{"more than 32 entries"};
				if (!entry.is_object())
					throw Bad{"an entry is not an object"};
				Event e;
				e.kind = EventKind::Cyber;
				e.name = one_of(entry, "id", cb::world::kCyberwareIds);
				e.amount = integer(entry, "on", 0.0, 1.0);
				out.events.push_back(std::move(e));
			}
			catch (const Bad &bad)
			{
				if (out.droppedEntries++ == 0)
					out.reason = "cyberware: " + bad.why;
			}
		}
	}

	/// {"t":"cyberfx","id","seconds"}: an implant effect starts (0 seconds: it ends now). It replaces Minecraft's perkfx.
	Event parse_cyberfx(const json &m)
	{
		Event e;
		e.kind = EventKind::CyberFx;
		e.name = one_of(m, "id", cb::world::kCyberFxIds);
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
		case EventKind::Cyber: // the install state, like a perk rank
		case EventKind::Scan:
		case EventKind::Hack:
		case EventKind::Session: // the cleanup must happen whatever the state
			return true;
		default:
			return false;
		}
	}

	bool transient(EventKind kind)
	{
		return kind != EventKind::None && !kept_while_inactive(kind);
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
		case EventKind::Cyber: return "cyber";
		case EventKind::CyberFx: return "cyberfx";
		case EventKind::Session: return "session";
		case EventKind::None: break;
		}
		return "";
	}

	Parsed parse_event(const json &message, double off)
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
				parse_blocks(message, out, off);
			else if (t == "perks")
				parse_perks(message, out);
			else if (t == "cyberware")
				parse_cyberware(message, out);
			else if (t == "block")
				out.events.push_back(parse_block(message, off));
			else if (t == "explosion")
				out.events.push_back(parse_explosion(message, off));
			else if (t == "hit")
				out.events.push_back(parse_hit(message));
			else if (t == "ignite")
				out.events.push_back(parse_ignite(message));
			else if (t == "teleport")
				out.events.push_back(parse_teleport(message, off));
			else if (t == "lead")
				out.events.push_back(parse_lead(message, off));
			else if (t == "pull")
				out.events.push_back(parse_pull(message, off));
			else if (t == "knock")
				out.events.push_back(parse_knock(message, off));
			else if (t == "effect")
				out.events.push_back(parse_effect(message));
			else if (t == "hack")
				out.events.push_back(parse_hack(message));
			else if (t == "scan")
				out.events.push_back(parse_scan(message));
			else if (t == "perkfx")
				out.events.push_back(parse_perkfx(message));
			else if (t == "cyberfx")
				out.events.push_back(parse_cyberfx(message));
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
		if (event.kind == EventKind::BlockReset)
		{
			m_blocks.clear(); // what they changed is forgotten by the reset anyway
			m_blockReset = true;
			return;
		}
		if (event.kind == EventKind::BlockAdd || event.kind == EventKind::BlockRemove)
		{
			const std::array<double, 3> cell{event.pos.x, event.pos.y, event.pos.z};
			if (m_blocks.size() >= kMaxBlockCells && !m_blocks.contains(cell))
			{
				++m_dropped;
				return;
			}
			m_blocks[cell] = event.kind;
			return;
		}
		if (m_queue.size() >= kCapacity)
		{
			++m_dropped;
			const auto oldestTransient = std::ranges::find_if(m_queue, [](const Event &e) { return transient(e.kind); });
			if (oldestTransient != m_queue.end())
				m_queue.erase(oldestTransient);
			else if (transient(event.kind))
				return; // only perks, scans and hacks are queued: they outrank this one
			else
				m_queue.pop_front();
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
		if (!m_queue.empty())
		{
			m_current = m_queue.front();
			m_queue.pop_front();
		}
		else if (m_blockReset)
		{
			m_current.kind = EventKind::BlockReset;
			m_blockReset = false;
		}
		else if (!m_blocks.empty())
		{
			const auto [cell, kind] = *m_blocks.begin();
			m_current.kind = kind;
			m_current.pos = {cell[0], cell[1], cell[2]};
			m_blocks.erase(m_blocks.begin());
		}
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
		m_blockReset = false;
		m_blocks.clear();
		m_current = {};
	}

	void EventQueue::clear_blocks()
	{
		std::lock_guard<std::mutex> lock(m_lock);
		m_blockReset = false;
		m_blocks.clear();
	}

	QueueStats EventQueue::stats() const
	{
		std::lock_guard<std::mutex> lock(m_lock);
		return {m_queue.size() + m_blocks.size() + (m_blockReset ? 1 : 0), m_dropped, m_rejected};
	}

	int level_k()
	{
		return g_levelK.load();
	}

	void on_minecraft_message(const json &message)
	{
		const auto type = message.find("t");
		if (type != message.end() && *type == "hello")
		{
			// a new Minecraft session: whatever the old one queued is stale, and its alignment comes again with `align`
			events().clear();
			set_level_k(0);
			g_thirdPerson = false; // the new session sends `persp` again after its hello
			set_pause(false);
			forget_session(); // and its `session` after its handshake with the server
			return;
		}
		if (type != message.end() && *type == "session")
		{
			std::string why;
			if (const auto session = parse_session(message, &why))
				on_session(*session);
			else
				note_invalid(1, "session: " + why);
			return;
		}
		if (type != message.end() && *type == "status")
		{
			const auto pause = message.find("pause");
			set_pause(pause != message.end() && pause->is_boolean() && pause->get<bool>());
			return;
		}
		if (type != message.end() && *type == "persp")
		{
			if (const auto back = parse_persp(message))
			{
				if (g_thirdPerson.exchange(*back) != *back)
					log::info("persp: Minecraft asks for %s", *back ? "the third-person view" : "first person");
			}
			else
				note_invalid(1, "persp: mode is missing or not \"first\" or \"back\"");
			return;
		}
		if (type != message.end() && *type == "align")
		{
			// plan retest2 section 1.2: k must be an integer 0..63; a bad message is counted and the old k kept
			try
			{
				set_level_k(integer(message, "k", 0.0, kLevelSteps - 1.0));
			}
			catch (const Bad &bad)
			{
				note_invalid(1, "align: " + bad.why);
			}
			return;
		}
		const Parsed parsed = parse_event(message, level_offset(level_k()));
		for (const Event &event : parsed.events)
			events().push(event);
		const size_t bad = parsed.status == Parsed::Status::Rejected ? 1 : parsed.droppedEntries;
		if (bad > 0)
			note_invalid(bad, parsed.reason);
	}

	std::optional<bool> parse_persp(const json &message)
	{
		const auto mode = message.find("mode");
		if (mode == message.end() || !mode->is_string())
			return std::nullopt;
		if (*mode == "back")
			return true;
		if (*mode == "first")
			return false;
		return std::nullopt;
	}

	bool third_person_wanted()
	{
		return g_thirdPerson;
	}

	bool minecraft_pause()
	{
		return g_pause;
	}

	void on_link_dropped()
	{
		g_thirdPerson = false;
		set_pause(false);
		forget_session();
	}

	void note_invalid(size_t count, const std::string &reason)
	{
		events().note_rejected(count);
		static std::atomic<int> logged{0};
		constexpr int kMaxLogged = 10; // the memory log line carries the total
		if (logged++ < kMaxLogged)
			log::warn("ignoring an invalid message or list entry from Minecraft (%s)", reason.c_str());
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

	std::optional<json> make_dmg(double frac, std::string_view kind, Vec3 fromCp, bool hasFrom, double off)
	{
		if (!std::isfinite(frac))
			return std::nullopt;
		const bool known = std::find(std::begin(kDamageKinds), std::end(kDamageKinds), kind) != std::end(kDamageKinds);
		json from = nullptr;
		if (hasFrom && finite(fromCp))
		{
			const Vec3 mc = cp_to_mc_pos(fromCp.x, fromCp.y, fromCp.z, off);
			from = triple_json(mc);
		}
		return json{{"t", "dmg"},
					{"frac", rounded(std::clamp(frac, 0.0, kMaxFrac), 1e4)},
					{"kind", known ? std::string(kind) : std::string("other")},
					{"from", std::move(from)}};
	}

	std::optional<json> make_npcs(std::span<const uint64_t> ids, std::span<const Vec3> feetCp,
								  std::span<const double> yawsCp, std::span<const uint8_t> vehicles,
								  std::span<const uint8_t> hostile, double off)
	{
		const size_t n = ids.size();
		if (feetCp.size() != n || yawsCp.size() != n || vehicles.size() != n || hostile.size() != n)
			return std::nullopt;
		json list = json::array();
		for (size_t i = 0; i < n; ++i)
		{
			if (!finite(feetCp[i]) || !std::isfinite(yawsCp[i]))
				continue;
			const Vec3 feet = cp_to_mc_pos(feetCp[i].x, feetCp[i].y, feetCp[i].z, off);
			list.push_back({{"id", std::to_string(ids[i])},
							{"p", triple_json(feet)},
							{"yaw", rounded(cp_yaw_to_mc_yaw(yawsCp[i]), 1e2)},
							{"kind", vehicles[i] != 0 ? "vehicle" : "human"},
							{"hostile", hostile[i] != 0}});
		}
		return json{{"t", "npcs"}, {"list", std::move(list)}};
	}

	json make_cam(int64_t frame, Vec3 position, YawPitch look, double vfov, Vec3 feet, double bodyYaw, CamFlags flags,
				  bool firstPerson, bool seat, int levelK)
	{
		return {{"t", "cam"},
				{"f", frame},
				{"p", {position.x, position.y, position.z}},
				{"r", {look.yaw, look.pitch, 0.0}},
				{"fov", vfov},
				{"fp", firstPerson},
				{"seat", seat},
				{"pl", {feet.x, feet.y, feet.z}},
				{"h", bodyYaw},
				{"cr", flags.crouched},
				{"fly", flags.flying},
				{"menu", flags.menu},
				{"air", flags.air},
				{"held", flags.held},
				{"lv", levelK}};
	}

	json make_look(double dyaw, double dpitch)
	{
		return {{"t", "look"}, {"d", {rounded(dyaw, 1e4), rounded(dpitch, 1e4)}}};
	}

	std::optional<json> make_mobdmg(int32_t id, double frac, uint64_t by, bool hasBy)
	{
		if (id < 0 || !std::isfinite(frac))
			return std::nullopt;
		return json{{"t", "mobdmg"},
					{"id", id},
					{"frac", rounded(std::clamp(frac, 0.0, kMaxFrac), 1e4)},
					{"by", hasBy ? json(std::to_string(by)) : json(nullptr)}};
	}

	std::optional<json> make_pcells(std::span<const Vec3> hitsCp, double off)
	{
		std::vector<std::array<int, 3>> cells;
		for (const Vec3 &hit : hitsCp)
		{
			const Vec3 mc = cp_to_mc_pos(hit.x, hit.y, hit.z, off);
			if (!finite(mc) || !(std::abs(mc.x) < kMaxCoord && std::abs(mc.y) < kMaxCoord && std::abs(mc.z) < kMaxCoord))
				continue;
			const std::array<int, 3> cell{int(std::floor(mc.x)), int(std::floor(mc.y)), int(std::floor(mc.z))};
			if (std::find(cells.begin(), cells.end(), cell) == cells.end())
				cells.push_back(cell);
			if (cells.size() == kMaxPcells)
				break;
		}
		if (cells.empty())
			return std::nullopt;
		json c = json::array();
		for (const auto &cell : cells)
			for (const int v : cell)
				c.push_back(v);
		return json{{"t", "pcells"}, {"c", std::move(c)}};
	}

	json make_unlead(uint64_t target)
	{
		return {{"t", "unlead"}, {"target", std::to_string(target)}};
	}

	std::optional<json> make_scaninfo(int32_t id, bool ok, uint64_t target, std::string_view name, std::string_view faction,
									  std::string_view threat, double hp, bool device, Vec3 feetCp, double width, double height,
									  double off)
	{
		if (id < 0)
			return std::nullopt;
		if (!ok)
		{
			name = faction = threat = {};
			target = 0;
			hp = width = height = 0.0;
			device = false;
			feetCp = {};
		}
		const bool knownThreat = std::find(std::begin(kThreatLevels), std::end(kThreatLevels), threat) != std::end(kThreatLevels);
		const Vec3 feet = finite(feetCp) ? cp_to_mc_pos(feetCp.x, feetCp.y, feetCp.z, off) : Vec3{};
		const auto finiteOr0 = [](double v) { return std::isfinite(v) ? v : 0.0; };
		return json{{"t", "scaninfo"},
					{"id", id},
					{"ok", ok},
					{"target", std::to_string(target)},
					{"name", wire::truncate_utf8(name, kMaxTextBytes)},
					{"faction", wire::truncate_utf8(faction, kMaxTextBytes)},
					{"threat", knownThreat ? std::string(threat) : std::string("none")},
					{"hp", rounded(std::clamp(finiteOr0(hp), 0.0, 1.0), 1e4)},
					{"device", device},
					{"p", triple_json(feet)},
					{"size", json::array({rounded(std::max(finiteOr0(width), 0.0), 1e3), rounded(std::max(finiteOr0(height), 0.0), 1e3)})}};
	}

	std::optional<json> make_hackresult(int32_t id, bool ok, std::string_view reason, double upload, std::string_view kind)
	{
		if (id < 0)
			return std::nullopt;
		json m{{"t", "hackresult"}, {"id", id}, {"ok", ok}, {"reason", ok ? std::string() : wire::truncate_utf8(reason, kMaxTextBytes)}};
		if (!ok)
			return m;
		m["upload"] = std::isfinite(upload) ? rounded(std::clamp(upload, 0.0, kMaxUploadSeconds), 1e3) : 0.0;
		if (std::find(std::begin(kHackKinds), std::end(kHackKinds), kind) != std::end(kHackKinds))
			m["kind"] = std::string(kind);
		return m;
	}

	std::optional<json> make_hackspread(int32_t id, uint64_t target, std::string_view why)
	{
		if (id < 0 || std::find(std::begin(kSpreadWhy), std::end(kSpreadWhy), why) == std::end(kSpreadWhy))
			return std::nullopt;
		return json{{"t", "hackspread"}, {"id", id}, {"target", std::to_string(target)}, {"why", std::string(why)}};
	}

	bool SpreadCounter::allow(int32_t id)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		auto it = std::ranges::find_if(m_counts, [id](const auto &entry) { return entry.first == id; });
		if (it == m_counts.end())
		{
			if (m_counts.size() >= kRemembered)
				m_counts.pop_front();
			m_counts.emplace_back(id, 0);
			it = std::prev(m_counts.end());
		}
		if (it->second >= kMaxSpreadPerId)
		{
			++m_dropped;
			return false;
		}
		++it->second;
		return true;
	}

	uint64_t SpreadCounter::dropped() const
	{
		std::lock_guard<std::mutex> lock(m_lock);
		return m_dropped;
	}

	json make_killed(uint64_t target, bool vehicle, bool hostile)
	{
		return {{"t", "killed"}, {"target", std::to_string(target)}, {"kind", vehicle ? "vehicle" : "human"}, {"hostile", hostile}};
	}

	// ---- Phase 1: the server session ----

	const char *session_state_name(SessionState state)
	{
		switch (state)
		{
		case SessionState::None: return "none";
		case SessionState::Host: return "host";
		case SessionState::Guest: return "guest";
		case SessionState::Refused: return "refused";
		case SessionState::Unknown: break;
		}
		return "";
	}

	std::optional<Session> parse_session(const json &message, std::string *why)
	{
		try
		{
			if (!message.is_object())
				throw Bad{"not an object"};
			constexpr const char *kStates[] = {"none", "host", "guest", "refused"};
			const std::string state = one_of(message, "state", kStates);
			Session out;
			out.state = state == "none" ? SessionState::None : state == "host" ? SessionState::Host
													 : state == "guest"   ? SessionState::Guest
																		  : SessionState::Refused;
			if (const auto reason = message.find("reason"); reason != message.end())
			{
				if (!reason->is_string())
					throw Bad{"reason is not a string"};
				out.reason = reason->get<std::string>();
				if (out.reason.size() > kMaxSessionReason)
					throw Bad{"reason is longer than 128 bytes"};
			}
			if (const auto perms = message.find("perms"); perms != message.end())
			{
				if (!perms->is_object())
					throw Bad{"perms is not an object"};
				for (size_t bit = 0; bit < std::size(kSessionPerms); ++bit)
					if (optional_bool(*perms, kSessionPerms[bit]))
						out.perms |= int32_t(1) << bit;
			}
			return out;
		}
		catch (const Bad &bad)
		{
			if (why)
				*why = bad.why;
			return std::nullopt;
		}
	}

	void on_session(const Session &session)
	{
		Session previous;
		{
			std::lock_guard<std::mutex> lock(g_sessionLock);
			if (session == g_session)
				return; // the client repeats its last verdict (every handshake result, reconnect, ...)
			previous = std::exchange(g_session, session);
		}
		std::string shown = session.reason; // the log gets no control characters from a peer
		for (char &c : shown)
			if (static_cast<unsigned char>(c) < 0x20)
				c = '?';
		log::info("session: %s (%s)", session_state_name(session.state), shown.c_str());

		Event e;
		e.kind = EventKind::Session;
		e.name = session_state_name(session.state);
		e.text = session.reason;
		e.extra = session.perms;
		// next() hands out the FIFO (where this event goes) before the block changes, and Session.reds resets the collision
		// registry on every non-host state: block events queued before it would come out after it and bring boxes back
		if (session.state != SessionState::Host)
			events().clear_blocks();
		events().push(e);

		const bool wasHost = previous.state == SessionState::Host;
		if (session.state == SessionState::Host && !wasHost)
			++g_groundEpoch; // a new server session: it has none of the ground sent before
		if (session.state != SessionState::Host && (wasHost || previous.state == SessionState::Unknown))
			g_sessionCleanup = true;
	}

	Session session()
	{
		std::lock_guard<std::mutex> lock(g_sessionLock);
		return g_session;
	}

	int ground_epoch()
	{
		return g_groundEpoch.load();
	}

	bool take_ground_restart(int &seen)
	{
		const int epoch = g_groundEpoch.load();
		return std::exchange(seen, epoch) != epoch;
	}

	bool take_session_cleanup()
	{
		return g_sessionCleanup.exchange(false);
	}
}
