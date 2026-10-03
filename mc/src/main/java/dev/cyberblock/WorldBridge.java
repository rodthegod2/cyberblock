// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Server-side half: Night City's collision as invisible barrier blocks, commands, and block changes for the host. */
public final class WorldBridge {
	/** More than this in one message is a bug or an attack: it would stall the server thread. */
	private static final long MAX_CELLS = 16_384L;
	private static volatile MinecraftServer server;
	/** Barriers we placed (so a reset only removes ours, never the player's builds). */
	private static final Set<BlockPos> barriers = ConcurrentHashMap.newKeySet();
	/** Block changes the host has yet to hear about (server thread only), a few per tick. */
	private static final BlockEvents.Outbox blockEvents = new BlockEvents.Outbox();
	/** While placing or removing the host's own ground: those changes aren't news to the host (server thread only). */
	private static boolean placingGround;

	private WorldBridge() {
	}

	static void attach(final MinecraftServer s) {
		server = s;
	}

	/** Server thread, before the world is saved: take our barriers out so the save is vanilla. */
	static void detach() {
		MinecraftServer s = server;
		server = null;
		blockEvents.clear();
		if (s != null) {
			removeBarriers(s.overworld());
		}
	}

	/** Night City ground from the host: barriers, only into air. */
	public static void solid(final List<Ranges.Range> ranges) {
		edit(ranges, true);
	}

	/** Ranges the host no longer reports as solid: our barriers there come out; anything else (the player's builds) stays. */
	public static void unsolid(final List<Ranges.Range> ranges) {
		edit(ranges, false);
	}

	private static void edit(final List<Ranges.Range> ranges, final boolean place) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerLevel level = s.overworld();
			long cells = 0;
			for (Ranges.Range r : ranges) {
				cells += Math.max(0L, (long) Math.min(r.yTop(), level.getMaxY()) - Math.max(r.yBottom(), level.getMinY()) + 1L);
			}

			if (cells > MAX_CELLS) {
				Cyberblock.LOG.warn("ignoring {} of {} cells (limit {})", place ? "ground" : "unground", cells, MAX_CELLS);
				return;
			}

			BlockState air = Blocks.AIR.defaultBlockState();
			BlockState barrier = Blocks.BARRIER.defaultBlockState();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			placingGround = true;
			try {
				for (Ranges.Range r : ranges) {
					for (int y = Math.max(r.yBottom(), level.getMinY()); y <= Math.min(r.yTop(), level.getMaxY()); y++) {
						pos.set(r.x(), y, r.z());
						if (!level.isInWorldBounds(pos)) {
							continue;
						}

						if (place) {
							if (level.getBlockState(pos).isAir()) {
								level.setBlock(pos, barrier, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
								barriers.add(pos.immutable());
							}
						} else if (barriers.remove(pos.immutable()) && level.getBlockState(pos).is(Blocks.BARRIER)) {
							level.setBlock(pos, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
						}
					}
				}
			} finally {
				placingGround = false;
			}
		});
	}

	/** Remove every barrier we placed (e.g. when the host teleports somewhere else). */
	public static void clearSolid() {
		MinecraftServer s = server;
		if (s != null) {
			s.execute(() -> removeBarriers(s.overworld()));
		}
	}

	/** Server thread only. */
	private static void removeBarriers(final ServerLevel level) {
		placingGround = true;
		for (BlockPos pos : barriers) {
			if (level.getBlockState(pos).is(Blocks.BARRIER)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}

		placingGround = false;
		barriers.clear();
	}

	/** Run a command as the server (op). Results go to the log, not to chat (send_command_feedback is off). */
	public static void command(final String command) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			Cyberblock.LOG.info("command: {}", command);
			s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), command);
		});
	}

	/** The server, or null before it started and after it stopped. */
	public static MinecraftServer server() {
		return server;
	}

	/** Every server tick: a few block changes for the host. */
	static void tick(final MinecraftServer s) {
		if (!Cyberblock.active) {
			blockEvents.clear(); // nobody is listening: do not replay stale changes when the host comes back
			return;
		}

		blockEvents.drain(BlockEvents.PER_TICK, (pos, op) -> Cyberblock.events.accept(Wire.block(op, BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos))));
	}

	/** Server thread, from LevelChunk.setBlockState: queue the change when the host's collision is affected. */
	public static void onBlockChanged(final ServerLevel level, final BlockPos pos, final BlockState oldState, final BlockState newState) {
		if (!Cyberblock.active || level != level.getServer().overworld()) {
			return;
		}

		BlockEvents.Op op = BlockEvents.change(oldState, newState, placingGround ? BlockEvents.Cause.HOST_GROUND : BlockEvents.Cause.WORLD);
		if (op != null) {
			blockEvents.put(pos.asLong(), op);
		}
	}
}
