package dev.cyberblock;

import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Layer 5 potions: Steve's status effects for the host ({"t":"effect"}), which gives V the matching Cyberpunk buffs. Every change
 * while active (ServerPlayerMixin), and the whole set again after each host hello, once the host is active (it drops effects
 * while it is not). Server thread only.
 */
public final class PlayerEffects {
	/** The protocol's "infinite". */
	static final double INFINITE_SECONDS = 86400.0;
	private static final Pattern NAME = Pattern.compile("[a-z_]{1,32}");
	private static volatile boolean resendDue;
	private static boolean wasActive;

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

	/** Server thread, from ServerPlayerMixin: an effect on Steve was added or changed (removed: true when it went). */
	public static void changed(final MobEffectInstance effect, final boolean removed) {
		if (Cyberblock.active) {
			send(effect, removed);
		}
	}

	private static void send(final MobEffectInstance effect, final boolean removed) {
		Holder<MobEffect> holder = effect.getEffect();
		holder.unwrapKey().ifPresent(key -> {
			String m = message(key.identifier(), removed, effect.getDuration(), effect.isInfiniteDuration(), effect.getAmplifier());
			if (m != null) {
				Cyberblock.events.accept(m);
			}
		});
	}

	/** Link thread: a host hello: it gets every effect again. */
	public static void hostHello() {
		resendDue = true;
	}

	/** Every server tick: the whole set once the host is active after a hello, and whenever it becomes active again. */
	static void tick(final MinecraftServer s) {
		boolean active = Cyberblock.active;
		boolean resend = active && (resendDue || !wasActive);
		wasActive = active;
		List<ServerPlayer> players = s.getPlayerList().getPlayers();
		if (!resend || players.isEmpty()) {
			return;
		}

		resendDue = false;
		for (MobEffectInstance effect : players.get(0).getActiveEffects()) {
			send(effect, false);
		}
	}
}
