package dev.cyberblock.client.ui.screen;

import dev.cyberblock.client.HostState;
import dev.cyberblock.client.atlas.AtlasPins;
import dev.cyberblock.client.atlas.AtlasStore;
import dev.cyberblock.client.atlas.AtlasTextures;
import dev.cyberblock.client.atlas.AtlasTiles;
import dev.cyberblock.client.atlas.AtlasWire;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.client.ui.RadarHud;
import dev.cyberblock.client.ui.UiLink;
import dev.cyberblock.client.ui.UiModel;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.jspecify.annotations.Nullable;

/**
 * Night City's world map as a Minecraft atlas (F12, section 1.11: M opens it), drawn like a vanilla filled map. On the parchment:
 * the explored Night City (AtlasStore: the highest surface of every 2-m cell Cyberpunk's survey rays found, coloured by height
 * like a map item, see AtlasColours), every map pin of Cyberpunk's {@code atlas} reply as a vanilla map decoration with its name
 * on hover, district names at far zoom, V as the map's player arrow and the tracked objective. North is up; drag to pan, wheel or
 * +/- to zoom. Pins are filtered by the buttons at the top (quests, travel, shops, gigs, places) and thinned out where they would
 * cover each other (the more important kind wins). Clicking a pin selects it: a quest pin can be tracked ({@code uiact track},
 * arg "pin", id = its index in the reply; Menus.reds TrackPin tracks its journal entry as the world map does); fast travel is
 * offered only for pins the reply marks as fast-travel points (index &gt;= 0, off in 1.0.0).
 */
public final class AtlasScreen extends Screen {
	private static final int MARGIN = 8;
	private static final int TOP = 26;
	private static final int BOTTOM = 28;
	private static final int PARCHMENT = 0xFFDCC896;
	private static final int GRID = 0x18402810;
	private static final int LABEL = 0xFF3B2A14;
	private static final int LABEL_BACK = 0x70DCC896;
	private static final int PIN_SIZE = 12;
	private static final int SMALL_PIN = 8;
	private static final double WHEEL_STEP = 1.25;
	/** Below this zoom (GUI px per block) the map is drawn from region textures (1 px = 16 m). */
	private static final double REGION_ZOOM = 0.25;
	/** District labels show below this zoom. */
	private static final double LABEL_ZOOM = 0.45;
	private static final Identifier PLAYER = Identifier.withDefaultNamespace("player");
	private static final Identifier OBJECTIVE = Identifier.withDefaultNamespace("target_point");

	private final @Nullable Screen parent;
	private final AtlasView view;
	private final Set<AtlasPins.Filter> shown = EnumSet.allOf(AtlasPins.Filter.class);
	private UiModel.@Nullable Reply reply;
	private int requestId = -1;
	private int selected = -1;
	private boolean dragging;
	private @Nullable Button action;
	/** The pins drawn this frame, by reply index, for clicks (screen x, y). */
	private final List<double[]> drawn = new ArrayList<>();

	public AtlasScreen(final @Nullable Screen parent) {
		super(NightCityPauseScreen.ui("atlas.title", "Atlas"));
		this.parent = parent;
		HostState.Pose pose = HostState.frame();
		this.view = new AtlasView(pose != null ? pose.px() : 0.0, pose != null ? pose.pz() : 0.0, 0.5);
	}

	@Override
	protected void init() {
		LocalPlayer player = this.minecraft.player;
		if (this.requestId < 0 && this.reply == null) {
			if (player != null && HostState.frame() == null) {
				this.view.centreOn(player.getX(), player.getZ());
			}

			this.requestId = UiLink.request("atlas", r -> {
				this.reply = r;
				this.selected = -1;
				this.updateButtons();
			});
		}

		int y = this.height - BOTTOM + 4;
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.zoom(WHEEL_STEP * WHEEL_STEP)).bounds(MARGIN, y, 20, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("-"), b -> this.zoom(1.0 / (WHEEL_STEP * WHEEL_STEP))).bounds(MARGIN + 22, y, 20, 20).build());
		this.addRenderableWidget(Button.builder(NightCityPauseScreen.ui("atlas.centre", "You"), b -> this.centreOnV()).bounds(MARGIN + 44, y, 36, 20).build());
		this.action = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.actSelected()).bounds(this.width / 2 - 70, y, 140, 20).build());
		// the filter toggles, top right
		AtlasPins.Filter[] filters = AtlasPins.Filter.values();
		int w = 50, x = this.width - MARGIN - filters.length * (w + 2) + 2;
		for (AtlasPins.Filter filter : filters) {
			this.addRenderableWidget(Button.builder(this.filterLabel(filter), b -> {
				if (!this.shown.remove(filter)) {
					this.shown.add(filter);
				}

				b.setMessage(this.filterLabel(filter));
			}).bounds(x, 3, w, 20).build());
			x += w + 2;
		}

		this.updateButtons();
	}

	private Component filterLabel(final AtlasPins.Filter filter) {
		Component name = NightCityPauseScreen.ui("atlas.filter." + filter.key(), filter.english());
		return this.shown.contains(filter) ? name : name.copy().withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.STRIKETHROUGH);
	}

	private void zoom(final double factor) {
		this.view.zoomAt(factor, this.viewX(), this.viewY(), this.viewX(), this.viewY());
	}

	private void centreOnV() {
		HostState.Pose pose = HostState.frame();
		LocalPlayer player = this.minecraft.player;
		if (pose != null) {
			this.view.centreOn(pose.px(), pose.pz());
		} else if (player != null) {
			this.view.centreOn(player.getX(), player.getZ());
		}
	}

	private UiModel.@Nullable ReplyPin selectedPin() {
		UiModel.Reply r = this.reply;
		return r != null && this.selected >= 0 && this.selected < r.pins().size() ? r.pins().get(this.selected) : null;
	}

	private void updateButtons() {
		UiModel.ReplyPin pin = this.selectedPin();
		if (this.action == null) {
			return;
		}

		if (pin != null && pin.index() >= 0) {
			this.action.visible = true;
			this.action.setMessage(NightCityPauseScreen.ui("atlas.fast_travel", "Fast travel to %s", pin.name()));
		} else if (pin != null && pin.kind().equals("quest")) {
			this.action.visible = true;
			this.action.setMessage(NightCityPauseScreen.ui("atlas.track", "Track"));
		} else {
			this.action.visible = false;
		}
	}

	private void actSelected() {
		UiModel.ReplyPin pin = this.selectedPin();
		if (pin == null) {
			return;
		}

		if (pin.index() >= 0) {
			if (UiLink.act("fasttravel", pin.name(), 0L, pin.index())) {
				this.minecraft.gui.setScreen(null);
			}
		} else if (pin.kind().equals("quest") && UiLink.act("track", "pin", 0L, this.selected)) {
			this.action.setMessage(NightCityPauseScreen.ui("atlas.tracked", "Tracked"));
		}
	}

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

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		McTheme.panel(graphics, this.left() - 4, this.top() - 4, this.right() - this.left() + 8, this.bottom() - this.top() + 8);
		graphics.fill(this.left(), this.top(), this.right(), this.bottom(), PARCHMENT);
		graphics.enableScissor(this.left(), this.top(), this.right(), this.bottom());
		this.drawMap(graphics);
		this.drawGrid(graphics);
		UiModel.Reply r = this.reply;
		if (r != null && this.view.zoom() < LABEL_ZOOM) {
			this.drawDistricts(graphics, r);
		}

		int hovered = r != null ? this.drawPins(graphics, r, mouseX, mouseY) : -1;
		UiModel.Quest quest = UiModel.quest;
		if (quest != null && quest.wp() != null) {
			this.decoration(graphics, OBJECTIVE, (float) this.view.screenX(quest.wp().x(), this.viewX()), (float) this.view.screenY(quest.wp().z(), this.viewY()), 0.0F,
				McTheme.GOLD, PIN_SIZE);
		}

		HostState.Pose pose = HostState.frame();
		LocalPlayer player = this.minecraft.player;
		if (pose != null || player != null) {
			double vx = pose != null ? pose.px() : player.getX(), vz = pose != null ? pose.pz() : player.getZ();
			float yaw = pose != null ? pose.yaw() : player.getYRot();
			float sx = (float) this.view.screenX(vx, this.viewX()), sy = (float) this.view.screenY(vz, this.viewY());
			// the sprite points up (north); Minecraft yaw 0 faces south
			this.decoration(graphics, PLAYER, sx, sy, (float) Math.toRadians(yaw + 180.0F), 0xFFFFFFFF, PIN_SIZE);
			Component you = NightCityPauseScreen.ui("atlas.you", "You");
			graphics.text(this.font, you, Math.round(sx) - this.font.width(you) / 2, Math.round(sy) + PIN_SIZE / 2 + 1, McTheme.PANEL_TEXT, false);
		}

		graphics.disableScissor();
		graphics.text(this.font, this.title, MARGIN, 9, McTheme.WHITE, true);
		graphics.text(this.font, this.statusLine(), MARGIN + this.font.width(this.title) + 8, 9, McTheme.GRAY, true);
		if (r == null) {
			Component status = this.requestId < 0 ? NightCityPauseScreen.ui("screen.unavailable", "Night City is not connected") : Component.literal("...");
			graphics.centeredText(this.font, status, this.width / 2, this.bottom() - 12, this.requestId < 0 ? McTheme.RED : McTheme.PANEL_TEXT);
		}

		UiModel.ReplyPin pin = this.selectedPin();
		if (pin != null && pin.index() < 0 && pin.kind().equals("fasttravel")) {
			graphics.centeredText(this.font, NightCityPauseScreen.ui("atlas.fast_travel_off", "Fast travel: use the Night City map (Night City Menu...)"),
				this.width / 2, this.height - BOTTOM + 10, McTheme.GRAY);
		}

		super.extractRenderState(graphics, mouseX, mouseY, a);
		if (r != null && hovered >= 0) {
			UiModel.ReplyPin over = r.pins().get(hovered);
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal(over.name().isEmpty() ? AtlasPins.kindName(over.kind()) : over.name()).withStyle(ChatFormatting.YELLOW));
			lines.add(Component.literal(AtlasPins.kindName(over.kind())).withStyle(ChatFormatting.GRAY));
			if (!over.district().isEmpty()) {
				lines.add(Component.literal(over.district()).withStyle(ChatFormatting.DARK_AQUA));
			}

			graphics.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
		}
	}

	/** "12.3 km² mapped", and the survey tour's progress while it runs. */
	private Component statusLine() {
		double km2 = AtlasStore.tiles.tileCount() * Math.pow(AtlasTiles.TILE * AtlasTiles.CELL / 1000.0, 2);
		AtlasWire.SurveyState survey = AtlasStore.survey;
		if (survey != null && survey.running()) {
			return NightCityPauseScreen.ui("atlas.surveying", "Surveying Night City: %s of %s", survey.done(), survey.total());
		}

		return NightCityPauseScreen.ui("atlas.mapped", "%s km² mapped (/cyberblock survey maps all of it)", String.format(java.util.Locale.ROOT, "%.1f", km2));
	}

	/** The explored Night City: tile textures (region textures at far zoom) under the visible area. */
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

	/** District names where their fast-travel points (or V's visits) are, never on top of each other. */
	private void drawDistricts(final GuiGraphicsExtractor graphics, final UiModel.Reply r) {
		Map<String, double[]> centres = AtlasPins.districtCentres(r.pins(), AtlasStore.districtCentres());
		List<int[]> taken = new ArrayList<>();
		for (Map.Entry<String, double[]> district : centres.entrySet()) {
			int sx = (int) Math.round(this.view.screenX(district.getValue()[0], this.viewX()));
			int sy = (int) Math.round(this.view.screenY(district.getValue()[1], this.viewY()));
			Component name = Component.literal(district.getKey().toUpperCase(java.util.Locale.ROOT));
			int w = this.font.width(name), x = sx - w / 2, y = sy - 4;
			int[] box = {x - 2, y - 2, x + w + 2, y + 10};
			if (!this.onMap(sx, sy) || taken.stream().anyMatch(t -> t[0] < box[2] && box[0] < t[2] && t[1] < box[3] && box[1] < t[3])) {
				continue;
			}

			taken.add(box);
			graphics.fill(box[0], box[1], box[2], box[3], LABEL_BACK);
			graphics.text(this.font, name, x, y, LABEL, false);
		}
	}

	/** The pins, most important first, skipping one that would cover a drawn one; returns the hovered reply index (-1 none). */
	private int drawPins(final GuiGraphicsExtractor graphics, final UiModel.Reply r, final int mouseX, final int mouseY) {
		this.drawn.clear();
		int size = this.view.zoom() < 0.15 ? SMALL_PIN : PIN_SIZE;
		int hovered = -1;
		for (int i : AtlasPins.drawOrder(r.pins(), this.shown, this.selected)) {
			UiModel.ReplyPin pin = r.pins().get(i);
			double sx = this.view.screenX(pin.p().x(), this.viewX()), sy = this.view.screenY(pin.p().z(), this.viewY());
			if (!this.onMap(sx, sy)) {
				continue;
			}

			boolean always = i == this.selected || pin.kind().equals("quest");
			double gap = size * 0.75;
			if (!always && this.drawn.stream().anyMatch(d -> Math.abs(d[1] - sx) < gap && Math.abs(d[2] - sy) < gap)) {
				continue;
			}

			this.drawn.add(new double[] {i, sx, sy});
			boolean over = Math.abs(mouseX - sx) <= size / 2.0 && Math.abs(mouseY - sy) <= size / 2.0 && this.onMap(mouseX, mouseY);
			if (over) {
				hovered = i;
			}

			if (i == this.selected) {
				graphics.outline((int) Math.round(sx) - size / 2 - 2, (int) Math.round(sy) - size / 2 - 2, size + 4, size + 4, McTheme.YELLOW);
			}

			this.decoration(graphics, RadarHud.pinSprite(pin.kind()), (float) sx, (float) sy, 0.0F, over ? McTheme.YELLOW : 0xFFFFFFFF, size);
		}

		return hovered;
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

	/** A vanilla map decoration (the map_decorations atlas), {@code size} wide, centred on (x, y) and turned by {@code radians}. */
	private void decoration(final GuiGraphicsExtractor graphics, final Identifier sprite, final float x, final float y, final float radians, final int colour,
		final int size) {
		TextureAtlasSprite atlasSprite = graphics.getSprite(new SpriteId(Sheets.MAP_DECORATIONS_SHEET, sprite));
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().rotate(radians);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, atlasSprite, -size / 2, -size / 2, size, size, colour);
		graphics.pose().popMatrix();
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		if (!this.onMap(event.x(), event.y())) {
			return false;
		}

		this.dragging = true;
		int size = this.view.zoom() < 0.15 ? SMALL_PIN : PIN_SIZE;
		// the topmost drawn pin under the cursor (drawn last = on top)
		for (int k = this.drawn.size() - 1; k >= 0; k--) {
			double[] d = this.drawn.get(k);
			if (Math.abs(event.x() - d[1]) <= size / 2.0 && Math.abs(event.y() - d[2]) <= size / 2.0) {
				this.selected = (int) d[0];
				this.updateButtons();
				return true;
			}
		}

		return true;
	}

	@Override
	public boolean mouseReleased(final MouseButtonEvent event) {
		this.dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
		if (this.dragging) {
			this.view.pan(dx, dy);
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

	@Override
	public void removed() {
		UiLink.forget(this.requestId);
		super.removed();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return this.parent != null;
	}
}
