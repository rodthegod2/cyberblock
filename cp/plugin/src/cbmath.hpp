// Cyberpunk -> Minecraft conversions: a line-for-line mirror of tools/cbmath.py, the single source of truth.
// Change cbmath.py first, regenerate shared/vectors.json, then mirror the change here (cb_tests checks the vectors).
#pragma once
#include <array>

namespace cb
{
	struct Vec3
	{
		double x, y, z;
	};

	struct YawPitch
	{
		double yaw, pitch; // Minecraft degrees
	};

	/// Inclusive Minecraft block range of one column: {x, z, yBottom, yTop}, the wire protocol's quadruple.
	using Range = std::array<int, 4>;

	Vec3 cp_to_mc_pos(double x, double y, double z);
	/// Cyberpunk camera forward: the quaternion (i, j, k, r) applied to local +Y.
	Vec3 cp_quat_forward(double i, double j, double k, double r);
	YawPitch mc_yaw_pitch(double dx, double dy, double dz);
	YawPitch cp_quat_to_mc_yaw_pitch(double i, double j, double k, double r);
	double vfov_from_hfov(double hfov_deg, double aspect);
	Range down_hit_to_range(double x, double y, double z);
	Range wall_hit_to_range(double x, double y, double z);
}
