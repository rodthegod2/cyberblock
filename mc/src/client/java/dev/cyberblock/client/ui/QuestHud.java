package dev.cyberblock.client.ui;

import dev.cyberblock.client.HostState;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;

/**
 * The tracked job (section 1.11): top-right below the effect icons, right-aligned, at most 160 px wide. The title in yellow, each
 * objective behind a box (ticked once its counter is full), counters, "(optional)" in grey, and the distance to the waypoint
 * with an arrow toward it when the Locator Bar is not on screen.
 */
final class QuestHud {
	static final int WIDTH = 160;
	private static final int TOP = 52;
	private static final int RIGHT_MARGIN = 4;
	private static final int BOX = 7;
	private static final int LINE = 10;

	private QuestHud() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		UiModel.Quest quest = UiModel.quest;
		Player player = UiHud.cornerPlayer();
		if (quest == null || player == null) {
			return;
		}

		Font font = Minecraft.getInstance().font;
		int right = graphics.guiWidth() - RIGHT_MARGIN;
		int y = TOP;
		for (FormattedCharSequence line : font.split(Component.literal(quest.title()).withStyle(ChatFormatting.BOLD), WIDTH)) {
			graphics.text(font, line, right - font.width(line), y, McTheme.YELLOW, true);
			y += LINE;
		}

		for (UiModel.Objective objective : quest.objectives()) {
			boolean full = objective.tot() > 0 && objective.cur() >= objective.tot();
			MutableComponent text = Component.literal(objective.text());
			if (objective.tot() > 0) {
				text.append(Component.literal(" (" + objective.cur() + "/" + objective.tot() + ")"));
			}

			if (objective.optional()) {
				text.append(" ").append(Component.translatableWithFallback("cyberblock.ui.quest.optional", "(optional)").withStyle(ChatFormatting.GRAY));
			}

			int colour = full ? McTheme.GREEN : objective.on() ? McTheme.WHITE : McTheme.GRAY;
			List<FormattedCharSequence> lines = font.split(text, WIDTH - BOX - 3);
			int first = y;
			for (FormattedCharSequence line : lines) {
				graphics.text(font, line, right - font.width(line), y, colour, true);
				y += LINE;
			}

			int widest = lines.stream().mapToInt(font::width).max().orElse(0);
			box(graphics, right - widest - BOX - 3, first, full, objective.on());
		}

		if (quest.wp() != null) {
			double dx = quest.wp().x() - player.getX(), dz = quest.wp().z() - player.getZ();
			double dy = quest.wp().y() - player.getY();
			long metres = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
			Component distance = Component.translatableWithFallback("cyberblock.ui.quest.distance", "%s m", metres);
			int textX = right - font.width(distance);
			graphics.text(font, distance, textX, y + 1, McTheme.GOLD, true);
			if (!locatorBarShown()) {
				HostState.Pose pose = HostState.frame();
				float yaw = pose != null ? pose.yaw() : player.getYRot();
				arrow(graphics, textX - 7, y + 4, (float) Math.toRadians(RadarProjection.bearing(dx, dz, yaw)));
			}
		}
	}

	/** Vanilla puts the Locator Bar where the experience bar is, and hides it with the hotbar: in a vehicle (VehicleHud) or with F1. */
	private static boolean locatorBarShown() {
		Minecraft minecraft = Minecraft.getInstance();
		return !HostState.vehicle() && !minecraft.gui.hud.isHidden() && minecraft.gameMode != null && !minecraft.gameMode.hasExperience();
	}

	/** A sunken slot-style box; full objectives get a green tick, the active one a yellow rim. */
	private static void box(final GuiGraphicsExtractor graphics, final int x, final int y, final boolean ticked, final boolean active) {
		McTheme.slot(graphics, x, y, BOX, BOX);
		if (active && !ticked) {
			graphics.outline(x - 1, y - 1, BOX + 2, BOX + 2, McTheme.YELLOW);
		}

		if (ticked) {
			// a 5x4 tick drawn in pixels
			int c = McTheme.GREEN;
			graphics.fill(x + 1, y + 3, x + 2, y + 4, c);
			graphics.fill(x + 2, y + 4, x + 3, y + 5, c);
			graphics.fill(x + 3, y + 3, x + 4, y + 4, c);
			graphics.fill(x + 4, y + 2, x + 5, y + 3, c);
			graphics.fill(x + 5, y + 1, x + 6, y + 2, c);
		}
	}

	/** A small gold arrow turned toward the waypoint (0 = straight ahead = up), made of filled pixels. */
	private static void arrow(final GuiGraphicsExtractor graphics, final int cx, final int cy, final float radians) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(cx + 0.5F, cy + 0.5F);
		graphics.pose().rotate(radians);
		graphics.fill(-1, -4, 1, 4, McTheme.GOLD);
		graphics.fill(-3, -2, 3, -1, McTheme.GOLD);
		graphics.fill(-2, -3, 2, -2, McTheme.GOLD);
		graphics.pose().popMatrix();
	}
}
