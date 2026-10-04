package dev.cyberblock.client.atlas;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The Atlas messages (protocol.md "Atlas"; pure, tested): {@code terrain} and {@code surveystate} from Cyberpunk, {@code survey}
 * to it, and which survey-tour stops Minecraft already has. A message that breaks a limit is invalid (null) as a whole.
 */
public final class AtlasWire {
	public static final Set<String> STATES = Set.of("running", "paused", "done", "stopped", "refused", "idle");
	public static final int MAX_CELLS = 64;
	public static final int MAX_DONE = 4096;
	/** The tour's grid (survey.hpp kTourSpacing, kTourHalf): stop (gx, gy) at Cyberpunk (200 gx, 200 gy), a 220 m square. */
	public static final double SPACING = 200.0;
	public static final double HALF = 110.0;
	/** A stop whose square Minecraft knows to this share counts as done. */
	public static final double COVERED = 0.9;

	/** {@code stop} is null unless the message reports a finished stop. */
	public record SurveyState(String state, int done, int total, double eta, String reason, int @Nullable [] stop) {
		public boolean running() {
			return this.state.equals("running") || this.state.equals("paused");
		}
	}

	private AtlasWire() {
	}

	/** {@code terrain.c}: whole quadruples (cx, cz, y, s), 1..64 cells, |cx|, |cz| < 1.5e7, |y| <= 20000, s 0 or 1; else null. */
	public static int @Nullable [] parseTerrain(final JsonObject m) {
		if (!(m.get("c") instanceof JsonArray c) || c.isEmpty() || c.size() % 4 != 0 || c.size() > 4 * MAX_CELLS) {
			return null;
		}

		int[] out = new int[c.size()];
		for (int i = 0; i < out.length; i++) {
			Long v = whole(c.get(i));
			if (v == null) {
				return null;
			}

			boolean ok = switch (i % 4) {
				case 0, 1 -> Math.abs(v) < 15_000_000L;
				case 2 -> Math.abs(v) <= 20_000L;
				default -> v == 0L || v == 1L;
			};
			if (!ok) {
				return null;
			}

			out[i] = (int) (long) v;
		}

		return out;
	}

	public static @Nullable SurveyState parseSurveyState(final JsonObject m) {
		if (!(m.get("state") instanceof JsonPrimitive s && s.isString()) || !STATES.contains(s.getAsString())) {
			return null;
		}

		Long done = whole(m.get("done")), total = whole(m.get("total"));
		if (done == null || total == null || done < 0 || total < 0 || done > total || total > Integer.MAX_VALUE) {
			return null;
		}

		double eta = m.get("eta") instanceof JsonPrimitive e && e.isNumber() ? e.getAsDouble() : -1.0;
		if (!(eta == -1.0 || (eta >= 0.0 && eta <= 1e7))) {
			return null;
		}

		String reason = m.get("reason") instanceof JsonPrimitive r && r.isString() ? r.getAsString() : "";
		if (reason.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64) {
			return null;
		}

		int[] stop = null;
		if (m.has("stop")) {
			if (!(m.get("stop") instanceof JsonArray a) || a.size() != 2) {
				return null;
			}

			Long gx = whole(a.get(0)), gy = whole(a.get(1));
			if (gx == null || gy == null || Math.abs(gx) > 100_000 || Math.abs(gy) > 100_000) {
				return null;
			}

			stop = new int[] {(int) (long) gx, (int) (long) gy};
		}

		return new SurveyState(s.getAsString(), (int) (long) done, (int) (long) total, eta, reason, stop);
	}

	/** {"t":"survey","op":op} and, for start, {@code done} as [gx, gy, ...] (at most 4096 stops; the rest are left out). */
	public static String survey(final String op, final Collection<Long> done) {
		if (!Set.of("start", "stop", "status").contains(op)) {
			throw new IllegalArgumentException("unknown survey op " + op);
		}

		JsonObject m = new JsonObject();
		m.addProperty("t", "survey");
		m.addProperty("op", op);
		if (op.equals("start")) {
			JsonArray list = new JsonArray();
			int n = 0;
			for (long stop : done) {
				if (n++ >= MAX_DONE) {
					break;
				}

				list.add(AtlasTiles.keyA(stop));
				list.add(AtlasTiles.keyB(stop));
			}

			m.add("done", list);
		}

		return m.toString();
	}

	/** The cells (inclusive rectangle cx0, cz0, cx1, cz1) of stop (gx, gy)'s square, in Minecraft space (z = -Cyberpunk y). */
	public static int[] stopCells(final int gx, final int gy) {
		double x = gx * SPACING, z = -gy * SPACING;
		return new int[] {AtlasTiles.cellOf(x - HALF), AtlasTiles.cellOf(z - HALF), AtlasTiles.cellOf(x + HALF), AtlasTiles.cellOf(z + HALF)};
	}

	/** The stops of {@code reported} plus every stop whose square the tiles know to {@link #COVERED}: what {@code start} lists. */
	public static List<Long> doneStops(final AtlasTiles tiles, final Collection<Long> reported) {
		TreeSet<Long> done = new TreeSet<>(reported);
		List<Long> keys = tiles.tileKeys();
		if (!keys.isEmpty()) {
			int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			for (long k : keys) {
				minX = Math.min(minX, AtlasTiles.keyA(k));
				maxX = Math.max(maxX, AtlasTiles.keyA(k));
				minZ = Math.min(minZ, AtlasTiles.keyB(k));
				maxZ = Math.max(maxZ, AtlasTiles.keyB(k));
			}

			double tileBlocks = AtlasTiles.TILE * AtlasTiles.CELL;
			int gx0 = (int) Math.floor(minX * tileBlocks / SPACING), gx1 = (int) Math.ceil((maxX + 1) * tileBlocks / SPACING);
			// Minecraft z = -Cyberpunk y: gy runs the other way
			int gy0 = (int) Math.floor(-(maxZ + 1) * tileBlocks / SPACING), gy1 = (int) Math.ceil(-minZ * tileBlocks / SPACING);
			for (int gy = gy0; gy <= gy1; gy++) {
				for (int gx = gx0; gx <= gx1; gx++) {
					int[] r = stopCells(gx, gy);
					if (!done.contains(AtlasTiles.key(gx, gy)) && tiles.coverage(r[0], r[1], r[2], r[3]) >= COVERED) {
						done.add(AtlasTiles.key(gx, gy));
					}
				}
			}
		}

		return new ArrayList<>(done);
	}

	/** Seconds left as the HUD shows them: "3h 12m", "12m", "under a minute", "unknown" (negative). */
	public static String eta(final double seconds) {
		if (!(seconds >= 0.0)) {
			return "unknown time";
		}

		long minutes = Math.round(seconds / 60.0);
		if (minutes < 1) {
			return "under a minute";
		}

		return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m";
	}

	private static @Nullable Long whole(final @Nullable JsonElement e) {
		if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
			return null;
		}

		double v = p.getAsDouble();
		return Double.isFinite(v) && v == Math.rint(v) && Math.abs(v) < 9e15 ? (long) v : null;
	}
}
