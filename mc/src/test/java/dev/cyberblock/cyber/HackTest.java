package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class HackTest {
	/** Plan 2026-10-03 section 1.5, copied by hand: item id, hack wire value, RAM cost. */
	private static final List<String> PLAN = List.of(
		"redstone_charge short_circuit 3",
		"blaze_chip overheat 4",
		"ink_sac_daemon reboot_optics 3",
		"cobweb_daemon cripple_movement 2",
		"spider_eye_virus contagion 4",
		"wither_skull_daemon synapse_burnout 6",
		"compass_ping ping 1",
		"bottle_forgetting memory_wipe 3",
		"creeper_brain cyberpsychosis 8",
		"redstone_lever device 1"
	);

	@Test
	void tableMatchesThePlanOneToOne() {
		List<String> table = Arrays.stream(Hack.values()).map(h -> h.item() + " " + h.wire() + " " + h.ram()).toList();
		assertEquals(PLAN, table);
	}

	@Test
	void longCooldownsOnlyForTheHeavyHacks() {
		for (Hack h : Hack.values()) {
			boolean heavy = h == Hack.SYNAPSE_BURNOUT || h == Hack.CYBERPSYCHOSIS;
			assertEquals(heavy ? 200 : 40, h.cooldownTicks(), h.name());
		}
	}

	@Test
	void onlyTheLeverNeedsADevice() {
		assertTrue(Hack.DEVICE.needsDevice());
		assertEquals(1, Arrays.stream(Hack.values()).filter(Hack::needsDevice).count());
	}

	@Test
	void byWire() {
		assertSame(Hack.MEMORY_WIPE, Hack.byWire("memory_wipe"));
		assertNull(Hack.byWire("MEMORY_WIPE"));
		assertNull(Hack.byWire("blackwall"));
	}
}
