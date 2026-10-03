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

	/// Keys Cyberpunk never sees while forwarding: E, Q, T, 1-9, "/", F5 and the two bound keys.
	bool is_swallowed(uint32_t vk, const Binds &binds);

	/// May `vk` be bound to hack or skills: 1..254, and not F9, Esc, a key of the fixed swallow set above, W, A, S, D,
	/// Space, Shift, Ctrl or C (those belong to Minecraft's movement or to Cyberpunk).
	bool bindable(uint32_t vk);

	struct BindsUpdate
	{
		Binds binds;                 // the keys to use from now on
		bool hackRejected = false;   // a hack key was given and is not usable: the previous one stays
		bool skillsRejected = false;
	};

	/// Applies a `binds` message ({"keys":{"hack":<vk>,"skills":<vk>}}) to `current`. A key that is missing stays; a key that
	/// is not a whole number, not bindable, or that would make both actions share one key keeps its previous value and is
	/// reported as rejected. nullopt when `keys` is missing or not an object.
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

	// ---- mouse ---------------------------------------------------------------------------------------------------------

	// RAWMOUSE.usButtonFlags bits (winuser.h RI_MOUSE_*)
	inline constexpr uint16_t kMouseLeftDown = 0x0001;
	inline constexpr uint16_t kMouseRightDown = 0x0004;
	inline constexpr uint16_t kMouseMiddleDown = 0x0010;
	inline constexpr uint16_t kMouseButton4Down = 0x0040;
	inline constexpr uint16_t kMouseButton5Down = 0x0100;
	inline constexpr uint16_t kMouseWheel = 0x0400;
	inline constexpr uint16_t kMouseHWheel = 0x0800;
	inline constexpr uint16_t kMouseDownAndWheelBits = kMouseLeftDown | kMouseRightDown | kMouseMiddleDown | kMouseButton4Down |
													   kMouseButton5Down | kMouseWheel | kMouseHWheel;

	/// `usButtonFlags` without the button-down and wheel bits; the button-up bits stay, so a button held before forwarding
	/// started is still released in Cyberpunk.
	constexpr uint16_t strip_down_and_wheel(uint16_t buttonFlags)
	{
		return static_cast<uint16_t>(buttonFlags & ~kMouseDownAndWheelBits);
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
