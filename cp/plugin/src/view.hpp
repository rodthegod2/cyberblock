// The on-foot third-person view (re-test 3 plan task D5, F2). Minecraft's F5 sends `persp` (world.cpp keeps the mode);
// while the mode is `back` and the scripts allow it (Cyberblock_SetViewAllowed: Active on foot, not held, no menu, no
// braindance, no photo mode), view.cpp moves V's first-person camera component behind and above V's head
// (FPPCameraComponent.SetLocalPosition), shortened by a raycast so Night City and Minecraft's collision boxes stop it.
// Everything else restores the component's own local position. Cyberpunk's vehicle cameras are never touched.
//
// The pure parts (the orbit and the boom's smoothing) are inline here, so cb_tests needs no game; view.cpp is the game.
#pragma once
#include "cbmath.hpp"
#include <algorithm>
#include <cmath>

namespace cb::view
{
	/// The camera's place behind V: `distance` metres back along the look, `height` above the eye, `shoulder` to the right.
	struct Orbit
	{
		double distance = 3.2;
		double height = 0.25;
		double shoulder = 0.0;
	};

	/// The camera offset from the eye in V's local axes (X right, Y forward, Z up) for a camera pitch of `pitchDeg`
	/// (Minecraft convention: positive looks down), at `distance` metres: (shoulder, -d cos p, h + d sin p). Looking down
	/// lifts the camera, looking up lowers it, so V stays in the middle of the picture.
	inline Vec3 desired_local(const Orbit &orbit, double pitchDeg, double distance)
	{
		const double p = pitchDeg * 3.14159265358979323846 / 180.0;
		return {orbit.shoulder, -distance * std::cos(p), orbit.height + distance * std::sin(p)};
	}
	inline Vec3 desired_local(const Orbit &orbit, double pitchDeg)
	{
		return desired_local(orbit, pitchDeg, orbit.distance);
	}

	/// Metres the camera stops short of a surface the boom ray hit, and the shortest boom.
	inline constexpr double kWallMargin = 0.25;
	inline constexpr double kMinDistance = 0.4;

	/// How long the boom may be when its ray hit something `hitDistance` metres from the eye: hit - 0.25 m, at least 0.4 m.
	inline double collision_distance(double hitDistance)
	{
		return std::max(hitDistance - kWallMargin, kMinDistance);
	}

	/// The boom's length over time: it snaps in at once when a wall comes closer (the camera never goes through it) and
	/// grows back out critically damped (no overshoot) over about kSmoothTime.
	class Boom
	{
	public:
		static constexpr double kSmoothTime = 0.2; // s

		/// Restarts at `distance` (the view was just applied).
		void reset(double distance)
		{
			m_distance = distance;
			m_velocity = 0.0;
		}
		/// One frame of `dt` seconds toward `target` metres.
		double step(double target, double dt)
		{
			if (!(dt > 0.0) || !std::isfinite(target))
				return m_distance;
			if (target <= m_distance)
			{
				m_distance = target;
				m_velocity = 0.0;
				return m_distance;
			}
			// critically damped spring (the usual SmoothDamp): omega = 2 / smooth time
			const double omega = 2.0 / kSmoothTime;
			const double x = omega * dt;
			const double decay = 1.0 / (1.0 + x + 0.48 * x * x + 0.235 * x * x * x);
			const double change = m_distance - target;
			const double temp = (m_velocity + omega * change) * dt;
			m_velocity = (m_velocity - omega * temp) * decay;
			m_distance = target + (change + temp) * decay;
			if (m_distance > target) // never overshoots past the target
			{
				m_distance = target;
				m_velocity = 0.0;
			}
			return m_distance;
		}
		double distance() const { return m_distance; }

	private:
		double m_distance = 0.0;
		double m_velocity = 0.0;
	};

	// ---- the game part (view.cpp) -----------------------------------------------------------------------------------

	/// Main thread, every frame before camera::publish: applies, updates or removes the third-person offset.
	void tick();
	/// The third-person offset is applied this frame (cam.fp is false, the drive leaves the game's mouse X alone). Any thread.
	bool applied();
	/// V's first-person eye in Cyberpunk space: the camera without the third-person offset (Cyberblock_ViewEye). Any thread.
	Vec3 eye();
	/// Cyberblock_SetViewAllowed: the scripts allow the view now. Any thread.
	void set_allowed(bool allowed);
}
