// The pure half of the Cyberpunk UI bridge (ui.hpp): section 1.1 builders, send gates, the reply builder and the UI
// action queue. No RED4ext here: cb_tests builds this file (tests/test_ui.cpp).
#include "ui.hpp"
#include "wire.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <utility>

using nlohmann::json;

namespace cb::ui
{
	namespace
	{
		/// Rounds a non-negative or negative finite double for the wire (seconds, speeds).
		double round_to(double v, double scale)
		{
			return wire::rounded(v, scale);
		}

		double clamp_seconds(double secs, double hi)
		{
			if (!std::isfinite(secs) || secs <= 0.0)
				return 0.0;
			return round_to(std::min(secs, hi), 100.0);
		}

		int64_t clamp_int(int64_t v, int64_t lo, int64_t hi)
		{
			return std::clamp(v, lo, hi);
		}

		std::string trim_spaces(std::string text)
		{
			const size_t first = text.find_first_not_of(' ');
			if (first == std::string::npos)
				return {};
			const size_t last = text.find_last_not_of(' ');
			return text.substr(first, last - first + 1);
		}

		json position(const Vec3 &cp, double off)
		{
			return wire::triple_json(cp_to_mc_pos(cp.x, cp.y, cp.z, off));
		}
	}

	std::string clean_text(std::string_view text, size_t maxBytes)
	{
		static constexpr uint32_t kMinForLength[] = {0, 0, 0x80, 0x800, 0x10000}; // shortest encoding of each length
		std::string out;
		out.reserve(std::min(text.size(), maxBytes + 4));
		size_t i = 0;
		while (i < text.size())
		{
			const unsigned char lead = static_cast<unsigned char>(text[i]);
			if (lead < 0x80)
			{
				if (lead == '\t' || lead == '\n' || lead == '\r')
					out += ' ';
				else if (lead >= 0x20 && lead != 0x7F)
					out += char(lead);
				++i;
				continue;
			}
			size_t length = 0;
			uint32_t cp = 0;
			if ((lead & 0xE0) == 0xC0)
			{
				length = 2;
				cp = lead & 0x1F;
			}
			else if ((lead & 0xF0) == 0xE0)
			{
				length = 3;
				cp = lead & 0x0F;
			}
			else if ((lead & 0xF8) == 0xF0)
			{
				length = 4;
				cp = lead & 0x07;
			}
			else
			{
				++i; // a stray continuation byte or an invalid lead byte
				continue;
			}
			bool complete = i + length <= text.size();
			for (size_t k = 1; complete && k < length; ++k)
			{
				const unsigned char next = static_cast<unsigned char>(text[i + k]);
				if ((next & 0xC0) != 0x80)
					complete = false;
				else
					cp = (cp << 6) | (next & 0x3F);
			}
			if (!complete)
			{
				++i; // a broken sequence: drop its lead byte, look at the rest again
				continue;
			}
			const bool valid = cp >= kMinForLength[length] && cp <= 0x10FFFF && !(cp >= 0xD800 && cp <= 0xDFFF);
			const bool control = cp >= 0x80 && cp <= 0x9F; // C1
			if (valid && !control)
				out.append(text.substr(i, length));
			i += length;
		}
		return trim_spaces(wire::truncate_utf8(trim_spaces(std::move(out)), maxBytes));
	}

	// ---- builders --------------------------------------------------------------------------------------------------------

	json make_stats(int64_t money, int64_t wanted, std::string_view district, int64_t hour, int64_t minute, double speedKmh)
	{
		const double speed = (!std::isfinite(speedKmh) || speedKmh < 0.0) ? -1.0 : round_to(std::min(speedKmh, 1000.0), 10.0);
		return {{"t", "stats"},
				{"money", std::max<int64_t>(money, 0)},
				{"wanted", clamp_int(wanted, 0, 5)},
				{"district", clean_text(district, kNameBytes)},
				{"time", json::array({clamp_int(hour, 0, 23), clamp_int(minute, 0, 59)})},
				{"speed", speed}};
	}

	json make_quest(std::string_view title, std::string_view kind, std::span<const Objective> objectives,
					std::optional<Vec3> wpCp, double off)
	{
		json list = json::array();
		for (const Objective &o : objectives)
		{
			if (list.size() >= kMaxObjectives)
				break;
			list.push_back({{"text", clean_text(o.text, kTextBytes)},
							{"cur", std::max<int64_t>(o.cur, 0)},
							{"tot", std::max<int64_t>(o.tot, 0)},
							{"opt", o.optional},
							{"on", o.on}});
		}
		json wp = nullptr;
		if (wpCp && wire::finite(*wpCp))
			wp = position(*wpCp, off);
		return {{"t", "quest"},
				{"title", clean_text(title, kTitleBytes)},
				{"kind", known_or(kind, kQuestKinds, "other")},
				{"obj", std::move(list)},
				{"wp", std::move(wp)}};
	}

	json make_pins(std::span<const Pin> pins, double off)
	{
		json list = json::array();
		for (const Pin &pin : pins)
		{
			if (list.size() >= kMaxPins)
				break;
			if (!wire::finite(pin.posCp))
				continue;
			list.push_back({{"p", position(pin.posCp, off)}, {"kind", known_or(pin.kind, kPinKinds, "other")}, {"tr", pin.tracked}});
		}
		return {{"t", "pins"}, {"list", std::move(list)}};
	}

	json make_prompt(std::string_view title, std::span<const PromptEntry> entries)
	{
		json list = json::array();
		for (const PromptEntry &e : entries)
		{
			if (list.size() >= kMaxPromptEntries)
				break;
			std::string text = clean_text(e.text, kTitleBytes);
			if (text.empty())
				continue;
			list.push_back({{"text", std::move(text)}, {"key", clean_text(e.key, kKeyBytes)}, {"hold", e.hold}, {"inactive", e.inactive}});
		}
		return {{"t", "prompt"}, {"title", clean_text(title, kTitleBytes)}, {"list", std::move(list)}};
	}

	json make_choices(std::span<const ChoiceHub> hubs, std::span<const ChoiceEntry> entries, int32_t activeHub,
					  int32_t selected, double secondsLeft)
	{
		json out = json::array();
		const size_t hubCount = std::min(hubs.size(), kMaxChoiceHubs);
		for (size_t h = 0; h < hubCount; ++h)
			out.push_back({{"id", hubs[h].id}, {"title", clean_text(hubs[h].title, kTitleBytes)}, {"list", json::array()}});
		size_t total = 0;
		for (const ChoiceEntry &e : entries)
		{
			if (total >= kMaxChoiceEntries)
				break;
			if (e.hub < 0 || size_t(e.hub) >= hubCount)
				continue;
			json flags = json::array();
			for (size_t bit = 0; bit < std::size(kChoiceFlags); ++bit)
				if (e.flags & (1 << bit))
					flags.push_back(kChoiceFlags[bit]);
			out[size_t(e.hub)]["list"].push_back({{"text", clean_text(e.text, kTextBytes)}, {"f", std::move(flags)}});
			++total;
		}
		const double left = (!std::isfinite(secondsLeft) || secondsLeft < 0.0) ? -1.0 : round_to(std::min(secondsLeft, 3600.0), 100.0);
		return {{"t", "choices"}, {"hubs", std::move(out)}, {"active", activeHub}, {"sel", std::max(selected, -1)}, {"left", left}};
	}

	json make_subtitle(bool show, std::string_view key, std::string_view who, std::string_view text, double secs)
	{
		return {{"t", "sub"},
				{"show", show},
				{"key", clean_text(key, kNameBytes)},
				{"who", clean_text(who, kNameBytes)},
				{"text", clean_text(text, kTextBytes)},
				{"secs", clamp_seconds(secs, kMaxSeconds)}};
	}

	std::optional<json> make_call(std::string_view phase, std::string_view who, bool audio, bool reject)
	{
		const std::string_view known = known_or(phase, kCallPhases, "");
		if (known.empty())
			return std::nullopt;
		return json{{"t", "call"}, {"phase", known}, {"who", clean_text(who, kNameBytes)}, {"audio", audio}, {"reject", reject}};
	}

	json make_notify(std::string_view kind, std::string_view title, std::string_view text, double secs)
	{
		return {{"t", "notify"},
				{"kind", known_or(kind, kNotifyKinds, "info")},
				{"title", clean_text(title, kTitleBytes)},
				{"text", clean_text(text, kTextBytes)},
				{"secs", clamp_seconds(secs, kMaxSeconds)}};
	}

	std::optional<json> make_screen_open(std::string_view name)
	{
		const std::string_view known = known_or(name, kScreens, "");
		if (known.empty())
			return std::nullopt;
		return json{{"t", "screen"}, {"open", known}};
	}

	std::optional<json> make_screen_pass(std::string_view name)
	{
		const std::string_view known = known_or(name, kPassNames, "");
		if (known.empty() && !name.empty())
			return std::nullopt;
		return json{{"t", "screen"}, {"pass", known}};
	}

	// ---- reply ------------------------------------------------------------------------------------------------------------

	bool ReplyBuilder::begin(std::string_view kind, int32_t id)
	{
		const std::string_view known = known_or(kind, kReplyKinds, "");
		m_active = !known.empty();
		m_kind = known;
		m_id = id;
		m_groups = json::array();
		m_pins = json::array();
		m_lastGroupKept = false;
		m_lines = 0;
		m_dropped = 0;
		m_bytes = json{{"t", "reply"}, {"kind", m_kind}, {"id", m_id}, {"groups", json::array()}, {"pins", json::array()}}.dump().size();
		return m_active;
	}

	// Each entry is counted with one separator byte, so the running total never undercounts the message.
	bool ReplyBuilder::fits(size_t bytes) const
	{
		return m_bytes + bytes <= kMaxBytes;
	}

	void ReplyBuilder::group(uint32_t hash, std::string_view title, std::string_view kind, bool tracked, bool done)
	{
		if (!m_active)
			return;
		m_lastGroupKept = false;
		if (m_groups.size() >= kMaxGroups)
		{
			++m_dropped;
			return;
		}
		json g = {{"hash", hash},
				  {"title", clean_text(title, kTitleBytes)},
				  {"kind", known_or(kind, kGroupKinds, "other")},
				  {"tracked", tracked},
				  {"done", done},
				  {"lines", json::array()}};
		const size_t bytes = g.dump().size() + 1;
		if (!fits(bytes))
		{
			++m_dropped;
			return;
		}
		m_groups.push_back(std::move(g));
		m_bytes += bytes;
		m_lastGroupKept = true;
	}

	void ReplyBuilder::line(uint32_t hash, std::string_view text, int32_t state)
	{
		if (!m_active)
			return;
		if (!m_lastGroupKept || m_lines >= kMaxLines)
		{
			++m_dropped;
			return;
		}
		const int32_t top = m_kind == "vendor" ? std::numeric_limits<int32_t>::max() : 2;
		json l = {{"hash", hash}, {"text", clean_text(text, kTextBytes)}, {"state", std::clamp(state, 0, top)}};
		const size_t bytes = l.dump().size() + 1;
		if (!fits(bytes))
		{
			++m_dropped;
			return;
		}
		m_groups.back()["lines"].push_back(std::move(l));
		++m_lines;
		m_bytes += bytes;
	}

	void ReplyBuilder::pin(Vec3 posCp, std::string_view kind, std::string_view name, int32_t index, double off, std::string_view district)
	{
		if (!m_active)
			return;
		if (m_pins.size() >= kMaxPins || !wire::finite(posCp))
		{
			++m_dropped;
			return;
		}
		json p = {{"p", position(posCp, off)},
				  {"kind", known_or(kind, kPinKinds, "other")},
				  {"name", clean_text(name, kNameBytes)},
				  {"i", std::max(index, -1)}};
		if (const std::string d = clean_text(district, kNameBytes); !d.empty())
			p["d"] = d;
		const size_t bytes = p.dump().size() + 1;
		if (!fits(bytes))
		{
			++m_dropped;
			return;
		}
		m_pins.push_back(std::move(p));
		m_bytes += bytes;
	}

	std::optional<ReplyBuilder::Result> ReplyBuilder::end()
	{
		if (!m_active)
			return std::nullopt;
		m_active = false;
		Result r;
		r.groups = m_groups.size();
		r.lines = m_lines;
		r.pins = m_pins.size();
		r.dropped = m_dropped;
		r.message = {{"t", "reply"}, {"kind", m_kind}, {"id", m_id}, {"groups", std::move(m_groups)}, {"pins", std::move(m_pins)}};
		r.bytes = r.message.dump().size();
		m_groups = json::array();
		m_pins = json::array();
		return r;
	}

	// ---- gates ------------------------------------------------------------------------------------------------------------

	bool Gate::admit(const std::string &full, const std::string &urgent, double now, double minInterval)
	{
		if (m_any && full == m_full)
			return false;
		if (m_any && urgent == m_urgent && now - m_time < minInterval)
			return false;
		m_any = true;
		m_full = full;
		m_urgent = urgent;
		m_time = now;
		return true;
	}

	bool Gate::admit_event(const std::string &full, double now, double window)
	{
		if (m_any && full == m_full && now - m_time < window)
			return false;
		m_any = true;
		m_full = full;
		m_time = now;
		return true;
	}

	void Gate::reset()
	{
		m_any = false;
		m_full.clear();
		m_urgent.clear();
		m_time = 0.0;
	}

	// ---- uiact and the queue ----------------------------------------------------------------------------------------------

	std::optional<UiAction> parse_uiact(const json &m, std::string *why)
	{
		try
		{
			if (!m.is_object())
				throw wire::Bad{"not an object"};
			UiAction action;
			action.a = wire::one_of(m, "a", kUiActs);
			if (const auto it = m.find("arg"); it != m.end() && !it->is_null())
			{
				if (!it->is_string())
					throw wire::Bad{"arg is not a string"};
				const std::string &arg = it->get_ref<const std::string &>();
				if (arg.size() > kNameBytes)
					throw wire::Bad{"arg is longer than 64 bytes"};
				action.arg = clean_text(arg, kNameBytes);
			}
			if (m.contains("hash"))
				action.hash = uint32_t(wire::number(m, "hash", 0.0, 4294967295.0));
			if (m.contains("hash") && double(action.hash) != m["hash"].get<double>())
				throw wire::Bad{"hash is not a whole number"};
			if ((action.a == "buy" || action.a == "sell") && action.hash == 0)
				throw wire::Bad{"hash (the item) is missing or 0"};
			if (m.contains("id"))
				action.id = wire::integer(m, "id", double(std::numeric_limits<int32_t>::min()), double(std::numeric_limits<int32_t>::max()));
			return action;
		}
		catch (const wire::Bad &bad)
		{
			if (why)
				*why = "uiact: " + bad.why;
			return std::nullopt;
		}
	}

	bool UiActions::push(const UiAction &action, bool on)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		if (!on)
		{
			++m_dropped;
			return false;
		}
		const size_t before = m_dropped;
		enqueue(action);
		return m_dropped == before;
	}

	void UiActions::enqueue(UiAction action)
	{
		const auto isEdge = [](const UiAction &a) { return a.a == "pause" || a.a == "resume"; };
		if (m_queue.size() >= kMax)
		{
			if (!isEdge(action))
			{
				++m_dropped;
				return;
			}
			const auto victim = std::find_if(m_queue.begin(), m_queue.end(), [&](const UiAction &a) { return !isEdge(a); });
			m_queue.erase(victim != m_queue.end() ? victim : m_queue.begin());
			++m_dropped;
		}
		m_queue.push_back(std::move(action));
	}

	void UiActions::set_pause(bool want)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		if (want == m_paused)
			return;
		m_paused = want;
		enqueue({want ? "pause" : "resume"});
	}

	bool UiActions::reset_pause()
	{
		std::lock_guard<std::mutex> lock(m_lock);
		const bool was = std::exchange(m_paused, false);
		if (m_queue.empty() || m_queue.back().a != "resume")
			enqueue({"resume"});
		return was;
	}

	std::optional<UiAction> UiActions::pop(bool on)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		while (!m_queue.empty())
		{
			UiAction action = std::move(m_queue.front());
			m_queue.pop_front();
			if (!on && action.a != "resume")
			{
				++m_dropped;
				continue;
			}
			return action;
		}
		return std::nullopt;
	}

	bool UiActions::paused() const
	{
		std::lock_guard<std::mutex> lock(m_lock);
		return m_paused;
	}

	bool UiActions::empty() const
	{
		std::lock_guard<std::mutex> lock(m_lock);
		return m_queue.empty();
	}

	size_t UiActions::size() const
	{
		std::lock_guard<std::mutex> lock(m_lock);
		return m_queue.size();
	}

	uint64_t UiActions::dropped() const
	{
		std::lock_guard<std::mutex> lock(m_lock);
		return m_dropped;
	}
}
