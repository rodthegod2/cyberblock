package dev.cyberblock.hack.breach;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import dev.cyberblock.content.ContentKind;
import dev.cyberblock.device.Device;
import dev.cyberblock.hack.breach.BreachPuzzle.DaemonDef;
import dev.cyberblock.hack.breach.BreachPuzzle.Tier;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Breach content as data (megaplan section 1.7, kinds {@code daemon} and {@code breach_tier}; 6A's schemas in
 * {@code shared/content-schemas/} and default files). Server only: a client gets one puzzle at a time through {@code cyberblock:ui
 * breach}.
 * <ul>
 * <li>{@code daemon}: {@code {"schema":1, "title_key", "text_key", "sequence":["BD","1C"] (2-4 codes), "kinds":[device kinds], "action",
 * "tiers":[3 x {"seconds","radius"}], "weight"?:1-10, "min_security"?:1-3}}. The daemon's wire id (the {@code daemon} DOWN's
 * {@code list}, the {@code breach} screen's {@code seqs.id}) is its {@code action}: protocol.md's ids are {@code [a-z0-9_]} and the
 * scripts run actions, so a datapack daemon that re-skins an action works without a script change. The {@code tiers} (seconds and radius
 * per strength) are checked here and belong to the scripts' side of each action.</li>
 * <li>{@code breach_tier}: {@code {"schema":1, "security":1-3, "grid":5-7, "buffer":4-8, "seconds":10-300, "daemons_min", "daemons_max":1-3,
 * "firewall":[3 x {"grid_add":0-2, "buffer_add":-3-0, "seconds_add":-120-0, "daemons_add":0-1}]}}: the breach for a device of that
 * security (1 ordinary, 2 secure, 3 hardened), and what the defender's Firewall rank 1-3 adds (the grid never past 7, the buffer never
 * under 4, the timer never under 10 s, the daemon range's top never past 3).</li>
 * </ul>
 * Without content (no files, or none for a kind) the built-in tables below apply (6A's default values); {@link #source} says which.
 */
public final class BreachContent {
	/** Phase 8's counterplay breach (megaplan 8-X1): 4x4, buffer 4, one daemon, 5 s; fixed, not content. */
	public static final Tier SHORT = new Tier(4, 4, 1, 1, 5);
	static final List<RankedTier> BUILT_IN_TIERS = List.of(
		new RankedTier(1, new Tier(5, 7, 1, 2, 60), List.of(new Step(1, 0, -5, 0), new Step(1, 0, -10, 0), new Step(2, -1, -15, 1))),
		new RankedTier(2, new Tier(6, 6, 1, 3, 50), List.of(new Step(1, 0, -5, 0), new Step(1, -1, -10, 0), new Step(1, -1, -15, 0))),
		new RankedTier(3, new Tier(7, 5, 2, 3, 45), List.of(new Step(0, 0, -5, 0), new Step(0, -1, -10, 0), new Step(0, -1, -15, 0))));
	static final List<Entry> BUILT_IN_DAEMONS = List.of(
		new Entry(new DaemonDef("open_door", codes("BD", "1C"), 6), Set.of("door", "gate", "terminal", "container"), 1),
		new Entry(new DaemonDef("disable_cameras", codes("55", "E9", "7A"), 6), Set.of("camera", "terminal", "screen"), 1),
		new Entry(new DaemonDef("reveal_enemies", codes("1C", "55"), 5), Set.of("terminal", "camera", "screen", "vending"), 1),
		new Entry(new DaemonDef("overload", codes("FF", "7A", "E9"), 3), Set.of("terminal", "light", "vending", "turret", "screen", "traffic"), 2));
	private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
	private static final Pattern KEY = Pattern.compile("[a-z0-9_.]{1,96}");
	private static @Nullable ContentKind<Entry> daemons;
	private static @Nullable ContentKind<RankedTier> tiers;

	/** A daemon file: the daemon, the device kinds it is offered for and the lowest security that offers it. */
	public record Entry(DaemonDef daemon, Set<String> kinds, int minSecurity) {
		boolean offeredFor(final String kind, final int security) {
			return this.kinds.contains(kind) && security >= this.minSecurity;
		}
	}

	/** One Firewall rank's change to a tier. */
	public record Step(int gridAdd, int bufferAdd, int secondsAdd, int daemonsAdd) {
	}

	/** A breach_tier file: the tier for this security level and its three Firewall steps. */
	public record RankedTier(int security, Tier tier, List<Step> firewall) {
		/** The tier with the defender's Firewall rank (0: none; 1-3; higher counts as 3) applied. */
		Tier with(final int firewallRank) {
			if (firewallRank <= 0) {
				return this.tier;
			}

			Step s = this.firewall.get(Math.min(firewallRank, 3) - 1);
			int max = Math.min(3, this.tier.daemonsMax() + s.daemonsAdd());
			return new Tier(Math.min(7, this.tier.grid() + s.gridAdd()), Math.max(4, this.tier.buffer() + s.bufferAdd()), Math.min(this.tier.daemonsMin(), max),
				max, Math.max(10, this.tier.seconds() + s.secondsAdd()));
		}
	}

	private BreachContent() {
	}

	/** Common init, once, before the server loads its datapacks. */
	static void register() {
		daemons = ContentKind.register("daemon", BreachContent::parseDaemon, false);
		tiers = ContentKind.register("breach_tier", BreachContent::parseTier, false);
	}

	/** Device security as breach_tier counts it: 1 ordinary, 2 secure (fl bit 2), 3 hardened (secure and locked). */
	public static int security(final Device d) {
		return !d.secure() ? 1 : d.locked() ? 3 : 2;
	}

	static Entry parseDaemon(final Identifier id, final JsonObject json) {
		key(json, "title_key");
		key(json, "text_key");
		String action = Content.string(json, "action", 32);
		if (!ID.matcher(action).matches()) {
			throw new IllegalArgumentException("action must be 1-32 of a-z, 0-9 and _");
		}

		List<String> sequence = strings(json, "sequence", 2, 4);
		int[] seq = new int[sequence.size()];
		for (int i = 0; i < seq.length; i++) {
			seq[i] = BreachPuzzle.CODES.indexOf(sequence.get(i));
			if (seq[i] < 0) {
				throw new IllegalArgumentException("sequence holds the codes " + String.join(", ", BreachPuzzle.CODES));
			}
		}

		List<String> kinds = strings(json, "kinds", 1, 11);
		for (String k : kinds) {
			if (DeviceType.byId(k) == null) {
				throw new IllegalArgumentException("kinds holds device kinds (section 1.11), not " + k);
			}
		}

		if (!(json.get("tiers") instanceof JsonArray t) || t.size() != 3) {
			throw new IllegalArgumentException("tiers must be three rows (strength 1, 2, 3)");
		}

		for (JsonElement row : t) {
			if (!(row instanceof JsonObject o)) {
				throw new IllegalArgumentException("a tier row is {seconds, radius}");
			}

			whole(o, "seconds", 0, 600);
			whole(o, "radius", 1, 150);
		}

		int weight = json.has("weight") ? whole(json, "weight", 1, 10) : 1;
		int minSecurity = json.has("min_security") ? whole(json, "min_security", 1, 3) : 1;
		return new Entry(new DaemonDef(action, seq, weight), Set.copyOf(kinds), minSecurity);
	}

	static RankedTier parseTier(final Identifier id, final JsonObject json) {
		int min = whole(json, "daemons_min", 1, 3), max = whole(json, "daemons_max", 1, 3);
		if (min > max) {
			throw new IllegalArgumentException("daemons_min must not be above daemons_max");
		}

		Tier tier = new Tier(whole(json, "grid", 5, 7), whole(json, "buffer", 4, 8), min, max, whole(json, "seconds", 10, 300));
		if (!(json.get("firewall") instanceof JsonArray f) || f.size() != 3) {
			throw new IllegalArgumentException("firewall must be three steps (Firewall rank 1, 2, 3)");
		}

		List<Step> steps = new ArrayList<>();
		for (JsonElement e : f) {
			if (!(e instanceof JsonObject s)) {
				throw new IllegalArgumentException("a firewall step is {grid_add, buffer_add, seconds_add, daemons_add}");
			}

			steps.add(new Step(whole(s, "grid_add", 0, 2), whole(s, "buffer_add", -3, 0), whole(s, "seconds_add", -120, 0), whole(s, "daemons_add", 0, 1)));
		}

		return new RankedTier(whole(json, "security", 1, 3), tier, List.copyOf(steps));
	}

	private static void key(final JsonObject json, final String key) {
		if (!KEY.matcher(Content.string(json, key, 96)).matches()) {
			throw new IllegalArgumentException(key + " must be a lang key (a-z, 0-9, _ and .)");
		}
	}

	private static List<String> strings(final JsonObject json, final String key, final int min, final int max) {
		if (!(json.get(key) instanceof JsonArray a) || a.size() < min || a.size() > max) {
			throw new IllegalArgumentException(key + " must be an array of " + min + " to " + max + " strings");
		}

		List<String> out = new ArrayList<>();
		for (JsonElement e : a) {
			if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
				throw new IllegalArgumentException(key + " must hold strings");
			}

			out.add(e.getAsString());
		}

		return out;
	}

	private static int whole(final JsonObject json, final String key, final int min, final int max) {
		JsonElement e = json.get(key);
		double v = e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : Double.NaN;
		if (!(v >= min && v <= max) || v != Math.rint(v)) {
			throw new IllegalArgumentException(key + " must be a whole number from " + min + " to " + max);
		}

		return (int) v;
	}

	private static int[] codes(final String... codes) {
		return Arrays.stream(codes).mapToInt(BreachPuzzle.CODES::indexOf).toArray();
	}

	/**
	 * The daemons offered for a device of this kind and security: the content's when any file offers one, else the built-ins', else (a
	 * kind nothing is written for, or Phase 8's players) the terminal's. Never empty. Pure.
	 */
	static List<DaemonDef> daemonsFor(final String kind, final int security, final Collection<Entry> content) {
		for (Collection<Entry> source : List.of(content, BUILT_IN_DAEMONS)) {
			List<DaemonDef> out = source.stream().filter(e -> e.offeredFor(kind, security)).map(Entry::daemon).toList();
			if (!out.isEmpty()) {
				return out;
			}
		}

		return daemonsFor("terminal", 1, content); // the built-ins always offer terminal daemons at security 1
	}

	/**
	 * The tier for a security level with the defender's Firewall rank: of the content tiers, the one with the highest {@code security} not
	 * above the level (the lowest one when all are above it); the built-ins without content. Pure.
	 */
	static Tier tierFor(final int security, final int firewallRank, final Collection<RankedTier> content) {
		Collection<RankedTier> source = content.isEmpty() ? BUILT_IN_TIERS : content;
		RankedTier best = null, lowest = null;
		for (RankedTier t : source) {
			if (t.security() <= security && (best == null || t.security() > best.security())) {
				best = t;
			}

			if (lowest == null || t.security() < lowest.security()) {
				lowest = t;
			}
		}

		return (best != null ? best : lowest).with(firewallRank);
	}

	/** The loaded daemon files (empty before the first load or without the kind). */
	static Collection<Entry> daemonFiles() {
		ContentKind<Entry> k = daemons;
		return k == null ? List.of() : Content.all(k).values();
	}

	static Collection<RankedTier> tierFiles() {
		ContentKind<RankedTier> k = tiers;
		return k == null ? List.of() : Content.all(k).values();
	}

	public static List<DaemonDef> daemonsFor(final String kind, final int security) {
		return daemonsFor(kind, security, daemonFiles());
	}

	public static Tier tierFor(final int security, final int firewallRank) {
		return tierFor(security, firewallRank, tierFiles());
	}

	/** For the log lines: where the daemons and tiers come from now. */
	static String source() {
		int d = daemonFiles().size(), t = tierFiles().size();
		return "daemons " + (d > 0 ? d + " files" : "built-in") + ", tiers " + (t > 0 ? t + " files" : "built-in");
	}
}
