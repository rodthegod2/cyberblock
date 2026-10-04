package dev.cyberblock.client.hack.screen;

import dev.cyberblock.ProxyEntities;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.client.atlas.AtlasTextures;
import dev.cyberblock.client.atlas.AtlasTiles;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.client.ui.screen.AtlasView;
import dev.cyberblock.hack.actions.ActWire;
import dev.cyberblock.hack.actions.RemoteHack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Net Map (megaplan task 6-I1, an item): Night City from above on the Atlas's map tiles (AtlasView and AtlasTextures, composed
 * here; the Atlas screen itself is unchanged), with the linked hosts, the cameras their games stream, the car figures this client
 * sees and the team's pings. A mode picks what a click on the map does: open a camera's feed, open a car's Vehicle Panel, outline
 * the figures around a point for the team, ping there, or gridlock the road there. The top buttons act on the selected host: black
 * out its district for 1, 2 or 5 minutes, switch its district's traffic lights, show its photo feed (the DLL's picture as a map) or
 * take its feed map item. When the Phase 0 (h) fallback is on, a ring shows the 50 m around each V that remote hacks reach.
 */
final class NetMapScreen extends Screen {
	private static final int MARGIN = 8, TOP = 26, BOTTOM = 52;
	private static final int PARCHMENT = 0xFFDCC896, GRID = 0x18402810, LABEL = 0xFF3B2A14;
	private static final double WHEEL_STEP = 1.25, REGION_ZOOM = 0.25;
	/** A click this close (GUI px) to a marker picks it. */
	private static final double PICK_PX = 8.0;
	/** A press that moved this far (GUI px) is a drag, not a click. */
	private static final double DRAG_PX = 3.0;
	private static final int FEED_PX = 128;
	private static final int[] BLACKOUT_MINUTES = {1, 2, 5};
	private static final Identifier PLAYER = Identifier.withDefaultNamespace("player");
	private static final Identifier TARGET = Identifier.withDefaultNamespace("target_x");

	/** What a click on the map does. */
	enum Mode {
		CAMERA(RemoteHack.CAM_FEED), VEHICLE(null), OUTLINE(RemoteHack.OUTLINE), PING(RemoteHack.PING_AREA), GRIDLOCK(RemoteHack.GRIDLOCK);

		final @Nullable RemoteHack hack;

		Mode(final @Nullable RemoteHack hack) {
			this.hack = hack;
		}

		Component label() {
			return this.hack != null ? ToolScreen.hackName(this.hack) : Component.translatableWithFallback("cyberblock.hack.vehicle.title", "Vehicle Panel");
		}
	}

	private final AtlasView view;
	private Mode mode = Mode.CAMERA;
	private int blackoutIndex = 1;
	private boolean feed;
	private boolean dragging, dragged;
	private double pressX, pressY;
	private int ticks;
	private long sentAt = Long.MAX_VALUE;
	private Component status = Component.empty();
	private int statusColour = McTheme.GRAY;
	private final List<Button> modeButtons = new ArrayList<>();
	private @Nullable Button hostButton, blackoutButton, feedButton;

	NetMapScreen() {
		super(Component.translatableWithFallback("cyberblock.hack.netmap.title", "Net Map"));
		NetState.Host h = ActionsClient.selectedHost();
		LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
		Vec3 centre = h != null ? h.p() : player != null ? player.position() : Vec3.ZERO;
		this.view = new AtlasView(centre.x, centre.z, 0.75);
	}

	@Override
	protected void init() {
		this.modeButtons.clear();
		int y1 = this.height - BOTTOM + 4, y2 = y1 + 24;
		int avail = this.width - 2 * MARGIN;
		// the five modes share the first row; the second holds the host's district hacks, the feed map and the zoom
		int modeW = (avail - 4 * 2) / Mode.values().length;
		int x = MARGIN;
		for (Mode m : Mode.values()) {
			Button b = Button.builder(m.label(), press -> {
				this.mode = m;
				this.updateLabels();
			}).bounds(x, y1, modeW, 20).build();
			this.modeButtons.add(this.addRenderableWidget(b));
			x += modeW + 2;
		}

		int rest = avail - 68 - 16 - 3 * 2;
		int blackoutW = rest * 36 / 100, trafficW = rest * 34 / 100, feedW = rest - blackoutW - trafficW;
		x = MARGIN;
		this.blackoutButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.blackout()).bounds(x, y2, blackoutW, 20).build());
		x += blackoutW + 2;
		this.addRenderableWidget(Button.builder(Component.literal("±"), b -> {
			this.blackoutIndex = (this.blackoutIndex + 1) % BLACKOUT_MINUTES.length;
			this.updateLabels();
		}).bounds(x, y2, 16, 20).build());
		x += 18;
		this.addRenderableWidget(Button.builder(ToolScreen.hackName(RemoteHack.TRAFFIC_SWITCH), b -> this.districtHack(RemoteHack.TRAFFIC_SWITCH, 0))
			.bounds(x, y2, trafficW, 20).build());
		x += trafficW + 2;
		this.addRenderableWidget(Button.builder(Component.translatableWithFallback("cyberblock.hack.netmap.take_feed", "Take feed map"), b -> this.takeFeedMap())
			.bounds(x, y2, feedW, 20).build());
		int right = this.width - MARGIN;
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.zoom(WHEEL_STEP * WHEEL_STEP)).bounds(right - 64, y2, 20, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("-"), b -> this.zoom(1.0 / (WHEEL_STEP * WHEEL_STEP))).bounds(right - 42, y2, 20, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatableWithFallback("cyberblock.hack.netmap.centre", "V"), b -> this.centreOnHost())
			.bounds(right - 20, y2, 20, 20).build());
		this.hostButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			NetState.Host next = ActionsClient.state().next(ActionsClient.selected);
			ActionsClient.selected = next == null ? null : next.id();
			this.centreOnHost();
			this.updateLabels();
		}).bounds(right - 236, 3, 170, 20).build());
		this.feedButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.toggleFeed()).bounds(right - 62, 3, 62, 20).build());
		this.updateLabels();
	}

	private void updateLabels() {
		for (int i = 0; i < this.modeButtons.size(); i++) {
			Mode m = Mode.values()[i];
			Component label = m.label();
			this.modeButtons.get(i).setMessage(m == this.mode ? label.copy().withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE) : label);
		}

		NetState.Host h = ActionsClient.selectedHost();
		if (this.hostButton != null) {
			this.hostButton.setMessage(ToolScreen.hostLabel(h));
		}

		if (this.blackoutButton != null) {
			Component label = Component.translatableWithFallback("cyberblock.hack.netmap.blackout_minutes", "Blackout: %s min", BLACKOUT_MINUTES[this.blackoutIndex]);
			this.blackoutButton.setMessage(h != null && h.blackout() > 0 ? label.copy().withStyle(ChatFormatting.DARK_GRAY) : label);
		}

		if (this.feedButton != null) {
			Component label = Component.translatableWithFallback("cyberblock.hack.netmap.feed", "Photo feed");
			this.feedButton.setMessage(this.feed ? label.copy().withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE) : label);
		}
	}

	// ---- actions ------------------------------------------------------------------------------------------------------------

	private void blackout() {
		this.districtHack(RemoteHack.BLACKOUT, BLACKOUT_MINUTES[this.blackoutIndex] * 60);
	}

	private void districtHack(final RemoteHack hack, final int secs) {
		NetState.Host h = ActionsClient.selectedHost();
		if (h == null) {
			this.status(Component.translatableWithFallback("cyberblock.hack.netmap.no_host", "No linked Cyberpunk host"), McTheme.RED);
			return;
		}

		this.sent(ActionsClient.send(ActWire.T_RHACK, ActWire.district(hack, h.id(), secs)), hack);
	}

	private void toggleFeed() {
		NetState.Host h = ActionsClient.selectedHost();
		this.feed = !this.feed;
		if (h != null) {
			ActionsClient.send(ActWire.T_FEED, ActWire.feedWatch(h.id(), this.feed));
		}

		this.updateLabels();
	}

	private void takeFeedMap() {
		NetState.Host h = ActionsClient.selectedHost();
		if (h != null) {
			ActionsClient.send(ActWire.T_FEEDMAP, ActWire.host(h.id()));
		}
	}

	private void sent(final boolean ok, final RemoteHack hack) {
		this.sentAt = net.minecraft.util.Util.getMillis();
		this.status(ok ? Component.translatableWithFallback("cyberblock.rhack.sending", "%s: sending...", ToolScreen.hackName(hack))
			: Component.translatableWithFallback("cyberblock.rhack.offline", "This server has no Cyberblock link"), ok ? McTheme.WHITE : McTheme.RED);
	}

	private void status(final Component text, final int colour) {
		this.status = text;
		this.statusColour = colour;
	}

	/** A click on the map at screen (sx, sy): pick a host, else do the mode's action there. */
	private void click(final double sx, final double sy) {
		NetState state = ActionsClient.state();
		for (NetState.Host h : state.hosts()) {
			if (this.near(h.p(), sx, sy)) {
				ActionsClient.selected = h.id();
				this.updateLabels();
				return;
			}
		}

		switch (this.mode) {
			case CAMERA -> {
				NetState.Cam cam = null;
				for (NetState.Cam c : state.cams()) {
					if (this.near(c.p(), sx, sy) && (cam == null || this.px(c.p(), sx, sy) < this.px(cam.p(), sx, sy))) {
						cam = c;
					}
				}

				if (cam != null) {
					this.sent(ActionsClient.send(ActWire.T_RHACK, ActWire.camera(cam.host(), cam.id())), RemoteHack.CAM_FEED);
				}
			}
			case VEHICLE -> {
				ProxyEntity car = null;
				for (ProxyEntity v : this.cars()) {
					if (this.near(v.position(), sx, sy) && (car == null || this.px(v.position(), sx, sy) < this.px(car.position(), sx, sy))) {
						car = v;
					}
				}

				if (car != null) {
					this.minecraft.gui.setScreen(new VehiclePanelScreen(car.getId(), car.label()));
				}
			}
			default -> {
				double wx = this.view.worldX(sx, this.viewX()), wz = this.view.worldZ(sy, this.viewY());
				NetState.Host near = state.nearestActive(wx, wz);
				double wy = near != null ? near.p().y : this.minecraft.player != null ? this.minecraft.player.getY() : 64.0;
				RemoteHack hack = this.mode.hack;
				this.sent(ActionsClient.send(ActWire.T_RHACK, ActWire.point(hack, new Vec3(wx, wy, wz))), hack);
			}
		}
	}

	/** The car figures this client tracks (alive). */
	private List<ProxyEntity> cars() {
		List<ProxyEntity> out = new ArrayList<>();
		LocalPlayer player = this.minecraft.player;
		if (this.minecraft.level == null || player == null) {
			return out;
		}

		for (Entity e : this.minecraft.level.getEntities((Entity) null, new AABB(player.position(), player.position()).inflate(512.0),
			e -> e.getType() == ProxyEntities.VEHICLE)) {
			if (e instanceof ProxyEntity proxy && !proxy.dead()) {
				out.add(proxy);
			}
		}

		return out;
	}

	private double px(final Vec3 p, final double sx, final double sy) {
		return Math.hypot(this.view.screenX(p.x, this.viewX()) - sx, this.view.screenY(p.z, this.viewY()) - sy);
	}

	private boolean near(final Vec3 p, final double sx, final double sy) {
		return this.px(p, sx, sy) <= PICK_PX;
	}

	private void zoom(final double factor) {
		this.view.zoomAt(factor, this.viewX(), this.viewY(), this.viewX(), this.viewY());
	}

	private void centreOnHost() {
		NetState.Host h = ActionsClient.selectedHost();
		if (h != null) {
			this.view.centreOn(h.p().x, h.p().z);
		} else if (this.minecraft.player != null) {
			this.view.centreOn(this.minecraft.player.getX(), this.minecraft.player.getZ());
		}
	}

	// ---- layout -------------------------------------------------------------------------------------------------------------

	private int left() {
		return MARGIN;
	}

	private int right() {
		return this.width - MARGIN;
	}

	private int top() {
		return TOP;
	}

	private int bottom() {
		return this.height - BOTTOM;
	}

	private double viewX() {
		return (this.left() + this.right()) / 2.0;
	}

	private double viewY() {
		return (this.top() + this.bottom()) / 2.0;
	}

	private boolean onMap(final double x, final double y) {
		return x >= this.left() && x < this.right() && y >= this.top() && y < this.bottom();
	}

	// ---- per tick and drawing ------------------------------------------------------------------------------------------------

	@Override
	public void tick() {
		if (this.ticks++ % 20 == 0) {
			ActionsClient.requestState();
			this.updateLabels();
		}

		NetState.Host h = ActionsClient.selectedHost();
		if (this.feed && h != null && this.ticks % 40 == 1) {
			// the watch lasts 5 s on the server: renewed every 2 s while the feed is shown
			ActionsClient.send(ActWire.T_FEED, ActWire.feedWatch(h.id(), true));
		}

		ActionsClient.Result r = ActionsClient.lastSince(this.sentAt);
		if (r != null) {
			RemoteHack hack = RemoteHack.byWire(r.hack());
			Component name = hack == null ? Component.literal(r.hack()) : ToolScreen.hackName(hack);
			this.status(r.ok() ? Component.translatableWithFallback("cyberblock.rhack.done", "%s: done", name)
				: Component.translatableWithFallback("cyberblock.rhack.failed", "%s failed: %s", name, r.reason()), r.ok() ? McTheme.GREEN : McTheme.RED);
			this.sentAt = Long.MAX_VALUE;
		}
	}

	@Override
	public void removed() {
		NetState.Host h = ActionsClient.selectedHost();
		if (this.feed && h != null) {
			ActionsClient.send(ActWire.T_FEED, ActWire.feedWatch(h.id(), false));
		}

		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		McTheme.panel(graphics, this.left() - 4, this.top() - 4, this.right() - this.left() + 8, this.bottom() - this.top() + 8);
		graphics.fill(this.left(), this.top(), this.right(), this.bottom(), PARCHMENT);
		graphics.enableScissor(this.left(), this.top(), this.right(), this.bottom());
		this.drawMap(graphics);
		this.drawGrid(graphics);
		NetState state = ActionsClient.state();
		Component hover = this.drawMarkers(graphics, state, mouseX, mouseY);
		graphics.disableScissor();
		if (this.feed) {
			this.drawFeed(graphics);
		}

		graphics.text(this.font, this.title, MARGIN, 9, McTheme.WHITE, true);
		// what a click does (or the answer to the last request) on a strip along the map's bottom edge
		Component hint = this.status.getString().isEmpty() ? this.hint(state) : this.status;
		graphics.fill(this.left(), this.bottom() - 12, this.right(), this.bottom(), 0xA0000000);
		graphics.text(this.font, this.font.plainSubstrByWidth(hint.getString(), this.right() - this.left() - 8), this.left() + 4, this.bottom() - 10,
			this.status.getString().isEmpty() ? McTheme.GRAY : this.statusColour, true);
		super.extractRenderState(graphics, mouseX, mouseY, a);
		if (hover != null) {
			graphics.setComponentTooltipForNextFrame(this.font, List.of(hover), mouseX, mouseY);
		}
	}

	/** What a click does now, or why nothing can be done. */
	private Component hint(final NetState state) {
		if (ActionsClient.stateAge() == Long.MAX_VALUE) {
			return Component.translatableWithFallback("cyberblock.hack.netmap.waiting", "Asking the server...");
		}

		if (state.hosts().isEmpty()) {
			return Component.translatableWithFallback("cyberblock.hack.netmap.no_host", "No linked Cyberpunk host");
		}

		return switch (this.mode) {
			case CAMERA -> Component.translatableWithFallback("cyberblock.hack.netmap.hint.camera", "Click a camera (%s seen) to watch its feed", state.cams().size());
			case VEHICLE -> Component.translatableWithFallback("cyberblock.hack.netmap.hint.vehicle", "Click a car to open its Vehicle Panel");
			default -> Component.translatableWithFallback("cyberblock.hack.netmap.hint.point", "Click the map: %s there", this.mode.label());
		};
	}

	/** The map tiles under the visible area, as the Atlas draws them. */
	private void drawMap(final GuiGraphicsExtractor graphics) {
		AtlasTextures.beginFrame();
		double x0 = this.view.worldX(this.left(), this.viewX()), x1 = this.view.worldX(this.right(), this.viewX());
		double z0 = this.view.worldZ(this.top(), this.viewY()), z1 = this.view.worldZ(this.bottom(), this.viewY());
		double zoom = this.view.zoom();
		int span = (zoom < REGION_ZOOM ? AtlasTiles.REGION : 1) * AtlasTiles.TILE * AtlasTiles.CELL;
		for (int gz = (int) Math.floor(z0 / span); gz <= (int) Math.floor(z1 / span); gz++) {
			for (int gx = (int) Math.floor(x0 / span); gx <= (int) Math.floor(x1 / span); gx++) {
				float sx = (float) this.view.screenX((double) gx * span, this.viewX()), sy = (float) this.view.screenY((double) gz * span, this.viewY());
				float px = (float) (zoom * span / AtlasTiles.TILE);
				if (zoom < REGION_ZOOM) {
					AtlasTextures.drawRegion(graphics, gx, gz, sx, sy, px);
				} else {
					AtlasTextures.drawTile(graphics, gx, gz, sx, sy, px);
				}
			}
		}
	}

	private void drawGrid(final GuiGraphicsExtractor graphics) {
		int step = this.view.gridStep();
		double x0 = this.view.worldX(this.left(), this.viewX()), x1 = this.view.worldX(this.right(), this.viewX());
		for (double x = Math.floor(x0 / step) * step; x <= x1; x += step) {
			int sx = (int) Math.round(this.view.screenX(x, this.viewX()));
			graphics.fill(sx, this.top(), sx + 1, this.bottom(), GRID);
		}

		double z0 = this.view.worldZ(this.top(), this.viewY()), z1 = this.view.worldZ(this.bottom(), this.viewY());
		for (double z = Math.floor(z0 / step) * step; z <= z1; z += step) {
			int sy = (int) Math.round(this.view.screenY(z, this.viewY()));
			graphics.fill(this.left(), sy, this.right(), sy + 1, GRID);
		}
	}

	/** Cars, cameras, pings, range rings and hosts; returns the tooltip of the marker under the mouse (null: none). */
	private @Nullable Component drawMarkers(final GuiGraphicsExtractor graphics, final NetState state, final int mouseX, final int mouseY) {
		Component hover = null;
		for (ProxyEntity car : this.cars()) {
			int sx = (int) Math.round(this.view.screenX(car.getX(), this.viewX())), sy = (int) Math.round(this.view.screenY(car.getZ(), this.viewY()));
			int paint = car.colour() >= 0 ? 0xFF000000 | car.colour() : 0xFF606060;
			graphics.fill(sx - 3, sy - 2, sx + 3, sy + 2, paint);
			graphics.outline(sx - 3, sy - 2, 6, 4, this.mode == Mode.VEHICLE ? McTheme.YELLOW : McTheme.OUTLINE);
			if (this.near(car.position(), mouseX, mouseY)) {
				hover = Component.literal(car.label().isEmpty() ? "Car" : car.label());
			}
		}

		for (NetState.Cam cam : state.cams()) {
			int sx = (int) Math.round(this.view.screenX(cam.p().x, this.viewX())), sy = (int) Math.round(this.view.screenY(cam.p().z, this.viewY()));
			graphics.fill(sx - 2, sy - 2, sx + 3, sy + 3, this.mode == Mode.CAMERA ? McTheme.AQUA : 0xFF3A8A8A);
			// the way it looks: Minecraft yaw 0 faces south (+z)
			double rad = Math.toRadians(cam.yaw());
			int tx = sx + (int) Math.round(-Math.sin(rad) * 6.0), tz = sy + (int) Math.round(Math.cos(rad) * 6.0);
			graphics.fill(Math.min(sx, tx), Math.min(sy, tz), Math.max(sx, tx) + 1, Math.max(sy, tz) + 1, 0x8055FFFF);
			if (this.near(cam.p(), mouseX, mouseY)) {
				NetState.Host owner = state.host(cam.host());
				hover = Component.translatableWithFallback("cyberblock.hack.netmap.camera", "Camera (%s)", owner == null ? "?" : owner.name());
			}
		}

		for (ActionsClient.Ping ping : ActionsClient.pings()) {
			this.decoration(graphics, TARGET, (float) this.view.screenX(ping.p().x, this.viewX()), (float) this.view.screenY(ping.p().z, this.viewY()),
				McTheme.YELLOW, 10);
		}

		for (NetState.Host h : state.hosts()) {
			double sx = this.view.screenX(h.p().x, this.viewX()), sy = this.view.screenY(h.p().z, this.viewY());
			if (state.range() > 0.0 && h.active()) {
				this.ring(graphics, sx, sy, state.range() * this.view.zoom(), 0x80C03030);
			}

			boolean selected = h.id().equals(ActionsClient.selected);
			this.decoration(graphics, PLAYER, (float) sx, (float) sy, selected ? McTheme.YELLOW : h.active() ? 0xFFFFFFFF : 0xFF909090, 12);
			String label = h.name() + (h.blackout() > 0 ? " • " + h.blackout() + " s" : "");
			graphics.text(this.font, label, (int) Math.round(sx) - this.font.width(label) / 2, (int) Math.round(sy) + 8, selected ? 0xFF7A5A00 : LABEL, false);
			if (this.near(h.p(), mouseX, mouseY)) {
				hover = Component.literal(h.name() + (h.district().isEmpty() ? "" : " (" + h.district() + ")"));
			}
		}

		return hover;
	}

	/** A dotted circle of radius `r` GUI px. */
	private void ring(final GuiGraphicsExtractor graphics, final double cx, final double cy, final double r, final int colour) {
		int dots = (int) Math.clamp(r * 0.8, 16, 256);
		for (int i = 0; i < dots; i++) {
			double t = 2.0 * Math.PI * i / dots;
			int x = (int) Math.round(cx + Math.cos(t) * r), y = (int) Math.round(cy + Math.sin(t) * r);
			graphics.fill(x, y, x + 1, y + 1, colour);
		}
	}

	/** The selected host's photo feed in the map's top-right corner. */
	private void drawFeed(final GuiGraphicsExtractor graphics) {
		NetState.Host h = ActionsClient.selectedHost();
		int x = this.right() - FEED_PX - 6, y = this.top() + 6;
		McTheme.panel(graphics, x - 4, y - 14, FEED_PX + 8, FEED_PX + 18);
		Component label = Component.translatableWithFallback("cyberblock.hack.netmap.feed_of", "Photo feed: %s", h == null ? "?" : h.name());
		graphics.text(this.font, this.font.plainSubstrByWidth(label.getString(), FEED_PX), x, y - 11, McTheme.PANEL_TEXT, false);
		if (h == null || !FeedTexture.draw(graphics, h.id(), x, y, FEED_PX)) {
			graphics.fill(x, y, x + FEED_PX, y + FEED_PX, 0xFF202020);
			graphics.centeredText(this.font, Component.translatableWithFallback("cyberblock.hack.netmap.feed_wait", "Waiting for the host's picture..."), x + FEED_PX / 2,
				y + FEED_PX / 2 - 4, McTheme.GRAY);
		} else if (FeedTexture.age(h.id()) > 3) {
			graphics.text(this.font, FeedTexture.age(h.id()) + " s", x + 2, y + FEED_PX - 10, McTheme.RED, true);
		}
	}

	private void decoration(final GuiGraphicsExtractor graphics, final Identifier sprite, final float x, final float y, final int colour, final int size) {
		TextureAtlasSprite atlasSprite = graphics.getSprite(new SpriteId(Sheets.MAP_DECORATIONS_SHEET, sprite));
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, atlasSprite, -size / 2, -size / 2, size, size, colour);
		graphics.pose().popMatrix();
	}

	// ---- input ----------------------------------------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		if (!this.onMap(event.x(), event.y())) {
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
		boolean wasClick = this.dragging && !this.dragged && this.onMap(event.x(), event.y());
		this.dragging = false;
		if (wasClick) {
			this.click(event.x(), event.y());
			return true;
		}

		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
		if (this.dragging) {
			this.dragged |= Math.hypot(event.x() - this.pressX, event.y() - this.pressY) > DRAG_PX;
			if (this.dragged) {
				this.view.pan(dx, dy);
			}

			return true;
		}

		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
		if (scrollY != 0.0 && this.onMap(x, y)) {
			this.view.zoomAt(Math.pow(WHEEL_STEP, scrollY), x, y, this.viewX(), this.viewY());
			return true;
		}

		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	/** The dev screenshot tour: show the feed panel and pick a mode without clicks. */
	void tour(final boolean showFeed, final Mode show) {
		this.feed = showFeed;
		this.mode = show;
		this.updateLabels();
	}

	/** The selected host's id (the dev tour puts a test frame there). */
	@Nullable UUID shownHost() {
		NetState.Host h = ActionsClient.selectedHost();
		return h == null ? null : h.id();
	}
}
