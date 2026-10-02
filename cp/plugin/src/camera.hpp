// Publishes Cyberpunk's camera every frame while the mashup is active: `cam` to Minecraft, pose and clip planes to
// the compositor. All conversions go through cbmath.hpp.
#pragma once

namespace cb::camera
{
	/// Whether GetActiveCameraFOV is horizontal (converted to vertical with the camera's aspect) or already vertical.
	/// Decided by the cube test (task F2).
	inline constexpr bool fovIsHorizontal = true;

	/// Call once per frame from the Running game state (the game's main thread).
	void publish();
}
