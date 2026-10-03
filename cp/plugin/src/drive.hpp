// The per-frame flight drive (plan 2026-10-03-retest2 task D5, contract sections 1.3 and 1.4): while Steve glides or
// is launched in Minecraft, V is teleported every rendered frame to the interpolated flight sample (snapshots.hpp
// Flight::render_pos), clipped against Night City, and put on the ground at the touchdown. cb::follow::Follower decides;
// this file talks to the game. Redscript only says whether V may be moved (Cyberblock_SetFlightAllowed, 10 Hz).
#pragma once

namespace cb::drive
{
	/// Main thread, every frame, from main.cpp's Running-state update before camera::publish (so `cam.fly` is true on
	/// the first frame V moves). Keeps ticking while the game is paused; behind a Cyberpunk menu it does nothing and its
	/// timers stand still.
	void tick();
	/// The drive moves V this flight (the same value as cam.fly). Any thread.
	bool driving();
	/// 0 while driving, the seconds since the last flight ended, 999 before any. Any thread.
	float seconds_since_end();
}
