// redscript natives, registered as RTTI global functions like RED4ext.SDK's examples/native_globals_redscript.
#include "natives.hpp"
#include "compositor.h"
#include "link.hpp"
#include "log.hpp"
#include "probes.hpp"
#include <RED4ext/RED4ext.hpp>
#include <RED4ext/Scripting/Natives/Generated/Vector4.hpp>
#include <cmath>
#include <initializer_list>
#include <mutex>
#include <optional>
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
		for (const auto &[type, paramName] : params)
			func->AddParam(type, paramName);
		if (returnType != nullptr)
			func->SetReturnType(returnType);
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
		add(rtti, "Cyberblock_Log", &Log, nullptr, {{"String", "msg"}});
		log::info("redscript natives registered");
	}
}
