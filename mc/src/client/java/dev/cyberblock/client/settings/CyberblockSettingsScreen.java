package dev.cyberblock.client.settings;

import dev.cyberblock.mode.overlord.Intensity;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.settings.Difficulty;
import dev.cyberblock.settings.SettingsWire;
import dev.cyberblock.skin.SkinRules;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The Cyberblock settings screen (megaplan 9-S1, section 1.13; started by Q-B5), vanilla widgets only, with the tabs Guests, Look, PvP,
 * Difficulty, Performance and World. Server values (guest toggles and device types, body, Holo-Table pin, PvP, difficulty, creative
 * erase) show what the server answered ({@link ServerValues}) and change only through the {@code /cyberblock} command each row sends, so
 * the server decides; rows a player may not change are greyed with the reason. server.json's rows (World) are read-only and shown to
 * operators and the singleplayer owner only; the join password shows only as on or off. Performance values are client only and saved
 * on this PC ({@link PerformancePrefs}). Opens from the "Cyberblock..." button of the pause screens and with {@code /cyberblock settings}.
 *
 * <p>Changing tab opens the screen again on that tab, so rows other packages add after init (QV's camera rows, through Fabric's
 * AFTER_INIT) are laid out under the Look tab's own rows; on the other tabs they are hidden.
 */
public final class CyberblockSettingsScreen extends Screen {
	/** A row another package adds (on the Look tab): a widget at x, y of the given width, 20 high. */
	@FunctionalInterface
	public interface Row {
		AbstractWidget create(int x, int y, int width);
	}

	public enum Page {
		GUESTS("Guests"), LOOK("Look"), PVP("PvP"), DIFFICULTY("Difficulty"), PERFORMANCE("Performance"), WORLD("World");

		private final String english;

		Page(final String english) {
			this.english = english;
		}

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		Component title() {
			return Component.translatableWithFallback("cyberblock.settings.tab." + this.id(), this.english);
		}
	}

	static final int WIDTH = 310, HALF = 153, THIRD = 100, H = 20, GAP = 4;
	private static final List<Row> ROWS = new CopyOnWriteArrayList<>();
	private static final Component WAITING = Component.translatableWithFallback("cyberblock.settings.waiting",
		"Waiting for the server's values (a server without this Cyberblock version cannot be changed here)");
	private static final Component LOCKED = Component.translatableWithFallback("cyberblock.settings.locked", "Only the host or an operator can change this");
	/** The tab the screen opens on next time (this session). */
	private static Page last = Page.GUESTS;

	private final @Nullable Screen parent;
	private final Page page;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private final TabManager tabs = new TabManager(this::addRenderableWidget, this::removeWidget, this::selected, tab -> {
	});
	private final List<ServerRow<?>> serverRows = new ArrayList<>();
	private final List<Runnable> appliers = new ArrayList<>();
	private @Nullable MenuTabBar bar;
	private Tab @Nullable [] pages;
	/** Every widget this screen made; anything else (added by other packages after init) shows on the Look tab only. */
	private @Nullable Set<GuiEventListener> own;

	private CyberblockSettingsScreen(final @Nullable Screen parent, final Page page) {
		super(Component.translatableWithFallback("cyberblock.settings.title", "Cyberblock settings"));
		this.parent = parent;
		this.page = page;
	}

	/** Opens the screen over parent (null: back to the game when it closes) on the tab used last. QU's Night City pause screen calls this. */
	public static void open(final @Nullable Screen parent) {
		open(parent, last);
	}

	/** Opens the screen on one tab. */
	public static void open(final @Nullable Screen parent, final Page page) {
		last = page;
		Minecraft.getInstance().gui.setScreen(new CyberblockSettingsScreen(parent, page));
	}

	/** Adds a row to the Look tab, for every screen opened from now on (client thread, at start-up). */
	public static void addRow(final Row row) {
		ROWS.add(row);
	}

	public Page page() {
		return this.page;
	}

	@Override
	protected void init() {
		this.serverRows.clear();
		this.appliers.clear();
		this.own = null;
		Page[] all = Page.values();
		this.pages = new Tab[all.length];
		for (Page p : all) {
			this.pages[p.ordinal()] = p == this.page ? this.build(p) : new GridLayoutTab(p.title());
		}

		this.bar = MenuTabBar.builder(this.tabs, this.width).addTabs(this.pages).build();
		this.addRenderableWidget(this.bar);
		this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).width(200).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.bar.selectTab(this.page.ordinal(), false);
		this.arrange();
		this.own = new HashSet<>(this.children());
		this.apply(ServerValues.snapshot());
		ServerValues.request();
	}

	/** The tab bar on top, the page under it, Done in the footer (as vanilla's tabbed screens). */
	private void arrange() {
		if (this.bar == null) {
			return;
		}

		this.bar.arrangeElements(this.width);
		int top = this.bar.getRectangle().bottom();
		this.tabs.setTabArea(new ScreenRectangle(0, top, this.width, this.height - this.layout.getFooterHeight() - top));
		this.layout.setHeaderHeight(top);
		this.layout.arrangeElements();
	}

	/** The tab bar picked another page: the screen opens again on it. */
	private void selected(final Tab tab) {
		if (this.own == null || this.pages == null) {
			return; // the first selection, inside init
		}

		for (Page p : Page.values()) {
			if (this.pages[p.ordinal()] == tab && p != this.page) {
				open(this.parent, p);
				return;
			}
		}
	}

	/** The server's values (or none yet): every server row shows them and is greyed where this player may not change it. */
	void apply(final SettingsWire.@Nullable Snapshot s) {
		for (ServerRow<?> row : this.serverRows) {
			row.apply(s);
		}

		for (Runnable r : this.appliers) {
			r.run();
		}
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		Set<GuiEventListener> mine = this.own;
		if (mine != null) {
			for (GuiEventListener l : this.children()) {
				if (!mine.contains(l) && l instanceof AbstractWidget w) {
					w.visible = this.page == Page.LOOK;
				}
			}
		}

		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	// ---- the pages ----

	private Tab build(final Page p) {
		PageTab tab = new PageTab(p.title());
		switch (p) {
			case GUESTS -> this.guests(tab);
			case LOOK -> this.look(tab);
			case PVP -> this.pvp(tab);
			case DIFFICULTY -> this.difficulty(tab);
			case PERFORMANCE -> this.performance(tab);
			case WORLD -> this.world(tab);
		}

		return tab;
	}

	private void guests(final PageTab tab) {
		tab.text(Component.translatableWithFallback("cyberblock.settings.guests.intro", "What guests may do to your Cyberpunk game while you host"));
		List<AbstractWidget> toggles = new ArrayList<>();
		for (Permissions.Toggle t : Permissions.Toggle.values()) {
			toggles.add(this.onOff(THIRD, Component.translatableWithFallback("cyberblock.settings.guests." + t.id(), capital(t.id())),
				Component.translatableWithFallback("cyberblock.settings.guests." + t.id() + ".help", guestHelp(t)), s -> s.permissions().has(t), s -> true,
				on -> "cyberblock guests " + t.id() + (on ? " on" : " off")).button);
		}

		tab.rows(toggles, 3);
		tab.text(Component.translatableWithFallback("cyberblock.settings.guests.device_types", "Device types guests may use (with Devices on)"));
		List<AbstractWidget> types = new ArrayList<>();
		Component needsDevices = Component.translatableWithFallback("cyberblock.settings.guests.devices.off", "Turn Devices on first");
		for (Permissions.DeviceType d : Permissions.DeviceType.values()) {
			types.add(this.onOff(THIRD, Component.translatableWithFallback("cyberblock.settings.device." + d.id(), deviceLabel(d)),
				Component.translatableWithFallback("cyberblock.settings.device.help", "Guests may use these of your Night City: %s",
					Component.translatableWithFallback("cyberblock.device.kind." + d.id(), d.id())),
				s -> s.permissions().device(d), s -> s.permissions().has(Permissions.Toggle.DEVICES), on -> "cyberblock guests devices " + d.id() + (on ? " on" : " off"))
				.locked(needsDevices).button);
		}

		tab.rows(types, 3);
	}

	private void look(final PageTab tab) {
		CycleButton<String> body = this.server(CycleButton.builder(CyberblockSettingsScreen::bodyName, SkinRules.MALE).withValues(SkinRules.MALE, SkinRules.FEMALE),
			HALF, Component.translatableWithFallback("cyberblock.settings.body", "Cyber skin"),
			Component.translatableWithFallback("cyberblock.settings.body.help", "How every player sees you while your Cyberpunk game is linked"),
			SettingsWire.Snapshot::body, s -> true, v -> "cyberblock body " + v).button;
		AbstractWidget pin = this.onOff(HALF, Component.translatableWithFallback("cyberblock.settings.hide_pin", "Hide my Holo pin"),
			Component.translatableWithFallback("cyberblock.settings.hide_pin.help", "Outside matches your pin is left off other players' Holo-Tables"),
			SettingsWire.Snapshot::hidePin, s -> true, on -> "cyberblock holo hide " + (on ? "on" : "off")).button;
		tab.rows(List.of(body, pin), 2);
		for (Row row : ROWS) {
			tab.rows(List.of(row.create(0, 0, WIDTH)), 1);
		}
	}

	private void pvp(final PageTab tab) {
		AbstractWidget pvp = this.onOff(HALF, Component.translatableWithFallback("cyberblock.settings.pvp", "PvP"),
			Component.translatableWithFallback("cyberblock.settings.pvp.help", "Players hurt each other only when both have PvP on"),
			SettingsWire.Snapshot::pvp, s -> true, on -> "cyberblock pvp " + (on ? "on" : "off")).button;
		tab.rows(List.of(pvp), 1);
		tab.text(Component.translatableWithFallback("cyberblock.settings.pvp.note", "PvP stays on for 15 s after your last fight"));
	}

	private void difficulty(final PageTab tab) {
		AbstractWidget intensity = this.server(CycleButton.builder(CyberblockSettingsScreen::intensityName, Difficulty.OVERLORD_DEFAULT)
				.withValues(Intensity.values()), HALF, Component.translatableWithFallback("cyberblock.settings.overlord", "Overlord"),
			Component.translatableWithFallback("cyberblock.settings.overlord.help", "The intensity a new match's Overlord starts with; the match's host can still change it"),
			SettingsWire.Snapshot::overlord, SettingsWire.Snapshot::admin, v -> "cyberblock difficulty overlord " + v.key()).button;
		List<Integer> steps = IntStream.rangeClosed(Difficulty.HEAT_MIN / Difficulty.HEAT_STEP, Difficulty.HEAT_MAX / Difficulty.HEAT_STEP)
			.mapToObj(i -> i * Difficulty.HEAT_STEP).toList();
		AbstractWidget heat = this.server(CycleButton.builder((Integer v) -> Component.literal(v + " %"), Difficulty.HEAT_DEFAULT).withValues(steps), HALF,
			Component.translatableWithFallback("cyberblock.settings.heat", "Heat scaling"),
			Component.translatableWithFallback("cyberblock.settings.heat.help", "How much of every chaos gain reaches the Server Heat meter"),
			SettingsWire.Snapshot::heat, SettingsWire.Snapshot::admin, v -> "cyberblock difficulty heat " + v).button;
		tab.rows(List.of(intensity, heat), 2);
		tab.text(Component.translatableWithFallback("cyberblock.settings.difficulty.note", "Saved with the world; operators and the host change these"));
	}

	private void performance(final PageTab tab) {
		PerformancePrefs p = PerformancePrefs.current();
		int scaleSteps = (PerformancePrefs.SCALE_MAX - PerformancePrefs.SCALE_MIN) / PerformancePrefs.SCALE_STEP;
		int capSteps = (PerformancePrefs.CAP_MAX - PerformancePrefs.CAP_MIN) / PerformancePrefs.CAP_STEP;
		StepSlider scale = new StepSlider(HALF, scaleSteps, (p.scale() - PerformancePrefs.SCALE_MIN) / PerformancePrefs.SCALE_STEP,
			i -> CommonComponents.optionNameValue(Component.translatableWithFallback("cyberblock.settings.scale", "Frame scale"),
				Component.literal((PerformancePrefs.SCALE_MIN + i * PerformancePrefs.SCALE_STEP) + " %")),
			i -> PerformancePrefs.set(PerformancePrefs.current().withScale(PerformancePrefs.SCALE_MIN + i * PerformancePrefs.SCALE_STEP)));
		scale.setTooltip(Tooltip.create(Component.translatableWithFallback("cyberblock.settings.scale.help",
			"Minecraft renders and exports its frame at this share of the window; the compositor upscales it")));
		StepSlider cap = new StepSlider(HALF, capSteps, (p.figureCap() - PerformancePrefs.CAP_MIN) / PerformancePrefs.CAP_STEP,
			i -> CommonComponents.optionNameValue(Component.translatableWithFallback("cyberblock.settings.figure_cap", "Figures"),
				Component.literal(Integer.toString(PerformancePrefs.CAP_MIN + i * PerformancePrefs.CAP_STEP))),
			i -> PerformancePrefs.set(PerformancePrefs.current().withFigureCap(PerformancePrefs.CAP_MIN + i * PerformancePrefs.CAP_STEP)));
		cap.setTooltip(Tooltip.create(Component.translatableWithFallback("cyberblock.settings.figure_cap.help", "How many of the nearest Night City figures are drawn")));
		tab.rows(List.of(scale, cap), 2);
		tab.text(Component.translatableWithFallback("cyberblock.settings.performance.note", "Saved on this PC only"));
	}

	private void world(final PageTab tab) {
		AbstractWidget erase = this.onOff(WIDTH, Component.translatableWithFallback("cyberblock.outline.erase", "Creative can erase the replica permanently"),
			Component.translatableWithFallback("cyberblock.settings.erase.help", "Broken outline blocks stay broken until the probes see the cell again"),
			SettingsWire.Snapshot::erase, s -> s.admin() && !s.eraseForced(), on -> "cyberblock outline erase " + (on ? "on" : "off"))
			.locked(s -> s.admin() ? Component.translatableWithFallback("cyberblock.outline.erase.forced",
				"Set by config/cyberblock/server.json (creativeErasesReplica: true); change it there and restart to use this world's setting") : LOCKED).button;
		tab.rows(List.of(erase), 1);
		List<AbstractWidget> info = new ArrayList<>();
		info.add(this.info("join_password", "Join password", w -> onOffText(w.joinPassword()),
			"Players type /login <password> once per world. The password is set by the server admin in server.json and is never shown"));
		info.add(this.info("allow_hosts", "Hosting", w -> onOffText(w.allowHosts()), "Whether players may link a Cyberpunk game at all"));
		info.add(this.info("hosts", "Hosts list", w -> w.hosts() == 0
			? Component.translatableWithFallback("cyberblock.settings.world.hosts.anyone", "not limited")
			: Component.translatableWithFallback("cyberblock.settings.world.hosts.count", "%s names", w.hosts()), "Who may host; /cyberblock hosts add|remove"));
		info.add(this.info("max_hosts", "Hosts at once", w -> Component.literal(Integer.toString(w.maxHosts())), "How many Cyberpunk games may be linked at once"));
		info.add(this.info("require_mod", "Mod required", w -> onOffText(w.requireMod()), "Players without the Cyberblock mod are turned away"));
		info.add(this.info("rate", "Message rate", w -> Component.literal(String.format(Locale.ROOT, "x%.2f", w.rate())), "Scales every relay rate limit"));
		info.add(this.info("sanity_radius", "Sanity radius", w -> Component.literal(w.sanityRadius() + " m"), "How far from its V a host's positions may be"));
		info.add(this.info("pvp_default", "PvP for new players", w -> onOffText(w.pvpDefault()), "The PvP toggle a player starts with"));
		info.add(this.info("backups", "Backups", w -> w.backupsDaily()
			? Component.literal(String.format(Locale.ROOT, "%02d:00, keep %d", w.backupsHour(), w.backupsKeep()))
			: CommonComponents.OPTION_OFF, "The daily world backup: its hour (server time) and how many are kept"));
		info.add(this.info("web_map", "Web map", w -> onOffText(w.webMap()), "The Night City web map"));
		info.add(this.info("join_help", "Join help", w -> w.joinHelp()
			? Component.translatableWithFallback("cyberblock.settings.world.set", "set")
			: Component.translatableWithFallback("cyberblock.settings.world.unset", "none"), "The text a refused player is shown"));
		tab.rows(info, 2);
		StringWidget note = tab.text(WAITING);
		this.appliers.add(() -> {
			SettingsWire.Snapshot s = ServerValues.snapshot();
			note.setMessage(s == null ? WAITING : s.world() == null
				? Component.translatableWithFallback("cyberblock.settings.world.hidden", "Only the host or an operator can see the server's settings")
				: Component.translatableWithFallback("cyberblock.settings.world.read_only", "Read-only: the server admin sets these in server.json"));
		});
	}

	// ---- rows ----

	/** A server on/off row: shows the server's value, sends {@code command(on)} when clicked. */
	private ServerRow<Boolean> onOff(final int width, final Component label, final Component help, final Function<SettingsWire.Snapshot, Boolean> value,
		final Predicate<SettingsWire.Snapshot> may, final Function<Boolean, String> command) {
		return this.server(CycleButton.onOffBuilder(false), width, label, help, value, may, command);
	}

	private <T> ServerRow<T> server(final CycleButton.Builder<T> builder, final int width, final Component label, final Component help,
		final Function<SettingsWire.Snapshot, T> value, final Predicate<SettingsWire.Snapshot> may, final Function<T, String> command) {
		ServerRow<T> row = new ServerRow<>(value, may, help);
		row.button = builder.withTooltip(v -> row.tooltip()).create(0, 0, width, H, label, (b, v) -> ServerValues.command(command.apply(v)));
		this.serverRows.add(row);
		return row;
	}

	/** A read-only server.json row (World): "Label: value", greyed, with what it means. */
	private AbstractWidget info(final String key, final String english, final Function<SettingsWire.World, Component> value, final String help) {
		Component label = Component.translatableWithFallback("cyberblock.settings.world." + key, english);
		Button b = Button.builder(label, x -> {
		}).width(HALF).tooltip(Tooltip.create(Component.translatableWithFallback("cyberblock.settings.world." + key + ".help", help))).build();
		b.active = false;
		this.appliers.add(() -> {
			SettingsWire.Snapshot s = ServerValues.snapshot();
			b.setMessage(CommonComponents.optionNameValue(label, s == null || s.world() == null ? Component.literal("-") : value.apply(s.world())));
		});
		return b;
	}

	private static Component onOffText(final boolean on) {
		return on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
	}

	static Component bodyName(final String body) {
		return SkinRules.FEMALE.equals(body)
			? Component.translatableWithFallback("cyberblock.settings.body.female", "Female V")
			: Component.translatableWithFallback("cyberblock.settings.body.male", "Male V");
	}

	static Component intensityName(final Intensity i) {
		return Component.translatableWithFallback("cyberblock.settings.intensity." + i.key(), capital(i.key()));
	}

	static String capital(final String id) {
		return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
	}

	/** The device type's short English label for a 100-wide button ("Doors", "Vending"); its tooltip names it in full. */
	static String deviceLabel(final Permissions.DeviceType d) {
		return switch (d) {
			case VENDING, TRAFFIC -> capital(d.id());
			default -> capital(d.id()) + "s";
		};
	}

	/** Section 1.6's table: what each toggle gates when a guest causes it. */
	static String guestHelp(final Permissions.Toggle t) {
		return switch (t) {
			case EXPLOSIONS -> "Guest explosions and wind charges near your V reach your game";
			case DAMAGE -> "Guest hits, fire, leads and rods reach your NPCs and cars";
			case HACKS -> "Guest scans and quickhacks reach your NPCs, cars and devices";
			case DEVICES -> "Guests may control your devices (each type below)";
			case SPAWNS -> "Guest and Overlord spawns of Night City NPCs and events reach your game";
		};
	}

	/** One server row: its button, the server's value for it, and who may change it. */
	private static final class ServerRow<T> {
		final Function<SettingsWire.Snapshot, T> value;
		final Predicate<SettingsWire.Snapshot> may;
		final Component help;
		Function<SettingsWire.Snapshot, Component> locked = s -> LOCKED;
		CycleButton<T> button;

		ServerRow(final Function<SettingsWire.Snapshot, T> value, final Predicate<SettingsWire.Snapshot> may, final Component help) {
			this.value = value;
			this.may = may;
			this.help = help;
		}

		ServerRow<T> locked(final Component why) {
			return this.locked(s -> why);
		}

		ServerRow<T> locked(final Function<SettingsWire.Snapshot, Component> why) {
			this.locked = why;
			return this;
		}

		Tooltip tooltip() {
			SettingsWire.Snapshot s = ServerValues.snapshot();
			return Tooltip.create(s == null ? WAITING : this.may.test(s) ? this.help : this.locked.apply(s));
		}

		void apply(final SettingsWire.@Nullable Snapshot s) {
			this.button.active = s != null && this.may.test(s);
			// setValue also refreshes the tooltip (the reason it is greyed, or what it does)
			this.button.setValue(s == null ? this.button.getValue() : this.value.apply(s));
		}
	}

	/** A page: centred rows of widgets, a text line where it helps. */
	private static final class PageTab extends GridLayoutTab {
		private final GridLayout.RowHelper helper;

		PageTab(final Component title) {
			super(title);
			this.layout.rowSpacing(GAP);
			this.layout.defaultCellSetting().alignHorizontallyCenter();
			this.helper = this.layout.createRowHelper(1);
		}

		/** The widgets in rows of {@code perRow}. */
		void rows(final List<? extends AbstractWidget> widgets, final int perRow) {
			for (int i = 0; i < widgets.size(); i += perRow) {
				LinearLayout row = LinearLayout.horizontal().spacing(GAP);
				widgets.subList(i, Math.min(widgets.size(), i + perRow)).forEach(row::addChild);
				this.helper.addChild(row);
			}
		}

		StringWidget text(final Component text) {
			return this.helper.addChild(new StringWidget(WIDTH, 12, text, Minecraft.getInstance().font).setMaxWidth(WIDTH));
		}
	}

	/** A slider over steps 0..steps; the label and the change see the step. */
	private static final class StepSlider extends AbstractSliderButton {
		private final int steps;
		private final IntFunction<Component> label;
		private final IntConsumer change;

		StepSlider(final int width, final int steps, final int step, final IntFunction<Component> label, final IntConsumer change) {
			super(0, 0, width, H, Component.empty(), Math.clamp(step, 0, steps) / (double) steps);
			this.steps = steps;
			this.label = label;
			this.change = change;
			this.updateMessage();
		}

		private int step() {
			return (int) Math.round(this.value * this.steps);
		}

		@Override
		protected void updateMessage() {
			this.setMessage(this.label.apply(this.step()));
		}

		@Override
		protected void applyValue() {
			this.change.accept(this.step());
		}
	}
}
