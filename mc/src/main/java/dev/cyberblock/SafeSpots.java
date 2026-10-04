package dev.cyberblock;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

	private SafeSpots() {
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
	}

	/** Every server tick. */
	static void tick(final MinecraftServer server) {
		long now = server.getTickCount();
		BREAKS.values().removeIf(tick -> now - tick > RECENT_TICKS);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (Cyberblock.active || player.isSpectator() || player.getAbilities().flying || player.isFallFlying() || player.isPassenger()) {
				continue;
			}

			ServerLevel level = player.level();
			Track track = TRACKS.computeIfAbsent(player.getUUID(), id -> new Track());
			BlockPos feet = player.blockPosition();
			if (player.onGround() && !level.getBlockState(feet.below()).isAir()) {
				track.stood(feet);
				continue;
			}

			BlockPos spot = track.newest();
			if (spot == null) {
				continue;
			}

			boolean recent = BREAKS.entrySet().stream().anyMatch(e -> nearbyAndRecent(spot, e.getKey(), e.getValue(), now));
			if (!shouldRescue(player.getY(), spot.getY(), level.getMinY(), recent)) {
				continue;
			}

			BlockPos to = track.pick(p -> !level.getBlockState(p.below()).isAir());
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
