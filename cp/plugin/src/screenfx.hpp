// The Sandevistan / Kerenzikov screen effect's timing (re-test 3 plan task D7, section 1.10, F10). Cyberblock_SetSlowMoFx
// starts or ends it; the compositor turns it into the effect's `cb_slowmo` every frame: x the intensity (0.15 s ease-in,
// hold, 0.4 s ease-out after the end; 0 while a Cyberpunk menu is open), y the kind (0 Sandevistan, 1 Kerenzikov), z
// the seconds since the start, w 0. Pure: the compositor passes the clock in.
#pragma once
#include <array>

namespace cb::screenfx
{
	inline constexpr double kEaseIn = 0.15;     // s
	inline constexpr double kEaseOut = 0.4;     // s, after the end
	inline constexpr double kMaxSeconds = 30.0; // longest effect

	enum class Kind
	{
		Sandevistan = 0,
		Kerenzikov = 1,
	};

	/// 0..1, `since` seconds after the start of an effect that ends `duration` seconds after it: a smoothstep up over
	/// kEaseIn, 1 while it runs, then a smoothstep down over kEaseOut from the level it had at the end. 0 before the start.
	double envelope(double since, double duration);

	/// One effect at a time; a new start replaces the running one. Not thread-safe (the compositor locks it).
	class SlowMo
	{
	public:
		/// Starts `kind` for `seconds` (clamped to 0..30) at `now`; seconds <= 0 (or not finite) ends it as stop() does.
		void start(Kind kind, double seconds, double now);
		/// Ends the running effect now: it eases out over kEaseOut.
		void stop(double now);
		/// Gone at once (Cyberblock off).
		void clear() { m_on = false; }
		/// The effect's cb_slowmo at `now`; all zeros when nothing runs or `menu` (a Cyberpunk menu is open).
		std::array<float, 4> uniform(double now, bool menu) const;
		/// Something runs or is still easing out at `now`.
		bool running(double now) const;

	private:
		bool m_on = false;
		Kind m_kind = Kind::Sandevistan;
		double m_start = 0.0;
		double m_duration = 0.0;
	};
}
