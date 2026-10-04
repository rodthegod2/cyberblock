// The Atlas survey (explored map): which Night City columns get long down rays, the `terrain` messages built from the
// hits, and the automatic survey tour (`/cyberblock survey`) that carries V across Night City to fill the whole map.
// Pure: no RED4ext; survey_game.cpp talks to the game and the link.
//
// Walking survey (Columns): 2-m cells (cbmath kSurveyCell) within kRadius of V, nearest first; a cell handed out is not
// handed out again for kRefreshSeconds (kMissRetrySeconds after a miss), so the rays go where nothing is known yet.
//
// Tour (2026-10-03 survey-fast rework: the first tour needed 5.5 h for 2116 fixed stops; the whole tour must now take at
// most 15 minutes, so it is planned to a time budget rather than a quality target):
// - Area: the city is the convex hull of the fast-travel points whose district is not the Badlands (map pins join the
//   district of their nearest fast-travel point), widened by kCityMargin; the outskirts are the hull of every pin widened
//   by kOutskirtsMargin, minus the city. Open ocean beyond them is never visited.
// - Squares: the area is cut into 200-m squares (stop (gx, gy) = the square centred on Cyberpunk (200 gx, 200 gy), 100 x
//   100 cells). A square is sampled at every stride-th cell each way (stride 1: every 2-m cell; one ray fills its stride x
//   stride block of cells). City squares come first, then the outskirts as a coarser pass.
// - Time budget: the strides are planned so the whole tour ends within kPlanSeconds: the finest city stride that fits,
//   then the finest outskirts stride that fits in what is left (choose_strides). The plan starts from guessed rates and is
//   made again after every stop from the measured ones (seconds per column, seconds per stop besides the columns, squares
//   finished per stop); only squares nobody has sampled yet change stride. The whole city at a coarser cell is preferred
//   to gaps: the walking survey refines it later. The ETA is that same estimate. Retries are skipped once kPlanSeconds are
//   used, and the tour stops at kBudgetSeconds (a later start resumes).
// - Stops: V is held in the air over the centre of the next square (nearest to V with a pull towards where the tour
//   started, so the map grows outward from V). The DLL probes a 3 x 3 grid until the hits stay the same for
//   kSettleStable s (kSettleTimeout at most), then rings of probes measure how far loaded collision reaches, then every
//   unsampled column of every unfinished square within that reach is cast, as fast as the frame budget allows. Meanwhile V
//   flies towards the next stop (up to kMaxDrift, the far squares sampled first), so its collision streams in while this
//   one is sampled. A square is finished when every column hit something, or when it lay wholly inside the reach of a
//   settled stop and at least kSquareCovered of its columns hit (holes in the collision are accepted). A stop that did not
//   settle sends its square to a retry pass; the second visit always finishes it. Every finished square is reported
//   (`surveystate.stop`), so Minecraft keeps the progress and a later start resumes.
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

	/// One survey ray: the column (x, y) and the surface z in Cyberpunk space; kind 0 miss, 1 ground or building, 2 water.
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
		/// `minRadius`: columns nearer V than this are never handed out (the DLL's own rays in a vehicle stay clear of
		/// the collision boxes, which exist only within 64 m of V).
		explicit Columns(double minRadius = 0.0);
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

	// ---- the per-frame ray budget ---------------------------------------------------------------------------------------

	inline constexpr double kFrameTargetMs = 40.0;   // the tour keeps a frame within this (25 fps; V is held, the player AFK) ...
	inline constexpr double kMinBudgetMs = 2.0;      // ... but always casts for at least this long
	inline constexpr double kMaxBudgetMs = 25.0;     // ... and never longer than this
	inline constexpr double kVehicleBudgetMs = 1.5;  // the walking survey's rays per frame while V drives
	inline constexpr double kVehicleMinRadius = 66.0; // m: and only beyond the collision boxes (64 m around V)

	/// Milliseconds of rays this frame: what is left of kFrameTargetMs after the game's own share of the last frame
	/// (`intervalMs` between frames minus `lastWorkMs`, the rays cast in it), within kMinBudgetMs..kMaxBudgetMs.
	double frame_budget_ms(double intervalMs, double lastWorkMs);

	// ---- the tour's area ------------------------------------------------------------------------------------------------

	struct P2
	{
		double x, y;
	};
	/// The convex hull, counter-clockwise, without collinear points (fewer than 3 points: those left after duplicates).
	std::vector<P2> convex_hull(std::vector<P2> points);
	/// 0 inside the hull (or on it), else the distance to it. An empty hull is infinitely far.
	double hull_distance(const std::vector<P2> &hull, P2 p);
	/// The hull's area in m^2 (0 below 3 points).
	double hull_area(const std::vector<P2> &hull);

	enum PinKind
	{
		kCityTravel = 0,     // a fast-travel point outside the Badlands
		kOutskirtsTravel = 1, // a fast-travel point in the Badlands (or North/South Badlands, Morro Rock)
		kMapPin = 2,         // any other map pin (district unknown)
	};
	struct Pin
	{
		double x, y;
		int kind; // PinKind
	};

	inline constexpr int kMinPins = 8;                 // fewer usable pins than this: no area
	inline constexpr int kMinCityPoints = 6;           // fewer city points than this: the city is where pins are dense
	inline constexpr double kPinJoinRadius = 800.0;    // m: a map pin joins the district of its nearest fast-travel point
	inline constexpr double kDenseCell = 500.0;        // m: the dense-pin fallback's grid ...
	inline constexpr int kDenseCount = 4;              // ... and the pins a cell needs to count as city
	inline constexpr double kCityMargin = 300.0;       // m around the city hull
	inline constexpr double kOutskirtsMargin = 300.0;  // m around the hull of every pin
	inline constexpr double kWorldLimit = 8000.0;      // m: squares only within |x|, |y| <= this

	struct Area
	{
		std::vector<P2> city;    // hull
		std::vector<P2> outskirts; // hull of every pin
		size_t cityPoints = 0;   // the points the city hull was built from
		bool dense = false;      // the city came from the dense-pin fallback
	};
	/// The area from the pins (positions at 0, 0 or beyond 10 km are left out); nullopt with `why` when there is none.
	std::optional<Area> make_area(std::span<const Pin> pins, std::string *why = nullptr);

	// ---- the tour's squares -----------------------------------------------------------------------------------------------

	inline constexpr double kTourSpacing = 200.0;  // m: a square's side (stop (gx, gy) at Cyberpunk (gx S, gy S))
	inline constexpr double kTourHalf = 100.0;     // m: half of it
	inline constexpr int kSquareCells = 100;       // 2-m cells per side
	inline constexpr std::array<int, 4> kCityStrides = {1, 2, 4, 5};    // city cells of 2, 4, 8 or 10 m (divisors of 100)
	inline constexpr std::array<int, 3> kOutskirtsStrides = {4, 5, 10}; // outskirts cells of 8, 10 or 20 m
	inline constexpr int kOutskirtsStride = 4;     // the outskirts' stride before any plan
	inline constexpr double kSquareCovered = 0.9;  // the share of hits that finishes a square inside a settled stop's reach
	inline constexpr int kMaxVisits = 2;           // a square's second visit (the retry) always finishes it
	inline constexpr double kHomeBias = 0.5;       // the next square: distance from V + this x distance from the start
	inline constexpr size_t kMaxDone = 4096;       // finished squares Minecraft may list in `survey.done`

	struct Stop
	{
		int gx, gy;
		auto operator<=>(const Stop &) const = default;
	};

	/// Cyberpunk (x, y) of a square's centre.
	std::array<double, 2> stop_centre(Stop s);

	struct Square
	{
		Stop stop{};
		bool outskirts = false;
		int stride = 1;   // one column per stride x stride cells
		int visits = 0;   // stops held over its centre
		bool done = false;
		int count = 0;    // columns that hit
		std::vector<bool> sampled;
		int side() const { return kSquareCells / stride; } // columns per side
		int total() const { return side() * side(); }
		/// Cyberpunk (x, y) of column i (0 .. total - 1): the centre cell of its stride x stride block.
		std::array<double, 2> column(int i) const;
	};

	/// Every square of the area, city squares (centre within kCityMargin of the city hull, stride 1) and outskirts squares
	/// (centre within kOutskirtsMargin of the outskirts hull, kOutskirtsStride), without `done`; `total` (optional) gets the
	/// count with them.
	std::vector<Square> plan(const Area &area, const std::set<Stop> &done, size_t *total = nullptr);

	/// The order squares are visited in: the next is the unfinished square of the first non-empty group (city first visits,
	/// city retries, outskirts first visits, outskirts retries) that minimises distance from `from` + kHomeBias x distance
	/// from `home`. nullopt when every square is finished. `group` (optional) gets that group (0..3).
	std::optional<size_t> next_square(const std::vector<Square> &squares, P2 from, P2 home, int *group = nullptr);

	// ---- the time budget -----------------------------------------------------------------------------------------------

	inline constexpr double kPlanSeconds = 720.0;   // the plan aims to finish the whole tour within 12 minutes ...
	inline constexpr double kBudgetSeconds = 900.0; // ... and the tour stops after 15 (a later start resumes)
	inline constexpr double kReplanSeconds = 1.0;   // the plan is made again at least this often while sampling
	/// What the tour has measured (or guessed, before the first stops).
	struct Rates
	{
		double columnSeconds = 40e-6; // wall-clock seconds per column cast while sampling (rays and frames)
		double stopSeconds = 1.0;     // seconds per stop besides its columns (streaming, probes, the flight)
		double squaresPerStop = 4.0;  // squares finished per stop
	};
	struct Strides
	{
		int city = 1, outskirts = kOutskirtsStride;
		auto operator<=>(const Strides &) const = default;
	};
	/// Seconds the unfinished squares still take at `rates` if the squares nobody has sampled yet get `strides` (the
	/// others keep theirs).
	double estimate_seconds(const std::vector<Square> &squares, const Rates &rates, Strides strides);
	/// The finest city stride, then the finest outskirts stride (never finer than the city's), that finish within
	/// `secondsLeft`; the coarsest pair when none does.
	Strides choose_strides(const std::vector<Square> &squares, const Rates &rates, double secondsLeft);
	inline constexpr double kRefineShare = 0.85; // a finer plan is taken only when it needs at most this share of the time left
	/// The plan made again: choose_strides when it is the `first` or when `current` no longer fits (or a coarser pair is
	/// chosen); a finer pair only when it fits in kRefineShare of the time left.
	Strides replan_strides(const std::vector<Square> &squares, const Rates &rates, double secondsLeft, Strides current, bool first);

	// ---- the tour -------------------------------------------------------------------------------------------------------

	inline constexpr double kTourHoldZ = 150.0;     // m: the lowest altitude V is held at
	inline constexpr double kTourHoldAbove = 25.0;  // m: and at least this far above the highest probe of the last stop
	inline constexpr double kRayTop = 600.0;        // m: a survey ray's start ...
	inline constexpr double kRayBottom = -100.0;    // m: ... and end
	inline constexpr double kProbeStep = 60.0;      // m between the 3 x 3 settle probes
	inline constexpr double kSettleStable = 0.5;    // s the probes must stay the same (with at least one hit)
	inline constexpr double kSettleTimeout = 2.0;   // s: then sample what is there and send the square to the retry pass
	inline constexpr double kMaxDrift = 150.0;      // m V flies towards the next stop while sampling (at most half the way)
	inline constexpr std::array<double, 7> kReachRings = {150.0, 200.0, 250.0, 300.0, 400.0, 500.0, 600.0}; // m
	inline constexpr int kReachRays = 12;           // probes per ring ...
	inline constexpr int kReachHits = 10;           // ... that must hit for the ring to count as loaded
	inline constexpr double kMinReach = 145.0;      // m: a stop always samples its own square (half diagonal 141 m)
	inline constexpr double kStatusSeconds = 1.0;   // `surveystate` while running
	inline constexpr size_t kTextBytes = 64;        // `surveystate.reason`

	/// A `survey` request from Minecraft.
	struct Request
	{
		std::string op; // start, stop, status
		std::vector<Stop> done;
	};
	/// {"t":"survey","op":"start"|"stop"|"status","done":[gx, gy, ...]} (done: start only, whole numbers |v| <= 100000,
	/// pairs, at most kMaxDone squares); nullopt with `why` for anything else.
	std::optional<Request> parse_request(const nlohmann::json &m, std::string *why = nullptr);

	/// What the scripts do this tick.
	enum class Action
	{
		None,
		Bounds, // collect the map pins and fast-travel points and call Tour::area
		Begin,  // make V invisible and invulnerable, lock saving
		End,    // put V back at home() and undo Begin
	};

	/// What the DLL's per-frame rays do now.
	enum class Need
	{
		None,
		Probes,  // cast probes() and hand them to probed()
		Columns, // cast next_column() and hand each to column()
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
		/// After Action::Bounds: the pins the area is derived from.
		void area(std::span<const Pin> pins);

		/// Every frame (main thread): what the rays do now (None in the frame a stop began, so V is there first).
		Need need(double now) const;
		/// After Need::Probes: the columns to probe (all of them this frame).
		std::vector<std::array<double, 2>> probes() const;
		/// Their hits, in the same order.
		void probed(std::span<const Hit> hits, double now);
		/// After Need::Columns: the next column; nullopt once the stop's columns are all handed out (the stop ends then
		/// and the next one begins).
		std::optional<std::array<double, 2>> next_column(double now);
		/// The hit of the column next_column just handed out.
		void column(const Hit &hit);
		/// Milliseconds the rays took this frame (for the stop's log line).
		void spent(double ms);
		/// The cells to send as `terrain` (a hit fills its whole stride x stride block).
		std::vector<Hit> take_cells();
		size_t pending_cells() const { return m_cells.size(); }

		/// Where the drive holds V (the current stop at the hold altitude); nullopt when V is not held.
		std::optional<Vec3> hold() const;
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
			Reach,
			Sample,
			Ending,
		};
		void status(const std::string &state, const std::string &reason, std::optional<Stop> finished = std::nullopt);
		bool go_next(double now); // picks the next square and arrives there; false when none is left (the tour ends)
		void finish_stop(double now);
		void finish_square(Square &s);
		void end(const std::string &state, const std::string &reason);
		double eta() const;
		Rates rates(double now) const;
		void replan(double now);
		double run_seconds(double now) const; // this run's tour time so far, the current stop included
		std::optional<P2> predict_next(); // the stop after this one, if this one finishes every square inside its reach
		double drift() const;       // m V has flown from the stop's centre towards the next
		std::string group_name() const;
		Vec3 centre() const;

		Phase m_phase = Phase::Idle;
		std::set<Stop> m_done;
		std::vector<Square> m_squares;
		size_t m_total = 0, m_finished = 0; // squares including the ones done before this run; finished of them
		double m_progress = 0.0, m_progressAtStart = 0.0;
		Strides m_strides{};
		double m_sampleSeconds = 0.0, m_sampleStart = 0.0; // this run's time in Sample, and when the current Sample began
		size_t m_columnsRun = 0;                            // columns cast this run
		double m_lastPlan = -1e300;
		bool m_planned = false;
		std::optional<P2> m_next;                           // where V flies while sampling
		size_t m_target = 0;   // index of the square V is held over
		int m_group = 0;
		P2 m_from{};           // where the last stop was (the start: V's feet)
		Vec3 m_home{};
		double m_homeYaw = 0.0;
		double m_holdZ = kTourHoldZ;
		double m_now = 0.0;
		double m_arrived = 0.0;
		double m_stableSince = 0.0;
		double m_pausedAt = -1.0;
		double m_lastStatus = -1e300;
		double m_runSeconds = 0.0; // finished stops' durations this run
		size_t m_stopsThisRun = 0;
		std::vector<int> m_signature;
		bool m_anyHit = false;
		bool m_settled = false;  // the probes settled before the timeout
		double m_settleSeconds = 0.0;
		double m_highest = -1e300; // the highest probe hit of the current stop
		double m_reach = 0.0;      // measured
		double m_radius = kMinReach; // sampled
		// the stop's columns: candidate squares, the cursor, the column handed out last
		std::vector<size_t> m_candidates;
		size_t m_candidate = 0;
		int m_columnIndex = 0;
		std::optional<std::pair<size_t, int>> m_pending;
		std::vector<int> m_handed, m_hit, m_skipped; // per candidate: columns handed out this stop, their hits, columns left for later
		size_t m_columns = 0, m_hits = 0;
		double m_rayMs = 0.0;
		std::vector<Hit> m_cells;
		bool m_statusWanted = false;
		std::string m_stopWhy;
		std::string m_endState, m_endReason;
		std::vector<nlohmann::json> m_messages;
		std::vector<std::string> m_log;
	};
}
