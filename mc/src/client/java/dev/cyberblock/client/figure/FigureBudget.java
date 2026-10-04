package dev.cyberblock.client.figure;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.client.perf.Performance;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Fewer figures in Performance mode (megaplan task 9-F1). The cap: of the Night City figures near this client (people and cars of every
 * host), only the nearest {@code Performance.figureCap()} (16..48, default 48) are drawn, chosen again every client tick. Distance LOD:
 * a person farther than LOD_DISTANCE from the camera is drawn still (no stride, no aim, fire, combat, crouch or ragdoll pose; a body
 * still lies down). Each change of whether the cap cuts is logged once ({@code figures: cap <n> draws the nearest <n> of <m>} / {@code
 * figures: cap <n> draws all <m>}). Client thread only.
 */
public final class FigureBudget {
	/** m: farther figures are drawn without animation. */
	static final double LOD_DISTANCE = 32.0;
	/** The figures drawn this tick, or null when the cap cuts none (every figure is). */
	private static @Nullable IntOpenHashSet allowed;
	private static boolean loggedCut;
	private static int loggedCap = -1;

	private FigureBudget() {
	}

	/** The cap in force (FigureStats says it in its line). */
	static int cap() {
		return Performance.figureCap();
	}

	/** Whether this figure is among the ones drawn. */
	static boolean allowed(final int entityId) {
		IntOpenHashSet set = allowed;
		return set == null || set.contains(entityId);
	}

	/** Whether a figure this far (squared, from the render state) is drawn with its animation. */
	static boolean animated(final double distanceToCameraSq) {
		return distanceToCameraSq <= LOD_DISTANCE * LOD_DISTANCE;
	}

	/** END_CLIENT_TICK: the nearest `cap` figures to the camera. */
	static void tick(final Minecraft minecraft) {
		Entity camera = minecraft.getCameraEntity();
		if (minecraft.level == null || camera == null) {
			allowed = null;
			return;
		}

		Vec3 at = camera.position();
		int n = 0;
		int[] ids = new int[64];
		double[] distSq = new double[64];
		for (Entity e : minecraft.level.entitiesForRendering()) {
			if (e instanceof ProxyEntity) {
				if (n == ids.length) {
					ids = Arrays.copyOf(ids, n * 2);
					distSq = Arrays.copyOf(distSq, n * 2);
				}

				ids[n] = e.getId();
				distSq[n] = e.position().distanceToSqr(at);
				n++;
			}
		}

		int cap = cap();
		int[] keep = nearest(ids, distSq, n, cap);
		allowed = keep == null ? null : new IntOpenHashSet(keep);
		boolean cut = keep != null;
		if (cut != loggedCut || cap != loggedCap && cut) {
			loggedCut = cut;
			loggedCap = cap;
			Cyberblock.LOG.info(cut ? "figures: cap {} draws the nearest {} of {}" : "figures: cap {} draws all {} near", cap, cut ? cap : n, n);
		}
	}

	/** The ids of the `cap` nearest of the first `n` (ties: the smaller id first), or null when `n <= cap` (all are drawn). Pure. */
	static int @Nullable [] nearest(final int[] ids, final double[] distSq, final int n, final int cap) {
		if (n <= cap) {
			return null;
		}

		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}

		Arrays.sort(order, (a, b) -> distSq[a] != distSq[b] ? Double.compare(distSq[a], distSq[b]) : Integer.compare(ids[a], ids[b]));
		int[] out = new int[cap];
		for (int i = 0; i < cap; i++) {
			out[i] = ids[order[i]];
		}

		return out;
	}
}
