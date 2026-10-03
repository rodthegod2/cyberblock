package dev.cyberblock.cyber;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/** {"t":"hackresult","id":n,"ok":bool,"reason":"..."}: Cyberpunk's answer to every `hack` (plan section 1.2). */
public record HackResult(int id, boolean ok, String reason) {
	private static final int MAX_REASON = 64;

	/** The answer, or null when it breaks the protocol (dropped and counted by the caller). */
	public static @Nullable HackResult parse(final JsonObject m) {
		try {
			int id = m.get("id").getAsInt();
			if (id < 0 || !m.getAsJsonPrimitive("ok").isBoolean()) {
				return null;
			}

			String reason = m.has("reason") && !m.get("reason").isJsonNull() ? m.get("reason").getAsString() : "";
			return reason.length() <= MAX_REASON ? new HackResult(id, m.get("ok").getAsBoolean(), reason) : null;
		} catch (RuntimeException e) {
			return null;
		}
	}
}
