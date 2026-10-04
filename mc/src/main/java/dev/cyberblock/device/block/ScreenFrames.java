package dev.cyberblock.device.block;

import com.mojang.serialization.Codec;
import dev.cyberblock.Cyberblock;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * TVs and billboards (section 1.11, toggle bit 512 {@code screen}) for the server (4M): a {@code width x height} wall of
 * {@link ScreenFrameBlock}s (the screen's body), each with a vanilla glow item frame on its front (4A's device_map {@code entity})
 * holding a locked filled map with its tile of a {@link ScreenImage} picture. The frames are fixed (nothing can be taken out, only
 * creative players can break them) and their map items carry the custom-data mark {@link #MARK}, so an active host's client hides
 * them like the device blocks. The maps are made once per picture tile and reused (SavedData
 * {@code cyberblock:screens}), so screens that come and go never pile up map files. The frames follow the body blocks however they are
 * set: {@link ScreenFrameBlock} refreshes its panel when a body block appears or switches, and {@link #init}'s sweep takes away a frame
 * whose body is gone, so the server may simply set screen_frame blocks (4M does) or use {@link #place}, {@link #set} and
 * {@link #remove}. Server thread.
 */
public final class ScreenFrames {
	/** The custom-data key on a screen's map items (and the entity tag of its frames). */
	public static final String MARK = "cyberblock_screen";
	public static final int MAX_SIZE = 4;
	/** How far a refresh looks for the rest of a screen, each way. */
	private static final int RUN_LIMIT = 64;
	/** Ticks between two checks for frames whose body is gone. */
	private static final int SWEEP_TICKS = 20;
	/** Every screen frame in a loaded chunk (server thread). */
	private static final Set<ItemFrame> FRAMES = Collections.newSetFromMap(new IdentityHashMap<>());
	/** The map colour of each ScreenImage ink. */
	private static final byte[] COLOURS = {
		MapColor.COLOR_BLACK.getPackedId(MapColor.Brightness.LOWEST),
		MapColor.COLOR_GRAY.getPackedId(MapColor.Brightness.LOWEST),
		MapColor.COLOR_GRAY.getPackedId(MapColor.Brightness.HIGH),
		MapColor.SNOW.getPackedId(MapColor.Brightness.HIGH),
		MapColor.COLOR_CYAN.getPackedId(MapColor.Brightness.HIGH),
		MapColor.COLOR_MAGENTA.getPackedId(MapColor.Brightness.HIGH),
		MapColor.COLOR_YELLOW.getPackedId(MapColor.Brightness.HIGH),
		MapColor.COLOR_ORANGE.getPackedId(MapColor.Brightness.HIGH),
		MapColor.COLOR_RED.getPackedId(MapColor.Brightness.HIGH),
		MapColor.COLOR_BLUE.getPackedId(MapColor.Brightness.HIGH),
		MapColor.COLOR_PURPLE.getPackedId(MapColor.Brightness.HIGH),
		MapColor.COLOR_GREEN.getPackedId(MapColor.Brightness.HIGH)};

	/**
	 * One screen: {@code origin} is its bottom-left cell as seen from the front, {@code facing} the way the picture looks (horizontal),
	 * 1..{@link #MAX_SIZE} frames each way.
	 */
	public record Screen(BlockPos origin, Direction facing, int width, int height) {
		public Screen {
			if (!facing.getAxis().isHorizontal() || width < 1 || width > MAX_SIZE || height < 1 || height > MAX_SIZE) {
				throw new IllegalArgumentException("bad screen " + facing + " " + width + "x" + height);
			}

			origin = origin.immutable();
		}

		/** The body cell of tile (column from the viewer's left, row from the bottom). */
		BlockPos cell(final int column, final int row) {
			return this.origin.relative(this.facing.getCounterClockWise(), column).above(row);
		}

		/** The cell in front of a body cell, where its frame hangs. */
		BlockPos frame(final int column, final int row) {
			return this.cell(column, row).relative(this.facing);
		}
	}

	/** The map id of every picture tile made so far. */
	static final class Maps extends SavedData {
		static final Codec<Maps> CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT).xmap(Maps::new, maps -> maps.ids);
		static final SavedDataType<Maps> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Cyberblock.ID, "screens"), Maps::new, CODEC, null);
		private final Map<String, Integer> ids;

		Maps() {
			this(Map.of());
		}

		Maps(final Map<String, Integer> ids) {
			this.ids = new HashMap<>(ids);
		}
	}

	private ScreenFrames() {
	}

	/**
	 * The frames follow the body blocks: every screen frame the server knows (spawned or loaded) is checked once a second, and one whose
	 * body block is gone is taken away, so a screen the server removes by setting its blocks to air (4M at device removal and session
	 * end) leaves no fixed frame behind, even across a restart. Common init, once.
	 */
	static void init() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof ItemFrame frame && frame.entityTags().contains(MARK)) {
				FRAMES.add(frame);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof ItemFrame frame) {
				FRAMES.remove(frame);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % SWEEP_TICKS == 0 && !FRAMES.isEmpty()) {
				sweep();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> FRAMES.clear());
	}

	private static void sweep() {
		for (ItemFrame frame : List.copyOf(FRAMES)) {
			BlockPos body = frame.getPos().relative(frame.getDirection().getOpposite());
			if (frame.isRemoved()) {
				FRAMES.remove(frame);
			} else if (frame.level().isLoaded(body) && !frame.level().getBlockState(body).is(DeviceBlocks.SCREEN_FRAME)) {
				FRAMES.remove(frame);
				frame.discard();
			}
		}
	}

	/**
	 * Puts the screen up: its body blocks into {@link DeviceBlocks#free free} cells, and its frames ({@link #show}); returns how many
	 * frames were hung.
	 */
	public static int place(final ServerLevel level, final Screen screen, final boolean on) {
		BlockState body = DeviceBlocks.SCREEN_FRAME.defaultBlockState().setValue(FacingDeviceBlock.FACING, screen.facing()).setValue(ScreenFrameBlock.ON, on);
		for (int column = 0; column < screen.width(); column++) {
			for (int row = 0; row < screen.height(); row++) {
				BlockPos cell = screen.cell(column, row);
				if (DeviceBlocks.free(level.getBlockState(cell))) {
					level.setBlock(cell, body, Block.UPDATE_ALL);
				}
			}
		}

		return show(level, screen);
	}

	/** Switches the screen on or off. */
	public static void set(final ServerLevel level, final Screen screen, final boolean on) {
		for (int column = 0; column < screen.width(); column++) {
			for (int row = 0; row < screen.height(); row++) {
				BlockPos cell = screen.cell(column, row);
				BlockState body = level.getBlockState(cell);
				if (body.is(DeviceBlocks.SCREEN_FRAME) && body.getValue(ScreenFrameBlock.ON) != on) {
					level.setBlock(cell, body.setValue(ScreenFrameBlock.ON, on), Block.UPDATE_ALL);
				}
			}
		}

		show(level, screen);
	}

	/**
	 * The panel {@code pos} belongs to gets its frames ({@link ScreenFrameBlock}'s refresh): the body blocks facing {@code facing} in a
	 * row and a column through {@code pos} make one screen, cut into panels of at most {@link #MAX_SIZE} each way from its bottom-left.
	 */
	static void refresh(final ServerLevel level, final BlockPos pos, final Direction facing) {
		Direction right = facing.getCounterClockWise();
		int left = run(level, pos, right.getOpposite(), facing), below = run(level, pos, Direction.DOWN, facing);
		int width = left + 1 + run(level, pos, right, facing), height = below + 1 + run(level, pos, Direction.UP, facing);
		int column = left / MAX_SIZE * MAX_SIZE, row = below / MAX_SIZE * MAX_SIZE;
		BlockPos origin = pos.relative(right, column - left).below(below - row);
		show(level, new Screen(origin, facing, Math.min(MAX_SIZE, width - column), Math.min(MAX_SIZE, height - row)));
	}

	/** How many body blocks facing {@code facing} follow {@code pos} toward {@code way} (at most {@link #RUN_LIMIT}). */
	private static int run(final ServerLevel level, final BlockPos pos, final Direction way, final Direction facing) {
		int n = 0;
		BlockState s;
		while (n < RUN_LIMIT && (s = level.getBlockState(pos.relative(way, n + 1))).is(DeviceBlocks.SCREEN_FRAME) && s.getValue(FacingDeviceBlock.FACING) == facing) {
			n++;
		}

		return n;
	}

	/**
	 * Every body block of the screen gets its frame with its tile of the screen's picture, lit or dark as the block: a missing frame is
	 * hung where the front cell is air, a hanging one gets the right map. Returns how many frames were hung.
	 */
	static int show(final ServerLevel level, final Screen screen) {
		int pattern = pattern(screen), hung = 0;
		for (int column = 0; column < screen.width(); column++) {
			for (int row = 0; row < screen.height(); row++) {
				BlockState body = level.getBlockState(screen.cell(column, row));
				if (!body.is(DeviceBlocks.SCREEN_FRAME) || body.getValue(FacingDeviceBlock.FACING) != screen.facing()) {
					continue;
				}

				ItemStack map = map(level, screen, pattern, body.getValue(ScreenFrameBlock.ON), column, row);
				BlockPos front = screen.frame(column, row);
				ItemFrame frame = frameAt(level, front, screen.facing());
				if (frame != null) {
					if (!Objects.equals(frame.getItem().get(DataComponents.MAP_ID), map.get(DataComponents.MAP_ID))) {
						frame.setItem(map, false);
					}
				} else if (level.getBlockState(front).isAir()) {
					frame = new GlowItemFrame(level, front, screen.facing());
					frame.setItem(map, false);
					frame.addTag(MARK);
					fix(level, frame);
					hung += level.addFreshEntity(frame) ? 1 : 0;
				}
			}
		}

		return hung;
	}

	/** The screen's picture: chosen by where its panel starts, so it stays the same however often the panel is refreshed. Pure. */
	static int pattern(final Screen screen) {
		return Math.floorMod(screen.origin().hashCode(), ScreenImage.PATTERNS);
	}

	/** Takes the screen away: its frames and body blocks. */
	public static void remove(final ServerLevel level, final Screen screen) {
		for (int column = 0; column < screen.width(); column++) {
			for (int row = 0; row < screen.height(); row++) {
				ItemFrame frame = frameAt(level, screen.frame(column, row), screen.facing());
				if (frame != null) {
					frame.discard();
				}

				if (level.getBlockState(screen.cell(column, row)).is(DeviceBlocks.SCREEN_FRAME)) {
					level.setBlock(screen.cell(column, row), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
	}

	/** Is this a screen's map item (both sides: the custom data travels with the item)? */
	public static boolean isScreen(final ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(MARK);
	}

	private static @Nullable ItemFrame frameAt(final ServerLevel level, final BlockPos cell, final Direction facing) {
		for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, new AABB(cell), f -> f.entityTags().contains(MARK) && f.getDirection() == facing)) {
			return frame;
		}

		return null;
	}

	/** The map item of one tile, made (and remembered) the first time. */
	private static ItemStack map(final ServerLevel level, final Screen screen, final int pattern, final boolean on, final int column, final int row) {
		int p = Math.floorMod(pattern, ScreenImage.PATTERNS);
		int tileRow = screen.height() - 1 - row;
		String key = "p" + p + "/" + screen.width() + "x" + screen.height() + "/" + column + "," + tileRow + "/" + (on ? 1 : 0);
		ServerLevel overworld = level.getServer().overworld();
		Maps maps = overworld.getDataStorage().computeIfAbsent(Maps.TYPE);
		Integer known = maps.ids.get(key);
		MapId id = known == null ? null : new MapId(known);
		if (id == null || overworld.getMapData(id) == null) {
			id = overworld.getFreeMapId();
			MapItemSavedData data = MapItemSavedData.createFresh(0.0, 0.0, (byte) 0, false, false, Level.OVERWORLD).locked();
			byte[] inks = ScreenImage.tile(p, screen.width(), screen.height(), column, tileRow, on);
			for (int i = 0; i < inks.length; i++) {
				data.colors[i] = COLOURS[inks[i]];
			}

			data.setDirty();
			overworld.setMapData(id, data);
			maps.ids.put(key, id.id());
			maps.setDirty();
		}

		ItemStack stack = new ItemStack(Items.FILLED_MAP);
		stack.set(DataComponents.MAP_ID, id);
		CompoundTag mark = new CompoundTag();
		mark.putBoolean(MARK, true);
		CustomData.set(DataComponents.CUSTOM_DATA, stack, mark);
		return stack;
	}

	/** A fixed frame: vanilla keeps "Fixed" only in its saved data, so the frame goes through a save and load with it set. */
	private static void fix(final ServerLevel level, final ItemFrame frame) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		frame.saveWithoutId(out);
		CompoundTag tag = out.buildResult();
		tag.putBoolean("Fixed", true);
		frame.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
	}
}
