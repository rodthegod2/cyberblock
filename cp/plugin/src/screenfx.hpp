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
	/// `easeIn`, 1 while it runs, then a smoothstep down over `easeOut` from the level it had at the end. 0 before the start.
	double envelope(double since, double duration, double easeIn = kEaseIn, double easeOut = kEaseOut);

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

	// ---- Phase 8 (megaplan 2026-10-04 sections 1.8 and 1.9, task 8-D1): the Reboot Optics glitch ----------------------
	// A `glitch {seconds}` from the server (another player's Reboot Optics on this linked host) scrambles the picture; the
	// compositor turns it into the effect's `cb_glitch` every frame: x the intensity (kGlitchEaseIn ease-in, hold,
	// kGlitchEaseOut ease-out; 0 while a Cyberpunk menu is open), y the seconds since the start, z the effect's length in
	// seconds, w 0. The Glitch pass of Cyberblock.fx draws it over everything, Minecraft's HUD included.

	inline constexpr double kGlitchEaseIn = 0.08;     // s: the hack lands at once
	inline constexpr double kGlitchEaseOut = 0.5;     // s, after the end
	inline constexpr double kMaxGlitchSeconds = 10.0; // `glitch.seconds` is 0..10

	/// One glitch at a time; overlapping hacks extend it. Not thread-safe (the owner locks it).
	class Glitch
	{
	public:
		/// Glitches for `seconds` (clamped to 10) from `now`. While one runs (or eases out) it ends at the later of its own
		/// end and now + seconds, without a second ease-in. seconds <= 0 (or not finite) ends it now: it eases out.
		void start(double seconds, double now);
		/// Gone at once (Cyberblock off, a new Minecraft, a link drop, leaving the host role).
		void clear() { m_on = false; }
		/// cb_glitch at `now`; all zeros when nothing runs, x 0 while `menu` (a Cyberpunk menu is open).
		std::array<float, 4> uniform(double now, bool menu) const;
		bool running(double now) const;

	private:
		bool m_on = false;
		double m_start = 0.0;
		double m_duration = 0.0;
	};
}
