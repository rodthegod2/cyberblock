#include "feed.hpp"
#include "log.hpp"
#include "world.hpp"
#include <limits>
#include <mutex>

namespace
{
	using nlohmann::json;

	/// The "redmean" distance between two colours (a cheap perceptual weighting of squared RGB differences).
	int64_t distance(int r1, int g1, int b1, int r2, int g2, int b2)
	{
		const int64_t mean = (r1 + r2) / 2;
		const int64_t dr = r1 - r2, dg = g1 - g2, db = b1 - b2;
		return ((512 + mean) * dr * dr >> 8) + 4 * dg * dg + ((767 - mean) * db * db >> 8);
	}

	std::once_flag g_paletteOnce;
	std::atomic<bool> g_paletteReady{false};
	const cb::feed::Palette *g_palette = nullptr; // never destroyed: the render thread may read it while the process exits
}

namespace cb::feed
{
	const std::array<uint32_t, 62> kMapColours = {
		0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00, 0xFFFFFF, 0xA4A8B8, 0x976D4D,
		0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33, 0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C,
		0x999999, 0x4C7F99, 0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D, 0x5CDBD5, 0x4A80FF,
		0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A, 0xBA8524, 0x677535, 0xA04D4E, 0x392923,
		0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C, 0x4C3223, 0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D,
		0x167E86, 0x3A8E8C, 0x562C3E, 0x14B485, 0x646464, 0xD8AF93, 0x7FA796};

	std::optional<uint32_t> packed_rgb(uint8_t packed)
	{
		if (packed < kFirstPacked || packed > kLastPacked)
			return std::nullopt;
		const uint32_t base = kMapColours[packed >> 2];
		const uint32_t modifier = uint32_t(kBrightness[packed & 3]);
		const auto scale = [modifier](uint32_t channel) { return channel * modifier / 255; };
		return scale(base >> 16 & 0xFF) << 16 | scale(base >> 8 & 0xFF) << 8 | scale(base & 0xFF);
	}

	uint8_t nearest(uint8_t r, uint8_t g, uint8_t b)
	{
		uint8_t best = kFirstPacked;
		int64_t bestDistance = std::numeric_limits<int64_t>::max();
		for (int p = kFirstPacked; p <= kLastPacked; ++p)
		{
			const uint32_t c = *packed_rgb(uint8_t(p));
			const int64_t d = distance(r, g, b, int(c >> 16), int(c >> 8 & 0xFF), int(c & 0xFF));
			if (d < bestDistance)
			{
				bestDistance = d;
				best = uint8_t(p);
			}
		}
		return best;
	}

	Palette::Palette() : m_lut(size_t(1) << 15)
	{
		// each entry is the nearest colour to the centre of its 8 x 8 x 8 cell
		for (size_t i = 0; i < m_lut.size(); ++i)
			m_lut[i] = nearest(uint8_t((i >> 10) << 3 | 4), uint8_t((i >> 5 & 31) << 3 | 4), uint8_t((i & 31) << 3 | 4));
	}

	void prepare()
	{
		std::call_once(g_paletteOnce, [] {
			g_palette = new Palette;
			g_paletteReady = true;
		});
	}

	bool palette_ready()
	{
		return g_paletteReady;
	}

	const Palette &palette()
	{
		return *g_palette;
	}

	std::optional<std::vector<uint8_t>> to_map_colours(std::span<const uint8_t> pixels, size_t rowPitch, bool bgra,
														const Palette &palette)
	{
		constexpr size_t kRow = size_t(kSize) * 4;
		if (rowPitch < kRow || pixels.size() < rowPitch * (kSize - 1) + kRow)
			return std::nullopt;
		const size_t r = bgra ? 2 : 0, b = bgra ? 0 : 2;
		std::vector<uint8_t> out(kPixels);
		for (size_t y = 0; y < size_t(kSize); ++y)
		{
			const uint8_t *row = pixels.data() + y * rowPitch;
			for (size_t x = 0; x < size_t(kSize); ++x)
			{
				const uint8_t *p = row + x * 4;
				out[y * kSize + x] = palette.map(p[r], p[1], p[b]);
			}
		}
		return out;
	}

	std::string base64(std::span<const uint8_t> bytes)
	{
		static constexpr char kAlphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
		std::string out;
		out.reserve((bytes.size() + 2) / 3 * 4);
		size_t i = 0;
		for (; i + 3 <= bytes.size(); i += 3)
		{
			const uint32_t v = uint32_t(bytes[i]) << 16 | uint32_t(bytes[i + 1]) << 8 | bytes[i + 2];
			out += kAlphabet[v >> 18];
			out += kAlphabet[v >> 12 & 63];
			out += kAlphabet[v >> 6 & 63];
			out += kAlphabet[v & 63];
		}
		if (const size_t rest = bytes.size() - i; rest > 0)
		{
			const uint32_t v = uint32_t(bytes[i]) << 16 | (rest == 2 ? uint32_t(bytes[i + 1]) << 8 : 0);
			out += kAlphabet[v >> 18];
			out += kAlphabet[v >> 12 & 63];
			out += rest == 2 ? kAlphabet[v >> 6 & 63] : '=';
			out += '=';
		}
		return out;
	}

	std::optional<json> make_feed(std::span<const uint8_t> colours)
	{
		if (colours.size() != kPixels)
			return std::nullopt;
		for (const uint8_t c : colours)
			if (c < kFirstPacked || c > kLastPacked)
				return std::nullopt;
		return json{{"t", "feed"}, {"w", kSize}, {"h", kSize}, {"px", base64(colours)}};
	}

	FeedReq parse_feedreq(const json &message)
	{
		FeedReq out;
		if (!message.is_object())
			return out;
		const auto type = message.find("t");
		if (type == message.end() || *type != "feedreq")
			return out;
		const auto on = message.find("on");
		if (on == message.end() || !on->is_boolean())
		{
			out.status = FeedReq::Status::Rejected;
			return out;
		}
		out.status = FeedReq::Status::Valid;
		out.on = on->get<bool>();
		return out;
	}

	bool CostMonitor::note(double ms)
	{
		if (m_tripped)
			return false;
		m_slow = ms > kMaxCostMs ? m_slow + 1 : 0;
		if (m_slow < kSlowInARow)
			return false;
		m_tripped = true;
		return true;
	}

	void CostMonitor::reset()
	{
		m_slow = 0;
		m_tripped = false;
	}

	void Gate::set_requested(bool on)
	{
		m_requested = on;
	}

	bool Gate::due(double now)
	{
		if (!m_requested || m_fallback || !palette_ready())
			return false;
		std::lock_guard<std::mutex> lock(m_lock);
		if (m_last && now - *m_last < kIntervalSeconds)
			return false;
		m_last = now;
		return true;
	}

	bool Gate::fall_back()
	{
		return !m_fallback.exchange(true);
	}

	void Gate::reset()
	{
		m_requested = false;
		m_fallback = false;
		std::lock_guard<std::mutex> lock(m_lock);
		m_last.reset();
	}

	void on_minecraft_message(const json &message)
	{
		const auto type = message.find("t");
		if (type == message.end())
			return;
		if (*type == "hello")
		{
			gate().reset(); // a new Minecraft asks again if it wants a feed; the fallback is tried again too
			return;
		}
		if (*type == "session")
		{
			// a feed is the host's: leaving the host role ends it
			const auto state = message.find("state");
			if (gate().requested() && (state == message.end() || *state != "host"))
			{
				gate().set_requested(false);
				log::info("feed: off (no longer the host)");
			}
			return;
		}
		const FeedReq req = parse_feedreq(message);
		if (req.status == FeedReq::Status::Ignored)
			return;
		if (req.status == FeedReq::Status::Rejected)
		{
			world::note_invalid(1, "feedreq: on is missing or not a bool");
			return;
		}
		if (req.on)
			prepare(); // builds the colour table here, never on the render thread
		const bool was = gate().requested();
		gate().set_requested(req.on);
		if (was == req.on)
			return;
		if (!req.on)
			log::info("feed: off (Minecraft stopped asking)");
		else if (gate().fallen_back())
			log::info("feed: requested, but the back-buffer feed is off this session; Minecraft's top-down replica render is "
					  "the fallback");
		else
			log::info("feed: on (path: back-buffer read-back through the Cyberblock.fx Feed pass, %dx%d map colours, at "
					  "most 1 per second)",
					  kSize, kSize);
	}

	void on_link_dropped()
	{
		gate().reset();
	}
}
