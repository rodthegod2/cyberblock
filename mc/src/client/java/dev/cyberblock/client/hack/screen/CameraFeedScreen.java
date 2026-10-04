package dev.cyberblock.client.hack.screen;

import dev.cyberblock.client.HostState;
import dev.cyberblock.client.ui.McTheme;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Net Map recon: a Night City camera's view (megaplan task 6-I1, the written fallback of 6-S1: the scripts report the camera's place
 * and facing, and Minecraft renders the replica from there instead of taking over the host's Cyberpunk screen). While open, the
 * client's camera follows a marker at the camera's place, looking along its facing and a little down; a CCTV frame is drawn over
 * it. Only what this client has loaded shows (the replica within its view distance, the figures it tracks), so the server refuses
 * a camera farther than that from the player ("Move within N blocks of the camera", RemoteActions.cameraReach). On an active host's
 * own screen Cyberpunk drives the camera, so the view stays the host's. Esc closes it.
 */
final class CameraFeedScreen extends Screen {
	/** A camera looks this far down (degrees). */
	static final float PITCH = 20.0F;
	private final Vec3 at;
	private final float yaw;
	private final String name;
	private @Nullable CameraType before;
	private @Nullable Marker eye;
	private boolean hudWasHidden;
	private int ticks;

	CameraFeedScreen(final Vec3 at, final float yaw, final String name) {
		super(Component.translatableWithFallback("cyberblock.camfeed.title", "Camera feed"));
		this.at = at;
		this.yaw = yaw;
		this.name = name;
	}

	@Override
	protected void init() {
		if (this.eye != null || this.minecraft.level == null) {
			return;
		}

		Marker marker = new Marker(EntityTypes.MARKER, this.minecraft.level);
		marker.snapTo(this.at.x, this.at.y, this.at.z, this.yaw, PITCH);
		this.eye = marker;
		this.before = this.minecraft.options.getCameraType();
		this.hudWasHidden = this.minecraft.gui.hud.isHidden();
		this.minecraft.options.setCameraType(CameraType.FIRST_PERSON);
		// a camera has no hands, crosshair or hotbar: the HUD is off while the feed shows (this screen still draws)
		if (!this.hudWasHidden) {
			this.minecraft.gui.hud.toggle();
		}
		this.minecraft.setCameraEntity(marker);
	}

	@Override
	public void tick() {
		this.ticks++;
	}

	@Override
	public void removed() {
		if (this.eye != null) {
			this.minecraft.setCameraEntity(this.minecraft.player);
			if (this.before != null) {
				this.minecraft.options.setCameraType(this.before);
			}

			if (!this.hudWasHidden && this.minecraft.gui.hud.isHidden()) {
				this.minecraft.gui.hud.toggle();
			}
			this.eye = null;
		}

		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** No dimming: the world behind is the feed. */
	@Override
	public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		int w = this.width, h = this.height;
		// scanlines and a dark frame, like a security monitor
		for (int y = 0; y < h; y += 3) {
			graphics.fill(0, y, w, y + 1, 0x18000000);
		}

		int edge = 6;
		graphics.fill(0, 0, w, edge, 0xC0101010);
		graphics.fill(0, h - edge, w, h, 0xC0101010);
		graphics.fill(0, 0, edge, h, 0xC0101010);
		graphics.fill(w - edge, 0, w, h, 0xC0101010);
		boolean blink = this.ticks / 10 % 2 == 0;
		if (blink) {
			graphics.fill(edge + 6, edge + 6, edge + 12, edge + 12, McTheme.RED);
		}

		String label = this.name.isEmpty() ? this.title.getString() : this.title.getString() + ": " + this.name;
		graphics.text(this.font, label, edge + 16, edge + 5, McTheme.WHITE, true);
		String where = String.format(java.util.Locale.ROOT, "%.0f %.0f %.0f  %03.0f°", this.at.x, this.at.y, this.at.z, Math.floorMod((int) this.yaw, 360) * 1.0);
		graphics.text(this.font, where, w - edge - 6 - this.font.width(where), edge + 5, McTheme.GRAY, true);
		Component hint = HostState.frame() != null
			? Component.translatableWithFallback("cyberblock.camfeed.host", "Your Cyberpunk drives your view: camera feeds show on guests' screens")
			: Component.translatableWithFallback("cyberblock.camfeed.hint", "Replica view from the camera (Esc to leave)");
		graphics.centeredText(this.font, hint, w / 2, h - edge - 14, McTheme.GRAY);
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}
}
