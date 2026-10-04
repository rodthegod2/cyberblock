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
#include <cstdint>

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

	// ---- Steve's body for the compositor's re-projection (in-game 2026-10-03: third person "lags behind") ---------------
	//
	// The compositor re-projects Minecraft's (older) frame onto Cyberpunk's newer camera as if everything in it stood still.
	// Steve does not: he is drawn at V's feet of the cam the frame was rendered for, and V has moved on with the camera since,
	// so a still-standing Steve slid back by V's motion over the frame's age (a few cm walking, a metre or more gliding).
	// The body is the capsule Steve is drawn in; the effect moves what lies inside it by V's motion instead (Cyberblock.fx).

	/// How Steve is drawn: not at all (first person), standing (feet at `cam.pl`), seated (`cam.pl` is V's hips) or gliding
	/// (his body lies along his look, from the feet: vanilla's fall-flying pose pivots there).
	enum class BodyPose
	{
		None,
		Standing,
		Seated,
		Gliding,
	};

	/// A capsule in Minecraft coordinates (y up): the segment a-b grown by `radius`. `valid` false: no body.
	struct Capsule
	{
		Vec3 a{}, b{};
		double radius = 0.0;
		bool valid = false;
	};

	/// Steve's capsule for a cam: `feet` is the cam's `pl` (Minecraft coordinates), `yawDeg`/`pitchDeg` his look in
	/// Minecraft's convention (a gliding body lies along it). Standing: 1.9 m tall, 0.5 m around (arms swing out); seated:
	/// from 0.7 m under the hips to 1.35 m over them, 0.6 m around (legs forward); gliding: 0.35..1.45 m along the look
	/// from the feet, 0.75 m around (the wings).
	inline Capsule body_capsule(BodyPose pose, Vec3 feet, double yawDeg, double pitchDeg)
	{
		Capsule c;
		switch (pose)
		{
		case BodyPose::None:
			return c;
		case BodyPose::Standing:
			c.a = {feet.x, feet.y + 0.5, feet.z};
			c.b = {feet.x, feet.y + 1.4, feet.z};
			c.radius = 0.5;
			break;
		case BodyPose::Seated:
			c.a = {feet.x, feet.y - 0.1, feet.z};
			c.b = {feet.x, feet.y + 0.75, feet.z};
			c.radius = 0.6;
			break;
		case BodyPose::Gliding:
		{
			constexpr double kDeg = 3.14159265358979323846 / 180.0;
			const double cp = std::cos(pitchDeg * kDeg);
			const Vec3 d{-std::sin(yawDeg * kDeg) * cp, -std::sin(pitchDeg * kDeg), std::cos(yawDeg * kDeg) * cp};
			c.a = {feet.x + d.x * 0.35, feet.y + d.y * 0.35, feet.z + d.z * 0.35};
			c.b = {feet.x + d.x * 1.45, feet.y + d.y * 1.45, feet.z + d.z * 1.45};
			c.radius = 0.75;
			break;
		}
		}
		c.valid = std::isfinite(c.a.x) && std::isfinite(c.a.y) && std::isfinite(c.a.z) && std::isfinite(c.b.x) &&
				  std::isfinite(c.b.y) && std::isfinite(c.b.z);
		return c;
	}

	/// The body of one published cam: its frame number (`cam.f`, which Minecraft echoes in every exported frame), V's feet
	/// (`cam.pl`, Minecraft coordinates) and the capsule Steve is drawn in.
	struct Body
	{
		int64_t frame = -1;
		Vec3 feet{};
		Capsule capsule;
	};

	/// The bodies of the last kSize published cams, found again by frame number when Minecraft's frame names the cam it
	/// was rendered for. Not thread-safe (the compositor holds its pose lock).
	class BodyRing
	{
	public:
		static constexpr int kSize = 64; // a second of cams at 60 fps: Minecraft's frames are never older

		void put(const Body &body)
		{
			m_ring[m_next] = body;
			m_next = (m_next + 1) % kSize;
		}
		/// The body published with `frame`; an invalid one (frame -1) when it is not among the last kSize.
		Body find(int64_t frame) const
		{
			for (const Body &b : m_ring)
				if (b.frame == frame && frame >= 0)
					return b;
			return {};
		}

	private:
		Body m_ring[kSize];
		int m_next = 0;
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
