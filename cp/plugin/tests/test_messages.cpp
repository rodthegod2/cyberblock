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
		return make_scaninfo(7, ok, 123456789012ull, name, faction, threat, hp, false, {1.0, 2.0, 3.0}, 0.6, 1.8, 0.0);
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
	const auto m = make_pcells(std::vector<cb::Vec3>{{10.5, 20.2, 64.7}}, 0.0);
	CHECK(m.has_value() && m->at("t") == "pcells");
	CHECK(m->at("c") == json::array({10, 64, -21}));
	// negative coordinates floor away from zero: Cyberpunk (-0.5, -0.5, -0.5) -> Minecraft (-0.5, -0.5, 0.5) -> (-1, -1, 0)
	CHECK(make_pcells(std::vector<cb::Vec3>{{-0.5, -0.5, -0.5}}, 0.0)->at("c") == json::array({-1, -1, 0}));
	// a point exactly on a cell corner belongs to the cell above it: Cyberpunk (3, -5, 7) -> Minecraft (3, 7, 5)
	CHECK(make_pcells(std::vector<cb::Vec3>{{3.0, -5.0, 7.0}}, 0.0)->at("c") == json::array({3, 7, 5}));
	CHECK(survives_the_wire(*m));
}

TEST(pcells_repeated_cells_are_sent_once_in_first_seen_order)
{
	const auto m = make_pcells(std::vector<cb::Vec3>{{1.1, 1.1, 1.1}, {5.5, 5.5, 5.5}, {1.9, 1.9, 1.9}, {5.1, 5.9, 5.2}, {2.5, 1.1, 1.1}}, 0.0);
	CHECK(m.has_value());
	CHECK(m->at("c") == json::array({1, 1, -2, 5, 5, -6, 2, 1, -2}));
}

TEST(pcells_hold_at_most_64_cells_and_drop_the_rest)
{
	const auto m = make_pcells(distinct_hits(100), 0.0);
	CHECK(m.has_value() && m->at("c").size() == 192);
	CHECK(m->at("c")[0] == 0 && m->at("c")[189] == 63); // the first 64 hits are kept
	CHECK(make_pcells(distinct_hits(64), 0.0)->at("c").size() == 192);
	CHECK(make_pcells(distinct_hits(65), 0.0)->at("c").size() == 192);
	CHECK(make_pcells(distinct_hits(1), 0.0)->at("c").size() == 3);
	// repeats do not use up the 64: 200 hits in three cells are three cells
	std::vector<cb::Vec3> few;
	for (int i = 0; i < 200; ++i)
		few.push_back({i % 3 + 0.5, 0.5, 0.5});
	CHECK(make_pcells(few, 0.0)->at("c").size() == 9);
}

TEST(pcells_skip_bad_points_and_are_absent_without_cells)
{
	CHECK(!make_pcells(std::vector<cb::Vec3>{}, 0.0));
	CHECK(!make_pcells(std::vector<cb::Vec3>{{kNaN, 0, 0}, {0, kInf, 0}, {0, 0, -kInf}, {3e7, 0, 0}, {0, 0, 3e7}, {0, -3e7, 0}}, 0.0));
	const auto m = make_pcells(std::vector<cb::Vec3>{{kNaN, 0, 0}, {1.5, 1.5, 1.5}, {1e12, 0, 0}}, 0.0);
	CHECK(m.has_value() && m->at("c") == json::array({1, 1, -2}));
	CHECK(make_pcells(std::vector<cb::Vec3>{{29999999.5, 0, 0}}, 0.0).has_value()); // |c| < 3e7
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
	const auto m = make_scaninfo(7, true, 18446744073709551615ull, "Officer Test", "NCPD", "high", 0.8, true, {1.0, 2.0, 3.0}, 0.6, 1.8, 0.0);
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
	const auto m = make_scaninfo(9, false, 55, "Leftover", "Leftover", "extreme", 0.5, true, {4, 5, 6}, 2.0, 2.0, 0.0);
	CHECK(m.has_value() && m->at("ok") == false && m->at("id") == 9);
	CHECK(m->at("name") == "" && m->at("faction") == "" && m->at("threat") == "none");
	CHECK(m->at("target") == "0" && approx(m->at("hp"), 0) && m->at("device") == false);
	CHECK(approx(m->at("p")[0], 0) && approx(m->at("p")[1], 0) && approx(m->at("p")[2], 0));
	CHECK(approx(m->at("size")[0], 0) && approx(m->at("size")[1], 0));
}

TEST(scaninfo_always_answers_unless_the_id_is_invalid)
{
	CHECK(!make_scaninfo(-1, true, 1, "x", "y", "low", 0.5, false, {}, 1, 1, 0.0));
	CHECK(make_scaninfo(0, false, 0, "", "", "", 0, false, {}, 0, 0, 0.0).has_value());
	// a non-finite position or size cannot go on the wire: zeros, but the request is still answered
	const auto m = make_scaninfo(3, true, 1, "x", "y", "low", 0.5, false, {kNaN, 1, 1}, kInf, kNaN, 0.0);
	CHECK(m.has_value() && survives_the_wire(*m));
	CHECK(approx(m->at("p")[0], 0) && approx(m->at("p")[1], 0) && approx(m->at("size")[0], 0) && approx(m->at("size")[1], 0));
	CHECK(approx(make_scaninfo(3, true, 1, "x", "y", "low", 0.5, false, {}, -2.0, 1.0, 0.0)->at("size")[0], 0)); // never negative
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

// ---- re-test 3 (plan 2026-10-03-retest3-polish section 1.1): upload, kind and hackspread -------------------------------

TEST(hackresult_upload_is_clamped_to_0_30_and_non_finite_is_0)
{
	CHECK(approx(make_hackresult(1, true, "", 2.5, "squad")->at("upload"), 2.5));
	CHECK(approx(make_hackresult(1, true, "", -1.0, "squad")->at("upload"), 0.0));
	CHECK(approx(make_hackresult(1, true, "", 31.0, "squad")->at("upload"), 30.0));
	CHECK(approx(make_hackresult(1, true, "", kNaN, "squad")->at("upload"), 0.0));
	CHECK(approx(make_hackresult(1, true, "", kInf, "squad")->at("upload"), 0.0));
	CHECK(approx(make_hackresult(1, true, "", 1.23456, "squad")->at("upload"), 1.235)); // rounded to ms
	CHECK(make_hackresult(1, true, "")->at("upload") == 0.0); // the defaults: instant, no kind
	CHECK(survives_the_wire(*make_hackresult(1, true, "", kNaN, "crowd")));
}

TEST(hackresult_kind_is_one_of_the_seven_or_left_out)
{
	for (const char *kind : cb::world::kHackKinds)
		CHECK(make_hackresult(4, true, "", 1.0, kind)->at("kind") == kind);
	CHECK(!make_hackresult(4, true, "", 1.0, "pedestrian")->contains("kind"));
	CHECK(!make_hackresult(4, true, "", 1.0, "")->contains("kind"));
	CHECK(!make_hackresult(4, true, "", 1.0, "Squad")->contains("kind"));
}

TEST(hackresult_leaves_upload_and_kind_out_when_not_ok)
{
	const auto failed = make_hackresult(5, false, "target gone", 3.0, "squad");
	CHECK(failed.has_value() && !failed->contains("upload") && !failed->contains("kind"));
	CHECK(failed->size() == 4 && failed->at("reason") == "target gone");
}

TEST(hackspread_carries_the_id_a_decimal_target_and_why)
{
	const auto m = cb::world::make_hackspread(9, 18446744073709551615ull, "chain");
	CHECK(m.has_value() && m->at("t") == "hackspread" && m->at("id") == 9 && m->at("why") == "chain");
	CHECK(m->at("target") == "18446744073709551615" && m->size() == 4 && survives_the_wire(*m));
	for (const char *why : cb::world::kSpreadWhy)
		CHECK(cb::world::make_hackspread(1, 2, why)->at("why") == why);
	CHECK(!cb::world::make_hackspread(1, 2, "contagion"));
	CHECK(!cb::world::make_hackspread(1, 2, ""));
	CHECK(!cb::world::make_hackspread(-1, 2, "ping"));
}

TEST(hackspread_allows_16_per_id_and_counts_the_rest)
{
	cb::world::SpreadCounter counter;
	for (int i = 0; i < cb::world::kMaxSpreadPerId; ++i)
		CHECK(counter.allow(7));
	CHECK(!counter.allow(7) && !counter.allow(7));
	CHECK(counter.dropped() == 2);
	CHECK(counter.allow(8)); // another id has its own budget
	// the counter remembers a bounded number of ids: an old id forgotten long ago starts over
	for (int id = 100; id < 100 + int(cb::world::SpreadCounter::kRemembered); ++id)
		CHECK(counter.allow(id));
	CHECK(counter.allow(7));
	CHECK(counter.dropped() == 2);
}

// ---- cam flags ---------------------------------------------------------------------------------------------------------

TEST(cam_carries_the_stance_in_cr)
{
	const json standing = cb::world::make_cam(41, {1.5, 65.0, -2.5}, {150.0, 12.5}, 58.7, {1.5, 63.4, -2.5}, 140.0, {}, true, false, 0);
	CHECK(standing.at("t") == "cam" && standing.at("f") == 41 && standing.at("fp") == true);
	CHECK(standing.at("cr") == false && standing.at("cr").is_boolean());
	CHECK(standing.at("p") == json::array({1.5, 65.0, -2.5}) && standing.at("pl") == json::array({1.5, 63.4, -2.5}));
	CHECK(standing.at("r") == json::array({150.0, 12.5, 0.0}) && approx(standing.at("fov"), 58.7) && approx(standing.at("h"), 140.0));
	const json crouching = cb::world::make_cam(42, {}, {}, 70.0, {}, 0.0, {.crouched = true}, true, false, 0);
	CHECK(crouching.at("cr") == true && crouching.at("cr").is_boolean());
	CHECK(survives_the_wire(standing));
}

TEST(cam_carries_fly_menu_air_and_held_as_booleans)
{
	const json none = cb::world::make_cam(1, {}, {}, 70.0, {}, 0.0, {}, true, false, 0);
	for (const char *flag : {"cr", "fly", "menu", "air", "held"})
		CHECK(none.at(flag).is_boolean() && none.at(flag) == false);
	CHECK(none.size() == 15); // t f p r fov fp seat pl h + the five flags + lv
	const auto only = [](cb::world::CamFlags flags, const char *expected)
	{
		const json cam = cb::world::make_cam(2, {}, {}, 70.0, {}, 0.0, flags, true, false, 0);
		for (const char *flag : {"cr", "fly", "menu", "air", "held"})
			if (cam.at(flag) != (std::string(flag) == expected))
				return false;
		return true;
	};
	CHECK(only({.crouched = true}, "cr") && only({.flying = true}, "fly") && only({.menu = true}, "menu"));
	CHECK(only({.air = true}, "air") && only({.held = true}, "held"));
	const json all = cb::world::make_cam(3, {}, {}, 70.0, {}, 0.0, {true, true, true, true, true}, true, false, 0);
	CHECK(all.at("fly") == true && all.at("menu") == true && all.at("air") == true && all.at("held") == true);
	CHECK(survives_the_wire(all));
}

// ---- re-test 3 (plan 2026-10-03-retest3-polish section 1.1): seat and an honest fp ----------------------------------

TEST(cam_carries_an_honest_fp_and_the_seat_flag)
{
	const json onFoot = cb::world::make_cam(1, {}, {}, 70.0, {}, 0.0, {}, true, false, 0);
	CHECK(onFoot.at("fp") == true && onFoot.at("seat") == false && onFoot.at("seat").is_boolean());
	const json thirdPersonOnFoot = cb::world::make_cam(2, {}, {}, 70.0, {}, 0.0, {}, false, false, 0);
	CHECK(thirdPersonOnFoot.at("fp") == false && thirdPersonOnFoot.at("seat") == false);
	const json seatedFirst = cb::world::make_cam(3, {}, {}, 70.0, {1.0, 2.0, 3.0}, 0.0, {.held = true}, true, true, 0);
	CHECK(seatedFirst.at("fp") == true && seatedFirst.at("seat") == true && seatedFirst.at("held") == true);
	CHECK(seatedFirst.at("pl") == json::array({1.0, 2.0, 3.0})); // the hips point, as the camera computed it
	const json seatedThird = cb::world::make_cam(4, {}, {}, 70.0, {}, 0.0, {.held = true}, false, true, 0);
	CHECK(seatedThird.at("fp") == false && seatedThird.at("seat") == true && survives_the_wire(seatedThird));
}

// ---- re-test 2 (plan 2026-10-03-retest2 section 1.1): the ground alignment ---------------------------------------------

TEST(cam_carries_the_alignment_it_was_converted_with_in_lv)
{
	CHECK(cb::world::make_cam(1, {}, {}, 70.0, {}, 0.0, {}, true, false, 0).at("lv") == 0);
	const json cam = cb::world::make_cam(2, {1.0, 6.0, -2.0}, {10.0, 5.0}, 70.0, {1.0, 6.016, -2.0}, 0.0, {}, true, false, 57);
	CHECK(cam.at("lv") == 57 && cam.at("lv").is_number_integer());
	CHECK(cam.at("pl") == json::array({1.0, 6.016, -2.0})); // make_cam does not convert: the camera already did
	CHECK(survives_the_wire(cam));
}

TEST(positions_for_minecraft_take_minus_off)
{
	const double off = 57.0 / 64.0; // 0.890625
	// pcells: the cell of y - off
	CHECK(make_pcells(std::vector<cb::Vec3>{{10.5, 20.2, 64.7}}, off)->at("c") == json::array({10, 63, -21}));
	CHECK(make_pcells(std::vector<cb::Vec3>{{10.5, 20.2, 64.95}}, off)->at("c") == json::array({10, 64, -21}));
	// scaninfo.p
	const auto s = make_scaninfo(7, true, 1, "x", "y", "low", 0.5, false, {1.0, 2.0, 3.0}, 0.6, 1.8, off);
	CHECK(approx(s->at("p")[0], 1) && approx(s->at("p")[1], 2.109) && approx(s->at("p")[2], -2)); // rounded to mm
	// dmg.from
	const auto d = cb::world::make_dmg(0.5, "bullet", {1.0, 2.0, 3.0}, true, off);
	CHECK(approx(d->at("from")[1], 2.109) && approx(d->at("from")[2], -2));
	// npcs[].p (the yaw is a direction: unchanged)
	const std::vector<uint64_t> ids{7};
	const std::vector<cb::Vec3> feet{{10.0, 20.0, 5.0}};
	const std::vector<double> yaws{90.0};
	const std::vector<uint8_t> flags{0};
	const json n = cb::world::make_npcs(ids, feet, yaws, flags, flags, off)->at("list")[0];
	CHECK(approx(n.at("p")[0], 10) && approx(n.at("p")[1], 4.109) && approx(n.at("p")[2], -20) && approx(n.at("yaw"), 90));
}

// ---- look --------------------------------------------------------------------------------------------------------------

TEST(look_carries_the_yaw_and_pitch_deltas_in_degrees)
{
	const json m = cb::world::make_look(1.5, -0.6);
	CHECK(m.at("t") == "look" && m.at("d").size() == 2 && m.size() == 2);
	CHECK(approx(m.at("d")[0], 1.5) && approx(m.at("d")[1], -0.6));
	// 0.15 degrees per count sums to values like 0.30000000000000004: rounded, so the wire text stays short
	CHECK(cb::world::make_look(0.1 + 0.2, 0.15 * 3).dump() == R"({"d":[0.3,0.45],"t":"look"})");
	CHECK(survives_the_wire(m));
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
