package dev.cyberblock.client.mode.match;

import dev.cyberblock.client.ui.UiModel;
import dev.cyberblock.mode.match.MatchWire;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The match HUD (megaplan 8-M1), at the right edge: the PvP chip (shown while PvP is on or in a match; with chat or the inventory
 * open a button takes its place, MatchClient) and, in a match, its clock, the teams' scores (yours marked) and the capture points
 * in their owner's colour with the bar of the team taking them. A HUD element, so it is also in the overlay handed to Cyberpunk.
 */
final class MatchHud {
	static final int CHIP_W = 64;
	static final int CHIP_H = 16;
	private static final int PANEL_W = 120;
	private static final int BACK = 0x90000000;
	private static final int GREY = 0xFFAAAAAA;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int RED = 0xFFFF5555;
	private static final int QUEST_TOP = 52;
	private static final int QUEST_W = 160;

	private MatchHud() {
	}

	/** The chip's x, y and width for a GUI of this size (the HUD button sits exactly on it): below the tracked job when there is one. */
	static int[] chip(final int width, final int height) {
		return new int[] {width - CHIP_W - 4, Math.max(Math.max(4, height / 2 - 72), questBottom() + 6), CHIP_W};
	}

	/**
	 * The lowest y of the tracked job at the top right (QuestHud's layout, section 1.11: from y 52, 10 px lines, 160 px wide, the
	 * objectives 10 px narrower, one line for the waypoint distance); 0 without a job.
	 */
	private static int questBottom() {
		UiModel.Quest quest = UiModel.quest;
		if (quest == null) {
			return 0;
		}

		Font font = Minecraft.getInstance().font;
		int lines = font.split(Component.literal(quest.title()).withStyle(ChatFormatting.BOLD), QUEST_W).size();
		for (UiModel.Objective o : quest.objectives()) {
			MutableComponent text = Component.literal(o.text());
			if (o.tot() > 0) {
				text.append(" (" + o.cur() + "/" + o.tot() + ")");
			}

			if (o.optional()) {
				text.append(" ").append(Component.translatableWithFallback("cyberblock.ui.quest.optional", "(optional)"));
			}

			lines += font.split(text, QUEST_W - 10).size();
		}

		return QUEST_TOP + (lines + (quest.wp() != null ? 1 : 0)) * 10;
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker delta) {
		Minecraft minecraft = Minecraft.getInstance();
		// HUD elements are drawn under open screens too: only chat leaves the right edge free (the Match Terminal and the Cyberware
		// screen show their own PvP button and match view there)
		Screen screen = minecraft.gui.screen();
		if (minecraft.player == null || minecraft.gui.hud.isHidden() || screen != null && !(screen instanceof ChatScreen)) {
			return;
		}

		MatchWire.State state = MatchClient.state();
		MatchWire.View mine = state == null ? null : state.mine();
		boolean on = MatchClient.pvpOn();
		Font font = minecraft.font;
		int[] chip = chip(graphics.guiWidth(), graphics.guiHeight());
		if (on || mine != null) {
			graphics.fill(chip[0], chip[1], chip[0] + chip[2], chip[1] + CHIP_H, BACK);
			graphics.outline(chip[0], chip[1], chip[2], CHIP_H, on ? RED : GREY);
			Component label = MatchClient.pvpLabel(on);
			graphics.text(font, label, chip[0] + (chip[2] - font.width(label)) / 2, chip[1] + 4, on ? RED : GREY, true);
		}

		if (mine == null) {
			return;
		}

		int x = graphics.guiWidth() - PANEL_W - 4, y = chip[1] + CHIP_H + 4;
		int lines = 1 + mine.teams().size() + mine.points().size();
		graphics.fill(x, y, x + PANEL_W, y + 4 + lines * 10, BACK);
		String clock = mine.state().equals("lobby") ? Component.translatableWithFallback("cyberblock.match.hud.lobby", "lobby").getString()
			: String.format(Locale.ROOT, "%d:%02d", mine.time() / 60, mine.time() % 60);
		graphics.text(font, mine.name(), x + 3, y + 3, WHITE, true);
		graphics.text(font, clock, x + PANEL_W - 3 - font.width(clock), y + 3, mine.time() <= 30 && mine.state().equals("running") ? RED : GREY, true);
		y += 13;
		for (int t = 0; t < mine.teams().size(); t++) {
			MatchWire.Team team = mine.teams().get(t);
			graphics.fill(x + 3, y + 1, x + 9, y + 7, 0xFF000000 | team.colour());
			String name = (t == state.team() ? "> " : "") + team.name();
			graphics.text(font, font.plainSubstrByWidth(name, PANEL_W - 40), x + 12, y, WHITE, true);
			String score = Integer.toString(team.score());
			graphics.text(font, score, x + PANEL_W - 3 - font.width(score), y, WHITE, true);
			y += 10;
		}

		for (MatchWire.Point p : mine.points()) {
			int owner = p.owner() >= 0 && p.owner() < mine.teams().size() ? 0xFF000000 | mine.teams().get(p.owner()).colour() : GREY;
			graphics.text(font, font.plainSubstrByWidth(p.name(), PANEL_W - 46), x + 6, y, owner, true);
			int barX = x + PANEL_W - 38, w = 34;
			graphics.fill(barX, y + 2, barX + w, y + 6, 0xFF303030);
			int holder = p.holder() >= 0 && p.holder() < mine.teams().size() ? 0xFF000000 | mine.teams().get(p.holder()).colour() : GREY;
			graphics.fill(barX, y + 2, barX + (int) Math.round(w * p.progress()), y + 6, holder);
			y += 10;
		}
	}
}
