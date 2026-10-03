// Input from Cyberpunk's window: F9 toggles the mashup. While it is active (on foot, no Cyberpunk menu, window in front)
// the mouse buttons and wheel, 1-9, E, Q, T, /, F5 and the hack and skills keys go to Minecraft and Cyberpunk never sees
// them (the keys from both WM_INPUT and WM_KEYDOWN, the mouse from the game's GetRawInputData and its messages), Space
// and Left Shift go to Minecraft as jump and sprint but Cyberpunk keeps them, and while a Minecraft screen is open the raw
// mouse drives Minecraft's cursor and Esc goes to Minecraft instead of opening Cyberpunk's pause menu. Cyberpunk still sees
// everything else. Audit: docs/notes/cp-mechanics-audit.md A.3.
#pragma once
#include <nlohmann/json_fwd.hpp>

namespace cb::input
{
	/// Call once per frame: hooks Cyberpunk's main window once it exists and sends a pending cursor move.
	void tick();
	/// The link's observer for `binds` (link thread): sets the keys of the hack and skills actions.
	void on_minecraft_message(const nlohmann::json &message);
	/// Unhooks the window and the game's GetRawInputData (plugin unload).
	void shutdown();
}
