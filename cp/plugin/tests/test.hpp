// Micro test runner shared by the cb_tests sources (runner in test_main.cpp).
#pragma once
#include <cstdio>

namespace cbtest
{
	using Fn = void (*)();
	/// Adds a test to the list test_main.cpp runs; used through TEST().
	bool add(const char *name, Fn fn);
	/// Counts a failed CHECK and prints where it failed.
	void fail(const char *file, int line, const char *expr);
}

#define TEST(name)                                                   \
	static void name();                                              \
	static const bool name##_registered = cbtest::add(#name, &name); \
	static void name()

#define CHECK(cond)                                 \
	do                                              \
	{                                               \
		if (!(cond))                                \
			cbtest::fail(__FILE__, __LINE__, #cond); \
	} while (false)
