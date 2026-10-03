// Registers a redscript native as an RTTI global function, like RED4ext.SDK's examples/native_globals_redscript (re-test 3
// plan section 1.3: shared by natives.cpp and the UI bridge's ui_game.cpp). The body moved verbatim from natives.cpp.
#pragma once
#include <RED4ext/RED4ext.hpp>
#include <initializer_list>
#include <utility>
#include "log.hpp"

namespace cb::natives
{
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
