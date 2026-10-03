package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Plan retest3 section 1.6 (Minecraft phases) and section 1.1 (`hackresult` upload/kind, `hackspread`). */
class HackFxRulesTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void impactLengthsAreTheTables() {
		int[] ticks = {40, 100, 80, 120, 120, 40, 200, 80, 120, 40};
		for (Hack hack : Hack.values()) {
			assertEquals(ticks[hack.ordinal()], HackFxRules.impactTicks(hack), hack.name());
		}
	}

	@Test
	void everyChipSoundsItsOwnPitch() {
		Set<Float> pitches = new HashSet<>();
		for (Hack hack : Hack.values()) {
			float p = HackFxRules.pitch(hack);
			assertTrue(p >= 0.5F && p <= 2.0F, hack.name());
			pitches.add(p);
		}

		assertEquals(Hack.values().length, pitches.size());
	}

	@Test
	void uploadIsClampedToThirtySeconds() {
		assertEquals(0.0, HackFxRules.clampUpload(-3.0));
		assertEquals(1.5, HackFxRules.clampUpload(1.5));
		assertEquals(30.0, HackFxRules.clampUpload(99.0));
		assertEquals(0.0, HackFxRules.clampUpload(Double.NaN));
		assertEquals(0.0, HackFxRules.clampUpload(Double.POSITIVE_INFINITY));
		assertEquals(30, HackFxRules.uploadTicks(1.5));
		assertEquals(600, HackFxRules.uploadTicks(1.0E9));
		assertEquals(0, HackFxRules.uploadTicks(0.0));
	}

	@Test
	void websGoIntoTheFeetCellItsFourNeighboursAndTheHead() {
		BlockPos feet = new BlockPos(10, 64, -3);
		List<BlockPos> cells = HackFxRules.webCells(feet);
		assertEquals(List.of(feet, new BlockPos(10, 64, -4), new BlockPos(10, 64, -2), new BlockPos(11, 64, -3),
			new BlockPos(9, 64, -3), new BlockPos(10, 65, -3)), cells);
	}

	@Test
	void noWebNearAPlayer() {
		BlockPos cell = new BlockPos(0, 64, 0);
		// a player box 0.6 wide standing right next to the cell: refused
		AABB beside = new AABB(1.2, 64.0, 0.2, 1.8, 65.8, 0.8);
		assertFalse(HackFxRules.webAllowed(cell, List.of(beside)));
		// two blocks away from the cell centre: allowed
		AABB away = new AABB(2.1, 64.0, 0.2, 2.7, 65.8, 0.8);
		assertTrue(HackFxRules.webAllowed(cell, List.of(away)));
		assertTrue(HackFxRules.webAllowed(cell, List.of()));
		assertFalse(HackFxRules.webAllowed(cell, List.of(away, beside)));
	}

	@Test
	void theCreeperMaskIsEightByEight() {
		assertEquals(8, HackFxRules.CREEPER_MASK.size());
		int pixels = 0;
		for (String row : HackFxRules.CREEPER_MASK) {
			assertEquals(8, row.length());
			assertTrue(row.matches("[#.]+"), row);
			pixels += (int) row.chars().filter(c -> c == '#').count();
		}

		assertEquals(pixels, HackFxRules.maskPixels(0.1).size());
		// centred: offsets run -0.35 .. 0.35
		for (double[] p : HackFxRules.maskPixels(0.1)) {
			assertTrue(Math.abs(p[0]) <= 0.35 + 1.0E-9 && Math.abs(p[1]) <= 0.35 + 1.0E-9);
		}
	}

	@Test
	void beamPointsRunToTheTargetAndAreCapped() {
		List<Vec3> points = HackFxRules.beamPoints(Vec3.ZERO, new Vec3(0.0, 0.0, 3.0));
		assertEquals(6, points.size());
		assertEquals(new Vec3(0.0, 0.0, 0.5), points.getFirst());
		assertEquals(new Vec3(0.0, 0.0, 3.0), points.getLast());
		assertEquals(HackFxRules.BEAM_MAX, HackFxRules.beamPoints(Vec3.ZERO, new Vec3(500.0, 0.0, 0.0)).size());
		assertTrue(HackFxRules.beamPoints(Vec3.ZERO, Vec3.ZERO).isEmpty());
	}

	@Test
	void hackresultCarriesUploadAndKind() {
		HackResult r = HackResult.parse(json("{\"t\":\"hackresult\",\"id\":4,\"ok\":true,\"reason\":\"\",\"upload\":1.25,\"kind\":\"crowd\"}"));
		assertNotNull(r);
		assertEquals(1.25, r.upload());
		assertEquals("crowd", r.kind());
		// absent: instant and no kind (older DLLs)
		HackResult old = HackResult.parse(json("{\"id\":6,\"ok\":true}"));
		assertNotNull(old);
		assertEquals(0.0, old.upload());
		assertEquals("", old.kind());
		// clamped, and a bad field is dropped alone
		assertEquals(30.0, HackResult.parse(json("{\"id\":1,\"ok\":true,\"upload\":120}")).upload());
		assertEquals(0.0, HackResult.parse(json("{\"id\":1,\"ok\":true,\"upload\":\"soon\"}")).upload());
		assertEquals("", HackResult.parse(json("{\"id\":1,\"ok\":true,\"kind\":\"robot\"}")).kind());
		assertEquals("", HackResult.parse(json("{\"id\":1,\"ok\":true,\"kind\":3}")).kind());
		for (String kind : HackFxRules.KINDS) {
			assertEquals(kind, HackResult.parse(json("{\"id\":1,\"ok\":true,\"kind\":\"" + kind + "\"}")).kind());
		}

		assertEquals(7, HackFxRules.KINDS.size());
	}

	@Test
	void hackspreadParse() {
		HackSpread s = HackSpread.parse(json("{\"t\":\"hackspread\",\"id\":9,\"target\":\"18446744073709551615\",\"why\":\"ping\"}"));
		assertNotNull(s);
		assertEquals(9, s.id());
		assertEquals(-1L, s.target()); // the unsigned decimal id, as ScanInfo reads it
		assertEquals("ping", s.why());
		for (String why : List.of("chain", "spread", "ping", "frenzy")) {
			assertNotNull(HackSpread.parse(json("{\"id\":1,\"target\":\"7\",\"why\":\"" + why + "\"}")), why);
		}

		assertNull(HackSpread.parse(json("{\"id\":1,\"target\":\"7\",\"why\":\"virus\"}")));
		assertNull(HackSpread.parse(json("{\"id\":-1,\"target\":\"7\",\"why\":\"ping\"}")));
		assertNull(HackSpread.parse(json("{\"id\":1,\"target\":\"0\",\"why\":\"ping\"}")));
		assertNull(HackSpread.parse(json("{\"id\":1,\"target\":\"x\",\"why\":\"ping\"}")));
		assertNull(HackSpread.parse(json("{\"id\":1,\"why\":\"ping\"}")));
		assertNull(HackSpread.parse(json("{\"id\":1,\"target\":\"7\"}")));
	}
}
