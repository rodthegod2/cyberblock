package dev.cyberblock.npc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * One player at one role NPC's screen (megaplan task 7-N1): who, which NPC and role, what it sells, the tab shown, the NPC's last
 * line and the result of the last press. Server thread.
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
	/** The NPC's own name ("" for a Night City figure: the role's title shows). */
	final String name;
	/** Who sells here, for stock limits: the hub figure, or the Night City figure's entity. */
	final String key;
	/** The shop this NPC sells from (null: none). */
	final @Nullable Identifier shop;
	String tab = "";
	String line = "";
	String result = "";

	Visit(final ServerPlayer player, final Entity npc, final Identifier roleId, final NpcRole role, final String name, final String key,
		final @Nullable Identifier shop) {
		this.player = player;
		this.npc = npc;
		this.roleId = roleId;
		this.role = role;
		this.name = name;
		this.key = key;
		this.shop = shop;
	}

	/** The NPC says a line for this context (placeholders filled, {player} always); with none in content, the last line stays. */
	void say(final String context, final String... pairs) {
		Map<String, String> values = new HashMap<>();
		values.put("player", this.player.getPlainTextName());
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			values.put(pairs[i], pairs[i + 1]);
		}

		String line = Dialogue.pick(NpcContent.dialogue(), this.roleId, this.role.dialogue(), context, values, ThreadLocalRandom.current());
		if (!line.isEmpty() || context.equals("greeting")) {
			this.line = line;
		}
	}
}
