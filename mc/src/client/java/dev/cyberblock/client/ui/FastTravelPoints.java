package dev.cyberblock.client.ui;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The host's Night City fast-travel points for the Holo-Table (megaplan 7-V1). They come from {@code uiact atlasreq} with
 * {@code arg:"fasttravel"}: Menus.reds then answers a {@code reply kind:"atlas"} holding only the fast-travel points, each with its
 * index ({@code i}) in the game's fast-travel list while fast travel is possible for the host right now, -1 otherwise. The Atlas
 * asks without that arg and always gets -1, so it stays map-only. Travelling is {@code uiact fasttravel} with {@code id} = the
 * index and {@code arg} = the point's name (the scripts check that both still match).
 */
public final class FastTravelPoints {
	private static final Comparator<UiModel.ReplyPin> ORDER = Comparator.comparing((UiModel.ReplyPin p) -> p.district().toLowerCase(Locale.ROOT))
		.thenComparing(p -> p.name().toLowerCase(Locale.ROOT));

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
}
