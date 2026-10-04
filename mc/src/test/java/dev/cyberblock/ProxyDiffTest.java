package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.ProxyDiff.Entry;
import dev.cyberblock.ProxyDiff.Kind;
import dev.cyberblock.ProxyDiff.Motion;
import dev.cyberblock.ProxyDiff.Parsed;
import dev.cyberblock.ProxyDiff.Plan;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
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
		return new Entry(id, 1, 2, 3, 0, kind, false);
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
	void hostileIsReadFromTheList() {
		Parsed p = parse("[{\"id\":\"1\",\"p\":[0,0,0],\"yaw\":0,\"kind\":\"human\",\"hostile\":true},"
			+ "{\"id\":\"2\",\"p\":[0,0,0],\"yaw\":0,\"kind\":\"human\",\"hostile\":false},"
			+ "{\"id\":\"3\",\"p\":[0,0,0],\"yaw\":0,\"kind\":\"vehicle\",\"hostile\":true}]");
		assertEquals(0, p.rejected());
		assertTrue(p.entries().get(0).hostile());
		assertFalse(p.entries().get(1).hostile());
		assertTrue(p.entries().get(2).hostile());
	}

	@Test
	void hostileIsOptionalAndNeverMakesAnEntryBad() {
		for (String hostile : new String[] {"", ",\"hostile\":null", ",\"hostile\":\"true\"", ",\"hostile\":1", ",\"hostile\":[true]", ",\"hostile\":{}"}) {
			Parsed p = parse("[{\"id\":\"1\",\"p\":[0,0,0],\"yaw\":0,\"kind\":\"human\"" + hostile + "}]");
			assertEquals(0, p.rejected(), hostile);
			assertEquals(1, p.entries().size(), hostile);
			assertFalse(p.entries().get(0).hostile(), hostile);
		}
	}

	@Test
	void deadEntriesAreTheHostsLastSightingAndNotAlive() {
		Parsed p = parse("[{\"id\":\"1\",\"p\":[4,5,6],\"yaw\":10,\"kind\":\"vehicle\",\"hostile\":false,\"dead\":true},"
			+ "{\"id\":\"2\",\"p\":[0,0,0],\"yaw\":0,\"kind\":\"human\",\"hostile\":false},"
			+ "{\"id\":\"3\",\"p\":[0,0,0],\"yaw\":0,\"kind\":\"human\",\"dead\":false},"
			+ "{\"id\":\"4\",\"p\":[0,0,0],\"yaw\":0,\"kind\":\"human\",\"dead\":\"true\"},"
			+ "{\"id\":\"1\",\"p\":[0,0,0],\"yaw\":0,\"kind\":\"vehicle\"}]");
		assertEquals(1, p.rejected(), "an id once, dead or alive");
		assertEquals(Set.of(2L, 3L, 4L), ids(p.entries()), "only a JSON true is dead");
		assertEquals(1, p.dead().size());
		Entry wreck = p.dead().get(0);
		assertEquals(1L, wreck.id());
		assertEquals(Kind.VEHICLE, wreck.kind());
		assertEquals(4.0, wreck.x(), "where it died");
		assertEquals(List.of(), parse("[" + npc("5", "[0,0,0]", "0", "human") + "]").dead());
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

	private static Entry at(final double x, final double y, final double z) {
		return new Entry(1, x, y, z, 0, Kind.VEHICLE, false);
	}

	private static final long TENTH = 100_000_000L;

	@Test
	void aProxyRunsOnAtTheSpeedOfTheLastTwoLists() {
		Motion first = Motion.first(at(0, 64, 0), 0L);
		assertFalse(first.moving());
		assertEquals(new Vec3(0, 64, 0), first.position(TENTH / 2), "no speed known yet: it stays");
		// 1.5 m in a tenth of a second is 15 m/s
		Motion second = first.next(at(1.5, 64, 0), TENTH);
		assertTrue(second.moving());
		assertEquals(15.0, second.vx(), 1e-9);
		assertEquals(new Vec3(1.5, 64, 0), second.position(TENTH), "at the list's own position when it arrives");
		Vec3 half = second.position(TENTH + TENTH / 2);
		assertEquals(2.25, half.x, 1e-9);
		assertEquals(64.0, half.y, 1e-9);
		assertEquals(3.0, second.position(TENTH + TENTH).x, 1e-9, "a whole interval on: where the next list should put it");
	}

	@Test
	void speedHasAllThreeAxes() {
		Motion m = Motion.first(at(0, 64, 0), 0L).next(at(0.3, 63.6, -0.4), TENTH);
		assertEquals(3.0, m.vx(), 1e-9);
		assertEquals(-4.0, m.vy(), 1e-9);
		assertEquals(-4.0, m.vz(), 1e-9);
		Vec3 half = m.position(TENTH + TENTH / 2);
		assertEquals(0.3 + 3.0 * 0.05, half.x, 1e-9);
		assertEquals(63.6 - 4.0 * 0.05, half.y, 1e-9);
		assertEquals(-0.4 - 4.0 * 0.05, half.z, 1e-9);
	}

	@Test
	void aProxyNeverRunsOnMoreThanHalfASecondAhead() {
		Motion m = Motion.first(at(0, 64, 0), 0L).next(at(2, 64, 0), TENTH); // 20 m/s
		assertEquals(2.0 + 20.0 * 0.5, m.position(TENTH + 500_000_000L).x, 1e-9);
		assertEquals(12.0, m.position(TENTH + 5_000_000_000L).x, 1e-9, "the host went quiet: it stays where it was heading");
	}

	@Test
	void aTimeBeforeTheListIsNotExtrapolatedBackwards() {
		Motion m = Motion.first(at(0, 64, 0), 1_000L).next(at(2, 64, 0), 1_000L + TENTH);
		assertEquals(2.0, m.position(1_000L).x, 1e-9);
	}

	@Test
	void aJumpIsATeleportNotDriving() {
		Motion m = Motion.first(at(0, 64, 0), 0L).next(at(100, 64, 0), TENTH); // 1000 m/s
		assertFalse(m.moving());
		assertEquals(100.0, m.position(TENTH + TENTH).x, 1e-9, "it is simply there");
		assertTrue(Motion.first(at(0, 64, 0), 0L).next(at(4, 64, 0), TENTH).moving(), "40 m/s still drives");
		assertFalse(Motion.first(at(0, 64, 0), 0L).next(at(4.1, 64, 0), TENTH).moving(), "41 m/s does not");
	}

	@Test
	void aStoppedProxyStopsAndListsTooCloseTogetherSayNothingNew() {
		Motion running = Motion.first(at(0, 64, 0), 0L).next(at(1.5, 64, 0), TENTH);
		assertFalse(running.next(at(1.5, 64, 0), 2 * TENTH).moving(), "braked to a halt");
		Motion tooSoon = running.next(at(1.6, 64, 0), TENTH + 1_000_000L); // 1 ms later
		assertEquals(15.0, tooSoon.vx(), 1e-9, "keeps the speed it had");
		assertEquals(1.6, tooSoon.x(), 1e-9, "but is where the host put it");
		assertEquals(TENTH + 1_000_000L, tooSoon.nanos());
		assertEquals(15.0, running.next(at(1.5, 64, 0), TENTH).vx(), 1e-9, "no time passed at all");
	}

	@Test
	void theLogLineCountsPeopleAndCars() {
		assertEquals("proxies: 7 people, 3 cars (spawned 9, removed 2)", ProxyDiff.summary(7, 3, 9, 2));
		assertEquals("proxies: 0 people, 0 cars (spawned 0, removed 4)", ProxyDiff.summary(0, 0, 0, 4));
	}

	@Test
	void noProxiesAndAFullListSpawnsAll() {
		Plan plan = ProxyDiff.apply(Map.of(), List.of(entry(1, Kind.HUMAN), entry(-1, Kind.VEHICLE)));
		assertEquals(2, plan.spawn().size());
		assertTrue(plan.move().isEmpty());
		assertTrue(plan.remove().isEmpty());
	}
}
