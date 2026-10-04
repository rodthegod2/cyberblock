package dev.cyberblock.client.holo;

import dev.cyberblock.client.ui.FastTravelPoints;
import dev.cyberblock.client.ui.UiModel;
import java.util.List;
import java.util.function.Supplier;

/**
 * The fast-travel hook of the Holo-Table (megaplan 7-V1: "the Holo-Table offers the host's fast-travel points"): {@link #points}
 * gives the host's own fast-travel points (7V's {@link FastTravelPoints#known()}, from its Cyberpunk game); the table lists them
 * under "Fast travel". The button asks the server first ({@code act holotravel}: the spawn rules, and a host at the table leaves the
 * hub so V is released) and runs the point's action ({@link FastTravelPoints#travel}) when the server says go ({@code ui holotravel}).
 * {@link HoloClient#init} installs the hook ({@link #install}); empty for guests (no host linked). Client thread.
 */
public final class HoloFastTravel {
	/** One point: its name, its Minecraft position (x, z) for the miniature, its district, and what travelling there does. */
	public record Point(String name, double x, double z, String district, Runnable travel) {
	}

	/** No points: the hook before {@link #install}. */
	static final Supplier<List<Point>> NONE = List::of;

	/** The provider of the host's points; asked each time the table lists them, so keep it cheap. */
	public static volatile Supplier<List<Point>> points = NONE;

	private HoloFastTravel() {
	}

	/** Installs 7V's host points as the table's fast-travel list. */
	static void install() {
		points = () -> of(FastTravelPoints.known());
	}

	/** 7V's points as table points: {@code ReplyPin.p} is already in Minecraft coordinates (as the Atlas and the radar use it). */
	static List<Point> of(final List<UiModel.ReplyPin> pins) {
		return pins.stream().map(p -> new Point(p.name(), p.p().x(), p.p().z(), p.district(), () -> FastTravelPoints.travel(p))).toList();
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
