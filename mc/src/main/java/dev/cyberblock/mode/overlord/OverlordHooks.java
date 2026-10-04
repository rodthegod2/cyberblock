package dev.cyberblock.mode.overlord;

import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * What the Overlord asks the other Phase 8 packages, as replaceable hooks (set at their init or by the integrator; the server thread
 * reads them). A player's match, team and role (OVERLORD for an Overlord) come from {@code HoloHooks.match}, which 8M fills. Until
 * {@link #running} is set no match counts as running, so the Overlord and the Auto-Director stay off (fail closed: a lobby or an ended
 * match must never get spawns); the area falls back to the box around the match's players and the Heat to 0. The wiring, once 8M and 8T
 * are on main: {@code running = id -> 8M's match with that id exists and is RUNNING}, {@code area = id -> its zone's bounding box
 * [x - r, z - r, x + r, z + r]}, {@code heat = () -> (int) Math.round(ServerHeat.value())}.
 */
public final class OverlordHooks {
	static final IntSupplier NO_HEAT = () -> 0;
	static final Predicate<String> NOT_SET = match -> false;
	static final Function<String, double @Nullable []> NO_AREA = match -> null;

	/** 8T: the Server Heat, 0..100. */
	public static volatile IntSupplier heat = NO_HEAT;
	/** 8M: is this match running (not in its lobby or over)? Only a running match gets Overlord spawns and events. */
	public static volatile Predicate<String> running = NOT_SET;
	/** 8M: the match's zone as [x0, z0, x1, z1] in blocks, or null for the box around its players. */
	public static volatile Function<String, double @Nullable []> area = NO_AREA;

	private OverlordHooks() {
	}
}
