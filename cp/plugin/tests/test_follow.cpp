// The per-frame flight follower (plan 2026-10-03-retest2 task D4): one test per rule. Pure: frames are fed by hand.
#include "follow.hpp"
#include "test.hpp"
#include <cmath>
#include <string>

namespace
{
	using cb::follow::Act;
	using cb::follow::Follower;
	using cb::follow::In;
	using cb::follow::Out;

	constexpr double kFrame = 1.0 / 60.0;

	/// A frame with the gate open, no menu, Steve flying at `target` and V's feet at `feet`.
	In flying(double now, cb::Vec3 target, cb::Vec3 feet = {0, 0, 0})
	{
		In in;
		in.gate = true;
		in.active = true;
		in.target = target;
		in.feet = feet;
		in.now = now;
		return in;
	}

	bool at(const cb::Vec3 &a, double x, double y, double z)
	{
		return std::abs(a.x - x) < 1e-9 && std::abs(a.y - y) < 1e-9 && std::abs(a.z - z) < 1e-9;
	}

	bool says(const Out &out, const char *text)
	{
		return out.log.find(text) != std::string::npos;
	}

	/// Starts a glide at `now` and follows it for `frames` frames along +x (1 m per frame), applying every move.
	double follow_for(Follower &f, double now, int frames, bool launch = false)
	{
		for (int i = 0; i < frames; ++i, now += kFrame)
		{
			In in = flying(now, {double(i), 0, 100});
			in.launch = launch;
			const Out out = f.step(in);
			if (out.act == Act::Move)
				f.applied(out.to);
		}
		return now;
	}
}

TEST(follow_starts_on_an_active_flight_with_the_gate_open)
{
	Follower f;
	CHECK(!f.driving());
	In in = flying(10.0, {1, 2, 3});
	in.gate = false;
	CHECK(f.step(in).act == Act::None && !f.driving()); // not allowed: nothing
	in = flying(10.0, {1, 2, 3});
	in.menu = true;
	CHECK(f.step(in).act == Act::None && !f.driving()); // a menu: nothing starts
	in = flying(10.0, {1, 2, 3});
	in.active = false;
	CHECK(f.step(in).act == Act::None && !f.driving()); // no flight
	const Out start = f.step(flying(10.0, {1, 2, 3}));
	CHECK(start.act == Act::Move && at(start.to, 1, 2, 3) && says(start, "flight: start (glide)") && f.driving());
	Follower g;
	In launch = flying(0.0, {0, 0, 0});
	launch.launch = true;
	CHECK(says(g.step(launch), "flight: start (launch)"));
}

TEST(follow_moves_to_every_target_while_active)
{
	Follower f;
	f.step(flying(0.0, {0, 0, 100}));
	for (int i = 1; i < 10; ++i)
	{
		const Out out = f.step(flying(i * kFrame, {double(i), 0, 100}));
		CHECK(out.act == Act::Move && at(out.to, i, 0, 100) && out.log.empty());
		f.applied(out.to);
	}
	CHECK(f.driving() && f.seconds_since_end(1.0) == 0.0);
	CHECK(f.summary().frames == 10 && std::abs(f.summary().maxStep - 1.0) < 1e-9);
}

TEST(follow_a_5_s_menu_freezes_everything_then_samples_come_back_with_no_ground)
{
	Follower f;
	double now = follow_for(f, 0.0, 30);
	// Minecraft goes silent: V is held where it was last put
	In silent = flying(now, {}, {});
	silent.active = false;
	silent.timedOut = true;
	const Out hold = f.step(silent);
	CHECK(hold.act == Act::Move && at(hold.to, 29, 0, 100) && says(hold, "flight: no samples, holding V"));
	for (int i = 0; i < 12; ++i) // 0.2 s of the 1 s hold
	{
		silent.now = now += kFrame;
		CHECK(f.step(silent).act == Act::Move);
	}
	// a 5 s menu: nothing happens and the hold timer does not run
	for (double t = 0; t < 5.0; t += kFrame)
	{
		In menu = silent;
		menu.menu = true;
		menu.now = now += kFrame;
		CHECK(f.step(menu).act == Act::None);
		CHECK(f.driving());
	}
	// the menu closes, still silent: the hold goes on (0.2 s used of 1 s), no Ground
	silent.now = now += kFrame;
	CHECK(f.step(silent).act == Act::Move);
	// samples are back: following again
	const Out back = f.step(flying(now += kFrame, {40, 0, 100}));
	CHECK(back.act == Act::Move && at(back.to, 40, 0, 100) && says(back, "flight: samples back"));
	CHECK(f.step(flying(now += kFrame, {41, 0, 100})).log.empty());
}

TEST(follow_a_menu_freezes_the_flight_clock_for_the_quick_landing_rule)
{
	Follower f;
	f.step(flying(0.0, {0, 0, 100}));
	for (double now = kFrame; now < 3.0; now += kFrame)
	{
		In menu = flying(now, {0, 0, 100});
		menu.menu = true;
		CHECK(f.step(menu).act == Act::None);
	}
	// 3 s passed, but only one frame of flight: a landing now is still a quick one
	In land = flying(3.0, {10, 0, 90}, {0, 0, 100});
	land.active = false;
	CHECK(f.step(land).act == Act::Stop);
}

TEST(follow_timeout_holds_v_for_1_s_then_puts_it_on_the_ground)
{
	Follower f;
	double now = follow_for(f, 0.0, 10);
	In silent = flying(now, {99, 99, 99});
	silent.active = false;
	silent.timedOut = true;
	const Out first = f.step(silent);
	CHECK(first.act == Act::Move && at(first.to, 9, 0, 100) && says(first, "holding V"));
	for (silent.now = now + kFrame; silent.now < now + 0.99; silent.now += kFrame)
	{
		const Out hold = f.step(silent);
		CHECK(hold.act == Act::Move && at(hold.to, 9, 0, 100) && hold.log.empty());
	}
	silent.now = now + 1.001;
	const Out ground = f.step(silent);
	CHECK(ground.act == Act::Ground && at(ground.to, 9, 0, 100) && says(ground, "flight: no samples for 1 s, V put on the ground"));
	CHECK(!f.driving() && f.step(silent).act == Act::None);
	CHECK(std::abs(f.seconds_since_end(silent.now + 0.5) - 0.5) < 1e-9);
}

TEST(follow_gate_closing_stops_without_a_move)
{
	Follower f;
	double now = follow_for(f, 0.0, 10);
	In closed = flying(now, {50, 0, 100});
	closed.gate = false;
	const Out stop = f.step(closed);
	CHECK(stop.act == Act::Stop && says(stop, "flight: stopped, V is held, in a vehicle or Cyberblock is off"));
	CHECK(!f.driving());
	CHECK(f.step(closed).act == Act::None); // stays stopped while the gate is closed
	// also while holding through a timeout
	Follower g;
	now = follow_for(g, 0.0, 10);
	In silent = flying(now, {});
	silent.active = false;
	silent.timedOut = true;
	g.step(silent);
	silent.gate = false;
	silent.now += kFrame;
	CHECK(g.step(silent).act == Act::Stop && !g.driving());
}

TEST(follow_quick_or_near_landing_leaves_v_where_it_is)
{
	// touchdown within 0.3 s of the start (a hop): Stop
	Follower f;
	f.step(flying(0.0, {0, 0, 100}));
	In land = flying(0.25, {30, 0, 60}, {0, 0, 100});
	land.active = false;
	const Out quick = f.step(land);
	CHECK(quick.act == Act::Stop && says(quick, "flight: over, V stays where it is") && !f.driving());
	// V is already within 1.5 m of the landing spot: Stop
	Follower g;
	double now = follow_for(g, 0.0, 60);
	In near = flying(now, {60.0, 1.0, 100.5}, {59.5, 0.5, 100});
	near.active = false;
	CHECK(g.step(near).act == Act::Stop);
}

TEST(follow_normal_landing_lands_v_on_the_spot)
{
	Follower f;
	double now = follow_for(f, 0.0, 120, true); // 2 s of a launch
	In land = flying(now, {130, 0, 64}, {119, 0, 100});
	land.active = false;
	const Out out = f.step(land);
	CHECK(out.act == Act::Land && at(out.to, 130, 0, 64) && says(out, "flight: landed (launch) after 2."));
	CHECK(!f.driving() && f.seconds_since_end(now) == 0.0 && std::abs(f.seconds_since_end(now + 1.25) - 1.25) < 1e-9);
	CHECK(f.step(land).act == Act::None); // the landing is handled once
	// the next flight starts over
	const Out again = f.step(flying(now + 2.0, {0, 0, 0}));
	CHECK(again.act == Act::Move && says(again, "flight: start (glide)"));
}

TEST(follow_seconds_since_end_is_999_before_any_flight)
{
	Follower f;
	CHECK(f.seconds_since_end(0.0) == 999.0 && f.seconds_since_end(12345.0) == 999.0);
	f.step(flying(5.0, {0, 0, 0}));
	CHECK(f.seconds_since_end(5.0) == 0.0);
}

TEST(follow_a_menu_after_landing_freezes_seconds_since_end)
{
	Follower f;
	double now = follow_for(f, 0.0, 60);
	In land = flying(now, {80, 0, 64}, {0, 0, 100});
	land.active = false;
	CHECK(f.step(land).act == Act::Land);
	In menu = land;
	menu.menu = true;
	for (double t = 0; t < 4.0; t += kFrame)
	{
		menu.now = now += kFrame;
		f.step(menu);
	}
	CHECK(f.seconds_since_end(now) < 0.05); // the fall-damage grace does not run out behind a menu
}

// ---- the drive's geometry (task D5), pure ------------------------------------------------------------------------------

TEST(stop_short_stops_the_margin_before_the_hit)
{
	using cb::follow::stop_short;
	CHECK(at(stop_short({0, 0, 0}, {10, 0, 0}, 4.0, 0.3), 3.7, 0, 0));
	CHECK(at(stop_short({0, 0, 0}, {0, 3, 4}, 5.0, 0.3), 0, 3 * 0.94, 4 * 0.94)); // along the segment, any direction
	CHECK(at(stop_short({1, 1, 1}, {9, 1, 1}, 0.2, 0.3), 1, 1, 1));               // closer than the margin: stay put
	CHECK(at(stop_short({1, 1, 1}, {9, 1, 1}, 50.0, 0.3), 9, 1, 1));              // never past the target
	CHECK(at(stop_short({1, 1, 1}, {1, 1, 1}, 0.0, 0.3), 1, 1, 1));               // no movement
}

TEST(snap_landing_lifts_out_of_the_ground_and_drops_onto_it_from_over_1_m)
{
	using cb::follow::snap_landing;
	CHECK(at(snap_landing({5, 6, 10.0}, 10.4), 5, 6, 10.4));  // the spot is inside the ground: lifted onto it
	CHECK(at(snap_landing({5, 6, 10.0}, 8.5), 5, 6, 8.5));    // 1.5 m up: put on the ground
	CHECK(at(snap_landing({5, 6, 10.0}, 9.5), 5, 6, 10.0));   // 0.5 m up: a short drop is fine
	CHECK(at(snap_landing({5, 6, 10.0}, 10.0), 5, 6, 10.0));  // on it
	CHECK(at(snap_landing({5, 6, 10.0}, std::nullopt), 5, 6, 10.0)); // no ground found: the spot
}
