#include "devices.hpp"
#include "link.hpp"
#include "log.hpp"
#include "probes.hpp"
#include "wire.hpp"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <iterator>
#include <numbers>
#include <utility>

namespace
{
	using nlohmann::json;

	constexpr double kMaxRequestId = 2147483647.0; // scripts get request ids as Int32

	template<size_t N>
	bool listed(std::string_view value, const char *const (&names)[N])
	{
		return std::find(std::begin(names), std::end(names), value) != std::end(names);
	}

	bool printable_ascii(std::string_view text)
	{
		return std::all_of(text.begin(), text.end(), [](char c) { return c > ' ' && c < 0x7f; });
	}

	bool in_range(const cb::Vec3 &v)
	{
		return cb::wire::finite(v) && std::abs(v.x) < cb::wire::kMaxCoord && std::abs(v.y) < cb::wire::kMaxCoord &&
			   std::abs(v.z) < cb::wire::kMaxCoord;
	}

	double distance_sq(const cb::Vec3 &a, const cb::Vec3 &b)
	{
		const double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
		return dx * dx + dy * dy + dz * dz;
	}
}

namespace cb::devices
{
	std::optional<Validated> validate(std::span<const uint64_t> ids, std::span<const std::string> classes,
									  std::span<const std::string> kinds, std::span<const Vec3> pos,
									  std::span<const double> yaws, std::span<const Vec3> sizes,
									  std::span<const int32_t> states, std::span<const int32_t> flags)
	{
		const size_t n = ids.size();
		if (classes.size() != n || kinds.size() != n || pos.size() != n || yaws.size() != n ||
			(!sizes.empty() && sizes.size() != n) || states.size() != n || flags.size() != n)
			return std::nullopt;
		Validated out;
		std::vector<uint64_t> seen;
		const auto drop = [&out](const char *why) {
			if (out.dropped++ == 0)
				out.reason = why;
		};
		for (size_t i = 0; i < n; ++i)
		{
			if (ids[i] == 0)
				drop("an id is 0");
			else if (std::find(seen.begin(), seen.end(), ids[i]) != seen.end())
				drop("an id repeats");
			else if (classes[i].empty() || !printable_ascii(classes[i]))
				drop("a class is empty or not printable ASCII");
			else if (!listed(kinds[i], kKinds))
				drop("a kind is not a device_map kind");
			else if (!in_range(pos[i]))
				drop("a position is not finite or out of range");
			else if (!std::isfinite(yaws[i]))
				drop("a yaw is not finite");
			else if (states[i] < kMinState || states[i] > kMaxState)
				drop("a state is outside -2..1");
			else if (flags[i] < 0 || flags[i] > kFlagMask)
				drop("flags are outside 0..31");
			else
			{
				seen.push_back(ids[i]);
				out.devices.push_back(
					{ids[i], classes[i].substr(0, kMaxClassChars), kinds[i], pos[i], yaws[i], states[i], flags[i]});
			}
		}
		return out;
	}

	std::vector<Device> nearest(std::vector<Device> devices, const std::optional<Vec3> &center)
	{
		if (center)
		{
			const double limit = kRadius * kRadius;
			std::erase_if(devices, [&](const Device &d) { return !(distance_sq(d.pos, *center) <= limit); });
			std::stable_sort(devices.begin(), devices.end(), [&](const Device &a, const Device &b) {
				return distance_sq(a.pos, *center) < distance_sq(b.pos, *center);
			});
		}
		if (devices.size() > kMaxEntries)
			devices.resize(kMaxEntries);
		return devices;
	}

	json entry_json(const Device &device, double off)
	{
		return {{"id", std::to_string(device.id)},
				{"cls", device.cls},
				{"kind", device.kind},
				{"p", wire::triple_json(cp_to_mc_pos(device.pos.x, device.pos.y, device.pos.z, off))},
				{"yaw", wire::rounded(world::cp_yaw_to_mc_yaw(device.yaw), 1e2)},
				{"st", device.st},
				{"fl", device.fl}};
	}

	std::optional<json> Stream::submit(std::span<const Device> devices, double off, double now)
	{
		const bool full = !m_fullAt || now - *m_fullAt >= kFullListSeconds;
		std::map<uint64_t, json> current;
		json list = json::array();
		for (const Device &device : devices)
		{
			json entry = entry_json(device, off);
			const auto sent = m_sent.find(device.id);
			if (full || sent == m_sent.end() || sent->second != entry)
				list.push_back(entry);
			current.emplace(device.id, std::move(entry));
		}
		m_sent = std::move(current);
		if (full)
			m_fullAt = now;
		if (list.empty())
			return std::nullopt;
		return json{{"t", "devices"}, {"list", std::move(list)}};
	}

	void Stream::reset()
	{
		m_sent.clear();
		m_fullAt.reset();
	}

	void Stream::note_state(uint64_t id, int32_t st, int32_t fl)
	{
		const auto sent = m_sent.find(id);
		if (sent == m_sent.end())
			return;
		sent->second["st"] = st;
		sent->second["fl"] = fl;
	}

	std::optional<json> make_devstate(uint64_t id, int32_t st, int32_t fl, std::string_view by)
	{
		if (id == 0 || st < kMinState || st > kMaxState || fl < 0 || fl > kFlagMask || !listed(by, kBy))
			return std::nullopt;
		return json{{"t", "devstate"}, {"id", std::to_string(id)}, {"st", st}, {"fl", fl}, {"by", std::string(by)}};
	}

	std::optional<json> make_devresult(int32_t id, bool ok, std::string_view reason)
	{
		if (id < 0)
			return std::nullopt;
		return json{{"t", "devresult"},
					{"id", id},
					{"ok", ok},
					{"reason", ok ? std::string() : wire::truncate_utf8(reason, kMaxReasonBytes)}};
	}

	std::string_view attribute(std::string_view by, bool guestRecent)
	{
		return guestRecent ? std::string_view("guest") : by;
	}

	void GuestWindow::note(int32_t request, uint64_t device, double now)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		if (m_entries.size() >= kRemembered)
			m_entries.pop_front();
		m_entries.push_back({request, device, now});
	}

	void GuestWindow::forget(int32_t request)
	{
		std::lock_guard<std::mutex> lock(m_lock);
		std::erase_if(m_entries, [request](const Entry &e) { return e.request == request; });
	}

	bool GuestWindow::recent(uint64_t device, double now) const
	{
		std::lock_guard<std::mutex> lock(m_lock);
		return std::any_of(m_entries.begin(), m_entries.end(), [&](const Entry &e) {
			return e.device == device && now - e.at >= 0.0 && now - e.at <= kGuestSeconds;
		});
	}

	void GuestWindow::clear()
	{
		std::lock_guard<std::mutex> lock(m_lock);
		m_entries.clear();
	}

	DevAct parse_devact(const json &message)
	{
		DevAct out;
		if (!message.is_object())
			return out;
		const auto type = message.find("t");
		if (type == message.end() || *type != "devact")
			return out;
		try
		{
			const int32_t id = wire::integer(message, "id", 0.0, kMaxRequestId);
			const uint64_t dev = wire::entity_id(message, "dev");
			if (dev == 0)
				throw wire::Bad{"dev is 0"};
			const auto act = message.find("act");
			if (act == message.end() || !act->is_string())
				throw wire::Bad{"act is missing or not a string"};
			const std::string &name = act->get_ref<const std::string &>();
			if (!listed(name, kActs))
			{
				out.status = DevAct::Status::Unsupported;
				out.reply = make_devresult(id, false, "unsupported");
				out.reason = "act " + wire::truncate_utf8(name, 32) + " is not supported";
				return out;
			}
			out.status = DevAct::Status::Event;
			out.event.kind = world::EventKind::DevAct;
			out.event.id = id;
			out.event.target = dev;
			out.event.name = name;
		}
		catch (const wire::Bad &bad)
		{
			out = {};
			out.status = DevAct::Status::Rejected;
			out.reason = "devact: " + bad.why;
		}
		return out;
	}

	BoxSize default_size(std::string_view kind)
	{
		// Night City's sizes, generously: a double door, a garage door, a wall lamp, ... (megaplan section 1.11);
		// Minecraft's device_map sizes replace them once known.
		if (kind == "door")
			return {2.4, 2.8, 0.6, false};
		if (kind == "gate")
			return {4.0, 3.2, 0.8, false};
		if (kind == "light")
			return {0.8, 0.8, 0.8, true};
		if (kind == "camera")
			return {0.8, 0.8, 0.8, true};
		if (kind == "terminal")
			return {1.0, 2.0, 0.8, false};
		if (kind == "vending")
			return {1.4, 2.4, 1.2, false};
		if (kind == "traffic")
			return {1.0, 6.0, 1.0, false};
		if (kind == "elevator")
			return {1.0, 2.2, 0.6, false};
		if (kind == "turret")
			return {1.2, 1.4, 1.2, true};
		if (kind == "screen")
			return {2.0, 1.4, 0.4, true};
		if (kind == "container")
			return {1.4, 1.2, 1.0, false};
		return {1.0, 1.0, 1.0, false};
	}

	Aabb device_box(const Device &device)
	{
		const BoxSize size = default_size(device.kind);
		// Cyberpunk yaw a: right = (cos a, sin a), forward = (-sin a, cos a); the turned footprint's half extents
		const double a = device.yaw * std::numbers::pi / 180.0;
		const double c = std::abs(std::cos(a)), s = std::abs(std::sin(a));
		const double hx = c * size.w / 2 + s * size.d / 2 + kBoxMargin;
		const double hy = s * size.w / 2 + c * size.d / 2 + kBoxMargin;
		const double bottom = size.centered ? device.pos.z - size.h / 2 : device.pos.z;
		return {{device.pos.x - hx, device.pos.y - hy, bottom - kBoxMargin},
				{device.pos.x + hx, device.pos.y + hy, bottom + size.h + kBoxMargin}};
	}

	void BoxSet::set(std::span<const Device> devices, double now)
	{
		std::vector<Aabb> next;
		next.reserve(std::min(devices.size(), kMax));
		for (const Device &device : devices.first(std::min(devices.size(), kMax)))
			next.push_back(device_box(device));
		std::lock_guard<std::mutex> lock(m_lock);
		m_boxes = std::move(next);
		m_at = now;
	}

	std::vector<Aabb> BoxSet::current(double now) const
	{
		std::lock_guard<std::mutex> lock(m_lock);
		if (now - m_at > kMaxAge)
			return {};
		return m_boxes;
	}

	void on_minecraft_message(const json &message)
	{
		const auto type = message.find("t");
		if (type == message.end())
			return;
		if (*type == "hello")
		{
			guest_window().clear(); // the old session's requests
			return;
		}
		const DevAct act = parse_devact(message);
		static std::atomic<int> logged{0};
		constexpr int kMaxLogged = 50; // requests are player clicks, but a bad peer must not fill the log
		switch (act.status)
		{
		case DevAct::Status::Ignored:
			return;
		case DevAct::Status::Rejected:
			world::note_invalid(1, act.reason);
			return;
		case DevAct::Status::Unsupported:
			if (act.reply)
				link().send(*act.reply);
			if (logged++ < kMaxLogged)
				log::info("devact: %d refused: %s (devresult unsupported)", (*act.reply)["id"].get<int>(), act.reason.c_str());
			return;
		case DevAct::Status::Event:
			guest_window().note(act.event.id, act.event.target, steady_seconds());
			world::events().push(act.event);
			if (logged++ < kMaxLogged)
				log::info("devact: %d %s on %llu handed to the scripts", act.event.id, act.event.name.c_str(),
						  static_cast<unsigned long long>(act.event.target));
			return;
		}
	}

	void on_link_dropped()
	{
		guest_window().clear();
	}
}
