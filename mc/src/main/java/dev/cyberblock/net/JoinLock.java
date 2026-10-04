package dev.cyberblock.net;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.cyberblock.Cyberblock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The optional join password (server.json {@code joinPassword}): it lets a server run without a whitelist. A player who joins and
 * has not passed it on this world is locked: held where they joined, no block, item, entity or container use, no pickup or drop
 * (mixins), no damage, not a mob target, no chat and no command but /login, and no Cyberpunk host session (the handshake answers
 * guest with {@link Handshake.HostRefusal#LOCKED}; the right password re-runs it). {@code /login <password>} (also
 * {@code /cyberblock login}) unlocks: one try per second, five wrong ones kick, and a locked player is kicked after 120 s.
 * Verified players are remembered by UUID in LoginsData until the password changes. The password is never logged, sent to a
 * client or shown; vanilla 26.3 logs a player's command text only when the command throws (Commands.performCommand, javap), so
 * the login handler never throws. Server thread unless a method says otherwise.
 */
public final class JoinLock {
	static final String PROMPT = "This server needs a password: type /login <password>";
	static final String KICK_WRONG = "Cyberblock: wrong join password " + JoinGate.MAX_ATTEMPTS + " times. Ask the server owner for the password.";
	static final String KICK_TIMEOUT = "Cyberblock: no join password within " + JoinGate.TIMEOUT_MS / 1000
		+ " s. Type /login <password> right after you join.";
	static final String KICK_WAIT = "Cyberblock: too many wrong join passwords. Try again in %d min, or ask the server owner for the password.";
	/** How far a locked player may drift from where they joined before being put back (squared, blocks). */
	private static final double DRIFT_SQ = 0.25;
	private static final JoinGate gate = new JoinGate();
	/** Where each locked player is held. */
	private static final Map<UUID, Vec3> holds = new ConcurrentHashMap<>();
	private static volatile LoginsData data = new LoginsData();

	private JoinLock() {
	}

	/** Common init (ServerNet.init): what a locked player may not do, through Fabric API's events. */
	static void init() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> deny(player));
		UseItemCallback.EVENT.register((player, level, hand) -> deny(player));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> deny(player));
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> deny(player));
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> deny(player));
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !locked(player));
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !(entity instanceof Player p && locked(p)));
		// Fabric's check runs at the head of PlayerList.broadcastChatMessage, before vanilla logs the message: a password typed
		// without the slash is neither shown nor logged
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			if (!locked(sender)) {
				return true;
			}

			remind(sender);
			return false;
		});
	}

	private static InteractionResult deny(final Player player) {
		return locked(player) ? InteractionResult.FAIL : InteractionResult.PASS;
	}

	/** Is this player locked by the join password? False on the client side and for everyone while no password is set. Any thread. */
	public static boolean locked(final Player player) {
		return player instanceof ServerPlayer && gate.locked(player.getUUID());
	}

	/** The only commands a locked player may run: {@code login ...} and {@code cyberblock login ...} (CommandsMixin). */
	public static boolean isLoginCommand(final String command) {
		return JoinGate.isLoginCommand(command);
	}

	static boolean locked(final UUID player) {
		return gate.locked(player);
	}

	/** A join password is set and this player passed it on this world (it then counts like the whitelist for hosting). */
	static boolean verified(final UUID player) {
		return ServerConfig.get().joinPasswordOn() && data.verified(player);
	}

	/** {@code /login}, {@code /cyberblock login} (everyone) and {@code /cyberblock password [reset]} (operators). */
	static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("login").executes(c -> usage(c.getSource())).then(password()));
		dispatcher.register(Commands.literal("cyberblock")
			.then(Commands.literal("login").executes(c -> usage(c.getSource())).then(password()))
			.then(Commands.literal("password").requires(NetCommands::opOrOwner).executes(c -> show(c.getSource()))
				.then(Commands.literal("reset").requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_ADMINS)).executes(c -> reset(c.getSource())))));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> password() {
		return Commands.argument("password", StringArgumentType.greedyString())
			.executes(c -> login(c.getSource(), StringArgumentType.getString(c, "password")));
	}

	/** Server start, after the config is loaded: the world's records, dropped when the password changed. */
	static void started(final MinecraftServer server) {
		data = server.overworld().getDataStorage().computeIfAbsent(LoginsData.TYPE);
		sync();
	}

	static void stopping() {
		gate.clear();
		holds.clear();
		data = new LoginsData();
	}

	private static void sync() {
		ServerConfig config = ServerConfig.get();
		if (data.sync(config.joinPassword())) {
			Cyberblock.LOG.info("login: the join password changed: every player must /login again");
		}

		if (config.joinPasswordOn()) {
			Cyberblock.LOG.info("login: join password on ({} players verified on this world)", data.count());
		}
	}

	/** server.json was read again (/cyberblock hosts add|remove): lock or unlock the players online to match. */
	static void reconfigured(final MinecraftServer server) {
		sync();
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			boolean need = required(server, p);
			if (need && !gate.locked(p.getUUID())) {
				lock(p);
				ServerNet.rehandshake(p);
			} else if (!need && gate.locked(p.getUUID())) {
				release(p);
				ServerNet.rehandshake(p);
			}
		}
	}

	private static boolean required(final MinecraftServer server, final ServerPlayer player) {
		return ServerConfig.get().joinPasswordOn() && !ServerNet.isOwner(server, player) && !data.verified(player.getUUID());
	}

	/**
	 * A player joined (before its client's hs is handled): locked when the join password applies to it. Wrong attempts survive a
	 * reconnect (JoinGate.STRIKE_MEMORY_MS), so a player who used all five is refused at once until they are forgotten.
	 */
	static void onJoin(final ServerPlayer player, final MinecraftServer server) {
		if (!required(server, player)) {
			return;
		}

		long now = System.currentTimeMillis();
		if (gate.exhausted(player.getUUID(), now)) {
			long minutes = gate.minutesUntilRetry(player.getUUID(), now);
			Cyberblock.LOG.info("login: {} refused ({} wrong passwords in the last {} min)", player.getPlainTextName(), JoinGate.MAX_ATTEMPTS,
				JoinGate.STRIKE_MEMORY_MS / 60_000);
			player.connection.disconnect(Component.literal(KICK_WAIT.formatted(minutes)));
			return;
		}

		lock(player);
	}

	static void onLeave(final UUID player) {
		gate.unlock(player);
		holds.remove(player);
	}

	private static void lock(final ServerPlayer p) {
		gate.lock(p.getUUID(), System.currentTimeMillis());
		p.stopRiding();
		holds.put(p.getUUID(), p.position());
		Cyberblock.LOG.info("login: {} is locked until /login (join password)", p.getPlainTextName());
		p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 100, 20));
		p.connection.send(new ClientboundSetTitleTextPacket(Component.translatableWithFallback("cyberblock.login.title", "Password needed")));
		p.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatableWithFallback("cyberblock.login.subtitle", "Type /login <password>")));
		p.sendSystemMessage(Component.translatableWithFallback("cyberblock.login.prompt", PROMPT));
		// a Cyberblock client opens its password box (LoginScreen); a client without the mod ignores it
		ServerNet.ui(p, "login", "{}");
	}

	private static void release(final ServerPlayer p) {
		gate.unlock(p.getUUID());
		holds.remove(p.getUUID());
		p.connection.send(new ClientboundClearTitlesPacket(true));
	}

	private static void remind(final ServerPlayer p) {
		p.sendSystemMessage(Component.translatableWithFallback("cyberblock.login.reminder", "Log in first: type /login <password>"));
	}

	/** Each server tick: hold the locked players in place, show the time left and kick those out of time. */
	static void tick(final MinecraftServer server) {
		if (holds.isEmpty()) {
			return;
		}

		boolean second = server.getTickCount() % 20 == 0;
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, Vec3> e : holds.entrySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null) {
				continue;
			}

			hold(p, e.getValue());
			if (second) {
				p.sendOverlayMessage(Component.translatableWithFallback("cyberblock.login.countdown", "Type /login <password> (%s s left)",
					gate.secondsLeft(p.getUUID(), now)));
			}
		}

		if (second) {
			for (UUID u : gate.timedOut(now)) {
				ServerPlayer p = server.getPlayerList().getPlayer(u);
				onLeave(u);
				if (p != null) {
					Cyberblock.LOG.info("login: {} kicked (no /login in {} s)", p.getPlainTextName(), JoinGate.TIMEOUT_MS / 1000);
					p.connection.disconnect(Component.literal(KICK_TIMEOUT));
				}
			}
		}
	}

	private static void hold(final ServerPlayer p, final Vec3 at) {
		if (p.isPassenger()) {
			p.stopRiding();
		}

		p.setDeltaMovement(Vec3.ZERO);
		p.resetFallDistance(); // no fall damage stored up for the moment the lock ends
		if (p.position().distanceToSqr(at) > DRIFT_SQ) {
			p.connection.teleport(at.x, at.y, at.z, p.getYRot(), p.getXRot());
		}
	}

	private static int usage(final CommandSourceStack source) {
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.login.usage", "Type /login <password>"));
		return 0;
	}

	/** Never throws: vanilla would log the command, password included ("'/{}' threw an exception"). Never echoes the password. */
	private static int login(final CommandSourceStack source, final String typed) {
		ServerPlayer p = source.getPlayer();
		try {
			if (p == null) {
				source.sendFailure(Component.translatableWithFallback("cyberblock.login.player_only", "Only a player can log in"));
				return 0;
			}

			ServerConfig config = ServerConfig.get();
			if (!config.joinPasswordOn()) {
				p.sendSystemMessage(Component.translatableWithFallback("cyberblock.login.off", "This server has no join password"));
				return 0;
			}

			JoinGate.Result r = gate.attempt(p.getUUID(), typed, config.joinPassword(), System.currentTimeMillis());
			switch (r.outcome()) {
				case NOT_LOCKED -> p.sendSystemMessage(Component.translatableWithFallback("cyberblock.login.already", "You are already logged in"));
				case TOO_FAST -> p.sendSystemMessage(Component.translatableWithFallback("cyberblock.login.too_fast", "Wait a second between /login attempts"));
				case WRONG -> {
					Cyberblock.LOG.info("login: {} typed a wrong password (attempts left: {})", p.getPlainTextName(), r.attemptsLeft());
					p.sendSystemMessage(Component.translatableWithFallback("cyberblock.login.wrong", "Wrong password (attempts left: %s)", r.attemptsLeft()));
					ServerNet.ui(p, "login", "{\"left\":" + r.attemptsLeft() + "}");
				}
				case KICK -> {
					Cyberblock.LOG.info("login: {} kicked ({} wrong passwords)", p.getPlainTextName(), JoinGate.MAX_ATTEMPTS);
					onLeave(p.getUUID());
					p.connection.disconnect(Component.literal(KICK_WRONG));
				}
				case OK -> {
					data.add(p.getUUID());
					release(p);
					Cyberblock.LOG.info("login: {} verified", p.getPlainTextName());
					p.sendSystemMessage(Component.translatableWithFallback("cyberblock.login.welcome", "Welcome to Cyberblock, %s! You will not need the password again on this world.",
						p.getPlainTextName()));
					// a Cyberpunk host's link waited for this: the handshake is decided again now (hs_ack host, then its hello)
					ServerNet.rehandshake(p);
					return 1;
				}
			}

			return 0;
		} catch (RuntimeException e) {
			Cyberblock.LOG.warn("login: failed for {} ({})", p == null ? "?" : p.getPlainTextName(), e.getClass().getSimpleName());
			return 0;
		}
	}

	private static int show(final CommandSourceStack source) {
		boolean on = ServerConfig.get().joinPasswordOn();
		source.sendSystemMessage(on
			? Component.translatableWithFallback("cyberblock.login.status.on", "Join password: on; %s players verified on this world, %s locked now",
				data.count(), gate.lockedCount())
			: Component.translatableWithFallback("cyberblock.login.status.off",
				"Join password: off (set joinPassword in config/cyberblock/server.json, then restart the server)"));
		return on ? 1 : 0;
	}

	private static int reset(final CommandSourceStack source) {
		int n = data.reset();
		Cyberblock.LOG.info("login: {} cleared the verified list ({} players)", source.getTextName(), n);
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.login.reset",
			"Cleared %s verified players: everyone must /login at their next join (players online now stay logged in)", n));
		return n;
	}
}
