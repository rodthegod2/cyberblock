// Layers 2-6 (world interaction, mobs, flight, tethers, powers), the parts that need no game: what Minecraft sends
// (`block`, `blocks`, `explosion`, `hit`, `ignite`, `teleport`, `lead`, `pull`, `knock`, `effect`, `hack`, `scan`, `perks`,
// `perkfx`, and since re-test 2 `cyberware` and `cyberfx`) is validated, converted to Cyberpunk space and queued for
// redscript, `align` sets the ground alignment, and the messages for Minecraft are built
// from what redscript submits. natives.cpp is the thin game-facing layer over this.
// Wire formats: shared/protocol.md; plans: docs/superpowers/plans/2026-10-02-layer2-world-interaction.md section 1 and
// docs/superpowers/plans/2026-10-03-layers3-6-complete-mod.md sections 1.2, 1.3 and 1.5, and
// docs/superpowers/plans/2026-10-03-retest2-and-cyberware.md sections 1.1-1.3.
//
// Ground alignment (retest2 section 1.1): Minecraft owns k (0..63) and sends it as `align`; positions convert with
// off = k / 64 m both ways (Minecraft y = Cyberpunk z - off). Every position conversion below takes `off` explicitly.
#pragma once
#include "cbmath.hpp"
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

namespace cb::world
{
	// ---- Minecraft -> Cyberpunk -------------------------------------------------------------------------------------

	enum class EventKind
	{
		None,
		Explosion,
		Hit,
		Ignite,
		BlockAdd,
		BlockRemove,
		BlockReset,
		Teleport,
		Lead,
		LeadRelease,
		Pull,
		Knock,
		Effect,
		Hack,
		Scan,
		Perk,
		PerkFx,
		Cyber,   // re-test 2: one installed/removed implant (plan section 1.3)
		CyberFx, // re-test 2: an implant effect starts or ends
		Prefs,   // Phase Q (megaplan Q-D1): a `prefs` arrived; `flag` is its `stepup`
		Session, // Phase 1 (megaplan 2026-10-04 sections 1.2 and 1.8): the Minecraft server's verdict on this host changed
		VTeleport, // Phase 3 (megaplan sections 1.8 and 1.9): move V to a spot (Meet Up, Holo-Table, hub exit)
		Park,      // Phase 3: park V (on) or bring V back (off)
		DevAct,    // Phase 4 (megaplan sections 1.8 and 1.9, task 4-D1): act on a device; parsed by devices.cpp
	};

	/// The CName redscript sees from Cyberblock_NextEvent: "explosion", "hit", "ignite", "block_add", "block_remove",
	/// "block_reset", "teleport", "lead", "lead_release", "pull", "knock", "effect", "hack", "scan", "perk", "perkfx",
	/// "cyber", "cyberfx", "prefs", "session", "vteleport", "park", "devact"; "" (the empty CName) for None.
	const char *event_name(EventKind kind);

	/// One Minecraft event, already in Cyberpunk space. Which fields each kind fills is the event table of plan
	/// 2026-10-03 section 1.3; the others are zero.
	struct Event
	{
		EventKind kind = EventKind::None;
		Vec3 pos{};          // explosion centre | block AABB min corner | teleport / vteleport feet | lead anchor | pull toward |
							 // knock centre
		uint64_t target = 0; // hit / ignite / lead / pull / hack / scan target: the EntityID hash (scan: 0 = look-at) |
							 // devact: the device id
		double amount = 0;   // explosion power | hit dmg / 20 (fraction of the target's max health) | ignite seconds |
							 // teleport / vteleport yaw (Cyberpunk degrees) | lead length | pull strength | knock radius |
							 // effect seconds | perk rank | perkfx seconds | cyber 1 installed, 0 not | cyberfx seconds |
							 // prefs vdist (0 = the game's own)
		Vec3 vector{};       // hit impulse, m/s
		bool flag = false;   // explosion fire | lead has a fixed anchor | hack chains | prefs stepup | park on
		std::string name;    // effect name | hack name | perk id | perkfx id | cyberware id | cyberfx id | session state |
							 // vteleport why | devact act
		double extra = 0;    // knock strength | effect amplifier | session perms bitmask
		int32_t id = 0;      // hit: the mob that dealt it (-1 none) | hack, scan, vteleport, devact: the request id
		std::string text;    // session: the reason (Cyberblock_EventText)
	};

	/// The ten hack names of plan section 1.5 (`hack.hack`) and the twenty perk ids (`perks[].id`, `perkfx.id`).
	inline constexpr const char *kHackNames[] = {"short_circuit", "overheat",        "reboot_optics", "cripple_movement",
												 "contagion",     "synapse_burnout", "ping",          "memory_wipe",
												 "cyberpsychosis", "device"};
	inline constexpr const char *kPerkIds[] = {"iron_skin",    "gorilla_arms", "painkiller", "juggernaut",   "double_jump",
											   "sandevistan",  "quick_draw",   "dodge",      "tinkerer",     "smart_arrows",
											   "field_medic",  "demolitions",  "extra_ram",  "cheap_hacks",  "chain_hack",
											   "overclock",    "optical_camo", "headhunter", "cold_blood",   "silent_steps"};

	/// Re-test 2 (plan section 1.7): the 17 cyberware ids, in the wire order, for `cyberware.list[].id`, and the three
	/// implant effects of `cyberfx.id`.
	inline constexpr const char *kCyberwareIds[] = {
		"mantis_blades", "gorilla_arms", "monowire",     "projectile_launch_system", "reinforced_tendons", "fortified_ankles",
		"lynx_paws",     "sandevistan",  "berserk",      "cyberdeck",                "kerenzikov",         "optical_camo",
		"subdermal_armor", "kiroshi_optics", "biomonitor", "second_heart",           "ram_upgrade"};
	inline constexpr const char *kCyberFxIds[] = {"sandevistan", "kerenzikov", "berserk"};
	/// Most entries one `cyberware` message may hold (17 ids: a sane bound, the rest is dropped).
	inline constexpr size_t kMaxCyberwareEntries = 32;

	struct Parsed
	{
		enum class Status
		{
			Ignored,  // not one of the handled types (or not an object): another layer's message, nothing to do
			Event,    // `events` is valid
			Rejected, // one of the handled types, but a field is missing or invalid: `reason` says which
		};
		Status status = Status::Ignored;
		/// Status::Event: what the message expands to, in order. Usually one event. `blocks` is a `block_reset` (when it
		/// resets) followed by a `block_add` per cell, `perks` is a `perk` per entry, `cyberware` a `cyber` per entry; a
		/// `blocks` without cells has none.
		std::vector<Event> events;
		/// List entries (`perks` and `cyberware` entries, `blocks` cells) that were invalid and left out; `reason` names
		/// the first one.
		size_t droppedEntries = 0;
		std::string reason;
	};

	/// Validates one message from Minecraft. Limits: coordinates finite with |v| < 3e7 (block coordinates whole
	/// numbers), explosion power 0..20, hit dmg 0..1000, ignite seconds 0..120, ids decimal uint64 strings, and the
	/// layers 3-6 limits of plan section 1.2: lead length 1..32, pull/knock strength 0..50, knock radius 0..16, effect
	/// seconds 0..86400 and amplifier 0..255, perkfx seconds 0..30, at most 64 block cells per `blocks`, hack names and
	/// perk ids from the lists above, effect names [a-z_]{1,32}; re-test 2: `cyberware` entries {id of kCyberwareIds,
	/// on 0|1} (at most 32; a bad one is dropped and counted), `cyberfx` {id of kCyberFxIds, seconds 0..30}. `hit` without `impulse` gets a zero impulse, and `hit`
	/// without `mob` the mob id -1. Positions (block AABBs, explosion, teleport, lead anchor, pull toward, knock centre)
	/// get `+off`; the hit impulse does not. The production caller passes the current level_offset; the 0.0 default is
	/// for tests. Phase 3: `vteleport` {id 0..2^31-1, p (feet, `+off`), yaw, why of kVTeleportWhy} and `park` {on bool}.
	/// Never throws.
	Parsed parse_event(const nlohmann::json &message, double off = 0.0);

	struct QueueStats
	{
		size_t queued = 0;     // events waiting, block changes included
		uint64_t dropped = 0;  // events dropped because the queue (or the block cell table) was full
		uint64_t rejected = 0; // messages parse_event rejected (and list entries it dropped)
	};

	/// Events for redscript, and the "current" event the Cyberblock_Event* natives read. Never blocks: every operation is
	/// a short critical section.
	///
	/// Block changes are state, so they are never lost to a burst: they are kept per cell (the last add or remove of each
	/// cell wins, and a `block_reset` forgets every change before it), so memory is bounded by the cells that changed, at
	/// most kMaxBlockCells. Every other kind is a FIFO of at most kCapacity events; when it is full the oldest transient
	/// event goes first (a perk, cyber, scan or hack only when nothing else is left); a `prefs` replaces the one already
	/// queued, if any (the newest setting wins in its place). next() hands out that FIFO first, then a
	/// pending `block_reset`, then the block changes (by cell, not by arrival: their order does not matter once each cell
	/// has one change).
	class EventQueue
	{
	public:
		static constexpr size_t kCapacity = 256;
		static constexpr size_t kMaxBlockCells = size_t(1) << 20;

		/// Whether the mashup is Active. While it is not, the transient events (explosion, hit, ignite, teleport, lead,
		/// lead_release, pull, knock, effect, perkfx, cyberfx) are discarded (nothing can act on them), and each change of
		/// state discards the ones already queued, so a stale blast never replays on the next activation. `block_add`,
		/// `block_remove`, `block_reset`, `perk`, `cyber`, `scan`, `hack`, `session`, `vteleport`, `park` and `devact` are
		/// always kept: redscript's block registry, perk ranks and installed cyberware need every one of them, a scan, a
		/// hack, a vteleport, a park or a devact must be answered, and a session change must clean Night City whatever the
		/// state.
		void set_active(bool active);
		void push(const Event &event);
		/// Counts a message parse_event rejected.
		void note_rejected(size_t count = 1);
		/// Pops the oldest event and makes it current; with none queued the current event becomes None.
		EventKind next();
		Event current() const;
		/// Forgets every queued event and the current one (Minecraft reconnected: they belong to the old session).
		void clear();
		/// Forgets the pending block changes and `block_reset` only (a non-host `session` resets the collision registry, so
		/// block events queued before it must not come out after it and put boxes back).
		void clear_blocks();
		QueueStats stats() const;

	private:
		mutable std::mutex m_lock;
		std::deque<Event> m_queue; // every kind but the block ones, oldest first
		bool m_blockReset = false; // a block_reset is pending; it comes out before m_blocks
		std::map<std::array<double, 3>, EventKind> m_blocks; // AABB min corner -> BlockAdd or BlockRemove
		Event m_current;
		bool m_active = false;
		uint64_t m_dropped = 0;
		uint64_t m_rejected = 0;
	};

	/// The plugin's event queue. Never destroyed: the link thread may still use it while the process exits.
	inline EventQueue &events()
	{
		static EventQueue *instance = new EventQueue;
		return *instance;
	}

	// ---- the ground alignment (plan 2026-10-03-retest2 section 1.1) ----

	/// k is a whole number of 1/64 m steps.
	inline constexpr int kLevelSteps = 64;
	/// The alignment k of the attached world: 0..63, from Minecraft's last `align`; 0 before any and after every `hello`.
	int level_k();
	/// off = k / 64 m, exact in float32, double and JSON.
	inline double level_offset(int k)
	{
		return k / 64.0;
	}

	/// The link's observer (runs on the link thread for every message from Minecraft): queues the events of the valid
	/// messages in events() (converted with the current alignment), counts and logs invalid ones (the first 10 only: a
	/// misbehaving peer must not fill the log), forgets the old session's events and resets k to 0 on each `hello`, and
	/// takes k from `align` (an integer 0..63; a bad one is counted and the old k kept; a change is logged), and hands
	/// `session` to on_session (Phase 1).
	void on_minecraft_message(const nlohmann::json &message);
	/// Counts `count` invalid messages or list entries (events().stats().rejected) and logs `reason` for the first 10 only.
	void note_invalid(size_t count, const std::string &reason);

	// ---- re-test 3 (plan 2026-10-03-retest3-polish section 1.1): Minecraft -> Cyberpunk state ----

	/// {"t":"persp","mode"}: true for "back", false for "first"; nullopt when `mode` is missing or anything else.
	std::optional<bool> parse_persp(const nlohmann::json &message);
	/// The last `persp` asked for the third-person view (on_minecraft_message keeps it; false before any, after every
	/// `hello` and after a link drop). Any thread.
	bool third_person_wanted();
	/// The last `status.pause` (Minecraft's Night City pause screen is open: Cyberpunk must be paused). False before any
	/// status, when a status has no boolean `pause` (older Minecraft builds), after every `hello` and after a link drop.
	/// Any thread. The UI bridge turns it into pause/resume actions (plan section 1.1: level-triggered).
	bool minecraft_pause();
	/// A linked Minecraft went away (main.cpp's drop handler, link thread): forgets `persp`, the pause and the `prefs`.
	void on_link_dropped();

	// ---- Phase Q (megaplan sections 1.9 and Q-D1): `prefs`, the third-person camera preferences ----

	/// What `prefs` sets. The defaults hold before any `prefs`, after every `hello` and after a link drop.
	struct ViewPrefs
	{
		double dist = 3.2;      // m behind V on foot, 1.5..8
		double shoulder = 0.45; // m to the right (negative: left), -1..1
		double fov = 0.0;       // 0 = the game's own, else 50..110 (gameCameraComponent.SetFOV units)
		bool recenter = true;   // swing the shoulder offset to the centre while a wall is on that side
		double vdist = 0.0;     // 0 = the game's own, else 3..14 m (View.reds picks the nearest vehicle camera preset)
		bool stepup = true;     // the step-up assist (scripts, through the event `prefs`)
		bool debug = false;     // Minecraft's `/cyberblock debug view` flag: `cam` carries `vdbg` while it is on
		bool operator==(const ViewPrefs &) const = default;
	};

	/// `prefs` applied on top of `current`: every number is clamped into its range (`fov` and `vdist`: below 0 is 0, above
	/// 0 but under the lower bound is raised to it); a field that is missing keeps its current value; a field that is not
	/// finite or of the wrong type keeps its current value and is counted in `bad` (its name in `why`, the first one only);
	/// `debug` is optional like every field (a `prefs` without it keeps the flag); any other field is ignored. Never throws.
	/// shared/vectors.json `prefs_apply` (tools/cbmath.py apply_prefs) pins these rules.
	ViewPrefs parse_prefs(const nlohmann::json &message, const ViewPrefs &current, size_t &bad, std::string &why);
	/// The prefs in effect (on_minecraft_message keeps them). Any thread.
	ViewPrefs view_prefs();
	/// The `vdbg` the next `cam` carries: `values` while Minecraft's view debug flag (`prefs.debug`) is on, else none. Any
	/// thread.
	std::optional<std::array<double, 3>> vdbg_to_send(const std::array<double, 3> &values);

	// ---- Cyberpunk -> Minecraft -------------------------------------------------------------------------------------

	/// The damage kinds the `dmg` message knows; anything else is sent as "other".
	inline constexpr const char *kDamageKinds[] = {"bullet", "explosion", "melee", "fall", "vehicle", "fire", "other"};

	/// Minecraft yaw (degrees, -180..180) of a Cyberpunk yaw (degrees, 0 = facing +Y, counter-clockwise from above, as
	/// Entity.GetWorldYaw reports it): Cyberpunk forward (-sin a, cos a) becomes Minecraft (-sin m, cos m) at m = 180 - a.
	double cp_yaw_to_mc_yaw(double cpYaw);

	/// {"t":"dmg","frac","kind","from"}: `frac` (fraction of V's max health) is clamped to 0..4; `from` (Cyberpunk
	/// space, sent in Minecraft space with `-off`) is null unless `hasFrom` and finite. nullopt when `frac` is not finite.
	std::optional<nlohmann::json> make_dmg(double frac, std::string_view kind, Vec3 fromCp, bool hasFrom, double off);

	/// {"t":"npcs","list":[{"id","p","yaw","kind","hostile"[,"dead":true]}]} from the arrays redscript submits:
	/// Cyberpunk feet positions (`-off`) and yaws, converted to Minecraft. `dead` (empty, or one flag per id) marks the
	/// last sighting of an NPC that died or a vehicle that was destroyed (Phase 3 amendment); the key is sent only when
	/// set. nullopt unless the arrays have the same length. An entry with a non-finite position or yaw is left out.
	std::optional<nlohmann::json> make_npcs(std::span<const uint64_t> ids, std::span<const Vec3> feetCp,
											std::span<const double> yawsCp, std::span<const uint8_t> vehicles,
											std::span<const uint8_t> hostile, double off,
											std::span<const uint8_t> dead = {});

	// ---- Phase 5 (megaplan 2026-10-04 sections 1.8 and 1.9 with the Phase 0 amendment, items 7; task 5-D1) -----------
	// The `npcs` extension: Cyberblock_SubmitNpcs2 adds role, faction, gender, archetype, animation bits, vehicle class,
	// colour and hp to every entry. Everything below is pure (no game); natives.cpp is the thin layer over it.

	/// `anim` bits (1.9): the scripts set 4..128, the DLL sets 1 and 2 from position deltas (the scripts have no walk or run).
	inline constexpr int kAnimWalk = 1, kAnimRun = 2, kAnimAim = 4, kAnimFire = 8, kAnimRagdoll = 16, kAnimDead = 32,
						 kAnimCrouch = 64, kAnimCombat = 128;
	inline constexpr int kAnimMask = 255;
	/// Longest `role`, `fac`, `arch` and `vc` in characters (1.9: `vc` <= 32, for example `Car/Turbo`).
	inline constexpr size_t kMaxFigureTag = 32;

	/// Walk and run from the horizontal speed between two submits (m/s, Cyberpunk metres are Minecraft metres). A person
	/// strolls at about 1.4 m/s, jogs at 3.5 and sprints at 5 or more. Each bit has two thresholds (on above, off below)
	/// so the speed jitter of a standing or cruising NPC does not flicker the pose. Faster than kTeleportSpeed is a
	/// respawn or a fast travel, not walking: no bit. Samples closer than kMinMotionInterval carry the last bits; samples
	/// further apart than kMaxMotionInterval say nothing (the NPC was out of the list in between).
	inline constexpr double kWalkOn = 0.6, kWalkOff = 0.3, kRunOn = 3.2, kRunOff = 2.6, kTeleportSpeed = 15.0;
	inline constexpr double kMinMotionInterval = 0.02, kMaxMotionInterval = 1.0;

	/// How the three numbers of a `VehicleColorTemplate.MainColor` are read (Phase 0 CET F2 decides; unconfirmed, so HSB
	/// is the working assumption: the game's own colour picker stores hue, saturation and brightness, each 0..255).
	enum class ColourFormat
	{
		Hsb,
		Rgb,
	};
	/// The one place the format is chosen; change it (and the log line) if F2 says RGB.
	inline constexpr ColourFormat kColourFormat = ColourFormat::Hsb;
	inline constexpr const char *kColourFormatLog = "npcs: colour format hsb (unconfirmed)";

	/// `colours[i]` from the scripts: the template's three numbers packed (first << 16 | second << 8 | third, each 0..255),
	/// -1 when the car has no colour template. "#RRGGBB" (upper case) for a packed value, nullopt for -1 or anything that
	/// is not three bytes. Hsb: hue 0..255 is 0..360 degrees, saturation and brightness 0..255 are 0..1.
	std::optional<std::string> colour_hex(int32_t packed, ColourFormat format = kColourFormat);

	/// A figure tag as sent: the text itself when it is 1..kMaxFigureTag characters of [A-Za-z0-9_./-] (the enum and
	/// vendor type names of the game, "Car/Turbo"), else nullopt (the key is then left out; a cut-off name would be wrong).
	std::optional<std::string> figure_tag(std::string_view text);

	/// Walk and run bits for the people of consecutive npcs lists (Phase 0 f: the scripts cannot read them). Remembers
	/// the last feet position of every person; whoever is missing from a list is forgotten. Vehicles are not tracked.
	class NpcMotion
	{
	public:
		/// The bits (kAnimWalk, kAnimRun or 0) of every entry, from the positions (Cyberpunk space, any consistent
		/// offset) and `nowSeconds` (a monotonic clock). `vehicles[i] != 0` entries get 0 and are not remembered.
		std::vector<int> update(std::span<const uint64_t> ids, std::span<const Vec3> feetCp,
								std::span<const uint8_t> vehicles, double nowSeconds);
		void clear() { m_known.clear(); }
		size_t size() const { return m_known.size(); }

	private:
		struct Sample
		{
			Vec3 feet;
			double at;
			int bits;
		};
		std::map<uint64_t, Sample> m_known;
	};

	/// The parallel arrays of Cyberblock_SubmitNpcs2 beyond the six of SubmitNpcs. Each is empty (the key is left out of
	/// every entry) or as long as `ids`.
	struct NpcTags
	{
		std::span<const std::string> roles, factions, archetypes, vclass; // CNames as text
		std::span<const int32_t> genders, anims, colours;
		std::span<const double> hp;
	};

	/// {"t":"npcs","list":[...]} with the Phase 5 keys per entry: `role`, `fac`, `arch` (a valid figure_tag, else absent),
	/// `g` 0 unknown / 1 male / 2 female (anything else, and every vehicle, 0), `anim` (the scripts' bits 4..128; 0 for a
	/// vehicle; bits 1 and 2 are set from `motion` for living people who are not ragdolling), `vc` (vehicles only), `col`
	/// (vehicles only, when `colours` decodes), `hp` 0..1 (absent when negative or not finite). A tag array that is not
	/// empty and not as long as `ids` makes the whole list nullopt, like the old arrays. `motion` null leaves bits 1 and 2
	/// out. With empty `tags` and no `motion` this is exactly make_npcs.
	std::optional<nlohmann::json> make_npcs2(std::span<const uint64_t> ids, std::span<const Vec3> feetCp,
											 std::span<const double> yawsCp, std::span<const uint8_t> vehicles,
											 std::span<const uint8_t> hostile, double off, std::span<const uint8_t> dead,
											 const NpcTags &tags, NpcMotion *motion, double nowSeconds);

	/// What one npcs list carries, for the DLL's 10 s summary line (1.14 style): how many entries have which tag.
	struct NpcTagSummary
	{
		int people = 0, vehicles = 0, withFaction = 0, withRole = 0, withArchetype = 0, male = 0, female = 0, unknownGender = 0,
			withColour = 0, walking = 0, running = 0;
	};
	NpcTagSummary summarize_npcs(const nlohmann::json &list);

	// ---- layers 3-6 (plan 2026-10-03 section 1.2) ----

	/// The threat levels `scaninfo.threat` knows; anything else is sent as "none".
	inline constexpr const char *kThreatLevels[] = {"none", "low", "medium", "high", "extreme"};
	/// Longest `scaninfo.name`, `scaninfo.faction` and `hackresult.reason`, in UTF-8 bytes.
	inline constexpr size_t kMaxTextBytes = 64;
	/// Most block cells one `pcells` message holds.
	inline constexpr size_t kMaxPcells = 64;

	/// What `cam` says about V besides the pose.
	struct CamFlags
	{
		bool crouched = false; // `cr`: V is crouched
		bool flying = false;   // `fly`: the DLL drive moves V after Steve's glide or launch (natives::flightFollowing)
		bool menu = false;     // `menu`: a Cyberpunk menu is open
		bool air = false;      // `air`: V is in the air (jump, fall, double jump, air dash, ...)
		bool held = false;     // `held`: V is in a vehicle or held by a scene, workspot, braindance or moving elevator
	};

	/// {"t":"cam",...}: the camera and V, every Cyberpunk frame while active (Minecraft convention, so `position`, `feet`
	/// and `look` are already converted, the positions with the offset of `levelK`). `r` is [yaw, pitch, 0]; `cr`, `fly`,
	/// `menu`, `air` and `held` are `flags`; `lv` is `levelK`, the alignment the positions were converted with. Re-test 3
	/// (plan section 1.1): `fp` is `firstPerson` (false in a third-person vehicle camera and while the on-foot third-person
	/// view is applied) and `seat` is `seat` (V sits in a vehicle; `feet` is then V's hips point). Numbers are not rounded.
	/// Phase Q (megaplan section 1.9): `vdbg` [collide m, smooth m, age ms] is added when given (the view debug flag is on),
	/// each value clamped to its protocol range (0..100 m, 0..60000 ms; not finite: 0) and rounded to 1 mm / 0.1 ms.
	nlohmann::json make_cam(int64_t frame, Vec3 position, YawPitch look, double vfov, Vec3 feet, double bodyYaw, CamFlags flags,
							bool firstPerson, bool seat, int levelK,
							const std::optional<std::array<double, 3>> &vdbg = std::nullopt);

	/// {"t":"look","d":[dyaw,dpitch]}: raw mouse movement as Minecraft look deltas in degrees (right and down are positive).
	nlohmann::json make_look(double dyaw, double dpitch);

	/// {"t":"mobdmg","id","frac","by"}: a Cyberpunk NPC damaged the puppet of mob `id`. `frac` (fraction of the puppet's
	/// max health) is clamped to 0..4; `by` is the NPC's id as a decimal string, null unless `hasBy`. nullopt when `id`
	/// is negative or `frac` is not finite.
	std::optional<nlohmann::json> make_mobdmg(int32_t id, double frac, uint64_t by, bool hasBy);

	/// {"t":"pcells","c":[x,y,z, ...]}: the Minecraft block cells (`floor` per axis, after converting from Cyberpunk space
	/// with `-off`) of Night City surface points hit by predicted projectiles. Points with a non-finite or out-of-range
	/// coordinate are skipped, repeated cells are sent once, and past 64 cells the rest is dropped. nullopt when no cell
	/// is left.
	std::optional<nlohmann::json> make_pcells(std::span<const Vec3> hitsCp, double off);

	/// {"t":"unlead","target":"<id>"}: Cyberpunk gave up the tether on `target`.
	nlohmann::json make_unlead(uint64_t target);

	/// {"t":"scaninfo",...}: the reply to a `scan`. `name` and `faction` are cut to 64 UTF-8 bytes on a character
	/// boundary, `threat` is one of kThreatLevels (else "none"), `hp` is clamped to 0..1, `feetCp` (Cyberpunk space) is
	/// sent in Minecraft space (`-off`) and `width` x `height` as `size`. A non-finite number is sent as 0. `ok` false
	/// sends the empty reply (empty strings, threat "none", hp 0, no target, no device, zeros). nullopt when `id` is
	/// negative.
	std::optional<nlohmann::json> make_scaninfo(int32_t id, bool ok, uint64_t target, std::string_view name,
												std::string_view faction, std::string_view threat, double hp, bool device,
												Vec3 feetCp, double width, double height, double off);

	// ---- re-test 3 (plan 2026-10-03-retest3-polish sections 1.1 and 1.6): distinct quickhacks ----

	/// The target kinds `hackresult.kind` knows (anything else is left out).
	inline constexpr const char *kHackKinds[] = {"squad", "crowd", "driver", "camera", "traffic", "distract", "device"};
	/// Why `hackspread` touched an extra NPC.
	inline constexpr const char *kSpreadWhy[] = {"chain", "spread", "ping", "frenzy"};
	/// Most `hackspread` messages one hack request id may send; the 17th and later are dropped and counted.
	inline constexpr int kMaxSpreadPerId = 16;
	/// Longest upload a `hackresult` reports, seconds.
	inline constexpr double kMaxUploadSeconds = 30.0;

	/// {"t":"hackresult","id","ok","reason"[,"upload","kind"]}: the reply to a `hack`; `reason` is cut to 64 UTF-8 bytes and
	/// is "" when `ok`. Only when `ok`: `upload` (seconds the upload takes, clamped to 0..30, a non-finite one is 0) and
	/// `kind` (one of kHackKinds; left out when empty or unknown). nullopt when `id` is negative.
	std::optional<nlohmann::json> make_hackresult(int32_t id, bool ok, std::string_view reason, double upload = 0.0,
												  std::string_view kind = {});

	/// {"t":"hackspread","id","target","why"}: hack request `id` also touched NPC `target` (sent as a decimal string).
	/// nullopt when `id` is negative or `why` is not one of kSpreadWhy.
	std::optional<nlohmann::json> make_hackspread(int32_t id, uint64_t target, std::string_view why);

	/// Counts `hackspread` messages per hack request id (kMaxSpreadPerId each). Remembers the last kRemembered ids, so its
	/// memory is bounded. Thread-safe.
	class SpreadCounter
	{
	public:
		static constexpr size_t kRemembered = 64;
		/// Whether one more spread may be sent for `id`; false (and counted as dropped) past kMaxSpreadPerId.
		bool allow(int32_t id);
		/// Spreads refused so far.
		uint64_t dropped() const;

	private:
		mutable std::mutex m_lock;
		std::deque<std::pair<int32_t, int>> m_counts; // id -> spreads sent, oldest id first
		uint64_t m_dropped = 0;
	};

	/// {"t":"killed","target","kind","hostile"}: an NPC or vehicle that V damaged died or was destroyed.
	nlohmann::json make_killed(uint64_t target, bool vehicle, bool hostile);

	// ---- Phase 1 (megaplan 2026-10-04 sections 1.2 and 1.8, task 1-H1): the server session ---------------------------

	/// What the Minecraft server made of this host (`session.state`). Unknown before the first `session` of a Minecraft
	/// (a mod older than Phase 1 never sends one) and again after every `hello` and link drop; it acts like Host, so a
	/// singleplayer game with an older mod behaves as before.
	enum class SessionState
	{
		Unknown,
		None,
		Host,
		Guest,
		Refused,
	};
	/// "none", "host", "guest", "refused"; "" for Unknown.
	const char *session_state_name(SessionState state);
	/// Longest `session.reason`, in UTF-8 bytes.
	inline constexpr size_t kMaxSessionReason = 128;
	/// The keys of `session.perms`, in bit order (1 explosions, 2 damage, 4 hacks, 8 devices, 16 spawns), as `hs_ack.perms`.
	inline constexpr const char *kSessionPerms[] = {"explosions", "damage", "hacks", "devices", "spawns"};

	struct Session
	{
		SessionState state = SessionState::Unknown;
		std::string reason;
		int32_t perms = 0;
		bool operator==(const Session &) const = default;
	};

	/// {"t":"session","state","reason","perms"}: `state` one of "none", "host", "guest", "refused"; `reason` a string of
	/// at most 128 UTF-8 bytes ("" when absent); `perms` an object whose five kSessionPerms keys are booleans when present
	/// (an absent key is false, other keys are ignored; no `perms` at all is 0). nullopt, with `why` saying which field
	/// failed, for anything else. Never throws.
	std::optional<Session> parse_session(const nlohmann::json &message, std::string *why = nullptr);

	/// Takes a valid `session` (on_minecraft_message, link thread). When it differs from the last one it logs
	/// `session: <state> (<reason>)`, queues a `session` event (name = state, text = reason, extra = perms; kept while not
	/// Active), bumps ground_epoch() on a change to Host, and on a change from Host or Unknown to None, Guest or Refused
	/// raises take_session_cleanup(). A repeat of the last one does nothing.
	void on_session(const Session &session);
	/// The last valid `session` (state Unknown before one, after a `hello` and after a link drop).
	Session session();
	/// +1 each time this host becomes the server's host: the ground Minecraft got before belongs to another server session,
	/// so the probe state must be forgotten and `clear` sent (natives.cpp SubmitProbes and restart_ground).
	int ground_epoch();
	/// Whether the ground was last restarted for an older epoch; then `seen` becomes the current one. natives.cpp keeps
	/// `seen` with its probe state.
	bool take_ground_restart(int &seen);
	/// True once after a `session` moved this host away from Host (or Unknown): the DLL does what a link drop does on its
	/// side (main.cpp: unpause, flight stop) while the WebSocket stays open.
	bool take_session_cleanup();

	// ---- Phase 3 (megaplan 2026-10-04 sections 1.8 and 1.9 with the Phase 0 amendment, task 3-D1) --------------------

	/// `vteleport.why`: what moves V. The event carries it as its name.
	inline constexpr const char *kVTeleportWhy[] = {"meetup", "holo", "hub_exit"};
	/// `env.weather`.
	inline constexpr const char *kWeathers[] = {"clear", "cloudy", "rain", "storm", "fog", "smog", "sandstorm", "other"};

	/// The `env.weather` of a weather preset name (`worldWeatherState.name`, case ignored) by its words: sandstorm,
	/// storm / thunder, rain, fog, pollution / smog / toxic, cloud / overcast, sunny / clear / blue; anything else is
	/// "other" with `known` false. An empty name (nothing read) is "other" and known.
	const char *weather_from_preset(std::string_view preset, bool *known = nullptr);
	/// {"t":"env","time":[h,m],"weather"}: the game time and the mapped weather (`known` as weather_from_preset). nullopt
	/// unless h is 0..23 and m 0..59.
	std::optional<nlohmann::json> make_env(int32_t hour, int32_t minute, std::string_view preset, bool *known = nullptr);

	/// A `vteleported` / `parked` reason (section 1.9 amendment item 3): "", "no ground", "timeout" or "blocked (<why>)"
	/// with a non-empty <why>, at most 64 UTF-8 bytes in all and no control characters.
	bool valid_teleport_reason(std::string_view reason);
	/// {"t":"vteleported","id","ok","reason","p"}: the answer to `vteleport` `id`; `feetCp` is V's snapped feet (Cyberpunk
	/// space, sent in Minecraft space with `-off`). `reason` is "" when `ok`; a failure needs a valid non-empty reason (a
	/// "blocked (<why>)" over 64 bytes has its <why> shortened to fit). nullopt when `id` is negative, the feet are not
	/// finite or the reason is not valid.
	std::optional<nlohmann::json> make_vteleported(int32_t id, bool ok, std::string_view reason, Vec3 feetCp, double off);
	/// {"t":"parked","on","ok","reason"}: the answer to `park` `on`; the reason rule of make_vteleported. nullopt when a
	/// failure has no valid reason.
	std::optional<nlohmann::json> make_parked(bool on, bool ok, std::string_view reason);
	/// The `parked` answer a Park.reds log line stands for (3S names no native for it; its lines are the contract):
	/// "park: on refused (<why>)" -> on, failed, "blocked (<why>)"; "park: on (...)" -> on, ok; "park: off (...)" -> off,
	/// ok. nullopt for any other line.
	std::optional<nlohmann::json> parked_from_log(std::string_view line);
}
