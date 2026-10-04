package dev.cyberblock.meetup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.JoinLock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Megaplan 3-E2, Meet Up: {@code /cyberblock meetup <player>} asks a friend; the friend accepts within 60 s with the clickable chat
 * line or {@code /cyberblock meetup accept}. A requester whose Cyberpunk is active moves V: the server sends
 * {@code vteleport {id, p, yaw, why:"meetup"}} DOWN to its session (the DLL and scripts teleport V with the Phase 0 (g) settle probe
 * and ground snap) and on {@code vteleported ok} Steve follows V to the snapped spot; a failure shows its reason ({@code ""},
 * {@code no ground}, {@code timeout}, {@code blocked (<why>)}). Any other requester (a guest) is teleported next to the friend's
 * Steve. One request per 30 s per requester; refused while the requester's V is held by the game or in a vehicle; Phase 8 adds the
 * match-zone rule in {@link #matchZoneRefusal}. Server thread only.
 *
 * <p>Log lines: {@code meetup: <a> asked <b>}, {@code meetup: <a> -> <b>: vteleport <id> sent}, {@code meetup: <a> -> <b> ok
 * (<x> <y> <z>)}, {@code meetup: <a> -> <b> failed (<reason>)}, {@code meetup: <a> -> <b> (guest teleported)}.
 */
public final class MeetUp {
	static final long ACCEPT_MS = 60_000L;
	static final long COOLDOWN_MS = 30_000L;
	/** The DLL gives up after 15 s of settling; past this a vteleport without an answer is forgotten. */
	static final long ANSWER_MS = 30_000L;
	/** vteleported.p further than this (horizontally) from the requested spot is not believed: Steve goes to the requested spot. */
	static final double LANDING_SLACK = 32.0;
	static final int REASON_MAX = 128;
	private static final double MAX_COORD = 3.0E7;

	/** A request waiting for the target's accept. */
	private record Request(UUID from, String fromName, long at) {
	}

	/** A vteleport waiting for its vteleported, keyed by the requester (one at a time). */
	private record Teleport(int id, UUID target, String targetName, double[] p, long at) {
	}

	/** vteleported {id, ok, reason, p}; p is null when absent (a failure needs none). */
	record Answer(int id, boolean ok, String reason, double @Nullable [] p) {
	}

	/** By target. */
	private static final Map<UUID, Request> requests = new HashMap<>();
	/** By requester: when it last asked. */
	private static final Map<UUID, Long> asked = new HashMap<>();
	private static final Map<UUID, Teleport> teleports = new HashMap<>();

	private MeetUp() {
	}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> register(dispatcher));
		HostSessions.listen("vteleported", MeetUp::onAnswer);
		HostSessions.onEnd(s -> teleports.remove(s.uuid()));
		ServerTickEvents.END_SERVER_TICK.register(MeetUp::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			requests.clear();
			asked.clear();
			teleports.clear();
		});
	}

	private static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("meetup")
			.then(Commands.literal("accept").executes(c -> accept(c.getSource())))
			.then(Commands.argument("player", EntityArgument.player()).executes(c -> ask(c.getSource(), EntityArgument.getPlayer(c, "player"))))));
	}

	/** The lang key why a request is refused, or null when it may go. */
	static @Nullable String refusal(final boolean self, final long cooldownLeft, final boolean held, final boolean vehicle, final @Nullable String zone) {
		if (self) {
			return "cyberblock.meetup.self";
		}

		if (cooldownLeft > 0) {
			return "cyberblock.meetup.cooldown";
		}

		if (held) {
			return "cyberblock.meetup.held";
		}

		return vehicle ? "cyberblock.meetup.vehicle" : zone;
	}

	/** Phase 8 (match zones) fills this: a lang key when the requester may not meet the target there; no zones before Phase 8. */
	static @Nullable String matchZoneRefusal(final @Nullable ServerPlayer requester, final @Nullable ServerPlayer target) {
		return null;
	}

	/** Whole seconds until this requester may ask again (0 = now). */
	static long cooldownLeft(final @Nullable Long lastAsked, final long now) {
		return lastAsked == null ? 0 : Math.max(0, (lastAsked + COOLDOWN_MS - now + 999) / 1000);
	}

	static boolean expired(final long at, final long now, final long window) {
		return now - at >= window;
	}

	/** {"t":"vteleport","id":..,"p":[x,y,z],"yaw":..,"why":"meetup"}: Minecraft position and yaw, like {@code teleport}. */
	static String vteleport(final int id, final double x, final double y, final double z, final float yaw) {
		return String.format(Locale.ROOT, "{\"t\":\"vteleport\",\"id\":%d,\"p\":[%.3f,%.3f,%.3f],\"yaw\":%.2f,\"why\":\"meetup\"}", id, x, y, z, yaw);
	}

	/** A valid vteleported, or null: id whole, ok boolean, reason a string of at most 128 bytes (absent = ""), p three finite numbers when ok. */
	static @Nullable Answer answer(final JsonObject m) {
		if (!(m.get("id") instanceof JsonPrimitive id) || !id.isNumber() || id.getAsDouble() != Math.rint(id.getAsDouble())
			|| Math.abs(id.getAsDouble()) > Integer.MAX_VALUE || !(m.get("ok") instanceof JsonPrimitive ok) || !ok.isBoolean()) {
			return null;
		}

		String reason = "";
		JsonElement r = m.get("reason");
		if (r != null && !r.isJsonNull()) {
			if (!(r instanceof JsonPrimitive rp) || !rp.isString() || rp.getAsString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > REASON_MAX) {
				return null;
			}

			reason = rp.getAsString();
		}

		double[] p = position(m.get("p"));
		if (ok.getAsBoolean() && p == null) {
			return null;
		}

		return new Answer(id.getAsInt(), ok.getAsBoolean(), reason, p);
	}

	private static double @Nullable [] position(final @Nullable JsonElement e) {
		if (!(e instanceof JsonArray a) || a.size() != 3) {
			return null;
		}

		double[] p = new double[3];
		for (int i = 0; i < 3; i++) {
			if (!(a.get(i) instanceof JsonPrimitive n) || !n.isNumber() || !Double.isFinite(n.getAsDouble()) || Math.abs(n.getAsDouble()) >= MAX_COORD) {
				return null;
			}

			p[i] = n.getAsDouble();
		}

		return p;
	}

	/** Where Steve goes: V's snapped spot, unless it is more than 32 blocks (horizontally) from the requested one. */
	static double[] landing(final double @Nullable [] answered, final double[] asked) {
		if (answered == null || Math.hypot(answered[0] - asked[0], answered[2] - asked[2]) > LANDING_SLACK) {
			return asked;
		}

		return answered;
	}

	/** vteleported.reason as text for the players. */
	static Component reason(final String reason) {
		if (reason.isEmpty()) {
			return Component.translatableWithFallback("cyberblock.meetup.reason.unknown", "Cyberpunk gave no reason");
		}

		if (reason.equals("no ground")) {
			return Component.translatableWithFallback("cyberblock.meetup.reason.no_ground", "no ground under that spot in Night City");
		}

		if (reason.equals("timeout")) {
			return Component.translatableWithFallback("cyberblock.meetup.reason.timeout", "Night City did not load there in time");
		}

		if (reason.startsWith("blocked (") && reason.endsWith(")")) {
			return Component.translatableWithFallback("cyberblock.meetup.reason.blocked", "blocked (%s)", reason.substring(9, reason.length() - 1));
		}

		return Component.literal(reason);
	}

	/** /cyberblock meetup <player>. */
	private static int ask(final CommandSourceStack source, final ServerPlayer target) {
		ServerPlayer from = source.getPlayer();
		if (from == null) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.meetup.player_only", "Only a player can meet up"));
			return 0;
		}

		long now = System.currentTimeMillis();
		long wait = cooldownLeft(asked.get(from.getUUID()), now);
		String why = JoinLock.locked(target) ? "cyberblock.meetup.locked" : moveRefusal(from, target, from == target, wait);
		if (why != null) {
			source.sendFailure(refusalText(why, target, wait));
			return 0;
		}

		requests.put(target.getUUID(), new Request(from.getUUID(), from.getPlainTextName(), now));
		asked.put(from.getUUID(), now);
		Component click = Component.translatableWithFallback("cyberblock.meetup.accept", "[Accept]").withStyle(style -> style
			.withColor(ChatFormatting.GREEN).withUnderlined(true)
			.withClickEvent(new ClickEvent.RunCommand("/cyberblock meetup accept"))
			.withHoverEvent(new HoverEvent.ShowText(Component.translatableWithFallback("cyberblock.meetup.accept.hover",
				"Click within 60 s, or type /cyberblock meetup accept"))));
		// the client mod shows this line as a toast too (MeetUpPrompt matches the key): keep the key and the first argument
		target.sendSystemMessage(Component.translatableWithFallback("cyberblock.meetup.request", "%s wants to meet up. %s", from.getPlainTextName(), click));
		say(from, Component.translatableWithFallback("cyberblock.meetup.sent", "Meet Up request sent to %s (60 s to accept)", target.getPlainTextName()));
		Cyberblock.LOG.info("meetup: {} asked {}", from.getPlainTextName(), target.getPlainTextName());
		return 1;
	}

	/** Why the requester cannot be moved to the target now (lang key), or null. */
	private static @Nullable String moveRefusal(final ServerPlayer from, final ServerPlayer target, final boolean self, final long wait) {
		HostSession s = HostSessions.of(from);
		boolean drives = s != null && s.active();
		return refusal(self, wait, drives && s.held(), drives && s.vehicle(), matchZoneRefusal(from, target));
	}

	private static Component refusalText(final String key, final ServerPlayer target, final long wait) {
		return switch (key) {
			case "cyberblock.meetup.self" -> Component.translatableWithFallback(key, "You cannot meet up with yourself");
			case "cyberblock.meetup.cooldown" -> Component.translatableWithFallback(key, "Wait %s s before the next Meet Up", wait);
			case "cyberblock.meetup.held" -> Component.translatableWithFallback(key, "Meet Up refused: the game holds V right now (a scene or a ride)");
			case "cyberblock.meetup.vehicle" -> Component.translatableWithFallback(key, "Meet Up refused: V is in a vehicle");
			case "cyberblock.meetup.locked" -> Component.translatableWithFallback(key, "%s has not logged in yet", target.getPlainTextName());
			default -> Component.translatableWithFallback(key, "Meet Up is not allowed there");
		};
	}

	/** /cyberblock meetup accept: the newest request to this player, if it is less than 60 s old. */
	private static int accept(final CommandSourceStack source) {
		ServerPlayer target = source.getPlayer();
		Request r = target == null ? null : requests.remove(target.getUUID());
		long now = System.currentTimeMillis();
		if (r == null || expired(r.at(), now, ACCEPT_MS)) {
			source.sendFailure(r == null
				? Component.translatableWithFallback("cyberblock.meetup.none", "No Meet Up request to accept")
				: Component.translatableWithFallback("cyberblock.meetup.expired", "The Meet Up request from %s has expired", r.fromName()));
			return 0;
		}

		MinecraftServer server = target.level().getServer();
		ServerPlayer from = server.getPlayerList().getPlayer(r.from());
		if (from == null) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.meetup.gone", "%s is not online any more", r.fromName()));
			return 0;
		}

		String why = moveRefusal(from, target, false, 0);
		if (why != null) {
			Component text = refusalText(why, target, 0);
			say(from, text);
			source.sendFailure(text);
			return 0;
		}

		HostSession s = HostSessions.of(from);
		if (s != null && s.active()) {
			return moveV(s, from, target, server, now);
		}

		ServerLevel level = target.level();
		from.teleportTo(level, target.getX(), target.getY(), target.getZ(), Set.of(), target.getYRot(), target.getXRot(), true);
		met(from, target);
		Cyberblock.LOG.info("meetup: {} -> {} (guest teleported)", from.getPlainTextName(), target.getPlainTextName());
		return 1;
	}

	/** The requester's Cyberpunk moves V to the target (Night City is the overworld); Steve follows on the answer. */
	private static int moveV(final HostSession s, final ServerPlayer from, final ServerPlayer target, final MinecraftServer server, final long now) {
		if (target.level() != server.overworld()) {
			Component text = Component.translatableWithFallback("cyberblock.meetup.world", "Meet Up: %s is not in Night City's world", target.getPlainTextName());
			say(from, text);
			say(target, text);
			return 0;
		}

		double[] p = {target.getX(), target.getY(), target.getZ()};
		int id = s.newRequest(target.getUUID());
		teleports.put(from.getUUID(), new Teleport(id, target.getUUID(), target.getPlainTextName(), p, now));
		s.send(vteleport(id, p[0], p[1], p[2], from.getYRot()));
		say(from, Component.translatableWithFallback("cyberblock.meetup.moving", "Moving V to %s...", target.getPlainTextName()));
		say(target, Component.translatableWithFallback("cyberblock.meetup.coming", "%s is on the way", from.getPlainTextName()));
		Cyberblock.LOG.info("meetup: {} -> {}: vteleport {} sent", from.getPlainTextName(), target.getPlainTextName(), id);
		return 1;
	}

	/** vteleported from a host's DLL: Steve follows V, or the reason shows. */
	private static void onAnswer(final HostSession s, final JsonObject m) {
		Answer a = answer(m);
		Teleport t = teleports.get(s.uuid());
		if (a == null || t == null || t.id() != a.id()) {
			Cyberblock.LOG.debug("meetup: {} answer {} ignored", s.name(), a == null ? "invalid" : "unasked");
			return;
		}

		teleports.remove(s.uuid());
		ServerPlayer from = s.player();
		if (from == null) {
			return;
		}

		MinecraftServer server = from.level().getServer();
		ServerPlayer target = server.getPlayerList().getPlayer(t.target());
		if (!a.ok()) {
			Component why = reason(a.reason());
			say(from, Component.translatableWithFallback("cyberblock.meetup.failed", "Meet Up failed: %s", why));
			if (target != null) {
				say(target, Component.translatableWithFallback("cyberblock.meetup.failed.target", "%s could not reach you: %s", from.getPlainTextName(), why));
			}

			Cyberblock.LOG.info("meetup: {} -> {} failed ({})", s.name(), t.targetName(), a.reason());
			return;
		}

		double[] p = landing(a.p(), t.p());
		from.teleportTo(server.overworld(), p[0], p[1], p[2], Set.of(), from.getYRot(), from.getXRot(), true);
		if (target != null) {
			met(from, target);
		}

		Cyberblock.LOG.info(String.format(Locale.ROOT, "meetup: %s -> %s ok (%.1f %.1f %.1f)", s.name(), t.targetName(), p[0], p[1], p[2]));
	}

	private static void met(final ServerPlayer from, final ServerPlayer target) {
		say(from, Component.translatableWithFallback("cyberblock.meetup.met", "Met up with %s", target.getPlainTextName()));
		say(target, Component.translatableWithFallback("cyberblock.meetup.arrived", "%s met up with you", from.getPlainTextName()));
	}

	/** Once a second: a vteleport its DLL never answered is forgotten, and the requester told. */
	private static void tick(final MinecraftServer server) {
		if (server.getTickCount() % 20 != 0 || teleports.isEmpty()) {
			return;
		}

		long now = System.currentTimeMillis();
		for (UUID host : List.copyOf(teleports.keySet())) {
			Teleport t = teleports.get(host);
			if (expired(t.at(), now, ANSWER_MS)) {
				teleports.remove(host);
				ServerPlayer from = server.getPlayerList().getPlayer(host);
				if (from != null) {
					say(from, Component.translatableWithFallback("cyberblock.meetup.no_answer", "Meet Up: no answer from your Cyberpunk game"));
				}

				Cyberblock.LOG.info("meetup: {} -> {} failed (no answer)", from == null ? host : from.getPlainTextName(), t.targetName());
			}
		}
	}

	/** A system message: send_command_feedback may be off (the singleplayer setup turns it off). */
	private static void say(final ServerPlayer player, final Component text) {
		player.sendSystemMessage(text);
	}
}
