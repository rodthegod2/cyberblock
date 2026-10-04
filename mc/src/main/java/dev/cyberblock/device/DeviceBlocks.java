package dev.cyberblock.device;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * The blocks devices become (megaplan section 1.11) and how a device's state shows on them. The ids come from device_map (4A's data);
 * the blocks are 4B's (device/block/**). This side sets their state by property name, so any block works and a property a block lacks
 * is skipped. Both property sets in flight are covered: 4A's blockstates (lamp_<dye> lit; sliding_gate step; traffic_lamp signal
 * off|red|amber|green; screen_frame on) and 4B's blocks (lamp color + lit; sliding_gate axis; traffic_lamp light off|red|yellow|green;
 * lift_call_button unsupported); camera and turret mode, facing, broken and quest_locked are the same in both.
 *
 * <p>Runtime feature check: 4B's device doors replace device_map's vanilla doors when they are registered; an id that is not registered
 * first tries the other naming ({@code <ns>:lamp_<dye>} is {@code <ns>:lamp} with that color), then falls back to the type's vanilla
 * stand-in, logged once per id ({@code devices: block <id> is not registered: <type> uses <stand-in>}).
 */
public final class DeviceBlocks {
	public static final String LAMP = "cyberblock:lamp_white", LOCK_LAMP = "cyberblock:lamp_red", CAMERA = "cyberblock:eye_camera",
		NET_PORT = "cyberblock:net_port", VENDING = "cyberblock:vending_machine", TRAFFIC = "cyberblock:traffic_lamp", LIFT = "cyberblock:lift_platform",
		TURRET = "cyberblock:turret", GATE = "cyberblock:sliding_gate", SCREEN = "cyberblock:screen_frame", CRATE = "cyberblock:crate",
		LIFT_BUTTON = "cyberblock:lift_call_button";
	/** The sliding gate's last step (4A's blockstates): fully open. */
	static final String GATE_OPEN_STEP = "4";
	private static final Pattern DYED_LAMP = Pattern.compile("([a-z0-9_.-]+):lamp_([a-z_]+)");
	private static final Map<String, String> PREFERRED = Map.of("minecraft:oak_door", "cyberblock:device_door", "minecraft:iron_door",
		"cyberblock:device_iron_door");
	/** Plain vanilla blocks with no redstone output and no screen of their own, one per type, for a missing block. */
	private static final Map<DeviceType, Block> STAND_INS = new EnumMap<>(DeviceType.class);
	private static final Set<String> warned = ConcurrentHashMap.newKeySet();

	static {
		STAND_INS.put(DeviceType.DOOR, Blocks.OAK_DOOR);
		STAND_INS.put(DeviceType.GATE, Blocks.IRON_BARS);
		STAND_INS.put(DeviceType.LIGHT, Blocks.REDSTONE_LAMP);
		STAND_INS.put(DeviceType.CAMERA, Blocks.TINTED_GLASS);
		STAND_INS.put(DeviceType.TERMINAL, Blocks.CRYING_OBSIDIAN);
		STAND_INS.put(DeviceType.VENDING, Blocks.TERRACOTTA);
		STAND_INS.put(DeviceType.TRAFFIC, Blocks.REDSTONE_LAMP);
		STAND_INS.put(DeviceType.ELEVATOR, Blocks.IRON_BLOCK);
		STAND_INS.put(DeviceType.TURRET, Blocks.DISPENSER);
		STAND_INS.put(DeviceType.SCREEN, Blocks.COAL_BLOCK);
		STAND_INS.put(DeviceType.CONTAINER, Blocks.SPRUCE_PLANKS);
	}

	private DeviceBlocks() {
	}

	/** The block of an id (or its other naming), or the type's vanilla stand-in (logged once per id). */
	public static BlockState resolve(final String id, final DeviceType type) {
		BlockState s = find(id);
		if (s != null) {
			return s;
		}

		Block standIn = STAND_INS.get(type);
		if (warned.add(id)) {
			Cyberblock.LOG.info("devices: block {} is not registered: {} uses {} (vanilla stand-in)", id, type.id(), BuiltInRegistries.BLOCK.getKey(standIn));
		}

		return standIn.defaultBlockState();
	}

	/** An attachment's block (or its other naming), or air (left out) when it is missing (logged once per id). */
	public static BlockState resolveAttachment(final String id) {
		BlockState s = find(id);
		if (s == null && warned.add(id)) {
			Cyberblock.LOG.info("devices: block {} is not registered: that attachment is left out", id);
		}

		return s == null ? Blocks.AIR.defaultBlockState() : s;
	}

	private static @Nullable BlockState find(final String id) {
		// 4B's device doors stand for the vanilla doors of device_map when they exist: hidden for active hosts (no Minecraft door drawn
		// over Night City's), unbreakable, spared by explosions, no drops
		String preferred = PREFERRED.get(id);
		BlockState s = preferred == null ? null : registered(preferred);
		s = s != null ? s : registered(id);
		Matcher lamp = DYED_LAMP.matcher(id);
		if (s == null && lamp.matches()) {
			BlockState one = registered(lamp.group(1) + ":lamp");
			s = one == null ? null : with(one, "color", lamp.group(2));
		}

		return s;
	}

	private static @Nullable BlockState registered(final String id) {
		Identifier key = Identifier.tryParse(id);
		Block block = key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
		return block == null || block == Blocks.AIR ? null : block.defaultBlockState();
	}

	/** Whether 4B's device blocks are in this game (one line at server start says which path is active). */
	public static boolean customBlocksPresent() {
		return registered(CAMERA) != null;
	}

	/** How the device looks: facing from its yaw, and the state properties (4A's or 4B's names, or vanilla's) its block has. */
	public static BlockState look(final BlockState base, final Device d) {
		Direction facing = Direction.fromYRot(d.yaw());
		BlockState s = with(with(base, "facing", facing.getSerializedName()), "axis", facing.getClockWise().getAxis().getSerializedName());
		boolean on = d.on();
		s = switch (d.type()) {
			case DOOR -> with(s, "open", Boolean.toString(d.open()));
			case GATE -> with(with(s, "open", Boolean.toString(d.open())), "step", d.open() ? GATE_OPEN_STEP : "0");
			case LIGHT -> with(s, "lit", Boolean.toString(on));
			// the contract carries no traffic colour (protocol.md, Phase 4 semantics): on shows amber (yellow), off shows dark
			case TRAFFIC -> with(with(with(s, "signal", on ? "amber" : "off"), "light", on ? "yellow" : "off"), "lit", Boolean.toString(on));
			case CAMERA -> with(s, "mode", on ? "on" : "off");
			case TURRET -> with(s, "mode", on ? "active" : "off");
			case SCREEN -> with(s, "on", Boolean.toString(on));
			default -> s;
		};
		return with(with(s, "quest_locked", Boolean.toString(d.questLocked())), "broken", Boolean.toString(d.broken()));
	}

	/** An attachment: facing and quest_locked as its device; a lamp that shows only while locked is lit; a lift call button greyed. */
	static BlockState attachment(final BlockState base, final Device d, final boolean lockedOnly) {
		BlockState s = with(with(base, "facing", Direction.fromYRot(d.yaw()).getSerializedName()), "quest_locked", Boolean.toString(d.questLocked()));
		// a lift call is unproven (Phase 0 c): the call button shows greyed
		return with(lockedOnly ? with(s, "lit", "true") : s, "unsupported", "true");
	}

	/** A gate whose block shows open by itself (4A's sliding gate steps): it stays standing while open. */
	static boolean showsOpen(final Block block) {
		return block.getStateDefinition().getProperty("step") != null || block.getStateDefinition().getProperty("open") != null;
	}

	/** The state with the named property set to the value, or the state unchanged when the block has no such property or value. */
	public static BlockState with(final BlockState s, final String name, final String value) {
		Property<?> p = s.getBlock().getStateDefinition().getProperty(name);
		return p == null ? s : set(s, p, value);
	}

	private static <T extends Comparable<T>> BlockState set(final BlockState s, final Property<T> p, final String value) {
		return p.getValue(value).map(v -> s.setValue(p, v)).orElse(s);
	}
}
