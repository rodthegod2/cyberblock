package dev.cyberblock;

import com.google.gson.JsonObject;
import dev.cyberblock.cyber.Kills;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.Permissions;
import java.util.ArrayList;
import java.util.HashMap;
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
 * everything it reports goes to that host only. Guests see the proxies as figures (task 3-F1): only while the owner's session is
 * active, and a proxy whose NPC or vehicle died (the list's `dead` flag for any death, or a `killed` for V's) stays a few seconds as
 * a body or a wreck. Server thread only.
 */
public final class ProxyManager {
	private static final ProxyFleet.ByOwner<ProxyEntity> fleets = new ProxyFleet.ByOwner<>();
	/** Each host's dead and just-gone ids (task 3-F1). */
	private static final Map<UUID, ProxyDiff.Graveyard> graves = new HashMap<>();
	/** Each host's bodies and wrecks: out of its fleet (nothing to hit or move), removed when their time is up or the session ends. */
	private static final Map<UUID, List<ProxyEntity>> corpses = new HashMap<>();
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
		ProxyDiff.Graveyard graveyard = graves.computeIfAbsent(s.uuid(), o -> new ProxyDiff.Graveyard());
		// a proxy that was killed or unloaded under us is simply gone: it is spawned again below if the host still lists it
		fleet.proxies.values().removeIf(ProxyEntity::isRemoved);
		fleet.kinds.keySet().retainAll(fleet.proxies.keySet());
		fleet.motions.keySet().retainAll(fleet.proxies.keySet());
		// the host's last sighting of an NPC that died or a car that was destroyed, whoever did it: a body or a wreck
		for (ProxyDiff.Entry e : parsed.dead()) {
			died(s.uuid(), level, e.id(), e, received, "npcs dead flag");
		}

		// a dead NPC the host still lists for a moment is not stood up again next to its body
		ProxyDiff.Plan plan = ProxyDiff.apply(fleet.kinds, graveyard.alive(parsed.entries(), received));
		for (Long id : plan.remove()) {
			ProxyDiff.Kind kind = fleet.kinds.remove(id);
			fleet.motions.remove(id);
			ProxyEntity proxy = fleet.proxies.remove(id);
			// where it left the list: a `killed` that comes after this list still leaves the body there
			graveyard.left(new ProxyDiff.Entry(id, proxy.getX(), proxy.getY(), proxy.getZ(), proxy.getYRot(), kind, false), received);
			remove(proxy);
		}

		for (ProxyDiff.Entry e : plan.move()) {
			fleet.proxies.get(e.id()).apply(e);
			fleet.motions.merge(e.id(), ProxyDiff.Motion.first(e, received), (was, first) -> was.next(e, received));
		}

		for (ProxyDiff.Entry e : plan.spawn()) {
			ProxyEntity proxy = spawn(level, s.uuid(), e);
			if (proxy != null) {
				fleet.proxies.put(e.id(), proxy);
				fleet.kinds.put(e.id(), e.kind());
				fleet.motions.put(e.id(), ProxyDiff.Motion.first(e, received));
			}
		}
	}

	/** A new proxy for the entry in the world, or null when it could not be added. */
	private static @Nullable ProxyEntity spawn(final ServerLevel level, final UUID owner, final ProxyDiff.Entry e) {
		// beyond the loaded chunks it could not be ticked or hit anyway
		if (!level.hasChunkAt(BlockPos.containing(e.x(), e.y(), e.z()))) {
			return null;
		}

		EntityType<ProxyEntity> type = e.kind() == ProxyDiff.Kind.HUMAN ? ProxyEntities.HUMAN : ProxyEntities.VEHICLE;
		ProxyEntity proxy = type.create(level, EntitySpawnReason.COMMAND);
		if (proxy == null) {
			return null;
		}

		proxy.setOwner(owner);
		proxy.setProxyId(e.id());
		proxy.apply(e);
		if (!level.addFreshEntity(proxy)) {
			return null;
		}

		spawned++;
		return proxy;
	}

	/** ProxyEntities.register (the proxies' startup): the host's kill reports make bodies and wrecks. */
	static void listen() {
		HostSessions.listen("killed", (s, m) -> {
			try {
				killed(s, m);
			} catch (RuntimeException e) {
				WorldModule.warn("bad killed message for the figures: " + e);
			}
		});
	}

	/**
	 * {"t":"killed"} from a host (Kills): an NPC or vehicle that V (Steve) hurt died. Usually the list's dead flag has laid the body
	 * already; otherwise it is laid now, also when the list already left the NPC out (where it was last seen).
	 */
	static void killed(final HostSession s, final JsonObject message) {
		MinecraftServer server = WorldBridge.server();
		Kills.Killed killed = Kills.parse(message);
		if (server == null || !s.active() || killed == null) {
			return;
		}

		died(s.uuid(), server.overworld(), killed.target(), null, System.nanoTime(), "killed");
	}

	/**
	 * The owner's NPC or vehicle id died (task 3-F1): its proxy leaves the fleet and stays as a body or a wreck for a few seconds. With
	 * no live proxy, one is laid at seen (the dead flag's position) or else where the list left it out, but only when the list left it
	 * out lately and it was not buried already, so each death leaves one body whichever report comes first. why: which report, logged.
	 */
	private static void died(final UUID owner, final ServerLevel level, final long id, final ProxyDiff.@Nullable Entry seen, final long nanos,
		final String why) {
		ProxyDiff.Entry last = graves.computeIfAbsent(owner, o -> new ProxyDiff.Graveyard()).killed(id, nanos);
		ProxyFleet<ProxyEntity> fleet = fleets.all().get(owner);
		ProxyEntity proxy = fleet == null ? null : fleet.proxies.remove(id);
		if (fleet != null) {
			fleet.kinds.remove(id);
			fleet.motions.remove(id);
		}

		if (proxy != null && !proxy.isRemoved()) {
			if (seen != null) {
				proxy.apply(seen);
			}
		} else {
			proxy = last == null ? null : spawn(level, owner, seen != null ? seen : last);
		}

		if (proxy != null) {
			bury(owner, proxy, why);
		}
	}

	/** The proxy is dead: its leash goes (the host knows), it lies there for the guests, and ProxyManager.tick removes it in time. */
	private static void bury(final UUID owner, final ProxyEntity proxy, final String why) {
		proxy.reportRemoved();
		if (proxy.isLeashed()) {
			proxy.dropLeash();
		}

		proxy.die();
		corpses.computeIfAbsent(owner, o -> new ArrayList<>()).add(proxy);
		boolean car = proxy.getType() == ProxyEntities.VEHICLE;
		Cyberblock.LOG.info("figures: {} {} died ({} for {} s; from the {})", car ? "vehicle" : "person", Long.toUnsignedString(proxy.proxyId()),
			car ? "wreck" : "body", ProxyDiff.deadTicks(car ? ProxyDiff.Kind.VEHICLE : ProxyDiff.Kind.HUMAN) / 20, why);
	}

	/** Every tick: bodies and wrecks whose time is up go; with their host's session no longer active, all of them go. */
	private static void tickCorpses() {
		for (Iterator<Map.Entry<UUID, List<ProxyEntity>>> it = corpses.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, List<ProxyEntity>> e = it.next();
			HostSession session = HostSessions.of(e.getKey());
			boolean live = session != null && session.active();
			e.getValue().removeIf(p -> {
				boolean due = !live || p.isRemoved()
					|| p.deadTicks() >= ProxyDiff.deadTicks(p.getType() == ProxyEntities.VEHICLE ? ProxyDiff.Kind.VEHICLE : ProxyDiff.Kind.HUMAN);
				if (due) {
					p.discard();
				}

				return due;
			});
			if (e.getValue().isEmpty()) {
				it.remove();
			}
		}
	}

	/** The owner's bodies and wrecks go now, and what it knew of its dead. */
	private static void clearCorpses(final UUID owner) {
		List<ProxyEntity> list = corpses.remove(owner);
		if (list != null) {
			list.forEach(ProxyEntity::discard);
		}

		graves.remove(owner);
	}

	/**
	 * Every server tick: a host that is not driving any more (or gone) has no proxies. A quiet list does not: the host keeps sending
	 * camera poses through its menus and saves, so the session only stops being active when it is really gone, and a list that leaves
	 * a proxy out removes it by itself.
	 */
	static void tick(final MinecraftServer s) {
		long now = System.nanoTime();
		report(now);
		tickCorpses();
		for (Iterator<Map.Entry<UUID, ProxyFleet<ProxyEntity>>> it = fleets.all().entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, ProxyFleet<ProxyEntity>> e = it.next();
			HostSession session = HostSessions.of(e.getKey());
			// figures live only while their host's game is on (task 3-F1): an offline host leaves only its saved blocks
			if (session == null || !session.active()) {
				it.remove();
				clear(e.getValue());
				graves.remove(e.getKey());
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
		List.copyOf(corpses.keySet()).forEach(ProxyManager::clearCorpses);
		graves.clear();
	}

	/** The host's session ended: its proxies go (and with them their leads, as items), and its bodies and wrecks. */
	static void end(final UUID owner) {
		ProxyFleet<ProxyEntity> fleet = fleets.remove(owner);
		if (fleet != null) {
			clear(fleet);
		}

		clearCorpses(owner);
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

		if (!WorldModule.allows(owner, actor, Permissions.Toggle.DAMAGE)) {
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
