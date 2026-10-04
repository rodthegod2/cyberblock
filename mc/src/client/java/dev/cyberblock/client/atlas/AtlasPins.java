package dev.cyberblock.client.atlas;

import dev.cyberblock.client.ui.UiModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Which Atlas pins show, in what order, and where the district labels go (pure, tested). Pins are drawn most important first
 * (the selected one, quests, fast travel, ripperdocs, vendors, gigs, police, apartments, cars, the rest), and a pin that would
 * cover one already drawn is left out, so the map stays readable at any zoom; quests and the selected pin always show.
 */
public final class AtlasPins {
	/** The filter buttons. */
	public enum Filter {
		QUESTS("quests", "Quests", Set.of("quest")),
		TRAVEL("travel", "Travel", Set.of("fasttravel")),
		SHOPS("shops", "Shops", Set.of("vendor", "ripperdoc")),
		GIGS("gigs", "Gigs", Set.of("gig", "police")),
		PLACES("places", "Places", Set.of("apartment", "car", "other"));

		private final String key, english;
		private final Set<String> kinds;

		Filter(final String key, final String english, final Set<String> kinds) {
			this.key = key;
			this.english = english;
			this.kinds = kinds;
		}

		public String key() {
			return this.key;
		}

		public String english() {
			return this.english;
		}

		public static Filter of(final String kind) {
			for (Filter f : values()) {
				if (f.kinds.contains(kind)) {
					return f;
				}
			}

			return PLACES;
		}
	}

	private static final List<String> PRIORITY = List.of("quest", "fasttravel", "ripperdoc", "vendor", "gig", "police", "apartment", "car", "other");

	private AtlasPins() {
	}

	/** Lower is more important. */
	public static int priority(final String kind) {
		int i = PRIORITY.indexOf(kind);
		return i < 0 ? PRIORITY.size() : i;
	}

	/** The indices of the pins whose filter is shown, most important first (the selected pin before all), stable otherwise. */
	public static List<Integer> drawOrder(final List<UiModel.ReplyPin> pins, final Set<Filter> shown, final int selected) {
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < pins.size(); i++) {
			if (i == selected || shown.contains(Filter.of(pins.get(i).kind()))) {
				order.add(i);
			}
		}

		order.sort(Comparator.comparingInt((Integer i) -> i == selected ? -1 : priority(pins.get(i).kind())));
		return order;
	}

	/** A pin kind as the map's tooltip names it. */
	public static String kindName(final String kind) {
		return switch (kind) {
			case "quest" -> "Quest";
			case "fasttravel" -> "Fast travel point";
			case "vendor" -> "Vendor";
			case "ripperdoc" -> "Ripperdoc";
			case "gig" -> "Gig";
			case "police" -> "NCPD";
			case "apartment" -> "Apartment";
			case "car" -> "Vehicle";
			default -> "Place";
		};
	}

	/**
	 * District name -> label centre (Minecraft x, z): the mean of the pins that name the district (fast-travel points), else
	 * where V has been in it ({@code visits}). Sorted by name.
	 */
	public static Map<String, double[]> districtCentres(final List<UiModel.ReplyPin> pins, final Map<String, double[]> visits) {
		Map<String, double[]> sums = new TreeMap<>();
		for (UiModel.ReplyPin pin : pins) {
			if (!pin.district().isBlank()) {
				double[] s = sums.computeIfAbsent(pin.district(), d -> new double[3]);
				s[0] += pin.p().x();
				s[1] += pin.p().z();
				s[2] += 1.0;
			}
		}

		Map<String, double[]> out = new LinkedHashMap<>();
		sums.forEach((name, s) -> out.put(name, new double[] {s[0] / s[2], s[1] / s[2]}));
		new TreeMap<>(visits).forEach((name, centre) -> out.putIfAbsent(name, centre.clone()));
		return out;
	}
}
