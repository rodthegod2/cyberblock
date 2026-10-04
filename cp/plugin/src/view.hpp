// The on-foot third-person view (re-test 3 plan task D5, F2). Minecraft's F5 sends `persp` (world.cpp keeps the mode);
// while the mode is `back` and the scripts allow it (Cyberblock_SetViewAllowed: Active on foot, not held, no menu, no
// braindance, no photo mode), view.cpp moves V's first-person camera component behind and above V's head
// (FPPCameraComponent.SetLocalPosition), shortened by a raycast so Night City and Minecraft's collision boxes stop it.
// Everything else restores the component's own local position. Cyberpunk's vehicle cameras are never touched.
//
// Phase Q (megaplan Q-D1): Minecraft's `prefs` (world::view_prefs) set the distance, the shoulder offset, the FOV and
// the auto-recenter; four corner rays keep the camera out of walls; the boom and the shoulder move critically damped.
//
// The pure parts (the orbit, the rays, the smoothing, the recenter) are inline here, so cb_tests needs no game; view.cpp
// is the game.
#pragma once
#include "cbmath.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <optional>
#include <span>

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

	// ---- Phase Q (megaplan Q-D1): collision from four corner rays, smoothing, the shoulder and its auto-recenter ----------
	//
	// The camera sits on the segment from V's eye to where the orbit wants it, scaled by boom / R (R the length the rays
	// were cast for): the segment up to the nearest hit minus the margin is clear, so the camera never goes into a wall.

	/// Metres the camera stops short of a surface a ray hit, and the shortest boom.
	inline constexpr double kWallMargin = 0.25;
	inline constexpr double kMinDistance = 0.4;
	/// Half the width and height of the small box around the camera whose corners the four rays aim at (m): wider than
	/// the near plane (2 cm away, about 3 cm across) so the picture's edges never reach into a wall either.
	inline constexpr double kCornerHalfWidth = 0.2;
	inline constexpr double kCornerHalfHeight = 0.12;
	/// The shoulder swings to the centre while a wall is within kRecenterNear of the eye on the shoulder side and back
	/// once none is within kRecenterFar (the gap keeps it from flapping along a wall at 0.6 m).
	inline constexpr double kRecenterNear = 0.6;
	inline constexpr double kRecenterFar = 0.7;

	/// One step of a critically damped spring (the usual SmoothDamp: no overshoot for a still target) of `value` toward
	/// `target` over about `smoothTime` seconds. Never passes the target.
	inline void smooth_damp(double &value, double &velocity, double target, double smoothTime, double dt)
	{
		const double omega = 2.0 / smoothTime;
		const double x = omega * dt;
		const double decay = 1.0 / (1.0 + x + 0.48 * x * x + 0.235 * x * x * x);
		const double change = value - target;
		const double temp = (velocity + omega * change) * dt;
		const bool above = value > target;
		velocity = (velocity - omega * temp) * decay;
		value = target + (change + temp) * decay;
		if (above ? value < target : value > target)
		{
			value = target;
			velocity = 0.0;
		}
	}

	/// The camera's up in V's local axes (X right, Y forward, Z up) at Minecraft pitch `pitchDeg` (positive looks down):
	/// perpendicular to its forward (0, cos p, -sin p).
	inline Vec3 camera_up_local(double pitchDeg)
	{
		const double p = pitchDeg * 3.14159265358979323846 / 180.0;
		return {0.0, std::sin(p), std::cos(p)};
	}

	struct Ray
	{
		Vec3 from{}, to{};
		double length() const { return std::hypot(to.x - from.x, to.y - from.y, to.z - from.z); }
	};

	/// The four collision rays: from the eye to the corners of the kCornerHalfWidth x kCornerHalfHeight box around the
	/// camera (`right`, `up`: the camera's unit axes in the same space). Order: right-up, left-up, right-down, left-down.
	inline std::array<Ray, 4> corner_rays(Vec3 eye, Vec3 camera, Vec3 right, Vec3 up)
	{
		std::array<Ray, 4> rays;
		int i = 0;
		for (const double v : {1.0, -1.0})
			for (const double h : {1.0, -1.0})
			{
				const double x = h * kCornerHalfWidth, y = v * kCornerHalfHeight;
				rays[i++] = {eye, {camera.x + right.x * x + up.x * y, camera.y + right.y * x + up.y * y,
								   camera.z + right.z * x + up.z * y}};
			}
		return rays;
	}

	/// How long the boom may be when it was cast `reach` metres long and corner ray i (length lengths[i]) hit something
	/// hits[i] metres from the eye: reach x the smallest (hit - 0.25 m) / length, within 0.4 m..reach. nullopt when no ray
	/// hit anything (or none of them is usable): the boom is free.
	inline std::optional<double> collide_distance(double reach, std::span<const double> lengths,
												  std::span<const std::optional<double>> hits)
	{
		std::optional<double> fraction;
		for (size_t i = 0; i < lengths.size() && i < hits.size(); ++i)
		{
			if (!hits[i] || !std::isfinite(*hits[i]) || !(lengths[i] > 1e-6))
				continue;
			const double f = (*hits[i] - kWallMargin) / lengths[i];
			if (!fraction || f < *fraction)
				fraction = f;
		}
		if (!fraction || !(reach > 0.0))
			return std::nullopt;
		return std::clamp(reach * *fraction, std::min(kMinDistance, reach), reach);
	}

	/// The boom's length over time, critically damped: in fast (kInTime), out slower (kOutTime), and never past `limit`
	/// (the collision distance): a wall coming closer pulls the camera in at once, so it never goes through it.
	class Boom
	{
	public:
		static constexpr double kInTime = 0.05; // s
		static constexpr double kOutTime = 0.3; // s

		/// Restarts at `distance` (the view was just applied).
		void reset(double distance)
		{
			m_distance = distance;
			m_velocity = 0.0;
		}
		/// One frame of `dt` seconds toward `target` metres, held under `limit` (infinity: no wall).
		double step(double target, double limit, double dt)
		{
			if (!(dt > 0.0) || !std::isfinite(target) || std::isnan(limit))
				return m_distance;
			smooth_damp(m_distance, m_velocity, target, target < m_distance ? kInTime : kOutTime, dt);
			if (m_distance > limit)
			{
				m_distance = limit;
				m_velocity = 0.0;
			}
			return m_distance;
		}
		double distance() const { return m_distance; }

	private:
		double m_distance = 0.0;
		double m_velocity = 0.0;
	};

	/// The ray that looks for a wall on the shoulder side: from the eye kRecenterFar metres along `right` (unit) times the
	/// sign of `shoulder`.
	inline Ray shoulder_probe(Vec3 eye, Vec3 right, double shoulder)
	{
		const double s = shoulder < 0.0 ? -kRecenterFar : kRecenterFar;
		return {eye, {eye.x + right.x * s, eye.y + right.y * s, eye.z + right.z * s}};
	}

	/// The over-the-shoulder offset over time (critically damped over kSmoothTime) with the auto-recenter.
	class Shoulder
	{
	public:
		static constexpr double kSmoothTime = 0.25; // s

		void reset(double offset)
		{
			m_offset = offset;
			m_velocity = 0.0;
			m_recentering = false;
		}
		/// One frame toward `wanted` (prefs `shoulder`); with `recenter` on, toward 0 while the shoulder probe finds a wall
		/// `wall` metres from the eye (nullopt: none within kRecenterFar) closer than kRecenterNear, and back to `wanted`
		/// once none is within kRecenterFar.
		double step(double wanted, bool recenter, std::optional<double> wall, double dt)
		{
			if (!std::isfinite(wanted))
				return m_offset;
			if (!recenter || wanted == 0.0 || !wall || *wall >= kRecenterFar)
				m_recentering = false;
			else if (*wall < kRecenterNear)
				m_recentering = true;
			if (dt > 0.0)
				smooth_damp(m_offset, m_velocity, m_recentering ? 0.0 : wanted, kSmoothTime, dt);
			return m_offset;
		}
		double offset() const { return m_offset; }
		bool recentering() const { return m_recentering; }

	private:
		double m_offset = 0.0;
		double m_velocity = 0.0;
		bool m_recentering = false;
	};

	/// What the `view:` log line shows. changed() says when it is worth a new line: a distance moved by 0.1 m or more, or
	/// the recenter flipped.
	struct ViewLine
	{
		double dist = 0.0, collide = 0.0, shoulder = 0.0;
		bool recenter = false;
		bool changed(const ViewLine &last) const
		{
			return std::abs(dist - last.dist) >= 0.1 || std::abs(collide - last.collide) >= 0.1 ||
				   std::abs(shoulder - last.shoulder) >= 0.1 || recenter != last.recenter;
		}
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
	/// `cam.vdbg` (megaplan section 1.9): [collision distance m, smoothed distance m, ms since the view's last tick]; the
	/// distances are 0 while the view is not applied. Any thread.
	std::array<double, 3> vdbg();
}
