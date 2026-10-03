package dev.cyberblock.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * The Minecraft look of every Night City UI element (plan 2026-10-03-retest3-polish section 1.11): Minecraft's own font, the
 * vanilla tooltip sprites for boxes, the container panel colours and bevels, and ChatFormatting text colours. Every sprite is the
 * player's own vanilla one, named by id and drawn at runtime (ids checked in the 26.3 client jar: gui/sprites/tooltip/background
 * and frame through TooltipRenderUtil, gui/sprites/boss_bar/*, gui/sprites/toast/*, textures/map/decorations/* in the
 * map_decorations atlas, textures/map/map_background.png, textures/gui/book.png, textures/gui/menu_background.png). Nothing is
 * copied into the mod.
 */
public final class McTheme {
	/** Text colours, from ChatFormatting (section 1.11). */
	public static final int WHITE = argb(ChatFormatting.WHITE);
	public static final int YELLOW = argb(ChatFormatting.YELLOW);
	public static final int GOLD = argb(ChatFormatting.GOLD);
	public static final int AQUA = argb(ChatFormatting.AQUA);
	public static final int GRAY = argb(ChatFormatting.GRAY);
	public static final int DARK_GRAY = argb(ChatFormatting.DARK_GRAY);
	public static final int RED = argb(ChatFormatting.RED);
	public static final int GREEN = argb(ChatFormatting.GREEN);
	/** Container panel colours and bevels. */
	public static final int PANEL = 0xFFC6C6C6;
	public static final int SLOT = 0xFF8B8B8B;
	public static final int BEVEL_DARK = 0xFF555555;
	public static final int BEVEL_LIGHT = 0xFFFFFFFF;
	public static final int SLOT_SHADOW = 0xFF373737;
	public static final int OUTLINE = 0xFF000000;
	/** The text colour vanilla uses for container titles on the grey panel. */
	public static final int PANEL_TEXT = 0xFF404040;
	/** The padding TooltipRenderUtil draws around the content box (its PADDING plus the frame). */
	public static final int TOOLTIP_PAD = 4;

	public static final Identifier BOSS_BAR_BACKGROUND = Identifier.withDefaultNamespace("boss_bar/yellow_background");
	public static final Identifier BOSS_BAR_PROGRESS = Identifier.withDefaultNamespace("boss_bar/yellow_progress");
	public static final Identifier MAP_BACKGROUND = Identifier.withDefaultNamespace("textures/map/map_background.png");

	private McTheme() {
	}

	private static int argb(final ChatFormatting format) {
		TextColor colour = TextColor.fromLegacyFormat(format);
		return 0xFF000000 | (colour == null ? 0xFFFFFF : colour.getValue());
	}

	/** A vanilla tooltip box around the content rectangle (x, y, w, h): the same sprites item tooltips use. */
	public static void tooltipBox(final GuiGraphicsExtractor graphics, final int x, final int y, final int w, final int h) {
		TooltipRenderUtil.extractTooltipBackground(graphics, x, y, w, h, null);
	}

	/** A container panel (inventory grey) with its raised bevel and rounded black outline. */
	public static void panel(final GuiGraphicsExtractor graphics, final int x, final int y, final int w, final int h) {
		graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
		// the black outline, corners cut like the vanilla container frame
		graphics.fill(x + 2, y, x + w - 2, y + 1, OUTLINE);
		graphics.fill(x + 2, y + h - 1, x + w - 2, y + h, OUTLINE);
		graphics.fill(x, y + 2, x + 1, y + h - 2, OUTLINE);
		graphics.fill(x + w - 1, y + 2, x + w, y + h - 2, OUTLINE);
		graphics.fill(x + 1, y + 1, x + 2, y + 2, OUTLINE);
		graphics.fill(x + w - 2, y + 1, x + w - 1, y + 2, OUTLINE);
		graphics.fill(x + 1, y + h - 2, x + 2, y + h - 1, OUTLINE);
		graphics.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, OUTLINE);
		// light top-left, dark bottom-right, 2 px each
		graphics.fill(x + 2, y + 1, x + w - 3, y + 3, BEVEL_LIGHT);
		graphics.fill(x + 1, y + 2, x + 3, y + h - 3, BEVEL_LIGHT);
		graphics.fill(x + 3, y + h - 3, x + w - 2, y + h - 1, BEVEL_DARK);
		graphics.fill(x + w - 3, y + 3, x + w - 1, y + h - 2, BEVEL_DARK);
	}

	/** A sunken slot (the dark grey of an inventory slot, shadow top-left, highlight bottom-right). */
	public static void slot(final GuiGraphicsExtractor graphics, final int x, final int y, final int w, final int h) {
		graphics.fill(x, y, x + w, y + h, SLOT);
		graphics.fill(x, y, x + w - 1, y + 1, SLOT_SHADOW);
		graphics.fill(x, y, x + 1, y + h - 1, SLOT_SHADOW);
		graphics.fill(x + 1, y + h - 1, x + w, y + h, BEVEL_LIGHT);
		graphics.fill(x + w - 1, y + 1, x + w, y + h, BEVEL_LIGHT);
	}

	/** A 2 px raised bevel frame (#FFFFFF top-left, #555555 bottom-right) inside the rectangle, for the pass-through frame. */
	public static void bevelFrame(final GuiGraphicsExtractor graphics, final int x, final int y, final int w, final int h, final int thickness) {
		graphics.fill(x, y, x + w, y + thickness, BEVEL_LIGHT);
		graphics.fill(x, y, x + thickness, y + h, BEVEL_LIGHT);
		graphics.fill(x, y + h - thickness, x + w, y + h, BEVEL_DARK);
		graphics.fill(x + w - thickness, y, x + w, y + h, BEVEL_DARK);
		graphics.outline(x - 1, y - 1, w + 2, h + 2, OUTLINE);
	}

	/** "[F]" in yellow: the key label every prompt and call toast uses. Returns its width. */
	public static int keyLabel(final GuiGraphicsExtractor graphics, final Font font, final String key, final int x, final int y) {
		Component label = keyText(key);
		graphics.text(font, label, x, y, YELLOW, true);
		return font.width(label);
	}

	public static Component keyText(final String key) {
		return Component.literal("[" + key + "]");
	}

	/** A vanilla boss bar (yellow), filled by fraction 0..1, 182 px wide unless {@code width} is smaller. */
	public static void bossBar(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final float fraction) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BOSS_BAR_BACKGROUND, 182, 5, 0, 0, x, y, width, 5);
		int filled = Math.round(width * Math.clamp(fraction, 0.0F, 1.0F));
		if (filled > 0) {
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BOSS_BAR_PROGRESS, 182, 5, 0, 0, x, y, filled, 5);
		}
	}

	/** {@code colour} with its alpha scaled by {@code alpha} 0..1 (text fades). Alpha under ~4 % makes the font skip the text. */
	public static int fade(final int colour, final float alpha) {
		return ARGB.color(Math.round(ARGB.alpha(colour) * Math.clamp(alpha, 0.0F, 1.0F)), colour);
	}
}
