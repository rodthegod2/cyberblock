package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The listener tests moved with listen/dispatch to dev.cyberblock.net.HostSessionsTest (megaplan section 1.4). */
class BridgeTest {
	@Test
	void defaultsAreHarmless() {
		assertEquals(0, Bridge.perkRank.applyAsInt("iron_skin"));
		// plan retest2 section 1.6
		assertNull(Bridge.proxyAimedAt.at(Vec3.ZERO, new Vec3(0.0, 0.0, 1.0), 48.0));
		assertFalse(Bridge.hostileProxy.test(null));
		assertEquals(0, Bridge.alignK);
		// plan retest3 section 1.5
		assertFalse(Bridge.pingedProxy.test(null));
		assertDoesNotThrow(() -> Bridge.pingProxy.accept(null, 200));
		// megaplan section 1.4: owner-scoped proxy hooks
		assertNull(Bridge.proxyById.at(new UUID(1, 1), 7L));
		assertEquals(0L, Bridge.proxyIdOf.applyAsLong(null));
		assertNull(Bridge.proxyOwnerOf.apply(null));
	}
}
