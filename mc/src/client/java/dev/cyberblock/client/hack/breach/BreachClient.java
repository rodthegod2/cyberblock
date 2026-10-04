package dev.cyberblock.client.hack.breach;

import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.hack.breach.BreachWire;
import net.minecraft.client.Minecraft;

/**
 * The client side of Breach Protocol (megaplan 6-B1): every {@code cyberblock:ui breach} updates the {@link BreachScreen} showing that
 * puzzle, or opens one for a breach that is still running (never for a breach the player already closed). Client thread.
 */
public final class BreachClient {
	private static int bad;
	/** The puzzle the player closed last: its late messages (the abort's answer) never reopen it. */
	private static int closed = -1;

	private BreachClient() {
	}

	/** From the client entrypoint. */
	public static void init() {
		UiRoutes.listen(BreachWire.T, BreachClient::onUi);
	}

	static void onUi(final String json) {
		BreachWire.Screen data;
		try {
			data = BreachWire.parse(JsonParser.parseString(json).getAsJsonObject());
		} catch (RuntimeException e) {
			data = null;
		}

		if (data == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("breach: bad screen data dropped ({} so far)", bad);
			}

			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui.screen() instanceof BreachScreen screen && screen.id() == data.id()) {
			screen.update(data);
		} else if (!data.done() && data.id() != closed) {
			minecraft.gui.setScreen(new BreachScreen(data));
		}
	}

	static void closed(final int id) {
		closed = id;
	}
}
