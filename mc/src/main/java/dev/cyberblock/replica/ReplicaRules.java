package dev.cyberblock.replica;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.function.LongPredicate;
import org.jspecify.annotations.Nullable;

/**
 * The rules of the replica (megaplan task 3-R1), pure: what a {@code ground} cell becomes, when an {@code unground} takes a replica
 * block out, how {@code ground.m} and {@code unground.seen} are read, and the door and device filter hook. WorldBridge applies them.
 */
public final class ReplicaRules {
	/** Phase 4 fills it from the device boxes: cells that never become replica blocks (doors are not walls). Any thread may read. */
	public static volatile LongPredicate excludedCells = cell -> false;

	/** What a cell a host reports as ground becomes. */
	public enum Ground {
		/** A replica block goes in (the cell is air). */
		PLACE,
		/** A projectile's 3 s barrier stood there: it is Night City ground now, and becomes a replica block. */
		PROMOTE,
		/** Something is there already (a player's block, a replica block): only the host's claim is noted. */
		CLAIM,
		/** An excluded cell (a door or a device): nothing. */
		SKIP
	}

	private ReplicaRules() {
	}

	/** The door and device filter (packed BlockPos). */
	public static boolean excluded(final long cell) {
		return excludedCells.test(cell);
	}

	/** Air cells only: never over a player's block. */
	public static Ground onGround(final boolean excluded, final boolean air, final boolean tempBarrier) {
		if (excluded) {
			return Ground.SKIP;
		}

		return air ? Ground.PLACE : tempBarrier ? Ground.PROMOTE : Ground.CLAIM;
	}

	/**
	 * A ground report for a cell already holding something: the material a replica block there becomes, or 0 for no change. Only a
	 * probe material (Palette.fromProbe of ground.m) replaces a replica block's material, so blocks first placed by the heuristic take
	 * the real one once the probes send it; a heuristic-only report (0) never does, so two hosts with different refY do not flip
	 * blocks back and forth. current is the cell's replica material (0 for a player's block, which is never replaced).
	 */
	public static int regrade(final int current, final int probeMaterial) {
		boolean change = current != ReplicaBlocks.UNKNOWN && probeMaterial != ReplicaBlocks.UNKNOWN && probeMaterial != current;
		return change ? probeMaterial : ReplicaBlocks.UNKNOWN;
	}

	/**
	 * An unground takes the replica block out only when the cell was seen empty inside the probe radius (seen:true), holds a replica
	 * block, and no other host still reports it as ground. Cells that only left the radius stay: the map grows.
	 */
	public static boolean ungroundRemoves(final boolean seen, final boolean replica, final boolean claimedByOthers) {
		return seen && replica && !claimedByOthers;
	}

	/** {@code unground.seen}: true only for a literal true. */
	public static boolean seen(final JsonObject m) {
		JsonElement e = m.get("seen");
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}

	/**
	 * {@code ground.m}: one material code per quadruple, codes out of 0..26 read as 0 (unknown). Null when absent or unusable (not an
	 * array of numbers of the right length): the heuristic decides every cell of the message then.
	 */
	public static int @Nullable [] codes(final JsonObject m, final int quadruples) {
		JsonElement e = m.get("m");
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != quadruples) {
			return null;
		}

		JsonArray a = e.getAsJsonArray();
		int[] out = new int[quadruples];
		for (int i = 0; i < quadruples; i++) {
			JsonElement v = a.get(i);
			if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
				return null;
			}

			double d = v.getAsDouble();
			out[i] = d >= 1 && d < ReplicaBlocks.CODES && d == Math.rint(d) ? (int) d : ReplicaBlocks.UNKNOWN;
		}

		return out;
	}

	/** Which path chose this message's materials: "probes" when any code is known, else "heuristic". */
	public static String materialPath(final int @Nullable [] codes) {
		if (codes != null) {
			for (int c : codes) {
				if (c != ReplicaBlocks.UNKNOWN) {
					return "probes";
				}
			}
		}

		return "heuristic";
	}
}
