package dev.cyberblock.mode.pvphack;

import com.google.gson.JsonObject;
import dev.cyberblock.cyber.Hack;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The messages of player hacks (megaplan 8-X1). {@code cyberblock:ui t:"pvphack"} to one client, one JSON object with {@code k}:
 * <ul>
 * <li>{@code intrusion} {from, hack, ms, breach}: a hack is uploading into you (the warning; {@code breach}: the short Breach opened)</li>
 * <li>{@code end} {why ∈ blocked, firewall, landed, gone}: the intrusion is over (the client closes a Breach screen still open)</li>
 * <li>{@code glitch} {ms}: Reboot Optics landed on you (the glitch overlay)</li>
 * <li>{@code ping} {eid, ms}: outline this player for your team</li>
 * </ul>
 * And the {@code glitch} DOWN of section 1.9 for a Cyberpunk-linked victim: {@code {"t":"glitch","seconds":0..10}}.
 */
public final class PvpHackWire {
	public static final String UI = "pvphack";
	/** Longer than any effect (and any upload) this package sends. */
	static final int MAX_MS = 30_000;
	private static final int MAX_NAME = 32;
	private static final int MAX_WHY = 16;

	public enum Kind {
		INTRUSION,
		END,
		GLITCH,
		PING;

		String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	/** A parsed message; fields a kind does not use are "", null, 0, -1 or false. */
	public record Msg(Kind kind, String from, @Nullable Hack hack, int ms, int eid, boolean breach, String why) {
	}

	private PvpHackWire() {
	}

	public static String intrusion(final String from, final Hack hack, final int ms, final boolean breach) {
		JsonObject m = head(Kind.INTRUSION);
		m.addProperty("from", from);
		m.addProperty("hack", hack.wire());
		m.addProperty("ms", ms);
		m.addProperty("breach", breach);
		return m.toString();
	}

	public static String end(final String why) {
		JsonObject m = head(Kind.END);
		m.addProperty("why", why);
		return m.toString();
	}

	public static String glitch(final int ms) {
		JsonObject m = head(Kind.GLITCH);
		m.addProperty("ms", ms);
		return m.toString();
	}

	public static String ping(final int entityId, final int ms) {
		JsonObject m = head(Kind.PING);
		m.addProperty("eid", entityId);
		m.addProperty("ms", ms);
		return m.toString();
	}

	/** The section 1.9 {@code glitch} DOWN: Reboot Optics on a linked victim, whole seconds clamped to 0..10. */
	public static String glitchDown(final int seconds) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "glitch");
		m.addProperty("seconds", Math.clamp(seconds, 0, 10));
		return m.toString();
	}

	private static JsonObject head(final Kind kind) {
		JsonObject m = new JsonObject();
		m.addProperty("k", kind.id());
		return m;
	}

	/** The message, or null when it breaks these rules (the client drops it). */
	public static @Nullable Msg parse(final JsonObject m) {
		try {
			String k = m.get("k").getAsString();
			for (Kind kind : Kind.values()) {
				if (kind.id().equals(k)) {
					return parse(kind, m);
				}
			}

			return null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static @Nullable Msg parse(final Kind kind, final JsonObject m) {
		if (kind == Kind.END) {
			String why = m.get("why").getAsString();
			return why.length() <= MAX_WHY ? new Msg(kind, "", null, 0, -1, false, why) : null;
		}

		int ms = m.get("ms").getAsInt();
		if (ms < 0 || ms > MAX_MS) {
			return null;
		}

		return switch (kind) {
			case GLITCH -> new Msg(kind, "", null, ms, -1, false, "");
			case PING -> new Msg(kind, "", null, ms, m.get("eid").getAsInt(), false, "");
			default -> {
				String from = m.get("from").getAsString();
				Hack hack = Hack.byWire(m.get("hack").getAsString());
				yield from.length() <= MAX_NAME && hack != null ? new Msg(kind, from, hack, ms, -1, m.get("breach").getAsBoolean(), "") : null;
			}
		};
	}
}
