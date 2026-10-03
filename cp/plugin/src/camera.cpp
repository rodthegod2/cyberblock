// Reads the camera and V through the game's script functions (RED4ext ExecuteFunction, as the SDK's
// examples/execute_functions and native_globals_redscript). Names and signatures are from NativeDB (2.31 dump):
//   gameCameraSystem.GetActiveCameraWorldTransform(transform: Transform) -> Bool  (fills `transform`)
//   gameCameraSystem.GetActiveCameraFOV() -> Float, GetAspectRatio() -> Float, GetActiveCameraForward() -> Vector4
//   GetPlayer;GameInstance(ScriptGameInstance) -> handle:PlayerPuppet
//   entEntity.GetWorldPosition() -> Vector4, GetWorldForward() -> Vector4, GetWorldOrientation() -> Quaternion (2.31
//   script API core/entity/entity.swift)
// Positions (camera, feet) take the ground alignment (plan 2026-10-03-retest2 section 1.1: Minecraft y = Cyberpunk z - k/64),
// read once per publish and sent as `cam.lv`; directions (facing, forward, the look quaternion) never do.
#include "camera.hpp"
#include "cbmath.hpp"
#include "compositor.h"
#include "drive.hpp"
#include "keys.hpp"
#include "link.hpp"
#include "log.hpp"
#include "natives.hpp"
#include "view.hpp"
#include "world.hpp"
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

	// re-test 3 task D3: how far Cyberpunk turns the camera per raw mouse count, learnt on foot (main thread only)
	cb::keys::TurnRate g_turn;
	double g_lastYaw = NAN;     // the previous sampled frame's Minecraft yaw; NaN after a frame that was not sampled
	double g_loggedRate = 0.0;  // the rate the last `look:` line gave

	/// Feeds TurnRate with this frame's yaw change and counts while V turns only by the mouse, and logs the rate once it
	/// settles and whenever it changes by more than 10 %.
	void learn_turn_rate(double mcYaw, int64_t counts)
	{
		const bool sampling = cb::natives::active && !cb::natives::held && !cb::natives::menuOpen && !cb::natives::inVehicle &&
							  cb::drive::seconds_since_end() > 0.5f;
		if (!sampling)
		{
			g_lastYaw = NAN;
			return;
		}
		if (std::isfinite(g_lastYaw))
			g_turn.sample(std::remainder(mcYaw - g_lastYaw, 360.0), counts);
		g_lastYaw = mcYaw;
		if (g_turn.counts() < cb::keys::TurnRate::kWarmUpCounts)
			return;
		const double rate = g_turn.degrees_per_count();
		if (g_loggedRate == 0.0 || std::abs(rate - g_loggedRate) > 0.1 * g_loggedRate)
		{
			g_loggedRate = rate;
			cb::log::info("look: %.4f deg per mouse count", rate);
		}
	}

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
}

namespace cb::camera
{
	double degrees_per_count()
	{
		return g_turn.degrees_per_count();
	}

	void publish(int64_t mouseX)
	{
		if (!natives::active || !link().linked())
		{
			g_lastYaw = NAN;
			return;
		}
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

		// re-test 3 (task D4): seated in a vehicle, `pl` is V's hips point (V's orientation applied to the script's
		// V-local hips offset) and `fp` says which vehicle camera is on
		const bool seated = natives::seated;
		Vec3 body{feet.X, feet.Y, feet.Z};
		if (seated)
		{
			RED4ext::Quaternion orientation{0.0f, 0.0f, 0.0f, 0.0f};
			if (call(player.GetPtr(), "entEntity", "GetWorldOrientation", &orientation))
			{
				const Quat o{orientation.i, orientation.j, orientation.k, orientation.r};
				const double norm = o.i * o.i + o.j * o.j + o.k * o.k + o.r * o.r;
				if (norm > 0.5 && norm < 1.5)
				{
					const Vec3 hips = rotate(o, {natives::hipsX.load(), natives::hipsY.load(), natives::hipsZ.load()});
					body = {body.x + hips.x, body.y + hips.y, body.z + hips.z};
				}
			}
		}
		const bool firstPerson = seated ? !natives::seatTpp : !view::applied();

		const int levelK = world::level_k(); // once per publish: the cam's positions and its `lv` agree
		const double off = world::level_offset(levelK);
		const RED4ext::Vector4 &c = transform.position;
		const Vec3 position = cp_to_mc_pos(c.X, c.Y, c.Z, off);
		const YawPitch look = cp_quat_to_mc_yaw_pitch(q.i, q.j, q.k, q.r);
		if (aspect <= 0.0f)
			aspect = 16.0f / 9.0f;
		const double vfov = fovIsHorizontal ? vfov_from_hfov(fov, aspect) : fov;
		const Vec3 feetMc = cp_to_mc_pos(body.x, body.y, body.z, off);
		const Vec3 facingMc = cp_dir_to_mc(facing.X, facing.Y, facing.Z);
		const double bodyYaw = std::hypot(facingMc.x, facingMc.z) > 1e-6 ? mc_yaw_pitch(facingMc.x, 0.0, facingMc.z).yaw : look.yaw;

		link().send(world::make_cam(g_frame++, position, look, vfov, feetMc, bodyYaw,
									{.crouched = natives::crouched,
									 .flying = natives::flightFollowing,
									 .menu = natives::menuOpen,
									 .air = natives::inAir,
									 .held = natives::held},
									firstPerson, seated, levelK));
		compositor::set_host_pose(float(look.yaw), float(look.pitch), 0.0f, float(vfov), position.x, position.y, position.z);
		compositor::set_host_planes(kNearClip, kFarClip);
		learn_turn_rate(look.yaw, mouseX);
		drive::note_pitch(look.pitch);

		if (g_loggedPoses < kLoggedPoses)
		{
			// for the cube test (F2): the raw camera, what it became, and the camera's own forward as a cross-check
			RED4ext::Vector4 forward;
			call(cameras, "gameCameraSystem", "GetActiveCameraForward", &forward);
			const Vec3 forwardMc = cp_dir_to_mc(forward.X, forward.Y, forward.Z);
			const YawPitch check = std::hypot(forwardMc.x, forwardMc.y, forwardMc.z) > 1e-6
									   ? mc_yaw_pitch(forwardMc.x, forwardMc.y, forwardMc.z)
									   : YawPitch{NAN, NAN};
			log::info("pose %d: cp pos (%.3f, %.3f, %.3f) quat (%.4f, %.4f, %.4f, %.4f) fov %.2f aspect %.4f -> mc pos "
					  "(%.3f, %.3f, %.3f) yaw %.2f pitch %.2f vfov %.2f; GetActiveCameraForward gives yaw %.2f pitch %.2f; "
					  "feet mc (%.3f, %.3f, %.3f) body yaw %.2f; ground alignment k %d/64",
					  g_loggedPoses + 1, c.X, c.Y, c.Z, q.i, q.j, q.k, q.r, fov, aspect, position.x, position.y,
					  position.z, look.yaw, look.pitch, vfov, check.yaw, check.pitch, feetMc.x, feetMc.y, feetMc.z, bodyYaw,
					  levelK);
			++g_loggedPoses;
		}
	}
}
