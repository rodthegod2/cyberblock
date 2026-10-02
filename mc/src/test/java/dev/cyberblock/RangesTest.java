package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class RangesTest {
	@Test
	void decodesQuadruples() {
		List<Ranges.Range> r = Ranges.decode(new int[] {10, -21, 3, 4, -4, 7, 12, 12});
		assertEquals(List.of(new Ranges.Range(10, -21, 3, 4), new Ranges.Range(-4, 7, 12, 12)), r);
	}

	@Test
	void rejectsBadLength() {
		assertThrows(IllegalArgumentException.class, () -> Ranges.decode(new int[] {1, 2, 3}));
	}

	@Test
	void rejectsInvertedRange() {
		assertThrows(IllegalArgumentException.class, () -> Ranges.decode(new int[] {0, 0, 5, 4}));
	}

	@Test
	void countsBlocks() {
		assertEquals(2, new Ranges.Range(0, 0, 3, 4).height());
	}
}
