// The Sandevistan / Kerenzikov screen effect's timing (re-test 3 plan task D7, section 1.10).
#include "screenfx.hpp"
#include "test.hpp"
#include <cmath>

namespace
{
	using cb::screenfx::envelope;
	using cb::screenfx::Kind;
	using cb::screenfx::SlowMo;

	bool close(double a, double b)
	{
		return std::abs(a - b) <= 1e-6;
	}
}

TEST(envelope_eases_in_over_0_15_s_holds_and_eases_out_over_0_4_s)
{
	CHECK(close(envelope(-0.1, 5.0), 0.0) && close(envelope(0.0, 5.0), 0.0));
	CHECK(close(envelope(0.075, 5.0), 0.5)); // half way up: smoothstep(0.5) = 0.5
	CHECK(close(envelope(0.15, 5.0), 1.0) && close(envelope(2.0, 5.0), 1.0) && close(envelope(5.0, 5.0), 1.0));
	CHECK(close(envelope(5.2, 5.0), 0.5)); // half way down
	CHECK(close(envelope(5.4, 5.0), 0.0) && close(envelope(9.0, 5.0), 0.0));
	double last = 1.0; // monotonic on the way out
	for (double t = 5.0; t <= 5.45; t += 0.01)
	{
		CHECK(envelope(t, 5.0) <= last + 1e-12);
		last = envelope(t, 5.0);
	}
	CHECK(close(envelope(NAN, 5.0), 0.0) && close(envelope(1.0, NAN), 0.0));
}

TEST(an_effect_ended_during_its_ease_in_eases_out_from_where_it_was)
{
	// ended 0.075 s in: the lower of the two curves, so no jump
	CHECK(close(envelope(0.075, 0.075), 0.5));
	CHECK(envelope(0.1, 0.075) < 0.5 && envelope(0.1, 0.075) > 0.0);
	CHECK(close(envelope(0.475, 0.075), 0.0));
}

TEST(slowmo_gives_cb_slowmo_with_kind_and_seconds_since_start)
{
	SlowMo fx;
	auto u = fx.uniform(10.0, false);
	CHECK(u[0] == 0.0f && u[1] == 0.0f && u[2] == 0.0f && u[3] == 0.0f);
	fx.start(Kind::Sandevistan, 8.0, 10.0);
	u = fx.uniform(11.0, false);
	CHECK(close(u[0], 1.0) && u[1] == 0.0f && close(u[2], 1.0) && u[3] == 0.0f);
	CHECK(fx.uniform(11.0, true)[0] == 0.0f); // a Cyberpunk menu is open: off, but the clock runs on
	CHECK(close(fx.uniform(11.0, true)[2], 1.0));
	fx.start(Kind::Kerenzikov, 2.0, 20.0); // a new one replaces it
	u = fx.uniform(20.5, false);
	CHECK(close(u[0], 1.0) && u[1] == 1.0f && close(u[2], 0.5));
	CHECK(fx.running(22.3) && !fx.running(22.41));
	CHECK(fx.uniform(22.5, false)[0] == 0.0f && fx.uniform(22.5, false)[2] == 0.0f);
}

TEST(slowmo_stop_eases_out_and_clear_is_immediate)
{
	SlowMo fx;
	fx.start(Kind::Sandevistan, 30.0, 0.0);
	fx.stop(1.0); // n"" or 0 seconds
	CHECK(close(fx.uniform(1.2, false)[0], 0.5) && fx.running(1.39) && !fx.running(1.41));
	fx.start(Kind::Sandevistan, 5.0, 2.0);
	fx.start(Kind::Sandevistan, 0.0, 3.0); // zero seconds ends the running one
	CHECK(close(fx.uniform(3.2, false)[0], 0.5));
	fx.start(Kind::Sandevistan, 99.0, 10.0); // clamped to 30 s
	CHECK(fx.running(40.3) && !fx.running(40.41));
	fx.clear(); // Cyberblock off
	CHECK(!fx.running(11.0) && fx.uniform(11.0, false)[0] == 0.0f);
	fx.stop(12.0); // nothing running: nothing happens
	CHECK(!fx.running(12.0));
}

// ---- Phase 8 (megaplan task 8-D1): the Reboot Optics glitch, cb_glitch ----

TEST(envelope_with_its_own_eases)
{
	using cb::screenfx::kGlitchEaseIn;
	using cb::screenfx::kGlitchEaseOut;
	CHECK(close(envelope(kGlitchEaseIn / 2, 3.0, kGlitchEaseIn, kGlitchEaseOut), 0.5));
	CHECK(close(envelope(kGlitchEaseIn, 3.0, kGlitchEaseIn, kGlitchEaseOut), 1.0));
	CHECK(close(envelope(3.0 + kGlitchEaseOut / 2, 3.0, kGlitchEaseIn, kGlitchEaseOut), 0.5));
	CHECK(close(envelope(3.0 + kGlitchEaseOut, 3.0, kGlitchEaseIn, kGlitchEaseOut), 0.0));
	// the two-argument form is the Sandevistan's eases
	CHECK(close(envelope(5.2, 5.0), envelope(5.2, 5.0, cb::screenfx::kEaseIn, cb::screenfx::kEaseOut)));
}

TEST(glitch_gives_cb_glitch_with_seconds_since_start_and_length)
{
	using cb::screenfx::Glitch;
	Glitch fx;
	auto u = fx.uniform(5.0, false);
	CHECK(u[0] == 0.0f && u[1] == 0.0f && u[2] == 0.0f && u[3] == 0.0f && !fx.running(5.0));
	fx.start(4.0, 10.0);
	u = fx.uniform(11.0, false);
	CHECK(close(u[0], 1.0) && close(u[1], 1.0) && close(u[2], 4.0) && u[3] == 0.0f);
	CHECK(fx.uniform(11.0, true)[0] == 0.0f && close(fx.uniform(11.0, true)[1], 1.0)); // a menu: off, the clock runs on
	CHECK(fx.running(14.0 + cb::screenfx::kGlitchEaseOut - 0.01) && !fx.running(14.0 + cb::screenfx::kGlitchEaseOut + 0.01));
	CHECK(fx.uniform(20.0, false)[0] == 0.0f && fx.uniform(20.0, false)[2] == 0.0f);
}

TEST(glitch_overlapping_hacks_extend_it_and_zero_seconds_ends_it)
{
	using cb::screenfx::Glitch;
	Glitch fx;
	fx.start(4.0, 0.0);
	fx.start(1.0, 1.0); // a shorter one while it runs: the running one is not cut short
	CHECK(close(fx.uniform(2.0, false)[2], 4.0));
	fx.start(6.0, 2.0); // a longer one: ends 6 s from now, no second ease-in
	auto u = fx.uniform(2.0, false);
	CHECK(close(u[0], 1.0) && close(u[1], 2.0) && close(u[2], 8.0));
	fx.start(0.0, 3.0); // seconds 0: the hack is cured, it eases out from now
	CHECK(close(fx.uniform(3.0 + cb::screenfx::kGlitchEaseOut / 2, false)[0], 0.5));
	CHECK(!fx.running(3.0 + cb::screenfx::kGlitchEaseOut + 0.01));
	fx.start(99.0, 10.0); // clamped to 10 s
	CHECK(close(fx.uniform(10.5, false)[2], 10.0));
	fx.start(NAN, 11.0); // not a number: ends it like 0
	CHECK(!fx.running(11.0 + cb::screenfx::kGlitchEaseOut + 0.01));
	fx.start(3.0, 30.0); // after it ended: a fresh start, eased in again
	CHECK(close(fx.uniform(30.0, false)[0], 0.0) && close(fx.uniform(30.0, false)[1], 0.0));
	fx.clear();
	CHECK(!fx.running(30.5) && fx.uniform(30.5, false)[0] == 0.0f);
}
