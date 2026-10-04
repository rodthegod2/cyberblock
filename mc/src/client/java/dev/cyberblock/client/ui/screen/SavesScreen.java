package dev.cyberblock.client.ui.screen;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.client.ui.SavesModel;
import dev.cyberblock.client.ui.UiLink;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * "Night City saves" (megaplan 9-U1): Cyberpunk's save and load lists as a Minecraft list screen, opened from the Night City
 * pause screen. It lists the saves Saves.reds sends ({@code uiact journalreq arg:"saves"}, newest first), loads the selected one
 * after a confirmation ({@code uiact loadlast arg:<name> id:<index>}), makes a new manual save ({@code save arg:"new"}) or a quick
 * save ({@code save}) and loads the last checkpoint ({@code loadlast}). When Night City gives no list (the runtime check of the
 * 2.31 save-list calls failed) it says so and "Night City Menu..." (the escape hatch, {@code cpmenu pause}) leads to Cyberpunk's
 * own lists, as before Phase 9. Back never needs the link.
 */
public final class SavesScreen extends Screen {
	private static final int LIST_W = 280;
	private static final int ROW_H = 36;
	private static final int TOP = 32;
	private static final int BUTTON_W = 100;
	private static final int GAP = 4;
	/** A list is asked for again this long (ticks) after a save, so the new one shows. */
	private static final int REFRESH_TICKS = 40;
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);

	/** The buttons of the screen. Which of them work depends on the link and the selected save only (see {@link #usable}). */
	public enum Item {
		LOAD, NEW_SAVE, QUICK_SAVE, LOAD_LAST, CP_MENU, BACK
	}

	private final @Nullable Screen parent;
	private SavesModel.@Nullable Saves saves;
	private int requestId = -1;
	/** The selected save's name, not its row: a refresh puts a new save on top and shifts every row (null = none). */
	private @Nullable String selectedKey;
	private int scroll;
	private int refreshIn = -1;
	private final Map<Item, Button> buttons = new EnumMap<>(Item.class);

	public SavesScreen(final @Nullable Screen parent) {
		super(NightCityPauseScreen.ui("saves.title", "Night City saves"));
		this.parent = parent;
	}

	/**
	 * Whether a button works. Back never needs Night City; the escape hatch and the save buttons need only the link, never the list,
	 * so a list that did not come never strands the player; Load needs a loadable selected save.
	 */
	public static boolean usable(final Item item, final boolean linked, final SavesModel.@Nullable Save selected) {
		return switch (item) {
			case BACK -> true;
			case LOAD -> linked && selected != null && selected.loadable();
			default -> linked;
		};
	}

	/** Asks Cyberpunk for its save list; {@code -1} (nothing sent) when no host is linked. */
	public static int requestSaves(final Consumer<SavesModel.Saves> to) {
		return UiLink.request("journal", "saves", r -> to.accept(SavesModel.of(r)));
	}

	/** Loads a save (Saves.reds finds it again by name). False (nothing sent) when it cannot be loaded or no host is linked. */
	public static boolean load(final SavesModel.Save save) {
		return save.loadable() && UiLink.act("loadlast", save.key(), 0L, save.index());
	}

	/** A new manual save. False (nothing sent) when no host is linked. */
	public static boolean newSave() {
		return UiLink.act("save", "new", 0L, 0);
	}

	@Override
	protected void init() {
		if (this.requestId < 0 && this.saves == null) {
			this.request();
		}

		boolean linked = UiLink.linked();
		int left = this.width / 2 - (3 * BUTTON_W + 2 * GAP) / 2;
		int row1 = this.height - 52, row2 = this.height - 28;
		this.add(Item.LOAD, left, row1, NightCityPauseScreen.ui("saves.load", "Load"), b -> this.confirmLoad(), linked);
		this.add(Item.NEW_SAVE, left + BUTTON_W + GAP, row1, NightCityPauseScreen.ui("saves.new", "New Save"), b -> this.saved(newSave()), linked);
		this.add(Item.QUICK_SAVE, left + 2 * (BUTTON_W + GAP), row1, NightCityPauseScreen.ui("saves.quick", "Quick Save"),
			b -> this.saved(UiLink.act("save", "", 0L, 0)), linked);
		this.add(Item.LOAD_LAST, left, row2, NightCityPauseScreen.ui("pause.load_last", "Load Last Save"), b -> {
			if (UiLink.act("loadlast", "", 0L, 0)) {
				this.minecraft.gui.setScreen(null);
			}
		}, linked);
		this.add(Item.CP_MENU, left + BUTTON_W + GAP, row2, NightCityPauseScreen.ui("pause.cp_menu", "Night City Menu..."), b -> {
			if (NightCityPauseScreen.openCyberpunkMenu()) {
				this.minecraft.gui.setScreen(null);
			}
		}, linked);
		this.add(Item.BACK, left + 2 * (BUTTON_W + GAP), row2, NightCityPauseScreen.ui("saves.back", "Back"), b -> this.onClose(), linked);
	}

	private void add(final Item item, final int x, final int y, final Component label, final Button.OnPress onPress, final boolean linked) {
		Button button = Button.builder(label, onPress).bounds(x, y, BUTTON_W, 20).build();
		this.buttons.put(item, button);
		this.addRenderableWidget(button);
		this.update(linked);
	}

	private void update(final boolean linked) {
		SavesModel.Save save = this.selectedSave();
		for (Map.Entry<Item, Button> e : this.buttons.entrySet()) {
			e.getValue().active = usable(e.getKey(), linked, save);
		}
	}

	private void request() {
		this.requestId = requestSaves(s -> {
			this.saves = s;
			this.requestId = -1;
			if (!s.available()) {
				Cyberblock.LOG.info("ui: saves unavailable ({}): Night City Menu... has Cyberpunk's own lists", s.why());
			}

			if (SavesModel.indexOf(s.list(), this.selectedKey) < 0) {
				this.selectedKey = null; // that save is gone: nothing is selected rather than its neighbour
			}

			this.update(UiLink.linked());
		});
	}

	/** After a save: the list is asked for again shortly, so the new save shows. */
	private void saved(final boolean sent) {
		if (sent) {
			this.refreshIn = REFRESH_TICKS;
		}
	}

	@Override
	public void tick() {
		if (this.refreshIn > 0 && --this.refreshIn == 0 && this.requestId < 0) {
			this.request();
		}

		this.update(UiLink.linked());
	}

	private SavesModel.@Nullable Save selectedSave() {
		SavesModel.Saves s = this.saves;
		int row = s == null ? -1 : SavesModel.indexOf(s.list(), this.selectedKey);
		return s == null || row < 0 ? null : s.list().get(row);
	}

	/** What tells two saves at the same place apart in the confirmation: its type and date, or its name when the date is unknown. */
	public static String describe(final SavesModel.Save save) {
		String when = save.time() != null ? WHEN.format(save.time().atZone(ZoneId.systemDefault())) : save.key();
		String type = typeName(save.type());
		return type.isEmpty() ? when : type + ", " + when;
	}

	private void confirmLoad() {
		SavesModel.Save save = this.selectedSave();
		if (save == null || !save.loadable()) {
			return;
		}

		this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes && load(save)) {
				this.minecraft.gui.setScreen(null);
			} else {
				this.minecraft.gui.setScreen(this);
			}
		}, NightCityPauseScreen.ui("saves.load_title", "Load %s?", save.title()),
			NightCityPauseScreen.ui("saves.load_confirm", "%s. Unsaved progress is lost.", describe(save))));
	}

	private int listLeft() {
		return this.width / 2 - LIST_W / 2;
	}

	private int listBottom() {
		return this.height - 60;
	}

	private int visibleRows() {
		return Math.max(1, (this.listBottom() - TOP) / ROW_H);
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 12, McTheme.WHITE);
		int x = this.listLeft(), centre = this.width / 2, middle = (TOP + this.listBottom()) / 2 - 4;
		SavesModel.Saves s = this.saves;
		if (s == null) {
			Component status = this.requestId < 0 ? NightCityPauseScreen.ui("screen.unavailable", "Night City is not connected")
				: NightCityPauseScreen.ui("saves.asking", "Asking Night City for its saves...");
			graphics.centeredText(this.font, status, centre, middle, this.requestId < 0 ? McTheme.RED : McTheme.GRAY);
			return;
		}

		if (!s.available()) {
			graphics.centeredText(this.font, NightCityPauseScreen.ui("saves.unavailable", "Night City did not list its saves"), centre, middle - 6, McTheme.RED);
			graphics.centeredText(this.font, NightCityPauseScreen.ui("saves.use_menu", "Night City Menu... opens Cyberpunk's own save and load lists"), centre,
				middle + 6, McTheme.GRAY);
			return;
		}

		List<SavesModel.Save> list = s.list();
		if (list.isEmpty()) {
			graphics.centeredText(this.font, NightCityPauseScreen.ui("saves.empty", "No saves yet"), centre, middle, McTheme.GRAY);
			return;
		}

		this.scroll = Math.clamp(this.scroll, 0, Math.max(0, list.size() - this.visibleRows()));
		graphics.enableScissor(x - 2, TOP - 2, x + LIST_W + 2, this.listBottom() + 2);
		int y = TOP, selected = SavesModel.indexOf(list, this.selectedKey);
		for (int i = this.scroll; i < list.size() && y < this.listBottom(); i++, y += ROW_H) {
			this.row(graphics, list.get(i), i == selected, x, y, mouseX, mouseY);
		}

		graphics.disableScissor();
	}

	/** One save, drawn like a world in Minecraft's world list: name, then type, level and play time, then date and job. */
	private void row(final GuiGraphicsExtractor graphics, final SavesModel.Save save, final boolean selected, final int x, final int y, final int mouseX,
		final int mouseY) {
		boolean hovered = mouseX >= x && mouseX < x + LIST_W && mouseY >= y && mouseY < y + ROW_H - 2 && mouseY < this.listBottom();
		if (selected) {
			graphics.fill(x - 2, y - 2, x + LIST_W + 2, y + ROW_H - 2, McTheme.WHITE);
			graphics.fill(x - 1, y - 1, x + LIST_W + 1, y + ROW_H - 3, 0xFF000000);
		} else if (hovered) {
			graphics.fill(x - 1, y - 1, x + LIST_W + 1, y + ROW_H - 3, 0x40FFFFFF);
		}

		int textW = LIST_W - 4;
		graphics.text(this.font, this.font.substrByWidth(Component.literal(save.title()), textW).getString(), x + 2, y + 1, save.loadable() ? McTheme.WHITE
			: McTheme.GRAY, false);
		StringBuilder second = new StringBuilder(typeName(save.type()));
		if (save.level() >= 0) {
			second.append(second.isEmpty() ? "" : " · ").append(NightCityPauseScreen.ui("saves.level", "Level %s", save.level()).getString());
		}

		String played = SavesModel.played(save.played());
		if (!played.isEmpty()) {
			second.append(second.isEmpty() ? "" : " · ").append(NightCityPauseScreen.ui("saves.played", "%s played", played).getString());
		}

		if (save.damaged()) {
			second.setLength(0);
			second.append(NightCityPauseScreen.ui("saves.damaged", "Cyberpunk cannot load this save").getString());
		} else if (!save.loadable()) {
			second.setLength(0);
			second.append(NightCityPauseScreen.ui("saves.long_name", "Load this one through Night City Menu...").getString());
		}

		graphics.text(this.font, this.font.substrByWidth(Component.literal(second.toString()), textW).getString(), x + 2, y + 12, McTheme.GRAY, false);
		String third = save.time() != null ? WHEN.format(save.time().atZone(ZoneId.systemDefault())) : save.key();
		if (!save.job().isEmpty()) {
			third += " · " + save.job();
		}

		graphics.text(this.font, this.font.substrByWidth(Component.literal(third), textW).getString(), x + 2, y + 23, McTheme.DARK_GRAY, false);
	}

	private static String typeName(final String type) {
		return switch (type) {
			case "ManualSave" -> NightCityPauseScreen.ui("saves.type.manual", "Manual save").getString();
			case "QuickSave" -> NightCityPauseScreen.ui("saves.type.quick", "Quick save").getString();
			case "AutoSave" -> NightCityPauseScreen.ui("saves.type.auto", "Autosave").getString();
			case "PointOfNoReturn" -> NightCityPauseScreen.ui("saves.type.ponr", "Point of no return").getString();
			case "EndGameSave" -> NightCityPauseScreen.ui("saves.type.endgame", "End of game").getString();
			default -> "";
		};
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		SavesModel.Saves s = this.saves;
		int x = this.listLeft();
		if (s == null || event.x() < x || event.x() >= x + LIST_W || event.y() < TOP || event.y() >= this.listBottom()) {
			return false;
		}

		int row = (int) ((event.y() - TOP) / ROW_H) + this.scroll;
		if (row < 0 || row >= s.list().size()) {
			return false;
		}

		this.selectedKey = s.list().get(row).key();
		this.update(UiLink.linked());
		if (doubleClick) {
			this.confirmLoad();
		}

		return true;
	}

	@Override
	public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
		this.scroll -= (int) Math.signum(scrollY);
		return true;
	}

	@Override
	public void removed() {
		// a confirmation on top drops the wait too; init asks again when it comes back without a list
		UiLink.forget(this.requestId);
		this.requestId = -1;
		super.removed();
	}

	@Override
	public void onClose() {
		Cyberblock.LOG.info("ui: screen saves closed");
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return this.parent != null;
	}
}
