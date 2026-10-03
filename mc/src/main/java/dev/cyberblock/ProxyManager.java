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
	private static final Map<Long, ProxyEntity> proxies = new HashMap<>();
	private static final Map<Long, ProxyDiff.Kind> kinds = new HashMap<>();
	/** Where each proxy was last put and how fast it was going: the proxy runs on at that speed until the next list. */
	private static final Map<Long, ProxyDiff.Motion> motions = new HashMap<>();
	private static int bad;

	private ProxyManager() {
	}

	/** Link thread: the host's npcs list. */
	public static void update(final JsonObject message) {
		MinecraftServer s = WorldBridge.server();
		long received = System.nanoTime();
		if (s != null) {
			s.execute(() -> apply(s.overworld(), message, received));
		}
	}

	private static void apply(final ServerLevel level, final JsonObject message, final long received) {
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

		// a proxy that was killed or unloaded under us is simply gone: it is spawned again below if the host still lists it
		proxies.values().removeIf(ProxyEntity::isRemoved);
		kinds.keySet().retainAll(proxies.keySet());
		motions.keySet().retainAll(proxies.keySet());
		ProxyDiff.Plan plan = ProxyDiff.apply(kinds, parsed.entries());
		for (Long id : plan.remove()) {
			kinds.remove(id);
			motions.remove(id);
			remove(proxies.remove(id));
		}

		for (ProxyDiff.Entry e : plan.move()) {
			place(proxies.get(e.id()), e);
			motions.merge(e.id(), ProxyDiff.Motion.first(e, received), (was, first) -> was.next(e, received));
		}

		for (ProxyDiff.Entry e : plan.spawn()) {
			spawn(level, e, received);
		}
	}

	private static void place(final ProxyEntity proxy, final ProxyDiff.Entry e) {
		proxy.setHostile(e.hostile());
		proxy.setPos(e.x(), e.y(), e.z());
		proxy.setYRot(e.yaw());
		proxy.setYHeadRot(e.yaw());
		proxy.setYBodyRot(e.yaw());
	}

	private static void spawn(final ServerLevel level, final ProxyDiff.Entry e, final long received) {
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
			motions.put(e.id(), ProxyDiff.Motion.first(e, received));
		}
	}

	/**
	 * Every server tick: the host not driving any more means no proxies. A quiet list does not: the host keeps sending camera
	 * poses through its menus and saves, so Cyberblock.active only drops when it is really gone, and a list that leaves a proxy
	 * out removes it by itself.
	 */
	static void tick(final MinecraftServer s) {
		if (proxies.isEmpty()) {
			return;
		}

		if (!Cyberblock.active) {
			clear();
			return;
		}

		// between the host's lists (10 per second) every proxy goes on at the speed it was last seen at
		long now = System.nanoTime();
		for (Map.Entry<Long, ProxyEntity> e : proxies.entrySet()) {
			ProxyDiff.Motion m = motions.get(e.getKey());
			if (m != null && m.moving() && !e.getValue().isRemoved()) {
				Vec3 at = m.position(now);
				e.getValue().setPos(at.x, at.y, at.z);
			}
		}
	}

	/** Server thread: remove every proxy (the host left, or the world is about to be saved). */
	static void clear() {
		for (ProxyEntity proxy : proxies.values()) {
			remove(proxy);
		}

		proxies.clear();
		kinds.clear();
		motions.clear();
	}

	/**
	 * A proxy goes; a lead on it drops as an item, as vanilla's would. The host hears of it (a gone proxy reports nothing
	 * more), so it stops dragging the NPC: in a vehicle (an empty list), after the list timeout, out of the 48 m list.
	 */
	private static void remove(final ProxyEntity proxy) {
		proxy.reportRemoved();
		if (proxy.isLeashed()) {
			proxy.dropLeash();
		}

		proxy.discard();
	}

	/** Link thread: {"t":"unlead","target":"id"}: the host gave the tether up (target gone, or it snapped). */
	public static void unlead(final JsonObject message) {
		long id = MobRules.id(message.get("target"));
		MinecraftServer s = WorldBridge.server();
		if (id != 0L && s != null) {
			s.execute(() -> {
				ProxyEntity proxy = proxies.get(id);
				if (proxy != null && !proxy.isRemoved()) {
					proxy.unlead();
				}
			});
		}
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
