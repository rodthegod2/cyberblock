// The Atlas survey: the walking survey's columns, `terrain` messages, `survey` requests and the tour state machine.
#include "survey.hpp"
#include "test.hpp"
#include <cmath>
#include <set>
#include <vector>

namespace
{
	using namespace cb::survey;
	using nlohmann::json;

	TourIn at(double now, std::string blocked = "", bool menu = false)
	{
		TourIn in;
		in.now = now;
		in.feet = {100.5, -200.25, 12.0};
		in.yaw = 37.0;
		in.blocked = std::move(blocked);
		in.menu = menu;
		return in;
	}

	/// The 3 x 3 probes all hitting ground at z (kind 1), or water (kind 2), or nothing (kind 0).
	std::vector<Hit> probes(int kind, double z = 10.0)
	{
		return std::vector<Hit>(9, Hit{0.0, 0.0, z, kind});
	}

	/// Starts a tour over the bounds x 0..400, y 0 (after the margin: stops (0, 0), (1, 0), (2, 0)); returns the last tick's time.
	double started(Tour &tour, std::vector<Stop> done = {})
	{
		tour.request_start(std::move(done));
		CHECK(tour.tick(at(0.0)) == Action::Bounds);
		tour.bounds(kBoundsMargin, kBoundsMargin, 400.0 - kBoundsMargin, -kBoundsMargin, 20); // x 0..400, y 0
		CHECK(tour.tick(at(0.1)) == Action::Begin);
		CHECK(tour.tick(at(0.2)) == Action::Settle);
		return 0.2;
	}

	size_t drain(Tour &tour)
	{
		size_t n = 0;
		while (tour.next_column())
			++n;
		return n;
	}

	const json *last_state(const std::vector<json> &messages)
	{
		return messages.empty() ? nullptr : &messages.back();
	}
}

TEST(terrain_message_holds_quads_in_minecraft_cells_and_skips_misses)
{
	const std::vector<Hit> hits{{10.4, 20.6, 5.0, 1}, {0.0, 0.0, 0.0, 0}, {-0.5, 0.5, -3.5, 2}, {NAN, 0.0, 0.0, 1}};
	const auto messages = make_terrain(hits, 0.0);
	CHECK(messages.size() == 1);
	CHECK(messages[0]["t"] == "terrain");
	CHECK(messages[0]["c"] == json({5, -11, 5, 0, -1, -1, -3, 1}));
	// the alignment moves the height only
	CHECK(make_terrain(std::vector<Hit>{{10.4, 20.6, 5.0, 1}}, 0.890625)[0]["c"] == json({5, -11, 4, 0}));
}

TEST(terrain_messages_are_cut_at_64_cells)
{
	std::vector<Hit> hits;
	for (int i = 0; i < 130; ++i)
		hits.push_back({double(i) * 2.0, 0.0, 1.0, 1});
	const auto messages = make_terrain(hits, 0.0);
	CHECK(messages.size() == 3 && messages[0]["c"].size() == 256 && messages[1]["c"].size() == 256 && messages[2]["c"].size() == 8);
}

TEST(walking_columns_start_under_v_and_are_not_repeated)
{
	Columns columns;
	const cb::Vec3 feet{10.4, 20.6, 5.0}; // cell (5, -11)
	const auto first = columns.next(feet, 0.0);
	CHECK(first && cb::survey_cell((*first)[0], (*first)[1], 0.0, 0.0).cx == 5 && cb::survey_cell((*first)[0], (*first)[1], 0.0, 0.0).cz == -11);
	std::set<std::pair<int, int>> seen;
	seen.insert({5, -11});
	size_t handed = 1;
	while (const auto c = columns.next(feet, 0.0))
	{
		const cb::SurveyCell cell = cb::survey_cell((*c)[0], (*c)[1], 0.0, 0.0);
		CHECK(seen.insert({cell.cx, cell.cz}).second);
		CHECK((cell.cx - 5) * (cell.cx - 5) + (cell.cz + 11) * (cell.cz + 11) <= 48 * 48);
		++handed;
	}
	CHECK(handed > 7000 && handed < 7400); // pi 48^2 cells
	CHECK(!columns.next(feet, 0.5));       // idle for a second
	CHECK(!columns.next(feet, 10.0));      // nothing is due before the refresh
	const auto again = columns.next(feet, kRefreshSeconds + 1.0);
	CHECK(again.has_value());
}

TEST(walking_columns_retry_a_miss_early_and_follow_v)
{
	Columns columns;
	const cb::Vec3 feet{0.5, 0.5, 0.0};
	const auto first = columns.next(feet, 0.0);
	while (columns.next(feet, 0.0))
	{
	}
	columns.missed((*first)[0], (*first)[1], 1.0);
	CHECK(!columns.next(feet, 5.0));
	const auto retried = columns.next(feet, 1.0 + kMissRetrySeconds + 0.1);
	CHECK(retried && (*retried)[0] == (*first)[0] && (*retried)[1] == (*first)[1]);
	// 40 m east: a new pass starts at once around the new place; the nearest cells not handed out yet lie just past the old
	// radius (48 cells around cell 0)
	const auto moved = columns.next({40.5, 0.5, 0.0}, 1.0 + kMissRetrySeconds + 0.2);
	CHECK(moved.has_value());
	const cb::SurveyCell next = cb::survey_cell((*moved)[0], (*moved)[1], 0.0, 0.0);
	CHECK(next.cx * next.cx + (next.cz + 1) * (next.cz + 1) > 48 * 48);              // outside the old pass
	CHECK((next.cx - 20) * (next.cx - 20) + (next.cz + 1) * (next.cz + 1) <= 29 * 29); // and as near the new place as that allows
	columns.clear();
	CHECK(columns.remembered() == 0 && columns.next(feet, 0.0).has_value());
}

TEST(plan_is_a_serpentine_over_every_stop_touching_the_bounds)
{
	size_t total = 0;
	const auto stops = plan(0.0, 0.0, 400.0, 200.0, {}, &total);
	// x: stops -0..2 (gx 0, 1, 2), y: gy 0, 1
	CHECK(total == 6 && stops.size() == 6);
	CHECK(stops[0] == (Stop{0, 0}) && stops[2] == (Stop{2, 0}) && stops[3] == (Stop{2, 1}) && stops[5] == (Stop{0, 1}));
	const auto rest = plan(0.0, 0.0, 400.0, 200.0, {{1, 0}, {2, 1}}, &total);
	CHECK(total == 6 && rest.size() == 4 && rest[1] == (Stop{2, 0}) && rest[2] == (Stop{1, 1}));
	CHECK(plan(10.0, 0.0, 0.0, 0.0, {}).empty()); // inverted bounds
	CHECK(stop_centre({-3, 4})[0] == -600.0 && stop_centre({-3, 4})[1] == 800.0);
}

TEST(survey_requests_are_validated)
{
	const auto start = parse_request(json{{"t", "survey"}, {"op", "start"}, {"done", {1, 2, -3, 4}}});
	CHECK(start && start->op == "start" && start->done.size() == 2 && start->done[1] == (Stop{-3, 4}));
	CHECK(parse_request(json{{"t", "survey"}, {"op", "status"}})->done.empty());
	std::string why;
	CHECK(!parse_request(json{{"t", "survey"}, {"op", "go"}}, &why) && !why.empty());
	CHECK(!parse_request(json{{"t", "survey"}, {"op", "start"}, {"done", {1, 2, 3}}}));
	CHECK(!parse_request(json{{"t", "survey"}, {"op", "start"}, {"done", {1.5, 2}}}));
	CHECK(!parse_request(json{{"t", "survey"}, {"op", "start"}, {"done", {1, 200000}}}));
	CHECK(!parse_request(json{{"t", "survey"}, {"op", "start"}, {"done", "1,2"}}));
	CHECK(!parse_request(json::array()));
}

TEST(tour_refuses_while_blocked_and_without_bounds)
{
	Tour tour;
	tour.request_start({});
	CHECK(tour.tick(at(0.0, "vehicle")) == Action::None && !tour.running());
	auto messages = tour.take_messages();
	CHECK(last_state(messages) && (*last_state(messages))["state"] == "refused" && (*last_state(messages))["reason"] == "vehicle");

	tour.request_start({});
	CHECK(tour.tick(at(1.0)) == Action::Bounds);
	tour.bounds(0.0, 0.0, 100.0, 100.0, 3);
	CHECK(!tour.running());
	messages = tour.take_messages();
	CHECK((*last_state(messages))["state"] == "refused");
}

TEST(tour_holds_v_waits_for_streaming_samples_and_reports_each_stop)
{
	Tour tour;
	double t = started(tour);
	const auto hold = tour.hold();
	CHECK(hold && hold->x == 0.0 && hold->y == 0.0 && hold->z == kTourHoldZ);
	CHECK(tour.home().x == 100.5 && tour.home_yaw() == 37.0);
	// probes that keep changing never settle before the timeout
	tour.settle(probes(1, 10.0), t + 0.5);
	tour.settle(probes(1, 11.0), t + 1.6);
	CHECK(tour.tick(at(t + 1.7)) == Action::Settle);
	tour.settle(probes(1, 11.0), t + 2.0);
	CHECK(tour.tick(at(t + 2.1)) == Action::Settle); // stable only 0.4 s
	tour.settle(probes(1, 11.0), t + 2.7);
	CHECK(tour.tick(at(t + 2.8)) == Action::Sample);
	CHECK(drain(tour) == 111 * 111); // 220 m square of 2-m cells (both edges included)
	tour.sampled(111 * 111, 12000);
	tour.take_messages();
	CHECK(tour.tick(at(t + 3.0)) == Action::Settle); // the next stop
	const auto messages = tour.take_messages();
	bool reported = false;
	for (const json &m : messages)
		reported |= m.contains("stop") && m["stop"] == json({0, 0}) && m["done"] == 1 && m["total"] == 3;
	CHECK(reported);
	CHECK(tour.hold()->x == 200.0 && tour.hold()->z == kTourHoldZ); // highest probe 11 m: the floor of 150 m holds
}

TEST(tour_skips_water_and_void_and_ends_back_home)
{
	Tour tour;
	double t = started(tour);
	tour.settle(probes(2), t + 0.1);
	tour.settle(probes(2), t + 2.0);   // all water and stable: skipped without sampling
	CHECK(tour.tick(at(t + 2.1)) == Action::Settle && tour.hold()->x == 200.0);
	tour.settle(probes(0), t + 2.2);
	tour.settle(probes(0), t + 2.2 + kSettleTimeout + 0.1); // nothing streamed: skipped at the timeout
	CHECK(tour.tick(at(t + 20.0)) == Action::Settle && tour.hold()->x == 400.0);
	tour.settle(probes(1, 300.0), t + 20.1);
	tour.settle(probes(1, 300.0), t + 22.0);
	CHECK(tour.tick(at(t + 22.1)) == Action::Sample);
	drain(tour);
	CHECK(tour.tick(at(t + 22.2)) == Action::End);
	CHECK(!tour.running() && !tour.hold());
	const auto messages = tour.take_messages();
	CHECK((*last_state(messages))["state"] == "done" && (*last_state(messages))["done"] == 3);
}

TEST(tour_stops_on_request_or_when_blocked_and_pauses_behind_menus)
{
	Tour tour;
	double t = started(tour);
	CHECK(tour.tick(at(t + 1.0, "", true)) == Action::None); // a menu: wait
	CHECK(tour.hold().has_value());                           // V stays held
	auto messages = tour.take_messages();
	CHECK((*last_state(messages))["state"] == "paused");
	CHECK(tour.tick(at(t + 30.0)) == Action::Settle);         // the 29 s behind the menu do not time the stop out
	tour.settle(probes(0), t + 30.1);
	CHECK(tour.tick(at(t + 30.2)) == Action::Settle && tour.hold()->x == 0.0);
	tour.request_stop("stopped by /cyberblock survey stop");
	CHECK(tour.tick(at(t + 31.0)) == Action::End && !tour.running());
	messages = tour.take_messages();
	CHECK((*last_state(messages))["state"] == "stopped");

	started(tour);
	CHECK(tour.tick(at(5.0, "off")) == Action::End);
	messages = tour.take_messages();
	CHECK((*last_state(messages))["reason"] == "off");
}

TEST(tour_resumes_without_the_done_stops_and_reset_forgets_it)
{
	Tour tour;
	tour.request_start({{0, 0}, {1, 0}});
	CHECK(tour.tick(at(0.0)) == Action::Bounds);
	tour.bounds(kBoundsMargin, kBoundsMargin, 400.0 - kBoundsMargin, -kBoundsMargin, 20); // x 0..400, y 0
	CHECK(tour.tick(at(0.1)) == Action::Begin);
	CHECK(tour.hold()->x == 400.0); // the only stop left
	tour.reset();
	CHECK(!tour.running() && !tour.hold());
	CHECK(tour.tick(at(1.0)) == Action::None);

	tour.request_start({{0, 0}, {1, 0}, {2, 0}});
	CHECK(tour.tick(at(2.0)) == Action::Bounds);
	tour.bounds(kBoundsMargin, kBoundsMargin, 400.0 - kBoundsMargin, -kBoundsMargin, 20); // x 0..400, y 0
	CHECK(!tour.running());
	const auto messages = tour.take_messages();
	CHECK((*last_state(messages))["state"] == "done");
}
