// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
// ReShade add-on half of Cyberblock.dll: uploads Minecraft's latest frame (shared memory "Local\CyberblockFrame") into
// textures that Cyberblock.fx composites into Cyberpunk's picture against Cyberpunk's depth buffer.
#pragma once

namespace compositor
{
	/// Registers with ReShade once it is loaded; call until it returns true.
	bool try_register(void *module);
	void unregister(void *module);
	/// Whether to composite this frame (the scripts turn it off when Cyberblock is off or Minecraft is lost).
	void set_active(bool active);
	/// Cyberpunk's camera clip planes, to turn its depth buffer into metres.
	void set_host_planes(float near_clip, float far_clip);
	/// Cyberpunk's current camera in Minecraft's convention (rotation in degrees, vertical fov, position in Minecraft
	/// coordinates): Minecraft's frame is re-projected (with its depth) from the pose it was rendered with to this
	/// one, hiding the link's latency on camera turns and moves.
	void set_host_pose(float yaw, float pitch, float roll, float fov, double x, double y, double z);
	/// How many poses back the presented picture is (1: the caller reads the camera of the frame being prepared).
	void set_pose_lag(int frames);
	/// Minecraft's picture moves with the camera (a chase cam framing Steve): composite it as rendered, without
	/// re-projecting it to Cyberpunk's newer pose, so Steve stays exactly where the camera framed him.
	void set_camera_locked(bool locked);
	/// A scene's look: the effect's "Match Night City lighting", depth bias and slope bias (negative: the preset's own values).
	void set_look(float light_match, float depth_bias, float slope_bias);
	/// The camera shake, applied to the finished picture (x, y: fraction of the screen height; roll: radians), and the
	/// nether portal's warp (0..1).
	void set_screen_fx(float shake_x, float shake_y, float shake_roll, float portal_warp);
	/// Cyberpunk's backbuffer size as ReShade sees it (0 until the first frame).
	void backbuffer_size(int &width, int &height);
	/// A Cyberpunk menu is open and passed through (no Minecraft screen replaced it; re-test 3 plan section 1.10): the
	/// effect's `cb_menu` makes the Composite pass skip Minecraft's world, and the Final pass still adds the overlay (now
	/// only Minecraft's frame around the menu). Every frame from the game thread; a change is logged.
	void set_menu_mode(bool menu);
}
