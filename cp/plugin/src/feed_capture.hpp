// Phase 6: the ReShade half of the photo feed (megaplan 2026-10-04 task 6-D1; feed.hpp has the rules and the wire).
// Once a second while Minecraft asks for a feed: begin_effects sets Cyberblock.fx's cb_feed for that frame, the effect's
// Feed pass shrinks the composited picture into CbFeedTex (128 x 128), finish_effects records a copy of it into a
// read-back texture, present fences the copy, and a later present maps it (never waiting on the GPU), turns it into map
// colours and sends `feed`. The render-thread time of all that is measured per capture; three captures over 0.5 ms in a
// row, or a capture that cannot run, switch the back-buffer feed off with one log line and the fallback notice `feed
// {w:0,h:0,px:"",fallback}` to Minecraft (the fallback: Minecraft's top-down replica render).
#pragma once

namespace feed_capture
{
	/// Adds the feed's ReShade event callbacks; compositor::try_register calls it right after the add-on registered
	/// (ReShade removes them with the add-on).
	void register_events();
}
