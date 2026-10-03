package dev.cyberblock.client.ui.screen;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.StatusReporter;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.client.ui.UiLink;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Night City's pause menu in Minecraft (F12, section 1.11: Esc and H in Cyberpunk open this). While it is open (and while a
 * screen opened from it is), {@link StatusReporter#nightCityPaused} is set, which Minecraft sends as {@code status.pause}: the DLL
 * pauses Cyberpunk exactly then. Its buttons are vanilla buttons; the Night City ones send {@code uiact}. "Night City Menu..." is
 * the escape hatch to Cyberpunk's own pause menu (settings, saves, everything Minecraft does not replace).
 */
public final class NightCityPauseScreen extends Screen {
	private static final int FULL = 204;
	private static final int HALF = 100;
	private static final int ROW = 24;
	/** A screen opened from here is on top: the pause stays (removed() runs before the child's init in the same tick). */
	private boolean toChild;

	public NightCityPauseScreen() {
		super(ui("pause.title", "Night City (paused)"));
	}

	static MutableComponent ui(final String key, final String english, final Object... args) {
		return Component.translatableWithFallback("cyberblock.ui." + key, english, args);
	}

	@Override
	protected void init() {
		StatusReporter.nightCityPaused = true;
		boolean linked = UiLink.linked();
		int left = this.width / 2 - FULL / 2;
		int y = this.height / 4 + 8;
		this.addRenderableWidget(Button.builder(ui("pause.back", "Back to Night City"), b -> this.onClose()).bounds(left, y, FULL, 20).build());
		y += ROW + 4;
		this.child(left, y, HALF, ui("pause.map", "Map"), () -> new AtlasScreen(this), linked);
		this.child(left + FULL - HALF, y, HALF, ui("pause.journal", "Journal"), () -> new JournalScreen(this), linked);
		y += ROW;
		this.child(left, y, HALF, ui("pause.messages", "Messages"), () -> new PhoneScreen(this), linked);
		this.act(left + FULL - HALF, y, HALF, ui("pause.cp_menu", "Night City Menu..."), "cpmenu", "pause", true, linked);
		y += ROW;
		this.act(left, y, HALF, ui("pause.save", "Save Game"), "save", "", false, linked);
		this.act(left + FULL - HALF, y, HALF, ui("pause.load_last", "Load Last Save"), "loadlast", "", true, linked);
		y += ROW + 4;
		this.child(left, y, FULL, ui("pause.options", "Minecraft Options..."), () -> new OptionsScreen(this, this.minecraft.options), true);
		y += ROW;
		Button quit = Button.builder(ui("pause.quit", "Quit to Cyberpunk Main Menu"), b -> this.confirmQuit()).bounds(left, y, FULL, 20).build();
		quit.active = linked;
		this.addRenderableWidget(quit);
	}

	private void child(final int x, final int y, final int w, final Component label, final Supplier<Screen> screen, final boolean active) {
		Button button = Button.builder(label, b -> {
			this.toChild = true;
			this.minecraft.gui.setScreen(screen.get());
		}).bounds(x, y, w, 20).build();
		button.active = active;
		this.addRenderableWidget(button);
	}

	/** A button that sends {@code uiact a}; {@code close} leaves the pause screen (Cyberpunk takes over: its menu, a load). */
	private void act(final int x, final int y, final int w, final Component label, final String a, final String arg, final boolean close, final boolean active) {
		Button button = Button.builder(label, b -> {
			if (UiLink.act(a, arg, 0L, 0) && close) {
				this.minecraft.gui.setScreen(null);
			}
		}).bounds(x, y, w, 20).build();
		button.active = active;
		this.addRenderableWidget(button);
	}

	private void confirmQuit() {
		this.toChild = true;
		this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes && UiLink.act("mainmenu", "", 0L, 0)) {
				this.minecraft.gui.setScreen(null);
			} else {
				this.minecraft.gui.setScreen(this);
			}
		}, ui("pause.quit", "Quit to Cyberpunk Main Menu"), ui("pause.quit_confirm", "Quit to the Cyberpunk main menu? Unsaved progress is lost.")));
	}

	@Override
	public void removed() {
		if (!this.toChild) {
			StatusReporter.nightCityPaused = false;
		}

		this.toChild = false;
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		// Night City is paused too (status.pause), so Minecraft may pause with it
		return true;
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 4 - 12, McTheme.WHITE);
		if (!UiLink.linked()) {
			graphics.centeredText(this.font, ui("screen.unavailable", "Night City is not connected"), this.width / 2, this.height / 4 - 2, McTheme.RED);
		}
	}

	@Override
	public void onClose() {
		Cyberblock.LOG.info("ui: screen pause closed");
		super.onClose();
	}
}
