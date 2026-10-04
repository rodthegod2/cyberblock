package dev.cyberblock.client.holo;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.HostState;
import dev.cyberblock.client.atlas.AtlasStore;
import dev.cyberblock.client.atlas.AtlasTiles;
import dev.cyberblock.client.holo.HoloEntries.Entry;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.client.ui.screen.AtlasScreen;
import dev.cyberblock.holo.HoloRules;
import dev.cyberblock.holo.HoloText;
import dev.cyberblock.holo.HoloWire;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/**
 * The Holo-Table view (megaplan 7-T1): the shared live 3D miniature of the replica (HoloMesh over the shared Night City map, with
 * the replica detail near players), every player the server lets this player see as a pin (head, name, team colour, [V], status:
 * in a car, in combat, downed), the optional layers, and on the right the spawn list: district landmarks, Spawn Beacons, team bases,
 * friends to join and (7V's hook) the host's fast-travel points. Drag to rotate (sideways) and tilt (up and down), right-drag or
 * shift-drag to pan, wheel to zoom; the buttons do the same. "2D" shows the same on the Atlas ({@link AtlasScreen} with
 * {@link HoloFlat}); when the 3D table costs more than {@link #BUDGET_NANOS} a frame the view falls back to 2D for the session
 * (logged once).
 */
public final class HoloScreen extends Screen {
	static final int MARGIN = 8, TOP = 26, BOTTOM = 28, PANEL = 156, ROW = 11, DETAIL_H = 58;
	/** The 3D table's own cost per frame (mesh projection and submission) before 2D takes over. */
	static final long BUDGET_NANOS = 8_000_000L;
	static final int BUDGET_FRAMES = 30;
	static final long REBUILD_NANOS = 250_000_000L;
	static final int V_COLOUR = 0xFFFCEE0A;
	private static final Identifier LANDMARK = Identifier.withDefaultNamespace("white_banner");
	private static final Identifier BEACON = Identifier.withDefaultNamespace("blue_marker");
	private static final Identifier BASE = Identifier.withDefaultNamespace("target_point");
	private static final Identifier TRAVEL = Identifier.withDefaultNamespace("target_x");
	/** The layers shown, kept for the session. */
	static final Set<String> shownLayers = new HashSet<>();
	/** The 3D table was too slow on this PC: the table opens in 2D for the rest of the session. */
	static boolean forced2D;
	/** The 2D Atlas this table opened (HoloClient polls while it shows). */
	static @Nullable AtlasScreen flat;
	/** The selected spawn entry's key (kind:id, or travel:name), kept between openings. */
	static @Nullable String selected;

	private final HoloCamera cam;
	private final HoloMesh.Quads quads = new HoloMesh.Quads();
	private final Matrix3x2f affine = new Matrix3x2f();
	private HoloMesh.@Nullable Grid grid;
	private long builtAt;
	private double builtCx = Double.NaN, builtCz, builtRadius;
	private int builtViewVersion;
	private @Nullable AtlasTiles builtView;
	private @Nullable List<HoloWire.Detail> builtDetail;
	private double costEma;
	private int frames;
	private int scroll;
	private boolean rotating, panning;
	private @Nullable Button action, hide;
	private List<String> builtLayers = List.of();
	/** Hit boxes drawn this frame: {x0, y0, x1, y1} and the entry key. */
	private final List<Hit> hits = new ArrayList<>();

	private record Hit(int x0, int y0, int x1, int y1, String key, List<Component> tooltip) {
	}

	HoloScreen() {
		super(Component.translatableWithFallback("cyberblock.holo.title", "Holo-Table"));
		double[] c = startCentre();
		this.cam = new HoloCamera(c[0], c[1], 256.0);
	}

	/** Opens the table: 3D, or 2D when this PC fell back to it. */
	static void open(final Minecraft minecraft) {
		if (forced2D) {
			double[] c = startCentre();
			minecraft.gui.setScreen(flatScreen(null, c[0], c[1], 0.5));
		} else {
			minecraft.gui.setScreen(new HoloScreen());
			Cyberblock.LOG.info("holo: table view opened (3D miniature)");
		}
	}

	private static AtlasScreen flatScreen(final @Nullable Screen parent, final double x, final double z, final double zoom) {
		AtlasScreen atlas = new AtlasScreen(parent, Component.translatableWithFallback("cyberblock.holo.title_2d", "Holo-Table (map)"), new HoloFlat(parent != null),
			x, z, zoom);
		flat = atlas;
		return atlas;
	}

	/** V (an active host), else Steve in Night City, else the first spawn point, else the map's origin. */
	private static double[] startCentre() {
		HostState.Pose pose = HostState.frame();
		if (pose != null) {
			return new double[] {pose.px(), pose.pz()};
		}

		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && player.level().dimension() == Level.OVERWORLD) {
			return new double[] {player.getX(), player.getZ()};
		}

		HoloWire.State s = HoloClient.state();
		if (s != null && !s.spawns().isEmpty()) {
			return new double[] {s.spawns().getFirst().x(), s.spawns().getFirst().z()};
		}

		return new double[] {0.0, 0.0};
	}

	// ---- layout ----

	private int left() {
		return MARGIN;
	}

	private int right() {
		return this.width - PANEL - 2 * MARGIN;
	}

	private int top() {
		return TOP;
	}

	private int bottom() {
		return this.height - BOTTOM;
	}

	private int panelX() {
		return this.width - PANEL - MARGIN;
	}

	private int listBottom() {
		return this.bottom() - DETAIL_H;
	}

	private boolean onMap(final double x, final double y) {
		return x >= this.left() && x < this.right() && y >= this.top() && y < this.bottom();
	}

	private boolean onList(final double x, final double y) {
		return x >= this.panelX() && x < this.panelX() + PANEL && y >= this.top() && y < this.listBottom();
	}

	private double scale() {
		return Math.min(this.right() - this.left(), this.bottom() - this.top()) / (2.0 * this.cam.radius());
	}

	// ---- widgets ----

	@Override
	protected void init() {
		int y = this.height - BOTTOM + 4, x = MARGIN;
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.cam.zoom(1.5)).bounds(x, y, 18, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("-"), b -> this.cam.zoom(1 / 1.5)).bounds(x += 20, y, 18, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("↑"), b -> this.cam.tiltBy(-15)).bounds(x += 20, y, 18, 20)
			.tooltip(net.minecraft.client.gui.components.Tooltip.create(text("tilt_up", "Tilt toward the horizon"))).build());
		this.addRenderableWidget(Button.builder(Component.literal("↓"), b -> this.cam.tiltBy(15)).bounds(x += 20, y, 18, 20)
			.tooltip(net.minecraft.client.gui.components.Tooltip.create(text("tilt_down", "Tilt toward the top-down view"))).build());
		this.addRenderableWidget(Button.builder(Component.literal("⟲"), b -> this.cam.turn(Math.PI / 4 / HoloCamera.TURN)).bounds(x += 20, y, 18, 20)
			.tooltip(net.minecraft.client.gui.components.Tooltip.create(text("turn", "Turn the table"))).build());
		this.addRenderableWidget(Button.builder(text("you", "You"), b -> this.centreOnMe()).bounds(x += 20, y, 30, 20).build());
		x += 34;
		HoloWire.State s = HoloClient.state();
		this.builtLayers = s == null ? List.of() : s.layers().stream().map(HoloWire.Layer::id).toList();
		for (String id : this.builtLayers) {
			if (x + 44 > this.right()) {
				break;
			}

			this.addRenderableWidget(Button.builder(this.layerLabel(id), b -> {
				if (!shownLayers.remove(id)) {
					shownLayers.add(id);
				}

				b.setMessage(this.layerLabel(id));
			}).bounds(x, y, 42, 20).build());
			x += 44;
		}

		int px = this.panelX();
		this.addRenderableWidget(Button.builder(text("2d", "2D"), b -> this.toFlat()).bounds(px + PANEL - 30, 3, 30, 20)
			.tooltip(net.minecraft.client.gui.components.Tooltip.create(text("2d.tip", "The same on the Atlas map"))).build());
		this.hide = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			HoloWire.State st = HoloClient.state();
			HoloClient.hide(st == null || !st.hidden());
		}).bounds(px, 3, PANEL - 34, 20).build());
		this.action = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.act()).bounds(px, this.bottom() - 22, PANEL, 20).build());
		this.updateButtons();
	}

	private Component layerLabel(final String id) {
		Component name = layerName(id);
		return shownLayers.contains(id) ? name.copy().withStyle(ChatFormatting.AQUA) : name.copy().withStyle(ChatFormatting.DARK_GRAY);
	}

	static Component layerName(final String id) {
		return switch (id) {
			case "npcs" -> text("layer.npcs", "NPCs");
			case "hotspots" -> text("layer.hotspots", "Danger");
			case "devices" -> text("layer.devices", "Devices");
			case "heat" -> text("layer.heat", "Heat");
			case "events" -> text("layer.events", "Events");
			case "gangwars" -> text("layer.gangwars", "Gangs");
			case "matches" -> text("layer.matches", "Matches");
			case "drones" -> text("layer.drones", "Drones");
			default -> Component.literal(id);
		};
	}

	static Component text(final String key, final String english, final Object... args) {
		return Component.translatableWithFallback("cyberblock.holo." + key, english, args);
	}

	private void updateButtons() {
		HoloWire.State s = HoloClient.state();
		if (this.hide != null) {
			boolean hidden = s != null && s.hidden();
			this.hide.setMessage(hidden ? text("hide.button_on", "My pin: hidden") : text("hide.button_off", "My pin: shown"));
			this.hide.active = s != null && !s.match();
		}

		if (this.action != null) {
			Entry e = selectedEntry();
			this.action.visible = e != null;
			if (e != null) {
				this.action.setMessage(e.point() != null ? text("fast_travel", "Fast travel") : text("spawn", "Spawn here"));
				this.action.active = e.point() != null || e.ok();
			}
		}
	}

	private void act() {
		if (go(selectedEntry())) {
			this.minecraft.gui.setScreen(null);
		}
	}

	/** Spawns at (or fast travels to) the entry; true when a request went (the view then closes). */
	static boolean go(final @Nullable Entry e) {
		if (e == null) {
			return false;
		}

		if (e.point() != null) {
			e.point().travel().run();
			return true;
		}

		if (e.kind() != null && e.ok()) {
			HoloClient.spawn(e.kind(), e.id());
			return true;
		}

		return false;
	}

	private void centreOnMe() {
		double[] c = startCentre();
		this.cam.centreOn(c[0], c[1]);
	}

	private void toFlat() {
		this.minecraft.gui.setScreen(flatScreen(this, this.cam.cx(), this.cam.cz(), (this.right() - this.left()) / (2.0 * this.cam.radius())));
	}

	@Override
	public void tick() {
		HoloWire.State s = HoloClient.state();
		List<String> layers = s == null ? List.of() : s.layers().stream().map(HoloWire.Layer::id).toList();
		if (!layers.equals(this.builtLayers)) {
			this.rebuildWidgets();
		}

		this.updateButtons();
	}

	// ---- the spawn list ----

	static @Nullable Entry selectedEntry() {
		return HoloEntries.find(HoloEntries.of(HoloClient.state(), HoloFastTravel.list()), selected);
	}

	static Component groupName(final String group) {
		return switch (group) {
			case "landmark" -> text("group.landmark", "Landmarks");
			case "beacon" -> text("group.beacon", "Spawn Beacons");
			case "base" -> text("group.base", "Team bases");
			case "friend" -> text("group.friend", "Join a friend");
			default -> text("group.travel", "Fast travel");
		};
	}

	/** The rows as drawn: a group header ("#group") before its first entry, then its entries' keys. */
	private static List<Object> rows(final List<Entry> entries) {
		List<Object> rows = new ArrayList<>();
		String group = null;
		for (Entry e : entries) {
			if (!e.group().equals(group)) {
				group = e.group();
				rows.add(groupName(group));
			}

			rows.add(e);
		}

		return rows;
	}

	// ---- drawing ----

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		this.hits.clear();
		McTheme.panel(graphics, this.left() - 4, this.top() - 4, this.right() - this.left() + 8, this.bottom() - this.top() + 8);
		graphics.fill(this.left(), this.top(), this.right(), this.bottom(), 0xFF050B12);
		graphics.enableScissor(this.left(), this.top(), this.right(), this.bottom());
		long t0 = System.nanoTime();
		this.drawTable(graphics);
		this.cost(System.nanoTime() - t0);
		HoloWire.State s = HoloClient.state();
		List<Entry> entries = HoloEntries.of(s, HoloFastTravel.list());
		this.drawLayers(graphics, s);
		this.drawMarkers(graphics, entries);
		this.drawPins(graphics, s);
		graphics.disableScissor();
		graphics.text(this.font, this.title, MARGIN, 9, McTheme.AQUA, true);
		graphics.text(this.font, this.status(s), MARGIN + this.font.width(this.title) + 8, 9, McTheme.GRAY, true);
		this.drawPanel(graphics, entries, mouseX, mouseY);
		super.extractRenderState(graphics, mouseX, mouseY, a);
		for (Hit h : this.hits) {
			if (mouseX >= h.x0() && mouseX < h.x1() && mouseY >= h.y0() && mouseY < h.y1() && this.onMap(mouseX, mouseY)) {
				graphics.setComponentTooltipForNextFrame(this.font, h.tooltip(), mouseX, mouseY);
				break;
			}
		}
	}

	private Component status(final HoloWire.@Nullable State s) {
		if (s == null) {
			return text("waiting", "Waiting for the table...");
		}

		Component players = text("status", "%s players on the table", s.pins().size());
		return s.cooldown() > 0 ? players.copy().append(" | ").append(text("status.cooldown", "next spawn in %s s", s.cooldown())) : players;
	}

	/** The 3D miniature: rebuilt from the map at most 4 times a second, drawn every frame. */
	private void drawTable(final GuiGraphicsExtractor graphics) {
		double sx0 = (this.left() + this.right()) / 2.0, sy0 = (this.top() + this.bottom()) / 2.0, scale = this.scale();
		AtlasTiles view = AtlasStore.view();
		List<HoloWire.Detail> detail = HoloClient.detail();
		long now = System.nanoTime();
		boolean moved = this.cam.cx() != this.builtCx || this.cam.cz() != this.builtCz || this.cam.radius() != this.builtRadius;
		boolean changed = view != this.builtView || view.version() != this.builtViewVersion || detail != this.builtDetail;
		if (this.grid == null || (moved || changed) && now - this.builtAt >= REBUILD_NANOS) {
			this.grid = HoloMesh.build(HoloMesh.N, this.cam.cx(), this.cam.cz(), this.cam.radius(), view::get, detail);
			this.builtAt = now;
			this.builtCx = this.cam.cx();
			this.builtCz = this.cam.cz();
			this.builtRadius = this.cam.radius();
			this.builtView = view;
			this.builtViewVersion = view.version();
			this.builtDetail = detail;
			this.cam.setBase(this.grid.base());
		}

		int span = AtlasTiles.TILE * AtlasTiles.CELL;
		double r = this.cam.radius();
		HoloClient.cover((int) Math.floor((this.cam.cx() - r) / span), (int) Math.floor((this.cam.cz() - r) / span), (int) Math.floor((this.cam.cx() + r) / span),
			(int) Math.floor((this.cam.cz() + r) / span));
		HoloMesh.quads(this.grid, this.cam, sx0, sy0, scale, this.quads);
		float[] d = this.quads.data();
		for (int q = 0; q < this.quads.count(); q++) {
			int i = q * HoloMesh.FLOATS;
			graphics.pose().pushMatrix();
			graphics.pose().mul(this.affine.set(d[i + 2], d[i + 3], d[i + 4], d[i + 5], d[i], d[i + 1]));
			graphics.fill(0, 0, 1, 1, Float.floatToRawIntBits(d[i + 6]));
			graphics.pose().popMatrix();
		}

		if (this.quads.count() <= 1) {
			// only the table's plate: nothing of Night City is known under the miniature
			graphics.centeredText(this.font, text("empty", "No Night City mapped here yet"), (int) sx0, (int) sy0, McTheme.GRAY);
		}
	}

	/** The 3D view's own cost; past the budget for BUDGET_FRAMES frames on average, 2D takes over for the session. */
	private void cost(final long nanos) {
		this.costEma = this.frames == 0 ? nanos : this.costEma * 0.9 + nanos * 0.1;
		if (++this.frames >= BUDGET_FRAMES && this.costEma > BUDGET_NANOS && !forced2D) {
			forced2D = true;
			Cyberblock.LOG.info(String.format(Locale.ROOT, "holo: the 3D table costs %.1f ms a frame here (budget %.1f ms): 2D mode (the Atlas) for this session",
				this.costEma / 1e6, BUDGET_NANOS / 1e6));
			this.minecraft.execute(() -> this.minecraft.gui.setScreen(flatScreen(null, this.cam.cx(), this.cam.cz(),
				(this.right() - this.left()) / (2.0 * this.cam.radius()))));
		}
	}

	/** The height of the miniature under (x, z): its column, else its base. */
	private double groundAt(final double x, final double z) {
		HoloMesh.Grid g = this.grid;
		if (g == null) {
			return this.cam.cy();
		}

		int i = (int) Math.floor((x - g.x0()) / g.step()), j = (int) Math.floor((z - g.z0()) / g.step());
		if (i < 0 || j < 0 || i >= g.n() || j >= g.n() || Float.isNaN(g.heights()[j * g.n() + i])) {
			return g.base();
		}

		return g.heights()[j * g.n() + i];
	}

	private void drawLayers(final GuiGraphicsExtractor graphics, final HoloWire.@Nullable State s) {
		if (s == null) {
			return;
		}

		double sx0 = (this.left() + this.right()) / 2.0, sy0 = (this.top() + this.bottom()) / 2.0, scale = this.scale();
		for (HoloWire.Layer layer : s.layers()) {
			if (!shownLayers.contains(layer.id())) {
				continue;
			}

			for (HoloWire.LayerItem item : layer.items()) {
				int x = (int) Math.round(this.cam.sx(item.x(), item.z(), sx0, scale));
				int y = (int) Math.round(this.cam.sy(item.x(), this.groundAt(item.x(), item.z()) + 1, item.z(), sy0, scale));
				int r = 2 + Math.round(item.v() * 5);
				int colour = 0xC0000000 | (item.colour() >= 0 ? item.colour() : 0x40E0FF);
				graphics.fill(x - r, y - r, x + r, y + r, colour);
				List<Component> tip = new ArrayList<>();
				tip.add(layerName(layer.id()).copy().withStyle(ChatFormatting.AQUA));
				if (!item.label().isEmpty()) {
					tip.add(Component.literal(item.label()));
				}

				this.hits.add(new Hit(x - r, y - r, x + r, y + r, "", tip));
			}
		}
	}

	/** Landmarks, beacons, team bases and fast-travel points on the table (friends are their pins). */
	private void drawMarkers(final GuiGraphicsExtractor graphics, final List<Entry> entries) {
		double sx0 = (this.left() + this.right()) / 2.0, sy0 = (this.top() + this.bottom()) / 2.0, scale = this.scale();
		for (Entry e : entries) {
			if (e.group().equals("friend")) {
				continue;
			}

			int x = (int) Math.round(this.cam.sx(e.x(), e.z(), sx0, scale));
			int y = (int) Math.round(this.cam.sy(e.x(), e.point() != null ? this.groundAt(e.x(), e.z()) : e.y(), e.z(), sy0, scale));
			if (!this.onMap(x, y)) {
				continue;
			}

			if (e.key().equals(selected)) {
				graphics.outline(x - 6, y - 12, 12, 12, McTheme.YELLOW);
			}

			marker(graphics, e, x, y - 6);
			this.hits.add(new Hit(x - 5, y - 11, x + 5, y - 1, e.key(), entryTooltip(e)));
		}
	}

	/** A spawn point's map decoration, 10 px, centred on (x, y): grey when the spawn is refused now. */
	static void marker(final GuiGraphicsExtractor graphics, final Entry e, final int x, final int y) {
		Identifier sprite = switch (e.group()) {
			case "landmark" -> LANDMARK;
			case "beacon" -> BEACON;
			case "base" -> BASE;
			default -> TRAVEL;
		};
		int colour = !e.ok() && e.point() == null ? 0xFF808080 : e.group().equals("landmark") ? 0xFF7FE8FF : 0xFFFFFFFF;
		decoration(graphics, sprite, x, y, colour, 10);
	}

	static List<Component> entryTooltip(final Entry e) {
		List<Component> tip = new ArrayList<>();
		tip.add(Component.literal(e.name()).withStyle(ChatFormatting.YELLOW));
		tip.add(groupName(e.group()).copy().withStyle(ChatFormatting.GRAY));
		if (!e.sub().isEmpty()) {
			tip.add(Component.literal(e.sub()).withStyle(ChatFormatting.DARK_AQUA));
		}

		if (!e.ok() && e.point() == null) {
			HoloWire.State s = HoloClient.state();
			tip.add(HoloText.refusal(e.why(), s == null ? 0 : s.cooldown()).copy().withStyle(ChatFormatting.RED));
		}

		return tip;
	}

	/** Every pin: a stalk from the ground, the head framed in the team colour (gold for you), [V], name and status. */
	private void drawPins(final GuiGraphicsExtractor graphics, final HoloWire.@Nullable State s) {
		if (s == null) {
			return;
		}

		double sx0 = (this.left() + this.right()) / 2.0, sy0 = (this.top() + this.bottom()) / 2.0, scale = this.scale();
		List<int[]> labels = new ArrayList<>();
		List<HoloWire.Pin> pins = new ArrayList<>(s.pins());
		pins.sort(Comparator.comparingDouble(p -> this.cam.depth(p.x(), p.z())));
		for (HoloWire.Pin p : pins) {
			int x = (int) Math.round(this.cam.sx(p.x(), p.z(), sx0, scale));
			int ground = (int) Math.round(this.cam.sy(p.x(), p.y(), p.z(), sy0, scale));
			int y = ground - 18;
			if (!this.onMap(x, ground)) {
				continue;
			}

			graphics.fill(x, y + 8, x + 1, ground, 0xC0FFFFFF);
			int frame = p.you() ? McTheme.GOLD : p.colour() >= 0 ? 0xFF000000 | p.colour() : 0xFFE0E0E0;
			String key = "friend:" + p.id();
			if (key.equals(selected)) {
				graphics.outline(x - 7, y - 3, 14, 14, McTheme.YELLOW);
			}

			graphics.fill(x - 5, y - 1, x + 5, y + 9, frame);
			PlayerFaceExtractor.extractRenderState(graphics, skin(p), x - 4, y, 8);
			int ix = x + 6;
			for (ItemStack status : statusIcons(p)) {
				graphics.pose().pushMatrix();
				graphics.pose().translate(ix, y);
				graphics.pose().scale(0.5F, 0.5F);
				graphics.item(status, 0, 0);
				graphics.pose().popMatrix();
				ix += 9;
			}

			Component name = pinName(p);
			int w = this.font.width(name), lx = x - w / 2, ly = y - 11;
			int[] box = {lx - 1, ly - 1, lx + w + 1, ly + 9};
			if (p.you() || labels.stream().noneMatch(b -> b[0] < box[2] && box[0] < b[2] && b[1] < box[3] && box[1] < b[3])) {
				labels.add(box);
				graphics.fill(box[0], box[1], box[2], box[3], 0x90000000);
				graphics.text(this.font, name, lx, ly, McTheme.WHITE, false);
			}

			this.hits.add(new Hit(x - 5, y - 1, x + 5, y + 9, p.you() ? "" : key, pinTooltip(p)));
		}
	}

	static Component pinName(final HoloWire.Pin p) {
		Component name = Component.literal(p.name());
		return p.linked() ? Component.literal("[V] ").withColor(V_COLOUR).append(name) : name;
	}

	static List<ItemStack> statusIcons(final HoloWire.Pin p) {
		List<ItemStack> out = new ArrayList<>();
		if (p.car()) {
			out.add(new ItemStack(Items.MINECART));
		}

		if (p.combat()) {
			out.add(new ItemStack(Items.IRON_SWORD));
		}

		if (p.downed()) {
			out.add(new ItemStack(Items.SKELETON_SKULL));
		}

		return out;
	}

	static List<Component> pinTooltip(final HoloWire.Pin p) {
		List<Component> tip = new ArrayList<>();
		tip.add(pinName(p).copy().withStyle(ChatFormatting.YELLOW));
		if (p.linked()) {
			tip.add(text("pin.linked", "Cyberpunk linked (V)").copy().withStyle(ChatFormatting.GOLD));
		}

		if (p.team() >= 0) {
			tip.add(text("pin.team", "Team %s", p.team() + 1).copy().withStyle(ChatFormatting.GRAY));
		}

		if (p.car()) {
			tip.add(text("pin.car", "In a car"));
		}

		if (p.combat()) {
			tip.add(text("pin.combat", "In combat").copy().withStyle(ChatFormatting.RED));
		}

		if (p.downed()) {
			tip.add(text("pin.downed", "Downed").copy().withStyle(ChatFormatting.DARK_RED));
		}

		if (!p.you()) {
			tip.add(text("pin.join", "Click, then Spawn here: join %s", p.name()).copy().withStyle(ChatFormatting.DARK_GRAY));
		}

		return tip;
	}

	static PlayerSkin skin(final HoloWire.Pin p) {
		var connection = Minecraft.getInstance().getConnection();
		PlayerInfo info = connection == null ? null : connection.getPlayerInfo(p.id());
		return info != null ? info.getSkin() : DefaultPlayerSkin.get(p.id());
	}

	static void decoration(final GuiGraphicsExtractor graphics, final Identifier sprite, final int x, final int y, final int colour, final int size) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, graphics.getSprite(new SpriteId(Sheets.MAP_DECORATIONS_SHEET, sprite)), x - size / 2, y - size / 2, size, size,
			colour);
	}

	/** The spawn list on the right, the selected entry's details under it. */
	private void drawPanel(final GuiGraphicsExtractor graphics, final List<Entry> entries, final int mouseX, final int mouseY) {
		int px = this.panelX(), top = this.top(), bottom = this.listBottom();
		McTheme.panel(graphics, px - 4, top - 4, PANEL + 8, this.bottom() - top + 8);
		graphics.fill(px, top, px + PANEL, bottom, 0xFF101820);
		List<Object> rows = rows(entries);
		int visible = (bottom - top) / ROW;
		this.scroll = Math.clamp(this.scroll, 0, Math.max(0, rows.size() - visible));
		graphics.enableScissor(px, top, px + PANEL, bottom);
		for (int r = this.scroll; r < Math.min(rows.size(), this.scroll + visible + 1); r++) {
			int y = top + (r - this.scroll) * ROW;
			if (rows.get(r) instanceof Component header) {
				graphics.text(this.font, header.copy().withStyle(ChatFormatting.AQUA), px + 2, y + 2, McTheme.WHITE, false);
				continue;
			}

			Entry e = (Entry) rows.get(r);
			boolean over = mouseX >= px && mouseX < px + PANEL && mouseY >= y && mouseY < y + ROW && mouseY < bottom;
			if (e.key().equals(selected)) {
				graphics.fill(px, y, px + PANEL, y + ROW, 0xFF2A4A5A);
			} else if (over) {
				graphics.fill(px, y, px + PANEL, y + ROW, 0xFF1C2C38);
			}

			String name = this.font.plainSubstrByWidth(e.name(), PANEL - 12);
			graphics.text(this.font, name, px + 8, y + 2, e.ok() || e.point() != null ? McTheme.WHITE : McTheme.DARK_GRAY, false);
		}

		graphics.disableScissor();
		if (rows.isEmpty()) {
			graphics.text(this.font, text("no_spawns", "No spawn points yet"), px + 4, top + 4, McTheme.GRAY, false);
		}

		Entry e = selectedEntry();
		int y = bottom + 4;
		if (e != null) {
			graphics.text(this.font, this.font.plainSubstrByWidth(e.name(), PANEL), px, y, McTheme.YELLOW, false);
			Component sub = e.sub().isEmpty() ? groupName(e.group()) : Component.literal(e.sub());
			graphics.text(this.font, sub, px, y + 10, McTheme.GRAY, false);
			if (!e.ok() && e.point() == null) {
				HoloWire.State s = HoloClient.state();
				graphics.textWithWordWrap(this.font, HoloText.refusal(e.why(), s == null ? 0 : s.cooldown()), px, y + 20, PANEL, McTheme.RED, false);
			}
		} else {
			graphics.textWithWordWrap(this.font, text("pick", "Pick a spawn point or a player"), px, y, PANEL, McTheme.GRAY, false);
		}
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		if (this.onList(event.x(), event.y())) {
			List<Object> rows = rows(HoloEntries.of(HoloClient.state(), HoloFastTravel.list()));
			int r = this.scroll + (int) ((event.y() - this.top()) / ROW);
			if (r >= 0 && r < rows.size() && rows.get(r) instanceof Entry e) {
				this.select(e.key());
				this.cam.centreOn(e.x(), e.z());
			}

			return true;
		}

		if (!this.onMap(event.x(), event.y())) {
			return false;
		}

		for (int i = this.hits.size() - 1; i >= 0; i--) {
			Hit h = this.hits.get(i);
			if (!h.key().isEmpty() && event.x() >= h.x0() && event.x() < h.x1() && event.y() >= h.y0() && event.y() < h.y1()) {
				this.select(h.key());
				return true;
			}
		}

		boolean pan = event.button() == 1 || event.hasShiftDown();
		this.panning = pan;
		this.rotating = !pan;
		return true;
	}

	private void select(final String key) {
		selected = key;
		this.updateButtons();
	}

	@Override
	public boolean mouseReleased(final MouseButtonEvent event) {
		this.rotating = false;
		this.panning = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
		if (this.panning) {
			this.cam.pan(dx, dy, this.scale());
			return true;
		}

		if (this.rotating) {
			this.cam.turn(dx);
			this.cam.tiltBy(dy);
			return true;
		}

		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
		if (scrollY != 0.0 && this.onMap(x, y)) {
			this.cam.zoom(Math.pow(1.25, scrollY));
			return true;
		}

		if (scrollY != 0.0 && this.onList(x, y)) {
			this.scroll -= (int) Math.signum(scrollY) * 3;
			return true;
		}

		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
