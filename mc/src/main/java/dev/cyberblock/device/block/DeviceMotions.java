package dev.cyberblock.device.block;

import dev.cyberblock.Cyberblock;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Multi-block devices that move in steps (the sliding gates and the lift platforms): one step of each running motion every
 * {@link #STEP_TICKS} server ticks. A new motion for the same device replaces the running one. Server thread; nothing is saved
 * (a motion cut by a restart leaves the device where it was, and the next {@code devstate} sets it again).
 */
final class DeviceMotions {
	/** A step every quarter second: a gate slides a column, a lift moves a block (4 blocks per second). */
	static final int STEP_TICKS = 5;

	@FunctionalInterface
	interface Motion {
		/** One step; false when the motion is over (arrived or blocked). */
		boolean step();
	}

	private static final Map<String, Motion> RUNNING = new LinkedHashMap<>();

	private DeviceMotions() {
	}

	static void init() {
		ServerTickEvents.END_SERVER_TICK.register(DeviceMotions::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> RUNNING.clear());
	}

	/** The key of one device's motion: its kind, level and anchor cell. */
	static String key(final String kind, final ServerLevel level, final BlockPos anchor) {
		return kind + " " + level.dimension() + " " + anchor.asLong();
	}

	static void start(final String key, final Motion motion) {
		RUNNING.put(key, motion);
	}

	static void stop(final String key) {
		RUNNING.remove(key);
	}

	static boolean running(final String key) {
		return RUNNING.containsKey(key);
	}

	private static void tick(final MinecraftServer server) {
		if (RUNNING.isEmpty() || server.getTickCount() % STEP_TICKS != 0) {
			return;
		}

		RUNNING.entrySet().removeIf(e -> {
			try {
				return !e.getValue().step();
			} catch (RuntimeException ex) {
				Cyberblock.LOG.warn("device: motion {} failed: {}", e.getKey(), ex.toString());
				return true;
			}
		});
	}
}
