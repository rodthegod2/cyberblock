package dev.cyberblock.device.block;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A readable Night City container's loot in the vanilla chest view (section 1.11: contents are read only). The server (4M) opens it
 * when a player asks to {@code view} a readable {@link CrateBlock}. Every click is refused: the items are copies of what the host's
 * game shows, nothing can be taken or put in, and the server's menu never changes, so the client's view snaps back.
 */
public final class LootView {
	/** One chest row is 9 slots; three rows hold what a view can show. */
	public static final int SLOTS = 27;

	private LootView() {
	}

	/** Opens the view (the first {@link #SLOTS} stacks); {@code title} null: "Loot". */
	public static void open(final ServerPlayer player, final @Nullable Component title, final List<ItemStack> items) {
		SimpleContainer contents = new SimpleContainer(SLOTS);
		for (int i = 0; i < Math.min(items.size(), SLOTS); i++) {
			contents.setItem(i, items.get(i).copy());
		}

		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ReadOnly(id, inventory, contents), title == null ? title() : title));
	}

	/** The default title of a loot view. */
	public static Component title() {
		return Component.translatableWithFallback("cyberblock.device.loot", "Loot (look only)");
	}

	/** The vanilla 9x3 chest menu with every click refused. */
	static final class ReadOnly extends ChestMenu {
		ReadOnly(final int id, final Inventory inventory, final SimpleContainer contents) {
			super(MenuType.GENERIC_9x3, id, inventory, contents, 3);
		}

		@Override
		public void clicked(final int slot, final int button, final ContainerInput input, final Player player) {
			// read only
		}

		@Override
		public ItemStack quickMoveStack(final Player player, final int slot) {
			return ItemStack.EMPTY;
		}

		@Override
		public boolean stillValid(final Player player) {
			return player.isAlive();
		}
	}
}
