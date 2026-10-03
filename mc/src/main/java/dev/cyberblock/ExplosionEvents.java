package dev.cyberblock;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Explosions in the Minecraft world as {"t":"explosion"} for the host, which turns each into a real Cyberpunk explosion.
 * The vanilla explosion runs unchanged: blocks break in Minecraft, entities are hurt (hooks: ServerLevelMixin for every
 * explosion, FireworkRocketEntityMixin for the burst of a rocket, which is not a vanilla explosion).
 */
public final class ExplosionEvents {
	/** Minecraft radius of a firework burst; the rocket hurts entities by itself, without a vanilla explosion. */
	public static final double FIREWORK_POWER = 1.5;
	/** The host accepts 0..20; commands and data packs can ask for more. */
	static final double MAX_POWER = 20.0;

	private ExplosionEvents() {
	}

	/** Wind charges and wind bursts "explode" only to push and press buttons: nothing for Night City to blow up. */
	static boolean isBlast(final Level.ExplosionInteraction interaction) {
		return interaction != Level.ExplosionInteraction.TRIGGER;
	}

	/** The wire message, or null when the host should not hear of this explosion (a push, no size, or not a number). */
	static String message(final Level.ExplosionInteraction interaction, final double x, final double y, final double z, final double power, final boolean fire) {
		if (!isBlast(interaction) || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !(power > 0.0) || !Double.isFinite(power)) {
			return null;
		}

		return Wire.explosion(x, y, z, Math.min(power, MAX_POWER), fire);
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
