package dev.cyberblock.cyber;

import com.google.gson.JsonObject;
import dev.cyberblock.Bridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;
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

	/** Server thread: XP orbs where the proxy stands (at the player when it is gone), and cold blood. */
	public static void onKilled(final ServerPlayer player, final Killed killed) {
		Entity proxy = Bridge.proxyById.apply(killed.target());
		Vec3 at = proxy != null && !proxy.isRemoved() && proxy.level() == player.level() ? proxy.position() : player.position();
		ExperienceOrb.award(player.level(), at, xp(killed.hostile()));
		PerkEffects.coldBlood(player);
	}
}
