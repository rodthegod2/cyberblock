package dev.cyberblock.client.hack.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.cyber.HackWheel;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.client.ui.UiLink;
import dev.cyberblock.cyber.Hacking;
import dev.cyberblock.hack.actions.ActWire;
import dev.cyberblock.hack.actions.ActionItems;
import dev.cyberblock.net.Payloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.minecraft.world.waypoints.Waypoint;
import org.jspecify.annotations.Nullable;

/**
 * The interaction map on the client (megaplan task 6-I1): the items open their screens, the server's answers arrive here
 * (`cyberblock:ui` `ract`, `netmap`, `feed`, `vpanel`, `camfeed`, `outline`, `netping`, `comms`, `scanowner`), outlines and pings
 * are drawn in the world for their time, and a Comms message becomes a toast and a locator-bar waypoint on the host's own HUD
 * (Minecraft's HUD is the host's HUD; no Cyberpunk change). Client thread.
 */
public final class ActionsClient {
	/** A Comms waypoint stays on the locator bar this long (or until the same sender sends another). */
	static final long WAYPOINT_MS = 300_000L;
	/** The answer to the player's last request, for the open screen. */
	record Result(String hack, boolean ok, String reason, long atMs) {
	}

	record Ping(Vec3 p, long untilMs, String by) {
	}

	private static volatile NetState state = NetState.EMPTY;
	private static long stateAtMs;
	private static @Nullable Result last;
	/** Outlined figures: entity id -> until (ms). */
	private static final Map<Integer, Long> outlines = new HashMap<>();
	private static final List<Ping> pings = new ArrayList<>();
	/** Comms waypoints: sender -> waypoint id, and waypoint id -> until (ms). */
	private static final Map<String, UUID> waypointOf = new HashMap<>();
	private static final Map<UUID, Long> waypointUntil = new HashMap<>();
	/** The host the screens act on (shared, so the Net Map and the tools agree). */
	static @Nullable UUID selected;
	private static int bad;

	private ActionsClient() {
	}

	/** From the client entrypoint. */
	public static void init() {
		ActionItems.clientOpen = kind -> Minecraft.getInstance().execute(() -> open(kind));
		route(ActWire.UI_RESULT, m -> last = new Result(m.get("hack").getAsString(), m.get("ok").getAsBoolean(), text(m, "reason", 128), Util.getMillis()));
		route(ActWire.UI_NETMAP, m -> {
			NetState parsed = NetState.parse(m);
			if (parsed != null) {
				state = parsed;
				stateAtMs = Util.getMillis();
			}
		});
		route(ActWire.UI_FEED, m -> {
			byte[] px = ActWire.parseFeed(feedMessage(m));
			if (px != null) {
				FeedTexture.put(UUID.fromString(m.get("host").getAsString()), px);
			}
		});
		route(ActWire.UI_VPANEL, m -> Minecraft.getInstance().gui.setScreen(new VehiclePanelScreen(m.get("eid").getAsInt(), text(m, "name", 64))));
		route(ActWire.UI_CAMFEED, m -> {
			Vec3 p = ActWire.position(m.get("p"));
			if (p != null) {
				Minecraft.getInstance().gui.setScreen(new CameraFeedScreen(p, m.get("yaw").getAsFloat(), text(m, "name", 64)));
			}
		});
		route(ActWire.UI_OUTLINE, m -> {
			long until = Util.getMillis() + Math.clamp(m.get("secs").getAsLong(), 1L, 120L) * 1000L;
			JsonArray ids = m.getAsJsonArray("ids");
			for (int i = 0; i < Math.min(ids.size(), 256); i++) {
				outlines.put(ids.get(i).getAsInt(), until);
			}
		});
		route(ActWire.UI_PING, m -> {
			Vec3 p = ActWire.position(m.get("p"));
			if (p != null && pings.size() < 64) {
				pings.add(new Ping(p, Util.getMillis() + Math.clamp(m.get("secs").getAsLong(), 1L, 120L) * 1000L, text(m, "by", 32)));
			}
		});
		route(ActWire.UI_COMMS, ActionsClient::onComms);
		route(Hacking.SCAN_OWNER, m -> HackWheel.scanOwner(text(m, "name", 32)));
		ClientTickEvents.END_CLIENT_TICK.register(ActionsClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(ActionsClient::clear));
		ShotTour.init();
		Cyberblock.LOG.info("hack: interaction map screens ready (Vehicle Panel, Traffic Tool, Net Map, Comms Panel)");
	}

	private static void route(final String t, final Consumer<JsonObject> handler) {
		UiRoutes.listen(t, json -> {
			try {
				handler.accept(JsonParser.parseString(json).getAsJsonObject());
			} catch (RuntimeException e) {
				if (bad++ % 100 == 0) {
					Cyberblock.LOG.warn("hack: bad ui {} dropped ({} so far)", t, bad);
				}
			}
		});
	}

	/** A string field, at most `max` characters. */
	private static String text(final JsonObject m, final String key, final int max) {
		JsonElement e = m.get(key);
		String s = e == null || e.isJsonNull() ? "" : e.getAsString();
		return s.length() > max ? s.substring(0, max) : s;
	}

	/** The ui feed {host, px} as the `feed` UP the parser reads (128 x 128). */
	private static JsonObject feedMessage(final JsonObject m) {
		JsonObject f = new JsonObject();
		f.addProperty("w", ActWire.FEED_SIDE);
		f.addProperty("h", ActWire.FEED_SIDE);
		f.add("px", m.get("px"));
		return f;
	}

	static void open(final ActionItems.Kind kind) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.gui.setScreen(switch (kind) {
			case TRAFFIC_TOOL -> new TrafficToolScreen();
			case NET_MAP -> new NetMapScreen();
			case COMMS_PANEL -> new CommsPanelScreen();
		});
	}

	/** A request to the server; false when it has no Cyberblock channel (nothing sent). */
	static boolean send(final String t, final String json) {
		if (!ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			return false;
		}

		ClientPlayNetworking.send(new Payloads.Act(t, json));
		return true;
	}

	/** Asks the server for the Net Map's state (the answer replaces {@link #state}). */
	static void requestState() {
		send(ActWire.T_NETMAP, "{}");
	}

	static NetState state() {
		return state;
	}

	/** Milliseconds since the last Net Map state (Long.MAX_VALUE: none yet). */
	static long stateAge() {
		return stateAtMs == 0L ? Long.MAX_VALUE : Util.getMillis() - stateAtMs;
	}

	/** The answer to a request sent at or after `sinceMs`, else null. */
	static @Nullable Result lastSince(final long sinceMs) {
		Result r = last;
		return r != null && r.atMs() >= sinceMs ? r : null;
	}

	/** The selected host, or (when none or gone) the first active one, else the first; null without hosts. */
	static NetState.@Nullable Host selectedHost() {
		NetState s = state;
		NetState.Host h = s.host(selected);
		if (h == null) {
			h = s.hosts().stream().filter(NetState.Host::active).findFirst().orElse(s.hosts().isEmpty() ? null : s.hosts().getFirst());
			selected = h == null ? null : h.id();
		}

		return h;
	}

	/** Where the player points: the block his crosshair hits, else his feet. */
	static Vec3 aimedPoint(final Minecraft minecraft) {
		HitResult hit = minecraft.hitResult;
		LocalPlayer player = minecraft.player;
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			return Vec3.atBottomCenterOf(block.getBlockPos().above());
		}

		return player == null ? Vec3.ZERO : player.position();
	}

	/** A Comms message for this host: a toast, and a waypoint on the locator bar (replacing the sender's last one). */
	private static void onComms(final JsonObject m) {
		String from = text(m, "from", 32);
		String text = text(m, "text", ActWire.MAX_TEXT);
		Vec3 p = ActWire.position(m.get("p"));
		Component title = Component.translatableWithFallback("cyberblock.comms.from", "Comms from %s", from);
		Component body = !text.isEmpty() ? Component.literal(text)
			: Component.translatableWithFallback("cyberblock.comms.waypoint", "Waypoint at %s %s %s", (int) Math.floor(p.x), (int) Math.floor(p.y),
				(int) Math.floor(p.z));
		UiLink.toast("sms", title.getString(), body.getString(), 8.0);
		Minecraft minecraft = Minecraft.getInstance();
		if (p == null || minecraft.player == null) {
			return;
		}

		UUID previous = waypointOf.remove(from);
		if (previous != null) {
			untrack(minecraft, previous);
		}

		UUID id = UUID.randomUUID();
		Waypoint.Icon icon = new Waypoint.Icon();
		icon.color = Optional.of(McTheme.AQUA); // opaque ARGB, as Waypoints does (alpha 0 draws nothing)
		minecraft.player.connection.getWaypointManager().trackWaypoint(TrackedWaypoint.setPosition(id, icon, BlockPos.containing(p)));
		waypointOf.put(from, id);
		waypointUntil.put(id, Util.getMillis() + WAYPOINT_MS);
	}

	private static void untrack(final Minecraft minecraft, final UUID id) {
		waypointUntil.remove(id);
		if (minecraft.player != null) {
			minecraft.player.connection.getWaypointManager().untrackWaypoint(TrackedWaypoint.empty(id));
		}
	}

	/** Every client tick (inside vanilla's per-tick gizmo collection): outlines and pings drawn, old ones and waypoints dropped. */
	private static void tick(final Minecraft minecraft) {
		long now = Util.getMillis();
		outlines.values().removeIf(until -> until < now);
		pings.removeIf(p -> p.untilMs() < now);
		for (Iterator<Map.Entry<UUID, Long>> it = waypointUntil.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Long> e = it.next();
			if (e.getValue() < now) {
				it.remove();
				waypointOf.values().remove(e.getKey());
				if (minecraft.player != null) {
					minecraft.player.connection.getWaypointManager().untrackWaypoint(TrackedWaypoint.empty(e.getKey()));
				}
			}
		}

		if (minecraft.level == null) {
			return;
		}

		for (int id : outlines.keySet()) {
			Entity e = minecraft.level.getEntity(id);
			if (e != null && !e.isRemoved()) {
				Gizmos.cuboid(e.getBoundingBox().inflate(0.1), GizmoStyle.stroke(0xE0FF5555, 2.5F)).setAlwaysOnTop();
			}
		}

		for (Ping ping : pings) {
			Vec3 p = ping.p();
			Gizmos.cuboid(new AABB(p.x - 0.3, p.y, p.z - 0.3, p.x + 0.3, p.y + 8.0, p.z + 0.3), GizmoStyle.strokeAndFill(0xE0FFFF55, 2.0F, 0x40FFFF55))
				.setAlwaysOnTop();
		}

		ShotTour.tick(minecraft);
	}

	/** Pings to draw on the Net Map. */
	static List<Ping> pings() {
		return List.copyOf(pings);
	}

	private static void clear() {
		state = NetState.EMPTY;
		stateAtMs = 0L;
		last = null;
		outlines.clear();
		pings.clear();
		waypointOf.clear();
		waypointUntil.clear();
		selected = null;
		FeedTexture.clear();
	}
}
