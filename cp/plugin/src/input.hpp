// Input from Cyberpunk's window. F9 toggles the mashup and never reaches Cyberpunk (its QuickLoad), in every state.
// While Cyberblock forwards (Active on foot, no Cyberpunk menu, linked, window in front) the left/right/middle buttons and
// the wheel, 1-9, E, Q, T, X, /, F5 and the six bound keys (hack G, skills K, cyberware U, activate Y, arms L, camo O;
// plan 2026-10-03-retest3-polish section 1.7) go to Minecraft and Cyberpunk never sees them (the keys
// from both WM_INPUT and WM_KEYDOWN, the mouse from the game's GetRawInputData and its messages); Space and both Shifts
// go to Minecraft as jump and sprint but Cyberpunk keeps them; mouse 4/5 and every other key stay Cyberpunk's. While a
// Cyberpunk dialogue offers two or more choices, E, Q, 1-4 and the wheel stay Cyberpunk's. While a Minecraft screen is
// open every key but F9 is kept from Cyberpunk (V stands still), typed text and the editing keys go to the screen, the raw
// mouse drives Minecraft's cursor and Esc goes to Minecraft instead of opening Cyberpunk's pause menu.
// In the Afterlife hub (world::hub_in, megaplan 1.9 amendment 2026-10-04; V parked, Minecraft full-screen) W, A, S, D
// and Ctrl also go to Minecraft as `key` forward, left, back, right and sneak, Space and the Shifts (jump, sprint) and
// these five are kept from Cyberpunk, and the raw mouse motion goes as `look` deltas instead of turning Cyberpunk's
// camera. Logged as 'hub: input to Minecraft on|off'.
// Audit: docs/notes/cp-mechanics-audit.md A.3.
#pragma once
#include <cstdint>
#include <nlohmann/json_fwd.hpp>

namespace cb::input
{
	/// Call once per frame: hooks Cyberpunk's main window once it exists and sends a pending cursor move.
	void tick();
	/// The link's observer for `binds` (link thread): sets the keys of the six actions (keys::apply_binds) and logs them
	/// ("binds: hack G, skills K, cyberware U, activate Y, arms L, camo O").
	void on_minecraft_message(const nlohmann::json &message);
	/// The raw mouse X counts that arrived while forwarding with no Minecraft screen open since the last call (re-test 3
	/// task D3: the drive turns V by them during a glide). Game thread, once per frame.
	int64_t take_mouse_x();
	/// Unhooks the window and the game's GetRawInputData (plugin unload).
	void shutdown();
}
