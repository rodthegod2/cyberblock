package dev.cyberblock.client.mode.downed;

import dev.cyberblock.client.ui.DownedHud;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.mode.downed.DownedWire;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The downed screen (megaplan 8-T2, the death screen tie-in): it opens when the player goes down, in the vanilla death screen's look
 * (a red wash, a big title, vanilla buttons): the bleed-out or Trauma Team countdown, a teammate's revive, and three choices: call
 * Trauma Team (its price in eddies; {@code /cyberblock trauma}), crawl (closes it; Esc too; the pause screen's "Downed..." brings it
 * back), give up ({@code /cyberblock giveup}: bleed out now, then Minecraft's death screen). It never pauses the game.
 */
public final class DownedScreen extends Screen {
	private Button trauma;

	public DownedScreen() {
		super(Component.translatableWithFallback("cyberblock.downed.screen.title", "You are down"));
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 100, y = this.height / 4 + 72;
		DownedWire.Status s = DownedHud.status;
		this.trauma = this.addRenderableWidget(Button.builder(traumaLabel(s), b -> this.command("cyberblock trauma")).bounds(x, y, 200, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatableWithFallback("cyberblock.downed.screen.crawl", "Crawl"), b -> this.onClose())
			.bounds(x, y + 24, 200, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatableWithFallback("cyberblock.downed.screen.give_up", "Give up"), b -> {
			this.command("cyberblock giveup");
			this.onClose();
		}).bounds(x, y + 48, 200, 20).build());
		this.update();
	}

	static Component traumaLabel(final DownedWire.Status s) {
		if (s.trauma() >= 0) {
			return Component.translatableWithFallback("cyberblock.downed.screen.trauma_coming", "Trauma Team is on its way");
		}

		return s.price() > 0 ? Component.translatableWithFallback("cyberblock.downed.screen.trauma", "Call Trauma Team (%s eddies)", s.price())
			: Component.translatableWithFallback("cyberblock.downed.screen.trauma_free", "Call Trauma Team");
	}

	private void command(final String command) {
		if (this.minecraft != null && this.minecraft.player != null) {
			this.minecraft.player.connection.sendCommand(command);
		}
	}

	/** The server's latest status: the trauma button follows it. */
	void update() {
		if (this.trauma != null) {
			DownedWire.Status s = DownedHud.status;
			this.trauma.setMessage(traumaLabel(s));
			this.trauma.active = s.trauma() < 0;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		graphics.fillGradient(0, 0, this.width, this.height, 0x60500000, 0xA0803030);
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		DownedWire.Status s = DownedHud.status;
		long age = System.currentTimeMillis() - DownedHud.statusAt;
		graphics.pose().pushMatrix();
		graphics.pose().scale(2.0F, 2.0F);
		graphics.centeredText(this.font, this.title, this.width / 2 / 2, 30, McTheme.WHITE);
		graphics.pose().popMatrix();
		int y = this.height / 4 + 20;
		Component line = s.trauma() >= 0
			? Component.translatableWithFallback("cyberblock.downed.hud.trauma", "Trauma Team lands in %s s", DownedHud.left(s.trauma(), age))
			: Component.translatableWithFallback("cyberblock.downed.hud.bleed", "Bleeding out in %s s", DownedHud.left(s.left(), age));
		graphics.centeredText(this.font, line, this.width / 2, y, s.trauma() >= 0 ? McTheme.AQUA : McTheme.YELLOW);
		y += 12;
		if (s.revive() > 0.0F && !s.by().isEmpty()) {
			graphics.centeredText(this.font, Component.translatableWithFallback("cyberblock.downed.hud.reviving_you", "%s is reviving you", s.by()),
				this.width / 2, y, McTheme.GREEN);
		} else if (s.solo()) {
			graphics.centeredText(this.font, Component.translatableWithFallback("cyberblock.downed.screen.solo", "Nobody is here to revive you"),
				this.width / 2, y, McTheme.GRAY);
		} else {
			graphics.centeredText(this.font, Component.translatableWithFallback("cyberblock.downed.screen.wait",
				"A teammate can revive you: right-click held on you for 4 s"), this.width / 2, y, McTheme.GRAY);
		}
	}
}
