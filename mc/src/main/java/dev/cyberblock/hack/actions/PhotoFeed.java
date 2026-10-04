package dev.cyberblock.hack.actions;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.net.ServerNet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jspecify.annotations.Nullable;

/**
 * A host's photo feed (megaplan tasks 6-I1 and 6-D1): the DLL sends `feed` (Cyberpunk's picture as 128 x 128 map colours, at most
 * once a second) only while someone watches, asked with `feedreq on|off`. The frames go to the Net Map viewers
 * (`cyberblock:ui t:"feed"`) and into the host's feed map, a locked vanilla filled map whose colours are the picture, so anyone
 * holding it sees the host's view like any map item. The map item remembers its host (custom data), so after a restart holding it
 * finds its host again. Server thread.
 */
final class PhotoFeed {
	static final String TAG = "cyberblock_feed";
	static final FeedViewers viewers = new FeedViewers();
	/** The feed map of each host (created on the first "take the feed map"). */
	private static final Map<UUID, MapId> maps = new HashMap<>();
	/** Hosts whose DLL sent the feed fallback notice this session (logged once each). */
	private static final java.util.Set<UUID> fellBack = new java.util.HashSet<>();
	private static int bad;

	private PhotoFeed() {
	}

	static void init() {
		HostSessions.listen("feed", PhotoFeed::onFeed);
		HostSessions.onStart(session -> {
			viewers.restarted(session.uuid());
			fellBack.remove(session.uuid()); // a new session's DLL tries its picture copy again
		});
		HostSessions.onEnd(session -> viewers.ended(session.uuid()));
	}

	/** `cyberblock:act t:"feed"`: a Net Map viewer starts, renews or stops watching a host. */
	static void watch(final ServerPlayer player, final ActWire.FeedWatch watch, final long now) {
		viewers.watch(watch.host(), player.getUUID(), watch.on(), now);
	}

	/**
	 * `cyberblock:act t:"feedmap"` (RemoteActions checked the host's session, the toggle and the cooldown): the player gets the
	 * host's feed map (a fresh locked map, made once per host), unless he already carries one of that host.
	 */
	static void giveMap(final ServerPlayer player, final UUID host) {
		HostSession session = HostSessions.of(host);
		MinecraftServer server = player.level().getServer();
		if (session == null) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.hack.no_link", "No Night City to reach: its host left"));
			return;
		}

		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (host.equals(hostOf(player.getInventory().getItem(i)))) {
				player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.feed.map_have", "You already carry %s's feed map", session.name()));
				return;
			}
		}

		MapId id = maps.computeIfAbsent(host, h -> create(server.overworld()));
		ItemStack stack = new ItemStack(Items.FILLED_MAP);
		stack.set(DataComponents.MAP_ID, id);
		stack.set(DataComponents.CUSTOM_NAME, Component.translatableWithFallback("cyberblock.feed.map", "Photo feed: %s", session.name()));
		CompoundTag tag = new CompoundTag();
		tag.putString(TAG, host.toString());
		CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
		// as Ripperdoc and Kit: into the inventory (dropped at his feet when full), the client hears of the slot at once
		player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);

		Cyberblock.LOG.info("feed: {} took {}'s feed map (map {})", player.getPlainTextName(), session.name(), id.id());
	}

	private static MapId create(final ServerLevel level) {
		MapId id = level.getFreeMapId();
		level.setMapData(id, MapItemSavedData.createFresh(0.0, 0.0, (byte) 0, false, false, Level.OVERWORLD).locked());
		return id;
	}

	/**
	 * Once a second: players holding a feed map watch its host (only a live host whose `hacks` toggle lets them in, so a map taken
	 * before the owner switched guests off stops updating); feedreq goes on or off where that changed.
	 */
	static void tick(final MinecraftServer server, final long now) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			for (InteractionHand hand : InteractionHand.values()) {
				UUID host = hostOf(p.getItemInHand(hand));
				if (host != null && mayWatch(host, p.getUUID())) {
					viewers.holding(host, p.getUUID(), now);
				}
			}
		}

		for (FeedViewers.Change change : viewers.changes(now)) {
			HostSession session = HostSessions.of(change.host());
			if (session != null) {
				session.send(ActWire.feedReq(change.on()));
				Cyberblock.LOG.info("feed: {} feedreq {}", session.name(), change.on() ? "on" : "off");
			}
		}
	}

	/** A live session of this host whose `hacks` toggle admits the viewer (the owner and other hosts always). */
	static boolean mayWatch(final UUID host, final UUID viewer) {
		HostSession session = HostSessions.of(host);
		return session != null && session.active() && Permissions.allows(host, viewer, Permissions.Toggle.HACKS);
	}

	/** The host a feed map item shows (and its map noted again after a restart), null for any other item. */
	private static @Nullable UUID hostOf(final ItemStack stack) {
		MapId id = stack.get(DataComponents.MAP_ID);
		CustomData data = id == null ? null : stack.get(DataComponents.CUSTOM_DATA);
		String text = data == null ? "" : data.copyTag().getStringOr(TAG, "");
		if (text.isEmpty()) {
			return null;
		}

		try {
			UUID host = UUID.fromString(text);
			maps.putIfAbsent(host, id);
			return host;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** `feed` UP: the frame goes to the host's Net Map viewers and into its feed map. */
	static void onFeed(final HostSession session, final com.google.gson.JsonObject m) {
		if (ActWire.isFeedFallback(m)) {
			// the DLL gave up its picture copy this session; Minecraft has no replica stand-in for the feed (docs/notes/phase6-descopes.md)
			if (fellBack.add(session.uuid())) {
				Cyberblock.LOG.info("feed: {} has no photo feed this session ({}); the viewers get no picture", session.name(),
					m.get("fallback").getAsString());
			}

			return;
		}

		byte[] px = ActWire.parseFeed(m);
		ServerPlayer host = session.player();
		if (px == null || host == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("feed: bad feed from {} dropped (seen {} times)", session.name(), bad);
			}

			return;
		}

		MinecraftServer server = host.level().getServer();
		String json = ActWire.feedUi(session.uuid(), px);
		// the same clock as watch() (Util.getMillis, not the wall clock), and the toggle again: it may have changed since the watch
		for (UUID viewer : viewers.screenViewers(session.uuid(), Util.getMillis())) {
			ServerPlayer p = server.getPlayerList().getPlayer(viewer);
			if (p != null && Permissions.allows(session.uuid(), viewer, Permissions.Toggle.HACKS)) {
				ServerNet.ui(p, ActWire.UI_FEED, json);
			}
		}

		MapId id = maps.get(session.uuid());
		MapItemSavedData data = id == null ? null : server.overworld().getMapData(id);
		if (data != null) {
			for (int z = 0; z < ActWire.FEED_SIDE; z++) {
				for (int x = 0; x < ActWire.FEED_SIDE; x++) {
					data.setColor(x, z, px[z * ActWire.FEED_SIDE + x]);
				}
			}
		}
	}

	static void left(final UUID player) {
		viewers.left(player);
	}

	static void clear() {
		viewers.clear();
		maps.clear();
		fellBack.clear();
	}
}
