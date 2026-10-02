// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
	/** Layer 1 does not tell the host about block changes (layer 2 turns this on and turns them into collision boxes). */
	private static final boolean REPORT_BLOCKS = false;
	/**
	 * What Night City's solid cells are made of: invisible barriers. -Dcyberblock.debugGround=true makes them stone, so a
	 * ground slab from a fake host can be seen in the exported frames.
	 */
	private static final Block GROUND = Boolean.getBoolean("cyberblock.debugGround") ? Blocks.STONE : Blocks.BARRIER;
	/** More than this in one message is a bug or an attack: it would stall the server thread. */
	private static final long MAX_CELLS = 16_384L;
	private static volatile MinecraftServer server;
	/** Barriers we placed (so a reset only removes ours, never the player's builds). */
	private static final Set<BlockPos> barriers = ConcurrentHashMap.newKeySet();
	/** Block changes this tick (server thread only): true = now solid, false = gone. Sent at the end of the tick. */
	private static final Map<BlockPos, Boolean> changes = new LinkedHashMap<>();
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
			BlockState ground = GROUND.defaultBlockState();
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
								level.setBlock(pos, ground, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
								barriers.add(pos.immutable());
							}
						} else if (barriers.remove(pos.immutable()) && level.getBlockState(pos).is(GROUND)) {
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
			if (level.getBlockState(pos).is(GROUND)) {
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

	private static boolean solidForHost(final ServerLevel level, final BlockPos pos, final BlockState state) {
		return !state.isAir() && !state.is(Blocks.BARRIER) && !state.getCollisionShape(level, pos).isEmpty();
	}

	/** Every server tick: block changes for the host. */
	static void tick(final MinecraftServer s) {
		flush();
	}

	/** Server thread, from Level.setBlock: remember the change; flushed once per tick. */
	public static void onBlockChanged(final ServerLevel level, final BlockPos pos, final BlockState state) {
		if (!REPORT_BLOCKS || !Cyberblock.active || level != level.getServer().overworld()) {
			return;
		}

		if (!placingGround) {
			changes.put(pos.immutable(), solidForHost(level, pos, state));
		}
	}

	/** End of each server tick: {"t":"blocks","set":[x,y,z,...],"clear":[x,y,z,...]}. */
	private static void flush() {
		if (changes.isEmpty()) {
			return;
		}

		StringBuilder set = new StringBuilder();
		StringBuilder clear = new StringBuilder();
		for (Map.Entry<BlockPos, Boolean> e : changes.entrySet()) {
			StringBuilder b = e.getValue() ? set : clear;
			BlockPos p = e.getKey();
			b.append(b.isEmpty() ? "" : ",").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
		}

		changes.clear();
		Cyberblock.events.accept("{\"t\":\"blocks\",\"set\":[" + set + "],\"clear\":[" + clear + "]}");
	}
}
