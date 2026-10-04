package dev.cyberblock.client.mode.van;

import dev.cyberblock.holo.HoloWire;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * The Van's miniature of the host's area (megaplan 8-V1: "the Holo-Table renderer, scoped"): the same HoloMesh over the shared Night
 * City map, with the replica detail taken from the blocks this client has loaded there (the netrunner's Van usually stands near the
 * action), as one {@link HoloWire.Detail} patch: per column the surface height and map colour, unknown where no chunk is loaded or
 * nothing in the column shows on a map (the shared map shows there).
 */
final class VanMini {
	/** One column: its surface (the block above the top) and the top block's map colour id, or null when unknown. */
	@FunctionalInterface
	interface Columns {
		int @org.jspecify.annotations.Nullable [] at(int x, int z);
	}

	private VanMini() {
	}

	/** The detail patch of n x n columns from (x0, z0). Pure. */
	static HoloWire.Detail detail(final int x0, final int z0, final int n, final Columns columns) {
		short[] heights = new short[n * n];
		byte[] colours = new byte[n * n];
		for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; x++) {
				int i = z * n + x;
				int[] c = columns.at(x0 + x, z0 + z);
				boolean ok = c != null && c[1] > 0 && c[1] < 64 && c[0] > Short.MIN_VALUE && c[0] < Short.MAX_VALUE;
				heights[i] = ok ? (short) c[0] : HoloWire.UNKNOWN;
				colours[i] = ok ? (byte) c[1] : 0;
			}
		}

		return new HoloWire.Detail(x0, z0, n, heights, colours);
	}

	/** How many chunks under the n x n columns from (x0, z0) this client has loaded (a change remakes the detail). */
	static int loadedChunks(final ClientLevel level, final int x0, final int z0, final int n) {
		int count = 0;
		for (int cz = z0 >> 4; cz <= (z0 + n - 1) >> 4; cz++) {
			for (int cx = x0 >> 4; cx <= (x0 + n - 1) >> 4; cx++) {
				count += level.getChunk(cx, cz, ChunkStatus.FULL, false) != null ? 1 : 0;
			}
		}

		return count;
	}

	/**
	 * The loaded blocks of this client's level as columns: the top block that shows on a map, as the photo feed's replica view
	 * (ReplicaFeed) finds it: glass, barriers and the like show what is under them; a column with nothing that shows is unknown.
	 */
	static Columns of(final ClientLevel level) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		return (x, z) -> {
			ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
			if (chunk == null) {
				return null;
			}

			for (int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15); y >= level.getMinY(); y--) {
				MapColor colour = chunk.getBlockState(pos.set(x, y, z)).getMapColor(level, pos);
				if (colour != MapColor.NONE) {
					return new int[] {y + 1, colour.id};
				}
			}

			return null;
		};
	}
}
