package dev.cyberblock.client.npc;

import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.device.block.DeviceBlocks;
import dev.cyberblock.net.Payloads;
import dev.cyberblock.npc.NpcWire;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A role NPC's screen (megaplan task 7-N1): the NPC's name and line (original dialogue content), the player's eddies, the role's tabs
 * and its rows (an icon, a name, a second line, a price and one button each), in the Minecraft container look ({@link McTheme}).
 * Everything comes from the server's {@code cyberblock:ui npc}; a button sends {@code cyberblock:act npc} with the row id, and the
 * server answers with the screen again and a result. Closes when the player walks away from the NPC.
 */
public final class NpcScreen extends Screen {
	static final int WIDTH = 276, ROW = 24, PER_PAGE = 5, TOP = 76, BTN = 58, LINES = 3;
	/** Blocks from the NPC before the screen closes (the server's reach less a margin). */
	static final double WALK_AWAY = 7.0;
	/** Buttons whose price is what the player gets, not pays. */
	static final Set<String> EARNS = Set.of("sell", "take", "turn_in");
	/** Rows with no text and no meaningful item: their words come from the button's code. */
	static final Set<String> SERVICE_ROWS = Set.of("heal", "repair_vehicle", "ask");
	private static final Map<String, String> BUTTONS = Map.ofEntries(Map.entry("buy", "Buy"), Map.entry("sell", "Sell"),
		Map.entry("sold_out", "Sold out"), Map.entry("install", "Install"), Map.entry("surgery", "Surgery"), Map.entry("installed", "Installed"),
		Map.entry("take", "Take"), Map.entry("turn_in", "Turn in"), Map.entry("abandon", "Drop"), Map.entry("drink", "Drink"), Map.entry("ask", "Ask"),
		Map.entry("heal", "Heal"), Map.entry("repair", "Repair"), Map.entry("repair_vehicle", "Repair"));
	private static final Map<String, String> TABS = Map.of("buy", "Buy", "sell", "Sell", "implants", "Implants", "gigs", "Gigs", "drinks", "Drinks",
		"rumours", "Rumours", "heal", "Clinic", "repair", "Repairs", "none", "Closed");
	private static final Map<String, String> RESULTS = Map.ofEntries(Map.entry("ok", "Done"), Map.entry("sold", "Sold"),
		Map.entry("poor", "Not enough eddies"), Map.entry("stock", "Sold out"), Map.entry("gone", "No longer offered"),
		Map.entry("noitem", "You do not have the items"), Map.entry("busy", "Finish your gig first"), Map.entry("taken", "Gig taken"),
		Map.entry("done", "Gig done"), Map.entry("abandoned", "Gig dropped"), Map.entry("none", "Nothing to do"),
		Map.entry("installed", "Already installed"), Map.entry("recovering", "Still recovering from surgery"),
		Map.entry("surgery_ok", "The surgery went well"), Map.entry("surgery_fail", "The surgery failed"), Map.entry("cancelled", "Surgery called off"),
		Map.entry("healed", "Patched up"), Map.entry("repaired", "Repaired"), Map.entry("unavailable", "Not available yet"),
		Map.entry("kept", "Only enchanted, named or worn copies: kept"), Map.entry("drank", "Cheers"));
	private static final Set<String> GOOD = Set.of("ok", "sold", "taken", "done", "surgery_ok", "healed", "repaired", "drank");
	/** The English of each screen's role, when the role's title_key has no translation. */
	private static final Map<String, String> ROLES = Map.of("ripperdoc", "Ripperdoc", "shop", "Vendor", "contracts", "Fixer", "bartender", "Bartender",
		"medic", "Medic", "mechanic", "Mechanic");
	private static int bad;
	private NpcWire.Screen screen;
	private int page;

	private NpcScreen(final NpcWire.Screen screen) {
		super(title(screen));
		this.screen = screen;
	}

	/** {@code cyberblock:ui npc} (client thread): opens the screen, or refreshes it when it already shows this NPC. */
	static void onUi(final String json) {
		NpcWire.Screen s;
		try {
			s = NpcWire.parseScreen(JsonParser.parseString(json).getAsJsonObject());
		} catch (RuntimeException e) {
			s = null;
		}

		if (s == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("npc: bad npc screen dropped ({} so far)", bad);
			}

			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}

		if (minecraft.gui.screen() instanceof NpcScreen open && open.screen.e() == s.e() && open.screen.role().equals(s.role())) {
			open.update(s);
		} else {
			minecraft.gui.setScreen(new NpcScreen(s));
		}
	}

	static Component title(final NpcWire.Screen s) {
		Component role = Component.translatableWithFallback(s.title().isEmpty() ? "cyberblock.role." + s.role() : s.title(), ROLES.getOrDefault(s.role(), s.role()));
		return s.name().isEmpty() ? role : Component.translatableWithFallback("cyberblock.npc.named", "%s, %s", s.name(), role);
	}

	private void update(final NpcWire.Screen next) {
		boolean tabChanged = !next.tab().equals(this.screen.tab());
		this.screen = next;
		this.page = tabChanged ? 0 : Math.min(this.page, pages(next.rows().size()) - 1);
		this.rebuildWidgets();
	}

	/** Pages for n rows (at least one). Pure. */
	static int pages(final int rows) {
		return Math.max(1, (rows + PER_PAGE - 1) / PER_PAGE);
	}

	static Component button(final String code) {
		return Component.translatableWithFallback("cyberblock.npc.btn." + code, BUTTONS.getOrDefault(code, code));
	}

	static Component result(final String code) {
		return Component.translatableWithFallback("cyberblock.npc.result." + code, RESULTS.getOrDefault(code, code));
	}

	static boolean good(final String result) {
		return GOOD.contains(result);
	}

	/** A row's name: its text, else the service's words, else its item's name. */
	static Component rowName(final NpcWire.Row row, final ItemStack stack) {
		if (!row.key().isEmpty()) {
			return Component.translatableWithFallback(row.key(), row.text());
		}

		if (!row.text().isEmpty()) {
			return Component.literal(row.text());
		}

		return switch (row.btn()) {
			case "heal" -> Component.translatableWithFallback("cyberblock.npc.row.heal", "Heal %s hearts", row.n());
			case "repair_vehicle" -> Component.translatableWithFallback("cyberblock.npc.row.repair_vehicle", "Repair your Cyberpunk car");
			case "ask" -> Component.translatableWithFallback("cyberblock.npc.row.ask", "Heard any rumours?");
			default -> stack.getHoverName();
		};
	}

	private int left() {
		return (this.width - WIDTH) / 2;
	}

	private int top() {
		return (this.height - this.panelHeight()) / 2;
	}

	/** As tall as the rows shown: a full page when there are several. */
	private int panelHeight() {
		return height(this.screen.rows().size());
	}

	/** The panel's height for this many rows (at least one row's room, at most a page). Pure. */
	static int height(final int rows) {
		return TOP + Math.clamp(rows, 1, PER_PAGE) * ROW + 30;
	}

	@Override
	protected void init() {
		int left = this.left(), top = this.top();
		List<String> tabs = this.screen.tabs();
		if (tabs.size() > 1) {
			int w = (WIDTH - 16 - 4 * (tabs.size() - 1)) / tabs.size();
			for (int i = 0; i < tabs.size(); i++) {
				String tab = tabs.get(i);
				Button b = Button.builder(Component.translatableWithFallback("cyberblock.npc.tab." + tab, TABS.getOrDefault(tab, tab)), x -> this.act("tab", tab))
					.bounds(left + 8 + i * (w + 4), top + TOP - 24, w, 20).build();
				b.active = !tab.equals(this.screen.tab());
				this.addRenderableWidget(b);
			}
		}

		List<NpcWire.Row> rows = this.screen.rows();
		int first = this.page * PER_PAGE;
		for (int i = first; i < Math.min(rows.size(), first + PER_PAGE); i++) {
			NpcWire.Row row = rows.get(i);
			Button b = Button.builder(button(row.btn()), x -> this.act("do", row.id())).bounds(left + WIDTH - BTN - 8, top + TOP + (i - first) * ROW + 1, BTN, 20)
				.build();
			b.active = row.on();
			this.addRenderableWidget(b);
		}

		int pages = pages(rows.size());
		if (pages > 1) {
			int y = top + this.panelHeight() - 26;
			Button prev = Button.builder(Component.literal("<"), b -> this.turn(-1)).bounds(left + 8, y, 20, 20).build();
			Button next = Button.builder(Component.literal(">"), b -> this.turn(1)).bounds(left + WIDTH - 28, y, 20, 20).build();
			prev.active = this.page > 0;
			next.active = this.page < pages - 1;
			this.addRenderableWidget(prev);
			this.addRenderableWidget(next);
		}
	}

	private void turn(final int by) {
		this.page = Math.clamp(this.page + by, 0, pages(this.screen.rows().size()) - 1);
		this.rebuildWidgets();
	}

	private void act(final String a, final String arg) {
		if (ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			ClientPlayNetworking.send(new Payloads.Act(NpcWire.ACT, NpcWire.act(this.screen.e(), a, arg)));
		}
	}

	@Override
	public void tick() {
		Entity npc = this.minecraft.level == null ? null : this.minecraft.level.getEntity(this.screen.e());
		if (this.minecraft.player == null || npc == null || npc.isRemoved() || this.minecraft.player.distanceTo(npc) > WALK_AWAY) {
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
		McTheme.panel(graphics, this.left(), this.top(), WIDTH, this.panelHeight());
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int left = this.left(), top = this.top();
		graphics.text(this.font, this.title, left + 8, top + 8, McTheme.PANEL_TEXT, false);
		Component eddies = Component.translatableWithFallback("cyberblock.shop.balance", "Your eddies: %s", this.screen.eddies());
		int eddiesX = left + WIDTH - 8 - this.font.width(eddies);
		graphics.text(this.font, eddies, eddiesX, top + 8, McTheme.PANEL_TEXT, false);
		if (DeviceBlocks.EDDIES != null) {
			graphics.item(new ItemStack(DeviceBlocks.EDDIES), eddiesX - 18, top + 3);
		}

		if (!this.screen.line().isEmpty()) {
			List<FormattedCharSequence> lines = this.font.split(Component.literal("“" + this.screen.line() + "”"), WIDTH - 16);
			for (int i = 0; i < Math.min(LINES, lines.size()); i++) {
				graphics.text(this.font, lines.get(i), left + 8, top + 22 + i * 10, McTheme.DARK_GRAY, false);
			}
		}

		List<NpcWire.Row> rows = this.screen.rows();
		if (rows.isEmpty()) {
			graphics.centeredText(this.font, Component.translatableWithFallback("cyberblock.npc.empty", "Nothing here right now"), left + WIDTH / 2, top + TOP + 8,
				McTheme.PANEL_TEXT);
		}

		int first = this.page * PER_PAGE;
		for (int i = first; i < Math.min(rows.size(), first + PER_PAGE); i++) {
			this.row(graphics, rows.get(i), left + 8, top + TOP + (i - first) * ROW, mouseX, mouseY);
		}

		int pages = pages(rows.size());
		int bottom = top + this.panelHeight() - 20;
		if (pages > 1) {
			graphics.centeredText(this.font, Component.literal((this.page + 1) + " / " + pages), left + WIDTH / 2, bottom - 12, McTheme.PANEL_TEXT);
		}

		if (!this.screen.result().isEmpty()) {
			graphics.centeredText(this.font, result(this.screen.result()), left + WIDTH / 2, bottom, good(this.screen.result()) ? McTheme.GREEN : McTheme.RED);
		}
	}

	private void row(final GuiGraphicsExtractor graphics, final NpcWire.Row row, final int x, final int y, final int mouseX, final int mouseY) {
		ItemStack stack = stack(row);
		McTheme.slot(graphics, x, y + 2, 18, 18);
		if (!stack.isEmpty()) {
			graphics.item(stack, x + 1, y + 3);
			graphics.itemDecorations(this.font, stack, x + 1, y + 3);
		}

		int textWidth = WIDTH - 16 - 24 - BTN - 6;
		Component price = row.price() < 0 ? Component.empty() : EARNS.contains(row.btn())
			? Component.translatableWithFallback("cyberblock.npc.earn", "+%s eddies", row.price())
			: Component.translatableWithFallback("cyberblock.shop.price", "%s eddies", row.price());
		int priceWidth = this.font.width(price);
		graphics.text(this.font, this.font.plainSubstrByWidth(rowName(row, stack).getString(), textWidth - priceWidth - 4), x + 24, y + 2, McTheme.PANEL_TEXT, false);
		boolean dear = !EARNS.contains(row.btn()) && !row.on() && row.price() > this.screen.eddies();
		graphics.text(this.font, price, x + 24 + textWidth - priceWidth, y + 2, EARNS.contains(row.btn()) ? McTheme.GREEN : dear ? McTheme.RED : McTheme.DARK_GRAY, false);
		if (!row.sub().isEmpty()) {
			graphics.text(this.font, this.font.plainSubstrByWidth(row.sub(), textWidth), x + 24, y + 12, McTheme.DARK_GRAY, false);
		}

		if (mouseX >= x && mouseX < x + 24 + textWidth && mouseY >= y + 2 && mouseY < y + 22) {
			if (!row.sub().isEmpty() && this.font.width(row.sub()) > textWidth) {
				graphics.setTooltipForNextFrame(this.font, this.font.split(Component.literal(row.sub()), 200), mouseX, mouseY);
			} else if (!stack.isEmpty() && mouseX < x + 18) {
				graphics.setTooltipForNextFrame(this.font, stack, mouseX, mouseY);
			}
		}
	}

	/** The row's icon (EMPTY for none, a barrier when the client does not know the item). */
	static ItemStack stack(final NpcWire.Row row) {
		if (row.item().isEmpty()) {
			return ItemStack.EMPTY;
		}

		Identifier id = Identifier.tryParse(row.item());
		Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		return item == null || item == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(item, Math.clamp(row.n(), 1, 64));
	}
}
