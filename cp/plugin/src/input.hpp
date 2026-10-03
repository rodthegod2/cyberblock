// Input from Cyberpunk's window: F9 toggles the mashup; while it is active (on foot, window in front) mouse buttons,
// wheel, 1-9, E, Q, T and / go to Minecraft, and while a Minecraft screen is open the raw mouse drives Minecraft's
// cursor and Esc goes to Minecraft instead of opening Cyberpunk's pause menu. Cyberpunk still sees everything else.
#pragma once

namespace cb::input
{
	/// Call once per frame: hooks Cyberpunk's main window once it exists and sends a pending cursor move.
	void tick();
	/// Unhooks the window (plugin unload).
	void shutdown();
}
