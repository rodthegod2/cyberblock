package dev.cyberblock.replica;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cyberblock.Cyberblock;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * The Offline Builds log (megaplan task 3-R2): every player block change the hosts' Night City hears about (a solid block placed or
 * removed, BlockEvents), with the player's name and the time, kept with the world (saved data cyberblock:changes) for 30 days and at
 * most {@link #REGION_CAP} per region (512 x 512 blocks). Package 3E reads it on a host's return ({@link #since}: the changes after
 * the host's last session end, which HostsData keeps). Server thread only.
 */
public final class ChangeLog extends SavedData {
	public static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000;
	public static final int REGION_CAP = 4096;

	/** One change: the cell (packed BlockPos), placed or removed, by whom, when (epoch ms). */
	public record Change(long cell, boolean placed, String name, long time) {
		static final Codec<Change> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.LONG.fieldOf("cell").forGetter(Change::cell),
			Codec.BOOL.fieldOf("placed").forGetter(Change::placed),
			Codec.STRING.fieldOf("name").forGetter(Change::name),
			Codec.LONG.fieldOf("time").forGetter(Change::time)
		).apply(i, Change::new));

		/** The region key of the cell: (x >> 9, z >> 9) packed. */
		public long region() {
			return ChangeLog.region(this.cell);
		}
	}

	static final Codec<ChangeLog> CODEC = RecordCodecBuilder.create(i -> i.group(
		Change.CODEC.listOf().fieldOf("changes").forGetter(log -> log.changes)
	).apply(i, ChangeLog::new));
	/** No data fixer: Fabric API's SavedDataStorageMixin reads a type without one as it is (as PlacedBlocks). */
	static final SavedDataType<ChangeLog> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Cyberblock.ID, "changes"), ChangeLog::new, CODEC, null);

	/** Oldest first. */
	private final List<Change> changes;
	private final Long2IntOpenHashMap perRegion = new Long2IntOpenHashMap();

	ChangeLog() {
		this(List.of());
	}

	ChangeLog(final List<Change> changes) {
		this.changes = new ArrayList<>(changes);
		this.changes.forEach(c -> this.perRegion.addTo(c.region(), 1));
	}

	public static long region(final long cell) {
		return ((long) (BlockPos.getX(cell) >> 9) << 32) | ((BlockPos.getZ(cell) >> 9) & 0xFFFFFFFFL);
	}

	/** Adds a change made at its time (now): older than 30 days go, and the region's oldest when it is full. */
	void add(final Change c) {
		this.prune(c.time());
		this.changes.add(c);
		if (this.perRegion.addTo(c.region(), 1) + 1 > REGION_CAP) {
			for (Iterator<Change> it = this.changes.iterator(); it.hasNext();) {
				if (it.next().region() == c.region()) {
					it.remove();
					this.perRegion.addTo(c.region(), -1);
					break;
				}
			}
		}

		this.setDirty();
	}

	/** Forgets the changes older than KEEP_MS at now. */
	void prune(final long now) {
		int gone = 0;
		while (gone < this.changes.size() && now - this.changes.get(gone).time() > KEEP_MS) {
			this.perRegion.addTo(this.changes.get(gone).region(), -1);
			gone++;
		}

		if (gone > 0) {
			this.changes.subList(0, gone).clear();
			this.setDirty();
		}
	}

	/** The changes after epochMs, oldest first (a copy). */
	List<Change> after(final long epochMs) {
		List<Change> out = new ArrayList<>();
		for (Change c : this.changes) {
			if (c.time() > epochMs) {
				out.add(c);
			}
		}

		return out;
	}

	int size() {
		return this.changes.size();
	}

	int inRegion(final long region) {
		return this.perRegion.get(region);
	}

	// ---- the running world ------------------------------------------------------------------------------------------------

	/** Who is changing which cells right now: a player's use of a block (the placed cell is the hit or its neighbour) or a break. */
	private record Pending(String name, long a, long b, int tick) {
	}

	private static @Nullable ChangeLog log;
	private static @Nullable Pending pending;

	static void attach(final MinecraftServer server) {
		ChangeLog l = server.overworld().getDataStorage().computeIfAbsent(TYPE);
		l.prune(System.currentTimeMillis());
		log = l;
	}

	static void detach() {
		use(null);
	}

	/** The running world's log (attach, detach, tests). */
	static void use(final @Nullable ChangeLog l) {
		log = l;
		pending = null;
	}

	/** The changes in this world after epochMs (empty when no world runs). */
	public static List<Change> since(final long epochMs) {
		ChangeLog l = log;
		return l == null ? List.of() : l.after(epochMs);
	}

	/** A player is about to change these cells in this server tick (a block use: hit and hit + face; a break: the cell twice). */
	static void acting(final String name, final long a, final long b, final int tick) {
		pending = new Pending(name, a, b, tick);
	}

	/** From WorldBridge.onBlockChanged: a solid block was placed or removed at cell during tick; logged when a player is doing it. */
	public static void changed(final long cell, final boolean placed, final int tick) {
		Pending p = pending;
		ChangeLog l = log;
		if (l != null && p != null && p.tick() == tick && (p.a() == cell || p.b() == cell)) {
			l.add(new Change(cell, placed, p.name(), System.currentTimeMillis()));
		}
	}
}
