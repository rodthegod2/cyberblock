// The Atlas survey (explored map): which Night City columns the scripts cast their long down rays at, the `terrain`
// messages built from the hits, and the automatic survey tour (`/cyberblock survey`) that carries V across Night City
// to fill the whole map. Pure: no RED4ext; survey_game.cpp talks to the game and the link.
//
// Walking survey (Columns): 2-m cells (cbmath kSurveyCell) within kRadius of V, nearest first; a cell handed out is not
// handed out again for kRefreshSeconds (kMissRetrySeconds after a miss), so the rays go where nothing is known yet.
//
// Tour: a serpentine walk over a grid of stops kTourSpacing apart (stop (gx, gy) at Cyberpunk (gx·S, gy·S)) inside the
// bounds the scripts derive from the map pins. At each stop V is held in the air (the DLL drive), the scripts cast a
// 3 x 3 probe grid until Night City's collision has streamed in (the probes stay the same for kSettleStable s, at least
// kSettleMin s after arrival; kSettleTimeout at most), then every 2-m column of the stop's square (kTourHalf around it)
// is sampled. A stop whose probes find only water, or nothing (void), is skipped. Every finished stop is reported
// (`surveystate.stop`), so Minecraft keeps the progress and a later start resumes.
#pragma once
#include "cbmath.hpp"
#include <array>
#include <cstddef>
#include <cstdint>
#include <nlohmann/json.hpp>
#include <optional>
#include <set>
#include <span>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace cb::survey
{
	inline constexpr double kRadius = 96.0;           // m around V the walking survey reaches
	inline constexpr double kRefreshSeconds = 300.0;  // a column is surveyed again after this
	inline constexpr double kMissRetrySeconds = 20.0; // a column whose ray found nothing (collision not streamed yet)
	inline constexpr size_t kCellsPerMessage = 64;    // `terrain.c` holds at most this many cells

	/// One survey ray from the scripts: the column (x, y) and the surface z in Cyberpunk space; kind 0 miss, 1 ground or
	/// building, 2 water.
	struct Hit
	{
		double x, y, z;
		int kind;
	};

	/// The `terrain` messages for the hits that found something ({"t":"terrain","c":[cx, cz, y, s, ...]}, at most
	/// kCellsPerMessage cells each; s 1 for water). Misses and non-finite hits are left out.
	std::vector<nlohmann::json> make_terrain(std::span<const Hit> hits, double off);

	/// The walking survey's columns around V.
	class Columns
	{
	public:
		Columns();
		/// The next Cyberpunk column (x, y) to cast at, nearest to V first; nullopt when every cell within kRadius was
		/// handed out recently (then nothing is looked at for a second unless V moves 16 m).
		std::optional<std::array<double, 2>> next(Vec3 cpFeet, double now);
		/// The ray at column (x, y) found nothing: try it again after kMissRetrySeconds.
		void missed(double x, double y, double now);
		/// Forget everything (a new Minecraft world, another alignment): the area around V is surveyed again.
		void clear();
		size_t remembered() const { return m_until.size(); }

	private:
		std::vector<std::pair<int, int>> m_offsets; // cell offsets within kRadius, nearest first
		std::unordered_map<int64_t, double> m_until; // cell -> when it may be handed out again
		int m_centreX = 0, m_centreZ = 0;
		size_t m_cursor = 0;
		bool m_pass = false;
		double m_idleUntil = -1e300;
	};

	// ---- the tour -----------------------------------------------------------------------------------------------------

	inline constexpr double kTourSpacing = 200.0;   // m between stops
	inline constexpr double kTourHalf = 110.0;      // m: a stop samples the square of this half-size around it
	inline constexpr double kTourHoldZ = 150.0;     // m: the lowest altitude V is held at
	inline constexpr double kTourHoldAbove = 25.0;  // m: and at least this far above the highest probe of the last stop
	inline constexpr double kProbeStep = 80.0;      // m between the 3 x 3 settle probes
	inline constexpr double kSettleMin = 1.5;       // s after arriving before a stop may count as streamed
	inline constexpr double kSettleStable = 1.0;    // s the probes must stay the same
	inline constexpr double kSettleTimeout = 15.0;  // s: then sample what is there (or skip a stop with no ground)
	inline constexpr double kStatusSeconds = 1.0;   // `surveystate` while running
	inline constexpr int kMinPins = 8;              // fewer map pins than this: no bounds
	inline constexpr double kBoundsMargin = 200.0;  // m around the pins
	inline constexpr double kMaxSpan = 9000.0;      // m: the bounds are cut to this around their centre
	inline constexpr double kWorldLimit = 8000.0;   // m: and to |x|, |y| <= this
	inline constexpr size_t kMaxDone = 4096;        // finished stops Minecraft may list in `survey.done`
	inline constexpr size_t kTextBytes = 64;        // `surveystate.reason`

	struct Stop
	{
		int gx, gy;
		auto operator<=>(const Stop &) const = default;
	};

	/// Cyberpunk (x, y) of a stop.
	std::array<double, 2> stop_centre(Stop s);
	/// Every stop whose square touches the bounds, in serpentine order (rows of gy upward, x alternating), without
	/// `done`. `total` (optional) gets the number of stops including the done ones.
	std::vector<Stop> plan(double minX, double minY, double maxX, double maxY, const std::set<Stop> &done, size_t *total = nullptr);

	/// A `survey` request from Minecraft.
	struct Request
	{
		std::string op; // start, stop, status
		std::vector<Stop> done;
	};
	/// {"t":"survey","op":"start"|"stop"|"status","done":[gx, gy, ...]} (done: start only, whole numbers |v| <= 100000,
	/// pairs, at most kMaxDone stops); nullopt with `why` for anything else.
	std::optional<Request> parse_request(const nlohmann::json &m, std::string *why = nullptr);

	enum class Action
	{
		None,
		Bounds, // the scripts must derive the bounds from the map pins and call Tour::bounds
		Begin,  // make V invisible and invulnerable, lock saving
		Settle, // cast the 3 x 3 probes around stop() and hand them to Tour::settle
		Sample, // cast columns from Tour::next_column and submit them
		End,    // put V back at home() and undo Begin
	};

	struct TourIn
	{
		double now = 0.0;
		Vec3 feet{};      // V's feet, Cyberpunk space
		double yaw = 0.0; // V's facing, Cyberpunk degrees
		std::string blocked; // why V may not tour now ("" = may): off, vehicle, held, combat, flight, unlinked, dead
		bool menu = false;   // a Cyberpunk menu is open: the tour waits
	};

	class Tour
	{
	public:
		void request_start(std::vector<Stop> done);
		void request_stop(std::string why);
		void request_status();
		/// Every script tick; the scripts carry out what it returns.
		Action tick(const TourIn &in);
		/// After Action::Bounds: the Cyberpunk x/y extents of `pins` map pins.
		void bounds(double minX, double minY, double maxX, double maxY, int pins);
		/// After Action::Settle: the 3 x 3 probes around stop() (Hit.z the surface, kind 0 miss, 1 ground, 2 water).
		void settle(std::span<const Hit> probes, double now);
		/// After Action::Sample: the stop's next column; nullopt once all were handed out.
		std::optional<std::array<double, 2>> next_column();
		/// Columns cast and hits found this tick (for the log and the summary).
		void sampled(size_t columns, size_t hits);
		/// Where the drive holds V (the current stop at the hold altitude); nullopt when V is not held.
		std::optional<Vec3> hold() const;
		/// The current stop's centre at the hold altitude.
		Vec3 stop() const;
		/// Where V stood (feet) and faced when the tour started.
		Vec3 home() const { return m_home; }
		double home_yaw() const { return m_homeYaw; }
		bool running() const;
		/// `surveystate` messages to send, oldest first.
		std::vector<nlohmann::json> take_messages();
		/// Log lines, oldest first.
		std::vector<std::string> take_log();
		/// The session ended: forget the tour without an End (nothing is left to put back).
		void reset();

	private:
		enum class Phase
		{
			Idle,
			Requested,
			AwaitBounds,
			Begin,
			Settle,
			Sample,
			Ending,
		};
		void status(const std::string &state, const std::string &reason, std::optional<Stop> finished = std::nullopt);
		void arrive(double now);
		void finish_stop(double now, const char *how);
		void end(const std::string &state, const std::string &reason);
		double eta() const;

		Phase m_phase = Phase::Idle;
		std::set<Stop> m_done;
		std::vector<Stop> m_plan;
		size_t m_index = 0;
		size_t m_total = 0;
		Vec3 m_home{};
		double m_homeYaw = 0.0;
		double m_holdZ = kTourHoldZ;
		double m_now = 0.0;
		double m_arrived = 0.0;
		double m_stableSince = 0.0;
		double m_pausedAt = -1.0;
		double m_lastStatus = -1e300;
		double m_stopSeconds = 0.0; // the sum of finished stops' durations this run
		size_t m_stopsThisRun = 0;
		std::vector<int> m_signature;
		double m_highest = 0.0; // the highest probe hit of the current stop
		int m_cx0 = 0, m_cz0 = 0, m_cx1 = 0, m_cz1 = 0, m_cx = 0, m_cz = 0; // the stop's cells and the next one
		bool m_columnsDone = false;
		size_t m_columns = 0, m_hits = 0;
		bool m_statusWanted = false;
		std::string m_stopWhy;
		std::string m_endState, m_endReason;
		std::vector<nlohmann::json> m_messages;
		std::vector<std::string> m_log;
	};
}
