package dev.cyberblock;

import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;

/**
 * Minecraft to Cyberpunk messages of layers 2-5 (see shared/protocol.md), one JSON object each. Everything is already in
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

	/** {"t":"blocks","reset":bool,"c":[x,y,z, ...]}: solid blocks (packed BlockPos), the resync after a host hello. */
	public static String blocks(final boolean reset, final long[] cells) {
		StringBuilder b = new StringBuilder(32 + cells.length * 24).append("{\"t\":\"blocks\",\"reset\":").append(reset).append(",\"c\":[");
		for (int i = 0; i < cells.length; i++) {
			long c = cells[i];
			b.append(i == 0 ? "" : ",").append(BlockPos.getX(c)).append(',').append(BlockPos.getY(c)).append(',').append(BlockPos.getZ(c));
		}

		return b.append("]}").toString();
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

	/** As hit, plus "mob":id (layer 3) when mob &gt;= 0: the mob that dealt a mob, arrow or trident hit. */
	public static String hit(final String target, final double dmg, final String kind, final double ix, final double iy, final double iz, final int mob) {
		String hit = hit(target, dmg, kind, ix, iy, iz);
		return mob < 0 ? hit : hit.substring(0, hit.length() - 1) + ",\"mob\":" + mob + "}";
	}

	/** {"t":"mobs","list":[{"id":n,"kind":"zombie","p":[x,y,z],"yaw":deg,"w":m,"h":m,"hp":n,"maxHp":n}, ...]} in the given order. */
	public static String mobs(final List<MobRules.Seen> list) {
		StringBuilder b = new StringBuilder("{\"t\":\"mobs\",\"list\":[");
		for (int i = 0; i < list.size(); i++) {
			MobRules.Seen s = list.get(i);
			b.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT,
				"{\"id\":%d,\"kind\":\"%s\",\"p\":[%.3f,%.3f,%.3f],\"yaw\":%.2f,\"w\":%.3f,\"h\":%.3f,\"hp\":%.2f,\"maxHp\":%.2f}",
				s.id(), MobRules.kind(s.kind()), fin(s.x()), fin(s.y()), fin(s.z()), fin(s.yaw()),
				clamp(s.w(), 16.0), clamp(s.h(), 16.0), clamp(s.hp(), 2048.0), clamp(s.maxHp(), 2048.0)));
		}

		return b.append("]}").toString();
	}

	/** Within the protocol's 0..max. */
	private static double clamp(final double v, final double max) {
		return Math.clamp(fin(v), 0.0, max);
	}

	/** {"t":"flight","active":bool,"p":[x,y,z] feet,"v":[x,y,z] m/s (at most 200),"yaw":deg,"pitch":deg}: Steve gliding, or where he landed. */
	public static String flight(final boolean active, final double x, final double y, final double z, final double vx, final double vy, final double vz,
		final double yaw, final double pitch) {
		double[] v = FlightRules.capSpeed(vx, vy, vz);
		return String.format(Locale.ROOT, "{\"t\":\"flight\",\"active\":%b,\"p\":[%.4f,%.4f,%.4f],\"v\":[%.3f,%.3f,%.3f],\"yaw\":%.2f,\"pitch\":%.2f}",
			active, fin(x), fin(y), fin(z), v[0], v[1], v[2], fin(yaw), fin(pitch));
	}

	/** {"t":"ignite","target":"id","seconds":s}: a proxy was set on fire. */
	public static String ignite(final String target, final double seconds) {
		return String.format(Locale.ROOT, "{\"t\":\"ignite\",\"target\":\"%s\",\"seconds\":%.2f}", target, fin(seconds));
	}
}
