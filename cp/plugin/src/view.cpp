// The on-foot third-person view, game side (view.hpp). Game functions, called through RTTI like camera.cpp and drive.cpp
// (RED4ext ExecuteFunction); names and signatures from the 2.31 script API (NativeDB, redscript):
//   PlayerPuppet.GetFPPCameraComponent() -> ref<FPPCameraComponent> (cyberpunk/player/player.swift);
//   entIPlacedComponent.GetLocalPosition() -> Vector4, SetLocalPosition(pos: Vector4) (IPlacedComponent, which
//     FPPCameraComponent extends through CameraComponent and entBaseCameraComponent);
//   entEntity.GetWorldOrientation() -> Quaternion (core/entity/entity.swift);
//   gameCameraSystem.GetActiveCameraWorldTransform(out transform: Transform) -> Bool (as camera.cpp);
//   gameSpatialQueriesSystem.SyncRaycastByCollisionGroup (as drive.cpp), groups n"Static" and n"Terrain": Minecraft's
//     collision boxes are real Static entities in Night City, so blocks stop the camera too.
// The offset is in V's local axes, assuming the component's parent frame is V's (logged on the first applied frame as
// "view: applied, eye moved D m (expected E m)", which checks it). Every function is resolved once; if one is missing,
// "view: disabled: ... missing" is logged and the view never applies (cam.fp stays true).
#include "view.hpp"
#include "link.hpp"
#include "log.hpp"
#include "natives.hpp"
#include "world.hpp"
#include <RED4ext/GameEngine.hpp>
#include <RED4ext/RED4ext.hpp>
#include <RED4ext/Scripting/Natives/Generated/Quaternion.hpp>
#include <RED4ext/Scripting/Natives/Generated/Vector4.hpp>
#include <RED4ext/Scripting/Natives/Generated/physics/TraceResult.hpp>
#include <RED4ext/Scripting/Natives/ScriptGameInstance.hpp>
#include <RED4ext/Scripting/Natives/Transform.hpp>
#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <optional>
#include <string>

namespace
{
	using cb::Vec3;

	constexpr const char *kGroups[] = {"Static", "Terrain"};

	struct Api
	{
		enum class State
		{
			Unresolved,
			Ready,
			Disabled,
		};
		State state = State::Unresolved;
		RED4ext::CBaseFunction *fpp = nullptr, *getLocal = nullptr, *setLocal = nullptr, *orientation = nullptr,
							   *cameraTransform = nullptr, *raycast = nullptr;
		RED4ext::CClass *cameraClass = nullptr, *spatialClass = nullptr, *spatialInterface = nullptr;
	};
	Api g_api;

	// main thread only
	bool g_applied = false;
	RED4ext::WeakHandle<RED4ext::IScriptable> g_component; // the camera component the offset sits on
	RED4ext::Vector4 g_base{};                               // its own local position, restored when the view ends
	Vec3 g_offset{};                                         // the local offset applied last frame
	cb::view::Boom g_boom;
	const cb::view::Orbit g_orbit;
	std::chrono::steady_clock::time_point g_lastFrame{};
	std::optional<Vec3> g_before; // the camera's world position the frame before the view applied (for the check line)
	bool g_measured = false;

	std::atomic<bool> g_allowed{false};
	std::atomic<bool> g_appliedFlag{false};
	std::mutex g_eyeLock;
	Vec3 g_eye{};

	void set_eye(const Vec3 &eye)
	{
		std::lock_guard<std::mutex> lock(g_eyeLock);
		g_eye = eye;
	}

	double distance(const Vec3 &a, const Vec3 &b)
	{
		return std::hypot(a.x - b.x, a.y - b.y, a.z - b.z);
	}

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
		g_api.fpp = function("PlayerPuppet", "GetFPPCameraComponent");
		g_api.getLocal = function("entIPlacedComponent", "GetLocalPosition");
		g_api.setLocal = function("entIPlacedComponent", "SetLocalPosition");
		g_api.orientation = function("entEntity", "GetWorldOrientation");
		g_api.cameraTransform = function("gameCameraSystem", "GetActiveCameraWorldTransform");
		g_api.raycast = function("gameSpatialQueriesSystem", "SyncRaycastByCollisionGroup");
		g_api.cameraClass = rtti->GetClass("gameCameraSystem");
		g_api.spatialClass = rtti->GetClass("gameSpatialQueriesSystem");
		g_api.spatialInterface = rtti->GetClass("gameISpatialQueriesSystem");

		std::string missing;
		const auto need = [&missing](bool ok, const char *what)
		{
			if (!ok)
				missing += (missing.empty() ? "" : ", ") + std::string(what);
		};
		need(g_api.fpp != nullptr && g_api.fpp->returnType != nullptr, "PlayerPuppet.GetFPPCameraComponent");
		need(g_api.getLocal != nullptr && g_api.getLocal->returnType != nullptr, "IPlacedComponent.GetLocalPosition");
		need(g_api.setLocal != nullptr && g_api.setLocal->params.Size() == 1, "IPlacedComponent.SetLocalPosition(1)");
		need(g_api.orientation != nullptr && g_api.orientation->returnType != nullptr, "Entity.GetWorldOrientation");
		need(g_api.cameraClass != nullptr && g_api.cameraTransform != nullptr, "CameraSystem.GetActiveCameraWorldTransform");
		need((g_api.spatialClass != nullptr || g_api.spatialInterface != nullptr) && g_api.raycast != nullptr &&
				 g_api.raycast->params.Size() == 6,
			 "SpatialQueriesSystem.SyncRaycastByCollisionGroup(6)");
		if (!missing.empty())
		{
			cb::log::error("view: disabled: %s missing", missing.c_str());
			g_api.state = Api::State::Disabled;
			return false;
		}
		g_api.state = Api::State::Ready;
		return true;
	}

	RED4ext::IScriptable *system(RED4ext::CClass *cls, RED4ext::CClass *fallback = nullptr)
	{
		const RED4ext::CGameEngine *engine = RED4ext::CGameEngine::Get();
		if (engine == nullptr || engine->framework == nullptr || engine->framework->gameInstance == nullptr)
			return nullptr;
		RED4ext::IScriptable *s = cls != nullptr ? engine->framework->gameInstance->GetSystem(cls) : nullptr;
		if (s == nullptr && fallback != nullptr)
			s = engine->framework->gameInstance->GetSystem(fallback);
		return s;
	}

	/// The active camera's world position and its Minecraft pitch.
	bool camera(Vec3 &position, double &pitch)
	{
		RED4ext::IScriptable *cameras = system(g_api.cameraClass);
		if (cameras == nullptr)
			return false;
		RED4ext::Transform transform;
		transform.orientation = {0.0f, 0.0f, 0.0f, 0.0f};
		bool ok = false;
		RED4ext::StackArgs_t args{{nullptr, &transform}};
		if (!RED4ext::ExecuteFunction(cameras, g_api.cameraTransform, &ok, args) || !ok)
			return false;
		const RED4ext::Quaternion &q = transform.orientation;
		if (q.i * q.i + q.j * q.j + q.k * q.k + q.r * q.r < 0.5f)
			return false;
		position = {transform.position.X, transform.position.Y, transform.position.Z};
		pitch = cb::cp_quat_to_mc_yaw_pitch(q.i, q.j, q.k, q.r).pitch;
		return std::isfinite(position.x) && std::isfinite(position.y) && std::isfinite(position.z) && std::isfinite(pitch);
	}

	std::optional<double> raycast(const Vec3 &from, const Vec3 &to)
	{
		RED4ext::IScriptable *spatial = system(g_api.spatialClass, g_api.spatialInterface);
		if (spatial == nullptr)
			return std::nullopt;
		std::optional<double> nearest;
		for (const char *group : kGroups)
		{
			RED4ext::Vector4 start{float(from.x), float(from.y), float(from.z), 1.0f};
			RED4ext::Vector4 end{float(to.x), float(to.y), float(to.z), 1.0f};
			RED4ext::CName groupName(group);
			RED4ext::physics::TraceResult result{};
			bool staticOnly = false, dynamicOnly = false, hit = false;
			RED4ext::StackArgs_t args{{nullptr, &start},  {nullptr, &end},        {nullptr, &groupName},
									  {nullptr, &result}, {nullptr, &staticOnly}, {nullptr, &dynamicOnly}};
			if (!RED4ext::ExecuteFunction(spatial, g_api.raycast, &hit, args) || !hit)
				continue;
			const Vec3 at{result.position.X, result.position.Y, result.position.Z};
			if (!std::isfinite(at.x) || !std::isfinite(at.y) || !std::isfinite(at.z))
				continue;
			const double d = distance(from, at);
			if (!nearest || d < *nearest)
				nearest = d;
		}
		return nearest;
	}

	bool set_local(RED4ext::IScriptable *component, RED4ext::Vector4 position)
	{
		RED4ext::StackArgs_t args{{nullptr, &position}};
		return RED4ext::ExecuteFunction(component, g_api.setLocal, nullptr, args);
	}

	/// Takes the offset off again (the component's own local position back), if it is still there.
	void restore(const char *reason)
	{
		if (!g_applied)
			return;
		g_applied = false;
		g_appliedFlag = false;
		g_offset = {};
		g_before.reset();
		if (const RED4ext::Handle<RED4ext::IScriptable> component = g_component.Lock())
			set_local(component.GetPtr(), g_base);
		g_component = {};
		cb::log::info("view: first person (%s)", reason);
	}

	/// Why the view may not be applied now; nullptr when it may.
	const char *blocked()
	{
		if (!cb::world::third_person_wanted())
			return "Minecraft asks for first person";
		if (!cb::link().linked())
			return "link down";
		if (!cb::natives::active)
			return "Cyberblock off";
		if (cb::natives::inVehicle || cb::natives::seated)
			return "in a vehicle";
		if (cb::natives::menuOpen)
			return "a menu is open";
		if (cb::natives::held)
			return "V is held";
		if (!g_allowed)
			return "not allowed by the scripts";
		return nullptr;
	}
}

namespace cb::view
{
	void tick()
	{
		const auto now = std::chrono::steady_clock::now();
		const double dt = g_lastFrame.time_since_epoch().count() == 0
							  ? 0.0
							  : std::min(std::chrono::duration<double>(now - g_lastFrame).count(), 0.1);
		g_lastFrame = now;

		if (!natives::active)
		{
			restore("Cyberblock off");
			return;
		}
		if (!resolve())
			return;

		Vec3 cam{};
		double pitch = 0.0;
		const bool haveCamera = camera(cam, pitch);
		const char *why = blocked();
		if (why != nullptr || !haveCamera)
		{
			restore(why != nullptr ? why : "no camera");
			if (haveCamera)
				set_eye(cam);
			return;
		}

		RED4ext::ScriptGameInstance game;
		RED4ext::Handle<RED4ext::IScriptable> player;
		if (!RED4ext::ExecuteGlobalFunction("GetPlayer;GameInstance", &player, game) || !player)
		{
			restore("no V");
			return;
		}
		RED4ext::StackArgs_t none;
		RED4ext::Quaternion orientation{0.0f, 0.0f, 0.0f, 0.0f};
		if (!RED4ext::ExecuteFunction(player.GetPtr(), g_api.orientation, &orientation, none))
		{
			restore("V's orientation unreadable");
			return;
		}
		const Quat q{orientation.i, orientation.j, orientation.k, orientation.r};
		const double norm = q.i * q.i + q.j * q.j + q.k * q.k + q.r * q.r;
		if (!(norm > 0.5 && norm < 1.5))
		{
			restore("V's orientation is not a rotation");
			return;
		}

		if (!g_applied)
		{
			RED4ext::Handle<RED4ext::IScriptable> component;
			if (!RED4ext::ExecuteFunction(player.GetPtr(), g_api.fpp, &component, none) || !component ||
				!RED4ext::ExecuteFunction(component.GetPtr(), g_api.getLocal, &g_base, none))
			{
				static bool warned = false;
				if (!std::exchange(warned, true))
					log::warn("view: V's first-person camera component is not there; staying in first person (logged once)");
				set_eye(cam);
				return;
			}
			g_component = RED4ext::WeakHandle<RED4ext::IScriptable>(component);
			g_applied = true;
			g_appliedFlag = true;
			g_offset = {};
			g_before = cam;
			g_boom.reset(g_orbit.distance);
			log::info("view: third person on (distance %.2f m)", g_orbit.distance);
		}

		const RED4ext::Handle<RED4ext::IScriptable> component = g_component.Lock();
		if (!component)
		{
			g_applied = false;
			g_appliedFlag = false;
			log::info("view: first person (the camera component went away)");
			return;
		}

		// the eye: the camera without last frame's offset
		const Vec3 applied = rotate(q, g_offset);
		const Vec3 eye{cam.x - applied.x, cam.y - applied.y, cam.z - applied.z};
		set_eye(eye);
		if (g_before && !g_measured && (g_offset.x != 0.0 || g_offset.y != 0.0 || g_offset.z != 0.0))
		{
			g_measured = true;
			log::info("view: applied, eye moved %.2f m (expected %.2f m)", distance(cam, *g_before),
					  std::hypot(g_offset.x, g_offset.y, g_offset.z));
		}

		// the boom: as long as the orbit wants, shortened where the ray from the eye meets Night City or a block
		const Vec3 want = desired_local(g_orbit, pitch);
		const Vec3 wantWorld = rotate(q, want);
		const double length = std::hypot(want.x, want.y, want.z);
		double target = g_orbit.distance;
		if (const auto hit = raycast(eye, {eye.x + wantWorld.x, eye.y + wantWorld.y, eye.z + wantWorld.z}); hit && length > 0.0)
			target = std::min(g_orbit.distance, g_orbit.distance * collision_distance(*hit) / length);
		const double boom = dt > 0.0 ? g_boom.step(target, dt) : g_boom.step(target, 1e-3);
		g_offset = desired_local(g_orbit, pitch, boom);
		RED4ext::Vector4 local{float(g_base.X + g_offset.x), float(g_base.Y + g_offset.y), float(g_base.Z + g_offset.z), g_base.W};
		if (!set_local(component.GetPtr(), local))
			restore("SetLocalPosition failed");
	}

	bool applied()
	{
		return g_appliedFlag;
	}

	Vec3 eye()
	{
		std::lock_guard<std::mutex> lock(g_eyeLock);
		return g_eye;
	}

	void set_allowed(bool allowed)
	{
		g_allowed = allowed;
	}
}
