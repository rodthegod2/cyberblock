package dev.cyberblock.client.net;

import com.google.gson.JsonObject;

/**
 * The {@code hstate} UP message (megaplan section 1.2): what the server needs to know of V, derived from the host's {@code cam},
 * {@code state} and Steve's Flight. Sent at 5 Hz and at once on a change, never faster than 10 a second (the server's bucket).
 */
public final class HState {
	static final long PERIOD_NANOS = 200_000_000L;
	static final long MIN_GAP_NANOS = 100_000_000L;

	/** One sample; {@code feetCpZ} NaN when unknown (left out of the message). */
	public record Snapshot(boolean active, boolean vehicle, boolean air, boolean cr, boolean held, boolean fly, boolean launch,
		boolean seat, int lv, double feetCpZ) {

		public String json() {
			JsonObject m = new JsonObject();
			m.addProperty("t", "hstate");
			m.addProperty("active", this.active);
			m.addProperty("vehicle", this.vehicle);
			m.addProperty("air", this.air);
			m.addProperty("cr", this.cr);
			m.addProperty("held", this.held);
			m.addProperty("fly", this.fly);
			m.addProperty("launch", this.launch);
			m.addProperty("seat", this.seat);
			m.addProperty("lv", Math.clamp(this.lv, 0, 63));
			if (Double.isFinite(this.feetCpZ)) {
				m.addProperty("feetCpZ", this.feetCpZ);
			}

			return m.toString();
		}

		/** Same flags and level; the feet count as the same within 1 cm (they wobble every frame). */
		boolean sameAs(final Snapshot o) {
			return this.active == o.active && this.vehicle == o.vehicle && this.air == o.air && this.cr == o.cr && this.held == o.held
				&& this.fly == o.fly && this.launch == o.launch && this.seat == o.seat && this.lv == o.lv
				&& (Double.isNaN(this.feetCpZ) ? Double.isNaN(o.feetCpZ) : Math.abs(this.feetCpZ - o.feetCpZ) < 0.01);
		}
	}

	private Snapshot last;
	private long lastNanos;

	/** Whether {@code now} is due to be sent: the first, a change (at most every 100 ms) or 200 ms since the last. Remembers it when due. */
	public boolean due(final Snapshot now, final long nanos) {
		boolean due;
		if (this.last == null) {
			due = true;
		} else {
			long since = nanos - this.lastNanos;
			due = since >= PERIOD_NANOS || since >= MIN_GAP_NANOS && !now.sameAs(this.last);
		}

		if (due) {
			this.last = now;
			this.lastNanos = nanos;
		}

		return due;
	}

	/** A new connection: the next sample is sent at once. */
	public void reset() {
		this.last = null;
	}
}
