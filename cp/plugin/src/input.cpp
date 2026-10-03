#include "input.hpp"
#include "drive.hpp"
#include "iat.hpp"
#include "keys.hpp"
#include "link.hpp"
#include "log.hpp"
#include "natives.hpp"
#include "view.hpp"
#include "world.hpp"
#include <windows.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cwchar>
#include <map>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace
{
	// keys.hpp spells the Windows values out so it needs no Windows headers; make sure they are the real ones
	static_assert(cb::keys::kMouseLeftDown == RI_MOUSE_LEFT_BUTTON_DOWN && cb::keys::kMouseRightDown == RI_MOUSE_RIGHT_BUTTON_DOWN &&
				  cb::keys::kMouseMiddleDown == RI_MOUSE_MIDDLE_BUTTON_DOWN && cb::keys::kMouseButton4Down == RI_MOUSE_BUTTON_4_DOWN &&
				  cb::keys::kMouseButton5Down == RI_MOUSE_BUTTON_5_DOWN && cb::keys::kMouseWheel == RI_MOUSE_WHEEL &&
				  cb::keys::kMouseHWheel == RI_MOUSE_HWHEEL);
	static_assert(cb::keys::kEscape == VK_ESCAPE && cb::keys::kF5 == VK_F5 && cb::keys::kF9 == VK_F9 && cb::keys::kOem2 == VK_OEM_2 &&
				  cb::keys::kSpace == VK_SPACE && cb::keys::kShift == VK_SHIFT && cb::keys::kControl == VK_CONTROL &&
				  cb::keys::kLeftShift == VK_LSHIFT && cb::keys::kRightShift == VK_RSHIFT && cb::keys::kLeftControl == VK_LCONTROL &&
				  cb::keys::kRightControl == VK_RCONTROL);

	constexpr double kCursorCountsPerWindow = 800.0; // raw mouse counts to cross the whole window
	constexpr auto kCursorInterval = std::chrono::microseconds(1'000'000 / 120);
	constexpr char kEscape[] = "key:escape";

	std::atomic<HWND> g_window{nullptr};     // set once found (hooked or not), so the search stops
	std::atomic<WNDPROC> g_original{nullptr}; // the window's own procedure, null unless hooked

	// Messages still owed to Minecraft: what was sent down -> the matching "up", so every press is released even when
	// forwarding stops in between (vehicle, focus loss, mashup off). Window thread only.
	std::map<std::string, nlohmann::json> g_held;
	int g_wheel = 0; // wheel delta not yet worth a notch. Window thread only.

	// The keys bound to the four actions: written on the link thread (`binds`), read on the window thread.
	std::atomic<uint32_t> g_bindHack{cb::keys::kG};
	std::atomic<uint32_t> g_bindSkills{cb::keys::kK};
	std::atomic<uint32_t> g_bindCyberware{cb::keys::kU};
	std::atomic<uint32_t> g_bindActivate{cb::keys::kY};
	std::atomic<uint32_t> g_bindArms{cb::keys::kL};
	std::atomic<uint32_t> g_bindCamo{cb::keys::kO};
	// One gate per input path, since each of WM_INPUT and WM_KEYDOWN sees every key event. Window thread only.
	cb::keys::KeyGate g_rawGate;
	cb::keys::KeyGate g_messageGate;
	cb::keys::CharJoiner g_chars; // WM_CHAR surrogate pairs. Window thread only.

	// Minecraft's cursor while one of its screens is open, in 0..1 of the window: moved on the window thread, flushed
	// from the game thread too.
	struct Cursor
	{
		double x = 0.5, y = 0.5;
		bool dirty = false;
		bool screenWasOpen = false;
		std::chrono::steady_clock::time_point sentAt;
	};
	std::mutex g_cursorLock;
	Cursor g_cursor;

	// Raw mouse deltas for the camera-only flight fallback (Cyberblock_SetLookCapture), summed on the window thread and
	// flushed from there and from the game thread.
	std::mutex g_lookLock;
	cb::keys::LookAccumulator g_look;

	// Raw mouse X counts since the game thread last took them (take_mouse_x), summed on the window thread.
	std::atomic<int64_t> g_mouseX{0};

	// USER32!GetRawInputData as the game's import table had it before the hook (null while not hooked). iat::patch sets it
	// before the hook goes live; it is never cleared, because a game thread may be inside the hook while we unload.
	using GetRawInputDataFn = UINT(WINAPI *)(HRAWINPUT, UINT, LPVOID, PUINT, UINT);
	void *g_realGetRawInputData = nullptr;
	std::atomic<bool> g_mouseHooked{false};

	cb::keys::Binds binds()
	{
		return {g_bindHack, g_bindSkills, g_bindCyberware, g_bindActivate, g_bindArms, g_bindCamo};
	}

	/// A bound key as a player reads it: the letter or digit, F1..F24, else the VK code.
	std::string vk_name(uint32_t vk)
	{
		if ((vk >= 'A' && vk <= 'Z') || (vk >= '0' && vk <= '9'))
			return std::string(1, char(vk));
		if (vk >= VK_F1 && vk <= VK_F24)
			return "F" + std::to_string(vk - VK_F1 + 1);
		char hex[8];
		std::snprintf(hex, sizeof(hex), "0x%02X", unsigned(vk));
		return hex;
	}

	/// Forward to Minecraft right now: mashup on, on foot, no Cyberpunk menu, linked, Cyberpunk's window in front.
	bool forwarding()
	{
		return cb::natives::active && !cb::natives::inVehicle && !cb::natives::menuOpen && cb::link().linked() &&
			   GetForegroundWindow() == g_window;
	}

	bool screen_open()
	{
		return forwarding() && cb::link().screen_open();
	}

	/// A Cyberpunk choice list with two or more choices is shown and no Minecraft screen covers it: E, Q, 1-4 and the wheel
	/// are Cyberpunk's.
	bool choices_shown()
	{
		return cb::natives::choicesOpen && !cb::link().screen_open();
	}

	cb::keys::Context context()
	{
		return {.screenOpen = screen_open(), .choices = choices_shown()};
	}

	/// Shift and Ctrl as the key state of the message being handled has them, in SDL bits.
	int mods()
	{
		return cb::keys::sdl_mods(GetKeyState(VK_SHIFT) < 0, GetKeyState(VK_CONTROL) < 0);
	}

	/// Sends `message` with the modifiers held now.
	void send_with_mods(nlohmann::json message)
	{
		message["mods"] = mods();
		cb::link().send(message);
	}

	void release(const std::string &id)
	{
		const auto it = g_held.find(id);
		if (it == g_held.end())
			return;
		send_with_mods(std::move(it->second));
		g_held.erase(it);
	}

	/// Sends `down` (a `key` or `click`, with the modifiers held now) and keeps `up` until the release.
	void press(const std::string &id, nlohmann::json down, nlohmann::json up)
	{
		if (g_held.contains(id))
			return; // already down (key repeat)
		send_with_mods(std::move(down));
		g_held[id] = std::move(up);
	}

	/// Releases everything but a swallowed Esc, whose own key-up must still be kept from Cyberpunk.
	void release_all_but_escape()
	{
		for (auto it = g_held.begin(); it != g_held.end();)
		{
			if (it->first == kEscape)
			{
				++it;
				continue;
			}
			send_with_mods(std::move(it->second));
			it = g_held.erase(it);
		}
	}

	/// A `key` message; press() and release() add its `mods`.
	nlohmann::json key(const char *name, bool down)
	{
		return {{"t", "key"}, {"k", name}, {"down", down}};
	}

	/// b: 0 left, 1 right, 2 middle. A screen gets clicks, the world gets attack / use / pick. A press goes to Minecraft
	/// while forwarding, and then Cyberpunk must not see it: returns whether it was taken. A release is always passed on
	/// (to both), so a button held from before forwarding started is released in Cyberpunk too.
	bool button(int b, bool down)
	{
		static const char *const kKeys[] = {"attack", "use", "pick"};
		const std::string id = "button:" + std::to_string(b);
		if (!down)
		{
			release(id);
			return false;
		}
		if (!forwarding())
			return false;
		if (cb::link().screen_open())
			press(id, {{"t", "click"}, {"b", b}, {"down", true}}, {{"t", "click"}, {"b", b}, {"down", false}});
		else
			press(id, key(kKeys[b], true), key(kKeys[b], false));
		return true;
	}

	/// Locked: sends the cursor if it moved and the last send is at least 1/120 s old.
	void flush_cursor(Cursor &cursor)
	{
		const auto now = std::chrono::steady_clock::now();
		if (!cursor.dirty || now - cursor.sentAt < kCursorInterval)
			return;
		cb::link().send({{"t", "cursor"}, {"x", cursor.x}, {"y", cursor.y}});
		cursor.dirty = false;
		cursor.sentAt = now;
	}

	/// Locked: sends the summed look deltas if there are any and the last send is at least 1/120 s old.
	void flush_look()
	{
		if (const auto turn = g_look.take(std::chrono::steady_clock::now()))
			cb::link().send(cb::world::make_look((*turn)[0], (*turn)[1]));
	}

	void on_raw_mouse(const RAWMOUSE &mouse)
	{
		if ((mouse.usFlags & MOUSE_MOVE_ABSOLUTE) != 0)
			return;
		// the drive's owned yaw (re-test 3 task D3): every relative X count while V is Cyberpunk's to turn
		if (forwarding() && !cb::link().screen_open())
			g_mouseX += mouse.lLastX;
		if (screen_open())
		{
			std::lock_guard<std::mutex> lock(g_cursorLock);
			g_cursor.x = std::clamp(g_cursor.x + mouse.lLastX / kCursorCountsPerWindow, 0.0, 1.0);
			g_cursor.y = std::clamp(g_cursor.y + mouse.lLastY / kCursorCountsPerWindow, 0.0, 1.0);
			g_cursor.dirty = true;
			flush_cursor(g_cursor);
		}
		else if (cb::natives::lookCapture && forwarding())
		{
			std::lock_guard<std::mutex> lock(g_lookLock);
			g_look.add(mouse.lLastX, mouse.lLastY);
			flush_look();
		}
	}

	/// Whether Cyberpunk must not see this raw keyboard packet: a key of the swallow set while forwarding (with its repeats
	/// and its up; every key while a Minecraft screen is open), F9, or an Esc that Minecraft takes.
	bool swallow_raw_key(const RAWKEYBOARD &keyboard)
	{
		const uint32_t vk = keyboard.VKey;
		const bool down = (keyboard.Flags & RI_KEY_BREAK) == 0;
		if (vk == VK_ESCAPE)
			return screen_open() || g_held.contains(kEscape);
		if (vk == VK_F9)
			return true; // Cyberblock's toggle: Cyberpunk's own F9 (QuickLoad) never fires while its window is in front
		const bool swallowable = forwarding() && cb::keys::is_swallowed(vk, binds(), context());
		return (down ? g_rawGate.down(vk, swallowable) : g_rawGate.up(vk)) == cb::keys::KeyGate::Verdict::Swallow;
	}

	/// Raw input on the window: cursor and look motion from the mouse, and whether this packet is a key to keep from
	/// Cyberpunk. The packet is read with USER32's own GetRawInputData, which our hook of the game's import table does not
	/// touch: this sees the mouse buttons and wheel unmodified.
	bool on_raw_input(LPARAM lParam)
	{
		RAWINPUT raw;
		UINT size = sizeof(raw);
		if (GetRawInputData(reinterpret_cast<HRAWINPUT>(lParam), RID_INPUT, &raw, &size, sizeof(RAWINPUTHEADER)) == UINT(-1))
			return false; // not a mouse or keyboard packet (bigger HID data): not ours
		if (raw.header.dwType == RIM_TYPEMOUSE)
			on_raw_mouse(raw.data.mouse);
		return raw.header.dwType == RIM_TYPEKEYBOARD && swallow_raw_key(raw.data.keyboard);
	}

	/// The `key` name Minecraft knows `vk` by, or null when it is not forwarded. Either Shift is sprint (a double tap is
	/// Steve's Sandevistan); Cyberpunk keeps both.
	const char *key_name(WPARAM vk)
	{
		switch (vk)
		{
		case 'E': return "inventory";
		case 'Q': return "drop";
		case 'T': return "chat";
		case 'X': return "swap"; // main hand <-> offhand (Minecraft's F, which is Cyberpunk's interact key)
		case VK_OEM_2: return "command"; // the "/?" key on US layouts
		case VK_F5: return "perspective";
		case VK_SPACE: return "jump";
		case VK_SHIFT:
		case VK_LSHIFT:
		case VK_RSHIFT: return "sprint";
		}
		if (vk == g_bindHack)
			return "hack";
		if (vk == g_bindSkills)
			return "skills";
		if (vk == g_bindCyberware)
			return "cyberware";
		if (vk == g_bindActivate)
			return "activate";
		if (vk == g_bindArms)
			return "arms";
		if (vk == g_bindCamo)
			return "camo";
		return nullptr;
	}

	/// Legacy key messages. Returns true for a key that Cyberpunk must not see: a key of the swallow set while forwarding
	/// (with its repeats and its up; every key while a Minecraft screen is open), F9, or an Esc that Minecraft takes.
	bool on_key(WPARAM vk, LPARAM lParam, bool down)
	{
		const bool repeat = down && (lParam & (1 << 30)) != 0;
		if (vk == VK_F9)
		{
			// the toggle, in every state; Cyberpunk never sees F9 (its default QuickLoad would load a save)
			if (down && !repeat)
				cb::natives::togglePressed = true;
			return true;
		}
		if (vk == VK_ESCAPE)
		{
			if (down && !repeat && screen_open())
				press(kEscape, key("escape", true), key("escape", false));
			const bool ours = g_held.contains(kEscape);
			if (!down)
				release(kEscape);
			return ours;
		}

		const auto code = static_cast<uint32_t>(vk);
		const cb::keys::Context now = context();
		const bool swallowable = forwarding() && cb::keys::is_swallowed(code, binds(), now);
		const bool swallow =
			(down ? g_messageGate.down(code, swallowable) : g_messageGate.up(code)) == cb::keys::KeyGate::Verdict::Swallow;

		// a Cyberpunk choice list keeps E, Q and 1-4 (a release still goes out for a press Minecraft got before it showed)
		const bool forward = forwarding() && !(now.choices && cb::keys::is_choice_key(code));
		if (const char *name = key_name(vk))
		{
			const std::string id = std::string("key:") + name;
			if (!down)
				release(id);
			else if (forward)
				press(id, key(name, true), key(name, false));
		}
		else if (vk >= '1' && vk <= '9' && down && !repeat && forward)
			cb::link().send({{"t", "slot"}, {"n", int(vk - '1')}});

		// the editing keys of a Minecraft screen (text fields, recipe book, sign edit), with their auto-repeat
		if (down && now.screenOpen)
			if (const auto scancode = cb::keys::editing_scancode(code))
				send_with_mods({{"t", "skey"}, {"sc", *scancode}});
		return swallow;
	}

	/// Typed text (WM_CHAR, which the game's own TranslateMessage makes from the key-down even when we swallow it): goes to
	/// an open Minecraft screen as `char` and Cyberpunk does not see it. Returns whether it was taken.
	bool on_char(WPARAM unit)
	{
		if (!screen_open())
		{
			g_chars.reset();
			return false;
		}
		static bool logged = false;
		if (!std::exchange(logged, true))
			cb::log::info("typing reaches Minecraft's screens (first WM_CHAR while a screen is open)");
		if (const auto codepoint = g_chars.feed(static_cast<uint16_t>(unit)))
			cb::link().send({{"t", "char"}, {"c", uint32_t(*codepoint)}});
		return true;
	}

	/// The wheel while forwarding: notches go to Minecraft and Cyberpunk does not see the message. A Cyberpunk choice list
	/// keeps the wheel.
	bool on_wheel(WPARAM wParam)
	{
		if (!forwarding() || choices_shown())
			return false;
		g_wheel += GET_WHEEL_DELTA_WPARAM(wParam);
		const int notches = g_wheel / WHEEL_DELTA; // + = away from the user = up
		g_wheel -= notches * WHEEL_DELTA;
		if (notches != 0)
			cb::link().send({{"t", "scroll"}, {"d", notches}});
		return true;
	}

	LRESULT CALLBACK wnd_proc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
	{
		if (!g_held.empty() && !forwarding())
			release_all_but_escape();

		switch (msg)
		{
		case WM_INPUT:
			if (on_raw_input(lParam))
				return DefWindowProcW(hwnd, msg, wParam, lParam); // a swallowed key: only the system's cleanup
			break;
		case WM_KEYDOWN:
		case WM_KEYUP:
		case WM_SYSKEYDOWN:
		case WM_SYSKEYUP:
			if (on_key(wParam, lParam, msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN))
				return 0;
			break;
		case WM_CHAR:
			if (on_char(wParam))
				return 0;
			break;
		case WM_LBUTTONDOWN:
		case WM_LBUTTONDBLCLK:
			if (button(0, true))
				return 0;
			break;
		case WM_LBUTTONUP:
			button(0, false);
			break;
		case WM_RBUTTONDOWN:
		case WM_RBUTTONDBLCLK:
			if (button(1, true))
				return 0;
			break;
		case WM_RBUTTONUP:
			button(1, false);
			break;
		case WM_MBUTTONDOWN:
		case WM_MBUTTONDBLCLK:
			if (button(2, true))
				return 0;
			break;
		case WM_MBUTTONUP:
			button(2, false);
			break;
		case WM_MOUSEWHEEL:
			if (on_wheel(wParam))
				return 0;
			break;
		case WM_MOUSEHWHEEL:
			if (forwarding())
				return 0;
			break;
		case WM_KILLFOCUS: // key-ups now go elsewhere
			release_all_but_escape();
			release(kEscape);
			g_rawGate.reset();
			g_messageGate.reset();
			g_chars.reset();
			break;
		}
		return CallWindowProcW(g_original.load(), hwnd, msg, wParam, lParam);
	}

	/// The game's own GetRawInputData: while forwarding, the mouse packets it gets lose their left/right/middle button-down
	/// and wheel bits (those go to Minecraft instead), but keep the motion that turns the camera, the button-up bits and
	/// mouse 4/5.
	UINT WINAPI get_raw_input_data(HRAWINPUT raw, UINT command, LPVOID data, PUINT size, UINT headerSize)
	{
		const UINT copied = reinterpret_cast<GetRawInputDataFn>(g_realGetRawInputData)(raw, command, data, size, headerSize);
		constexpr UINT kMouseBytes = sizeof(RAWINPUTHEADER) + sizeof(RAWMOUSE);
		if (copied != UINT(-1) && command == RID_INPUT && data != nullptr && copied >= kMouseBytes && forwarding())
		{
			auto *packet = static_cast<RAWINPUT *>(data);
			if (packet->header.dwType == RIM_TYPEMOUSE)
			{
				RAWMOUSE &mouse = packet->data.mouse;
				// while the drive owns V's yaw (a glide in first person) the game must not turn V itself: the drive turns
				// it by these counts (take_mouse_x) and every teleport would otherwise undo the game's turn. Pitch stays.
				if (cb::drive::driving() && !cb::view::applied() && !cb::link().screen_open() &&
					(mouse.usFlags & MOUSE_MOVE_ABSOLUTE) == 0)
					mouse.lLastX = 0;
				if (choices_shown())
					mouse.usButtonFlags = cb::keys::strip_down(mouse.usButtonFlags); // the wheel scrolls Cyberpunk's choices
				else
				{
					if ((mouse.usButtonFlags & cb::keys::kMouseWheel) != 0 || (mouse.usButtonFlags & cb::keys::kMouseHWheel) != 0)
						mouse.usButtonData = 0; // the wheel delta goes with the wheel bit
					mouse.usButtonFlags = cb::keys::strip_down_and_wheel(mouse.usButtonFlags);
				}
			}
		}
		return copied;
	}

	void hook_mouse_buttons()
	{
		if (cb::iat::patch(GetModuleHandleW(nullptr), "USER32.dll", "GetRawInputData", reinterpret_cast<void *>(&get_raw_input_data),
						   &g_realGetRawInputData))
		{
			g_mouseHooked = true;
			cb::log::info("hooked GetRawInputData in the game's import table: mouse buttons and wheel go to Minecraft");
		}
		else
			cb::log::error("could not hook GetRawInputData in the game's import table (%lu): mouse buttons and wheel also reach "
						   "Cyberpunk",
						   GetLastError());
	}

	BOOL CALLBACK find_window(HWND hwnd, LPARAM out)
	{
		DWORD pid = 0;
		GetWindowThreadProcessId(hwnd, &pid);
		if (pid != GetCurrentProcessId() || !IsWindowVisible(hwnd))
			return TRUE;
		wchar_t title[256] = {};
		wchar_t cls[256] = {};
		InternalGetWindowText(hwnd, title, 256); // unlike GetWindowText, sends no message to the window's thread
		GetClassNameW(hwnd, cls, 256);
		if (std::wcsstr(title, L"Cyberpunk 2077") == nullptr && std::wcsstr(cls, L"Cyberpunk 2077") == nullptr)
			return TRUE;
		*reinterpret_cast<HWND *>(out) = hwnd;
		return FALSE;
	}

	std::vector<RAWINPUTDEVICE> registered_raw_devices()
	{
		UINT count = 0;
		GetRegisteredRawInputDevices(nullptr, &count, sizeof(RAWINPUTDEVICE));
		std::vector<RAWINPUTDEVICE> devices(count);
		if (count > 0 && GetRegisteredRawInputDevices(devices.data(), &count, sizeof(RAWINPUTDEVICE)) == UINT(-1))
			devices.clear();
		return devices;
	}

	/// The game registers raw mouse input itself (its WM_INPUT reaches this window); registering again would replace
	/// its flags, so only register when nobody has.
	void ensure_raw_mouse(HWND hwnd)
	{
		for (const RAWINPUTDEVICE &d : registered_raw_devices())
			if (d.usUsagePage == 0x01 && d.usUsage == 0x02)
			{
				cb::log::info("raw mouse input already registered (flags 0x%lx)", d.dwFlags);
				return;
			}
		const RAWINPUTDEVICE mouse{0x01, 0x02, 0, hwnd}; // generic desktop / mouse, no RIDEV_INPUTSINK
		if (RegisterRawInputDevices(&mouse, 1, sizeof(mouse)))
			cb::log::info("registered raw mouse input");
		else
			cb::log::error("RegisterRawInputDevices failed (%lu): no cursor in Minecraft screens", GetLastError());
	}

	/// Audit G-1: does the game read the keyboard as raw input (usage page 1, usage 6) or as WM_KEYDOWN? Logged the first
	/// time forwarding starts, when the game has certainly registered whatever it registers.
	void log_keyboard_path()
	{
		for (const RAWINPUTDEVICE &d : registered_raw_devices())
			if (d.usUsagePage == 0x01 && d.usUsage == 0x06)
			{
				cb::log::info("raw keyboard input is registered (flags 0x%lx%s): keys reach the game as WM_INPUT%s", d.dwFlags,
							  (d.dwFlags & RIDEV_NOLEGACY) != 0 ? ", no legacy messages" : "",
							  (d.dwFlags & RIDEV_NOLEGACY) != 0 ? " only" : " and as WM_KEYDOWN");
				return;
			}
		cb::log::info("raw keyboard input is not registered: keys reach the game as WM_KEYDOWN/WM_KEYUP");
	}

	void hook()
	{
		HWND hwnd = nullptr;
		EnumWindows(find_window, reinterpret_cast<LPARAM>(&hwnd));
		if (hwnd == nullptr)
			return;
		g_window = hwnd;
		// set g_original before the swap so messages arriving during it already have somewhere to go
		g_original = reinterpret_cast<WNDPROC>(GetWindowLongPtrW(hwnd, GWLP_WNDPROC));
		const auto previous =
			reinterpret_cast<WNDPROC>(SetWindowLongPtrW(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(&wnd_proc)));
		if (previous == nullptr)
		{
			g_original = nullptr;
			cb::log::error("could not hook Cyberpunk's window (%lu): no input forwarding or F9", GetLastError());
			return;
		}
		g_original = previous;
		cb::log::info("hooked Cyberpunk's window");
		ensure_raw_mouse(hwnd);
		hook_mouse_buttons();
	}
}

namespace cb::input
{
	void tick()
	{
		if (g_window == nullptr)
			hook();

		static bool loggedKeyboard = false;
		if (!loggedKeyboard && forwarding())
		{
			loggedKeyboard = true;
			log_keyboard_path();
		}

		{
			std::lock_guard<std::mutex> lock(g_lookLock);
			if (cb::natives::lookCapture && forwarding())
				flush_look();
			else
				g_look.clear();
		}

		std::lock_guard<std::mutex> lock(g_cursorLock);
		const bool open = screen_open();
		if (open && !g_cursor.screenWasOpen)
		{
			g_cursor.x = g_cursor.y = 0.5; // every screen starts with the cursor centred
			g_cursor.dirty = true;
		}
		g_cursor.screenWasOpen = open;
		if (open)
			flush_cursor(g_cursor);
	}

	void on_minecraft_message(const nlohmann::json &message)
	{
		const auto type = message.find("t");
		if (type == message.end() || *type != "binds")
			return;
		const auto update = keys::apply_binds(message, binds());
		if (!update)
		{
			world::note_invalid(1, "binds: keys is missing or not an object");
			return;
		}
		const keys::Binds &next = update->binds;
		g_bindHack = next.hack;
		g_bindSkills = next.skills;
		g_bindCyberware = next.cyberware;
		g_bindActivate = next.activate;
		g_bindArms = next.arms;
		g_bindCamo = next.camo;
		static std::atomic<bool> warned{false};
		if (update->fellBack() && !warned.exchange(true))
			log::warn("binds: %s (logged once)",
					  update->clash ? "two actions got the same key; all six fall back to G, K, U, Y, L and O"
									: "a key Minecraft sent cannot be used (not 1..254, or one of Cyberpunk's own or Minecraft's "
									  "forwarded keys); it falls back to its default");
		log::info("binds: hack %s, skills %s, cyberware %s, activate %s, arms %s, camo %s", vk_name(next.hack).c_str(),
				  vk_name(next.skills).c_str(), vk_name(next.cyberware).c_str(), vk_name(next.activate).c_str(),
				  vk_name(next.arms).c_str(), vk_name(next.camo).c_str());
	}

	int64_t take_mouse_x()
	{
		return g_mouseX.exchange(0);
	}

	void shutdown()
	{
		if (g_mouseHooked.exchange(false))
			iat::restore(GetModuleHandleW(nullptr), "USER32.dll", "GetRawInputData", reinterpret_cast<void *>(&get_raw_input_data),
						 g_realGetRawInputData);
		if (g_original.load() == nullptr)
			return;
		if (GetWindowLongPtrW(g_window, GWLP_WNDPROC) == reinterpret_cast<LONG_PTR>(&wnd_proc))
			SetWindowLongPtrW(g_window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(g_original.load()));
		else
			log::warn("another hook sits on top of Cyberpunk's window; leaving ours in its chain");
	}
}
