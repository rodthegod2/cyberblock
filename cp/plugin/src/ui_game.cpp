// The game-facing half of the Cyberpunk UI bridge (ui.hpp; plan 2026-10-03-retest3-polish sections 1.2, 1.3, 1.14 and
// task U2): the package U redscript natives (declared in cp/scripts/Cyberblock/UiNatives.reds), registered like the
// others through cb::natives::add, and the pump that calls the script function CyberblockUi.Pump (Ui.reds) on the
// game thread.
//
// RED4ext.SDK APIs (third_party/RED4ext.SDK): CRTTISystem::GetClass/GetClassByScriptName, CClass::staticFuncs/funcs/
// parent and CBaseFunction::fullName/shortName (RTTITypes.hpp, Scripting/Functions.hpp; CClass::GetFunction compares
// shortName only, RTTITypes-inl.hpp, so the pump walks the functions itself), ExecuteFunction(void *instance,
// CBaseFunction *, void *out) (Scripting/Utils-inl.hpp; a static script function takes no instance), GetParameter,
// CName::ToString, DynArray, CString.
#include "ui.hpp"
#include "link.hpp"
#include "log.hpp"
#include "native_reg.hpp"
#include "natives.hpp"
#include "world.hpp"
#include <RED4ext/RED4ext.hpp>
#include <RED4ext/Scripting/Natives/Generated/Vector4.hpp>
#include <RED4ext/Scripting/Utils.hpp>
#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

using RED4ext::CStackFrame;
using RED4ext::IScriptable;
using nlohmann::json;

namespace
{
	/// The send gates of each message type; touched only under g_lock.
	struct Gates
	{
		cb::ui::Gate stats, quest, pins, prompt, choices, sub, call, notify, pass;

		void reset()
		{
			for (cb::ui::Gate *gate : {&stats, &quest, &pins, &prompt, &choices, &sub, &call, &notify, &pass})
				gate->reset();
		}
	};

	std::mutex g_lock;
	Gates g_gates;
	int g_session = -1;
	cb::ui::ReplyBuilder g_reply;
	/// The action Cyberblock_NextUiAction made current (game thread).
	cb::ui::UiAction g_current;
	std::atomic<uint64_t> g_invalidUiacts{0};
	/// When the DLL last ran CyberblockUi.Pump (steady seconds); the scripts pump from their own tick while it is stale.
	std::atomic<double> g_lastPump{-1e300};

	cb::ui::UiActions &actions()
	{
		static cb::ui::UiActions *instance = new cb::ui::UiActions; // never destroyed: the link thread may still push
		return *instance;
	}

	double now_seconds()
	{
		return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
	}

	double level_offset()
	{
		return cb::world::level_offset(cb::world::level_k());
	}

	/// A Minecraft that (re)connected has seen none of our messages: every gate starts over. Call with g_lock held.
	void sync_session()
	{
		const int session = cb::link().session();
		if (session != g_session)
		{
			g_session = session;
			g_gates.reset();
		}
	}

	/// On and linked: HUD data may go to Minecraft.
	bool can_send()
	{
		return cb::natives::active && cb::link().linked();
	}

	/// Sends `message` through `gate` (see ui.hpp Gate::admit); `urgent` is the part sent at once on a change.
	void send_gated(cb::ui::Gate &gate, const json &message, const std::string &urgent, double minInterval)
	{
		std::lock_guard<std::mutex> lock(g_lock);
		sync_session();
		if (gate.admit(message.dump(), urgent, now_seconds(), minInterval))
			cb::link().send(message);
	}

	void send_changed(cb::ui::Gate &gate, const json &message)
	{
		const std::string text = message.dump();
		std::lock_guard<std::mutex> lock(g_lock);
		sync_session();
		if (gate.admit(text, text, now_seconds(), 0.0))
			cb::link().send(message);
	}

	/// A script CName as text ("" for n"", "?" for a name the pool does not know).
	std::string name_text(RED4ext::CName name)
	{
		if (name.IsNone())
			return {};
		const char *text = name.ToString();
		return text ? text : "?";
	}

	void warn_once(bool &warned, const char *native, const char *why)
	{
		if (!std::exchange(warned, true))
			cb::log::warn("%s: %s; dropping it (logged once)", native, why);
	}

	// ---- screens --------------------------------------------------------------------------------------------------------

	// Cyberblock_OpenScreen(name: CName) -> Bool: true when Minecraft will open that screen (linked, On, no Minecraft
	// screen open); then screen.open is sent.
	void OpenScreen(IScriptable *, CStackFrame *frame, bool *out, int64_t)
	{
		RED4ext::CName name;
		RED4ext::GetParameter(frame, &name);
		++frame->code;
		bool opened = false;
		if (can_send() && !cb::link().screen_open())
		{
			if (const auto message = cb::ui::make_screen_open(name_text(name)))
			{
				cb::link().send(*message);
				opened = true;
			}
			else
			{
				static bool warned = false;
				warn_once(warned, "Cyberblock_OpenScreen", "unknown screen name");
			}
		}
		if (out)
			*out = opened;
	}

	// Cyberblock_PassMenu(name: CName) -> Void: a pass-through menu opened (n"" ended); sent on change.
	void PassMenu(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::CName name;
		RED4ext::GetParameter(frame, &name);
		++frame->code;
		if (!can_send())
			return;
		if (const auto message = cb::ui::make_screen_pass(name_text(name)))
			send_changed(g_gates.pass, *message);
		else
		{
			static bool warned = false;
			warn_once(warned, "Cyberblock_PassMenu", "unknown pass-through name");
		}
	}

	// ---- HUD data -------------------------------------------------------------------------------------------------------

	// Cyberblock_HudStats(money, wanted, district, hour, minute, speed): money, wanted and district go at once, clock and
	// speed at most once a second.
	void HudStats(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		int32_t money = 0, wanted = 0, hour = 0, minute = 0;
		RED4ext::CString district;
		float speed = -1.0f;
		RED4ext::GetParameter(frame, &money);
		RED4ext::GetParameter(frame, &wanted);
		RED4ext::GetParameter(frame, &district);
		RED4ext::GetParameter(frame, &hour);
		RED4ext::GetParameter(frame, &minute);
		RED4ext::GetParameter(frame, &speed);
		++frame->code;
		if (!can_send())
			return;
		const json message = cb::ui::make_stats(money, wanted, district.c_str(), hour, minute, speed);
		const std::string urgent = json{message["money"], message["wanted"], message["district"]}.dump();
		send_gated(g_gates.stats, message, urgent, 1.0);
	}

	// Cyberblock_HudQuest(title, kind, texts, cur, tot, optional, tracked, hasWp, wp): any change but the waypoint goes at
	// once, a moving waypoint at most 5 times a second.
	void HudQuest(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::CString title;
		RED4ext::CName kind;
		RED4ext::DynArray<RED4ext::CString> texts;
		RED4ext::DynArray<int32_t> cur;
		RED4ext::DynArray<int32_t> tot;
		RED4ext::DynArray<bool> optional;
		int32_t tracked = -1;
		bool hasWp = false;
		RED4ext::Vector4 wp;
		RED4ext::GetParameter(frame, &title);
		RED4ext::GetParameter(frame, &kind);
		RED4ext::GetParameter(frame, &texts);
		RED4ext::GetParameter(frame, &cur);
		RED4ext::GetParameter(frame, &tot);
		RED4ext::GetParameter(frame, &optional);
		RED4ext::GetParameter(frame, &tracked);
		RED4ext::GetParameter(frame, &hasWp);
		RED4ext::GetParameter(frame, &wp);
		++frame->code;
		if (!can_send())
			return;
		const uint32_t n = texts.Size();
		if (cur.Size() != n || tot.Size() != n || optional.Size() != n)
		{
			static bool warned = false;
			warn_once(warned, "Cyberblock_HudQuest", "the objective arrays differ in length");
			return;
		}
		std::vector<cb::ui::Objective> objectives;
		for (uint32_t i = 0; i < n; ++i)
			objectives.push_back({texts[i].c_str(), cur[i], tot[i], optional[i], int32_t(i) == tracked});
		const std::optional<cb::Vec3> waypoint = hasWp ? std::optional<cb::Vec3>(cb::Vec3{wp.X, wp.Y, wp.Z}) : std::nullopt;
		json message = cb::ui::make_quest(title.c_str(), name_text(kind), objectives, waypoint, level_offset());
		json withoutWp = message;
		withoutWp.erase("wp");
		send_gated(g_gates.quest, message, withoutWp.dump() + (message["wp"].is_null() ? "0" : "1"), 0.2);
	}

	// Cyberblock_HudPins(pos, kinds, tracked): at most twice a second.
	void HudPins(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::DynArray<RED4ext::Vector4> pos;
		RED4ext::DynArray<RED4ext::CName> kinds;
		RED4ext::DynArray<bool> tracked;
		RED4ext::GetParameter(frame, &pos);
		RED4ext::GetParameter(frame, &kinds);
		RED4ext::GetParameter(frame, &tracked);
		++frame->code;
		if (!can_send())
			return;
		if (kinds.Size() != pos.Size() || tracked.Size() != pos.Size())
		{
			static bool warned = false;
			warn_once(warned, "Cyberblock_HudPins", "the arrays differ in length");
			return;
		}
		std::vector<cb::ui::Pin> pins;
		for (uint32_t i = 0; i < pos.Size(); ++i)
			pins.push_back({{pos[i].X, pos[i].Y, pos[i].Z}, name_text(kinds[i]), tracked[i]});
		send_gated(g_gates.pins, cb::ui::make_pins(pins, level_offset()), "", 0.5);
	}

	// Cyberblock_HudPrompt(title, texts, keys, holds, inactive): on change; an empty list clears the prompt.
	void HudPrompt(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::CString title;
		RED4ext::DynArray<RED4ext::CString> texts;
		RED4ext::DynArray<RED4ext::CName> keys;
		RED4ext::DynArray<bool> holds;
		RED4ext::DynArray<bool> inactive;
		RED4ext::GetParameter(frame, &title);
		RED4ext::GetParameter(frame, &texts);
		RED4ext::GetParameter(frame, &keys);
		RED4ext::GetParameter(frame, &holds);
		RED4ext::GetParameter(frame, &inactive);
		++frame->code;
		if (!can_send())
			return;
		const uint32_t n = texts.Size();
		if (keys.Size() != n || holds.Size() != n || inactive.Size() != n)
		{
			static bool warned = false;
			warn_once(warned, "Cyberblock_HudPrompt", "the arrays differ in length");
			return;
		}
		std::vector<cb::ui::PromptEntry> entries;
		for (uint32_t i = 0; i < n; ++i)
			entries.push_back({texts[i].c_str(), name_text(keys[i]), holds[i], inactive[i]});
		send_changed(g_gates.prompt, cb::ui::make_prompt(title.c_str(), entries));
	}

	// Cyberblock_HudChoices(hubIds, hubTitles, hubOf, texts, flags, activeHub, selected, secondsLeft): on change.
	void HudChoices(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::DynArray<int32_t> hubIds;
		RED4ext::DynArray<RED4ext::CString> hubTitles;
		RED4ext::DynArray<int32_t> hubOf;
		RED4ext::DynArray<RED4ext::CString> texts;
		RED4ext::DynArray<int32_t> flags;
		int32_t activeHub = -1, selected = -1;
		float secondsLeft = -1.0f;
		RED4ext::GetParameter(frame, &hubIds);
		RED4ext::GetParameter(frame, &hubTitles);
		RED4ext::GetParameter(frame, &hubOf);
		RED4ext::GetParameter(frame, &texts);
		RED4ext::GetParameter(frame, &flags);
		RED4ext::GetParameter(frame, &activeHub);
		RED4ext::GetParameter(frame, &selected);
		RED4ext::GetParameter(frame, &secondsLeft);
		++frame->code;
		if (!can_send())
			return;
		if (hubTitles.Size() != hubIds.Size() || hubOf.Size() != texts.Size() || flags.Size() != texts.Size())
		{
			static bool warned = false;
			warn_once(warned, "Cyberblock_HudChoices", "the arrays differ in length");
			return;
		}
		std::vector<cb::ui::ChoiceHub> hubs;
		for (uint32_t i = 0; i < hubIds.Size(); ++i)
			hubs.push_back({hubIds[i], hubTitles[i].c_str()});
		std::vector<cb::ui::ChoiceEntry> entries;
		for (uint32_t i = 0; i < texts.Size(); ++i)
			entries.push_back({hubOf[i], texts[i].c_str(), flags[i]});
		send_changed(g_gates.choices, cb::ui::make_choices(hubs, entries, activeHub, selected, secondsLeft));
	}

	// Cyberblock_Subtitle(show, key, speaker, text, seconds): each dialogue line shown or hidden.
	void Subtitle(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		bool show = false;
		RED4ext::CString key, speaker, text;
		float seconds = 0.0f;
		RED4ext::GetParameter(frame, &show);
		RED4ext::GetParameter(frame, &key);
		RED4ext::GetParameter(frame, &speaker);
		RED4ext::GetParameter(frame, &text);
		RED4ext::GetParameter(frame, &seconds);
		++frame->code;
		if (!can_send())
			return;
		send_changed(g_gates.sub, cb::ui::make_subtitle(show, key.c_str(), speaker.c_str(), text.c_str(), seconds));
	}

	// Cyberblock_PhoneCall(phase, contact, audio, rejectable): on change.
	void PhoneCall(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::CName phase;
		RED4ext::CString contact;
		bool audio = false, rejectable = false;
		RED4ext::GetParameter(frame, &phase);
		RED4ext::GetParameter(frame, &contact);
		RED4ext::GetParameter(frame, &audio);
		RED4ext::GetParameter(frame, &rejectable);
		++frame->code;
		if (!can_send())
			return;
		if (const auto message = cb::ui::make_call(name_text(phase), contact.c_str(), audio, rejectable))
			send_changed(g_gates.call, *message);
		else
		{
			static bool warned = false;
			warn_once(warned, "Cyberblock_PhoneCall", "unknown call phase");
		}
	}

	// Cyberblock_Notify(kind, title, text, seconds): sent while linked (also in the moment Cyberblock turns On); the same
	// notification again within a second is a duplicate (a subclass and its base both reporting it).
	void Notify(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::CName kind;
		RED4ext::CString title, text;
		float seconds = 0.0f;
		RED4ext::GetParameter(frame, &kind);
		RED4ext::GetParameter(frame, &title);
		RED4ext::GetParameter(frame, &text);
		RED4ext::GetParameter(frame, &seconds);
		++frame->code;
		if (!cb::link().linked())
			return;
		const json message = cb::ui::make_notify(name_text(kind), title.c_str(), text.c_str(), seconds);
		std::lock_guard<std::mutex> lock(g_lock);
		sync_session();
		if (g_gates.notify.admit_event(message.dump(), now_seconds(), 1.0))
			cb::link().send(message);
	}

	// ---- UI actions -----------------------------------------------------------------------------------------------------

	// Cyberblock_NextUiAction() -> CName: pops the next action and makes it current; n"" when none.
	void NextUiAction(IScriptable *, CStackFrame *frame, RED4ext::CName *out, int64_t)
	{
		++frame->code;
		const auto action = actions().pop(cb::natives::active);
		g_current = action ? *action : cb::ui::UiAction{};
		if (action)
			cb::log::info("ui: action %s", action->a.c_str());
		if (out)
			*out = RED4ext::CName(g_current.a.c_str());
	}

	void UiActionArg(IScriptable *, CStackFrame *frame, RED4ext::CString *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = RED4ext::CString(g_current.arg.c_str());
	}

	void UiActionHash(IScriptable *, CStackFrame *frame, uint32_t *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = g_current.hash;
	}

	void UiActionId(IScriptable *, CStackFrame *frame, int32_t *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = g_current.id;
	}

	// Cyberblock_UiPumpLive() -> Bool: the DLL runs CyberblockUi.Pump right now or ran it within the last 0.5 s. While it
	// is false the scripts' 10 Hz tick pumps instead, so Minecraft's screen requests are answered even when the DLL cannot
	// find the function, and Menus.reds does not pause Night City (that tick stands still while paused: no resume).
	void UiPumpLive(IScriptable *, CStackFrame *frame, bool *out, int64_t)
	{
		++frame->code;
		if (out)
			*out = now_seconds() - g_lastPump.load() < 0.5;
	}

	// ---- replies --------------------------------------------------------------------------------------------------------

	void ReplyBegin(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::CName kind;
		int32_t id = 0;
		RED4ext::GetParameter(frame, &kind);
		RED4ext::GetParameter(frame, &id);
		++frame->code;
		std::lock_guard<std::mutex> lock(g_lock);
		if (!g_reply.begin(name_text(kind), id))
		{
			static bool warned = false;
			warn_once(warned, "Cyberblock_ReplyBegin", "unknown reply kind");
		}
	}

	void ReplyGroup(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		uint32_t hash = 0;
		RED4ext::CString title;
		RED4ext::CName kind;
		bool tracked = false, done = false;
		RED4ext::GetParameter(frame, &hash);
		RED4ext::GetParameter(frame, &title);
		RED4ext::GetParameter(frame, &kind);
		RED4ext::GetParameter(frame, &tracked);
		RED4ext::GetParameter(frame, &done);
		++frame->code;
		std::lock_guard<std::mutex> lock(g_lock);
		g_reply.group(hash, title.c_str(), name_text(kind), tracked, done);
	}

	void ReplyLine(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		uint32_t hash = 0;
		RED4ext::CString text;
		int32_t state = 0;
		RED4ext::GetParameter(frame, &hash);
		RED4ext::GetParameter(frame, &text);
		RED4ext::GetParameter(frame, &state);
		++frame->code;
		std::lock_guard<std::mutex> lock(g_lock);
		g_reply.line(hash, text.c_str(), state);
	}

	void ReplyPin(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		RED4ext::Vector4 pos;
		RED4ext::CName kind;
		RED4ext::CString name;
		int32_t index = -1;
		RED4ext::CString district;
		RED4ext::GetParameter(frame, &pos);
		RED4ext::GetParameter(frame, &kind);
		RED4ext::GetParameter(frame, &name);
		RED4ext::GetParameter(frame, &index);
		RED4ext::GetParameter(frame, &district);
		++frame->code;
		std::lock_guard<std::mutex> lock(g_lock);
		g_reply.pin({pos.X, pos.Y, pos.Z}, name_text(kind), name.c_str(), index, level_offset(), district.c_str());
	}

	// Cyberblock_ReplyEnd(): sends the reply (while linked) and logs its size.
	void ReplyEnd(IScriptable *, CStackFrame *frame, void *, int64_t)
	{
		++frame->code;
		std::optional<cb::ui::ReplyBuilder::Result> result;
		{
			std::lock_guard<std::mutex> lock(g_lock);
			result = g_reply.end();
		}
		if (!result)
			return;
		cb::log::info("ui: reply %s %zu groups, %zu lines, %zu pins, %zu bytes%s", result->message["kind"].get<std::string>().c_str(),
					  result->groups, result->lines, result->pins, result->bytes,
					  result->dropped > 0 ? (", " + std::to_string(result->dropped) + " entries dropped").c_str() : "");
		if (cb::link().linked())
			cb::link().send(result->message);
	}

	// ---- pump -----------------------------------------------------------------------------------------------------------

	/// Forgets Minecraft's pause and queues a resume (the script unpauses only what it paused).
	void unpause(const char *reason)
	{
		if (actions().reset_pause())
			cb::log::info("ui: unpaused (%s)", reason);
	}

	/// The script function `wanted` of `type` or a parent: static and member functions, matched by
	/// cb::ui::script_function_matches (a script function's full name is mangled, "Pump;"; CClass::GetFunction compares
	/// shortName only). `matched` gets the full name that matched.
	RED4ext::CBaseFunction *find_script_function(RED4ext::CClass *type, const char *wanted, std::string &matched)
	{
		for (; type != nullptr; type = type->parent)
		{
			const auto pick = [&](auto &functions) -> RED4ext::CBaseFunction *
			{
				for (RED4ext::CBaseFunction *function : functions)
					if (function != nullptr &&
						cb::ui::script_function_matches(name_text(function->fullName), name_text(function->shortName), wanted))
					{
						matched = name_text(function->fullName);
						return function;
					}
				return nullptr;
			};
			if (RED4ext::CBaseFunction *function = pick(type->staticFuncs))
				return function;
			if (RED4ext::CBaseFunction *function = pick(type->funcs))
				return function;
		}
		return nullptr;
	}

	/// "full/short" of each function `type` declares (for the not-found log).
	std::string function_names(const RED4ext::CClass *type)
	{
		std::string names;
		const auto add = [&names](const auto &functions)
		{
			for (const RED4ext::CBaseFunction *function : functions)
				if (function != nullptr)
					names += (names.empty() ? "" : ", ") + name_text(function->fullName) + "/" + name_text(function->shortName);
		};
		add(type->staticFuncs);
		add(type->funcs);
		return names.empty() ? "none" : names;
	}

	/// CyberblockUi.Pump; null while it cannot be found. In game (2026-10-03) the first look, on the first Running frame
	/// at the main menu, found no CyberblockUi class yet and the old code never looked again, so no Night City screen
	/// ever got its reply (the Atlas stayed empty): it is now looked up again every 2 s until it is found. In game
	/// (2026-10-04) CClass::GetFunction("Pump") never found it although the scripts called CyberblockUi.Pump themselves:
	/// the compiled script blob names it "Pump;" (redscript's mangled full name), so the lookup now walks the class's
	/// static and member functions itself and accepts the plain or the mangled name.
	RED4ext::CBaseFunction *pump_function()
	{
		static RED4ext::CBaseFunction *function = nullptr;
		static double nextTry = 0.0;
		static bool warned = false;
		if (function != nullptr)
			return function;
		const double now = now_seconds();
		if (now < nextTry)
			return nullptr;
		nextTry = now + 2.0;
		RED4ext::CRTTISystem *rtti = RED4ext::CRTTISystem::Get();
		RED4ext::CClass *type = rtti->GetClass("CyberblockUi");
		if (type == nullptr)
			type = rtti->GetClassByScriptName("CyberblockUi");
		std::string matched;
		function = type != nullptr ? find_script_function(type, "Pump", matched) : nullptr;
		if (function)
			cb::log::info("ui: pump ready (CyberblockUi.%s, %s%s)", matched.c_str(), function->flags.isStatic ? "static" : "member",
						  warned ? ", found on a later look" : "");
		else if (!std::exchange(warned, true))
		{
			if (type == nullptr)
				cb::log::warn("ui: pump not found yet: no CyberblockUi class; looking again every 2 s");
			else
				cb::log::warn("ui: pump not found yet: CyberblockUi has no Pump (its functions, full/short: %s); looking again every 2 s",
							  function_names(type).c_str());
		}
		return function;
	}
}

namespace cb::ui
{
	void register_natives(RED4ext::CRTTISystem *rtti)
	{
		using cb::natives::add;
		add(rtti, "Cyberblock_OpenScreen", &OpenScreen, "Bool", {{"CName", "name"}});
		add(rtti, "Cyberblock_PassMenu", &PassMenu, nullptr, {{"CName", "name"}});
		add(rtti, "Cyberblock_HudStats", &HudStats, nullptr,
			{{"Int32", "money"}, {"Int32", "wanted"}, {"String", "district"}, {"Int32", "hour"}, {"Int32", "minute"}, {"Float", "speed"}});
		add(rtti, "Cyberblock_HudQuest", &HudQuest, nullptr,
			{{"String", "title"}, {"CName", "kind"}, {"array:String", "texts"}, {"array:Int32", "cur"}, {"array:Int32", "tot"},
			 {"array:Bool", "optional"}, {"Int32", "tracked"}, {"Bool", "hasWp"}, {"Vector4", "wp"}});
		add(rtti, "Cyberblock_HudPins", &HudPins, nullptr, {{"array:Vector4", "pos"}, {"array:CName", "kinds"}, {"array:Bool", "tracked"}});
		add(rtti, "Cyberblock_HudPrompt", &HudPrompt, nullptr,
			{{"String", "title"}, {"array:String", "texts"}, {"array:CName", "keys"}, {"array:Bool", "holds"}, {"array:Bool", "inactive"}});
		add(rtti, "Cyberblock_HudChoices", &HudChoices, nullptr,
			{{"array:Int32", "hubIds"}, {"array:String", "hubTitles"}, {"array:Int32", "hubOf"}, {"array:String", "texts"},
			 {"array:Int32", "flags"}, {"Int32", "activeHub"}, {"Int32", "selected"}, {"Float", "secondsLeft"}});
		add(rtti, "Cyberblock_Subtitle", &Subtitle, nullptr,
			{{"Bool", "show"}, {"String", "key"}, {"String", "speaker"}, {"String", "text"}, {"Float", "seconds"}});
		add(rtti, "Cyberblock_PhoneCall", &PhoneCall, nullptr,
			{{"CName", "phase"}, {"String", "contact"}, {"Bool", "audio"}, {"Bool", "rejectable"}});
		add(rtti, "Cyberblock_Notify", &Notify, nullptr, {{"CName", "kind"}, {"String", "title"}, {"String", "text"}, {"Float", "seconds"}});
		add(rtti, "Cyberblock_NextUiAction", &NextUiAction, "CName");
		add(rtti, "Cyberblock_UiActionArg", &UiActionArg, "String");
		add(rtti, "Cyberblock_UiActionHash", &UiActionHash, "Uint32");
		add(rtti, "Cyberblock_UiActionId", &UiActionId, "Int32");
		add(rtti, "Cyberblock_UiPumpLive", &UiPumpLive, "Bool");
		add(rtti, "Cyberblock_ReplyBegin", &ReplyBegin, nullptr, {{"CName", "kind"}, {"Int32", "id"}});
		add(rtti, "Cyberblock_ReplyGroup", &ReplyGroup, nullptr,
			{{"Uint32", "hash"}, {"String", "title"}, {"CName", "kind"}, {"Bool", "tracked"}, {"Bool", "done"}});
		add(rtti, "Cyberblock_ReplyLine", &ReplyLine, nullptr, {{"Uint32", "hash"}, {"String", "text"}, {"Int32", "state"}});
		add(rtti, "Cyberblock_ReplyPin", &ReplyPin, nullptr,
			{{"Vector4", "pos"}, {"CName", "kind"}, {"String", "name"}, {"Int32", "index"}, {"String", "district"}});
		add(rtti, "Cyberblock_ReplyEnd", &ReplyEnd, nullptr);
		cb::log::info("ui: natives registered");
	}

	void on_minecraft_message(const json &m)
	{
		if (!m.is_object())
			return;
		const auto t = m.find("t");
		if (t == m.end() || !t->is_string())
			return;
		const std::string &type = t->get_ref<const std::string &>();
		if (type == "hello")
			unpause("hello");
		else if (type == "uiact")
		{
			std::string why;
			if (const auto action = parse_uiact(m, &why))
				actions().push(*action, cb::natives::active);
			else if (g_invalidUiacts.fetch_add(1) < 10)
				cb::log::warn("ui: dropped an invalid message: %s", why.c_str());
		}
	}

	void pump()
	{
		static double last = 0.0;
		const double now = now_seconds();
		if (now - last < 1.0 / 30.0)
			return;
		last = now;

		const bool active = cb::natives::active;
		static bool wasActive = false;
		const bool wasOn = std::exchange(wasActive, active);
		if (wasOn && !active)
			unpause("off");
		if (!wasOn && active)
		{
			// Minecraft may have dropped the HUD while Cyberblock was Off: the script's next poll sends everything again
			std::lock_guard<std::mutex> lock(g_lock);
			g_gates.reset();
		}
		actions().set_pause(active && cb::link().linked() && cb::world::minecraft_pause());
		if (!active && actions().empty())
			return;
		if (RED4ext::CBaseFunction *function = pump_function())
		{
			// live while it runs: the `pause` it may handle checks Cyberblock_UiPumpLive (Menus.reds)
			const double previous = g_lastPump.exchange(now);
			if (!RED4ext::ExecuteFunction(static_cast<void *>(nullptr), function, nullptr))
			{
				g_lastPump = previous;
				static bool warned = false;
				if (!std::exchange(warned, true))
					cb::log::warn("ui: CyberblockUi.Pump did not run (ExecuteFunction failed); the scripts keep pumping from their tick");
			}
		}
	}

	void on_link_dropped()
	{
		unpause("link dropped");
	}
}
