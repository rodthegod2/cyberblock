package dev.cyberblock.client.device;

import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.device.block.DeviceBlocks;
import dev.cyberblock.device.block.DeviceHooks;
import dev.cyberblock.device.block.ShopWire;
import dev.cyberblock.net.Payloads;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The vending machine's shop (section 1.11 vending; section 1.16 eddies): what the server's {@code cyberblock:ui t:"shop"} lists,
 * in the Minecraft container look ({@link McTheme}), one row per line with its item, count, price and stock and a Buy button. The
 * server decides everything: Buy sends {@code devact buy} with the line id and the server answers with the shop again (new eddies,
 * new stock, the result). Buy is greyed when the player's eddies (as the server counted them) do not reach the price or the line is
 * sold out. The screen closes when the player walks away from the machine.
 */
public final class VendingScreen extends Screen {
	static final int WIDTH = 248;
	static final int ROW = 24;
	static final int PER_PAGE = 6;
	static final int TOP = 26;
	static final int BUY_WIDTH = 52;
	/** Blocks from the machine before the screen closes (the server's reach for a buy, less a margin). */
	static final double WALK_AWAY = 7.0;
	private static int bad;
	private ShopWire.Shop shop;
	private int page;

	private VendingScreen(final ShopWire.Shop shop) {
		super(title(shop));
		this.shop = shop;
	}

	/** {@code cyberblock:ui shop} (client thread): opens the screen, or refreshes it when it already shows this machine. */
	static void onUi(final String json) {
		ShopWire.Shop shop;
		try {
			shop = ShopWire.parseShop(JsonParser.parseString(json).getAsJsonObject());
		} catch (RuntimeException e) {
			shop = null;
		}

		if (shop == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("device: bad shop dropped ({} so far)", bad);
			}

			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}

		if (minecraft.gui.screen() instanceof VendingScreen open && open.same(shop)) {
			open.update(shop);
		} else {
			minecraft.gui.setScreen(new VendingScreen(shop));
		}
	}

	private static Component title(final ShopWire.Shop shop) {
		return shop.title().isEmpty() ? Component.translatableWithFallback("cyberblock.shop.title", "Vending machine") : Component.literal(shop.title());
	}

	private boolean same(final ShopWire.Shop other) {
		return other.x() == this.shop.x() && other.y() == this.shop.y() && other.z() == this.shop.z();
	}

	private void update(final ShopWire.Shop next) {
		this.shop = next;
		this.page = Math.min(this.page, pages(next.lines().size()) - 1);
		this.rebuildWidgets();
	}

	/** Pages for n lines (at least one). Pure. */
	static int pages(final int lines) {
		return Math.max(1, (lines + PER_PAGE - 1) / PER_PAGE);
	}

	/** Can the player buy this line, as far as the client can tell (the server checks again)? Pure. */
	static boolean affordable(final ShopWire.Line line, final int eddies) {
		return line.stock() != 0 && line.price() <= eddies;
	}

	private int left() {
		return (this.width - WIDTH) / 2;
	}

	private int top() {
		return (this.height - height()) / 2;
	}

	private static int height() {
		return TOP + PER_PAGE * ROW + 30;
	}

	@Override
	protected void init() {
		int left = this.left();
		int top = this.top();
		List<ShopWire.Line> lines = this.shop.lines();
		int first = this.page * PER_PAGE;
		for (int i = first; i < Math.min(lines.size(), first + PER_PAGE); i++) {
			ShopWire.Line line = lines.get(i);
			Button buy = Button.builder(Component.translatableWithFallback("cyberblock.shop.buy", "Buy"), b -> this.buy(line))
				.bounds(left + WIDTH - BUY_WIDTH - 8, top + TOP + (i - first) * ROW + 1, BUY_WIDTH, 20).build();
			buy.active = affordable(line, this.shop.eddies());
			this.addRenderableWidget(buy);
		}

		int pages = pages(lines.size());
		if (pages > 1) {
			int y = top + height() - 26;
			Button prev = Button.builder(Component.literal("<"), b -> this.turn(-1)).bounds(left + 8, y, 20, 20).build();
			Button next = Button.builder(Component.literal(">"), b -> this.turn(1)).bounds(left + WIDTH - 28, y, 20, 20).build();
			prev.active = this.page > 0;
			next.active = this.page < pages - 1;
			this.addRenderableWidget(prev);
			this.addRenderableWidget(next);
		}
	}

	private void turn(final int by) {
		this.page = Math.clamp(this.page + by, 0, pages(this.shop.lines().size()) - 1);
		this.rebuildWidgets();
	}

	private void buy(final ShopWire.Line line) {
		if (ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			ClientPlayNetworking.send(new Payloads.Act(DeviceHooks.T_DEVACT, ShopWire.buy(this.shop.x(), this.shop.y(), this.shop.z(), line.id())));
		}
	}

	@Override
	public void tick() {
		if (this.minecraft.player == null || this.minecraft.player.distanceToSqr(Vec3.atCenterOf(new BlockPos(this.shop.x(), this.shop.y(),
			this.shop.z()))) > WALK_AWAY * WALK_AWAY) {
			this.onClose();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		McTheme.panel(graphics, this.left(), this.top(), WIDTH, height());
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int left = this.left();
		int top = this.top();
		graphics.text(this.font, this.title, left + 8, top + 8, McTheme.PANEL_TEXT, false);
		Component eddies = Component.translatableWithFallback("cyberblock.shop.eddies", "%s eddies", this.shop.eddies());
		int eddiesX = left + WIDTH - 8 - this.font.width(eddies);
		graphics.text(this.font, eddies, eddiesX, top + 8, McTheme.PANEL_TEXT, false);
		if (DeviceBlocks.EDDIES != null) {
			graphics.item(new ItemStack(DeviceBlocks.EDDIES), eddiesX - 18, top + 3);
		}

		List<ShopWire.Line> lines = this.shop.lines();
		if (lines.isEmpty()) {
			graphics.centeredText(this.font, Component.translatableWithFallback("cyberblock.shop.empty", "Nothing for sale"), left + WIDTH / 2, top + TOP + 8,
				McTheme.PANEL_TEXT);
		}

		int first = this.page * PER_PAGE;
		for (int i = first; i < Math.min(lines.size(), first + PER_PAGE); i++) {
			this.row(graphics, lines.get(i), left + 8, top + TOP + (i - first) * ROW, mouseX, mouseY);
		}

		int pages = pages(lines.size());
		int bottom = top + height() - 20;
		if (pages > 1) {
			graphics.centeredText(this.font, Component.literal((this.page + 1) + " / " + pages), left + WIDTH / 2, bottom - 12, McTheme.PANEL_TEXT);
		}

		if (!this.shop.result().isEmpty()) {
			graphics.centeredText(this.font, result(this.shop.result()), left + WIDTH / 2, bottom, this.shop.result().equals("ok") ? McTheme.GREEN : McTheme.RED);
		}
	}

	private void row(final GuiGraphicsExtractor graphics, final ShopWire.Line line, final int x, final int y, final int mouseX, final int mouseY) {
		ItemStack stack = stack(line);
		McTheme.slot(graphics, x, y + 2, 18, 18);
		graphics.item(stack, x + 1, y + 3);
		graphics.itemDecorations(this.font, stack, x + 1, y + 3);
		int textWidth = WIDTH - 16 - 24 - BUY_WIDTH - 6;
		Component name = stack.getHoverName();
		graphics.text(this.font, this.font.plainSubstrByWidth(name.getString(), textWidth), x + 24, y + 2, McTheme.PANEL_TEXT, false);
		Component price = Component.translatableWithFallback("cyberblock.shop.price", "%s eddies", line.price());
		Component stock = line.stock() == 0 ? Component.translatableWithFallback("cyberblock.shop.sold_out", "sold out")
			: line.stock() > 0 ? Component.translatableWithFallback("cyberblock.shop.stock", "%s left", line.stock()) : Component.empty();
		graphics.text(this.font, price, x + 24, y + 12, line.price() <= this.shop.eddies() ? McTheme.DARK_GRAY : McTheme.RED, false);
		graphics.text(this.font, stock, x + 24 + this.font.width(price) + 6, y + 12, line.stock() == 0 ? McTheme.RED : McTheme.DARK_GRAY, false);
		if (mouseX >= x && mouseX < x + 18 && mouseY >= y + 2 && mouseY < y + 20) {
			graphics.setTooltipForNextFrame(this.font, stack, mouseX, mouseY);
		}
	}

	/** The line's item stack (a barrier when the client does not know the item). */
	static ItemStack stack(final ShopWire.Line line) {
		Identifier id = Identifier.tryParse(line.item());
		Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		return item == null ? new ItemStack(Items.BARRIER) : new ItemStack(item, line.n());
	}

	/** The words of a purchase result code. */
	static Component result(final String code) {
		String fallback = switch (code) {
			case "ok" -> "Bought";
			case "poor" -> "Not enough eddies";
			case "stock" -> "Sold out";
			case "full" -> "No room in your inventory";
			case "far" -> "Too far from the machine";
			case "gone" -> "This machine is gone";
			case "refused" -> "The host does not let guests buy here";
			default -> code;
		};
		return Component.translatableWithFallback("cyberblock.shop.result." + code, fallback);
	}
}
