// Import address table hooks: swaps the entry a module's import table holds for one function of one DLL, so that every
// call the module makes to it goes to our function first. Used for USER32!GetRawInputData in Cyberpunk2077.exe
// (input.cpp); calls made from other modules, including this plugin, are not affected.
#pragma once
#include <windows.h>

namespace cb::iat
{
	/// Points `module`'s import entry for `function` of `dll` (case-insensitive DLL name, import by name) at
	/// `replacement`, and stores the address it held in `*original` first, so `replacement` can already call through it.
	/// False, changing nothing, when the module has no such import or the entry cannot be written.
	bool patch(HMODULE module, const char *dll, const char *function, void *replacement, void **original);

	/// Puts `original` back in the entry for `function` of `dll`, but only while it still holds `replacement` (somebody
	/// else's hook on top of ours stays). True when the entry holds `original` afterwards.
	bool restore(HMODULE module, const char *dll, const char *function, void *replacement, void *original);
}
