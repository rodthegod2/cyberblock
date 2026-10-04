package dev.cyberblock.hack.redstone;

import dev.cyberblock.cyber.CyberItems;
import dev.cyberblock.cyber.Hack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The screens of the Proximity Sensor and the Breach Port (megaplan 6-R1) as a vanilla chest screen whose slots are buttons, so the
 * server alone decides (no client code, nothing a client can forge but a click). Nothing can be taken out or put in.
 * <ul>
 * <li>Proximity Sensor (one row): people, cars, V on or off; range down, the range (1-16 blocks), range up.</li>
 * <li>Breach Port (two rows): the nine quickhacks for people (the chosen one glows); range down, range, range up, the Cyberdeck
 * block's RAM, and Breach Protocol on the linked device.</li>
 * </ul>
 */
final class HackMenu extends ChestMenu {
	/** Slots of the proximity row. */
	static final int PERSON = 0, CAR = 1, V = 2, RANGE_DOWN = 4, RANGE = 5, RANGE_UP = 6;
	/** Slots of the Breach Port's second row. */
	static final int PORT_RANGE_DOWN = 9, PORT_RANGE = 10, PORT_RANGE_UP = 11, PORT_RAM = 13, PORT_BREACH = 15;
	/** The quickhacks a Breach Port fires at people (the Redstone Lever works on devices only). */
	static final List<Hack> HACKS = java.util.Arrays.stream(Hack.values()).filter(h -> !h.needsDevice()).toList();

	private final ServerLevel level;
	private final BlockPos pos;
	private final boolean port;
	private final SimpleContainer buttons;

	private HackMenu(final int id, final Inventory inventory, final ServerLevel level, final BlockPos pos, final boolean port, final SimpleContainer buttons) {
		super(port ? MenuType.GENERIC_9x2 : MenuType.GENERIC_9x1, id, inventory, buttons, port ? 2 : 1);
		this.level = level;
		this.pos = pos;
		this.port = port;
		this.buttons = buttons;
		this.refresh();
	}

	static void open(final ServerPlayer player, final ServerLevel level, final BlockPos pos, final boolean port) {
		Component title = port ? Component.translatableWithFallback("cyberblock.redstone.breach_port.title", "Breach Port")
			: Component.translatableWithFallback("cyberblock.redstone.proximity.title", "Proximity Sensor");
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new HackMenu(id, inventory, level, pos, port, new SimpleContainer(port ? 18 : 9)), title));
	}

	private HackBlockEntity entity() {
		return this.level.getBlockEntity(this.pos) instanceof HackBlockEntity be ? be : null;
	}

	@Override
	public boolean stillValid(final Player player) {
		return this.entity() != null && player.distanceToSqr(this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5) <= 64.0;
	}

	@Override
	public ItemStack quickMoveStack(final Player player, final int slot) {
		return ItemStack.EMPTY;
	}

	/** Only a plain click on a button does something; every other click is undone (the client is told the true slots again). */
	@Override
	public void clicked(final int slot, final int button, final ContainerInput input, final Player player) {
		HackBlockEntity be = this.entity();
		if (be != null && input == ContainerInput.PICKUP && slot >= 0 && slot < this.buttons.getContainerSize() && player instanceof ServerPlayer p) {
			if (this.press(be, slot, p)) {
				this.level.playSound(null, this.pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.4F, 1.2F);
			}

			this.refresh();
		}

		this.sendAllDataToRemote();
	}

	private boolean press(final HackBlockEntity be, final int slot, final ServerPlayer player) {
		if (!this.port) {
			switch (slot) {
				case PERSON -> be.mask(be.mask() ^ RedstoneRules.PERSON);
				case CAR -> be.mask(be.mask() ^ RedstoneRules.CAR);
				case V -> be.mask(be.mask() ^ RedstoneRules.V);
				case RANGE_DOWN -> be.range(be.range() - 1);
				case RANGE_UP -> be.range(be.range() + 1);
				default -> {
					return false;
				}
			}
		} else if (slot < HACKS.size()) {
			be.hack(HACKS.get(slot));
		} else {
			switch (slot) {
				case PORT_RANGE_DOWN -> be.range(be.range() - 1);
				case PORT_RANGE_UP -> be.range(be.range() + 1);
				case PORT_BREACH -> {
					player.closeContainer();
					RedstoneHacks.breach(this.level, this.pos, player);
					return true;
				}
				default -> {
					return false;
				}
			}
		}

		be.actor(player.getUUID()); // who configured it acts through it
		return true;
	}

	private void refresh() {
		HackBlockEntity be = this.entity();
		if (be == null) {
			return;
		}

		for (int i = 0; i < this.buttons.getContainerSize(); i++) {
			this.buttons.setItem(i, ItemStack.EMPTY);
		}

		if (!this.port) {
			this.buttons.setItem(PERSON, toggle(Items.ARMOR_STAND, "cyberblock.redstone.proximity.person", "People", (be.mask() & RedstoneRules.PERSON) != 0));
			this.buttons.setItem(CAR, toggle(Items.MINECART, "cyberblock.redstone.proximity.car", "Cars", (be.mask() & RedstoneRules.CAR) != 0));
			this.buttons.setItem(V, toggle(Items.ENDER_EYE, "cyberblock.redstone.proximity.v", "V, the host", (be.mask() & RedstoneRules.V) != 0));
			this.rangeButtons(be, RANGE_DOWN, RANGE, RANGE_UP);
			return;
		}

		for (int i = 0; i < HACKS.size(); i++) {
			Hack h = HACKS.get(i);
			ItemStack icon = new ItemStack(CyberItems.QUICKHACKS.get(h));
			boolean chosen = h == be.hack();
			icon.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, chosen);
			icon.set(DataComponents.LORE, lore(Component.translatableWithFallback("cyberblock.redstone.breach_port.cost", "%s RAM per target", h.ram()),
				chosen ? Component.translatableWithFallback("cyberblock.redstone.chosen", "Chosen") : Component.translatableWithFallback("cyberblock.redstone.breach_port.pick",
					"Pick the quickhack to fire")));
			this.buttons.setItem(i, icon);
		}

		this.rangeButtons(be, PORT_RANGE_DOWN, PORT_RANGE, PORT_RANGE_UP);
		HackBlockEntity deck = RedstoneHacks.deckNextTo(this.level, this.pos);
		long now = RedstoneHacks.now(this.level);
		this.buttons.setItem(PORT_RAM, button(RedstoneBlocks.CYBERDECK_BLOCK.asItem(), deck == null
			? Component.translatableWithFallback(RedstoneRules.Refusal.NO_DECK.key(), RedstoneRules.Refusal.NO_DECK.fallback())
			: Component.translatableWithFallback("cyberblock.redstone.cyberdeck.ram", "RAM %s of %s", deck.deck(now).ram(now),
				RedstoneRules.DECK_MAX_RAM)));
		this.buttons.setItem(PORT_BREACH, button(Items.COMPASS, be.link() == null
			? Component.translatableWithFallback("cyberblock.redstone.breach_port.breach_unlinked", "Breach Protocol: link a device first (Cyberdeck Link)")
			: Component.translatableWithFallback("cyberblock.redstone.breach_port.breach", "Breach Protocol on %s", be.linkLabel())));
	}

	private void rangeButtons(final HackBlockEntity be, final int down, final int show, final int up) {
		this.buttons.setItem(down, button(Items.STAINED_GLASS_PANE.red(), Component.translatableWithFallback("cyberblock.redstone.range_down", "Range -1")));
		ItemStack range = button(Items.REPEATER, Component.translatableWithFallback("cyberblock.redstone.proximity.range", "Range: %s blocks", be.range()));
		range.setCount(be.range());
		this.buttons.setItem(show, range);
		this.buttons.setItem(up, button(Items.STAINED_GLASS_PANE.lime(), Component.translatableWithFallback("cyberblock.redstone.range_up", "Range +1")));
	}

	private static ItemStack toggle(final Item item, final String key, final String fallback, final boolean on) {
		ItemStack stack = button(item, Component.translatableWithFallback(key, fallback));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, on);
		stack.set(DataComponents.LORE, lore(on ? Component.translatableWithFallback("cyberblock.redstone.watched", "Watched: click to ignore")
			: Component.translatableWithFallback("cyberblock.redstone.ignored", "Ignored: click to watch")));
		return stack;
	}

	private static ItemStack button(final Item item, final Component name) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.ITEM_NAME, name);
		return stack;
	}

	private static ItemLore lore(final Component... lines) {
		List<Component> out = new ArrayList<>();
		for (Component c : lines) {
			out.add(c.copy().withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)));
		}

		return new ItemLore(out);
	}
}
