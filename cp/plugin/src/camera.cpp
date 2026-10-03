// Reads the camera and V through the game's script functions (RED4ext ExecuteFunction, as the SDK's
// examples/execute_functions and native_globals_redscript). Names and signatures are from NativeDB (2.31 dump):
//   gameCameraSystem.GetActiveCameraWorldTransform(transform: Transform) -> Bool  (fills `transform`)
//   gameCameraSystem.GetActiveCameraFOV() -> Float, GetAspectRatio() -> Float, GetActiveCameraForward() -> Vector4
//   GetPlayer;GameInstance(ScriptGameInstance) -> handle:PlayerPuppet
//   entEntity.GetWorldPosition() -> Vector4, GetWorldForward() -> Vector4
#include "camera.hpp"
#include "cbmath.hpp"
#include "compositor.h"
#include "link.hpp"
#include "log.hpp"
#include "natives.hpp"
#include <RED4ext/GameEngine.hpp>
#include <RED4ext/RED4ext.hpp>
#include <RED4ext/Scripting/Natives/Generated/Vector4.hpp>
#include <RED4ext/Scripting/Natives/ScriptGameInstance.hpp>
#include <RED4ext/Scripting/Natives/Transform.hpp>
#include <cmath>
#include <cstdint>

namespace
{
	// Scripts cannot read the camera's clip planes (only entBaseCameraComponent.near/farPlaneOverride exist).
	// Cyberpunk's depth is reversed-Z with an infinite far plane (raw depth is linear in screen row on flat ground,
	// F1). Near from that ground slope: n = slope * eyeHeight * fy = 1.173e-5 * ~1.7 m * 1062 px (vertical fov
	// 68.24 deg at 1440p) = 0.021. Fine-tune live with the shader's "Night City depth scale".
	constexpr float kNearClip = 0.0212f;
	constexpr float kFarClip = 10000.0f;
	constexpr int kLoggedPoses = 5;

	int64_t g_frame = 0;
	int g_loggedPoses = 0;
	bool g_warned = false;

	void warn_once(const char *message)
	{
		if (!g_warned)
			cb::log::error("%s; no camera is published", message);
		g_warned = true;
	}

	/// The game's camera system, or null outside a game session.
	RED4ext::IScriptable *camera_system()
	{
		const RED4ext::CGameEngine *engine = RED4ext::CGameEngine::Get();
		if (engine == nullptr || engine->framework == nullptr || engine->framework->gameInstance == nullptr)
			return nullptr;
		static RED4ext::CClass *cls = RED4ext::CRTTISystem::Get()->GetClass("gameCameraSystem");
		return cls != nullptr ? engine->framework->gameInstance->GetSystem(cls) : nullptr;
	}

	/// Calls `self.function(args...)` of script class `className`; false if anything is missing.
	bool call(RED4ext::IScriptable *self, const char *className, const char *function, void *out,
			  RED4ext::StackArgs_t args = {})
	{
		RED4ext::CClass *cls = RED4ext::CRTTISystem::Get()->GetClass(className);
		RED4ext::CBaseFunction *fn = cls != nullptr ? cls->GetFunction(function) : nullptr;
		return self != nullptr && fn != nullptr && RED4ext::ExecuteFunction(self, fn, out, args);
	}

	nlohmann::json triple(const cb::Vec3 &v)
	{
		return {v.x, v.y, v.z};
	}
}

namespace cb::camera
{
	void publish()
	{
		if (!natives::active || !link().linked())
			return;
		RED4ext::IScriptable *cameras = camera_system();
		if (cameras == nullptr)
			return;

		RED4ext::Transform transform;
		transform.orientation = {0.0f, 0.0f, 0.0f, 0.0f}; // not a rotation: shows whether the call filled it in
		bool haveTransform = false;
		float fov = 0.0f;
		float aspect = 0.0f;
		if (!call(cameras, "gameCameraSystem", "GetActiveCameraWorldTransform", &haveTransform, {{nullptr, &transform}}) ||
			!haveTransform || !call(cameras, "gameCameraSystem", "GetActiveCameraFOV", &fov) ||
			!call(cameras, "gameCameraSystem", "GetAspectRatio", &aspect))
			return warn_once("the camera system calls failed");
		const RED4ext::Quaternion &q = transform.orientation;
		if (q.i * q.i + q.j * q.j + q.k * q.k + q.r * q.r < 0.5f)
			return warn_once("GetActiveCameraWorldTransform did not fill the transform");

		RED4ext::ScriptGameInstance game;
		RED4ext::Handle<RED4ext::IScriptable> player;
		RED4ext::Vector4 feet, facing;
		if (!RED4ext::ExecuteGlobalFunction("GetPlayer;GameInstance", &player, game) || !player)
			return; // loading, or no V yet
		if (!call(player.GetPtr(), "entEntity", "GetWorldPosition", &feet) ||
			!call(player.GetPtr(), "entEntity", "GetWorldForward", &facing))
			return warn_once("reading V's position failed");

		const RED4ext::Vector4 &c = transform.position;
		const Vec3 position = cp_to_mc_pos(c.X, c.Y, c.Z);
		const YawPitch look = cp_quat_to_mc_yaw_pitch(q.i, q.j, q.k, q.r);
		if (aspect <= 0.0f)
			aspect = 16.0f / 9.0f;
		const double vfov = fovIsHorizontal ? vfov_from_hfov(fov, aspect) : fov;
		const Vec3 feetMc = cp_to_mc_pos(feet.X, feet.Y, feet.Z);
		const Vec3 facingMc = cp_to_mc_pos(facing.X, facing.Y, facing.Z);
		const double bodyYaw = std::hypot(facingMc.x, facingMc.z) > 1e-6 ? mc_yaw_pitch(facingMc.x, 0.0, facingMc.z).yaw : look.yaw;

		link().send({{"t", "cam"},
					  {"f", g_frame++},
					  {"p", triple(position)},
					  {"r", {look.yaw, look.pitch, 0.0}},
					  {"fov", vfov},
					  {"fp", true},
					  {"pl", triple(feetMc)},
					  {"h", bodyYaw}});
		compositor::set_host_pose(float(look.yaw), float(look.pitch), 0.0f, float(vfov), position.x, position.y, position.z);
		compositor::set_host_planes(kNearClip, kFarClip);

		if (g_loggedPoses < kLoggedPoses)
		{
			// for the cube test (F2): the raw camera, what it became, and the camera's own forward as a cross-check
			RED4ext::Vector4 forward;
			call(cameras, "gameCameraSystem", "GetActiveCameraForward", &forward);
			const Vec3 forwardMc = cp_to_mc_pos(forward.X, forward.Y, forward.Z);
			const YawPitch check = std::hypot(forwardMc.x, forwardMc.y, forwardMc.z) > 1e-6
									   ? mc_yaw_pitch(forwardMc.x, forwardMc.y, forwardMc.z)
									   : YawPitch{NAN, NAN};
			log::info("pose %d: cp pos (%.3f, %.3f, %.3f) quat (%.4f, %.4f, %.4f, %.4f) fov %.2f aspect %.4f -> mc pos "
					  "(%.3f, %.3f, %.3f) yaw %.2f pitch %.2f vfov %.2f; GetActiveCameraForward gives yaw %.2f pitch %.2f; "
					  "feet mc (%.3f, %.3f, %.3f) body yaw %.2f",
					  g_loggedPoses + 1, c.X, c.Y, c.Z, q.i, q.j, q.k, q.r, fov, aspect, position.x, position.y,
					  position.z, look.yaw, look.pitch, vfov, check.yaw, check.pitch, feetMc.x, feetMc.y, feetMc.z, bodyYaw);
			++g_loggedPoses;
		}
	}
}
