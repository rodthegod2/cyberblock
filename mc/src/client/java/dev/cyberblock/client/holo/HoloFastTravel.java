package dev.cyberblock.client.holo;

import java.util.List;
import java.util.function.Supplier;

/**
 * The fast-travel hook of the Holo-Table for 7V (megaplan 7-V1: "the Holo-Table offers the host's fast-travel points"): 7V sets
 * {@link #points} to the host's own fast-travel points (from its Cyberpunk game, {@code uiact fasttravel} once verified); the table
 * lists them under "Fast travel". The button asks the server first ({@code act holotravel}: the spawn rules, and a host at the table
 * leaves the hub so V is released) and runs the point's action when the server says go ({@code ui holotravel}). Empty (nothing
 * listed) until 7V installs it, and for guests. Client thread.
 *
 * <p>The hook is installed in the Phase 7 merge, with the snippet in 7V's {@code FastTravelPoints.known()} javadoc:
 * {@code HoloFastTravel.points = () -> FastTravelPoints.known().stream().map(p -> new HoloFastTravel.Point(p.name(), p.p().x(),
 * p.p().z(), p.district(), () -> FastTravelPoints.travel(p))).toList();}
 */
public final class HoloFastTravel {
	/** One point: its name, its Minecraft position (x, z) for the miniature, its district, and what travelling there does. */
	public record Point(String name, double x, double z, String district, Runnable travel) {
	}

	/** 7V's provider of the host's points; asked each time the table lists them, so keep it cheap. */
	public static volatile Supplier<List<Point>> points = List::of;

	private HoloFastTravel() {
	}

	static List<Point> list() {
		try {
			List<Point> p = points.get();
			return p == null ? List.of() : p;
		} catch (RuntimeException e) {
			return List.of();
		}
	}
}
