// The Atlas survey: the walking survey's columns, `terrain` messages, `survey` requests, the tour's area (hulls), squares
// (adaptive coverage), order, ETA and frame budget, and the tour state machine driven like the DLL drives it every frame.
#include "survey.hpp"
#include "test.hpp"
#include <cmath>
#include <cstdio>
#include <functional>
#include <map>
#include <set>
#include <string>
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

	const json *last_state(const std::vector<json> &messages)
	{
		return messages.empty() ? nullptr : &messages.back();
	}

	/// The city: fast-travel points on a 100-m grid over x 0..400, y 0..400. The outskirts: fast-travel points in the
	/// Badlands at x 1500, and map pins near each (which join their nearest fast-travel point's district).
	std::vector<Pin> pins(int size = 400)
	{
		std::vector<Pin> out;
		for (int x = 0; x <= size; x += 100)
			for (int y = 0; y <= size; y += 100)
				out.push_back({double(x) + 0.5, double(y) + 0.5, kCityTravel});
		for (int y = 0; y <= 400; y += 200)
			out.push_back({double(size) + 1100.0, double(y), kOutskirtsTravel});
		out.push_back({1450.0, 30.0, kMapPin});  // near a Badlands point
		out.push_back({250.0, 250.0, kMapPin});  // inside the city
		out.push_back({0.0, 0.0, kMapPin});      // no place: left out
		return out;
	}

	/// Drives a tour the way the scripts (tick, 10 Hz) and the DLL (need / probes / columns, every frame) do, against a
	/// world whose collision is loaded within `reach` of where V is held, ground at z 10.
	struct Sim
	{
		Tour tour;
		double now = 0.0, nextTick = 0.0, reach = 401.0;
		std::function<bool(double, double)> solid = [](double, double) { return true; }; // the world has collision there
		std::function<bool(double)> streamed = [](double) { return true; };            // at this time
		std::vector<Hit> cells;
		std::vector<json> messages;
		std::vector<std::string> log;
		bool menu = false;
		std::string blocked;
		std::vector<Action> actions;

		Hit ray(double x, double y)
		{
			const auto h = tour.hold();
			if (!h || !streamed(now) || !solid(x, y) || std::hypot(x - h->x, y - h->y) > reach)
				return {x, y, 0.0, 0};
			return {x, y, 10.0, 1};
		}

		void frame(size_t maxColumns)
		{
			int batches = 0;
			size_t columns = 0;
			while (true)
			{
				const Need need = tour.need(now);
				if (need == Need::Probes && batches++ < 2)
				{
					std::vector<Hit> hits;
					for (const auto &[x, y] : tour.probes())
						hits.push_back(ray(x, y));
					tour.probed(hits, now);
				}
				else if (need == Need::Columns && columns++ < maxColumns)
				{
					if (const auto c = tour.next_column(now))
						tour.column(ray((*c)[0], (*c)[1]));
				}
				else
					break;
			}
			const auto c = tour.take_cells();
			cells.insert(cells.end(), c.begin(), c.end());
			drain();
		}

		void drain()
		{
			for (auto &m : tour.take_messages())
				messages.push_back(std::move(m));
			for (auto &l : tour.take_log())
				log.push_back(std::move(l));
		}

		/// One 60 Hz frame, and the script tick when one is due.
		void step(size_t maxColumns = 20000)
		{
			now += 1.0 / 60.0;
			if (!menu)
				frame(maxColumns);
			if (now >= nextTick)
			{
				nextTick = now + 0.1;
				TourIn in = at(now, blocked, menu);
				actions.push_back(tour.tick(in));
				drain();
			}
		}

		/// Starts the tour on pins(size); returns false when it did not begin.
		bool start(std::vector<Stop> done = {}, int size = 400)
		{
			tour.request_start(std::move(done));
			if (tour.tick(at(now)) != Action::Bounds)
				return false;
			const auto p = pins(size);
			tour.area(p);
			drain();
			now += 0.1;
			const Action a = tour.tick(at(now));
			drain();
			nextTick = now + 0.1;
			return a == Action::Begin;
		}

		void run(double seconds, size_t maxColumns = 20000)
		{
			const double until = now + seconds;
			while (now < until && tour.running())
				step(maxColumns);
		}

		size_t stops() const
		{
			size_t n = 0;
			for (const auto &l : log)
				n += l.rfind("survey: stop ", 0) == 0;
			return n;
		}

		std::set<Stop> reported() const
		{
			std::set<Stop> out;
			for (const json &m : messages)
				if (m.contains("stop"))
					out.insert({m["stop"][0].get<int>(), m["stop"][1].get<int>()});
			return out;
		}
	};
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

TEST(vehicle_columns_stay_beyond_the_collision_boxes)
{
	Columns columns(kVehicleMinRadius);
	const cb::Vec3 feet{0.5, 0.5, 0.0};
	size_t handed = 0;
	while (const auto c = columns.next(feet, 0.0))
	{
		CHECK(std::hypot((*c)[0] - 1.0, (*c)[1] + 1.0) >= kVehicleMinRadius - 2.0); // cell centres; V's cell is (0, -1)
		CHECK(std::hypot((*c)[0] - 1.0, (*c)[1] + 1.0) <= kRadius + 2.0);
		++handed;
	}
	CHECK(handed > 3600 && handed < 4000); // pi (48^2 - 33^2) cells
}

TEST(frame_budget_fills_a_25_fps_frame_within_limits)
{
	CHECK(frame_budget_ms(10.0, 0.0) == kMaxBudgetMs); // a 100 fps game would leave 30 ms: capped at 25
	CHECK(frame_budget_ms(30.0, 10.0) == 20.0);       // the game took 20 ms: 20 ms left of 40
	CHECK(frame_budget_ms(50.0, 5.0) == kMinBudgetMs); // a slow game still gets 2 ms
	CHECK(frame_budget_ms(1000.0, 0.0) == kMinBudgetMs);
	CHECK(frame_budget_ms(NAN, 0.0) == kMinBudgetMs);
}

TEST(convex_hull_and_distance)
{
	const std::vector<P2> hull = convex_hull({{0, 0}, {10, 0}, {10, 10}, {0, 10}, {5, 5}, {5, 0}, {10, 10}, {NAN, 1}});
	CHECK(hull.size() == 4); // the inner point, the collinear one and the duplicate are gone
	CHECK(hull_area(hull) == 100.0);
	CHECK(hull_distance(hull, {5, 5}) == 0.0 && hull_distance(hull, {10, 5}) == 0.0);
	CHECK(std::abs(hull_distance(hull, {13, 14}) - 5.0) < 1e-9);
	CHECK(std::abs(hull_distance(hull, {-2, 5}) - 2.0) < 1e-9);
	// counter-clockwise
	double twice = 0.0;
	for (size_t i = 0; i < hull.size(); ++i)
		twice += hull[i].x * hull[(i + 1) % hull.size()].y - hull[(i + 1) % hull.size()].x * hull[i].y;
	CHECK(twice > 0.0);
	CHECK(convex_hull({{1, 1}, {1, 1}}).size() == 1 && hull_distance({{0, 0}}, {3, 4}) == 5.0);
	CHECK(std::isinf(hull_distance({}, {0, 0})));
}

TEST(area_is_the_city_hull_and_the_outskirts_hull)
{
	const auto p = pins();
	std::string why;
	const auto area = make_area(p, &why);
	CHECK(area.has_value() && !area->dense);
	CHECK(area->cityPoints == 26); // 25 city fast-travel points and the map pin inside the city; not the Badlands pin
	CHECK(std::abs(hull_area(area->city) - 400.0 * 400.0) < 1.0);
	CHECK(hull_distance(area->city, {1450.0, 30.0}) > 1000.0);
	CHECK(hull_distance(area->outskirts, {1450.0, 30.0}) == 0.0 && hull_distance(area->outskirts, {1000.0, 200.0}) == 0.0);

	// without district information the city is where the pins are dense
	std::vector<Pin> unlabelled;
	for (const Pin &pin : p)
		unlabelled.push_back({pin.x, pin.y, kMapPin});
	const auto dense = make_area(unlabelled);
	CHECK(dense && dense->dense && hull_distance(dense->city, {1500.0, 200.0}) > 1000.0 && hull_distance(dense->city, {200, 200}) == 0.0);

	CHECK(!make_area(std::span<const Pin>(p.data(), 5), &why) && why.find("not enough") != std::string::npos);
}

TEST(plan_covers_the_city_finely_and_the_outskirts_coarsely_never_the_open_sea)
{
	const auto area = make_area(pins());
	size_t total = 0;
	const auto squares = plan(*area, {}, &total);
	CHECK(total == squares.size());
	size_t city = 0, outskirts = 0;
	for (const Square &s : squares)
	{
		const auto [x, y] = stop_centre(s.stop);
		if (s.outskirts)
		{
			++outskirts;
			CHECK(s.total() == 25 * 25);
			CHECK(hull_distance(area->outskirts, {x, y}) <= kOutskirtsMargin);
		}
		else
		{
			++city;
			CHECK(s.total() == 100 * 100);
			CHECK(hull_distance(area->city, {x, y}) <= kCityMargin);
		}
		CHECK(y > -600.0 && y < 1000.0); // nothing far beyond the pins (the sea)
	}
	CHECK(city == 25);                   // gx, gy -1..3: within 300 m of the 400-m city square
	CHECK(outskirts > 10 && outskirts < 40);
	size_t rest = 0;
	const auto left = plan(*area, {{0, 0}, {1, 1}, {50, 50}}, &rest);
	CHECK(rest == total && left.size() == total - 2);
}

TEST(square_columns_cover_its_cells_exactly)
{
	Square city;
	city.stop = {-3, 2};
	std::set<std::pair<int, int>> cells;
	for (int i = 0; i < city.total(); ++i)
	{
		const auto [x, y] = city.column(i);
		CHECK(std::abs(x - -600.0) < 100.0 && std::abs(y - 400.0) < 100.0);
		const cb::SurveyCell c = cb::survey_cell(x, y, 0.0, 0.0);
		cells.insert({c.cx, c.cz});
	}
	CHECK(cells.size() == 10000);
	Square out;
	out.stop = {0, 0};
	out.outskirts = true;
	out.stride = 4;
	CHECK(out.total() == 625);
	const auto [x0, y0] = out.column(0); // the cell at offset (2, 2) of the north-west 4 x 4 block
	CHECK(x0 == -100.0 + 5.0 && y0 == 100.0 - 5.0);
}

TEST(squares_are_visited_outward_from_v_city_first_retries_after)
{
	const auto area = make_area(pins());
	auto squares = plan(*area, {});
	const P2 home{0.0, 0.0};
	P2 from = home;
	std::vector<double> distances;
	std::vector<bool> outskirts;
	int group = -1;
	while (const auto i = next_square(squares, from, home, &group))
	{
		const auto [x, y] = stop_centre(squares[*i].stop);
		distances.push_back(std::hypot(x, y));
		outskirts.push_back(squares[*i].outskirts);
		squares[*i].done = true;
		from = {x, y};
	}
	CHECK(distances.size() == squares.size() && distances[0] == 0.0); // V's own square first
	// city before outskirts
	const auto firstOutskirts = std::find(outskirts.begin(), outskirts.end(), true);
	CHECK(std::all_of(firstOutskirts, outskirts.end(), [](bool o) { return o; }));
	// the city grows outward: its first half lies nearer V than its second
	const size_t cityCount = size_t(firstOutskirts - outskirts.begin());
	double nearHalf = 0.0, farHalf = 0.0;
	for (size_t i = 0; i < cityCount; ++i)
		(i < cityCount / 2 ? nearHalf : farHalf) += distances[i];
	CHECK(nearHalf < farHalf);
	// a square visited once (not finished) waits for the retry pass after every first visit of its group
	for (Square &s : squares)
		s.done = false;
	squares[0].visits = 1;
	const auto first = next_square(squares, {stop_centre(squares[0].stop)[0], stop_centre(squares[0].stop)[1]}, home, &group);
	CHECK(first && *first != 0 && group == 0);
	for (size_t i = 1; i < squares.size(); ++i)
		if (!squares[i].outskirts)
			squares[i].done = true;
	CHECK(next_square(squares, home, home, &group) == size_t(0) && group == 1);
}

TEST(the_plan_fits_the_time_budget_finest_city_first)
{
	auto squares = plan(*make_area(pins(3000)), {});
	size_t city = 0;
	for (const Square &s : squares)
		city += !s.outskirts;
	CHECK(city > 200);
	Rates fast;
	fast.columnSeconds = 1e-6;
	CHECK(choose_strides(squares, fast, kPlanSeconds) == (Strides{1, 4})); // plenty of time: every 2-m cell
	Rates slow;
	slow.columnSeconds = 400e-6;
	const Strides coarse = choose_strides(squares, slow, kPlanSeconds);
	CHECK(coarse.city > 1 && coarse.outskirts >= coarse.city);
	CHECK(estimate_seconds(squares, slow, coarse) <= kPlanSeconds);
	// one step finer would not have fitted
	const auto finer = std::find(kCityStrides.begin(), kCityStrides.end(), coarse.city) - 1;
	CHECK(estimate_seconds(squares, slow, {*finer, std::max(*finer, kOutskirtsStrides.front())}) > kPlanSeconds);
	// nothing fits: the coarsest
	Rates hopeless;
	hopeless.stopSeconds = 100.0;
	CHECK(choose_strides(squares, hopeless, kPlanSeconds) == (Strides{kCityStrides.back(), kOutskirtsStrides.back()}));
	// made again: coarser at once when the plan no longer fits, finer only with room to spare
	CHECK(replan_strides(squares, slow, kPlanSeconds, {1, 4}, false) == coarse);
	const double needs = estimate_seconds(squares, slow, coarse);
	CHECK(replan_strides(squares, slow, needs / kRefineShare + 1.0, {kCityStrides.back(), 10}, false) <= coarse);
	const Strides tight = replan_strides(squares, slow, needs + 1.0, {kCityStrides.back(), 10}, false); // not `coarse`: too tight
	CHECK(tight > coarse && (tight == (Strides{kCityStrides.back(), 10}) || estimate_seconds(squares, slow, tight) <= kRefineShare * (needs + 1.0)));
	CHECK(replan_strides(squares, slow, needs + 1.0, {kCityStrides.back(), 10}, true) == coarse);
	// a square already sampled keeps its stride; a finished one costs nothing
	Rates columnsOnly;
	columnsOnly.stopSeconds = 0.0;
	std::vector<Square> two(2);
	two[0].sampled.assign(10000, false);
	two[0].count = 2500;
	two[1].done = true;
	CHECK(std::abs(estimate_seconds(two, columnsOnly, {4, 10}) - 7500.0 * columnsOnly.columnSeconds) < 1e-9);
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

TEST(tour_refuses_while_blocked_and_without_an_area)
{
	Tour tour;
	tour.request_start({});
	CHECK(tour.tick(at(0.0, "vehicle")) == Action::None && !tour.running());
	auto messages = tour.take_messages();
	CHECK(last_state(messages) && (*last_state(messages))["state"] == "refused" && (*last_state(messages))["reason"] == "vehicle");

	tour.request_start({});
	CHECK(tour.tick(at(1.0)) == Action::Bounds);
	const std::vector<Pin> few(3, Pin{10.0, 10.0, kCityTravel});
	tour.area(few);
	CHECK(!tour.running());
	messages = tour.take_messages();
	CHECK((*last_state(messages))["state"] == "refused");
}

TEST(tour_maps_the_city_in_big_bites_then_the_outskirts_and_goes_home)
{
	Sim sim;
	CHECK(sim.start());
	const double begun = sim.now;
	const auto hold = sim.tour.hold();
	CHECK(hold && hold->z == kTourHoldZ && std::hypot(hold->x - 100.5, hold->y + 200.25) <= 150.0); // V's own square first
	CHECK(sim.tour.home().x == 100.5 && sim.tour.home_yaw() == 37.0);
	CHECK(!sim.log.empty() && sim.log[0].find("city hull 0.2 km2") != std::string::npos);
	// the first stop: loaded at once, so it leaves after the probes stayed the same for kSettleStable, not 15 s
	while (sim.stops() == 0 && sim.tour.running())
		sim.step();
	CHECK(sim.now - begun < kSettleStable + 0.3);
	CHECK(sim.log.back().find("settled after 0.5 s, reach 400 m") != std::string::npos);
	sim.run(600.0);
	CHECK(!sim.tour.running() && sim.actions.back() == Action::End && !sim.tour.hold());

	const auto area = make_area(pins());
	size_t total = 0;
	const auto squares = plan(*area, {}, &total);
	CHECK(sim.reported().size() == total);
	CHECK(sim.stops() < total / 2); // one stop maps every square within its measured reach
	CHECK((*last_state(sim.messages))["state"] == "done" && (*last_state(sim.messages))["done"] == total);
	// each city cell once at 2 m; each outskirts ray filled its 4 x 4 cells
	std::set<std::pair<int, int>> cells;
	size_t city = 0, outskirts = 0;
	for (const Square &s : squares)
		(s.outskirts ? outskirts : city) += 1;
	for (const Hit &h : sim.cells)
	{
		const cb::SurveyCell c = cb::survey_cell(h.x, h.y, h.z, 0.0);
		CHECK(cells.insert({c.cx, c.cz}).second);
	}
	CHECK(cells.size() == (city + outskirts) * 10000);
	// every city stop before the first outskirts stop
	size_t lastCity = 0, firstOutskirts = SIZE_MAX;
	for (size_t i = 0; i < sim.log.size(); ++i)
	{
		if (sim.log[i].find("[city") != std::string::npos)
			lastCity = i;
		if (sim.log[i].find("[outskirts") != std::string::npos)
			firstOutskirts = std::min(firstOutskirts, i);
	}
	CHECK(lastCity < firstOutskirts);
}

TEST(tour_measures_a_short_reach_and_takes_more_stops)
{
	Sim near;
	near.reach = 160.0; // only the 150-m ring is loaded
	CHECK(near.start());
	near.run(900.0);
	Sim far;
	CHECK(far.start());
	far.run(900.0);
	CHECK(!near.tour.running() && !far.tour.running());
	CHECK(near.reported() == far.reported());
	CHECK(near.stops() > far.stops());
	bool measured = false;
	for (const auto &l : near.log)
		measured |= l.find("reach 150 m (sampled 150 m)") != std::string::npos;
	CHECK(measured);
}

TEST(tour_retries_a_square_that_never_streams_and_finishes_it_on_the_second_visit)
{
	Sim sim;
	// square (0, 0) has no collision at all
	sim.solid = [](double x, double y) { return !(x >= -100.0 && x < 100.0 && y > -100.0 && y <= 100.0); };
	CHECK(sim.start());
	sim.run(900.0);
	CHECK(!sim.tour.running());
	size_t visits = 0;
	bool kept = false;
	for (const auto &l : sim.log)
		if (l.find(" at (0, 0) ") != std::string::npos)
		{
			++visits;
			kept |= l.find("nothing streamed") != std::string::npos && l.find("kept for the retry pass") != std::string::npos;
		}
	CHECK(visits == 2 && kept);
	CHECK(sim.reported().contains(Stop{0, 0}));
	bool retried = false;
	for (const auto &l : sim.log)
		retried |= l.find(" at (0, 0) [city retry]") != std::string::npos;
	CHECK(retried);
}

TEST(tour_waits_behind_menus_and_ends_on_request_or_when_blocked)
{
	Sim sim;
	CHECK(sim.start());
	sim.menu = true;
	sim.run(30.0);
	CHECK(sim.tour.running() && sim.tour.hold().has_value() && sim.stops() == 0); // V stays held, nothing is cast
	CHECK((*last_state(sim.messages))["state"] == "paused");
	sim.menu = false;
	while (sim.stops() == 0)
		sim.step();
	CHECK(sim.log.back().find("settled after 0.") != std::string::npos); // the 30 s behind the menu did not count
	sim.tour.request_stop("stopped by /cyberblock survey stop");
	sim.step();
	sim.run(0.2);
	CHECK(!sim.tour.running() && sim.actions.back() == Action::End);
	CHECK((*last_state(sim.messages))["state"] == "stopped");

	Sim off;
	CHECK(off.start());
	off.run(2.0);
	off.blocked = "off";
	off.run(0.3);
	CHECK(!off.tour.running() && off.actions.back() == Action::End && (*last_state(off.messages))["reason"] == "off");
}

TEST(tour_resumes_without_the_done_squares_and_reset_forgets_it)
{
	const auto area = make_area(pins());
	size_t total = 0;
	const auto squares = plan(*area, {}, &total);
	std::vector<Stop> done;
	for (const Square &s : squares)
		if (!(s.stop == Stop{3, 3}))
			done.push_back(s.stop);
	Sim sim;
	CHECK(sim.start(done));
	CHECK(sim.tour.hold()->x == 600.0 && sim.tour.hold()->y == 600.0); // the only square left
	sim.run(60.0);
	CHECK(!sim.tour.running() && sim.reported() == (std::set<Stop>{{3, 3}}));
	CHECK((*last_state(sim.messages))["done"] == total);

	Sim again;
	CHECK(again.start());
	again.tour.reset();
	CHECK(!again.tour.running() && !again.tour.hold() && again.tour.tick(at(1.0)) == Action::None);

	done.push_back({3, 3});
	Tour tour;
	tour.request_start(done);
	CHECK(tour.tick(at(0.0)) == Action::Bounds);
	const auto p = pins();
	tour.area(p);
	CHECK(!tour.running());
	const auto messages = tour.take_messages();
	CHECK((*last_state(messages))["state"] == "done");
}

TEST(tour_coarsens_a_big_city_to_finish_within_the_budget)
{
	Sim sim;
	CHECK(sim.start({}, 3000)); // a 3 x 3 km city
	const double begun = sim.now;
	sim.run(kBudgetSeconds + 10.0, 20); // 20 columns a frame: 0.8 ms each, every 2-m cell would take half an hour
	CHECK(!sim.tour.running() && (*last_state(sim.messages))["state"] == "done");
	CHECK(sim.now - begun < kBudgetSeconds);
	bool coarser = false;
	for (const auto &l : sim.log)
		coarser |= l.find("plan: city every 4 m") != std::string::npos || l.find("plan: city every 8 m") != std::string::npos;
	CHECK(coarser);
	const auto area = make_area(pins(3000));
	size_t total = 0;
	plan(*area, {}, &total);
	CHECK(sim.reported().size() == total); // the whole city, coarser, rather than gaps
}

TEST(tour_stops_at_the_hard_budget_and_resumes_later)
{
	Sim sim;
	CHECK(sim.start({}, 3000));
	const double begun = sim.now;
	sim.run(kBudgetSeconds + 60.0, 1); // hopelessly slow rays
	CHECK(!sim.tour.running() && sim.now - begun < kBudgetSeconds + 1.0);
	CHECK((*last_state(sim.messages))["state"] == "stopped");
	CHECK((*last_state(sim.messages))["reason"].get<std::string>().find("15-minute") != std::string::npos);
	CHECK(sim.actions.back() == Action::End);
}
