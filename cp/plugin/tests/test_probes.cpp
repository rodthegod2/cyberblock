// ProbeSet: which Night City cells Minecraft is told about, and when they are taken back; the probe material table.
#include "probes.hpp"
#include "test.hpp"
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

	/// Checks every name -> code pair found in the vectors' `materials` value against material_code: a list of code
	/// names (index = code), an object of name: code, or objects with a name field and a whole-number `code`; other
	/// lists and objects are walked. Returns how many pairs it checked (a mismatch fails a CHECK and prints the pair).
	size_t check_material_vectors(const nlohmann::json &v)
	{
		const auto pair = [](const std::string &name, int code)
		{
			const int got = cb::material_code(name);
			if (got != code)
				std::printf("  materials vector: %s -> %d, the table says %d\n", name.c_str(), code, got);
			CHECK(got == code);
			return size_t(1);
		};
		size_t checked = 0;
		if (v.is_array())
		{
			for (size_t i = 0; i < v.size(); ++i)
				checked += v[i].is_string() ? pair(v[i].get<std::string>(), int(i)) : check_material_vectors(v[i]);
			return checked;
		}
		if (!v.is_object())
			return 0;
		if (const auto code = v.find("code"); code != v.end() && code->is_number_integer())
			for (const char *field : {"name", "material", "in", "id", "key"})
				if (const auto name = v.find(field); name != v.end() && name->is_string())
					return pair(name->get<std::string>(), code->get<int>());
		for (const auto &[key, value] : v.items())
			checked += value.is_number_integer() ? pair(key, value.get<int>()) : check_material_vectors(value);
		return checked;
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
	// column (10, -21) is probed again and hits higher up (a car roof, a new floor): {10, -21, 7, 8}
	const std::vector<cb::Vec3> higher{{10.4, 20.6, 9.0}};
	const cb::Delta reprobed = set.submit(kCenter, higher, {}, kRadius, kNoOffset, 1.0);
	CHECK(reprobed.add == (Quads{10, -21, 7, 8}));
	CHECK(reprobed.remove.empty());
	CHECK(empty(set.submit(kCenter, {}, {}, kRadius, kNoOffset, 2.5))); // the old floor was confirmed only 2.5 s ago
	const cb::Delta expired = set.submit(kCenter, {}, {}, kRadius, kNoOffset, 3.5);
	CHECK(expired.add.empty());
	// column (11, -21) was never re-probed, so it stays; the old floor was observed empty inside the radius: `seen`
	CHECK(expired.seen == (Quads{10, -21, 3, 4}));
	CHECK(expired.remove.empty());
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
	set.submit(origin, std::vector<cb::Vec3>{{2.5, -3.5, 5.0}}, riser, kRadius, kNoOffset, 1.0);
	// the step's range expires, but cell 0 is still claimed by the riser
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
	set.submit(kCenter, std::vector<cb::Vec3>{{10.4, 20.6, 9.0}}, {}, kRadius, kNoOffset, 1.0); // (10, -21) hits higher
	// V walks to Minecraft x -5: column 31 leaves the radius, column 10 stays inside and its old floor is not hit again
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
	for (size_t code = 0; code < std::size(cb::kMaterialCodes); ++code)
	{
		bool known = false;
		CHECK(cb::material_code(cb::kMaterialCodes[code], &known) == int(code));
		CHECK(known);
	}
	CHECK(std::string(cb::kMaterialCodes[11]) == "neon_white" && std::string(cb::kMaterialCodes[26]) == "neon_black");
	CHECK(std::string(cb::kMaterialCodes[14]) == "neon_light_blue" && std::string(cb::kMaterialCodes[19]) == "neon_light_gray");
}

TEST(material_names_match_by_substring_ignoring_case)
{
	CHECK(cb::material_code("Concrete_Dark_01") == 2);
	CHECK(cb::material_code("concrete") == 1);
	CHECK(cb::material_code("asphalt.physmat") == 3);
	CHECK(cb::material_code("pavement_wet") == 4);
	CHECK(cb::material_code("glass_bulletproof") == 5);
	CHECK(cb::material_code("metal_hollow") == 6);
	CHECK(cb::material_code("brick_red") == 7);
	CHECK(cb::material_code("tiles") == 8);
	CHECK(cb::material_code("dirt") == 9);
	CHECK(cb::material_code("grass_tall") == 9);
	CHECK(cb::material_code("water_shallow") == 10);
	CHECK(cb::material_code("neon_light_blue_sign") == 14);
	CHECK(cb::material_code("neon_blue") == 22);
	CHECK(cb::material_code("sign_neon_pink") == 17);
	CHECK(cb::material_code("neon") == 13); // a neon of no known colour glows magenta
}

TEST(unknown_and_empty_material_names_are_0)
{
	bool known = true;
	CHECK(cb::material_code("plastic", &known) == 0);
	CHECK(!known);
	known = false;
	CHECK(cb::material_code("", &known) == 0);
	CHECK(known); // no name is not an unknown name: nothing to log
	CHECK(!cb::material_table().empty());
	for (const cb::MaterialName &entry : cb::material_table())
		CHECK(entry.code >= 0 && entry.code < 27 && entry.substring[0] != '\0');
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
	const size_t checked = check_material_vectors(all.at("materials"));
	if (checked == 0)
		std::puts("  materials: shared/vectors.json key 'materials' holds no name -> code pair this check knows");
	CHECK(checked > 0);
}
