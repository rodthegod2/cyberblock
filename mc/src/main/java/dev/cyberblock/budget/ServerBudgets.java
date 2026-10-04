package dev.cyberblock.budget;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import java.util.Locale;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * The server's tick budgets (megaplan task 9-F1): replica placements (the cells of hosts' ground, unground and clear) and device updates
 * (devices, devstate, devresult) each get at most so much work a tick; what does not fit waits for the next ticks in its host's order
 * (TickQueue), so a host's whole street arriving at once spreads over a few ticks instead of stalling one. The DOWN queue's budget is
 * DownQueue.PER_TICK (net). Every REPORT_TICKS a line says what had to wait, when anything did. Server thread only.
 */
public final class ServerBudgets {
	/** Replica cells placed or released a tick, over every host (a host's relay budget is 20000 cells a second: 1000 a tick). */
	public static final int REPLICA_CELLS_PER_TICK = 4096;
	/** Device updates a tick, over every host: a `devices` list counts its entries, a devstate or devresult 1. */
	public static final int DEVICE_UPDATES_PER_TICK = 64;
	/** Past this many waiting messages a host's next one runs at once (the budget never grows a queue without end). */
	static final int MAX_QUEUED = 512;
	static final int REPORT_TICKS = 1200;
	private static final TickQueue replica = new TickQueue(REPLICA_CELLS_PER_TICK, MAX_QUEUED, e -> failed("replica", e));
	private static final TickQueue devices = new TickQueue(DEVICE_UPDATES_PER_TICK, MAX_QUEUED, e -> failed("device", e));
	private static boolean registered;

	private ServerBudgets() {
	}

	/** A replica handler (ground, unground, clear) under the replica budget; its units are the message's cells. */
	public static BiConsumer<HostSession, JsonObject> replica(final BiConsumer<HostSession, JsonObject> handler) {
		register();
		return (s, m) -> {
			int cells = cells(m);
			replica.offer(s.uuid(), cells, () -> {
				handler.accept(s, m);
				return cells;
			});
		};
	}

	/** A device handler (devices, devstate, devresult) under the device budget; a list counts its entries, any other message 1. */
	public static BiConsumer<HostSession, JsonObject> devices(final BiConsumer<HostSession, JsonObject> handler) {
		register();
		return (s, m) -> {
			int updates = entries(m);
			devices.offer(s.uuid(), updates, () -> {
				handler.accept(s, m);
				return updates;
			});
		};
	}

	/** The cells a ground or unground covers (the sum of its ranges' heights, at least 1; a malformed one counts 1: its handler drops it). */
	static int cells(final JsonObject m) {
		JsonElement c = m.get("c");
		if (c == null || !c.isJsonArray() || c.getAsJsonArray().size() % 4 != 0) {
			return 1;
		}

		JsonArray a = c.getAsJsonArray();
		long sum = 0L;
		for (int i = 0; i + 3 < a.size(); i += 4) {
			JsonElement bottom = a.get(i + 2), top = a.get(i + 3);
			if (!number(bottom) || !number(top)) {
				return 1;
			}

			sum += Math.max(0L, (long) top.getAsDouble() - (long) bottom.getAsDouble() + 1L);
		}

		return (int) Math.clamp(sum, 1L, 1_000_000L);
	}

	/** A `devices` list's entries (at least 1), 1 for any other message. */
	static int entries(final JsonObject m) {
		JsonElement list = m.get("list");
		return list != null && list.isJsonArray() ? Math.max(1, list.getAsJsonArray().size()) : 1;
	}

	private static boolean number(final JsonElement e) {
		return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && Double.isFinite(e.getAsDouble());
	}

	private static synchronized void register() {
		if (registered) {
			return;
		}

		registered = true;
		ServerTickEvents.START_SERVER_TICK.register(ServerBudgets::tick);
		HostSessions.onEnd(s -> {
			replica.drop(s.uuid());
			devices.drop(s.uuid());
		});
		ServerLifecycleEvents.SERVER_STARTED.register(s -> Cyberblock.LOG.info("budget: replica placements {} cells a tick, device updates {} a tick, "
			+ "DOWN {} messages a tick per host", REPLICA_CELLS_PER_TICK, DEVICE_UPDATES_PER_TICK, HostSessions.DOWN_PER_TICK));
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			replica.clear();
			devices.clear();
		});
	}

	/** The start of every server tick: the budgets come back and what waited runs first. */
	private static void tick(final MinecraftServer server) {
		replica.tick();
		devices.tick();
		if (server.getTickCount() % REPORT_TICKS == 0) {
			String line = report(replica.takeStats(), devices.takeStats(), HostSessions.takeDownStats());
			if (line != null) {
				Cyberblock.LOG.info(line);
			}
		}
	}

	/** The minute's line, null when nothing had to wait. Pure. */
	static @Nullable String report(final long[] r, final long[] d, final long[] down) {
		if (r[0] + d[0] + down[0] == 0L) {
			return null;
		}

		return String.format(Locale.ROOT, "budget: last 60 s: replica %d messages (%d cells) waited (longest queue %d), devices %d messages "
			+ "(%d updates) waited (longest queue %d), DOWN %d messages held to the next tick (most sent in one tick %d)", r[0], r[1], r[2], d[0], d[1],
			d[2], down[0], down[1]);
	}

	private static void failed(final String what, final RuntimeException e) {
		Cyberblock.LOG.warn("budget: a waiting {} message failed: {}", what, e.toString());
	}
}
