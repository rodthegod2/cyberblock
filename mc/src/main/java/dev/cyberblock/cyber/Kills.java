package dev.cyberblock.cyber;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import org.jspecify.annotations.Nullable;

/** {"t":"killed","target":"<id>","kind":"human"|"vehicle","hostile":bool}: Night City lost someone to V (task C8). */
public final class Kills {
	public static final int XP = 5;
	public static final int HOSTILE_XP = 10;

	/** A parsed `killed`. */
	public record Killed(long target, boolean vehicle, boolean hostile) {
	}

	private Kills() {
	}

	/** The message, or null when it breaks the protocol. */
	public static @Nullable Killed parse(final JsonObject m) {
		try {
			long target = ScanInfo.entityId(m.get("target"));
			String kind = m.get("kind").getAsString();
			if (target == 0L || !(kind.equals("human") || kind.equals("vehicle")) || !m.getAsJsonPrimitive("hostile").isBoolean()) {
				return null;
			}

			return new Killed(target, kind.equals("vehicle"), m.get("hostile").getAsBoolean());
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** XP for a kill: 10 for a hostile, else 5. */
	public static int xp(final boolean hostile) {
		return hostile ? HOSTILE_XP : XP;
	}

	/**
	 * Server thread: XP orbs at Steve, and cold blood. Not where the proxy stood: a kill is often tens of metres off (a car, a shot from
	 * afar), beyond the reach of Steve's pickup, and the proxy may be gone from the list by the time the message arrives.
	 */
	public static void onKilled(final ServerPlayer player, final Killed killed) {
		ExperienceOrb.award(player.level(), player.position(), xp(killed.hostile()));
		PerkEffects.coldBlood(player);
	}
}
