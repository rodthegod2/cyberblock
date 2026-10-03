// Input from Cyberpunk's window. F9 toggles the mashup and never reaches Cyberpunk (its QuickLoad), in every state.
// While Cyberblock forwards (Active on foot, no Cyberpunk menu, linked, window in front) the left/right/middle buttons and
// the wheel, 1-9, E, Q, T, X, /, F5 and the four bound keys (hack G, skills K, cyberware U, activate Y; plan
// 2026-10-03-retest2 section 1.8) go to Minecraft and Cyberpunk never sees them (the keys
// from both WM_INPUT and WM_KEYDOWN, the mouse from the game's GetRawInputData and its messages); Space and both Shifts
// go to Minecraft as jump and sprint but Cyberpunk keeps them; mouse 4/5 and every other key stay Cyberpunk's. While a
// Cyberpunk dialogue offers two or more choices, E, Q, 1-4 and the wheel stay Cyberpunk's. While a Minecraft screen is
// open every key but F9 is kept from Cyberpunk (V stands still), typed text and the editing keys go to the screen, the raw
// mouse drives Minecraft's cursor and Esc goes to Minecraft instead of opening Cyberpunk's pause menu.
// Audit: docs/notes/cp-mechanics-audit.md A.3.
#pragma once
#include <nlohmann/json_fwd.hpp>

namespace cb::input
{
	/// Call once per frame: hooks Cyberpunk's main window once it exists and sends a pending cursor move.
	void tick();
	/// The link's observer for `binds` (link thread): sets the keys of the hack, skills, cyberware and activate actions
	/// (keys::apply_binds) and logs them ("binds: hack G, skills K, cyberware U, activate Y").
	void on_minecraft_message(const nlohmann::json &message);
	/// Unhooks the window and the game's GetRawInputData (plugin unload).
	void shutdown();
}
