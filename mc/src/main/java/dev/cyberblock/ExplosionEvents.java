package dev.cyberblock;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Explosions in the Minecraft world as {"t":"explosion"} for the host, which turns each into a real Cyberpunk explosion; wind
 * bursts (wind charges, breeze charges, the mace: ExplosionInteraction.TRIGGER) as {"t":"knock"}, a push. The vanilla explosion runs
 * unchanged (hooks: ServerLevelMixin for every explosion, FireworkRocketEntityMixin for the burst of a rocket, which is not a vanilla
 * explosion), except that with the demolitions perk Steve's own are half as big again.
 */
public final class ExplosionEvents {
	/** Minecraft radius of a firework burst; the rocket hurts entities by itself, without a vanilla explosion. */
	public static final double FIREWORK_POWER = 1.5;
	/** The host accepts 0..20; commands and data packs can ask for more. */
	static final double MAX_POWER = 20.0;
	/** The demolitions perk: Steve's explosions are this much bigger. */
	static final float DEMOLITIONS = 1.5F;
	/** Creepers Steve lit with flint and steel or a fire charge (server thread only; they are gone after their blast). */
	private static final Set<Entity> litBySteve = Collections.newSetFromMap(new WeakHashMap<>());

	private ExplosionEvents() {
	}

	/** Wind charges and wind bursts "explode" only to push and press buttons: nothing for Night City to blow up, only to push. */
	static boolean isBlast(final Level.ExplosionInteraction interaction) {
		return interaction != Level.ExplosionInteraction.TRIGGER;
	}

	/** The wire message (explosion, or knock for a wind burst), or null when the host should not hear of it (no size, or not a number). */
	static String message(final Level.ExplosionInteraction interaction, final double x, final double y, final double z, final double power, final boolean fire) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !(power > 0.0) || !Double.isFinite(power)) {
			return null;
		}

		if (!isBlast(interaction)) {
			return Wire.knock(x, y, z, power, Tethers.knockStrength(power));
		}

		return Wire.explosion(x, y, z, Math.min(power, MAX_POWER), fire);
	}

	/** An explosion's radius after the demolitions perk (Steve's own, with the perk: half as big again). */
	static float radius(final float r, final boolean steves, final boolean demolitions) {
		return steves && demolitions ? r * DEMOLITIONS : r;
	}

	/**
	 * Server thread, from ServerLevel.explode before it runs: the radius it runs with. Steve's explosions are the ones he caused (his
	 * TNT, a fireball he sent back, anything whose damage names him) and creepers he lit.
	 */
	public static float radius(final @Nullable Entity source, final @Nullable DamageSource damageSource, final float r) {
		Entity cause = damageSource != null ? damageSource.getEntity() : Explosion.getIndirectSourceEntity(source);
		boolean steves = cause instanceof ServerPlayer || source != null && litBySteve.contains(source);
		return radius(r, steves, Bridge.perkRank.applyAsInt("demolitions") > 0);
	}

	/** Server thread: Steve lit this creeper. */
	public static void litBySteve(final Entity creeper) {
		litBySteve.add(creeper);
	}

	/** Server thread, from ServerLevel.explode. */
	public static void report(final ServerLevel level, final Level.ExplosionInteraction interaction, final double x, final double y, final double z, final double power, final boolean fire) {
		if (WorldBridge.hostSees(level)) {
			String message = message(interaction, x, y, z, power, fire);
			if (message != null) {
				Cyberblock.events.accept(message);
			}
		}
	}

	/** Server thread, from a firework rocket that bursts with at least one star (a plain flight rocket does nothing). */
	public static void reportFirework(final ServerLevel level, final double x, final double y, final double z) {
		report(level, Level.ExplosionInteraction.NONE, x, y, z, FIREWORK_POWER, false);
	}
}
