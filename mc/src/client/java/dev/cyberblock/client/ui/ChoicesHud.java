package dev.cyberblock.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Dialogue and interaction choices (F13, section 1.11): a tooltip-styled list at the bottom centre above the subtitles. The
 * selected entry of the active hub gets "> " and yellow, inactive entries are grey, quest-important ones gold; paid entries end
 * in "€$", passed and failed attribute checks in a green tick or a red cross; a timed hub shows a boss-bar style timer. Only
 * drawn: E/Q, 1-4 and the wheel stay Cyberpunk's (Cyberblock_SetChoices), Minecraft handles no input here.
 */
final class ChoicesHud {
	private static final int LINE = 10;
	private static final int HUB_GAP = 4;
	private static final int MAX_WIDTH = 260;
	private static final int TIMER_HEIGHT = 9;
	/** The longest time a timed hub showed since it opened: the timer's full width. Render thread only. */
	private static double timerTotal;
	private static UiModel.Choices timerOf = UiModel.Choices.EMPTY;

	private ChoicesHud() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		UiModel.Choices choices = UiModel.choices;
		if (choices.isEmpty() || UiHud.player() == null) {
			return;
		}

		Font font = Minecraft.getInstance().font;
		int width = 0, height = 0;
		for (UiModel.Hub hub : choices.hubs()) {
			if (!hub.title().isEmpty()) {
				width = Math.max(width, font.width(hub.title()));
				height += LINE;
			}

			for (int i = 0; i < hub.list().size(); i++) {
				width = Math.max(width, font.width(entry(hub, i, choices)));
				height += LINE;
			}

			height += HUB_GAP;
		}

		width = Math.min(width, MAX_WIDTH);
		height -= HUB_GAP + 1;
		double left = timeLeft(choices);
		int bottom = graphics.guiHeight() - SubtitleHud.BOTTOM_FROM_EDGE - SubtitleHud.height(graphics.guiWidth()) - 6 - McTheme.TOOLTIP_PAD
			- (left >= 0.0 ? TIMER_HEIGHT : 0);
		int x = (graphics.guiWidth() - width) / 2, y = bottom - height;
		McTheme.tooltipBox(graphics, x, y, width, height);
		for (UiModel.Hub hub : choices.hubs()) {
			boolean active = hub.id() == choices.active();
			if (!hub.title().isEmpty()) {
				graphics.text(font, font.plainSubstrByWidth(hub.title(), width), x, y, active ? McTheme.AQUA : McTheme.GRAY, true);
				y += LINE;
			}

			for (int i = 0; i < hub.list().size(); i++) {
				graphics.text(font, Language.getInstance().getVisualOrder(font.substrByWidth(entry(hub, i, choices), width)), x, y, colour(hub, i, choices), true);
				y += LINE;
			}

			y += HUB_GAP;
		}

		if (left >= 0.0) {
			int barWidth = Math.min(182, width + 2 * McTheme.TOOLTIP_PAD);
			McTheme.bossBar(graphics, (graphics.guiWidth() - barWidth) / 2, bottom + McTheme.TOOLTIP_PAD + 3, barWidth, (float) (left / timerTotal));
		}
	}

	/** Seconds left on a timed hub now (counted down since the message), -1 when untimed. */
	private static double timeLeft(final UiModel.Choices choices) {
		if (!choices.timed()) {
			return -1.0;
		}

		if (choices != timerOf) {
			// a new message: a new hub starts the timer over, the same hub keeps its full width
			UiModel.Hub was = timerOf.activeHub();
			UiModel.Hub now = choices.activeHub();
			if (!timerOf.timed() || was == null || now == null || was.id() != now.id() || choices.left() > timerTotal) {
				timerTotal = Math.max(choices.left(), 0.1);
			}

			timerOf = choices;
		}

		return Math.max(0.0, choices.left() - (System.nanoTime() - choices.at()) / 1e9);
	}

	private static boolean selected(final UiModel.Hub hub, final int i, final UiModel.Choices choices) {
		return hub.id() == choices.active() && i == choices.sel();
	}

	/** "> " on the selection, the text, then the flag suffixes. */
	private static Component entry(final UiModel.Hub hub, final int i, final UiModel.Choices choices) {
		UiModel.Choice choice = hub.list().get(i);
		MutableComponent text = Component.literal(selected(hub, i, choices) ? "> " : "  ").append(choice.text());
		if (choice.flags().contains(UiModel.Flag.PAY)) {
			text.append(Component.literal(" €$").withStyle(ChatFormatting.GOLD));
		}

		if (choice.flags().contains(UiModel.Flag.CHECK_OK)) {
			text.append(Component.literal(" ✔").withStyle(ChatFormatting.GREEN));
		} else if (choice.flags().contains(UiModel.Flag.CHECK_FAIL)) {
			text.append(Component.literal(" ✘").withStyle(ChatFormatting.RED));
		}

		return text;
	}

	private static int colour(final UiModel.Hub hub, final int i, final UiModel.Choices choices) {
		UiModel.Choice choice = hub.list().get(i);
		if (choice.flags().contains(UiModel.Flag.INACTIVE) || hub.id() != choices.active()) {
			return McTheme.GRAY;
		}

		if (selected(hub, i, choices)) {
			return McTheme.YELLOW;
		}

		return choice.flags().contains(UiModel.Flag.QUEST) ? McTheme.GOLD : McTheme.WHITE;
	}
}
