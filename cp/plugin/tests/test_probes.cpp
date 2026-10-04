// ProbeSet: which Night City cells Minecraft is told about, and when they are taken back; the probe material table.
#include "probes.hpp"
#include "test.hpp"
#include <algorithm>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <nlohmann/json.hpp>
#include <string>
#include <vector>

namespace
{
	using Quads = std::vector<int>;
	constexpr double kRadius = 24.0;
	constexpr double kNoOffset = 0.0; // ground alignment k 0 (plan 2026-10-03-retest2 section 1.1)

	// Cyberpunk-space feet; Minecraft column (10, -20)
	const cb::Vec3 kCenter{10.0, 20.0, 5.0};
	// down hits -> {10, -21, 3, 4} and {11, -21, 3, 4}; wall hit -> {10, -23, 5, 5}
	const std::vector<cb::Vec3> kDown{{10.4, 20.6, 5.0}, {11.2, 20.6, 5.0}};
	const std::vector<cb::Vec3> kWall{{10.4, 22.5, 5.7}};
	const Quads kAll{10, -23, 5, 5, 10, -21, 3, 4, 11, -21, 3, 4}; // ordered by x, z, y

	bool empty(const cb::Delta &d)
	{
		return d.add.empty() && d.remove.empty() && d.seen.empty();
	}

	std::vector<std::string> strings(std::span<const std::string> s)
	{
		return {s.begin(), s.end()};
	}

	/// Checks the DLL's rule against a vectors.json `materials` value as tools/gen_vectors.py writes it: `codes`
	/// [{code, id, block, match}], `order` (codes and "neon"), `neon_any`, `neon_any_code` and `cases` [{name, code}].
	void check_material_vectors(const nlohmann::json &m)
	{
		const nlohmann::json &codes = m.at("codes");
		CHECK(codes.size() == std::size(cb::kMaterialCodes));
		for (const auto &c : codes)
		{
			const int code = c.at("code").get<int>();
			CHECK(code >= 0 && code < int(std::size(cb::kMaterialCodes)));
			if (code < 0 || code >= int(std::size(cb::kMaterialCodes)))
				continue;
			CHECK(c.at("id").get<std::string>() == cb::kMaterialCodes[code]);
			CHECK(c.at("match").get<std::vector<std::string>>() == strings(cb::material_match(code)));
		}
		std::vector<int> order;
		for (const auto &rule : m.at("order"))
			order.push_back(rule.is_string() && rule.get<std::string>() == "neon" ? cb::kAnyNeonRule : rule.get<int>());
		CHECK(order == std::vector<int>(cb::material_order().begin(), cb::material_order().end()));
		CHECK(m.at("neon_any").get<std::vector<std::string>>() == strings(cb::material_neon_any()));
		CHECK(m.at("neon_any_code").get<int>() == cb::kNeonFirst);
		CHECK(m.contains("cases") && !m.at("cases").empty());
		for (const auto &c : m.value("cases", nlohmann::json::array()))
		{
			const std::string name = c.at("name").get<std::string>();
			const int want = c.at("code").get<int>(), got = cb::material_code(name);
			if (got != want)
				std::printf("  materials case: '%s' -> %d in cbmath, %d in the DLL\n", name.c_str(), want, got);
			CHECK(got == want);
		}
	}
}

TEST(same_hits_twice_send_nothing_new)
{
	cb::ProbeSet set;
	const cb::Delta first = set.submit(kCenter, kDown, kWall, kRadius, kNoOffset, 0.0);
	CHECK(first.add == kAll);
	CHECK(first.remove.empty());
	CHECK(empty(set.submit(kCenter, kDown, kWall, kRadius, kNoOffset, 0.5)));
}

TEST(moving_30m_removes_ranges_beyond_the_radius)
{
	cb::ProbeSet set;
	set.submit(kCenter, kDown, kWall, kRadius, kNoOffset, 0.0);
	const cb::Delta moved = set.submit({kCenter.x + 30.0, kCenter.y, kCenter.z}, {}, {}, kRadius, kNoOffset, 0.1);
	CHECK(moved.add.empty());
	CHECK(moved.remove == kAll);
	CHECK(moved.seen.empty()); // Phase 3: they only left the radius, nobody saw them empty (the replica keeps them)
	// hits that are now out of range are ignored rather than re-added
	CHECK(empty(set.submit({kCenter.x + 30.0, kCenter.y, kCenter.z}, kDown, kWall, kRadius, kNoOffset, 0.2)));
}

TEST(range_not_rehit_for_3s_after_its_column_is_probed_again_is_removed)
{
	cb::ProbeSet set;
	set.submit(kCenter, kDown, {}, kRadius, kNoOffset, 0.0);
	// column (10, -21) is probed again and hits higher up (a car roof, an awning): {10, -21, 7, 8}
	const std::vector<cb::Vec3> higher{{10.4, 20.6, 9.0}};
	const cb::Delta reprobed = set.submit(kCenter, higher, {}, kRadius, kNoOffset, 1.0);
	CHECK(reprobed.add == (Quads{10, -21, 7, 8}));
	CHECK(reprobed.remove.empty());
	CHECK(empty(set.submit(kCenter, {}, {}, kRadius, kNoOffset, 2.5))); // the old floor was confirmed only 2.5 s ago
	const cb::Delta expired = set.submit(kCenter, {}, {}, kRadius, kNoOffset, 3.5);
	CHECK(expired.add.empty());
	// column (11, -21) was never re-probed, so it stays; the old floor is under the new hit, where no down ray looks:
	// taken back, but not `seen` (the replica keeps it)
	CHECK(expired.remove == (Quads{10, -21, 3, 4}));
	CHECK(expired.seen.empty());
}

TEST(a_floor_that_drops_away_under_the_down_ray_is_seen)
{
	cb::ProbeSet set;
	set.submit(kCenter, kDown, {}, kRadius, kNoOffset, 0.0); // {10, -21, 3, 4}; down rays start at Minecraft y 8
	// the column now hits lower ({10, -21, 0, 1}): the ray passed through 3..4 on its way down
	CHECK(set.submit(kCenter, std::vector<cb::Vec3>{{10.4, 20.6, 2.0}}, {}, kRadius, kNoOffset, 1.0).add ==
		  (Quads{10, -21, 0, 1}));
	const cb::Delta d = set.submit(kCenter, {}, {}, kRadius, kNoOffset, 3.5);
	CHECK(d.seen == (Quads{10, -21, 3, 4}));
	CHECK(d.remove.empty());
}

TEST(walking_uphill_past_a_wall_sees_nothing)
{
	// a wall ray at feet + 0.5 m hits column (10, -23) at cell 5; V walks one metre up a slope and it hits cell 6
	cb::ProbeSet set;
	set.submit(kCenter, {}, kWall, kRadius, kNoOffset, 0.0);
	const cb::Vec3 uphill{kCenter.x, kCenter.y, kCenter.z + 1.0};
	CHECK(set.submit(uphill, {}, std::vector<cb::Vec3>{{10.4, 22.5, 6.7}}, kRadius, kNoOffset, 1.0).add ==
		  (Quads{10, -23, 6, 6}));
	const cb::Delta d = set.submit(uphill, {}, {}, kRadius, kNoOffset, 3.5);
	CHECK(d.remove == (Quads{10, -23, 5, 5})); // still wall: only wall rays moved, nobody saw it empty
	CHECK(d.seen.empty());
}

TEST(standing_on_a_bridge_over_a_known_street_sees_nothing)
{
	cb::ProbeSet set;
	set.submit(kCenter, kDown, {}, kRadius, kNoOffset, 0.0); // the street: {10, -21, 3, 4}
	// V on a bridge 5 m up: the down ray stops on the bridge deck ({10, -21, 8, 9}) and never reaches the street
	const cb::Vec3 bridge{kCenter.x, kCenter.y, kCenter.z + 5.0};
	set.submit(bridge, std::vector<cb::Vec3>{{10.4, 20.6, 10.0}}, {}, kRadius, kNoOffset, 1.0);
	const cb::Delta d = set.submit(bridge, {}, {}, kRadius, kNoOffset, 3.5);
	CHECK(d.remove == (Quads{10, -21, 3, 4}));
	CHECK(d.seen.empty());
}

TEST(a_roof_above_the_down_ray_start_is_not_seen)
{
	// V on a roof 15 m up sees it ({10, -21, 18, 19}), then takes the stairs down to the street
	cb::ProbeSet set;
	const cb::Vec3 roof{kCenter.x, kCenter.y, kCenter.z + 15.0};
	CHECK(set.submit(roof, std::vector<cb::Vec3>{{10.4, 20.6, 20.0}}, {}, kRadius, kNoOffset, 0.0).add ==
		  (Quads{10, -21, 18, 19}));
	set.submit(kCenter, kDown, {}, kRadius, kNoOffset, 1.0); // the street; this down ray started at Minecraft y 8
	const cb::Delta d = set.submit(kCenter, {}, {}, kRadius, kNoOffset, 3.5);
	CHECK(d.remove == (Quads{10, -21, 18, 19}));
	CHECK(d.seen.empty());
}

TEST(clear_then_submit_adds_everything_again)
{
	cb::ProbeSet set;
	set.submit(kCenter, kDown, kWall, kRadius, kNoOffset, 0.0);
	set.clear();
	const cb::Delta again = set.submit(kCenter, kDown, kWall, kRadius, kNoOffset, 0.5);
	CHECK(again.add == kAll);
	CHECK(again.remove.empty());
}

TEST(overlapping_ranges_keep_their_shared_cells)
{
	// column (2, 3): a step whose top is at 0.7 m (nearest whole y 1: {2, 3, -1, 0}) and its riser hit by a wall ray
	// ({2, 3, 0, 0})
	const cb::Vec3 origin{0.0, 0.0, 0.0};
	const std::vector<cb::Vec3> riser{{2.5, -3.5, 0.5}};
	cb::ProbeSet set;
	const cb::Delta first = set.submit(origin, std::vector<cb::Vec3>{{2.5, -3.5, 0.7}}, riser, kRadius, kNoOffset, 0.0);
	CHECK(first.add == (Quads{2, 3, -1, 0}));
	// the down ray now goes through the step to a floor below ({2, 3, -5, -4})
	set.submit(origin, std::vector<cb::Vec3>{{2.5, -3.5, -3.0}}, riser, kRadius, kNoOffset, 1.0);
	// the step's range expires and its cell -1 was seen empty, but cell 0 is still claimed by the riser
	const cb::Delta expired = set.submit(origin, {}, riser, kRadius, kNoOffset, 3.5);
	CHECK(expired.add.empty());
	CHECK(expired.seen == (Quads{2, 3, -1, -1}));
	CHECK(expired.remove.empty());
}

// ---- re-test 2 (plan 2026-10-03-retest2 section 1.1): nearest rounding and the ground alignment ----------------------------

TEST(a_floor_hit_rounds_to_the_nearest_whole_y)
{
	// the barrier's top face is the nearest whole y to the floor: 0.3 m -> 0, 0.7 m -> 1 (ceil put both at 1)
	cb::ProbeSet set;
	const cb::Vec3 origin{0.0, 0.0, 0.0};
	CHECK(set.submit(origin, std::vector<cb::Vec3>{{2.5, -3.5, 0.3}}, {}, kRadius, kNoOffset, 0.0).add == (Quads{2, 3, -2, -1}));
	cb::ProbeSet other;
	CHECK(other.submit(origin, std::vector<cb::Vec3>{{2.5, -3.5, 0.7}}, {}, kRadius, kNoOffset, 0.0).add == (Quads{2, 3, -1, 0}));
}

TEST(the_alignment_puts_the_barrier_top_on_the_aligned_floor)
{
	// V stands on a floor at Cyberpunk z 6.907; Minecraft chose k 57 (off 0.890625), so the floor is Minecraft y 6.016:
	// the barrier under it is {5, 6} -> cells 4..5, top face 6, and float noise on the hits changes nothing
	const double off = 57.0 / 64.0;
	const cb::Vec3 feet{3.5, 2.5, 6.907};
	cb::ProbeSet set;
	const std::vector<cb::Vec3> floor{{3.5, 2.5, 6.907}, {4.5, 2.5, 6.907 - 1e-6}, {5.5, 2.5, 6.907 + 1e-6}};
	CHECK(set.submit(feet, floor, {}, kRadius, off, 0.0).add == (Quads{3, -3, 4, 5, 4, -3, 4, 5, 5, -3, 4, 5}));
	// the same hits at k 0: top face 7, so blocks placed on it float 0.09 m above the floor Night City shows
	cb::ProbeSet unaligned;
	CHECK(unaligned.submit(feet, floor, {}, kRadius, kNoOffset, 0.0).add == (Quads{3, -3, 5, 6, 4, -3, 5, 6, 5, -3, 5, 6}));
	// a wall hit at the same height is the cell the floor's top face starts
	cb::ProbeSet wall;
	CHECK(wall.submit(feet, {}, std::vector<cb::Vec3>{{3.5, 2.5, 6.907 + 0.5}}, kRadius, off, 0.0).add == (Quads{3, -3, 6, 6}));
}

// ---- Phase 3 (megaplan 2026-10-04 task 3-D1): `seen` and the material per range ---------------------------------------

TEST(seen_and_left_cells_of_one_call_are_reported_apart)
{
	// column (10, -21) and column (31, -21), both inside the radius of kCenter (Minecraft x 10)
	const std::vector<cb::Vec3> two{{10.4, 20.6, 5.0}, {31.4, 20.6, 5.0}};
	cb::ProbeSet set;
	CHECK(set.submit(kCenter, two, {}, kRadius, kNoOffset, 0.0).add == (Quads{10, -21, 3, 4, 31, -21, 3, 4}));
	set.submit(kCenter, std::vector<cb::Vec3>{{10.4, 20.6, 2.0}}, {}, kRadius, kNoOffset, 1.0); // (10, -21) hits lower
	// V walks to Minecraft x -5: column 31 leaves the radius; column 10 stays inside and its old floor, which the down
	// ray passed through, is not hit again
	const cb::Delta d = set.submit({-5.0, kCenter.y, kCenter.z}, {}, {}, kRadius, kNoOffset, 3.5);
	CHECK(d.add.empty());
	CHECK(d.seen == (Quads{10, -21, 3, 4}));
	CHECK(d.remove == (Quads{31, -21, 3, 4}));
}

TEST(materials_give_one_code_per_added_quadruple)
{
	const std::vector<int> downM{1, 6}, wallM{5};
	const cb::ProbeMaterials m{downM, wallM};
	cb::ProbeSet set;
	const cb::Delta d = set.submit(kCenter, kDown, kWall, kRadius, kNoOffset, 0.0, &m);
	CHECK(d.add == kAll);
	CHECK(d.materials == (std::vector<int>{5, 1, 6})); // kAll's order: the wall range, then the two floors
	// nothing new: nothing to send, no codes
	const cb::Delta again = set.submit(kCenter, kDown, kWall, kRadius, kNoOffset, 0.5, &m);
	CHECK(empty(again) && again.materials.empty());
}

TEST(without_materials_the_codes_are_left_out)
{
	cb::ProbeSet set;
	const cb::Delta d = set.submit(kCenter, kDown, kWall, kRadius, kNoOffset, 0.0);
	CHECK(d.add == kAll);
	CHECK(d.materials.empty());
}

TEST(materials_of_another_length_than_the_hits_are_ignored)
{
	const std::vector<int> downM{1}, wallM{5}; // two down hits, one code
	const cb::ProbeMaterials m{downM, wallM};
	cb::ProbeSet set;
	const cb::Delta d = set.submit(kCenter, kDown, kWall, kRadius, kNoOffset, 0.0, &m);
	CHECK(d.add == kAll);
	CHECK(d.materials.empty());
}

TEST(stacked_cells_merge_only_when_their_material_is_the_same)
{
	// column (2, 3): a floor at 0.7 m ({2, 3, -1, 0}) and a wall hit right above it ({2, 3, 1, 1})
	const cb::Vec3 origin{0.0, 0.0, 0.0};
	const std::vector<cb::Vec3> floor{{2.5, -3.5, 0.7}}, wall{{2.5, -3.5, 1.5}};
	const std::vector<int> asphalt{3}, brick{7};
	cb::ProbeSet differ;
	const cb::ProbeMaterials m1{asphalt, brick};
	const cb::Delta split = differ.submit(origin, floor, wall, kRadius, kNoOffset, 0.0, &m1);
	CHECK(split.add == (Quads{2, 3, -1, 0, 2, 3, 1, 1}));
	CHECK(split.materials == (std::vector<int>{3, 7}));
	cb::ProbeSet same;
	const cb::ProbeMaterials m2{asphalt, asphalt};
	const cb::Delta merged = same.submit(origin, floor, wall, kRadius, kNoOffset, 0.0, &m2);
	CHECK(merged.add == (Quads{2, 3, -1, 1}));
	CHECK(merged.materials == (std::vector<int>{3}));
	// without materials the old merging is unchanged
	cb::ProbeSet none;
	CHECK(none.submit(origin, floor, wall, kRadius, kNoOffset, 0.0).add == (Quads{2, 3, -1, 1}));
}

TEST(a_cell_keeps_the_material_it_was_added_with)
{
	const cb::Vec3 origin{0.0, 0.0, 0.0};
	const std::vector<cb::Vec3> floor{{2.5, -3.5, 0.7}};
	const std::vector<int> glass{5}, metal{6}, none;
	cb::ProbeSet set;
	const cb::ProbeMaterials first{glass, none};
	CHECK(set.submit(origin, floor, {}, kRadius, kNoOffset, 0.0, &first).materials == (std::vector<int>{5}));
	const cb::ProbeMaterials second{metal, none};
	CHECK(empty(set.submit(origin, floor, {}, kRadius, kNoOffset, 0.5, &second))); // already solid: nothing resent
	set.clear();
	CHECK(set.submit(origin, floor, {}, kRadius, kNoOffset, 1.0, &second).materials == (std::vector<int>{6}));
}

// ---- the shared material name table (megaplan section 1.8 amendment item 1) ----------------------------------------

TEST(every_code_name_maps_to_its_own_code)
{
	CHECK(std::size(cb::kMaterialCodes) == 27);
	for (size_t code = 1; code < std::size(cb::kMaterialCodes); ++code)
	{
		bool known = false;
		CHECK(cb::material_code(cb::kMaterialCodes[code], &known) == int(code));
		CHECK(known);
	}
	CHECK(std::string(cb::kMaterialCodes[11]) == "neon_white" && std::string(cb::kMaterialCodes[26]) == "neon_black");
	CHECK(std::string(cb::kMaterialCodes[14]) == "neon_light_blue" && std::string(cb::kMaterialCodes[19]) == "neon_light_gray");
}

TEST(material_names_follow_the_cbmath_rule)
{
	// a few of tools/gen_vectors.py's cases, so cb_tests holds the rule before shared/vectors.json carries them
	CHECK(cb::material_code("Concrete_White_Wall") == 1);
	CHECK(cb::material_code("plaster_dirty") == 1); // not ground through "dirt": light concrete comes first
	CHECK(cb::material_code("Concrete_Wet_Floor") == 2);
	CHECK(cb::material_code("Road_Marking_Yellow") == 3);
	CHECK(cb::material_code("pavement_concrete") == 4);
	CHECK(cb::material_code("shopfront_glass_blue") == 5);
	CHECK(cb::material_code("scaffold_pipe") == 6);
	CHECK(cb::material_code("masonry_old") == 7);
	CHECK(cb::material_code("marble_floor") == 8);
	CHECK(cb::material_code("terrain_rock_cliff") == 9);
	CHECK(cb::material_code("puddle_street") == 10);
	CHECK(cb::material_code("neon_light_blue_tube") == 14);
	CHECK(cb::material_code("neon_blue_tube") == 22);
	CHECK(cb::material_code("emissive_light_gray_panel") == 19);
	CHECK(cb::material_code("led_pink_strip") == 17);
	CHECK(cb::material_code("neon_generic_sign") == cb::kNeonFirst); // a neon of no known dye is the white neon
	CHECK(cb::material_code("hologram_ad_01") == cb::kNeonFirst);
	CHECK(cb::material_code("concrete") == 0); // a bare "concrete" names neither shade
}

TEST(unknown_and_empty_material_names_are_0)
{
	bool known = true;
	CHECK(cb::material_code("plastic", &known) == 0);
	CHECK(!known);
	known = false;
	CHECK(cb::material_code("", &known) == 0);
	CHECK(known); // no name is not an unknown name: nothing to log
	CHECK(cb::material_code("None", &known) == 0 && !known);
	// every code 1..26 is tried exactly once, and so is "any other neon"
	std::vector<int> rules(cb::material_order().begin(), cb::material_order().end());
	std::sort(rules.begin(), rules.end());
	std::vector<int> want{cb::kAnyNeonRule};
	for (int code = 1; code < 27; ++code)
		want.push_back(code);
	CHECK(rules == want);
	CHECK(cb::material_match(0).empty() && cb::material_match(27).empty() && cb::material_match(-1).empty());
}

TEST(material_cache_flags_each_unknown_name_once)
{
	cb::MaterialCache cache;
	bool first = false;
	CHECK(!cache.find(1).has_value());
	CHECK(cache.add(1, "plastic", first) == 0 && first);
	CHECK(cache.find(1) == 0);
	CHECK(cache.add(1, "plastic", first) == 0 && !first);
	CHECK(cache.add(2, "metal", first) == 6 && !first);
	CHECK(cache.find(2) == 6);
	CHECK(cache.add(3, "", first) == 0 && !first);
	// bounded: once full it still answers, but stops remembering (and flagging) new names
	for (uint64_t key = 100; cache.size() < cb::MaterialCache::kMaxNames; ++key)
		cache.add(key, "metal", first);
	CHECK(cache.add(99999, "rubber", first) == 0 && !first);
	CHECK(!cache.find(99999).has_value());
	CHECK(cache.size() == cb::MaterialCache::kMaxNames);
}

TEST(material_table_matches_shared_vectors)
{
	std::ifstream file(CB_VECTORS);
	const nlohmann::json all = nlohmann::json::parse(file);
	if (!all.contains("materials"))
		return (void)std::puts("  (skipped: shared/vectors.json has no materials key yet; task 3-P1 writes it)");
	check_material_vectors(all.at("materials"));
}
