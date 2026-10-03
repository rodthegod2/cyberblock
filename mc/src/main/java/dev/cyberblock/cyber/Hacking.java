package dev.cyberblock.cyber;

import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Server side of scanning and hacking: the locked target (task C4), quickhack use and its answer (task C5). Server thread unless noted. */
public final class Hacking {
	/** A scanned target stays locked this long. */
	static final long LOCK_NANOS = 5_000_000_000L;
	/** A hack Cyberpunk never answered counts as failed (and is refunded) after this long. */
	static final long ANSWER_NANOS = 10_000_000_000L;
	private static final AtomicInteger ids = new AtomicInteger();
	private static final Map<Integer, Pending> pending = new HashMap<>();
	private static @Nullable Locked locked;

	/** What the last successful scan found: a Cyberpunk id, and whether it is a device. */
	public record Locked(long target, boolean device, long untilNanos) {
	}

	/** A sent hack waiting for its `hackresult`: who paid how much. */
	private record Pending(UUID player, int cost, long sentNanos) {
	}

	private Hacking() {
	}

	/** A fresh request id for `scan` or `hack` (int >= 0, any thread). */
	public static int nextId() {
		return ids.getAndUpdate(i -> i == Integer.MAX_VALUE ? 0 : i + 1);
	}

	/** A `scaninfo` with ok: this target is the one quickhacks go to for the next 5 s. */
	public static void lock(final long target, final boolean device) {
		locked = new Locked(target, device, System.nanoTime() + LOCK_NANOS);
	}

	/** The locked target, or null when none was scanned in the last 5 s. Wall time: a Sandevistan slowing the ticks does not stretch it. */
	public static @Nullable Locked locked() {
		Locked l = locked;
		return l != null && System.nanoTime() - l.untilNanos() < 0 ? l : null;
	}

	/** A quickhack item used (QuickhackItem): the rules, then RAM, cooldown and the `hack` message. True when it was sent. */
	static boolean use(final ServerPlayer player, final ItemStack stack, final Hack hack) {
		Locked lock = locked();
		boolean proxy = lock != null && !lock.device() && Bridge.proxyById.apply(lock.target()) != null;
		int ram = CyberData.ram(player);
		int cost = Ram.cost(hack, CyberData.has(player, Perk.CHEAP_HACKS));
		HackRules.Outcome outcome = HackRules.check(CyberData.hasDeck(player), lock != null, lock != null && lock.device(), proxy, hack, ram, cost);
		if (outcome != HackRules.Outcome.OK) {
			player.sendOverlayMessage(switch (outcome) {
				case NO_DECK -> Component.translatable("cyberblock.hack.no_deck");
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
		pending.put(id, new Pending(player.getUUID(), cost, System.nanoTime()));
		Cyberblock.events.accept(CyberWire.hack(id, lock.target(), hack, CyberData.has(player, Perk.CHAIN_HACK)));
		return true;
	}

	/** A `hackresult`: a failure gives the RAM back and says why; a success plays the hack sound. */
	public static void onResult(final MinecraftServer server, final HackResult result) {
		Pending p = pending.remove(result.id());
		ServerPlayer player = p == null ? null : server.getPlayerList().getPlayer(p.player());
		if (player == null) {
			return;
		}

		if (result.ok()) {
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.8F, 1.7F);
		} else {
			refund(player, p.cost());
			player.sendOverlayMessage(Component.translatable("cyberblock.hack.failed", result.reason()));
		}
	}

	private static void refund(final ServerPlayer player, final int cost) {
		CyberData.setRam(player, Math.min(CyberData.maxRam(player), CyberData.ram(player) + cost));
	}

	/** Every server tick: hacks Cyberpunk never answered (the link went away) are refunded. */
	static void tick(final MinecraftServer server) {
		long now = System.nanoTime();
		for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
			Pending p = it.next();
			if (now - p.sentNanos() > ANSWER_NANOS) {
				it.remove();
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
	}
}
