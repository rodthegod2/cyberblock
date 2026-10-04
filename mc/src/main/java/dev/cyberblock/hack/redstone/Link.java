package dev.cyberblock.hack.redstone;

import dev.cyberblock.device.Device;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a Cyberdeck Link ties a redstone hacking block to (megaplan 6-R1): the owner's UUID, the device's Cyberpunk position rounded to
 * {@link #STEP} and its Cyberpunk class. Night City ids may change between sessions, positions and classes do not, so the link is
 * resolved again at runtime against the owner's current device table (it works after restarts and is "unresolved" while the owner is
 * offline or the device is not in its list). Pure.
 *
 * <p>Cyberpunk position from a Minecraft one: {@code (x, -z, y + k/64)} (cbmath.hpp {@code mc_to_cp_pos}, with the world's ground
 * alignment k), so a realignment of the world does not break a link.
 */
public record Link(UUID owner, double x, double y, double z, String cls) {
	/** Rounding of the stored position, metres (the DLL's position-hash fallback rounds to 25 cm too). */
	public static final double STEP = 0.25;
	/** A device matches when every axis is within this of the stored position (one rounding step either way, and a little). */
	static final double MATCH = 0.3;
	/** Classes are at most 48 characters on the wire (DeviceWire.CLS_CAP). */
	static final int CLS_CAP = 48;

	public Link {
		if (owner == null || cls == null || cls.isEmpty() || cls.length() > CLS_CAP || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
			throw new IllegalArgumentException("not a link");
		}
	}

	/** The link to this device of this owner, with the world's ground alignment k (0..63). */
	public static Link of(final UUID owner, final Device d, final int k) {
		double[] cp = cyberpunk(d.x(), d.y(), d.z(), k);
		return new Link(owner, round(cp[0]), round(cp[1]), round(cp[2]), d.cls());
	}

	/** Minecraft (x, y, z) to Cyberpunk (x, -z, y + k/64). */
	static double[] cyberpunk(final double x, final double y, final double z, final int k) {
		return new double[] {x, -z, y + k / 64.0};
	}

	static double round(final double v) {
		double r = Math.round(v / STEP) * STEP;
		return r == 0.0 ? 0.0 : r; // never -0.0, so equal links are equal records
	}

	/** Is this the linked device (same class, same rounded place)? */
	public boolean matches(final Device d, final int k) {
		if (!d.cls().equals(this.cls)) {
			return false;
		}

		double[] cp = cyberpunk(d.x(), d.y(), d.z(), k);
		return Math.abs(cp[0] - this.x) <= MATCH && Math.abs(cp[1] - this.y) <= MATCH && Math.abs(cp[2] - this.z) <= MATCH;
	}

	/** The linked device in the owner's table now: the nearest match, or null (unresolved). */
	public @Nullable Device resolve(final Collection<Device> table, final int k) {
		Device best = null;
		double bestSq = Double.MAX_VALUE;
		for (Device d : table) {
			if (this.matches(d, k)) {
				double[] cp = cyberpunk(d.x(), d.y(), d.z(), k);
				double dx = cp[0] - this.x, dy = cp[1] - this.y, dz = cp[2] - this.z, sq = dx * dx + dy * dy + dz * dz;
				if (sq < bestSq) {
					bestSq = sq;
					best = d;
				}
			}
		}

		return best;
	}

	/** One text per linked device: every relay linked to it shares the loop guard. */
	public String key() {
		return String.format(Locale.ROOT, "%s@%.2f,%.2f,%.2f/%s", this.owner, this.x, this.y, this.z, this.cls);
	}
}
