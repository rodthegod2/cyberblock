// The per-frame flight drive (plan 2026-10-03-retest2 task D5, contract sections 1.3 and 1.4): while Steve glides or
// is launched in Minecraft, V is teleported every rendered frame to the interpolated flight sample (snapshots.hpp
// Flight::render_pos), clipped against Night City, and put on the ground at the touchdown. cb::follow::Follower decides;
// this file talks to the game. Redscript only says whether V may be moved (Cyberblock_SetFlightAllowed, 10 Hz).
//
// Re-test 3 (plan task D3, F4): the drive owns V's yaw while it flies. It starts from V's facing and turns by the raw
// mouse X counts (input.cpp take_mouse_x; the game's own copy of them is stripped while driving in first person) at the
// rate Cyberpunk turns per count (keys::TurnRate, learnt by camera.cpp), and every teleport passes that yaw.
#pragma once
#include <cstdint>

namespace cb::drive
{
	/// Main thread, every frame, from main.cpp's Running-state update before camera::publish (so `cam.fly` is true on
	/// the first frame V moves). Keeps ticking while the game is paused; behind a Cyberpunk menu it does nothing and its
	/// timers stand still. `mouseX`: raw mouse X counts since the last frame; `degreesPerCount`: how far Cyberpunk turns
	/// per count.
	void tick(int64_t mouseX, double degreesPerCount);
	/// The camera's Minecraft pitch this frame (camera.cpp), for the flight summary's pitch range; ignored unless driving.
	/// Main thread.
	void note_pitch(double mcPitch);
	/// The drive moves V this flight (the same value as cam.fly). Any thread.
	bool driving();
	/// 0 while driving, the seconds since the last flight ended, 999 before any. Any thread.
	float seconds_since_end();
}
