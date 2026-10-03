// Runs every TEST() and returns the number of failed CHECKs (0 = pass), so ctest reports failures.
#include "test.hpp"
#include <exception>
#include <vector>

namespace
{
	struct Entry
	{
		const char *name;
		cbtest::Fn fn;
	};

	std::vector<Entry> &tests()
	{
		static std::vector<Entry> list; // function-local: TEST() registers during static initialisation
		return list;
	}

	int g_failures = 0;
}

bool cbtest::add(const char *name, Fn fn)
{
	tests().push_back({name, fn});
	return true;
}

void cbtest::fail(const char *file, int line, const char *expr)
{
	++g_failures;
	std::printf("  FAIL %s:%d: %s\n", file, line, expr);
}

int main()
{
	for (const Entry &test : tests())
	{
		const int before = g_failures;
		try
		{
			test.fn();
		}
		catch (const std::exception &e) // a test that throws fails, instead of killing the run before anything prints
		{
			cbtest::fail(test.name, 0, e.what());
		}
		std::printf("%s %s\n", g_failures == before ? "pass" : "FAIL", test.name);
	}
	std::printf("%zu tests, %d failed checks\n", tests().size(), g_failures);
	return g_failures;
}
