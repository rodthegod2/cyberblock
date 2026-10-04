// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock;

import com.google.gson.JsonObject;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.replica.NightCityMap;
import dev.cyberblock.replica.Palette;
import dev.cyberblock.replica.ReplicaBlocks;
import dev.cyberblock.replica.ReplicaRules;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Server-side half: Night City as replica blocks (megaplan task 3-R1; before Phase 3, invisible barriers) from every host session's
 * probes (plan 2026-10-04 section 1.4), and the world's block changes for every host near them. The replica is saved with the world
 * and grows as anyone explores: only an unground of cells seen empty takes blocks out; leaving the probe radius, a clear and a session
 * end keep them. Barriers remain only for the projectile cells (pcells). Server thread only.
 */
public final class WorldBridge {
	/** More than this in one message is a bug or an attack: it would stall the server thread. */
	private static final long MAX_CELLS = 16_384L;
	private static volatile MinecraftServer server;
	/** Which hosts report each cell as ground right now (inside their probe radius); the replica blocks themselves stay. */
	private static final Claims claims = new Claims();
	/** Per host session: its share of the block changes and its projectile barriers. */
	private static final Map<UUID, Host> hosts = new HashMap<>();
	/** While placing or removing a host's own ground: those changes aren't news to any host. */
	private static boolean placingGround;
	/**
	 * Replica blocks a creative player broke, until they come back (plan Q-B6, megaplan 3-R1): the snap-back or a re-report puts
	 * the block back; an unground of the cell seen empty means Night City has nothing there any more.
	 */
	private static final OutlineGuard guard = new OutlineGuard();
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
		/** Where this session's replica materials come from: "probes" (ground.m) or "heuristic"; null before its first ground. */
		@Nullable String materialPath;
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

	/** Before the world is saved: take our projectile barriers out (the replica blocks are saved with the world). */
	static void detach() {
		MinecraftServer s = server;
		server = null;
		for (HostSession session : HostSessions.all()) {
			session.send(Wire.blocks(true, new long[0])); // this world's blocks are leaving Night City with it
		}

		if (s != null) {
			ServerLevel level = s.overworld();
			for (Host h : hosts.values()) {
				removeTemp(level, h.pcells.clear());
			}
		}

		claims.clear();
		guard.clear();
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
			if (!s.active() || !resync(s)) {
				host(s.uuid()).feed.hello();
			}
		}

		return was;
	}

	/**
	 * Starts this host's resync now: align, then the reset with the blocks nearest to its Steve first. False when it cannot start (no
	 * world, or the host's player is not online this tick).
	 */
	private static boolean resync(final HostSession s) {
		PlacedBlocks p = placed;
		ServerPlayer player = s.player();
		if (p == null || player == null) {
			return false;
		}

		long[] cells = p.snapshot();
		BlockPos at = player.blockPosition();
		host(s.uuid()).feed.start(k(), cells, at.getX(), at.getY(), at.getZ(), s::send);
		Cyberblock.LOG.info("block resync for {}: {} cells", player.getName().getString(), cells.length);
		return true;
	}

	/**
	 * {"t":"ground"} from a host: replica blocks into air cells only (never over a player's block), the material from ground.m through
	 * the palette, else the heuristic (megaplan 3-R1). The session's first ground logs which path is active, and again once probes
	 * start giving materials.
	 */
	static void solid(final HostSession s, final JsonObject m) {
		List<Ranges.Range> ranges = Ranges.decode(ints(m));
		int[] codes = ReplicaRules.codes(m, ranges.size());
		String path = ReplicaRules.materialPath(codes);
		Host h = host(s.uuid());
		if (h.materialPath == null || "probes".equals(path) && !path.equals(h.materialPath)) {
			h.materialPath = path;
			Cyberblock.LOG.info("replica: materials from {} ({})", path, s.name());
		}

		edit(s, ranges, codes, true, false);
	}

	/**
	 * Ranges the host no longer reports as solid: its claims go; the replica blocks there come out only when the cells were seen empty
	 * (unground.seen) and no other host still reports them. Cells that only left the probe radius stay.
	 */
	static void unsolid(final HostSession s, final JsonObject m) {
		edit(s, Ranges.decode(ints(m)), null, false, ReplicaRules.seen(m));
	}

	private static int[] ints(final JsonObject m) {
		var a = m.getAsJsonArray("c");
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsInt();
		}

		return out;
	}

	private static void edit(final HostSession session, final List<Ranges.Range> ranges, final int @Nullable [] codes, final boolean place,
		final boolean seen) {
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

		UUID owner = session.uuid();
		ServerPlayer player = session.player();
		String district = session.district();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int restored = 0;
		placingGround = true;
		try {
			for (int i = 0; i < ranges.size(); i++) {
				Ranges.Range r = ranges.get(i);
				int code = codes == null ? ReplicaBlocks.UNKNOWN : codes[i];
				// the street the host's Steve stands on is the heuristic's reference (no player this tick: the range's own top)
				int refY = player != null ? player.getBlockY() : r.yTop() + 1;
				int top = NightCityMap.top(r.x(), r.z());
				boolean water = NightCityMap.water(r.x(), r.z());
				for (int y = Math.max(r.yBottom(), level.getMinY()); y <= Math.min(r.yTop(), level.getMaxY()); y++) {
					pos.set(r.x(), y, r.z());
					if (!level.isInWorldBounds(pos)) {
						continue;
					}

					long cell = pos.asLong();
					BlockState state = level.getBlockState(pos);
					if (!place) {
						claims.release(owner, cell);
						boolean othersClaim = claims.claimed(cell);
						if (ReplicaRules.ungroundRemoves(seen, ReplicaBlocks.is(state), othersClaim)) {
							level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
						}

						if (seen && !othersClaim) {
							guard.unclaimed(cell); // seen empty: a broken replica block has nothing to come back to
						}

						continue;
					}

					// a broken replica block the probes report again comes back now, whatever its decision (plan Q-B6)
					boolean wasBroken = guard.reported(cell);
					boolean excluded = ReplicaRules.excluded(cell), temp = temp(cell);
					// the spawn platform's barriers (wb-left) yield to Night City: the replica takes the cell over
					boolean air = state.isAir() || !excluded && !temp && state.is(Blocks.BARRIER) && SafeSpots.releaseSpawnPlatformCell(pos.immutable());
					switch (ReplicaRules.onGround(excluded, air, temp && state.is(Blocks.BARRIER))) {
						case PLACE -> {
							level.setBlock(pos, replica(Palette.material(code, r, y, refY, top, water, district)), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
							claims.claim(owner, cell);
							restored += wasBroken ? 1 : 0;
						}
						case PROMOTE -> {
							promote(cell); // a projectile's barrier the probes now report as ground: Night City is there
							level.setBlock(pos, replica(Palette.material(code, r, y, refY, top, water, district)), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
							claims.claim(owner, cell);
						}
						case CLAIM -> claims.claim(owner, cell); // a player's block or a replica block already stands there
						case SKIP -> {
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
	}

	/** The replica block of a material code (a barrier only if the blocks were never registered, which a running game never sees). */
	private static BlockState replica(final int material) {
		Block block = ReplicaBlocks.block(material);
		return block == null ? Blocks.BARRIER.defaultBlockState() : block.defaultBlockState();
	}

	/** {"t":"clear"}: this host's claims and projectile barriers go; the replica blocks stay (megaplan 3-R1). */
	static void clearSolid(final HostSession s) {
		release(s.uuid(), false);
	}

	/** The session ended: its claims and projectile barriers go, and its share of the block changes is forgotten; the replica stays. */
	static void end(final UUID owner) {
		release(owner, true);
	}

	private static void release(final UUID owner, final boolean forget) {
		MinecraftServer s = server;
		claims.releaseAll(owner);
		Host h = forget ? hosts.remove(owner) : hosts.get(owner);
		long[] temps = h == null ? new long[0] : h.pcells.clear();
		if (s != null) {
			removeTemp(s.overworld(), temps);
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
	 * Broken replica blocks whose 10 s are up come back (plan Q-B6, megaplan 3-R1), into air with no player inside the cell. A player
	 * standing on top of it (feet on its top face, give or take a little: Steve stands where V stands) does not count: the block comes
	 * back under him, as Night City's ground is there.
	 */
	private static void snapBack(final ServerLevel level, final long now) {
		Long2IntMap due = guard.dueMaterials(now, cell -> level.getBlockState(BlockPos.of(cell)).isAir(),
			cell -> level.getEntitiesOfClass(Player.class, new AABB(BlockPos.of(cell)).deflate(0.1)).isEmpty());
		if (due.isEmpty()) {
			return;
		}

		placingGround = true;
		try {
			for (Long2IntMap.Entry e : due.long2IntEntrySet()) {
				level.setBlock(BlockPos.of(e.getLongKey()), replica(e.getIntValue()), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		} finally {
			placingGround = false;
		}

		Cyberblock.LOG.info("outline: restored {}", due.size());
	}

	/** Which of our outline blocks this is (before it was broken): a replica block, a barrier of ours, or null. */
	private static OutlineRules.@Nullable Kind outlineKind(final ServerLevel level, final BlockPos pos, final BlockState state) {
		MinecraftServer s = server;
		if (s == null || level != s.overworld()) {
			return null;
		}

		if (ReplicaBlocks.is(state)) {
			return OutlineRules.Kind.REPLICA;
		}

		if (!state.is(Blocks.BARRIER)) {
			return null;
		}

		long cell = pos.asLong();
		if (claims.claimed(cell)) {
			return OutlineRules.Kind.OUTLINE_BARRIER;
		}

		return temp(cell) ? OutlineRules.Kind.PROJECTILE_BARRIER : null;
	}

	/** Before a player breaks a block (PlayerBlockBreakEvents.BEFORE): false refuses the break (plan Q-B4). */
	public static boolean mayBreak(final ServerLevel level, final ServerPlayer player, final BlockPos pos, final BlockState state) {
		OutlineRules.Kind kind = outlineKind(level, pos, state);
		return kind == null || OutlineRules.decide(player.gameMode(), OutlineRules.creativeErases(), kind) != OutlineRules.Decision.DENY;
	}

	/**
	 * After a player broke a block (PlayerBlockBreakEvents.AFTER; state is what it was). A replica block (or an outline barrier): only
	 * the Minecraft block went (BlockEvents.change sends nothing for it: Cyberpunk hears nothing, Night City never changes); the
	 * snap-back or a re-report puts it back (permanent erase: only a re-report), an unground of the cell seen empty forgets it.
	 */
	public static void broken(final ServerLevel level, final ServerPlayer player, final BlockPos pos, final BlockState state) {
		BlockPos cell = pos.immutable();
		OutlineRules.Kind kind = outlineKind(level, cell, state);
		if (kind == OutlineRules.Kind.PROJECTILE_BARRIER) {
			promote(cell.asLong()); // forgotten: it is not ours to take out when its 3 s are up
			return;
		}

		if (kind == null) {
			return;
		}

		OutlineRules.Decision decision = OutlineRules.decide(player.gameMode(), OutlineRules.creativeErases(), kind);
		long now = level.getServer().getTickCount();
		guard.broke(cell.asLong(), now, decision, ReplicaBlocks.material(state));
		SafeSpots.outlineBroke(cell, now);
		Cyberblock.LOG.info("outline: {} broke {} {} {} ({})", player.getName().getString(), cell.getX(), cell.getY(), cell.getZ(),
			decision == OutlineRules.Decision.KEEP ? "kept: permanent erase" : "snap back in 10 s");
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

			// the session's DOWN queue dropped messages (plan section 1.3): its blocks need a resync, from its next active tick
			if (session.takeResync()) {
				h.feed.hello();
			}

			ServerPlayer player = session.player();
			if (player == null) {
				continue; // not online this tick (a respawn edge, a revoke before its end): it is picked up again once it is
			}

			boolean active = session.active();
			// a world (new or from before re-test 2) without an alignment gets one the first time a host's V stands in it
			GroundAlign a = align;
			if (active && a != null && a.k() == GroundAlign.UNSET && vStands(session)) {
				level(session, "first activation of " + player.getName().getString());
			}

			if (h.feed.resyncWanted(active)) {
				resync(session);
			}

			BlockPos at = player.blockPosition();
			h.feed.tick(active, at.getX(), at.getZ(), session::send);
		}

		if (guard.size() > 0) {
			snapBack(level, s.getTickCount());
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
			ServerPlayer player = session.player();
			BlockFeed feed = host(session.uuid()).feed;
			if (player == null) {
				feed.hello(); // not online this tick, so not routable by distance: every block again once it is back and active
			} else {
				BlockPos at = player.blockPosition();
				feed.change(cell, op, session.active(), at.getX(), at.getZ());
			}
		}
	}
}
