// The game-facing half of the Atlas survey (survey.hpp): the redscript natives (declared in
// cp/scripts/Cyberblock/SurveyNatives.reds), the `survey` requests from Minecraft and the `terrain` / `surveystate`
// messages to it, and the tour's hold handed to the drive (drive.hpp set_hold / request_return).
//
// RED4ext.SDK APIs as natives.cpp: GetParameter, DynArray<Vector4>, CName; registered through cb::natives::add.
#include "survey_game.hpp"
#include "drive.hpp"
#include "link.hpp"
#include "log.hpp"
#include "native_reg.hpp"
#include "natives.hpp"
#include "survey.hpp"
#include "world.hpp"
#include <RED4ext/RED4ext.hpp>
#include <RED4ext/Scripting/Natives/Generated/Vector4.hpp>
#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>

using RED4ext::CStackFrame;
using RED4ext::IScriptable;

namespace
{
	std::mutex g_lock;
	cb::survey::Columns g_columns; // under g_lock
	cb::survey::Tour g_tour;       // under g_lock
	int g_session = -1;            // the link session and alignment the walking survey's memory belongs to
	int g_levelK = -1;
	std::atomic<uint64_t> g_invalid{0};

	double now_seconds()
	{
		return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
	}

	std::string name_text(RED4ext::CName name)
	{
		if (name.IsNone())
			return {};
		const char *text = name.ToString();
		return text ? text : "?";
	}

	/// A new Minecraft session or another alignment: what was sent before may belong to another world or height, so the
	/// area around V is surveyed again. Call with g_lock held.
	void sync_memory()
	{
		const int session = cb::link().session(), k = cb::world::level_k();
		if (session != g_session || k != g_levelK)
		{
			g_session = session;
			g_levelK = k;
			g_columns.clear();
		}
	}

	/// Sends the tour's `surveystate` messages and writes its log lines. Call with g_lock held.
	void flush_tour()
	{
		for (nlohmann::json &m : g_tour.take_messages())
			if (cb::link().linked())
				cb::link().send(m);
		for (const std::string &line : g_tour.take_log())
			cb::log::info("%s", line.c_str());
	}

	RED4ext::Vector4 column_vector(const std::optional<std::array<double, 2>> &c)
	{
		if (!c)
			return {0.0f, 0.0f, 0.0f, 0.0f};
		return {float((*c)[0]), float((*c)[1]), 0.0f, 1.0f};
	}

	std::vector<cb::survey::Hit> to_hits(const RED4ext::DynArray<RED4ext::Vector4> &points)
	{
		std::vector<cb::survey::Hit> out;
		out.reserve(points.Size());
		for (const RED4ext::Vector4 &p : points)
			out.push_back({p.X, p.Y, p.Z, int(std::lround(p.W))});
		return out;
	}

	// Cyberblock_SurveyNext(feet: Vector4) -> Vector4: the next column (x, y) to cast a survey ray at, W 1; W 0 when there
	// is none this tick. During a tour: the stop's columns while it samples, nothing else; otherwise the walking survey
	// around V while Cyberblock is active and linked.
	void SurveyNext(IScriptable *, CStackFrame *frame, RED4ext::Vector4 *out, int64_t)
	{
		RED4ext::Vector4 feet;
		RED4ext::GetParameter(frame, &feet);
		++frame->code;
		std::optional<std::array<double, 2>> column;
		{
			std::lock_guard<std::mutex> lock(g_lock);
			if (g_tour.running())
				column = g_tour.next_column();
			else if (cb::natives::active && cb::link().linked())
			{
				sync_memory();
				column = g_columns.next({feet.X, feet.Y, feet.Z}, now_seconds());
			}
		}
		if (out)
			*out = column_vector(column);
	}

	// Cyberblock_SubmitSurvey(hits: array<Vector4>) -> Void: one ray per column handed out (x, y the column, z the surface,
	// W 0 miss, 1 ground or building, 2 water). Sends `terrain` (converted with the alignment) while linked.
	void SubmitSurvey(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::DynArray<RED4ext::Vector4> points;
		RED4ext::GetParameter(frame, &points);
		++frame->code;
		const std::vector<cb::survey::Hit> hits = to_hits(points);
		size_t found = 0;
		{
			std::lock_guard<std::mutex> lock(g_lock);
			const double now = now_seconds();
			for (const cb::survey::Hit &h : hits)
			{
				if (h.kind == 1 || h.kind == 2)
					++found;
				else if (!g_tour.running())
					g_columns.missed(h.x, h.y, now);
			}
			if (g_tour.running())
				g_tour.sampled(hits.size(), found);
		}
		if (found == 0 || !cb::link().linked())
			return;
		for (nlohmann::json &m : cb::survey::make_terrain(hits, cb::world::level_offset(cb::world::level_k())))
			cb::link().send(m);
	}

	// Cyberblock_SurveyTourTick(feet: Vector4, yaw: Float, blocked: CName, menu: Bool) -> CName: the tour's step this tick
	// (n"" none, n"bounds", n"begin", n"settle", n"sample", n"end"); hands the hold to the drive.
	void SurveyTourTick(IScriptable *, CStackFrame *frame, RED4ext::CName *out, int64_t)
	{
		RED4ext::Vector4 feet;
		float yaw = 0.0f;
		RED4ext::CName blocked;
		bool menu = false;
		RED4ext::GetParameter(frame, &feet);
		RED4ext::GetParameter(frame, &yaw);
		RED4ext::GetParameter(frame, &blocked);
		RED4ext::GetParameter(frame, &menu);
		++frame->code;
		cb::survey::TourIn in;
		in.now = now_seconds();
		in.feet = {feet.X, feet.Y, feet.Z};
		in.yaw = yaw;
		in.blocked = name_text(blocked);
		in.menu = menu;
		const char *name = "";
		{
			std::lock_guard<std::mutex> lock(g_lock);
			const cb::survey::Action action = g_tour.tick(in);
			switch (action)
			{
			case cb::survey::Action::Bounds: name = "bounds"; break;
			case cb::survey::Action::Begin: name = "begin"; break;
			case cb::survey::Action::Settle: name = "settle"; break;
			case cb::survey::Action::Sample: name = "sample"; break;
			case cb::survey::Action::End: name = "end"; break;
			case cb::survey::Action::None: break;
			}
			if (action == cb::survey::Action::End)
				cb::drive::request_return(g_tour.home(), g_tour.home_yaw());
			else
				cb::drive::set_hold(g_tour.hold());
			flush_tour();
		}
		if (out)
			*out = RED4ext::CName(name);
	}

	// Cyberblock_SurveyTourBounds(min: Vector4, max: Vector4, pins: Int32) -> Void: the x/y extents of `pins` map pins.
	void SurveyTourBounds(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::Vector4 min, max;
		int32_t pins = 0;
		RED4ext::GetParameter(frame, &min);
		RED4ext::GetParameter(frame, &max);
		RED4ext::GetParameter(frame, &pins);
		++frame->code;
		std::lock_guard<std::mutex> lock(g_lock);
		g_tour.bounds(min.X, min.Y, max.X, max.Y, pins);
		cb::drive::set_hold(g_tour.hold());
		flush_tour();
	}

	// Cyberblock_SurveyTourSettle(probes: array<Vector4>) -> Void: the 3 x 3 probes (z the surface, W kind as SubmitSurvey).
	void SurveyTourSettle(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::DynArray<RED4ext::Vector4> points;
		RED4ext::GetParameter(frame, &points);
		++frame->code;
		const std::vector<cb::survey::Hit> probes = to_hits(points);
		std::lock_guard<std::mutex> lock(g_lock);
		g_tour.settle(probes, now_seconds());
		cb::drive::set_hold(g_tour.hold());
		flush_tour();
	}

	// Cyberblock_SurveyTourStop() -> Vector4: the current stop's centre at the hold altitude (Cyberpunk space).
	void SurveyTourStop(IScriptable *, CStackFrame *frame, RED4ext::Vector4 *out, int64_t)
	{
		++frame->code;
		std::lock_guard<std::mutex> lock(g_lock);
		const cb::Vec3 s = g_tour.stop();
		if (out)
			*out = {float(s.x), float(s.y), float(s.z), 1.0f};
	}

	// Cyberblock_SurveyTourHome() -> Vector4: where V stood when the tour started (feet), W V's yaw then (degrees).
	void SurveyTourHome(IScriptable *, CStackFrame *frame, RED4ext::Vector4 *out, int64_t)
	{
		++frame->code;
		std::lock_guard<std::mutex> lock(g_lock);
		const cb::Vec3 h = g_tour.home();
		if (out)
			*out = {float(h.x), float(h.y), float(h.z), float(g_tour.home_yaw())};
	}

	// Cyberblock_SurveyTourReset() -> Void: the session ended; the tour is forgotten (nothing to put back).
	void SurveyTourReset(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		++frame->code;
		std::lock_guard<std::mutex> lock(g_lock);
		g_tour.reset();
		cb::drive::set_hold(std::nullopt);
		flush_tour();
	}
}

namespace cb::survey_game
{
	void register_natives(RED4ext::CRTTISystem *rtti)
	{
		using cb::natives::add;
		add(rtti, "Cyberblock_SurveyNext", &SurveyNext, "Vector4", {{"Vector4", "feet"}});
		add(rtti, "Cyberblock_SubmitSurvey", &SubmitSurvey, nullptr, {{"array:Vector4", "hits"}});
		add(rtti, "Cyberblock_SurveyTourTick", &SurveyTourTick, "CName",
			{{"Vector4", "feet"}, {"Float", "yaw"}, {"CName", "blocked"}, {"Bool", "menu"}});
		add(rtti, "Cyberblock_SurveyTourBounds", &SurveyTourBounds, nullptr, {{"Vector4", "min"}, {"Vector4", "max"}, {"Int32", "pins"}});
		add(rtti, "Cyberblock_SurveyTourSettle", &SurveyTourSettle, nullptr, {{"array:Vector4", "probes"}});
		add(rtti, "Cyberblock_SurveyTourStop", &SurveyTourStop, "Vector4");
		add(rtti, "Cyberblock_SurveyTourHome", &SurveyTourHome, "Vector4");
		add(rtti, "Cyberblock_SurveyTourReset", &SurveyTourReset, nullptr);
		cb::log::info("survey: natives registered");
	}

	void on_minecraft_message(const nlohmann::json &m)
	{
		if (!m.is_object())
			return;
		const auto t = m.find("t");
		if (t == m.end() || !t->is_string() || t->get_ref<const std::string &>() != "survey")
			return;
		std::string why;
		const std::optional<cb::survey::Request> request = cb::survey::parse_request(m, &why);
		if (!request)
		{
			if (g_invalid.fetch_add(1) < 10)
				cb::log::warn("survey: dropped an invalid message: %s", why.c_str());
			return;
		}
		std::lock_guard<std::mutex> lock(g_lock);
		if (request->op == "start")
		{
			cb::log::info("survey: start asked by Minecraft (%zu stops already done)", request->done.size());
			g_tour.request_start(request->done);
		}
		else if (request->op == "stop")
			g_tour.request_stop("stopped by /cyberblock survey stop");
		else
			g_tour.request_status();
	}

	void on_link_dropped()
	{
		std::lock_guard<std::mutex> lock(g_lock);
		g_columns.clear();
	}
}
