package dev.cyberblock.npc;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * One player at one role NPC's screen (megaplan task 7-N1): who, which NPC and role, the tab shown, the NPC's last line and the
 * result of the last press. Server thread.
 */
final class Visit {
	/** What each role's screen does: its tabs, the rows of the tab shown, and a press on a row (the result code). */
	interface Service {
		List<String> tabs(Visit v);

		List<NpcWire.Row> rows(Visit v);

		String press(Visit v, String row);
	}

	final ServerPlayer player;
	final Entity npc;
	final Identifier roleId;
	final NpcRole role;
	/** A hub NPC (dialogue variants {@code <context>.hub}). */
	final boolean hub;
	/** Who sells here, for stock limits: the hub spot, or the figure's entity. */
	final String key;
	String tab = "";
	String line = "";
	String result = "";

	Visit(final ServerPlayer player, final Entity npc, final Identifier roleId, final NpcRole role, final boolean hub, final String key) {
		this.player = player;
		this.npc = npc;
		this.roleId = roleId;
		this.role = role;
		this.hub = hub;
		this.key = key;
	}

	/** The NPC says a line for this context (its hub variant first in the hub); nothing when content has none. */
	void say(final String context) {
		String line = Dialogue.pick(NpcContent.dialogue().values(), this.role.screen(), this.hub ? context + ".hub" : context,
			ThreadLocalRandom.current());
		if (!line.isEmpty() || context.equals("greeting")) {
			this.line = line;
		}
	}

	/** The name on the screen: a hub NPC's own name, else the role's. */
	String name() {
		return this.npc instanceof HubNpc h && !h.npcName().isEmpty() ? h.npcName() : this.role.name();
	}
}
