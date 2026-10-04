package dev.cyberblock.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import dev.cyberblock.content.ContentKind;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Which Minecraft block a Night City device becomes (megaplan section 1.11, content kind {@code device_map} of section 1.7, 4A's
 * schema shared/content-schemas/device_map.schema.json). Phase 0 (c) gives no device size, so the box comes from here too.
 *
 * <p>A file maps one Cyberpunk class: {@code {"schema":1, "class", "kind", "block", "size":[w, h, d], "alt_block"?, "alt_when"?:
 * "secure"|"readable", "entity"?, "attachments"?: [{"block", "offset":[dx, dy, dz], "when"?: "always"|"locked"}], "shops"?: [ids]}}.
 * A class with no file takes its kind's built-in default below (so every kind works with no content at all). {@code entity} (a glow
 * item frame on screens) is read but not spawned: the screen block stands for it.
 */
public final class DeviceMap {
	/** An extra block at an offset from the device's first cell (dx across the facing, dy up, dz along it); lockedOnly: while locked. */
	public record Attachment(String block, int dx, int dy, int dz, boolean lockedOnly) {
	}

	/** One mapping. altBlock replaces block while the device is secure (altWhen "secure"); "readable" never holds in Phase 4. */
	public record Entry(DeviceType type, @Nullable String cls, String block, @Nullable String altBlock, @Nullable String altWhen, int w, int h, int d,
		List<Attachment> attachments, List<String> shops) {
		/** The block for this device now. */
		public String blockFor(final Device dev) {
			return this.altBlock != null && "secure".equals(this.altWhen) && dev.secure() ? this.altBlock : this.block;
		}
	}

	static final int MAX_SIZE = 16, MAX_OFFSET = 16, MAX_ATTACHMENTS = 4, MAX_SHOPS = 8;
	/** The Cyberpunk classes of the Phase 0 (c) evidence and the section 1.9 amendment's note, with their type. */
	private static final Map<String, DeviceType> CLASSES = Map.ofEntries(Map.entry("door", DeviceType.DOOR),
		Map.entry("securitygate", DeviceType.GATE), Map.entry("roadblock", DeviceType.GATE), Map.entry("electriclight", DeviceType.LIGHT),
		Map.entry("interactivesign", DeviceType.LIGHT), Map.entry("billboarddevice", DeviceType.SCREEN), Map.entry("tv", DeviceType.SCREEN),
		Map.entry("surveillancecamera", DeviceType.CAMERA), Map.entry("terminal", DeviceType.TERMINAL), Map.entry("computer", DeviceType.TERMINAL),
		Map.entry("vendingmachine", DeviceType.VENDING), Map.entry("weaponvendingmachine", DeviceType.VENDING), Map.entry("icemachine", DeviceType.VENDING),
		Map.entry("trafficlight", DeviceType.TRAFFIC), Map.entry("crossinglight", DeviceType.TRAFFIC), Map.entry("liftdevice", DeviceType.ELEVATOR),
		Map.entry("securityturret", DeviceType.TURRET), Map.entry("gamecontainerobjectbase", DeviceType.CONTAINER));
	/** The kind defaults with no content (the same blocks and sizes as 4A's default files). */
	private static final Map<DeviceType, Entry> BUILT_IN = new EnumMap<>(DeviceType.class);
	private static final List<String> VENDING_SHOPS = List.of("cyberblock:vending_snacks", "cyberblock:vending_drinks", "cyberblock:vending_gear");

	static {
		BUILT_IN.put(DeviceType.DOOR, new Entry(DeviceType.DOOR, null, "minecraft:oak_door", "minecraft:iron_door", "secure", 1, 2, 1,
			List.of(new Attachment(DeviceBlocks.LOCK_LAMP, 0, 2, 0, true)), List.of()));
		builtIn(DeviceType.GATE, DeviceBlocks.GATE, 3, 2, 1);
		builtIn(DeviceType.LIGHT, DeviceBlocks.LAMP, 1, 1, 1);
		builtIn(DeviceType.CAMERA, DeviceBlocks.CAMERA, 1, 1, 1);
		builtIn(DeviceType.TERMINAL, DeviceBlocks.NET_PORT, 1, 1, 1);
		BUILT_IN.put(DeviceType.VENDING, new Entry(DeviceType.VENDING, null, DeviceBlocks.VENDING, null, null, 1, 1, 1, List.of(), VENDING_SHOPS));
		builtIn(DeviceType.TRAFFIC, DeviceBlocks.TRAFFIC, 1, 1, 1);
		BUILT_IN.put(DeviceType.ELEVATOR, new Entry(DeviceType.ELEVATOR, null, DeviceBlocks.LIFT, null, null, 2, 1, 2,
			List.of(new Attachment(DeviceBlocks.LIFT_BUTTON, 0, 1, 0, false)), List.of()));
		builtIn(DeviceType.TURRET, DeviceBlocks.TURRET, 1, 1, 1);
		builtIn(DeviceType.SCREEN, DeviceBlocks.SCREEN, 2, 1, 1);
		builtIn(DeviceType.CONTAINER, DeviceBlocks.CRATE, 1, 1, 1);
	}

	private static void builtIn(final DeviceType type, final String block, final int w, final int h, final int d) {
		BUILT_IN.put(type, new Entry(type, null, block, null, null, w, h, d, List.of(), List.of()));
	}

	private static @Nullable ContentKind<Entry> kind;
	/** The class table last built and the content snapshot it came from (content is replaced whole on /reload). */
	private static volatile Tables tables = Tables.build(Map.of());

	private record Tables(Map<Identifier, Entry> from, Map<String, Entry> byClass) {
		static Tables build(final Map<Identifier, Entry> entries) {
			Map<String, Entry> byClass = new HashMap<>();
			// sorted by id (Content keeps them sorted): the last file of a class wins, so a friend's namespace can override it
			for (Entry e : entries.values()) {
				byClass.put(e.cls().toLowerCase(Locale.ROOT), e);
			}

			return new Tables(entries, Map.copyOf(byClass));
		}
	}

	private DeviceMap() {
	}

	/** Common init, once (before the server loads its datapacks). Server only: clients get the blocks, not the map. */
	static void register() {
		kind = ContentKind.register("device_map", DeviceMap::parse, false);
	}

	static Entry parse(final Identifier id, final JsonObject json) {
		String cls = Content.string(json, "class", DeviceWire.CLS_CAP);
		if (cls.isEmpty()) {
			throw new IllegalArgumentException("class is 1 to 48 characters");
		}

		DeviceType type = DeviceType.byId(Content.string(json, "kind", 16));
		if (type == null) {
			throw new IllegalArgumentException("kind must be one of door, gate, light, camera, terminal, vending, traffic, elevator, turret, screen, container");
		}

		String block = id(json, "block");
		String alt = json.has("alt_block") ? id(json, "alt_block") : null;
		String when = json.has("alt_when") ? Content.string(json, "alt_when", 16) : null;
		if (when != null && !when.equals("secure") && !when.equals("readable") || (alt == null) != (when == null)) {
			throw new IllegalArgumentException("alt_block and alt_when come together; alt_when is secure or readable");
		}

		if (json.has("entity")) {
			id(json, "entity");
		}

		int[] size = ints(json.get("size"), 3, 1, MAX_SIZE, "size must be [w, h, d], whole numbers from 1 to " + MAX_SIZE);
		List<Attachment> attachments = new ArrayList<>();
		if (json.has("attachments")) {
			if (!(json.get("attachments") instanceof JsonArray a) || a.size() > MAX_ATTACHMENTS) {
				throw new IllegalArgumentException("attachments must be an array of at most " + MAX_ATTACHMENTS);
			}

			for (JsonElement e : a) {
				if (!(e instanceof JsonObject o)) {
					throw new IllegalArgumentException("an attachment is {block, offset, when}");
				}

				int[] off = ints(o.get("offset"), 3, -MAX_OFFSET, MAX_OFFSET, "offset must be [dx, dy, dz] within " + MAX_OFFSET);
				String w = o.has("when") ? Content.string(o, "when", 16) : "always";
				if (!w.equals("always") && !w.equals("locked")) {
					throw new IllegalArgumentException("when is always or locked");
				}

				attachments.add(new Attachment(id(o, "block"), off[0], off[1], off[2], w.equals("locked")));
			}
		}

		List<String> shops = new ArrayList<>();
		if (json.has("shops")) {
			if (!(json.get("shops") instanceof JsonArray a) || a.isEmpty() || a.size() > MAX_SHOPS) {
				throw new IllegalArgumentException("shops must list 1 to " + MAX_SHOPS + " shop ids");
			}

			for (JsonElement e : a) {
				String shop = e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "";
				if (shop.length() > 128 || Identifier.tryParse(shop) == null || !shop.contains(":")) {
					throw new IllegalArgumentException("shops must hold shop ids like cyberblock:vending_snacks");
				}

				shops.add(shop);
			}
		}

		return new Entry(type, cls, block, alt, when, size[0], size[1], size[2], List.copyOf(attachments), List.copyOf(shops));
	}

	private static String id(final JsonObject json, final String key) {
		String s = Content.string(json, key, 128);
		if (Identifier.tryParse(s) == null || !s.contains(":")) {
			throw new IllegalArgumentException(key + " must be an id like minecraft:oak_door");
		}

		return s;
	}

	private static int[] ints(final @Nullable JsonElement e, final int n, final int min, final int max, final String why) {
		if (!(e instanceof JsonArray a) || a.size() != n) {
			throw new IllegalArgumentException(why);
		}

		int[] out = new int[n];
		for (int i = 0; i < n; i++) {
			JsonElement v = a.get(i);
			double d = v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : Double.NaN;
			if (!(d >= min && d <= max) || d != Math.rint(d)) {
				throw new IllegalArgumentException(why);
			}

			out[i] = (int) d;
		}

		return out;
	}

	private static Tables tables() {
		Tables t = tables;
		if (kind == null) {
			return t; // before register (tests)
		}

		Map<Identifier, Entry> now = Content.all(kind);
		if (t.from() != now) {
			t = Tables.build(now);
			tables = t;
		}

		return t;
	}

	/** Tests (before register): the tables from these entries instead of the loaded content. */
	static void use(final Map<Identifier, Entry> entries) {
		tables = Tables.build(entries);
	}

	/** How many device_map files the content holds (0: the built-in table alone). */
	static int contentEntries() {
		return tables().from().size();
	}

	/** The type of a Cyberpunk class: its device_map file, else the built-in class table; null when neither knows it. */
	public static @Nullable DeviceType typeOf(final String cls) {
		String c = cls.toLowerCase(Locale.ROOT);
		Entry e = tables().byClass().get(c);
		return e != null ? e.type() : CLASSES.get(c);
	}

	/** The mapping of one device: its class's file when it is of the same type, else the type's built-in default. */
	public static Entry entry(final String cls, final DeviceType type) {
		Entry e = tables().byClass().get(cls.toLowerCase(Locale.ROOT));
		return e != null && e.type() == type ? e : BUILT_IN.get(type);
	}
}
