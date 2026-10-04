// Phase 5 (megaplan 2026-10-04 sections 1.8 and 1.9 with the Phase 0 amendment, item 7; task 5-D1): the `npcs` figure
// tags: Cyberblock_SubmitNpcs2's builder, validators, the walk/run derivation and the colour conversion.
#include "test.hpp"
#include "world.hpp"
#include <cmath>
#include <fstream>
#include <initializer_list>
#include <limits>
#include <string>
#include <vector>

namespace
{
	using nlohmann::json;
	using cb::world::NpcMotion;
	using cb::world::NpcTags;

	constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();

	bool approx(double a, double b)
	{
		return std::abs(a - b) < 1e-6;
	}

	std::vector<std::string> names(std::initializer_list<std::string> list)
	{
		return {list.begin(), list.end()};
	}

	int pack(int a, int b, int c)
	{
		return (a << 16) | (b << 8) | c;
	}

	/// An all-people list of one id, for the motion tests.
	std::vector<int> bits_after(NpcMotion &m, std::vector<cb::Vec3> feet, double now)
	{
		std::vector<uint64_t> ids(feet.size());
		for (size_t i = 0; i < ids.size(); ++i)
			ids[i] = i + 1;
		return m.update(ids, feet, std::vector<uint8_t>(feet.size(), 0), now);
	}
}

TEST(colour_hex_reads_the_template_triple_as_hsb_by_default)
{
	using cb::world::colour_hex;
	CHECK(colour_hex(pack(0, 255, 255)) == "#FF0000");   // hue 0: red
	CHECK(colour_hex(pack(128, 255, 255)) == "#00FFFF"); // hue 180: cyan
	CHECK(colour_hex(pack(192, 255, 255)) == "#8000FF"); // hue 270
	CHECK(colour_hex(pack(85, 255, 255)) == "#02FF00");  // hue 119.5: green, a touch of red
	CHECK(colour_hex(pack(40, 0, 200)) == "#C8C8C8");    // no saturation: the grey of the brightness
	CHECK(colour_hex(pack(200, 255, 0)) == "#000000");   // no brightness: black
	CHECK(colour_hex(pack(56, 55, 64)) == "#3C4032");    // the archer_hella poor look of docs/notes/phase0-feasibility.md
	CHECK(colour_hex(pack(255, 255, 255)) == "#FF0006"); // hue 359 degrees, still sector 5
}

TEST(colour_hex_rgb_format_is_the_triple_itself)
{
	using cb::world::ColourFormat;
	using cb::world::colour_hex;
	CHECK(colour_hex(pack(10, 200, 255), ColourFormat::Rgb) == "#0AC8FF");
	CHECK(colour_hex(0, ColourFormat::Rgb) == "#000000");
	CHECK(colour_hex(0xFFFFFF, ColourFormat::Rgb) == "#FFFFFF");
}

TEST(colour_hex_matches_tools_cbmath_vectors)
{
	// shared/vectors.json `colour` (tools/cbmath.py colour_hex is the truth), both readings of the template triple
	std::ifstream file(CB_VECTORS);
	const json colour = json::parse(file).at("colour");
	CHECK(colour.size() >= 20);
	for (const json &c : colour)
	{
		const auto format = c.at("format") == "rgb" ? cb::world::ColourFormat::Rgb : cb::world::ColourFormat::Hsb;
		const auto got = cb::world::colour_hex(c.at("packed").get<int32_t>(), format);
		CHECK(c.at("hex").is_null() ? !got.has_value() : (got.has_value() && *got == c.at("hex").get<std::string>()));
	}
}

TEST(colour_hex_rejects_none_and_non_bytes)
{
	using cb::world::colour_hex;
	CHECK(!colour_hex(-1));
	CHECK(!colour_hex(-2000000000));
	CHECK(!colour_hex(0x1000000)); // a fourth byte is not a triple
	CHECK(!colour_hex(0x7FFFFFFF));
	CHECK(cb::world::kColourFormat == cb::world::ColourFormat::Hsb); // the working assumption until CET F2
	CHECK(std::string(cb::world::kColourFormatLog) == "npcs: colour format hsb (unconfirmed)");
}

TEST(figure_tag_accepts_game_names_and_refuses_the_rest)
{
	using cb::world::figure_tag;
	CHECK(figure_tag("RipperDoc") == "RipperDoc");
	CHECK(figure_tag("Car/Turbo") == "Car/Turbo");
	CHECK(figure_tag("Tyger_Claws") == "Tyger_Claws");
	CHECK(figure_tag("a-b.c") == "a-b.c");
	CHECK(!figure_tag(""));
	CHECK(!figure_tag("two words"));
	CHECK(!figure_tag("quote\""));
	CHECK(!figure_tag("new\nline"));
	CHECK(!figure_tag("caf\xC3\xA9")); // not ASCII
	CHECK(figure_tag(std::string(32, 'a')).has_value());
	CHECK(!figure_tag(std::string(33, 'a')));
}

TEST(npc_motion_derives_walk_and_run_from_position_deltas)
{
	NpcMotion m;
	CHECK((bits_after(m, {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}}, 10.0) == std::vector<int>{0, 0, 0})); // the first sighting says nothing
	// 0.1 s later: 0 m/s, 1.4 m/s (walk), 5 m/s (run)
	CHECK((bits_after(m, {{0, 0, 0}, {0.14, 0, 0}, {0.3, 0.4, 0}}, 10.1) == std::vector<int>{0, cb::world::kAnimWalk, cb::world::kAnimRun}));
	CHECK(m.size() == 3);
}

TEST(npc_motion_uses_the_horizontal_speed_only)
{
	NpcMotion m;
	bits_after(m, {{0, 0, 0}}, 0.0);
	CHECK((bits_after(m, {{0, 0, -3}}, 0.1) == std::vector<int>{0})); // falling 3 m in 0.1 s is no walking
}

TEST(npc_motion_thresholds_have_hysteresis)
{
	NpcMotion m;
	double x = 0, t = 0;
	const auto step = [&](double speed)
	{
		x += speed * 0.1;
		t += 0.1;
		return bits_after(m, {{x, 0, 0}}, t)[0];
	};
	CHECK(step(0.0) == 0);
	CHECK(step(0.45) == 0); // below kWalkOn from standing
	CHECK(step(0.8) == cb::world::kAnimWalk);
	CHECK(step(0.45) == cb::world::kAnimWalk); // above kWalkOff: still walking
	CHECK(step(0.2) == 0);                     // below kWalkOff
	CHECK(step(3.5) == cb::world::kAnimRun);
	CHECK(step(2.8) == cb::world::kAnimRun);  // above kRunOff
	CHECK(step(2.0) == cb::world::kAnimWalk); // below kRunOff: down to walk
}

TEST(npc_motion_ignores_teleports_stale_samples_and_repeats)
{
	NpcMotion m;
	bits_after(m, {{0, 0, 0}}, 0.0);
	CHECK((bits_after(m, {{40, 0, 0}}, 0.1) == std::vector<int>{0})); // 40 m in 0.1 s is a respawn
	// 5 m in 2 s: the NPC was out of the list in between; no bits and a fresh start
	CHECK((bits_after(m, {{45, 0, 0}}, 2.1) == std::vector<int>{0}));
	CHECK((bits_after(m, {{45.14, 0, 0}}, 2.2) == std::vector<int>{cb::world::kAnimWalk}));
	// the same moment submitted twice keeps the bits and does not eat the real delta
	CHECK((bits_after(m, {{45.14, 0, 0}}, 2.205) == std::vector<int>{cb::world::kAnimWalk}));
	CHECK((bits_after(m, {{45.28, 0, 0}}, 2.3) == std::vector<int>{cb::world::kAnimWalk}));
	CHECK((bits_after(m, {{50, 0, 0}}, 1.0) == std::vector<int>{0})); // a clock that went backwards says nothing
}

TEST(npc_motion_forgets_what_left_the_list_and_skips_vehicles)
{
	NpcMotion m;
	const std::vector<uint64_t> ids{1, 2};
	const std::vector<uint8_t> mixed{0, 1};
	m.update(ids, std::vector<cb::Vec3>{{0, 0, 0}, {0, 0, 0}}, mixed, 0.0);
	CHECK(m.size() == 1); // the car is not tracked
	CHECK((m.update(ids, std::vector<cb::Vec3>{{0.14, 0, 0}, {5, 0, 0}}, mixed, 0.1) == std::vector<int>{cb::world::kAnimWalk, 0}));
	m.update({}, {}, {}, 0.2);
	CHECK(m.size() == 0);
	CHECK((bits_after(m, {{9, 0, 0}}, 0.3) == std::vector<int>{0})); // back after being forgotten: a first sighting
	m.clear();
	CHECK(m.size() == 0);
}

TEST(npcs2_adds_every_tag_to_the_entries)
{
	const std::vector<uint64_t> ids{10, 11, 12};
	const std::vector<cb::Vec3> feet{{1, 2, 3}, {4, 5, 6}, {7, 8, 9}};
	const std::vector<double> yaws{0, 0, 0};
	const std::vector<uint8_t> vehicles{0, 0, 1};
	const std::vector<uint8_t> hostile{1, 0, 0};
	const auto roles = names({"", "RipperDoc", ""});
	const auto factions = names({"Maelstrom", "NCPD", "Arasaka"});
	const auto archetypes = names({"Gunner", "Civilian", ""});
	const auto vclass = names({"", "", "Car/Turbo"});
	const std::vector<int32_t> genders{1, 2, 0};
	const std::vector<int32_t> anims{cb::world::kAnimAim | cb::world::kAnimCombat, cb::world::kAnimCrouch, 0};
	const std::vector<int32_t> colours{-1, -1, pack(128, 255, 255)};
	const std::vector<double> hp{1.0, 0.25, 0.5};
	const NpcTags tags{roles, factions, archetypes, vclass, genders, anims, colours, hp};
	const auto m = cb::world::make_npcs2(ids, feet, yaws, vehicles, hostile, 0.0, {}, tags, nullptr, 0.0);
	CHECK(m.has_value());
	const json &list = m->at("list");
	CHECK(list.size() == 3);
	CHECK(!list[0].contains("role") && list[0].at("fac") == "Maelstrom" && list[0].at("arch") == "Gunner");
	CHECK(list[0].at("g") == 1 && list[0].at("anim") == (4 | 128) && approx(list[0].at("hp"), 1.0));
	CHECK(!list[0].contains("vc") && !list[0].contains("col"));
	CHECK(list[1].at("role") == "RipperDoc" && list[1].at("g") == 2 && list[1].at("anim") == 64 && approx(list[1].at("hp"), 0.25));
	CHECK(list[2].at("kind") == "vehicle" && list[2].at("vc") == "Car/Turbo" && list[2].at("col") == "#00FFFF");
	CHECK(list[2].at("fac") == "Arasaka" && !list[2].contains("arch") && approx(list[2].at("hp"), 0.5));
	CHECK(list[0].at("id") == "10" && list[0].at("hostile") == true && list[0].at("kind") == "human"); // the old keys
	CHECK(json::parse(m->dump()) == *m);
}

TEST(npcs2_validators_bound_every_field)
{
	const std::vector<uint64_t> ids{1, 2};
	const std::vector<cb::Vec3> feet{{0, 0, 0}, {1, 1, 1}};
	const std::vector<double> yaws{0, 0};
	const std::vector<uint8_t> people{0, 0};
	const std::vector<uint8_t> cars{1, 1};
	const auto roles = names({"bad role!", std::string(33, 'r')});
	const auto vclass = names({std::string(33, 'v'), "Bike/Arch"});
	const std::vector<int32_t> genders{7, -1};
	const std::vector<int32_t> anims{0x7FFFFFFF, -1};
	const std::vector<int32_t> colours{0x1000000, -1};
	const std::vector<double> hp{kNaN, 7.0};
	const NpcTags tags{roles, {}, {}, vclass, genders, anims, colours, hp};
	const json p = cb::world::make_npcs2(ids, feet, yaws, people, people, 0.0, {}, tags, nullptr, 0.0)->at("list");
	CHECK(!p[0].contains("role") && !p[1].contains("role")); // a bad or over-long tag is left out
	CHECK(p[0].at("g") == 0 && p[1].at("g") == 0);           // unknown gender numbers become 0
	CHECK(p[0].at("anim") == (255 & ~3) && p[1].at("anim") == 0); // the 8 bits only; walk and run never come from the scripts
	CHECK(!p[0].contains("hp"));                             // not finite
	CHECK(approx(p[1].at("hp"), 1.0));                       // clamped
	CHECK(!p[0].contains("vc") && !p[0].contains("col"));    // people carry no vehicle fields
	const json c = cb::world::make_npcs2(ids, feet, yaws, cars, people, 0.0, {}, tags, nullptr, 0.0)->at("list");
	CHECK(!c[0].contains("vc") && c[1].at("vc") == "Bike/Arch");
	CHECK(!c[0].contains("col") && !c[1].contains("col")); // not three bytes, or none
	const std::vector<double> unknown{-1.0, 0.0};
	const NpcTags unknownHp{{}, {}, {}, {}, {}, {}, {}, unknown};
	const json h = cb::world::make_npcs2(ids, feet, yaws, people, people, 0.0, {}, unknownHp, nullptr, 0.0)->at("list");
	CHECK(!h[0].contains("hp") && approx(h[1].at("hp"), 0.0)); // -1 is "unknown"
}

TEST(npcs2_vehicles_have_no_gender_and_no_pose)
{
	const std::vector<uint64_t> ids{1};
	const std::vector<cb::Vec3> feet{{0, 0, 0}};
	const std::vector<double> yaws{0};
	const std::vector<uint8_t> car{1}, none{0};
	const std::vector<int32_t> genders{2}, anims{cb::world::kAnimAim | cb::world::kAnimWalk};
	const NpcTags tags{{}, {}, {}, {}, genders, anims, {}, {}};
	NpcMotion motion;
	const json list = cb::world::make_npcs2(ids, feet, yaws, car, none, 0.0, {}, tags, &motion, 0.0)->at("list");
	CHECK(list[0].at("g") == 0 && list[0].at("anim") == 0);
}

TEST(npcs2_tag_arrays_must_be_empty_or_as_long_as_the_list)
{
	const std::vector<uint64_t> ids{1, 2};
	const std::vector<cb::Vec3> feet{{0, 0, 0}, {1, 1, 1}};
	const std::vector<double> yaws{0, 0};
	const std::vector<uint8_t> flags{0, 0};
	const std::vector<int32_t> one{1};
	const std::vector<double> oneHp{1.0};
	const auto oneName = names({"A"});
	const auto make = [&](const NpcTags &tags)
	{ return cb::world::make_npcs2(ids, feet, yaws, flags, flags, 0.0, {}, tags, nullptr, 0.0); };
	CHECK(make(NpcTags{}).has_value());
	CHECK(!make(NpcTags{oneName, {}, {}, {}, {}, {}, {}, {}}));
	CHECK(!make(NpcTags{{}, oneName, {}, {}, {}, {}, {}, {}}));
	CHECK(!make(NpcTags{{}, {}, oneName, {}, {}, {}, {}, {}}));
	CHECK(!make(NpcTags{{}, {}, {}, oneName, {}, {}, {}, {}}));
	CHECK(!make(NpcTags{{}, {}, {}, {}, one, {}, {}, {}}));
	CHECK(!make(NpcTags{{}, {}, {}, {}, {}, one, {}, {}}));
	CHECK(!make(NpcTags{{}, {}, {}, {}, {}, {}, one, {}}));
	CHECK(!make(NpcTags{{}, {}, {}, {}, {}, {}, {}, oneHp}));
	// the six old arrays are checked as before
	CHECK(!cb::world::make_npcs2(std::vector<uint64_t>{1}, feet, yaws, flags, flags, 0.0, {}, NpcTags{}, nullptr, 0.0));
}

TEST(npcs2_with_no_tags_and_no_motion_is_exactly_the_old_list)
{
	const std::vector<uint64_t> ids{1, 2};
	const std::vector<cb::Vec3> feet{{0, 0, 0}, {kNaN, 1, 1}};
	const std::vector<double> yaws{0, 0};
	const std::vector<uint8_t> flags{0, 1};
	const std::vector<uint8_t> dead{1, 0};
	CHECK(*cb::world::make_npcs2(ids, feet, yaws, flags, flags, 0.0, dead, NpcTags{}, nullptr, 0.0) ==
		  *cb::world::make_npcs(ids, feet, yaws, flags, flags, 0.0, dead));
}

TEST(npcs2_motion_sets_walk_and_run_for_the_living_only)
{
	const std::vector<uint64_t> ids{1, 2, 3, 4, 5};
	const std::vector<double> yaws(5, 0.0);
	const std::vector<uint8_t> hostile(5, 0);
	const std::vector<uint8_t> kinds{0, 0, 0, 0, 1};
	const std::vector<uint8_t> dead{0, 0, 0, 1, 0};
	const std::vector<int32_t> anims{cb::world::kAnimAim, 0, cb::world::kAnimRagdoll, 0, 0};
	const NpcTags tags{{}, {}, {}, {}, {}, anims, {}, {}};
	NpcMotion motion;
	const auto send = [&](double x, double now)
	{
		const std::vector<cb::Vec3> feet{{x, 0, 0}, {x * 4, 0, 0}, {x, 0, 0}, {x, 0, 0}, {x * 4, 0, 0}};
		return cb::world::make_npcs2(ids, feet, yaws, kinds, hostile, 0.0, dead, tags, &motion, now)->at("list");
	};
	const json first = send(0.0, 0.0);
	CHECK(first[0].at("anim") == 4 && first[1].at("anim") == 0 && first[2].at("anim") == 16);
	const json second = send(0.14, 0.1); // person 1 walks at 1.4 m/s (and aims), person 2 at 5.6 m/s
	CHECK(second[0].at("anim") == (4 | 1)); // aiming while walking: both bits
	CHECK(second[1].at("anim") == 2);       // run
	CHECK(second[2].at("anim") == 16);      // a ragdoll moves without walking
	CHECK(second[3].at("anim") == 0);       // the dead do not walk
	CHECK(second[4].at("anim") == 0);       // a vehicle is no walker
}

TEST(npcs2_motion_without_an_anims_array_still_sends_the_derived_bits)
{
	const std::vector<uint64_t> ids{1};
	const std::vector<double> yaws{0};
	const std::vector<uint8_t> flags{0};
	NpcMotion motion;
	cb::world::make_npcs2(ids, std::vector<cb::Vec3>{{0, 0, 0}}, yaws, flags, flags, 0.0, {}, NpcTags{}, &motion, 0.0);
	const json list = cb::world::make_npcs2(ids, std::vector<cb::Vec3>{{0.14, 0, 0}}, yaws, flags, flags, 0.0, {}, NpcTags{}, &motion, 0.1)->at("list");
	CHECK(list[0].at("anim") == 1);
}

TEST(npcs2_motion_is_in_ground_metres_whatever_the_level_offset)
{
	const std::vector<uint64_t> ids{1};
	const std::vector<double> yaws{0};
	const std::vector<uint8_t> flags{0};
	NpcMotion motion;
	cb::world::make_npcs2(ids, std::vector<cb::Vec3>{{0, 0, 0}}, yaws, flags, flags, 0.5, {}, NpcTags{}, &motion, 0.0);
	const json list = cb::world::make_npcs2(ids, std::vector<cb::Vec3>{{0, 3.5, 0}}, yaws, flags, flags, 0.5, {}, NpcTags{}, &motion, 1.0)->at("list");
	CHECK(list[0].at("anim") == 2); // 3.5 m/s
}

TEST(npc_tag_summary_names_the_runtime_path)
{
	const json none = json::array({{{"kind", "human"}}, {{"kind", "vehicle"}}});
	CHECK(std::string(cb::world::summarize_npcs(none).path()) == "none");
	const json factionOnly = json::array({{{"kind", "human"}, {"fac", "NCPD"}, {"g", 0}, {"anim", 0}}, {{"kind", "human"}, {"fac", "Maelstrom"}}});
	const auto f = cb::world::summarize_npcs(factionOnly);
	CHECK(std::string(f.path()) == "faction only" && f.people == 2 && f.withFaction == 2 && f.unknownGender == 2);
	const json full = json::array({{{"kind", "human"}, {"fac", "NCPD"}, {"g", 2}, {"arch", "Gunner"}, {"role", "RipperDoc"}, {"anim", 3}},
								   {{"kind", "human"}, {"g", 1}, {"anim", 1}},
								   {{"kind", "vehicle"}, {"col", "#000000"}}});
	const auto s = cb::world::summarize_npcs(full);
	CHECK(std::string(s.path()) == "full" && s.people == 2 && s.vehicles == 1 && s.male == 1 && s.female == 1);
	CHECK(s.withRole == 1 && s.withArchetype == 1 && s.withColour == 1 && s.walking == 2 && s.running == 1);
	CHECK(std::string(cb::world::summarize_npcs(json::array()).path()) == "none");
	CHECK(std::string(cb::world::summarize_npcs(json(5)).path()) == "none");
}
