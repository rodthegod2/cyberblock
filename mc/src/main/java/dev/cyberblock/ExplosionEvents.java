package dev.cyberblock;

import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Explosions in the Minecraft world as {"t":"explosion"} for the host, which turns each into a real Cyberpunk explosion; wind
 * bursts (wind charges, breeze charges, the mace: ExplosionInteraction.TRIGGER) as {"t":"knock"}, a push. The vanilla explosion runs
 * unchanged (hooks: ServerLevelMixin for every explosion, FireworkRocketEntityMixin for the burst of a rocket, which is not a vanilla
 * explosion), except that with the demolitions perk Steve's own are half as big again. Every active host whose Steve is within
 * REACH hears of it; one a guest caused only with that host's explosions toggle (plan 2026-10-04 sections 1.2 and 1.6).
 */
public final class ExplosionEvents {
	/** Minecraft radius of a firework burst; the rocket hurts entities by itself, without a vanilla explosion. */
	public static final double FIREWORK_POWER = 1.5;
	/** The host accepts 0..20; commands and data packs can ask for more. */
	static final double MAX_POWER = 20.0;
	/** Cyberpunk metres of push radius per block of a wind burst's Minecraft radius (a wind charge's 1.2 pushes within 2.4 m: more than a hand's reach). */
	static final double KNOCK_RADIUS_PER_POWER = 2.0;
	/** The demolitions perk: Steve's explosions are this much bigger. */
	static final float DEMOLITIONS = 1.5F;
	/** Explosions and wind bursts reach hosts whose Steve is within this many blocks. */
	static final double REACH = 128.0;
	/** Creepers a player lit with flint and steel or a fire charge, and who (server thread only; they are gone after their blast). */
	private static final Map<Entity, Player> litBy = new WeakHashMap<>();

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
			return Wire.knock(x, y, z, Math.min(KNOCK_RADIUS_PER_POWER * power, Tethers.MAX_KNOCK_RADIUS), Tethers.knockStrength(power));
		}

		return Wire.explosion(x, y, z, Math.min(power, MAX_POWER), fire);
	}

	/**
	 * An explosion's radius after the demolitions perk (Steve's own blast, with the perk: half as big again). A wind burst is no blast,
	 * it only pushes (and its radius is what the host hears): the perk leaves it alone.
	 */
	static float radius(final Level.ExplosionInteraction interaction, final float r, final boolean steves, final boolean demolitions) {
		return isBlast(interaction) && steves && demolitions ? r * DEMOLITIONS : r;
	}

	/**
	 * Server thread, from ServerLevel.explode before it runs: the radius it runs with. Steve's explosions are the ones he caused (his
	 * TNT, a fireball he sent back, anything whose damage names him) and creepers he lit.
	 */
	public static float radius(final Level.ExplosionInteraction interaction, final @Nullable Entity source, final @Nullable DamageSource damageSource, final float r) {
		boolean steves = cause(source, damageSource) instanceof ServerPlayer;
		return radius(interaction, r, steves, Bridge.perkRank.applyAsInt("demolitions") > 0);
	}

	/** Server thread: the player behind an explosion (his TNT, a fireball he sent back, a creeper he lit), else whatever caused it. */
	public static @Nullable Entity cause(final @Nullable Entity source, final @Nullable DamageSource damageSource) {
		Player lit = source == null ? null : litBy.get(source);
		return lit != null ? lit : damageSource != null ? damageSource.getEntity() : Explosion.getIndirectSourceEntity(source);
	}

	/** Server thread: a player lit this creeper. */
	public static void litBy(final Entity creeper, final Player player) {
		litBy.put(creeper, player);
	}

	/** A host whose Steve is this far away (dx, dy, dz) hears of an explosion. */
	static boolean reaches(final double dx, final double dy, final double dz) {
		return dx * dx + dy * dy + dz * dz <= REACH * REACH;
	}

	/** Server thread, from ServerLevel.explode: cause is the player (or other entity) behind it, null when none. */
	public static void report(final ServerLevel level, final Level.ExplosionInteraction interaction, final double x, final double y, final double z,
		final double power, final boolean fire, final @Nullable Entity cause) {
		String message = message(interaction, x, y, z, power, fire);
		if (message == null || level != level.getServer().overworld()) {
			return;
		}

		boolean blocked = false;
		for (HostSession s : HostSessions.active()) {
			ServerPlayer steve = s.player();
			if (steve.level() != level || !reaches(steve.getX() - x, steve.getY() - y, steve.getZ() - z)) {
				continue;
			}

			if (WorldModule.allows(s.uuid(), cause, WorldModule.EXPLOSIONS)) {
				s.send(message);
			} else {
				blocked = true;
			}
		}

		if (blocked) {
			WorldModule.blocked(cause);
		}
	}

	/** Server thread, from a firework rocket that bursts with at least one star (a plain flight rocket does nothing); owner: who shot it. */
	public static void reportFirework(final ServerLevel level, final double x, final double y, final double z, final @Nullable Entity owner) {
		report(level, Level.ExplosionInteraction.NONE, x, y, z, FIREWORK_POWER, false, owner);
	}
}
