package dev.cyberblock.hack.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;

/**
 * The photo feed's written fallback (megaplan task 6-D1): when the host's DLL sends no picture (the read-back cost too much or
 * cannot run, and `feed` is then simply absent, section 1.9), the server draws the replica from above around V, one block per
 * pixel, into the same 128 x 128 map colours a `feed` carries, so the Net Map and the feed map show it the same way. Low
 * resolution by design. Only chunks already loaded are read (never loads one); V is a red mark in the middle. Server thread.
 */
final class ReplicaFeed {
	/** No picture this long after `feedreq on` (or after the last picture) and the replica stands in. */
	static final long WAIT_MS = 3_000L;
	private static final int SIDE = ActWire.FEED_SIDE;

	private ReplicaFeed() {
	}

	/** Whether the replica stands in now: the feed went on at `onAt` and no picture came since then (or since `lastFrame`). Pure. */
	static boolean due(final long onAt, final long lastFrame, final long now) {
		return now - Math.max(onAt, lastFrame) >= WAIT_MS;
	}

	/**
	 * Packed map colours (colour id * 4 + brightness id) from each column's top: its MapColor id (0: nothing, transparent) and
	 * height, row by row from the north-west. As a map item: higher than the column to the north is bright, lower is dark. Pure.
	 */
	static byte[] shade(final int[] colourIds, final int[] heights) {
		byte[] px = new byte[SIDE * SIDE];
		for (int z = 0; z < SIDE; z++) {
			for (int x = 0; x < SIDE; x++) {
				int i = z * SIDE + x;
				if (colourIds[i] == 0) {
					continue;
				}

				int north = z > 0 ? heights[i - SIDE] : heights[i];
				MapColor.Brightness b = heights[i] > north ? MapColor.Brightness.HIGH : heights[i] < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
				px[i] = (byte) (colourIds[i] * 4 + b.id);
			}
		}

		return px;
	}

	/** The replica around `centre` from above, as a `feed`'s 16384 map colour bytes. */
	static byte[] render(final ServerLevel level, final Vec3 centre) {
		int[] colourIds = new int[SIDE * SIDE], heights = new int[SIDE * SIDE];
		int x0 = (int) Math.floor(centre.x) - SIDE / 2, z0 = (int) Math.floor(centre.z) - SIDE / 2;
		int minY = level.getMinY();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = 0; z < SIDE; z++) {
			for (int x = 0; x < SIDE; x++) {
				int wx = x0 + x, wz = z0 + z;
				LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(wx), SectionPos.blockToSectionCoord(wz));
				if (chunk == null) {
					continue;
				}

				// the top block that shows on a map (as a map item: glass, barriers and the like show what is under them)
				int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
				for (; y >= minY; y--) {
					BlockState state = chunk.getBlockState(pos.set(wx, y, wz));
					MapColor colour = state.getMapColor(level, pos);
					if (colour != MapColor.NONE) {
						colourIds[z * SIDE + x] = colour.id;
						heights[z * SIDE + x] = y;
						break;
					}
				}
			}
		}

		byte[] px = shade(colourIds, heights);
		byte v = MapColor.COLOR_RED.getPackedId(MapColor.Brightness.HIGH);
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				px[(SIDE / 2 + dz) * SIDE + SIDE / 2 + dx] = v;
			}
		}

		return px;
	}
}
