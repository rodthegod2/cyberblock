package dev.cyberblock.client.figure;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntities;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.figure.FigureTags;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The figures' effects on a guest's client, every client tick (megaplan task 5-F1): a red hit flash and heart particles when a
 * figure's hp drops, a muzzle flash while it fires, an explosion when it dies in one (a destroyed car always), how long it has been a
 * ragdoll or dead by the host's bits, and the red outline through walls on hostile figures (the Kiroshi technique: a gizmo box, here
 * always on top). Nothing on an active host's own client.
 */
final class FigureEffects {
	/** Ticks the red hit flash lasts. */
	static final int FLASH_TICKS = 8;
	private static final int OUTLINE_STROKE = 0xE0FF2B45, OUTLINE_FILL = 0x0CFF2B45;
	private static final Map<Entity, Fx> FX = new WeakHashMap<>();
	/** What the log last said about where walk and run come from (null: nothing yet). */
	private static @Nullable Boolean loggedBits;

	/** One figure's client-side state. */
	static final class Fx {
		float lastHp = Float.NaN;
		int flash;
		int ragdollTicks;
		int deadBitTicks;
		boolean burst;
	}

	private FigureEffects() {
	}

	/** The figure's client-side state (a fresh one for a figure this client has not ticked yet). */
	static Fx of(final Entity entity) {
		return FX.computeIfAbsent(entity, e -> new Fx());
	}

	static void tick(final Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		LocalPlayer player = minecraft.player;
		if (level == null || player == null || !FigureRenderer.drawn()) {
			return;
		}

		for (Entity e : level.entitiesForRendering()) {
			if (e instanceof ProxyEntity proxy && !proxy.isRemoved()) {
				tick(level, proxy, of(proxy));
				if (FigureRules.outlined(proxy.hostile(), proxy.dead(), proxy.distanceToSqr(player))) {
					Gizmos.cuboid(proxy.getBoundingBox().inflate(0.08), GizmoStyle.strokeAndFill(OUTLINE_STROKE, 2.5F, OUTLINE_FILL)).setAlwaysOnTop();
				}
			}
		}
	}

	private static void tick(final ClientLevel level, final ProxyEntity proxy, final Fx fx) {
		RandomSource random = level.getRandom();
		boolean car = proxy.getType() == ProxyEntities.VEHICLE;
		int anim = proxy.anim();
		boolean dead = proxy.dead() || (anim & FigureTags.DEAD) != 0;
		float hp = proxy.hp();
		if (fx.flash > 0) {
			fx.flash--;
		}

		if (!Float.isNaN(fx.lastHp) && hp < fx.lastHp - 0.001F) {
			fx.flash = FLASH_TICKS;
			int hearts = Mth.clamp(Math.round((fx.lastHp - hp) * 10.0F), 1, 6);
			for (int i = 0; i < hearts; i++) {
				level.addParticle(ParticleTypes.DAMAGE_INDICATOR, proxy.getRandomX(0.5), proxy.getY(0.6), proxy.getRandomZ(0.5),
					0.0, 0.1 + random.nextDouble() * 0.1, 0.0);
			}
		}

		fx.lastHp = hp;
		fx.ragdollTicks = (anim & FigureTags.RAGDOLL) != 0 && !proxy.dead() ? fx.ragdollTicks + 1 : 0;
		fx.deadBitTicks = (anim & FigureTags.DEAD) != 0 && !proxy.dead() ? fx.deadBitTicks + 1 : 0;
		if (dead && !fx.burst) {
			fx.burst = true;
			if (proxy.blown()) {
				explode(level, proxy, car, random);
			}
		}

		if (!car && !dead && (anim & FigureTags.FIRE) != 0 && proxy.tickCount % FigureAnim.FIRE_PERIOD == 0) {
			muzzleFlash(level, proxy, random);
		}

		if (!car && !dead) {
			logPath(anim, proxy);
		}
	}

	/** A shot: a flame and a puff of smoke at the right hand, held out in front at chest height. */
	private static void muzzleFlash(final ClientLevel level, final ProxyEntity proxy, final RandomSource random) {
		float yaw = proxy.getYRot() * Mth.DEG_TO_RAD;
		double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), rx = -Mth.cos(yaw), rz = -Mth.sin(yaw);
		double x = proxy.getX() + fx * 0.85 + rx * 0.3, y = proxy.getY() + 1.4, z = proxy.getZ() + fz * 0.85 + rz * 0.3;
		level.addParticle(ParticleTypes.SMALL_FLAME, x, y, z, fx * 0.05, 0.0, fz * 0.05);
		level.addParticle(ParticleTypes.CRIT, x, y, z, fx * 0.4, random.nextGaussian() * 0.02, fz * 0.4);
		level.addParticle(ParticleTypes.SMOKE, x, y + 0.05, z, 0.0, 0.02, 0.0);
	}

	/** It died in an explosion: a big burst for a vehicle, a smaller one for a person, smoke either way. */
	private static void explode(final ClientLevel level, final ProxyEntity proxy, final boolean car, final RandomSource random) {
		level.addParticle(car ? ParticleTypes.EXPLOSION_EMITTER : ParticleTypes.EXPLOSION, proxy.getX(), proxy.getY(0.5), proxy.getZ(), 0.0, 0.0, 0.0);
		for (int i = 0; i < (car ? 12 : 5); i++) {
			level.addParticle(ParticleTypes.LARGE_SMOKE, proxy.getRandomX(1.0), proxy.getY(random.nextDouble()), proxy.getRandomZ(1.0),
				0.0, 0.05 + random.nextDouble() * 0.05, 0.0);
		}
	}

	/**
	 * Says in the log where this client's walk and run come from: from motion at the first moving figure without walk or run bits,
	 * from the host's bits at the first figure with them (at most these two lines: a slow walker without bits next to a host that
	 * sends them is no change of path).
	 */
	private static void logPath(final int anim, final ProxyEntity proxy) {
		boolean bits = FigureAnim.fromBits(anim);
		boolean moving = proxy.getX() != proxy.xo || proxy.getZ() != proxy.zo;
		if (bits ? Boolean.TRUE.equals(loggedBits) : !moving || loggedBits != null) {
			return;
		}

		loggedBits = bits;
		Cyberblock.LOG.info(bits ? "figures: walk and run from the host's anim bits" : "figures: walk and run from motion (the host sends no walk or run bits)");
	}
}
