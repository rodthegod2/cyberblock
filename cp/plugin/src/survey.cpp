#include "survey.hpp"
#include "wire.hpp"
#include <algorithm>
#include <cmath>
#include <cstdio>

namespace
{
	using nlohmann::json;

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

	Columns::Columns()
	{
		const int r = int(kRadius / kSurveyCell);
		for (int dz = -r; dz <= r; ++dz)
			for (int dx = -r; dx <= r; ++dx)
				if (dx * dx + dz * dz <= r * r)
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

	// ---- tour: plan and requests --------------------------------------------------------------------------------------

	std::array<double, 2> stop_centre(Stop s)
	{
		return {s.gx * kTourSpacing, s.gy * kTourSpacing};
	}

	std::vector<Stop> plan(double minX, double minY, double maxX, double maxY, const std::set<Stop> &done, size_t *total)
	{
		std::vector<Stop> out;
		if (total)
			*total = 0;
		if (!(minX <= maxX && minY <= maxY) || !std::isfinite(minX) || !std::isfinite(maxX) || !std::isfinite(minY) || !std::isfinite(maxY))
			return out;
		// a stop's square reaches kTourHalf around it: every stop whose square touches the bounds
		const int gx0 = int(std::ceil((minX - kTourHalf) / kTourSpacing)), gx1 = int(std::floor((maxX + kTourHalf) / kTourSpacing));
		const int gy0 = int(std::ceil((minY - kTourHalf) / kTourSpacing)), gy1 = int(std::floor((maxY + kTourHalf) / kTourSpacing));
		size_t count = 0;
		for (int gy = gy0; gy <= gy1; ++gy)
		{
			const bool forward = (gy - gy0) % 2 == 0;
			for (int i = 0; i <= gx1 - gx0; ++i)
			{
				const Stop s{forward ? gx0 + i : gx1 - i, gy};
				++count;
				if (!done.contains(s))
					out.push_back(s);
			}
		}
		if (total)
			*total = count;
		return out;
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

	// ---- tour: the state machine ----------------------------------------------------------------------------------------

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

	void Tour::status(const std::string &state, const std::string &reason, std::optional<Stop> finished)
	{
		const size_t remaining = m_plan.size() > m_index ? m_plan.size() - m_index : 0;
		json m = {{"t", "surveystate"},
				  {"state", state},
				  {"done", m_total >= remaining ? m_total - remaining : 0},
				  {"total", m_total},
				  {"eta", state == "running" || state == "paused" ? std::round(eta()) : -1.0},
				  {"reason", wire::truncate_utf8(reason, kTextBytes)}};
		if (finished)
			m["stop"] = {finished->gx, finished->gy};
		m_messages.push_back(std::move(m));
		m_lastStatus = m_now;
	}

	double Tour::eta() const
	{
		if (m_stopsThisRun == 0)
			return -1.0;
		const size_t remaining = m_plan.size() > m_index ? m_plan.size() - m_index : 0;
		return m_stopSeconds / double(m_stopsThisRun) * double(remaining);
	}

	void Tour::arrive(double now)
	{
		m_arrived = now;
		m_stableSince = now;
		m_signature.clear();
		m_highest = -1e300;
		m_columnsDone = false;
		m_columns = m_hits = 0;
		m_phase = Phase::Settle;
	}

	void Tour::end(const std::string &state, const std::string &reason)
	{
		m_endState = state;
		m_endReason = reason;
		m_phase = Phase::Ending;
	}

	void Tour::finish_stop(double now, const char *how)
	{
		const Stop s = m_plan[m_index];
		const double seconds = now - m_arrived;
		m_stopSeconds += seconds;
		++m_stopsThisRun;
		++m_index;
		char line[192];
		std::snprintf(line, sizeof line, "survey: stop %zu/%zu (%d, %d) %s: %zu columns, %zu hits in %.1f s; eta %s",
					  m_total - (m_plan.size() - m_index), m_total, s.gx, s.gy, how, m_columns, m_hits, seconds, minutes(eta()).c_str());
		m_log.push_back(line);
		status("running", "", s);
		if (m_highest > -1e299)
			m_holdZ = std::max(kTourHoldZ, m_highest + kTourHoldAbove);
		if (m_index >= m_plan.size())
			end("done", "every stop surveyed");
		else
			arrive(now);
	}

	Action Tour::tick(const TourIn &in)
	{
		m_now = in.now;
		if (m_statusWanted)
		{
			m_statusWanted = false;
			if (m_phase == Phase::Idle)
				status("idle", "");
			else
				status(in.menu ? "paused" : "running", "");
		}
		switch (m_phase)
		{
		case Phase::Idle:
			return Action::None;
		case Phase::Requested:
			if (!in.blocked.empty())
			{
				m_phase = Phase::Idle;
				m_plan.clear();
				m_index = m_total = 0;
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
			return Action::None; // the scripts call bounds() right after Action::Bounds
		case Phase::Ending:
		{
			m_phase = Phase::Idle;
			status(m_endState, m_endReason);
			const size_t remaining = m_plan.size() > m_index ? m_plan.size() - m_index : 0;
			char line[192];
			std::snprintf(line, sizeof line, "survey: %s (%s) after %zu stops this run, %zu of %zu done", m_endState.c_str(),
						  m_endReason.c_str(), m_stopsThisRun, m_total - std::min(m_total, remaining), m_total);
			m_log.push_back(line);
			return Action::End;
		}
		default:
			break;
		}

		// Begin, Settle, Sample: running
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
		}
		if (in.now - m_lastStatus >= kStatusSeconds)
			status("running", "");

		switch (m_phase)
		{
		case Phase::Begin:
			arrive(in.now);
			return Action::Begin;
		case Phase::Settle:
			return Action::Settle;
		case Phase::Sample:
			if (m_columnsDone)
			{
				finish_stop(in.now, "sampled");
				return m_phase == Phase::Ending ? tick(in) : Action::Settle;
			}
			return Action::Sample;
		default:
			return Action::None;
		}
	}

	void Tour::bounds(double minX, double minY, double maxX, double maxY, int pins)
	{
		if (m_phase != Phase::AwaitBounds)
			return;
		if (pins < kMinPins || !std::isfinite(minX) || !std::isfinite(minY) || !std::isfinite(maxX) || !std::isfinite(maxY))
		{
			m_phase = Phase::Idle;
			status("refused", "not enough map pins to find Night City's bounds");
			m_log.push_back("survey: refused (" + std::to_string(pins) + " map pins)");
			return;
		}
		const auto clampAxis = [](double &lo, double &hi)
		{
			lo -= kBoundsMargin;
			hi += kBoundsMargin;
			const double mid = (lo + hi) / 2.0;
			lo = std::max({lo, mid - kMaxSpan / 2.0, -kWorldLimit});
			hi = std::min({hi, mid + kMaxSpan / 2.0, kWorldLimit});
		};
		clampAxis(minX, maxX);
		clampAxis(minY, maxY);
		m_plan = plan(minX, minY, maxX, maxY, m_done, &m_total);
		m_index = 0;
		m_stopsThisRun = 0;
		m_stopSeconds = 0.0;
		m_holdZ = std::max(kTourHoldZ, m_home.z + kTourHoldAbove);
		char line[224];
		std::snprintf(line, sizeof line, "survey: bounds x %.0f..%.0f, y %.0f..%.0f from %d map pins: %zu stops, %zu left, home (%.1f, %.1f, %.1f)",
					  minX, maxX, minY, maxY, pins, m_total, m_plan.size(), m_home.x, m_home.y, m_home.z);
		m_log.push_back(line);
		if (m_plan.empty())
		{
			m_phase = Phase::Idle;
			status("done", "every stop was already surveyed");
			return;
		}
		m_phase = Phase::Begin;
		status("running", "");
	}

	void Tour::settle(std::span<const Hit> probes, double now)
	{
		if (m_phase != Phase::Settle || m_pausedAt >= 0.0)
			return;
		std::vector<int> signature;
		bool anyGround = false, anyWater = false;
		double highest = -1e300;
		for (const Hit &p : probes)
		{
			const bool hit = (p.kind == 1 || p.kind == 2) && std::isfinite(p.z);
			signature.push_back(hit ? p.kind : 0);
			signature.push_back(hit ? int(std::lround(p.z * 2.0)) : 0);
			anyGround |= hit && p.kind == 1;
			anyWater |= hit && p.kind == 2;
			if (hit)
				highest = std::max(highest, p.z);
		}
		if (signature != m_signature)
		{
			m_signature = std::move(signature);
			m_stableSince = now;
		}
		const double elapsed = now - m_arrived;
		const bool stable = elapsed >= kSettleMin && now - m_stableSince >= kSettleStable;
		const bool timedOut = elapsed >= kSettleTimeout;
		if (!(stable && (anyGround || anyWater)) && !timedOut)
			return;
		if (!anyGround)
		{
			// only water, or nothing at all (outside the streamed world): nothing to map here
			m_highest = highest;
			finish_stop(now, anyWater ? "skipped (water)" : "skipped (nothing streamed)");
			return;
		}
		m_highest = highest;
		const auto [x, y] = stop_centre(m_plan[m_index]);
		const auto [cx0, cz0] = cell_of(x - kTourHalf, y + kTourHalf);
		const auto [cx1, cz1] = cell_of(x + kTourHalf, y - kTourHalf);
		m_cx0 = m_cx = cx0;
		m_cz0 = m_cz = cz0;
		m_cx1 = cx1;
		m_cz1 = cz1;
		m_columnsDone = false;
		m_phase = Phase::Sample;
	}

	std::optional<std::array<double, 2>> Tour::next_column()
	{
		if (m_phase != Phase::Sample || m_columnsDone)
			return std::nullopt;
		if (m_cz > m_cz1)
		{
			m_columnsDone = true;
			return std::nullopt;
		}
		const std::array<double, 2> column = survey_column(m_cx, m_cz);
		if (++m_cx > m_cx1)
		{
			m_cx = m_cx0;
			++m_cz;
		}
		return column;
	}

	void Tour::sampled(size_t columns, size_t hits)
	{
		m_columns += columns;
		m_hits += hits;
	}

	std::optional<Vec3> Tour::hold() const
	{
		if (m_phase != Phase::Settle && m_phase != Phase::Sample)
			return std::nullopt;
		return stop();
	}

	Vec3 Tour::stop() const
	{
		if (m_index >= m_plan.size())
			return m_home;
		const auto [x, y] = stop_centre(m_plan[m_index]);
		return {x, y, m_holdZ};
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
		m_plan.clear();
		m_index = m_total = 0;
		m_pausedAt = -1.0;
		m_stopWhy.clear();
	}
}
