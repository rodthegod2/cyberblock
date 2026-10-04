package dev.cyberblock.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.cyberblock.Cyberblock;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The server's state for one host (megaplan section 1.4), keyed by the player's UUID. Created when the handshake grants the host
 * role (state NONE until the host's hello arrives), LINKED after each host hello, ACTIVE while {@code hstate.active}. The hstate,
 * aim and stats fields are applied by HostSessions before any listener runs, so listeners always read the message's values. DOWN
 * messages queue here and go out at the end of the server tick. Server thread only.
 */
public final class HostSession {
	public enum State {
		/** The host role is granted; no host hello yet (nothing is sent DOWN). */
		NONE,
		/** The host's Cyberblock.dll said hello; Cyberpunk is Off or not streaming. */
		LINKED,
		/** Cyberpunk is On and streaming (hstate.active). */
		ACTIVE
	}

	private final UUID uuid;
	private final String name;
	private final @Nullable MinecraftServer server;
	private final Consumer<String> wire;
	private final DownQueue down = new DownQueue();
	private final RequestIds requests = new RequestIds();
	private State state = State.NONE;
	private String mod = "", dll = "";
	/** When this session became ACTIVE (a sequence number; the earliest active session is the primary), 0 when not active. */
	private long activeSince;
	private boolean active, vehicle, airborne, crouching, held, flying, launching, seated;
	private int levelK;
	private double feetCpZ = Double.NaN;
	private double aim = -1.0;
	private long money;
	private int wanted;
	private String district = "";
	private int[] time = {0, 0};
	private long upCount, downCount, droppedDown;
	private long sampledUp, sampledDown;
	private double upRate, downRate;

	/**
	 * @param server the server (null in tests: player() is then null)
	 * @param wire where flushed DOWN messages go (ServerNet sends cyberblock:down to the player)
	 */
	HostSession(final UUID uuid, final String name, final @Nullable MinecraftServer server, final Consumer<String> wire) {
		this.uuid = uuid;
		this.name = name;
		this.server = server;
		this.wire = wire;
	}

	public UUID uuid() {
		return this.uuid;
	}

	/** The player's name (for logs and commands). */
	public String name() {
		return this.name;
	}

	/** The host's player right now (a respawn makes a new ServerPlayer), or null when it is not online. */
	public @Nullable ServerPlayer player() {
		return this.server == null ? null : this.server.getPlayerList().getPlayer(this.uuid);
	}

	public State state() {
		return this.state;
	}

	public boolean active() {
		return this.state == State.ACTIVE;
	}

	/** V is in a vehicle (hstate/state.vehicle). */
	public boolean vehicle() {
		return this.vehicle;
	}

	/** V is in the air (hstate.air). */
	public boolean airborne() {
		return this.airborne;
	}

	/** V is crouched (hstate.cr). */
	public boolean crouching() {
		return this.crouching;
	}

	/** The game holds V: a scene, workspot, braindance or moving elevator (hstate.held). */
	public boolean held() {
		return this.held;
	}

	/** Steve glides (hstate.fly). */
	public boolean flying() {
		return this.flying;
	}

	/** Steve is launched: Minecraft's physics move him (hstate.launch; was Cyberblock.launching). */
	public boolean launching() {
		return this.launching;
	}

	/** V sits (hstate.seat). */
	public boolean seated() {
		return this.seated;
	}

	/** V stands still on the ground: not in the air, not held, not gliding (was Bridge.hostStands). */
	public boolean stands() {
		return !this.airborne && !this.held && !this.flying;
	}

	/** V's feet in Cyberpunk z (hstate.feetCpZ), NaN when unknown. */
	public double feetCpZ() {
		return this.feetCpZ;
	}

	/** The host's aim distance (aim.d), negative for no limit. */
	public double aim() {
		return this.aim;
	}

	/** The ground alignment k the host converted with (hstate.lv, 0..63). */
	public int levelK() {
		return this.levelK;
	}

	public long money() {
		return this.money;
	}

	public int wanted() {
		return this.wanted;
	}

	public String district() {
		return this.district;
	}

	/** [hour, minute] of the host's Night City. */
	public int[] time() {
		return this.time.clone();
	}

	/** What guests may do to this host's game. */
	public Permissions perms() {
		return Permissions.of(this.uuid);
	}

	/** The client's mod version and its DLL's version from the last hs. */
	public String mod() {
		return this.mod;
	}

	public String dll() {
		return this.dll;
	}

	void versions(final String mod, final String dll) {
		this.mod = mod;
		this.dll = dll;
	}

	/** DOWN to this host's DLL, sent at the end of the tick; dropped unless LINKED or ACTIVE, or when over the down cap. */
	public void send(final String json) {
		if (this.state == State.NONE) {
			this.droppedDown++;
			return;
		}

		if (!Payloads.fits(json, Payloads.DOWN_CAP)) {
			this.droppedDown++;
			Cyberblock.LOG.warn("session: {} down {} over the cap: dropped", this.name, DownQueue.typeOf(json));
			return;
		}

		this.down.offer(json);
	}

	/** A request id routed back to the asker (scan, hack, devact, spawn). */
	public int newRequest(final UUID asker) {
		return this.requests.issue(asker);
	}

	/** Who asked for the request id; the last 256 ids are remembered. */
	public @Nullable UUID asker(final int requestId) {
		return this.requests.asker(requestId);
	}

	RequestIds requests() {
		return this.requests;
	}

	/**
	 * The DOWN queue overflowed since the last call, so this session's blocks need a resync (the world track checks it each tick).
	 * Reading it clears it.
	 */
	public boolean takeResync() {
		return this.down.takeResync();
	}

	/** End of the server tick: every queued DOWN message goes out in order. */
	void flush() {
		if (this.state == State.NONE) {
			this.down.clear();
			return;
		}

		this.down.drain(json -> {
			this.downCount++;
			this.wire.accept(json);
		});
	}

	int queued() {
		return this.down.size();
	}

	long upCount() {
		return this.upCount;
	}

	long downCount() {
		return this.downCount;
	}

	long droppedDown() {
		return this.droppedDown + this.down.dropped();
	}

	void countUp() {
		this.upCount++;
	}

	/** Every few seconds: messages per second up and down since the last sample (for /cyberblock hosts). */
	void sampleRates(final double seconds) {
		this.upRate = (this.upCount - this.sampledUp) / seconds;
		this.downRate = (this.downCount - this.sampledDown) / seconds;
		this.sampledUp = this.upCount;
		this.sampledDown = this.downCount;
	}

	double upRate() {
		return this.upRate;
	}

	double downRate() {
		return this.downRate;
	}

	long activeSince() {
		return this.activeSince;
	}

	/** A host hello: LINKED (or still ACTIVE). Returns false for a hello of another protocol. */
	boolean applyHello(final JsonObject m) {
		JsonElement proto = m.get("proto");
		if (proto != null && !(proto.isJsonPrimitive() && proto.getAsJsonPrimitive().isNumber() && proto.getAsInt() == Payloads.PROTO)) {
			return false;
		}

		if (this.state == State.NONE) {
			this.state = State.LINKED;
		}

		return true;
	}

	/**
	 * hstate {active, vehicle, air, cr, held, fly, launch, seat, lv, feetCpZ}: booleans (absent = false), lv 0..63, feetCpZ finite or
	 * absent. Returns the state change (null when none), or throws IllegalArgumentException for a bad message (nothing applied).
	 */
	@Nullable State applyHstate(final JsonObject m, final long sequence) {
		boolean a = bool(m, "active"), v = bool(m, "vehicle"), air = bool(m, "air"), cr = bool(m, "cr"), h = bool(m, "held"), f = bool(m, "fly"),
			l = bool(m, "launch"), s = bool(m, "seat");
		int lv = m.has("lv") ? integer(m, "lv", 0, 63) : 0;
		double z = Double.NaN;
		if (m.has("feetCpZ") && !m.get("feetCpZ").isJsonNull()) {
			z = number(m, "feetCpZ");
		}

		this.vehicle = v;
		this.airborne = air;
		this.crouching = cr;
		this.held = h;
		this.flying = f;
		this.launching = l;
		this.seated = s;
		this.levelK = lv;
		this.feetCpZ = z;
		return this.activate(a, sequence);
	}

	/** state {active, vehicle}. */
	@Nullable State applyState(final JsonObject m, final long sequence) {
		boolean a = bool(m, "active"), v = bool(m, "vehicle");
		this.vehicle = v;
		return this.activate(a, sequence);
	}

	/** aim {d}: -1..64, negative = no limit. */
	void applyAim(final JsonObject m) {
		double d = number(m, "d");
		if (d < -1.0 || d > 64.0) {
			throw new IllegalArgumentException("aim.d out of range: " + d);
		}

		this.aim = d < 0.0 ? -1.0 : d;
	}

	/** stats {money >= 0, wanted 0..5, district <= 64, time [h 0..23, m 0..59]}. */
	void applyStats(final JsonObject m) {
		double money = number(m, "money");
		if (money < 0 || money != Math.rint(money)) {
			throw new IllegalArgumentException("stats.money must be a whole number >= 0");
		}

		int wanted = integer(m, "wanted", 0, 5);
		JsonElement d = m.get("district");
		if (d == null || !d.isJsonPrimitive() || !d.getAsJsonPrimitive().isString() || d.getAsString().length() > 64) {
			throw new IllegalArgumentException("stats.district must be a string of at most 64 characters");
		}

		JsonElement t = m.get("time");
		if (t == null || !t.isJsonArray() || t.getAsJsonArray().size() != 2) {
			throw new IllegalArgumentException("stats.time must be [h, m]");
		}

		JsonArray ta = t.getAsJsonArray();
		int hour = whole(ta.get(0), 0, 23), minute = whole(ta.get(1), 0, 59);
		this.money = (long) money;
		this.wanted = wanted;
		this.district = d.getAsString();
		this.time = new int[] {hour, minute};
	}

	/** LINKED <-> ACTIVE (a NONE session stays NONE until its hello). */
	private @Nullable State activate(final boolean on, final long sequence) {
		this.active = on;
		if (this.state == State.LINKED && on) {
			this.state = State.ACTIVE;
			this.activeSince = sequence;
			return State.ACTIVE;
		}

		if (this.state == State.ACTIVE && !on) {
			this.state = State.LINKED;
			this.activeSince = 0;
			return State.LINKED;
		}

		return null;
	}

	/** After a hello: an active flag reported while NONE applies now. */
	@Nullable State reactivate(final long sequence) {
		return this.activate(this.active, sequence);
	}

	private static boolean bool(final JsonObject m, final String key) {
		JsonElement e = m.get(key);
		if (e == null || e.isJsonNull()) {
			return false;
		}

		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException(key + " must be true or false");
		}

		return e.getAsBoolean();
	}

	private static double number(final JsonObject m, final String key) {
		JsonElement e = m.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(key + " must be a number");
		}

		double v = e.getAsDouble();
		if (!Double.isFinite(v)) {
			throw new IllegalArgumentException(key + " must be finite");
		}

		return v;
	}

	private static int integer(final JsonObject m, final String key, final int min, final int max) {
		JsonElement e = m.get(key);
		if (e == null) {
			throw new IllegalArgumentException(key + " is missing");
		}

		return whole(e, min, max);
	}

	private static int whole(final JsonElement e, final int min, final int max) {
		if (!e.isJsonPrimitive() || !((JsonPrimitive) e).isNumber()) {
			throw new IllegalArgumentException("not a number: " + e);
		}

		double v = e.getAsDouble();
		if (!(v >= min && v <= max) || v != Math.rint(v)) {
			throw new IllegalArgumentException(v + " is not a whole number from " + min + " to " + max);
		}

		return (int) v;
	}
}
