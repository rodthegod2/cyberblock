package dev.cyberblock.mode.downed;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.device.Shops;
import dev.cyberblock.device.block.DeviceBlocks;
import dev.cyberblock.holo.HoloHooks;
import dev.cyberblock.mode.heat.HeatRules;
import dev.cyberblock.mode.heat.ServerHeat;
import dev.cyberblock.mode.spectate.Spectate;
import dev.cyberblock.net.ServerNet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Downed and revive (megaplan 8-T2): instead of dying a player goes down: crawling (the pose mixin), slowed, unable to jump, attack,
 * use or break anything, with a 60 s bleed-out. A teammate revives it by holding right-click on it for 4 s; or it pays
 * {@link DownedRules#TRAUMA_PRICE} Minecraft eddies to call a Trauma Team flyer that lands 30 s later and heals it fully (the bleed-out
 * waits meanwhile). Only when the bleed-out ends does the player really die, so Minecraft's death screen appears only then. The void,
 * {@code /kill}, a totem, creative and spectator keep vanilla's rules. Leaving while down counts as bleeding out.
 *
 * <p>Hosts go down the same way: Steve's hearts are the one health (HostDamage), so V's "death" reaches this flow, and the scripts' death
 * screen hand-off (8S) keeps Cyberpunk's own death screen away. The downed screen and HUD show on the Minecraft layer the host sees.
 *
 * <p>Wire: {@link DownedWire}. Commands: {@code /cyberblock trauma}, {@code /cyberblock giveup}. Hooks for other phases:
 * {@link #teammates} (8M), {@link #isDown}. Server thread (the client half only reads {@link #clientDowned}).
 */
public final class Downed {
	/** The client's own answer to {@link #isDown} for client-side players (the downlist); the client entrypoint installs it. */
	public static volatile Predicate<Player> clientDowned = player -> false;
	/** May the first player revive the second? 8M can replace it; the default reads match teams (HoloHooks.match), then vanilla teams. */
	public static volatile BiPredicate<ServerPlayer, ServerPlayer> teammates = Downed::defaultTeammates;
	static final Identifier SLOW = Identifier.fromNamespaceAndPath(Cyberblock.ID, "downed_slow");
	static final Identifier NO_JUMP = Identifier.fromNamespaceAndPath(Cyberblock.ID, "downed_no_jump");
	/** Crawling speed: a quarter of walking. */
	static final double CRAWL = -0.75;
	private static final Map<UUID, DownState> down = new HashMap<>();
	private static final Set<UUID> finishing = new HashSet<>();
	private static final Map<UUID, Long> refusedAt = new HashMap<>();
	private static @Nullable MinecraftServer server;

	private Downed() {
	}

	/** Common init, once (Cyberblock.onInitialize). */
	public static void init() {
		ServerLivingEntityEvents.ALLOW_DEATH.register(Downed::allowDeath);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(Downed::allowDamage);
		UseEntityCallback.EVENT.register(Downed::useEntity);
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> isDown(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) -> isDown(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> isDown(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> isDown(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		ServerTickEvents.END_SERVER_TICK.register(Downed::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> ServerNet.ui(handler.getPlayer(), DownedWire.T_LIST, DownedWire.list(down.keySet())));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
			ServerPlayer p = handler.getPlayer();
			if (server != null && down.containsKey(p.getUUID())) {
				finish(p, "left while down");
			}

			refusedAt.remove(p.getUUID());
		});
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			down.clear();
			finishing.clear();
			Cyberblock.LOG.info("downed: ready (bleed-out {} s, revive hold {} s, Trauma Team {} eddies, {} s)", DownState.BLEED_TICKS / 20,
				DownState.REVIVE_TICKS / 20, DownedRules.TRAUMA_PRICE, DownState.TRAUMA_TICKS / 20);
		});
		// a stopping server keeps its downed players at their 1 health (nobody bleeds out because the server went down)
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			down.clear();
			server = null;
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(Commands.literal("cyberblock")
			.then(Commands.literal("trauma").executes(c -> trauma(c.getSource())))
			.then(Commands.literal("giveup").executes(c -> giveUp(c.getSource())))));
		HoloHooks.downed = Downed::isDown;
	}

	/** Is this player down? On the server its state; on the client the server's downlist. */
	public static boolean isDown(final Player player) {
		if (player instanceof ServerPlayer p) {
			return down.containsKey(p.getUUID());
		}

		return clientDowned.test(player);
	}

	/** The downed players' ids (a copy). */
	public static Set<UUID> downed() {
		return Set.copyOf(down.keySet());
	}

	private static long now() {
		MinecraftServer s = server;
		return s == null ? 0 : s.getTickCount();
	}

	private static boolean allowDeath(final LivingEntity entity, final DamageSource source, final float amount) {
		if (!(entity instanceof ServerPlayer p) || server == null) {
			return true;
		}

		boolean totem = p.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || p.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
		boolean wasDown = down.containsKey(p.getUUID());
		DownedRules.Death d = DownedRules.onDeath(finishing.contains(p.getUUID()), wasDown, source.is(DamageTypeTags.BYPASSES_INVULNERABILITY), totem,
			p.isCreative() || p.isSpectator());
		if (d == DownedRules.Death.DIE) {
			if (wasDown && !finishing.contains(p.getUUID())) {
				// the void or /kill while down: no bleed-out left to wait for
				end(p);
				Spectate.feed("", "died", p.getPlainTextName());
				Cyberblock.LOG.info("downed: {} died while down ({})", p.getPlainTextName(), source.getMsgId());
			}

			return true;
		}

		goDown(p, source);
		return false;
	}

	private static void goDown(final ServerPlayer p, final DamageSource source) {
		long now = now();
		DownState st = new DownState();
		down.put(p.getUUID(), st);
		p.setHealth(1.0F);
		p.stopFallFlying();
		p.stopRiding();
		p.clearFire();
		p.setSprinting(false);
		modify(p, true);
		p.setPose(Pose.SWIMMING);
		ServerPlayer killer = ServerHeat.killer(p, source);
		String name = p.getPlainTextName();
		if (killer != null && killer != p) {
			ServerHeat.add(HeatRules.Source.PVP_KILL, killer, HeatRules.Source.PVP_KILL.amount(), p.position());
			Spectate.feed(killer.getPlainTextName(), "downed", name);
		} else {
			Spectate.feed("", "downed", name);
		}

		p.level().getServer().getPlayerList().broadcastSystemMessage(Component.translatableWithFallback("cyberblock.downed.went_down",
			"%s is down: hold right-click on them for 4 s to revive", p.getDisplayName()), false);
		Cyberblock.LOG.info("downed: {} went down ({}{})", name, source.getMsgId(), killer != null && killer != p ? " by " + killer.getPlainTextName() : "");
		sendList();
		sendStatus(p, st, now);
	}

	private static boolean allowDamage(final LivingEntity entity, final DamageSource source, final float amount) {
		if (!(entity instanceof ServerPlayer p)) {
			return true;
		}

		DownState st = down.get(p.getUUID());
		if (st == null || finishing.contains(p.getUUID()) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return true;
		}

		// a downed player takes no damage; each hit (at most one a second) costs 5 s of its bleed-out
		if (st.hit(now())) {
			p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 0.8F);
			sendStatus(p, st, now());
		}

		return false;
	}

	/** Right-click held on a downed player: the revive (server); a downed player itself uses nothing. */
	private static InteractionResult useEntity(final Player player, final Level level, final InteractionHand hand, final Entity entity, final @Nullable EntityHitResult hit) {
		if (isDown(player)) {
			return InteractionResult.FAIL;
		}

		if (!(entity instanceof Player target) || !isDown(target)) {
			return InteractionResult.PASS;
		}

		if (!(player instanceof ServerPlayer reviver) || !(target instanceof ServerPlayer downed) || hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.SUCCESS; // the client sends the packet; the off hand never revives (nor uses its item on them)
		}

		DownState st = down.get(downed.getUUID());
		long now = now();
		if (st == null || reviver.isSpectator() || reviver.distanceTo(downed) > DownedRules.REVIVE_RANGE) {
			return InteractionResult.SUCCESS;
		}

		if (!teammates.test(reviver, downed)) {
			Long last = refusedAt.get(reviver.getUUID());
			if (last == null || now - last > 40) {
				refusedAt.put(reviver.getUUID(), now);
				reviver.sendOverlayMessage(Component.translatableWithFallback("cyberblock.downed.not_team", "Only %s's teammates can revive them",
					downed.getDisplayName()));
			}

			return InteractionResult.FAIL;
		}

		boolean done = st.hold(reviver.getUUID(), now);
		ServerNet.ui(reviver, DownedWire.T_REVIVE, DownedWire.revive(new DownedWire.Revive(downed.getPlainTextName(), done ? 1.0F : st.revive(now))));
		if (done) {
			revive(downed, reviver, false);
		} else if (now % 10 == 0) {
			sendStatus(downed, st, now);
		}

		return InteractionResult.SUCCESS;
	}

	private static boolean defaultTeammates(final ServerPlayer reviver, final ServerPlayer downed) {
		HoloHooks.Match md = HoloHooks.match.apply(downed), mr = HoloHooks.match.apply(reviver);
		return DownedRules.mayRevive(reviver == downed, md.id(), md.team(), mr.id(), mr.team(), downed.getTeam() != null, reviver.isAlliedTo(downed));
	}

	private static void tick(final MinecraftServer s) {
		if (down.isEmpty()) {
			return;
		}

		long now = s.getTickCount();
		for (Map.Entry<UUID, DownState> e : List.copyOf(down.entrySet())) {
			ServerPlayer p = s.getPlayerList().getPlayer(e.getKey());
			DownState st = e.getValue();
			if (p == null) {
				down.remove(e.getKey());
				sendList();
				continue;
			}

			switch (st.tick()) {
				case BLED_OUT -> finish(p, "bled out");
				case TRAUMA_ARRIVED -> revive(p, null, true);
				case NONE -> {
					if (p.getHealth() < 1.0F) {
						p.setHealth(1.0F);
					}

					if (st.traumaCalled()) {
						flyer(p, st.traumaLeft());
					}

					if (now % 10 == 0) {
						sendStatus(p, st, now);
					}
				}
			}
		}
	}

	/** The Trauma Team flyer's approach: a light column coming down for the last 3 s, an engine sound, then the landing. */
	private static void flyer(final ServerPlayer p, final long left) {
		if (!(p.level() instanceof ServerLevel level) || left > 60) {
			return;
		}

		if (left == 60) {
			level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ELYTRA_FLYING, SoundSource.NEUTRAL, 1.5F, 1.4F);
		}

		if (left % 2 == 0) {
			double h = 2.0 + left * 0.5;
			level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + h, p.getZ(), 8, 0.6, 0.4, 0.6, 0.02);
		}
	}

	/** Revived by a teammate or by Trauma Team (reviver null). */
	static void revive(final ServerPlayer p, final @Nullable ServerPlayer reviver, final boolean trauma) {
		DownState st = down.get(p.getUUID());
		boolean flyerOnItsWay = st != null && st.traumaCalled() && !trauma;
		end(p);
		p.setHealth(trauma ? p.getMaxHealth() : DownedRules.REVIVE_HEALTH);
		String name = p.getPlainTextName();
		if (trauma) {
			p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1));
			if (p.level() instanceof ServerLevel level) {
				level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.NEUTRAL, 1.0F, 1.2F);
				level.sendParticles(ParticleTypes.FIREWORK, p.getX(), p.getY() + 1.0, p.getZ(), 30, 0.5, 0.8, 0.5, 0.05);
			}

			p.sendSystemMessage(Component.translatableWithFallback("cyberblock.downed.trauma_done", "Trauma Team: patient stabilised. Stay safe, choom."));
			Spectate.feed("Trauma Team", "trauma", name);
		} else {
			if (reviver != null) {
				p.sendSystemMessage(Component.translatableWithFallback("cyberblock.downed.revived", "%s got you back up", reviver.getDisplayName()));
				reviver.sendOverlayMessage(Component.translatableWithFallback("cyberblock.downed.you_revived", "You revived %s", p.getDisplayName()));
			}

			if (flyerOnItsWay) {
				p.sendSystemMessage(Component.translatableWithFallback("cyberblock.downed.trauma_turned_back", "Trauma Team turned back (no refunds)"));
			}

			Spectate.feed(reviver == null ? "" : reviver.getPlainTextName(), "revived", name);
		}

		Cyberblock.LOG.info("downed: {} revived ({})", name, trauma ? "Trauma Team" : reviver == null ? "?" : "by " + reviver.getPlainTextName());
	}

	/** The bleed-out ended (or the player gave up or left): the real death, with its own death message. */
	static void finish(final ServerPlayer p, final String why) {
		UUID id = p.getUUID();
		end(p);
		finishing.add(id);
		try {
			if (p.level() instanceof ServerLevel level) {
				p.hurtServer(level, bleedOut(level), Float.MAX_VALUE);
				if (p.isAlive()) {
					p.kill(level);
				}
			}
		} finally {
			finishing.remove(id);
		}

		Spectate.feed("", "bled_out", p.getPlainTextName());
		Cyberblock.LOG.info("downed: {} {} (death screen now)", p.getPlainTextName(), why);
	}

	/** Leaves the downed state (no health change): modifiers off, the lists and the player's screen told. */
	private static void end(final ServerPlayer p) {
		if (down.remove(p.getUUID()) == null) {
			return;
		}

		modify(p, false);
		sendList();
		ServerNet.ui(p, DownedWire.T_DOWNED, DownedWire.status(DownedWire.Status.UP));
	}

	private static DamageSource bleedOut(final ServerLevel level) {
		Holder<DamageType> type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.GENERIC_KILL);
		return new DamageSource(type) {
			@Override
			public Component getLocalizedDeathMessage(final LivingEntity victim) {
				return Component.translatableWithFallback("cyberblock.downed.death", "%s bled out", victim.getDisplayName());
			}
		};
	}

	/** Crawling: a quarter of the walking speed and no jump. */
	private static void modify(final ServerPlayer p, final boolean on) {
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED), jump = p.getAttribute(Attributes.JUMP_STRENGTH);
		if (speed != null) {
			speed.removeModifier(SLOW);
			if (on) {
				speed.addTransientModifier(new AttributeModifier(SLOW, CRAWL, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			}
		}

		if (jump != null) {
			jump.removeModifier(NO_JUMP);
			if (on) {
				jump.addTransientModifier(new AttributeModifier(NO_JUMP, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			}
		}
	}

	private static void sendList() {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		String json = DownedWire.list(down.keySet());
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			ServerNet.ui(p, DownedWire.T_LIST, json);
		}
	}

	private static void sendStatus(final ServerPlayer p, final DownState st, final long now) {
		UUID by = st.reviver(now);
		ServerPlayer reviver = by == null || server == null ? null : server.getPlayerList().getPlayer(by);
		boolean solo = p.level().getServer().getPlayerList().getPlayers().stream().noneMatch(o -> o != p && !o.isSpectator() && !isDown(o));
		ServerNet.ui(p, DownedWire.T_DOWNED, DownedWire.status(new DownedWire.Status(true, DownedRules.seconds(st.bleedLeft()),
			st.traumaCalled() ? DownedRules.seconds(st.traumaLeft()) : -1, st.revive(now), reviver == null ? "" : reviver.getPlainTextName(),
			p.isCreative() ? 0 : DownedRules.TRAUMA_PRICE, solo)));
	}

	/** /cyberblock trauma: a downed player pays for the flyer. */
	private static int trauma(final CommandSourceStack source) {
		ServerPlayer p = source.getPlayer();
		DownState st = p == null ? null : down.get(p.getUUID());
		if (st == null) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.downed.not_down", "You are not down"));
			return 0;
		}

		if (st.traumaCalled()) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.downed.trauma_coming", "Trauma Team is already on its way"));
			return 0;
		}

		if (!pay(p, DownedRules.TRAUMA_PRICE)) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.downed.trauma_poor", "Trauma Team wants %s eddies; you have %s",
				DownedRules.TRAUMA_PRICE, balance(p.getInventory())));
			return 0;
		}

		st.callTrauma();
		p.sendSystemMessage(Component.translatableWithFallback("cyberblock.downed.trauma_called", "Trauma Team is on its way: %s s. Hang on.",
			DownState.TRAUMA_TICKS / 20));
		Cyberblock.LOG.info("downed: {} called Trauma Team ({} eddies)", p.getPlainTextName(), p.isCreative() ? 0 : DownedRules.TRAUMA_PRICE);
		sendStatus(p, st, now());
		return 1;
	}

	/** /cyberblock giveup: bleed out now. */
	private static int giveUp(final CommandSourceStack source) {
		ServerPlayer p = source.getPlayer();
		DownState st = p == null ? null : down.get(p.getUUID());
		if (st == null) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.downed.not_down", "You are not down"));
			return 0;
		}

		st.giveUp();
		return 1;
	}

	// Minecraft eddies (megaplan 1.16): eddies first, then as few chips as cover the rest, the change back in eddies (Shops.Payment)

	static long balance(final Inventory inv) {
		return count(inv, DeviceBlocks.EDDIES) + (long) count(inv, DeviceBlocks.EDDIE_CHIP) * Shops.Payment.CHIP;
	}

	private static boolean pay(final ServerPlayer player, final int price) {
		if (player.isCreative()) {
			return true;
		}

		Inventory inv = player.getInventory();
		Shops.Payment pay = DeviceBlocks.EDDIES == null ? null : Shops.Payment.of(count(inv, DeviceBlocks.EDDIES), count(inv, DeviceBlocks.EDDIE_CHIP), price);
		if (pay == null) {
			return false;
		}

		take(inv, DeviceBlocks.EDDIES, pay.eddies());
		take(inv, DeviceBlocks.EDDIE_CHIP, pay.chips());
		for (int left = pay.change(); left > 0; left -= 64) {
			player.getInventory().placeItemBackInInventory(new ItemStack(DeviceBlocks.EDDIES, Math.min(left, 64)), Prediction.SERVER_ONLY);
		}

		return true;
	}

	private static int count(final Inventory inv, final @Nullable Item item) {
		long n = 0;
		for (int i = 0; item != null && i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item)) {
				n += s.getCount();
			}
		}

		return (int) Math.min(n, Integer.MAX_VALUE);
	}

	private static void take(final Inventory inv, final @Nullable Item item, final int n) {
		int left = n;
		for (int i = 0; item != null && i < inv.getContainerSize() && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item)) {
				int k = Math.min(left, s.getCount());
				s.shrink(k);
				left -= k;
			}
		}

		inv.setChanged();
	}
}
