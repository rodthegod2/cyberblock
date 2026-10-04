package dev.cyberblock.client.ui;

import dev.cyberblock.client.holo.HoloFastTravel;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The host's Night City fast-travel points for the Holo-Table (megaplan 7-V1). They come from {@code uiact atlasreq} with
 * {@code arg:"fasttravel"}: Menus.reds then answers a {@code reply kind:"atlas"} holding only the fast-travel points, each with its
 * index ({@code i}) in the game's fast-travel list while fast travel is possible for the host right now, -1 otherwise. The Atlas
 * asks without that arg and always gets -1, so it stays map-only. Travelling is {@code uiact fasttravel} with {@code id} = the
 * index and {@code arg} = the point's name (the scripts check that both still match; while V is parked in the hub they travel
 * when the host leaves it).
 *
 * <p>The Holo-Table (package 7T) lists {@link #known()} through its {@link HoloFastTravel#points} hook and runs {@link #travel} for
 * a picked point; {@link UiClient} installs the hook at client init.
 */
public final class FastTravelPoints {
	/** The list is asked for again when it is older than this while somebody reads it. */
	private static final long REFRESH_NANOS = 5_000_000_000L;
	private static final Comparator<UiModel.ReplyPin> ORDER = Comparator.comparing((UiModel.ReplyPin p) -> p.district().toLowerCase(Locale.ROOT))
		.thenComparing(p -> p.name().toLowerCase(Locale.ROOT));

	private static volatile List<UiModel.ReplyPin> known = List.of();
	private static long askedAt;
	private static int requestId = -1;

	private FastTravelPoints() {
	}

	/** The fast-travel points of a reply, by district and then name. */
	public static List<UiModel.ReplyPin> of(final UiModel.Reply reply) {
		return reply.pins().stream().filter(p -> p.kind().equals("fasttravel")).sorted(ORDER).toList();
	}

	/** The host may fast travel now: the scripts marked at least one point with its index. */
	public static boolean available(final List<UiModel.ReplyPin> points) {
		return points.stream().anyMatch(p -> p.index() >= 0);
	}

	/** Client init: the Holo-Table lists {@link #known()} and travels with {@link #travel}. */
	static void installHoloHook() {
		HoloFastTravel.points = () -> known().stream().map(FastTravelPoints::holoPoint).toList();
	}

	/** A point for the Holo-Table; reply pin positions are already Minecraft coordinates (the DLL converts them). */
	static HoloFastTravel.Point holoPoint(final UiModel.ReplyPin p) {
		return new HoloFastTravel.Point(p.name(), p.p().x(), p.p().z(), p.district(), () -> travel(p));
	}

	/**
	 * Client thread, cheap enough to call every frame: the host's last known points (empty while no host is linked, so guests see
	 * none), asking Cyberpunk again when the list is older than 5 s.
	 */
	public static List<UiModel.ReplyPin> known() {
		if (!UiLink.linked()) {
			known = List.of();
			return known;
		}

		long now = System.nanoTime();
		if (requestId < 0 || now - askedAt > REFRESH_NANOS) {
			UiLink.forget(requestId);
			askedAt = now;
			requestId = UiLink.request("atlas", "fasttravel", r -> known = of(r));
		}

		return known;
	}

	/**
	 * {@code uiact fasttravel} for a point of {@link #known()}; false when no host is linked. A point without an index (fast travel
	 * not possible now) still goes by name: the scripts toast why it cannot happen.
	 */
	public static boolean travel(final UiModel.ReplyPin point) {
		return UiLink.act("fasttravel", point.name(), 0L, point.index());
	}
}
