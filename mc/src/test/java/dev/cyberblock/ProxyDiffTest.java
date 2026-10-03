package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.ProxyDiff.Entry;
import dev.cyberblock.ProxyDiff.Kind;
import dev.cyberblock.ProxyDiff.Parsed;
import dev.cyberblock.ProxyDiff.Plan;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProxyDiffTest {
	private static final double MIN_Y = -512;
	private static final double MAX_Y = 1535;

	private static JsonObject message(final String list) {
		return JsonParser.parseString("{\"t\":\"npcs\",\"list\":" + list + "}").getAsJsonObject();
	}

	private static Parsed parse(final String list) {
		return ProxyDiff.parse(message(list), MIN_Y, MAX_Y);
	}

	private static String npc(final String id, final String p, final String yaw, final String kind) {
		return "{\"id\":\"" + id + "\",\"p\":" + p + ",\"yaw\":" + yaw + ",\"kind\":\"" + kind + "\",\"hostile\":false}";
	}

	private static Entry entry(final long id, final Kind kind) {
		return new Entry(id, 1, 2, 3, 0, kind);
	}

	private static Set<Long> ids(final List<Entry> entries) {
		Set<Long> out = new HashSet<>();
		entries.forEach(e -> out.add(e.id()));
		return out;
	}

	@Test
	void parsesTheProtocolExample() {
		Parsed p = parse("[" + npc("9007199254740993", "[-1243.0,64.9,808.5]", "90.0", "human") + "," + npc("18446744073709551615", "[1,2,3]", "-45", "vehicle") + "]");
		assertEquals(0, p.rejected());
		assertEquals(2, p.entries().size());
		Entry a = p.entries().get(0);
		assertEquals(9007199254740993L, a.id());
		assertEquals(-1243.0, a.x());
		assertEquals(64.9, a.y());
		assertEquals(808.5, a.z());
		assertEquals(90.0F, a.yaw());
		assertEquals(Kind.HUMAN, a.kind());
		Entry b = p.entries().get(1);
		assertEquals(-1L, b.id(), "2^64-1 is a valid unsigned id (it is -1 as a signed long)");
		assertEquals("18446744073709551615", Long.toUnsignedString(b.id()));
		assertEquals(Kind.VEHICLE, b.kind());
	}

	@Test
	void anEmptyListIsValidButAMissingOneIsNot() {
		assertEquals(0, parse("[]").entries().size());
		assertNull(ProxyDiff.parse(JsonParser.parseString("{\"t\":\"npcs\"}").getAsJsonObject(), MIN_Y, MAX_Y));
		assertNull(ProxyDiff.parse(JsonParser.parseString("{\"t\":\"npcs\",\"list\":5}").getAsJsonObject(), MIN_Y, MAX_Y));
		assertNull(ProxyDiff.parse(JsonParser.parseString("{\"t\":\"npcs\",\"list\":null}").getAsJsonObject(), MIN_Y, MAX_Y));
	}

	@Test
	void badIdsAreSkipped() {
		for (String id : new String[] {"", "abc", "-1", "+5", "1.5", "18446744073709551616", "999999999999999999999", " 7", "0x10"}) {
			Parsed p = parse("[" + npc(id, "[0,0,0]", "0", "human") + "]");
			assertEquals(0, p.entries().size(), "id " + id);
			assertEquals(1, p.rejected(), "id " + id);
		}
	}

	@Test
	void anIdMustBeAStringNotANumber() {
		Parsed p = parse("[{\"id\":5,\"p\":[0,0,0],\"yaw\":0,\"kind\":\"human\"}]");
		assertEquals(1, p.rejected());
	}

	@Test
	void badNumbersAreSkipped() {
		for (String bad : new String[] {"[\"1\",0,0]", "[null,0,0]", "[0,0]", "[0,0,0,0]", "[1e999,0,0]", "[0,0,1e999]", "[3e7,0,0]", "[0,0,-3e7]", "{}", "5"}) {
			Parsed p = parse("[" + npc("1", bad, "0", "human") + "]");
			assertEquals(1, p.rejected(), "p " + bad);
		}

		assertEquals(1, parse("[" + npc("1", "[0,0,0]", "1e999", "human") + "]").rejected());
		assertEquals(1, parse("[" + npc("1", "[0,0,0]", "\"90\"", "human") + "]").rejected());
	}

	@Test
	void nanAndInfinityLiteralsAreSkippedToo() {
		// Gson's parser is lenient: these arrive as numbers
		for (String bad : new String[] {"[NaN,0,0]", "[0,NaN,0]", "[0,0,Infinity]", "[-Infinity,0,0]"}) {
			assertEquals(1, parse("[" + npc("1", bad, "0", "human") + "]").rejected(), bad);
		}

		assertEquals(1, parse("[" + npc("1", "[0,0,0]", "NaN", "human") + "]").rejected());
	}

	@Test
	void heightsOutsideTheWorldAreSkipped() {
		assertEquals(1, parse("[" + npc("1", "[0,-512.5,0]", "0", "human") + "]").rejected());
		assertEquals(1, parse("[" + npc("1", "[0,1536,0]", "0", "human") + "]").rejected());
		assertEquals(0, parse("[" + npc("1", "[0,-512,0]", "0", "human") + "]").rejected());
		assertEquals(0, parse("[" + npc("1", "[0,1535,0]", "0", "human") + "]").rejected());
	}

	@Test
	void unknownKindsAndNonObjectsAreSkipped() {
		assertEquals(1, parse("[" + npc("1", "[0,0,0]", "0", "tank") + "]").rejected());
		assertEquals(1, parse("[" + npc("1", "[0,0,0]", "0", "") + "]").rejected());
		Parsed p = parse("[7, null, \"x\", [], {}]");
		assertEquals(5, p.rejected());
		assertEquals(0, p.entries().size());
	}

	@Test
	void goodEntriesSurviveBadNeighbours() {
		Parsed p = parse("[" + npc("1", "[0,0,0]", "0", "human") + "," + npc("x", "[0,0,0]", "0", "human") + "," + npc("3", "[0,0,0]", "0", "vehicle") + "]");
		assertEquals(Set.of(1L, 3L), ids(p.entries()));
		assertEquals(1, p.rejected());
	}

	@Test
	void aRepeatedIdKeepsTheFirst() {
		Parsed p = parse("[" + npc("1", "[10,0,0]", "0", "human") + "," + npc("1", "[20,0,0]", "0", "human") + "]");
		assertEquals(1, p.entries().size());
		assertEquals(10.0, p.entries().get(0).x());
		assertEquals(1, p.rejected());
	}

	@Test
	void moreThanTheCapIsCutOff() {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < ProxyDiff.MAX_ENTRIES + 10; i++) {
			sb.append(i == 0 ? "" : ",").append(npc(Integer.toString(i), "[0,0,0]", "0", "human"));
		}

		Parsed p = parse(sb.append("]").toString());
		assertEquals(ProxyDiff.MAX_ENTRIES, p.entries().size());
		assertEquals(10, p.rejected());
	}

	@Test
	void yawIsReducedToAFiniteAngle() {
		assertEquals(10.0F, parse("[" + npc("1", "[0,0,0]", "370", "human") + "]").entries().get(0).yaw());
		assertEquals(-90.0F, parse("[" + npc("1", "[0,0,0]", "-90", "human") + "]").entries().get(0).yaw());
	}

	@Test
	void newIdsSpawnKnownIdsMoveMissingIdsGo() {
		Map<Long, Kind> current = new HashMap<>(Map.of(1L, Kind.HUMAN, 2L, Kind.HUMAN, 3L, Kind.VEHICLE));
		Plan plan = ProxyDiff.apply(current, List.of(entry(2, Kind.HUMAN), entry(3, Kind.VEHICLE), entry(4, Kind.HUMAN)));
		assertEquals(Set.of(4L), ids(plan.spawn()));
		assertEquals(Set.of(2L, 3L), ids(plan.move()));
		assertEquals(List.of(1L), plan.remove());
	}

	@Test
	void anIdThatChangesKindIsReplaced() {
		Plan plan = ProxyDiff.apply(Map.of(1L, Kind.HUMAN), List.of(entry(1, Kind.VEHICLE)));
		assertEquals(List.of(1L), plan.remove());
		assertEquals(Set.of(1L), ids(plan.spawn()));
		assertTrue(plan.move().isEmpty());
	}

	@Test
	void anEmptyListRemovesEveryone() {
		Plan plan = ProxyDiff.apply(Map.of(1L, Kind.HUMAN, 2L, Kind.VEHICLE), List.of());
		assertEquals(Set.of(1L, 2L), new HashSet<>(plan.remove()));
		assertTrue(plan.spawn().isEmpty());
		assertTrue(plan.move().isEmpty());
	}

	@Test
	void noProxiesAndAFullListSpawnsAll() {
		Plan plan = ProxyDiff.apply(Map.of(), List.of(entry(1, Kind.HUMAN), entry(-1, Kind.VEHICLE)));
		assertEquals(2, plan.spawn().size());
		assertTrue(plan.move().isEmpty());
		assertTrue(plan.remove().isEmpty());
	}
}
