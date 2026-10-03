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

	/// Axis-aligned box in Cyberpunk space (metres).
	struct Aabb
	{
		Vec3 min, max;
	};

	/// Inclusive Minecraft block range of one column: {x, z, yBottom, yTop}, the wire protocol's quadruple.
	using Range = std::array<int, 4>;

	Vec3 cp_to_mc_pos(double x, double y, double z);
	/// Inverse of cp_to_mc_pos: Minecraft (x, y, z) -> Cyberpunk (x, -z, y). Directions map the same way.
	Vec3 mc_to_cp_pos(double x, double y, double z);
	/// A velocity or impulse (m/s) maps like a position, with no offset. A separate name so callers say what they mean.
	Vec3 mc_vel_to_cp(double vx, double vy, double vz);
	/// Minecraft yaw (degrees, 0 = facing +Z) -> Cyberpunk yaw (degrees, 0 = facing +Y, counter-clockwise from above):
	/// a = 180 - m, normalised to (-180, 180]. The same formula maps back, as world::cp_yaw_to_mc_yaw does.
	double mc_yaw_to_cp_yaw(double mcYaw);
	/// The Cyberpunk box of Minecraft block (bx, by, bz): min (bx, -(bz+1), by), max (bx+1, -bz, by+1).
	Aabb mc_block_to_cp_aabb(int bx, int by, int bz);
	/// Cyberpunk camera forward: the quaternion (i, j, k, r) applied to local +Y.
	Vec3 cp_quat_forward(double i, double j, double k, double r);
	YawPitch mc_yaw_pitch(double dx, double dy, double dz);
	YawPitch cp_quat_to_mc_yaw_pitch(double i, double j, double k, double r);
	double vfov_from_hfov(double hfov_deg, double aspect);
	Range down_hit_to_range(double x, double y, double z);
	Range wall_hit_to_range(double x, double y, double z);
}
