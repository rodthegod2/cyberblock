// Phase 8: roles and modes (megaplan 2026-10-04 sections 1.8 and 1.9 with the Phase 0 amendment item 8, task 8-D1). The
// parts that need no game: the four DOWN messages are validated and become the redscript events n"spawn", n"heat",
// n"attitude" and n"glitch" (Spawns.reds, package 8S), the `spawnresult` answer is built, and `glitch` also drives the
// ReShade glitch (cb_glitch, screenfx.hpp). natives.cpp hands the events out through the existing Cyberblock_Event*
// getters and implements Cyberblock_SpawnResult.
//
// Wire (section 1.9):
//   spawn       DOWN {"t":"spawn","id":<request>,"rec","p":[x,y,z],"n":1..8,"hostile":bool[,"faction"]}: spawn `n` Night
//               City NPCs of kind `rec` (one of kSpawnRecords; `turret` was dropped by the Phase 0 amendment: a turret is a
//               device, Minecraft-side only) at `p` (Minecraft space). `faction` is an attitude group or gang name of
//               [A-Za-z0-9_]{0,32} (the scripts pick the records; "" or absent: the kind's own). Event n"spawn": EventId the
//               request, EventName the kind, EventPos the spot in Cyberpunk space with the ground alignment, EventAmount
//               the count, EventFlag hostile, EventText the faction. A well-formed spawn of an unknown kind is answered at
//               once with {"t":"spawnresult","id","ok":false,"n":0,"reason":"unsupported"}.
//   spawnresult UP   {"t":"spawnresult","id","ok","n":0..8,"reason"}: built by make_spawnresult (Cyberblock_SpawnResult).
//               The DLL itself answers reason "expired" for a spawn that sat in the queue too long (expired_spawn).
//   heat        DOWN {"t":"heat","tier":0..5}: the server's Heat tier for this host. Event n"heat": EventAmount the tier.
//               State: kept while not Active, and a newer one replaces the queued one.
//   attitude    DOWN {"t":"attitude","faction","toward" in friendly|neutral|hostile}. Event n"attitude": EventName the
//               faction ([A-Za-z0-9_]{1,32}), EventText the direction, EventAmount +1 / 0 / -1. Kept while not Active.
//   glitch      DOWN {"t":"glitch","seconds":0..10} (Reboot Optics on this linked host; 0 ends it). Event n"glitch":
//               EventAmount the seconds (transient: dropped while not Active). The DLL itself starts the screen glitch.
#pragma once
#include "rhack.hpp"
#include "world.hpp"
#include <array>
#include <cstdint>
#include <nlohmann/json.hpp>
#include <optional>
#include <string_view>

namespace cb::modes
{
	/// `spawn.rec`: the Night City units the scripts can spawn (Phase 0 d: people and drones; no turret).
	inline constexpr const char *kSpawnRecords[] = {"ganger", "ncpd", "ncpd_heavy", "maxtac", "drone"};
	/// `attitude.toward`.
	inline constexpr const char *kAttitudes[] = {"friendly", "neutral", "hostile"};
	inline constexpr int kMaxSpawnCount = 8;
	inline constexpr int kMaxHeatTier = 5;
	/// Longest faction name, in bytes ([A-Za-z0-9_]: the TweakDB attitude group names, `6thStreet`, `kurtz_army`, ...).
	inline constexpr size_t kMaxFactionBytes = 32;
	/// Longest `spawnresult.reason`, in UTF-8 bytes.
	inline constexpr size_t kMaxReasonBytes = 64;

	/// The parse result, as rhack's: Ignored (another type), Event, Unsupported (send `reply` now) or Rejected (`reason`).
	using Request = rhack::Request;

	/// Validates `spawn`: `id` a whole number 0..2^31-1, `rec` a string, `p` [x, y, z] with |c| < 3e7 (converted to
	/// Cyberpunk space with the ground alignment `off`), `n` a whole number 1..8, `hostile` a bool, `faction` absent or a
	/// string of 0..32 characters of [A-Za-z0-9_]. A valid message whose `rec` is not in kSpawnRecords is Unsupported.
	/// Never throws.
	Request parse_spawn(const nlohmann::json &message, double off);
	/// Validates `heat`: `tier` a whole number 0..5. Never throws.
	Request parse_heat(const nlohmann::json &message);
	/// Validates `attitude`: `faction` 1..32 characters of [A-Za-z0-9_], `toward` one of kAttitudes. Never throws.
	Request parse_attitude(const nlohmann::json &message);
	/// Validates `glitch`: `seconds` a number 0..10. Never throws.
	Request parse_glitch(const nlohmann::json &message);

	/// {"t":"spawnresult","id","ok","n","reason"}: the answer to `spawn` `id`. `n` (how many came) is clamped to 0..8;
	/// `reason` is "" when `ok`, else cut to 64 UTF-8 bytes with control characters dropped ("failed" when nothing is
	/// left). nullopt when `id` is negative.
	std::optional<nlohmann::json> make_spawnresult(int32_t id, bool ok, int32_t n, std::string_view reason);

	/// Oldest a queued `spawn` may be when the scripts pull it, in seconds since the link thread got it. The server gives
	/// up on a spawn 5 s after sending it (NcSpawns.ANSWER_MS) and sends that spawn's Minecraft squad, and the scripts
	/// answer at the latest 3 s after they pull the event, so the time it waits here must stay well under 2 s. Events.reds
	/// drains the queue every tick, so only a stalled tick (pause menu, loading, saving) gets near this.
	inline constexpr double kMaxSpawnQueueSeconds = 1.0;
	/// The answer the DLL itself sends for a `spawn` that waited in the queue longer than kMaxSpawnQueueSeconds at `now`
	/// (cb::steady_seconds()): {"t":"spawnresult","id","ok":false,"n":0,"reason":"expired"}; the event is then skipped so
	/// the scripts never spawn it. nullopt for any other event, a fresh spawn, or one never stamped (`received` 0).
	std::optional<nlohmann::json> expired_spawn(const world::Event &event, double now);

	/// The link's observer for this layer (link thread, after world::on_minecraft_message): valid messages are queued in
	/// world::events(), an unknown spawn kind is answered `unsupported` at once, an invalid message is counted
	/// (world::note_invalid). A valid `glitch` also starts (or, at 0 s, ends) the screen glitch while this host is Host
	/// (or has no session yet); `hello` and a `session` that is not Host end it. Each is logged (the first 50).
	void on_minecraft_message(const nlohmann::json &message);
	/// A linked Minecraft went away: the screen glitch ends.
	void on_link_dropped();
	/// cb_glitch at `now` (cb::steady_seconds()): screenfx::Glitch::uniform. Any thread (the compositor, every frame).
	std::array<float, 4> glitch_uniform(double now, bool menu);
	/// Ends the screen glitch at once (Cyberblock off). Any thread.
	void glitch_clear();
}
