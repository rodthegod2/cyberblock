package dev.cyberblock.device.block;

import com.mojang.serialization.Codec;
import dev.cyberblock.Cyberblock;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
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
 * TVs and billboards (section 1.11, toggle bit 512 {@code screen}) for the server (4M): a {@code width x height} wall of vanilla item
 * frames, each holding a locked filled map with its tile of a {@link ScreenImage} picture. The frames are fixed (no support needed,
 * nothing can be taken out, only creative players can break them) and their map items carry the custom-data mark {@link #MARK}, so
 * an active host's client hides them like the device blocks. The maps are made once per picture tile and reused (SavedData
 * {@code cyberblock:screens}), so screens that come and go never pile up map files. Server thread.
 */
public final class ScreenFrames {
	/** The custom-data key on a screen's map items (and the entity tag of its frames). */
	public static final String MARK = "cyberblock_screen";
	public static final int MAX_SIZE = 4;
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

		/** The cell of frame (column from the viewer's left, row from the bottom). */
		BlockPos cell(final int column, final int row) {
			return this.origin.relative(this.facing.getCounterClockWise(), column).above(row);
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

	/** Hangs the screen's frames (cells that already hold one of its frames keep it); returns how many were hung. */
	public static int place(final ServerLevel level, final Screen screen, final int pattern, final boolean on) {
		int hung = 0;
		for (int column = 0; column < screen.width(); column++) {
			for (int row = 0; row < screen.height(); row++) {
				BlockPos cell = screen.cell(column, row);
				if (frameAt(level, cell, screen.facing()) != null || !DeviceBlocks.free(level.getBlockState(cell))) {
					continue;
				}

				ItemFrame frame = new ItemFrame(level, cell, screen.facing());
				frame.setItem(map(level, screen, pattern, on, column, row), false);
				frame.addTag(MARK);
				fix(level, frame);
				if (level.addFreshEntity(frame)) {
					hung++;
				}
			}
		}

		return hung;
	}

	/** Shows another picture, or switches the screen on or off. */
	public static void set(final ServerLevel level, final Screen screen, final int pattern, final boolean on) {
		for (int column = 0; column < screen.width(); column++) {
			for (int row = 0; row < screen.height(); row++) {
				ItemFrame frame = frameAt(level, screen.cell(column, row), screen.facing());
				if (frame != null) {
					frame.setItem(map(level, screen, pattern, on, column, row), false);
				}
			}
		}
	}

	/** Takes the screen's frames away. */
	public static void remove(final ServerLevel level, final Screen screen) {
		for (int column = 0; column < screen.width(); column++) {
			for (int row = 0; row < screen.height(); row++) {
				ItemFrame frame = frameAt(level, screen.cell(column, row), screen.facing());
				if (frame != null) {
					frame.discard();
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
