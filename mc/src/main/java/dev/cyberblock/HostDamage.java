package dev.cyberblock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Steve's health is the only health: while the host drives V (who cannot die), every damage event on V arrives as
 * {"t":"dmg","frac":0..4,"kind":"...","from":[x,y,z]|null} and hurts Steve by frac of his maximum health, through vanilla's
 * damage rules (armour, totems, creative and spectator immunity, death and respawn).
 */
public final class HostDamage {
	/** The host clamps to 0..4 (a hit can be several times V's health); anything else is garbage. */
	static final double MAX_FRAC = 4.0;
	/** Positions beyond these are garbage, not a place in the world. */
	private static final double XZ_LIMIT = 2.9E7;
	private static final double Y_LIMIT = 20000.0;
	private static int bad;
	/**
	 * Until when (System.nanoTime) the host's "fall" damage is ignored: Steve's own glide set it (layer 4: while gliding and 2 s
	 * after touchdown, Minecraft's elytra rules hurt him, not V's landing). Long.MIN_VALUE: never.
	 */
	public static volatile long fallFreeUntil = Long.MIN_VALUE;

	/** @param from where the damage came from (Minecraft coordinates), null when unknown */
	record Hit(float frac, String kind, @Nullable Vec3 from) {
	}

	private HostDamage() {
	}

	/** The vanilla damage type for a host kind. An unknown kind is "other": generic damage. */
	static ResourceKey<DamageType> type(final String kind) {
		return switch (kind) {
			case "bullet" -> DamageTypes.MOB_PROJECTILE;
			case "explosion" -> DamageTypes.EXPLOSION;
			case "melee" -> DamageTypes.MOB_ATTACK;
			case "fall" -> DamageTypes.FALL;
			case "fire" -> DamageTypes.ON_FIRE;
			default -> DamageTypes.GENERIC; // vehicle, other
		};
	}

	/**
	 * The hit in a message, or null when frac is missing, not a number, negative or above 4 (then nothing happens). A bad
	 * "from" only loses the knockback direction: the damage still lands.
	 */
	static Hit parse(final JsonObject m) {
		double frac = m.get("frac") instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : Double.NaN;
		if (!(frac >= 0.0 && frac <= MAX_FRAC)) {
			return null;
		}

		String kind = m.get("kind") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : "other";
		return new Hit((float) frac, kind, position(m.get("from")));
	}

	private static @Nullable Vec3 position(final JsonElement e) {
		if (!(e instanceof JsonArray a) || a.size() != 3) {
			return null;
		}

		double x = number(a.get(0)), y = number(a.get(1)), z = number(a.get(2));
		return Math.abs(x) <= XZ_LIMIT && Math.abs(y) <= Y_LIMIT && Math.abs(z) <= XZ_LIMIT ? new Vec3(x, y, z) : null;
	}

	private static double number(final JsonElement e) {
		return e instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : Double.NaN;
	}

	/** Link thread: the host's dmg message. */
	public static void update(final JsonObject message) {
		Hit hit = parse(message);
		if (hit == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("ignoring a dmg message with a bad frac (seen {} times)", bad);
			}

			return;
		}

		MinecraftServer s = WorldBridge.server();
		if (s != null && hit.frac() > 0.0F) {
			s.execute(() -> hurt(s, hit));
		}
	}

	private static void hurt(final MinecraftServer s, final Hit hit) {
		List<ServerPlayer> players = s.getPlayerList().getPlayers();
		if (!Cyberblock.active || players.isEmpty()) {
			return;
		}

		ServerPlayer steve = players.get(0);
		if (ignoredFall(hit.kind(), System.nanoTime(), fallFreeUntil, steve.hasEffect(MobEffects.SLOW_FALLING))) {
			return;
		}

		// every bullet counts: vanilla's damage cooldown would swallow all but the strongest hit in half a second
		steve.damageCooldownTime = 0;
		steve.hurtServer(steve.level(), source(steve.level(), hit), hit.frac() * steve.getMaxHealth());
	}

	/** V's fall damage does not reach Steve with Slow Falling, nor around his own glide (until fallFreeUntil). */
	static boolean ignoredFall(final String kind, final long now, final long fallFreeUntil, final boolean slowFalling) {
		return kind.equals("fall") && (slowFalling || fallFreeUntil != Long.MIN_VALUE && now - fallFreeUntil < 0L);
	}

	private static DamageSource source(final ServerLevel level, final Hit hit) {
		Holder<DamageType> type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type(hit.kind()));
		String verb = switch (hit.kind()) {
			case "bullet" -> " was shot";
			case "melee" -> " was slain";
			default -> null;
		};
		if (verb == null) {
			return new DamageSource(type, hit.from());
		}

		// both vanilla "mob" messages name the attacker, and there is no entity to name: say it without one
		return new DamageSource(type, hit.from()) {
			@Override
			public Component getLocalizedDeathMessage(final LivingEntity victim) {
				return Component.empty().append(victim.getDisplayName()).append(verb + " in Night City");
			}
		};
	}
}
