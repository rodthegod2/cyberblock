package dev.cyberblock.hack.breach;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cyberblock.device.Device;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a finished breach does in the owner's game (megaplan 6-B1; section 1.9 {@code daemon} DOWN {@code {id, dev, list, strength}},
 * protocol.md "Phase 6"), pure. Uploaded daemons go as their ids (the content's action: {@code open_door}, {@code disable_cameras},
 * {@code reveal_enemies}, {@code overload}, ...) with the strength of {@link BreachRules#strength}. A failure (or a timeout with nothing
 * uploaded) goes as exactly {@code ["alarm"]}: the scripts raise the alarm of the device's area (cameras alert) and, until Phase 8's
 * Heat meter, the host's wanted level +1 where Phase 0 (e) allows it. A breach aborted before its first pick sends nothing.
 */
public final class BreachResults {
	/** The reserved daemon id of a failed breach (protocol.md), always alone. */
	public static final String ALARM = "alarm";
	/** Server Heat a failed breach adds (Phase 8 meter, through {@link Breach#heat}). */
	public static final int FAILURE_HEAT = 5;

	/** How one breach ended, for whoever opened it. */
	public record Outcome(BreachGame.End end, boolean cancelled, boolean failed, List<BreachPuzzle.Daemon> uploaded, int strength) {
		public static Outcome of(final BreachGame g) {
			return new Outcome(g.end(), g.cancelled(), g.failed(), g.uploadedDaemons(), g.strength());
		}

		public boolean success() {
			return !this.uploaded.isEmpty();
		}

		/** The uploaded daemons' ids, in the puzzle's order. */
		public List<String> ids() {
			return this.uploaded.stream().map(BreachPuzzle.Daemon::id).toList();
		}

		/** The breach screen's end state (protocol.md "Breach states"): solved, aborted, timeout or failed. */
		public String state() {
			return this.success() ? "solved" : this.cancelled ? "aborted" : this.end == BreachGame.End.TIMEOUT ? "timeout" : "failed";
		}
	}

	private BreachResults() {
	}

	/** The {@code daemon} DOWN for this outcome on device d (its decimal id), or null when the breach was aborted before a pick. */
	public static @Nullable String daemonDown(final int request, final Device d, final Outcome o) {
		if (o.cancelled()) {
			return null;
		}

		JsonObject m = new JsonObject();
		m.addProperty("t", "daemon");
		m.addProperty("id", request);
		m.addProperty("dev", d.idText());
		JsonArray list = new JsonArray();
		(o.failed() ? List.of(ALARM) : o.ids()).forEach(list::add);
		m.add("list", list);
		m.addProperty("strength", o.failed() ? 1 : o.strength());
		return m.toString();
	}
}
