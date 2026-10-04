package dev.cyberblock.client.replica;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReplicaRenderTest {
	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Megaplan 3-A1: ten materials, then neon in the 16 dye colours, all cyberblock:nc_*. */
	@Test
	void theReplicaIdsAreTheTwentySixNcBlocks() {
		List<Identifier> ids = ReplicaRender.replicaIds();
		assertEquals(26, ids.size());
		assertEquals(26, ids.stream().distinct().count());
		assertTrue(ids.stream().allMatch(id -> id.getNamespace().equals("cyberblock") && id.getPath().startsWith("nc_")));
		assertEquals(List.of("nc_concrete_light", "nc_concrete_dark", "nc_asphalt", "nc_sidewalk", "nc_glass", "nc_metal", "nc_brick",
			"nc_tile", "nc_ground", "nc_water"), ids.subList(0, 10).stream().map(Identifier::getPath).toList());
		assertTrue(ids.contains(Identifier.fromNamespaceAndPath("cyberblock", "nc_neon_light_blue")));
		assertTrue(ids.contains(Identifier.fromNamespaceAndPath("cyberblock", "nc_neon_magenta")));
		assertTrue(ids.contains(Identifier.fromNamespaceAndPath("cyberblock", "nc_neon_black")));
	}

	/** No outline on a hidden block, except for a creative player (the Q5 rule: creative may break replica blocks). */
	@Test
	void theOutlineFollowsTheQ5Rule() {
		assertTrue(ReplicaRender.outlineShown(false, false));
		assertTrue(ReplicaRender.outlineShown(false, true));
		assertFalse(ReplicaRender.outlineShown(true, false));
		assertTrue(ReplicaRender.outlineShown(true, true));
	}

	/** The view switches only after the raw state held for SWITCH_TICKS ticks; a short gap in the cam stream never re-meshes. */
	@Test
	void theViewSwitchesOnceAfterTheStateHeld() {
		int n = 0;
		for (int tick = 1; tick < ReplicaRender.SWITCH_TICKS; tick++) {
			n = ReplicaRender.debounce(false, true, n);
			assertTrue(n < ReplicaRender.SWITCH_TICKS);
		}

		assertEquals(ReplicaRender.SWITCH_TICKS, ReplicaRender.debounce(false, true, n));
		// a gap: the raw state comes back to the view before the count is full, and the count starts again
		assertEquals(0, ReplicaRender.debounce(false, false, 7));
		assertEquals(0, ReplicaRender.debounce(true, true, 0));
	}

	/** Nothing is hidden while the host view is off, whatever is registered. */
	@Test
	void nothingHidesWhileTheHostViewIsOff() {
		ReplicaRender.registerHostHidden(net.minecraft.world.level.block.Blocks.STONE);
		assertFalse(ReplicaRender.hiding());
		assertFalse(ReplicaRender.hides(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()));
	}
}
