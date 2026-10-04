package dev.cyberblock.client.mode.overlord;

import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.client.ui.screen.AtlasScreen;
import dev.cyberblock.mode.overlord.OverlordWire;
import dev.cyberblock.net.Payloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The client side of the Overlord Console (megaplan 8-O1): the {@code cyberblock:ui ovl} route (a state with {@code open:true} opens
 * the console on the Atlas), the newest state for the console, the once-a-second {@code act ovlpoll} while it is open, and the spawn
 * and event requests. Client thread.
 */
public final class OverlordClient {
	static final int POLL_TICKS = 20;
	private static OverlordWire.@Nullable State state;
	private static @Nullable AtlasScreen console;
	private static int ticks, bad;

	private OverlordClient() {
	}

	/** From the client entrypoint. */
	public static void init() {
		UiRoutes.listen(OverlordWire.UI_STATE, OverlordClient::onUi);
		ClientTickEvents.END_CLIENT_TICK.register(OverlordClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(() -> {
			state = null;
			console = null;
		}));
	}

	public static OverlordWire.@Nullable State state() {
		return state;
	}

	static void onUi(final String json) {
		OverlordWire.State s;
		try {
			s = OverlordWire.parseState(JsonParser.parseString(json).getAsJsonObject());
		} catch (RuntimeException e) {
			s = null;
		}

		if (s == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("overlord: bad console state dropped ({} so far)", bad);
			}

			return;
		}

		state = s;
		Minecraft minecraft = Minecraft.getInstance();
		if (s.open() && minecraft.player != null && !showing(minecraft)) {
			open(minecraft, s);
		}
	}

	static boolean showing(final Minecraft minecraft) {
		return console != null && minecraft.gui.screen() == console;
	}

	/** The console: the Atlas centred on the match area, zoomed to fit it beside the palette. */
	private static void open(final Minecraft minecraft, final OverlordWire.State s) {
		double[] a = s.area();
		double cx = a == null ? minecraft.player.getX() : (a[0] + a[2]) / 2.0, cz = a == null ? minecraft.player.getZ() : (a[1] + a[3]) / 2.0;
		double zoom = 1.0;
		if (a != null) {
			double w = minecraft.getWindow().getGuiScaledWidth() - OverlordConsole.PANEL_W - 24.0, h = minecraft.getWindow().getGuiScaledHeight() - 60.0;
			zoom = Math.max(0.25, Math.min(4.0, Math.min(w / Math.max(1.0, a[2] - a[0]), h / Math.max(1.0, a[3] - a[1]))));
		}

		// the palette panel covers the right of the map: shift the centre so the area sits in the free part
		cx += OverlordConsole.PANEL_W / 2.0 / zoom;
		console = new AtlasScreen(null, Component.translatableWithFallback("cyberblock.overlord.console", "Overlord Console"), new OverlordConsole(),
			cx, cz, zoom);
		minecraft.gui.setScreen(console);
		Cyberblock.LOG.info("overlord: console opened (match {})", s.match());
	}

	/** A spawn or event at a map point; the server answers with a fresh state (and an action-bar line). */
	static void ask(final boolean event, final String id, final double x, final double z) {
		if (ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			ClientPlayNetworking.send(new Payloads.Act(event ? OverlordWire.T_EVENT : OverlordWire.T_SPAWN, OverlordWire.ask(id, x, z)));
		}
	}

	private static void tick(final Minecraft minecraft) {
		if (!showing(minecraft)) {
			return;
		}

		if (++ticks % POLL_TICKS == 0 && ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			ClientPlayNetworking.send(new Payloads.Act(OverlordWire.T_POLL, "{}"));
		}
	}
}
