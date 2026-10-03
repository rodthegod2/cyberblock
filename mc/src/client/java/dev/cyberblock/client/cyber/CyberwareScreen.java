package dev.cyberblock.client.cyber;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.CyberMod;
import dev.cyberblock.cyber.CyberSlot;
import dev.cyberblock.cyber.Cyberware;
import dev.cyberblock.cyber.CyberwareItem;
import dev.cyberblock.cyber.CyberwareRules;
import dev.cyberblock.cyber.Ripperdoc;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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
import net.minecraft.world.item.ItemStack;

/**
 * The ripperdoc (plan retest2 section 1.7, key U): on art/gui/cyberware_bg.png, the eight body slots on the left (a frame
 * per place) and the implants he could install on the right (the ones in his inventory; every one in creative). A
 * left-click installs an available implant or takes out an installed one. Each installed ACTIVE implant shows the key that
 * fires it (Y Operating System, L Arms, O camo: plan retest3 section 1.7), and the three keys are listed under the
 * implants. Driven by the host's cursor and clicks like every screen; the game keeps running behind it. The integrated server does the work (singleplayer only, as the skill tree).
 */
public final class CyberwareScreen extends Screen {
	private static final Identifier BACKGROUND = Identifier.fromNamespaceAndPath(Cyberblock.ID, "textures/gui/cyberware_bg.png");
	private static final int PANEL_W = 256;
	private static final int PANEL_H = 192;
	private static final int FRAME = 18;
	private static final int BOX_W = 60;
	private static final int BOX_H = 38;
	private static final int BOX_STEP = 42;
	private static final int CELL = 22;
	private static final int COLUMNS = 4;
	/** One page: 4 x 6 cells. There are 17 implants, so it always holds every kind he has. */
	private static final int PAGE = 24;
	private static final int INSTALLED = 0xFF5FF4FF;
	private static final int EMPTY = 0xFF4A5478;
	private static final int KEY = 0xFFFFE14D;

	public CyberwareScreen() {
		super(Component.translatable("cyberblock.cyberware.title"));
	}

	private int left() {
		return Math.max(0, (this.width - PANEL_W) / 2);
	}

	private int top() {
		return Math.max(0, (this.height - PANEL_H) / 2);
	}

	/** Slot boxes: the first four in the left column, the rest in the right one. */
	private int boxX(final CyberSlot slot) {
		return this.left() + 8 + slot.ordinal() / 4 * (BOX_W + 4);
	}

	private int boxY(final CyberSlot slot) {
		return this.top() + 20 + slot.ordinal() % 4 * BOX_STEP;
	}

	private int frameX(final CyberSlot slot, final int n) {
		return this.boxX(slot) + 3 + n * (FRAME + 2);
	}

	private int frameY(final CyberSlot slot) {
		return this.boxY(slot) + 16;
	}

	private int cellX(final int i) {
		return this.left() + 150 + i % COLUMNS * CELL;
	}

	private int cellY(final int i) {
		return this.top() + 32 + i / COLUMNS * CELL;
	}

	private static boolean inside(final double x, final double y, final int x0, final int y0, final int size) {
		return x >= x0 && x < x0 + size && y >= y0 && y < y0 + size;
	}

	/** What he could install: every implant in creative, else the kinds in his inventory, in catalogue order. */
	private List<Cyberware> available(final LocalPlayer player) {
		if (player.isCreative()) {
			return List.of(Cyberware.values());
		}

		Set<Cyberware> kinds = EnumSet.noneOf(Cyberware.class);
		for (ItemStack stack : player.getInventory()) {
			if (stack.getItem() instanceof CyberwareItem item) {
				kinds.add(item.cyberware());
			}
		}

		return new ArrayList<>(kinds).subList(0, Math.min(PAGE, kinds.size()));
	}

	@Override
	public boolean isPauseScreen() {
		// singleplayer pauses the server under a pause screen: Night City keeps going, so Minecraft must too
		return false;
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		LocalPlayer player = this.minecraft.player;
		if (player == null) {
			return;
		}

		int left = this.left(), top = this.top();
		graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, left, top, 0.0F, 0.0F, PANEL_W, PANEL_H, PANEL_W, PANEL_H);
		graphics.centeredText(this.font, this.title, left + PANEL_W / 2, top + 6, 0xFFFFFFFF);
		Map<String, String> slots = CyberData.slots(player);
		ItemStack hovered = ItemStack.EMPTY;
		for (CyberSlot slot : CyberSlot.values()) {
			int x = this.boxX(slot), y = this.boxY(slot);
			graphics.fill(x, y, x + BOX_W, y + BOX_H, 0xC00A0C15);
			graphics.outline(x, y, BOX_W, BOX_H, 0xFF3A4468);
			// three quarters size, smaller still when the name would run out of its box ("Operating System")
			Component label = Component.translatable(slot.langKey());
			float scale = Math.min(0.75F, (BOX_W - 6) / (float) Math.max(1, this.font.width(label)));
			graphics.pose().pushMatrix();
			graphics.pose().translate(x + 3, y + 4);
			graphics.pose().scale(scale, scale);
			graphics.text(this.font, label, 0, 0, 0xFFD6DEFF, false);
			graphics.pose().popMatrix();
			for (int n = 0; n < slot.capacity(); n++) {
				Cyberware in = Cyberware.byId(slots.get(slot.key(n)));
				int fx = this.frameX(slot, n), fy = this.frameY(slot);
				graphics.fill(fx, fy, fx + FRAME, fy + FRAME, 0xFF141826);
				graphics.outline(fx, fy, FRAME, FRAME, in == null ? EMPTY : INSTALLED);
				if (in != null) {
					ItemStack stack = new ItemStack(in.item());
					graphics.item(stack, fx + 1, fy + 1);
					CyberwareRules.Trigger trigger = CyberwareRules.Trigger.of(in);
					if (trigger != null) {
						Component key = CyberKeys.of(trigger).getTranslatedKeyMessage();
						graphics.text(this.font, key, fx + FRAME - this.font.width(key) + 1, fy - 3, KEY, true);
					}

					if (inside(mouseX, mouseY, fx, fy, FRAME)) {
						hovered = stack;
					}
				}
			}
		}

		graphics.text(this.font, Component.translatable("cyberblock.cyberware.screen.available"), this.cellX(0), top + 20, 0xFFD6DEFF, false);
		List<Cyberware> available = this.available(player);
		for (int i = 0; i < available.size(); i++) {
			Cyberware c = available.get(i);
			int x = this.cellX(i), y = this.cellY(i);
			graphics.fill(x, y, x + FRAME, y + FRAME, 0xFF141826);
			graphics.outline(x, y, FRAME, FRAME, EMPTY);
			ItemStack stack = new ItemStack(c.item());
			graphics.item(stack, x + 1, y + 1);
			if (slots.containsValue(c.id())) {
				// already installed: dimmed
				graphics.fill(x + 1, y + 1, x + FRAME - 1, y + FRAME - 1, 0xA00A0C15);
			}

			if (inside(mouseX, mouseY, x, y, FRAME)) {
				hovered = stack;
			}
		}

		int textX = this.cellX(0), textWidth = COLUMNS * CELL;
		if (available.isEmpty()) {
			graphics.textWithWordWrap(this.font, Component.translatable("cyberblock.cyberware.screen.empty"), textX, this.cellY(0), textWidth, 0xFF9AA6C8);
		}

		Set<Cyberware> installed = CyberwareRules.installed(slots);
		// under the last row of implants (17 implants: 5 rows at most), or the empty note
		int keyY = this.cellY((Math.max(1, available.size()) + COLUMNS - 1) / COLUMNS * COLUMNS) + (available.isEmpty() ? 12 : 2);
		for (CyberwareRules.Trigger trigger : CyberwareRules.Trigger.values()) {
			Cyberware fires = CyberwareRules.forTrigger(installed, trigger);
			Component line = Component.empty().append(CyberKeys.of(trigger).getTranslatedKeyMessage()).append(": ")
				.append(fires == null ? Component.literal("-") : Ripperdoc.name(fires));
			graphics.text(this.font, line, textX, keyY, KEY, false);
			keyY += 10;
		}
		Component note = CyberKeys.note();
		if (note != null) {
			graphics.centeredText(this.font, note, this.width / 2, Math.min(this.height - 10, top + PANEL_H + 4), 0xFFFF8A2B);
		}

		if (!hovered.isEmpty()) {
			graphics.setTooltipForNextFrame(this.font, hovered, mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		LocalPlayer player = this.minecraft.player;
		boolean left = event.button() == InputConstants.MOUSE_BUTTON_LEFT;
		boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
		if (player == null || !left && !right) {
			return super.mouseClicked(event, doubleClick);
		}

		Map<String, String> slots = CyberData.slots(player);
		for (CyberSlot slot : CyberSlot.values()) {
			for (int n = 0; n < slot.capacity(); n++) {
				String key = slot.key(n);
				Cyberware in = Cyberware.byId(slots.get(key));
				if (in != null && inside(event.x(), event.y(), this.frameX(slot, n), this.frameY(slot), FRAME)) {
					if (left) {
						this.onServer(p -> Ripperdoc.remove(p, key));
					}

					return true;
				}
			}
		}

		List<Cyberware> available = this.available(player);
		for (int i = 0; i < available.size(); i++) {
			Cyberware c = available.get(i);
			if (left && inside(event.x(), event.y(), this.cellX(i), this.cellY(i), FRAME)) {
				this.onServer(p -> Ripperdoc.install(p, c));
				return true;
			}
		}

		return super.mouseClicked(event, doubleClick);
	}

	/** Singleplayer only (spec): the integrated server applies the ripperdoc's rules. */
	private void onServer(final Consumer<ServerPlayer> task) {
		IntegratedServer server = this.minecraft.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> {
				ServerPlayer player = CyberMod.localPlayer();
				if (player != null) {
					task.accept(player);
				}
			});
		}
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (CyberKeys.cyberware.matches(event)) {
			this.onClose();
			return true;
		}

		return super.keyPressed(event);
	}
}
