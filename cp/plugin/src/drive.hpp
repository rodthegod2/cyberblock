// The per-frame flight drive (plan 2026-10-03-retest2 task D5, contract sections 1.3 and 1.4): while Steve glides or
// is launched in Minecraft, V is teleported every rendered frame to the interpolated flight sample (snapshots.hpp
// Flight::render_pos), clipped against Night City, and put on the ground at the touchdown. cb::follow::Follower decides;
// this file talks to the game. Redscript only says whether V may be moved (Cyberblock_SetFlightAllowed, 10 Hz).
//
// Re-test 3 (plan task D3, F4): the drive owns V's yaw while it flies. It starts from V's facing and turns by the raw
// mouse X counts (input.cpp take_mouse_x; the game's own copy of them is stripped while driving in first person) at the
// rate Cyberpunk turns per count (keys::TurnRate, learnt by camera.cpp), and every teleport passes that yaw.
#pragma once
#include "cbmath.hpp"
#include <cstdint>
#include <optional>

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

	/// The Atlas survey tour (survey.hpp): while a hold is set, V is teleported to `cpFeet` (Cyberpunk space) every frame
	/// the flight gate allows (Cyberblock active, not in a vehicle, not held, flights allowed, no Cyberpunk menu), facing
	/// as at the hold's first frame, so V hangs in the air while Night City streams in. nullopt ends it. Any thread.
	void set_hold(std::optional<Vec3> cpFeet);
	/// After a tour: V is teleported once to `cpFeet` facing `yaw` (Cyberpunk degrees) on the next frame the gate allows,
	/// within 2 s (the scripts put V back themselves too; this one runs on the main thread after the last hold frame).
	/// Any thread.
	void request_return(Vec3 cpFeet, double yaw);
}
