package dev.cyberblock.client.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.HostState;
import dev.cyberblock.client.PlayerSync;
import dev.cyberblock.client.settings.DebugFlags;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

/**
 * Q-V4: the view pipeline's numbers while {@code /cyberblock debug view} is on (DebugFlags.view()): an overlay at the top left in
 * a small font, and every 5 s the log line {@code view: frame <ms> pose age <ms> interp <f> dist <m> collide <m>}. Frame time and
 * pose age (cam receive to render) are Minecraft's; the camera's own distance after collision and smoothing come from cam.vdbg
 * (the DLL sends it while its debug flag is on); dist falls back to the measured camera-to-head distance when there is none.
 */
public final class ViewDebugHud {
	private static final long LOG_NANOS = 5_000_000_000L;
	private static final float SCALE = 0.75F;
	/** cam.vdbg older than this is not shown (the DLL stopped sending it). */
	private static final long VDBG_STALE_NANOS = 1_000_000_000L;

	/** cam.vdbg: collide m, smooth m, age ms, and when it arrived. Written on the link thread. */
	private static volatile double[] vdbg;
	private static volatile long vdbgNanos;

	// render thread
	private static long lastFrame;
	private static double frameMs = Double.NaN, poseAgeMs = Double.NaN, camDist = Double.NaN, steveOff = Double.NaN;
	private static double sumFrame, sumAge, maxFrame;
	private static int frames;
	private static long nextLog = System.nanoTime() + LOG_NANOS;

	private ViewDebugHud() {
	}

	/** Link thread, after HostState took the cam: its vdbg [collide m, smooth m, age ms], if valid (protocol.md "Phase Q"). */
	static void cam(final JsonObject m) {
		double[] v = parseVdbg(m);
		if (v != null) {
			vdbg = v;
			vdbgNanos = System.nanoTime();
		}
	}

	/** cam.vdbg as {collide, smooth, age}, or null when absent or bad (collide and smooth 0..100 m, age 0..60000 ms). */
	static double[] parseVdbg(final JsonObject m) {
		if (!(m.get("vdbg") instanceof JsonArray a) || a.size() != 3) {
			return null;
		}

		double[] v = new double[3];
		double[] max = {100.0, 100.0, 60000.0};
		for (int i = 0; i < 3; i++) {
			try {
				v[i] = a.get(i).getAsDouble();
			} catch (RuntimeException e) {
				return null;
			}

			if (!(v[i] >= 0.0 && v[i] <= max[i])) {
				return null;
			}
		}

		return v;
	}

	/** Every rendered frame, after the frame's pose was taken and Steve placed (CameraMixin). */
	public static void frame() {
		long now = System.nanoTime();
		frameMs = lastFrame == 0L ? Double.NaN : (now - lastFrame) / 1.0E6;
		lastFrame = now;
		HostState.Pose p = HostState.frame();
		LocalPlayer player = Minecraft.getInstance().player;
		if (p == null || player == null) {
			poseAgeMs = camDist = steveOff = Double.NaN;
			return;
		}

		poseAgeMs = (now - p.receivedNanos()) / 1.0E6;
		double eyeY = p.py() + player.getEyeHeight();
		camDist = Math.sqrt(sq(p.x() - p.px()) + sq(p.y() - eyeY) + sq(p.z() - p.pz()));
		double[] feet = PlayerSync.renderFeet();
		steveOff = feet == null ? Double.NaN : Math.sqrt(sq(feet[0] - p.px()) + sq(feet[1] - p.py()) + sq(feet[2] - p.pz()));
		if (Double.isFinite(frameMs)) {
			sumFrame += frameMs;
			maxFrame = Math.max(maxFrame, frameMs);
			sumAge += poseAgeMs;
			frames++;
		}
	}

	private static double sq(final double v) {
		return v * v;
	}

	/** cam.vdbg if it is fresh, else null. */
	private static double[] liveVdbg() {
		double[] v = vdbg;
		return v != null && System.nanoTime() - vdbgNanos < VDBG_STALE_NANOS ? v : null;
	}

	/** Client tick: the 5 s log line while the flag is on (averages over the 5 s). */
	static void tick() {
		long now = System.nanoTime();
		if (now - nextLog < 0L) {
			return;
		}

		nextLog = now + LOG_NANOS;
		if (DebugFlags.view() && frames > 0) {
			double[] v = liveVdbg();
			Cyberblock.LOG.info(logLine(sumFrame / frames, maxFrame, sumAge / frames, PlayerSync.renderFactor(), v != null ? v[1] : camDist, v != null ? v[0] : Double.NaN));
		}

		sumFrame = sumAge = maxFrame = 0.0;
		frames = 0;
	}

	/** {@code view: frame <ms> pose age <ms> interp <f> dist <m> collide <m>} (section 1.14), plus the worst frame of the window. */
	static String logLine(final double frameMs, final double maxFrameMs, final double poseAgeMs, final double interp, final double dist, final double collide) {
		return String.format(Locale.ROOT, "view: frame %.1f pose age %.1f interp %.2f dist %.2f collide %s (worst frame %.1f)",
			frameMs, poseAgeMs, interp, dist, Double.isNaN(collide) ? "n/a" : String.format(Locale.ROOT, "%.2f", collide), maxFrameMs);
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		if (!DebugFlags.view()) {
			return;
		}

		HostState.Pose p = HostState.frame();
		List<String> lines = new ArrayList<>();
		lines.add(String.format(Locale.ROOT, "frame %.1f ms", frameMs));
		if (p == null) {
			lines.add("no host pose");
		} else {
			lines.add(String.format(Locale.ROOT, "pose age %.1f ms  interp %.2f", poseAgeMs, PlayerSync.renderFactor()));
			lines.add((p.firstPerson() ? "first person" : "third person") + String.format(Locale.ROOT, "  cam dist %.2f m", camDist));
			lines.add(String.format(Locale.ROOT, "steve-V %.2f m", steveOff));
			double[] v = liveVdbg();
			lines.add(v == null ? "vdbg: none" : String.format(Locale.ROOT, "collide %.2f m  smooth %.2f m  age %.0f ms", v[0], v[1], v[2]));
		}

		Font font = Minecraft.getInstance().font;
		int width = 0;
		for (String line : lines) {
			width = Math.max(width, font.width(line));
		}

		graphics.nextStratum();
		graphics.pose().pushMatrix();
		graphics.pose().translate(2.0F, 2.0F);
		graphics.pose().scale(SCALE, SCALE);
		graphics.fill(0, 0, width + 4, lines.size() * 10 + 2, 0x90000000);
		for (int i = 0; i < lines.size(); i++) {
			graphics.text(font, lines.get(i), 2, 2 + i * 10, 0xFFE0E0E0, false);
		}

		graphics.pose().popMatrix();
	}
}
