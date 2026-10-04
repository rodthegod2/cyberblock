package dev.cyberblock.mode.overlord;

import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * What the Overlord asks the other Phase 8 packages, as replaceable hooks (set at their init; the server thread reads them). A
 * player's match, team and role (OVERLORD for an Overlord) come from {@code HoloHooks.match}, which 8M fills. Until these are set
 * every match that has players counts as running, its area is the box around its players and the Heat is 0.
 */
public final class OverlordHooks {
	/** 8T: the Server Heat, 0..100. */
	public static volatile IntSupplier heat = () -> 0;
	/** 8M: is this match running (not in its lobby or over)? Only a running match gets Overlord spawns and events. */
	public static volatile Predicate<String> running = match -> true;
	/** 8M: the match's zone as [x0, z0, x1, z1] in blocks, or null for the box around its players. */
	public static volatile Function<String, double @Nullable []> area = match -> null;

	private OverlordHooks() {
	}
}
