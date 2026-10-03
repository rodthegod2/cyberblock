// redscript natives, registered as RTTI global functions like RED4ext.SDK's examples/native_globals_redscript.
#include "natives.hpp"
#include "compositor.h"
#include "link.hpp"
#include "log.hpp"
#include "probes.hpp"
#include "world.hpp"
#include <RED4ext/RED4ext.hpp>
#include <RED4ext/Scripting/Natives/Generated/Vector4.hpp>
#include <atomic>
#include <cmath>
#include <cstdint>
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
		cb::ProbeSet probes;
		std::optional<cb::Vec3> lastCenter;
	};
	std::mutex g_lock;
	Mashup g_mashup;

	void send_state()
	{
		cb::link().send({{"t", "state"}, {"active", g_mashup.active}, {"vehicle", g_mashup.vehicle}});
		cb::link().send({{"t", "hud"}, {"hidden", g_mashup.vehicle}});
	}

	/// Minecraft drops all ground and is sent it again from the next probes.
	void restart_ground()
	{
		cb::link().send({{"t", "clear"}});
		g_mashup.probes.clear();
		g_mashup.lastCenter.reset();
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

	/// The `dmg` kind `name` spells ("" when it is none of cb::world::kDamageKinds, which make_dmg sends as "other").
	/// Compares hashes, so no game name pool is needed.
	std::string_view damage_kind(RED4ext::CName name)
	{
		for (const char *kind : cb::world::kDamageKinds)
			if (RED4ext::CName(kind) == name)
				return kind;
		return {};
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
		cb::world::events().set_active(active);
		compositor::set_active(active);
		if (changed)
		{
			cb::log::info("mashup %s%s", active ? "on" : "off", inVehicle ? " (in a vehicle)" : "");
			send_state();
		}
		if (activeChanged)
			restart_ground();
	}

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
		if (!g_mashup.active || !cb::link().linked())
			return;
		if (g_mashup.session != cb::link().session())
		{
			// Minecraft (re)connected since we last told it anything
			g_mashup.session = cb::link().session();
			send_state();
			restart_ground();
		}
		const cb::Vec3 feet{center.X, center.Y, center.Z};
		if (g_mashup.lastCenter && std::hypot(feet.x - g_mashup.lastCenter->x, feet.y - g_mashup.lastCenter->y,
											  feet.z - g_mashup.lastCenter->z) > kTeleportMetres)
			restart_ground();
		g_mashup.lastCenter = feet;

		const cb::Delta delta = g_mashup.probes.submit(feet, to_vec3(downHits), to_vec3(wallHits), radius);
		if (!delta.remove.empty())
			cb::link().send({{"t", "unground"}, {"c", delta.remove}});
		if (!delta.add.empty())
			cb::link().send({{"t", "ground"}, {"c", delta.add}});
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
		const auto message = cb::world::make_dmg(frac, damage_kind(kind), {from.X, from.Y, from.Z}, hasFrom);
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
	// hostile: array<Bool>) -> Void. The full list of nearby NPCs and vehicles (Minecraft despawns the proxy of any id
	// that is missing). Feet and yaws are Cyberpunk values; a yaw is in degrees as Entity.GetWorldYaw reports it.
	void SubmitNpcs(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::DynArray<uint64_t> ids;
		RED4ext::DynArray<RED4ext::Vector4> feet;
		RED4ext::DynArray<float> yaws;
		RED4ext::DynArray<bool> vehicles;
		RED4ext::DynArray<bool> hostile;
		RED4ext::GetParameter(frame, &ids);
		RED4ext::GetParameter(frame, &feet);
		RED4ext::GetParameter(frame, &yaws);
		RED4ext::GetParameter(frame, &vehicles);
		RED4ext::GetParameter(frame, &hostile);
		++frame->code;

		if (!cb::natives::active)
			return;
		const std::vector<uint64_t> idList = to_vector<uint64_t>(ids);
		const std::vector<cb::Vec3> feetList = to_vec3(feet);
		const std::vector<double> yawList = to_vector<double>(yaws);
		const std::vector<uint8_t> vehicleList = to_vector<uint8_t>(vehicles);
		const std::vector<uint8_t> hostileList = to_vector<uint8_t>(hostile);
		const auto message = cb::world::make_npcs(idList, feetList, yawList, vehicleList, hostileList);
		if (message)
			cb::link().send(*message);
		else
		{
			static bool warned = false;
			if (!std::exchange(warned, true))
				cb::log::warn("Cyberblock_SubmitNpcs: arrays differ in length (ids %zu, feet %zu, yaws %zu, vehicles %zu, "
							  "hostile %zu); dropping the list (logged once)",
							  idList.size(), feetList.size(), yawList.size(), vehicleList.size(), hostileList.size());
		}
	}

	// Cyberblock_NextEvent() -> CName: pops the oldest Minecraft event and makes it current: n"explosion", n"hit",
	// n"ignite", n"block_add" or n"block_remove"; n"" (the empty CName, hash 0) when there is none.
	void NextEvent(IScriptable *, CStackFrame *frame, RED4ext::CName *out, int64_t)
	{
		++frame->code;
		const cb::world::EventKind kind = cb::world::events().next();
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
	// seconds.
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

	// Cyberblock_EventFlag() -> Bool: whether the explosion burns.
	void EventFlag(IScriptable *, CStackFrame *frame, bool *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = cb::world::events().current().flag;
	}

	// Cyberblock_Log(msg: String) -> Void
	void Log(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::CString message;
		RED4ext::GetParameter(frame, &message);
		++frame->code;
		cb::log::info("script: %s", message.c_str());
	}

	using Param = std::pair<const char *, const char *>; // RTTI type, name

	template<typename T>
	void add(RED4ext::CRTTISystem *rtti, const char *name, RED4ext::ScriptingFunction_t<T> fn, const char *returnType,
			 std::initializer_list<Param> params = {})
	{
		RED4ext::CGlobalFunction *func = RED4ext::CGlobalFunction::Create(name, name, fn);
		func->flags = {};
		func->flags.isNative = true;
		func->flags.isStatic = true;
		// an unknown RTTI type name leaves the function without that parameter: say so, the script call would misbehave
		for (const auto &[type, paramName] : params)
			if (!func->AddParam(type, paramName))
				cb::log::error("native %s: unknown parameter type %s (%s)", name, type, paramName);
		if (returnType != nullptr && !func->SetReturnType(returnType))
			cb::log::error("native %s: unknown return type %s", name, returnType);
		rtti->RegisterFunction(func);
	}
}

namespace cb::natives
{
	void register_types()
	{
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
		add(rtti, "Cyberblock_SetAim", &SetAim, nullptr, {{"Float", "distance"}});
		add(rtti, "Cyberblock_Damage", &Damage, nullptr,
			{{"Float", "frac"}, {"CName", "kind"}, {"Vector4", "from"}, {"Bool", "hasFrom"}});
		add(rtti, "Cyberblock_SubmitNpcs", &SubmitNpcs, nullptr,
			{{"array:Uint64", "ids"}, {"array:Vector4", "feet"}, {"array:Float", "yaws"}, {"array:Bool", "vehicles"},
			 {"array:Bool", "hostile"}});
		add(rtti, "Cyberblock_NextEvent", &NextEvent, "CName");
		add(rtti, "Cyberblock_EventPos", &EventPos, "Vector4");
		add(rtti, "Cyberblock_EventTarget", &EventTarget, "Uint64");
		add(rtti, "Cyberblock_EventAmount", &EventAmount, "Float");
		add(rtti, "Cyberblock_EventVector", &EventVector, "Vector4");
		add(rtti, "Cyberblock_EventFlag", &EventFlag, "Bool");
		add(rtti, "Cyberblock_Log", &Log, nullptr, {{"String", "msg"}});
		log::info("redscript natives registered");
	}
}
