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
