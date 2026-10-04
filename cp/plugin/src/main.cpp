// Cyberblock.dll: a RED4ext plugin (loaded from red4ext\plugins\Cyberblock\) that also registers itself as a ReShade
// add-on (compositor.h). It links to the Minecraft mod (link.hpp), gives redscript its natives (natives.hpp),
// moves V after Steve's flights every frame (drive.hpp), applies the third-person view (view.hpp), publishes Cyberpunk's
// camera every frame (camera.hpp), casts the Atlas survey's rays every frame (survey_game.hpp), forwards input from Cyberpunk's window (input.hpp) and pumps the UI bridge (ui.hpp).
#include <RED4ext/RED4ext.hpp>

#include "camera.hpp"
#include "compositor.h"
#include "devices.hpp"
#include "drive.hpp"
#include "feed.hpp"
#include "input.hpp"
#include "link.hpp"
#include "log.hpp"
#include "natives.hpp"
#include "rhack.hpp"
#include "snapshots.hpp"
#include "survey_game.hpp"
#include "ui.hpp"
#include "view.hpp"
#include "world.hpp"

namespace
{
	RED4ext::v1::PluginHandle g_handle = nullptr;
	bool g_compositorRegistered = false; // only touched from the game's update thread

	/// Cyberpunk 2077 2.31. RED4ext splits the product version "2.31" by digit (2.3.1); the 2.31.0 reading is accepted
	/// as well so the gate does not hinge on how the loader parses it. Both are logged on load.
	bool is_supported_runtime(const RED4ext::v1::SemVer &version)
	{
		return version.major == 2 && ((version.minor == 3 && version.patch == 1) || (version.minor == 31 && version.patch == 0));
	}

	bool on_running_enter(RED4ext::CGameApplication *)
	{
		return true;
	}

	/// Runs every frame while the game is running.
	bool on_running_update(RED4ext::CGameApplication *)
	{
		// ReShade may load after this plugin, so keep trying to register the compositor with it until that succeeds.
		if (!g_compositorRegistered && compositor::try_register(g_handle))
		{
			g_compositorRegistered = true;
			cb::log::info("compositor registered with ReShade");
		}
		int width = 0, height = 0;
		compositor::backbuffer_size(width, height);
		cb::link().set_view(width, height);
		// the mouse X of this frame: the drive turns V by it during a glide (it owns V's yaw), the camera learns the rate
		const int64_t dx = cb::input::take_mouse_x();
		cb::drive::tick(dx, cb::camera::degrees_per_count()); // before the camera: cam.fly is true on the first frame V is moved
		cb::survey_game::frame();                             // the survey's rays, at the stop the drive just held V at
		cb::view::tick();                                     // before the camera: cam.fp says whether the view applied
		cb::camera::publish(dx);
		cb::input::tick();
		cb::ui::pump(); // the UI bridge (ui_game.cpp) throttles itself to 30 Hz and runs while paused too
		// a Cyberpunk menu passed through (no Minecraft screen replaced it): Minecraft's world is not drawn over it
		compositor::set_menu_mode(cb::natives::active && cb::natives::menuOpen && !cb::link().screen_open());
		return false;
	}
}

RED4EXT_C_EXPORT bool RED4EXT_CALL Main(RED4ext::v1::PluginHandle aHandle, RED4ext::v1::EMainReason aReason,
										const RED4ext::v1::Sdk *aSdk)
{
	switch (aReason)
	{
	case RED4ext::v1::EMainReason::Load:
	{
		g_handle = aHandle;
		cb::log::open(aHandle);

		const RED4ext::v1::SemVer *runtime = aSdk != nullptr ? aSdk->runtime : nullptr;
		if (runtime == nullptr)
		{
			cb::log::error("Cyberblock %s: RED4ext did not report the game version; staying inactive", cb::kVersion);
			return true;
		}
		cb::log::info("Cyberblock %s loading, game version %u.%u.%u", cb::kVersion, static_cast<unsigned>(runtime->major),
					  static_cast<unsigned>(runtime->minor), static_cast<unsigned>(runtime->patch));
		if (!is_supported_runtime(*runtime))
		{
			cb::log::error("unsupported game version (Cyberblock needs Cyberpunk 2077 2.31); staying inactive");
			return true; // loaded but registered nothing: the game runs untouched
		}

		RED4ext::CRTTISystem *rtti = RED4ext::CRTTISystem::Get();
		rtti->AddRegisterCallback(cb::natives::register_types);
		rtti->AddPostRegisterCallback(cb::natives::post_register_types);

		RED4ext::v1::GameState running{on_running_enter, on_running_update, nullptr};
		if (!aSdk->gameStates->Add(aHandle, RED4ext::EGameStateType::Running, &running))
			cb::log::error("could not add the Running game state; no compositor or camera");

		cb::link().start(
			[](const nlohmann::json &message)
			{
				cb::world::on_minecraft_message(message);
				cb::snap::on_minecraft_message(message);
				cb::input::on_minecraft_message(message);
				cb::ui::on_minecraft_message(message);
				cb::survey_game::on_minecraft_message(message);
				cb::devices::on_minecraft_message(message); // Phase 4: `devact`
				cb::rhack::on_minecraft_message(message);   // Phase 6: `rhack`, `daemon`
				cb::feed::on_minecraft_message(message);    // Phase 6: `feedreq` (and `hello`, `session`)
				if (message.value("t", "") == "session")
				{
					// megaplan 1-H1: a new server session gets the whole ground again; leaving the host role cleans the DLL
					// side as a link drop does (flight stop, unpause), but the WebSocket stays open
					cb::natives::on_session_message();
					if (cb::world::take_session_cleanup())
					{
						cb::snap::on_link_dropped();
						cb::natives::on_link_dropped();
						cb::ui::on_link_dropped();
					}
				}
			},
			[]
			{
				cb::snap::on_link_dropped();
				cb::world::on_link_dropped();
				cb::natives::on_link_dropped();
				cb::ui::on_link_dropped();
				cb::survey_game::on_link_dropped();
				cb::devices::on_link_dropped();
				cb::feed::on_link_dropped();
			});
		break;
	}
	case RED4ext::v1::EMainReason::Unload:
	{
		cb::input::shutdown();
		cb::link().stop();
		compositor::unregister(g_handle);
		g_compositorRegistered = false;
		cb::log::info("Cyberblock unloaded");
		cb::log::close();
		break;
	}
	}

	return true;
}

RED4EXT_C_EXPORT void RED4EXT_CALL Query(RED4ext::v1::PluginInfo *aInfo)
{
	aInfo->name = L"Cyberblock";
	aInfo->author = L"rodthegod2";
	aInfo->version = RED4EXT_V1_SEMVER(CB_VERSION_MAJOR, CB_VERSION_MINOR, CB_VERSION_PATCH); // project(VERSION)
	// the game version is gated in Main(), so RED4ext only acts as the loader
	aInfo->runtime = RED4EXT_V1_RUNTIME_VERSION_INDEPENDENT;
	aInfo->sdk = RED4EXT_V1_SDK_VERSION_CURRENT;
}

RED4EXT_C_EXPORT uint32_t RED4EXT_CALL Supports()
{
	return RED4EXT_API_VERSION_1;
}
