package dev.cyberblock;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The world's ground alignment (plan retest2 R4, section 1.1): Minecraft y = Cyberpunk z - k/64, with k an integer 0..63 kept
 * with the world (saved data cyberblock:align, the PlacedBlocks pattern). It is chosen once from the ground under V, so Night City's
 * floor maps just above a whole block and the nearest-rounded barrier under V has its top face exactly at that block: blocks Steve
 * places sit on the street. UNSET until the first activation picks it. Server thread only.
 */
public final class GroundAlign extends SavedData {
	/** Not chosen yet: the first activation with V standing picks it (WorldBridge.tick). */
	public static final int UNSET = -1;
	/** Steps per metre: the offset is k / 64 m, exact in float, double and JSON. */
	static final int STEPS = 64;
	static final Codec<GroundAlign> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("k").forGetter(GroundAlign::k)
	).apply(i, GroundAlign::new));
	/** No data fixer: Fabric API's SavedDataStorageMixin reads a type without one as it is. */
	static final SavedDataType<GroundAlign> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Cyberblock.ID, "align"), GroundAlign::new, CODEC, null);
	private int k;

	GroundAlign() {
		this(UNSET);
	}

	/** A saved value outside 0..63 (edited by hand) is taken as unset: the next activation picks it again. */
	GroundAlign(final int k) {
		this.k = valid(k) ? k : UNSET;
	}

	private static boolean valid(final int k) {
		return k >= 0 && k < STEPS;
	}

	/** The world's k, or UNSET. */
	public int k() {
		return this.k;
	}

	/** A new k (0..63); only a change makes the world save it. */
	void set(final int k) {
		if (!valid(k)) {
			throw new IllegalArgumentException("alignment k out of range: " + k);
		}

		if (k != this.k) {
			this.k = k;
			this.setDirty();
		}
	}

	/**
	 * The k that puts the floor V stands on (cpZ, V's feet in Cyberpunk z) 1.95 to 17.6 mm above a whole block:
	 * floorMod(floor(frac(z) * 64 - 0.125), 64). Same as tools/cbmath.py align_k.
	 */
	public static int kOf(final double cpZ) {
		if (!Double.isFinite(cpZ)) {
			throw new IllegalArgumentException("V's feet are not a number: " + cpZ);
		}

		double fraction = cpZ - Math.floor(cpZ);
		return Math.floorMod((int) Math.floor(fraction * STEPS - 0.125), STEPS);
	}

	/** Metres between Cyberpunk z and Minecraft y for a k. */
	public static double offset(final int k) {
		return k / (double) STEPS;
	}
}
