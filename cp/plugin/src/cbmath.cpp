// Line-for-line mirror of tools/cbmath.py (see cbmath.hpp).
#include "cbmath.hpp"
#include <algorithm>
#include <cmath>
#include <numbers>

namespace
{
	// as CPython's math.degrees / math.radians
	double degrees(double x)
	{
		return x * (180.0 / std::numbers::pi);
	}

	double radians(double x)
	{
		return x * (std::numbers::pi / 180.0);
	}

	int floor_int(double x)
	{
		return static_cast<int>(std::floor(x));
	}
}

namespace cb
{
	Vec3 cp_to_mc_pos(double x, double y, double z, double off)
	{
		return {x, z - off, -y};
	}

	Vec3 mc_to_cp_pos(double x, double y, double z, double off)
	{
		return {x, -z, y + off};
	}

	Vec3 cp_dir_to_mc(double x, double y, double z)
	{
		return {x, z, -y};
	}

	Vec3 mc_vel_to_cp(double vx, double vy, double vz)
	{
		return {vx, -vz, vy};
	}

	double mc_yaw_to_cp_yaw(double mcYaw)
	{
		double yaw = std::fmod(180.0 - mcYaw, 360.0);
		if (yaw > 180.0)
			yaw -= 360.0;
		else if (yaw <= -180.0)
			yaw += 360.0;
		return yaw;
	}

	Aabb mc_block_to_cp_aabb(int bx, int by, int bz, double off)
	{
		const double x = bx, y = by, z = bz; // doubles first: no int overflow at the extremes
		return {{x, -(z + 1), y + off}, {x + 1, -z, y + 1 + off}};
	}

	Vec3 cp_quat_forward(double i, double j, double k, double r)
	{
		return {2 * (i * j - r * k), 1 - 2 * (i * i + k * k), 2 * (j * k + r * i)};
	}

	YawPitch mc_yaw_pitch(double dx, double dy, double dz)
	{
		const double n = std::sqrt(dx * dx + dy * dy + dz * dz);
		dx /= n, dy /= n, dz /= n;
		return {degrees(std::atan2(-dx, dz)), degrees(std::asin(std::max(-1.0, std::min(1.0, -dy))))};
	}

	YawPitch cp_quat_to_mc_yaw_pitch(double i, double j, double k, double r)
	{
		const Vec3 f = cp_quat_forward(i, j, k, r);
		const Vec3 m = cp_dir_to_mc(f.x, f.y, f.z);
		return mc_yaw_pitch(m.x, m.y, m.z);
	}

	double vfov_from_hfov(double hfov_deg, double aspect)
	{
		return degrees(2 * std::atan(std::tan(radians(hfov_deg) / 2) / aspect));
	}

	Range down_hit_to_range(double x, double y, double z, double off)
	{
		const int top = floor_int(z - off + 0.5) - 1; // nearest whole y, ties up: as cbmath.py's math.floor, never round()
		return {floor_int(x), floor_int(-y), top - 1, top};
	}

	Range wall_hit_to_range(double x, double y, double z, double off)
	{
		const int b = floor_int(z - off);
		return {floor_int(x), floor_int(-y), b, b};
	}

	Vec3 rotate(const Quat &q, Vec3 v)
	{
		// t = 2 (u x v); v' = v + r t + u x t, with u = (i, j, k)
		const Vec3 t{2.0 * (q.j * v.z - q.k * v.y), 2.0 * (q.k * v.x - q.i * v.z), 2.0 * (q.i * v.y - q.j * v.x)};
		return {v.x + q.r * t.x + (q.j * t.z - q.k * t.y), v.y + q.r * t.y + (q.k * t.x - q.i * t.z),
				v.z + q.r * t.z + (q.i * t.y - q.j * t.x)};
	}
}
