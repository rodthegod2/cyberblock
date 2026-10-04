#include "world.hpp"
#include "log.hpp"
#include "wire.hpp"
#include <algorithm>
#include <array>
#include <atomic>
#include <cctype>
#include <cmath>
#include <cstdio>
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

	/// Phase Q: the `prefs` in effect (link thread writes, the game's threads read).
	std::mutex g_prefsLock;
	cb::world::ViewPrefs g_prefs;

	void set_prefs(const cb::world::ViewPrefs &prefs)
	{
		std::lock_guard<std::mutex> lock(g_prefsLock);
		if (prefs == g_prefs)
			return;
		g_prefs = prefs;
		cb::log::info("prefs: dist %.2f shoulder %.2f fov %.0f recenter %s vdist %.1f stepup %s debug %s", prefs.dist,
					  prefs.shoulder, prefs.fov, prefs.recenter ? "on" : "off", prefs.vdist, prefs.stepup ? "on" : "off",
					  prefs.debug ? "on" : "off");
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

	/// Phase 3: {"t":"vteleport","id","p":[x,y,z],"yaw","why"}: `p` (Minecraft feet) through the same conversion as
	/// `teleport`, `yaw` as a Cyberpunk yaw, `why` one of kVTeleportWhy as the event's name.
	Event parse_vteleport(const json &m, double off)
	{
		Event e;
		e.kind = EventKind::VTeleport;
		e.id = integer(m, "id", 0.0, kMaxRequestId);
		e.pos = cp_triple(m, "p", off);
		e.amount = cb::mc_yaw_to_cp_yaw(number(m, "yaw", -kMaxCoord, kMaxCoord));
		e.name = one_of(m, "why", cb::world::kVTeleportWhy);
		return e;
	}

	/// Phase 3: {"t":"park","on":bool}: the event's flag.
	Event parse_park(const json &m)
	{
		const auto on = m.find("on");
		if (on == m.end() || !on->is_boolean())
			throw Bad{"on is missing or not a bool"};
		Event e;
		e.kind = EventKind::Park;
		e.flag = on->get<bool>();
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
		case EventKind::Prefs: // a setting: the scripts need the last one whenever they start acting
		case EventKind::Scan:
		case EventKind::Hack:
		case EventKind::Session: // the cleanup must happen whatever the state
		case EventKind::VTeleport: // the server waits for `vteleported`: the scripts answer (or refuse) whatever the state
		case EventKind::Park:      // likewise `parked`
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
		case EventKind::Prefs: return "prefs";
		case EventKind::Session: return "session";
		case EventKind::VTeleport: return "vteleport";
		case EventKind::Park: return "park";
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
			else if (t == "vteleport")
				out.events.push_back(parse_vteleport(message, off));
			else if (t == "park")
				out.events.push_back(parse_park(message));
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
		if (event.kind == EventKind::Prefs) // a setting: one queued is enough, the newest wins (a slider sends many)
		{
			const auto queued = std::ranges::find_if(m_queue, [](const Event &e) { return e.kind == EventKind::Prefs; });
			if (queued != m_queue.end())
			{
				*queued = event;
				return;
			}
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
			set_prefs({}); // and its `prefs`
			forget_session(); // and its `session` after its handshake with the server
			return;
		}
		if (type != message.end() && *type == "prefs")
		{
			size_t bad = 0;
			std::string why;
			const ViewPrefs prefs = parse_prefs(message, view_prefs(), bad, why);
			if (bad > 0)
				note_invalid(bad, "prefs: " + why);
			set_prefs(prefs);
			Event e;
			e.kind = EventKind::Prefs;
			e.flag = prefs.stepup;
			e.amount = prefs.vdist; // View.reds picks the nearest vehicle camera preset (0: the game's own)
			events().push(e);
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
		set_prefs({});
		forget_session();
	}

	ViewPrefs parse_prefs(const json &message, const ViewPrefs &current, size_t &bad, std::string &why)
	{
		ViewPrefs out = current;
		bad = 0;
		why.clear();
		if (!message.is_object())
			return out;
		const auto wrong = [&](const char *key)
		{
			if (bad++ == 0)
				why = std::string(key) + " is not a finite number or a boolean as it should be";
		};
		// a number clamped into lo..hi; `zeroOff`: also 0 (the game's own), so -x..0 is 0 and 0..lo is raised to lo
		const auto number = [&](const char *key, double &field, double lo, double hi, bool zeroOff)
		{
			const auto it = message.find(key);
			if (it == message.end())
				return;
			const double v = it->is_number() ? it->get<double>() : NAN;
			if (!std::isfinite(v))
				return wrong(key);
			field = zeroOff && v <= 0.0 ? 0.0 : std::clamp(v, lo, hi);
		};
		const auto boolean = [&](const char *key, bool &field)
		{
			const auto it = message.find(key);
			if (it == message.end())
				return;
			if (!it->is_boolean())
				return wrong(key);
			field = it->get<bool>();
		};
		number("dist", out.dist, 1.5, 8.0, false);
		number("shoulder", out.shoulder, -1.0, 1.0, false);
		number("fov", out.fov, 50.0, 110.0, true);
		number("vdist", out.vdist, 3.0, 14.0, true);
		boolean("recenter", out.recenter);
		boolean("stepup", out.stepup);
		boolean("debug", out.debug);
		return out;
	}

	ViewPrefs view_prefs()
	{
		std::lock_guard<std::mutex> lock(g_prefsLock);
		return g_prefs;
	}

	std::optional<std::array<double, 3>> vdbg_to_send(const std::array<double, 3> &values)
	{
		return view_prefs().debug ? std::optional(values) : std::nullopt;
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
								  std::span<const uint8_t> hostile, double off, std::span<const uint8_t> dead)
	{
		return make_npcs2(ids, feetCp, yawsCp, vehicles, hostile, off, dead, NpcTags{}, nullptr, 0.0);
	}

	// ---- Phase 5 (task 5-D1) ----

	std::optional<std::string> colour_hex(int32_t packed, ColourFormat format)
	{
		if (packed < 0 || packed > 0xFFFFFF)
			return std::nullopt;
		const double first = (packed >> 16) & 0xFF, second = (packed >> 8) & 0xFF, third = packed & 0xFF;
		double r = first / 255.0, g = second / 255.0, b = third / 255.0;
		if (format == ColourFormat::Hsb)
		{
			const double hue = first * 360.0 / 256.0 / 60.0; // sector 0..6
			const double sat = second / 255.0, val = third / 255.0;
			const double f = hue - std::floor(hue);
			const double p = val * (1.0 - sat), q = val * (1.0 - sat * f), t = val * (1.0 - sat * (1.0 - f));
			switch (static_cast<int>(hue) % 6)
			{
				case 0: r = val, g = t, b = p; break;
				case 1: r = q, g = val, b = p; break;
				case 2: r = p, g = val, b = t; break;
				case 3: r = p, g = q, b = val; break;
				case 4: r = t, g = p, b = val; break;
				default: r = val, g = p, b = q; break;
			}
		}
		char text[8];
		std::snprintf(text, sizeof text, "#%02X%02X%02X", static_cast<int>(std::lround(r * 255.0)),
					  static_cast<int>(std::lround(g * 255.0)), static_cast<int>(std::lround(b * 255.0)));
		return std::string(text);
	}

	std::optional<std::string> figure_tag(std::string_view text)
	{
		if (text.empty() || text.size() > kMaxFigureTag)
			return std::nullopt;
		for (const char c : text)
			if (!std::isalnum(static_cast<unsigned char>(c)) && c != '_' && c != '.' && c != '/' && c != '-')
				return std::nullopt;
		return std::string(text);
	}

	std::vector<int> NpcMotion::update(std::span<const uint64_t> ids, std::span<const Vec3> feetCp,
									   std::span<const uint8_t> vehicles, double nowSeconds)
	{
		std::vector<int> bits(ids.size(), 0);
		std::map<uint64_t, Sample> next;
		for (size_t i = 0; i < ids.size() && i < feetCp.size() && i < vehicles.size(); ++i)
		{
			if (vehicles[i] != 0 || !finite(feetCp[i]))
				continue;
			const auto old = m_known.find(ids[i]);
			Sample sample{feetCp[i], nowSeconds, 0};
			if (old != m_known.end())
			{
				const double dt = nowSeconds - old->second.at;
				if (dt < kMinMotionInterval && dt >= 0.0)
				{
					sample = old->second; // the same moment again: keep the old sample, so the next delta spans real time
				}
				else if (dt > 0.0 && dt <= kMaxMotionInterval)
				{
					const double speed = std::hypot(feetCp[i].x - old->second.feet.x, feetCp[i].y - old->second.feet.y) / dt;
					const bool wasWalk = (old->second.bits & kAnimWalk) != 0, wasRun = (old->second.bits & kAnimRun) != 0;
					if (speed <= kTeleportSpeed)
					{
						if (speed >= (wasRun ? kRunOff : kRunOn))
							sample.bits = kAnimRun;
						else if (speed >= (wasWalk || wasRun ? kWalkOff : kWalkOn))
							sample.bits = kAnimWalk;
					}
				}
			}
			bits[i] = sample.bits;
			next[ids[i]] = sample;
		}
		m_known = std::move(next);
		return bits;
	}

	std::optional<json> make_npcs2(std::span<const uint64_t> ids, std::span<const Vec3> feetCp,
								   std::span<const double> yawsCp, std::span<const uint8_t> vehicles,
								   std::span<const uint8_t> hostile, double off, std::span<const uint8_t> dead,
								   const NpcTags &tags, NpcMotion *motion, double nowSeconds)
	{
		const size_t n = ids.size();
		if (feetCp.size() != n || yawsCp.size() != n || vehicles.size() != n || hostile.size() != n ||
			(!dead.empty() && dead.size() != n))
			return std::nullopt;
		for (const size_t size : {tags.roles.size(), tags.factions.size(), tags.archetypes.size(), tags.vclass.size(),
								  tags.genders.size(), tags.anims.size(), tags.colours.size(), tags.hp.size()})
			if (size != 0 && size != n)
				return std::nullopt;
		const std::vector<int> motionBits =
			motion != nullptr ? motion->update(ids, feetCp, vehicles, nowSeconds) : std::vector<int>(n, 0);
		json list = json::array();
		for (size_t i = 0; i < n; ++i)
		{
			if (!finite(feetCp[i]) || !std::isfinite(yawsCp[i]))
				continue;
			const Vec3 feet = cp_to_mc_pos(feetCp[i].x, feetCp[i].y, feetCp[i].z, off);
			const bool vehicle = vehicles[i] != 0;
			json entry = {{"id", std::to_string(ids[i])},
						  {"p", triple_json(feet)},
						  {"yaw", rounded(cp_yaw_to_mc_yaw(yawsCp[i]), 1e2)},
						  {"kind", vehicle ? "vehicle" : "human"},
						  {"hostile", hostile[i] != 0}};
			const bool gone = !dead.empty() && dead[i] != 0;
			if (gone)
				entry["dead"] = true;
			const auto put = [&](const char *key, std::span<const std::string> from)
			{
				if (!from.empty())
					if (const auto tag = figure_tag(from[i]))
						entry[key] = *tag;
			};
			put("role", tags.roles);
			put("fac", tags.factions);
			put("arch", tags.archetypes);
			// vehicles have no gender and no pose: g and anim are 0 whatever the scripts say
			if (!tags.genders.empty())
				entry["g"] = !vehicle && (tags.genders[i] == 1 || tags.genders[i] == 2) ? tags.genders[i] : 0;
			if (!tags.anims.empty() || motion != nullptr)
			{
				int anim = tags.anims.empty() || tags.anims[i] < 0 || vehicle ? 0 : tags.anims[i] & kAnimMask; // negative: invalid
				anim &= ~(kAnimWalk | kAnimRun); // the scripts cannot read them (Phase 0 f); only the DLL sets them
				if (!vehicle && !gone && (anim & (kAnimRagdoll | kAnimDead)) == 0)
					anim |= motionBits[i];
				entry["anim"] = anim;
			}
			if (vehicle)
			{
				put("vc", tags.vclass);
				if (!tags.colours.empty())
					if (const auto colour = colour_hex(tags.colours[i]))
						entry["col"] = *colour;
			}
			if (!tags.hp.empty() && std::isfinite(tags.hp[i]) && tags.hp[i] >= 0.0)
				entry["hp"] = rounded(std::min(tags.hp[i], 1.0), 1e3);
			list.push_back(std::move(entry));
		}
		return json{{"t", "npcs"}, {"list", std::move(list)}};
	}

	NpcTagSummary summarize_npcs(const json &list)
	{
		NpcTagSummary s;
		if (!list.is_array())
			return s;
		for (const json &e : list)
		{
			if (e.value("kind", "") == "vehicle")
			{
				++s.vehicles;
				s.withColour += e.contains("col") ? 1 : 0;
				continue;
			}
			++s.people;
			s.withFaction += e.contains("fac") ? 1 : 0;
			s.withRole += e.contains("role") ? 1 : 0;
			s.withArchetype += e.contains("arch") ? 1 : 0;
			const int g = e.value("g", 0);
			(g == 1 ? s.male : g == 2 ? s.female : s.unknownGender) += 1;
			const int anim = e.value("anim", 0);
			s.walking += (anim & kAnimWalk) != 0 ? 1 : 0;
			s.running += (anim & kAnimRun) != 0 ? 1 : 0;
		}
		return s;
	}

	json make_cam(int64_t frame, Vec3 position, YawPitch look, double vfov, Vec3 feet, double bodyYaw, CamFlags flags,
				  bool firstPerson, bool seat, int levelK, const std::optional<std::array<double, 3>> &vdbg)
	{
		json cam = {{"t", "cam"},
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
		if (vdbg)
		{
			const auto clamp = [](double v, double hi, double scale)
			{ return std::isfinite(v) ? rounded(std::clamp(v, 0.0, hi), scale) : 0.0; };
			cam["vdbg"] = {clamp((*vdbg)[0], 100.0, 1e3), clamp((*vdbg)[1], 100.0, 1e3), clamp((*vdbg)[2], 60000.0, 1e1)};
		}
		return cam;
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

	// ---- Phase 3: env, vteleported, parked ----

	const char *weather_from_preset(std::string_view preset, bool *known)
	{
		if (known)
			*known = true;
		if (preset.empty())
			return "other";
		std::string lower(preset);
		std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return char(std::tolower(c)); });
		// in order: "sandstorm" before "storm", rain words before fog and smog ("toxic_rain" is rain)
		static constexpr std::pair<const char *, const char *> kWords[] = {
			{"sandstorm", "sandstorm"}, {"storm", "storm"},  {"thunder", "storm"}, {"rain", "rain"},      {"fog", "fog"},
			{"pollution", "smog"},      {"smog", "smog"},    {"toxic", "smog"},    {"cloud", "cloudy"},   {"overcast", "cloudy"},
			{"sunny", "clear"},         {"clear", "clear"},  {"blue", "clear"}};
		for (const auto &[word, weather] : kWords)
			if (lower.find(word) != std::string::npos)
				return weather;
		if (known)
			*known = false;
		return "other";
	}

	std::optional<json> make_env(int32_t hour, int32_t minute, std::string_view preset, bool *known)
	{
		if (hour < 0 || hour > 23 || minute < 0 || minute > 59)
			return std::nullopt;
		return json{{"t", "env"}, {"time", {hour, minute}}, {"weather", weather_from_preset(preset, known)}};
	}

	bool valid_teleport_reason(std::string_view reason)
	{
		if (reason.size() > kMaxTextBytes ||
			std::any_of(reason.begin(), reason.end(), [](char c) { return static_cast<unsigned char>(c) < 0x20 || c == 0x7f; }))
			return false;
		constexpr std::string_view kOpen = "blocked (";
		if (reason.starts_with(kOpen))
			return reason.size() > kOpen.size() + 1 && reason.back() == ')';
		return reason.empty() || reason == "no ground" || reason == "timeout";
	}

	/// The reason a `vteleported` / `parked` carries: "" when ok, a too long "blocked (<why>)" shortened to fit; nullopt
	/// when it breaks the contract.
	static std::optional<std::string> answer_reason(bool ok, std::string_view reason)
	{
		if (ok)
			return std::string();
		constexpr std::string_view kOpen = "blocked (";
		std::string out(reason);
		if (reason.size() > kMaxTextBytes && reason.starts_with(kOpen) && reason.ends_with(')'))
			out = std::string(kOpen) +
				  wire::truncate_utf8(reason.substr(kOpen.size(), reason.size() - kOpen.size() - 1), kMaxTextBytes - kOpen.size() - 1) +
				  ")";
		if (out.empty() || !valid_teleport_reason(out))
			return std::nullopt;
		return out;
	}

	std::optional<json> make_vteleported(int32_t id, bool ok, std::string_view reason, Vec3 feetCp, double off)
	{
		const auto why = answer_reason(ok, reason);
		if (id < 0 || !finite(feetCp) || !why)
			return std::nullopt;
		return json{{"t", "vteleported"},
					{"id", id},
					{"ok", ok},
					{"reason", *why},
					{"p", triple_json(cp_to_mc_pos(feetCp.x, feetCp.y, feetCp.z, off))}};
	}

	std::optional<json> make_parked(bool on, bool ok, std::string_view reason)
	{
		const auto why = answer_reason(ok, reason);
		if (!why)
			return std::nullopt;
		return json{{"t", "parked"}, {"on", on}, {"ok", ok}, {"reason", *why}};
	}

	std::optional<json> parked_from_log(std::string_view line)
	{
		constexpr std::string_view kRefused = "park: on refused (", kOn = "park: on (", kOff = "park: off (";
		if (line.starts_with(kRefused) && line.ends_with(')'))
			return make_parked(true, false, "blocked (" + std::string(line.substr(kRefused.size())));
		if (line.starts_with(kOn) && line.ends_with(')'))
			return make_parked(true, true, "");
		if (line.starts_with(kOff) && line.ends_with(')'))
			return make_parked(false, true, "");
		return std::nullopt;
	}
}
