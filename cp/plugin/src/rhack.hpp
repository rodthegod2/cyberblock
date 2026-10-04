// Phase 6: remote hacks and Breach daemons from Minecraft (megaplan 2026-10-04 sections 1.8 and 1.9, task 6-D1). The
// parts that need no game: `rhack` and `daemon` are validated and become the redscript events n"rhack" and n"daemon";
// both are answered by the scripts through Cyberblock_HackResult (`hackresult` with the request id). natives.cpp only
// hands the events out through the existing Cyberblock_Event* getters.
//
// Wire (section 1.9):
//   rhack  DOWN {"t":"rhack","id":<request>,"target":"<dec>"|null|"","hack", optional "p":[x,y,z], "r", "secs",
//               "district"}; `hack` is one of the ten hack names of world::kHackNames or one of kRemoteHacks. `target`
//               absent, null, "" or "0" is no target (the server's builder sends "" for an area hack, the protocol null).
//               The optional extras (6I ActWire.rhack, 6P protocol.md "Phase 6"): `p` the area's centre in Minecraft
//               space, `r` its radius in metres (0..150), `secs` the effect's length in seconds (0..600, 0 = the scripts'
//               own), `district` a district name. Event n"rhack": EventId the request, EventTarget the target (0 = none),
//               EventName the hack, EventPos `p` in Cyberpunk space with the ground alignment (zero when absent: the
//               scripts act around V), EventExtra `r` (0 when absent), EventAmount `secs` IN SECONDS (0 when absent),
//               EventText the district (cut to 64 bytes, control characters dropped; "" when absent). A valid request
//               whose hack is not known is answered at once with {"t":"hackresult","id","ok":false,"reason":"unsupported"}.
//   daemon DOWN {"t":"daemon","id":<request>,"dev":"<dec>","list":["<daemon id>", ...],"strength":1..3}: the daemons a
//               solved Breach uploads to device `dev`. Event n"daemon": EventId the request, EventTarget the device,
//               EventAmount the strength, EventText the daemon ids joined by ',' (in the order sent), EventName the
//               first daemon id. The daemon ids come from the `daemon` content (namespaced ids), so the DLL checks their
//               form only; the scripts answer an id they cannot perform.
#pragma once
#include "world.hpp"
#include <cstddef>
#include <nlohmann/json.hpp>
#include <optional>
#include <string>
#include <string_view>

namespace cb::rhack
{
	/// The remote hacks Phase 6 adds to the ten quickhack names (section 1.9 `rhack`).
	inline constexpr const char *kRemoteHacks[] = {"veh_lock",       "veh_engine_off", "veh_brake", "veh_start",
												   "veh_jam",        "traffic_switch", "gridlock",  "blackout",
												   "cam_feed",       "outline",        "ping_area"};
	/// Most daemons one `daemon` message names (a Breach buffer holds at most 8 codes, so never more daemons).
	inline constexpr size_t kMaxDaemons = 8;
	/// Longest daemon id, in bytes; ids are [a-z0-9_.:/-] (content ids are `<namespace>:<name>`).
	inline constexpr size_t kMaxDaemonIdBytes = 64;
	inline constexpr int kMinStrength = 1;
	inline constexpr int kMaxStrength = 3;
	/// Largest `rhack.r` (metres) and `rhack.secs` (seconds), as the protocol bounds them; longest district, in bytes.
	inline constexpr double kMaxRadius = 150.0;
	inline constexpr double kMaxSeconds = 600.0;
	inline constexpr size_t kMaxDistrictBytes = 64;

	/// Whether `hack` is a quickhack name (world::kHackNames) or a remote hack (kRemoteHacks).
	bool known_hack(std::string_view hack);
	/// Whether `hack` acts on one thing and so needs a target: the quickhacks, the vehicle hacks and `cam_feed`. The area
	/// hacks (`traffic_switch`, `gridlock`, `blackout`, `outline`, `ping_area`) may come without one.
	bool needs_target(std::string_view hack);

	struct Request
	{
		enum class Status
		{
			Ignored,     // not a `rhack` / `daemon`
			Event,       // `event` is the event to queue
			Unsupported, // a well-formed `rhack` whose hack is not known: send `reply` now
			Rejected,    // a field is missing or invalid: `reason` says which
		};
		Status status = Status::Ignored;
		world::Event event;
		std::optional<nlohmann::json> reply;
		std::string reason;
	};

	/// Validates {"t":"rhack","id","target","hack"} and the optional extras: `id` a whole number 0..2^31-1, `target` a
	/// decimal uint64 string, or absent / null / "" / "0" (0) for an area hack, `hack` a string; `p` [x, y, z] with
	/// |c| < 3e7 (converted to Cyberpunk space with the ground alignment `off`), `r` a number 0..kMaxRadius, `secs` a
	/// whole number 0..kMaxSeconds, `district` a string (cut, never rejected for its length). A targeted hack
	/// (needs_target) with no target, or a present extra that breaks its rule, is rejected. Never throws.
	Request parse_rhack(const nlohmann::json &message, double off);
	/// Validates {"t":"daemon","id","dev","list","strength"}: `id` as above, `dev` a non-zero decimal uint64 string, `list`
	/// 1..kMaxDaemons ids of 1..kMaxDaemonIdBytes characters from [a-z0-9_.:/-], `strength` a whole number 1..3. Never
	/// throws.
	Request parse_daemon(const nlohmann::json &message);

	/// The link's observer for this layer (link thread): a valid `rhack` or `daemon` is queued in world::events(), an
	/// unknown hack is answered `unsupported` at once, an invalid message is counted (world::note_invalid). Each is logged
	/// (the first 50: requests are player actions, but a bad peer must not fill the log).
	void on_minecraft_message(const nlohmann::json &message);
}
