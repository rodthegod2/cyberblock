package dev.cyberblock.device.block;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.replica.ReplicaBlocks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.material.MapColor;

/**
 * The device blocks of section 1.11 (megaplan task 4-B1), registered on both sides at startup ({@link #register}), and the Minecraft
 * eddies of section 1.16. Ids ({@code cyberblock:<name>}) and block-state properties are the contract with 4A's art (blockstates,
 * models, textures, lang) and 4M's placement:
 * <ul>
 * <li>{@code device_door}, {@code device_iron_door}: vanilla door properties ({@code facing, half, hinge, open, powered}) +
 * {@code quest_locked}; {@code door_lock_lamp}: {@code quest_locked}</li>
 * <li>{@code lamp}: {@code color} (16 dyes), {@code lit}, {@code quest_locked}</li>
 * <li>{@code eye_camera}: {@code facing}, {@code mode} (off, on, alarm, friendly), {@code quest_locked}</li>
 * <li>{@code net_port}: {@code facing}, {@code breached}, {@code quest_locked}</li>
 * <li>{@code vending_machine}: {@code facing}, {@code broken}, {@code quest_locked}</li>
 * <li>{@code traffic_lamp}: {@code facing}, {@code light} (off, red, yellow, green), {@code quest_locked}</li>
 * <li>{@code lift_platform}: {@code quest_locked}; {@code lift_call_button}: {@code facing}, {@code unsupported}, {@code quest_locked}</li>
 * <li>{@code turret}: {@code facing}, {@code mode} (off, active, friendly), {@code quest_locked} (the head is drawn by the client)</li>
 * <li>{@code sliding_gate}: {@code axis} (x, z), {@code quest_locked}</li>
 * <li>{@code crate}: {@code readable}, {@code quest_locked}</li>
 * </ul>
 * Like the replica blocks they are unbreakable in survival and spared by explosions, drop nothing, have no item and never host mob
 * spawns: Night City owns them, the server places and removes them.
 */
public final class DeviceBlocks {
	public static Block DOOR;
	public static Block IRON_DOOR;
	public static Block DOOR_LOCK_LAMP;
	public static Block LAMP;
	public static Block EYE_CAMERA;
	public static Block NET_PORT;
	public static Block VENDING_MACHINE;
	public static Block TRAFFIC_LAMP;
	public static Block LIFT_PLATFORM;
	public static Block LIFT_CALL_BUTTON;
	public static Block TURRET;
	public static Block SLIDING_GATE;
	public static Block CRATE;
	public static BlockEntityType<DeviceBlockEntity> EYE_CAMERA_ENTITY;
	public static BlockEntityType<DeviceBlockEntity> TURRET_ENTITY;
	/** Section 1.16: one Minecraft eddie (stacks to 64). */
	public static Item EDDIES;
	/** Section 1.16: a credchip worth 100 eddies. */
	public static Item EDDIE_CHIP;
	public static final int EDDIE_CHIP_VALUE = 100;
	/** The block ids ({@code cyberblock:<name>}), registration order. */
	public static final List<String> NAMES = List.of("device_door", "device_iron_door", "door_lock_lamp", "lamp", "eye_camera", "net_port",
		"vending_machine", "traffic_lamp", "lift_platform", "lift_call_button", "turret", "sliding_gate", "crate");
	private static final List<Block> ALL = new ArrayList<>();
	private static final Set<Block> IS = Collections.newSetFromMap(new IdentityHashMap<>());

	private DeviceBlocks() {
	}

	/** Common init, once: the blocks, their two block entity types, the eddies, the devact route, motions and the dev command. */
	public static void register() {
		for (String name : NAMES) {
			Block block = Registry.register(BuiltInRegistries.BLOCK, ResourceKey.create(Registries.BLOCK, id(name)), create(name));
			ALL.add(block);
			IS.add(block);
		}

		DOOR = ALL.get(0);
		IRON_DOOR = ALL.get(1);
		DOOR_LOCK_LAMP = ALL.get(2);
		LAMP = ALL.get(3);
		EYE_CAMERA = ALL.get(4);
		NET_PORT = ALL.get(5);
		VENDING_MACHINE = ALL.get(6);
		TRAFFIC_LAMP = ALL.get(7);
		LIFT_PLATFORM = ALL.get(8);
		LIFT_CALL_BUTTON = ALL.get(9);
		TURRET = ALL.get(10);
		SLIDING_GATE = ALL.get(11);
		CRATE = ALL.get(12);
		EYE_CAMERA_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("eye_camera"),
			FabricBlockEntityTypeBuilder.create((pos, state) -> new DeviceBlockEntity(EYE_CAMERA_ENTITY, pos, state), EYE_CAMERA).build());
		TURRET_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("turret"),
			FabricBlockEntityTypeBuilder.create((pos, state) -> new DeviceBlockEntity(TURRET_ENTITY, pos, state), TURRET).build());
		EDDIES = item("eddies", new Item.Properties());
		EDDIE_CHIP = item("eddie_chip", new Item.Properties().rarity(Rarity.UNCOMMON));
		DeviceHooks.init();
		DeviceMotions.init();
		DeviceTestCommand.init();
		// Phase 0 c (not yet confirmed in game): lift calls are unproven and vending is a Minecraft shop; say which path is active
		Cyberblock.LOG.info("device: {} device blocks and the eddies registered; lift call: {}; vending buy: Minecraft shop (eddies, never reaches Night City)",
			ALL.size(), LiftCallButtonBlock.CALL_SUPPORTED ? "supported (call reaches Night City)" : "greyed (unsupported until a check proves it)");
	}

	/** Every device block, registration order (client: hidden for active hosts). */
	public static List<Block> all() {
		return Collections.unmodifiableList(ALL);
	}

	/** Is this one of the device blocks? */
	public static boolean is(final BlockState state) {
		return IS.contains(state.getBlock());
	}

	/** May a device take this cell? Air and replica cells only: never a player's block, never another device. */
	public static boolean free(final BlockState state) {
		return state.isAir() || ReplicaBlocks.is(state);
	}

	private static Identifier id(final String name) {
		return Identifier.fromNamespaceAndPath(Cyberblock.ID, name);
	}

	private static BlockBehaviour.Properties props(final String name, final MapColor colour, final SoundType sound) {
		return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id(name))).mapColor(colour).sound(sound)
			.strength(-1.0F, 3600000.8F).noLootTable().isValidSpawn(Blocks::never);
	}

	/** A new device block, not registered yet (tests build them this way too). */
	static Block create(final String name) {
		return switch (name) {
			case "device_door" -> new DeviceDoorBlock(BlockSetType.OAK, props(name, MapColor.WOOD, SoundType.WOOD).noOcclusion());
			case "device_iron_door" -> new DeviceDoorBlock(BlockSetType.IRON, props(name, MapColor.METAL, SoundType.METAL).noOcclusion());
			case "door_lock_lamp" -> new DoorLockLampBlock(props(name, MapColor.COLOR_RED, SoundType.GLASS).lightLevel(DoorLockLampBlock::light));
			case "lamp" -> new LampBlock(props(name, MapColor.WOOL, SoundType.GLASS).lightLevel(LampBlock::light));
			case "eye_camera" -> new EyeCameraBlock(props(name, MapColor.COLOR_GRAY, SoundType.METAL));
			case "net_port" -> new NetPortBlock(props(name, MapColor.COLOR_CYAN, SoundType.METAL).lightLevel(state -> 4));
			case "vending_machine" -> new VendingMachineBlock(props(name, MapColor.COLOR_RED, SoundType.METAL).lightLevel(VendingMachineBlock::light));
			case "traffic_lamp" -> new TrafficLampBlock(props(name, MapColor.COLOR_BLACK, SoundType.METAL).lightLevel(TrafficLampBlock::light));
			case "lift_platform" -> new LiftPlatformBlock(props(name, MapColor.METAL, SoundType.METAL));
			case "lift_call_button" -> new LiftCallButtonBlock(props(name, MapColor.METAL, SoundType.METAL).noCollision().noOcclusion());
			case "turret" -> new TurretBlock(props(name, MapColor.COLOR_GRAY, SoundType.METAL).noOcclusion());
			case "sliding_gate" -> new SlidingGateBlock(props(name, MapColor.METAL, SoundType.METAL).noOcclusion());
			case "crate" -> new CrateBlock(props(name, MapColor.WOOD, SoundType.WOOD));
			default -> throw new IllegalArgumentException("no device block " + name);
		};
	}

	private static Item item(final String name, final Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(properties.setId(key)));
	}
}
