// The Minecraft game mode Minecraft reports in `status.gm` (plan 2026-10-03 task D6): redscript reads it through
// Cyberblock_GameMode to tell creative (V stays invincible) from the other modes. Header-only, so link.cpp needs nothing else.
#pragma once
#include <string_view>

namespace cb
{
	enum class GameMode
	{
		None, // no status received in this session yet
		Survival,
		Creative,
		Adventure,
		Spectator,
	};

	/// The mode a `status.gm` value names; None for anything else (the value is then ignored).
	constexpr GameMode parse_game_mode(std::string_view name)
	{
		if (name == "survival")
			return GameMode::Survival;
		if (name == "creative")
			return GameMode::Creative;
		if (name == "adventure")
			return GameMode::Adventure;
		if (name == "spectator")
			return GameMode::Spectator;
		return GameMode::None;
	}

	/// The `status.gm` spelling of a mode; "" (the empty CName in scripts) for None.
	constexpr const char *game_mode_name(GameMode mode)
	{
		switch (mode)
		{
		case GameMode::Survival: return "survival";
		case GameMode::Creative: return "creative";
		case GameMode::Adventure: return "adventure";
		case GameMode::Spectator: return "spectator";
		case GameMode::None: break;
		}
		return "";
	}
}
