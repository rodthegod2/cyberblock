package dev.cyberblock.device.block;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.DevCommands;
import dev.cyberblock.net.ServerNet;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jspecify.annotations.Nullable;

/**
 * Dev-only showcase of every device block (megaplan 4-B1 screenshots), registered only with {@code -Dcyberblock.devtools=true}
 * and only for ops, like {@link DevCommands}:
 * <ul>
 * <li>{@code /cyberblock test devices <x> <y> <z>}: builds the showcase row eastward from there, every front facing south</li>
 * <li>{@code /cyberblock test devices <x> <y> <z> cycle}: steps every device to its next state (doors, gate and lift move)</li>
 * <li>{@code /cyberblock test devices <x> <y> <z> clear}: takes it all away</li>
 * <li>{@code /cyberblock test devices use <x> <y> <z> <act>}: a device block use as the source player ({@code execute as}), the way
 * a right-click asks</li>
 * <li>{@code /cyberblock test devices echo on|off}: a stand-in for the device layer (4M) that answers block uses itself (doors open,
 * lamps switch, the vending machine opens a demo shop, a readable crate shows demo loot); never installed by itself</li>
 * </ul>
 */
final class DeviceTestCommand {
	/** The showcase's parts, as offsets east of the command's cell. */
	private static final int CAMERAS = 3;
	private static final int TRAFFIC = 7;
	private static final int TURRETS = 12;
	private static final int DOORS = 16;
	private static final int GATE = 25;
	private static final int LIFT = 31;
	private static final int SCREENS = 35;
	private static final int CRATES = 45;
	private static final Direction FRONT = Direction.SOUTH;
	private static final List<ShopWire.Line> DEMO_SHOP = List.of(
		new ShopWire.Line("honey", "minecraft:honey_bottle", 1, 15, -1),
		new ShopWire.Line("cookies", "minecraft:cookie", 4, 5, 12),
		new ShopWire.Line("apple", "minecraft:golden_apple", 1, 120, 2),
		new ShopWire.Line("bread", "minecraft:bread", 2, 8, 0));
	/** The stand-in handler, and the one it replaced (restored by echo off). */
	private static final DeviceHooks.Handler ECHO = DeviceTestCommand::answer;
	private static DeviceHooks.@Nullable Handler before;
	/** The last showcase built (its gate's full size: an open gate keeps only its floor rail, so it cannot be measured then). */
	private static @Nullable BlockPos built;

	private DeviceTestCommand() {
	}

	static void init() {
		if (!DevCommands.enabled()) {
			return;
		}

		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(Commands.literal("cyberblock")
			.then(Commands.literal("test").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(devices()))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> devices() {
		return Commands.literal("devices")
			.then(Commands.literal("echo").then(Commands.literal("on").executes(c -> echo(c.getSource(), true)))
				.then(Commands.literal("off").executes(c -> echo(c.getSource(), false))))
			.then(Commands.literal("use").then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer())
				.then(Commands.argument("z", IntegerArgumentType.integer()).then(Commands.argument("act", StringArgumentType.word())
					.executes(c -> use(c.getSource(), pos(c), StringArgumentType.getString(c, "act"))))))))
			.then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer(-64, 300))
				.then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> build(c.getSource(), pos(c)))
					.then(Commands.literal("cycle").executes(c -> cycle(c.getSource(), pos(c))))
					.then(Commands.literal("clear").executes(c -> clear(c.getSource(), pos(c)))))));
	}

	private static BlockPos pos(final CommandContext<CommandSourceStack> c) {
		return new BlockPos(IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z"));
	}

	private static int build(final CommandSourceStack source, final BlockPos o) {
		ServerLevel level = source.getLevel();
		clear(source, o);
		built = o.immutable();
		DyeColor[] colours = {DyeColor.CYAN, DyeColor.MAGENTA, DyeColor.YELLOW, DyeColor.RED};
		for (int i = 0; i < colours.length; i++) {
			set(level, o.offset(i / 2, i % 2, 0), LampBlock.state(colours[i], true));
		}

		set(level, o.offset(0, 2, 0), LampBlock.state(DyeColor.WHITE, false));
		set(level, o.offset(1, 2, 0), LampBlock.state(DyeColor.LIME, true).setValue(DeviceBlock.QUEST_LOCKED, true));
		EyeCameraBlock.Mode[] modes = {EyeCameraBlock.Mode.ON, EyeCameraBlock.Mode.ALARM, EyeCameraBlock.Mode.FRIENDLY};
		for (int i = 0; i < modes.length; i++) {
			set(level, o.offset(CAMERAS + i, 3, 0), facing(DeviceBlocks.EYE_CAMERA).setValue(EyeCameraBlock.MODE, modes[i]));
		}

		set(level, o.offset(CAMERAS, 0, 0), facing(DeviceBlocks.NET_PORT));
		set(level, o.offset(CAMERAS + 1, 0, 0), facing(DeviceBlocks.NET_PORT).setValue(NetPortBlock.BREACHED, true));
		set(level, o.offset(CAMERAS + 2, 0, 0), facing(DeviceBlocks.VENDING_MACHINE));
		set(level, o.offset(CAMERAS + 2, 1, 0), facing(DeviceBlocks.VENDING_MACHINE).setValue(VendingMachineBlock.BROKEN, true));
		TrafficLampBlock.Signal[] signals = TrafficLampBlock.Signal.values();
		for (int i = 0; i < signals.length; i++) {
			set(level, o.offset(TRAFFIC + i, 1, 0), DeviceBlocks.TRAFFIC_LAMP.defaultBlockState().setValue(TrafficLampBlock.SIGNAL, signals[i]));
		}

		TurretBlock.Mode[] turrets = TurretBlock.Mode.values();
		for (int i = 0; i < turrets.length; i++) {
			set(level, o.offset(TURRETS + i, 0, 0), facing(DeviceBlocks.TURRET).setValue(TurretBlock.MODE, turrets[i]));
		}

		Doors.place(level, o.offset(DOORS, 0, 0), FRONT, DoorHingeSide.LEFT, false, false);
		Doors.place(level, o.offset(DOORS + 2, 0, 0), FRONT, DoorHingeSide.LEFT, true, false);
		Doors.setLocked(level, o.offset(DOORS + 2, 0, 0), true, false);
		Doors.place(level, o.offset(DOORS + 4, 0, 0), FRONT, DoorHingeSide.LEFT, false, false);
		Doors.setLocked(level, o.offset(DOORS + 4, 0, 0), false, true);
		Doors.place(level, o.offset(DOORS + 6, 0, 0), FRONT, DoorHingeSide.LEFT, false, true);
		Doors.place(level, o.offset(DOORS + 7, 0, 0), FRONT, DoorHingeSide.RIGHT, false, true);
		Gates.place(level, gate(o), false);
		Lifts.place(level, shaft(o), o.getY());
		set(level, o.offset(LIFT + 2, 1, 0), LiftCallButtonBlock.state(FRONT));
		set(level, o.offset(LIFT + 2, 2, 0), LiftCallButtonBlock.state(FRONT).setValue(LiftCallButtonBlock.LIT, true));
		ScreenFrames.place(level, screen(o, 0), true);
		ScreenFrames.place(level, screen(o, 1), true);
		ScreenFrames.place(level, screen(o, 2), false);
		set(level, o.offset(CRATES, 0, 0), DeviceBlocks.CRATE.defaultBlockState().setValue(CrateBlock.READABLE, true));
		set(level, o.offset(CRATES + 1, 0, 0), DeviceBlocks.CRATE.defaultBlockState());
		return say(source, "test: devices built at " + o.toShortString());
	}

	private static int cycle(final CommandSourceStack source, final BlockPos o) {
		ServerLevel level = source.getLevel();
		for (int x = 0; x <= CRATES + 1; x++) {
			for (int y = 0; y <= 6; y++) {
				BlockPos p = o.offset(x, y, 0);
				BlockState s = level.getBlockState(p);
				if (s.getBlock() instanceof LampBlock) {
					set(level, p, s.cycle(LampBlock.LIT));
				} else if (s.is(DeviceBlocks.EYE_CAMERA)) {
					set(level, p, s.cycle(EyeCameraBlock.MODE));
				} else if (s.is(DeviceBlocks.TRAFFIC_LAMP)) {
					set(level, p, s.cycle(TrafficLampBlock.SIGNAL));
				} else if (s.is(DeviceBlocks.TURRET)) {
					set(level, p, s.cycle(TurretBlock.MODE));
				} else if (s.getBlock() instanceof DeviceDoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
					Doors.setOpen(level, p, !s.getValue(DoorBlock.OPEN));
				}
			}
		}

		Gates.Gate gate = gate(o);
		Gates.slide(level, gate, Gates.opened(level, gate) == 0);
		Lifts.Shaft shaft = shaft(o);
		Lifts.moveTo(level, shaft, Lifts.platformY(level, shaft).orElse(o.getY()) == o.getY() ? shaft.maxY() : o.getY());
		boolean even = level.getGameTime() % 2 == 0;
		ScreenFrames.set(level, screen(o, 0), even);
		ScreenFrames.set(level, screen(o, 2), !even);
		return say(source, "test: devices cycled at " + o.toShortString());
	}

	private static int clear(final CommandSourceStack source, final BlockPos o) {
		ServerLevel level = source.getLevel();
		Gates.remove(level, gate(o));
		Lifts.remove(level, shaft(o));
		for (int i = 0; i < 3; i++) {
			ScreenFrames.remove(level, screen(o, i));
		}

		for (int x = 0; x <= CRATES + 1; x++) {
			for (int y = 0; y <= 7; y++) {
				BlockPos p = o.offset(x, y, 0);
				if (DeviceBlocks.is(level.getBlockState(p))) {
					level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
				}
			}
		}

		return 1;
	}

	private static Gates.Gate gate(final BlockPos o) {
		// facing north: the gate spans east from its origin and slides back west into it
		return new Gates.Gate(o.offset(GATE, 0, 0), Direction.NORTH, 4, 3);
	}

	private static Lifts.Shaft shaft(final BlockPos o) {
		return new Lifts.Shaft(o.getX() + LIFT, o.getZ() - 1, 2, 2, o.getY(), o.getY() + 6);
	}

	/** Screen 0: 3x2 lit, 1: 2x2 lit, 2: 1x1 off; bottom-left cells as seen from the south. */
	private static ScreenFrames.Screen screen(final BlockPos o, final int i) {
		return switch (i) {
			case 0 -> new ScreenFrames.Screen(o.offset(SCREENS, 0, 0), FRONT, 3, 2);
			case 1 -> new ScreenFrames.Screen(o.offset(SCREENS + 4, 0, 0), FRONT, 2, 2);
			default -> new ScreenFrames.Screen(o.offset(SCREENS + 7, 0, 0), FRONT, 1, 1);
		};
	}

	private static BlockState facing(final Block block) {
		return block.defaultBlockState().setValue(FacingDeviceBlock.FACING, FRONT);
	}

	private static void set(final ServerLevel level, final BlockPos pos, final BlockState state) {
		level.setBlock(pos, state, Block.UPDATE_ALL);
	}

	private static int say(final CommandSourceStack source, final String line) {
		Cyberblock.LOG.info(line);
		source.sendSuccess(() -> Component.literal(line), false);
		return 1;
	}

	/** A device block use as the source player (execute as <player> run ...), through the same hook a right-click takes. */
	private static int use(final CommandSourceStack source, final BlockPos pos, final String act) {
		ServerPlayer player = source.getPlayer();
		BlockState s = source.getLevel().getBlockState(pos);
		if (player == null || !DeviceBlocks.is(s)) {
			source.sendFailure(Component.literal(player == null ? "test: use needs a player (execute as <player> run ...)" : "test: no device block at " + pos.toShortString()));
			return 0;
		}

		if (DeviceHooks.questLocked(s)) {
			DeviceHooks.tell(player, "cyberblock.device.quest_locked", "Night City is holding this device for a quest");
		} else {
			DeviceHooks.request(player, pos, act, new JsonObject());
		}

		return say(source, "test: device use " + act + " at " + pos.toShortString() + " by " + player.getPlainTextName());
	}

	private static int echo(final CommandSourceStack source, final boolean on) {
		if (on && DeviceHooks.handler() != ECHO) {
			before = DeviceHooks.handler();
			DeviceHooks.install(ECHO);
		} else if (!on && DeviceHooks.handler() == ECHO) {
			DeviceHooks.restore(before);
		}

		return say(source, "test: devices echo " + (on ? "on (block uses answered locally)" : "off"));
	}

	/** The stand-in device layer: applies the act at once, as if Night City said ok. */
	private static void answer(final ServerPlayer player, final BlockPos pos, final String act, final JsonObject extra) {
		ServerLevel level = player.level();
		BlockState s = level.getBlockState(pos);
		Cyberblock.LOG.info("test: device {} at {} by {} (echo)", act, pos.toShortString(), player.getPlainTextName());
		switch (act) {
			case "open", "close" -> {
				if (s.getBlock() instanceof DeviceDoorBlock) {
					Doors.setOpen(level, pos, act.equals("open"));
				} else if (s.is(DeviceBlocks.SLIDING_GATE)) {
					Gates.slide(level, gateAt(level, pos, s), act.equals("open"));
				}
			}
			case "on", "off" -> {
				if (s.getBlock() instanceof LampBlock) {
					set(level, pos, s.setValue(LampBlock.LIT, act.equals("on")));
				}
			}
			case "enable", "disable", "friendly" -> {
				if (s.is(DeviceBlocks.EYE_CAMERA)) {
					set(level, pos, s.setValue(EyeCameraBlock.MODE, act.equals("disable") ? EyeCameraBlock.Mode.OFF : act.equals("friendly")
						? EyeCameraBlock.Mode.FRIENDLY : EyeCameraBlock.Mode.ON));
				} else if (s.is(DeviceBlocks.TURRET)) {
					set(level, pos, s.setValue(TurretBlock.MODE, act.equals("disable") ? TurretBlock.Mode.OFF : act.equals("friendly")
						? TurretBlock.Mode.FRIENDLY : TurretBlock.Mode.ACTIVE));
				}
			}
			case "shop" -> shop(player, pos, "");
			case "buy" -> buy(player, pos, ShopWire.buyLine(extra));
			case "view" -> LootView.open(player, null, List.of(new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.REDSTONE, 12),
				new ItemStack(DeviceBlocks.EDDIES, 25), new ItemStack(DeviceBlocks.EDDIE_CHIP)));
			default -> DeviceHooks.tell(player, "cyberblock.device.unsupported", "Night City does not let Minecraft do this yet");
		}
	}

	/** The whole gate a cell belongs to: the showcase's gate, else walk to its origin (toward where it slides) and bottom and measure it. */
	private static Gates.Gate gateAt(final ServerLevel level, final BlockPos pos, final BlockState s) {
		if (built != null) {
			Gates.Gate shown = gate(built);
			if (pos.getY() >= shown.origin().getY() && pos.getY() < shown.origin().getY() + shown.height() && pos.getZ() == shown.origin().getZ()
				&& pos.getX() >= shown.origin().getX() && pos.getX() < shown.origin().getX() + shown.width()) {
				return shown;
			}
		}

		Direction facing = s.getValue(FacingDeviceBlock.FACING);
		BlockPos origin = pos;
		while (level.getBlockState(origin.relative(facing.getCounterClockWise())).is(DeviceBlocks.SLIDING_GATE)) {
			origin = origin.relative(facing.getCounterClockWise());
		}

		while (level.getBlockState(origin.below()).is(DeviceBlocks.SLIDING_GATE)) {
			origin = origin.below();
		}

		int width = 1;
		while (width < Gates.MAX_SIZE && level.getBlockState(origin.relative(facing.getClockWise(), width)).is(DeviceBlocks.SLIDING_GATE)) {
			width++;
		}

		int height = 1;
		while (height < Gates.MAX_SIZE && level.getBlockState(origin.above(height)).is(DeviceBlocks.SLIDING_GATE)) {
			height++;
		}

		return new Gates.Gate(origin, facing, width, height);
	}

	private static void shop(final ServerPlayer player, final BlockPos pos, final String result) {
		int eddies = 0;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			eddies += stack.is(DeviceBlocks.EDDIES) ? stack.getCount() : stack.is(DeviceBlocks.EDDIE_CHIP) ? stack.getCount() * DeviceBlocks.EDDIE_CHIP_VALUE : 0;
		}

		ServerNet.ui(player, ShopWire.T_SHOP, ShopWire.shop(new ShopWire.Shop(pos.getX(), pos.getY(), pos.getZ(), "", eddies, DEMO_SHOP, result)));
	}

	private static void buy(final ServerPlayer player, final BlockPos pos, final @Nullable String line) {
		ShopWire.Line found = DEMO_SHOP.stream().filter(l -> l.id().equals(line)).findFirst().orElse(null);
		String result = found == null ? "gone" : found.stock() == 0 ? "stock" : "ok";
		if (found != null && result.equals("ok")) {
			player.getInventory().add(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(found.item())), found.n()));
		}

		shop(player, pos, result.toLowerCase(Locale.ROOT));
	}
}
