package dev.cyberblock;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * The proxies in the Minecraft world: one per Cyberpunk NPC or vehicle in the host's latest {"t":"npcs"} list, created,
 * moved and removed to match it. Server thread only (the host's message is handed over with MinecraftServer.execute).
 */
public final class ProxyManager {
	/** The host sends 10 per second; two seconds of silence mean it has gone, and the proxies with it. */
	private static final int TIMEOUT_TICKS = 40;
	private static final Map<Long, ProxyEntity> proxies = new HashMap<>();
	private static final Map<Long, ProxyDiff.Kind> kinds = new HashMap<>();
	private static int ticksSinceList;
	private static int bad;

	private ProxyManager() {
	}

	/** Link thread: the host's npcs list. */
	public static void update(final JsonObject message) {
		MinecraftServer s = WorldBridge.server();
		if (s != null) {
			s.execute(() -> apply(s.overworld(), message));
		}
	}

	private static void apply(final ServerLevel level, final JsonObject message) {
		if (!Cyberblock.active) {
			return;
		}

		ProxyDiff.Parsed parsed = ProxyDiff.parse(message, level.getMinY(), level.getMaxY());
		if (parsed == null) {
			warn("npcs message without a list");
			return;
		}

		if (parsed.rejected() > 0) {
			warn(parsed.rejected() + " bad npcs entries skipped");
		}

		ticksSinceList = 0;
		// a proxy that was killed or unloaded under us is simply gone: it is spawned again below if the host still lists it
		proxies.values().removeIf(ProxyEntity::isRemoved);
		kinds.keySet().retainAll(proxies.keySet());
		ProxyDiff.Plan plan = ProxyDiff.apply(kinds, parsed.entries());
		for (Long id : plan.remove()) {
			kinds.remove(id);
			proxies.remove(id).discard();
		}

		for (ProxyDiff.Entry e : plan.move()) {
			place(proxies.get(e.id()), e);
		}

		for (ProxyDiff.Entry e : plan.spawn()) {
			spawn(level, e);
		}
	}

	private static void place(final ProxyEntity proxy, final ProxyDiff.Entry e) {
		proxy.setPos(e.x(), e.y(), e.z());
		proxy.setYRot(e.yaw());
		proxy.setYHeadRot(e.yaw());
		proxy.setYBodyRot(e.yaw());
	}

	private static void spawn(final ServerLevel level, final ProxyDiff.Entry e) {
		// beyond the loaded chunks it could not be ticked or hit anyway
		if (!level.hasChunkAt(BlockPos.containing(e.x(), e.y(), e.z()))) {
			return;
		}

		EntityType<ProxyEntity> type = e.kind() == ProxyDiff.Kind.HUMAN ? ProxyEntities.HUMAN : ProxyEntities.VEHICLE;
		ProxyEntity proxy = type.create(level, EntitySpawnReason.COMMAND);
		if (proxy == null) {
			return;
		}

		proxy.setProxyId(e.id());
		place(proxy, e);
		if (level.addFreshEntity(proxy)) {
			proxies.put(e.id(), proxy);
			kinds.put(e.id(), e.kind());
		}
	}

	/** Every server tick: no list for two seconds, or the host not driving any more: no proxies. */
	static void tick(final MinecraftServer s) {
		if (!proxies.isEmpty() && (!Cyberblock.active || ++ticksSinceList > TIMEOUT_TICKS)) {
			clear();
		}
	}

	/** Server thread: remove every proxy (the host left, or the world is about to be saved). */
	static void clear() {
		for (ProxyEntity proxy : proxies.values()) {
			proxy.discard();
		}

		proxies.clear();
		kinds.clear();
	}

	/** Server thread (Bridge.proxyById): the live proxy standing for a Cyberpunk id, or null. */
	static Entity byId(final long id) {
		ProxyEntity proxy = proxies.get(id);
		return proxy != null && !proxy.isRemoved() ? proxy : null;
	}

	/** Server thread (Bridge.proxyIdOf): the Cyberpunk id a live proxy stands for, 0 for any other entity. */
	static long idOf(final Entity entity) {
		return entity instanceof ProxyEntity proxy && !proxy.isRemoved() && proxies.get(proxy.proxyId()) == proxy ? proxy.proxyId() : 0L;
	}

	/** Server thread, from a proxy: it took damage that counts (mob: the attacking mob's entity id, -1 for none). */
	static void hit(final ProxyEntity proxy, final float damage, final String kind, final Vec3 impulse, final int mob) {
		if (Cyberblock.active) {
			Cyberblock.events.accept(Wire.hit(Long.toUnsignedString(proxy.proxyId()), Math.min(damage, 1000.0F), kind, impulse.x, impulse.y, impulse.z, mob));
		}
	}

	/** Server thread, from a proxy: it was set on fire (the host accepts 0..120 seconds). */
	static void ignite(final ProxyEntity proxy, final double seconds) {
		if (Cyberblock.active) {
			Cyberblock.events.accept(Wire.ignite(Long.toUnsignedString(proxy.proxyId()), Math.min(seconds, 120.0)));
		}
	}

	/** A hostile host could make this a log flood: say it once, then every 100th time. */
	private static void warn(final String what) {
		if (bad++ % 100 == 0) {
			Cyberblock.LOG.warn("{} (seen {} times)", what, bad);
		}
	}
}
