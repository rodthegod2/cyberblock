package dev.cyberblock.client.cyber;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.CyberMod;
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
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
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
	private static final int OWNED = 0xFF5FF4FF;
	private static final int AVAILABLE = 0xFFFFE14D;
	private static final int LOCKED = 0xFF4A5478;

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
		graphics.centeredText(this.font, this.title, left + PANEL / 2, top + 8, 0xFFFFFFFF);
		Map<String, Integer> ranks = this.ranks();
		int levels = this.levels();
		for (int b = 0; b < Perk.BRANCHES.length; b++) {
			int cx = left + b * COLUMN + COLUMN / 2;
			graphics.blit(RenderPipelines.GUI_TEXTURED, texture("item/skill_" + Perk.BRANCHES[b]), cx - 8, top + 22, 0.0F, 0.0F, 16, 16, 16, 16);
			Component label = Component.translatable("cyberblock.skills.branch." + Perk.BRANCHES[b]);
			graphics.pose().pushMatrix();
			graphics.pose().translate(cx, top + 42);
			graphics.pose().scale(0.75F, 0.75F);
			graphics.centeredText(this.font, label, 0, 0, 0xFFD6DEFF);
			graphics.pose().popMatrix();
		}

		for (Perk perk : Perk.values()) {
			this.drawNode(graphics, perk, PerkShop.check(perk, ranks, levels));
		}

		graphics.centeredText(this.font, Component.translatable("cyberblock.skills.levels", levels), left + PANEL / 2, top + PANEL - 16, 0xFF7CFF5A);
		Component note = CyberKeys.note();
		if (note != null) {
			graphics.centeredText(this.font, note, this.width / 2, Math.min(this.height - 10, top + PANEL + 4), 0xFFFF8A2B);
		}

		Perk hovered = this.perkAt(mouseX, mouseY);
		if (hovered != null) {
			graphics.setComponentTooltipForNextFrame(this.font, this.tooltip(hovered, ranks), mouseX, mouseY);
		}
	}

	private void drawNode(final GuiGraphicsExtractor graphics, final Perk perk, final PerkShop.Outcome state) {
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

		graphics.fill(x, y, x + NODE, y + NODE, 0xFF0A0C15);
		graphics.outline(x, y, NODE, NODE, colour);
		if (state == PerkShop.Outcome.OWNED) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, TOKEN, x + 3, y + 3, 0.0F, 0.0F, 16, 16, 16, 16);
		} else {
			// the branch emblem, faint: lit up yellow when it can be bought now
			int tint = state == PerkShop.Outcome.OK ? 0xC0FFFFFF : 0x50FFFFFF;
			graphics.blit(RenderPipelines.GUI_TEXTURED, texture("item/skill_" + Perk.BRANCHES[perk.branch()]), x + 3, y + 3, 0.0F, 0.0F, 16, 16, 16, 16, tint);
		}

		Component cost = Component.literal(Integer.toString(perk.cost()));
		graphics.pose().pushMatrix();
		graphics.pose().translate(x + NODE / 2.0F, y + NODE + 2);
		graphics.pose().scale(0.75F, 0.75F);
		graphics.centeredText(this.font, cost, 0, 0, state == PerkShop.Outcome.TOO_POOR ? 0xFFFF5A5A : 0xFF9AA6C8);
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
		IntegratedServer server = this.minecraft.getSingleplayerServer();
		if (perk == null || server == null) {
			return super.mouseClicked(event, doubleClick);
		}

		// singleplayer only (spec): the integrated server applies the purchase rules and takes the levels
		server.execute(() -> {
			ServerPlayer player = CyberMod.localPlayer();
			if (player != null) {
				PerkShop.buy(player, perk);
			}
		});
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
