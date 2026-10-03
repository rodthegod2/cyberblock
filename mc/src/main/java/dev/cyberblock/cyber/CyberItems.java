package dev.cyberblock.cyber;

import dev.cyberblock.Cyberblock;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** The cyber items (plan section 1.5 and task C1), registered at startup on both sides. */
public final class CyberItems {
	public static final Map<Hack, QuickhackItem> QUICKHACKS = new EnumMap<>(Hack.class);
	/** Needed in the inventory to have RAM, and so to hack. */
	public static Item CYBERDECK;
	/** Consumed for +4 RAM. */
	public static Item RAM_SHARD;
	/** Cyberware sword: 7 damage, attack speed 1.8. */
	public static Item MANTIS_BLADE;
	/** Cyberware fist: 4 damage and knockback +2 (a proxy's impulse follows knockback, so it shoves cars). */
	public static Item GORILLA_GAUNTLET;

	private CyberItems() {
	}

	static void register() {
		for (Hack hack : Hack.values()) {
			QUICKHACKS.put(hack, register(hack.item(), p -> new QuickhackItem(hack, p), new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
		}

		CYBERDECK = register("cyberdeck", Item::new, new Item.Properties().stacksTo(1).rarity(Rarity.RARE));
		RAM_SHARD = register("ram_shard", RamShardItem::new, new Item.Properties().stacksTo(16));
		// diamond's sword bonus is 3: 1 (hand) + 3 + 3 = 7 damage; 4 - 2.2 = 1.8 attacks per second
		MANTIS_BLADE = register("mantis_blade", Item::new, new Item.Properties().sword(ToolMaterial.DIAMOND, 3.0F, -2.2F).rarity(Rarity.RARE));
		GORILLA_GAUNTLET = register("gorilla_gauntlet", Item::new, new Item.Properties().stacksTo(1).rarity(Rarity.RARE).attributes(
			ItemAttributeModifiers.builder()
				.add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, 3.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
				.add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, -2.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
				.add(Attributes.ATTACK_KNOCKBACK, new AttributeModifier(id("gauntlet_knockback"), 2.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
				.build()
		));

		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("cyberblock"));
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, tab, FabricCreativeModeTab.builder()
			.title(Component.translatable("itemGroup.cyberblock"))
			.icon(() -> new ItemStack(CYBERDECK))
			.displayItems((parameters, output) -> {
				output.accept(CYBERDECK);
				output.accept(RAM_SHARD);
				QUICKHACKS.values().forEach(output::accept);
				output.accept(MANTIS_BLADE);
				output.accept(GORILLA_GAUNTLET);
			})
			.build());
	}

	private static <T extends Item> T register(final String name, final Function<Item.Properties, T> factory, final Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties.setId(key)));
	}

	private static Identifier id(final String path) {
		return Identifier.fromNamespaceAndPath(Cyberblock.ID, path);
	}
}
