package dev.cyberblock;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import net.minecraft.world.entity.Entity;

/** Contract between the Minecraft world track (M, owns this file) and the cyber track (C). Plan 2026-10-03 section 1.6. */
public final class Bridge {
	/** Host message listeners by "t": HostLink calls the one registered for a message's type after its own handling, on the link thread. */
	public static final Map<String, Consumer<JsonObject>> listeners = new ConcurrentHashMap<>();
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
		Consumer<JsonObject> listener = listeners.get(type);
		if (listener != null) {
			try {
				listener.accept(message);
			} catch (RuntimeException e) {
				Cyberblock.LOG.warn("listener for {} failed: {}", type, e.toString());
			}
		}
	}
}
