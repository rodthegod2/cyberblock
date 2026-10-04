package dev.cyberblock.figure;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.content.Content;
import dev.cyberblock.content.ContentKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.jspecify.annotations.Nullable;

/**
 * The server side of the figures' look (megaplan task 5-F1): the {@code skin_map} and {@code vehicle_map} content kinds, a proxy's
 * look from its host's tags, and the log lines that say which data and which Phase 0 (f) path is in use.
 */
public final class Figures {
	private static @Nullable ContentKind<SkinMap.Row> skins;
	private static @Nullable ContentKind<VehicleMap.Row> vehicles;

	private Figures() {
	}

	/** Once at startup, before the server loads its datapacks (ProxyManager.listen). */
	public static void register() {
		skins = ContentKind.register("skin_map", SkinMap::parse, false);
		vehicles = ContentKind.register("vehicle_map", VehicleMap::parse, false);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> Cyberblock.LOG.info(rowsLine(rows(skins).size(), rows(vehicles).size())));
	}

	/** The look of a person (vehicle false) or a vehicle with these tags and Night City id, from the loaded maps. */
	public static FigureLook look(final boolean vehicle, final FigureTags tags, final long id) {
		return vehicle ? FigureLook.vehicle(VehicleMap.resolve(rows(vehicles), tags), tags)
			: FigureLook.person(SkinMap.resolve(rows(skins), tags, id), tags);
	}

	private static <T> List<T> rows(final @Nullable ContentKind<T> kind) {
		return kind == null ? List.of() : new ArrayList<>(Content.all(kind).values());
	}

	/** The line logged once per server start. */
	static String rowsLine(final int skinRows, final int vehicleRows) {
		return "figures: skin_map " + skinRows + " rows, vehicle_map " + vehicleRows + " rows";
	}

	/**
	 * The line logged once per host session, at its first list with anyone in it: which Phase 0 (f) tags the host sends, so the
	 * user's test shows which path is active; every missing one names its written fallback.
	 */
	public static String tagsLine(final String host, final Set<String> seen) {
		List<String> missing = new ArrayList<>();
		for (String field : FigureTags.FIELDS) {
			if (!seen.contains(field)) {
				missing.add(field);
			}
		}

		if (seen.isEmpty()) {
			return "figures: " + host + " sends no npcs tags (fallback: neutral skins, walk and run from motion, class default colours, full hp)";
		}

		List<String> fallbacks = new ArrayList<>();
		if (!seen.contains("role") && !seen.contains("fac")) {
			fallbacks.add("neutral skins");
		} else if (!seen.contains("role")) {
			fallbacks.add("skins by faction (no role)");
		}

		if (!seen.contains("anim")) {
			fallbacks.add("walk and run from motion, no poses");
		}

		if (!seen.contains("col")) {
			fallbacks.add("class default colours");
		}

		if (!seen.contains("vc")) {
			fallbacks.add("vehicles by faction or sedan");
		}

		return "figures: " + host + " npcs tags " + String.join(" ", seen) + (missing.isEmpty() ? " (all)"
			: "; missing " + String.join(" ", missing) + (fallbacks.isEmpty() ? "" : " (fallback: " + String.join(", ", fallbacks) + ")"));
	}
}
