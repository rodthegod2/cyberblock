package dev.cyberblock.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Cyberpunk's interaction prompt ("F Talk", "F Open", "F Pick up", F13) as a vanilla tooltip box right of the crosshair: the
 * key in yellow brackets, the action in white, inactive entries grey, hold actions with a bar under them. Only drawn: the keys
 * stay Cyberpunk's (F, R, 1, 2...), Minecraft handles no input here.
 */
final class PromptHud {
	private static final int LINE = 10;
	private static final int HOLD_BAR = 4;
	private static final int MAX_WIDTH = 200;

	private PromptHud() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		UiModel.Prompt prompt = UiModel.prompt;
		if (prompt.isEmpty() || UiHud.player() == null) {
			return;
		}

		Font font = Minecraft.getInstance().font;
		boolean titled = !prompt.title().isEmpty();
		int width = titled ? Math.min(MAX_WIDTH, font.width(prompt.title())) : 0;
		int height = titled ? LINE + 2 : 0;
		for (UiModel.PromptEntry entry : prompt.list()) {
			width = Math.max(width, entryWidth(font, entry));
			height += LINE + (entry.hold() ? HOLD_BAR : 0);
		}

		height -= 1;
		int x = graphics.guiWidth() / 2 + 12 + McTheme.TOOLTIP_PAD;
		int y = graphics.guiHeight() / 2 - height / 2;
		McTheme.tooltipBox(graphics, x, y, width, height);
		if (titled) {
			graphics.text(font, font.plainSubstrByWidth(prompt.title(), MAX_WIDTH), x, y, McTheme.AQUA, true);
			y += LINE + 2;
		}

		for (UiModel.PromptEntry entry : prompt.list()) {
			int textX = x;
			if (!entry.key().isEmpty()) {
				Component key = McTheme.keyText(entry.key());
				graphics.text(font, key, x, y, entry.inactive() ? McTheme.GRAY : McTheme.YELLOW, true);
				textX += font.width(key) + 4;
			}

			int colour = entry.inactive() ? McTheme.GRAY : McTheme.WHITE;
			graphics.text(font, label(entry), textX, y, colour, true);
			y += LINE;
			if (entry.hold()) {
				// a hold action: an empty bar under it in the slot colours (Cyberpunk keeps the key and its progress)
				McTheme.slot(graphics, textX, y - 1, Math.max(16, width - (textX - x)), 3);
				y += HOLD_BAR;
			}
		}
	}

	/** The action, then " (Hold)" in grey for a hold action. */
	private static Component label(final UiModel.PromptEntry entry) {
		Component text = Component.literal(entry.text());
		return entry.hold()
			? Component.empty().append(text).append(Component.literal(" (").append(Component.translatableWithFallback("cyberblock.ui.prompt.hold", "Hold")).append(")")
				.withStyle(ChatFormatting.GRAY))
			: text;
	}

	private static int entryWidth(final Font font, final UiModel.PromptEntry entry) {
		return (entry.key().isEmpty() ? 0 : font.width(McTheme.keyText(entry.key())) + 4) + font.width(label(entry));
	}
}
