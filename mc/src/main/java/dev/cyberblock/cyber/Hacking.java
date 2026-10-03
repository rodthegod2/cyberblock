package dev.cyberblock.cyber;

import dev.cyberblock.Cyberblock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Server side of scanning and hacking: the locked target (task C4), quickhack use and its answer (task C5). Server thread unless noted. */
public final class Hacking {
	/** A scanned target stays locked this long (the scan tooltip shows it as long). */
	public static final long LOCK_NANOS = 5_000_000_000L;
	/** A hack Cyberpunk never answered counts as failed (and is refunded) after this long once the host link is down. */
	static final long ANSWER_NANOS = 10_000_000_000L;
	/**
	 * The same while the host is connected: Cyberpunk keeps a `hack` while it is paused or not Active and answers it later,
	 * so a refund after 10 s would make that hack free.
	 */
	static final long CONNECTED_ANSWER_NANOS = 60_000_000_000L;
	static final double SCAN_RANGE = 48.0;
	static final double KIROSHI_SCAN_RANGE = 80.0;
	private static final AtomicInteger ids = new AtomicInteger();
	private static final Map<Integer, Pending> pending = new HashMap<>();
	private static @Nullable Locked locked;

	/**
	 * What the last successful scan found: a Cyberpunk id, whether it is a device, and (plan retest3 section 1.6) where: the
	 * feet or device base x/y/z, the height there and the scanned name, for the hack effects that play at a fixed point.
	 */
	public record Locked(long target, boolean device, long untilNanos, double x, double y, double z, double height, String name) {
	}

	/** A sent hack waiting for its `hackresult`: who paid how much, and what went where (the lock it went to). */
	private record Pending(UUID player, int cost, long sentNanos, Hack hack, Locked lock) {
	}

	private Hacking() {
	}

	/** How far the hack key's scan ray reaches (plan retest2 section 1.7): 80 blocks with Kiroshi optics installed, else 48. */
	public static double scanRange(final Set<String> installedCyberware) {
		return installedCyberware.contains("kiroshi_optics") ? KIROSHI_SCAN_RANGE : SCAN_RANGE;
	}

	/** A fresh request id for `scan` or `hack` (int >= 0, any thread). */
	public static int nextId() {
		return ids.getAndUpdate(i -> i == Integer.MAX_VALUE ? 0 : i + 1);
	}

	/** A `scaninfo` with ok: this target is the one quickhacks go to for the next 5 s. */
	public static void lock(final long target, final boolean device) {
		lock(target, device, 0.0, 0.0, 0.0, 0.0, "");
	}

	/** The same with the scan's place (feet or device base), height and name. */
	public static void lock(final long target, final boolean device, final double x, final double y, final double z, final double height,
		final String name) {
		locked = new Locked(target, device, System.nanoTime() + LOCK_NANOS, x, y, z, height, name);
	}

	/** A `scaninfo` without ok: the hack key now points at nothing, so quickhacks no longer go to the earlier target. */
	public static void unlock() {
		locked = null;
	}

	/** The locked target, or null when none was scanned in the last 5 s. Wall time: a Sandevistan slowing the ticks does not stretch it. */
	public static @Nullable Locked locked() {
		Locked l = locked;
		return l != null && System.nanoTime() - l.untilNanos() < 0 ? l : null;
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
	 * The hack key's wheel fired a chip (HackWheel): the first one of that kind in the inventory is used as if it were in his
	 * hand; one that recharges says so.
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

	/** A quickhack item used (QuickhackItem): the rules, then RAM, cooldown and the `hack` message. True when it was sent. */
	static boolean use(final ServerPlayer player, final ItemStack stack, final Hack hack) {
		Locked lock = locked();
		int ram = CyberData.ram(player);
		int cost = Ram.cost(hack, CyberData.has(player, Perk.CHEAP_HACKS));
		HackRules.Outcome outcome = HackRules.check(CyberData.hasDeck(player), lock != null, lock != null && lock.device(), hack, ram, cost);
		if (outcome == HackRules.Outcome.OK && HackFx.busy(lock.target())) {
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

		CyberData.setRam(player, ram - cost);
		player.getCooldowns().addCooldown(stack, hack.cooldownTicks());
		int id = nextId();
		pending.put(id, new Pending(player.getUUID(), cost, System.nanoTime(), hack, lock));
		Cyberblock.events.accept(CyberWire.hack(id, lock.target(), hack, CyberData.has(player, Perk.CHAIN_HACK)));
		HackFx.start(id, player, hack, lock);
		player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.hack.fired", "Hack: %s", stack.getHoverName())
			.withColor(HackFxRules.colour(hack)));
		return true;
	}

	/** Why there is no RAM (quickhacks, RAM shards): no cyberdeck installed or carried. */
	static Component noDeck(final Player player) {
		return Component.translatableWithFallback("cyberblock.hack.no_deck", "No cyberdeck: install one in the Operating System slot (U) or carry one");
	}

	/** A `hackresult`: a failure gives the RAM back and says why; a success lets HackFx finish the upload and play the impact. */
	public static void onResult(final MinecraftServer server, final HackResult result) {
		Pending p = pending.remove(result.id());
		ServerPlayer player = p == null ? null : server.getPlayerList().getPlayer(p.player());
		if (player == null) {
			return;
		}

		if (result.ok()) {
			HackFx.onResult(server, result);
			CyberAdvancements.hacked(player, p.hack());
		} else {
			HackFx.cancel(result.id());
			refund(player, p.cost());
			player.sendOverlayMessage(Component.translatable("cyberblock.hack.failed", result.reason()));
		}
	}

	private static void refund(final ServerPlayer player, final int cost) {
		CyberData.setRam(player, Math.min(CyberData.maxRam(player), CyberData.ram(player) + cost));
	}

	/** How long an unanswered hack waits for its `hackresult`: 10 s with the host link down, 60 s while it is up. */
	static long answerTimeout(final boolean hostActive) {
		return hostActive ? CONNECTED_ANSWER_NANOS : ANSWER_NANOS;
	}

	/** Every server tick: hacks Cyberpunk never answered (the link went away) are refunded. */
	static void tick(final MinecraftServer server) {
		long now = System.nanoTime();
		long timeout = answerTimeout(Cyberblock.active);
		for (Iterator<Map.Entry<Integer, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, Pending> e = it.next();
			Pending p = e.getValue();
			if (now - p.sentNanos() > timeout) {
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

	/** Server stopping: forget everything. */
	static void clear() {
		locked = null;
		pending.clear();
		HackFx.clear();
	}
}
