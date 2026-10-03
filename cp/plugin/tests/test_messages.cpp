// Layers 3-6 in the DLL: the messages for Minecraft that natives build from what redscript submits (plan 2026-10-03
// section 1.2, Cyberpunk -> Minecraft), the `cam` message and its new `cr` field, and UTF-8 truncation.
#include "test.hpp"
#include "wire.hpp"
#include "world.hpp"
#include <cmath>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace
{
	using nlohmann::json;
	using cb::world::make_hackresult;
	using cb::world::make_killed;
	using cb::world::make_mobdmg;
	using cb::world::make_pcells;
	using cb::world::make_scaninfo;
	using cb::world::make_unlead;

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

	std::optional<json> scan(bool ok, const std::string &name = "Officer Test", const std::string &faction = "NCPD",
							 const std::string &threat = "high", double hp = 0.8)
	{
		return make_scaninfo(7, ok, 123456789012ull, name, faction, threat, hp, false, {1.0, 2.0, 3.0}, 0.6, 1.8);
	}

	std::vector<cb::Vec3> distinct_hits(int count)
	{
		std::vector<cb::Vec3> hits;
		for (int i = 0; i < count; ++i)
			hits.push_back({i + 0.5, 0.5, 0.5}); // Cyberpunk (x, y, z) -> cell (i, 0, -1)
		return hits;
	}
}

// ---- mobdmg ------------------------------------------------------------------------------------------------------------

TEST(mobdmg_carries_the_mob_the_fraction_and_the_attacker)
{
	const auto m = make_mobdmg(7, 0.5, 18446744073709551615ull, true);
	CHECK(m.has_value());
	CHECK(m->at("t") == "mobdmg" && m->at("id") == 7 && approx(m->at("frac"), 0.5));
	CHECK(m->at("by") == "18446744073709551615"); // a decimal string: it does not fit a JSON double
	CHECK(m->at("id").is_number_integer() && survives_the_wire(*m));
}

TEST(mobdmg_by_is_null_unless_has_by)
{
	CHECK(make_mobdmg(7, 0.5, 99, false)->at("by").is_null());
	CHECK(make_mobdmg(7, 0.5, 0, true)->at("by") == "0");
}

TEST(mobdmg_fraction_is_clamped_to_0_4_and_must_be_finite)
{
	CHECK(approx(make_mobdmg(1, 7.5, 0, false)->at("frac"), 4));
	CHECK(approx(make_mobdmg(1, 4.0, 0, false)->at("frac"), 4));
	CHECK(approx(make_mobdmg(1, -1.0, 0, false)->at("frac"), 0));
	CHECK(approx(make_mobdmg(1, 0.0, 0, false)->at("frac"), 0));
	CHECK(!make_mobdmg(1, kNaN, 0, false) && !make_mobdmg(1, kInf, 0, false) && !make_mobdmg(1, -kInf, 0, false));
}

TEST(mobdmg_mob_id_must_not_be_negative)
{
	CHECK(make_mobdmg(0, 0.1, 0, false).has_value());
	CHECK(make_mobdmg(2147483647, 0.1, 0, false).has_value());
	CHECK(!make_mobdmg(-1, 0.1, 0, false));
}

// ---- pcells ------------------------------------------------------------------------------------------------------------

TEST(pcells_are_minecraft_block_cells_floored_per_axis_after_conversion)
{
	// Cyberpunk (10.5, 20.2, 64.7) -> Minecraft (10.5, 64.7, -20.2) -> cell (10, 64, -21)
	const auto m = make_pcells(std::vector<cb::Vec3>{{10.5, 20.2, 64.7}});
	CHECK(m.has_value() && m->at("t") == "pcells");
	CHECK(m->at("c") == json::array({10, 64, -21}));
	// negative coordinates floor away from zero: Cyberpunk (-0.5, -0.5, -0.5) -> Minecraft (-0.5, -0.5, 0.5) -> (-1, -1, 0)
	CHECK(make_pcells(std::vector<cb::Vec3>{{-0.5, -0.5, -0.5}})->at("c") == json::array({-1, -1, 0}));
	// a point exactly on a cell corner belongs to the cell above it: Cyberpunk (3, -5, 7) -> Minecraft (3, 7, 5)
	CHECK(make_pcells(std::vector<cb::Vec3>{{3.0, -5.0, 7.0}})->at("c") == json::array({3, 7, 5}));
	CHECK(survives_the_wire(*m));
}

TEST(pcells_repeated_cells_are_sent_once_in_first_seen_order)
{
	const auto m = make_pcells(std::vector<cb::Vec3>{{1.1, 1.1, 1.1}, {5.5, 5.5, 5.5}, {1.9, 1.9, 1.9}, {5.1, 5.9, 5.2}, {2.5, 1.1, 1.1}});
	CHECK(m.has_value());
	CHECK(m->at("c") == json::array({1, 1, -2, 5, 5, -6, 2, 1, -2}));
}

TEST(pcells_hold_at_most_64_cells_and_drop_the_rest)
{
	const auto m = make_pcells(distinct_hits(100));
	CHECK(m.has_value() && m->at("c").size() == 192);
	CHECK(m->at("c")[0] == 0 && m->at("c")[189] == 63); // the first 64 hits are kept
	CHECK(make_pcells(distinct_hits(64))->at("c").size() == 192);
	CHECK(make_pcells(distinct_hits(65))->at("c").size() == 192);
	CHECK(make_pcells(distinct_hits(1))->at("c").size() == 3);
	// repeats do not use up the 64: 200 hits in three cells are three cells
	std::vector<cb::Vec3> few;
	for (int i = 0; i < 200; ++i)
		few.push_back({i % 3 + 0.5, 0.5, 0.5});
	CHECK(make_pcells(few)->at("c").size() == 9);
}

TEST(pcells_skip_bad_points_and_are_absent_without_cells)
{
	CHECK(!make_pcells(std::vector<cb::Vec3>{}));
	CHECK(!make_pcells(std::vector<cb::Vec3>{{kNaN, 0, 0}, {0, kInf, 0}, {0, 0, -kInf}, {3e7, 0, 0}, {0, 0, 3e7}, {0, -3e7, 0}}));
	const auto m = make_pcells(std::vector<cb::Vec3>{{kNaN, 0, 0}, {1.5, 1.5, 1.5}, {1e12, 0, 0}});
	CHECK(m.has_value() && m->at("c") == json::array({1, 1, -2}));
	CHECK(make_pcells(std::vector<cb::Vec3>{{29999999.5, 0, 0}}).has_value()); // |c| < 3e7
}

// ---- unlead and killed -------------------------------------------------------------------------------------------------

TEST(unlead_names_the_target_as_a_decimal_string)
{
	const json m = make_unlead(18446744073709551615ull);
	CHECK(m.at("t") == "unlead" && m.at("target") == "18446744073709551615" && m.size() == 2);
	CHECK(make_unlead(0).at("target") == "0");
	CHECK(survives_the_wire(m));
}

TEST(killed_reports_the_kind_and_whether_it_was_hostile)
{
	const json human = make_killed(77, false, true);
	CHECK(human.at("t") == "killed" && human.at("target") == "77" && human.at("kind") == "human" && human.at("hostile") == true);
	const json vehicle = make_killed(18446744073709551615ull, true, false);
	CHECK(vehicle.at("kind") == "vehicle" && vehicle.at("hostile") == false && vehicle.at("target") == "18446744073709551615");
	CHECK(survives_the_wire(human) && survives_the_wire(vehicle));
}

// ---- scaninfo ----------------------------------------------------------------------------------------------------------

TEST(scaninfo_fills_every_field)
{
	const auto m = make_scaninfo(7, true, 18446744073709551615ull, "Officer Test", "NCPD", "high", 0.8, true, {1.0, 2.0, 3.0}, 0.6, 1.8);
	CHECK(m.has_value());
	CHECK(m->at("t") == "scaninfo" && m->at("id") == 7 && m->at("ok") == true);
	CHECK(m->at("target") == "18446744073709551615" && m->at("name") == "Officer Test" && m->at("faction") == "NCPD");
	CHECK(m->at("threat") == "high" && approx(m->at("hp"), 0.8) && m->at("device") == true);
	CHECK(approx(m->at("p")[0], 1) && approx(m->at("p")[1], 3) && approx(m->at("p")[2], -2)); // cp (1,2,3) -> mc (1,3,-2)
	CHECK(m->at("size").size() == 2 && approx(m->at("size")[0], 0.6) && approx(m->at("size")[1], 1.8));
	CHECK(m->at("p").size() == 3 && survives_the_wire(*m));
}

TEST(scaninfo_threat_is_one_of_the_five_levels_else_none)
{
	for (const char *level : {"none", "low", "medium", "high", "extreme"})
		CHECK(scan(true, "x", "y", level)->at("threat") == level);
	for (const char *bad : {"", "HIGH", "critical", "high ", "med"})
		CHECK(scan(true, "x", "y", bad)->at("threat") == "none");
}

TEST(scaninfo_hp_is_clamped_to_0_1)
{
	CHECK(approx(scan(true, "x", "y", "low", 1.7)->at("hp"), 1));
	CHECK(approx(scan(true, "x", "y", "low", -0.2)->at("hp"), 0));
	CHECK(approx(scan(true, "x", "y", "low", 1.0)->at("hp"), 1));
	CHECK(approx(scan(true, "x", "y", "low", kNaN)->at("hp"), 0));
	CHECK(approx(scan(true, "x", "y", "low", kInf)->at("hp"), 0)); // not a finite number: 0, not "full health"
}

TEST(scaninfo_text_is_cut_to_64_bytes_on_a_character_boundary)
{
	const std::string ascii64(64, 'a');
	CHECK(scan(true, ascii64, ascii64)->at("name") == ascii64 && scan(true, ascii64, ascii64)->at("faction") == ascii64);
	CHECK(scan(true, ascii64 + "b", ascii64 + "b")->at("name") == ascii64);
	CHECK(scan(true, ascii64 + "b", ascii64 + "b")->at("faction") == ascii64);
	const std::string e = "\xc3\xa9"; // e acute, 2 bytes
	CHECK(scan(true, std::string(62, 'a') + e)->at("name") == std::string(62, 'a') + e); // exactly 64 bytes: kept
	CHECK(scan(true, std::string(63, 'a') + e)->at("name") == std::string(63, 'a'));     // would split the character: dropped whole
	// an emoji (4 bytes) then "tail" after `pad` letters: the emoji is kept whole while it ends by byte 64 (pad <= 60),
	// and what follows it fills the rest
	const std::string face = "\xf0\x9f\x98\x80";
	const std::pair<int, std::string> expected[] = {{58, std::string(58, 'a') + face + "ta"}, {59, std::string(59, 'a') + face + "t"},
													{60, std::string(60, 'a') + face},        {61, std::string(61, 'a')},
													{62, std::string(62, 'a')},               {63, std::string(63, 'a')}};
	for (const auto &[pad, want] : expected)
	{
		const auto m = scan(true, std::string(size_t(pad), 'a') + face + "tail", "NCPD");
		CHECK(m->at("name") == want);
		CHECK(survives_the_wire(*m));
	}
}

TEST(a_failed_scan_is_the_empty_reply)
{
	const auto m = make_scaninfo(9, false, 55, "Leftover", "Leftover", "extreme", 0.5, true, {4, 5, 6}, 2.0, 2.0);
	CHECK(m.has_value() && m->at("ok") == false && m->at("id") == 9);
	CHECK(m->at("name") == "" && m->at("faction") == "" && m->at("threat") == "none");
	CHECK(m->at("target") == "0" && approx(m->at("hp"), 0) && m->at("device") == false);
	CHECK(approx(m->at("p")[0], 0) && approx(m->at("p")[1], 0) && approx(m->at("p")[2], 0));
	CHECK(approx(m->at("size")[0], 0) && approx(m->at("size")[1], 0));
}

TEST(scaninfo_always_answers_unless_the_id_is_invalid)
{
	CHECK(!make_scaninfo(-1, true, 1, "x", "y", "low", 0.5, false, {}, 1, 1));
	CHECK(make_scaninfo(0, false, 0, "", "", "", 0, false, {}, 0, 0).has_value());
	// a non-finite position or size cannot go on the wire: zeros, but the request is still answered
	const auto m = make_scaninfo(3, true, 1, "x", "y", "low", 0.5, false, {kNaN, 1, 1}, kInf, kNaN);
	CHECK(m.has_value() && survives_the_wire(*m));
	CHECK(approx(m->at("p")[0], 0) && approx(m->at("p")[1], 0) && approx(m->at("size")[0], 0) && approx(m->at("size")[1], 0));
	CHECK(approx(make_scaninfo(3, true, 1, "x", "y", "low", 0.5, false, {}, -2.0, 1.0)->at("size")[0], 0)); // never negative
}

// ---- hackresult --------------------------------------------------------------------------------------------------------

TEST(hackresult_on_success_has_an_empty_reason)
{
	const auto ok = make_hackresult(12, true, "left over from before");
	CHECK(ok.has_value() && ok->at("t") == "hackresult" && ok->at("id") == 12 && ok->at("ok") == true && ok->at("reason") == "");
	const auto failed = make_hackresult(13, false, "target out of range");
	CHECK(failed->at("ok") == false && failed->at("reason") == "target out of range");
	CHECK(survives_the_wire(*ok) && survives_the_wire(*failed));
}

TEST(hackresult_reason_is_cut_to_64_bytes_and_the_id_must_not_be_negative)
{
	const std::string ascii64(64, 'r');
	CHECK(make_hackresult(1, false, ascii64 + "x")->at("reason") == ascii64);
	CHECK(make_hackresult(1, false, std::string(63, 'r') + "\xc3\xa9")->at("reason") == std::string(63, 'r'));
	CHECK(make_hackresult(0, true, "").has_value());
	CHECK(!make_hackresult(-1, false, "no"));
}

// ---- cam.cr ------------------------------------------------------------------------------------------------------------

TEST(cam_carries_the_stance_in_cr)
{
	const json standing = cb::world::make_cam(41, {1.5, 65.0, -2.5}, {150.0, 12.5}, 58.7, {1.5, 63.4, -2.5}, 140.0, false);
	CHECK(standing.at("t") == "cam" && standing.at("f") == 41 && standing.at("fp") == true);
	CHECK(standing.at("cr") == false && standing.at("cr").is_boolean());
	CHECK(standing.at("p") == json::array({1.5, 65.0, -2.5}) && standing.at("pl") == json::array({1.5, 63.4, -2.5}));
	CHECK(standing.at("r") == json::array({150.0, 12.5, 0.0}) && approx(standing.at("fov"), 58.7) && approx(standing.at("h"), 140.0));
	const json crouching = cb::world::make_cam(42, {}, {}, 70.0, {}, 0.0, true);
	CHECK(crouching.at("cr") == true && crouching.at("cr").is_boolean());
	CHECK(survives_the_wire(standing));
}

// ---- truncate_utf8 -----------------------------------------------------------------------------------------------------

TEST(truncate_utf8_never_splits_a_character)
{
	using cb::wire::truncate_utf8;
	CHECK(truncate_utf8("", 64).empty() && truncate_utf8("abc", 64) == "abc" && truncate_utf8("abc", 3) == "abc");
	CHECK(truncate_utf8("abc", 2) == "ab" && truncate_utf8("abc", 0).empty());
	const std::string text = "a\xc3\xa9\xe2\x82\xac\xf0\x9f\x98\x80"; // a, e acute (2), euro (3), emoji (4): 10 bytes
	CHECK(truncate_utf8(text, 10) == text);
	CHECK(truncate_utf8(text, 9) == "a\xc3\xa9\xe2\x82\xac"); // the emoji would be cut
	CHECK(truncate_utf8(text, 7) == "a\xc3\xa9\xe2\x82\xac");
	CHECK(truncate_utf8(text, 6) == "a\xc3\xa9\xe2\x82\xac"); // the euro sign ends at byte 5: it fits
	CHECK(truncate_utf8(text, 5) == "a\xc3\xa9"); // 5 bytes would cut the euro sign in two
	CHECK(truncate_utf8(text, 4) == "a\xc3\xa9");
	CHECK(truncate_utf8(text, 2) == "a");                     // e acute would be cut
	CHECK(truncate_utf8(text, 1) == "a");
	CHECK(truncate_utf8(text, 0).empty());
	for (size_t n = 0; n <= text.size(); ++n)
		CHECK(json(truncate_utf8(text, n)).dump().size() > 0); // always valid UTF-8: dump() throws on a split character
}
