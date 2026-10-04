package dev.cyberblock.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * One {@code contract} content entry: a fixer's gig (megaplan section 1.7 and task 7-N1; 7A's
 * {@code shared/content-schemas/contract.schema.json}):
 * <pre>
 * {"schema": 1, "title": "...", "text": "...", "done_text": "...", "type": "defeat_hostiles", "target": {"faction": "TygerClaws", "count": 6},
 *  "reward": {"eddies": 260, "items": [{"item": "cyberblock:ram_shard", "count": 1}]}, "difficulty": 2, "time_limit": 0, "repeatable": true}
 * </pre>
 * {@code deliver_item} {item, count}: hand the items to a fixer. {@code defeat_hostiles} {faction, count}: Night City people of that
 * faction die after the player struck them. {@code reach_landmark} {landmark}: stand within {@link #REACH} blocks of the
 * {@code landmark}. {@code breach_device} {kind, count}: Phase 6 Breach Protocols solved on devices of that section 1.11 kind. The
 * reward is Minecraft eddies (section 1.16) and up to 4 item stacks; {@code time_limit} seconds (0: none); a gig that is not
 * {@code repeatable} is offered to a player only until he has done it. Texts are original content. Pure.
 */
public record Contract(String title, String text, String doneText, Type type, @Nullable Identifier item, int count, String faction,
	@Nullable Identifier landmark, String kind, int eddies, List<Reward> items, int difficulty, int timeLimit, boolean repeatable) {
	/** Blocks from a reach_landmark gig's landmark that count as there. */
	public static final double REACH = 6.0;
	static final List<String> KINDS = List.of("door", "gate", "light", "camera", "terminal", "vending", "traffic", "elevator", "turret", "screen",
		"container");

	public enum Type {
		DELIVER_ITEM, DEFEAT_HOSTILES, REACH_LANDMARK, BREACH_DEVICE;

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	public record Reward(Identifier item, int count) {
	}

	static Contract parse(final Identifier id, final JsonObject json) {
		String title = Content.string(json, "title", 48), text = Content.string(json, "text", 280);
		String doneText = NpcJson.string(json, "done_text", 200, "");
		Type type;
		try {
			type = Type.valueOf(Content.string(json, "type", 24).toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("type must be deliver_item, defeat_hostiles, reach_landmark or breach_device");
		}

		JsonObject target = NpcJson.object(json.get("target"), "target");
		Identifier item = null, landmark = null;
		String faction = "", kind = "";
		int count = 1;
		switch (type) {
			case DELIVER_ITEM -> {
				item = NpcJson.id(Content.string(target, "item", 128), "target.item");
				count = NpcJson.whole(target, "count", 1, 512, 1);
			}
			case DEFEAT_HOSTILES -> {
				faction = Content.string(target, "faction", 48);
				if (!faction.matches("[A-Za-z0-9_]+")) {
					throw new IllegalArgumentException("target.faction must be a faction name like TygerClaws");
				}

				count = NpcJson.whole(target, "count", 1, 512, 1);
			}
			case REACH_LANDMARK -> landmark = NpcJson.id(Content.string(target, "landmark", 128), "target.landmark");
			case BREACH_DEVICE -> {
				kind = Content.string(target, "kind", 16);
				if (!KINDS.contains(kind)) {
					throw new IllegalArgumentException("target.kind must be one of " + KINDS);
				}

				count = NpcJson.whole(target, "count", 1, 512, 1);
			}
		}

		JsonObject reward = NpcJson.object(json.get("reward"), "reward");
		List<Reward> items = new ArrayList<>();
		for (JsonElement e : NpcJson.array(reward, "items", 4)) {
			JsonObject r = NpcJson.object(e, "a reward item");
			items.add(new Reward(NpcJson.id(Content.string(r, "item", 128), "reward item"), NpcJson.whole(r, "count", 1, 64, 1)));
		}

		boolean repeatable = json.has("repeatable") && json.get("repeatable").isJsonPrimitive() && json.get("repeatable").getAsBoolean();
		return new Contract(title, text, doneText, type, item, count, faction, landmark, kind, NpcJson.whole(reward, "eddies", 1, 99_999, 1),
			List.copyOf(items), NpcJson.whole(json, "difficulty", 1, 3, 1), NpcJson.whole(json, "time_limit", 0, 86_400, 0), repeatable);
	}
}
