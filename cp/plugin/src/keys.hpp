// The pure parts of input forwarding (plan 2026-10-03 task D5, audit A.3): which keys Cyberpunk never sees while Minecraft
// has the keyboard, which keys may be bound to the hack and skills actions, the rule that keeps a key from sticking in
// Cyberpunk, and the mouse bits and look deltas. Nothing here needs Windows: input.cpp is the thin layer over it.
#pragma once
#include <array>
#include <chrono>
#include <cstdint>
#include <map>
#include <nlohmann/json.hpp>
#include <optional>

namespace cb::keys
{
	// Windows virtual-key codes (winuser.h), written out so this file needs no Windows headers.
	inline constexpr uint32_t kEscape = 0x1B;
	inline constexpr uint32_t kShift = 0x10;
	inline constexpr uint32_t kControl = 0x11;
	inline constexpr uint32_t kSpace = 0x20;
	inline constexpr uint32_t kDigit1 = 0x31; // '1'..'9' are 0x31..0x39
	inline constexpr uint32_t kDigit4 = 0x34;
	inline constexpr uint32_t kDigit9 = 0x39;
	inline constexpr uint32_t kA = 0x41;
	inline constexpr uint32_t kC = 0x43;
	inline constexpr uint32_t kD = 0x44;
	inline constexpr uint32_t kE = 0x45;
	inline constexpr uint32_t kG = 0x47;
	inline constexpr uint32_t kK = 0x4B;
	inline constexpr uint32_t kQ = 0x51;
	inline constexpr uint32_t kS = 0x53;
	inline constexpr uint32_t kT = 0x54;
	inline constexpr uint32_t kW = 0x57;
	inline constexpr uint32_t kX = 0x58;
	inline constexpr uint32_t kF5 = 0x74;
	inline constexpr uint32_t kF9 = 0x78;
	inline constexpr uint32_t kLeftShift = 0xA0;
	inline constexpr uint32_t kRightShift = 0xA1;
	inline constexpr uint32_t kLeftControl = 0xA2;
	inline constexpr uint32_t kRightControl = 0xA3;
	inline constexpr uint32_t kOem2 = 0xBF; // the "/?" key on US layouts

	/// The two rebindable actions' keys; the defaults are G (hack) and K (skills).
	struct Binds
	{
		uint32_t hack = kG;
		uint32_t skills = kK;
		bool operator==(const Binds &) const = default;
	};

	/// What is on screen besides the world.
	struct Context
	{
		/// A Cyberpunk dialogue or choice list offers two or more choices (Cyberblock_SetChoices): E, Q, 1-4 and the wheel
		/// pick them, so they stay Cyberpunk's.
		bool choices = false;
	};

	/// E, Q and 1-4: the keys a Cyberpunk choice list needs while Context::choices.
	bool is_choice_key(uint32_t vk);

	/// Keys Cyberpunk never sees while forwarding: E, Q, T, X, 1-9, "/", F5 and the two bound keys (but not E, Q and 1-4
	/// while `context.choices`), and F9 (Cyberblock's toggle, which input.cpp keeps from Cyberpunk in every state, so its
	/// QuickLoad never fires).
	bool is_swallowed(uint32_t vk, const Binds &binds, Context context = {});

	/// May `vk` be bound to hack or skills: 1..254, and not F9, Esc, Space, Shift, Ctrl, a key of the fixed swallow set
	/// above (E Q T X 1-9 / F5), or one of Cyberpunk's own letters W A S D C F I M J H N B P V Z R. Of the keys Minecraft
	/// can send (letters, digits, F-keys; shared/vectors.json `binds`) that leaves G K L O U Y 0 F1-F4 F6-F8 F10-F12.
	bool bindable(uint32_t vk);

	struct BindsUpdate
	{
		Binds binds;                 // the keys to use from now on
		bool hackRejected = false;   // a hack key was given and is not a whole number that bindable() takes
		bool skillsRejected = false;
		bool clash = false;          // both actions resolved to one key
		/// Something was wrong: both actions went back to the defaults G/K.
		bool fellBack() const { return hackRejected || skillsRejected || clash; }
	};

	/// Applies a `binds` message ({"keys":{"hack":<vk>,"skills":<vk>}}) to `current`. A key that is missing stays. When a
	/// given key is not a whole number or not bindable, or both actions resolve to one key, BOTH fall back to the defaults
	/// G (hack) and K (skills), as Minecraft does. nullopt when `keys` is missing or not an object.
	std::optional<BindsUpdate> apply_binds(const nlohmann::json &message, const Binds &current);

	/// Decides, key by key, what Cyberpunk gets: a key that goes down while forwarding is swallowed (with its repeats and its
	/// key-up), a key whose down Cyberpunk saw gets its up as well, so nothing sticks in Cyberpunk. A key's first down
	/// decides. Window thread only; one gate per input path, because each path (WM_INPUT, WM_KEYDOWN) sees every event.
	class KeyGate
	{
	public:
		enum class Verdict
		{
			Pass,    // Cyberpunk gets it
			Swallow, // Cyberpunk never sees it
		};

		/// A key went down (or repeated). `swallowable`: forwarding is on and the key is in the swallow set.
		Verdict down(uint32_t vk, bool swallowable);
		/// The key went up.
		Verdict up(uint32_t vk);
		/// Focus was lost: the ups of keys held meanwhile never arrive.
		void reset() { m_state.clear(); }

	private:
		enum class State
		{
			Seen,  // Cyberpunk saw the down
			Eaten, // the down was swallowed
		};
		std::map<uint32_t, State> m_state;
	};

	// ---- modifiers -----------------------------------------------------------------------------------------------------

	// SDL keymod bits (SDL_KMOD_LSHIFT, SDL_KMOD_LCTRL), as Minecraft 26.3 reads modifiers
	inline constexpr int kModShift = 0x0001;
	inline constexpr int kModCtrl = 0x0040;

	/// The `mods` of a `key`, `click` or `skey` message: Shift-click quick-moves a stack, Ctrl+Q drops a whole stack.
	constexpr int sdl_mods(bool shift, bool ctrl)
	{
		return (shift ? kModShift : 0) | (ctrl ? kModCtrl : 0);
	}

	// ---- mouse ---------------------------------------------------------------------------------------------------------

	// RAWMOUSE.usButtonFlags bits (winuser.h RI_MOUSE_*)
	inline constexpr uint16_t kMouseLeftDown = 0x0001;
	inline constexpr uint16_t kMouseRightDown = 0x0004;
	inline constexpr uint16_t kMouseMiddleDown = 0x0010;
	inline constexpr uint16_t kMouseButton4Down = 0x0040;
	inline constexpr uint16_t kMouseButton5Down = 0x0100;
	inline constexpr uint16_t kMouseWheel = 0x0400;
	inline constexpr uint16_t kMouseHWheel = 0x0800;
	/// The bits Minecraft takes: left, right and middle down and both wheels. Mouse 4 and 5 are not among them: Cyberpunk
	/// keeps the side buttons (players bind dodge, the scanner and the like to them).
	inline constexpr uint16_t kMouseDownAndWheelBits = kMouseLeftDown | kMouseRightDown | kMouseMiddleDown | kMouseWheel | kMouseHWheel;

	/// `usButtonFlags` without the left/right/middle button-down and wheel bits; the button-up bits stay, so a button held
	/// before forwarding started is still released in Cyberpunk, and the side buttons pass untouched.
	constexpr uint16_t strip_down_and_wheel(uint16_t buttonFlags)
	{
		return static_cast<uint16_t>(buttonFlags & ~kMouseDownAndWheelBits);
	}

	/// As strip_down_and_wheel, but the wheels stay too: while a Cyberpunk choice list is shown the wheel scrolls it.
	constexpr uint16_t strip_down(uint16_t buttonFlags)
	{
		return static_cast<uint16_t>(buttonFlags & ~(kMouseLeftDown | kMouseRightDown | kMouseMiddleDown));
	}

	/// Minecraft turns 0.15 degrees per raw mouse count at sensitivity 100 %.
	inline constexpr double kLookDegreesPerCount = 0.15;

	/// Raw mouse deltas summed into Minecraft look deltas, handed out at most 120 times a second.
	class LookAccumulator
	{
	public:
		using Clock = std::chrono::steady_clock;
		static constexpr Clock::duration kMinInterval = std::chrono::microseconds(1'000'000 / 120);

		void add(long dx, long dy)
		{
			m_dx += dx;
			m_dy += dy;
		}
		/// {dyaw, dpitch} in degrees (right and down are positive) when something is pending and the last one went out at
		/// least 1/120 s ago; empties the sum.
		std::optional<std::array<double, 2>> take(Clock::time_point now);
		/// Forgets what is pending.
		void clear() { m_dx = m_dy = 0; }

	private:
		long m_dx = 0, m_dy = 0;
		Clock::time_point m_sentAt{};
	};
}
