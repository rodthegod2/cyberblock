package dev.cyberblock.client.cyber;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.CyberActs;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.PerkShop;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The skill tree (task C6): five branch columns of four perks on art/gui/skill_tree_bg.png, bought with XP levels by a
 * click. Hovering a perk shows its cost, prerequisite and both games' effects. Driven by the host's cursor and clicks like
 * every screen; the game keeps running behind it.
 */
public final class SkillTreeScreen extends Screen {
	private static final Identifier BACKGROUND = texture("gui/skill_tree_bg");
	private static final Identifier TOKEN = texture("item/perk_token");
	private static final int PANEL = 256;
	private static final int COLUMN = PANEL / Perk.BRANCHES.length;
	private static final int NODE = 22;
	private static final int FIRST_NODE_Y = 64;
	private static final int NODE_STEP = 42;
	/** Minecraft container colours (plan retest3 section 1.11): dark grey text on the #C6C6C6 panel A drew, ChatFormatting accents. */
	private static final int TEXT = 0xFF404040;
	private static final int OWNED = 0xFF00AAAA;
	private static final int AVAILABLE = 0xFFFFFF55;
	private static final int LOCKED = 0xFF555555;

	public SkillTreeScreen() {
		super(Component.translatable("cyberblock.skills.title"));
	}

	private static Identifier texture(final String path) {
		return Identifier.fromNamespaceAndPath(Cyberblock.ID, "textures/" + path + ".png");
	}

	private int left() {
		return Math.max(0, (this.width - PANEL) / 2);
	}

	private int top() {
		return Math.max(0, (this.height - PANEL) / 2);
	}

	private int nodeX(final Perk perk) {
		return this.left() + perk.branch() * COLUMN + (COLUMN - NODE) / 2;
	}

	private int nodeY(final Perk perk) {
		return this.top() + FIRST_NODE_Y + (perk.tier() - 1) * NODE_STEP;
	}

	private @Nullable Perk perkAt(final double x, final double y) {
		for (Perk perk : Perk.values()) {
			int nx = this.nodeX(perk), ny = this.nodeY(perk);
			if (x >= nx && x < nx + NODE && y >= ny && y < ny + NODE) {
				return perk;
			}
		}

		return null;
	}

	private Map<String, Integer> ranks() {
		LocalPlayer player = this.minecraft.player;
		return player == null ? Map.of() : player.getAttachedOrElse(CyberData.PERKS, Map.of());
	}

	private int levels() {
		LocalPlayer player = this.minecraft.player;
		return player == null ? 0 : player.experienceLevel;
	}

	/** Creative or `/cyberblock perks free on` (plan retest2 R3): the costs are shown greyed out and nothing takes levels. */
	private boolean free() {
		LocalPlayer player = this.minecraft.player;
		return player != null && CyberData.freePerks(player);
	}

	@Override
	public boolean isPauseScreen() {
		// singleplayer pauses the server under a pause screen: Night City keeps going, so Minecraft must too
		return false;
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int left = this.left(), top = this.top();
		graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, left, top, 0.0F, 0.0F, PANEL, PANEL, PANEL, PANEL);
		graphics.text(this.font, this.title, left + PANEL / 2 - this.font.width(this.title) / 2, top + 8, TEXT, false);
		Map<String, Integer> ranks = this.ranks();
		int levels = this.levels();
		boolean free = this.free();
		for (int b = 0; b < Perk.BRANCHES.length; b++) {
			int cx = left + b * COLUMN + COLUMN / 2;
			graphics.blit(RenderPipelines.GUI_TEXTURED, texture("item/skill_" + Perk.BRANCHES[b]), cx - 8, top + 22, 0.0F, 0.0F, 16, 16, 16, 16);
			Component label = Component.translatable("cyberblock.skills.branch." + Perk.BRANCHES[b]);
			graphics.pose().pushMatrix();
			graphics.pose().translate(cx, top + 42);
			graphics.pose().scale(0.75F, 0.75F);
			graphics.text(this.font, label, -this.font.width(label) / 2, 0, TEXT, false);
			graphics.pose().popMatrix();
		}

		for (Perk perk : Perk.values()) {
			this.drawNode(graphics, perk, PerkShop.check(perk, ranks, levels, free), free);
		}

		// beside the title: at 720p the GUI is 240 high and the panel's bottom edge is off screen
		Component levelsText = free ? Component.translatable("cyberblock.skills.free") : Component.translatable("cyberblock.skills.levels", levels);
		graphics.text(this.font, levelsText, left + PANEL - 8 - this.font.width(levelsText), top + 8, 0xFF00AA00, false);
		Component note = CyberKeys.note();
		if (note != null) {
			graphics.centeredText(this.font, note, this.width / 2, Math.min(this.height - 10, top + PANEL + 4), 0xFFFFAA00);
		}

		Perk hovered = this.perkAt(mouseX, mouseY);
		if (hovered != null) {
			graphics.setComponentTooltipForNextFrame(this.font, this.tooltip(hovered, ranks), mouseX, mouseY);
		}
	}

	private void drawNode(final GuiGraphicsExtractor graphics, final Perk perk, final PerkShop.Outcome state, final boolean free) {
		int x = this.nodeX(perk), y = this.nodeY(perk);
		int colour = switch (state) {
			case OWNED -> OWNED;
			case OK -> AVAILABLE;
			default -> LOCKED;
		};
		if (perk.tier() < Perk.TIERS) {
			// the link to the next tier, lit once this one is owned
			graphics.fill(x + NODE / 2 - 1, y + NODE, x + NODE / 2 + 1, y + NODE_STEP, state == PerkShop.Outcome.OWNED ? OWNED : LOCKED);
		}

		// the slot well is A's background; a node that can be bought now is lit like a hovered slot, and every node gets its state's rim
		if (state == PerkShop.Outcome.OK) {
			graphics.fill(x + 1, y + 1, x + NODE - 1, y + NODE - 1, 0x80FFFFFF);
		}

		graphics.outline(x, y, NODE, NODE, colour);
		if (state == PerkShop.Outcome.OWNED) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, TOKEN, x + 3, y + 3, 0.0F, 0.0F, 16, 16, 16, 16);
		} else {
			// the branch emblem, faint: lit up yellow when it can be bought now
			int tint = state == PerkShop.Outcome.OK ? 0xFFFFFFFF : 0x60FFFFFF;
			graphics.blit(RenderPipelines.GUI_TEXTURED, texture("item/skill_" + Perk.BRANCHES[perk.branch()]), x + 3, y + 3, 0.0F, 0.0F, 16, 16, 16, 16, tint);
		}

		Component cost = Component.literal(Integer.toString(perk.cost()));
		graphics.pose().pushMatrix();
		// right of the node, clear of the link line below it
		graphics.pose().translate(x + NODE + 2.0F, y + NODE / 2.0F - 3.0F);
		graphics.pose().scale(0.75F, 0.75F);
		int costColour = free ? 0xFF8B8B8B : state == PerkShop.Outcome.TOO_POOR ? 0xFFAA0000 : TEXT;
		graphics.text(this.font, cost, 0, 0, costColour, false);
		graphics.pose().popMatrix();
	}

	private List<Component> tooltip(final Perk perk, final Map<String, Integer> ranks) {
		List<Component> lines = new ArrayList<>();
		String key = "perk.cyberblock." + perk.id();
		lines.add(Component.translatable(key).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		if (ranks.getOrDefault(perk.id(), 0) > 0) {
			lines.add(Component.translatable("cyberblock.skills.owned").withStyle(ChatFormatting.GREEN));
		} else {
			lines.add(Component.translatable("cyberblock.skills.cost", perk.cost()).withStyle(ChatFormatting.YELLOW));
		}

		Perk before = perk.prerequisite();
		lines.add(Component.translatable("cyberblock.skills.requires",
			before == null ? Component.translatable("cyberblock.skills.none") : Component.translatable("perk.cyberblock." + before.id()))
			.withStyle(ChatFormatting.GRAY));
		lines.add(Component.translatable("cyberblock.skills.minecraft", Component.translatable(key + ".mc")).withStyle(ChatFormatting.WHITE));
		lines.add(Component.translatable("cyberblock.skills.cyberpunk", Component.translatable(key + ".cp")).withStyle(ChatFormatting.LIGHT_PURPLE));
		return lines;
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		Perk perk = this.perkAt(event.x(), event.y());
		// the server applies the purchase rules and takes the levels (megaplan task 1-C1: `act t:"perk"`)
		if (perk == null || !CyberLink.act(CyberActs.PERK, CyberActs.perk(perk))) {
			return super.mouseClicked(event, doubleClick);
		}

		return true;
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (CyberKeys.skills.matches(event)) {
			this.onClose();
			return true;
		}

		return super.keyPressed(event);
	}
}
