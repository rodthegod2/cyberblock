// Checks the C++ mirror against tools/cbmath.py through shared/vectors.json (CB_VECTORS, set by CMake).
#include "cbmath.hpp"
#include "test.hpp"
#include <algorithm>
#include <cmath>
#include <fstream>
#include <nlohmann/json.hpp>

namespace
{
	constexpr double kTolerance = 1e-6;

	nlohmann::json vectors()
	{
		std::ifstream file(CB_VECTORS);
		return nlohmann::json::parse(file); // throws (and fails the run) if the file is missing or malformed
	}

	bool close(double a, double b)
	{
		return std::abs(a - b) <= kTolerance;
	}

	/// -180 and 180 are the same yaw.
	bool same_yaw(double a, double b)
	{
		const double d = std::fmod(std::abs(a - b), 360.0);
		return std::min(d, 360.0 - d) <= kTolerance;
	}

	cb::Range to_range(const nlohmann::json &j)
	{
		return {j.at(0).get<int>(), j.at(1).get<int>(), j.at(2).get<int>(), j.at(3).get<int>()};
	}
}

TEST(position_mapping)
{
	const cb::Vec3 p = cb::cp_to_mc_pos(1.0, 2.0, 3.0);
	CHECK(p.x == 1.0 && p.y == 3.0 && p.z == -2.0);
}

TEST(mc_position_mapping)
{
	// plan layer 2 section 1.1: Minecraft (x, y, z) -> Cyberpunk (x, -z, y)
	const cb::Vec3 p = cb::mc_to_cp_pos(1.0, 3.0, -2.0);
	CHECK(p.x == 1.0 && p.y == 2.0 && p.z == 3.0);
}

TEST(mc_position_round_trips_with_cp_to_mc)
{
	const cb::Vec3 cp = cb::mc_to_cp_pos(-12.5, 64.25, 40.75);
	const cb::Vec3 mc = cb::cp_to_mc_pos(cp.x, cp.y, cp.z);
	CHECK(mc.x == -12.5 && mc.y == 64.25 && mc.z == 40.75);
	const cb::Vec3 back = cb::mc_to_cp_pos(mc.x, mc.y, mc.z);
	CHECK(back.x == cp.x && back.y == cp.y && back.z == cp.z);
}

TEST(mc_block_aabb_example)
{
	// plan layer 2 section 1.1: block (10, 4, -21) -> ((10, 20, 4), (11, 21, 5))
	const cb::Aabb box = cb::mc_block_to_cp_aabb(10, 4, -21);
	CHECK(box.min.x == 10.0 && box.min.y == 20.0 && box.min.z == 4.0);
	CHECK(box.max.x == 11.0 && box.max.y == 21.0 && box.max.z == 5.0);
}

namespace
{
	// Literal cases computed from the section 1.1 formulas (mc_to_cp_pos, mc_block_to_cp_aabb) outside this code base.
	// tools/cbmath.py + shared/vectors.json own the truth; these keep cb_tests hermetic.
	struct PosCase
	{
		cb::Vec3 mc, cp;
	};
	const PosCase kPosCases[] = {
		{{1.0, 3.0, -2.0}, {1.0, 2.0, 3.0}},
		{{0.0, 0.0, 0.0}, {0.0, 0.0, 0.0}},
		{{-12.5, 64.25, 40.75}, {-12.5, -40.75, 64.25}},
		{{1234.5, -10.0, -987.6}, {1234.5, 987.6, -10.0}},
		{{-0.01, 319.99, 0.01}, {-0.01, -0.01, 319.99}},
		{{29999999.0, 5.5, -29999999.0}, {29999999.0, 29999999.0, 5.5}},
	};

	struct BlockCase
	{
		int bx, by, bz;
		cb::Vec3 min, max;
	};
	const BlockCase kBlockCases[] = {
		{10, 4, -21, {10, 20, 4}, {11, 21, 5}},
		{0, 0, 0, {0, -1, 0}, {1, 0, 1}},
		{-1, -1, -1, {-1, 0, -1}, {0, 1, 0}},
		{123, 70, 456, {123, -457, 70}, {124, -456, 71}},
		{-30, 319, 17, {-30, -18, 319}, {-29, -17, 320}},
		{29999999, -64, -29999999, {29999999, 29999998, -64}, {30000000, 29999999, -63}},
	};

	bool same(const cb::Vec3 &a, const cb::Vec3 &b)
	{
		return close(a.x, b.x) && close(a.y, b.y) && close(a.z, b.z);
	}
}

TEST(mc_position_cases)
{
	for (const PosCase &c : kPosCases)
	{
		CHECK(same(cb::mc_to_cp_pos(c.mc.x, c.mc.y, c.mc.z), c.cp));
		CHECK(same(cb::cp_to_mc_pos(c.cp.x, c.cp.y, c.cp.z), c.mc));
	}
}

TEST(mc_block_aabb_cases)
{
	for (const BlockCase &c : kBlockCases)
	{
		const cb::Aabb box = cb::mc_block_to_cp_aabb(c.bx, c.by, c.bz);
		CHECK(same(box.min, c.min));
		CHECK(same(box.max, c.max));
		// a block is a 1 m cube whose Cyberpunk footprint covers the Minecraft cell
		CHECK(close(box.max.x - box.min.x, 1.0) && close(box.max.y - box.min.y, 1.0) && close(box.max.z - box.min.z, 1.0));
	}
}

// Track P adds `mc_pos` ({mc, cp}) and `mc_block` ({block, min, max}) to shared/vectors.json. The literal cases above
// stand alone; these run on the shared file as soon as P's cases are merged (until then they report a skip).
TEST(mc_position_vectors)
{
	const nlohmann::json all = vectors();
	if (!all.contains("mc_pos"))
		return (void)std::puts("  (skipped: shared/vectors.json has no mc_pos cases yet)");
	CHECK(!all.at("mc_pos").empty());
	for (const auto &c : all.at("mc_pos"))
	{
		const auto &mc = c.at("mc");
		const auto &cp = c.at("cp");
		CHECK(same(cb::mc_to_cp_pos(mc.at(0), mc.at(1), mc.at(2)), {cp.at(0), cp.at(1), cp.at(2)}));
	}
}

TEST(mc_block_vectors)
{
	const nlohmann::json all = vectors();
	if (!all.contains("mc_block"))
		return (void)std::puts("  (skipped: shared/vectors.json has no mc_block cases yet)");
	CHECK(!all.at("mc_block").empty());
	for (const auto &c : all.at("mc_block"))
	{
		const auto &b = c.at("block");
		const auto &lo = c.at("min");
		const auto &hi = c.at("max");
		const cb::Aabb box = cb::mc_block_to_cp_aabb(b.at(0), b.at(1), b.at(2));
		CHECK(same(box.min, {lo.at(0), lo.at(1), lo.at(2)}));
		CHECK(same(box.max, {hi.at(0), hi.at(1), hi.at(2)}));
	}
}

TEST(vfov_from_hfov)
{
	CHECK(std::abs(cb::vfov_from_hfov(90.0, 16.0 / 9.0) - 58.7155) <= 1e-3);
}

TEST(quat_vectors)
{
	const nlohmann::json cases = vectors().at("quat");
	CHECK(!cases.empty());
	for (const auto &c : cases)
	{
		const auto &q = c.at("q");
		const cb::YawPitch yp = cb::cp_quat_to_mc_yaw_pitch(q.at(0), q.at(1), q.at(2), q.at(3));
		CHECK(same_yaw(yp.yaw, c.at("yaw")));
		CHECK(close(yp.pitch, c.at("pitch")));
	}
}

TEST(down_vectors)
{
	const nlohmann::json cases = vectors().at("down");
	CHECK(!cases.empty());
	for (const auto &c : cases)
	{
		const auto &h = c.at("hit");
		CHECK(cb::down_hit_to_range(h.at(0), h.at(1), h.at(2)) == to_range(c.at("range")));
	}
}

TEST(wall_vectors)
{
	const nlohmann::json cases = vectors().at("wall");
	CHECK(!cases.empty());
	for (const auto &c : cases)
	{
		const auto &h = c.at("hit");
		CHECK(cb::wall_hit_to_range(h.at(0), h.at(1), h.at(2)) == to_range(c.at("range")));
	}
}
