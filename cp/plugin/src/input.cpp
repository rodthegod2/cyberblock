#include "input.hpp"
#include "link.hpp"
#include "log.hpp"
#include "natives.hpp"
#include <windows.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cwchar>
#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace
{
	constexpr double kCursorCountsPerWindow = 800.0; // raw mouse counts to cross the whole window
	constexpr auto kCursorInterval = std::chrono::microseconds(1'000'000 / 120);
	constexpr char kEscape[] = "key:escape";

	std::atomic<HWND> g_window{nullptr};     // set once found (hooked or not), so the search stops
	std::atomic<WNDPROC> g_original{nullptr}; // the window's own procedure, null unless hooked

	// Messages still owed to Minecraft: what was sent down -> the matching "up", so every press is released even when
	// forwarding stops in between (vehicle, focus loss, mashup off). Window thread only.
	std::map<std::string, nlohmann::json> g_held;
	int g_wheel = 0; // wheel delta not yet worth a notch. Window thread only.

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

	/// Forward to Minecraft right now: mashup on, on foot, linked, Cyberpunk's window in front.
	bool forwarding()
	{
		return cb::natives::active && !cb::natives::inVehicle && cb::link().linked() &&
			   GetForegroundWindow() == g_window;
	}

	bool screen_open()
	{
		return forwarding() && cb::link().screen_open();
	}

	void press(const std::string &id, nlohmann::json down, nlohmann::json up)
	{
		if (g_held.contains(id))
			return; // already down (key repeat)
		cb::link().send(down);
		g_held[id] = std::move(up);
	}

	void release(const std::string &id)
	{
		const auto it = g_held.find(id);
		if (it == g_held.end())
			return;
		cb::link().send(it->second);
		g_held.erase(it);
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
			cb::link().send(it->second);
			it = g_held.erase(it);
		}
	}

	nlohmann::json key(const char *name, bool down)
	{
		return {{"t", "key"}, {"k", name}, {"down", down}};
	}

	/// b: 0 left, 1 right, 2 middle. A screen gets clicks, the world gets attack / use / pick.
	void button(int b, bool down)
	{
		static const char *const kKeys[] = {"attack", "use", "pick"};
		const std::string id = "button:" + std::to_string(b);
		if (!down)
			return release(id);
		if (!forwarding())
			return;
		if (cb::link().screen_open())
			press(id, {{"t", "click"}, {"b", b}, {"down", true}}, {{"t", "click"}, {"b", b}, {"down", false}});
		else
			press(id, key(kKeys[b], true), key(kKeys[b], false));
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

	/// Raw input on the window: cursor motion, and whether this message is an Esc to keep from Cyberpunk.
	bool on_raw_input(LPARAM lParam)
	{
		RAWINPUT raw;
		UINT size = sizeof(raw);
		if (GetRawInputData(reinterpret_cast<HRAWINPUT>(lParam), RID_INPUT, &raw, &size, sizeof(RAWINPUTHEADER)) ==
			UINT(-1))
			return false; // not a mouse or keyboard packet (bigger HID data): not ours
		if (raw.header.dwType == RIM_TYPEMOUSE && (raw.data.mouse.usFlags & MOUSE_MOVE_ABSOLUTE) == 0 && screen_open())
		{
			std::lock_guard<std::mutex> lock(g_cursorLock);
			g_cursor.x = std::clamp(g_cursor.x + raw.data.mouse.lLastX / kCursorCountsPerWindow, 0.0, 1.0);
			g_cursor.y = std::clamp(g_cursor.y + raw.data.mouse.lLastY / kCursorCountsPerWindow, 0.0, 1.0);
			g_cursor.dirty = true;
			flush_cursor(g_cursor);
		}
		return raw.header.dwType == RIM_TYPEKEYBOARD && raw.data.keyboard.VKey == VK_ESCAPE &&
			   (screen_open() || g_held.contains(kEscape));
	}

	/// Legacy key messages. Returns true for an Esc that Minecraft takes (Cyberpunk must not see it).
	bool on_key(WPARAM vk, bool down, bool repeat)
	{
		if (vk == VK_F9)
		{
			if (down && !repeat)
				cb::natives::togglePressed = true;
			return false;
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

		const char *name = nullptr;
		switch (vk)
		{
		case 'E': name = "inventory"; break;
		case 'Q': name = "drop"; break;
		case 'T': name = "chat"; break;
		case VK_OEM_2: name = "command"; break; // the "/?" key on US layouts
		}
		if (name != nullptr)
		{
			const std::string id = std::string("key:") + name;
			if (!down)
				release(id);
			else if (forwarding())
				press(id, key(name, true), key(name, false));
		}
		else if (vk >= '1' && vk <= '9' && down && !repeat && forwarding())
			cb::link().send({{"t", "slot"}, {"n", int(vk - '1')}});
		return false;
	}

	LRESULT CALLBACK wnd_proc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
	{
		if (!g_held.empty() && !forwarding())
			release_all_but_escape();

		switch (msg)
		{
		case WM_INPUT:
			if (on_raw_input(lParam))
				return DefWindowProcW(hwnd, msg, wParam, lParam); // swallowed Esc: only the system's cleanup
			break;
		case WM_KEYDOWN:
		case WM_KEYUP:
			if (on_key(wParam, msg == WM_KEYDOWN, (lParam & (1 << 30)) != 0 && msg == WM_KEYDOWN))
				return 0;
			break;
		case WM_LBUTTONDOWN:
		case WM_LBUTTONDBLCLK:
		case WM_LBUTTONUP:
			button(0, msg != WM_LBUTTONUP);
			break;
		case WM_RBUTTONDOWN:
		case WM_RBUTTONDBLCLK:
		case WM_RBUTTONUP:
			button(1, msg != WM_RBUTTONUP);
			break;
		case WM_MBUTTONDOWN:
		case WM_MBUTTONDBLCLK:
		case WM_MBUTTONUP:
			button(2, msg != WM_MBUTTONUP);
			break;
		case WM_MOUSEWHEEL:
			if (forwarding())
			{
				g_wheel += GET_WHEEL_DELTA_WPARAM(wParam);
				const int notches = g_wheel / WHEEL_DELTA; // + = away from the user = up
				g_wheel -= notches * WHEEL_DELTA;
				if (notches != 0)
					cb::link().send({{"t", "scroll"}, {"d", notches}});
			}
			break;
		case WM_KILLFOCUS: // key-ups now go elsewhere
			release_all_but_escape();
			release(kEscape);
			break;
		}
		return CallWindowProcW(g_original.load(), hwnd, msg, wParam, lParam);
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

	/// The game registers raw mouse input itself (its WM_INPUT reaches this window); registering again would replace
	/// its flags, so only register when nobody has.
	void ensure_raw_mouse(HWND hwnd)
	{
		UINT count = 0;
		GetRegisteredRawInputDevices(nullptr, &count, sizeof(RAWINPUTDEVICE));
		std::vector<RAWINPUTDEVICE> devices(count);
		if (count > 0 && GetRegisteredRawInputDevices(devices.data(), &count, sizeof(RAWINPUTDEVICE)) == UINT(-1))
			devices.clear();
		for (const RAWINPUTDEVICE &d : devices)
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
	}
}

namespace cb::input
{
	void tick()
	{
		if (g_window == nullptr)
			hook();

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

	void shutdown()
	{
		if (g_original.load() == nullptr)
			return;
		if (GetWindowLongPtrW(g_window, GWLP_WNDPROC) == reinterpret_cast<LONG_PTR>(&wnd_proc))
			SetWindowLongPtrW(g_window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(g_original.load()));
		else
			log::warn("another hook sits on top of Cyberpunk's window; leaving ours in its chain");
	}
}
