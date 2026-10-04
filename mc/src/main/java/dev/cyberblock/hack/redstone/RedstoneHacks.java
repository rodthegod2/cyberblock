package dev.cyberblock.hack.redstone;

import com.google.gson.JsonObject;
import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntities;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.Wire;
import dev.cyberblock.cyber.CyberWire;
import dev.cyberblock.cyber.Hack;
import dev.cyberblock.cyber.HackResult;
import dev.cyberblock.cyber.Hacking;
import dev.cyberblock.device.Device;
import dev.cyberblock.device.DeviceMap;
import dev.cyberblock.device.DeviceShape;
import dev.cyberblock.device.DeviceWire;
import dev.cyberblock.hack.actions.RangeRule;
import dev.cyberblock.hack.breach.Breach;
import dev.cyberblock.hack.redstone.RedstoneRules.Deck;
import dev.cyberblock.hack.redstone.RedstoneRules.Refusal;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.JoinLock;
import dev.cyberblock.net.Permissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The redstone hacking blocks on the server (megaplan 6-R1): links made with the Cyberdeck Link and resolved at runtime against the
 * owner's device table, the Device Relay's devact, the Breach Port's quickhacks paid from a Cyberdeck block, the Charge's explosion,
 * the sensors, the rate limit (2 actions a second per block) and the loop freeze (more than 8 toggles of one device in 4 s: 30 s).
 * Every action is checked against the owner's guest toggles for the player who placed, linked or configured the block, and against the
 * Phase 0 (h) reach (the one {@link RangeRule#SERVER} every remote action shares). Server thread only.
 *
 * <p>Log lines: {@code hack range: ...} (RangeRule's one line, at the first remote action and when the fallback takes over), {@code redstone:
 * <who> linked the <block> at x y z to <device>}, {@code redstone: relay at x y z -> devact <act> ...}, {@code redstone: <block> at x y
 * z refused (<why>)}, {@code redstone: loop: ... (cyberblock.redstone.loop)}, {@code redstone: breach port at x y z fired <hack> ...},
 * {@code redstone: charge at x y z exploded in N Night City games ...}.
 */
public final class RedstoneHacks {
	/** Answers older than this are forgotten (and a Breach Port's RAM for them is not refunded). */
	static final long ANSWER_TICKS = 1200;
	private static final DeviceTable table = new DeviceTable();
	private static final RateLimit rate = new RateLimit();
	private static final LoopGuard loops = new LoopGuard();
	private static final Map<PendingKey, Pending> pending = new HashMap<>();
	private static volatile @Nullable MinecraftServer server;

	private record PendingKey(UUID owner, int request) {
	}

	/**
	 * A devact or a hack a block sent: where from, who acts, the RAM to refund and the target's distance from V (NaN: unknown).
	 */
	private record Pending(String what, ResourceKey<Level> dimension, BlockPos pos, @Nullable BlockPos deck, int cost, UUID actor, double toV, long tick) {
	}

	private RedstoneHacks() {
	}

	static void init() {
		HostSessions.listen("devices", (s, m) -> {
			DeviceWire.Parsed p = DeviceWire.devices(m, DeviceMap::typeOf);
			if (p != null && s.active()) {
				table.update(s.uuid(), p.devices(), tick());
			}
		});
		HostSessions.listen("devstate", (s, m) -> {
			DeviceWire.State st = DeviceWire.state(m);
			if (st != null) {
				table.state(s.uuid(), st.id(), st.st(), st.fl());
			}
		});
		HostSessions.listen("devresult", (s, m) -> {
			DeviceWire.Result r = DeviceWire.result(m);
			if (r != null) {
				answered(s, r.id(), r.ok(), r.reason());
			}
		});
		HostSessions.listen("hackresult", (s, m) -> {
			HackResult r = HackResult.parse(m);
			if (r != null) {
				answered(s, r.id(), r.ok(), r.reason());
			}
		});
		HostSessions.onEnd(s -> {
			table.end(s.uuid());
			pending.keySet().removeIf(k -> k.owner().equals(s.uuid()));
		});
		// the Cyberdeck Link's block clicks run before the device layer's right-click on a device block (which would use the device)
		Identifier early = RedstoneBlocks.id("cyberdeck_link_first");
		UseBlockCallback.EVENT.addPhaseOrdering(early, Event.DEFAULT_PHASE);
		UseBlockCallback.EVENT.register(early, RedstoneHacks::onUseBlock);
		ServerTickEvents.END_SERVER_TICK.register(RedstoneHacks::tick);
		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			table.clear();
			rate.clear();
			loops.clear();
			pending.clear();
			server = null;
		});
	}

	/** The level's game time: it runs on with the world and is saved with it (the Cyberdeck block's refill). */
	static long now(final Level level) {
		return level.getLevelData().getGameTime();
	}

	/** Server ticks since start (rate limits, loops, the device table: none of them outlive the server). */
	private static long tick() {
		MinecraftServer s = server;
		return s == null ? 0L : s.getTickCount();
	}

	private static void tick(final MinecraftServer s) {
		long now = s.getTickCount();
		if (now % 20 != 0) {
			return;
		}

		table.expire(now);
		rate.prune(now);
		loops.prune(now);
		pending.values().removeIf(p -> now - p.tick() >= ANSWER_TICKS);
	}

	// ---- the blocks --------------------------------------------------------------------------------------------------------

	/** A rising edge reached a Device Relay, a Breach Port or a Charge. */
	static void fire(final ServerLevel level, final BlockPos pos, final TriggerBlock.Kind kind) {
		if (!(level.getBlockEntity(pos) instanceof HackBlockEntity be)) {
			return;
		}

		if (!rate.allow(pos.asLong(), tick())) {
			Cyberblock.LOG.debug("redstone: {} at {} over 2 actions a second: skipped", kind, pos.toShortString());
			return;
		}

		switch (kind) {
			case RELAY -> relay(level, pos, be);
			case BREACH_PORT -> breachPort(level, pos, be);
			case CHARGE -> charge(level, pos, be);
		}
	}

	private static void relay(final ServerLevel level, final BlockPos pos, final HackBlockEntity be) {
		long now = tick();
		Link link = be.link();
		HostSession s = link == null ? null : HostSessions.of(link.owner());
		Device d = s == null || s.state() == HostSession.State.NONE ? null : link.resolve(table.of(link.owner(), now), s.levelK());
		boolean frozen = link != null && loops.frozen(link.key(), now);
		UUID actor = be.actor();
		boolean allowed = d != null && RedstoneRules.allowedDevice(Permissions.of(link.owner()), privileged(link.owner(), actor), d.type());
		double toV = d == null ? Double.NaN : distanceToV(s, new Vec3(d.x(), d.y(), d.z()));
		Refusal r = RedstoneRules.relay(link != null, d, frozen, allowed, reaches(toV));
		if (r == Refusal.NONE) {
			LoopGuard.Verdict v = loops.toggle(link.key(), now);
			if (v != LoopGuard.Verdict.OK) {
				r = Refusal.FROZEN;
				if (v == LoopGuard.Verdict.TRIPPED) {
					Cyberblock.LOG.info("redstone: loop: {} was toggled more than {} times in 4 s; every relay linked to it is frozen for 30 s (cyberblock.redstone.loop)",
						be.linkLabel(), LoopGuard.LIMIT);
					tellNear(level, pos, Component.translatableWithFallback(Refusal.FROZEN.key(), Refusal.FROZEN.fallback()));
				}
			}
		}

		if (r != Refusal.NONE) {
			if (r == Refusal.FROZEN) {
				level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 12, 0.25, 0.25, 0.25, 0.02);
			}

			refuse(level, pos, be, "relay", r);
			return;
		}

		UUID asker = actor == null ? link.owner() : actor;
		for (String act : RedstoneRules.relayActs(d)) {
			int request = s.newRequest(asker);
			s.send(DeviceWire.devact(request, d, act));
			pending.put(new PendingKey(link.owner(), request), new Pending("relay " + act, level.dimension(), pos, null, 0, asker, toV, now));
			Cyberblock.LOG.info("redstone: relay at {} -> devact {} {} {} of {} (request {})", pos.toShortString(), act, d.type().id(), d.idText(), s.name(), request);
		}

		be.status = "";
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 4, 0.2, 0.05, 0.2, 0.0);
	}

	private static void breachPort(final ServerLevel level, final BlockPos pos, final HackBlockEntity be) {
		HackBlockEntity deckEntity = deckNextTo(level, pos);
		if (deckEntity == null) {
			refuse(level, pos, be, "breach port", Refusal.NO_DECK);
			return;
		}

		Hack hack = be.hack();
		UUID actor = be.actor();
		long now = tick(), gameNow = now(level);
		Vec3 centre = Vec3.atCenterOf(pos);
		double range = be.range();
		List<ProxyEntity> targets = new ArrayList<>(level.getEntitiesOfClass(ProxyEntity.class, new AABB(pos).inflate(range),
			e -> !e.dead() && e.owner() != null && Bridge.proxyIdOf.applyAsLong(e) != 0L && e.position().distanceToSqr(centre) <= range * range));
		targets.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(centre)));
		int sent = 0;
		Refusal last = targets.isEmpty() ? Refusal.NO_TARGET : Refusal.NONE;
		for (ProxyEntity e : targets) {
			if (sent >= RedstoneRules.MAX_TARGETS) {
				break;
			}

			UUID owner = e.owner();
			HostSession s = HostSessions.of(owner);
			if (s == null || !s.active()) {
				last = Refusal.UNRESOLVED;
				continue;
			}

			double toV = distanceToV(s, e.position());
			Refusal r = RedstoneRules.hack(RedstoneRules.allowed(Permissions.of(owner), privileged(owner, actor), Permissions.Toggle.HACKS), reaches(toV));
			if (r != Refusal.NONE) {
				last = r;
				continue;
			}

			Deck paid = deckEntity.deck(gameNow).take(hack.ram(), gameNow);
			if (paid == null) {
				last = Refusal.NO_RAM;
				break;
			}

			deckEntity.deck(paid);
			UUID asker = actor == null ? owner : actor;
			int request = s.newRequest(asker);
			s.send(CyberWire.hack(request, Bridge.proxyIdOf.applyAsLong(e), hack, false));
			pending.put(new PendingKey(owner, request), new Pending("breach port " + hack.wire(), level.dimension(), pos, deckEntity.getBlockPos(), hack.ram(), asker,
				toV, now));
			sent++;
		}

		if (sent == 0) {
			refuse(level, pos, be, "breach port", last);
			return;
		}

		be.status = "";
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 8, 0.3, 0.1, 0.3, 0.0);
		Cyberblock.LOG.info("redstone: breach port at {} fired {} at {} target(s) (RAM left {})", pos.toShortString(), hack.wire(), sent,
			deckEntity.deck(gameNow).ram(gameNow));
	}

	private static void charge(final ServerLevel level, final BlockPos pos, final HackBlockEntity be) {
		UUID actor = be.actor();
		Vec3 c = Vec3.atCenterOf(pos);
		int reached = 0, blocked = 0;
		for (HostSession s : HostSessions.active()) {
			ServerPlayer steve = s.player();
			if (steve == null || steve.level() != level || steve.position().distanceToSqr(c) > RedstoneRules.CHARGE_REACH * RedstoneRules.CHARGE_REACH) {
				continue;
			}

			if (RedstoneRules.allowed(Permissions.of(s.uuid()), privileged(s.uuid(), actor), Permissions.Toggle.EXPLOSIONS)) {
				s.send(Wire.explosion(c.x, c.y, c.z, RedstoneRules.CHARGE_POWER, false));
				reached++;
			} else {
				blocked++;
			}
		}

		if (blocked > 0) {
			refuse(level, pos, be, "charge", Refusal.EXPLOSIONS_OFF);
		}

		if (reached == 0) {
			if (blocked == 0) {
				be.status = "no Night City within 128 blocks";
				level.sendParticles(ParticleTypes.SMOKE, c.x, c.y + 0.5, c.z, 6, 0.2, 0.2, 0.2, 0.01);
				Cyberblock.LOG.info("redstone: charge at {} fizzled: no active host within {} blocks", pos.toShortString(), (int) RedstoneRules.CHARGE_REACH);
			}

			return;
		}

		level.removeBlock(pos, false);
		level.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 1, 0.0, 0.0, 0.0, 0.0);
		level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 2.0F, 1.0F);
		Cyberblock.LOG.info("redstone: charge at {} exploded in {} Night City game(s){}", pos.toShortString(), reached,
			blocked > 0 ? " (blocked by " + blocked + " host's explosions toggle)" : "");
	}

	/** The sensors' output now. */
	static int sense(final ServerLevel level, final BlockPos pos, final SensorBlock.Kind kind) {
		Vec3 c = Vec3.atCenterOf(pos);
		if (kind == SensorBlock.Kind.HEAT) {
			HostSession nearest = null;
			double best = Double.MAX_VALUE;
			for (HostSession s : HostSessions.all()) {
				ServerPlayer p = s.player();
				double d = p == null || p.level() != level ? Double.MAX_VALUE : p.position().distanceToSqr(c);
				if (d < best) {
					best = d;
					nearest = s;
				}
			}

			return nearest == null ? 0 : RedstoneRules.heatSignal(nearest.wanted());
		}

		if (!(level.getBlockEntity(pos) instanceof HackBlockEntity be)) {
			return 0;
		}

		double r = be.range();
		int people = 0, cars = 0, vs = 0;
		for (ProxyEntity e : level.getEntitiesOfClass(ProxyEntity.class, new AABB(pos).inflate(r), e -> !e.dead() && e.position().distanceToSqr(c) <= r * r)) {
			if (e.getType() == ProxyEntities.VEHICLE) {
				cars++;
			} else {
				people++;
			}
		}

		for (HostSession s : HostSessions.active()) {
			ServerPlayer p = s.player();
			if (p != null && p.level() == level && p.position().distanceToSqr(c) <= r * r) {
				vs++;
			}
		}

		return RedstoneRules.proximitySignal(be.mask(), people, cars, vs);
	}

	// ---- right-clicks ------------------------------------------------------------------------------------------------------

	/** A right-click with an empty hand: the Breach Port's screen, the relay's and the charge's status. */
	static void use(final ServerLevel level, final BlockPos pos, final TriggerBlock.Kind kind, final ServerPlayer player) {
		if (!(level.getBlockEntity(pos) instanceof HackBlockEntity be)) {
			return;
		}

		switch (kind) {
			case BREACH_PORT -> HackMenu.open(player, level, pos, true);
			case CHARGE -> player.sendOverlayMessage(Component.translatableWithFallback("item.cyberblock.charge.tooltip",
				"On a redstone signal it blows up in Night City"));
			case RELAY -> player.sendOverlayMessage(relayStatus(be));
		}
	}

	private static Component relayStatus(final HackBlockEntity be) {
		Link link = be.link();
		if (link == null) {
			return Component.translatableWithFallback(Refusal.NO_LINK.key(), Refusal.NO_LINK.fallback());
		}

		HostSession s = HostSessions.of(link.owner());
		boolean resolved = s != null && s.state() != HostSession.State.NONE && link.resolve(table.of(link.owner(), tick()), s.levelK()) != null;
		return resolved ? Component.translatableWithFallback("cyberblock.redstone.link.resolved", "Linked: %s", be.linkLabel())
			: Component.translatableWithFallback(Refusal.UNRESOLVED.key(), Refusal.UNRESOLVED.fallback());
	}

	static void useSensor(final ServerLevel level, final BlockPos pos, final SensorBlock.Kind kind, final ServerPlayer player) {
		if (kind == SensorBlock.Kind.PROXIMITY) {
			HackMenu.open(player, level, pos, false);
		} else {
			int signal = sense(level, pos, kind);
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.redstone.heat.level", "NCPD heat %s", signal / 3));
		}
	}

	/**
	 * The Breach Port screen's button: Breach Protocol (6B) on the linked device, anchored at the port (the player stays near it).
	 * Breach checks the owner's guest toggles for the player and tells them when it refuses.
	 */
	static void breach(final ServerLevel level, final BlockPos pos, final ServerPlayer player) {
		HackBlockEntity be = level.getBlockEntity(pos) instanceof HackBlockEntity e ? e : null;
		Link link = be == null ? null : be.link();
		if (link == null) {
			player.sendOverlayMessage(Component.translatableWithFallback(Refusal.NO_LINK.key(), Refusal.NO_LINK.fallback()));
			return;
		}

		HostSession s = HostSessions.of(link.owner());
		Device d = s == null || s.state() == HostSession.State.NONE ? null : link.resolve(table.of(link.owner(), tick()), s.levelK());
		if (d == null) {
			player.sendOverlayMessage(Component.translatableWithFallback(Refusal.UNRESOLVED.key(), Refusal.UNRESOLVED.fallback()));
		} else {
			Breach.openOnDevice(player, pos, link.owner(), d);
		}
	}

	/** The Cyberdeck block next to a Breach Port (the first of the six sides that has one), or null. */
	static @Nullable HackBlockEntity deckNextTo(final Level level, final BlockPos pos) {
		for (Direction side : Direction.values()) {
			BlockPos at = pos.relative(side);
			if (level.getBlockState(at).is(RedstoneBlocks.CYBERDECK_BLOCK) && level.getBlockEntity(at) instanceof HackBlockEntity be) {
				return be;
			}
		}

		return null;
	}

	// ---- the Cyberdeck Link ------------------------------------------------------------------------------------------------

	/** Use-block callback (an early phase): with the Cyberdeck Link in the main hand, link a relay or port, or pick a device block. */
	private static InteractionResult onUseBlock(final Player player, final Level level, final InteractionHand hand, final BlockHitResult hit) {
		if (hand != InteractionHand.MAIN_HAND || player.isSpectator() || !player.getMainHandItem().is(RedstoneBlocks.CYBERDECK_LINK)) {
			return InteractionResult.PASS;
		}

		if (JoinLock.locked(player)) {
			return InteractionResult.FAIL; // the join password comes first (megaplan 1.6)
		}

		BlockPos pos = hit.getBlockPos();
		boolean linkable = level.getBlockState(pos).getBlock() instanceof TriggerBlock t && t.kind() != TriggerBlock.Kind.CHARGE;
		if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer p)) {
			return linkable ? InteractionResult.SUCCESS : InteractionResult.PASS;
		}

		ItemStack stack = p.getMainHandItem();
		if (linkable) {
			linkBlock(server, pos, p, stack);
			return InteractionResult.SUCCESS;
		}

		Found found = deviceAt(pos);
		if (found == null) {
			return InteractionResult.PASS;
		}

		CyberdeckLinkItem.store(stack, found.link(), found.label());
		p.sendOverlayMessage(picked(found));
		return InteractionResult.SUCCESS;
	}

	/** Right-click in the air with the link: the device the player's scan locked (5 s) goes on the item. */
	static void pickLocked(final ServerPlayer player, final ItemStack stack) {
		Found found = locked(player);
		if (found == null) {
			player.sendOverlayMessage(howToLink());
			return;
		}

		CyberdeckLinkItem.store(stack, found.link(), found.label());
		player.sendOverlayMessage(picked(found));
	}

	private static Component picked(final Found found) {
		return Component.translatableWithFallback("cyberblock.redstone.link.begin", "Link started: %s. Right-click a Device Relay to finish", found.label());
	}

	private static Component howToLink() {
		return Component.translatableWithFallback(Refusal.NO_LINK.key(), Refusal.NO_LINK.fallback());
	}

	private static void linkBlock(final ServerLevel level, final BlockPos pos, final ServerPlayer player, final ItemStack stack) {
		if (!(level.getBlockEntity(pos) instanceof HackBlockEntity be)) {
			return;
		}

		Found found = locked(player);
		if (found == null) {
			CyberdeckLinkItem.Picked picked = CyberdeckLinkItem.read(stack);
			found = picked == null ? null : new Found(picked.link(), picked.label());
		}

		if (found == null) {
			player.sendOverlayMessage(howToLink());
			return;
		}

		be.link(found.link(), found.label(), player.getUUID());
		BlockState state = level.getBlockState(pos);
		if (state.hasProperty(TriggerBlock.LINKED) && !state.getValue(TriggerBlock.LINKED)) {
			level.setBlock(pos, state.setValue(TriggerBlock.LINKED, true), Block.UPDATE_CLIENTS);
		}

		player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.redstone.link.done", "Linked to %s", found.label()));
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 6, 0.25, 0.05, 0.25, 0.0);
		Cyberblock.LOG.info("redstone: {} linked the {} at {} to {}", player.getPlainTextName(), level.getBlockState(pos).getBlock().getName().getString(),
			pos.toShortString(), found.label());
	}

	private record Found(Link link, String label) {
	}

	/** The device the player's scan locked in the last 5 s, when it is in its owner's device table. */
	private static @Nullable Found locked(final ServerPlayer player) {
		Hacking.Locked lock = Hacking.locked(player.getUUID());
		HostSession s = lock == null || !lock.device() ? null : HostSessions.of(lock.owner());
		Device d = s == null ? null : table.get(lock.owner(), lock.target(), tick());
		return d == null ? null : new Found(Link.of(lock.owner(), d, s.levelK()), label(d, s));
	}

	/** The device whose block (or box) is at pos, of any host. */
	private static @Nullable Found deviceAt(final BlockPos pos) {
		long cell = pos.asLong();
		Found best = null;
		double bestSq = Double.MAX_VALUE;
		for (HostSession s : HostSessions.all()) {
			for (Device d : table.of(s.uuid(), tick())) {
				double dx = pos.getX() + 0.5 - d.x(), dz = pos.getZ() + 0.5 - d.z();
				int up = pos.getY() - (int) Math.floor(d.y());
				boolean near = Math.abs(dx) <= 1.5 && Math.abs(dz) <= 1.5 && up >= -1 && up <= 3; // attachments: the lamp over a locked door
				if (near || DeviceShape.box(d, DeviceMap.entry(d.cls(), d.type())).contains(cell)) {
					double sq = dx * dx + dz * dz + up * up;
					if (sq < bestSq) {
						bestSq = sq;
						best = new Found(Link.of(s.uuid(), d, s.levelK()), label(d, s));
					}
				}
			}
		}

		return best;
	}

	private static String label(final Device d, final HostSession owner) {
		return d.type().id() + " (" + d.cls() + ") of " + owner.name();
	}

	// ---- answers and refusals ----------------------------------------------------------------------------------------------

	/** A devresult or hackresult of an owner: ours when the request id is one a block sent. */
	private static void answered(final HostSession s, final int request, final boolean ok, final String reason) {
		Pending p = pending.remove(new PendingKey(s.uuid(), request));
		if (p == null) {
			return; // the device layer's or a player's request, or a late answer
		}

		if (RangeRule.SERVER.observe(ok, reason, p.toV(), p.what())) {
			Hacking.announceRange();
		}

		if (ok) {
			return;
		}

		MinecraftServer srv = server;
		ServerLevel level = srv == null ? null : srv.getLevel(p.dimension());
		if (level != null && p.deck() != null && level.getBlockEntity(p.deck()) instanceof HackBlockEntity deck) {
			deck.deck(deck.deck(now(level)).give(p.cost(), now(level)));
		}

		if (level != null && level.getBlockEntity(p.pos()) instanceof HackBlockEntity be) {
			be.status = reason;
		}

		tell(p.actor(), RangeRule.rangeRefusal(reason) ? Component.translatableWithFallback(Refusal.OUT_OF_RANGE.key(), Refusal.OUT_OF_RANGE.fallback())
			: Component.translatableWithFallback("cyberblock.device.refused", "Night City refused: %s", reason));
		Cyberblock.LOG.info("redstone: {} at {} refused by Night City ({})", p.what(), p.pos().toShortString(), reason);
	}

	/** A refusal is said and logged when it is new for the block (a clock on an unresolved relay says it once, not twice a second). */
	private static void refuse(final ServerLevel level, final BlockPos pos, final HackBlockEntity be, final String what, final Refusal r) {
		if (be.status.equals(r.name())) {
			return;
		}

		be.status = r.name();
		if (r != Refusal.NO_TARGET) {
			tell(be.actor(), Component.translatableWithFallback(r.key(), r.fallback()));
		}

		Cyberblock.LOG.info("redstone: {} at {} refused ({})", what, pos.toShortString(), r.name().toLowerCase(java.util.Locale.ROOT));
	}

	private static void tell(final @Nullable UUID player, final Component message) {
		MinecraftServer s = server;
		ServerPlayer p = s == null || player == null ? null : s.getPlayerList().getPlayer(player);
		if (p != null) {
			p.sendOverlayMessage(message);
		}
	}

	private static void tellNear(final ServerLevel level, final BlockPos pos, final Component message) {
		for (ServerPlayer p : level.players()) {
			if (p.blockPosition().closerThan(pos, 24.0)) {
				p.sendOverlayMessage(message);
			}
		}
	}

	/** The actor is the owner, another host, or nobody (Permissions.allows' rule): the guest toggles do not apply. */
	private static boolean privileged(final UUID owner, final @Nullable UUID actor) {
		return actor == null || actor.equals(owner) || HostSessions.isHost(actor);
	}

	/**
	 * Phase 0 (h), the server's one rule: may a block's action reach a streamed target this far from V (NaN: unknown, sent only on the
	 * documented path)? Says the rule's line at the first remote action.
	 */
	private static boolean reaches(final double toV) {
		Hacking.announceRange();
		return RangeRule.SERVER.allows(toV, true);
	}

	/** Metres from the owner's V (its player) to a place; NaN when V is not in the world. */
	private static double distanceToV(final @Nullable HostSession s, final Vec3 at) {
		ServerPlayer p = s == null ? null : s.player();
		return p == null ? Double.NaN : p.position().distanceTo(at);
	}
}
