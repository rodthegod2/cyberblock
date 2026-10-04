// redscript natives, registered as RTTI global functions like RED4ext.SDK's examples/native_globals_redscript.
#include "natives.hpp"
#include "compositor.h"
#include "devices.hpp"
#include "drive.hpp"
#include "link.hpp"
#include "log.hpp"
#include "native_reg.hpp"
#include "probes.hpp"
#include "snapshots.hpp"
#include "survey_game.hpp"
#include "ui.hpp"
#include "view.hpp"
#include "world.hpp"
#include <RED4ext/RED4ext.hpp>
#include <RED4ext/Scripting/Natives/Generated/Vector4.hpp>
#include <RED4ext/Scripting/Natives/ScriptGameInstance.hpp>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <string>
#include <initializer_list>
#include <mutex>
#include <optional>
#include <string_view>
#include <utility>
#include <vector>

using RED4ext::CStackFrame;
using RED4ext::IScriptable;

namespace
{
	constexpr double kTeleportMetres = 64.0;

	/// What Minecraft has been told; touched only under g_lock (scripts may call from more than one thread).
	struct Mashup
	{
		bool active = false;
		bool vehicle = false;
		int session = 0; // the link session the state below was sent to
		int levelK = 0;  // the ground alignment `probes` was built with (plan retest2 section 1.1)
		int groundEpoch = 0; // the server session `probes` was built for (world::ground_epoch, megaplan 1-H1)
		cb::ProbeSet probes;
		std::optional<cb::Vec3> lastCenter;
		// Phase 3: the codes of the last Cyberblock_SubmitProbeMaterials (down, wall), for the next SubmitProbes only
		std::optional<std::pair<std::vector<int>, std::vector<int>>> pendingMaterials;
		cb::MaterialCache materials;
		int materialPath = -1; // which path the last probe tick took: -1 none yet, 0 Minecraft's heuristic, 1 the scripts
	};

	/// The current ground alignment as an offset in metres (Minecraft y = Cyberpunk z - off).
	double level_offset()
	{
		return cb::world::level_offset(cb::world::level_k());
	}
	std::mutex g_lock;
	Mashup g_mashup;

	void send_state()
	{
		cb::link().send({{"t", "state"}, {"active", g_mashup.active}, {"vehicle", g_mashup.vehicle}});
		// kept for older Minecraft builds; since re-test 3 Minecraft's HUD (and first-person hand) stays in vehicles too
		cb::link().send({{"t", "hud"}, {"hidden", false}});
	}

	/// Logs the seat when it changes ("seat: on (third person)", "seat: on (first person)", "seat: off").
	void log_seat(bool seated, bool tpp)
	{
		static std::mutex lock;
		static int last = -1; // 0 off, 1 first person, 2 third person
		const int now = !seated ? 0 : tpp ? 2 : 1;
		std::lock_guard<std::mutex> guard(lock);
		if (std::exchange(last, now) == now)
			return;
		if (now == 0)
			cb::log::info("seat: off");
		else
			cb::log::info("seat: on (%s)", now == 2 ? "third person" : "first person");
	}

	void clear_seat()
	{
		cb::natives::seated = false;
		cb::natives::seatTpp = false;
		cb::natives::hipsX = cb::natives::hipsY = cb::natives::hipsZ = 0.0f;
		cb::natives::seatFwdX = cb::natives::seatFwdY = 0.0f;
		log_seat(false, false);
	}

	/// Minecraft drops all ground and is sent it again from the next probes (for the current server session).
	void restart_ground()
	{
		cb::link().send({{"t", "clear"}});
		g_mashup.probes.clear();
		g_mashup.lastCenter.reset();
		cb::world::take_ground_restart(g_mashup.groundEpoch);
	}

	std::vector<cb::Vec3> to_vec3(const RED4ext::DynArray<RED4ext::Vector4> &points)
	{
		std::vector<cb::Vec3> out;
		out.reserve(points.Size());
		for (const RED4ext::Vector4 &p : points)
			out.push_back({p.X, p.Y, p.Z});
		return out;
	}

	/// A script array copied into a vector, each element cast to `To` (Bool arrays become bytes, which std::span can view).
	template<typename To, typename From>
	std::vector<To> to_vector(const RED4ext::DynArray<From> &array)
	{
		std::vector<To> out;
		out.reserve(array.Size());
		for (const From &value : array)
			out.push_back(static_cast<To>(value));
		return out;
	}

	/// The entry of `names` that `name` spells ("" when it is none of them). Compares hashes, so no game name pool is needed.
	template<size_t N>
	std::string_view known_name(RED4ext::CName name, const char *const (&names)[N])
	{
		for (const char *candidate : names)
			if (RED4ext::CName(candidate) == name)
				return candidate;
		return {};
	}

	/// Sends `message`; without one (a bad argument) says so once per `warned` flag.
	void send_or_warn(const std::optional<nlohmann::json> &message, bool &warned, const char *native, const char *why)
	{
		if (message)
			cb::link().send(*message);
		else if (!std::exchange(warned, true))
			cb::log::warn("%s: %s; dropping it (logged once)", native, why);
	}

	RED4ext::Vector4 to_vector4(const cb::Vec3 &v, float w)
	{
		return {float(v.x), float(v.y), float(v.z), w};
	}

	// Cyberblock_Version() -> String
	void Version(IScriptable *, CStackFrame *frame, RED4ext::CString *out, int64_t)
	{
		++frame->code; // skip ParamEnd
		if (out)
			*out = RED4ext::CString(cb::kVersion);
	}

	// Cyberblock_IsLinked() -> Bool
	void IsLinked(IScriptable *, CStackFrame *frame, bool *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = cb::link().linked();
	}

	// Cyberblock_SecondsSinceFrame() -> Float. compositor.h has no frame clock, so this is the time since Minecraft's
	// last `status` (10 Hz while it runs).
	void SecondsSinceFrame(IScriptable *, CStackFrame *frame, float *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = cb::link().seconds_since_status();
	}

	// Cyberblock_ScreenOpen() -> Bool
	void ScreenOpen(IScriptable *, CStackFrame *frame, bool *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = cb::link().screen_open();
	}

	// Cyberblock_ConsumeToggle() -> Bool
	void ConsumeToggle(IScriptable *, CStackFrame *frame, bool *out, int64_t)
	{
		++frame->code;
		const bool pressed = cb::natives::togglePressed.exchange(false);
		if (out)
			*out = pressed;
	}

	// Cyberblock_SetActive(active: Bool, inVehicle: Bool) -> Void
	void SetActive(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		bool active = false;
		bool inVehicle = false;
		RED4ext::GetParameter(frame, &active);
		RED4ext::GetParameter(frame, &inVehicle);
		++frame->code;

		std::lock_guard<std::mutex> lock(g_lock);
		const bool activeChanged = active != g_mashup.active;
		const bool changed = activeChanged || inVehicle != g_mashup.vehicle;
		g_mashup.active = active;
		g_mashup.vehicle = inVehicle;
		g_mashup.session = cb::link().session();
		cb::natives::active = active;
		cb::natives::inVehicle = inVehicle;
		if (!active)
		{
			cb::natives::flightAllowed = false; // the script allows flights again on its next tick while Active
			clear_seat();
			cb::view::set_allowed(false); // the view comes off on the next frame
			cb::natives::phonePrompt = false;
		}
		cb::world::events().set_active(active);
		compositor::set_active(active);
		if (changed)
		{
			cb::log::info("mashup %s%s", active ? "on" : "off", inVehicle ? " (in a vehicle)" : "");
			cb::natives::choicesOpen = false; // the script sets it again on its next tick
			cb::natives::flightFollowing = false;
			send_state();
		}
		if (activeChanged)
			restart_ground();
	}

	/// The `ground.m` code of a material or mesh appearance name; an unknown name is logged the first time. g_lock held.
	int material_of(RED4ext::CName name)
	{
		if (name.IsNone())
			return 0;
		if (const auto code = g_mashup.materials.find(name.hash))
			return *code;
		const char *text = name.ToString();
		bool firstUnknown = false;
		const int code = g_mashup.materials.add(name.hash, text ? text : "", firstUnknown);
		if (firstUnknown)
			cb::log::info("materials: unknown name %s -> 0", text);
		return code;
	}

	/// Logs which material path the probe ticks take when it changes (the first 10 changes: it should not flap).
	void log_material_path(int path)
	{
		static int changes = 0;
		if (std::exchange(g_mashup.materialPath, path) == path || changes++ >= 10)
			return;
		if (path == 1)
			cb::log::info("materials: path scripts (Cyberblock_SubmitProbeMaterials each tick; ground.m sent, CET B1-B5 values)");
		else
			cb::log::info("materials: path heuristic (no materials from the scripts; ground.m left out, Minecraft's "
						  "material_palette heuristic applies: fallback b)");
	}

	// Cyberblock_SubmitProbeMaterials(down: array<CName>, wall: array<CName>) -> Void (megaplan section 1.8 amendment
	// item 1): one material or mesh appearance name per down and wall hit of the Cyberblock_SubmitProbes that follows in
	// the same tick (n"" when neither is known). Mapped to `ground.m` codes through the fixed table (probes.hpp).
	void SubmitProbeMaterials(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::DynArray<RED4ext::CName> down;
		RED4ext::DynArray<RED4ext::CName> wall;
		RED4ext::GetParameter(frame, &down);
		RED4ext::GetParameter(frame, &wall);
		++frame->code;

		std::lock_guard<std::mutex> lock(g_lock);
		const auto codes = [](const RED4ext::DynArray<RED4ext::CName> &names)
		{
			std::vector<int> out;
			out.reserve(names.Size());
			for (const RED4ext::CName &name : names)
				out.push_back(material_of(name));
			return out;
		};
		g_mashup.pendingMaterials.emplace(codes(down), codes(wall));
	}

	/// Phase 4 (the block at the end): drops the hits inside device boxes, with their material codes. g_lock held.
	void filter_device_hits(std::vector<cb::Vec3> &down, std::vector<cb::Vec3> &wall,
							std::pair<std::vector<int>, std::vector<int>> *codes);

	// Cyberblock_SubmitProbes(center: Vector4, downHits: array<Vector4>, wallHits: array<Vector4>, radius: Float)
	void SubmitProbes(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::Vector4 center;
		RED4ext::DynArray<RED4ext::Vector4> downHits;
		RED4ext::DynArray<RED4ext::Vector4> wallHits;
		float radius = 0.0f;
		RED4ext::GetParameter(frame, &center);
		RED4ext::GetParameter(frame, &downHits);
		RED4ext::GetParameter(frame, &wallHits);
		RED4ext::GetParameter(frame, &radius);
		++frame->code;

		std::lock_guard<std::mutex> lock(g_lock);
		// the materials belong to this tick only, whatever happens to it
		auto pending = std::exchange(g_mashup.pendingMaterials, std::nullopt);
		if (!g_mashup.active || !cb::link().linked())
			return;
		if (g_mashup.session != cb::link().session())
		{
			// Minecraft (re)connected since we last told it anything
			g_mashup.session = cb::link().session();
			send_state();
			restart_ground();
		}
		// Minecraft re-aligned the ground (`align`): every range already sent sits at the old height, so start over
		const int levelK = cb::world::level_k();
		if (levelK != g_mashup.levelK)
		{
			cb::log::info("ground: alignment k %d -> %d, Minecraft gets the ground again", g_mashup.levelK, levelK);
			g_mashup.levelK = levelK;
			restart_ground();
		}
		const cb::Vec3 feet{center.X, center.Y, center.Z};
		// the link's queue had to drop ground messages while Minecraft was not reading: its ground starts over
		const bool groundLost = cb::link().take_ground_lost();
		if (groundLost || (g_mashup.lastCenter && std::hypot(feet.x - g_mashup.lastCenter->x, feet.y - g_mashup.lastCenter->y,
														 feet.z - g_mashup.lastCenter->z) > kTeleportMetres))
			restart_ground();
		g_mashup.lastCenter = feet;

		std::optional<cb::ProbeMaterials> materials;
		if (pending && pending->first.size() == downHits.Size() && pending->second.size() == wallHits.Size())
			materials = cb::ProbeMaterials{pending->first, pending->second};
		else if (pending)
		{
			static bool warned = false;
			if (!std::exchange(warned, true))
				cb::log::warn("Cyberblock_SubmitProbeMaterials: %zu down / %zu wall names for %u down / %u wall hits; ground.m "
							  "left out for that tick (logged once)",
							  pending->first.size(), pending->second.size(), downHits.Size(), wallHits.Size());
		}
		log_material_path(materials ? 1 : 0);

		std::vector<cb::Vec3> down = to_vec3(downHits), wall = to_vec3(wallHits);
		filter_device_hits(down, wall, materials ? &*pending : nullptr); // Phase 4: doors are not walls
		if (materials)
			materials = cb::ProbeMaterials{pending->first, pending->second};
		const cb::Delta delta = g_mashup.probes.submit(feet, down, wall, radius,
													   cb::world::level_offset(levelK), cb::steady_seconds(),
													   materials ? &*materials : nullptr);
		// Phase 3 (megaplan section 1.9): cells observed empty carry `seen` (the replica drops them); cells that only
		// left the radius do not (the replica keeps them)
		if (!delta.seen.empty())
			cb::link().send({{"t", "unground"}, {"c", delta.seen}, {"seen", true}});
		if (!delta.remove.empty())
			cb::link().send({{"t", "unground"}, {"c", delta.remove}});
		if (!delta.add.empty())
		{
			nlohmann::json ground{{"t", "ground"}, {"c", delta.add}};
			if (!delta.materials.empty())
				ground["m"] = delta.materials;
			cb::link().send(ground);
		}
	}

	// ---- Phase 3 (megaplan 2026-10-04 sections 1.8 and 1.9 with the Phase 0 amendment, task 3-D1) ---------------------

	std::string name_text(RED4ext::CName name)
	{
		if (name.IsNone())
			return {};
		const char *text = name.ToString();
		return text ? text : "";
	}

	/// The `env` gate (1 Hz for the clock, a weather change at once, never the same message twice) and what it was
	/// last reset for; touched only under g_envLock.
	std::mutex g_envLock;
	cb::ui::Gate g_envGate;
	int g_envSession = -1, g_envEpoch = -1;
	std::vector<std::string> g_unmappedWeather; // presets already logged as unmapped (at most 64)

	// Cyberblock_SetEnv(hour: Int32, minute: Int32, weather: CName) -> Void (Env.reds, 1 Hz): the game time and the
	// weather preset name (Codeware WeatherSystem.GetWeatherState().name; n"" when it cannot be read) as `env`.
	void SetEnv(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		int32_t hour = 0, minute = 0;
		RED4ext::CName weather;
		RED4ext::GetParameter(frame, &hour);
		RED4ext::GetParameter(frame, &minute);
		RED4ext::GetParameter(frame, &weather);
		++frame->code;
		if (!cb::natives::active || !cb::link().linked())
			return;

		const std::string preset = name_text(weather);
		bool known = true;
		const auto message = cb::world::make_env(hour, minute, preset, &known);
		std::lock_guard<std::mutex> lock(g_envLock);
		if (!message)
		{
			static bool warned = false;
			if (!std::exchange(warned, true))
				cb::log::warn("Cyberblock_SetEnv: time %d:%d is not a clock time; dropping it (logged once)", hour, minute);
			return;
		}
		if (!known && g_unmappedWeather.size() < 64 && std::ranges::find(g_unmappedWeather, preset) == g_unmappedWeather.end())
		{
			g_unmappedWeather.push_back(preset);
			cb::log::info("env: weather preset %s is not mapped -> other", preset.c_str());
		}
		// a Minecraft that (re)connected, or a new server session, has not had the current values
		const int session = cb::link().session(), epoch = cb::world::ground_epoch();
		if (std::exchange(g_envSession, session) != session || std::exchange(g_envEpoch, epoch) != epoch)
			g_envGate.reset();
		static bool first = true;
		const std::string weatherOut = (*message)["weather"];
		if (!g_envGate.admit(message->dump(), weatherOut, cb::steady_seconds(), 1.0))
			return;
		cb::link().send(*message);
		if (std::exchange(first, false))
			cb::log::info("env: path scripts (time %02d:%02d, weather %s -> %s)%s", hour, minute,
						  preset.empty() ? "unreadable" : preset.c_str(), weatherOut.c_str(),
						  preset.empty() ? ": weather is 'other' (fallback: no weather preset name)" : "");
	}

	/// V's feet now (Cyberpunk space) through the game's script functions, as camera.cpp reads them; nullopt without V.
	std::optional<cb::Vec3> player_feet()
	{
		RED4ext::ScriptGameInstance game;
		RED4ext::Handle<RED4ext::IScriptable> player;
		if (!RED4ext::ExecuteGlobalFunction("GetPlayer;GameInstance", &player, game) || !player)
			return std::nullopt;
		RED4ext::CClass *entity = RED4ext::CRTTISystem::Get()->GetClass("entEntity");
		RED4ext::CBaseFunction *position = entity != nullptr ? entity->GetFunction("GetWorldPosition") : nullptr;
		RED4ext::Vector4 feet;
		RED4ext::StackArgs_t none;
		if (position == nullptr || !RED4ext::ExecuteFunction(player.GetPtr(), position, &feet, none))
			return std::nullopt;
		return cb::Vec3{feet.X, feet.Y, feet.Z};
	}

	// Cyberblock_TeleportDone(id: Int32, ok: Bool, reason: String) -> Void (Teleport.reds): the answer to event
	// n"vteleport" `id`; reason "" (ok), "no ground", "timeout" or "blocked (<why>)". Sent as `vteleported` with V's feet
	// now (the snapped position), or the last probe centre when V cannot be read.
	void TeleportDone(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		int32_t id = 0;
		bool ok = false;
		RED4ext::CString reason;
		RED4ext::GetParameter(frame, &id);
		RED4ext::GetParameter(frame, &ok);
		RED4ext::GetParameter(frame, &reason);
		++frame->code;

		std::optional<cb::Vec3> feet = player_feet();
		if (!feet)
		{
			std::lock_guard<std::mutex> lock(g_lock);
			feet = g_mashup.lastCenter;
		}
		static bool warned = false;
		const auto message =
			cb::world::make_vteleported(id, ok, reason.c_str(), feet.value_or(cb::Vec3{NAN, NAN, NAN}), level_offset());
		if (message)
			cb::log::info("vteleported: %d %s%s%s at mc (%.2f, %.2f, %.2f)", id, ok ? "ok" : "failed", ok ? "" : ": ",
						  ok ? "" : (*message)["reason"].get<std::string>().c_str(), (*message)["p"][0].get<double>(),
						  (*message)["p"][1].get<double>(), (*message)["p"][2].get<double>());
		send_or_warn(message, warned, "Cyberblock_TeleportDone",
					 "a negative id, no position for V, or a reason that is not \"\", \"no ground\", \"timeout\" or "
					 "\"blocked (<why>)\"");
	}

	// Cyberblock_SetAim(distance: Float) -> Void: metres from the camera to the first Night City surface under the
	// crosshair (negative: none in range). Minecraft won't target blocks beyond it, so nothing is built or broken
	// through Night City walls and no selection outline shows through them. Sent only when it changes by 2 cm or more.
	void SetAim(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		float distance = -1.0f;
		RED4ext::GetParameter(frame, &distance);
		++frame->code;
		static float last = -2.0f;
		if (!std::isfinite(distance))
			distance = -1.0f;
		const bool changed = (distance < 0.0f) != (last < 0.0f) || std::fabs(distance - last) >= 0.02f;
		if (!changed)
			return;
		last = distance;
		cb::link().send({{"t", "aim"}, {"d", distance < 0.0f ? -1.0 : std::round(double(distance) * 1000.0) / 1000.0}});
	}

	// Cyberblock_Damage(frac: Float, kind: CName, from: Vector4, hasFrom: Bool) -> Void. A damage event on V as a
	// fraction of V's max health; `from` is in Cyberpunk space. Dropped while the mashup is not Active.
	void Damage(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		float frac = 0.0f;
		RED4ext::CName kind;
		RED4ext::Vector4 from;
		bool hasFrom = false;
		RED4ext::GetParameter(frame, &frac);
		RED4ext::GetParameter(frame, &kind);
		RED4ext::GetParameter(frame, &from);
		RED4ext::GetParameter(frame, &hasFrom);
		++frame->code;

		if (!cb::natives::active)
			return;
		const auto message =
			cb::world::make_dmg(frac, known_name(kind, cb::world::kDamageKinds), {from.X, from.Y, from.Z}, hasFrom, level_offset());
		if (message)
			cb::link().send(*message);
		else
		{
			static bool warned = false;
			if (!std::exchange(warned, true))
				cb::log::warn("Cyberblock_Damage: frac is not a finite number; dropping it (logged once)");
		}
	}

	// Cyberblock_SubmitNpcs(ids: array<Uint64>, feet: array<Vector4>, yaws: array<Float>, vehicles: array<Bool>,
	// hostile: array<Bool>, dead: array<Bool>) -> Void. The full list of nearby NPCs and vehicles (Minecraft despawns
	// the proxy of any id that is missing); `dead` marks the last sighting of one that died or was destroyed, whoever
	// caused it (Minecraft leaves a body or a wreck). Feet and yaws are Cyberpunk values; a yaw is in degrees as
	// Entity.GetWorldYaw reports it.
	void SubmitNpcs(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::DynArray<uint64_t> ids;
		RED4ext::DynArray<RED4ext::Vector4> feet;
		RED4ext::DynArray<float> yaws;
		RED4ext::DynArray<bool> vehicles;
		RED4ext::DynArray<bool> hostile;
		RED4ext::DynArray<bool> dead;
		RED4ext::GetParameter(frame, &ids);
		RED4ext::GetParameter(frame, &feet);
		RED4ext::GetParameter(frame, &yaws);
		RED4ext::GetParameter(frame, &vehicles);
		RED4ext::GetParameter(frame, &hostile);
		RED4ext::GetParameter(frame, &dead);
		++frame->code;

		if (!cb::natives::active)
			return;
		const std::vector<uint64_t> idList = to_vector<uint64_t>(ids);
		const std::vector<cb::Vec3> feetList = to_vec3(feet);
		const std::vector<double> yawList = to_vector<double>(yaws);
		const std::vector<uint8_t> vehicleList = to_vector<uint8_t>(vehicles);
		const std::vector<uint8_t> hostileList = to_vector<uint8_t>(hostile);
		const std::vector<uint8_t> deadList = to_vector<uint8_t>(dead);
		const auto message =
			cb::world::make_npcs(idList, feetList, yawList, vehicleList, hostileList, level_offset(), deadList);
		if (message)
			cb::link().send(*message);
		else
		{
			static bool warned = false;
			if (!std::exchange(warned, true))
				cb::log::warn("Cyberblock_SubmitNpcs: arrays differ in length (ids %zu, feet %zu, yaws %zu, vehicles %zu, "
							  "hostile %zu, dead %zu); dropping the list (logged once)",
							  idList.size(), feetList.size(), yawList.size(), vehicleList.size(), hostileList.size(),
							  deadList.size());
		}
	}

	// ---- Phase 5 (megaplan 2026-10-04 sections 1.8 and 1.9 with the Phase 0 amendment, item 7; task 5-D1) -------------

	/// The people's last positions for the walk and run bits (the scripts cannot read them, Phase 0 f), the log state,
	/// and the lock around both.
	std::mutex g_npcLock;
	cb::world::NpcMotion g_npcMotion;
	bool g_npcColourLogged = false;
	std::chrono::steady_clock::time_point g_npcSummaryAt;

	std::vector<std::string> name_list(const RED4ext::DynArray<RED4ext::CName> &names)
	{
		std::vector<std::string> out;
		out.reserve(names.Size());
		for (const RED4ext::CName &name : names)
			out.push_back(name_text(name));
		return out;
	}

	/// The DLL's runtime path lines of the user's test: the colour format once, and every 10 s what the list carried.
	/// The tags path line (`npcs: tags full|faction only`) has one source, the scripts (Npcs.reds), which see the gender
	/// and archetype names before they are packed. Called with the list that was sent.
	void log_npc_paths(const nlohmann::json &message)
	{
		if (!std::exchange(g_npcColourLogged, true))
			cb::log::info("%s", cb::world::kColourFormatLog);
		const cb::world::NpcTagSummary tags = cb::world::summarize_npcs(message.at("list"));
		if (tags.people == 0 && tags.vehicles == 0)
			return;
		const auto now = std::chrono::steady_clock::now();
		if (now - g_npcSummaryAt < std::chrono::seconds(10))
			return;
		g_npcSummaryAt = now;
		cb::log::info("npcs: %d people (faction %d, role %d, archetype %d; gender male %d female %d unknown %d; walk %d run %d), "
					  "%d vehicles (colour %d)",
					  tags.people, tags.withFaction, tags.withRole, tags.withArchetype, tags.male, tags.female,
					  tags.unknownGender, tags.walking, tags.running, tags.vehicles, tags.withColour);
	}

	// Cyberblock_SubmitNpcs2(ids, feet, yaws, vehicles, hostile, dead, roles: array<CName>, factions: array<CName>,
	// genders: array<Int32>, archetypes: array<CName>, anims: array<Int32>, vclass: array<CName>, colours: array<Int32>,
	// hp: array<Float>) -> Void: Cyberblock_SubmitNpcs plus the Phase 5 figure tags, parallel to ids (Npcs.reds). The
	// DLL adds the walk and run bits of `anims` from position deltas. Cyberblock_SubmitNpcs stays for older scripts.
	void SubmitNpcs2(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::DynArray<uint64_t> ids;
		RED4ext::DynArray<RED4ext::Vector4> feet;
		RED4ext::DynArray<float> yaws;
		RED4ext::DynArray<bool> vehicles;
		RED4ext::DynArray<bool> hostile;
		RED4ext::DynArray<bool> dead;
		RED4ext::DynArray<RED4ext::CName> roles;
		RED4ext::DynArray<RED4ext::CName> factions;
		RED4ext::DynArray<int32_t> genders;
		RED4ext::DynArray<RED4ext::CName> archetypes;
		RED4ext::DynArray<int32_t> anims;
		RED4ext::DynArray<RED4ext::CName> vclass;
		RED4ext::DynArray<int32_t> colours;
		RED4ext::DynArray<float> hp;
		RED4ext::GetParameter(frame, &ids);
		RED4ext::GetParameter(frame, &feet);
		RED4ext::GetParameter(frame, &yaws);
		RED4ext::GetParameter(frame, &vehicles);
		RED4ext::GetParameter(frame, &hostile);
		RED4ext::GetParameter(frame, &dead);
		RED4ext::GetParameter(frame, &roles);
		RED4ext::GetParameter(frame, &factions);
		RED4ext::GetParameter(frame, &genders);
		RED4ext::GetParameter(frame, &archetypes);
		RED4ext::GetParameter(frame, &anims);
		RED4ext::GetParameter(frame, &vclass);
		RED4ext::GetParameter(frame, &colours);
		RED4ext::GetParameter(frame, &hp);
		++frame->code;

		if (!cb::natives::active)
			return;
		const std::vector<uint64_t> idList = to_vector<uint64_t>(ids);
		const std::vector<cb::Vec3> feetList = to_vec3(feet);
		const std::vector<double> yawList = to_vector<double>(yaws);
		const std::vector<uint8_t> vehicleList = to_vector<uint8_t>(vehicles);
		const std::vector<uint8_t> hostileList = to_vector<uint8_t>(hostile);
		const std::vector<uint8_t> deadList = to_vector<uint8_t>(dead);
		const std::vector<std::string> roleList = name_list(roles), factionList = name_list(factions),
									   archetypeList = name_list(archetypes), vclassList = name_list(vclass);
		const std::vector<int32_t> genderList = to_vector<int32_t>(genders), animList = to_vector<int32_t>(anims),
								   colourList = to_vector<int32_t>(colours);
		const std::vector<double> hpList = to_vector<double>(hp);
		const cb::world::NpcTags tags{roleList, factionList, archetypeList, vclassList, genderList, animList, colourList, hpList};
		const double now = std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();

		std::lock_guard<std::mutex> lock(g_npcLock);
		const auto message = cb::world::make_npcs2(idList, feetList, yawList, vehicleList, hostileList, level_offset(), deadList,
												   tags, &g_npcMotion, now);
		if (message)
		{
			cb::link().send(*message);
			log_npc_paths(*message);
		}
		else
		{
			g_npcMotion.clear();
			static bool warned = false;
			if (!std::exchange(warned, true))
				cb::log::warn("Cyberblock_SubmitNpcs2: arrays differ in length (ids %zu, feet %zu, yaws %zu, vehicles %zu, "
							  "hostile %zu, dead %zu, roles %zu, factions %zu, genders %zu, archetypes %zu, anims %zu, "
							  "vclass %zu, colours %zu, hp %zu); dropping the list (logged once)",
							  idList.size(), feetList.size(), yawList.size(), vehicleList.size(), hostileList.size(),
							  deadList.size(), roleList.size(), factionList.size(), genderList.size(), archetypeList.size(),
							  animList.size(), vclassList.size(), colourList.size(), hpList.size());
		}
	}

	// Cyberblock_NextEvent() -> CName: pops the oldest Minecraft event and makes it current: n"explosion", n"hit",
	// n"ignite", n"block_add", n"block_remove", n"block_reset", n"teleport", n"lead", n"lead_release", n"pull", n"knock",
	// n"effect", n"hack", n"scan", n"perk", n"perkfx", n"cyber" or n"cyberfx" (re-test 2: one `cyber` per entry of a
	// `cyberware` message, kept while not Active like n"perk"; n"cyberfx" is dropped while not Active), n"prefs" (Phase Q:
	// Minecraft's camera prefs arrived, kept while not Active; Cyberblock_EventFlag is `stepup`), n"session" (Phase 1: the
	// server's verdict on this host changed; kept while not Active), n"vteleport" (Phase 3: move V; EventPos the target
	// feet, EventAmount the yaw, EventId the request id, EventName why; kept while not Active: Cyberblock_TeleportDone
	// answers it), n"park" (Phase 3: EventFlag on; kept while not Active), n"devact" (Phase 4: EventId the request id,
	// EventTarget the device id, EventName the act; kept while not Active: Cyberblock_DeviceResult answers it), n"rhack"
	// (Phase 6: EventId the request id, EventTarget the target or 0 for an area hack, EventName the hack) and n"daemon"
	// (Phase 6: EventId the request id, EventTarget the device id, EventAmount the strength 1..3, EventName the first
	// daemon id, EventText every daemon id joined by ','), both kept while not Active and answered with
	// Cyberblock_HackResult; n"" (the empty CName, hash 0) when there is none.
	void NextEvent(IScriptable *, CStackFrame *frame, RED4ext::CName *out, int64_t)
	{
		++frame->code;
		const cb::world::EventKind kind = cb::world::events().next();
		if (kind == cb::world::EventKind::VTeleport || kind == cb::world::EventKind::Park)
		{
			const cb::world::Event e = cb::world::events().current();
			if (kind == cb::world::EventKind::VTeleport)
				cb::log::info("vteleport: %d (%s) to cp (%.2f, %.2f, %.2f) handed to the scripts", e.id, e.name.c_str(), e.pos.x,
							  e.pos.y, e.pos.z);
			else
				cb::log::info("park: %s handed to the scripts", e.flag ? "on" : "off");
		}
		if (out)
			*out = RED4ext::CName(cb::world::event_name(kind)); // "" gives hash 0
	}

	// Cyberblock_EventPos() -> Vector4: the explosion centre, or the block's AABB min corner (Cyberpunk space, w = 1).
	void EventPos(IScriptable *, CStackFrame *frame, RED4ext::Vector4 *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = to_vector4(cb::world::events().current().pos, 1.0f);
	}

	// Cyberblock_EventTarget() -> Uint64: the EntityID hash a hit or ignite aims at.
	void EventTarget(IScriptable *, CStackFrame *frame, uint64_t *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = cb::world::events().current().target;
	}

	// Cyberblock_EventAmount() -> Float: explosion power | hit damage as a fraction of the target's max health | ignite
	// seconds | ... (world.hpp Event::amount) | cyber: 1 installed, 0 not | cyberfx: seconds (0 = end now).
	void EventAmount(IScriptable *, CStackFrame *frame, float *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = float(cb::world::events().current().amount);
	}

	// Cyberblock_EventVector() -> Vector4: the hit impulse in Cyberpunk space, m/s (w = 0).
	void EventVector(IScriptable *, CStackFrame *frame, RED4ext::Vector4 *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = to_vector4(cb::world::events().current().vector, 0.0f);
	}

	// Cyberblock_EventFlag() -> Bool: whether the explosion burns | prefs: the step-up assist is on | park: on (world.hpp
	// Event::flag).
	void EventFlag(IScriptable *, CStackFrame *frame, bool *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = cb::world::events().current().flag;
	}

	// Cyberblock_EventName() -> CName: the effect name | hack name | perk id | perkfx id | cyberware id | cyberfx id |
	// session state (n"none", n"host", n"guest", n"refused") | vteleport why (n"meetup", n"holo", n"hub_exit"); n"" for the
	// other kinds.
	void EventName(IScriptable *, CStackFrame *frame, RED4ext::CName *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = RED4ext::CName(cb::world::events().current().name.c_str());
	}

	// Cyberblock_EventExtra() -> Float: the knock strength | effect amplifier | session perms bitmask (1 explosions,
	// 2 damage, 4 hacks, 8 devices, 16 spawns); 0 for the other kinds.
	void EventExtra(IScriptable *, CStackFrame *frame, float *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = float(cb::world::events().current().extra);
	}

	// Cyberblock_EventId() -> Int32: a hit's mob id (-1 when no mob dealt it) | a hack, scan or vteleport request id; 0
	// otherwise.
	void EventId(IScriptable *, CStackFrame *frame, int32_t *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = cb::world::events().current().id;
	}

	// Cyberblock_EventText() -> String (megaplan 2026-10-04 section 1.8): the string payload of the current event: a
	// session's reason | a daemon event's ids joined by ',' (Phase 6); "" for the other kinds.
	void EventText(IScriptable *, CStackFrame *frame, RED4ext::CString *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = RED4ext::CString(cb::world::events().current().text.c_str());
	}

	// ---- layer 3: mobs ----------------------------------------------------------------------------------------------

	/// The index parameter `i: Int32` of the snapshot getters.
	int32_t index_parameter(CStackFrame *frame)
	{
		int32_t i = 0;
		RED4ext::GetParameter(frame, &i);
		++frame->code;
		return i;
	}

	// Cyberblock_MobCount() -> Int32: takes a snapshot of the latest `mobs` list (empty once it is 2 s old, after a link
	// drop or a hello) that the getters read.
	void MobCount(IScriptable *, CStackFrame *frame, int32_t *out, int64_t)
	{
		++frame->code;
		const size_t count = cb::snap::mobs().snapshot(cb::snap::Clock::now());
		if (out)
			*out = int32_t(count);
	}

	// Cyberblock_MobId(i: Int32) -> Int32: the Minecraft entity id; 0 for an index out of range (the getters below too).
	void MobId(IScriptable *, CStackFrame *frame, int32_t *out, int64_t)
	{
		const auto mob = cb::snap::mobs().at(index_parameter(frame));
		if (out)
			*out = mob ? mob->id : 0;
	}

	// Cyberblock_MobKind(i: Int32) -> CName: n"zombie", n"skeleton", ...
	void MobKind(IScriptable *, CStackFrame *frame, RED4ext::CName *out, int64_t)
	{
		const auto mob = cb::snap::mobs().at(index_parameter(frame));
		if (out)
			*out = mob ? RED4ext::CName(mob->kind.c_str()) : RED4ext::CName();
	}

	// Cyberblock_MobPos(i: Int32) -> Vector4: feet, Cyberpunk space (w = 1).
	void MobPos(IScriptable *, CStackFrame *frame, RED4ext::Vector4 *out, int64_t)
	{
		const auto mob = cb::snap::mobs().at(index_parameter(frame));
		if (out)
			*out = mob ? to_vector4(mob->feet, 1.0f) : to_vector4({}, 0.0f);
	}

	// Cyberblock_MobYaw(i: Int32) -> Float: Cyberpunk yaw, degrees.
	void MobYaw(IScriptable *, CStackFrame *frame, float *out, int64_t)
	{
		const auto mob = cb::snap::mobs().at(index_parameter(frame));
		if (out)
			*out = mob ? float(mob->yaw) : 0.0f;
	}

	// Cyberblock_MobSize(i: Int32) -> Vector4: X width, Y height, Z hp, W max hp.
	void MobSize(IScriptable *, CStackFrame *frame, RED4ext::Vector4 *out, int64_t)
	{
		const auto mob = cb::snap::mobs().at(index_parameter(frame));
		if (out)
			*out = mob ? RED4ext::Vector4{float(mob->width), float(mob->height), float(mob->hp), float(mob->maxHp)}
					   : to_vector4({}, 0.0f);
	}

	// ---- layer 4: flight and projectiles ----------------------------------------------------------------------------

	// Cyberblock_FlightActive() -> Bool: Minecraft says Steve glides (a sample older than 0.5 s counts as not).
	void FlightActive(IScriptable *, CStackFrame *frame, bool *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = cb::snap::flight().active(cb::snap::Clock::now());
	}

	// Cyberblock_FlightSeq() -> Int32: +1 on every start and every end (a landing, a 0.5 s timeout or a reset).
	void FlightSeq(IScriptable *, CStackFrame *frame, int32_t *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = cb::snap::flight().seq(cb::snap::Clock::now());
	}

	// Cyberblock_FlightTimedOut() -> Bool: the last flight ended because its samples stopped for 0.5 s (Minecraft went
	// silent, not a landing); false again after any new sample, a landing or a reset.
	void FlightTimedOut(IScriptable *, CStackFrame *frame, bool *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = cb::snap::flight().timed_out(cb::snap::Clock::now());
	}

	// ---- re-test 2 (plan 2026-10-03-retest2 section 1.3): the per-frame drive -------------------------------------------

	// Cyberblock_SetFlightAllowed(allowed: Bool) -> Void: 10 Hz from CyberblockFlight.Tick: V may be moved (live, not
	// mounted, not locked). The DLL drive (drive.cpp) never moves V while it is false. Cleared by Cyberblock_SetActive(false).
	void SetFlightAllowed(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		bool allowed = false;
		RED4ext::GetParameter(frame, &allowed);
		++frame->code;
		cb::natives::flightAllowed = allowed;
	}

	// Cyberblock_FlightDriving() -> Bool: the DLL is moving V this flight (the same value as cam.fly).
	void FlightDriving(IScriptable *, CStackFrame *frame, bool *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = cb::drive::driving();
	}

	// Cyberblock_SecondsSinceFlight() -> Float: 0 while driving, the seconds since the last flight ended (Cyberpunk menus
	// do not count), 999 if none yet.
	void SecondsSinceFlight(IScriptable *, CStackFrame *frame, float *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = cb::drive::seconds_since_end();
	}

	// Cyberblock_FlightPos() -> Vector4: feet, Cyberpunk space, p + v * min(age, 0.05 s) while flying, else the landing spot.
	// Kept for scripts; the drive uses Flight::render_pos.
	void FlightPos(IScriptable *, CStackFrame *frame, RED4ext::Vector4 *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = to_vector4(cb::snap::flight().pos(cb::snap::Clock::now()), 1.0f);
	}

	// Cyberblock_FlightVelocity() -> Vector4: m/s, Cyberpunk space (w = 0).
	void FlightVelocity(IScriptable *, CStackFrame *frame, RED4ext::Vector4 *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = to_vector4(cb::snap::flight().velocity(cb::snap::Clock::now()), 0.0f);
	}

	// Cyberblock_FlightYaw() -> Float: Cyberpunk yaw, degrees.
	void FlightYaw(IScriptable *, CStackFrame *frame, float *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = float(cb::snap::flight().yaw(cb::snap::Clock::now()));
	}

	// Cyberblock_FlightPitch() -> Float: Minecraft pitch, degrees (positive = down).
	void FlightPitch(IScriptable *, CStackFrame *frame, float *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = float(cb::snap::flight().pitch(cb::snap::Clock::now()));
	}

	// Cyberblock_ProjCount() -> Int32: takes a snapshot of the latest `proj` list (empty once it is 0.5 s old).
	void ProjCount(IScriptable *, CStackFrame *frame, int32_t *out, int64_t)
	{
		++frame->code;
		const size_t count = cb::snap::projectiles().snapshot(cb::snap::Clock::now());
		if (out)
			*out = int32_t(count);
	}

	// Cyberblock_ProjPos(i: Int32) -> Vector4: Cyberpunk space (w = 1); zeros for an index out of range.
	void ProjPos(IScriptable *, CStackFrame *frame, RED4ext::Vector4 *out, int64_t)
	{
		const auto p = cb::snap::projectiles().at(index_parameter(frame));
		if (out)
			*out = p ? to_vector4(p->pos, 1.0f) : to_vector4({}, 0.0f);
	}

	// Cyberblock_ProjVelocity(i: Int32) -> Vector4: m/s, Cyberpunk space (w = 0).
	void ProjVelocity(IScriptable *, CStackFrame *frame, RED4ext::Vector4 *out, int64_t)
	{
		const auto p = cb::snap::projectiles().at(index_parameter(frame));
		if (out)
			*out = to_vector4(p ? p->velocity : cb::Vec3{}, 0.0f);
	}

	// Cyberblock_ProjGravity(i: Int32) -> Float: m/s^2 along -Z.
	void ProjGravity(IScriptable *, CStackFrame *frame, float *out, int64_t)
	{
		const auto p = cb::snap::projectiles().at(index_parameter(frame));
		if (out)
			*out = p ? float(p->gravity) : 0.0f;
	}

	// ---- messages to Minecraft: layers 3-6 --------------------------------------------------------------------------

	// Cyberblock_MobDamage(id: Int32, frac: Float, by: Uint64, hasBy: Bool) -> Void: a Cyberpunk NPC (`by`, when `hasBy`)
	// damaged the puppet of Minecraft mob `id` by `frac` of its max health. Dropped while the mashup is not Active.
	void MobDamage(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		int32_t id = 0;
		float frac = 0.0f;
		uint64_t by = 0;
		bool hasBy = false;
		RED4ext::GetParameter(frame, &id);
		RED4ext::GetParameter(frame, &frac);
		RED4ext::GetParameter(frame, &by);
		RED4ext::GetParameter(frame, &hasBy);
		++frame->code;

		if (!cb::natives::active)
			return;
		static bool warned = false;
		send_or_warn(cb::world::make_mobdmg(id, frac, by, hasBy), warned, "Cyberblock_MobDamage",
					 "the mob id is negative or frac is not a finite number");
	}

	// Cyberblock_SubmitProjectileHits(hits: array<Vector4>) -> Void: Cyberpunk-space surface points on predicted projectile
	// paths; Minecraft makes the block cells around them temporary barriers. Dropped while the mashup is not Active.
	void SubmitProjectileHits(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::DynArray<RED4ext::Vector4> hits;
		RED4ext::GetParameter(frame, &hits);
		++frame->code;

		if (!cb::natives::active)
			return;
		const std::vector<cb::Vec3> points = to_vec3(hits);
		if (const auto message = cb::world::make_pcells(points, level_offset()))
			cb::link().send(*message);
	}

	// Cyberblock_Unlead(target: Uint64) -> Void: Cyberpunk gave up the tether on `target` (gone, or snapped).
	void Unlead(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		uint64_t target = 0;
		RED4ext::GetParameter(frame, &target);
		++frame->code;
		cb::link().send(cb::world::make_unlead(target));
	}

	// Cyberblock_ScanResult(id: Int32, ok: Bool, target: Uint64, name: String, faction: String, threat: CName, hp: Float,
	// device: Bool, pos: Vector4, size: Vector4) -> Void: the reply to a `scan` request. Always sent (while linked): a
	// request must be answered. `pos` is feet in Cyberpunk space, `size` is X width, Y height.
	void ScanResult(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		int32_t id = 0;
		bool ok = false;
		uint64_t target = 0;
		RED4ext::CString name;
		RED4ext::CString faction;
		RED4ext::CName threat;
		float hp = 0.0f;
		bool device = false;
		RED4ext::Vector4 pos;
		RED4ext::Vector4 size;
		RED4ext::GetParameter(frame, &id);
		RED4ext::GetParameter(frame, &ok);
		RED4ext::GetParameter(frame, &target);
		RED4ext::GetParameter(frame, &name);
		RED4ext::GetParameter(frame, &faction);
		RED4ext::GetParameter(frame, &threat);
		RED4ext::GetParameter(frame, &hp);
		RED4ext::GetParameter(frame, &device);
		RED4ext::GetParameter(frame, &pos);
		RED4ext::GetParameter(frame, &size);
		++frame->code;

		static bool warned = false;
		send_or_warn(cb::world::make_scaninfo(id, ok, target, name.c_str(), faction.c_str(),
											  known_name(threat, cb::world::kThreatLevels), hp, device, {pos.X, pos.Y, pos.Z},
											  size.X, size.Y, level_offset()),
					 warned, "Cyberblock_ScanResult", "the request id is negative");
	}

	// Cyberblock_HackResult(id: Int32, ok: Bool, reason: String, upload: Float, kind: CName) -> Void: the reply to a `hack`
	// request. Always sent (while linked). `reason` is "" on success; `upload` (seconds, clamped 0..30) and `kind` (one of
	// world::kHackKinds, n"" allowed when !ok) only go out when `ok` (plan 2026-10-03-retest3-polish section 1.2).
	void HackResult(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		int32_t id = 0;
		bool ok = false;
		RED4ext::CString reason;
		float upload = 0.0f;
		RED4ext::CName kind;
		RED4ext::GetParameter(frame, &id);
		RED4ext::GetParameter(frame, &ok);
		RED4ext::GetParameter(frame, &reason);
		RED4ext::GetParameter(frame, &upload);
		RED4ext::GetParameter(frame, &kind);
		++frame->code;

		const std::string_view kindName = known_name(kind, cb::world::kHackKinds);
		if (ok && kindName.empty() && !kind.IsNone())
		{
			static bool kindWarned = false;
			if (!std::exchange(kindWarned, true))
				cb::log::warn("Cyberblock_HackResult: kind %s is not a known target kind; sent without one (logged once)",
							  kind.ToString());
		}
		static bool warned = false;
		send_or_warn(cb::world::make_hackresult(id, ok, reason.c_str(), upload, kindName), warned, "Cyberblock_HackResult",
					 "the request id is negative");
	}

	/// The per-id cap of Cyberblock_HackSpread.
	cb::world::SpreadCounter g_spreads;

	// Cyberblock_HackSpread(id: Int32, target: Uint64, why: CName) -> Void: hack request `id` also touched NPC `target`
	// (why: n"chain", n"spread", n"ping" or n"frenzy"). At most 16 per id; the 17th and later are dropped and counted.
	void HackSpread(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		int32_t id = 0;
		uint64_t target = 0;
		RED4ext::CName why;
		RED4ext::GetParameter(frame, &id);
		RED4ext::GetParameter(frame, &target);
		RED4ext::GetParameter(frame, &why);
		++frame->code;

		static bool warned = false;
		const auto message = cb::world::make_hackspread(id, target, known_name(why, cb::world::kSpreadWhy));
		if (!message)
		{
			if (!std::exchange(warned, true))
				cb::log::warn("Cyberblock_HackSpread: a negative id or an unknown why (%s); dropping it (logged once)",
							  why.ToString());
			return;
		}
		if (!g_spreads.allow(id))
		{
			static bool capWarned = false;
			if (!std::exchange(capWarned, true))
				cb::log::warn("Cyberblock_HackSpread: hack %d touched more than %d NPCs; the rest are not sent (logged once, "
							  "%llu so far)",
							  id, cb::world::kMaxSpreadPerId, static_cast<unsigned long long>(g_spreads.dropped()));
			return;
		}
		cb::link().send(*message);
	}

	// Cyberblock_Killed(target: Uint64, vehicle: Bool, hostile: Bool) -> Void: an NPC or vehicle that V damaged died.
	void Killed(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		uint64_t target = 0;
		bool vehicle = false;
		bool hostile = false;
		RED4ext::GetParameter(frame, &target);
		RED4ext::GetParameter(frame, &vehicle);
		RED4ext::GetParameter(frame, &hostile);
		++frame->code;
		cb::link().send(cb::world::make_killed(target, vehicle, hostile));
	}

	// Cyberblock_SetStance(crouched: Bool, inAir: Bool, held: Bool) -> Void: V is crouched / in the air / held by a vehicle
	// or the game; carried as `cam.cr`, `cam.air` and `cam.held` until they change.
	void SetStance(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		bool crouched = false;
		bool inAir = false;
		bool held = false;
		RED4ext::GetParameter(frame, &crouched);
		RED4ext::GetParameter(frame, &inAir);
		RED4ext::GetParameter(frame, &held);
		++frame->code;
		cb::natives::crouched = crouched;
		cb::natives::inAir = inAir;
		cb::natives::held = held;
	}

	// Cyberblock_GameMode() -> CName: Minecraft's last `status.gm` (n"survival", n"creative", n"adventure", n"spectator"),
	// n"" before any status in this session.
	void GameModeNative(IScriptable *, CStackFrame *frame, RED4ext::CName *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = RED4ext::CName(cb::game_mode_name(cb::link().game_mode()));
	}

	// Cyberblock_SetMenu(open: Bool) -> Void: a Cyberpunk menu is open; input forwarding pauses until it closes.
	void SetMenu(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		bool open = false;
		RED4ext::GetParameter(frame, &open);
		++frame->code;
		cb::natives::menuOpen = open;
	}

	// Cyberblock_SetChoices(open: Bool) -> Void: a Cyberpunk dialogue or choice list shows two or more choices; E, Q, 1-4
	// and the wheel go to Cyberpunk (to pick one) instead of Minecraft until it is false again.
	void SetChoices(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		bool open = false;
		RED4ext::GetParameter(frame, &open);
		++frame->code;
		cb::natives::choicesOpen = open;
	}

	// Cyberblock_SetLookCapture(on: Bool) -> Void: while on, raw mouse movement goes to Minecraft as `look` deltas. Kept,
	// but no script calls it since re-test 2 (the camera-only flight fallback is gone).
	void SetLookCapture(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		bool on = false;
		RED4ext::GetParameter(frame, &on);
		++frame->code;
		cb::natives::lookCapture = on;
	}

	// ---- re-test 3 (plan 2026-10-03-retest3-polish section 1.2) -------------------------------------------------------

	// Cyberblock_SetSeat(seated: Bool, tpp: Bool, hipsLocal: Vector4) -> Void: 10 Hz and on a camera switch from
	// CyberblockSeat.Tick. hipsLocal is in V's local axes (Y forward, Z up), metres; a non-finite one counts as zero.
	void SetSeat(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		bool seated = false;
		bool tpp = false;
		RED4ext::Vector4 hips{};
		RED4ext::GetParameter(frame, &seated);
		RED4ext::GetParameter(frame, &tpp);
		RED4ext::GetParameter(frame, &hips);
		++frame->code;

		if (!seated)
		{
			clear_seat();
			return;
		}
		const auto finite_or_0 = [](float v) { return std::isfinite(v) && std::abs(v) < 10.0f ? v : 0.0f; };
		cb::natives::hipsX = finite_or_0(hips.X);
		cb::natives::hipsY = finite_or_0(hips.Y);
		cb::natives::hipsZ = finite_or_0(hips.Z);
		cb::natives::seatTpp = tpp;
		cb::natives::seated = true;
		log_seat(true, tpp);
	}

	// Cyberblock_SetSeatForward(forward: Vector4) -> Void: the car's forward while V is seated (CyberblockSeat.Tick).
	void SetSeatForward(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::Vector4 forward{};
		RED4ext::GetParameter(frame, &forward);
		++frame->code;
		const float n = std::hypot(forward.X, forward.Y);
		const bool ok = std::isfinite(n) && n > 1e-3f;
		cb::natives::seatFwdX = ok ? forward.X / n : 0.0f;
		cb::natives::seatFwdY = ok ? forward.Y / n : 0.0f;
	}

	// Cyberblock_SetViewAllowed(allowed: Bool) -> Void: the third-person view may be applied now (Active on foot, not held,
	// no menu, no braindance, no photo mode; CyberblockView.Tick, 10 Hz). Cleared by Cyberblock_SetActive(false).
	void SetViewAllowed(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		bool allowed = false;
		RED4ext::GetParameter(frame, &allowed);
		++frame->code;
		cb::view::set_allowed(allowed);
	}

	// Cyberblock_ViewEye() -> Vector4: V's first-person eye in Cyberpunk space (w = 1): the active camera without the
	// third-person offset, so aim rays start where they would in first person.
	void ViewEye(IScriptable *, CStackFrame *frame, RED4ext::Vector4 *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = to_vector4(cb::view::eye(), 1.0f);
	}

	// Cyberblock_SetSlowMoFx(id: CName, seconds: Float) -> Void: the Sandevistan (n"sandevistan") or Kerenzikov
	// (n"kerenzikov") screen effect for `seconds` (0..30) from now; n"" or 0 ends it (it eases out).
	void SetSlowMoFx(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::CName id;
		float seconds = 0.0f;
		RED4ext::GetParameter(frame, &id);
		RED4ext::GetParameter(frame, &seconds);
		++frame->code;

		constexpr const char *kKinds[] = {"sandevistan", "kerenzikov"};
		const std::string_view kind = known_name(id, kKinds);
		if (!std::isfinite(seconds))
			seconds = 0.0f;
		if (kind.empty() || seconds <= 0.0f)
		{
			if (!id.IsNone() && kind.empty())
			{
				static bool warned = false;
				if (!std::exchange(warned, true))
					cb::log::warn("Cyberblock_SetSlowMoFx: %s is not sandevistan or kerenzikov; ending the effect (logged once)",
								  id.ToString());
			}
			compositor::set_slowmo(0, 0.0f);
			cb::log::info("screenfx: off");
			return;
		}
		seconds = std::min(seconds, 30.0f);
		compositor::set_slowmo(kind == "kerenzikov" ? 1 : 0, seconds);
		cb::log::info("screenfx: %s %.1f s", std::string(kind).c_str(), seconds);
	}

	// Cyberblock_SetPhonePrompt(open: Bool) -> Void (declared in U's UiNatives.reds): a call rings or an actionable
	// notification shows, so T goes to Cyberpunk (answer, reply) instead of opening Minecraft's chat.
	void SetPhonePrompt(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		bool open = false;
		RED4ext::GetParameter(frame, &open);
		++frame->code;
		if (cb::natives::phonePrompt.exchange(open) != open)
			cb::log::info("phone: T goes to %s", open ? "Cyberpunk (a call or a message waits)" : "Minecraft again");
	}

	// Cyberblock_Log(msg: String) -> Void. Phase 3: Park.reds's result lines ("park: on (...)", "park: on refused (<why>)",
	// "park: off (...)") are also Minecraft's `parked` answer; section 1.8 names no native for it (world::parked_from_log).
	void Log(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::CString message;
		RED4ext::GetParameter(frame, &message);
		++frame->code;
		cb::log::info("script: %s", message.c_str());
		if (const auto parked = cb::world::parked_from_log(message.c_str()); parked && cb::link().linked())
		{
			cb::link().send(*parked);
			cb::log::info("parked: on %s ok %s%s%s", (*parked)["on"].get<bool>() ? "true" : "false",
						  (*parked)["ok"].get<bool>() ? "true" : "false", (*parked)["ok"].get<bool>() ? "" : " ",
						  (*parked)["reason"].get<std::string>().c_str());
		}
	}

	// ---- Phase 4 (megaplan 2026-10-04 sections 1.8, 1.9 and 1.11 with the Phase 0 amendment items 5-6, task 4-D1) ------
	// The pure parts are devices.cpp; `devact` comes in through devices::on_minecraft_message (main.cpp) as the event
	// n"devact": EventId the request id, EventTarget the device id, EventName the act; kept while not Active.

	/// The `devices` stream and what it was last reset for; touched only under g_devLock (never taken with g_lock held
	/// the other way round: SubmitDevices reads g_lock first and lets it go).
	std::mutex g_devLock;
	cb::devices::Stream g_devStream;
	int g_devSession = -1, g_devEpoch = -1, g_devLevelK = -1;

	/// Logs the first `limit` calls of a kind (a native's per-change line) and then stays quiet.
	bool log_budget(std::atomic<int> &count, int limit)
	{
		return count++ < limit;
	}

	void filter_device_hits(std::vector<cb::Vec3> &down, std::vector<cb::Vec3> &wall,
							std::pair<std::vector<int>, std::vector<int>> *codes)
	{
		const std::vector<cb::Aabb> boxes = cb::devices::boxes().current(cb::steady_seconds());
		if (boxes.empty())
			return;
		static bool logged = false;
		if (!std::exchange(logged, true))
			cb::log::info("probes: device-box filter active (%zu boxes)", boxes.size());
		cb::drop_hits_in_boxes(down, codes ? &codes->first : nullptr, boxes);
		cb::drop_hits_in_boxes(wall, codes ? &codes->second : nullptr, boxes);
	}

	// Cyberblock_SubmitDevices(ids: array<Uint64>, classes: array<CName>, kinds: array<CName>, pos: array<Vector4>,
	// yaws: array<Float>, sizes: array<Vector4>, states: array<Int32>, flags: array<Int32>) -> Void (Devices.reds, every
	// 0.5 s): the devices near V (amendment item 5: ids from CyberblockIds.Hash or the position+class fallback, the RTTI
	// class, the kind of section 1.11, the world position and yaw, sizes always zero, EDeviceStatus or EDoorStatus, the
	// 1.9 flags). Sent as `devices` (the nearest 64 within 48 m, deltas plus a full list every 5 s); every valid device
	// also gets a box for the probe filter.
	void SubmitDevices(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::DynArray<uint64_t> ids;
		RED4ext::DynArray<RED4ext::CName> classes;
		RED4ext::DynArray<RED4ext::CName> kinds;
		RED4ext::DynArray<RED4ext::Vector4> pos;
		RED4ext::DynArray<float> yaws;
		RED4ext::DynArray<RED4ext::Vector4> sizes;
		RED4ext::DynArray<int32_t> states;
		RED4ext::DynArray<int32_t> flags;
		RED4ext::GetParameter(frame, &ids);
		RED4ext::GetParameter(frame, &classes);
		RED4ext::GetParameter(frame, &kinds);
		RED4ext::GetParameter(frame, &pos);
		RED4ext::GetParameter(frame, &yaws);
		RED4ext::GetParameter(frame, &sizes);
		RED4ext::GetParameter(frame, &states);
		RED4ext::GetParameter(frame, &flags);
		++frame->code;
		if (!cb::natives::active)
			return;

		std::vector<std::string> classList, kindList;
		classList.reserve(classes.Size());
		for (const RED4ext::CName &name : classes)
			classList.push_back(name_text(name));
		kindList.reserve(kinds.Size());
		static std::atomic<int> unknownKinds{0};
		for (const RED4ext::CName &name : kinds)
		{
			const std::string_view kind = known_name(name, cb::devices::kKinds);
			if (kind.empty() && log_budget(unknownKinds, 10))
				cb::log::info("devices: kind %s is not a device_map kind; that device is left out",
							  name.IsNone() ? "(none)" : name_text(name).c_str());
			kindList.emplace_back(kind);
		}
		const auto valid = cb::devices::validate(to_vector<uint64_t>(ids), classList, kindList, to_vec3(pos),
												 to_vector<double>(yaws), to_vec3(sizes), to_vector<int32_t>(states),
												 to_vector<int32_t>(flags));
		if (!valid)
		{
			static bool warned = false;
			if (!std::exchange(warned, true))
				cb::log::warn("Cyberblock_SubmitDevices: arrays differ in length (ids %u, classes %u, kinds %u, pos %u, "
							  "yaws %u, sizes %u, states %u, flags %u); dropping the list (logged once)",
							  ids.Size(), classes.Size(), kinds.Size(), pos.Size(), yaws.Size(), sizes.Size(), states.Size(),
							  flags.Size());
			return;
		}
		if (valid->dropped > 0)
		{
			static bool warned = false;
			if (!std::exchange(warned, true))
				cb::log::warn("Cyberblock_SubmitDevices: %zu invalid entries left out (first: %s; logged once)", valid->dropped,
							  valid->reason.c_str());
		}
		const double now = cb::steady_seconds();
		cb::devices::boxes().set(valid->devices, now);

		std::optional<cb::Vec3> center;
		{
			std::lock_guard<std::mutex> lock(g_lock);
			center = g_mashup.lastCenter;
		}
		if (!center)
			center = player_feet();
		const std::vector<cb::devices::Device> nearby = cb::devices::nearest(valid->devices, center);
		if (!cb::link().linked())
			return;

		std::lock_guard<std::mutex> lock(g_devLock);
		// a Minecraft that (re)connected, a new server session or a new ground alignment needs the whole list again
		const int session = cb::link().session(), epoch = cb::world::ground_epoch(), levelK = cb::world::level_k();
		if (std::exchange(g_devSession, session) != session || std::exchange(g_devEpoch, epoch) != epoch ||
			std::exchange(g_devLevelK, levelK) != levelK)
			g_devStream.reset();
		const auto message = g_devStream.submit(nearby, cb::world::level_offset(levelK), now);
		if (!message)
			return;
		cb::link().send(*message);
		static bool first = true;
		if (std::exchange(first, false))
			cb::log::info("devices: path scripts (Cyberblock_SubmitDevices: %zu valid, %zu sent of the nearest within 48 m%s; "
						  "deltas plus a full list every 5 s)",
						  valid->devices.size(), (*message)["list"].size(), center ? "" : ", V's position unknown");
	}

	// Cyberblock_DeviceState(id: Uint64, state: Int32, flags: Int32, by: CName) -> Void (Devices.reds): a device changed;
	// `by` is n"host" (V executed the action), n"npc" or n"script" (no executor). Sent as `devstate`, with by "guest" when
	// a guest's `devact` for this device came within 2 s (amendment item 5).
	void DeviceState(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		uint64_t id = 0;
		int32_t state = 0, flags = 0;
		RED4ext::CName by;
		RED4ext::GetParameter(frame, &id);
		RED4ext::GetParameter(frame, &state);
		RED4ext::GetParameter(frame, &flags);
		RED4ext::GetParameter(frame, &by);
		++frame->code;
		if (!cb::natives::active || !cb::link().linked())
			return;

		const std::string_view scripts = known_name(by, cb::devices::kBy);
		const bool guest = cb::devices::guest_window().recent(id, cb::steady_seconds());
		const std::string_view sent = cb::devices::attribute(scripts, guest);
		static bool warned = false;
		const auto message = cb::devices::make_devstate(id, state, flags, sent);
		send_or_warn(message, warned, "Cyberblock_DeviceState",
					 "an id of 0, a state outside -2..1, flags outside 0..31 or a by that is not npc, host, guest or script");
		if (!message)
			return;
		{
			std::lock_guard<std::mutex> lock(g_devLock);
			g_devStream.note_state(id, state, flags);
		}
		static std::atomic<int> logged{0};
		if (log_budget(logged, 50))
			cb::log::info("devstate: %llu st %d fl %d by %s%s", static_cast<unsigned long long>(id), state, flags,
						  std::string(sent).c_str(), guest && scripts != "guest" ? " (a guest's devact within 2 s)" : "");
	}

	// Cyberblock_DeviceResult(id: Int32, ok: Bool, reason: String) -> Void (Devices.reds): the answer to event n"devact"
	// `id`. Sent as `devresult`; a failed request is no longer the guest's window (no change follows it).
	void DeviceResult(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		int32_t id = 0;
		bool ok = false;
		RED4ext::CString reason;
		RED4ext::GetParameter(frame, &id);
		RED4ext::GetParameter(frame, &ok);
		RED4ext::GetParameter(frame, &reason);
		++frame->code;

		if (!ok)
			cb::devices::guest_window().forget(id);
		static bool warned = false;
		const auto message = cb::devices::make_devresult(id, ok, reason.c_str());
		send_or_warn(message, warned, "Cyberblock_DeviceResult", "the request id is negative");
		static std::atomic<int> logged{0};
		if (message && log_budget(logged, 50))
			cb::log::info("devresult: %d %s%s%s", id, ok ? "ok" : "failed", ok ? "" : ": ",
						  (*message)["reason"].get<std::string>().c_str());
	}

	void register_device_natives(RED4ext::CRTTISystem *rtti)
	{
		using cb::natives::add;
		add(rtti, "Cyberblock_SubmitDevices", &SubmitDevices, nullptr,
			{{"array:Uint64", "ids"}, {"array:CName", "classes"}, {"array:CName", "kinds"}, {"array:Vector4", "pos"},
			 {"array:Float", "yaws"}, {"array:Vector4", "sizes"}, {"array:Int32", "states"}, {"array:Int32", "flags"}});
		add(rtti, "Cyberblock_DeviceState", &DeviceState, nullptr,
			{{"Uint64", "id"}, {"Int32", "state"}, {"Int32", "flags"}, {"CName", "by"}});
		add(rtti, "Cyberblock_DeviceResult", &DeviceResult, nullptr, {{"Int32", "id"}, {"Bool", "ok"}, {"String", "reason"}});
	}
	// ---- end of Phase 4 ----------------------------------------------------------------------------------------------
}

namespace cb::natives
{
	void register_types()
	{
	}

	void on_link_dropped()
	{
		clear_seat();
		phonePrompt = false;
	}

	void on_session_message()
	{
		std::lock_guard<std::mutex> lock(g_lock);
		if (int seen = g_mashup.groundEpoch; !world::take_ground_restart(seen))
			return;
		log::info("ground: a new server session, Minecraft gets the ground again");
		send_state();
		restart_ground();
	}

	void post_register_types()
	{
		RED4ext::CRTTISystem *rtti = RED4ext::CRTTISystem::Get();
		add(rtti, "Cyberblock_Version", &Version, "String");
		add(rtti, "Cyberblock_IsLinked", &IsLinked, "Bool");
		add(rtti, "Cyberblock_SecondsSinceFrame", &SecondsSinceFrame, "Float");
		add(rtti, "Cyberblock_ScreenOpen", &ScreenOpen, "Bool");
		add(rtti, "Cyberblock_ConsumeToggle", &ConsumeToggle, "Bool");
		add(rtti, "Cyberblock_SetActive", &SetActive, nullptr, {{"Bool", "active"}, {"Bool", "inVehicle"}});
		add(rtti, "Cyberblock_SubmitProbes", &SubmitProbes, nullptr,
			{{"Vector4", "center"}, {"array:Vector4", "downHits"}, {"array:Vector4", "wallHits"}, {"Float", "radius"}});
		add(rtti, "Cyberblock_SubmitProbeMaterials", &SubmitProbeMaterials, nullptr,
			{{"array:CName", "down"}, {"array:CName", "wall"}});
		add(rtti, "Cyberblock_SetEnv", &SetEnv, nullptr, {{"Int32", "hour"}, {"Int32", "minute"}, {"CName", "weather"}});
		add(rtti, "Cyberblock_TeleportDone", &TeleportDone, nullptr, {{"Int32", "id"}, {"Bool", "ok"}, {"String", "reason"}});
		log::info("materials: name rule of %zu rules (tools/cbmath.py MATERIAL_ORDER)", material_order().size());
		add(rtti, "Cyberblock_SetAim", &SetAim, nullptr, {{"Float", "distance"}});
		add(rtti, "Cyberblock_Damage", &Damage, nullptr,
			{{"Float", "frac"}, {"CName", "kind"}, {"Vector4", "from"}, {"Bool", "hasFrom"}});
		add(rtti, "Cyberblock_SubmitNpcs", &SubmitNpcs, nullptr,
			{{"array:Uint64", "ids"}, {"array:Vector4", "feet"}, {"array:Float", "yaws"}, {"array:Bool", "vehicles"},
			 {"array:Bool", "hostile"}, {"array:Bool", "dead"}});
		// Phase 5 (task 5-D1)
		add(rtti, "Cyberblock_SubmitNpcs2", &SubmitNpcs2, nullptr,
			{{"array:Uint64", "ids"}, {"array:Vector4", "feet"}, {"array:Float", "yaws"}, {"array:Bool", "vehicles"},
			 {"array:Bool", "hostile"}, {"array:Bool", "dead"}, {"array:CName", "roles"}, {"array:CName", "factions"},
			 {"array:Int32", "genders"}, {"array:CName", "archetypes"}, {"array:Int32", "anims"}, {"array:CName", "vclass"},
			 {"array:Int32", "colours"}, {"array:Float", "hp"}});
		add(rtti, "Cyberblock_NextEvent", &NextEvent, "CName");
		add(rtti, "Cyberblock_EventPos", &EventPos, "Vector4");
		add(rtti, "Cyberblock_EventTarget", &EventTarget, "Uint64");
		add(rtti, "Cyberblock_EventAmount", &EventAmount, "Float");
		add(rtti, "Cyberblock_EventVector", &EventVector, "Vector4");
		add(rtti, "Cyberblock_EventFlag", &EventFlag, "Bool");
		add(rtti, "Cyberblock_EventName", &EventName, "CName");
		add(rtti, "Cyberblock_EventExtra", &EventExtra, "Float");
		add(rtti, "Cyberblock_EventId", &EventId, "Int32");
		add(rtti, "Cyberblock_EventText", &EventText, "String");
		add(rtti, "Cyberblock_MobCount", &MobCount, "Int32");
		add(rtti, "Cyberblock_MobId", &MobId, "Int32", {{"Int32", "i"}});
		add(rtti, "Cyberblock_MobKind", &MobKind, "CName", {{"Int32", "i"}});
		add(rtti, "Cyberblock_MobPos", &MobPos, "Vector4", {{"Int32", "i"}});
		add(rtti, "Cyberblock_MobYaw", &MobYaw, "Float", {{"Int32", "i"}});
		add(rtti, "Cyberblock_MobSize", &MobSize, "Vector4", {{"Int32", "i"}});
		add(rtti, "Cyberblock_FlightActive", &FlightActive, "Bool");
		add(rtti, "Cyberblock_FlightSeq", &FlightSeq, "Int32");
		add(rtti, "Cyberblock_FlightTimedOut", &FlightTimedOut, "Bool");
		add(rtti, "Cyberblock_SetFlightAllowed", &SetFlightAllowed, nullptr, {{"Bool", "allowed"}});
		add(rtti, "Cyberblock_FlightDriving", &FlightDriving, "Bool");
		add(rtti, "Cyberblock_SecondsSinceFlight", &SecondsSinceFlight, "Float");
		add(rtti, "Cyberblock_FlightPos", &FlightPos, "Vector4");
		add(rtti, "Cyberblock_FlightVelocity", &FlightVelocity, "Vector4");
		add(rtti, "Cyberblock_FlightYaw", &FlightYaw, "Float");
		add(rtti, "Cyberblock_FlightPitch", &FlightPitch, "Float");
		add(rtti, "Cyberblock_ProjCount", &ProjCount, "Int32");
		add(rtti, "Cyberblock_ProjPos", &ProjPos, "Vector4", {{"Int32", "i"}});
		add(rtti, "Cyberblock_ProjVelocity", &ProjVelocity, "Vector4", {{"Int32", "i"}});
		add(rtti, "Cyberblock_ProjGravity", &ProjGravity, "Float", {{"Int32", "i"}});
		add(rtti, "Cyberblock_MobDamage", &MobDamage, nullptr,
			{{"Int32", "id"}, {"Float", "frac"}, {"Uint64", "by"}, {"Bool", "hasBy"}});
		add(rtti, "Cyberblock_SubmitProjectileHits", &SubmitProjectileHits, nullptr, {{"array:Vector4", "hits"}});
		add(rtti, "Cyberblock_Unlead", &Unlead, nullptr, {{"Uint64", "target"}});
		add(rtti, "Cyberblock_ScanResult", &ScanResult, nullptr,
			{{"Int32", "id"}, {"Bool", "ok"}, {"Uint64", "target"}, {"String", "name"}, {"String", "faction"},
			 {"CName", "threat"}, {"Float", "hp"}, {"Bool", "device"}, {"Vector4", "pos"}, {"Vector4", "size"}});
		add(rtti, "Cyberblock_HackResult", &HackResult, nullptr,
			{{"Int32", "id"}, {"Bool", "ok"}, {"String", "reason"}, {"Float", "upload"}, {"CName", "kind"}});
		add(rtti, "Cyberblock_HackSpread", &HackSpread, nullptr, {{"Int32", "id"}, {"Uint64", "target"}, {"CName", "why"}});
		add(rtti, "Cyberblock_Killed", &Killed, nullptr, {{"Uint64", "target"}, {"Bool", "vehicle"}, {"Bool", "hostile"}});
		add(rtti, "Cyberblock_SetStance", &SetStance, nullptr, {{"Bool", "crouched"}, {"Bool", "inAir"}, {"Bool", "held"}});
		add(rtti, "Cyberblock_GameMode", &GameModeNative, "CName");
		add(rtti, "Cyberblock_SetMenu", &SetMenu, nullptr, {{"Bool", "open"}});
		add(rtti, "Cyberblock_SetChoices", &SetChoices, nullptr, {{"Bool", "open"}});
		add(rtti, "Cyberblock_SetLookCapture", &SetLookCapture, nullptr, {{"Bool", "on"}});
		add(rtti, "Cyberblock_SetSeat", &SetSeat, nullptr, {{"Bool", "seated"}, {"Bool", "tpp"}, {"Vector4", "hipsLocal"}});
		add(rtti, "Cyberblock_SetSeatForward", &SetSeatForward, nullptr, {{"Vector4", "forward"}});
		add(rtti, "Cyberblock_SetViewAllowed", &SetViewAllowed, nullptr, {{"Bool", "allowed"}});
		add(rtti, "Cyberblock_ViewEye", &ViewEye, "Vector4");
		add(rtti, "Cyberblock_SetSlowMoFx", &SetSlowMoFx, nullptr, {{"CName", "id"}, {"Float", "seconds"}});
		add(rtti, "Cyberblock_SetPhonePrompt", &SetPhonePrompt, nullptr, {{"Bool", "open"}});
		add(rtti, "Cyberblock_Log", &Log, nullptr, {{"String", "msg"}});
		register_device_natives(rtti); // Phase 4 (task 4-D1)
		log::info("redscript natives registered");
		ui::register_natives(rtti); // the UI bridge's natives (ui_game.cpp, re-test 3 plan section 1.3)
		survey_game::register_natives(rtti); // the Atlas survey's natives (survey_game.cpp)
	}
}
