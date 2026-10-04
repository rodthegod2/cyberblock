package dev.cyberblock.device.block;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.replica.ReplicaBlocks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
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
 * eddies of section 1.16. Ids ({@code cyberblock:<name>}) and block-state properties match 4A's blockstates (art/gen_block_art.py)
 * and are what 4M's placement sets:
 * <ul>
 * <li>{@code device_door}, {@code device_iron_door}: vanilla door properties ({@code facing, half, hinge, open, powered}) +
 * {@code quest_locked} (the art is the player's own vanilla door models)</li>
 * <li>{@code lamp_<dye>} (16): {@code lit}, {@code quest_locked}; {@code lamp_red} is also a locked door's lamp</li>
 * <li>{@code eye_camera}: {@code facing}, {@code mode} (off, on, alarm, friendly), {@code quest_locked}</li>
 * <li>{@code net_port}: {@code facing}, {@code breached}, {@code quest_locked}</li>
 * <li>{@code vending_machine}: {@code facing}, {@code broken}, {@code quest_locked}</li>
 * <li>{@code traffic_lamp}: {@code signal} (off, red, amber, green), {@code quest_locked}</li>
 * <li>{@code lift_platform}: {@code moving}, {@code quest_locked}; {@code lift_call_button}: {@code facing}, {@code lit},
 * {@code quest_locked}</li>
 * <li>{@code turret}: {@code facing}, {@code mode} (off, active, friendly), {@code quest_locked} (the client draws the turning head)</li>
 * <li>{@code sliding_gate}: {@code facing}, {@code step} (0 closed .. 4 open), {@code quest_locked}</li>
 * <li>{@code screen_frame}: {@code facing}, {@code on}, {@code quest_locked} (a glow item frame with the picture hangs in front)</li>
 * <li>{@code crate}: {@code readable}, {@code quest_locked}</li>
 * </ul>
 * Like the replica blocks they are unbreakable in survival and spared by explosions, drop nothing, have no item and never host mob
 * spawns: Night City owns them, the server places and removes them.
 */
public final class DeviceBlocks {
	public static Block DOOR;
	public static Block IRON_DOOR;
	public static Block EYE_CAMERA;
	public static Block NET_PORT;
	public static Block VENDING_MACHINE;
	public static Block TRAFFIC_LAMP;
	public static Block LIFT_PLATFORM;
	public static Block LIFT_CALL_BUTTON;
	public static Block TURRET;
	public static Block SLIDING_GATE;
	public static Block SCREEN_FRAME;
	public static Block CRATE;
	public static BlockEntityType<DeviceBlockEntity> EYE_CAMERA_ENTITY;
	public static BlockEntityType<DeviceBlockEntity> TURRET_ENTITY;
	/** Section 1.16: one Minecraft eddie (stacks to 64). */
	public static Item EDDIES;
	/** Section 1.16: a credchip worth {@link #EDDIE_CHIP_VALUE} eddies. */
	public static Item EDDIE_CHIP;
	public static final int EDDIE_CHIP_VALUE = 100;
	/** The block ids ({@code cyberblock:<name>}), registration order: the doors, the 16 lamps in dye order, then the rest. */
	public static final List<String> NAMES = Stream.of(Stream.of("device_door", "device_iron_door"),
		Stream.of(DyeColor.values()).map(dye -> "lamp_" + dye.getSerializedName()),
		Stream.of("eye_camera", "net_port", "vending_machine", "traffic_lamp", "lift_platform", "lift_call_button", "turret", "sliding_gate", "screen_frame",
			"crate")).flatMap(s -> s).toList();
	private static final List<Block> ALL = new ArrayList<>();
	private static final Map<String, Block> BY_NAME = new HashMap<>();
	private static final Set<Block> IS = Collections.newSetFromMap(new IdentityHashMap<>());

	private DeviceBlocks() {
	}

	/** Common init, once: the blocks, their two block entity types, the eddies, the devact route, motions and the dev command. */
	public static void register() {
		for (String name : NAMES) {
			Block block = Registry.register(BuiltInRegistries.BLOCK, ResourceKey.create(Registries.BLOCK, id(name)), create(name));
			ALL.add(block);
			BY_NAME.put(name, block);
			IS.add(block);
		}

		DOOR = BY_NAME.get("device_door");
		IRON_DOOR = BY_NAME.get("device_iron_door");
		EYE_CAMERA = BY_NAME.get("eye_camera");
		NET_PORT = BY_NAME.get("net_port");
		VENDING_MACHINE = BY_NAME.get("vending_machine");
		TRAFFIC_LAMP = BY_NAME.get("traffic_lamp");
		LIFT_PLATFORM = BY_NAME.get("lift_platform");
		LIFT_CALL_BUTTON = BY_NAME.get("lift_call_button");
		TURRET = BY_NAME.get("turret");
		SLIDING_GATE = BY_NAME.get("sliding_gate");
		SCREEN_FRAME = BY_NAME.get("screen_frame");
		CRATE = BY_NAME.get("crate");
		EYE_CAMERA_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("eye_camera"),
			FabricBlockEntityTypeBuilder.create((pos, state) -> new DeviceBlockEntity(EYE_CAMERA_ENTITY, pos, state), EYE_CAMERA).build());
		TURRET_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("turret"),
			FabricBlockEntityTypeBuilder.create((pos, state) -> new DeviceBlockEntity(TURRET_ENTITY, pos, state), TURRET).build());
		EDDIES = item("eddies", new Item.Properties());
		EDDIE_CHIP = item("eddie_chip", new Item.Properties().rarity(Rarity.UNCOMMON));
		DeviceHooks.init();
		DeviceMotions.init();
		ScreenFrames.init();
		DeviceTestCommand.init();
		// Phase 0 c (not yet confirmed in game): lift calls are unproven and vending is a Minecraft shop; say which path is active
		Cyberblock.LOG.info("device: {} device blocks and the eddies registered; lift call: {}; vending buy: Minecraft shop (eddies, never reaches Night City)",
			ALL.size(), LiftCallButtonBlock.CALL_SUPPORTED ? "supported (call reaches Night City)" : "greyed (unsupported until a check proves it)");
	}

	/** Every device block, registration order (client: hidden for active hosts). */
	public static List<Block> all() {
		return Collections.unmodifiableList(ALL);
	}

	/** The lamp of a dye colour ({@code cyberblock:lamp_<dye>}). */
	public static Block lamp(final DyeColor colour) {
		return BY_NAME.get("lamp_" + colour.getSerializedName());
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
		if (name.startsWith("lamp_")) {
			for (DyeColor dye : DyeColor.values()) {
				if (name.equals("lamp_" + dye.getSerializedName())) {
					return new LampBlock(dye, props(name, MapColor.WOOL, SoundType.GLASS).mapColor(dye).lightLevel(LampBlock::light));
				}
			}
		}

		return switch (name) {
			case "device_door" -> new DeviceDoorBlock(DeviceDoorBlock.NIGHT_CITY_WOOD, props(name, MapColor.WOOD, SoundType.WOOD).noOcclusion());
			case "device_iron_door" -> new DeviceDoorBlock(BlockSetType.IRON, props(name, MapColor.METAL, SoundType.METAL).noOcclusion());
			case "eye_camera" -> new EyeCameraBlock(props(name, MapColor.COLOR_GRAY, SoundType.METAL));
			case "net_port" -> new NetPortBlock(props(name, MapColor.COLOR_CYAN, SoundType.METAL).lightLevel(state -> 4));
			case "vending_machine" -> new VendingMachineBlock(props(name, MapColor.COLOR_RED, SoundType.METAL).lightLevel(VendingMachineBlock::light));
			case "traffic_lamp" -> new TrafficLampBlock(props(name, MapColor.COLOR_BLACK, SoundType.METAL).noOcclusion().lightLevel(TrafficLampBlock::light));
			case "lift_platform" -> new LiftPlatformBlock(props(name, MapColor.METAL, SoundType.METAL).noOcclusion());
			case "lift_call_button" -> new LiftCallButtonBlock(props(name, MapColor.METAL, SoundType.METAL).noCollision().noOcclusion()
				.lightLevel(LiftCallButtonBlock::light));
			case "turret" -> new TurretBlock(props(name, MapColor.COLOR_GRAY, SoundType.METAL));
			case "sliding_gate" -> new SlidingGateBlock(props(name, MapColor.METAL, SoundType.METAL).noOcclusion());
			case "screen_frame" -> new ScreenFrameBlock(props(name, MapColor.COLOR_BLACK, SoundType.GLASS).lightLevel(ScreenFrameBlock::light));
			case "crate" -> new CrateBlock(props(name, MapColor.WOOD, SoundType.WOOD));
			default -> throw new IllegalArgumentException("no device block " + name);
		};
	}

	private static Item item(final String name, final Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(properties.setId(key)));
	}
}
