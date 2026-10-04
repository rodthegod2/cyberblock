// Phase 6: the host's photo feed (megaplan 2026-10-04 sections 1.9 and task 6-D1). While Minecraft asks for it
// (`feedreq {on:true}`, DOWN), the compositor reads Cyberpunk's picture back once a second (feed_capture.cpp: the
// Cyberblock.fx pass Feed shrinks the composited picture, the centre square of the screen, to 128 x 128 on the GPU, and
// only those 64 KB are copied back), maps every pixel to the nearest Minecraft map colour and sends `feed` (BOTH: the
// host's client shows it and relays it for the Net Map and the Phase 8 Van). The parts that need no game are here.
//
// Wire (section 1.9):
//   feedreq DOWN {"t":"feedreq","on":bool}
//   feed    UP   {"t":"feed","w":128,"h":128,"px":"<base64 of 16384 map-colour bytes>"}: bytes row by row from the top
//                left, each a packed Minecraft map colour (MapColor id * 4 + brightness id), never 0..3 (NONE).
//   feed    UP   {"t":"feed","w":0,"h":0,"px":"","fallback":"<why>"}: the fallback notice (proposed for a dated 1.9
//                amendment; integrator): there will be no picture this Minecraft session; `fallback` (at most 128 bytes)
//                says why. Sent once when the capture gives up while a feed is requested, and again on a later
//                `feedreq on:true` while fallen back (at most once a second).
//
// Fallback (6-D1): when the read-back costs the game more than 0.5 ms of frame time (CostMonitor), or the capture cannot
// run (an old Cyberblock.fx without the Feed pass, a device without texture-to-buffer copies, a failed read-back), the
// DLL stops sending pictures, logs one line saying so, and tells Minecraft with the fallback notice, so its top-down
// replica render can stand in. A receiver that does not know the notice drops it as a bad `feed` (w, h not 128), which is
// the same as silence: Minecraft should also treat no picture within 3 s of `feedreq on` as the fallback.
#pragma once
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <nlohmann/json.hpp>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace cb::feed
{
	/// The feed is kSize x kSize map colours (a Minecraft map item).
	inline constexpr int kSize = 128;
	inline constexpr size_t kPixels = size_t(kSize) * kSize;
	/// At most one feed this often (seconds).
	inline constexpr double kIntervalSeconds = 1.0;
	/// A capture that costs the game's render thread more than this (milliseconds) is slow (6-D1's 0.5 ms).
	inline constexpr double kMaxCostMs = 0.5;
	/// This many slow captures in a row switch the back-buffer feed off (one slow capture is a hiccup, not a cost).
	inline constexpr int kSlowInARow = 3;

	/// Minecraft 26.3's 62 map base colours (net.minecraft.world.level.material.MapColor, by id; read with javap from
	/// the 26.3 jar), 0xRRGGBB. Id 0 (NONE) is transparent on a map and never sent.
	extern const std::array<uint32_t, 62> kMapColours;
	/// MapColor.Brightness modifiers by brightness id: LOW, NORMAL, HIGH, LOWEST.
	inline constexpr std::array<int, 4> kBrightness = {180, 220, 255, 135};
	/// The first and last packed colour the feed uses (id 1 brightness 0 .. id 61 brightness 3).
	inline constexpr uint8_t kFirstPacked = 4;
	inline constexpr uint8_t kLastPacked = 61 * 4 + 3;

	/// The colour Minecraft draws packed map colour `packed` in (MapColor.getColorFromPackedId: each channel * modifier
	/// / 255, rounded down), 0xRRGGBB; nullopt for 0..3 (NONE) and past kLastPacked.
	std::optional<uint32_t> packed_rgb(uint8_t packed);
	/// The packed map colour (kFirstPacked..kLastPacked) nearest to (r, g, b) by the "redmean" colour distance; the
	/// lowest packed value wins a tie. Exhaustive: 244 comparisons.
	uint8_t nearest(uint8_t r, uint8_t g, uint8_t b);

	/// nearest() for every colour quantised to 5 bits per channel (32 KB). Building it takes some milliseconds, so it is
	/// built off the render thread (prepare(), on the link thread when a feed is first asked for).
	class Palette
	{
	public:
		Palette();
		uint8_t map(uint8_t r, uint8_t g, uint8_t b) const
		{
			return m_lut[(size_t(r >> 3) << 10) | (size_t(g >> 3) << 5) | size_t(b >> 3)];
		}

	private:
		std::vector<uint8_t> m_lut;
	};
	/// Builds the plugin's palette once (thread-safe); later calls return at once.
	void prepare();
	/// Whether prepare() has finished (the render thread never waits for it).
	bool palette_ready();
	/// The plugin's palette; only after palette_ready().
	const Palette &palette();

	/// Map colours for a kSize x kSize picture of 8-bit pixels, 4 bytes each, `rowPitch` bytes from one row to the next
	/// (at least kSize * 4), channels R, G, B at offsets 0, 1, 2 (`bgra`: B, G, R). nullopt when `pixels` is too short.
	std::optional<std::vector<uint8_t>> to_map_colours(std::span<const uint8_t> pixels, size_t rowPitch, bool bgra,
														const Palette &palette);

	/// Standard base64 (RFC 4648, with padding).
	std::string base64(std::span<const uint8_t> bytes);

	/// {"t":"feed","w":128,"h":128,"px"}. nullopt unless `colours` holds exactly kPixels packed colours, each
	/// kFirstPacked..kLastPacked.
	std::optional<nlohmann::json> make_feed(std::span<const uint8_t> colours);
	/// Longest fallback reason, in bytes.
	inline constexpr size_t kMaxFallbackBytes = 128;
	/// {"t":"feed","w":0,"h":0,"px":"","fallback":<why cut to kMaxFallbackBytes>}: the fallback notice.
	nlohmann::json make_feed_fallback(std::string_view why);

	struct FeedReq
	{
		enum class Status
		{
			Ignored,  // not a `feedreq`
			Valid,    // `on` holds the request
			Rejected, // `on` is missing or not a bool
		};
		Status status = Status::Ignored;
		bool on = false;
	};
	/// Validates {"t":"feedreq","on":bool}. Never throws.
	FeedReq parse_feedreq(const nlohmann::json &message);

	/// Whether captures cost too much: note() each capture's render-thread milliseconds; kSlowInARow captures over
	/// kMaxCostMs in a row trip it. Not thread-safe (the render thread owns it).
	class CostMonitor
	{
	public:
		/// Returns true when this capture tripped the monitor (only once, until reset()).
		bool note(double ms);
		bool tripped() const { return m_tripped; }
		void reset();

	private:
		int m_slow = 0;
		bool m_tripped = false;
	};

	/// What the feed is doing: whether Minecraft asks for it, when the last capture began, and whether the back-buffer
	/// path was given up for this Minecraft session (the fallback). Thread-safe: the link thread sets the request, the
	/// render thread asks due() and reports the fallback.
	class Gate
	{
	public:
		void set_requested(bool on);
		bool requested() const { return m_requested; }
		/// Whether a capture should start now: requested, no fallback, the palette ready and kIntervalSeconds since the
		/// last capture began (which `now` then becomes).
		bool due(double now);
		/// Gives the back-buffer feed up until reset(), for reason `why`; returns true the first time (so the caller logs it
		/// once; the first reason is kept).
		bool fall_back(std::string_view why);
		bool fallen_back() const { return m_fallback; }
		/// The fallback notice to send now: only while requested and fallen back, at most once per kIntervalSeconds.
		std::optional<nlohmann::json> fallback_notice(double now);
		/// A new Minecraft session (`hello`) or none (a link drop): not requested, no fallback, no capture yet.
		void reset();

	private:
		std::atomic<bool> m_requested{false};
		std::atomic<bool> m_fallback{false};
		std::mutex m_lock;
		std::optional<double> m_last;
		std::optional<double> m_lastNotice;
		std::string m_reason;
	};

	/// Seconds on the steady clock (the clock due() and fallback_notice() are given by the plugin).
	double now_seconds();

	/// The plugin's gate. Never destroyed: the link thread may still use it while the process exits.
	inline Gate &gate()
	{
		static Gate *instance = new Gate;
		return *instance;
	}

	/// The link's observer for this layer (link thread): `feedreq` turns the feed on or off (logged on change, and on
	/// "on" the palette is prepared), an invalid one is counted (world::note_invalid); a `hello` resets the gate.
	void on_minecraft_message(const nlohmann::json &message);
	/// A linked Minecraft went away: no feed until it asks again.
	void on_link_dropped();
}
