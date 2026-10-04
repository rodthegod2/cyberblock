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

// ---- Steve's body for the re-projection (in-game 2026-10-03: third person lagged behind) --------------------------------

namespace
{
	using cb::view::BodyPose;
	using cb::view::Capsule;

	/// Cyberblock.fx's in_body, on the CPU.
	bool inside(const Capsule &c, cb::Vec3 p)
	{
		const cb::Vec3 ab{c.b.x - c.a.x, c.b.y - c.a.y, c.b.z - c.a.z};
		const double len2 = std::max(ab.x * ab.x + ab.y * ab.y + ab.z * ab.z, 1e-6);
		const double t = std::clamp(((p.x - c.a.x) * ab.x + (p.y - c.a.y) * ab.y + (p.z - c.a.z) * ab.z) / len2, 0.0, 1.0);
		return std::hypot(p.x - (c.a.x + ab.x * t), p.y - (c.a.y + ab.y * t), p.z - (c.a.z + ab.z * t)) <= c.radius;
	}
}

TEST(a_standing_body_holds_steve_from_boots_to_hair_but_not_the_ground_around_him)
{
	const Capsule c = cb::view::body_capsule(BodyPose::Standing, {10, 64, -5}, 30.0, 10.0);
	CHECK(c.valid);
	CHECK(inside(c, {10.1, 64.05, -5}));  // a boot
	CHECK(inside(c, {10, 65.85, -5}));    // the top of his head
	CHECK(inside(c, {10.45, 65.3, -5}));  // an arm swung out
	CHECK(!inside(c, {10, 63.95, -5}));   // the block he stands on
	CHECK(!inside(c, {10.6, 64.0, -5}));  // the ground beside him
	CHECK(!inside(c, {10, 66.0, -5}));    // above him
}

TEST(a_gliding_body_lies_along_the_look_from_the_feet)
{
	// Minecraft yaw 0 looks along +z, yaw 90 along -x; pitch 90 straight down
	const Capsule level = cb::view::body_capsule(BodyPose::Gliding, {0, 100, 0}, 0.0, 0.0);
	CHECK(close(level.a.z, 0.35) && close(level.b.z, 1.45) && close(level.a.y, 100.0) && close(level.radius, 0.75));
	CHECK(inside(level, {0, 100, 1.9}) && inside(level, {0.7, 100, 1.0}) && !inside(level, {0, 100, -0.5}));
	const Capsule west = cb::view::body_capsule(BodyPose::Gliding, {0, 100, 0}, 90.0, 0.0);
	CHECK(close(west.b.x, -1.45, 1e-9) && close(west.b.z, 0.0, 1e-9));
	const Capsule dive = cb::view::body_capsule(BodyPose::Gliding, {0, 100, 0}, 0.0, 90.0);
	CHECK(close(dive.b.y, 98.55, 1e-9) && close(dive.b.z, 0.0, 1e-9));
}

TEST(a_seated_body_reaches_from_the_seat_to_the_head_and_none_or_garbage_is_no_body)
{
	const Capsule seat = cb::view::body_capsule(BodyPose::Seated, {0, 10, 0}, 0.0, 0.0); // cam.pl is V's hips
	CHECK(inside(seat, {0, 10, 0}) && inside(seat, {0, 11.0, 0}) && inside(seat, {0, 9.4, 0.3}) && !inside(seat, {0, 11.6, 0}));
	CHECK(!cb::view::body_capsule(BodyPose::None, {0, 10, 0}, 0.0, 0.0).valid);
	CHECK(!cb::view::body_capsule(BodyPose::Standing, {NAN, 10, 0}, 0.0, 0.0).valid);
	CHECK(!cb::view::body_capsule(BodyPose::Gliding, {0, 10, 0}, NAN, 0.0).valid);
}

TEST(the_body_ring_finds_the_cam_a_minecraft_frame_was_rendered_for)
{
	cb::view::BodyRing ring;
	for (int64_t f = 0; f < 100; ++f)
		ring.put({f, {double(f), 0, 0}, cb::view::body_capsule(BodyPose::Standing, {double(f), 0, 0}, 0, 0)});
	const cb::view::Body found = ring.find(80);
	CHECK(found.frame == 80 && close(found.feet.x, 80.0) && found.capsule.valid);
	CHECK(ring.find(99).frame == 99 && ring.find(36).frame == 36);
	CHECK(ring.find(35).frame == -1 && !ring.find(35).capsule.valid); // older than the last 64
	CHECK(ring.find(100).frame == -1 && ring.find(-1).frame == -1);
}
