package dev.cyberblock.client.ui;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

/**
 * Megaplan 3-E3: the server's Offline Builds summary (chat line {@code cyberblock.offline.summary}: blocks placed, removed, by whom)
 * also shows as a toast when a host comes back: "While you were away" over "N blocks placed, M removed (by A, B)".
 */
public final class OfflineBuildsToast {
	static final String KEY = "cyberblock.offline.summary";

	private OfflineBuildsToast() {
	}

	public static void init() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			String text = overlay ? null : text(message);
			if (text != null) {
				UiLink.toast("info", Component.translatableWithFallback("cyberblock.offline.title", "While you were away").getString(), text, 12.0);
			}
		});
	}

	/** The toast's line for a summary chat line ("N blocks placed, M removed (by A, B)"), else null. */
	static @Nullable String text(final Component message) {
		if (!(message.getContents() instanceof TranslatableContents t) || !t.getKey().equals(KEY) || t.getArgs().length < 3) {
			return null;
		}

		return Component.translatableWithFallback("cyberblock.offline.counts", "%s blocks placed, %s removed (by %s)", t.getArgument(0).getString(),
			t.getArgument(1).getString(), t.getArgument(2).getString()).getString();
	}
}
