package dev.cyberblock.replica;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Replica block states for tests. A unit test runs after Bootstrap froze the block registry, so a new Block cannot be made the normal
 * way (its constructor asks the frozen registry for an intrusive holder): for the construction only, the registry is unfrozen with a
 * holder map, then put back as it was. The blocks are never registered; tests only need their states and classes.
 */
public final class ReplicaTestBlocks {
	private static final Block[] MADE = new Block[ReplicaBlocks.CODES];

	private ReplicaTestBlocks() {
	}

	public static synchronized BlockState state(final int code) {
		if (MADE[code] == null) {
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
					MADE[code] = ReplicaBlocks.create(code);
				} finally {
					frozen.set(BuiltInRegistries.BLOCK, true);
					holders.set(BuiltInRegistries.BLOCK, was);
				}
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}

		return MADE[code].defaultBlockState();
	}
}
