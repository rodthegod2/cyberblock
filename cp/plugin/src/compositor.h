// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
// ReShade add-on half of Cyberblock.dll: uploads Minecraft's latest frame (shared memory "Local\CyberblockFrame") into
// textures that Cyberblock.fx composites into Cyberpunk's picture against Cyberpunk's depth buffer.
#pragma once
#include <algorithm>
#include <cmath>
#include "view.hpp"

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
	/// one, hiding the link's latency on camera turns and moves. `body`: Steve's body for this camera (the cam's frame
	/// number, V's feet and the capsule Steve is drawn in; no capsule in first person): what lies in the capsule of the
	/// uploaded frame's own camera (found by the frame number Minecraft echoes) is moved with V, not left behind.
	void set_host_pose(float yaw, float pitch, float roll, float fov, double x, double y, double z, const cb::view::Body &body = {});
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
	/// The size Minecraft is asked to render and export at (the link's `view`): the backbuffer's, times Performance mode's
	/// `prefs.scale` (megaplan 9-F1; 0.5..1, world::view_prefs). Cyberblock.fx samples Minecraft's layers by texture coordinate, so
	/// a smaller picture is upscaled to the screen (bilinear, plus its UpscaleSharpen). Logs 'compositor: Minecraft renders at
	/// <scale> of the screen (<w>x<h> for <bw>x<bh>)' when the size changes. Game thread, every frame.
	void frame_size(int &width, int &height);
	/// Each side times `scale` (clamped into 0.5..1; not a number: 1), rounded half up, at least 1; 0 stays 0 (no backbuffer yet).
	/// tools/loadhost.py scaled_view is the same rule. Pure.
	inline int scaled_side(int side, double scale)
	{
		if (side <= 0)
			return 0;
		const double s = std::isnan(scale) ? 1.0 : std::clamp(scale, 0.5, 1.0);
		return std::max(1, static_cast<int>(std::floor(static_cast<double>(side) * s + 0.5)));
	}
	/// A Cyberpunk menu is open and passed through (no Minecraft screen replaced it; re-test 3 plan section 1.10): the
	/// effect's `cb_menu` makes the Composite pass skip Minecraft's world, and the Final pass still adds the overlay (now
	/// only Minecraft's frame around the menu). Every frame from the game thread; a change is logged.
	void set_menu_mode(bool menu);
	/// The player is in the Afterlife hub (megaplan 7-D1, `hub in:true`): the effect's `cb_full` makes the Composite pass
	/// show Minecraft's own picture on every pixel (as rendered, no re-projection and no depth blending against Night
	/// City), the Final pass adds only the overlay, and a stalled Minecraft's last frame stays up for a couple of seconds
	/// instead of flashing Night City (the way a loading cover does). A Cyberpunk menu passed through still shows. Every
	/// frame from the game thread; a change is logged.
	void set_full_screen(bool full);
	/// The Sandevistan (kind 0) or Kerenzikov (kind 1) screen effect starts now for `seconds` (re-test 3 plan section
	/// 1.10); seconds <= 0 ends it (it eases out over 0.4 s). set_active(false) clears it at once. Any thread.
	void set_slowmo(int kind, float seconds);
}
