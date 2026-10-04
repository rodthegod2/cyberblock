package dev.cyberblock;

import com.google.gson.JsonObject;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The proxies in the Minecraft world: one per Cyberpunk NPC or vehicle in each host's latest {"t":"npcs"} list, created, moved and
 * removed to match it. Owner-scoped (plan 2026-10-04 section 1.4): a proxy belongs to the host whose game its id comes from, and
 * everything it reports goes to that host only. Server thread only.
 */
public final class ProxyManager {
	private static final ProxyFleet.ByOwner<ProxyEntity> fleets = new ProxyFleet.ByOwner<>();
	/** The log says how many proxies there are, and how many hits went to the hosts, at most this often (ns). */
	private static final long REPORT_NANOS = 5_000_000_000L;
	private static long lastReport = System.nanoTime() - REPORT_NANOS;
	/** What the last log line said, and the proxies spawned and removed since. */
	private static int loggedHumans = -1, loggedCars = -1, spawned, removed;
	/** Hits sent to the hosts since the last log line, and the latest one. */
	private static int hitsSent;
	private static String lastHit = "";

	private ProxyManager() {
	}

	/** {"t":"npcs"} from a host: its proxies follow the list. */
	static void update(final HostSession s, final JsonObject message) {
		MinecraftServer server = WorldBridge.server();
		if (server == null || !s.active()) {
			return;
		}

		ServerLevel level = server.overworld();
		long received = System.nanoTime();
		ProxyDiff.Parsed parsed = ProxyDiff.parse(message, level.getMinY(), level.getMaxY());
		if (parsed == null) {
			WorldModule.warn("npcs message without a list");
			return;
		}

		if (parsed.rejected() > 0) {
			WorldModule.warn(parsed.rejected() + " bad npcs entries skipped");
		}

		ProxyFleet<ProxyEntity> fleet = fleets.of(s.uuid());
		// a proxy that was killed or unloaded under us is simply gone: it is spawned again below if the host still lists it
		fleet.proxies.values().removeIf(ProxyEntity::isRemoved);
		fleet.kinds.keySet().retainAll(fleet.proxies.keySet());
		fleet.motions.keySet().retainAll(fleet.proxies.keySet());
		ProxyDiff.Plan plan = ProxyDiff.apply(fleet.kinds, parsed.entries());
		for (Long id : plan.remove()) {
			fleet.kinds.remove(id);
			fleet.motions.remove(id);
			remove(fleet.proxies.remove(id));
		}

		for (ProxyDiff.Entry e : plan.move()) {
			place(fleet.proxies.get(e.id()), e);
			fleet.motions.merge(e.id(), ProxyDiff.Motion.first(e, received), (was, first) -> was.next(e, received));
		}

		for (ProxyDiff.Entry e : plan.spawn()) {
			spawn(level, s.uuid(), fleet, e, received);
		}
	}

	private static void place(final ProxyEntity proxy, final ProxyDiff.Entry e) {
		proxy.setHostile(e.hostile());
		proxy.setPos(e.x(), e.y(), e.z());
		proxy.setYRot(e.yaw());
		proxy.setYHeadRot(e.yaw());
		proxy.setYBodyRot(e.yaw());
	}

	private static void spawn(final ServerLevel level, final UUID owner, final ProxyFleet<ProxyEntity> fleet, final ProxyDiff.Entry e, final long received) {
		// beyond the loaded chunks it could not be ticked or hit anyway
		if (!level.hasChunkAt(BlockPos.containing(e.x(), e.y(), e.z()))) {
			return;
		}

		EntityType<ProxyEntity> type = e.kind() == ProxyDiff.Kind.HUMAN ? ProxyEntities.HUMAN : ProxyEntities.VEHICLE;
		ProxyEntity proxy = type.create(level, EntitySpawnReason.COMMAND);
		if (proxy == null) {
			return;
		}

		proxy.setOwner(owner);
		proxy.setProxyId(e.id());
		place(proxy, e);
		if (level.addFreshEntity(proxy)) {
			fleet.proxies.put(e.id(), proxy);
			fleet.kinds.put(e.id(), e.kind());
			fleet.motions.put(e.id(), ProxyDiff.Motion.first(e, received));
			spawned++;
		}
	}

	/**
	 * Every server tick: a host that is not driving any more (or gone) has no proxies. A quiet list does not: the host keeps sending
	 * camera poses through its menus and saves, so the session only stops being active when it is really gone, and a list that leaves
	 * a proxy out removes it by itself.
	 */
	static void tick(final MinecraftServer s) {
		long now = System.nanoTime();
		report(now);
		for (Iterator<Map.Entry<UUID, ProxyFleet<ProxyEntity>>> it = fleets.all().entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, ProxyFleet<ProxyEntity>> e = it.next();
			HostSession session = HostSessions.of(e.getKey());
			if (session == null || !session.active()) {
				it.remove();
				clear(e.getValue());
				continue;
			}

			// between the host's lists (10 per second) every proxy goes on at the speed it was last seen at
			for (Map.Entry<Long, ProxyEntity> p : e.getValue().proxies.entrySet()) {
				ProxyDiff.Motion m = e.getValue().motions.get(p.getKey());
				if (m != null && m.moving() && !p.getValue().isRemoved()) {
					Vec3 at = m.position(now);
					p.getValue().setPos(at.x, at.y, at.z);
				}
			}
		}
	}

	/** Says in the log how many proxies there are when that changed, and how many hits went to the hosts: at most every REPORT_NANOS. */
	private static void report(final long now) {
		if (now - lastReport < REPORT_NANOS) {
			return;
		}

		int humans = 0, cars = 0;
		for (ProxyFleet<ProxyEntity> fleet : fleets.all().values()) {
			for (ProxyDiff.Kind kind : fleet.kinds.values()) {
				if (kind == ProxyDiff.Kind.HUMAN) {
					humans++;
				} else {
					cars++;
				}
			}
		}

		if (humans != loggedHumans || cars != loggedCars) {
			Cyberblock.LOG.info(ProxyDiff.summary(humans, cars, spawned, removed));
			loggedHumans = humans;
			loggedCars = cars;
			spawned = 0;
			removed = 0;
			lastReport = now;
		}

		if (hitsSent > 0) {
			Cyberblock.LOG.info("proxies: {} hits sent to hosts (latest: {})", hitsSent, lastHit);
			hitsSent = 0;
			lastReport = now;
		}
	}

	/** Remove every proxy of every host (the world is about to be saved). */
	static void clear() {
		for (ProxyFleet<ProxyEntity> fleet : fleets.all().values()) {
			clear(fleet);
		}

		fleets.all().clear();
	}

	/** The host's session ended: its proxies go (and with them their leads, as items). */
	static void end(final UUID owner) {
		ProxyFleet<ProxyEntity> fleet = fleets.remove(owner);
		if (fleet != null) {
			clear(fleet);
		}
	}

	private static void clear(final ProxyFleet<ProxyEntity> fleet) {
		for (ProxyEntity proxy : fleet.proxies.values()) {
			remove(proxy);
		}

		fleet.proxies.clear();
		fleet.kinds.clear();
		fleet.motions.clear();
	}

	/**
	 * A proxy goes; a lead on it drops as an item, as vanilla's would. Its host hears of it (a gone proxy reports nothing more), so
	 * it stops dragging the NPC: in a vehicle (an empty list), after the list timeout, out of the 48 m list.
	 */
	private static void remove(final ProxyEntity proxy) {
		removed++;
		proxy.reportRemoved();
		if (proxy.isLeashed()) {
			proxy.dropLeash();
		}

		proxy.discard();
	}

	/** {"t":"unlead","target":"id"} from a host: it gave the tether up (target gone, or it snapped). */
	static void unlead(final HostSession s, final JsonObject message) {
		ProxyEntity proxy = fleets.get(s.uuid(), MobRules.id(message.get("target")));
		if (proxy != null && !proxy.isRemoved()) {
			proxy.unlead();
		}
	}

	/** Bridge.proxyById: the live proxy standing for a Cyberpunk id of this owner's game, or null. */
	static @Nullable Entity byId(final UUID owner, final long id) {
		ProxyEntity proxy = fleets.get(owner, id);
		return proxy != null && !proxy.isRemoved() ? proxy : null;
	}

	/**
	 * Bridge.proxyAimedAt (the scan pick of plan retest2 R2): the live proxy of any host the ray from eye along dir means, within
	 * reach and a margin around its box (ProxyAim), nearest the line of sight first. Null when none.
	 */
	static @Nullable Entity aimedAt(final Vec3 eye, final Vec3 dir, final double reach) {
		List<ProxyEntity> live = new ArrayList<>();
		for (ProxyFleet<ProxyEntity> fleet : fleets.all().values()) {
			fleet.proxies.values().stream().filter(p -> !p.isRemoved()).forEach(live::add);
		}

		return ProxyAim.best(eye, dir, reach, live, Entity::getBoundingBox,
			p -> p.getType() == ProxyEntities.VEHICLE ? ProxyAim.VEHICLE_MARGIN : ProxyAim.HUMAN_MARGIN);
	}

	/** Bridge.proxyIdOf: the Cyberpunk id a live proxy stands for, 0 for any other entity. */
	static long idOf(final Entity entity) {
		return entity instanceof ProxyEntity proxy && !proxy.isRemoved() && fleets.get(proxy.owner(), proxy.proxyId()) == proxy ? proxy.proxyId() : 0L;
	}

	/** Bridge.proxyOwnerOf: the host whose game a live proxy belongs to, null for any other entity. */
	static @Nullable UUID ownerOf(final Entity entity) {
		return idOf(entity) != 0L ? ((ProxyEntity) entity).owner() : null;
	}

	/**
	 * A message about a proxy for its owner, when the owner's session is active and, for a guest's action (actor: the player behind
	 * it, null when none), the owner's damage toggle allows it (plan section 1.6). A blocked guest is told. True when it went.
	 */
	static boolean toOwner(final ProxyEntity proxy, final String message, final @Nullable Entity actor) {
		UUID owner = proxy.owner();
		HostSession s = owner == null ? null : HostSessions.of(owner);
		if (s == null || !s.active()) {
			return false;
		}

		if (!WorldModule.allows(owner, actor, WorldModule.DAMAGE)) {
			WorldModule.blocked(actor);
			return false;
		}

		s.send(message);
		return true;
	}

	/** From a proxy: it took damage that counts (mob: the attacking mob's entity id, -1 for none; attacker: who dealt it). */
	static void hit(final ProxyEntity proxy, final float damage, final String kind, final Vec3 impulse, final int mob, final @Nullable Entity attacker) {
		String id = Long.toUnsignedString(proxy.proxyId());
		if (toOwner(proxy, Wire.hit(id, Math.min(damage, 1000.0F), kind, impulse.x, impulse.y, impulse.z, mob), attacker)) {
			hitsSent++;
			lastHit = kind + " " + damage + " dmg on " + id;
		}
	}

	/** From a proxy: it was set on fire (the host accepts 0..120 seconds); actor: who did it, when known. */
	static void ignite(final ProxyEntity proxy, final double seconds, final @Nullable Entity actor) {
		toOwner(proxy, Wire.ignite(Long.toUnsignedString(proxy.proxyId()), Math.min(seconds, 120.0)), actor);
	}
}
