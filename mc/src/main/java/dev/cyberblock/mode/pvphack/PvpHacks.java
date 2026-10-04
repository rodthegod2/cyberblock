package dev.cyberblock.mode.pvphack;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Cyberware;
import dev.cyberblock.cyber.CyberwareRules;
import dev.cyberblock.cyber.Hack;
import dev.cyberblock.cyber.Hacking;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.Ram;
import dev.cyberblock.cyber.ScanInfo;
import dev.cyberblock.hack.breach.Breach;
import dev.cyberblock.hack.breach.BreachResults;
import dev.cyberblock.mode.pvphack.PvpHackRules.Party;
import dev.cyberblock.mode.pvphack.PvpHackRules.Refusal;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.JoinLock;
import dev.cyberblock.net.ServerNet;
import dev.cyberblock.skin.CyberSkins;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Hacking players on the server (megaplan 8-X1). The hack key's scan locks a player along its ray ({@link #pick}, {@link #scanned}), a
 * quickhack used on that lock goes through {@link PvpHackRules} (opt-in and war zones, RAM, the player cooldown), then:
 * <ol>
 * <li>a charged {@code firewall} implant blocks it at once and recharges 60 s;</li>
 * <li>otherwise "Intrusion detected" warns the target and opens the short Breach (6-B1, 4x4, 5 s) on his screen; solving it blocks the
 * hack, anything else (failed, timed out, aborted, never started within a few seconds) lets it land;</li>
 * <li>a landed chip hits through vanilla effects and damage (a linked victim's Steve carries them to V as today), shorter against the
 * {@code netrunner} implant; Reboot Optics also sends the {@code glitch} DOWN to a Cyberpunk-linked victim.</li>
 * </ol>
 * Server thread.
 */
public final class PvpHacks {
	/** A player's box is widened this much for the scan ray (the proxies' pick forgives a near miss too). */
	static final double PICK_SLACK = 0.3;
	/** A locked player farther than this from the hacker (blocks) has walked out of reach: the lock is gone. */
	static final double KEEP_RANGE = 96.0;
	private static final Map<UUID, Lock> locks = new HashMap<>();
	private static final Map<UUID, Intrusion> intrusions = new HashMap<>();
	private static volatile @Nullable MinecraftServer server;
	private static boolean announced;

	/** The hacker's locked player and until when (wall time, as Hacking's NPC lock). */
	private record Lock(UUID target, long untilNanos) {
	}

	/** A hack uploading into a target: who, what, when it started and lands at the latest (server ticks), and whether the target got a Breach. */
	private record Intrusion(UUID hacker, String hackerName, Hack hack, long started, long deadline, boolean breach) {
	}

	private PvpHacks() {
	}

	/** Common init, once (Cyberblock.onInitialize, after Breach). */
	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(PvpHacks::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> left(handler.getPlayer().getUUID()));
		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> clear());
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> PvpHackCommands.register(dispatcher));
	}

	/** The one line saying which paths are active (at the first server tick, after every other package installed its hooks). */
	private static void announce() {
		announced = true;
		Cyberblock.LOG.info("pvphack: ready; {}; firewall blocks one hack then recharges {} s; netrunner halves effects; Reboot Optics sends "
			+ "glitch DOWN to linked victims (a DLL without the 8D glitch pass ignores it: Minecraft overlay only)", PvpHooks.describe(),
			Cyberware.FIREWALL.cooldownTicks() / 20);
	}

	// ---- the lock --------------------------------------------------------------------------------------------------------------

	/**
	 * The player along the hack key's ray, nearer than {@code limit} (the proxy the ray found, or its reach) and not behind a block;
	 * never the hacker, a spectator or a dead player. Null when none.
	 */
	public static @Nullable ServerPlayer pick(final ServerPlayer hacker, final Vec3 eye, final Vec3 look, final double limit) {
		Vec3 end = eye.add(look.scale(limit));
		ServerPlayer best = null;
		double bestDistance = limit;
		for (ServerPlayer p : hacker.level().players()) {
			if (p == hacker || p.isSpectator() || !p.isAlive()) {
				continue;
			}

			Optional<Vec3> hit = p.getBoundingBox().inflate(PICK_SLACK).clip(eye, end);
			double distance = hit.map(eye::distanceTo).orElse(Double.MAX_VALUE);
			if (distance < bestDistance && visible(hacker, eye, hit.get())) {
				best = p;
				bestDistance = distance;
			}
		}

		return best;
	}

	private static boolean visible(final ServerPlayer hacker, final Vec3 eye, final Vec3 at) {
		HitResult block = hacker.level().clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, hacker));
		return block.getType() == HitResult.Type.MISS || block.getLocation().distanceToSqr(eye) >= at.distanceToSqr(eye) - 0.01;
	}

	/** The scan found this player: he is the hacker's lock for 5 s, and the hacker's tooltip and box show him (a scaninfo built here). */
	public static void scanned(final ServerPlayer hacker, final ServerPlayer target) {
		locks.put(hacker.getUUID(), new Lock(target.getUUID(), System.nanoTime() + Hacking.LOCK_NANOS));
		ServerNet.ui(hacker, "scaninfo", scanInfo(target, PvpHackRules.targetable(PvpHooks.pvpOn(target), PvpHooks.inWarZone(target),
			CyberSkins.linked(target))).toJson(target.getId()));
	}

	/** A player as the scan tooltip shows him: grey "none" threat when he cannot be hacked, red "high" when he can. */
	static ScanInfo scanInfo(final ServerPlayer target, final boolean hackable) {
		String name = target.getPlainTextName();
		Vec3 feet = target.position();
		return new ScanInfo(0, true, target.getId() + 1L, name.length() > 64 ? name.substring(0, 64) : name,
			CyberSkins.linked(target) ? "Cyberpunk-linked player" : "Minecraft player", hackable ? "high" : "none",
			Math.clamp(target.getHealth() / target.getMaxHealth(), 0.0F, 1.0F), false, feet.x, feet.y, feet.z, target.getBbWidth(), target.getBbHeight());
	}

	/** The scan went elsewhere (an NPC, nothing): the player lock goes. */
	public static void unlock(final UUID hacker) {
		locks.remove(hacker);
	}

	/** Whether the hacker's quickhacks go to a player now (a lock younger than 5 s). */
	public static boolean locked(final UUID hacker) {
		Lock l = locks.get(hacker);
		return l != null && System.nanoTime() - l.untilNanos() < 0;
	}

	// ---- a hack --------------------------------------------------------------------------------------------------------------------

	/** A quickhack used while a player is locked (Hacking.use): true when the hack went (the item use succeeds). */
	public static boolean use(final ServerPlayer hacker, final ItemStack stack, final Hack hack) {
		Lock l = locks.get(hacker.getUUID());
		MinecraftServer s = server;
		ServerPlayer target = l == null || s == null ? null : s.getPlayerList().getPlayer(l.target());
		if (target == null || target.level() != hacker.level() || target.distanceTo(hacker) > KEEP_RANGE) {
			unlock(hacker.getUUID());
			hacker.sendOverlayMessage(Component.translatable("cyberblock.hack.no_target", Component.keybind("key.cyberblock.hack")));
			return false;
		}

		return fire(hacker, target, hack, stack, false);
	}

	/** Whether the player's party in a hack: 8M's toggle and zones, his link, and the shields (join password, spectating, dead). */
	static Party party(final ServerPlayer p) {
		return new Party(PvpHooks.pvpOn(p), PvpHooks.inWarZone(p), CyberSkins.linked(p), JoinLock.locked(p) || p.isSpectator() || !p.isAlive());
	}

	/**
	 * Fires a chip at a player through the rules, then the firewall or the intrusion. {@code test}: the dev command (no deck, RAM or chip
	 * needed; the target may be the hacker himself, for the screenshots); every other rule holds.
	 */
	static boolean fire(final ServerPlayer hacker, final ServerPlayer target, final Hack hack, final @Nullable ItemStack stack, final boolean test) {
		int ram = CyberData.ram(hacker);
		int cost = Ram.cost(hack, CyberData.has(hacker, Perk.CHEAP_HACKS));
		boolean self = hacker == target;
		Refusal refusal = PvpHackRules.check(hack, self && !test, party(hacker), party(target), test || CyberData.hasDeck(hacker), test ? cost : ram, cost,
			intrusions.containsKey(target.getUUID()));
		if (refusal != Refusal.NONE) {
			hacker.sendOverlayMessage(refusal(refusal, target, hack, ram, cost));
			Cyberblock.LOG.info("pvphack: {} -> {} {}: refused ({})", hacker.getPlainTextName(), target.getPlainTextName(), hack.wire(),
				refusal.name().toLowerCase(Locale.ROOT));
			return false;
		}

		if (!test) {
			CyberData.setRam(hacker, ram - cost);
			if (stack != null) {
				hacker.getCooldowns().addCooldown(stack, PvpHackRules.cooldownTicks(hack));
			}
		}

		if (Hacking.firewallBlocks(target)) {
			firewalled(hacker, target, hack);
			return true;
		}

		long now = tick();
		boolean breach = Breach.open(target, new Breach.Spec("player", 1, 0, true, "0"), null,
			o -> breachEnded(target.getUUID(), now, o));
		int ticks = PvpHackRules.uploadTicks(breach);
		intrusions.put(target.getUUID(), new Intrusion(hacker.getUUID(), hacker.getPlainTextName(), hack, now, now + ticks, breach));
		ServerNet.ui(target, PvpHackWire.UI, PvpHackWire.intrusion(hacker.getPlainTextName(), hack, ticks * 50, breach));
		target.sendOverlayMessage(Component.translatableWithFallback("cyberblock.pvphack.intrusion.bar", "Intrusion detected: %s is uploading %s",
			hacker.getPlainTextName(), hackName(hack)).withColor(0xFF4B4B));
		sound(target, SoundEvents.BEACON_DEACTIVATE, 1.6F);
		hacker.sendOverlayMessage(Component.translatableWithFallback("cyberblock.pvphack.uploading", "Uploading %s into %s",
			hackName(hack), target.getPlainTextName()).withColor(0x5FF4FF));
		Cyberblock.LOG.info("pvphack: {} -> {} {}: intrusion ({}{})", hacker.getPlainTextName(), target.getPlainTextName(), hack.wire(),
			breach ? "short breach, lands in " + ticks / 20.0 + " s at the latest" : "no breach possible, lands in " + ticks / 20.0 + " s",
			test ? ", test" : "");
		return true;
	}

	/** The target's charged Firewall stopped the hack: both hear it, the implant recharges (its HUD bar). */
	private static void firewalled(final ServerPlayer hacker, final ServerPlayer target, final Hack hack) {
		int seconds = Cyberware.FIREWALL.cooldownTicks() / 20;
		hacker.sendOverlayMessage(Component.translatableWithFallback("cyberblock.pvphack.blocked.firewall", "%s's Firewall blocked your hack",
			target.getPlainTextName()).withColor(0xFFAA00));
		target.sendOverlayMessage(Component.translatableWithFallback("cyberblock.pvphack.firewall.self", "Your Firewall blocked %s from %s (recharging %s s)",
			hackName(hack), hacker.getPlainTextName(), seconds).withColor(0x55FF55));
		sound(target, SoundEvents.SHIELD_BLOCK.value(), 1.2F);
		burst(target, ParticleTypes.WAX_OFF, 14);
		Cyberblock.LOG.info("pvphack: {} -> {} {}: blocked by the firewall (recharging {} s)", hacker.getPlainTextName(), target.getPlainTextName(),
			hack.wire(), seconds);
	}

	/** The target's short Breach ended: solved blocks the hack, anything else lets it land. Stale outcomes (the intrusion already over) are ignored. */
	private static void breachEnded(final UUID targetId, final long started, final BreachResults.Outcome o) {
		Intrusion in = intrusions.get(targetId);
		if (in == null || in.started() != started) {
			return;
		}

		intrusions.remove(targetId);
		finish(targetId, in, PvpHackRules.result(false, o.success()), "breach " + o.state());
	}

	/** Every server tick: intrusions past their time land (a Breach never started, or the screen could not open). */
	private static void tick(final MinecraftServer s) {
		server = s;
		if (!announced) {
			announce();
		}

		long now = s.getTickCount();
		for (Map.Entry<UUID, Intrusion> e : List.copyOf(intrusions.entrySet())) {
			if (now >= e.getValue().deadline()) {
				intrusions.remove(e.getKey());
				finish(e.getKey(), e.getValue(), PvpHackRules.Result.LANDED, e.getValue().breach() ? "breach not finished in time" : "upload");
			}
		}
	}

	/** An intrusion is over: blocked (both told) or landed (the chip's effect), and the target's warning closes. */
	private static void finish(final UUID targetId, final Intrusion in, final PvpHackRules.Result result, final String why) {
		ServerPlayer target = player(targetId);
		ServerPlayer hacker = player(in.hacker());
		String targetName = target == null ? targetId.toString() : target.getPlainTextName();
		if (target == null) {
			Cyberblock.LOG.info("pvphack: {} -> {} {}: the target left: nothing lands", in.hackerName(), targetName, in.hack().wire());
			return;
		}

		ServerNet.ui(target, PvpHackWire.UI, PvpHackWire.end(result == PvpHackRules.Result.BREACH ? "blocked" : "landed"));
		if (result == PvpHackRules.Result.BREACH) {
			target.sendOverlayMessage(Component.translatableWithFallback("cyberblock.pvphack.blocked.self", "Intrusion blocked").withColor(0x55FF55));
			if (hacker != null) {
				hacker.sendOverlayMessage(Component.translatableWithFallback("cyberblock.pvphack.blocked.breach", "%s blocked your hack with a Breach",
					targetName).withColor(0xFFAA00));
			}

			Cyberblock.LOG.info("pvphack: {} -> {} {}: blocked by the breach ({})", in.hackerName(), targetName, in.hack().wire(), why);
			return;
		}

		Party now = party(target);
		if (now.shielded() || !PvpHackRules.targetable(now.pvpOn(), now.warZone(), now.linked())) {
			Cyberblock.LOG.info("pvphack: {} -> {} {}: fizzled (the target is shielded or out of PvP now)", in.hackerName(), targetName, in.hack().wire());
			return;
		}

		boolean netrunner = CyberwareRules.netrunnerResists(CyberData.installed(target));
		PvpHackRules.Effect effect = PvpHackRules.effect(in.hack(), netrunner);
		String extra = land(hacker, target, in.hack(), effect);
		target.sendOverlayMessage(Component.translatableWithFallback("cyberblock.pvphack.hit", "Hacked by %s: %s", in.hackerName(), hackName(in.hack()))
			.withColor(0xFF4B4B));
		if (hacker != null) {
			hacker.sendOverlayMessage(Component.translatableWithFallback("cyberblock.pvphack.landed", "%s landed on %s", hackName(in.hack()), targetName)
				.withColor(0x55FF55));
		}

		Cyberblock.LOG.info("pvphack: {} -> {} {}: landed ({}; {} ticks{}{})", in.hackerName(), targetName, in.hack().wire(), why, effect.ticks(),
			netrunner ? ", netrunner resisted" : "", extra);
	}

	/** The chip's effect on the target; returns what the log line adds. */
	private static String land(final @Nullable ServerPlayer hacker, final ServerPlayer target, final Hack hack, final PvpHackRules.Effect e) {
		ServerLevel level = target.level();
		DamageSource source = hacker == null ? level.damageSources().magic() : level.damageSources().indirectMagic(hacker, hacker);
		switch (hack) {
			case SHORT_CIRCUIT -> {
				target.hurtServer(level, source, e.damage());
				target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, e.ticks(), e.amplifier(), false, true), hacker);
				target.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, e.ticks(), 2, false, true), hacker);
				burst(target, ParticleTypes.ELECTRIC_SPARK, 30);
				sound(target, SoundEvents.SCULK_CLICKING, 0.6F);
			}
			case REBOOT_OPTICS -> {
				target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, e.ticks(), 0, false, true), hacker);
				ServerNet.ui(target, PvpHackWire.UI, PvpHackWire.glitch(e.ticks() * 50));
				burst(target, ParticleTypes.ENCHANT, 20);
				HostSession session = HostSessions.of(target);
				if (session != null && session.state() != HostSession.State.NONE) {
					int seconds = PvpHackRules.glitchSeconds(e.ticks());
					session.send(PvpHackWire.glitchDown(seconds));
					// no DLL feature bits exist: the DLL version names the path (one without 8D's glitch pass drops the unknown type)
					return "; glitch DOWN " + seconds + " s to " + session.name() + " (DLL " + session.dll() + ")";
				}
			}
			case CRIPPLE_MOVEMENT -> {
				target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, e.ticks(), e.amplifier(), false, true), hacker);
				burst(target, ParticleTypes.LARGE_SMOKE, 12);
			}
			case PING -> {
				List<ServerPlayer> team = team(hacker, target);
				for (ServerPlayer p : team) {
					ServerNet.ui(p, PvpHackWire.UI, PvpHackWire.ping(target.getId(), e.ticks() * 50));
				}

				return "; outlined for " + team.size() + " player(s) of the hacker's team";
			}
			case OVERHEAT -> {
				target.igniteForSeconds(e.ticks() / 20.0F);
				target.hurtServer(level, source, e.damage());
				burst(target, ParticleTypes.FLAME, 24);
				sound(target, SoundEvents.FIRECHARGE_USE, 1.0F);
			}
			case MEMORY_WIPE -> {
				return wipe(target, e.ticks());
			}
			default -> {
			}
		}

		return "";
	}

	/** Memory Wipe: the selected hotbar slot is emptied and its stack dropped at the target's feet (never deleted), picked up after a delay. */
	private static String wipe(final ServerPlayer target, final int pickupDelay) {
		Inventory inventory = target.getInventory();
		int slot = inventory.getSelectedSlot();
		ItemStack stack = inventory.getItem(slot);
		if (stack.isEmpty()) {
			return "; the selected slot was empty";
		}

		inventory.setItem(slot, ItemStack.EMPTY);
		ItemEntity drop = new ItemEntity(target.level(), target.getX(), target.getY() + 0.2, target.getZ(), stack, 0.0, 0.15, 0.0);
		drop.setPickUpDelay(pickupDelay);
		target.level().addFreshEntity(drop);
		target.sendOverlayMessage(Component.translatableWithFallback("cyberblock.pvphack.wiped", "Memory Wipe: your %s dropped at your feet",
			stack.getHoverName()).withColor(0xFF4B4B));
		sound(target, SoundEvents.ITEM_PICKUP, 0.5F);
		return "; slot " + slot + " (" + stack.getCount() + " x " + stack.getItem() + ") dropped at the feet";
	}

	/** Ping's audience: the hacker, his match team (8M) and his vanilla team allies; never the target. */
	private static List<ServerPlayer> team(final @Nullable ServerPlayer hacker, final ServerPlayer target) {
		if (hacker == null) {
			return List.of();
		}

		int team = PvpHooks.team(hacker);
		return hacker.level().players().stream().filter(p -> p != target && (p == hacker || team >= 0 && PvpHooks.team(p) == team
			|| hacker.isAlliedTo(p))).toList();
	}

	// ---- helpers -----------------------------------------------------------------------------------------------------------------

	private static Component refusal(final Refusal r, final ServerPlayer target, final Hack hack, final int ram, final int cost) {
		String name = target.getPlainTextName();
		return switch (r) {
			case SELF -> Component.translatableWithFallback("cyberblock.pvphack.refused.self", "You cannot hack yourself");
			case NOT_A_PLAYER_CHIP -> Component.translatableWithFallback("cyberblock.pvphack.refused.chip", "%s does not work on players", hackName(hack));
			case NO_DECK -> Component.translatableWithFallback("cyberblock.hack.no_deck", "No cyberdeck: install one in the Operating System slot (U) or carry one");
			case YOU_OUT -> Component.translatableWithFallback("cyberblock.pvphack.refused.you_out", "Turn your PvP on (or join a war zone) to hack players");
			case TARGET_SHIELDED -> Component.translatableWithFallback("cyberblock.pvphack.refused.shielded", "%s cannot be hacked right now", name);
			case TARGET_OUT -> Component.translatableWithFallback("cyberblock.pvphack.refused.target_out", "%s has not opted in to PvP", name);
			case BUSY -> Component.translatableWithFallback("cyberblock.pvphack.refused.busy", "%s is already being hacked", name);
			case NO_RAM -> Component.translatable("cyberblock.hack.no_ram", ram, cost);
			case NONE -> Component.empty();
		};
	}

	private static Component hackName(final Hack hack) {
		return Component.translatable("hack.cyberblock." + hack.wire());
	}

	private static void burst(final ServerPlayer target, final ParticleOptions particle, final int count) {
		target.level().sendParticles(particle, target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ(), count, 0.3, 0.5, 0.3, 0.02);
	}

	private static void sound(final ServerPlayer target, final SoundEvent sound, final float pitch) {
		target.level().playSound(null, target.getX(), target.getY(), target.getZ(), sound, SoundSource.PLAYERS, 1.0F, pitch);
	}

	private static long tick() {
		MinecraftServer s = server;
		return s == null ? 0L : s.getTickCount();
	}

	private static @Nullable ServerPlayer player(final UUID id) {
		MinecraftServer s = server;
		return s == null ? null : s.getPlayerList().getPlayer(id);
	}

	/** A player left: his lock, the intrusions into him (his Breach was aborted by Breach itself) and the locks on him go. */
	private static void left(final UUID player) {
		locks.remove(player);
		intrusions.remove(player);
		locks.values().removeIf(l -> l.target().equals(player));
	}

	private static void clear() {
		locks.clear();
		intrusions.clear();
		server = null;
		announced = false;
	}
}
