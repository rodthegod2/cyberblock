package dev.cyberblock;

import java.util.Locale;

/**
 * Minecraft to Cyberpunk messages of layer 2 (see shared/protocol.md), one JSON object each. Everything is already in
 * Minecraft convention: the host converts to Cyberpunk space. Numbers are never NaN or infinite: a non-finite value
 * would make the line invalid JSON, so it is sent as 0 (the callers have validated their inputs; this is the last guard).
 */
public final class Wire {
	private Wire() {
	}

	private static double fin(final double v) {
		return Double.isFinite(v) ? v : 0.0;
	}

	/** {"t":"block","op":"add"|"remove","pos":[bx,by,bz]}: a solid non-barrier block appeared or went. */
	public static String block(final BlockEvents.Op op, final int x, final int y, final int z) {
		return String.format(Locale.ROOT, "{\"t\":\"block\",\"op\":\"%s\",\"pos\":[%d,%d,%d]}", op == BlockEvents.Op.ADD ? "add" : "remove", x, y, z);
	}

	/** {"t":"explosion","pos":[x,y,z],"power":radius,"fire":bool}. */
	public static String explosion(final double x, final double y, final double z, final double power, final boolean fire) {
		return String.format(Locale.ROOT, "{\"t\":\"explosion\",\"pos\":[%.3f,%.3f,%.3f],\"power\":%.3f,\"fire\":%b}", fin(x), fin(y), fin(z), fin(power), fire);
	}

	/** {"t":"hit","target":"id","dmg":points,"kind":"melee|arrow|trident|mob|explosion|fire","impulse":[x,y,z]}: a proxy took damage. */
	public static String hit(final String target, final double dmg, final String kind, final double ix, final double iy, final double iz) {
		return String.format(Locale.ROOT, "{\"t\":\"hit\",\"target\":\"%s\",\"dmg\":%.3f,\"kind\":\"%s\",\"impulse\":[%.3f,%.3f,%.3f]}",
			target, fin(dmg), kind, fin(ix), fin(iy), fin(iz));
	}

	/** {"t":"ignite","target":"id","seconds":s}: a proxy was set on fire. */
	public static String ignite(final String target, final double seconds) {
		return String.format(Locale.ROOT, "{\"t\":\"ignite\",\"target\":\"%s\",\"seconds\":%.2f}", target, fin(seconds));
	}
}
