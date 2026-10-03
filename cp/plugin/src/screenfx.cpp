#include "screenfx.hpp"
#include <algorithm>
#include <cmath>

namespace
{
	double smoothstep(double edge, double x)
	{
		const double t = std::clamp(x / edge, 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}
}

namespace cb::screenfx
{
	double envelope(double since, double duration)
	{
		if (!(since >= 0.0) || !std::isfinite(duration))
			return 0.0;
		if (since <= duration)
			return smoothstep(kEaseIn, since);
		// out from wherever the ease-in had got to when the effect ended
		return smoothstep(kEaseIn, std::max(duration, 0.0)) * (1.0 - smoothstep(kEaseOut, since - duration));
	}

	void SlowMo::start(Kind kind, double seconds, double now)
	{
		if (!std::isfinite(seconds) || seconds <= 0.0)
		{
			stop(now);
			return;
		}
		m_on = true;
		m_kind = kind;
		m_start = now;
		m_duration = std::min(seconds, kMaxSeconds);
	}

	void SlowMo::stop(double now)
	{
		if (m_on)
			m_duration = std::clamp(now - m_start, 0.0, m_duration);
	}

	bool SlowMo::running(double now) const
	{
		return m_on && now - m_start < m_duration + kEaseOut;
	}

	std::array<float, 4> SlowMo::uniform(double now, bool menu) const
	{
		if (!running(now))
			return {0.0f, 0.0f, 0.0f, 0.0f};
		const double since = now - m_start;
		const double x = menu ? 0.0 : envelope(since, m_duration);
		return {float(x), m_kind == Kind::Kerenzikov ? 1.0f : 0.0f, float(std::max(since, 0.0)), 0.0f};
	}
}
