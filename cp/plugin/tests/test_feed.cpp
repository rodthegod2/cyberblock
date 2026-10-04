// Phase 6 (megaplan 2026-10-04 task 6-D1): the photo feed's map colours, message, request and fallback rules.
#include "feed.hpp"
#include "link.hpp"
#include "test.hpp"
#include "world.hpp"
#include <cstdint>
#include <deque>
#include <string>
#include <vector>

namespace
{
	using nlohmann::json;
	using cb::feed::FeedReq;

	uint32_t rgb(uint8_t r, uint8_t g, uint8_t b)
	{
		return uint32_t(r) << 16 | uint32_t(g) << 8 | b;
	}

	/// A kSize x kSize RGBA picture of one colour, rows `pitch` bytes apart.
	std::vector<uint8_t> picture(uint8_t r, uint8_t g, uint8_t b, size_t pitch = cb::feed::kSize * 4)
	{
		std::vector<uint8_t> px(pitch * cb::feed::kSize, 0xEE);
		for (int y = 0; y < cb::feed::kSize; ++y)
			for (int x = 0; x < cb::feed::kSize; ++x)
			{
				uint8_t *p = px.data() + y * pitch + x * 4;
				p[0] = r, p[1] = g, p[2] = b, p[3] = 255;
			}
		return px;
	}

	std::vector<uint8_t> decode64(const std::string &text)
	{
		const std::string alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
		std::vector<uint8_t> out;
		uint32_t acc = 0;
		int bits = 0;
		for (char c : text)
		{
			if (c == '=')
				break;
			acc = acc << 6 | uint32_t(alphabet.find(c));
			bits += 6;
			if (bits >= 8)
			{
				bits -= 8;
				out.push_back(uint8_t(acc >> bits));
			}
		}
		return out;
	}
}

TEST(feed_map_colours_are_minecraft_26_3s)
{
	// spot checks against MapColor (javap, 26.3): GRASS 0x7FB238, WATER 0x4040FF, COLOR_BLACK 0x191919, GLOW_LICHEN 0x7FA796
	CHECK(cb::feed::kMapColours[0] == 0);
	CHECK(cb::feed::kMapColours[1] == 0x7FB238 && cb::feed::kMapColours[12] == 0x4040FF);
	CHECK(cb::feed::kMapColours[29] == 0x191919 && cb::feed::kMapColours[61] == 0x7FA796);
	// packed = id * 4 + brightness; channel * modifier / 255 rounded down (ARGB.scaleRGB)
	CHECK(cb::feed::packed_rgb(8 * 4 + 2) == 0xFFFFFFu);                  // SNOW, HIGH
	CHECK(cb::feed::packed_rgb(8 * 4 + 1) == rgb(220, 220, 220));         // SNOW, NORMAL
	CHECK(cb::feed::packed_rgb(8 * 4 + 3) == rgb(135, 135, 135));         // SNOW, LOWEST
	CHECK(cb::feed::packed_rgb(1 * 4 + 0) == rgb(127 * 180 / 255, 178 * 180 / 255, 56 * 180 / 255)); // GRASS, LOW
	for (int p = 0; p < 4; ++p)
		CHECK(!cb::feed::packed_rgb(uint8_t(p))); // NONE
	CHECK(!cb::feed::packed_rgb(248) && !cb::feed::packed_rgb(255));
	CHECK(cb::feed::packed_rgb(cb::feed::kLastPacked).has_value());
}

TEST(feed_nearest_finds_exact_map_colours)
{
	for (int p = cb::feed::kFirstPacked; p <= cb::feed::kLastPacked; ++p)
	{
		const uint32_t c = *cb::feed::packed_rgb(uint8_t(p));
		const uint8_t got = cb::feed::nearest(uint8_t(c >> 16), uint8_t(c >> 8), uint8_t(c));
		// a colour two packed ids share maps to the lower one; it must look the same
		CHECK(cb::feed::packed_rgb(got) == c);
		CHECK(got >= cb::feed::kFirstPacked && got <= cb::feed::kLastPacked);
	}
	CHECK(cb::feed::packed_rgb(cb::feed::nearest(255, 255, 255)) == 0xFFFFFFu);
	CHECK(cb::feed::packed_rgb(cb::feed::nearest(250, 2, 3)) == 0xFF0000u); // FIRE, HIGH
}

TEST(feed_palette_matches_nearest_on_quantised_colours)
{
	const cb::feed::Palette palette;
	for (int r = 0; r < 256; r += 37)
		for (int g = 0; g < 256; g += 29)
			for (int b = 0; b < 256; b += 41)
			{
				// the table holds the nearest colour of each 8 x 8 x 8 cell's centre
				const uint8_t want = cb::feed::nearest(uint8_t((r & ~7) | 4), uint8_t((g & ~7) | 4), uint8_t((b & ~7) | 4));
				CHECK(palette.map(uint8_t(r), uint8_t(g), uint8_t(b)) == want);
			}
}

TEST(feed_converts_rgba_and_bgra_pictures_with_padded_rows)
{
	const cb::feed::Palette palette;
	const uint8_t red = palette.map(250, 10, 10);
	const auto rgba = cb::feed::to_map_colours(picture(250, 10, 10), cb::feed::kSize * 4, false, palette);
	CHECK(rgba && rgba->size() == cb::feed::kPixels && rgba->front() == red && rgba->back() == red);
	// the same bytes read as BGRA are blue
	const auto bgra = cb::feed::to_map_colours(picture(250, 10, 10, 640), 640, true, palette);
	CHECK(bgra && bgra->at(5000) == palette.map(10, 10, 250));
	// a different pixel lands at its own index (row-major from the top left)
	std::vector<uint8_t> px = picture(0, 0, 0);
	uint8_t *p = px.data() + (3 * cb::feed::kSize + 7) * 4;
	p[0] = 255, p[1] = 255, p[2] = 255;
	const auto one = cb::feed::to_map_colours(px, cb::feed::kSize * 4, false, palette);
	CHECK(one && one->at(3 * cb::feed::kSize + 7) == palette.map(255, 255, 255) && one->at(0) == palette.map(0, 0, 0));
	// too short, or a pitch below a row
	CHECK(!cb::feed::to_map_colours(std::span<const uint8_t>(px).first(px.size() - 1), cb::feed::kSize * 4, false, palette));
	CHECK(!cb::feed::to_map_colours(px, cb::feed::kSize * 4 - 4, false, palette));
}

TEST(feed_base64_is_rfc_4648)
{
	const auto b64 = [](const std::string &s) {
		return cb::feed::base64(std::span<const uint8_t>(reinterpret_cast<const uint8_t *>(s.data()), s.size()));
	};
	CHECK(b64("") == "");
	CHECK(b64("f") == "Zg==");
	CHECK(b64("fo") == "Zm8=");
	CHECK(b64("foo") == "Zm9v");
	CHECK(b64("foobar") == "Zm9vYmFy");
	const uint8_t high[] = {0xFB, 0xFF, 0xBF};
	CHECK(cb::feed::base64(high) == "+/+/");
}

TEST(feed_message_shape_and_limits)
{
	std::vector<uint8_t> colours(cb::feed::kPixels, 4 * 29 + 1);
	colours[0] = cb::feed::kFirstPacked;
	colours.back() = cb::feed::kLastPacked;
	const auto m = cb::feed::make_feed(colours);
	CHECK(m && (*m)["t"] == "feed" && (*m)["w"] == 128 && (*m)["h"] == 128);
	const std::string px = (*m)["px"].get<std::string>();
	CHECK(px.size() == (cb::feed::kPixels + 2) / 3 * 4); // 21848 characters
	CHECK(decode64(px) == colours);
	// the whole message stays well under the 30000-byte UP cap (megaplan section 1.3)
	CHECK(m->dump().size() < 30000);
	std::vector<uint8_t> none = colours;
	none[100] = 3; // NONE (transparent) is never sent
	CHECK(!cb::feed::make_feed(none));
	std::vector<uint8_t> past = colours;
	past[100] = 248;
	CHECK(!cb::feed::make_feed(past));
	CHECK(!cb::feed::make_feed(std::span<const uint8_t>(colours).first(cb::feed::kPixels - 1)));
}

TEST(feed_is_a_state_stream_on_the_link)
{
	// a Minecraft that stops reading keeps only the newest picture queued
	cb::OutQueue q;
	q.push(json{{"t", "feed"}, {"w", 128}, {"h", 128}, {"px", "A"}});
	q.push(json{{"t", "hackresult"}, {"id", 1}, {"ok", true}, {"reason", ""}});
	q.push(json{{"t", "feed"}, {"w", 128}, {"h", 128}, {"px", "B"}});
	std::deque<std::string> out;
	q.take(out);
	CHECK(out.size() == 2 && q.superseded() >= 1);
	CHECK(out.back().find("\"px\":\"B\"") != std::string::npos);
	CHECK(out.front().find("hackresult") != std::string::npos);
}

TEST(feedreq_validation)
{
	const auto req = [](const char *text) { return cb::feed::parse_feedreq(json::parse(text)); };
	CHECK(req(R"({"t":"feedreq","on":true})").status == FeedReq::Status::Valid && req(R"({"t":"feedreq","on":true})").on);
	CHECK(req(R"({"t":"feedreq","on":false})").status == FeedReq::Status::Valid && !req(R"({"t":"feedreq","on":false})").on);
	CHECK(req(R"({"t":"feedreq"})").status == FeedReq::Status::Rejected);
	CHECK(req(R"({"t":"feedreq","on":1})").status == FeedReq::Status::Rejected);
	CHECK(req(R"({"t":"feed","on":true})").status == FeedReq::Status::Ignored);
	CHECK(req(R"([true])").status == FeedReq::Status::Ignored);
}

TEST(feed_gate_is_at_most_one_capture_a_second_and_only_while_requested)
{
	cb::feed::prepare(); // due() waits for the palette
	CHECK(cb::feed::palette_ready());
	cb::feed::Gate gate;
	CHECK(!gate.due(100.0)); // not requested
	gate.set_requested(true);
	CHECK(gate.due(100.0));
	CHECK(!gate.due(100.5));
	CHECK(!gate.due(100.99));
	CHECK(gate.due(101.0));
	gate.set_requested(false);
	CHECK(!gate.due(105.0));
	gate.set_requested(true);
	CHECK(gate.due(105.0));
	// the fallback stops it until a new Minecraft session
	CHECK(gate.fall_back("slow"));
	CHECK(!gate.fall_back("again")); // logged once
	CHECK(!gate.due(200.0) && gate.fallen_back());
	gate.reset();
	CHECK(!gate.requested() && !gate.fallen_back());
	gate.set_requested(true);
	CHECK(gate.due(200.0));
}

TEST(feed_cost_monitor_trips_on_three_slow_captures_in_a_row)
{
	cb::feed::CostMonitor cost;
	CHECK(!cost.note(0.9)); // one hiccup
	CHECK(!cost.note(0.2)); // and the run starts over
	CHECK(!cost.note(0.6));
	CHECK(!cost.note(0.7));
	CHECK(!cost.note(0.5)); // exactly 0.5 ms is not over
	CHECK(!cost.note(0.51));
	CHECK(!cost.note(0.8));
	CHECK(cost.note(1.2) && cost.tripped()); // the third in a row
	CHECK(!cost.note(5.0));                  // reported once
	cost.reset();
	CHECK(!cost.tripped());
}

TEST(feed_observer_follows_feedreq_hello_session_and_drops)
{
	cb::feed::Gate &gate = cb::feed::gate();
	gate.reset();
	const auto rejectedBefore = cb::world::events().stats().rejected;
	cb::feed::on_minecraft_message(json::parse(R"({"t":"feedreq","on":true})"));
	CHECK(gate.requested() && cb::feed::palette_ready());
	cb::feed::on_minecraft_message(json::parse(R"({"t":"feedreq","on":"yes"})"));
	CHECK(gate.requested() && cb::world::events().stats().rejected == rejectedBefore + 1);
	cb::feed::on_minecraft_message(json::parse(R"({"t":"feedreq","on":false})"));
	CHECK(!gate.requested());
	cb::feed::on_minecraft_message(json::parse(R"({"t":"feedreq","on":true})"));
	cb::feed::on_minecraft_message(json::parse(R"({"t":"session","state":"host","reason":"","perms":{}})"));
	CHECK(gate.requested()); // still the host
	cb::feed::on_minecraft_message(json::parse(R"({"t":"session","state":"guest","reason":"","perms":{}})"));
	CHECK(!gate.requested()); // no longer the host: no feed
	cb::feed::on_minecraft_message(json::parse(R"({"t":"feedreq","on":true})"));
	gate.fall_back("test");
	// a later feedreq on while fallen back keeps the request (and sends nothing: the server renders the replica)
	cb::feed::on_minecraft_message(json::parse(R"({"t":"feedreq","on":false})"));
	cb::feed::on_minecraft_message(json::parse(R"({"t":"feedreq","on":true})"));
	CHECK(gate.requested() && gate.fallen_back());
	cb::feed::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})"));
	CHECK(!gate.requested() && !gate.fallen_back());
	cb::feed::on_minecraft_message(json::parse(R"({"t":"feedreq","on":true})"));
	cb::feed::on_link_dropped();
	CHECK(!gate.requested());
}

TEST(feed_fallback_keeps_the_first_reason_until_a_new_session)
{
	// section 1.9 has one `feed` shape (128 x 128): the fallback sends nothing, it only remembers why for the log
	cb::feed::Gate gate;
	CHECK(gate.reason().empty());
	gate.set_requested(true);
	CHECK(gate.fall_back("CbFeedTex is not 128 x 128 RGBA8"));
	gate.fall_back("a second reason"); // the first reason is kept
	CHECK(gate.reason() == "CbFeedTex is not 128 x 128 RGBA8");
	CHECK(!gate.due(10.0));
	gate.reset(); // a new Minecraft session tries the capture again
	CHECK(gate.reason().empty() && !gate.fallen_back());
}
