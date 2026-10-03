package dev.cyberblock.client.cyber;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Cyberware;
import dev.cyberblock.cyber.CyberwareRules;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The implants' row right of the hotbar (plan retest2 section 1.7): one icon per installed ACTIVE or AUTO implant, a bar
 * under each (grey filling up cyan while it recharges, full cyan when ready, magenta draining while its effect runs) and, on
 * each ACTIVE one, the key that fires it (Y, L or O as bound: plan retest3 section 1.7). A HUD element, so it lands in the
 * overlay Cyberpunk composites.
 */
final class CyberwareHud {
	static final Identifier ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "cyberware_bar");
	private static final int STEP = 20;
	private static final int BAR_EMPTY = 0xFF3A3F4F;
	private static final int BAR_READY = 0xFF33E6FF;
	private static final int BAR_ACTIVE = 0xFFFF3DF2;
	private static final int KEY = 0xFFFFE14D;

	private CyberwareHud() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || player.isSpectator()) {
			return;
		}

		List<Cyberware> row = CyberwareRules.activeOrder(CyberData.installed(player));
		if (row.isEmpty()) {
			return;
		}

		long now = player.level().getGameTime();
		// the hotbar is 182 wide, centred, its top 22 above the bottom edge
		int x0 = graphics.guiWidth() / 2 + 99;
		int y = graphics.guiHeight() - 22;
		for (int i = 0; i < row.size(); i++) {
			Cyberware c = row.get(i);
			int x = x0 + i * STEP;
			CyberwareRules.Timer timer = CyberData.timer(player, c);
			graphics.fill(x, y, x + STEP - 2, y + 22, 0x900A0C15);
			graphics.item(new ItemStack(c.item()), x + 1, y + 1);
			graphics.fill(x + 1, y + 19, x + 17, y + 21, BAR_EMPTY);
			if (timer.active(now)) {
				graphics.fill(x + 1, y + 19, x + 1 + Math.round(16 * timer.activeFraction(now)), y + 21, BAR_ACTIVE);
				graphics.pose().pushMatrix();
				graphics.pose().translate(x + 1, y - 5);
				graphics.pose().scale(0.5F, 0.5F);
				graphics.text(minecraft.font, Component.translatable("cyberblock.cyberware.hud.active"), 0, 0, BAR_ACTIVE, true);
				graphics.pose().popMatrix();
			} else {
				graphics.fill(x + 1, y + 19, x + 1 + Math.round(16 * (1.0F - timer.remainingFraction(now))), y + 21, BAR_READY);
			}

			CyberwareRules.Trigger trigger = CyberwareRules.Trigger.of(c);
			if (trigger != null) {
				graphics.pose().pushMatrix();
				graphics.pose().translate(x + STEP - 3, y + 1);
				graphics.pose().scale(0.5F, 0.5F);
				Component key = CyberKeys.of(trigger).getTranslatedKeyMessage();
				graphics.text(minecraft.font, key, -minecraft.font.width(key), 0, KEY, true);
				graphics.pose().popMatrix();
			}
		}
	}
}
