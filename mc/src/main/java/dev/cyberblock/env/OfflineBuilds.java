package dev.cyberblock.env;

import com.mojang.brigadier.CommandDispatcher;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.replica.ChangeLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Megaplan 3-E3, the Offline Builds summary. When a host's session starts, the usual resync (WorldBridge, after each host hello)
 * sends every placed block, so what guests built while it was away is in Night City at once; this tells the host what that was:
 * "While you were away: N blocks placed, M removed (by A, B)" (a chat line the client mod also shows as a toast,
 * OfflineBuildsToast) and {@code /cyberblock changes} lists the cells by region with coordinates. The changes come from package 3R's
 * ChangeLog since the host's last session end (SavedData cyberblock_hosts); they count net per cell (placed then broken again is no
 * change). Server thread only.
 */
public final class OfflineBuilds {
	/** One logged player block change (ChangeLog's entry). */
	record Change(long pos, boolean placed, String player, long time) {
	}

	/** A cell that changed net: placed (solid now, air before) or removed (air now, solid before), and who changed it last. */
	record Cell(long pos, boolean placed, String player) {
	}

	record Summary(int placed, int removed, List<String> players, List<Cell> cells) {
	}

	/** A Minecraft region file: 512 x 512 blocks. */
	record Region(int x, int z) {
		String label() {
			return String.format(java.util.Locale.ROOT, "r.%d.%d (x %d..%d, z %d..%d)", this.x, this.z, this.x * 512, this.x * 512 + 511, this.z * 512,
				this.z * 512 + 511);
		}
	}

	static final int MAX_NAMES = 4;
	static final int MAX_COORDS = 16;
	static final int MAX_REGIONS = 8;
	/** Per host: the "away since" its current session started with (what was shown, and what /cyberblock changes lists). */
	private static final Map<UUID, Long> since = new HashMap<>();

	private OfflineBuilds() {
	}

	public static void init() {
		HostSessions.onStart(OfflineBuilds::onStart);
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> register(dispatcher));
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> since.clear());
	}

	private static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("changes").executes(c -> changes(c.getSource()))));
	}

	/** The one place this package reads 3R's ChangeLog (its changes after epochMs, oldest first). */
	private static List<Change> changesSince(final long epochMs) {
		return ChangeLog.since(epochMs).stream().map(c -> new Change(c.cell(), c.placed(), c.name(), c.time())).toList();
	}

	/** Show the summary for this absence: there was one (not a first session) and it was not shown in this session already. */
	static boolean worthShowing(final long lastEnd, final @Nullable Long shown) {
		return lastEnd > 0 && (shown == null || shown != lastEnd);
	}

	/** After each host hello: the summary of what changed since its last session ended, when anything did. */
	private static void onStart(final HostSession s) {
		long lastEnd = HostSessions.lastSessionEnd(s.uuid());
		ServerPlayer player = s.player();
		if (!worthShowing(lastEnd, since.get(s.uuid())) || player == null) {
			return;
		}

		since.put(s.uuid(), lastEnd);
		Summary sum = summarize(changesSince(lastEnd));
		if (sum.placed() + sum.removed() == 0) {
			return;
		}

		String by = names(sum.players());
		// OfflineBuildsToast matches this key and reads the three arguments: keep both
		player.sendSystemMessage(Component.translatableWithFallback("cyberblock.offline.summary", "While you were away: %s blocks placed, %s removed (by %s)",
			sum.placed(), sum.removed(), by));
		player.sendSystemMessage(Component.translatableWithFallback("cyberblock.offline.hint", "Where: %s", Component.literal("/cyberblock changes")
			.withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/cyberblock changes")))));
		Cyberblock.LOG.info("offline builds: {}: {} placed, {} removed while away (by {})", s.name(), sum.placed(), sum.removed(), by);
	}

	/** Net per cell, in log order: first and last change of each cell decide (both placed = placed, both removed = removed, else none). */
	static Summary summarize(final List<Change> changes) {
		Map<Long, Change> first = new LinkedHashMap<>(), last = new HashMap<>();
		for (Change c : changes) {
			first.putIfAbsent(c.pos(), c);
			last.put(c.pos(), c);
		}

		List<Cell> cells = new ArrayList<>();
		LinkedHashSet<String> players = new LinkedHashSet<>();
		int placed = 0, removed = 0;
		for (Change f : first.values()) {
			Change l = last.get(f.pos());
			if (f.placed() != l.placed()) {
				continue;
			}

			cells.add(new Cell(f.pos(), l.placed(), l.player()));
			players.add(l.player());
			if (l.placed()) {
				placed++;
			} else {
				removed++;
			}
		}

		return new Summary(placed, removed, List.copyOf(players), cells);
	}

	/** "A, B, C, D" and "+N" past four. */
	static String names(final List<String> players) {
		String shown = String.join(", ", players.subList(0, Math.min(MAX_NAMES, players.size())));
		return players.size() > MAX_NAMES ? shown + " +" + (players.size() - MAX_NAMES) : shown;
	}

	/** The cells by region file, west to east then north to south. */
	static Map<Region, List<Cell>> byRegion(final List<Cell> cells) {
		Map<Region, List<Cell>> out = new TreeMap<>(Comparator.comparingInt(Region::x).thenComparingInt(Region::z));
		for (Cell c : cells) {
			out.computeIfAbsent(new Region(BlockPos.getX(c.pos()) >> 9, BlockPos.getZ(c.pos()) >> 9), r -> new ArrayList<>()).add(c);
		}

		return out;
	}

	/** "x y z, x y z (+N more)". */
	static String coords(final List<Cell> cells, final int max) {
		String shown = cells.stream().limit(max).map(c -> BlockPos.getX(c.pos()) + " " + BlockPos.getY(c.pos()) + " " + BlockPos.getZ(c.pos()))
			.collect(Collectors.joining(", "));
		return cells.size() > max ? shown + " (+" + (cells.size() - max) + " more)" : shown;
	}

	/**
	 * /cyberblock changes: the cells changed since the player's last session ended (as its current session started with), by region.
	 * A player who never hosted sees the last 24 hours.
	 */
	private static int changes(final CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		Long from = player == null ? null : since.get(player.getUUID());
		long lastEnd = player == null ? 0L : HostSessions.lastSessionEnd(player.getUUID());
		long start = from != null ? from : lastEnd > 0 ? lastEnd : System.currentTimeMillis() - 24L * 3_600_000L;
		Summary sum = summarize(changesSince(start));
		long minutes = Math.max(0, (System.currentTimeMillis() - start) / 60_000L);
		String ago = minutes < 120 ? minutes + " min" : minutes / 60 + " h";
		if (sum.cells().isEmpty()) {
			source.sendSystemMessage(Component.translatableWithFallback("cyberblock.changes.none", "No blocks changed in the last %s", ago));
			return 0;
		}

		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.changes.header", "In the last %s: %s blocks placed, %s removed (by %s)", ago,
			sum.placed(), sum.removed(), names(sum.players())));
		Map<Region, List<Cell>> regions = byRegion(sum.cells());
		int n = 0;
		for (Map.Entry<Region, List<Cell>> e : regions.entrySet()) {
			if (n++ == MAX_REGIONS) {
				source.sendSystemMessage(Component.translatableWithFallback("cyberblock.changes.more", "+%s more regions", regions.size() - MAX_REGIONS));
				break;
			}

			List<Cell> placed = e.getValue().stream().filter(Cell::placed).toList();
			List<Cell> removed = e.getValue().stream().filter(c -> !c.placed()).toList();
			source.sendSystemMessage(Component.translatableWithFallback("cyberblock.changes.region", "Region %s: %s placed, %s removed", e.getKey().label(),
				placed.size(), removed.size()));
			if (!placed.isEmpty()) {
				source.sendSystemMessage(Component.translatableWithFallback("cyberblock.changes.placed", "  placed: %s", coords(placed, MAX_COORDS))
					.withStyle(ChatFormatting.GREEN));
			}

			if (!removed.isEmpty()) {
				source.sendSystemMessage(Component.translatableWithFallback("cyberblock.changes.removed", "  removed: %s", coords(removed, MAX_COORDS))
					.withStyle(ChatFormatting.RED));
			}
		}

		return sum.cells().size();
	}
}
