package dev.cyberblock.cyber;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * {"t":"hackresult","id":n,"ok":bool,"reason":"...","upload":s,"kind":"..."}: Cyberpunk's answer to every `hack` (plan section
 * 1.2; `upload` and `kind` from plan retest3 section 1.1).
 *
 * @param upload seconds until the hack lands in Cyberpunk (0..30, 0 = instant; 0 when absent or not a finite number)
 * @param kind what it hit (HackFxRules.KINDS), "" when absent or unknown
 */
public record HackResult(int id, boolean ok, String reason, double upload, String kind) {
	private static final int MAX_REASON = 64;

	public HackResult(final int id, final boolean ok, final String reason) {
		this(id, ok, reason, 0.0, "");
	}

	/** The answer, or null when it breaks the protocol (dropped and counted by the caller). A bad `upload` or `kind` is dropped alone. */
	public static @Nullable HackResult parse(final JsonObject m) {
		try {
			int id = m.get("id").getAsInt();
			if (id < 0 || !m.getAsJsonPrimitive("ok").isBoolean()) {
				return null;
			}

			String reason = m.has("reason") && !m.get("reason").isJsonNull() ? m.get("reason").getAsString() : "";
			if (reason.length() > MAX_REASON) {
				return null;
			}

			JsonElement u = m.get("upload");
			double upload = u != null && u.isJsonPrimitive() && u.getAsJsonPrimitive().isNumber() ? HackFxRules.clampUpload(u.getAsDouble()) : 0.0;
			JsonElement k = m.get("kind");
			String kind = k != null && k.isJsonPrimitive() && k.getAsJsonPrimitive().isString() && HackFxRules.KINDS.contains(k.getAsString()) ? k.getAsString() : "";
			return new HackResult(id, m.get("ok").getAsBoolean(), reason, upload, kind);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
