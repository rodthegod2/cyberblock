// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock;

import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Server-side half: Night City's collision as invisible barrier blocks, per host session (plan 2026-10-04 section 1.4), and the
 * world's block changes for every host near them. Server thread only.
 */
public final class WorldBridge {
	/** More than this in one message is a bug or an attack: it would stall the server thread. */
	private static final long MAX_CELLS = 16_384L;
	private static volatile MinecraftServer server;
	/** The barriers we placed and which hosts' ground each is (a reset only removes ours, never the player's builds). */
	private static final Claims claims = new Claims();
	/** Per host session: its share of the block changes and its projectile barriers. */
	private static final Map<UUID, Host> hosts = new HashMap<>();
	/** While placing or removing a host's own ground: those changes aren't news to any host. */
	private static boolean placingGround;
	/** The protocol's limit for one pcells message. */
	private static final int MAX_PROJECTILE_CELLS = 64;
	/** The solid blocks of the world the hosts play in (null while no world runs). */
	private static PlacedBlocks placed;
	/** The ground alignment of the world (null while no world runs): one k per world, every host uses it. */
	private static GroundAlign align;

	/** One host's state here. */
	private static final class Host {
		final BlockFeed feed = new BlockFeed();
		/** Barriers on Night City surfaces in the way of this host's Steve's projectiles, for 3 seconds. */
		final TempCells pcells = new TempCells();
	}

	private WorldBridge() {
	}

	private static Host host(final UUID owner) {
		return hosts.computeIfAbsent(owner, o -> new Host());
	}

	/** When the world has started. */
	static void attach(final MinecraftServer s) {
		server = s;
		placed = s.overworld().getDataStorage().computeIfAbsent(PlacedBlocks.TYPE);
		align = s.overworld().getDataStorage().computeIfAbsent(GroundAlign.TYPE);
		Bridge.alignK = k();
	}

	/** Before the world is saved: take our barriers out so the save is vanilla. */
	static void detach() {
		MinecraftServer s = server;
		server = null;
		for (HostSession session : HostSessions.all()) {
			session.send(Wire.blocks(true, new long[0])); // this world's blocks are leaving Night City with it
		}

		if (s != null) {
			ServerLevel level = s.overworld();
			removeBarriers(level, claims.clear());
			for (Host h : hosts.values()) {
				removeTemp(level, h.pcells.clear());
			}
		}

		claims.clear();
		hosts.clear();
		placed = null;
		align = null;
		Bridge.alignK = 0;
	}

	/** The world's alignment k (0 while none is chosen or no world runs). */
	static int k() {
		GroundAlign a = align;
		return a == null ? 0 : Math.max(a.k(), 0);
	}

	/**
	 * A host said hello (a new session, or a restart of either side): it starts again from k 0, so it hears this world's alignment
	 * at once, and gets align plus every block again in one tick once it is active.
	 */
	static void hello(final HostSession s) {
		if (server != null) {
			s.send(Wire.align(k()));
		}

		host(s.uuid()).feed.hello();
	}

	/** V stands still on the ground, out of any vehicle, and its feet are known: the ground there can be aligned to. */
	private static boolean vStands(final HostSession s) {
		return s.stands() && !s.vehicle() && Double.isFinite(s.feetCpZ());
	}

	/** Why the ground cannot be aligned to this host's V now (a lang key for /cyberblock level), or null when it can. */
	static @Nullable String levelRefused(final @Nullable HostSession s) {
		if (s == null || !s.active() || align == null) {
			return "cyberblock.level.no_host";
		}

		return vStands(s) ? null : "cyberblock.level.not_standing";
	}

	/**
	 * With levelRefused(s) null: aligns the world's ground to the floor this host's V stands on (plan retest2 R4). Every active host
	 * hears the new k and at once gets every block again (align then the reset, in this tick), so the blocks Steve placed are moved by
	 * it too; a host that is not active gets both once it is. Returns the k the world had (UNSET when none was chosen yet).
	 */
	static int level(final HostSession by, final String why) {
		double z = by.feetCpZ();
		int was = align.k();
		int k = GroundAlign.kOf(z);
		align.set(k);
		Bridge.alignK = k;
		Cyberblock.LOG.info("ground alignment: k {} -> {} (offset {} m) at Cyberpunk z {} ({})", was, k, GroundAlign.offset(k), z, why);
		for (HostSession s : HostSessions.all()) {
			if (s.active()) {
				resync(s);
			} else {
				host(s.uuid()).feed.hello();
			}
		}

		return was;
	}

	/** Starts this host's resync now: align, then the reset with the blocks nearest to its Steve first. */
	private static void resync(final HostSession s) {
		PlacedBlocks p = placed;
		if (p == null) {
			return;
		}

		long[] cells = p.snapshot();
		BlockPos at = s.player().blockPosition();
		host(s.uuid()).feed.start(k(), cells, at.getX(), at.getY(), at.getZ(), s::send);
		Cyberblock.LOG.info("block resync for {}: {} cells", s.player().getName().getString(), cells.length);
	}

	/** {"t":"ground"} from a host: barriers, only into air (or a cell another host already made one). */
	static void solid(final HostSession s, final List<Ranges.Range> ranges) {
		edit(s.uuid(), ranges, true);
	}

	/** Ranges the host no longer reports as solid: our barriers there come out unless another host still reports them. */
	static void unsolid(final HostSession s, final List<Ranges.Range> ranges) {
		edit(s.uuid(), ranges, false);
	}

	private static void edit(final UUID owner, final List<Ranges.Range> ranges, final boolean place) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		ServerLevel level = s.overworld();
		long count = 0;
		for (Ranges.Range r : ranges) {
			count += Math.max(0L, (long) Math.min(r.yTop(), level.getMaxY()) - Math.max(r.yBottom(), level.getMinY()) + 1L);
		}

		if (count > MAX_CELLS) {
			Cyberblock.LOG.warn("ignoring {} of {} cells (limit {})", place ? "ground" : "unground", count, MAX_CELLS);
			return;
		}

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

					long cell = pos.asLong();
					if (!place) {
						if (claims.release(owner, cell) && !temp(cell) && level.getBlockState(pos).is(Blocks.BARRIER)) {
							level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
						}
					} else if (claims.claimed(cell)) {
						claims.claim(owner, cell); // another host's barrier already stands there
					} else if (level.getBlockState(pos).isAir()) {
						level.setBlock(pos, barrier, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
						claims.claim(owner, cell);
					} else if (promote(cell) && level.getBlockState(pos).is(Blocks.BARRIER)) {
						claims.claim(owner, cell); // a projectile's barrier the probes now report as ground stays
					}
				}
			}
		} finally {
			placingGround = false;
		}
	}

	/** {"t":"clear"}: this host's barriers go (those no other host reports), and its projectile ones. */
	static void clearSolid(final HostSession s) {
		release(s.uuid(), false);
	}

	/** The session ended: its barriers and projectile barriers go, and its share of the block changes is forgotten. */
	static void end(final UUID owner) {
		release(owner, true);
	}

	private static void release(final UUID owner, final boolean forget) {
		MinecraftServer s = server;
		long[] orphaned = claims.releaseAll(owner);
		Host h = forget ? hosts.remove(owner) : hosts.get(owner);
		long[] temps = h == null ? new long[0] : h.pcells.clear();
		if (s != null) {
			removeBarriers(s.overworld(), orphaned);
			removeTemp(s.overworld(), temps);
		}
	}

	/** Takes out the given ground barriers (unless something else replaced them meanwhile). */
	private static void removeBarriers(final ServerLevel level, final long[] cells) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		placingGround = true;
		try {
			for (long cell : cells) {
				pos.set(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell));
				if (!temp(cell) && level.getBlockState(pos).is(Blocks.BARRIER)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}
		} finally {
			placingGround = false;
		}
	}

	/** Some host's projectile barrier stands in this cell. */
	private static boolean temp(final long cell) {
		for (Host h : hosts.values()) {
			if (h.pcells.contains(cell)) {
				return true;
			}
		}

		return false;
	}

	/** The probes made this cell ground: it is no host's projectile barrier any more. True when it was one. */
	private static boolean promote(final long cell) {
		boolean was = false;
		for (Host h : hosts.values()) {
			was |= h.pcells.promote(cell);
		}

		return was;
	}

	/**
	 * {"t":"pcells","c":[x,y,z, ...]} from an active host: Night City surfaces on the paths of its Steve's projectiles. Each becomes
	 * a barrier (only into air) for 3 seconds, so the projectile hits it. Cells outside the world are skipped.
	 */
	static void projectileCells(final HostSession session, final int[] cells) {
		MinecraftServer s = server;
		if (s == null || cells.length / 3 > MAX_PROJECTILE_CELLS || !session.active()) {
			return;
		}

		ServerLevel level = s.overworld();
		TempCells pcells = host(session.uuid()).pcells;
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
	}

	/** Takes the given projectile barriers out, unless a host reports them as ground or holds them for its own projectile. */
	private static void removeTemp(final ServerLevel level, final long[] cells) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		placingGround = true;
		try {
			for (long cell : cells) {
				pos.set(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell));
				if (!claims.claimed(cell) && !temp(cell) && level.getBlockState(pos).is(Blocks.BARRIER)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}
		} finally {
			placingGround = false;
		}
	}

	/**
	 * Run a command as the server (op): {"t":"cmd"} from a dev client's own host (LOCAL, never relayed; plan section 1.2). Results go
	 * to the log, not to chat (send_command_feedback is off). Any thread.
	 */
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

	/** Every server tick: expired projectile barriers go; per host, its resync's next message, then a few live block changes near it. */
	static void tick(final MinecraftServer s) {
		ServerLevel level = s.overworld();
		Set<UUID> live = new HashSet<>();
		for (HostSession session : HostSessions.all()) {
			UUID owner = session.uuid();
			live.add(owner);
			Host h = host(owner);
			if (h.pcells.size() > 0) {
				removeTemp(level, h.pcells.expire(s.getTickCount()));
			}

			boolean active = session.active();
			// a world (new or from before re-test 2) without an alignment gets one the first time a host's V stands in it
			GroundAlign a = align;
			if (active && a != null && a.k() == GroundAlign.UNSET && vStands(session)) {
				level(session, "first activation of " + session.player().getName().getString());
			}

			if (h.feed.resyncWanted(active)) {
				resync(session);
			}

			BlockPos at = session.player().blockPosition();
			h.feed.tick(active, at.getX(), at.getZ(), session::send);
		}

		// a session that ended without its end reaching us (it cannot, but nothing of it may stay)
		for (UUID gone : hosts.keySet().stream().filter(o -> !live.contains(o)).toList()) {
			end(gone);
		}
	}

	/** True while this player's host session is active and Steve is in the world it plays in: only then it wants to hear about it. */
	public static boolean hostSees(final ServerPlayer player) {
		return WorldModule.activeSession(player) != null && player.level() == player.level().getServer().overworld();
	}

	/**
	 * From LevelChunk.setBlockState: when a host's collision is affected, record the change (PlacedBlocks, always) and hand it to
	 * every host session (it goes now to those near it, later to those far away, and as a resync to those not active).
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
		for (HostSession session : HostSessions.all()) {
			BlockPos at = session.player().blockPosition();
			host(session.uuid()).feed.change(cell, op, session.active(), at.getX(), at.getZ());
		}
	}
}
