#include "rhack.hpp"
#include "link.hpp"
#include "log.hpp"
#include "wire.hpp"
#include <algorithm>
#include <atomic>
#include <iterator>

namespace
{
	using nlohmann::json;
	using cb::rhack::Request;

	constexpr double kMaxRequestId = 2147483647.0; // scripts get request ids as Int32

	template<size_t N>
	bool listed(std::string_view value, const char *const (&names)[N])
	{
		return std::find(std::begin(names), std::end(names), value) != std::end(names);
	}

	bool is_type(const json &message, const char *type)
	{
		if (!message.is_object())
			return false;
		const auto t = message.find("t");
		return t != message.end() && *t == type;
	}

	bool daemon_id_char(char c)
	{
		return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == ':' || c == '/' || c == '-';
	}

	Request rejected(const char *type, const cb::wire::Bad &bad)
	{
		Request out;
		out.status = Request::Status::Rejected;
		out.reason = std::string(type) + ": " + bad.why;
		return out;
	}

	/// `target`: absent, null, "" or "0" all mean no target (0); anything else must be a decimal uint64. The server's
	/// builder (6I ActWire.rhack) sends "" for an area hack, the protocol (6P) null: both are the same request.
	uint64_t optional_target(const json &message)
	{
		const auto it = message.find("target");
		if (it == message.end() || it->is_null() || (it->is_string() && (it->get_ref<const std::string &>().empty() ||
																		  it->get_ref<const std::string &>() == "0")))
			return 0;
		return cb::wire::entity_id(message, "target");
	}

	bool has(const json &message, const char *key)
	{
		return message.find(key) != message.end();
	}

	/// The district a district hack names: a string cut to kMaxDistrictBytes (never inside a UTF-8 character) with its
	/// control characters dropped; "" when absent.
	std::string district(const json &message)
	{
		const auto it = message.find("district");
		if (it == message.end())
			return {};
		if (!it->is_string())
			throw cb::wire::Bad{"district is not a string"};
		std::string text = cb::wire::truncate_utf8(it->get_ref<const std::string &>(), cb::rhack::kMaxDistrictBytes);
		std::erase_if(text, [](char c) { return static_cast<unsigned char>(c) < 0x20 || c == 0x7F; });
		return text;
	}
}

namespace cb::rhack
{
	bool known_hack(std::string_view hack)
	{
		return listed(hack, world::kHackNames) || listed(hack, kRemoteHacks);
	}

	bool needs_target(std::string_view hack)
	{
		return hack != "traffic_switch" && hack != "gridlock" && hack != "blackout" && hack != "outline" && hack != "ping_area";
	}

	Request parse_rhack(const json &message, double off)
	{
		Request out;
		if (!is_type(message, "rhack"))
			return out;
		try
		{
			const int32_t id = wire::integer(message, "id", 0.0, kMaxRequestId);
			const uint64_t target = optional_target(message);
			const auto hack = message.find("hack");
			if (hack == message.end() || !hack->is_string())
				throw wire::Bad{"hack is missing or not a string"};
			const std::string &name = hack->get_ref<const std::string &>();
			if (!known_hack(name))
			{
				out.status = Request::Status::Unsupported;
				out.reply = world::make_hackresult(id, false, "unsupported");
				out.reason = "hack " + wire::truncate_utf8(name, 32) + " is not supported";
				return out;
			}
			if (target == 0 && needs_target(name))
				throw wire::Bad{"hack " + name + " needs a target"};
			if (has(message, "p"))
				out.event.pos = wire::cp_triple(message, "p", off);
			if (has(message, "r"))
				out.event.extra = wire::number(message, "r", 0.0, kMaxRadius);
			if (has(message, "secs"))
				out.event.amount = wire::integer(message, "secs", 0.0, kMaxSeconds);
			out.event.text = district(message);
			out.status = Request::Status::Event;
			out.event.kind = world::EventKind::RHack;
			out.event.id = id;
			out.event.target = target;
			out.event.name = name;
		}
		catch (const wire::Bad &bad)
		{
			return rejected("rhack", bad);
		}
		return out;
	}

	Request parse_daemon(const json &message)
	{
		Request out;
		if (!is_type(message, "daemon"))
			return out;
		try
		{
			const int32_t id = wire::integer(message, "id", 0.0, kMaxRequestId);
			const uint64_t dev = wire::entity_id(message, "dev");
			if (dev == 0)
				throw wire::Bad{"dev is 0"};
			const auto list = message.find("list");
			if (list == message.end() || !list->is_array() || list->empty() || list->size() > kMaxDaemons)
				throw wire::Bad{"list is missing, empty, not an array or longer than 8"};
			std::string joined;
			for (const json &entry : *list)
			{
				if (!entry.is_string())
					throw wire::Bad{"a daemon id is not a string"};
				const std::string &daemon = entry.get_ref<const std::string &>();
				if (daemon.empty() || daemon.size() > kMaxDaemonIdBytes || !std::all_of(daemon.begin(), daemon.end(), daemon_id_char))
					throw wire::Bad{"a daemon id is not [a-z0-9_.:/-]{1,64}"};
				if (!joined.empty())
					joined += ',';
				joined += daemon;
			}
			const int32_t strength = wire::integer(message, "strength", kMinStrength, kMaxStrength);
			out.status = Request::Status::Event;
			out.event.kind = world::EventKind::Daemon;
			out.event.id = id;
			out.event.target = dev;
			out.event.amount = strength;
			out.event.name = list->front().get<std::string>();
			out.event.text = std::move(joined);
		}
		catch (const wire::Bad &bad)
		{
			return rejected("daemon", bad);
		}
		return out;
	}

	void on_minecraft_message(const json &message)
	{
		const auto type = message.find("t");
		if (type == message.end())
			return;
		Request request;
		if (*type == "rhack")
			request = parse_rhack(message, world::level_offset(world::level_k()));
		else if (*type == "daemon")
			request = parse_daemon(message);
		else
			return;
		static std::atomic<int> logged{0};
		constexpr int kMaxLogged = 50;
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
				log::info("rhack: %d refused: %s (hackresult unsupported)", (*request.reply)["id"].get<int>(),
						  request.reason.c_str());
			return;
		case Request::Status::Event:
			world::events().push(request.event);
			if (logged++ >= kMaxLogged)
				return;
			if (request.event.kind == world::EventKind::RHack)
				log::info("rhack: %d %s on %llu (cp (%.1f, %.1f, %.1f), r %.0f m, %.0f s, district '%s') handed to the scripts",
						  request.event.id, request.event.name.c_str(), static_cast<unsigned long long>(request.event.target),
						  request.event.pos.x, request.event.pos.y, request.event.pos.z, request.event.extra,
						  request.event.amount, request.event.text.c_str());
			else
				log::info("daemon: %d [%s] strength %.0f on device %llu handed to the scripts", request.event.id,
						  request.event.text.c_str(), request.event.amount,
						  static_cast<unsigned long long>(request.event.target));
			return;
		}
	}
}
