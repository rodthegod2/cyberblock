// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
	/** The solid blocks of the world the host plays in (server thread; null while no world runs). */
	private static PlacedBlocks placed;
	/** The resync the host gets after its hello (server thread). */
	private static final BlockSync resync = new BlockSync();
	/** A host said hello and has not gone: a world that starts now resyncs it. */
	private static volatile boolean linked;
	/** A block change the host never heard of (it was not active then): resync once it is again (server thread). */
	private static boolean missed;
	private static boolean wasActive;

	private WorldBridge() {
	}

	/** Server thread, when the world has started. */
	static void attach(final MinecraftServer s) {
		server = s;
		placed = s.overworld().getDataStorage().computeIfAbsent(PlacedBlocks.TYPE);
		if (linked) {
			startResync(s);
		}
	}

	/** Server thread, before the world is saved: take our barriers out so the save is vanilla. */
	static void detach() {
		MinecraftServer s = server;
		server = null;
		blockEvents.clear();
		resync.clear();
		placed = null;
		missed = wasActive = false;
		if (linked) {
			// this world's blocks are leaving Night City with it
			Cyberblock.events.accept(Wire.blocks(true, new long[0]));
		}

		if (s != null) {
			removeBarriers(s.overworld());
		}
	}

	/** Link thread: the host said hello (a new session, or a restart of either side): it gets every block again. */
	public static void hostHello() {
		linked = true;
		MinecraftServer s = server;
		if (s != null) {
			s.execute(() -> startResync(s));
		}
	}

	/** Link thread: the last host connection closed. */
	public static void hostGone() {
		linked = false;
		MinecraftServer s = server;
		if (s != null) {
			s.execute(resync::clear);
		}
	}

	/** Server thread: start sending every block again, nearest to Steve first. Pending live events are in the snapshot already. */
	private static void startResync(final MinecraftServer s) {
		PlacedBlocks p = placed;
		if (p == null || server != s) {
			return;
		}

		long[] cells = p.snapshot();
		List<ServerPlayer> players = s.getPlayerList().getPlayers();
		if (!players.isEmpty()) {
			BlockPos at = players.get(0).blockPosition();
			BlockSync.nearestFirst(cells, at.getX(), at.getY(), at.getZ());
		}

		blockEvents.clear();
		missed = false;
		resync.start(cells);
		Cyberblock.LOG.info("block resync: {} cells", cells.length);
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

	/** Every server tick: the resync's next message, then a few live block changes for the host (in that order). */
	static void tick(final MinecraftServer s) {
		boolean active = Cyberblock.active;
		if (active && !wasActive && missed && linked) {
			startResync(s);
		}

		wasActive = active;
		String blocks = resync.next();
		if (blocks != null) {
			Cyberblock.events.accept(blocks);
		}

		if (!active) {
			blockEvents.clear(); // nobody is listening: do not replay stale changes when the host comes back
			return;
		}

		blockEvents.drain(BlockEvents.PER_TICK, (pos, op) -> Cyberblock.events.accept(Wire.block(op, BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos))));
	}

	/** True while the host is driving the game and this is the world it plays in: the only time it wants to hear about the world. */
	public static boolean hostSees(final ServerLevel level) {
		return Cyberblock.active && level == level.getServer().overworld();
	}

	/**
	 * Server thread, from LevelChunk.setBlockState: when the host's collision is affected, record the change (PlacedBlocks, always)
	 * and queue it for the host (while it is active; otherwise it gets a resync when it is again).
	 */
	public static void onBlockChanged(final ServerLevel level, final BlockPos pos, final BlockState oldState, final BlockState newState) {
		PlacedBlocks p = placed;
		if (p == null || level != level.getServer().overworld()) {
			return;
		}

		BlockEvents.Op op = BlockEvents.change(oldState, newState, placingGround ? BlockEvents.Cause.HOST_GROUND : BlockEvents.Cause.WORLD);
		if (op == null) {
			return;
		}

		long cell = pos.asLong();
		p.apply(cell, op);
		resync.forget(cell);
		if (Cyberblock.active) {
			blockEvents.put(cell, op);
		} else {
			missed = true;
		}
	}
}
