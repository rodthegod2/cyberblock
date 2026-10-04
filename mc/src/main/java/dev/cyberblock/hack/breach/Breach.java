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
 * <li>A right-click on a Net Port block (Phase 4's terminal): {@link #openOnDevice} with the device's owner (quest-locked ports
 * refuse). Uploaded daemons go to the owner as one {@code daemon} DOWN; a failure sends {@code ["alarm"]} and adds
 * {@link BreachResults#FAILURE_HEAT} through {@link #heat} (Phase 8's meter; until it is installed the alarm daemon carries the host's
 * wanted +1, see the start-up line). When the owner's guest toggles ({@code hacks}, or the device type's bit) refuse the guest, the breach
 * still plays out in Minecraft and nothing is sent (megaplan 1.6). An abort is not a free re-roll (protocol.md "Phase 6"): closing it
 * before the first pick keeps that puzzle, its timer running, for that player and device, and the next right-click reopens it; its timeout
 * is a failure. The rate limits of {@link BreachLimits} apply: 10 s for the same player and device after a failure, and an owner gets at
 * most one alarm a minute. A Net Port with no device behind it (a creative or test block) opens a practice breach with no effect.</li>
 * <li>6R's Breach Port: {@link #openOnDevice} with the linked device.</li>
 * <li>Phase 8's player hacks: {@link #open} with {@link Spec#shortBreach} (4x4, 5 s from the opening) or the defender's
 * {@link #firewallRank}, and its own outcome handler; {@link #close} ends it early.</li>
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
	/** Device breaches closed before their first pick: kept, timer running, until reopened or timed out. */
	private static final Map<BreachLimits.Hold, Session> kept = new HashMap<>();
	private static final BreachLimits limits = new BreachLimits();
	private static final Map<DeviceRegistry.Key, Answer> answers = new HashMap<>();
	private static int nextId = 1;
	private static volatile @Nullable MinecraftServer server;

	/**
	 * A breach in play: its puzzle id, the player, the game, where it must stay near, who hears the outcome, and for a device breach the
	 * player and device it is kept under when closed before a pick (null: a practice or Phase 8 breach, cancelled instead).
	 */
	private record Session(int id, String dev, UUID player, BreachGame game, @Nullable Vec3 anchor, Level level, Consumer<BreachResults.Outcome> onEnd,
		BreachLimits.@Nullable Hold hold) {
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
		kept.clear();
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
	 * Opens a breach for the player: a new seeded puzzle for the spec, its timer running from now, the screen on the player's client,
	 * and {@code onEnd} with the outcome once it ends (cancelled ones too). False (nothing opened) while the player is in another breach
	 * or is locked by the join password (megaplan 1.6: no interaction before /login, whoever the caller is).
	 *
	 * @param anchor where the player must stay within {@link #REACH} (null: anywhere)
	 */
	public static boolean open(final ServerPlayer player, final Spec spec, final @Nullable Vec3 anchor, final Consumer<BreachResults.Outcome> onEnd) {
		return open(player, spec, anchor, null, onEnd);
	}

	/** {@link #open}; with a hold, the breach this player kept on this device (closed before a pick) reopens instead of a new one. */
	private static boolean open(final ServerPlayer player, final Spec spec, final @Nullable Vec3 anchor, final BreachLimits.@Nullable Hold hold,
		final Consumer<BreachResults.Outcome> onEnd) {
		if (JoinLock.locked(player)) {
			Cyberblock.LOG.info("breach: {} refused (locked by the join password)", player.getPlainTextName());
			return false;
		}

		if (busy(player)) {
			tell(player, Component.translatableWithFallback("cyberblock.breach.busy", "You are already in a breach"));
			return false;
		}

		Session old = hold == null ? null : kept.remove(hold);
		if (old != null) {
			// a new screen id: the client never reopens a puzzle id it closed
			Session s = new Session(nextId++, old.dev(), old.player(), old.game(), anchor, player.level(), old.onEnd(), hold);
			sessions.put(player.getUUID(), s);
			show(player, s);
			Cyberblock.LOG.info("breach: {} reopened the kept {}x{} on {} ({} s left, the same puzzle: an abort is not a re-roll)", player.getPlainTextName(),
				old.game().puzzle().size(), old.game().puzzle().size(), old.dev(), (old.game().leftMs(now()) + 999) / 1000);
			return true;
		}

		long seed = ThreadLocalRandom.current().nextLong();
		BreachPuzzle.Tier tier = spec.shortBreach() ? BreachContent.SHORT : BreachContent.tierFor(spec.security(), spec.firewall());
		BreachPuzzle puzzle = BreachPuzzle.generate(seed, tier, BreachContent.daemonsFor(spec.kind(), spec.security()));
		Session s = new Session(nextId++, spec.dev(), player.getUUID(), new BreachGame(puzzle, now()), anchor, player.level(), onEnd, hold);
		sessions.put(player.getUUID(), s);
		show(player, s);
		Cyberblock.LOG.info("breach: {} opened {}x{} on {} {} (security {}, firewall {}{}, buffer {}, {} s from now, daemons {}, seed {})",
			player.getPlainTextName(), puzzle.size(), puzzle.size(), spec.kind(), spec.dev(), spec.security(), spec.firewall(),
			spec.shortBreach() ? ", short" : "", puzzle.buffer(), puzzle.seconds(), puzzle.daemons().stream().map(BreachPuzzle.Daemon::id).toList(), seed);
		return true;
	}

	/**
	 * A breach on a host's device (a Net Port right-click, 6R's Breach Port): checks the device, the session and the cooldown, then
	 * reopens the puzzle this player kept on this device or opens a new one at the device's security; at the end the owner gets the
	 * {@code daemon} DOWN (uploaded daemons, or the alarm) unless the owner's guest toggles refuse this player. False when refused (the
	 * player was told why).
	 */
	public static boolean openOnDevice(final ServerPlayer player, final BlockPos pos, final UUID owner, final Device device) {
		BreachLimits.Hold hold = new BreachLimits.Hold(player.getUUID(), new DeviceRegistry.Key(owner, device.id()));
		HostSession session = HostSessions.of(owner);
		long cooldown = limits.cooldown(hold, ticks());
		if (device.questLocked()) {
			tell(player, Component.translatableWithFallback("cyberblock.device.quest_locked", "Night City is holding this device for a quest"));
		} else if (session == null || session.state() == HostSession.State.NONE) {
			tell(player, Component.translatableWithFallback("cyberblock.device.no_link", "Night City is not connected: devices cannot be used"));
		} else if (cooldown > 0 && !kept.containsKey(hold)) {
			tell(player, Component.translatableWithFallback("cyberblock.breach.cooldown", "Wait %s s before breaching this device again", seconds(cooldown)));
			Cyberblock.LOG.info("breach: {} on {} {} -> refused (cooldown: {} s more after a failed breach)", player.getPlainTextName(), device.type().id(),
				device.idText(), seconds(cooldown));
		} else {
			if (!allowed(owner, player.getUUID(), device)) {
				Cyberblock.LOG.info("breach: {} on {} {} of {}: the host's guest toggle refuses it, so it plays out in Minecraft only (nothing will be sent)",
					player.getPlainTextName(), device.type().id(), device.idText(), session.name());
			}

			Spec spec = new Spec(device.type().id(), BreachContent.security(device), 0, false, device.idText());
			return open(player, spec, Vec3.atCenterOf(pos), hold, o -> deviceOutcome(hold, device, o));
		}

		return false;
	}

	/** Does the owner let this player's breach reach their game: the {@code hacks} toggle and the device type's bit (the host always)? */
	private static boolean allowed(final UUID owner, final UUID player, final Device device) {
		return Permissions.allowsDevice(owner, player, device.type()) && Permissions.allows(owner, player, Permissions.Toggle.HACKS);
	}

	/**
	 * A device breach ended: the cooldown after a failure ({@link BreachLimits}), then, when the owner's guest toggles allow it, the
	 * daemon DOWN to the owner (a failure within the owner's alarm window is merged into the alarm already raised), Heat with the alarm,
	 * the player told.
	 */
	private static void deviceOutcome(final BreachLimits.Hold hold, final Device device, final BreachResults.Outcome o) {
		UUID playerId = hold.player();
		DeviceRegistry.Key key = hold.device();
		ServerPlayer player = player(playerId);
		HostSession session = HostSessions.of(key.owner());
		String who = player == null ? playerId.toString() : player.getPlainTextName();
		if (o.cancelled()) {
			// not reached: a device breach closed before its first pick is kept, not cancelled
			Cyberblock.LOG.info("breach: {} aborted on {} {} before a pick: nothing sent", who, device.type().id(), device.idText());
			return;
		}

		if (o.failed()) {
			limits.failed(hold, ticks());
		}

		if (!allowed(key.owner(), playerId, device)) {
			tell(player, Component.translatableWithFallback("cyberblock.guest.blocked", "That host does not let guests do this to their Night City"));
			Cyberblock.LOG.info("breach: {} {} on {} {} -> blocked (the host's guest toggle): played out in Minecraft only, nothing sent", who, o.state(),
				device.type().id(), device.idText());
			return;
		}

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
	 * The devtools bypass of the test command ({@code /cyberblock test breach <x> <y> <z> solve|fail}): the player's cooldown on the Net
	 * Port's device and a puzzle kept there are dropped, so a scenario may breach the same device again at once with a fresh puzzle. The
	 * owner's alarm window stays.
	 */
	static void clearLockoutsForTest(final ServerPlayer player, final BlockPos pos) {
		DeviceRegistry.Tracked t = DeviceModule.at(pos);
		if (t != null) {
			BreachLimits.Hold hold = new BreachLimits.Hold(player.getUUID(), t.key());
			limits.clear(hold);
			kept.remove(hold);
			Cyberblock.LOG.info("breach: test bypass: the cooldown and kept puzzle of {} on {} {} cleared", player.getPlainTextName(), t.device().type().id(),
				t.device().idText());
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
			stop(s);
			return;
		}

		if (!s.game().pick(r.cell(), now())) {
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

	/**
	 * Ends the player's breach now, as an abort (Phase 8: the intrusion it defends against landed or was blocked): the screen shows it
	 * ended and {@code onEnd} hears the outcome (cancelled before a pick, a failure after one). Nothing when the player is in no breach.
	 */
	public static void close(final ServerPlayer player) {
		abort(player.getUUID());
	}

	static void abort(final UUID player) {
		Session s = sessions.get(player);
		if (s != null) {
			stop(s);
		}
	}

	/**
	 * The player stops the breach (an abort, the screen closed, walking away, leaving, {@link #close}). A device breach before its first
	 * pick is kept for that player and device, its timer running (protocol.md: an abort is not a free re-roll); anything else quits.
	 */
	private static void stop(final Session s) {
		BreachGame g = s.game();
		BreachLimits.Hold hold = s.hold();
		if (g.expire(now()) || hold == null || g.started()) {
			g.quit();
			update(s);
			return;
		}

		sessions.remove(s.player(), s);
		kept.put(hold, s);
		ServerPlayer p = player(s.player());
		if (p != null) {
			ServerNet.ui(p, BreachWire.T, BreachWire.screen(s.id(), s.dev(), g, now(), true));
		}

		Cyberblock.LOG.info("breach: {} closed the breach of {} before a pick: the puzzle is kept with its timer ({} s left) for the next right-click",
			p == null ? s.player().toString() : p.getPlainTextName(), s.dev(), (g.leftMs(now()) + 999) / 1000);
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

	/** Each tick: timers end breaches, kept ones too (a failure); a player who walked away or changed level stops. */
	private static void tick(final MinecraftServer s) {
		long now = now();
		for (Session session : List.copyOf(sessions.values())) {
			ServerPlayer p = s.getPlayerList().getPlayer(session.player());
			if (p == null || p.level() != session.level()
				|| session.anchor() != null && p.getEyePosition().distanceToSqr(session.anchor()) > REACH * REACH) {
				stop(session);
			} else if (session.game().expire(now) || session.game().ended()) {
				update(session);
			}
		}

		for (Map.Entry<BreachLimits.Hold, Session> e : List.copyOf(kept.entrySet())) {
			Session session = e.getValue();
			if (session.game().expire(now) && kept.remove(e.getKey(), session)) {
				Cyberblock.LOG.info("breach: the kept breach of {} by {} ran out of time", session.dev(), session.player());
				session.onEnd().accept(BreachResults.Outcome.of(session.game()));
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
