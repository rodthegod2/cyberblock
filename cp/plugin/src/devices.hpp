// Phase 4: Night City's devices for Minecraft (megaplan 2026-10-04 sections 1.8, 1.9 and 1.11 with the dated Phase 0
// amendment, task 4-D1). The parts that need no game: the `devices` stream (validation, the nearest 64 within 48 m,
// deltas plus a full list every 5 s, positions through cp_to_mc_pos), `devstate` and `devresult` builders, who changed a
// device (`by`, with the guest's 2 s window), the `devact` request from Minecraft as a redscript event, and the device
// boxes the probe filter drops hits in (probes.hpp drop_hits_in_boxes). natives.cpp is the thin game-facing layer.
//
// Wire (section 1.9 with amendment items 4-6):
//   devices   UP   {"t":"devices","list":[{"id":"<dec>","cls","kind","p":[x,y,z],"yaw","st","fl"}]}; `size` is omitted
//                  (Minecraft takes it from `device_map`); a delta lists only new or changed entries, the full list
//                  comes every 5 s, so a device missing from every message for 10 s has left (4M's removal timer).
//   devstate  UP   {"t":"devstate","id":"<dec>","st","fl","by"}, by one of npc, host, guest, script.
//   devact    DOWN {"t":"devact","id":<request>,"dev":"<dec>","act"}; the supported acts become the event n"devact"
//                  (EventId the request, EventTarget the device, EventName the act); any other act is answered at once
//                  with {"t":"devresult","id","ok":false,"reason":"unsupported"}.
//   devresult UP   {"t":"devresult","id","ok","reason"}.
#pragma once
#include "cbmath.hpp"
#include "world.hpp"
#include <array>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <map>
#include <mutex>
#include <nlohmann/json.hpp>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace cb::devices
{
	/// Most entries one `devices` message holds (the nearest ones).
	inline constexpr size_t kMaxEntries = 64;
	/// Devices farther than this from V (metres, Cyberpunk space) are not sent.
	inline constexpr double kRadius = 48.0;
	/// A full list goes out at least this often (seconds); between two, only deltas.
	inline constexpr double kFullListSeconds = 5.0;
	/// Longest `cls` (an RTTI class name): a longer one is cut to this.
	inline constexpr size_t kMaxClassChars = 48;
	/// `st`: EDeviceStatus (DISABLED -2, UNPOWERED -1, OFF 0, ON 1) or, for doors, EDoorStatus (SEALED -2, LOCKED -1,
	/// CLOSED 0, OPENED 1).
	inline constexpr int32_t kMinState = -2;
	inline constexpr int32_t kMaxState = 1;
	/// `fl` bits: 1 locked, 2 secure, 4 quest-locked, 8 broken, 16 powered.
	inline constexpr int32_t kFlagLocked = 1, kFlagSecure = 2, kFlagQuestLocked = 4, kFlagBroken = 8, kFlagPowered = 16;
	inline constexpr int32_t kFlagMask = 31;
	/// A device change within this many seconds of a `devact` for that device is the guest's (`by:"guest"`).
	inline constexpr double kGuestSeconds = 2.0;
	/// Longest `devresult.reason`, in UTF-8 bytes.
	inline constexpr size_t kMaxReasonBytes = 64;

	/// The `kind` values: section 1.11's toggle-bit names (the `device_map` kinds the scripts mirror), in bit order.
	inline constexpr const char *kKinds[] = {"door",   "gate",    "light",    "camera", "terminal", "vending",
											 "traffic", "elevator", "turret", "screen", "container"};
	/// `devstate.by`.
	inline constexpr const char *kBy[] = {"npc", "host", "guest", "script"};
	/// The `devact.act` values Cyberpunk can perform (amendment item 6). `call`, `buy` and `breach` are not among them.
	inline constexpr const char *kActs[] = {"open", "close", "toggle", "lock",     "unlock",  "on",
											"off",  "disable", "enable", "distract", "friendly"};

	/// One device as the scripts submit it, in Cyberpunk space.
	struct Device
	{
		uint64_t id = 0;
		std::string cls;  // RTTI class name, at most kMaxClassChars
		std::string kind; // one of kKinds
		Vec3 pos{};       // world position
		double yaw = 0.0; // Cyberpunk degrees (Entity.GetWorldYaw)
		int32_t st = 0;
		int32_t fl = 0;
	};

	struct Validated
	{
		std::vector<Device> devices; // the valid entries, in the order submitted
		size_t dropped = 0;          // invalid entries left out
		std::string reason;          // why the first one was
	};

	/// The parallel arrays of Cyberblock_SubmitDevices as devices. nullopt unless every array has the length of `ids`
	/// (`sizes` may also be empty: it is ignored, amendment item 5). An entry is left out (and counted) when its id is 0
	/// or repeats an earlier one, its class is empty or holds anything but printable ASCII, its kind is not one of
	/// kKinds, its position is not finite or |c| >= 3e7, its yaw is not finite, its state is outside -2..1 or its flags
	/// outside 0..31. A class longer than 48 chars is cut to 48.
	std::optional<Validated> validate(std::span<const uint64_t> ids, std::span<const std::string> classes,
									  std::span<const std::string> kinds, std::span<const Vec3> pos,
									  std::span<const double> yaws, std::span<const Vec3> sizes,
									  std::span<const int32_t> states, std::span<const int32_t> flags);

	/// The devices within kRadius of `center` (Cyberpunk space), nearest first, at most kMaxEntries. Without a centre
	/// (V's position unknown) the first kMaxEntries in the order given.
	std::vector<Device> nearest(std::vector<Device> devices, const std::optional<Vec3> &center);

	/// One `devices.list` entry: the id as a decimal string, the position through cp_to_mc_pos with `-off` (1 mm), the yaw
	/// as a Minecraft yaw (0.01 degree).
	nlohmann::json entry_json(const Device &device, double off);

	/// What has been sent of the `devices` stream. Not thread-safe (natives.cpp guards it).
	class Stream
	{
	public:
		/// The `devices` message for one submit of `devices` (already nearest() and capped): the full list when none was
		/// sent since the last reset() or the last full list is kFullListSeconds old, else only the entries that are new
		/// or differ from what was last sent. nullopt when there is nothing to send (no change, or an empty full list).
		std::optional<nlohmann::json> submit(std::span<const Device> devices, double off, double now);
		/// The next submit sends the full list (Minecraft reconnected, a new server session, a new ground alignment).
		void reset();
		/// A `devstate` told Minecraft this state: the next delta does not repeat it.
		void note_state(uint64_t id, int32_t st, int32_t fl);
		/// Devices in the last message state.
		size_t size() const { return m_sent.size(); }

	private:
		std::map<uint64_t, nlohmann::json> m_sent; // id -> the entry as Minecraft has it
		std::optional<double> m_fullAt;
	};

	/// {"t":"devstate","id","st","fl","by"}. nullopt when `id` is 0, `st` is outside -2..1, `fl` outside 0..31 or `by` is
	/// not one of kBy.
	std::optional<nlohmann::json> make_devstate(uint64_t id, int32_t st, int32_t fl, std::string_view by);
	/// {"t":"devresult","id","ok","reason"}: the answer to `devact` `id`; `reason` cut to 64 UTF-8 bytes, "" when `ok`.
	/// nullopt when `id` is negative.
	std::optional<nlohmann::json> make_devresult(int32_t id, bool ok, std::string_view reason);

	/// `by` as sent: "guest" when a guest's `devact` for the device came within kGuestSeconds, else the scripts' own.
	std::string_view attribute(std::string_view by, bool guestRecent);

	/// The `devact` requests of the last kGuestSeconds, so the change they cause is sent as the guest's. At most
	/// kRemembered requests; thread-safe (the link thread notes them, the game's threads ask).
	class GuestWindow
	{
	public:
		static constexpr size_t kRemembered = 64;
		void note(int32_t request, uint64_t device, double now);
		/// The request failed (`devresult ok:false`): no change follows it.
		void forget(int32_t request);
		/// Whether a request for `device` came within the last kGuestSeconds.
		bool recent(uint64_t device, double now) const;
		void clear();

	private:
		struct Entry
		{
			int32_t request;
			uint64_t device;
			double at;
		};
		mutable std::mutex m_lock;
		std::deque<Entry> m_entries; // oldest first
	};

	struct DevAct
	{
		enum class Status
		{
			Ignored,     // not a `devact`
			Event,       // `event` is the n"devact" event to queue
			Unsupported, // a valid request with an act Cyberpunk cannot perform: send `reply` now
			Rejected,    // a field is missing or invalid: `reason` says which
		};
		Status status = Status::Ignored;
		world::Event event;
		std::optional<nlohmann::json> reply;
		std::string reason;
	};
	/// Validates {"t":"devact","id","dev","act"}: `id` a whole number 0..2^31-1, `dev` a non-zero decimal uint64 string,
	/// `act` a string. Never throws.
	DevAct parse_devact(const nlohmann::json &message);

	// ---- device boxes for the probe filter ----

	/// A kind's box until Minecraft's `device_map` sizes are known: width (along the device's right), height, depth
	/// (along its facing) in metres, and whether the position is the box's vertical centre (wall-mounted kinds) rather
	/// than its bottom.
	struct BoxSize
	{
		double w, h, d;
		bool centered;
	};
	/// The default box of `kind` (a 1 m cube standing on the position for an unknown kind).
	BoxSize default_size(std::string_view kind);
	/// Every box grows by this on each side: probe hits lie on the device's surface.
	inline constexpr double kBoxMargin = 0.25;
	/// The Cyberpunk-space box around a device: its default size turned by its yaw, plus the margin.
	Aabb device_box(const Device &device);

	/// The boxes of the last device list (thread-safe). They expire kMaxAge seconds after they were set.
	class BoxSet
	{
	public:
		static constexpr size_t kMax = 256;
		static constexpr double kMaxAge = 5.0;
		/// The boxes of `devices` (the first kMax).
		void set(std::span<const Device> devices, double now);
		std::vector<Aabb> current(double now) const;

	private:
		mutable std::mutex m_lock;
		std::vector<Aabb> m_boxes;
		double m_at = 0.0;
	};

	// ---- the plugin's instances ----

	/// Never destroyed: the link thread may still use them while the process exits.
	inline GuestWindow &guest_window()
	{
		static GuestWindow *instance = new GuestWindow;
		return *instance;
	}
	inline BoxSet &boxes()
	{
		static BoxSet *instance = new BoxSet;
		return *instance;
	}

	/// The link's observer for this layer (link thread): a `devact` is queued as an event (and noted in guest_window())
	/// or answered `unsupported` at once; an invalid one is counted (world::note_invalid). A `hello` forgets the window.
	void on_minecraft_message(const nlohmann::json &message);
	/// A linked Minecraft went away: forgets the guest window (the boxes belong to Night City and stay until they expire).
	void on_link_dropped();
}
