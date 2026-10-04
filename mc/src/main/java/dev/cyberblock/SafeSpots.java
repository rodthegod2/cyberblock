package dev.cyberblock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The void-fall rescue (plan Q-B6). Night City's ground is outline blocks over Minecraft's void, and a creative player may break
 * them: per player the last places they stood on a solid block are kept, and a player who falls more than FALL_BLOCKS below the
 * latest within RECENT_TICKS of an outline break nearby, or below the world's minimum build height plus VOID_MARGIN, is put back on
 * the newest of them that still has a block under it, with no fall damage. Not while flying, gliding, spectating, riding, or while
 * the host drives Steve (V's ground is his then). Server thread only.
 */
public final class SafeSpots {
	static final int FALL_BLOCKS = 16;
	/** 3 seconds. */
	static final int RECENT_TICKS = 60;
	/** An outline break this close to the player's spot (horizontally, in blocks) counts as nearby. */
	static final int NEAR_BLOCKS = 8;
	static final int VOID_MARGIN = 8;
	/** Places kept per player: the newest can be the very block that was broken. */
	static final int KEEP_SPOTS = 8;

	/** One player's last places standing on a solid block (the feet's block position), newest last, no two the same in a row. */
	static final class Track {
		private final ArrayDeque<BlockPos> spots = new ArrayDeque<>();

		void stood(final BlockPos feet) {
			if (feet.equals(this.spots.peekLast())) {
				return;
			}

			this.spots.addLast(feet.immutable());
			if (this.spots.size() > KEEP_SPOTS) {
				this.spots.removeFirst();
			}
		}

		@Nullable BlockPos newest() {
			return this.spots.peekLast();
		}

		/** The newest place that is still supported (a block under it), or null. */
		@Nullable BlockPos pick(final Predicate<BlockPos> supported) {
			for (Iterator<BlockPos> it = this.spots.descendingIterator(); it.hasNext();) {
				BlockPos spot = it.next();
				if (supported.test(spot)) {
					return spot;
				}
			}

			return null;
		}
	}

	private static final Map<UUID, Track> TRACKS = new HashMap<>();
	/** Recent outline breaks: cell to tick. */
	private static final Map<BlockPos, Long> BREAKS = new HashMap<>();
	/** The spawn platform reaches this far from the world spawn (a 5 x 5 layer under it). */
	static final int PLATFORM_RADIUS = 2;
	/** Feet height for a spawn moved off the void floor: Minecraft's usual surface (sea level 63, plus the platform under it). */
	static final int VOID_SPAWN_Y = 65;
	/** The spawn platform's barrier cells this run (placed now, or found where it was placed before). */
	private static final Set<BlockPos> PLATFORM = new HashSet<>();

	private SafeSpots() {
	}

	/**
	 * A new void world puts its spawn on the floor of the world (a flat world with no layers: minimum build height), where a player
	 * would drop straight into the void; such a spawn moves up to VOID_SPAWN_Y at the same x and z.
	 */
	static BlockPos usableSpawn(final BlockPos spawn, final int minY) {
		return spawn.getY() <= minY + VOID_MARGIN ? new BlockPos(spawn.getX(), VOID_SPAWN_Y, spawn.getZ()) : spawn;
	}

	/** The spawn platform's cells: a (2r+1) x (2r+1) layer one block under the spawn. */
	static List<BlockPos> platformCells(final BlockPos spawn) {
		List<BlockPos> cells = new ArrayList<>();
		for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
			for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
				cells.add(spawn.offset(dx, -1, dz));
			}
		}

		return cells;
	}

	/**
	 * A dedicated Night City server (WorldCheck): guests without Cyberpunk join over the void before any replica block exists, so the
	 * world spawn gets a small platform of barrier blocks (invisible, never sent to a host: BlockEvents ignores barriers), placed only
	 * into air. When Night City or player blocks already lie under the spawn nothing is placed; once they arrive the platform is left
	 * inert, and the replica may take its cells over ({@link #releaseSpawnPlatformCell}). The void-fall rescue lands on it.
	 */
	public static void placeSpawnPlatform(final ServerLevel level) {
		PLATFORM.clear();
		LevelData.RespawnData respawn = level.getRespawnData();
		if (!respawn.dimension().equals(level.dimension())) {
			return;
		}

		BlockPos spawn = usableSpawn(respawn.pos(), level.getMinY());
		if (!spawn.equals(respawn.pos())) {
			level.setRespawnData(LevelData.RespawnData.of(level.dimension(), spawn, respawn.yaw(), respawn.pitch()));
			Cyberblock.LOG.info("spawn: the world spawn was on the void floor (y {}): moved to {} {} {}", respawn.pos().getY(), spawn.getX(), spawn.getY(),
				spawn.getZ());
		}

		List<BlockPos> cells = platformCells(spawn);
		if (cells.stream().anyMatch(c -> level.isOutsideBuildHeight(c))) {
			Cyberblock.LOG.warn("spawn: no platform: the world spawn {} {} {} is at the edge of the build height", spawn.getX(), spawn.getY(), spawn.getZ());
			return;
		}

		if (cells.stream().anyMatch(c -> !level.getBlockState(c).isAir() && !level.getBlockState(c).is(Blocks.BARRIER))) {
			Cyberblock.LOG.info("spawn: blocks already lie under the world spawn: no platform needed");
			return;
		}

		int placed = 0;
		for (BlockPos c : cells) {
			if (level.getBlockState(c).isAir()) {
				level.setBlockAndUpdate(c, Blocks.BARRIER.defaultBlockState());
				placed++;
			}

			PLATFORM.add(c.immutable());
		}

		Cyberblock.LOG.info("spawn: platform under the world spawn {} {} {} ({} barrier blocks placed, {} kept): guests land here until Night City blocks exist",
			spawn.getX(), spawn.getY(), spawn.getZ(), placed, PLATFORM.size() - placed);
	}

	/**
	 * For the replica (3R): a barrier at this cell is the spawn platform's, not Night City ground, so a replica block may replace it;
	 * true (and the platform forgets the cell) when it was one. Server thread.
	 */
	public static boolean releaseSpawnPlatformCell(final BlockPos cell) {
		return PLATFORM.remove(cell);
	}

	/**
	 * Where a rescued player goes: the newest of its own spots that still has a block under it, else the world spawn when something
	 * holds it up (the spawn platform or Night City blocks), else nowhere.
	 */
	static @Nullable BlockPos rescueTarget(final Track track, final Predicate<BlockPos> supported, final @Nullable BlockPos spawn) {
		BlockPos own = track.pick(supported);
		return own != null ? own : spawn != null && supported.test(spawn) ? spawn : null;
	}

	/** y is the falling player's, spotY their last safe spot's, minY the world's minimum build height. */
	static boolean shouldRescue(final double y, final int spotY, final int minY, final boolean recentBreakNearby) {
		return y < minY + VOID_MARGIN || recentBreakNearby && y < spotY - FALL_BLOCKS;
	}

	/** An outline break at cell on breakTick is nearby and recent for a player whose spot is spot, at tick now. */
	static boolean nearbyAndRecent(final BlockPos spot, final BlockPos cell, final long breakTick, final long now) {
		int dx = cell.getX() - spot.getX(), dz = cell.getZ() - spot.getZ();
		return now - breakTick <= RECENT_TICKS && dx * dx + dz * dz <= NEAR_BLOCKS * NEAR_BLOCKS;
	}

	/** WorldBridge, when a player broke an outline block. */
	static void outlineBroke(final BlockPos cell, final long tick) {
		BREAKS.put(cell.immutable(), tick);
	}

	static void clear() {
		TRACKS.clear();
		BREAKS.clear();
		PLATFORM.clear();
	}

	/** Every server tick. */
	static void tick(final MinecraftServer server) {
		long now = server.getTickCount();
		BREAKS.values().removeIf(tick -> now - tick > RECENT_TICKS);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (Cyberblock.hostActive(player) || player.isSpectator() || player.getAbilities().flying || player.isFallFlying() || player.isPassenger()) {
				continue;
			}

			ServerLevel level = player.level();
			Track track = TRACKS.computeIfAbsent(player.getUUID(), id -> new Track());
			BlockPos feet = player.blockPosition();
			if (player.onGround() && !level.getBlockState(feet.below()).isAir()) {
				track.stood(feet);
				continue;
			}

			// a guest who never stood on anything (it joined over the void) has no spot: only the void threshold applies, and it goes
			// to the world spawn when something holds it up there (the spawn platform)
			BlockPos spot = track.newest();
			boolean recent = spot != null && BREAKS.entrySet().stream().anyMatch(e -> nearbyAndRecent(spot, e.getKey(), e.getValue(), now));
			if (!shouldRescue(player.getY(), spot == null ? 0 : spot.getY(), level.getMinY(), recent)) {
				continue;
			}

			LevelData.RespawnData respawn = server.getRespawnData();
			BlockPos spawn = respawn.dimension().equals(level.dimension()) ? respawn.pos() : null;
			BlockPos to = rescueTarget(track, p -> !level.getBlockState(p.below()).isAir(), spawn);
			if (to != null) {
				rescue(player, level, to);
			}
		}
	}

	private static void rescue(final ServerPlayer player, final ServerLevel level, final BlockPos to) {
		player.resetFallDistance();
		player.setDeltaMovement(Vec3.ZERO);
		player.teleportTo(level, to.getX() + 0.5, to.getY(), to.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
		player.resetFallDistance();
		player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.outline.rescued", "Caught you: back to safe ground"));
		Cyberblock.LOG.info("outline: rescued {} to {} {} {}", player.getName().getString(), to.getX(), to.getY(), to.getZ());
	}
}
