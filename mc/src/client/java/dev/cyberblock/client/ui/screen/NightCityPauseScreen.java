package dev.cyberblock.client.ui.screen;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.StatusReporter;
import dev.cyberblock.client.settings.CyberblockSettingsScreen;
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
 * the escape hatch to Cyberpunk's own pause menu (settings, saves, everything Minecraft does not replace). "Cyberblock..." opens
 * Cyberblock's own settings screen (megaplan Q-B5), which needs no link.
 */
public final class NightCityPauseScreen extends Screen {
	private static final int FULL = 204;
	private static final int HALF = 100;
	private static final int ROW = 24;
	/** Height of the button block (back to quit): the top is lifted on a small window so the last button stays on screen. */
	private static final int BLOCK = 176;
	/** A screen opened from here is on top: the pause stays (removed() runs before the child's init in the same tick). */
	private boolean toChild;

	/** The buttons of the screen. Which of them work depends on the link and on nothing else (see {@link #usable}). */
	public enum Item {
		BACK, MAP, JOURNAL, MESSAGES, CP_MENU, SAVE, SAVES, OPTIONS, CYBERBLOCK, QUIT
	}

	/**
	 * Whether a button works. Back, Minecraft's options and Cyberblock's settings never need Night City, so a lost link cannot strand
	 * the player; every button that talks to Cyberpunk is greyed without one.
	 */
	public static boolean usable(final Item item, final boolean linked) {
		return switch (item) {
			case BACK, OPTIONS, CYBERBLOCK -> true;
			default -> linked;
		};
	}

	/** The escape hatch: asks Cyberpunk to open its own pause menu once. False (nothing sent) when no host is linked. */
	public static boolean openCyberpunkMenu() {
		return UiLink.act("cpmenu", "pause", 0L, 0);
	}

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
		int y = top();
		this.add(Item.BACK, left, y, FULL, ui("pause.back", "Back to Night City"), b -> this.onClose(), linked);
		y += ROW + 4;
		this.child(Item.MAP, left, y, HALF, ui("pause.map", "Map"), () -> new AtlasScreen(this), linked);
		this.child(Item.JOURNAL, left + FULL - HALF, y, HALF, ui("pause.journal", "Journal"), () -> new JournalScreen(this), linked);
		y += ROW;
		this.child(Item.MESSAGES, left, y, HALF, ui("pause.messages", "Messages"), () -> new PhoneScreen(this), linked);
		this.add(Item.CP_MENU, left + FULL - HALF, y, HALF, ui("pause.cp_menu", "Night City Menu..."), b -> {
			if (openCyberpunkMenu()) {
				this.minecraft.gui.setScreen(null);
			}
		}, linked);
		y += ROW;
		this.act(Item.SAVE, left, y, HALF, ui("pause.save", "Save Game"), "save", false, linked);
		// the saves screen (megaplan 9-U1) lists, loads and makes saves, and still has "Load Last Save"
		this.child(Item.SAVES, left + FULL - HALF, y, HALF, ui("pause.saves", "Saves..."), () -> new SavesScreen(this), linked);
		y += ROW + 4;
		this.child(Item.OPTIONS, left, y, FULL, ui("pause.options", "Minecraft Options..."), () -> new OptionsScreen(this, this.minecraft.options), linked);
		y += ROW;
		this.add(Item.CYBERBLOCK, left, y, FULL, Component.translatableWithFallback("cyberblock.settings.button", "Cyberblock..."), b -> {
			this.toChild = true;
			CyberblockSettingsScreen.open(this);
		}, linked);
		y += ROW;
		this.add(Item.QUIT, left, y, FULL, ui("pause.quit", "Quit to Cyberpunk Main Menu"), b -> this.confirmQuit(), linked);
	}

	/** The top of the button block: a quarter down, but never so low that the last button leaves the screen. */
	private int top() {
		return Math.max(24, Math.min(this.height / 4 + 8, this.height - BLOCK));
	}

	private void add(final Item item, final int x, final int y, final int w, final Component label, final Button.OnPress onPress, final boolean linked) {
		Button button = Button.builder(label, onPress).bounds(x, y, w, 20).build();
		button.active = usable(item, linked);
		this.addRenderableWidget(button);
	}

	private void child(final Item item, final int x, final int y, final int w, final Component label, final Supplier<Screen> screen, final boolean linked) {
		this.add(item, x, y, w, label, b -> {
			this.toChild = true;
			this.minecraft.gui.setScreen(screen.get());
		}, linked);
	}

	/** A button that sends {@code uiact a}; {@code close} leaves the pause screen (Cyberpunk takes over: a load). */
	private void act(final Item item, final int x, final int y, final int w, final Component label, final String a, final boolean close, final boolean linked) {
		this.add(item, x, y, w, label, b -> {
			if (UiLink.act(a, "", 0L, 0) && close) {
				this.minecraft.gui.setScreen(null);
			}
		}, linked);
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
		graphics.centeredText(this.font, this.title, this.width / 2, top() - 20, McTheme.WHITE);
		if (!UiLink.linked()) {
			graphics.centeredText(this.font, ui("screen.unavailable", "Night City is not connected"), this.width / 2, top() - 10, McTheme.RED);
		}
	}

	@Override
	public void onClose() {
		Cyberblock.LOG.info("ui: screen pause closed");
		super.onClose();
	}
}
