package dev.cyberblock.cyber;

import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What the implants do in Minecraft (plan retest2 section 1.7, task C4), with their particles and sounds, and the
 * `cyberfx` that gives V the Cyberpunk side where there is one. Server thread. Passive implants are attribute modifiers
 * (checked every tick: the arms only count with an empty main hand); ACTIVE ones fire from the activate key (activate);
 * AUTO ones fire by themselves (Kerenzikov on drawing a bow in the air, the Biomonitor at low health, the Second Heart
 * instead of death). Firing times are kept in CyberData.CYBER_READY (level game time, saved and synced for the HUD).
 */
public final class CyberwareEffects {
	/** One attribute modifier of a passive implant (id cyberblock:cw_<id>); `emptyHand`: only while the main hand is empty. */
	private record Mod(Cyberware cyberware, Holder<Attribute> attribute, double amount, boolean emptyHand) {
	}

	private static final List<Mod> MODS = List.of(
		new Mod(Cyberware.MANTIS_BLADES, Attributes.ATTACK_DAMAGE, 6.0, true),
		new Mod(Cyberware.MANTIS_BLADES, Attributes.ATTACK_SPEED, -2.2, true),
		new Mod(Cyberware.GORILLA_ARMS, Attributes.ATTACK_DAMAGE, 4.0, true),
		new Mod(Cyberware.GORILLA_ARMS, Attributes.ATTACK_SPEED, -2.0, true),
		new Mod(Cyberware.GORILLA_ARMS, Attributes.ATTACK_KNOCKBACK, 2.0, true),
		new Mod(Cyberware.MONOWIRE, Attributes.ATTACK_DAMAGE, 3.0, true),
		new Mod(Cyberware.MONOWIRE, Attributes.ATTACK_SPEED, -1.6, true),
		new Mod(Cyberware.MONOWIRE, Attributes.ENTITY_INTERACTION_RANGE, 3.0, true),
		new Mod(Cyberware.REINFORCED_TENDONS, Attributes.SAFE_FALL_DISTANCE, 2.0, false),
		new Mod(Cyberware.FORTIFIED_ANKLES, Attributes.SAFE_FALL_DISTANCE, 4.0, false),
		new Mod(Cyberware.SUBDERMAL_ARMOR, Attributes.ARMOR, 6.0, false),
		new Mod(Cyberware.SUBDERMAL_ARMOR, Attributes.ARMOR_TOUGHNESS, 2.0, false));
	private static final int SANDEVISTAN_CYAN = 0x33E6FF;
	private static final int SANDEVISTAN_MAGENTA = 0xFF3DF2;
	private static final int BLADE_RED = 0xFF2B45;
	private static final float PLS_POWER = 2.5F;
	private static final float BIOMONITOR_HEAL = 8.0F;
	private static final double BERSERK_RADIUS = 3.0;
	/** Game time the invisibility the camo gave runs out, per player (an attack or a launcher shot breaks only that one). */
	private static final Map<UUID, Long> camoUntil = new HashMap<>();
	/** Players whose Sandevistan ran last tick (its end plays the power-down). */
	private static final Set<UUID> sandevistanRunning = new HashSet<>();
	/** Wall time the shared slow-mo ends, 0 when time runs normally. */
	private static long slowEndsMs;

	private CyberwareEffects() {
	}

	private static Identifier modifierId(final Cyberware cyberware) {
		return Identifier.fromNamespaceAndPath(Cyberblock.ID, "cw_" + cyberware.id());
	}

	// ---- the shared slow controller (Sandevistan, Kerenzikov) --------------------------------------------------------------

	/** Slows the server to SLOW_TICK_RATE for `ms` of wall time (a longer one already running is kept). */
	static void slow(final MinecraftServer server, final long ms) {
		slowEndsMs = Math.max(slowEndsMs, System.currentTimeMillis() + ms);
		server.tickRateManager().setTickRate(CyberwareRules.SLOW_TICK_RATE);
	}

	/** Back to normal ticks (the slow-mo ran out, the player left or the server stops). */
	static void endSlow(final MinecraftServer server) {
		if (slowEndsMs != 0L) {
			slowEndsMs = 0L;
			server.tickRateManager().setTickRate(CyberwareRules.NORMAL_TICK_RATE);
		}
	}

	// ---- passive implants ----------------------------------------------------------------------------------------------

	/** Every tick and on respawn: the passive implants' modifiers match what is installed (and the main hand, for the arms). */
	public static void apply(final ServerPlayer player) {
		Set<Cyberware> installed = CyberData.installed(player);
		boolean emptyHand = player.getMainHandItem().isEmpty();
		for (Mod mod : MODS) {
			PerkEffects.modifier(player, mod.attribute(), modifierId(mod.cyberware()), mod.amount(),
				installed.contains(mod.cyberware()) && (emptyHand || !mod.emptyHand()));
		}
	}

	/** Every server tick. */
	static void tick(final MinecraftServer server) {
		if (slowEndsMs != 0L && System.currentTimeMillis() >= slowEndsMs) {
			endSlow(server);
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			long now = player.level().getGameTime();
			apply(player);
			biomonitor(player, now);
			sandevistanTrail(player, now);
			Long until = camoUntil.get(player.getUUID());
			if (until != null && now >= until) {
				camoUntil.remove(player.getUUID());
			}
		}
	}

	// ---- the activate key ------------------------------------------------------------------------------------------------

	/**
	 * The activate key tapped (CyberKeys): fires the selected ACTIVE implant, or says why not (none installed, cooling down,
	 * too close). `aim`: where the camera ray hits (64 blocks at most, the Night City surface included), for the launcher.
	 */
	public static void activate(final ServerPlayer player, final @Nullable Vec3 aim) {
		Cyberware cyberware = CyberData.selected(player);
		if (cyberware == null) {
			player.sendOverlayMessage(Component.translatable("cyberblock.cyberware.none"));
			return;
		}

		long now = player.level().getGameTime();
		CyberwareRules.Timer timer = CyberData.timer(player, cyberware);
		if (!timer.ready(now)) {
			player.sendOverlayMessage(Component.translatable("cyberblock.cyberware.cooldown", Ripperdoc.name(cyberware), timer.secondsLeft(now)));
			return;
		}

		boolean fired = switch (cyberware) {
			case PROJECTILE_LAUNCH_SYSTEM -> launcher(player, aim, now);
			case SANDEVISTAN -> sandevistan(player, now);
			case BERSERK -> berserk(player, now);
			case OPTICAL_CAMO -> camo(player, now);
			default -> false;
		};
		if (fired) {
			Cyberblock.LOG.info("cyberware: activate {}", cyberware.id());
		}
	}

	/** A double-tapped sprint (the host's `key sprint`): the Sandevistan, only with both its perk and the implant. */
	public static void sprintSandevistan(final ServerPlayer player) {
		long now = player.level().getGameTime();
		if (CyberData.has(player, Perk.SANDEVISTAN) && CyberData.has(player, Cyberware.SANDEVISTAN)
			&& CyberData.timer(player, Cyberware.SANDEVISTAN).ready(now) && sandevistan(player, now)) {
			Cyberblock.LOG.info("cyberware: activate sandevistan (sprint)");
		}
	}

	/** Projectile launch system: a hitscan rocket to the aim point (4 to 64 blocks), smoke and flame along the way. */
	private static boolean launcher(final ServerPlayer player, final @Nullable Vec3 aim, final long now) {
		Vec3 eye = player.getEyePosition();
		if (aim == null || !Double.isFinite(aim.x) || !Double.isFinite(aim.y) || !Double.isFinite(aim.z) || aim.distanceTo(eye) < CyberwareRules.PLS_MIN) {
			player.sendOverlayMessage(Component.translatable("cyberblock.cyberware.too_close"));
			return false;
		}

		Vec3 to = aim.distanceTo(eye) > CyberwareRules.PLS_RANGE ? eye.add(aim.subtract(eye).normalize().scale(CyberwareRules.PLS_RANGE)) : aim;
		ServerLevel level = player.level();
		Vec3 step = to.subtract(eye).normalize();
		for (double d = 1.0; d < to.distanceTo(eye); d += 1.0) {
			Vec3 p = eye.add(step.scale(d));
			level.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
			level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
		}

		sound(player, SoundEvents.CROSSBOW_SHOOT, 1.0F);
		sound(player, SoundEvents.FIRECHARGE_USE, 1.0F);
		breakCamo(player, now);
		level.explode(player, to.x, to.y, to.z, PLS_POWER, Level.ExplosionInteraction.NONE);
		CyberData.setTimer(player, Cyberware.PROJECTILE_LAUNCH_SYSTEM,
			CyberwareRules.Timer.fire(now, 0, Cyberware.PROJECTILE_LAUNCH_SYSTEM.cooldownTicks()));
		return true;
	}

	/** Sandevistan: Speed II and slowed time (the server at 10 TPS), afterimages while it runs; V's slow-mo via `cyberfx`. */
	private static boolean sandevistan(final ServerPlayer player, final long now) {
		boolean perk = CyberData.has(player, Perk.SANDEVISTAN);
		int duration = CyberwareRules.durationTicks(Cyberware.SANDEVISTAN, perk);
		int gameTicks = CyberwareRules.gameTicks(duration, true);
		player.addEffect(new MobEffectInstance(MobEffects.SPEED, gameTicks, 1));
		slow(player.level().getServer(), duration * 50L);
		sound(player, SoundEvents.BEACON_ACTIVATE, 1.8F);
		Cyberblock.events.accept(CyberWire.cyberfx("sandevistan", duration / 20.0));
		CyberData.setTimer(player, Cyberware.SANDEVISTAN, CyberwareRules.Timer.fire(now, gameTicks, CyberwareRules.cooldownTicks(Cyberware.SANDEVISTAN, perk)));
		return true;
	}

	private static void sandevistanTrail(final ServerPlayer player, final long now) {
		if (CyberData.timer(player, Cyberware.SANDEVISTAN).active(now)) {
			sandevistanRunning.add(player.getUUID());
			ServerLevel level = player.level();
			level.sendParticles(new DustParticleOptions(SANDEVISTAN_CYAN, 1.0F), player.xo, player.yo + 1.0, player.zo, 4, 0.2, 0.5, 0.2, 0.0);
			level.sendParticles(new DustParticleOptions(SANDEVISTAN_MAGENTA, 1.0F), player.xo, player.yo + 1.0, player.zo, 4, 0.2, 0.5, 0.2, 0.0);
			level.sendParticles(ParticleTypes.END_ROD, player.xo, player.yo + 1.0, player.zo, 1, 0.2, 0.4, 0.2, 0.0);
		} else if (sandevistanRunning.remove(player.getUUID())) {
			sound(player, SoundEvents.BEACON_DEACTIVATE, 1.8F);
		}
	}

	/** Berserk: Strength, Resistance and Jump Boost, and a shockwave that throws back hostile mobs within 3 blocks. */
	private static boolean berserk(final ServerPlayer player, final long now) {
		int duration = Cyberware.BERSERK.durationTicks();
		player.addEffect(new MobEffectInstance(MobEffects.STRENGTH, duration, 0));
		player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, duration, 0));
		player.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, duration, 0));
		ServerLevel level = player.level();
		ring(level, new DustParticleOptions(BLADE_RED, 1.5F), player.position().add(0.0, 0.2, 0.0), 2.0, 24);
		level.sendParticles(ParticleTypes.ANGRY_VILLAGER, player.getX(), player.getY() + 2.0, player.getZ(), 5, 0.4, 0.2, 0.4, 0.0);
		sound(player, SoundEvents.MACE_SMASH_GROUND_HEAVY, 1.0F);
		sound(player, SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.0F);
		DamageSource source = player.damageSources().playerAttack(player);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(BERSERK_RADIUS),
			m -> m instanceof Enemy && m.isAlive() && m.distanceTo(player) <= BERSERK_RADIUS)) {
			mob.knockback(1.5, player.getX() - mob.getX(), player.getZ() - mob.getZ(), source, 0.0F);
		}

		Cyberblock.events.accept(CyberWire.cyberfx("berserk", duration / 20.0));
		CyberData.setTimer(player, Cyberware.BERSERK, CyberwareRules.Timer.fire(now, duration, Cyberware.BERSERK.cooldownTicks()));
		return true;
	}

	/** Optical camo: 10 s of Invisibility (no particles, icon shown), which V gets through the `effect` message. */
	private static boolean camo(final ServerPlayer player, final long now) {
		int duration = Cyberware.OPTICAL_CAMO.durationTicks();
		player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, duration, 0, false, false, true));
		player.level().sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.3, 0.6, 0.3, 0.05);
		sound(player, SoundEvents.ILLUSIONER_MIRROR_MOVE, 1.0F);
		camoUntil.put(player.getUUID(), now + duration);
		CyberData.setTimer(player, Cyberware.OPTICAL_CAMO, CyberwareRules.Timer.fire(now, duration, Cyberware.OPTICAL_CAMO.cooldownTicks()));
		return true;
	}

	/** Steve attacked or fired the launcher: the camo's own invisibility ends (a potion's stays), and its cooldown starts. */
	private static void breakCamo(final ServerPlayer player, final long now) {
		Long until = camoUntil.remove(player.getUUID());
		if (until == null || now >= until) {
			return;
		}

		MobEffectInstance invisibility = player.getEffect(MobEffects.INVISIBILITY);
		if (invisibility != null && !invisibility.isVisible() && invisibility.getDuration() <= until - now + 1) {
			player.removeEffect(MobEffects.INVISIBILITY);
		}

		CyberData.setTimer(player, Cyberware.OPTICAL_CAMO, CyberData.timer(player, Cyberware.OPTICAL_CAMO).stop(now));
		sound(player, SoundEvents.ILLUSIONER_MIRROR_MOVE, 0.6F);
	}

	// ---- automatic implants -------------------------------------------------------------------------------------------

	/**
	 * Kerenzikov (CyberMod's UseItemCallback): drawing a bow, crossbow or trident while V (or, without the host, Steve) is in
	 * the air slows time for 2 s; V's slow-mo via `cyberfx`.
	 */
	public static void kerenzikov(final ServerPlayer player) {
		long now = player.level().getGameTime();
		boolean airborne = Cyberblock.active ? Bridge.hostAirborne : !player.onGround();
		if (!airborne || !CyberData.has(player, Cyberware.KERENZIKOV) || !CyberData.timer(player, Cyberware.KERENZIKOV).ready(now)) {
			return;
		}

		int duration = Cyberware.KERENZIKOV.durationTicks();
		slow(player.level().getServer(), duration * 50L);
		ring(player.level(), ParticleTypes.END_ROD, player.position().add(0.0, 1.0, 0.0), 1.2, 16);
		sound(player, SoundEvents.BEACON_POWER_SELECT, 0.6F);
		Cyberblock.events.accept(CyberWire.cyberfx("kerenzikov", duration / 20.0));
		CyberData.setTimer(player, Cyberware.KERENZIKOV,
			CyberwareRules.Timer.fire(now, CyberwareRules.gameTicks(duration, true), Cyberware.KERENZIKOV.cooldownTicks()));
		Cyberblock.LOG.info("cyberware: activate kerenzikov");
	}

	/** Biomonitor, every tick: at 30 % health or less it heals 8 and gives Regeneration I (then 90 s to recharge). */
	private static void biomonitor(final ServerPlayer player, final long now) {
		if (!CyberData.has(player, Cyberware.BIOMONITOR) || !CyberwareRules.biomonitor(player.getHealth(), player.getMaxHealth())
			|| !CyberData.timer(player, Cyberware.BIOMONITOR).ready(now)) {
			return;
		}

		player.heal(BIOMONITOR_HEAL);
		player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 120, 0));
		player.level().sendParticles(ParticleTypes.HEART, player.getX(), player.getY() + 2.0, player.getZ(), 6, 0.4, 0.3, 0.4, 0.0);
		sound(player, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F);
		player.sendOverlayMessage(Component.translatable("cyberblock.cyberware.biomonitor"));
		CyberData.setTimer(player, Cyberware.BIOMONITOR, CyberwareRules.Timer.fire(now, 0, Cyberware.BIOMONITOR.cooldownTicks()));
		Cyberblock.LOG.info("cyberware: activate biomonitor");
	}

	/**
	 * Second Heart (CyberLivingEntityMixin, when no totem saved him): instead of dying he is back at full health with
	 * Regeneration II and Absorption, and the totem animation plays. Not against damage that bypasses invulnerability (the
	 * void, /kill). True when it saved him.
	 */
	public static boolean secondHeart(final ServerPlayer player, final DamageSource source) {
		long now = player.level().getGameTime();
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || !CyberData.has(player, Cyberware.SECOND_HEART)
			|| !CyberData.timer(player, Cyberware.SECOND_HEART).ready(now)) {
			return false;
		}

		player.setHealth(player.getMaxHealth());
		player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
		player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0));
		player.level().broadcastEntityEvent(player, (byte) 35);
		player.sendOverlayMessage(Component.translatable("cyberblock.cyberware.second_heart"));
		CyberData.setTimer(player, Cyberware.SECOND_HEART, CyberwareRules.Timer.fire(now, 0, Cyberware.SECOND_HEART.cooldownTicks()));
		Cyberblock.LOG.info("cyberware: activate second_heart");
		return true;
	}

	// ---- hooks -------------------------------------------------------------------------------------------------------

	/** Steve attacks (AttackEntityCallback): the camo breaks, and an empty hand shows the arms implant at work. */
	public static void onAttack(final ServerPlayer player, final Entity target) {
		breakCamo(player, player.level().getGameTime());
		if (!player.getMainHandItem().isEmpty()) {
			return;
		}

		ServerLevel level = player.level();
		Vec3 at = target.position().add(0.0, target.getBbHeight() / 2.0, 0.0);
		if (CyberData.has(player, Cyberware.MANTIS_BLADES)) {
			level.sendParticles(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
			level.sendParticles(new DustParticleOptions(BLADE_RED, 1.2F), at.x, at.y, at.z, 6, 0.3, 0.3, 0.3, 0.0);
			sound(player, SoundEvents.PLAYER_ATTACK_STRONG, 1.0F);
		} else if (CyberData.has(player, Cyberware.GORILLA_ARMS)) {
			level.sendParticles(ParticleTypes.GUST, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
			sound(player, SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.0F);
			sound(player, SoundEvents.MACE_SMASH_GROUND, 1.4F);
		} else if (CyberData.has(player, Cyberware.MONOWIRE)) {
			Vec3 eye = player.getEyePosition();
			Vec3 d = at.subtract(eye);
			for (double t = 0.0; t <= 1.0; t += 0.25 / Math.max(0.25, d.length())) {
				Vec3 p = eye.add(d.scale(t));
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
			}

			sound(player, SoundEvents.PLAYER_ATTACK_SWEEP, 1.8F);
		}
	}

	/** The player left or the server stops: per-player state goes and time runs normally again. */
	static void reset(final @Nullable MinecraftServer server) {
		camoUntil.clear();
		sandevistanRunning.clear();
		if (server != null) {
			endSlow(server);
		}
	}

	private static void sound(final ServerPlayer player, final SoundEvent sound, final float pitch) {
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, 1.0F, pitch);
	}

	private static void ring(final ServerLevel level, final ParticleOptions particle, final Vec3 centre, final double radius, final int points) {
		for (int i = 0; i < points; i++) {
			double a = Math.PI * 2.0 * i / points;
			level.sendParticles(particle, centre.x + Math.cos(a) * radius, centre.y, centre.z + Math.sin(a) * radius, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}
}
