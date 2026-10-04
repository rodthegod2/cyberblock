package dev.cyberblock.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A host's Cyberpunk vendor or ripperdoc as data for the Minecraft vendor screen (megaplan 7-V1; sections 1.9 and 1.16). When the
 * host opens a Night City vendor while Cyberblock is On, Vendors.reds answers with an unrequested {@code reply kind:"vendor"}:
 * <ul>
 * <li>{@code id}: the host's vendor session (+1 each time a vendor is taken over); a refresh after a purchase keeps it;</li>
 * <li>{@code pins[0]}: the vendor itself: {@code kind} {@code ripperdoc} or {@code vendor}, {@code name} its shop name;</li>
 * <li>{@code groups}: one per stock category, titled by it; kind {@code buy} with {@code done:false} lists the vendor's stock,
 * kind {@code sell} with {@code done:true} V's items the vendor takes (either mark makes a sell group);</li>
 * <li>{@code lines}: {@code hash} the item hash, {@code text} its name, {@code state} its unit price in eddies.</li>
 * </ul>
 * Buying and selling go back as {@code uiact buy|sell}: {@code hash} the line's hash, {@code id} the line's index among the lines of
 * its side in reply order, {@code arg} the quantity (1 to {@link #BULK}; the scripts clamp it to the stack and the money). The host
 * pays with its real Cyberpunk money ({@code stats.money}); nothing is paid in Minecraft eddies. Nothing asks for the reply (no
 * {@code vendorreq}), and buy/sell are {@code uiact}s, never DLL events: the one Phase 7 vendor contract (megaplan 1.8/1.9 amendment
 * 2026-10-04). Installing cyberware stays Cyberpunk's own ripperdoc screen ("Night City vendor..."): the Minecraft ripperdoc screen
 * is a shop only (a Phase 7 descope).
 */
public final class VendorModel {
	/** A shift-click buys or sells this many (the scripts clamp it to the stack). */
	public static final int BULK = 10;

	/**
	 * @param index the line's index among its side's lines (what {@code uiact} sends as {@code id})
	 * @param price the unit price in eddies
	 */
	public record Item(int index, long hash, String name, String category, long price) {
	}

	public record Vendor(int session, String name, boolean ripperdoc, List<Item> buy, List<Item> sell) {
	}

	private VendorModel() {
	}

	/** The vendor a {@code reply kind:"vendor"} describes; null for any other reply kind. */
	public static @Nullable Vendor of(final UiModel.Reply reply) {
		if (!reply.kind().equals("vendor")) {
			return null;
		}

		String name = "";
		boolean ripperdoc = false;
		if (!reply.pins().isEmpty()) {
			UiModel.ReplyPin pin = reply.pins().get(0);
			name = pin.name();
			ripperdoc = pin.kind().equals("ripperdoc");
		}

		List<Item> buy = new ArrayList<>();
		List<Item> sell = new ArrayList<>();
		for (UiModel.Group group : reply.groups()) {
			List<Item> side = sellSide(group) ? sell : buy;
			for (UiModel.Line line : group.lines()) {
				side.add(new Item(side.size(), line.hash(), line.text(), group.title(), line.state()));
			}
		}

		return new Vendor(reply.id(), name, ripperdoc, List.copyOf(buy), List.copyOf(sell));
	}

	/** A group of V's items the vendor takes: kind {@code sell} or {@code done:true} (the scripts send both; either is enough). */
	static boolean sellSide(final UiModel.Group group) {
		return group.done() || group.kind().equals("sell");
	}

	/** How many one click trades: 1, or {@link #BULK} with Shift. */
	public static int quantity(final boolean shift) {
		return shift ? BULK : 1;
	}

	/** The host can pay for one: true while the money is unknown (the scripts check again). */
	public static boolean affordable(final Item item, final UiModel.@Nullable Stats stats) {
		return stats == null || item.price() <= stats.money();
	}

	/** "1,250 €$": an eddie amount as Cyberpunk writes it. */
	public static String eddies(final long amount) {
		return String.format(Locale.ROOT, "%,d €$", amount);
	}
}
