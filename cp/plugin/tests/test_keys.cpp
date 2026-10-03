// Input forwarding, the pure parts (plan 2026-10-03 task D5): the swallow set with the rebindable keys, bind validation,
// the key-up rule as a state machine, the mouse bits and the look deltas.
#include "keys.hpp"
#include "test.hpp"
#include <chrono>
#include <cmath>

namespace
{
	using namespace cb::keys;
	using nlohmann::json;
	using Verdict = KeyGate::Verdict;
	using Clock = LookAccumulator::Clock;
	using namespace std::chrono_literals;

	bool approx(double a, double b)
	{
		return std::abs(a - b) <= 1e-9;
	}

	/// Binds as a function call: a braced Binds in a CHECK would be split at its comma by the macro.
	Binds binds_of(uint32_t hack = kG, uint32_t skills = kK)
	{
		return {hack, skills};
	}

	json binds_message(const std::string &keys)
	{
		return json::parse(R"({"t":"binds","keys":)" + keys + "}");
	}
}

// ---- the swallow set ---------------------------------------------------------------------------------------------------

TEST(the_fixed_swallow_set_is_e_q_t_digits_slash_and_f5)
{
	const Binds defaults;
	for (uint32_t vk : {uint32_t('E'), uint32_t('Q'), uint32_t('T'), kOem2, kF5})
		CHECK(is_swallowed(vk, defaults));
	for (uint32_t vk = '1'; vk <= '9'; ++vk)
		CHECK(is_swallowed(vk, defaults));
	CHECK(!is_swallowed('0', defaults)); // the 0 key is not in the set
}

TEST(the_default_binds_g_and_k_are_swallowed_too)
{
	const Binds defaults;
	CHECK(defaults.hack == 0x47 && defaults.skills == 0x4B);
	CHECK(is_swallowed('G', defaults) && is_swallowed('K', defaults));
}

TEST(rebinding_moves_the_swallowed_keys)
{
	const Binds moved{'Z', 'X'};
	CHECK(is_swallowed('Z', moved) && is_swallowed('X', moved));
	CHECK(!is_swallowed('G', moved) && !is_swallowed('K', moved)); // Cyberpunk gets its walk toggle and crafting back
	CHECK(is_swallowed('E', moved) && is_swallowed(kF5, moved));   // the fixed set stays
}

TEST(everything_else_reaches_cyberpunk)
{
	const Binds defaults;
	for (uint32_t vk : {uint32_t('W'), uint32_t('A'), uint32_t('S'), uint32_t('D'), kSpace, kShift, kLeftShift, kControl, kLeftControl,
						uint32_t('C'), kF9, kEscape, uint32_t('X'), uint32_t('R'), uint32_t('F'), uint32_t('V'), uint32_t(0x09) /*Tab*/,
						uint32_t(0x70) /*F1*/, uint32_t(0x61) /*Numpad 1*/, uint32_t(0x12) /*Alt*/})
		CHECK(!is_swallowed(vk, defaults));
}

// ---- which keys may be bound -------------------------------------------------------------------------------------------

TEST(bindable_keys_are_1_to_254_and_not_reserved)
{
	CHECK(bindable('G') && bindable('K') && bindable('Z') && bindable('V') && bindable('R'));
	CHECK(bindable(0x70) && bindable(0x7B));                  // F1, F12
	CHECK(bindable(0x61) && bindable(0x09) && bindable(0x12)); // Numpad 1, Tab, Alt
	CHECK(bindable(1) && bindable(254));
	CHECK(!bindable(0) && !bindable(255) && !bindable(256) && !bindable(1000) && !bindable(0xFFFFFFFFu));
}

TEST(reserved_keys_cannot_be_bound)
{
	CHECK(!bindable(kF9) && !bindable(kEscape));
	for (uint32_t vk : {uint32_t('E'), uint32_t('Q'), uint32_t('T'), kOem2, kF5}) // the swallow set
		CHECK(!bindable(vk));
	for (uint32_t vk = '1'; vk <= '9'; ++vk)
		CHECK(!bindable(vk));
	for (uint32_t vk : {uint32_t('W'), uint32_t('A'), uint32_t('S'), uint32_t('D'), kSpace, kShift, kLeftShift, kRightShift, kControl,
						kLeftControl, kRightControl, uint32_t('C')})
		CHECK(!bindable(vk));
}

TEST(the_default_binds_are_bindable_and_distinct)
{
	const Binds defaults;
	CHECK(bindable(defaults.hack) && bindable(defaults.skills) && defaults.hack != defaults.skills);
}

// ---- the binds message -------------------------------------------------------------------------------------------------

TEST(a_binds_message_sets_both_keys)
{
	const auto update = apply_binds(binds_message(R"({"hack":90,"skills":88})"), Binds{});
	CHECK(update.has_value() && update->binds == binds_of(90, 88) && !update->hackRejected && !update->skillsRejected);
}

TEST(a_missing_key_stays_and_an_unusable_one_keeps_its_previous_value)
{
	const Binds current{'Z', 'X'};
	const auto onlyHack = apply_binds(binds_message(R"({"hack":86})"), current); // V
	CHECK(onlyHack && onlyHack->binds == binds_of('V', 'X') && !onlyHack->hackRejected && !onlyHack->skillsRejected);
	const auto badHack = apply_binds(binds_message(R"({"hack":69,"skills":82})"), current); // hack = E (swallowed), skills = R
	CHECK(badHack && badHack->binds == binds_of('Z', 'R') && badHack->hackRejected && !badHack->skillsRejected);
	const auto bothBad = apply_binds(binds_message(R"({"hack":0,"skills":120})"), current); // 0 and F9
	CHECK(bothBad && bothBad->binds == current && bothBad->hackRejected && bothBad->skillsRejected);
	for (const char *value : {R"("71")", "71.5", "-71", "300", "null", "true", "[71]", "255"})
	{
		const auto update = apply_binds(binds_message(std::string(R"({"hack":)") + value + "}"), current);
		CHECK(update && update->binds == current && update->hackRejected);
	}
	const auto whole = apply_binds(binds_message(R"({"hack":86.0})"), current); // a whole number written as a float
	CHECK(whole && whole->binds.hack == 'V' && !whole->hackRejected);
}

TEST(a_binds_message_without_a_keys_object_is_malformed)
{
	CHECK(!apply_binds(json::parse(R"({"t":"binds"})"), binds_of()));
	CHECK(!apply_binds(json::parse(R"({"t":"binds","keys":[71,75]})"), binds_of()));
	CHECK(!apply_binds(json::parse(R"({"t":"binds","keys":"GK"})"), binds_of()));
	const auto empty = apply_binds(binds_message("{}"), Binds{'Z', 'X'});
	CHECK(empty && empty->binds == binds_of('Z', 'X') && !empty->hackRejected && !empty->skillsRejected);
}

TEST(one_key_for_both_actions_is_refused)
{
	const Binds current{'Z', 'X'};
	const auto same = apply_binds(binds_message(R"({"hack":86,"skills":86})"), current);
	CHECK(same && same->binds == current && same->hackRejected && same->skillsRejected);
	const auto clash = apply_binds(binds_message(R"({"hack":88})"), current); // hack onto the current skills key
	CHECK(clash && clash->binds == current && clash->hackRejected && !clash->skillsRejected);
	const auto swap = apply_binds(binds_message(R"({"hack":88,"skills":90})"), current); // swapping is fine
	CHECK(swap && swap->binds == binds_of('X', 'Z') && !swap->hackRejected && !swap->skillsRejected);
}

// ---- the key-up rule ---------------------------------------------------------------------------------------------------

TEST(a_key_pressed_while_forwarding_is_swallowed_with_its_repeats_and_its_up)
{
	KeyGate gate;
	CHECK(gate.down('E', true) == Verdict::Swallow);
	CHECK(gate.down('E', true) == Verdict::Swallow); // auto-repeat
	CHECK(gate.up('E') == Verdict::Swallow);
	CHECK(gate.down('E', true) == Verdict::Swallow); // and again for the next press
	CHECK(gate.up('E') == Verdict::Swallow);
}

TEST(a_key_pressed_before_forwarding_gets_its_up_so_nothing_sticks)
{
	KeyGate gate;
	CHECK(gate.down('E', false) == Verdict::Pass);   // held from before F9
	CHECK(gate.down('E', true) == Verdict::Pass);    // forwarding started: the repeat still follows the first down
	CHECK(gate.up('E') == Verdict::Pass);            // Cyberpunk saw the down, so it must see the up
	CHECK(gate.down('E', true) == Verdict::Swallow); // the next press is a fresh decision
	CHECK(gate.up('E') == Verdict::Swallow);
}

TEST(forwarding_stopping_mid_press_does_not_leak_the_swallowed_keys_up_or_repeats)
{
	KeyGate gate;
	CHECK(gate.down('G', true) == Verdict::Swallow);
	CHECK(gate.down('G', false) == Verdict::Swallow); // a repeat after forwarding stopped: Cyberpunk never saw the first down
	CHECK(gate.up('G') == Verdict::Swallow);
	CHECK(gate.down('G', false) == Verdict::Pass);     // not forwarding: a normal key
	CHECK(gate.up('G') == Verdict::Pass);
}

TEST(a_key_that_is_not_swallowable_always_passes)
{
	KeyGate gate;
	for (int i = 0; i < 3; ++i)
		CHECK(gate.down('W', false) == Verdict::Pass);
	CHECK(gate.up('W') == Verdict::Pass);
}

TEST(an_up_nobody_saw_the_down_of_passes)
{
	KeyGate gate;
	CHECK(gate.up('Q') == Verdict::Pass); // pressed before the plugin tracked anything
	CHECK(gate.up('Q') == Verdict::Pass);
}

TEST(keys_are_tracked_independently)
{
	KeyGate gate;
	CHECK(gate.down('E', true) == Verdict::Swallow);
	CHECK(gate.down('R', false) == Verdict::Pass);
	CHECK(gate.down('T', true) == Verdict::Swallow);
	CHECK(gate.up('R') == Verdict::Pass);
	CHECK(gate.up('E') == Verdict::Swallow);
	CHECK(gate.up('T') == Verdict::Swallow);
}

TEST(losing_focus_forgets_every_key)
{
	KeyGate gate;
	gate.down('E', true);
	gate.down('R', false);
	gate.reset();
	CHECK(gate.up('E') == Verdict::Pass); // the release that arrives after refocusing
	CHECK(gate.down('E', true) == Verdict::Swallow);
	CHECK(gate.up('R') == Verdict::Pass);
}

// ---- mouse bits and look deltas ----------------------------------------------------------------------------------------

TEST(strip_removes_every_button_down_and_wheel_bit_and_keeps_the_up_bits)
{
	static_assert(strip_down_and_wheel(0x0001 | 0x0004 | 0x0010 | 0x0040 | 0x0100 | 0x0400 | 0x0800) == 0);
	CHECK(strip_down_and_wheel(kMouseLeftDown) == 0 && strip_down_and_wheel(kMouseRightDown) == 0);
	CHECK(strip_down_and_wheel(kMouseMiddleDown) == 0 && strip_down_and_wheel(kMouseButton4Down) == 0);
	CHECK(strip_down_and_wheel(kMouseButton5Down) == 0 && strip_down_and_wheel(kMouseWheel) == 0 && strip_down_and_wheel(kMouseHWheel) == 0);
	// RI_MOUSE_LEFT_BUTTON_UP 0x2, RIGHT 0x8, MIDDLE 0x20, BUTTON_4 0x80, BUTTON_5 0x200: a release is never lost
	for (uint16_t up : {uint16_t(0x0002), uint16_t(0x0008), uint16_t(0x0020), uint16_t(0x0080), uint16_t(0x0200)})
	{
		CHECK(strip_down_and_wheel(up) == up);
		CHECK(strip_down_and_wheel(uint16_t(up | kMouseLeftDown | kMouseWheel)) == up);
	}
	CHECK(strip_down_and_wheel(0) == 0);
	CHECK(strip_down_and_wheel(0xFFFF) == 0xF2AA); // 0xFFFF without 0x0D55 (the seven down and wheel bits): the rest is left alone
}

TEST(the_look_deltas_are_summed_scaled_and_sent_at_most_120_times_a_second)
{
	LookAccumulator look;
	const Clock::time_point t0 = Clock::time_point{} + 10s;
	CHECK(!look.take(t0)); // nothing pending
	look.add(10, -4);
	const auto first = look.take(t0);
	CHECK(first.has_value() && approx((*first)[0], 1.5) && approx((*first)[1], -0.6)); // 0.15 degrees per count
	CHECK(!look.take(t0 + 20ms)); // emptied by the send
	look.add(2, 2);
	CHECK(!look.take(t0 + 8ms)); // under 1/120 s since the last send
	look.add(3, 0);
	CHECK(!look.take(t0 + 8332us));
	const auto second = look.take(t0 + 8334us); // 1/120 s is 8333.3 us
	CHECK(second.has_value() && approx((*second)[0], 0.75) && approx((*second)[1], 0.3)); // 5 and 2 counts, both kept
	CHECK(!look.take(t0 + 1s));
}

TEST(clearing_forgets_the_pending_look)
{
	LookAccumulator look;
	look.add(100, 100);
	look.clear();
	CHECK(!look.take(Clock::time_point{} + 10s));
	look.add(-7, 7);
	const auto turn = look.take(Clock::time_point{} + 10s);
	CHECK(turn && approx((*turn)[0], -1.05) && approx((*turn)[1], 1.05)); // left is negative yaw, down is positive pitch
}

TEST(look_deltas_that_cancel_out_send_nothing)
{
	LookAccumulator look;
	look.add(5, -3);
	look.add(-5, 3);
	CHECK(!look.take(Clock::time_point{} + 10s));
}
