// Layers 3-4 in the DLL: the `mobs`, `flight` and `proj` messages kept as state (plan 2026-10-03 section 1.3): parsing and
// conversion, staleness, flight extrapolation, and the snapshots that natives index into.
#include "snapshots.hpp"
#include "natives.hpp"
#include "test.hpp"
#include "world.hpp"
#include <chrono>
#include <cmath>
#include <algorithm>
#include <cstdint>
#include <limits>
#include <optional>
#include <string>
#include <vector>

namespace
{
	using nlohmann::json;
	using namespace cb::snap;
	using namespace std::chrono_literals;

	constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();
	constexpr double kInf = std::numeric_limits<double>::infinity();

	bool approx(double a, double b, double eps = 1e-9)
	{
		return std::abs(a - b) <= eps;
	}

	bool approx(const cb::Vec3 &a, double x, double y, double z, double eps = 1e-9)
	{
		return approx(a.x, x, eps) && approx(a.y, y, eps) && approx(a.z, z, eps);
	}

	/// One mob entry as JSON text; `fields` replaces the defaults, e.g. R"("hp":-1)".
	std::string mob_entry(const std::string &fields = {})
	{
		json m = {{"id", 5}, {"kind", "zombie"}, {"p", {10, 64, -20}}, {"yaw", 0}, {"w", 0.6}, {"h", 1.95}, {"hp", 20}, {"maxHp", 20}};
		if (!fields.empty())
		{
			const json changes = json::parse("{" + fields + "}"); // a named object: items() must not outlive it
			for (const auto &[key, value] : changes.items())
				m[key] = value;
		}
		return m.dump();
	}

	json mobs_message(std::initializer_list<std::string> entries)
	{
		std::string text = R"({"t":"mobs","list":[)";
		bool first = true;
		for (const std::string &e : entries)
		{
			text += (first ? "" : ",") + e;
			first = false;
		}
		return json::parse(text + "]}");
	}

	json proj_entry(const json &changes = json::object())
	{
		json p = {{"id", 9}, {"p", {1, 70, -2}}, {"v", {3, 4, 5}}, {"g", 20}};
		for (const auto &[key, value] : changes.items())
			p[key] = value;
		return p;
	}

	json flight_message(bool active)
	{
		return {{"t", "flight"}, {"active", active}, {"p", {10, 100, -20}}, {"v", {3, -1, 4}}, {"yaw", 0}, {"pitch", 30}};
	}

	FlightSample sample(bool active, cb::Vec3 pos, cb::Vec3 vel = {}, double yaw = 0, double pitch = 0)
	{
		FlightSample s;
		s.active = active;
		s.pos = pos;
		s.velocity = vel;
		s.yaw = yaw;
		s.pitch = pitch;
		return s;
	}
}

// ---- parse_mobs --------------------------------------------------------------------------------------------------------

TEST(a_mob_entry_is_converted_to_cyberpunk_space)
{
	const ListParse<Mob> p = parse_mobs(mobs_message({mob_entry()}));
	CHECK(p.valid && p.dropped == 0 && p.items.size() == 1);
	const Mob &m = p.items[0];
	CHECK(m.id == 5 && m.kind == "zombie");
	CHECK(approx(m.feet, 10, 20, 64)); // mc (10, 64, -20) -> cp (x, -z, y)
	CHECK(approx(m.yaw, 180));         // Minecraft yaw 0 faces +Z = Cyberpunk -Y = yaw 180
	CHECK(approx(m.width, 0.6) && approx(m.height, 1.95) && approx(m.hp, 20) && approx(m.maxHp, 20));
	CHECK(approx(parse_mobs(mobs_message({mob_entry(R"("yaw":-90)")})).items[0].yaw, -90));
}

TEST(mob_limits_are_inclusive)
{
	const ListParse<Mob> p = parse_mobs(mobs_message({mob_entry(R"("w":16,"h":16,"hp":2048,"maxHp":2048,"id":2147483647)"),
													   mob_entry(R"("w":0,"h":0,"hp":0,"maxHp":0,"id":0)"),
													   mob_entry(R"("kind":"zombified_piglin_1")"),
													   mob_entry(R"("p":[29999999,-64,-29999999])")}));
	CHECK(p.valid && p.dropped == 0 && p.items.size() == 4);
}

TEST(mob_kinds_are_lowercase_words_up_to_32)
{
	const std::string longest(32, 'a');
	CHECK(parse_mobs(mobs_message({mob_entry(R"("kind":")" + longest + R"(")")})).items.size() == 1);
	for (const char *kind : {"", "Zombie", "zombie-villager", "zombie villager", "minecraft:zombie", "z\xc3\xb6mbie"})
	{
		const ListParse<Mob> p = parse_mobs(mobs_message({mob_entry("\"kind\":" + json(kind).dump())}));
		CHECK(p.valid && p.items.empty() && p.dropped == 1);
	}
	CHECK(parse_mobs(mobs_message({mob_entry(R"("kind":")" + std::string(33, 'a') + R"(")")})).dropped == 1);
}

TEST(a_bad_mob_entry_is_dropped_and_counted_the_rest_is_kept)
{
	const ListParse<Mob> p = parse_mobs(mobs_message({
		mob_entry(R"("id":1)"),
		mob_entry(R"("id":-1)"),
		mob_entry(R"("id":1.5)"),
		mob_entry(R"("hp":-0.5)"),
		mob_entry(R"("hp":2048.5)"),
		mob_entry(R"("maxHp":3000)"),
		mob_entry(R"("w":16.5)"),
		mob_entry(R"("h":-1)"),
		mob_entry(R"("p":[0,0])"),
		mob_entry(R"("p":[0,0,3e7])"),
		mob_entry(R"("yaw":"north")"),
		"7",
		mob_entry(R"("id":2)"),
	}));
	CHECK(p.valid && p.dropped == 11 && !p.reason.empty());
	CHECK(p.items.size() == 2 && p.items[0].id == 1 && p.items[1].id == 2);
}

TEST(a_mob_entry_missing_a_field_is_dropped)
{
	for (const char *key : {"id", "kind", "p", "yaw", "w", "h", "hp", "maxHp"})
	{
		json entry = json::parse(mob_entry());
		entry.erase(key);
		const ListParse<Mob> p = parse_mobs(json{{"t", "mobs"}, {"list", json::array({entry})}});
		CHECK(p.valid && p.items.empty() && p.dropped == 1);
	}
}

TEST(non_finite_mob_numbers_are_dropped)
{
	json entry = json::parse(mob_entry());
	entry["hp"] = kNaN;
	CHECK(parse_mobs(json{{"t", "mobs"}, {"list", json::array({entry})}}).dropped == 1);
	entry = json::parse(mob_entry());
	entry["p"] = json::array({0, kInf, 0});
	CHECK(parse_mobs(json{{"t", "mobs"}, {"list", json::array({entry})}}).dropped == 1);
	entry = json::parse(mob_entry());
	entry["yaw"] = kNaN;
	CHECK(parse_mobs(json{{"t", "mobs"}, {"list", json::array({entry})}}).dropped == 1);
}

TEST(at_most_12_mobs_are_kept)
{
	json list = json::array();
	for (int i = 0; i < 15; ++i)
		list.push_back(json::parse(mob_entry("\"id\":" + std::to_string(i))));
	const ListParse<Mob> p = parse_mobs(json{{"t", "mobs"}, {"list", list}});
	CHECK(p.valid && p.items.size() == 12 && p.dropped == 3);
	CHECK(p.items.front().id == 0 && p.items.back().id == 11); // nearest first: the farthest are the ones dropped
}

TEST(a_mobs_message_without_a_list_is_invalid_and_an_empty_list_is_fine)
{
	CHECK(!parse_mobs(json::parse(R"({"t":"mobs"})")).valid);
	CHECK(!parse_mobs(json::parse(R"({"t":"mobs","list":{"id":1}})")).valid);
	CHECK(!parse_mobs(json::parse(R"({"t":"mobs","list":"zombie"})")).valid);
	CHECK(!parse_mobs(json::parse(R"({"t":"mobs"})")).reason.empty());
	const ListParse<Mob> empty = parse_mobs(json::parse(R"({"t":"mobs","list":[]})"));
	CHECK(empty.valid && empty.items.empty() && empty.dropped == 0);
}

// ---- parse_proj --------------------------------------------------------------------------------------------------------

TEST(a_projectile_is_converted_to_cyberpunk_space)
{
	const ListParse<Projectile> p = parse_proj(json{{"t", "proj"}, {"list", json::array({proj_entry()})}});
	CHECK(p.valid && p.dropped == 0 && p.items.size() == 1);
	const Projectile &e = p.items[0];
	CHECK(e.id == 9);
	CHECK(approx(e.pos, 1, 2, 70));        // position: (x, -z, y)
	CHECK(approx(e.velocity, 3, -5, 4));   // velocity: the same map, no offset
	CHECK(approx(e.gravity, 20));
}

TEST(projectile_limits)
{
	json list = json::array({proj_entry({{"v", {200, 0, 0}}}), proj_entry({{"v", {0, -200, 0}}}), proj_entry({{"v", {0, 0, 0}}}),
							 proj_entry({{"g", 0}}), proj_entry({{"g", 40}}), proj_entry({{"id", 0}}),
							 proj_entry({{"v", {115, 115, 115}}})}); // |v| = 199.2
	const ListParse<Projectile> ok = parse_proj(json{{"t", "proj"}, {"list", list}});
	CHECK(ok.valid && ok.dropped == 0 && ok.items.size() == 7);
	list = json::array({proj_entry({{"v", {150, 150, 0}}}),     // |v| = 212 > 200: the limit is on the speed, not per axis
						proj_entry({{"v", {201, 0, 0}}}), proj_entry({{"g", 40.5}}), proj_entry({{"g", -1}}),
						proj_entry({{"id", -1}}), proj_entry({{"p", {0, 0}}}), proj_entry({{"p", {3e7, 0, 0}}}),
						proj_entry({{"v", {kInf, 0, 0}}}), proj_entry({{"g", kNaN}}), json(3)});
	const ListParse<Projectile> bad = parse_proj(json{{"t", "proj"}, {"list", list}});
	CHECK(bad.valid && bad.items.empty() && bad.dropped == 10);
	for (const char *key : {"id", "p", "v", "g"})
	{
		json entry = proj_entry();
		entry.erase(key);
		CHECK(parse_proj(json{{"t", "proj"}, {"list", json::array({entry})}}).dropped == 1);
	}
}

TEST(at_most_16_projectiles_are_kept)
{
	json list = json::array();
	for (int i = 0; i < 20; ++i)
		list.push_back(proj_entry({{"id", i}}));
	const ListParse<Projectile> p = parse_proj(json{{"t", "proj"}, {"list", list}});
	CHECK(p.valid && p.items.size() == 16 && p.dropped == 4);
	CHECK(parse_proj(json::parse(R"({"t":"proj","list":[]})")).valid);
	CHECK(!parse_proj(json::parse(R"({"t":"proj"})")).valid);
}

// ---- parse_flight ------------------------------------------------------------------------------------------------------

TEST(an_active_flight_message_is_converted)
{
	std::string reason;
	const auto s = parse_flight(flight_message(true), reason);
	CHECK(s.has_value() && s->active);
	CHECK(approx(s->pos, 10, 20, 100));       // mc (10, 100, -20) -> cp (10, 20, 100)
	CHECK(approx(s->velocity, 3, -4, -1));    // mc (3, -1, 4) -> cp (3, -4, -1)
	CHECK(approx(s->yaw, 180));               // Minecraft yaw 0 -> Cyberpunk 180
	CHECK(approx(s->pitch, 30));              // pitch is Minecraft's both ways
}

TEST(a_touchdown_message_only_needs_the_landing_spot)
{
	std::string reason;
	const auto s = parse_flight(json::parse(R"({"t":"flight","active":false,"p":[1,64,-2]})"), reason);
	CHECK(s.has_value() && !s->active && approx(s->pos, 1, 2, 64) && approx(s->velocity, 0, 0, 0));
	CHECK(std::isnan(s->yaw) && std::isnan(s->pitch)); // "not sent": Flight keeps the look it had
	const auto full = parse_flight(flight_message(false), reason);
	CHECK(full.has_value() && !full->active && approx(full->yaw, 180) && approx(full->pitch, 30));
}

TEST(bad_flight_messages_are_rejected_with_a_reason)
{
	std::string reason;
	for (const char *text : {R"({"t":"flight","p":[0,0,0],"v":[0,0,0],"yaw":0,"pitch":0})",                       // no active
							 R"({"t":"flight","active":1,"p":[0,0,0],"v":[0,0,0],"yaw":0,"pitch":0})",            // not a bool
							 R"({"t":"flight","active":true,"v":[0,0,0],"yaw":0,"pitch":0})",                     // no p
							 R"({"t":"flight","active":true,"p":[0,0],"v":[0,0,0],"yaw":0,"pitch":0})",
							 R"({"t":"flight","active":true,"p":[0,0,3e7],"v":[0,0,0],"yaw":0,"pitch":0})",
							 R"({"t":"flight","active":true,"p":[0,0,0],"yaw":0,"pitch":0})",                     // flying needs v
							 R"({"t":"flight","active":true,"p":[0,0,0],"v":[0,0,0],"pitch":0})",                 // and yaw
							 R"({"t":"flight","active":true,"p":[0,0,0],"v":[0,0,0],"yaw":0})",                   // and pitch
							 R"({"t":"flight","active":true,"p":[0,0,0],"v":[150,150,0],"yaw":0,"pitch":0})",     // |v| > 200
							 R"({"t":"flight","active":true,"p":[0,0,0],"v":[0,0,0],"yaw":"0","pitch":0})",
							 R"({"t":"flight","active":false,"p":[0,0,0],"v":[500,0,0]})"})                      // present but bad
	{
		reason.clear();
		CHECK(!parse_flight(json::parse(text), reason).has_value());
		CHECK(!reason.empty());
	}
	json inf_v = flight_message(true);
	inf_v["v"] = json::array({kInf, 0, 0});
	json nan_yaw = flight_message(true);
	nan_yaw["yaw"] = kNaN;
	CHECK(!parse_flight(inf_v, reason).has_value() && !parse_flight(nan_yaw, reason).has_value());
}

TEST(flight_speed_limit_is_inclusive_and_pitch_is_clamped)
{
	std::string reason;
	json m = flight_message(true);
	m["v"] = json::array({0, 200, 0});
	CHECK(parse_flight(m, reason).has_value());
	m["v"] = json::array({0, 200.01, 0});
	CHECK(!parse_flight(m, reason).has_value());
	m = flight_message(true);
	m["pitch"] = 95;
	CHECK(approx(parse_flight(m, reason)->pitch, 90));
	m["pitch"] = -100;
	CHECK(approx(parse_flight(m, reason)->pitch, -90));
}

// ---- ListState: snapshots and staleness --------------------------------------------------------------------------------

TEST(a_snapshot_is_isolated_from_later_lists)
{
	ListState<Mob> state(2s);
	const Clock::time_point t0 = Clock::now();
	Mob a;
	a.id = 1;
	Mob b;
	b.id = 2;
	state.set({a, a}, t0);
	CHECK(state.snapshot(t0) == 2);
	state.set({b, b, b}, t0 + 100ms); // a new message does not change the snapshot already taken
	CHECK(state.at(0)->id == 1 && state.at(1)->id == 1 && !state.at(2));
	CHECK(state.snapshot(t0 + 100ms) == 3);
	CHECK(state.at(2)->id == 2);
}

TEST(snapshot_indices_out_of_range_are_empty)
{
	ListState<Mob> state(2s);
	CHECK(!state.at(0) && !state.at(-1)); // before any snapshot
	const Clock::time_point t0 = Clock::now();
	state.set({Mob{}, Mob{}}, t0);
	CHECK(!state.at(0)); // set() alone takes no snapshot
	CHECK(state.snapshot(t0) == 2);
	CHECK(state.at(0) && state.at(1) && !state.at(2) && !state.at(-1) && !state.at(1000000));
}

TEST(a_list_goes_stale_after_its_max_age)
{
	ListState<Mob> mobsState(2s);
	const Clock::time_point t0 = Clock::now();
	mobsState.set({Mob{}}, t0);
	CHECK(mobsState.snapshot(t0 + 2000ms) == 1);  // exactly 2 s old: still there
	CHECK(mobsState.snapshot(t0 + 2001ms) == 0);  // older: gone
	ListState<Projectile> projState(500ms);
	projState.set({Projectile{}, Projectile{}}, t0);
	CHECK(projState.snapshot(t0 + 500ms) == 2);
	CHECK(projState.snapshot(t0 + 501ms) == 0);
	projState.set({Projectile{}}, t0 + 600ms); // a fresh list revives it
	CHECK(projState.snapshot(t0 + 700ms) == 1);
}

TEST(clear_forgets_the_latest_list_but_not_the_snapshot_taken)
{
	ListState<Mob> state(2s);
	const Clock::time_point t0 = Clock::now();
	state.set({Mob{}}, t0);
	CHECK(state.snapshot(t0) == 1);
	state.clear();
	CHECK(state.at(0).has_value());
	CHECK(state.snapshot(t0) == 0 && !state.at(0));
	CHECK(state.snapshot(t0) == 0); // nothing was ever set on a fresh state either
	CHECK(ListState<Mob>(2s).snapshot(Clock::now()) == 0);
}

TEST(an_empty_list_is_a_list_too)
{
	ListState<Mob> state(2s);
	const Clock::time_point t0 = Clock::now();
	state.set({Mob{}}, t0);
	state.set({}, t0 + 10ms); // "one empty list after the last one goes"
	CHECK(state.snapshot(t0 + 20ms) == 0);
}

// ---- Flight ------------------------------------------------------------------------------------------------------------

TEST(flight_starts_inactive)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	CHECK(!f.active(t0) && f.seq(t0) == 0);
	CHECK(approx(f.pos(t0), 0, 0, 0) && approx(f.velocity(t0), 0, 0, 0));
}

TEST(flight_position_is_extrapolated_by_velocity_times_the_age_up_to_one_minecraft_tick)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	f.update(sample(true, {0, 0, 100}, {10, 0, -5}, 30, 12), t0);
	CHECK(f.active(t0) && f.seq(t0) == 1);
	CHECK(approx(f.pos(t0), 0, 0, 100));
	CHECK(approx(f.pos(t0 + 20ms), 0.2, 0, 99.9));
	CHECK(approx(f.pos(t0 + 50ms), 0.5, 0, 99.75));
	CHECK(approx(f.pos(t0 + 100ms), 0.5, 0, 99.75)); // clamped: no further than 0.05 s (one tick) of travel
	CHECK(approx(f.pos(t0 + 300ms), 0.5, 0, 99.75));
	CHECK(approx(f.pos(t0 - 50ms), 0, 0, 100));     // a query before the sample never extrapolates backwards
	CHECK(approx(f.velocity(t0 + 300ms), 10, 0, -5) && approx(f.yaw(t0), 30) && approx(f.pitch(t0), 12));
}

TEST(a_new_sample_replaces_the_extrapolation)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	f.update(sample(true, {0, 0, 100}, {10, 0, 0}), t0);
	f.update(sample(true, {1.2, 0, 100}, {10, 0, 0}), t0 + 100ms);
	CHECK(approx(f.pos(t0 + 100ms), 1.2, 0, 100));
	CHECK(approx(f.pos(t0 + 150ms), 1.7, 0, 100));
	CHECK(f.seq(t0 + 150ms) == 1); // active -> active is no change
}

TEST(a_sample_older_than_half_a_second_ends_the_flight)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	f.update(sample(true, {0, 0, 100}, {10, 0, -5}), t0);
	CHECK(f.active(t0 + 500ms) && f.seq(t0 + 500ms) == 1); // exactly 0.5 s old: still flying
	CHECK(!f.active(t0 + 501ms));                           // older: Minecraft vanished mid-flight
	CHECK(f.seq(t0 + 501ms) == 2);                          // a landing: FlightSeq moved
	CHECK(approx(f.pos(t0 + 2s), 0.5, 0, 99.75));           // V ends where the flight was last seen (clamped extrapolation)
	CHECK(approx(f.velocity(t0 + 2s), 0, 0, 0));
	CHECK(f.seq(t0 + 10s) == 2);                            // and it is counted once
}

TEST(staleness_is_noticed_by_any_query_and_counted_before_the_next_start)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	f.update(sample(true, {0, 0, 100}, {10, 0, 0}), t0);
	CHECK(approx(f.pos(t0 + 1s).x, 0.5)); // the first query after the gap ends the flight
	CHECK(f.seq(t0 + 1s) == 2);
	f.update(sample(true, {50, 0, 90}, {10, 0, 0}), t0 + 2s);
	CHECK(f.active(t0 + 2s) && f.seq(t0 + 2s) == 3);
	// a sample after a silent gap with no query in between: the missed landing is counted first
	Flight g;
	g.update(sample(true, {0, 0, 100}, {10, 0, 0}), t0);
	g.update(sample(true, {50, 0, 90}, {10, 0, 0}), t0 + 2s);
	CHECK(g.active(t0 + 2s) && g.seq(t0 + 2s) == 3 && approx(g.pos(t0 + 2s), 50, 0, 90));
}

TEST(touchdown_ends_the_flight_at_the_landing_spot)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	f.update(sample(true, {0, 0, 100}, {10, 0, -5}, 30, 12), t0);
	f.update(sample(false, {7, 8, 60}, {}, kNaN, kNaN), t0 + 40ms);
	CHECK(!f.active(t0 + 40ms) && f.seq(t0 + 40ms) == 2);
	CHECK(approx(f.pos(t0 + 40ms), 7, 8, 60));
	CHECK(approx(f.pos(t0 + 5s), 7, 8, 60));          // the landing spot stays, however old
	CHECK(f.seq(t0 + 5s) == 2);                       // an inactive flight never goes stale again
	CHECK(approx(f.yaw(t0 + 5s), 30) && approx(f.pitch(t0 + 5s), 12)); // the touchdown message carried no look
	f.update(sample(false, {7, 8, 60}), t0 + 6s);     // a repeated touchdown is no change
	CHECK(f.seq(t0 + 6s) == 2);
	f.update(sample(true, {7, 8, 61}, {0, 0, 1}, 45, -10), t0 + 7s);
	CHECK(f.active(t0 + 7s) && f.seq(t0 + 7s) == 3);
	CHECK(approx(f.yaw(t0 + 7s), 45) && approx(f.pitch(t0 + 7s), -10));
}

TEST(reset_ends_a_flight_in_progress_once)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	f.reset(t0);
	CHECK(f.seq(t0) == 0); // nothing to end
	f.update(sample(true, {0, 0, 100}, {10, 0, 0}), t0);
	f.reset(t0 + 30ms);
	CHECK(!f.active(t0 + 30ms) && f.seq(t0 + 30ms) == 2);
	CHECK(approx(f.pos(t0 + 30ms), 0.3, 0, 100));
	f.reset(t0 + 60ms);
	CHECK(f.seq(t0 + 60ms) == 2);
}

TEST(a_flight_that_goes_stale_is_timed_out_until_the_next_sample)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	CHECK(!f.timed_out(t0));
	f.update(sample(true, {0, 0, 100}, {10, 0, 0}), t0);
	CHECK(!f.timed_out(t0 + 500ms));
	CHECK(f.timed_out(t0 + 501ms) && !f.active(t0 + 501ms)); // Minecraft went silent mid-flight
	CHECK(f.timed_out(t0 + 5s));                             // stays until something new arrives
	f.update(sample(true, {50, 0, 90}, {10, 0, 0}), t0 + 6s);
	CHECK(!f.timed_out(t0 + 6s) && f.active(t0 + 6s));       // a new sample
	f.update(sample(false, {7, 8, 60}), t0 + 6s + 40ms);
	CHECK(!f.timed_out(t0 + 7s));                            // a real landing is not a timeout
	f.update(sample(true, {0, 0, 100}, {10, 0, 0}), t0 + 8s);
	CHECK(f.timed_out(t0 + 9s));
	f.reset(t0 + 9s);
	CHECK(!f.timed_out(t0 + 9s));                            // a reset clears it
	f.update(sample(true, {0, 0, 100}, {10, 0, 0}), t0 + 10s);
	f.reset(t0 + 10s + 10ms);
	CHECK(!f.timed_out(t0 + 20s));                           // a reset end is not a timeout either
}

// ---- the link observer (the plugin's global state) ----------------------------------------------------------------------

TEST(following_ends_on_a_landing_a_hello_or_a_drop_but_not_on_a_timeout)
{
	using cb::natives::flightFollowing;
	on_link_dropped();
	on_minecraft_message(flight_message(true));
	flightFollowing = true; // what Cyberblock_SetFlightFollowing(true) does
	on_minecraft_message(flight_message(true));
	CHECK(flightFollowing); // more samples leave it alone
	CHECK(!flight().timed_out(Clock::now()));
	on_minecraft_message(json::parse(R"({"t":"flight","active":false,"p":[10,64,-20]})"));
	CHECK(!flightFollowing); // Steve landed
	flightFollowing = true;
	on_minecraft_message(json::parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"})"));
	CHECK(!flightFollowing); // a new Minecraft session
	flightFollowing = true;
	on_link_dropped();
	CHECK(!flightFollowing); // the link dropped
	// a flight that times out (no samples for 0.5 s) leaves it set: Cyberblock_FlightTimedOut tells the script
	Flight f;
	const Clock::time_point t0 = Clock::now();
	f.update(sample(true, {0, 0, 100}), t0);
	flightFollowing = true;
	CHECK(!f.active(t0 + 1s) && f.timed_out(t0 + 1s) && flightFollowing);
	flightFollowing = false;
}

TEST(a_bad_flight_message_does_not_end_following)
{
	cb::natives::flightFollowing = true;
	on_minecraft_message(json::parse(R"({"t":"flight","active":false})")); // no p: rejected
	CHECK(cb::natives::flightFollowing);
	cb::natives::flightFollowing = false;
}

TEST(observer_keeps_mobs_and_projectiles_for_the_natives)
{
	on_link_dropped();
	on_minecraft_message(mobs_message({mob_entry(R"("id":3)"), mob_entry(R"("id":4)")}));
	on_minecraft_message(json{{"t", "proj"}, {"list", json::array({proj_entry()})}});
	CHECK(mobs().snapshot(Clock::now()) == 2);
	CHECK(mobs().at(0)->id == 3 && mobs().at(1)->id == 4);
	CHECK(projectiles().snapshot(Clock::now()) == 1 && projectiles().at(0)->id == 9);
	on_minecraft_message(json::parse(R"({"t":"mobs","list":[]})"));
	CHECK(mobs().snapshot(Clock::now()) == 0);
	on_link_dropped();
	CHECK(projectiles().snapshot(Clock::now()) == 0);
}

TEST(observer_counts_invalid_messages_and_dropped_entries)
{
	on_link_dropped();
	const uint64_t before = cb::world::events().stats().rejected;
	on_minecraft_message(mobs_message({mob_entry(), mob_entry(R"("hp":-1)")}));       // one entry dropped
	CHECK(cb::world::events().stats().rejected == before + 1 && mobs().snapshot(Clock::now()) == 1);
	on_minecraft_message(json::parse(R"({"t":"mobs","list":5})"));                     // invalid: kept list stays
	CHECK(cb::world::events().stats().rejected == before + 2 && mobs().snapshot(Clock::now()) == 1);
	on_minecraft_message(json::parse(R"({"t":"proj"})"));
	on_minecraft_message(json::parse(R"({"t":"flight","active":true})"));
	CHECK(cb::world::events().stats().rejected == before + 4);
	on_minecraft_message(json::parse(R"({"t":"explosion","pos":[0,0,0],"power":1})")); // not ours: not counted here
	on_minecraft_message(json::parse(R"([1,2])"));
	CHECK(cb::world::events().stats().rejected == before + 4);
	on_link_dropped();
}

TEST(observer_drives_the_flight_and_a_hello_ends_it)
{
	on_link_dropped();
	Flight &f = flight();
	const int32_t seq0 = f.seq(Clock::now());
	on_minecraft_message(flight_message(true));
	CHECK(f.active(Clock::now()) && f.seq(Clock::now()) == seq0 + 1);
	CHECK(approx(f.pos(Clock::now()), 10, 20, 100, 0.5)); // within a few ms of travel
	on_minecraft_message(json::parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"})"));
	CHECK(!f.active(Clock::now()) && f.seq(Clock::now()) == seq0 + 2);
	on_minecraft_message(flight_message(true));
	on_link_dropped();
	CHECK(!f.active(Clock::now()) && f.seq(Clock::now()) == seq0 + 4);
}

TEST(a_hello_empties_mobs_and_projectiles)
{
	on_link_dropped();
	on_minecraft_message(mobs_message({mob_entry()}));
	on_minecraft_message(json{{"t", "proj"}, {"list", json::array({proj_entry()})}});
	CHECK(mobs().snapshot(Clock::now()) == 1 && projectiles().snapshot(Clock::now()) == 1);
	on_minecraft_message(json::parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"})"));
	CHECK(mobs().snapshot(Clock::now()) == 0 && projectiles().snapshot(Clock::now()) == 0);
}

// ---- re-test 2 (plan 2026-10-03-retest2 section 1.1): positions take the ground alignment, velocities do not -------------

TEST(snapshot_positions_take_plus_off_and_velocities_do_not)
{
	constexpr double off = 57.0 / 64.0; // 0.890625
	const ListParse<Mob> mobsAt = parse_mobs(mobs_message({mob_entry()}), off);
	CHECK(approx(mobsAt.items.at(0).feet, 10, 20, 64.890625) && approx(mobsAt.items[0].yaw, 180));
	const ListParse<Projectile> projAt = parse_proj(json{{"t", "proj"}, {"list", json::array({proj_entry()})}}, off);
	CHECK(approx(projAt.items.at(0).pos, 1, 2, 70.890625) && approx(projAt.items[0].velocity, 3, -5, 4));
	std::string reason;
	const auto s = parse_flight(flight_message(true), reason, off);
	CHECK(s && approx(s->pos, 10, 20, 100.890625) && approx(s->velocity, 3, -4, -1) && approx(s->yaw, 180) && approx(s->pitch, 30));
	const auto land = parse_flight(json::parse(R"({"t":"flight","active":false,"p":[1,64,-2]})"), reason, off);
	CHECK(land && approx(land->pos, 1, 2, 64.890625)); // the landing spot too
}

TEST(the_observer_converts_snapshots_with_the_current_alignment)
{
	on_link_dropped();
	cb::world::on_minecraft_message(json::parse(R"({"t":"align","k":57})")); // main.cpp: world first, then snap
	on_minecraft_message(json::parse(R"({"t":"align","k":57})"));
	on_minecraft_message(mobs_message({mob_entry()}));
	CHECK(mobs().snapshot(Clock::now()) == 1 && approx(mobs().at(0)->feet, 10, 20, 64.890625));
	on_minecraft_message(json::parse(R"({"t":"flight","active":false,"p":[1,64,-2]})"));
	CHECK(approx(flight().pos(Clock::now()), 1, 2, 64.890625));
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"})"));
	on_minecraft_message(json::parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"})"));
	on_minecraft_message(mobs_message({mob_entry()}));
	CHECK(mobs().snapshot(Clock::now()) == 1 && approx(mobs().at(0)->feet, 10, 20, 64));
	on_link_dropped();
}

// ---- re-test 2 (plan 2026-10-03-retest2 section 1.2 and task D3): kind, ts and the interpolated render position -----------

namespace
{
	/// A deterministic 0..1 sequence (a linear congruential generator), so the jitter is the same on every run.
	struct Lcg
	{
		uint32_t state = 12345u;
		double next()
		{
			state = state * 1664525u + 1013904223u;
			return (state >> 8) / double(1u << 24);
		}
	};

	/// Seconds as a steady-clock duration.
	Clock::duration secs(double s)
	{
		return std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double>(s));
	}

	FlightSample timed(cb::Vec3 pos, cb::Vec3 vel, std::optional<double> tsMs, bool launch = false)
	{
		FlightSample s = sample(true, pos, vel);
		s.ts = tsMs;
		s.launch = launch;
		return s;
	}
}

TEST(flight_kind_and_ts_are_parsed_and_a_bad_ts_is_ignored)
{
	std::string reason;
	json m = flight_message(true);
	CHECK(!parse_flight(m, reason)->launch && !parse_flight(m, reason)->ts); // absent: a glide, no ts
	m["kind"] = "launch";
	CHECK(parse_flight(m, reason)->launch);
	for (const json &kind : {json("glide"), json("Launch"), json("rocket"), json(1), json(nullptr), json(true)})
	{
		m["kind"] = kind;
		const auto s = parse_flight(m, reason);
		CHECK(s.has_value() && !s->launch); // anything but "launch" is a glide, never a rejection
	}
	m = flight_message(true);
	m["ts"] = 81234.5;
	CHECK(parse_flight(m, reason)->ts == 81234.5);
	m["ts"] = 0;
	CHECK(parse_flight(m, reason)->ts == 0.0);
	m["ts"] = 1e13;
	CHECK(parse_flight(m, reason)->ts == 1e13);
	for (const json &bad : {json(-1), json(1.0000001e13), json("81234"), json(nullptr), json(kNaN), json(kInf), json::array()})
	{
		m["ts"] = bad;
		const auto s = parse_flight(m, reason);
		CHECK(s.has_value() && !s->ts); // ignored, not rejected
	}
	const auto land = parse_flight(json::parse(R"({"t":"flight","active":false,"kind":"launch","ts":99,"p":[1,64,-2]})"), reason);
	CHECK(land && !land->active && land->launch && land->ts == 99.0);
}

TEST(render_pos_follows_linear_motion_with_jittered_arrivals_smoothly)
{
	// Steve flies at 20 m/s along +x; Minecraft samples at 120 Hz with its own clock (ts). The local link adds 2 ms plus
	// 0..4 ms of jitter, and every 0.3 s Minecraft stalls for 25 ms (a GC pause, a slow frame) and its samples arrive in
	// one burst. V is placed every 60 fps frame: each step must be 20 m/s x 16.7 ms within 2 cm, and never go backwards.
	Flight f;
	const Clock::time_point t0 = Clock::now();
	const cb::Vec3 v{20, 0, 0};
	const double spacing = 1.0 / 120.0;
	Lcg rng;
	std::vector<std::pair<Clock::time_point, FlightSample>> arrivals;
	Clock::time_point previous = t0;
	for (int i = 0; i < 240; ++i)
	{
		const double ts = i * spacing;
		const double stall = std::fmod(ts, 0.3) < 0.025 ? 0.025 - std::fmod(ts, 0.3) : 0.0; // held until the stall ends
		// TCP keeps the order: a late message holds back the ones behind it
		previous = std::max(previous, t0 + secs(ts + stall + 0.002 + 0.004 * rng.next()));
		arrivals.push_back({previous, timed({v.x * ts, 5, 100}, v, ts * 1000.0 + 5000.0)});
	}
	size_t next = 0;
	double last = -1e9;
	int frames = 0;
	bool smooth = true, monotonic = true, onTheLine = true;
	for (double t = 0.2; t < 1.9; t += 1.0 / 60.0)
	{
		const Clock::time_point now = t0 + secs(t);
		while (next < arrivals.size() && arrivals[next].first <= now)
		{
			f.update(arrivals[next].second, arrivals[next].first);
			++next;
		}
		const cb::Vec3 p = f.render_pos(now);
		if (last > -1e8)
		{
			smooth = smooth && std::abs((p.x - last) - v.x / 60.0) < 0.02;
			monotonic = monotonic && p.x >= last;
		}
		onTheLine = onTheLine && approx(p.y, 5, 1e-9) && approx(p.z, 100, 1e-9);
		last = p.x;
		++frames;
	}
	CHECK(frames > 90 && smooth && monotonic && onTheLine);
	// V trails Steve by the playout delay and the link's lowest latency, not by a whole tick
	const Clock::time_point end = t0 + secs(1.9);
	const double truth = v.x * (1.9 - 0.002);
	CHECK(f.render_pos(end).x < truth && f.render_pos(end).x > truth - v.x * 0.07);
}

TEST(render_pos_without_ts_still_follows)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	const cb::Vec3 v{0, -10, -2};
	for (int i = 0; i < 60; ++i)
	{
		const double t = i / 60.0;
		f.update(timed({0, v.y * t, 200 + v.z * t}, v, std::nullopt), t0 + secs(t));
	}
	// arrival times are the clock: the playout delay is 1.5 x the 16.7 ms spacing, so V is where Steve was 25 ms ago
	const cb::Vec3 at = f.render_pos(t0 + secs(59 / 60.0 + 0.005));
	CHECK(approx(at.y, v.y * (59 / 60.0 + 0.005 - 0.025), 1e-6));
	CHECK(approx(at.z, 200 + at.y / 5, 1e-6)); // on the line z = 200 + y/5
}

TEST(render_pos_extrapolates_at_most_50_ms_past_the_newest_sample)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	f.update(timed({0, 0, 100}, {10, 0, 0}, 1000.0), t0);
	f.update(timed({0.1, 0, 100}, {10, 0, 0}, 1010.0), t0 + 10ms);
	CHECK(approx(f.render_pos(t0 + 400ms), 0.1 + 10 * 0.05, 0, 100, 1e-9)); // capped at 0.05 s of travel
	CHECK(approx(f.render_pos(t0 + 10ms + 15ms + 20ms), 0.1 + 10 * 0.020, 0, 100, 1e-6)); // D = 15 ms: 20 ms past it
	// before the oldest sample: held there, never extrapolated backwards
	CHECK(approx(f.render_pos(t0 + 5ms), 0, 0, 100, 1e-9));
}

TEST(render_pos_of_an_inactive_flight_is_the_landing_spot)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	CHECK(approx(f.render_pos(t0), 0, 0, 0));
	f.update(timed({0, 0, 100}, {10, 0, 0}, 0.0), t0);
	f.update(sample(false, {7, 8, 60}), t0 + 40ms);
	CHECK(approx(f.render_pos(t0 + 40ms), 7, 8, 60) && approx(f.render_pos(t0 + 5s), 7, 8, 60));
	// a flight that goes stale ends where it was last seen, as pos() says
	Flight g;
	g.update(timed({0, 0, 100}, {10, 0, 0}, 0.0), t0);
	CHECK(approx(g.render_pos(t0 + 1s), g.pos(t0 + 1s).x, 0, 100) && !g.active(t0 + 1s));
}

TEST(the_ring_starts_over_with_every_flight_and_on_reset)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	for (int i = 0; i < 10; ++i)
		f.update(timed({double(i), 0, 100}, {120, 0, 0}, i * 8.0), t0 + std::chrono::milliseconds(8 * i));
	f.update(sample(false, {9, 0, 100}), t0 + 90ms);
	// a new flight far away, whose ts clock restarted: nothing from the old ring leaks into it
	f.update(timed({500, 0, 50}, {0, 0, 0}, 3.0), t0 + 1s);
	CHECK(approx(f.render_pos(t0 + 1s + 1ms), 500, 0, 50) && approx(f.render_pos(t0 + 1s + 100ms), 500, 0, 50));
	f.reset(t0 + 2s);
	f.update(timed({-5, 0, 10}, {0, 0, 0}, 0.0), t0 + 3s);
	CHECK(approx(f.render_pos(t0 + 3s + 30ms), -5, 0, 10));
}

TEST(launch_and_stats_follow_the_samples)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	CHECK(!f.launch(t0) && f.stats().samples == 0);
	f.update(timed({0, 0, 70}, {0, 0, 12}, 0.0, true), t0);
	f.update(timed({0, 0, 70.1}, {0, 0, 12}, 8.0, true), t0 + 8ms);
	f.update(timed({0, 0, 70.3}, {0, 0, 12}, 16.0, true), t0 + 16ms);
	f.update(timed({0, 0, 70.6}, {0, 0, 12}, 40.0, true), t0 + 46ms);
	CHECK(f.launch(t0 + 46ms) && f.active(t0 + 46ms));
	const FlightStats s = f.stats();
	CHECK(s.samples == 4 && approx(s.medianGapMs, 8, 0.6) && approx(s.maxGapMs, 30, 0.6));
	f.update(timed({0, 0, 80}, {}, 60.0, false), t0 + 60ms); // switched to a glide mid-air
	CHECK(!f.launch(t0 + 60ms));
	f.update(sample(false, {0, 0, 80}), t0 + 70ms);
	CHECK(f.stats().samples == 5); // kept after the landing, for the drive's summary line
	f.update(timed({0, 0, 80}, {}, 0.0), t0 + 1s);
	CHECK(f.stats().samples == 1 && f.stats().maxGapMs == 0.0); // a new flight starts counting again
}
