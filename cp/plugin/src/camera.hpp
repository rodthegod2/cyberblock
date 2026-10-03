// Publishes Cyberpunk's camera every frame while the mashup is active: `cam` to Minecraft, pose and clip planes to
// the compositor. All conversions go through cbmath.hpp.
#pragma once
#include <cstdint>

namespace cb::camera
{
	/// Whether GetActiveCameraFOV is horizontal (converted to vertical with the camera's aspect) or already vertical.
	/// Cube test (F2, 2026-10-02): it is vertical. Read as horizontal (68.24 -> 41.7 vertical), Minecraft rendered
	/// about 1.8x zoomed in and blocks looked too big.
	inline constexpr bool fovIsHorizontal = false;

	/// Call once per frame from the Running game state (the game's main thread). `mouseX`: the raw mouse X counts of this
	/// frame (input::take_mouse_x), which teach the turn rate (re-test 3 task D3).
	void publish(int64_t mouseX);
	/// How many degrees Cyberpunk turns the camera per raw mouse count (keys::TurnRate; 0.15 until learnt). Main thread.
	double degrees_per_count();
}
