// The per-frame flight follower (plan 2026-10-03-retest2 task D4): a pure state machine that decides, once per rendered
// frame, what the DLL drive (drive.cpp) does to V while Steve glides or is launched in Minecraft. It never touches the
// game: the drive feeds it a frame (In), carries out the answer (Out) and reports where V really went (applied).
//
// Re-test 2 R1: the script loop that moved V ran about once per second (Flight.reds' DelayCallbackNextFrame never fired
// again from inside its callback in 2.31), so V jumped ~17 m at a time and landed from a 1 s old position. The drive runs
// in RED4ext's Running-state OnUpdate instead, every frame, and this is its brain.
#pragma once
#include "cbmath.hpp"
#include <optional>
#include <string>

namespace cb::follow
{
	/// The point `margin` metres short of a hit `hitDistance` metres along from -> to; `from` itself when the hit is
	/// closer than `margin` (the drive's clip, task D5).
	Vec3 stop_short(Vec3 from, Vec3 to, double hitDistance, double margin);

	/// Where a landing puts V's feet, given the highest Night City ground found below the spot (task D5): the ground
	/// when it is above the spot (the spot sank into it) or the spot is more than 1 m above it (V would fall), else the
	/// spot itself. No ground found: the spot.
	Vec3 snap_landing(Vec3 spot, std::optional<double> groundZ);

	/// What the drive does this frame.
	enum class Act
	{
		None,   // nothing
		Move,   // teleport V to `to` (clipped against Night City) and keep following
		Land,   // the flight ended: put V on the ground at `to` (the landing spot), then stop
		Ground, // Minecraft went silent for 1 s: put V on the ground below `to` (where V was last put), then stop
		Stop,   // stop following and leave V where it is: no teleport
	};

	/// One frame, as the drive sees it.
	struct In
	{
		bool gate = false;     // V may be moved: Cyberblock active, not in a vehicle, not held, flightAllowed, linked, V found
		bool menu = false;     // a Cyberpunk menu is open: do nothing, freeze the timers
		bool active = false;   // Steve flies (Flight::active)
		bool timedOut = false; // the flight ended because its samples stopped (Flight::timed_out)
		bool launch = false;   // the flight is a launch, not a glide (Flight::launch)
		Vec3 target{};         // Flight::render_pos: where Steve's feet are for this frame, or the landing spot
		Vec3 feet{};           // V's feet now
		double now = 0;        // seconds on a steady clock
	};

	struct Out
	{
		Act act = Act::None;
		Vec3 to{};
		std::string log; // a line for Cyberblock.log, "" for none
	};

	/// What one flight looked like, for the drive's summary line.
	struct Summary
	{
		int frames = 0;      // frames with a Move
		double maxStep = 0;  // longest distance between two consecutive applied() positions, m
	};

	/// Idle -> Following on a start; Following <-> Holding on a timeout and when samples come back; back to Idle on
	/// Land, Ground and Stop. Rules:
	/// - Start: Idle, gate open, flight active, no menu -> Following, Move(target), "flight: start (glide|launch)".
	/// - The gate closes while Following or Holding -> Stop, no teleport, "flight: stopped, V is held, ...".
	/// - A menu -> None, and every timer (start, hold, end) moves forward by the frame time: frozen.
	/// - Active -> Move(target); from Holding also "flight: samples back".
	/// - Landing (not active, not timed out): Stop "flight: over, V stays where it is" when the flight lasted under
	///   kQuickLand or V's feet are within kNearLand of the spot; else Land(target) "flight: landed (...) after X s".
	/// - Timeout (not active, timed out): Move(last applied position) for up to kHold ("flight: no samples, holding V"),
	///   then Ground(last applied position).
	class Follower
	{
	public:
		static constexpr double kHold = 1.0;      // s V is held in place while Minecraft is silent
		static constexpr double kQuickLand = 0.3; // s: a flight shorter than this leaves V where it is
		static constexpr double kNearLand = 1.5;  // m: V this close to the landing spot stays where it is
		static constexpr double kClipMargin = 0.3; // m the drive stops V short of Night City geometry
		static constexpr double kBodyLift = 0.9;   // m above the feet the drive casts its clip rays (the body's middle)

		Out step(const In &in);
		/// The drive moved V to `to` (after clipping): held there on a timeout, and the start of the next step.
		void applied(Vec3 to);
		/// Following or Holding: the drive moves V this flight (cam.fly).
		bool driving() const;
		/// 0 while driving, the seconds since the last flight ended (menus excluded), 999 before any flight.
		double seconds_since_end(double now) const;
		/// The current or last flight's frames and largest step.
		Summary summary() const;
		/// Where V was last put (V's feet at the start, until the first applied()): where the next clip starts.
		Vec3 last() const;

	private:
		enum class State
		{
			Idle,
			Following,
			Holding,
		};

		Out end(Act act, Vec3 to, std::string log, double now);

		State m_state = State::Idle;
		double m_start = 0;       // when this flight started (moved forward by menus)
		double m_holdSince = 0;   // when the hold started (moved forward by menus)
		double m_endedAt = -1;    // when the last flight ended (moved forward by menus); < 0: none yet
		double m_lastNow = -1;    // the previous step's time; < 0: none yet
		bool m_launch = false;    // the flight's kind, for the logs
		Vec3 m_last{};            // where V was last put
		bool m_haveApplied = false;
		Summary m_summary;
	};
}
