// The game-facing half of the Atlas survey (survey.hpp): the redscript natives (declared in
// cp/scripts/Cyberblock/SurveyNatives.reds), the `survey` requests from Minecraft and the `terrain` / `surveystate`
// messages to it, the tour's hold handed to the drive (drive.hpp set_hold / request_return), and the per-frame rays.
//
// RED4ext.SDK APIs as natives.cpp: GetParameter, DynArray<Vector4>, CName; registered through cb::natives::add.
// The per-frame rays (frame()) call game functions through RTTI like drive.cpp (RED4ext ExecuteFunction; names and
// signatures from the decompiled 2.31 scripts, orphans.swift 27612-27647, and NativeDB):
//   gameSpatialQueriesSystem.SyncRaycastByQueryFilter(start: Vector4, end: Vector4, queryFilter: QueryFilter,
//     out result: TraceResult, opt staticOnly: Bool, opt dynamicOnly: Bool) -> Bool;
//   static physicsQueryFilter.AddGroup(out filter: QueryFilter, group: CName) -> Void, groups n"Static", n"Terrain" and
//     n"Water" (as Probes.reds SurveyRay);
//   fallback when either is missing: gameSpatialQueriesSystem.SyncRaycastByCollisionGroup (drive.cpp), one ray per group.
// Their signatures are logged once ("survey: rays ready ..."). Unlike the scripts' rays these do not pass through
// Cyberblock's own collision boxes; the boxes exist only within 64 m of V (Collision.reds Stream), so the tour (V held at
// least 150 m up, 25 m above the highest roof near the stop) and the vehicle survey (columns from 66 m out) never meet one.
#include "survey_game.hpp"
#include "drive.hpp"
#include "link.hpp"
#include "log.hpp"
#include "native_reg.hpp"
#include "natives.hpp"
#include "survey.hpp"
#include "world.hpp"
#include <RED4ext/GameEngine.hpp>
#include <RED4ext/RED4ext.hpp>
#include <RED4ext/Scripting/Natives/Generated/Vector4.hpp>
#include <RED4ext/Scripting/Natives/Generated/physics/QueryFilter.hpp>
#include <RED4ext/Scripting/Natives/Generated/physics/TraceResult.hpp>
#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

using RED4ext::CStackFrame;
using RED4ext::IScriptable;

namespace
{
	using Clock = std::chrono::steady_clock;

	std::mutex g_lock;
	cb::survey::Columns g_columns;                                    // on foot (the scripts' rays), under g_lock
	cb::survey::Columns g_vehicleColumns{cb::survey::kVehicleMinRadius}; // in a vehicle (the DLL's rays), under g_lock
	cb::survey::Tour g_tour;                                          // under g_lock
	cb::Vec3 g_feet{NAN, NAN, NAN};                                   // V's feet at the last tour tick, under g_lock
	int g_session = -1;            // the link session and alignment the walking survey's memory belongs to
	int g_levelK = -1;
	std::atomic<uint64_t> g_invalid{0};
	std::atomic<bool> g_raysReady{false}; // the DLL casts its own rays (frame()); the scripts leave the vehicle survey to it
	constexpr size_t kQueueBackoff = 512;  // queued messages to Minecraft above which the rays wait a frame
	constexpr size_t kMaxFrameCells = 256 * cb::survey::kCellsPerMessage; // cells one frame may queue (a coarse ray fills up to 100)

	double now_seconds()
	{
		return std::chrono::duration<double>(Clock::now().time_since_epoch()).count();
	}

	double ms_since(Clock::time_point start)
	{
		return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
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
			g_vehicleColumns.clear();
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

	void send_terrain(const std::vector<cb::survey::Hit> &hits)
	{
		if (hits.empty() || !cb::link().linked())
			return;
		for (nlohmann::json &m : cb::survey::make_terrain(hits, cb::world::level_offset(cb::world::level_k())))
			cb::link().send(m);
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

	// ---- the DLL's own survey rays (main thread) ----------------------------------------------------------------------

	struct Rays
	{
		enum class State
		{
			Unresolved,
			Filter, // SyncRaycastByQueryFilter: one Static + Terrain + Water ray, one short Water ray on a hit
			Groups, // SyncRaycastByCollisionGroup: one ray per group
			Disabled,
		};
		State state = State::Unresolved;
		RED4ext::CClass *spatialClass = nullptr, *spatialInterface = nullptr;
		RED4ext::CBaseFunction *byFilter = nullptr, *byGroup = nullptr;
		RED4ext::physics::QueryFilter surface{}, water{};
	};
	Rays g_rays; // main thread only

	std::string signature(RED4ext::CBaseFunction *fn)
	{
		if (fn == nullptr)
			return "missing";
		std::string out;
		for (uint32_t i = 0; i < fn->params.Size(); ++i)
		{
			const RED4ext::CProperty *p = fn->params[i];
			out += (i ? ", " : "") + std::string(p->flags.isOut ? "out " : "") + (p->flags.isOptional ? "opt " : "") +
				   p->type->GetName().ToString();
		}
		return out + " -> " + (fn->returnType ? fn->returnType->type->GetName().ToString() : "Void");
	}

	/// `filter` with `group` added through the game's own QueryFilter.AddGroup.
	bool add_group(RED4ext::CBaseFunction *addGroup, RED4ext::physics::QueryFilter &filter, const char *group)
	{
		RED4ext::CName name(group);
		RED4ext::StackArgs_t args{{nullptr, &filter}, {nullptr, &name}};
		return RED4ext::ExecuteFunction(static_cast<void *>(nullptr), addGroup, nullptr, args); // a static: no context
	}

	bool resolve_rays()
	{
		if (g_rays.state != Rays::State::Unresolved)
			return g_rays.state != Rays::State::Disabled;
		RED4ext::CRTTISystem *rtti = RED4ext::CRTTISystem::Get();
		g_rays.spatialClass = rtti->GetClass("gameSpatialQueriesSystem");
		g_rays.spatialInterface = rtti->GetClass("gameISpatialQueriesSystem");
		RED4ext::CClass *filterClass = rtti->GetClass("physicsQueryFilter");
		RED4ext::CBaseFunction *addGroup = filterClass ? filterClass->GetFunction("AddGroup") : nullptr;
		if (g_rays.spatialClass)
		{
			g_rays.byFilter = g_rays.spatialClass->GetFunction("SyncRaycastByQueryFilter");
			g_rays.byGroup = g_rays.spatialClass->GetFunction("SyncRaycastByCollisionGroup");
		}
		const auto usable = [](RED4ext::CBaseFunction *fn) { return fn != nullptr && fn->params.Size() == 6 && fn->returnType != nullptr; };
		if (usable(g_rays.byFilter) && addGroup != nullptr && addGroup->params.Size() == 2 &&
			add_group(addGroup, g_rays.surface, "Static") && add_group(addGroup, g_rays.surface, "Terrain") &&
			add_group(addGroup, g_rays.surface, "Water") && add_group(addGroup, g_rays.water, "Water") &&
			(g_rays.surface.mask1 | g_rays.surface.mask2) != 0 && (g_rays.water.mask1 | g_rays.water.mask2) != 0)
			g_rays.state = Rays::State::Filter;
		else if (usable(g_rays.byGroup))
			g_rays.state = Rays::State::Groups;
		else
			g_rays.state = Rays::State::Disabled;
		if (g_rays.state == Rays::State::Disabled)
			cb::log::error("survey: rays disabled: no usable SpatialQueriesSystem raycast (SyncRaycastByQueryFilter %s, "
						   "SyncRaycastByCollisionGroup %s); the tour cannot run",
						   signature(g_rays.byFilter).c_str(), signature(g_rays.byGroup).c_str());
		else
			cb::log::info("survey: rays ready, %s (SyncRaycastByQueryFilter %s, AddGroup %s, masks %016llx/%016llx water %016llx/%016llx; "
						  "SyncRaycastByCollisionGroup %s)",
						  g_rays.state == Rays::State::Filter ? "query filter" : "one ray per collision group", signature(g_rays.byFilter).c_str(),
						  signature(addGroup).c_str(), static_cast<unsigned long long>(g_rays.surface.mask1),
						  static_cast<unsigned long long>(g_rays.surface.mask2), static_cast<unsigned long long>(g_rays.water.mask1),
						  static_cast<unsigned long long>(g_rays.water.mask2), signature(g_rays.byGroup).c_str());
		g_raysReady = g_rays.state != Rays::State::Disabled;
		return g_raysReady;
	}

	IScriptable *spatial_system()
	{
		const RED4ext::CGameEngine *engine = RED4ext::CGameEngine::Get();
		if (engine == nullptr || engine->framework == nullptr || engine->framework->gameInstance == nullptr)
			return nullptr;
		IScriptable *system = g_rays.spatialClass ? engine->framework->gameInstance->GetSystem(g_rays.spatialClass) : nullptr;
		if (system == nullptr && g_rays.spatialInterface != nullptr)
			system = engine->framework->gameInstance->GetSystem(g_rays.spatialInterface);
		return system;
	}

	/// z of the first surface on the vertical segment top -> bottom at (x, y): `filter` for Filter, else `group`.
	std::optional<double> trace(IScriptable *spatial, double x, double y, double top, double bottom, const RED4ext::physics::QueryFilter *filter,
								const char *group)
	{
		RED4ext::Vector4 start{float(x), float(y), float(top), 1.0f};
		RED4ext::Vector4 end{float(x), float(y), float(bottom), 1.0f};
		RED4ext::physics::TraceResult result{};
		bool staticOnly = false, dynamicOnly = false, hit = false;
		RED4ext::physics::QueryFilter copy = filter ? *filter : RED4ext::physics::QueryFilter{};
		RED4ext::CName groupName(group ? group : "Static");
		RED4ext::StackArgs_t args{{nullptr, &start}, {nullptr, &end}, {nullptr, filter ? static_cast<void *>(&copy) : static_cast<void *>(&groupName)},
								  {nullptr, &result}, {nullptr, &staticOnly}, {nullptr, &dynamicOnly}};
		if (!RED4ext::ExecuteFunction(spatial, filter ? g_rays.byFilter : g_rays.byGroup, &hit, args) || !hit || !std::isfinite(result.position.Z))
			return std::nullopt;
		return double(result.position.Z);
	}

	/// One survey column, as Probes.reds SurveyRay: the highest Night City surface (Static, Terrain or Water) between
	/// kRayTop and kRayBottom; kind 1 ground or building, 2 water, 0 nothing.
	cb::survey::Hit survey_ray(IScriptable *spatial, double x, double y)
	{
		using namespace cb::survey;
		if (g_rays.state == Rays::State::Filter)
		{
			const std::optional<double> z = trace(spatial, x, y, kRayTop, kRayBottom, &g_rays.surface, nullptr);
			if (!z)
				return {x, y, 0.0, 0};
			const bool water = trace(spatial, x, y, *z + 0.5, *z - 0.5, &g_rays.water, nullptr).has_value();
			return {x, y, *z, water ? 2 : 1};
		}
		std::optional<double> best;
		for (const char *group : {"Static", "Terrain"})
			if (const auto z = trace(spatial, x, y, kRayTop, kRayBottom, nullptr, group); z && (!best || *z > *best))
				best = z;
		const std::optional<double> water = trace(spatial, x, y, kRayTop, kRayBottom, nullptr, "Water");
		if (water && (!best || *water >= *best - 0.01))
			return {x, y, *water, 2};
		return best ? Hit{x, y, *best, 1} : Hit{x, y, 0.0, 0};
	}

	// ---- natives ------------------------------------------------------------------------------------------------------

	// Cyberblock_SurveyNext(feet: Vector4) -> Vector4: the next column (x, y) to cast a survey ray at, W 1; W 0 when there
	// is none this tick. The walking survey around V while Cyberblock is active and linked, on foot only once the DLL casts
	// its own rays (frame(): the tour, and the survey in a vehicle).
	void SurveyNext(IScriptable *, CStackFrame *frame, RED4ext::Vector4 *out, int64_t)
	{
		RED4ext::Vector4 feet;
		RED4ext::GetParameter(frame, &feet);
		++frame->code;
		std::optional<std::array<double, 2>> column;
		{
			std::lock_guard<std::mutex> lock(g_lock);
			if (!g_tour.running() && cb::natives::active && cb::link().linked() && !(cb::natives::inVehicle && g_raysReady))
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
		{
			std::lock_guard<std::mutex> lock(g_lock);
			const double now = now_seconds();
			for (const cb::survey::Hit &h : hits)
				if (h.kind != 1 && h.kind != 2)
					g_columns.missed(h.x, h.y, now);
		}
		send_terrain(hits);
	}

	// Cyberblock_SurveyTourTick(feet: Vector4, yaw: Float, blocked: CName, menu: Bool) -> CName: the tour's step this tick
	// (n"" none, n"bounds", n"begin", n"end"); hands the hold to the drive.
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
			g_feet = in.feet;
			const cb::survey::Action action = g_tour.tick(in);
			switch (action)
			{
			case cb::survey::Action::Bounds: name = "bounds"; break;
			case cb::survey::Action::Begin: name = "begin"; break;
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

	// Cyberblock_SurveyTourArea(pins: array<Vector4>) -> Void: x, y every fast-travel point and map pin, W its kind (0 a
	// fast-travel point outside the Badlands, 1 one in them, 2 any other map pin).
	void SurveyTourArea(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::DynArray<RED4ext::Vector4> points;
		RED4ext::GetParameter(frame, &points);
		++frame->code;
		std::vector<cb::survey::Pin> pins;
		pins.reserve(points.Size());
		for (const RED4ext::Vector4 &p : points)
			pins.push_back({p.X, p.Y, int(std::lround(p.W))});
		std::lock_guard<std::mutex> lock(g_lock);
		g_tour.area(pins);
		cb::drive::set_hold(g_tour.hold());
		flush_tour();
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
		g_feet = {NAN, NAN, NAN};
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
		add(rtti, "Cyberblock_SurveyTourArea", &SurveyTourArea, nullptr, {{"array:Vector4", "pins"}});
		add(rtti, "Cyberblock_SurveyTourHome", &SurveyTourHome, "Vector4");
		add(rtti, "Cyberblock_SurveyTourReset", &SurveyTourReset, nullptr);
		cb::log::info("survey: natives registered");
	}

	void frame()
	{
		static double lastFrame = -1.0, lastWork = 0.0;
		const Clock::time_point start = Clock::now();
		const double now = now_seconds();
		const double interval = lastFrame < 0.0 ? 1000.0 : (now - lastFrame) * 1000.0;
		lastFrame = now;
		bool touring = false;
		cb::Vec3 feet{};
		{
			std::lock_guard<std::mutex> lock(g_lock);
			touring = g_tour.running();
			feet = g_feet;
		}
		const bool menu = cb::natives::menuOpen;
		// the tour casts only while the drive holds V at the stop (drive.cpp survey_frame's gate)
		const bool tour = touring && cb::natives::active && !cb::natives::inVehicle && !cb::natives::held && cb::natives::flightAllowed && !menu;
		const bool vehicle = !touring && cb::natives::active && cb::natives::inVehicle && !menu && cb::link().linked() && std::isfinite(feet.x);
		const double previousWork = std::exchange(lastWork, 0.0);
		if ((!tour && !vehicle) || !resolve_rays())
			return;
		IScriptable *spatial = spatial_system();
		if (spatial == nullptr || cb::link().send_stats().queued > kQueueBackoff)
			return; // no game yet, or Minecraft has not taken the last cells yet
		const double budget = tour ? cb::survey::frame_budget_ms(interval, previousWork) : cb::survey::kVehicleBudgetMs;
		std::vector<cb::survey::Hit> cells;
		{
			std::lock_guard<std::mutex> lock(g_lock);
			if (tour)
			{
				int batches = 0; // probe batches this frame: the settle probes once, the reach rings once
				while (ms_since(start) < budget && g_tour.pending_cells() < kMaxFrameCells)
				{
					const cb::survey::Need need = g_tour.need(now);
					if (need == cb::survey::Need::Probes && batches++ < 2)
					{
						std::vector<cb::survey::Hit> hits;
						for (const auto &[x, y] : g_tour.probes())
							hits.push_back(survey_ray(spatial, x, y));
						g_tour.probed(hits, now);
					}
					else if (need == cb::survey::Need::Columns)
					{
						if (const auto column = g_tour.next_column(now))
							g_tour.column(survey_ray(spatial, (*column)[0], (*column)[1]));
					}
					else
						break;
				}
				cells = g_tour.take_cells();
				g_tour.spent(ms_since(start));
				cb::drive::set_hold(g_tour.hold());
				flush_tour();
			}
			else
			{
				sync_memory();
				while (ms_since(start) < budget)
				{
					const auto column = g_vehicleColumns.next(feet, now);
					if (!column)
						break;
					const cb::survey::Hit hit = survey_ray(spatial, (*column)[0], (*column)[1]);
					if (hit.kind == 1 || hit.kind == 2)
						cells.push_back(hit);
					else
						g_vehicleColumns.missed(hit.x, hit.y, now);
				}
			}
		}
		lastWork = ms_since(start);
		send_terrain(cells);
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
			cb::log::info("survey: start asked by Minecraft (%zu squares already done)", request->done.size());
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
		g_vehicleColumns.clear();
	}
}
