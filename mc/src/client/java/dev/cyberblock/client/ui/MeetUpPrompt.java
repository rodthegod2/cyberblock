package dev.cyberblock.client.ui;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

/**
 * Megaplan 3-E2: a Meet Up request (the server's chat line {@code cyberblock.meetup.request} with its clickable [Accept]) also shows
 * as a toast, so a player looking at Night City notices it. Accepting stays the chat line or {@code /cyberblock meetup accept}: a
 * toast cannot be clicked. A client without the mod still gets the chat line.
 */
public final class MeetUpPrompt {
	static final String KEY = "cyberblock.meetup.request";

	private MeetUpPrompt() {
	}

	public static void init() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			String from = overlay ? null : from(message);
			if (from != null) {
				UiLink.toast("sms", Component.translatableWithFallback("cyberblock.meetup.prompt.title", "Meet Up: %s", from).getString(),
					Component.translatableWithFallback("cyberblock.meetup.prompt.text", "Click [Accept] in chat within 60 s").getString(), 10.0);
			}
		});
	}

	/** Who asks, when this chat line is a Meet Up request; else null. */
	static @Nullable String from(final Component message) {
		return message.getContents() instanceof TranslatableContents t && t.getKey().equals(KEY) && t.getArgs().length >= 1
			? t.getArgument(0).getString() : null;
	}
}
