package dev.cyberblock.client.npc;

import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.net.Payloads;
import dev.cyberblock.npc.NpcWire;
import dev.cyberblock.npc.Surgery;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The ripperdoc's surgery (megaplan task 7-N1): a timing bar. The marker sweeps from one end to the other and back; cut (the button,
 * Space, Enter or a click on the bar) while it is inside the green zone. The server times the cut itself and answers with the
 * ripperdoc's screen and the result. Esc calls the surgery off (nothing paid, nothing lost); no cut before the time runs out counts
 * as a cut then.
 */
public final class SurgeryScreen extends Screen {
	static final int WIDTH = 240, HEIGHT = 96, BAR_W = 200, BAR_H = 14;
	private static int bad;
	private final NpcWire.Surgery surgery;
	private final long startNanos = System.nanoTime();
	private boolean cut;
	private Button cutButton;

	private SurgeryScreen(final NpcWire.Surgery surgery) {
		super(Component.translatableWithFallback("cyberblock.npc.surgery.title", "Surgery"));
		this.surgery = surgery;
	}

	/** {@code cyberblock:ui surgery} (client thread): the bar starts now. */
	static void onUi(final String json) {
		NpcWire.Surgery s;
		try {
			s = NpcWire.parseSurgery(JsonParser.parseString(json).getAsJsonObject());
		} catch (RuntimeException e) {
			s = null;
		}

		if (s == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("npc: bad surgery dropped ({} so far)", bad);
			}

			return;
		}

		Minecraft.getInstance().gui.setScreen(new SurgeryScreen(s));
	}

	private long elapsedMs() {
		return (System.nanoTime() - this.startNanos) / 1_000_000L;
	}

	private int left() {
		return (this.width - WIDTH) / 2;
	}

	private int top() {
		return (this.height - HEIGHT) / 2;
	}

	@Override
	protected void init() {
		this.cutButton = Button.builder(Component.translatableWithFallback("cyberblock.npc.surgery.cut", "Cut!"), b -> this.cut())
			.bounds(this.left() + WIDTH / 2 - 40, this.top() + HEIGHT - 28, 80, 20).build();
		this.cutButton.active = !this.cut;
		this.addRenderableWidget(this.cutButton);
	}

	private void send(final String a) {
		if (ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			ClientPlayNetworking.send(new Payloads.Act(NpcWire.ACT, NpcWire.act(this.surgery.e(), a, "")));
		}
	}

	private void cut() {
		if (!this.cut) {
			this.cut = true;
			this.cutButton.active = false;
			this.send("cut");
		}
	}

	@Override
	public void tick() {
		if (!this.cut && this.elapsedMs() > this.surgery.timeout()) {
			this.cut();
		}
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (event.isConfirmation() || event.isSelection()) {
			this.cut();
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		int x = this.left() + (WIDTH - BAR_W) / 2, y = this.top() + 40;
		if (event.x() >= x && event.x() < x + BAR_W && event.y() >= y - 4 && event.y() < y + BAR_H + 4) {
			this.cut();
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	/** Esc before the cut calls the surgery off. */
	@Override
	public void onClose() {
		if (!this.cut) {
			this.cut = true;
			this.send("abort");
		}

		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		McTheme.panel(graphics, this.left(), this.top(), WIDTH, HEIGHT);
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int left = this.left(), top = this.top();
		Identifier id = Identifier.tryParse(Cyberblock.ID + ":" + this.surgery.implant());
		ItemStack implant = id == null ? ItemStack.EMPTY : BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(ItemStack.EMPTY);
		if (!implant.isEmpty()) {
			graphics.item(implant, left + 8, top + 4);
		}

		Component name = implant.isEmpty() ? Component.literal(this.surgery.implant()) : implant.getHoverName();
		graphics.text(this.font, Component.translatableWithFallback("cyberblock.npc.surgery.of", "Surgery: %s", name), left + 28, top + 8, McTheme.PANEL_TEXT, false);
		long msLeft = Math.max(0, this.surgery.timeout() - this.elapsedMs());
		graphics.text(this.font, Component.translatableWithFallback("cyberblock.npc.surgery.hint", "Cut in the green (%s s)", (msLeft + 999) / 1000), left + 28,
			top + 22, McTheme.DARK_GRAY, false);
		int x = left + (WIDTH - BAR_W) / 2, y = top + 40;
		graphics.fill(x - 1, y - 1, x + BAR_W + 1, y + BAR_H + 1, McTheme.OUTLINE);
		graphics.fill(x, y, x + BAR_W, y + BAR_H, 0xFF3A3A3A);
		graphics.fill(x + Math.round(this.surgery.lo() * BAR_W), y, x + Math.round(this.surgery.hi() * BAR_W), y + BAR_H, 0xFF3FBF3F);
		if (!this.cut) {
			int m = x + (int) Math.round(Surgery.position(this.elapsedMs(), this.surgery.period()) * BAR_W);
			graphics.fill(m - 1, y - 3, m + 2, y + BAR_H + 3, 0xFFFFFFFF);
		} else {
			graphics.centeredText(this.font, Component.translatableWithFallback("cyberblock.npc.surgery.wait", "..."), left + WIDTH / 2, y + 3, 0xFFFFFFFF);
		}
	}
}
