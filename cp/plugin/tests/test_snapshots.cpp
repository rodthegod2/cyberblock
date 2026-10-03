// Layers 3-4 in the DLL: the `mobs`, `flight` and `proj` messages kept as state (plan 2026-10-03 section 1.3): parsing and
// conversion, staleness, flight extrapolation, and the snapshots that natives index into.
#include "snapshots.hpp"
#include "test.hpp"
#include "world.hpp"
#include <chrono>
#include <cmath>
#include <limits>
#include <string>

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

TEST(flight_position_is_extrapolated_by_velocity_times_the_age_up_to_a_tenth_of_a_second)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	f.update(sample(true, {0, 0, 100}, {10, 0, -5}, 30, 12), t0);
	CHECK(f.active(t0) && f.seq(t0) == 1);
	CHECK(approx(f.pos(t0), 0, 0, 100));
	CHECK(approx(f.pos(t0 + 50ms), 0.5, 0, 99.75));
	CHECK(approx(f.pos(t0 + 100ms), 1.0, 0, 99.5));
	CHECK(approx(f.pos(t0 + 300ms), 1.0, 0, 99.5)); // clamped: no further than 0.1 s of travel
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
	CHECK(approx(f.pos(t0 + 2s), 1.0, 0, 99.5));            // V ends where the flight was last seen (clamped extrapolation)
	CHECK(approx(f.velocity(t0 + 2s), 0, 0, 0));
	CHECK(f.seq(t0 + 10s) == 2);                            // and it is counted once
}

TEST(staleness_is_noticed_by_any_query_and_counted_before_the_next_start)
{
	Flight f;
	const Clock::time_point t0 = Clock::now();
	f.update(sample(true, {0, 0, 100}, {10, 0, 0}), t0);
	CHECK(approx(f.pos(t0 + 1s).x, 1.0)); // the first query after the gap ends the flight
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

// ---- the link observer (the plugin's global state) ----------------------------------------------------------------------

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
