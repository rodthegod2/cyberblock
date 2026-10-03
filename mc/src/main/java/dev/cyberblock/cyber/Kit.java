package dev.cyberblock.cyber;

import dev.cyberblock.Cyberblock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

/**
 * The starter kit (task C8): everything the layers need to be tried, handed out once automatically on the first
 * activation in survival, and by `/cyberblock kit` at any time. The hotbar gets what is used first (sword, bow, blocks,
 * TNT, flint and steel, pearls, rockets, lead, fishing rod), the shield goes in the offhand and the elytra on the chest;
 * the cyberdeck, the quickhack chips and the rest go in the main inventory, so they never crowd the hotbar.
 */
public final class Kit {
	/** {@link Entry#slot()}: the first free slot of the main inventory (9..35). */
	static final int MAIN = -1;
	/** {@link Entry#slot()}: the chest armour slot (Minecraft's inventory slot number: 36 + EquipmentSlot.CHEST's index). */
	static final int CHEST = 38;
	/** {@link Entry#slot()}: the offhand slot. */
	static final int OFFHAND = Inventory.SLOT_OFFHAND;
	private static final int MAIN_FIRST = 9;
	private static final int MAIN_END = 36;

	/**
	 * One kind of item: an item id, how many, a potion id for potions (else null), and where it goes: a hotbar slot 0..8,
	 * {@link #CHEST}, {@link #OFFHAND} or {@link #MAIN}.
	 */
	public record Entry(String item, int count, @Nullable String potion, int slot) {
		Entry(final String item, final int count, final int slot) {
			this(item, count, null, slot);
		}
	}

	public static final List<Entry> ENTRIES = entries();

	private Kit() {
	}

	private static List<Entry> entries() {
		List<Entry> e = new ArrayList<>();
		e.add(new Entry("minecraft:iron_sword", 1, 0));
		e.add(new Entry("minecraft:bow", 1, 1));
		e.add(new Entry("minecraft:stone", 64, 2));
		e.add(new Entry("minecraft:tnt", 8, 3));
		e.add(new Entry("minecraft:flint_and_steel", 1, 4));
		e.add(new Entry("minecraft:ender_pearl", 8, 5));
		e.add(new Entry("minecraft:firework_rocket", 16, 6));
		e.add(new Entry("minecraft:lead", 2, 7));
		e.add(new Entry("minecraft:fishing_rod", 1, 8));
		e.add(new Entry("minecraft:shield", 1, OFFHAND));
		e.add(new Entry("minecraft:elytra", 1, CHEST));

		e.add(new Entry("minecraft:arrow", 32, MAIN));
		e.add(new Entry("minecraft:glass", 64, MAIN));
		e.add(new Entry("minecraft:wind_charge", 8, MAIN));
		e.add(new Entry("minecraft:potion", 1, "minecraft:swiftness", MAIN));
		e.add(new Entry("minecraft:potion", 1, "minecraft:invisibility", MAIN));
		e.add(new Entry("minecraft:potion", 1, "minecraft:leaping", MAIN));
		e.add(new Entry("minecraft:zombie_spawn_egg", 4, MAIN));
		e.add(new Entry("minecraft:skeleton_spawn_egg", 2, MAIN));
		e.add(new Entry("minecraft:creeper_spawn_egg", 2, MAIN));
		e.add(new Entry("cyberblock:cyberdeck", 1, MAIN));
		e.add(new Entry("cyberblock:ram_shard", 2, MAIN));
		for (Hack hack : Hack.values()) {
			e.add(new Entry("cyberblock:" + hack.item(), 1, MAIN));
		}

		e.add(new Entry("cyberblock:mantis_blade", 1, MAIN));
		e.add(new Entry("cyberblock:gorilla_gauntlet", 1, MAIN));
		return List.copyOf(e);
	}

	/** The stack of one entry, or null (logged) when its id does not resolve. */
	private static @Nullable ItemStack stack(final Entry e) {
		Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(e.item()));
		if (item == Items.AIR) {
			Cyberblock.LOG.warn("kit: no item {}", e.item());
			return null;
		}

		if (e.potion() == null) {
			return new ItemStack(item, e.count());
		}

		ItemStack potion = BuiltInRegistries.POTION.get(Identifier.parse(e.potion()))
			.map(p -> PotionContents.createItemStack(item, p).copyWithCount(e.count())).orElse(null);
		if (potion == null) {
			Cyberblock.LOG.warn("kit: no potion {}", e.potion());
		}

		return potion;
	}

	/**
	 * The empty slot a kit stack takes when it tops up no stack he already has: its own slot (hotbar, chest, offhand) when
	 * that is empty, else the first empty main-inventory slot; -1 leaves it to vanilla (any free hotbar slot, else dropped
	 * at his feet).
	 */
	static int target(final int own, final IntPredicate empty) {
		if (own != MAIN && empty.test(own)) {
			return own;
		}

		for (int slot = MAIN_FIRST; slot < MAIN_END; slot++) {
			if (empty.test(slot)) {
				return slot;
			}
		}

		return -1;
	}

	/** Gives the kit (see the class comment); a stack he already has is topped up, what does not fit drops at his feet. */
	public static void give(final ServerPlayer player) {
		Inventory inventory = player.getInventory();
		for (Entry e : ENTRIES) {
			ItemStack stack = stack(e);
			if (stack == null) {
				continue;
			}

			int slot = inventory.getSlotWithRemainingSpace(stack) >= 0 ? -1 : target(e.slot(), s -> inventory.getItem(s).isEmpty());
			if (slot >= 0) {
				inventory.setItem(slot, stack);
				// as vanilla's placeItemBackInInventory: the client hears of the slot at once, whatever screen is open
				player.connection.send(inventory.createInventoryUpdatePacket(slot));
			} else {
				inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
			}
		}

		player.setAttached(CyberData.KIT_GIVEN, true);
	}

	/** The host says Cyberblock is active: a survival player who never had the kit gets it now. */
	public static void onActive(final ServerPlayer player) {
		if (!player.getAttachedOrElse(CyberData.KIT_GIVEN, false) && player.gameMode() == GameType.SURVIVAL) {
			give(player);
			player.sendSystemMessage(Component.translatable("cyberblock.kit.given"));
		}
	}
}
