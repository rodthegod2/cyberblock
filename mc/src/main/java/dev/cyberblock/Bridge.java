package dev.cyberblock;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Contract between the Minecraft world track (M, owns this file) and the cyber track (C). Plan 2026-10-03 section 1.6, with the
 * re-test 2 additions of plan 2026-10-03-retest2-and-cyberware section 1.6 and the re-test 3 additions of plan
 * 2026-10-03-retest3-polish section 1.5 (several listeners per type, ping).
 */
public final class Bridge {
	/** Scan pick from authoritative proxy positions (plan retest2 R2). M sets it (ProxyManager::aimedAt); call on the server thread only. */
	@FunctionalInterface
	public interface ProxyAimer {
		@Nullable Entity at(Vec3 eye, Vec3 dir, double reach);
	}
	public static volatile ProxyAimer proxyAimedAt = (eye, dir, reach) -> null;
	/** The proxy stands for a hostile NPC (synced entity data, so it is valid on the client). M sets it. */
	public static volatile Predicate<Entity> hostileProxy = e -> false;
	/** Ids (section 1.7) of the cyberware installed in the local player: an immutable set that C replaces on every change, join, respawn, and with Set.of() on disconnect. Any thread may read it. */
	public static volatile Set<String> installedCyberware = Set.of();
	/** V is in the air (the host's cam.air). M sets it. */
	public static volatile boolean hostAirborne;
	/** Host jump key presses since start (monotonic). M increments it in HostState on every jump key-down. Readers keep their own last value; nobody consumes it. */
	public static final AtomicInteger hostJumpPresses = new AtomicInteger();
	/** Ground alignment k of the attached world (0 without one). M sets it. */
	public static volatile int alignK;
	/** V's feet, Cyberpunk z (from cam.pl + lv/64); NaN before the first cam. M sets it. */
	public static volatile double hostFeetCpZ = Double.NaN;
	/** V stands still on the ground: cam !air && !held && !fly. M sets it. */
	public static volatile boolean hostStands;

	/** Host message listeners by "t" (link thread). Several listeners per type run in registration order; one that throws is logged and the rest still run. */
	public static final Map<String, List<Consumer<JsonObject>>> listeners = new ConcurrentHashMap<>(); // values are CopyOnWriteArrayList

	/** Adds a listener for host messages of this type (plan retest3 section 1.5); HostLink calls it after its own handling. */
	public static void listen(final String type, final Consumer<JsonObject> listener) {
		listeners.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>()).add(listener);
	}

	/** The proxy is pinged (Compass Ping): synced entity data, valid on the client. M sets it. */
	public static volatile Predicate<Entity> pingedProxy = e -> false;
	/** Marks a proxy pinged for `ticks` (server thread; 0 clears). M sets it. */
	public static volatile ObjIntConsumer<Entity> pingProxy = (e, ticks) -> {};
	/** Perk rank (0 = not owned) of the local player by perk id (plan section 1.5). C sets it; M reads it on the server thread. */
	public static volatile ToIntFunction<String> perkRank = id -> 0;
	/** The live proxy entity for a Cyberpunk id, or null. M sets it; server thread only. */
	public static volatile LongFunction<Entity> proxyById = id -> null;
	/** The Cyberpunk id a proxy stands for, 0 when the entity is not a proxy. M sets it; server thread only. */
	public static volatile ToLongFunction<Entity> proxyIdOf = e -> 0L;
	/** V is crouched (the host's cam.cr). M sets it. */
	public static volatile boolean hostCrouching;
	/** The game holds V (the host's cam.held: a scene, workspot, braindance or moving elevator), so V cannot be moved. M sets it. */
	public static volatile boolean hostHeld;
	/** V is in a vehicle (the host's state.vehicle). M sets it. */
	public static volatile boolean hostInVehicle;

	private Bridge() {
	}

	/** HostLink calls this for every host message (link thread). A listener that throws is logged, never fatal. */
	public static void dispatch(final String type, final JsonObject message) {
		List<Consumer<JsonObject>> list = listeners.get(type);
		if (list == null) {
			return;
		}

		for (Consumer<JsonObject> listener : list) {
			try {
				listener.accept(message);
			} catch (RuntimeException e) {
				Cyberblock.LOG.warn("listener for {} failed: {}", type, e.toString());
			}
		}
	}
}
