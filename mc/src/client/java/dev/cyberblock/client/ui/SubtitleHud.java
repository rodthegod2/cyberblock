package dev.cyberblock.client.ui;

import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Dialogue subtitles (section 1.11): above the hotbar (its bottom at height - 72), the speaker in aqua and the line in white on a
 * dark backdrop like vanilla's subtitles, centred and wrapped. A line goes when Cyberpunk hides it, or fades out after its
 * {@code secs} (0 = until hidden, at most 30 s).
 */
final class SubtitleHud {
	static final int BOTTOM_FROM_EDGE = 72;
	private static final int LINE = 10;
	private static final int MAX_WIDTH = 320;
	private static final double FADE_SECONDS = 0.5;
	private static final double LONGEST = 30.0;

	private SubtitleHud() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		List<FormattedCharSequence> lines = lines(graphics.guiWidth());
		UiModel.Subtitle sub = UiModel.subtitle;
		if (lines == null || sub == null || UiHud.player() == null) {
			return;
		}

		float alpha = alpha(sub);
		Font font = Minecraft.getInstance().font;
		int width = lines.stream().mapToInt(font::width).max().orElse(0);
		int bottom = graphics.guiHeight() - BOTTOM_FROM_EDGE;
		int top = bottom - lines.size() * LINE;
		int left = (graphics.guiWidth() - width) / 2;
		graphics.fill(left - 3, top - 2, left + width + 3, bottom + 1, McTheme.fade(0xA0000000, alpha));
		int y = top;
		for (FormattedCharSequence line : lines) {
			graphics.text(font, line, (graphics.guiWidth() - font.width(line)) / 2, y, McTheme.fade(McTheme.WHITE, alpha), true);
			y += LINE;
		}
	}

	/** The subtitle's height in GUI pixels with its backdrop (0 when none shows): ChoicesHud stacks above it. */
	static int height(final int guiWidth) {
		List<FormattedCharSequence> lines = lines(guiWidth);
		return lines == null ? 0 : lines.size() * LINE + 3;
	}

	/** The wrapped "Speaker: line", or null when no subtitle shows now. */
	private static @Nullable List<FormattedCharSequence> lines(final int guiWidth) {
		UiModel.Subtitle sub = UiModel.subtitle;
		if (sub == null || !sub.show() || sub.text().isEmpty() || alpha(sub) <= 0.0F) {
			return null;
		}

		Component text = sub.who().isEmpty()
			? Component.literal(sub.text())
			: Component.empty().append(Component.literal(sub.who() + ": ").withColor(McTheme.AQUA & 0xFFFFFF)).append(Component.literal(sub.text()));
		return Minecraft.getInstance().font.split(text, Math.min(MAX_WIDTH, guiWidth - 40));
	}

	/** 1 while shown, fading to 0 over the last half second of {@code secs}. */
	private static float alpha(final UiModel.Subtitle sub) {
		double shown = (System.nanoTime() - sub.at()) / 1e9;
		double secs = sub.secs() > 0.0 ? sub.secs() : LONGEST;
		return (float) Math.clamp((secs + FADE_SECONDS - shown) / FADE_SECONDS, 0.0, 1.0);
	}
}
