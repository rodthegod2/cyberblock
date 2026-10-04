// The per-frame flight drive (drive.hpp). Game functions, called through RTTI like camera.cpp (RED4ext ExecuteFunction;
// names and signatures from NativeDB and the decompiled 2.31 scripts):
//   gameTeleportationFacility.Teleport(objectToTeleport: handle:gameObject, position: Vector4, orientation: EulerAngles)
//     (orphans.swift 31935; NativeDB gameTeleportationFacility), the system found as gameTeleportationFacility or
//     gameITeleportationFacility;
//   gameSpatialQueriesSystem.SyncRaycastByCollisionGroup(start: Vector4, end: Vector4, opt collisionGroup: CName,
//     out result: TraceResult, opt staticOnly: Bool, opt dynamicOnly: Bool) -> Bool (orphans.swift 27647), groups
//     n"Static" and n"Terrain" (Probes.reds uses both);
//   entEntity.GetWorldPosition() -> Vector4, GetWorldForward() -> Vector4 (as camera.cpp);
//   static Vector4.ToRotation(dir: Vector4) -> EulerAngles (2.31 script API: vanilla teleports that keep the facing pass
//     ToRotation(GetWorldForward())); read once at a flight's start, then the drive owns the yaw (drive.hpp, task D3);
//   GetPlayer;GameInstance (as camera.cpp).
// Every function's parameter types are logged once ("drive: ready ..."); if one is missing the drive logs
// "drive: disabled: ... missing" and never moves V, so cam.fly stays false and Minecraft calls every flight off.
//
// The gate (when V may be moved) is Cyberblock active, not in a vehicle, not held, Cyberblock_SetFlightAllowed and V
// found. A dropped link is not in it: on_link_dropped resets the flight to a landing at the last place Steve was seen
// (Flight::reset), and that lands V on the ground instead of leaving it in mid-air.
//
// A collision ends the flight (in-game 2026-10-03: a glide into a building left V held 0.3 m from the wall every frame
// while Steve glided on in Minecraft, mouse and keys dead, until a save was loaded): the first frame a clip ray meets
// Night City, V is put on the ground below the point it was stopped at, the follower stops (cam.fly false, so
// Minecraft calls its glide off and puts Steve back at V) and does not take the same glide up again (follow.hpp).
#include "drive.hpp"
#include "follow.hpp"
#include "log.hpp"
#include "natives.hpp"
#include "snapshots.hpp"
#include <RED4ext/GameEngine.hpp>
#include <RED4ext/RED4ext.hpp>
#include <RED4ext/Scripting/Natives/Generated/EulerAngles.hpp>
#include <RED4ext/Scripting/Natives/Generated/Vector4.hpp>
#include <RED4ext/Scripting/Natives/Generated/physics/TraceResult.hpp>
#include <RED4ext/Scripting/Natives/ScriptGameInstance.hpp>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <optional>
#include <string>
#include <utility>

namespace
{
	using cb::Vec3;
	using cb::follow::Act;
	using cb::follow::Follower;

	constexpr double kLandProbeUp = 1.5;        // m above a landing spot the ground ray starts
	constexpr double kGroundProbeDown = 1000.0; // m below the start point the ground rays end
	constexpr const char *kGroups[] = {"Static", "Terrain"};

	/// The resolved game functions; touched on the main thread only.
	struct Api
	{
		enum class State
		{
			Unresolved,
			Ready,
			Disabled,
		};
		State state = State::Unresolved;
		RED4ext::CClass *teleportClass = nullptr, *teleportInterface = nullptr;
		RED4ext::CClass *spatialClass = nullptr, *spatialInterface = nullptr;
		RED4ext::CBaseFunction *teleport = nullptr, *raycast = nullptr, *position = nullptr, *forward = nullptr,
							   *toRotation = nullptr;
		bool weakTarget = false; // Teleport's first parameter is a weak handle
	};
	Api g_api;

	Follower g_follower;          // main thread only
	bool g_clipped = false;       // this flight stopped V short of Night City at least once
	bool g_teleportWarned = false; // this flight logged a failed Teleport

	std::atomic<bool> g_driving{false};
	std::atomic<float> g_sinceEnd{999.0f};
	bool g_teleportedThisFrame = false; // main thread: tick teleported V this frame, from g_feetBefore
	Vec3 g_feetBefore{};

	/// The survey tour's hold and return (set_hold, request_return), under g_holdLock.
	struct Return
	{
		Vec3 feet;
		double yaw;
		double at; // seconds (steady clock) when it was asked for
	};
	std::mutex g_holdLock;
	std::optional<Vec3> g_hold;
	std::optional<Return> g_return;
	bool g_holding = false;   // main thread: the hold teleported V on an earlier frame
	double g_holdYaw = 0.0;   // main thread: V's facing at the hold's first frame
	constexpr double kReturnSeconds = 2.0;

	double seconds(cb::snap::Clock::time_point t)
	{
		return std::chrono::duration<double>(t.time_since_epoch()).count();
	}

	RED4ext::Vector4 to_vector4(const Vec3 &v)
	{
		return {float(v.x), float(v.y), float(v.z), 1.0f};
	}

	Vec3 to_vec3(const RED4ext::Vector4 &v)
	{
		return {v.X, v.Y, v.Z};
	}

	/// "handle:gameObject, Vector4, EulerAngles -> Void".
	std::string signature(RED4ext::CBaseFunction *fn)
	{
		std::string out;
		for (uint32_t i = 0; i < fn->params.Size(); ++i)
		{
			const RED4ext::CProperty *p = fn->params[i];
			out += (i ? ", " : "") + std::string(p->flags.isOut ? "out " : "") + (p->flags.isOptional ? "opt " : "") +
				   p->type->GetName().ToString();
		}
		out += " -> ";
		out += fn->returnType ? fn->returnType->type->GetName().ToString() : "Void";
		return out;
	}

	/// Resolves the game functions once; false (and logs once why) when the drive cannot work.
	bool resolve()
	{
		if (g_api.state != Api::State::Unresolved)
			return g_api.state == Api::State::Ready;
		RED4ext::CRTTISystem *rtti = RED4ext::CRTTISystem::Get();
		const auto function = [rtti](const char *cls, const char *name) -> RED4ext::CBaseFunction *
		{
			RED4ext::CClass *type = rtti->GetClass(cls);
			return type != nullptr ? type->GetFunction(name) : nullptr;
		};
		g_api.teleportClass = rtti->GetClass("gameTeleportationFacility");
		g_api.teleportInterface = rtti->GetClass("gameITeleportationFacility");
		g_api.spatialClass = rtti->GetClass("gameSpatialQueriesSystem");
		g_api.spatialInterface = rtti->GetClass("gameISpatialQueriesSystem");
		g_api.teleport = function("gameTeleportationFacility", "Teleport");
		g_api.raycast = function("gameSpatialQueriesSystem", "SyncRaycastByCollisionGroup");
		g_api.position = function("entEntity", "GetWorldPosition");
		g_api.forward = function("entEntity", "GetWorldForward");
		g_api.toRotation = function("Vector4", "ToRotation");

		std::string missing;
		const auto need = [&missing](bool ok, const char *what)
		{
			if (!ok)
				missing += (missing.empty() ? "" : ", ") + std::string(what);
		};
		need(g_api.teleportClass != nullptr || g_api.teleportInterface != nullptr, "the TeleportationFacility class");
		need(g_api.spatialClass != nullptr || g_api.spatialInterface != nullptr, "the SpatialQueriesSystem class");
		need(g_api.teleport != nullptr && g_api.teleport->params.Size() == 3, "TeleportationFacility.Teleport(3)");
		need(g_api.raycast != nullptr && g_api.raycast->params.Size() == 6 && g_api.raycast->returnType != nullptr,
			 "SpatialQueriesSystem.SyncRaycastByCollisionGroup(6) -> Bool");
		need(g_api.position != nullptr && g_api.position->returnType != nullptr, "Entity.GetWorldPosition");
		need(g_api.forward != nullptr && g_api.forward->returnType != nullptr, "Entity.GetWorldForward");
		need(g_api.toRotation != nullptr && g_api.toRotation->params.Size() == 1 && g_api.toRotation->returnType != nullptr,
			 "Vector4.ToRotation(1) -> EulerAngles");
		if (!missing.empty())
		{
			cb::log::error("drive: disabled: %s missing", missing.c_str());
			g_api.state = Api::State::Disabled;
			return false;
		}
		const RED4ext::rtti::ERTTIType target = g_api.teleport->params[0]->type->GetType();
		g_api.weakTarget = target == RED4ext::rtti::ERTTIType::WeakHandle;
		if (target != RED4ext::rtti::ERTTIType::Handle && !g_api.weakTarget)
		{
			cb::log::error("drive: disabled: Teleport's target is %s, not a handle", signature(g_api.teleport).c_str());
			g_api.state = Api::State::Disabled;
			return false;
		}
		cb::log::info("drive: ready (Teleport %s, raycast %s; GetWorldPosition %s, GetWorldForward %s, ToRotation %s)",
					  signature(g_api.teleport).c_str(), signature(g_api.raycast).c_str(),
					  signature(g_api.position).c_str(), signature(g_api.forward).c_str(),
					  signature(g_api.toRotation).c_str());
		g_api.state = Api::State::Ready;
		return true;
	}

	RED4ext::IScriptable *game_system(RED4ext::CClass *cls, RED4ext::CClass *fallback)
	{
		const RED4ext::CGameEngine *engine = RED4ext::CGameEngine::Get();
		if (engine == nullptr || engine->framework == nullptr || engine->framework->gameInstance == nullptr)
			return nullptr;
		RED4ext::IScriptable *system = cls != nullptr ? engine->framework->gameInstance->GetSystem(cls) : nullptr;
		if (system == nullptr && fallback != nullptr)
			system = engine->framework->gameInstance->GetSystem(fallback);
		return system;
	}

	/// What a frame needs from the game: V and the two systems.
	struct Game
	{
		RED4ext::Handle<RED4ext::IScriptable> player;
		RED4ext::IScriptable *teleport = nullptr;
		RED4ext::IScriptable *spatial = nullptr;
		Vec3 feet{};
	};

	/// V, its feet and the systems; false while any is missing (loading, no session).
	bool read_game(Game &game)
	{
		game.teleport = game_system(g_api.teleportClass, g_api.teleportInterface);
		game.spatial = game_system(g_api.spatialClass, g_api.spatialInterface);
		if (game.teleport == nullptr || game.spatial == nullptr)
			return false;
		RED4ext::ScriptGameInstance instance;
		if (!RED4ext::ExecuteGlobalFunction("GetPlayer;GameInstance", &game.player, instance) || !game.player)
			return false;
		RED4ext::Vector4 feet{};
		RED4ext::StackArgs_t none;
		if (!RED4ext::ExecuteFunction(game.player.GetPtr(), g_api.position, &feet, none))
			return false;
		game.feet = to_vec3(feet);
		return std::isfinite(game.feet.x) && std::isfinite(game.feet.y) && std::isfinite(game.feet.z);
	}

	/// The first Night City surface of `group` on the segment from -> to.
	std::optional<Vec3> raycast(RED4ext::IScriptable *spatial, const Vec3 &from, const Vec3 &to, const char *group)
	{
		RED4ext::Vector4 start = to_vector4(from);
		RED4ext::Vector4 end = to_vector4(to);
		RED4ext::CName groupName(group);
		RED4ext::physics::TraceResult result{};
		bool staticOnly = false;
		bool dynamicOnly = false;
		bool hit = false;
		RED4ext::StackArgs_t args{{nullptr, &start},  {nullptr, &end},        {nullptr, &groupName},
								  {nullptr, &result}, {nullptr, &staticOnly}, {nullptr, &dynamicOnly}};
		if (!RED4ext::ExecuteFunction(spatial, g_api.raycast, &hit, args) || !hit)
			return std::nullopt;
		const Vec3 at{result.position.X, result.position.Y, result.position.Z};
		if (!std::isfinite(at.x) || !std::isfinite(at.y) || !std::isfinite(at.z))
			return std::nullopt;
		return at;
	}

	double distance(const Vec3 &a, const Vec3 &b)
	{
		return std::hypot(a.x - b.x, a.y - b.y, a.z - b.z);
	}

	/// V's feet moved from -> to, stopped kClipMargin short of the nearest Night City Static or Terrain surface that any
	/// of the clip rays (shins, middle and head: Follower::kClipLifts) crosses; `clipped` says whether anything was in the
	/// way. One ray at the middle let a kerb or a low wall through at the shins, and V's head through a ceiling.
	Vec3 clip(const Game &game, const Vec3 &from, const Vec3 &to, bool &clipped)
	{
		clipped = false;
		if (distance(from, to) < 1e-4)
			return to;
		double nearest = INFINITY;
		for (const double lift : Follower::kClipLifts)
		{
			const Vec3 a{from.x, from.y, from.z + lift};
			const Vec3 b{to.x, to.y, to.z + lift};
			for (const char *group : kGroups)
				if (const auto hit = raycast(game.spatial, a, b, group))
					nearest = std::min(nearest, distance(a, *hit));
		}
		if (!std::isfinite(nearest))
			return to;
		clipped = true;
		return cb::follow::stop_short(from, to, nearest, Follower::kClipMargin);
	}

	/// The highest Night City ground (Static or Terrain) below `at`, from `up` metres above it.
	std::optional<double> ground_z(const Game &game, const Vec3 &at, double up)
	{
		const Vec3 top{at.x, at.y, at.z + up};
		const Vec3 bottom{at.x, at.y, at.z - kGroundProbeDown};
		std::optional<double> best;
		for (const char *group : kGroups)
			if (const auto hit = raycast(game.spatial, top, bottom, group))
				if (!best || hit->z > *best)
					best = hit->z;
		return best;
	}

	/// V's facing now as EulerAngles (ToRotation of GetWorldForward, as vanilla teleports that keep the facing do).
	std::optional<RED4ext::EulerAngles> facing(Game &game)
	{
		RED4ext::Vector4 forward{};
		RED4ext::StackArgs_t none;
		if (!RED4ext::ExecuteFunction(game.player.GetPtr(), g_api.forward, &forward, none))
			return std::nullopt;
		RED4ext::EulerAngles rotation{};
		RED4ext::StackArgs_t direction{{nullptr, &forward}};
		if (!RED4ext::ExecuteFunction(static_cast<void *>(nullptr), g_api.toRotation, &rotation, direction)) // a static: no context
			return std::nullopt;
		if (!std::isfinite(rotation.Yaw))
			return std::nullopt;
		return rotation;
	}

	/// Teleports V to `feet` facing `yaw` (Cyberpunk degrees): the drive owns V's yaw while it flies (re-test 3 task D3), so
	/// the mouse-X turn the game never saw (input.cpp strips it) is applied here instead of being undone every frame.
	bool teleport(Game &game, const Vec3 &feet, double yaw)
	{
		g_teleportedThisFrame = true;
		g_feetBefore = game.feet;
		RED4ext::EulerAngles rotation{0.0f, 0.0f, float(yaw)};
		RED4ext::Vector4 position = to_vector4(feet);
		RED4ext::WeakHandle<RED4ext::IScriptable> weak(game.player);
		RED4ext::StackArgs_t args;
		if (g_api.weakTarget)
			args.emplace_back(nullptr, &weak);
		else
			args.emplace_back(nullptr, &game.player);
		args.emplace_back(nullptr, &position);
		args.emplace_back(nullptr, &rotation);
		if (RED4ext::ExecuteFunction(game.teleport, g_api.teleport, nullptr, args))
			return true;
		if (!std::exchange(g_teleportWarned, true))
			cb::log::warn("drive: TeleportationFacility.Teleport failed (logged once per flight)");
		return false;
	}

	/// The owned yaw of this flight (re-test 3 task D3). Main thread only.
	struct Yaw
	{
		double now = 0.0;   // Cyberpunk degrees: what every teleport of this flight uses
		double start = 0.0; // at the start of the flight
		int64_t counts = 0; // raw mouse X counts turned in this flight
		double rate = 0.0;  // degrees per count, the last one used
		double pitchMin = INFINITY, pitchMax = -INFINITY; // the camera's Minecraft pitch while driving (camera.cpp)
	};
	Yaw g_yaw;

	void summary()
	{
		const cb::snap::FlightStats samples = cb::snap::flight().stats();
		const cb::follow::Summary frames = g_follower.summary();
		const bool pitched = g_yaw.pitchMin <= g_yaw.pitchMax;
		cb::log::info("flight: %d frames, %d samples (median spacing %.1f ms, max gap %.1f ms), max step %.2f m, clipped %s, "
					  "yaw start %.1f end %.1f, mouse x %lld counts at %.4f deg/count, pitch %.1f..%.1f",
					  frames.frames, samples.samples, samples.medianGapMs, samples.maxGapMs, frames.maxStep,
					  g_clipped ? "yes" : "no", g_yaw.start, g_yaw.now, static_cast<long long>(g_yaw.counts), g_yaw.rate,
					  pitched ? g_yaw.pitchMin : 0.0, pitched ? g_yaw.pitchMax : 0.0);
	}

	void mirror(double now)
	{
		g_driving = g_follower.driving();
		g_sinceEnd = float(std::min(g_follower.seconds_since_end(now), 999.0));
	}
}

namespace
{
	/// The survey tour's part of a frame: the return teleport, then the hold. True while a hold is set (whether or not
	/// the gate let it move V this frame).
	bool survey_frame(double now)
	{
		std::optional<Vec3> hold;
		std::optional<Return> back;
		{
			std::lock_guard<std::mutex> lock(g_holdLock);
			hold = g_hold;
			if (g_return && now - g_return->at > kReturnSeconds)
				g_return.reset(); // never a stale return: the scripts already put V back
			back = g_return;
		}
		const bool gate = cb::natives::active && !cb::natives::inVehicle && !cb::natives::held && cb::natives::flightAllowed &&
						  !cb::natives::menuOpen;
		if (!hold)
			g_holding = false;
		if ((!hold && !back) || !gate || !resolve())
			return hold.has_value();
		Game game;
		if (!read_game(game))
			return hold.has_value();
		if (back && !hold)
		{
			{
				std::lock_guard<std::mutex> lock(g_holdLock);
				g_return.reset();
			}
			if (teleport(game, back->feet, back->yaw))
				cb::log::info("survey: V put back at (%.1f, %.1f, %.1f)", back->feet.x, back->feet.y, back->feet.z);
			return false;
		}
		if (!g_holding)
		{
			const std::optional<RED4ext::EulerAngles> start = facing(game);
			g_holdYaw = start ? double(start->Yaw) : 0.0;
			g_holding = true;
		}
		teleport(game, *hold, g_holdYaw);
		return true;
	}
}

namespace cb::drive
{
	void set_hold(std::optional<Vec3> cpFeet)
	{
		std::lock_guard<std::mutex> lock(g_holdLock);
		g_hold = cpFeet;
	}

	void request_return(Vec3 cpFeet, double yaw)
	{
		const double now = seconds(snap::Clock::now());
		std::lock_guard<std::mutex> lock(g_holdLock);
		g_hold.reset();
		g_return = Return{cpFeet, yaw, now};
	}

	void tick(int64_t mouseX, double degreesPerCount)
	{
		g_teleportedThisFrame = false;
		if (g_api.state == Api::State::Disabled)
			return;
		const snap::Clock::time_point now = snap::Clock::now();
		if (survey_frame(seconds(now)))
			return; // the survey tour holds V this frame: no flight runs meanwhile
		snap::Flight &flight = snap::flight();
		follow::In in;
		in.now = seconds(now);
		in.menu = natives::menuOpen;
		in.active = flight.active(now);
		in.timedOut = flight.timed_out(now);
		in.launch = flight.launch(now);
		in.target = flight.render_pos(now);
		in.gate = natives::active && !natives::inVehicle && !natives::held && natives::flightAllowed;

		// the game is asked only when V may be moved and there is something to do; behind a menu nothing moves
		Game game;
		if (in.gate && !in.menu && (g_follower.driving() || in.active))
		{
			if (!resolve())
				return; // disabled: never drives, so cam.fly stays false and Minecraft calls the flight off
			in.gate = read_game(game);
			in.feet = game.feet;
		}

		const bool wasDriving = g_follower.driving();
		const follow::Out out = g_follower.step(in);
		if (!wasDriving && g_follower.driving())
		{
			g_clipped = false;
			g_teleportWarned = false;
			// the owned yaw starts from V's facing now; this frame's counts belong to the time before the flight
			const std::optional<RED4ext::EulerAngles> start = facing(game);
			g_yaw = {};
			g_yaw.now = g_yaw.start = start ? double(start->Yaw) : 0.0;
			g_yaw.rate = degreesPerCount;
			if (!start)
				log::warn("drive: could not read V's facing at the start; the glide starts facing yaw 0");
		}
		else if (wasDriving && !in.menu)
		{
			// the mouse-X turn the game did not see (input.cpp strips it while driving in first person)
			g_yaw.now = follow::turned_yaw(g_yaw.now, mouseX, degreesPerCount);
			g_yaw.counts += mouseX;
			g_yaw.rate = degreesPerCount;
		}
		if (!out.log.empty())
			log::info("%s", out.log.c_str());

		switch (out.act)
		{
		case Act::None:
			break;
		case Act::Move:
		{
			bool clipped = false;
			const Vec3 to = clip(game, g_follower.last(), out.to, clipped);
			if (clipped)
			{
				// a collision ends the flight: V goes onto the ground below the point it was stopped at, and the
				// follower lets the glide go (Minecraft calls it off when it sees cam.fly false)
				g_clipped = true;
				const follow::Out ended = g_follower.blocked(to, in.now);
				log::info("%s", ended.log.c_str());
				const std::optional<double> ground = ground_z(game, to, Follower::kBodyLift);
				teleport(game, {to.x, to.y, ground ? *ground : to.z}, g_yaw.now);
				natives::flightFollowing = false;
				summary();
				break;
			}
			if (teleport(game, to, g_yaw.now))
			{
				g_follower.applied(to);
				natives::flightFollowing = true;
			}
			break;
		}
		case Act::Land:
		{
			// a blocked path ends on the ground below where V was stopped; else at the spot, snapped to the ground
			bool blocked = false;
			const Vec3 reached = clip(game, g_follower.last(), out.to, blocked);
			Vec3 feet;
			if (blocked)
			{
				g_clipped = true;
				const std::optional<double> ground = ground_z(game, reached, Follower::kBodyLift);
				feet = {reached.x, reached.y, ground ? *ground : reached.z};
			}
			else
				feet = follow::snap_landing(out.to, ground_z(game, out.to, kLandProbeUp));
			teleport(game, feet, g_yaw.now);
			natives::flightFollowing = false;
			summary();
			break;
		}
		case Act::Ground:
		{
			const std::optional<double> ground = ground_z(game, out.to, Follower::kBodyLift); // below where V was held
			teleport(game, {out.to.x, out.to.y, ground ? *ground : out.to.z}, g_yaw.now);
			natives::flightFollowing = false;
			summary();
			break;
		}
		case Act::Stop:
			natives::flightFollowing = false;
			summary();
			break;
		}
		// never "following" while the follower is idle: cam.fly would keep Minecraft's glide alive with no V under it
		if (!g_follower.driving() && natives::flightFollowing)
			natives::flightFollowing = false;
		mirror(in.now);
	}

	bool driving()
	{
		return g_driving;
	}

	void note_pitch(double mcPitch)
	{
		if (!g_follower.driving() || !std::isfinite(mcPitch))
			return;
		g_yaw.pitchMin = std::min(g_yaw.pitchMin, mcPitch);
		g_yaw.pitchMax = std::max(g_yaw.pitchMax, mcPitch);
	}

	float seconds_since_end()
	{
		return g_sinceEnd;
	}

	bool feet_before_teleport(Vec3 &feet)
	{
		if (!g_teleportedThisFrame)
			return false;
		feet = g_feetBefore;
		return true;
	}
}
