package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.cyberblock.client.cyber.HackWheel;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan retest3 section 1.6, the wheel rule (F1's main cause: the remembered pick beat the chip in his hand). */
class HackWheelTest {
	private static final List<Hack> ALL = List.of(Hack.values());

	@Test
	void theChipInHandBeatsTheStoredPick() {
		assertSame(Hack.OVERHEAT, HackWheel.pick(Hack.OVERHEAT, Hack.SHORT_CIRCUIT, ALL));
		assertSame(Hack.CYBERPSYCHOSIS, HackWheel.pick(Hack.CYBERPSYCHOSIS, null, ALL));
	}

	@Test
	void thenThePickThenTheFirstChip() {
		assertSame(Hack.PING, HackWheel.pick(null, Hack.PING, ALL));
		assertSame(Hack.SHORT_CIRCUIT, HackWheel.pick(null, null, ALL));
		// a pick or a hand chip he no longer has does not count
		List<Hack> some = List.of(Hack.CONTAGION, Hack.MEMORY_WIPE);
		assertSame(Hack.CONTAGION, HackWheel.pick(Hack.PING, Hack.OVERHEAT, some));
		assertSame(Hack.MEMORY_WIPE, HackWheel.pick(Hack.PING, Hack.MEMORY_WIPE, some));
		assertNull(HackWheel.pick(Hack.PING, Hack.PING, List.of()));
	}

	@Test
	void aSlotChangeToAChipClearsThePick() {
		assertNull(HackWheel.afterSlotChange(Hack.SHORT_CIRCUIT, Hack.OVERHEAT));
		assertNull(HackWheel.afterSlotChange(Hack.SHORT_CIRCUIT, Hack.SHORT_CIRCUIT));
		// a slot without a chip keeps it
		assertSame(Hack.SHORT_CIRCUIT, HackWheel.afterSlotChange(Hack.SHORT_CIRCUIT, null));
		assertNull(HackWheel.afterSlotChange(null, null));
	}

	@Test
	void everyChipHasItsOwnColour() {
		// the lever shares the redstone red on purpose (both are redstone); every other chip is distinct
		assertEquals(HackFxRules.colour(Hack.SHORT_CIRCUIT), HackFxRules.colour(Hack.DEVICE));
		assertEquals(Hack.values().length - 1, new HashSet<>(Arrays.stream(Hack.values()).map(HackFxRules::colour).toList()).size());
		assertEquals(0xFFD34A, HackFxRules.colour(Hack.PING));
		assertEquals(0x3FD13A, HackFxRules.colour(Hack.CYBERPSYCHOSIS));
	}
}
