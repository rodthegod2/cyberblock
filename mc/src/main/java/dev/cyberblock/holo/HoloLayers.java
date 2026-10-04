package dev.cyberblock.holo;

import dev.cyberblock.ProxyEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.entity.EntityTypeTest;
import org.jspecify.annotations.Nullable;

/**
 * The Holo-Table's optional layers (megaplan 7-T1): each a provider that lists items (a spot, a weight 0..1, a colour, a label) for
 * one viewer. This package fills {@code npcs} (NPC density: Night City figures per 64-block square) and {@code hotspots} (where
 * hostile figures gather), both counting only the figures of hosts whose pins the viewer may see; the rest are hooks with a fixed id that later phases fill with {@link #register}: {@code devices}
 * (Phase 4's device registry), {@code heat} (8-T1 Server Heat per district), {@code events} (8-O1), {@code gangwars} (8-M1
 * territory), {@code matches} (8-M1), {@code drones} (8-V1 Van drones). A layer without a provider is not sent; the client shows a
 * toggle for every layer it gets. Server thread.
 */
public final class HoloLayers {
	/** Every layer id the table knows, in toggle order. */
	public static final List<String> IDS = List.of("npcs", "hotspots", "devices", "heat", "events", "gangwars", "matches", "drones");
	/** Square size (blocks) of the density layers. */
	static final int SQUARE = 64;
	private static final Map<String, BiFunction<MinecraftServer, ServerPlayer, List<HoloWire.LayerItem>>> providers = new LinkedHashMap<>();

	private HoloLayers() {
	}

	/** Phase 4/8: sets (or replaces) the provider of one layer; it runs for each viewer once a second, so keep it cheap. */
	public static void register(final String id, final BiFunction<MinecraftServer, ServerPlayer, List<HoloWire.LayerItem>> provider) {
		if (!IDS.contains(id)) {
			throw new IllegalArgumentException("unknown Holo-Table layer " + id + " (add it to HoloLayers.IDS)");
		}

		providers.put(id, provider);
	}

	/**
	 * NPC figures exist only around active hosts (each host streams its own surroundings), so their squares would say where a host is:
	 * a viewer counts only the figures of the hosts whose pins it may see (HoloModule.visible: not a hidden pin, not a match it may
	 * not see). Figures without an owner are left out too.
	 */
	static void registerBuiltIn() {
		register("npcs", (server, viewer) -> density(seenBy(figures(server.overworld()), owners(server, viewer)), false));
		register("hotspots", (server, viewer) -> density(seenBy(figures(server.overworld()), owners(server, viewer)), true));
	}

	private static Set<UUID> owners(final MinecraftServer server, final ServerPlayer viewer) {
		Set<UUID> out = new HashSet<>();
		for (ServerPlayer p : HoloModule.visible(server, viewer)) {
			out.add(p.getUUID());
		}

		return out;
	}

	/** The figures whose owner is one of {@code owners}. Pure. */
	static List<Figure> seenBy(final List<Figure> figures, final Set<UUID> owners) {
		return figures.stream().filter(f -> f.owner() != null && owners.contains(f.owner())).toList();
	}

	/** The layers for one viewer, in {@link #IDS} order; a provider that fails is skipped (and logged by the caller). */
	static List<HoloWire.Layer> all(final MinecraftServer server, final ServerPlayer viewer) {
		List<HoloWire.Layer> out = new ArrayList<>();
		for (String id : IDS) {
			var provider = providers.get(id);
			if (provider != null) {
				out.add(new HoloWire.Layer(id, List.copyOf(provider.apply(server, viewer))));
			}
		}

		return out;
	}

	/** A live figure: its position, whether it is hostile, and the host whose game it is in (null when unknown). */
	record Figure(double x, double z, boolean hostile, @Nullable UUID owner) {
	}

	private static List<Figure> figures(final @Nullable ServerLevel level) {
		List<Figure> out = new ArrayList<>();
		if (level != null) {
			for (ProxyEntity p : level.getEntities(EntityTypeTest.forClass(ProxyEntity.class), p -> !p.dead())) {
				out.add(new Figure(p.getX(), p.getZ(), p.hostile(), p.owner()));
			}
		}

		return out;
	}

	/**
	 * Figures counted per 64-block square (only hostile ones for hot-spots), one item per square at its centre with the weight
	 * count / the busiest square's count. Pure.
	 */
	static List<HoloWire.LayerItem> density(final List<Figure> figures, final boolean hostileOnly) {
		Map<Long, int[]> counts = new HashMap<>();
		int max = 0;
		for (Figure f : figures) {
			if (hostileOnly && !f.hostile()) {
				continue;
			}

			long key = ((long) Math.floorDiv((int) Math.floor(f.x()), SQUARE) << 32) | (Math.floorDiv((int) Math.floor(f.z()), SQUARE) & 0xFFFFFFFFL);
			int n = ++counts.computeIfAbsent(key, k -> new int[1])[0];
			max = Math.max(max, n);
		}

		List<HoloWire.LayerItem> out = new ArrayList<>();
		for (Map.Entry<Long, int[]> e : counts.entrySet()) {
			double x = ((int) (e.getKey() >> 32) + 0.5) * SQUARE, z = ((int) e.getKey().longValue() + 0.5) * SQUARE;
			out.add(new HoloWire.LayerItem(x, z, (float) e.getValue()[0] / max, hostileOnly ? 0xE03030 : 0x30C0E0, Integer.toString(e.getValue()[0])));
		}

		out.sort((a, b) -> Float.compare(b.v(), a.v()));
		return out.subList(0, Math.min(out.size(), HoloWire.MAX_ITEMS));
	}
}
