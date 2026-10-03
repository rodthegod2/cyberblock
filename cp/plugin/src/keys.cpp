#include "keys.hpp"
#include <cmath>

namespace
{
	using namespace cb::keys;

	bool is_digit(uint32_t vk)
	{
		return vk >= kDigit1 && vk <= kDigit9;
	}

	/// The keys of the swallow set that never change: E, Q, T, 1-9, "/" and F5.
	bool in_fixed_swallow_set(uint32_t vk)
	{
		return vk == kE || vk == kQ || vk == kT || vk == kOem2 || vk == kF5 || is_digit(vk);
	}

	/// Reads `keys[name]`: leaves `slot` alone when it is missing, sets it when it is usable, and flags `rejected` when
	/// it is there but not a whole number that bindable() takes.
	void read_bind(const nlohmann::json &keys, const char *name, uint32_t &slot, bool &rejected)
	{
		const auto it = keys.find(name);
		if (it == keys.end())
			return;
		const double v = it->is_number() ? it->get<double>() : -1.0;
		if (v == std::floor(v) && v >= 0.0 && v <= 255.0 && bindable(static_cast<uint32_t>(v)))
			slot = static_cast<uint32_t>(v);
		else
			rejected = true;
	}
}

namespace cb::keys
{
	bool is_swallowed(uint32_t vk, const Binds &binds)
	{
		return in_fixed_swallow_set(vk) || vk == binds.hack || vk == binds.skills;
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
		case kW:
		case kA:
		case kS:
		case kD:
		case kC:
			return false;
		default:
			return true;
		}
	}

	std::optional<BindsUpdate> apply_binds(const nlohmann::json &message, const Binds &current)
	{
		const auto keys = message.find("keys");
		if (keys == message.end() || !keys->is_object())
			return std::nullopt;
		BindsUpdate out{current};
		Binds next = current;
		read_bind(*keys, "hack", next.hack, out.hackRejected);
		read_bind(*keys, "skills", next.skills, out.skillsRejected);
		if (next.hack == next.skills)
		{
			// one key for both actions would fire both: keep the previous pair
			out.hackRejected = out.hackRejected || next.hack != current.hack;
			out.skillsRejected = out.skillsRejected || next.skills != current.skills;
			return out;
		}
		out.binds = next;
		return out;
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
