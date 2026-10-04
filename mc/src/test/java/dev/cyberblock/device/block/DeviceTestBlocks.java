package dev.cyberblock.device.block;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;

/**
 * Device blocks for tests, as ReplicaTestBlocks does it: after Bootstrap froze the block registry a new Block cannot be made the
 * normal way, so for the construction only the registry is unfrozen with a holder map, then put back. Never registered.
 */
final class DeviceTestBlocks {
	private static final Map<String, Block> MADE = new HashMap<>();

	private DeviceTestBlocks() {
	}

	static synchronized Block block(final String name) {
		Block made = MADE.get(name);
		if (made != null) {
			return made;
		}

		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		try {
			Field holders = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
			holders.setAccessible(true);
			Field frozen = MappedRegistry.class.getDeclaredField("frozen");
			frozen.setAccessible(true);
			Object was = holders.get(BuiltInRegistries.BLOCK);
			holders.set(BuiltInRegistries.BLOCK, new IdentityHashMap<>());
			frozen.set(BuiltInRegistries.BLOCK, false);
			try {
				made = DeviceBlocks.create(name);
			} finally {
				frozen.set(BuiltInRegistries.BLOCK, true);
				holders.set(BuiltInRegistries.BLOCK, was);
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}

		MADE.put(name, made);
		return made;
	}
}
