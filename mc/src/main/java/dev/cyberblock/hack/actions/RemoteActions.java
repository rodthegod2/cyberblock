package dev.cyberblock.hack.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntities;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.cyber.CyberRoute;
import dev.cyberblock.cyber.HackResult;
import dev.cyberblock.cyber.Hacking;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.net.ServerNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The interaction map on the server (megaplan task 6-I1): every action of the Vehicle Panel, the Traffic Tool, the Net Map and the
 * Comms Panel is a `cyberblock:act` request, checked here (target, owner, the owner's `hacks`/`devices` toggles, the Phase 0 (h)
 * range rule, cooldowns), sent to the owner as an `rhack` DOWN with a request id of the owner's session, and answered by the
 * owner's `hackresult` UP (routed here by Hacking.otherResults), which becomes the asker's feedback and the Minecraft-side part
 * (outlines, pings, the camera view). Server thread. Log lines: `rhack: ...`, `hack range: ...`, `feed: ...`, `comms: ...`.
 */
public final class RemoteActions {
	/** 6R's Cyberdeck Link item (right-click a car figure with it: the Vehicle Panel); matched by id, so this needs no 6R class. */
	public static final String CYBERDECK_LINK = Cyberblock.ID + ":cyberdeck_link";
	/** The Net Map's state is answered at most this often per player. */
	static final long NETMAP_MS = 250L;
	/** Comms: one message per player this often. */
	static final long COMMS_SECONDS = 2L;
	/** At most this many cameras in one Net Map answer. */
	static final int MAX_CAMS = 512;
	static final NetIndex index = new NetIndex();
	static final Cooldowns cooldowns = new Cooldowns();

	/** A sent `rhack` waiting for its `hackresult`: who asked, what, where the target was and how far from V. */
	private record Pending(UUID asker, RemoteHack hack, long sentMs, Vec3 at, double distance, String name, float yaw) {
	}

	private static final Map<CyberRoute.Key, Pending> pending = new HashMap<>();
	private static final Map<UUID, Long> lastNetMap = new HashMap<>();
	private static int bad;

	private RemoteActions() {
	}

	/** Common init (Cyberblock.onInitialize): the items, the act types, the UP listeners and the server events. */
	public static void init() {
		ActionItems.register();
		act(ActWire.T_RHACK, RemoteActions::onRhack);
		act(ActWire.T_COMMS, RemoteActions::onComms);
		act(ActWire.T_NETMAP, (player, m) -> netMap(player));
		act(ActWire.T_FEED, (player, m) -> {
			ActWire.FeedWatch watch = ActWire.parseFeedWatch(m);
			if (watch == null) {
				warn("bad feed request dropped");
			} else {
				PhotoFeed.watch(player, watch, Util.getMillis());
			}
		});
		act(ActWire.T_FEEDMAP, (player, m) -> {
			UUID host = ActWire.parseHost(m);
			if (host == null) {
				warn("bad feedmap request dropped");
			} else {
				PhotoFeed.giveMap(player, host);
			}
		});
		HostSessions.listen("devices", (session, m) -> index.devices(session.uuid(), m, Util.getMillis()));
		HostSessions.onEnd(session -> {
			index.end(session.uuid());
			pending.keySet().removeIf(k -> k.owner().equals(session.uuid()));
		});
		PhotoFeed.init();
		Hacking.otherResults(RemoteActions::onResult);
		UseEntityCallback.EVENT.register(RemoteActions::useEntity);
		ServerTickEvents.END_SERVER_TICK.register(RemoteActions::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			lastNetMap.remove(handler.getPlayer().getUUID());
			PhotoFeed.left(handler.getPlayer().getUUID());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> clear());
	}

	private static void act(final String t, final BiConsumer<ServerPlayer, JsonObject> handler) {
		ServerNet.onAct(t, handler);
	}

	// ---- rhack ---------------------------------------------------------------------------------------------------------

	/** The resolved target of a request: whose game, which Night City id (0: none), where, its name and facing. */
	record Resolved(HostSession session, long target, Vec3 at, String name, float yaw, boolean streamed, @Nullable String district) {
	}

	private static void onRhack(final ServerPlayer player, final JsonObject m) {
		ActWire.Request request = ActWire.parse(m);
		if (request == null) {
			warn("bad rhack request dropped");
			return;
		}

		RemoteHack hack = request.hack();
		long now = Util.getMillis();
		String coolKey = player.getUUID() + ":" + hack.wire();
		long wait = cooldowns.secondsLeft(coolKey, now);
		if (wait > 0) {
			refuse(player, hack, Component.translatableWithFallback("cyberblock.rhack.cooldown", "%s: ready in %s s", name(hack), wait), "cooldown");
			return;
		}

		Resolved r = resolve(player, request);
		if (r == null) {
			return;
		}

		HostSession session = r.session();
		UUID owner = session.uuid();
		boolean ownerOrHost = owner.equals(player.getUUID()) || HostSessions.isHost(player.getUUID());
		if (!hack.allowed(Permissions.of(owner), ownerOrHost)) {
			refuse(player, hack, Component.translatableWithFallback("cyberblock.guest.blocked", "This Cyberpunk host does not let guests do that"), "blocked");
			return;
		}

		ServerPlayer v = session.player();
		double distance = v == null ? 0.0 : v.position().distanceTo(r.at());
		Hacking.announceRange();
		if (!RangeRule.SERVER.allows(distance, r.streamed())) {
			refuse(player, hack, Hacking.outOfRange(), "out of range");
			return;
		}

		if (hack == RemoteHack.BLACKOUT) {
			long busy = cooldowns.secondsLeft("blackout:" + owner, now);
			if (busy > 0) {
				refuse(player, hack, Component.translatableWithFallback("cyberblock.rhack.busy", "%s: already running there (%s s left)", name(hack), busy), "busy");
				return;
			}
		}

		int id = session.newRequest(player.getUUID());
		int secs = hack == RemoteHack.BLACKOUT ? request.secs() : hack.seconds();
		session.send(ActWire.rhack(id, r.target(), hack, r.district(), hack.target() == RemoteHack.Target.POINT ? r.at() : null, secs));
		pending.put(new CyberRoute.Key(owner, id), new Pending(player.getUUID(), hack, now, r.at(), distance, r.name(), r.yaw()));
		cooldowns.start(coolKey, now, hack.cooldownSeconds());
		if (hack == RemoteHack.BLACKOUT) {
			cooldowns.start("blackout:" + owner, now, secs);
		}

		player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.rhack.sent", "%s: uploading...", name(hack)).withStyle(ChatFormatting.AQUA));
		Cyberblock.LOG.info(String.format(Locale.ROOT, "rhack: %s -> %s %s %s (%.0f m from V)", player.getPlainTextName(), session.name(), hack.wire(),
			r.target() != 0L ? Long.toUnsignedString(r.target()) : r.district() != null ? r.district() : "point", distance));
	}

	/** The request's owner and target, or null after telling the player why there is none. */
	private static @Nullable Resolved resolve(final ServerPlayer player, final ActWire.Request request) {
		RemoteHack hack = request.hack();
		switch (hack.target()) {
			case VEHICLE -> {
				Entity e = player.level().getEntity(request.eid());
				UUID owner = e == null ? null : Bridge.proxyOwnerOf.apply(e);
				long id = owner == null ? 0L : Bridge.proxyIdOf.applyAsLong(e);
				if (!(e instanceof ProxyEntity proxy) || e.getType() != ProxyEntities.VEHICLE || proxy.dead() || id == 0L) {
					refuse(player, hack, Component.translatableWithFallback("cyberblock.rhack.no_car", "That is not a car of Night City"), "no car");
					return null;
				}

				HostSession session = activeSession(player, hack, owner);
				return session == null ? null : new Resolved(session, id, e.position(), proxy.label(), e.getYRot(), true, null);
			}
			case CAMERA -> {
				HostSession session = activeSession(player, hack, request.host());
				NetIndex.Cam cam = session == null ? null : index.camera(session.uuid(), request.dev(), Util.getMillis());
				if (session != null && cam == null) {
					refuse(player, hack, Component.translatableWithFallback("cyberblock.rhack.no_camera", "That camera is gone"), "no camera");
					return null;
				}

				return session == null ? null : new Resolved(session, cam.id(), new Vec3(cam.x(), cam.y(), cam.z()), "", cam.yaw(), true, null);
			}
			case DISTRICT -> {
				HostSession session = activeSession(player, hack, request.host());
				ServerPlayer v = session == null ? null : session.player();
				if (v == null) {
					return null;
				}

				if (session.district().isEmpty()) {
					refuse(player, hack, Component.translatableWithFallback("cyberblock.rhack.no_district", "Night City has not said which district its V is in yet"),
						"no district");
					return null;
				}

				return new Resolved(session, 0L, v.position(), session.district(), 0.0F, true, session.district());
			}
			case POINT -> {
				Vec3 p = request.p();
				ServerLevel level = player.level();
				HostSession session = HostSessions.nearestActive(level, p, RangeRule.STREAMED_RANGE);
				if (session == null) {
					refuse(player, hack, Hacking.outOfRange(), "no host near");
					return null;
				}

				return new Resolved(session, 0L, p, "", 0.0F, true, null);
			}
		}

		return null;
	}

	/** The owner's session when its Cyberpunk is On, else null after telling the player. */
	private static @Nullable HostSession activeSession(final ServerPlayer player, final RemoteHack hack, final @Nullable UUID owner) {
		HostSession session = owner == null ? null : HostSessions.of(owner);
		if (session == null || !session.active() || session.player() == null) {
			refuse(player, hack, Component.translatableWithFallback("cyberblock.hack.no_link", "No Night City to reach: its host left"), "no host");
			return null;
		}

		return session;
	}

	/** Tells the asker (action bar and the open screen) that the request was not sent. */
	private static void refuse(final ServerPlayer player, final RemoteHack hack, final Component why, final String reason) {
		player.sendOverlayMessage(why.copy().withStyle(ChatFormatting.RED));
		ServerNet.ui(player, ActWire.UI_RESULT, ActWire.result(hack, false, why.getString()));
		Cyberblock.LOG.debug("rhack: {} {} refused ({})", player.getPlainTextName(), hack.wire(), reason);
	}

	static Component name(final RemoteHack hack) {
		return Component.translatableWithFallback(hack.langKey(), hack.english());
	}

	/** Hacking.otherResults: a `hackresult` for one of this module's requests. */
	private static boolean onResult(final MinecraftServer server, final UUID owner, final HackResult result) {
		Pending p = pending.remove(new CyberRoute.Key(owner, result.id()));
		if (p == null) {
			return false;
		}

		if (RangeRule.SERVER.observe(result.ok(), result.reason(), p.distance(), p.hack().wire())) {
			Hacking.announceRange();
		}

		ServerPlayer player = server.getPlayerList().getPlayer(p.asker());
		HostSession session = HostSessions.of(owner);
		Cyberblock.LOG.info("rhack: {} answered {} {}{}", session == null ? owner : session.name(), p.hack().wire(), result.ok() ? "ok" : "refused",
			result.ok() ? "" : " (" + result.reason() + ")");
		if (player == null) {
			return true;
		}

		Component name = name(p.hack());
		player.sendOverlayMessage(result.ok()
			? Component.translatableWithFallback("cyberblock.rhack.done", "%s: done", name).withStyle(ChatFormatting.GREEN)
			: Component.translatableWithFallback("cyberblock.rhack.failed", "%s failed: %s", name, result.reason()).withStyle(ChatFormatting.RED));
		ServerNet.ui(player, ActWire.UI_RESULT, ActWire.result(p.hack(), result.ok(), result.reason()));
		if (result.ok()) {
			landed(player, p);
		}

		return true;
	}

	/** The Minecraft side of a hack Night City ran: the camera view, outlines and pings for the asker's team. */
	private static void landed(final ServerPlayer player, final Pending p) {
		switch (p.hack()) {
			case CAM_FEED -> ServerNet.ui(player, ActWire.UI_CAMFEED, ActWire.camFeed(p.at(), p.yaw(), p.name()));
			case OUTLINE -> {
				List<Integer> ids = new ArrayList<>();
				for (Entity e : figures(player.level(), p.at(), RemoteHack.OUTLINE_RADIUS)) {
					ids.add(e.getId());
				}

				String json = ActWire.outline(ids, p.hack().seconds());
				team(player).forEach(q -> ServerNet.ui(q, ActWire.UI_OUTLINE, json));
			}
			case PING_AREA -> {
				for (Entity e : figures(player.level(), p.at(), RemoteHack.PING_RADIUS)) {
					Bridge.pingProxy.accept(e, p.hack().seconds() * 20);
				}

				String json = ActWire.ping(p.at(), p.hack().seconds(), player.getPlainTextName());
				team(player).forEach(q -> ServerNet.ui(q, ActWire.UI_PING, json));
			}
			default -> {
			}
		}
	}

	/** The live figures within `radius` of a point. */
	private static List<Entity> figures(final ServerLevel level, final Vec3 at, final double radius) {
		return level.getEntities((Entity) null, new AABB(at, at).inflate(radius),
			e -> e instanceof ProxyEntity proxy && !proxy.dead() && e.position().distanceToSqr(at) <= radius * radius);
	}

	/** The asker and every online player on his vanilla scoreboard team (Phase 8's match teams build on the same). */
	private static List<ServerPlayer> team(final ServerPlayer player) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer q : player.level().getServer().getPlayerList().getPlayers()) {
			if (q == player || q.getTeam() != null && q.isAlliedTo(player)) {
				out.add(q);
			}
		}

		return out;
	}

	// ---- the Vehicle Panel from a right-click ----------------------------------------------------------------------------

	/** Right-click a car figure with the Cyberdeck Link: the server opens the Vehicle Panel on it (both sides take the click). */
	private static InteractionResult useEntity(final Player player, final Level level, final InteractionHand hand, final Entity entity,
		final @Nullable EntityHitResult hit) {
		if (player.isSpectator() || entity.getType() != ProxyEntities.VEHICLE || !(entity instanceof ProxyEntity proxy) || proxy.dead()
			|| !BuiltInRegistries.ITEM.getKey(player.getItemInHand(hand).getItem()).toString().equals(CYBERDECK_LINK)) {
			return InteractionResult.PASS;
		}

		if (player instanceof ServerPlayer serverPlayer) {
			ServerNet.ui(serverPlayer, ActWire.UI_VPANEL, ActWire.vpanel(entity.getId(), proxy.label()));
		}

		return InteractionResult.SUCCESS;
	}

	// ---- Comms Panel ---------------------------------------------------------------------------------------------------

	private static void onComms(final ServerPlayer player, final JsonObject m) {
		ActWire.Comms comms = ActWire.parseComms(m);
		if (comms == null) {
			warn("bad comms dropped");
			return;
		}

		long now = Util.getMillis();
		String key = "comms:" + player.getUUID();
		long wait = cooldowns.secondsLeft(key, now);
		HostSession session = HostSessions.of(comms.to());
		ServerPlayer host = session == null ? null : session.player();
		if (wait > 0) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.comms.wait", "Comms: wait %s s", wait).withStyle(ChatFormatting.RED));
			return;
		}

		if (host == null) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.comms.no_host", "Comms: that host is not linked").withStyle(ChatFormatting.RED));
			return;
		}

		cooldowns.start(key, now, COMMS_SECONDS);
		ServerNet.ui(host, ActWire.UI_COMMS, ActWire.commsUi(player.getPlainTextName(), comms.text(), comms.p()));
		player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.comms.sent", "Comms: sent to %s", session.name()).withStyle(ChatFormatting.GREEN));
		Cyberblock.LOG.info("comms: {} -> {} ({}{})", player.getPlainTextName(), session.name(), comms.text().isEmpty() ? "" : "message",
			comms.p() == null ? "" : comms.text().isEmpty() ? "waypoint" : " and waypoint");
	}

	// ---- Net Map -------------------------------------------------------------------------------------------------------

	/**
	 * {"hosts":[{"id":"&lt;uuid&gt;","name","p":[x,y,z],"district","active","blackout":s}],"cams":[{"h":"&lt;uuid&gt;","id":"&lt;id&gt;",
	 * "p":[x,y,z],"yaw"}],"range":50|0}: the linked hosts (where their V is, their district, how long a blackout there still runs),
	 * the cameras their games stream, and the Phase 0 (h) fallback range (0: every streamed target).
	 */
	private static void netMap(final ServerPlayer player) {
		long now = Util.getMillis();
		Long last = lastNetMap.put(player.getUUID(), now);
		if (last != null && now - last < NETMAP_MS) {
			return;
		}

		JsonArray hosts = new JsonArray();
		for (HostSession s : HostSessions.all()) {
			ServerPlayer v = s.player();
			if (v == null) {
				continue;
			}

			JsonObject h = new JsonObject();
			h.addProperty("id", s.uuid().toString());
			h.addProperty("name", s.name());
			h.add("p", ActWire.vec(v.position()));
			h.addProperty("district", s.district());
			h.addProperty("active", s.active());
			h.addProperty("blackout", cooldowns.secondsLeft("blackout:" + s.uuid(), now));
			hosts.add(h);
		}

		JsonArray cams = new JsonArray();
		for (NetIndex.Cam c : index.cameras(now)) {
			if (cams.size() == MAX_CAMS) {
				break;
			}

			JsonObject o = new JsonObject();
			o.addProperty("h", c.owner().toString());
			o.addProperty("id", Long.toUnsignedString(c.id()));
			o.add("p", ActWire.vec(new Vec3(c.x(), c.y(), c.z())));
			o.addProperty("yaw", c.yaw());
			cams.add(o);
		}

		JsonObject m = new JsonObject();
		m.add("hosts", hosts);
		m.add("cams", cams);
		m.addProperty("range", RangeRule.SERVER.fallback() ? RangeRule.FALLBACK_RANGE : 0.0);
		ServerNet.ui(player, ActWire.UI_NETMAP, m.toString());
	}

	// ---- housekeeping --------------------------------------------------------------------------------------------------

	/** Once a second: requests Night City never answered are told; feed requests follow the viewers. */
	private static void tick(final MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}

		long now = Util.getMillis();
		for (Iterator<Map.Entry<CyberRoute.Key, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<CyberRoute.Key, Pending> e = it.next();
			HostSession session = HostSessions.of(e.getKey().owner());
			long timeoutMs = Hacking.answerTimeout(session != null && session.active()) / 1_000_000L;
			if (now - e.getValue().sentMs() > timeoutMs) {
				it.remove();
				ServerPlayer player = server.getPlayerList().getPlayer(e.getValue().asker());
				if (player != null) {
					Component why = Component.translatableWithFallback("cyberblock.rhack.no_answer", "%s: Night City did not answer", name(e.getValue().hack()));
					player.sendOverlayMessage(why.copy().withStyle(ChatFormatting.RED));
					ServerNet.ui(player, ActWire.UI_RESULT, ActWire.result(e.getValue().hack(), false, why.getString()));
				}
			}
		}

		PhotoFeed.tick(server, now);
	}

	private static void clear() {
		pending.clear();
		lastNetMap.clear();
		index.clear();
		cooldowns.clear();
		PhotoFeed.clear();
	}

	/** A hostile client could make this a log flood: say it once, then every 100th time. */
	private static void warn(final String what) {
		if (bad++ % 100 == 0) {
			Cyberblock.LOG.warn("{} (seen {} times)", what, bad);
		}
	}
}
