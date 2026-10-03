package dev.cyberblock.cyber;

import dev.cyberblock.Cyberblock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

/**
 * The starter kit (task C8): everything the layers need to be tried, handed out once automatically on the first
 * activation in survival, and by `/cyberblock kit` at any time.
 */
public final class Kit {
	/** One kind of item: an item id, how many, and a potion id for potions (else null). */
	public record Entry(String item, int count, @Nullable String potion) {
		Entry(final String item, final int count) {
			this(item, count, null);
		}
	}

	public static final List<Entry> ENTRIES = entries();

	private Kit() {
	}

	private static List<Entry> entries() {
		List<Entry> e = new ArrayList<>();
		e.add(new Entry("cyberblock:cyberdeck", 1));
		e.add(new Entry("cyberblock:ram_shard", 2));
		for (Hack hack : Hack.values()) {
			e.add(new Entry("cyberblock:" + hack.item(), 1));
		}

		e.add(new Entry("minecraft:stone", 64));
		e.add(new Entry("minecraft:glass", 64));
		e.add(new Entry("minecraft:tnt", 8));
		e.add(new Entry("minecraft:flint_and_steel", 1));
		e.add(new Entry("minecraft:bow", 1));
		e.add(new Entry("minecraft:arrow", 32));
		e.add(new Entry("minecraft:shield", 1));
		e.add(new Entry("minecraft:iron_sword", 1));
		e.add(new Entry("minecraft:lead", 2));
		e.add(new Entry("minecraft:fishing_rod", 1));
		e.add(new Entry("minecraft:wind_charge", 8));
		e.add(new Entry("minecraft:ender_pearl", 8));
		e.add(new Entry("minecraft:elytra", 1));
		e.add(new Entry("minecraft:firework_rocket", 16));
		e.add(new Entry("minecraft:potion", 1, "minecraft:swiftness"));
		e.add(new Entry("minecraft:potion", 1, "minecraft:invisibility"));
		e.add(new Entry("minecraft:potion", 1, "minecraft:leaping"));
		e.add(new Entry("minecraft:zombie_spawn_egg", 4));
		e.add(new Entry("minecraft:skeleton_spawn_egg", 2));
		e.add(new Entry("minecraft:creeper_spawn_egg", 2));
		e.add(new Entry("cyberblock:mantis_blade", 1));
		e.add(new Entry("cyberblock:gorilla_gauntlet", 1));
		return List.copyOf(e);
	}

	/** The stacks of the kit; an id that does not resolve is logged and left out. */
	static List<ItemStack> stacks() {
		List<ItemStack> stacks = new ArrayList<>();
		for (Entry e : ENTRIES) {
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(e.item()));
			if (item == Items.AIR) {
				Cyberblock.LOG.warn("kit: no item {}", e.item());
				continue;
			}

			if (e.potion() == null) {
				stacks.add(new ItemStack(item, e.count()));
			} else {
				BuiltInRegistries.POTION.get(Identifier.parse(e.potion())).ifPresentOrElse(
					potion -> stacks.add(PotionContents.createItemStack(item, potion).copyWithCount(e.count())),
					() -> Cyberblock.LOG.warn("kit: no potion {}", e.potion()));
			}
		}

		return stacks;
	}

	/** Gives the kit: the elytra goes on when the chest slot is empty, the rest into the inventory, what does not fit drops at his feet. */
	public static void give(final ServerPlayer player) {
		for (ItemStack stack : stacks()) {
			if (stack.is(Items.ELYTRA) && player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
				player.setItemSlot(EquipmentSlot.CHEST, stack);
			} else {
				player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
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
