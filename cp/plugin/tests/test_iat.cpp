// The import table hook (iat.cpp), run on this test program's own import table: the PE walk is the same one that patches
// USER32!GetRawInputData in Cyberpunk2077.exe (whose import of it was confirmed in the file, regular and not delay-loaded).
#include "iat.hpp"
#include "test.hpp"

namespace
{
	constexpr DWORD kFakeA = 424242;
	constexpr DWORD kFakeB = 737373;

	DWORD WINAPI fake_a()
	{
		return kFakeA;
	}

	DWORD WINAPI fake_b()
	{
		return kFakeB;
	}

	void *address(DWORD(WINAPI *fn)())
	{
		return reinterpret_cast<void *>(fn);
	}
}

TEST(a_patched_import_redirects_the_calls_the_module_makes_and_restore_undoes_it)
{
	const HMODULE self = GetModuleHandleW(nullptr);
	const DWORD real = GetCurrentProcessId();
	void *original = nullptr;
	CHECK(cb::iat::patch(self, "KERNEL32.dll", "GetCurrentProcessId", address(&fake_a), &original));
	const DWORD patched = GetCurrentProcessId();
	const DWORD viaOriginal = original != nullptr ? reinterpret_cast<DWORD(WINAPI *)()>(original)() : 0;
	const bool restored = cb::iat::restore(self, "kernel32.dll", "GetCurrentProcessId", address(&fake_a), original); // any case
	CHECK(patched == kFakeA);   // this module's call went to the replacement
	CHECK(viaOriginal == real); // and the saved address is the real function
	CHECK(restored && GetCurrentProcessId() == real);
}

TEST(restore_leaves_somebody_elses_hook_on_top_in_place)
{
	const HMODULE self = GetModuleHandleW(nullptr);
	const DWORD real = GetCurrentProcessId();
	void *original = nullptr;
	void *underneath = nullptr;
	CHECK(cb::iat::patch(self, "KERNEL32.dll", "GetCurrentProcessId", address(&fake_a), &original));
	CHECK(cb::iat::patch(self, "KERNEL32.dll", "GetCurrentProcessId", address(&fake_b), &underneath)); // a later hook, over ours
	CHECK(underneath == address(&fake_a));
	CHECK(!cb::iat::restore(self, "KERNEL32.dll", "GetCurrentProcessId", address(&fake_a), original)); // ours is not on top
	CHECK(GetCurrentProcessId() == kFakeB);                                                            // so nothing changed
	CHECK(cb::iat::restore(self, "KERNEL32.dll", "GetCurrentProcessId", address(&fake_b), underneath)); // the top hook comes off
	CHECK(cb::iat::restore(self, "KERNEL32.dll", "GetCurrentProcessId", address(&fake_a), original));
	CHECK(GetCurrentProcessId() == real);
}

TEST(patching_an_import_the_module_does_not_have_changes_nothing)
{
	const HMODULE self = GetModuleHandleW(nullptr);
	void *original = reinterpret_cast<void *>(1);
	CHECK(!cb::iat::patch(self, "KERNEL32.dll", "NoSuchFunctionAnywhere", address(&fake_a), &original));
	CHECK(!cb::iat::patch(self, "NOSUCH.dll", "GetCurrentProcessId", address(&fake_a), &original));
	CHECK(!cb::iat::patch(nullptr, "KERNEL32.dll", "GetCurrentProcessId", address(&fake_a), &original));
	CHECK(original == reinterpret_cast<void *>(1)); // untouched
	CHECK(GetCurrentProcessId() != kFakeA);
}

TEST(a_function_is_only_found_under_the_dll_it_is_imported_from)
{
	const HMODULE self = GetModuleHandleW(nullptr);
	void *original = nullptr;
	CHECK(!cb::iat::patch(self, "USER32.dll", "GetCurrentProcessId", address(&fake_a), &original)); // it is a KERNEL32 import
	CHECK(GetCurrentProcessId() != kFakeA);
}
