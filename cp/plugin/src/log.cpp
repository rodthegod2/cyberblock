#include "log.hpp"
#include <windows.h>
#include <cstdarg>
#include <cstdio>
#include <filesystem>
#include <mutex>
#include <share.h>

namespace
{
	std::mutex g_lock;
	FILE *g_file = nullptr;

	std::filesystem::path log_directory(HMODULE module)
	{
		wchar_t buffer[MAX_PATH] = {};
		const DWORD length = GetModuleFileNameW(module, buffer, MAX_PATH);
		if (length == 0 || length >= MAX_PATH)
			return {};
		const std::filesystem::path dll_dir = std::filesystem::path(buffer).parent_path();
		const std::filesystem::path plugins_dir = dll_dir.parent_path();
		if (plugins_dir.filename() == L"plugins")
			return plugins_dir.parent_path() / L"logs";
		return dll_dir;
	}

	void emit(const char *level, const char *format, va_list args)
	{
		char message[1024];
		vsnprintf(message, sizeof(message), format, args);
		SYSTEMTIME now;
		GetLocalTime(&now);
		std::lock_guard<std::mutex> lock(g_lock);
		if (g_file == nullptr)
			return;
		fprintf(g_file, "[%04u-%02u-%02u %02u:%02u:%02u.%03u] [%-5s] [%5lu] %s\n", now.wYear, now.wMonth, now.wDay,
				now.wHour, now.wMinute, now.wSecond, now.wMilliseconds, level, GetCurrentThreadId(), message);
		fflush(g_file);
	}
}

namespace cb::log
{
	bool open(void *module)
	{
		std::lock_guard<std::mutex> lock(g_lock);
		if (g_file != nullptr)
			return true;
		const std::filesystem::path dir = log_directory(static_cast<HMODULE>(module));
		if (dir.empty())
			return false;
		std::error_code ignored;
		std::filesystem::create_directories(dir, ignored);
		// deny-write only: the file stays readable while the game runs, so it can be tailed
		g_file = _wfsopen((dir / L"Cyberblock.log").c_str(), L"w", _SH_DENYWR);
		return g_file != nullptr;
	}

	void close()
	{
		std::lock_guard<std::mutex> lock(g_lock);
		if (g_file != nullptr)
		{
			fclose(g_file);
			g_file = nullptr;
		}
	}

	void info(const char *format, ...)
	{
		va_list args;
		va_start(args, format);
		emit("info", format, args);
		va_end(args);
	}

	void warn(const char *format, ...)
	{
		va_list args;
		va_start(args, format);
		emit("warn", format, args);
		va_end(args);
	}

	void error(const char *format, ...)
	{
		va_list args;
		va_start(args, format);
		emit("error", format, args);
		va_end(args);
	}
}
