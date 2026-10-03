// Checks the C++ mirror against tools/cbmath.py through shared/vectors.json (CB_VECTORS, set by CMake).
#include "cbmath.hpp"
#include "test.hpp"
#include "world.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <fstream>
#include <nlohmann/json.hpp>
#include <vector>

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
	const cb::Vec3 p = cb::cp_to_mc_pos(1.0, 2.0, 3.0, 0.0);
	CHECK(p.x == 1.0 && p.y == 3.0 && p.z == -2.0);
}

TEST(mc_position_mapping)
{
	// plan layer 2 section 1.1: Minecraft (x, y, z) -> Cyberpunk (x, -z, y)
	const cb::Vec3 p = cb::mc_to_cp_pos(1.0, 3.0, -2.0, 0.0);
	CHECK(p.x == 1.0 && p.y == 2.0 && p.z == 3.0);
}

TEST(mc_position_round_trips_with_cp_to_mc)
{
	const cb::Vec3 cp = cb::mc_to_cp_pos(-12.5, 64.25, 40.75, 0.0);
	const cb::Vec3 mc = cb::cp_to_mc_pos(cp.x, cp.y, cp.z, 0.0);
	CHECK(mc.x == -12.5 && mc.y == 64.25 && mc.z == 40.75);
	const cb::Vec3 back = cb::mc_to_cp_pos(mc.x, mc.y, mc.z, 0.0);
	CHECK(back.x == cp.x && back.y == cp.y && back.z == cp.z);
}

TEST(mc_block_aabb_example)
{
	// plan layer 2 section 1.1: block (10, 4, -21) -> ((10, 20, 4), (11, 21, 5))
	const cb::Aabb box = cb::mc_block_to_cp_aabb(10, 4, -21, 0.0);
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
		CHECK(same(cb::mc_to_cp_pos(c.mc.x, c.mc.y, c.mc.z, 0.0), c.cp));
		CHECK(same(cb::cp_to_mc_pos(c.cp.x, c.cp.y, c.cp.z, 0.0), c.mc));
	}
}

TEST(mc_block_aabb_cases)
{
	for (const BlockCase &c : kBlockCases)
	{
		const cb::Aabb box = cb::mc_block_to_cp_aabb(c.bx, c.by, c.bz, 0.0);
		CHECK(same(box.min, c.min));
		CHECK(same(box.max, c.max));
		// a block is a 1 m cube whose Cyberpunk footprint covers the Minecraft cell
		CHECK(close(box.max.x - box.min.x, 1.0) && close(box.max.y - box.min.y, 1.0) && close(box.max.z - box.min.z, 1.0));
	}
}

// shared/vectors.json `mc_pos` ({mc, k, cp}) and `mc_block` ({block, k, min, max}), at k 0 and 57 (plan retest2 D1).
namespace
{
	double offset_of(const nlohmann::json &c)
	{
		return cb::world::level_offset(c.at("k").get<int>());
	}
}

TEST(mc_position_vectors)
{
	const nlohmann::json all = vectors();
	CHECK(all.at("mc_pos").size() >= 12);
	bool sawOffset = false;
	for (const auto &c : all.at("mc_pos"))
	{
		const auto &mc = c.at("mc");
		const auto &cp = c.at("cp");
		const double off = offset_of(c);
		sawOffset = sawOffset || off != 0.0;
		CHECK(same(cb::mc_to_cp_pos(mc.at(0), mc.at(1), mc.at(2), off), {cp.at(0), cp.at(1), cp.at(2)}));
		CHECK(same(cb::cp_to_mc_pos(cp.at(0), cp.at(1), cp.at(2), off), {mc.at(0), mc.at(1), mc.at(2)}));
	}
	CHECK(sawOffset);
}

TEST(mc_block_vectors)
{
	const nlohmann::json all = vectors();
	CHECK(all.at("mc_block").size() >= 12);
	for (const auto &c : all.at("mc_block"))
	{
		const auto &b = c.at("block");
		const auto &lo = c.at("min");
		const auto &hi = c.at("max");
		const cb::Aabb box = cb::mc_block_to_cp_aabb(b.at(0), b.at(1), b.at(2), offset_of(c));
		CHECK(same(box.min, {lo.at(0), lo.at(1), lo.at(2)}));
		CHECK(same(box.max, {hi.at(0), hi.at(1), hi.at(2)}));
	}
}

// ---- layers 3-6 (plan 2026-10-03 section 1.1): yaw and velocity ---------------------------------------------------------

namespace
{
	// Minecraft yaw m -> Cyberpunk yaw a = 180 - m, normalised to (-180, 180]; computed by hand from the section 1.1 formula.
	struct YawCase
	{
		double mc, cp;
	};
	const YawCase kYawCases[] = {
		{0.0, 180.0}, {90.0, 90.0}, {-90.0, -90.0}, {180.0, 0.0}, {-180.0, 0.0}, {359.5, -179.5},
		{45.0, 135.0}, {-45.0, -135.0}, {1.0, 179.0}, {-1.0, -179.0}, {179.0, 1.0}, {-179.0, -1.0},
		{360.0, 180.0}, {-360.0, 180.0}, {720.0, 180.0},
	};
}

TEST(mc_yaw_cases)
{
	for (const YawCase &c : kYawCases)
	{
		const double got = cb::mc_yaw_to_cp_yaw(c.mc);
		CHECK(close(got, c.cp));
		CHECK(got > -180.0 && got <= 180.0); // (-180, 180]: a half-turn is +180, never -180
	}
}

TEST(mc_yaw_round_trips_with_cp_yaw_to_mc_yaw)
{
	// m = 180 - a is its own inverse: layer 2's cp_yaw_to_mc_yaw undoes mc_yaw_to_cp_yaw (same turn, so compare modulo 360)
	for (double m = -360.0; m <= 360.0; m += 22.5)
	{
		const double back = cb::world::cp_yaw_to_mc_yaw(cb::mc_yaw_to_cp_yaw(m));
		CHECK(same_yaw(back, m));
	}
}

TEST(mc_yaw_agrees_with_the_quaternion_conversion)
{
	// Minecraft yaw m faces (-sin m, cos m) in Minecraft, so a Cyberpunk entity with yaw a = mc_yaw_to_cp_yaw(m) (a turn of
	// a degrees about +Z: quaternion (0, 0, sin(a/2), cos(a/2))) has the forward vector cbmath turns back into yaw m
	for (double m = -180.0; m <= 180.0; m += 15.0)
	{
		const double h = cb::mc_yaw_to_cp_yaw(m) * 3.14159265358979323846 / 360.0;
		CHECK(same_yaw(cb::cp_quat_to_mc_yaw_pitch(0, 0, std::sin(h), std::cos(h)).yaw, m));
	}
}

TEST(mc_vel_cases)
{
	const cb::Vec3 v = cb::mc_vel_to_cp(1.0, 2.0, 3.0);
	CHECK(v.x == 1.0 && v.y == -3.0 && v.z == 2.0); // (x, -z, y): up (+Y) is up (+Z), south (+Z) is -Y
	CHECK(same(cb::mc_vel_to_cp(0.0, 0.0, 0.0), {0.0, 0.0, 0.0}));
	CHECK(same(cb::mc_vel_to_cp(0.0, -1.5, 0.0), {0.0, 0.0, -1.5})); // falling stays falling
	CHECK(same(cb::mc_vel_to_cp(-200.0, 200.0, -200.0), {-200.0, 200.0, 200.0}));
	CHECK(same(cb::mc_vel_to_cp(12.25, -3.5, -7.75), {12.25, 7.75, -3.5}));
	// no offset (unlike a block corner), and the same map as a position
	const cb::Vec3 p = cb::mc_to_cp_pos(4.0, 5.0, 6.0, 0.0);
	CHECK(same(cb::mc_vel_to_cp(4.0, 5.0, 6.0), p));
}

// P adds `mc_yaw` ({mc, cp}) and `mc_vel` ({mc, cp}) to shared/vectors.json (plan section 1.7). The literal cases above stand
// alone; these run on the shared file as soon as P's cases are merged (until then they report a skip).
TEST(mc_yaw_vectors)
{
	const nlohmann::json all = vectors();
	if (!all.contains("mc_yaw"))
		return (void)std::puts("  (skipped: shared/vectors.json has no mc_yaw cases yet)");
	CHECK(all.at("mc_yaw").size() >= 6);
	for (const auto &c : all.at("mc_yaw"))
		CHECK(same_yaw(cb::mc_yaw_to_cp_yaw(c.at("mc")), c.at("cp")));
}

TEST(mc_vel_vectors)
{
	const nlohmann::json all = vectors();
	if (!all.contains("mc_vel"))
		return (void)std::puts("  (skipped: shared/vectors.json has no mc_vel cases yet)");
	CHECK(all.at("mc_vel").size() >= 6);
	for (const auto &c : all.at("mc_vel"))
	{
		const auto &mc = c.at("mc");
		const auto &cp = c.at("cp");
		CHECK(same(cb::mc_vel_to_cp(mc.at(0), mc.at(1), mc.at(2)), {cp.at(0), cp.at(1), cp.at(2)}));
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
	CHECK(cases.size() >= 24); // k 0, 32, 57 and 63, plus the noise cases
	for (const auto &c : cases)
	{
		const auto &h = c.at("hit");
		CHECK(cb::down_hit_to_range(h.at(0), h.at(1), h.at(2), offset_of(c)) == to_range(c.at("range")));
	}
}

TEST(wall_vectors)
{
	const nlohmann::json cases = vectors().at("wall");
	CHECK(cases.size() >= 24);
	for (const auto &c : cases)
	{
		const auto &h = c.at("hit");
		CHECK(cb::wall_hit_to_range(h.at(0), h.at(1), h.at(2), offset_of(c)) == to_range(c.at("range")));
	}
}

// ---- re-test 2 (plan 2026-10-03-retest2 section 1.1): the ground alignment -------------------------------------------------

TEST(offset_moves_positions_by_off_both_ways)
{
	const double off = cb::world::level_offset(57); // 0.890625
	CHECK(off == 0.890625 && cb::world::level_offset(0) == 0.0 && cb::world::level_offset(63) == 63.0 / 64.0);
	CHECK(float(off) == off); // exact in float32: Vector4 carries it unchanged
	CHECK(same(cb::cp_to_mc_pos(1.0, 2.0, 3.0, 0.5), {1.0, 2.5, -2.0}));
	CHECK(same(cb::mc_to_cp_pos(1.0, 2.5, -2.0, 0.5), {1.0, 2.0, 3.0}));
	CHECK(same(cb::mc_to_cp_pos(0.0, 70.0, 0.0, off), {0.0, 0.0, 70.890625}));
	for (const int k : {0, 1, 32, 57, 63})
		for (const cb::Vec3 &p : {cb::Vec3{1.0, 2.0, 3.0}, cb::Vec3{-1245.5, 812.25, 66.62}, cb::Vec3{7.25, -3.0, 6.907}})
		{
			const double o = cb::world::level_offset(k);
			const cb::Vec3 mc = cb::cp_to_mc_pos(p.x, p.y, p.z, o);
			CHECK(same(cb::mc_to_cp_pos(mc.x, mc.y, mc.z, o), p));
		}
}

TEST(block_aabb_with_offset)
{
	const double off = cb::world::level_offset(57);
	const cb::Aabb box = cb::mc_block_to_cp_aabb(10, 4, -21, off);
	CHECK(same(box.min, {10, 20, 4.890625}) && same(box.max, {11, 21, 5.890625}));
	// a point inside the box is in the block's own cell
	const cb::Vec3 inside{(box.min.x + box.max.x) / 2, (box.min.y + box.max.y) / 2, (box.min.z + box.max.z) / 2};
	CHECK((cb::wall_hit_to_range(inside.x, inside.y, inside.z, off) == cb::Range{10, -21, 4, 4}));
}

TEST(down_hits_round_to_the_nearest_whole_y)
{
	CHECK((cb::down_hit_to_range(0.5, 0.5, 12.25, 0.0) == cb::Range{0, -1, 10, 11})); // was 12 with ceil: a block above
	CHECK((cb::down_hit_to_range(0.5, 0.5, 12.5, 0.0) == cb::Range{0, -1, 11, 12}));  // a tie goes up
	CHECK((cb::down_hit_to_range(0.5, 0.5, 12.49, 0.0) == cb::Range{0, -1, 10, 11}));
	CHECK((cb::down_hit_to_range(0.5, 0.5, -0.75, 0.0) == cb::Range{0, -1, -3, -2}));
	const double off = cb::world::level_offset(57);
	for (const double d : {-1e-6, 0.0, 1e-6, -1e-4, 1e-4}) // float noise on an aligned floor gives one range
		CHECK((cb::down_hit_to_range(3.5, 2.5, 6.907 + d, off) == cb::Range{3, -3, 4, 5}));
	CHECK((cb::wall_hit_to_range(0.5, 0.5, 6.907, off) == cb::Range{0, -1, 6, 6}));
}

TEST(directions_never_take_the_offset)
{
	CHECK(same(cb::cp_dir_to_mc(1.0, 2.0, 3.0), {1.0, 3.0, -2.0}));
	CHECK(same(cb::cp_dir_to_mc(1.0, 2.0, 3.0), cb::cp_to_mc_pos(1.0, 2.0, 3.0, 0.0)));
	CHECK(!same(cb::cp_dir_to_mc(1.0, 2.0, 3.0), cb::cp_to_mc_pos(1.0, 2.0, 3.0, 0.5)));
	CHECK(same(cb::mc_vel_to_cp(1.0, 2.0, 3.0), {1.0, -3.0, 2.0}));
	// the look comes from cp_dir_to_mc: the same at every k (compare with the mapping written out)
	for (const auto &q : {std::array<double, 4>{0, 0, 0, 1}, std::array<double, 4>{0.1, -0.2, 0.3, 0.927}})
	{
		const cb::Vec3 f = cb::cp_quat_forward(q[0], q[1], q[2], q[3]);
		const cb::YawPitch want = cb::mc_yaw_pitch(f.x, f.z, -f.y);
		const cb::YawPitch got = cb::cp_quat_to_mc_yaw_pitch(q[0], q[1], q[2], q[3]);
		CHECK(close(got.yaw, want.yaw) && close(got.pitch, want.pitch));
	}
}

TEST(vector_file_has_the_alignment_cases)
{
	const nlohmann::json all = vectors();
	std::vector<int> ks;
	for (const auto &c : all.at("down"))
		ks.push_back(c.at("k").get<int>());
	for (const int k : {0, 32, 57, 63})
		CHECK(std::find(ks.begin(), ks.end(), k) != ks.end());
	CHECK(all.at("align").size() == 7); // align_k is Minecraft's (not mirrored); the set is there for Java and Python
}
