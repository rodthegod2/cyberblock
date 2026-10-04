package dev.cyberblock.client.mode.van;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.atlas.AtlasStore;
import dev.cyberblock.client.atlas.AtlasTiles;
import dev.cyberblock.client.atlas.AtlasWire;
import dev.cyberblock.client.holo.HoloCamera;
import dev.cyberblock.client.holo.HoloMesh;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.hack.actions.ActWire;
import dev.cyberblock.holo.HoloWire;
import dev.cyberblock.mode.van.VanRules;
import dev.cyberblock.mode.van.VanWire;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/**
 * The Van screen (megaplan task 8-V1): on the left a 3D miniature of the linked host's area (the Holo-Table's HoloMesh and
 * HoloCamera, scoped to {@link VanRules#MINI_RADIUS} around V) with every target the Van sees on it; on the right the host's live
 * photo feed (6-D1, drawn like its feed map; the map item itself one click away) and the target list. Click a target (on the
 * miniature or in the list) for the Hack Wheel. Drag turns and tilts the miniature, the wheel zooms. The bottom row launches or
 * flies the drone and docks it; the top row picks the host among those in the Van's reach.
 */
final class VanScreen extends Screen {
	static final int MARGIN = 8, TOP = 26, BOTTOM = 30, PANEL = 140, ROW = 11, FEED = 128;
	/** A press that moved this far (GUI px) is a drag; a marker this close picks. */
	static final double DRAG_PX = 3.0, PICK_PX = 6.0;
	static final long REBUILD_NANOS = 250_000_000L;
	static final int TILE_TICKS = 10;
	static final long MISSING_NANOS = 10_000_000_000L, KNOWN_NANOS = 60_000_000_000L;
	static final int PERSON = 0xFFEFEFEF, HOSTILE = 0xFFFF5555, CAR = 0xFFFFC040, DEVICE = 0xFF40E0FF, V = 0xFFFCEE0A;
	private static final Map<Long, Long> asked = new HashMap<>();
	private final HoloCamera cam = new HoloCamera(0.0, 0.0, VanRules.MINI_RADIUS);
	private final HoloMesh.Quads quads = new HoloMesh.Quads();
	private final Matrix3x2f affine = new Matrix3x2f();
	private HoloMesh.@Nullable Grid grid;
	private long builtAt;
	private double builtCx = Double.NaN, builtCz, builtRadius;
	private int builtViewVersion = -1;
	private boolean dragging, dragged;
	private double pressX, pressY;
	private int scroll;
	private int ticks;
	private @Nullable Button flyButton, dockButton, hostButton;
	/** Markers drawn this frame (for clicks and tooltips). */
	private final List<Marker> markers = new ArrayList<>();

	private record Marker(int x, int y, VanWire.Target target) {
	}

	VanScreen() {
		super(Component.translatableWithFallback("cyberblock.van.title", "Netrunner Van"));
	}

	// ---- layout -------------------------------------------------------------------------------------------------------------

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

	private int listTop() {
		return TOP + FEED + 30;
	}

	private boolean onMini(final double x, final double y) {
		return x >= this.left() && x < this.right() && y >= this.top() && y < this.bottom();
	}

	private double scale() {
		return Math.min(this.right() - this.left(), this.bottom() - this.top()) / (2.0 * this.cam.radius());
	}

	// ---- widgets ------------------------------------------------------------------------------------------------------------

	@Override
	protected void init() {
		int y = this.height - BOTTOM + 5;
		int x = MARGIN;
		this.flyButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> VanClient.fly(this.minecraft)).bounds(x, y, 100, 20).build());
		this.dockButton = this.addRenderableWidget(Button.builder(text("dock", "Dock drone"), b -> VanClient.dock()).bounds(x += 102, y, 80, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.cam.zoom(1.25)).bounds(x += 84, y, 18, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("-"), b -> this.cam.zoom(1 / 1.25)).bounds(x += 20, y, 18, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("⟲"), b -> this.cam.turn(Math.PI / 4 / 0.01)).bounds(x += 20, y, 18, 20)
			.tooltip(Tooltip.create(text("turn", "Turn the miniature"))).build());
		int px = this.panelX();
		this.addRenderableWidget(Button.builder(text("take_map", "Take feed map"), b -> this.takeMap()).bounds(px, TOP + FEED + 4, PANEL, 20)
			.tooltip(Tooltip.create(text("take_map.tip", "The host's photo feed as a map item (6-D1)"))).build());
		this.hostButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.nextHost()).bounds(this.width - MARGIN - 170, 3, 170, 20).build());
		this.updateButtons();
		VanWire.State s = VanClient.state();
		if (s != null) {
			VanClient.watch(s, true);
		}
	}

	static Component text(final String key, final String english, final Object... args) {
		return Component.translatableWithFallback("cyberblock.van." + key, english, args);
	}

	private void updateButtons() {
		VanWire.State s = VanClient.state();
		if (this.flyButton != null) {
			boolean flying = s != null && s.drone() != null;
			this.flyButton.setMessage(flying ? text("fly", "Fly drone") : text("launch", "Launch drone"));
			this.flyButton.active = s != null && (flying || s.charge() >= VanRules.LAUNCH_MIN / (float) VanRules.BATTERY);
		}

		if (this.dockButton != null) {
			this.dockButton.active = s != null && s.drone() != null;
		}

		if (this.hostButton != null) {
			VanWire.Host h = host(s);
			this.hostButton.setMessage(h == null ? text("no_host_short", "Host: none in reach")
				: text("host", "Host: %s", h.name()).copy().withStyle(h.open() ? ChatFormatting.WHITE : ChatFormatting.RED));
			this.hostButton.active = s != null && s.hosts().size() > 1;
		}
	}

	static VanWire.@Nullable Host host(final VanWire.@Nullable State s) {
		if (s == null || s.host() == null) {
			return null;
		}

		return s.hosts().stream().filter(h -> h.id().equals(s.host())).findFirst().orElse(null);
	}

	private void nextHost() {
		VanWire.State s = VanClient.state();
		if (s == null || s.hosts().isEmpty()) {
			return;
		}

		int i = s.hosts().indexOf(host(s));
		VanWire.Host next = s.hosts().get((i + 1) % s.hosts().size());
		VanClient.send(VanWire.T_VAN, VanWire.linkHost(next.id()));
	}

	private void takeMap() {
		VanWire.State s = VanClient.state();
		if (s != null && s.host() != null) {
			VanClient.send(ActWire.T_FEEDMAP, ActWire.host(s.host()));
		}
	}

	@Override
	public void tick() {
		this.ticks++;
		this.updateButtons();
		VanWire.State s = VanClient.state();
		if (s != null && s.centre() != null) {
			this.cam.centreOn(s.centre().x, s.centre().z);
		}

		if (this.ticks % TILE_TICKS == 0 && AtlasStore.view() != AtlasStore.tiles) {
			this.askTiles();
		}
	}

	/** A guest's Atlas shows the server's shared map: ask for its tiles under the miniature (as the Holo-Table does). */
	private void askTiles() {
		int span = AtlasTiles.TILE * AtlasTiles.CELL;
		double r = this.cam.radius();
		int tx0 = (int) Math.floor((this.cam.cx() - r) / span), tx1 = (int) Math.floor((this.cam.cx() + r) / span);
		int tz0 = (int) Math.floor((this.cam.cz() - r) / span), tz1 = (int) Math.floor((this.cam.cz() + r) / span);
		AtlasTiles view = AtlasStore.view();
		long now = System.nanoTime();
		List<long[]> due = new ArrayList<>();
		for (int tz = tz0; tz <= tz1; tz++) {
			for (int tx = tx0; tx <= tx1 && due.size() < AtlasWire.MAX_MAPTILE_TILES / 2; tx++) {
				long key = AtlasTiles.key(tx, tz);
				Long at = asked.get(key);
				if (at == null || now - at >= (view.hasTile(tx, tz) ? KNOWN_NANOS : MISSING_NANOS)) {
					asked.put(key, now);
					due.add(new long[] {tx, tz, 0L});
				}
			}
		}

		if (!due.isEmpty()) {
			VanClient.send("maptile", AtlasWire.mapTileRequest(due));
		}
	}

	@Override
	public void removed() {
		VanWire.State s = VanClient.state();
		if (s != null) {
			VanClient.watch(s, false);
		}

		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---- drawing ------------------------------------------------------------------------------------------------------------

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		VanWire.State s = VanClient.state();
		this.markers.clear();
		McTheme.panel(graphics, this.left() - 4, this.top() - 4, this.right() - this.left() + 8, this.bottom() - this.top() + 8);
		graphics.fill(this.left(), this.top(), this.right(), this.bottom(), 0xFF050B12);
		graphics.enableScissor(this.left(), this.top(), this.right(), this.bottom());
		if (s != null && s.centre() != null) {
			this.drawMini(graphics, s);
		} else {
			graphics.centeredText(this.font, s == null ? text("waiting", "Waiting for the Van...")
				: text("no_host", "No Cyberpunk host within %s blocks of the Van", (int) VanRules.LINK_RANGE), (this.left() + this.right()) / 2,
				(this.top() + this.bottom()) / 2, McTheme.GRAY);
		}

		graphics.disableScissor();
		graphics.text(this.font, this.title, MARGIN, 9, McTheme.AQUA, true);
		graphics.text(this.font, this.status(s), MARGIN + this.font.width(this.title) + 8, 9, McTheme.GRAY, true);
		this.drawFeed(graphics, s);
		VanWire.Target listHover = this.drawList(graphics, s, mouseX, mouseY);
		this.drawBattery(graphics, s);
		super.extractRenderState(graphics, mouseX, mouseY, a);
		VanWire.Target hover = listHover != null ? listHover : this.markerAt(mouseX, mouseY);
		if (hover != null) {
			graphics.setComponentTooltipForNextFrame(this.font, tooltip(hover), mouseX, mouseY);
		}
	}

	private Component status(final VanWire.@Nullable State s) {
		if (s == null) {
			return Component.empty();
		}

		long drone = s.targets().stream().filter(t -> t.seen() == VanRules.Seen.DRONE).count();
		return text("status", "%s targets seen (%s by the drone)", s.targets().size(), drone);
	}

	/** The miniature of the host's area: rebuilt at most 4 times a second, drawn every frame, with V, the drone and the targets. */
	private void drawMini(final GuiGraphicsExtractor graphics, final VanWire.State s) {
		double sx0 = (this.left() + this.right()) / 2.0, sy0 = (this.top() + this.bottom()) / 2.0, scale = this.scale();
		AtlasTiles view = AtlasStore.view();
		long now = System.nanoTime();
		boolean moved = this.cam.cx() != this.builtCx || this.cam.cz() != this.builtCz || this.cam.radius() != this.builtRadius;
		if (this.grid == null || (moved || view.version() != this.builtViewVersion || now - this.builtAt > 1_000_000_000L) && now - this.builtAt >= REBUILD_NANOS) {
			int r = (int) Math.ceil(this.cam.radius());
			List<HoloWire.Detail> detail = this.minecraft.level == null ? List.of()
				: List.of(VanMini.detail((int) Math.floor(this.cam.cx()) - r, (int) Math.floor(this.cam.cz()) - r, 2 * r + 1, VanMini.of(this.minecraft.level)));
			this.grid = HoloMesh.build(HoloMesh.N, this.cam.cx(), this.cam.cz(), this.cam.radius(), view::get, detail);
			this.builtAt = now;
			this.builtCx = this.cam.cx();
			this.builtCz = this.cam.cz();
			this.builtRadius = this.cam.radius();
			this.builtViewVersion = view.version();
			this.cam.setBase(this.grid.base());
		}

		HoloMesh.quads(this.grid, this.cam, sx0, sy0, scale, this.quads);
		float[] d = this.quads.data();
		for (int q = 0; q < this.quads.count(); q++) {
			int i = q * 7;
			graphics.pose().pushMatrix();
			graphics.pose().mul(this.affine.set(d[i + 2], d[i + 3], d[i + 4], d[i + 5], d[i], d[i + 1]));
			graphics.fill(0, 0, 1, 1, Float.floatToRawIntBits(d[i + 6]));
			graphics.pose().popMatrix();
		}

		if (this.quads.count() <= 1) {
			graphics.centeredText(this.font, text("unmapped", "No Night City mapped here yet"), (int) sx0, this.bottom() - 14, McTheme.GRAY);
		}

		// V, then the targets, then the drone on top
		this.dot(graphics, s.centre(), V, 4, true);
		for (VanWire.Target t : s.targets()) {
			int[] at = this.dot(graphics, t.p(), colour(t), t.kind() == VanRules.Kind.CAR ? 3 : 2, t.seen() == VanRules.Seen.DRONE);
			this.markers.add(new Marker(at[0], at[1], t));
		}

		if (s.drone() != null && this.minecraft.level != null && this.minecraft.level.getEntity(s.drone().eid()) != null) {
			this.dot(graphics, this.minecraft.level.getEntity(s.drone().eid()).position(), McTheme.AQUA, 3, true);
		}
	}

	static int colour(final VanWire.Target t) {
		return switch (t.kind()) {
			case PERSON -> t.hostile() ? HOSTILE : PERSON;
			case CAR -> CAR;
			case DEVICE -> DEVICE;
		};
	}

	/** A square marker over the miniature at a world point (outlined when `ring`); returns its screen place. */
	private int[] dot(final GuiGraphicsExtractor graphics, final Vec3 p, final int colour, final int r, final boolean ring) {
		double sx0 = (this.left() + this.right()) / 2.0, sy0 = (this.top() + this.bottom()) / 2.0, scale = this.scale();
		int x = (int) Math.round(this.cam.sx(p.x, p.z, sx0, scale)), y = (int) Math.round(this.cam.sy(p.x, p.y + 1.0, p.z, sy0, scale));
		graphics.fill(x - r, y - r, x + r + 1, y + r + 1, colour);
		if (ring) {
			graphics.outline(x - r - 2, y - r - 2, 2 * r + 5, 2 * r + 5, McTheme.AQUA);
		}

		return new int[] {x, y};
	}

	private VanWire.@Nullable Target markerAt(final double mx, final double my) {
		if (!this.onMini(mx, my)) {
			return null;
		}

		Marker best = null;
		double bestD = PICK_PX;
		for (Marker m : this.markers) {
			double d = Math.hypot(m.x() - mx, m.y() - my);
			if (d <= bestD) {
				bestD = d;
				best = m;
			}
		}

		return best == null ? null : best.target();
	}

	/** The host's photo feed in the panel's top, framed like a map. */
	private void drawFeed(final GuiGraphicsExtractor graphics, final VanWire.@Nullable State s) {
		int x = this.panelX() + (PANEL - FEED) / 2, y = TOP;
		McTheme.panel(graphics, x - 4, y - 4, FEED + 8, FEED + 8);
		VanWire.Host h = host(s);
		if (h == null || !VanFeed.draw(graphics, h.id(), x, y, FEED)) {
			graphics.fill(x, y, x + FEED, y + FEED, 0xFF202020);
			Component why = h == null ? text("feed_none", "No host linked") : !h.open() ? text("feed_closed", "This host lets no guest watch")
				: text("feed_wait", "Waiting for the host's picture...");
			List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(why, FEED - 8);
			for (int i = 0; i < lines.size(); i++) {
				graphics.centeredText(this.font, lines.get(i), x + FEED / 2, y + FEED / 2 - 4 * lines.size() + 9 * i, McTheme.GRAY);
			}
		} else if (VanFeed.age(h.id()) > 3) {
			graphics.text(this.font, VanFeed.age(h.id()) + " s", x + 2, y + FEED - 10, McTheme.RED, true);
		}
	}

	/** The targets as a list under the feed; returns the hovered one. */
	private VanWire.@Nullable Target drawList(final GuiGraphicsExtractor graphics, final VanWire.@Nullable State s, final int mx, final int my) {
		int px = this.panelX(), top = this.listTop(), bottom = this.bottom();
		graphics.fill(px, top - 2, px + PANEL, bottom, 0xFF101820);
		if (s == null) {
			return null;
		}

		List<VanWire.Target> targets = s.targets();
		int rows = (bottom - top) / ROW;
		this.scroll = Math.clamp(this.scroll, 0, Math.max(0, targets.size() - rows));
		if (targets.isEmpty()) {
			graphics.text(this.font, text("no_targets", "Nothing in sight"), px + 4, top + 2, McTheme.GRAY, false);
			return null;
		}

		VanWire.Target hover = null;
		Vec3 from = s.centre() != null ? s.centre() : Vec3.ZERO;
		for (int i = 0; i < rows && this.scroll + i < targets.size(); i++) {
			VanWire.Target t = targets.get(this.scroll + i);
			int y = top + i * ROW;
			boolean over = mx >= px && mx < px + PANEL && my >= y && my < y + ROW;
			if (over) {
				graphics.fill(px, y, px + PANEL, y + ROW, 0xFF2A4A5A);
				hover = t;
			}

			graphics.fill(px + 3, y + 3, px + 8, y + 8, colour(t));
			String dist = String.format(Locale.ROOT, "%.0f m", t.p().distanceTo(from));
			String name = this.font.plainSubstrByWidth(name(t).getString(), PANEL - 24 - this.font.width(dist));
			graphics.text(this.font, name, px + 11, y + 2, t.seen() == VanRules.Seen.DRONE ? McTheme.AQUA : McTheme.WHITE, false);
			graphics.text(this.font, dist, px + PANEL - 3 - this.font.width(dist), y + 2, McTheme.GRAY, false);
		}

		return hover;
	}

	private void drawBattery(final GuiGraphicsExtractor graphics, final VanWire.@Nullable State s) {
		if (s == null) {
			return;
		}

		int x = MARGIN + 270, y = this.height - BOTTOM + 11;
		float charge = s.drone() != null ? s.drone().battery() : s.charge();
		Component line = s.drone() != null
			? text("battery_flying", "Drone: %s %% at %s m", Math.round(charge * 100), Math.round(s.drone().range()))
			: text("battery_docked", "Drone docked: %s %%", Math.round(charge * 100));
		graphics.text(this.font, line, x, y, charge < 0.2F ? McTheme.RED : McTheme.GRAY, true);
	}

	/** A target's name: the figure's tag, else what it is. */
	static Component name(final VanWire.Target t) {
		if (!t.name().isEmpty()) {
			return Component.literal(t.name());
		}

		return switch (t.kind()) {
			case PERSON -> text("kind.person", "Person");
			case CAR -> text("kind.car", "Car");
			case DEVICE -> text("device." + t.type(), t.type().isEmpty() ? "Device" : Character.toUpperCase(t.type().charAt(0)) + t.type().substring(1));
		};
	}

	static List<Component> tooltip(final VanWire.Target t) {
		List<Component> out = new ArrayList<>();
		out.add(name(t).copy().withStyle(t.hostile() ? ChatFormatting.RED : ChatFormatting.WHITE));
		out.add(switch (t.kind()) {
			case PERSON -> text("kind.person", "Person");
			case CAR -> text("kind.car", "Car");
			case DEVICE -> text("kind.device", "Device: %s", t.type());
		});
		out.add((t.seen() == VanRules.Seen.DRONE ? text("seen.drone", "Seen by your drone") : text("seen.screen", "In the host's area")).copy()
			.withStyle(ChatFormatting.DARK_AQUA));
		out.add(text("click", "Click: Hack Wheel").copy().withStyle(ChatFormatting.DARK_GRAY));
		return out;
	}

	// ---- input --------------------------------------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		VanWire.State s = VanClient.state();
		int px = this.panelX(), top = this.listTop();
		if (s != null && event.x() >= px && event.x() < px + PANEL && event.y() >= top && event.y() < this.bottom()) {
			int i = this.scroll + (int) ((event.y() - top) / ROW);
			if (i >= 0 && i < s.targets().size()) {
				this.wheel(s.targets().get(i));
				return true;
			}
		}

		if (!this.onMini(event.x(), event.y())) {
			return false;
		}

		this.dragging = true;
		this.dragged = false;
		this.pressX = event.x();
		this.pressY = event.y();
		return true;
	}

	@Override
	public boolean mouseReleased(final MouseButtonEvent event) {
		boolean click = this.dragging && !this.dragged;
		this.dragging = false;
		if (click) {
			VanWire.Target t = this.markerAt(event.x(), event.y());
			if (t != null) {
				this.wheel(t);
			}

			return true;
		}

		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
		if (this.dragging) {
			this.dragged |= Math.hypot(event.x() - this.pressX, event.y() - this.pressY) > DRAG_PX;
			if (this.dragged) {
				this.cam.turn(dx);
				this.cam.tiltBy(dy);
			}

			return true;
		}

		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
		if (scrollY != 0.0 && this.onMini(x, y)) {
			this.cam.zoom(Math.pow(1.15, scrollY));
			return true;
		}

		if (scrollY != 0.0 && x >= this.panelX()) {
			this.scroll -= (int) Math.signum(scrollY);
			return true;
		}

		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	private void wheel(final VanWire.Target t) {
		Cyberblock.LOG.debug("van: wheel on {} {}", t.kind().wire(), t.name());
		this.minecraft.gui.setScreen(new VanWheelScreen(t, false));
	}
}
