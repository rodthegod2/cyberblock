// Input forwarding, the pure parts (plan 2026-10-03 task D5, re-test 2 task D6: four binds): the swallow set with the
// rebindable keys, bind validation,
// the key-up rule as a state machine, the mouse bits and the look deltas.
#include "keys.hpp"
#include "test.hpp"
#include <chrono>
#include <cmath>
#include <fstream>

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

TEST(the_fixed_swallow_set_is_e_q_t_x_digits_slash_and_f5)
{
	const Binds defaults;
	for (uint32_t vk : {uint32_t('E'), uint32_t('Q'), uint32_t('T'), uint32_t('X'), kOem2, kF5})
		CHECK(is_swallowed(vk, defaults));
	for (uint32_t vk = '1'; vk <= '9'; ++vk)
		CHECK(is_swallowed(vk, defaults));
	CHECK(!is_swallowed('0', defaults)); // the 0 key is not in the set
}

TEST(the_default_binds_g_k_u_and_y_are_swallowed_too)
{
	const Binds defaults;
	CHECK(defaults.hack == 0x47 && defaults.skills == 0x4B && defaults.cyberware == 0x55 && defaults.activate == 0x59);
	CHECK(is_swallowed('G', defaults) && is_swallowed('K', defaults) && is_swallowed('U', defaults) && is_swallowed('Y', defaults));
}

TEST(rebinding_moves_the_swallowed_keys)
{
	const Binds moved{'L', 'O', 0x70, 0x71};
	CHECK(is_swallowed('L', moved) && is_swallowed('O', moved));
	CHECK(!is_swallowed('G', moved) && !is_swallowed('K', moved)); // Cyberpunk gets its walk toggle and crafting back
	CHECK(is_swallowed('E', moved) && is_swallowed(kF5, moved));   // the fixed set stays
}

TEST(everything_else_reaches_cyberpunk)
{
	const Binds defaults;
	for (uint32_t vk : {uint32_t('W'), uint32_t('A'), uint32_t('S'), uint32_t('D'), kSpace, kShift, kLeftShift, kRightShift, kControl,
						kLeftControl, uint32_t('C'), kEscape, uint32_t('R'), uint32_t('F'), uint32_t('V'), uint32_t(0x09) /*Tab*/,
						uint32_t(0x70) /*F1*/, uint32_t(0x61) /*Numpad 1*/, uint32_t(0x12) /*Alt*/})
		CHECK(!is_swallowed(vk, defaults));
}

TEST(a_cyberpunk_choice_list_keeps_e_q_and_1_to_4)
{
	const Context choices{.choices = true};
	for (uint32_t vk : {uint32_t('E'), uint32_t('Q'), uint32_t('1'), uint32_t('2'), uint32_t('3'), uint32_t('4')})
	{
		CHECK(is_choice_key(vk));
		CHECK(is_swallowed(vk, Binds{}) && !is_swallowed(vk, Binds{}, choices));
	}
	// everything else Minecraft takes stays Minecraft's: 5-9, T, X, /, F5, the bound keys, and F9
	for (uint32_t vk : {uint32_t('5'), uint32_t('9'), uint32_t('T'), uint32_t('X'), kOem2, kF5, kG, kK, kF9})
	{
		CHECK(!is_choice_key(vk));
		CHECK(is_swallowed(vk, Binds{}, choices));
	}
	for (uint32_t vk : {uint32_t('W'), uint32_t('F'), kSpace, kShift})
		CHECK(!is_swallowed(vk, Binds{}, choices));
}

TEST(an_open_minecraft_screen_keeps_every_key_from_cyberpunk)
{
	const Context screen{.screenOpen = true};
	for (uint32_t vk = 1; vk <= 254; ++vk)
		CHECK(is_swallowed(vk, Binds{}, screen)); // W A S D too: V stands still while Steve's inventory or chat is open
	// a screen covers a Cyberpunk choice list: its keys are Minecraft's again
	CHECK(is_swallowed('E', Binds{}, Context{.screenOpen = true, .choices = true}));
}

TEST(a_key_held_from_before_a_screen_opened_still_gets_its_up)
{
	KeyGate gate;
	CHECK(gate.down('W', is_swallowed('W', Binds{})) == Verdict::Pass); // walking when the inventory opens
	const bool swallowable = is_swallowed('W', Binds{}, Context{.screenOpen = true});
	CHECK(gate.down('W', swallowable) == Verdict::Pass); // the repeat follows the first down
	CHECK(gate.up('W') == Verdict::Pass);                // so Cyberpunk sees the release and V stops
	CHECK(gate.down('W', swallowable) == Verdict::Swallow);
	CHECK(gate.up('W') == Verdict::Swallow);
}

TEST(editing_keys_have_minecraft_26_3_scancodes)
{
	// Windows VK -> SDL scancode (InputConstants.KEY_*)
	const std::pair<uint32_t, int> cases[] = {{0x0D, 40} /*Enter*/, {0x08, 42} /*Backspace*/, {0x09, 43} /*Tab*/,
											  {0x2E, 76} /*Delete*/,  {0x24, 74} /*Home*/,      {0x23, 77} /*End*/,
											  {0x21, 75} /*PgUp*/,    {0x22, 78} /*PgDn*/,      {0x27, 79} /*Right*/,
											  {0x25, 80} /*Left*/,    {0x28, 81} /*Down*/,      {0x26, 82} /*Up*/};
	for (const auto &[vk, scancode] : cases)
		CHECK(editing_scancode(vk) == scancode);
	for (uint32_t vk : {uint32_t('A'), uint32_t('1'), kSpace, kEscape, kShift, kF5, uint32_t(0x2D) /*Insert*/})
		CHECK(!editing_scancode(vk));
}

TEST(typed_characters_are_joined_into_code_points)
{
	CharJoiner chars;
	CHECK(chars.feed(u'a') == U'a' && chars.feed(0x00E9) == char32_t(0x00E9) && chars.feed(u' ') == U' '); // e acute
	CHECK(!chars.feed(0xD83D));                          // a high surrogate waits
	CHECK(chars.feed(0xDE00) == char32_t(0x1F600));      // U+1F600 from D83D DE00
	CHECK(!chars.feed(0xDE00));                          // a low surrogate on its own
	CHECK(!chars.feed(0xD83D) && chars.feed(u'b') == U'b'); // an unpaired high one is dropped, the next char kept
	for (uint16_t control : {uint16_t(0x08), uint16_t(0x09), uint16_t(0x0D), uint16_t(0x1B), uint16_t(0x01), uint16_t(0x7F)})
		CHECK(!chars.feed(control));                     // Backspace, Tab, Enter, Esc, Ctrl+A, DEL
	CHECK(!chars.feed(0xD83D));
	chars.reset();
	CHECK(!chars.feed(0xDE00));                          // reset forgot the waiting high surrogate
}

TEST(f9_never_reaches_cyberpunk)
{
	// Cyberblock's toggle; Cyberpunk's default F9 is QuickLoad, which loaded a save in the 2026-10-03 session
	CHECK(is_swallowed(kF9, Binds{}));
	CHECK(is_swallowed(kF9, binds_of('L', 'O')));
}

// ---- which keys may be bound -------------------------------------------------------------------------------------------

TEST(bindable_keys_are_1_to_254_and_not_reserved)
{
	// what is left of the keys Minecraft can send: G K L O U Y 0 F1-F4 F6-F8 F10-F12
	for (uint32_t vk : {uint32_t('G'), uint32_t('K'), uint32_t('L'), uint32_t('O'), uint32_t('U'), uint32_t('Y'), uint32_t('0')})
		CHECK(bindable(vk));
	for (uint32_t vk : {0x70u, 0x71u, 0x72u, 0x73u, 0x75u, 0x76u, 0x77u, 0x79u, 0x7Au, 0x7Bu}) // F1-F4, F6-F8, F10-F12
		CHECK(bindable(vk));
	CHECK(bindable(0x61) && bindable(0x09) && bindable(0x12)); // Numpad 1, Tab, Alt (Minecraft never sends these)
	CHECK(bindable(1) && bindable(254));
	CHECK(!bindable(0) && !bindable(255) && !bindable(256) && !bindable(1000) && !bindable(0xFFFFFFFFu));
}

TEST(reserved_keys_cannot_be_bound)
{
	CHECK(!bindable(kF9) && !bindable(kEscape) && !bindable(kF5));
	for (uint32_t vk : {uint32_t('E'), uint32_t('Q'), uint32_t('T'), uint32_t('X'), kOem2}) // the swallow set
		CHECK(!bindable(vk));
	for (uint32_t vk = '1'; vk <= '9'; ++vk)
		CHECK(!bindable(vk));
	for (char c : std::string("WASDCFIMJHNBPVZR")) // Cyberpunk's own letters while V is on foot
		CHECK(!bindable(uint32_t(c)));
	for (uint32_t vk : {kSpace, kShift, kLeftShift, kRightShift, kControl, kLeftControl, kRightControl})
		CHECK(!bindable(vk));
}

TEST(the_default_binds_are_bindable_and_distinct)
{
	const Binds defaults;
	const uint32_t keys[] = {defaults.hack, defaults.skills, defaults.cyberware, defaults.activate};
	for (size_t i = 0; i < 4; ++i)
	{
		CHECK(bindable(keys[i]));
		for (size_t j = i + 1; j < 4; ++j)
			CHECK(keys[i] != keys[j]);
	}
}

// ---- the binds message (plan 2026-10-03-retest2 section 1.8: four keys) ---------------------------------------------------

namespace
{
	/// Four binds as a function call (see binds_of).
	Binds four(uint32_t hack, uint32_t skills, uint32_t cyberware, uint32_t activate)
	{
		return {hack, skills, cyberware, activate};
	}

	constexpr uint32_t kL = 'L', kO = 'O', k0 = '0', kF1 = 0x70, kF2 = 0x71, kF4 = 0x73;
}

TEST(a_binds_message_sets_all_four_keys)
{
	const auto update =
		apply_binds(binds_message(R"({"hack":76,"skills":79,"cyberware":48,"activate":112})"), Binds{}); // L, O, 0, F1
	CHECK(update.has_value() && update->binds == four(kL, kO, k0, kF1) && !update->fellBack());
}

TEST(a_missing_name_keeps_the_current_key)
{
	// an older Minecraft build sends only hack and skills: cyberware and activate stay as they are
	const auto older = apply_binds(binds_message(R"({"hack":76,"skills":79})"), four(kG, kK, k0, kF1));
	CHECK(older && older->binds == four(kL, kO, k0, kF1) && !older->fellBack());
	const auto onlyActivate = apply_binds(binds_message(R"({"activate":115})"), four(kL, kO, k0, kF1)); // F4
	CHECK(onlyActivate && onlyActivate->binds == four(kL, kO, k0, kF4) && !onlyActivate->fellBack());
	const auto empty = apply_binds(binds_message("{}"), four(kL, kO, k0, kF1));
	CHECK(empty && empty->binds == four(kL, kO, k0, kF1) && !empty->fellBack());
}

TEST(an_unusable_key_falls_back_to_its_own_default)
{
	const Binds current = four(kL, kO, k0, kF1);
	const auto badHack = apply_binds(binds_message(R"({"hack":69})"), current); // E: swallowed
	CHECK(badHack && badHack->binds == four(kG, kO, k0, kF1) && badHack->hackRejected && badHack->fellBack());
	CHECK(!badHack->skillsRejected && !badHack->cyberwareRejected && !badHack->activateRejected && !badHack->clash);
	const auto badSkills = apply_binds(binds_message(R"({"skills":70})"), current); // F: Cyberpunk's interact
	CHECK(badSkills && badSkills->binds == four(kL, kK, k0, kF1) && badSkills->skillsRejected);
	const auto badCyberware = apply_binds(binds_message(R"({"cyberware":120})"), current); // F9
	CHECK(badCyberware && badCyberware->binds == four(kL, kO, kU, kF1) && badCyberware->cyberwareRejected);
	const auto badActivate = apply_binds(binds_message(R"({"activate":87})"), current); // W
	CHECK(badActivate && badActivate->binds == four(kL, kO, k0, kY) && badActivate->activateRejected);
	for (const char *value : {R"("71")", "71.5", "-71", "300", "null", "true", "[71]", "255", "0", "82" /*R*/, "86" /*V*/, "73" /*I*/})
	{
		const auto update = apply_binds(binds_message(std::string(R"({"hack":)") + value + "}"), current);
		CHECK(update && update->binds == four(kG, kO, k0, kF1) && update->hackRejected);
	}
	const auto whole = apply_binds(binds_message(R"({"hack":113.0})"), current); // F2, a whole number written as a float
	CHECK(whole && whole->binds == four(kF2, kO, k0, kF1) && !whole->fellBack());
	const auto allBad = apply_binds(binds_message(R"({"hack":0,"skills":120,"cyberware":32,"activate":16})"), current);
	CHECK(allBad && allBad->binds == Binds{} && allBad->hackRejected && allBad->skillsRejected && allBad->cyberwareRejected &&
		  allBad->activateRejected && !allBad->clash);
}

TEST(any_two_equal_keys_send_all_four_back_to_g_k_u_y)
{
	const Binds current = four(kL, kO, k0, kF1);
	const auto same = apply_binds(binds_message(R"({"hack":85,"skills":85})"), current);
	CHECK(same && same->binds == Binds{} && same->clash && !same->hackRejected && !same->skillsRejected);
	const auto ontoCurrent = apply_binds(binds_message(R"({"cyberware":76})"), current); // onto the current hack key
	CHECK(ontoCurrent && ontoCurrent->binds == Binds{} && ontoCurrent->clash);
	const auto lastTwo = apply_binds(binds_message(R"({"cyberware":114,"activate":114})"), current); // F3 twice
	CHECK(lastTwo && lastTwo->binds == Binds{} && lastTwo->clash);
	const auto ontoDefault = apply_binds(binds_message(R"({"activate":71})"), Binds{}); // Y -> G, the hack key
	CHECK(ontoDefault && ontoDefault->binds == Binds{} && ontoDefault->clash);
	// a refused key falls back to its default, which another key may already use: that is a clash too
	const auto refusedOntoAnother = apply_binds(binds_message(R"({"skills":69})"), four(kK, kL, k0, kF1));
	CHECK(refusedOntoAnother && refusedOntoAnother->binds == Binds{} && refusedOntoAnother->skillsRejected &&
		  refusedOntoAnother->clash);
	const auto swap = apply_binds(binds_message(R"({"hack":79,"skills":76,"cyberware":112,"activate":48})"), current);
	CHECK(swap && swap->binds == four(kO, kL, kF1, k0) && !swap->fellBack()); // a permutation is fine
	const auto defaults = apply_binds(binds_message(R"({"hack":71,"skills":75,"cyberware":85,"activate":89})"), current);
	CHECK(defaults && defaults->binds == Binds{} && !defaults->fellBack());
}

TEST(a_binds_message_without_a_keys_object_is_malformed)
{
	CHECK(!apply_binds(json::parse(R"({"t":"binds"})"), binds_of()));
	CHECK(!apply_binds(json::parse(R"({"t":"binds","keys":[71,75]})"), binds_of()));
	CHECK(!apply_binds(json::parse(R"({"t":"binds","keys":"GK"})"), binds_of()));
}

TEST(the_cyberware_and_activate_keys_u_and_y_are_swallowed)
{
	const Binds defaults;
	CHECK(defaults.cyberware == 0x55 && defaults.activate == 0x59 && kU == 0x55 && kY == 0x59);
	CHECK(is_swallowed('U', defaults) && is_swallowed('Y', defaults));
	const Binds moved = four(kL, kO, kF2, kF4);
	CHECK(is_swallowed(kF2, moved) && is_swallowed(kF4, moved));
	CHECK(!is_swallowed('U', moved) && !is_swallowed('Y', moved)); // Cyberpunk gets them back
	CHECK(is_swallowed('U', defaults, Context{.choices = true}));  // not a choice key
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

// ---- modifiers ---------------------------------------------------------------------------------------------------------

TEST(mods_are_sdl_left_shift_and_left_ctrl_bits)
{
	static_assert(sdl_mods(false, false) == 0 && sdl_mods(true, false) == 1 && sdl_mods(false, true) == 64);
	CHECK(sdl_mods(true, true) == (kModShift | kModCtrl) && kModShift == 0x0001 && kModCtrl == 0x0040);
}

// ---- mouse bits and look deltas ----------------------------------------------------------------------------------------

TEST(strip_removes_the_left_right_middle_down_and_wheel_bits_and_keeps_the_up_bits)
{
	static_assert(strip_down_and_wheel(0x0001 | 0x0004 | 0x0010 | 0x0400 | 0x0800) == 0);
	CHECK(strip_down_and_wheel(kMouseLeftDown) == 0 && strip_down_and_wheel(kMouseRightDown) == 0);
	CHECK(strip_down_and_wheel(kMouseMiddleDown) == 0 && strip_down_and_wheel(kMouseWheel) == 0 && strip_down_and_wheel(kMouseHWheel) == 0);
	// RI_MOUSE_LEFT_BUTTON_UP 0x2, RIGHT 0x8, MIDDLE 0x20, BUTTON_4 0x80, BUTTON_5 0x200: a release is never lost
	for (uint16_t up : {uint16_t(0x0002), uint16_t(0x0008), uint16_t(0x0020), uint16_t(0x0080), uint16_t(0x0200)})
	{
		CHECK(strip_down_and_wheel(up) == up);
		CHECK(strip_down_and_wheel(uint16_t(up | kMouseLeftDown | kMouseWheel)) == up);
	}
	CHECK(strip_down_and_wheel(0) == 0);
	CHECK(strip_down_and_wheel(0xFFFF) == 0xF3EA); // 0xFFFF without 0x0C15 (the five down and wheel bits): the rest is left alone
}

TEST(strip_down_keeps_the_wheels_for_a_cyberpunk_choice_list)
{
	CHECK(strip_down(kMouseWheel) == kMouseWheel && strip_down(kMouseHWheel) == kMouseHWheel);
	CHECK(strip_down(uint16_t(kMouseLeftDown | kMouseRightDown | kMouseMiddleDown)) == 0);
	CHECK(strip_down(uint16_t(kMouseLeftDown | kMouseWheel | kMouseButton4Down | 0x0002)) == (kMouseWheel | kMouseButton4Down | 0x0002));
}

TEST(mouse_4_and_5_stay_with_cyberpunk)
{
	// the side buttons are this player's dodge and scanner: Cyberpunk gets their down bits while forwarding too
	CHECK(strip_down_and_wheel(kMouseButton4Down) == kMouseButton4Down);
	CHECK(strip_down_and_wheel(kMouseButton5Down) == kMouseButton5Down);
	CHECK(strip_down_and_wheel(uint16_t(kMouseButton4Down | kMouseLeftDown | kMouseWheel)) == kMouseButton4Down);
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

// shared/vectors.json `binds`: the keys Minecraft can name and which the DLL takes. Minecraft's VirtualKeys reads the same
// set, so a binding Minecraft sends is one the DLL accepts, and every other one is sent as the default (G/K) instead.
TEST(bindable_matches_the_shared_binds_vectors)
{
	std::ifstream file(CB_VECTORS);
	const nlohmann::json vectors = nlohmann::json::parse(file);
	CHECK(vectors.at("binds").size() == 48);
	for (const auto &c : vectors.at("binds"))
		CHECK(cb::keys::bindable(c.at("vk").get<uint32_t>()) == c.at("bindable").get<bool>());
}
