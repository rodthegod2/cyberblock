// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

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
	/** Outline blocks a creative player broke, until they come back (server thread only). */
	private static final OutlineGuard guard = new OutlineGuard();
	/** The protocol's limit for one pcells message. */
	private static final int MAX_PROJECTILE_CELLS = 64;
	/** Barriers on Night City surfaces in the way of Steve's projectiles, for 3 seconds (server thread only). */
	private static final TempCells pcells = new TempCells();
	/** The solid blocks of the world the host plays in (server thread; null while no world runs). */
	private static PlacedBlocks placed;
	/** The resync the host gets after its hello (server thread). */
	private static final BlockSync resync = new BlockSync();
	/** A host said hello and has not gone: a world that starts now resyncs it. */
	private static volatile boolean linked;
	/**
	 * A resync is owed (a hello, or a world started while linked): it starts on the first server tick that sees the host active, so
	 * it is not sent while the host is still loading or paused and cannot drain it.
	 */
	private static volatile boolean resyncDue;
	/** A block change the host never heard of (it was not active then): resync once it is again (server thread). */
	private static boolean missed;
	private static boolean wasActive;
	/** The ground alignment of the world the host plays in (server thread; null while no world runs). */
	private static GroundAlign align;
	/**
	 * Held while Bridge.alignK changes and its align message goes out, and while a host hello's align goes out (link thread): the
	 * host never ends up with an older k than ours.
	 */
	private static final Object ALIGN_LOCK = new Object();

	private WorldBridge() {
	}

	/** Server thread, when the world has started. */
	static void attach(final MinecraftServer s) {
		server = s;
		placed = s.overworld().getDataStorage().computeIfAbsent(PlacedBlocks.TYPE);
		align = s.overworld().getDataStorage().computeIfAbsent(GroundAlign.TYPE);
		synchronized (ALIGN_LOCK) {
			Bridge.alignK = Math.max(align.k(), 0);
			if (linked) {
				// before the resync, so the host places this world's blocks with this world's alignment
				Cyberblock.events.accept(Wire.align(Bridge.alignK));
			}
		}

		if (linked) {
			resyncDue = true;
		}
	}

	/** Server thread, before the world is saved: take our barriers out so the save is vanilla. */
	static void detach() {
		MinecraftServer s = server;
		server = null;
		blockEvents.clear();
		resync.clear();
		placed = null;
		align = null;
		Bridge.alignK = 0;
		missed = wasActive = false;
		if (linked) {
			// this world's blocks are leaving Night City with it
			Cyberblock.events.accept(Wire.blocks(true, new long[0]));
		}

		if (s != null) {
			removeBarriers(s.overworld());
		}
	}

	/**
	 * Link thread: the host said hello (a new session, or a restart of either side): it starts again from k 0, so it hears this
	 * world's alignment at once, and gets every block again once it is active.
	 */
	public static void hostHello() {
		linked = true;
		synchronized (ALIGN_LOCK) {
			if (server != null) {
				Cyberblock.events.accept(Wire.align(Bridge.alignK));
			}
		}

		resyncDue = true;
	}

	/** A host said hello and has not gone. */
	public static boolean linked() {
		return linked;
	}

	/** V stands still on the ground, out of any vehicle, and its feet are known: the ground there can be aligned to. */
	private static boolean vStands() {
		return Bridge.hostStands && !Bridge.hostInVehicle && Double.isFinite(Bridge.hostFeetCpZ);
	}

	/**
	 * Server thread: why the ground cannot be aligned to V now (a lang key for /cyberblock level), or null when it can.
	 */
	static String levelRefused() {
		if (!Cyberblock.active || !linked || align == null) {
			return "cyberblock.level.no_host";
		}

		return vStands() ? null : "cyberblock.level.not_standing";
	}

	/**
	 * Server thread, with levelRefused() null: aligns the world's ground to the floor V stands on (plan retest2 R4). The host hears
	 * the new k and at once gets every block again (a reset resync), so the blocks Steve placed are moved by it too. Returns the k
	 * the world had (UNSET when none was chosen yet).
	 */
	static int level(final MinecraftServer s, final String why) {
		double z = Bridge.hostFeetCpZ;
		int was = align.k();
		int k = GroundAlign.kOf(z);
		align.set(k);
		synchronized (ALIGN_LOCK) {
			Bridge.alignK = k;
			Cyberblock.events.accept(Wire.align(k));
		}

		Cyberblock.LOG.info("ground alignment: k {} -> {} (offset {} m) at Cyberpunk z {} ({})", was, k, GroundAlign.offset(k), z, why);
		// the blocks follow on this thread, before any other block message: the reset first
		resyncDue = false;
		startResync(s);
		String first = resync.next();
		if (first != null) {
			Cyberblock.events.accept(first);
		}

		return was;
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
			int restored = 0;
			placingGround = true;
			try {
				for (Ranges.Range r : ranges) {
					for (int y = Math.max(r.yBottom(), level.getMinY()); y <= Math.min(r.yTop(), level.getMaxY()); y++) {
						pos.set(r.x(), y, r.z());
						if (!level.isInWorldBounds(pos)) {
							continue;
						}

						if (place) {
							// a broken outline block the probes report again comes back now, whatever its decision (plan Q-B6)
							boolean wasBroken = guard.reported(pos.asLong());
							if (level.getBlockState(pos).isAir()) {
								level.setBlock(pos, barrier, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
								barriers.add(pos.immutable());
								restored += wasBroken ? 1 : 0;
							} else if (pcells.promote(pos.asLong()) && level.getBlockState(pos).is(Blocks.BARRIER)) {
								barriers.add(pos.immutable()); // a projectile's barrier the probes now report as ground stays
							}
						} else {
							guard.unclaimed(pos.asLong());
							if (barriers.remove(pos.immutable()) && level.getBlockState(pos).is(Blocks.BARRIER)) {
								level.setBlock(pos, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
							}
						}
					}
				}
			} finally {
				placingGround = false;
			}

			if (restored > 0) {
				Cyberblock.LOG.info("outline: restored {}", restored);
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

	/** Server thread only: the ground barriers and the projectiles' ones. */
	private static void removeBarriers(final ServerLevel level) {
		placingGround = true;
		for (BlockPos pos : barriers) {
			if (level.getBlockState(pos).is(Blocks.BARRIER)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}

		placingGround = false;
		barriers.clear();
		guard.clear();
		removeTemp(level, pcells.clear());
	}

	/**
	 * Link thread: {"t":"pcells","c":[x,y,z, ...]}: Night City surfaces on the paths of Steve's projectiles. Each becomes a barrier
	 * (only into air) for 3 seconds, so the projectile hits it. Cells outside the world are skipped.
	 */
	public static void projectileCells(final int[] cells) {
		MinecraftServer s = server;
		if (s == null || cells.length / 3 > MAX_PROJECTILE_CELLS) {
			return;
		}

		s.execute(() -> {
			if (!Cyberblock.active) {
				return;
			}

			ServerLevel level = s.overworld();
			long now = s.getTickCount();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			placingGround = true;
			try {
				for (int i = 0; i + 2 < cells.length; i += 3) {
					pos.set(cells[i], cells[i + 1], cells[i + 2]);
					if (!level.isInWorldBounds(pos)) {
						continue;
					}

					long cell = pos.asLong();
					if (pcells.contains(cell)) {
						pcells.put(cell, now); // still on a path: it stays longer
					} else if (level.getBlockState(pos).isAir()) {
						level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
						pcells.put(cell, now);
					}
				}
			} finally {
				placingGround = false;
			}
		});
	}

	/** Server thread: broken outline blocks whose 10 s are up come back (plan Q-B6), into air with no player in the cell. */
	private static void snapBack(final ServerLevel level, final long now) {
		long[] due = guard.due(now, cell -> level.getBlockState(BlockPos.of(cell)).isAir(),
			cell -> level.getEntitiesOfClass(Player.class, new AABB(BlockPos.of(cell))).isEmpty());
		if (due.length == 0) {
			return;
		}

		placingGround = true;
		try {
			for (long cell : due) {
				BlockPos pos = BlockPos.of(cell);
				level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				barriers.add(pos);
			}
		} finally {
			placingGround = false;
		}

		Cyberblock.LOG.info("outline: restored {}", due.length);
	}

	/** Server thread: takes the given projectile barriers out (unless something else replaced them meanwhile). */
	private static void removeTemp(final ServerLevel level, final long[] cells) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		placingGround = true;
		try {
			for (long cell : cells) {
				pos.set(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell));
				if (level.getBlockState(pos).is(Blocks.BARRIER)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}
		} finally {
			placingGround = false;
		}
	}

	/** Server thread: which of our outline blocks this is (before it was broken), or null when it is not one of ours. */
	private static OutlineRules.Kind outlineKind(final ServerLevel level, final BlockPos pos, final BlockState state) {
		MinecraftServer s = server;
		if (s == null || level != s.overworld() || !state.is(Blocks.BARRIER)) {
			return null;
		}

		if (barriers.contains(pos)) {
			return OutlineRules.Kind.OUTLINE_BARRIER;
		}

		return pcells.contains(pos.asLong()) ? OutlineRules.Kind.PROJECTILE_BARRIER : null;
	}

	/** Server thread, before a player breaks a block (PlayerBlockBreakEvents.BEFORE): false refuses the break (plan Q-B4). */
	public static boolean mayBreak(final ServerLevel level, final ServerPlayer player, final BlockPos pos, final BlockState state) {
		OutlineRules.Kind kind = outlineKind(level, pos.immutable(), state);
		return kind == null || OutlineRules.decide(player.gameMode(), OutlineRules.creativeErases(), kind) != OutlineRules.Decision.DENY;
	}

	/**
	 * Server thread, after a player broke a block (PlayerBlockBreakEvents.AFTER; state is what it was). An outline block: only the
	 * Minecraft block went (BlockEvents.change sends nothing for barrier to air: Cyberpunk hears nothing), and the cell leaves our
	 * barrier set, so a later unground does not try to remove it.
	 */
	public static void broken(final ServerLevel level, final ServerPlayer player, final BlockPos pos, final BlockState state) {
		BlockPos cell = pos.immutable();
		OutlineRules.Kind kind = outlineKind(level, cell, state);
		if (kind == OutlineRules.Kind.PROJECTILE_BARRIER) {
			pcells.promote(cell.asLong()); // forgotten: it is not ours to take out when its 3 s are up
			return;
		}

		if (kind != OutlineRules.Kind.OUTLINE_BARRIER) {
			return;
		}

		barriers.remove(cell);
		OutlineRules.Decision decision = OutlineRules.decide(player.gameMode(), OutlineRules.creativeErases(), kind);
		long now = level.getServer().getTickCount();
		guard.broke(cell.asLong(), now, decision);
		SafeSpots.outlineBroke(cell, now);
		Cyberblock.LOG.info("outline: {} broke {} {} {} ({})", player.getName().getString(), cell.getX(), cell.getY(), cell.getZ(),
			decision == OutlineRules.Decision.KEEP ? "kept: permanent erase" : "snap back in 10 s");
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

	/** Every server tick: expired projectile barriers go; the resync's next message, then a few live block changes for the host. */
	static void tick(final MinecraftServer s) {
		if (pcells.size() > 0) {
			removeTemp(s.overworld(), pcells.expire(s.getTickCount()));
		}

		if (guard.size() > 0) {
			snapBack(s.overworld(), s.getTickCount());
		}

		boolean active = Cyberblock.active;
		// a world (new or from before re-test 2) without an alignment gets one the first time V stands in it
		GroundAlign a = align;
		if (active && linked && a != null && a.k() == GroundAlign.UNSET && vStands()) {
			level(s, "first activation");
		}

		if (active && linked && (resyncDue || !wasActive && missed)) {
			resyncDue = false;
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
