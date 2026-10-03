package dev.cyberblock.client.ui.screen;

import dev.cyberblock.client.HostState;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.client.ui.RadarHud;
import dev.cyberblock.client.ui.UiLink;
import dev.cyberblock.client.ui.UiModel;
import java.util.List;
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
 * Night City's world map as a Minecraft atlas (F12, section 1.11: M opens it). A parchment-coloured grid drawn in code (north up,
 * pannable by dragging, zoomed with the wheel or the +/- buttons), every map pin of Cyberpunk's {@code atlas} reply with vanilla
 * map-decoration sprites, V as the map's player arrow and the tracked objective. Clicking a pin selects it: Track sends
 * {@code uiact track} (arg "pin", id = the pin's index in the reply), Fast travel {@code uiact fasttravel} (id = the fast-travel
 * index), offered only for pins the reply marks as fast-travel points (index &gt;= 0).
 */
public final class AtlasScreen extends Screen {
	private static final int MARGIN = 8;
	private static final int TOP = 22;
	private static final int BOTTOM = 28;
	private static final int PARCHMENT = 0xFFDCC896;
	private static final int GRID = 0x30402810;
	private static final int PIN_SIZE = 12;
	private static final double WHEEL_STEP = 1.25;
	private static final Identifier PLAYER = Identifier.withDefaultNamespace("player");
	private static final Identifier OBJECTIVE = Identifier.withDefaultNamespace("target_point");

	private final @Nullable Screen parent;
	private final AtlasView view;
	private UiModel.@Nullable Reply reply;
	private int requestId = -1;
	private int selected = -1;
	private boolean dragging;
	private @Nullable Button track, travel;

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
		this.track = this.addRenderableWidget(Button.builder(NightCityPauseScreen.ui("atlas.track", "Track"), b -> this.trackSelected())
			.bounds(this.width / 2 - 102, y, 100, 20).build());
		this.travel = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.travelSelected()).bounds(this.width / 2 + 2, y, 140, 20).build());
		this.updateButtons();
	}

	private void zoom(final double factor) {
		this.view.zoomAt(factor, this.viewX(), this.viewY(), this.viewX(), this.viewY());
	}

	private UiModel.@Nullable ReplyPin selectedPin() {
		UiModel.Reply r = this.reply;
		return r != null && this.selected >= 0 && this.selected < r.pins().size() ? r.pins().get(this.selected) : null;
	}

	private void updateButtons() {
		UiModel.ReplyPin pin = this.selectedPin();
		if (this.track != null && this.travel != null) {
			this.track.visible = pin != null;
			this.travel.visible = pin != null && pin.index() >= 0;
			if (pin != null && pin.index() >= 0) {
				this.travel.setMessage(NightCityPauseScreen.ui("atlas.fast_travel", "Fast travel to %s", pin.name()));
			}
		}
	}

	private void trackSelected() {
		if (this.selectedPin() != null) {
			UiLink.act("track", "pin", 0L, this.selected);
		}
	}

	private void travelSelected() {
		UiModel.ReplyPin pin = this.selectedPin();
		if (pin != null && pin.index() >= 0 && UiLink.act("fasttravel", pin.name(), 0L, pin.index())) {
			this.minecraft.gui.setScreen(null);
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
		this.drawGrid(graphics);
		UiModel.Reply r = this.reply;
		int hovered = -1;
		if (r != null) {
			List<UiModel.ReplyPin> pins = r.pins();
			for (int i = 0; i < pins.size(); i++) {
				UiModel.ReplyPin pin = pins.get(i);
				float sx = (float) this.view.screenX(pin.p().x(), this.viewX()), sy = (float) this.view.screenY(pin.p().z(), this.viewY());
				boolean over = Math.abs(mouseX - sx) <= PIN_SIZE / 2.0F && Math.abs(mouseY - sy) <= PIN_SIZE / 2.0F && this.onMap(mouseX, mouseY);
				if (over) {
					hovered = i;
				}

				if (i == this.selected) {
					graphics.outline(Math.round(sx) - PIN_SIZE / 2 - 2, Math.round(sy) - PIN_SIZE / 2 - 2, PIN_SIZE + 4, PIN_SIZE + 4, McTheme.YELLOW);
				}

				this.decoration(graphics, RadarHud.pinSprite(pin.kind()), sx, sy, 0.0F, over ? McTheme.YELLOW : 0xFFFFFFFF);
			}
		}

		UiModel.Quest quest = UiModel.quest;
		if (quest != null && quest.wp() != null) {
			this.decoration(graphics, OBJECTIVE, (float) this.view.screenX(quest.wp().x(), this.viewX()), (float) this.view.screenY(quest.wp().z(), this.viewY()), 0.0F,
				McTheme.GOLD);
		}

		HostState.Pose pose = HostState.frame();
		LocalPlayer player = this.minecraft.player;
		if (pose != null || player != null) {
			double vx = pose != null ? pose.px() : player.getX(), vz = pose != null ? pose.pz() : player.getZ();
			float yaw = pose != null ? pose.yaw() : player.getYRot();
			float sx = (float) this.view.screenX(vx, this.viewX()), sy = (float) this.view.screenY(vz, this.viewY());
			// the sprite points up (north); Minecraft yaw 0 faces south
			this.decoration(graphics, PLAYER, sx, sy, (float) Math.toRadians(yaw + 180.0F), 0xFFFFFFFF);
			Component you = NightCityPauseScreen.ui("atlas.you", "You");
			graphics.text(this.font, you, Math.round(sx) - this.font.width(you) / 2, Math.round(sy) + PIN_SIZE / 2 + 1, McTheme.PANEL_TEXT, false);
		}

		graphics.disableScissor();
		graphics.centeredText(this.font, this.title, this.width / 2, 7, McTheme.WHITE);
		if (r == null) {
			Component status = this.requestId < 0 ? NightCityPauseScreen.ui("screen.unavailable", "Night City is not connected") : Component.literal("...");
			graphics.centeredText(this.font, status, this.width / 2, (int) this.viewY(), this.requestId < 0 ? McTheme.RED : McTheme.PANEL_TEXT);
		}

		UiModel.ReplyPin pin = this.selectedPin();
		if (pin != null && pin.index() < 0 && pin.kind().equals("fasttravel")) {
			graphics.text(this.font, NightCityPauseScreen.ui("atlas.fast_travel_off", "Fast travel: use the Night City map (Night City Menu...)"),
				this.width / 2 + 2, this.height - BOTTOM + 10, McTheme.GRAY, true);
		}

		super.extractRenderState(graphics, mouseX, mouseY, a);
		if (r != null && hovered >= 0) {
			UiModel.ReplyPin over = r.pins().get(hovered);
			graphics.setComponentTooltipForNextFrame(this.font, List.of(
				Component.literal(over.name().isEmpty() ? over.kind() : over.name()).withStyle(ChatFormatting.YELLOW),
				Component.literal(over.kind()).withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
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

	/** A vanilla map decoration (the map_decorations atlas), PIN_SIZE wide, centred on (x, y) and turned by {@code radians}. */
	private void decoration(final GuiGraphicsExtractor graphics, final Identifier sprite, final float x, final float y, final float radians, final int colour) {
		TextureAtlasSprite atlasSprite = graphics.getSprite(new SpriteId(Sheets.MAP_DECORATIONS_SHEET, sprite));
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().rotate(radians);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, atlasSprite, -PIN_SIZE / 2, -PIN_SIZE / 2, PIN_SIZE, PIN_SIZE, colour);
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
		UiModel.Reply r = this.reply;
		if (r != null) {
			for (int i = 0; i < r.pins().size(); i++) {
				UiModel.ReplyPin pin = r.pins().get(i);
				double sx = this.view.screenX(pin.p().x(), this.viewX()), sy = this.view.screenY(pin.p().z(), this.viewY());
				if (Math.abs(event.x() - sx) <= PIN_SIZE / 2.0 && Math.abs(event.y() - sy) <= PIN_SIZE / 2.0) {
					this.selected = i;
					this.updateButtons();
					return true;
				}
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
