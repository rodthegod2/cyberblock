#include "modes.hpp"
#include "link.hpp"
#include "log.hpp"
#include "probes.hpp"
#include "screenfx.hpp"
#include "wire.hpp"
#include <algorithm>
#include <atomic>
#include <iterator>
#include <mutex>

namespace
{
	using nlohmann::json;
	using cb::modes::Request;

	constexpr double kMaxRequestId = 2147483647.0; // scripts get request ids as Int32

	std::mutex g_glitchLock;
	cb::screenfx::Glitch g_glitch;

	bool is_type(const json &message, const char *type)
	{
		if (!message.is_object())
			return false;
		const auto t = message.find("t");
		return t != message.end() && *t == type;
	}

	Request rejected(const char *type, const cb::wire::Bad &bad)
	{
		Request out;
		out.status = Request::Status::Rejected;
		out.reason = std::string(type) + ": " + bad.why;
		return out;
	}

	bool faction_char(char c)
	{
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
	}

	/// `faction`: a string of minLength..32 characters of [A-Za-z0-9_]; "" when absent and minLength is 0.
	std::string faction(const json &message, size_t minLength)
	{
		const auto it = message.find("faction");
		if (it == message.end() && minLength == 0)
			return {};
		if (it == message.end() || !it->is_string())
			throw cb::wire::Bad{"faction is missing or not a string"};
		const std::string &text = it->get_ref<const std::string &>();
		if (text.size() < minLength || text.size() > cb::modes::kMaxFactionBytes ||
			!std::all_of(text.begin(), text.end(), faction_char))
			throw cb::wire::Bad{"faction is not [A-Za-z0-9_]{" + std::to_string(minLength) + ",32}"};
		return text;
	}

	bool required_bool(const json &message, const char *key)
	{
		const auto it = message.find(key);
		if (it == message.end() || !it->is_boolean())
			throw cb::wire::Bad{std::string(key) + " is missing or not a bool"};
		return it->get<bool>();
	}

	void glitch(double seconds)
	{
		std::lock_guard<std::mutex> lock(g_glitchLock);
		g_glitch.start(seconds, cb::steady_seconds());
	}
}

namespace cb::modes
{
	Request parse_spawn(const json &message, double off)
	{
		Request out;
		if (!is_type(message, "spawn"))
			return out;
		try
		{
			const int32_t id = wire::integer(message, "id", 0.0, kMaxRequestId);
			const auto rec = message.find("rec");
			if (rec == message.end() || !rec->is_string())
				throw wire::Bad{"rec is missing or not a string"};
			const std::string &kind = rec->get_ref<const std::string &>();
			out.event.pos = wire::cp_triple(message, "p", off);
			out.event.amount = wire::integer(message, "n", 1.0, kMaxSpawnCount);
			out.event.flag = required_bool(message, "hostile");
			out.event.text = faction(message, 0);
			if (std::find(std::begin(kSpawnRecords), std::end(kSpawnRecords), std::string_view(kind)) == std::end(kSpawnRecords))
			{
				out = {};
				out.status = Request::Status::Unsupported;
				out.reply = make_spawnresult(id, false, 0, "unsupported");
				out.reason = "rec " + wire::truncate_utf8(kind, 32) + " is not supported";
				return out;
			}
			out.status = Request::Status::Event;
			out.event.kind = world::EventKind::Spawn;
			out.event.id = id;
			out.event.name = kind;
		}
		catch (const wire::Bad &bad)
		{
			return rejected("spawn", bad);
		}
		return out;
	}

	Request parse_heat(const json &message)
	{
		Request out;
		if (!is_type(message, "heat"))
			return out;
		try
		{
			out.event.amount = wire::integer(message, "tier", 0.0, kMaxHeatTier);
			out.event.kind = world::EventKind::Heat;
			out.status = Request::Status::Event;
		}
		catch (const wire::Bad &bad)
		{
			return rejected("heat", bad);
		}
		return out;
	}

	Request parse_attitude(const json &message)
	{
		Request out;
		if (!is_type(message, "attitude"))
			return out;
		try
		{
			out.event.name = faction(message, 1);
			out.event.text = wire::one_of(message, "toward", kAttitudes);
			out.event.amount = out.event.text == "friendly" ? 1.0 : out.event.text == "hostile" ? -1.0 : 0.0;
			out.event.kind = world::EventKind::Attitude;
			out.status = Request::Status::Event;
		}
		catch (const wire::Bad &bad)
		{
			return rejected("attitude", bad);
		}
		return out;
	}

	Request parse_glitch(const json &message)
	{
		Request out;
		if (!is_type(message, "glitch"))
			return out;
		try
		{
			out.event.amount = wire::number(message, "seconds", 0.0, screenfx::kMaxGlitchSeconds);
			out.event.kind = world::EventKind::Glitch;
			out.status = Request::Status::Event;
		}
		catch (const wire::Bad &bad)
		{
			return rejected("glitch", bad);
		}
		return out;
	}

	std::optional<json> make_spawnresult(int32_t id, bool ok, int32_t n, std::string_view reason)
	{
		if (id < 0)
			return std::nullopt;
		std::string why;
		if (!ok)
		{
			why = wire::truncate_utf8(reason, kMaxReasonBytes);
			std::erase_if(why, [](char c) { return static_cast<unsigned char>(c) < 0x20 || c == 0x7F; });
			if (why.empty())
				why = "failed";
		}
		return json{{"t", "spawnresult"}, {"id", id}, {"ok", ok}, {"n", std::clamp(n, 0, kMaxSpawnCount)}, {"reason", why}};
	}

	std::optional<json> expired_spawn(const world::Event &event, double now)
	{
		if (event.kind != world::EventKind::Spawn || event.received <= 0.0 || now - event.received <= kMaxSpawnQueueSeconds)
			return std::nullopt;
		return make_spawnresult(event.id, false, 0, "expired");
	}

	void on_minecraft_message(const json &message)
	{
		const auto type = message.find("t");
		if (type == message.end() || !type->is_string())
			return;
		const std::string &t = type->get_ref<const std::string &>();
		if (t == "hello")
		{
			glitch_clear(); // a new Minecraft
			return;
		}
		if (t == "session")
		{
			// world::on_minecraft_message ran first and took it: leaving the host role ends the glitch
			const world::SessionState state = world::session().state;
			if (state != world::SessionState::Host && state != world::SessionState::Unknown)
				glitch_clear();
			return;
		}
		Request request;
		if (t == "spawn")
			request = parse_spawn(message, world::level_offset(world::level_k()));
		else if (t == "heat")
			request = parse_heat(message);
		else if (t == "attitude")
			request = parse_attitude(message);
		else if (t == "glitch")
			request = parse_glitch(message);
		else
			return;

		static std::atomic<int> logged{0};
		constexpr int kMaxLogged = 50;
		request.event.received = cb::steady_seconds(); // a spawn too old when the scripts pull it is expired_spawn
		const world::Event &e = request.event;
		switch (request.status)
		{
		case Request::Status::Ignored:
			return;
		case Request::Status::Rejected:
			world::note_invalid(1, request.reason);
			return;
		case Request::Status::Unsupported:
			if (request.reply)
				link().send(*request.reply);
			if (logged++ < kMaxLogged)
				log::info("spawn: %d refused: %s (spawnresult unsupported)", (*request.reply)["id"].get<int>(),
						  request.reason.c_str());
			return;
		case Request::Status::Event:
			break;
		}

		if (e.kind == world::EventKind::Glitch)
		{
			const world::SessionState state = world::session().state;
			if (state == world::SessionState::Host || state == world::SessionState::Unknown)
				glitch(e.amount);
		}
		world::events().push(e);
		if (logged++ >= kMaxLogged)
			return;
		switch (e.kind)
		{
		case world::EventKind::Spawn:
			log::info("spawn: %d %.0f x %s (%s%s%s) at cp (%.1f, %.1f, %.1f) handed to the scripts", e.id, e.amount,
					  e.name.c_str(), e.flag ? "hostile" : "not hostile", e.text.empty() ? "" : ", ", e.text.c_str(), e.pos.x,
					  e.pos.y, e.pos.z);
			break;
		case world::EventKind::Heat:
			log::info("heat: tier %.0f handed to the scripts", e.amount);
			break;
		case world::EventKind::Attitude:
			log::info("attitude: %s %s toward V handed to the scripts", e.name.c_str(), e.text.c_str());
			break;
		case world::EventKind::Glitch:
			log::info("glitch: %.1f s (Reboot Optics; cb_glitch %s)", e.amount, e.amount > 0.0 ? "on" : "ends");
			break;
		default:
			break;
		}
	}

	void on_link_dropped()
	{
		glitch_clear();
	}

	std::array<float, 4> glitch_uniform(double now, bool menu)
	{
		std::lock_guard<std::mutex> lock(g_glitchLock);
		return g_glitch.uniform(now, menu);
	}

	void glitch_clear()
	{
		std::lock_guard<std::mutex> lock(g_glitchLock);
		g_glitch.clear();
	}
}
