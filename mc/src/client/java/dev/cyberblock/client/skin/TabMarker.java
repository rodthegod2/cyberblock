package dev.cyberblock.client.skin;

import dev.cyberblock.client.net.Roster;
import dev.cyberblock.skin.SkinRules;
import java.util.UUID;
import net.minecraft.network.chat.Component;

/**
 * Megaplan 5-K1: the player list marks every linked player with a V-coloured diamond after the name (PlayerTabOverlayMixin), from
 * the roster, so the mark shows even when the player is in another scoreboard team and has no [V] prefix.
 */
public final class TabMarker {
	static final Component MARKER = Component.literal(" \u25C6").withColor(SkinRules.V_COLOUR);

	private TabMarker() {
	}

	/** The name as the player list shows it, with the marker when the roster flags the player as linked. */
	public static Component decorate(final Component name, final UUID uuid) {
		return decorate(name, Roster.linked(uuid));
	}

	static Component decorate(final Component name, final boolean linked) {
		return linked ? name.copy().append(MARKER) : name;
	}
}
