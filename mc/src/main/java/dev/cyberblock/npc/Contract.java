package dev.cyberblock.npc;

import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import java.util.Locale;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * One {@code contract} content entry: a fixer's gig (megaplan section 1.7 and task 7-N1). The file
 * {@code data/<ns>/cyberblock/contract/<name>.json}:
 * <pre>
 * {"schema": 1, "title": "...", "text": "...", "type": "deliver|defeat|reach|breach", "target": "...", "count": 3,
 *  "reward": 150, "reward_item": {"item": "minecraft:emerald", "count": 1}, "pos": [x, y, z], "radius": 8}
 * </pre>
 * {@code deliver}: bring {@code count} of the item {@code target} to any fixer. {@code defeat}: {@code count} Night City people of
 * the faction {@code target} ({@code any} for any faction) die after you struck them. {@code reach}: get within {@code radius} blocks
 * of the landmark {@code target} (a {@code landmark} content id) or of {@code pos} when given. {@code breach}: {@code count}
 * successful Breach Protocols on a device of kind {@code target} ({@code any}: any device). {@code reward} is Minecraft eddies
 * (section 1.16). Texts are original content. Pure.
 */
public record Contract(String title, String text, Type type, String target, int count, int reward, @Nullable Identifier rewardItem,
	int rewardCount, double @Nullable [] pos, double radius) {
	static final int MAX_COUNT = 64, MAX_REWARD = 100_000;

	public enum Type {
		DELIVER, DEFEAT, REACH, BREACH;

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	static Contract parse(final Identifier id, final JsonObject json) {
		String title = Content.string(json, "title", 64), text = NpcJson.string(json, "text", 256, "");
		String typeText = Content.string(json, "type", 16);
		Type type;
		try {
			type = Type.valueOf(typeText.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("type must be deliver, defeat, reach or breach");
		}

		double[] pos = json.has("pos") ? Content.position(json, "pos") : null;
		String target = type == Type.REACH && pos != null ? NpcJson.string(json, "target", 128, "") : Content.string(json, "target", 128);
		if (type == Type.DELIVER || type == Type.REACH && !target.isEmpty()) {
			NpcJson.id(target, "target");
		} else if (type != Type.REACH && target.isBlank()) {
			throw new IllegalArgumentException("target must name a faction, a device kind or any");
		}

		int count = type == Type.REACH ? 1 : NpcJson.whole(json, "count", 1, MAX_COUNT, 1);
		Identifier rewardItem = null;
		int rewardCount = 0;
		if (json.has("reward_item")) {
			JsonObject r = NpcJson.object(json.get("reward_item"), "reward_item");
			rewardItem = NpcJson.id(Content.string(r, "item", 128), "reward_item.item");
			rewardCount = NpcJson.whole(r, "count", 1, 64, 1);
		}

		return new Contract(title, text, type, target, count, NpcJson.whole(json, "reward", 0, MAX_REWARD, 0), rewardItem, rewardCount, pos,
			NpcJson.number(json, "radius", 2.0, 64.0, 8.0));
	}
}
