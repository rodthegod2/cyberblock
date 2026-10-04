package dev.cyberblock.client.ui;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.HostState;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/**
 * Registers the Night City HUD (section 1.11 placement) and answers the questions every element asks: is a host driving, and is a
 * pass-through menu covering the screen (then only MenuFrameHud draws).
 */
public final class UiHud {
	private UiHud() {
	}

	static Identifier id(final String path) {
		return Identifier.fromNamespaceAndPath(Cyberblock.ID, "ui/" + path);
	}

	static void register() {
		// under the chat, so a chat line is never hidden by the HUD
		before(VanillaHudElements.CHAT, "radar", RadarHud::draw);
		before(VanillaHudElements.CHAT, "stats", StatsHud::draw);
		before(VanillaHudElements.CHAT, "quest", QuestHud::draw);
		before(VanillaHudElements.CHAT, "subtitle", SubtitleHud::draw);
		before(VanillaHudElements.CHAT, "choices", ChoicesHud::draw);
		before(VanillaHudElements.CHAT, "prompt", PromptHud::draw);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, id("third_person_crosshair"), CrosshairHud::draw);
		VehicleHud.register();
		HudElementRegistry.addLast(id("menu_frame"), MenuFrameHud::draw);
	}

	private static void before(final Identifier vanilla, final String path, final HudElement element) {
		HudElementRegistry.attachElementBefore(vanilla, id(path), element);
	}

	/**
	 * The local player when a host drives Minecraft, spoke within 10 s and no pass-through menu covers the screen; else null (draw
	 * nothing). A quiet host only hides the HUD: UiModel keeps its data for when the host speaks again.
	 */
	static @Nullable Player player() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!HostState.active() || minecraft.player == null || !UiLink.recentlyLinked() || covered()) {
			return null;
		}

		return minecraft.player;
	}

	/** The player for the big corner elements (radar, stats, job): also null while a Minecraft screen is open over them. */
	static @Nullable Player cornerPlayer() {
		return Minecraft.getInstance().gui.screen() != null ? null : player();
	}

	/**
	 * A Cyberpunk menu is open and passed through, or Night City is loading: MenuFrameHud draws the frame or the cover, everything
	 * else returns early.
	 */
	static boolean covered() {
		HostState.Pose pose = HostState.frame();
		return pose != null && pose.menu() || UiLink.loading();
	}

	/**
	 * One of package A's GUI icons ({@code cyberblock:ui/<name>} as a GUI sprite, or {@code textures/gui/ui/<name>.png}), when the
	 * resources have it. Returns false when neither exists, so the caller draws its own fallback.
	 */
	static boolean icon(final GuiGraphicsExtractor graphics, final String name, final int x, final int y, final int w, final int h, final int colour) {
		int kind = ICONS.computeIfAbsent(name, UiHud::findIcon);
		if (kind == SPRITE) {
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath(Cyberblock.ID, "ui/" + name), x, y, w, h, colour);
		} else if (kind == TEXTURE) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, iconTexture(name), x, y, 0.0F, 0.0F, w, h, w, h, colour);
		}

		return kind != NONE;
	}

	private static final int NONE = 0, SPRITE = 1, TEXTURE = 2;
	/** Looked up once per name: the resource packs do not change under a running HUD often enough to matter. */
	private static final Map<String, Integer> ICONS = new ConcurrentHashMap<>();

	private static int findIcon(final String name) {
		ResourceManager resources = Minecraft.getInstance().getResourceManager();
		if (resources.getResource(Identifier.fromNamespaceAndPath(Cyberblock.ID, "textures/gui/sprites/ui/" + name + ".png")).isPresent()) {
			return SPRITE;
		}

		return resources.getResource(iconTexture(name)).isPresent() ? TEXTURE : NONE;
	}

	private static Identifier iconTexture(final String name) {
		return Identifier.fromNamespaceAndPath(Cyberblock.ID, "textures/gui/ui/" + name + ".png");
	}
}
