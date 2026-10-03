package dev.cyberblock.cyber;

import com.google.gson.JsonObject;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {"t":"hackspread","id":n,"target":"<id>","why":"chain|spread|ping|frenzy"}: one more NPC a hack touched (plan retest3
 * section 1.1). `chain`/`spread` come before that id's `hackresult`, `ping`/`frenzy` may come after it.
 */
public record HackSpread(int id, long target, String why) {
	public static final Set<String> WHY = Set.of("chain", "spread", "ping", "frenzy");

	/** The spread, or null when it breaks the protocol (an unknown `why` makes it invalid): the caller drops and counts it. */
	public static @Nullable HackSpread parse(final JsonObject m) {
		try {
			int id = m.get("id").getAsInt();
			long target = ScanInfo.entityId(m.get("target"));
			String why = m.get("why").getAsString();
			return id >= 0 && target != 0L && WHY.contains(why) ? new HackSpread(id, target, why) : null;
		} catch (RuntimeException e) {
			return null;
		}
	}
}
