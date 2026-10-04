package dev.cyberblock.mode.match;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.serialization.Codec;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.ServerConfig;
import dev.cyberblock.skin.CyberSkins;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * The PvP toggle (megaplan 8-M1, section 1.13's per-player {@code pvp} attachment): off for everyone by default
 * ({@code pvpDefault} in the server config) until a match turns it on for its players, or the player turns it on with the HUD
 * button, the Cyberware screen button or {@code /cyberblock pvp on}. A hit between two players applies only when both are on
 * ({@link PvpRules}); the attacker sees why not on the action bar. After the last PvP hit the toggle stays on 15 s; an "off" asked
 * meanwhile is refused with the seconds left, and a match that ends meanwhile turns it off when the 15 s are over. A hit on a linked
 * player is an ordinary hit on Steve, so it lands on V through Steve's hearts (the one health rule). Players with PvP on wear a red
 * name tag ({@link NameTags}). Server thread.
 *
 * <p>Log lines: {@code pvp: <name> on|off (<why>)}, {@code pvp: <attacker> hit <victim> (both on; <victim>'s hearts|V through
 * Steve's hearts)} (once per pair per 10 s), {@code pvp: the pvp game rule is off in this world: nobody can hurt another player
 * whatever the toggles say}.
 */
public final class Pvp {
	/** The player's toggle; saved with the world and kept through death. Absent: the server's pvpDefault. */
	public static final AttachmentType<Boolean> PVP = AttachmentRegistry.<Boolean>builder()
		.persistent(Codec.BOOL)
		.copyOnDeath()
		.buildAndRegister(Identifier.fromNamespaceAndPath(Cyberblock.ID, "pvp"));
	private static final long LOG_PAIR_MS = 10_000L;
	private static final long REFUSAL_NOTE_MS = 1_000L;
	private static final Map<UUID, Long> lastHit = new HashMap<>();
	private static final Map<UUID, Long> lastRefusalNote = new HashMap<>();
	private static final Map<String, Long> lastPairLog = new HashMap<>();
	/** Players whose toggle goes off once their 15 s lock is over (a match ended or they left it during a fight). */
	private static final Set<UUID> offWhenUnlocked = new HashSet<>();

	private Pvp() {
	}

	static void init() {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(Pvp::allowDamage);
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(command()));
		ServerLifecycleEvents.SERVER_STARTED.register(Pvp::started);
		ServerTickEvents.END_SERVER_TICK.register(Pvp::tick);
	}

	/** Server stop, after Matches ended every match: toggles that waited for their lock go off now (they are saved with the world). */
	static void stopping(final MinecraftServer server) {
		for (UUID uuid : offWhenUnlocked) {
			ServerPlayer p = server.getPlayerList().getPlayer(uuid);
			if (p != null) {
				p.setAttached(PVP, false);
			}
		}

		lastHit.clear();
		lastRefusalNote.clear();
		lastPairLog.clear();
		offWhenUnlocked.clear();
	}

	/** After Matches took the player out of its match: a toggle that waited for its lock goes off now (the lock ends with the session). */
	static void disconnected(final ServerPlayer player) {
		UUID uuid = player.getUUID();
		lastRefusalNote.remove(uuid);
		if (offWhenUnlocked.remove(uuid)) {
			player.setAttached(PVP, false);
			Cyberblock.LOG.info("pvp: {} off (match over, left the server)", player.getPlainTextName());
		}
	}

	/** The player's PvP toggle. */
	public static boolean on(final ServerPlayer player) {
		return player.getAttachedOrElse(PVP, ServerConfig.get().pvpDefault());
	}

	/** Milliseconds the toggle must still stay on (the 15 s after the last PvP hit). */
	public static long lockLeft(final ServerPlayer player) {
		return PvpRules.lockLeft(System.currentTimeMillis(), lastHit.getOrDefault(player.getUUID(), PvpRules.NEVER));
	}

	/** The player asked to turn PvP on or off (button or command); false when the 15 s lock refused "off". */
	public static boolean request(final ServerPlayer player, final boolean wanted) {
		boolean was = on(player);
		boolean now = PvpRules.next(was, wanted, System.currentTimeMillis(), lastHit.getOrDefault(player.getUUID(), PvpRules.NEVER));
		if (!wanted && now) {
			int s = PvpRules.seconds(lockLeft(player));
			player.sendSystemMessage(Component.translatableWithFallback("cyberblock.pvp.locked", "You were in a fight: PvP can go off in %s s", s), true);
			return false;
		}

		offWhenUnlocked.remove(player.getUUID());
		set(player, now, "asked");
		player.sendSystemMessage(now ? Component.translatableWithFallback("cyberblock.pvp.now_on", "PvP on: players who have it on too can hurt you")
			: Component.translatableWithFallback("cyberblock.pvp.now_off", "PvP off: no player can hurt you"), true);
		return true;
	}

	/** A match turns the toggle on for its player (no lock applies to turning on). */
	static void matchOn(final ServerPlayer player) {
		offWhenUnlocked.remove(player.getUUID());
		set(player, true, "match");
	}

	/**
	 * A match is over for the player: its toggle goes back to {@code before}. "Off" waits for the 15 s lock (a fight in the last
	 * seconds keeps both players able to finish it).
	 */
	static void matchOver(final ServerPlayer player, final boolean before) {
		if (before || !on(player)) {
			return;
		}

		if (lockLeft(player) > 0L) {
			offWhenUnlocked.add(player.getUUID());
		} else {
			set(player, false, "match over");
		}
	}

	private static void set(final ServerPlayer player, final boolean on, final String why) {
		if (on == on(player) && player.hasAttached(PVP)) {
			return;
		}

		player.setAttached(PVP, on);
		Cyberblock.LOG.info("pvp: {} {} ({})", player.getPlainTextName(), on ? "on" : "off", why);
		HostSessions.rosterChanged();
		Matches.changed(player);
	}

	private static void tick(final MinecraftServer server) {
		if (offWhenUnlocked.isEmpty() || server.getTickCount() % 10 != 0) {
			return;
		}

		for (UUID uuid : Set.copyOf(offWhenUnlocked)) {
			ServerPlayer p = server.getPlayerList().getPlayer(uuid);
			if (p == null) {
				offWhenUnlocked.remove(uuid);
			} else if (lockLeft(p) == 0L) {
				offWhenUnlocked.remove(uuid);
				set(p, false, "match over, lock done");
			}
		}
	}

	private static void started(final MinecraftServer server) {
		if (!server.overworld().getGameRules().get(GameRules.PVP)) {
			Cyberblock.LOG.warn("pvp: the pvp game rule is off in this world: nobody can hurt another player whatever the toggles say");
		}
	}

	/** Fabric ALLOW_DAMAGE: false cancels the hit. Only player-on-player hits (also arrows, tridents, TNT a player lit) are judged. */
	private static boolean allowDamage(final LivingEntity entity, final DamageSource source, final float amount) {
		if (!(entity instanceof ServerPlayer victim) || !(source.getEntity() instanceof ServerPlayer attacker) || attacker == victim) {
			return true;
		}

		Match m = Matches.of(attacker.getUUID());
		boolean sameTeam = m != null && m == Matches.of(victim.getUUID()) && m.teammates(attacker.getUUID(), victim.getUUID());
		PvpRules.Refusal refusal = PvpRules.refusal(on(attacker), on(victim), sameTeam, m != null && m.mode.friendlyFire());
		long now = System.currentTimeMillis();
		if (refusal != null) {
			if (now - lastRefusalNote.getOrDefault(attacker.getUUID(), 0L) >= REFUSAL_NOTE_MS) {
				lastRefusalNote.put(attacker.getUUID(), now);
				attacker.sendSystemMessage(Component.translatableWithFallback(refusal.key, refusal.fallback), true);
			}

			return false;
		}

		lastHit.put(attacker.getUUID(), now);
		lastHit.put(victim.getUUID(), now);
		String pair = attacker.getUUID() + ">" + victim.getUUID();
		if (now - lastPairLog.getOrDefault(pair, 0L) >= LOG_PAIR_MS) {
			lastPairLog.put(pair, now);
			Cyberblock.LOG.info("pvp: {} hit {} (both on; {})", attacker.getPlainTextName(), victim.getPlainTextName(),
				CyberSkins.linked(victim) ? "V through Steve's hearts" : victim.getPlainTextName() + "'s hearts");
		}

		return true;
	}

	/** /cyberblock pvp (shows it), /cyberblock pvp on|off (any player, for itself). */
	private static LiteralArgumentBuilder<CommandSourceStack> command() {
		return Commands.literal("cyberblock").then(Commands.literal("pvp")
			.executes(c -> show(c.getSource()))
			.then(Commands.literal("on").executes(c -> request(c.getSource().getPlayerOrException(), true) ? 1 : 0))
			.then(Commands.literal("off").executes(c -> request(c.getSource().getPlayerOrException(), false) ? 1 : 0)));
	}

	private static int show(final CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.pvp.players_only", "Only a player has a PvP toggle"));
			return 0;
		}

		long left = lockLeft(player);
		// system messages: the world's send_command_feedback is off (CyberblockClient), which would hide sendSuccess
		Component text;
		if (!on(player)) {
			text = Component.translatableWithFallback("cyberblock.pvp.status_off", "PvP is off. Turn it on with /cyberblock pvp on");
		} else if (left > 0L) {
			text = Component.translatableWithFallback("cyberblock.pvp.status_locked", "PvP is on and stays on %s s more after your last fight",
				PvpRules.seconds(left));
		} else {
			text = Component.translatableWithFallback("cyberblock.pvp.status_on", "PvP is on. Turn it off with /cyberblock pvp off");
		}

		source.sendSystemMessage(text);
		return 1;
	}
}
