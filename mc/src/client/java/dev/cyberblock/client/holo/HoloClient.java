package dev.cyberblock.client.holo;

import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.atlas.AtlasStore;
import dev.cyberblock.client.atlas.AtlasTiles;
import dev.cyberblock.client.atlas.AtlasWire;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.holo.HoloRules;
import dev.cyberblock.holo.HoloWire;
import dev.cyberblock.net.Payloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * The client side of the Holo-Table (megaplan 7-T1): the {@code cyberblock:ui holo} route (the first state, {@code open:true}, opens
 * the table view), the newest table state for the views, the once-a-second ask while a view is open (with the replica detail every
 * 5 s), and, for a guest whose Atlas shows the server's shared map, the map tiles under the miniature ({@code act maptile}, answered
 * into that map by 3C's ServerMap; nearest first, a missing tile again after 10 s, a known one after 60 s). Client thread.
 */
public final class HoloClient {
	static final int POLL_TICKS = 20;
	static final int DETAIL_EVERY = 5;
	static final int TILE_TICKS = 10;
	static final long MISSING_NANOS = 10_000_000_000L, KNOWN_NANOS = 60_000_000_000L;
	private static HoloWire.@Nullable State state;
	private static @Nullable List<HoloWire.Detail> detail;
	private static int version;
	private static int ticks, polls;
	private static int bad;
	/** The tiles the miniature covers now (inclusive), set by the view each frame; null when no view is open. */
	private static int @Nullable [] window;
	private static final Map<Long, Long> asked = new HashMap<>();
	static final long TRAVEL_NANOS = 60_000_000_000L;
	/** The fast travel asked for, waiting for the server's go-ahead. */
	private static HoloFastTravel.@Nullable Point travelling;
	private static long travelAsked;

	private HoloClient() {
	}

	/** From the client entrypoint. */
	public static void init() {
		UiRoutes.listen(HoloWire.T_STATE, HoloClient::onUi);
		UiRoutes.listen(HoloWire.T_TRAVEL, HoloClient::onTravel);
		ClientTickEvents.END_CLIENT_TICK.register(HoloClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(HoloClient::clear));
	}

	/** The newest table state (null before the first). */
	public static HoloWire.@Nullable State state() {
		return state;
	}

	/** The newest replica detail (kept between answers that carry none). */
	public static List<HoloWire.Detail> detail() {
		List<HoloWire.Detail> d = detail;
		return d == null ? List.of() : d;
	}

	/** Bumped whenever the state or the detail changes. */
	public static int version() {
		return version;
	}

	static void onUi(final String json) {
		HoloWire.State s;
		try {
			s = HoloWire.parseState(JsonParser.parseString(json).getAsJsonObject());
		} catch (RuntimeException e) {
			s = null;
		}

		if (s == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("holo: bad table state dropped ({} so far)", bad);
			}

			return;
		}

		state = s;
		if (s.detail() != null) {
			detail = s.detail();
		}

		version++;
		Minecraft minecraft = Minecraft.getInstance();
		if (s.open() && minecraft.player != null && !viewing(minecraft.gui.screen())) {
			HoloScreen.open(minecraft);
		}
	}

	/** Is this screen a Holo-Table view (the 3D table or its 2D Atlas)? */
	static boolean viewing(final @Nullable Screen screen) {
		return screen != null && (screen instanceof HoloScreen || screen == HoloScreen.flat);
	}

	/** The view's miniature covers tiles tx0..tx1, tz0..tz1 now (the guest tile asks go nearest the centre first). */
	static void cover(final int tx0, final int tz0, final int tx1, final int tz1) {
		window = new int[] {tx0, tz0, tx1, tz1};
	}

	/** Spawn there; the view closes (a refusal comes back as a chat line). */
	static void spawn(final HoloRules.Kind kind, final String id) {
		if (ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			ClientPlayNetworking.send(new Payloads.Act(HoloWire.T_SPAWN, HoloWire.spawn(kind, id)));
		}
	}

	/**
	 * Fast travel to one of the host's points: the server checks the spawn rules (and takes a host at the table out of the hub first),
	 * then answers {@code ui holotravel} and the point's own action (7V's {@code uiact fasttravel}) runs; a refusal is a chat line.
	 */
	static void travel(final HoloFastTravel.Point point) {
		if (ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			travelling = point;
			travelAsked = System.nanoTime();
			ClientPlayNetworking.send(new Payloads.Act(HoloWire.T_TRAVEL, HoloWire.travel(point.name(), point.x(), point.z())));
		}
	}

	/** The server's go-ahead for the fast travel this client asked for (another name, or one older than a minute, is ignored). */
	static void onTravel(final String json) {
		HoloWire.Travel go;
		try {
			go = HoloWire.parseTravel(JsonParser.parseString(json).getAsJsonObject());
		} catch (RuntimeException e) {
			go = null;
		}

		HoloFastTravel.Point point = travelling;
		travelling = null;
		if (go == null || point == null || !HoloWire.cap(point.name(), HoloWire.NAME_MAX).equals(go.name())
			|| System.nanoTime() - travelAsked > TRAVEL_NANOS) {
			Cyberblock.LOG.info("holo: fast travel go-ahead ignored (not the one asked for)");
			return;
		}

		Cyberblock.LOG.info("holo: fast travel to {}", point.name());
		point.travel().run();
	}

	static void hide(final boolean on) {
		if (ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			ClientPlayNetworking.send(new Payloads.Act(HoloWire.T_HIDE, HoloWire.hide(on)));
		}
	}

	private static void tick(final Minecraft minecraft) {
		ticks++;
		if (!viewing(minecraft.gui.screen()) || !ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			window = null;
			return;
		}

		if (ticks % POLL_TICKS == 0) {
			ClientPlayNetworking.send(new Payloads.Act(HoloWire.T_POLL, HoloWire.poll(polls++ % DETAIL_EVERY == 0)));
		}

		int[] w = window;
		if (ticks % TILE_TICKS == 0 && w != null && AtlasStore.view() != AtlasStore.tiles) {
			List<long[]> due = due(w, AtlasStore.view(), System.nanoTime(), AtlasWire.MAX_MAPTILE_TILES / 2);
			if (!due.isEmpty()) {
				ClientPlayNetworking.send(new Payloads.Act("maptile", AtlasWire.mapTileRequest(due)));
			}
		}
	}

	/**
	 * Up to {@code max} tiles of the window to ask for now, nearest the centre first: a missing one every 10 s, a known one every
	 * 60 s (with version 0: the server sends it whole). They count as asked now.
	 */
	static List<long[]> due(final int[] w, final AtlasTiles view, final long now, final int max) {
		List<long[]> out = new ArrayList<>();
		int cx = (w[0] + w[2]) / 2, cz = (w[1] + w[3]) / 2;
		int reach = Math.max(w[2] - w[0], w[3] - w[1]) / 2 + 1;
		for (int r = 0; r <= reach && out.size() < max; r++) {
			for (int tz = cz - r; tz <= cz + r && out.size() < max; tz++) {
				for (int tx = cx - r; tx <= cx + r && out.size() < max; tx++) {
					if (Math.max(Math.abs(tx - cx), Math.abs(tz - cz)) != r || tx < w[0] || tx > w[2] || tz < w[1] || tz > w[3]) {
						continue;
					}

					long key = AtlasTiles.key(tx, tz);
					Long at = asked.get(key);
					if (at == null || now - at >= (view.hasTile(tx, tz) ? KNOWN_NANOS : MISSING_NANOS)) {
						asked.put(key, now);
						out.add(new long[] {tx, tz, 0L});
					}
				}
			}
		}

		return out;
	}

	private static void clear() {
		state = null;
		detail = null;
		window = null;
		asked.clear();
		travelling = null;
		version++;
	}
}
