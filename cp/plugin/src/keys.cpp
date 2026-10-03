#include "keys.hpp"
#include <algorithm>
#include <cmath>
#include <iterator>
#include <string_view>
#include <utility>

namespace
{
	using namespace cb::keys;

	bool is_digit(uint32_t vk)
	{
		return vk >= kDigit1 && vk <= kDigit9;
	}

	/// The keys of the swallow set that never change: E, Q, T, X, 1-9, "/" and F5.
	bool in_fixed_swallow_set(uint32_t vk)
	{
		return vk == kE || vk == kQ || vk == kT || vk == kX || vk == kOem2 || vk == kF5 || is_digit(vk);
	}

	/// Reads `keys[name]`: leaves `slot` alone when it is missing, sets it when it is usable, and when it is there but
	/// not a whole number that bindable() takes, sets it to `fallback` (the action's default) and flags `rejected`.
	void read_bind(const nlohmann::json &keys, const char *name, uint32_t &slot, uint32_t fallback, bool &rejected)
	{
		const auto it = keys.find(name);
		if (it == keys.end())
			return;
		const double v = it->is_number() ? it->get<double>() : -1.0;
		if (v == std::floor(v) && v >= 0.0 && v <= 255.0 && bindable(static_cast<uint32_t>(v)))
			slot = static_cast<uint32_t>(v);
		else
		{
			slot = fallback;
			rejected = true;
		}
	}
}

namespace cb::keys
{
	bool is_choice_key(uint32_t vk)
	{
		return vk == kE || vk == kQ || (vk >= kDigit1 && vk <= kDigit4);
	}

	bool is_swallowed(uint32_t vk, const Binds &binds, Context context)
	{
		if (vk == kF9 || context.screenOpen)
			return true;
		if (context.choices && is_choice_key(vk))
			return false;
		if (in_fixed_swallow_set(vk))
			return true;
		const auto keys = binds.keys();
		return std::find(keys.begin(), keys.end(), vk) != keys.end();
	}

	bool bindable(uint32_t vk)
	{
		if (vk < 1 || vk > 254)
			return false;
		if (in_fixed_swallow_set(vk))
			return false;
		switch (vk)
		{
		case kEscape:
		case kF9:
		case kSpace:
		case kShift:
		case kLeftShift:
		case kRightShift:
		case kControl:
		case kLeftControl:
		case kRightControl:
			return false;
		}
		// Cyberpunk's own letters while V is on foot (movement, crouch, interact, menus, reload, holster, ...)
		constexpr std::string_view kCyberpunkLetters = "WASDCFIMJHNBPVZR";
		return !(vk >= 'A' && vk <= 'Z' && kCyberpunkLetters.find(char(vk)) != std::string_view::npos);
	}

	std::optional<BindsUpdate> apply_binds(const nlohmann::json &message, const Binds &current)
	{
		const auto keys = message.find("keys");
		if (keys == message.end() || !keys->is_object())
			return std::nullopt;
		BindsUpdate out;
		const Binds defaults;
		Binds next = current;
		read_bind(*keys, "hack", next.hack, defaults.hack, out.hackRejected);
		read_bind(*keys, "skills", next.skills, defaults.skills, out.skillsRejected);
		read_bind(*keys, "cyberware", next.cyberware, defaults.cyberware, out.cyberwareRejected);
		read_bind(*keys, "activate", next.activate, defaults.activate, out.activateRejected);
		read_bind(*keys, "arms", next.arms, defaults.arms, out.armsRejected);
		read_bind(*keys, "camo", next.camo, defaults.camo, out.camoRejected);
		// one key for two actions would fire both: every action goes back to its default
		const auto resolved = next.keys();
		for (size_t i = 0; i < std::size(resolved); ++i)
			for (size_t j = i + 1; j < std::size(resolved); ++j)
				out.clash = out.clash || resolved[i] == resolved[j];
		out.binds = out.clash ? defaults : next;
		return out;
	}

	std::optional<int> editing_scancode(uint32_t vk)
	{
		switch (vk)
		{
		case 0x0D: return 40; // VK_RETURN (also numpad Enter) -> KEY_RETURN
		case 0x08: return 42; // VK_BACK -> KEY_BACKSPACE
		case 0x09: return 43; // VK_TAB -> KEY_TAB
		case 0x2E: return 76; // VK_DELETE -> KEY_DELETE
		case 0x24: return 74; // VK_HOME -> KEY_HOME
		case 0x23: return 77; // VK_END -> KEY_END
		case 0x21: return 75; // VK_PRIOR -> KEY_PAGEUP
		case 0x22: return 78; // VK_NEXT -> KEY_PAGEDOWN
		case 0x27: return 79; // VK_RIGHT -> KEY_RIGHT
		case 0x25: return 80; // VK_LEFT -> KEY_LEFT
		case 0x28: return 81; // VK_DOWN -> KEY_DOWN
		case 0x26: return 82; // VK_UP -> KEY_UP
		default: return std::nullopt;
		}
	}

	std::optional<char32_t> CharJoiner::feed(uint16_t unit)
	{
		if (unit >= 0xD800 && unit <= 0xDBFF)
		{
			m_high = unit; // an earlier unpaired one is dropped
			return std::nullopt;
		}
		const uint16_t high = std::exchange(m_high, uint16_t(0));
		if (unit >= 0xDC00 && unit <= 0xDFFF)
		{
			if (high == 0)
				return std::nullopt; // a low surrogate on its own
			return char32_t(0x10000 + ((high - 0xD800) << 10) + (unit - 0xDC00));
		}
		if (unit < 0x20 || unit == 0x7F)
			return std::nullopt;
		return char32_t(unit);
	}

	KeyGate::Verdict KeyGate::down(uint32_t vk, bool swallowable)
	{
		const auto known = m_state.find(vk);
		if (known != m_state.end())
			return known->second == State::Eaten ? Verdict::Swallow : Verdict::Pass; // a repeat follows the first down
		m_state[vk] = swallowable ? State::Eaten : State::Seen;
		return swallowable ? Verdict::Swallow : Verdict::Pass;
	}

	KeyGate::Verdict KeyGate::up(uint32_t vk)
	{
		const auto known = m_state.find(vk);
		if (known == m_state.end())
			return Verdict::Pass; // pressed before anything was tracked: let the release through
		const Verdict verdict = known->second == State::Eaten ? Verdict::Swallow : Verdict::Pass;
		m_state.erase(known);
		return verdict;
	}

	std::optional<std::array<double, 2>> LookAccumulator::take(Clock::time_point now)
	{
		if ((m_dx == 0 && m_dy == 0) || now - m_sentAt < kMinInterval)
			return std::nullopt;
		const std::array<double, 2> turn{m_dx * kLookDegreesPerCount, m_dy * kLookDegreesPerCount};
		m_dx = m_dy = 0;
		m_sentAt = now;
		return turn;
	}
}
