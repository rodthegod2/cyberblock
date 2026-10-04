package dev.cyberblock.npc;

import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;

/**
 * One {@code dialogue} content entry (megaplan section 1.7 and task 7-N1; 7A's {@code shared/content-schemas/dialogue.schema.json}):
 * the original lines of one role, {@code {"schema": 1, "role": "cyberblock:bartender", "lines": {"greeting": ["..."], ...}}}. A role
 * speaks the lines of its {@code npc_role}'s {@code dialogue} file and of any file whose {@code role} is that role (a datapack adds
 * lines beside the defaults). The screens ask for {@code greeting}, {@code rumour}, {@code purchase}, {@code sale}, {@code no_funds},
 * {@code gig_offer}, {@code gig_done}, {@code drink}, {@code healed}, {@code repaired}, {@code installed}, {@code surgery_ok} and
 * {@code surgery_fail}; a line's placeholders {player}, {item}, {price}, {title}, {reward}, {implant} and {drink} are filled in. With
 * no line for a context the NPC says nothing. Pure.
 */
public record Dialogue(Identifier role, Map<String, List<String>> lines) {
	static final int MAX_CONTEXTS = 32, MAX_LINES = 32, LINE_BYTES = 200;
	private static final Pattern CONTEXT = Pattern.compile("[a-z_]{1,24}");

	static Dialogue parse(final Identifier id, final JsonObject json) {
		Identifier role = NpcJson.id(Content.string(json, "role", 128), "role");
		if (!(json.get("lines") instanceof JsonObject lines) || lines.size() > MAX_CONTEXTS) {
			throw new IllegalArgumentException("lines must be an object of at most " + MAX_CONTEXTS + " contexts");
		}

		Map<String, List<String>> out = new java.util.LinkedHashMap<>();
		for (String context : lines.keySet()) {
			if (!CONTEXT.matcher(context).matches()) {
				throw new IllegalArgumentException("context " + context + " must be a-z and _");
			}

			JsonObject wrap = new JsonObject();
			wrap.add("lines", lines.get(context));
			List<String> list = NpcJson.strings(wrap, "lines", MAX_LINES, LINE_BYTES);
			if (list.isEmpty()) {
				throw new IllegalArgumentException("context " + context + " has no lines");
			}

			out.put(context, list);
		}

		return new Dialogue(role, Map.copyOf(out));
	}

	/** Every line of this role in this context: from its own dialogue file and from files naming the role. */
	static List<String> lines(final Map<Identifier, Dialogue> all, final Identifier roleId, final Identifier file, final String context) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<Identifier, Dialogue> e : all.entrySet()) {
			if (e.getKey().equals(file) || e.getValue().role.equals(roleId)) {
				out.addAll(e.getValue().lines.getOrDefault(context, List.of()));
			}
		}

		return out;
	}

	/** One line with its placeholders filled ("" when content has none). */
	static String pick(final Map<Identifier, Dialogue> all, final Identifier roleId, final Identifier file, final String context,
		final Map<String, String> values, final RandomGenerator random) {
		List<String> lines = lines(all, roleId, file, context);
		return lines.isEmpty() ? "" : fill(lines.get(random.nextInt(lines.size())), values);
	}

	/** {name} placeholders replaced by their values; unknown ones stay as written. */
	static String fill(final String line, final Map<String, String> values) {
		String out = line;
		for (Map.Entry<String, String> e : values.entrySet()) {
			out = out.replace("{" + e.getKey() + "}", e.getValue());
		}

		return out;
	}
}
