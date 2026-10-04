package dev.cyberblock.npc;

import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;

/**
 * One {@code dialogue} content entry (megaplan section 1.7: original lines per NPC role; task 7-N1). The file
 * {@code data/<ns>/cyberblock/dialogue/<name>.json}:
 * <pre>
 * {"schema": 1, "role": "bartender", "lines": {"greeting": ["..."], "greeting.hub": ["..."], "rumour": ["..."], "farewell": ["..."]}}
 * </pre>
 * {@code role} is a screen name of {@link NpcRole#SCREENS} or {@code any} (lines every role may use). A context is
 * {@code <word>} or {@code <word>.<word>} (a more specific variant); a lookup for {@code greeting.hub} falls back to {@code greeting}
 * when no file has the variant. Several files for one role add up (a datapack adds lines beside the defaults). Contexts the screens
 * ask for: {@code greeting} (with {@code .hub} in the Afterlife), {@code farewell}, {@code thanks} (after a deal), {@code poor} (not
 * enough eddies), {@code rumour}, {@code gig_taken}, {@code gig_done}, {@code gig_busy}, {@code surgery_ok}, {@code surgery_fail},
 * {@code heal}, {@code repair}. Every line shown is from content: with none, the screen shows no line. Pure.
 */
public record Dialogue(String role, Map<String, List<String>> lines) {
	static final int MAX_CONTEXTS = 32, MAX_LINES = 64, LINE_BYTES = 256;
	private static final Pattern CONTEXT = Pattern.compile("[a-z_]{1,24}(\\.[a-z_]{1,24})?");

	static Dialogue parse(final Identifier id, final JsonObject json) {
		String role = Content.string(json, "role", 16);
		if (!role.equals("any") && !NpcRole.SCREENS.contains(role)) {
			throw new IllegalArgumentException("role must be any or one of " + NpcRole.SCREENS);
		}

		if (!(json.get("lines") instanceof JsonObject lines) || lines.size() > MAX_CONTEXTS) {
			throw new IllegalArgumentException("lines must be an object of at most " + MAX_CONTEXTS + " contexts");
		}

		Map<String, List<String>> out = new LinkedHashMap<>();
		for (String context : lines.keySet()) {
			if (!CONTEXT.matcher(context).matches()) {
				throw new IllegalArgumentException("context " + context + " must be <word> or <word>.<word> (a-z and _)");
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

	/**
	 * Every line for this role (and role "any") in this context, falling back from {@code word.variant} to {@code word} when nothing
	 * has the variant.
	 */
	static List<String> lines(final Collection<Dialogue> all, final String role, final String context) {
		for (String c = context; ; c = c.substring(0, c.lastIndexOf('.'))) {
			List<String> out = new ArrayList<>();
			for (Dialogue d : all) {
				if (d.role.equals(role) || d.role.equals("any")) {
					out.addAll(d.lines.getOrDefault(c, List.of()));
				}
			}

			if (!out.isEmpty() || c.indexOf('.') < 0) {
				return out;
			}
		}
	}

	/** One line for this role and context, "" when content has none. */
	static String pick(final Collection<Dialogue> all, final String role, final String context, final RandomGenerator random) {
		List<String> lines = lines(all, role, context);
		return lines.isEmpty() ? "" : lines.get(random.nextInt(lines.size()));
	}
}
