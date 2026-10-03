package dev.cyberblock.cyber;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cyberblock.Cyberblock;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The real cobwebs Cobweb Daemon puts round its target (plan retest3 section 1.6), per level (saved data cyberblock:hack_webs):
 * each placed cell (packed BlockPos) with the level game time it goes again. Kept with the world so that a web the game was
 * closed on is removed on the next load (cleanAll) instead of staying for good. Server thread only.
 */
public final class HackWebs extends SavedData {
	private static final Codec<HackWebs> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.unboundedMap(Codec.STRING, Codec.LONG).fieldOf("webs").forGetter(HackWebs::asStrings)
	).apply(i, HackWebs::fromStrings));
	/** No data fixer: Fabric API's SavedDataStorageMixin reads a type without one as it is (as PlacedBlocks). */
	private static final SavedDataType<HackWebs> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Cyberblock.ID, "hack_webs"), HackWebs::new, CODEC, null);
	/** Cell to the game time it is removed. */
	private final Long2LongOpenHashMap webs = new Long2LongOpenHashMap();

	private HackWebs() {
	}

	private Map<String, Long> asStrings() {
		Map<String, Long> out = new HashMap<>();
		for (Long2LongMap.Entry e : this.webs.long2LongEntrySet()) {
			out.put(Long.toString(e.getLongKey()), e.getLongValue());
		}

		return out;
	}

	private static HackWebs fromStrings(final Map<String, Long> saved) {
		HackWebs data = new HackWebs();
		saved.forEach((cell, until) -> {
			try {
				data.webs.put(Long.parseLong(cell), (long) until);
			} catch (NumberFormatException e) {
				// an edited save: that entry is dropped
			}
		});
		return data;
	}

	private static HackWebs of(final ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	/**
	 * Cobwebs round `feet` (HackFxRules.webCells), only into air and never near a player (HackFxRules.webAllowed), for
	 * WEB_TICKS. Returns how many were placed.
	 */
	public static int place(final ServerLevel level, final BlockPos feet) {
		List<AABB> players = new ArrayList<>();
		for (Player player : level.players()) {
			players.add(player.getBoundingBox());
		}

		HackWebs data = of(level);
		long until = level.getGameTime() + HackFxRules.WEB_TICKS;
		int placed = 0;
		for (BlockPos cell : HackFxRules.webCells(feet)) {
			if (level.isLoaded(cell) && level.getBlockState(cell).isAir() && HackFxRules.webAllowed(cell, players)
				&& level.setBlock(cell, Blocks.COBWEB.defaultBlockState(), Block.UPDATE_ALL)) {
				data.webs.put(cell.asLong(), until);
				placed++;
			}
		}

		if (placed > 0) {
			data.setDirty();
			Cyberblock.LOG.info("webs: placed {} removed {}", placed, 0);
		}

		return placed;
	}

	/** Every server tick, per level: webs whose time is up go (only while still a cobweb: a player may have broken it). */
	static void tick(final ServerLevel level) {
		HackWebs data = level.getDataStorage().get(TYPE);
		if (data == null || data.webs.isEmpty()) {
			return;
		}

		long now = level.getGameTime();
		int removed = data.removeWhere(level, until -> now >= until);
		if (removed > 0) {
			Cyberblock.LOG.info("webs: placed {} removed {}", 0, removed);
		}
	}

	/**
	 * World load (and server stop): every web still tracked is due now, whatever its time; the loaded ones go at once, the
	 * others as soon as their chunk is loaded again (tick).
	 */
	static void cleanAll(final MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			HackWebs data = level.getDataStorage().get(TYPE);
			if (data != null && !data.webs.isEmpty()) {
				data.webs.replaceAll((cell, until) -> Long.MIN_VALUE);
				int removed = data.removeWhere(level, until -> true);
				Cyberblock.LOG.info("webs: placed {} removed {}", 0, removed);
			}
		}
	}

	/** Removes the due webs in loaded chunks (only while still a cobweb: a player may have broken it); returns how many entries went. */
	private int removeWhere(final ServerLevel level, final LongPredicate due) {
		int removed = 0;
		for (var it = this.webs.long2LongEntrySet().iterator(); it.hasNext(); ) {
			Long2LongMap.Entry e = it.next();
			BlockPos cell = BlockPos.of(e.getLongKey());
			if (!due.test(e.getLongValue()) || !level.isLoaded(cell)) {
				continue;
			}

			if (level.getBlockState(cell).is(Blocks.COBWEB)) {
				level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}

			it.remove();
			removed++;
		}

		if (removed > 0) {
			this.setDirty();
		}

		return removed;
	}
}
