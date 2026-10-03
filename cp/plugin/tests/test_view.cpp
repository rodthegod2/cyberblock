// The on-foot third-person view (re-test 3 plan task D5): the orbit, the boom and `persp`. Pure: the game part
// (view.cpp) is checked in game.
#include "test.hpp"
#include "view.hpp"
#include "world.hpp"
#include <cmath>

namespace
{
	using cb::view::Boom;
	using cb::view::Orbit;
	using nlohmann::json;

	bool close(double a, double b, double tolerance = 1e-9)
	{
		return std::abs(a - b) <= tolerance;
	}
}

TEST(the_orbit_sits_3_2_m_behind_and_0_25_m_above_the_eye)
{
	const Orbit orbit;
	CHECK(close(orbit.distance, 3.2) && close(orbit.height, 0.25) && close(orbit.shoulder, 0.0));
	const cb::Vec3 level = cb::view::desired_local(orbit, 0.0);
	CHECK(close(level.x, 0.0) && close(level.y, -3.2) && close(level.z, 0.25)); // straight back along -Y (V's forward is +Y)
}

TEST(looking_down_lifts_the_camera_and_looking_up_lowers_it)
{
	const Orbit orbit;
	const cb::Vec3 down = cb::view::desired_local(orbit, 30.0); // Minecraft pitch: positive looks down
	CHECK(close(down.y, -3.2 * std::cos(30.0 * 3.14159265358979323846 / 180.0)) && close(down.z, 0.25 + 1.6));
	const cb::Vec3 up = cb::view::desired_local(orbit, -30.0);
	CHECK(close(up.z, 0.25 - 1.6) && close(up.y, down.y));
	const cb::Vec3 straightDown = cb::view::desired_local(orbit, 90.0);
	CHECK(close(straightDown.y, 0.0, 1e-9) && close(straightDown.z, 3.45));
	// the boom keeps its length whatever the pitch (the height is added on top)
	for (double p = -89.0; p <= 89.0; p += 7.0)
	{
		const cb::Vec3 v = cb::view::desired_local(orbit, p);
		CHECK(close(std::hypot(v.y, v.z - orbit.height), orbit.distance));
	}
	const Orbit shoulder{2.0, 0.1, 0.5};
	const cb::Vec3 s = cb::view::desired_local(shoulder, 0.0, 1.5);
	CHECK(close(s.x, 0.5) && close(s.y, -1.5) && close(s.z, 0.1));
}

TEST(a_wall_shortens_the_boom_to_the_hit_minus_0_25_m_but_never_under_0_4_m)
{
	CHECK(close(cb::view::collision_distance(3.0), 2.75));
	CHECK(close(cb::view::collision_distance(0.65), 0.4));
	CHECK(close(cb::view::collision_distance(0.5), 0.4));
	CHECK(close(cb::view::collision_distance(0.0), 0.4));
}

TEST(the_boom_snaps_in_at_once_and_grows_back_smoothly)
{
	Boom boom;
	boom.reset(3.2);
	CHECK(close(boom.step(1.0, 1.0 / 60.0), 1.0)); // a wall: in at once
	double last = boom.distance();
	bool grew = true;
	for (int i = 0; i < 30; ++i) // half a second at 60 fps
	{
		const double d = boom.step(3.2, 1.0 / 60.0);
		grew = grew && d >= last && d <= 3.2;
		last = d;
	}
	CHECK(grew && last > 2.9 && last <= 3.2); // most of the way back, never past the target
	for (int i = 0; i < 120; ++i)
		boom.step(3.2, 1.0 / 60.0);
	CHECK(close(boom.distance(), 3.2, 1e-3));
	CHECK(close(boom.step(2.0, 0.0), boom.distance())); // no time passed: no change
	CHECK(close(boom.step(NAN, 0.016), boom.distance()));
}

TEST(persp_is_first_or_back_and_anything_else_is_invalid)
{
	CHECK(cb::world::parse_persp(json{{"t", "persp"}, {"mode", "back"}}) == true);
	CHECK(cb::world::parse_persp(json{{"t", "persp"}, {"mode", "first"}}) == false);
	CHECK(!cb::world::parse_persp(json{{"t", "persp"}, {"mode", "front"}}));
	CHECK(!cb::world::parse_persp(json{{"t", "persp"}, {"mode", 1}}));
	CHECK(!cb::world::parse_persp(json{{"t", "persp"}}));
}

TEST(persp_back_is_kept_until_first_a_hello_or_a_link_drop)
{
	cb::world::on_minecraft_message(json{{"t", "hello"}, {"proto", 1}});
	CHECK(!cb::world::third_person_wanted());
	cb::world::on_minecraft_message(json{{"t", "persp"}, {"mode", "back"}});
	CHECK(cb::world::third_person_wanted());
	cb::world::on_minecraft_message(json{{"t", "persp"}, {"mode", "sideways"}}); // invalid: the old mode stays
	CHECK(cb::world::third_person_wanted());
	cb::world::on_minecraft_message(json{{"t", "persp"}, {"mode", "first"}});
	CHECK(!cb::world::third_person_wanted());
	cb::world::on_minecraft_message(json{{"t", "persp"}, {"mode", "back"}});
	cb::world::on_minecraft_message(json{{"t", "hello"}, {"proto", 1}});
	CHECK(!cb::world::third_person_wanted());
	cb::world::on_minecraft_message(json{{"t", "persp"}, {"mode", "back"}});
	cb::world::on_link_dropped();
	CHECK(!cb::world::third_person_wanted());
}

// ---- re-test 3 task D6: status.pause ----------------------------------------------------------------------------------

TEST(minecraft_pause_follows_status_pause_and_resets_on_hello_and_drop)
{
	cb::world::on_minecraft_message(json{{"t", "hello"}, {"proto", 1}});
	CHECK(!cb::world::minecraft_pause());
	cb::world::on_minecraft_message(json{{"t", "status"}, {"screen", true}, {"pause", true}});
	CHECK(cb::world::minecraft_pause());
	cb::world::on_minecraft_message(json{{"t", "status"}, {"screen", true}}); // an older build: no pause field
	CHECK(!cb::world::minecraft_pause());
	cb::world::on_minecraft_message(json{{"t", "status"}, {"pause", true}});
	cb::world::on_minecraft_message(json{{"t", "status"}, {"pause", "yes"}}); // not a boolean: not paused
	CHECK(!cb::world::minecraft_pause());
	cb::world::on_minecraft_message(json{{"t", "status"}, {"pause", true}});
	cb::world::on_minecraft_message(json{{"t", "hello"}, {"proto", 1}});
	CHECK(!cb::world::minecraft_pause());
	cb::world::on_minecraft_message(json{{"t", "status"}, {"pause", true}});
	cb::world::on_link_dropped();
	CHECK(!cb::world::minecraft_pause());
}
