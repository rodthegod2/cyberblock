// The game-facing half of the Atlas survey (survey.hpp): natives, `survey` requests from Minecraft, the tour's hold.
#pragma once
#include <nlohmann/json.hpp>

namespace RED4ext
{
	struct CRTTISystem;
}

namespace cb::survey_game
{
	/// Registers the survey natives (RED4ext register callback, with the others).
	void register_natives(RED4ext::CRTTISystem *rtti);
	/// The link's observer for `survey` (link thread); ignores every other message.
	void on_minecraft_message(const nlohmann::json &m);
	/// The link dropped: the walking survey starts over with the next Minecraft.
	void on_link_dropped();
}
