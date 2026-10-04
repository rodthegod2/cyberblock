package dev.cyberblock;

import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Layer 5 potions: a host's Steve's status effects for that host ({"t":"effect"}), which gives V the matching Cyberpunk buffs. Every
 * change while its session is active (ServerPlayerMixin), and the whole set again after each host hello, once the session is active
 * (the host drops effects while it is not), in the same tick as the block resync. Server thread only.
 */
public final class PlayerEffects {
	/** The protocol's "infinite". */
	static final double INFINITE_SECONDS = 86400.0;
	private static final Pattern NAME = Pattern.compile("[a-z_]{1,32}");
	/** Sessions that said hello and have not had the whole set since. */
	private static final Set<UUID> resendDue = new HashSet<>();
	/** Sessions that were active on the last tick. */
	private static final Set<UUID> wasActive = new HashSet<>();

	private PlayerEffects() {
	}

	/**
	 * The message for an effect (a vanilla effect path such as "speed"), or null for one the protocol cannot name (another namespace,
	 * other characters). Removed: 0 seconds; infinite: 86400.
	 */
	public static String message(final Identifier id, final boolean removed, final int durationTicks, final boolean infinite, final int amplifier) {
		if (!id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) || !NAME.matcher(id.getPath()).matches()) {
			return null;
		}

		double seconds = removed ? 0.0 : infinite ? INFINITE_SECONDS : durationTicks / 20.0;
		return Wire.effect(id.getPath(), seconds, amplifier);
	}

	/** From ServerPlayerMixin: an effect on this player was added or changed (removed: true when it went). */
	public static void changed(final ServerPlayer player, final MobEffectInstance effect, final boolean removed) {
		HostSession s = HostSessions.of(player);
		if (s != null && s.active()) {
			send(s, effect, removed);
		}
	}

	private static void send(final HostSession s, final MobEffectInstance effect, final boolean removed) {
		Holder<MobEffect> holder = effect.getEffect();
		holder.unwrapKey().ifPresent(key -> {
			String m = message(key.identifier(), removed, effect.getDuration(), effect.isInfiniteDuration(), effect.getAmplifier());
			if (m != null) {
				s.send(m);
			}
		});
	}

	/** A host hello: it gets every effect again once it is active. */
	static void hello(final HostSession s) {
		resendDue.add(s.uuid());
	}

	/** The session ended. */
	static void end(final UUID owner) {
		resendDue.remove(owner);
		wasActive.remove(owner);
	}

	/** Every server tick: the whole set once a session is active after a hello, and whenever it becomes active again. */
	static void tick(final MinecraftServer server) {
		for (HostSession s : HostSessions.all()) {
			UUID owner = s.uuid();
			boolean active = s.active();
			boolean resend = active && (resendDue.contains(owner) || !wasActive.contains(owner));
			if (active) {
				wasActive.add(owner);
			} else {
				wasActive.remove(owner);
			}

			if (resend) {
				resendDue.remove(owner);
				for (MobEffectInstance effect : s.player().getActiveEffects()) {
					send(s, effect, false);
				}
			}
		}
	}
}
