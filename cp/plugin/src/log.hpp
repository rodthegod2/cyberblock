// Plugin log: <game>\red4ext\logs\Cyberblock.log, truncated on every launch. Safe to call from any thread and
// before open() (messages are then dropped).
#pragma once

namespace cb::log
{
	/// Opens the log next to RED4ext's own logs, found from the plugin's module handle (the plugin lives in
	/// red4ext\plugins\Cyberblock\). Outside that layout the log goes beside the DLL. Returns false if it cannot open.
	bool open(void *module);
	void close();

	/// printf-style; one line per call, flushed immediately so a crash does not lose it.
	void info(const char *format, ...);
	void warn(const char *format, ...);
	void error(const char *format, ...);
}
