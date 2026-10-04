package dev.cyberblock.cyber;

import com.google.gson.JsonObject;
import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.hack.actions.RangeRule;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.ServerNet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server side of scanning and hacking: the locked target (task C4), quickhack use and its answer (task C5). Megaplan
 * 2026-10-04 task 1-C1: every player has his own lock, scans and hacks go to the owner of the target through CyberRoute
 * (a guest's too, when the owner's `hacks` toggle allows it) with that owner's request ids, and the answers come back as UP
 * messages of that owner's session. Server thread.
 */
public final class Hacking {
	/** A scanned target stays locked this long (the scan tooltip shows it as long). */
	public static final long LOCK_NANOS = 5_000_000_000L;
	/** A hack Cyberpunk never answered counts as failed (and is refunded) after this long once the owner's Cyberpunk is not On (nanoseconds). */
	static final long ANSWER_NANOS = 10_000_000_000L;
	/**
	 * The same while the owner's Cyberpunk is On: it keeps a `hack` while it is paused and answers it later, so a refund after
	 * 10 s would make that hack free.
	 */
	static final long CONNECTED_ANSWER_NANOS = 60_000_000_000L;
	static final double SCAN_RANGE = 48.0;
	static final double KIROSHI_SCAN_RANGE = 80.0;
	/** A scan ray must start this close to the player's eyes (the host camera sits up to 8 m behind and beside V). */
	static final double EYE_SLACK = 16.0;
	/** No scan request for this long: the hack key was let go, and the next request is a new hold (the throttle sends at once). */
	static final long HOLD_GAP_MS = 500L;
	/** A scan's answer counts this long after the last request (the client takes answers up to 1 s after the key is let go). */
	static final long ANSWER_LINGER_MS = 1200L;
	/** The client's own pick (the scan's `target` hint) counts only within this angle of the ray (cosine). */
	private static final double HINT_COS = 0.97;
	private static final int MEMORY = 256;
	private static final Map<CyberRoute.Key, Pending> pending = new HashMap<>();
	private static final Map<UUID, Locked> locks = new HashMap<>();
	private static final Map<UUID, ScanThrottle> throttles = new HashMap<>();
	private static final Map<UUID, Long> lastScanMs = new HashMap<>();
	/** The proxy entity a sent scan picked (-1: none), for the asker's outline. */
	private static final LinkedHashMap<CyberRoute.Key, Integer> scanned = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(final Map.Entry<CyberRoute.Key, Integer> eldest) {
			return this.size() > MEMORY;
		}
	};

	/**
	 * What the player's last successful scan found: whose game (the owner session's UUID), a Cyberpunk id, whether it is a
	 * device, and (plan retest3 section 1.6) where: the feet or device base x/y/z, the height there and the scanned name, for
	 * the hack effects that play at a fixed point.
	 */
	public record Locked(UUID owner, long target, boolean device, long untilNanos, double x, double y, double z, double height, String name) {
	}

	/**
	 * A sent hack waiting for its `hackresult`: who paid how much, and what went where (the lock it went to); `distance` is the
	 * target's distance from the owner's V when it was sent (-1: unknown), for the Phase 0 (h) range check.
	 */
	private record Pending(UUID player, int cost, long sentNanos, Hack hack, Locked lock, double distance) {
	}

	/**
	 * A `hackresult` whose request id is not a quickhack's: the interaction map's remote hacks (megaplan task 6-I1, `rhack`), Breach
	 * daemons (6-B1). True when the id was the listener's own request.
	 */
	@FunctionalInterface
	public interface ResultListener {
		boolean onResult(MinecraftServer server, UUID owner, HackResult result);
	}

	private static final List<ResultListener> otherResults = new CopyOnWriteArrayList<>();

	private Hacking() {
	}

	/** Adds a listener for the `hackresult` answers to requests other than quickhacks (server thread, registration order). */
	public static void otherResults(final ResultListener listener) {
		otherResults.add(listener);
	}

	/** How far the hack key's scan ray reaches (plan retest2 section 1.7): 80 blocks with Kiroshi optics installed, else 48. */
	public static double scanRange(final Set<String> installedCyberware) {
		return installedCyberware.contains("kiroshi_optics") ? KIROSHI_SCAN_RANGE : SCAN_RANGE;
	}

	/** Reach cut at the Night City surface the host aims at (`aim` metres, negative: none), as the client's HostState.aimLimit. */
	static double aimLimit(final double reach, final double aim) {
		return aim < 0.0 ? reach : Math.min(reach, aim);
	}

	/** A `scaninfo` with ok: this target of `owner`'s game is the one the player's quickhacks go to for the next 5 s. */
	public static void lock(final UUID player, final UUID owner, final long target, final boolean device) {
		lock(player, owner, target, device, 0.0, 0.0, 0.0, 0.0, "");
	}

	/** The same with the scan's place (feet or device base), height and name. */
	public static void lock(final UUID player, final UUID owner, final long target, final boolean device, final double x, final double y,
		final double z, final double height, final String name) {
		locks.put(player, new Locked(owner, target, device, System.nanoTime() + LOCK_NANOS, x, y, z, height, name));
	}

	/** A `scaninfo` without ok: the hack key now points at nothing, so the player's quickhacks no longer go to the earlier target. */
	public static void unlock(final UUID player) {
		locks.remove(player);
	}

	/** The player's locked target, or null when none was scanned in the last 5 s. Wall time: a Sandevistan slowing the ticks does not stretch it. */
	public static @Nullable Locked locked(final UUID player) {
		Locked l = locks.get(player);
		return l != null && System.nanoTime() - l.untilNanos() < 0 ? l : null;
	}

	/**
	 * A `scan` request (CyberActs): the ray must start at the player and reaches 48 blocks (80 with Kiroshi optics), cut at the
	 * Night City surface his own host aims at; the proxy along it is picked from the server's own positions (Bridge.proxyAimedAt,
	 * which forgives a ray that passes just beside the target) and the scan goes to its owner (CyberRoute.scan), throttled as
	 * before (ScanThrottle: on change, every second, at most 5 per second).
	 */
	static void scan(final ServerPlayer player, final CyberActs.Scan scan) {
		if (scan.eye().distanceTo(player.getEyePosition()) > EYE_SLACK) {
			CyberNet.warn("scan from too far from the player dropped");
			return;
		}

		UUID uuid = player.getUUID();
		long nowMs = Util.getMillis();
		Long last = lastScanMs.put(uuid, nowMs);
		ScanThrottle throttle = throttles.computeIfAbsent(uuid, u -> new ScanThrottle());
		if (last == null || nowMs - last > HOLD_GAP_MS) {
			throttle.reset();
		}

		double reach = aimLimit(scanRange(CyberData.installedIds(player)), CyberNet.aim(player));
		Entity e = Bridge.proxyAimedAt.at(scan.eye(), scan.look(), reach);
		if (e == null && scan.target() != null) {
			e = hint(player, scan, reach);
		}

		UUID owner = e == null ? null : Bridge.proxyOwnerOf.apply(e);
		long target = owner == null ? 0L : Bridge.proxyIdOf.applyAsLong(e);
		if (target == 0L) {
			e = null;
		}

		if (!throttle.shouldSend(nowMs, e == null ? -1L : e.getId())) {
			return;
		}

		CyberRoute.Sent sent = CyberRoute.scan(uuid, target == 0L ? null : CyberNet.owner(owner), target, CyberNet.own(player));
		switch (sent.refusal()) {
			case NONE -> scanned.put(sent.key(), e == null ? -1 : e.getId());
			case BLOCKED -> {
				player.sendOverlayMessage(blocked());
				nothing(player);
			}
			case NO_HOST -> nothing(player);
		}
	}

	/** The client's own pick, when it is a live proxy within reach close to the ray. */
	private static @Nullable Entity hint(final ServerPlayer player, final CyberActs.Scan scan, final double reach) {
		Entity e = player.level().getEntity(scan.target());
		if (e == null || e.isRemoved() || Bridge.proxyIdOf.applyAsLong(e) == 0L) {
			return null;
		}

		Vec3 to = e.getBoundingBox().getCenter().subtract(scan.eye());
		double distance = to.length();
		return distance > 1.0E-6 && distance <= reach && to.scale(1.0 / distance).dot(scan.look()) >= HINT_COS ? e : null;
	}

	/** Nothing to scan (a guest aiming at nothing, or a game he may not hack): his lock and tooltip go. */
	private static void nothing(final ServerPlayer player) {
		unlock(player.getUUID());
		ServerNet.ui(player, "scaninfo", ScanInfo.NOTHING.toJson(-1));
	}

	/** The guest action bar line of megaplan section 1.6. */
	static Component blocked() {
		return Component.translatableWithFallback("cyberblock.guest.blocked", "This Cyberpunk host does not let guests do that");
	}

	/**
	 * A `scaninfo` UP from `owner`'s session: routed to the player who asked (the session's request id). A target found becomes
	 * his lock for 5 s, nothing found drops it, and the answer goes to his client (`cyberblock:ui t:"scaninfo"`, with the
	 * proxy's entity id for the outline). An answer that comes after he let the hack key go is dropped, as the client would.
	 */
	static void onScanInfo(final MinecraftServer server, final CyberRoute.Owner owner, final ScanInfo info) {
		Integer entity = scanned.remove(new CyberRoute.Key(owner.uuid(), info.id()));
		UUID asker = owner.asker(info.id());
		ServerPlayer player = asker == null ? null : server.getPlayerList().getPlayer(asker);
		Long last = asker == null ? null : lastScanMs.get(asker);
		if (player == null || last == null || Util.getMillis() - last > ANSWER_LINGER_MS) {
			return;
		}

		if (info.ok()) {
			lock(asker, owner.uuid(), info.target(), info.device(), info.x(), info.y(), info.z(), info.h(), info.name());
		} else {
			unlock(asker);
		}

		ServerNet.ui(player, "scaninfo", info.toJson(entity == null ? -1 : entity));
		if (!asker.equals(owner.uuid())) {
			// megaplan task 6-I1: a guest's hack wheel names whose Night City the target is in (the owner routing)
			HostSession session = HostSessions.of(owner.uuid());
			ServerNet.ui(player, SCAN_OWNER, scanOwner(info.ok() && session != null ? session.name() : ""));
		}
	}

	/** cyberblock:ui type for a guest: whose Night City his scanned target is in. */
	public static final String SCAN_OWNER = "scanowner";

	/** {"name":"host"} ("" when the scan found nothing). */
	static String scanOwner(final String name) {
		JsonObject m = new JsonObject();
		m.addProperty("name", name);
		return m.toString();
	}

	/**
	 * Phase 0 check (h) for a guest's quickhack (megaplan section 4.1 h, RangeRule): metres from the owner's V to the locked target,
	 * -1 when either place is unknown (a lock without a place, the owner offline); the check passes then.
	 */
	static double distanceFromV(final @Nullable ServerPlayer v, final Locked lock) {
		boolean placed = lock.x() != 0.0 || lock.y() != 0.0 || lock.z() != 0.0;
		return v == null || !placed ? -1.0 : v.position().distanceTo(new Vec3(lock.x(), lock.y(), lock.z()));
	}

	/** The first stack of hack's chip anywhere in the player's inventory (hotbar, main inventory, offhand), else EMPTY. Either side. */
	public static ItemStack findChip(final Player player, final Hack hack) {
		for (ItemStack stack : player.getInventory()) {
			if (stack.is(CyberItems.QUICKHACKS.get(hack))) {
				return stack;
			}
		}

		return ItemStack.EMPTY;
	}

	/**
	 * The hack key's wheel fired a chip (HackWheel, `act t:"hack"`): the first one of that kind in the inventory is used as if it
	 * were in his hand; one that recharges says so.
	 */
	public static void useChip(final ServerPlayer player, final Hack hack) {
		ItemStack stack = findChip(player, hack);
		if (stack.isEmpty()) {
			return;
		}

		if (player.getCooldowns().isOnCooldown(stack)) {
			player.sendOverlayMessage(Component.translatable("cyberblock.hack.cooldown"));
		} else {
			use(player, stack, hack);
		}
	}

	/**
	 * A quickhack item used (QuickhackItem): the rules, the owner's `hacks` toggle, then RAM, cooldown and the `hack` to the
	 * owner of the locked target. True when it was sent.
	 */
	static boolean use(final ServerPlayer player, final ItemStack stack, final Hack hack) {
		Locked lock = locked(player.getUUID());
		int ram = CyberData.ram(player);
		int cost = Ram.cost(hack, CyberData.has(player, Perk.CHEAP_HACKS));
		HackRules.Outcome outcome = HackRules.check(CyberData.hasDeck(player), lock != null, lock != null && lock.device(), hack, ram, cost);
		if (outcome == HackRules.Outcome.OK && HackFx.busy(lock.owner(), lock.target())) {
			// the target still uploads a hack: no RAM spent, no cooldown (section 1.6)
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.hack.busy", "%s is still uploading a hack",
				lock.name().isEmpty() ? "?" : lock.name()));
			return false;
		}

		if (outcome != HackRules.Outcome.OK) {
			player.sendOverlayMessage(switch (outcome) {
				case NO_DECK -> noDeck(player);
				case NO_TARGET -> Component.translatable("cyberblock.hack.no_target", Component.keybind("key.cyberblock.hack"));
				case NEED_DEVICE -> Component.translatable("cyberblock.hack.need_device");
				case NEED_NPC -> Component.translatable("cyberblock.hack.need_npc");
				default -> Component.translatable("cyberblock.hack.no_ram", ram, cost);
			});
			return false;
		}

		CyberRoute.Owner owner = CyberNet.owner(lock.owner());
		if (owner == null) {
			// the target's host left since the scan: nothing to hack, nothing spent
			unlock(player.getUUID());
			player.sendOverlayMessage(Component.translatable("cyberblock.hack.no_link"));
			return false;
		}

		if (CyberRoute.check(owner, player.getUUID()) != CyberRoute.Refusal.NONE) {
			player.sendOverlayMessage(blocked());
			return false;
		}

		// a guest hacks from afar (Phase 0 h): every streamed target, or only within 50 m of V once the fallback is on
		boolean remote = !owner.uuid().equals(player.getUUID());
		HostSession session = remote ? HostSessions.of(owner.uuid()) : null;
		double distance = remote ? distanceFromV(session == null ? null : session.player(), lock) : -1.0;
		if (remote) {
			announceRange();
			if (distance >= 0.0 && !RangeRule.SERVER.allows(distance, true)) {
				player.sendOverlayMessage(outOfRange());
				return false;
			}
		}

		CyberData.setRam(player, ram - cost);
		player.getCooldowns().addCooldown(stack, hack.cooldownTicks());
		CyberRoute.Key key = CyberRoute.hack(player.getUUID(), owner, lock.target(), hack, CyberData.has(player, Perk.CHAIN_HACK)).key();
		pending.put(key, new Pending(player.getUUID(), cost, System.nanoTime(), hack, lock, distance));
		HackFx.start(key, player, hack, lock);
		player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.hack.fired", "Hack: %s", stack.getHoverName())
			.withColor(HackFxRules.colour(hack)));
		return true;
	}

	/** Why there is no RAM (quickhacks, RAM shards): no cyberdeck installed or carried. */
	static Component noDeck(final Player player) {
		return Component.translatableWithFallback("cyberblock.hack.no_deck", "No cyberdeck: install one in the Operating System slot (U) or carry one");
	}

	/**
	 * A `hackresult` UP from `owner`'s session (only ids that session issued count): a failure gives the RAM back and says why; a
	 * success lets HackFx finish the upload and play the impact.
	 */
	public static void onResult(final MinecraftServer server, final UUID owner, final HackResult result) {
		CyberRoute.Key key = new CyberRoute.Key(owner, result.id());
		Pending p = pending.remove(key);
		if (p == null) {
			for (ResultListener l : otherResults) {
				if (l.onResult(server, owner, result)) {
					return;
				}
			}

			return;
		}

		if (RangeRule.SERVER.observe(result.ok(), result.reason(), p.distance(), p.hack().wire())) {
			announceRange();
		}

		ServerPlayer player = server.getPlayerList().getPlayer(p.player());
		if (player == null) {
			return;
		}

		if (result.ok()) {
			HackFx.onResult(server, key, result);
			CyberAdvancements.hacked(player, p.hack());
		} else {
			HackFx.cancel(key);
			refund(player, p.cost());
			player.sendOverlayMessage(Component.translatable("cyberblock.hack.failed", result.reason()));
		}
	}

	private static void refund(final ServerPlayer player, final int cost) {
		CyberData.setRam(player, Math.min(CyberData.maxRam(player), CyberData.ram(player) + cost));
	}

	/** The one log line saying which Phase 0 (h) path is active, at the first remote hack and when the fallback switches on. */
	public static void announceRange() {
		String line = RangeRule.SERVER.takeLine();
		if (line != null) {
			Cyberblock.LOG.info(line);
		}
	}

	/** A remote target past the Phase 0 (h) fallback range. */
	public static Component outOfRange() {
		return Component.translatableWithFallback("cyberblock.hack.out_of_range", "Out of range: from here only targets within 50 m of the host's V can be hacked");
	}

	/** How long an unanswered hack waits for its `hackresult`: 10 s while the owner's Cyberpunk is not On, 60 s while it is. */
	public static long answerTimeout(final boolean ownerActive) {
		return ownerActive ? CONNECTED_ANSWER_NANOS : ANSWER_NANOS;
	}

	/** Every server tick: hacks Cyberpunk never answered (its host went away or Off) are refunded. */
	static void tick(final MinecraftServer server) {
		long now = System.nanoTime();
		for (Iterator<Map.Entry<CyberRoute.Key, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<CyberRoute.Key, Pending> e = it.next();
			Pending p = e.getValue();
			CyberRoute.Owner owner = CyberNet.owner(e.getKey().owner());
			if (now - p.sentNanos() > answerTimeout(owner != null && owner.active())) {
				it.remove();
				HackFx.cancel(e.getKey());
				ServerPlayer player = server.getPlayerList().getPlayer(p.player());
				if (player != null) {
					refund(player, p.cost());
					player.sendOverlayMessage(Component.translatable("cyberblock.hack.no_link"));
				}
			}
		}
	}

	/** The player left: his lock and scan state go (his unanswered hacks are dropped by tick once their time is up). */
	static void forget(final UUID player) {
		locks.remove(player);
		throttles.remove(player);
		lastScanMs.remove(player);
	}

	/** A host session ended: nobody stays locked on its game (its unanswered hacks run out on the short timeout and are refunded). */
	static void ownerEnded(final UUID owner) {
		locks.values().removeIf(l -> l.owner().equals(owner));
		scanned.keySet().removeIf(k -> k.owner().equals(owner));
	}

	/** Server stopping: forget everything. */
	static void clear() {
		locks.clear();
		pending.clear();
		throttles.clear();
		lastScanMs.clear();
		scanned.clear();
		HackFx.clear();
		RangeRule.SERVER.reset();
	}
}
