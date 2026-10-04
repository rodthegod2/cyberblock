package dev.cyberblock.holo;

import dev.cyberblock.Cyberblock;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * The Holo-Table's two blocks (megaplan 7-T1), registered on both sides at startup with their items: {@code cyberblock:holo_table}
 * (hub furniture, unbreakable in survival, glows) and {@code cyberblock:spawn_beacon} (placeable in Night City, breakable, spared by
 * explosions, drops itself without a loot table; crafted from a compass, redstone and iron). Both are in the Cyberblock creative
 * tab. Their art (blockstates, models, textures, item definitions) and English names are
 * 7A's, under these ids.
 */
public final class HoloBlocks {
	public static final String HOLO_TABLE_ID = "holo_table";
	public static final String SPAWN_BEACON_ID = "spawn_beacon";
	public static Block HOLO_TABLE;
	public static Block SPAWN_BEACON;
	public static Item HOLO_TABLE_ITEM;
	public static Item SPAWN_BEACON_ITEM;

	private HoloBlocks() {
	}

	static void register() {
		HOLO_TABLE = block(HOLO_TABLE_ID, new HoloTableBlock(props(HOLO_TABLE_ID, MapColor.COLOR_CYAN, SoundType.METAL).strength(-1.0F, 3600000.8F)
			.noOcclusion().lightLevel(state -> 10).isValidSpawn(Blocks::never)));
		SPAWN_BEACON = block(SPAWN_BEACON_ID, new SpawnBeaconBlock(props(SPAWN_BEACON_ID, MapColor.COLOR_LIGHT_BLUE, SoundType.METAL).strength(1.5F, 1200.0F)
			.noLootTable().lightLevel(state -> 7).isValidSpawn(Blocks::never)));
		HOLO_TABLE_ITEM = item(HOLO_TABLE_ID, HOLO_TABLE);
		SPAWN_BEACON_ITEM = item(SPAWN_BEACON_ID, SPAWN_BEACON);
		// in the Cyberblock tab (CyberItems' tab, left untouched); survival players craft the beacon (recipe/spawn_beacon.json)
		CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("cyberblock"))).register(output -> {
			output.accept(HOLO_TABLE_ITEM);
			output.accept(SPAWN_BEACON_ITEM);
		});
	}

	private static Identifier id(final String name) {
		return Identifier.fromNamespaceAndPath(Cyberblock.ID, name);
	}

	private static BlockBehaviour.Properties props(final String name, final MapColor colour, final SoundType sound) {
		return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id(name))).mapColor(colour).sound(sound);
	}

	private static Block block(final String name, final Block block) {
		return Registry.register(BuiltInRegistries.BLOCK, ResourceKey.create(Registries.BLOCK, id(name)), block);
	}

	private static Item item(final String name, final Block block) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
	}
}
