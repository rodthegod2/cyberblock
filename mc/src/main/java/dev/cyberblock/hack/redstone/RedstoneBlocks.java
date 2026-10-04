package dev.cyberblock.hack.redstone;

import dev.cyberblock.Cyberblock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * The redstone hacking blocks and the Cyberdeck Link (megaplan 6-R1), registered on both sides at startup ({@link #register}, from
 * Cyberblock.onInitialize). Ids ({@code cyberblock:<name>}; art, models, item definitions and lang are 6A's):
 * <ul>
 * <li>{@code device_relay}, {@code breach_port}, {@code charge}: block state {@code triggered} (true while powered)</li>
 * <li>{@code proximity_sensor}, {@code heat_sensor}: block state {@code power} 0..15 (the output)</li>
 * <li>{@code cyberdeck_block}: no block state (its RAM is in the block entity; a comparator reads it)</li>
 * <li>{@code cyberdeck_link}: the item (one per stack)</li>
 * </ul>
 * One block entity type, {@code cyberblock:redstone_hack}, serves all six blocks. Every block has an item (a player's block, unlike the
 * device blocks) and drops itself.
 */
public final class RedstoneBlocks {
	public static final List<String> NAMES = List.of("device_relay", "proximity_sensor", "heat_sensor", "breach_port", "charge", "cyberdeck_block");
	public static final String LINK_ITEM = "cyberdeck_link";
	public static final String ENTITY_ID = "redstone_hack";
	public static Block DEVICE_RELAY;
	public static Block PROXIMITY_SENSOR;
	public static Block HEAT_SENSOR;
	public static Block BREACH_PORT;
	public static Block CHARGE;
	public static Block CYBERDECK_BLOCK;
	public static Item CYBERDECK_LINK;
	public static BlockEntityType<HackBlockEntity> ENTITY;
	private static final Map<String, Block> BY_NAME = new HashMap<>();

	private RedstoneBlocks() {
	}

	/** Common init, once: blocks, their items, the block entity type, the link item, the creative tab entries and the server module. */
	public static void register() {
		for (String name : NAMES) {
			Block block = Registry.register(BuiltInRegistries.BLOCK, ResourceKey.create(Registries.BLOCK, id(name)), create(name));
			BY_NAME.put(name, block);
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
			Registry.register(BuiltInRegistries.ITEM, key, new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(key)));
		}

		DEVICE_RELAY = BY_NAME.get("device_relay");
		PROXIMITY_SENSOR = BY_NAME.get("proximity_sensor");
		HEAT_SENSOR = BY_NAME.get("heat_sensor");
		BREACH_PORT = BY_NAME.get("breach_port");
		CHARGE = BY_NAME.get("charge");
		CYBERDECK_BLOCK = BY_NAME.get("cyberdeck_block");
		ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id(ENTITY_ID), FabricBlockEntityTypeBuilder.create(
			(pos, state) -> new HackBlockEntity(ENTITY, pos, state), DEVICE_RELAY, PROXIMITY_SENSOR, HEAT_SENSOR, BREACH_PORT, CHARGE, CYBERDECK_BLOCK).build());
		ResourceKey<Item> link = ResourceKey.create(Registries.ITEM, id(LINK_ITEM));
		CYBERDECK_LINK = Registry.register(BuiltInRegistries.ITEM, link, new CyberdeckLinkItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON).setId(link)));
		// after the cyber items in the Cyberblock tab
		CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("cyberblock"))).register(out -> {
			out.accept(CYBERDECK_LINK);
			NAMES.forEach(name -> out.accept(BY_NAME.get(name)));
		});
		RedstoneHacks.init();
		Cyberblock.LOG.info("redstone: {} redstone hacking blocks and the Cyberdeck Link registered", NAMES.size());
	}

	static Identifier id(final String name) {
		return Identifier.fromNamespaceAndPath(Cyberblock.ID, name);
	}

	private static BlockBehaviour.Properties props(final String name, final MapColor colour) {
		return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id(name))).mapColor(colour).sound(SoundType.METAL)
			.strength(1.5F, 6.0F).noLootTable();
	}

	/** A new block, not registered yet (tests build them this way too). */
	static Block create(final String name) {
		return switch (name) {
			case "device_relay" -> new TriggerBlock(TriggerBlock.Kind.RELAY, props(name, MapColor.COLOR_CYAN));
			case "breach_port" -> new TriggerBlock(TriggerBlock.Kind.BREACH_PORT, props(name, MapColor.COLOR_MAGENTA).lightLevel(state -> 3));
			case "charge" -> new TriggerBlock(TriggerBlock.Kind.CHARGE, props(name, MapColor.COLOR_RED).strength(0.5F, 0.0F).sound(SoundType.GRASS));
			case "proximity_sensor" -> new SensorBlock(SensorBlock.Kind.PROXIMITY, props(name, MapColor.COLOR_YELLOW));
			case "heat_sensor" -> new SensorBlock(SensorBlock.Kind.HEAT, props(name, MapColor.COLOR_ORANGE));
			case "cyberdeck_block" -> new CyberdeckBlock(props(name, MapColor.COLOR_BLUE).lightLevel(state -> 5));
			default -> throw new IllegalArgumentException("no redstone hacking block " + name);
		};
	}
}
