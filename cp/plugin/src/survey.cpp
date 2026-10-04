#include "survey.hpp"
#include "wire.hpp"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <numbers>

namespace
{
	using nlohmann::json;
	using cb::survey::P2;

	int64_t key(int cx, int cz)
	{
		return (int64_t(cx) << 32) ^ int64_t(uint32_t(cz));
	}

	/// The survey cell of a Cyberpunk column.
	std::pair<int, int> cell_of(double x, double y)
	{
		const cb::SurveyCell c = cb::survey_cell(x, y, 0.0, 0.0);
		return {c.cx, c.cz};
	}

	std::string minutes(double seconds)
	{
		if (seconds < 0.0)
			return "unknown";
		const long s = long(seconds + 0.5);
		char text[32];
		std::snprintf(text, sizeof text, "%ldh%02ldm", s / 3600, (s / 60) % 60);
		return text;
	}

	double cross(P2 o, P2 a, P2 b)
	{
		return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
	}

	double distance(P2 a, P2 b)
	{
		return std::hypot(a.x - b.x, a.y - b.y);
	}

	double segment_distance(P2 p, P2 a, P2 b)
	{
		const double dx = b.x - a.x, dy = b.y - a.y, len2 = dx * dx + dy * dy;
		const double t = len2 > 0.0 ? std::clamp(((p.x - a.x) * dx + (p.y - a.y) * dy) / len2, 0.0, 1.0) : 0.0;
		return distance(p, {a.x + t * dx, a.y + t * dy});
	}

	/// The first cell (cx, cz) of square s: its north-west corner in Minecraft space.
	std::pair<int, int> first_cell(cb::survey::Stop s)
	{
		return {cb::survey::kSquareCells * s.gx - cb::survey::kSquareCells / 2, -cb::survey::kSquareCells * s.gy - cb::survey::kSquareCells / 2};
	}

	/// The distance from p to the nearest and to the farthest point of square s.
	std::pair<double, double> square_reach(cb::survey::Stop s, P2 p)
	{
		const auto [x, y] = cb::survey::stop_centre(s);
		const double dx = std::abs(p.x - x), dy = std::abs(p.y - y), h = cb::survey::kTourHalf;
		return {std::hypot(std::max(0.0, dx - h), std::max(0.0, dy - h)), std::hypot(dx + h, dy + h)};
	}
}

namespace cb::survey
{
	std::vector<json> make_terrain(std::span<const Hit> hits, double off)
	{
		std::vector<json> out;
		json cells = json::array();
		for (const Hit &h : hits)
		{
			if ((h.kind != 1 && h.kind != 2) || !std::isfinite(h.x) || !std::isfinite(h.y) || !std::isfinite(h.z) ||
				std::abs(h.x) >= wire::kMaxCoord || std::abs(h.y) >= wire::kMaxCoord || std::abs(h.z) >= 20000.0)
				continue;
			const SurveyCell c = survey_cell(h.x, h.y, h.z, off);
			cells.insert(cells.end(), {c.cx, c.cz, c.h, h.kind == 2 ? 1 : 0});
			if (cells.size() == 4 * kCellsPerMessage)
			{
				out.push_back({{"t", "terrain"}, {"c", std::move(cells)}});
				cells = json::array();
			}
		}
		if (!cells.empty())
			out.push_back({{"t", "terrain"}, {"c", std::move(cells)}});
		return out;
	}

	// ---- walking survey -----------------------------------------------------------------------------------------------

	Columns::Columns(double minRadius)
	{
		const int r = int(kRadius / kSurveyCell);
		const double inner = minRadius / kSurveyCell;
		for (int dz = -r; dz <= r; ++dz)
			for (int dx = -r; dx <= r; ++dx)
				if (dx * dx + dz * dz <= r * r && double(dx * dx + dz * dz) >= inner * inner)
					m_offsets.emplace_back(dx, dz);
		std::stable_sort(m_offsets.begin(), m_offsets.end(), [](const auto &a, const auto &b)
						 { return a.first * a.first + a.second * a.second < b.first * b.first + b.second * b.second; });
	}

	std::optional<std::array<double, 2>> Columns::next(Vec3 cpFeet, double now)
	{
		if (!std::isfinite(cpFeet.x) || !std::isfinite(cpFeet.y))
			return std::nullopt;
		const auto [fx, fz] = cell_of(cpFeet.x, cpFeet.y);
		const int moved = std::max(std::abs(fx - m_centreX), std::abs(fz - m_centreZ));
		if (m_pass && moved > 8)
			m_pass = false; // V walked 16 m: start again around where V is now, nearest first
		if (!m_pass)
		{
			if (now < m_idleUntil && moved <= 8)
				return std::nullopt;
			m_pass = true;
			m_cursor = 0;
			m_centreX = fx;
			m_centreZ = fz;
			if (m_until.size() > 4 * m_offsets.size())
			{
				const int far = 3 * int(kRadius / kSurveyCell);
				std::erase_if(m_until, [&](const auto &entry)
							  {
								  const int cx = int(entry.first >> 32), cz = int(int32_t(uint32_t(entry.first)));
								  return entry.second <= now || std::abs(cx - fx) > far || std::abs(cz - fz) > far;
							  });
			}
		}
		while (m_cursor < m_offsets.size())
		{
			const int cx = m_centreX + m_offsets[m_cursor].first, cz = m_centreZ + m_offsets[m_cursor].second;
			++m_cursor;
			double &until = m_until[key(cx, cz)];
			if (until <= now)
			{
				until = now + kRefreshSeconds;
				return survey_column(cx, cz);
			}
		}
		m_pass = false;
		m_idleUntil = now + 1.0;
		return std::nullopt;
	}

	void Columns::missed(double x, double y, double now)
	{
		if (!std::isfinite(x) || !std::isfinite(y))
			return;
		const auto [cx, cz] = cell_of(x, y);
		m_until[key(cx, cz)] = now + kMissRetrySeconds;
	}

	void Columns::clear()
	{
		m_until.clear();
		m_pass = false;
		m_idleUntil = -1e300;
	}

	// ---- frame budget -------------------------------------------------------------------------------------------------

	double frame_budget_ms(double intervalMs, double lastWorkMs)
	{
		if (!std::isfinite(intervalMs) || !std::isfinite(lastWorkMs))
			return kMinBudgetMs;
		const double game = std::max(0.0, intervalMs - std::max(0.0, lastWorkMs));
		return std::clamp(kFrameTargetMs - game, kMinBudgetMs, kMaxBudgetMs);
	}

	// ---- area ---------------------------------------------------------------------------------------------------------

	std::vector<P2> convex_hull(std::vector<P2> points)
	{
		std::erase_if(points, [](P2 p) { return !std::isfinite(p.x) || !std::isfinite(p.y); });
		std::sort(points.begin(), points.end(), [](P2 a, P2 b) { return a.x < b.x || (a.x == b.x && a.y < b.y); });
		points.erase(std::unique(points.begin(), points.end(), [](P2 a, P2 b) { return a.x == b.x && a.y == b.y; }), points.end());
		if (points.size() < 3)
			return points;
		// Andrew's monotone chain: lower hull, then upper hull, counter-clockwise
		std::vector<P2> hull(2 * points.size());
		size_t k = 0;
		for (const P2 &p : points)
		{
			while (k >= 2 && cross(hull[k - 2], hull[k - 1], p) <= 0.0)
				--k;
			hull[k++] = p;
		}
		for (size_t i = points.size() - 1, lower = k + 1; i-- > 0;)
		{
			while (k >= lower && cross(hull[k - 2], hull[k - 1], points[i]) <= 0.0)
				--k;
			hull[k++] = points[i];
		}
		hull.resize(k - 1);
		return hull;
	}

	double hull_distance(const std::vector<P2> &hull, P2 p)
	{
		if (hull.empty())
			return INFINITY;
		if (hull.size() == 1)
			return distance(p, hull[0]);
		bool inside = hull.size() >= 3;
		double nearest = INFINITY;
		for (size_t i = 0; i < hull.size(); ++i)
		{
			const P2 a = hull[i], b = hull[(i + 1) % hull.size()];
			if (cross(a, b, p) < 0.0)
				inside = false;
			nearest = std::min(nearest, segment_distance(p, a, b));
		}
		return inside ? 0.0 : nearest;
	}

	double hull_area(const std::vector<P2> &hull)
	{
		if (hull.size() < 3)
			return 0.0;
		double twice = 0.0;
		for (size_t i = 0; i < hull.size(); ++i)
		{
			const P2 a = hull[i], b = hull[(i + 1) % hull.size()];
			twice += a.x * b.y - b.x * a.y;
		}
		return std::abs(twice) / 2.0;
	}

	std::optional<Area> make_area(std::span<const Pin> pins, std::string *why)
	{
		const auto fail = [why](std::string text) -> std::optional<Area>
		{
			if (why)
				*why = std::move(text);
			return std::nullopt;
		};
		std::vector<Pin> valid;
		for (const Pin &p : pins)
			if (std::isfinite(p.x) && std::isfinite(p.y) && !(p.x == 0.0 && p.y == 0.0) && std::abs(p.x) <= 10000.0 &&
				std::abs(p.y) <= 10000.0 && p.kind >= kCityTravel && p.kind <= kMapPin)
				valid.push_back(p);
		if (valid.size() < size_t(kMinPins))
			return fail("not enough map pins to find Night City (" + std::to_string(valid.size()) + ")");

		// a map pin belongs to the district of the nearest fast-travel point within kPinJoinRadius
		std::vector<P2> city, all;
		for (const Pin &p : valid)
		{
			all.push_back({p.x, p.y});
			int kind = p.kind;
			if (kind == kMapPin)
			{
				double best = kPinJoinRadius;
				for (const Pin &t : valid)
					if (t.kind != kMapPin && distance({p.x, p.y}, {t.x, t.y}) <= best)
					{
						best = distance({p.x, p.y}, {t.x, t.y});
						kind = t.kind;
					}
			}
			if (kind == kCityTravel)
				city.push_back({p.x, p.y});
		}
		Area area;
		size_t travel = 0;
		for (const Pin &p : valid)
			travel += p.kind == kCityTravel;
		if (travel < size_t(kMinCityPoints))
		{
			// no district information: the city is where the pins are dense
			std::unordered_map<int64_t, int> counts;
			const auto cell = [](const Pin &p) { return key(int(std::floor(p.x / kDenseCell)), int(std::floor(p.y / kDenseCell))); };
			for (const Pin &p : valid)
				++counts[cell(p)];
			city.clear();
			for (const Pin &p : valid)
				if (counts[cell(p)] >= kDenseCount)
					city.push_back({p.x, p.y});
			area.dense = true;
		}
		area.cityPoints = city.size();
		area.city = convex_hull(std::move(city));
		area.outskirts = convex_hull(std::move(all));
		if (area.city.size() < 3)
			return fail("the city's map pins do not span an area");
		return area;
	}

	// ---- squares ------------------------------------------------------------------------------------------------------

	std::array<double, 2> stop_centre(Stop s)
	{
		return {s.gx * kTourSpacing, s.gy * kTourSpacing};
	}

	std::array<double, 2> Square::column(int i) const
	{
		const int n = side();
		const auto [cx0, cz0] = first_cell(stop);
		return survey_column(cx0 + (i % n) * stride + stride / 2, cz0 + (i / n) * stride + stride / 2);
	}

	std::vector<Square> plan(const Area &area, const std::set<Stop> &done, size_t *total)
	{
		std::vector<Square> out;
		if (total)
			*total = 0;
		if (area.city.empty() && area.outskirts.empty())
			return out;
		double minX = INFINITY, minY = INFINITY, maxX = -INFINITY, maxY = -INFINITY;
		for (const auto *hull : {&area.city, &area.outskirts})
			for (const P2 &p : *hull)
			{
				minX = std::min(minX, p.x);
				maxX = std::max(maxX, p.x);
				minY = std::min(minY, p.y);
				maxY = std::max(maxY, p.y);
			}
		const double margin = std::max(kCityMargin, kOutskirtsMargin);
		const int gx0 = int(std::ceil(std::max(minX - margin, -kWorldLimit) / kTourSpacing));
		const int gx1 = int(std::floor(std::min(maxX + margin, kWorldLimit) / kTourSpacing));
		const int gy0 = int(std::ceil(std::max(minY - margin, -kWorldLimit) / kTourSpacing));
		const int gy1 = int(std::floor(std::min(maxY + margin, kWorldLimit) / kTourSpacing));
		size_t count = 0;
		for (int gy = gy0; gy <= gy1; ++gy)
			for (int gx = gx0; gx <= gx1; ++gx)
			{
				const Stop s{gx, gy};
				const auto [x, y] = stop_centre(s);
				Square square;
				square.stop = s;
				if (hull_distance(area.city, {x, y}) <= kCityMargin)
					square.outskirts = false;
				else if (hull_distance(area.outskirts, {x, y}) <= kOutskirtsMargin)
					square.outskirts = true;
				else
					continue;
				++count;
				if (done.contains(s))
					continue;
				square.stride = square.outskirts ? kOutskirtsStride : 1;
				square.sampled.assign(size_t(square.total()), false);
				out.push_back(std::move(square));
			}
		if (total)
			*total = count;
		return out;
	}

	std::optional<size_t> next_square(const std::vector<Square> &squares, P2 from, P2 home, int *group)
	{
		for (int g = 0; g < 4; ++g)
		{
			std::optional<size_t> best;
			double bestScore = INFINITY;
			for (size_t i = 0; i < squares.size(); ++i)
			{
				const Square &s = squares[i];
				if (s.done || (s.outskirts ? 2 : 0) + (s.visits > 0 ? 1 : 0) != g)
					continue;
				const auto [x, y] = stop_centre(s.stop);
				const double score = distance(from, {x, y}) + kHomeBias * distance(home, {x, y});
				if (score < bestScore)
				{
					bestScore = score;
					best = i;
				}
			}
			if (best)
			{
				if (group)
					*group = g;
				return best;
			}
		}
		return std::nullopt;
	}

	double estimate_seconds(const std::vector<Square> &squares, const Rates &rates, Strides strides)
	{
		double columns = 0.0, left = 0.0;
		for (const Square &s : squares)
		{
			if (s.done)
				continue;
			if (s.count > 0)
			{
				columns += double(s.total() - s.count);
				left += 1.0 - double(s.count) / double(s.total());
			}
			else
			{
				const int side = kSquareCells / (s.outskirts ? strides.outskirts : strides.city);
				columns += double(side * side);
				left += 1.0;
			}
		}
		const double stops = left / std::max(rates.squaresPerStop, 0.25);
		return stops * rates.stopSeconds + columns * rates.columnSeconds;
	}

	Strides choose_strides(const std::vector<Square> &squares, const Rates &rates, double secondsLeft)
	{
		for (const int city : kCityStrides)
			for (const int outskirts : kOutskirtsStrides)
				if (outskirts >= city && estimate_seconds(squares, rates, {city, outskirts}) <= secondsLeft)
					return {city, outskirts};
		return {kCityStrides.back(), kOutskirtsStrides.back()};
	}

	Strides replan_strides(const std::vector<Square> &squares, const Rates &rates, double secondsLeft, Strides current, bool first)
	{
		const Strides fit = choose_strides(squares, rates, secondsLeft);
		if (first || fit >= current)
			return fit; // the same, or coarser because the current plan no longer fits
		// finer only with room to spare, so the plan does not swing back and forth on a little noise
		const Strides roomy = choose_strides(squares, rates, secondsLeft * kRefineShare);
		return roomy < current ? roomy : current;
	}

	std::optional<Request> parse_request(const json &m, std::string *why)
	{
		try
		{
			if (!m.is_object())
				throw wire::Bad{"not an object"};
			static constexpr const char *kOps[] = {"start", "stop", "status"};
			Request r;
			r.op = wire::one_of(m, "op", kOps);
			if (r.op == "start" && m.contains("done"))
			{
				const json &done = m.at("done");
				if (!done.is_array() || done.size() % 2 != 0 || done.size() > 2 * kMaxDone)
					throw wire::Bad{"done is not a list of at most 4096 pairs"};
				for (size_t i = 0; i < done.size(); i += 2)
				{
					const json pair = {{"x", done[i]}, {"y", done[i + 1]}};
					r.done.push_back({wire::integer(pair, "x", -100000, 100000), wire::integer(pair, "y", -100000, 100000)});
				}
			}
			return r;
		}
		catch (const wire::Bad &bad)
		{
			if (why)
				*why = "survey: " + bad.why;
			return std::nullopt;
		}
	}

	// ---- tour: requests and messages ----------------------------------------------------------------------------------

	void Tour::request_start(std::vector<Stop> done)
	{
		if (running())
		{
			m_statusWanted = true;
			m_log.push_back("survey: start ignored, already running");
			return;
		}
		m_done = std::set<Stop>(done.begin(), done.end());
		m_phase = Phase::Requested;
	}

	void Tour::request_stop(std::string why)
	{
		if (running())
			m_stopWhy = why.empty() ? "stopped" : std::move(why);
		else
			m_statusWanted = true;
	}

	void Tour::request_status()
	{
		m_statusWanted = true;
	}

	bool Tour::running() const
	{
		return m_phase != Phase::Idle;
	}

	std::string Tour::group_name() const
	{
		static constexpr const char *kNames[] = {"city", "city retry", "outskirts", "outskirts retry"};
		return kNames[std::clamp(m_group, 0, 3)];
	}

	void Tour::status(const std::string &state, const std::string &reason, std::optional<Stop> finished)
	{
		json m = {{"t", "surveystate"},
				  {"state", state},
				  {"done", std::min(m_finished, m_total)},
				  {"total", m_total},
				  {"eta", state == "running" || state == "paused" ? std::round(eta()) : -1.0},
				  {"reason", wire::truncate_utf8(reason, kTextBytes)}};
		if (finished)
			m["stop"] = {finished->gx, finished->gy};
		m_messages.push_back(std::move(m));
		m_lastStatus = m_now;
	}

	double Tour::run_seconds(double now) const
	{
		double seconds = m_runSeconds;
		if (m_phase == Phase::Settle || m_phase == Phase::Reach || m_phase == Phase::Sample)
			seconds += std::max(0.0, (m_pausedAt >= 0.0 ? m_pausedAt : now) - m_arrived);
		return seconds;
	}

	Rates Tour::rates(double now) const
	{
		Rates r; // the guesses until there is something measured
		const bool sampling = m_phase == Phase::Sample && m_pausedAt < 0.0;
		const double sampleSeconds = m_sampleSeconds + (sampling ? std::max(0.0, now - m_sampleStart) : 0.0);
		const size_t columns = m_columnsRun + (sampling ? m_columns : 0);
		if (columns >= 100 && (columns >= 2000 || sampleSeconds >= 1.0))
			r.columnSeconds = sampleSeconds / double(columns);
		if (m_stopsThisRun >= 1)
			r.stopSeconds = std::max(0.0, m_runSeconds - m_sampleSeconds) / double(m_stopsThisRun);
		if (m_stopsThisRun >= 2 && m_progress - m_progressAtStart > 0.1)
			r.squaresPerStop = (m_progress - m_progressAtStart) / double(m_stopsThisRun);
		return r;
	}

	double Tour::eta() const
	{
		if (m_squares.empty())
			return -1.0;
		// the tour never runs past kBudgetSeconds, so neither does the time it shows
		return std::min(estimate_seconds(m_squares, rates(m_now), m_strides), std::max(0.0, kBudgetSeconds - run_seconds(m_now)));
	}

	void Tour::replan(double now)
	{
		m_lastPlan = now;
		const Rates r = rates(now);
		const Strides before = m_strides;
		m_strides = replan_strides(m_squares, r, kPlanSeconds - run_seconds(now), m_strides, !m_planned);
		// squares nobody sampled yet take the new strides (not the one the current stop is going through)
		const size_t busy = m_phase == Phase::Sample && m_candidate < m_candidates.size() ? m_candidates[m_candidate] : SIZE_MAX;
		for (size_t i = 0; i < m_squares.size(); ++i)
		{
			Square &s = m_squares[i];
			const int stride = s.outskirts ? m_strides.outskirts : m_strides.city;
			if (i != busy && !s.done && s.count == 0 && s.stride != stride)
			{
				s.stride = stride;
				s.sampled.assign(size_t(s.total()), false);
			}
		}
		if (m_strides != before || !m_planned)
		{
			m_planned = true;
			char line[256];
			std::snprintf(line, sizeof line,
						  "survey: plan: city every %d m, outskirts every %d m, about %.0f s left of the %.0f s plan (%.1f us/column, %.2f s/stop, "
						  "%.1f squares/stop%s)",
						  m_strides.city * kSurveyCell, m_strides.outskirts * kSurveyCell, estimate_seconds(m_squares, r, m_strides),
						  kPlanSeconds, r.columnSeconds * 1e6, r.stopSeconds, r.squaresPerStop, m_stopsThisRun == 0 ? ", guessed" : ", measured");
			m_log.push_back(line);
		}
	}

	void Tour::end(const std::string &state, const std::string &reason)
	{
		m_endState = state;
		m_endReason = reason;
		m_phase = Phase::Ending;
	}

	Vec3 Tour::centre() const
	{
		if (m_target >= m_squares.size())
			return m_home;
		const auto [x, y] = stop_centre(m_squares[m_target].stop);
		return {x, y, m_holdZ};
	}

	// ---- tour: the state machine ----------------------------------------------------------------------------------------

	Action Tour::tick(const TourIn &in)
	{
		m_now = in.now;
		if (m_statusWanted)
		{
			m_statusWanted = false;
			if (m_phase == Phase::Idle)
				status("idle", "");
			else
				status(in.menu ? "paused" : "running", in.menu ? "menu" : group_name());
		}
		switch (m_phase)
		{
		case Phase::Idle:
			return Action::None;
		case Phase::Requested:
			if (!in.blocked.empty())
			{
				m_phase = Phase::Idle;
				m_squares.clear();
				m_total = m_finished = 0;
				status("refused", in.blocked);
				m_log.push_back("survey: refused (" + in.blocked + ")");
				return Action::None;
			}
			m_home = in.feet;
			m_homeYaw = in.yaw;
			m_phase = Phase::AwaitBounds;
			return Action::Bounds;
		case Phase::AwaitBounds:
			if (!in.blocked.empty())
			{
				m_phase = Phase::Idle;
				status("refused", in.blocked);
				return Action::None;
			}
			return Action::None; // the scripts call area() right after Action::Bounds
		case Phase::Ending:
		{
			m_phase = Phase::Idle;
			status(m_endState, m_endReason);
			char line[192];
			std::snprintf(line, sizeof line, "survey: %s (%s) after %zu stops in %.0f s this run, %zu of %zu squares done",
						  m_endState.c_str(), m_endReason.c_str(), m_stopsThisRun, m_runSeconds, m_finished, m_total);
			m_log.push_back(line);
			return Action::End;
		}
		default:
			break;
		}

		// Begin, Settle, Reach, Sample: running
		if (!m_stopWhy.empty())
		{
			end("stopped", std::exchange(m_stopWhy, std::string()));
			return tick(in);
		}
		if (!in.blocked.empty())
		{
			end("stopped", in.blocked);
			return tick(in);
		}
		if (in.menu)
		{
			if (m_pausedAt < 0.0)
			{
				m_pausedAt = in.now;
				status("paused", "menu");
			}
			return Action::None;
		}
		if (m_pausedAt >= 0.0)
		{
			// the time behind the menu does not count for streaming or the stop's duration
			const double paused = in.now - std::exchange(m_pausedAt, -1.0);
			m_arrived += paused;
			m_stableSince += paused;
			m_sampleStart += paused;
		}
		if (m_phase != Phase::Begin && run_seconds(in.now) >= kBudgetSeconds)
		{
			end("stopped", "15-minute budget used; /cyberblock survey resumes");
			return tick(in);
		}
		if (m_phase == Phase::Begin)
		{
			if (!go_next(in.now))
			{
				end("done", "every square was already surveyed");
				return tick(in);
			}
			status("running", group_name());
			return Action::Begin;
		}
		if (in.now - m_lastStatus >= kStatusSeconds)
			status("running", group_name());
		return Action::None; // the DLL's per-frame rays do the stop's work
	}

	void Tour::area(std::span<const Pin> pins)
	{
		if (m_phase != Phase::AwaitBounds)
			return;
		std::string why;
		const std::optional<Area> area = make_area(pins, &why);
		if (!area)
		{
			m_phase = Phase::Idle;
			status("refused", why);
			m_log.push_back("survey: refused (" + why + ")");
			return;
		}
		m_squares = plan(*area, m_done, &m_total);
		m_finished = m_total - m_squares.size();
		m_progress = m_progressAtStart = double(m_finished);
		m_runSeconds = m_sampleSeconds = 0.0;
		m_lastPlan = -1e300;
		m_planned = false;
		m_columnsRun = 0;
		m_stopsThisRun = 0;
		m_strides = {};
		m_target = 0;
		m_group = 0;
		m_from = {m_home.x, m_home.y};
		m_holdZ = std::max(kTourHoldZ, m_home.z + kTourHoldAbove);
		size_t city = 0, outskirts = 0, columns = 0;
		for (const Square &s : m_squares)
		{
			(s.outskirts ? outskirts : city) += 1;
			columns += size_t(kSquareCells * kSquareCells);
		}
		const double squareKm2 = kTourSpacing * kTourSpacing / 1e6;
		char line[384];
		std::snprintf(line, sizeof line,
					  "survey: area from %zu pins: city hull %.1f km2 (%zu %s points), outskirts hull %.1f km2; squares left: %zu city "
					  "(%.1f km2) + %zu outskirts (%.1f km2), %zu of %zu done; %zu columns at full detail; home (%.1f, %.1f, %.1f)",
					  pins.size(), hull_area(area->city) / 1e6, area->cityPoints, area->dense ? "dense-pin" : "city", hull_area(area->outskirts) / 1e6,
					  city, double(city) * squareKm2, outskirts, double(outskirts) * squareKm2, m_finished, m_total, columns, m_home.x, m_home.y,
					  m_home.z);
		m_log.push_back(line);
		replan(m_now);
		if (m_squares.empty())
		{
			m_phase = Phase::Idle;
			status("done", "every square was already surveyed");
			return;
		}
		m_phase = Phase::Begin;
		status("running", "");
	}

	bool Tour::go_next(double now)
	{
		int group = 0;
		std::optional<size_t> next = next_square(m_squares, m_from, {m_home.x, m_home.y}, &group);
		if (next && group % 2 == 1 && m_runSeconds >= kPlanSeconds)
		{
			// no time for retries: what those squares have stays on the map (the walking survey fills it in later)
			for (Square &s : m_squares)
				if (!s.done && s.visits > 0)
					finish_square(s);
			m_log.push_back("survey: the plan's time is used up, retries skipped");
			next = next_square(m_squares, m_from, {m_home.x, m_home.y}, &group);
		}
		if (!next)
			return false;
		m_target = *next;
		m_group = group;
		const auto [x, y] = stop_centre(m_squares[m_target].stop);
		m_from = {x, y};
		m_arrived = m_stableSince = now;
		m_signature.clear();
		m_anyHit = m_settled = false;
		m_settleSeconds = 0.0;
		m_highest = -1e300;
		m_reach = 0.0;
		m_radius = kMinReach;
		m_candidates.clear();
		m_handed.clear();
		m_hit.clear();
		m_pending.reset();
		m_next.reset();
		m_columns = m_hits = 0;
		m_rayMs = 0.0;
		m_phase = Phase::Settle;
		return true;
	}

	Need Tour::need(double now) const
	{
		if (m_pausedAt >= 0.0)
			return Need::None;
		switch (m_phase)
		{
		case Phase::Settle:
			return now > m_arrived ? Need::Probes : Need::None; // V is held at the new stop from the next frame on
		case Phase::Reach:
			return Need::Probes;
		case Phase::Sample:
			return Need::Columns;
		default:
			return Need::None;
		}
	}

	std::vector<std::array<double, 2>> Tour::probes() const
	{
		std::vector<std::array<double, 2>> out;
		const Vec3 c = centre();
		if (m_phase == Phase::Settle)
		{
			for (int dy = -1; dy <= 1; ++dy)
				for (int dx = -1; dx <= 1; ++dx)
					out.push_back({c.x + kProbeStep * dx, c.y + kProbeStep * dy});
		}
		else if (m_phase == Phase::Reach)
		{
			for (size_t ring = 0; ring < kReachRings.size(); ++ring)
				for (int k = 0; k < kReachRays; ++k)
				{
					// each ring turned by half a step against the last, so the rings do not probe the same few lines
					const double angle = 2.0 * std::numbers::pi * (double(k) + 0.5 * double(ring % 2)) / kReachRays;
					out.push_back({c.x + kReachRings[ring] * std::cos(angle), c.y + kReachRings[ring] * std::sin(angle)});
				}
		}
		return out;
	}

	void Tour::probed(std::span<const Hit> hits, double now)
	{
		if (m_pausedAt >= 0.0)
			return;
		const auto hit = [](const Hit &h) { return (h.kind == 1 || h.kind == 2) && std::isfinite(h.z); };
		if (m_phase == Phase::Settle)
		{
			std::vector<int> signature;
			bool any = false;
			double highest = -1e300;
			for (const Hit &p : hits)
			{
				const bool h = hit(p);
				signature.push_back(h ? p.kind : 0);
				signature.push_back(h ? int(std::lround(p.z * 2.0)) : 0);
				any |= h;
				if (h)
					highest = std::max(highest, p.z);
			}
			if (signature != m_signature)
			{
				m_signature = std::move(signature);
				m_stableSince = now;
			}
			const double elapsed = now - m_arrived;
			const bool stable = any && now - m_stableSince >= kSettleStable;
			if (!stable && elapsed < kSettleTimeout)
				return;
			m_settled = stable;
			m_anyHit = any;
			m_settleSeconds = elapsed;
			m_highest = highest;
			if (!any)
			{
				finish_stop(now); // nothing streamed (or nothing there): the square goes to the retry pass
				return;
			}
			m_phase = Phase::Reach;
			return;
		}
		if (m_phase != Phase::Reach)
			return;

		// the reach: the widest ring such that it and every ring inside it is loaded
		m_reach = 0.0;
		bool loaded = true;
		for (size_t ring = 0; ring < kReachRings.size(); ++ring)
		{
			int count = 0;
			for (int k = 0; k < kReachRays; ++k)
			{
				const size_t i = ring * kReachRays + size_t(k);
				if (i < hits.size() && hit(hits[i]))
				{
					++count;
					m_highest = std::max(m_highest, hits[i].z);
				}
			}
			loaded = loaded && count >= kReachHits;
			if (loaded)
				m_reach = kReachRings[ring];
		}
		m_radius = std::clamp(m_reach, kMinReach, kReachRings.back());

		// every unfinished square the reach touches; while V flies towards the next stop the squares farthest from it go
		// first, so the columns left for the end of the flight are the ones nearest V
		const Vec3 c = centre();
		m_candidates.clear();
		for (size_t i = 0; i < m_squares.size(); ++i)
			if (!m_squares[i].done && square_reach(m_squares[i].stop, {c.x, c.y}).first <= m_radius)
				m_candidates.push_back(i);
		m_next = predict_next();
		std::vector<std::pair<double, size_t>> order;
		for (const size_t i : m_candidates)
		{
			const auto [x, y] = stop_centre(m_squares[i].stop);
			order.emplace_back(m_next ? (x - c.x) * (m_next->x - c.x) + (y - c.y) * (m_next->y - c.y) : std::hypot(x - c.x, y - c.y), i);
		}
		std::stable_sort(order.begin(), order.end(), [](const auto &a, const auto &b) { return a.first < b.first; });
		m_candidates.clear();
		for (const auto &[key, i] : order)
			m_candidates.push_back(i);
		m_sampleStart = now;
		m_handed.assign(m_candidates.size(), 0);
		m_hit.assign(m_candidates.size(), 0);
		m_skipped.assign(m_candidates.size(), 0);
		m_candidate = 0;
		m_columnIndex = 0;
		m_pending.reset();
		m_phase = Phase::Sample;
	}

	std::optional<std::array<double, 2>> Tour::next_column(double now)
	{
		if (m_phase != Phase::Sample || m_pausedAt >= 0.0)
			return std::nullopt;
		if (now - m_lastPlan >= kReplanSeconds)
			replan(now); // the first stops measure the real cost of a column: the plan follows within a second
		const Vec3 c = centre();
		const std::optional<Vec3> v = hold();
		const double r2 = m_radius * m_radius;
		while (m_candidate < m_candidates.size())
		{
			const Square &s = m_squares[m_candidates[m_candidate]];
			while (!s.done && m_columnIndex < s.total())
			{
				const int i = m_columnIndex++;
				if (s.sampled[size_t(i)])
					continue;
				const std::array<double, 2> column = s.column(i);
				const double dx = column[0] - c.x, dy = column[1] - c.y;
				const double vx = column[0] - v->x, vy = column[1] - v->y;
				if (dx * dx + dy * dy > r2)
					continue; // beyond the stop's reach: a later stop casts it
				if (vx * vx + vy * vy > r2)
				{
					++m_skipped[m_candidate]; // beyond V's reach now that V flew on: a later stop casts it
					continue;
				}
				m_pending = {m_candidate, i};
				++m_handed[m_candidate];
				return column;
			}
			++m_candidate;
			m_columnIndex = 0;
		}
		finish_stop(now);
		return std::nullopt;
	}

	void Tour::column(const Hit &hit)
	{
		if (!m_pending)
			return;
		const auto [candidate, i] = *std::exchange(m_pending, std::nullopt);
		++m_columns;
		if (!((hit.kind == 1 || hit.kind == 2) && std::isfinite(hit.z)))
			return; // a miss stays unsampled: a later stop casts it again
		++m_hits;
		++m_hit[candidate];
		Square &s = m_squares[m_candidates[candidate]];
		if (!s.sampled[size_t(i)])
		{
			s.sampled[size_t(i)] = true;
			++s.count;
			m_progress += 1.0 / double(s.total());
		}
		if (s.stride > 1)
		{
			// one ray fills its whole stride x stride block of cells
			const auto [cx0, cz0] = first_cell(s.stop);
			const int n = s.side(), bx = cx0 + (i % n) * s.stride, bz = cz0 + (i / n) * s.stride;
			for (int dz = 0; dz < s.stride; ++dz)
				for (int dx = 0; dx < s.stride; ++dx)
				{
					const auto [x, y] = survey_column(bx + dx, bz + dz);
					m_cells.push_back({x, y, hit.z, hit.kind});
				}
		}
		else
			m_cells.push_back(hit);
		if (!s.done && s.count == s.total())
			finish_square(s);
	}

	void Tour::finish_square(Square &s)
	{
		if (s.done)
			return;
		s.done = true;
		m_progress += 1.0 - double(s.count) / double(s.total());
		++m_finished;
		status("running", group_name(), s.stop);
	}

	void Tour::finish_stop(double now)
	{
		const double seconds = now - m_arrived;
		if (m_phase == Phase::Sample)
			m_sampleSeconds += std::max(0.0, now - m_sampleStart);
		m_columnsRun += m_columns;
		m_runSeconds += seconds;
		++m_stopsThisRun;
		// a settled stop finishes every square that lay wholly inside its reach (each of its open columns was cast) once
		// enough of them hit: the rest are holes in Night City's collision, not streaming
		if (m_settled)
		{
			const Vec3 c = centre();
			for (size_t k = 0; k < m_candidates.size(); ++k)
			{
				Square &s = m_squares[m_candidates[k]];
				if (!s.done && m_skipped[k] == 0 && square_reach(s.stop, {c.x, c.y}).second <= m_radius &&
					double(s.count) >= kSquareCovered * double(s.total()))
					finish_square(s);
			}
		}
		Square &target = m_squares[m_target];
		++target.visits;
		const bool retry = !target.done && target.visits < kMaxVisits;
		if (!target.done && !retry)
			finish_square(target); // the retry did not do better: what it has stays on the map
		char line[320];
		std::snprintf(line, sizeof line,
					  "survey: stop %zu at (%d, %d) [%s]: %s after %.1f s, reach %.0f m (sampled %.0f m), %zu columns, %zu hits in %.0f ms "
					  "(%.1f us/column), %.1f s in all%s; %zu of %zu squares, eta %s",
					  m_stopsThisRun, target.stop.gx, target.stop.gy, group_name().c_str(),
					  m_settled ? "settled" : (m_anyHit ? "not settled" : "nothing streamed"), m_settleSeconds, m_reach, m_radius, m_columns,
					  m_hits, m_rayMs, m_columns ? 1000.0 * m_rayMs / double(m_columns) : 0.0, seconds, retry ? ", square kept for the retry pass" : "",
					  m_finished, m_total, minutes(eta()).c_str());
		m_log.push_back(line);
		if (m_highest > -1e299)
			m_holdZ = std::max(kTourHoldZ, m_highest + kTourHoldAbove);
		if (m_runSeconds >= kBudgetSeconds)
		{
			end("stopped", "15-minute budget used; /cyberblock survey resumes");
			return;
		}
		replan(now);
		if (!go_next(now))
			end("done", "every square surveyed");
	}

	void Tour::spent(double ms)
	{
		if (std::isfinite(ms) && ms > 0.0)
			m_rayMs += ms;
	}

	std::vector<Hit> Tour::take_cells()
	{
		return std::exchange(m_cells, {});
	}

	std::optional<Vec3> Tour::hold() const
	{
		if (m_phase != Phase::Settle && m_phase != Phase::Reach && m_phase != Phase::Sample)
			return std::nullopt;
		Vec3 c = centre();
		if (m_phase == Phase::Sample && m_next)
		{
			const double dx = m_next->x - c.x, dy = m_next->y - c.y, d = std::hypot(dx, dy);
			if (d > 1.0)
			{
				c.x += dx / d * drift();
				c.y += dy / d * drift();
			}
		}
		return c;
	}

	double Tour::drift() const
	{
		if (m_phase != Phase::Sample || !m_next || m_candidates.empty())
			return 0.0;
		const Vec3 c = centre();
		const double reach = std::min(kMaxDrift, 0.5 * std::hypot(m_next->x - c.x, m_next->y - c.y));
		double done = double(std::min(m_candidate, m_candidates.size()));
		if (m_candidate < m_candidates.size())
			done += double(m_columnIndex) / double(std::max(1, m_squares[m_candidates[m_candidate]].total()));
		return reach * std::clamp(done / double(m_candidates.size()), 0.0, 1.0);
	}

	std::optional<P2> Tour::predict_next()
	{
		// as if this stop finished its own square and every square wholly inside its reach
		const Vec3 c = centre();
		std::vector<size_t> assumed;
		for (const size_t i : m_candidates)
			if (!m_squares[i].done && (i == m_target || square_reach(m_squares[i].stop, {c.x, c.y}).second <= m_radius))
			{
				m_squares[i].done = true;
				assumed.push_back(i);
			}
		const std::optional<size_t> next = next_square(m_squares, {c.x, c.y}, {m_home.x, m_home.y});
		for (const size_t i : assumed)
			m_squares[i].done = false;
		if (!next)
			return std::nullopt;
		const auto [x, y] = stop_centre(m_squares[*next].stop);
		return P2{x, y};
	}

	std::vector<json> Tour::take_messages()
	{
		return std::exchange(m_messages, {});
	}

	std::vector<std::string> Tour::take_log()
	{
		return std::exchange(m_log, {});
	}

	void Tour::reset()
	{
		if (m_phase != Phase::Idle)
			m_log.push_back("survey: forgotten (the session ended)");
		m_phase = Phase::Idle;
		m_squares.clear();
		m_candidates.clear();
		m_pending.reset();
		m_cells.clear();
		m_total = m_finished = 0;
		m_pausedAt = -1.0;
		m_stopWhy.clear();
	}
}
