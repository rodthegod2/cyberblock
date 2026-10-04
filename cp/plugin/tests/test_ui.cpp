// The Cyberpunk UI bridge's pure half (ui.cpp; plan 2026-10-03-retest3-polish section 1.1 and task U2): every builder
// with its limits, UTF-8 safe truncation, control characters, positions with the ground alignment, enum mapping, the
// reply caps, the send gates, the uiact validation and the action queue with its pause rules.
#include "test.hpp"
#include "ui.hpp"
#include <cmath>
#include <limits>
#include <string>
#include <vector>

namespace
{
	using nlohmann::json;
	using namespace cb::ui;

	constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();
	constexpr double kInf = std::numeric_limits<double>::infinity();

	bool approx(double a, double b)
	{
		return std::abs(a - b) <= 1e-9;
	}

	/// The wire text parses back to the same value (no NaN or invalid UTF-8 got through).
	bool survives_the_wire(const json &message)
	{
		return json::parse(message.dump()) == message;
	}

	/// "é" is two bytes (C3 A9): a cut after an odd count lands inside one.
	std::string accents(size_t count)
	{
		std::string text;
		for (size_t i = 0; i < count; ++i)
			text += "\xC3\xA9";
		return text;
	}

	bool valid_utf8(const std::string &text)
	{
		try
		{
			(void)json(text).dump();
			return true;
		}
		catch (const json::exception &)
		{
			return false;
		}
	}
}

// ---- clean_text -------------------------------------------------------------------------------------------------------

TEST(ui_clean_text_strips_controls_and_keeps_utf8)
{
	CHECK(clean_text("Talk", 128) == "Talk");
	CHECK(clean_text("  a\tb\nc\r ", 128) == "a b c");
	CHECK(clean_text(std::string("a\x01" "b\x7F" "c", 5), 128) == "abc");
	CHECK(clean_text("a\xC2\x85" "b", 128) == "ab");          // C1 NEL
	CHECK(clean_text("a\xFF\xC3" "b\xE2\x82", 128) == "ab");  // invalid and broken sequences
	CHECK(clean_text("\xE2\x82\xAC$ 100", 128) == "\xE2\x82\xAC$ 100"); // the euro sign stays
	CHECK(clean_text("\xF0\x9F\x98\x80", 128) == "\xF0\x9F\x98\x80");   // a 4-byte character stays
	CHECK(clean_text("\xED\xA0\x80" "x", 128) == "x");         // a UTF-16 surrogate is not UTF-8
}

TEST(ui_clean_text_cuts_on_a_character_boundary)
{
	const std::string text = accents(100); // 200 bytes
	const std::string cut = clean_text(text, 129);
	CHECK(cut.size() == 128 && cut == accents(64) && valid_utf8(cut));
	CHECK(clean_text(accents(1), 1).empty());
	CHECK(clean_text("abcdef", 3) == "abc");
}

// ---- stats ------------------------------------------------------------------------------------------------------------

TEST(ui_stats_carries_and_clamps_every_field)
{
	const json m = make_stats(12345, 3, "Watson", 21, 7, 87.25);
	CHECK(m["t"] == "stats" && m["money"] == 12345 && m["wanted"] == 3 && m["district"] == "Watson");
	CHECK(m["time"] == json::array({21, 7}) && approx(m["speed"], 87.3));
	CHECK(m["money"].is_number_integer() && survives_the_wire(m));

	const json c = make_stats(-5, 9, std::string(100, 'd'), 30, -1, -3.0);
	CHECK(c["money"] == 0 && c["wanted"] == 5 && c["district"].get<std::string>().size() == 64);
	CHECK(c["time"] == json::array({23, 0}) && c["speed"] == -1);
	CHECK(make_stats(0, -2, "", 0, 0, kNaN)["speed"] == -1);
	CHECK(make_stats(0, -2, "", 0, 0, kNaN)["wanted"] == 0);
	CHECK(make_stats(0, 0, "", 0, 0, 0.0)["speed"] == 0);
	CHECK(make_stats(0, 0, "", 0, 0, kInf)["speed"] == -1);
	CHECK(make_stats(0, 0, "", 0, 0, 5000.0)["speed"] == 1000);
}

// ---- quest ------------------------------------------------------------------------------------------------------------

TEST(ui_quest_converts_the_waypoint_with_the_alignment)
{
	const std::vector<Objective> obj = {{"Go to Lizzie's", 0, 0, false, true}, {"Collect shards", 2, 5, true, false}};
	const json m = make_quest("The Heist", "main", obj, cb::Vec3{10.0, 20.0, 30.0}, 0.25);
	CHECK(m["t"] == "quest" && m["title"] == "The Heist" && m["kind"] == "main");
	CHECK(m["obj"].size() == 2);
	CHECK(m["obj"][0]["text"] == "Go to Lizzie's" && m["obj"][0]["on"] == true && m["obj"][0]["opt"] == false);
	CHECK(m["obj"][1]["cur"] == 2 && m["obj"][1]["tot"] == 5 && m["obj"][1]["opt"] == true && m["obj"][1]["on"] == false);
	// Cyberpunk (x, y, z) -> Minecraft (x, z - off, -y)
	CHECK(approx(m["wp"][0], 10.0) && approx(m["wp"][1], 29.75) && approx(m["wp"][2], -20.0));
	CHECK(survives_the_wire(m));
}

TEST(ui_quest_limits_kinds_and_missing_waypoints)
{
	std::vector<Objective> many(20, Objective{"x", -3, -1, false, false});
	const json m = make_quest(std::string(300, 't'), "romance", many, std::nullopt, 0.0);
	CHECK(m["kind"] == "other" && m["title"].get<std::string>().size() == 128);
	CHECK(m["obj"].size() == 12 && m["obj"][0]["cur"] == 0 && m["obj"][0]["tot"] == 0);
	CHECK(m["wp"].is_null());
	CHECK(make_quest("a", "gig", {}, cb::Vec3{kNaN, 0.0, 0.0}, 0.0)["wp"].is_null());
	CHECK(make_quest("a", "gig", {}, std::nullopt, 0.0)["obj"].is_array());
	// an objective's text is cut at 256 bytes on a character boundary
	const std::vector<Objective> longText = {{accents(200), 0, 0, false, false}};
	const std::string text = make_quest("a", "side", longText, std::nullopt, 0.0)["obj"][0]["text"];
	CHECK(text.size() == 256 && valid_utf8(text));
}

// ---- pins -------------------------------------------------------------------------------------------------------------

TEST(ui_pins_map_kinds_and_cap_the_list)
{
	std::vector<Pin> pins;
	pins.push_back({{1.0, 2.0, 3.0}, "vendor", false});
	pins.push_back({{4.0, 5.0, 6.0}, "bounty", true});
	pins.push_back({{kNaN, 5.0, 6.0}, "quest", true});
	const json m = make_pins(pins, 0.5);
	CHECK(m["t"] == "pins" && m["list"].size() == 2);
	CHECK(m["list"][0]["kind"] == "vendor" && m["list"][0]["tr"] == false);
	CHECK(approx(m["list"][0]["p"][1], 2.5) && approx(m["list"][0]["p"][2], -2.0));
	CHECK(m["list"][1]["kind"] == "other" && m["list"][1]["tr"] == true);

	std::vector<Pin> many(50, Pin{{0.0, 0.0, 0.0}, "gig", false});
	CHECK(make_pins(many, 0.0)["list"].size() == 32);
	CHECK(make_pins({}, 0.0)["list"].empty());
}

// ---- prompt -----------------------------------------------------------------------------------------------------------

TEST(ui_prompt_carries_keys_holds_and_clears)
{
	const std::vector<PromptEntry> entries = {{"Talk", "F", false, false}, {"Pick up", "R", true, false},
											  {"Hack", "1", false, true}};
	const json m = make_prompt("Judy", entries);
	CHECK(m["t"] == "prompt" && m["title"] == "Judy" && m["list"].size() == 3);
	CHECK(m["list"][0]["text"] == "Talk" && m["list"][0]["key"] == "F" && m["list"][0]["hold"] == false);
	CHECK(m["list"][1]["hold"] == true && m["list"][2]["inactive"] == true);

	const json cleared = make_prompt("", {});
	CHECK(cleared["list"].empty() && cleared["title"] == "");

	std::vector<PromptEntry> many(10, PromptEntry{"x", "Space", false, false});
	const json capped = make_prompt("", many);
	CHECK(capped["list"].size() == 6 && capped["list"][0]["key"] == "Spa");
	const std::vector<PromptEntry> blank = {{"  ", "F", false, false}, {"Open", "F", false, false}};
	CHECK(make_prompt("", blank)["list"].size() == 1);
}

// ---- choices ----------------------------------------------------------------------------------------------------------

TEST(ui_choices_group_entries_by_hub_with_flags)
{
	const std::vector<ChoiceHub> hubs = {{7, "Judy"}, {9, "Panam"}};
	const std::vector<ChoiceEntry> entries = {{0, "Ask about the job", 4}, {0, "[Pay 500] Bribe", 2 | 1},
											  {1, "Leave", 0},          {1, "[Body 9] Rip the door", 8 | 32 | 64}};
	const json m = make_choices(hubs, entries, 7, 1, 4.25);
	CHECK(m["t"] == "choices" && m["hubs"].size() == 2 && m["active"] == 7 && m["sel"] == 1 && approx(m["left"], 4.25));
	CHECK(m["hubs"][0]["id"] == 7 && m["hubs"][0]["title"] == "Judy" && m["hubs"][0]["list"].size() == 2);
	CHECK(m["hubs"][0]["list"][0]["f"] == json::array({"quest"}));
	CHECK(m["hubs"][0]["list"][1]["f"] == json::array({"inactive", "pay"}));
	CHECK(m["hubs"][1]["list"][1]["f"] == json::array({"check_ok", "timed"})); // bit 64 is unknown: dropped
	CHECK(survives_the_wire(m));
}

TEST(ui_choices_cap_hubs_entries_and_bad_values)
{
	std::vector<ChoiceHub> hubs;
	for (int i = 0; i < 6; ++i)
		hubs.push_back({i, "h"});
	std::vector<ChoiceEntry> entries;
	for (int i = 0; i < 20; ++i)
		entries.push_back({i % 6, "c", 0});
	entries.push_back({-1, "bad hub", 0});
	const json m = make_choices(hubs, entries, 0, -7, kNaN);
	CHECK(m["hubs"].size() == 4 && m["sel"] == -1 && m["left"] == -1);
	size_t total = 0;
	for (const json &hub : m["hubs"])
		total += hub["list"].size();
	CHECK(total == 12);
	CHECK(make_choices(hubs, entries, 0, 0, -2.0)["left"] == -1);
	CHECK(make_choices(hubs, entries, 0, 0, 1e9)["left"] == 3600);

	const json cleared = make_choices({}, {}, -1, -1, -1.0);
	CHECK(cleared["hubs"].empty());
}

// ---- subtitles, calls, notifications, screens --------------------------------------------------------------------------

TEST(ui_subtitle_clamps_seconds_and_cuts_texts)
{
	const json m = make_subtitle(true, "line_42", "Jackie", "Preem, V!", 3.5);
	CHECK(m["t"] == "sub" && m["show"] == true && m["key"] == "line_42" && m["who"] == "Jackie");
	CHECK(m["text"] == "Preem, V!" && approx(m["secs"], 3.5));
	CHECK(make_subtitle(false, "", "", "", 99.0)["secs"] == 30);
	CHECK(make_subtitle(false, "", "", "", kNaN)["secs"] == 0);
	CHECK(make_subtitle(true, std::string(80, 'k'), accents(40), accents(200), 1.0)["who"].get<std::string>().size() == 64);
	CHECK(make_subtitle(true, std::string(80, 'k'), "", "", 1.0)["key"].get<std::string>().size() == 64);
	CHECK(make_subtitle(true, "", "", accents(200), 1.0)["text"].get<std::string>().size() == 256);
}

TEST(ui_call_needs_a_known_phase)
{
	const auto m = make_call("incoming", "Rogue", true, true);
	CHECK(m.has_value() && (*m)["t"] == "call" && (*m)["phase"] == "incoming" && (*m)["who"] == "Rogue");
	CHECK((*m)["audio"] == true && (*m)["reject"] == true);
	CHECK(make_call("end", "", false, false).has_value());
	CHECK(!make_call("ringing", "Rogue", true, true).has_value());
}

TEST(ui_notify_maps_unknown_kinds_to_info)
{
	const json m = make_notify("money", "Eddies", "+500 \xE2\x82\xAC$", 4.0);
	CHECK(m["t"] == "notify" && m["kind"] == "money" && m["title"] == "Eddies" && approx(m["secs"], 4.0));
	CHECK(make_notify("achievement", "x", "y", 1.0)["kind"] == "info");
	CHECK(make_notify("sms", std::string(200, 'a'), "", -1.0)["title"].get<std::string>().size() == 128);
	CHECK(make_notify("sms", "", "", -1.0)["secs"] == 0);
}

TEST(ui_screen_messages_accept_only_known_names)
{
	CHECK(make_screen_open("map").value() == json({{"t", "screen"}, {"open", "map"}}));
	CHECK(make_screen_open("cyberware").has_value() && make_screen_open("skills").has_value());
	CHECK(!make_screen_open("vendor").has_value() && !make_screen_open("").has_value());
	CHECK(make_screen_pass("vendor").value() == json({{"t", "screen"}, {"pass", "vendor"}}));
	CHECK(make_screen_pass("").value() == json({{"t", "screen"}, {"pass", ""}}));
	CHECK(make_screen_pass("loading").has_value() && make_screen_pass("death").has_value());
	CHECK(!make_screen_pass("pause").has_value());
}

// ---- reply ------------------------------------------------------------------------------------------------------------

TEST(ui_reply_builds_groups_lines_and_pins)
{
	ReplyBuilder reply;
	CHECK(!reply.end().has_value());
	CHECK(reply.begin("journal", 5));
	reply.line(1, "orphan line", 0); // no group yet: dropped
	reply.group(4000000000u, "The Heist", "main", true, false);
	reply.line(11, "Meet Dex", 1);
	reply.line(12, "Get the Flathead", 7); // state clamped to 2
	reply.group(2, "Gig", "contract", false, true); // unknown kind -> other
	reply.pin({1.0, 2.0, 3.0}, "fasttravel", "Japantown", 4, 0.0);
	reply.pin({kNaN, 2.0, 3.0}, "quest", "bad", -1, 0.0);
	const auto r = reply.end();
	CHECK(r.has_value() && !reply.active());
	const json &m = r->message;
	CHECK(m["t"] == "reply" && m["kind"] == "journal" && m["id"] == 5);
	CHECK(m["groups"].size() == 2 && m["groups"][0]["hash"] == 4000000000u && m["groups"][0]["tracked"] == true);
	CHECK(m["groups"][0]["kind"] == "main" && m["groups"][0]["lines"].size() == 2);
	CHECK(m["groups"][0]["lines"][1]["state"] == 2 && m["groups"][0]["lines"][0]["text"] == "Meet Dex");
	CHECK(m["groups"][1]["kind"] == "other" && m["groups"][1]["done"] == true && m["groups"][1]["lines"].empty());
	CHECK(m["pins"].size() == 1 && m["pins"][0]["i"] == 4 && m["pins"][0]["name"] == "Japantown");
	CHECK(m["pins"][0]["kind"] == "fasttravel" && approx(m["pins"][0]["p"][1], 3.0));
	CHECK(r->groups == 2 && r->lines == 2 && r->pins == 1 && r->dropped == 2 && r->bytes == m.dump().size());
	CHECK(!reply.begin("inventory", 1) && !reply.active());
}

TEST(ui_vendor_reply_carries_the_price_in_state)
{
	// megaplan 7-D1: groups = stock categories, lines = items (text, price in `state`, the item hash in `hash`)
	ReplyBuilder reply;
	CHECK(reply.begin("vendor", 9));
	reply.group(77, "Pistols", "other", false, false);
	reply.line(4000000001u, "Liberty", 12500);   // a price far above the journal's 0..2
	reply.line(5, "Free sample", -3);            // never negative
	reply.line(6, "Overpriced", 2147483647);     // Int32 max stays
	reply.group(78, "Armor", "main", false, false); // not a vendor group kind: buy
	reply.group(79, "Your items", "sell", false, true); // what V can sell keeps its kind (megaplan 1.9 amendment)
	reply.line(4000000002u, "Broken radio", 35);
	reply.group(80, "Cyberware", "buy", false, false);
	const auto r = reply.end();
	CHECK(r.has_value());
	const json &m = r->message;
	CHECK(m["t"] == "reply" && m["kind"] == "vendor" && m["id"] == 9 && m["groups"].size() == 4);
	CHECK(m["groups"][0]["kind"] == "buy" && m["groups"][1]["kind"] == "buy" && m["groups"][2]["kind"] == "sell" &&
		  m["groups"][3]["kind"] == "buy");
	CHECK(m["groups"][2]["lines"][0]["state"] == 35);
	CHECK(m["groups"][0]["title"] == "Pistols" && m["groups"][0]["lines"].size() == 3);
	const json &lines = m["groups"][0]["lines"];
	CHECK(lines[0]["hash"] == 4000000001u && lines[0]["text"] == "Liberty" && lines[0]["state"] == 12500);
	CHECK(lines[1]["state"] == 0 && lines[2]["state"] == 2147483647);
	// the other kinds keep their 0..2 state and their group kinds (buy and sell mean nothing there: other)
	CHECK(reply.begin("journal", 1));
	reply.group(1, "Q", "side", false, false);
	reply.line(1, "x", 12500);
	reply.group(2, "R", "sell", false, false);
	const json journal = reply.end()->message;
	CHECK(journal["groups"][0]["lines"][0]["state"] == 2);
	CHECK(journal["groups"][0]["kind"] == "side" && journal["groups"][1]["kind"] == "other");
	CHECK(r->bytes == m.dump().size());
}

TEST(ui_reply_caps_counts_and_bytes)
{
	ReplyBuilder reply;
	CHECK(reply.begin("phone", 1));
	for (uint32_t g = 0; g < 200; ++g)
	{
		reply.group(g, "Contact", "sms", false, false);
		for (uint32_t l = 0; l < 5; ++l)
			reply.line(l, "Hey", 0);
	}
	for (int p = 0; p < 1100; ++p)
		reply.pin({0.0, 0.0, 0.0}, "quest", "p", -1, 0.0);
	const auto r = reply.end();
	CHECK(r->groups == 128 && r->lines == 512 && r->pins == 1024);
	CHECK(r->dropped == (200 - 128) + (1000 - 512) + (1100 - 1024));

	// 256 KB: big lines stop fitting before the line cap
	CHECK(reply.begin("journal", 2));
	reply.group(1, "Big", "side", false, false);
	const std::string text = std::string(250, 'x');
	for (int l = 0; l < 2000; ++l)
		reply.line(uint32_t(l), text, 0);
	const auto big = reply.end();
	CHECK(big->bytes <= ReplyBuilder::kMaxBytes && big->bytes == big->message.dump().size());
	CHECK(big->lines == 512 || big->dropped > 0);
	// 512 lines of 256 quotes (each escaped to two bytes on the wire): past 256 KB, so the byte cap bites
	CHECK(reply.begin("journal", 3));
	for (uint32_t g = 0; g < 128; ++g)
	{
		reply.group(g, std::string(128, '\\'), "main", false, false);
		for (int l = 0; l < 4; ++l)
			reply.line(uint32_t(l), std::string(256, '"'), 0);
	}
	const auto capped = reply.end();
	CHECK(capped->bytes <= ReplyBuilder::kMaxBytes && capped->dropped > 0);
	CHECK(capped->bytes == capped->message.dump().size() && survives_the_wire(capped->message));
}

// ---- gates ------------------------------------------------------------------------------------------------------------

TEST(ui_gate_dedupes_and_rate_limits)
{
	Gate gate;
	CHECK(gate.admit("a", "u1", 0.0, 1.0));
	CHECK(!gate.admit("a", "u1", 5.0, 1.0));  // unchanged: never twice
	CHECK(!gate.admit("b", "u1", 0.5, 1.0));  // only the slow part changed, too soon
	CHECK(gate.admit("b", "u1", 1.0, 1.0));   // ... after the interval
	CHECK(gate.admit("c", "u2", 1.1, 1.0));   // the urgent part changed: at once
	gate.reset();
	CHECK(gate.admit("c", "u2", 1.2, 1.0));   // Minecraft reconnected: sent again

	Gate events;
	CHECK(events.admit_event("x", 0.0, 1.0));
	CHECK(!events.admit_event("x", 0.5, 1.0));
	CHECK(events.admit_event("x", 1.6, 1.0));
	CHECK(events.admit_event("y", 1.7, 1.0));
}

// ---- uiact and the action queue ----------------------------------------------------------------------------------------

TEST(ui_uiact_validation)
{
	const auto ok = parse_uiact(json::parse(R"({"t":"uiact","a":"track","arg":"q101","hash":4294967295,"id":12})"));
	CHECK(ok.has_value() && ok->a == "track" && ok->arg == "q101" && ok->hash == 4294967295u && ok->id == 12);
	const auto bare = parse_uiact(json::parse(R"({"t":"uiact","a":"save"})"));
	CHECK(bare.has_value() && bare->arg.empty() && bare->hash == 0 && bare->id == 0);
	CHECK(parse_uiact(json::parse(R"({"t":"uiact","a":"save","hash":3.0})")).has_value());

	std::string why;
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"pause"})"), &why) && !why.empty()); // not a uiact
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"teleport"})")));
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact"})")));
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"track","hash":-1})")));
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"track","hash":4294967296})")));
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"track","hash":1.5})")));
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"track","hash":"7"})")));
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"track","id":2147483648})")));
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"track","arg":5})")));
	CHECK(!parse_uiact(json{{"t", "uiact"}, {"a", "track"}, {"arg", std::string(65, 'a')}}));
	CHECK(parse_uiact(json{{"t", "uiact"}, {"a", "track"}, {"arg", std::string(64, 'a')}}).has_value());
	CHECK(!parse_uiact(json::array()));
}

TEST(ui_buy_and_sell_need_the_item_hash)
{
	// megaplan 7-D1 / 7-V1 and the 1.8 amendment: `uiact buy|sell`, hash = the item, id = the line's index in its side,
	// arg = the quantity
	const auto buy = parse_uiact(json::parse(R"({"t":"uiact","a":"buy","arg":"10","hash":4000000001,"id":9})"));
	CHECK(buy.has_value() && buy->a == "buy" && buy->hash == 4000000001u && buy->id == 9 && buy->arg == "10");
	const auto sell = parse_uiact(json::parse(R"({"t":"uiact","a":"sell","hash":17})"));
	CHECK(sell.has_value() && sell->a == "sell" && sell->hash == 17 && sell->id == 0);
	std::string why;
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"buy"})"), &why) && why.find("hash") != std::string::npos);
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"sell"})")));
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"buy","hash":0})"))); // no item
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"buy","hash":-4})")));
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"buy","hash":1.5})")));
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"sell","hash":"7"})")));
	CHECK(!parse_uiact(json::parse(R"({"t":"uiact","a":"buy","hash":4294967296})")));
	// they queue like any other action, and are dropped while Cyberblock is off
	UiActions q;
	CHECK(q.push(*buy, true) && q.pop(true)->a == "buy");
	CHECK(!q.push(*sell, false) && !q.pop(true).has_value());
}

TEST(ui_actions_drop_everything_but_resume_while_off)
{
	UiActions q;
	CHECK(!q.push({"save"}, false) && q.empty() && q.dropped() == 1);
	CHECK(q.push({"save"}, true) && q.push({"track", "q", 3, 1}, true) && q.size() == 2);
	// went Off before the script drained them: only resumes come out
	q.reset_pause();
	const auto first = q.pop(false);
	CHECK(first.has_value() && first->a == "resume" && q.empty() && q.dropped() == 3);
	CHECK(!q.pop(false).has_value());
}

TEST(ui_actions_pause_is_level_triggered)
{
	UiActions q;
	q.set_pause(false);
	CHECK(q.empty() && !q.paused());
	q.set_pause(true);
	q.set_pause(true); // no new edge
	CHECK(q.size() == 1 && q.paused());
	CHECK(q.pop(true)->a == "pause");
	q.set_pause(false);
	CHECK(q.pop(true)->a == "resume" && !q.paused());
	// a link drop (or hello, or Off) while paused: resume, and the next status.pause pauses again
	q.set_pause(true);
	(void)q.pop(true);
	CHECK(q.reset_pause() && !q.paused());
	CHECK(q.pop(true)->a == "resume");
	q.set_pause(true);
	CHECK(q.pop(true)->a == "pause");
	// a reset while not paused still queues one resume, never two in a row
	q.set_pause(false);
	(void)q.pop(true);
	CHECK(!q.reset_pause());
	CHECK(!q.reset_pause());
	CHECK(q.size() == 1 && q.pop(true)->a == "resume");
}

TEST(ui_actions_are_bounded_and_keep_pause_edges)
{
	UiActions q;
	for (size_t i = 0; i < UiActions::kMax + 5; ++i)
		q.push({"track", "", uint32_t(i), 0}, true);
	CHECK(q.size() == UiActions::kMax && q.dropped() == 5);
	q.set_pause(true); // evicts the oldest action, never the pause
	CHECK(q.size() == UiActions::kMax);
	UiAction last;
	while (const auto a = q.pop(true))
		last = *a;
	CHECK(last.a == "pause");
}

TEST(ui_reply_pin_carries_its_district_only_when_known)
{
	ReplyBuilder reply;
	CHECK(reply.begin("atlas", 7));
	reply.pin({1.0, 2.0, 3.0}, "fasttravel", "Japantown West", 12, 0.0, "Westbrook");
	reply.pin({1.0, 2.0, 3.0}, "vendor", "Gun shop", -1, 0.0, "");
	reply.pin({1.0, 2.0, 3.0}, "vendor", "Ctrl", -1, 0.0, std::string(80, 'd') + "");
	const auto r = reply.end();
	const nlohmann::json &pins = r->message["pins"];
	CHECK(pins.size() == 3 && pins[0]["d"] == "Westbrook" && !pins[1].contains("d"));
	CHECK(pins[2]["d"].get<std::string>().size() == 64); // cut to kNameBytes, the control character stripped
}
