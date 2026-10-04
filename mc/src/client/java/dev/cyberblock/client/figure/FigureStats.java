package dev.cyberblock.client.figure;

import dev.cyberblock.Cyberblock;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Megaplan 9-F1: how many Night City figures this client draws and animates, and its frame rate, logged every LOG_SECONDS while any
 * figure is near (the renderers asked about it). Counted per second: a figure counts once per second however many frames drew it.
 * Client thread only.
 */
final class FigureStats {
	static final int LOG_SECONDS = 10;
	private static final Window window = new Window();
	private static int ticks;

	private FigureStats() {
	}

	/** A renderer was asked about this figure (it is loaded and near); `drawn`: it is drawn this frame. */
	static void considered(final int entityId, final boolean drawn) {
		window.near.add(entityId);
		if (drawn) {
			window.drawn.add(entityId);
		}
	}

	/** This figure is drawn with its animation this frame (stride, poses, ragdoll). */
	static void animated(final int entityId) {
		window.animated.add(entityId);
	}

	/** END_CLIENT_TICK: one sample a second, one log line every LOG_SECONDS samples. */
	static void tick(final Minecraft minecraft) {
		if (++ticks % 20 != 0) {
			return;
		}

		window.sample(minecraft.getFps());
		if (window.samples >= LOG_SECONDS) {
			String line = window.line(0);
			if (line != null) {
				Cyberblock.LOG.info(line);
			}
		}
	}

	/** The counts of one log window. Pure. */
	static final class Window {
		final Set<Integer> near = new HashSet<>(), drawn = new HashSet<>(), animated = new HashSet<>();
		int samples, drawnMax;
		long nearSum, drawnSum, animatedSum, fpsSum;

		/** The second is over: its counts join the window's. */
		void sample(final int fps) {
			this.samples++;
			this.nearSum += this.near.size();
			this.drawnSum += this.drawn.size();
			this.animatedSum += this.animated.size();
			this.drawnMax = Math.max(this.drawnMax, this.drawn.size());
			this.fpsSum += fps;
			this.near.clear();
			this.drawn.clear();
			this.animated.clear();
		}

		/** The window's line (averages per second; cap 0: none), null when no figure was near; the window starts over either way. */
		@Nullable String line(final int cap) {
			String line = this.nearSum == 0L || this.samples == 0 ? null : String.format(Locale.ROOT,
				"figures: %.1f drawn of %.1f near (max %d, cap %s), %.1f animated; client %.0f fps", this.drawnSum / (double) this.samples,
				this.nearSum / (double) this.samples, this.drawnMax, cap > 0 ? Integer.toString(cap) : "none", this.animatedSum / (double) this.samples,
				this.fpsSum / (double) this.samples);
			this.samples = this.drawnMax = 0;
			this.nearSum = this.drawnSum = this.animatedSum = this.fpsSum = 0L;
			return line;
		}
	}
}
