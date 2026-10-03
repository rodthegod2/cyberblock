#include "follow.hpp"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <utility>

namespace
{
	double distance(const cb::Vec3 &a, const cb::Vec3 &b)
	{
		return std::hypot(a.x - b.x, a.y - b.y, a.z - b.z);
	}

	const char *kind(bool launch)
	{
		return launch ? "launch" : "glide";
	}
}

namespace cb::follow
{
	Vec3 stop_short(Vec3 from, Vec3 to, double hitDistance, double margin)
	{
		const double length = distance(from, to);
		if (!(length > 0.0))
			return from;
		const double t = std::clamp((hitDistance - margin) / length, 0.0, 1.0);
		return {from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t};
	}

	Vec3 snap_landing(Vec3 spot, std::optional<double> groundZ)
	{
		constexpr double kMaxDrop = 1.0; // m a landing may leave V above the ground (it then falls, fall damage off)
		if (groundZ && (*groundZ > spot.z || spot.z - *groundZ > kMaxDrop))
			return {spot.x, spot.y, *groundZ};
		return spot;
	}

	Out Follower::end(Act act, Vec3 to, std::string log, double now)
	{
		m_state = State::Idle;
		m_endedAt = now;
		return {act, to, std::move(log)};
	}

	Out Follower::step(const In &in)
	{
		const double dt = m_lastNow < 0 ? 0.0 : std::max(in.now - m_lastNow, 0.0);
		m_lastNow = in.now;
		const bool driving = m_state != State::Idle;

		// the gate first: Stop never moves V, so it is safe even behind a menu
		if (driving && !in.gate)
			return end(Act::Stop, {}, "flight: stopped, V is held, in a vehicle or Cyberblock is off", in.now);

		if (in.menu)
		{
			// the game is paused or a menu is up: nothing moves and no timer runs
			m_start += dt;
			m_holdSince += dt;
			if (m_endedAt >= 0)
				m_endedAt += dt;
			return {};
		}

		if (!driving)
		{
			if (!in.gate || !in.active)
				return {};
			m_state = State::Following;
			m_start = in.now;
			m_launch = in.launch;
			m_last = in.feet;
			m_haveApplied = false;
			m_summary = {};
			++m_summary.frames;
			char line[64];
			std::snprintf(line, sizeof(line), "flight: start (%s)", kind(in.launch));
			return {Act::Move, in.target, line};
		}

		if (in.active)
		{
			m_launch = in.launch; // a launch may turn into a glide mid-air
			++m_summary.frames;
			const bool wasHolding = m_state == State::Holding;
			m_state = State::Following;
			return {Act::Move, in.target, wasHolding ? "flight: samples back" : ""};
		}

		if (!in.timedOut)
		{
			// Minecraft reported the touchdown
			if (in.now - m_start < kQuickLand || distance(in.target, in.feet) < kNearLand)
				return end(Act::Stop, {}, "flight: over, V stays where it is", in.now);
			char line[96];
			std::snprintf(line, sizeof(line), "flight: landed (%s) after %.1f s", kind(m_launch), in.now - m_start);
			return end(Act::Land, in.target, line, in.now);
		}

		// Minecraft went silent: hold V where it was last put, then put it on the ground
		if (m_state == State::Following)
		{
			m_state = State::Holding;
			m_holdSince = in.now;
			++m_summary.frames;
			return {Act::Move, m_last, "flight: no samples, holding V"};
		}
		if (in.now - m_holdSince < kHold)
		{
			++m_summary.frames;
			return {Act::Move, m_last, ""};
		}
		return end(Act::Ground, m_last, "flight: no samples for 1 s, V put on the ground", in.now);
	}

	void Follower::applied(Vec3 to)
	{
		if (m_haveApplied)
			m_summary.maxStep = std::max(m_summary.maxStep, distance(m_last, to));
		m_last = to;
		m_haveApplied = true;
	}

	bool Follower::driving() const
	{
		return m_state != State::Idle;
	}

	double Follower::seconds_since_end(double now) const
	{
		if (driving())
			return 0.0;
		if (m_endedAt < 0)
			return 999.0;
		return std::max(now - m_endedAt, 0.0);
	}

	Summary Follower::summary() const
	{
		return m_summary;
	}

	Vec3 Follower::last() const
	{
		return m_last;
	}
}
