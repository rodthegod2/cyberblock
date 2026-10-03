package dev.cyberblock.client.cyber;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Cyberware;
import dev.cyberblock.cyber.CyberwareRules;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * The Sandevistan's Minecraft flourish (plan retest3 C7, F10). Always: a HUD element with lime corner brackets, the word
 * SANDEVISTAN and a countdown bar. Only while not hosted (standalone Minecraft): the cyberblock:sandevistan post effect
 * (GameRendererCyberMixin requests it), since hosted the ReShade effect grades the whole composited frame. In third person:
 * afterimages, three translucent copies from a 2-tick position history (drawn by ArmCyberwareLayer through CyberLook).
 */
public final class SandevistanFx {
	static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "sandevistan");
	public static final Identifier POST_EFFECT = Identifier.fromNamespaceAndPath(Cyberblock.ID, "sandevistan");
	private static final int LIME = 0xFF9DFF3A;
	private static final int TEAL = 0xFF33E6C8;
	private static final int BRACKET = 28;
	private static final int THICK = 2;
	private static final int BAR_WIDTH = 90;
	/** Afterimages: this many copies, each this many ticks further back. */
	static final int GHOSTS = 3;
	static final int GHOST_STEP = 2;
	private static final int HISTORY = GHOSTS * GHOST_STEP + 1;
	/** The local player's positions, newest first (client thread). */
	private static final List<Vec3> history = new ArrayList<>();
	/** The post effect was requested last frame (render thread). */
	private static boolean postOn;

	private SandevistanFx() {
	}

	/** The local player's Sandevistan runs now (its synced timer). */
	static boolean running(final LocalPlayer player) {
		return CyberData.timer(player, Cyberware.SANDEVISTAN).active(player.level().getGameTime());
	}

	/** GameRendererCyberMixin: request the post effect this frame (standalone only). Logged when it starts and stops. */
	public static boolean wantsPostEffect() {
		LocalPlayer player = Minecraft.getInstance().player;
		boolean wants = !Cyberblock.active && player != null && running(player);
		if (wants != postOn) {
			postOn = wants;
			Cyberblock.LOG.info("sandevistan: post effect {}", wants ? "on" : "off");
		}

		return wants;
	}

	/** Every client tick: the position history (cleared when the Sandevistan is not running). */
	static void tick(final Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || !running(player)) {
			history.clear();
			return;
		}

		history.addFirst(player.position());
		while (history.size() > HISTORY) {
			history.removeLast();
		}
	}

	/** World offsets of the afterimages from `now` (the interpolated position), oldest last; fewer while the history fills. */
	static List<Vec3> ghostOffsets(final Vec3 now) {
		List<Vec3> out = new ArrayList<>();
		for (int k = 1; k <= GHOSTS; k++) {
			int at = k * GHOST_STEP;
			if (at < history.size()) {
				Vec3 d = history.get(at).subtract(now);
				if (d.lengthSqr() > 0.01) {
					out.add(d);
				}
			}
		}

		return out;
	}

	/** HUD element: brackets in the four corners, the word and the time left. */
	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}

		CyberwareRules.Timer timer = CyberData.timer(player, Cyberware.SANDEVISTAN);
		long now = player.level().getGameTime();
		if (!timer.active(now)) {
			return;
		}

		int w = graphics.guiWidth(), h = graphics.guiHeight();
		int m = 6;
		corner(graphics, m, m, 1, 1);
		corner(graphics, w - m, m, -1, 1);
		corner(graphics, m, h - m, 1, -1);
		corner(graphics, w - m, h - m, -1, -1);
		Component word = Component.translatableWithFallback("cyberblock.sandevistan.hud", "SANDEVISTAN");
		int y = 34;
		graphics.centeredText(minecraft.font, word, w / 2, y, LIME);
		int x0 = w / 2 - BAR_WIDTH / 2;
		graphics.fill(x0 - 1, y + 11, x0 + BAR_WIDTH + 1, y + 15, 0xC0101810);
		graphics.fill(x0, y + 12, x0 + Math.round(BAR_WIDTH * timer.activeFraction(now)), y + 14, TEAL);
	}

	/** One L-shaped bracket with its corner at (x, y), arms running along +dx and +dy. */
	private static void corner(final GuiGraphicsExtractor graphics, final int x, final int y, final int dx, final int dy) {
		int x1 = x + dx * BRACKET;
		int y1 = y + dy * THICK;
		graphics.fill(Math.min(x, x1), Math.min(y, y1), Math.max(x, x1), Math.max(y, y1), LIME);
		int x2 = x + dx * THICK;
		int y2 = y + dy * BRACKET;
		graphics.fill(Math.min(x, x2), Math.min(y, y2), Math.max(x, x2), Math.max(y, y2), LIME);
	}
}
