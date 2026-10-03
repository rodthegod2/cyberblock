package dev.cyberblock;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Every solid non-barrier block of the world the host plays in (packed BlockPos.asLong), kept with the world (saved data
 * cyberblock:blocks), so that after a restart or a reconnect the host can be told all of them again ({"t":"blocks"}). Kept
 * exactly as BlockEvents reports changes (adds and removes), whether or not a host is attached. Server thread only.
 */
public final class PlacedBlocks extends SavedData {
	static final Codec<PlacedBlocks> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.LONG_STREAM.fieldOf("cells").forGetter(data -> Arrays.stream(data.cells.toLongArray()))
	).apply(i, cells -> new PlacedBlocks(cells.toArray())));
	/** No data fixer: Fabric API's SavedDataStorageMixin reads a type without one as it is. */
	static final SavedDataType<PlacedBlocks> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Cyberblock.ID, "blocks"), PlacedBlocks::new, CODEC, null);
	private final LongOpenHashSet cells;

	PlacedBlocks() {
		this(new long[0]);
	}

	PlacedBlocks(final long[] cells) {
		this.cells = new LongOpenHashSet(cells);
	}

	/** A reported change: the cell is solid now (ADD) or not any more (REMOVE). */
	void apply(final long cell, final BlockEvents.Op op) {
		if (op == BlockEvents.Op.ADD ? this.cells.add(cell) : this.cells.remove(cell)) {
			this.setDirty();
		}
	}

	boolean contains(final long cell) {
		return this.cells.contains(cell);
	}

	int size() {
		return this.cells.size();
	}

	/** A copy of every cell, in no particular order. */
	long[] snapshot() {
		return this.cells.toLongArray();
	}
}
