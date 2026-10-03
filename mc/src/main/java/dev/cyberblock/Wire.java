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

	/** {"t":"proj","list":[{"id":n,"p":[x,y,z],"v":[x,y,z] m/s (at most 200),"g":m/s^2}, ...]} in the given order. */
	public static String proj(final List<Projectiles.Flying> list) {
		StringBuilder b = new StringBuilder("{\"t\":\"proj\",\"list\":[");
		for (int i = 0; i < list.size(); i++) {
			Projectiles.Flying f = list.get(i);
			double[] v = FlightRules.capSpeed(f.vx(), f.vy(), f.vz());
			b.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "{\"id\":%d,\"p\":[%.3f,%.3f,%.3f],\"v\":[%.3f,%.3f,%.3f],\"g\":%.3f}",
				f.id(), fin(f.x()), fin(f.y()), fin(f.z()), v[0], v[1], v[2], Math.clamp(fin(f.g()), 0.0, Projectiles.MAX_GRAVITY)));
		}

		return b.append("]}").toString();
	}

	/** {"t":"teleport","p":[x,y,z] feet,"yaw":deg}: Steve's ender pearl landed; the host moves V there. */
	public static String teleport(final double x, final double y, final double z, final double yaw) {
		return String.format(Locale.ROOT, "{\"t\":\"teleport\",\"p\":[%.3f,%.3f,%.3f],\"yaw\":%.2f}", fin(x), fin(y), fin(z), fin(yaw));
	}

	/** {"t":"lead","target":"id","anchor":[x,y,z]|null,"length":m}: a proxy was leashed, to Steve (anchor null) or to a fence knot. */
	public static String lead(final String target, final double[] anchor, final double length) {
		String at = anchor == null ? "null" : String.format(Locale.ROOT, "[%.3f,%.3f,%.3f]", fin(anchor[0]), fin(anchor[1]), fin(anchor[2]));
		return String.format(Locale.ROOT, "{\"t\":\"lead\",\"target\":\"%s\",\"anchor\":%s,\"length\":%.2f}", target, at, Math.clamp(fin(length), 1.0, 32.0));
	}

	/** {"t":"lead","target":"id","release":true}: the leash was removed, broken or dropped. */
	public static String leadRelease(final String target) {
		return "{\"t\":\"lead\",\"target\":\"" + target + "\",\"release\":true}";
	}

	/** {"t":"pull","target":"id","toward":[x,y,z],"strength":m/s}: a fishing rod reeled a hooked proxy in. */
	public static String pull(final String target, final double x, final double y, final double z, final double strength) {
		return String.format(Locale.ROOT, "{\"t\":\"pull\",\"target\":\"%s\",\"toward\":[%.3f,%.3f,%.3f],\"strength\":%.2f}",
			target, fin(x), fin(y), fin(z), Math.clamp(fin(strength), 0.0, 50.0));
	}

	/** {"t":"knock","pos":[x,y,z],"radius":m,"strength":m/s}: a wind burst (sent instead of explosion). */
	public static String knock(final double x, final double y, final double z, final double radius, final double strength) {
		return String.format(Locale.ROOT, "{\"t\":\"knock\",\"pos\":[%.3f,%.3f,%.3f],\"radius\":%.3f,\"strength\":%.3f}",
			fin(x), fin(y), fin(z), Math.clamp(fin(radius), 0.0, Tethers.MAX_KNOCK_RADIUS), Math.clamp(fin(strength), 0.0, Tethers.MAX_KNOCK_STRENGTH));
	}

	/** {"t":"effect","name":"speed","seconds":s (0 = removed, 86400 = infinite),"amp":0..255}: a status effect on Steve. */
	public static String effect(final String name, final double seconds, final int amp) {
		return String.format(Locale.ROOT, "{\"t\":\"effect\",\"name\":\"%s\",\"seconds\":%.2f,\"amp\":%d}", name, Math.clamp(fin(seconds), 0.0, 86400.0),
			Math.clamp(amp, 0, 255));
	}

	/** {"t":"align","k":0..63}: the world's ground alignment, Minecraft y = Cyberpunk z - k/64 (plan retest2 section 1.2). */
	public static String align(final int k) {
		return "{\"t\":\"align\",\"k\":" + Math.clamp(k, 0, GroundAlign.STEPS - 1) + "}";
	}

	/** {"t":"ignite","target":"id","seconds":s}: a proxy was set on fire. */
	public static String ignite(final String target, final double seconds) {
		return String.format(Locale.ROOT, "{\"t\":\"ignite\",\"target\":\"%s\",\"seconds\":%.2f}", target, fin(seconds));
	}
}
