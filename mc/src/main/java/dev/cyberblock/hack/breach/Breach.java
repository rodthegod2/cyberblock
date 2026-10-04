package dev.cyberblock.hack.breach;

import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Cyberware;
import dev.cyberblock.cyber.HackResult;
import dev.cyberblock.device.Device;
import dev.cyberblock.device.DeviceModule;
import dev.cyberblock.device.DeviceRegistry;
import dev.cyberblock.device.block.NetPortBlock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.JoinLock;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.net.ServerNet;
import dev.cyberblock.npc.Gigs;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Breach Protocol on the server (megaplan 6-B1): opens a breach for a player, validates every pick ({@link BreachGame}), ends it
 * (all uploaded, buffer full, timer, abort, walking away, leaving) and hands the {@link BreachResults.Outcome} to whoever opened it.
 * One breach per player at a time. Server thread only.
 *
 * <p>The ways in:
 * <ul>
 * <li>A right-click on a Net Port block (Phase 4's terminal): {@link #openOnDevice} with the device's owner (the guest toggles
 * {@code devices}, its type bit and {@code hacks}; quest-locked ports refuse). Uploaded daemons go to the owner as one {@code daemon}
 * DOWN; a failure sends {@code ["alarm"]} and adds {@link BreachResults#FAILURE_HEAT} through {@link #heat} (Phase 8's meter; until it is
 * installed the alarm daemon carries the host's wanted +1, see the start-up line). A Net Port with no device behind it (a creative or
 * test block) opens a practice breach with no effect. The rate limits of {@link BreachLimits} apply: a breached device waits 30 s, a
 * player whose breach failed waits 30 s for any device, and an owner gets at most one alarm a minute.</li>
 * <li>6R's Breach Port: {@link #openOnDevice} with the linked device.</li>
 * <li>Phase 8's player hacks: {@link #open} with {@link Spec#shortBreach} (4x4, 5 s) or the defender's {@link #firewallRank}, and its
 * own outcome handler.</li>
 * </ul>
 */
public final class Breach {
	/**
	 * What a breach is: the device kind and security (1-3) its tier and daemons come from, the defender's Firewall rank (0-3), or the
	 * short breach; {@code dev} is the device's decimal id for the screen ("0": no device).
	 */
	public record Spec(String kind, int security, int firewall, boolean shortBreach, String dev) {
	}

	/** Phase 8's Server Heat: a failed breach adds to the meter (megaplan 8-T1). */
	@FunctionalInterface
	public interface Heat {
		void add(UUID host, ServerPlayer cause, int amount);
	}

	/** Phase 8 installs its Heat meter here; null until then (the alarm daemon carries the wanted +1 instead). */
	public static volatile @Nullable Heat heat;
	/** Blocks between the player's eyes and the breached device before the breach ends (a screen's reach plus a margin). */
	static final double REACH = 8.0;
	/** A daemon DOWN with no hackresult after this long is forgotten (server ticks: 60 s). */
	static final long ANSWER_TICKS = 1200;
	private static final Identifier EARLY = Identifier.fromNamespaceAndPath(Cyberblock.ID, "breach");
	private static final Map<UUID, Session> sessions = new HashMap<>();
	private static final BreachLimits limits = new BreachLimits();
	private static final Map<DeviceRegistry.Key, Answer> answers = new HashMap<>();
	private static int nextId = 1;
	private static volatile @Nullable MinecraftServer server;

	/** A breach in play: its puzzle id, the player, the game, where it must stay near, and who hears the outcome. */
	private record Session(int id, String dev, UUID player, BreachGame game, @Nullable Vec3 anchor, Level level, Consumer<BreachResults.Outcome> onEnd) {
	}

	/** A daemon DOWN waiting for the owner's hackresult (keyed by owner and request id): the player to tell. */
	private record Answer(UUID player, long tick) {
	}

	private Breach() {
	}

	/** Common init, once (Cyberblock.onInitialize, after ServerNet and the device layer). */
	public static void init() {
		BreachContent.register();
		ServerNet.onAct(BreachWire.T, Breach::onAct);
		// before the device layer's own use handler, which answers a terminal with "later update"
		UseBlockCallback.EVENT.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
		UseBlockCallback.EVENT.register(EARLY, Breach::onUse);
		HostSessions.listen("hackresult", Breach::onHackResult);
		ServerTickEvents.END_SERVER_TICK.register(Breach::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> abort(handler.getPlayer().getUUID()));
		ServerLifecycleEvents.SERVER_STARTED.register(Breach::started);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> stopping());
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> BreachCommands.register(dispatcher));
	}

	/** The one line saying which paths are active: content or built-ins, the Firewall implant, the Heat meter. */
	private static void started(final MinecraftServer s) {
		server = s;
		Cyberblock.LOG.info("breach: ready ({}); firewall implant {}; a failure sends the alarm daemon (cameras alert; the scripts add wanted +1 "
				+ "where Phase 0 (e) allows), Heat meter {}", BreachContent.source(),
			Cyberware.byId("firewall") != null ? "present: a defender's Firewall raises the tier" : "not in this build: no tier raise",
			heat != null ? "installed: +" + BreachResults.FAILURE_HEAT : "not in this build (Phase 8)");
	}

	private static void stopping() {
		sessions.clear();
		limits.clear();
		answers.clear();
		server = null;
	}

	/** The defender's Firewall rank (megaplan section 1.17): 1 with the {@code firewall} implant installed, else 0. */
	public static int firewallRank(final ServerPlayer defender) {
		return CyberData.installedIds(defender).contains("firewall") ? 1 : 0;
	}

	/** Is this player in a breach now? */
	public static boolean busy(final ServerPlayer player) {
		return sessions.containsKey(player.getUUID());
	}

	/**
	 * Opens a breach for the player: a new seeded puzzle for the spec, the screen on the player's client, and {@code onEnd} with the
	 * outcome once it ends (aborted ones too). False (nothing opened) while the player is in another breach or is locked by the
	 * join password (megaplan 1.6: no interaction before /login, whoever the caller is).
	 *
	 * @param anchor where the player must stay within {@link #REACH} (null: anywhere)
	 */
	public static boolean open(final ServerPlayer player, final Spec spec, final @Nullable Vec3 anchor, final Consumer<BreachResults.Outcome> onEnd) {
		if (JoinLock.locked(player)) {
			Cyberblock.LOG.info("breach: {} refused (locked by the join password)", player.getPlainTextName());
			return false;
		}

		if (busy(player)) {
			tell(player, Component.translatableWithFallback("cyberblock.breach.busy", "You are already in a breach"));
			return false;
		}

		long seed = ThreadLocalRandom.current().nextLong();
		BreachPuzzle.Tier tier = spec.shortBreach() ? BreachContent.SHORT : BreachContent.tierFor(spec.security(), spec.firewall());
		BreachPuzzle puzzle = BreachPuzzle.generate(seed, tier, BreachContent.daemonsFor(spec.kind(), spec.security()));
		Session s = new Session(nextId++, spec.dev(), player.getUUID(), new BreachGame(puzzle, now()), anchor, player.level(), onEnd);
		sessions.put(player.getUUID(), s);
		show(player, s);
		Cyberblock.LOG.info("breach: {} opened {}x{} on {} {} (security {}, firewall {}{}, buffer {}, {} s, daemons {}, seed {})", player.getPlainTextName(),
			puzzle.size(), puzzle.size(), spec.kind(), spec.dev(), spec.security(), spec.firewall(), spec.shortBreach() ? ", short" : "", puzzle.buffer(),
			puzzle.seconds(), puzzle.daemons().stream().map(BreachPuzzle.Daemon::id).toList(), seed);
		return true;
	}

	/**
	 * A breach on a host's device (a Net Port right-click, 6R's Breach Port): checks the device and the owner's guest toggles, then
	 * opens the breach at the device's security; at the end the owner gets the {@code daemon} DOWN (uploaded daemons, or the alarm).
	 * False when refused (the player was told why).
	 */
	public static boolean openOnDevice(final ServerPlayer player, final BlockPos pos, final UUID owner, final Device device) {
		DeviceRegistry.Key key = new DeviceRegistry.Key(owner, device.id());
		HostSession session = HostSessions.of(owner);
		if (device.questLocked()) {
			tell(player, Component.translatableWithFallback("cyberblock.device.quest_locked", "Night City is holding this device for a quest"));
		} else if (!Permissions.allowsDevice(owner, player.getUUID(), device.type()) || !Permissions.allows(owner, player.getUUID(), Permissions.Toggle.HACKS)) {
			tell(player, Component.translatableWithFallback("cyberblock.guest.blocked", "That host does not let guests do this to their Night City"));
			Cyberblock.LOG.info("breach: {} on {} {} -> blocked (the host's guest toggle)", player.getPlainTextName(), device.type().id(), device.idText());
		} else if (session == null || session.state() == HostSession.State.NONE) {
			tell(player, Component.translatableWithFallback("cyberblock.device.no_link", "Night City is not connected: devices cannot be used"));
		} else if (limits.playerWait(player.getUUID(), ticks()) > 0) {
			long seconds = seconds(limits.playerWait(player.getUUID(), ticks()));
			tell(player, Component.translatableWithFallback("cyberblock.breach.player_locked_out", "Your last breach failed: no breaches for %s s", seconds));
			Cyberblock.LOG.info("breach: {} on {} {} -> refused (locked out for {} s more after a failed breach)", player.getPlainTextName(),
				device.type().id(), device.idText(), seconds);
		} else if (limits.deviceWait(key, ticks()) > 0) {
			tell(player, Component.translatableWithFallback("cyberblock.breach.locked_out", "This device is locked out for %s s",
				seconds(limits.deviceWait(key, ticks()))));
		} else {
			Spec spec = new Spec(device.type().id(), BreachContent.security(device), 0, false, device.idText());
			return open(player, spec, Vec3.atCenterOf(pos), o -> deviceOutcome(player.getUUID(), key, device, o));
		}

		return false;
	}

	/**
	 * A device breach ended: the limits ({@link BreachLimits}), then the daemon DOWN to the owner (nothing when aborted; a failure
	 * within the owner's alarm window is merged into the alarm already raised), Heat with the alarm, the player told.
	 */
	private static void deviceOutcome(final UUID playerId, final DeviceRegistry.Key key, final Device device, final BreachResults.Outcome o) {
		ServerPlayer player = player(playerId);
		HostSession session = HostSessions.of(key.owner());
		String who = player == null ? playerId.toString() : player.getPlainTextName();
		if (o.cancelled()) {
			Cyberblock.LOG.info("breach: {} aborted on {} {} before a pick: nothing sent", who, device.type().id(), device.idText());
			return;
		}

		limits.ended(key, playerId, o.failed(), ticks());
		if (session == null || session.state() == HostSession.State.NONE) {
			tell(player, Component.translatableWithFallback("cyberblock.device.no_link", "Night City is not connected: devices cannot be used"));
			Cyberblock.LOG.info("breach: {} ended ({}) but the owner is gone: nothing sent", who, o.state());
			return;
		}

		if (o.failed() && !limits.alarm(key.owner(), ticks())) {
			tell(player, Component.translatableWithFallback("cyberblock.breach.alarm", "Alarm raised: the cameras around it are alerted"));
			Cyberblock.LOG.info("breach: {} {} on {} {} of {} -> alarm merged (one alarm per owner every {} s): nothing sent", who, o.state(),
				device.type().id(), device.idText(), session.name(), BreachLimits.ALARM_TICKS / 20);
			return;
		}

		int request = session.newRequest(playerId);
		session.send(BreachResults.daemonDown(request, device, o));
		answers.put(new DeviceRegistry.Key(key.owner(), request), new Answer(playerId, ticks()));
		if (o.failed()) {
			Heat h = heat;
			if (h != null && player != null) {
				h.add(key.owner(), player, BreachResults.FAILURE_HEAT);
			}

			tell(player, Component.translatableWithFallback("cyberblock.breach.alarm", "Alarm raised: the cameras around it are alerted"));
			Cyberblock.LOG.info("breach: {} {} on {} {} of {} -> daemon {} [alarm] ({})", who, o.state(), device.type().id(), device.idText(), session.name(),
				request, h != null ? "Heat +" + BreachResults.FAILURE_HEAT : "Heat meter absent: wanted +1 by the scripts where Phase 0 (e) allows");
		} else {
			tell(player, Component.translatableWithFallback("cyberblock.breach.uploaded", "Daemon uploaded: %s",
				ComponentUtils.formatList(o.ids().stream().map(BreachWire::daemonName).toList(), Component.literal(", "))));
			Cyberblock.LOG.info("breach: {} solved {} (strength {}) on {} {} of {} -> daemon {}", who, o.ids(), o.strength(), device.type().id(),
				device.idText(), session.name(), request);
			if (player != null) {
				Gigs.breached(player, device.type().id()); // the section 1.11 kind a breach_device gig names
			}
		}
	}

	/** A hackresult for one of our daemon requests: the player hears a refusal (other ids belong to Hacking and the remote hacks). */
	private static void onHackResult(final HostSession s, final JsonObject m) {
		HackResult r = HackResult.parse(m);
		Answer a = r == null ? null : answers.remove(new DeviceRegistry.Key(s.uuid(), r.id()));
		if (a == null) {
			return;
		}

		if (!r.ok()) {
			tell(player(a.player()), Component.translatableWithFallback("cyberblock.breach.refused", "Night City refused the daemons: %s", r.reason()));
		}

		Cyberblock.LOG.info("breach: {} answered daemon {}: {}", s.name(), r.id(), r.ok() ? "ok" : "refused (" + r.reason() + ")");
	}

	// ---- the Net Port ------------------------------------------------------------------------------------------------------------

	/** A right-click on a Net Port block opens Breach (server side; the client's use goes on to the server unchanged). */
	private static InteractionResult onUse(final Player player, final Level level, final InteractionHand hand, final BlockHitResult hit) {
		// a player locked by the join password falls through to JoinLock's own handler (DEFAULT phase), which refuses the use:
		// Fabric stops at the first result that is not PASS, so returning anything else here would skip it
		if (!(level instanceof ServerLevel) || !(player instanceof ServerPlayer actor) || JoinLock.locked(actor) || hand != InteractionHand.MAIN_HAND
			|| actor.isSpectator() || !(level.getBlockState(hit.getBlockPos()).getBlock() instanceof NetPortBlock)
			|| actor.isShiftKeyDown() && !actor.getMainHandItem().isEmpty()) {
			return InteractionResult.PASS;
		}

		netPort(actor, hit.getBlockPos());
		return InteractionResult.SUCCESS;
	}

	/** The Net Port at pos: its device's breach, or a practice breach when no device stands there. */
	static void netPort(final ServerPlayer player, final BlockPos pos) {
		DeviceRegistry.Tracked t = DeviceModule.at(pos);
		if (t != null) {
			openOnDevice(player, pos, t.key().owner(), t.device());
			return;
		}

		Cyberblock.LOG.info("breach: {} on a Net Port with no Night City device at {}: practice", player.getPlainTextName(), pos.toShortString());
		practice(player, 1, false, Vec3.atCenterOf(pos));
	}

	/**
	 * The devtools bypass of the test command ({@code /cyberblock test breach <x> <y> <z> solve|fail}): the lockouts of the Net Port's
	 * device and of the player are cleared, so a scenario may breach the same device again at once. The owner's alarm window stays.
	 */
	static void clearLockoutsForTest(final ServerPlayer player, final BlockPos pos) {
		DeviceRegistry.Tracked t = DeviceModule.at(pos);
		if (t != null) {
			limits.clear(t.key(), player.getUUID());
			Cyberblock.LOG.info("breach: test bypass: lockouts of {} and {} {} cleared", player.getPlainTextName(), t.device().type().id(), t.device().idText());
		}
	}

	/** A breach with no device behind it (a lone Net Port, the test command): the result is only shown. */
	static boolean practice(final ServerPlayer player, final int security, final boolean shortBreach, final @Nullable Vec3 anchor) {
		UUID id = player.getUUID();
		return open(player, new Spec("terminal", security, 0, shortBreach, "0"), anchor, o -> {
			if (!o.cancelled()) {
				tell(player(id), o.failed() ? Component.translatableWithFallback("cyberblock.breach.practice_failed", "Practice: breach failed")
					: Component.translatableWithFallback("cyberblock.breach.practice_done", "Practice: %s daemons uploaded (strength %s)", o.uploaded().size(),
						o.strength()));
			}

			Cyberblock.LOG.info("breach: practice by {} ended ({}, {})", player.getPlainTextName(), o.state(), o.ids());
		});
	}

	// ---- play --------------------------------------------------------------------------------------------------------------------

	/** {@code cyberblock:act breach}: a pick or an abort for the player's own breach (another id is stale and dropped). */
	private static void onAct(final ServerPlayer player, final JsonObject m) {
		BreachWire.Request r = BreachWire.request(m);
		Session s = sessions.get(player.getUUID());
		if (r == null || s == null || r.id() != s.id()) {
			return;
		}

		if (r.abort()) {
			s.game().quit();
		} else if (!s.game().pick(r.cell(), now())) {
			Cyberblock.LOG.debug("breach: {} pick {} refused", player.getPlainTextName(), r.cell());
		}

		update(s);
	}

	/** The test tools' picks go the same way a client's do. */
	static boolean pick(final ServerPlayer player, final int cell) {
		Session s = sessions.get(player.getUUID());
		boolean ok = s != null && s.game().pick(cell, now());
		if (s != null) {
			update(s);
		}

		return ok;
	}

	/** The player's breach in play, for the test tools. */
	static @Nullable BreachGame game(final ServerPlayer player) {
		Session s = sessions.get(player.getUUID());
		return s == null ? null : s.game();
	}

	static void abort(final UUID player) {
		Session s = sessions.get(player);
		if (s != null) {
			s.game().quit();
			update(s);
		}
	}

	private static void show(final ServerPlayer player, final Session s) {
		ServerNet.ui(player, BreachWire.T, BreachWire.screen(s.id(), s.dev(), s.game(), now()));
	}

	/** The player sees the new state; an ended breach is closed and its outcome handed on. */
	private static void update(final Session s) {
		ServerPlayer p = player(s.player());
		if (p != null) {
			show(p, s);
		}

		if (s.game().ended() && sessions.remove(s.player(), s)) {
			s.onEnd().accept(BreachResults.Outcome.of(s.game()));
		}
	}

	/** Each tick: timers and idle breaches end; a player who walked away or changed level aborts. */
	private static void tick(final MinecraftServer s) {
		long now = now();
		for (Session session : List.copyOf(sessions.values())) {
			ServerPlayer p = s.getPlayerList().getPlayer(session.player());
			if (p == null || p.level() != session.level()
				|| session.anchor() != null && p.getEyePosition().distanceToSqr(session.anchor()) > REACH * REACH) {
				session.game().quit();
			}

			if (session.game().expire(now) || session.game().ended()) {
				update(session);
			}
		}

		if (s.getTickCount() % 20 == 0) {
			long t = s.getTickCount();
			limits.prune(t);
			answers.values().removeIf(a -> t - a.tick() >= ANSWER_TICKS);
		}
	}

	// ---- helpers -----------------------------------------------------------------------------------------------------------------

	private static long now() {
		return System.nanoTime() / 1_000_000L;
	}

	/** Whole seconds, rounded up, of a wait in ticks. */
	private static long seconds(final long ticks) {
		return (ticks + 19) / 20;
	}

	private static long ticks() {
		MinecraftServer s = server;
		return s == null ? 0L : s.getTickCount();
	}

	private static @Nullable ServerPlayer player(final UUID id) {
		MinecraftServer s = server;
		return s == null ? null : s.getPlayerList().getPlayer(id);
	}

	private static void tell(final @Nullable ServerPlayer player, final Component message) {
		if (player != null) {
			player.sendOverlayMessage(message);
		}
	}
}
