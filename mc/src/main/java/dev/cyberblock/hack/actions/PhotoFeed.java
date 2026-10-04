package dev.cyberblock.hack.actions;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.JoinLock;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.net.ServerNet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
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
 * (`cyberblock:ui t:"feed"`) and to the players holding the host's feed map, a locked vanilla filled map item that remembers its
 * host (custom data), so after a restart holding it finds its host again.
 * <p>
 * The map's saved colours stay blank: vanilla sends a map's saved colours to every player carrying a copy (and to item-frame
 * viewers), whatever their permissions, and a cartography table clones it. So each frame goes as a map packet of its own to each
 * holder the owner's `hacks` toggle admits at that frame (and who is not locked by the join password), and a holder who is turned
 * away gets a blank picture once. Server thread.
 */
final class PhotoFeed {
	static final String TAG = "cyberblock_feed";
	static final FeedViewers viewers = new FeedViewers();
	/** The feed map of each host (created on the first "take the feed map"). */
	private static final Map<UUID, MapId> maps = new HashMap<>();
	/** A holder's feed map: its host and map id (he may hold an older copy or a clone of another id). */
	private record Held(UUID host, MapId id) {
	}

	/** The feed map each online holder held last. */
	private static final Map<UUID, Held> held = new HashMap<>();
	/** Holders whose client was last sent a picture (they get a blank one as soon as they are turned away, held or not). */
	private static final Set<UUID> shown = new HashSet<>();
	/** Feed map ids already blanked on this server run (a map saved by an older build may still hold a frame). */
	private static final Set<MapId> scrubbed = new HashSet<>();
	private static final byte[] BLANK = new byte[ActWire.FEED_BYTES];
	private static int bad;

	private PhotoFeed() {
	}

	static void init() {
		HostSessions.listen("feed", PhotoFeed::onFeed);
		HostSessions.onStart(session -> viewers.restarted(session.uuid()));
		HostSessions.onEnd(session -> viewers.ended(session.uuid()));
		Cyberblock.LOG.info("feed: feed maps get each frame as a per-holder map packet (the map's saved colours stay blank)");
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
		scrub(server, id);
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
	 * before the owner switched guests off stops updating, and its holder's picture is blanked); feedreq goes on or off where that
	 * changed.
	 */
	static void tick(final MinecraftServer server, final long now) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			for (InteractionHand hand : InteractionHand.values()) {
				ItemStack stack = p.getItemInHand(hand);
				UUID host = hostOf(stack);
				MapId id = stack.get(DataComponents.MAP_ID);
				if (host == null || id == null) {
					continue;
				}

				scrub(server, id);
				Held before = held.put(p.getUUID(), new Held(host, id));
				if (before != null && !before.equals(held.get(p.getUUID()))) {
					blank(p, before); // he switched maps: the one he holds no longer is not kept up to date
				}

				if (admitted(host, p)) {
					viewers.holding(host, p.getUUID(), now);
				}

				break; // one feed map per player: the main hand's, else the off hand's
			}
		}

		// turned away (the owner's `hacks` toggle, the session, the join lock): the picture his client was shown goes blank at once
		for (UUID viewer : Set.copyOf(shown)) {
			ServerPlayer p = server.getPlayerList().getPlayer(viewer);
			Held h = held.get(viewer);
			if (p == null || h == null) {
				shown.remove(viewer);
			} else if (!admitted(h.host(), p)) {
				blank(p, h);
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

	/** May this online player see this host's picture now: {@link #mayWatch} and not locked by the join password. */
	private static boolean admitted(final UUID host, final ServerPlayer player) {
		return mayWatch(host, player.getUUID()) && !JoinLock.locked(player);
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

	/** Once per server run and map: its saved colours blank (vanilla then sends the blank to everyone carrying or framing it). */
	private static void scrub(final MinecraftServer server, final MapId id) {
		if (!scrubbed.add(id)) {
			return;
		}

		MapItemSavedData data = server.overworld().getMapData(id);
		if (data == null) {
			return;
		}

		int cleared = 0;
		for (int i = 0; i < data.colors.length; i++) {
			if (data.colors[i] != 0) {
				data.setColor(i % ActWire.FEED_SIDE, i / ActWire.FEED_SIDE, (byte) 0);
				cleared++;
			}
		}

		if (cleared > 0) {
			Cyberblock.LOG.info("feed: map {} held a saved frame; blanked", id.id());
		}
	}

	/** `feed` UP: the frame goes to the host's Net Map viewers and feed map holders that are admitted at this frame. */
	static void onFeed(final HostSession session, final com.google.gson.JsonObject m) {
		byte[] px = ActWire.parseFeed(m);
		ServerPlayer host = session.player();
		if (px == null || host == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("feed: bad feed from {} dropped (seen {} times)", session.name(), bad);
			}

			return;
		}

		MinecraftServer server = host.level().getServer();
		// the same clock as watch() (Util.getMillis, not the wall clock); the toggle, the session and the join lock again at every frame
		FeedViewers.Recipients to = viewers.recipients(session.uuid(), Util.getMillis(), viewer -> {
			ServerPlayer p = server.getPlayerList().getPlayer(viewer);
			return p != null && admitted(session.uuid(), p);
		});
		String json = ActWire.feedUi(session.uuid(), px);
		for (UUID viewer : to.screens()) {
			ServerPlayer p = server.getPlayerList().getPlayer(viewer);
			if (p != null) {
				ServerNet.ui(p, ActWire.UI_FEED, json);
			}
		}

		for (UUID viewer : to.maps()) {
			Held h = held.get(viewer);
			ServerPlayer p = server.getPlayerList().getPlayer(viewer);
			if (h != null && p != null && h.host().equals(session.uuid())) {
				p.connection.send(picture(h.id(), px));
				shown.add(viewer);
			}
		}
	}

	/** Blanks the feed map a holder's client was shown, once. */
	private static void blank(final ServerPlayer player, final Held h) {
		if (shown.remove(player.getUUID())) {
			player.connection.send(picture(h.id(), BLANK));
		}
	}

	/** One whole 128 x 128 picture for this map id on one client only; the map's saved colours are not touched. */
	private static ClientboundMapItemDataPacket picture(final MapId id, final byte[] px) {
		return new ClientboundMapItemDataPacket(id, (byte) 0, true, Optional.empty(),
			Optional.of(new MapItemSavedData.MapPatch(0, 0, ActWire.FEED_SIDE, ActWire.FEED_SIDE, px)));
	}

	static void left(final UUID player) {
		viewers.left(player);
		held.remove(player);
		shown.remove(player);
	}

	static void clear() {
		viewers.clear();
		maps.clear();
		held.clear();
		shown.clear();
		scrubbed.clear();
	}
}
