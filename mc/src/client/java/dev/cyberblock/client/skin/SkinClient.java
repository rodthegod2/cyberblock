package dev.cyberblock.client.skin;

import dev.cyberblock.Cyberblock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Megaplan 5-K1, the client side: the cyber skin (AbstractClientPlayerMixin), the player list marker and the ally outlines. */
public final class SkinClient {
	private SkinClient() {
	}

	public static void init() {
		if (System.getProperty("cyberblock.ownSkin") != null) {
			Cyberblock.LOG.info("skin: -Dcyberblock.ownSkin is ignored since Phase 5: linked players always wear the cyber skin");
		}

		ClientTickEvents.END_CLIENT_TICK.register(AllyOutlines::tick);
	}
}
