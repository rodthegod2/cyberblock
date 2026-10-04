// Cyberpunk <-> Minecraft conversions: a line-for-line mirror of tools/cbmath.py, the single source of truth.
// Change cbmath.py first, regenerate shared/vectors.json, then mirror the change here (cb_tests checks the vectors).
//
// Re-test 2 (plan 2026-10-03-retest2 section 1.1): a world has a ground alignment k (0..63), off = k / 64 m, and
// Minecraft y = Cyberpunk z - off. Every position conversion takes `off` as a REQUIRED parameter, so each call site says
// which alignment it uses (world::level_offset(world::level_k())). Directions, velocities and impulses never take it.
// align_k (choosing k) is Minecraft's job and is not mirrored here.
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

	/// A rotation quaternion (i, j, k, r) as RED4ext's Quaternion stores it.
	struct Quat
	{
		double i, j, k, r;
	};

	/// Axis-aligned box in Cyberpunk space (metres).
	struct Aabb
	{
		Vec3 min, max;
	};

	/// Inclusive Minecraft block range of one column: {x, z, yBottom, yTop}, the wire protocol's quadruple.
	using Range = std::array<int, 4>;

	/// Cyberpunk position (x, y, z) -> Minecraft (x, z - off, -y).
	Vec3 cp_to_mc_pos(double x, double y, double z, double off);
	/// Inverse of cp_to_mc_pos: Minecraft (x, y, z) -> Cyberpunk (x, -z, y + off).
	Vec3 mc_to_cp_pos(double x, double y, double z, double off);
	/// A Cyberpunk direction (camera forward, facing) -> Minecraft (x, z, -y). Never offset.
	Vec3 cp_dir_to_mc(double x, double y, double z);
	/// A Minecraft velocity or impulse (m/s) -> Cyberpunk (vx, -vz, vy). Never offset.
	Vec3 mc_vel_to_cp(double vx, double vy, double vz);
	/// Minecraft yaw (degrees, 0 = facing +Z) -> Cyberpunk yaw (degrees, 0 = facing +Y, counter-clockwise from above):
	/// a = 180 - m, normalised to (-180, 180]. The same formula maps back, as world::cp_yaw_to_mc_yaw does.
	double mc_yaw_to_cp_yaw(double mcYaw);
	/// The Cyberpunk box of Minecraft block (bx, by, bz): min (bx, -(bz+1), by + off), max (bx+1, -bz, by+1 + off).
	Aabb mc_block_to_cp_aabb(int bx, int by, int bz, double off);
	/// Cyberpunk camera forward: the quaternion (i, j, k, r) applied to local +Y.
	Vec3 cp_quat_forward(double i, double j, double k, double r);
	YawPitch mc_yaw_pitch(double dx, double dy, double dz);
	/// The Minecraft look of a Cyberpunk camera quaternion (through cp_dir_to_mc: no offset).
	YawPitch cp_quat_to_mc_yaw_pitch(double i, double j, double k, double r);
	double vfov_from_hfov(double hfov_deg, double aspect);
	/// A floor hit -> the two cells under it; the top face is the nearest whole y to z - off (ties go up).
	Range down_hit_to_range(double x, double y, double z, double off);
	/// A wall hit -> the one cell it is in: y = floor(z - off).
	Range wall_hit_to_range(double x, double y, double z, double off);

	/// Atlas survey cells (cbmath.py SURVEY_CELL): metres per side.
	inline constexpr int kSurveyCell = 2;
	/// A survey cell and the Minecraft y of the highest Night City surface in it.
	struct SurveyCell
	{
		int cx, cz, h;
	};
	/// A survey hit (Cyberpunk space) -> cx = floor(x / 2), cz = floor(-y / 2), h = floor(z - off + 0.5) (the top-face rule
	/// of down_hit_to_range).
	SurveyCell survey_cell(double x, double y, double z, double off);
	/// The Cyberpunk column (x, y) at the centre of cell (cx, cz): (2cx + 1, -(2cz + 1)).
	std::array<double, 2> survey_column(int cx, int cz);

	/// `v` rotated by the unit quaternion `q` (q v q*). C++ only (re-test 3 task D4: V's hips point from V's local axes
	/// into the world); it stays inside Cyberpunk space, so cbmath.py has no mirror. cp_quat_forward(q) == rotate(q, +Y).
	Vec3 rotate(const Quat &q, Vec3 v);
}
