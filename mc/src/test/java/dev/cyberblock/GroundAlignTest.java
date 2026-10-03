package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Plan retest2 section 1.1: the ground alignment k chosen from V's feet, the same cases in every test suite. */
class GroundAlignTest {
	@ParameterizedTest
	@CsvSource({"6.907, 57", "6.001, 63", "0.0, 63", "-3.25, 47", "312.999, 63", "-0.0005, 63", "64.5, 31"})
	void theFixedCases(final double z, final int k) {
		assertEquals(k, GroundAlign.kOf(z));
	}

	/**
	 * Re-test 3 F5 ("ground alignment is perfect: keep it", plan retest3 M6): a regression pin of the whole R2 table, each case's k and
	 * where its floor lands (1.95 to 17.6 mm above a whole block). Any change of the alignment maths breaks this.
	 */
	@ParameterizedTest
	@CsvSource({"6.907, 57, 6", "6.001, 63, 5", "0.0, 63, -1", "-3.25, 47, -4", "312.999, 63, 312", "-0.0005, 63, -1", "64.5, 31, 64"})
	void retest3KeepsTheR2Alignment(final double z, final int k, final int block) {
		assertEquals(k, GroundAlign.kOf(z));
		double y = z - GroundAlign.offset(k);
		assertEquals(block, (int) Math.floor(y), "the floor stands on block " + block);
		double above = y - block;
		assertTrue(above >= 0.125 / 64.0 - 1e-9 && above < 1.125 / 64.0 + 1e-9, "z " + z + ": " + above + " m above the block");
	}

	/** The shared vectors (tools/gen_vectors.py) once they carry the align set; skipped before. */
	@Test
	void theSharedVectors() throws IOException {
		Path file = Path.of("..", "shared", "vectors.json");
		Assumptions.assumeTrue(Files.exists(file), "no shared/vectors.json");
		String text = Files.readString(file, StandardCharsets.UTF_8);
		JsonObject vectors = JsonParser.parseString(text.startsWith("﻿") ? text.substring(1) : text).getAsJsonObject();
		Assumptions.assumeTrue(vectors.has("align"), "vectors.json has no align set yet");
		int cases = 0;
		for (JsonElement c : vectors.getAsJsonArray("align")) {
			JsonObject o = c.getAsJsonObject();
			assertEquals(o.get("k").getAsInt(), GroundAlign.kOf(o.get("z").getAsDouble()), o.toString());
			cases++;
		}

		assertTrue(cases >= 7);
	}

	/**
	 * The aligned floor maps to N + e with e in [1.95 mm, 17.6 mm): the nearest-rounded barrier top is exactly N, so Steve's feet
	 * are never inside the barrier under him, and never more than 2 cm above it.
	 */
	@Test
	void theFloorLandsJustAboveAWholeBlock() {
		Random random = new Random(20261003L);
		for (int i = 0; i < 10_000; i++) {
			double z = (random.nextDouble() - 0.5) * 2000.0;
			int k = GroundAlign.kOf(z);
			assertTrue(k >= 0 && k <= 63, "k " + k);
			double y = z - GroundAlign.offset(k);
			double e = y - Math.floor(y);
			assertTrue(e >= 0.125 / 64.0 - 1e-9 && e < 1.125 / 64.0 + 1e-9, "z " + z + " k " + k + " e " + e);
			// the barrier under V (plan section 1.1 down_hit_to_range): its top face is the whole block below the feet
			int top = (int) Math.floor(y + 0.5) - 1;
			assertEquals(Math.floor(y), top + 1.0, "z " + z);
		}
	}

	@Test
	void offsetIsExact() {
		assertEquals(0.0, GroundAlign.offset(0));
		assertEquals(0.890625, GroundAlign.offset(57));
		assertEquals(63.0 / 64.0, GroundAlign.offset(63));
	}

	@Test
	void notAFiniteZ() {
		assertThrows(IllegalArgumentException.class, () -> GroundAlign.kOf(Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> GroundAlign.kOf(Double.POSITIVE_INFINITY));
	}

	@Test
	void savedDataStartsUnsetAndOnlyChangesAreDirty() {
		GroundAlign a = new GroundAlign();
		assertEquals(GroundAlign.UNSET, a.k());
		assertFalse(a.isDirty());
		a.set(57);
		assertEquals(57, a.k());
		assertTrue(a.isDirty());
		a.setDirty(false);
		a.set(57);
		assertFalse(a.isDirty(), "the same k is no change");
		assertThrows(IllegalArgumentException.class, () -> a.set(64));
		assertThrows(IllegalArgumentException.class, () -> a.set(-1));
		assertEquals(57, a.k());
	}

	@Test
	void aBadSavedValueIsUnset() {
		assertEquals(GroundAlign.UNSET, new GroundAlign(64).k());
		assertEquals(GroundAlign.UNSET, new GroundAlign(-7).k());
		assertEquals(12, new GroundAlign(12).k());
	}
}
